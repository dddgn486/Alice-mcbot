# MineTask 不可达目标安全失败验收证据

日期：2026-09-06

## 用户场景

用户布置了 Bot 无法寻路到达、也无法安全挖掘的目标场景，并完成客户端测试。

## 服务端事实

初始规划为 `pathStatus=UNREACHABLE`、`executable=false`。MineTask 在 `EVALUATING` 阶段直接执行 `FAIL_SAFE`。

终态为：

- `terminal=FAILED`
- `code=failed:no_safe_execution_path`
- `recoveryAttempts=0`
- `recoveryStage=ESCALATED_FAILURE`
- `recoveryEvents=[ESCALATED_FAILURE]`
- `failureCode=no_safe_execution_path`
- `failurePhase=EVALUATING`
- `failureDetails=`

没有创建 BotMiner 执行器，没有任务级重规划，没有授权隧道。

## 回归事实

同一轮客户端测试随后回归场景 B，仍然发生目标访问清障并以 `COMPLETED/done` 结束：

`recoveryEvents=[BOTMINER_PATH_RETRY, TARGET_ACCESS_CLEAR]`

## 结论

不可达目标安全失败和场景 B 目标访问清障回归均通过，达到 `USER_ACCEPTED`。

完整日志见 `latest.log`，关键行见 `key-lines.log`。
