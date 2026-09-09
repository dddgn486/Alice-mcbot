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
- **破坏成本**：Alice `breakTicks/20` 把 tick 当秒折算，导致"破石 ≈ 0.375 走路单位"而 Baritone 是 2.05 走路单位（≈5.5× 偏差）。
  若保留归一化单位（走路 1.0），除数应为 `WALK_ONE_BLOCK_COST = 4.633`；若整体改 tick 制，则应 `+ breakTicks`（并加 Baritone 的 `blockBreakAdditionalPenalty = 2`）。
- **放置成本反例更正**：4.0 与 Baritone 20 属不同单位，相对值 4.0× vs 4.32× 走路，**基本对齐**；只需修正错引注释。

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

### 2.8 已修：残留探针（本次清理）

- `AStarMovementSearch.java:138-141` 的 `[PlanProbe]` 临时探针仍在生产代码中（审计 B 区发现），
  已删除并编译通过；这是 D-036/测试规则"探针验证后立即删除"的违规实例。

### 2.9 已对齐（第一刀，D-037，2026-09-09）

| 项 | 改动 | Baritone 依据 | 状态 |
|---|---|---|---|
| 流体不可挖 | `BlockBreakSafety.explicitTargetRefusal` + `BlockInteraction.estimateBreakTicks` | `MovementHelper.java:588-590` | IMPLEMENTED + COMPILES |
| 放置校验结果 | `BlockInteraction.placeAt` 检查 `InteractionResult` + 服务器世界复核 | `BlockPlaceHelper.java:48-52` | IMPLEMENTED + COMPILES |
| 危险方块扩表 | `MovementHelper.avoidWalkingInto`（火/仙人掌/浆果丛/末地门/蛛网/气泡柱） | `MovementHelper.java:350-360` | IMPLEMENTED + COMPILES |
| 删除任意 BlockItem 兜底 | `BlockInteraction.findPlaceableSlot` 仅白名单 | `Settings.java:230-235` | IMPLEMENTED + COMPILES |
| D-031 文档修正 | 放置描述去掉"视线校验" | — | 已修正 |

验证夹具：`alice_test:fluid_course`（2 格高充水墙，判别性）+ `alice_test:lava_course`（封闭静止岩浆池） + `alice:pathing_fluid_guard`（期望 `UNREACHABLE`）。

---

## §3 分层对照表（并行审计结果）

> 四层审计已全部合并。
> 标记：✅ = 我已亲自复核行号/语义；⬜ = 来自审计 agent，待我复核。

### 3.D 世界交互（破坏/放置/世界视图）vs Baritone utils

> 注：本地 Baritone 树是 **1.21.4**（`gradle.properties:5,11`），Alice 是 1.20.1；行号仅对该树成立，语义需按 1.20.1 复核。

