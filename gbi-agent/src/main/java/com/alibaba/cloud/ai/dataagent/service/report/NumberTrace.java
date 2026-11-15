package com.alibaba.cloud.ai.dataagent.service.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that the amounts written in a report can be found in the data the system actually queried.
 *
 * <p>
 * The report writer is an LLM: it copies numbers out of result sets and sometimes adds them up in its head. Observed
 * failures: a monthly total copied from the wrong month, a total in 元 printed as 万元. A number counts as traced if it
 * equals (0.5%) a cell, a column total, a subtotal per value of another column, or the difference of two of those in
 * the same column. Numbers below 100, percentages, multiples and years are skipped. What is left is flagged for review;
 * it is not proof of an error (unit conversions and averages the report derived are also flagged).
 */
public final class NumberTrace {

	private static final Pattern CODE = Pattern.compile("```.*?```", Pattern.DOTALL);

	private static final Pattern DATES = Pattern.compile("\\d{4}-\\d{2}(-\\d{2})?|\\d{4}年|20\\d{4}");

	private static final Pattern NUM = Pattern.compile("(-?\\d[\\d,]*\\.?\\d*)\\s*(万|%|％|倍)?");

	private NumberTrace() {
	}

	/** @param results each evidence query's rows (column -> value as text) */
	public static List<String> untraced(String body, List<List<Map<String, String>>> results) {
		Set<String> out = new LinkedHashSet<>();
		List<Double> cands = candidates(results);
		String text = DATES.matcher(CODE.matcher(body).replaceAll(" ")).replaceAll(" ");
		Matcher m = NUM.matcher(text);
		while (m.find()) {
			Double v = num(m.group(1));
			String unit = m.group(2);
			if (v == null || (unit != null && !"万".equals(unit))) {
				continue;
			}
			double x = "万".equals(unit) ? v * 10000 : v;
			if (Math.abs(x) < 100) {
				continue;
			}
			boolean hit = false;
			for (double c : cands) {
				if (Math.abs(x - c) <= Math.max(0.011, 0.005 * Math.abs(c))) {
					hit = true;
					break;
				}
			}
			if (!hit) {
				out.add(m.group(0).strip());
			}
		}
		return new ArrayList<>(out);
	}

	static List<Double> candidates(List<List<Map<String, String>>> results) {
		Map<String, List<Double>> byCol = new HashMap<>();
		for (List<Map<String, String>> rows : results) {
			if (rows.isEmpty()) {
				continue;
			}
			List<String> cols = new ArrayList<>(rows.get(0).keySet());
			for (String c : cols) {
				List<Double> vals = new ArrayList<>();
				for (Map<String, String> r : rows) {
					Double v = num(r.get(c));
					if (v != null) {
						vals.add(v);
					}
				}
				if (vals.isEmpty()) {
					continue;
				}
				List<Double> col = byCol.computeIfAbsent(c, k -> new ArrayList<>());
				col.addAll(vals);
				col.add(vals.stream().mapToDouble(Double::doubleValue).sum());
				for (String k : cols) { // subtotals per value of another column, e.g. per month
					if (k.equals(c)) {
						continue;
					}
					Map<String, Double> groups = new HashMap<>();
					for (Map<String, String> r : rows) {
						Double v = num(r.get(c));
						if (v != null) {
							groups.merge(String.valueOf(r.get(k)), v, Double::sum);
						}
					}
					if (groups.size() > 1 && groups.size() < rows.size()) {
						col.addAll(groups.values());
					}
				}
			}
		}
		List<Double> out = new ArrayList<>();
		for (List<Double> vals : byCol.values()) {
			out.addAll(vals);
			List<Double> small = vals.subList(0, Math.min(60, vals.size()));
			for (double a : small) {
				for (double b : small) {
					out.add(a - b);
				}
			}
		}
		return out;
	}

	private static Double num(String s) {
		if (s == null) {
			return null;
		}
		try {
			return Double.parseDouble(s.replace(",", ""));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
