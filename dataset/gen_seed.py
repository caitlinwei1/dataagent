"""Deterministic seed-data generator for gbi_ecommerce (86 tables).

Usage: python3 dataset/gen_seed.py [mysql|pg] > /tmp/seed.sql
       docker exec -i <mysql> mysql -uroot -proot gbi_ecommerce < /tmp/seed.sql

Data window: 2024-11-01 .. 2026-10-31 (24 months). Same random seed -> same data.
Planted patterns for 归因/根因 questions are listed in dataset/README.md (PLANTED).
"""
import os
import random
import sys
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "schema"))
from extra_columns import EXTRA, audit_for  # noqa: E402

DIALECT = sys.argv[1] if len(sys.argv) > 1 else "mysql"   # mysql | pg
QUOTE = "`" if DIALECT == "mysql" else '"'
R = random.Random(20261113)
RX = random.Random(7)  # separate stream for filler columns so core data does not shift
START, END = date(2024, 11, 1), date(2026, 10, 31)
DAYS = (END - START).days + 1
OUT = sys.stdout
BATCH = 1000

# PLANTED 1: 华东区域仓 (warehouse 3) runs out of 数码家电 SKUs 2026-10-12..2026-10-18
STOCKOUT_WEEK = (date(2026, 10, 12), date(2026, 10, 18))
# PLANTED 2: carrier 5 (极速达) is slow into 西南 in 2026 Q3
SLOW_CARRIER, SLOW_REGION_BIG, SLOW_Q = 5, "西南", (date(2026, 7, 1), date(2026, 9, 30))


def money(x):
    return Decimal(x).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def lit(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (int, Decimal)):
        return str(v)
    if isinstance(v, float):
        return repr(round(v, 6))
    if isinstance(v, datetime):
        return "'" + v.strftime("%Y-%m-%d %H:%M:%S") + "'"
    if isinstance(v, date):
        return "'" + v.isoformat() + "'"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


ID_RANGES = {"shop_id": 12, "warehouse_id": 8, "alternative_warehouse_id": 8, "carrier_id": 5, "supplier_id": 25,
             "platform_id": 4, "member_id": 8000, "brand_id": 30}
TEXT_POOL = ["系统", "张伟", "王芳", "李娜", "刘洋", "陈静", "运营一组", "运营二组", None]


def filler(name, typ, enum, row_time):
    if enum:
        return RX.choice(enum)
    if typ == "b":
        n = ID_RANGES.get(name)
        return RX.randint(1, n) if n else None
    if typ == "t":
        return RX.choices([0, 1], weights=[85, 15])[0] if name.startswith(("is_", "has_")) or name.endswith(("_flag", "bound")) else RX.randint(1, 5)
    if typ == "i":
        return RX.randint(0, 500)
    if typ == "d":
        return money(RX.uniform(0, 200))
    if typ == "r":
        return round(RX.uniform(0, 1), 4)
    if typ == "dt":
        return START + timedelta(days=RX.randrange(DAYS))
    if typ == "ts":
        return (row_time or datetime.combine(START, datetime.min.time())) + timedelta(hours=RX.randint(0, 72))
    if name.endswith(("_no", "_code")):
        return name.split("_")[0].upper()[:3] + str(RX.randrange(10**7, 10**8))
    return RX.choice(TEXT_POOL)


def parse_spec(spec):
    parts = spec.split(" ")
    rest = parts[2:]
    enum = rest[-1].strip("{}").split("|") if rest and rest[-1].startswith("{") else None
    return parts[0], parts[1], enum


class Table:
    def __init__(self, name, cols):
        self.name, self.core, self.rows = name, cols, []
        self.extra = [parse_spec(x) for x in EXTRA[name]]
        self.audit = [parse_spec(x)[0] for x in audit_for(name)]
        self.cols = cols + [e[0] for e in self.extra] + self.audit

    def add(self, *vals, x=None):
        """x: overrides for extra columns that must stay consistent with core data."""
        assert len(vals) == len(self.core), (self.name, vals)
        row_time = next((v for v in vals if isinstance(v, datetime)), None)
        x = x or {}
        assert set(x) <= {n for n, _, _ in self.extra}, (self.name, set(x))
        ext = [x[n] if n in x else filler(n, t, e, row_time) for n, t, e in self.extra]
        ct = row_time or (next((v for v in vals if isinstance(v, date)), None))
        ct = datetime.combine(ct, datetime.min.time()) if isinstance(ct, date) and not isinstance(ct, datetime) else ct
        ct = ct or datetime(2024, 10, 1)
        aud = {"created_by": "system", "created_time": ct, "updated_time": ct, "is_deleted": 0}
        self.rows.append(tuple(vals) + tuple(ext) + tuple(aud[a] for a in self.audit))
        if len(self.rows) >= BATCH:
            self.flush()
        return vals

    def flush(self):
        if not self.rows:
            return
        Q = QUOTE
        OUT.write(f"INSERT INTO {Q}{self.name}{Q} ({Q}" + f"{Q},{Q}".join(self.cols) + f"{Q}) VALUES\n")
        OUT.write(",\n".join("(" + ",".join(lit(v) for v in r) + ")" for r in self.rows) + ";\n")
        self.rows = []


TABLES = {}


def tbl(name, cols):
    t = Table(name, cols.split())
    TABLES[name] = t
    return t


def rand_dt(d):
    return datetime(d.year, d.month, d.day, R.choices(range(24), weights=[1, 1, 1, 1, 1, 1, 2, 3, 4, 5, 6, 6, 6, 5, 5, 5, 5, 5, 6, 7, 9, 10, 9, 5])[0], R.randrange(60), R.randrange(60))


def daterange(a, b):
    d = a
    while d <= b:
        yield d
        d += timedelta(days=1)


OUT.write("SET FOREIGN_KEY_CHECKS=0; SET UNIQUE_CHECKS=0; SET autocommit=0;\n" if DIALECT == "mysql"
          else "SET search_path TO gbi_ecommerce; BEGIN;\n")

# ------------------------------------------------------------------ dims
platforms = ["天猫", "京东", "抖音", "自营商城"]
t = tbl("dim_platform", "id name")
for i, n in enumerate(platforms, 1):
    t.add(i, n)

REGIONS = {
    "华东": ["上海", "江苏", "浙江", "安徽", "福建", "江西", "山东"],
    "华北": ["北京", "天津", "河北", "山西", "内蒙古"],
    "华南": ["广东", "广西", "海南"],
    "华中": ["河南", "湖北", "湖南"],
    "西南": ["重庆", "四川", "贵州", "云南", "西藏"],
    "西北": ["陕西", "甘肃", "青海", "宁夏", "新疆"],
    "东北": ["辽宁", "吉林", "黑龙江"],
}
REGION_WEIGHT = {"华东": 32, "华北": 16, "华南": 18, "华中": 12, "西南": 10, "西北": 5, "东北": 7}
CITY = {"上海": "上海", "江苏": "南京", "浙江": "杭州", "安徽": "合肥", "福建": "福州", "江西": "南昌", "山东": "济南",
        "北京": "北京", "天津": "天津", "河北": "石家庄", "山西": "太原", "内蒙古": "呼和浩特", "广东": "广州",
        "广西": "南宁", "海南": "海口", "河南": "郑州", "湖北": "武汉", "湖南": "长沙", "重庆": "重庆", "四川": "成都",
        "贵州": "贵阳", "云南": "昆明", "西藏": "拉萨", "陕西": "西安", "甘肃": "兰州", "青海": "西宁", "宁夏": "银川",
        "新疆": "乌鲁木齐", "辽宁": "沈阳", "吉林": "长春", "黑龙江": "哈尔滨"}
