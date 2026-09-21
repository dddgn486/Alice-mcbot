# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> # ▶ 压缩后**第一件事**（2026-09-21 深夜更新；上一版"先读勘测报告"**已完成**）
>
> **已完成**：`survey/24`/`25`/`26`+`README` **已完整通读**并整理（结论与逐条对账见 `D-373` 与下面速览）；
> 用户当日裁定 = **「A1+A2 止血 + B 判据实验并行」**，三条挖矿路线顺序 = **③ 鱼骨 → ① 跟随 → ② 探洞**（排在 A/B 之后）。
>
> # ▶▶ 压缩后**先读这 6 行**（2026-09-21 14:0x；P2 已落地，红→绿实证完毕，等客户端实测第七轮）
> - **P2（拾取加固）已完成**：`withinPickupReach` 换**真实拾取盒**（与 `inPickupRange` 共享外扩常量，
>   上界 `1.2 → 1.425`）+ `pickupGoalFor` **不再静默兜底到物品自身格** + 规划前硬不变式
>   （`GoalFoot` 必须可站，新原因码 `no_standable_approach`）+ 决策层信号 `PICKUP_SLOW`/`PICKUP_DETOUR`。
>   决策与证据见 `docs/AI_DECISIONS.md` **`D-375`**、复盘 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` **§11**。
> - **新夹具**：电池步 `collect_slot_approach`（EXTRA，自建空中场景，**4 案例一次跑完**）：
>   `SLOT_REACH`（夹缝：走过去捡到、**一格不挖**）/ `UNREACHABLE`（真够不到：如实退休、物品留在世界）/
>   `SEALED_ROOM`（反证：该破墙时照破 + 决策层收到 `PICKUP_DETOUR`）/ `PICKUP_DELAY`（反证：慢也上报）。
>   新门禁 `[D-375·收集目标可站]` **8/8 注入变红**；夹具**红→绿**：改回修复前形态 ⇒ `failures=7`
>   （含 `UNREACHABLE` 真的挖穿天花板把物品捡走 `worldChanges=1`），恢复 ⇒ `failures=0`。
> - ⭐ **两条被实测逼出来的判据修正**（别再犯）：① 「只看世界最终状态」会漏 —— 修复前 `SLOT_REACH`
>   的 `worldChanges=0` 竟是真的（物品在破格**之前**就被原版拾取范围捞走）⇒ 必须判**收集器挑的目标格**
>   （新观察点 `lastGoalFoot()`，`SUMMARY` 有 `goal_foot=`）；② 事件判据不许读「走位执行过的 Movement 类型」
>   （要等某段**成功**才追加，常恰好是最后一段 ⇒ 实测破了 2 格却 `detour_events=0`）⇒ 改读**运行账增量**。
> - **下一步 = 客户端第七轮（等用户；零参数入口仍是 `/alice mine here`）**：看 `[CollectDrops] SUMMARY`
>   的 `collected=N/N`、`no_approach=`、`slow_events=`/`detour_events=`，以及**是否还有 `cluster_budget` 烧满**；
>   夹缝掉落物应表现为**走到旁边就捡到、不再挖天花板**（若出现"够不到却不捡"，看 `no_standable_approach` 日志）。
> - ⚠️ **P3 重做仍受阻**（`新的世界 (2)` 已被第六轮覆盖）⇒ 需冻结副本，或改成**当场建场景**的深矿几何（推荐）。
> - 其余待办：`canWalkThrough` 全局改名（**建议不做**，117 处/37 文件）；3 条「未门禁」红线待定。
>
> **⭐ 2026-09-21 离线批次（P0 + G1 + G2 + G3 + P1）全部完成并各自反向对照；现在到「需要客户端实测」边界。**
>
> **已完成（都在 `D-374` 有逐条判据；全部 `check-all` 19 通过、CORE 51/52 仅剩既有 `lumber_job`）**：
> - **P0 图补全**：`appendBreakAndEnter` 闸门改「脚位 **且** 头位」；新 CORE 步 `break_enter_head_blocked`（4 用例）；
>   红注入 ⇒ 红态**正是真机症状**（`SEARCH_LIMIT` / 0 条边），`FOOT_BLOCKED` 仍绿 ⇒ 外科式。
> - **G1**：`MovementHelper.bodyPassable` + 4 个目的地闸门 + 新门禁 `rule_edge_destination_body_clearance`（3 注入红）。
> - **G2 ⭐**：新步 `edge_completeness` = **边集差集**（局部谓词「应有边」vs `appendCandidates`「实际边」）。
>   **它量出了 P0 的量级**：把谓词改回只查脚位 ⇒ `gaps=35`（缺的全是 `BREAK_AND_ENTER`，
>   `expected=349 / actual=488`）⇒ **那个一行谓词在普通地形里就砍掉约 20% 的破入边**——
>   `D-373` 的「88 段绕远」极可能就是它。
> - **G3**：`AGENTS.md` 6 条红线全部带标记（**有门禁 4 / 未门禁 2**，未门禁必须写复核触发）；
>   新门禁 `check-redline-gates.sh`（4 注入红）。暴露：**`D-036` 此前零门禁**，而本次缺口正落在它上面。
> - **P1**：挖掘侧 `SEARCH_LIMIT ≠ UNREACHABLE`（`MiningPlanner` 不再并成 `no_reachable`；
>   `MineJob` 暂时性失败不永久了结，重试上限由已有 `attemptFailures` 派生）。S3 规则扩展 + 2 注入红。
>
> **客户端状态**：jar **`2e96d17a22a87952b5297d85bcb4d54d4da3aca970fe0f5a8889e8fb68e9c894`** 已同步到
> `mods/alice-1.0.0-1.20.1.jar`；源码已镜像 `D:\JAVA_projects\alice\`。
>
> **下一步（真机第六轮，等用户；零参数入口 `/alice mine here`）**：
> 1. ⭐ **掉落物是否还会被瞬退**：日志看 `[CollectDrops] SUMMARY … collected=N/N`（N>0），
>    以及**新出现的** `[BreakEnter] cleared pos=<头位方块>`（= 新能力在真机生效）；
> 2. 挖掘是否仍推进、`[SearchTick]` 是否仍每 tick ≤1 次、TPS 是否仍干净（A1 未回归）；
> 3. ⚠️ 观察面：修复**新增了写边**（头位破块）⇒ 若出现「为捡东西多挖一格」，那是**预期行为**；
>    但若出现「到处乱挖/挖穿不该挖的」，立刻停并回报（`WriteBudget` 上限未改，红线未动）。
>
> ⚠️ **P3 已经跑过一次、结果判定为「读数无效」**（探针起点踩空：`432,83,428=air`，因为真机存档
> `新的世界 (2)` 已被第五轮测试覆盖存盘）——**不是**"不可达"。探针已加**读数有效性判据**
> （起点可站 + 至少一次 `nodes>1`），无效 ⇒ 响亮失败。要回答"88 段是否缺口造成"需要
> **一份冻结的 round-4 副本**或**当场建场景的深矿几何**。详见
> `docs/reviews/2026-09-21-B-深矿可达性判据实验.md` 末节。
>
> ⚠️ **第六轮真机（13:30）**：拾取已修好（6/7 簇 `collected=N/N`），但**仍有绕远** ——
两簇烧满 200 tick 被 `retire cluster_budget`（最坏 `collected=0/13`）。根因已定位：
**锚点 = 掉落物自身格（1 格高夹缝、站不住）**，因为 `pickupGoalFor` 的 `best==null` **静默回退**；
于是搜索只能挖 19 段（破 6 格、≈10 s）进那格。**这正是 P2**，详见
`docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §10（含存档取证与 4 条修法）。
>
> **之后的待办**：**P2** 拾取层加固（判据统一 / 失败可 `reanchor` / 到位但够不到时朝物品走一步 /
> `best==null` 补日志）——⚠️ **端到端 `collected=1/1` 夹具尚未建**（P0 的夹具是规划级）；
> **P3** 只用 **1× 预算**重跑 `single:mine_reach_probe`（G2 已强烈暗示 88 段是缺口造成的，P3 用来证实）。
