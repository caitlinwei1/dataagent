"""Run the 320-question 问数 eval against a running gbi-agent and score it.

Every question gets its own conversation (threadId = conversationId). Multi-turn questions replay their earlier turns
in the same conversation first; only the last turn is scored. single / join / multi_turn run with nl2sqlOnly=true;
multi_step runs the full graph (planner -> SQL/Python steps -> report, human review off).

Per question it stores the raw SSE stream (traces/<config>/<id>.jsonl) and one record in results.jsonl with:
plan, every generated SQL, every guard block / execution error with its class, the final SQL and result,
latency and first-output latency.

Usage:
  python3 eval/scripts/run_nl2sql.py --config full --split dev [--tasks single,join] [--workers 8] [--limit N]
  python3 eval/scripts/run_nl2sql.py --config full --split dev --rescore     # recompute scores from traces only
"""
import argparse
import collections
import concurrent.futures as cf
import json
import os
import re
import sys
import threading
import time
import uuid

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QFILE = os.path.join(ROOT, "eval", "nl2sql", "questions.jsonl")
API = os.environ.get("GBI_API", "http://localhost:8065") + "/api/stream/search"
AGENT = os.environ.get("GBI_AGENT", "5")
CLASS_RE = re.compile(r"(?:SQL守卫拦截|SQL执行失败)\[([A-Z_]+)/")


# ------------------------------------------------------------------ calling the backend
def stream(query, thread, nl2sql_only, timeout=900):
    params = dict(agentId=AGENT, query=query, threadId=thread, nl2sqlOnly=str(nl2sql_only).lower(),
                  humanFeedback="false")
    events, t0, first = [], time.time(), None
    try:
        with requests.get(API, params=params, stream=True, timeout=timeout) as r:
            for line in r.iter_lines(decode_unicode=True):
                if line and line.startswith("data:"):
                    try:
                        e = json.loads(line[5:])
                    except ValueError:
                        continue
                    e["_t"] = round(time.time() - t0, 2)
                    if first is None and e.get("nodeName") in ("SqlGenerateNode", "PlannerNode"):
                        first = e["_t"]
                    events.append(e)
    except Exception as ex:  # timeout / connection reset: keep what we have
        events.append({"error": True, "text": f"client: {type(ex).__name__}: {ex}", "_t": round(time.time() - t0, 2)})
    return events, round(time.time() - t0, 1), first


def segments(events):
    """Consecutive (node, textType) runs of the stream joined into text."""
    out = []
    for e in events:
        k = (e.get("nodeName"), e.get("textType"))
        if not out or out[-1][0] != k:
            out.append([k, ""])
        out[-1][1] += e.get("text") or ""
    return out


def extract(events):
    segs = segments(events)
    gen_sql = [t.strip() for (n, tt), t in segs if n == "SqlGenerateNode" and tt == "SQL"]
    exec_sql = [t.strip() for (n, tt), t in segs if n == "SqlExecuteNode" and tt == "SQL"]
    results = [t for (n, tt), t in segs if n == "SqlExecuteNode" and tt == "RESULT_SET"]
    exec_text = "".join(t for (n, tt), t in segs if n == "SqlExecuteNode" and tt == "TEXT")
    blocks = len(re.findall(r"SQL守卫拦截", exec_text))
    classes = CLASS_RE.findall(exec_text)
    plan_raw = "".join(t for (n, tt), t in segs if n == "PlannerNode" and tt == "JSON")
    plan = None
    if plan_raw:
        s, e = plan_raw.find("{"), plan_raw.rfind("}")
        try:
            plan = json.loads(plan_raw[s:e + 1])
        except ValueError:
            plan = None
    nodes = []
    for (n, _), _t in segs:
        if n and (not nodes or nodes[-1] != n):
            nodes.append(n)
    report = "".join(t for (n, tt), t in segs if n == "ReportGeneratorNode")
    py_fail = sum(1 for (n, tt), t in segs if n == "PythonExecuteNode" and "执行失败" in t)
    degraded = any("【降级】" in t or "降级" in t for (n, tt), t in segs if n in ("PythonAnalyzeNode", "PythonExecuteNode"))
    errors = [e.get("text") for e in events if e.get("error")]
    last = None
    if results:
        try:
            last = json.loads(results[-1]).get("resultSet")
        except ValueError:
            last = None
    return dict(plan_steps=len(plan.get("execution_plan", [])) if plan else None,
                plan_tools=[s.get("tool_to_use") for s in plan.get("execution_plan", [])] if plan else None,
                sql_attempts=len(gen_sql), sql_generated=gen_sql, sql_executed=exec_sql, guard_blocks=blocks,
                error_classes=classes, final_sql=exec_sql[-1] if results and exec_sql else None, result=last,
                nodes=nodes, report=report, python_failures=py_fail, python_degraded=degraded, errors=errors,
                python_runs=sum(1 for n in nodes if n == "PythonExecuteNode"))


