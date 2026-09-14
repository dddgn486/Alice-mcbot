# 未收口项总账（2026-09-12 整理）

> **用途**：把散在 6+ 份勘测/审计报告里的"还没做完 / 还没验证 / 报告已过期"集中到一处，
> 每条给出来源、**当前复核证据**、**验证判据**、触发条件。避免"半年后重新发明或永久遗忘"。
> **规则**：只登记**可行动**的项；已完成的老条目不再保留（历史见 git log 与 `AI_DECISIONS.md`）。
> **整理时的复核基线**：`HEAD = e7f1a87`（J8 收尾闭合后）。标注「**复核**」的条目由本轮逐条 grep/打开源码二次确认。

**来源报告**（本总账的输入）：
`R4_BARITONE_ALIGNMENT_AUDIT.md`、`ALIGNMENT_OPEN_QUESTIONS.md`、`reference/BARITONE_PORTING_CHECKLIST.md`、
`RISK_SYSTEM_ISSUE_LIST.md`、`RISK_SYSTEM_DESIGN_DRAFT.md`、`RISK_SYSTEM_REVIEW_20260910.md`、
`RISK_MODES_DISCUSSION.md`、`JOB_LAYER_DESIGN.md`、`WORLD_WRITE_AUTHORIZATION.md`、
`MULTI_BOT_INTERFACE_RESERVATION.md`、`MINE_MIGRATION_DESIGN.md`。

---

## §0 一览

| 组 | 项数 | 影响业务？ | 备注 |
|---|---|---|---|
| §1 安全底座（风险 / 维生 / 写入准入） | 8 | ✅ 3 项（S-1/S-2/S-4），常驻任务后上升 | 风险清单 9 条断言**至今全部成立**，P0/P1/P2 **无一实施**，切片 S1–S6 **0/6** |
| §2 内核搜索正确性 | 7 | ✅ 2 项（K-1/K-2） | 审计 64 项里剩的最硬的两条；S-2 与 K（未加载区块）是**同一件事** |
| §3 Job 层与决策缝契约 | 9 | ✅ 4 项（J-1/J-2/J-3/J-9） | 直接决定"能不能接 LLM 决策层"（项目立项差异②） |
| §4 世界写入授权登记缺口 | 8 | ⚠️ 登记债 | G1/G2/G9 已修；其余 + 新发现 1 项 |
| §5 验证债 | 5 | ⚠️ 证据可信度 | `AI_TEST_MATRIX.md` 里有 **16 处「待测」**，其中若干已被后续 D-0xx 取代（需逐条核对） |
| §6 文档债 | 6 | ⚠️ 误导风险 | 三份审计报告的**包路径/常量/禁令**都已过期 |

---

## §1 安全底座（风险 / 维生 / 写入准入）

> 复核结论：`RISK_SYSTEM_REVIEW_20260910.md` 的 9 条断言**全部属实且至今未修**；
> 该复核对草案只有**一处反对意见**（§3.7「未知要计价」不适用于未加载区块，应**硬拒**）。
> 执行顺序建议：`S1` → `P1-A`（原在 S5，复核要求提前）→ `S2`/`S3` → `S4` → `S5` → `S6`。

| # | 项 | 来源 | 现状（复核证据） | 验证判据 |
|---|---|---|---|---|
| **S-1** | **维生否决后没有出口**（P1-C） | `ISSUE_LIST.md:297-348` | **✅ `WINDOWS_CLIENT`（D-132 附注二：14:47 两次复现：逃生出口 → `start_escape` → `SurvivalExitTask COMPLETED`，`segment_done ticks=5` 真的走出方块）**：`SurvivalSystem.nearestSafeRefuge`（纯查询）+ `SurvivalExitTask`（复用已验收 WalkTo）+ `SurvivalExit` 豁免（防每 tick 自杀循环）；入口 `alice:survival_exit_check` + 场景 `alice_test:survival_course` | 被岩浆/火包围 ⇒ 中断后应有一次"到最近安全点"的动作（`WalkToTask`），而不是停在原地被烧。**J8 常驻后优先级上升最多** |
| **S-2** | **执行期没有"未加载区块/世界边界"准入**（P1-A + 审计 §3.A:181） | `ISSUE_LIST.md:202-252`、`R4_AUDIT.md:181` | **已实施（D-132；A/B `WINDOWS_CLIENT`：`GOAL_NOT_LOADED`+`no_sync_load=true` / `near_goal=REACHED`；C 待复测）**：`MovementContext.chunkLoaded/withinWorldBorder` + 新状态 `GOAL_NOT_LOADED` + 跨区块节点门控（照 Baritone `AStarPathFinder:105-112`）；入口 `alice:chunk_guard_check` | 目标落在未加载区块 ⇒ **硬拒**（复核 §2：`getBlockState` 会**同步加载/生成区块并阻塞主线程**，不是 void air）。这也是 D-004/D-076「`SEARCH_LIMIT ≠ UNREACHABLE`」判据的前提 |
| **S-3** | `MineTask` **重复调用** `SurvivalSystem.tick`（P1-B） | `ISSUE_LIST.md:254-296` | **已实施（D-132，客户端间接确认：维生终态只由会话记一次）**：删掉 `MineTask.tick` 里那次调用（含 import），维生一律由会话统一记 `SURVIVAL_INTERRUPTED` | 删掉 `MineTask` 里那次（对齐 `FollowTask`/`Job`）；`lastTaskResult = "failed:"+reason` 已带原因码，信息不丢 |
| **S-4** | `FluidRiskPolicy` **零调用**（P0-C） | `ISSUE_LIST.md:151-200` | **✅ `WINDOWS_CLIENT`（D-132 附注：`[FluidMineCheck] SUMMARY … → PASS`）**：`MiningPlanner.plan` 目标确认接线 + `MineTask` 硬拒优先（不许再加高/清障）；入口 `alice:fluid_mine_check` + 场景 `alice_test:fluid_mine_course` | 探针已写好，只差接线；伐木/挖矿循环持续"挖穿未知方块"⇒ 邻格岩浆流入场景被反复暴露 |
| **S-5** | **可回收性 = 空实现**（P0-B）＝项目差异① | `ISSUE_LIST.md:94-150`、`REVIEW:73-90` | **未实现**（复核）：`SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED`/`NOT_REVERSIBLE` 三个值 **0 处读取**；唯一校验 `MovementSpec.java:42` 恒假；唯一转换点 `PlannedMovementSpecs.java:32-49` **两边都写死 `LOCAL_STEP`** ⇒ `0<0` 恒假 | 让"不是可回收步骤"真的能**改变搜索/准入结果**。⚠ 复核 §3 明确：接线必须**先 (a) 填真实等级，后 (b) 打开校验**，顺序反了会崩规划器 |
| **S-6** | `RiskSwitches` 是**全局静态状态**（P0-A） | `ISSUE_LIST.md:46-93` | **未实现**（复核）：`pathing/risk/RiskSwitches.java:20`（static volatile）+ 消费者直读全局（`SurfaceMovementProvider.java:401`、`DescendExecutionFactory.java:58`） | 按 D-046「先有消费者再建类型」：`RiskProfile` 冻结点（S1）的第一个真实消费者是 **Job 候选筛选**（`JOB_LAYER_DESIGN.md:374`）——需先裁定字段口径 |
| **S-7** | 三个守卫默认值**互不一致**（P2-C） | `ISSUE_LIST.md:349-...` | **未实现**（复核）：`descend_overshoot` 默认 `false`（`RiskSwitches.java:20`），而 FALL 的 `fallRecoverable`（`SurfaceMovementProvider.java:193/202-213`）与 ASCEND 的 FallingBlock 前置（`AscendExecutionFactory.java:59-64`）**无条件执行** | 登记为过渡态即可；但**Baritone 对照记录必须标注 Alice 侧多开了 2 个 Baritone 没有的守卫**，否则对照结论不可信 |
| **S-8** | `LiveExecutionContext.policyVersion` **恒为 0**（P2-A） | `ISSUE_LIST.md:349-370` | **未实现**（复核）：`pathing/core/session/PathSession.java:290` 写死 `0L, 0L`，无其它写入点 | 接上后 S1 的"随请求冻结"才**可观测** |

**未核实（需实测，不属于上述清单）**：① 工具耐久（全仓仅 `InterfaceScanner` 提及，Job 层无判定）；
② `bot` 被清除（`BotManager.remove` 不重生）后 `LumberRegionState`（按 UUID 的 SavedData）残留是否可观测
—— "常驻 Job + bot 消失"的组合没有日志覆盖。

---

## §2 内核搜索正确性（对齐审计 b 类）

> 审计原结论（`R4_AUDIT.md:400`）：64 项 = **39 未登记偏离 + 13 缺失 + 7 已登记 + 4 对齐**；
> D-036「除目标层外默认对齐 Baritone」**当时不成立**。
> **复核后大量已收口**（成本模型 D-040、启发式、A\* 系数、危险方块、放置校验、COLUMN 容差×5、
> 超时收敛、`abort()` 清进度、条件 settle、疾跑、`canWalkOn` 白名单、`WorldView` 已删 D-044）。

| # | 项 | 来源 | 现状（复核证据） | 判据 / 备注 |
|---|---|---|---|---|
| **K-1** | 预算耗尽**丢弃 best-so-far 前缀** | `R4_AUDIT.md:180` | **未实现**（复核）：`AStarMovementSearch.java:117-124` 预算耗尽即整条 `PathPlan.failure(SEARCH_LIMIT)`，best-so-far 只进诊断串；`PlanningStatus` 无 `PARTIAL`（`PathPlanner.java:85` 的 `PARTIAL` 是**零消费者死枚举**） | 长距离/挖矿重试的直接收益；⚠ **`PARTIAL` 绝不可映射为 `UNREACHABLE`**（D-004 红线） |
| **K-2** | **legacy 双内核仍有活引用** | `R4_AUDIT.md:231/280` | **未实现**（复核）：`task/TransferTask.java:85-92` 仍 `new SurfacePathfinder + new PathExecutor`（只被 `BotManager.java:314` 的 `LEGACY_PATHING_TASKS_ENABLED=false` 挡住）；`pathing/movement/DescendMovement.java:142`、`PillarMovement.java:146` 仍裸 `setBlock` | 违反 D-031「唯一入口」；是挖掘链路迁移与 R7 异步搜索的未解债务 |
| **K-3** | `safeToCancel` 抢占门控缺失 | `R4_AUDIT.md:204` | **未实现**（复核）：全仓 `safeToCancel` **0 命中**；`core/MovementExecution.java:4-23` 接口仍无该字段 | 只在"空中/挖掘中被取消"时可观察（输入残留）；三项 b 类里**最不影响业务** |
| **K-4** | 规划/执行谓词**不统一** | `R4_AUDIT.md:220` | **未实现**（复核升级）：规划侧含扫掠（`MovementHelper.java:290` `canSweepPlayer`），而执行工厂只用 `canWalkThrough`（`AscendExecutionFactory.java:48-63`、`TraverseExecutionFactory.java:34-35`、`DiagonalExecutionFactory.java:45-57`） | 窄场景"可规划不可执行"；当前**无复现证据** |
| **K-5** | 死状态 `POSTCONDITION_FAILED` | `R4_AUDIT.md:206` | **未实现**（复核）：`PathSessionStatus.java:24` 全仓无生产者（D-047 只复活了 `BLOCKED`） | 观测盲区：该状态永远不会出现 |
| **K-6** | 其余完备性项 | `R4_AUDIT.md:182-206` | `closestPathPos`/`ticksAway`（软2硬3/200 tick）、世界变化检测（`costVerificationLookahead`）、双阶段预算（`SearchBudget.java:12-33` 单墙钟）、路径裁剪/favoring、`isReplaceable`/开门/直放分支 —— 均无实现 | 优先级最低；PARKOUR 已按 D-044 定为**暂不采纳**（非缺口） |
| **K-7** | **登记缺口** | 复核 | §3.A 的「未加载区块门控」「best-so-far」**未见 D-0xx 登记**；§3.B 三层超时（代码已按 D-042 收敛）与 snipsnap 语义差异也未单列登记 | 按 `WORLD_WRITE_AUTHORIZATION.md:4`「未登记即隐式默认 = 违规」补登记 |

---

## §3 Job 层与决策缝契约（**决定"能不能接 LLM"**）

> `JOB_LAYER_DESIGN.md` 的切片表**止于 J8**，**没有 J9+ 清单**；§10 明确把
> 「LLM 接入 / 风险画像 / 多 bot」列为**不解决**。J1–J8 已把"决策缝"的地基做完（见 `AI_PROJECT_STATE.md`）。

| # | 项 | 出处 | 现状（复核证据） |
|---|---|---|---|
| **J-1** | ✅ **已实施（D-134，客户端待测）**：两个记录类各加 `botId` + `terminalReason`，并有 `task_terminal_reason` 证据行 | `TaskExecutionRecord.java:8-19`、`BotManager.java:1225-1234` | **未实现**：终态只记死串 `"done"` / `"failed:"+failureReason()` ⇒ `quota_met` / `idle_no_work` / `no_reachable_candidate` 等**只能从日志读**。这是差异②（上抛 LLM）的**最关键缺口** |
| **J-2** | ✅ **已实施（D-134，客户端待测）**：`JobRequest` + `JobLauncher` + `BotManager.assignJob`（入口发料也收在这一处） | `BotManager.java:487/528/579` | **未实现**：逐域硬编码 `assignLumberJob`/`assignMineJob`/`assignRegionLumber`；通用 `assignTask(:791)` 需要调用方自己持有 `Job` 类 ⇒ LLM 无法"用一个动作起任意 Job" |
| **J-3** | ✅ **已实施（D-134）**：终态记录/TaskOutcome 均有 `botId`（UUID 字符串） | `TaskExecutionRecord.java:8-19` vs `MULTI_BOT_INTERFACE_RESERVATION.md:7` | **未实现**：与多 bot 预留的期望直接冲突（`taskKind` 现为类名，如 `RegionLumberJob`） |
| **J-4** | `job/` 包**未覆写 `failureReport()`** | 复核 | **未实现**：`LumberJob` 上抛的 `tool_missing`/`climb_incomplete` 进不了 `TaskOutcome.failure` ⇒ 失败码在 Job 层"半途而废" |
| **J-5** | `UNTIL_FULL` 声明了**没实现** | `JOB_LAYER_DESIGN.md:383/435-439` | **未实现**：`GoalSpec.Kind.UNTIL_FULL`（`GoalSpec.java:28`）与 `stopWhenFull`（`:20`）全仓**无读取点**；`COLLECT_ITEMS`（`:24/62-65`）同样无消费者 |
| **J-6** | `MineJob.countTargetItems` 硬编码矿物清单、忽略 `productTag` | `job/mine/MineJob.java:278-302` | **未实现**：与 `GoalSpec` 的"产物"口径脱节 |
| **J-7** | 决策结果/拒绝理由**无结构化回读** | `DecisionTrace` | **未实现**：`policy.select(...)` 的 `reason` 与 `rejected()` 只进 `BotLog`；没有读回通道（LLM 拿不到"为什么没选它"） |
| **J-8** | 树种过滤未做 / 非区域补种未做 | `JOB_LAYER_DESIGN.md:441-444` | **部分实现**：区域补种已做（`REGION_REPLANT` + 账本 `KEEP`，J8 Slice B）；`Tree.species` 仅记录、**未过滤** |
| **J-10 掉落物归属与收集授权** | ✅ **已实施并 `WINDOWS_CLIENT`**（D-143/D-144）：`DropProvenance`（我方直接/间接 60tick·4格松窗 / `GRANTED_AREA` / `FOREIGN`）+ `DropPolicy` 唯一判定入口 + **被动拾取闸门**（`EntityItemPickupEvent`，节流+计数）+ `CollectGrant` 选区授权（ONCE/SESSION/ALWAYS，`always` 报告显式标记）+ 收集按策略过滤（`anyDrops` 退役）。**遗留登记**：`CollectDropsTask` 的 `policy_blocked` 终态区分、"我方放置/拆除点"并入松窗。（原提案： 现在只认"我方破坏事件配对"⇒ 漏掉**我方行为的间接后果**（树叶衰减掉树苗/木棍、仙人掌/甘蔗被移除支撑后弹出）与**玩家派活**（捡玩家授权区里的东西）） |
| **J-9** | **LLM 接入本体**（差异②）✅ **已实施（D-135，客户端待测）**：快照契约 + 动作词汇表 + 事件驱动/节流 + 严格拒绝 + 决策 trace；配置从当前部署复制（`config/alice-llm.json`） | `JOB_LAYER_DESIGN.md:390-396`、`:21/:244-247` | **未实现**，四件套都缺：① LLM 调用；② **给 LLM 的权威状态快照契约**；③ **动作词汇表**（"起哪个 Job + 什么 spec"）；④ **触发节奏**（不能每 tick 调）。可注入点已就位：`SelectionPolicy.java:15`、`CandidateSource.java:16` |

---

## §4 世界写入授权登记缺口（G 项）

来源 `WORLD_WRITE_AUTHORIZATION.md:69-77,101`。**G1/G2/G9 已修**（R2a/R2b）。

