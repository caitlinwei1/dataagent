package com.gbi.cloud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.SecretKey;

/**
 * HS512 JWT. Like RuoYi-Cloud the token has no exp claim: it only names a Redis session (user_key), and the
 * session's TTL is the expiry. That keeps logout / kick-out / permission change effective immediately, which a
 * self-contained JWT cannot do without a blacklist.
 */
public final class JwtUtils {

	private final SecretKey key;

	public JwtUtils(String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 64) {
			throw new IllegalArgumentException("JWT secret must be at least 64 bytes for HS512");
		}
		this.key = Keys.hmacShaKeyFor(bytes);
	}

	public String create(Map<String, Object> claims) {
		return Jwts.builder().claims(claims).signWith(key, Jwts.SIG.HS512).compact();
	}

	/** @throws io.jsonwebtoken.JwtException on a bad signature, wrong algorithm or malformed token */
	public Claims parse(String token) {
		return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
	}

}
