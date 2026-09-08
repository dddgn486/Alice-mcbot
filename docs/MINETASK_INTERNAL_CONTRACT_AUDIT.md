# MineTask 第一阶段内部契约审查

更新时间：2026-09-06

## 范围

本审查只记录当前源码事实，不实施 PathExecutor、MineTask 或寻路体系的大范围重构。

保留边界：

- MineTask/DropCollectionTask 使用 `HARD_PATH`；
- `SEARCH_LIMIT` 不等于 `UNREACHABLE`；
- 目标访问清障与路径清障分离；
- `SOFT_SURFACE`、隧道、搭路和带世界修改的 Movement 不隐式接入普通挖矿；
- 服务端是状态真相；
- A/B/C 已验收行为不作为本轮重构对象。

相关 skill：`alice-path-planning-execution-contract`、`alice-task-lifecycle-and-recovery`。

## 一、MineTask 当前真实状态机

源码阶段枚举：

```text
EVALUATING
MOVING_TO_POSITION
MINING
COLLECTING
```

实际 tick 时序：

```text
创建 MineTask
  -> phase=EVALUATING
  -> MiningPlanner.plan(bot, target)
  -> 保存 MiningPlan
  -> 初始路径状态检查
      -> UNREACHABLE: 直接 FAILED/no_safe_execution_path
      -> SEARCH_LIMIT: 直接 FAILED/stand_search_limit
      -> REACHED: 继续
  -> phase=MINING
  -> startMining(target)
  -> BotMiner.tick()
      -> BotMiner 内部 PathExecutor 移动
      -> 视线/距离/挖掘协议检查
      -> 目标方块破坏
  -> 若是遮挡目标：清障目标完成后重新 startMining(original target)
  -> 原目标完成
  -> phase=COLLECTING
  -> 创建 DropCollectionTask
  -> collector.tick()
  -> DONE 或 FAILED
```

### `MOVING_TO_POSITION` 的真实含义

当前实现中，`MOVING_TO_POSITION` 不是独立移动阶段。它的 tick 逻辑是：

```java
// TODO: 实现移动逻辑（暂时直接开始挖掘）
phase = Phase.MINING;
startMining(target);
```

因此：

- MineTask 表面上有“移动到站位”阶段；
- 实际移动由 BotMiner 根据 MiningPlan 内的路径负责；
- MineTask 没有独立的 PathExecutor 成员；
- 没有“移动阶段成功后再把计划交给挖掘执行器”的独立确认点；
- `MOVING_TO_POSITION` 当前更接近过渡占位状态，而不是已实现的生命周期阶段。

这不是本轮直接修复项，但必须在后续职责讨论中正面处理。

## 二、MiningPlan 生命周期

### 产生

`MiningPlanner.plan(bot, target)` 负责：

1. 获取 Bot 当前脚位作为 `startFoot`；
2. 判断当前位置是否已经足够好；
3. 否则选择一个站位候选；
4. 通过 `SurfacePathfinder.find(bot, startFoot, standingFoot)` 规划 HARD_PATH；
5. 通过 `LineOfSightChecker` 计算目标可见性；
6. 组合 `MiningPlan(target, startFoot, standingFoot, path, visibility)`。

### 消费

MineTask 将计划保存到 `currentPlan`，随后：

```java
new BotMiner(bot, currentPlan)
```

BotMiner 保存：

- `plannedPath`；
- `plannedStartFoot`；
- `preferredStandingPoint`；
- target；
- 执行模式 `PLAN`。

BotMiner 后续会用计划路径创建 `PathExecutor`，并在运行时再次检查起点、路径段和站位条件。

### 替换

任务级重规划 `tryReplan()`：

- 只在 BotMiner 报告符合条件的计划失效时进入；
- 最多一次；
- 重新调用 `MiningPlanner.plan(bot, target)`；
- 替换 `currentPlan`；
- 重置清障深度和当前挖掘目标；
- 记录 `MINETASK_REPLAN`；
- 重新创建 BotMiner。

当前已验证“初始 UNREACHABLE 直接失败”，因此初始不可执行计划不会再走这条重规划链。

## 三、MineTask 与 BotMiner 的责任边界

### MineTask 负责

- 目标方块生命周期；
- 领域规划调用；
- 计划前置筛选；
- 目标访问局部清障；
- BotMiner 失败分类；
- 一次任务级重规划；
- 进入掉落物收集；
- 任务终态和结构化失败事实。

