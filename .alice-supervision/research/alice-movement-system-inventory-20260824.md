# Alice 移动体系现状地图（深度调研对照基线）

- 规划角色：Alice 实现规划员+
- 日期：2026-08-24
- Git 基线：`19af345281f61223c465e4cb632382fee1e11941`
- 工作会话门禁：`./tools/work-session-start.sh --allow-no-plan` 已执行；因 active plan 为 `NO_ACTIVE_PLAN` 以退出码 1 拒绝启动。此任务仅为深度调研对照的只读地图。
- 已读任务定义：`.alice-supervision/research/bot-pathfinding-soft-hard-investigation-task.txt`

> **本规划不构成实现授权。** 不得把本地图、外部 Baritone/Mineflayer 对照或“软路径更真实”的方向直接转化为代码、active plan 或矿链接入授权。

## 1. 现状结论

Alice 当前不是"硬搜索 vs 软搜索"的二分：两条已存在软任务和硬任务**共用同一离散 `SurfacePathfinder` 曲面脚位路径**，主要差异在**移动执行**和**成功证据**。

- **HARD_PATH**：`AStarPathfinder`/`SurfacePathfinder` 先证明离散脚位可达，`PathExecutor` 随后手动 `setPos` 小步推进、到段后手动 `setOnGround(true)`。它可确定性重放、无需 fake player 客户端，但不运行完整玩家重力/摩擦/真实移动输入。
- **SOFT_SURFACE 实验**：同一离散脚位路径先经 `MovementHelper` 重验单格几何，然后逐 tick 调原版 `ServerPlayer.travel(...)`（`NATIVE_TRAVEL`）或碰撞 `move(MoverType.SELF, ...)`（`SELF_MOVE` 对照）；最终以实体真实 `onGround` 和碰撞形状顶面支撑证明到达。它更贴近真实玩家物理，但当前仅限独立探针和 follow。
- **道路/隧道**：并非软路径分支。道路是曲线/体素离散几何 + 世界硬验证 + `RoadBuildTask` 强制施工/强制移动；隧道是独立保守规划/验证，尚未接入 `MineTask`。

当前协议冻结：`MineTask`、`DropCollectionTask`、普通挖矿必须保持 `HARD_PATH`；`SOFT_SURFACE` 不得接入矿链、道路、隧道、流体、逃生。`SEARCH_LIMIT != UNREACHABLE`，不能授权隧道（`docs/SUPERVISION_PROTOCOL.md:13-18`；`TunnelPlanner.java:60-76`）。

## 2. 类/方法职责与硬软属性地图