| # | 项 | 现状 |
|---|---|---|
| **G3** | 模组连锁破坏**无凭证**（`MineTask.beginChain` 反射调模组 `MiningScheduler`） | 破坏量不受 Alice 预算约束；需与"模组能力层"一起做 |
| **G4** | Slice B2：**尝试级 tick 预算** | Slice A 已落地（`WriteBudget`，D-106）；B2 未做 |
| **G5** | **容器写入是第三个维度**（`TransferTask`/`ChestBotTransferPrimitive`） | 现有授权面完全不覆盖 |
| **G6** | legacy `pathing/movement` **裸写入** | `DescendMovement.java:142`、`PillarMovement.java:146` 裸 `setBlock`（当前无生产调用，与 K-2 同源） |
| **G7** | **死闸门**：`FluidRiskPolicy.miningRefusal` 无调用者，而 `MineTask` 保留 `fluid_risk_lava` 硬拒绝分支 | 一个**永远为假**的分支（与 S-4 同源） |
| **G8** | ✅ **已接线（D-157 + 附注一：含"保护区字段又变装饰"的真问题修复）**：`CapabilityGate` 让 **10 个死字段中的 6 个**真的拦人（`changesWorld`/`canBreakBlocks`/`canPlaceBlocks`/`requiresZoneAuthorization`/`consumesResources`/`requiresTool`）+ 声明一致性断言；**仍未读**：`maxNaturalDrop`/`supportsMidExecutionRevalidation`/`mutationIntents`/`intrinsicReversibility` |
| **G-新** | ✅ **已补登记（D-157）**：`WORLD_WRITE_AUTHORIZATION.md` 新增 **A9 = 区域补种放置**（`REGION_REPLANT`/KEEP/过放置预算），A9 断档消除。原状：J8 区域补种放置未进登记表且 A9 编号断档 |

---

## §5 验证债

| # | 项 | 说明 |
|---|---|---|
| **V-1** | `AI_TEST_MATRIX.md` 里 **16 处「待测」** | 其中若干**疑似已被后续 D-0xx 取代**（例：垂直下落 D-048/D-050、流体屏障 D-037、D-061 覆盖断言、R2 零进展 D-105、FALL/PILLAR 的 Baritone 对照）⇒ 需逐条核对后改判，否则矩阵不可信 |
| **V-2** | **未覆盖行为分支** | 几何不可达「首候选失败、次候选成功」、清障子任务**加高**行为、`trunkHeight+1>12` 截断、① 扫尾**超时**分支、`climb_incomplete` 场景、恢复 `too_far` 分支、**真实崩溃重启**路径 |
| **V-3** | **T6 盲区** | `19/24` 那种「本来就看不见目标、必须清障」的目标只给软提示；其"清障是否可行"归 `analyze-lumber-scene.py`（且不计可达性）—— 两项合一才算完整 |
| **V-4** | **Baritone 对照实验未跑** | `contrast_fall` / `contrast_pillar`（含"Alice 侧多开 2 个守卫"的标注要求，见 S-7） |
| **V-5** | **未核实项** | 工具耐久；死亡/bot 清除后区域态残留；`MovementSpec`/`MovementCapabilities` 内部一致性；Diagonal 绕角执行细节 |

---

## §6 文档债

| # | 项 | 说明 |
|---|---|---|
| **D-1** | 三份审计报告的**包路径全部过期** | 报告写 `com/alice/pathing/...`，现状是 `com/dddgn/alice/pathing/...`；`core/WorldView.java` 已删（D-044）⇒ 引用行号前必须先重定位 |
| **D-2** | `reference/BARITONE_PORTING_CHECKLIST.md:39` 的**禁令已被推翻** | "明确关闭 DOWNWARD / 多格 FALL / PILLAR" 已被 D-048/D-050、D-055、D-058 **客户端验收**取代 |
| **D-3** | `JOB_LAYER_DESIGN.md` §11 状态过期 | ① 攀爬（已由 J7 实现）、③ 补种（区域补种已由 J8 实现）仍写"本轮不做"；§10 的"风险画像未做"需与 §1 联动更新 |
| **D-4** | `AI_PROJECT_STATE.md` 中段已重写 | 原文是 2026-09-07 的 R2 快照（称 R2-D/R3 未启动）⇒ 本轮已改为能力现状 + 三条差异现状（commit `e7f1a87`） |
| **D-5** | `ALIGNMENT_OPEN_QUESTIONS.md` **Q1/Q4/Q7 仍未裁定** | Q2/Q3 已撤回（`:135-140`）；Q1（1 格红线 → 可回收性不变式）、Q4（replan 下沉任务层）、Q7（成本模型偏好）待用户裁定 |
| **D-6** | `RISK_MODES_DISCUSSION.md`（H/G/S 风险模式） | 用户明确"只讨论不实现"（D-046），保持现状 |

---

## §6.9 传输模块彻查（2026-09-13，用户要求）

**产出**：[`TRANSFER_MODULE_AUDIT.md`](TRANSFER_MODULE_AUDIT.md)（架构图 + 6 条发现，逐条带 `文件:行` 证据）。

**结论**：**不是屎山**，但三处真问题 —— ① **生产/测试错位**（533 行夹具在生产树，测试钩子伸进写入原语
`ChestBotTransferPrimitive:35/42/59/68`、生产状态机里带夹具短路 `TransferTask:31/75`）；
② **死码/只写状态**（3 个零引用错误码 + 2 个只写状态）；③ **容器写入无授权/预算维度**（0 处 `WriteBudget/WriteGrant`）。
核心（两段式写入 + 三重增量证明 / `blocksBot` 在途阻塞 / `SERVER_RESTART` 挂起）**扎实，不建议推倒重写**。

**建议**：定向重构 R1 生产/测试分离 → R2 新增 `alice:transfer_check` 一键入口 → R3 清死码 + 裁定授权维度；
**退役 `/alice selftest`**（必崩于无 Mekanism 客户端 + 与 in-game 电池重复 + 判据陈旧），
但**立即修** `InterfaceScanner` 的 Mekanism 硬引用（活雷）。

**用户裁定（2026-09-13）**：**R1+R2+R3 全做**；**容器写入算世界改动**（进 A 表 + 授权/预算）。

**已先行完成（D-160）**：`InterfaceScanner` 的 **Mekanism 硬引用**拆除（活雷；2026-09-13 崩溃根因）
—— Mek 代码整体搬进 `MekanismScanner`，只在 `ModList.isLoaded("mekanism")` 时触碰（JVM 惰性加载 ⇒ 缺席永不加载）。

**已执行（D-161）**：依赖清理（build.gradle 去掉 Mekanism，删除 `MekanismScanner`）/ **selftest 退役**
（BotSelftest + 命令 + legacy `PathingRegression`，有用断言救出为 `FootCellRuleCheck`）/
**R1 夹具迁出**（`com.dddgn.alice.fixture.transfer`，生产只留 `TransferTestHooks` 一个惰性接缝）/
**R2 一键入口** `alice:transfer_check`（电池第 23 步）。
**已做（D-162）**：**R3**（删 3 死码；两个只写状态重新定性为**审计哨兵**并注明；容器写入纳入
"世界改动"体系 —— `WriteReason.CONTAINER_TRANSFER` + `WriteBudget` 第三维度 `maxContainerWrites=32` +
两段写入前消费 + 拒绝码 `container_budget_exhausted` + G5 记录带 `requester/reason` + 登记表 **A11**）+
**L1**（行走目标改为**端点附近最近合法站点**、到达与写入前各做一次**触及校验**、新增
`endpoint_no_standing_point`/`endpoint_out_of_reach` 两码）；
**L2（真实 openMenu 菜单协议）另立项讨论**（用户：影响整个项目的模组方块交互基底）。

## §6.10 方块交互三条路线对比（2026-09-13 用户提问）

**产出**：[`INTERACTION_LAYERS_COMPARISON.md`](INTERACTION_LAYERS_COMPARISON.md)（A 接口直写 / B 菜单协议 /
C 视觉识别；含"兼容性与效率""玩家能看到什么"两项逐维度对比 + 6 个讨论点的两条路线形态）。

**要点**：
- **C（视觉识别）与 B 无关**：B 走的是真实玩家客户端同一条协议（`useItemOn` + `ContainerClick` 的服务端落点），
  不渲染、不看屏幕、不需要真实客户端；
- **兼容性**：A 只覆盖 Forge capability（物品/流体/能量），**配置/安全/升级只能靠模组专属接口**（强耦合、易碎），
  且**可能绕过模组的权限/统计/GUI 钩子**；B 走模组自己的入口 ⇒ 不绕过、版本更稳，但受触及/菜单校验约束，且做不到"玩家做不到的事"；
- **效率**：A 微秒级、可跨距离批量；B 毫秒级、逐槽点击（慢，但那正是玩家的真实速度）；
- **玩家可见**：A 几乎不可见（物品凭空变化、无开盖音效动画、可能无声绕过保护）；B 与真人一致（转向→右键→开盖动画/音效→逐堆移动→关盖）。
- **建议**：**分层** —— **B 作为默认**（模组自己的契约、不绕过权限、观感可读），**A 仅作显式优化**
  （适配表声明"语义等价"时才用，且仍守 L1 站位/触及）；并把"**旁观者能看懂 bot 在做什么**"写成产品要求。
- **用户裁定（2026-09-13）**：同意 **"B 默认 + A 显式优化"**；先做第 5 项验证，且**加延迟**便于观察。
  **已实现探针（D-163）**：`alice:menu_probe`（约 8 秒）—— `useItemOn` 开真菜单 → `menu.clicked` 两次搬铁锭 →
  `closeContainer`；判据 `[MenuProbe] SUMMARY menu_opened=… clicks=… item_moved=… verdict=…`。
  **结果待测**：通过 ⇒ B 路线可行（继续做槽位语义表/生命周期/A11 衔接）；不通过 ⇒ B 路线重新评估。

## §6.11 2026-09-13 会话收尾状态（交接用）

**本会话完成的**（全部有文档与判据）：
- **基-1 可回收性不变式**（两条轴：逐步有回程 / 活动无残留）——`WINDOWS_CLIENT`；
- **基-2 决策层进回归电池**（电池 9 → 26 项）；**基-3 S4 事件层**——`WINDOWS_CLIENT`；
- **基-4 决策 trace 落盘 + 跨重启语义**（含一次真实重启验证）——`WINDOWS_CLIENT`；
- **基-5 LLM 上抛契约**（J-4/J-6/J-7）——`WINDOWS_CLIENT`；
- **基-8 授权登记缺口**（G8 能力闸门 / A9 登记 / G3 外来破坏留痕 / G5 容器写入留痕）——`WINDOWS_CLIENT`；
- **基-9 工具与耐久管理（第一批）**——`WINDOWS_CLIENT`；
- **基-7 K-1**（`PlanningStatus.PARTIAL` + best-so-far 前缀 + 消费者真的用它）——`WINDOWS_CLIENT`；
  **K-2 迁移**（`TransferTask` 行走 + `/alice path` 诊断切到新内核 ⇒ 生产/开发路径无 legacy 引用）；
- **L2 交互路线**：对比文档（A/B/C 三路线）+ 探针验证（`alice:menu_probe`，用户实测通过）+
  `MenuSession` 组件化 + `MenuLifec​ycle` 生命周期（收尾收敛点 + 看门狗）+ **生产化**
  （`ContainerSemantics` v0 / 路线开关 `/alice transfer route` / 传输默认走菜单路线 / 菜单打开入事件环）；
- **传输模块彻查**（`TRANSFER_MODULE_AUDIT.md`）+ R1（夹具迁出生产）+ R2（`alice:transfer_check`）+
  R3（死码/授权预算/A11）+ L1（附近可站点 + 触及校验）；
- **环境事故与修复**：D 盘被 330 份镜像备份写满 ⇒ 清理 + 镜像脚本改为"默认不备份/不校验、自动轮转"（8.6 秒）；
  **依赖清理**（移除 Mekanism 等可选模组依赖，删 `MekanismScanner`）+ **`/alice selftest` 退役**
  （有用断言救出为 `FootCellRuleCheck`）。

**唯一未收口的（明确交接）**：`alice:transfer_check` 的 **`end_to_end`** 用例。
根因已精确定位并修复（**背包索引 ≠ 菜单槽位号**，见 D-165 附注四），
但**修复未经过客户端验证** ⇒ 下次开工第一件事：跑一次 `alice:transfer_check`，
判据 `[Transfer] SUMMARY … end_to_end=PASS … verdict=PASS` 且 `[Transfer] end_to_end … moved=3`。
若仍失败，日志里 `[Transfer] suspend code=… phase=… src=… dest=…` 与 `[Menu] …` 会直接给出第一现场。

**下次开工的建议顺序**：① 复测并收口 `end_to_end`；② 电池 `transfer` 步保持绿色（回归门不能长期红）；
③ 若 L2 生产化通过 ⇒ 用 `/alice transfer route capability|menu` 做一次 A/B 观感对照；

### §6.12 2026-09-13 下半场收尾（K-3/K-4/D-168/D-169/D-170）

> `end_to_end` 已闭合，两份独立证据：① 单跑 `alice:transfer_check`（10:09，`2026-09-13-7.log.gz`）
> ② 本轮 26 项电池里的 `transfer` 步（11:06，`2026-09-13-1.log.gz`）——
> 两次都是 `[Transfer] SUMMARY fixture=PASS end_to_end=PASS selection=PASS selector_events=PASS command_parse=PASS verdict=PASS`。
> §6.11 的"唯一未收口"**已闭合**。

**本轮完成的（都有客户端证据）**：

| 项 | 结论 | 依据 | 等级 |
|---|---|---|---|
| K-4 谓词统一 | 6 处复制 → 唯一定义 `canStandCentered`（8 调用点）；目标准入先测量；真异常 0 ⇒ **不引硬拒**，收口为"唯一定义 + 永久自断言" | 电池 `K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=88)` | `WINDOWS_CLIENT` |
| D-168 夹具测量 | `dropsLeft` 改基线 UUID 增量 + 三场景清实体 ⇒ 世界残留不再假失败 | `mine_regression` 11/11 PASS | `WINDOWS_CLIENT` |
| D-169 电池自杀 | K-3 退出电池（25 → 23）+ `fixture_not_top_level` 前提断言 + 手工入口两模式 | 电池 `(26/26) ticks=3507 → PASS` | `WINDOWS_CLIENT` |
| D-170 资源缺陷 | 3 个 0 字节模型 + 1 个死贴图引用补齐；新增 `tools/check-item-models.sh` 并接入构建前清单 | 客户端 `Failed to load model alice` = **0** 条 | `WINDOWS_CLIENT` |

**唯一待测**：**K-3 自检复测**（`alice:k3_stop_check` 右键 DEFER / Shift+右键 FORCED，各 5 秒；
判据 `bot_report` → `deferred=1` / `forcedUnsafe=1`）。FORCED 用例**至今从未跑过**。

**§6.12 续（同日晚些时候）**：
- **K-2 第三批已完成（D-171）**：legacy 双内核**整批删除** —— `pathing/` 顶层 21 个文件删 19 个
  （`AStarPathfinder`/`AStarPathPlanner`/`PathPlanner(s)`/`Local`/`Hybrid`/`Surface`/`OpenSet`/`PathNode`/`Goal`/
  `MovementPlan(Compiler)`/`MovementPathExecutor`/`MovementMode`/顶层 `MovementType`/`PathExecutor`/
  `TunnelPlanner`/`TunnelObstaclePolicy`/`TunnelPlan`），保留 `MovementHelper` + `FootCellRuleCheck`；
  删除依据 = **按路径分析的零活引用**（不是"看着像死"）；顺带删掉 `MovementHelper.cost` 与残留 import。
- **K-5 已收口（D-172）**：`POSTCONDITION_FAILED` **不删**（§5.3 契约要求），改为**给它生产者** ——
  归属规则收敛为纯函数 `PathSessionStatus.classify`，把原先被吞掉的 `*_SETTLING_TIMEOUT`/`*_OVERSHOT_*`
  归入"后置条件未满足"；并新增自检 `session_status_no_dead_value`（分类表 + 覆盖率，
  除 RUNNING/COMPLETED 外每个枚举值都必须有生产者 ⇒ 以后再加死值会红）。

**仍未动的余项**：Job 细粒度 `safeToCancel` 聚合（待频率数据）、阶段 2 模组浅测（§7）、
§7 的其他方向候选。