t = tbl("dim_region", "id big_region province")
region_rows, rid, prov_region = [], 0, {}
for big, provs in REGIONS.items():
    for p in provs:
        rid += 1
        t.add(rid, big, p)
        region_rows.append((rid, big, p))
        prov_region[p] = (rid, big)
big_of = {r[0]: r[1] for r in region_rows}


def pick_province():
    big = R.choices(list(REGION_WEIGHT), weights=list(REGION_WEIGHT.values()))[0]
    p = R.choice(REGIONS[big])
    return p, prov_region[p][0], big


def region_id_of(prov):
    return prov_region[prov][0]


SHOPS = [  # name, platform, province
    ("天猫旗舰店", 1, "浙江"), ("天猫数码专营店", 1, "上海"), ("天猫家居店", 1, "江苏"),
    ("京东自营旗舰店", 2, "北京"), ("京东华南店", 2, "广东"), ("京东西南店", 2, "四川"),
    ("抖音官方直播间", 3, "浙江"), ("抖音华中店", 3, "湖北"), ("抖音东北店", 3, "辽宁"),
    ("自营商城总店", 4, "上海"), ("自营商城华北店", 4, "天津"), ("自营商城西北店", 4, "陕西"),
]
t = tbl("dim_shop", "id name platform_id region_id opened_at")
shop_big = {}
for i, (n, p, prov) in enumerate(SHOPS, 1):
    t.add(i, n, p, region_id_of(prov), date(2021, 1, 1) + timedelta(days=R.randrange(900)))
    shop_big[i] = prov_region[prov][1]
t = tbl("dim_store", "id name shop_id region_id")
for i in range(1, 21):
    p, rid_, _ = pick_province()
    t.add(i, f"{CITY[p]}体验店{i}", R.choice([1, 4, 10]), rid_)
t = tbl("dim_currency", "code name")
for c, n in (("CNY", "人民币"), ("USD", "美元"), ("HKD", "港币")):
    t.add(c, n)

PROMO_DAYS = {}
for y in (2024, 2025, 2026):
    for d in daterange(date(y, 6, 1), date(y, 6, 18)):
        PROMO_DAYS[d] = "618"
    for d in daterange(date(y, 10, 31), date(y, 11, 11)):
        PROMO_DAYS[d] = "双11"
    for d in daterange(date(y, 1, 10), date(y, 1, 25)):
        PROMO_DAYS[d] = "年货节"
