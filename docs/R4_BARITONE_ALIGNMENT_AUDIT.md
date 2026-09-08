# Alice × Baritone 内核对齐审计（R3 搜索 / R4 会话 / R5 交互）

- 日期：2026-09-09
- 路线约束：D-036（Alice = Baritone 兼容内核；差异只在目标层，其余默认对齐）
- Baritone 基线：`/home/fb486/projects/reference/baritone/`，commit `64333af99a072caa3f4d6f17e4b223a5ae7da3f4`
- 方法：Alice 源码逐行 + Baritone 源码逐行对照；成本数值用 `ActionCosts` 公式实算；客户端日志实测 tick 数交叉验证
- 验证等级：**静态对照 = IMPLEMENTED**；成本相对权重有客户端日志佐证（§2.4）；其余对齐项尚未运行时验证

---

## §1 Baritone 关键事实（本文自证，均可复核）

### 1.1 PathExecutor（`src/main/java/baritone/pathing/path/PathExecutor.java`）

| 事实 | 位置 |
|---|---|
| 段完成 → **同一 tick** `pathPosition++` + `onChangeInPathPosition()` + 递归 `onTick()`，**不等待 settle** | `:231-236` |
| `onChangeInPathPosition()` 只做 `clearKeys()` + `ticksOnCurrent = 0`（0 tick 的"松键"） | `:593-596` |
| 段超时：`ticksOnCurrent > 规划期缓存的原始成本估计 + movementTimeoutTicks`（默认 **100**） | `:243`、`Settings.java:591` |
| 漂移判据 = 玩家到**整条路径所有 Movement 的 validPositions** 的最近距离 | `:256-267` |
| 软阈值 2 格：超时累计 `ticksAway > MAX_TICKS_AWAY(200)` 才 cancel；硬阈值 3 格：立即 cancel | `:52-59`、`:129-145` |
| MovementFall 例外：空中下落改用**水平**距离判定 | `:317-323` |
| 吸附 `snipsnapifpossible()`：onGround 或液体中，且 `deltaY >= -0.1`，`path.positions().indexOf(feet)` → 跳索引 | `:324-345` |
| 每 tick `getValidPositions().contains(feet)` 检查，不在则前/后搜索并 `reset()` 中间 Movement | `:101-124` |
| 世界变化：`costVerificationLookahead`（默认 **5**）前瞻成本 ≥ COST_INF → cancel；`maxCostIncrease`（默认 **10**）→ cancel | `:195-218`、`Settings.java:516,523` |
| 破坏/放置集合缓存：按 `pathPosition ± 10` 校验，变化则全量重算 | `:147-176` |
| 下一段目标所在区块未加载 → `clearKeys()` + 暂停 | `:186-192` |
| `safeToCancel()`：Movement 自行决定能否被新路径抢占；`shouldPause()` 在更优路径出现时暂停 | `:194`、`:257`、`:268-300` |

### 1.2 Movement 状态机（`src/main/java/baritone/pathing/movement/Movement.java`）

| 事实 | 位置 |
|---|---|
| 每 tick `clearAllKeys()` 后按状态重新设置输入（无残留按键） | `:122-150` |
| 液体中且脚位低于目标 0.6 → JUMP | `:125-127` |
| **卡墙自救**：`player.isInWall()` → 换最优工具 + `CLICK_LEFT` | `:129-132` |
| `prepared()`：待破坏方块的可达性/旋转、下落方块暂停、无可达面 → `UNREACHABLE` | `:155-197` |
| 状态完成 → 再次 `clearAllKeys()` | `:146-148` |

### 1.3 成本（`src/api/java/baritone/api/pathing/movement/ActionCosts.java` 等）

- 单位是 **tick**：`WALK_ONE_BLOCK_COST = 20/4.317 = 4.633`（`:25-27`）
- `JUMP_ONE_BLOCK_COST = d2t(1.25) - d2t(0.25) = 3.163`（`:63`、公式 `:78-95`）
- `jumpPenalty` 默认 **2.0**（`Settings.java:136`）
- ASCEND = `max(JUMP, WALK) + jumpPenalty = 6.633`（`MovementAscend.java:127-140`）
- DESCEND = `WALK_OFF(3.706) + max(FALL_1(5.615), CENTER(0.927)) = 9.321`（`MovementDescend.java:125-130`）
- **相对 WALK 的权重**：TRAVERSE 1.00 / DIAGONAL 1.41 / **ASCEND 1.43** / **DESCEND 2.01**

