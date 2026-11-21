package com.gbi.cloud.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gbi.gateway")
public record GatewayProperties(String jwtSecret, String signSecret, List<String> ignoreWhites) {
}
