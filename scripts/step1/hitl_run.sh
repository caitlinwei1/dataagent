#!/usr/bin/env bash
# Step 1 HITL run: ask -> plan pauses before HumanFeedbackNode -> approve by threadId -> SQL/Python/report.
set -euo pipefail
AGENT=${AGENT:-2}; T=${T:-step1-hitl-$(date +%s)}; OUT=${OUT:-results/step1}
Q=${Q:-统计每个商品分类的销售额和订单数，找出销售额最高的分类，并分析各分类销售额占比}
enc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }
API="localhost:8065/api/stream/search?agentId=$AGENT&threadId=$T&humanFeedback=true&query=$(enc "$Q")"
mkdir -p "$OUT"; echo "threadId=$T"
date -u +%FT%TZ > "$OUT/01_plan.start";  curl -sN --max-time 900 "$API" > "$OUT/01_plan.sse"
date -u +%FT%TZ > "$OUT/02_execute.start"; curl -sN --max-time 1800 "$API&humanFeedbackContent=$(enc 同意该计划)" > "$OUT/02_approve_execute.sse"
date -u +%FT%TZ > "$OUT/02_execute.end"
