# 05 Python 沙箱：Docker 容器池，1 CPU / 500 MiB / 60 s / 无网络，5 次失败显式降级

## 一句话讲法

> "计算交给模型写 Python，放在 Docker 容器池里跑。上游的限额配置'看起来有、实际没生效'：CPU 能用 2 核、内存带 swap 是 1000 MiB、进程数不限，池化容器还会读到上一个任务的输出。我把这些都修了，并用 9 个真实 Docker 用例证明限额生效；重写 5 次仍失败就显式降级，报告照样出、写清楚缺了什么。"

## 1. 原来有什么问题

LLM 写的代码不可信：会写死循环、读整张大表、fork 失控；被注入时会读 `/etc`、连内网、外发数据；一个人的任务能拖垮整台机器。上游有 Docker 池，但：

| 配置 | 上游实际效果 |
|---|---|
| `withCpuCount(1)` | Linux daemon 忽略它（只对 Windows 生效），实测 2 进程空转用了 **1.99 核** |
| `Memory=500MB`，没设 `MemorySwap` | Docker 默认 swap 上限 = 2× 内存，实际上限 **1000 MiB** |
| 没设 `PidsLimit` | 进程数不限，fork 炸弹可以打满 |
| 池化复用容器，`docker logs` 读全部历史 | **一个任务读到别的任务的输出** |
| 退出码 137 | 只知道 Killed，不知道是 OOM 还是超时 |
| 5 次失败后 | 降级输出是 `{}`；`PYTHON_FALLBACK_MODE` 不归零，同会话下一题直接走降级 |

## 2. Bad case 与根因（`results/step6/`）

- **跨任务输出泄漏 7/8**：池里容器复用，第二个任务 `docker logs` 拿到的是容器整个生命周期的日志，包含第一个任务的输出。这既是数据泄漏（跨用户），也会让分析结论错。
- **CPU 限额无效**：读 cgroup 文件 + 2 进程空转实测 1.99 核。根因是用错了 API（`CpuCount` vs `NanoCPUs`）。
- **降级后下一题也降级**：标志位没归零。
- **降级信息没用**：报告里只有一句固定的话，不知道哪步、为什么、缺了什么。

## 3. 怎么解决的（代码）

- `dataagent/service/code/impls/DockerCodePoolExecutorService.java`
  - `createHostConfig()`：`NanoCPUs=1e9`、`Memory=MemorySwap=500MiB`、`PidsLimit=64`、`NetworkMode=none`、`CapDrop=ALL`。
  - `buildExecutionCommand()`：容器内 `timeout -s SIGKILL 60s python3 -u script.py < input_data.txt`；外层 `wait` 再兜底。
  - `execTaskInContainer()`：写文件 → start → wait → `inspect` 拿 `State.StartedAt` 和退出码 → 读日志。
  - `fetchExecutionLogs()` + `currentRunOnly(payload, startedAt)`：日志带时间戳，丢掉早于本次 StartedAt 的行（用 daemon 的时钟，远程 Docker 也对）。
  - 137 时看 `State.OOMKilled`，在 stderr 补 `[sandbox] OOMKilled` 或 `[sandbox] killed: timeout 60s`，让重写 prompt 知道该省内存还是改算法。
- 池：`AbstractCodePoolExecutorService`，2 个常驻 + 2 个临时，任务排队。
- 降级：`PythonExecuteNode` 输出 `{"degraded":true,"tries":5,"last_error":...}`；`PythonExecutorDispatcher` 看到 `PYTHON_FALLBACK_MODE` 直接去 `PythonAnalyzeNode`；分析节点写"【降级】第 N 步的 Python 分析未完成（已重写 5 次，最后错误：内存超过沙箱上限）。本步只保留 SQL 结果……"，并把标志和计数归零；`GraphServiceImpl` 新问题也归零。

## 4. 为什么这么做

