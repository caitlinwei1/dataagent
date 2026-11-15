package com.alibaba.cloud.ai.dataagent.workflow.node;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.service.code.CodePoolExecutorService;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.PythonExecutorDispatcher;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Drives the generate → execute → dispatch loop with a sandbox that always fails (the OOM case) and checks: exactly
 * pythonMaxTriesCount executions, then an explicit degradation that names the step and the cause, and no leftover
 * fallback flag for the next step.
 */
class PythonFallbackTest {

	private final CodeExecutorProperties props = new CodeExecutorProperties();

	private final AtomicInteger runs = new AtomicInteger();

	private final CodePoolExecutorService alwaysOom = req -> {
		runs.incrementAndGet();
		return CodePoolExecutorService.TaskResponse.failure("allocated 448 MiB",
				"Killed\n[sandbox] OOMKilled: memory limit 500 MiB exceeded");
	};

	private OverAllState state() {
		OverAllState s = new OverAllState();
		for (String k : new String[] { PYTHON_GENERATE_NODE_OUTPUT, PYTHON_EXECUTE_NODE_OUTPUT, PYTHON_IS_SUCCESS,
				PYTHON_TRIES_COUNT, PYTHON_FALLBACK_MODE, SQL_RESULT_LIST_MEMORY }) {
			s.registerKeyAndStrategy(k, new ReplaceStrategy());
		}
		return s;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> drain(Map<String, Object> nodeResult) {
		Flux<GraphResponse<?>> f = (Flux<GraphResponse<?>>) nodeResult.get(PYTHON_EXECUTE_NODE_OUTPUT);
		List<GraphResponse<?>> all = f.collectList().block();
		GraphResponse<?> last = all.get(all.size() - 1);
		return (Map<String, Object>) last.resultValue().orElseThrow();
	}

	@Test
	void degradesExplicitlyAfterMaxTries() throws Exception {
		PythonExecuteNode execute = new PythonExecuteNode(alwaysOom, mock(JsonParseUtil.class), props);
		PythonExecutorDispatcher dispatcher = new PythonExecutorDispatcher(props);
		OverAllState s = state();
		List<String> route = new ArrayList<>();
		String next = PYTHON_GENERATE_NODE;
		while (PYTHON_GENERATE_NODE.equals(next) && route.size() < 20) {
			int tries = (Integer) s.value(PYTHON_TRIES_COUNT).orElse(0);
			s.updateState(Map.of(PYTHON_GENERATE_NODE_OUTPUT, "print(1)", PYTHON_TRIES_COUNT, tries + 1)); // generate
			s.updateState(drain(execute.apply(s)));
			next = dispatcher.apply(s);
			route.add(next);
		}
		assertEquals(props.getPythonMaxTriesCount(), runs.get(), "one sandbox run per rewrite");
		assertEquals(PYTHON_ANALYZE_NODE, next, "degrade to analysis instead of ending the whole run: " + route);
		assertEquals(Boolean.TRUE, s.value(PYTHON_FALLBACK_MODE).orElse(false));

		String out = (String) s.value(PYTHON_EXECUTE_NODE_OUTPUT).orElseThrow();
		assertTrue(out.contains("\"degraded\":true") && out.contains("OOMKilled"), out);
		String msg = PythonAnalyzeNode.fallbackMessage(2, out);
		assertTrue(msg.startsWith("【降级】第 2 步") && msg.contains("已重写 5 次") && msg.contains("内存超过沙箱上限"), msg);
	}

	@Test
	void failuresBelowTheCapGoBackToRewrite() throws Exception {
		PythonExecuteNode execute = new PythonExecuteNode(alwaysOom, mock(JsonParseUtil.class), props);
		OverAllState s = state();
		s.updateState(Map.of(PYTHON_GENERATE_NODE_OUTPUT, "print(1)", PYTHON_TRIES_COUNT, 2));
		s.updateState(drain(execute.apply(s)));
		assertFalse((Boolean) s.value(PYTHON_IS_SUCCESS).orElse(true));
		assertFalse((Boolean) s.value(PYTHON_FALLBACK_MODE).orElse(false));
		assertEquals(PYTHON_GENERATE_NODE, new PythonExecutorDispatcher(props).apply(s));
		assertTrue(((String) s.value(PYTHON_EXECUTE_NODE_OUTPUT).orElseThrow()).contains("OOMKilled"),
				"the rewrite prompt sees why it failed");
	}

	@Test
	void timeoutReasonIsNamed() {
		String out = "{\"degraded\":true,\"tries\":5,\"last_error\":\"Killed\\n[sandbox] killed: timeout 60s exceeded\"}";
		assertTrue(PythonAnalyzeNode.fallbackMessage(3, out).contains("运行超时"));
	}

}
