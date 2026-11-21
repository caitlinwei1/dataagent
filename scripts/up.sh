#!/usr/bin/env bash
# One-command bring-up of gbi-agent. Every step is idempotent: what already exists is reused.
#
#   1 meta DB (MySQL 8, saa_data_agent)        5 build gbi-agent -> run/gbi-agent.jar
#   2 sample datasources (MySQL :13306, PG)    6 start backend :8065 (Milvus profile, Docker sandbox)
#   3 Milvus standalone :19530                 7 register + activate DashScope chat / embedding models
#   4 Python sandbox image + dataset load      8 register the 电商经营分析 agent (86 tables, ~12 min of embeddings)
#
# Needs docker, JDK 17 + Maven, python3 with pyyaml + requests, and DASHSCOPE_API_KEY in the environment.
# Optional: CHAT_MODEL (default qwen-plus), EMBED_MODEL (default qwen3.7-text-embedding),
#           MODEL_PROXY=host:port if outbound HTTPS from the JVM must go through a proxy,
#           SECURITY=on (+ GBI_JWT_SECRET, GBI_GATEWAY_SIGN_SECRET) for step 9: gateway, auth service, data permission.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DASHSCOPE_API_KEY:?export DASHSCOPE_API_KEY first}"
API=http://localhost:8065
CHAT_MODEL=${CHAT_MODEL:-qwen-plus}
EMBED_MODEL=${EMBED_MODEL:-qwen3.7-text-embedding}
LOG=results/backend.log
step() { printf '\n== %s\n' "$*"; }

step "1/8 meta DB"
docker start da-meta >/dev/null 2>&1 || docker run -d --name da-meta \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=saa_data_agent -p 3306:3306 mysql:8.0
until docker exec da-meta mysqladmin ping -uroot -proot --silent 2>/dev/null; do sleep 2; done
FIRST_RUN=$(docker exec da-meta mysql -uroot -proot -N -e \
  "select count(*) from information_schema.tables where table_schema='saa_data_agent'" 2>/dev/null)

step "2/8 datasources"
docker compose -f upstream/dataagent-b297203/docker-file/docker-compose-datasource.yml up -d

step "3/8 Milvus"
docker compose -f deploy/milvus/docker-compose.yml up -d

step "4/8 sandbox image + dataset"
docker image inspect gbi-python-sandbox:0.1 >/dev/null 2>&1 || docker build -t gbi-python-sandbox:0.1 sandbox/
until docker exec data-agent-mysql-datasource mysqladmin ping -uroot -proot --silent 2>/dev/null; do sleep 2; done
LOADED=$(docker exec data-agent-mysql-datasource mysql -uroot -proot -N -e \
  "select count(*) from information_schema.tables where table_schema='gbi_ecommerce'" 2>/dev/null)
[ "$LOADED" -ge 86 ] && echo "gbi_ecommerce already loaded ($LOADED tables)" || scripts/load_dataset.sh

step "5/8 build"
mkdir -p run results
if [ ! -f run/gbi-agent.jar ]; then
  (cd gbi-agent && mvn -B -q -DskipTests -Dspotless.apply.skip=true -Dspotless.check.skip=true package)
  cp gbi-agent/target/gbi-agent-1.0.0-SNAPSHOT.jar run/gbi-agent.jar
fi

step "6/8 backend"
if curl -sf $API/api/agent/list >/dev/null; then
  echo "backend already running on :8065"
else
  INIT=never; [ "$FIRST_RUN" = "0" ] && INIT=always   # create the meta tables on the very first start
  : > "$LOG"
  DATA_AGENT_DATASOURCE_SQL_INIT=$INIT setsid nohup java -jar run/gbi-agent.jar --spring.profiles.active=milvus \
    --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
    --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1 >> "$LOG" 2>&1 < /dev/null &
  until curl -sf $API/api/agent/list >/dev/null; do
    grep -q "APPLICATION FAILED" "$LOG" && { tail -40 "$LOG"; exit 1; }; sleep 2; done
  echo "backend up, log: $LOG"
fi

