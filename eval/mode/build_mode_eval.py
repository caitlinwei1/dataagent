"""Question set and case library for the Turbo / Mix comparison (eval/mode/).

Leakage rules
  - only dev questions (the 128-question holdout stays unused)
  - eval questions: the 28 dev families the 40-question sample of step 7 never touched (the rules in
    gbi-agent/src/main/resources/gbi/rules.yaml were written from that sample's misses), SQL task types only
    (single / join / multi_turn), one question per family (variant chosen with seed 11)
  - case library: one gold question + SQL from every *other* dev family, so a case is never a variant of an eval
    question; the eval question's own family is absent from the library

Outputs eval/mode/questions.jsonl and eval/mode/cases.jsonl.
"""
import collections
import json
import os
import random

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
Q = [json.loads(l) for l in open(os.path.join(ROOT, "eval", "nl2sql", "questions.jsonl"), encoding="utf-8")]
S40 = {json.loads(l)["id"] for l in open(os.path.join(ROOT, "eval", "nl2sql", "results", "sample40", "v2", "results.jsonl"))}


def fam(q):
    return q["id"].split("-")[0]


def main():
    dev = collections.OrderedDict()
    for q in Q:
        if q["split"] == "dev":
            dev.setdefault(fam(q), []).append(q)
    touched = {fam(q) for q in Q if q["id"] in S40}
    rnd = random.Random(11)
    evalq, cases = [], []
    for f, qs in dev.items():
        if f in touched:
            g = next((q for q in qs if q.get("gold_sql") or q.get("key_sql")), None)
            if g:
                text = " ".join(g["context"] + [g["question"]])
                cases.append(dict(family=f, question=text, sql=g.get("gold_sql") or g["key_sql"]))
        elif qs[0]["task"] != "multi_step":
            evalq.append(rnd.choice(qs))
    assert not {fam(q) for q in evalq} & {c["family"] for c in cases}
    with open(os.path.join(ROOT, "eval", "mode", "questions.jsonl"), "w", encoding="utf-8") as fo:
        for q in evalq:
            fo.write(json.dumps(q, ensure_ascii=False) + "\n")
    with open(os.path.join(ROOT, "eval", "mode", "cases.jsonl"), "w", encoding="utf-8") as fo:
        for c in cases:
            fo.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(len(evalq), "eval questions", dict(collections.Counter(q["task"] for q in evalq)), ";", len(cases), "cases")


if __name__ == "__main__":
    main()
