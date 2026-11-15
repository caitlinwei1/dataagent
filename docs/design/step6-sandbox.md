# 第 6 步：Python 沙箱容器池

## 1. 解决什么问题

问数链路里，SQL 只负责取数；同比、环比、占比、异常检测、预测这些计算交给大模型写 Python 来完成。
大模型写出的代码是**不可信的**，常见的问题有三类：

- 写错：比如死循环、一次性读进整张大表、`fork` 失控。
- 被提示词注入诱导：比如去读 `/etc`、连接内网数据库、把数据外发。
- 资源打满：一个用户的分析任务把整台机器拖垮，其他人的问数请求一起变慢。

所以这一步的目标有四个：

- **隔离**：代码只能拿到本次的输入数据，访问不了宿主机、网络和其他任务的数据。
- **限额**：1 核 CPU、500 MiB 内存、运行 60 s、最多 64 个进程，超出就杀掉。
- **快**：用容器池复用容器，不用每次冷启动。
- **失败可控**：最多重写 5 次；仍然失败就明确降级，报告继续生成，并在报告里写明哪一步降级、为什么降级。

## 2. 怎么做的

```
PythonGenerateNode（tries+1） → PythonExecuteNode → PythonExecutorDispatcher
        ↑ 失败且 tries<5 ───────────────────────────┘     │
                                                          ├ 成功 → PythonAnalyzeNode（tries 归零）
                                                          └ 失败且 tries≥5 → 降级 → PythonAnalyzeNode
                                                              （输出【降级】说明，FALLBACK 和 tries 归零）
```

**容器池**：`AbstractCodePoolExecutorService` 维护 2 个常驻核心容器和 2 个临时容器，任务通过队列分配。
每次执行的流程是：

1. 把 `script.py` 和 `input_data.txt` 写入挂载目录。
2. `start` 容器。
3. `wait` 等它结束。
4. 读日志。
5. 用 `inspect` 拿退出码。

容器内执行的命令是 `timeout -s SIGKILL 60s python3 -u script.py < input_data.txt`。

**HostConfig**（`DockerCodePoolExecutorService.createHostConfig`）：

| 配置 | 值 | 作用 |
|---|---|---|
| `NanoCPUs` | 1e9 | 1 核，由 CFS quota 实际限制 |
| `Memory` / `MemorySwap` | 500 MiB / 500 MiB | 两者相等即禁用 swap |
| `PidsLimit` | 64 | 防 fork 炸弹 |
| `NetworkMode` | `none` | 只有 loopback 网卡 |
| `CapDrop` | `ALL` | 不能 chown、mount，不能开 raw socket |
| 超时 | 容器内 `timeout -s SIGKILL 60s`，外层 `wait` 再兜底 3000 s | 两层超时 |

**本步改动**

1. CPU：`withCpuCount` 改成 `withNanoCPUs`，并加上 `MemorySwap` 和 `PidsLimit`。
2. 日志只取本次运行：
   - 用 `inspect` 拿 `State.StartedAt`（这是 Docker daemon 的时钟，远程 Docker 也适用）。
   - 读日志时加 `withTimestamps(true)` 和 `withSince`。
   - 按行丢掉时间戳早于 StartedAt 的内容，即 `currentRunOnly()`。
3. 退出码为 137 时，用 `State.OOMKilled` 判断原因，在 stderr 里补一句 `[sandbox] OOMKilled ...` 或 `[sandbox] killed: timeout 60s`，让重写 Prompt 知道该减少内存占用还是优化算法。
4. 降级：
   - `PythonExecuteNode` 的降级输出从 `{}` 改为 `{"degraded":true,"tries":5,"last_error":...}`。
   - `PythonAnalyzeNode` 据此生成“【降级】第 N 步的 Python 分析未完成（已重写 5 次，最后错误：内存超过沙箱上限）。本步只保留上一步 SQL 的查询结果，报告不包含此步的计算结论。”，写进该步结果，报告节点直接引用。
   - 同时把 `PYTHON_FALLBACK_MODE` 和 `PYTHON_TRIES_COUNT` 归零。
   - 新问题开始时，`GraphServiceImpl` 也把这两个状态归零。

## 3. 为什么这么做（备选方案）

