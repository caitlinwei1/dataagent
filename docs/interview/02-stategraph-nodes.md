# 02 6 类 StateGraph 节点（意图 / 召回 / 规划 / SQL / Python / 报告）

## 一句话讲法

> "流程用 Spring AI Alibaba Graph 的 StateGraph 编排，代码里实际是 16 个节点、按职能归成 6 类。图的骨架来自开源 DataAgent，我主要改的是节点内部和失败路径：每条失败路径都要有上限、有原因、能结束。"

## 1. 6 类 ↔ 16 个节点（被问"6 个是哪 6 个"时要能对上）

| 类 | 节点（`dataagent/workflow/node/`） | 我改了什么 |
|---|---|---|
| 意图 | `IntentRecognitionNode`、`EvidenceRecallNode`、`QueryEnhanceNode`、`FeasibilityAssessmentNode` | 证据里加【时间口径】（`TimeRangeHints`） |
| 召回 | `SchemaRecallNode`、`TableRelationNode` | 7 路召回（见 01） |
| 规划 | `PlannerNode`、`PlanExecutorNode`、`HumanFeedbackNode` | 审核：修改/累积驳回/独立上限/MySQL 检查点（见 03） |
| SQL | `SqlGenerateNode`、`SemanticConsistencyNode`、`SqlExecuteNode` | 守卫 + 错误分类 + 修复提示（见 04） |
| Python | `PythonGenerateNode`、`PythonExecuteNode`、`PythonAnalyzeNode` | 沙箱限额生效、输出隔离、显式降级（见 05） |
| 报告 | `ReportGeneratorNode` | 预先算合计；接口侧证据附录与溯源（见 06） |

## 2. 原来有什么问题（图层面）

上游的图"能跑通 happy path"，但失败路径有不少坑。下面这些都是**读代码 + 实测**确认的：

| Bad case | 根因 | 怎么修的 | 记录 |
|---|---|---|---|
| 审核驳回到上限时抛 `GraphRunnerException: cannot find edge mapping`，而不是正常结束 | `HumanFeedbackNode` 往条件边里写了字符串 `"END"`，真正的结束节点 id 是 `StateGraph.END`（`"__END__"`），条件边的映射表里没有 `"END"` | 改用 `StateGraph.END`（见 `HumanFeedbackNode.decide()` 里的注释） | `docs/design/step5-hitl.md` |
| 模型自己修了 3 次计划格式错误，用户一次没驳回就被判超限 | 人工驳回和"计划结构自动修复"共用 `PLAN_REPAIR_COUNT` | 拆出 `HUMAN_REPLAN_COUNT` | step5 单测 |
| Python 降级后，同一会话下一题即使代码是对的也直接走降级 | `PYTHON_FALLBACK_MODE` 没有归零 | 分析节点和新问题开始时都归零 | step6 `PythonFallbackTest` |
| P01 连续 10 次生成同一个非法日期 `2026-02-29`，直到 SQL 重试耗尽 | 修复循环里，改写后的问题仍写着"至2026-02-29"，模型每次都照抄 | 证据里注入日历算好的半开区间 + 针对非法日期的修复提示 | step7 冒烟 |
| R05：计划写"通过 prd_spu 关联大区"（路径不存在），SQL 走了正确路径反而被语义校验驳回 10 次 | 语义校验以计划为准；计划本身错了，SQL 层怎么改都过不了 | **没修**：需要从 SQL 层回退到规划器。是已知缺陷 | step8 |
| （未合并分支）Turbo 模式在生成图表配置时退回了默认模型 | 模式放在 ThreadLocal，`SqlExecuteNode` 在另一个线程里调 LLM | 进入 `Flux.create` 前取出、在新线程里重新设置 | step10 分支 |

## 3. 怎么做的（框架机制，要能讲清）

