# Movement 实验 6A 场景 E 证据报告

日期：2026-09-06

## 客户端证据

用户完成场景 E 测试并提供最终结果截图，确认 Bot 在封闭动态屏障前停止，未继续前进。

## 服务端证据

```text
[MovementSceneE] phase=STARTED from=0, 64, 40 goalFoot=10, 64, 40 obstacle=5, 64, 40
[MovementExperiment5] started from=0, 64, 40 to=10, 64, 40 obstacle=5, 64, 40
[MovementSceneE] phase=OBSTACLE_PLACED botFoot=1, 64, 40 botY=64.000 tick=171 wallBlocks=6 obstacle=5, 64, 40
controller_stop_movement bot=AliceMovementBot
Movement 动态阻挡: 1/1 type=WalkMovement noProgress=101 obstacle=5, 65, 40 cleanup=controller_stop_movement
[MovementExperiment5] failed goalFoot=10, 64, 40 actualFoot=4, 64, 40 obstacle=5, 64, 40 onGround=true y=64.000 reason=movement_blocked_dynamic_WalkMovement
task_execution_terminal kind=MovementDynamicBlockTask target=方块@10, 64, 40 startTick=164 endTick=265 durationTicks=101 terminal=FAILED code=failed:movement_blocked_dynamic_WalkMovement pos=4, 64, 40 recovery=idle_after_cleanup failureCode=BLOCKED_DYNAMIC failurePhase=EXECUTING_MOVEMENT failureDetails=obstacle=5, 65, 40,noProgressTicks=101
```

## 结论

实验 6A 达到 `WINDOWS_CLIENT + SERVER_LOG + USER_ACCEPTED`：

- 场景 E 独立入口工作正常；
- 方块屏障在 Movement 执行期间出现；
- Bot 被真实碰撞阻挡，实际脚位为 `4,64,40`，目标为 `10,64,40`，`onGround=true`；
- 执行器准确报告 `BLOCKED_DYNAMIC`，阶段为 `EXECUTING_MOVEMENT`；
- 记录了检测到的碰撞方块 `(5,65,40)` 与 `noProgressTicks=101`；
- 超时/阻挡终态清理了 Controller 输入；
- 没有自动重规划，未接入 MineTask 或旧 `PathExecutor`。

`MOVEMENT_TIMEOUT` 与 `INVALID_PRECONDITION` 契约已在代码结构中保留，但本次只验证了 `BLOCKED_DYNAMIC` 分支，尚未完成 6B/6C 对照测试。