| 决策 | 选择 | 备选 | 为什么没选 |
|---|---|---|---|
| 隔离 | Docker + cgroup + network none + cap drop | gVisor / Kata / Firecracker | 隔离更强（独立内核），但要 KVM 或额外 runtime，启动多 0.1~1 s；威胁模型是"模型写错/被注入"，不是专门构造内核逃逸。接口已抽象成 `CodePoolExecutorService`，可以换实现 |
| | | 本地子进程 + `setrlimit` | 拦不住网络和文件系统 |
| | | 云函数 | 冷启动慢，数据要出域 |
| | | 让 LLM 直接算 | 数值会错，不可复现不可审计 |
| 复用 | 池化 | 每次 `docker run --rm` | 冷启动 0.5~1 s + pandas 导入；池化后 0.1~0.5 s。代价：必须清理上个任务的痕迹 |
| CPU | `NanoCPUs`（CFS quota） | `CpusetCpus` 绑核 | 多容器挤一个核，依赖机器核数 |
| 日志隔离 | 按 StartedAt 过滤 | 每任务重建容器 / 写文件读回 / 打印 nonce | 失去池化意义 / 远程 Docker 改动大 / 用户代码能伪造 nonce |
| 降级 | 只降当前步，报告继续，写明原因 | 整个流程失败 | 用户等了一分钟什么都没有；SQL 结果仍可信 |
| | | 静默跳过 | 报告看起来完整实际缺结论，最危险 |
| 重写上限 | 5（上游默认） | 1 / 无限 | 很多错第二次就能改好；无限则成本不可控 |

## 5. 难点

- **"配置了"≠"生效了"**：要用结果验证（cgroup 文件、空转测核数、分配 1 GiB 被 OOM、fork 炸弹被拒），而不是看配置。
- **池化的状态残留**：日志是最隐蔽的一个；`/tmp` 残留仍是已知风险（可以每 N 个任务回收容器）。
- **区分 OOM 和超时**：退出码都是 137，要看 `OOMKilled`。

## 6. 效果（`SandboxLimitsIT` 9/9 真实 Docker）

| 指标 | 上游 | 修复后 | 性质 |
|---|---|---|---|
| 2 进程空转实际核数 | 1.99 | 1.00 | 实测 |
| 内存实际上限 | 1000 MiB | 500 MiB | 实测 |
| 进程数上限 | 不限 | 64（第 61 个子进程起被拒） | 实测 |
| 跨任务输出泄漏 | 7/8 | 0/12 | 实测 |
| 超时被杀 | 60.1 s | 60.1 s（本来就生效） | 实测 |
| 外网 / 宿主 DB / DNS | 不通 | 不通（本来就生效） | 实测 |
| 降级后同线程下一题 | 直接降级 | 正常执行 | `PythonFallbackTest` 3/3 |
| 真实问数里降级触发率、第几次重写成功的分布 | — | **没统计** | 补法：跑完整 320 题（64 道多步题）后从 trace 统计 `PYTHON_TRIES_COUNT` |
| cgroup v2 | — | **没测**（宿主是 v1） | 说明即可 |

## 7. 追问

1. **为什么不用 gVisor？** 威胁模型、部署成本、启动延迟；接口已抽象，可以换。
2. **怎么证明限额生效？** 看结果不看配置：容器里读 cgroup、空转测核数、超额分配被 OOM、fork 炸弹被拒。这些测试在修复前就抓到了 `CpuCount` 不生效。
3. **池化有什么风险？** 状态残留，我们真踩了（日志泄漏 7/8）。`/tmp` 残留是剩下的风险，可以每 N 次回收容器。
4. **降级为什么不直接报错？** 一份报告 4~6 步，一步 Python 失败不该让前面的 SQL 结果作废，但必须显式告诉用户缺了什么。
5. **数据怎么进容器？** SQL 结果写成 `input_data.txt` 挂进去，从 stdin 读；容器没网络，拿不到别的数据。
