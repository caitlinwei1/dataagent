package com.alibaba.cloud.ai.dataagent.service.hitl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Human-in-the-loop plan review ({@code gbi.hitl.*}).
 */
@Data
@Component
@ConfigurationProperties(prefix = "gbi.hitl")
public class GbiHitlProperties {

	/** How many times a reviewer may reject a plan and get a new one; the next rejection ends the turn. */
	private int maxReplans = 3;

	/**
	 * Where paused graphs are kept: {@code memory} (b297203 default, lost on restart) or {@code mysql} (the meta
	 * database, so a threadId can be resumed after a restart or on another instance).
	 */
	private String checkpoint = "memory";

}
