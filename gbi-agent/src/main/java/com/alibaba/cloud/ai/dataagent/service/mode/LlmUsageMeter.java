package com.alibaba.cloud.ai.dataagent.service.mode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Cumulative LLM calls and token usage per model, read by the eval to compare modes on cost. */
public final class LlmUsageMeter {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LlmUsageMeter.class);

	private static final Map<String, AtomicLong[]> BY_MODEL = new ConcurrentHashMap<>();

	private LlmUsageMeter() {
	}

	private static AtomicLong[] of(String model) {
		return BY_MODEL.computeIfAbsent(model == null ? "default" : model,
				k -> new AtomicLong[] { new AtomicLong(), new AtomicLong(), new AtomicLong() });
	}

	public static void call(String model) {
		of(model)[0].incrementAndGet();
		if (LOG.isDebugEnabled()) {
			StackTraceElement caller = java.util.Arrays.stream(new Throwable().getStackTrace())
				.filter(f -> f.getClassName().startsWith("com.alibaba.cloud.ai.dataagent")
						&& !f.getClassName().contains(".service.llm.") && !f.getClassName().contains(".service.mode."))
				.findFirst()
				.orElse(null);
			LOG.debug("LLM call model={} caller={}", model, caller);
		}
	}

	/** Adds the usage carried by a response chunk (streamed calls carry it in the last chunk only). */
	public static void usage(String model, ChatResponse r) {
		if (r == null || r.getMetadata() == null) {
			return;
		}
		Usage u = r.getMetadata().getUsage();
		if (u == null) {
			return;
		}
		Integer in = u.getPromptTokens();
		Integer out = u.getCompletionTokens();
		if (in != null && in > 0) {
			of(model)[1].addAndGet(in);
		}
		if (out != null && out > 0) {
			of(model)[2].addAndGet(out);
		}
	}

	public static Map<String, Map<String, Long>> snapshot() {
		Map<String, Map<String, Long>> out = new LinkedHashMap<>();
		BY_MODEL.forEach((m, v) -> out.put(m,
				Map.of("calls", v[0].get(), "promptTokens", v[1].get(), "completionTokens", v[2].get())));
		return out;
	}

}
