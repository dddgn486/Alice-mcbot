# Baritone 对照登记表（**`D-532` §八 裁定 2** 的载体 · `O41` A③ 的证据面）

> ## ⚠️ 这张表是什么、不是什么
>
> | 它**是** | 它**不是** |
> |---|---|
> | **A③「Baritone 对照覆盖率」的唯一载体** —— 门禁 `tools/check-baritone-anchor.py` 逐行读它 | ❌ 判断"对照**对不对**"的机器 —— 机器只判"**有没有一行、且这一行是否可分类**"，**内容对错仍要人核** |
> | **每条内核改动的"锚"或"Alice 特有＋理由"的登记处** | ❌ 一份设计文档（不写做法） |
>
> **口径（`D-036` 规则 2/4/5 ＋ `D-532` §八 裁定 2）**：非 Alice 目标差异部分
> （**搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控**）**一律先对照**
> `/home/fb486/projects/reference/baritone-1.20.1/`（⚠️ 工作区有两棵树，**只认带 `-1.20.1` 后缀那棵**）；
> 必须偏离 ⇒ 在此登记「**Alice 特有 ＋ 理由**」。
>
> **三类取值**（门禁只认这三种）：
> 1. **真锚** = `<File>.java:NN`（可带区间，例 `MovementHelper.java:68-100`）；
> 2. **`Alice 特有：<理由>`** —— 理由**必须非空且具体**（⛔ "Alice 特有"四个字不算）；
> 3. **`待补锚：<归属哪一刀>`** = **可见负债** —— ⚠️ **出现任何一行 ⇒ 门禁红**（这就是"覆盖率 100%"的字面意思）。
>
> **判据（谁在跑）**：`python3 tools/check-baritone-anchor.py`（挂进 `tools/check-all.sh`）；
> ⭐ **只判"动过内核路径**且**改了代码**"的提交**（纯注释/文档改动**不判** —— 剥注释后无实质行即跳过）。
> **起点** = `48b5619b`（批次 1 设计单）⇒ 之前的提交**不判**（历史不追溯）。
>
> **怎么加一行**：某刀改了内核路径 ⇒ 在同一提交里往下面表格加一行（⛔ 不许"等以后补"，
> 补不了的写 `待补锚：归属 <刀>`，那会让门禁红着 —— 这正是它要的效果）。

---

## 表（批次 1 起）