**§6.13 第四次电池（2026-09-13 晚）**：26 项 **22/23**，唯一失败 = `transfer` 步的 `end_to_end`
（间歇：同计划 10:09 / 11:06 两次 5 tick 走完，这次一格没动磨满段预算）。
已排除夹具/重生成/菜单/强制停；已补**失败终态诊断** `[R4 Session] segment_stall`（D-174），
**遥控器候选已被用户否证**（没用过遥控器）⇒ 改为"链路计数"诊断（D-174 附注一）：
段卡死时同时打出 `entityTicksInSegment` / `travelCallsInSegment` + 输入串，
可判定"实体没 tick / tick 了但 travel 没进 / 物理跑了但没位移 / 有人每 tick 清零输入"。
**待复现**：独立跑 `alice:transfer_check` 约 15 次**全 PASS** ⇒ 该失败只在**电池语境**出现；
电池内已排除并发驱动（决策层 `trigger_skipped reason=suspended`、无 Job 活动、
无 `controller_stop_movement`）⇒ 待电池复现 + 新诊断读数。
**新开账（J 级，未修）**：**常驻 Job 不感知"bot 被传送离开作业范围"** ——
实测 `RegionLumberJob` 在转移场景（z≈404，目标树在 198 格外）一路搭了 12 格圆石再拆回；
触发条件 = "跑测试夹具时后台挂着常驻 Job"（本项目最常用操作）。
另登记潜在设计洞（非本次病因）：任务驱动与手动遥控无互斥。
K-5 新用例 `session_status_no_dead_value=PASS` ✓（能力闸门 11 项全 PASS）。
另：D-173 补删 `pathing/movement/` 14 文件 + 一个被 `.gitignore` 藏住的 `.backup` 残留。

④ 然后回到总账：**基-6 多 bot 并行（差异③，建议单独立项）** / 基-7 余项（K-3 `safeToCancel`、K-4 谓词不统一）。

## §7 方向候选（**2026-09-12 已裁定：基层优先，模组适配只做浅测**）

**用户裁定（D-147）**：不要急于深入模组适配；挑**两个模组**浅测；**先把基层补齐**。新路线：
1. **阶段 1 基层收口**：
   - ✅ **基-2 决策层进回归电池**（D-149，已实现已编译，待客户端跑电池）
   - ✅ **基-3 S4 事件层**（D-150，`TOOL_LOW`/`STUCK` 阈值 + 滞回；**2026-09-12 20:40 四例全 PASS → `WINDOWS_CLIENT`**）
   - 🔄 **基-1 可回收性不变式**（项目差异①）：**第一步已完成并实测（D-151 + 附注一）** = 可回收性真的被算出来 +
     依据可审计 + 真实计划分布（12 会话、10 种 Movement、distinctLevels=2）+ 寻路回归 13/13 无退化 +
     自检四例（含"校验是活的"负例）。
     **第二步已实施（D-152）**：`RecoverabilityFacts` 逐边事实（穿搜索到执行期）+ `RecoverabilityPolicy`
     策略表（`FALL>=PATH_REVERSIBLE`）⇒ **不带返回守卫事实的 FALL 边会被真的拒绝**（本机实测 `REFUSED`）；
     自检加 `fall_without_fact_refused` 负例。**待客户端复测**（自检 + `pathing_regression` 的 `fall_course`）。
     仍未使用：`SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED`（无产出者，策略表不写死）。
     **客户端自检 6/6 PASS**（含两个负例）；**待补**：`pathing_regression` 真实 FALL 边（事实穿搜索链路）。
     ✅ **第二条轴已落地（D-153，用户批准）**：*残留 = 可回收性失败* —— `RestoreScopeTask` 对账后
     仍剩我方方块 ⇒ 计数 + `[Recover] RESIDUE` + `RESIDUE` 事件（统一出口 `DecisionEvents`，自检期只记录不通知）；
     `PathingRegressionTask` 判分后自清场（**账本精准回收**，见下）。
     ⚠️ 首版清场实现有 bug（重跑场景地形 ⇒ 把站在区域里的 bot 埋了，所有任务一启动就死于窒息）——
     已修为"只回收我方 TEMP 方块、不碰地形"（D-153 附注一）。待客户端复测：
     `cleanup=ledger 剩余=0` 且不再出现 `[Recover] RESIDUE`。
     ✅ **客户端全绿（2026-09-12 22:59）**：真实 FALL 边带 `pillar_return_guard_passed` 事实（多会话），
     回归 13/13 + 10 种 Movement 全覆盖、`PathingRegressionTask terminal=COMPLETED`；
     `cleanup=ledger … 剩余=0`、`residues=0`、零 `RESIDUE` 事件 ⇒ **两条轴都验证完毕**。
     **未做**：残留的自动补救（回收 UNREACHABLE 时挖开/搭桥取回）—— 碰 D-076 红线，属能力扩展；
     `SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED` 仍无产出者（策略表不写死）
   - ✅ **基-4 决策 trace 落盘 + 跨重启语义**（D-154 + 附注一，**客户端全绿 + 真实跨重启验证**）：`DecisionTrace`（JSONL +
     内存尾 + 超限轮转 + 写失败不影响决策）、`DecisionState`（登记"重启会丢的未决请示/当前任务"，
     启动时**只报一次**）；裁定：请示作废但必须报出 / 任务不自动续做但如实汇报 / 不做假恢复；
   - ✅ **基-5 LLM 上抛契约**（D-155 + 附注一，**客户端全绿**）：J-4 四个 Job 覆写 `failureReport()`、
     J-6 `MineProductFilter`（目标驱动 + 标签族，尊重 `productTag`）、J-7 结构化拒绝回读（进 prompt + 汇报 +
     接受后清除）；电池第 19 步 `llm_contract`；
   - ✅ **基-9 工具与耐久管理（第一批，D-156 + 附注一/二：`WINDOWS_CLIENT` 全绿）**：`ToolSupply`（事实 + 安全搬运）、
     `ToolMaintenanceTask`（`already_ok`/`promoted_from_main`/`worn_no_spare`/`no_tool`）、
     决策动作 `maintain_tool`、工具事实进 prompt/汇报、`TOOL_LOW` 文案可行动；电池第 20 步 `tool_supply`。
     **未做**：工具来源（合成/取材料 = S6）、"耐久不足以完成计划工作量时提前拒绝"；
   - ✅ **基-8 授权登记缺口**（D-157 三附注）：G8 能力闸门（含"保护区字段又变装饰"真问题修复）、
     A9 登记表断档补齐、G3 外来破坏留痕（双向自检）、G5 容器写入留痕（NBT 往返断言）；
   - 🔄 **基-7 内核残余（第二批 = K-2 迁移，D-159）**：`TransferTask` 行走 + `/alice path` 诊断
     迁移到新内核 ⇒ **生产/开发路径上已无 legacy 引用**；余项：batch 3 删除死代码
     （`TunnelPlanner`/`LocalPathPlanner`/`HybridPathPlanner`/`PathPlanners`）+ `BotSelftest` 归属；
     **K-3 已实施（D-166 + 附注一）**：8 个执行器按 Baritone 承诺点声明 `safeToCancel` + 7 个寻路任务透传 +
`stopTask` 安全点延后（上限 20 tick，超时强停计数）+ 生存打断不安全计数 + **L2 空中门** + `/alice stop-task`；
**确定性夹具**见 D-166 附注二（`alice:k3_stop_check`：右键 DEFER / Shift+右键 FORCED；**不进电池**见 D-169）。
     **K-4 已实施（D-167，`COMPILES`，待客户端）**："可站"谓词 6 处复制 → **唯一定义**
     `MovementHelper.canStandCentered`（8 个调用点，纯重构；2 处写入类例外在代码处写明理由）；
     目标准入**只测量不硬拒**（遥测进 `bot_report` 的"目标准入（K-4 累计）"行 +
     电池 SUMMARY 自断言 `K4=OK/VIOLATION`）——**待电池实测决定**要不要引 `GOAL_NOT_STANDABLE` 硬拒。
     **K-4 真实运行数据（`SERVER_TESTED`）**：累计行只有写入类例外（89 / 51），**真异常 0 次**；
     完整电池的 `K4=OK` 仍待一次跑完的电池。
     **D-168 夹具测量修复**：`mine_regression` 的 `dropsLeft` 原先数"盒内全部掉落物"⇒ 世界残留假失败
     （run1 两次 FAIL 均只因 `dropsLeft=1`；run2 手动清残留后 PASS）。改为**基线 UUID 增量**
     （残留报 `foreignDrops=` 不计入）+ `mine_course`/`floating_course`/`chain_mine_course` 三个场景函数
     补 `kill @e[type=item,…]`。**新规则**：凡夹具断言涉及世界掉落物或背包净增量，
     必须"场景清实体"或"取基线"至少一条（同类风险：`ScaffoldLifecycleTask` 的 `itemsOnGround`，
     目前靠其场景已有 `kill` 兜住）。
**K-4 已收口（第四轮实测）**：完整电池 `K4=OK(真异常 0 / 写入类例外 88)` ⇒ 不引硬拒，
删临时告警、保留计数与电池自断言（D-167 附注一）。
**新增资源自检**：`tools/check-item-models.sh`（D-170；空模型/死贴图引用是"只有客户端日志能看出来"的缺陷类）。
**余项**：Job 细粒度承诺点聚合（待频率数据）、**K-5 遗留枚举死值**、**K-3 自检复测**（DEFER + FORCED）。
   - （第一批 = K-1，D-158）：`PlanningStatus.PARTIAL` + best-so-far 前缀
     + `PathRetryRunner` 真的消费（先走前缀再重规划）；**余项**：K-2 legacy 双内核活引用
     （`SurfacePathfinder` 7+ 处）、K-3 `safeToCancel` 全缺、K-4 谓词不统一、K-5 遗留枚举死值；
   - ⏭ 之后：**基-6 多 bot 并行（差异③，建议单独立项）**；
2. **阶段 2 真实数据浅测**：模组集合 = **Create + Extended Crafting + Mekanism + Thermal Expansion**
   （D-148：后两者是"配方打架"对照 ⇒ 压测**多路线/冲突**理解）；只验"运行时导出 + P1 读得懂多少"，
   产出**适配器清单**与**冲突报告**（⚠ 矿物词典=标签：成品材料统一、中间物与机器配方不统一 —— 见 D-148）；
3. **阶段 3 能力扩展**：S6 Craft/Smelt → 机器适配器（按清单挑 1–2 个）→ 偏好规则；多 bot 并行另立项。

（原文候选，保留备查）

| 方向 | 内容 | 依据（为什么现在） | 代价 / 风险 |
|---|---|---|---|
| **① 安全底座小批次** | `S-1 维生出口` + `S-2/K 未加载区块门控` + `S-3 删重复调用` + `S-4 接线` | J8 让 bot **常驻**（`RegionLumberJob.java:25-29`）⇒ 暴露从"任务瞬时"变"持续在线"；S-1 是唯一"拒绝对没有出口"的反例；S-2 同时是内核审计的 MISSING-IN-ALICE 高项、也是 D-004/D-076 判据的前提 | 4 项都是小改动；S-2 需定"硬拒"口径（复核已给答案）；可服务端 + 客户端双向验证 |
| **② 决策层接入（差异②）** | 先补契约（`J-1` `terminalReason` 进 `TaskOutcome`、`J-3` `botUuid`、`J-2` `GoalSpec→Job` 统一入口），再做 LLM 目标级循环（状态快照 + 动作词汇表 + 触发节奏） | 这是项目**立项目标本体**（LLM 只做目标级决策），J1–J8 造的地基正是为它；`J-1` 不做则 LLM 连"任务为什么结束"都拿不到 | 大弧线（多轮）；需先与用户定"LLM 的输入契约/动作集/触发时机"，避免做成第 4 个死抽象 |
| **③ 第二个高级任务** | 候选：**农耕补种**（成熟作物=新 `CandidateSource`；破坏一格=现成 `MineTask`；补种放置+账本 `KEEP`=J8 已跑通范式） | 证明 Job 层**不是伐木专用**（J5 只证明了骨架复用）；农耕**不需要新授权入口**、子动作全现成 | 业务价值取决于是否要做"农牧"方向；建造/拆除**最后做**（放置为主目标，踩 D-076，且 `RoadBuildTask` 的 `BULK_EDIT` 仍标"无预算"） |
| **④ 验证债/文档债清零** | `V-1` 矩阵 16 处待测逐条改判 + `V-2` 未覆盖分支补场景 + `D-1..D-3` 报告勘误 | 矩阵与审计报告是"下一个人读的第一手材料"，过期即误导（本轮已吃到一次：状态文档中段） | 纯卫生工作，可与其他方向并行；不产生新能力 |
| **⑤ 内核完备性** | `K-1` best-so-far 前缀 + `K-2` legacy 双内核收口 | `K-2` 是 R7 异步搜索与"唯一入口"的未解债务；`K-1` 对长距离/挖矿重试有直接收益 | 不直接服务业务；`K-1` 需同时定 `PARTIAL` 的对外语义（不得 = `UNREACHABLE`） |

**用户裁定（2026-09-12）**：**① → ②**（先安全底座小批次，再开决策层接入）。
**① 已完成并全部 `WINDOWS_CLIENT`**（S-1/S-2/S-3/S-4 + 起点脱困 D-133，见 D-132 附注三收口表）；
入口 `alice:survival_exit_check`、`alice:chunk_guard_check`、`alice:fluid_mine_check`（均零参数右键）。
→ **② 决策层接入进行中（三条通道骨架）**：S0 契约与管道（D-134/D-135）→ **S1 事实层（D-136）** →
**S2 选择层（D-139，`WINDOWS_CLIENT`）** → **S3 请示层服务端契约（D-140，`WINDOWS_CLIENT`）** →
下一步 **S3b 客户端弹窗** → **S3.5 收集归属 + 被动闸门（D-138 裁定）** → S4 事件层 → S5 知识层 → S6 执行层。
（原文）**当前进行中 = ② 决策层接入**（先补 §3 的 J-1/J-2/J-3 契约）。

**整理者的原建议**：**① → ②**。
先花 1–2 天把安全底座那 4 项小改做完（其中 S-2 与内核 K 项是同一件事，一次改动吃两份收益；
S-1 因常驻任务而真实化），再开 **②决策层接入** 这条真正的大弧线 —— 它才是"LLM 做目标级决策"
立项目标的核心，而 J1–J8 刚把它的地基（候选源/策略/trace/失败码/账本）铺完。
**③农耕** 可以作为 ② 的第一个"LLM 可选动作"来落，天然证明决策缝可用；
**④** 建议穿插着做（每轮收尾顺手清 2–3 条）。

**§6.14 崩溃与冻结（2026-09-13 晚，两件都已有实锤）**

- **服务端崩溃（已修，D-175）**：`crash-2026-09-13_12.07.23` —— `MineTask.tickRestore` NPE
  （`restoreTask` 已置 null 而 `phase` 仍是 RESTORE），由 `MineRegressionTask` 的 SCOPE_REOPEN
  用例"终态后又 tick 内层任务"触发，**打死服务端 tick 循环**。
  修：任务终态闩锁 + 防御守卫 + 夹具不再 tick 终态任务 + **电池 try/catch 隔离**（单步异常 → FAIL + 完整栈）。
  同类隐患（09-06 崩过一次同型 NPE）：19 处"字段置 null + `.tick()` 无守卫"，待逐个上闩锁。
- **bot 物理冻结（已定性，D-176）**：`segmentTicks=121 / entityTicksInSegment=0 / travelCallsInSegment=0`
  ⇒ **假人实体整段没被 tick**（输入 forward=1.00、onGround、空地、速度为 0）。
  这解释了 `exec_floating` 超时、`exec_chain` 收不到掉落、`restore FAILED remaining=1`、
  以及此前 transfer `end_to_end` 的间歇一格不动 —— **同一个病因**。
  已加 `[Bot] entity_tick_missing` 看门狗（含 removed/区块/玩家表/连接/task 现场）；
  **待复现取现场**后定修法（可能需要在 `BotManager` 侧补漏 tick 兜底驱动）。

**§6.15 2026-09-13 收尾复测（客户端 12:42）**：电池 **(26/26) ticks=3301 → PASS**、
`K4=OK(真异常 0 / 写入类例外 83)`、**22/22 终态步 `idempotent=true`**、
四个 exec 用例 `foreignOk=true(另有残留1件不计入)`（D-168 分支首次全量实测通过）、
`entity_tick_missing` / `segment_stall` / 异常均为 **0**。

**仍未闭环（按优先级）**：
1. **P0 假人物理冻结**（D-176 / 附注一）：**字节码实证** —— `ServerPlayer.doTick()` 的唯一调用者是
   `ServerGamePacketListenerImpl`（内部调父类 `tick()`）⇒ 假人物理**挂在"假连接被 tick"这条链上**，
   与 Alice 的全局 `ServerTickEvent.END` **不同源**。看门狗已带 `entityTicking=` 与新加的 `connTicks=`
   （`FakeConnection.tick()` 计数）：`connTicks` 不动 ⇒ 断在连接；在涨而实体不动 ⇒ 断在实体侧。
   **待复现取现场**后定修法（候选：让任务侧兜底驱动、或保证假连接进连接表）。
2. **传送感知**（D-180，用户要求只加报告）：`BotPlayer` 覆写两个 `teleportTo` ⇒ 计数 + from/to/tick +
   `[Bot] teleported` 日志 + 事件环 `TELEPORT` + `bot_report` 一行。
   **日志已实测（13:05，5 条）**；附注一修掉"原地复位"噪声（只有位移 ≥1 格才记日志/入环）。
   **仍待验证**：`bot_report` 的"传送（位移）"行（本轮没跑过 `bot_report`）。
2. **终态幂等契约推广**：19 处直接 tick 点里 17 处是夹具（已被电池隔离 + `idempotent=` 点名兜住），
   推广属欠账（同型 NPE 已出现两次：09-06 / 09-13）。