### 1.4 其它

| 事实 | 位置 |
|---|---|
| A* 系数 `COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10}` | `AbstractNodeCostSearch.java:69` |
| 候选由 `Moves` 枚举 + `AStarPathFinder` 遍历（含 DOWNWARD/PILLAR/FALL/PARKOUR） | `Moves.java:30+`、`AStarPathFinder.java:82-102` |
| 世界视图 `BlockStateInterface`：CachedWorld 缓存 + `isLoaded`/`worldContainsLoadedChunk` | `BlockStateInterface.java:79,135,142` |
| 危险方块：`avoidWalkingInto` = LAVA / FIRE / MAGMA(可配置) / **CACTUS** / **SWEET_BERRY_BUSH** | `MovementHelper.java:350-355` |
| 可穿行黑名单：cobweb / end portal / cocoa / skull / bubble column / shulker / slab / trapdoor / honey / end rod / berry bush / dripstone / amethyst / azalea / powder snow | `MovementHelper.java:149-155` |

---

## §2 已自证的对齐缺口（我逐行核对，证据充分）

### 2.1 成本模型：单位与相对权重都不对齐（P0）

| 动作 | Baritone 相对成本 | Alice 相对成本 | 判定 |
|---|---|---|---|
| TRAVERSE | 1.00 | 1.00 | 对齐 |
| DIAGONAL | 1.41 | √2 ≈ 1.41 | 对齐 |
| **ASCEND** | **1.43**（`(4.633+2.0)/4.633`） | **2.00** | 偏离（贵 40%） |
| **DESCEND** | **2.01**（`(3.706+5.615)/4.633`） | **1.00** | 偏离（便宜 50%） |

- Alice 位置：`CostModel.java:22-28`（`TRAVERSAL` 1.0/√2/2.0/1.0，无单位注释）。
- Baritone 位置：`ActionCosts.java:25-63`、`MovementAscend.java:127-140`、`MovementDescend.java:125-130`、`Settings.java:136`。
- **日志实测佐证**（`r4-session-8dd744d5`）：TRAVERSE 段 ~6 tick，DESCEND 段 ~15 tick，PLACE_STEP 段 ~15 tick
  → 实测 DESCEND ≈ 2.5× TRAVERSE，与 Baritone 的 2.01 一致，与 Alice 模型的 1.0 矛盾。
- 后果：A* 偏好与 Baritone 相反（Alice 认为下降最便宜、上升最贵），且段超时公式 `ceil(cost*20)+100`
  的隐含单位假设与实际物理不符。
- 对齐方向：改用 Baritone 的 tick 制公式（含 `jumpPenalty`），Alice 侧只需按 D-025 重标定跳跃相关常量；
  `GoalFoot.heuristic` 必须同步换成同单位的可采纳下界。

### 2.2 危险方块集合缺失（P0，安全）

- Alice：`pathing/MovementHelper.java:49-53` 只挡 LAVA / FIRE / MAGMA_BLOCK。
- Baritone：`MovementHelper.java:350-355` 另挡 **CACTUS**、**SWEET_BERRY_BUSH**（且 MAGMA 可配置）。
- 后果：Alice 会把仙人掌/甜浆果丛当作可通行，规划穿行 → 受伤。属"Baritone 有而 Alice 缺失"。

### 2.3 执行器缺少卡墙自救 / 液体处理（P0）

- Alice：`pathing/core/MovementExecution.java` 接口只有 tick/cancel/phase，无 `safeToCancel`/`reset`；
  `core/` 与 `session/` 内 grep 无 `isInWall` / `isLiquid` / `FallingBlock` 处理。
- Baritone：`Movement.java:129-132`（卡墙→换工具+破坏）、`:125-127`（液体→JUMP）、`:155-197`（下落方块暂停）。
- 后果：bot 被推进方块内或卡墙时，Alice 只能等段超时失败，无法自救。

### 2.4 漂移判据是自制的距离启发式（P1，已在 D-035 登记为临时）

- Alice：`PathSession.driftedOutOfSegment()` = "低于本段最低脚位 >1 格 或 同时离两端 >3 格"（对**当前段**）。
- Baritone：对**整条路径**所有 Movement 的 validPositions 取最近距离（`:256-267`），软 2/硬 3 + 200 tick（`:129-145`），
  且 MovementFall 用水平距离（`:317-323`）。
