#!/usr/bin/env bash
# HITL end-to-end records: reject-to-cap on the current build, then threadId resume across a backend restart with
# in-memory checkpoints (b297203 behaviour) and with MySQL checkpoints.
set -u
cd "$(dirname "$0")/../.."
R=scripts/step5/restart_backend.sh; E="python3 scripts/step5/hitl_e2e.py"
$R
rm -rf results/step5/reject; $E reject
for mode in memory mysql; do
  $R --gbi.hitl.checkpoint=$mode
  rm -rf results/step5/resume_$mode
  T=$($E resume --phase start --out resume_$mode | awk '/^thread/{print $2}')
  echo "restart ($mode) with thread $T paused at plan review"
  $R --gbi.hitl.checkpoint=$mode
  $E resume --phase finish --thread "$T" --out resume_$mode
done
