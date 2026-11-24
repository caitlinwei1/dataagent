# 11 B4 / B5 / B6：不卡死了，答案为什么还错 —— 逐节点根因

> 接 [10](10-baozun-scenario.md)。10 里 B4~B6 修掉的是"流程卡死"（死循环、校验锁死），但跑完的答案还是错的。这一页把每个例子沿链路 **意图 → 证据 → 问题增强 → 召回 → 精选 → 计划 → SQL → Python → 报告** 逐节点对照，找**最早出错的节点**，再说修法。
>
> 依据：`results/baozun/B*.after-r*.trace.jsonl`（10 里修完流程后的轮次）逐节点展开；gold SQL 在 v2 数据上重跑；修复后的重跑记录是 `B*.after2-*`、`B*.after3-*`、`B6.after4`、`B6.after5`。数据都是模拟数据。

## 一句话结论

三个例子最早出错的地方都**不在 SQL 生成**，而在 SQL 之前给模型的"材料"：

| | 最早出错的节点 | 错在哪 | 后面的节点怎么被带偏 |
|---|---|---|---|
| B4 | 召回 + **精选（TableRelationNode）** | 召回 10 张表里没有 `prd_sku / prd_spu / prd_category`；精选模型其实**选了** `prd_sku`、`prd_category`（从外键串里看到的表名），但这两张表没有 schema，被 `removeIf` **静默丢掉** | SQL 只能猜 `sku.category_id`；补表后又按一级类目 id 直接比三级类目，结果为空 |
| B5 | **证据召回 → 问题增强** | 证据 top-1 是枚举值"2025双11 → `mkt_activity.name`"，问题增强把它写进了 canonical query：3 轮都是"查询2025年双11营销活动（mkt_activity.name = '2025双11'）期间……" | r2 真按活动表筛订单 → 空；r3 没筛活动，但自己加了 `order_status IN (PAID,SHIPPED,COMPLETED)`，漏掉支付后关闭的单 → 少 1.3% |
| B6 | **证据召回 + 召回** | ① 问题里明写"销售额"，证据 8 条里没有"销售额"的口径（被店铺名、"2026双11"挤掉）；② 召回没有 `trd_order`，`trd_refund` 和店铺之间没有关联路径 | 计划用日汇总表 `trd_daily_shop_sales.paid_amount` 算销售额（含缺货关单，只跌 19.5%，真实跌 64%）；计划编造 `trd_refund.order_id = trd_daily_shop_sales.id` 关联，缺货退款查成 0 / 1 单 |

所以 10 里说的"模型推理错误"，大部分其实是**上游给的材料就错或不全**，模型只是照着错材料写。修法也就落在 SQL 之前，并且都是确定性的。

---

## B4 表多且相似：2026-10-15 华东区域仓数码家电可用库存

**正确答案**：23 件（日快照 `wms_inventory_snapshot`，经 SKU → SPU → 三级 → 二级 → 一级类目）。实时表 `wms_inventory` 会给 1,642。

### 逐节点（`B4.after-r2.trace.jsonl`）

| 节点 | 输出 | 对不对 |
|---|---|---|
| 意图 | 数据分析 | 对 |
| 证据召回 | 华东区域仓 → `wms_warehouse.name`；数码家电 → `prd_category.name` | 对，但没说"数码家电是一级类目" |
| 问题增强 | 时间 `>= '2026-10-15' AND < '2026-10-16'`、仓库、类目都写对了 | 对 |
| Schema 召回 | `dim_region, wms_warehouse, trd_order_address, wms_inventory, wms_inventory_snapshot, …`（10 张） | **错：没有 prd_sku / prd_spu / prd_category** |
| 精选 | 模型返回 `["wms_warehouse","wms_inventory_snapshot","prd_category","prd_sku"]` | 选对了方向，但 **`prd_category`、`prd_sku` 不在 schema 里，被过滤掉**（`Nl2SqlServiceImpl.fineSelect` 的 `removeIf`） |
| SQL 第 1 次 | `JOIN prd_category c ON sku.category_id = c.id` | 猜字段 → `Unknown column` |
| 报错补表（10 里的修复） | 补 6 张邻居表，`prd_spu` 在内 | 死循环没了 |
| SQL 最终 | `spu.category_id = (SELECT id FROM prd_category WHERE name='数码家电')` | **错：`spu.category_id` 指向三级类目，"数码家电"是一级（level=1，id=1），结果 NULL** |

