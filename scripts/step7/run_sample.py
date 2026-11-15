"""40-question paired sample (caitlin's choice when the qwen3.8-flash free quota ran out, 2026-11-15).

Sample: for each of the 20 (task type x intent) cells, one dev and one holdout question (seed 7), excluding the six
smoke-test questions that informed the step-7 fixes. Two backends run side by side on the same Milvus / meta DB:
  :8065  v2               (steps 3-6 + step-7 fixes)
  :8066  upstream_recall  (b297203 table recall, no step-7 fixes)
Each question runs on both at the same time with the same chat model, so every pair is comparable. When a model's free
quota runs out, both backends switch to the next model in MODELS and the question is re-run; the model is recorded.

Usage: python3 scripts/step7/run_sample.py [--workers 3]      (resumable)
"""
import argparse
import collections
import concurrent.futures as cf
import json
import os
import random
import sys
import threading
import time

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "eval", "scripts"))
import run_nl2sql as R  # noqa: E402

BACKENDS = {"v2": "http://localhost:8065", "upstream_recall": "http://localhost:8066"}
MODELS = ["qwen-plus", "qwen-max", "qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max", "qwen3.7-max"]
OUT = os.path.join(ROOT, "eval", "nl2sql", "results", "sample40")
EXCLUDE = {"S06-2", "J01-1", "M04-1", "S16-1", "J12-1", "P01-1"}
QUOTA_MARKERS = ("403 Forbidden", "insufficient_quota", "FreeTierOnly", "Free quota exhausted")

state = {"model": 0, "gen": 0}
lock = threading.Lock()


def sample():
    qs = [json.loads(l) for l in open(R.QFILE, encoding="utf-8")]
    cell = collections.defaultdict(lambda: collections.defaultdict(list))
    for q in qs:
        if q["id"] not in EXCLUDE:
            cell[(q["task"], q["intent"])][q["split"]].append(q)
    rnd = random.Random(7)
    picked = []
    for k in sorted(cell):
        for split in ("dev", "holdout"):
            picked.append(rnd.choice(cell[k][split]))
    return picked


def set_model(name):
    for api in BACKENDS.values():
        items = requests.get(api + "/api/model-config/list", timeout=30).json()
        items = items.get("data", items) if isinstance(items, dict) else items
        chat = [x for x in items if x.get("modelType") == "CHAT"][0]
        chat["modelName"] = name
        r = requests.put(api + "/api/model-config/update", json=chat, timeout=30).json()
        assert r.get("success"), r


def quota_error(rec):
    return any(m in (e or "") for e in rec["errors"] for m in QUOTA_MARKERS)


def run_pair(q):
    while True:
        with lock:
            gen, model = state["gen"], MODELS[state["model"]]
        with cf.ThreadPoolExecutor(2) as ex:
            futs = {c: ex.submit(R.run_one, q, c, os.path.join(OUT, c, "traces"), api + "/api/stream/search")
                    for c, api in BACKENDS.items()}
            out = {c: f.result() for c, f in futs.items()}
        if any(quota_error(rec) for rec, _ in out.values()):
            with lock:
                if state["gen"] == gen:  # first to notice switches the model for everyone
                    state["model"] += 1
                    state["gen"] += 1
                    if state["model"] >= len(MODELS):
                        raise SystemExit("all free-tier models exhausted")
                    set_model(MODELS[state["model"]])
                    print(f"quota exhausted on {model} -> {MODELS[state['model']]}", flush=True)
            continue
        with lock:
            if state["gen"] != gen:  # model switched while this pair was running: re-run on the new model
                continue
        res = {}
        for c, (rec, report) in out.items():
            rec["model"] = model
            res[c] = R.evaluate(q, rec, report)
        return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--start-model", default=MODELS[0])
    a = ap.parse_args()
    qs = sample()
    os.makedirs(OUT, exist_ok=True)
    json.dump([q["id"] for q in qs], open(os.path.join(OUT, "sample_ids.json"), "w"))
    for c in BACKENDS:
        os.makedirs(os.path.join(OUT, c, "traces"), exist_ok=True)
    done = set()
    rfiles = {c: os.path.join(OUT, c, "results.jsonl") for c in BACKENDS}
    if all(os.path.exists(f) for f in rfiles.values()):
        ids = [set(json.loads(l)["id"] for l in open(f)) for f in rfiles.values()]
        done = set.intersection(*ids)
    state["model"] = MODELS.index(a.start_model)
    set_model(MODELS[state["model"]])
    todo = [q for q in qs if q["id"] not in done]
    print(f"{len(todo)} pairs to run, model {MODELS[state['model']]}", flush=True)
    fh = {c: open(f, "a") for c, f in rfiles.items()}
    with cf.ThreadPoolExecutor(a.workers) as ex:
        futs = {ex.submit(run_pair, q): q for q in todo}
        for k, fu in enumerate(cf.as_completed(futs), 1):
            q = futs[fu]
            res = fu.result()
            with lock:
                for c, rec in res.items():
                    fh[c].write(json.dumps(rec, ensure_ascii=False) + "\n")
                    fh[c].flush()
            v, u = res["v2"], res["upstream_recall"]
            print(f"[{k}/{len(todo)}] {q['id']} {v['model']} v2 ex={v.get('ex')} done={v.get('completed')} "
                  f"| upstream ex={u.get('ex')} done={u.get('completed')}", flush=True)
    for c in BACKENDS:
        recs = [json.loads(l) for l in open(rfiles[c])]
        s = R.summarize(recs)
        s["models"] = dict(collections.Counter(r["model"] for r in recs))
        for split in ("dev", "holdout"):
            s[split] = {k: v for k, v in R.summarize([r for r in recs if r["split"] == split]).items()
                        if not k.startswith("by_")}
        json.dump(s, open(os.path.join(OUT, c, "summary.json"), "w"), ensure_ascii=False, indent=1)
        print(c, json.dumps({k: s[k] for k in ("sql_exec_success", "ex", "multi_step_completion", "models")},
                            ensure_ascii=False))


if __name__ == "__main__":
    main()
