#!/usr/bin/env bash
# Restart the backend from the saved vector store WITHOUT the startup re-embed, then re-run chosen configs.
# usage: rerun_configs.sh <tag:agent> <configs>   e.g. rerun_configs.sh full:5 "+term,+value"
set -u
cd "$(dirname "$0")/../.."
LOG=results/step3/rerun.log
M() { docker exec da-meta mysql -uroot -proot --default-character-set=utf8mb4 saa_data_agent -N -e "$1" 2>/dev/null; }
pgrep dockerd >/dev/null || (sudo dockerd > /tmp/dockerd.log 2>&1 &)
until docker info >/dev/null 2>&1; do sleep 1; done
docker start data-agent-mysql-datasource data-agent-postgres-datasource da-meta >/dev/null
until M "select 1" >/dev/null; do sleep 2; done
P=$(echo "$HTTPS_PROXY" | sed 's/.*://;s/\/.*//')
M "update model_config set proxy_port=$P where proxy_enabled=1"
if ! pgrep -f run/gbi-agent.jar >/dev/null; then
  # startup auto-init re-embeds every published agent whose agentId has no vectors (schema docs carry only
  # datasourceId), so hide them during startup; the saved store already holds their schema vectors
  PUB=$(M "select group_concat(id) from agent where status='published'")
  M "update agent set status='draft' where id in ($PUB)"
  setsid nohup java -jar run/gbi-agent.jar --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
    --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1 > results/step3/backend3.log 2>&1 < /dev/null &
  until grep -qE "No published agents found|Agent initialization completed" results/step3/backend3.log; do sleep 3; done
  M "update agent set status='published' where id in ($PUB)"
fi
for ta in $1; do t=${ta%%:*}; a=${ta##*:}
  curl -s -XPOST "localhost:8065/api/eval/schema-recall/invalidate?agentId=$a" >/dev/null
  echo "$(date -u +%T) $t agent $a: $2" >> $LOG
  python3 eval/scripts/run_table_select.py --agent $a --tag $t --configs "$2" >> $LOG 2>&1
done
echo "$(date -u +%T) done" >> $LOG
