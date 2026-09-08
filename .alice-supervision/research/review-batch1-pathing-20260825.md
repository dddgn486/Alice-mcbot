# 批 1 审查报告：pathing 模块（HARD_PATH/SOFT_SURFACE 边界核心线）

- **任务**：`4226c8de-8a28-4e7a-98c0-ed83e8b4e149`（批 1 / pathing 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2/§2/§4/§5）、`review-batch1-bot-20260825.md`（前置 bot 审查，B2 根因候选指向本模块）、`docs/HANDOVER.md §八`（R26/R30/R31/R33/R35/R36）、`docs/PATHING_REFACTOR.md`（寻路与采集重构方案）。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（pathing/ 14 文件 + 消费方 task/action/bot/command/item 引用）、git grep 交叉引用、javap 反编译 Forge 1.20.1-47.4.10 mapped jar（`Player.travel`/`LivingEntity.travel`/`aiStep`）。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：pathing = "寻路与移动边界：曲面寻路只委托真实可通行曲面的 A*，不破坏或放置方块；移动语义边界区分 HARD_PATH/SOFT_SURFACE/SOFT_FLUID/FORCED_BUILD"（SurfacePathfinder.java:8-13、MovementMode.java:3-5）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `PathExecutor.java` | 执行器 | HARD_PATH 手动位置步进执行器（setPos 小步 + 段到达对齐 Y/onGround），`MODE` 常量固定 HARD_PATH（:20） |
| `SurfacePathfinder.java` | 门面 | 曲面寻路门面：委托 A*，结构化返回 REACHED/UNREACHABLE/SEARCH_LIMIT（`inconclusive()`=SEARCH_LIMIT，:53-55） |
| `AStarPathfinder.java` | 算法 | A* 核心（Baritone 移植精简版）：MAX_NODES=12000/MAX_MOVES=256/SEARCH_MARGIN=12，零启发式 Dijkstra 顺序 + 重开逻辑 |
| `SoftMovementPrimitive.java` | 原语 | SOFT_SURFACE 最小移动原语：`toward/applyToward/applyJumpToward/settle`，NATIVE_TRAVEL 后端逐 tick `travel(0,0,1)`（:48-53） |
| `MovementHelper.java` | 原语 | 移动判定（Baritone MovementHelper 移植）：canWalkOn/Through/Traverse/Ascend/Descend/Sweep、supportTopY、cost() |
| `Goal.java` | 数据 | 目标体系（Baritone Goal 子集）：GoalBlock（精确脚位）/GoalNear（水平范围+同层） |
| `PathNode.java` | 数据 | A* 节点（pos/cost/combinedCost/previous/moves/turns） |
| `OpenSet.java` | 算法 | A* 二叉堆（combinedCost 主序、turns 次序、坐标决胜） |
| `MovementMode.java` | 数据 | 移动语义枚举（HARD_PATH/SOFT_SURFACE/SOFT_FLUID/FORCED_BUILD） |
| `MovementType.java` | 数据 | 移动类型枚举（TRAVERSE/ASCEND/DESCEND/DOWNWARD，Baritone Moves 子集） |
| `TunnelPlan.java` | 数据 | 不可变数学通道计划（入口/曲线/出口/成本，TUNNEL_FACTOR=10） |
| `TunnelPlanner.java` | 规划器 | 独立通道规划器：仅当 `SurfaceFailureReport.confirmedUnreachable()`（全部 UNREACHABLE）才进入 |
| `TunnelObstaclePolicy.java` | 策略 | 通道候选保守硬验证（拒绝流体/保护区/不可破坏/高代价） |
| `PathingRegression.java` | 回归 | headless 成本模型回归套件（10 项断言：diagonal/ascend/descend/elevatedAlternative/对角侧阻塞/下降扫掠/直线择优/整块边缘/下半砖/无支撑拒绝） |

**注册方式**：纯服务端静态工具类/执行器，无 Forge 总线注册；被 task/action/bot/command/item 按需调用。

---

## 2.2 边界归属

- **归属线**：pathing 是 HARD_PATH 与 SOFT_SURFACE 的**核心分界线**（规划 §4.2 候选方向：寻路算法/软移动执行）。
  - HARD_PATH：PathExecutor（矿链/拾取固定，R30）；
  - SOFT_SURFACE：SoftMovementPrimitive/SoftPathProbeTask/FollowTask/SoftMoveProbeTask/SoftPathMineTask（独立实验，R33/R35/R36）；
  - 隧道：TunnelPlan/TunnelPlanner/TunnelObstaclePolicy（**未来线，已实现但未接入矿链**）。
