package com.alibaba.cloud.ai.dataagent.service.schema.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TableRecallServiceTest {

	@Test
	void previousQuestionsReadsUserLinesOfTheMultiTurnContext() {
		String ctx = "用户: 上个月华东的GMV是多少？\nAI计划: 1. 查 trd_order\n2. 汇总\n用户: 按店铺拆开\nAI计划: ...";
		assertEquals(List.of("上个月华东的GMV是多少？", "按店铺拆开"), TableRecallService.previousQuestions(ctx));
	}

	@Test
	void previousQuestionsIsEmptyWithoutHistory() {
		assertEquals(List.of(), TableRecallService.previousQuestions("(无)"));
		assertEquals(List.of(), TableRecallService.previousQuestions(null));
	}

}
