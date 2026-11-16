package com.alibaba.cloud.ai.dataagent.service.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModeKnowledgeTest {

	@Test
	void shippedRulesLoadAndFireOnKeywords() {
		List<ModeKnowledge.Rule> rules = ModeKnowledge.loadRules("classpath:gbi/rules.yaml");
		assertTrue(rules.size() >= 5);
		ModeKnowledge k = new ModeKnowledge(rules, List.of());
		List<String> names = k.rulesFor("2026年3月数码家电在华东的销售额").stream().map(ModeKnowledge.Rule::name).toList();
		assertTrue(names.containsAll(List.of("类目名称", "大区归属", "有效订单", "审计时间")), names.toString());
		assertTrue(k.evidence("各一级类目的GMV", true, false, 3).startsWith("【业务规则】"));
	}

	@Test
	void mostSimilarCasesComeFirst() {
		ModeKnowledge k = new ModeKnowledge(List.of(),
				List.of(new ModeKnowledge.Case("2026年2月各平台的退款金额", "SELECT 1"),
						new ModeKnowledge.Case("2026年2月各大区的GMV是多少", "SELECT 2"),
						new ModeKnowledge.Case("库存周转天数最高的仓库", "SELECT 3")));
		List<ModeKnowledge.Case> top = k.casesFor("2026年3月各大区GMV", 2);
		assertEquals("SELECT 2", top.get(0).sql());
		assertTrue(k.evidence("2026年3月各大区GMV", false, true, 1).contains("SELECT 2"));
		assertEquals("", k.evidence("2026年3月各大区GMV", false, false, 1));
	}

	@Test
	void modesResolveAndUnknownFails() {
		GbiModeProperties p = new GbiModeProperties();
		assertEquals("qwen3.7-flash", p.resolve("turbo").getModel());
		assertTrue(p.resolve("mix").isCases() && !p.resolve("turbo").isCases());
		assertTrue(p.resolve(null).isSemanticCheck());
		assertThrows(IllegalArgumentException.class, () -> p.resolve("fast"));
	}

}
