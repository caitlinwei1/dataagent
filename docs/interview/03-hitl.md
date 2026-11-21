# 03 人工审核：计划展示 / 修改 / 驳回，threadId 恢复，最多 3 次重规划

## 一句话讲法

> "规划完先停下来给人看计划。我用 `interruptBefore` 暂停、检查点存 MySQL，审核人可以确认、直接改计划 JSON、或者驳回写原因；驳回意见会累积，最多重规划 3 次。重启后用同一个 threadId 还能接着执行。"

## 1. 原来有什么问题

上游已经有"确认 / 驳回 + threadId 恢复"，但有 6 个问题（读代码 + 实测）：

| 问题 | 后果 |
|---|---|
| 不能修改计划 | 想"给第 1 步加个过滤条件"只能驳回让模型重写，可能顺手改坏别的步骤 |
| 驳回计数和计划结构修复共用 `PLAN_REPAIR_COUNT` | 模型自己修了几次格式，用户还没驳回就超限 |
| 上限在进入节点时检查 | 第 3 次重规划出的计划刚展示，就被判超限，用户连确认的机会都没有 |
| 超限时写的是字符串 `"END"` | 实测抛 `GraphRunnerException: cannot find edge mapping` |
| 规划器只看到最后一次驳回意见 | 第 2 次说"加上同比"，第 1 次的"按省份拆分"丢了 |
| 检查点只在 JVM 内存（MemorySaver） | 审核期间重启或请求打到另一个实例，threadId 永远恢复不了 |

## 2. Bad case 与根因（`results/step5/` 有端到端记录）

- **重启后恢复失败**：内存检查点下，暂停 → 重启后端 → 用同一 threadId 发"确认"，**没有任何输出**。根因：状态只在内存里。换 MySQL 检查点后同样流程能完整跑到报告（132.7 秒）。
- **累积意见丢失**：连续两次驳回，第二次的计划只满足第二条意见。根因：`PLAN_VALIDATION_ERROR` 每次被覆盖。
- **到上限报错**：第 4 次驳回时抛异常。根因：`"END"` vs `"__END__"`。

## 3. 怎么解决的（代码）

- `dataagent/service/graph/GraphServiceImpl.java`
  - 构造器：`CompileConfig.builder().interruptBefore(HUMAN_FEEDBACK_NODE)`；`gbi.hitl.checkpoint=mysql` 时注册框架自带的 `MysqlSaver`（表 `GRAPH_CHECKPOINT` / `GRAPH_THREAD`，放在元数据库）。
  - `handleNewProcess`：新问题把 `HUMAN_REPLAN_COUNT`、`PLAN_REPAIR_COUNT`、`HUMAN_REJECT_REASONS`、`HUMAN_FEEDBACK_DATA` 清零。
  - `handleHumanFeedback`：`compiledGraph.updateState(threadId, {HUMAN_FEEDBACK_DATA: ...})` → `compiledGraph.stream(null, resumeConfig)` 从断点继续。
- `dataagent/workflow/node/HumanFeedbackNode.java` 的 `decide()`（纯函数，好测）：
  - 没反馈 → `human_next_node = WAIT_FOR_FEEDBACK` → `HumanFeedbackDispatcher` 返回 END，图暂停。
  - 确认 + 有 `edited_plan` → 用规划器同一个解析器 `PlanProcessUtil.parsePlan` 校验，能解析就替换 `PLANNER_NODE_OUTPUT` 直接执行（之后 `PlanExecutorNode` 仍做结构校验）；解析不了按驳回处理，把修改内容当原因。
  - 驳回 → `HUMAN_REPLAN_COUNT+1`，原因追加到 `HUMAN_REJECT_REASONS`，拼成编号列表写进 `PLAN_VALIDATION_ERROR` 给规划器；**驳回进来时才检查上限**，`replans >= maxReplans` 才结束，用 `StateGraph.END`。
- 配置：`GbiHitlProperties`，`gbi.hitl.max-replans=3`。

## 4. 为什么这么做

| 决策 | 选择 | 备选 | 为什么没选 |
|---|---|---|---|
| 怎么等人 | `interruptBefore` + 检查点 | 节点里阻塞等待 | 占着线程和 SSE 连接，审核人可能半小时后才回来 |
| 修改方式 | 结构化计划 JSON（同时保留自然语言驳回） | 只允许自然语言意见 | 要再过一次 LLM，可能改坏别的步骤；JSON 是改什么执行什么，可审计 |
| 检查点存哪 | MySQL（`MysqlSaver`） | Redis | 多一个依赖，还要考虑过期；审核记录本身是审计数据，适合落库 |
| | | 文件 `FileSystemSaver` | 多实例不共享 |
| 默认值 | 仍是内存 | | 不改变上游零依赖启动的体验 |
| 上限为什么 3 | 产品取舍 | 无限 / 1 次 | 驳回 3 次还不满意通常是问题没说清或数据不支持，继续猜只烧钱 |
| 意见累积 | 编号列表，要求全部满足 | 只传最新 | 后一条通常是补充不是推翻 |

## 5. 难点

- **"暂停"在框架里的语义**：`interruptBefore` 是在进入节点**之前**停，恢复时先 `updateState` 把反馈写进状态，再 `stream(null)` 让框架从检查点继续，`HumanFeedbackNode` 才真正执行并读到反馈。要理解为什么 `HumanFeedbackDispatcher` 在没反馈时返回 END（"等待"在图里就是结束本次流）。
- **上限检查的时机**：放在"驳回进来时"，而不是"进入节点时"，这样第 3 次重规划的结果用户还能确认。
- **两种计数器的语义分离**：模型的自我修复不应该消耗用户的驳回预算。

## 6. 效果

| 指标 | 结果 | 性质 |
|---|---|---|
| 单测 | 9/9：等待、确认、修改、修改解析失败、驳回、第 3 次仍可确认、第 4 次结束、累积意见、结构修复不占驳回预算 | 实测 |
| 端到端（真实模型） | 确认 / 修改 / 连续驳回到上限三个场景都按预期；修改后执行的 SQL 只查华东；驳回后的计划同时满足全部意见 | 实测，`results/step5/` |
| 重启恢复 | 内存检查点：重启后确认**无输出**；MySQL 检查点：完整执行到报告，132.7 s | 实测 |
| "审核让结果变好多少" | **没有量化** | 缺口。补法：从 320 题里挑 20 道多步题，模拟审核人按固定规则驳回（例如计划漏了题目里的维度就驳回），比较开/关审核的 EX 和完成率 |

## 7. 追问

1. **审核期间库里数据变了怎么办？** 计划只描述"查什么"，SQL 在确认之后才生成和执行，总是查最新数据。
2. **审核人把计划改成危险操作怎么办？** 改后的计划仍过 `PlanExecutorNode` 结构校验；生成的 SQL 还要过守卫（只读、白名单）和只读账号。
3. **SSE 连在 A 实例，恢复请求打到 B 实例？** 有 MySQL 检查点，B 能读到状态继续执行，结果推给发恢复请求的连接。上游内存检查点下直接失败。
4. **检查点会无限增长吗？** 会。生产需要按时间清理已结束线程，我没做——可以主动说。
5. **别人知道我的 threadId 能不能替我审核？** 上游可以（IDOR）。在鉴权分支里加了 `gbi_thread_owner` 表，恢复审核和读报告都校验归属，实测返回 403。
