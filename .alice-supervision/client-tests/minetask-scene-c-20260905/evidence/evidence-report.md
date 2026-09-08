# MineTask 场景 C 客户端验收证据

日期：2026-09-05

测试环境：PCL 标准 Forge 1.20.1 / Forge 47.4.10 / Alice / WorldEdit 7.2.15

入口：`/function alice_test:scene_c` 和 `alice:mining_scene_c_tester`

## 场景几何

- Bot 起点：`(0,64,0)`
- 目标：`(10,64,0)`
- 动态障碍：`(5,64,0)`
- 走廊边界：`x=-1..11,z=-2..2`

## 用户观察

用户提供的截图显示 Bot 已经到达动态障碍右侧并完成任务，右上角 Bot 状态为“空闲”。起点为青色高亮，动态障碍为橙色高亮，Bot 没有出现位置偏移或结束瞬移。

## 服务端关键事实

- 初始计划状态：`REACHED`
- 障碍放置时 Bot 脚位：`(1,64,0)`
- 障碍：`(5,64,0)`
- 夹具阶段：`OBSTACLE_PLACED`
- 执行器报告：目标脚位 `(5,64,0)` 因方块变化不可通行
- BotMiner 内部恢复：`重新规划(1/2)`
- 任务终态：`COMPLETED`
- 结果码：`done`
- 终点：`(10,64,0)`
- MineTask 任务级恢复次数：`0`

完整日志保存在 `latest.log`，关键行保存在 `scene-c-key-lines.log`，用户截图保存在 `scene-c-result.png`。

## 结论

场景 C 达到 `USER_ACCEPTED`。已验证长直路径中的动态障碍能够被真实放置，PathExecutor 能报告路径阻挡，BotMiner 能执行一次内部路径恢复并绕行到目标，最终完成挖掘任务。

## 边界

本证据只证明 BotMiner 内部恢复，不证明 MineTask 任务级重规划。`recoveryAttempts=0` 是本场景的正确结果。
