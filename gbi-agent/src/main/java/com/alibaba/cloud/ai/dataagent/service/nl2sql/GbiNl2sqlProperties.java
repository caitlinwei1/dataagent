package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fixes added after the step-7 dev error analysis ({@code gbi.nl2sql.*}); switchable so the eval can measure each.
 */
@Data
@Component
@ConfigurationProperties(prefix = "gbi.nl2sql")
public class GbiNl2sqlProperties {

	/** Calendar-exact half-open ranges for the time expressions of the question, added to the evidence. */
	private boolean timeHints = true;

	/** Concrete repair instruction appended to execution errors the model keeps repeating (invalid dates). */
	private boolean repairHints = true;

	/**
	 * B6: after this many failed SQL generations in one step (or the same failing SQL twice in a row), go back to the
	 * planner and rewrite the step instead of retrying the same instruction until the global limit. 0 = off.
	 */
	private int replanAfterSqlFailures = 4;

	/** Planner rewrites a failing SQL step may trigger per question (shares PLAN_REPAIR_COUNT with plan repair). */
	private int maxSqlReplans = 1;

	/**
	 * B5: semantic-check rejections allowed per SQL step. After that the SQL goes to the guard and the database, which
	 * are deterministic; the judge's last reason is kept in the logs. 0 = unbounded (upstream behaviour).
	 */
	private int maxSemanticRejects = 2;

	/**
	 * B4 / B6: right after fine selection, load the tables the model selected that the recall missed and the bridge
	 * tables on the shortest foreign-key path between selected tables, so the plan and SQL never need a join the
	 * schema cannot express.
	 */
	private boolean joinPathCompletion = true;

	/**
	 * B5 / B6: business terms (metrics, dimensions) whose name or synonym appears literally in the question are always
	 * in the evidence, ahead of the vector hits. Vector top-k alone let enum values ("2025双11", shop names) push the
	 * metric definition out.
	 */
	private boolean literalTermEvidence = true;

	/**
	 * B6: words that make a question an attribution ("why did it drop") question. Such questions get the fixed
	 * diagnostic tables below added to the schema and the attribution template appended to the planner input, instead
	 * of letting the planner pick dimensions freely (measured: it grouped by columns no SQL step returned). Empty = off.
	 */
	private java.util.List<String> attributionKeywords = new java.util.ArrayList<>(
			java.util.List.of("原因", "为什么", "归因", "怎么回事", "什么导致"));

	/** Diagnostic tables every attribution question may need; joined in through the foreign-key path completion. */
	private java.util.List<String> attributionTables = new java.util.ArrayList<>(
			java.util.List.of("trd_refund", "wms_stockout_record", "wms_warehouse"));

	/** Planner guidance for attribution questions: which breakdowns, in which order, with which joins. */
	private String attributionGuide = String.join("\n", "【归因模板】这是归因类问题，计划按下面的固定顺序拆解，不要自由发挥维度：",
			"1. SQL：用和问题相同的口径与过滤（品牌、大区等），输出三列分组：period（CASE WHEN 按支付时间分成 '本期'/'上期'）、order_status，以及每组订单数和金额（必须同时按 period 和 order_status 分组，不能只按状态）。这一步只要求 paid_at 非空，不要加 order_status 过滤（否则看不到支付后被关闭/取消的订单），看下跌是少下单还是支付后被关单；",
			"2. SQL：同样范围的订单的退款，经 trd_refund.order_id = trd_order.id 关联，按 本期/上期 × trd_refund.reason_code 汇总退款单数和金额；",
			"3. SQL：如果第 2 步里某个原因（例如 STOCKOUT 缺货）明显上升，查本期这些订单在 wms_stockout_record 里的记录（经 wms_stockout_record.order_id = trd_order.id），按仓库（wms_warehouse.name）汇总缺货次数和缺货数量；",
			"4. Python：只用前面 SQL 结果里已有的列做对比计算（环比、占比），不要引用 SQL 没有输出的字段；",
			"5. 报告：先给口径和环比数字，再按贡献大小说原因，数字必须来自前面的结果。");

	public boolean isAttributionQuestion(String question) {
		return question != null && attributionKeywords.stream().anyMatch(question::contains);
	}

	/** B4: on a table-selection error, add the foreign-key neighbours of the SQL's tables to the schema once. */
	private boolean schemaExpandOnTableError = true;

	/**
	 * B7: tables loaded T+1 (or later) and their date column. An empty result over one of them triggers a
	 * MAX(date) lookup and an explicit "data up to X" note instead of a silent empty table.
	 */
	private java.util.Map<String, String> freshnessColumns = new java.util.LinkedHashMap<>(java.util.Map.of(
			"mkt_ad_spend", "stat_date", "mkt_traffic_daily", "stat_date", "trd_daily_shop_sales", "stat_date",
			"trd_daily_sku_sales", "stat_date"));

	/**
	 * Semantic layer: the metric contracts file (definitions, SQL rules, brand / role variants, summary-table
	 * reconciliation). Empty = off.
	 */
	private String metricContracts = "classpath:gbi/metric-contracts.yml";

	/** Check each SQL against the rules of the metrics in the question before it runs. */
	private boolean metricCheck = true;

	/** Metric-rule rejections per SQL step; after that the SQL runs and the violation is noted in the report. */
	private int maxMetricRejects = 2;

	/** Re-run SQL that read a summary table against the detail table and note disagreement / closed-order share. */
	private boolean reconcile = true;

	/** Report: evidence ledger in the prompt, trace numbers / names after writing, one rewrite, footer. */
	private boolean reportLedger = true;

}
