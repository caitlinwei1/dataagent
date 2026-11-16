package com.alibaba.cloud.ai.dataagent.service.llm.impls;

import org.springframework.ai.openai.OpenAiChatOptions;

/** Test hook: the per-call options LlmService builds from the current mode. */
public final class BlockLlmServiceOptions {

	private BlockLlmServiceOptions() {
	}

	public static OpenAiChatOptions of() {
		return BlockLlmService.options(OpenAiChatOptions.builder());
	}

}