| 提交 | 内核面 | 对照 / Alice 特有 ＋ 理由 |
|---|---|---|
| `bdcff3c3` | `1-0a` · `pathing/core/goal/GoalColumnBlocks`（新） | **Alice 特有**：**目标形状**（同列到达集）由 `D-520` 裁定新增；⛔ 不在 `D-036` 列举的差异面（搜索/Movement/执行器状态机/自愈/段超时/成本模型/跳跃门控）内 ⇒ 无需 Baritone 对照。理由 = 形状本身是新设计，且它的 `exactFoot()==true` 已按 `D-517` 登记进 `check-far-goal-usage` |
| `3c954528` | `1-0b` · `pathing/core/goal/GoalAdjacent` 定性更正 ＋ 8 处行号引用 | **Alice 特有**：本刀**零行为增量**（定性更正文案 ＋ 失效引用修复）；形状语义见 `D-520`（`1-0a`/`1-1b` 族）⇒ 无需对照 |
| `7f37be1f` | `1-1a` · `pathing/core/search/SearchConclusion` 补第三形态 | **Alice 特有**：`GOAL_NOT_LOADED` 的存在理由是 **`D-132` 未加载区块红线**（内核不得静默加载区块 ⇒ 必须把"目标区没加载"与"不可达"分开报）—— 这是 **Alice 特有约束**（Baritone 无此红线） |
| `286e7793` | `1-1b₁` · `pathing/core/search/PathRequest` 形状自由重载 | **Alice 特有**：**授权面**不在 `D-036` 的差异面清单内；本刀逐字「授权面零改动」⇒ 无需对照 |
| `18a19f02` | `1-1b₂` · `task/mining/MiningPlanner.planGoalApproach` 接线（同列 → 侧面兜底） | **Alice 特有**（⭐ 强理由）：本刀是**删除**一个 Baritone **本来就没有**的东西 —— `O29` §7/§8 实测逐字「**Baritone 的挖掘从不枚举站位**」⇒ 到达判据从"枚举出来的那一格"改成**形状自身的算术**，方向与 `D-036` 一致 |
| `af2fbeb7` | `1-2` · `protection/BlockBreakSafety.avoidAdjacentBreaking` ＋ `CostModel` 补两项 | **对照（真锚）**：`MovementHelper.java:68-100`（③冰 ④虫蚀 ⑤侧邻危险）· `MovementHelper.java:580-606`（`includeFalling`）；分工逐字 = **正上方计价、侧邻禁止**（`plans` §6） |
| `123566bc` | `1-3` · `reach/` ＋ `task/mining/` 成员级退役 | **Alice 特有**：纯搬家／退休（`StandingPlanSelector` → `reach/DirectArrivalPlanner`、载体换 `ReachOutcome`、删评分载体）—— **无内核行为增量**；⚠️ 已实测证明它**不改变** `blocked`/`exec_blocked` 读数（同 profile 对照 `cost=10.81 budget=9.38` 逐字相同，台账 `O47` ⑥） |
| `61a52b1e` | `1-4`（一）· 日志键名/前缀诚实化（`task/MineTask` · `action/MineBlockRunner`） | **Alice 特有**：本刀**零行为增量** —— 只把三处 `plan.arrival()` 的日志键从**旧载体名** `mode=` 改成 `arrival=`（取值诚实化），并把 `[MiningPlanner探针]` 的「探针」去掉（那两行是**终态**日志）。⭐ **日志口径不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照（Baritone 无此日志面） |

> **批次 1 读数（2026-09-29 快照；⚠️ 每次以门禁打印为准）**：区间 `48b5619b..HEAD` 共 **24** 笔提交 ⇒
> **判 7 笔**（动过内核路径且**改了代码**）· **真锚 1**（`1-2`）· **Alice 特有 6** · **待补 0** ·
> **纯注释/定性跳过 2**（`56ac28c8` 词形替换 · `3c954528` 定性文案）。
> ⚠️ 本表**多收了一行**（`3c954528`，门禁判它"非代码改动"⇒ 跳过）—— 那是**故意的**：那一刀的内核面值得留档。
> ⚠️ **诚实解读**：`Alice 特有 : 真锚 = 6 : 1` 本身就是一个**可读的指标** ——
> 它量化了"这个内核有多少东西**没有 Baritone 参照物**"（正是 `survey/48` §1.1 指出的病根）。
> ⛔ 别把它读成"7 笔都合规所以没事"：它只说明**每笔都登记了理由**，理由站不站得住要人核。
| `db64d985` | `1-5` · `reach/MiningPlan` → `reach/ReachPlan`（＋ `task/` `bot/` 引用面） | **Alice 特有**：本刀**零行为增量** —— 只把类型的**名字**从 `MiningPlan` 改成 `ReachPlan`（与同包 `ReachOutcome` 对齐），⛔ 无任何搜索/Movement/执行器/自愈/成本/跳跃门控的增量。⭐ **命名不在** `D-036` 列举的差异面内 ⇒ 无需 Baritone 对照（⚠️ Baritone 那边根本没有这个类型，它是 Alice 自己的到达腿计划载体） |
| `8652ef42` | `4a` 柱② · `task/mining/MineStep` ＋ `task/collecting/CollectStep` 实现新注册口 `task/Step` | **Alice 特有**：本刀**零行为增量** —— 只是给两个原语加一个**标记接口**（`implements Step`），并把门禁的"原语集合"从**写死路径**改成 `rglob` **自动枚举**。⭐ **扩展点／注册面不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 —— Baritone 那边没有"原语注册口"这个概念（它的 Movement 是按枚举分派的）⇒ 无需对照 |

