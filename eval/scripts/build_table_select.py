"""Parse eval/table_select/questions.txt into eval/table_select/questions.jsonl and validate gold tables."""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
tables = {t["name"] for t in json.load(open(os.path.join(ROOT, "dataset/metadata/tables.json")))}
ablate = {t["name"] for t in json.load(open(os.path.join(ROOT, "dataset/metadata/tables.json"))) if t["ablate"]}
out, n = [], 0
for line in open(os.path.join(ROOT, "eval/table_select/questions.txt"), encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    parts = [p.strip() for p in line.split("|")]
    tags, gold, q = parts[0].split(","), parts[1].split(","), parts[2]
    ctx = parts[3].split(" / ") if len(parts) > 3 and parts[3] else []
    bad = [g for g in gold if g not in tables]
    assert not bad, (q, bad)
    n += 1
    out.append(dict(id=f"TS{n:03d}", domain=tags[0], tags=tags[1:], question=q, context=ctx, gold_tables=gold,
                    gold_has_ablated=any(g in ablate for g in gold)))
assert n == 180, n
with open(os.path.join(ROOT, "eval/table_select/questions.jsonl"), "w", encoding="utf-8") as f:
    for r in out:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
from collections import Counter
print(n, Counter(r["domain"] for r in out), "multi-turn", sum(bool(r["context"]) for r in out),
      "gold avg", round(sum(len(r["gold_tables"]) for r in out) / n, 2), ">5 gold", sum(len(r["gold_tables"]) > 5 for r in out),
      "ablated", sum(r["gold_has_ablated"] for r in out), "distinct gold tables", len({g for r in out for g in r["gold_tables"]}))