HOLIDAYS = {(1, 1), (5, 1), (5, 2), (5, 3), (10, 1), (10, 2), (10, 3), (10, 4), (10, 5), (10, 6), (10, 7)}
t = tbl("dim_date", "date_key year quarter month week_of_year is_weekend is_holiday promo_name")
for d in daterange(date(2024, 1, 1), date(2026, 12, 31)):
    t.add(d, d.year, (d.month - 1) // 3 + 1, d.month, d.isocalendar()[1], d.weekday() >= 5, (d.month, d.day) in HOLIDAYS, PROMO_DAYS.get(d))
t = tbl("fin_exchange_rate", "id rate_date currency_code rate_to_cny")
k = 0
for d in daterange(START, END):
    for c, base in (("CNY", 1.0), ("USD", 7.15), ("HKD", 0.915)):
        k += 1
        t.add(k, d, c, 1.0 if c == "CNY" else round(base * (1 + R.uniform(-0.01, 0.01)), 6))

# ------------------------------------------------------------------ product
L1 = {"数码家电": ["手机通讯", "电脑办公", "智能家电"], "服饰鞋包": ["女装", "男装", "箱包"], "美妆个护": ["护肤", "彩妆", "个人护理"],
      "食品生鲜": ["休闲零食", "粮油调味", "生鲜水果"], "家居日用": ["家纺", "厨具", "收纳清洁"], "母婴玩具": ["奶粉辅食", "童装", "玩具"]}
PRICE = {"数码家电": (300, 6000), "服饰鞋包": (60, 800), "美妆个护": (40, 600), "食品生鲜": (10, 200), "家居日用": (20, 500), "母婴玩具": (30, 900)}
t = tbl("prd_category", "id parent_id level name")
cid, leaf = 0, []  # (id, l1name, l1id)
for n1, l2s in L1.items():
    cid += 1
    c1 = cid
    t.add(c1, 0, 1, n1)
    for n2 in l2s:
        cid += 1
        c2 = cid
        t.add(c2, c1, 2, n2)
        for suffix in ("精选", "基础款"):
            cid += 1
            t.add(cid, c2, 3, n2 + suffix)
            leaf.append((cid, n1, c1))
l1_ids = {n: i for i, n in [(r[2], r[1]) for r in leaf]}
t = tbl("prd_brand", "id name is_own country")
brands = []
for i in range(1, 31):
    own = i <= 6
    t.add(i, ("自有品牌" if own else "品牌") + chr(64 + (i % 26 or 26)) + str(i), own, "中国" if own or i % 3 else R.choice(["日本", "美国", "法国", "韩国"]))
    brands.append(i)
t = tbl("prd_series", "id brand_id name")
for i in range(1, 51):
    t.add(i, R.choice(brands), f"系列{i}")
spu_t = tbl("prd_spu", "id spu_name category_id brand_id series_id launch_date")
sku_t = tbl("prd_sku", "id spu_id sku_code spec list_price status")
SKUS = []  # dict
SPU_NAME, SPU_CAT, SPU_BRAND = {}, {}, {}
skuid = 0
for spu in range(1, 401):
    c, l1n, l1id = R.choice(leaf)
    lo, hi = PRICE[l1n]
    base = R.uniform(lo, hi) ** 1 if R.random() < 0.3 else R.uniform(lo, (lo + hi) / 2.5)
    launch = date(2022, 1, 1) + timedelta(days=R.randrange(1500))
    b_ = R.choice(brands)
    spu_t.add(spu, f"{l1n[:2]}商品{spu}", c, b_, R.randrange(1, 51), launch, x=dict(spu_status="ON_SALE"))
    SPU_NAME[spu], SPU_CAT[spu], SPU_BRAND[spu] = f"{l1n[:2]}商品{spu}", c, b_
    for v in range(R.randint(1, 5)):
        skuid += 1
        price = money(base * R.uniform(0.9, 1.2))
        status = "ON_SALE" if R.random() < 0.88 else "OFF_SALE"
        sku_t.add(skuid, spu, f"SKU{skuid:05d}", R.choice(["黑色", "白色", "蓝色", "红色", "标准装", "大号", "小号"]) + f"/{v + 1}", price, status)
        SKUS.append(dict(id=skuid, spu=spu, price=price, l1=l1n, l1id=l1id, status=status, pop=R.paretovariate(1.3), launch=launch))
ON_SALE = [s for s in SKUS if s["status"] == "ON_SALE"]
SKU_W = [s["pop"] for s in ON_SALE]
t = tbl("prd_price_history", "id sku_id old_price new_price changed_at")
for i in range(1, 2501):
    s = R.choice(SKUS)
    t.add(i, s["id"], s["price"], money(float(s["price"]) * R.uniform(0.85, 1.1)), rand_dt(START + timedelta(days=R.randrange(DAYS))))
t = tbl("prd_attribute", "id name category_id")
for i, (n, c) in enumerate([(a, c[0]) for c in leaf[:12] for a in ("颜色", "材质")], 1):
    t.add(i, n, c)
t = tbl("prd_sku_attribute", "id sku_id attribute_id value")
for i, s in enumerate(SKUS[:3000], 1):
    t.add(i, s["id"], R.randrange(1, 25), R.choice(["棉", "涤纶", "金属", "塑料", "黑", "白"]))
t = tbl("prd_label", "id name")
for i, n in enumerate(["新品", "爆款", "清仓", "直播专享", "会员专享"], 1):
    t.add(i, n)
t = tbl("prd_sku_label", "id sku_id label_id")
for i in range(1, 1501):
    t.add(i, R.choice(SKUS)["id"], R.randrange(1, 6))
t = tbl("prd_bundle", "id name bundle_price")
t2 = tbl("prd_bundle_item", "id bundle_id sku_id quantity")
k = 0
for b in range(1, 41):
    items = R.sample(SKUS, R.randint(2, 4))
    t.add(b, f"组合套装{b}", money(sum(float(s["price"]) for s in items) * 0.85))
    for s in items:
        k += 1
        t2.add(k, b, s["id"], 1)
t = tbl("prd_listing_log", "id sku_id action acted_at")
k = 0
for s in SKUS:
    k += 1
    t.add(k, s["id"], "ON", datetime.combine(s["launch"], datetime.min.time()) + timedelta(hours=10))
    if s["status"] == "OFF_SALE":
        k += 1
        t.add(k, s["id"], "OFF", rand_dt(START + timedelta(days=R.randrange(DAYS))))
MONTHS = []
d = START
while d <= END:
    MONTHS.append(d.strftime("%Y-%m"))
    d = (d.replace(day=28) + timedelta(days=4)).replace(day=1)
COST = {}
t = tbl("prd_sku_cost", "id sku_id month_key unit_cost")
k = 0
for s in SKUS:
    ratio = R.uniform(0.45, 0.75)
    for m in MONTHS:
        k += 1
        c = money(float(s["price"]) * ratio * R.uniform(0.97, 1.03))
        COST[(s["id"], m)] = c
        t.add(k, s["id"], m, c)
t = tbl("prd_sku_barcode", "id sku_id barcode")
for s in SKUS:
    t.add(s["id"], s["id"], f"69{R.randrange(10**10, 10**11)}")

# ------------------------------------------------------------------ users & marketing
t = tbl("usr_member_level", "id name min_points discount_rate")
for i, (n, p, r) in enumerate([("普通", 0, 1.0), ("银卡", 2000, 0.98), ("金卡", 8000, 0.95), ("黑金", 30000, 0.92)], 1):
    t.add(i, n, p, r)
CHANNELS = [("DOUYIN", "抖音", 1), ("XHS", "小红书", 1), ("BAIDU", "百度", 1), ("WECHAT", "微信", 1), ("ORGANIC", "自然流量", 0)]
t = tbl("mkt_channel", "id code name is_paid")
for i, c in enumerate(CHANNELS, 1):
    t.add(i, *c)
MEMBERS = []
t = tbl("usr_member", "id register_at gender birth_year level_id register_channel_id province region_id")
for m in range(1, 8001):
    p, rid_, big = pick_province()
    reg = rand_dt(date(2022, 1, 1) + timedelta(days=R.randrange((END - date(2022, 1, 1)).days - 30)))
    lvl = R.choices([1, 2, 3, 4], weights=[60, 25, 12, 3])[0]
    ch = R.choices(range(1, 6), weights=[30, 15, 10, 15, 30])[0]
    t.add(m, reg, R.choice("MFFU"), R.randint(1965, 2006), lvl, ch, p, rid_,
          x=dict(city=CITY[p], status="ACTIVE", total_paid_amount=None, total_order_cnt=None, last_order_at=None))
    MEMBERS.append(dict(id=m, reg=reg, prov=p, rid=rid_, big=big, lvl=lvl, ch=ch, act=R.paretovariate(1.5)))
MEM_W = [m["act"] for m in MEMBERS]
t = tbl("usr_member_level_log", "id member_id from_level_id to_level_id changed_at")
k = 0
for m in MEMBERS:
    if m["lvl"] > 1:
        k += 1
        t.add(k, m["id"], m["lvl"] - 1, m["lvl"], m["reg"] + timedelta(days=R.randint(30, 600)))
t = tbl("usr_address", "id member_id province city is_default")
for m in MEMBERS:
    t.add(m["id"], m["id"], m["prov"], CITY[m["prov"]], 1)
t = tbl("usr_member_tag", "id member_id tag")
k = 0
for m in MEMBERS:
    if m["act"] > 3:
        k += 1
        t.add(k, m["id"], "HIGH_VALUE")
    elif R.random() < 0.15:
        k += 1
        t.add(k, m["id"], "CHURN_RISK")

ACTS = []
t = tbl("mkt_activity", "id name activity_type start_at end_at budget")
aid = 0
for y in (2024, 2025, 2026):
    for name, a, b in (("年货节", (1, 10), (1, 25)), ("618", (6, 1), (6, 18)), ("双11", (10, 31), (11, 11))):
        s, e = date(y, *a), date(y, *b)
        if e < START or s > END:
            continue
        aid += 1
        t.add(aid, f"{y}{name}", "BIG_PROMO", datetime.combine(s, datetime.min.time()), datetime.combine(e, datetime.max.time()).replace(microsecond=0), money(R.uniform(2e5, 8e5)))
        ACTS.append((aid, s, e))
for i in range(12):
    aid += 1
    s = START + timedelta(days=R.randrange(DAYS - 3))
    t.add(aid, f"限时秒杀{i + 1}", "FLASH_SALE", datetime.combine(s, datetime.min.time()), datetime.combine(s + timedelta(days=2), datetime.min.time()), money(R.uniform(2e4, 6e4)))
    ACTS.append((aid, s, s + timedelta(days=2)))
t = tbl("mkt_activity_sku", "id activity_id sku_id activity_price")
k = 0
for a, _, _ in ACTS:
    for s in R.sample(ON_SALE, 25):
        k += 1
        t.add(k, a, s["id"], money(float(s["price"]) * R.uniform(0.7, 0.9)))
TEMPLATES = []
t = tbl("mkt_coupon_template", "id name coupon_type threshold_amount face_value activity_id")
for i in range(1, 31):
    th = R.choice([0, 99, 199, 299, 499])
    fv = R.choice([5, 10, 20, 30, 50]) if th else R.choice([3, 5, 10])
    a = R.choice(ACTS)[0] if R.random() < 0.6 else None
    t.add(i, f"满{th}减{fv}" if th else f"无门槛{fv}元", "FULL_REDUCTION" if th else "NO_THRESHOLD", th, fv, a)
    TEMPLATES.append((i, th, fv))
coupon_t = tbl("mkt_coupon", "id template_id member_id status received_at used_at")
coupon_id = 0

t = tbl("mkt_ad_spend", "id stat_date channel_id spend impressions clicks")
k = 0
for d in daterange(START, END):
    boost = 2.5 if d in PROMO_DAYS else 1.0
    for ch in range(1, 5):
        k += 1
        sp = money(R.uniform(3000, 9000) * boost)
        imp = int(float(sp) * R.uniform(80, 140))
        t.add(k, d, ch, sp, imp, int(imp * R.uniform(0.01, 0.03)))
t = tbl("mkt_live_session", "id shop_id anchor start_at duration_min viewers")
for i in range(1, 301):
    t.add(i, R.choice([7, 8, 9]), R.choice(["主播小A", "主播小B", "主播小C", "主播小D"]), rand_dt(START + timedelta(days=R.randrange(DAYS))), R.randint(60, 300), R.randint(2000, 80000))

# ------------------------------------------------------------------ supply chain
t = tbl("scm_supplier", "id name province lead_days")
for i in range(1, 26):
    t.add(i, f"供应商{i}号", R.choice(list(CITY)), R.randint(3, 21))
WAREHOUSES = [  # id, name, type, province
    (1, "华东中心仓", "CENTRAL", "江苏"), (2, "华南中心仓", "CENTRAL", "广东"),
    (3, "华东区域仓", "REGIONAL", "浙江"), (4, "华北区域仓", "REGIONAL", "天津"),
    (5, "华中区域仓", "REGIONAL", "湖北"), (6, "西南区域仓", "REGIONAL", "四川"),
    (7, "上海前置仓", "FRONT", "上海"), (8, "北京前置仓", "FRONT", "北京"),
]
t = tbl("wms_warehouse", "id name warehouse_type region_id")
for w in WAREHOUSES:
    t.add(w[0], w[1], w[2], region_id_of(w[3]), x=dict(province=w[3], city=CITY[w[3]], status="ACTIVE", is_bonded=0))
WH_FOR_BIG = {"华东": [3, 1, 7], "华北": [4, 8], "华南": [2], "华中": [5], "西南": [6], "西北": [6, 4], "东北": [4]}
CARRIERS = [(1, "顺丰", "EXPRESS"), (2, "京东物流", "EXPRESS"), (3, "中通", "STANDARD"), (4, "圆通", "STANDARD"), (5, "极速达", "STANDARD")]
t = tbl("tms_carrier", "id name service_level")
for c in CARRIERS:
    t.add(*c)
t = tbl("tms_delivery_sla", "id warehouse_id region_id promised_hours")
SLA, k = {}, 0
for w in WAREHOUSES:
    wbig = prov_region[w[3]][1]
    for big, provs in REGIONS.items():
        h = 24 if big == wbig and w[2] == "FRONT" else 48 if big == wbig else 72 if big in ("华东", "华北", "华南", "华中") else 96
        rid_ = region_id_of(provs[0])
        k += 1
        t.add(k, w[0], rid_, h)
        SLA[(w[0], big)] = h

# ------------------------------------------------------------------ orders
def day_weight(d):
    w = 1.0 + 0.35 * (d - START).days / DAYS  # growth
    if d in PROMO_DAYS:
        w *= 3.2 if PROMO_DAYS[d] == "双11" else 2.4
    if d.weekday() >= 5:
        w *= 1.15
    if d.month == 2:
        w *= 0.8
    return w


ALL_DAYS = list(daterange(START, END))
DW = [day_weight(d) for d in ALL_DAYS]
N_ORDERS = 42000
order_days = sorted(R.choices(ALL_DAYS, weights=DW, k=N_ORDERS))

reasons = [("STOCKOUT", "缺货", "MERCHANT"), ("QUALITY", "质量问题", "MERCHANT"), ("LATE_DELIVERY", "物流超时", "LOGISTICS"),
           ("NO_REASON", "七天无理由", "CUSTOMER"), ("WRONG_ITEM", "发错货", "MERCHANT"), ("DAMAGED", "运输破损", "LOGISTICS")]
t = tbl("trd_aftersale_reason", "id code name responsibility")
for i, r in enumerate(reasons, 1):
    t.add(i, *r)
SOURCES = [("APP", "手机APP"), ("MINI_PROGRAM", "小程序"), ("WEB", "网页"), ("LIVE", "直播间"), ("OFFLINE", "线下门店")]
t = tbl("trd_order_source", "id code name")
for i, s in enumerate(SOURCES, 1):
    t.add(i, *s)

O = tbl("trd_order", "id order_no member_id shop_id source_id order_status total_amount discount_amount freight_amount pay_amount currency_code created_at paid_at completed_at is_presale")
OI = tbl("trd_order_item", "id order_id sku_id quantity unit_price item_amount discount_share pay_share")
OSL = tbl("trd_order_status_log", "id order_id from_status to_status changed_at operator")
OA = tbl("trd_order_address", "id order_id province city region_id receiver_name")
OP = tbl("trd_order_promotion", "id order_id promo_type coupon_id activity_id promo_amount")
PAY = tbl("trd_payment", "id order_id pay_channel pay_status amount paid_at")
FEE = tbl("trd_payment_channel_fee", "id payment_id fee_rate fee_amount")
RF = tbl("trd_refund", "id order_id aftersale_id refund_type reason_code refund_status refund_amount applied_at refunded_at")
RFI = tbl("trd_refund_item", "id refund_id order_item_id quantity amount")
AS = tbl("trd_aftersale", "id order_id aftersale_type reason_code status created_at closed_at")
INV = tbl("trd_order_invoice", "id order_id invoice_type invoice_amount issued_at")
REV = tbl("trd_order_review", "id order_item_id member_id score has_image created_at")
SPL = tbl("trd_order_split", "id parent_order_id child_order_id split_reason")
PRE = tbl("trd_presale_order", "id order_id deposit_amount balance_amount balance_paid_at")
CAN = tbl("trd_order_cancel", "id order_id cancel_reason cancel_by cancelled_at")
CMP = tbl("trd_complaint", "id order_id complaint_type severity created_at resolved_at")
RL = tbl("trd_return_logistics", "id refund_id carrier_id tracking_no shipped_at received_at")
COMP = tbl("trd_compensation", "id order_id comp_type amount reason_code created_at")
TAG = tbl("trd_order_tag", "id order_id tag")
GC = tbl("trd_gift_card_usage", "id order_id card_no used_amount used_at")
FO = tbl("oms_fulfillment_order", "id order_id warehouse_id status allocated_at promised_at")
OB = tbl("wms_outbound", "id fulfillment_id warehouse_id picked_at outbound_at")
OBI = tbl("wms_outbound_item", "id outbound_id sku_id quantity")
SH = tbl("tms_shipment", "id fulfillment_id carrier_id tracking_no shipped_at delivered_at")
TR = tbl("tms_shipment_track", "id shipment_id node city event_at")
SO = tbl("wms_stockout_record", "id order_id sku_id warehouse_id shortage_qty detected_at")
PTS = tbl("usr_points_log", "id member_id change_points biz_type created_at")
ATTR = tbl("mkt_member_attribution", "id member_id first_order_id channel_id")
INB = tbl("wms_inbound", "id warehouse_id inbound_type ref_id received_at")
INBI = tbl("wms_inbound_item", "id inbound_id sku_id quantity")
SETI = tbl("fin_settlement_item", "id settlement_id order_id amount")

ids = {k: 0 for k in "oi osl oa op pay fee rf rfi as inv rev spl pre can cmp rl comp tag gc fo ob obi sh tr so pts attr inb inbi seti".split()}


def nid(k):
    ids[k] += 1
    return ids[k]


member_first = {}
shop_daily = {}   # (date, shop) -> [gmv, paid, refund, orders, set(buyers)]
sku_daily = {}    # (date, sku) -> [qty, amount, refund_qty]
sku_sales_by_wh = {}  # (wh, sku) -> qty sold (for inventory)
settle = {}       # (shop, month) -> [gross, [(order, amount)]]
refund_by_day = {}
from collections import Counter, defaultdict  # noqa: E402
SD_X = defaultdict(Counter)    # (date, shop) -> discount/freight/items/new/refund_orders/cancel
SKU_X = defaultdict(lambda: [0, set(), Decimal(0), Decimal(0)])  # (date, sku) -> orders, buyers, gmv, refund_amt


def sd(d, shop):
    return shop_daily.setdefault((d, shop), [Decimal(0), Decimal(0), Decimal(0), 0, set()])


ORDER_META = {}


def order_extra(row):
    oid = row[0]
    meta = ORDER_META.get(oid, {})
    status = row[5]
    return dict(points_used=0, points_amount=Decimal("0.00"), tax_amount=Decimal("0.00"),
                order_type="PRESALE" if row[14] else "NORMAL",
                is_first_order=int(meta.get("first", False)), invoice_flag=int(meta.get("invoice", False)),
                risk_level="HIGH" if meta.get("risk") else "LOW",
                close_reason="STOCKOUT" if status == "CLOSED" else ("TIMEOUT" if status == "CANCELLED" else None),
                expected_delivery_date=meta.get("promised"), delivery_type="EXPRESS")


oid_base = 0
for d in order_days:
    oid = nid("oi") if False else None
    oid_base += 1
    oid = oid_base
    m = R.choices(MEMBERS, weights=MEM_W)[0]
    if m["reg"].date() > d:
        m = R.choice(MEMBERS[:3000])
    big = m["big"]
    cand_shops = [s for s in range(1, 13) if shop_big[s] == big] or list(range(1, 13))
    shop = R.choice(cand_shops) if R.random() < 0.55 else R.randrange(1, 13)
    platform = SHOPS[shop - 1][1]
    source = 4 if platform == 3 and R.random() < 0.7 else R.choices([1, 2, 3, 5], weights=[50, 30, 15, 5])[0]
    created = rand_dt(d)
    n_items = R.choices([1, 2, 3, 4], weights=[55, 28, 12, 5])[0]
    skus = []
    for _ in range(n_items):
        s = R.choices(ON_SALE, weights=SKU_W)[0]
        if s not in skus:
            skus.append(s)
    lines, total = [], Decimal(0)
    for s in skus:
        q = R.choices([1, 2, 3], weights=[80, 15, 5])[0]
        amt = s["price"] * q
        lines.append([s, q, amt])
        total += amt
    # PLANTED 1 (demand side): digital SKUs unavailable in 华东 that week -> half of those orders never happen
    if STOCKOUT_WEEK[0] <= d <= STOCKOUT_WEEK[1] and "华东" in (big, shop_big[shop]) \
            and any(s["l1"] == "数码家电" for s, _, _ in lines) and R.random() < 0.5:
        oid_base -= 1
        continue
    in_promo = d in PROMO_DAYS
    act = next((a for a in ACTS if a[1] <= d <= a[2]), None)
    disc_parts = []
    if act and R.random() < 0.7:
        disc_parts.append(("ACTIVITY", None, act[0], money(float(total) * R.uniform(0.05, 0.15))))
    if R.random() < 0.35:
        tpl = R.choice([x for x in TEMPLATES if x[1] <= float(total)] or [TEMPLATES[0]])
        coupon_id += 1
        coupon_t.add(coupon_id, tpl[0], m["id"], "USED", created - timedelta(days=R.randint(0, 20)), created)
        disc_parts.append(("COUPON", coupon_id, None, money(min(tpl[2], float(total) * 0.3))))
    lvl_rate = [1.0, 0.98, 0.95, 0.92][m["lvl"] - 1]
    if lvl_rate < 1:
        disc_parts.append(("MEMBER", None, None, money(float(total) * (1 - lvl_rate))))
    discount = sum((p[3] for p in disc_parts), Decimal(0))
    freight = Decimal(0) if total >= 99 else Decimal("8.00")
    pay = total - discount + freight
    # status path
    r = R.random()
    paid = None if r < 0.06 else created + timedelta(minutes=R.randint(1, 40))
    status = "CANCELLED" if paid is None else "PAID"
    O_row = [oid, f"E{d.strftime('%Y%m%d')}{oid:07d}", m["id"], shop, source, status, total, discount, freight, pay, "CNY", created, paid, None, 0]
    # items
    item_ids = []
    rem_disc = discount
    for i, (s, q, amt) in enumerate(lines):
        share = money(discount * amt / total) if i < len(lines) - 1 else rem_disc
        rem_disc -= share
        iid = nid("oi")
        OI.add(iid, oid, s["id"], q, s["price"], amt, share, amt - share,
               x=dict(sku_name=SPU_NAME[s["spu"]], category_id=SPU_CAT[s["spu"]], brand_id=SPU_BRAND[s["spu"]], is_gift=0,
                      activity_price=None, coupon_share=None, promotion_share=None, member_share=None,
                      cost_price=COST[(s["id"], d.strftime("%Y-%m"))], item_status="NORMAL", refunded_qty=0))
        item_ids.append((iid, s, q, amt - share))
    for p in disc_parts:
        OP.add(nid("op"), oid, p[0], p[1], p[2], p[3])
    prov = m["prov"] if R.random() < 0.85 else pick_province()[0]
    prid, pbig = prov_region[prov]
    OA.add(nid("oa"), oid, prov, CITY[prov], prid, "*" + R.choice("伟芳娜敏静丽强磊军洋"))
    OSL.add(nid("osl"), oid, None, "CREATED", created, "USER")
    if paid is None:
        cancelled = created + timedelta(minutes=R.choice([15, 30, 30, 120]))
        OSL.add(nid("osl"), oid, "CREATED", "CANCELLED", cancelled, "SYSTEM")
        CAN.add(nid("can"), oid, R.choice(["超时未支付", "不想要了", "信息填错"]), R.choice(["SYSTEM_TIMEOUT", "USER"]), cancelled,
                x=dict(refund_needed=0, order_status_before="CREATED"))
        SD_X[(d, shop)]["cancel"] += 1
        O.add(*O_row, x=order_extra(O_row))
        continue
    # payment
    if R.random() < 0.05:
        PAY.add(nid("pay"), oid, R.choice(["ALIPAY", "WECHAT"]), "FAILED", pay, None)
    chan = R.choices(["ALIPAY", "WECHAT", "CARD", "GIFT_CARD"], weights=[45, 40, 12, 3])[0]
    pid = nid("pay")
    PAY.add(pid, oid, chan, "SUCCESS", pay, paid)
    rate = {"ALIPAY": 0.006, "WECHAT": 0.006, "CARD": 0.008, "GIFT_CARD": 0.0}[chan]
    FEE.add(nid("fee"), pid, rate, money(float(pay) * rate))
    if chan == "GIFT_CARD":
        GC.add(nid("gc"), oid, f"GC{R.randrange(10**9)}", pay, paid)
    OSL.add(nid("osl"), oid, "CREATED", "PAID", paid, "USER")
    if m["id"] not in member_first:
        member_first[m["id"]] = oid
        TAG.add(nid("tag"), oid, "FIRST_ORDER")
        ORDER_META.setdefault(oid, {})["first"] = True
        ATTR.add(nid("attr"), m["id"], oid, m["ch"])
    if in_promo:
        TAG.add(nid("tag"), oid, "BIG_PROMO")
    if R.random() < 0.01:
        TAG.add(nid("tag"), oid, "HIGH_RISK")
        ORDER_META.setdefault(oid, {})["risk"] = True
    if R.random() < 0.03:
        O_row[14] = 1
        dep = money(float(pay) * 0.2)
        PRE.add(nid("pre"), oid, dep, pay - dep, paid + timedelta(days=R.randint(3, 10)))
    PTS.add(nid("pts"), m["id"], int(pay), "ORDER", paid)
    if R.random() < 0.2:
        INV.add(nid("inv"), oid, R.choice(["NORMAL", "NORMAL", "VAT"]), pay, paid + timedelta(days=R.randint(1, 15)))
        ORDER_META.setdefault(oid, {})["invoice"] = True

    # fulfilment: warehouse by receiver region
    whs = WH_FOR_BIG[pbig]
    wh = whs[0] if R.random() < 0.7 else R.choice(whs)
    stockout = False
    planted = STOCKOUT_WEEK[0] <= d <= STOCKOUT_WEEK[1] and "华东" in (pbig, shop_big[shop]) and any(s["l1"] == "数码家电" for _, s, _, _ in item_ids)
    if planted:
        wh = 3
        stockout = R.random() < 0.55
    elif R.random() < 0.02:
        stockout = True
    so_sku = None
    if stockout:
        _, so_s, so_q, _ = next(((a, s, q, p) for a, s, q, p in item_ids if (not planted or s["l1"] == "数码家电")), item_ids[0])
        so_sku = so_s
        SO.add(nid("so"), oid, so_s["id"], wh, so_q, paid + timedelta(minutes=R.randint(5, 120)))
    sdr = sd(d, shop)
    sdr[0] += total + freight
    sdr[1] += pay
    sdr[3] += 1
    sdr[4].add(m["id"])
    SD_X[(d, shop)]["discount"] += discount
    SD_X[(d, shop)]["freight"] += freight
    SD_X[(d, shop)]["items"] += sum(q for _, _, q, _ in item_ids)
    SD_X[(d, shop)]["new"] += int(member_first.get(m["id"]) == oid)
    for _, s, q, amt in item_ids:
        x = sku_daily.setdefault((d, s["id"]), [0, Decimal(0), 0])
        x[0] += q
        x[1] += amt
        sx = SKU_X[(d, s["id"])]
        sx[0] += 1
        sx[1].add(m["id"])
        sx[2] += s["price"] * q
    mk = d.strftime("%Y-%m")
    st = settle.setdefault((shop, mk), [Decimal(0), []])
    st[0] += pay
    st[1].append((oid, pay))

    if stockout and (planted or R.random() < 0.8):
        # cancelled for stockout -> full refund
        asid = nid("as")
        t0 = paid + timedelta(hours=R.randint(2, 30))
        AS.add(asid, oid, "REFUND", "STOCKOUT", "CLOSED", t0, t0 + timedelta(hours=R.randint(1, 24)))
        rid2 = nid("rf")
        RF.add(rid2, oid, asid, "REFUND_ONLY", "STOCKOUT", "SUCCESS", pay, t0, t0 + timedelta(hours=R.randint(1, 24)))
        for iid, s, q, p in item_ids:
            RFI.add(nid("rfi"), rid2, iid, q, p, x=dict(sku_id=s["id"], is_returned=0))
        rd = (t0 + timedelta(hours=12)).date()
        sd(rd, shop)[2] += pay
        SD_X[(rd, shop)]["refund_orders"] += 1
        O_row[5] = "CLOSED"
        OSL.add(nid("osl"), oid, "PAID", "CLOSED", t0, "SYSTEM")
        if R.random() < 0.3:
            COMP.add(nid("comp"), oid, "COUPON", Decimal("10.00"), "STOCKOUT", t0)
        O.add(*O_row, x=order_extra(O_row))
        continue

    fid = nid("fo")
    sla = SLA[(wh, pbig)]
    fo_alloc, fo_prom = paid + timedelta(minutes=R.randint(1, 30)), paid + timedelta(hours=sla)
    ORDER_META.setdefault(oid, {})["promised"] = fo_prom.date()
    pick = paid + timedelta(hours=R.uniform(2, 30))
    obid = nid("ob")
    OB.add(obid, fid, wh, pick, pick + timedelta(hours=R.uniform(0.5, 4)), x=dict(status="SHIPPED"))
    for _, s, q, _ in item_ids:
        OBI.add(nid("obi"), obid, s["id"], q)
        sku_sales_by_wh[(wh, s["id"])] = sku_sales_by_wh.get((wh, s["id"]), 0) + q
    carrier = R.choices([1, 2, 3, 4, 5], weights=[20, 20, 25, 20, 15])[0]
    shipped = pick + timedelta(hours=R.uniform(1, 6))
    transit = sla * R.uniform(0.45, 1.05)
    if carrier == SLOW_CARRIER and pbig == SLOW_REGION_BIG and SLOW_Q[0] <= d <= SLOW_Q[1]:
        transit = sla * R.uniform(1.2, 2.0)
    delivered = shipped + timedelta(hours=transit)
    shid = nid("sh")
    SH.add(shid, fid, carrier, f"T{shid:09d}", shipped, delivered,
           x=dict(status="DELIVERED", exception_type="DELAY" if delivered > fo_prom else None, receiver_city=CITY[prov]))
    FO.add(fid, oid, wh, "DELIVERED", fo_alloc, fo_prom,
           x=dict(split_seq=1, delivery_type="EXPRESS", shipped_at=shipped, delivered_at=delivered, cancel_reason=None))
    wcity = CITY[next(w[3] for w in WAREHOUSES if w[0] == wh)]
    for node, frac, city in (("PICKED_UP", 0, wcity), ("IN_TRANSIT", 0.4, wcity), ("OUT_FOR_DELIVERY", 0.9, CITY[prov]), ("DELIVERED", 1.0, CITY[prov])):
        TR.add(nid("tr"), shid, node, city, shipped + (delivered - shipped) * frac)
    OSL.add(nid("osl"), oid, "PAID", "SHIPPED", shipped, "SYSTEM")
    late = delivered > paid + timedelta(hours=sla)
    if R.random() < 0.025 and (late or R.random() < 0.3):
        CMP.add(nid("cmp"), oid, "LOGISTICS" if late else R.choice(["QUALITY", "SERVICE", "PRICE"]), R.randint(1, 3), delivered, delivered + timedelta(hours=R.randint(2, 72)))
    # refund / aftersale after delivery
    rr = R.random()
    p_ref = 0.07 + (0.06 if late else 0) + (0.05 if s["l1"] == "服饰鞋包" else 0)
    if rr < p_ref:
        code = R.choices(["QUALITY", "LATE_DELIVERY", "NO_REASON", "WRONG_ITEM", "DAMAGED"], weights=[25, 25 if late else 3, 40, 5, 7])[0]
        iid, s_, q, p = R.choice(item_ids)
        asid = nid("as")
        t0 = delivered + timedelta(hours=R.randint(2, 120))
        rtype = "RETURN_REFUND" if code != "LATE_DELIVERY" and R.random() < 0.7 else "REFUND_ONLY"
        AS.add(asid, oid, "RETURN" if rtype == "RETURN_REFUND" else "REFUND", code, "CLOSED", t0, t0 + timedelta(days=R.randint(1, 7)))
        ok = R.random() < 0.9
        rid2 = nid("rf")
        done = t0 + timedelta(days=R.randint(1, 6)) if ok else None
        RF.add(rid2, oid, asid, rtype, code, "SUCCESS" if ok else "REJECTED", p, t0, done)
        RFI.add(nid("rfi"), rid2, iid, q, p, x=dict(sku_id=s_["id"], is_returned=int(rtype == "RETURN_REFUND")))
        if ok:
            sd(done.date(), shop)[2] += p
            SD_X[(done.date(), shop)]["refund_orders"] += 1
            x = sku_daily.setdefault((done.date(), s_["id"]), [0, Decimal(0), 0])
            x[2] += q
            SKU_X[(done.date(), s_["id"])][3] += p
            PTS.add(nid("pts"), m["id"], -int(p), "REFUND", done)
        if rtype == "RETURN_REFUND":
            RL.add(nid("rl"), rid2, R.randint(1, 4), f"R{rid2:09d}", t0 + timedelta(days=1), t0 + timedelta(days=R.randint(2, 5)))
            inb = nid("inb")
            INB.add(inb, wh, "RETURN", rid2, t0 + timedelta(days=R.randint(2, 6)))
            INBI.add(nid("inbi"), inb, s_["id"], q)
        if code in ("DAMAGED", "LATE_DELIVERY") and R.random() < 0.3:
            COMP.add(nid("comp"), oid, R.choice(["CASH", "COUPON", "POINTS"]), money(R.uniform(5, 30)), code, t0)
        O_row[5] = "COMPLETED"
        O_row[13] = delivered + timedelta(days=R.randint(1, 7))
    else:
        O_row[5] = "COMPLETED" if delivered.date() < END - timedelta(days=7) else "SHIPPED"
        if O_row[5] == "COMPLETED":
            O_row[13] = delivered + timedelta(days=R.randint(1, 7))
            OSL.add(nid("osl"), oid, "SHIPPED", "COMPLETED", O_row[13], R.choice(["USER", "SYSTEM"]))
            if R.random() < 0.3:
                iid, s_, _, _ = R.choice(item_ids)
                REV.add(nid("rev"), iid, m["id"], R.choices([5, 4, 3, 2, 1], weights=[60, 20, 8, 5, 7] if not late else [30, 20, 15, 15, 20])[0], R.random() < 0.3, O_row[13] + timedelta(days=1))
    if len(item_ids) >= 3 and R.random() < 0.1:
        SPL.add(nid("spl"), oid, oid, "MULTI_WAREHOUSE")
    O.add(*O_row, x=order_extra(O_row))

# unused coupons
for _ in range(15000):
    coupon_id += 1
    m = R.choice(MEMBERS)
    rec = rand_dt(START + timedelta(days=R.randrange(DAYS)))
    coupon_t.add(coupon_id, R.choice(TEMPLATES)[0], m["id"], R.choice(["UNUSED", "EXPIRED", "EXPIRED"]), rec, None)

# cart
t = tbl("trd_cart", "id member_id sku_id quantity added_at converted")
for i in range(1, 30001):
    t.add(i, R.choices(MEMBERS, weights=MEM_W)[0]["id"], R.choices(ON_SALE, weights=SKU_W)[0]["id"], R.randint(1, 3), rand_dt(START + timedelta(days=R.randrange(DAYS))), R.random() < 0.35)

# daily aggregates
t = tbl("trd_daily_shop_sales", "id stat_date shop_id gmv paid_amount refund_amount net_sales order_cnt buyer_cnt")
k = 0
for (d, shop), v in sorted(shop_daily.items()):
    if d > END:
        continue
    k += 1
    e = SD_X[(d, shop)]
    t.add(k, d, shop, v[0], v[1], v[2], v[1] - v[2], v[3], len(v[4]),
          x=dict(discount_amount=money(e["discount"]), freight_amount=money(e["freight"]), new_buyer_cnt=e["new"],
                 item_qty=e["items"], refund_order_cnt=e["refund_orders"], cancel_order_cnt=e["cancel"], etl_batch=f"B{d:%Y%m%d}"))
t = tbl("trd_daily_sku_sales", "id stat_date sku_id sales_qty sales_amount refund_qty")
k = 0
for (d, sku), v in sorted(sku_daily.items()):
    if d > END:
        continue
    k += 1
    e = SKU_X[(d, sku)]
    t.add(k, d, sku, v[0], v[1], v[2], x=dict(shop_id=None, gmv=money(e[2]), order_cnt=e[0], buyer_cnt=len(e[1]),
                                             refund_amount=money(e[3]), etl_batch=f"B{d:%Y%m%d}"))

# traffic (uv roughly tied to buyers)
t = tbl("mkt_traffic_daily", "id stat_date shop_id channel_id pv uv")
k = 0
for d in ALL_DAYS:
    for shop in range(1, 13):
        buyers = shop_daily.get((d, shop), [0, 0, 0, 0, set()])[3]
        for ch in range(1, 6):
            k += 1
            uv = int((buyers + 1) * R.uniform(8, 25) * [0.3, 0.15, 0.1, 0.15, 0.3][ch - 1])
            t.add(k, d, shop, ch, int(uv * R.uniform(2.5, 5)), uv)

# settlements
t = tbl("fin_settlement", "id shop_id month_key gross_amount commission net_amount settled_at")
k = 0
for (shop, mk), (gross, orders) in sorted(settle.items()):
    k += 1
    rate = Decimal({1: "0.05", 2: "0.06", 3: "0.08", 4: "0.00"}[SHOPS[shop - 1][1]])
    com = money(gross * rate)
    y, mo = map(int, mk.split("-"))
    nxt = date(y + (mo == 12), mo % 12 + 1, 10)
    t.add(k, shop, mk, gross, com, gross - com, datetime.combine(nxt, datetime.min.time()) if nxt <= END else None)
    for oid, amt in orders:
        SETI.add(nid("seti"), k, oid, amt)
t = tbl("fin_platform_fee", "id platform_id category_id commission_rate")
k = 0
for p in range(1, 5):
    for n1, c1 in l1_ids.items():
        k += 1
        t.add(k, p, c1, [0.05, 0.06, 0.08, 0.0][p - 1] + (0.01 if n1 == "美妆个护" else 0))
t = tbl("fin_sales_target", "id shop_id month_key target_amount")
k = 0
for shop in range(1, 13):
    for mk in MONTHS:
        k += 1
        actual = sum((v[1] for (d, s), v in shop_daily.items() if s == shop and d.strftime("%Y-%m") == mk), Decimal(0))
        t.add(k, shop, mk, money(float(actual) * R.uniform(0.9, 1.15)) if actual else Decimal("10000.00"))

# purchasing, inbound, inventory
STOCKED = {}  # (wh, sku) -> on hand
for s in SKUS:
    for wh in R.sample([1, 2, 3, 4, 5, 6], 2) + ([7] if s["pop"] > 3 else []) + ([8] if s["pop"] > 3 else []):
        STOCKED[(wh, s["id"])] = 0
POT = tbl("scm_purchase_order", "id supplier_id warehouse_id status created_at expected_at")
POI = tbl("scm_purchase_order_item", "id po_id sku_id quantity unit_cost")
po, k = 0, 0
for mk in MONTHS:
    y, mo = map(int, mk.split("-"))
    for wh in range(1, 7):
        po += 1
        created = datetime(y, mo, R.randint(1, 10), 10)
        exp = created.date() + timedelta(days=R.randint(5, 20))
        received = exp <= END
        partial = R.random() < 0.12
        POT.add(po, R.randrange(1, 26), wh, "RECEIVED" if received and not partial else "PARTIAL_RECEIVED" if received else "CREATED", created, exp)
        inb = nid("inb") if received else None
        if received:
            INB.add(inb, wh, "PURCHASE", po, datetime.combine(exp, datetime.min.time()) + timedelta(hours=R.randint(8, 18)))
        for (w, sku) in [x for x in STOCKED if x[0] == wh and R.random() < 0.2]:
            k += 1
            q = R.randint(20, 300)
            POI.add(k, po, sku, q, COST[(sku, mk)])
            if received:
                INBI.add(nid("inbi"), inb, sku, int(q * (R.uniform(0.6, 0.95) if partial else 1)))
                STOCKED[(w, sku)] += q
WT = tbl("wms_transfer", "id from_warehouse_id to_warehouse_id status created_at done_at")
WTI = tbl("wms_transfer_item", "id transfer_id sku_id quantity")
k = 0
for i in range(1, 301):
    a, b = R.sample(range(1, 9), 2)
    c = rand_dt(START + timedelta(days=R.randrange(DAYS - 5)))
    WT.add(i, a, b, "DONE", c, c + timedelta(days=R.randint(1, 4)))
    for sku in R.sample(SKUS, R.randint(1, 4)):
        k += 1
        WTI.add(k, i, sku["id"], R.randint(5, 80))
# realtime inventory (end of window)
t = tbl("wms_inventory", "id warehouse_id sku_id on_hand_qty locked_qty safety_stock updated_at")
k = 0
FINAL = {}
for (wh, sku), _ in STOCKED.items():
    sold = sku_sales_by_wh.get((wh, sku), 0)
    on = max(0, int(R.uniform(0.05, 0.4) * (sold + 50)) + R.randint(0, 30))
    if wh == 3 and next(s for s in SKUS if s["id"] == sku)["l1"] == "数码家电" and R.random() < 0.3:
        on = R.randint(0, 3)
    FINAL[(wh, sku)] = on
    k += 1
    t.add(k, wh, sku, on, min(on, R.randint(0, 5)), R.randint(5, 40), datetime(2026, 10, 31, 23, 50))
# daily snapshot, last 92 days (2026-08-01 .. 2026-10-31) to keep size bounded
t = tbl("wms_inventory_snapshot", "id snapshot_date warehouse_id sku_id on_hand_qty locked_qty available_qty")
k = 0
snap_days = list(daterange(date(2026, 8, 1), END))
for (wh, sku), fin in FINAL.items():
    l1 = next(s for s in SKUS if s["id"] == sku)["l1"]
    level = fin + R.randint(0, 40)
    for d in snap_days:
        on = max(0, int(level * R.uniform(0.85, 1.15)))
        if wh == 3 and l1 == "数码家电" and STOCKOUT_WEEK[0] - timedelta(days=3) <= d <= STOCKOUT_WEEK[1]:
            on = R.randint(0, 2)
        lk = min(on, R.randint(0, 4))
        k += 1
        t.add(k, d, wh, sku, on, lk, on - lk)
ST = tbl("wms_stocktake", "id warehouse_id stocktake_date")
STI = tbl("wms_stocktake_item", "id stocktake_id sku_id book_qty actual_qty")
k, st = 0, 0
for mk in MONTHS:
    y, mo = map(int, mk.split("-"))
    for wh in range(1, 9):
        st += 1
        ST.add(st, wh, date(y, mo, 25) if date(y, mo, 25) <= END else END)
        for (w, sku) in R.sample([x for x in STOCKED if x[0] == wh], min(30, len([x for x in STOCKED if x[0] == wh]))):
            k += 1
            book = R.randint(0, 200)
            act_q = max(0, book + (R.randint(-3, 3) if R.random() < 0.15 else 0))
            STI.add(k, st, sku, book, act_q, x=dict(diff_qty=act_q - book, diff_amount=None))

for t in TABLES.values():
    t.flush()
OUT.write("COMMIT;\nSET FOREIGN_KEY_CHECKS=1; SET UNIQUE_CHECKS=1;\n" if DIALECT == "mysql" else "COMMIT;\n")
missing = {"usr_member_level", "dim_currency"} - set(TABLES)
sys.stderr.write(f"tables filled: {len(TABLES)} orders: {oid_base}\n")
