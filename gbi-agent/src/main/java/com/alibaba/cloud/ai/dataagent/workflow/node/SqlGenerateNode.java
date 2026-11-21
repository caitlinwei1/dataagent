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

import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * Enhanced SQL generation node that handles SQL query regeneration with advanced
 * optimization features. This node is responsible for: - Multi-round SQL optimization and
 * refinement - Syntax validation and security analysis - Performance optimization and
 * intelligent caching - Handling execution exceptions and semantic consistency failures -
 * Managing retry logic with schema advice - Providing streaming feedback during
 * regeneration process
 *
 * @author zhangshenghang
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlGenerateNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final DataAgentProperties properties;

	private final com.alibaba.cloud.ai.dataagent.service.nl2sql.GbiNl2sqlProperties nl2sqlProperties;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 判断是否达到最大尝试次数
		int count = state.value(SQL_GENERATE_COUNT, 0);
		SqlRetryDto pendingRetry = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());
		Map<String, Object> replan = replanIfStuck(state, count, pendingRetry);
		if (replan != null) {
			return replan;
		}
		if (count >= properties.getMaxSqlRetryCount()) {
			ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
			String sqlGenerateOutput = String.format("步骤[%d]中，SQL次数生成超限，最大尝试次数：%d，已尝试次数:%d，该步骤内容: \n %s",
					executionStep.getStep(), properties.getMaxSqlRetryCount(), count,
					executionStep.getToolParameters().getInstruction());
			log.error("SQL generation failed, reason: {}", sqlGenerateOutput);
			Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(sqlGenerateOutput));
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state, "正在进行重试评估...", "重试评估完成！",
					retryOutput -> Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT, 0), preFlux);
			// reset the sql generate count
			return Map.of(SQL_GENERATE_OUTPUT, generator);
		}

		// 获取planner分配的当前执行步骤的sql任务要求，每个步骤的sql任务是不同的。
		// 不要拿 user query 这个总体的大任务。
		String promptForSql = getCurrentExecutionStepInstruction(state);

		// 准备生成SQL
		String displayMessage;
		Flux<String> sqlFlux;
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());

		if (retryDto.sqlExecuteFail()) {
			displayMessage = "检测到SQL执行异常，开始重新生成SQL...";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else if (retryDto.semanticFail()) {
			displayMessage = "语义一致性校验未通过，开始重新生成SQL...";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else {
			displayMessage = "开始生成SQL...";
			sqlFlux = handleGenerateSql(state, promptForSql);
		}

		// 准备返回结果，同时需要清除一些状态数据
		Map<String, Object> result = new HashMap<>(Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT,
				count + 1, SQL_REGENERATE_REASON, SqlRetryDto.empty()));

		// Create display flux for user experience only
		StringBuilder sqlCollector = new StringBuilder();
		Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(displayMessage),
				ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
		Flux<ChatResponse> displayFlux = preFlux
			.concatWith(sqlFlux.doOnNext(sqlCollector::append).map(ChatResponseUtil::createPureResponse))
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()),
					ChatResponseUtil.createResponse("SQL生成完成，准备执行")));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> {
					String sql = nl2SqlService.sqlTrim(sqlCollector.toString());
					result.put(SQL_GENERATE_OUTPUT, sql);
					// B4/B6: the same failing SQL written again means more retries will not help
					if (retryDto.sqlExecuteFail() || retryDto.semanticFail()) {
						String previous = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, "");
						int repeats = state.value(SQL_REPEAT_COUNT, 0);
						result.put(SQL_REPEAT_COUNT, normalize(previous).equals(normalize(sql)) ? repeats + 1 : 0);
					}
					return result;
				}, displayFlux);

		return Map.of(SQL_GENERATE_OUTPUT, generator);
	}

	private Flux<String> handleRetryGenerateSql(OverAllState state, String originalSql, String errorMsg,
			String executionDescription) {
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String userQuery = StateUtil.getCanonicalQuery(state);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);

		SqlGenerationDTO sqlGenerationDTO = SqlGenerationDTO.builder()
			.evidence(evidence)
			.query(userQuery)
			.schemaDTO(schemaDTO)
			.sql(originalSql)
			.exceptionMessage(errorMsg)
			.executionDescription(executionDescription)
			.dialect(dialect)
			.build();

		return nl2SqlService.generateSql(sqlGenerationDTO);
	}

	private Flux<String> handleGenerateSql(OverAllState state, String executionDescription) {
		return handleRetryGenerateSql(state, null, null, executionDescription);
	}

	/**
	 * B6: a step whose SQL keeps failing usually has an instruction that cannot be answered as written (e.g. the plan
	 * asks for a join the schema does not have, and the semantic check holds the SQL to the plan). Retrying the same
	 * instruction ten times cannot fix that, so after {@code gbi.nl2sql.replan-after-sql-failures} failures, or the same
	 * failing SQL twice, go back to the planner with the reason and let it rewrite the step. Bounded by
	 * {@code max-sql-replans}; after that the old behaviour (retry until the global limit, then END) applies.
	 */
	private Map<String, Object> replanIfStuck(OverAllState state, int count, SqlRetryDto retry) {
		int threshold = nl2sqlProperties.getReplanAfterSqlFailures();
		// nl2sql-only mode has a fixed one-step plan: there is nothing for the planner to rewrite
		if (threshold <= 0 || Boolean.TRUE.equals(state.value(IS_ONLY_NL2SQL, false))
				|| (!retry.sqlExecuteFail() && !retry.semanticFail())) {
			return null;
		}
		int repeats = state.value(SQL_REPEAT_COUNT, 0);
		int replans = state.value(SQL_REPLAN_COUNT, 0);
		int semanticRejects = state.value(SEMANTIC_REJECT_COUNT, 0);
		int maxRejects = nl2sqlProperties.getMaxSemanticRejects();
		// the judge rejecting the step repeatedly is the same signal as repeated execution failures: the step's
		// instruction is probably not answerable as written (B6: it asked for a join the schema does not have)
		boolean judgeStuck = maxRejects > 0 && semanticRejects >= maxRejects;
		if ((count < threshold && repeats < 1 && !judgeStuck) || replans >= nl2sqlProperties.getMaxSqlReplans()) {
			return null;
		}
		ExecutionStep step = PlanProcessUtil.getCurrentExecutionStep(state);
		String reason = retry.reason() == null ? "" : retry.reason();
		if (reason.length() > 600) {
			reason = reason.substring(0, 600) + "...";
		}
		String feedback = String.format(
				"第%d步的SQL已失败%d次%s，这一步按现在的写法做不出来。最后一次失败原因：%s\n"
						+ "请只重写第%d步及依赖它的后续步骤：换一种可行的取数方式（例如拆成几条各自能执行的SQL，再在Python步骤里对比、合并），"
						+ "不要要求schema里不存在的表关联。前%d步已经执行完成，原样保留、步骤编号不变。",
				step.getStep(), count,
				repeats > 0 ? "（模型连续写出同一条失败的SQL）" : judgeStuck ? "（语义校验连续驳回）" : "", reason, step.getStep(),
				step.getStep() - 1);
		log.warn("sql_replan step={} attempts={} repeats={} replans={} reason={}", step.getStep(), count, repeats,
				replans, reason);
		Map<String, Object> out = new HashMap<>();
		out.put(SQL_GENERATE_OUTPUT, SQL_REPLAN);
		out.put(SQL_GENERATE_COUNT, 0);
		out.put(SQL_REPEAT_COUNT, 0);
		out.put(SEMANTIC_REJECT_COUNT, 0);
		out.put(SQL_REPLAN_COUNT, replans + 1);
		out.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
		out.put(PLAN_VALIDATION_ERROR, feedback);
		Flux<ChatResponse> preFlux = Flux
			.just(ChatResponseUtil.createResponse("步骤[" + step.getStep() + "]的SQL连续失败，回到规划器重写这一步：" + reason));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
				this.getClass(), state, "正在回退到规划...", "已提交重新规划", v -> out, preFlux);
		return Map.of(SQL_GENERATE_OUTPUT, generator);
	}

	private static String normalize(String sql) {
		return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
	}

}