### BotMiner 负责

- 单次挖掘动作；
- 计划消费；
- 计划起点/站位/视线/距离运行时检查；
- PathExecutor 路径执行；
- 动态路径阻挡检测；
- 最多两次内部路径恢复；
- 原版挖掘协议和进度；
- BotMiner 级 FailureReport。

### 当前边界中的关键事实

目标访问清障不是通用路径清障：

- 目标访问清障：MineTask 通过 raycast 找到近距离遮挡并有限挖除；
- 路径清障：当前正式路径执行遇到方块变化时由 PathExecutor 报告阻挡，BotMiner 负责内部恢复；
- 带破坏/放置的 Movement 当前不能因为存在类实现就接入 MineTask。

## 四、寻路体系盘点

### A. 当前 MineTask 正式链路

```text
MiningPlanner
  -> SurfacePathfinder.find(ServerPlayer, startFoot, standingFoot)
  -> AStarPathfinder.computeDetailed(ServerPlayer, ...)
  -> movement.MovementHelper.getPossibleMovements()
  -> MovementProvider/MovementRegistry 枚举邻居
  -> AStarPathfinder.SearchResult
  -> SurfacePathfinder.Result
  -> MiningPlan
  -> BotMiner
  -> PathExecutor(List<BlockPos>)
```

### B. `AStarPathfinder`

当前底层搜索器同时有两种输出：

1. `SearchResult`：`SearchStatus + List<BlockPos>`；
2. `MovementSearchResult`：`SearchStatus + List<Movement>`。

当前 `computeDetailed()` 的搜索：

- 名称为 AStar，但启发式为零，实际是 Dijkstra；
- 时间预算默认 100ms；
- 节点上限 50,000；
- 移动步数上限 1,024；
- 正式 ServerPlayer 链路使用新的 Movement Provider 枚举邻居；
- `computeDetailedLegacy(ServerLevel, ...)` 仍保留旧 `expandLegacy/tryMove` 逻辑。

因此同一底层类中存在：

```text
新 Movement 邻居模型
旧 tryMove/MovementType 模型
```

### C. `SurfacePathfinder`

正式门面，结果类型：

```java
Result(
  SearchStatus status,
  BlockPos goal,
  List<BlockPos> path,
  int expandedNodes,
  double totalCost
)
```

正式 ServerPlayer 方法调用新的 `computeDetailed()`；旧 ServerLevel 重载调用 deprecated 的 `computeDetailedLegacy()`。

它表达的是：

```text
脚位 -> 脚位的现有曲面路径
```

不表达挖掘、隧道、搭路或自动破坏路径。

### D. `PathExecutor`

当前正式执行器，输入：

```java
List<BlockPos>
```

输出：

```text
MOVING / DONE / FAILED
```

特点：

- 使用 `BasicMovement` 和原版物理；
- 按脚位段执行；
- 检查目标脚位和头位是否可通行；
- 检查实体实际位置、支撑和落地；
- 检测动态方块阻挡并通过 `wasObstructed()` 暴露；
- 卡住超过阈值时失败。

它不执行 `Movement` 对象，只执行坐标序列。

### E. `PathPlanner` 平行抽象

接口：

```java
PathResult plan(ServerPlayer bot, BlockPos start, Goal goal)
```

结果：

```text
SUCCESS / UNREACHABLE / TIMEOUT / PARTIAL
```

实现：

- `AStarPathPlanner`：包装 `AStarPathfinder.computeDetailed()`；
- `LocalPathPlanner`：包装 `SurfacePathfinder.find()`；
- `HybridPathPlanner`：按曼哈顿距离 50 格切换 Local/AStar；
- `PathPlanners`：静态便捷访问器。

当前发现这些实现没有进入 MineTask 正式调用链。它们的 `PlannerConfig` 也没有真正传递到底层搜索预算：接口默认配置方法直接调用无配置方法。

### F. `MovementPathExecutor`

输入：

```java
List<Movement>
```

执行：

```java
Movement.tick(bot)
```

输出：

```text
MOVING / DONE / FAILED
```

它是另一套执行模型，与 `PathExecutor(List<BlockPos>)` 并行存在。当前没有发现 MineTask 正式使用它。

## 五、主要接口语义冲突

### 1. 路径结果状态名称不一致

底层/Surface：

