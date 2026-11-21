# 00 整体架构一页纸：一次请求从入口到报告

> 背下来的目标：面试官说"讲讲你这个项目"，你能用 2 分钟把这一页讲完，并且每个框都能接着往下钻。
> 路径缩写：`dataagent/` = `gbi-agent/src/main/java/com/alibaba/cloud/ai/dataagent/`

## 1. 一句话

复现阿里云百炼"析言 GBI"的企业问数链路：用户问一句业务问题（"3 月华东数码类 GMV 环比为什么跌了"），系统完成 **选表 → 规划 → 写 SQL 并校验 → 执行 → Python 计算 → 带图表和证据的报告**，场景是电商经营 + 库存/仓储/履约（86 张表、1,146 字段、312 条业务术语）。

底座是开源的 spring-ai-alibaba/DataAgent（固定在 commit b297203），**图的骨架和状态字段是借的**，我做的是：数据集与评测集、召回、SQL 守卫、人工审核改造、沙箱修复、报告接口，以及（未合并分支上的）RuoYi 式鉴权与数据权限、Turbo/Mix 和混合部署。

## 2. 2 分钟讲法（照这个顺序讲）

1. **入口**：前端/调用方发 `GET /api/stream/search`（SSE 流）或 `POST /openapi/v1/reports`（同步拿整份报告）。带 `threadId`（就是 conversationId）。
2. **起图**：`GraphServiceImpl.graphStreamProcess` → `handleNewProcess`，把问题、agentId、多轮上下文、各种计数器清零后 `compiledGraph.stream(初始状态, threadId)`。图在启动时 `compile(interruptBefore(HUMAN_FEEDBACK_NODE))`，检查点可以存 MySQL。
3. **理解问题**：意图识别（闲聊直接结束）→ 证据召回（业务术语/口径 + 我加的【时间口径】）→ 问题改写。
4. **选表**：`SchemaRecallNode` 调 `TableRecallService`，7 路召回 + RRF 融合取前 K 张表 → `TableRelationNode` 补外键关系 → 可行性评估（不是数据分析问题或需要澄清就结束）。
5. **规划**：`PlannerNode` 让 LLM 输出 JSON 执行计划（每步用 SQL 还是 Python）→ `PlanExecutorNode` 校验计划结构，开了人工审核就停在 `HumanFeedbackNode` 前，等人确认/修改/驳回，之后按 threadId 恢复。
6. **逐步执行计划**：`PlanExecutorNode` 是"调度器"，按当前步骤分派：
   - SQL 步：`SqlGenerateNode`（Qwen 生成）→ `SemanticConsistencyNode`（LLM 检查是否回答了问题）→ `SqlExecuteNode`（**JSqlParser 守卫** → 只读执行 → 失败按"选表/方言/执行"分类，带原因回到生成，最多 10 次）。
   - Python 步：`PythonGenerateNode` → `PythonExecuteNode`（Docker 容器池，1 核/500MiB/60s/无网络）→ 失败回去重写，5 次后**显式降级** → `PythonAnalyzeNode`。
   - 每步结束回到 `PlanExecutorNode` 取下一步。
7. **出报告**：`ReportGeneratorNode` 写 Markdown（内嵌 ECharts JSON），报告前系统先算好合计（`ResultTotals`）；开放接口再由 `ReportAssembler` 附上**系统生成的证据附录**、校验图表、做数字溯源（`NumberTrace`），返回 `status / issues`。

## 3. 图（节点就是代码里的 16 个 addNode）

