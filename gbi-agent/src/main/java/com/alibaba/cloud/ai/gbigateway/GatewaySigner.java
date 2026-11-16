package com.alibaba.cloud.ai.gbigateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signing between the cloud service and the local data gateway.
 *
 * <p>
 * Header {@value #HEADER}: {@code t=<epoch seconds>,n=<random nonce>,sig=<hex HMAC-SHA256(secret, t.n.body)>}. The
 * gateway rejects a wrong signature, a timestamp outside the allowed skew, and a nonce it has already seen inside that
 * window, so the body cannot be changed in transit and a captured request cannot be replayed. The nonce (not the
 * signature) is the replay key: the cloud legitimately sends the same SQL twice in one second. Shared with the
 * cloud-side client (same jar).
 */
public final class GatewaySigner {

	public static final String HEADER = "X-Gbi-Signature";

	private GatewaySigner() {
	}

	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	public static String sign(String secret, long epochSeconds, String body) {
		return sign(secret, epochSeconds, Long.toHexString(RANDOM.nextLong()), body);
	}

	public static String sign(String secret, long epochSeconds, String nonce, String body) {
		return "t=" + epochSeconds + ",n=" + nonce + ",sig=" + hmac(secret, epochSeconds + "." + nonce + "." + body);
	}

	/** The nonce of a well-formed header, used by the gateway as the replay key. */
	public static String nonce(String header) {
		int i = header.indexOf(",n=");
		return i < 0 ? null : header.substring(i + 3, header.indexOf(",sig="));
	}

	/** @return null if valid, else the reason */
	public static String verify(String secret, String header, String body, long nowSeconds, long maxSkewSeconds) {
		if (secret == null || secret.isBlank()) {
			return "gateway secret not configured";
		}
		if (header == null || !header.startsWith("t=") || !header.contains(",n=") || !header.contains(",sig=")) {
			return "missing signature";
		}
		long t;
		try {
			t = Long.parseLong(header.substring(2, header.indexOf(',')));
		}
		catch (NumberFormatException e) {
			return "bad timestamp";
		}
		if (Math.abs(nowSeconds - t) > maxSkewSeconds) {
			return "timestamp outside allowed skew";
		}
		String expected = hmac(secret, t + "." + nonce(header) + "." + body);
		String got = header.substring(header.indexOf(",sig=") + 5);
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), got.getBytes(StandardCharsets.US_ASCII))
				? null : "bad signature";
	}

	private static String hmac(String secret, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : h) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
