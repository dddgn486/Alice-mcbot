# Movement 物理实验 2 证据报告

日期：2026-09-06

## 客户端观察

用户连续测试单个 WalkMovement，确认 Bot 行动没有异常感觉，移动流畅并正常停止。

## 服务端事实

同一轮测试连续完成三次 MovementWalkTask：

1. `from=-10,-60,38` -> `to=-7,-60,46`，`durationTicks=42`，`COMPLETED`；
2. `from=-7,-60,46` -> `to=0,-60,42`，`durationTicks=40`，`COMPLETED`；
3. `from=0,-60,42` -> `to=3,-60,51`，`durationTicks=46`，`COMPLETED`。

每次都满足：

- 使用 `WalkMovement`；
- `Movement 完成: 1/1`；
- `actualFoot` 与目标脚位一致；
- 终态 `COMPLETED/done`；
- `recoveryStage=NONE`；
- `recoveryEvents=[]`；
- Movement 完成时调用 `controller_stop_movement`，任务终态收尾时再次保证输入清理；
- 没有 `Movement 失败` 或 `Movement 超时`。

## 结论

实验 2 的单个纯 WalkMovement 闭环达到 `USER_ACCEPTED`：

```text
WalkMovement
  -> MovementPathExecutor
  -> BotController 输入
  -> BotPlayer.tick()/aiStep()
  -> 真实物理移动
  -> 实际脚位到达
  -> 输入清理
  -> 任务完成
```

本结论不覆盖台阶、跳跃、落差、动态阻挡、多 Movement 序列、世界修改型 Movement 或 MineTask 接入。
