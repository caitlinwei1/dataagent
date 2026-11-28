package com.alibaba.cloud.ai.dataagent.service.semantic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryReconcilerTest {

	static final MetricContracts C = MetricContracts.load("classpath:gbi/metric-contracts.yml");

	static final String B6_SUMMARY = "SELECT `t`.`stat_date`, SUM(`t`.`gmv`) AS `gmv`, SUM(`t`.`paid_amount`) AS `paid_amount` FROM `trd_daily_shop_sales` AS `t` JOIN `dim_shop` AS `s` ON `t`.`shop_id` = `s`.`id` WHERE `t`.`stat_date` >= '2026-10-12' AND `t`.`stat_date` < '2026-10-19' GROUP BY `t`.`stat_date`";

	@Test
	void substitutesTheSummaryTableKeepingItsAlias() {
		var spec = C.reconciles().get(0);
		String sql = SummaryReconciler.substitute(B6_SUMMARY, spec, spec.closedFilter());
		assertThat(sql).doesNotContain("FROM `trd_daily_shop_sales`").contains("FROM trd_order o")
			.contains("order_status IN ('CANCELLED', 'CLOSED')").contains("AS `t`");
		assertThat(SummaryReconciler.outputs(B6_SUMMARY, spec)).containsOnlyKeys("gmv", "paid_amount");
	}

	@Test
	void notesTheClosedOrderShare() {
		var spec = C.reconciles().get(0);
		List<Map<String, String>> summary = List.of(Map.of("stat_date", "2026-10-12", "gmv", "80000", "paid_amount", "139082.30"));
		List<String> ran = new ArrayList<>();
		var out = SummaryReconciler.reconcile(B6_SUMMARY, summary, C.reconciles(), sql -> {
			ran.add(sql);
			return sql.contains("'CLOSED'") ? List.of(Map.of("gmv", "1000", "paid_amount", "76637.57"))
					: List.of(Map.of("gmv", "80000", "paid_amount", "139082.30"));
		});
		assertThat(ran).hasSize(2);
		assertThat(out).isPresent();
		assertThat(out.get().note()).contains("76,637.57").contains("55.1%").doesNotContain("不一致");
	}

	@Test
	void silentWhenConsistentAndNoClosedShare() {
		List<Map<String, String>> summary = List.of(Map.of("gmv", "80000", "paid_amount", "70000"));
		var out = SummaryReconciler.reconcile(B6_SUMMARY, summary, C.reconciles(),
				sql -> sql.contains("'CLOSED'") ? List.of() : List.of(Map.of("gmv", "80000", "paid_amount", "70000")));
		assertThat(out).isPresent();
		assertThat(out.get().note()).isNull();
	}

	@Test
	void plainSummaryColumnsAreReconciledToo() {
		String b10 = "select `t`.`stat_date`, `t`.`paid_amount` from `trd_daily_shop_sales` as `t` join `dim_shop` as `s` on `t`.`shop_id` = `s`.`id` where `t`.`stat_date` >= '2026-10-12'";
		assertThat(SummaryReconciler.outputs(b10, C.reconciles().get(0))).containsOnlyKeys("paid_amount");
		// sem-r1: unqualified columns with a join
		String unq = "select `stat_date`, `paid_amount` from `trd_daily_shop_sales` tds join `dim_shop` ds on tds.`shop_id` = ds.`id`";
		assertThat(SummaryReconciler.outputs(unq, C.reconciles().get(0))).containsOnlyKeys("paid_amount");
	}

	@Test
	void ignoresSqlWithoutSummaryTable() {
		var out = SummaryReconciler.reconcile("SELECT SUM(pay_amount) FROM trd_order", List.of(Map.of("x", "1")),
				C.reconciles(), sql -> List.of());
		assertThat(out).isEmpty();
	}

}
