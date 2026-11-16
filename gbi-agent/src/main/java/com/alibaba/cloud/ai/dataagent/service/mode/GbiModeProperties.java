package com.alibaba.cloud.ai.dataagent.service.mode;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Answer modes modelled on XiYan GBI's Turbo / Mix editions ({@code gbi.mode.*}).
 *
 * <p>
 * GBI documents Turbo as "响应迅速，适合开箱即用的通用查询，允许通过规则干预输出" and Mix as "支持业务定制和结果干预，精度更高，
 * 允许通过案例学习提升处理能力". Here a mode is a named profile: which chat model every node uses, which LLM checks run,
 * how many SQL rewrites are allowed, and whether rules (问法 -> SQL 写法) and/or cases (similar question + SQL as few-shot)
 * are added to the evidence. A request picks a profile by name; extra profiles (e.g. ablations) are plain config.
 */
@Data
@Component
@ConfigurationProperties(prefix = "gbi.mode")
public class GbiModeProperties {

	/** Profile used when a request names none. "default" keeps the upstream behaviour. */
	private String defaultMode = "default";

	/** Rules file (YAML list of {name, when, sql}); classpath: or file path. */
	private String rulesFile = "classpath:gbi/rules.yaml";

	/** Cases file (JSON lines of {question, sql}); empty = no case library. */
	private String casesFile = "";

	private Map<String, Profile> profiles = new LinkedHashMap<>(Map.of("default", new Profile(), "turbo",
			Profile.turbo(), "mix", Profile.mix()));

	public Profile resolve(String name) {
		Profile p = profiles.get(name == null || name.isBlank() ? defaultMode : name);
		if (p == null) {
			throw new IllegalArgumentException("unknown mode: " + name + ", known: " + profiles.keySet());
		}
		return p;
	}

	@Data
	public static class Profile {

		/** Chat model for every LLM call of the run; empty = the active model in model config. */
		private String model = "";

		/** DashScope enable_thinking; null = the model's default. Turbo turns it off: hybrid models think by default. */
		private Boolean thinking;

		/** Run the LLM feasibility assessment before planning. */
		private boolean feasibility = true;

		/** Run the LLM semantic-consistency check before executing SQL (the deterministic guard always runs). */
		private boolean semanticCheck = true;

		/** SQL generation attempts per step; 0 = spring.ai.alibaba.data-agent.max-sql-retry-count. */
		private int maxSqlRetries = 0;

		/** Add matching rules (问法 -> SQL 写法) to the evidence. */
		private boolean rules = false;

		/** Add the most similar cases (question + SQL) to the evidence as few-shot examples. */
		private boolean cases = false;

		private int casesTopK = 3;

		static Profile turbo() {
			Profile p = new Profile();
			p.model = "qwen3.7-flash";
			p.thinking = false;
			p.feasibility = false;
			p.semanticCheck = false;
			p.maxSqlRetries = 3;
			p.rules = true;
			return p;
		}

		static Profile mix() {
			Profile p = new Profile();
			p.model = "qwen-plus";
			p.rules = true;
			p.cases = true;
			return p;
		}

	}

}
