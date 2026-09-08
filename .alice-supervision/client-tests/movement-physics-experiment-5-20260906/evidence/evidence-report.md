# Movement 实验 5 场景 D 证据报告

日期：2026-09-06

## 客户端观察

用户确认修复版封闭屏障测试完成，并提供最终结果截图作为 Windows 客户端终态佐证。截图文件未进入当前工作区，因此本报告不虚构其路径或文件名。

## 服务端事实

```text
[MovementSceneD] phase=STARTED from=0,64,20 goalFoot=10,64,20 obstacle=5,64,20
[MovementExperiment5] started from=0,64,20 to=10,64,20 obstacle=5,64,20
[MovementSceneD] phase=OBSTACLE_PLACED botFoot=1,64,20 botY=64.000 tick=848 wallBlocks=6 obstacle=5,64,20
controller_stop_movement bot=AliceMovementBot
Movement 超时: 1/1 type=WalkMovement noProgress=101 cleanup=controller_stop_movement
[MovementExperiment5] failed goalFoot=10,64,20 actualFoot=4,64,20 obstacle=5,64,20 onGround=true y=64.000 reason=movement_timeout_WalkMovement
task_execution_terminal kind=MovementDynamicBlockTask target=方块@10,64,20 startTick=841 endTick=942 durationTicks=101 terminal=FAILED code=failed:movement_timeout_WalkMovement pos=4,64,20 recovery=idle_after_cleanup
```

## 结论

实验 5 场景 D 达到 `WINDOWS_CLIENT + SERVER_LOG + USER_ACCEPTED`：

- 动态横向石墙在 Movement 执行期间放置；
- Bot 被真实碰撞阻挡于墙前，实际脚位为 `4,64,20`，`onGround=true`；
- 执行器在连续无进展 101 tick 后以事实型 `movement_timeout_WalkMovement` 失败；
- 超时分支调用 `controller_stop_movement`，并记录 `cleanup=controller_stop_movement`；
- 任务终态为 `FAILED`，没有自动重规划；
- 场景 D 保持独立，未接入 MineTask 或旧 `PathExecutor`。

当前不把 `movement_timeout_WalkMovement` 改称 `BLOCKED_DYNAMIC`：本次证据证明的是“动态阻挡导致无进展超时并完成输入清理”，尚未实现独立的动态阻挡分类码。
