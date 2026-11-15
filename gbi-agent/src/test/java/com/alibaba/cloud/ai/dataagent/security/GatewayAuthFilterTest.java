package com.alibaba.cloud.ai.dataagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayAuthFilterTest {

	static final String SECRET = "test-sign-secret";

	static final String LOGIN_JSON = """
			{"userId":2,"userName":"east_ops","roles":["east_ops"],"permissions":["gbi:query"],
			 "dataScope":{"allTables":true,"tablePatterns":[],"allRows":false,"dims":{"shop":["1","2"]}}}""";

	static final String LOGIN = Base64.getUrlEncoder().withoutPadding().encodeToString(LOGIN_JSON.getBytes(StandardCharsets.UTF_8));

	GatewayAuthFilter filter() {
		GbiSecurityProperties p = new GbiSecurityProperties();
		p.setEnabled(true);
		p.setSignSecret(SECRET);
		GbiSecurityProperties.Rule r = new GbiSecurityProperties.Rule();
		r.setPath("/api/stream/**");
		r.setMethods(List.of("GET"));
		r.setPermission("gbi:query");
		p.setRules(List.of(r));
		return new GatewayAuthFilter(p, new ObjectMapper());
	}

	MockHttpServletRequest signed(String method, String path, long ts, String login) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		String t = Long.toString(ts);
		req.addHeader("X-Gbi-User-Key", "k1");
		req.addHeader("X-Gbi-Login", login);
		req.addHeader("X-Gbi-Ts", t);
		req.addHeader("X-Gbi-Sign",
				GatewayAuthFilter.hmac(SECRET, method + "\n" + path + "\n" + t + "\n" + "k1" + "\n" + LOGIN));
		return req;
	}

	int run(MockHttpServletRequest req, CurrentUser[] seen) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest a, jakarta.servlet.ServletResponse b) {
				seen[0] = CurrentUser.get();
			}
		};
		filter().doFilter(req, resp, chain);
		return resp.getStatus();
	}

	@Test
	void signedRequestPassesAndExposesTheUser() throws Exception {
		CurrentUser[] seen = new CurrentUser[1];
		assertThat(run(signed("GET", "/api/stream/search", System.currentTimeMillis(), LOGIN), seen)).isEqualTo(200);
		assertThat(seen[0].userName()).isEqualTo("east_ops");
		assertThat(seen[0].permission().values("shop")).containsExactly("1", "2");
		assertThat(CurrentUser.get()).isNull();
	}

	@Test
	void directCallWithoutSignatureIsRejected() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/stream/search");
		req.addHeader("X-Gbi-User-Id", "1");
		assertThat(run(req, new CurrentUser[1])).isEqualTo(401);
	}

	@Test
	void tamperedLoginIsRejected() throws Exception {
		String admin = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(LOGIN_JSON.replace("\"allRows\":false", "\"allRows\":true").getBytes(StandardCharsets.UTF_8));
		MockHttpServletRequest req = signed("GET", "/api/stream/search", System.currentTimeMillis(), LOGIN);
		req.removeHeader("X-Gbi-Login");
		req.addHeader("X-Gbi-Login", admin);
		assertThat(run(req, new CurrentUser[1])).isEqualTo(401);
	}

	@Test
	void signatureIsBoundToThePath() throws Exception {
		MockHttpServletRequest req = signed("GET", "/api/stream/search", System.currentTimeMillis(), LOGIN);
		req.setRequestURI("/api/agent/1/delete");
		assertThat(run(req, new CurrentUser[1])).isEqualTo(401);
	}

	@Test
	void replayAfterTheWindowIsRejected() throws Exception {
		long old = System.currentTimeMillis() - GatewayAuthFilter.MAX_SKEW_MS - 1000;
		assertThat(run(signed("GET", "/api/stream/search", old, LOGIN), new CurrentUser[1])).isEqualTo(401);
	}

	@Test
	void missingFunctionPermissionIs403() throws Exception {
		// /api/agent/** falls to the default permission gbi:admin, which east_ops lacks
		assertThat(run(signed("POST", "/api/agent/5", System.currentTimeMillis(), LOGIN), new CurrentUser[1]))
			.isEqualTo(403);
	}

	@Test
	void openPathsSkipTheCheck() throws Exception {
		assertThat(run(new MockHttpServletRequest("GET", "/echo/ok"), new CurrentUser[1])).isEqualTo(200);
	}

}
