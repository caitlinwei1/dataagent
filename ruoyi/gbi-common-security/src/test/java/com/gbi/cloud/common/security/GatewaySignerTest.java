package com.gbi.cloud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewaySignerTest {

	@Test
	void payloadFormatIsFixed() {
		// gbi-agent's GatewayAuthFilter rebuilds this string by hand; changing it breaks every request
		assertThat(GatewaySigner.payload("GET", "/api/x", "1", "k", "L")).isEqualTo("GET\n/api/x\n1\nk\nL");
		// known vector, shared with gbi-agent's GatewayAuthFilterTest secret
		assertThat(new GatewaySigner("test-sign-secret").sign("GET", "/api/x", "1", "k", "L")).hasSize(64);
	}

	@Test
	void verifyChecksEveryField() {
		GatewaySigner s = new GatewaySigner("secret");
		long now = 1_700_000_000_000L;
		String ts = Long.toString(now);
		String sig = s.sign("GET", "/api/x", ts, "k", "L");
		assertThat(s.verify("GET", "/api/x", ts, "k", "L", sig, now)).isTrue();
		assertThat(s.verify("POST", "/api/x", ts, "k", "L", sig, now)).isFalse();
		assertThat(s.verify("GET", "/api/y", ts, "k", "L", sig, now)).isFalse();
		assertThat(s.verify("GET", "/api/x", ts, "k", "L2", sig, now)).isFalse();
		assertThat(s.verify("GET", "/api/x", ts, "k", "L", sig, now + GatewaySigner.MAX_SKEW_MS + 1)).isFalse();
		assertThat(new GatewaySigner("other").verify("GET", "/api/x", ts, "k", "L", sig, now)).isFalse();
	}

	@Test
	void jwtRejectsForeignKeyAndShortSecret() {
		String secret = "x".repeat(64);
		String token = new JwtUtils(secret).create(Map.of("user_key", "k"));
		assertThat(new JwtUtils(secret).parse(token).get("user_key")).isEqualTo("k");
		assertThatThrownBy(() -> new JwtUtils("y".repeat(64)).parse(token))
			.isInstanceOf(io.jsonwebtoken.JwtException.class);
		assertThatThrownBy(() -> new JwtUtils("short")).isInstanceOf(IllegalArgumentException.class);
	}

}
