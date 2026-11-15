"""86-table schema for the 电商经营分析 scenario (single source of truth).

Column spec: "name type 中文注释 [->table.col]"
Types: id=BIGINT PK auto, b=BIGINT, i=INT, t=TINYINT, s=VARCHAR(64), l=VARCHAR(255),
       d=DECIMAL(14,2), r=DECIMAL(12,6), dt=DATE, ts=DATETIME
`ablate=True` tables lose every table/column comment in the "partial" DDL variant
(注释消融实验). Roughly 40% of tables, spread across domains, including look-alike tables.
"""

TABLES = []


def T(name, comment, cols, ablate=False, domain=None):
    TABLES.append(dict(name=name, comment=comment, cols=cols, ablate=ablate, domain=domain))


# ---------------------------------------------------------------- 交易 (25)
D = "交易"
T("trd_order", "订单主表：一笔用户下单一行，金额字段口径见业务知识", [
    "id id 订单ID",
    "order_no s 订单编号",
    "member_id b 下单会员ID ->usr_member.id",
    "shop_id b 店铺ID ->dim_shop.id",
    "source_id i 订单来源ID ->trd_order_source.id",
    "order_status s 订单状态：CREATED待支付/PAID已支付/SHIPPED已发货/COMPLETED已完成/CANCELLED已取消/CLOSED已关闭",
    "total_amount d 商品总额（原价合计，不含运费，未扣优惠）",
    "discount_amount d 优惠总额（券+活动+会员折扣）",
    "freight_amount d 运费",
    "pay_amount d 实付金额=商品总额-优惠总额+运费",
    "currency_code s 币种 ->dim_currency.code",
    "created_at ts 下单时间",
    "paid_at ts 支付时间，未支付为空",
    "completed_at ts 确认收货时间",
    "is_presale t 是否预售订单 1是0否",
], domain=D)
T("trd_order_item", "订单明细：订单内每个SKU一行", [
    "id id 明细ID",
    "order_id b 订单ID ->trd_order.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 购买件数",
    "unit_price d 成交单价（原价）",
    "item_amount d 明细原价金额=单价*件数",
    "discount_share d 分摊到该明细的优惠金额",
    "pay_share d 分摊到该明细的实付金额（不含运费）",
], domain=D)
T("trd_order_status_log", "订单状态变更流水", [
    "id id 流水ID",
    "order_id b 订单ID ->trd_order.id",
    "from_status s 变更前状态",
    "to_status s 变更后状态",
    "changed_at ts 变更时间",
    "operator s 操作方：USER/SYSTEM/CS",
], ablate=True, domain=D)
T("trd_order_address", "订单收货地址快照", [
    "id id 地址ID",
    "order_id b 订单ID ->trd_order.id",
    "province s 收货省份",
    "city s 收货城市",
    "region_id b 收货地所属区域ID ->dim_region.id",
    "receiver_name s 收货人（脱敏）",
], domain=D)
T("trd_order_promotion", "订单优惠分摊明细：每种优惠一行", [
    "id id 分摊ID",
    "order_id b 订单ID ->trd_order.id",
    "promo_type s 优惠类型：COUPON/ACTIVITY/MEMBER",
    "coupon_id b 使用的优惠券ID ->mkt_coupon.id",
    "activity_id b 参与的活动ID ->mkt_activity.id",
    "promo_amount d 该项优惠金额",
], ablate=True, domain=D)
T("trd_payment", "支付单：一笔订单可能多次支付尝试", [
    "id id 支付单ID",
    "order_id b 订单ID ->trd_order.id",
    "pay_channel s 支付渠道：ALIPAY/WECHAT/CARD/GIFT_CARD",
    "pay_status s 支付状态：SUCCESS/FAILED/CLOSED",
    "amount d 支付金额",
    "paid_at ts 支付完成时间",
], domain=D)
T("trd_payment_channel_fee", "支付渠道手续费", [
    "id id 记录ID",
    "payment_id b 支付单ID ->trd_payment.id",
    "fee_rate r 费率",
    "fee_amount d 手续费金额",
], ablate=True, domain=D)
T("trd_refund", "退款单：仅退款或退货退款", [
    "id id 退款单ID",
    "order_id b 订单ID ->trd_order.id",
    "aftersale_id b 关联售后单ID ->trd_aftersale.id",
    "refund_type s 类型：REFUND_ONLY仅退款/RETURN_REFUND退货退款",
    "reason_code s 退款原因编码 ->trd_aftersale_reason.code",
    "refund_status s 退款状态：APPLIED/APPROVED/REJECTED/SUCCESS",
    "refund_amount d 退款金额",
    "applied_at ts 申请时间",
    "refunded_at ts 退款成功时间",
], domain=D)
T("trd_refund_item", "退款明细：退款涉及的SKU", [
    "id id 明细ID",
    "refund_id b 退款单ID ->trd_refund.id",
    "order_item_id b 订单明细ID ->trd_order_item.id",
    "quantity i 退款件数",
    "amount d 该SKU退款金额",
], ablate=True, domain=D)
T("trd_aftersale", "售后单", [
    "id id 售后单ID",
    "order_id b 订单ID ->trd_order.id",
    "aftersale_type s 类型：REFUND/RETURN/EXCHANGE/REPAIR",
    "reason_code s 原因编码 ->trd_aftersale_reason.code",
    "status s 状态：OPEN/PROCESSING/CLOSED",
    "created_at ts 发起时间",
    "closed_at ts 关闭时间",
], domain=D)
T("trd_aftersale_reason", "售后/退款原因维表", [
    "id id 原因ID",
    "code s 原因编码，如STOCKOUT缺货/QUALITY质量问题/LATE_DELIVERY物流超时/NO_REASON七天无理由",
    "name s 原因名称",
    "responsibility s 责任方：MERCHANT/LOGISTICS/CUSTOMER",
], domain=D)
T("trd_cart", "购物车加购记录", [
    "id id 记录ID",
    "member_id b 会员ID ->usr_member.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 加购件数",
    "added_at ts 加购时间",
    "converted t 是否已下单 1是0否",
], ablate=True, domain=D)
T("trd_order_invoice", "订单发票", [
    "id id 发票ID",
    "order_id b 订单ID ->trd_order.id",
    "invoice_type s 发票类型：NORMAL/VAT",
    "invoice_amount d 开票金额",
    "issued_at ts 开票时间",
], ablate=True, domain=D)
T("trd_order_review", "商品评价", [
    "id id 评价ID",
    "order_item_id b 订单明细ID ->trd_order_item.id",
    "member_id b 会员ID ->usr_member.id",
    "score t 评分1-5",
    "has_image t 是否带图",
    "created_at ts 评价时间",
], domain=D)
T("trd_order_split", "拆单关系：父订单拆成多个子订单发货", [
    "id id 记录ID",
    "parent_order_id b 父订单ID ->trd_order.id",
    "child_order_id b 子订单ID ->trd_order.id",
    "split_reason s 拆单原因：MULTI_WAREHOUSE/PRESALE/OVERSIZE",
], ablate=True, domain=D)
T("trd_presale_order", "预售订单定金与尾款", [
    "id id 记录ID",
    "order_id b 订单ID ->trd_order.id",
    "deposit_amount d 定金",
    "balance_amount d 尾款",
    "balance_paid_at ts 尾款支付时间",
], ablate=True, domain=D)
T("trd_order_source", "订单来源维表", [
    "id id 来源ID",
    "code s 来源编码：APP/MINI_PROGRAM/WEB/LIVE/OFFLINE",
    "name s 来源名称",
], domain=D)
T("trd_daily_shop_sales", "店铺日销售汇总（T+1离线计算）", [
    "id id 记录ID",
    "stat_date dt 统计日期 ->dim_date.date_key",
    "shop_id b 店铺ID ->dim_shop.id",
    "gmv d GMV：当日支付订单的商品总额+运费，不扣优惠、不扣退款",
    "paid_amount d 实付金额：当日支付订单的实付合计",
    "refund_amount d 当日退款成功金额",
    "net_sales d 退款后金额=实付金额-退款金额",
    "order_cnt i 支付订单数",
    "buyer_cnt i 支付买家数",
], domain=D)
T("trd_daily_sku_sales", "SKU日销售汇总（T+1离线计算）", [
    "id id 记录ID",
    "stat_date dt 统计日期 ->dim_date.date_key",
    "sku_id b SKU ID ->prd_sku.id",
    "sales_qty i 销量件数",
    "sales_amount d 销售额（实付分摊口径）",
    "refund_qty i 退款件数",
], ablate=True, domain=D)
T("trd_order_cancel", "订单取消记录", [
    "id id 记录ID",
    "order_id b 订单ID ->trd_order.id",
    "cancel_reason s 取消原因",
    "cancel_by s 发起方：USER/SYSTEM_TIMEOUT/CS",
    "cancelled_at ts 取消时间",
], domain=D)
T("trd_complaint", "客诉工单", [
    "id id 工单ID",
    "order_id b 订单ID ->trd_order.id",
    "complaint_type s 客诉类型：LOGISTICS/QUALITY/SERVICE/PRICE",
    "severity t 严重程度1-3",
    "created_at ts 创建时间",
    "resolved_at ts 解决时间",
], ablate=True, domain=D)
T("trd_return_logistics", "退货物流：用户寄回商品的运单", [
    "id id 记录ID",
    "refund_id b 退款单ID ->trd_refund.id",
    "carrier_id b 承运商ID ->tms_carrier.id",
    "tracking_no s 退货运单号",
    "shipped_at ts 用户寄出时间",
    "received_at ts 仓库签收时间",
], domain=D)
T("trd_compensation", "赔付单：对用户的额外补偿", [
    "id id 赔付ID",
    "order_id b 订单ID ->trd_order.id",
    "comp_type s 赔付类型：CASH/COUPON/POINTS",
    "amount d 赔付金额",
    "reason_code s 原因编码 ->trd_aftersale_reason.code",
    "created_at ts 赔付时间",
], ablate=True, domain=D)
T("trd_order_tag", "订单标签", [
    "id id 记录ID",
    "order_id b 订单ID ->trd_order.id",
    "tag s 标签：FIRST_ORDER首单/BIG_PROMO大促/HIGH_RISK风险单",
], domain=D)
T("trd_gift_card_usage", "礼品卡使用记录", [
    "id id 记录ID",
    "order_id b 订单ID ->trd_order.id",
    "card_no s 礼品卡号",
    "used_amount d 抵扣金额",
    "used_at ts 使用时间",
], ablate=True, domain=D)

