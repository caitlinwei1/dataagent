/*
 * GBI repro: multi-channel table recall settings.
 */
package com.alibaba.cloud.ai.dataagent.service.schema.recall;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for {@link TableRecallService}. {@code enabled=false} restores the original b297203 behaviour
 * (filter-only table recall that ignores the question).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gbi.recall")
public class GbiRecallProperties {

	/** Use multi-channel recall in SchemaRecallNode. */
	private boolean enabled = true;

	/** Channel: similarity search over table documents with the question text. */
	private boolean vector = true;

	/** Channel: similarity search over column documents, aggregated to tables. */
	private boolean column = true;

	/** Channel: BM25 over table/column names and comments plus metric/dimension glossary. */
	private boolean keyword = true;

	/** Channel: metric/dimension definitions (口径) from business knowledge name the tables they use. */
	private boolean term = true;

	/** Channel: tables linked by logical foreign keys to the leading candidates (join-path completion). */
	private boolean join = true;

	/** Number of leading candidates whose neighbours the join channel adds. */
	private int joinSeeds = 3;

	/** Channel: enum values read from the datasource (e.g. 华东 -> dim_region.big_region). */
	private boolean value = true;

	/** Prepend earlier turns of the conversation to the recall query. */
	private boolean multiTurn = true;

	/** Number of tables handed to the next node. */
	private int topK = 10;

	/** Reciprocal-rank-fusion constant. */
	private int rrfK = 60;

	private int vectorTopK = 30;

	private int columnTopK = 80;

	/** A string column is indexed as an enum when it has at most this many distinct values. */
	private int maxEnumDistinct = 60;

}
