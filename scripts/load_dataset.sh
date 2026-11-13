#!/usr/bin/env bash
# Build schema + seed and load gbi_ecommerce into MySQL and PostgreSQL (Hologres stand-in).
set -euo pipefail
cd "$(dirname "$0")/.."
MY=${MYSQL_CONTAINER:-data-agent-mysql-datasource}; PG=${PG_CONTAINER:-data-agent-postgres-datasource}
VARIANT=${VARIANT:-full}   # full | partial (注释消融)
python3 dataset/build_schema.py
python3 dataset/gen_seed.py mysql > /tmp/seed_mysql.sql
python3 dataset/gen_seed.py pg > /tmp/seed_pg.sql
docker exec -i "$MY" mysql -uroot -proot < "dataset/ddl/mysql_${VARIANT}.sql"
docker exec -i "$MY" mysql -uroot -proot gbi_ecommerce < /tmp/seed_mysql.sql
docker exec -i "$MY" mysql -uroot -proot gbi_ecommerce < dataset/derive.sql
docker exec -i "$MY" mysql -uroot -proot < dataset/ddl/readonly_mysql.sql
P="docker exec -i $PG psql -q -v ON_ERROR_STOP=1 -U postgres -d postgres"
$P < "dataset/ddl/postgres_${VARIANT}.sql"
$P < /tmp/seed_pg.sql
(echo "SET search_path TO gbi_ecommerce;"; cat dataset/derive.sql) | $P
$P < dataset/ddl/readonly_pg.sql
python3 dataset/build_glossary.py "$MY"
python3 dataset/stats.py "$MY"