| 关注点 | Alice（文件:行） | Baritone（文件:行） | 差异实质 | 分类 | 复核 |
|---|---|---|---|---|---|
| **可规划破坏流体/岩浆** | `SurfaceMovementProvider.java:104-119`（阻挡物经 `canWalkThrough` 收集）+ `BlockInteraction.breakRefusal:206-211`（无流体判定）+ `BlockBreakSafety.clearingRefusal:39-52`（只挡脚下/保护区/不可破坏/黑曜石） | `MovementHelper.java:586-591`（`!state.getFluidState().isEmpty()` → COST_INF） | 岩浆被当作可挖阻挡物，`estimateBreakTicks` 给出有限代价（≈10000 tick）→ 规划可能选"挖岩浆"，执行必然卡到段超时 | DEVIATION-UNREGISTERED（**P0 安全**） | ✅ |
| **放置忽略 `InteractionResult`** | `BlockInteraction.java:182-186`（调用后直接 `return PlaceResult.PLACED`） | `BlockPlaceHelper.java:48-52`（检查 `InteractionResult.SUCCESS` 才 swing/返回） | 服务端拒绝也算"已放置" → 上层认为成功，随后重试到超时 | DEVIATION-UNREGISTERED（**P0 正确性**） | ✅ |
| **"任意 BlockItem"兜底** | `BlockInteraction.findPlaceableSlot:130-147`（白名单未命中即取第一个 BlockItem） | `Settings.java:230-235`（`acceptableThrowawayItems` 白名单） | 会用火把/花/告示牌"搭台阶" → 目标仍不可站 → 无限重试 | DEVIATION-UNREGISTERED（P1） | ✅ |
| 支撑面谓词过宽 + 文档矛盾 | `BlockInteraction.isSolidForPlacement:122-128`（任意非空碰撞）；`docs/AI_DECISIONS.md:243-244` 仍称有视线校验 | `MovementHelper.java:558-565`（整格碰撞或玻璃）+ `:824-830`（逐面射线校验） | 会在半砖/栅栏/箱子侧面放置；D-031 文档与实现不符 | DEVIATION-UNREGISTERED（P1 + 文档） | ✅ |
| 破坏安全无 `avoidBreaking` 等价物 | `BlockBreakSafety.java:39-52` | `MovementHelper.java:72-115`（冰→水、蛀虫、邻接流体/下落方块） | 会挖冰、蛀虫方块、邻接危险块 | MISSING-IN-ALICE（P1） | ⬜ |
| 破坏无流体/LOS 语义 | `BlockBreakSession.java:85-87`（仅距离） | `Movement.java:153-197`（LOS + 换目标 + UNREACHABLE） | 只查距离，无可见性 | DEVIATION-UNREGISTERED（P2） | ⬜ |
| 破坏会话无 STOP/ABORT 包 | `BlockBreakSession.java:92-113`（只发 START）+ `BreakAndTraverseExecution.cancel` | `BlockBreakHelper.java:43-50`（`resetBlockRemoving`） | 取消后客户端裂纹残留（B 区同项） | DEVIATION-UNREGISTERED（P1） | ✅ |
| 工具选择平手/耐久 | `BlockInteraction.java:95-109` | `ToolSet.java:139-182` | 无材料代价 tie-break、无将坏工具跳过 | DEVIATION-UNREGISTERED（P2） | ⬜ |
| 破坏 tick 估算公式 | `BlockInteraction.java:222-242` | `ToolSet.java:207-239` | 数学等价（可收获 30h/s、不可收获 100h/s） | ALIGNED | ⬜ |
| 放置面顺序 | `BlockInteraction.java:34-35` | `Movement.java:36` | N/S/E/W/DOWN 一致 | ALIGNED | ✅ |
| 放置直放分支 / 放置节奏 | `BlockInteraction.java:157-188`（无直放、无间隔） | `MovementHelper.java:791-796`、`BlockPlaceHelper.java:47`（`rightClickSpeed=4`） | 缺"可替换方块直放"与放置间隔 | MISSING-IN-ALICE（P2） | ⬜ |
| 世界视图快照 / 未加载区块 | `WorldView.java:7-15`（零实现零调用）；`pathing/` 全包 `isLoaded|hasChunk|getChunk` 0 命中 | `BlockStateInterface.java:79,97,142-165` | 无快照、无"未加载"概念 | MISSING-IN-ALICE（P2/R7） | ✅ |
| `isReplaceable` 缺失 | `SurfaceMovementProvider.java:148` 用 `canWalkThrough` 代替 | `MovementHelper.java:298-326` | 可能计划往水里放方块 | MISSING-IN-ALICE（P2） | ⬜ |
| 开门 | 无（`grep openDoors` 0 命中） | `MovementHelper.java:773-790` | 撞门 | MISSING-IN-ALICE（P2） | ⬜ |
| `canSweepPlayer` 自制扫掠 | `MovementHelper.java:143-182` | 无对应物（用双邻判定） | Alice 更严，未登记 | DEVIATION-UNREGISTERED（P2） | ⬜ |
| 批量破坏瞬毁 | `BlockInteraction.java:255-257` | 无对应物 | 调用方已保护（`RoadBuilder`/`BotCommand`） | DEVIATION-JUSTIFIED（D-031） | ⬜ |
| 第二套破坏原语 | `BotMiner.java:374-396,683-690` | 唯一一套 | 违反 D-031"唯一入口" | DEVIATION-UNREGISTERED（P1） | ⬜ |

