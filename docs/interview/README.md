# 面试讲稿

目的：让简历上每一条都能讲清楚"原来有什么问题 → 发现的 bad case 和根因 → 怎么解决的 → 效果从多少到多少"，再加上为什么这么做、其他方案、难点和追问。

和已有文档的关系：`docs/design/stepN-*.md` 是每一步的完整设计记录；`docs/resume-map.md` 是简历逐条的简表。这里是**按面试顺序重新组织的讲稿**，每条都指到具体的类和方法，并标出哪些数字是实测、哪些是占位。

## 怎么用

1. 先背 [00 架构一页纸](00-architecture.md)：第 0 节是"业务和数据从哪来 → agent 为什么这么设计"（以宝尊为原型），然后能 2 分钟讲完一次请求从入口到报告。
2. 每条讲稿先背"一句话讲法"，再看 bad case 表，最后过一遍追问。
3. 打开代码对着看：每条第 3 节列了文件和方法，至少把 `TableRecallService.rank/fuse`、`SqlGuard.check`、`HumanFeedbackNode.decide`、`DockerCodePoolExecutorService.createHostConfig/currentRunOnly`、`ReportAssembler.assemble` 这 5 处读一遍。
4. 看 [99 缺口表](99-gaps.md)，知道哪些话不能说满。

## 目录

| # | 简历要点 | 讲稿 | 主要 bad case |
|---|---|---|---|
| 00 | 整体架构 | [00-architecture.md](00-architecture.md) | — |
| 01 | Schema 召回，180 题 Recall@5 | [01-schema-recall.md](01-schema-recall.md) | 上游召回不看问题；关联表 v1 掉 13 点；口径被软删除 |
| 02 | 6 类 StateGraph 节点 | [02-stategraph-nodes.md](02-stategraph-nodes.md) | `"END"` vs `"__END__"`；计数器共用；降级标志不归零 |
| 03 | HITL 计划审核 + threadId 恢复 + 最多 3 次 | [03-hitl.md](03-hitl.md) | 重启后恢复无输出；驳回意见丢失 |
| 04 | SQL 生成 + 4 道校验 + 错误分类 | [04-sql-generation-guard.md](04-sql-generation-guard.md) | P01 连续 10 次写 `2026-02-29`；MySQL 1370；类目层级 |
| 05 | Python Docker 沙箱 + 降级 | [05-python-sandbox.md](05-python-sandbox.md) | CPU 1.99 核；跨任务输出泄漏 7/8 |
| 06 | ECharts + Markdown 报告，OpenAPI | [06-report-openapi.md](06-report-openapi.md) | R07 把 -50% 写成 +0.74%；R25 空数据写出完整报告 |
| 07 | 320 题评测集 + 三项指标 | [07-eval-and-metrics.md](07-eval-and-metrics.md) | 13 道错题按口径分类 |
| 08 | 多数据源 / Turbo·Mix / 混合部署 | [08-datasources-modes-hybrid.md](08-datasources-modes-hybrid.md) | 思考开关没发出去（108 s）；ThreadLocal 换线程丢失 |
| 09 | RuoYi 网关、鉴权、数据权限 | [09-ruoyi-security.md](09-ruoyi-security.md) | 召回后过滤仍被证据绕过；`switchIfEmpty` 坑 |
| 10 | 落到宝尊：七个业务难点，问题在哪、方案对比、修复前后 | [10-baozun-scenario.md](10-baozun-scenario.md) | 收入口径取错月份（已修）；校验无上限（已修流程）；计划与校验锁死（已修流程，归因质量未修） |
| 11 | B4/B5/B6 跑完但答案错：逐节点根因 | [11-b4-b6-root-cause.md](11-b4-b6-root-cause.md) | 精选静默丢表；枚举值被当过滤条件；汇总表口径藏住根因；重名列互相覆盖 |
| 12 | 借鉴语义层：口径合约、品牌/角色口径、汇总对账、报告证据台账 | [12-semantic-layer.md](12-semantic-layer.md) | 模型连续 3 次不听口径提示；口径套到诊断步骤；B8 品牌合同口径；B10 汇总表一半是关单 |
| 99 | 缺口表 | [99-gaps.md](99-gaps.md) | — |

## 1 分钟自我介绍版

> 我复现了阿里云析言 GBI 的企业问数链路，场景是电商经营加库存履约，86 张表。底座是开源的 Spring AI Alibaba DataAgent，图的骨架是借的，我做了五处改造，每处都有前后对比：
> 一是选表，上游召回根本不看问题，Recall@5 只有 5.6%，修复后加了 6 路召回用 RRF 融合，到 86%；
> 二是 SQL，加了 JSqlParser 守卫和三类错误分类，40 题配对实验执行成功率 86.7% 到 100%；
> 三是人工审核，支持改计划、MySQL 检查点，重启后能按 threadId 恢复；
> 四是 Python 沙箱，发现上游的 CPU、内存限额都没生效，池化容器还会串输出，修完从 7/8 泄漏到 0；
> 五是报告接口，系统附证据、做数字溯源，并把合计从模型手里拿走，修掉了"报告把 -50% 写成 +0.74%"的问题。
> 评测集是自己建的 320 题，按题族和月份切 dev/holdout。目前的主要短板是口径类错误，EX 还在 57% 左右，下一步是确定性的口径规则——在 Turbo/Mix 的实验里，规则加案例能把 23 题上的 EX 从 56.5% 提到 91.3%。

## 必背数字（全部实测）

| 项 | 数字 |
|---|---|
| 召回 Recall@5 | 5.6% → 69.8%（修 bug）→ 86.2%（全通道）；注释缺一半 82.0% |
| SQL 守卫 | 75/75，误拦 0/30，3 ms/条；错误分类 14/14 |
| 40 题配对 | 执行成功 86.7% → 100%；首次成功 76.7% → 90%；EX 50.0% → 56.7%；多步完成 60% → 60% |
| 沙箱 | CPU 1.99 → 1.00 核；内存 1000 → 500 MiB；泄漏 7/8 → 0/12 |
| HITL | 重启恢复：内存检查点无输出 → MySQL 检查点 132.7 s 跑完 |
| 报告 | 8 题完成 3；系统 5.0 min vs 人工估算 64.3 min；R07 错 → 对 |
| Turbo/Mix（分支） | EX 56.5% / 73.9% / 91.3%；Turbo 快 2.5 倍、成本降 83% |
| 数据权限（分支） | 1,276/1,276 一致，380/380 拦截，0 误拦；网关 +0.98 ms p50 |

## 下一步

建议做 2~3 轮模拟面试：每轮挑 3 条，面试官角度连续追问 5 层，记录答不上来的点回填到对应讲稿的"追问"一节。
