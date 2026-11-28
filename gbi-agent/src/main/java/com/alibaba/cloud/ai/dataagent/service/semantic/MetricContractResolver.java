package com.alibaba.cloud.ai.dataagent.service.semantic;

import com.alibaba.cloud.ai.dataagent.security.DataPermission;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContracts.Brand;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContracts.Metric;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContracts.Rule;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContracts.Variant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which metric definitions apply to one question, for one user.
 *
 * <p>
 * A metric applies when one of its trigger words is in the question (a trigger inside a longer trigger of another
 * metric does not count: "销售收入" is 销售额, not 收入). Then the first variant whose brand / role matches replaces the
 * general definition. The brand comes from the question (exactly one brand named) or from the user's row permission
 * (every visible shop belongs to one brand): a brand operator asking "我们品牌的销售额" gets the brand's contract
 * definition without naming the brand. The role comes from the login (RuoYi role key).
 */
public final class MetricContractResolver {

	/** One metric as it applies to this question: the variant (null = general definition) and why it was chosen. */
	public record Resolved(String metric, String variant, String reason, String definition, List<Rule> rules) {

		public Map<String, Object> toState() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("metric", metric);
			m.put("variant", variant);
			m.put("reason", reason);
			m.put("definition", definition);
			return m;
		}

		/** One line for the evidence and the report. */
		public String describe() {
			return metric + "：" + (variant == null ? "通用口径" : "采用「" + variant + "」（" + reason + "）") + "。" + definition;
		}

	}

	private final MetricContracts contracts;

	public MetricContractResolver(MetricContracts contracts) {
		this.contracts = contracts;
	}

	public List<Resolved> resolve(String question, Collection<String> roles, DataPermission permission) {
		List<Resolved> out = new ArrayList<>();
		if (question == null || question.isBlank()) {
			return out;
		}
		List<Metric> hit = triggered(question);
		if (hit.isEmpty()) {
			return out;
		}
		String[] brand = brand(question, permission);
		Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(roles);
		for (Metric m : hit) {
			Variant chosen = null;
			String reason = null;
			for (Variant v : m.variants()) {
				boolean brandOk = v.brand() == null || v.brand().equals(brand[0]);
				boolean roleOk = v.role() == null || roleSet.contains(v.role());
				if (brandOk && roleOk && (v.brand() != null || v.role() != null)) {
					chosen = v;
					reason = v.brand() != null ? brand[1] : "你的角色是 " + v.role() + "，默认按该角色的口径";
					break;
				}
			}
			String scope = m.scope() == null ? "" : "适用范围：" + m.scope();
			if (chosen != null) {
				out.add(new Resolved(m.name(), chosen.name(), reason, join(chosen.definition(), scope), chosen.rules()));
			}
			else {
				out.add(new Resolved(m.name(), null, null, join(m.definition(), scope), m.rules()));
			}
		}
		return out;
	}

	private static String join(String definition, String scope) {
		return scope.isEmpty() ? definition : definition + scope;
	}

	/** Rules by metric name, for re-resolving what the state carries (the state holds names, not compiled rules). */
	public List<Rule> rulesOf(String metric, String variant) {
		for (Metric m : contracts.metrics()) {
			if (!m.name().equals(metric)) {
				continue;
			}
			if (variant == null) {
				return m.rules();
			}
			for (Variant v : m.variants()) {
				if (v.name().equals(variant)) {
					return v.rules();
				}
			}
		}
		return List.of();
	}

	List<Metric> triggered(String question) {
		String q = question.toLowerCase(Locale.ROOT);
		record Hit(Metric m, int start, int end) {
		}
		List<Hit> hits = new ArrayList<>();
		for (Metric m : contracts.metrics()) {
			if (m.questionPattern() != null && !m.questionPattern().matcher(question).find()) {
				continue;
			}
			for (String t : m.triggers()) {
				String tl = t.toLowerCase(Locale.ROOT);
				for (int i = q.indexOf(tl); i >= 0; i = q.indexOf(tl, i + 1)) {
					hits.add(new Hit(m, i, i + tl.length()));
				}
			}
		}
		List<Metric> out = new ArrayList<>();
		for (Hit h : hits) {
			boolean inside = hits.stream()
				.anyMatch(o -> o.m() != h.m() && o.start() <= h.start() && o.end() >= h.end()
						&& (o.end() - o.start()) > (h.end() - h.start()));
			if (!inside && !out.contains(h.m())) {
				out.add(h.m());
			}
		}
		return out;
	}

	/** [brand, reason] or [null, null]. */
	String[] brand(String question, DataPermission permission) {
		List<Brand> named = contracts.brands().stream().filter(b -> question.contains(b.name())).toList();
		if (named.size() == 1) {
			return new String[] { named.get(0).name(), "问题里指定了品牌 " + named.get(0).name() };
		}
		if (named.isEmpty() && permission != null && !permission.allRows()) {
			List<String> shops = permission.values("shop");
			if (!shops.isEmpty()) {
				for (Brand b : contracts.brands()) {
					if (!b.shops().isEmpty() && b.shops().containsAll(shops)) {
						return new String[] { b.name(), "你的数据权限只覆盖 " + b.name() + " 品牌的店铺" };
					}
				}
			}
		}
		return new String[] { null, null };
	}

	/** The block that goes in front of the evidence. Empty when no contracted metric is in the question. */
	public static String evidenceBlock(List<Resolved> resolved) {
		if (resolved.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(
				"【指标口径】以下口径由语义层按问题和你的身份解析，计算这些指标的 SQL 执行前会按它检查，必须遵守（注意各自的适用范围）：\n");
		for (Resolved r : resolved) {
			sb.append("- ").append(r.describe()).append("\n");
		}
		return sb.toString();
	}

}
