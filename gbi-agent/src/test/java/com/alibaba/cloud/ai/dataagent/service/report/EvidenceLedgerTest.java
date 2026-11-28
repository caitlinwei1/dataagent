package com.alibaba.cloud.ai.dataagent.service.report;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLedgerTest {

	/** B6 after5 shape: SQL steps returned data, the warehouse step's Python degraded, the report named a warehouse anyway. */
	static Map<String, String> b6Results() {
		Map<String, String> r = new LinkedHashMap<>();
		r.put("step_1", "{\"column\":[\"period\",\"order_status\",\"cnt\",\"amount\"],\"data\":[{\"period\":\"本期\",\"order_status\":\"CLOSED\",\"cnt\":\"23\",\"amount\":\"76637.57\"},{\"period\":\"本期\",\"order_status\":\"COMPLETED\",\"cnt\":\"20\",\"amount\":\"62444.73\"},{\"period\":\"上期\",\"order_status\":\"COMPLETED\",\"cnt\":\"66\",\"amount\":\"172718.25\"}]}");
		r.put("step_2", "{\"column\":[\"reason_code\",\"cnt\"],\"data\":[{\"reason_code\":\"STOCKOUT\",\"cnt\":\"23\"}]}");
		r.put("step_3", "【降级】第 3 步的 Python 分析未完成（已重写 5 次，最后错误：KeyError: 'warehouse'）。本步只保留上一步 SQL 的查询结果，报告不包含此步的计算结论。");
		r.put("step_4", "{\"column\":[\"x\"],\"data\":[]}");
		return r;
	}

	@Test
	void classifiesSteps() {
		var ledger = EvidenceLedger.build(List.of("SQL", "SQL", "PYTHON", "SQL", "REPORT"), b6Results());
		assertThat(ledger).extracting(EvidenceLedger.Step::status)
			.containsExactly(EvidenceLedger.Status.DONE, EvidenceLedger.Status.DONE, EvidenceLedger.Status.BLOCKED,
					EvidenceLedger.Status.EMPTY);
		assertThat(EvidenceLedger.promptBlock(ledger, List.of("销售额：通用口径"), List.of())).contains("第 3 步（PYTHON）：受阻")
			.contains("未定（零行）");
	}

	@Test
	void flagsTheFabricatedWarehouseAndCount() {
		Map<String, String> r = b6Results();
		String report = "本周销售额 62,444.73，上期 172,718.25，下降 63.8%。缺货退款 23 单。其中“华东仓-苏州中心仓”单仓贡献 12 单（57.1%），缺货订单分布于 7 个仓库。";
		List<String> untraced = EvidenceLedger.untraced(report, "曜石华东销售额为什么下降", EvidenceLedger.resultSets(r),
				EvidenceLedger.resultTexts(r));
		assertThat(untraced).contains("苏州中心仓", "12 单", "7 个仓库").doesNotContain("23 单");
		assertThat(untraced).noneMatch(u -> u.contains("62,444.73") || u.contains("172,718.25"));
	}

	@Test
	void tracedReportIsClean() {
		Map<String, String> r = b6Results();
		String report = "本周销售额 62,444.73，上期 172,718.25。本周 23 单支付后关闭，金额 76,637.57；第 3 步仓库定位受阻（Python 失败），仓库结论待确认。";
		assertThat(EvidenceLedger.untraced(report, "q", EvidenceLedger.resultSets(r), EvidenceLedger.resultTexts(r)))
			.isEmpty();
		String footer = EvidenceLedger.footer(EvidenceLedger.build(List.of("SQL", "SQL", "PYTHON", "SQL"), r), List.of(),
				List.of(), List.of());
		assertThat(footer).contains("受阻").contains("都能在查询结果中找到出处");
	}

	@Test
	void metricNotesChangeTheStepStatus() {
		Map<String, String> r = new LinkedHashMap<>();
		r.put("step_1", "{\"data\":[{\"a\":\"1\"}],\"note\":\"【口径】口径校验未通过：...（已重写 2 次仍未满足，按当前 SQL 执行，结果口径待确认）\"}");
		r.put("step_2", "{\"data\":[{\"a\":\"1\"}],\"note\":\"【口径】系统已按口径合约自动补上条件（模型重写后仍遗漏）：...\"}");
		assertThat(EvidenceLedger.build(List.of("SQL", "SQL"), r)).extracting(EvidenceLedger.Step::status)
			.containsExactly(EvidenceLedger.Status.UNVERIFIED, EvidenceLedger.Status.FIXED);
	}

	@Test
	void phrasesEndingInWarehouseAreNotNames() {
		assertThat(EvidenceLedger.untraced("建议把畅销 SKU 下沉至华东各城市前置仓", "q", List.of(), List.of())).isEmpty();
	}

}