- 注意：客户端扰动测试（位移 1 格）在 Baritone 语义下**同样不会 cancel**（距离 < 2），
  即"扰动被在途段吸收并自行走回"是 Baritone 一致行为，不是缺陷。

### 2.5 WorldView 是未实现的空接口（P2，R7 前置）

- Alice：`pathing/core/WorldView.java` 只有接口，全仓库无实现；搜索实际通过
  `MovementContext.live(bot, level, request)` 直读 `ServerLevel`。
- Baritone：`BlockStateInterface`（缓存 + 未加载区块语义）。
- 后果：R7 异步搜索没有世界快照基础；当前抽象是"自制但未落地"，应落地或删除。

### 2.6 A* 系数与 Movement 类型集差异（P1/P2）

- 系数：Alice `{1.5, 2, 2.5, 4.5}`（`AStarMovementSearch.java:29`） vs Baritone `{1.5, 2, 2.5, 3, 4, 5, 10}`（`:69`）。
- 缺失类型：Baritone 有 FALL / PILLAR / PARKOUR / DOWNWARD（`Moves.java:30+`），Alice 无
  —— 与 D-024 落差红线一起评审，不能单独补。

### 2.7 段间 settle 与段推进（P1，体验，已登记）

- Alice：每段 `settleTicks = 10` + `stopMovement()` 直到落地且水平速度 <0.05（`PathSession.java:93-102`、`:143`）。
- Baritone：同 tick 推进，仅 `clearKeys()`（`:231-236`、`:593-596`）。
- 现象：每格一次顿挫（客户端实测每个段边界 2~3 tick 停止）。

---

## §3 分层对照表（并行审计结果）

> 待合并：A 搜索内核 / B 执行会话 / C Movement 与成本 / D 世界交互。

---

## §4 对齐清单（建议顺序）

| 优先级 | 项 | Alice 改动点 | Baritone 依据 |
|---|---|---|---|
| P0 | 危险方块集合对齐（CACTUS/SWEET_BERRY_BUSH 等） | `pathing/MovementHelper.avoidWalkingInto` | `MovementHelper.java:350-355` |
| P0 | 成本模型改 tick 制 + 相对权重（ASCEND 1.43 / DESCEND 2.01）与启发式重标定 | `CostModel`、`GoalFoot.heuristic` | `ActionCosts.java:25-63`、`MovementAscend:127-140`、`MovementDescend:125-130` |
| P0 | 卡墙自救 + 液体跳跃 | `MovementExecution` 契约 + 各执行器 | `Movement.java:125-132` |
| P1 | 漂移判据改"到整条路径 validPositions 的最近距离"（软 2/硬 3/200 tick） | `PathSession.driftedOutOfSegment` | `PathExecutor:129-145,256-267` |
| P1 | 世界变化检测：前瞻 5 + 成本涨幅 10 + 破坏/放置集合缓存重算 | `PathSession.currentTargetStillValid` | `PathExecutor:147-218` |
| P1 | 段间条件 settle（同 tick 推进，仅在需要精确落点时停） | `PathSession.tick` | `PathExecutor:231-236,593-596` |
| P1 | A* 系数对齐 {1.5,2,2.5,3,4,5,10} | `AStarMovementSearch.COEFFICIENTS` | `AbstractNodeCostSearch:69` |
| P2 | 未加载区块语义（暂停/拒绝） | 搜索 + 执行 | `PathExecutor:186-192`、`BlockStateInterface:79` |
| P2 | `WorldView` 落地（R7）或删除 | `pathing/core/WorldView` | `BlockStateInterface` |
| P2 | `safeToCancel`/路径抢占 | `MovementExecution` + `PathSession` | `PathExecutor:194,257,268-300` |
| P2 | 缺失 Movement 类型（FALL/PILLAR/PARKOUR/DOWNWARD）—— 与 D-024 一起立项 | `MovementType` + provider + 执行器 | `Moves.java:30+` |

---

## §5 存疑与未验证

- 成本对齐后的**路径偏好变化**需要客户端复测（可能改变既有场景的路线）。
- Baritone 的 `MovementParkour`/`MovementFall` 与 D-024 落差红线冲突，属需用户决策的目标差异。
- Alice 假人物理（D-025 maxUpStep 0.6、控制相位差 1 tick）导致 Baritone 的**常量**不可直接照抄，
  只抄语义与相对权重，数值需按 §2.1 方式实测标定。
