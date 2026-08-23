# Bot 寻路与挖矿：SOFT_SURFACE / HARD_PATH 边界深度调研

**任务**：`.alice-supervision/research/bot-pathfinding-soft-hard-investigation-task.txt`  
**Alice 基线**：`19af345`  
**调研日期**：2026-08-24  
**方式**：只读、联网；未修改业务代码、计划、HANDOVER 或客户端证据，未运行 Alice 测试。

## 1. 核心结论

“软路径更符合真实玩家定位”有明确技术依据，但只能用于已经通过几何可执行性验证的低风险地表移动。Alice 的 `NATIVE_TRAVEL` 调用原版 `LivingEntity.travel`，能够保留原版碰撞、重力、摩擦、跳跃和击退后的速度演化；`HARD_PATH` 用 `setPos` / `setOnGround` 执行，不能自然承载上述动力学。

但真实性不等于可以立刻替代硬路径。Baritone、Mineflayer 都在搜索阶段就将可执行性、风险、破坏/放置与资源代价建模，并在执行期重验和重规划。Alice 当前 SOFT_SURFACE 仅覆盖平地与单格高差实验，尚未覆盖流体、实体拥挤、连续跳跃、冲刺跳、梯子、门、坠落风险、施工、采集或矿链。因此普通挖矿、拾取、道路、隧道仍必须固定 `HARD_PATH`，不得接入软路径。

建议的长期策略是：**软移动负责真实物理执行；硬规划负责权限、安全、施工及可证明结果。**

## 2. Alice 当前实现边界

### HARD_PATH：确定性执行器

`pathing/PathExecutor.java:12-16,85-98` 明确说明 Fake `ServerPlayer` 没有真实客户端持续输入，当前采用手动位置步进：

```java
bot.setPos(...);
bot.setOnGround(true);
bot.fallDistance = 0.0F;
```

优点：固定速度、路径段易审计、段目标受阻时明确失败并要求上层重规划（`59-64`）。缺点：跳过真实速度积分、重力、碰撞挤压、击退、连续跳跃轨迹。

`MineTask.java:14-17,92-116`：矿链只允许最多 2 格、4.5 格内、直接视线的局部清障；否则稳定失败 `target_requires_tunnel`。不自动生成道路/隧道，也不接 SOFT_SURFACE。

### SOFT_SURFACE：原版物理执行实验

`SoftMovementPrimitive.java:39-57` 的 `NATIVE_TRAVEL` 设置相对前进输入并调用：

```java
bot.setJumping(false);
bot.xxa = 0.0F;
bot.zza = 1.0F;
bot.travel(new Vec3(0, 0, 1));
```

上阶以 `setJumping(true)` + `travel` 执行（`61-75`）。抵达下降段后使用 `travel(Vec3.ZERO)` 结算原版重力、摩擦和落地，而不伪造 `onGround`（`79-86`）。

`SoftPathProbeTask.java:15-18,61-136`：仅为独立探针，逐段复核 `HORIZONTAL/ASCEND/DESCEND`，只在真实脚位、碰撞支撑顶面与 `onGround` 同时成立时前进。`FollowTask.java:15-17,45-120`：独立短程同维度跟随，24 格上限、每 10 tick 重算。

`MovementHelper.java:17-215` 已有保守基础：可站/可穿过、危险方块、玩家 AABB 连续扫掠、对角侧格、上下阶、VoxelShape 支撑顶面；但尚非完整玩家物理可行性模型。

## 3. 成熟实现

### Baritone

