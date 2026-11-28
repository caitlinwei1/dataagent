package com.alibaba.cloud.ai.dataagent.service.semantic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The metric contracts file (classpath {@code gbi/metric-contracts.yml} by default): per metric, the trigger words,
 * the definition, the deterministic SQL rules, and the brand / role variants; plus the summary-table reconciliation
 * specs. Plain records so the resolved form can be put into the graph state as maps.
 */
@Slf4j
public final class MetricContracts {

	public record Rule(Pattern questionPattern, List<String> whenAggregates, List<String> forbidSources,
			List<String> forbidFilters, List<String> requireMention, List<String> requireAnySource, List<String> requireLiteral, String hint,
			String fixTable, String fixWhere) {
	}

	public record Variant(String name, String brand, String role, String definition, List<Rule> rules) {
	}

	public record Metric(String name, List<String> triggers, Pattern questionPattern, String definition,
			String scope, List<Rule> rules, List<Variant> variants) {
	}

	public record Brand(String name, List<String> shops) {
	}

	public record Reconcile(String table, String detail, String closedFilter, List<String> columns,
			double closedShareThreshold, double mismatchThreshold, String explain) {
	}

	private final List<Metric> metrics;

	private final List<Brand> brands;

	private final List<Reconcile> reconciles;

	public MetricContracts(List<Metric> metrics, List<Brand> brands, List<Reconcile> reconciles) {
		this.metrics = metrics;
		this.brands = brands;
		this.reconciles = reconciles;
	}

	public static MetricContracts empty() {
		return new MetricContracts(List.of(), List.of(), List.of());
	}

	public List<Metric> metrics() {
		return metrics;
	}

	public List<Brand> brands() {
		return brands;
	}

	public List<Reconcile> reconciles() {
		return reconciles;
	}

	public static MetricContracts load(String location) {
		try {
			Resource r = new DefaultResourceLoader().getResource(location);
			if (!r.exists()) {
				log.warn("metric contracts not found at {}", location);
				return empty();
			}
			try (InputStream in = r.getInputStream()) {
				return parse(new Yaml().load(in));
			}
		}
		catch (Exception e) {
			log.error("cannot load metric contracts {}: {}", location, e.getMessage());
			return empty();
		}
	}

	@SuppressWarnings("unchecked")
	static MetricContracts parse(Object root) {
		Map<String, Object> m = root instanceof Map ? (Map<String, Object>) root : Map.of();
		List<Metric> metrics = new ArrayList<>();
		for (Map<String, Object> x : maps(m.get("metrics"))) {
			List<Variant> variants = new ArrayList<>();
			for (Map<String, Object> v : maps(x.get("variants"))) {
				Map<String, Object> when = v.get("when") instanceof Map ? (Map<String, Object>) v.get("when") : Map.of();
				variants.add(new Variant(str(v.get("name")), str(when.get("brand")), str(when.get("role")),
						str(v.get("definition")), rules(v.get("rules"))));
			}
			metrics.add(new Metric(str(x.get("name")), strings(x.get("triggers")), pattern(x.get("question_pattern")),
					str(x.get("definition")), str(x.get("scope")), rules(x.get("rules")), variants));
		}
		List<Brand> brands = new ArrayList<>();
		for (Map<String, Object> b : maps(m.get("brands"))) {
			brands.add(new Brand(str(b.get("name")), strings(b.get("shops"))));
		}
		List<Reconcile> rec = new ArrayList<>();
		for (Map<String, Object> r : maps(m.get("reconcile"))) {
			rec.add(new Reconcile(lower(str(r.get("table"))), str(r.get("detail")), str(r.get("closed_filter")),
					strings(r.get("columns")).stream().map(MetricContracts::lower).toList(),
					num(r.get("closed_share_threshold"), 0.05), num(r.get("mismatch_threshold"), 0.005),
					str(r.get("explain"))));
		}
		return new MetricContracts(metrics, brands, rec);
	}

	@SuppressWarnings("unchecked")
	private static List<Rule> rules(Object o) {
		List<Rule> out = new ArrayList<>();
		for (Map<String, Object> r : maps(o)) {
			Map<String, Object> fix = r.get("fix") instanceof Map ? (Map<String, Object>) r.get("fix") : Map.of();
			out.add(new Rule(pattern(r.get("question_pattern")), cols(r.get("when_aggregates")),
					cols(r.get("forbid_sources")), cols(r.get("forbid_filters")), cols(r.get("require_mention")),
					cols(r.get("require_any_source")), strings(r.get("require_literal")), str(r.get("hint")),
					lower(str(fix.get("table"))), str(fix.get("where"))));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> maps(Object o) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (o instanceof List<?> l) {
			for (Object x : l) {
				if (x instanceof Map<?, ?> mm) {
					out.add(new LinkedHashMap<>((Map<String, Object>) mm));
				}
			}
		}
		return out;
	}

	private static List<String> strings(Object o) {
		List<String> out = new ArrayList<>();
		if (o instanceof List<?> l) {
			l.forEach(x -> out.add(String.valueOf(x)));
		}
		return out;
	}

	private static List<String> cols(Object o) {
		return strings(o).stream().map(MetricContracts::lower).toList();
	}

	private static Pattern pattern(Object o) {
		return o == null ? null : Pattern.compile(String.valueOf(o));
	}

	private static String str(Object o) {
		return o == null ? null : String.valueOf(o).strip();
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	private static double num(Object o, double def) {
		return o instanceof Number n ? n.doubleValue() : def;
	}

}
