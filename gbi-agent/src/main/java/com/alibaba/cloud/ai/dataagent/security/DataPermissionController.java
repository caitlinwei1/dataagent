package com.alibaba.cloud.ai.dataagent.security;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.alibaba.cloud.ai.dataagent.service.sql.guard.GuardResult;
import com.alibaba.cloud.ai.dataagent.service.sql.guard.SqlGuard;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows what the data permission does to a SQL statement for the current user: the same guard → table grant → row
 * rewrite → execute path as SqlExecuteNode, without the LLM. Used by the permission eval and for debugging a
 * user's "why do I see fewer rows" question.
 */
@RestController
@RequestMapping("/api/data-permission")
@RequiredArgsConstructor
public class DataPermissionController {

	public record PreviewRequest(Long agentId, String sql, boolean execute) {
	}

	private final SqlGuard sqlGuard;

	private final RowPermissionRewriter rewriter;

	private final AgentDatasourceService agentDatasourceService;

	private final DatabaseUtil databaseUtil;

	@GetMapping("/me")
	public Map<String, Object> me() {
		CurrentUser u = CurrentUser.get();
		if (u == null) {
			return Map.of("security", false);
		}
		return Map.of("security", true, "user", u.userName(), "roles", u.roles(), "permissions", u.permissions(),
				"dataPermission", u.permission().toState(), "describe", u.permission().describe());
	}

	@PostMapping("/preview")
	public ResponseEntity<Map<String, Object>> preview(@RequestBody PreviewRequest req) throws Exception {
		CurrentUser u = CurrentUser.get();
		DataPermission perm = u == null ? null : u.permission();
		DbConfigBO db = databaseUtil.getAgentDbConfig(req.agentId());
		Set<String> whitelist = new HashSet<>();
		var ds = agentDatasourceService.getCurrentAgentDatasource(req.agentId());
		if (ds != null && ds.getSelectTables() != null) {
			ds.getSelectTables().forEach(t -> whitelist.add(t.toLowerCase(Locale.ROOT)));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		GuardResult g = sqlGuard.check(req.sql(), db.getSchema(), whitelist, Map.of());
		if (g.passed() && perm != null) {
			List<String> denied = g.tables().stream().filter(t -> !perm.allowsTable(t)).toList();
			if (!denied.isEmpty()) {
				g = GuardResult.permissionDenied("当前用户无权查询表：" + String.join(", ", denied), g.tables());
			}
		}
		out.put("passed", g.passed());
		out.put("check", g.failedCheck());
		out.put("message", g.message());
		out.put("tables", g.tables());
		if (!g.passed()) {
			return ResponseEntity.ok(out);
		}
		RowPermissionRewriter.Result r;
		try {
			r = rewriter.rewrite(req.sql(), perm);
		}
		catch (RowPermissionRewriter.RewriteException e) {
			out.put("passed", false);
			out.put("check", "PERMISSION");
			out.put("message", e.getMessage());
			return ResponseEntity.ok(out);
		}
		out.put("executedSql", r.sql());
		out.put("filteredTables", r.filteredTables());
		if (req.execute()) {
			DbQueryParameter p = new DbQueryParameter();
			p.setSql(r.sql());
			p.setSchema(db.getSchema());
			ResultSetBO rs = databaseUtil.getAgentAccessor(req.agentId()).executeSqlAndReturnObject(db, p);
			out.put("columns", rs.getColumn());
			out.put("rows", rs.getData());
			out.put("error", rs.getErrorMsg());
		}
		return ResponseEntity.ok(out);
	}

}
