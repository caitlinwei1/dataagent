#!/usr/bin/env bash
# Restart gbi-agent (Milvus profile) with extra args, e.g. --gbi.hitl.checkpoint=mysql; waits for agent init.
cd "$(dirname "$0")/../.."
LOG=${LOG:-results/step5/backend.log}; mkdir -p "$(dirname "$LOG")"
pkill -TERM -f "^java -jar run/gbi-agent"; while pgrep -f "^java -jar run/gbi-agent" >/dev/null; do sleep 1; done
: > "$LOG"
setsid nohup java -jar run/gbi-agent.jar --spring.profiles.active=milvus \
  --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
  --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1 "$@" >> "$LOG" 2>&1 < /dev/null &
until grep -qE "Agent initialization completed|APPLICATION FAILED" "$LOG"; do sleep 2; done
grep -E "Graph checkpoints|APPLICATION FAILED|Agent initialization completed" "$LOG" | cut -c1-160
