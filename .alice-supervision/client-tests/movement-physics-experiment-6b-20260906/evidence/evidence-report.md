# Movement 实验 6B 证据报告

日期：2026-09-06

## 客户端操作

用户反馈测试完成。测试物品为 `alice:movement_timeout_diagnostic_tester`，普通右键启动；该入口不修改世界、不放置障碍。

## 服务端事实

本轮至少两次运行结果一致：

```text
[MovementExperiment6B] started from=4,64,40 to=5,64,40 dynamicObstacleEvidence=NONE
Movement 超时: 1/1 type=DiagnosticTimeoutMovement noProgress=101 obstacle=- cleanup=controller_stop_movement
[MovementExperiment6B] failed actualFoot=4,64,40 onGround=true y=64.000 reason=movement_timeout_DiagnosticTimeoutMovement failureClass=MOVEMENT_TIMEOUT cleanup=controller_stop_movement
task_execution_terminal kind=MovementTimeoutDiagnosticTask ... terminal=FAILED code=failed:movement_timeout_DiagnosticTimeoutMovement pos=4,64,40 ... failureCode=MOVEMENT_TIMEOUT failurePhase=EXECUTING_MOVEMENT failureDetails=dynamicObstacleEvidence=NONE,noProgressTicks=101
```

## 结论

6B 达到 `WINDOWS_CLIENT + SERVER_LOG + USER_ACCEPTED`：没有动态障碍证据时保持 `MOVEMENT_TIMEOUT`，并清理 Controller 输入。该入口是执行器诊断对照，不代表自然物理场景中的所有超时原因。