step "7/8 models"
python3 - "$API" "$CHAT_MODEL" "$EMBED_MODEL" <<'EOF'
import os, sys, requests
api, chat, embed = sys.argv[1] + "/api/model-config", sys.argv[2], sys.argv[3]
items = requests.get(api + "/list", timeout=30).json()
items = items.get("data", items) if isinstance(items, dict) else items
proxy = os.environ.get("MODEL_PROXY")
for kind, name, extra in [("CHAT", chat, {"temperature": 0, "maxTokens": 4000, "completionsPath": "/v1/chat/completions"}),
                          ("EMBEDDING", embed, {"embeddingsPath": "/v1/embeddings"})]:
    have = [m for m in items if m.get("modelType") == kind]
    if any(m.get("isActive") for m in have):
        print(kind, "already active:", [m["modelName"] for m in have if m.get("isActive")][0])
        continue
    body = dict(provider="dashscope", baseUrl="https://dashscope.aliyuncs.com/compatible-mode",
                apiKey=os.environ["DASHSCOPE_API_KEY"], modelName=name, modelType=kind, **extra)
    if proxy:
        host, port = proxy.rsplit(":", 1)
        body.update(proxyEnabled=True, proxyHost=host, proxyPort=int(port))
    assert requests.post(api + "/add", json=body, timeout=30).json().get("success"), kind
    items = requests.get(api + "/list", timeout=30).json()
    items = items.get("data", items) if isinstance(items, dict) else items
    mid = [m for m in items if m.get("modelType") == kind and m.get("modelName") == name][-1]["id"]
    assert requests.post(f"{api}/activate/{mid}", timeout=30).json().get("success"), kind
    print(kind, "registered and activated:", name)   # the key itself is never printed
EOF

step "8/8 agent"
if curl -s $API/api/agent/list | grep -q '"电商经营分析"'; then
  echo "agent 电商经营分析 already registered"
else
  python3 scripts/setup_agent.py --api $API
fi

if [ "${SECURITY:-off}" = on ]; then
  # Microservice mode (RuoYi-Cloud layout): gbi-gateway :8080 is the only entry, gbi-auth :9200 issues tokens,
  # gbi-agent accepts only gateway-signed requests. Steps 7-8 above ran unauthenticated against :8065 on purpose
  # (bootstrap); from here on the agent needs the gateway.
  step "9 security: redis, gbi_system, gbi-auth, gbi-gateway, backend with the security profile"
  : "${GBI_JWT_SECRET:?export GBI_JWT_SECRET (64+ bytes) for SECURITY=on}"
  : "${GBI_GATEWAY_SIGN_SECRET:?export GBI_GATEWAY_SIGN_SECRET for SECURITY=on}"
  docker start da-redis >/dev/null 2>&1 || docker run -d --name da-redis -p 6379:6379 redis:7 >/dev/null
  docker exec da-meta mysql -uroot -proot -N -e "select 1 from information_schema.schemata where schema_name='gbi_system'" \
    2>/dev/null | grep -q 1 || docker exec -i da-meta mysql --default-character-set=utf8mb4 -uroot -proot < ruoyi/sql/gbi_system.sql
  if [ ! -f run/gbi-auth.jar ] || [ ! -f run/gbi-gateway.jar ]; then
    (cd ruoyi && mvn -B -q -DskipTests package)
    cp ruoyi/gbi-auth/target/gbi-auth-1.0.0-SNAPSHOT.jar run/gbi-auth.jar
    cp ruoyi/gbi-gateway/target/gbi-gateway-1.0.0-SNAPSHOT.jar run/gbi-gateway.jar
  fi
  for svc in auth:9200 gateway:8080; do
    name=${svc%%:*}; port=${svc##*:}
    curl -sf localhost:$port/actuator/health >/dev/null || \
      setsid nohup java -jar run/gbi-$name.jar > results/gbi-$name.log 2>&1 < /dev/null &
  done
  if ! curl -s -o /dev/null -w '%{http_code}' $API/api/agent/list | grep -q 401; then
    pkill -f run/gbi-agent.jar || true
    while curl -sf $API/echo/ok >/dev/null; do sleep 1; done
    : >> "$LOG"
    setsid nohup java -jar run/gbi-agent.jar --spring.profiles.active=milvus,security \
      --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
      --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1 >> "$LOG" 2>&1 < /dev/null &
  fi
  until curl -sf localhost:9200/actuator/health >/dev/null && curl -sf localhost:8080/actuator/health >/dev/null \
    && curl -sf $API/echo/ok >/dev/null; do sleep 2; done
  API=http://localhost:8080
  echo "gateway :8080 -> auth :9200 / agent :8065 (direct calls to :8065 now get 401)"
  echo "login: curl -s -XPOST $API/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"east_ops\",\"password\":\"Gbi@2026\"}'"
fi

cat <<EOF

Ready.
  report API   : POST $API/openapi/v1/reports   (docs: $API/swagger-ui.html)
  chat UI flow : POST $API/api/stream/search
  evals        : see README.md "评测"
EOF
