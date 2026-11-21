package com.gbi.cloud.gateway;

import com.gbi.cloud.common.security.GatewaySigner;
import com.gbi.cloud.common.security.JwtUtils;
import com.gbi.cloud.common.security.SecurityConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway's only security filter (RuoYi-Cloud ruoyi-gateway AuthFilter), plus a signature:
 * <ol>
 * <li>strip every identity header the client sent (X-Gbi-*, from-source), whitelisted path or not;</li>
 * <li>whitelisted paths pass;</li>
 * <li>verify the JWT signature, then that its Redis session still exists (logout / expiry / kick-out);</li>
 * <li>forward user id / name / key and the session's LoginUser, signed with HMAC so gbi-agent can reject
 * anything that did not come through here.</li>
 * </ol>
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

	private final ReactiveStringRedisTemplate redis;

	private final GatewayProperties props;

	private final JwtUtils jwt;

	private final GatewaySigner signer;

	private final AntPathMatcher matcher = new AntPathMatcher();

	public AuthFilter(ReactiveStringRedisTemplate redis, GatewayProperties props) {
		this.redis = redis;
		this.props = props;
		this.jwt = new JwtUtils(props.jwtSecret());
		this.signer = new GatewaySigner(props.signSecret());
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest().mutate().headers(AuthFilter::stripIdentity).build();
		String path = request.getURI().getRawPath();
		if (props.ignoreWhites() != null && props.ignoreWhites().stream().anyMatch(p -> matcher.match(p, path))) {
			return chain.filter(exchange.mutate().request(request).build());
		}
		String header = request.getHeaders().getFirst(SecurityConstants.AUTHORIZATION);
		if (header == null || !header.startsWith(SecurityConstants.BEARER)) {
			return unauthorized(exchange, "令牌不能为空");
		}
		Claims claims;
		try {
			claims = jwt.parse(header.substring(SecurityConstants.BEARER.length()));
		}
		catch (JwtException | IllegalArgumentException e) {
			return unauthorized(exchange, "令牌验证失败");
		}
		String userKey = claims.get(SecurityConstants.CLAIM_USER_KEY, String.class);
		Object userId = claims.get(SecurityConstants.CLAIM_USER_ID);
		String userName = claims.get(SecurityConstants.CLAIM_USER_NAME, String.class);
		if (userKey == null || userId == null) {
			return unauthorized(exchange, "令牌验证失败");
		}
		// Branch on the Redis lookup itself. Writing .flatMap(... chain.filter ...).switchIfEmpty(unauthorized) is
		// wrong: chain.filter() completes empty after the proxied response is written, so switchIfEmpty would fire
		// on every successful request and try to rewrite a committed response.
		return redis.opsForValue()
			.get(SecurityConstants.LOGIN_TOKEN_KEY + userKey)
			.map(java.util.Optional::of)
			.defaultIfEmpty(java.util.Optional.empty())
			.flatMap(found -> {
				if (found.isEmpty()) {
					return unauthorized(exchange, "登录状态已过期");
				}
				String loginJson = found.get();
				String login = Base64.getUrlEncoder().withoutPadding().encodeToString(loginJson.getBytes(StandardCharsets.UTF_8));
				String ts = Long.toString(System.currentTimeMillis());
				String method = request.getMethod().name().toUpperCase(Locale.ROOT);
				String sign = signer.sign(method, path, ts, userKey, login);
				ServerHttpRequest signed = request.mutate().headers(h -> {
					h.set(SecurityConstants.HEADER_USER_KEY, userKey);
					h.set(SecurityConstants.HEADER_USER_ID, userId.toString());
					h.set(SecurityConstants.HEADER_USER_NAME, userName == null ? "" : userName);
					h.set(SecurityConstants.HEADER_LOGIN, login);
					h.set(SecurityConstants.HEADER_TS, ts);
					h.set(SecurityConstants.HEADER_SIGN, sign);
				}).build();
				return chain.filter(exchange.mutate().request(signed).build());
			});
	}

	static void stripIdentity(HttpHeaders h) {
		h.keySet()
			.removeIf(k -> k.regionMatches(true, 0, SecurityConstants.HEADER_PREFIX, 0,
					SecurityConstants.HEADER_PREFIX.length()) || k.equalsIgnoreCase(SecurityConstants.FROM_SOURCE));
	}

	private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
		log.info("gateway_reject path={} reason={}", exchange.getRequest().getURI().getRawPath(), msg);
		ServerHttpResponse resp = exchange.getResponse();
		resp.setStatusCode(HttpStatus.UNAUTHORIZED);
		resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		DataBuffer buf = resp.bufferFactory()
			.wrap(("{\"code\":401,\"msg\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8));
		return resp.writeWith(Mono.just(buf));
	}

	@Override
	public int getOrder() {
		return -200;
	}

}
