package com.alibaba.cloud.ai.dataagent.service.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dataagent.service.mode.ModeContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** What the mode actually puts on the wire: model override and DashScope's enable_thinking. */
class ModeOptionsWireTest {

	private HttpServer server;

	@AfterEach
	void stop() {
		ModeContext.set(null);
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void turboModelAndThinkingOffReachTheRequestBody() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", ex -> {
			body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			headers.set(ex.getRequestHeaders());
			byte[] out = ("{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
					+ "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}")
				.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
		OpenAiApi api = new com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DashScopeOpenAiApi(
				"http://127.0.0.1:" + server.getAddress().getPort(), "test", "/v1/chat/completions", "/v1/embeddings",
				org.springframework.web.client.RestClient.builder(),
				org.springframework.web.reactive.function.client.WebClient.builder());
		OpenAiChatModel model = OpenAiChatModel.builder()
			.openAiApi(api)
			.defaultOptions(OpenAiChatOptions.builder().model("qwen-plus").temperature(0.0).build())
			.build();
		ModeContext.set(new ModeContext.Model("qwen3.7-flash", false));
		ChatClient.create(model)
			.prompt()
			.options(com.alibaba.cloud.ai.dataagent.service.llm.impls.BlockLlmServiceOptions.of())
			.user("hi")
			.call()
			.content();
		String b = body.get();
		assertTrue(b.contains("\"model\":\"qwen3.7-flash\""), b);
		assertTrue(b.contains("\"enable_thinking\":false"), b);
		assertTrue(b.contains("\"temperature\":0.0"), "defaults must survive the override: " + b);
		assertTrue(!headers.get().containsKey("X-gbi-enable-thinking"), "header must not reach DashScope");
	}

}
