package com.gbi.cloud.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over the identity headers the gateway forwards, so a downstream service can tell "came through the
 * gateway" from "someone inside the network set X-Gbi-User-Id by hand". RuoYi-Cloud relies on network isolation
 * plus the from-source header for this; the signature makes the trust explicit.
 *
 * <p>
 * gbi-agent builds separately (upstream parent pom), so it carries a copy of {@link #payload} and the HMAC; the
 * format is fixed by {@code GatewaySignerTest}.
 */
public final class GatewaySigner {

	/** Signed requests older than this are rejected (replay window). */
	public static final long MAX_SKEW_MS = 60_000;

	private final byte[] secret;

	public GatewaySigner(String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	public static String payload(String method, String path, String ts, String userKey, String login) {
		return method + "\n" + path + "\n" + ts + "\n" + userKey + "\n" + login;
	}

	public String sign(String method, String path, String ts, String userKey, String login) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of()
				.formatHex(mac.doFinal(payload(method, path, ts, userKey, login).getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public boolean verify(String method, String path, String ts, String userKey, String login, String sign,
			long nowMs) {
		if (ts == null || sign == null || userKey == null || login == null) {
			return false;
		}
		long t;
		try {
			t = Long.parseLong(ts);
		}
		catch (NumberFormatException e) {
			return false;
		}
		if (Math.abs(nowMs - t) > MAX_SKEW_MS) {
			return false;
		}
		return MessageDigest.isEqual(sign(method, path, ts, userKey, login).getBytes(StandardCharsets.US_ASCII),
				sign.getBytes(StandardCharsets.US_ASCII));
	}

}
