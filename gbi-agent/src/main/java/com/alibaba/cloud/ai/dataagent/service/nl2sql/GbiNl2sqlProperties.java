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

	/** B4: on a table-selection error, add the foreign-key neighbours of the SQL's tables to the schema once. */
	private boolean schemaExpandOnTableError = true;

	/**
	 * B7: tables loaded T+1 (or later) and their date column. An empty result over one of them triggers a
	 * MAX(date) lookup and an explicit "data up to X" note instead of a silent empty table.
	 */
	private java.util.Map<String, String> freshnessColumns = new java.util.LinkedHashMap<>(java.util.Map.of(
			"mkt_ad_spend", "stat_date", "mkt_traffic_daily", "stat_date", "trd_daily_shop_sales", "stat_date",
			"trd_daily_sku_sales", "stat_date"));

}
