# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> # ▶ 压缩后**第一件事**（2026-09-21 深夜更新；上一版"先读勘测报告"**已完成**）
>
> **已完成**：`survey/24`/`25`/`26`+`README` **已完整通读**并整理（结论与逐条对账见 `D-373` 与下面速览）；
> 用户当日裁定 = **「A1+A2 止血 + B 判据实验并行」**，三条挖矿路线顺序 = **③ 鱼骨 → ① 跟随 → ② 探洞**（排在 A/B 之后）。
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
> **之后的待办**：**P2** 拾取层加固（判据统一 / 失败可 `reanchor` / 到位但够不到时朝物品走一步 /
> `best==null` 补日志）——⚠️ **端到端 `collected=1/1` 夹具尚未建**（P0 的夹具是规划级）；
> **P3** 只用 **1× 预算**重跑 `single:mine_reach_probe`（G2 已强烈暗示 88 段是缺口造成的，P3 用来证实）。