### 3.A 搜索内核（R3）vs Baritone A*

> 注：本基线 Baritone **没有** `MovementHelper.generateMovements`；候选生成是 `Moves.values()`（`AStarPathFinder.java:102`）+ `Moves.apply`（`:119`）。

| 关注点 | Alice（文件:行） | Baritone（文件:行） | 差异实质 | 分类 | 复核 |
|---|---|---|---|---|---|
| 出堆判目标 / 节点哈希 / 开放集 / 取消语义 / closed 重开 | `AStarMovementSearch.java:76-83,100-104`、`SearchNode.java:43-49`、`BinaryHeapOpenSet.java:23-100` | `AStarPathFinder.java:95-101,171-175`、`BetterBlockPos.java:99-103`、`openset/BinaryHeapOpenSet.java:60-131` | 语义等价 | ALIGNED | ⬜ |
| **启发式公式与可采纳性** | `GoalFoot.java:19-23`（欧氏 + 对称 \|dy\|）；`CostModel.java:17` 声称可采纳 | `GoalBlock.java:108-118`、`GoalXZ.java:114-115`（octile × costHeuristic）、`GoalYLevel.java:49-59`（降 3.894 / 升 3.163 非对称） | **h 高估 → 不可采纳**（反例：p=(0,64,0)→goal=(1,63,0)，h(p)=2.0 而 DESCEND 成本 1.0） | DEVIATION-UNREGISTERED（高） | ✅ |
| **best-so-far 部分路径被丢弃** | `AStarMovementSearch.java:116-124`、`PathPlan.java:43-49`（失败时 movements 全空，bestSoFar 仅进诊断串） | `AbstractNodeCostSearch.java:203-213`（`MIN_DIST_PATH` ≥5 就返回前缀）、`:126-130`（SUCCESS_TO_GOAL / SUCCESS_SEGMENT） | 预算耗尽时已找到的可行前缀被整条丢弃，重规划只能从零开始 | DEVIATION-UNREGISTERED（高） | ⬜ |
| **无已加载区块/世界边界门控** | `MovementContext.java:39-41` 只有 `yInBounds` | `AStarPathFinder.java:83,105-111`（`isLoaded` + `numEmptyChunk < pathingMaxChunkBorderFetch`）、`:112`（worldBorder） | Alice 会朝未加载区块扩展（服务端读方块会同步加载/生成区块） | MISSING-IN-ALICE（高） | ✅ |
| 双超时（主 500ms / 失败 2000ms） | `SearchBudget.java:23-29`、`CorePathPlanner.java:14-15`（单墙钟 3000ms） | `AStarPathFinder.java:72-73,86`、`Settings.java:598,603` | 无主/失败双阶段预算 | DEVIATION-UNREGISTERED（中） | ⬜ |
| 时间检查粒度 | `AStarMovementSearch.java:70,161-163`（每节点） | `AStarPathFinder.java:79,84-89`（每 64 节点） | 性能差异 | DEVIATION-UNREGISTERED（低） | ⬜ |
| 改进阈值 | `AStarMovementSearch.java:91`（>0） | `AStarPathFinder.java:167,178`、`AbstractNodeCostSearch.java:83`（>0.01） | 浮点重复重开 | DEVIATION-UNREGISTERED（低） | ⬜ |
| 路径裁剪 / favoring / isFinished | 未找到 | `AbstractNodeCostSearch.java:114-125,59,101-103`、`AStarPathFinder.java:161-164` | 缺失（长距离/复用场景） | MISSING-IN-ALICE（中/低） | ⬜ |
| 对角语义 | `SurfaceMovementProvider.java:38-42`、`MovementHelper.java:127-135`（同层、两侧全通） | `MovementDiagonal.java:196-218,220-263,265-267`（单侧绕角 optionA/B、对角升降） | 缺绕角与对角升降 | DEVIATION-UNREGISTERED（中） | ⬜ |
| ASCEND 语义 | `SurfaceMovementProvider.java:43-53`、`MovementHelper.java:192-208`（纯通行） | `MovementAscend.java:67-155`（含放台阶/挖头/jumpPenalty） | Alice 更保守（放台阶走独立类型） | DEVIATION-JUSTIFIED（D-033） | ⬜ |
| 放置/破坏成本 | `SurfaceMovementProvider.java:26,125,162` | `Settings.java:124`(20)、`MovementHelper.java:599-604`、`Settings.java:131`(2) | 4.0 vs 20；`breakTicks/20` 单位混用 | DEVIATION-UNREGISTERED（高） | ✅ |
| 目标类型 | `GoalSpec.java:10-21`、`GoalFoot.java:8-34` | `goals/GoalBlock/GoalTwoBlocks/GoalGetToBlock/GoalXZ/GoalYLevel/GoalComposite/GoalNear` | 只有精确脚位 | MISSING-IN-ALICE（中） | ⬜ |
| 状态语义 | `PlanningStatus.java:4-14` | `PathCalculationResult.java:48-54` | Alice 区分 SEARCH_LIMIT/UNREACHABLE/CANCELLED | DEVIATION-JUSTIFIED（D-004） | ⬜ |
| legacy 零启发 Dijkstra | `pathing/AStarPathfinder.java:118-120,240-247` | 单一 A* | 双内核 | DEVIATION-UNREGISTERED（低） | ⬜ |

