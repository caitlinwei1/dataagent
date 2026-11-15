package com.gbi.cloud.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbi.cloud.common.security.DataScope;
import com.gbi.cloud.common.security.JwtUtils;
import com.gbi.cloud.common.security.LoginUser;
import com.gbi.cloud.common.security.SecurityConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login / logout / refresh, following RuoYi-Cloud's SysLoginService + SysPasswordService + TokenService:
 * BCrypt passwords, lock after N failures, the JWT names a Redis session that holds the LoginUser.
 */
@Service
public class AuthService {

	public record TokenResult(String accessToken, long expiresIn) {
	}

	private final SysUserRepository repo;

	private final StringRedisTemplate redis;

	private final ObjectMapper json;

	private final AuthProperties props;

	private final JwtUtils jwt;

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	public AuthService(SysUserRepository repo, StringRedisTemplate redis, ObjectMapper json, AuthProperties props) {
		this.repo = repo;
		this.redis = redis;
		this.json = json;
		this.props = props;
		this.jwt = new JwtUtils(props.jwtSecret());
	}

	public TokenResult login(String userName, String password, String ip) {
		if (userName == null || userName.isBlank() || password == null || password.isBlank()) {
			throw new AuthException(400, "用户名和密码不能为空");
		}
		String errKey = SecurityConstants.PWD_ERR_CNT_KEY + userName;
		String cnt = redis.opsForValue().get(errKey);
		if (cnt != null && Integer.parseInt(cnt) >= props.maxRetry()) {
			repo.loginLog(userName, ip, false, "账户已锁定");
			throw new AuthException(423, "密码错误 " + props.maxRetry() + " 次，账户锁定 " + props.lockMinutes() + " 分钟");
		}
		var user = repo.findByUserName(userName).orElse(null);
		// one message for "no such user" and "wrong password", so the endpoint cannot be used to enumerate users
		if (user == null || !encoder.matches(password, user.password())) {
			Long n = redis.opsForValue().increment(errKey);
			redis.expire(errKey, Duration.ofMinutes(props.lockMinutes()));
			repo.loginLog(userName, ip, false, "用户名或密码错误");
			throw new AuthException(401, "用户名或密码错误" + (n != null && n >= props.maxRetry() ? "，账户已锁定" : ""));
		}
		if (!"0".equals(user.status())) {
			repo.loginLog(userName, ip, false, "账户已停用");
			throw new AuthException(403, "账户已停用");
		}
		redis.delete(errKey);

		LoginUser lu = buildLoginUser(user);
		String userKey = UUID.randomUUID().toString();
		lu.setToken(userKey);
		store(lu);
		repo.loginLog(userName, ip, true, "登录成功");
		String token = jwt.create(Map.of(SecurityConstants.CLAIM_USER_KEY, userKey, SecurityConstants.CLAIM_USER_ID,
				user.userId(), SecurityConstants.CLAIM_USER_NAME, user.userName()));
		return new TokenResult(token, props.expireMinutes() * 60);
	}

	public void logout(String bearer) {
		String userKey = userKey(bearer);
		if (userKey != null) {
			redis.delete(SecurityConstants.LOGIN_TOKEN_KEY + userKey);
		}
	}

	/** Extends the session and re-reads roles and grants, so a permission change applies without logging in again. */
	public TokenResult refresh(String bearer) {
		LoginUser old = current(bearer);
		var user = repo.findByUserName(old.getUserName()).filter(u -> "0".equals(u.status()))
			.orElseThrow(() -> new AuthException(401, "账户不存在或已停用"));
		LoginUser lu = buildLoginUser(user);
		lu.setToken(old.getToken());
		store(lu);
		return new TokenResult(bearer.substring(SecurityConstants.BEARER.length()), props.expireMinutes() * 60);
	}

	public LoginUser current(String bearer) {
		String userKey = userKey(bearer);
		String v = userKey == null ? null : redis.opsForValue().get(SecurityConstants.LOGIN_TOKEN_KEY + userKey);
		if (v == null) {
			throw new AuthException(401, "登录状态已过期");
		}
		try {
			return json.readValue(v, LoginUser.class);
		}
		catch (JsonProcessingException e) {
			throw new AuthException(401, "登录状态无效");
		}
	}

	LoginUser buildLoginUser(SysUserRepository.SysUser user) {
		List<DataScopeResolver.Role> roles = repo.roles(user.userId());
		List<Long> roleIds = SysUserRepository.ids(roles);
		LoginUser lu = new LoginUser();
		lu.setUserId(user.userId());
		lu.setUserName(user.userName());
		lu.setDeptId(user.deptId());
		lu.setRoles(new LinkedHashSet<>(roles.stream().map(DataScopeResolver.Role::roleKey).toList()));
		lu.setPermissions(new LinkedHashSet<>(repo.permissions(roleIds)));
		DataScope scope = DataScopeResolver.resolve(user.deptId(), roles, repo.roleDepts(roleIds), repo.depts(),
				repo.deptData(), repo.roleTables(roleIds));
		lu.setDataScope(scope);
		return lu;
	}

	private void store(LoginUser lu) {
		long now = System.currentTimeMillis();
		lu.setLoginTime(now);
		lu.setExpireTime(now + props.expireMinutes() * 60_000);
		try {
			redis.opsForValue().set(SecurityConstants.LOGIN_TOKEN_KEY + lu.getToken(), json.writeValueAsString(lu),
					Duration.ofMinutes(props.expireMinutes()));
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private String userKey(String bearer) {
		if (bearer == null || !bearer.startsWith(SecurityConstants.BEARER)) {
			return null;
		}
		try {
			Claims c = jwt.parse(bearer.substring(SecurityConstants.BEARER.length()));
			return c.get(SecurityConstants.CLAIM_USER_KEY, String.class);
		}
		catch (JwtException | IllegalArgumentException e) {
			return null;
		}
	}

}
