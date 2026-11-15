package com.alibaba.cloud.ai.dataagent.service.sql.guard;

/**
 * The deterministic checks of {@link SqlGuard}. The fourth check, semantic consistency (does the SQL answer the
 * question), is the LLM-based SemanticConsistencyNode that runs before execution.
 */
public enum SqlCheck {

	/** One statement that the parser accepts. */
	SYNTAX(SqlErrorClass.DIALECT),

	/** A plain SELECT: no DML/DDL, no SELECT INTO / FOR UPDATE, no side-effect or file/sleep functions. */
	READ_ONLY(SqlErrorClass.SECURITY),

	/** Every table is in the agent's table whitelist and in the datasource's own schema. */
	WHITELIST(SqlErrorClass.TABLE_SELECTION),

	/** Qualified columns exist in the recalled schema. */
	COLUMN(SqlErrorClass.TABLE_SELECTION);

	private final SqlErrorClass errorClass;

	SqlCheck(SqlErrorClass errorClass) {
		this.errorClass = errorClass;
	}

	public SqlErrorClass errorClass() {
		return errorClass;
	}

}
