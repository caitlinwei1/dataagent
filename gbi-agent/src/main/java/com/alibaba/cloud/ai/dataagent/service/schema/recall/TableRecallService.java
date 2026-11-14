/*
 * GBI repro: multi-channel table recall (fixes b297203 ignoring the question text).
 */
package com.alibaba.cloud.ai.dataagent.service.schema.recall;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import com.alibaba.cloud.ai.dataagent.service.business.BusinessKnowledgeService;
import com.alibaba.cloud.ai.dataagent.service.datasource.DatasourceService;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.vo.BusinessKnowledgeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ranks the tables of a datasource for a question by fusing several channels with reciprocal rank fusion:
 * table-vector, column-vector, keyword (BM25), metric definitions (口径), enum values, join-path completion and multi-turn context.
 */
@Slf4j
@Service
public class TableRecallService {

	private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");

	private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");

	private static final Pattern ENUM_TERM = Pattern.compile("取值，对应 ");

	private final VectorStore vectorStore;

	private final DatabaseUtil databaseUtil;

	private final BusinessKnowledgeService businessKnowledgeService;

	private final GbiRecallProperties props;

	private final Map<Integer, Index> indexes = new ConcurrentHashMap<>();

	private final Map<Long, ValueIndex> valueIndexes = new ConcurrentHashMap<>();

	private final Map<Integer, Map<String, Set<String>>> adjacency = new ConcurrentHashMap<>();

	private final DatasourceService datasourceService;

	public TableRecallService(VectorStore vectorStore, DatabaseUtil databaseUtil,
			BusinessKnowledgeService businessKnowledgeService, GbiRecallProperties props,
			DatasourceService datasourceService) {
		this.datasourceService = datasourceService;
		this.vectorStore = vectorStore;
		this.databaseUtil = databaseUtil;
		this.businessKnowledgeService = businessKnowledgeService;
		this.props = props;
	}

	/** Channel switches for one call (defaults come from {@link GbiRecallProperties}). */
	public record Channels(boolean vector, boolean column, boolean keyword, boolean term, boolean value, boolean join,
			boolean multiTurn) {
	}

	public record Ranked(String table, double score, Map<String, Integer> ranks) {
	}

	public Channels defaults() {
		return new Channels(props.isVector(), props.isColumn(), props.isKeyword(), props.isTerm(), props.isValue(),
				props.isJoin(), props.isMultiTurn());
	}

	public boolean isEnabled() {
		return props.isEnabled();
	}

	/** Table documents in fused order, for SchemaRecallNode. */
	public List<Document> recallTableDocuments(Long agentId, Integer datasourceId, String query) {
		Index idx = index(datasourceId);
		return rank(agentId, datasourceId, query, List.of(), defaults()).stream()
			.limit(props.getTopK())
			.map(r -> idx.tableDocs.get(r.table()))
			.filter(Objects::nonNull)
			.toList();
	}

	/** Full ranking with per-channel ranks (used by the offline eval endpoint). */
	public List<Ranked> rank(Long agentId, Integer datasourceId, String query, List<String> context, Channels ch) {
		Index idx = index(datasourceId);
		String q = ch.multiTurn() && context != null && !context.isEmpty()
				? String.join("；", context) + "；" + query : query;
		Map<String, List<String>> lists = new LinkedHashMap<>();
		if (ch.vector()) {
			lists.put("vector", vectorTables(datasourceId, q));
		}
		if (ch.column()) {
			lists.put("column", columnTables(datasourceId, q));
		}
		if (ch.keyword()) {
			lists.put("keyword", idx.bm25(q));
		}
		if (ch.term()) {
			lists.put("term", termTables(q, glossary(agentId, idx)));
		}
		if (ch.value()) {
			lists.put("value", valueIndex(agentId, idx).match(q));
		}
		if (ch.join()) {
			// join-path completion: tables linked (logical FKs) to the leading candidates, e.g. the order header
			// or shop dimension a question needs only as a bridge and never names
			List<String> seeds = fuse(lists).stream().limit(props.getJoinSeeds()).map(Map.Entry::getKey).toList();
			lists.put("join", joinTables(datasourceId, seeds));
		}
		Map<String, Map<String, Integer>> ranks = new HashMap<>();
		lists.forEach((name, list) -> {
			for (int i = 0; i < list.size(); i++) {
				ranks.computeIfAbsent(list.get(i), k -> new LinkedHashMap<>()).put(name, i + 1);
			}
		});
		return fuse(lists).stream()
			.map(e -> new Ranked(e.getKey(), e.getValue(), ranks.get(e.getKey())))
			.toList();
	}

