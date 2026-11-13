"""Count tables, fields, terms and rows of the loaded dataset; writes dataset/metadata/stats.json.

Usage: python3 dataset/stats.py [mysql-container]
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
C = sys.argv[1] if len(sys.argv) > 1 else "data-agent-mysql-datasource"


def q(sql):
    out = subprocess.run(["docker", "exec", C, "mysql", "-uroot", "-proot", "-N", "-B", "-e", sql],
                         capture_output=True, text=True, check=True).stdout
    return [r.split("\t") for r in out.strip().split("\n") if r]


tables = int(q("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='gbi_ecommerce'")[0][0])
fields = int(q("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='gbi_ecommerce'")[0][0])
commented = int(q("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='gbi_ecommerce' AND column_comment<>''")[0][0])
names = [r[0] for r in q("SELECT table_name FROM information_schema.tables WHERE table_schema='gbi_ecommerce' ORDER BY 1")]
rows = {}
for n in names:
    rows[n] = int(q(f"SELECT COUNT(*) FROM gbi_ecommerce.`{n}`")[0][0])
glossary = json.load(open(os.path.join(HERE, "metadata", "glossary.json")))
catalog = json.load(open(os.path.join(HERE, "metadata", "tables.json")))
stats = dict(tables=tables, fields=fields, fields_with_comment=commented, terms=len(glossary),
             logical_relations=sum(len(t["foreign_keys"]) for t in catalog),
             ablated_tables=sum(t["ablate"] for t in catalog), total_rows=sum(rows.values()),
             empty_tables=[n for n, v in rows.items() if v == 0], rows=rows)
json.dump(stats, open(os.path.join(HERE, "metadata", "stats.json"), "w"), ensure_ascii=False, indent=1)
print({k: v for k, v in stats.items() if k != "rows"})
