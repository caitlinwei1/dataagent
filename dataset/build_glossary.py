"""Build the business glossary (dataset/metadata/glossary.json) from
  1) hand-written metrics / dimensions / abbreviations in business_terms.yaml
  2) enum values read from the loaded MySQL database (enum-value terms, used for value recall:
     "华东" -> dim_region.big_region = '华东').

Usage: python3 dataset/build_glossary.py [mysql-container]
"""
import json
import os
import subprocess
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
CONTAINER = sys.argv[1] if len(sys.argv) > 1 else "data-agent-mysql-datasource"

# (table, value column, label column or None, what it is)
ENUM_SOURCES = [
    ("dim_region", "big_region", None, "大区"),
    ("dim_region", "province", None, "省份"),
    ("prd_category", "name", None, "商品类目"),
    ("dim_platform", "name", None, "销售平台"),
    ("dim_shop", "name", None, "店铺"),
    ("mkt_channel", "code", "name", "流量渠道"),
    ("trd_order_source", "code", "name", "下单来源"),
    ("trd_aftersale_reason", "code", "name", "售后/退款原因"),
    ("trd_order", "order_status", None, "订单状态"),
    ("trd_refund", "refund_status", None, "退款状态"),
    ("trd_refund", "refund_type", None, "退款类型"),
    ("trd_aftersale", "aftersale_type", None, "售后类型"),
    ("tms_carrier", "name", None, "承运商"),
    ("wms_warehouse", "name", None, "仓库"),
    ("wms_warehouse", "warehouse_type", None, "仓库类型"),
    ("usr_member_level", "name", None, "会员等级"),
    ("usr_member_tag", "tag", None, "会员标签"),
    ("trd_order_tag", "tag", None, "订单标签"),
    ("prd_label", "name", None, "商品标签"),
    ("mkt_activity", "activity_type", None, "活动类型"),
    ("mkt_activity", "name", None, "营销活动"),
    ("trd_payment", "pay_channel", None, "支付渠道"),
    ("mkt_coupon_template", "coupon_type", None, "优惠券类型"),
    ("oms_fulfillment_order", "status", None, "履约单状态"),
    ("tms_shipment_track", "node", None, "物流轨迹节点"),
    ("prd_brand", "name", None, "品牌"),
    ("prd_brand", "level", None, "品牌等级"),
    ("trd_complaint", "complaint_type", None, "客诉类型"),
    ("dim_date", "promo_name", None, "大促名称"),
    ("trd_order", "device_type", None, "下单设备"),
    ("trd_order", "pay_type", None, "支付方式"),
    ("tms_shipment", "exception_type", None, "物流异常类型"),
]


def mysql(sql):
    out = subprocess.run(["docker", "exec", CONTAINER, "mysql", "--default-character-set=utf8mb4", "-uroot", "-proot", "gbi_ecommerce", "-N", "-B", "-e", sql],
                         capture_output=True, text=True, check=True).stdout
    return [line.split("\t") for line in out.strip().split("\n") if line]


def main():
    spec = yaml.safe_load(open(os.path.join(HERE, "metadata", "business_terms.yaml")))
    terms = []
    for m in spec["metrics"]:
        terms.append(dict(type="metric", term=str(m["term"]), synonyms=[str(x) for x in m.get("synonyms", [])],
                          description="；".join(x for x in [m.get("description"), "公式：" + m["formula"],
                                                           "过滤：" + m["filter"] if m.get("filter") else None,
                                                           "涉及：" + ", ".join(m["tables"])] if x),
                          tables=[t.split(".")[0] for t in m["tables"]]))
    for d in spec["dimensions"]:
        terms.append(dict(type="dimension", term=str(d["term"]), synonyms=[str(x) for x in d.get("synonyms", [])], description=d["description"], tables=[]))
    for k, v in spec["abbreviations"].items():
        terms.append(dict(type="abbreviation", term=k, synonyms=[], description=v, tables=[]))
    for table, col, label, what in ENUM_SOURCES:
        sel = f"SELECT DISTINCT {col}" + (f", {label}" if label else "") + f" FROM {table} WHERE {col} IS NOT NULL ORDER BY 1"
        for row in mysql(sel):
            value = row[0]
            name = row[1] if label else value
            syn = [value] if label and value != name else []
            terms.append(dict(type="enum_value", term=name, synonyms=syn,
                              description=f"{what}取值，对应 {table}.{col} = '{value}'", tables=[table],
                              column=f"{table}.{col}", value=value))
    seen, uniq = set(), []
    for t in terms:
        key = (t["term"], t.get("column"))
        if key not in seen:
            seen.add(key)
            uniq.append(t)
    json.dump(uniq, open(os.path.join(HERE, "metadata", "glossary.json"), "w"), ensure_ascii=False, indent=1)
    by = {}
    for t in uniq:
        by[t["type"]] = by.get(t["type"], 0) + 1
    print("terms", len(uniq), by)


if __name__ == "__main__":
    main()
