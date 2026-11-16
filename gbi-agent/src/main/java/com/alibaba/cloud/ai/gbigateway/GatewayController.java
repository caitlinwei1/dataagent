package com.alibaba.cloud.ai.gbigateway;

import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** POST /gateway/v1/access {method, param} -> {result}; errors carry SQLState so the cloud can still classify them. */
@RestController
@RequiredArgsConstructor
public class GatewayController {

	private static final ObjectMapper M = new ObjectMapper();

	private final GatewayProperties props;

	private final GatewayService service;

	/** Nonces already accepted inside the skew window: a captured request cannot be replayed even within it. */
	private final java.util.concurrent.ConcurrentHashMap<String, Long> seen = new java.util.concurrent.ConcurrentHashMap<>();

	@GetMapping("/gateway/v1/health")
	public Map<String, Object> health() {
		return Map.of("status", "UP", "dialect", props.getDatasource().getDialect(), "maxRows", props.getMaxRows(),
				"maskColumns", props.getMaskColumns());
	}

	@PostMapping(value = "/gateway/v1/access", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> access(@RequestBody String body,
			@RequestHeader(value = GatewaySigner.HEADER, required = false) String signature) throws Exception {
		long t0 = System.currentTimeMillis();
		String bad = GatewaySigner.verify(props.getSecret(), signature, body, System.currentTimeMillis() / 1000,
				props.getMaxSkewSeconds());
		long now = System.currentTimeMillis() / 1000;
		seen.values().removeIf(t -> now - t > props.getMaxSkewSeconds());
		if (bad == null && seen.putIfAbsent(GatewaySigner.nonce(signature), now) != null) {
			bad = "replayed request";
		}
		if (bad != null) {
			service.audit("?", null, null, 0, System.currentTimeMillis() - t0, "401 " + bad);
			return ResponseEntity.status(401).body(error(bad, null, 0));
		}
		JsonNode req = M.readTree(body);
		String method = req.path("method").asText();
		DbQueryParameter param = req.hasNonNull("param") ? M.treeToValue(req.get("param"), DbQueryParameter.class)
				: new DbQueryParameter();
		try {
			GatewayService.Outcome o = service.access(method, param);
			String out = M.writeValueAsString(Map.of("result", o.payload() == null ? Map.of() : o.payload()));
			service.audit(method, param, o, out.getBytes(StandardCharsets.UTF_8).length,
					System.currentTimeMillis() - t0, null);
			return ResponseEntity.ok(out);
		}
		catch (GatewayService.Rejected e) {
			service.audit(method, param, null, 0, System.currentTimeMillis() - t0, "403 " + e.getMessage());
			return ResponseEntity.status(403).body(error(e.getMessage(), null, 0));
		}
		catch (Exception e) {
			SQLException sql = sqlCause(e);
			service.audit(method, param, null, 0, System.currentTimeMillis() - t0, "422 " + e.getMessage());
			return ResponseEntity.status(422)
				.body(error(sql != null ? sql.getMessage() : e.getMessage(), sql == null ? null : sql.getSQLState(),
						sql == null ? 0 : sql.getErrorCode()));
		}
	}

	private static SQLException sqlCause(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException s) {
				return s;
			}
		}
		return null;
	}

	private static String error(String msg, String sqlState, int code) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("error", msg);
		m.put("sqlState", sqlState);
		m.put("vendorCode", code);
		return M.writeValueAsString(m);
	}

}