- **相邻边界对照**：
  - MineTask/DropCollectionTask/RoadBuildTask/BotMiner：**grep 确认零 SOFT 引用**（无 SoftMovementPrimitive/NATIVE_TRAVEL/SELF_MOVE/SoftPath* 引用）；MineTask 经 BotMiner+PathExecutor（HARD_PATH）；DropCollectionTask 直接用 PathExecutor（:145-164）→ **R30 隔离确认**。
  - TunnelPlanner：唯一调用方证据是 DropCollectionTask 注释**明示"不会调用 TunnelPlanner"**（DropCollectionTask.java:25）；grep 全工程无实际调用方 → **隧道线未接入任何任务，保持未来线状态**。
  - MineTask 深埋目标 → `target_requires_tunnel`（MineTask.java:113），**不生成隧道**（注释 :16）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| P1 | A* 零启发式保证成本最优（对角同价→曼哈顿高估） | `combinedCost=0`（AStarPathfinder.java:66-68）+ 重开逻辑（:160-186）+ OpenSet 按 combinedCost 排序 → 实为 Dijkstra 顺序 | 静态核对 | **一致**（注释与实现吻合）；PATHING_REFACTOR.md 第四阶段明确记录该设计 |
| P2 | MAX_NODES/MAX_MOVES 超限 → SEARCH_LIMIT | `closed.size()>MAX_NODES \|\| moveLimitReached → SEARCH_LIMIT`（AStarPathfinder.java:90-91） | 静态核对 | **一致**：三态返回（REACHED/UNREACHABLE/SEARCH_LIMIT） |
| P3 | SEARCH_LIMIT ≠ UNREACHABLE | SurfacePathfinder `inconclusive()` 独立方法（:53-55）；消费方分别映射 `*_search_limit` 失败码 | 静态核对 | **一致**（见 2.4 R26） |
| P4 | PathExecutor 固定 HARD_PATH，不可切换 | `public static final MovementMode MODE = MovementMode.HARD_PATH`（PathExecutor.java:20）；grep 全工程 MovementMode 仅此一处引用 | 静态核对 | **一致**：无任何代码可切换模式（R30） |
| P5 | NATIVE_TRAVEL 逐 tick travel 驱动 | `setJumping(false); xxa=0; zza=1; bot.travel(new Vec3(0,0,1))`（SoftMovementPrimitive.java:48-53）；消费方 SoftPathProbeTask:141-144/FollowTask:106-107/SoftPathMineTask:167-170/SoftMoveProbeTask:119 | 静态核对 | **一致**（R33 实现）；**与 bot 物理失效关联见 2.3-P6/B2 特别核查** |
| P6 | settle 不伪造 onGround | `xxa=0; zza=0; bot.travel(Vec3.ZERO)`（SoftMovementPrimitive.java:82-86）；SoftPathProbeTask 仅当 `supported && bot.onGround()` 完成段（:198-211），30 tick 超时 → `soft_path_unsettled`（:214-224） | 静态核对 | **一致**（R35） |
| P7 | MovementHelper 支持半格支撑（下半砖/台阶） | `supportTopY` 用 `VoxelShape.max(Y)` 非整数 Y（MovementHelper.java:90-97）；isStandingAtFootPos epsilon=0.08（:73-87）；PathingRegression halfSlab 断言（:89-98） | 静态核对 + 服务端可证 | **一致**（R35） |
| P8 | 成本模型固定（水平=1/上阶=2/下阶=1/原地下=3） | `cost(MovementType)`（MovementHelper.java:222-229）；PathingRegression assertPath 断言成本（diagonal=2/ascend=2/descend=1/elevated=3） | 服务端可证 | **一致**（PATHING_REFACTOR.md 第四阶段记录） |
| P9 | 对角必须两侧格可过 + 玩家扫掠 | `canTraverse` 对角侧检查（MovementHelper.java:109-117）+ `canSweepPlayer`（:125-164） | 静态核对 | **一致**；PathingRegression diagonalBlocked/descentSweepBlocked 断言（:44-63） |
| P10 | 隧道仅在曲面确认不可达时规划 | `confirmedUnreachable()` = 非空且全部 UNREACHABLE（TunnelPlanner.java:73-76）；SEARCH_LIMIT 不满足 → `surface_path_inconclusive` 拒绝（:20-21） | 静态核对 | **一致**（R26 隧道侧）；但**该规划器当前零调用方**（见 2.2） |

