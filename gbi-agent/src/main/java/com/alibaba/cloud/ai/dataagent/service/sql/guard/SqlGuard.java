package com.alibaba.cloud.ai.dataagent.service.sql.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.delete.ParenthesedDelete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.ParenthesedInsert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.ParenthesedUpdate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Deterministic pre-execution checks on LLM-generated SQL: syntax, read-only, table whitelist, column existence.
 *
 * <p>
 * It is one layer of several: the datasource also connects with a SELECT-only account, the JDBC connection is set
 * read-only, and statements run with a query timeout and a row cap. The guard exists so that a bad statement is
 * rejected with a reason the SQL generator can act on, before it reaches the database.
 */
@Component
public class SqlGuard {

	/** Functions with side effects, file / network access, or that can stall the database. */
	static final Set<String> DENIED_FUNCTIONS = Set.of("sleep", "benchmark", "pg_sleep", "pg_sleep_for",
			"pg_sleep_until", "load_file", "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
			"lo_import", "lo_export", "lo_unlink", "dblink", "dblink_exec", "get_lock", "release_lock",
			"pg_advisory_lock", "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "set_config", "nextval",
			"setval", "query_to_xml", "sys_exec", "sys_eval");

	/** Schemas that hold server metadata or other tenants' data, never business tables. */
	static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "mysql", "performance_schema", "sys",
			"pg_catalog", "pg_toast");

	/** Bare words that parse as columns but are keywords / niladic functions. */
	private static final Set<String> PSEUDO_COLUMNS = Set.of("current_date", "current_time", "current_timestamp",
			"localtime", "localtimestamp", "current_user", "session_user", "user", "true", "false", "null",
			// interval units, e.g. TIMESTAMPDIFF(HOUR, a, b) parses HOUR as a column
			"microsecond", "second", "minute", "hour", "day", "week", "month", "quarter", "year");

	/**
	 * Statement keywords that make an unparsable text a write / lock attempt rather than a plain syntax error.
	 */
	private static final java.util.regex.Pattern WRITE_WORDS = java.util.regex.Pattern.compile(
			"\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|replace|merge|upsert|call|exec|execute"
					+ "|into|lock|copy|load|set|rename|handler|do)\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * @param sql the generated SQL
	 * @param schema the datasource's database / schema name; qualified tables must use it (null = any non-system)
	 * @param whitelist tables the agent may read (lower case); empty = no whitelist configured
	 * @param columns recalled table → its columns (lower case); tables missing here are not column-checked
	 */
	public GuardResult check(String sql, String schema, Set<String> whitelist, Map<String, Set<String>> columns) {
		String text = sql == null ? "" : sql.strip();
		if (text.isEmpty()) {
			return GuardResult.fail(SqlCheck.SYNTAX, "SQL 为空", Set.of());
		}
		Statements statements;
		try {
			statements = CCJSqlParserUtil.parseStatements(text);
		}
		catch (JSQLParserException | RuntimeException e) {
			return unparsable(text, e);
		}
		if (statements.size() != 1) {
			return GuardResult.fail(SqlCheck.READ_ONLY, "只允许一条语句，实际 " + statements.size() + " 条", Set.of());
		}
		Statement st = statements.get(0);
		if (!(st instanceof Select select)) {
			return GuardResult.fail(SqlCheck.READ_ONLY, "只允许 SELECT，实际是 " + st.getClass().getSimpleName(), Set.of());
		}
		if (select.getForMode() != null) {
			return GuardResult.fail(SqlCheck.READ_ONLY, "不允许加锁读（FOR " + select.getForMode() + "）", Set.of());
		}

		Walker w = new Walker();
		Set<String> raw;
		try {
			raw = w.getTables((Statement) select);
		}
		catch (RuntimeException e) {
			return unparsable(text, e);
		}
		if (w.write != null) {
			return GuardResult.fail(SqlCheck.READ_ONLY, "包含写操作：" + w.write, Set.of());
		}
		if (w.into) {
			return GuardResult.fail(SqlCheck.READ_ONLY, "不允许 SELECT ... INTO", Set.of());
		}
		for (String fn : w.functions) {
			if (DENIED_FUNCTIONS.contains(fn)) {
				return GuardResult.fail(SqlCheck.READ_ONLY, "不允许调用函数 " + fn + "()", Set.of());
			}
		}

		Set<String> tables = new LinkedHashSet<>();
		for (String full : raw) {
			String[] parts = unquote(full).toLowerCase(Locale.ROOT).split("\\.");
			String table = parts[parts.length - 1];
			if (parts.length > 1) {
				String sch = parts[parts.length - 2];
				if (SYSTEM_SCHEMAS.contains(sch)) {
					return GuardResult.fail(SqlCheck.WHITELIST, "不允许访问系统库 " + sch, tables);
				}
				if (schema != null && !schema.isBlank() && !sch.equalsIgnoreCase(schema)) {
					return GuardResult.fail(SqlCheck.WHITELIST, "不允许跨库访问 " + sch + "." + table, tables);
				}
			}
			tables.add(table);
		}
		if (whitelist != null && !whitelist.isEmpty()) {
			List<String> outside = tables.stream().filter(t -> !whitelist.contains(t)).toList();
			if (!outside.isEmpty()) {
				return GuardResult.fail(SqlCheck.WHITELIST, "表不在白名单：" + String.join(", ", outside), tables);
			}
		}

		List<String> warnings = new ArrayList<>();
		if (columns != null && !columns.isEmpty()) {
			String bad = unknownColumn(w, tables, columns);
			if (bad != null) {
				return GuardResult.fail(SqlCheck.COLUMN, bad, tables);
			}
		}
		return GuardResult.ok(tables, warnings);
	}

	private static String unknownColumn(Walker w, Set<String> tables, Map<String, Set<String>> columns) {
		String single = tables.size() == 1 ? tables.iterator().next() : null;
		for (Column c : w.cols) {
			String name = unquote(c.getColumnName()).toLowerCase(Locale.ROOT);
			if (name.equals("*") || PSEUDO_COLUMNS.contains(name)) {
				continue;
			}
			Table t = c.getTable();
			String table;
			if (t != null && t.getName() != null) {
				String q = unquote(t.getName()).toLowerCase(Locale.ROOT);
				table = w.alias.getOrDefault(q, tables.contains(q) ? q : null);
			}
			else {
				// unqualified: only checkable with one table, and not when it names a select-list alias
				table = w.selectAliases.contains(name) ? null : single;
			}
			if (table == null || !columns.containsKey(table)) {
				continue;
			}
			if (!columns.get(table).contains(name)) {
				return "表 " + table + " 没有字段 " + name;
			}
		}
		return null;
	}

	/**
	 * Fail closed. If the text that could not be parsed carries a write / lock keyword outside string literals and
	 * comments, report it as a read-only violation so the regeneration hint says "write a plain SELECT".
	 */
	private static GuardResult unparsable(String text, Exception e) {
		String bare = text.replaceAll("'(?:[^'\\\\]|\\\\.|'')*'", "''")
			.replaceAll("--[^\\n]*", " ")
			.replaceAll("(?s)/\\*.*?\\*/", " ");
		java.util.regex.Matcher m = WRITE_WORDS.matcher(bare);
		if (m.find()) {
			return GuardResult.fail(SqlCheck.READ_ONLY, "无法解析且包含 " + m.group(1).toUpperCase(Locale.ROOT) + "，只允许纯 SELECT",
					Set.of());
		}
		return GuardResult.fail(SqlCheck.SYNTAX, "无法解析：" + firstLine(e), Set.of());
	}

	private static String unquote(String s) {
		return s.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
	}

	private static String firstLine(Exception e) {
		Throwable t = e.getCause() != null ? e.getCause() : e;
		String m = String.valueOf(t.getMessage());
		int i = m.indexOf('\n');
		return i > 0 ? m.substring(0, i) : m;
	}

	/** Collects tables plus what the base finder ignores: writes, INTO, functions, columns and aliases. */
	private static final class Walker extends TablesNamesFinder<Void> {

		String write;

		boolean into;

		final Set<String> functions = new LinkedHashSet<>();

		final List<Column> cols = new ArrayList<>();

		final Map<String, String> alias = new HashMap<>();

		final Set<String> selectAliases = new LinkedHashSet<>();

		Walker() {
			init(false);
		}

		@Override
		public <S> Void visit(PlainSelect ps, S ctx) {
			if ((ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) || ps.getIntoTempTable() != null) {
				into = true;
			}
			if (ps.getSelectItems() != null) {
				for (SelectItem<?> it : ps.getSelectItems()) {
					if (it.getAlias() != null) {
						selectAliases.add(unquote(it.getAlias().getName()).toLowerCase(Locale.ROOT));
					}
				}
			}
			return super.visit(ps, ctx);
		}

		@Override
		public <S> Void visit(Table table, S ctx) {
			if (table.getAlias() != null && table.getName() != null) {
				alias.put(unquote(table.getAlias().getName()).toLowerCase(Locale.ROOT),
						unquote(table.getName()).toLowerCase(Locale.ROOT));
			}
			return super.visit(table, ctx);
		}

		@Override
		public <S> Void visit(Function f, S ctx) {
			if (f.getName() != null) {
				String n = unquote(f.getName()).toLowerCase(Locale.ROOT);
				functions.add(n.substring(n.lastIndexOf('.') + 1));
			}
			return super.visit(f, ctx);
		}

		@Override
		public <S> Void visit(Column c, S ctx) {
			cols.add(c);
			return super.visit(c, ctx);
		}

		@Override
		public <S> Void visit(Insert s, S ctx) {
			write = "INSERT";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(ParenthesedInsert s, S ctx) {
			write = "INSERT";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(Update s, S ctx) {
			write = "UPDATE";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(ParenthesedUpdate s, S ctx) {
			write = "UPDATE";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(Delete s, S ctx) {
			write = "DELETE";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(ParenthesedDelete s, S ctx) {
			write = "DELETE";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(Merge s, S ctx) {
			write = "MERGE";
			return super.visit(s, ctx);
		}

		@Override
		public <S> Void visit(Upsert s, S ctx) {
			write = "UPSERT";
			return super.visit(s, ctx);
		}

	}

}
