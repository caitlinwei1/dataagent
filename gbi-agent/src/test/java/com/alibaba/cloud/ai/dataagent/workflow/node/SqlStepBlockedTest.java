package com.alibaba.cloud.ai.dataagent.workflow.node;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.GbiNl2sqlProperties;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.service.report.EvidenceLedger;
import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.SqlGenerateDispatcher;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B6 sem-r2: the third SQL step of a 4-step plan failed 10 times and the whole run ended without a report. Now the
 * step is marked blocked and the plan continues, so the report can say which step is 受阻.
 */
class SqlStepBlockedTest {

	private static final String PLAN = """
			{"thought_process":"t","execution_plan":[
			{"step":1,"tool_to_use":"SQL_GENERATE_NODE","tool_parameters":{"instruction":"a"}},
			{"step":2,"tool_to_use":"SQL_GENERATE_NODE","tool_parameters":{"instruction":"b"}},
			{"step":3,"tool_to_use":"SQL_GENERATE_NODE","tool_parameters":{"instruction":"c"}},
			{"step":4,"tool_to_use":"REPORT_GENERATOR_NODE","tool_parameters":{"instruction":"d"}}]}""";

	@Test
	@SuppressWarnings("unchecked")
	void exhaustedStepIsMarkedBlockedAndPlanContinues() throws Exception {
		DataAgentProperties props = new DataAgentProperties();
		GbiNl2sqlProperties gbi = new GbiNl2sqlProperties();
		gbi.setMaxSqlReplans(0);
		SqlGenerateNode node = new SqlGenerateNode(mock(Nl2SqlService.class), props, gbi);
		OverAllState s = new OverAllState();
		for (String k : new String[] { PLANNER_NODE_OUTPUT, PLAN_CURRENT_STEP, SQL_GENERATE_COUNT,
				SQL_REGENERATE_REASON, SQL_EXECUTE_NODE_OUTPUT, SQL_GENERATE_OUTPUT, SQL_REPLAN_COUNT }) {
			s.registerKeyAndStrategy(k, new ReplaceStrategy());
		}
		s.updateState(Map.of(PLANNER_NODE_OUTPUT, PLAN, PLAN_CURRENT_STEP, 3, SQL_GENERATE_COUNT,
				props.getMaxSqlRetryCount(), SQL_REGENERATE_REASON,
				SqlRetryDto.sqlExecute("comment not allow"), SQL_EXECUTE_NODE_OUTPUT,
				Map.of("step_1", "{\"data\":[{\"a\":\"1\"}]}", "step_2", "{\"data\":[{\"b\":\"2\"}]}")));

		Map<String, Object> out = node.apply(s);
		assertEquals(SQL_STEP_BLOCKED, out.get(SQL_GENERATE_OUTPUT));
		assertEquals(4, out.get(PLAN_CURRENT_STEP));
		s.updateState(out);
		assertEquals(PLAN_EXECUTOR_NODE, new SqlGenerateDispatcher(props).apply(s));

		Map<String, String> results = (Map<String, String>) out.get(SQL_EXECUTE_NODE_OUTPUT);
		assertTrue(results.get("step_3").startsWith("【降级】第 3 步") && results.get("step_3").contains("comment not allow"),
				results.get("step_3"));
		var ledger = EvidenceLedger.build(List.of("SQL", "SQL", "SQL", "REPORT"), results);
		assertEquals(EvidenceLedger.Status.BLOCKED, ledger.get(2).status());
	}

}
