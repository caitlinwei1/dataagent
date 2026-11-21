"""Run the 宝尊-style difficulty examples (eval/baozun/examples.jsonl) against a running gbi-agent.

For each example: ask the agent once (nl2sqlOnly, or the full graph for multi-step), store the SSE trace, and run the
gold SQL (and the typical wrong SQL, if given) on MySQL so the doc can show the real numbers side by side.

Usage: GBI_AGENT=<id> python3 eval/baozun/run_examples.py [--only B2,B5]
       B1 through the gateway as a brand operator (SECURITY=on):
       GBI_API=http://localhost:8080 GBI_USER=qinglan_ops python3 eval/baozun/run_examples.py --only B1
Results go to results/baozun/<id>.json; --tag after writes <id>.after.json so the before/after files sit side by side.
"""
import argparse
import json
import os
import subprocess
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "eval", "scripts"))
import run_nl2sql as R  # noqa: E402

OUT = os.path.join(ROOT, "results", "baozun")
MY = os.environ.get("MYSQL_CONTAINER", "data-agent-mysql-datasource")


def mysql(sql):
    p = subprocess.run(["docker", "exec", "-i", MY, "mysql", "--default-character-set=utf8mb4", "-uroot", "-proot",
                        "-B", "gbi_ecommerce", "-e", sql], capture_output=True, text=True)
    return (p.stdout or p.stderr).strip()


def notes(events):
    """Messages the new fixes emit (replan, semantic bypass, schema expansion, freshness), for the doc."""
    keys = ("回到规划器", "不再由模型把关", "沿外键补充表", "数据时效提示")
    out = []
    for seg in R.segments(events):
        for k in keys:
            if k in seg[1]:
                i = seg[1].find(k)
                out.append(seg[1][max(0, i - 40):i + 160].strip())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    user = os.environ.get("GBI_USER")
    if user:
        import requests
        gw = os.environ.get("GBI_API", "http://localhost:8080")
        os.environ["GBI_TOKEN"] = requests.post(gw + "/auth/login", json={"username": user, "password": "Gbi@2026"},
                                                timeout=30).json()["accessToken"]
    os.makedirs(OUT, exist_ok=True)
    exs = [json.loads(l) for l in open(os.path.join(HERE, "examples.jsonl"))]
    if a.only:
        exs = [e for e in exs if e["id"] in a.only.split(",")]
    for e in exs:
        suffix = f".{a.tag}" if a.tag else ""
        thread = f"baozun-{e['id']}-{uuid.uuid4().hex[:6]}"
        events, secs, _ = R.stream(e["question"], thread, e["mode"] == "nl2sql")
        with open(os.path.join(OUT, f"{e['id']}{suffix}.trace.jsonl"), "w") as f:
            for ev in events:
                f.write(json.dumps(ev, ensure_ascii=False) + "\n")
        x = R.extract(events)
        rec = dict(id=e["id"], question=e["question"], thread=thread, seconds=secs, sql_attempts=x["sql_attempts"],
                   error_classes=x["error_classes"], guard_blocks=x["guard_blocks"], sql_executed=x["sql_executed"],
                   final_sql=x["final_sql"], result=x["result"], plan_tools=x["plan_tools"],
                   report=x["report"][-6000:], errors=x["errors"], user=user,
                   notes=[t for t in notes(events)],
                   gold=mysql(e["gold_sql"]), wrong=mysql(e["wrong_sql"]) if e.get("wrong_sql") else None)
        json.dump(rec, open(os.path.join(OUT, f"{e['id']}{suffix}.json"), "w"), ensure_ascii=False, indent=1)
        print(e["id"], secs, "s", "sql_attempts", x["sql_attempts"], "final:", (x["final_sql"] or "")[:300].replace("\n", " "))


if __name__ == "__main__":
    main()
