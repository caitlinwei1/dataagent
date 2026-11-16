package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.service.mode.GbiModeProperties;
import com.alibaba.cloud.ai.dataagent.service.mode.LlmUsageMeter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1")
@RequiredArgsConstructor
@Tag(name = "GBI 模式与用量")
public class GbiUsageController {

	private final GbiModeProperties modes;

	@GetMapping("/modes")
	@Operation(summary = "可用的问答模式（Turbo / Mix 等）及其配置")
	public Map<String, Object> modes() {
		return Map.of("default", modes.getDefaultMode(), "profiles", modes.getProfiles());
	}

	@GetMapping("/usage")
	@Operation(summary = "进程启动以来各模型的 LLM 调用次数和 token 用量（累计值）")
	public Map<String, Map<String, Long>> usage() {
		return LlmUsageMeter.snapshot();
	}

}
