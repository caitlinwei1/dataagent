package com.alibaba.cloud.ai.dataagent.service.sql.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Classifies real errors from the local MySQL (:13306) and PostgreSQL (:15432) loaded by scripts/load_dataset.sh,
 * through the read-only account. Skipped when the databases are not running.
 */
class SqlErrorClassifierTest {

	private static final Map<String, SqlErrorClass> MYSQL = Map.of(
			"SELECT COUNT(*) FROM trd_orders", SqlErrorClass.TABLE_SELECTION,
			"SELECT paid_amount FROM trd_order", SqlErrorClass.TABLE_SELECTION,
			"SELECT shop_id, pay_amount FROM trd_order GROUP BY shop_id", SqlErrorClass.DIALECT,
			"SELECT date_trunc('month', paid_at) FROM trd_order", SqlErrorClass.DIALECT,
			"SELECT paid_at::date FROM trd_order", SqlErrorClass.DIALECT,
			"DELETE FROM trd_order WHERE id = -1", SqlErrorClass.EXECUTION,
			"SELECT * FROM saa_data_agent.model_config", SqlErrorClass.EXECUTION);

	private static final Map<String, SqlErrorClass> PG = Map.of(
			"SELECT COUNT(*) FROM trd_orders", SqlErrorClass.TABLE_SELECTION,
			"SELECT paid_amount FROM trd_order", SqlErrorClass.TABLE_SELECTION,
			"SELECT shop_id, pay_amount FROM trd_order GROUP BY shop_id", SqlErrorClass.DIALECT,
			"SELECT DATE_FORMAT(paid_at, '%Y-%m') FROM trd_order", SqlErrorClass.DIALECT,
			"SELECT `shop_id` FROM trd_order", SqlErrorClass.DIALECT,
			"DELETE FROM trd_order WHERE id = -1", SqlErrorClass.EXECUTION,
			"SELECT 1/0", SqlErrorClass.EXECUTION);

	@Test
	void mysqlErrors() {
		run("jdbc:mysql://127.0.0.1:13306/gbi_ecommerce?useSSL=false&allowPublicKeyRetrieval=true", MYSQL);
	}

	@Test
	void postgresErrors() {
		run("jdbc:postgresql://127.0.0.1:15432/postgres?currentSchema=gbi_ecommerce", PG);
	}

	/** The statement timeout works for the read-only account and is classified as an execution error. */
	@Test
	void timeoutKillsLongQueries() throws Exception {
		timeout("jdbc:mysql://127.0.0.1:13306/gbi_ecommerce?useSSL=false&allowPublicKeyRetrieval=true",
				"SELECT SLEEP(5)");
		timeout("jdbc:postgresql://127.0.0.1:15432/postgres?currentSchema=gbi_ecommerce", "SELECT pg_sleep(5)");
	}

	private static void timeout(String url, String sql) throws Exception {
		Connection c;
		try {
			c = DriverManager.getConnection(url, "gbi_reader", "gbi_reader_local");
		}
		catch (Exception e) {
			assumeTrue(false, "database not running: " + e.getMessage());
			return;
		}
		try (c; Statement st = c.createStatement()) {
			st.setQueryTimeout(1);
			long t0 = System.nanoTime();
			SqlErrorClass got = null;
			try {
				st.executeQuery(sql).next();
			}
			catch (Exception ex) {
				got = SqlErrorClassifier.classify(ex);
			}
			long ms = (System.nanoTime() - t0) / 1_000_000;
			org.junit.jupiter.api.Assertions.assertTrue(ms < 4000, url + " took " + ms + " ms");
			assertEquals(SqlErrorClass.EXECUTION, got, url);
		}
	}

	private static void run(String url, Map<String, SqlErrorClass> cases) {
		Connection c;
		try {
			c = DriverManager.getConnection(url, "gbi_reader", "gbi_reader_local");
		}
		catch (Exception e) {
			assumeTrue(false, "database not running: " + e.getMessage());
			return;
		}
		try (c) {
			for (var e : cases.entrySet()) {
				SqlErrorClass got = null;
				try (Statement st = c.createStatement()) {
					st.execute(e.getKey());
				}
				catch (Exception ex) {
					got = SqlErrorClassifier.classify(ex);
				}
				assertEquals(e.getValue(), got, e.getKey());
			}
		}
		catch (java.sql.SQLException e) {
			throw new IllegalStateException(e);
		}
	}

}
