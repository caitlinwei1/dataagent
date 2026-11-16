package com.alibaba.cloud.ai.dataagent.service.mode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Rules and cases, the two intervention mechanisms of the modes.
 *
 * <ul>
 * <li>Rule (Turbo and Mix): question keywords -> how the SQL must be written. Deterministic: a matching rule always
 * fires, so a business owner can fix a known mistake without touching the model.</li>
 * <li>Case (Mix): a past question with its correct SQL. The most similar cases are shown to the model as few-shot
 * examples, which generalises to questions no rule anticipated. Similarity is TF-IDF cosine over character bigrams:
 * deterministic, no embedding call, good enough for short Chinese business questions.</li>
 * </ul>
 */
@Slf4j
@Component
public class ModeKnowledge {

	public record Rule(String name, List<String> when, String sql) {
	}

	public record Case(String question, String sql) {
	}

	private final List<Rule> rules;

	private final List<Case> cases;

	private final List<Map<String, Double>> caseVectors = new ArrayList<>();

	private final Map<String, Double> idf = new HashMap<>();

	@org.springframework.beans.factory.annotation.Autowired
	public ModeKnowledge(GbiModeProperties props) {
		this(loadRules(props.getRulesFile()), loadCases(props.getCasesFile()));
	}

	ModeKnowledge(List<Rule> rules, List<Case> cases) {
		this.rules = rules;
		this.cases = cases;
		Map<String, Integer> df = new HashMap<>();
		List<Map<String, Integer>> tfs = cases.stream().map(c -> bigrams(c.question())).toList();
		tfs.forEach(tf -> tf.keySet().forEach(g -> df.merge(g, 1, Integer::sum)));
		df.forEach((g, n) -> idf.put(g, Math.log((1.0 + cases.size()) / (1.0 + n)) + 1));
		tfs.forEach(tf -> caseVectors.add(weigh(tf)));
		log.info("GBI mode knowledge: {} rules, {} cases", rules.size(), cases.size());
	}

	public List<Rule> rulesFor(String text) {
		return rules.stream().filter(r -> r.when().stream().anyMatch(text::contains)).toList();
	}

	public List<Case> casesFor(String text, int k) {
		if (cases.isEmpty() || k <= 0) {
			return List.of();
		}
		Map<String, Double> q = weigh(bigrams(text));
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < cases.size(); i++) {
			idx.add(i);
		}
		double[] sim = new double[cases.size()];
		for (int i = 0; i < cases.size(); i++) {
			sim[i] = cosine(q, caseVectors.get(i));
		}
		return idx.stream()
			.filter(i -> sim[i] > 0)
			.sorted(Comparator.comparingDouble((Integer i) -> -sim[i]).thenComparing(i -> i))
			.limit(k)
			.map(cases::get)
			.toList();
	}

	/** The evidence block for one question, or "" when nothing applies. */
	public String evidence(String text, boolean withRules, boolean withCases, int k) {
		StringBuilder sb = new StringBuilder();
		if (withRules) {
			List<Rule> hit = rulesFor(text);
			if (!hit.isEmpty()) {
				sb.append("【业务规则】（必须遵守）\n");
				hit.forEach(r -> sb.append("- ").append(r.name()).append("：").append(r.sql().strip()).append('\n'));
			}
		}
		if (withCases) {
			List<Case> hit = casesFor(text, k);
			if (!hit.isEmpty()) {
				sb.append("【相似问题的正确 SQL】（参考写法和口径，不要照抄条件）\n");
				for (Case c : hit) {
					sb.append("问题：").append(c.question()).append("\nSQL：").append(c.sql().strip()).append('\n');
				}
			}
		}
		return sb.toString();
	}

	private static Map<String, Integer> bigrams(String s) {
		Map<String, Integer> tf = new HashMap<>();
		String t = s.replaceAll("[\\s，。？?、,.]", "");
		for (int i = 0; i + 1 < t.length(); i++) {
			tf.merge(t.substring(i, i + 2), 1, Integer::sum);
		}
		return tf;
	}

	private Map<String, Double> weigh(Map<String, Integer> tf) {
		Map<String, Double> v = new HashMap<>();
		tf.forEach((g, n) -> v.put(g, n * idf.getOrDefault(g, 0.0)));
		return v;
	}

	private static double cosine(Map<String, Double> a, Map<String, Double> b) {
		double dot = 0, na = 0, nb = 0;
		for (Map.Entry<String, Double> e : a.entrySet()) {
			dot += e.getValue() * b.getOrDefault(e.getKey(), 0.0);
			na += e.getValue() * e.getValue();
		}
		for (double x : b.values()) {
			nb += x * x;
		}
		return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
	}

	@SuppressWarnings("unchecked")
	static List<Rule> loadRules(String location) {
		if (location == null || location.isBlank()) {
			return List.of();
		}
		try (InputStream in = resource(location).getInputStream()) {
			List<Map<String, Object>> raw = new Yaml().load(in);
			List<Rule> out = new ArrayList<>();
			for (Map<String, Object> r : raw == null ? List.<Map<String, Object>>of() : raw) {
				out.add(new Rule((String) r.get("name"), (List<String>) r.get("when"), (String) r.get("sql")));
			}
			return out;
		}
		catch (Exception e) {
			throw new IllegalStateException("cannot load rules " + location, e);
		}
	}

	static List<Case> loadCases(String location) {
		if (location == null || location.isBlank()) {
			return List.of();
		}
		ObjectMapper m = new ObjectMapper();
		try (InputStream in = resource(location).getInputStream()) {
			List<Case> out = new ArrayList<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank()) {
					JsonNode n = m.readTree(line);
					out.add(new Case(n.path("question").asText(), n.path("sql").asText()));
				}
			}
			return out;
		}
		catch (Exception e) {
			throw new IllegalStateException("cannot load cases " + location, e);
		}
	}

	private static Resource resource(String location) {
		return new DefaultResourceLoader().getResource(location.contains(":") ? location : "file:" + location);
	}

}
