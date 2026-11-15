"""Build the 40 report tasks (eval/report/tasks.jsonl): 20 attribution / root-cause, 20 periodic or thematic reports.

Each task carries
  key facts     : SQL computed on MySQL; the report should state the number (1% tolerance, ratios may be in %)
  must_mention  : for the two root causes planted in the dataset (华东区域仓 stockouts 2026-10-12..18,
                  极速达 late into 西南 in 2026 Q3) the attribution must name the cause
  manual effort : n_sql / n_charts for the manual-baseline estimate (see README: 10 min understanding + 8 min per
                  query + 5 min per chart + 10 min writing, +10 min for an attribution conclusion)
  sample        : the 8 tasks run in the quota-limited sample
"""
import importlib.util
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("bq", os.path.join(ROOT, "eval", "nl2sql", "build_questions.py"))
bq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bq)
cn, rng, prev, mysql = bq.cn, bq.rng, bq.prev, bq.mysql
REG, PLAT, CAT, GMV, SALE = bq.REG, bq.PLAT, bq.CAT, bq.GMV, bq.SALE

T = []


def task(kind, q, keys=(), must=(), n_sql=3, n_charts=2, sample=False):
    T.append(dict(kind=kind, question=q, keys=list(keys), must_mention=list(must), n_sql=n_sql, n_charts=n_charts,
                  sample=sample))


def gmv_mom(m, where="1=1", join=""):
    return (f"SELECT SUM(CASE WHEN {rng('o.paid_at', m)} THEN o.total_amount + o.freight_amount END) / "
            f"SUM(CASE WHEN {rng('o.paid_at', prev(m))} THEN o.total_amount + o.freight_amount END) - 1 "
            f"FROM trd_order o {join} WHERE {where}")


# ---------------------------------------------------------------- attribution (20)
for i, (reg, m) in enumerate([("华东", "2026-02"), ("华北", "2026-03"), ("华南", "2026-04"), ("西南", "2026-03")]):
    task("attribution", f"{cn(m)}{reg}的GMV相比上个月变化的原因是什么？请从一级类目、平台和店铺维度拆解",
         [("大区GMV环比", gmv_mom(m, f"r.big_region = '{reg}'", REG))], n_sql=4, n_charts=2, sample=i == 0)
task("attribution", "2026年10月12日到18日这一周，华东地区数码家电的销量为什么比前一周下滑？",
     [("该周华东区域仓缺货记录数", "SELECT COUNT(*) FROM wms_stockout_record so JOIN wms_warehouse w ON so.warehouse_id = w.id "
                          "WHERE w.name = '华东区域仓' AND so.detected_at >= '2026-10-12' AND so.detected_at < '2026-10-19'")],
     must=["缺货", "华东区域仓"], n_sql=5, n_charts=2, sample=True)
task("attribution", "2026年第三季度发往西南地区的订单，准时送达率为什么偏低？",
     [("西南Q3准时送达率", "SELECT SUM(t.delivered_at <= f.promised_at) / COUNT(*) FROM oms_fulfillment_order f JOIN tms_shipment t "
                     "ON t.fulfillment_id = f.id JOIN trd_order_address a ON a.order_id = f.order_id JOIN dim_region r "
                     "ON a.region_id = r.id WHERE r.big_region = '西南' AND t.delivered_at >= '2026-07-01' "
                     "AND t.delivered_at < '2026-10-01'")],
     must=["极速达"], n_sql=4, n_charts=2, sample=True)
for i, m in enumerate(["2026-02", "2026-03", "2026-04"]):
    task("attribution", f"{cn(m)}的成功退款金额相比上个月有什么变化？按退款原因和一级类目分析原因",
         [("当月成功退款金额", f"SELECT SUM(refund_amount) FROM trd_refund WHERE refund_status = 'SUCCESS' AND {rng('refunded_at', m)}")],
         n_sql=4, n_charts=2, sample=i == 0)
for plat, m in [("天猫", "2026-03"), ("京东", "2026-04"), ("抖音", "2026-02")]:
    task("attribution", f"{cn(m)}{plat}平台的客单价相比上个月变化的原因是什么？",
         [("当月客单价", f"SELECT SUM(o.pay_amount) / COUNT(DISTINCT o.id) FROM trd_order o {PLAT} WHERE p.name = '{plat}' "
                    f"AND {rng('o.paid_at', m)}")], n_sql=3, n_charts=2)
for m in ["2026-10", "2026-03"]:
    task("attribution", f"{cn(m)}因缺货导致的退款为什么增多？涉及哪些仓库和SKU？",
         [("当月缺货退款笔数", f"SELECT COUNT(*) FROM trd_refund WHERE refund_status = 'SUCCESS' AND reason_code = 'STOCKOUT' "
                       f"AND {rng('refunded_at', m)}")], n_sql=4, n_charts=2)
for m in ["2026-03", "2026-04"]:
    task("attribution", f"{cn(m)}新客占比相比上个月变化的原因是什么？结合投放渠道分析", [], n_sql=4, n_charts=2)
for m in ["2026-02", "2026-04"]:
    task("attribution", f"{cn(m)}广告投放的点击成本为什么变化？按渠道拆解",
         [("当月广告花费", f"SELECT SUM(spend) FROM mkt_ad_spend WHERE stat_date >= '{m}-01' AND stat_date < '{bq.nxt(m)}-01'")],
         n_sql=3, n_charts=2)