# ------------------------------------------------------------------ scoring
def num(v):
    if v is None:
        return None
    s = str(v).strip().replace(",", "")
    if s.endswith("%"):
        try:
            return float(s[:-1]) / 100
        except ValueError:
            return None
    try:
        return float(s)
    except ValueError:
        return None


def norm_str(v):
    s = "" if v is None else str(v).strip()
    if re.match(r"^\d{4}-\d{2}-\d{2}[ T]00:00:00(\.0+)?$", s):
        s = s[:10]
    return s


def close(a, b):
    return abs(a - b) <= max(0.011, 0.005 * abs(b))


def col_values(cols, rows, c):
    i = cols.index(c)
    return [r[i] if i < len(r) else None for r in rows]


def col_match(gold_vals, pred_vals, ordered):
    if len(gold_vals) != len(pred_vals):
        return False
    gn = [num(v) for v in gold_vals]
    if all(x is not None for x in gn):
        pn = [num(v) for v in pred_vals]
        if any(x is None for x in pn):
            return False
        ratio = all(abs(x) <= 1.5 for x in gn)
        for scale in ([1, 100] if ratio else [1]):
            g = [x * scale for x in gn]
            if ordered:
                if all(close(p, x) for p, x in zip(pn, g)):
                    return True
            else:
                left = sorted(pn)
                if all(close(p, x) for p, x in zip(left, sorted(g))):
                    return True
        return False
    gs, ps = [norm_str(v) for v in gold_vals], [norm_str(v) for v in pred_vals]
    return gs == ps if ordered else sorted(gs) == sorted(ps)


