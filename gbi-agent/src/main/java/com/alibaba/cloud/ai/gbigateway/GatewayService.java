package com.alibaba.cloud.ai.gbigateway;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.connector.accessor.AccessorFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

/**
 * The data plane of the hybrid deployment ("服务在云、数据在地"). Runs next to the database, holds its credentials, and
 * answers the cloud service's data requests with the same connector code the cloud would otherwise run itself.
 *
 * <p>
 * What it enforces regardless of what the cloud asks (the cloud is not trusted with the data):
 * <ul>
 * <li>only the read methods of {@link Accessor}; free SQL must parse as exactly one SELECT (JSqlParser), fail closed;</li>
 * <li>its own schema, not the one in the request;</li>
 * <li>a row cap, and masking of personal columns (phone, receiver name, ...) before anything leaves;</li>
 * <li>an audit line per request: method, SQL, rows / bytes sent, cells masked, outcome.</li>
 * </ul>
 */
@Slf4j
public class GatewayService {

	static final Set<String> METHODS = Set.of("showDatabases", "showSchemas", "showTables", "fetchTables", "showColumns",
			"showForeignKeys", "sampleColumn", "scanTable", "executeSqlAndReturnObject");

	private static final ObjectMapper M = new ObjectMapper();

	private final GatewayProperties props;

	private final AccessorFactory accessors;

	private final List<Pattern> masks;

	public GatewayService(GatewayProperties props, AccessorFactory accessors) {
		this.props = props;
		this.accessors = accessors;
		this.masks = props.getMaskColumns().stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();
	}

	/** Outcome of one request: the payload to return plus what the audit records. */
	public record Outcome(Object payload, int rows, int masked) {
	}

	public static class Rejected extends RuntimeException {

		public Rejected(String msg) {
			super(msg);
		}

	}

	public Outcome access(String method, DbQueryParameter param) throws Exception {
		if (!METHODS.contains(method)) {
			throw new Rejected("method not allowed: " + method);
		}
		DbQueryParameter p = param == null ? new DbQueryParameter() : param;
		DbConfigBO db = localConfig();
		if (db.getSchema() != null) {
			p.setSchema(db.getSchema());
		}
		if ("executeSqlAndReturnObject".equals(method)) {
			checkReadOnly(p.getSql());
		}
		Accessor accessor = accessors.getAccessorByDbConfig(db);
		Object out = accessor.accessDb(db, method, p);
		int masked = 0;
		int rows = 0;
		if (out instanceof ResultSetBO rs) {
			masked = maskAndCap(rs);
			rows = rs.getData() == null ? 0 : rs.getData().size();
		}
		else if ("sampleColumn".equals(method) && out instanceof List<?> values && isMasked(p.getColumn())) {
			List<String> m = new ArrayList<>();
			values.forEach(v -> m.add(mask(String.valueOf(v))));
			masked = m.size();
			rows = m.size();
			out = m;
		}
		else if (out instanceof List<?> l) {
			rows = l.size();
		}
		return new Outcome(out, rows, masked);
	}

	public DbConfigBO localConfig() {
		DbConfigBO c = new DbConfigBO();
		c.setUrl(props.getDatasource().getUrl());
		c.setUsername(props.getDatasource().getUsername());
		c.setPassword(props.getDatasource().getPassword());
		c.setSchema(props.getDatasource().getSchema());
		c.setDialectType(props.getDatasource().getDialect());
		c.setConnectionType("jdbc");
		return c;
	}

	static void checkReadOnly(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new Rejected("empty SQL");
		}
		Statements parsed;
		try {
			parsed = CCJSqlParserUtil.parseStatements(sql);
		}
		catch (Exception e) {
			throw new Rejected("SQL not parseable, refused: " + e.getMessage().lines().findFirst().orElse(""));
		}
		List<Statement> list = parsed.getStatements();
		if (list.size() != 1 || !(list.get(0) instanceof Select)) {
			throw new Rejected("only a single SELECT is allowed through the gateway");
		}
		String lower = sql.toLowerCase();
		if (lower.contains(" into outfile") || lower.contains(" into dumpfile") || lower.contains("for update")) {
			throw new Rejected("SELECT ... INTO OUTFILE / FOR UPDATE refused");
		}
	}

	int maskAndCap(ResultSetBO rs) {
		if (rs.getData() == null) {
			return 0;
		}
		if (rs.getData().size() > props.getMaxRows()) {
			rs.setData(new ArrayList<>(rs.getData().subList(0, props.getMaxRows())));
		}
		List<String> cols = rs.getColumn() == null ? List.of() : rs.getColumn().stream().filter(this::isMasked).toList();
		int n = 0;
		for (Map<String, String> row : rs.getData()) {
			for (String c : cols) {
				String v = row.get(c);
				if (v != null) {
					row.put(c, mask(v));
					n++;
				}
			}
		}
		return n;
	}

	boolean isMasked(String column) {
		return column != null && masks.stream().anyMatch(p -> p.matcher(column).matches());
	}

	/** 13812345678 -> 138****5678, 张三丰 -> 张**; keeps enough shape for a reader, nothing re-identifying. */
	static String mask(String v) {
		if (v.length() >= 7) {
			return v.substring(0, 3) + "*".repeat(v.length() - 7) + v.substring(v.length() - 4);
		}
		return v.isEmpty() ? v : v.charAt(0) + "*".repeat(v.length() - 1);
	}

	public synchronized void audit(String method, DbQueryParameter p, Outcome o, long bytes, long millis, String error) {
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("ts", Instant.now().toString());
		line.put("method", method);
		if (p != null && p.getSql() != null) {
			line.put("sql", p.getSql());
		}
		if (p != null && p.getTable() != null) {
			line.put("table", p.getTable());
		}
		line.put("rows", o == null ? 0 : o.rows());
		line.put("masked", o == null ? 0 : o.masked());
		line.put("bytes", bytes);
		line.put("ms", millis);
		line.put("error", error);
		try (FileWriter w = new FileWriter(props.getAuditLog(), true)) {
			w.write(M.writeValueAsString(line) + "\n");
		}
		catch (IOException e) {
			log.warn("audit write failed: {}", e.getMessage());
		}
	}

}
