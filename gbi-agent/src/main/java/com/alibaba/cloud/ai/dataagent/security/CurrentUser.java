package com.alibaba.cloud.ai.dataagent.security;

import java.util.Set;

/** The authenticated caller of the current HTTP request, as forwarded and signed by the gateway. */
public record CurrentUser(long userId, String userName, Set<String> roles, Set<String> permissions,
		DataPermission permission) {

	public static final String ALL_PERMISSION = "*:*:*";

	public boolean hasPermission(String perm) {
		return permissions.contains(ALL_PERMISSION) || permissions.contains(perm);
	}

	private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

	/** null when security is disabled or the request did not pass through {@link GatewayAuthFilter}. */
	public static CurrentUser get() {
		return HOLDER.get();
	}

	static void set(CurrentUser u) {
		HOLDER.set(u);
	}

	static void clear() {
		HOLDER.remove();
	}

}