# ---------------------------------------------------------------- 商品 (15)
D = "商品"
T("prd_spu", "商品SPU：同款商品", [
    "id id SPU ID",
    "spu_name l 商品名称",
    "category_id b 末级类目ID ->prd_category.id",
    "brand_id b 品牌ID ->prd_brand.id",
    "series_id b 系列ID ->prd_series.id",
    "launch_date dt 首次上架日期",
], domain=D)
T("prd_sku", "商品SKU：可售卖的最小单元（规格）", [
    "id id SKU ID",
    "spu_id b SPU ID ->prd_spu.id",
    "sku_code s SKU编码",
    "spec l 规格描述，如颜色/尺码",
    "list_price d 标价",
    "status s 状态：ON_SALE在售/OFF_SALE下架",
], domain=D)
T("prd_category", "商品类目，三级树形", [
    "id id 类目ID",
    "parent_id b 父类目ID，一级类目为0 ->prd_category.id",
    "level t 层级1/2/3",
    "name s 类目名称",
], domain=D)
T("prd_brand", "品牌", [
    "id id 品牌ID",
    "name s 品牌名称",
    "is_own t 是否自有品牌",
    "country s 品牌国家",
], domain=D)
T("prd_price_history", "SKU价格变更历史", [
    "id id 记录ID",
    "sku_id b SKU ID ->prd_sku.id",
    "old_price d 原价格",
    "new_price d 新价格",
    "changed_at ts 变更时间",
], ablate=True, domain=D)
T("prd_attribute", "商品属性定义", [
    "id id 属性ID",
    "name s 属性名，如颜色/材质",
    "category_id b 适用类目ID ->prd_category.id",
], ablate=True, domain=D)
T("prd_sku_attribute", "SKU属性取值", [
    "id id 记录ID",
    "sku_id b SKU ID ->prd_sku.id",
    "attribute_id b 属性ID ->prd_attribute.id",
    "value s 属性值",
], ablate=True, domain=D)
T("prd_label", "商品标签定义", [
    "id id 标签ID",
    "name s 标签名，如新品/爆款/清仓",
], domain=D)
T("prd_sku_label", "SKU打标", [
    "id id 记录ID",
    "sku_id b SKU ID ->prd_sku.id",
    "label_id b 标签ID ->prd_label.id",
], ablate=True, domain=D)
T("prd_bundle", "组合套装", [
    "id id 套装ID",
    "name l 套装名称",
    "bundle_price d 套装价",
], ablate=True, domain=D)
T("prd_bundle_item", "套装包含的SKU", [
    "id id 记录ID",
    "bundle_id b 套装ID ->prd_bundle.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 件数",
], ablate=True, domain=D)
T("prd_listing_log", "上下架记录", [
    "id id 记录ID",
    "sku_id b SKU ID ->prd_sku.id",
    "action s 动作：ON/OFF",
    "acted_at ts 操作时间",
], domain=D)
T("prd_sku_cost", "SKU采购成本（按月）", [
    "id id 记录ID",
    "sku_id b SKU ID ->prd_sku.id",
    "month_key s 月份YYYY-MM",
    "unit_cost d 单位成本",
], domain=D)
T("prd_series", "商品系列", [
    "id id 系列ID",
    "brand_id b 品牌ID ->prd_brand.id",
    "name s 系列名称",
], ablate=True, domain=D)
T("prd_sku_barcode", "SKU条码", [
    "id id 记录ID",
    "sku_id b SKU ID ->prd_sku.id",
    "barcode s 条码",
], ablate=True, domain=D)

