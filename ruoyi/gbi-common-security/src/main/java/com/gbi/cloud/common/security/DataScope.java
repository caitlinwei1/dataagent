package com.gbi.cloud.common.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a user may query, resolved once at login from roles, departments and grants.
 *
 * <ul>
 * <li>Table level: {@code allTables} or the {@code tablePatterns} (exact names or prefix wildcards such as
 * {@code wms_*}).</li>
 * <li>Row level: {@code allRows}, or per data dimension the values the user may see, e.g.
 * {@code shop -> [101, 102]}. A dimension missing from {@code dims} while {@code allRows} is false means no rows
 * (fail closed).</li>
 * </ul>
 */
public class DataScope {

	private boolean allTables;

	private List<String> tablePatterns = new ArrayList<>();

	private boolean allRows;

	private Map<String, List<String>> dims = new LinkedHashMap<>();

	public static DataScope unrestricted() {
		DataScope s = new DataScope();
		s.allTables = true;
		s.allRows = true;
		return s;
	}

	public boolean isAllTables() {
		return allTables;
	}

	public void setAllTables(boolean allTables) {
		this.allTables = allTables;
	}

	public List<String> getTablePatterns() {
		return tablePatterns;
	}

	public void setTablePatterns(List<String> tablePatterns) {
		this.tablePatterns = tablePatterns;
	}

	public boolean isAllRows() {
		return allRows;
	}

	public void setAllRows(boolean allRows) {
		this.allRows = allRows;
	}

	public Map<String, List<String>> getDims() {
		return dims;
	}

	public void setDims(Map<String, List<String>> dims) {
		this.dims = dims;
	}

}
