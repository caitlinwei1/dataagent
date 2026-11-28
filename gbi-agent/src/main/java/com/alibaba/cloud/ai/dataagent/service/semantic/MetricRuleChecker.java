package com.alibaba.cloud.ai.dataagent.service.semantic;

import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContractResolver.Resolved;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContracts.Rule;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks one SQL against the metric rules that apply to the question, before it runs.
 *
 * <p>
 * The terms in the evidence tell the model the definition; the model can still ignore it (B5: GMV filtered on
 * order_status; B6: 销售额 taken from the daily summary table that includes orders closed after payment). These rules
 * make the part of a definition that can be read off the SQL a hard check: which columns may be the source, which
 * columns may not be filtered on, which must be considered. A column is {@code table.column}; aliases are resolved,
 * and an unqualified column counts for every table of the SQL that has a rule on that column name.
 */
public final class MetricRuleChecker {

	private static final Set<String> AGGREGATES = Set.of("sum", "avg", "count", "min", "max");

	public record Violation(String metric, String variant, String message, String hint, Rule rule) {

		public String describe() {
			return "[" + metric + (variant == null ? "" : "/" + variant) + "] " + message
					+ (hint == null ? "" : "。" + hint);
		}

	}

	/** What the SQL references, resolved to tables. */
	record Refs(String text, Set<String> tables, Set<String> all, Set<String> aggregated, Set<String> filtered,
			Set<String> unqualifiedAll, Set<String> unqualifiedAggregated, Set<String> unqualifiedFiltered) {

		boolean has(Set<String> qualified, Set<String> unqualified, String tableDotColumn) {
			if (qualified.contains(tableDotColumn)) {
				return true;
			}
			int i = tableDotColumn.indexOf('.');
			return unqualified.contains(tableDotColumn.substring(i + 1)) && tables.contains(tableDotColumn.substring(0, i));
		}

		boolean references(String c) {
			return has(all, unqualifiedAll, c);
		}

		boolean aggregates(String c) {
			return has(aggregated, unqualifiedAggregated, c);
		}

		boolean filters(String c) {
			return has(filtered, unqualifiedFiltered, c);
		}

	}

	private MetricRuleChecker() {
	}

	/** Empty when the SQL passes, or cannot be parsed (the SQL guard reports syntax). */
	public static List<Violation> check(String sql, String question, List<Resolved> resolved) {
		List<Violation> out = new ArrayList<>();
		if (resolved == null || resolved.isEmpty() || sql == null || sql.isBlank()) {
			return out;
		}
		Refs refs = refs(sql);
		if (refs == null) {
			return out;
		}
		for (Resolved r : resolved) {
			for (Rule rule : r.rules()) {
				out.addAll(check(refs, question, r.metric(), r.variant(), rule));
			}
		}
		return out;
	}

	static List<Violation> check(Refs refs, String question, String metric, String variant, Rule rule) {
		List<Violation> out = new ArrayList<>();
		if (rule.questionPattern() != null && (question == null || !rule.questionPattern().matcher(question).find())) {
			return out;
		}
		if (!rule.whenAggregates().isEmpty() && rule.whenAggregates().stream().noneMatch(refs::aggregates)) {
			return out;
		}
		for (String c : rule.forbidSources()) {
			if (refs.references(c)) {
				out.add(new Violation(metric, variant, "用了不符合口径的字段 " + c, rule.hint(), rule));
			}
		}
		for (String c : rule.forbidFilters()) {
			if (refs.filters(c)) {
				out.add(new Violation(metric, variant, "对 " + c + " 加了口径不允许的过滤条件", rule.hint(), rule));
			}
		}
		for (String c : rule.requireMention()) {
			if (!refs.references(c)) {
				out.add(new Violation(metric, variant, "没有处理口径要求的 " + c, rule.hint(), rule));
			}
		}
		for (String lit : rule.requireLiteral()) {
			if (!refs.text().contains(lit.replaceAll("\\s+", ""))) {
				out.add(new Violation(metric, variant, "缺少口径要求的 " + lit, rule.hint(), rule));
			}
		}
		if (!rule.requireAnySource().isEmpty() && rule.requireAnySource().stream().noneMatch(refs::references)) {
			out.add(new Violation(metric, variant, "数据来源不对，应来自 " + String.join(" 或 ", rule.requireAnySource()),
					rule.hint(), rule));
		}
		return out;
	}

	/**
	 * Deterministic repair for "a required condition is missing": adds each violated rule's {@code fix.where} to the
	 * WHERE of every SELECT that reads {@code fix.table}, with that SELECT's alias for the table. Returns empty when
	 * some violation has no fix (wrong table / wrong column is not repaired mechanically) or the SQL cannot be parsed.
	 */
	public static java.util.Optional<String> autoFix(String sql, List<Violation> violations) {
		if (violations.isEmpty() || violations.stream().anyMatch(v -> v.rule() == null || v.rule().fixWhere() == null)) {
			return java.util.Optional.empty();
		}
		try {
			Statement st = CCJSqlParserUtil.parse(sql);
			java.util.Set<Rule> rules = new java.util.LinkedHashSet<>();
			violations.forEach(v -> rules.add(v.rule()));
			int[] added = { 0 };
			TablesNamesFinder<Void> w = new TablesNamesFinder<>() {
				{
					init(false);
				}

				@Override
				public <S> Void visit(PlainSelect ps, S ctx) {
					for (Rule r : rules) {
						String alias = aliasOf(ps, r.fixTable());
						if (alias == null) {
							continue;
						}
						try {
							String cond = r.fixWhere().replace("{t}", alias);
							ps.setWhere(CCJSqlParserUtil.parseCondExpression(
									ps.getWhere() == null ? cond : "(" + ps.getWhere() + ") AND " + cond));
							added[0]++;
						}
						catch (Exception e) {
							throw new IllegalStateException(e);
						}
					}
					return super.visit(ps, ctx);
				}
			};
			w.getTables(st);
			return added[0] == 0 ? java.util.Optional.empty() : java.util.Optional.of(st.toString());
		}
		catch (Exception e) {
			return java.util.Optional.empty();
		}
	}

