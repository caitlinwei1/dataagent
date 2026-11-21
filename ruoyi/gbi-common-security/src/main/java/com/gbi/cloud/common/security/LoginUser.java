package com.gbi.cloud.common.security;

import java.util.LinkedHashSet;
import java.util.Set;

/** The session object kept in Redis (RuoYi-Cloud's LoginUser, minus the SysUser entity). */
public class LoginUser {

	private String token;

	private Long userId;

	private String userName;

	private Long deptId;

	private Set<String> roles = new LinkedHashSet<>();

	private Set<String> permissions = new LinkedHashSet<>();

	private DataScope dataScope = new DataScope();

	private long loginTime;

	private long expireTime;

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public Long getDeptId() {
		return deptId;
	}

	public void setDeptId(Long deptId) {
		this.deptId = deptId;
	}

	public Set<String> getRoles() {
		return roles;
	}

	public void setRoles(Set<String> roles) {
		this.roles = roles;
	}

	public Set<String> getPermissions() {
		return permissions;
	}

	public void setPermissions(Set<String> permissions) {
		this.permissions = permissions;
	}

	public DataScope getDataScope() {
		return dataScope;
	}

	public void setDataScope(DataScope dataScope) {
		this.dataScope = dataScope;
	}

	public long getLoginTime() {
		return loginTime;
	}

	public void setLoginTime(long loginTime) {
		this.loginTime = loginTime;
	}

	public long getExpireTime() {
		return expireTime;
	}

	public void setExpireTime(long expireTime) {
		this.expireTime = expireTime;
	}

}
