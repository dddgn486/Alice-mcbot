# `region/` ＋ `authz/` 包结构提案（2026-10-01，待你确认；⛔ 本轮零 `src/` 改动）

> 用户 2026-10-01 逐字：「**任务区干脆改成 job 区域好了**；**authz 这个名字我采纳**；
> `protection` 现在按含义应该改成 **region 包**，但是我的建议是**把 authz 包和 region 包采纳，
> 而且 region 为顶层**，然后**首先要做的是整理这两个包的结构**，还要**一个一个清理带 Zone 的类**，
> 把**这两个包原来的旧代码全部清理干净，一起重构**」。

---

## §0 ⚠️ 先确认一处结构读法（我按这个写，不对请纠）

「把 authz 包和 region 包采纳，**而且 region 为顶层**」有两种读法：

| 读法 | 形状 | 我的判断 |
|---|---|---|
| ⭐ **(a)** **`region/` 顶层包含 `region/authz/`** | `com.dddgn.alice.region` ＋ `com.dddgn.alice.region.authz` | ⭐ **本文按 (a) 写**（"region 为顶层"最直白的读法） |
| (b) 两个**平级**顶层包，`region/` 只在**层序**上更高 | `com.dddgn.alice.region` ＋ `com.dddgn.alice.authz` | ⚠️ 若你要的是这个，本文的落点表**只改前缀，其余不变** |

⚠️ 注意本项目**已有"按子包声层"的先例**（`pathing/calc/` 声明为内核，`pathing/movement/`·`pathing/path/`
与 `action/` 同级）⇒ 两种读法在门禁上都做得到，⛔ 但**形状不同，必须你定**。

---

## §1 ⭐ 两包的**边界**（按"回答哪个问题"划，⛔ 不按"名字像什么"划）

这一刀的依据是 §15.2.4 的深层发现：**「保护区」一词今天同时承担三个问题** ⇒ 边界就按这三个问题划。

| 包 | 回答的问题 | 内容 |
|---|---|---|
| ⭐ **`region/`** | **区域是什么 · 谁声明的 · 谁能覆盖谁 · 什么时候消失** | 区域数据 · 三种区域类型 · 声明与解除 · 区域管理（写）· 区域几何 |
| ⭐ **`region/authz/`** | **这一格我允不允许动 · 还能动几次** | 授权判据（谓词）· 额度 · 留痕 · 外部裁决 |
| ⛔ **既不进** | **我改了要不要记/要不要还** | ⇒ `ledger/`（**已是第三个问题**，⛔ 不许塞回来） |

---

## §2 ⭐ `protection/` 9 个类的落点（逐个）

| 今天 | 行 | 生产消费文件 | 落点 | ⚠️ 理由 / 备注 |
|---|---|---|---|---|
| `SafeZoneData` | 598 | 16 | ⛔ **拆三份** | ① **认领集 ＋ 安全区集** → `region/AreaData`；② ⭐ **全球方块/标签黑名单** → ⛔ **踢出 `region/`**（它是**方块规则**，与"哪块地"正交 ⇒ 应随 `BlockBreakSafety` 走）；③ `protectionReason()` 的**三态拼装** → ⛔ **解散**（它把"区域"与"规则"揉在一个返回值里） |
| `TaskZoneRegistry` | 474 | 9 | ⭐ **`region/JobAreaRegistry`** | ⭐ 你已把 **任务区 → job 区** 改名裁了；本类正是 job 区的载体 |
| `ZoneAuthority` | 330 | 8 | ⛔ **拆三份** → `region/authz/` | ⭐ 它内部装 D（区域权限）/ E（档位）/ F（额度）三件事（`A2` §2） |
| `ProtectionZones` | 68 | **1** | ⛔ **不留在 `region/`** | ⭐ 它只是 `SafeZoneData.isClaimed` 的**别名**，**生产消费者只有 `WorldModLedger`**（§15.2.3 原因 B）⇒ 它是**账本取件口径** ⇒ 搬到 `ledger/`（或与账本的口径合并） |
| `BlockBreakSafety` | 226 | 6 | ⛔ **不进 `region/`** → **`action/`** | ⭐ 它装的是 **C（方块语义）＋ G（自保）**，两个都与"区域"无关；权限那部分（借道 `ZoneAuthority`）**剥掉**。落 `action/` 的理由 = 挨着 `BlockInteraction`（同层、同一调用栈） |
| `ProtectionClaimService` | 210 | 5 | **`region/ClaimService`** | ✅ 区域管理的**写入口**（认领/取消认领） |
| `ProtectionMapGeometry` | 230 | 2 | **`region/MapGeometry`** | ✅ 区域的**几何**（像素⇄区块）；⚠️ 若将来客户端/服务端分家，它可能要再挪 |
| `ReturnPointData` | 138 | 3 | ⛔ **踢出** | ⛔ **它是"返程点"**（`D-327` 机制 B 的"回哪一格"），**另一个概念** ⇒ 归 `pathing/` 或 `bot/`（⚠️ 需你指） |
| `ThirdPartyProtection` | 64 | 4 | **`region/authz/ExternalVerdict`** | ✅ 外部裁决（问 FTB）—— 它回答的是"允不允许" |

