package com.alibaba.cloud.ai.dataagent.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 403 for a thread / report that belongs to someone else. Ordered ahead of the upstream catch-all ExceptionAdvice,
 * which would otherwise turn it into a 500. Plain JSON text because the stream endpoint produces
 * text/event-stream and an object body might not be writable there.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionAdvice {

	@ExceptionHandler(ThreadOwnerRegistry.ThreadAccessDeniedException.class)
	public ResponseEntity<String> threadAccessDenied(ThreadOwnerRegistry.ThreadAccessDeniedException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"code\":403,\"msg\":\"" + e.getMessage() + "\"}");
	}

}
