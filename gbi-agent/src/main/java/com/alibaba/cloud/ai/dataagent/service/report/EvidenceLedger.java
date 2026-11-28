package com.alibaba.cloud.ai.dataagent.service.report;

import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The evidence ledger of one multi-step answer: what each step actually produced, and which numbers / names in the
 * report can be traced back to it.
 *
 * <p>
 * Before: when a step failed (Python degraded after 5 rewrites) or returned no rows, the report writer still had to
 * "explain the cause" and filled the gap itself: B6 after5 named a warehouse ("苏州中心仓 12 单，57.1%") that no
 * query had returned. The ledger makes three things explicit and deterministic: (1) each step is 已完成 / 未定（零行）
 * / 受阻（失败或降级） / 未执行, and the writer is told what it may say about each; (2) after writing, numbers, counts
 * and warehouse / shop names are traced against the step results; untraced ones trigger one rewrite; (3) whatever is
 * still untraced is listed under the report instead of being silently published.
 */
public final class EvidenceLedger {

	public enum Status {

		DONE("已完成"), FIXED("已完成（口径条件由系统自动补齐）"), UNVERIFIED("口径待确认"), EMPTY("未定（零行）"),
		BLOCKED("受阻"), MISSING("未执行");

		private final String label;

		Status(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public record Step(int step, String tool, Status status, String detail) {
	}

	/** Names a report may only use if a query returned them: warehouses and shops. */
	private static final Pattern ENTITY = Pattern.compile(
			"[\\u4e00-\\u9fa5]{2,8}?(?:中心仓|区域仓|前置仓|旗舰店|专卖店)");

	/** Small counts written as "12 单", "7 个仓库": checked against cells, totals and row counts. */
	private static final Pattern COUNT = Pattern.compile("(\\d{1,4})\\s*(?:单|件|笔|家店|家|个仓库|个仓|个店铺|个店|个SKU|个 SKU)");

	private EvidenceLedger() {
	}

	/**
	 * @param tools tool of each plan step, in order (SQL / PYTHON / REPORT ...)
	 * @param results the per-step results the graph accumulated ("step_1" -> result set JSON or analysis text)
	 */
	public static List<Step> build(List<String> tools, Map<String, String> results) {
		List<Step> out = new ArrayList<>();
		for (int i = 0; i < tools.size(); i++) {
			String tool = tools.get(i);
			if (tool != null && tool.toUpperCase().contains("REPORT")) {
				continue;
			}
			String r = results.get("step_" + (i + 1));
			out.add(classify(i + 1, tool, r));
		}
		return out;
	}

	static Step classify(int step, String tool, String r) {
		if (r == null || r.isBlank()) {
			return new Step(step, tool, Status.MISSING, "这一步没有执行到");
		}
		String t = r.strip();
		if (t.startsWith("【降级】") || t.contains("\"degraded\":true")) {
			return new Step(step, tool, Status.BLOCKED, firstLine(t));
		}
		if (t.startsWith("{")) {
			try {
				JsonNode n = JsonUtil.getObjectMapper().readTree(t);
				JsonNode data = n.path("data");
				if (data.isArray()) {
					if (data.isEmpty()) {
						String note = n.path("note").asText("");
						return new Step(step, tool, Status.EMPTY, note.isBlank() ? "查询返回 0 行" : note);
					}
					String note = n.path("note").asText("");
					if (note.contains("口径待确认")) {
						return new Step(step, tool, Status.UNVERIFIED, note.replace("\n", " "));
					}
					if (note.contains("自动补上条件")) {
						return new Step(step, tool, Status.FIXED, data.size() + " 行");
					}
					return new Step(step, tool, Status.DONE, data.size() + " 行");
				}
			}
			catch (Exception ignored) {
			}
		}
		return new Step(step, tool, Status.DONE, "分析结论");
	}

	/** The block appended to the report writer's input. */
	public static String promptBlock(List<Step> ledger, List<String> contracts, List<String> reconNotes) {
		StringBuilder sb = new StringBuilder("\n## 证据台账（系统生成，写报告必须遵守）\n");
		for (Step s : ledger) {
			sb.append("- 第 ").append(s.step()).append(" 步（").append(s.tool()).append("）：").append(s.status().label());
			if (s.status() != Status.DONE && s.status() != Status.FIXED) {
				sb.append("，").append(s.detail());
			}
			sb.append("\n");
		}
		if (!contracts.isEmpty()) {
			sb.append("口径：\n");
			contracts.forEach(c -> sb.append("- ").append(c).append("\n"));
		}
		if (!reconNotes.isEmpty()) {
			sb.append("对账：\n");
			reconNotes.forEach(c -> sb.append("- ").append(c).append("\n"));
		}
		sb.append("""
				规则：
				1. 报告里的每个数字、仓库名、店铺名都必须能在上面的执行结果里找到；算出来的比例写清楚用哪两个数算的。
				2. 状态为“受阻”或“未执行”的步骤：写“该步受阻（原因）”，不得给出这一步本应得到的任何数字、名称或结论，也不要猜。
				3. 状态为“未定（零行）”的步骤：写“查询无数据，结论未定”，不得写成 0 或“没有发生”。
				3a. 状态为“口径待确认”的步骤：可以引用数字，但必须注明“口径待确认”及原因，不能当作确认的结论。
				4. 结论分三类写：已确认（有数据）、待确认（数据不足）、受阻（步骤失败）。
				""");
		return sb.toString();
	}

	/** What in the report has no source: numbers / amounts, small counts, warehouse and shop names. */
	public static List<String> untraced(String report, String question, List<List<Map<String, String>>> resultSets,
			List<String> resultTexts) {
		Set<String> out = new LinkedHashSet<>();
		List<List<Map<String, String>>> sets = new ArrayList<>(resultSets);
		// numbers printed by analysis steps (Python output summarised by the analyzer) are evidence too
		for (String t : resultTexts) {
			List<Map<String, String>> rows = new ArrayList<>();
			Matcher m = Pattern.compile("-?\\d[\\d,]*\\.?\\d*").matcher(t);
			int i = 0;
			while (m.find()) {
				Map<String, String> row = new LinkedHashMap<>();
				row.put("v" + i++, m.group());
				rows.add(row);
			}
			if (!rows.isEmpty()) {
				// one column per number so totals across unrelated numbers are not formed
				for (Map<String, String> r : rows) {
					sets.add(List.of(r));
				}
			}
		}
		out.addAll(NumberTrace.untraced(report, sets));
		String evidence = question + "\n" + String.join("\n", resultTexts) + "\n" + flatten(resultSets);
		Set<Double> counts = new java.util.HashSet<>(NumberTrace.candidates(sets));
		for (List<Map<String, String>> rs : resultSets) {
			counts.add((double) rs.size());
		}
		String body = report.replaceAll("(?s)```.*?```", " ");
		Matcher c = COUNT.matcher(body);
		while (c.find()) {
			double v = Double.parseDouble(c.group(1));
			if (v < 2 || v >= 100) {
				continue; // >= 100 is NumberTrace's job
			}
			boolean hit = counts.stream().anyMatch(x -> Math.abs(x - v) < 0.001);
			if (!hit) {
				out.add(c.group().strip());
			}
		}
		Matcher e = ENTITY.matcher(body);
		while (e.find()) {
			String name = e.group();
			if (name.matches(".*[各至的和与及在从到等个].*")) {
				continue; // a phrase ("下沉至华东各城市前置仓"), not a name
			}
			String core = name.length() > 4 ? name.substring(name.length() - 5) : name;
			if (!evidence.contains(name) && !evidence.contains(core)) {
				out.add(name);
			}
		}
		return new ArrayList<>(out);
	}

	/** Result sets as (column -> value) rows, from the per-step results that are result-set JSON. */
	public static List<List<Map<String, String>>> resultSets(Map<String, String> results) {
		List<List<Map<String, String>>> out = new ArrayList<>();
		for (String r : results.values()) {
			if (r == null || !r.strip().startsWith("{")) {
				continue;
			}
			try {
				JsonNode data = JsonUtil.getObjectMapper().readTree(r).path("data");
				if (!data.isArray()) {
					continue;
				}
				List<Map<String, String>> rows = new ArrayList<>();
				for (JsonNode row : data) {
					Map<String, String> m = new LinkedHashMap<>();
					row.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().asText()));
					rows.add(m);
				}
				out.add(rows);
			}
			catch (Exception ignored) {
			}
		}
		return out;
	}

