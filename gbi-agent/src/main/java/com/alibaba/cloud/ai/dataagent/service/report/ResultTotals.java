package com.alibaba.cloud.ai.dataagent.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-computes column totals of a step result so the report writer quotes them instead of adding numbers up itself.
 *
 * <p>
 * Seen in the report sample (R07, two runs): both SQL results were right, but the LLM summed six refund amounts per
 * month in its head, got both months wrong and reported "+0.74%, stable" for a real -50% drop.
 */
public final class ResultTotals {

	private static final ObjectMapper M = new ObjectMapper();

	private ResultTotals() {
	}

	/** Returns a Markdown line with the row count and the total of each numeric column, or "" if not a result set. */
	public static String describe(String resultJson) {
		try {
			JsonNode root = M.readTree(resultJson);
			JsonNode rs = root.has("resultSet") ? root.path("resultSet") : root;
			JsonNode data = rs.path("data");
			if (!rs.has("column") || !data.isArray() || data.size() < 2) {
				return "";
			}
			List<String> parts = new ArrayList<>();
			for (JsonNode c : rs.path("column")) {
				String col = c.asText();
				BigDecimal sum = BigDecimal.ZERO;
				boolean numeric = true;
				for (JsonNode row : data) {
					String v = row.path(col).asText("");
					try {
						sum = sum.add(new BigDecimal(v.replace(",", "")));
					}
					catch (NumberFormatException e) {
						numeric = false;
						break;
					}
				}
				if (numeric && !col.toLowerCase().endsWith("id")) {
					parts.add(col + " 合计 = " + sum.setScale(2, RoundingMode.HALF_UP).toPlainString());
				}
			}
			if (parts.isEmpty()) {
				return "";
			}
			return "**系统计算的合计**（共 " + data.size() + " 行；报告中的合计与差值请直接引用这些值，不要自行相加）: "
					+ String.join("；", parts) + "\n";
		}
		catch (Exception e) {
			return "";
		}
	}

}