⇒ ⭐ **结果：`protection/` 9 个类里，只有 3 个原样进 `region/`**（`TaskZoneRegistry`/`ProtectionClaimService`/`ProtectionMapGeometry`）。

---

## §3 ⭐ 带 `Zone` 的类名逐个清理（你点名的"一个一个清理"）

⭐ **全仓带 `Zone` 的类共 10 个**（实测）＋ 1 个字段级 `Zone`：

| 类 / 成员 | `Zone` 指什么 | ⛔ 问题 | ⭐ 建议名 |
|---|---|---|---|
| `protection/ProtectionZones` | **保护区** | ⛔ 名字像"权限判据入口"，实为**账本别名** | **`LedgerScope`**（落 `ledger/`） |
| `protection/SafeZoneData` | ⛔ **子类（安全区）** 却装**父类** | 词与内容错位 | **`AreaData`**（`region/`） |
| `protection/TaskZoneRegistry` | **任务区** | ⭐ 你已改：**job 区** | **`JobAreaRegistry`** |
| `protection/ZoneAuthority` | ⛔ 实为**授权** | "Zone" 与 "Authority" 都读不出"授权判据" | 拆后命名（见 §5） |
| `write/WritePolicyMatrix.Zone` | ⛔ **Alice 的地 / 别人的地** | ⭐ **第三个义**，与"区域"**无关** | **`Tenure`**（归属） |
| `task/FixtureZone` | 保护区 | 夹具 | `FixtureClaim` |
| `fixture/TaskZoneCheckTask` | 任务区 | 夹具 | `JobAreaCheckTask` |
| `fixture/ProtectionZoneCheckTask` | 保护区 | 夹具 | `ClaimCheckTask` |
| `fixture/LedgerZoneScopeCheckTask` | 保护区 | 夹具 | `LedgerScopeCheckTask` |
| `ledger/WorldModLedger`（javadoc 用 "zone"） | 保护区 | ⛔ 文档用词 | 改文案 |

⭐ **原则：`Zone` 不再作类名构件**（除非真的指一片**几何**区域 —— 而今天没有一个类是这样）。

---

## §4 ⭐ 搬进 `region/authz/` 的（来自 `write/`）

| 今天 | 行 | 落点 | ⚠️ 理由 |
|---|---|---|---|
| `write/WritePolicyMatrix` | 1032 | ⭐ **`region/authz/`** | ⭐ **必须搬**：今天依赖方向是 **`protection/` → `write/`**（`ZoneAuthority.java:3-4` import 它）⇒ ⛔ 不能把授权判据的一部分留在"写入"名下 |
| `write/WriteBudget` | 605 | ⭐ **`region/authz/`** | 它是**额度**，而额度是新模型里"权限的表达"（用户裁定：安全区权限一样、**靠额度区分**） |
| `write/WriteAudit` | 106 | **`region/authz/`** | 留痕；⭐ 顺便补"**被拦也要留痕**"（今天 `logAllow` **只记放行**） |
| `write/WriteGrant` | 54 | ⛔ **不进** | 它是**归因**（谁/为什么），`requester` **不是身份**；且**44 文件引用** ⇒ 独立一刀 |
| `write/WriteReason` | 169 | ⛔ **不进** | ⭐ **它一个类扛三件事**（§6 表）⇒ 拆完各自归位 |
| `write/TaskTargetProtection` | 192 | ⛔ **不进** | ⭐ 它做的是**识别**（`isTaskTarget()`），名字却叫"保护" ⇒ 归**识别面** |

---

## §5 ⭐ `ZoneAuthority` 拆三个谓词（`A2` §2 的物理病灶）

