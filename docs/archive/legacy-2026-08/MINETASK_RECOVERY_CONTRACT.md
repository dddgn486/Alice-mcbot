# MineTask 恢复契约

更新时间：2026-09-05

## 目的

统一记录一次任务经历过的恢复层级和按发生顺序排列的恢复事件，同时保持各层的实际责任和预算不变。

`RecoveryStage` 是观测契约，不是新的恢复策略，也不授权额外重试、隧道或软移动。

## 层级

```text
NONE
TARGET_ACCESS_CLEAR
BOTMINER_PATH_RETRY
MINETASK_REPLAN
ESCALATED_FAILURE
```

任务终态同时保留本次任务达到过的最高层级 `recoveryStage`，以及按发生顺序排列的去重事件列表 `recoveryEvents`。层级高低表示恢复责任层，不表示成功程度；事件列表才表达先后顺序：

- `NONE`：未发生恢复；
- `TARGET_ACCESS_CLEAR`：MineTask 为访问原目标而执行有限局部清障；
- `BOTMINER_PATH_RETRY`：BotMiner 在执行路径被动态阻挡后，从当前位置重新规划路径；
- `MINETASK_REPLAN`：BotMiner 恢复不足并报告合格失效，MineTask 重新生成一次 `MiningPlan`；
- `ESCALATED_FAILURE`：当前层恢复无法继续，任务向终态失败升级。

## 责任边界

### BotMiner

负责单次挖掘动作和路径执行期间的内部恢复：

- 路径动态阻挡时最多内部重试两次；
- 报告 `FailureReport` 和 `PlanInvalidation`；
- 不决定是否启动任务级重规划。

当内部重试发生时，BotMiner 标记 `BOTMINER_PATH_RETRY`。

### MineTask

负责编排目标访问和任务级恢复：

- 近距离直接遮挡优先走有限目标访问清障；
- 任务级重规划最多一次；
- `SEARCH_LIMIT` 保持预算未知语义，不授权隧道；
- 无法继续时升级为明确失败。

局部清障标记 `TARGET_ACCESS_CLEAR`；成功任务级重规划标记 `MINETASK_REPLAN`；最终失败标记 `ESCALATED_FAILURE`。

### BotManager

只负责读取任务终态并保存观测结果：

- 非 MineTask 使用 `RecoveryStage.NONE`；
- MineTask 使用其终态恢复层级；
- 不在 BotManager 中决定重规划或改变任务预算。

## 结果语义

恢复层级必须和路径结果分开：

- `REACHED`：在搜索规则和预算内证明可达；
- `UNREACHABLE`：在规则和预算内证明不可达；
- `SEARCH_LIMIT`：预算耗尽，未知；
- `BLOCKED`：执行时环境变化导致当前路径段受阻。

恢复层级不能把 `SEARCH_LIMIT` 变成 `UNREACHABLE`，也不能单独授权挖隧道或接入 `SOFT_SURFACE`。

## 日志契约

任务终态日志包含：

```text
task_execution_terminal ... recovery=... recoveryStage=... recoveryEvents=...
[MineTask终态计划证据] ... recoveryAttempts=... recoveryStage=... recoveryEvents=...
```

判断证据时：

- `recoveryStage=NONE`：没有记录到恢复；
- `recoveryStage=TARGET_ACCESS_CLEAR`：发生目标访问清障；
- `recoveryStage=BOTMINER_PATH_RETRY`：发生 BotMiner 内部路径重试；
- `recoveryStage=MINETASK_REPLAN`：发生任务级重规划；
- `recoveryStage=ESCALATED_FAILURE`：任务最终升级失败。

`recoveryEvents` 按发生顺序记录去重事件。例如场景 B 可能是 `[BOTMINER_PATH_RETRY, TARGET_ACCESS_CLEAR]`；场景 C 是 `[BOTMINER_PATH_RETRY]`；没有恢复时为空列表。

`recoveryAttempts` 仍然只表示 MineTask 任务级重规划次数，不表示 BotMiner 内部重试次数。

## 当前证据范围

- 场景 A：应保持 `recoveryStage=NONE`；
- 场景 B：应为 `TARGET_ACCESS_CLEAR`；
- 场景 C：应为 `BOTMINER_PATH_RETRY`；
- 场景 D：尚未创建，`MINETASK_REPLAN` 尚未获得客户端验收。
