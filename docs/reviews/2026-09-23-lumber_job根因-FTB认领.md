# `lumber_job` 连红 35 轮的根因：**夹具场景落在玩家自己的 FTB 认领里**（2026-09-23）

> **结论一句话**：`lumber_job` 的红**不是内核缺陷、不是能力缺口**，是**一条没被声明的世界前提** ——
> `lumber_course_*` 场景是从**玩家真实存档**抓下来的（坐标就在玩家基地里），而那块地**已被玩家自己用
> FTB Chunks 认领**（2026-09-18 建立）⇒ FTB 取消 bot 的破坏事件 ⇒ 夹具拿到"树砍不动"的世界。
> ⭐ 这是一条**假红**（与"假绿"同类：它让人以为内核坏了）。
>
> 触发：用户 2026-09-23 选 `C4`（"查 `lumber_job` 真正阻塞点"）。

---

## 一、取证链（每一步都指向当场可复算的证据）

### 1.1 症状的**位置结构**（决定性线索）

同一次运行里，把"被拒的破坏"与"成功的破坏"按坐标排开（`run/headless-logs/20260923-122959-single_lumber_job.log`）：

| 被拒（`WRITE-REFUSED` / `destroyBlock=false`） | 成功（`block_break_done`） |
|---|---|
| `20,64,208` · `20,65,208` · `20,66,208` · `20,67,208`（原木） | `18,65,207` · `19,65,207` · `20,65,207` · `20,66,207` · `20,67,207` · `20,68,207` |
| `19,65,208` · `19,68,208` · `20,68,208`（树叶） | `18,66,206` |

⭐ **被拒的每一个坐标 `z=208/212/213/214`；成功的每一个 `z≤207`。** 与方块种类、工具、触及距离**无关**
（同样是 Oak Leaves，`18,65,207` 成功而 `19,65,208` 被拒）⇒ 这是**位置**问题，不是类型/权限问题。
`z=208` 恰好是 **chunk `z=13` 的第一个方块** ⇒ 指向"按区块划界的保护"。

### 1.2 谁在拦：**Alice 自己不拦**，FTB Chunks 在拦

| 检查 | 结果 |
|---|---|
| 全 `src/` 里取消破坏事件的地方 | **0 处**（`setCanceled(true)` 只有 `PickupGate:78` 掉落物拾取、`BotManager:1813` 死亡掉落） |
| 单方块路径用哪条销毁 | `action/BlockBreakSession.java:106` `bot.gameMode.destroyBlock(pos)` ⇒ **会触发 Forge 破坏事件**（`ThirdPartyProtection:16` 已反汇编核实：`Level.destroyBlock` **不**触发） |
| `destroyBlock=false` 意味着什么 | Forge 版 `ServerPlayerGameMode.destroyBlock` 在**事件被取消（返回 -1）**或**工具前置不满足**时返回 false |
| 服务端里 FTB 在不在 | **在** —— 日志有 `ftbchunks:*` 收包器注册 + `FTB Teams: loaded team data: 6 known players, 7 teams total` |

### 1.3 认领数据（世界母本里就有）

`run/world-pristine/ftbchunks/6ffe1112-cc41-461e-9ff8-d95eada91b14.snbt`：

```
"minecraft:overworld": [ { x: 1, z: 13 } { x: 2, z: 14 } { x: 2, z: 13 } { x: 1, z: 14 } ]
```

- 建立时间（`time=1789740981305L` 等）⇒ **2026-09-18 14:16 UTC**。
- 覆盖方块范围：`x∈[16,47]`、`z∈[208,239]` —— **正好包含 `z=208` 这条界**。
- 场景坐标（`task/LumberCourseAnchor.java`：`START_FOOT=23,64,207`、`EXPECTED_TREE=20,64,208`、
  区域 `x17..37 / z203..231`）**几乎全部落在认领内**；而 bot 站位在 `z≤207`（未认领）⇒ 它站在自己够不到的地盘外。

### 1.4 ⭐ 归因探针（**证"谁拦的"**，已作为永久改进保留）

原日志只写"（被保护层取消 / 未生效）"，**不说明谁拦的** —— 读日志的人分不清是我方闸门、方块本身、还是别的模组。
`BlockBreakSession` 的失败分支现在补一次**第三方裁决查询**（复用现成的 `ThirdPartyProtection` / `FtbChunksBridge`，
它调用的正是 FTB 自己的 `ClaimedChunkManager.shouldPreventInteraction + Protection.EDIT_BLOCK`）：

```
[WRITE-REFUSED] break pos=20, 64, 208 by=BlockBreakSession reason=world_unchanged（destroyBlock=false 方块仍是 Oak Log
  —— 被保护层取消 / 未生效）（第三方保护预检：FTB=ftb_claim_denied）
```

