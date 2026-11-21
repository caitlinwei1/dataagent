# 电商经营分析数据集（gbi_ecommerce）

Step 2 的产出：一个自建的电商 + 库存履约业务库，用于选表召回、问数和报告评测。所有内容（表结构、数据、术语）都是自己写的，没有使用 DataAgent 的示例表。

## 规模（`python3 dataset/stats.py` 实测）

| 项 | 数量 |
| --- | --- |
| 表 | 86 |
| 字段 | 1,154（v2；v1 为 1,146） |
| 业务术语 | 297（v2：指标 47、维度 11、缩写 11、枚举值 228；v1 为 312） |
| 逻辑外键 | 127（v2 新增 dim_shop.brand_id；库里不建物理外键，和多数业务库一致；导入 DataAgent logical_relation） |
| 注释消融组 | 38 张表（`ablate=True`），partial 版 DDL 去掉这些表的全部注释 |
| 数据行 | 1,473,129；时间窗 2024-11-01 至 2026-10-31，约 4.2 万订单 |

| 业务域 | 表数 | 消融表 |
| --- | --- | --- |
| 交易（订单、明细、支付、退款、售后、日汇总） | 25 | 12 |
| 商品（SPU/SKU、类目、品牌、价格、成本） | 15 | 8 |
| 用户与营销（会员、券、活动、渠道、流量、投放） | 15 | 6 |
| 库存仓储与履约（采购、入库、库存、调拨、出库、缺货、运单） | 20 | 8 |
| 财务结算与维表（结算、目标、汇率、区域、店铺、日期） | 11 | 4 |

## v2：按品牌电商服务商（代运营）的业务模式调整（2026-11-21）

原型参考公开资料里品牌电商服务商（如宝尊电商）的业务模式：替多个品牌在多个平台运营官方旗舰店，有经销、代运营、品牌管理三种合作模式，自建仓配。**品牌名、店铺名和所有数据都是虚构的模拟数据**，不代表任何公司的真实经营数据。v1 的数据就是提交 `f6acbc7`（本 PR 之前的 master），v1 上测得的指标（召回、40 题配对等）需要在 v2 上重跑后才能替换。

| 改动 | 表 / 字段 | 制造的难点 |
|---|---|---|
| 8 个品牌客户，每个品牌对应一个一级类目；12 个店铺 = 品牌 × 平台的官方旗舰店，店铺只卖本品牌商品 | `prd_brand`（重写）、`dim_shop.brand_id`（新增，逻辑外键 127 条） | 品牌之间的数据隔离；按品牌统计要走 `dim_shop.brand_id` |
| 合作模式：经销 / 代运营（TP）/ 品牌管理，代运营有服务费率 | `prd_brand.coop_mode`、`service_fee_rate` | “收入”口径因模式而异 |
| 结算拆出服务费和我方确认收入 | `fin_settlement.coop_mode`、`service_fee`、`revenue_amount` | 不能用 GMV / 实付当收入 |
| 平台原始订单状态码和订单号格式各不相同（天猫 `TRADE_FINISHED`、京东 `FINISHED_L`、抖音 `5`） | `trd_order.platform_status`、`platform_order_no` | 枚举异构；跨平台统计要用统一状态 `order_status` |
| 大促预售：618（定金 5/20–5/31，尾款 6/1 起）和双11（定金 10/20–10/30，尾款 10/31 20:00 起），定金期约 45% 订单为预售；`trd_order.paid_at` 是定金时间，履约从尾款支付后开始 | `trd_presale_order`（定金、尾款时间、状态都与订单一致） | 大促 GMV 按尾款归属；按 `paid_at` 过滤会漏单；尾款 00:00 后跨日 |
| 业务术语新增：我方收入、代运营服务费、大促GMV、品牌、合作模式、平台订单状态、TP | `metadata/business_terms.yaml` | 口径召回 |

埋入的两个根因保持不变：华东数码缺货现在落在曜石（华东两家店）和星屿（抖音店）上；极速达西南超时不受影响。

## 刻意制造的难点

