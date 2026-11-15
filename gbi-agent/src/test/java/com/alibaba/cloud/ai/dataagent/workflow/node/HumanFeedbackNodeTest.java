/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.workflow.node;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dataagent.service.hitl.GbiHitlProperties;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HumanFeedbackNodeTest {

	private static final String PLAN = "{\"thought_process\":\"按大区汇总\",\"execution_plan\":[{\"step\":1,"
			+ "\"tool_to_use\":\"SQL_GENERATE_NODE\",\"tool_parameters\":{\"instruction\":\"按大区汇总2026年3月GMV\"}}]}";

	private HumanFeedbackNode node;

	private OverAllState state;

	@BeforeEach
	void setUp() {
		node = new HumanFeedbackNode(new GbiHitlProperties());
		state = new OverAllState();
		for (String k : new String[] { PLAN_REPAIR_COUNT, HUMAN_REPLAN_COUNT, PLAN_CURRENT_STEP, HUMAN_REVIEW_ENABLED,
				PLAN_VALIDATION_ERROR, HUMAN_FEEDBACK_DATA, PLANNER_NODE_OUTPUT, HUMAN_REJECT_REASONS }) {
			state.registerKeyAndStrategy(k, new ReplaceStrategy());
		}
	}

	private Map<String, Object> decide(Map<String, Object> feedback, int replans) {
		state.updateState(Map.of(HUMAN_FEEDBACK_DATA, feedback, HUMAN_REPLAN_COUNT, replans));
		return node.decide(state).updates();
	}

	@Test
	void waitsWithoutFeedback() {
		assertEquals("WAIT_FOR_FEEDBACK", node.decide(state).updates().get("human_next_node"));
	}

	@Test
	void approveRunsThePlan() {
		Map<String, Object> r = decide(Map.of("feedback", true, "feedback_content", "同意"), 0);
		assertEquals(PLAN_EXECUTOR_NODE, r.get("human_next_node"));
		assertEquals(false, r.get(HUMAN_REVIEW_ENABLED));
		assertEquals("APPROVED", r.get(HUMAN_REVIEW_STATUS));
		assertEquals(Map.of(), r.get(HUMAN_FEEDBACK_DATA));
	}

	@Test
	void editReplacesThePlan() {
		Map<String, Object> r = decide(Map.of("feedback", true, "feedback_content", "改成按大区", "edited_plan", PLAN), 0);
		assertEquals(PLAN_EXECUTOR_NODE, r.get("human_next_node"));
		assertEquals(PLAN, r.get(PLANNER_NODE_OUTPUT));
		assertEquals("EDITED", r.get(HUMAN_REVIEW_STATUS));
	}

	@Test
	void unparsableEditBecomesARejectionCarryingTheEdit() {
		Map<String, Object> r = decide(Map.of("feedback", true, "feedback_content", "改", "edited_plan", "只看华东"), 0);
		assertEquals(PLANNER_NODE, r.get("human_next_node"));
		assertTrue(String.valueOf(r.get(PLAN_VALIDATION_ERROR)).contains("只看华东"));
		assertEquals(1, r.get(HUMAN_REPLAN_COUNT));
	}

	@Test
	void rejectReplansWithTheReason() {
		Map<String, Object> r = decide(Map.of("feedback", false, "feedback_content", "需要补充过滤条件"), 0);
		assertEquals(PLANNER_NODE, r.get("human_next_node"));
		assertEquals(1, r.get(HUMAN_REPLAN_COUNT));
		assertEquals(1, r.get(PLAN_CURRENT_STEP));
		assertEquals(true, r.get(HUMAN_REVIEW_ENABLED));
		assertEquals("需要补充过滤条件", r.get(PLAN_VALIDATION_ERROR));
		assertEquals("", r.get(PLANNER_NODE_OUTPUT));
		assertEquals(Map.of(), r.get(HUMAN_FEEDBACK_DATA));
		assertFalse(r.containsKey(PLAN_REPAIR_COUNT), "human rejections no longer touch the structural repair count");
	}

	@Test
	void thirdReplanIsAllowedAndTheFourthRejectionEnds() {
		assertEquals(PLANNER_NODE, decide(Map.of("feedback", false, "feedback_content", "x"), 2).get("human_next_node"));
		Map<String, Object> r = decide(Map.of("feedback", false, "feedback_content", "x"), 3);
		assertEquals(com.alibaba.cloud.ai.graph.StateGraph.END, r.get("human_next_node"));
		assertEquals("REPLAN_LIMIT", r.get(HUMAN_REVIEW_STATUS));
	}

	@Test
	void theLastRegeneratedPlanCanStillBeApproved() {
		assertEquals(PLAN_EXECUTOR_NODE, decide(Map.of("feedback", true, "feedback_content", "ok"), 3).get("human_next_node"));
	}

	@Test
	void laterRejectionsKeepEarlierReasons() {
		state.updateState(Map.of(HUMAN_REJECT_REASONS, java.util.List.of("按省份拆分")));
		Map<String, Object> r = decide(Map.of("feedback", false, "feedback_content", "加上同比"), 1);
		String err = String.valueOf(r.get(PLAN_VALIDATION_ERROR));
		assertTrue(err.contains("1. 按省份拆分") && err.contains("2. 加上同比"), err);
	}

	@Test
	void structuralRepairsDoNotUseTheReviewBudget() {
		state.updateState(Map.of(PLAN_REPAIR_COUNT, 3));
		assertEquals(PLANNER_NODE, decide(Map.of("feedback", false, "feedback_content", "x"), 0).get("human_next_node"));
	}

}