| 层/类 | 关键方法/路径 | 职责 | 当前属性 | 证据与边界 |
|---|---|---|---|---|
| `MovementMode` | `HARD_PATH`、`SOFT_SURFACE`、`SOFT_FLUID`、`FORCED_BUILD` | 移动**语义**枚举 | 分类边界，不等于具体后端 | `MovementMode.java:3-15`；注意 `SELF_MOVE/NATIVE_TRAVEL` 是 `SoftMovementPrimitive.Backend`，不是 `MovementMode` 成员 |
| `AStarPathfinder` | `computeDetailed`、`tryMove` | 离散曲面脚位搜索；状态为 `REACHED/UNREACHABLE/SEARCH_LIMIT` | 硬：server world-read、节点/步数/边界上限、确定性成本 | `AStarPathfinder.java:21-25,34-92,135-187`；Dijkstra（零 heuristic），水平/下降/上阶/原地下行成本固定 |
| `SurfacePathfinder` | `find`、`findFirst` | A* 门面，保留结构化可达/不可达/搜索预算结果 | 硬搜索底座，软探针也复用 | `SurfacePathfinder.java:8-12,18-60`；不挖/不放世界 |
| `MovementHelper` | `canWalkOn/Through`、`canTraverse/Ascend/Descend`、`canSweepPlayer`、`supportTopY`、`isStandingAtFootPos` | 离散可行性和连续碰撞/支撑顶面复核 | 硬几何判定；为软物理提供真实性证据 | `MovementHelper.java:17-45,55-87,130-228`；拒 lava/fire/magma/source fluid，兼容 slab/stair 碰撞顶面 |
| `PathExecutor` | `tick` | 硬路径执行：小步 `setPos`，到段手动 Y 对齐/`setOnGround(true)`；段被动态方块阻断则 FAILED/replan | **HARD_PATH** 固定执行器 | `PathExecutor.java:9-20,50-112`；不模拟完整玩家物理，`MODE=HARD_PATH` |
| `SoftMovementPrimitive` | `applyToward`、`applyJumpToward`、`settle` | 软移动单 tick：转向 + `travel` 或 `move(SELF)`；零输入 settle 结算重力/摩擦 | **SOFT_SURFACE 原语** | `SoftMovementPrimitive.java:8-10,39-86`；NATIVE_TRAVEL 设 `xxa/zza`/jump 后调用原版 travel，SELF_MOVE 仅回归对照 |
| `SoftMoveProbeTask` | `validate`、`tick`、`settleAtTarget` | 8 格同高度安全平地短距离实验 | 软、隔离 | `SoftMoveProbeTask.java:11-18,37-70,94-161`；不接 MineTask/PathExecutor，120 tick 上限、hazard/no-progress/settle 失败码 |
| `SoftPathProbeTask` | `tick`、`segmentAction` | 连续脚位软探针：规划 → 单段重验（水平/上阶/下降）→ NATIVE_TRAVEL → 实际脚位/支撑/onGround settle | 软、隔离 | `SoftPathProbeTask.java:15-18,50-79,81-179`；400 tick 限制；失败保留 `soft_path_*` 诊断 |
| `FollowTask` | `replan`、`tick` | 同维、24 格、每 10 tick 重算的短程跟随 | 软、独立状态 | `FollowTask.java:15-23,45-120`；复用 SurfacePathfinder + NATIVE_TRAVEL + settle；不接保护区/矿链 |
| `BotMiner` | `pickStandCandidates`、`SurfacePathfinder.find`、`PathExecutor`、`lineOfSightClear`、`faceTarget`、`handleBlockBreakAction` | 选 4.5 格内可挖站位，硬寻路，视线/距离/保护/液体检查后按原版破坏协议采矿 | 硬确定性执行 | `BotMiner.java:22-30,74-190,231-447`；路径可重试，不能改为软移动而不重审全部采矿站位/时序 |
| `MineTask` | `tick`、局部清障、`DropCollectionTask` | 单目标任务编排：最多 2 个直接可见/4.5 格本地清障 → 挖 → 拾取；深层失败 `target_requires_tunnel` | **HARD_PATH 矿链** | `MineTask.java:14-17,61-116`；创建时替换 selected 主手为钻石镐并同步 |
| `DropCollectionTask` | `tick`、`SurfacePathfinder`、`PathExecutor`、有限 `BotMiner` 阶梯清障 | 只拾取 origin UUID 锁定掉落物，曲面硬寻路，有限侧向阶梯恢复 | **HARD_PATH 矿链** | `DropCollectionTask.java:24-31,67-176`；不调用 TunnelPlanner |
| `AutoMineDecision` | `pickNearest` / `pickNearestBlock` | 感知/规则决策：扫描未保护目标，交 `TaskTarget` | 决策层，非移动执行器 | `AutoMineDecision.java:13-24,34-76`；LLM 只可替换此目标选择，不绕过执行安全契约 |
| `ContinuousRoadCurve` | `candidates`、`voxelizeQuadratic` | 生成直线+双侧二次 Bezier 曲线候选并体素化；不读世界 | 硬数学几何候选 | `ContinuousRoadCurve.java:10-13,22-50,55-117`；非玩家物理/非寻路执行 |
| `RoadPlan` | `buildUnits`、`selectContinuousRoute`、`shortestVoxelRoute`、`spiralCompensationRoute`、`validateRoute` | 选曲线或几何体素 A* 后备，必要时螺旋补高差；生成且验证离散道路单元 | 硬世界验证/蓝图 | `RoadPlan.java:108-185,188-218`；验证净空、液体、对角/高差/连续性，独立于 mine |
| `RoadBuildTask` | BUILD/WAIT/MOVE phases | 按已验证 RoadPlan 清净空、放 cobble、稳定检测、可见强制移动 | `FORCED_BUILD`，非 soft/hard normal path | `RoadBuildTask.java:20-25,26-79`；不走普通寻路/放置面判定；仍是独立道路施工功能 |
| `TunnelObstaclePolicy` | `validate` | 候选通道的保守硬验证：连续性、净空、流体、保护/不可破坏、支撑需求 | 硬验证，**不执行** | `TunnelObstaclePolicy.java:12-15,20-55` |
| `TunnelPlanner` | `plan`、`SurfaceFailureReport.confirmedUnreachable` | 仅连接明确曲面入口/出口，候选过 hard validate 后加两端 SurfacePathfinder 成本 | 硬规划，**未接 MineTask** | `TunnelPlanner.java:9-12,17-57,60-76`；所有站位必须明确 UNREACHABLE，SEARCH_LIMIT 只可上交 |
| `BotManager.BotSession` | `replaceTaskIfRunning`、`assign*`、`tick` | 单 bot task 生命周期/排他、SurvivalSystem 中断、target 广播 | 调度耦合点，非路径实现 | `BotManager.java:443-537`；所有软/硬 task 经此进入，in-transit transfer 禁止替换 |

