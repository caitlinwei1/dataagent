package com.alibaba.cloud.ai.dataagent.security;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who started each graph thread. threadId is the handle for human-review resume and for fetching a report, and it
 * travels in URLs; without an owner check anyone holding (or guessing) a threadId could approve someone else's plan
 * or read their report (an IDOR). Stored in the meta DB next to the MySQL checkpoints, so it survives a restart the
 * same way the resumable state does.
 */
@Slf4j
@Component
public class ThreadOwnerRegistry {

	private final JdbcTemplate jdbc;

	private volatile boolean ready;

	public ThreadOwnerRegistry(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private void ensureTable() {
		if (!ready) {
			jdbc.execute("CREATE TABLE IF NOT EXISTS gbi_thread_owner (thread_id VARCHAR(64) PRIMARY KEY, "
					+ "user_id BIGINT NOT NULL, user_name VARCHAR(64), created_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
			ready = true;
		}
	}

	/** Records the owner of a new thread, or checks it when the thread already exists. */
	public void claim(String threadId, CurrentUser user) {
		ensureTable();
		Long owner = owner(threadId);
		if (owner == null) {
			jdbc.update("INSERT IGNORE INTO gbi_thread_owner (thread_id, user_id, user_name) VALUES (?, ?, ?)", threadId,
					user.userId(), user.userName());
			owner = owner(threadId);
		}
		check(threadId, owner, user);
	}

	/** For resume / read: the thread must exist and belong to the caller. */
	public void requireOwner(String threadId, CurrentUser user) {
		ensureTable();
		check(threadId, owner(threadId), user);
	}

	private Long owner(String threadId) {
		List<Long> rows = jdbc.queryForList("SELECT user_id FROM gbi_thread_owner WHERE thread_id = ?", Long.class,
				threadId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static void check(String threadId, Long owner, CurrentUser user) {
		if (owner == null || owner != user.userId()) {
			log.info("agent_reject reason=thread_owner user={} thread={}", user.userName(), threadId);
			throw new ThreadAccessDeniedException("无权访问该会话：" + threadId);
		}
	}

	public static class ThreadAccessDeniedException extends RuntimeException {

		public ThreadAccessDeniedException(String msg) {
			super(msg);
		}

	}

}
