package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * OpenAiApi that can send DashScope's {@code enable_thinking}.
 *
 * <p>
 * Spring AI 1.1.0 drops {@code OpenAiChatOptions.extraBody} when it merges per-call options (the Turbo profile needs
 * thinking off: hybrid Qwen3 models think by default and Turbo was 2.7x slower than Mix before this). Per-call HTTP
 * headers do survive, so the mode sends {@value #THINKING_HEADER}; this class moves it into the request body. The
 * header never reaches DashScope.
 */
public class DashScopeOpenAiApi extends OpenAiApi {

	public static final String THINKING_HEADER = "X-Gbi-Enable-Thinking";

	private static final ObjectMapper M = new ObjectMapper();

	public DashScopeOpenAiApi(String baseUrl, String apiKey, String completionsPath, String embeddingsPath,
			RestClient.Builder rest, WebClient.Builder web) {
		super(baseUrl, new SimpleApiKey(apiKey), new LinkedMultiValueMap<>(), completionsPath, embeddingsPath, rest, web,
				RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
	}

	@Override
	public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request,
			MultiValueMap<String, String> headers) {
		return super.chatCompletionEntity(withThinking(request, headers), strip(headers));
	}

	@Override
	public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request,
			MultiValueMap<String, String> headers) {
		return super.chatCompletionStream(withThinking(request, headers), strip(headers));
	}

	static ChatCompletionRequest withThinking(ChatCompletionRequest request, MultiValueMap<String, String> headers) {
		List<String> v = headers == null ? null : headers.get(THINKING_HEADER);
		if (v == null || v.isEmpty()) {
			return request;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> body = M.convertValue(request, Map.class);
		body.put("enable_thinking", Boolean.parseBoolean(v.get(0)));
		return M.convertValue(body, ChatCompletionRequest.class);
	}

	private static MultiValueMap<String, String> strip(MultiValueMap<String, String> headers) {
		if (headers == null || !headers.containsKey(THINKING_HEADER)) {
			return headers;
		}
		MultiValueMap<String, String> copy = new LinkedMultiValueMap<>(headers);
		copy.remove(THINKING_HEADER);
		return copy;
	}

}
