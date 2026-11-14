#!/usr/bin/env python3
"""Run the 180 table-selection questions against /api/eval/schema-recall and compute Recall@5.

Configs: original (b297203, question ignored) and cumulative channel sets of the fixed recall.
Recall@5 (mean) = average over questions of |gold ∩ top5| / |gold|;  strict = share of questions whose gold
set is entirely inside top5.  Also reports Recall@10 (the node hands 10 tables to the next step).
Usage: run_table_select.py [--api URL] [--agent 5] [--tag full|partial]
"""
import argparse, collections, json, os, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CHANNELS = ["vector", "column", "keyword", "term", "value", "join", "multiTurn"]
# original, then each channel added cumulatively
CONFIGS = [("original", "original", None)] + [
    (("+" if i else "") + c, "v2", CHANNELS[:i + 1]) for i, c in enumerate(CHANNELS)]


def post(api, body):
    req = urllib.request.Request(api + "/api/eval/schema-recall", json.dumps(body).encode(),
                                 {"content-type": "application/json"})
    for i in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except Exception as e:  # embedding API hiccups
            if i == 3:
                raise
            time.sleep(2 * (i + 1))


def recall(gold, top):
    return len(set(gold) & set(top)) / len(gold)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8065")
    ap.add_argument("--agent", type=int, default=5)
    ap.add_argument("--tag", default="full")
    ap.add_argument("--configs", default=",".join(c[0] for c in CONFIGS))
    a = ap.parse_args()
    qs = [json.loads(l) for l in open(os.path.join(ROOT, "eval/table_select/questions.jsonl"))]
    outdir = os.path.join(ROOT, "eval/table_select/results", a.tag)
    os.makedirs(outdir, exist_ok=True)
    summary = {}
    for name, mode, ch in CONFIGS:
        if name not in a.configs.split(","):
            continue
        rows, t0 = [], time.time()
        for q in qs:
            res = post(a.api, {"agentId": a.agent, "query": q["question"], "context": q["context"], "mode": mode,
                               "channels": ch, "topK": 10})
            top = res["tables"]
            rows.append({"id": q["id"], "tags": q["tags"], "gold": q["gold_tables"], "top5": top[:5], "top10": top,
                         "recall5": recall(q["gold_tables"], top[:5]), "recall10": recall(q["gold_tables"], top),
                         "detail": res.get("detail")})
        with open(os.path.join(outdir, f"{name.lstrip('+')}.jsonl"), "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        n = len(rows)
        s = {"recall5_mean": sum(r["recall5"] for r in rows) / n,
             "recall5_strict": sum(r["recall5"] == 1 for r in rows) / n,
             "recall10_mean": sum(r["recall10"] for r in rows) / n,
             "seconds_per_q": (time.time() - t0) / n}
        by = collections.defaultdict(list)
        for r in rows:
            for t in r["tags"]:
                by[t].append(r["recall5"])
        s["recall5_by_tag"] = {t: round(sum(v) / len(v), 4) for t, v in sorted(by.items())}
        summary[name] = s
        print(f"{name:12s} R@5 mean {s['recall5_mean']:.3f} strict {s['recall5_strict']:.3f} R@10 {s['recall10_mean']:.3f}",
              file=sys.stderr)
    with open(os.path.join(outdir, "summary.json"), "w") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
