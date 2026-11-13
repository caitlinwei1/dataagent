#!/usr/bin/env bash
# Step 1: bring up historical DataAgent (b297203) with MySQL metadata DB + sample MySQL/PG sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UP="$ROOT/upstream/dataagent-b297203"

docker start da-meta 2>/dev/null || docker run -d --name da-meta \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=saa_data_agent -p 3306:3306 mysql:8.0
docker compose -f "$UP/docker-file/docker-compose-datasource.yml" up -d

JAR="$UP/data-agent-management/target/spring-ai-alibaba-data-agent-management-1.0.0-SNAPSHOT.jar"
[ -f "$JAR" ] || (cd "$UP/data-agent-management" && mvn -B -q -DskipTests -Dmaven.artifact.threads=1 package)

until docker exec da-meta mysqladmin ping -uroot -proot --silent; do sleep 2; done
mkdir -p "$ROOT/results/step1"
DATA_AGENT_DATASOURCE_SQL_INIT=always nohup java -jar "$JAR" > "$ROOT/results/step1/backend.log" 2>&1 &
until curl -sf localhost:8065/api/agent/list >/dev/null; do sleep 2; done
echo "backend up on :8065"
