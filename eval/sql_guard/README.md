# SQL 守卫测试集（Step 4）

`cases.jsonl` 共 75 条，由 `build_cases.py` 生成：

| 期望结果 | 条数 | 内容 |
|---|---|---|
| pass | 30 | 正常的分析 SQL：多表关联、CTE、窗口函数、子查询、UNION、MySQL 与 PostgreSQL 各自的日期函数、反引号和带库名的写法。全部用只读账号在真实 MySQL / PostgreSQL 上执行通过。 |
| READ_ONLY | 28 | DML、DDL、多语句、注释里藏的第二条语句、`SELECT INTO OUTFILE`、`FOR UPDATE`、`LOCK IN SHARE MODE`、`SLEEP` / `BENCHMARK` / `pg_sleep`、读文件函数、PostgreSQL 的写操作 CTE、`CALL`、`SET`、`GRANT`、`EXPLAIN ANALYZE DELETE` 等 |
| WHITELIST | 8 | 系统库（`information_schema`、`mysql.user`、`pg_shadow`）、跨库读元数据库（`saa_data_agent.model_config` 里存着模型 API Key）、不存在的表 |
| COLUMN | 5 | 不存在的字段（例如 `paid_amount`、`shop_name`、`gmv`） |
| SYNTAX | 4 | 拼写错误、多余逗号、括号不配对、空 SQL |

运行方式：`cd gbi-agent && mvn -Dtest=SqlGuardCasesTest test`。它会用 86 张表的白名单和字段目录逐条检查，并把结果写到 `results.json`。

**结果**：75/75 与标注一致，平均每条 3 毫秒。

**诚实说明**：用例和守卫是同一个人写的。第一次运行时有 7 条不一致：
- 6 条攻击语句 JSqlParser 解析不了，被判成了 SYNTAX。它们仍然被拦住了，只是归类不对。
- 1 条正常 SQL 被误判：`TIMESTAMPDIFF(HOUR, …)` 里的 `HOUR` 被当成字段。

修复后全部一致：解析失败时如果含写关键字，就判为 READ_ONLY；时间单位不当作字段。所以 75/75 不代表在未见过的 SQL 上的表现。真正的检验是 Step 7：用 LLM 生成的 SQL 统计拦截率和误拦率。

`SqlErrorClassifierTest` 在真实 MySQL 和 PostgreSQL 上各触发 7 种错误，检查"选表 / 方言 / 执行"三类归类是否正确，14/14 通过。
