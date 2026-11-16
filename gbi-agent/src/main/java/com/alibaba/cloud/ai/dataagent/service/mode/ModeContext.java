package com.alibaba.cloud.ai.dataagent.service.mode;

/**
 * The chat model (and thinking switch) of the graph node that is running on this thread. Set around every node's
 * apply() (see GbiModeAspect) from the run's mode; LlmService binds it to the request when the call is built, which
 * happens inside apply() even for streamed calls.
 */
public final class ModeContext {

	public record Model(String name, Boolean thinking) {
	}

	private static final ThreadLocal<Model> MODEL = new ThreadLocal<>();

	private ModeContext() {
	}

	public static void set(Model m) {
		if (m == null || ((m.name() == null || m.name().isBlank()) && m.thinking() == null)) {
			MODEL.remove();
		}
		else {
			MODEL.set(m);
		}
	}

	public static Model get() {
		return MODEL.get();
	}

	/** Model name of the current node's run, or null for the active model. */
	public static String model() {
		Model m = MODEL.get();
		return m == null || m.name() == null || m.name().isBlank() ? null : m.name();
	}

}
