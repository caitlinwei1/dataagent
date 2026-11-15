package com.gbi.cloud.auth;

import com.gbi.cloud.common.security.LoginUser;
import com.gbi.cloud.common.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

	public record LoginBody(String username, String password) {
	}

	private final AuthService auth;

	public AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/login")
	public AuthService.TokenResult login(@RequestBody LoginBody body, HttpServletRequest req) {
		String ip = req.getHeader("X-Forwarded-For");
		return auth.login(body.username(), body.password(), ip != null ? ip : req.getRemoteAddr());
	}

	@DeleteMapping("/logout")
	public Map<String, String> logout(@RequestHeader(value = SecurityConstants.AUTHORIZATION, required = false) String bearer) {
		auth.logout(bearer);
		return Map.of("msg", "已注销");
	}

	@PostMapping("/refresh")
	public AuthService.TokenResult refresh(@RequestHeader(SecurityConstants.AUTHORIZATION) String bearer) {
		return auth.refresh(bearer);
	}

	@GetMapping("/me")
	public LoginUser me(@RequestHeader(SecurityConstants.AUTHORIZATION) String bearer) {
		return auth.current(bearer);
	}

	@ExceptionHandler(AuthException.class)
	public ResponseEntity<Map<String, Object>> onAuth(AuthException e) {
		return ResponseEntity.status(e.status()).body(Map.of("code", e.status(), "msg", e.getMessage()));
	}

}
