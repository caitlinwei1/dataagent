# 09 RuoYi 式网关、登录鉴权与数据权限

> **状态**：在**未合并分支 `claude/project-thread-alilb6`**（设计文档 `docs/design/security-ruoyi-gateway-data-permission.md`，代码 `ruoyi/` + `gbi-agent/.../security/`）。

## 一句话讲法

> "上游完全没有鉴权，连模型 API Key 都能被读出来。我参考 RuoYi-Cloud 加了网关和认证服务：JWT 只是 Redis 会话的钥匙，网关删伪造头、签名后转发。数据权限参考 RuoYi-Cloud-Plus 用 JSqlParser 改写 SQL，但因为 SQL 是模型写的，我没有往 WHERE 追加条件，而是把受控表原地替换成带过滤的派生表。和物理隔离的视图副本逐条对比 1,276/1,276 一致，380 条越权全拦，0 误拦。"

## 1. 原来有什么问题

- 无鉴权：`/api/model-config/list` 返回 DashScope API Key；任何人能改删 agent/数据源；能查任何表。
- IDOR：知道别人的 threadId 就能替他审核计划、读他的报告；上游 `streamContextMap.computeIfAbsent(threadId).setSink(sink)` 甚至会把后来者的 SSE 接到别人的会话上。
- 企业问数要求"同一个问题不同人看到不同的数据"，但 SQL 是模型现写的，RuoYi 的 `@DataScope` 注解没有 Mapper 可加。

## 2. Bad case 与根因（端到端实测）

| Bad case | 根因 | 修复 |
|---|---|---|
| 仓库主管问"各店铺 GMV"：召回后只剩 `dim_shop`，模型还是从口径证据里学到 `trd_daily_shop_sales` 写进 SQL | 表级过滤只在召回后做不够，证据里也有表名 | 执行前用守卫解析出的全部表**再查一次**权限 |
| 同一问题被拦后重写满 10 次（86.8 s） | 权限错误被当成普通执行错误重试 | 连续 2 次权限拒绝就停，告诉用户超出权限：54.3 s、2 次 |
| 网关偶发 "Response ended prematurely" | `redis.get().flatMap(chain.filter).switchIfEmpty(401)`：`chain.filter()` 返回空 `Mono<Void>`，`switchIfEmpty` 在**每个成功请求后**都触发，改了已提交的响应 | 先把 Redis 结果包成 `Optional` 再分支（响应式编程的经典坑） |
| 评测第一次 72 条不一致 | 比较脚本问题：agent 输出 NULL 为空串、mysql CLI 输出 `NULL` | 修脚本，没改产品代码 |

## 3. 怎么做的

- **服务**：`ruoyi/gbi-gateway`（:8080，Spring Cloud Gateway）、`ruoyi/gbi-auth`（:9200，合并 ruoyi-auth + ruoyi-system）、`ruoyi/gbi-common-security`；gbi-agent 自己验签（`GatewayAuthFilter`）。
- **登录**：BCrypt；5 次错误锁 10 分钟；"用户不存在"和"密码错误"同一句话；LoginUser 存 Redis `login_tokens:{uuid}` TTL 720 分钟；JWT 只放 user_key（不带 exp）。注销删 key 立即失效。
- **网关**：删掉所有 `X-Gbi-*` 和 `from-source` 头 → 验 JWT → 查 Redis 会话 → LoginUser Base64 放 `X-Gbi-Login`，HMAC 签名（方法、路径、时间戳、user_key、LoginUser），agent 验签 60 s 窗口。按用户令牌桶限流（控 LLM 成本）。
- **功能权限**：`application-security.yml` 路径 → 权限串，未匹配默认 `gbi:admin`（fail closed）。
- **数据范围**：RuoYi data_scope 1–5 + 新表 `gbi_dept_data`（部门 → 店铺/仓库 id），登录时由 `DataScopeResolver` 解析成具体值。
- **表级**：`gbi_role_table`（模式匹配），召回后过滤 + 执行前再查。
- **行级**：`row-policies.yml`（脚本按元数据生成，50 张受控表；子表沿外键最多三层用 IN 子查询）；`RowPermissionRewriter` 把 `trd_order o` 改成 `(SELECT * FROM trd_order WHERE shop_id IN (...)) o`；模型看到的仍是原 SQL，执行的 SQL 进日志。
- **会话归属**：`gbi_thread_owner` 表，在 StreamContext 之前检查。

## 4. 为什么这么做

| 决策 | 选择 | 没选 | 原因 |
|---|---|---|---|
| 令牌 | JWT + Redis 会话（RuoYi） | 纯无状态 JWT | 注销/改权限要等过期，否则要黑名单（也是状态） |
| | | OAuth2 / Keycloak | 单应用不需要 SSO，太重 |
| | | Sa-Token（Plus 用的） | 封装太深；自己写一遍能讲清每一步。正式项目会直接用 |
| 下游信任 | 网关 HMAC 签名 | RuoYi 的内网信任 + `from-source` | 内网直连或头被改就失守；签名绑路径，拿查询签名调删除接口也不行 |
| | | mTLS / 服务网格 | 要证书体系，三个服务太重 |
| 行级过滤 | 派生表替换 | RuoYi-Plus 往 WHERE 追加 | 要处理模型起的别名；LEFT JOIN 的 WHERE 条件会把外连接变内连接；每层子查询/CTE/UNION 都要处理。Plus 处理的是人写的可控 SQL |
| | | PG RLS | MySQL 没有；要每个用户一个数据库角色，连接池无法共享 |
| | | 每角色一套视图 | 角色 × 表数量爆炸，权限变更要改 DDL |
| | | 查完内存过滤 | 聚合已算完，SUM 里已含无权限行 |
| | | 写进 prompt 让模型自己加 | 一次注入就绕过 |
| 何时算范围 | 登录时 | 每次查询 | 一次问数多条 SQL；放在 LoginUser 里便于审计。代价：改权限要 refresh |

## 5. 效果（全部实测）

| 指标 | 结果 |
|---|---|
| 276 条标准 SQL × 6 个用户 = 1,656 例，对比每用户一个视图隔离库 | 越权 380/380 拦截；可执行 1,276/1,276 一致（768 加了过滤，711 结果确实变了——不是空对空）；误拦 0 |
| 28 条手写越权攻击（CTE、UNION、LATERAL、`OR 1=1`、子表多跳、别名伪装……） | 0 泄露（`OR 1=1` 被 Druid WallFilter 先拦，多一层） |
| 接口层 11 项（无令牌、篡改、伪造头、绕网关、越权读报告……） | 11/11 |
| 真实模型 6 题换用户 | admin 12 店 / 华东运营 5 店 / 店群 7 店，各店数值与 admin 一致；越权 threadId 403 |
| 性能 | 网关 +0.98 ms p50 / +3.75 ms p99；改写约 1.7 ms/条 |

## 6. 追问

1. **为什么不直接用 RuoYi-Plus 的 WHERE 追加？** 见第 4 节，关键是 LEFT JOIN 语义和别名。
2. **派生表会不会让 SQL 变慢？** MySQL 8 和 PG 都会把简单派生表合并回原执行计划（derived merge）；实测开销毫秒级。
3. **改了权限多久生效？** refresh 或重新登录后；`/auth/refresh` 会重读角色。
4. **LoginUser 放在头里不怕被改吗？** 签名覆盖 LoginUser；数值维度强制整数，就算伪造也注入不了 SQL。
5. **data_scope=5 仅本人怎么办？** 业务表没有"属于谁"这一列，解析成无数据（`1=0`），而不是放开。
