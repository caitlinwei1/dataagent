package com.alibaba.cloud.ai.dataagent.service.semantic;

import com.alibaba.cloud.ai.dataagent.security.DataPermission;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.GbiNl2sqlProperties;
import com.alibaba.cloud.ai.dataagent.service.semantic.MetricContractResolver.Resolved;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Entry point of the (small) semantic layer: resolve the metric definitions for a question and a user, carry them
 * through the graph state, check SQL against them, reconcile summary tables.
 */
@Component
public class SemanticLayer {

	private final MetricContracts contracts;

	private final MetricContractResolver resolver;

	private final GbiNl2sqlProperties properties;

	public SemanticLayer(GbiNl2sqlProperties properties) {
		this.properties = properties;
		String location = properties.getMetricContracts();
		this.contracts = location == null || location.isBlank() ? MetricContracts.empty()
				: MetricContracts.load(location);
		this.resolver = new MetricContractResolver(contracts);
	}

	public MetricContracts contracts() {
		return contracts;
	}

	public List<Resolved> resolve(String question, Collection<String> roles, DataPermission permission) {
		return resolver.resolve(question, roles, permission);
	}

	/** The state form (names only) back to resolved metrics with their rules. */
	@SuppressWarnings("unchecked")
	public List<Resolved> fromState(Object o) {
		List<Resolved> out = new ArrayList<>();
		if (o instanceof List<?> l) {
			for (Object x : l) {
				if (x instanceof Map<?, ?> m) {
					String metric = (String) m.get("metric");
					String variant = (String) m.get("variant");
					out.add(new Resolved(metric, variant, (String) m.get("reason"), (String) m.get("definition"),
							resolver.rulesOf(metric, variant)));
				}
			}
		}
		return out;
	}

	public static List<Map<String, Object>> toState(List<Resolved> resolved) {
		return new ArrayList<>(resolved.stream().map(Resolved::toState).toList());
	}

	@SuppressWarnings("unchecked")
	public static List<String> roles(Object o) {
		List<String> out = new ArrayList<>();
		if (o instanceof Collection<?> c) {
			c.forEach(x -> out.add(String.valueOf(x)));
		}
		return out;
	}

	public GbiNl2sqlProperties properties() {
		return properties;
	}

}
