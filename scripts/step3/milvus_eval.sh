#!/usr/bin/env bash
# Run gbi-agent on the Milvus vector store: embed both agents once (startup auto-init), load the business-knowledge
# vectors, run the 180-question recall eval, then restart and check that nothing is re-embedded.
set -u
cd "$(dirname "$0")/../.."
LOG=results/step3/milvus.log; BLOG=results/step3/backend_milvus.log
M() { docker exec da-meta mysql -uroot -proot --default-character-set=utf8mb4 saa_data_agent -N -e "$1" 2>/dev/null; }
pgrep dockerd >/dev/null || (sudo dockerd > /tmp/dockerd.log 2>&1 &)
until docker info >/dev/null 2>&1; do sleep 1; done
docker start data-agent-mysql-datasource data-agent-postgres-datasource da-meta >/dev/null
docker compose -f deploy/milvus/docker-compose.yml up -d >/dev/null 2>&1
until [ "$(docker inspect -f '{{.State.Health.Status}}' gbi-milvus)" = healthy ]; do sleep 3; done
until M "select 1" >/dev/null; do sleep 2; done
P=$(echo "$HTTPS_PROXY" | sed 's/.*://;s/\/.*//')
M "update model_config set proxy_port=$P where proxy_enabled=1"
start() {
  pkill -TERM -f run/gbi-agent.jar; while pgrep -f run/gbi-agent.jar >/dev/null; do sleep 1; done
  : > $BLOG
  setsid nohup java -jar run/gbi-agent.jar --spring.profiles.active=milvus \
    --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
    --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1 > $BLOG 2>&1 < /dev/null &
  until grep -qE "Agent initialization completed|APPLICATION FAILED" $BLOG; do sleep 5; done
  grep -E "Agent initialization completed|already has table vectors|Initializing agent|APPLICATION FAILED" $BLOG | cut -c1-200 >> $LOG
}
echo "$(date -u +%T) first start (embeds into Milvus)" >> $LOG
start
for a in 5 6; do
  echo "$(date -u +%T) knowledge agent $a: $(curl -s -m 1800 -XPOST "localhost:8065/api/business-knowledge/refresh-vector-store?agentId=$a")" >> $LOG
done
echo "$(date -u +%T) second start (should skip embedding)" >> $LOG
start
python3 eval/scripts/run_table_select.py --agent 5 --tag milvus_full >> $LOG 2>&1
python3 eval/scripts/run_table_select.py --agent 6 --tag milvus_partial >> $LOG 2>&1
echo "$(date -u +%T) done" >> $LOG
