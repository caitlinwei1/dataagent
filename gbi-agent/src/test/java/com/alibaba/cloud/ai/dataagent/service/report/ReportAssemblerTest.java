package com.alibaba.cloud.ai.dataagent.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Uses a recorded stream of a real multi-step run (eval question P05-1) as the fixture. */
class ReportAssemblerTest {

	private static List<ReportAssembler.Chunk> trace(String name) throws Exception {
		ObjectMapper m = new ObjectMapper();
		List<ReportAssembler.Chunk> out = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				ReportAssemblerTest.class.getResourceAsStream("/report/" + name), StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null;) {
				JsonNode e = m.readTree(line);
				out.add(new ReportAssembler.Chunk(e.path("nodeName").asText(null), e.path("textType").asText(null),
						e.path("text").asText(null), e.path("error").asBoolean()));
			}
		}
		return out;
	}

	@Test
	void realRunBecomesAReportWithEvidence() throws Exception {
		GbiReport r = ReportAssembler.assemble("t1", "统计2026年3月各大区的GMV…", 120, trace("trace_P05-1.jsonl"));
		assertEquals("COMPLETED", r.status(), r.issues().toString());
		assertFalse(r.plan().isEmpty());
		assertFalse(r.evidence().isEmpty());
		assertTrue(r.evidence().get(0).sql().toUpperCase().startsWith("SELECT"), r.evidence().get(0).sql());
		assertTrue(r.evidence().get(0).rowCount() > 0);
		assertTrue(r.markdown().contains("## 附录：数据证据") && r.markdown().contains("```sql"));
		assertFalse(r.markdown().contains("开始生成报告"));
	}

	@Test
	void chartsAreParsedAndValidated() {
		String md = "# 标题\n```echarts\n{\"xAxis\":{},\"series\":[{\"type\":\"bar\",\"data\":[1,2]}]}\n```\n文字\n"
				+ "```echarts\n{\"series\":[{\"type\":\"line\",\"label\":{\"formatter\": function(p){return p}}}]}\n```";
		List<GbiReport.Chart> c = ReportAssembler.charts(md);
		assertEquals(2, c.size());
		assertTrue(c.get(0).valid());
		assertEquals("bar", c.get(0).type());
		assertFalse(c.get(1).valid(), "JS functions are not JSON: the front end cannot render it");
	}

	@Test
	void failedRunHasNoMarkdownAndSaysWhy() {
		GbiReport r = ReportAssembler.assemble("t2", "q", 3,
				List.of(new ReportAssembler.Chunk(null, "TEXT", "Error in stream processing: 403", true)));
		assertEquals("FAILED", r.status());
		assertEquals("", r.markdown());
		assertEquals(1, r.issues().size());
	}

	@Test
	void sqlRetryExhaustionIsReportedWithTheLastSemanticRejection() {
		List<ReportAssembler.Chunk> chunks = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			chunks.add(new ReportAssembler.Chunk("SqlGenerateNode", "SQL", "SELECT 1", false));
			chunks.add(new ReportAssembler.Chunk("SemanticConsistencyNode", "TEXT", "开始语义一致性校验", false));
			chunks.add(new ReportAssembler.Chunk("SemanticConsistencyNode", "TEXT", "不通过，原因" + i, false));
		}
		chunks.add(new ReportAssembler.Chunk("SqlGenerateNode", "TEXT",
				"正在进行重试评估...步骤[1]中，SQL次数生成超限，最大尝试次数：10", false));
		GbiReport r = ReportAssembler.assemble("t3", "q", 9, chunks);
		assertEquals("FAILED", r.status());
		assertEquals(2, r.issues().size(), r.issues().toString());
		assertTrue(r.issues().get(0).startsWith("步骤[1]中，SQL次数生成超限"), r.issues().get(0));
		assertTrue(r.issues().get(1).contains("驳回 3 次") && r.issues().get(1).endsWith("不通过，原因2"), r.issues().get(1));
	}

	@Test
	void reportOverEmptyResultsIsDegraded() {
		List<ReportAssembler.Chunk> chunks = List.of(
				new ReportAssembler.Chunk("SqlExecuteNode", "SQL", "SELECT 1 FROM t WHERE 1=0", false),
				new ReportAssembler.Chunk("SqlExecuteNode", "RESULT_SET",
						"{\"resultSet\":{\"column\":[\"a\"],\"data\":[]}}", false),
				new ReportAssembler.Chunk("ReportGeneratorNode", "MARK_DOWN", "# 报告\n无数据", false));
		GbiReport r = ReportAssembler.assemble("t4", "q", 5, chunks);
		assertEquals("DEGRADED", r.status());
		assertTrue(r.issues().get(0).contains("0 行"), r.issues().toString());
	}

	@Test
	void numbersAreTracedToTheResultSets() {
		// the two months of the real R07 run: the report swapped them and invented a +1,000 change
		List<List<Map<String, String>>> rs = List.of(
				List.of(Map.of("reason", "A", "amt", "40000.00"), Map.of("reason", "B", "amt", "28227.87")),
				List.of(Map.of("reason", "A", "amt", "100000.00"), Map.of("reason", "B", "amt", "36390.30")));
		String body = "2月退款 ¥68,227.87，1月 136,390.30，减少 68,162.43；其中 A 原因 4万。占比 58.6%，2026年。"
				+ "错误写法：2月 ¥136,390.30 → 1月 135,390.30，增加 1,000.00\n```echarts\n{\"series\":[{\"data\":[123456]}]}\n```";
		assertEquals(List.of("135,390.30", "1,000.00"), NumberTrace.untraced(body, rs));
	}

	@Test
	void clarificationStopIsReported() {
		GbiReport r = ReportAssembler.assemble("t5", "q", 30, List.of(new ReportAssembler.Chunk(
				"FeasibilityAssessmentNode", "TEXT", "【需求类型】：《需要澄清》\n【需求内容】：数码家电指哪一级类目？", false)));
		assertEquals("FAILED", r.status());
		assertTrue(r.issues().get(0).startsWith("可行性评估未通过：【需求类型】：《需要澄清》"), r.issues().toString());
	}

	@Test
	void totalsArePrecomputedForTheReportWriter() {
		String rs = "{\"column\":[\"reason_code\",\"amt\",\"shop_id\"],\"data\":[{\"reason_code\":\"A\",\"amt\":\"3446.94\",\"shop_id\":\"1\"},"
				+ "{\"reason_code\":\"B\",\"amt\":\"64780.93\",\"shop_id\":\"2\"}]}";
		String d = ResultTotals.describe(rs);
		assertTrue(d.contains("共 2 行") && d.contains("amt 合计 = 68227.87"), d);
		assertFalse(d.contains("reason_code 合计") || d.contains("shop_id 合计"), d);
		assertEquals("", ResultTotals.describe("{\"column\":[\"a\"],\"data\":[{\"a\":\"1\"}]}"));
	}

}
