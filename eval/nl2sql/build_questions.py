"""Build the 320-question 问数 eval set (eval/nl2sql/questions.jsonl) and validate every gold SQL on MySQL.

80 question families x 4 parameter sets.
  task types : single (单表) 24 / join (多表) 24 / multi_turn (多轮) 16 / multi_step (多步分析) 16 families
  intents    : top / agg / cmp / trend / drill, 16 families each (64 questions each)
  split      : by family (a template is entirely dev or holdout) AND by time (dev = 2026-02/03, holdout = 2026-04;
               inventory snapshots exist only for 2026-08..10: dev = 08/09, holdout = 10)
Metric definitions follow dataset/metadata/business_terms.yaml (GMV, 实付金额, 销售额 ...).

Usage: python3 eval/nl2sql/build_questions.py   (needs the gbi MySQL container; writes questions.jsonl + gold/)
"""
import json
import os
import re
import subprocess
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "eval", "nl2sql")


def nxt(m):
    y, mo = map(int, m.split("-"))
    return f"{y + (mo == 12)}-{mo % 12 + 1:02d}"


def prev(m, k=1):
    y, mo = map(int, m.split("-"))
    t = y * 12 + mo - 1 - k
    return f"{t // 12}-{t % 12 + 1:02d}"


def cn(m):
    y, mo = m.split("-")
    return f"{y}年{int(mo)}月"


def rng(col, m):
    return f"{col} >= '{m}-01' AND {col} < '{nxt(m)}-01'"


REG = "JOIN dim_shop s ON o.shop_id = s.id JOIN dim_region r ON s.region_id = r.id"
PLAT = "JOIN dim_shop s ON o.shop_id = s.id JOIN dim_platform p ON s.platform_id = p.id"
CAT = ("JOIN prd_category c3 ON i.category_id = c3.id JOIN prd_category c2 ON c3.parent_id = c2.id "
       "JOIN prd_category c1 ON c2.parent_id = c1.id")
SALE = "o.order_status NOT IN ('CANCELLED','CLOSED')"
GMV = "SUM(o.total_amount + o.freight_amount)"
DEV2 = ["2026-02", "2026-03", "2026-03", "2026-02"]
HO = ["2026-04"] * 4
DEV4 = ["2026-02", "2026-03", "2025-03", "2025-02"]
HO4 = ["2026-04", "2025-04", "2025-12", "2025-11"]
DEVINV = ["2026-08", "2026-09", "2026-09", "2026-08"]
HOINV = ["2026-10"] * 4
DEVICE = {"IOS": "iOS端", "ANDROID": "安卓端", "PC": "PC端", "H5": "H5端"}

F = []  # families


def fam(fid, task, intent, split, domain, q, sql, params, ctx=None, key_sql=None, key_desc=None, order=False):
    F.append(dict(fid=fid, task=task, intent=intent, split=split, domain=domain, q=q, sql=sql, params=params,
                  ctx=ctx or [], key_sql=key_sql, key_desc=key_desc, order=order))


def P(months, **lists):
    out = []
    for i, m in enumerate(months):
        d = {"m": m}
        for k, v in lists.items():
            d[k] = v[i % len(v)]
        out.append(d)
    return out


# ---------------------------------------------------------------- single table (24)
fam("S01", "single", "top", "dev", "交易", "{M}GMV最高的{n}天是哪几天？",
    "SELECT stat_date, SUM(gmv) AS gmv FROM trd_daily_shop_sales WHERE stat_date >= '{m}-01' AND stat_date < '{m1}-01' "
    "GROUP BY stat_date ORDER BY gmv DESC LIMIT {n}", P(DEV2, n=[3, 5, 3, 5]))
fam("S02", "single", "top", "holdout", "营销", "{M}观看人数最多的{n}场直播，主播和标题分别是什么？",
    "SELECT anchor, title, viewers FROM mkt_live_session WHERE {r:start_at} ORDER BY viewers DESC LIMIT {n}",
    P(HO, n=[3, 5, 1, 2]))
fam("S03", "single", "top", "dev", "营销", "{M}广告花费最高的{n}个投放计划是哪些？",
    "SELECT campaign_name, SUM(spend) AS spend FROM mkt_ad_spend WHERE stat_date >= '{m}-01' AND stat_date < '{m1}-01' "
    "GROUP BY campaign_name ORDER BY spend DESC LIMIT {n}", P(DEV2, n=[3, 5, 3, 5]))
fam("S04", "single", "top", "holdout", "供应链", "{prov}的供应商里评分最高的{n}家是哪些？",
    "SELECT name, rating FROM scm_supplier WHERE province = '{prov}' ORDER BY rating DESC LIMIT {n}",
    P(HO, prov=["上海", "浙江", "河南", "重庆"], n=[1, 2, 1, 2]))
fam("S05", "single", "top", "dev", "交易", "{M}退款金额最高的{n}笔成功退款，退款单号和金额是多少？",
    "SELECT refund_no, refund_amount FROM trd_refund WHERE refund_status = 'SUCCESS' AND {r:refunded_at} "
    "ORDER BY refund_amount DESC LIMIT {n}", P(DEV2, n=[3, 5, 3, 5]))
