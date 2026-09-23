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
