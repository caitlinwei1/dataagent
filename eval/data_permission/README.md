# 数据权限评测

设计说明见 [docs/design/security-ruoyi-gateway-data-permission.md](../../docs/design/security-ruoyi-gateway-data-permission.md)。所有请求都经过网关，前提是已经用 `SECURITY=on scripts/up.sh` 拉起服务，并导出了同样的 `GBI_GATEWAY_SIGN_SECRET`。

| 脚本 | 测什么 | 是否调用 LLM | 结果 |
|---|---|---|---|
| `run_permission_eval.py --agent 5` | 276 条标准 SQL × 6 个用户（1,656 例）+ `attacks.yaml` 28 条攻击 + 11 项接口检查 | 否，约 6 分钟 | `results/full/summary.json`、`cases.jsonl` |
| `bench_gateway.py` | 同一个接口经网关和直连 agent 的延迟差 | 否 | `results/gateway_latency.json` |
| `run_e2e_llm.py --agent 5` | 同一个问题换 4 个用户问；跨用户接管 threadId | 是（qwen-plus） | `results/e2e_llm.json`、`results/e2e_llm_denied_early_stop.json` |

## 正确答案怎么来

- 每个用户建一个 `gbi_scope_<user>` 库，里面每张表都是一个视图：受控表是带该用户过滤条件的视图，其他表直接映射到 `gbi_ecommerce`。
- 标准 SQL 在这个库里跑出的结果就是正确答案。这条路径用的是 MySQL 视图，没有经过 JSqlParser 改写，所以能独立地检验改写。
- 被测的路径是：`POST /api/data-permission/preview`（execute=true）→ 守卫 → 表级检查 → 行级改写 → 只读账号执行。和 `SqlExecuteNode` 走的是同一套逻辑，只是不调用 LLM。
- 加了行过滤的用例，还会再跑一次全量库。如果全量结果和隔离副本相同，说明过滤对这道题没有影响，`row_filter_changed_result` 不计入这一条。

判定规则：
- 涉及未授权表的用例必须被拦。
- 其余用例必须执行成功，并且结果和隔离副本一致。
- 攻击用例被拦也算通过，因为没有泄露。

## 结果（2026-11-15）

| 集合 | 应当拦截 | 已拦截 | 可执行 | 和隔离副本一致 | 过滤改变了结果 | 误拦 | 泄露或错误 |
|---|---|---|---|---|---|---|---|
| 标准 SQL × 6 用户 | 380 | 380 | 1,276 | 1,276 | 711 | 0 | 0 |
| 攻击 | 12 | 12（另有 1 条行级攻击被 Druid WallFilter 拦下） | 16 | 15 | 15 | 0 | 0 |
| 接口检查 | | | | | | | 11/11 通过 |

网关延迟（单客户端，2,000 次）：P50 +0.98 ms，P99 +3.75 ms。
