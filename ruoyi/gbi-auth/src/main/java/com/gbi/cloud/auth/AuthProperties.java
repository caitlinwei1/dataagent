package com.gbi.cloud.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param expireMinutes session TTL in Redis (RuoYi default 720)
 * @param maxRetry failed passwords before the account is locked (RuoYi SysPasswordService: 5)
 * @param lockMinutes lock duration (RuoYi: 10)
 */
@ConfigurationProperties("gbi.auth")
public record AuthProperties(String jwtSecret, long expireMinutes, int maxRetry, long lockMinutes) {
}
