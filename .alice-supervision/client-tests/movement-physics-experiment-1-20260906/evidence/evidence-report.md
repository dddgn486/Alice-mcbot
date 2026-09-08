# Movement 物理实验 1 证据报告

日期：2026-09-06

## 客户端观察

用户确认 Bot 平地行动流畅，没有观察到异常回弹、穿透或明显双倍位移；停止控制后状态稳定。

## 服务端探针事实

- `after_super` 阶段位置保持不变；
- `after_aiStep` 阶段出现实际位置变化；
- 活动输入阶段记录到 `forward=1.00` / 组合输入；
- 每个观测 tick 的 `travelCalls=1`；
- 停止输入后位置保持稳定；
- `onGround=true`。

## 结论

实验 1 的平地输入驱动与停止闭环达到 `USER_ACCEPTED`：

```text
BotController 输入 -> BotPlayer.tick -> aiStep -> travel/物理 -> 服务端位置
```

本结论只覆盖平地前进和停止，不覆盖台阶、跳跃、落差、动态阻挡、Movement 原语序列或 MineTask 接入。

证据文件：

- `latest.log`
- `physics-probe.log`
- `key-lines.log`
