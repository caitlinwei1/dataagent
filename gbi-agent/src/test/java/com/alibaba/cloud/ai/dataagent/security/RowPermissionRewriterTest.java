package com.alibaba.cloud.ai.dataagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RowPermissionRewriterTest {

	static final RowPolicyRegistry REGISTRY = new RowPolicyRegistry("classpath:data-permission/row-policies.yml");

	static final RowPermissionRewriter REWRITER = new RowPermissionRewriter(REGISTRY);

	/** 华东运营: 5 shops, 3 warehouses. */
	static final DataPermission EAST = new DataPermission(2, "east_ops", true, List.of(), false,
			Map.of("shop", List.of("1", "2", "3", "7", "10"), "warehouse", List.of("1", "3", "7")));

	static final String SHOP = "shop_id IN (1, 2, 3, 7, 10)";

	static String rewrite(String sql) {
		String out = REWRITER.rewrite(sql, EAST).sql();
		// whatever comes out must still be one parsable SELECT
		assertThat(parses(out)).as(out).isTrue();
		return out;
	}

	static boolean parses(String sql) {
		try {
			CCJSqlParserUtil.parse(sql);
			return true;
		}
		catch (Exception e) {
			return false;
		}
	}

	static int count(String s, String sub) {
		return s.split(java.util.regex.Pattern.quote(sub), -1).length - 1;
	}

	@Test
	void plainTableIsWrappedUnderItsOwnName() {
		String out = rewrite("SELECT SUM(pay_amount) FROM trd_order WHERE order_status = 'PAID'");
		assertThat(out).contains("FROM (SELECT * FROM trd_order WHERE " + SHOP + ") AS trd_order WHERE order_status = 'PAID'");
	}

	@Test
	void aliasIsKeptAndSharedTablesUntouched() {
		String out = rewrite("SELECT s.name, SUM(o.pay_amount) FROM trd_order o JOIN dim_shop s ON s.id = o.shop_id GROUP BY s.name");
		assertThat(out).contains(") o JOIN dim_shop s ON");
		assertThat(count(out, "SELECT *")).isEqualTo(1);
	}

	@Test
	void leftJoinStaysOuter() {
		String out = rewrite("SELECT w.name, COUNT(i.id) FROM wms_warehouse w LEFT JOIN wms_inventory i ON i.warehouse_id = w.id GROUP BY w.name");
		assertThat(out).contains("LEFT JOIN (SELECT * FROM wms_inventory WHERE warehouse_id IN (1, 3, 7)) i ON i.warehouse_id = w.id");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			// subquery in WHERE
			"SELECT COUNT(*) FROM trd_order WHERE id IN (SELECT order_id FROM trd_refund)|2",
			// EXISTS
			"SELECT COUNT(*) FROM trd_order o WHERE EXISTS (SELECT 1 FROM trd_refund r WHERE r.order_id = o.id)|2",
			// scalar subquery in the select list
			"SELECT (SELECT COUNT(*) FROM trd_refund) AS refunds, COUNT(*) FROM trd_order|2",
			// HAVING
			"SELECT shop_id FROM trd_order GROUP BY shop_id HAVING COUNT(*) > (SELECT COUNT(*) / 12 FROM trd_order)|2",
			// CTE
			"WITH m AS (SELECT shop_id, SUM(pay_amount) amt FROM trd_order GROUP BY shop_id) SELECT * FROM m|1",
			// UNION
			"SELECT id FROM trd_order UNION ALL SELECT order_id FROM trd_refund|2",
			// derived table
			"SELECT AVG(c) FROM (SELECT shop_id, COUNT(*) c FROM trd_order GROUP BY shop_id) t|1",
			// parenthesized join
			"SELECT COUNT(*) FROM (trd_order o JOIN trd_order_item i ON i.order_id = o.id)|2",
			// self join
			"SELECT COUNT(*) FROM trd_order a JOIN trd_order b ON a.member_id = b.member_id AND a.id < b.id|2",
			// LATERAL
			"SELECT o.id, x.n FROM trd_order o CROSS JOIN LATERAL (SELECT COUNT(*) n FROM trd_order_item i WHERE i.order_id = o.id) x|2",
			// schema-qualified and quoted
			"SELECT COUNT(*) FROM gbi_ecommerce.trd_order JOIN `trd_payment` p ON p.order_id = trd_order.id|2" })
	void everyReadIsFiltered(String sql, int expectedWraps) {
		String out = rewrite(sql);
		assertThat(count(out, "(SELECT * FROM")).as(out).isEqualTo(expectedWraps);
		assertThat(REWRITER.rewrite(sql, EAST).changed()).isTrue();
	}

	@Test
	void childTableFiltersThroughItsParent() {
		String out = rewrite("SELECT SUM(refund_amount) FROM trd_refund_item");
		assertThat(out).contains(
				"WHERE refund_id IN (SELECT id FROM trd_refund WHERE order_id IN (SELECT id FROM trd_order WHERE " + SHOP + "))");
	}

	@Test
	void orFilterKeepsItsParentheses() {
		String out = rewrite("SELECT COUNT(*) FROM wms_transfer WHERE status = 'DONE'");
		assertThat(out).contains("WHERE from_warehouse_id IN (1, 3, 7) OR to_warehouse_id IN (1, 3, 7)) AS wms_transfer WHERE status = 'DONE'");
	}

	@Test
	void unrestrictedOrSharedTablesAreUnchanged() {
		String sql = "SELECT COUNT(*) FROM trd_order";
		DataPermission all = DataPermission.unrestricted(1, "admin");
		assertThat(REWRITER.rewrite(sql, all).sql()).isEqualTo(sql);
		assertThat(REWRITER.rewrite(sql, null).changed()).isFalse();
		assertThat(REWRITER.rewrite("SELECT name FROM dim_shop", EAST).changed()).isFalse();
	}

	@Test
	void missingDimensionMeansNoRows() {
		DataPermission shopsOnly = new DataPermission(5, "shop_ops", true, List.of(), false, Map.of("shop", List.of("1")));
		String out = REWRITER.rewrite("SELECT SUM(qty) FROM wms_inventory", shopsOnly).sql();
		assertThat(out).contains("WHERE 1 = 0");
	}

	@Test
	void nonNumericValuesAreDroppedForNumericDimensions() {
		DataPermission evil = new DataPermission(9, "x", true, List.of(), false,
				Map.of("shop", List.of("1) OR (1=1", "2")));
		String out = REWRITER.rewrite("SELECT COUNT(*) FROM trd_order", evil).sql();
		assertThat(out).contains("shop_id IN (2)").doesNotContain("1=1");
	}

	@Test
	void unparsableSqlFailsClosed() {
		assertThatThrownBy(() -> REWRITER.rewrite("SELECT FROM WHERE", EAST))
			.isInstanceOf(RowPermissionRewriter.RewriteException.class);
	}

	@Test
	void tableGrantsMatchPrefixes() {
		DataPermission wh = new DataPermission(3, "sh_wh", false, List.of("wms_*", "dim_*", "tms_delivery_sla"), false,
				Map.of());
		assertThat(wh.allowsTable("wms_inventory")).isTrue();
		assertThat(wh.allowsTable("WMS_OUTBOUND")).isTrue();
		assertThat(wh.allowsTable("tms_delivery_sla")).isTrue();
		assertThat(wh.allowsTable("tms_shipment")).isFalse();
		assertThat(wh.allowsTable("trd_order")).isFalse();
	}

	@Test
	void permissionSurvivesTheGraphStateRoundTrip() {
		DataPermission back = DataPermission.fromState(EAST.toState());
		assertThat(back).isEqualTo(EAST);
		assertThat(DataPermission.fromState(null)).isNull();
	}

}