3. ~~两处设计洞~~ → **② 已修（A 项，D-179）**：`MiningTuning.gainHorizontallyReachable` 唯一定义 +
   `MineTask`/`CollectDropsTask` 入口守卫 + `RegionLumberJob` 的 `nearRegion()` 漂移守卫
   （区外挂起、每 100 tick 告警、400 tick 后如实失败 `outside_region`）；
   断言 `capability_gate → gain_requires_proximity`。**客户端已复测（`WINDOWS_CLIENT`）**：
   `gain_requires_proximity=PASS(near=true farRefused=true reach=4.5)`；
   真起 region_lumber 后把 bot 传走 ⇒ `[Job] region_drifted … ⇒ 挂起作业`，其后**无任何作业/写入**。
   附注一修正了**我方**一处缺陷：`driftTicks` 原按"巡查次数"计（400×20=8000 tick ≈ 6.7 分钟，
   与文档声称的 20 秒不符）⇒ 已改为真实 tick 计数 + 新增 `region_returned` 恢复日志。
   **未验证**：`outside_region` 终态（需连续漂移 20 秒）。
   ① 任务驱动与**手动遥控**无互斥（`BotInputPacket` 静默覆盖任务输入）—— **仍未修**（需先定语义）。
4. `pathing/` 包语义（`MovementHelper`/`FootCellRuleCheck` 是否迁入 `core`）。

**§6.16 阶段 2（模组浅测）开工（D-181）**：判据与工具链已就位。
- 客户端现状：`mods/` 275 个 jar，**四个目标模组（Create / Extended Crafting / Mekanism / Thermal）都未安装**
  （由用户按需安装，符合"开发期依赖清零"的裁定）。
- 已有链：`/alice recipes [file]` → `config/alice-recipes.json` → `tools/recipe-graph.py`（P1 逆推）。
- 本轮补：`RecipeDump` 增 `skippedTypes` 直方图（"读不懂的是谁"= 适配器候选证据）+
  新 `tools/recipe-readability.py`（只读离线审计：读得懂/读不懂、适配器候选、配方打架/跨模组同产出；
  `--selftest` PASS）。
- **模组已由 AI 代装（D-182）**：7 个 jar（create 6.0.8 / extendedcrafting 6.0.10 / cucumber 7.0.16 /
  mekanism 10.4.16.80 / thermal_expansion 11.0.1.29 / thermal_foundation 11.0.6.70 / cofh_core 11.0.2.56），
  sha1 全 OK；`flywheel`+`ponder` 内嵌在 create、`thermal_core` 内嵌在 thermal_foundation（均已 `mandatory=true`）
  ⇒ 依赖闭合；Forge 47.4.10 与已装 JEI 均满足；无重复 modId（客户端真实 jar 12 个）⇒ 读不懂类型可干净归因。
- **顺带修掉**：同步脚本在客户端 `mods/` 留 271 份 `.bak`（241 MB）不清理 ⇒ 已加轮转（keep=2）并清理（287 MB → 50 MB）。
- **首轮导出已完成并出报告**（`docs/STAGE2_MODS_READABILITY.md`，D-183）：
  可读 **772 / ≈5293（14.6%）**，可读类型只有 5 种；**抓到真问题** —— `STATION_BY_TYPE` 原键是
  **序列化器 id**（`crafting_shaped`…）而运行时是**类型 id**（`minecraft:crafting`）⇒ 2136 条工作台配方 +
  31 条锻造配方被误跳；已改用类型 id（预测可读率 → **2939 / 55.5%**）。
  适配器候选（只列不写）：Mekanism 1171/26 类 > Thermal 652/30 类 > Create 506/15 类 > Extended Crafting 25/4 类。
  冲突检测当前**不可信**（模组 crafting 未进可读集 ⇒ 跨模组同产出=0 是假象）。
- **最终复测完成（D-183 附注一）**：可读 **2923 / ≈5293（55.2%）**，原版体系**已完整覆盖**
  （只剩 16 条"无产出"特殊配方）；标签输入 5 → **262**；产出可读 minecraft 1253 / create 940 /
  thermal 333 / mekanism 318 / extendedcrafting 79。
  **配方打架**：**575** 个产出物多路线、**48** 个跨模组（copper_ingot 11 条、iron/gold 10 条…）；
  另发现**同材料多 id 由 `#forge:*` 归一**（101 个跨模组标签、涉及 1029 物品）⇒ 知识层须按标签归一。
  适配器候选排序：Mekanism 1171/26 类 > Thermal 652/30 类 > Create 506/15 类 > EC 25/4 类（**只列不写**）。
  报告：`docs/STAGE2_MODS_READABILITY.md`。**阶段 2 目标达成**。
  诚实边界：机器专属类型互斗（crushing vs pulverizer）在跳过集里，当前看不到。

**§6.17 阶段 3-A 启动（2026-09-13，用户同意方向）**：计划见 `docs/STAGE3A_CRAFT_PLAN.md`。
阶梯：**A1 只读配方查询原语**（本轮唯一增量）→ A2 随身 2×2 合成（复用 `MenuSession` 点击原语）
→ A3 工作台 3×3（优先用现成；放置才涉及世界写入 ⇒ 授权 + 预算 + 用完即拆）
→ A4 熔炉（燃料/时间/清理）→ A5 决策层接线（`GoalAction.Craft`，LLM 只从菜单选）。
**本轮不做**：机器适配器（D-183 排序）、跨模组材料归一。
A1 判据（零参数 `alice:craft_check`）：正例给工作站+材料清单，缺料给 `missing=`，无配方给 `no_recipe`，
**纯只读**（不改背包/世界）。

**§6.18 阶段 3-A 阶梯推进（2026-09-13，A3b 落盘）**：
- **A1 / A2 / A3 客户端 PASS**（`alice:craft_check` 7/7、`alice:craft_action_check` 7/7、
  `alice:craft_table_check` 7/7，含 `no_world_write` 零写入硬断言）。
- **A3b 已实施、待客户端**（D-190）：`task/craft/StationPlacement`（授权入口 **A12**，
  `WriteReason.CRAFT_STATION_PLACE`，账本记 `TEMP` ⇒ 受"建拆同权"约束）+ 夹具
  `alice:craft_station_check` + 场景 `alice_test:craft_station_course`（**故意没有工作台**）+
  电池 26 → **27 项**（`craft_station` 步）。
  判据：`station_placed`（世界事实）/ `write_accounted`（账本 TEMP）/ `placed_table_craft`（用自己放的台合出熔炉）/
  `teardown_clean`（**方块回空气 + pending=0**）。
- **A3b 首轮客户端实测失败并已修**（D-190 附注一）：`holding_station_item=PASS mainHand=crafting_table`
  但账本记的是 `place … minecraft:cobblestone←minecraft:air [TEMP CRAFT_STATION_PLACE]` ⇒
  根因 = 用了"放一个一次性方块"的原语去放工作台（它按白名单挑中了背包的圆石）；
  且夹具失败时未清理，圆石留在世界里。修：`placeAt` **按语义分成两个重载**（一次性方块 / 指定方块，
  共用实现体）+ `findSlotForBlock` + `NO_ITEM`；夹具加 **`CLEANUP` 失败清理相位** +
  起手 `dropStale` 销掉上一轮幽灵账目。**待复测**。
- **新登记的能力缺口**：**"把物品从主背包搬到快捷栏"未实现**（Baritone `InventoryBehavior.attemptToPutOnHotbar`）。
  现状：`findSlotForBlock` 只查快捷栏，找不到就如实报 `station_not_in_hotbar`（不假装能做）。
  将来任何"必须手持某物"的动作（放机器、喂熔炉、装桶）都会撞上这一条 ⇒ 要做就做成一个独立原语。
- **未做**（仍在本阶梯上）：A4 熔炉加工（燃料/时间/取出、失败不留半成品）、
  A5 决策层接线（`GoalAction.Craft` + 缺料多路事实进候选菜单）。

**§6.19 阶段 3-A / S1 合成工作站可切换（2026-09-13，D-192）**：
- 已实施（**待客户端**）：`GridDiscovery`（通用网格发现，原版容器判据）+ `CraftStation`（站点描述符 + 玩家切换，
  `auto` 只复刻现状）+ `alice:craft_grid_probe`（零参数只读探针）+ `/alice craft station` + `bot_report` 一行；
  场景 `alice_test:craft_tab_course` / 诊断 `alice_test:craft_tab_snapshot`。
- **已装模组**（固定客户端）：精妙存储 `1.4.86.2131`、精妙背包 `3.26.3.2157`、精妙核心 `1.5.1.2335`、
  Refined Storage `1.12.4`（用户裁定保留，暂不做兼容）。
- **待实测**：① 精妙容器菜单的真实槽位表（9 格是否出现在 `-100,-100`、`isActive` 真值）；
  ② `grid_addressable_without_tab` 是否 `true`（= "点开页签与不点开没区别"，用户裁定的判据）；
  ③ 开/关菜单是否改动容器 NBT（`craft_tab_snapshot` 前后差分）。
- **下一步（S1-5 / L2）**：按实测给 `upgradetab` 定 `take/source`；独立"装配"任务把合成升级点进升级槽
  （新 `WriteReason.STATION_PROVISION` + A 表条目 + 建拆同权的复位纪律）。

**§6.20 D-192 复测（2026-09-13 17:04，客户端）**：
- ✅ 崩溃已修（`InventoryMenu(unregistered(…))`）；✅ 通用发现器在随身菜单上**逐项复现 D-163 布局**（2×2@[1,2,3,4]、result=0、inv=[9..44]）；
  ✅ 站点切换生效（`upgradetab` → `StorageContainerMenu(sophisticatedstorage:storage) slots=63`）；✅ 落地等待 `settle_ticks=2`；
  ✅ 未装升级时如实 `no_grid`。
- ❌ **回归已修**：探针不匹配自检命名 ⇒ 终态招来 LLM、自动起了两次 `region_lumber`
  （D-192 附注二）⇒ 约定扩展到 `*ProbeTask` + 探针显式 `isSelfCheck()=true`。
- 📌 **新事实**：精妙菜单服务端槽位坐标全是 `@0,0` ⇒ **x/y 不可作"是否显示"的判据**，只能用容器身份 + `isActive`。
- ⏳ **仍待证**：装上升级后 `grid_addressable_without_tab` 的真值（L3 是否要发包）。

**§6.21 D-192 第三轮复测（2026-09-13 17:08–17:10，客户端）**：**顺序问题，不是缺陷**。
- ✅ **自检修复已验证**：本轮 `latest.log` 里 `decision_request trigger=terminal:CraftGridProbeTask` **0 次**
  （上一轮 2 次、自动起了两次 `region_lumber`）⇒ `*ProbeTask` 约定 + 显式覆写生效。
- ✅ 崩溃修好（`InventoryMenu(unregistered(…))`）、切换生效（`upgradetab@46,64,306` → `StorageContainerMenu slots=63`）。
- 🔎 `no_grid` 是**当时状态下的正确答案**：17:09:58 那一轮菜单只有 63 槽
  （27 存储 + 36 玩家 = **连升级槽都没有**）⇒ 那一刻升级**还没装**。
  而**关服存档**（17:10:37，`region/r.0.0.mca` chunk 2,19 解析）显示：
  `upgradeInventory{Size:1, Items:[crafting_upgrade]}`、`numberOfUpgradeSlots:1`、
  `numberOfInventorySlots:27` ⇒ **升级现在装着**（木头箱子有 1 个升级槽，场景选型没问题）。
  ⇒ 时序是：**探针 → 装升级 → 关游戏**。
- ⏭ **只差重跑一次探针**（jar 未变，**不用重启**）：应看到 `slots≈74`（27+1 升级槽+10 合成+36 玩家）+
  `discover=OK grid=3x3 …` + `grid_addressable_without_tab=true`。

**§6.22 D-192 第四轮：**升级确实在里面，服务端菜单却不认**（真矛盾，不是时序）**
- 精确时间线（`latest.log` + `stat`）：`17:13:32` 跑场景（**重建箱子**）→ `17:13:35/17:13:50` 两次"暂停并保存"（装升级）→
  **`region/r.0.0.mca` 写入时刻 `17:13:43.95`，解析出 `x:46 y:64 z:306 id:sophisticatedstorage:chest`、
  `numberOfUpgradeSlots:1`、`upgradeInventory:[sophisticatedstorage:crafting_upgrade]`** → `17:13:57` 探针仍 `63 槽、无升级槽、无网格`。
  ⇒ **升级比探针早 14 秒**，所以**不是时序问题**：方块实体有升级槽，服务端菜单里连升级槽都没有。
- 结论：需要**现场内省**才能定位（菜单手里的包装器 vs 方块实体手里的包装器）。已加**只读反射诊断** `CraftMenuIntrospection`
  （逐项 try/catch、只调 getter/读字段、只认接口形态），探针 SUMMARY 会多出 `diag_menu_wrapper=` / `diag_be_wrapper=` /
  `diag_menu_upgradeContainers=` 三行。
- **待测**：重启后**不要重跑场景**（否则升级又被清掉）→ `/alice craft station upgradetab` → 右键探针 → 看 `diag_*`。
  另请**自己打开那个箱子**看一眼：右侧有没有"合成"页签（这条客户端事实能立刻分开"模组不给"与"我们开法不同"）。

**§6.23 D-192 第五轮：根因 = 槽位不在 `menu.slots` 里**
- 用户实验（页签关/开各一次）⇒ 两次结果相同 ⇒ **页签状态不影响菜单内容**（符合"没区别就不用管"）。
- 反射诊断：`upgradeHandler=[slots=1]`、`upgradeContainers=1 [0]`（容器存在）但 `menu.slots=63`（27+36）⇒
  上游 `addUpgradeSlot` **只设 `slot.index` 并收进自己的列表，从不 `addSlot`**（字节码核对）。
- 已修：`collectSlots`（反射收全可达槽位）+ **点击地址改用 `slot.index`**；探针表新增 `#index` 与 `*` 标记。
  对原版站点行为不变（`index` == 位置）。
- **待测**：重启后（不要重跑场景）跑探针 ⇒ 期望 `discover=OK grid=3x3 slots=[63…]`、
  `extra_slots=10`、`grid_addressable_without_tab=true`。

**§6.24 D-192 第六轮：槽位表露出真身 + 发现器四路径重写**
- 17:22 探针：`slots(74)`；`*#63` 升级槽、`*#64..72` 九个网格格（**@-100,-100**）、`*#73` 结果槽 ⇒
  证明"页签槽位在 `menu.slots` 之外"的推断完全正确；但 `matrix=-(0)` ⇒ 9 格并不挂在 `CraftingContainer` 上。
- 17:19 截图作证：玩家视角该箱子右侧"合成"页签 + 3×3 + 结果槽正常 ⇒ 模组本身没问题。
- 发现器重写为**四条证据路径**（身份 → 结果槽 `craftSlots` → 上游 `getRecipeSlots` 自述 → 内容镜像唯一性），
  并输出 `matrixBy=` / `gridBy=`；如实拒绝码新增 `grid_slots_unresolved`。
- **待测**：`gridBy` 取哪条、`grid=3x3 slots=[64..72] result=73`；点击可用性留第二步（S1-5）。

**§6.25 D-192 第七轮：`matrix=-(0)` 真因 = vanilla 成员在生产环境是 SRG 名**
- 17:26 探针：`menu_slots=74` ✓、结果槽认出 ✓，但按名字反射 `craftSlots` 拿不到矩阵。
- 修：按**类型**找矩阵（结果槽字段/无参方法），并给 `scan` 增加"按类型收集可达 `CraftingContainer`"兜底；
  诊断读物品名改**编译期调用**。`note` 增 `matrixCandidates=`。
- **待测**：`matrixBy=resultSlotFieldByType`、`gridBy=?`、`grid=3x3 slots=[64..72] result=73`。

**§6.26 D-192 第八轮：探针入电池（串联三连）+ SKIP 语义**（2026-09-13）
- 电池 27 → **30 项**，新增三步（一步右键全跑）：
  - `craft_probe_inventory`：`station=inventory` + **期望 2×2 硬断言**（回归：通用发现器在原版随身菜单上不变）；
  - `craft_probe_table`：`station=table` + **期望 3×3 硬断言**（回归：原版工作台；**零模组依赖**）；
  - `craft_probe_upgradetab`：模组站点；**模组不在 ⇒ `station_opened=false` ⇒ SKIP（不判红）**；
    本轮它是"**探测路径**跑通 + 事实如实 + 零写入"的 PASS（升级未装时如实 `grid_found=false`）——
    **3×3 硬断言要等 L2 装配层落地**（届时换 `new CraftGridProbeTask(bot, observer, 3, 3)`）。
- 电池基础设施：`Step` 增 `skipWhen`（+ `stepSkippable` 工厂）、SUMMARY **SKIP 计入绿**（会写明 `SKIP=n`）、
  `endStep()` **复位站点选择**（自检串联不许把"我选了哪个站"泄漏给下一步）。
- 探针新增期望断言（`grid_found_expected` / `grid_matches_expected`）供回归用；独立物品入口仍是纯事实探针。

