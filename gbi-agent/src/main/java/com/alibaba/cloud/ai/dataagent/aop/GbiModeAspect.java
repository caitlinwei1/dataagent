package com.alibaba.cloud.ai.dataagent.aop;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.GBI_MODE;

import com.alibaba.cloud.ai.dataagent.service.mode.GbiModeProperties;
import com.alibaba.cloud.ai.dataagent.service.mode.ModeContext;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Runs every graph node with the chat model of the run's mode (Turbo / Mix), so no node needs to know about modes:
 * LlmService picks the model up from {@link ModeContext} when it builds the call.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class GbiModeAspect {

	private final GbiModeProperties modes;

	@Around("execution(* com.alibaba.cloud.ai.dataagent.workflow.node..*.apply(com.alibaba.cloud.ai.graph.OverAllState)) && args(state)")
	public Object withModeModel(ProceedingJoinPoint pjp, OverAllState state) throws Throwable {
		String mode = state.value(GBI_MODE, modes.getDefaultMode());
		ModeContext.Model previous = ModeContext.get();
		GbiModeProperties.Profile p = modes.resolve(mode);
		ModeContext.set(new ModeContext.Model(p.getModel(), p.getThinking()));
		try {
			return pjp.proceed();
		}
		finally {
			ModeContext.set(previous);
		}
	}

}