### 3.B 执行会话（PathSession）vs PathExecutor

| 关注点 | Alice（文件:行） | Baritone（文件:行） | 差异实质 | 分类 | 复核 |
|---|---|---|---|---|---|
| 段间推进 | `PathSession.java:93-102,143` | `PathExecutor.java:231-236`、`onChangeInPathPosition():593-596` | 无条件 settle(10) + 每 tick stopMovement vs 同 tick 推进、只 clearKeys | DEVIATION-JUSTIFIED（D-036 待办） | ✅ |
| 段超时单位 | `PathSession.java:195-198` | `PathExecutor.java:243` + `movementTimeoutTicks=100` | Alice cost=格数 ×20；Baritone cost 已是 tick → 每格宽松约 4.6× | DEVIATION-UNREGISTERED | ✅ |
| **三层自制超时** | `BreakAndTraverseExecution.java:40`(400)、`PlaceStepAndTraverseExecution.java:35`(200)、`DescendExecution.java:32`(20)、`AscendExecution.java:96` | 单层 `PathExecutor.java:243` | 执行器内 + 会话 + 任务三层各自为政 | DEVIATION-UNREGISTERED | ✅ |
| 漂移判定 | `PathSession.java:229-241` | `PathExecutor.java:101-128` + `Movement.java:104-110` | 距离启发式 + onGround 门 vs 每 tick `getValidPositions().contains(feet)` | DEVIATION-JUSTIFIED（D-035/D-036 待改） | ✅ |
| 离路径距离 | 无 | `PathExecutor.java:51-52,129-145,256-269`；`MAX_TICKS_AWAY=200` | 无 `closestPathPos`/`ticksAway` | MISSING-IN-ALICE | ✅ |
| snipsnap 语义 | `PathSession.java:268-301` | `PathingBehavior.java:197`（拼接下一段）+ `PathExecutor.java:103-127`（段内重同步） | Alice 把"失败自愈"当 snipsnap，Baritone 是两件事 | DEVIATION-UNREGISTERED | ⬜ |
| 世界变化检测 | `PathSession.java:121-126,207-219` | `PathExecutor.java:195-219` + `Movement.java:93-96,244-251` | 无 `recalculateCost` / lookahead(5) / maxCostIncrease(10) / `calculatedWhileLoaded` | DEVIATION-UNREGISTERED | ✅ |
| `safeToCancel` 门控 | 无 | `PathExecutor.java:194,208,213` + `MovementTraverse.java:353-357` | Alice 无条件取消（空中/挖掘中同样取消） | DEVIATION-UNREGISTERED | ⬜ |
| **COLUMN 容差只有 Descend 生效** | `TraverseExecution.java:105`、`AscendExecution.java:148`、`DiagonalExecution.java:122`、`BreakAndTraverseExecution.java:187`、`PlaceStepAndTraverseExecution.java:171` 全部硬编码 0.3D | `MovementTraverse.java:253-258`、`MovementDescend.java:237` | 会话给中间段 COLUMN，但 5 个执行器忽略 tolerance → 违反 D-027 | DEVIATION-UNREGISTERED | ✅ |
| 死状态 | `PathSessionStatus.java:13,24`（BLOCKED / POSTCONDITION_FAILED 声明但 `mapFailure` 永不产生） | — | 死枚举 | DEVIATION-UNREGISTERED | ✅ |
| 重规划位置 | `PathSession.java:304-325`（会话内 MAX_REPLANS=2） | `PathingBehavior.java:154-193`（上层重算） | 重试/放弃属目标级决策却塞进执行器 | DEVIATION-UNREGISTERED | ⬜ |
| **取消不清破坏进度** | `BreakAndTraverseExecution.cancel():144-151`（只 stopMovement）+ `BlockBreakSession` 无 abort | `PathExecutor.java:603-608` → `BlockBreakHelper.java:43-50` | 客户端裂纹残留 | DEVIATION-UNREGISTERED | ✅ |
| sprint 门控 | 无（core/movement 下 `setSprinting` 0 命中） | `PathExecutor.java:238-241,345-488` | 从不疾跑（慢约 30%），成本却含冲刺系数 | MISSING-IN-ALICE | ✅ |
| 未加载区块暂停 | 无 | `PathExecutor.java:186-193` | 服务端读方块即加载，影响低 | MISSING-IN-ALICE（低） | ⬜ |
| 死参数 | `LiveExecutionContext.java:13-14` + `PathSession.java:176`（revision 恒 0） | — | 版本化失效检测未接线 | DEVIATION-UNREGISTERED | ✅ |
| **校验失败路径不受段超时覆盖** | `PathSession.java:104-113,178-181`（`execution==null` 时 `segmentTicks` 不递增） | 无（Baritone 直接 cancel 整条） | 只剩任务级 600 tick 兜底 | DEVIATION-UNREGISTERED | ✅ |
| 输入释放 | 各 Execution `stopMovement` | `Movement.java:139-148`、`PathExecutor.java:593-608` | 语义基本一致 | ALIGNED | ⬜ |