**§6.27 D-193（S1-5a）：执行接入发现器**（2026-09-13，待客户端复测）
- `InventoryCraft` 4 参入口 = 发现驱动（`grid_unrecognized` 兜底，不回退常量）；`inventorySpec()` 删除。
- `TableCraft.craftWithMenu` 前置断言 = "菜单里认得出 ≥3×3 网格"（取代 `instanceof CraftingMenu`，
  为模组站点打开门）；`tableSpec()` 删除。
- **等价性证据**：探针已证明两种原版菜单的发现结果与旧常量逐项一致 ⇒ 由电池 `craft_action`/`craft_table`/`craft_station` 复测。
- **下一步 A（L2 装配层）**：bot 用菜单协议装/拆合成升级（独立 `WriteReason.STATION_PROVISION` + A 表 + 复位纪律）
  ⇒ 之后 `craft_probe_upgradetab` 升级成 3×3 硬断言。再往后的 C：模组站点真合成（摆 `#64..#72`、取 `#73`，
  并实测"Shift 右击将成品放入容器/玩家物品栏"开关与"材料来源=容器优先"两维）。

**§6.28 D-194（L2 装配层）**（2026-09-13，待客户端）
- 新写入理由 `WriteReason.STATION_PROVISION` + **A13**（容器写入维度 `consumeContainerWrite`，超限 REFUSED）。
- `task/craft/StationProvision`：装入/取回两个动作，**落点交给菜单**（QUICK_MOVE，不被接受时兜底读模组 `upgradeSlots`），
  **地址映射靠发现**（`container == player.getInventory() && getContainerSlot() == 下标`），绝不按公式猜；
  点击用 `menu.clicked`（不用 `MenuSession.click`，它会拒绝超出 `menu.slots` 的上游自管地址）。
- 夹具 `CraftStationProvisionCheckTask` + 入口 `alice:craft_station_provision_check`：
  前提（无网格）→ 装 → **关掉再开** → **能力验证 ≥3×3** → 取回 → 能力消失 + 物品回包；失败**回滚**。
- 电池 30 → **31 项**（模组不在/站点不在 ⇒ SKIP）；场景不再给玩家发升级。
- **未验证**：QUICK_MOVE 是否被上游接受、重开后 3×3 是否出现、取回后是否复原。**下一步 C**：模组站点真合成 + 两维语义实测。

**§6.29 D-195（C：模组站点真合成）**（2026-09-13，待客户端）
- **A 已客户端验证通过**：`provision_verified=true`（QUICK_MOVE 被上游接受：`fromSlot=54`）、
  `item_moved_into_container=true`、`deprovision_verified=true`（`fromAddress=63` 取回）、`item_returned=true`、
  `no_block_writes=true`、`verdict=PASS`。
- 用户确认场景："装一次 → 一直用 → 直到让它拆"（装配独立成层；夹具的"装→验→拆"只是自检）。
- C 新增：`CraftStationCraftCheckTask` + `alice:craft_station_craft_check`；
  `CraftStation.UPGRADE_TAB.provisionUpgrade`（数据驱动"该装什么"）；电池 31 → **32 项**。
- **待测**：`materials_consumed=8`、`product_produced=1`、产物去向（`product_in_player`/`product_in_container`）、
  开关值 `shift_click_into_storage`、以及"原语假设是否被打脸"（`primitive_assumption_mismatch`）。

**§6.30 D-195 附注一（C 首测失败 → 已修）**：`InventoryCraft.click` 用 `slot >= menu.slots.size()` 守卫，
把上游自管地址（64..72/73 ≥ 63）**静默拒绝** ⇒ 材料没动、产物 0，且失败码被报成 `missing_ingredient`（病名反向）。
修：守卫只拒负数 + `GridDiscovery.slotByAddress` + `placeGrid` 返回**区分过的**失败码 + 清理/找料改用发现表。
**待客户端复测**（jar `81fc53ec…`）。

**§6.31 D-195 附注二（C PASS + 产物口径修复）**：C 复测 PASS（真合成 8 圆石→1 熔炉、装/拆全绿）。
实测两维：**产物进容器**（`product_in_container=1`）、**不自动补料**（`grid_after_craft` 全空）；
原语因"数玩家背包"报 `result_not_taken` ⇒ 已把**产物计数口径**做成可注入（默认玩家背包；
`TableCraft` 用"玩家背包 + 菜单容器"）。**待查（假设非结论）**：开关读到 `false` 但产物仍进容器
⇒ 决定因素可能是**玩家侧**设置 `shift_click_open_tab`。**待复测**：32 项电池（确认口径改动无回归）。

**§6.32 32 项电池两个缺陷（已修，待复测）**：① 产物口径把结果槽**预览**算成产物 ⇒ A3/A3b 假失败
（`product+1 FAIL:result_not_taken`）⇒ 新增 `countLandedProduct`（排除结果槽与网格）；
② 偶发"服务端没开菜单"（`use_item_on result=SUCCESS` vs 成功时的 `CONSUME` + `menu_open_timeout`）⇒
失败如实打码（failure code / 当前菜单 / 重试次数）+ **重试一次**（冷却 10 tick、计时归零）。
jar `4217b7ec…`。

**§6.33 前提污染（已修，待复测）**：`setblock` 放同种方块**短路** ⇒ 箱子里的升级跨场景存活 ⇒
`craft_station_provision` 的"本来没有合成能力"前提被弄脏。修：场景先 `setblock … air` 再放箱子；
两个夹具都**自己清理前提**（provision 记 `premise_cleaned`，craft 重算数量基线）。
jar `2efb2d1e…`；**教训**："重建场景"不等于"状态干净"。

**§6.34 32 项电池第三轮：全绿 + 机制发现（已加固）**：`(32/32) ticks=3592 → PASS`、无 SKIP/FAIL/崩溃。
发现：**握着合成升级右键容器 ⇒ 物品自己装进去、GUI 不开**（`result=SUCCESS` vs 正常 `CONSUME`），
这解释了上一轮的 `menu_open_timeout`，也解释了本轮夹具为何要清理"残留升级"。
加固：开菜单前 `clearHeldUpgrade`（把选中槽换成空格）。
**阶段 3-A 工作站大块收口**。jar `e8082b1b…`。

**§6.35 D-196（A4 熔炉）**：新增 `FurnaceStation`（3 格容器 + `ContainerData` 类型字段 ⇒ 认炉子，零写死下标）
+ 夹具 `CraftFurnaceCheckTask` + 入口 `alice:craft_furnace_check` + 场景 `alice_test:furnace_course`；
电池 32 → **33 项**。判据=世界事实（stone+1 / cobble-1 / 炉内不留东西 / 零方块写入），超时把输入取回。
**待客户端**；**A4b（熔炉升级/菜单型炉子）**复用同一发现器。

**§6.36 D-197 电池分档管理**：三档（BASELINE 13 / MAIN 10 / EXTRA 10），默认 CORE=23 项；
配置唯一入口 `RegressionBatteryTask.CURATION`；说明书 `docs/BATTERY_CURATION.md`（含 6 条维护规则 + 历史表）；
入口 `alice:regression_battery`（CORE）与 `/alice battery core|full|list`；
**自校验防漂移**（漏登记/文档不一致直接判红）。顺带修 A4 的 `ContainerData` 下标映射与匿名类名。
jar `1aef94f3…`；**待客户端**：CORE 23 项跑通。

**§6.37 CORE 首跑 + 熔炉复位（已修，待复测）**：CORE `(23/23) ticks=2583`（FULL 3527 ⇒ −27%），
但自校验拿"裁剪后的步骤集"比归属表 ⇒ 把 10 个被跳过的 EXTRA 误判成 phantom ⇒ 假 FAIL（已修：比全量快照）。
用户提醒"熔炉会一直燃烧"属实（煤 1600tick vs 一次 200tick ⇒ ~1400tick 余焰，且取走燃料不会灭）⇒
夹具加 CLEANUP：取回炉内剩余物 + `setblock air`→`furnace` 复位方块（场景同款做法）。
jar `3312608d…`。

**§6.38 D-198（A4b 菜单型炉子）**：精妙"熔炼升级"= `sophisticatedstorage:smelting_upgrade`，页签是 3 格烹饪槽
+ **方法自述**（无 `ContainerData`）⇒ `FurnaceStation` 加第二条证据路径；站点模型加 `COOKING_TAB`
（容器 + 哪一种能力）；夹具复用 `CraftFurnaceCheckTask(upgradeTab=true)`；入口 `alice:craft_cooking_check`；
电池 33 → **34**（MAIN 11 ⇒ CORE 24），归属表与说明书同步。jar `2d7cf69e…`；**待客户端**。

**§6.39 D-198 附注一（A4b 首测失败→已修）**：装配成功但 `no_furnace_slots` —— 模组烹饪页签的 3 格**不在同一个容器**上。
修：`FurnaceStation` 加**路径 ③ 上游自述** `getCookingSlots()`（恰好 3 个 Slot）+ **`mayPlace` 行为探针**判定
输入/燃料/输出（结果槽两样都不收、燃料槽收煤不收圆石、输入槽收圆石），并打印 `by=`/`assignBy=`。jar `c94a2ee9…`。

**§6.40 D-198 附注二（A4b 第二轮实测：真因 = 自述者在宿主"下面一层"，已修）**（2026-09-13，客户端 19:02）
- **客户端事实**（`[FurnaceDiag]`，装配成功、菜单重开后的那一次发现）：
  - `menu=…sophisticatedstorage…StorageContainerMenu menuSlots=63`、`reachableObjects=167`；
  - `obj=CookingUpgradeContainer methods=[getSmeltingLogicContainer]` ⇒ **宿主看得见**；
  - 槽位表：`#63 (anon)/SimpleContainer containerSlot=0 item=1xsmelting_upgrade`（存储的升级槽，**未登记**）、
    `#64 SlotSuppliedHandler/… containerSlot=0 mayPlace[coal=false cobble=true]`（=输入）、
    `#65 SlotSuppliedHandler/… containerSlot=1 mayPlace[coal=true cobble=false]`（=燃料）、
    `#66 (anon)/… containerSlot=2 mayPlace[coal=false cobble=false]`（=输出）⇒ **3 格烹饪槽确实可达、形态完美**；
  - `discover=FAIL:no_furnace_slots` 且 **没有 `[Furnace] 认出炉子 by=…`** ⇒ 路径 ③ 的候选里**没有自述者**。
- **真因（字节码核对）**：`CookingUpgradeContainer.<init>` = `cookingLogicContainer = new CookingLogicContainer<>(player,
  supplyFromWrapper(w -> w.getCookingLogic()), slots::add)` —— 自述者（`CookingLogicContainer`：`getCookingSlots()` 恰好 3 格 +
  `getCookTimeTotal/getBurnTimeTotal/isCooking`）是宿主的一个**私有字段**，比 2 层遍历上限**深一层** ⇒
  **看见宿主、看不见自述者**（不是"没有这个对象"，也不是"格子数不对"）。修：候选集对**槽位宿主**
  （`GridDiscovery.Scan.owners()`，即产出过槽位的上游容器）**再展开一层字段**，并**自校验**采用
  （`getCookingSlots()` 必须给出恰好 3 个 `Slot`）；**不做全局加深**（免得把 `ServerLevel` 那类世界对象翻进去）。
- **判据顺序不变**：路径 ①（同一容器恰好 3 格）→ 路径 ③（自述 3 格）；输入/燃料/输出**仍由 `mayPlace` 行为判定**，
  不采信自述顺序；进度证据 = `ContainerData`（原版）或自述方法族（模组）。
- **动作路径补证（为什么 64/65/66 点得动）**：上游 `StorageContainerMenuBase` 重写了 `doClick`：
  先 `if (slotId >= getTotalSlotsNumber()) return;`（`getTotalSlotsNumber()` = `menu.slots.size()` + **`upgradeSlots.size()`**，
  页签槽位就挂在 `upgradeSlots` 里），然后用**被重写的 `getSlot(int)`** 解析（`getSlot(i≥slots.size())` → `upgradeSlots.get(i-slots.size())`）。
  ⇒ 地址 `64..66` 合法且落到那 3 格上 —— 与 C（合成页签）实测能点 `64..73` 真合成是**同一机制**。
- jar `dcb45b76…`（当时同步的工件；已修，当轮客户端即验证能力通过）；**待客户端**：`alice:craft_cooking_check` + CORE 电池（应 24/24）。

**§6.41 A4b 能力客户端已验证 ✓ + 新缺陷（夹具 CLEANUP 自我重入）已修**（2026-09-13 第二轮：能力证据来自 jar `dcb45b76…` 的那次实测；夹具修复在 jar `502ec5f2…`，已同步）
- **能力通过（世界事实，19:39:55–19:40:05）**：
  - `[Furnace] 认出炉子 by=ownerDeclaration(getCookingSlots) assignBy=mayPlaceProbe input=#64 fuel=#65 output=#66
    container=SimpleContainer data=CookingLogicContainer(selfReported)` ⇒ **路径③ 修好了**（§6.40）；
  - `provision_verified=true`、`input_and_fuel_placed=true input=true fuel=true` ⇒ 装升级 + 往页签槽放料**都生效**；
  - `smelted=true product+1`、`input_consumed=true input-1`、`no_half_products=true inputLeft=0 outputLeft=0`
    ⇒ **沙子真被烧成玻璃、料真被消耗、炉内不留东西**（`litTime=1600` 证明真的在烧）。**A4b 能力 = 客户端已验证**。
- **新缺陷（夹具，不是能力；用户报"一直开关箱子开关个不停"）**：`[Menu] closed reason=furnace_cleanup type=-` ↔
  `[Menu] use_item_on … result=CONSUME` **每 ~50ms 一对、无限循环**，SUMMARY 从未打印。
  **根因（控制流，确定性）**：`cleanupFurnace()` 是**每 tick 都被调用的相位处理函数**；A4b 分支里它
  `closeSession(...)` 之后把活交给需要**多 tick** 的 `deprovisionAndFinish()`（开菜单→等 OPEN→取回升级），
  但**相位仍停在 CLEANUP** ⇒ 下一 tick 又跑 `cleanupFurnace()`、把刚开的菜单关掉 ⇒ 永不收敛。
  （A4 方块炉没这问题：它一 tick 内 `setblock`+`finish()` 就完了。）
- **修（夹具层，最小）**：① 新增独立相位 `DEPROVISION`（`deprovisionAndFinish` → `deprovision`），
  `cleanupFurnace()` 一律 `advance(Phase.DEPROVISION)` 换相位，**CLEANUP 只走一次**；
  ② cleanup 加**菜单守卫**（只剩玩家自带 `InventoryMenu` 时不再跑发现器、不再拿旧地址点击 —— 原先会打 150 行 diag +
  3 次 `ReportedException`）；③ `waitSmelt()` 里发现器从**每 tick** 改成**每 40 tick**（原来刷了 **383 行**
  `[Furnace] 认出炉子`，把真正要看的行淹了）。
- **等级**：IMPLEMENTED + COMPILES；**待客户端**：`alice:craft_cooking_check`（这次应打出 SUMMARY，期望 PASS）+ CORE 电池（应 24/24）。

**§6.42 A4b 收口 ✅（客户端 PASS，jar `502ec5f2…`）**（2026-09-13 第三轮，19:49 + 19:51）
- **单跑 `alice:craft_cooking_check` = PASS**：
  `discover=OK input=#64 fuel=#65 output=#66 container=SimpleContainer data=CookingLogicContainer(selfReported)`
  `provision_verified=true input_placed=true fuel_placed=true input_and_fuel_placed=true smelt_ticks=207 output_taken=true`
  `product_delta=1 cobblestone_delta=-1 input_left=0 fuel_left=0 output_left=0 smelted=true input_consumed=true`
  `no_half_products=true burn_left_ticks_before_reset=1600 cleanup_station_open=true`
  `leftovers_returned=fuel=true input=true output=true deprovision_moved=true upgrade_returned=true`
  `no_block_writes=true verdict=PASS`（19:49:00→19:49:11，约 11 秒）
- **死循环已消失**（对照 §6.41）：`reason=furnace_cleanup` 全程 **3 次**（两个 A4b 跑 + 一个 A4 方块炉跑，各一次）、
  `ReportedException` **0 次**、`[Furnace] 认出炉子` 从 383 行降到 **23 行**（= 24 步电池各一两次）。
- **CORE 电池 = `(24/24) ticks=2809 → PASS`**，逐项含 **`craft_cooking=PASS`**（与单跑同样的 SUMMARY）、
  `craft_furnace=PASS`（原版方块炉回归无变化，`input=#0 fuel=#1 output=#2`）；
  `PROFILE=CORE baseline=13 main=11 extra_skipped=10`、无 SKIP/FAIL、无崩溃。
- ⇒ **A4b（菜单型炉子 / 精妙"熔炼升级页签"）收口**；阶段 3-A 只剩 **A5 决策层接线**。
- **USER_ACCEPTED（用户 2026-09-13 确认）**：客户端看到"只开关箱子 2~3 次、没有连续开关"，**认可 A4b 通过**。