实测该轮 **7/7 被拒行全部带 `ftb_claim_denied`**（`run/headless-logs/20260923-123239-…`）。

### 1.5 ⭐⭐ 反向对照（A/B）：把认领从母本拿掉

把 `run/world-pristine/ftbchunks/` 临时移开（本地副本，已复位）再跑同一步（日志 `20260923-123423-…`）：

| | 有认领（现状） | 无认领（A/B） |
|---|---|---|
| `ftb_claim_denied` | 7+ | **0** |
| `WRITE-REFUSED` | 有 | **0** |
| 终态理由 | `no_reachable_candidate`（`ticks=1018`） | **`partial_quota`**（`ticks=904`） |
| 实际战果 | 树全砍不动 | **砍完 3 棵橡树 + 冷杉若干**（`trees 3/4 logs 19/23`） |

⇒ **因果成立**：认领是"砍不动"的**直接原因**（不是唯一原因，见 §二）。

---

## 二、⭐ A/B 同时暴露出**第二层**原因（它一直被第一层盖着）

去掉认领后**仍然 FAIL**，理由是 `partial_quota`（`trees 3/4`）。逐条归因：

| 候选 | 结果 | 性质 |
|---|---|---|
| `20,64,208` 橡树 | 砍完 4/4 | ✅ |
| `19,64,213` 橡树 | 砍完 4/4 | ✅ |
| `29,64,213` 橡树 | 砍完 4/4 | ✅ |
| `28,64,208` 云杉（7 原木） | 砍完 | ✅ |
| `33,64,208` 橡树 | **规划期就失败**：`MiningPlanner standable_only … reason=no_valid_standing_point`（一次破坏都没发生）⇒ 记 `already_attempted` ⇒ **本 job 内永久排除** | ⚠️ 待查（是场景几何还是内核，**未定**） |
| `22,64,218` 2×2 高大云杉 | `trunk_too_tall` | ✅ **按设计**（`LumberCourseAnchor:26` 逐字"期望被拒的对照"） |

⇒ 配额是 **4 棵**，而可用候选只有 5 棵（其中 1 棵被设计排除）⇒ **任何一次规划失败都直接导致配额落空**。
⭐ 放大机制 = `LumberJob.java:646` 把失败过一次的树记进 `attempted` ⇒ **一次失败 = 永久少一棵候选**。
（这一条在"有认领"的世界里被完全掩盖：那时**每棵树都失败**，看到的是 `no_reachable_candidate`。）

### 2.1 第三层（与前两层独立）：`idempotent=false`

`LumberJob.java:304-306` `if (terminated) return Task.Status.DONE;` —— 终态是 `FAILED` 时再 tick 返回 `DONE`
⇒ 违反 `D-178`（日志逐字：`idempotent=false（再 tick 返回 DONE/DONE，期望 FAILED）`）。
**这与前两层无关**，且**修它不会让本步转绿**（该步 PASS 判据是 `status == DONE && idempotent`）。

---

## 三、影响面（**不是只影响这一步**）

### 3.1 场景函数扫描（115 个函数 × 认领区 `x[16,47] z[208,239]`）

| 场景函数 | 坐标行 | 落在认领内 |
|---|---|---|
| `lumber_course_terrain` | 855 | **760** |
| `lumber_course_trees` | 186 | **161** |
| `lumber_plant` | 14 | **10** |
| `partial_search_terrain` | 2 | 1（仅 fill 起点角在界内；**bot 活动点在 `z=245`，界外** ⇒ 无实际暴露） |

⇒ **暴露集中在伐木类场景**（`lumber_job` / `lumber_failure` / `region_maintain` / `lumber_plant`）。
⚠️ 这解释了为什么"只有 `lumber_job` 连红"而其余 40 步都还好。

### 3.2 ⭐ 结构性教训（比这一步更重要）

世界母本 = **玩家真实存档的副本**（`tools/headless-battery.sh:38` 的设计，为了让 5 个依赖绝对坐标的
CORE 步拿到真地形）。**代价**：夹具会**继承玩家世界的一切** —— 认领、别的 bot、圈养设施、已被改过的地形。
⇒ **"夹具自带前提"这条纪律必须扩展到第三方保护**：

- `Z1` 已经为**我方**保护区做了这件事（`task/FixtureZone`：夹具自己认领 + 声明任务区，否则不成立就报错）；
- **第三方**（FTB）那一半今天**没有人管** ⇒ 夹具踩进去只会得到**误导性的内核失败码**（`no_reachable_candidate`），
  而不是"**前提不成立**"。**这是"静默"的一种**：错的原因、对的表象。

---

## 四、我不主张的（诚实边界）