### 3.C Movement 原语 / 成本 / 候选生成

| 关注点 | Alice（文件:行） | Baritone（文件:行） | 差异实质 | 分类 | 复核 |
|---|---|---|---|---|---|
| Traverse 前置 | `core/TraverseExecutionFactory.java:34-38` | `movements/MovementTraverse.java:77-124` | 无 srcDown 检查、无破坏成本 | DEVIATION-UNREGISTERED | ⬜ |
| 规划谓词 vs 执行谓词 | 规划用 `canTraverse`（含 sweep，`MovementHelper.java:118-137`）；执行工厂用 `canWalkThrough` | 同一 `cost()` 函数同时用于规划与执行 | 执行校验不含 sweep → 可能"可规划不可执行" | DEVIATION-UNREGISTERED | ⬜ |
| Diagonal 前置/绕行 | `core/DiagonalExecutionFactory.java:44-59` | `MovementDiagonal.java:220-263`（optionA/B） | 无绕行成本 | DEVIATION-UNREGISTERED | ⬜ |
| Diagonal 几何 | `MovementSpec.java:63-67`（dy=0） | `MovementDiagonal.java:104-114`（dy±1） | 缺对角升降 | MISSING-IN-ALICE（需 D-024 裁决） | ⬜ |
| **Ascend 跳跃门控** | `core/AscendExecution.java:160-180` | `MovementAscend.java:204-230` + **`headBonkClear()` `:233-243`** | 缺头顶四向净空门控 | DEVIATION-UNREGISTERED | ✅ |
| Ascend 前置 | `core/AscendExecutionFactory.java:45-55` | `MovementAscend.java:96-131` | 缺 FallingBlock / climbable / bottom-slab | DEVIATION-UNREGISTERED | ⬜ |
| **`canWalkOn` 判定** | `MovementHelper.java:18-34`：碰撞形状非空即真 | `MovementHelper.java:387-426` **白名单**（`isBlockNormalCube` 且排除 MAGMA/BUBBLE/HONEY + ladder/farmland 等） | Alice 会接受栅栏/蜂蜜块等非整格方块 | DEVIATION-UNREGISTERED | ✅ |
| 破坏成本 | `SurfaceMovementProvider.java:124-125` `breakTicks/20` | `MovementHelper.java:586-615`（1/strVsBlock + penalty） | 单位混用（秒混进格标度） | DEVIATION-UNREGISTERED | ✅ |
| 放置成本 | `SurfaceMovementProvider.java:25-26,162`：4.0，注释引用不存在的 `PLACE_ONE_BLOCK_COST` | `CalculationContext.java:106` = `blockPlacementPenalty`，`Settings.java:124` 默认 20 | **相对值几乎对齐**（Alice 4.0/1.0 = 4.0×走路；Baritone 20/4.633 = 4.32×走路）；问题只是**注释错引** | 注释修正（非数值偏离） | ✅（我复核修正） |
| 候选集 | `SurfaceMovementProvider.java:33-71` | `Moves.java:31-317` | 缺 DOWNWARD / PILLAR / PARKOUR / FALL | MISSING-IN-ALICE（FALL/PARKOUR 属 D-024） | ⬜ |
| sprint | `TraverseExecution.java:126-127` 仅 forward | `MovementTraverse.java:269-271` + `SPRINT_MULTIPLIER` | 从不 sprint，但成本含冲刺系数 | DEVIATION-UNREGISTERED | ⬜ |
| `MovementSpec` 与工厂矛盾 | `MovementSpec.java:68-77` 允许对角 ASCEND/DESCEND；`AscendExecutionFactory.java:36`/`DescendExecutionFactory` 要求 `horizontalDist==1` | n/a | 契约自相矛盾（当前无调用方触发） | DEVIATION-UNREGISTERED（潜在） | ✅ |
| legacy 第二内核 | `movement/*`：裸 `setBlock`（`DescendMovement.java:142`、`PillarMovement.java:146`）+ 第二套成本表 | 只有一套 | 违反 D-031 唯一入口 | DEVIATION-UNREGISTERED | ⬜ |
| `MovementCapabilities` 声明未生效 | `core/MovementCapabilities.java:14,17-19` 无消费者 | n/a | 红线只在 `DescendExecutionFactory` 内联 | DEVIATION-UNREGISTERED | ⬜ |
| 完成判定/合法位置集/驱动旋转 | `MovementHelper.java:94-105`、D-026、`TraverseExecution.java:124-125` | 各 Movement | Alice 多 onGround/水平约束 | DEVIATION-JUSTIFIED（D-026/D-029） | ✅ |
| BREAK_AND_TRAVERSE / PLACE_STEP | `MovementSpec.java:78-92` | Baritone 无此类型（破坏/放置计入 traverse cost） | Alice 目标差异 | DEVIATION-JUSTIFIED（D-031/D-033） | ✅ |

