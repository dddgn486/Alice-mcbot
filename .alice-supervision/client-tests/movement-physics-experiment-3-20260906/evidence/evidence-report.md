# Movement 物理实验 3 证据报告

日期：2026-09-06

## 客户端观察

用户确认平地连续 Movement 序列通过，行动无异常。用户另试图让 Bot 跳上方块，该次被实验前置条件拒绝；这是当前实验只覆盖同高度纯行走的预期边界，不作为平地序列失败。

## 服务端事实

本轮日志至少记录五次三段序列成功执行。每次均按以下顺序完成：

```text
Movement 完成: 1/3
Movement 完成: 2/3
Movement 完成: 3/3
```

成功终态均满足：

- `MovementExperiment3 completed segments=3`；
- `actualFoot` 与 `goalFoot` 一致；
- `terminal=COMPLETED code=done`；
- `recoveryStage=NONE`；
- `recoveryEvents=[]`；
- 每段完成和最终终态均调用 `controller_stop_movement`；
- 未出现 `Movement 失败` 或 `Movement 超时`。

跳跃/高差尝试记录为：

```text
terminal=FAILED code=failed:movement_sequence_invalid
```

该失败发生在规划前置检查，不会伪造为平地 WalkMovement 成功。

## 结论

实验 3 达到 `USER_ACCEPTED`，验证了：

```text
多个 WalkMovement
  -> MovementPathExecutor 依次切换
  -> BotController 输入
  -> BotPlayer.tick()/aiStep()
  -> 实际脚位逐段到达
  -> 最终输入清理
  -> 任务完成
```

本结论不覆盖台阶跳跃、下降、动态阻挡、世界修改型 Movement 或 MineTask 接入。