1. **`destroyBlock=false` 的最终来源是"FTB 取消事件"这一点，我读的是 Forge 侧语义 + FTB 自己的裁决函数结论**
   （§1.4 的 `ftb_claim_denied` 就是 FTB 自己的答案），**没有**去反汇编 `ServerPlayerGameMode` 逐行核对
   哪一条 `return false` 命中。⚠️ 但 A/B（§1.5）已把"认领 = 砍不动的原因"变成**行为事实**，不依赖该细节。
2. **`33,64,208` 的 `no_valid_standing_point` 是场景几何问题还是内核问题，我没有定论** ⇒ 不写成缺陷。
   （场景函数头部自己记着一条"通道红线"：`y=65` 的 `z=212..214` 树冠连成 `x=17..31` 的墙，
   只有 `x=22..26` 是缺口 ⇒ 该区域的站位问题**有设计层面的成因可能**。）
3. **我没有改夹具、没有搬场景、没有动母本**（A/B 用的移开已复位：6 个文件与移开前一致）。
4. 本报告**未**复核 `lumber_failure` / `region_maintain` 是否也因此假绿/假红 —— 只证明"坐标落在认领内"。

---

## 五、可选处置方向（**供拍板，未实施**）

| # | 方案 | 代价 | 说明 |
|---|---|---|---|
| **A** | **夹具声明前提**：跑伐木步之前断言"场景盒内无第三方保护"，不成立 ⇒ 报 `FIXTURE_THIRD_PARTY_CLAIM` 这类**专属码** | 小 | 照 `FixtureZone`（`Z1`）的模子；**把误导性的内核失败码换成"前提不成立"**。**建议无论如何都做** |
| **B** | **把伐木课程搬出认领区**（例如整体 `z -= 32` ⇒ 落到 `z∈[171,199]`） | 中 | 让该步**能真的过**；⚠️ 但场景有那条"树冠通道红线"（`z=212..214`），搬迁会改变几何 ⇒ 必须重跑夹具验证 |
| **C** | 把母本换成**专用夹具世界**（不带玩家认领） | 大 | 与 `headless-battery.sh:33-39` 的设计相冲突（5 个 CORE 步依赖真存档坐标）⇒ 不建议现在做 |
| **D** | 顺手修 `LumberJob` 终态闩锁（`D-178`，6 处同形状） | 小 | 让该步的 `idempotent=true`；**不使该步转绿**，但与 A/B 配套才看得见真实状态 |

**推荐顺序**：`A`（防再犯，且让日志说真话）→ `D`（便宜、真契约）→ `B`（要动几何，单独一轮配夹具验证）。

---

## 六、证据索引

| 内容 | 路径 |
|---|---|
| 复现（有认领） | `run/headless-logs/20260923-122959-single_lumber_job.log`（`FAIL ticks=1018`） |
| 归因探针 | `run/headless-logs/20260923-123239-single_lumber_job.log`（7/7 `ftb_claim_denied`） |
| 反向对照（无认领） | `run/headless-logs/20260923-123423-single_lumber_job.log`（`ftb_claim_denied`=0，`partial_quota`） |
| CORE 里的原始红（连红起点） | `run/headless-logs/20260920-234751-core.log` 起 |
| 认领数据 | `run/world-pristine/ftbchunks/6ffe1112-….snbt` |
| 代码：归因 | `src/main/java/com/dddgn/alice/action/BlockBreakSession.java`（`thirdPartyNote()`） |
| 代码：候选排除放大 | `src/main/java/com/dddgn/alice/job/lumber/LumberJob.java:646` |
| 代码：终态闩锁 | `src/main/java/com/dddgn/alice/job/lumber/LumberJob.java:304-306` |
| 场景锚点 | `src/main/java/com/dddgn/alice/task/LumberCourseAnchor.java` |

---

## 七、落地：`C5`（夹具第三方前提）+ `D`（终态闩锁）（用户 2026-09-23 拍板）

### 7.1 `D`：6 处终态闩锁统一成 `MineTask` 形状

`if (terminated) return Task.Status.DONE;` ⇒ 外层 `tick()` 记住**首次终态并原样回放**
（`terminalStatus` + `tickOnce()`），全仓 6 处一次改完：
`LumberJob` · `MineJob` · `CollectJob` · `RestoreScopeTask` · `LumberFailureCheckTask` · `ClearGuardCheckTask`。

⭐ **注入证明（不是"读代码觉得对"）**：临时把 `lumber_job` 的树配额改成不可达（`trees 4 → 99`）+ 移开认领
⇒ 终态仍是 `FAILED partial_quota`（**同一个失败码**），但

| | 修前（`20260922-225609-core.log`） | 注入后（`20260923-124551-…`） |
|---|---|---|
| `idempotent` | **`false（再 tick 返回 DONE/DONE，期望 FAILED）`** | ✅ **`true`** |

