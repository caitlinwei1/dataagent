"""Paired comparison of a run_modes.py run: per-mode table + per-question wins/losses vs default.
Usage: python3 eval/mode/compare.py r1 [--hybrid hybrid_turbo]"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
run = sys.argv[1]
rows = [json.loads(l) for l in open(os.path.join(ROOT, "results", run, "results.jsonl"))]
by = {}
for r in rows:
    by.setdefault(r["id"], {})[r["mode"]] = r
ids = [i for i, m in by.items() if len(m) == 3]
print(f"complete questions: {len(ids)}")
for mode in ["default", "turbo", "mix"]:
    rs = [by[i][mode] for i in ids]
    n = len(rs)
    lat = sorted(r["seconds"] for r in rs)
    calls = sum(sum(d["calls"] for d in r["usage"].values()) for r in rs) / n
    tok = sum(sum(d["promptTokens"] + d["completionTokens"] for d in r["usage"].values()) for r in rs) / n
    print(f"{mode:8} EX {sum(bool(r['ex']) for r in rs)}/{n}  exec {sum(bool(r['exec_success']) for r in rs)}/{n}  "
          f"first-try {sum(bool(r['exec_success']) and r['sql_attempts'] <= 1 for r in rs)}/{n}  "
          f"p50 {lat[n // 2]:.1f}s mean {sum(lat) / n:.1f}s p90 {lat[min(n - 1, int(n * .9))]:.1f}s  "
          f"calls {calls:.1f} tokens {tok:.0f} cost {sum(r['cost_cny'] for r in rs) / n:.4f} "
          f"retries>=10 {sum(r['sql_attempts'] >= 10 for r in rs)}")
for mode in ["turbo", "mix"]:
    w = [i for i in ids if by[i][mode]["ex"] and not by[i]["default"]["ex"]]
    l = [i for i in ids if not by[i][mode]["ex"] and by[i]["default"]["ex"]]
    print(f"{mode} vs default: wins {len(w)} {w}  losses {len(l)} {l}")