```mermaid
flowchart TD
  API["/api/stream/search (SSE)<br/>/openapi/v1/reports (同步)"] --> GS[GraphServiceImpl<br/>threadId · 初始状态 · 检查点]
  GS --> IR[IntentRecognitionNode<br/>意图] -->|闲聊| END1((END))
  IR --> ER[EvidenceRecallNode<br/>口径 + 时间口径] --> QE[QueryEnhanceNode<br/>改写]
  QE --> SR[SchemaRecallNode<br/>7 路召回 + RRF] --> TR[TableRelationNode] --> FA[FeasibilityAssessmentNode]
  FA -->|需澄清/非分析| END2((END))
  FA --> PL[PlannerNode<br/>JSON 计划] --> PE{PlanExecutorNode<br/>校验 + 调度}
  PE -->|结构错误 ≤2 次| PL
  PE -->|开启审核| HF[HumanFeedbackNode<br/>interruptBefore 暂停]
  HF -->|驳回 ≤3 次| PL
  HF -->|确认/修改| PE
  PE -->|SQL 步| SG[SqlGenerateNode] --> SC[SemanticConsistencyNode] -->|不一致| SG
  SC --> SX[SqlExecuteNode<br/>守卫 · 只读 · 错误分类] -->|失败, 带原因| SG
  SX --> PE
  PE -->|Python 步| PG[PythonGenerateNode] --> PX[PythonExecuteNode<br/>Docker 池] -->|失败 <5| PG
  PX -->|成功 / 第 5 次失败降级| PA[PythonAnalyzeNode] --> PE
  PE -->|最后一步| RG[ReportGeneratorNode<br/>Markdown + ECharts] --> END3((END))
  RG -.-> RA[ReportAssembler<br/>证据附录 · 图表校验 · 数字溯源]
```

## 4. 关键代码位置速查

| 环节 | 文件 / 方法 |
|---|---|
| 图的定义（节点、边、状态字段 KeyStrategy） | `dataagent/config/DataAgentConfiguration.java`（`addNode` / `addConditionalEdges`） |
| 编译、暂停、检查点、恢复 | `dataagent/service/graph/GraphServiceImpl.java`：构造器里 `interruptBefore` + `MysqlSaver`；`handleNewProcess`；`handleHumanFeedback`（`updateState` 后 `stream(null)`） |
| 路由逻辑 | `dataagent/workflow/dispatcher/*Dispatcher.java`（每个条件边一个类） |
| 召回 | `dataagent/service/schema/recall/TableRecallService.java`：`rank()`、`fuse()`（RRF）、`termTables`、`joinTables`、`valueIndex` |
| 时间口径 | `dataagent/service/nl2sql/TimeRangeHints.java`，在 `EvidenceRecallNode` 注入 |
| SQL 守卫 / 错误分类 | `dataagent/service/sql/guard/SqlGuard.check()`、`SqlErrorClassifier.classify()/repairHint()`；在 `SqlExecuteNode.guard()/blocked()` 调用 |
| 人工审核 | `dataagent/workflow/node/HumanFeedbackNode.decide()`、`service/hitl/GbiHitlProperties` |
| 沙箱 | `dataagent/service/code/impls/DockerCodePoolExecutorService.java`：`createHostConfig()`、`execTaskInContainer()`、`currentRunOnly()`；池在 `AbstractCodePoolExecutorService` |
| 降级 | `PythonExecuteNode`（输出 `{"degraded":true,...}`）、`PythonExecutorDispatcher`、`PythonAnalyzeNode` |
| 报告接口 | `dataagent/controller/ReportApiController.java`、`service/report/ReportAssembler / NumberTrace / ResultTotals` |
| 评测 | `eval/table_select`（180 题）、`eval/sql_guard`（75 例）、`eval/nl2sql`（320 题）、`eval/report`（40 任务）、`eval/scripts/run_*.py` |

## 5. 状态怎么流动（被问"节点之间怎么传数据"时用）

- 所有节点共享一个 `OverAllState`（一个 Map），每个 key 在 `DataAgentConfiguration` 里声明合并策略，这里全是 `REPLACE`。
- 节点 `apply(state)` 返回一个 Map，框架按策略合并进状态；Dispatcher 只读状态、返回下一个节点名。
- 检查点就是"每走完一个节点，把整个 state 序列化存一份"，key 是 threadId。所以暂停/恢复/重启后继续都靠它。
- 我新加的状态字段：`HUMAN_REPLAN_COUNT`、`HUMAN_REJECT_REASONS`、`HUMAN_REVIEW_STATUS`（审核）、`PYTHON_FALLBACK_MODE` 的语义（降级），未合并分支里还有 `GBI_DATA_PERMISSION`、`GBI_MODE`。

## 6. 讲的时候要守住的边界

- **不要说图是自己从零搭的。** 说"在开源 DataAgent 的图上，改了召回、守卫、审核、沙箱、报告五处，每处都有前后对比"。
- **数字只讲实测的**，简历上没跑到的要能说出"实测是多少、差在哪、怎么补"。见 [99-gaps.md](99-gaps.md)。