	private static String aliasOf(PlainSelect ps, String table) {
		List<net.sf.jsqlparser.statement.select.FromItem> items = new ArrayList<>();
		items.add(ps.getFromItem());
		if (ps.getJoins() != null) {
			ps.getJoins().forEach(j -> items.add(j.getRightItem()));
		}
		for (var it : items) {
			if (it instanceof Table t && t.getName() != null) {
				String n = unquote(t.getName()).toLowerCase(Locale.ROOT);
				if (n.substring(n.lastIndexOf('.') + 1).equals(table)) {
					return t.getAlias() != null ? t.getAlias().getName() : t.getName();
				}
			}
		}
		return null;
	}

	static Refs refs(String sql) {
		Statement st;
		try {
			st = CCJSqlParserUtil.parse(sql);
		}
		catch (Exception e) {
			return null;
		}
		Walker w = new Walker();
		try {
			w.getTables(st);
		}
		catch (RuntimeException e) {
			return null;
		}
		Set<String> tables = new LinkedHashSet<>(w.alias.values());
		Set<String> all = new LinkedHashSet<>(), agg = new LinkedHashSet<>(), filt = new LinkedHashSet<>();
		Set<String> uAll = new LinkedHashSet<>(), uAgg = new LinkedHashSet<>(), uFilt = new LinkedHashSet<>();
		for (Ref r : w.refs) {
			String name = unquote(r.column.getColumnName()).toLowerCase(Locale.ROOT);
			Table t = r.column.getTable();
			String table = null;
			if (t != null && t.getName() != null) {
				table = w.alias.get(unquote(t.getName()).toLowerCase(Locale.ROOT));
				if (table == null) {
					continue; // alias of a derived table / CTE: not a base column
				}
			}
			if (table == null) {
				uAll.add(name);
				if (r.inAggregate) {
					uAgg.add(name);
				}
				if (r.inFilter) {
					uFilt.add(name);
				}
			}
			else {
				String q = table + "." + name;
				all.add(q);
				if (r.inAggregate) {
					agg.add(q);
				}
				if (r.inFilter) {
					filt.add(q);
				}
			}
		}
		return new Refs(sql.replaceAll("\\s+", ""), tables, all, agg, filt, uAll, uAgg, uFilt);
	}

	private static String unquote(String s) {
		return s.replace("`", "").replace("\"", "");
	}

	private record Ref(Column column, boolean inAggregate, boolean inFilter) {
	}

	/** Columns with two flags: inside an aggregate function, inside a WHERE / JOIN ON / HAVING. */
	private static final class Walker extends TablesNamesFinder<Void> {

		final Map<String, String> alias = new HashMap<>();

		final List<Ref> refs = new ArrayList<>();

		private int aggregateDepth;

		/** Column nodes under a WHERE / JOIN ON / HAVING, by identity. */
		private final Set<Column> filterColumns = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

		Walker() {
			init(false);
		}

		@Override
		public <S> Void visit(Table table, S ctx) {
			if (table.getName() != null) {
				String name = unquote(table.getName()).toLowerCase(Locale.ROOT);
				name = name.substring(name.lastIndexOf('.') + 1);
				alias.put(name, name);
				if (table.getAlias() != null) {
					alias.put(unquote(table.getAlias().getName()).toLowerCase(Locale.ROOT), name);
				}
			}
			return super.visit(table, ctx);
		}

		@Override
		public <S> Void visit(PlainSelect ps, S ctx) {
			List<Expression> roots = new ArrayList<>();
			roots.add(ps.getWhere());
			roots.add(ps.getHaving());
			if (ps.getJoins() != null) {
				for (Join j : ps.getJoins()) {
					if (j.getOnExpressions() != null) {
						roots.addAll(j.getOnExpressions());
					}
				}
			}
			for (Expression e : roots) {
				if (e != null) {
					ColumnCollector cc = new ColumnCollector();
					cc.collect(e);
					filterColumns.addAll(cc.columns);
				}
			}
			return super.visit(ps, ctx);
		}

		@Override
		public <S> Void visit(Function f, S ctx) {
			String n = f.getName() == null ? "" : unquote(f.getName()).toLowerCase(Locale.ROOT);
			boolean agg = AGGREGATES.contains(n);
			if (agg) {
				aggregateDepth++;
			}
			try {
				return super.visit(f, ctx);
			}
			finally {
				if (agg) {
					aggregateDepth--;
				}
			}
		}

		@Override
		public <S> Void visit(Column c, S ctx) {
			refs.add(new Ref(c, aggregateDepth > 0, filterColumns.contains(c)));
			return super.visit(c, ctx);
		}

	}

	/** All column nodes under one expression (including sub-selects in it). */
	private static final class ColumnCollector extends TablesNamesFinder<Void> {

		final List<Column> columns = new ArrayList<>();

		ColumnCollector() {
			init(false);
		}

		void collect(Expression e) {
			e.accept(this, null);
		}

		@Override
		public <S> Void visit(Column c, S ctx) {
			columns.add(c);
			return super.visit(c, ctx);
		}

	}

}
