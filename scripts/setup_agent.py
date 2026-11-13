"""Register the gbi_ecommerce dataset in DataAgent (b297203 REST API):
datasource (read-only account) -> agent -> 86 tables -> logical relations -> 312 business terms
-> semantic model -> schema init -> publish.

Usage: python3 scripts/setup_agent.py [--api http://localhost:8065] [--type mysql|postgresql] [--skip-knowledge]
Prints the agent id.
"""
import argparse
import json
import os
import urllib.request

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
META = os.path.join(ROOT, "dataset", "metadata")


def call(api, method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(api + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        txt = r.read().decode()
    return json.loads(txt) if txt else None


def unwrap(resp):
    return resp.get("data", resp) if isinstance(resp, dict) and "success" in resp else resp


TYPE_NAMES = {"id": "bigint", "b": "bigint", "i": "int", "t": "tinyint", "s": "varchar(64)", "l": "varchar(255)",
              "d": "decimal(14,2)", "r": "decimal(12,6)", "dt": "date", "ts": "datetime"}


def import_semantic(api, aid):
    cols = {(t["name"], c["name"]): c for t in json.load(open(os.path.join(META, "tables.json"))) for c in t["columns"]}
    items = [dict(tableName=s["table"], columnName=s["column"], businessName=s["name"], synonyms=s["synonyms"],
                  businessDescription=s["desc"], columnComment=cols[(s["table"], s["column"])]["comment"],
                  dataType=TYPE_NAMES[cols[(s["table"], s["column"])]["type"]])
             for s in yaml.safe_load(open(os.path.join(META, "semantic_model.yaml")))]
    print("semantic model", unwrap(call(api, "POST", "/api/semantic-model/batch-import", dict(agentId=aid, items=items))))


def import_knowledge(api, aid):
    for t in json.load(open(os.path.join(META, "glossary.json"))):
        call(api, "POST", "/api/business-knowledge", dict(businessTerm=t["term"], description=t["description"],
                                                         synonyms=",".join(map(str, t["synonyms"])), isRecall=True, agentId=aid))
    print("business knowledge imported")
    import_semantic(api, aid)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8065")
    ap.add_argument("--type", default="mysql", choices=["mysql", "postgresql"])
    ap.add_argument("--name", default="电商经营分析")
    ap.add_argument("--skip-knowledge", action="store_true")
    ap.add_argument("--semantic-only", type=int, metavar="AGENT_ID")
    ap.add_argument("--knowledge-only", type=int, metavar="AGENT_ID", help="only import knowledge + init for an existing agent")
    a = ap.parse_args()
    api = a.api

    if a.semantic_only:
        import_semantic(api, a.semantic_only)
        print("init", call(api, "POST", f"/api/agent/{a.semantic_only}/datasources/init"))
        return
    if a.knowledge_only:
        import_knowledge(api, a.knowledge_only)
        print("init", call(api, "POST", f"/api/agent/{a.knowledge_only}/datasources/init"))
        return
    if a.type == "mysql":
        ds = dict(name=f"gbi_ecommerce (MySQL, 只读)", type="mysql", host="127.0.0.1", port=13306, databaseName="gbi_ecommerce",
                  username="gbi_reader", password="gbi_reader_local",
                  connectionUrl="jdbc:mysql://127.0.0.1:13306/gbi_ecommerce?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                  status="active", description="电商经营分析 86 表（只读账号）")
    else:
        ds = dict(name=f"gbi_ecommerce (PostgreSQL, 只读)", type="postgresql", host="127.0.0.1", port=15432, databaseName="postgres",
                  username="gbi_reader", password="gbi_reader_local",
                  connectionUrl="jdbc:postgresql://127.0.0.1:15432/postgres?currentSchema=gbi_ecommerce",
                  status="active", description="电商经营分析 86 表（只读账号，Hologres 替身）")
    ds = unwrap(call(api, "POST", "/api/datasource", ds))
    dsid = ds["id"]
    print("datasource", dsid, unwrap(call(api, "POST", f"/api/datasource/{dsid}/test")))

    agent = call(api, "POST", "/api/agent", dict(name=a.name, description="电商经营分析：交易、商品、用户营销、库存履约、财务", status="draft",
                                              category="电商", tags="GBI,电商,履约"))
    aid = agent["id"]
    print("agent", aid)
    call(api, "POST", f"/api/agent/{aid}/datasources/{dsid}")
    call(api, "PUT", f"/api/agent/{aid}/datasources/toggle", dict(datasourceId=dsid, isActive=True))
    tables = [t["name"] for t in json.load(open(os.path.join(META, "tables.json")))]
    call(api, "POST", f"/api/agent/{aid}/datasources/tables", dict(datasourceId=dsid, tables=tables))

    for r in json.load(open(os.path.join(META, "logical_relations.json"))):
        call(api, "POST", f"/api/datasource/{dsid}/logical-relations", dict(
            sourceTableName=r["table"], sourceColumnName=r["column"], targetTableName=r["ref_table"],
            targetColumnName=r["ref_column"], relationType="N:1",
            description=f"{r['table']}.{r['column']} 关联 {r['ref_table']}.{r['ref_column']}"))
    print("logical relations imported")

    if not a.skip_knowledge:
        import_knowledge(api, aid)

    print("init", call(api, "POST", f"/api/agent/{aid}/datasources/init"))
    call(api, "POST", f"/api/agent/{aid}/publish")
    print("AGENT_ID", aid)


if __name__ == "__main__":
    main()
