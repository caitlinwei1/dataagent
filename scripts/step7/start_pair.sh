#!/usr/bin/env bash
# Start the two backends for the paired sample: v2 on :8065, upstream_recall on :8066 (same Milvus / meta DB).
cd "$(dirname "$0")/../.."
pkill -TERM -f "^java -jar run/gbi-agent"; while pgrep -f "^java -jar run/gbi-agent" >/dev/null; do sleep 1; done
COMMON="--spring.profiles.active=milvus --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1"
start() { local log=$1; shift; : > "$log"; setsid nohup java -jar run/gbi-agent.jar $COMMON "$@" >> "$log" 2>&1 < /dev/null & }
start results/step7/backend_v2.log
start results/step7/backend_upstream_recall.log --server.port=8066 --gbi.recall.enabled=false \
  --gbi.nl2sql.time-hints=false --gbi.nl2sql.repair-hints=false
for l in results/step7/backend_v2.log results/step7/backend_upstream_recall.log; do
  until grep -qE "Agent initialization completed|APPLICATION FAILED" "$l"; do sleep 2; done
  grep -E "APPLICATION FAILED|Agent initialization completed|Tomcat started on port" "$l" | cut -c1-140
done