---

## 2.4 已知坑与 R# 对照

| R# | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| R26 | SEARCH_LIMIT 仅表示预算不能证明可达性，不能用作通道授权；默认停止并报告 | ✅ **遵守** | AStarPathfinder 三态（:90-91）；SurfacePathfinder `inconclusive()` 独立（:53-55）；TunnelPlanner `confirmedUnreachable()` 要求**全部 UNREACHABLE**（:73-76）→ SEARCH_LIMIT 不授权隧道；消费方均映射独立失败码（FollowTask:135 `follow_search_limit`、SoftPathProbeTask:84/315、SoftPathMineTask:104/299、TransferTask:86 `HARD_PATH_SEARCH_LIMIT`、BotMiner:234 standSearchLimit、BotCommand:396）——**无任何"SEARCH_LIMIT→通道授权"路径** |
| R30 | PathExecutor 固定 HARD_PATH，普通挖矿/拾取不可切换 | ✅ **遵守** | PathExecutor.MODE static final（:20）；MovementMode 全工程唯一引用点；MineTask/DropCollectionTask/BotMiner 零 SOFT 引用（grep 确认） |
| R31 | 软移动/软寻路优先借鉴成熟实现，不从零重写核心；`setPos` 不能伪装软移动 | ✅ **遵守（实现侧）** | AStarPathfinder/OpenSet/PathNode/Goal/MovementHelper 均标注"移植自 Baritone"；SoftMovementPrimitive 用 travel 不用 setPos；PATHING_REFACTOR.md "成熟方案复用原则"段明确 Baritone movement primitive + Carpet action pack 方向。**注意**：`setPos` 在 PathExecutor（HARD_PATH）仍在使用——但 R31 原文指"软移动不能伪装"，HARD_PATH 的 setPos 是既有稳定模式（PATHING_REFACTOR.md "setPos 的 HARD_PATH 只保留为当前稳定兼容模式"）→ 不违反 |
| R33 | NATIVE_TRAVEL 已验平地+单格高差；任务逐 tick 显式调 travel | ✅ **遵守（代码一致）** | SoftMovementPrimitive.java:48-53；SoftPathProbeTask:141-144。**风险提示**：R33 验证范围=平地+单格高差，**未含实体交互（推挤/击退）**——与 bot 审查 B2 关联（见 2.3 特别核查），待监督员确认是否需更新 R33 验证边界表述 |
| R35 | 落地需真实支撑顶面；跨障后零输入 travel 结算；30 tick 未稳 → soft_probe_unsettled | ✅ **遵守** | MovementHelper.supportTopY（:90-97）；SoftMovementPrimitive.settle（:82-86）；SoftPathProbeTask:198-225 |
| R36 | FollowTask 独立、可开关、与保护区解耦 | ✅ **遵守** | FollowTask 只跟随同维度在线玩家（:21-25），FOLLOW_DISTANCE=2/REPLAN_INTERVAL=10/MAX_TARGET_DISTANCE=24/MAX_SETTLE_TICKS=30（:18-25）；BotManager.stopFollow/assignFollow 独立入口（bot 审查已确认）；类注释明示独立 |

**边界漂移待监督员判定**：
- **D1**：R33"已验"表述 vs bot 物理 T4 FAIL——NATIVE_TRAVEL 的实体交互（推挤/击退）从未被验证，R33 的"已通过客户端测试"范围应明确限定为"平地+单格高差"，避免被误读为"物理完整"。
- **D2**：SoftMovementPrimitive 的 `applyToward` 默认 3 参重载固定 `Backend.SELF_MOVE`（SoftMovementPrimitive.java:89-91）——与 R33"金斧普通右键默认 NATIVE_TRAVEL"表述存在潜在混淆（实际由 SoftMoveSelector/BotCommand 显式传 NATIVE_TRAVEL，默认重载仅兜底）；**待监督员确认默认后端语义是否需显式化**。

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- A* 成本与路径（PathingRegression 10 项断言，服务端 PASS 已确认）；
- SEARCH_LIMIT/UNREACHABLE 三态映射（各 task 失败码可 fixture 断言）；
- PathExecutor HARD_PATH 段推进/受阻重规划（NO_PROGRESS_LIMIT=80，:26,99-105）；
- MovementHelper 支撑顶面/扫掠判定（PathingRegression halfSlab/fullBlockEdge/unsupportedLanding）。

