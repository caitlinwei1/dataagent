package com.alibaba.cloud.ai.dataagent.service.code.impls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DockerLogFilterTest {

	private static final Instant START = Instant.parse("2026-11-15T05:00:00.500000000Z");

	@Test
	void dropsLinesOfEarlierRunsAndStripsTimestamps() {
		String payload = "2026-11-15T05:00:00.100000000Z secret of the previous task\n"
				+ "2026-11-15T05:00:00.600000000Z {\"华东\": 40}\n" + "2026-11-15T05:00:01.000000000Z done\n";
		assertEquals("{\"华东\": 40}\ndone\n", DockerCodePoolExecutorService.currentRunOnly(payload, START));
	}

	@Test
	void keepsLinesWithoutTimestampAndWithoutStartTime() {
		assertEquals("raw line", DockerCodePoolExecutorService.currentRunOnly("raw line", START));
		assertEquals("x\n", DockerCodePoolExecutorService.currentRunOnly("2026-11-15T04:00:00Z x\n", null));
	}

}
