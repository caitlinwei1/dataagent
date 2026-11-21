package com.alibaba.cloud.ai.dataagent.service.sql.freshness;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.GbiNl2sqlProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7: tables loaded T+1 return an empty result for "today", and an empty table reads as "spend was 0". When a query
 * over such a table comes back empty and its date literals reach past the table's latest date, say so
 * deterministically ("data up to 2026-10-30") instead of letting the model or the reader guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFreshnessService {

	private static final Pattern DATE = Pattern.compile("'(\\d{4}-\\d{2}-\\d{2})");

	private static final long TTL_MS = 5 * 60 * 1000L;

	private final GbiNl2sqlProperties properties;

	private record Cached(LocalDate latest, long at) {
	}

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	/** A note when the result is empty and the SQL asks for dates after a T+1 table's latest date, else empty. */
	public Optional<String> explainEmpty(String sql, Collection<String> tables, Accessor accessor,
			DbConfigBO dbConfig) {
		LocalDate maxAsked = maxDateLiteral(sql);
		if (maxAsked == null || tables == null) {
			return Optional.empty();
		}
		List<String> parts = new ArrayList<>();
		for (String raw : tables) {
			String table = bare(raw);
			String column = properties.getFreshnessColumns().get(table);
			if (column == null) {
				continue;
			}
			LocalDate latest = latest(table, column, accessor, dbConfig);
			if (latest != null && !maxAsked.isBefore(latest.plusDays(1))) {
				parts.add(table + " 最新数据日期 " + latest);
			}
		}
		if (parts.isEmpty()) {
			return Optional.empty();
		}
		String note = "数据时效提示：查询结果为空，不代表实际为 0。" + String.join("；", parts)
				+ "（T+1 回传，当天数据次日才有），查询的日期超出了已有数据的范围。";
		log.info("freshness note sql_max_date={} {}", maxAsked, parts);
		return Optional.of(note);
	}

	/** Latest date literal in the SQL; for a half-open range "< '2026-11-01'" the asked day is the day before. */
	static LocalDate maxDateLiteral(String sql) {
		if (sql == null) {
			return null;
		}
		LocalDate max = null;
		Matcher m = DATE.matcher(sql);
		while (m.find()) {
			try {
				LocalDate d = LocalDate.parse(m.group(1));
				String before = sql.substring(Math.max(0, m.start() - 4), m.start()).replace(" ", "");
				if (before.endsWith("<")) {
					d = d.minusDays(1);
				}
				if (max == null || d.isAfter(max)) {
					max = d;
				}
			}
			catch (Exception ignored) {
				// not a calendar date
			}
		}
		return max;
	}

	private LocalDate latest(String table, String column, Accessor accessor, DbConfigBO dbConfig) {
		String key = dbConfig.getUrl() + "|" + table;
		Cached c = cache.get(key);
		if (c != null && System.currentTimeMillis() - c.at() < TTL_MS) {
			return c.latest();
		}
		try {
			DbQueryParameter q = new DbQueryParameter();
			q.setSql("SELECT MAX(" + column + ") AS latest FROM " + table);
			q.setSchema(dbConfig.getSchema());
			ResultSetBO rs = accessor.executeSqlAndReturnObject(dbConfig, q);
			String v = rs.getData() == null || rs.getData().isEmpty() ? null
					: rs.getData().get(0).values().stream().findFirst().orElse(null);
			LocalDate latest = v == null ? null : LocalDate.parse(v.substring(0, 10));
			cache.put(key, new Cached(latest, System.currentTimeMillis()));
			return latest;
		}
		catch (Exception e) {
			log.warn("freshness lookup failed table={} msg={}", table, e.getMessage());
			return null;
		}
	}

	private static String bare(String table) {
		String t = table.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
		int dot = t.lastIndexOf('.');
		return dot >= 0 ? t.substring(dot + 1) : t;
	}

}
