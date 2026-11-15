package com.alibaba.cloud.ai.dataagent.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gbi.security.*}. Off by default so the eval scripts and the single-process setup keep working; the
 * microservice bring-up (scripts/up.sh with SECURITY=on) turns it on.
 */
@Data
@ConfigurationProperties("gbi.security")
public class GbiSecurityProperties {

	private boolean enabled = false;

	/** Shared with gbi-gateway (GBI_GATEWAY_SIGN_SECRET). */
	private String signSecret = "change-me-gbi-gateway-sign-secret";

	/** Paths reachable without the gateway (health checks, API docs). */
	private List<String> openPaths = new ArrayList<>(List.of("/echo/ok", "/actuator/health", "/swagger-ui/**",
			"/swagger-ui.html", "/v3/api-docs/**"));

	/**
	 * Function permissions (RuoYi's @RequiresPermissions, as config): first matching rule wins; a request matching
	 * no rule needs {@code defaultPermission}.
	 */
	private List<Rule> rules = new ArrayList<>();

	private String defaultPermission = "gbi:admin";

	@Data
	public static class Rule {

		private String path;

		/** Empty = any method. */
		private List<String> methods = new ArrayList<>();

		private String permission;

	}

}
