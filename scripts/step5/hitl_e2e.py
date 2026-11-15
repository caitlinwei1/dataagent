"""End-to-end plan-review (HITL) runs against the live backend; every SSE event is saved for the record.

Scenarios:
  approve   question -> plan shown (graph pauses) -> approve -> executes
  edit      question -> plan -> reviewer edits the plan JSON (adds a filter) -> the edited plan executes
  reject    question -> plan -> reject 1, 2, 3 (each gives a new plan) -> 4th reject ends the turn
  resume    question -> plan -> (backend restarted by the caller) -> approve the same threadId -> executes

usage: hitl_e2e.py <scenario> [--phase start|finish] [--thread ID]
Output: results/step5/<scenario>/<n>_<label>.jsonl and summary.json
"""
import argparse, json, os, sys, time, uuid
import requests

API = "http://localhost:8065/api/stream/search"
AGENT = "5"
Q = "2026年3月各大区的GMV分别是多少？"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "results", "step5")


def stream(params, out):
    events, t0 = [], time.time()
    with requests.get(API, params=params, stream=True, timeout=900) as r:
        for line in r.iter_lines(decode_unicode=True):
            if line and line.startswith("data:"):
                try:
                    events.append(json.loads(line[5:]))
                except ValueError:
                    pass
    with open(out, "w") as f:
        for e in events:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    return events, round(time.time() - t0, 1)


def text(events, node):
    return "".join(e.get("text") or "" for e in events if e.get("nodeName") == node)


def plan_of(events):
    raw = "".join(e.get("text") or "" for e in events if e.get("nodeName") == "PlannerNode" and e.get("textType") == "JSON")
    try:
        return json.loads(raw)
    except ValueError:
        s, e = raw.find("{"), raw.rfind("}")
        return json.loads(raw[s:e + 1]) if s >= 0 else None


def nodes(events):
    seen = []
    for e in events:
        n = e.get("nodeName")
        if n and (not seen or seen[-1] != n):
            seen.append(n)
    return seen


def record(events, secs, label):
    p = plan_of(events)
    return dict(label=label, seconds=secs, nodes=nodes(events), plan_steps=len(p["execution_plan"]) if p else None,
                plan=p, feedback_msg=text(events, "HumanFeedbackNode").strip(),
                sql=text(events, "SqlGenerateNode")[-600:], errors=[e.get("text") for e in events if e.get("error")])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario")
    ap.add_argument("--phase", default="all")
    ap.add_argument("--thread")
    ap.add_argument("--out", help="output folder name (default: the scenario)")
    a = ap.parse_args()
    d = os.path.join(ROOT, a.out or a.scenario)
    os.makedirs(d, exist_ok=True)
    tid = a.thread or str(uuid.uuid4())
    base = dict(agentId=AGENT, threadId=tid, query=Q, humanFeedback="true")
    rounds = []
    sp = os.path.join(d, "summary.json")
    if a.phase == "finish" and os.path.exists(sp):
        rounds = json.load(open(sp))["rounds"]

    def go(label, **extra):
        ev, secs = stream({**base, **extra}, os.path.join(d, f"{len(rounds) + 1}_{label}.jsonl"))
        rounds.append(record(ev, secs, label))
        print(label, secs, "s", rounds[-1]["nodes"], "|", rounds[-1]["feedback_msg"][:80], flush=True)
        return ev

    if a.phase in ("all", "start"):
        go("question")
    if a.scenario == "approve":
        go("approve", humanFeedbackContent="同意，执行")
    elif a.scenario == "edit":
        plan = rounds[-1]["plan"]
        plan["execution_plan"][0]["tool_parameters"]["instruction"] += "；只统计华东大区（dim_region.big_region = '华东'）"
        plan["execution_plan"] = plan["execution_plan"][:1]
        go("edit", humanFeedbackContent="只看华东，只要第一步", editedPlan=json.dumps(plan, ensure_ascii=False))
    elif a.scenario == "reject":
        for i, why in enumerate(["请按省份而不是大区拆分", "请加上同比", "请只看天猫平台", "还是不对"], 1):
            go(f"reject{i}", humanFeedbackContent=why, rejectedPlan="true")
    elif a.scenario == "resume" and a.phase in ("all", "finish"):
        go("approve_after_restart", humanFeedbackContent="同意，执行")
    json.dump(dict(thread=tid, question=Q, rounds=rounds), open(sp, "w"), ensure_ascii=False, indent=1)
    print("thread", tid)


if __name__ == "__main__":
    main()
