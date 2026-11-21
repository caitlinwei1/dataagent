package com.alibaba.cloud.ai.dataagent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a request only if gbi-gateway signed it (RuoYi-Cloud's InnerAuth idea, made cryptographic), then checks
 * the function permission for the path and exposes the caller through {@link CurrentUser}.
 *
 * <p>
 * The HMAC payload must match gbi-common-security's GatewaySigner byte for byte; gbi-agent builds on the upstream
 * parent pom so it keeps this copy instead of depending on that module.
 */
@Slf4j
public class GatewayAuthFilter extends OncePerRequestFilter {

	static final String H_USER_KEY = "X-Gbi-User-Key";

	static final String H_LOGIN = "X-Gbi-Login";

	static final String H_TS = "X-Gbi-Ts";

	static final String H_SIGN = "X-Gbi-Sign";

	static final long MAX_SKEW_MS = 60_000;

	private final GbiSecurityProperties props;

	private final ObjectMapper json;

	private final AntPathMatcher matcher = new AntPathMatcher();

	public GatewayAuthFilter(GbiSecurityProperties props, ObjectMapper json) {
		this.props = props;
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
			throws ServletException, IOException {
		String path = req.getRequestURI();
		if (props.getOpenPaths().stream().anyMatch(p -> matcher.match(p, path))) {
			chain.doFilter(req, resp);
			return;
		}
		String method = req.getMethod().toUpperCase(Locale.ROOT);
		String userKey = req.getHeader(H_USER_KEY);
		String login = req.getHeader(H_LOGIN);
		String ts = req.getHeader(H_TS);
		String sign = req.getHeader(H_SIGN);
		if (!verify(props.getSignSecret(), method, path, ts, userKey, login, sign, System.currentTimeMillis())) {
			deny(resp, 401, "请求未经网关签名或签名已过期");
			log.info("agent_reject reason=signature path={}", path);
			return;
		}
		CurrentUser user;
		try {
			user = parse(new String(Base64.getUrlDecoder().decode(login), StandardCharsets.UTF_8));
		}
		catch (Exception e) {
			deny(resp, 401, "登录信息无效");
			return;
		}
		String need = requiredPermission(method, path);
		if (!user.hasPermission(need)) {
			deny(resp, 403, "没有权限：" + need);
			log.info("agent_reject reason=permission user={} need={} path={}", user.userName(), need, path);
			return;
		}
		CurrentUser.set(user);
		try {
			chain.doFilter(req, resp);
		}
		finally {
			CurrentUser.clear();
		}
	}

	String requiredPermission(String method, String path) {
		for (GbiSecurityProperties.Rule r : props.getRules()) {
			if (matcher.match(r.getPath(), path)
					&& (r.getMethods().isEmpty() || r.getMethods().stream().anyMatch(m -> m.equalsIgnoreCase(method)))) {
				return r.getPermission();
			}
		}
		return props.getDefaultPermission();
	}

	CurrentUser parse(String loginJson) throws IOException {
		JsonNode n = json.readTree(loginJson);
		long userId = n.path("userId").asLong();
		String userName = n.path("userName").asText();
		JsonNode ds = n.path("dataScope");
		List<String> patterns = new ArrayList<>();
		ds.path("tablePatterns").forEach(x -> patterns.add(x.asText()));
		Map<String, List<String>> dims = new LinkedHashMap<>();
		ds.path("dims").fields().forEachRemaining(e -> {
			List<String> vs = new ArrayList<>();
			e.getValue().forEach(x -> vs.add(x.asText()));
			dims.put(e.getKey(), vs);
		});
		DataPermission perm = new DataPermission(userId, userName, ds.path("allTables").asBoolean(false), patterns,
				ds.path("allRows").asBoolean(false), dims);
		return new CurrentUser(userId, userName, strings(n.path("roles")), strings(n.path("permissions")), perm);
	}

	private static Set<String> strings(JsonNode arr) {
		Set<String> s = new LinkedHashSet<>();
		arr.forEach(x -> s.add(x.asText()));
		return s;
	}

	static boolean verify(String secret, String method, String path, String ts, String userKey, String login,
			String sign, long now) {
		if (ts == null || userKey == null || login == null || sign == null) {
			return false;
		}
		long t;
		try {
			t = Long.parseLong(ts);
		}
		catch (NumberFormatException e) {
			return false;
		}
		if (Math.abs(now - t) > MAX_SKEW_MS) {
			return false;
		}
		String expect = hmac(secret, method + "\n" + path + "\n" + ts + "\n" + userKey + "\n" + login);
		return MessageDigest.isEqual(expect.getBytes(StandardCharsets.US_ASCII),
				sign.getBytes(StandardCharsets.US_ASCII));
	}

	static String hmac(String secret, String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void deny(HttpServletResponse resp, int status, String msg) throws IOException {
		resp.setStatus(status);
		resp.setContentType("application/json;charset=UTF-8");
		resp.getWriter().write("{\"code\":" + status + ",\"msg\":\"" + msg + "\"}");
	}

}
