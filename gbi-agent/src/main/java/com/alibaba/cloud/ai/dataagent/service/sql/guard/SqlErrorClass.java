package com.alibaba.cloud.ai.dataagent.service.sql.guard;

/**
 * Why a generated SQL failed. Drives the regeneration hint and the error breakdown in the eval.
 */
public enum SqlErrorClass {

	/** Wrong / missing / non-whitelisted table or column: the schema-recall or SQL step picked the wrong objects. */
	TABLE_SELECTION("选表"),

	/** Parses for one database but not this one: syntax, functions, GROUP BY rules, type casts. */
	DIALECT("方言"),

	/** Valid SQL that failed at run time: timeout, permission, connection, data error. */
	EXECUTION("执行"),

	/** Blocked before execution because it is not a pure read (write, DDL, lock, file / sleep functions). */
	SECURITY("安全"),
	/** The user may not read this table / these rows; the model should answer with the tables it is allowed. */
	PERMISSION("权限");

	private final String label;

	SqlErrorClass(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
