package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the explicit time expressions of a question (2026年2月, 2025年, 2025年4月到2026年1月, 2026年3月5日, 第一季度 ...)
 * into calendar-exact half-open ranges and adds the comparison periods the question asks for (去年同期 / 上个月 / 环比).
 *
 * <p>
 * Why: the LLM computes month ends itself and writes {@code '2026-02-29'} (no such day), MySQL rejects it, and the
 * repair loop regenerates the same literal because the rewritten question still says "至2026-02-29". Date arithmetic is
 * deterministic, so it is done here and handed to every prompt through the evidence block.
 */
public final class TimeRangeHints {

	private static final Pattern RANGE = Pattern
		.compile("(\\d{4})年(\\d{1,2})月(?:份)?\\s*(?:到|至|-|~)\\s*(?:(\\d{4})年)?(\\d{1,2})月");

	private static final Pattern DAY = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})[日号]");

	private static final Pattern MONTH = Pattern.compile("(\\d{4})年(\\d{1,2})月(?!\\s*\\d{1,2}[日号])");

	private static final Pattern QUARTER = Pattern.compile("(\\d{4})年(?:第)?([一二三四1-4])季度|(\\d{4})年?Q([1-4])");

	private static final Pattern HALF = Pattern.compile("(\\d{4})年(上|下)半年");

	private static final Pattern YEAR = Pattern.compile("(\\d{4})年(?!\\s*(?:\\d{1,2}月|第|[一二三四1-4]季度|上半年|下半年))");

	private TimeRangeHints() {
	}

	public static String hints(String... texts) {
		String text = String.join("\n", texts == null ? new String[0] : texts);
		Map<String, String> out = new LinkedHashMap<>();
		YearMonth anchor = null;
		java.util.List<YearMonth> months = new java.util.ArrayList<>();
		String masked = text;

		Matcher m = RANGE.matcher(text);
		while (m.find()) {
			YearMonth a = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
			int y2 = m.group(3) != null ? Integer.parseInt(m.group(3)) : a.getYear();
			YearMonth b = YearMonth.of(y2, Integer.parseInt(m.group(4)));
			out.put(m.group(), range(a.atDay(1), b.plusMonths(1).atDay(1)));
			anchor = b;
			masked = masked.replace(m.group(), " ");
		}
		m = DAY.matcher(masked);
		while (m.find()) {
			LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
					Integer.parseInt(m.group(3)));
			out.put(m.group(), range(d, d.plusDays(1)));
		}
		m = MONTH.matcher(masked);
		while (m.find()) {
			YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
			out.put(m.group(), range(ym.atDay(1), ym.plusMonths(1).atDay(1)));
			anchor = ym;
			months.add(ym);
		}
		m = QUARTER.matcher(masked);
		while (m.find()) {
			int y = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3));
			int q = "一二三四".indexOf(m.group(2) != null ? m.group(2) : m.group(4)) + 1;
			if (q == 0) {
				q = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(4));
			}
			YearMonth s = YearMonth.of(y, 3 * q - 2);
			out.put(m.group(), range(s.atDay(1), s.plusMonths(3).atDay(1)));
		}
		m = HALF.matcher(masked);
		while (m.find()) {
			YearMonth s = YearMonth.of(Integer.parseInt(m.group(1)), "上".equals(m.group(2)) ? 1 : 7);
			out.put(m.group(), range(s.atDay(1), s.plusMonths(6).atDay(1)));
		}
		m = YEAR.matcher(masked.replaceAll("\\d{4}年\\d{1,2}月", " "));
		while (m.find()) {
			int y = Integer.parseInt(m.group(1));
			out.put(y + "年", range(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1)));
		}
		if (anchor != null) {
			if (text.contains("同期") || text.contains("同比") || text.contains("去年")) {
				YearMonth ly = anchor.minusYears(1);
				out.putIfAbsent("去年同期（" + ly.getYear() + "年" + ly.getMonthValue() + "月）",
						range(ly.atDay(1), ly.plusMonths(1).atDay(1)));
			}
			if (text.contains("上个月") || text.contains("上月") || text.contains("环比")) {
				YearMonth pm = anchor.minusMonths(1);
				out.putIfAbsent("上个月（" + pm.getYear() + "年" + pm.getMonthValue() + "月）",
						range(pm.atDay(1), pm.plusMonths(1).atDay(1)));
			}
			Matcher k = Pattern.compile("近(\\d{1,2})个月").matcher(text);
			if (k.find()) {
				int n = Integer.parseInt(k.group(1));
				YearMonth s = anchor.minusMonths(n - 1L);
				out.putIfAbsent("截至" + anchor.getYear() + "年" + anchor.getMonthValue() + "月的近" + n + "个月",
						range(s.atDay(1), anchor.plusMonths(1).atDay(1)));
			}
		}
		if (out.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(
				"【时间口径】以下日期范围由系统按日历精确计算，SQL 必须照抄，使用 >= 起始 AND < 结束 的半开区间，不要自己推算月末日期：\n");
		out.forEach((k, v) -> sb.append("- ").append(k).append("：").append(v).append('\n'));
		if (!months.isEmpty()) {
			// B2: monthly tables (settlement, targets, budgets) carry the business month in month_key; the
			// settlement of March is made in early April, so a settled_at range for March returns February's books
			sb.append("- 按月记账的表（有 month_key 业务月字段，如结算、目标、预算）：用 month_key = '")
				.append(months.get(0))
				.append("' 取这个月，不要用结算时间 settled_at 或创建时间去卡日期范围（")
				.append(months.get(0).getMonthValue())
				.append(" 月的账在下个月初才结算）\n");
		}
		return sb.toString();
	}

	private static String range(LocalDate from, LocalDate toExclusive) {
		return ">= '" + from + "' AND < '" + toExclusive + "'";
	}

}
