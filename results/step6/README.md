# 第 6 步结果：Python 沙箱容器池

运行方式：`cd gbi-agent && mvn -Dtest=SandboxLimitsIT test`。需要本机 Docker 和镜像 `gbi-python-sandbox:0.1`。
用例代码在 `gbi-agent/src/test/java/.../service/code/SandboxLimitsIT.java`，每个用例的输出写在 `sandbox/*.json`。

配置（`_limits.json`）：1 核 CPU，500 MiB 内存（禁用 swap），代码超时 60 s，`network_mode=none`，最多 64 个进程，最多重写 5 次。

| 用例 | 修复后结果 | 证据 |
|---|---|---|
| 正常 pandas 分析 | 成功，0.5 s | `01_normal.json` |
| cgroup 限制 | cfs 100000/100000 = 1 核；memory 和 memory+swap 都是 524288000；pids 64 | `02_cgroup.json` |
| 2 个进程同时空转 | 实际用了 1.00 核 | `03_cpu_burn.json` |
| 申请 1 GiB 内存 | 分到 448 MiB 时被 OOM 杀掉，stderr 带 `OOMKilled` | `04_memory.json` |
| sleep 600 s | 60.1 s 被杀掉，stderr 带 `timeout 60s` | `05_timeout.json` |
| 访问外网、1.1.1.1、宿主机 MySQL | 3 个目标全部连不上（DNS 失败，或网络不可达） | `06_network.json` |
| fork 炸弹 | 第 61 个子进程起被拒绝（EAGAIN） | `07_fork_bomb.json` |
| chown、raw socket、mount | 全部被拒 | `08_privileges.json` |
| 同一容器连续跑 12 个任务 | 后一个任务读不到前一个任务的输出 | `09_leak_*.json` |

## 修复前（上游 b297203）的实测

| 问题 | 修复前 | 修复后 | 证据 |
|---|---|---|---|
| CPU 限制 | `CpuCount=1` 在 Linux 上不生效：`NanoCpus=0`、`cfs_quota=-1`，2 个进程用了 1.99 核 | 1.00 核 | `upstream_cpucount_probe.txt` |
| 内存 | 没设 MemorySwap，Docker 默认给 2 倍，实际上限 1000 MiB | 500 MiB | 同上（memsw=1048576000） |
| 进程数 | `pids.max=max`，没有上限 | 64 | 同上 |
| 跨任务输出泄露 | 8 个用例里 7 个读到了之前任务的输出 | 0 个（12 次读取全部干净） | `before_logfix/*.json` |
| 失败原因 | 只返回 `Killed`，分不清是超时还是内存不足 | 标注 `OOMKilled` 或 `timeout` | `04`、`05` 两个用例 |
| 降级范围 | `PYTHON_FALLBACK_MODE` 一旦置位就不再复位，同线程后面的步骤和下一个问题都会直接走降级 | 降级只作用于当前步骤；新问题会重置 | `PythonFallbackTest` |
| 降级提示 | 固定一句“Python 高级分析功能暂时不可用，出现错误” | 写明第几步、重写了几次、最后的错误，并说明报告不含这一步的结论 | `PythonFallbackTest` |

单元测试：`DockerLogFilterTest` 2 个、`PythonFallbackTest` 3 个；`SandboxLimitsIT` 9 个，需要 Docker 才能跑。
