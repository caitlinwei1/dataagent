# Turbo / Mix 模式评测（第 10 步）

同一个后端、同一个智能体、同一道题，依次用不同模式各跑一遍（配对），一次只跑一题，所以每次运行的 LLM 用量可以从 `GET /openapi/v1/usage` 的前后差值精确算出。打分沿用第 7 步 `run_nl2sql.py` 的执行结果比对。设计见 [step10](../../docs/design/step10-turbo-mix-hybrid.md)。

## 题目和防泄漏

`build_mode_eval.py` 生成：

- **评测题** `questions.jsonl`：23 题，只取 dev。来自第 7 步 40 题样本**从未用过**的 28 个题族，每族一题（种子 11），只要 SQL 类任务：单表 9、多表 9、多轮 5。holdout 没有动。
- **案例库** `cases.jsonl`：17 条“问题 + 正确 SQL”，取自样本用过的 17 个题族。评测题的同族题不会出现在案例库里。
- **规则** `gbi-agent/src/main/resources/gbi/rules.yaml`：6 条，来自第 7 步样本的失败分析，不是照着评测题写的。
- **价格** `prices.json`：百炼模型页（中国内地，最低输入档），元 / 百万 token。qwen-plus 输入 0.8、输出 2；qwen3.7-flash 输入 0.2、输出 0.8。

## 结果（2026-11-15，`results/r1`、`results/ablation`）

| 模式 | 模型 | 校验 | 规则 | 案例 | EX | 严格 EX | 执行成功 | 首次即成功 | 耗时 p50 / p90 | LLM 调用 | token / 题 | 成本 元 / 题 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| default（上游） | qwen-plus | 全 | – | – | 13/23 = 56.5% | 10 | 22 | 22 | 35.1 / 40.6 s | 10.5 | 59.9k | 0.0512 |
| turbo，去掉规则 | qwen3.7-flash，不思考 | 精简 | – | – | 14/23 = 60.9% | 11 | 23 | 23 | 14.6 / 15.6 s | 7.3 | 38.2k | 0.0081 |
| **turbo** | qwen3.7-flash，不思考 | 精简 | ✓ | – | **17/23 = 73.9%** | 13 | 22 | 21 | **13.9 / 16.4 s** | 7.4 | 40.7k | **0.0087** |
| mix，去掉案例 | qwen-plus | 全 | ✓ | – | 17/23 = 73.9% | 13 | 23 | 23 | 35.2 / 37.8 s | 9.8 | 59.3k | 0.0502 |
| **mix** | qwen-plus | 全 | ✓ | ✓ | **21/23 = 91.3%** | 17 | 22 | 19 | 36.9 / 45.5 s | 10.7 | 72.3k | 0.0611 |

“精简”= 跳过可行性评估和语义一致性校验，SQL 重试上限 3（JSqlParser 守卫照常）。严格 EX 要求行顺序也一致。

**逐题配对**（谁对谁错）：

| 对比 | 胜 | 负 | 符号检验 p（双侧） |
|---|---|---|---|
| mix vs default | 8 | 0 | 0.008 |
| turbo vs default | 6 | 2 | 0.29 |
| turbo vs turbo 去规则（规则的作用，小模型） | 5 | 2 | 0.45 |
| mix 去案例 vs default（规则的作用，大模型） | 5 | 1 | 0.22 |
| mix vs mix 去案例（案例的作用） | 4 | 0 | 0.125 |
| turbo 去规则 vs default（换小模型 + 精简链路） | 2 | 1 | 1.0 |

**同一配置跑两次会差多少**：Turbo 直连跑一次（`r1`），混合部署下又跑一次（`hybrid_turbo`，取数链路已证明结果完全一致）。两次 EX 都是 17/23，但有 6 题一次对一次错，最终 SQL 文本相同的只有 4 题。也就是说，23 题上单次 ±3 题以内的差别可能只是模型的随机性。

## 结论

1. **Turbo：快 2.5 倍，便宜 83%，准确率不降。** 换成小模型并去掉两次 LLM 校验后，EX 和 default 持平（14 vs 13，差在噪声内），耗时 35 s → 14 s，成本 0.051 → 0.008 元 / 题。再加上规则，EX 到 17/23。
2. **Mix：准确率最高，成本只多 19%。** 8 胜 0 负，是这几组里唯一显著的差别。拆开看，规则和案例各贡献约 4 题，案例 4 胜 0 负。代价是 prompt 变长（token +21%），首次即成功从 22 降到 19：案例让模型写出更复杂的 SQL，需要重写。
3. **规则对大小模型都有用**（各 +3～4 题），而且几乎不增加 token。口径类问题（大区归属、类目层级、审计时间）最适合用规则解决，这和第 7 步的失败分析一致。
4. 和官方定位吻合：Turbo 响应快、用规则干预；Mix 精度更高、靠案例学习。

## 剩下的错误

- **M03-1**（多轮，“缺货数量最多的前 3 个 SKU”）：三种模式都错。default 和 Mix 用 `COUNT(*)` 数缺货记录，而不是对 `shortage_qty` 求和，Mix 还用了审计时间 `created_time`（审计时间规则靠“月 / 年”这类字触发，这一轮的问题里没有，上一轮才有）；Turbo 则误解了上一轮的上下文，去查了退款表。
- **S10-2 / J15-4**：分别是 Mix 和 Turbo 的 SQL 重试耗尽：Mix 被语义一致性校验连续驳回到 10 次上限；Turbo 连续 3 次选表类错误，到了它的上限 3。
- **S11-2**（Turbo）：问“iOS 端和安卓端分别是多少”，SQL 没有按端分组，只给了合计。

## 复现

```bash
python3 eval/mode/build_mode_eval.py
# 后端要带案例库启动：--gbi.mode.cases-file=eval/mode/cases.jsonl
python3 eval/scripts/run_modes.py --run r1                               # default / turbo / mix，可断点续跑
# 消融用的两个模式通过启动参数加：
#   --gbi.mode.profiles[turbo-norules].model=qwen3.7-flash ...rules=false
#   --gbi.mode.profiles[mix-nocases].model=qwen-plus ...rules=true ...cases=false
python3 eval/scripts/run_modes.py --run ablation --modes turbo-norules,mix-nocases
python3 eval/mode/compare.py r1
```
