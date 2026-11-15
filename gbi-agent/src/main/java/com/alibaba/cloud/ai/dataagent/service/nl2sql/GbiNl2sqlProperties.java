package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fixes added after the step-7 dev error analysis ({@code gbi.nl2sql.*}); switchable so the eval can measure each.
 */
@Data
@Component
@ConfigurationProperties(prefix = "gbi.nl2sql")
public class GbiNl2sqlProperties {

	/** Calendar-exact half-open ranges for the time expressions of the question, added to the evidence. */
	private boolean timeHints = true;

	/** Concrete repair instruction appended to execution errors the model keeps repeating (invalid dates). */
	private boolean repairHints = true;

}
