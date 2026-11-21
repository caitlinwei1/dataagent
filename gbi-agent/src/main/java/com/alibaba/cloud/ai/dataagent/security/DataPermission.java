package com.alibaba.cloud.ai.dataagent.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What one user may query: which tables, and per data dimension which values. Built from the LoginUser the
 * gateway forwards (resolved at login by gbi-auth) and carried through the graph as plain maps / lists so it
 * survives the MySQL checkpoint used for human-review resume.
 *
 * <p>
 * {@code null} anywhere a DataPermission is expected means "security disabled": no restriction.
 */
public record DataPermission(long userId, String userName, boolean allTables, List<String> tablePatterns,
		boolean allRows, Map<String, List<String>> dims) {

	public static DataPermission unrestricted(long userId, String userName) {
		return new DataPermission(userId, userName, true, List.of(), true, Map.of());
	}

	/** Exact names or prefix wildcards ({@code wms_*}), matched case-insensitively. */
	public boolean allowsTable(String table) {
		if (allTables) {
			return true;
		}
		String t = table.toLowerCase(Locale.ROOT);
		for (String p : tablePatterns) {
			String q = p.toLowerCase(Locale.ROOT);
			if (q.endsWith("*") ? t.startsWith(q.substring(0, q.length() - 1)) : t.equals(q)) {
				return true;
			}
		}
		return false;
	}

	/** Values allowed for a dimension; empty = none (also when the dimension was never granted). */
	public List<String> values(String dim) {
		return dims.getOrDefault(dim, List.of());
	}

	public Map<String, Object> toState() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("userId", userId);
		m.put("userName", userName);
		m.put("allTables", allTables);
		m.put("tablePatterns", new ArrayList<>(tablePatterns));
		m.put("allRows", allRows);
		Map<String, Object> d = new LinkedHashMap<>();
		dims.forEach((k, v) -> d.put(k, new ArrayList<>(v)));
		m.put("dims", d);
		return m;
	}

	@SuppressWarnings("unchecked")
	public static DataPermission fromState(Object o) {
		if (!(o instanceof Map<?, ?> m) || m.isEmpty()) {
			return null;
		}
		Map<String, List<String>> dims = new LinkedHashMap<>();
		Object d = m.get("dims");
		if (d instanceof Map<?, ?> dm) {
			dm.forEach((k, v) -> dims.put(String.valueOf(k), strings(v)));
		}
		return new DataPermission(((Number) m.get("userId")).longValue(), String.valueOf(m.get("userName")),
				Boolean.TRUE.equals(m.get("allTables")), strings(m.get("tablePatterns")),
				Boolean.TRUE.equals(m.get("allRows")), dims);
	}

	private static List<String> strings(Object o) {
		List<String> out = new ArrayList<>();
		if (o instanceof Iterable<?> it) {
			it.forEach(x -> out.add(String.valueOf(x)));
		}
		return out;
	}

	/** One line for logs and the UI. */
	public String describe() {
		Set<String> parts = new LinkedHashSet<>();
		parts.add(allTables ? "全部表" : "表 " + String.join(",", tablePatterns));
		if (allRows) {
			parts.add("全部数据");
		}
		else {
			dims.forEach((k, v) -> parts.add(k + "=" + v));
			if (dims.isEmpty()) {
				parts.add("无行级数据");
			}
		}
		return userName + "（" + String.join("；", parts) + "）";
	}

}