# ---------------------------------------------------------------- 用户与营销 (15)
D = "用户与营销"
T("usr_member", "会员", [
    "id id 会员ID",
    "register_at ts 注册时间",
    "gender s 性别：M/F/U",
    "birth_year i 出生年份",
    "level_id b 当前会员等级ID ->usr_member_level.id",
    "register_channel_id b 注册渠道ID ->mkt_channel.id",
    "province s 常住省份",
    "region_id b 常住区域ID ->dim_region.id",
], domain=D)
T("usr_member_level", "会员等级", [
    "id id 等级ID",
    "name s 等级名称：普通/银卡/金卡/黑金",
    "min_points i 所需积分",
    "discount_rate r 会员折扣率",
], domain=D)
T("usr_member_level_log", "会员等级变更记录", [
    "id id 记录ID",
    "member_id b 会员ID ->usr_member.id",
    "from_level_id b 原等级 ->usr_member_level.id",
    "to_level_id b 新等级 ->usr_member_level.id",
    "changed_at ts 变更时间",
], ablate=True, domain=D)
T("usr_address", "会员收货地址簿", [
    "id id 地址ID",
    "member_id b 会员ID ->usr_member.id",
    "province s 省份",
    "city s 城市",
    "is_default t 是否默认地址",
], ablate=True, domain=D)
T("usr_points_log", "积分流水", [
    "id id 流水ID",
    "member_id b 会员ID ->usr_member.id",
    "change_points i 积分变动，正为获得负为消耗",
    "biz_type s 业务类型：ORDER/REFUND/SIGN_IN/REDEEM",
    "created_at ts 时间",
], ablate=True, domain=D)
T("usr_member_tag", "会员标签", [
    "id id 记录ID",
    "member_id b 会员ID ->usr_member.id",
    "tag s 标签：HIGH_VALUE高价值/CHURN_RISK流失风险/NEW新客",
], domain=D)
T("mkt_coupon_template", "优惠券模板", [
    "id id 模板ID",
    "name l 券名称",
    "coupon_type s 类型：FULL_REDUCTION满减/DISCOUNT折扣/NO_THRESHOLD无门槛",
    "threshold_amount d 使用门槛金额",
    "face_value d 面额或折扣",
    "activity_id b 所属活动ID ->mkt_activity.id",
], domain=D)
T("mkt_coupon", "用户领取的优惠券", [
    "id id 券ID",
    "template_id b 模板ID ->mkt_coupon_template.id",
    "member_id b 会员ID ->usr_member.id",
    "status s 状态：UNUSED/USED/EXPIRED",
    "received_at ts 领取时间",
    "used_at ts 使用时间",
], domain=D)
T("mkt_activity", "营销活动", [
    "id id 活动ID",
    "name l 活动名称，如双11/618/年货节",
    "activity_type s 类型：BIG_PROMO大促/FLASH_SALE秒杀/NEW_USER拉新",
    "start_at ts 开始时间",
    "end_at ts 结束时间",
    "budget d 活动预算",
], domain=D)
T("mkt_activity_sku", "活动商品及活动价", [
    "id id 记录ID",
    "activity_id b 活动ID ->mkt_activity.id",
    "sku_id b SKU ID ->prd_sku.id",
    "activity_price d 活动价",
], ablate=True, domain=D)
T("mkt_channel", "流量/投放渠道", [
    "id id 渠道ID",
    "code s 渠道编码：DOUYIN/XHS/BAIDU/WECHAT/ORGANIC",
    "name s 渠道名称",
    "is_paid t 是否付费渠道",
], domain=D)
T("mkt_ad_spend", "广告投放花费（按日按渠道）", [
    "id id 记录ID",
    "stat_date dt 日期 ->dim_date.date_key",
    "channel_id b 渠道ID ->mkt_channel.id",
    "spend d 花费",
    "impressions b 曝光",
    "clicks b 点击",
], domain=D)
T("mkt_traffic_daily", "店铺流量日表", [
    "id id 记录ID",
    "stat_date dt 日期 ->dim_date.date_key",
    "shop_id b 店铺ID ->dim_shop.id",
    "channel_id b 渠道ID ->mkt_channel.id",
    "pv b 浏览量",
    "uv b 访客数",
], domain=D)
T("mkt_live_session", "直播场次", [
    "id id 场次ID",
    "shop_id b 店铺ID ->dim_shop.id",
    "anchor s 主播",
    "start_at ts 开播时间",
    "duration_min i 时长（分钟）",
    "viewers i 观看人数",
], ablate=True, domain=D)
T("mkt_member_attribution", "会员首单归因渠道", [
    "id id 记录ID",
    "member_id b 会员ID ->usr_member.id",
    "first_order_id b 首单订单ID ->trd_order.id",
    "channel_id b 归因渠道ID ->mkt_channel.id",
], ablate=True, domain=D)

