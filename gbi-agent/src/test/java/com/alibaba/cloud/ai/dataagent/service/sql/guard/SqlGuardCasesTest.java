package com.alibaba.cloud.ai.dataagent.service.sql.guard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Runs eval/sql_guard/cases.jsonl (legit analytic SQL + unsafe / wrong SQL) through the guard with the 86-table
 * whitelist and column catalog, and writes eval/sql_guard/results.json.
 */
class SqlGuardCasesTest {

	private static final ObjectMapper M = new ObjectMapper();

	@Test
	void casesBehaveAsLabelled() throws Exception {
		Path root = Path.of("..").toAbsolutePath().normalize();
		Map<String, Set<String>> columns = new HashMap<>();
		for (JsonNode t : M.readTree(root.resolve("dataset/metadata/tables.json").toFile())) {
			Set<String> cs = new HashSet<>();
			t.get("columns").forEach(c -> cs.add(c.get("name").asText()));
			columns.put(t.get("name").asText(), cs);
		}
		SqlGuard guard = new SqlGuard();
		List<String> wrong = new ArrayList<>();
		Map<String, int[]> byExpect = new LinkedHashMap<>(); // expect -> {total, matched}
		ArrayNode rows = M.createArrayNode();
		long t0 = System.nanoTime();
		for (String line : Files.readAllLines(root.resolve("eval/sql_guard/cases.jsonl"))) {
			JsonNode c = M.readTree(line);
			String expect = c.get("expect").asText();
			GuardResult r = guard.check(c.get("sql").asText(), "gbi_ecommerce", columns.keySet(), columns);
			String got = r.passed() ? "pass" : r.failedCheck().name();
			boolean match = got.equals(expect);
			int[] s = byExpect.computeIfAbsent(expect, k -> new int[2]);
			s[0]++;
			s[1] += match ? 1 : 0;
			if (!match) {
				wrong.add(c.get("id").asText() + " expect " + expect + " got " + got + " " + r.message());
			}
			ObjectNode row = rows.addObject();
			row.put("id", c.get("id").asText());
			row.put("expect", expect);
			row.put("got", got);
			row.put("errorClass", r.passed() ? null : r.errorClass().name());
			row.put("message", r.message());
		}
		double msPerCase = (System.nanoTime() - t0) / 1e6 / rows.size();
		ObjectNode out = M.createObjectNode();
		ObjectNode summary = out.putObject("summary");
		byExpect.forEach((k, v) -> summary.putObject(k).put("total", v[0]).put("matched", v[1]));
		summary.put("ms_per_case", msPerCase);
		out.set("cases", rows);
		M.writerWithDefaultPrettyPrinter().writeValue(root.resolve("eval/sql_guard/results.json").toFile(), out);
		assertTrue(wrong.isEmpty(), String.join("\n", wrong));
	}

}
