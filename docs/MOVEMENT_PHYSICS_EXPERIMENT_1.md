# Movement 物理实验 1：BotController 单段输入驱动

准备日期：2026-09-06
状态：平地前进/停止已获用户验收；台阶、落差和动作序列待后续实验

## 目的

验证 Alice Forge Bot 的最小真实物理推进链，不把寻路、MovementPathExecutor 或 MineTask 的问题混入实验。

```text
BotController 输入
  -> BotPlayer.tick()
  -> controller.onUpdate()
  -> super.tick()
  -> aiStep()
  -> travel/move/碰撞/重力
  -> 服务端实体状态
```

## 当前框架假设

- `aiStep()` 保留在 `BotPlayer.tick()`，作为实体生命周期中的统一物理推进入口；
- `BotController` 只保存和应用输入，不直接承担路径规划；
- Forge `ServerPlayer.tick()` 不按 Fabric/普通 Mob 假设自动完成 Bot 所需物理推进；
- 任务侧 `BasicMovement.travel()` 与实体侧 `aiStep()` 的交互另设对照组，不在输入驱动主组中混入。

这些是实验基线，不是已经完成的物理结论。

## 实验边界

本实验不使用：

- MineTask；
- BotMiner；
- PathExecutor；
- MovementPathExecutor；
- PathPlanner；
- SurfacePathfinder；
- BreakAndWalkMovement；
- DescendMovement；
- PillarMovement；
- 隧道、搭路、破坏或放置方块。

只测试 BotController 和 BotPlayer 的实体物理行为。

## 对照组

### A：输入驱动主组

```text
controller.setForward(1.0F)
BotPlayer.tick()
BotPlayer.aiStep()（由 BotPlayer.tick 内部调用）
```

### B：静止/停止组

```text
controller.stopMovement()
BotPlayer.tick()
```

观察速度残留、滑动、重力和最终停稳。

### C：任务侧 travel 对照组

仅用于确认当前旧执行链的实际行为：

```text
BasicMovement.applyToward(...)
BotPlayer.tick()
```

记录显式 `travel()` 与 `aiStep()` 的前后状态，不把该组结果当作输入驱动结论。

## 客户端最小场景

优先使用现有 Bot 控制入口或新增独立实验物品，不创建 MineTask 场景。

准备四个简单场景：

1. 平地：Bot 前方 5 格无遮挡；
2. 停止：Bot 前进后清除输入；
3. 墙体：Bot 前方 2 格放置一格高墙；
4. 台阶/落差：分别观察上一级台阶和自然落地。

第一轮只要求平地和停止；墙体、台阶/落差作为后续观察，不在物理链尚未确认时扩展修复。

## 每 tick 记录

服务端日志至少记录：

```text
tick
inputForward
inputStrafing
inputJumping
beforePos
beforeDeltaMovement
beforeOnGround
afterSuperTickPos
afterSuperTickDeltaMovement
afterSuperTickOnGround
afterAiStepPos
afterAiStepDeltaMovement
afterAiStepOnGround
footPos
supportBlock
```

如能低侵入加入计数，再记录：

```text
controllerUpdateCount
travelCount
aiStepCount
moveCount
```

计数必须是观测工具，不能改变调用顺序。

## 通过条件

### 输入驱动

- Bot 能从平地起点持续向目标方向移动；
- 位移方向和速度稳定；
- 没有明显双倍位移、穿透或位置回弹；
- 客户端实体显示与服务端位置一致。

### 停止

- 清除输入后不再持续获得新的前进输入；
- 速度按原版物理自然衰减或在合理时间内停稳；
- 不发生无来源滑动或重新加速。

### 墙体

- Bot 不穿过墙；
- 碰撞后能保持在墙前或按实际物理停住；
- 服务端和客户端位置一致。

### 台阶/落差

- 不把接近目标误判为已站稳；
- 上台阶、落地后的 Y、支撑和 `onGround` 状态可解释；
- 失败时能区分输入无效、碰撞阻挡和物理未结算。

## 结果分类

实验后只允许使用以下结论等级：

- `CODE_REVIEW`：调用链已确认；
- `SERVER_LOG`：服务端状态和调用顺序已记录；
- `WINDOWS_CLIENT`：客户端观察符合服务端事实；
- `USER_ACCEPTED`：用户确认实验结果。

不能因为 BotController 类存在就称“真实行走完成”。

## 实验后决策分支

### 若 A/B/C 均通过

进入实验 2：单个纯 `WalkMovement` 改为提交 Controller 输入，由 BotPlayer 统一推进物理。

### 若输入驱动不能移动

先分析 `super.tick()`、`aiStep()`、输入字段和 ServerPlayer 物理前置条件，不直接恢复任务侧 `travel()`。

### 若能移动但发生重复/异常推进

使用实际日志做 5 Whys 根因分析，先确定重复调用发生在哪个边界，再决定是否调整 BasicMovement 或 BotPlayer 调用顺序。

### 若服务端正确、客户端异常

转入实体同步/客户端验收调查，不把客户端显示问题归因给寻路或 Movement。

## 实验 2：单个 WalkMovement

实验 2 已加入独立入口 `alice:movement_walk_tester`，链路为：

```text
MovementWalkTask
  -> WalkMovement
  -> MovementPathExecutor
  -> BotController 输入
  -> BotPlayer.tick()/aiStep()
```

它不调用旧 `PathExecutor`，不接 MineTask。实验物品右键一个平地方块后，目标脚位为该方块上方；终态会清除 BotController 输入。

当前工件已构建并同步，SHA-256：`8ba593072aab94929e58ebcd9261c876402ba65cf56e1bc674de9ab2a118d182`。

用户已连续测试三次，均确认 Movement 行走流畅且无异常；服务端三次均为 `COMPLETED/done`，实际脚位与目标一致，终态输入清理，达到 `USER_ACCEPTED`。证据见 `.alice-supervision/client-tests/movement-physics-experiment-2-20260906/evidence/`。

## 明日第一步

1. 确认当前工作树和实际 PCL 工件；
2. 检查现有 Bot 控制测试入口是否足以执行 A/B；
3. 若不足，只添加最小实验入口和日志，不改正式任务；
4. 构建、同步；
5. 由用户在客户端观察平地前进和停止；
6. 再决定是否加入墙体和台阶对照。