```text
REACHED / UNREACHABLE / SEARCH_LIMIT
```

PathPlanner：

```text
SUCCESS / UNREACHABLE / TIMEOUT / PARTIAL
```

这里至少存在：

- `REACHED` 与 `SUCCESS` 重复表达；
- `SEARCH_LIMIT` 与 `TIMEOUT` 可能混淆“预算耗尽”和“运行超时”；
- `PARTIAL` 在当前正式 HARD_PATH 链中没有明确消费契约；
- 执行时动态阻挡 `BLOCKED` 没有进入 PathPlanner.PathStatus。

### 2. 规划输出模型不一致

```text
List<BlockPos>
vs
List<Movement>
```

当前搜索阶段已经通过 Movement 计算邻居，但正式执行阶段又转换/退回到 BlockPos 路径，形成：

```text
Movement 用于候选扩展
BlockPos 用于执行
```

这会丢失 Movement 类型、专用前置条件、专用后置条件和资源/世界修改语义。

### 3. 旧 ServerLevel 路径入口仍存在

`SurfacePathfinder.find(ServerLevel, ...)` 和 `computeDetailedLegacy(...)` 仍被诊断、回归和若干任务/工具调用。它们与新的 ServerPlayer 路径判定并非同一套邻居/边界语义。

### 4. 配置接口与实际预算脱节

`PathPlanner.PlannerConfig` 声称支持 timeout、maxNodes、allowPartial，但默认实现和现有包装器没有把全部配置传递到 AStarPathfinder。

### 5. `PathExecutor` 的阻挡判定是坐标通行检查

它对每个段目标统一检查：

```java
canWalkThrough(segmentGoal)
canWalkThrough(segmentGoal.above())
```

这对普通硬路径有效，但不能直接覆盖未来需要先破坏方块的 Movement。这与 D-014 一致：不能用单一通行检查覆盖所有 Movement。

## 六、目前已证实与尚未证实

### 已证实

- A/B/C MineTask 行为和恢复事件通过客户端验收；
- 初始 `UNREACHABLE` 目标直接安全失败；
- `TaskOutcome`/`TaskFailureReport` 能记录 MineTask 失败事实；
- 当前 MineTask 正式路径是 `SurfacePathfinder -> AStarPathfinder -> PathExecutor`；
- `PathPlanner` 和 `MovementPathExecutor` 未进入 MineTask 正式链路；
- `AStarPathfinder` 当前实际为零启发式 Dijkstra。

### 尚未证实

- `MovementPathExecutor` 是否能覆盖当前 PathExecutor 的物理、支撑、落地和阻挡要求；
- `Movement` 原语序列是否具备稳定序列化/重规划/失败契约；
- `PathPlanner` 配置字段的最终语义；
- 统一结果类型应以脚位路径、Movement 原语还是双层结果为主；
- MineTask 是否应拥有独立移动阶段，还是明确由 BotMiner 统一消费完整 MiningPlan；
- `MINETASK_REPLAN` 正向成功样本。

## 七、后续严肃讨论问题

建议按以下顺序讨论，不直接改代码：

1. 正式寻路的唯一语义结果是否应统一为 `REACHED/UNREACHABLE/SEARCH_LIMIT/BLOCKED`；
2. `PathPlanner` 是否保留，还是让 `SurfacePathfinder` 成为领域明确的通用脚位规划门面；
3. 正式执行单位应是 `List<BlockPos>`、`List<Movement>`，还是“可审计 Movement + 兼容脚位投影”；
4. `MovementProvider` 只负责候选生成，还是必须与 MovementPathExecutor 成对出现；
5. 旧 `ServerLevel` 路径入口何时、以何种证据退役；
6. MineTask 的移动阶段是否需要真正独立；
7. 任务级重规划接收的是路径失败、计划失效还是更高层的领域报告。

## 八、移动执行事实核对（重要修正）

### Forge Bot 的实际物理调用链

不能套用 Fabric 或普通 Mob 的默认假设。当前 `BotPlayer` 明确执行：

```text
BotPlayer.tick()
  -> controller.onUpdate()
  -> super.tick()
  -> aiStep()
```

当前项目的工作假设是：Forge `ServerPlayer.tick()` 不会自动为该 Bot 完成所需的 AI 物理推进，因此 `BotPlayer.aiStep()` 是必要的服务端物理推进入口。

但这不等于任务里可以随意再次调用 `travel()`：