- **定义**：`DataAgentConfiguration` 里 `new StateGraph(keyStrategyFactory)`，`addNode` 注册 16 个节点，`addEdge` / `addConditionalEdges` 连线；每个条件边是一个 `*Dispatcher implements EdgeAction`，只读 state 返回下一个节点名。
- **状态**：`OverAllState` 是一个共享 Map，每个 key 声明合并策略（这里全是 `REPLACE`，另一种常见的是 `APPEND`，用于消息列表）。节点返回的 Map 按策略合并。
- **循环**：图里有 4 个环：计划修复（≤2）、人工驳回（≤3）、SQL 重写（≤10）、Python 重写（≤5）。**每个环的计数器都在 state 里，由 Dispatcher 判断上限**。
- **调度器模式**：`PlanExecutorNode` 每次读 `PLAN_CURRENT_STEP`，按计划里那一步的 `tool_to_use` 决定去 SQL 还是 Python；做完回到它；最后一步之后去报告。这样计划有几步都不用改图。
- **暂停与恢复**：`compile(interruptBefore(HUMAN_FEEDBACK_NODE))`；恢复时 `updateState(threadId, 反馈)` 再 `stream(null, config)`。
- **流式**：节点通过 `FluxUtil.createStreamingGeneratorWithMessages` 往 SSE 推中间结果，`GraphServiceImpl.subscribeToFlux` 把 `NodeOutput` 转成 `ServerSentEvent`。

## 4. 为什么用 StateGraph（备选方案）

| 方案 | 为什么没选 |
|---|---|
| 一个大 Agent（ReAct，模型自己决定调哪个工具） | 企业问数步骤是确定的；ReAct 的路径不可控、不可审计，失败时不好定位；也没法在固定位置插人工审核 |
| 硬编码的 Service 串行调用 | 重试环、暂停恢复、流式输出、检查点都要自己写 |
| LangGraph（Python） | 思路一样（StateGraph、interrupt、checkpointer 都是同一套概念），但团队技术栈是 Java/Spring，上游就是 SAA Graph |
| 工作流引擎（Flowable/Temporal） | 适合长事务业务流程，不擅长 LLM 流式输出，太重 |

优点：路径确定、每步可观测（SSE 每个节点都有输出）、环都有上限、可以在节点边界暂停。
缺点：新增一种能力要改图；节点多、状态字段多（40+ 个 key），状态命名和清零容易出错（上面的降级不归零就是这类 bug）。

## 5. 技术难点

- **状态清零**：同一个 threadId 问下一题时，哪些字段要清（计数器、反馈、降级标志）、哪些要留（多轮上下文）。`GraphServiceImpl.handleNewProcess` 显式地把计数器重置。
- **失败路径要"能结束"**：每个环都要有上限，到上限要走到 END 并留下原因，而不是抛异常。
- **多实例**：上游的 StreamContext、多轮上下文、检查点都在 JVM 内存里，只能单实例。我把检查点挪到了 MySQL；StreamContext 仍在内存（SSE 连接本身就绑实例）。

## 6. 效果

这一条本身没有单独的指标，效果体现在 03/04/05/06 各自的数字里。可以说："每一处改造都有开关可以关掉，用来做前后对比。"

## 7. 追问

1. **节点之间怎么传数据？** 共享 `OverAllState`，每个 key 有合并策略；节点只返回自己要改的字段。
2. **为什么 Dispatcher 和 Node 分开？** Node 做事、写状态；Dispatcher 只做路由判断，是纯函数，便于单测，也便于在图定义里一眼看到所有分支。
3. **图里有环，怎么保证不会死循环？** 4 个环各有计数器和上限，计数器在 state 里，Dispatcher 判断。还要注意计数器在新问题时要重置。
4. **一个节点失败（比如 LLM 超时）会怎样？** 节点抛异常会进 `handleStreamError`，SSE 推一条 error 并清理上下文；业务性失败（SQL 错、Python 错）不抛异常，而是写进 state 由 Dispatcher 决定重试还是降级。
5. **为什么不用一个 ReAct Agent？** 见第 4 节第一行。
