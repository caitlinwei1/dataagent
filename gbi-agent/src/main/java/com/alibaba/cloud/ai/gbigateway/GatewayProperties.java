package com.alibaba.cloud.ai.gbigateway;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local data gateway settings ({@code gbi.gateway.*}). Everything here stays on the customer's side. */
@Data
@ConfigurationProperties(prefix = "gbi.gateway")
public class GatewayProperties {

	/** Shared HMAC secret; the cloud side holds the same value instead of any database credential. */
	private String secret;

	private long maxSkewSeconds = 300;

	/** Most rows any query may send out of the local network. */
	private int maxRows = 1000;

	/** Column names (regex, case-insensitive) whose values are masked before leaving. */
	private List<String> maskColumns = new ArrayList<>(List.of(".*(phone|mobile).*", "(.*_)?tel$", ".*receiver_name$",
			".*(id_card|id_no)$", ".*email$", ".*(address_detail|detail_address|street)$"));

	/** JSON-lines audit of every request: what was asked, how many rows and bytes left, what was masked. */
	private String auditLog = "gateway-audit.jsonl";

	private Db datasource = new Db();

	@Data
	public static class Db {

		/** mysql or postgresql (Hologres / ADB-PG use postgresql). */
		private String dialect = "mysql";

		private String url;

		private String username;

		private String password;

		private String schema;

	}

}