### 根因
1. **召回漏了桥接表，精选又把模型要的表静默丢了**。这是一个代码级缺陷：精选模型能看到外键串 `wms_inventory_snapshot.sku_id=prd_sku.id`，于是选了 `prd_sku`，但这张表没被召回、没有列信息，过滤时直接丢掉，也不报。
2. **类目是三级树，证据只说了"数码家电对应 prd_category.name"，没说它是一级、要向上关联两次**。术语"类目"里写了，但这条术语没进证据（证据 top-8 被枚举值占满）。

### 修法和为什么
| 方案 | 为什么不选 / 选 |
|---|---|
| 召回 topK 调大 | 每题 prompt 都变长；01 里测过 topK 和噪声的权衡 |
| 只靠出错后补表（10 里已有） | 能救死循环，但要先错一次（多一次 SQL + 一次执行），而且补的是"邻居"不是"路径"，B4 补了 6 张只有 1 张有用 |
| **精选后按外键补齐关联路径（选这个）** | 精选完、写计划和 SQL 之前：① 模型选了但召回没给的表，按名字把 schema 补回来；② 选中的表在外键图里不连通时，用最短路径（≤3 条边）把中间的桥接表补进来。确定性、不调模型；只补路径上的表，B4 只多 `prd_spu`（+ 被选中的 `prd_sku`、`prd_category`） |
| **一级/二级类目的枚举值证据直接给出过滤写法（选这个）** | 值召回命中"数码家电"时，证据写明"一级类目，`prd_spu.category_id IN (SELECT c3.id … WHERE c1.name='数码家电')`"。层级是数据里确定的事实，不该让模型每次自己推 |

代码：`SchemaExpansionService.completeJoinPaths()/bridge()`，`TableRelationNode.completeJoinPaths()`，`SchemaDTO.requestedTables`；`dataset/build_glossary.py` 的 `enum_note()`。开关 `gbi.nl2sql.join-path-completion`。

---

## B5 大促时间窗：2025 双11（10-31~11-11）各品牌 GMV

**正确答案**：曜石 956,004.20（金额 = `total_amount + freight_amount`；预售单按尾款时间 `balance_paid_at` 归属；**不按订单状态过滤**，支付后关闭的单也计 GMV）。只按 `paid_at` 过滤是 852,227.60（漏掉 200 张预售单）。

### 逐节点（3 轮对比）

| 节点 | r1 | r2 | r3 |
|---|---|---|---|
| 证据召回 top-1 | **"2025双11 → mkt_activity.name"** | 同 | 同 |
| 证据里有没有 GMV 定义 | 没有（只有"大促GMV"） | 没有 | 没有 |
| 问题增强 canonical query | **"2025年双11营销活动（mkt_activity.name = '2025双11'）期间"** | 同 | 同 |
| 精选 | 含 `mkt_activity` | 含 `mkt_activity` | 含 `mkt_activity` |
| SQL | 定金+尾款当金额（术语歧义，已在 10 里修） | **JOIN 活动表 + 订单促销表**，再加状态过滤 → 空 | 没 JOIN 活动，但加 **`order_status IN ('PAID','SHIPPED','COMPLETED')`** |
| 结果 | 873,706.96（-8.6%） | 空 | 943,238.18（-1.3%） |

数据核对：`2025双11` 这条活动记录只属于 1 个平台（`platform_id=1`），`trd_order_promotion` 关联它的 1,308 单里**预售单 0 张**；窗口内支付后关闭的单 35 张、GMV 42,669.73，r3 正是丢了它们。

### 根因
1. **枚举值证据被当成过滤条件**。向量召回按问题文字相似度，"2025双11"这个活动名和问题最像，排第一；问题增强把它写进了标准问题，后面每个节点都当成"用户要求按活动筛"。但业务上"双11期间"是**时间段**，活动表是单平台的一条活动记录。
2. **GMV 的口径定义没进证据**。问题里明写"GMV"，但 top-8 被 7 条枚举值占满，GMV"不扣退款"的定义不在；模型按常识"有效订单"加了状态过滤。

