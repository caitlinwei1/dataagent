"""Render eval/table_select/results/{full,partial}/summary.json as Markdown tables."""
import json, os, sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "table_select", "results")
TAGS = ["single", "join", "multi", "syn", "abbr", "enum", "look", "ablate"]


def pct(x):
    return "-" if x is None else f"{100 * x:.1f}%"


def main(tags=("full", "partial")):
    s = {t: json.load(open(os.path.join(ROOT, t, "summary.json"))) for t in tags}
    names = list(s[tags[0]].keys())
    out = ["| 配置 | " + " | ".join(f"{t} R@5 均值 | {t} R@5 全中 | {t} R@10" for t in tags) + " | 秒/题 |",
           "|---|" + "---|" * (3 * len(tags) + 1)]
    for n in names:
        row = [n]
        for t in tags:
            r = s[t].get(n, {})
            row += [pct(r.get("recall5_mean")), pct(r.get("recall5_strict")), pct(r.get("recall10_mean"))]
        row.append(f"{s[tags[0]].get(n, {}).get('seconds_per_q', 0):.2f}")
        out.append("| " + " | ".join(row) + " |")
    out.append("")
    for t in tags:
        out.append(f"**{t}：按题型 R@5 均值**\n")
        out.append("| 配置 | " + " | ".join(TAGS) + " |")
        out.append("|---|" + "---|" * len(TAGS))
        for n in names:
            bt = s[t].get(n, {}).get("recall5_by_tag", {})
            out.append("| " + n + " | " + " | ".join(pct(bt.get(g)) for g in TAGS) + " |")
        out.append("")
    print("\n".join(out))


if __name__ == "__main__":
    main(tuple(sys.argv[1:]) or ("full", "partial"))
