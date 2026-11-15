#!/usr/bin/env bash
# Build schema + seed and load the dataset into MySQL and PostgreSQL (Hologres stand-in).
#   VARIANT=full    -> MySQL database / PG schema gbi_ecommerce          (all comments)
#   VARIANT=partial -> MySQL database / PG schema gbi_ecommerce_partial  (注释消融: 38 tables without comments)
# The two variants live side by side; the read-only account gbi_reader is granted SELECT on whichever is loaded.
set -euo pipefail
cd "$(dirname "$0")/.."
MY=${MYSQL_CONTAINER:-data-agent-mysql-datasource}; PG=${PG_CONTAINER:-data-agent-postgres-datasource}
VARIANT=${VARIANT:-full}
case "$VARIANT" in full) DB=gbi_ecommerce ;; partial) DB=gbi_ecommerce_partial ;; *) echo "VARIANT must be full|partial" >&2; exit 1 ;; esac
export GBI_DB=$DB
python3 dataset/build_schema.py
python3 dataset/gen_seed.py mysql > /tmp/seed_mysql.sql
python3 dataset/gen_seed.py pg > /tmp/seed_pg.sql
M="docker exec -i $MY mysql --default-character-set=utf8mb4 -uroot -proot"
$M < "dataset/ddl/mysql_${VARIANT}.sql"
$M "$DB" < /tmp/seed_mysql.sql
$M "$DB" < dataset/derive.sql
sed "s/__DB__/$DB/g" dataset/ddl/readonly_mysql.sql | $M
P="docker exec -i $PG psql -q -v ON_ERROR_STOP=1 -U postgres -d postgres"
$P < "dataset/ddl/postgres_${VARIANT}.sql"
$P < /tmp/seed_pg.sql
(echo "SET search_path TO $DB;"; cat dataset/derive.sql) | $P
sed "s/__DB__/$DB/g" dataset/ddl/readonly_pg.sql | $P
python3 dataset/build_glossary.py "$MY"
python3 dataset/stats.py "$MY"
