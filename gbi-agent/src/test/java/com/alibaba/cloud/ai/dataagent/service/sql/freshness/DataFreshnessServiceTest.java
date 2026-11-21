package com.alibaba.cloud.ai.dataagent.service.sql.freshness;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DataFreshnessServiceTest {

	@Test
	void halfOpenUpperBoundMeansDayBefore() {
		String sql = "select sum(spend) from mkt_ad_spend where stat_date >= '2026-10-31' and stat_date < '2026-11-01'";
		assertThat(DataFreshnessService.maxDateLiteral(sql)).isEqualTo(LocalDate.of(2026, 10, 31));
	}

	@Test
	void equalityAndNoDates() {
		assertThat(DataFreshnessService.maxDateLiteral("where stat_date = '2026-10-30'"))
			.isEqualTo(LocalDate.of(2026, 10, 30));
		assertThat(DataFreshnessService.maxDateLiteral("select 1")).isNull();
	}

}
