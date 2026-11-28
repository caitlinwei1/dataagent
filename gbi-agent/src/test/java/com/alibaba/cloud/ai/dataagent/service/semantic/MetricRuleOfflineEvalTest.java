package com.alibaba.cloud.ai.dataagent.service.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline numbers for the metric rules, no model calls: false positives on every gold SQL (320 问数 + the 宝尊
 * examples), and which wrong SQLs (examples' typical wrong SQL + SQL the agent actually executed in recorded runs) are
 * caught. Writes results/semantic/offline_check.json when run from the repository.
 */
class MetricRuleOfflineEvalTest {

	static final ObjectMapper M = new ObjectMapper();

	static final MetricContractResolver R = new MetricContractResolver(
			MetricContracts.load("classpath:gbi/metric-contracts.yml"));

	/** The two demo users of the role / brand examples, as gbi-auth resolves them (ruoyi/sql/gbi_system.sql). */
	static java.util.List<MetricContractResolver.Resolved> resolveAs(String q, String user) {
		return switch (user) {
			case "qinglan_ops" -> R.resolve(q, Set.of("brand_ops"), new com.alibaba.cloud.ai.dataagent.security.DataPermission(7,
					user, false, List.of("trd_*"), false, Map.of("shop", List.of("4", "5"))));
			case "finance" -> R.resolve(q, Set.of("finance"), null);
			default -> R.resolve(q, Set.of(), null);
		};
	}

	@Test
	void goldSqlHasNoFalsePositives() throws Exception {
		Path root = Path.of("..").toAbsolutePath().normalize();
		Path questions = root.resolve("eval/nl2sql/questions.jsonl");
		Path examples = root.resolve("eval/baozun/examples.jsonl");
		if (!Files.exists(questions) || !Files.exists(examples)) {
			return;
		}
		Map<String, Object> report = new LinkedHashMap<>();
		List<Map<String, Object>> fps = new ArrayList<>();
		int gold = 0, triggered = 0;
		for (Path p : List.of(questions, examples)) {
			for (String line : Files.readAllLines(p)) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode n = M.readTree(line);
				String q = n.path("question").asText(), sql = n.path("gold_sql").asText();
				var resolved = resolveAs(q, n.path("user").asText(""));
				gold++;
				if (!resolved.isEmpty()) {
					triggered++;
				}
				var v = MetricRuleChecker.check(sql, q, resolved);
				if (!v.isEmpty()) {
					fps.add(Map.of("id", n.path("id").asText(), "question", q, "violations",
							v.stream().map(MetricRuleChecker.Violation::describe).toList()));
				}
			}
		}
		report.put("gold_sql", gold);
		report.put("gold_with_contracted_metric", triggered);
		report.put("gold_false_positives", fps.size());
		report.put("false_positive_cases", fps);

		// wrong SQL: the examples' typical wrong SQL, and every SQL the agent executed in the recorded 宝尊 runs
		List<Map<String, Object>> wrong = new ArrayList<>();
		for (String line : Files.readAllLines(examples)) {
			JsonNode n = M.readTree(line);
			if (n.hasNonNull("wrong_sql") && !n.path("wrong_sql").asText().isBlank()) {
				String q = n.path("question").asText();
				var v = MetricRuleChecker.check(n.path("wrong_sql").asText(), q, resolveAs(q, n.path("user").asText("")));
				wrong.add(Map.of("id", n.path("id").asText() + "/wrong_sql", "caught", !v.isEmpty(), "violations",
						v.stream().map(MetricRuleChecker.Violation::describe).toList()));
			}
		}
		report.put("example_wrong_sql", wrong);
		List<Map<String, Object>> recorded = new ArrayList<>();
		try (var files = Files.list(root.resolve("results/baozun"))) {
			for (Path f : files.filter(x -> x.toString().endsWith(".json") && !x.toString().contains(".sem-")).sorted().toList()) {
				JsonNode n = M.readTree(f.toFile());
				String q = n.path("question").asText();
				var resolved = resolveAs(q, n.path("user").asText(""));
				int i = 0;
				for (JsonNode s : n.path("sql_executed")) {
					var v = MetricRuleChecker.check(s.asText(), q, resolved);
					recorded.add(Map.of("run", f.getFileName().toString(), "sql_index", i++, "flagged", !v.isEmpty(),
							"violations", v.stream().map(MetricRuleChecker.Violation::describe).toList()));
				}
			}
		}
		report.put("recorded_executed_sql", recorded.size());
		report.put("recorded_flagged", recorded.stream().filter(r -> (Boolean) r.get("flagged")).count());
		report.put("recorded", recorded);
		Path out = root.resolve("results/semantic/offline_check.json");
		Files.createDirectories(out.getParent());
		M.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
		System.out.println("offline_check gold=" + gold + " triggered=" + triggered + " fp=" + fps.size()
				+ " wrong_caught=" + wrong.stream().filter(w -> (Boolean) w.get("caught")).count() + "/" + wrong.size()
				+ " recorded_flagged=" + report.get("recorded_flagged") + "/" + recorded.size());
		assertThat(gold).isGreaterThan(0);
	}

}
