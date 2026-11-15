package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dataagent.service.sql.guard.SqlErrorClassifier;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TimeRangeHintsTest {

	@Test
	void februaryEndsOnTheFirstOfMarch() {
		String h = TimeRangeHints.hints("2026年2月每天的GMV是多少？");
		assertTrue(h.contains("2026年2月：>= '2026-02-01' AND < '2026-03-01'"), h);
		assertTrue(!h.contains("02-29") && !h.contains("02-28'"), h);
	}

	@Test
	void comparisonPeriodsComeFromTheAnchorMonth() {
		String h = TimeRangeHints.hints("用户: 2026年3月的GMV是多少？", "和去年同期比呢？");
		assertTrue(h.contains("去年同期（2025年3月）：>= '2025-03-01' AND < '2025-04-01'"), h);
		h = TimeRangeHints.hints("分析2026年1月GMV相比上个月的变化");
		assertTrue(h.contains("上个月（2025年12月）：>= '2025-12-01' AND < '2026-01-01'"), h);
		h = TimeRangeHints.hints("截至2026年4月的近6个月，每个月的广告花费");
		assertTrue(h.contains(">= '2025-11-01' AND < '2026-05-01'"), h);
	}

	@Test
	void rangesYearsQuartersAndDays() {
		String h = TimeRangeHints.hints("2025年10月到2026年4月每个月的订单数");
		assertTrue(h.contains("2025年10月到2026年4月：>= '2025-10-01' AND < '2026-05-01'"), h);
		h = TimeRangeHints.hints("2025年每个月的订单数，以及2025年第四季度和2024年下半年");
		assertTrue(h.contains("2025年：>= '2025-01-01' AND < '2026-01-01'"), h);
		assertTrue(h.contains(">= '2025-10-01' AND < '2026-01-01'"), h);
		assertTrue(h.contains(">= '2024-07-01' AND < '2025-01-01'"), h);
		h = TimeRangeHints.hints("2026年3月5日的订单");
		assertTrue(h.contains(">= '2026-03-05' AND < '2026-03-06'"), h);
		assertEquals("", TimeRangeHints.hints("各大区的GMV"));
	}

	@Test
	void invalidDateGetsAConcreteRepairHint() {
		String hint = SqlErrorClassifier.repairHint(new SQLException("Incorrect DATE value: '2026-02-29'", "HY000", 1525));
		assertTrue(hint.contains("'2026-02-29' 在日历上不存在"), hint);
		assertEquals("", SqlErrorClassifier.repairHint(new SQLException("Unknown column 'x'", "42S22", 1054)));
	}

}
