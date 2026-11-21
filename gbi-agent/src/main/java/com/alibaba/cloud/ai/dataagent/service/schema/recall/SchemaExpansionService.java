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
		log.info("schema_expand agent={} seeds={} added={}", agentId, seeds, names);
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
