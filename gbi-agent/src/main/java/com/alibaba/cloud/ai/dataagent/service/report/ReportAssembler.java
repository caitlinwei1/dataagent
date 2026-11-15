package com.alibaba.cloud.ai.dataagent.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link GbiReport} from the node outputs of one graph run.
 *
 * <p>
 * The report text is written by the LLM; the evidence appendix is not: it is rendered from the SQL that actually ran
 * and the rows that came back, so every number in the report can be traced to a query even when the LLM forgets to
 * cite one, and a reviewer can re-run it.
 */
public final class ReportAssembler {

	private static final ObjectMapper M = new ObjectMapper();

	private static final Pattern ECHARTS = Pattern.compile("```echarts\\s*\\n(.*?)```", Pattern.DOTALL);

	/** One streamed chunk: node name, text type name (SQL / RESULT_SET / MARK_DOWN / JSON / TEXT), text. */
	public record Chunk(String node, String type, String text, boolean error) {
	}

	private ReportAssembler() {
	}

	public static GbiReport assemble(String threadId, String question, double seconds, List<Chunk> chunks) {
		List<String[]> segs = new ArrayList<>(); // node, type, text
		List<String> issues = new ArrayList<>();
		for (Chunk c : chunks) {
			if (c.error()) {
				issues.add(c.text());
				continue;
			}
			String[] last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
			if (last != null && eq(last[0], c.node()) && eq(last[1], c.type())) {
				last[2] = last[2] + (c.text() == null ? "" : c.text());
			}
			else {
				segs.add(new String[] { c.node(), c.type(), c.text() == null ? "" : c.text() });
			}
		}
		// plan
		List<Map<String, String>> plan = new ArrayList<>();
		String planJson = join(segs, "PlannerNode", "JSON");
		try {
			JsonNode p = M.readTree(planJson.substring(planJson.indexOf('{'), planJson.lastIndexOf('}') + 1));
			for (JsonNode s : p.path("execution_plan")) {
				Map<String, String> step = new LinkedHashMap<>();
				step.put("step", s.path("step").asText());
				step.put("tool", s.path("tool_to_use").asText());
				step.put("instruction", s.path("tool_parameters").path("instruction").asText(""));
				plan.add(step);
			}
		}
		catch (Exception ignored) {
		}
		// evidence: each executed SQL followed by its result set
		List<GbiReport.Evidence> evidence = new ArrayList<>();
		List<List<Map<String, String>>> results = new ArrayList<>();
		String pendingSql = null;
		int semanticRejects = 0;
		String lastReject = null;
		for (String[] s : segs) {
			if ("SqlExecuteNode".equals(s[0]) && "SQL".equals(s[1])) {
				pendingSql = s[2].strip();
			}
			else if ("SqlExecuteNode".equals(s[0]) && "RESULT_SET".equals(s[1]) && pendingSql != null) {
				evidence.add(evidence(evidence.size() + 1, pendingSql, s[2]));
				results.add(rows(s[2]));
				pendingSql = null;
			}
			else if ("SqlExecuteNode".equals(s[0]) && "TEXT".equals(s[1]) && s[2].contains("失败")) {
				issues.add(s[2].strip());
			}
			if (("PythonAnalyzeNode".equals(s[0]) || "PythonExecuteNode".equals(s[0])) && s[2].contains("降级")) {
				issues.add(s[2].strip());
			}
			if ("FeasibilityAssessmentNode".equals(s[0]) && !s[2].contains("《数据分析》") && s[2].contains("【需求类型】")) {
				// the graph stops before planning (needs clarification / not a data question)
				issues.add("可行性评估未通过：" + clip(s[2].replace("正在进行可行性评估...", "").replace("可行性评估完成！", "").strip()));
			}
			if ("SemanticConsistencyNode".equals(s[0]) && s[2].contains("不通过")) {
				semanticRejects++;
				lastReject = clip(s[2].replace("开始语义一致性校验", "").replace("语义一致性校验完成", "").strip());
			}
			if ("SqlGenerateNode".equals(s[0]) && s[2].contains("超限")) {
				// the graph ends here without a report; say why instead of returning an empty FAILED
				String msg = s[2].substring(s[2].indexOf("步骤") < 0 ? 0 : s[2].indexOf("步骤"));
				issues.add(clip(msg.replace("正在进行重试评估...", "").replace("重试评估完成！", "").strip()));
			}
		}
		if (semanticRejects > 0) {
			issues.add("语义一致性校验共驳回 " + semanticRejects + " 次，最后一次理由：" + lastReject);
		}
		String body = join(segs, "ReportGeneratorNode", "MARK_DOWN");
		if (body.isBlank()) {
			body = join(segs, "ReportGeneratorNode", null);
		}
		body = body.replace("开始生成报告...", "").replace("报告生成完成！", "").strip();
		List<GbiReport.Chart> charts = charts(body);
		if (!evidence.isEmpty() && evidence.stream().allMatch(e -> e.rowCount() == 0)) {
			// a fluent report written over empty result sets reads like an answer but is not one
			issues.add("降级：全部 " + evidence.size() + " 条 SQL 都返回 0 行，报告内容没有数据支撑");
		}
		boolean degraded = issues.stream().anyMatch(i -> i.contains("降级"));
		String status = body.isBlank() ? "FAILED" : degraded ? "DEGRADED" : "COMPLETED";
		String markdown = body.isBlank() ? "" : body + "\n\n" + appendix(evidence);
		List<String> untraced = body.isBlank() ? List.of() : NumberTrace.untraced(body, results);
		return new GbiReport(threadId, question, status, seconds, plan, markdown, charts, evidence, issues, untraced);
	}

