package com.alibaba.cloud.ai.dataagent.service.sql.guard;

import java.util.List;
import java.util.Set;

/**
 * Outcome of {@link SqlGuard#check}. {@code tables} lists the tables the SQL reads (lower case, unqualified).
 */
public record GuardResult(boolean passed, SqlCheck failedCheck, String message, Set<String> tables,
		List<String> warnings) {

	static GuardResult ok(Set<String> tables, List<String> warnings) {
		return new GuardResult(true, null, "", tables, warnings);
	}

	static GuardResult fail(SqlCheck check, String message, Set<String> tables) {
		return new GuardResult(false, check, message, tables, List.of());
	}

	/** Table / row data permission, decided outside the guard (it needs the caller, the guard does not). */
	public static GuardResult permissionDenied(String message, Set<String> tables) {
		return new GuardResult(false, SqlCheck.PERMISSION, message, tables, List.of());
	}

	public SqlErrorClass errorClass() {
		return failedCheck == null ? null : failedCheck.errorClass();
	}

	/** Text fed back to SQL generation so the model knows what to change. */
	public String feedback() {
		return passed ? "" : "SQL守卫拦截[" + failedCheck + "/" + errorClass().label() + "]：" + message;
	}

}
