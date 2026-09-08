# MineTask WorldEdit 测试场景计划

更新时间：2026-09-05

## 目标

使用 PCL 的标准 Forge 1.20.1 实例和 WorldEdit 7.2.15，生成可重复的 MineTask 测试场景。WorldEdit 只负责场景创建、保存、加载和撤销；Alice 负责目标选择、MineTask、路径执行、Bot 状态和日志。

不修改 Alice 的 UserDev 依赖，不把 WorldEdit 加入 `build.gradle`，不把 `SOFT_SURFACE` 接入 MineTask。

## 固定测试区域

所有场景使用独立世界中的区域 `(-16, 63, -16)` 到 `(16, 72, 16)`。建议先创建一个专用世界，例如 `alice-minetask-scenes`。

生成基础平地：

```text
//pos1 -16,63,-16
//pos2 16,63,16
//set stone
//pos1 -16,64,-16
//pos2 16,72,16
//set air
```

基础规则：

- `y=63` 是石头支撑面。
- Bot 脚位使用 `y=64`。
- 目标方块必须是非空气方块，并且目标周围保留可观察空间。
- 每次测试前先清理上一场景的临时方块，再重新加载对应蓝图。
- 不在游戏运行时直接修改 `.mca` 文件。

## 场景 A：正常曲面挖掘基线

### 目的

确认普通目标可以通过真实曲面路径到达合法挖掘站位，并进入正常挖掘流程。

### 场景几何

目标方块：`(4,64,4)`。

```text
//pos1 4,64,4
//pos2 4,64,4
//set deepslate
```

使用 `/function alice_test:scene_a` 生成场景并领取 `alice:mining_scene_tester`，手持后普通右键任意方块即可自动生成/复用 Bot、摆位并启动场景 A。

### 预期

- `MiningPlanner` 产生 `REACHED` 计划。
- `MiningPlan` 的 `visibility=true`、`executable=true`。
- `BotMiner` 使用计划路径到达站位。
- 目标方块被正常挖掉。
- 任务进入收集阶段；若拾取失败，只记录为 `DropCollectionTask` 问题，不归因于规划主链。

### 关键证据

```text
[MiningPlanner探针] planning ... REACHED
[MiningPlan探针] consumed ... executable=true
[BotMiner探针] PathExecutor ... REACHED
挖掘完成: target=...
```

## 场景 B：固定视线遮挡与有限目标访问清障

### 目的

验证 Bot 到达合法站位后，目标视线被少量方块遮挡时，MineTask 只执行有限的目标访问清障，不把它扩大为隧道或软移动。

### 场景几何

先恢复场景 A 的平地，然后建立一面低墙。目标放在墙的另一侧，遮挡最多保持两格厚度。

```text
//pos1 -4,64,1
//pos2 4,65,1
//set stone

//pos1 0,64,3
//pos2 0,64,3
//set deepslate
```

使用 `/function alice_test:scene_b` 生成场景并领取 `alice:mining_scene_b_tester`，手持后普通右键任意方块即可自动生成/复用 Bot、摆位到 `(-3,64,0)` 并启动场景 B；目标固定为 `(0,64,3)`。场景自动生成前后边界 `z=-1/4`、左右边界 `x=-4/4`，墙体为 `x=-3..3,y=64..65,z=1`，避免从墙端绕行或从上方跳过。

### 预期

- 规划和执行仍使用 `HARD_PATH`。
- 如果当前站位的目标视线被墙遮挡，MineTask 只尝试直接可见、范围内的有限 blocker。
- 清障深度不超过两格。
- 不出现隧道规划、长距离挖通道或 `SOFT_SURFACE` 回退。
- 若场景几何导致所有合法站位不可用，应报告明确失败码，而不是把 `SEARCH_LIMIT` 转换成不可达。

### 关键证据

```text
[MineTask计划失败报告] ... planInvalidation=RUNTIME_VISIBILITY_FAILED
局部清障(1/2): ...
清障完成: ... -> 继续挖原目标
挖掘完成: target=...
```

此场景用于验证现有有限清障，不用于证明任务级重规划已经通过。