| 决策 | 选择 | 备选 | 为什么不选备选 |
|---|---|---|---|
| 隔离技术 | Docker + cgroup + `network=none` + cap drop | gVisor（runsc）、Kata/Firecracker 微虚拟机 | 隔离更强（独立内核），但需要宿主机支持 KVM 或额外 runtime，部署复杂，启动多 100 ms 到 1 s。我们的威胁模型是“大模型写错或被注入”，不是“攻击者专门构造内核逃逸”，Docker 加最小权限已经够用。上游的 `CodePoolExecutorEnum` 预留了 KATA，以后可以切换 |
| | | 本地 `python` 子进程加 `resource.setrlimit` | 拦不住网络和文件系统访问，也没有进程数隔离。上游的 LOCAL 模式只用于开发 |
| | | 云函数（FC/Lambda） | 冷启动慢；要把数据传到外部，数据合规有风险 |
| | | 让大模型直接算，不用 Python | 大模型做数值计算会出错，没法复现，也没法审计。代码可以审计，可以重跑 |
| 容器复用 | 池化，常驻 2 个加临时 2 个 | 每个任务 `docker run --rm` | 冷启动约 0.5 到 1 s，还要加上 pandas 的导入时间；池化后正常任务 0.1 到 0.5 s。代价是必须清掉上一个任务留下的痕迹，也就是本步修的日志泄露 |
| CPU 限制 | `NanoCPUs`（CFS quota） | `CpuCount` | Linux daemon 会忽略 `CpuCount`，这个参数只对 Windows 生效，实测 2 个进程用了 1.99 核 |
| | | `CpusetCpus` 绑定核心 | 多个容器会挤在同一个核上，而且依赖机器的核数 |
| 内存 | `Memory` = `MemorySwap` | 只设 `Memory` | Docker 默认的 swap 上限是 2 倍内存，实测上限变成 1000 MiB，而且换页会变慢，超时更难判断 |
| 超时 | 容器内 `timeout -s SIGKILL` | 只靠 Java 端 `waitContainer(timeout)` | Java 端超时后容器还在跑，需要再发 `kill`；容器内超时由进程自己结束，退出码明确 |
| 日志隔离 | 用 StartedAt 过滤日志 | 每个任务重建容器 | 失去了池化的意义 |
| | | 输出写文件再读回 | 远程 Docker 模式下要 `copyArchiveFromContainer`，改动更大 |
| | | 在开头打印一个 nonce 标记 | 用户代码可能伪造这个标记 |
| 降级 | 只降级当前步骤，报告继续生成，并写明原因 | 失败就结束整个流程（上游 dispatcher 在异常路径上会 `END`） | 用户等了一分钟什么都拿不到。SQL 结果仍然可信，应该交付 |
| | | 静默跳过这一步 | 报告看起来完整，实际缺了结论，这是最危险的情况 |
| 重写上限 | 5 次 | 无限次 | 成本和延迟不可控 |
| | | 1 次 | 大模型很多错误第二次就能改好，比如列名或类型，次数太少会浪费 |

## 4. 量化结果（修复前 → 修复后）

| 指标 | 修复前（上游 b297203） | 修复后 |
|---|---|---|
| 2 个进程空转实际用的核数 | 1.99 | 1.00 |
| 内存实际上限 | 1000 MiB（含 swap） | 500 MiB |
| 进程数上限 | 无（`max`） | 64，fork 炸弹从第 61 个子进程起被拒绝 |
| 一个任务读到其他任务输出的比例 | 7/8 个用例 | 0/12 |
| 超时任务被杀掉的时间 | 60.1 s | 60.1 s（本来就生效） |
| 外网、宿主机 DB、DNS | 不通 | 不通（本来就生效） |
| 失败原因是否可区分 | 否（只有 `Killed`） | 能区分 OOMKilled 和 timeout |
| 降级后同线程下一题的 Python | 直接走降级，即使代码是对的 | 正常执行 |
| 降级提示 | 一句固定的话 | 写明哪一步、重写几次、什么原因、报告缺什么 |

测试：

- `SandboxLimitsIT`：9/9，在真实 Docker 上运行，日志在 `results/step6/sandbox/`。
- `DockerLogFilterTest`：2/2。
- `PythonFallbackTest`：3/3。用一个总是 OOM 的桩执行器，验证正好执行 5 次后转到分析节点，降级文案包含“第 N 步”“已重写 5 次”“内存超过沙箱上限”。

**没测的部分**：

- 第 7 步的问数评测里，Python 降级的真实触发率要统计，那里会给出“多少题触发了降级”。
- 宿主机是 cgroup v1；cgroup v2 下的配置项是同一套 API，没有实测。

## 5. 面试问答

- **为什么不用 gVisor？** 见第 3 节的隔离技术一行：威胁模型、部署成本、启动延迟。接口已经抽象成 `CodePoolExecutorService`，以后要上 Kata 只需要换一个实现。
- **怎么证明限额真的生效？** 不看配置，看结果：在容器里读 cgroup 文件，加上 2 个进程空转测出的实际核数、分配 1 GiB 内存被 OOM 杀掉、fork 炸弹被拒绝。这些测试在修复前就能抓到 `CpuCount` 不生效的问题。
- **池化有什么风险？** 状态残留。我们实际踩到了：`docker logs` 返回的是容器整个生命周期的日志，第二个任务会拿到第一个任务的输出，等于跨用户泄露数据。现在按 StartedAt 过滤。`/app` 下的输入文件每次覆盖写；`/tmp` 残留仍然是已知风险，如果要做到严格隔离，可以每 N 个任务回收一次容器。
- **降级为什么不直接报错？** 一份报告通常有 4 到 6 步，一步 Python 失败不应该让前面的 SQL 结果作废。但必须**显式**告诉用户，否则就是带着缺陷交付的报告。
- **5 次重写怎么定的？** 是上游的默认值。第 7 步会统计“第几次重写成功”的分布再调整，目前没有依据去改它。
