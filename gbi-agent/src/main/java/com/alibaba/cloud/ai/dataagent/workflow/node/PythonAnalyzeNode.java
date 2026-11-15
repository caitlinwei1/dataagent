/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.dataagent.prompt.PromptConstant;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;

/**
 * 根据Python代码的运行结果做总结分析
 *
 * @author vlsmb
 * @since 2025/7/30
 */
@Slf4j
@Component
@AllArgsConstructor
public class PythonAnalyzeNode implements NodeAction {

	private final LlmService llmService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// Get context
		String userQuery = StateUtil.getCanonicalQuery(state);
		String pythonOutput = StateUtil.getStringValue(state, PYTHON_EXECUTE_NODE_OUTPUT);
		int currentStep = PlanProcessUtil.getCurrentStepNumber(state);
		@SuppressWarnings("unchecked")
		Map<String, String> sqlExecuteResult = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class,
				new HashMap<>());

		// 检查是否进入降级模式
		boolean isFallbackMode = StateUtil.getObjectValue(state, PYTHON_FALLBACK_MODE, Boolean.class, false);

		if (isFallbackMode) {
			// 降级模式
			String fallbackMessage = fallbackMessage(currentStep, pythonOutput);
			log.warn("Python分析节点检测到降级模式，返回固定提示信息");

			Flux<ChatResponse> fallbackFlux = Flux.just(ChatResponseUtil.createResponse(fallbackMessage));

			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state, "正在处理分析结果...\n", "\n处理完成。", aiResponse -> {
						Map<String, String> updatedSqlResult = PlanProcessUtil.addStepResult(sqlExecuteResult,
								currentStep, fallbackMessage);
						log.info("python fallback message: {}", fallbackMessage);
						// the degradation is scoped to this step: the next Python step gets a fresh budget
						return Map.of(SQL_EXECUTE_NODE_OUTPUT, updatedSqlResult, PLAN_CURRENT_STEP, currentStep + 1,
								PYTHON_FALLBACK_MODE, false, PYTHON_TRIES_COUNT, 0);
					}, fallbackFlux);

			return Map.of(PYTHON_ANALYSIS_NODE_OUTPUT, generator);
		}

		String systemPrompt = PromptConstant.getPythonAnalyzePromptTemplate()
			.render(Map.of("python_output", pythonOutput, "user_query", userQuery));

		Flux<ChatResponse> pythonAnalyzeFlux = llmService.callSystem(systemPrompt);

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "正在分析代码运行结果...\n", "\n结果分析完成。", aiResponse -> {
					Map<String, String> updatedSqlResult = PlanProcessUtil.addStepResult(sqlExecuteResult, currentStep,
							aiResponse);
					log.info("python analyze result: {}", aiResponse);
					return Map.of(SQL_EXECUTE_NODE_OUTPUT, updatedSqlResult, PLAN_CURRENT_STEP, currentStep + 1,
							PYTHON_TRIES_COUNT, 0);
				}, pythonAnalyzeFlux);

		return Map.of(PYTHON_ANALYSIS_NODE_OUTPUT, generator);
	}

	static String fallbackMessage(int step, String pythonOutput) {
		String reason = "";
		try {
			var node = JsonUtil.getObjectMapper().readTree(pythonOutput == null ? "{}" : pythonOutput);
			String err = node.path("last_error").asText("");
			if (err.contains("OOMKilled")) {
				reason = "内存超过沙箱上限";
			}
			else if (err.contains("timeout")) {
				reason = "运行超时";
			}
			else if (!err.isBlank()) {
				String[] lines = err.strip().split("\n");
				reason = lines[lines.length - 1];
			}
			if (node.has("tries")) {
				reason = "已重写 " + node.path("tries").asInt() + " 次" + (reason.isEmpty() ? "" : "，最后错误：" + reason);
			}
		}
		catch (Exception ignored) {
		}
		return "【降级】第 " + step + " 步的 Python 分析未完成" + (reason.isEmpty() ? "" : "（" + reason + "）")
				+ "。本步只保留上一步 SQL 的查询结果，报告不包含此步的计算结论。";
	}

}