官方仓库：[cabaletta/baritone](https://github.com/cabaletta/baritone)  
固定源码：HEAD `ad627c83cc0ac6059d7a1b29eca4e54214584b5e`。

| 源码 | 已确认事实 |
|---|---|
| [`AStarPathFinder.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/calc/AStarPathFinder.java) | 维护实际 cost、启发指标、优先队列；`COST_INF` 移动不进入路径；较低代价会更新邻居。 |
| [`ActionCosts.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/api/java/baritone/api/pathing/movement/ActionCosts.java) | 使用近似 tick cost，区分步行、水、灵魂沙、梯子、潜行、冲刺、坠落与跳跃。`WALK_ONE_BLOCK_COST=20/4.317`，`SPRINT_ONE_BLOCK_COST=20/5.612`。 |
| [`MovementHelper.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/movement/MovementHelper.java) | `canWalkThrough`、`canWalkOn`、方块状态、液体、危险、可替换性、破坏/放置可行性及成本。 |
| [`MovementAscend.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/movement/movements/MovementAscend.java) | 上升前检查支撑、可放置性、掉落方块窒息、半砖、可穿过空间；将破坏、放置、跳跃/潜行成本纳入路径。 |
| [`MovementDescend.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/movement/movements/MovementDescend.java) | 评估前方破坏、落差、梯子、水桶与安全落点；不可行返回 `COST_INF`。 |
| [`MovementParkour.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/movement/movements/MovementParkour.java) | 检查冲刺跳距离、障碍、落点、越界安全、农田限制与必要搭方块成本。 |
| [`MineProcess.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/process/MineProcess.java) | 周期刷新矿点与 goal，维护黑名单/掉落扫描，拒绝不可挖或熔岩风险目标。 |

Baritone 不是连续刚体最优控制器，而是离散 movement primitive + 预测 cost + 执行期真实输入控制。可迁移的是“可执行性与成本进入搜索、执行期重验与重规划”，不是复制其 movement 类。

### Mineflayer Pathfinder

官方仓库：[PrismarineJS/mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder)  
固定源码：HEAD `d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95`。

- [`lib/movements.js`](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95/lib/movements.js)：邻居生成包含可挖/可放、实体 AABB 交叠成本、重力方块、液体、门、跳高限制、对角、破坏和放置预算；`safeOrBreak` 把可破坏性、工具挖掘时间和劳动成本加入路线。
- [`lib/physics.js`](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95/lib/physics.js)：使用 `prismarine-physics` 的 `PlayerState` 和 `bot.physics.simulatePlayer(state, world)` 逐 tick 模拟，验证步行跳和冲刺跳是否在限定 tick 内实际抵达。
- [`lib/goto.js`](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95/lib/goto.js)：监听目标改变、path update/stop 和达成事件，驱动重算/失败处理。

Mineflayer 的重要模式是“离散路径 + 局部逐 tick 物理仿真”，而非全局连续物理规划。

### Minecraft 原版导航

Forge 1.20.1-47.4.10 反编译：`GroundPathNavigation` 继承 `PathNavigation`，具备 `createPathFinder`、`canUpdatePath`、`createPath`、`trimPath`、门/围栏配置；`PathNavigation` 具备 `createPath`、`moveTo`、`tick`、`followThePath`、`recomputePath`、`canMoveDirectly`、`isStableDestination`。

它面向 `Mob` 的 AI/`MoveControl`，不是对 Fake `ServerPlayer` 的直接输入替代；可参考动态路径维护，不应未经适配直接套用。

## 4. 物理碰撞、重力与击退

对 Alice 使用的 Forge 1.20.1-47.4.10 jar 反编译确认：

- `LivingEntity.travel(Vec3)` 含重力常量 `0.08d`、空气阻力约 `0.980000019d`、地面相关摩擦 `0.91f`，并经 `moveRelative`、`move(MoverType.SELF, ...)`、`setDeltaMovement` 执行输入、碰撞和速度更新。
- `LivingEntity.jumpFromGround()` 将 `deltaMovement.y` 设为 `getJumpPower()`；冲刺按 yaw 额外加入 `0.2f` 水平速度，设置 `hasImpulse=true`。
- `LivingEntity.knockback(strength, ratioX, ratioZ)` 先触发 Forge `onLivingKnockBack`；按 knockback resistance 缩放；现有水平速度减半后叠加反向归一化冲量。若在地面，垂直速度最高为 `0.4d`。

因此：

1. `NATIVE_TRAVEL` 是实现真实玩家物理的正确基础。它可兼容 mod 对 travel、碰撞、属性和 Forge 事件的改写。
2. HARD_PATH/SELF_MOVE 不适合作为物理真实性基础；`setPos` 跨过速度积分，`setOnGround` 会掩盖真实落地，击退后不自然漂移。
3. 不建议先自研完整物理。需要复刻碰撞形状、液体、梯子、附魔、效果、冲刺、方块摩擦、实体推挤与 Forge hooks，维护成本高。
4. 不必预测每一次随机战斗击退。应把确定的悬崖、流体、岩浆、低顶、实体密集区作为禁行/高成本；受击或速度突变后执行 `SETTLE -> REVALIDATE -> REPLAN/FAIL`。

未找到可作为 Alice 直接依赖的“完整真实物理全局寻路 mod”先例。Baritone 是 primitive/cost/执行控制；Mineflayer 是离散搜索加局部物理模拟。

## 5. 对照表

| 维度 | Alice HARD_PATH | Alice SOFT_SURFACE | Baritone | Mineflayer | MC 原版寻路 |
|---|---|---|---|---|---|
| 路径搜索 | `SurfacePathfinder` 曲面 A*/Dijkstra；矿链固定使用 | 同曲面 A*，仅 probe/follow | A*，primitive 预测 cost 入搜索 | 图搜索，邻居含破坏/放置/实体/液体成本 | `PathNavigation` + `PathFinder` |
| 移动执行 | 手动 `setPos`，到段强制 onGround | `travel` 输入、jump、settle | walk/jump/sneak/parkour 输入状态机 | control state + `simulatePlayer` | Mob `MoveControl`/travel |
| 可执行性校验 | `canWalkOn/Through`、扫掠、视线硬检查 | 同几何，逐段真实支撑/onGround | 可走、可破、可放、危险、跳跃、落差 | 形状、实体、重力块、液体、跳高、dig/place | NodeEvaluator/路径类型 |
| 物理/碰撞 | 预检查，非物理积分 | 原版 travel/move，重力/摩擦/碰撞生效 | 离散预测 + 真实执行输入 | 独立逐 tick PlayerState 模拟 | 原版 Mob 物理，不直适配玩家 |
| 挖矿集成 | `MineTask`/BotMiner；局部清障，深埋拒绝 | 当前禁止 | MineProcess、可破成本、矿点/掉落 | dig/place 可进 movement cost | 无通用玩家矿链 |
| 失败回退 | 阻塞后上层重规划；`target_requires_tunnel` | probe/follow 稳定失败码，无 HARD 回退 | 动态世界/执行失败重算 | path update/stop/goal 变化重算 | tick/recomputePath |

## 6. 硬软共存边界

### HARD_PATH 必须保留

- 普通挖矿、拾取、道路、隧道：当前硬性禁止 SOFT_SURFACE 接入。
- 施工与世界写入：PlaceTask、RoadBuildTask、未来 TunnelPlanner 必须保留候选、保护、流体与资源硬验证。
- transfer、风险区域和预算有限流程：需要可回放的计划、稳定终端码和保守失败。
- 客户端物理不稳定或未知环境：高延迟、多模组移动改写、载具、未知液体/实体堆叠应拒绝或暂停，不应乐观软走。

### 可逐步软化

- 现有独立平地短段和跟随。
- 已 probe 的相邻一格上阶/下降。
- 独立的低风险长距离地表行走，前提是先完成动态重验、重规划和 hazard policy。
- 最早只能作为“到达已经由硬验证确认的入口/站位”的移动子任务；采矿、搭建和安全判断仍保持 HARD_PATH。

## 7. 路线图

### P0：冻结

保持 MineTask、DropCollectionTask、道路、隧道不接 SOFT_SURFACE；不提供自动 HARD fallback。`soft-probe`、`soft-path-probe`、`follow` 仅作独立体验/诊断。

### P1：物理观测与扰动恢复

只增加观测：`deltaMovement`、onGround、fallDistance、支撑顶面、碰撞方向、水平/垂直偏差、实体接触、受击/速度突变。段外偏差进入 `SETTLE -> REVALIDATE -> REPLAN/FAIL`，不得 `setPos` 校正。

### P2：移动 primitive 与风险

扩展连续跳、低顶、斜坡/半砖、门/梯子、流体、悬崖的 primitive 检查；定义致死禁行、可恢复高成本、需用户确认风险。禁止破坏/放置，禁止矿链。

### P3：动态短程软导航

独立 `SoftTravelTask`：距离上限、世界变更重验、碰撞/无进展/速度突变停止，有限重算，稳定失败码。借鉴 Baritone 的搜索/执行分层，不复制源码。

### P4：接入前置条件

仅当 P1-P3 客户端验收、失败码稳定、流体/坠落/推挤/重规划矩阵通过、保护与资源边界审计完成后，才可由用户逐链批准“软移动至已硬验证站位”。每个 mine、pickup、road、tunnel 链必须单独批准；`SEARCH_LIMIT` 永远不等于 `UNREACHABLE`。

### P5：当前未批准的任务接入

即使批准，soft travel 也只能抵达已由 HARD_PATH/TunnelObstaclePolicy 确认的入口或站位。挖掘、清障、搭建、流体、保护、掉落归属和最终交互继续 HARD_PATH。软搜索失败不得变成自动挖通授权。

## 8. 客户端验证矩阵

| 类别 | 必测项 | 通过证据 |
|---|---|---|
| 基础物理 | 平地、半砖、上/下台阶、零输入 settle | 脚位、supportTopY、onGround、fallDistance；无强制 setPos |
| 碰撞 | 低顶、墙角、对角夹角、栅栏/门、实体推挤 | 无穿墙；阻塞稳定失败或重规划 |
| 重力 | 一格下落、不同支撑顶面、悬边 | 不伪造 onGround；仅真实支撑后完成 |
| 跳跃 | 单格上升、顶棚阻断、连续 jump 限制 | 真实 travel 轨迹；失败不传送 |
| 扰动 | 受击/击退、效果变化、延迟观察 | 速度突变 settle/revalidate/replan 或保守失败 |
| 危险 | 岩浆、火、深坑、水流、实体堆 | 禁行/高风险失败，不硬穿越 |
| 动态世界 | 中段封路、支撑移除、目标移动 | 重验重算或失败，无 stale path |
| 隔离 | mine/pickup/road/tunnel/transfer | 确认软路径无偷接入、无自动 HARD fallback |

Headless PASS、日志“完成”或单次无崩溃不能替代客户端物理证据。

## 9. 迁移边界

### 直接可迁移

- 每种 movement primitive 在搜索前建模可执行性、风险与成本。
- 执行期重验，无进展、世界变更、速度偏差后的重规划或稳定失败。
- 对角、扫掠、支撑与落点一体校验。
- 将实体拥挤、重力方块、液体、破坏/放置风险作为决策输入。
- 区分 `NO_PATH`、`SEARCH_LIMIT`、`BLOCKED_SEGMENT`、`UNSETTLED`、`HAZARD`、`PHYSICS_DEVIATION`。

### 需适配 Forge 1.20.1 / Alice

- Baritone cost 必须按 Alice 禁止操作、Fake ServerPlayer、保护区、工具和背包策略重建，不能照搬数字。
- Mineflayer 的 Node/协议/`prismarine-physics` 不能直接使用；应借鉴逐 tick 模拟原则，优先复用 Forge `travel`。
- 原版 PathNavigation 面向 Mob，需为 BotPlayer 建适配层并验证服务端 tick 行为。
- 风险模型必须接入 SurvivalSystem、任务生命周期、ScopeBuffer 与保守失败规范。

### 专属耦合，不可直接迁移

- Baritone client mixin、输入控制、世界缓存、配置、inventory behavior、break/place 生命周期。
- Mineflayer 协议客户端、Node 事件循环、PlayerState、版本数据注册表。
- 两者完整矿点扫描/世界视野模型不能越过 Alice 的保护和掉落归属边界。

## 10. 证据缺口

- 未运行 Baritone、Mineflayer、原版或 Alice 客户端；客户端可见物理结论必须 Windows 客户端实测。
- Mineflayer pathfinder 固定源码直接依赖 `prismarine-physics`；本次未固定其独立仓库版本，不能作更强版本断言。
- `0.08`、约 `0.98`、`0.91` 来自 Forge 1.20.1-47.4.10 `LivingEntity.travel` 反编译；完整运动仍受状态、方块、流体、效果和 Forge hooks 影响。

## 11. 最终建议

未来应优先投资 NATIVE_TRAVEL 的观测、primitive 可行性、扰动恢复与客户端验证矩阵，不让软路径直接接入矿链。只有每个任务链单独获得用户批准后，才可从“软移动至已硬验证站位”开始受控接入。

**本报告不构成实现授权**
