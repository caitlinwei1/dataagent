"""Push edited business terms (dataset/metadata/glossary.json) into an existing agent without re-registering it.

Only terms whose description or synonyms changed are updated (each update re-embeds that one term).
Usage: python3 scripts/sync_knowledge.py --agent 5 [--api http://localhost:8065]
"""
import argparse
import json
import os

import requests

META = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dataset", "metadata")


def unwrap(r):
    r = r.json()
    return r.get("data", r) if isinstance(r, dict) else r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8065")
    ap.add_argument("--agent", type=int, required=True)
    a = ap.parse_args()
    have = {k["businessTerm"]: k for k in unwrap(requests.get(f"{a.api}/api/business-knowledge",
                                                              params={"agentId": a.agent}, timeout=60))}
    changed = 0
    for t in json.load(open(os.path.join(META, "glossary.json"))):
        syn = ",".join(map(str, t["synonyms"]))
        k = have.get(t["term"])
        if k is None or (k.get("description") == t["description"] and (k.get("synonyms") or "") == syn):
            continue
        body = dict(businessTerm=t["term"], description=t["description"], synonyms=syn, agentId=a.agent)
        r = requests.put(f"{a.api}/api/business-knowledge/{k['id']}", json=body, timeout=120)
        r.raise_for_status()
        changed += 1
    print("business terms updated:", changed)


if __name__ == "__main__":
    main()