---

## §3.9 对齐进度（2026-09-09）

| 批次 | 内容 | 状态 |
|---|---|---|
| D-035 | 段计时缺失（永不终止）+ 段内漂移检测 | `WINDOWS_CLIENT`（用户 2026-09-09 通过） |
| D-037 | 流体不可挖 / 放置校验 `InteractionResult` / 危险方块扩表 / 删任意 BlockItem 兜底 / D-031 文档 | `WINDOWS_CLIENT`（`fluid_course`+`lava_course` 通过） |
| D-038 | 驱动相位 END→START | **已撤回**（Forge 源码证明等价） |
| D-040 | 成本模型按实测标定（TRAVERSE 1.00/DIAGONAL 1.33/ASCEND 1.67/DESCEND 2.67）+ 启发式 octile/非对称竖向 + 破坏放置成本 + A* 系数 | `WINDOWS_CLIENT` + `USER_ACCEPTED`（路线翻转 PASS、电池 8/8、三场景 COMPLETED 且总 tick 不劣化） |
| D-041 A | `canWalkOn` 混合判定（整格 OR 白名单 OR 碰撞非空且非已知坏方块） | `WINDOWS_CLIENT`（`fence_course` UNREACHABLE PASS） |
| D-041 B | Ascend 前置：FallingBlock / climbable 拒绝 + 运行期头部守卫（bottom-slab 另立项） | 已实施；代码级验证 + ascend 回归 PASS |
| D-042 C | 执行器契约：COLUMN 容差 ×5 / 超时收敛单层 / 取消清理破坏进度 / 校验失败路径计时 | 已实施，待测（串联回归） |
| D-043 D | Q4 重规划下沉（`PathRetryRunner`）+ 封路夹具 `alice:pathing_waller` | 已实施，待测 |
| 待办 E | ⑤ 删 `WorldView` 空接口 / ⑥ legacy 收口 / ⑨ `canSweepPlayer` 对比 / Q1 FALL 裁决 | 未开始 |

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
| P0 | `canWalkOn` 改白名单（拒绝栅栏/蜂蜜块等非整格"可站"） | `pathing/MovementHelper.canWalkOn` | `MovementHelper.java:387-426` |
| P1 | Ascend 补 `headBonkClear` 门控 + factory 补 FallingBlock/climbable/bottom-slab 拒绝 | `AscendExecution.shouldJump`、`AscendExecutionFactory.validate` | `MovementAscend.java:96-131,219-243` |
| P1 | 放置成本 4.0 → 对齐 `blockPlacementPenalty`(20)；修正错引注释 | `SurfaceMovementProvider.java:25-26,162` | `Settings.java:124`、`CalculationContext.java:106` |
| P1 | 破坏成本统一 tick 单位（去掉 `/20`） | `SurfaceMovementProvider.java:124-125` | `MovementHelper.java:586-615` |
| P1 | 规划/执行谓词统一（执行工厂改调 `canTraverse`） | `TraverseExecutionFactory.validate`、`DiagonalExecutionFactory` | `MovementTraverse.java:76-172` |
| P1 | legacy 双内核收口（裸 setBlock + 第二套成本表） | `movement/*`、`PathExecutor`、`FollowTask` | D-031 唯一入口 |
| P2 | sprint 门控（成本含冲刺系数但从不冲刺） | `TraverseExecution`/`DiagonalExecution` | `MovementTraverse.java:269-271` |
| P2 | `MovementSpec` 与工厂几何校验矛盾修正 | `MovementSpec`/两个工厂 | 契约一致性 |
| P2 | `MovementCapabilities` 接入或删除 | `core/MovementCapabilities` | 声明式红线落地 |
| P1 | COLUMN 容差在 5 个执行器生效（不再硬编码 EXACT） | 5 个 `*Execution` postcondition | D-027 契约 |
| P1 | 超时收敛为单层（去掉执行器内 400/200/20 自制超时） | `BreakAndTraverseExecution`/`PlaceStepAndTraverseExecution`/`DescendExecution`/`AscendExecution` | `PathExecutor.java:243` |
| P1 | 取消时清理破坏进度（`BlockBreakSession.abort()`） | `BreakAndTraverseExecution.cancel`、`BlockBreakSession` | `PathExecutor.java:603-608` |
| P1 | `execution==null` 校验失败路径也计入段计时 | `PathSession.tick` | 段超时全覆盖 |
| P2 | 死状态/死参数清理（BLOCKED、POSTCONDITION_FAILED、revision） | `PathSessionStatus`、`LiveExecutionContext` | 代码卫生 |
| P2 | replan 决策下沉到任务层 | `PathSession`、`PathSessionDiagnosticTask` | `PathingBehavior.java:154-193` |
| P2 | `closestPathPos` + `ticksAway`（软 2/硬 3/200 tick） | `PathSession` | `PathExecutor.java:129-145,256-269` |
| P2 | 段内重同步（前向/后向搜索 + reset）与 snipsnap 拼接拆分 | `PathSession` | `PathExecutor.java:101-128` |
| P0 | 启发式改为可采纳（octile + 非对称竖向，或与成本模型同标定） | `GoalFoot.heuristic` | `GoalXZ.java:114-115`、`GoalYLevel.java:49-59` |
| P1 | 搜索加已加载区块门控 + XZ 世界边界 | `MovementContext`、`SurfaceMovementProvider`、`AStarMovementSearch` | `AStarPathFinder.java:83,105-112` |
| P1 | 预算耗尽时返回 best-so-far 前缀（`PARTIAL` 状态，不冒充不可达） | `AStarMovementSearch`、`PathPlan`、`PathSession` | `AbstractNodeCostSearch.java:203-213` |
| P2 | 双阶段预算（主/失败超时）+ 时间检查每 64 节点 + 改进阈值 0.01 | `SearchBudget`、`AStarMovementSearch` | `AStarPathFinder.java:72-86,167-178` |
| P2 | 目标类型补齐（GoalXZ/GoalYLevel/GoalTwoBlocks 等） | `GoalSpec` 实现类 | `goals/*` |
| P0 | **破坏拒绝流体**（岩浆/水不可作为清障目标） | `BlockInteraction.breakRefusal`、`BlockBreakSafety.clearingRefusal`、`SurfaceMovementProvider.appendBreakAndTraverse` | `MovementHelper.java:586-591` |
| P0 | **放置校验 `InteractionResult`**（不再假成功） | `BlockInteraction.placeAt` | `BlockPlaceHelper.java:48-52` |
| P1 | 删除"任意 BlockItem"兜底（无白名单方块即不可放置） | `BlockInteraction.findPlaceableSlot` | `Settings.java:230-235` |
| P1 | 破坏安全补 `avoidBreaking` 等价物（冰/蛀虫/邻接流体与下落方块） | `BlockBreakSafety.clearingRefusal` | `MovementHelper.java:72-115` |
| P1 | 支撑面改整格判定；同步修正 D-031 文档中的"视线校验"描述 | `BlockInteraction.isSolidForPlacement`、`AI_DECISIONS.md` | `MovementHelper.java:558-565` |
| P1 | `canWalkThrough` 补 Baritone 显式 NO 列表（蛛网/粉末雪/浆果丛/气泡柱/活板门等） | `pathing/MovementHelper.canWalkThrough` | `MovementHelper.java:144-192` |
| P2 | 工具选择 tie-break 与将坏工具跳过 | `BlockInteraction.findBestToolSlot` | `ToolSet.java:139-182` |
| P2 | `isReplaceable` / 开门 / 放置间隔 / 直放分支 | `MovementHelper`、`BlockInteraction` | `MovementHelper.java:298-326,773-796` |

---

## §5 存疑与未验证

- 成本对齐后的**路径偏好变化**需要客户端复测（可能改变既有场景的路线）。
- Baritone 的 `MovementParkour`/`MovementFall` 与 D-024 落差红线冲突，属需用户决策的目标差异。
- Alice 假人物理（D-025 maxUpStep 0.6、控制相位差 1 tick）导致 Baritone 的**常量**不可直接照抄，
  只抄语义与相对权重，数值需按 §2.1 方式实测标定。
