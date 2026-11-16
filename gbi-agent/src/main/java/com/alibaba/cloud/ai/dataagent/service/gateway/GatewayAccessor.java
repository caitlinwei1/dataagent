package com.alibaba.cloud.ai.dataagent.service.gateway;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ColumnInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.DatabaseInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ForeignKeyInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.SchemaInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.TableInfoBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.enums.BizDataSourceTypeEnum;
import com.alibaba.cloud.ai.gbigateway.GatewaySigner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Cloud side of the hybrid deployment: an {@link Accessor} that forwards every data request to the local data gateway
 * instead of opening a JDBC connection. Everything above it (recall, guard, SQL execution, error classification) is
 * unchanged; a gateway error carrying SQLState is rethrown as an SQLException so the 选表 / 方言 / 执行 classes still work.
 */
@Component
public class GatewayAccessor implements Accessor {

	private static final ObjectMapper M = new ObjectMapper()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private static final Map<String, TypeReference<?>> TYPES = Map.of("showDatabases",
			new TypeReference<List<DatabaseInfoBO>>() {
			}, "showSchemas", new TypeReference<List<SchemaInfoBO>>() {
			}, "showTables", new TypeReference<List<TableInfoBO>>() {
			}, "fetchTables", new TypeReference<List<TableInfoBO>>() {
			}, "showColumns", new TypeReference<List<ColumnInfoBO>>() {
			}, "showForeignKeys", new TypeReference<List<ForeignKeyInfoBO>>() {
			}, "sampleColumn", new TypeReference<List<String>>() {
			}, "scanTable", new TypeReference<ResultSetBO>() {
			}, "executeSqlAndReturnObject", new TypeReference<ResultSetBO>() {
			});

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Override
	public String getAccessorType() {
		return "Gateway_Accessor";
	}

	@Override
	public boolean supportedDataSourceType(String type) {
		return BizDataSourceTypeEnum.MYSQL_GATEWAY.getTypeName().equals(type)
				|| BizDataSourceTypeEnum.POSTGRESQL_GATEWAY.getTypeName().equals(type);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T accessDb(DbConfigBO dbConfig, String method, DbQueryParameter param) throws Exception {
		TypeReference<?> type = TYPES.get(method);
		if (type == null) {
			throw new UnsupportedOperationException("Unknown method: " + method);
		}
		String body = M.writeValueAsString(Map.of("method", method, "param", param == null ? Map.of() : param));
		HttpRequest req = HttpRequest.newBuilder(URI.create(base(dbConfig.getUrl()) + "/gateway/v1/access"))
			.timeout(Duration.ofSeconds(120))
			.header("Content-Type", "application/json")
			.header(GatewaySigner.HEADER,
					GatewaySigner.sign(dbConfig.getPassword(), System.currentTimeMillis() / 1000, body))
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonNode out = M.readTree(resp.body());
		if (resp.statusCode() != 200) {
			String msg = "[gateway " + resp.statusCode() + "] " + out.path("error").asText();
			if (out.hasNonNull("sqlState")) {
				throw new SQLException(msg, out.get("sqlState").asText(), out.path("vendorCode").asInt());
			}
			throw new SQLException(msg);
		}
		return (T) M.convertValue(out.get("result"), type);
	}

	private static String base(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	@Override
	public List<DatabaseInfoBO> showDatabases(DbConfigBO c) throws Exception {
		return accessDb(c, "showDatabases", null);
	}

	@Override
	public List<SchemaInfoBO> showSchemas(DbConfigBO c) throws Exception {
		return accessDb(c, "showSchemas", null);
	}

	@Override
	public List<TableInfoBO> showTables(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "showTables", p);
	}

	@Override
	public List<TableInfoBO> fetchTables(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "fetchTables", p);
	}

	@Override
	public List<ColumnInfoBO> showColumns(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "showColumns", p);
	}

	@Override
	public List<ForeignKeyInfoBO> showForeignKeys(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "showForeignKeys", p);
	}

	@Override
	public List<String> sampleColumn(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "sampleColumn", p);
	}

	@Override
	public ResultSetBO scanTable(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "scanTable", p);
	}

	@Override
	public ResultSetBO executeSqlAndReturnObject(DbConfigBO c, DbQueryParameter p) throws Exception {
		return accessDb(c, "executeSqlAndReturnObject", p);
	}

	/** Health probe used by the datasource connection test. */
	public boolean ping(String url) {
		try {
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base(url) + "/gateway/v1/health"))
				.timeout(Duration.ofSeconds(5))
				.build(), HttpResponse.BodyHandlers.ofString());
			return r.statusCode() == 200;
		}
		catch (Exception e) {
			return false;
		}
	}

}