## 3. 硬路径保留核心（确定性、可证明、可回放）

以下不是“旧实现可随软移动替掉”的细节，而是未来任何软路径方案也必须保留或等价保留的服务端权威能力：

1. **结构化搜索结果与失败分类**：`SurfacePathfinder.Result` / `AStarPathfinder.SearchStatus` 需继续区分 `REACHED`、`UNREACHABLE`、`SEARCH_LIMIT`（`AStarPathfinder.java:34-44,90-92`）。绝不能把预算耗尽当不可达或通道授权。
2. **离散几何预过滤**：`MovementHelper.canWalkOn/Through/Traverse/Ascend/Descend`、对角侧格/头部/玩家 AABB sweep、危险/流体拒绝（`MovementHelper.java`）。软 `travel` 是执行和观测，不是跳过预检的许可。
3. **可审计任务边界**：`Task` + `BotSession` 的单任务排他、SurvivalSystem 中断、`TaskExecutionRecord`、稳定失败码；软路径不能绕开 `replaceTaskIfRunning` 或在 transfer in-transit 时替换任务。
4. **采矿执行安全链**：`BotMiner` 的目标站位候选、4.5 格 reach、raycast 视线、`BlockBreakSafety`、`FluidRiskPolicy`、原版 break progression；“更真实移动”不改变挖掘授权。
5. **道路/隧道双层验证**：曲线只生成候选，`RoadPlan` 判世界合法；TunnelPlanner 只在全站位确认 UNREACHABLE 后，以 `TunnelObstaclePolicy` 硬拒绝流体/保护/断裂。不得因软执行失败隐式构建道路或通道。
6. **离散/连续到达证据分离**：软路径额外有 `onGround + supportTopY + actual foot`；但规划脚位、日志阶段、上层任务终态仍需结构化、可回放。

## 4. 软路径现状、已验证范围与禁止接入点

### 4.1 已软的部分

- **NATIVE_TRAVEL（默认实验后端）**：向朝向写 `zza=1`/`xxa=0` 并调 `ServerPlayer.travel`，上阶专用原语显式 `setJumping(true)`（`SoftMovementPrimitive.java:39-75`）。它让原版处理碰撞、重力、摩擦、落地。
- **SELF_MOVE（回归对照）**：同样朝向后用 `bot.move(MoverType.SELF, delta)`（`SoftMovementPrimitive.java:48-56`）；HANDOVER 明确跨障不稳定，不适合扩展。
- **settle 与实体真值验收**：`SoftMovementPrimitive.settle(Vec3.ZERO)` 不伪造 `setOnGround`；`MovementHelper.supportTopY/isStandingAtFootPos` 支持下半砖/台阶；Soft* tasks 只有目标脚位、顶面支撑和 onGround 同时成立才 DONE（HANDOVER `:26,38`；`SoftMoveProbeTask.java:145-161`）。
- **独立软任务**：`SoftMoveProbeTask`（同高 <=8 格）、`SoftPathProbeTask`（A* 连续段）、`FollowTask`（同维 <=24 格、每 10 tick replan）。每个都含 hazard/no-progress/timeout/settle 失败码。

### 4.2 当前禁止/未验证

