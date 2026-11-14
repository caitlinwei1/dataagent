/*
 * GBI repro: offline evaluation endpoints.
 */
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.mapper.AgentDatasourceMapper;
import com.alibaba.cloud.ai.dataagent.service.schema.recall.TableRecallService;
import com.alibaba.cloud.ai.dataagent.service.schema.recall.TableRecallService.Channels;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.SimpleVectorStoreInitialization;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Table-recall ranking for the Recall@5 eval: mode "original" (b297203) or "v2" (multi-channel). */
@RestController
@RequestMapping("/api/eval")
@AllArgsConstructor
public class EvalController {

	private final TableRecallService tableRecallService;

	private final AgentDatasourceMapper agentDatasourceMapper;

	private final org.springframework.beans.factory.ObjectProvider<SimpleVectorStoreInitialization> vectorStoreFile;

	@Data
	public static class RecallRequest {

		private Long agentId;

		private String query;

		private List<String> context = List.of();

		private String mode = "v2";

		/** Subset of vector,column,keyword,term,value,join,multiTurn; null = configured defaults. */
		private List<String> channels;

		private int topK = 10;

	}

	@PostMapping("/schema-recall")
	public Map<String, Object> schemaRecall(@RequestBody RecallRequest req) {
		Integer ds = agentDatasourceMapper.selectActiveDatasourceIdByAgentId(req.getAgentId());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("datasourceId", ds);
		if ("original".equals(req.getMode())) {
			out.put("tables", tableRecallService.originalTables(ds, req.getTopK()));
			return out;
		}
		Channels ch = req.getChannels() == null ? tableRecallService.defaults()
				: new Channels(req.getChannels().contains("vector"), req.getChannels().contains("column"),
						req.getChannels().contains("keyword"), req.getChannels().contains("term"),
						req.getChannels().contains("value"), req.getChannels().contains("join"),
						req.getChannels().contains("multiTurn"));
		List<TableRecallService.Ranked> ranked = tableRecallService.rank(req.getAgentId(), ds, req.getQuery(),
				req.getContext(), ch);
		List<TableRecallService.Ranked> top = ranked.subList(0, Math.min(req.getTopK(), ranked.size()));
		out.put("tables", top.stream().map(TableRecallService.Ranked::table).toList());
		out.put("detail", top);
		return out;
	}

	/** Write the in-memory vector store to its file now (it is otherwise only written on graceful shutdown). */
	@PostMapping("/vectorstore/save")
	public Map<String, Object> saveVectorStore() {
		SimpleVectorStoreInitialization init = vectorStoreFile.getIfAvailable();
		if (init != null) {
			init.save();
		}
		return Map.of("saved", init != null);
	}

	@PostMapping("/schema-recall/invalidate")
	public Map<String, Object> invalidate(@RequestParam Long agentId) {
		tableRecallService.invalidate(agentDatasourceMapper.selectActiveDatasourceIdByAgentId(agentId));
		return Map.of("ok", true);
	}

}