**§6.43 A5 决策层接线：`GoalAction.Craft` + 可做清单 + 生产路径 `CraftJob`**（2026-09-13，D-199，实施完成待客户端）
- **词汇表**：新增动作 `{"action":"craft","item":"<物品id>","count":N}`（`GoalAction.Craft`）。**严格解析**：
  `item` 必须命中本轮候选菜单的**可做清单**（否则 `Refused(not_in_menu:…)` 并**回读**清单规模与截断事实）；
  在清单里但**当前站点做不了** ⇒ `Refused(station_cannot:…)`（换站点是玩家的事）；
  `count` 夹取到 `[1,32]` 并记 `clamps`。**请求里不带站点**（沿用用户裁定：工作站由玩家切换，不自动选优）。
- **候选菜单（确定性事实）**：新增 `craftable` 清单 —— 用**只读**扫描运行时配方表算出"**以当前背包持有的材料
  就能做**"的产物：产物 id + 配方类型映射出的工作站 + `can_use=`（当前选中站点能否做）+ 有界（`MAX_CRAFTABLE=40`
  / `MAX_RECIPE_SCAN=8000`，超限如实 `truncated`）。保守口径：`Ingredient` 逐个"背包里至少一个"；
  未在 `RecipeDump.stationFor` 白名单里的类型**如实跳过**（机器配方不猜）。可做清单**不占**位置类 12 项预算。
- **执行（生产路径）**：`JobRequest.Kind.CRAFT`（`productTag`=产物 id、`quota`=数量）→ `JobLauncher`
  （`CRAFT` **不发料**：合成只真消耗真产物）→ 新增 `job/craft/CraftJob`：只读查询 → 开**玩家选中的**站点 →
  等菜单 OPEN → 需要的能力没装就**按需装配**（`StationProvision` + 容器写入预算）并**关掉再开** →
  网格合成（`GridDiscovery`+`InventoryCraft`，产物口径含容器）或 3 格烧炼（`FurnaceStation`；燃料由
  `ForgeHooks.getBurnTime` 给事实，不写死煤）→ **判据只看世界事实**（产物 +N、材料 −N）→ 失败清场。
  **不自动拆回**升级（装配独立成层，拆是另一层的事）。
- **自检入口**：`alice:craft_goal_check`（零参数、**不需要场景**）+ 电池步 `craft_goal`（MAIN ⇒ **CORE 25 / FULL 35**）：
  给 4 块橡木木板 ⇒ 断言清单里有 `minecraft:crafting_table` 且 `can_use=true` ⇒ 断言越界被拒、在清单里被接受 ⇒
  用同一个 `CraftJob` 真做一个工作台 ⇒ 断言木板 −4、工作台 +1 ⇒ 清空背包。
- **等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（76 项）+ 已同步（jar `8497ec11…`）；**待客户端**（`alice:craft_goal_check` + CORE 电池 = 25/25 + `/alice ask` 观察 LLM 只在清单里选）。

**§6.44 A5 客户端实测：确定性路径 PASS + CORE 25/25 ✅**（2026-09-13 20:07–20:13，jar `8497ec11…`）
- **`alice:craft_goal_check` = PASS**（跑了两遍，`durationTicks=8`）：
  `materials_given=true menu_craftable_total=27 menu_truncated=false menu_has_target=true`
  `parse_out_of_menu=Refused(not_in_menu:minecraft:diamond_block（可做清单 27 项…）) parse_out_of_menu_refused=true`
  `parse_in_menu=Craft(minecraft:crafting_table x1) parse_in_menu_accepted=true`
  `station_cannot_probe=minecraft:netherite_boots parse_station_cannot_refused=true`
  `job_terminal=DONE job_terminal_reason=crafted:minecraft:crafting_table x1 job_failure=-`
  `planks_delta=-4 product_count=1 planks_consumed=true product_produced=true inventory_cleaned=true verdict=PASS`
  过程证据：`[CraftJob] query item=minecraft:crafting_table x1 → CRAFTABLE … grid=2x2 crafts=1` →
  `[CraftJob] 站点 OK station=inventory 随身菜单（无需打开）` → `craft OK … produced=1` → `世界事实 product 0→1 ⇒ 达成`。
  ⇒ **"可做清单 + 严格解析（越界/站点做不了都拒）+ 生产路径 CraftJob + 世界事实判据"整条链在客户端成立**。
- **CORE 电池 = `(25/25) ticks=2737 → PASS`**（`baseline=13 main=12 extra_skipped=10`），逐项含 **`craft_goal=PASS`**。
- 自检期间决策层被正确挂起（`[Goal] trigger_skipped reason=suspended trigger=terminal:CraftGoalCheckTask`）⇒ D-192 附注二的约定仍然生效。
- **LLM 路径（`/alice ask`）本轮没测到**：`/alice ask` 是 **S3 权限请示通道**（列出/答复未决请示），
  无请示时回 `[alice] 没有未决请示` 是**正确行为**，它**不发起决策**。发起决策的入口见 §6.45。

**§6.45 决策触发入口（澄清，避免下次再走错门）**（2026-09-13）
- **自动触发**：任务终态（`terminal:<kind>`）、阈值事件（`event:<…>`）、维生中断（`survival:<…>`）、
  空闲（`idle`，**默认关**，需 `idleDecisionEnabled`）。
- **手动/强制触发**：`alice:goal_director`（右键）⇒ 打印 LLM 配置 + 权威快照 + `GoalDirector.forceOnce`，
  **不受自检暂停影响**（这正是自检窗口内想单独验 LLM 时该用的入口）。
- **不是触发入口**：`/alice ask`（那是 S3 请示通道）。
- 因此 A5 的 **LLM 路径**（LLM 是否会从 `craftable` 清单里选 `craft`）**仍未驗**，下一轮用 `goal_director` 物品观察。

**§6.46 测试通路改造（D-200）**：`trigger` 进 LLM 状态 + `/alice instruct <原话>`（**直连测试通道**）+ `/alice region clear`。
- `trigger` 现在出现在快照顶层（`terminal:…`/`event:…`/`manual`/`operator`）⇒ LLM 能知道"为什么被问"。
- **直连通道的用法（连通性测试，不看菜单）**：`/alice instruct 用你词汇表里的 craft 动作做一个工作台` ⇒
  user prompt 就是这句话，解析**放行菜单校验**（包里没材料也会照做，执行层如实报 `missing_ingredients` —— 这本身是通路证据），
  日志打一行 `[Goal] directed_result raw=… → …`。**自动触发路径仍严格按"只能从菜单选"校验**。
- **登记为临时裁定**：直连通道放行菜单校验**削弱了 A5 红线**；复核触发 = A5 的 LLM 路径验证通过 ⇒ 二选一：
  回收 `/alice instruct`，或加 `LlmConfig` 闸门（默认关）并写进红线说明。
- `/alice region clear`：清掉玩家已选定区域（夹具/测试收尾），菜单里不再出现 `region:saved`。

**§6.47 A5 的 LLM 路径客户端验证 ✅（jar `8e497003…`，2026-09-13 20:39）**
```
[Goal] decision_request trigger=operator mode=directed model=deepseek-flash calledAtTick=243
[Goal] directed_result raw={"action":"craft","item":"minecraft:crafting_table","count":1} → Craft(minecraft:crafting_table x1)
[Goal] execute action=craft ok=true trigger=operator
[CraftJob] query item=minecraft:crafting_table x1 → CRAFTABLE … grid=2x2 crafts=1
[CraftJob] 站点 OK station=inventory 随身菜单（无需打开）
[CraftJob] craft OK … produced=1 consumed=[crafting_table+1]
[CraftJob] 世界事实 product 0→1（目标 x1）⇒ 达成
```
⇒ **prompt → LLM 选动作 → 严格解析 → 唯一执行入口 → 真世界变化** 整条 LLM 通路成立；
用户确认"结果符合预期"。**阶段 3-A（A1–A5）到此全部客户端验证。**

**§6.49 阶段 3-B 启动：模组机器适配（2026-09-13，D-202）**
- 用户裁定：按 D-183 优先级做机器适配器，**本次适配 = 今后适配其它模组的实验**。
- 新增 `docs/MOD_ADAPTER_PROTOCOL.md`（协议 v1）：**六步流水线** S0 枚举 → S1 只读发现 → S2 站点发现 →
  S3 能力闸门 → S4 单机闭环 → S5 收口回收；每步独立验收点、可随时停；**只读先于执行**；
  "读不懂多少"始终可见；**通用 vs 专属**分离判据（上游自述／两上游共享／纯形态可自校验）否则留模组专属；
  实验记录 8 栏模板 + 反模式清单 + 探针回收纪律。
- **第一个实验 = Mekanism**（被跳过 1171 条 / 26 类型；`crushing` 210、`enriching` 142、`injecting` 76、
  `combining` 62、`purifying` 28）。**第一最小闭环 = S0 + S1（全只读）**：`docs/MEKANISM_FACTS.md` +
  查询层机器路线 + 零参数探针 `alice:machine_probe`。S2 的机器范围**等 S1 读数再定**。
- 等级：协议 IMPLEMENTED；S0/S1 未开始。

**§6.50 电池整理后的红项调查（进行中）**（2026-09-13）
- **事实**：整理后 CORE 首跑 `(14/17) → FAIL`，红项 = `craft_furnace`（`smelted=false` 超时 421 tick、`input_consumed=false`、
  `discover=OK input=#0/#1/#2`、`input_placed=true`）、`craft_cooking`（同形，`#64/#65/#66`）、
  **`transfer`（BASELINE，上一轮 25/25 时 PASS）**：`segment_stall … onGround=true delta=0,0,0 input=forward=1.00 → SEGMENT_TIMEOUT`。
- **反证**：同一轮里 `pathing`/`mine_job`/`lumber_job`（都要移动+世界推进）PASS ⇒ **不是"服务端不 tick"**。
- **22:53 单跑复测**：`alice:craft_furnace_check` **PASS**（`smelted=true smelt_ticks=200 product_delta=1 furnace_reset=true`；
  首次 `furnace_found=false` 是**场景没摆**，不是缺陷）⇒ **in-battery 失败未复现**。
- **当前假设（未定论）**：① 序列/残留污染（本轮把 8 步从 CORE 挪走 ⇒ 前置清场/场景序列变了）；
  ② 那一次会话的世界状态（上一轮 `/alice instruct` 起过 `region_lumber`、bot 被反复传送）。
  首要可检验机制 = **"发现时的菜单 ≠ 点击时的菜单"**（点击被接受但机器里没料 ⇒ 点了错菜单；
  `craft_cooking` 的 64..66 与精妙**合成页签** 64..73 地址重叠，正好同族）。
- **下一步（探针先装再测）**：`[Furnace] menu#<identity> slots=<n>`（discover 时 + 每次 placeOne 前）+
  transfer 侧一行输入/前方方块事实；然后**重跑 CORE**：若复现 ⇒ 用探针定位"菜单身份"；若不复现 ⇒ 记为**偶发/世界状态型**，
  但仍要补一条"步骤前清场"的守卫（不允许靠"重跑就好了"收口）。
- **判据纪律**：不因为"单跑绿了"就宣布整理成功；CORE 17/17 未确认前，整理视为**未收口**。
- **22:50 复跑（重启客户端后）= `(14/17) → FAIL`，同样三项**（`craft_furnace`/`craft_cooking`/`transfer`）⇒ **可复现 ×2**。
- **关键对照**：同样的这三步在 **CORE 25（20:07）是 PASS**（`(25/25) ticks=2737 → PASS`）⇒
  **唯一变量 = 我把 8 步从 CORE 挪走** ⇒ 结论升级为：**"撤走的步骤在替这三步做前置/清场"**（是我的整理引入的回归，
  **不是**世界状态偶发）。⇒ **按证据的处置是"先恢复、再逐条撤"**：不能靠"少跑几步"换来假绿。
- **判别实验（23:06，`/alice battery full`）= `(34/35) ticks=3964 → FAIL`**：
  **`craft_furnace=PASS craft_cooking=PASS transfer=PASS`** ⇒ **结论确认**：CORE 17 的那三项红 = **缺前置**
  （撤走的 8 步里有谁在替它们清场）；FULL 里它们跑在"完整链条"中就是绿的。
  **同时暴露同一疾病的第二个受害者**：唯一红项 = **`capability_gate=FAIL reason=foreign_break_attribution`
  （`ticks=1`）** —— BASELINE 的闸门步在**第一 tick**就断言失败：它假定"外来破坏归因"的前提是干净的，
  而 FULL 的**顺序**里它前面刚跑过 `region_maintain`/`transfer`（真破坏/真写入）⇒ **前提被前面的步骤污染**。
  （CORE 17 里它反而绿，只是因为 `transfer` 那时**失败了**、写得更少 ⇒ "绿"是假的。）
- **共因（本轮真正的收获）**：**电池内存在隐式的跨步耦合**——夹具互相依赖"上一步顺便清场"。
  我的整理不是"引入了 bug"，而是**把隐藏的耦合暴露出来**；`capability_gate` 证明这病不止一处。
- **处置（按证据、可复现）**：
  ① **先把 8 步恢复进 CORE**（回到可复现的绿基线 25 项），不为"少跑几步"接受假绿；
  ② 给每个夹具**显式自证前提**（当前菜单=玩家自带菜单 / bot 在起点且 onGround / 用到的方块实体状态已复位 /
     账本与归因检查**限定在自己的时间窗内**），做到**顺序无关**；
  ③ 然后**逐条**把步骤移出 CORE，**每移一条复跑一次**，绿灯才继续；
  ④ 把这条写进 `docs/MOD_ADAPTER_PROTOCOL.md` 的反模式（"**不许依赖上一步顺便清场；前置必须显式自证**"）。

**§6.51 阶段 3-B 启动与 S0/S1/S2（2026-09-13/14，D-202~D-206）**
- **方法先行**：`docs/MOD_ADAPTER_PROTOCOL.md`（六步流水线 + 通用/专属分离判据 + 记录模板 + 反模式）；
  用户裁定"本次适配 = 今后适配其它模组/附属模组的实验"。
- **S0**（离线，真数据）：`docs/MEKANISM_FACTS.md` —— Mekanism **26 类型 / 1171 条**（`crushing` 210、
  `pigment_extracting` 178、`painting` 176、`enriching` 142、`sawing` 124…）；总量随会话变化已标出处。
- **S1**（只读，客户端验证）：`MachineRecipeFacts`（问上游 + 自校验）→ `RecipeQuery.MACHINE_ROUTE`
  （有出处路线：机器类型 + 材料）；`CraftJob` 对机器路线**如实拒绝** `machine_recipe_unsupported:not_executable`。
  实测样例：`mekanism:dust_lithium → MACHINE_ROUTE station=mekanism:crystallizing`；
  `mekanism:dust_iron → station=mekanism:enriching perCraft=12 mats=[minecraft:raw_iron_block x1]`。
  期间修掉两个真 bug：机器分支未按目标过滤（`machineTypes` 收全表）、非物品输入的 `mats=[]` 歧义。
- **S2**（只读，客户端验证）：机器站点认得出（命名空间方块 → `MekanismTileContainer` → `getTileEntity()`）；
  **进度=上游自述**（`getScaledProgress`/`getOperatingTicks`/`getActive`）。
- **S5 回收**：两支临时入口（`alice:machine_probe`、`alice:machine_station_probe`）已删除，
  任务转电池步 `machine_route` / `machine_station`（机器不在 ⇒ SKIP）⇒ **CORE 25 → 27**。
- **S3**（只读，**客户端验证 ✅**，2026-09-14，D-209，commit `fb979e5` + `edf5643`）：
  ① 真源 `decision/MachineMap.java` **27 行 = 上游全部类型**（23 有站点 + 4 无站点：`evaporating`
  多方块、`energy_conversion`/`gas_conversion`/`infusion_conversion` 无单方块站点）；
  ② `Route.station` 由配方类型 id → **机器方块 id**（`RecipeQuery` 两行，类型仍留在 `Route.type`）；
  ③ 探针改「按表认机器」：每台一组 `m{i}_*`，断言**菜单类 == 已实测登记值** +
  **方块实体自述配方类型 == 表里的类型**（`getRecipeType()`→`getRegistryName()` 只读反射）；
  ④ 生成视图 `docs/MACHINE_MAP.csv` + 双向闸门 `tools/check-machine-map.sh`
  （Tier A 结构 / Tier B `javap` 读上游 jar：**27 类型 ↔ 27 行双向一致**；反向验证删行、改 `crusher_typo` 均立刻 FAIL）；
  ⑤ 场景加第二台 `mekanism:crusher`（@66,64,307）作"加机器不改 Java"的活证据，已同步进存档 datapack。
  **第二轮实测（`latest.log:2941`/`:2953`/`:2971`/`:3680`）**：`按表找到 2 台`、`m1_binding=true m2_binding=true`、
  电池 `(28/28) ticks=2789 → PASS` ⇒ 表真的成了"哪台机器"的判据。
  **实测纠正两条口径**（已改，`COMPILES` + 闸门全绿，待下次复跑确认显示）：
  ① 实测类型 **26** ≠ 表 27，差的是**零配方的 `mekanism:smelting`** ⇒ 探针改为对表行**完整划分**
  （`with_site_confirmed` 22 + `with_site_unobserved` 1 + `no_site` 4 + `row_block_missing` 0 = 27）
  + 分桶守恒自检，`with_site_unobserved` 只报事实不判红；② **`menuClass` 不区分机器**（两机同一个
  `MekanismTileContainer`、槽位表逐项相同）⇒ 身份只能靠 `m{i}_binding`；crusher 菜单类已按观察值回填。
  **未覆盖如实登记**：只登记基础机（`crushing` 的 4 档工厂变体在 `note` 点名未入表）。
  **待客户端**：`/alice authz` 的 L2 行（**仍从未在客户端敲过**）+ 下次复跑看新口径显示。
