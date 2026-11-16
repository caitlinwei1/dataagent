package com.alibaba.cloud.ai.dataagent.controller;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.service.report.GbiReport;
import com.alibaba.cloud.ai.dataagent.service.report.ReportAssembler;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Sinks;

/**
 * Open report API (GBI step 8): one call runs the whole chain (intent → recall → plan → SQL → Python → report) and
 * returns the report as structured JSON — Markdown, ECharts options and SQL evidence — instead of an SSE stream meant
 * for the chat UI. Described by OpenAPI 3 at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/reports")
@Tag(name = "报告 OpenAPI", description = "提交分析问题，获取带图表和数据证据的 Markdown 报告")
public class ReportApiController {

	private static final int KEEP = 200;

	private final GraphService graphService;

	/** Most recent reports by threadId (in memory; the chat history keeps the full stream). */
	private final Map<String, GbiReport> reports = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, GbiReport> eldest) {
			return size() > KEEP;
		}
	});

	@Schema(description = "报告请求")
	public record ReportRequest(@Schema(description = "智能体 ID", example = "5") String agentId,
			@Schema(description = "分析问题", example = "2026年3月华东的GMV和去年同期相比变化了多少？从一级类目和平台两个维度分析原因") String question,
			@Schema(description = "可选：会话 ID，传入则作为多轮追问的上下文") String threadId,
			@Schema(description = "可选：超时秒数，默认 900") Integer timeoutSeconds,
			@Schema(description = "可选：问答模式 turbo / mix（默认 gbi.mode.default-mode）", example = "mix") String mode) {
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "生成分析报告（同步）", description = "跑完整链路后返回报告；status=DEGRADED 表示有步骤降级，issues 里写明原因")
	public ResponseEntity<GbiReport> create(@RequestBody ReportRequest req) {
		if (req.agentId() == null || req.question() == null || req.question().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		String threadId = req.threadId() != null ? req.threadId() : "report-" + UUID.randomUUID();
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		GraphRequest gr = GraphRequest.builder()
			.agentId(req.agentId())
			.threadId(threadId)
			.query(req.question())
			.humanFeedback(false)
			.nl2sqlOnly(false)
			.mode(req.mode())
			.build();
		long t0 = System.nanoTime();
		graphService.graphStreamProcess(sink, gr);
		List<ReportAssembler.Chunk> chunks = new ArrayList<>();
		int timeout = req.timeoutSeconds() != null ? req.timeoutSeconds() : 900;
		try {
			sink.asFlux()
				.takeUntil(sse -> STREAM_EVENT_COMPLETE.equals(sse.event()) || STREAM_EVENT_ERROR.equals(sse.event()))
				.doOnNext(sse -> {
					GraphNodeResponse d = sse.data();
					if (d != null && (d.getText() != null || d.isError())) {
						chunks.add(new ReportAssembler.Chunk(d.getNodeName(),
								d.getTextType() == null ? null : d.getTextType().name(), d.getText(), d.isError()));
					}
				})
				.blockLast(Duration.ofSeconds(timeout));
		}
		catch (Exception e) {
			log.warn("report {} did not finish: {}", threadId, e.getMessage());
			graphService.stopStreamProcessing(threadId);
			chunks.add(new ReportAssembler.Chunk(null, "TEXT", "超时或中断：" + e.getMessage(), true));
		}
		GbiReport report = ReportAssembler.assemble(threadId, req.question(), (System.nanoTime() - t0) / 1e9, chunks);
		reports.put(threadId, report);
		return ResponseEntity.ok(report);
	}

	@GetMapping(value = "/{threadId}", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "按会话 ID 查询报告")
	public ResponseEntity<GbiReport> get(@Parameter(description = "会话 ID") @PathVariable String threadId) {
		GbiReport r = reports.get(threadId);
		return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
	}

	@GetMapping(value = "/{threadId}/markdown", produces = "text/markdown;charset=UTF-8")
	@Operation(summary = "下载报告 Markdown（含数据证据附录）")
	public ResponseEntity<String> markdown(@PathVariable String threadId) {
		GbiReport r = reports.get(threadId);
		return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r.markdown());
	}

}