⇒ 闩锁**真的记住了状态**（不是把断言绕过）。**注**：`CollectJob` 的 `finish(FAILED,…)` 今天取不到
（`:223` 只以 `DONE` 收尾）⇒ 那一处是**形状统一**，不是修活缺陷（已写进它的字段注释）。

### 7.2 `C5`：夹具自己断言第三方前提

新增两个类：

| 类 | 职责 |
|---|---|
| `task/FixtureThirdParty` | 逐区块问 FTB 自己的裁决函数（复用 `ThirdPartyProtection`）；**采样粒度 = 每个区块一次**（第三方保护按区块），采样点取「盒 ∩ 区块」最小角；**fail-open**（桥不可用 ⇒ 前提成立，与 `D-326` 取舍一致）；**不改世界** |
| `task/PremiseGateTask` | 前提闸门：**首 tick 之前**求值一次，不成立就**当场以专属码收场**（一个字的世界操作都不做）；必须**透传** `isSelfCheck()`（`WritePolicyMatrix`/夹具洁净门禁都读它）、`taskName()`、`target()`、`safeToCancel()`、`failureReport()` |

**为什么用闸门而不是 `skipWhen`**：`CheckStep.skipWhen` 是**终态之后**才求值
（`RegressionBatteryTask:754-761`）⇒ 任务仍会真跑一遍、仍会打出那条**误导性的功能失败码**。
闸门把前提提到**开跑之前** ⇒ 日志里只有"前提不成立"。

接线：`LumberModule` 的 3 步（`lumber_failure` / `lumber_job` / `region_maintain`）走
`CheckStep.skippable(..., LumberModule::premiseFailed)` + `guarded(...)`；前提盒**从 `LumberCourseAnchor`
的区域常量派生**（不另写一套）；`region_maintain` 的 `doneWhen` 用 `unwrap(task)` 穿透包装。

### 7.3 实测（三条，互为对照）

| 运行 | 条件 | 结果 |
|---|---|---|
| A 现状 | 认领在 | `lumber_job=SKIP ticks=0 idempotent=true` ⇒ **`DEGRADED`**（`passed=0/1 skipped=1`）；⭐ `[Premise]` 行**逐字点名 4 个被拦区块 `chunk(1,13)(1,14)(2,13)(2,14)@…:ftb_claim_denied`** —— 与认领文件**恰好一致**；**`no_reachable_candidate`/`trunk_too_tall`/`WRITE-REFUSED` 全部 = 0**；用时 **72 s → 21 s** |
| B 移开认领 | 前提成立 | `lumber_job=PASS ticks=593` ⇒ 说明**前提成立时这一步真能过** |
| C 注入 | 移开认领 + 配额不可达 | `FAIL partial_quota` + **`idempotent=true`**（§7.1 的证明） |
| D 模块 | `module:lumber` | **`failures=0 skipped=4 [lumber_failure, lumber_job, region_maintain, region_sweep_e2e]`** ⇒ 4 个踩在认领盒里的步全部如实 `SKIP`；`region_sweep`/`region_maintain_unmaintainable` 照常 `PASS`；用时 **147 s → 26 s** |

⭐ **D 这一步多抓出第 4 个受害夹具**：第一次跑 `module:lumber` 时 `region_sweep_e2e` 仍 **FAIL**
（`REGION_SWEEP_E2E_FAILED`），日志逐字 `[WRITE-REFUSED] plant pos=23, 64, 211 by=region_lumber…` ×113
⇒ **认领同样拦"放置"**（不只是破坏），而该步用 `LumberCourseAnchor.region()` **在代码里自建场景**
⇒ 我先前那次"115 个场景函数"的扫描**漏掉了它**（扫描只看数据包函数）。
⇒ **教训（写进 `LumberModule` 的注释了）**：排查"夹具踩到别人的地"**不能只扫数据包场景函数**，
**代码里自建场景的夹具**同样要查。

### 7.4 ⚠️ 顺带查出的新事实：**前提成立时这一步是"刀尖上的"（flaky）**

两次**同样移开认领**的运行，结果不同：

| 日志 | 结果 |
|---|---|
| `20260923-123423-…` | `FAIL partial_quota ticks=904`（`rejected=[22,64,218:trunk_too_tall, 33,64,208:already_attempted]`） |
| `20260923-124423-…` | **`PASS ticks=593`**（无 rejected 列表 ⇒ 配额干净达成） |

⇒ 配额要 **4 棵**、可用候选 **5 棵**（其中 1 棵是设计上的"期望被拒对照"）⇒ **任何一次规划失败都翻盘**，
而 `already_attempted` 让"失败一次 = 永久少一棵"。
⭐ **对 `C6`（搬迁）的含义**：**搬迁是必要条件，但不保证稳定转绿** —— 还得同时解决
「配额 vs 候选数」这个刀尖（或让 `attempted` 不再是永久的）。**所以 `C6` 不能只按"搬完就绿"验收。**