- 禁止接入：`MineTask`、`DropCollectionTask`、普通挖矿、道路、隧道、流体、逃生（协议 `docs/SUPERVISION_PROTOCOL.md:13-18`；HANDOVER `:30-38,246-254`）。
- `SOFT_FLUID` 仅枚举占位（`MovementMode.java:9-14`），无可调用执行器；不能推断支持水/岩浆。
- `SoftPathProbeTask` 的上阶仅限“已由曲面 A* 规划且重验为相邻 ASCEND”的显式 jump；它不是 parkour、连续跑跳、游泳、攀爬或通用自动跳跃。
- 未完成客户端证据：碰撞后重规划、击退、实体推挤、动态流体、连续多高差、逃生等。headless 路径成本证明不等于 fake player 客户端物理（HANDOVER `:28,42`）。

## 5. 未来软路径接入的现有耦合点（不构成授权）

### 5.1 接入挖矿/拾取

需同时改变/抽象的耦合点：

1. `BotMiner.executor` 当前字段类型直接是 `PathExecutor`，构造点在 stand candidate 选择后（`BotMiner.java:39-43,116-155`）。软接入至少需一个共同 `PathExecution` 契约或在 BotMiner 内明确选择 executor；不能仅替换 `new PathExecutor` 而保留 hard-only 的完成/重试假设。
2. `DropCollectionTask.executor` 同样直接持有 `PathExecutor`（`DropCollectionTask.java:43,143-175`），且有有限 stair `BotMiner` 清障。软接入需另审掉落物位置、实体移动、目标动态变化、forcePickup 时序，不能只切后端。
3. `MineTask` 继承 BotMiner/DropCollectionTask 的 HARD_PATH 链，同时启动时改 selected 主手（`MineTask.java:34-43`）。软执行必须继续接受 survival、视线、reach、工具/库存和 break 原子性约束。
4. 搜索层（`SurfacePathfinder`）目前可复用，但 MovementHelper 只覆盖有限步型。若要物理碰撞/击退/动量，需扩展“候选状态”与“执行后偏离→安全重规划”契约，不能伪称现有脚位段足以表达速度/空中状态。

### 5.2 接入道路

1. `RoadPlan` 生成的是“可施工的 support/clearance 单元”，不是 bot 走路路径；`RoadBuildTask` 明确不使用普通寻路（`RoadBuildTask.java:20-25`）。软路径接入道路必须先决定是“施工者去下个已经建好单元”的独立 movement primitive，还是改施工拓扑；两者不可混包。
2. `RoadBuildTask` 当前以 `MOVE_SPEED` 和直接位置变化展示施工过程，且 construction 与 world edit 同 task。软化其移动会使方块放置/实体位置/稳定检测耦合，须单独工作包和 Windows 矩阵。
3. 曲线/体素/螺旋以及 `RoadPlan.validateRoute` 硬几何验证均须保留；软失败不能改用道路生成作为兜底。

### 5.3 接入隧道

1. `TunnelPlanner` 尚未被 `MineTask` 调用，因此没有“只改后端”的插点；要接入需先有被批准的 TunnelPlan 执行任务。
2. `TunnelPlanner.SurfaceFailureReport` 强制所有站位 `UNREACHABLE`；任何软路径搜索耗尽/执行失败也不得转译成可挖通道。需定义软执行失败是否意味着重新搜索、挂起还是人工决策，不能简单映射 `SEARCH_LIMIT`。
3. `TunnelObstaclePolicy` 的流体/保护/不可破坏/支撑拒绝是施工授权底线，不应由真实物理移动替代。

### 5.4 Task/调度耦合

- 所有入口最终经 `BotManager.BotSession.assignSoftMoveProbe/assignSoftPathProbe/assignFollow/assign/assignRoadBuild`，统一 `replaceTaskIfRunning` 与 target 广播（`BotManager.java:443-537`）。未来软任务须作为独立 `Task` 加入并遵守任务排他/transfer guard。
- `BotManager.isBusy` 被测试入口和 inventory GUI 保护使用；软任务接入不能绕开它，否则会和 transfer in-transit/GUI inventory write 产生竞态。
- `PathExecutor` 并非接口，所有持有者直接依赖其 class（BotMiner/DropCollectionTask/TransferTask）；若未来统一 hard/soft executor，需单列一次小的接口抽取并完整回归，不能在矿链功能包中顺便重构。

