"""Turbo vs Mix (vs default) on the same questions, same backend, one question at a time.

For each question of eval/mode/questions.jsonl, every mode runs in turn (paired). Sequential on purpose: the LLM
usage of a run is the difference of GET /openapi/v1/usage before and after it, which is only clean when nothing else
runs. Scoring is run_nl2sql.py's execution-result comparison. Cost is estimated from the token counts with the
per-model prices in PRICES (CNY per million tokens, input / output).

The backend must be started with the case library: --gbi.mode.cases-file=eval/mode/cases.jsonl

Usage: python3 eval/scripts/run_modes.py --run r1 [--modes default,turbo,mix]      (resumable)
"""
import argparse
import collections
import json
import os
import sys

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "eval", "scripts"))
import run_nl2sql as R  # noqa: E402

BASE = os.environ.get("GBI_API", "http://localhost:8065")
PRICES = json.load(open(os.path.join(ROOT, "eval", "mode", "prices.json")))


def usage():
    return requests.get(BASE + "/openapi/v1/usage", timeout=30).json()


def delta(a, b):
    out = {}
    for m, v in b.items():
        d = {k: v[k] - a.get(m, {}).get(k, 0) for k in v}
        if any(d.values()):
            out[m] = d
    return out


def cost(u):
    total = 0.0
    for m, d in u.items():
        p = PRICES.get(m) or PRICES["default"]
        total += d["promptTokens"] / 1e6 * p[0] + d["completionTokens"] / 1e6 * p[1]
    return round(total, 5)


def summarize(rows):
    out = {}
    for mode, rs in sorted(collections.defaultdict(list, {m: [r for r in rows if r["mode"] == m]
                                                          for m in {r["mode"] for r in rows}}).items()):
        n = len(rs)
        lat = sorted(r["seconds"] for r in rs)
        first = sorted(r["first_output_seconds"] for r in rs if r["first_output_seconds"] is not None)
        out[mode] = dict(
            n=n, ex=round(sum(bool(r["ex"]) for r in rs) / n, 4), ex_strict=round(sum(bool(r["ex_strict"]) for r in rs) / n, 4),
            exec_success=round(sum(bool(r["exec_success"]) for r in rs) / n, 4),
            first_try_exec=round(sum(bool(r["exec_success"]) and r["sql_attempts"] <= 1 for r in rs) / n, 4),
            latency_p50=lat[n // 2], latency_mean=round(sum(lat) / n, 1), latency_p90=lat[min(n - 1, int(n * 0.9))],
            first_sql_p50=first[len(first) // 2] if first else None,
            llm_calls_mean=round(sum(sum(d["calls"] for d in r["usage"].values()) for r in rs) / n, 2),
            tokens_mean=round(sum(sum(d["promptTokens"] + d["completionTokens"] for d in r["usage"].values()) for r in rs) / n),
            cost_cny_per_q=round(sum(r["cost_cny"] for r in rs) / n, 5),
            by_task={t: round(sum(bool(r["ex"]) for r in rs if r["task"] == t) / max(1, sum(r["task"] == t for r in rs)), 3)
                     for t in sorted({r["task"] for r in rs})})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--modes", default="default,turbo,mix")
    a = ap.parse_args()
    modes = a.modes.split(",")
    qs = [json.loads(l) for l in open(os.path.join(ROOT, "eval", "mode", "questions.jsonl"), encoding="utf-8")]
    out = os.path.join(ROOT, "eval", "mode", "results", a.run)
    tdir = os.path.join(out, "traces")
    os.makedirs(tdir, exist_ok=True)
    rfile = os.path.join(out, "results.jsonl")
    done = {(r["id"], r["mode"]): r for r in map(json.loads, open(rfile))} if os.path.exists(rfile) else {}
    with open(rfile, "a") as f:
        for q in qs:
            for m in modes:
                if (q["id"], m) in done:
                    continue
                R.MODE = m
                before = usage()
                rec, report = R.run_one(q, m, tdir)
                rec = R.evaluate(q, rec, report)
                rec["mode"] = m
                rec["usage"] = delta(before, usage())
                rec["cost_cny"] = cost(rec["usage"])
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                f.flush()
                done[(q["id"], m)] = rec
                print(f"{q['id']:6} {m:8} ex={rec['ex']!s:5} {rec['seconds']:6.1f}s attempts={rec['sql_attempts']} "
                      f"calls={sum(d['calls'] for d in rec['usage'].values())} cost={rec['cost_cny']}", flush=True)
    s = summarize(list(done.values()))
    json.dump(s, open(os.path.join(out, "summary.json"), "w"), ensure_ascii=False, indent=1)
    print(json.dumps(s, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
