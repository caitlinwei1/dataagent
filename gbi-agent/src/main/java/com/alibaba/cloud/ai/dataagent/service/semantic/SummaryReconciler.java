package com.alibaba.cloud.ai.dataagent.service.semantic;

import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContracts.Reconcile;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Detail-vs-summary reconciliation for a SQL that read a summary table.
 *
 * <p>
 * The summary table is swapped for the same-definition aggregate over the detail table (a derived table with the
 * summary's alias), so the model's SQL runs unchanged on both; a second run keeps only cancelled / closed orders. Two
 * things come out: whether summary and detail agree (an ETL problem if not), and how much of the summary number comes
 * from orders closed after payment. In B6 that share was the root cause: the weekly paid amount of the summary table
 * fell 19.5%, but 55% of this week's amount was orders closed for stockout.
 */
public final class SummaryReconciler {

	private static final Set<String> AGGREGATES = Set.of("sum", "count", "avg", "min", "max");

	public record Outcome(String table, Map<String, double[]> columns, String note) {
	}

	private SummaryReconciler() {
	}

	/**
	 * @param sql the model's SQL (before permission rewriting)
	 * @param summaryRows rows the SQL returned
	 * @param run runs a SQL (with the caller's permission applied) and returns its rows
	 * @return a note when summary and detail disagree or the closed share is above the threshold
	 */
	public static Optional<Outcome> reconcile(String sql, List<Map<String, String>> summaryRows, List<Reconcile> specs,
			Function<String, List<Map<String, String>>> run) {
		if (sql == null || summaryRows == null || summaryRows.isEmpty()) {
			return Optional.empty();
		}
		for (Reconcile spec : specs) {
			Map<String, String> outputs = outputs(sql, spec);
			if (outputs.isEmpty()) {
				continue;
			}
			String detailSql = substitute(sql, spec, "");
			String closedSql = substitute(sql, spec, spec.closedFilter() == null ? "" : spec.closedFilter());
			if (detailSql == null || closedSql == null) {
				continue;
			}
			List<Map<String, String>> detail = run.apply(detailSql);
			List<Map<String, String>> closed = run.apply(closedSql);
			if (detail == null) {
				continue;
			}
			Map<String, double[]> cols = new LinkedHashMap<>();
			StringBuilder note = new StringBuilder();
			for (Map.Entry<String, String> o : outputs.entrySet()) {
				String label = o.getKey();
				double s = total(summaryRows, label), d = total(detail, label),
						c = closed == null ? 0 : total(closed, label);
				cols.put(label, new double[] { s, d, c });
				boolean mismatch = Math.abs(s - d) > Math.max(0.01, spec.mismatchThreshold() * Math.abs(s));
				double share = s == 0 ? 0 : c / s;
				if (mismatch) {
					note.append("；").append(label).append(" 汇总 ").append(fmt(s)).append(" 与明细重算 ").append(fmt(d))
						.append(" 不一致（差 ").append(pct((s - d) / (s == 0 ? 1 : s))).append("），汇总表可能未刷新或口径不同");
				}
				if (share > spec.closedShareThreshold()) {
					note.append("；").append(label).append(" 合计 ").append(fmt(s)).append(" 中，支付后被取消/关闭的订单贡献 ")
						.append(fmt(c)).append("（").append(pct(share)).append("）");
				}
			}
			if (note.length() == 0) {
				return Optional.of(new Outcome(spec.table(), cols, null));
			}
			String text = "【对账】本步用了汇总表 " + spec.table() + "，已按同口径从订单明细重算" + note + "。" + spec.explain() + "。";
			return Optional.of(new Outcome(spec.table(), cols, text));
		}
		return Optional.empty();
	}

	/** Output label -> summary column, for select items that aggregate a reconcilable column of the summary table. */
	static Map<String, String> outputs(String sql, Reconcile spec) {
		Map<String, String> out = new LinkedHashMap<>();
		Statement st;
		try {
			st = CCJSqlParserUtil.parse(sql);
		}
		catch (Exception e) {
			return out;
		}
		if (!(st instanceof Select sel) || !(sel.getSelectBody() instanceof PlainSelect ps)) {
			return out;
		}
		Set<String> aliases = new java.util.HashSet<>();
		boolean present = collectAliases(ps.getFromItem(), spec.table(), aliases);
		if (ps.getJoins() != null) {
			for (Join j : ps.getJoins()) {
				present |= collectAliases(j.getRightItem(), spec.table(), aliases);
			}
		}
		if (!present) {
			return out;
		}
		boolean single = ps.getJoins() == null || ps.getJoins().isEmpty();
		for (SelectItem<?> it : ps.getSelectItems()) {
			String col = aggregatedColumn(it.getExpression(), aliases, spec.columns(), single);
			if (col != null) {
				String label = it.getAlias() != null ? it.getAlias().getName().replace("`", "").replace("\"", "")
						: it.getExpression() instanceof Column c ? c.getColumnName().replace("`", "")
								: it.getExpression().toString();
				out.put(label, col);
			}
		}
		return out;
	}

	private static boolean collectAliases(FromItem item, String table, Set<String> aliases) {
		if (item instanceof Table t && t.getName() != null) {
			String n = t.getName().replace("`", "").toLowerCase(Locale.ROOT);
			n = n.substring(n.lastIndexOf('.') + 1);
			if (n.equals(table)) {
				aliases.add(n);
				if (t.getAlias() != null) {
					aliases.add(t.getAlias().getName().replace("`", "").toLowerCase(Locale.ROOT));
				}
				return true;
			}
		}
		return false;
	}

	private static String aggregatedColumn(net.sf.jsqlparser.expression.Expression e, Set<String> aliases,
			List<String> columns, boolean single) {
		List<String> found = new ArrayList<>();
		if (e instanceof Column c) { // a plain summary column (one row per day / shop): totals compare the same way
			String name = c.getColumnName().replace("`", "").toLowerCase(Locale.ROOT);
			String q = c.getTable() == null || c.getTable().getName() == null ? null
					: c.getTable().getName().replace("`", "").toLowerCase(Locale.ROOT);
			return columns.contains(name) && (q == null || aliases.contains(q)) ? name : null;
		}
		TablesNamesFinder<Void> f = new TablesNamesFinder<>() {
			int depth;

			{
				init(false);
			}

			@Override
			public <S> Void visit(net.sf.jsqlparser.expression.Function fn, S ctx) {
				boolean agg = fn.getName() != null && AGGREGATES.contains(fn.getName().toLowerCase(Locale.ROOT));
				if (agg) {
					depth++;
				}
				super.visit(fn, ctx);
				if (agg) {
					depth--;
				}
				return null;
			}

			@Override
			public <S> Void visit(Column c, S ctx) {
				if (depth > 0) {
					String name = c.getColumnName().replace("`", "").toLowerCase(Locale.ROOT);
					String q = c.getTable() == null || c.getTable().getName() == null ? null
							: c.getTable().getName().replace("`", "").toLowerCase(Locale.ROOT);
					if (columns.contains(name) && (q == null || aliases.contains(q))) {
						found.add(name);
					}
				}
				return null;
			}
		};
		try {
			e.accept(f, null);
		}
		catch (RuntimeException ex) {
			return null;
		}
		return found.isEmpty() ? null : found.get(0);
	}

	/** The SQL with every reference to the summary table replaced by the detail aggregate (same alias). */
	static String substitute(String sql, Reconcile spec, String extra) {
		try {
			Statement st = CCJSqlParserUtil.parse(sql);
			String detail = spec.detail().replace("{extra}", extra == null ? "" : extra);
			Set<PlainSelect> generated = Collections.newSetFromMap(new IdentityHashMap<>());
			int[] replaced = { 0 };
			TablesNamesFinder<Void> w = new TablesNamesFinder<>() {
				{
					init(false);
				}

				@Override
				public <S> Void visit(PlainSelect ps, S ctx) {
					if (generated.contains(ps)) {
						return null;
					}
					ps.setFromItem(wrap(ps.getFromItem()));
					if (ps.getJoins() != null) {
						for (Join j : ps.getJoins()) {
							j.setRightItem(wrap(j.getRightItem()));
						}
					}
					return super.visit(ps, ctx);
				}

				@Override
				public <S> Void visit(ParenthesedFromItem p, S ctx) {
					p.setFromItem(wrap(p.getFromItem()));
					return super.visit(p, ctx);
				}

				private FromItem wrap(FromItem item) {
					if (!(item instanceof Table t) || t.getName() == null) {
						return item;
					}
					String n = t.getName().replace("`", "").toLowerCase(Locale.ROOT);
					if (!n.substring(n.lastIndexOf('.') + 1).equals(spec.table())) {
						return item;
					}
					try {
						PlainSelect inner = ((Select) CCJSqlParserUtil.parse(detail)).getPlainSelect();
						generated.add(inner);
						ParenthesedSelect sub = new ParenthesedSelect();
						sub.setSelect(inner);
						sub.setAlias(t.getAlias() != null ? t.getAlias() : new Alias(t.getName(), true));
						replaced[0]++;
						return sub;
					}
					catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}
			};
			w.getTables(st);
			return replaced[0] == 0 ? null : st.toString();
		}
		catch (Exception e) {
			return null;
		}
	}

	static double total(List<Map<String, String>> rows, String label) {
		double sum = 0;
		for (Map<String, String> r : rows) {
			String v = null;
			for (Map.Entry<String, String> e : r.entrySet()) {
				if (e.getKey().equalsIgnoreCase(label) || e.getKey().endsWith("." + label)) {
					v = e.getValue();
					break;
				}
			}
			if (v == null) {
				continue;
			}
			try {
				sum += Double.parseDouble(v.replace(",", ""));
			}
			catch (NumberFormatException ignored) {
			}
		}
		return sum;
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%,.2f", v);
	}

	private static String pct(double v) {
		return String.format(Locale.ROOT, "%.1f%%", v * 100);
	}

}