| 新件 | 回答 | 今天的对应 | 出口 |
|---|---|---|---|
| **`AreaPermission`** | 这一格**允不允许动**（D 维） | `authorize` 第 1–4 步 | `ALLOW` / `DENY(protected_*)` / `PASS` |
| **`AreaPermissionLevel`** | 授权**到哪一档**（E 维） | `authorize` 第 5 步 ＋ `WritePolicyMatrix.Level` | ⚠️ 待裁（见 §7） |
| **`Quota`** | 还能**动几次**（F 维） | `authorize` 第 5d 步 ＋ `WriteBudget` | `DENY(quota_exhausted)` |

⛔ **三者不许再合成一个函数** —— 它们今天挤在 `ZoneAuthority.authorize` 一个 330 行函数里，
这就是"授权臃肿"的物理位置。

---

## §6 ⭐ `WriteReason` 拆三份（它一个类扛三件事）

| 拆出的部分 | 今天的方法 | 归到哪个维度 | 落点 |
|---|---|---|---|
| **安全策略** | `policy()`（`EXPLICIT_TARGET`/`CLEARING`） | **C 方块语义** | 随 `BlockBreakSafety` 走（`action/`） |
| **账本义务** | `temporary()` | ⭐ **账维** | `ledger/` |
| **归因身份** | `name()` | 留痕 | 随 `WriteGrant`（独立一刀） |

⭐ 依据：`A2` §5.4 实测 —— **`policy()` 是安全策略轴，⛔ 不是目标归属轴**
（反证：`DESCEND_FOOT` 标 `EXPLICIT_TARGET` 却**只由内核发出** 4 处）。

---

## §7 ⚠️ 待你确认（结构定案前必须答）

| # | 问题 | 我的推荐 |
|---|---|---|
| **1** | ⭐ **`region/authz/` 还是两个平级顶层包**（§0 两种读法） | ⭐ **(a) `region/authz/`** |
| **2** | **`ReturnPointData`（返程点）去哪** | ⚠️ `pathing/`（它与返程路径同族）—— **需你指** |
| **3** | **`BlockBreakSafety` 的方块语义（C）落哪** | ⭐ `action/`（挨着 `BlockInteraction`）；⛔ 进 `region/` 会让包名再变一次谎话 |
| **4** | ⭐ **E 维（档位）留不留**（你说"初始权限由声明者给"⇒ 档位可能就是"初始权限"本身） | ⚠️ **倾向：档位 = 初始权限的值域，⛔ 不再是 `L0–L3` 的有序阶梯**（取消 `ordinal`） |
| **5** | **`ProtectionMapGeometry` 要不要随客户端/服务端分家再挪** | ⚠️ 先留 `region/`，分家时再动 |
| **6** | **层序门禁 `check-layer-direction` 的新断言** | ⭐ 提议：`region/authz/` 不许 import `{task, action, job}`；`region/` 不许 import `{task, job}`；`authz/` 不许 import `region/` 之外的**上层** |

---

## §8 ⭐ 执行顺序（你已定："先结构，再逐个清 Zone，两包旧代码一起重构"）

| 步 | 内容 | 门禁 |
|---|---|---|
| **0** | ⭐ **先立不变量**（方案草案 §7 的 5 条） | 2/4 提进 CORE ＋ 新增"授权入口唯一"门禁 |
| **1** | ⭐ **本提案你确认**（§7 六点） | — |
| **2** | **建两包骨架 ＋ 搬 3 个原样类**（`JobAreaRegistry`/`ClaimService`/`MapGeometry`） | 层序门禁改断言 ＋ 搬包同步表 |
| **3** | **拆 `SafeZoneData`**（认领集 → `region/`；黑名单 → `action/`） | `check-protection-install-point` 等 8 处硬写路径 |
| **4** | **拆 `ZoneAuthority` → 三个谓词**（§5） | `A2` §7 的 5 条不变量**逐条仍绿** |
| **5** | **搬 `WritePolicyMatrix`/`WriteBudget`/`WriteAudit` → `region/authz/`** | ⭐ **`protection/ → write/` 这条依赖消失** |
| **6** | **逐个清 `Zone` 类名**（§3 表） | `check-duplicate-class-names` 等 |
| **7** | **`WriteReason` 拆三份 ＋ `WriteGrant` 收窄**（独立一刀） | ⭐ **44/38 文件的引用面** |

⚠️ **步 2–6 是"一起重构"（按你的裁定，⛔ 不零散搬 —— `R5`）**；步 7 因其引用面（44 文件）**单列**。