**需客户端实测（Windows）**：
- NATIVE_TRAVEL 软移动的物理可见性（真实位移/跳跃轨迹/落地动画）；
- **实体交互（推挤/击退）**：bot 被玩家推挤/击退的客户端可见位移——**当前零客户端证据（T4 FAIL）**；
- 软路径上/下台阶、混合段、受阻净空、半格支撑（PATHING_REFACTOR.md：CLIENT_TEST_PENDING）；
- FollowTask 持续运动/重算/失败边界（R36 自述"尚未获得客户端持续运动、重算和失败边界验证"）。

> **必须写明**：服务端 PASS ≠ 客户端正确。bot 审查已记录 ab510fd 教训（服务端静态推断被客户端实测否定）；pathing 的 PathingRegression PASS 只证明**成本模型与几何判定**，**不证明假人物理行为**。按 evidence-collection-standard：凡涉及"服务端成本 PASS + 客户端物理 FAIL"的组合，必须补双端证据，不得以 A* 正确性覆盖实体物理缺失。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| A* 成本模型（10 项 PathingRegression） | selftest 内 `pathingRegressionPass`（BotSelftest.java:344） | 服务端 PASS（fixture） | 无客户端需求（算法纯服务端） |
| HARD_PATH 矿链隔离（R30） | 65f4863 retest T3（MineTask 日志无 soft 关键字）+ grep 零引用 | USER_ACCEPTED（客户端记录，T3）+ 静态核对 | — |
| NATIVE_TRAVEL 平地+单格高差 | 65f4863 retest T1-T2 + P1 观测（软寻路/挖掘） | USER_ACCEPTED（客户端记录，T1-T3） | — |
| NATIVE_TRAVEL 实体交互（推挤/击退） | 65f4863 retest T4（bot 穿玩家+无击退） | CLIENT_TEST_FAILED（现象级） | **服务端零断言**（无 isPushable/push 后 delta/knockback 后 delta fixture） |
| 软路径上/下台阶、混合段、半格支撑 | PATHING_REFACTOR.md 自述 | CLIENT_TEST_PENDING | 需 Windows 矩阵 |
| 隧道线（TunnelPlanner/Policy） | 代码完整 + 零调用方 | 静态核对 | 无任何运行/验收证据（未来线） |
| FollowTask | R36 自述未客户端验证 | CLIENT_TEST_PENDING | 需 Windows 矩阵 |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| SoftMovementPrimitive NATIVE_TRAVEL（bot 物理失效关联） | **B 修复（候选，修改权归修复主线）** | bot 审查 B2 交叉验证指向本文件 :48-53；T4 客户端 FAIL；本报告修正 B2 表述（见特别核查）——travel 是"叠加输入+摩擦衰减"非"覆盖"，需服务端断言定案 |
| PathExecutor（HARD_PATH） | **D 维持** | 与 R30 一致、隔离确认、T3 客户端隔离验证通过 |
| SurfacePathfinder/AStarPathfinder/OpenSet/PathNode/Goal/MovementHelper | **D 维持（算法）** | 成本模型与 R26 语义一致、PathingRegression 服务端 PASS；替换候选见 2.8 |
| TunnelPlan/TunnelPlanner/TunnelObstaclePolicy | **A 补测试（未来线）** | 代码完整但零调用方、零运行证据；未接入矿链 = 无风险但无验证 |
| PathingRegression | **A 补测试（缺口）** | 缺"假人物理交互"断言（isPushable/push/knockback delta）——补测试后可为 bot 物理失效提供服务端证据 |
| 停止条件 | 未触发 | 未发现崩溃/死锁级 bug；未需改 R#/冻结边界才能下结论；未与 p1 线冲突（只提交证据） |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向 A：寻路算法（自研 A* vs 成熟库）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 自研 A* 已标注"移植自 Baritone AStarPathFinder 精简版"（类注释）；Baritone 有 1.20.1 Forge 发布物 + LGPL（补充调查已固定） | 自研版与 Baritone 完整版的能力差距评估（break-aware/成本模型/性能） |
| M2 Forge 1.20.1 兼容 | 自研服务端直读版天然兼容；Baritone 是 client-side input 驱动（非服务端假人） | 成熟库在服务端 ServerPlayer 场景的可用性 |
| M3 集成成本 | 自研被 8+ 消费方引用；替换需重写调用面 | 引用面完整清单 |
| M4 客户端风险 | 寻路算法本身无客户端可见行为（客户端风险在移动执行层非算法层） | — |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**方向 B：软移动执行（自研 travel 注入 vs 成熟 input-override）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | R31 已记录"Baritone movement primitive + Carpet action pack"方向；PATHING_REFACTOR.md 明确"不从零重写核心运动" | 成熟方案在 Forge 1.20.1 ServerPlayer 上的适配证据 |
| M2 Forge 1.20.1 兼容 | `travel(Vec3)`/`xxa`/`zza` 映射已确认可用；`ServerPlayer.tick()` 不自然消费 fake player 输入（R33） | "输入注入边界"的正式验证（R31 明示待确认项） |
| M3 集成成本 | 改动 SoftMovementPrimitive/BotPlayer.tick 驱动方式 | 波及全部软移动消费方（5 task + 2 selector） |
| M4 客户端风险 | **当前 T4 客户端 FAIL（bot 穿玩家/无击退）** | 修复后客户端矩阵（推挤/击退/穿墙/玩家跳跃回归） |
| M5 过渡成本 | 单实现切换 | 回滚验证方案 |

