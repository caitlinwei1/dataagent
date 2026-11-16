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
package com.alibaba.cloud.ai.dataagent.service.llm.impls;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import lombok.AllArgsConstructor;
import com.alibaba.cloud.ai.dataagent.service.mode.LlmUsageMeter;
import com.alibaba.cloud.ai.dataagent.service.mode.ModeContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

@AllArgsConstructor
public class StreamLlmService implements LlmService {

	private final AiModelRegistry registry;

	@Override
	public Flux<ChatResponse> call(String system, String user) {
		return run(spec().system(system).user(user));
	}

	@Override
	public Flux<ChatResponse> callSystem(String system) {
		return run(spec().system(system));
	}

	@Override
	public Flux<ChatResponse> callUser(String user) {
		return run(spec().user(user));
	}

	/** GBI: the run's mode may pick another model (Turbo / Mix); usage is metered per model. */
	private ChatClient.ChatClientRequestSpec spec() {
		ChatClient.ChatClientRequestSpec spec = registry.getChatClient().prompt();
		return spec.options(options(OpenAiChatOptions.builder().streamUsage(true)));
	}

	private Flux<ChatResponse> run(ChatClient.ChatClientRequestSpec spec) {
		String model = ModeContext.model();
		LlmUsageMeter.call(model);
		return spec.stream().chatResponse().doOnNext(r -> LlmUsageMeter.usage(model, r));
	}

	static OpenAiChatOptions options(OpenAiChatOptions.Builder o) {
		ModeContext.Model m = ModeContext.get();
		if (m != null && ModeContext.model() != null) {
			o.model(m.name());
		}
		if (m != null && m.thinking() != null) {
			// sent as a header and moved into the body by DashScopeOpenAiApi (extraBody is dropped by Spring AI 1.1.0)
			o.httpHeaders(java.util.Map.of(
					com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DashScopeOpenAiApi.THINKING_HEADER,
					String.valueOf(m.thinking())));
		}
		return o.build();
	}

}