### 修法和为什么
| 方案 | 为什么不选 / 选 |
|---|---|
| 去掉问题增强 | 问题增强对时间口径（`>= / <`）帮助很大（B4 的时间就是它写对的），不能去 |
| 在 SQL 后做"多余条件"检查 | 有价值（99 第 3 条），但这次的错在 SQL 之前就定了；先修源头 |
| **术语字面命中保底（选这个）** | 问题里字面出现的指标/维度术语（含同义词，枚举值除外）一定进证据、排在最前，最多 4 条。"GMV""销售额"这种词用户说出来就是在指定口径，不该和枚举值抢 top-8 |
| **活动名枚举值写明用法（选这个）** | 证据写明"这是单平台的活动记录；问双11期间的 GMV 按日期范围过滤，不要关联活动表筛订单"；"大促GMV"术语同样补一句 |
| **GMV 口径写明"不按订单状态过滤"（选这个）** | 和"销售额（排除取消/关闭）"区分开，这是两个指标真正的差别 |

代码：`EvidenceRecallNode.literalTermDocuments()`（开关 `gbi.nl2sql.literal-term-evidence`）；`dataset/metadata/business_terms.yaml`（GMV、大促GMV、销售额）；`build_glossary.py` 的 `enum_note()`。

---

## B6 多步归因：曜石华东 10/12~18 比前一周跌了多少、为什么

**正确答案**：销售额（实付、排除取消/关闭）172,718.25 → 62,444.73，**-63.8%**。本周 43 单里 23 单是"支付后因缺货关闭"（23 单全部是 `STOCKOUT` 退款，实付 76,637.57）。根因：华东区域仓数码家电缺货。

### 逐节点（`B6.after-r2.trace.jsonl`，r1/r3 同类）

| 节点 | 输出 | 对不对 |
|---|---|---|
| 证据召回 | 两个曜石店铺名、曜石、华东、大区、大促GMV、缺货退款率、2026双11 | **错：问题写的是"销售额"，证据里没有"销售额"口径** |
| Schema 召回 | `trd_aftersale_reason, dim_region, trd_daily_shop_sales, dim_shop, dim_date, trd_daily_sku_sales, wms_stockout_record, trd_refund, prd_brand, prd_listing_log` | **错：没有 `trd_order`**（退款和店铺之间唯一的关联路径） |
| 计划 第 1 步 | 用 `trd_daily_shop_sales` 按天取 GMV、实付 | **错表**：日汇总的 `paid_amount` 含支付后被关闭的单，算出来只跌 19.5%（139,082.30 vs 172,718.25），把根因"抹平"了 |
| 计划 第 2 步 | "`trd_refund` 关联 `trd_daily_shop_sales`…" | **不可行的关联**：两表之间没有外键路径；SQL 写成 `tr.order_id = tds.id`，只查到 1 单 |
| 计划 第 3 步 | 查 `dim_date.promo_name` 看有没有大促 | 被证据里的"大促GMV / 2026双11"带出来的无关步骤 |
| Python / 报告 | r2 报告写 -43.2%（自己切错了天数）；r3 Python 拿字符串和数字比 | 下游错误，但前面的数已经错了 |

### 根因
1. **相似表口径不同**：日汇总表 `trd_daily_shop_sales.paid_amount` 和明细 `trd_order` 都能算"实付"，但汇总表不排除支付后关闭的单。偏偏这个例子的根因就是"支付后缺货关单"，用汇总表正好把根因藏起来。表注释和术语都没写这一点，"销售额"术语又没进证据。
2. **漏召桥接表 `trd_order`**，计划只能在现有表里编关联（和 B4 同一类问题）。
3. 下游（报告算术、Python 类型）的错是真的，但即使它们全对，前两条也已经让结论错了。