## 6. 深度调研对照准备（Alice 侧定义）

外部调研应使用下列定义，不要按外部库的命名把 Alice 概念误对齐：

| 对照维度 | Alice HARD_PATH（当前事实） | Alice SOFT_SURFACE（当前事实） | 外部对照时应问 |
|---|---|---|---|
| 路径搜索 | A*/Dijkstra，有限 Node/Move/边界，离散脚位、MovementHelper 验证 | **复用同一** SurfacePathfinder；不是独立软搜索 | Baritone/Mineflayer 是否把物理状态（速度/空中）纳入搜索 state？ |
| 执行 | `setPos` 小步、到点手动 Y/onGround | `travel` 输入或 `move(SELF)`，再 settle | 外部 movement primitive 如何交给原版物理、如何检测偏离？ |
| 可执行性 | block collision/支撑/危险/对角/sweep hard check | 先同样重验，再以实际 foot/support/onGround 证明 | 是否区分离散可行和运行时物理可达？ |
| 物理/碰撞 | 未模拟完整重力/摩擦，手动对齐 | NATIVE_TRAVEL 已结算碰撞/重力/摩擦；无击退模型 | 如何处理 jump、step、sneak、parkour、push/knockback、流体？ |
| 挖矿集成 | BotMiner 站位+视线+reach+原版 break；Mine/Drop chain hard | 明确禁止接入 | 外部库是否把 break/place 当 path move，Alice 哪些授权契约不可迁移？ |
| 道路/隧道 | RoadPlan/TunnelPlanner 独立 world-edit/验证 | 无 | 外部 "break-aware path" 能否在 Alice 保持独立授权边界？ |
| 失败回退 | SEARCH_LIMIT/UNREACHABLE 分开；不自动 tunnel/road | soft_path_* / follow_* 失败，仅 task failed | 外部 replan/penalty/fallback 是否会隐式破坏/放置？ |

### 外部模式的初始迁移筛选

- **可能可借鉴（需 Forge/Alice 适配）**：movement 抽象（规划步型与执行步型分开）、运动后状态验证、偏离后的有限 replan、按风险/物理状态分阶段启用。
- **必须保留 Alice 语义再适配**：server authoritative task 生命周期；SURVIVAL/FluidRisk/BlockBreakSafety；`SEARCH_LIMIT != UNREACHABLE`；矿链/道路/隧道各自授权；客户端 Windows evidence。
- **不可直接迁移**：Baritone 的 break/place-aware path 直接授权、Mineflayer 的 Node/客户端物理模型、Fabric fake player 生命周期、外部库的 inventory/permission/packet 假设。它们不能覆盖 Alice Forge 1.20.1、`BotPlayer/FakeConnection`、现有 transfer/GUI task guard。

## 7. 供外部调研明确验证的问题

1. Baritone `Movement` 体系如何把“静态可走判定”与“执行后成功/失败”分开，哪些原语适合映射到 Alice 的 `MovementHelper + SoftMovementPrimitive`？
2. Baritone/Mineflayer 的 replan 是否把 `SEARCH_LIMIT` 与真正不可达区分；其 break/place fallback 在 Alice 应被哪些现有授权边界阻断？
3. 原版 player `travel` 在 fake `ServerPlayer` 上对重力、jump、collision、knockback 的实际服务端可用性；Alice NATIVE_TRAVEL 已验的最小链路外，哪些 hook 需要固定 Forge 1.20.1 source check？
4. 物理状态（速度、onGround、fallDistance、受击/流体）是否需要扩展 `Task`/executor 的结果对象，而不是塞进 `BlockPos` A*？
5. 路径偏离后如何保留 bot inventory/transfer/target scope 语义并安全重规划，不隐式改任务目标或进入道路/隧道？

## 8. 交付状态

- 报告路径：`.alice-supervision/research/alice-movement-system-inventory-20260824.md`
- 仅只读：未修改业务代码、active plan、HANDOVER、审核/客户端记录；未运行测试。
- 现有 active plan 为 `NO_ACTIVE_PLAN`，本报告只供深度调研对照与监督员后续规划。
- **本规划不构成实现授权。**
