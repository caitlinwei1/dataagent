# Step 1 结果：历史版 DataAgent (b297203) 跑通

运行时间：2026-11-13（UTC），threadId `step1-hitl-002`，智能体 2（销售数据分析），数据源 MySQL `product_db`。

| 环节 | 结果 | 证据 |
| --- | --- | --- |
| 模型 | qwen3.8-flash（对话）+ qwen3.7-text-embedding-flash（向量），DashScope 兼容接口 | `scripts/step1/register_models.sh` |
| Schema 初始化 | 6 张表向量化成功 | `POST /api/agent/2/datasources/init` |
| 计划展示 + HITL 暂停 | PlannerNode 输出计划后在 HumanFeedbackNode 前中断，SSE 以 complete 结束 | `01_plan.sse` |
| 按 threadId 恢复 | 同一 threadId 带 `humanFeedbackContent=同意该计划` 继续执行 | `02_approve_execute.sse` |
| SQL 生成与执行 | 4 表 JOIN 按分类聚合；与手工 SQL 核对一致（电子产品 9196.00 / 3 单，合计 12645.00） | `sql.txt` |
| Python 执行 | Docker 容器池，一次成功（镜像 `gbi-python-sandbox:0.1`） | `python_output.txt` |
| Markdown 报告 | 含执行摘要、表格与 2 个 ECharts 图表 | `report.md` |

耗时：计划阶段 58 秒（22:36:21→22:37:19），批准后执行到报告 5 分 42 秒（→22:43:01）。

## 跑通过程中发现的问题（留给后续步骤）

1. **对话模型不走代理**：reactor-netty 忽略 JVM 代理参数，报 `Failed to resolve 'dashscope.aliyuncs.com'`。用 b297203 自带的 model_config 代理字段（proxy_enabled/host/port）解决；Embedding 调用不受影响。
2. **本地 Python 执行器与 pandas 3.0 不兼容**：生成代码使用 `to_numeric(errors="ignore")`，在 pandas 3.0 下报错，第一次运行（threadId `step1-hitl-001`）因此失败重试。改用 Docker 执行器 + 自建镜像（`sandbox/Dockerfile`，pandas 2.2）后通过。默认镜像 continuumio/anaconda3 超过 3GB，不再使用。
3. **容器输出中文乱码**：Docker 执行器读取 stdout 时把 UTF-8 按 Latin-1 解码（`python_output.txt` 中分类名乱码），报告节点仍从 SQL 结果拿到正确中文。第 6 步修。
4. **向量库在内存**（`vectorstore.type: simple`）：后端重启后需重新 init。
5. 示例数据源写的是 docker 内主机名（mysql-data），后端跑在宿主机时需改为 127.0.0.1:13306 / 15432。

## 复现

```bash
sudo dockerd &                                   # 如未运行
./scripts/step1/up.sh                            # 元数据库 + 示例数据源 + 后端
docker build -t gbi-python-sandbox:0.1 sandbox/  # 代理环境见 Dockerfile 注释
# 以 docker 执行器重启后端：
java -jar upstream/dataagent-b297203/data-agent-management/target/*.jar \
  --spring.ai.alibaba.data-agent.code-executor.code-pool-executor=docker \
  --spring.ai.alibaba.data-agent.code-executor.image-name=gbi-python-sandbox:0.1
DASHSCOPE_API_KEY=... ./scripts/step1/register_models.sh   # 然后 activate 两个 id
# 代理环境：UPDATE model_config SET proxy_enabled=1, proxy_host=..., proxy_port=...
# 数据源主机改为 127.0.0.1，然后 toggle / tables / init 智能体 2
T=step1-hitl-002 ./scripts/step1/hitl_run.sh
```
