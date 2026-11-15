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

import com.alibaba.cloud.ai.dataagent.dto.planner.Plan;
import com.alibaba.cloud.ai.dataagent.service.hitl.GbiHitlProperties;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;

/**
 * Plan review. The graph is interrupted before this node; the reviewer resumes the same threadId with one of:
 * approve, edit (an edited plan JSON that replaces the generated one), or reject with a reason (the planner
 * regenerates). Rejections are capped by {@code gbi.hitl.max-replans} (default 3): the rejection after the cap ends
 * the turn instead of planning again.
 *
 * <p>
 * Differences from b297203: plan editing is new; the cap counts only human rejections (b297203 shared the counter
 * with automatic structural repairs, so failed repairs used up the reviewer's budget), and the cap is checked when a
 * rejection arrives, so the reviewer can still approve the last regenerated plan.
 */
@Slf4j
@Component
@AllArgsConstructor
public class HumanFeedbackNode implements NodeAction {

	private final GbiHitlProperties props;

	/** State updates plus the message shown to the reviewer (null = nothing to show). */
	record Decision(Map<String, Object> updates, String message) {
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Decision d = decide(state);
		if (d.message() == null) {
			return d.updates();
		}
		Map<String, Object> result = d.updates();
		return Map.of(HUMAN_FEEDBACK_NODE_OUTPUT, FluxUtil.createStreamingGeneratorWithMessages(this.getClass(), state,
				v -> result, Flux.just(ChatResponseUtil.createResponse(d.message()))));
	}

	Decision decide(OverAllState state) {
		Map<String, Object> updated = new HashMap<>();
		Map<String, Object> feedbackData = StateUtil.getObjectValue(state, HUMAN_FEEDBACK_DATA, Map.class, Map.of());
		if (feedbackData.isEmpty()) {
			updated.put("human_next_node", "WAIT_FOR_FEEDBACK");
			return new Decision(updated, null);
		}
		int replans = StateUtil.getObjectValue(state, HUMAN_REPLAN_COUNT, Integer.class, 0);
		Object approvedValue = feedbackData.getOrDefault("feedback", true);
		boolean approved = approvedValue instanceof Boolean b ? b : Boolean.parseBoolean(approvedValue.toString());
		String content = String.valueOf(feedbackData.getOrDefault("feedback_content", ""));
		String edited = String.valueOf(feedbackData.getOrDefault("edited_plan", ""));
		String message;

		if (approved && StringUtils.hasText(edited)) {
			Plan plan = PlanProcessUtil.parsePlan(edited);
			if (plan != null && plan.getExecutionPlan() != null && !plan.getExecutionPlan().isEmpty()) {
				log.info("Plan edited by reviewer → execution ({} steps)", plan.getExecutionPlan().size());
				updated.put(PLANNER_NODE_OUTPUT, edited);
				approve(updated, "EDITED");
				message = "已采用修改后的计划（" + plan.getExecutionPlan().size() + " 步），开始执行";
			}
			else {
				// an edit that does not parse is taken as a rejection whose reason is the edit itself
				approved = false;
				content = "用户修改的计划无法解析，请按用户的修改意图重新规划：" + edited;
				message = null;
			}
		}
		else if (approved) {
			log.info("Plan approved → execution");
			approve(updated, "APPROVED");
			message = "计划已确认，开始执行";
		}
		else {
			message = null;
		}

		if (!approved) {
			if (replans >= props.getMaxReplans()) {
				log.warn("Plan rejected after {} replans, limit {} reached → end", replans, props.getMaxReplans());
				// StateGraph.END ("__END__"): b297203 put the literal "END" here, which has no edge mapping, so
				// reaching the cap threw GraphRunnerException instead of ending the turn
				updated.put("human_next_node", com.alibaba.cloud.ai.graph.StateGraph.END);
				updated.put(HUMAN_REVIEW_STATUS, "REPLAN_LIMIT");
				message = "已重新规划 " + replans + " 次，达到上限（" + props.getMaxReplans() + " 次），本轮结束。请调整问题后重新提问。";
			}
			else {
				log.info("Plan rejected → regeneration (replan {}/{})", replans + 1, props.getMaxReplans());
				updated.put("human_next_node", PLANNER_NODE);
				updated.put(HUMAN_REPLAN_COUNT, replans + 1);
				updated.put(HUMAN_REVIEW_STATUS, "REJECTED");
				updated.put(PLAN_CURRENT_STEP, 1);
				updated.put(HUMAN_REVIEW_ENABLED, true);
				// the planner sees every reason of this turn, so a second rejection refines the first instead of
				// replacing it (b297203 passed only the latest reason)
				java.util.List<String> reasons = new java.util.ArrayList<>(
						StateUtil.getObjectValue(state, HUMAN_REJECT_REASONS, java.util.List.class, java.util.List.of()));
				reasons.add(StringUtils.hasLength(content) ? content : "用户驳回了计划");
				updated.put(HUMAN_REJECT_REASONS, reasons);
				updated.put(PLAN_VALIDATION_ERROR, reasons.size() == 1 ? reasons.get(0)
						: "用户已多次驳回，请同时满足以下全部意见：\n" + numbered(reasons));
				updated.put(PLANNER_NODE_OUTPUT, "");
				// the next pause must wait for new feedback, not reuse this one
				updated.put(HUMAN_FEEDBACK_DATA, Map.of());
				message = "计划已驳回，第 " + (replans + 1) + "/" + props.getMaxReplans() + " 次重新规划。驳回原因：" + content;
			}
		}

		return new Decision(updated, message);
	}

	private static String numbered(java.util.List<String> reasons) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < reasons.size(); i++) {
			sb.append(i + 1).append(". ").append(reasons.get(i)).append('\n');
		}
		return sb.toString().strip();
	}

	private static void approve(Map<String, Object> updated, String status) {
		updated.put("human_next_node", PLAN_EXECUTOR_NODE);
		updated.put(HUMAN_REVIEW_ENABLED, false);
		updated.put(HUMAN_REVIEW_STATUS, status);
		updated.put(HUMAN_FEEDBACK_DATA, Map.of());
	}

}