- **S4**（**3-B 的第一次写入**，`COMPILES` + 闸门全绿 / **待客户端验证**，2026-09-14，D-210）：
  ① 夹具 `MachineCycleCheckTask` + 零参数物品 `alice:machine_cycle_check`：认机器（表）→ 开菜单 →
  挑一道**物品进出**的配方（按 id 排序取第一道，不写死）→ shift-click 放料 → 等**真实进度** → shift-click 取产物
  → 断言 `product_before/product_after`、`machine_emptied`、`input_consumed`；
  ② **不新造授权**：容器写入维度 `WriteBudget` + 理由 `CONTAINER_TRANSFER` + requester `machine-cycle`
  （矩阵前缀规则登记为 `CONTAINER`）；**不猜槽位**：QUICK_MOVE 让菜单决定落点，成不成**只看结果**；
  ③ 场景 `machine_course` 在富集仓下方加**真实电源** `mekanism:creative_energy_cube`（纯数据 ⇒ 不改 Java）；
  夹具先等场景电源，真喂不上才**前提补电**并**必然留痕** `energy_source=…`（D-210）；
  ④ 失败码全是可归因的：`machine_absent`/`machine_out_of_reach`/`no_recipe_with_item_io`/
  `container_write_refused`/`feed_*`/`take_*`/`no_product_in_600ticks:no_energy|progress_stalled`。
  **v1 边界**：单机单配方 + 站位用夹具传送；**内核寻路走到机器旁 = S4 v2**。
  **客户端状态（第四轮实测）**：S4 物品**没跑**（日志里 `MachineCycle` 0 次）⇒ 待下一轮。
- **R1 收口：容器写入进策略表**（2026-09-14，D-211，`COMPILES` + 六闸门 PASS / 待客户端）：
  ① **矩阵首次经手容器写入**：挂点 = `WriteBudget.consumeContainerWrite`（所有已接线容器写入的必经之处，
  容器写入**不产生账本条目** ⇒ 放置类的执行期复验在容器侧没有对应物，这就是它的对应物）；
  顺序**先策略后预算**；口径与移动授权对齐（**未登记 ⇒ 留痕不拒**；**已登记但未声明 ⇒ 硬拒**）；
  拒绝权默认**武装**，留一行开关 `setContainerRefusalArmed` 作回退把手 + 给夹具断言两种模式。
  ② **可执行覆盖**：`docs/authz/CONTAINER_WRITE_SITES.csv`（**20 个调用点**，每行 `category`+`why`，
  `gated=no` 必须写理由）+ `tools/policy-map.py` **断言⑦**（未登记点 / 谎报 `gated=yes` /
  陈旧登记行 / `enforced_by` 指向不存在或没真调用 `consumeContainerWrite` ⇒ 全 FAIL；四条负例已实测都红）。
  ③ **覆盖审查顶出的两个真缺口（已补）**：`CraftJob`（**生产**熔炼：往炉子放料/取产物**从没记过账**）、
  `MenuProbeTask`；表随之在 **CRAFT 行（P-05/P-16）声明 `CONTAINER_TRANSFER`**。
  ④ **已知边界（未做，如实登记）**：`InventoryCraft` 结果槽 shift-click 在开着容器菜单时会把产物放进容器
  （实测 `product_in_container=1`）而不过闸——接它需要给该方法 grant 参数；`TransferFixture` 在隔离层
  直接驱动搬运原语，**有意**不过闸。
  ⑤ **复核触发**（下一轮电池）：`container_checks=N`（恒 0 ⇒ 挂点没接上）、`container_refused=0`
  （>0 ⇒ 有生产路径被硬停，先读归因）。
- **夹具纪律**（用户要求）：场景夹具**自带传送 + 结束复位**（PLAYBOOK §5.0d）；审计已无缺口。

**§6.52 授权模型与过程开销：修订方向（2026-09-14，D-207 + 两轮工作流审查）**
- **代码侧待办**：① 集中策略表（区域×任务类别 → TEMP/KEEP + Movement 集合 + 预算；默认 PROTECTED）；② 野外默认放开 `PILLAR/FALL/DOWNWARD`；③ `miningApproach` 改"按条件放行"（**先 A/B 客户端证据**）。
- **过程侧待办**：记账 ≤15 行/条；收口集中更新文档；验证批量化；`tools/check-fixture-hygiene.sh`；`docs/WORKFLOW_RULES.md` 索引；"待验证"单一队列。
- **待用户拍板**：验证等级 5→3；`AI_TEST_MATRIX` 去留；规则日落机制。
- **审查量化留档**：09-14 当日 19 提交（6 纯文档/13 含代码）、`docs +436` vs `src +864`；场景函数 102 个、电池仅引用 8 个；决策文档 62 处"待客户端"。
- **第五轮：R1 容器闸门活了 + S4 首次跑通 + 抓到一个"假前提"**（2026-09-14，客户端验证）：
  ① **R1**：`container_gate_live=PASS container_gate_armed=PASS containerGate=armed container_checks=13
  container_refused=0`（`latest.log:3206`）。**13 = 各步 `containers=N/32` 之和**（provision 2 + craft 2 +
  furnace 3 + cooking 4 + transfer 2)⇒ 闸门覆盖面与预算覆盖面**逐点一致**，不是恒 0 的死开关；
  每步 `refusedContainers=0` ⇒ 无生产路径被硬停。日志里唯一那条 `denied action=container`（`:3201`，
  `by=walk-to:CONTAINER_TRANSFER`）是 `container_gate_armed` 负例故意打的（故 WARN 与 `container_refused=0`
  不矛盾：负例跑完 `finally` 还原开关 + 快照还原观察样本）。⇒ **D-211 的两条复核触发都已解除**。
  ② **S4**：`[MachineCycle] SUMMARY … binding=true feed_verified=true in_machine=1 active_seen=true
  progress_ticks=199 product_after=1 product_landed=true machine_emptied=true input_consumed=true
  container_writes=2 reset=true verdict=PASS`（`:3811`）；两次写容器走同一套闸门+预算
  （`scope=…#1478:MachineCycleCheckTask … containers=2/32 refusedContainers=0`，`:3815`）。⇒ S4 = `WINDOWS_CLIENT`。
  ③ **场景电源前提是假的**：第五轮判为"朝向"并改 `[facing=up]`（D-212）；**第六轮带着 `[facing=up]` 重跑仍是
  `energy_at_open=0.0` + `api_precharge`（`latest.log:281`；存档 `r.0.0.mca` 里 `facing:"up"` 已核实生效）
  ⇒ 朝向不是根因（D-213）**。真因 = **创造能量方块放下时自带电量 0 J、且永远充不进电**
  （`BasicEnergyContainer:52` 初值 ZERO + 创造档 `insert`/`extract` 强制模拟；`TileComponentEjector:166` 跳过空容器），
  上游设计里"空变体"就是 power sink、"满变体"靠**放置时读物品 NBT** 灌入（`BlockMekanism:310-314`）。
  证据 = 存档里的对照组（同一次保存）：机器 `EnergyContainers=[{"Container":0,"stored":"3990000"}]`、
  方块 `EnergyContainers=[]`。已修场景加 `data merge block … {EnergyContainers:[{Container:0,stored:"4000000000"}]}`
  （命令 10→11 条，**纯数据、不重编 jar**）。勘察全文 `docs/reviews/2026-09-14-S4电源根因勘察.md`。
  **✅ 2026-09-14 第七轮已重验通过（`WINDOWS_CLIENT`）**：`/reload` → `/function alice_test:machine_course`
  （11 条命令，`latest.log:187`）→ 右键 `alice:machine_cycle_check` ⇒ `:213`
  `energy_at_open=20000.0 energy_source=cube（场景电源，未补电）`（**不再是** `api_precharge` 的 4.0E6）、
  `progress_ticks=199 product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true
  verdict=PASS` ⇒ **电来自场景本身，D-213 判据成立**（D-212 因果判定确认作废；朝向修正保留）。
  语义别读强：`energy_source=cube` 证的是"场景把电送上了"（开机 `energy_at_open > 0`），不是"认出了 cube 方块"。
  ④ 旁记：`K4=OK(… 写入类例外=43)`（上轮 56，交替，两次都 `K4=OK`，未取证，暂不动）。
  ⑤ **S4 升级为电池步（D-197）— ✅ 已执行（2026-09-14 第七轮）**：条件（③ 重验为 `cube`）已满足 ⇒
  新增 `machine_cycle`（MAIN，`stepSkippable`，预算 1600 > 任务自身 `MAX_TICKS` 1400），
  电池 **CORE 28→29 / FULL 38→39**（`RegressionBatteryTask` CURATION + `docs/BATTERY_CURATION.md` + 历史表已同步）。
  **✅ 该步客户端绿已确认（2026-09-14 第九轮）**：新会话（新 jar）⇒ `machine_cycle=PASS ticks=210 idempotent=true`
  （`latest.log:3130`）、整轮 `(29/29) ticks=3108 → PASS`（`:3842`）；步内仍是 `energy_source=cube（场景电源，未补电）`
  + `containers=2/32 refusedContainers=0` ⇒ **升格闭环完成**。
  ⑦ **✅ 已关闭（2026-09-14，随 S5 收口的 jar 重编一并做）**：用户可见文案里的项数**不再写死** ——
  新增 `RegressionBatteryTask.coreStepCount()`（项数唯一出处 = `CURATION`），`RegressionBatteryItem` 的启动文案
  与类注释、`AliceItems` 注释全部改为**现算**（原写死"26 项"而实测 `(29/29)`）。
  同一轮还归位了一处**错位注释**（`assignCraftCookingCheck` 的 A4b 说明原本挂在机器探针上方）。
  新 jar `sha256=d5e49c1b4ec7e8f48b634c97f912f4513c5423ef4952871b569899d44fcfc5ef` ⇒ **客户端需重启加载**。
  ⑥ **✅ 已关闭（2026-09-14 第七轮）**：随本次因功能需要重编 jar 一并改了 `MachineCycleCheckTask` 类注释 ② ——
  写明"创造方块放下就是 0 J（`BasicEnergyContainer.stored = ZERO` + creative 强制 SIMULATE ⇒ 灌不满也放不出），
  场景用 `/data merge block …` 把电写进方块实体（`load()` → `setEnergy`，绕过插入守卫；灌进去后 extract 仍 SIMULATE
  ⇒ 永不为空 = 真无限电源），`api_precharge` 只是兜底"，并新增"能量判据的准确含义"一段。
  新 jar `sha256=b290b8b3a1276915feee509f6e7203aad7ca16ad2454e742b5f2d14dc3a7984f`（已同步到客户端 `mods/`）；
  **客户端需重启才会加载新 jar**。
  ⑧ **✅ 已关闭（2026-09-14 第十轮复核，全绿）**：启动文案打 **"29 项，约 2~4 分钟"**（不再写死 26 ⇒ 台账⑦ 修复生效）、
  `[Regression] SUMMARY … machine_cycle=PASS … (29/29) ticks=3083 → PASS`（`latest.log:3849`）。
  **"探针零残留"的判据由 Forge 自己给出**：进世界时报 `[ERROR] Unidentified mapping from registry minecraft:item
  alice:machine_cycle_check: 3405` + `missing registry entries`（另有 stats 一条非法统计警告）⇒ 该物品**已不在注册表**。
  ⚠️ **这两条是"删注册物品"的一次性自愈警告，不是回归**（已用**磁盘状态**证实，不靠日志措辞）：退出后
  `level.dat` 里 `grep machine_cycle_check` = **0**、`stats/<uuid>.json` 里该键已消失 ⇒ 详见 D-215 附注一，
  **下次回收物品时别在复核轮里把它误判成"回收搞坏了什么"**。
  旁记（第十一轮**已取证定性 ⇒ 结案，不再是开放项**）：`K4 写入类例外` = **`PathingStats` 的全局事件计数**
  （`RegressionBatteryTask.k4Delta` 用 `totalsSnapshot()` 减去电池开始时的基线），计的是
  "A* 到达目标格但 `canStandCentered=false`、**且进入边是写入类**（`plannedBreaks/plannedPlaces>0`）"的
  **搜索事件次数** —— 见 `AStarMovementSearch:112-122`（**纯通行边**走进来的那种才是真异常 `goal_not_standable`）。
  **它按构造就不参与判定**：`k4Ok = goalBad==0 && finalBad==0`，`postWrite` 只被打印。
  实测 43 / 56 / 56 / **58**（第十一轮 `latest.log:3924`）⇒ 波动 = 每轮**搜索次数**差异
  （挖掘/掉落收集类步骤的规划次数本来就不稳定），**不是语义变化**，也不再指示"上一轮遗留脚手架"。