	/** Reciprocal rank fusion, ties broken by name for determinism. */
	private List<Map.Entry<String, Double>> fuse(Map<String, List<String>> lists) {
		Map<String, Double> score = new HashMap<>();
		lists.values().forEach(list -> {
			for (int i = 0; i < list.size(); i++) {
				score.merge(list.get(i), 1.0 / (props.getRrfK() + i + 1), Double::sum);
			}
		});
		return score.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.toList();
	}

	/** Metric/dimension definitions (口径) name the tables they are computed from. */
	private List<String> termTables(String q, List<GlossaryTerm> glossary) {
		String lq = q.toLowerCase(Locale.ROOT);
		Map<String, Integer> best = new HashMap<>();
		for (GlossaryTerm g : glossary) {
			int hit = g.names()
				.stream()
				.filter(nm -> nm.length() >= 2 && lq.contains(nm.toLowerCase(Locale.ROOT)))
				.mapToInt(String::length)
				.max()
				.orElse(0);
			for (int i = 0; hit > 0 && i < g.tables().size(); i++) {
				best.merge(g.tables().get(i), hit * 10 - i, Math::max);
			}
		}
		return best.keySet().stream().sorted(Comparator.<String>comparingInt(t -> -best.get(t)).thenComparing(t -> t)).toList();
	}

	private List<String> joinTables(Integer datasourceId, List<String> seeds) {
		Map<String, Set<String>> adj = adjacency(datasourceId);
		Map<String, Integer> links = new HashMap<>();
		for (String seed : seeds) {
			for (String n : adj.getOrDefault(seed, Set.of())) {
				if (!seeds.contains(n)) {
					links.merge(n, 1, Integer::sum);
				}
			}
		}
		// the seeds lead the list: an expansion list that left them out would hand every neighbour a vote the
		// seed itself lacks and push the seed down (measured: -13 pt Recall@5 on the full-comment schema).
		// Then a neighbour shared by several seeds (a bridge), then by seed order.
		List<String> out = new ArrayList<>(seeds);
		links.keySet()
			.stream()
			.sorted(Comparator.<String>comparingInt(t -> -links.get(t)).thenComparingInt(t -> firstSeed(adj, seeds, t))
				.thenComparing(t -> t))
			.forEach(out::add);
		return out;
	}

	private static int firstSeed(Map<String, Set<String>> adj, List<String> seeds, String t) {
		for (int i = 0; i < seeds.size(); i++) {
			if (adj.getOrDefault(seeds.get(i), Set.of()).contains(t)) {
				return i;
			}
		}
		return seeds.size();
	}

	private Map<String, Set<String>> adjacency(Integer datasourceId) {
		return adjacency.computeIfAbsent(datasourceId, id -> {
			Map<String, Set<String>> adj = new HashMap<>();
			for (LogicalRelation r : datasourceService.getLogicalRelations(id)) {
				if (r.getIsDeleted() != null && r.getIsDeleted() == 1) {
					continue;
				}
				adj.computeIfAbsent(r.getSourceTableName(), k -> new LinkedHashSet<>()).add(r.getTargetTableName());
				adj.computeIfAbsent(r.getTargetTableName(), k -> new LinkedHashSet<>()).add(r.getSourceTableName());
			}
			return adj;
		});
	}

