"""Run report tasks through the open report API (POST /openapi/v1/reports) and score them.

Per task: status, wall time, charts (count / valid ECharts JSON / types), evidence (SQL count), key facts stated in the
report, planted root cause named (must_mention), conclusion + recommendation sections, manual-baseline estimate and
speed-up. Outputs eval/report/results/<run>/{<id>.json,<id>.md,results.jsonl,summary.json}.

Number traceability: every amount-like number (|x| >= 100, not a year/date) in the report prose is looked up in the
full results of the evidence SQL (re-run on MySQL): a cell, a column total, or the difference of two of those in the
same column. Numbers that match nothing are listed as `untraced` -- usually arithmetic the LLM did in its head.
"usable" = COMPLETED and at least one evidence query returned rows.

If the chat model's free quota runs out (403), the next model in MODELS is activated and the task is re-run.

Usage: python3 eval/scripts/run_reports.py --run sample8 [--all] [--workers 2] [--rescore]
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import threading

import importlib.util

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API = os.environ.get("GBI_API", "http://localhost:8065")
AGENT = os.environ.get("GBI_AGENT", "5")
MODELS = ["qwen-plus", "qwen-max", "qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max", "qwen3.7-max"]
QUOTA = ("403", "insufficient_quota", "FreeTierOnly", "Free quota exhausted")
state = {"model": 0, "gen": 0}
_spec = importlib.util.spec_from_file_location("bq", os.path.join(ROOT, "eval", "nl2sql", "build_questions.py"))
bq = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bq)
lock = threading.Lock()


def set_model(name):
    items = requests.get(API + "/api/model-config/list", timeout=30).json()
    items = items.get("data", items) if isinstance(items, dict) else items
    chat = [x for x in items if x.get("modelType") == "CHAT"][0]
    chat["modelName"] = name
    assert requests.put(API + "/api/model-config/update", json=chat, timeout=30).json().get("success")


def key_hit(value, text):
    nums = []
    for x in re.findall(r"-?\d[\d,]*\.?\d*", text):
        try:
            nums.append(float(x.replace(",", "")))
        except ValueError:
            pass
    wan = [n * 10000 for n in nums]  # "95.85万"
    cands = [value, value * 100] if abs(value) <= 1.5 else [value]
    return any(abs(n - c) <= max(0.06, 0.01 * abs(c)) for n in nums + wan for c in cands)


def _num(x):
    try:
        return float(str(x).replace(",", ""))
    except (TypeError, ValueError):
        return None


def evidence_values(rep):
    by_col = {}  # column name -> candidate values (cells + totals), across all evidence queries
    for e in rep.get("evidence") or []:
        try:
            cols, data = bq.mysql(e["sql"])
        except Exception:
            continue
        for j, c in enumerate(cols):
            vals = [v for v in (_num(r[j]) for r in data if j < len(r)) if v is not None]
            if not vals:
                continue
            by_col.setdefault(c, []).extend(vals + [sum(vals)])
            for k in range(len(cols)):  # subtotals per value of every label column (e.g. per month)
                groups = {}
                for r in data:
                    if k < len(r) and j < len(r) and k != j and _num(r[j]) is not None:
                        groups.setdefault(r[k], []).append(_num(r[j]))
                if 1 < len(groups) < len(data):
                    by_col[c].extend(sum(g) for g in groups.values())
    cands = set()
    for vals in by_col.values():
        cands.update(vals)
        small = vals[:60]
        cands.update(a - b for a in small for b in small)
    return cands


def prose_numbers(text):
    text = re.sub(r"```.*?```", " ", text, flags=re.S)
    text = re.sub(r"\d{4}-\d{2}(-\d{2})?|\d{4}年|20\d{4}", " ", text)
    out = []
    for m in re.finditer(r"(-?\d[\d,]*\.?\d*)\s*(万|%|％|倍)?", text):
        v = _num(m.group(1))
        if v is None or m.group(2) in ("%", "％", "倍"):
            continue
        v = v * 10000 if m.group(2) else v
        if abs(v) >= 100:
            out.append((m.group(0).strip(), v))
    return out


def untraced(rep, text):
    nums = prose_numbers(text)
    if not nums:
        return 0, []
    cands = evidence_values(rep)
    bad = [raw for raw, v in nums if not any(abs(v - c) <= max(0.011, 0.005 * abs(c)) for c in cands)]
    return len(nums), bad


def score(t, rep):
    body = rep.get("markdown") or ""
    text = body.split("## 附录：数据证据")[0]
    charts = rep.get("charts") or []
    keys = [key_hit(k["value"], text) for k in t["keys"]]
    must = [m in text for m in t["must_mention"]]
    n_nums, bad = untraced(rep, text) if rep.get("status") != "FAILED" else (0, [])
    rows = [e.get("rowCount", 0) for e in rep.get("evidence") or []]
    status = rep.get("status")
    if status == "COMPLETED" and rows and not any(rows):  # same rule as ReportAssembler (older runs lack it)
        status = "DEGRADED"
    return dict(
        id=t["id"], kind=t["kind"], status=status, usable=status == "COMPLETED" and any(rows),
        numbers=n_nums, untraced=bad, seconds=round(rep.get("seconds", 0), 1),
        model=rep.get("_model"), plan_steps=len(rep.get("plan") or []),
        tools=[p.get("tool") for p in rep.get("plan") or []], charts=len(charts),
        charts_valid=sum(c["valid"] for c in charts), chart_types=[c.get("type") for c in charts],
        evidence_sql=len(rep.get("evidence") or []), keys_hit=sum(keys), keys_total=len(keys),
        must_hit=sum(must), must_total=len(must),
        has_conclusion=bool(re.search(r"结论|摘要|总结|核心发现", text)), has_recommendation=bool(re.search(r"建议|行动", text)),
        issues=rep.get("issues") or [], manual_minutes_est=t["manual_minutes_est"],
        speedup=round(t["manual_minutes_est"] * 60 / rep["seconds"], 1) if rep.get("seconds") else None)


def run(t, outdir):
    while True:
        with lock:
            gen, model = state["gen"], MODELS[state["model"]]
        try:
            rep = requests.post(API + "/openapi/v1/reports", json=dict(agentId=AGENT, question=t["question"],
                                                                         timeoutSeconds=1500), timeout=1800).json()
        except Exception as e:
            rep = dict(status="FAILED", seconds=0, issues=[f"client: {e}"], markdown="")
        if rep.get("status") == "FAILED" and any(q in i for i in rep.get("issues", []) for q in QUOTA):
            with lock:
                if state["gen"] == gen:
                    state["model"] += 1
                    state["gen"] += 1
                    if state["model"] >= len(MODELS):
                        raise SystemExit("all free-tier models exhausted")
                    set_model(MODELS[state["model"]])
                    print(f"quota exhausted on {model} -> {MODELS[state['model']]}", flush=True)
            continue
        rep["_model"] = model
        json.dump(rep, open(os.path.join(outdir, t["id"] + ".json"), "w"), ensure_ascii=False, indent=1)
        open(os.path.join(outdir, t["id"] + ".md"), "w").write(f"<!-- {t['question']} | {model} -->\n\n"
                                                                + (rep.get("markdown") or ""))
        return score(t, rep)


def summarize(rows):
    n = len(rows)
    ok = [r for r in rows if r["status"] == "COMPLETED"]
    secs = sorted(r["seconds"] for r in ok)
    s = dict(n=n, completed=len(ok), completion_rate=round(len(ok) / n, 3) if n else None,
             with_valid_chart=sum(r["charts_valid"] > 0 for r in ok), charts=sum(r["charts"] for r in ok),
             charts_valid=sum(r["charts_valid"] for r in ok), with_evidence=sum(r["evidence_sql"] > 0 for r in ok),
             keys_hit=sum(r["keys_hit"] for r in ok), keys_total=sum(r["keys_total"] for r in ok),
             must_hit=sum(r["must_hit"] for r in rows), must_total=sum(r["must_total"] for r in rows),
             numbers=sum(r["numbers"] for r in ok), numbers_untraced=sum(len(r["untraced"]) for r in ok),
             with_conclusion=sum(r["has_conclusion"] for r in ok), with_recommendation=sum(r["has_recommendation"] for r in ok),
             system_seconds_p50=secs[len(secs) // 2] if secs else None,
             system_minutes_mean=round(sum(secs) / len(secs) / 60, 1) if secs else None,
             manual_minutes_est_mean=round(sum(r["manual_minutes_est"] for r in ok) / len(ok), 1) if ok else None)
    if ok:
        s["time_saved_ratio"] = round(1 - (sum(secs) / 60) / sum(r["manual_minutes_est"] for r in ok), 3)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--start-model", default=MODELS[0])
    ap.add_argument("--ids", help="comma-separated task ids to run instead of the sample")
    ap.add_argument("--rescore", action="store_true", help="re-score the saved <id>.json files, no API calls")
    a = ap.parse_args()
    tasks = [json.loads(l) for l in open(os.path.join(ROOT, "eval", "report", "tasks.jsonl"), encoding="utf-8")]
    tasks = tasks if a.all else [t for t in tasks if (t["id"] in a.ids.split(",") if a.ids else t["sample"])]
    outdir = os.path.join(ROOT, "eval", "report", "results", a.run)
    os.makedirs(outdir, exist_ok=True)
    rfile = os.path.join(outdir, "results.jsonl")
    if a.rescore:
        rows = []
        for t in tasks:
            f = os.path.join(outdir, t["id"] + ".json")
            if os.path.exists(f):
                rows.append(score(t, json.load(open(f))))
        with open(rfile, "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        s = summarize(rows)
        json.dump(s, open(os.path.join(outdir, "summary.json"), "w"), ensure_ascii=False, indent=1)
        for r in rows:
            print(r["id"], r["status"], f"numbers={r['numbers']} untraced={r['untraced']}")
        print(json.dumps(s, ensure_ascii=False))
        return
    done = {json.loads(l)["id"]: json.loads(l) for l in open(rfile)} if os.path.exists(rfile) else {}
    state["model"] = MODELS.index(a.start_model)
    set_model(MODELS[state["model"]])
    todo = [t for t in tasks if t["id"] not in done]
    print(f"{len(todo)} tasks to run", flush=True)
    with cf.ThreadPoolExecutor(a.workers) as ex, open(rfile, "a") as f:
        futs = {ex.submit(run, t, outdir): t for t in todo}
        for fu in cf.as_completed(futs):
            r = fu.result()
            with lock:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
                f.flush()
                done[r["id"]] = r
            print(f"{r['id']} {r['status']} {r['seconds']}s charts={r['charts_valid']}/{r['charts']} "
                  f"evidence={r['evidence_sql']} keys={r['keys_hit']}/{r['keys_total']} must={r['must_hit']}/{r['must_total']} "
                  f"model={r['model']}", flush=True)
    rows = [done[t["id"]] for t in tasks if t["id"] in done]
    s = summarize(rows)
    json.dump(s, open(os.path.join(outdir, "summary.json"), "w"), ensure_ascii=False, indent=1)
    print(json.dumps(s, ensure_ascii=False))


if __name__ == "__main__":
    main()