### 修法和为什么
- **归因模板**（新）：问题里有"原因/为什么/归因"时，① 把固定的诊断表（`trd_refund`、`wms_stockout_record`、`wms_warehouse`）加入精选结果，再走关联路径补齐；② 给规划器追加固定拆解顺序：本期/上期 × 订单状态 → 退款原因 → 缺货仓库 → Python 只算已有列 → 报告。为什么不让模型自由发挥：第一版修复后重跑，环比数字对了，但计划去"按设备类型、支付方式拆解"，这些列 SQL 根本没取，Python 连错 4 次。归因维度是业务定的，应该写成配置（`gbi.nl2sql.attribution-*`），不是每次让模型猜。
- **结果集重名列**（新发现的 bug）：`SELECT shop.name, brand.name, warehouse.name` 有三列都叫 `name`，`ResultSetBuilder` 按列名取值、按列名放进 Map，三列塌成一列，仓库名从来没到过 Python 和报告，报告只能"推断"是华东中心仓（错的，应该是华东区域仓）。改成按下标取值，重名列加表名前缀（`wms_warehouse.name`）。
- **术语字面命中保底**（同 B5）："销售额"进证据，写明"必须排除取消/关闭订单，按店铺/品牌看要用 `trd_order` 明细；`trd_daily_shop_sales.paid_amount` 含缺货关单，不能当销售额"。表注释同步写上。
- **精选后补齐关联路径**（同 B4）：选中 `trd_refund` 和 `dim_shop` 不连通 → 最短路径补 `trd_order`。计划看得到正确关联，就不会写 `refund.order_id = shop_sales.id`。
- 下游的两类错误这次不修，记录在下面"还没解决的"。

---

## 修复后实测

条件：2026-11-24，qwen-plus，v2 数据，直连 gbi-agent（admin），同一题跑多轮。记录在 `results/baozun/B*.after2-r*`（第一版修复）、`B*.after3-r*`（第二版）、`B6.after4`、`B6.after5`。

| | 修复前（10 里的最后一轮） | 修复后 | 正确答案 |
|---|---|---|---|
| B4 | 空（NULL），3~4 次 SQL | **4/4 轮 = 23**；3 轮 1 次写对，1 轮被语义校验误驳回 2 次后放行（见下） | 23 |
| B5 | 943,238.18（-1.3%）/ 空 | 第一版 2 轮：852,227.60（预售归属丢了，见下）；**第二版：8 个品牌全部等于正确答案**（曜石 956,004.20） | 956,004.20 |
| B6 | 归因 3/3 错；环比 -19.5% 或 -43.2% | 见下表：环比 3/4 对；缺货归因 3/3 对；仓库 1/3 对 | -63.8%，华东区域仓缺货 |

B6 每一轮（同一问题，代码逐轮加修复）：

| 轮次 | 改动 | 环比 | 归因到缺货 | 仓库 | 问题出在哪 |
|---|---|---|---|---|---|
| `after2-r1` | 关联路径 + 字面术语 | **-63.84% ✓** | ✗ | — | 计划按"设备类型、支付方式"拆解，这些列 SQL 没取，Python 连错，最后网络断开无报告 |
| `after3-r1` | + 归因模板 | **✓** | **✓**（STOCKOUT 23 单 ¥76,637.57，上期 0） | ✗ 猜成"华东中心仓" | 三个 `name` 列塌成一列，仓库名没到报告 |
| `after4` | + 重名列修复 | ✗（报告写"上期有效销售为 0"） | **✓** | **✓ 华东区域仓 23/23** | 第 1 步 SQL 只按状态分组、没按期间分，报告拿合计硬解释 |
| `after5` | + 模板第 1 步写死"按期间 × 状态分组" | **✓** | **✓** | ✗ **编造**"苏州中心仓 12/21" | 仓库那一步 Python 失败，报告在没有数据的情况下编了一个仓库和数字 |

结论：B6 从"3/3 归因错"到"环比和缺货原因基本稳定地对"，但**最后一公里卡在报告节点**：上游步骤缺数据时，报告不会说"缺"，而是编。这已经不是 SQL 之前的材料问题了，是报告节点没有约束（见"还没解决的"第 1 条）。