	/** Original b297203 behaviour: first N table documents by filter only, question ignored. */
	public List<String> originalTables(Integer datasourceId, int topK) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		Filter.Expression f = b
			.and(b.eq(Constant.DATASOURCE_ID, datasourceId.toString()),
					b.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE))
			.build();
		return vectorStore
			.similaritySearch(SearchRequest.builder().query("default").topK(topK).similarityThreshold(0.0)
				.filterExpression(f).build())
			.stream()
			.map(d -> (String) d.getMetadata().get(DocumentMetadataConstant.NAME))
			.toList();
	}

	public void invalidate(Integer datasourceId) {
		indexes.remove(datasourceId);
		adjacency.remove(datasourceId);
		valueIndexes.clear();
	}

	// ------------------------------------------------------------------ channels

	private List<String> vectorTables(Integer datasourceId, String q) {
		return search(datasourceId, DocumentMetadataConstant.TABLE, q, props.getVectorTopK()).stream()
			.map(d -> (String) d.getMetadata().get(DocumentMetadataConstant.NAME))
			.distinct()
			.toList();
	}

	private List<String> columnTables(Integer datasourceId, String q) {
		return search(datasourceId, DocumentMetadataConstant.COLUMN, q, props.getColumnTopK()).stream()
			.map(d -> (String) d.getMetadata().get(DocumentMetadataConstant.TABLE_NAME))
			.distinct()
			.toList();
	}

	private List<Document> search(Integer datasourceId, String type, String q, int topK) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		Filter.Expression f = b
			.and(b.eq(Constant.DATASOURCE_ID, datasourceId.toString()), b.eq(DocumentMetadataConstant.VECTOR_TYPE, type))
			.build();
		return vectorStore.similaritySearch(
				SearchRequest.builder().query(q).topK(topK).similarityThreshold(0.0).filterExpression(f).build());
	}

	/** Metric / dimension / abbreviation terms (enum-value terms are left to the value channel). */
	private List<GlossaryTerm> glossary(Long agentId, Index idx) {
		if (agentId == null) {
			return List.of();
		}
		List<GlossaryTerm> out = new ArrayList<>();
		for (BusinessKnowledgeVO k : businessKnowledgeService.getKnowledge(agentId)) {
			String desc = Optional.ofNullable(k.getDescription()).orElse("");
			if (ENUM_TERM.matcher(desc).find()) {
				continue;
			}
			List<String> names = new ArrayList<>();
			names.add(k.getBusinessTerm());
			if (k.getSynonyms() != null) {
				Arrays.stream(k.getSynonyms().split("[,，]")).map(String::trim).filter(s -> !s.isEmpty()).forEach(names::add);
			}
			Set<String> tables = new LinkedHashSet<>();
			Matcher m = ASCII_WORD.matcher(desc.toLowerCase());
			while (m.find()) {
				if (idx.tableDocs.containsKey(m.group())) {
					tables.add(m.group());
				}
			}
			if (!tables.isEmpty()) {
				out.add(new GlossaryTerm(names, new ArrayList<>(tables)));
			}
		}
		return out;
	}

	record GlossaryTerm(List<String> names, List<String> tables) {
	}

	// ------------------------------------------------------------------ BM25 index over table/column metadata

	private Index index(Integer datasourceId) {
		return indexes.computeIfAbsent(datasourceId, this::buildIndex);
	}

	private Index buildIndex(Integer datasourceId) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		Index idx = new Index();
		for (String type : List.of(DocumentMetadataConstant.TABLE, DocumentMetadataConstant.COLUMN)) {
			Filter.Expression f = b
				.and(b.eq(Constant.DATASOURCE_ID, datasourceId.toString()),
						b.eq(DocumentMetadataConstant.VECTOR_TYPE, type))
				.build();
			List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
				.query("schema")
				.topK(100000)
				.similarityThreshold(0.0)
				.filterExpression(f)
				.build());
			for (Document d : docs) {
				Map<String, Object> md = d.getMetadata();
				String desc = String.valueOf(md.getOrDefault("description", ""));
				if (DocumentMetadataConstant.TABLE.equals(type)) {
					String name = (String) md.get(DocumentMetadataConstant.NAME);
					idx.tableDocs.put(name, d);
					idx.addText(name, name + " " + desc, 3);
				}
				else {
					String table = (String) md.get(DocumentMetadataConstant.TABLE_NAME);
					String col = (String) md.get(DocumentMetadataConstant.NAME);
					idx.addText(table, col + " " + desc, 1);
					idx.columns.add(new String[] { table, col, String.valueOf(md.getOrDefault("type", "")) });
				}
			}
		}
		idx.finish();
		log.info("Built recall index for datasource {}: {} tables, {} columns", datasourceId, idx.tableDocs.size(),
				idx.columns.size());
		return idx;
	}

	static List<String> tokens(String text) {
		List<String> out = new ArrayList<>();
		String lower = text.toLowerCase(Locale.ROOT);
		Matcher m = ASCII_WORD.matcher(lower);
		while (m.find()) {
			String w = m.group();
			out.add(w);
			if (w.contains("_")) {
				for (String p : w.split("_")) {
					if (p.length() > 1) {
						out.add(p);
					}
				}
			}
		}
		Matcher c = CJK_RUN.matcher(text);
		while (c.find()) {
			String run = c.group();
			for (int i = 0; i + 1 < run.length(); i++) {
				out.add(run.substring(i, i + 2));
			}
		}
		return out;
	}

	static class Index {

		final Map<String, Document> tableDocs = new LinkedHashMap<>();

		final List<String[]> columns = new ArrayList<>();

		final Map<String, Map<String, Integer>> tf = new HashMap<>();

		final Map<String, Integer> len = new HashMap<>();

		final Map<String, Integer> df = new HashMap<>();

		double avgLen;

		void addText(String table, String text, int weight) {
			Map<String, Integer> m = tf.computeIfAbsent(table, k -> new HashMap<>());
			for (String t : tokens(text)) {
				m.merge(t, weight, Integer::sum);
				len.merge(table, weight, Integer::sum);
			}
		}

		void finish() {
			tf.values().forEach(m -> m.keySet().forEach(t -> df.merge(t, 1, Integer::sum)));
			avgLen = len.values().stream().mapToInt(Integer::intValue).average().orElse(1);
		}

		List<String> bm25(String q) {
			double k1 = 1.2, b = 0.75;
			int n = tf.size();
			Map<String, Double> s = new HashMap<>();
			Set<String> qt = new LinkedHashSet<>(tokens(q));
			for (Map.Entry<String, Map<String, Integer>> e : tf.entrySet()) {
				double sc = 0;
				for (String t : qt) {
					Integer f = e.getValue().get(t);
					if (f == null) {
						continue;
					}
					double idf = Math.log(1 + (n - df.get(t) + 0.5) / (df.get(t) + 0.5));
					sc += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len.get(e.getKey()) / avgLen));
				}
				if (sc > 0) {
					s.put(e.getKey(), sc);
				}
			}
			return s.entrySet()
				.stream()
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
				.map(Map.Entry::getKey)
				.toList();
		}

	}

	// ------------------------------------------------------------------ enum value index

	private ValueIndex valueIndex(Long agentId, Index idx) {
		if (agentId == null) {
			return new ValueIndex();
		}
		return valueIndexes.computeIfAbsent(agentId, id -> buildValueIndex(id, idx));
	}

	private ValueIndex buildValueIndex(Long agentId, Index idx) {
		ValueIndex vi = new ValueIndex();
		DbConfigBO cfg = databaseUtil.getAgentDbConfig(agentId);
		Accessor accessor = databaseUtil.getAgentAccessor(agentId);
		int scanned = 0;
		for (String[] c : idx.columns) {
			String type = c[2].toLowerCase(Locale.ROOT);
			if (!(type.contains("char") || type.contains("text"))) {
				continue;
			}
			String col = c[1];
			if (col.endsWith("_no") || col.endsWith("_code") && !col.equals("code") || col.contains("remark")
					|| col.contains("phone") || col.contains("image") || col.contains("url") || col.equals("created_by")) {
				continue;
			}
			DbQueryParameter p = new DbQueryParameter();
			p.setSchema(cfg.getSchema());
			p.setSql("SELECT DISTINCT " + col + " FROM " + c[0] + " WHERE " + col + " IS NOT NULL LIMIT "
					+ (props.getMaxEnumDistinct() + 1));
			try {
				ResultSetBO rs = accessor.executeSqlAndReturnObject(cfg, p);
				scanned++;
				if (rs.getData() == null || rs.getData().size() > props.getMaxEnumDistinct()) {
					continue;
				}
				for (Map<String, String> row : rs.getData()) {
					String v = row.values().stream().findFirst().orElse(null);
					if (v != null && v.length() >= 2 && !v.matches("[0-9.\\-: ]+")) {
						vi.add(v, c[0]);
					}
				}
			}
			catch (Exception e) {
				log.debug("value index skip {}.{}: {}", c[0], col, e.getMessage());
			}
		}
		log.info("Built value index for agent {}: scanned {} columns, {} values", agentId, scanned, vi.size());
		return vi;
	}

	static class ValueIndex {

		/** value -> tables holding it, value length kept for ranking */
		final Map<String, Set<String>> values = new HashMap<>();

		void add(String value, String table) {
			values.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(table);
		}

		int size() {
			return values.size();
		}

		List<String> match(String q) {
			String lq = q.toLowerCase(Locale.ROOT);
			Map<String, Integer> best = new HashMap<>();
			Map<String, Integer> hits = new HashMap<>();
			values.forEach((v, tables) -> {
				if (lq.contains(v)) {
					// a value that lives in fewer tables is more specific
					int specificity = v.length() * 10 - tables.size();
					for (String t : tables) {
						best.merge(t, specificity, Math::max);
						hits.merge(t, 1, Integer::sum);
					}
				}
			});
			return best.keySet()
				.stream()
				.sorted(Comparator.<String>comparingInt(t -> -best.get(t)).thenComparing(t -> -hits.get(t)))
				.collect(Collectors.toList());
		}

	}

}
