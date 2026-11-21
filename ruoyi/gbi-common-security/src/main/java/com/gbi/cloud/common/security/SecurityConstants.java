package com.gbi.cloud.common.security;

/**
 * Names shared by the gateway, the auth service and gbi-agent. Mirrors RuoYi-Cloud's SecurityConstants /
 * CacheConstants / TokenConstants, folded into one class.
 */
public final class SecurityConstants {

	private SecurityConstants() {
	}

	/** Request header carrying the client's token: {@code Authorization: Bearer <jwt>}. */
	public static final String AUTHORIZATION = "Authorization";

	public static final String BEARER = "Bearer ";

	/** JWT claims. The JWT only carries keys; the full LoginUser lives in Redis under the user key. */
	public static final String CLAIM_USER_KEY = "user_key";

	public static final String CLAIM_USER_ID = "user_id";

	public static final String CLAIM_USER_NAME = "username";

	/** Redis: login_tokens:{user_key} -> LoginUser JSON, TTL = session length (RuoYi CacheConstants.LOGIN_TOKEN_KEY). */
	public static final String LOGIN_TOKEN_KEY = "login_tokens:";

	/** Redis: pwd_err_cnt:{username} -> failed login count (RuoYi CacheConstants.PWD_ERR_CNT_KEY). */
	public static final String PWD_ERR_CNT_KEY = "pwd_err_cnt:";

	/**
	 * Headers the gateway sets on every forwarded request. Any copy sent by the client is removed first, so a
	 * downstream service can trust them only together with a valid {@link #HEADER_SIGN}.
	 */
	public static final String HEADER_PREFIX = "X-Gbi-";

	public static final String HEADER_USER_KEY = "X-Gbi-User-Key";

	public static final String HEADER_USER_ID = "X-Gbi-User-Id";

	public static final String HEADER_USER_NAME = "X-Gbi-User-Name";

	/** Base64url(JSON LoginUser): roles, permissions and the resolved data scope. */
	public static final String HEADER_LOGIN = "X-Gbi-Login";

	public static final String HEADER_TS = "X-Gbi-Ts";

	public static final String HEADER_SIGN = "X-Gbi-Sign";

	/** RuoYi-Cloud's marker for service-to-service calls; never accepted from outside. */
	public static final String FROM_SOURCE = "from-source";

}