## 场景 C：动态路径障碍

### 目的

验证运行中的路径发生变化时，PathExecutor 报告动态阻挡，BotMiner 先执行自己的有限内部路径恢复。

### 操作方式

1. 执行 `/function alice_test:scene_c`，生成长直走廊、起点、目标和 C 启动器。
2. 手持 `alice:mining_scene_c_tester`，普通右键任意方块启动测试。
3. 启动器自动标记起点 `(0,64,0)`、目标 `(10,64,0)` 和中央障碍 `(5,64,0)`，再生成或复用 Bot。
4. 等待 Bot 开始沿直线路径移动；夹具会在 Bot 前进后自动放置真实石头。
5. 观察 Bot 是否因动态阻挡绕行或恢复，最后观察目标和任务终态。
6. Shift+右键任意方块清理夹具石头和高亮。

### 预期

- 初始计划必须是 `REACHED`。
- 夹具只在 Bot 已移动且距离障碍约 2 至 4 格时放置石头。
- `PathExecutor` 报告动态阻挡。
- BotMiner 最多进行两次内部路径恢复。
- 内部恢复成功时，`MineTask recoveryAttempts=0` 是正确结果，不代表任务级重规划失败。

### 关键证据

```text
[MiningReplanFixture] phase=OBSTACLE_PLACED
路径受阻,重新规划(1/2)
[MineTask终态计划证据] ... recoveryAttempts=0
```

## 场景 D：任务级一次重规划候选场景

### 目的

专门验证 `MineTask` 在 BotMiner 内部路径恢复和现有目标访问清障都无法继续时，最多调用一次 `MiningPlanner`，并切换到另一个合法站位。

### 重要限制

当前可视动态障碍夹具已经验证了执行层恢复，但尚未验证任务级 `recoveryAttempts=1`。不能只在场景 C 上重复增加障碍来声称场景 D 通过。

场景 D 必须满足以下几何条件：

- 初始计划选中的站位是唯一的最短直通站位。
- 动态障碍封死该站位的到达路线。
- 备用站位位于目标另一侧，脚位、头位和支撑均合法。
- 备用站位仍在目标 4.5 格挖掘距离内，并且有清晰视线。
- 动态障碍不直接成为当前站位到目标的 blocker，避免 MineTask 进入目标访问清障。
- BotMiner 的两次路径恢复不能绕过障碍到达原站位。
- 重新规划后必须产生不同的 `standingFoot`。

### 验收日志

只有同时出现以下证据，才标记任务级重规划通过：

```text
[MineTask计划失败报告] ... planInvalidation=PLAN_PATH_STALE
[MineTask重规划探针] ... recoveryAttempt=1/1 ... currentPlanReplaced=true
[MiningPlan探针] consumed ... newStanding=...
[MineTask终态计划证据] ... recoveryAttempts=1
```

如果日志只有 `路径受阻,重新规划(1/2)` 或 `(2/2)`，那只是 BotMiner 内部恢复，不是场景 D 的通过证据。

## 蓝图保存与恢复

每个场景完成后，在 WorldEdit 中用木斧选择 `(-16,63,-16)` 到 `(16,72,16)`，再执行：

```text
//copy
//schem save alice-minetask-scene-a
```

其他场景分别保存为：

```text
alice-minetask-scene-b
alice-minetask-scene-c
alice-minetask-scene-d-candidate
```

恢复时：

```text
//schem load alice-minetask-scene-a
//paste -a
```

加载后先检查目标方块、支撑面和 Bot 起点，再开始 Alice 测试。不要在有运行中任务或 Bot 的情况下直接粘贴覆盖其脚下区域。

## 验收等级

- 场景文件和命令：`IMPLEMENTED`
- Alice 构建：`COMPILES/BUILD`
- 服务端日志：`SERVER_LOG`
- PCL 标准 Forge 客户端实际观察：`WINDOWS_CLIENT`
- 用户确认结果：`USER_ACCEPTED`

当前只有场景 C 的执行层动态恢复已经有 `WINDOWS_CLIENT` 和 `USER_ACCEPTED` 证据。场景 D 的任务级重规划仍待单独验证。
