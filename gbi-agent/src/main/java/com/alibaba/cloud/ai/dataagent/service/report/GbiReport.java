package com.alibaba.cloud.ai.dataagent.service.report;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** A finished analysis report as returned by the open report API. */
@Schema(description = "分析报告：Markdown 正文、图表配置和每个数字可追溯的 SQL 证据")
public record GbiReport(@Schema(description = "会话 ID（conversationId），可用于查询和复现") String threadId,
		@Schema(description = "原始问题") String question,
		@Schema(description = "COMPLETED / FAILED / DEGRADED（有步骤降级，报告不完整）") String status,
		@Schema(description = "从提问到报告完成的秒数") double seconds,
		@Schema(description = "执行计划的步骤（工具 + 说明）") List<Map<String, String>> plan,
		@Schema(description = "报告正文（Markdown，图表为 ```echarts 代码块），末尾附数据证据") String markdown,
		@Schema(description = "报告中的 ECharts 图表") List<Chart> charts,
		@Schema(description = "数据证据：每一步执行的 SQL 和结果摘要") List<Evidence> evidence,
		@Schema(description = "错误或降级说明") List<String> issues,
		@Schema(description = "正文中在 SQL 结果里找不到出处的金额类数字（换算、推算或抄错，需人工核对）") List<String> untracedNumbers) {

	@Schema(description = "一张 ECharts 图表")
	public record Chart(int index, @Schema(description = "图表类型，取第一个 series 的 type") String type,
			@Schema(description = "ECharts option 是否为合法 JSON 且含 series") boolean valid,
			@Schema(description = "ECharts option（JSON 字符串）") String option) {
	}

	@Schema(description = "一条 SQL 证据")
	public record Evidence(int index, String sql, List<String> columns, int rowCount,
			@Schema(description = "前 5 行") List<Map<String, Object>> preview) {
	}

}
