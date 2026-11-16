#!/usr/bin/env bash
# Start the local data gateway ("数据在地" half of the hybrid deployment) from the same jar as the backend.
# Run it inside the network that can reach the database; the cloud backend only needs to reach this port.
#
#   GATEWAY_SECRET       shared HMAC secret; the cloud datasource stores the same value as its "password"
#   GATEWAY_DB_PASSWORD  read-only database password; it lives only here, never in the cloud meta DB
#   optional: GATEWAY_DB_DIALECT (mysql|postgresql), GATEWAY_DB_URL, GATEWAY_DB_USER, GATEWAY_DB_SCHEMA, GATEWAY_PORT
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GATEWAY_SECRET:?export GATEWAY_SECRET first}"
: "${GATEWAY_DB_PASSWORD:?export GATEWAY_DB_PASSWORD first}"
JAR=${JAR:-run/gbi-agent.jar}
mkdir -p run/gateway
export GATEWAY_AUDIT_LOG=${GATEWAY_AUDIT_LOG:-$PWD/run/gateway/audit.jsonl}
setsid nohup java -Dloader.main=com.alibaba.cloud.ai.gbigateway.GatewayApplication -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --spring.config.location=file:deploy/gateway/gateway.yml > run/gateway/gateway.log 2>&1 < /dev/null &
until curl -sf "localhost:${GATEWAY_PORT:-8070}/gateway/v1/health" >/dev/null; do
  grep -q "APPLICATION FAILED" run/gateway/gateway.log && { tail -30 run/gateway/gateway.log; exit 1; }; sleep 1; done
echo "gateway up on :${GATEWAY_PORT:-8070}, audit log $GATEWAY_AUDIT_LOG"