**替换决策点所需证据清单**（§4.3）：① 交叉矩阵：寻路×挖掘（✅T3 隔离）、SOFT_SURFACE×碰撞（⬜ 零证据/❌T4 FAIL）、隧道×流体（⬜ 零调用方）；② M1-M5 对照表（方向 A 证据较足、方向 B 需先做 Forge 输入注入边界实测）；③ R# 冲突面（方向 B 若改 travel 驱动需同步更新 R33/R35 表述）；④ 过渡/回滚方案；⑤ 新 Windows 矩阵草案（推挤/击退/软移动物理可见性）。

> 只列方向不选型；替换提案需监督员审核 + 用户批准。

---

## 特别核查项结论

### ① B2 交叉验证：SoftMovementPrimitive 每 tick travel 是否覆盖击退/推挤 delta（bot 审查根因候选）

**代码事实（已确认）**：
- `SoftMovementPrimitive.applyToward` NATIVE_TRAVEL 分支：`setJumping(false); xxa=0; zza=1; bot.travel(new Vec3(0,0,1))`（SoftMovementPrimitive.java:48-53）；SoftPathProbeTask 每 tick 调用（:141-144）。
- javap 反编译 `LivingEntity.travel`：`getDeltaMovement`（字节码 27）→ `moveRelative`（343）→ `getDeltaMovement`（351）→ `move(MoverType.SELF)`（354）→ 多处 `setDeltaMovement`（414/433/577/596/613/639）→ 摩擦缩放（610 scale 0.91/0.98 类）；`Player.travel` override 先处理游泳/乘骑再 super（Player.travel 字节码 4-236）。

**B2 表述修正（相对 bot 审查）**：bot 审查的"travel 覆盖击退 delta"**应修正为"叠加+衰减"**——`moveRelative` 是**增量叠加输入**到现有 deltaMovement（非清零覆盖），`move` 应用位移，随后 `setDeltaMovement` 按摩擦衰减。因此：
- 击退/推挤写入的 delta 在**下一次 travel 的 move 中仍会贡献位移**（不完全被抹掉）；
- 真正"无位移"更可能由**hurt/knockback 未触发**（isPushable/hurt 链）、**bot tick 链未完整运行**（bot 审查 B3：NPE 跳过 aiStep/pushEntities）或**客户端未收到位置包**（FakeConnection 同步）配合产生——**B2 从"唯一根因候选"降级为"影响因素之一"**，需服务端断言区分。

**服务端断言 fixture 方案（建议，供修复主线/监督员采纳，本审查不实施）**：
1. **F1 hurt→knockback→delta**：对 bot 调 `bot.hurt(damageSource, 1.0f)` 或直接 `bot.knockback(0.4, dx, dz)`，断言 `getDeltaMovement()` 非零（证明 hurt 链触发）；
2. **F2 travel 后位移保留**：F1 后跑 1 tick 任务层 `travel(0,0,1)`，断言位置变化包含击退分量（区分"travel 吞掉" vs "travel 保留"）；
3. **F3 push 链**：`bot.push(实体)` 后断言 deltaMovement 变化 + 位移（证明 push 写入路径）；
4. **F4 tick 链完整性**：断言 bot 的 `horizontalCollision/verticalCollisionBelow` 在推挤/撞击后变化（证明 aiStep/pushEntities 在运行）；若恒 false → 指向 bot tick 链 NPE（bot 审查 B3）；
5. **F5 时序对照**：同一 tick 内"hurt 后立即 travel" vs "hurt 后延迟 1 tick travel" 位移对比（定位时序覆盖）；
6. **F6 版本对照**：回退 `19af345`（P1 前）跑 F1-F3，确认 bot 物理失效引入点（progress-snapshot 下一步建议）。