for wh, m in [("华东中心仓", "2026-03"), ("华南中心仓", "2026-04")]:
    task("attribution", f"{cn(m)}{wh}的出库量相比上个月变化的原因是什么？",
         [("当月出库单数", f"SELECT COUNT(*) FROM wms_outbound ob JOIN wms_warehouse w ON ob.warehouse_id = w.id "
                     f"WHERE w.name = '{wh}' AND {rng('ob.outbound_at', m)}")], n_sql=3, n_charts=2)

# ---------------------------------------------------------------- periodic / thematic (20)
for i, m in enumerate(["2026-02", "2026-03", "2026-04", "2026-01"]):
    task("report", f"生成{cn(m)}经营月报：GMV、支付订单数、客单价、退款率，以及各大区表现",
         [("当月GMV", f"SELECT {GMV} FROM trd_order o WHERE {rng('o.paid_at', m)}")], n_sql=4, n_charts=2, sample=i == 0)
for i, m in enumerate(["2026-03", "2026-04", "2026-02"]):
    task("report", f"分析{cn(m)}各一级类目的销售额结构和毛利率",
         [("当月销售额（商品口径）", f"SELECT SUM(i.pay_share) FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id "
                          f"WHERE {SALE} AND {rng('o.paid_at', m)}")], n_sql=3, n_charts=2, sample=i == 0)
for m in ["2026-03", "2026-04"]:
    task("report", f"{cn(m)}各投放渠道的广告花费、点击量和点击成本分析报告",
         [("当月广告花费", f"SELECT SUM(spend) FROM mkt_ad_spend WHERE stat_date >= '{m}-01' AND stat_date < '{bq.nxt(m)}-01'")],
         n_sql=2, n_charts=2)
for m in ["2026-09", "2026-10"]:
    task("report", f"{cn(m)}末各仓库的库存健康度报告：可用库存、库存金额和库存可售天数（DOS）",
         [("月末可用库存合计", f"SELECT SUM(available_qty) FROM wms_inventory_snapshot WHERE snapshot_date = "
                       f"LAST_DAY('{m}-01')")], n_sql=3, n_charts=2)
for i, m in enumerate(["2026-03", "2026-04"]):
    task("report", f"{cn(m)}履约时效报告：整体准时送达率，以及各快递公司和仓库的表现",
         [("当月准时送达率", "SELECT SUM(t.delivered_at <= f.promised_at) / COUNT(*) FROM oms_fulfillment_order f JOIN tms_shipment t "
                      f"ON t.fulfillment_id = f.id WHERE t.delivered_at IS NOT NULL AND {rng('t.delivered_at', m)}")],
         n_sql=3, n_charts=2, sample=i == 0)
for m in ["2026-03", "2026-04"]:
    task("report", f"{cn(m)}会员复购分析：复购率和各等级会员的贡献",
         [("当月支付买家数", f"SELECT COUNT(DISTINCT member_id) FROM trd_order WHERE {rng('paid_at', m)}")], n_sql=3, n_charts=2)
for m in ["2026-03", "2026-04"]:
    task("report", f"{cn(m)}天猫、京东、抖音、自营商城四个平台的经营对比",
         [("当月GMV", f"SELECT {GMV} FROM trd_order o WHERE {rng('o.paid_at', m)}")], n_sql=2, n_charts=2)
task("report", "2025年双11大促（2025年10月31日至11月11日）复盘报告",
     [("大促期GMV", f"SELECT {GMV} FROM trd_order o WHERE o.paid_at >= '2025-10-31' AND o.paid_at < '2025-11-12'")],
     n_sql=4, n_charts=3)
task("report", "2026年618大促（2026年6月1日至6月18日）复盘报告",
     [("大促期GMV", f"SELECT {GMV} FROM trd_order o WHERE o.paid_at >= '2026-06-01' AND o.paid_at < '2026-06-19'")],
     n_sql=4, n_charts=3)
task("report", "2026年第一季度经营报告",
     [("Q1 GMV", f"SELECT {GMV} FROM trd_order o WHERE o.paid_at >= '2026-01-01' AND o.paid_at < '2026-04-01'")],
     n_sql=4, n_charts=3, sample=True)


def manual_minutes(t):
    return 10 + 8 * t["n_sql"] + 5 * t["n_charts"] + 10 + (10 if t["kind"] == "attribution" else 0)


def main():
    assert len(T) == 40 and sum(t["kind"] == "attribution" for t in T) == 20, (len(T), sum(t["kind"] == "attribution" for t in T))
    out = []
    for i, t in enumerate(T, 1):
        keys = []
        for desc, sql in t["keys"]:
            _, data = mysql(sql)
            keys.append(dict(desc=desc, sql=sql, value=float(data[0][0])))
        out.append(dict(id=f"R{i:02d}", kind=t["kind"], question=t["question"], keys=keys, must_mention=t["must_mention"],
                        n_sql=t["n_sql"], n_charts=t["n_charts"], manual_minutes_est=manual_minutes(t), sample=t["sample"]))
    with open(os.path.join(ROOT, "eval", "report", "tasks.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(len(out), "tasks;", sum(r["sample"] for r in out), "in sample; manual est avg",
          round(sum(r["manual_minutes_est"] for r in out) / len(out), 1), "min")
    for r in out:
        if r["sample"]:
            print(r["id"], r["question"], [round(k["value"], 4) for k in r["keys"]], r["manual_minutes_est"])


if __name__ == "__main__":
    main()
