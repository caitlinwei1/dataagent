package com.alibaba.cloud.ai.dataagent.security;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Table → owning data dimension + filter template, from {@code data-permission/row-policies.yml} (generated from
 * the dataset's metadata by scripts/security/gen_row_policies.py). RuoYi-Cloud-Plus declares this with
 * {@code @DataPermission/@DataColumn} on mapper methods; the GBI agent has no mappers for business tables (the LLM
 * writes the SQL), so the declaration moves from the code to the table.
 */
@Slf4j
@Component
public class RowPolicyRegistry {

	/** @param filter SQL boolean expression over the table's own columns; ":dim" expands to the allowed values */
	public record Policy(String table, String dim, String filter) {
	}

	private final Map<String, Policy> policies = new LinkedHashMap<>();

	private final Map<String, String> dimTypes = new LinkedHashMap<>();

	@org.springframework.beans.factory.annotation.Autowired
	public RowPolicyRegistry(
			@Value("${gbi.security.row-policies:classpath:data-permission/row-policies.yml}") String location) {
		this(load(location));
	}

	RowPolicyRegistry(Map<String, Object> doc) {
		Object dims = doc.get("dimensions");
		if (dims instanceof Map<?, ?> m) {
			m.forEach((k, v) -> dimTypes.put(String.valueOf(k), String.valueOf(v)));
		}
		Object tables = doc.get("tables");
		if (tables instanceof Map<?, ?> m) {
			m.forEach((k, v) -> {
				Map<?, ?> p = (Map<?, ?>) v;
				String t = String.valueOf(k).toLowerCase(Locale.ROOT);
				policies.put(t, new Policy(t, String.valueOf(p.get("dim")), String.valueOf(p.get("filter"))));
			});
		}
		log.info("row policies: {} tables, dimensions {}", policies.size(), dimTypes);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(String location) {
		try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
			return new Yaml().load(in);
		}
		catch (Exception e) {
			throw new IllegalStateException("cannot load row policies from " + location, e);
		}
	}

	public Policy get(String table) {
		return policies.get(table.toLowerCase(Locale.ROOT));
	}

	public boolean numeric(String dim) {
		return "number".equals(dimTypes.get(dim));
	}

	public Map<String, Policy> all() {
		return policies;
	}

}
