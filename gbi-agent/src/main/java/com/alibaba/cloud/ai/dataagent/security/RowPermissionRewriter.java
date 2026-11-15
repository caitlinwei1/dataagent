package com.alibaba.cloud.ai.dataagent.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Row-level permission by SQL rewrite, after RuoYi-Cloud-Plus's PlusDataPermissionInterceptor: parse with JSqlParser,
 * find every place a restricted table is read, and add that user's filter there.
 *
 * <p>
 * Where the filter goes differs from RuoYi-Plus. RuoYi-Plus appends {@code AND <cond>} to the WHERE of each
 * PlainSelect (and the ON of joins), which is fine for its hand-written mapper SQL. LLM SQL has outer joins,
 * aliases, CTEs and nested subqueries; a condition in the WHERE of a LEFT JOIN turns it into an inner join, and the
 * condition has to be re-qualified with whatever alias the model chose. Here every restricted table reference
 * {@code trd_order o} is replaced in place by {@code (SELECT * FROM trd_order WHERE shop_id IN (...)) AS o}: the
 * filter is written against the bare table, so it never needs the outer alias, and the outer query's join / null
 * semantics are untouched. MySQL 8 and PostgreSQL both merge such derived tables back into the plan.
 *
 * <p>
 * Coverage uses the same TablesNamesFinder walk as SqlGuard, so a table the guard sees is a table this rewrites.
 * Anything the walk finds but could not replace makes the rewrite fail, and the caller does not execute (fail
 * closed).
 */
@Component
public class RowPermissionRewriter {

	public record Result(String sql, List<String> filteredTables, boolean changed) {
	}

	public static class RewriteException extends RuntimeException {

		public RewriteException(String msg) {
			super(msg);
		}

	}

	private static final Pattern NUMBER = Pattern.compile("-?\\d{1,19}");

	private final RowPolicyRegistry registry;

	public RowPermissionRewriter(RowPolicyRegistry registry) {
		this.registry = registry;
	}

	/** @param perm null or allRows = no row restriction, the SQL is returned unchanged */
	public Result rewrite(String sql, DataPermission perm) {
		if (perm == null || perm.allRows()) {
			return new Result(sql, List.of(), false);
		}
		Statement st;
		try {
			st = CCJSqlParserUtil.parse(sql);
		}
		catch (JSQLParserException e) {
			throw new RewriteException("无法解析，不能附加数据权限");
		}
		if (!(st instanceof Select)) {
			throw new RewriteException("只有 SELECT 能附加数据权限");
		}
		Walker w = new Walker(perm);
		w.getTables(st);
		if (!w.missed.isEmpty()) {
			throw new RewriteException("无法为这些位置的表附加数据权限：" + String.join(", ", w.missed));
		}
		if (w.filtered.isEmpty()) {
			return new Result(sql, List.of(), false);
		}
		return new Result(st.toString(), new ArrayList<>(w.filtered), true);
	}

	/** The filter for one table with the user's values filled in; "1 = 0" when the user has none. */
	String condition(RowPolicyRegistry.Policy p, DataPermission perm) {
		List<String> literals = new ArrayList<>();
		boolean numeric = registry.numeric(p.dim());
		for (String v : perm.values(p.dim())) {
			if (numeric) {
				if (NUMBER.matcher(v).matches()) {
					literals.add(v);
				}
			}
			else {
				literals.add("'" + v.replace("'", "''") + "'");
			}
		}
		if (literals.isEmpty()) {
			return "1 = 0";
		}
		return p.filter().replace(":" + p.dim(), String.join(", ", literals));
	}

	private final class Walker extends TablesNamesFinder<Void> {

		private final DataPermission perm;

		/** Selects this walker generated: not walked again, so their tables are not wrapped twice. */
		private final Set<PlainSelect> generated = Collections.newSetFromMap(new IdentityHashMap<>());

		final Set<String> filtered = new LinkedHashSet<>();

		final Set<String> missed = new LinkedHashSet<>();

		Walker(DataPermission perm) {
			this.perm = perm;
			init(false);
		}

		@Override
		public <S> Void visit(PlainSelect ps, S ctx) {
			if (generated.contains(ps)) {
				return null;
			}
			ps.setFromItem(wrap(ps.getFromItem()));
			wrapJoins(ps.getJoins());
			return super.visit(ps, ctx);
		}

		@Override
		public <S> Void visit(ParenthesedFromItem p, S ctx) {
			p.setFromItem(wrap(p.getFromItem()));
			wrapJoins(p.getJoins());
			return super.visit(p, ctx);
		}

		/** Reached only for a table that was not replaced (a position this walker does not handle). */
		@Override
		public <S> Void visit(Table t, S ctx) {
			if (t.getName() != null && registry.get(unquote(t.getName())) != null) {
				missed.add(t.getFullyQualifiedName());
			}
			return super.visit(t, ctx);
		}

		private void wrapJoins(List<Join> joins) {
			if (joins != null) {
				for (Join j : joins) {
					j.setRightItem(wrap(j.getRightItem()));
				}
			}
		}

		private FromItem wrap(FromItem item) {
			if (!(item instanceof Table t) || t.getName() == null) {
				return item;
			}
			String name = unquote(t.getName()).toLowerCase(Locale.ROOT);
			RowPolicyRegistry.Policy p = registry.get(name);
			if (p == null) {
				return item;
			}
			String table = t.getSchemaName() == null ? t.getName() : t.getSchemaName() + "." + t.getName();
			PlainSelect inner;
			try {
				inner = ((Select) CCJSqlParserUtil
					.parse("SELECT * FROM " + table + " WHERE " + condition(p, perm))).getPlainSelect();
			}
			catch (JSQLParserException e) {
				throw new RewriteException("数据权限条件无法解析：" + name);
			}
			generated.add(inner);
			ParenthesedSelect sub = new ParenthesedSelect();
			sub.setSelect(inner);
			sub.setAlias(t.getAlias() != null ? t.getAlias() : new Alias(t.getName(), true));
			filtered.add(name);
			return sub;
		}

	}

	private static String unquote(String s) {
		return s.replace("`", "").replace("\"", "");
	}

}