- `BasicMovement.applyToward/applyJumpToward/settle` 直接调用 `bot.travel(...)`；
- `BotPlayer.tick()` 后续还调用 `aiStep()`；
- 因此“实体 tick 不自动 travel”和“任务执行器手动 travel 可能重复推进”必须分别讨论；
- `BotController` 的注释仍错误地声称 `ServerPlayer.tick()` 自动调用 `travel()`，不能作为事实依据。

当前待验证事实是：在一个完整服务器 tick 内，`BasicMovement` 的显式 `travel()` 与随后 `aiStep()` 的实际调用关系、速度/位置变化和副作用是否符合预期。

### 当前实际存在的移动执行入口

#### 1. `PathExecutor`：当前正式硬路径执行器

- 输入：`List<BlockPos>`；
- 使用：`BasicMovement.applyToward()` 和 `BasicMovement.settle()`；
- 不是纯 `setPos` 瞬移；
- 依赖显式 `travel()`，并结合实体位置、支撑和碰撞检查；
- MineTask、DropCollectionTask、WalkToTask、TransferTask、PlaceTask 当前使用它；
- 这是“正式使用中的坐标路径执行器”，但其命名 `HARD_PATH` 与实际物理实现不一致。

#### 2. `MovementPathExecutor`：新 Movement 原语执行器

- 输入：`List<Movement>`；
- 调用每个 Movement 的 `tick(bot)`；
- 已实现调度、超时和失败状态；
- 当前没有进入 MineTask 正式链路；
- 其能否可靠处理支撑、落地、跳跃、动态阻挡和动作后置条件尚未通过独立客户端闭环。

#### 3. `BasicMovement`：共享物理执行工具

- 通过 `travel(Vec3)` 执行朝向移动、跳跃和 settle；
- 是当前 PathExecutor 和 Movement 原语的共同底层实现；
- 不是独立路径规划器；
- “是否单 tick 重复推进”是当前首要物理审计问题。

#### 4. `BotController`：输入状态控制器

- 提供 forward、strafing、jumping、sneaking、sprinting；
- `BotPlayer.tick()` 的 `controller.onUpdate()` 将控制状态写入玩家输入字段；
- 适合表达真实玩家输入，但目前没有被 `MovementPathExecutor` 的 `WalkMovement` 使用；
- 远程控制入口使用它，说明输入控制基础设施存在，但不代表自动寻路已经接入。

#### 5. 直接位置/速度写入的遗留或旁路实现

源码中仍有：

- `setPos()`：测试、自检和 `RoadBuildTask` 旁路逻辑；
- `setDeltaMovement()`：部分伐木移动逻辑；
- 世界直接 `setBlock()` / `destroyBlock()`：`PillarMovement`、`DescendMovement`、`BreakAndWalkMovement` 等实验原语。

这些都不能统称为“SOFT_PATH”，也不能自动视为新 Movement 正式能力。

### SOFT_PATH / SOFT_SURFACE 的真实状态

当前代码里正式枚举名称是 `SOFT_SURFACE`，没有发现仍在源码中的 `SOFT_PATH` 实现入口。旧的 `SoftPathProbeTask`、`SoftMovementPrimitive` 等文件在当前工作树中已被删除或不再可读，但架构文档仍保留旧描述，因此文档和源码存在残留不一致。

根据当前可读源码，`SOFT_SURFACE` 只能确认是一个模式名/目标语义，不足以证明“完整真实玩家行走”已完成。真实行走相关的现有基础设施是：

```text
BotController 输入
  + BotPlayer.aiStep()
  + BasicMovement.travel()
  + Movement 原语
```

但缺少正式闭环证据：

```text
输入 -> 单 tick 物理推进 -> 碰撞/重力 -> 实际到达 -> 后置条件 -> 动态阻挡/失败
```

因此当前不能给 `SOFT_SURFACE` 贴 `USER_ACCEPTED` 或“完整实现”标签。

## 结论

当前最需要解决的不是“再加一个寻路算法”，而是先冻结正式寻路契约：

```text
规划对象是什么？
结果状态是什么？
执行对象是什么？
动态阻挡由谁报告？
领域规划和通用移动如何连接？
```

在这些问题明确前，不应把平行的 `PathPlanner`/`MovementPathExecutor` 直接接入 MineTask，也不应因为类名或注释写着“统一/推荐”就把它们视为正式架构。
