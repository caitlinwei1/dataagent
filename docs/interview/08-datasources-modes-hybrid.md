# 08 多数据源（MySQL / PostgreSQL / Hologres·ADB-PG）+ Turbo / Mix + 云上本地混合部署

> **状态**：数据源部分在 master；Turbo/Mix 和混合部署在**未合并分支 `step10-turbo-mix-hybrid`**（设计文档 `docs/design/step10-turbo-mix-hybrid.md`）。面试前先合并或至少熟悉那个分支。

## 一句话讲法

> "数据集同时生成 MySQL 和 PostgreSQL 两份，守卫和错误分类两边都实测；Hologres/ADB-PG 走 PG 方言，没有真实实例。Turbo/Mix 我按官方文档解读成两组按请求生效的配置：Turbo 是小模型 + 精简链路 + 规则干预，Mix 是大模型 + 规则 + 案例学习；混合部署是在 `Accessor` 接口处切开，云端只发签名请求，本地网关只读执行、打码、审计。"

## 1. 多数据源

- **做了什么**：`dataset/` 同时生成 MySQL 和 PG（schema `gbi_ecommerce`）；只读账号两边都有；`SqlErrorClassifier` 两套错误码；守卫用 JSqlParser，两种语法都能解析。
- **Bad case**：MySQL 1370（PG 函数在 MySQL 上报权限错）→ 归为方言（见 04）；上游拼样本 SQL 没给 `year_month` 这种关键字列名加反引号 → `dim_date` 取样失败（直连也失败，没修）。
- **边界**：Hologres / ADB-PG **只能说"代码支持其方言和错误码，没有在真实实例上验证"**。补法：开一个按量付费的 Hologres 实例，把 PG 那份数据导进去，跑守卫用例和 40 题样本。

## 2. Turbo / Mix（step10 分支）

**先讲清楚"这是我的解读"**：官方文档只说 Turbo"响应迅速，可通过规则干预"，Mix"精度更高，可通过案例学习提升"，没公开模型和链路。

| 字段 | default（上游） | turbo | mix |
|---|---|---|---|
| 模型 | qwen-plus | qwen3.7-flash，关思考 | qwen-plus |
| 可行性评估（1 次 LLM） | 开 | 跳过 | 开 |
| 语义一致性（每条 SQL 1 次 LLM） | 开 | 跳过，靠 JSqlParser 守卫兜底 | 开 |
| SQL 重试 | 10 | 3 | 10 |
| 规则干预 | 关 | 开 | 开 |
| 案例学习 | 关 | 关 | 开（Top-3 相似案例） |

- **代码**：`service/mode/GbiModeProperties`；请求带 `mode=`；`GraphServiceImpl` 写进 `GBI_MODE`；`aop/GbiModeAspect` 包每个节点的 `apply()` 把模型放进 `ModeContext`（ThreadLocal）；`StreamLlmService/BlockLlmService` 用 `OpenAiChatOptions.model(...)` 覆盖；`LlmUsageMeter` 计 token。
- **规则**：`resources/gbi/rules.yaml` 6 条，关键词触发，把"必须遵守"的写法放进证据（一级类目要沿 parent_id 上溯两次、"华东"默认按店铺归属、订单月份用 `paid_at` 不用 `created_time`）。**全部来自 step7 错题**。
- **案例**：`eval/mode/cases.jsonl` 17 条，字符二元组 TF-IDF 取 Top-3。为什么不用向量：17 条，TF-IDF 零依赖、确定、可复现。
- **防泄漏**：案例取自 step7 用过的 17 个题族，评测题取自从未用过的 28 个题族，holdout 没动。

**踩坑（很好讲的两个）**
1. **思考没关掉**：Spring AI 1.1.0 合并调用选项时丢掉 `extraBody`，`enable_thinking:false` 根本没发出去。冒烟时 Turbo 一题 18k 输出 token、108 秒，比 Mix 还慢。修法：用请求头把开关带到自定义的 `DashScopeOpenAiApi`（`OpenAiApi` 子类）写进请求体；`ModeOptionsWireTest` 起本地 HTTP 服务断言请求体。修好后 19.7 s、634 token。
2. **换线程模式丢失**：`SqlExecuteNode` 在另一个线程调 LLM 生成图表配置，ThreadLocal 为空，退回默认模型。靠 `LlmUsageMeter` 的调用来源日志发现；进入 `Flux.create` 前取出、新线程里重设。

**效果（23 道 dev 配对，实测）**

| 模式 | EX | p50 耗时 | 元/题 |
|---|---|---|---|
| default | 13/23 = 56.5% | 35.1 s | 0.0512 |
| turbo 去规则 | 14/23 | 14.6 s | 0.0081 |
| **turbo** | **17/23 = 73.9%** | **13.9 s** | **0.0087** |
| mix 去案例 | 17/23 | 35.2 s | 0.0502 |
| **mix** | **21/23 = 91.3%** | 36.9 s | 0.0611 |

- Turbo vs default：快 2.5 倍、成本降 83%；消融显示 EX 提升来自规则，不是小模型。
- Mix vs default：8 胜 0 负（符号检验 p=0.008，唯一显著）；规则和案例各约 4 题。
- **噪声**：同一 Turbo 配置跑两次 EX 都是 17/23，但 6 题翻转。±3 题内不算数。

## 3. 混合部署：服务在云、数据在地（step10 分支）

- **切分点**：`Accessor` 接口（9 个方法：表列表、列信息、外键、采样、执行 SQL……）。新数据源类型 `mysql-gateway` / `postgresql-gateway` 对应 `GatewayAccessor`，把方法名和参数签名后发给本地网关。**接口以上的召回、守卫、Python、报告一行没改。**
- **网关**：同一个 jar 换主类（`com.alibaba.cloud.ai.gbigateway.GatewayApplication`，`scripts/gateway.sh`），不连元数据库、不调模型。强制：HMAC 签名 + 300 s 时间窗 + nonce 防重放；只读（JSqlParser 恰好一条 SELECT）；忽略请求里的 schema；最多 1000 行；手机号/姓名/证件/地址打码；每请求一行审计 JSON；报错时回传 SQLState + 错误码，云端重建 `SQLException`，错误分类照常。
- **为什么这样切**：VPN 直连要把库账号放云上；数据库协议代理要实现协议细节、打码只能在字节流上做；模型本地部署那是私有化。
- **效果（实测）**：276 条标准 SQL 直连 vs 网关结果 **276/276 一致**，额外耗时 p50 4.5 ms / p90 8.2 ms；混合部署上 Turbo 跑 23 题 EX 17/23（同直连）；初始化 schema 时 25 个敏感格子被打码；7 个安全用例全部通过（写操作、多语句、`INTO OUTFILE`、篡改签名、重放都被拒）。

## 4. 追问

1. **Turbo/Mix 是 GBI 的内部实现吗？** 不是，是我按官方文档描述的复现和解读；模型选择官方没公开。
2. **Mix 91.3% 是不是过拟合？** 案例和评测题来自不同题族；但 23 题太小，只有 Mix vs default 显著。holdout 还没跑。
3. **为什么按请求切换模式而不是按 agent？** 对比实验要同一个 agent、同一份向量、同一道题只换模式；按请求是按 agent 的超集。
4. **混合部署里查询结果还会回云端吗？** 会，和 GBI 官方流程一样（云端生成 SQL → 本地执行 → 结果回云端分析）。所以网关做打码、限行、审计，限制"能带走什么"。
5. **网关签名为什么还要防重放？** 网关端口对云开放，签名证明来源和完整性，nonce + 时间窗防止截获的合法请求被重放。
