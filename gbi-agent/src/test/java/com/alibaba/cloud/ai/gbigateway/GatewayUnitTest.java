package com.alibaba.cloud.ai.gbigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayUnitTest {

	@Test
	void signatureBindsSecretBodyAndTime() {
		String body = "{\"method\":\"showTables\"}";
		String h = GatewaySigner.sign("s3cret", 1000, body);
		String h2 = GatewaySigner.sign("s3cret", 1000, body);
		assertThat(GatewaySigner.nonce(h)).isNotEqualTo(GatewaySigner.nonce(h2)); // same request twice is not a replay
		assertThat(GatewaySigner.verify("s3cret", h.replace(",n=", ",n=0"), body, 1010, 300)).isEqualTo("bad signature");
		assertThat(GatewaySigner.verify("s3cret", h, body, 1010, 300)).isNull();
		assertThat(GatewaySigner.verify("other", h, body, 1010, 300)).isEqualTo("bad signature");
		assertThat(GatewaySigner.verify("s3cret", h, body.replace("show", "drop"), 1010, 300))
			.isEqualTo("bad signature");
		assertThat(GatewaySigner.verify("s3cret", h, body, 1000 + 301, 300)).contains("skew");
		assertThat(GatewaySigner.verify("s3cret", null, body, 1000, 300)).isEqualTo("missing signature");
		assertThat(GatewaySigner.verify("", h, body, 1000, 300)).contains("not configured");
	}

	@Test
	void onlyOneSelectPasses() {
		GatewayService.checkReadOnly("SELECT a FROM t WHERE b = 'x; DROP TABLE t'");
		GatewayService.checkReadOnly("WITH x AS (SELECT 1 a) SELECT a FROM x");
		for (String sql : List.of("DELETE FROM t", "UPDATE t SET a = 1", "SELECT 1; DROP TABLE t",
				"INSERT INTO t SELECT * FROM s", "SELECT * FROM t INTO OUTFILE '/tmp/x'", "SELECT * FROM t FOR UPDATE",
				"CALL p()", "SELEC broken", "")) {
			assertThatThrownBy(() -> GatewayService.checkReadOnly(sql)).as(sql)
				.isInstanceOf(GatewayService.Rejected.class);
		}
	}

	@Test
	void personalColumnsAreMaskedAndRowsCapped() {
		GatewayProperties p = new GatewayProperties();
		p.setMaxRows(2);
		GatewayService s = new GatewayService(p, null);
		assertThat(s.isMasked("receiver_name")).isTrue();
		assertThat(s.isMasked("phone_masked")).isTrue();
		assertThat(s.isMasked("contact_tel")).isTrue();
		assertThat(s.isMasked("hotel")).isFalse();
		assertThat(s.isMasked("pay_amount")).isFalse();
		assertThat(GatewayService.mask("13812345678")).isEqualTo("138****5678");
		assertThat(GatewayService.mask("张三丰")).isEqualTo("张**");

		ResultSetBO rs = new ResultSetBO();
		rs.setColumn(List.of("order_id", "receiver_name"));
		List<Map<String, String>> data = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Map<String, String> row = new HashMap<>();
			row.put("order_id", "o" + i);
			row.put("receiver_name", "王小明" + i);
			data.add(row);
		}
		rs.setData(data);
		assertThat(s.maskAndCap(rs)).isEqualTo(2);
		assertThat(rs.getData()).hasSize(2);
		assertThat(rs.getData().get(0)).containsEntry("order_id", "o0").containsEntry("receiver_name", "王***");
	}

}
