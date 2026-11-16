# 混合部署评测（第 10 步）

“服务在云、数据在地”：云端的 gbi-agent 不连数据库，所有取数经本地数据网关。设计见 [step10](../../docs/design/step10-turbo-mix-hybrid.md)。

## 1. 结果一致性和网关开销

`GatewayBenchmark`：问数集 320 题里全部 276 条标准 SQL（256 条 `gold_sql` + 20 条多步题的 `key_sql`，含 holdout，只执行标准 SQL，不涉及模型），每条先直连执行，再经网关执行，比较列名和每一行。

```bash
(cd gbi-agent && mvn test -Dtest=GatewayBenchmark -Dgbi.bench=true)   # 写 results/bench.json
```

| 指标 | 结果 |
|---|---|
| 结果完全一致 | 276 / 276 |
| 直连耗时 p50 / p90 | 10.2 / 21.2 ms |
| 经网关耗时 p50 / p90 | 14.8 / 25.7 ms |
| 网关额外耗时 p50 / p90 / 平均 | 4.5 / 8.2 / 4.9 ms |
| 每条平均返回 | 313 字节，平均 6 行，最多 31 行 |

网关和后端在同一台机器上，所以这是签名、HTTP、JSON、打码本身的开销，不含公网往返。

## 2. 经网关初始化 schema

新建 `mysql-gateway` 数据源（只存网关地址和签名密钥）和智能体，86 张表全部经网关取元数据、采样并向量化：

```bash
GATEWAY_SECRET=... GATEWAY_DB_PASSWORD=... scripts/gateway.sh
GATEWAY_SECRET=... python3 scripts/setup_agent.py --type mysql-gateway --name "电商经营分析（混合部署）"
```

| 方法 | 次数 | 离开网关 | 打码格子 |
|---|---|---|---|
| fetchTables | 1 | 12.6 KB | 0 |
| showColumns | 86 | 156 KB | 0 |
| 每表 5 行样本 | 86 | 147 KB | 25 |
| showForeignKeys | 1 | 13 B | 0 |

样本 SQL 失败 1 条：`dim_date.year_month` 是 MySQL 关键字，上游拼样本 SQL 时没有加反引号，直连也一样失败。

## 3. 端到端问数

在混合部署的智能体上用 Turbo 模式跑第 10 步的 23 道问数题（`eval/mode/questions.jsonl`），所有 SQL 都经网关执行：

```bash
GBI_AGENT=<混合部署智能体 id> python3 eval/scripts/run_modes.py --run hybrid_turbo --modes turbo
```

| 指标 | 经网关（Turbo） |
|---|---|
| EX | 17/23 = 73.9% |
| SQL 执行成功 | 23/23 |
| 首次即执行成功 | 22/23 |
| 端到端耗时 p50 / p90 | 14.1 / 15.6 s |
| 每题 SQL 数 / 离开网关的字节 | 1.3 条 / 1.4 KB（共 29 条、31.8 KB） |
| 网关侧每条 SQL 耗时（含数据库） | 平均 20 ms |

第一道题时，召回的“枚举值”通道会建索引：经网关跑了 229 条 `SELECT DISTINCT`，共 58 KB，收件人姓名的 18 个值在网关打了码，所以向量库和枚举索引里没有真实姓名。

EX 和直连的对比见 [eval/mode/README.md](../mode/README.md)。

## 4. 安全测试

`GatewayUnitTest`、`GatewayRoundTripIT`（MySQL）、`GatewayPostgresIT`（PostgreSQL），7 个用例：写操作和多语句 403；错误密钥、篡改、超时、重放 401；错误码 42S22 / 42703 能在云端归到“选表”类；个人信息列打码；行数上限生效。