	/** Per-step results that are text (analysis conclusions, degradation notes). */
	public static List<String> resultTexts(Map<String, String> results) {
		List<String> out = new ArrayList<>();
		for (String r : results.values()) {
			if (r != null && !r.strip().startsWith("{")) {
				out.add(r);
			}
		}
		return out;
	}

	/** The section printed under the report. */
	public static String footer(List<Step> ledger, List<String> contracts, List<String> reconNotes,
			List<String> untraced) {
		StringBuilder sb = new StringBuilder("\n\n---\n### 证据核对（系统生成）\n");
		for (Step s : ledger) {
			sb.append("- 第 ").append(s.step()).append(" 步 ").append(s.tool()).append("：").append(s.status().label());
			if (s.status() != Status.DONE && s.status() != Status.FIXED) {
				sb.append("（").append(s.detail()).append("）");
			}
			sb.append("\n");
		}
		contracts.forEach(c -> sb.append("- 口径：").append(c).append("\n"));
		reconNotes.forEach(c -> sb.append("- ").append(c).append("\n"));
		if (untraced.isEmpty()) {
			sb.append("- 报告中的数字和名称都能在查询结果中找到出处。\n");
		}
		else {
			sb.append("- ⚠️ 以下内容在查询结果中找不到出处，请勿直接采信：").append(String.join("、", untraced)).append("\n");
		}
		return sb.toString();
	}

	private static String flatten(List<List<Map<String, String>>> sets) {
		StringBuilder sb = new StringBuilder();
		sets.forEach(rs -> rs.forEach(r -> sb.append(r.values()).append("\n")));
		return sb.toString();
	}

	private static String firstLine(String s) {
		int i = s.indexOf('\n');
		return (i > 0 ? s.substring(0, i) : s).replace("【降级】", "").strip();
	}

}