## 阶段 3-B 后续（2026-09-14，(c) 起步 + (a) Thermal S0 的产出）

  ⑨ **✅ 已关闭（2026-09-14，第十四轮）—— `--target/--routes` 已真做**：`tools/recipe-readability.py` 现在注册了
  `--target <item>`（聚焦模式：只打印反查段，不打印完整报告）与 `--routes`（逐条明细 + 输入），
  `find_routes()` 统一处理 `output` 的字符串/对象/列表三种形态（原版配方实测 **3689/3689 全是字符串**，
  兼容分支是保险），**自证断言已加**（`--selftest` 现要求铁锭反查得到 **2** 条原版路线且 type 集合正确 ——
  没有断言的实现等于没实现）。实测验收：`--target minecraft:netherite_ingot --routes` ⇒ **7 条 / 3 个命名空间**
  （minecraft / mekanism / thermal，含熔炉与高炉）—— 这正是 S1/S2 要的"跨模组打架"视图。
  **并把口径边界写进输出**（[Target] 段固定打印一行）：机器类型在导出里**只有计数、没有产出字段**
  ⇒ **机器路线（`thermal:press` / `mekanism:crushing` …）本查询一条也看不到**，要含机器路线必须走运行时
  `RecipeQuery`。历史：docstring 曾宣传这两个参数但 `main()` 未注册（实测 `error: unrecognized arguments`）；
  **修的是实现，不是删承诺**。
  ⑩ **Thermal S1 的两个前置事实（如实登记）**：
  ① **没有上游 sources jar、也没有本地反编译产物** ⇒ 与 Mekanism 不同，S1 要**先解决"去哪拿源码/字节码"**
  （本机可 `unzip` 静态 jar 读 data/ 与 class，但"问上游自述"那套判据仍建议对着源码核，见 D-036 的取证纪律）；
  ② **✅ 已关闭（2026-09-14，第十三轮）—— `alice-recipes.json` 已就地重导**：游戏内 `/alice recipes` ⇒
  `recipes=3689 skipped=2379 tags=693 skippedTop=thermal:press=227,mekanism:crushing=210,mekanism:pigment_extracting=178`
  （`config/alice-recipes.json` 1527420 字节，mtime 2026-09-14 17:15）⇒ **现在的表就是"当前客户端真实分布"**。
  重导复核：Thermal 仍 **30 类型 / 652 条**（与 S0 旧导出**逐项一致** ⇒ 结构事实稳定）；可读 2923→3689 的增量来自
  后装的 refinedstorage / sophisticatedcore|storage|backpacks，而**它们的配方全部落在原版可读类型里
  （`skippedTypes` 里 0 条）**，跳过量只 +9。新发现见 `docs/THERMAL_FACTS.md` §6（**30 个类型里约一半是
  燃料/催化/增幅类修饰类型，不是机器** ⇒ S1 枚举不得按类型数建行）。
  ③ **✅ 已关闭（2026-09-14，第十四轮）—— `+34` 两个机制各占一边，逐项闭合**：
  **`+52` = 一个被整片漏掉的内嵌 jar（JiJ）**：`thermal_foundation` 里藏着
  `META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar`（4.4 MB，**是内容不是库**：11 个 `device_*` 方块 + 22 条 device/fuel 类型配方全在里面）。
  `mods/*.jar` 逐个 `unzip` **看不到内嵌 jar**（只显示为一行 `META-INF/jarjar/xxx.jar`）⇒ 这是"按资源枚举设备"最容易踩的坑；
  全客户端只有 `create`（4 个内嵌 jar，纯库，`data/*/recipes` 各 0 条）与 `thermal_foundation` 带 JiJ，**Mekanism 没有**
  ⇒ 既有 Mekanism S0/S1 结论不受影响。**`−18` = 配方条件在加载期筛掉**：`smelter_recycle` 22 条**全部**带
  `conditions:[{type:"cofh_core:tag_exists", tag:"forge:armor/<金属>"|"forge:tools/<金属>"}]`，运行时只有 **4** 条存活 ——
  而 Alice 自己的 `itemTags`（693 个）里**恰好只有这 4 个标签存在**（`forge:armor/{gold,iron}` + `forge:tools/{gold,iron}`），
  其余 18 个（9 种金属 × armor/tools）不存在 ⇒ 条件不通过。**两侧独立吻合：`670 − 18 = 652`。**
  教训入册：**"静态 jar 里有多少条配方" ≠ "运行时有多少条"**（条件会在加载期筛），引用静态计数时必须声明这一点。
  完整证据与复算命令见 **`docs/THERMAL_S1_FACTS.md`**。
  ⑪ **✅ 已关闭（2026-09-14，D-217）—— (c) 增量 2 施工清单已全部落地**：`CraftJob` 的 `MACHINE_ROUTE`
  不再 `not_executable`，改为**数据驱动的执行准入**（`MachineMap.executable(...)`，只升 `mekanism:enriching`
  一行 ⇒ `CraftJob` 只驱动 `EXECUTABLE` 的行）。闭环本体抽成 `task/craft/MachineCycle`（**夹具与生产同一份**，
  夹具变薄壳），两条红线都落地：① **不补电**（执行器里没有造能量的代码；`EnergyTopUp` 只有夹具实现、
  生产位置传 `null`）+ **门禁 `tools/check-precharge-containment.sh`** 三条断言（含反向测试：注入一次
  `precharge(` ⇒ 立刻红）；② 目标机器取 `route.station()`，多输入/化学品输入如实拒绝。新电池步
  `craft_machine` ⇒ **CORE 29→30 / FULL 39→40**；六道门禁 PASS（`check-policy-matrix` 顺带抓到写入点登记的
  漂移：`docs/authz/CONTAINER_WRITE_SITES.csv` 已从夹具改登记 `task/craft/MachineCycle`）。
  **剩余如实边界**（不是台账项，是范围）：机器路线目前只支持**单物品输入**的机器。
  ⑫ **`[Recover] session=<id> movements=N …` 的标签与载荷不符（观测缺陷，第十一轮踩到，未修）**：
  该行由 `PathSession:273` 打印，载荷来自 `RecoverabilityReport.describe()` —— 而后者是**自服务器启动累计**
  的静态计数（`RecoverabilityReport:13` 明确"不随会话清空"，`reset()` 只由 `recoverability` 自检步调用）。
  ⇒ 第十一轮 `machine-walk-0` 那行的 `movements=167`（含 `PILLAR=13 / DOWNWARD=15 / BREAK_*`）**不是这段路的**，
  是当时的**全进程累计**；**别拿它给某次寻路定罪**（该步是否真写入，看同一步 `WriteBudget`：实测 `breaks=0 places=0`）。
  最小修法 = 会话开始记基线、打印**每会话增量**（或至少把措辞改成"累计"）。
  ⑬ **✅ 已关闭（2026-09-14，D-218）—— 查询层现在按"执行准入"优先挑机器路线**：`RecipeQuery` 返回
  `MACHINE_ROUTE` 前把候选路线**只排不删**（主序 `hasExecutor(station)`、次序 `recipeId` ⇒ 结论**确定**，
  不随配方管理器迭代序漂），并在 `Result.note` 里写清"挑了哪台 / 有没有准入 / 同类共几条、其中几条有准入"。
  **不删**保住了 S1 的既有语义（读得出路线就报得出），执行与否仍由 `CraftJob` 按 `EXECUTABLE` 如实判定。
  **观测点** = 电池步 `craft_machine` 新增的 `fallback_used`：修好后首选候选应直接可用 ⇒ 期望
  **`fallback_used=false` 且 `target=minecraft:clay_ball`**（第十二轮的 `true` + `target=soul_soil` 即本问题的证据）。
  变回 `true` ⇒ **先查是不是又有"未准入"的机器抢了同一产出**（那是产品问题，不是回归）。
  ⑭ **✅ 已关闭（2026-09-14，第十五轮）—— 探针的采样命名空间改为"按 `MachineMap` 表推导"**：
  原状：`MachineProbeTask.NAMESPACE` 是单个常量 `"mekanism"`（`MOD_ADAPTER_PROTOCOL` §6 早标成"**半通用**"）。
  加了 32 行 Thermal 后，探针仍只枚举 `mekanism` 的配方类型 ⇒ **26 个 Thermal 站点行全部落进
  `with_site_unobserved`**（第十四轮实测 `22 + 27 + 10 + 0 = 59`，守恒成立、`row_block_missing=[]` 通过），
  而那个桶的日志文案写的是"上游 0 配方或模组集差异" ⇒ **会把"探针没采样这个命名空间"读成"该模组没有配方"**。
  **修法**（最小）：① `adoptedNamespaces()` 从 `MachineMap.rows()` 的 `typeId` 前缀推导（表是唯一真源，
  加模组不用改探针）⇒ `MachineProbeTask` 现在 **0 处 `mekanism` 代码字面量**；② 过滤器从 `startsWith` 改为
  "命名空间 ∈ 表里出现过的那些"；③ 新增**逐命名空间一行**覆盖计数
  （`[MachineProbe] namespace=<ns> types=… type_recipes=… samples=… unreadable_via_vanilla=… upstream_readable=…
  machine_output_not_item=… input_readable=…`），SUMMARY 保留全局合计并把 `namespace=` 改为 `namespaces=[…]`；
  ④ `failureReason()` 的 `_absent` 后缀**保留**（电池侧判据是 `contains("_absent")`），前缀改为
  `machine_namespaces_absent`。
  **第十五轮期望（新基线）**：`namespaces=[mekanism, thermal] types=56 type_recipes=1823`，
  `with_site_confirmed=**46** with_site_unobserved=[mekanism:smelting, thermal:brewer, thermal:hive_extractor]`
  （分桶 `46+3+10+0=59`）—— 即 `with_site_unobserved` **回到它文档里的本义：只有真·零配方**。
  算术来源：有站点 49 行 − 零配方 3 行（`smelting`；Thermal 侧 `brewer`/`hive_extractor` 上游有类型但本次零配方）。
  **顺带入册的教训**：**"没被采样"与"不存在"必须能从日志上区分开** —— 一个统计字段混着两种含义，
  迟早会被读成错的那一种。
  **✅ 第十五轮实测复核通过**（`latest.log:3007`/`:3177`/`:3178`）：`命名空间=[mekanism, thermal] 类型=56 条数=1823`、
  `with_site_confirmed=**46**`、`with_site_unobserved=[mekanism:smelting, thermal:brewer, thermal:hive_extractor]`
  （`46+3+10+0=59`）、`row_block_missing=[]`、`unmapped=[]`、`(30/30) ticks=3353 → PASS`；
  逐命名空间行与**手算预测逐项相同**（`mekanism 26/1171`、`thermal 30/652`）。
  ⇒ "Thermal 32 个类型里哪 30 个在运行时真有配方"**从此有自动化证据**。
  ⑮ **✅ 已关闭（第十五轮发现 → 第十六轮修复并客户端复核）—— Thermal 机器配方的"读法"缺失：不是读不出，是访问器名字不同**：
  逐命名空间行里 `namespace=thermal … upstream_readable=0 input_readable=0 machine_output_not_item=57`（57/57 抽样全空），
  而 `namespace=mekanism` 是 `upstream_readable=20 input_readable=30`。**根因已取证（javap，不是猜）**：
  Thermal 的机器配方类（`PressRecipe` / `PulverizerRecipe` / `CentrifugeRecipe` / `CrystallizerRecipe` /
  `RefineryRecipe` / `PyrolyzerRecipe` / `CrucibleRecipe` / `PulverizerRecycleRecipe` …）**都 `extends ThermalRecipe`**，
  访问器是 **`getInputItems()`（`List<Ingredient>`）/ `getInputFluids()` / `getOutputItems()`（`List<ItemStack>`）/
  `getOutputItemChances()`（`List<Float>`）/ `getEnergy()` / `getXp()`**；
  而 Alice 现在反射问的是 `getOutputDefinition`/`getOutputs`（输出）与 `getInput`/`getItemInput`（输入）——
  **Mekanism 的名字**。⇒ 查询层因此给不出 Thermal 的"机器产线（上游自述可读输入/输出）"路线。
  **⚠️ 修之前必须先想清楚的一条**：**Thermal 的产出带概率信息**，实测 **146 / 670** 条配方**声明了 `chance` 字段**，
  取值跨 **0.05 ~ 12.5**（`2.0` 出现 50 次）⇒ **不在 [0,1]**，**它本身不是"概率"**，语义未取证
  ⇒ **只读 `getOutputItems()` 会把这类产出写成"必然产出"**，那是**过度承诺**（红线：未知语义默认只读、不猜）。
  最小修法 = 扩展名族 + **配套读 `getOutputItemChances()`**，但**只声明"有概率信息"、不解释数值**（语义另立 ⑯）。
  **验证点**：下一轮电池里 `namespace=thermal upstream_readable>0 input_readable>0`，且有概率信息的条数被如实报出。
  **输入形态也不同**：Mekanism 的输入是单一 `InputIngredient#getRepresentations()`，Thermal 是 `List<Ingredient>`
  ⇒ 读取代码要**按形态分支**，不能照抄。
  **✅ 已修（第十六轮，2026-09-14）—— 只做"读出来 + 如实标注"，判据一行没动**：
  ① `MachineRecipeFacts` 成为**唯一读取器**：名族扩到 `getInputItems`/`getInputFluids`/`getOutputItems`/`getOutputItemChances`；
  ② `itemStacks()` 新增吃 `List<Ingredient>` 形态（每项取**第一个物品**当代表，与 `getRepresentations()` 同一口径：
  **只取代表、不展开标签**）；③ 新增 `Facts.chanceDeclared()`（**上游给了概率信息就为真**；
  **明确不解释数值** —— 第一版写成 `chance < 1.0` 是**在猜语义**，被运行时数字当场证伪后撤掉，见下）；
  ④ **纯流体输入**也算"配料存在"（否则会报成 `mats=[]` = "不需要材料"，正是 D-204 那个 bug 类）；
  ⑤ `MachineProbeTask` 的两份私有反射读取器**删除**，改调同一个 `MachineRecipeFacts.read()`
  （"探针读得出、查询层读不出"这种两处口径漂移从此不可能再现），并新增
  **`chance_declared=`** 计数（per-namespace 行 + SUMMARY 都有）；
  ⑥ `RecipeQuery` 在 `note` 末尾如实追加"⚠️ 该配方声明了产出概率（chance 语义未取证 ⇒ 不作必然产出）"，
  **`Verdict` 语义、`Route` 记录字段、`MachineMap` 能力列、`CraftJob` 准入全部未动**（`Route` 不加字段 ⇒ 下游构造点零改动）。
  **第十六轮实测**（`latest.log:3177`/`:3178` 一带）：`namespace=thermal` 的 `upstream_readable` **0 → 26**、
  `input_readable` **0 → 34**（57 条抽样；流体输出的 `refinery`/`crucible` **仍读不出**，是如实结果），
  `namespace=mekanism` 四个计数（31/20/31/30）**逐字未变** ⇒ **只读逻辑没有误伤既有行为**。
  ⚠️ **同时暴露了我第一版的错误**：`probabilistic_output=23/57` 与"JSON 里 `chance<1` 的条数"**算不出来**
  （按 9.7% 比例、每类抽 2 条，期望只有 ~5）⇒ **`getOutputItemChances()` 返回的很可能不是 JSON 原值**，
  拿它做 `< 1.0` 判断是**猜语义** ⇒ 已撤，改为只声明"有概率信息"。见 ⑯。
  ⑯ **Thermal 的 `chance` 到底是什么意思 —— 语义未取证（第十六轮发现，未修；**不阻塞**任何当前工作）**：
  **已知事实**（三条，都可复算）：① 静态 JSON 里 **146 / 670** 条 Thermal 配方声明了 `chance`，
  取值跨 **0.05 ~ 12.5**（`2.0` 出现 50 次、`1.0` 出现 14 次、`0.2` 出现 32 次）⇒ **不在 [0,1]**，
  **不能当概率直接读**；② 每条配方里 `chance` 的个数分布是 1 个（74 条）/ 2 个（45 条）/ 3 个（27 条）
  ⇒ 常见形态是"主产出 + 1~2 个副产物各带一个数"；③ **运行时的 `getOutputItemChances()` 与 JSON 对不上**：
  第十六轮实测 `namespace=thermal … probabilistic_output=23 / 57`，而按"JSON 里至少含一个 `<1` 值"的比例
  （65/670 = 9.7%）与"每类抽 2 条"的抽样设计，期望只有 **~5** ⇒ **访问器返回的很可能不是 JSON 原值**
  （疑：按机器/增幅折算后的值、或对缺省项填了默认值）。
  **为什么现在不做**：它的唯一用途是"能不能把某条机器路线的产出当**必然**"，
  而 **Thermal 全表 32 行都是 `READ_ONLY`**、没有任何执行准入 ⇒ 当前**不影响任何行为**；
  本轮的处置（只声明"有概率信息"、不解释数值）已经**堵住了过度承诺**这一侧。
  **要动它时的最小取证路径**：`javap -c` 追 `IMachineRecipe#getOutputItemChances(IMachineInventory)` 的
  **调用方**（机器方块实体里真正算产出的那处），看它把返回值当**概率**（除/比 100）还是当**倍率**（乘基准）；
  顺带核 `use_chance`（14 条配方带这个顶层字段）与 `primary_mod`/`secondary_mod`（各 11 条）的含义。
  **触发条件**：任何"要把 Thermal 机器升 `EXECUTABLE`、或要让概率产出参与 `CraftJob` 判定"的动作之前，**必须先收这一项**。

---

## §8 T1 审计残留（2026-09-14 登记，来源 = 三路只读审计 R-4/R-5/R-1 的"没做完"部分）

> 全部出自 `docs/reviews/2026-09-14-项目完成度与优先级审查.md` §7.1。
> **登记的目的是"不是遗忘"，不是"现在要做"** —— 每条都给了触发条件。

| # | 项 | 现状 | 为什么现在不做 | 触发条件 |
|---|---|---|---|---|
| **R4-残** | `RegionLumberJob` 补种**仍直接 `level.setBlock`** | 已补 `BlockInteraction.reachable` **触及前提**；其余（朝向/放置面/`gameMode` 交互路径）未走原语 | 选定树苗可能落在**主背包**，而 `BlockInteraction.placeAt` 只认**快捷栏 0–8** ⇒ 改走原语会**同时改变物品消耗路径与放置面语义**，属行为变更、需独立一轮客户端验证 | 下一次动 `RegionLumberJob` 补种路径时一起做 |
| **R5-残** | `StationProvision.click` / `InventoryCraft.click` **未做编译期强制** | `FurnaceStation` 已做（`WriteGrant` 必传 + `WriteReason.container()` 校验）；这两个仍是"调用方自觉" | 内部调用点共约 **19 处**，属机械重构；且两者在 `CONTAINER_WRITE_SITES.csv` 里**已登记 `gated=no` + 理由**（`StationProvision` 是 `gated=yes`） | 下一次动合成/装配路径时，(临时) 先做这两处 |
| **R2-残** | **R-2 的运行期路径没被走到** | 门禁 `check-provision-containment.sh` 已断言；客户端第十七轮 `[Job] launch` **0 条**（LLM 全程未被触发 ⇒ 没有 Job 被起） | 需要一个"真的起一次 Job"的动作；自动触发被 R-3 有意挡住了 | 手动右键 `alice:goal_director` 或 `alice:job_launcher`，看是否出现 `[Job] 生产入口只搬运不发料（…）` |
| **R1-残** | `prod_budget_exhausted`（连锁破坏预算耗尽）分支**无客户端证据** | 代码已接入 `WriteBudget` 并按增量计账；但电池 `exec_chain` 用例是干净 3×3 矿脉（约 9 次破坏 ≪ `DEFAULT_MAX_BREAKS=64`）⇒ **新分支不会被现有场景触发** | 造"连锁破坏数 > 64"的场景是**新夹具工作量**，与 T1 的"修红线"不是一回事 | 接 T3，或专门补一条场景时 |

## §9 T2/T3 未动（用户拍板前不开）

- **T2 无头回归**：**已实测可用但被拔掉** —— `./gradlew runServer` 在本机 ⇒ `Done (3.675s)!`、
  `Enabled Gametest Namespaces: [alice]`；而 `BotSelftest` 已于 `cbd177a` 删除、
  `build.gradle:95-97` **仍在宣传失效指令**。建议先做「1 个样板 + 覆盖度评估」，**N < 8 就不铺开**。
- **T3 模组 #3 的数据模型**：**`create-1.20.1-6.0.8.jar` 与 `ExtendedCrafting-1.20.1-6.0.10.jar`
  已经在客户端 `mods/` 里**，且按审查 §3.3 会被**静默读错**（读取器把 Mekanism 特例当通则、不试 vanilla 接口；
  `MachineMap` 的 1 方块↔1 类型不变式让 Thermal 6 行**已是错事实**）。
  ⇒ **推任何新模组前必须先做 T3。**