fam("S06", "single", "agg", "dev", "交易", "{M}的GMV是多少？",
    f"SELECT {GMV} AS gmv FROM trd_order o WHERE {{r:o.paid_at}}", P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S07", "single", "agg", "holdout", "交易", "{M}的支付订单数和客单价分别是多少？",
    "SELECT COUNT(DISTINCT id) AS order_cnt, SUM(pay_amount) / COUNT(DISTINCT id) AS atv FROM trd_order WHERE {r:paid_at}",
    P(HO4))
fam("S08", "single", "agg", "dev", "交易", "{M}退款成功的有多少笔，退款金额合计多少？",
    "SELECT COUNT(*) AS refund_cnt, SUM(refund_amount) AS refund_amount FROM trd_refund "
    "WHERE refund_status = 'SUCCESS' AND {r:refunded_at}", P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S09", "single", "agg", "holdout", "交易", "{M}{devname}的下单数是多少？",
    "SELECT COUNT(*) AS order_cnt FROM trd_order WHERE device_type = '{dev}' AND {r:created_at}",
    P(HO, dev=["IOS", "ANDROID", "PC", "H5"]))
fam("S10", "single", "agg", "dev", "营销", "{M}领取的优惠券里，已使用的占比是多少？",
    "SELECT SUM(status = 'USED') / COUNT(*) AS used_ratio FROM mkt_coupon WHERE {r:received_at}",
    P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S11", "single", "cmp", "dev", "交易", "{M}iOS端和安卓端的实付金额分别是多少？",
    "SELECT device_type, SUM(pay_amount) AS pay_amount FROM trd_order WHERE device_type IN ('IOS','ANDROID') "
    "AND {r:paid_at} GROUP BY device_type", P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S12", "single", "cmp", "holdout", "交易", "对比一下{M}和{Mp}的支付订单数",
    "SELECT DATE_FORMAT(paid_at, '%Y-%m') AS month, COUNT(*) AS order_cnt FROM trd_order "
    "WHERE paid_at >= '{mp}-01' AND paid_at < '{m1}-01' GROUP BY month", P(["2026-04", "2025-04", "2025-12", "2025-10"]))
fam("S13", "single", "cmp", "dev", "交易", "{M}仅退款和退货退款各自的成功退款金额是多少？",
    "SELECT refund_type, SUM(refund_amount) AS refund_amount FROM trd_refund WHERE refund_status = 'SUCCESS' "
    "AND {r:refunded_at} GROUP BY refund_type", P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S14", "single", "cmp", "holdout", "交易", "{M}预售订单和非预售订单的客单价对比",
    "SELECT is_presale, SUM(pay_amount) / COUNT(*) AS atv FROM trd_order WHERE {r:paid_at} GROUP BY is_presale",
    P(["2026-04", "2025-04", "2025-10", "2025-11"]))
fam("S15", "single", "cmp", "dev", "交易", "{M}不同支付方式（在线支付、货到付款、分期）的支付订单数各是多少？",
    "SELECT pay_type, COUNT(*) AS order_cnt FROM trd_order WHERE {r:paid_at} GROUP BY pay_type",
    P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S16", "single", "trend", "dev", "交易", "{M}每天的GMV是多少？",
    f"SELECT DATE(o.paid_at) AS d, {GMV} AS gmv FROM trd_order o WHERE {{r:o.paid_at}} GROUP BY d ORDER BY d",
    P(["2026-02", "2026-03", "2025-03", "2025-02"]), order=True)
fam("S17", "single", "trend", "holdout", "交易", "{A}到{B}每个月的支付订单数是多少？",
    "SELECT DATE_FORMAT(paid_at, '%Y-%m') AS month, COUNT(*) AS order_cnt FROM trd_order "
    "WHERE paid_at >= '{a}-01' AND paid_at < '{b1}-01' GROUP BY month ORDER BY month",
    [{"m": "2026-04", "a": a, "b": b} for a, b in [("2025-04", "2025-09"), ("2025-10", "2026-04"), ("2025-06", "2025-12"),
                                                    ("2026-01", "2026-04")]], order=True)
fam("S18", "single", "trend", "dev", "营销", "{M}每天的UV是多少？",
    "SELECT stat_date, SUM(uv) AS uv FROM mkt_traffic_daily WHERE stat_date >= '{m}-01' AND stat_date < '{m1}-01' "
    "GROUP BY stat_date ORDER BY stat_date", P(["2026-02", "2026-03", "2025-03", "2025-02"]), order=True)
fam("S19", "single", "trend", "holdout", "营销", "截至{M}的近{k}个月，每个月的广告花费是多少？",
    "SELECT DATE_FORMAT(stat_date, '%Y-%m') AS month, SUM(spend) AS spend FROM mkt_ad_spend "
    "WHERE stat_date >= '{mk}-01' AND stat_date < '{m1}-01' GROUP BY month ORDER BY month",
    P(HO, k=[3, 6, 4, 2]), order=True)
