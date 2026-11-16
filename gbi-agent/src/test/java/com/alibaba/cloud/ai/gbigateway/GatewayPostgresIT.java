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
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** The PostgreSQL dialect (the one Hologres / ADB-PG use) through the gateway, against the local PG :15432. */
class GatewayPostgresIT {

	static final String URL = "jdbc:postgresql://127.0.0.1:15432/postgres?currentSchema=gbi_ecommerce";

	@Test
	void postgresThroughTheGateway() throws Exception {
		try (var c = DriverManager.getConnection(URL, "gbi_reader", "gbi_reader_local")) {
			// database is up
		}
		catch (Exception e) {
			assumeTrue(false, "database not running: " + e.getMessage());
		}
		try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(GatewayApplication.class)
			.properties("server.port=0", "spring.config.name=none", "gbi.gateway.secret=pg-secret",
					"gbi.gateway.audit-log=" + Files.createTempFile("pg-audit", ".jsonl"),
					"gbi.gateway.datasource.dialect=postgresql", "gbi.gateway.datasource.url=" + URL,
					"gbi.gateway.datasource.username=gbi_reader", "gbi.gateway.datasource.password=gbi_reader_local",
					"gbi.gateway.datasource.schema=gbi_ecommerce")
			.run()) {
			DbConfigBO cloud = new DbConfigBO();
			cloud.setUrl("http://127.0.0.1:" + ((WebServerApplicationContext) ctx).getWebServer().getPort());
			cloud.setPassword("pg-secret");
			GatewayAccessor gw = new GatewayAccessor();
			List<TableInfoBO> tables = gw.showTables(cloud, new DbQueryParameter());
			assertThat(tables).extracting(TableInfoBO::getName).contains("trd_order", "wms_inventory");
			ResultSetBO rs = gw.executeSqlAndReturnObject(cloud, new DbQueryParameter()
				.setSql("SELECT receiver_name, COUNT(*) AS n FROM trd_order_address GROUP BY receiver_name LIMIT 3"));
			assertThat(rs.getData()).hasSize(3).allSatisfy(r -> assertThat(r.get("receiver_name")).contains("*"));
			assertThatThrownBy(() -> gw.executeSqlAndReturnObject(cloud,
					new DbQueryParameter().setSql("SELECT no_such_col FROM trd_order")))
				.isInstanceOfSatisfying(SQLException.class, e -> {
					assertThat(e.getSQLState()).isEqualTo("42703");
					assertThat(SqlErrorClassifier.classify(e)).isEqualTo(SqlErrorClass.TABLE_SELECTION);
				});
		}
	}

}
