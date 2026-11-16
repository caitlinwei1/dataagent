package com.alibaba.cloud.ai.gbigateway;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.connector.accessor.AccessorFactory;
import com.alibaba.cloud.ai.dataagent.service.gateway.GatewayAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Direct JDBC vs through the gateway, over every gold SQL of the 问数 set (eval/nl2sql/questions.jsonl). Same accessor
 * code on both sides, so the difference is the gateway hop: HTTP + signing + JSON + masking. Run with
 * {@code mvn test -Dtest=GatewayBenchmark -Dgbi.bench=true}; writes eval/hybrid/results/bench.json.
 */
@EnabledIfSystemProperty(named = "gbi.bench", matches = "true")
class GatewayBenchmark {

	static final String URL = "jdbc:mysql://127.0.0.1:13306/gbi_ecommerce?useSSL=false&allowPublicKeyRetrieval=true";

	static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	@Test
	void directVsGateway() throws Exception {
		Path root = Path.of(System.getProperty("user.dir")).getParent();
		Path audit = Files.createTempFile("bench-audit", ".jsonl");
		List<Map<String, String>> sqls = new ArrayList<>();
		for (String line : Files.readAllLines(root.resolve("eval/nl2sql/questions.jsonl"))) {
			JsonNode q = M.readTree(line);
			String sql = q.hasNonNull("gold_sql") ? q.get("gold_sql").asText()
					: q.hasNonNull("key_sql") ? q.get("key_sql").asText() : null;
			if (sql != null) {
				sqls.add(Map.of("id", q.get("id").asText(), "split", q.get("split").asText(), "sql", sql));
			}
		}
		try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(GatewayApplication.class)
			.properties("server.port=0", "spring.config.name=none", "gbi.gateway.secret=bench-secret",
					"gbi.gateway.max-rows=100000", "gbi.gateway.audit-log=" + audit, "gbi.gateway.datasource.url=" + URL,
					"gbi.gateway.datasource.username=gbi_reader", "gbi.gateway.datasource.password=gbi_reader_local",
					"gbi.gateway.datasource.schema=gbi_ecommerce")
			.run()) {
			GatewayService svc = ctx.getBean(GatewayService.class);
			DbConfigBO local = svc.localConfig();
			Accessor direct = ctx.getBean(AccessorFactory.class).getAccessorByDbConfig(local);
			DbConfigBO cloud = new DbConfigBO();
			cloud.setUrl("http://127.0.0.1:" + ((WebServerApplicationContext) ctx).getWebServer().getPort());
			cloud.setPassword("bench-secret");
			GatewayAccessor gw = new GatewayAccessor();
			// warm up both paths (pool, JIT, HTTP connection)
			for (int i = 0; i < 20; i++) {
				direct.executeSqlAndReturnObject(local, new DbQueryParameter().setSql(sqls.get(i).get("sql")));
				gw.executeSqlAndReturnObject(cloud, new DbQueryParameter().setSql(sqls.get(i).get("sql")));
			}
			Files.writeString(audit, "");
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Map<String, String> s : sqls) {
				Map<String, Object> r = new LinkedHashMap<>(Map.of("id", s.get("id"), "split", s.get("split")));
				ResultSetBO a = null;
				ResultSetBO b = null;
				long t0 = System.nanoTime();
				try {
					a = direct.executeSqlAndReturnObject(local, new DbQueryParameter().setSql(s.get("sql")));
				}
				catch (Exception e) {
					r.put("direct_error", e.getMessage());
				}
				long t1 = System.nanoTime();
				try {
					b = gw.executeSqlAndReturnObject(cloud, new DbQueryParameter().setSql(s.get("sql")));
				}
				catch (Exception e) {
					r.put("gateway_error", e.getMessage());
				}
				long t2 = System.nanoTime();
				r.put("direct_ms", (t1 - t0) / 1e6);
				r.put("gateway_ms", (t2 - t1) / 1e6);
				if (a != null && b != null) {
					GatewayService masker = svc;
					int maskedCols = (int) a.getColumn().stream().filter(masker::isMasked).count();
					masker.maskAndCap(a); // apply the same masking to the direct result, then compare exactly
					r.put("rows", a.getData().size());
					r.put("masked_columns", maskedCols);
					r.put("identical", a.getColumn().equals(b.getColumn()) && a.getData().equals(b.getData()));
					r.put("direct_json_bytes", M.writeValueAsBytes(a.getData()).length);
				}
				rows.add(r);
			}
			long auditBytes = 0;
			int auditMasked = 0;
			for (String line : Files.readAllLines(audit, StandardCharsets.UTF_8)) {
				JsonNode j = M.readTree(line);
				auditBytes += j.path("bytes").asLong();
				auditMasked += j.path("masked").asInt();
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("sqls", rows.size());
			out.put("audit_bytes_sent", auditBytes);
			out.put("audit_cells_masked", auditMasked);
			out.put("results", rows);
			Path dir = root.resolve("eval/hybrid/results");
			Files.createDirectories(dir);
			Files.write(dir.resolve("bench.json"), M.writeValueAsBytes(out));
		}
	}

}