	static List<GbiReport.Chart> charts(String markdown) {
		List<GbiReport.Chart> out = new ArrayList<>();
		Matcher m = ECHARTS.matcher(markdown);
		while (m.find()) {
			String option = m.group(1).strip();
			String type = null;
			boolean valid = false;
			try {
				JsonNode n = M.readTree(option);
				JsonNode series = n.path("series");
				JsonNode first = series.isArray() ? series.path(0) : series;
				valid = !first.isMissingNode() && first.size() > 0;
				type = first.path("type").asText(null);
			}
			catch (Exception ignored) {
			}
			out.add(new GbiReport.Chart(out.size() + 1, type, valid, option));
		}
		return out;
	}

	static String appendix(List<GbiReport.Evidence> evidence) {
		if (evidence.isEmpty()) {
			return "## 附录：数据证据\n\n本报告没有执行 SQL。";
		}
		StringBuilder sb = new StringBuilder("## 附录：数据证据\n\n以下 SQL 由系统实际执行，报告中的数字均来自这些结果（只读账号，可复现）。\n");
		for (GbiReport.Evidence e : evidence) {
			sb.append("\n**证据 ").append(e.index()).append("**（").append(e.rowCount()).append(" 行）\n\n```sql\n")
				.append(e.sql())
				.append("\n```\n");
			if (!e.preview().isEmpty()) {
				sb.append("\n| ").append(String.join(" | ", e.columns())).append(" |\n|");
				sb.append(" --- |".repeat(e.columns().size())).append('\n');
				for (Map<String, Object> row : e.preview()) {
					sb.append("| ");
					for (String c : e.columns()) {
						sb.append(String.valueOf(row.get(c))).append(" | ");
					}
					sb.setLength(sb.length() - 1);
					sb.append('\n');
				}
				if (e.rowCount() > e.preview().size()) {
					sb.append("\n（共 ").append(e.rowCount()).append(" 行，仅展示前 ").append(e.preview().size()).append(" 行）\n");
				}
			}
		}
		return sb.toString();
	}

	private static GbiReport.Evidence evidence(int index, String sql, String resultJson) {
		List<String> cols = new ArrayList<>();
		List<Map<String, Object>> preview = new ArrayList<>();
		int rows = 0;
		try {
			JsonNode rs = M.readTree(resultJson).path("resultSet");
			rs.path("column").forEach(c -> cols.add(c.asText()));
			JsonNode data = rs.path("data");
			rows = data.size();
			for (int i = 0; i < Math.min(5, rows); i++) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (String c : cols) {
					row.put(c, data.get(i).path(c).asText(null));
				}
				preview.add(row);
			}
		}
		catch (Exception ignored) {
		}
		return new GbiReport.Evidence(index, sql, cols, rows, preview);
	}

	private static List<Map<String, String>> rows(String resultJson) {
		List<Map<String, String>> out = new ArrayList<>();
		try {
			JsonNode rs = M.readTree(resultJson).path("resultSet");
			List<String> cols = new ArrayList<>();
			rs.path("column").forEach(c -> cols.add(c.asText()));
			for (JsonNode d : rs.path("data")) {
				Map<String, String> row = new LinkedHashMap<>();
				for (String c : cols) {
					row.put(c, d.path(c).asText(null));
				}
				out.add(row);
			}
		}
		catch (Exception ignored) {
		}
		return out;
	}

	private static String join(List<String[]> segs, String node, String type) {
		StringBuilder sb = new StringBuilder();
		for (String[] s : segs) {
			if (node.equals(s[0]) && (type == null || type.equals(s[1]))) {
				sb.append(s[2]);
			}
		}
		return sb.toString();
	}

	private static String clip(String s) {
		return s.length() <= 400 ? s : s.substring(0, 400) + "…";
	}

	private static boolean eq(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}

}