# ---------------------------------------------------------------- 库存仓储与履约 (20)
D = "库存仓储与履约"
T("scm_supplier", "供应商", [
    "id id 供应商ID",
    "name l 供应商名称",
    "province s 所在省份",
    "lead_days i 平均交货周期（天）",
], domain=D)
T("scm_purchase_order", "采购单", [
    "id id 采购单ID",
    "supplier_id b 供应商ID ->scm_supplier.id",
    "warehouse_id b 收货仓ID ->wms_warehouse.id",
    "status s 状态：CREATED/PARTIAL_RECEIVED/RECEIVED/CANCELLED",
    "created_at ts 下单时间",
    "expected_at dt 预计到货日期",
], domain=D)
T("scm_purchase_order_item", "采购单明细", [
    "id id 明细ID",
    "po_id b 采购单ID ->scm_purchase_order.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 采购数量",
    "unit_cost d 采购单价",
], ablate=True, domain=D)
T("wms_warehouse", "仓库", [
    "id id 仓库ID",
    "name s 仓库名称",
    "warehouse_type s 类型：CENTRAL中心仓/REGIONAL区域仓/FRONT前置仓",
    "region_id b 所在区域ID ->dim_region.id",
], domain=D)
T("wms_inbound", "入库单（采购到货/退货入库/调拨入库）", [
    "id id 入库单ID",
    "warehouse_id b 仓库ID ->wms_warehouse.id",
    "inbound_type s 类型：PURCHASE/RETURN/TRANSFER",
    "ref_id b 来源单据ID（采购单/退款单/调拨单）",
    "received_at ts 入库时间",
], domain=D)
T("wms_inbound_item", "入库明细", [
    "id id 明细ID",
    "inbound_id b 入库单ID ->wms_inbound.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 入库数量",
], ablate=True, domain=D)
T("wms_inventory_snapshot", "库存日快照：每日24点各仓各SKU库存", [
    "id id 记录ID",
    "snapshot_date dt 快照日期 ->dim_date.date_key",
    "warehouse_id b 仓库ID ->wms_warehouse.id",
    "sku_id b SKU ID ->prd_sku.id",
    "on_hand_qty i 在库数量",
    "locked_qty i 已占用数量",
    "available_qty i 可用数量=在库-占用",
], domain=D)
T("wms_inventory", "实时库存：当前时刻各仓各SKU库存", [
    "id id 记录ID",
    "warehouse_id b 仓库ID ->wms_warehouse.id",
    "sku_id b SKU ID ->prd_sku.id",
    "on_hand_qty i 在库数量",
    "locked_qty i 已占用数量",
    "safety_stock i 安全库存",
    "updated_at ts 更新时间",
], ablate=True, domain=D)
T("wms_transfer", "调拨单：仓间移库", [
    "id id 调拨单ID",
    "from_warehouse_id b 调出仓 ->wms_warehouse.id",
    "to_warehouse_id b 调入仓 ->wms_warehouse.id",
    "status s 状态：CREATED/IN_TRANSIT/DONE",
    "created_at ts 创建时间",
    "done_at ts 完成时间",
], domain=D)
T("wms_transfer_item", "调拨明细", [
    "id id 明细ID",
    "transfer_id b 调拨单ID ->wms_transfer.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 数量",
], ablate=True, domain=D)
T("wms_outbound", "出库单：仓库拣货出库", [
    "id id 出库单ID",
    "fulfillment_id b 履约单ID ->oms_fulfillment_order.id",
    "warehouse_id b 出库仓ID ->wms_warehouse.id",
    "picked_at ts 拣货完成时间",
    "outbound_at ts 出库时间",
], domain=D)
T("wms_outbound_item", "出库明细", [
    "id id 明细ID",
    "outbound_id b 出库单ID ->wms_outbound.id",
    "sku_id b SKU ID ->prd_sku.id",
    "quantity i 出库数量",
], ablate=True, domain=D)
T("wms_stockout_record", "缺货记录：订单分配库存失败", [
    "id id 记录ID",
    "order_id b 订单ID ->trd_order.id",
    "sku_id b 缺货SKU ID ->prd_sku.id",
    "warehouse_id b 尝试分配的仓库ID ->wms_warehouse.id",
    "shortage_qty i 缺货数量",
    "detected_at ts 发现时间",
], domain=D)
T("wms_stocktake", "盘点单", [
    "id id 盘点单ID",
    "warehouse_id b 仓库ID ->wms_warehouse.id",
    "stocktake_date dt 盘点日期",
], ablate=True, domain=D)
T("wms_stocktake_item", "盘点差异明细", [
    "id id 明细ID",
    "stocktake_id b 盘点单ID ->wms_stocktake.id",
    "sku_id b SKU ID ->prd_sku.id",
    "book_qty i 账面数量",
    "actual_qty i 实盘数量",
], ablate=True, domain=D)
T("tms_carrier", "承运商（快递公司）", [
    "id id 承运商ID",
    "name s 名称，如顺丰/京东物流/中通",
    "service_level s 服务等级：EXPRESS/STANDARD",
], domain=D)
T("tms_shipment", "发货运单：商家发给用户的快递", [
    "id id 运单ID",
    "fulfillment_id b 履约单ID ->oms_fulfillment_order.id",
    "carrier_id b 承运商ID ->tms_carrier.id",
    "tracking_no s 运单号",
    "shipped_at ts 揽收时间",
    "delivered_at ts 签收时间",
], domain=D)
T("tms_shipment_track", "物流轨迹节点", [
    "id id 记录ID",
    "shipment_id b 运单ID ->tms_shipment.id",
    "node s 节点：PICKED_UP/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION",
    "city s 所在城市",
    "event_at ts 时间",
], ablate=True, domain=D)
T("tms_delivery_sla", "配送时效承诺：按仓库与收货区域", [
    "id id 记录ID",
    "warehouse_id b 发货仓ID ->wms_warehouse.id",
    "region_id b 收货区域ID ->dim_region.id",
    "promised_hours i 承诺送达时长（小时）",
], domain=D)
T("oms_fulfillment_order", "履约单：订单分配到仓库后的履约任务，一个订单可拆多个", [
    "id id 履约单ID",
    "order_id b 订单ID ->trd_order.id",
    "warehouse_id b 分配仓库ID ->wms_warehouse.id",
    "status s 状态：ALLOCATED/PICKING/SHIPPED/DELIVERED/CANCELLED",
    "allocated_at ts 分配时间",
    "promised_at ts 承诺送达时间",
], domain=D)