| `3b88871c` | 批次 2 ① `P3` **波 2（夹具波）** · `task/` → `fixture/` 116 类 | **Alice 特有**：本刀**零行为增量** —— 只改**包位与引用**（`git mv` ＋ `package` 行 ＋ `import`/全限定名）。⚠️ 它改了内核路径上的文件**只因为**这些文件（`MineTask.java` 的 import 面 · `task/mining/GainStepRunner` 等被搬走的邻居 · `MiningReplanFixture`/`MiningSceneFixture` 的旧路径）出现在搬迁的**引用面**上。⭐ **包位与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照 |
| `349c3f76` | 批次 2 ① `P3` 波 2 **更正刀** · 回搬 4 个生产可达的类 ＋ 修分类器 | **Alice 特有**：本刀**零行为增量** —— 4 个类回搬只改**包位与引用**；分类器修的是 `P0` 生成物的**判据**（`task/` 不再整体算验证侧 · `R6`「被生产引用 ⇒ 不可剔除」· import 行不算引用）。⚠️ `MineTask.java` 被触及**只因为** `GainStepRunner` 的 import/全限定名来回改了一次。⭐ 逐字同上：包位与判据不在 `D-036` 的差异面内 |
| `accd1815` | 刀「乙」（`D-561`）· `pathing/core/` 按 Baritone 三分（`calc/`36 · `movement/`27 · `path/`4）＋「`calc/` 零写侧调用点」进门禁 | **Alice 特有**：本刀**零行为增量** —— 只改**包位与引用**（67 个 `git mv` ＋ `package` 行 ＋ 590 处 `import`/全限定名/`{@link}` 改写）。⚠️ 它改了内核路径上的文件（`MineBlockRunner.java` · `FishboneJob.java` · `AStarMovementSearch.java` · `BinaryHeapOpenSet.java` …）**只因为**这些文件出现在搬迁的**引用面**上。⭐ **包位与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⚠️⚠️ **补记（2026-10-01）**：本行是**事后补的** —— 刀「乙」提交时**没有**同刀登记，而门禁 `check-baritone-anchor` **只判已提交历史**（`git log BASE..HEAD`）⇒ 当时的"全绿"是在代码**未提交**时跑出来的**假绿**（台账 `O113`） |
| `e768afe0` | 刀 1（`D-562`）· 建 `action/mining/`（`MineBlockRunner` ＋ `ChainMining`）＋ 「原语住根 · 域执行件住子包」三条门禁 | **Alice 特有**：本刀**零行为增量** —— 只改**包位与引用**（2 个 `git mv` ＋ `package` 行 ＋ 18 文件 / 25 处 `import`/全限定名/**路径指针字符串**改写）。⚠️ 它改了内核路径上的文件（`pathing/calc/GoalAdjacent.java` · `GoalColumnBlocks.java` · `task/MineTask.java` · `task/mining/{MineStep,MiningPlanner}.java`）**只因为**这些文件出现在搬迁的**引用面**上。⭐ **包位与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⭐ **行为零变化有对照证据**（见 `D-562` §四：同代码两次运行 `[alice]` 侧差异 318 行 = 噪声地板；基线 vs 本刀 314 行；114 个判定 token 三次运行完全相同） |
| `7d6de008` | 刀 5（`D-565`）· 清 `Zone` 类名：8 类改名 ＋ 3 个同名 `Zone` 分离（`JobAreaRegistry`/`AreaData`/`LedgerScope`/`FixtureClaim`/3 个夹具/`Tenure`） | **Alice 特有**：本刀**零行为增量** —— 只改**类名与引用**（`git mv` ＋ 标识符替换），⛔ 不改任何判据、常量、调用顺序或世界写入；内核面仅被**引用名**顺带扫到（`BlockInteraction`/`MineCandidateSource`/`PathSession` 里出现的 `JobAreaRegistry`/`AreaData`）。✅ 字面名**用户已复核同意**（2026-10-01，见 `D-565` 刀 5 的表） |
| `e6b88a67` | 刀 3（`D-566`）· 建 `region/` ＋ `region/authz/` 骨架 ＋ 从 `protection/` **原样搬** 3 个类（`JobAreaRegistry` · `ProtectionClaimService`→`ClaimService` · `ProtectionMapGeometry`→`MapGeometry`）＋ 两份 `package-info.java` 契约 ＋ `check-layer-direction` 补搬包判据 | **Alice 特有**：本刀**零行为增量** —— 只改**包位与引用**（3 个 `git mv` ＋ `package` 行 ＋ 2 处补 import ＋ 15 文件 / 20 处 `import`/全限定名改写），⛔ 不改任何判据、常量、调用顺序或世界写入。⚠️ 它改了内核路径上的 `action/BlockInteraction.java` **只因为**该文件出现在搬迁的**引用面**上（`com.dddgn.alice.protection.JobAreaRegistry` → `...region.JobAreaRegistry` 一行 import）。⭐ **包位与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⭐ 判据 = 静态门禁**双向注入即红**（见 `D-566`），行为夹具对本刀**结构性无感**（同 `D-425` 口径） |
| `34326004` | 刀 4（`D-566`）第 1 件 · `WriteGrant` → `Attribution`（**归因原语**，⛔ 不搬家、留 `write/`） | **Alice 特有**：本刀**零行为增量** —— 只改**类名与文件名的标识符替换**（74 文件：`src/` 69 ＋ `tools/` 5），⛔ 不改任何判据、常量、调用顺序或世界写入。⚠️ 它改了内核路径上的 `BlockInteraction.java` · `action/mining/MineBlockRunner.java` · `job/fishbone/FishboneJob.java` · `job/mine/MineCandidateSource.java` **只因为**这些文件出现在改名的**引用面**上（`WriteGrant.of(...)` → `Attribution.of(...)`）。⭐ **命名与包位不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⭐ 名字由用户 2026-10-01 确认（方案草案 `§22.3` 三点之二）；判据 = 静态门禁（`compileJava` ＋ `check-all` 静态档 `pass=40 warning=1 failed=0` ＋ `check-layer-direction` 红臂 34/34） |
| `9a4aa3b1` | 刀 4（`D-566`）第 2 件 · `write/WriteAudit` → **`ledger/ModifyAudit`**（归因账与物质账同族）＋ 登记 `Quota` 的层序冲突 | **Alice 特有**：本刀**零行为增量** —— 只改**包位、类名与引用**（1 个 `git mv` ＋ `package` 行 ＋ 1 处补 import ＋ 13 文件引用面 ＋ 1 处**过期 javadoc 指针**修正）。⚠️ 它改了内核路径上的 `BlockInteraction.java` · `action/mining/MineBlockRunner.java` · `job/fishbone/FishboneJob.java` · `job/mine/MineJob.java` **只因为**这些文件出现在搬迁的**引用面**上（`WriteAudit.summary()` → `ModifyAudit.summary()`，含 2 处全限定名）。⭐ **包位与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⭐ 判据 = 静态门禁（搬包判据**双向注入即红** ＋ `check-all` `pass=41`）；⚠️ **同刀第 3 件（`Quota` → `job/`）因层序反转暂停**，见台账 `O131` |
| `f5131dd0` | 刀 4（`D-566`）第 3 件 · `write/WriteBudget` → **`region/authz/Quota`**（用户第二次裁定；⛔ 不去 `job/`）＋ 顺带 `QuotaCheckTask`/`QuotaCheckItem`/`assignQuotaCheck` 改名 | **Alice 特有**：本刀**零行为增量** —— 只改**包位、类名与引用**（1 个 `git mv` ＋ 2 个顺带改名的文件 ＋ `package` 行 ＋ 3 处补 import ＋ 70 文件引用面）。⚠️ 它改了内核路径上的 `BlockInteraction.java` · `job/mine/MineCandidateSource.java` · `job/mine/MineJob.java` · `pathing/calc/CapabilityGate.java` **只因为**这些文件出现在搬迁的**引用面**上（`WriteBudget.` → `Quota.`）。⭐ **包位与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⭐ 判据 = 静态门禁（4 个门禁当场抓到 7 处硬写路径/名单）＋ `check-all` `pass=41`（CORE 44 步） |
| `7cb21595` | 刀 4（`D-566`）第 4 件 · `protection/ZoneAuthority` **拆三个谓词后定名**（整类消失 ⇒ `region/authz/{AreaPermission,AreaPermissionLevel,Quota}` ＋ `ledger/ModifyAudit`） | **Alice 特有**：本刀**零行为增量** —— D/E/F 三块的**代码逐字搬走**（判据顺序 `isClaimed` → 覆盖 → 理由 → 刀 2 位置 ⇒ E 档位 ⇒ F 额度 一字未变），拒绝码/常量/调用顺序全同；引用面改写 = 19 文件 `import` ＋ 全限定名。⚠️ 它改了内核路径上的 `action/BlockInteraction.java` · `job/mine/MineCandidateSource.java` · `job/mine/MineIntent.java` · `pathing/calc/CapabilityGate.java` **只因为**这些文件出现在改名的**引用面**上（`ZoneAuthority.` → `AreaPermission.`，含 `Act.PLACE` 与 `{@code}` 指针）。⭐ **包位、类名与依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⚠️ **唯一非零行为面（如实登记）**：留痕日志前缀 `[ZoneAuthority]` → `[ModifyAudit]`（发射类已不存在）—— ⛔ 不涉及任何判据。⭐ 判据 = `compileJava` ＋ 静态门禁（**三条新判据逐个注入即红**）＋ `ALICE_HEADLESS=1 check-all` `PASS_WITH_REGISTERED_REDS: pass=41`（CORE 42/44，两个红 = `EXPECTED_REDS` 逐字命中）；行为夹具对本刀**结构性无感**（同 `D-425` 口径） |
| `9551aa1b` | 刀 `zone` 清理（`O135`/`D-567`）第 1 段 · **`WorkingArea` 合并成一个类** ＋ `job_region` 改名族（`JobAreaRegistry`→`JobRegionRegistry` · `JobArea`→`JobRegion` · `zoneOf`/`zoneAt`/`Result.zone()`→`jobRegion*` · `[TaskZone]`→`[JobRegion]`）＋ zone 清理（`areaLevel`/`areaAt`/`isInReturnArea`/`regionKind`/`inJobRegionPlaceRefusal`/`claims`/`inArea`） | **Alice 特有**：本刀**零行为增量** —— 只改**类型、方法、字段与日志前缀的名字**（＋一次**纯几何类型合并**：两份重复的水平矩形合成一个 `region/WorkingArea`，`dimension` 从 footprint 搬到 `JobRegion` 上，其唯一读者 `zoneAt` 的匹配语义逐字不变）。⚠️ 它改了内核路径上的 `action/BlockInteraction.java` · `job/mine/MineCandidateSource.java` **只因为**这两个文件出现在改名的**引用面**上（`JobAreaRegistry.zonePlaceCount` → `inJobRegionPlaceCount`、局部 `safeZones` → `claims`）。⭐ **命名/包位/依赖方向不在** `D-036` 列举的差异面（搜索 / Movement / 执行器状态机 / 自愈 / 段超时 / 成本模型 / 跳跃门控）内 ⇒ 无需 Baritone 对照。⭐ 判据 = `compileJava` ＋ 静态门禁（含 `policy-map.sh` 重生成）· ⚠️ **行为侧待本刀第 3 段**（存档键迁移 ＋ 电池复跑）同批验 |