fam("S20", "single", "trend", "dev", "会员", "{M}每天新注册的会员有多少？",
    "SELECT DATE(register_at) AS d, COUNT(*) AS cnt FROM usr_member WHERE {r:register_at} GROUP BY d ORDER BY d",
    P(["2026-02", "2026-03", "2025-03", "2025-02"]), order=True)
fam("S21", "single", "drill", "dev", "交易", "{M}下单的订单里，各订单状态分别有多少单？",
    "SELECT order_status, COUNT(*) AS cnt FROM trd_order WHERE {r:created_at} GROUP BY order_status",
    P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S22", "single", "drill", "holdout", "交易", "{M}各设备端的支付订单数和实付金额分别是多少？",
    "SELECT device_type, COUNT(*) AS order_cnt, SUM(pay_amount) AS pay_amount FROM trd_order WHERE {r:paid_at} "
    "GROUP BY device_type", P(["2026-04", "2025-04", "2025-12", "2025-11"]))
fam("S23", "single", "drill", "dev", "交易", "{M}按退款原因编码统计成功退款的笔数",
    "SELECT reason_code, COUNT(*) AS cnt FROM trd_refund WHERE refund_status = 'SUCCESS' AND {r:refunded_at} "
    "GROUP BY reason_code", P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("S24", "single", "drill", "holdout", "交易", "{M}各类型的客诉工单分别有多少？",
    "SELECT complaint_type, COUNT(*) AS cnt FROM trd_complaint WHERE {r:created_at} GROUP BY complaint_type", P(HO4))

# ---------------------------------------------------------------- multi-table join (24)
REGS = ["华东", "华北", "华南", "华中"]
CATS = ["数码家电", "服饰鞋包", "美妆个护", "食品生鲜", "家居日用", "母婴玩具"]
fam("J01", "join", "top", "dev", "交易", "{M}{reg}实付金额最高的{n}家店铺是哪些？",
    f"SELECT s.name, SUM(o.pay_amount) AS pay_amount FROM trd_order o {REG} WHERE r.big_region = '{{reg}}' AND "
    "{r:o.paid_at} GROUP BY s.name ORDER BY pay_amount DESC LIMIT {n}", P(DEV2, reg=REGS, n=[2, 3, 2, 3]))
fam("J02", "join", "top", "holdout", "商品", "{M}销量最高的{n}个SKU，编码和规格是什么？",
    "SELECT k.sku_code, k.spec, SUM(i.quantity) AS qty FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id "
    "JOIN prd_sku k ON i.sku_id = k.id WHERE {r:o.paid_at} GROUP BY k.sku_code, k.spec ORDER BY qty DESC LIMIT {n}",
    P(["2026-04", "2025-04", "2026-04", "2025-04"], n=[3, 5, 5, 3]))
fam("J03", "join", "top", "dev", "商品", "{M}{cat}类目下销售额最高的{n}个品牌是哪些？",
    f"SELECT b.name, SUM(i.pay_share) AS sales FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id {CAT} "
    f"JOIN prd_brand b ON i.brand_id = b.id WHERE c1.name = '{{cat}}' AND {SALE} AND {{r:o.paid_at}} "
    "GROUP BY b.name ORDER BY sales DESC LIMIT {n}", P(DEV2, cat=CATS, n=[3, 3, 5, 5]))
fam("J04", "join", "top", "holdout", "履约", "{M}缺货次数最多的{n}个仓库是哪些？",
    "SELECT w.name, COUNT(*) AS cnt FROM wms_stockout_record so JOIN wms_warehouse w ON so.warehouse_id = w.id "
    "WHERE {r:so.detected_at} GROUP BY w.name ORDER BY cnt DESC LIMIT {n}",
    P(["2026-04", "2025-04", "2026-04", "2025-04"], n=[2, 3, 3, 2]))
fam("J05", "join", "top", "dev", "履约", "{M}从发货到签收平均用时最长的{n}家快递公司是哪些？",
    "SELECT c.name, AVG(TIMESTAMPDIFF(HOUR, t.shipped_at, t.delivered_at)) AS avg_hours FROM tms_shipment t "
    "JOIN tms_carrier c ON t.carrier_id = c.id WHERE {r:t.shipped_at} AND t.delivered_at IS NOT NULL "
    "GROUP BY c.name ORDER BY avg_hours DESC LIMIT {n}", P(DEV2, n=[2, 3, 2, 3]))
fam("J06", "join", "agg", "dev", "交易", "{M}{reg}的GMV是多少？",
    f"SELECT {GMV} AS gmv FROM trd_order o {REG} WHERE r.big_region = '{{reg}}' AND {{r:o.paid_at}}",
    P(DEV2, reg=["华东", "华南", "西南", "华北"]))
fam("J07", "join", "agg", "holdout", "交易", "{M}{plat}平台的支付订单数是多少？",
    f"SELECT COUNT(DISTINCT o.id) AS order_cnt FROM trd_order o {PLAT} WHERE p.name = '{{plat}}' AND {{r:o.paid_at}}",
    P(HO, plat=["天猫", "京东", "抖音", "自营商城"]))
fam("J08", "join", "agg", "dev", "商品", "{M}{cat}类目的销售额是多少？",
    f"SELECT SUM(i.pay_share) AS sales FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id {CAT} "
    f"WHERE c1.name = '{{cat}}' AND {SALE} AND {{r:o.paid_at}}", P(DEV2, cat=CATS))
fam("J09", "join", "agg", "holdout", "履约", "{M}{wh}有多少张出库单，一共出库多少件？",
    "SELECT COUNT(DISTINCT ob.id) AS outbound_cnt, SUM(oi.quantity) AS qty FROM wms_outbound ob "
    "JOIN wms_outbound_item oi ON oi.outbound_id = ob.id JOIN wms_warehouse w ON ob.warehouse_id = w.id "
    "WHERE w.name = '{wh}' AND {r:ob.outbound_at}", P(HO, wh=["华东中心仓", "华南中心仓", "华北区域仓", "上海前置仓"]))
fam("J10", "join", "agg", "dev", "会员", "{M}{lvl}会员的支付买家数是多少？",
    "SELECT COUNT(DISTINCT o.member_id) AS buyers FROM trd_order o JOIN usr_member u ON o.member_id = u.id "
    "JOIN usr_member_level l ON u.level_id = l.id WHERE l.name = '{lvl}' AND {r:o.paid_at}",
    P(DEV2, lvl=["金卡", "银卡", "黑金", "普通"]))
fam("J11", "join", "cmp", "holdout", "交易", "{M}天猫和京东的GMV对比",
    f"SELECT p.name, {GMV} AS gmv FROM trd_order o {PLAT} WHERE p.name IN ('天猫','京东') AND {{r:o.paid_at}} "
    "GROUP BY p.name", P(["2026-04", "2025-04", "2025-12", "2025-11"]))
fam("J12", "join", "cmp", "dev", "交易", "{M}{reg}和{reg2}的客单价分别是多少？",
    f"SELECT r.big_region, SUM(o.pay_amount) / COUNT(DISTINCT o.id) AS atv FROM trd_order o {REG} "
    "WHERE r.big_region IN ('{reg}','{reg2}') AND {r:o.paid_at} GROUP BY r.big_region",
    P(DEV2, reg=["华东", "华北", "华南", "西南"], reg2=["华南", "华中", "西北", "东北"]))
fam("J13", "join", "cmp", "dev", "商品", "{M}自有品牌和非自有品牌的销售额各是多少？",
    f"SELECT b.is_own, SUM(i.pay_share) AS sales FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id "
    f"JOIN prd_brand b ON i.brand_id = b.id WHERE {SALE} AND {{r:o.paid_at}} GROUP BY b.is_own",
    P(["2026-02", "2026-03", "2025-03", "2025-02"]))
fam("J14", "join", "cmp", "holdout", "履约", "{M}各快递公司的准时送达率是多少？",
    "SELECT c.name, SUM(t.delivered_at <= f.promised_at) / COUNT(*) AS on_time_rate FROM oms_fulfillment_order f "
    "JOIN tms_shipment t ON t.fulfillment_id = f.id JOIN tms_carrier c ON t.carrier_id = c.id "
    "WHERE t.delivered_at IS NOT NULL AND {r:t.delivered_at} GROUP BY c.name",
    P(["2026-04", "2025-04", "2025-12", "2025-11"]))
fam("J15", "join", "trend", "dev", "交易", "{M}{reg}每天的实付金额是多少？",
    f"SELECT DATE(o.paid_at) AS d, SUM(o.pay_amount) AS pay_amount FROM trd_order o {REG} "
    "WHERE r.big_region = '{reg}' AND {r:o.paid_at} GROUP BY d ORDER BY d", P(DEV2, reg=REGS), order=True)
fam("J16", "join", "trend", "holdout", "商品", "{y}年{cat}类目每个月的销售额是多少？",
    f"SELECT DATE_FORMAT(o.paid_at, '%Y-%m') AS month, SUM(i.pay_share) AS sales FROM trd_order_item i "
    f"JOIN trd_order o ON i.order_id = o.id {CAT} WHERE c1.name = '{{cat}}' AND {SALE} "
    "AND o.paid_at >= '{y}-01-01' AND o.paid_at < '{y1}-01-01' GROUP BY month ORDER BY month",
    [{"m": "2026-04", "y": 2025, "cat": c} for c in ["数码家电", "美妆个护", "食品生鲜", "母婴玩具"]], order=True)
fam("J17", "join", "trend", "dev", "履约", "{M}{wh}每天的出库单数是多少？",
    "SELECT DATE(ob.outbound_at) AS d, COUNT(*) AS cnt FROM wms_outbound ob JOIN wms_warehouse w "
    "ON ob.warehouse_id = w.id WHERE w.name = '{wh}' AND {r:ob.outbound_at} GROUP BY d ORDER BY d",
    P(DEV2, wh=["华东中心仓", "华南中心仓", "华东区域仓", "华中区域仓"]), order=True)
fam("J18", "join", "trend", "holdout", "交易", "{y}年{plat}平台每个月的GMV是多少？",
    f"SELECT DATE_FORMAT(o.paid_at, '%Y-%m') AS month, {GMV} AS gmv FROM trd_order o {PLAT} WHERE p.name = '{{plat}}' "
    "AND o.paid_at >= '{y}-01-01' AND o.paid_at < '{y1}-01-01' GROUP BY month ORDER BY month",
    [{"m": "2026-04", "y": 2025, "plat": p} for p in ["天猫", "京东", "抖音", "自营商城"]], order=True)
fam("J19", "join", "trend", "dev", "库存", "{M}{wh}每天的可用库存总量是多少？",
    "SELECT sn.snapshot_date, SUM(sn.available_qty) AS available_qty FROM wms_inventory_snapshot sn "
    "JOIN wms_warehouse w ON sn.warehouse_id = w.id WHERE w.name = '{wh}' AND sn.snapshot_date >= '{m}-01' "
    "AND sn.snapshot_date < '{m1}-01' GROUP BY sn.snapshot_date ORDER BY sn.snapshot_date",
    P(DEVINV, wh=["华东中心仓", "华南中心仓", "北京前置仓", "西南区域仓"]), order=True)
fam("J20", "join", "drill", "dev", "交易", "{M}{reg}各省份的GMV是多少？",
    f"SELECT r.province, {GMV} AS gmv FROM trd_order o {REG} WHERE r.big_region = '{{reg}}' AND {{r:o.paid_at}} "
    "GROUP BY r.province", P(DEV2, reg=REGS))
fam("J21", "join", "drill", "holdout", "商品", "{M}{cat}下各二级类目的销售额是多少？",
    f"SELECT c2.name, SUM(i.pay_share) AS sales FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id {CAT} "
    f"WHERE c1.name = '{{cat}}' AND {SALE} AND {{r:o.paid_at}} GROUP BY c2.name", P(HO, cat=CATS))
fam("J22", "join", "drill", "dev", "交易", "{M}{plat}平台各店铺的支付订单数是多少？",
    f"SELECT s.name, COUNT(DISTINCT o.id) AS order_cnt FROM trd_order o {PLAT} WHERE p.name = '{{plat}}' "
    "AND {r:o.paid_at} GROUP BY s.name", P(DEV2, plat=["天猫", "京东", "抖音", "自营商城"]))
fam("J23", "join", "drill", "holdout", "交易", "{M}各退款原因（中文名称）的成功退款金额是多少？",
    "SELECT ar.name, SUM(rf.refund_amount) AS refund_amount FROM trd_refund rf JOIN trd_aftersale_reason ar "
    "ON rf.reason_code = ar.code WHERE rf.refund_status = 'SUCCESS' AND {r:rf.refunded_at} GROUP BY ar.name",
    P(["2026-04", "2025-04", "2025-12", "2025-11"]))
fam("J24", "join", "drill", "dev", "交易", "{M}{reg}各店铺的成功退款金额是多少？",
    f"SELECT s.name, SUM(rf.refund_amount) AS refund_amount FROM trd_refund rf JOIN trd_order o ON rf.order_id = o.id "
    f"{REG} WHERE r.big_region = '{{reg}}' AND rf.refund_status = 'SUCCESS' AND {{r:rf.refunded_at}} GROUP BY s.name",
    P(DEV2, reg=["华东", "华南", "华北", "西南"]))

# ---------------------------------------------------------------- multi-turn (16): only the last turn is scored
fam("M01", "multi_turn", "top", "dev", "交易", "GMV最高的那个大区里，排名前{n}的店铺是哪些？",
    f"SELECT s.name, {GMV} AS gmv FROM trd_order o {REG} WHERE {{r:o.paid_at}} AND r.big_region = ("
    f"SELECT r2.big_region FROM trd_order o2 JOIN dim_shop s2 ON o2.shop_id = s2.id JOIN dim_region r2 "
    "ON s2.region_id = r2.id WHERE {r:o2.paid_at} GROUP BY r2.big_region ORDER BY SUM(o2.total_amount + o2.freight_amount) "
    "DESC LIMIT 1) GROUP BY s.name ORDER BY gmv DESC LIMIT {n}", P(DEV2, n=[2, 3, 2, 3]), ctx=["{M}各大区的GMV分别是多少？"])
fam("M02", "multi_turn", "top", "holdout", "商品", "这个类目里销售额最高的{n}个SKU是哪些？",
    f"SELECT k.sku_code, SUM(i.pay_share) AS sales FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id {CAT} "
    f"JOIN prd_sku k ON i.sku_id = k.id WHERE c1.name = '{{cat}}' AND {SALE} AND {{r:o.paid_at}} "
    "GROUP BY k.sku_code ORDER BY sales DESC LIMIT {n}", P(HO, cat=["数码家电", "服饰鞋包", "家居日用", "美妆个护"], n=[3, 3, 5, 5]),
    ctx=["{M}{cat}类目的销售额是多少？"])
fam("M03", "multi_turn", "top", "dev", "库存", "缺货数量最多的前{n}个SKU是哪些？",
    "SELECT k.sku_code, SUM(so.shortage_qty) AS shortage FROM wms_stockout_record so JOIN prd_sku k ON so.sku_id = k.id "
    "WHERE {r:so.detected_at} GROUP BY k.sku_code ORDER BY shortage DESC LIMIT {n}",
    P(DEV2, n=[3, 5, 3, 5]), ctx=["{M}有哪些仓库发生过缺货？"])
fam("M04", "multi_turn", "agg", "dev", "交易", "其中{reg}是多少？",
    f"SELECT SUM(o.pay_amount) AS pay_amount FROM trd_order o {REG} WHERE r.big_region = '{{reg}}' AND {{r:o.paid_at}}",
    P(DEV2, reg=REGS), ctx=["{M}的实付金额是多少？"])
fam("M05", "multi_turn", "agg", "holdout", "交易", "那{plat2}呢？",
    f"SELECT COUNT(DISTINCT o.id) AS order_cnt FROM trd_order o {PLAT} WHERE p.name = '{{plat2}}' AND {{r:o.paid_at}}",
    P(HO, plat=["天猫", "京东", "抖音", "自营商城"], plat2=["京东", "抖音", "自营商城", "天猫"]),
    ctx=["{M}{plat}平台的支付订单数是多少？"])
fam("M06", "multi_turn", "agg", "dev", "履约", "换成{wh2}呢？",
    "SELECT COUNT(*) AS outbound_cnt FROM wms_outbound ob JOIN wms_warehouse w ON ob.warehouse_id = w.id "
    "WHERE w.name = '{wh2}' AND {r:ob.outbound_at}",
    P(DEV2, wh=["华东中心仓", "华南中心仓", "华北区域仓", "华中区域仓"], wh2=["华南中心仓", "华东中心仓", "华中区域仓", "西南区域仓"]),
    ctx=["{M}{wh}有多少张出库单？"])
fam("M07", "multi_turn", "cmp", "dev", "交易", "和去年同期比呢？",
    f"SELECT DATE_FORMAT(o.paid_at, '%Y-%m') AS month, {GMV} AS gmv FROM trd_order o WHERE {{r:o.paid_at}} "
    "OR {r:o.paid_at@y} GROUP BY month", P(DEV4), ctx=["{M}的GMV是多少？"])
fam("M08", "multi_turn", "cmp", "holdout", "交易", "和{reg2}比呢？",
    f"SELECT r.big_region, SUM(o.pay_amount) / COUNT(DISTINCT o.id) AS atv FROM trd_order o {REG} "
    "WHERE r.big_region IN ('{reg}','{reg2}') AND {r:o.paid_at} GROUP BY r.big_region",
    P(HO, reg=["华东", "华北", "华南", "华中"], reg2=["华南", "西南", "东北", "西北"]), ctx=["{M}{reg}的客单价是多少？"])
fam("M09", "multi_turn", "cmp", "dev", "交易", "和安卓端对比一下",
    "SELECT device_type, COUNT(*) AS order_cnt FROM trd_order WHERE device_type IN ('IOS','ANDROID') AND {r:paid_at} "
    "GROUP BY device_type", P(DEV4), ctx=["{M}iOS端的支付订单数是多少？"])
fam("M10", "multi_turn", "cmp", "dev", "交易", "环比上个月变化了多少？",
    f"SELECT DATE_FORMAT(o.paid_at, '%Y-%m') AS month, {GMV} AS gmv FROM trd_order o {PLAT} WHERE p.name = '{{plat}}' "
    "AND o.paid_at >= '{mp}-01' AND o.paid_at < '{m1}-01' GROUP BY month",
    P(DEV2, plat=["自营商城", "天猫", "京东", "抖音"]), ctx=["{M}{plat}平台的GMV是多少？"])
fam("M11", "multi_turn", "trend", "holdout", "交易", "按天看趋势呢？",
    f"SELECT DATE(o.paid_at) AS d, {GMV} AS gmv FROM trd_order o {REG} WHERE r.big_region = '{{reg}}' AND "
    "{r:o.paid_at} GROUP BY d ORDER BY d", P(HO, reg=REGS), ctx=["{M}{reg}的GMV是多少？"], order=True)
fam("M12", "multi_turn", "trend", "dev", "商品", "按月拆开看看",
    f"SELECT DATE_FORMAT(o.paid_at, '%Y-%m') AS month, SUM(i.pay_share) AS sales FROM trd_order_item i "
    f"JOIN trd_order o ON i.order_id = o.id {CAT} WHERE c1.name = '{{cat}}' AND {SALE} "
    "AND o.paid_at >= '{y}-01-01' AND o.paid_at < '{y1}-01-01' GROUP BY month ORDER BY month",
    [{"m": "2026-03", "y": 2025, "cat": c} for c in ["服饰鞋包", "家居日用", "数码家电", "食品生鲜"]],
    ctx=["{y}年{cat}类目的销售额是多少？"], order=True)
fam("M13", "multi_turn", "trend", "dev", "履约", "每天的运单量走势呢？",
    "SELECT DATE(t.shipped_at) AS d, COUNT(*) AS cnt FROM tms_shipment t JOIN tms_carrier c ON t.carrier_id = c.id "
    "WHERE c.name = '{car}' AND {r:t.shipped_at} GROUP BY d ORDER BY d",
    P(DEV2, car=["顺丰", "京东物流", "中通", "圆通"]), ctx=["{M}{car}的运单量是多少？"], order=True)
fam("M14", "multi_turn", "drill", "dev", "交易", "按省份拆一下",
    f"SELECT r.province, {GMV} AS gmv FROM trd_order o {REG} WHERE r.big_region = '{{reg}}' AND {{r:o.paid_at}} "
    "GROUP BY r.province", P(DEV2, reg=["华南", "华中", "西南", "华东"]), ctx=["{M}{reg}的GMV是多少？"])
fam("M15", "multi_turn", "drill", "holdout", "交易", "按退款原因拆分呢？",
    "SELECT rf.reason_code, SUM(rf.refund_amount) AS refund_amount FROM trd_refund rf WHERE rf.refund_status = 'SUCCESS' "
    "AND {r:rf.refunded_at} GROUP BY rf.reason_code", P(["2026-04", "2025-04", "2025-12", "2025-11"]),
    ctx=["{M}的成功退款金额是多少？"])
fam("M16", "multi_turn", "drill", "holdout", "交易", "下钻到店铺看看",
    f"SELECT s.name, COUNT(DISTINCT o.id) AS order_cnt FROM trd_order o {PLAT} WHERE p.name = '{{plat}}' "
    "AND {r:o.paid_at} GROUP BY s.name", P(HO4, plat=["天猫", "京东", "抖音", "自营商城"]),
    ctx=["{M}{plat}平台的支付订单数是多少？"])

# ---------------------------------------------------------------- multi-step analysis (16): full plan, SQL + Python + report
# key_sql returns one number the report should state (None = completion only).
fam("P01", "multi_step", "cmp", "dev", "交易", "分析{M}GMV相比上个月的变化，并按大区拆解变化来源",
    None, P(DEV4), key_sql=f"SELECT ({GMV} filter_m) / ({GMV} filter_mp) - 1",
    key_desc="GMV环比增长率")
fam("P02", "multi_step", "trend", "dev", "交易", "根据截至{M}的近6个月支付订单数，预测下个月的订单数", None, P(DEV4))
fam("P03", "multi_step", "drill", "dev", "交易", "{M}{reg}的GMV和去年同期相比变化了多少？从一级类目和平台两个维度分析原因",
    None, P(DEV2, reg=REGS), key_sql="REGION_YOY", key_desc="大区GMV同比增长率")
fam("P04", "multi_step", "top", "holdout", "交易", "找出{M}退款率最高的{n}个一级类目，并分析主要退款原因", None,
    P(HO4, n=[2, 3, 3, 2]))
fam("P05", "multi_step", "agg", "dev", "交易", "统计{M}各大区的GMV、支付订单数和客单价，并计算各大区GMV占比", None, P(DEV4),
    key_sql="TOTAL_GMV", key_desc="全国GMV合计")
fam("P06", "multi_step", "trend", "holdout", "交易", "分析{A}到{B}每个月实付金额的趋势，计算每月环比增长率，指出增长最快的月份", None,
    [{"m": "2026-04", "a": a, "b": b} for a, b in [("2025-04", "2025-12"), ("2025-10", "2026-04"), ("2025-05", "2025-11"),
                                                    ("2025-11", "2026-04")]])
fam("P07", "multi_step", "cmp", "dev", "交易", "对比{M}各平台的客单价和退款率，并分析差异", None, P(DEV4))
fam("P08", "multi_step", "drill", "dev", "履约", "{M}因缺货导致的成功退款有多少笔？涉及哪些仓库和SKU？给出补货建议", None, P(DEV4),
    key_sql="STOCKOUT_REFUNDS", key_desc="缺货退款笔数")
fam("P09", "multi_step", "top", "dev", "库存", "{M}销售额前{n}的SKU，月末库存可售天数（DOS）分别是多少？", None,
    P(DEVINV, n=[5, 3, 5, 3]))
fam("P10", "multi_step", "agg", "holdout", "商品", "计算{M}的毛利和毛利率，并按一级类目拆分", None,
    P(["2026-04", "2025-04", "2025-12", "2025-11"]))
fam("P11", "multi_step", "trend", "dev", "交易", "分析{M}每天GMV的波动，识别异常的日期并解释可能原因", None, P(DEV4))
fam("P12", "multi_step", "cmp", "holdout", "会员", "对比{M}和去年同期的新客占比与复购率", None, P(["2026-04", "2025-12", "2025-11", "2025-10"]))
fam("P13", "multi_step", "drill", "dev", "履约", "{M}的准时送达率是多少？哪些快递公司和仓库拖了后腿？", None, P(DEV4),
    key_sql="ON_TIME", key_desc="整体准时送达率")
fam("P14", "multi_step", "agg", "dev", "会员", "基于截至{M}底的消费数据做RFM会员分层，统计各层人数", None, P(DEV4))
fam("P15", "multi_step", "top", "holdout", "营销", "{M}哪个投放渠道的点击成本最低？结合花费和点击给出预算分配建议", None,
    P(["2026-04", "2025-04", "2025-12", "2025-11"]))
fam("P16", "multi_step", "drill", "holdout", "库存", "{M}{wh}的盘点差异率是多少？差异主要来自哪些SKU？", None,
    P(HOINV, wh=["华东中心仓", "华南中心仓", "华北区域仓", "华中区域仓"]))


def key_sql(fid, p):
    m, mp = p["m"], prev(p["m"])
    if fid == "P01":
        return (f"SELECT SUM(CASE WHEN {rng('o.paid_at', m)} THEN o.total_amount + o.freight_amount END) / "
                f"SUM(CASE WHEN {rng('o.paid_at', mp)} THEN o.total_amount + o.freight_amount END) - 1 FROM trd_order o")
    if fid == "P03":
        my = prev(m, 12)
        return (f"SELECT SUM(CASE WHEN {rng('o.paid_at', m)} THEN o.total_amount + o.freight_amount END) / "
                f"SUM(CASE WHEN {rng('o.paid_at', my)} THEN o.total_amount + o.freight_amount END) - 1 "
                f"FROM trd_order o {REG} WHERE r.big_region = '{p['reg']}'")
    if fid == "P05":
        return f"SELECT {GMV} FROM trd_order o WHERE {rng('o.paid_at', m)}"
    if fid == "P08":
        return (f"SELECT COUNT(*) FROM trd_refund WHERE refund_status = 'SUCCESS' AND reason_code = 'STOCKOUT' "
                f"AND {rng('refunded_at', m)}")
    if fid == "P13":
        return ("SELECT SUM(t.delivered_at <= f.promised_at) / COUNT(*) FROM oms_fulfillment_order f JOIN tms_shipment t "
                f"ON t.fulfillment_id = f.id WHERE t.delivered_at IS NOT NULL AND {rng('t.delivered_at', m)}")
    return None


def render(tpl, p):
    m = p["m"]
    v = dict(p)
    v.update(M=cn(m), m1=nxt(m), mp=prev(m), Mp=cn(prev(m)), mk=prev(m, p.get("k", 1) - 1) if "k" in p else m)
    if "y" in p:
        v["y1"] = p["y"] + 1
    if "a" in p:
        v.update(A=cn(p["a"]), B=cn(p["b"]), b1=nxt(p["b"]))
    if "dev" in p:
        v["devname"] = DEVICE[p["dev"]]
    out = tpl
    # {r:col} -> month range on col ; {r:col@y} -> same month last year
    import re
    out = re.sub(r"\{r:([\w.]+)@y\}", lambda mo: rng(mo.group(1), prev(m, 12)), out)
    out = re.sub(r"\{r:([\w.]+)\}", lambda mo: rng(mo.group(1), m), out)
    return out.format(**v)


def mysql(sql):
    r = subprocess.run(["docker", "exec", "-i", "data-agent-mysql-datasource", "mysql", "--default-character-set=utf8mb4",
                        "-uroot", "-proot", "-B", "gbi_ecommerce", "-e", sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() + "\n" + sql)
    lines = [l for l in r.stdout.splitlines()]
    if not lines:
        return [], []
    cols = lines[0].split("\t")
    return cols, [l.split("\t") for l in lines[1:]]


def main():
    assert len(F) == 80, len(F)
    os.makedirs(os.path.join(OUT, "gold"), exist_ok=True)
    rows, problems = [], []
    for f in F:
        for i, p in enumerate(f["params"]):
            qid = f"{f['fid']}-{i + 1}"
            q = render(f["q"], p)
            ctx = [render(c, p) for c in f["ctx"]]
            rec = dict(id=qid, family=f["fid"], split=f["split"], task=f["task"], intent=f["intent"], domain=f["domain"],
                       month=p["m"], question=q, context=ctx, order_matters=f["order"])
            if f["sql"]:
                sql = render(f["sql"], p)
                cols, data = mysql(sql)
                rec.update(gold_sql=sql, gold_columns=cols, gold_rows=data)
                lim = re.search(r"ORDER BY .* LIMIT (\d+)$", sql)
                if lim:  # ties at the cut-off: any tied row is an acceptable answer
                    n = int(lim.group(1))
                    _, more = mysql(sql[:lim.start(1) - 6] + f"LIMIT {n + 200}")
                    tied = [x for x in more[n:] if data and x[-1] == data[-1][-1]]
                    if tied:
                        rec.update(top_n=n, gold_tie_rows=tied)
                if not data or all(v in ("NULL", "") for v in data[0]):
                    problems.append((qid, "empty gold", q))
            ks = key_sql(f["fid"], p)
            if ks:
                _, data = mysql(ks)
                rec.update(key_sql=ks, key_value=float(data[0][0]), key_desc=f["key_desc"])
            rows.append(rec)
    assert len({r["question"] + "|" + "/".join(r["context"]) for r in rows}) == len(rows), "duplicate questions"
    with open(os.path.join(OUT, "questions.jsonl"), "w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(len(rows), Counter(r["split"] for r in rows), Counter(r["task"] for r in rows))
    print("intent", Counter(r["intent"] for r in rows))
    for s in ("dev", "holdout"):
        sub = [r for r in rows if r["split"] == s]
        print(s, Counter(r["task"] for r in sub), Counter(r["intent"] for r in sub), Counter(r["month"] for r in sub))
    print("problems", problems)


if __name__ == "__main__":
    main()
