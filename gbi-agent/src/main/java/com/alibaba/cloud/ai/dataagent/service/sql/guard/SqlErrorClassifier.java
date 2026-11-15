package com.alibaba.cloud.ai.dataagent.service.sql.guard;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;
import java.util.Set;

/**
 * Maps a database error to 选表 / 方言 / 执行 using SQLState and vendor codes (MySQL, PostgreSQL / Hologres / ADB-PG),
 * with a message fallback when a driver wraps the exception.
 */
public final class SqlErrorClassifier {

	/** MySQL: unknown table / column, ambiguous column, table not in FROM. */
	private static final Set<Integer> MYSQL_TABLE = Set.of(1146, 1054, 1052, 1109, 1051);

	/**
	 * MySQL: syntax, unknown function, wrong arg count, ONLY_FULL_GROUP_BY, invalid group function use. 1370
	 * ("execute command denied ... for routine") is what the SELECT-only account gets for a function MySQL does not
	 * have, e.g. PostgreSQL's date_trunc: MySQL looks for a stored routine and checks EXECUTE first.
	 */
	private static final Set<Integer> MYSQL_DIALECT = Set.of(1064, 1305, 1370, 1582, 1583, 1584, 1055, 1111, 1140,
			3065);

	/** PostgreSQL: undefined table / column, ambiguous column. */
	private static final Set<String> PG_TABLE = Set.of("42P01", "42703", "42702", "42P10");

	/** PostgreSQL: syntax, undefined function, type mismatch, grouping error, invalid cast. */
	private static final Set<String> PG_DIALECT = Set.of("42601", "42883", "42804", "42803", "42846", "42725",
			"22P02");

	private SqlErrorClassifier() {
	}

	public static SqlErrorClass classify(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SQLTimeoutException) {
				return SqlErrorClass.EXECUTION;
			}
			if (t instanceof SQLException e) {
				String state = e.getSQLState() == null ? "" : e.getSQLState();
				if (MYSQL_TABLE.contains(e.getErrorCode()) || PG_TABLE.contains(state)) {
					return SqlErrorClass.TABLE_SELECTION;
				}
				if (MYSQL_DIALECT.contains(e.getErrorCode()) || PG_DIALECT.contains(state)) {
					return SqlErrorClass.DIALECT;
				}
				if (!state.isEmpty() || e.getErrorCode() != 0) {
					return SqlErrorClass.EXECUTION;
				}
			}
		}
		String m = String.valueOf(error == null ? "" : error.getMessage()).toLowerCase(Locale.ROOT);
		if (m.contains("doesn't exist") || m.contains("does not exist") && (m.contains("relation") || m.contains("column"))
				|| m.contains("unknown column")) {
			return SqlErrorClass.TABLE_SELECTION;
		}
		if (m.contains("syntax") || m.contains("function") && m.contains("does not exist")
				|| m.contains("group by")) {
			return SqlErrorClass.DIALECT;
		}
		return SqlErrorClass.EXECUTION;
	}

}
