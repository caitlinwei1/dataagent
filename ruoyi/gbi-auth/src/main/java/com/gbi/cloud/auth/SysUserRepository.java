package com.gbi.cloud.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the RuoYi-style system tables in gbi_system. Plain JDBC: a dozen queries don't need MyBatis. */
@Repository
public class SysUserRepository {

	public record SysUser(long userId, Long deptId, String userName, String password, String status) {
	}

	private final JdbcTemplate jdbc;

	public SysUserRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<SysUser> findByUserName(String userName) {
		List<SysUser> rows = jdbc.query(
				"SELECT user_id, dept_id, user_name, password, status FROM sys_user WHERE user_name = ? AND del_flag = '0'",
				(rs, i) -> new SysUser(rs.getLong(1), (Long) rs.getObject(2, Long.class), rs.getString(3),
						rs.getString(4), rs.getString(5)),
				userName);
		return rows.stream().findFirst();
	}

	public List<DataScopeResolver.Role> roles(long userId) {
		return jdbc.query("""
				SELECT r.role_id, r.role_key, r.data_scope FROM sys_role r
				JOIN sys_user_role ur ON ur.role_id = r.role_id
				WHERE ur.user_id = ? AND r.status = '0' AND r.del_flag = '0'
				ORDER BY r.role_id""", (rs, i) -> new DataScopeResolver.Role(rs.getLong(1), rs.getString(2),
				rs.getString(3)), userId);
	}

	public Set<String> permissions(List<Long> roleIds) {
		if (roleIds.isEmpty()) {
			return Set.of();
		}
		return new LinkedHashSet<>(jdbc.queryForList(
				"SELECT DISTINCT perms FROM sys_role_perm WHERE role_id IN (" + placeholders(roleIds.size()) + ")",
				String.class, roleIds.toArray()));
	}

	public Map<Long, Set<Long>> roleDepts(List<Long> roleIds) {
		Map<Long, Set<Long>> out = new HashMap<>();
		if (roleIds.isEmpty()) {
			return out;
		}
		jdbc.query("SELECT role_id, dept_id FROM sys_role_dept WHERE role_id IN (" + placeholders(roleIds.size()) + ")",
				rs -> {
					out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getLong(2));
				}, roleIds.toArray());
		return out;
	}

	public Map<Long, Set<String>> roleTables(List<Long> roleIds) {
		Map<Long, Set<String>> out = new HashMap<>();
		if (roleIds.isEmpty()) {
			return out;
		}
		jdbc.query("SELECT role_id, table_pattern FROM gbi_role_table WHERE role_id IN ("
				+ placeholders(roleIds.size()) + ")", rs -> {
					out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
				}, roleIds.toArray());
		return out;
	}

	public List<DataScopeResolver.Dept> depts() {
		return jdbc.query("SELECT dept_id, ancestors FROM sys_dept WHERE del_flag = '0' AND status = '0'",
				(rs, i) -> new DataScopeResolver.Dept(rs.getLong(1), rs.getString(2)));
	}

	public Map<Long, Map<String, Set<String>>> deptData() {
		Map<Long, Map<String, Set<String>>> out = new HashMap<>();
		jdbc.query("SELECT dept_id, dim, dim_value FROM gbi_dept_data", rs -> {
			out.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
				.computeIfAbsent(rs.getString(2), k -> new LinkedHashSet<>())
				.add(rs.getString(3));
		});
		return out;
	}

	public void loginLog(String userName, String ip, boolean ok, String msg) {
		jdbc.update("INSERT INTO sys_logininfor (user_name, ipaddr, status, msg, access_time) VALUES (?, ?, ?, ?, NOW())",
				userName, ip, ok ? "0" : "1", msg);
	}

	static List<Long> ids(List<DataScopeResolver.Role> roles) {
		List<Long> ids = new ArrayList<>();
		roles.forEach(r -> ids.add(r.roleId()));
		return ids;
	}

	private static String placeholders(int n) {
		return String.join(",", java.util.Collections.nCopies(n, "?"));
	}

}