### 修复过程中踩的坑（面试可讲）
1. **改证据描述，反而让它被召回得更靠前**。第一版给活动名枚举值加了一句"问双11期间的销售额、GMV 时按日期过滤……"，结果这条描述和问题更像了，B5 的证据 top-10 里 7 条是活动名，"大促GMV"被挤掉，预售归属又丢了（852,227.60，等于只按 `paid_at` 的错误答案）。第二版：枚举值的说明缩成一句事实（"单平台活动记录，不代表大促时间段"），正面的口径放进指标术语，再给"大促GMV"加同义词"双11期间/大促期间"走字面命中。**教训：向量召回里，说明文字也参与相似度，"提醒"写得越像问题，越会被错误地召回。**
2. **语义校验变成了最吵的节点**。B4 有一轮第一次 SQL 就是对的，但 LLM 校验说"`wms_warehouse` 没有 `name` 字段"（其实有，是召回给它的列不全），驳回 2 次；10 里加的"每步最多驳回 2 次"把它放行了，结果正确。没有那个上限，这题会回到死循环。

## 改了哪些代码

| 文件 | 改动 | 对应 |
|---|---|---|
| `Nl2SqlServiceImpl.fineSelect`、`SchemaDTO.requestedTables` | 记下精选模型要的全部表，不再静默丢 | B4 |
| `SchemaExpansionService.completeJoinPaths / bridge`、`TableRelationNode` | 精选后：补回模型要但召回没给的表；不连通时按外键最短路径（≤3 条边）补桥接表，最多 6 张，过滤无权限的表 | B4、B6 |
| `EvidenceRecallNode.literalTermDocuments` | 问题里字面出现的指标/维度术语（含同义词，枚举值除外）排在证据最前，最多 4 条 | B5、B6 |
| `GbiNl2sqlProperties`、`PlannerNode`、`TableRelationNode` | 归因类问题：加诊断表 + 固定拆解模板 | B6 |
| `ResultSetBuilder` | 按下标取值；重名列加表名前缀 | B6 |
| `dataset/build_glossary.py`、`business_terms.yaml`、`schema/tables.py` | 一级/二级类目给出过滤写法；活动名说明；GMV 不按状态过滤；销售额不能用日汇总表；`trd_daily_shop_sales.paid_amount` 注释 | B4、B5、B6 |
| `scripts/sync_knowledge.py` | 改了术语后只更新变化的条目（不用重注册 86 张表） | — |
| 单测 | `SchemaExpansionServiceTest`（+3）、`EvidenceLiteralTermTest`（2）、`ResultSetLabelTest`（2）；全量 191 个通过 | — |

开关都在 `gbi.nl2sql.*`：`join-path-completion`、`literal-term-evidence`、`attribution-keywords / attribution-tables / attribution-guide`，可以逐个关掉做对比。

## 还没解决的（主动说）

- **报告会编数（B6 现在最主要的错）**。after5 里仓库那一步 Python 失败，报告写出"苏州中心仓 12/21"，这个仓库和数字在任何一步结果里都没有；after4 里报告把合计硬解释成"上期销售为 0"。`NumberTrace`（06）只在开放接口上跑，流式接口也要跑：报告里每个数字和实体名必须能在前面某一步的结果里找到，找不到就删掉或标"数据缺失"。这是下一步最该做的。
- "销售额"术语（排除关闭订单）和归因模板第 1 步（要看关闭订单）会打架：after5 的第 1 步仍然加了 `NOT IN ('CANCELLED','CLOSED')`，所以看不到 23 张缺货关单，只能靠退款表间接看到。模板要显式覆盖术语里的过滤条件。
- 语义校验的误驳回（B4 那轮说"没有 name 字段"）。字段存不存在是可以确定性检查的（SQL 守卫已经解析出列），应该在采纳驳回前先用 schema 核对，核对不上就忽略这条驳回。根上的原因是列召回给 `wms_warehouse` 的列不全。
- B6 一次要 10 分钟以上（11 次 SQL，多数是语义校验驳回），归因模板第 1 步要求"含关闭订单"，但"销售额"术语要求排除关闭订单，模型两边打架；模板第 1 步应该改成直接按状态分组、不加状态过滤（现在的写法就是这个意思，但模型仍会先加过滤）。
- 关联路径补齐只看外键图的最短路径。两张表之间有多条等长路径时（例如经订单还是经售后单），选的是外键登记顺序里的第一条，不一定是业务上对的那条。
