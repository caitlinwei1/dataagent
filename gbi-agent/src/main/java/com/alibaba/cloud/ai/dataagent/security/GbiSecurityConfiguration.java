package com.alibaba.cloud.ai.dataagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(GbiSecurityProperties.class)
public class GbiSecurityConfiguration {

	@Bean
	@ConditionalOnProperty(name = "gbi.security.enabled", havingValue = "true")
	public FilterRegistrationBean<GatewayAuthFilter> gatewayAuthFilter(GbiSecurityProperties props, ObjectMapper json) {
		FilterRegistrationBean<GatewayAuthFilter> reg = new FilterRegistrationBean<>(new GatewayAuthFilter(props, json));
		reg.addUrlPatterns("/*");
		reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return reg;
	}

}
