# Step 2 冒烟：原版 b297203 接入 86 表

- 智能体 5「电商经营分析」，数据源为 MySQL `gbi_ecommerce`（只读账号 `gbi_reader`），86 表全选，126 条逻辑外键、312 条业务知识、25 条语义模型已导入。
- Schema 初始化：1,232 个文档（86 表 + 1,146 列）逐条 embedding，23:10:39 → 23:23:10，约 12.5 分钟。
- 问题「2026年10月华东大区每个店铺的GMV是多少，按GMV从高到低排序」（nl2sqlOnly，`smoke_nl2sql.sse`）：
  - SchemaRecallNode 召回的 10 张表是 trd_presale_order, trd_aftersale, oms_fulfillment_order, trd_refund, fin_platform_fee, dim_platform, prd_label, trd_order_invoice, trd_payment, trd_aftersale_reason，**与问题无关**，缺 dim_region、trd_daily_shop_sales。这印证了原版 `SchemaServiceImpl.getTableDocumentsByDatasource` 只按过滤条件取前 10 个文档、不使用问题文本。
  - 可行性评估因此判为「需要澄清」，流程终止，没有生成 SQL。
- 这个现象作为第 3 步 Recall@5 baseline 的定性证据；数字以 180 题实测为准。
