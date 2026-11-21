"""Run the 宝尊-style difficulty examples (eval/baozun/examples.jsonl) against a running gbi-agent.

For each example: ask the agent once (nl2sqlOnly, or the full graph for multi-step), store the SSE trace, and run the
gold SQL (and the typical wrong SQL, if given) on MySQL so the doc can show the real numbers side by side.

Usage: GBI_AGENT=<id> python3 eval/baozun/run_examples.py [--only B2,B5]
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    exs = [json.loads(l) for l in open(os.path.join(HERE, "examples.jsonl"))]
    if a.only:
        exs = [e for e in exs if e["id"] in a.only.split(",")]
    for e in exs:
        thread = f"baozun-{e['id']}-{uuid.uuid4().hex[:6]}"
        events, secs, _ = R.stream(e["question"], thread, e["mode"] == "nl2sql")
        with open(os.path.join(OUT, f"{e['id']}.trace.jsonl"), "w") as f:
            for ev in events:
                f.write(json.dumps(ev, ensure_ascii=False) + "\n")
        x = R.extract(events)
        rec = dict(id=e["id"], question=e["question"], thread=thread, seconds=secs, sql_attempts=x["sql_attempts"],
                   error_classes=x["error_classes"], guard_blocks=x["guard_blocks"], sql_executed=x["sql_executed"],
                   final_sql=x["final_sql"], result=x["result"], plan_tools=x["plan_tools"],
                   report=x["report"][-6000:], errors=x["errors"],
                   gold=mysql(e["gold_sql"]), wrong=mysql(e["wrong_sql"]) if e.get("wrong_sql") else None)
        json.dump(rec, open(os.path.join(OUT, f"{e['id']}.json"), "w"), ensure_ascii=False, indent=1)
        print(e["id"], secs, "s", "sql_attempts", x["sql_attempts"], "final:", (x["final_sql"] or "")[:300].replace("\n", " "))


if __name__ == "__main__":
    main()
