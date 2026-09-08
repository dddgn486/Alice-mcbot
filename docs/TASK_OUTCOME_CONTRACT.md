# 事实型任务结果契约

更新时间：2026-09-06

## 目标

为任务终态提供一个跨任务可读取的事实快照，同时保留 MineTask 等领域任务的详细内部报告。

本契约不包含决策层策略，不返回“应该重试”“应该换目标”或“应该询问用户”等建议。

## 统一结果

`TaskOutcome` 表示一次任务的终态事实：

- `taskKind`：任务类型；
- `targetDescription`：目标描述；
- `terminalStatus`：终态；
- `resultCode`：稳定的机器可读结果码；
- `terminalBotPos`：终态时 Bot 的脚位；
- `failure`：失败时的事实报告，成功时为 `null`。

当前终态枚举沿用 `TaskExecutionRecord.TerminalStatus`：

- `COMPLETED`
- `FAILED`
- `SURVIVAL_INTERRUPTED`
- `CANCELLED_FOLLOW`
- `CANCELLED_REPLACED`
- `REJECTED_BEFORE_START`

## 失败报告

`TaskFailureReport` 只描述事实：

- `code`：任务失败码；
- `phase`：任务失败时所在阶段；
- `details`：领域事实补充，例如计划失效类型；
- `recoveryStage`：达到的最高恢复层级；
- `recoveryEvents`：按发生顺序排列的去重恢复事件。

它不包含以下字段：

- `retryable`
- `recommendedAction`
- `shouldChangeTarget`
- `userActionRequired`
- `priority`

这些字段需要多个任务和真实失败样本后，才有资格进入决策层契约讨论。

## 分层

```text
BotMiner.FailureReport
    -> MineTask 领域恢复和失败处理
    -> TaskFailureReport 事实快照
    -> TaskOutcome 统一终态
    -> 未来决策层读取事实并自行决定下一步
```

BotMiner 的内部报告不直接成为跨任务接口。它可以包含计划失效、运行时视线和路径状态等 MineTask 特有信息。

## 兼容方式

- `Task.failureReason()` 保留，用于兼容旧调用和日志；
- `Task.failureReport()` 提供默认事实报告，旧任务无需立即重构；
- `MineTask` 覆盖 `failureReport()`，输出阶段、失败码、计划失效类型和恢复事件；
- `TaskExecutionRecord` 保留原有字段，并新增 `outcome`；
- 成功任务的 `TaskOutcome.failure` 为 `null`；
- 失败任务的 `TaskOutcome.failure` 不应被解读为自动动作建议。

## 当前范围

本轮由 MineTask 作为第一个领域实现者接入。TransferTask、LumberTask 和其他任务暂时使用默认报告，后续应先积累真实失败样本，再讨论跨任务失败类别和决策层策略。

MineTask 的初始计划必须先满足 `MiningPlan.isExecutable()`：

- 初始路径 `UNREACHABLE` 直接报告 `no_safe_execution_path`；
- 初始路径 `SEARCH_LIMIT` 直接报告 `stand_search_limit`；
- 不可执行初始计划不交给 BotMiner，也不触发无意义的任务级重规划；
- 路径 `REACHED` 但运行时视线被近距离遮挡，仍由既有目标访问清障处理。

场景 D 不属于本轮范围。它只用于未来验证 MineTask 任务级重规划，不是统一结果契约的前置条件。
