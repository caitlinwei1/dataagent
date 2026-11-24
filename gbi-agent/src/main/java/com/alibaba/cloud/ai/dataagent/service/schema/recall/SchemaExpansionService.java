package com.alibaba.cloud.ai.dataagent.service.schema.recall;

import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import com.alibaba.cloud.ai.dataagent.mapper.AgentDatasourceMapper;
import com.alibaba.cloud.ai.dataagent.security.DataPermission;
import com.alibaba.cloud.ai.dataagent.service.datasource.DatasourceService;
import com.alibaba.cloud.ai.dataagent.service.schema.SchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B4: a table-selection error (unknown column / unknown table) usually means the table that holds the column was
 * never recalled, so regenerating against the same schema repeats the same guess. This adds the foreign-key
 * neighbours of the tables the failing SQL used, once per step, and hands the regeneration the larger schema.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaExpansionService {

	/** Enough for a bridge table plus its dimension; more would dilute the prompt again. */
	static final int MAX_ADDED = 6;

	private final SchemaService schemaService;

	private final DatasourceService datasourceService;

	private final AgentDatasourceMapper agentDatasourceMapper;

	public record Expansion(SchemaDTO schema, List<String> added) {
	}

	public Optional<Expansion> expand(Long agentId, SchemaDTO schema, Collection<String> sqlTables,
			DataPermission permission) {
		if (schema == null || sqlTables == null || sqlTables.isEmpty()) {
			return Optional.empty();
		}
		Integer datasourceId = agentDatasourceMapper.selectActiveDatasourceIdByAgentId(agentId);
		if (datasourceId == null) {
			return Optional.empty();
		}
		Set<String> present = new LinkedHashSet<>();
		if (schema.getTable() != null) {
			schema.getTable().forEach(t -> present.add(t.getName().toLowerCase(Locale.ROOT)));
		}
		Set<String> seeds = new LinkedHashSet<>();
		sqlTables.forEach(t -> seeds.add(bare(t)));
		List<LogicalRelation> relations = datasourceService.getLogicalRelations(datasourceId)
			.stream()
			.filter(r -> r.getIsDeleted() == null || r.getIsDeleted() != 1)
			.toList();
		List<String> added = neighbours(relations, seeds, present, permission);
		if (added.isEmpty()) {
			return Optional.empty();
		}
		Optional<Expansion> out = merge(agentId, datasourceId, schema, present, added, relations);
		out.ifPresent(e -> log.info("schema_expand agent={} seeds={} added={}", agentId, seeds, e.added()));
		return out;
	}

	/**
	 * B4 / B6: run right after fine selection, before any SQL is written. Two deterministic repairs:
	 * <ol>
	 * <li>tables the selection model asked for but the recall never returned are loaded instead of silently dropped
	 * (B4: the model asked for {@code prd_sku}, {@code prd_category} from the foreign-key list, got neither);</li>
	 * <li>selected tables that do not connect through the logical foreign keys are connected by the shortest path, and
	 * the bridge tables on it are added (B4: {@code prd_spu} between SKU and category; B6: {@code trd_order} between
	 * {@code trd_refund} and {@code dim_shop}, without which the planner invents {@code refund.order_id = shop.id}).</li>
	 * </ol>
	 * Bounded: paths of at most {@link #MAX_BRIDGE_HOPS} edges, at most {@link #MAX_ADDED} tables, only tables the
	 * user may read.
	 */
	public Optional<Expansion> completeJoinPaths(Long agentId, SchemaDTO schema, Collection<String> requested,
			DataPermission permission) {
		if (schema == null) {
			return Optional.empty();
		}
		Integer datasourceId = agentDatasourceMapper.selectActiveDatasourceIdByAgentId(agentId);
		if (datasourceId == null) {
			return Optional.empty();
		}
		Set<String> present = new LinkedHashSet<>();
		if (schema.getTable() != null) {
			schema.getTable().forEach(t -> present.add(t.getName().toLowerCase(Locale.ROOT)));
		}
		List<LogicalRelation> relations = datasourceService.getLogicalRelations(datasourceId)
			.stream()
			.filter(r -> r.getIsDeleted() == null || r.getIsDeleted() != 1)
			.toList();
		Set<String> known = new LinkedHashSet<>();
		relations.forEach(r -> {
			known.add(r.getSourceTableName());
			known.add(r.getTargetTableName());
		});
		Set<String> wanted = new LinkedHashSet<>(present);
		if (requested != null) {
			requested.stream()
				.map(SchemaExpansionService::bare)
				.filter(t -> known.contains(t) || present.contains(t))
				.filter(t -> permission == null || permission.allowsTable(t))
				.forEach(wanted::add);
		}
		List<String> added = bridge(relations, wanted, permission).stream()
			.filter(t -> !present.contains(t))
			.limit(MAX_ADDED)
			.toList();
		if (added.isEmpty()) {
			return Optional.empty();
		}
		Optional<Expansion> out = merge(agentId, datasourceId, schema, present, added, relations);
		out.ifPresent(e -> log.info("join_path_complete agent={} selected={} added={}", agentId, wanted, e.added()));
		return out;
	}

	/** Longest join path a bridge may add (3 edges = up to 2 bridge tables, e.g. sku -> spu -> category). */
	static final int MAX_BRIDGE_HOPS = 3;

	/**
	 * The wanted tables plus the bridges that connect them: components of the wanted set (edges inside it) are
	 * attached to the largest one by the shortest foreign-key path, smallest component first. Deterministic: ties go
	 * to relation order.
	 */
	static List<String> bridge(List<LogicalRelation> relations, Set<String> wanted, DataPermission permission) {
		Map<String, Set<String>> adj = new HashMap<>();
		for (LogicalRelation r : relations) {
			String a = r.getSourceTableName(), b = r.getTargetTableName();
			if (a == null || b == null || a.equals(b)) {
				continue;
			}
			adj.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
			adj.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
		}
		Set<String> result = new LinkedHashSet<>(wanted);
		List<Set<String>> comps = components(adj, result);
		if (comps.size() < 2) {
			return new ArrayList<>(result);
		}
		comps.sort(Comparator.<Set<String>>comparingInt(Set::size).reversed());
		Set<String> main = new LinkedHashSet<>(comps.get(0));
		for (int i = comps.size() - 1; i >= 1; i--) {
			List<String> path = shortestPath(adj, comps.get(i), main, permission);
			if (path == null) {
				continue; // no short path: leave it to the SQL step (and the error-time expansion)
			}
			main.addAll(comps.get(i));
			main.addAll(path);
			result.addAll(path);
		}
		return new ArrayList<>(result);
	}

	private static List<Set<String>> components(Map<String, Set<String>> adj, Set<String> nodes) {
		List<Set<String>> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String n : nodes) {
			if (!seen.add(n)) {
				continue;
			}
			Set<String> comp = new LinkedHashSet<>();
			java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(List.of(n));
			while (!q.isEmpty()) {
				String x = q.poll();
				comp.add(x);
				for (String y : adj.getOrDefault(x, Set.of())) {
					if (nodes.contains(y) && seen.add(y)) {
						q.add(y);
					}
				}
			}
			out.add(comp);
		}
		return out;
	}

	/** Intermediate tables of the shortest path from any table of {@code from} to any table of {@code to}. */
	private static List<String> shortestPath(Map<String, Set<String>> adj, Set<String> from, Set<String> to,
			DataPermission permission) {
		Map<String, String> parent = new HashMap<>();
		Map<String, Integer> depth = new HashMap<>();
		java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
		for (String f : from) {
			depth.put(f, 0);
			q.add(f);
		}
		while (!q.isEmpty()) {
			String x = q.poll();
			int d = depth.get(x);
			if (d >= MAX_BRIDGE_HOPS) {
				continue;
			}
			for (String y : adj.getOrDefault(x, Set.of())) {
				if (depth.containsKey(y)) {
					continue;
				}
				if (to.contains(y)) {
					List<String> mid = new ArrayList<>();
					for (String c = x; !from.contains(c); c = parent.get(c)) {
						mid.add(0, c);
					}
					return mid;
				}
				if (permission != null && !permission.allowsTable(y)) {
					continue;
				}
				depth.put(y, d + 1);
				parent.put(y, x);
				q.add(y);
			}
		}
		return null;
	}

	private Optional<Expansion> merge(Long agentId, Integer datasourceId, SchemaDTO schema, Set<String> present,
			List<String> added, List<LogicalRelation> relations) {
		List<Document> tableDocs = schemaService.getTableDocuments(datasourceId, added);
		List<Document> columnDocs = schemaService.getColumnDocumentsByTableName(datasourceId, added);
		SchemaDTO extra = new SchemaDTO();
		schemaService.buildSchemaFromDocuments(agentId.toString(), columnDocs, tableDocs, extra);
		if (extra.getTable() == null || extra.getTable().isEmpty()) {
			return Optional.empty();
		}
		SchemaDTO merged = new SchemaDTO();
		merged.setName(schema.getName());
		merged.setDescription(schema.getDescription());
		List<TableDTO> tables = new ArrayList<>(schema.getTable() == null ? List.of() : schema.getTable());
		tables.addAll(extra.getTable());
		merged.setTable(tables);
		merged.setTableCount(tables.size());
		Set<String> all = new LinkedHashSet<>(present);
		added.forEach(all::add);
		Set<String> fks = new LinkedHashSet<>(schema.getForeignKeys() == null ? List.of() : schema.getForeignKeys());
		for (LogicalRelation r : relations) {
			if (all.contains(r.getSourceTableName()) && all.contains(r.getTargetTableName())) {
				fks.add(String.format("%s.%s=%s.%s", r.getSourceTableName(), r.getSourceColumnName(),
						r.getTargetTableName(), r.getTargetColumnName()));
			}
		}
		merged.setForeignKeys(new ArrayList<>(fks));
		List<String> names = extra.getTable().stream().map(TableDTO::getName).toList();
		return Optional.of(new Expansion(merged, names));
	}

	/** Neighbours of the seeds not yet in the schema; a table linked to several seeds (a bridge) first. */
	static List<String> neighbours(List<LogicalRelation> relations, Set<String> seeds, Set<String> present,
			DataPermission permission) {
		Map<String, Integer> links = new HashMap<>();
		Map<String, Integer> firstSeen = new HashMap<>();
		int i = 0;
		for (LogicalRelation r : relations) {
			String other = null;
			if (seeds.contains(r.getSourceTableName())) {
				other = r.getTargetTableName();
			}
			else if (seeds.contains(r.getTargetTableName())) {
				other = r.getSourceTableName();
			}
			if (other == null || present.contains(other) || seeds.contains(other)) {
				continue;
			}
			if (permission != null && !permission.allowsTable(other)) {
				continue;
			}
			links.merge(other, 1, Integer::sum);
			firstSeen.putIfAbsent(other, i++);
		}
		return links.keySet()
			.stream()
			.sorted(Comparator.<String>comparingInt(t -> -links.get(t)).thenComparingInt(firstSeen::get))
			.limit(MAX_ADDED)
			.toList();
	}

	private static String bare(String table) {
		String t = table.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
		int dot = t.lastIndexOf('.');
		return dot >= 0 ? t.substring(dot + 1) : t;
	}

}
