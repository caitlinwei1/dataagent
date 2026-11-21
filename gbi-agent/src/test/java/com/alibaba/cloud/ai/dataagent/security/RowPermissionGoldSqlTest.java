package com.alibaba.cloud.ai.dataagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every gold / key SQL of the 320-question set rewrites without error for a restricted user, and the rewrite cost
 * per statement (printed) is small next to an LLM call. The result-level check against the database is
 * eval/data_permission/run_permission_eval.py.
 */
class RowPermissionGoldSqlTest {

	@Test
	void allGoldSqlRewrite() throws Exception {
		Path file = Path.of("../eval/nl2sql/questions.jsonl");
		List<String> sqls = new ArrayList<>();
		ObjectMapper om = new ObjectMapper();
		for (String line : Files.readAllLines(file)) {
			JsonNode q = om.readTree(line);
			String sql = q.hasNonNull("gold_sql") ? q.get("gold_sql").asText()
					: q.hasNonNull("key_sql") ? q.get("key_sql").asText() : null;
			if (sql != null) {
				sqls.add(sql);
			}
		}
		assertThat(sqls).hasSize(276);
		RowPermissionRewriter rw = RowPermissionRewriterTest.REWRITER;
		int changed = 0;
		for (String s : sqls) {
			if (rw.rewrite(s, RowPermissionRewriterTest.EAST).changed()) {
				changed++;
			}
		}
		int rounds = 20;
		long t0 = System.nanoTime();
		for (int i = 0; i < rounds; i++) {
			for (String s : sqls) {
				rw.rewrite(s, RowPermissionRewriterTest.EAST);
			}
		}
		double us = (System.nanoTime() - t0) / 1e3 / (rounds * sqls.size());
		System.out.printf("row rewrite: %d gold SQL, %d filtered, %.0f us per statement%n", sqls.size(), changed, us);
		assertThat(changed).isGreaterThan(100);
	}

}