- **近义字段**：`total_amount`（商品总额）/ `pay_amount`（实付）/ `trd_daily_shop_sales.gmv` / `net_sales`（退款后金额）/ `trd_order_item.pay_share`（明细实付）。口径写在 `metadata/business_terms.yaml`，“销售额”默认按实付。
- **相似表**：`wms_inventory`（实时）vs `wms_inventory_snapshot`（日快照）；`oms_fulfillment_order` → `wms_outbound` → `tms_shipment` 三段履约；`tms_shipment` vs `trd_return_logistics`；`trd_refund` / `trd_aftersale` / `trd_compensation`；`mkt_channel`（流量渠道）vs `trd_order_source`（下单端）；`dim_shop` vs `dim_store`。
- **区域口径歧义**：店铺运营归属区域 `dim_shop.region_id` vs 收货区域 `trd_order_address.region_id`。
- **跨域题**：缺货（`wms_stockout_record`）→ 退款（`trd_refund.reason_code='STOCKOUT'`）。
- **注释消融**：同一份数据，`ddl/mysql_full.sql` 与 `ddl/mysql_partial.sql` 只差注释。

## 埋入的根因模式（给归因 / 报告题用）

1. **华东数码缺货**：2026-10-12 至 10-18，华东区域仓（warehouse 3）数码家电缺货。华东店铺这一周 GMV 103,389，上周 124,095（-16.7%），缺货退款 7 单（前几周 1–2 单）；`wms_inventory_snapshot` 中该仓数码家电可用库存降到 0–2。
2. **极速达西南超时**：2026 Q3 承运商 5（极速达）发往西南的平均履约时长 95.7 小时，其他承运商约 53–57 小时，Q2 时极速达也是 54.6 小时。

## 文件

| 路径 | 说明 |
| --- | --- |
| `schema/tables.py` | 86 张表的核心字段定义（唯一来源） |
| `schema/extra_columns.py` | 业务扩展字段与审计字段规则 |
| `build_schema.py` | 生成 MySQL / PostgreSQL DDL（full、partial）、M-Schema、`metadata/tables.json`、`logical_relations.json` |
| `gen_seed.py` | 确定性造数（固定随机种子），输出 MySQL 或 PG 方言 INSERT |
| `derive.sql` | 跨行派生字段（退款件数、会员累计消费等），两种方言通用 |
| `ddl/readonly_*.sql` | 只读账号 `gbi_reader`（MySQL 仅 SELECT；PG 默认只读事务 + 30s statement_timeout） |
| `metadata/business_terms.yaml` | 指标口径、维度、缩写、相似表说明 |
| `metadata/semantic_model.yaml` | 25 个易混字段的列级语义模型 |
| `build_glossary.py` → `metadata/glossary.json` | 312 条术语（含从库里读出的枚举值） |
| `metadata/mschema_{full,partial}.txt` | XiYan 风格 M-Schema |
| `sample/*.csv` | 8 张核心表各前 20 行 |

## 复现

```bash
./scripts/load_dataset.sh                   # 建库、造数、加载 MySQL 与 PostgreSQL、建只读账号、生成术语与统计
VARIANT=partial ./scripts/load_dataset.sh   # 注释消融版
python3 scripts/setup_agent.py              # 在 DataAgent 里建数据源（只读账号）+ 智能体 + 86 表 + 逻辑外键 + 术语 + 语义模型，并初始化
```

## 已知限制

- 扩展字段中非关键的文本/数值列（如 `buyer_remark`、`trd_order.device_type`）是随机填充，只保证类型和枚举合法；参与指标口径的扩展字段（明细类目/成本、会员累计、履约时间、日汇总扩展列等）与核心数据一致。
- `wms_inventory_snapshot` 只覆盖 2026-08-01 起 92 天，控制数据量。
- PostgreSQL 库同时作为 Hologres / ADB-PG 的本地替身；两者都是 PG 协议，但没有在真实实例上验证。
- 在 b297203 原版上初始化 86 表需要约 12.5 分钟：SimpleVectorStore 逐条调用 embedding（1,232 次），后续可改批量。
