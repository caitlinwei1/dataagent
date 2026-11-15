package com.gbi.cloud.auth;

import com.gbi.cloud.common.security.DataScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns RuoYi's role data_scope into concrete business values, once, at login.
 *
 * <p>
 * RuoYi's {@code @DataScope} aspect expands data_scope into a SQL fragment over sys_dept at query time, which works
 * because RuoYi's own tables carry dept_id / create_by. The GBI warehouse doesn't: business tables carry shop_id /
 * warehouse_id. So departments are bound to business values (gbi_dept_data), and the role's scope picks which
 * departments count:
 * <ul>
 * <li>1 全部数据 → no row filter</li>
 * <li>2 自定数据 → the departments in sys_role_dept</li>
 * <li>3 本部门 → the user's department</li>
 * <li>4 本部门及以下 → the user's department and every department whose ancestors contain it</li>
 * <li>5 仅本人 → nothing: no business table has an owner column, so this resolves to no rows (fail closed)</li>
 * </ul>
 * Several roles are OR-ed, as in RuoYi.
 */
public final class DataScopeResolver {

	public record Role(long roleId, String roleKey, String dataScope) {
	}

	public record Dept(long deptId, String ancestors) {
	}

	/**
	 * @param userDeptId the user's department
	 * @param roles the user's enabled roles
	 * @param roleDepts role_id → departments (sys_role_dept), used by scope 2
	 * @param depts every department with its ancestors path ("0,100,101")
	 * @param deptData dept_id → dimension → values (gbi_dept_data)
	 * @param roleTables role_id → table patterns (gbi_role_table)
	 */
	public static DataScope resolve(Long userDeptId, List<Role> roles, Map<Long, Set<Long>> roleDepts,
			Collection<Dept> depts, Map<Long, Map<String, Set<String>>> deptData, Map<Long, Set<String>> roleTables) {
		DataScope scope = new DataScope();

		Set<String> patterns = new TreeSet<>();
		for (Role r : roles) {
			patterns.addAll(roleTables.getOrDefault(r.roleId(), Set.of()));
		}
		scope.setAllTables(patterns.contains("*"));
		scope.setTablePatterns(scope.isAllTables() ? List.of() : new ArrayList<>(patterns));

		if (roles.stream().anyMatch(r -> "1".equals(r.dataScope()))) {
			scope.setAllRows(true);
			return scope;
		}
		Set<Long> visible = new LinkedHashSet<>();
		for (Role r : roles) {
			switch (r.dataScope() == null ? "" : r.dataScope()) {
				case "2" -> visible.addAll(roleDepts.getOrDefault(r.roleId(), Set.of()));
				case "3" -> {
					if (userDeptId != null) {
						visible.add(userDeptId);
					}
				}
				case "4" -> {
					if (userDeptId != null) {
						visible.add(userDeptId);
						for (Dept d : depts) {
							if (d.ancestors() != null && List.of(d.ancestors().split(",")).contains(userDeptId.toString())) {
								visible.add(d.deptId());
							}
						}
					}
				}
				default -> {
					// "5" (仅本人) and unknown values grant no rows
				}
			}
		}
		Map<String, Set<String>> dims = new LinkedHashMap<>();
		for (Long d : visible) {
			deptData.getOrDefault(d, Map.of()).forEach((dim, values) -> dims.computeIfAbsent(dim, k -> new TreeSet<>()).addAll(values));
		}
		Map<String, List<String>> out = new LinkedHashMap<>();
		dims.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
		scope.setAllRows(false);
		scope.setDims(out);
		return scope;
	}

	private DataScopeResolver() {
	}

}