def score(q, res):
    """Returns (strict, numeric) execution accuracy for one question."""
    if not res or not res.get("data"):
        return False, False
    pcols = res.get("column") or list(res["data"][0].keys())
    prows = [[row.get(c) for c in pcols] for row in res["data"]]
    gcols, grows = q["gold_columns"], q["gold_rows"]
    ordered = q.get("order_matters", False)
    if q.get("gold_tie_rows"):  # top-N with ties at the cut-off: pred must be N rows drawn from gold+ties
        pool = grows + q["gold_tie_rows"]
        if len(prows) != q["top_n"]:
            return False, False
        val = [num(r[-1]) for r in pool]
        pv = None
        for c in pcols:
            vals = [num(x) for x in col_values(pcols, prows, c)]
            if all(v is not None for v in vals):
                pv = vals
        ok = pv is not None and all(any(close(p, g) for g in val if g is not None) for p in pv) and \
            all(any(close(p, num(r[-1])) for p in pv) for r in grows if num(r[-1]) != num(grows[-1][-1]))
        return ok, ok

    def match(skip_labels):
        used = set()
        for gc in gcols:
            gv = col_values(gcols, grows, gc)
            is_num = all(num(v) is not None for v in gv)
            hit = None
            for pc in pcols:
                if pc in used:
                    continue
                if col_match(gv, col_values(pcols, prows, pc), ordered):
                    hit = pc
                    break
            if hit:
                used.add(hit)
            elif not (skip_labels and not is_num):
                return False
        return True

    strict = match(False)
    numeric = strict or (len(prows) == len(grows) and match(True))
    if not numeric and q["intent"] == "top" and len(prows) == len(grows):
        # "which N shops/SKUs ...": the entities are the answer, the ranking value is optional
        labels = [c for c in gcols if not all(num(v) is not None for v in col_values(gcols, grows, c))]
        if labels:
            used = set()
            numeric = True
            for gc in labels:
                gv = col_values(gcols, grows, gc)
                hit = next((pc for pc in pcols if pc not in used and col_match(gv, col_values(pcols, prows, pc), False)), None)
                if hit is None:
                    numeric = False
                    break
                used.add(hit)
    if not numeric and len(grows) * len(gcols) <= 4:  # small answers in a different shape (2 rows vs 2 columns)
        gnums = [num(v) for r in grows for v in r if num(v) is not None]
        pnums = [num(v) for r in prows for v in r if num(v) is not None]
        if gnums and len(pnums) <= 3 * len(gnums) + 3:
            left = list(pnums)
            ok = True
            for g in gnums:
                j = next((i for i, p in enumerate(left) if close(p, g)), None)
                if j is None:
                    ok = False
                    break
                left.pop(j)
            numeric = ok
    return strict, numeric


def key_hit(q, report):
    v = q.get("key_value")
    if v is None or not report:
        return None
    nums = [float(x.replace(",", "")) for x in re.findall(r"-?\d[\d,]*\.?\d*", report) if x.replace(",", "").strip("-.")]
    cands = [v, v * 100] if abs(v) <= 1.5 else [v, v / 10000]
    return any(abs(n - c) <= max(0.06, 0.01 * abs(c)) for n in nums for c in cands)


# ------------------------------------------------------------------ main
def run_one(q, config, tdir):
    thread = f"{config}-{q['id']}-{uuid.uuid4().hex[:6]}"
    full = q["task"] == "multi_step"
    ctx_turns = []
    for c in q["context"]:
        ev, secs, _ = stream(c, thread, nl2sql_only=True)
        ctx_turns.append(dict(question=c, seconds=secs, final_sql=extract(ev)["final_sql"]))
    ev, secs, first = stream(q["question"], thread, nl2sql_only=not full, timeout=1800 if full else 900)
    with open(os.path.join(tdir, q["id"] + ".jsonl"), "w") as f:
        for e in ev:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    x = extract(ev)
    rec = dict(id=q["id"], conversation_id=thread, config=config, task=q["task"], intent=q["intent"],
               split=q["split"], question=q["question"], context_turns=ctx_turns, seconds=secs,
               first_output_seconds=first, **{k: v for k, v in x.items() if k != "report"})
    rec["report_chars"] = len(x["report"])
    return rec, x["report"]


def evaluate(q, rec, report):
    if q["task"] == "multi_step":
        done = bool(report.strip()) and not rec["errors"] and not rec["python_degraded"]
        rec.update(exec_success=rec["final_sql"] is not None, completed=done, key_hit=key_hit(q, report))
    else:
        strict, numeric = score(q, rec.get("result"))
        rec.update(exec_success=rec["result"] is not None and not rec["errors"], ex_strict=strict, ex=numeric)
    return rec


