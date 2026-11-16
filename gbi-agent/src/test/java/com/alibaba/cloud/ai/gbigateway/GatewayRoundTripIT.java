package com.alibaba.cloud.ai.gbigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.TableInfoBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.service.gateway.GatewayAccessor;
import com.alibaba.cloud.ai.dataagent.service.sql.guard.SqlErrorClass;
import com.alibaba.cloud.ai.dataagent.service.sql.guard.SqlErrorClassifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Hybrid deployment end to end: the cloud-side {@link GatewayAccessor} talks to a real gateway process context in front
 * of the local MySQL (:13306, loaded by scripts/load_dataset.sh). Skipped when that database is not running.
 */
class GatewayRoundTripIT {

	static final String URL = "jdbc:mysql://127.0.0.1:13306/gbi_ecommerce?useSSL=false&allowPublicKeyRetrieval=true";

	static ConfigurableApplicationContext ctx;

	static Path audit;

	static DbConfigBO cloud;

	static final GatewayAccessor accessor = new GatewayAccessor();

	@BeforeAll
	static void start() throws Exception {
		try (var c = DriverManager.getConnection(URL, "gbi_reader", "gbi_reader_local")) {
			// database is up
		}
		catch (Exception e) {
			assumeTrue(false, "database not running: " + e.getMessage());
		}
		audit = Files.createTempFile("gateway-audit", ".jsonl");
		ctx = new SpringApplicationBuilder(GatewayApplication.class).properties("server.port=0",
				"spring.config.name=none", "gbi.gateway.secret=test-secret", "gbi.gateway.max-rows=50",
				"gbi.gateway.audit-log=" + audit, "gbi.gateway.datasource.url=" + URL,
				"gbi.gateway.datasource.username=gbi_reader", "gbi.gateway.datasource.password=gbi_reader_local",
				"gbi.gateway.datasource.schema=gbi_ecommerce")
			.run();
		int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
		cloud = new DbConfigBO();
		cloud.setUrl("http://127.0.0.1:" + port);
		cloud.setPassword("test-secret");
		cloud.setSchema("ignored_by_gateway");
		cloud.setDialectType("mysql");
		cloud.setConnectionType("gateway");
	}

	@AfterAll
	static void stop() {
		if (ctx != null) {
			ctx.close();
		}
	}

	@Test
	void schemaAndQueriesGoThroughTheGateway() throws Exception {
		List<TableInfoBO> tables = accessor.showTables(cloud, new DbQueryParameter().setSchema("whatever"));
		assertThat(tables).extracting(TableInfoBO::getName).contains("trd_order", "wms_inventory");

		ResultSetBO rs = accessor.executeSqlAndReturnObject(cloud,
				new DbQueryParameter().setSql("SELECT COUNT(*) AS n FROM trd_order"));
		assertThat(Long.parseLong(rs.getData().get(0).get("n"))).isPositive();

		ResultSetBO people = accessor.executeSqlAndReturnObject(cloud,
				new DbQueryParameter().setSql("SELECT order_id, receiver_name FROM trd_order_address LIMIT 200"));
		assertThat(people.getData()).hasSize(50); // capped at max-rows
		assertThat(people.getData()).allSatisfy(r -> assertThat(r.get("receiver_name")).contains("*"));

		assertThat(Files.readString(audit)).contains("trd_order_address").contains("\"masked\":50");
	}

	@Test
	void writesAreRefusedAndSqlErrorsKeepTheirClass() {
		assertThatThrownBy(() -> accessor.executeSqlAndReturnObject(cloud,
				new DbQueryParameter().setSql("DELETE FROM trd_order WHERE id = -1")))
			.hasMessageContaining("[gateway 403]");

		assertThatThrownBy(() -> accessor.executeSqlAndReturnObject(cloud,
				new DbQueryParameter().setSql("SELECT no_such_col FROM trd_order")))
			.isInstanceOfSatisfying(SQLException.class, e -> {
				assertThat(e.getSQLState()).isEqualTo("42S22");
				assertThat(SqlErrorClassifier.classify(e)).isEqualTo(SqlErrorClass.TABLE_SELECTION);
			});
	}

	@Test
	void wrongSecretAndReplayAreRejected() throws Exception {
		DbConfigBO wrong = new DbConfigBO();
		wrong.setUrl(cloud.getUrl());
		wrong.setPassword("guess");
		assertThatThrownBy(() -> accessor.showTables(wrong, new DbQueryParameter()))
			.hasMessageContaining("[gateway 401]");

		String body = "{\"method\":\"showSchemas\",\"param\":{}}";
		String sig = GatewaySigner.sign("test-secret", System.currentTimeMillis() / 1000, body);
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder(URI.create(cloud.getUrl() + "/gateway/v1/access"))
			.header("Content-Type", "application/json")
			.header(GatewaySigner.HEADER, sig)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
		HttpResponse<String> again = http.send(req, HttpResponse.BodyHandlers.ofString());
		assertThat(again.statusCode()).isEqualTo(401);
		assertThat(again.body()).contains("replayed");
	}

}
