#!/usr/bin/env bash
# Wait for the embedding API, bring the stack up, make sure both agents are embedded, run Recall@5 for full + partial.
set -u
cd "$(dirname "$0")/../.."
LOG=results/step3/resume.log
EMB=${EMB:-qwen3.7-text-embedding}   # the -flash variant hit "free tier only" on this account
emb_ok() { curl -s -m 30 https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings -H "Authorization: Bearer $DASHSCOPE_API_KEY" \
  -H 'content-type: application/json' -d '{"model":"qwen3.7-text-embedding","input":"ok"}' | grep -q '"embedding"'; }
until emb_ok; do echo "$(date -u +%T) embedding still blocked" >> $LOG; sleep 120; done
echo "$(date -u +%T) embedding OK" >> $LOG
pgrep dockerd >/dev/null || (sudo dockerd > /tmp/dockerd.log 2>&1 &)
until docker info >/dev/null 2>&1; do sleep 1; done
docker start data-agent-mysql-datasource data-agent-postgres-datasource da-meta >/dev/null
until docker exec da-meta mysql -uroot -proot -e "select 1" >/dev/null 2>&1; do sleep 2; done
until docker exec data-agent-mysql-datasource mysql -uroot -proot -e "select 1" >/dev/null 2>&1; do sleep 2; done
P=$(echo "$HTTPS_PROXY" | sed 's/.*://;s/\/.*//')
docker exec da-meta mysql -uroot -proot saa_data_agent -e "update model_config set proxy_port=$P where proxy_enabled=1; update model_config set model_name='$EMB' where model_type='EMBEDDING' and is_active=1" 2>/dev/null
if ! pgrep -f run/gbi-agent.jar >/dev/null; then
  nohup java -jar run/gbi-agent.jar --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
    --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1 > results/step3/backend.log 2>&1 &
  until grep -q "Agent initialization completed. Success" results/step3/backend.log; do sleep 10; done
fi
cnt() { python3 -c "
import json,sys
d=json.load(open('vectorstore/vectorstore.json'))
print(sum(1 for v in d.values() if v['metadata'].get('datasourceId')=='$1' and v['metadata'].get('vectorType')=='table'))"; }
curl -s -XPOST localhost:8065/api/eval/vectorstore/save >/dev/null
for pair in "5 3" "6 4"; do set -- $pair
  if [ "$(cnt $2)" != "86" ]; then echo "$(date -u +%T) init agent $1" >> $LOG
    curl -s -m 3600 -XPOST localhost:8065/api/agent/$1/datasources/init >> $LOG; echo >> $LOG
    curl -s -XPOST localhost:8065/api/eval/vectorstore/save >/dev/null; fi
done
echo "$(date -u +%T) tables ds3=$(cnt 3) ds4=$(cnt 4)" >> $LOG
curl -s -XPOST "localhost:8065/api/eval/schema-recall/invalidate?agentId=5" >/dev/null
curl -s -XPOST "localhost:8065/api/eval/schema-recall/invalidate?agentId=6" >/dev/null
python3 eval/scripts/run_table_select.py --agent 5 --tag full > results/step3/eval_full.log 2>&1 &
python3 eval/scripts/run_table_select.py --agent 6 --tag partial > results/step3/eval_partial.log 2>&1
wait
echo "$(date -u +%T) eval done" >> $LOG
