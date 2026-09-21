# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> # ▶ 压缩后**第一件事**（2026-09-21 深夜更新；上一版"先读勘测报告"**已完成**）
>
> **已完成**：`survey/24`/`25`/`26`+`README` **已完整通读**并整理（结论与逐条对账见 `D-373` 与下面速览）；
> 用户当日裁定 = **「A1+A2 止血 + B 判据实验并行」**，三条挖矿路线顺序 = **③ 鱼骨 → ① 跟随 → ② 探洞**（排在 A/B 之后）。
>
> # ▶▶ 压缩后**先读这 8 行**（2026-09-21 15:0x；第八轮真机已复盘，`D-376` 已落地，B/C/D 等用户定方向）
> - **第八轮真机（14:19–14:22，bot `tango`，`/alice mine here @610,66,91`）三个事实**（复盘 §13）：
>   ① **拾取绕远没复现**（9 簇里 8 簇逐字 `N/N`，收集侧零破块）；唯一一次失败被 14:17 同步的 `approach_probe`
>   抓到**精确几何** ⇒ 残差**定量**了：物品压在格远角（格内偏移 **0.875**）+ bot 停在离心 **0.49** 处，
>   而 `withinPickupReach` 假设 bot 站正中心 ⇒ 模型以为还有 **0.05** 余量，真机 `inRange=false`
>   （上界 ≈ **0.49 + 0.375 ≈ 0.87**）。⚠️ 探针有**盲区要修**：只探了 `dy=0` 的 12 格，漏了
>   `pickupGoalFor` 真正会搜的 `dy=-1`（bot 自己站的那格就被漏掉，读数误导）。
>   ② **搭石斜下被卡 = `PLACE_STEP_AND_TRAVERSE dy=-1` 没查「过渡空间」**（用户目视确认了第三层那块石头）：
>   `segment_stall … pos=…,94.300 delta≈0 forward=1.00 toBlock=空气 headBlock=空气 segmentTicks=222`（×2，约 20 秒）
>   ⇒ 挡住 bot 的只能是**身体扫掠盒覆盖、闸门不查的那一层**（`to.above(2)`）。**已修**：规划侧 + 执行侧
>   统一用 `canSweepPlayer`（与 `canDescend` 同一谓词；`ASCEND_NO_HEADROOM` 早就查 `from.up2`）⇒ `D-376`。
>   ③ **落水自锁 + 溺水**：`BREAK_AND_TRAVERSE` 跨 2 格把**中间格**（自己刚站过的台阶）破掉 ⇒ 掉进水里；
>   水里 `ASCEND_INVALID_PRECONDITION` ×2、`PILLAR_NOT_ON_GROUND` ×11 ⇒ 沉底、`air 300→-2`、掉血 20→10，
>   **任务未中断**。⚠️ **更正我上一版的说法**：水里**做过**（`shouldHoldJumpInWater` = `D-243`/`D-251`，
>   `AscendExecution` 水分支 + `SurvivalExitCheckTask` 端到端「从水里出来」都有）—— 但**浮起是"某个 ASCEND 段
>   正在执行"的副作用**；本次 ASCEND 段全部因 bot 在下沉而立刻过期 ⇒ 没有任何段按住跳跃 ⇒ 继续下沉。
>   用户补充：**落水点附近的可站点都比水平面高一格，正常浮水跳不上去（要在岸边搭方块才行）**。
> - **已落地 `D-376`**（本次 A 修复）：`SurfaceMovementProvider.appendPlaceStepAndTraverse` + `PlaceStepAndTraverseExecutionFactory.validate`
>   补扫掠空间判据（后者带 `NO_SWEEP@…几何`）；新门禁 `[D-376·高度变化查过渡空间]`（**7/7 注入红且各命中专属条目**）；
>   新夹具 `PlaceStepDescendClearanceCheckTask`（电池步 `place_step_descend_clearance`，EXTRA，2 用例互为对照）：
>   `[PlaceStepClear] checks=24 failures=0 → PASS`，读数 `BLOCKED edge=false sweep=false factoryValid=false code=…NO_SWEEP`
>   / `CLEAR edge=true sweep=true factoryValid=true`。
> - 🔬 **B 的机制尚未钉死 ⇒ 已加临时探针（零行为改动，取证后删）**：`BotManager.BotSession.tick(HazardState)`
>   入口与 `decide` 之后各一条 `[SurvProbe]`（每 20 tick 一条；`grep SurvProbe logs/latest.log`）。
>   **为什么必须探**：`decide(LOW_AIR, duration ≥ 10)` 在代码上**只可能**返回 INTERRUPT / FLOAT_UP /
>   ABANDON_NO_EXIT，而这三条分支**都留 WARN 日志**（含 `任务因维生危险中断`）；第八轮真机里
>   `air 300→-2`、掉血 20→10 共 340 tick，**一条都没有** ⇒ 代码与实测矛盾 ⇒ 先钉机制，不许猜着改。
>   判读口径写在探针注释里：全无 ⇒ `session.tick(hazard)` 没被调到；`task=null` ⇒ 卡在提前返回；
>   `enter` 有 `verdict` 无 ⇒ `decide` 之前返回/抛异常；有 `verdict` 无分支 ⇒ 判决值不在三条分支里。
>   ⚠️ **更正**：上一版我说"水里完全没做"是**错的** —— `SurvivalFloatTask`（`D-237`）、`shouldHoldJumpInWater`
>   （`D-243`/`D-251`）、`SurvivalExitCheckTask` 的端到端「从水里出来」都在，且 `single:survival_exit` 仍 PASS。
> - **等用户定方向（都已给详细方案，见对话/复盘 §13.4）**：
>   **B** 水中上浮（新能力 or 放宽 ASCEND 的水中分支 + 维生「无出口也可否决」）、
>   **C** 禁「破掉自己唯一落脚点」的破坏性 fallback、
>   **D** 拾取残差改「失败即排除该格、取次优」+ 修探针盲区。
> - 其余待办不变：`canWalkThrough` 全局改名（**建议不做**）；3 条「未门禁」红线待定；P3 需冻结副本或当场建景。
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
