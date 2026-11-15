#!/usr/bin/env bash
# Run one eval config on one split: restart gbi-agent with the config's switches, then run_nl2sql.py.
#   upstream_recall : b297203 table recall (question text ignored), no step-7 fixes
#   v1              : steps 3-6 (multi-channel recall on Milvus, SQL guard, error classes), no step-7 fixes
#   v2              : v1 + step-7 fixes from the dev error analysis (time ranges, repair hints)
# Usage: scripts/step7/run_eval.sh v2 dev [extra run_nl2sql.py args]
set -e
cd "$(dirname "$0")/../.."
CONFIG=$1; SPLIT=$2; shift 2
case $CONFIG in
  upstream_recall) ARGS="--gbi.recall.enabled=false --gbi.nl2sql.time-hints=false --gbi.nl2sql.repair-hints=false" ;;
  v1) ARGS="--gbi.nl2sql.time-hints=false --gbi.nl2sql.repair-hints=false" ;;
  v2) ARGS="" ;;
  *) echo "unknown config $CONFIG"; exit 1 ;;
esac
LOG=results/step7/backend_${CONFIG}_${SPLIT}.log bash scripts/step5/restart_backend.sh $ARGS
python3 eval/scripts/run_nl2sql.py --config "$CONFIG" --split "$SPLIT" "$@"