# ---------------------------------------------------------------- 财务结算与维表 (11)
D = "财务结算与维表"
T("fin_settlement", "店铺结算单（按月）", [
    "id id 结算单ID",
    "shop_id b 店铺ID ->dim_shop.id",
    "month_key s 结算月份YYYY-MM",
    "gross_amount d 应结算总额",
    "commission d 平台佣金",
    "net_amount d 实际结算金额",
    "settled_at ts 结算时间",
], domain=D)
T("fin_settlement_item", "结算明细：按订单", [
    "id id 明细ID",
    "settlement_id b 结算单ID ->fin_settlement.id",
    "order_id b 订单ID ->trd_order.id",
    "amount d 结算金额",
], ablate=True, domain=D)
T("fin_platform_fee", "平台费率配置（按平台按类目）", [
    "id id 记录ID",
    "platform_id b 平台ID ->dim_platform.id",
    "category_id b 一级类目ID ->prd_category.id",
    "commission_rate r 佣金率",
], ablate=True, domain=D)
T("fin_sales_target", "月度销售目标（按店铺）", [
    "id id 记录ID",
    "shop_id b 店铺ID ->dim_shop.id",
    "month_key s 月份YYYY-MM",
    "target_amount d 目标实付金额",
], domain=D)
T("fin_exchange_rate", "汇率（按日）", [
    "id id 记录ID",
    "rate_date dt 日期",
    "currency_code s 币种 ->dim_currency.code",
    "rate_to_cny r 兑人民币汇率",
], domain=D)
T("dim_region", "区域维表：大区-省", [
    "id id 区域ID",
    "big_region s 大区：华东/华北/华南/华中/西南/西北/东北",
    "province s 省份",
], domain=D)
T("dim_shop", "店铺维表", [
    "id id 店铺ID",
    "name s 店铺名称",
    "platform_id b 所属平台ID ->dim_platform.id",
    "region_id b 运营归属区域ID ->dim_region.id",
    "opened_at dt 开店日期",
], domain=D)
T("dim_store", "线下门店维表", [
    "id id 门店ID",
    "name s 门店名称",
    "shop_id b 归属店铺ID ->dim_shop.id",
    "region_id b 所在区域ID ->dim_region.id",
], ablate=True, domain=D)
T("dim_date", "日期维表", [
    "date_key dt 日期（主键）",
    "year i 年",
    "quarter t 季度",
    "month t 月",
    "week_of_year t 周",
    "is_weekend t 是否周末",
    "is_holiday t 是否法定节假日",
    "promo_name s 当日所属大促名称，无则为空",
], domain=D)
T("dim_currency", "币种维表", [
    "code s 币种代码（主键），如CNY/USD/HKD",
    "name s 币种名称",
], ablate=True, domain=D)
T("dim_platform", "销售平台维表", [
    "id id 平台ID",
    "name s 平台：天猫/京东/抖音/自营商城",
], domain=D)

assert len(TABLES) == 86, len(TABLES)
