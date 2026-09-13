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