def summarize(recs):
    def rate(xs):
        xs = [x for x in xs if x is not None]
        return round(sum(bool(x) for x in xs) / len(xs), 4) if xs else None

    s = {"n": len(recs)}
    sq = [r for r in recs if r["task"] != "multi_step"]
    ms = [r for r in recs if r["task"] == "multi_step"]
    s["sql_questions"] = len(sq)
    s["sql_exec_success"] = rate(r["exec_success"] for r in sq)
    s["ex"] = rate(r["ex"] for r in sq)
    s["ex_strict"] = rate(r["ex_strict"] for r in sq)
    s["multi_step_questions"] = len(ms)
    s["multi_step_completion"] = rate(r["completed"] for r in ms)
    s["multi_step_key_hit"] = rate(r["key_hit"] for r in ms)
    s["first_try_exec"] = rate(r["exec_success"] and r["sql_attempts"] <= 1 for r in sq)
    s["repaired"] = sum(1 for r in sq if r["exec_success"] and r["sql_attempts"] > 1)
    s["guard_blocks"] = sum(r["guard_blocks"] for r in recs)
    s["error_classes"] = dict(collections.Counter(c for r in recs for c in r["error_classes"]))
    s["python_runs"] = sum(r["python_runs"] for r in ms)
    s["python_degraded"] = sum(bool(r["python_degraded"]) for r in ms)
    lat = sorted(r["seconds"] for r in sq)
    if lat:
        s["sql_latency_p50"] = lat[len(lat) // 2]
        s["sql_latency_p90"] = lat[int(len(lat) * 0.9)]
    lat = sorted(r["seconds"] for r in ms)
    if lat:
        s["multi_step_latency_p50"] = lat[len(lat) // 2]
    for dim in ("task", "intent"):
        by = collections.defaultdict(list)
        for r in recs:
            by[r[dim]].append(r)
        s["by_" + dim] = {k: {"n": len(v), "exec": rate(x["exec_success"] for x in v),
                              "ex": rate(x.get("ex") for x in v), "completed": rate(x.get("completed") for x in v)}
                          for k, v in sorted(by.items())}
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--split", default="dev")
    ap.add_argument("--tasks", default="single,join,multi_turn,multi_step")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--ids", default="")
    ap.add_argument("--rescore", action="store_true")
    a = ap.parse_args()
    qs = [json.loads(l) for l in open(QFILE, encoding="utf-8")]
    qs = [q for q in qs if q["split"] == a.split and q["task"] in a.tasks.split(",")]
    if a.ids:
        qs = [q for q in qs if q["id"] in a.ids.split(",")]
    if a.limit:
        qs = qs[:a.limit]
    out = os.path.join(ROOT, "eval", "nl2sql", "results", f"{a.config}_{a.split}")
    tdir = os.path.join(out, "traces")
    os.makedirs(tdir, exist_ok=True)
    rfile = os.path.join(out, "results.jsonl")
    done = {}
    if os.path.exists(rfile):
        for l in open(rfile):
            r = json.loads(l)
            done[r["id"]] = r
    byid = {q["id"]: q for q in qs}
    if a.rescore:
        recs = []
        for i, r in done.items():
            if i not in byid:
                continue
            ev = [json.loads(l) for l in open(os.path.join(tdir, i + ".jsonl"))]
            x = extract(ev)
            r.update({k: v for k, v in x.items() if k != "report"})
            recs.append(evaluate(byid[i], r, x["report"]))
        with open(rfile, "w") as f:
            for r in recs:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    else:
        todo = [q for q in qs if q["id"] not in done]
        print(f"{len(todo)} to run ({len(done)} already done) -> {out}", flush=True)
        lock = threading.Lock()
        with cf.ThreadPoolExecutor(a.workers) as ex, open(rfile, "a") as f:
            futs = {ex.submit(run_one, q, a.config, tdir): q for q in todo}
            for k, fu in enumerate(cf.as_completed(futs), 1):
                q = futs[fu]
                rec, report = fu.result()
                rec = evaluate(q, rec, report)
                with lock:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                    f.flush()
                    done[q["id"]] = rec
                print(f"[{k}/{len(todo)}] {q['id']} {rec['seconds']}s exec={rec['exec_success']} "
                      f"ex={rec.get('ex')} done={rec.get('completed')} tries={rec['sql_attempts']}", flush=True)
        recs = [done[i] for i in byid if i in done]
    s = summarize(recs)
    json.dump(s, open(os.path.join(out, "summary.json"), "w"), ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in s.items() if not k.startswith("by_")}, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
