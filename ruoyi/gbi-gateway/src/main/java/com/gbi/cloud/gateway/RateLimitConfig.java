package com.gbi.cloud.gateway;

import com.gbi.cloud.common.security.SecurityConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

	/** Bucket per user. AuthFilter runs first and sets the header, so it is never client-supplied here. */
	@Bean
	public KeyResolver userKeyResolver() {
		return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(SecurityConstants.HEADER_USER_ID))
			.defaultIfEmpty("anonymous");
	}

}