### ② PathExecutor 是否可被切到非 HARD_PATH 模式（R30）

**结论：不可切换，R30 遵守**。`PathExecutor.MODE` 是 `public static final`（:20），无 setter；grep 全工程 `MovementMode` 仅 PathExecutor.java:20 一处引用；MovementMode 注释明示"其他模式必须由专用执行器实现"（MovementMode.java:4）；MineTask/DropCollectionTask/BotMiner 零 SOFT 引用。**无任何代码路径可将矿链切到 SOFT**。

### ③ SEARCH_LIMIT 耗尽是否仅"停止并报告"（R26）

**结论：遵守**。AStarPathfinder 三态分离（:90-91）；SurfacePathfinder `inconclusive()` 与 `reachable()` 独立（:49-55）；TunnelPlanner 仅在 `confirmedUnreachable()`（**全部 UNREACHABLE**）时授权隧道（:73-76），SEARCH_LIMIT → `surface_path_inconclusive` 拒绝（:20-21）；8 个消费方各自映射 `*_search_limit` 失败码（见 2.4 R26）——**不存在"SEARCH_LIMIT→通道授权/扩大预算"自动路径**（预算扩大仅由上层决策，PATHING_REFACTOR.md 明示）。

### ④ TunnelPlanner/TunnelPlan 实现状态（未来线）

**结论：代码已完整实现（三件套），但未接入任何任务，属未来线待评估**。
- TunnelPlan：不可变数学计划（record + 不可变列表 + 成本校验，:11-52）；
- TunnelPlanner：规划器完整（候选遍历/曲面连接/成本 = 曲面段 + 隧道×10 因子，:17-58）；
- TunnelObstaclePolicy：保守硬验证（流体/保护区/不可破坏/高代价拒绝，:16-56）；
- **零调用方**：grep 确认无任何代码调用 TunnelPlanner.plan；DropCollectionTask 注释明示"不会调用 TunnelPlanner"（:25）；MineTask 深埋 → `target_requires_tunnel` 失败码（:113），不生成隧道；
- **R26 守护**：规划器自身已内置"仅 confirmedUnreachable 授权"（:19-21,73-76）——未来接入时该守护已就位；
- **风险**：零运行证据（无 fixture/无客户端测试）；接入矿链属未来决策，需用户批准（规划 §4.2 隧道方向标注 TunnelPlanner 属未实现/未接入）。

---

## 与 active plan 冲突声明

- pathing 的 SoftMovementPrimitive/travel 驱动与 P1 客户端同步修复线（p1-client-sync-fix-v1）**交叉于 bot 物理问题**（bot 审查 B2/B3）。按规划 §5.3/§5.4：该线实态 **CLIENT_TEST_FAILED**；本审查**只提交证据（F1-F6 断言方案），不派发修复、不改 SoftMovementPrimitive/travel 驱动**；修改权归修复主线，证据可被监督员采纳进该线调查计划（须写入 active plan Research Decision）。

---

## 证据分级汇总

- **已确认事实**：14 文件职责与实现（行号如上）；MovementMode 唯一引用（R30 不可切换）；SEARCH_LIMIT 三态 + 8 消费方映射（R26 无授权路径）；矿链零 SOFT 引用（隔离）；TunnelPlanner 零调用方（未来线）；travel 反编译链（moveRelative 叠加 + move + 摩擦衰减）；PathingRegression 10 项成本断言存在。
- **架构推论**：B2 修正（travel 叠加+衰减，非覆盖；"无位移"需 hurt 未触发/B3 tick 链/同步缺失配合）；R33 验证边界需收窄表述（D1）；默认后端语义需显式化（D2）。
- **待调查**：bot 物理失效根因定案（需 F1-F6 服务端断言 + 回退 19af345 验证）；软路径上/下台阶客户端验证；FollowTask 客户端验证；隧道线接入决策。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、修复派发均需监督员审核 + 用户批准。
