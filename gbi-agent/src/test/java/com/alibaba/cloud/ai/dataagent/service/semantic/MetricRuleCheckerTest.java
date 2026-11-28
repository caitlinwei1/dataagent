package com.alibaba.cloud.ai.dataagent.service.semantic;

import com.alibaba.cloud.ai.dataagent.security.DataPermission;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContractResolver.Resolved;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetricRuleCheckerTest {

	static final MetricContracts C = MetricContracts.load("classpath:gbi/metric-contracts.yml");

	static final MetricContractResolver R = new MetricContractResolver(C);

	static List<MetricRuleChecker.Violation> check(String q, String sql) {
		return MetricRuleChecker.check(sql, q, R.resolve(q, Set.of(), null));
	}

	static final String B6_Q = "曜石品牌在华东的销售额，2026年10月12日到18日这一周比前一周下降了多少？分析一下原因。";

	static final String B5_Q = "2025年双11期间（10月31日到11月11日）各品牌的GMV是多少？";

	@Test
	void loadsContracts() {
		assertThat(C.metrics()).extracting(MetricContracts.Metric::name).contains("销售额", "GMV", "我方收入");
		assertThat(C.reconciles()).hasSize(1);
	}

	@Test
	void b6SummaryTableForSalesIsRejected() {
		String sql = "SELECT `t`.`stat_date`, SUM(`t`.`gmv`) AS `gmv`, SUM(`t`.`paid_amount`) AS `paid_amount` FROM `trd_daily_shop_sales` AS `t` JOIN `dim_shop` AS `s` ON `t`.`shop_id` = `s`.`id` JOIN `prd_brand` AS `b` ON `s`.`brand_id` = `b`.`id` WHERE `b`.`name` = '曜石' AND `t`.`stat_date` >= '2026-10-05' GROUP BY `t`.`stat_date`";
		assertThat(check(B6_Q, sql)).extracting(MetricRuleChecker.Violation::message)
			.anyMatch(m -> m.contains("trd_daily_shop_sales.paid_amount"));
	}

	@Test
	void b6GoldPasses() {
		String gold = "SELECT CASE WHEN o.paid_at<'2026-10-12' THEN '前一周' ELSE '本周' END wk, SUM(o.pay_amount) FROM trd_order o JOIN dim_shop s ON s.id=o.shop_id JOIN prd_brand b ON b.id=s.brand_id JOIN dim_region r ON r.id=s.region_id WHERE b.name='曜石' AND r.big_region='华东' AND o.paid_at>='2026-10-05' AND o.paid_at<'2026-10-19' AND o.order_status NOT IN ('CANCELLED','CLOSED') GROUP BY wk";
		assertThat(check(B6_Q, gold)).isEmpty();
	}

	@Test
	void salesWithoutStatusIsRejectedButStatusBreakdownPasses() {
		String noStatus = "SELECT SUM(o.pay_amount) FROM trd_order o WHERE o.paid_at >= '2026-10-12' AND o.paid_at < '2026-10-19'";
		assertThat(check(B6_Q, noStatus)).isNotEmpty();
		String breakdown = "SELECT o.order_status, COUNT(*), SUM(o.pay_amount) FROM trd_order o WHERE o.paid_at >= '2026-10-12' GROUP BY o.order_status";
		assertThat(check(B6_Q, breakdown)).isEmpty();
		// refund steps of the same question do not aggregate pay_amount: no rule applies
		String refunds = "SELECT r.reason_code, COUNT(*) FROM trd_refund r JOIN trd_order o ON o.id = r.order_id GROUP BY r.reason_code";
		assertThat(check(B6_Q, refunds)).isEmpty();
	}

	@Test
	void b5StatusFilterAndMissingPresaleAreRejected() {
		String r3 = "SELECT `b`.`name`, SUM(`o`.`total_amount` + `o`.`freight_amount`) AS `gmv` FROM `trd_order` `o` JOIN `dim_shop` `s` ON `o`.`shop_id` = `s`.`id` JOIN `prd_brand` `b` ON `s`.`brand_id` = `b`.`id` LEFT JOIN `trd_presale_order` `p` ON `o`.`id` = `p`.`order_id` WHERE `o`.`order_status` IN ('PAID', 'SHIPPED', 'COMPLETED') AND ((`o`.`is_presale` = 0 AND `o`.`paid_at` >= '2025-10-31') OR (`o`.`is_presale` = 1 AND `p`.`balance_paid_at` >= '2025-10-31')) GROUP BY `b`.`name`";
		assertThat(check(B5_Q, r3)).extracting(MetricRuleChecker.Violation::message)
			.anyMatch(m -> m.contains("trd_order.order_status"));
		String noPresale = "SELECT b.name, SUM(o.total_amount+o.freight_amount) AS gmv FROM trd_order o JOIN dim_shop s ON s.id=o.shop_id JOIN prd_brand b ON b.id=s.brand_id WHERE o.paid_at>='2025-10-31' AND o.paid_at<'2025-11-12' GROUP BY b.name";
		assertThat(check(B5_Q, noPresale)).extracting(MetricRuleChecker.Violation::message)
			.anyMatch(m -> m.contains("balance_paid_at"));
		String gold = "SELECT b.name, SUM(o.total_amount+o.freight_amount) AS gmv FROM trd_order o JOIN dim_shop s ON s.id=o.shop_id JOIN prd_brand b ON b.id=s.brand_id LEFT JOIN trd_presale_order ps ON ps.order_id=o.id WHERE o.paid_at IS NOT NULL AND COALESCE(ps.balance_paid_at,o.paid_at)>='2025-10-31' AND COALESCE(ps.balance_paid_at,o.paid_at)<'2025-11-12' GROUP BY b.name ORDER BY gmv DESC";
		assertThat(check(B5_Q, gold)).isEmpty();
	}

	@Test
	void b2RevenueBySettledAtIsRejected() {
		String q = "2026年3月我们公司在各个品牌上的收入分别是多少？";
		String bySettled = "select b.name, sum(s.revenue_amount) from fin_settlement as s join dim_shop as d on s.shop_id = d.id join prd_brand as b on d.brand_id = b.id where s.settled_at >= '2026-03-01' and s.settled_at < '2026-04-01' group by b.name";
		assertThat(check(q, bySettled)).isNotEmpty();
		String payAmount = "SELECT b.name, SUM(o.pay_amount) FROM trd_order o JOIN dim_shop s ON s.id=o.shop_id JOIN prd_brand b ON b.id=s.brand_id WHERE o.paid_at>='2026-03-01' GROUP BY b.name";
		assertThat(check(q, payAmount)).isNotEmpty();
		String gold = "SELECT b.name, b.coop_mode, SUM(f.revenue_amount) AS revenue FROM fin_settlement f JOIN dim_shop s ON s.id=f.shop_id JOIN prd_brand b ON b.id=s.brand_id WHERE f.month_key='2026-03' GROUP BY b.name, b.coop_mode";
		assertThat(check(q, gold)).isEmpty();
	}

	@Test
	void salesRevenueIsSalesNotRevenue() {
		assertThat(R.resolve("2026年3月的销售收入是多少", Set.of(), null)).extracting(Resolved::metric)
			.containsExactly("销售额");
	}

	@Test
	void b3AndB4WrongSqlAreRejected() {
		String b3 = "SELECT p.name, COUNT(*) FROM trd_order o JOIN dim_shop s ON s.id=o.shop_id JOIN dim_platform p ON p.id=s.platform_id WHERE o.platform_status='TRADE_FINISHED' GROUP BY p.name";
		assertThat(check("2026年3月各平台已完成的订单分别有多少单？", b3)).isNotEmpty();
		String b4 = "SELECT SUM(i.on_hand_qty-i.locked_qty) FROM wms_inventory i WHERE i.warehouse_id=3";
		assertThat(check("2026年10月15日华东区域仓数码家电的可用库存一共多少件？", b4)).isNotEmpty();
		// "current" stock may use the real-time table
		assertThat(check("华东区域仓现在的可用库存一共多少件？", b4)).isEmpty();
	}

	@Test
	void brandAndRoleVariants() {
		DataPermission qinglanOps = new DataPermission(7, "qinglan_ops", false, List.of("trd_*"), false,
				Map.of("shop", List.of("4", "5")));
		Resolved r = R.resolve("2026年3月我们品牌的销售额是多少？", Set.of("brand_ops"), qinglanOps).get(0);
		assertThat(r.variant()).isEqualTo("青岚合同口径");
		assertThat(r.reason()).contains("数据权限");
		String general = "SELECT SUM(o.pay_amount) FROM trd_order o WHERE o.paid_at >= '2026-03-01' AND o.order_status NOT IN ('CANCELLED','CLOSED')";
		assertThat(MetricRuleChecker.check(general, "", List.of(r))).isNotEmpty();
		String contract = "SELECT SUM(o.pay_amount) / 1.13 FROM trd_order o WHERE o.paid_at >= '2026-03-01' AND o.order_status NOT IN ('CANCELLED','CLOSED')";
		assertThat(MetricRuleChecker.check(contract, "", List.of(r))).isEmpty();

		Resolved fin = R.resolve("2026年3月各品牌的销售额分别是多少？", Set.of("finance"), null).get(0);
		assertThat(fin.variant()).isEqualTo("财务确认口径");
		assertThat(MetricRuleChecker.check(general, "", List.of(fin))).isNotEmpty();
		assertThat(MetricRuleChecker.check("SELECT SUM(net_sales) FROM trd_daily_shop_sales WHERE stat_date >= '2026-03-01'",
				"", List.of(fin))).isEmpty();

		// admin, brand named in the question -> the brand's contract; two brands named -> general definition
		assertThat(R.resolve("青岚2026年3月销售额", Set.of("admin"), null).get(0).variant()).isEqualTo("青岚合同口径");
		assertThat(R.resolve("青岚和曜石2026年3月销售额", Set.of("admin"), null).get(0).variant()).isNull();
		assertThat(R.resolve("2026年3月各品牌的销售额", Set.of("admin"), null).get(0).variant()).isNull();
	}

	@Test
	void missingStatusFilterIsRepairedDeterministically() {
		// B6 sem-r1 step 1: the model kept this SQL through two hints
		String step1 = "SELECT COUNT(*) AS order_count, SUM(`pay_amount`) AS total_pay_amount FROM `trd_order` AS `o` JOIN `dim_shop` AS `s` ON `o`.`shop_id` = `s`.`id` WHERE `o`.`paid_at` >= '2026-10-12' AND `o`.`paid_at` < '2026-10-19' AND `o`.`paid_at` IS NOT NULL";
		var resolved = R.resolve(B6_Q, Set.of(), null);
		var v = MetricRuleChecker.check(step1, B6_Q, resolved);
		assertThat(v).hasSize(1);
		String fixed = MetricRuleChecker.autoFix(step1, v).orElseThrow();
		assertThat(fixed).contains("`o`.order_status NOT IN ('CANCELLED', 'CLOSED')");
		assertThat(MetricRuleChecker.check(fixed, B6_Q, resolved)).isEmpty();
		// a wrong source is not repaired mechanically
		String summary = "SELECT SUM(t.paid_amount) FROM trd_daily_shop_sales t";
		assertThat(MetricRuleChecker.autoFix(summary, MetricRuleChecker.check(summary, B6_Q, resolved))).isEmpty();
	}

}
