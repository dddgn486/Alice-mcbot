# 回归电池分档与维护规则（D-197）

> 用户 2026-09-13 要求：*"我觉得需要做电池回归管理了，现在电池过程太长了，电池的回归项只测**必要基础项**和**当前主线项**，
> 每次有新场景、更换主线都要更新，**由你来管理**。"*
> 本文就是那套管理的**唯一说明书**；配置的**唯一入口**是代码里的
> `RegressionBatteryTask.CURATION`（一张按名字的归属表），两处**必须同步**。

## 1. 三档含义

| 档 | 含义 | 默认跑？ |
|---|---|---|
| **BASELINE** | **必要基础**：坏了就不能信任 bot 的任何动作（移动内核 / 写入闸门 / 破坏性路径 / 生产 Job 闭环） | ✅ |
| **MAIN** | **当前主线**：这一轮正在做的能力（今天 = 阶段 3-A 工作站 + 熔炉） | ✅ |
| **EXTRA** | 已验收、与主线无关、或耗时/需长期观察的项 | ❌（`/alice battery full` 才跑） |

跑法：`/alice battery core`（默认，= BASELINE + MAIN）｜`/alice battery full`（全量）｜`/alice battery list`（看归属表）。
游戏内物品 `alice:regression_battery` 右键 = **CORE**。

SUMMARY 会打印 `PROFILE=core baseline=… main=… extra_skipped=…`：
**跳过多少、跑多少、各档几项**一眼可见；归属表与实跑项对不上（漏登记 / 文档说测了其实没测）**直接判红**。

## 2. 分档理由（**逐条清单与项数见生成物，本文只留"为什么"**）

> ⭐ **2026-09-23（`B3` / `Q-22`）**：本节的三个小节**不再是清单** —— 93 项的**清单/档位/CORE 序号**
> 由 `docs/CAPABILITY_LIST.md` §4 **从代码生成**（生成器 `tools/capability-list.py`，
> 门禁 `tools/check-capability-list.sh` 已挂 `check-all`）。
> **为什么改**：本节的手写清单和小节计数**此前长期漂移**（标题曾写 15/35/15，真值 15/26/52 ——
> 见下面那条"本节标题计数长期漂移"的认领），而"靠人读才发现"正是 `survey/29 §3.8⑥` 要根治的病。
> ⇒ 现在**"哪一步在哪一档"只有一个家**（代码里的 `CURATION` → 生成的清单），本节的文字是
> **理由叙述**（人写的知识，不可生成），**不许再自称清单或计数**（门禁会因此判红）。

> ⭐ **2026-09-22 校正（CORE 53 → 41，`D-407`/`D-408`）**：用户「整理下 CORE 内容，**次要的剔除**」。
> **降级 12 步 ⇒ EXTRA（一步没删，FULL 仍全覆盖）**：
> `fall_execute` · `pillar_execute`（自证前提的诊断，同坐标同 Movement 已由 BASELINE 的 `pathing` 覆盖）·
> `craft_probe_inventory` · `craft_probe_table` · `craft_probe_upgradetab` · `machine_route`（只读探针，零写入）·
> `craft_action` · `craft_station` · `craft_station_provision` · `craft_station_craft`（`D-201` 原名单，**扣掉 `craft_table`**）·
> `machine_station` · `machine_cycle`（阶段 3-B 已验收、非当前主线；`craft_machine` 是同一闭环的生产入口，保留）。
> **刻意保留**：`craft_table`（CORE 里**唯一**的原版 3×3 `CraftingMenu` 执行覆盖）· `coarse_goal_prefix`（用户点名，4 tick）。
> **新增**：`ledger_zone_scope`（**EXTRA**，`Z1`/`D-398` 的地理范围判据，`LedgerModule`）。
> ⇒ BASELINE 15 + MAIN 26 + EXTRA **52** = **93**，**CORE 41**。
> **同片修掉两个真缺陷**（与本瘦身**是两件事**，别用降级代替修复）：① `CleanupWrappedTask` 把内层 `FAILED`
> 报成 `DONE` ⇒ `fall_execute`/`pillar_execute` **结构上不可能红**（已透传）；② **电池把场景函数跑在冷区块上**
> （旧顺序 `scenes → provision`，编排器早已是 `provision → scenes`）⇒ `/fill` 静默失败、`/setblock` 随区块卸载
> 回滚 ⇒ 夹具拿着没有地形的世界做判断（`craft_table` 实测；已对齐并加 `scene rc` 日志）。
> ⭐ **纪律（`D-201` 附注一）**：撤步骤必须**复跑 CORE 逐步 diff** —— 本轮已做（53→41 两轮比对：
> 除被撤 12 步外判决逐条不变、存活步 **0 位移**）。


> **2026-09-20 校正（3）**：`region_maintain_unmaintainable` 进 EXTRA（`D-349`/勘测侧 Pit 2）——
> **`MAINTAIN` 的"不可维持"判据**：常驻区域作业不许"看起来在跑、其实终态已不可达"。
> 判据四条 = 前提（无树无苗不欠树）∧ 触发（≤400 tick 内登记 + 上报"可做什么"）∧ **不越权**（登记时仍 `RUNNING`）
> ∧ 恢复（注入欠树+苗 ⇒ 标记清除且真补种）。反向对照（退回旧行为）红 5 条。
> ⇒ BASELINE 15 + MAIN 36 + EXTRA **23** = **74**，CORE 仍 **51**。
>
> **2026-09-20 校正（2）**：`scope_pending_grace` 进 EXTRA（`D-348`：**登记被推迟**的现场取证 ——
> 新鲜区块里同一 tick 建地形 + 破坏方块 ⇒ 掉落物在 tick 末还没被登记进世界，而它在**宽限窗口**内真的会出现。
> 判据两条：① 前提 = 本轮确实观察到推迟；② 期望 = 它最终被登记（`liveDrops()` 非空且归属为我方）。
> 旧实现（窗口=0）注入 ⇒ 两条同时红）
> ⇒ BASELINE 15 + MAIN 36 + EXTRA **22** = **73**，CORE 仍 **51**（`core` 实跑佐证：`extra_skipped=22 passed=51/51`）。
>
> **2026-09-20 校正**：`mine_run_metrics` 进 EXTRA（`D-347`：**运行账的现场取证** —— 孤立矿道里连跑 3 次
> 真 `MineJob`（每次配额 2）＋ 1 次**反向对照**（目标类型不在场景里）⇒ 断言"到达率 / 世界改动数"是
> **量出来的**、且**到达率真的会小于 1**（3/4）。它自建地形（垫层 + 行走层 + 8 块矿）⇒ 只适合
> `single:mine_run_metrics` / `module:mining`，CORE 跳过）
> ⇒ BASELINE 15 + MAIN 36 + EXTRA 18 = **69**，CORE 仍 **51**。
>
> **2026-09-19 校正（5）**：`task_zone` 进 EXTRA（`§5.12` 第 4 件的**几何 + 锁定**层，`D-338` 附注六：
> 工作区域（方块级）⇒ 任务区（区块级**最小覆盖**）+ 可覆盖保护区父类 / **不得**覆盖安全区（报错，不裁剪）
> + 随 `scopeId` 生灭 + **真跑一次 `RegionLumberJob`** 的两条路径；收尾按增量还原临时认领）
> ⇒ BASELINE 15 + MAIN 36 + EXTRA 17 = **68**，CORE 仍 **51**。
>
> **2026-09-19 校正（4）**：`safe_return` 进 EXTRA（`D-327` 机制 B：**任务失败后回安全区的兜底** ——
> 无认领区 ⇒ 诚实码 `return_no_safe_zone`；200 格外 ⇒ **分段走回认领区块**（2 段 / 732 tick）；
> 封死格 ⇒ `return_unreachable` 且**站定不动**）⇒ BASELINE 15 + MAIN 36 + EXTRA 16 = **67**，CORE 仍 **51**。
>
> **2026-09-19 校正（3）**：`place_step_diagonal` 进 EXTRA（`D-336`：斜向上升那一格的**规划级**门禁，三用例互为反证）
> ⇒ BASELINE 15 + MAIN 36 + EXTRA 15 = **66**，CORE 仍 **51**。
>
> **2026-09-19 校正（2）**：`mine_inventory` 进 EXTRA（**矿侧容量守卫的门禁** —— 矿侧此前**完全没有** `inventory_full` 覆盖）
> ⇒ BASELINE 15 + MAIN 36 + EXTRA 14 = **65**，CORE 仍 **51**。
>
> **2026-09-19 校正**：`far_path_bench` + `path_retry_bench` 进 EXTRA（**测量用基准，不是判据**）
> ⇒ BASELINE 15 + MAIN 36 + EXTRA 13 = **64**，CORE 仍 **51**（`core` 实跑 SUMMARY 佐证：`baseline=15 main=36 extra_skipped=13 passed=51/51`）。
> 这两步会**改世界**（走廊 + forceload 40+ 区块 / 一个封死房间），所以**只适合 `single:` 单独跑**，CORE 与全量档都会跳过。
> **2026-09-19 更正**：`far_path_bench` 已**不只是测量** —— 它同时承载"内核从不加载区块（红线 `D-132`）"、"被边界挡住不许报 `UNREACHABLE`"、"夹边界的一跳必须线性"、"300 格远距离旅行真的到得了 + 精确落脚能组合"这几条**有红绿的判据**（`D-337` / `附注一` / `附注二`，共 19 项检查）；曲线本身仍不设通过/失败。
>
> 2026-09-18 校正：本节标题里的计数长期漂移（原写 46/38）⇒ 本次按 `RegressionBatteryTask.CURATION`
> **逐条数了一遍**：BASELINE 15 + MAIN 36 + EXTRA 11 = **62**，CORE = **51**（2026-09-18：`bot_ownership` 进 MAIN、`bot_pair_no_recurse` 进 EXTRA、`break_refused` 进 MAIN）。

> 2026-09-14 校正：本节此前写「34 项 → CORE 24 项」**已过期**（实际 = BASELINE 13 + MAIN 14 + EXTRA 10 = 37，CORE = 13+14 = 27，与客户端实测 `(27/27)` 一致）。
> 加入 `write_policy`（BASELINE）后为 **38 / CORE 28**；加入 `machine_cycle`（MAIN）后为 **39 / CORE 29**；
> 加入 `craft_machine`（MAIN，(c) 增量 2 / D-217）后为 **40 / CORE 30**。
> 2026-09-15 校正：加入 `mine_menu`（MAIN，M1 / G1：挖矿候选菜单契约）后为 **41 / CORE 31**。
> 2026-09-15 校正（2）：加入 `no_progress`（MAIN，M2 / G2：长作业周期复评）后为 **42 / CORE 32**。
> 2026-09-15 校正（3）：`llm_contract` **EXTRA → MAIN**（M4：它现在含"失败事实必须是字段"的门禁）后为 **42 / CORE 33**。
> 2026-09-15 校正（4）：加入 `mine_no_tool`（MAIN，M3 / G3 归因：缺工具必须报 `tool_missing`）后为 **43 / CORE 34**。
> 2026-09-15 校正（5）：加入 `survival_exit`（**BASELINE**，S-5 / D-226：维生决策表 + 出口可达 + 脚位口径 + 掉血可见）
> 后为 **44 / CORE 35**。**这是维生的第一个电池步** —— 此前 `Survival` 在电池里命中 0（只能真人验）。


### BASELINE —— 理由叙述（逐条清单见 `docs/CAPABILITY_LIST.md` §4）
`pathing`（移动内核聚合 + 覆盖断言）、`write_budget`（写入预算/授权闸门）、`mine_regression`（挖掘闭环 11 用例）、
`mine_job`、`lumber_job`（两个生产 Job 的最小闭环）、`transfer`（L2 容器传输——破坏性最强）、
`clear_guard` / `clear_retry` / `scaffold`（三条破坏性路径的守卫与建拆同权）、`partial_search`（`SEARCH_LIMIT ≠ UNREACHABLE`）、
`capability_gate`（闸门）、`tool_supply`（不凭空变工具）、`recoverability`（可回收性等级）、
`write_policy`（D-207 ①：写入集中策略表——表完整性 + **越权必须被拒**的负例 + 未登记 requester 留痕=0）
`survival_exit`（S-5 / D-226：维生决策表（软危险 = 溺水/着火 需"宽限 + 有出口"才否决、无出口**不否决**）、
封闭场景四层前提自证、**真实着火** ⇒ 不否决 + `exit=none decision=continue`、脚位格口径回归（半砖）、
出口真能走到、掉血 ⇒ `DANGER`（`delta=`）且不刷屏）

### MAIN —— 理由叙述（阶段 3-A（回退瘦身，保持完整）+ 阶段 3-B / S1+S2（机器只读）+ S4 + (c) 增量 2（机器写入：夹具 + 生产））
> 2026-09-18 起含 `bot_ownership`（**D-319** 假人归属：创建者登记 / 认领单向 / 存档往返 / 老存档不猜；判据 20 条、约 5 tick、不写世界）
`decision_contract`（③/策展：从 EXTRA 提到 MAIN —— 它自己的类注释写着"任何改动都跑得到"，
而档位在 EXTRA ⇒ CORE 跑不到；200 tick、纯逻辑、不调 LLM、不改世界 ⇒ 与 M1/M2/M4 三次提档同一理由）、
`mine_no_tool`（M3：缺工具必须报 `tool_missing`，不许被总括码盖掉）、
`mine_stale` / `mine_budget`（**M3b / D-232**：两条"照词表写了却从没被观测过"的归因映射各配一个确定性夹具——
身份复检恒假 ⇒ 全 `target_replaced` ⇒ `stale_target`；本作用域预算压 0 ⇒ 全 `WRITE_BUDGET_EXHAUSTED` ⇒ `write_budget_exhausted`）、
`craft_check`、`craft_action`、`craft_table`、`craft_station`、`craft_probe_inventory`、`craft_probe_table`、
`craft_probe_upgradetab`、`craft_station_provision`、`craft_station_craft`、`craft_furnace`、`craft_cooking`、`craft_goal`、
`machine_route`（S1：机器配方**只读**——问上游自述读输入/输出 + 查询层给 `MACHINE_ROUTE`；模组不在 ⇒ SKIP）、
`machine_station`（S2 起为只读站点探针；**S3 起按 `MachineMap` 认机器**——半径内表里登记的方块每类一台（`m1_*`/`m2_*`），
断言"菜单类 == 已实测登记值"与"方块实体自述配方类型 == 表里的类型"，并读槽位表/`ContainerData`/上游进度方法名；机器不在 ⇒ SKIP）、
`machine_cycle`（**S4：单机最小闭环**——放料 → 等 → 取产物，是 3-B 的**第一次容器写入**：
口径 = `WriteBudget.consumeContainerWrite` + 理由 `CONTAINER_TRANSFER` + requester `machine-cycle`，
按结果验证而不猜槽位；判据看 `verdict=PASS` + `energy_source=cube（场景电源，未补电）`；
⚠️ 场景必须 `/data merge block` 灌电（创造方块放下是 0 J，D-213）；机器不在 ⇒ SKIP）
`craft_machine`（**(c) 增量 2 / D-217：机器路线的生产路径** —— 起**真 `CraftJob`**，由它自己的 `MACHINE_ROUTE`
分支完成"走 → 开 → 电 → 放料 → 等 → 取"。与 `machine_cycle` 是**同一份闭环实现**（`task/craft/MachineCycle`），
区别在入口：本步走**生产**入口（查询层判 `MACHINE_ROUTE` → `MachineMap` 的**数据驱动执行准入** `EXECUTABLE`）。
夹具只做三件测试专属的事：传送到平台远角、挑"只能靠机器做出来"的目标物（用生产查询层现场复核）、按前提备料；
判据 = `CraftJob` 终态 COMPLETED + 背包里目标物真的 +1 + 机器路线事实里 `walk_state=DONE`/`product_landed=true`/
`machine_emptied=true`。**不补电**（没电 ⇒ `machine_no_energy`，D-216 红线①，门禁机械保证）；机器不在 ⇒ SKIP）

`protection_zones`（**设计线 · 保护区（D-313，2026-09-18）**：`SafeZoneData` 从"水平圆形半径"改成 ⭐ **区块级 2D 认领**
（忽略 Y ⇒ 覆盖全高度；旧格式自动迁移 + 计数可查，不静默丢）⇒ 断言"认领的区块**全高度**拒绝 / 取消即时生效 /
单一安全入口 `BlockBreakSafety` 两条策略都看得到 / `save→load` 往返 / 迁移换算 = **与该圆相交即认领**（期望值在夹具里独立算）/
黑名单回归 + **自清理**（认领与规则都会持久化 ⇒ 收尾必须复原并断言）"。纯查询、不写方块、不传送、不生成区块 ⇒ 成本≈0
（约 10 tick）；**追加在步表末尾** ⇒ 既有 48 步次序一个格子都不动 ✓）

> **为什么退回来了**：2026-09-13 实测——把其中 8 项移出 CORE 后，`craft_furnace`/`craft_cooking`/`transfer`
> **可复现地变红**（重启客户端后仍红），而它们在 FULL（35 项）里**全绿** ⇒ 撤走的是它们的**隐含前置/清场**。
> **瘦身的前提**是"每个夹具自己显式自证前提、顺序无关"；该前提未落地前不允许再减 CORE 项。
> 恢复路径：先做"显式自证前提"（菜单身份/位置/方块实体状态/账本与归因时间窗）⇒ 再**逐条**撤，**每条复跑一次**。

### EXTRA —— 理由叙述（逐条清单见 `docs/CAPABILITY_LIST.md` §4）
`lumber_failure`、`region_maintain`（区域常驻 Job，耗时）、`decision_contract`、`decision_trace`、
`permission_gate`、`pickup_gate`、`collect_job`、`recipes_dump`、`event_thresholds`、
`bot_pair_no_recurse`（2026-09-18，D-320：两假人相邻不爆栈）、
`far_path_bench`（**测量基准**，D-328：距离→节点/毫秒曲线 + 加载边界 + 执行器行走；
会 forceload 40+ 区块并写/清走廊 ⇒ 只 `single:far_path_bench`）、
`path_retry_bench`（**测量基准**，D-328 附注 + D-331 门禁：失败重试节奏 + "未加载目标不许被同步加载"；
会造/拆一个封死房间 ⇒ 只 `single:path_retry_bench`）、
`place_step_diagonal`（D-336：**斜向上升那一格** —— 通用信封 / **挖矿信封（不含 PILLAR）** / 纯通行信封三用例；规划级）、
`mine_inventory`（D-335：矿侧容量守卫门禁 —— 前置满包 ⇒ 一格不动；作业中满包 ⇒ **恰好只少 1 格**；
判据钉在"世界有没有被白改"上，先红后绿实测）、
`safe_return`（D-327 机制 B：**任务失败后回安全区的兜底** —— 三用例：无区 ⇒ `return_no_safe_zone`、
200 格外 ⇒ 分段走回**认领区块里**、封死格 ⇒ `return_unreachable` + 原地不动；夹具自己认领/取消
+ 自建/还原封盒 ⇒ 只 `single:safe_return`）
`collect_slot_approach`（`D-375`：**掉落物「够得着的可站格」** —— 1 格高夹缝 ⇒ 必须走到可站邻格去捡、
**一格都不许挖**；无任何可站邻格 ⇒ 如实 `no_standable_approach` 退休、**不许挖穿天花板**；
反证①封死石室（世界修改已授权）⇒ 合法破墙 + 决策层收到 `PICKUP_DETOUR`；
反证②永久 `PickupDelay` ⇒ 决策层收到 `PICKUP_SLOW`。自建空中场景、4 案例一次跑完、约 220 tick）
`survival_escape_air`（`D-383`：**逃生途中的空气告警** —— 真机那次逃生路线钻了水下 7 秒
（`air 284→158`），而 `escapeTask=true` 时维生三条救援分支全被排除 ⇒ 逃生途中零动作。
夹具真驱动一个 `SurvivalExitTask`，三相位钉住「告警的触发与松开」：干地不许按 / 眼在水里 + `air=60`
必须激活 / `air=300` 必须解除；判据用 `airAlarmActive()`（**不能用** `isJumping()` —— 执行器自己
爬出水面时也按跳跃）。自建孤立水池、约 40 tick。红对照：撤掉告警调用 ⇒ 恰好红在那条）
`survival_stop_in_hazard`（`D-384` / B3：**有活动危险时延后停止不许落地** —— 本链条起点。
第二个假人（夹具不能停自己）+ 3×3 石壳 + 1 宽×4 高竖井（无别的可站格 ⇒ 软危险落 `HOLD_NO_EXIT`）
+ `ON_FIRE` + **空中**提延后停止 ⇒ 危险中 60 tick 任务必须还在、灭火后 2 tick 内落地。
红对照：撤掉 `&& !hazardActive` ⇒ 恰好红在那条）
`survival_shore_escape`（`D-382`，用户 2026-09-21 第十二轮裁定：**无任务的水下 bot「先浮 → 再走上岸」**，纯通行、零写权）
—— 两个案例：① 5×5×3 水池 + 四周天然干地（岸在 2 格外）⇒ 先起 `SurvivalFloatTask`、**再**起
`SurvivalExitTask`、最终**真的离开水**，且**世界零改动**；② **反证**：21×21 水池（17³ 搜索盒里无落点）
⇒ 照样浮起来，但**永远不起逃生任务、永远不上岸、零改动**（用户口径③：没有现成站位就如实失败，不造站位）。
前提用**节点预算**直接问同一张图（生产预检是**时间预算**、浸没时会不稳地返回「可规划」⇒ 不许拿它当前提）。
自建水池、夹具自己传送/复位、约 240 tick×2。红对照：撤掉找岸档 ⇒ `failures=3`（全在「必须走上岸」那组）
；真人入口 = `/alice shore-escape-test`（零参数：建孤立场景 + 入池 + 清任务 + 按住决策层））
`mining_water_break_cost`（`D-385`：**规划期的挖掘成本必须等于执行侧真值 —— 含 vanilla 的两项状态惩罚**
- `mining_search_limit_honesty`（EXTRA，`D-387` + ⭐`D-435`/`P1-d`）：搜索限流诚实性 —— ① 同 tick 占满 A1 额度 ⇒ 理由必须是 `search_incomplete`；下一 tick 同一目标必须能规划（红臂：三条腿还原 ⇒ `found_but_unminable`）。② ⭐ **`PARTIAL`（跑了、烧光预算、只有前缀）同等对待**（真值表 + 条件式行为臂，另断言规划器那次搜索真的跑起来）。
- `coarse_goal_prefix`（MAIN，`D-390`）：**粗目标 + 滚动重规划**的核心断言进 CORE（原来只有 EXTRA 的 `far_path_bench` 测这件事）：自建 8 格走廊 + 400 格外未加载目标区 ⇒ 不许 `GOAL_NOT_LOADED`/`UNREACHABLE`、必须有前缀、跑完不许读未加载区块（红臂：预算 1ms ⇒ FAIL）。
- `fishbone_slice1`（EXTRA，`D-386`，`J-1.1` 鱼骨切片 1）：**模板推进 + 不搜索**的行为级判据 —— 三臂一次跑完（① 实心石体挖通主巷 ⇒ `C1` 模板=事实 / `C3` 搜索规模恒定且零 `SEARCH_LIMIT` / `C4` 可返回；② 前方基岩 ⇒ `main_blocked:<码>` 且**先返回起点**、那一格与之后零改动；③ 起点被封 ⇒ `start_unreachable` 且**零世界改动**）。⭐ 顺带量两条读数（`survey/32` 发现二）：**每轮规划耗时** + **每格 tick 成本**。判据落在**世界事实**上（逐格期望表 + `WriteAudit` 破坏条目必须全在模板格内）。自建孤立石体 ⇒ 不依赖真机存档、也不依赖前序模块。
- `mine_vein_propagation`（EXTRA，`D-389`）：**沿脉传播**的行为级判据（石壳+3×2×5 铁矿脉）：传播必须触发 + 沿脉走后零昂贵搜索 + 完成度不许退化（已测上限 12/30，`D-391` 待收口）。⚠️ 约 130 s/轮（跑一个真实作业）。
（`isEyeInFluid(WATER) && !hasAquaAffinity ⇒ ÷5`、`!onGround() ⇒ ÷5`）。自建空中水池（5×5×5 石箱内挖
3×3×4 水池）+ 同高干燥踏板，被测几何 = `from(1,0,0) → mid(2,0,0)=石壁 → to(3,0,0)`。五个用例覆盖四个
`(眼在水里, 在地面)` 组合（`DRY_GROUND/WATER_SURFACE/SUBMERGED_GROUND/SUBMERGED_FLOAT/DRY_AIRBORNE`）
⇒ 逐用例断言 `estimateBreakTicks == 1.0F/getDestroyProgress`；再用生产边生成器断言"同一条
`BREAK_AND_TRAVERSE` 边浮在水面时破坏项贵 `(vanilla_湿−vanilla_干)/6`"（**期望锚在 vanilla 真值上**，
锚在 estimate 上则永远绿）+ 代价结论「陆地挖 < 放一块 < 水里挖」⇒ 规划器**自然**偏向放置。
约 35 tick、零执行。红对照：撤掉惩罚 ⇒ `failures=7`（4 条估计值 + 1 条规划器差额 + 2 条代价结论），
28 条前提/自洽判据全绿）
`collect_offcenter_retry`（3-b / `D-381`：**「到位却够不到」⇒ 换格再试** —— bot 站进"模型说够得着"的那一格、
但**离心 0.49** 站着（真机第七/八轮那一幕）⇒ 必须把该格记进本簇排除集、取**次优**格、真的把物品捡回来
（`collected=1` + `goal_excluded=1` + 世界零改动）；顺带钉 `approach_probe` 与 `pickupGoalFor`
**枚举同一批格**（含 `dy=-1` 层 —— 第八轮那份 `standable=0` 误读正是探针只扫 `dy=0` 造成的）。
自建空中孤岛、夹具自己传送/复位、约 30 tick。红对照：撤排除集 ⇒ `collected=0`；探针改回只扫 `dy=0` ⇒ 红在 `D0` 那条）
`place_step_descend_clearance`（`D-376`：**搭石斜下的「过渡空间」** —— 规划级：`to`/`to.above()` 空气、
`to.below()` 缺支撑（正要放一块），但 `to.above(2)` 实心 ⇒ **不许**生成 `PLACE_STEP_AND_TRAVERSE` 边、
执行工厂必须以 `NO_SWEEP` 拒绝；反证：去掉那块 ⇒ 这条边**必须**生成且工厂接受。
它钉的是真机那 `segment_stall … pos=…,94.300 delta≈0 forward=1.00 segmentTicks=222`（×2）的
「顶着格边界原地走」；零搜索、毫秒级、2 用例一次跑完）
`survival_idle_drown`（`D-377`：**无任务的 bot 也必须被维生接管** —— 真机里「任务被延后停止清掉后在水里沉底、air 300→-2、health 20→1.0 而维生零动作」。夹具用**第二个假人**（天然无任务）跑生产路径，自带 1 格宽水井场景；判据 = 自救在空气还够时就开始了（`firstTaskAir > 0`）+ 头露出水面 + 空气回到 `AIR_SAFE`，且共享分类表仍是 `WATER_CONTACT`（沉底档只对无任务生效））
`head_blocked_route_closure`（`D-378`：**`survey/27 §3 #1` 的收口判据** —— 「88 段绕远是不是那一行谓词造成的」。
当场自建基岩隧道（5 格「脚位空 + 头位实」的夹缝直挖路 + 一条 46 格绕远，脚位层上下焊死 ⇒ 只剩平面移动），
**同一条请求跑三遍**：生产 provider ⇒ 直挖路 **7 段**（其中 5 段正是旧谓词拒掉的那一类）；
**旧谓词边过滤**（夹具自带 provider 包装，不改生产代码；等价性可算）⇒ **47 段**绕远且不含那类边；
旧谓词 + 紧预算（40 节点）⇒ 到不了且预算打满（复现 `SEARCH_LIMIT` 的机制）。自建并还原约 1 000 格、3 次小搜索）
`break_traverse_footing`（`D-379`：**「破坏通行」破掉的中间列是 bot 要踩过去的一格 ⇒ 它必须立得住**
—— 真机 `BREAK_AND_TRAVERSE from=632,64,95→to=632,64,93` 破掉中间格 `632,64,94` 后 0.9 秒，bot 在
`632,62,94`（中间列正下方、水面）⇒ 从中间列掉进水里 → 沉底 → 溺水。规划级（边生成层 + 执行工厂准入，
两侧同谓词 `canWalkOn(mid)`），3 用例：悬空+水 ⇒ 不许生成 / 悬空+浅坑 ⇒ 同样不许（契约判据）/
立在地板上 ⇒ 必须生成（反证）。自建并还原 3 格场景 + 1 次边生成，毫秒级）

## 3. 维护规则（我 = AI 负责执行）

1. **新增场景/新夹具** ⇒ 同时在 `CURATION` 与本文登记；新能力默认进 **MAIN**（还没验收前）。
2. **一条能力验收通过、且不再是主线** ⇒ 从 MAIN **移到 EXTRA**（保留可跑，不占默认时长）。
3. **任何"坏了就不能信任 bot"的新守卫**（新的破坏性路径、新的写入维度） ⇒ 进 **BASELINE**。
4. **换主线**（例如从"工作站"转到"熔炉/决策层接线"） ⇒ 重写 MAIN 一节，并把退场项移入 EXTRA。
5. **跑哪一档 = 看改了什么（用户 2026-09-19 裁定，D-332）** —— 别把"每改一点就全量"当稳妥：
   | 改了什么 | 跑什么 | 实测代价 |
   |---|---|---|
   | 只碰文档/规则 | **只跑文档类门禁**（三件套行数 / `check-step-names` / `check-fixture-hygiene`） | ~秒级 |
   | 单个功能/夹具/一条路径 | `single:<步>`；同模块多项 ⇒ `module:<id>`（可 `--no-build`） | 25~90 s |
   | **收口**（多提交收束、交付给用户测之前、换主线） | `core`（51 步）→ 必要时 `full` + `check-all.sh` | core ≈250 s / `check-all` ≈5 min |

   理由：模块化之后**改动的影响面是可知的** ⇒ 用 `single:`/`module:` 就能拿到同等信息；
   CORE/全量只负责"**跨模块回归**"这一件它们独有的事 ⇒ 它该在**收口点**跑，不是每步跑。
5. 每次改动都要在 `docs/AI_DECISIONS.md` 留一条记录（为什么进/出哪一档），并给出**当轮的 CORE 项数**。
6. 归属表与代码不一致时电池会判红 —— 那是**设计如此**（防止"文档说测了、其实没测"）。

### 3.1 两类**不组进电池**的模块（不是漏登记，是设计）

| 模块 | 为什么不在电池里 | 怎么跑 |
|---|---|---|
| `harness_self` | 编排器自检（**故意**被外部命令打断两次）⇒ 期望判决 = **FAIL** | `module:harness_self` |
| ⭐ **翻面示例（已完成，别照抄"临时模块"这一步）**：`mine_drop_range` **曾经**是这样一个"故意红"的临时模块（`D-345`：缺陷取证，`expectedVerdict=FAIL`、不进电池）；修好（`D-346`）当天按双向绊线**搬进 `MiningModule`（EXTRA）+ CURATION 登记**（步名 `mine_far_drop`），临时模块与注册表项已删除 ⇒ 现在它就是一个**正常回归步** | 教训：临时红模块**只该活到修复那一刻**；`expectedVerdict` 的声明与本文的那一行都必须在同一次提交里改掉，否则下一个人会把它当成"已知坏掉的测试" | `single:mine_far_drop` / `module:mining` |

⚠️ 纪律：**"故意红"的模块必须显式声明 `expectedVerdict()`，并在本文 + `AI_DECISIONS.md` 写明翻面条件** ——
否则下一个人会把它当成"电池漏登记"或"已知坏掉但没人管的测试"。

### 3.2 ⭐ CORE 结果缓存（2026-09-20，`D-352`）—— 只对**默认那一轮 core** 生效

`core` 真跑 ≈ 4–5 min，而按 §3.5 它只在**收口点**跑 ⇒ 收口那一下很贵。现改为：
**源码指纹一致 + 上次判决 PASS ⇒ 秒级复用判决**（在 `tools/headless-battery.sh` 内；缓存文件 `run/.cache/core-verdict.txt`）。

| 问题 | 答案（**代码为准**，别当散文记） |
|---|---|
| 什么情况会命中？ | **只有** `MODE=core` + `prod` + 未开 `--keep-world`/`--reuse-world`/`--no-build` + **无** `ALICE_EXTRA_JVM_ARGS` + 未开 `ALICE_KEEP_ALICE_DATA` + **指纹与上次 PASS 逐字一致** |
| 指纹算哪些输入？ | `src/` 全量 · `tools/` 全量（夹具/数据包/本脚本）· 构建脚本 · **`run/world-pristine` 世界母本**（5 个 CORE 步的 `START_FOOT` 依赖它的地形）· **上游模组 jar**（排除 alice 自己）· `server.properties`（**剔注释行** + `difficulty` 归一）· `unix_args.txt` · `java -version` ·（`--no-build` 时）工件 sha |
| 会不会静默复用？ | **不会**：命中时大声打 `缓存复用（指纹=… 上次真跑 …）` 并注明"这不是新证据"；`--no-cache` / `ALICE_BATTERY_NO_CACHE=1` 强制真跑；缺失/坏文件/指纹不符/**上次非 PASS** ⇒ **一律真跑** |
| 只缓存好结果吗？ | **是**，只写 `verdict=PASS`（红/降级/无判决永不写入 ⇒ 不存在"把红记成绿"）；写入用 tmp+mv ⇒ 不留半截文件 |
| 踩过的坑（第一版） | 把 `server.properties` **整份**入指纹 ⇒ 它第一条注释是 `Properties.store()` 写的**保存时间戳**，每次 boot 都变 ⇒ **缓存永不命中**（实测：真跑 262 s 后紧接着复跑仍在真跑，且逐分量对指纹后只此一项在变）⇒ 修法 = 剔掉注释行 |

## 4. 历史

| 日期 | CORE | FULL | 说明 |
|---|---|---|---|
| 2026-09-20 | **51** | **74** | ⭐ **`D-349` 勘测侧三处隐藏坑（Pit 1/2/3）**：Pit 1 = §7 标题校正（四条是**刹车**不是成功来源）+ 新 §7.5「能成功的全部来源」+ ⭐**已落地** `JobKindContract` 声明表（每 kind：判据 / **读世界事实的方法** / 不一致时怎么办）+ 门禁 `check-job-kind-contracts.sh`（**核对 `queryRef` 指向的方法真的存在**；三种注入全红）+ `JobLauncher.create` 受理闸。Pit 2 = ⭐**已落地** `RegionLumberJob.maintainUnreachable()`（无树无苗无欠 ⇒ 如实登记 + 上报「可做什么」+ 事件环，**不擅自收工**；恢复自清）+ 新步 `region_maintain_unmaintainable`（EXTRA，`LumberModule` 6 步；反向对照红 5 条）。Pit 3 = **纯声明** `ESCALATION_CAP=3 ⇒ END_QUEUE + 待机 + 如实登记`（无调用者 ⇒ 不写死代码）。 |
| 2026-09-20 | **51** | **73** | ⭐ **`D-348` 掉落物登记被推迟（`survey/22` 尾巴 + `D-347` 弧的现场）**：`ScopeBuffer.flushPending` 原来"tick 末判一次就永久丢弃"，而 `EntityJoinLevelEvent` 是在 `PersistentEntitySectionManager` **把实体登记进查找表之前**发出的、登记可推迟 **1~19 tick** ⇒ 会丢掉**真的会进世界**的掉落物（收集器看不到 ⇒ `MineJob` 如实 `product_not_collected`）。修 = ① **40 tick 宽限窗口**内每 tick 复验；② ⭐**归属在入队那一刻解析并随窗口携带**（只做①会让实体救回但归属丢 ⇒ 照样捡不起来 —— 夹具逼出来的半截）。另：路径 A（连锁挖掘交给 Ore Excavation，它取消原版掉落并缓冲）**丢弃正确、无损失**（每次 CORE 稳定 17 条，同轮真产物 `provenance=OURS_DIRECT` 被正常捕捉）。新步 `scope_pending_grace`（**EXTRA**，由 `PickupModule` 提供：3 步）—— 绿 `checks=8 failures=0`（推迟 13 tick）/ 反向对照（窗口=0）**红 2 条**。**CORE 51/51** · `check-all` 17 PASS/0 FAIL。 |
| 2026-09-20 | **51** | **72** | ⭐ **`D-347` 运行账（`survey/22 §5.2③` 的可测量判据）**：新增 `bot/TaskMetrics`（累计：启动/完成/状态桶/耗时/到达/返程/世界改动/被拒）+ 接线四处（`BotSession.beginTask`、`BotSession.recordTerminal`、`WriteBudget.consumeBreak/Place/ContainerWrite`、任务自报到达）+ 只读出口（`alice:bot_report` 一行）+ 两道**会话侧门禁**（`CheckHarness.verdict` / `HeadlessBattery` 判决前：账必须真的动过）。新步 `mine_run_metrics`（**EXTRA**，由 `MiningModule` 提供：9 步）⇒ **24 条判据** 全绿：每次**到达增量恰好 1**、账与 `WriteBudget` 独立读数逐位一致（3/4/4，含走位清障）、耗时 > 0、反向对照 `no_reachable_candidate` 且到达/改动增量都是 0 ⇒ 到达率 **3/4**、平均 tick **98**、世界改动 **11**。⚠️ 本夹具**只判运行账、不判收集闭环**（收集另有 `mine_job`/`mine_far_drop`/`mine_inventory` 判）⇒ 每次运行的 `quota_met` 结果进 SUMMARY 的 `quota_met次数` **事实字段**（实测 single=2/3、module=3/3 ⇒ 间歇性，附注一见 `D-347`）。⭐ 首跑实测推翻了我自己的假设"世界改动数 = 配额"（详见 `D-347`）。 |
| 2026-09-20 | **51** | **71** | ⭐ **`D-346` 收集追取上限改为由作用域派生**（`survey/22 §1.5①` 的修复）：`CollectDropsTask.MAX_CHASE_DISTANCE=32` 写死 ⇒ 小于 `MineJob` 自己的作用域直径（`2×SCAN_RADIUS(24)=48`）⇒ 自己挖的产物被自己**永久**退休 ⇒ `product_not_collected`（`D-345` 取证：`retire reason=too_far` + `collected=1/2`）。修 = `ScopeBuffer.currentRadius()`（只读）+ `chaseLimit()=max(32, 2×半径)`。新步 `mine_far_drop`（**EXTRA**，由 `MiningModule` 提供：8 步）—— **A/B 同一夹具**：修复前 `clusters=1/collected=1/地上剩 1/FAIL` → 修复后 `clusters=2（远件 3240 也被捡）/collected=2/地上 0/PASS`。`single:mine_far_drop` PASS · `module:mining` PASS(8 步) · **CORE 51/51**（271 s，`extra_skipped=20`，步序未变）· `check-all` 16 PASS/0 FAIL（`D-074` 裁定 2 的 N 由实现定 ⇒ 本次把 N 从 32 改成作用域直径，口径不变） |
| 2026-09-13 | 23 | 33 | 建立分档：BASELINE 13 / MAIN 10（3-A 工作站+熔炉）/ EXTRA 10 |
| 2026-09-13 | 24 | 34 | 新增 A4b 菜单型炉子（`craft_cooking`）⇒ MAIN 11 |
| 2026-09-13 | 25 | 35 | 新增 A5 决策层接线（`craft_goal`）⇒ MAIN 12 |
| 2026-09-13 | **17** | 35 | **3-A 收口整理**（D-201）：MAIN 12 → **4**（只留「只此一步覆盖」的机制：查询／决策端到端／方块炉／菜单炉），8 项退 EXTRA |
| 2026-09-13 | **25** | 35 | **回退瘦身**（D-201 附注一）：8 项重回 MAIN —— CORE 17 的三项红可复现、FULL 里同三项全绿 ⇒ 撤走的是隐含前置；瘦身前提（显式自证前提）未落地前不减项 |
| 2026-09-14 | 26 | 36 | 阶段 3-B / S1：新增 `machine_route`（`alice:machine_probe` 临时入口按 S5 **回收**，任务转为电池步）⇒ MAIN 13 |
| 2026-09-14 | 27 | 37 | 阶段 3-B / S2：新增 `machine_station`（临时入口 `alice:machine_station_probe` 按 S5 **回收**，任务转电池步）⇒ MAIN 14 |
| 2026-09-14 | 28 | 38 | D-207 ①：新增 `write_policy`（**BASELINE**，写入集中策略表 + 越权负例）⇒ BASELINE 14 |
| 2026-09-14 | **29** | **39** | 阶段 3-B / S4（D-213）：新增 `machine_cycle`（**MAIN**，单机最小闭环 = 3-B 第一次容器写入）⇒ MAIN 15。准入前提见 D-197：**等场景电源自证为 `cube` 之后才升**（2026-09-14 客户端实测 `energy_source=cube` + `verdict=PASS` ⇒ 前提满足）。临时入口 `alice:machine_cycle_check` 待电池步转绿后按 S5 回收 |
| 2026-09-14 | **30** | **40** | 阶段 3-B / **(c) 增量 2**（D-217）：新增 `craft_machine`（**MAIN**，机器路线的**生产**入口 —— `CraftJob` 真的驱动一台机器；与 `machine_cycle` 共用 `task/craft/MachineCycle`）⇒ MAIN 16。夹具入口与生产入口**各留一步**：前者验"场景 + 闭环"，后者验"生产接线"；两条都写容器（requester 分别是 `machine-cycle` / `craft`） |
| 2026-09-15 | **30** | **40** | **项数不变**（D-220，改的是"通道本身可不可信"，不是覆盖）：① `pathing` 步内 `+wall`/`+disturb` 的夹具时机改为**场景局部基准**（原来比的 `ticks` 是任务级 ⇒ `30` 从未生效）；② 场景判据加「**声明了夹具就必须真的动手**」（`FIXTURE_NOT_FIRED`）+ 删扰动静默降级；③ 新增场景 `dip_course+run`（复用 `dip_course_terrain`）把 `DIAGONAL` 变成**确定性**覆盖（此前靠偶发绕行）；④ 无头服务端设 `difficulty=peaceful`（清敌对生物噪声 ⇒ 修掉 `exit=3` 无判决） |
| 2026-09-15 | **31** | 41 | M1（G1）：新增 `mine_menu`（MAIN）—— 挖矿候选菜单契约（`mine` 不许猜位置；矿石场景复用 `MineCandidateSource`）。**刻意不进 EXTRA**：它是"不猜语义"红线的门禁，必须每次改动都跑得到 |
| 2026-09-15 | **32** | 42 | M2（G2）：新增 `no_progress`（MAIN）—— 长作业周期复评（无进度 ⇒ 报一次 `NO_PROGRESS`；夹具自造停滞与重新武装，**收尾把窗口复位回 0**）|
| 2026-09-15 | **33** | 42 | M4（G3）：`llm_contract` **EXTRA → MAIN** —— 新增 `snapshot_failure_fields` 判据（终态失败事实必须是**字段**：phase + 有界 details；无失败不许留 stale）。理由同 M1/M2：门禁必须默认跑得到 |
| 2026-09-15 | **34** | 43 | M3（G3 归因）：新增 `mine_no_tool`（MAIN）—— 与 `mine_job` 同场景同 Job，**唯一差别是不发镐**；判据挂在 `doneWhen`（终态理由成为 `tool_missing` 即 PASS，Job 本身 FAILED 是预期的）|
| 2026-09-15 | **35** | **44** | S-5（D-226）：新增 `survival_exit`（**BASELINE**）—— 维生决策自检。维生此前是**唯一零电池步的子系统**；本步顺带实测抓到并修掉一条真缺陷（`startSurvivalExit` 拿 `blockPosition()` 当"排除自己" ⇒ 站在半砖上时**把自己那格当出口**、逃生 0 步完成而 bot 原地不动）。⚠️ 只覆盖"决策 + 出口可达 + 事实登记"；**"真被否决"那半只能真人验**（真否决会 `complete` 掉会话任务=电池本身） |
| 2026-09-15 | **35** | **44** | D-228（同日追加）：`survival_exit` 判据 38 → **45**（火焰共享标志/着火递减/最低血量/掉血事件/入水空气消耗 + 2 条 `fill` 生效断言）；步数不变（仍挂既有 BASELINE 步），CORE `(35/35) ticks=3701 → PASS`。修因：假人缺 `baseTick`。 |
| 2026-09-16 | **38** | **46** | **D-243**：`survival_exit` 判据数不变（112），但 `FLOODED_SHAFT` 由"tripwire 断言失败"翻成**正断言**（水里逃生 `DONE`）；内核 `PILLAR`/`ASCEND` 水里上浮，CORE 仍 **38/38** |
| 2026-09-16 | **38** | **46** | **D-242**：`survival_exit` 判据 99 → **112**（新相位 `FLOODED_SHAFT`：灌水竖坑 + 额度三连 + 已知缺口 tripwire）；步数不变 |
| 2026-09-16 | **38** | **46** | **D-241**：`survival_exit` 判据 87 → **99**（新相位 `SHAFT_ESCAPE`：2 格深竖坑 + 逃生准备金）；`write_policy` 新增"纯通行名单不漂移"门禁；步数不变 |
| 2026-09-15 | **38** | **46** | **D-238**：`survival_exit` 判据 78 → **87**（新相位 `UNREACHABLE_REFUGE`：出口可规划预检）；步数不变 |
| 2026-09-15 | **38** | **46** | **D-237**：`survival_exit` 判据 65 → **78**（新相位 `OPEN_WATER`：溺水 `FLOAT_UP` + 端到端驱动 `SurvivalFloatTask`）；步数不变 |
| 2026-09-15 | **38** | **46** | **D-236**：`survival_exit` 判据 55 → **65**（新增 `DEEP_WATER` 相位：封闭水牢 + 溺水 `ABANDON_NO_EXIT`）；步数不变 |
| 2026-09-15 | **38** | **46** | **③/策展**：`decision_contract` EXTRA → MAIN（承诺与档位矛盾；200 tick 纯逻辑），步数不变 |
| 2026-09-15 | **37** | **46** | **M4b / D-231 + M3b / D-232**（同日追加）：新增 `mine_stale` / `mine_budget`（MAIN，各 400 tick 预算）⇒ 归因映射 `stale_target` / `write_budget_exhausted` **首次被观测**；M4b 不新增步（判据挂在既有 `mine_no_tool`）。CORE `(37/37) → PASS`。反向对照：映射短路 ⇒ 两步各判红 |
| 2026-09-15 | **35** | **44** | D-229（同日追加）：`survival_exit` 判据 45 → **55**（冻结决策表 + 细雪机理/分类/HOLD_NO_EXIT/全冻掉血）；步数不变，CORE `(35/35) ticks=3830 → PASS`。修因：细雪冻结伤害在补 `baseTick` 后变成真实危险，但没有危险档。 |
| 2026-09-16 | **38** | **41** | **项数不变**（D-247 水位切片 A）：`pathing` 步新增场景 `water_course`（执行：1 格深水沟横跨全场 ⇒ 绕不过去，纯通行 COMPLETED + 零写入）与 `water_course+cost`（只规划：水里步子必须按 `WATER_TRAVERSE_MULTIPLIER=7.25` 计价，期望值从计划自身推导；反向对照把常量改 1.0 ⇒ 恰好这一条红）|
| 2026-09-17 | **39** | **47** | **队列第③项**：新增 `mine_failure_visible`（**MAIN**，场景 `ore_course`）—— `MineJob` 新尝试上场时必须**保留上一轮失败事实**（夹具自持并手动 tick 一个 `MineJob`，用 `identityCheck` 覆盖器"前 2 个候选判为 replaced"构造「活动尝试+已有失败」；⚠️ 结束态采样判别不了 ⇒ 会假绿）。反向对照（failure 传空串）⇒ FAIL ✓ |
| 2026-09-17 | **40** | **48** | **S-9**：新增 `damage_event_visible`（**MAIN**）—— 伤害改用**事件**观测（`DamageLedger` ← `LivingDamageEvent`）：精确计数 4 次/4.0 点，而采样路径净变化仅 1.0。⚠️ 点火造伤害**不可行**：会触发维生中断（`survival_on_fire`）⇒ 任务被中断、电池无判决（exit=3）。反向对照（关掉观测）⇒ FAIL ✓ |
| 2026-09-17 | **41** | **49** | **S-6**：新增 `risk_profile_frozen`（**MAIN**）—— 风险开关按 bot **冻结**（冻结点=`assignTask`；命令改开关后 `freezeAll` 保 A/B）。9 条 check：冻结后全局变更不影响画像、重新冻结才跟进、freezeAll 立刻生效、收尾复位。反向对照（不冻结）⇒ FAIL ✓ |
| 2026-09-17 | **42** | **50** | **F4 地基（D-267）**：新增 `speech_channel`（**MAIN**）—— 说话通道只出不进（说话不改决策态；判别性判据 = 门禁 F4-P1 双向源码断言 + 夹具①；夹具②三条实测恒真 ⇒ 已标注为「非判据」）。注入（say 变空操作 / 说话接进决策）⇒ 均可红 ✓ |
| 2026-09-17 | **43** | **51** | **死亡机制第 1 步（D-276）**：新增 `death_persistence`（**MAIN**）—— 死亡不删数据（倒下态 FALLEN 含位置/死因/时刻；旧行为是 `clearBot()` 清存档）。9 checks；反向对照（改回旧逻辑）⇒ FAIL ✓ |
| 2026-09-17 | 43 | 51 | **S-9 消费（D-277）**：`damage_event_visible` 由 5 条 check ✅ 扩到 **9 条** —— 决策快照必须带 `damage` 节点且与台账一致（窗口口径 200 tick）。门禁 **S9-P1**（快照须读 `DamageLedger` 且带 `damage`）；注入 ⇒ 门禁与夹具**都红** ✓ |

### 2026-09-17　　按步跑电池：**步名写错不再白跑**（两层）

- **背景（实测踩到）**：把步名写成 `permission_contract`（真名是 `permission_gate`；⚠️ 本文档**故意不复述错误的 `single:` 写法**，否则本门禁会拦自己）⇒ 服务端起跑、白跑 200 tick、最后只有 `battery_never_ran` ⇒ 看起来像电池坏了。
- **① 运行时快速失败**：`HeadlessBattery` 起跑前用 `RegressionBatteryTask.knownStepNames()`（= 归属表 `CURATION`，构造期自校验与步骤表一一对应）判名字；未知 ⇒ `未知步名 single:X（已知 N 步；相近候选：[…]）` + `verdict=unknown_step exit=6`，脚本会明说「步名不存在」。实测 17 s 结束、候选精准命中 `permission_gate`。
- **② 静态门禁 SH-P1**（`tools/check-step-names.sh`，挂在 `check-all.sh`）：扫描 docs / skills / tools / AGENTS 里的 `single:<步名>` 引用（当前 **105 处**）与电池步表比对 ⇒ 打错字**在门禁阶段就红**，连服务端都不用起。反向对照（文档塞假步名）⇒ 红 ✓。
| 2026-09-17 | 43 | **52** | **D-276 端到端**：新增 `death_kill_bot`（**EXTRA**，只适合 `single:` 单跑：它另开探针 bot 并弄死它，会覆盖 `botTag`）。配套两轮脚本 `tools/death-persistence-e2e.sh`（saveOnHalt + `--keep-world` ⇒ 查 `world/data/alice_bot.dat` ⇒ `--reuse-world` 重启读回倒下态）；反向对照可红 ✓ |
| 2026-09-17 | 43 | **52** | **FULL 全跑 52/52 PASS**（含本轮新增 `death_kill_bot`/`death_persistence`/`speech_channel`/`risk_profile_frozen`/`damage_event_visible`/`mine_failure_visible`）。⚠️ 同轮实测：**`single:machine_station` 单独跑 FAIL**（`menu_open_failed:mekanism:enriching:menu_not_settled`）而 **FULL 里 PASS** ⇒ 再次印证「EXTRA 步单跑缺 FULL 隐含前置」（与 `pickup_gate` 同类）⇒ **判这类步不要用 `single:`**。
| 2026-09-17 | **46** | **55** | **V-4 数据无头化（D-280）**：新增 `fall_execute` / `pillar_execute`（**MAIN**，诊断加 `exec_ticks`）+ `contrast_timer`（**MAIN**，11 条 check：打字延迟不计时 / 只有 `sw_goal` 停表；两组反向对照均可红）。⚠️ 该夹具抓出 `sw_tick` 缺 `at @s` 的 mcfunction 陷阱（`as` 不改位置 ⇒ 条件恒真）|
| 2026-09-17 | **46** | **55** | **CORE 46/46 PASS**（含 `contrast_timer`/`fall_execute`/`pillar_execute`）。⚠️ 首轮红：PILLAR 诊断垫的方块留在 `WorldModLedger` + 发的圆石留在背包 ⇒ 后续 craft 步的 `no_world_write`/`crafted_furnace` 双双红 ⇒ 修法 `CleanupWrappedTask`（诊断收尾：拆我方临时方块 + 复位库存），见 **D-281** |
| 2026-09-17 | **45** | **55** | **CORE 修剪**：`contrast_timer` 由 MAIN 移入 **EXTRA**（V-4 已裁定不阻塞；工具检查不必占 CORE）。另记一条**偶发假红**：`survival_exit` 1 次 FAIL（DANGER 命中 0；前后两轮 PASS）⇒ 只登记不追查（台账有登记）。|
| 2026-09-17 | **46** | **56** | **F1 归因（D-285）**：新增 `driver_label`（MAIN，断言电池归因=fixture；注入 SYSTEM 可红）+ 73 处指派点标归因 + 门禁 **F1-P1**（删一处归因即红）。⚠️ 顺带修 `BotSession.tick` 重入空指针（曾致看门狗关服）|
| 2026-09-17 | **47** | **57** | **D-291 + R-2 Phase 1a**：新增 `container_access_profile`（MAIN，画像硬门判据，3 checks PASS）；账本 4 步改由 `task/check/modules/LedgerModule` 提供（**行为等价**，判据=CORE 47/47 ✓）；`stopWhenFull` 删除 + 门禁 J5-P1 扩展；S6-P1 泛化为任何 `RiskSwitches.<getter>()` 直读即违规；修 `mine_failure_visible` 编码旧默认值（0→200）的前提 |
| 2026-09-17 | **48** | **58** | **D-292 判据闭环**：`hazard_aversion_plan`（MAIN，plan-only；厌恶开⇒贴危险格 3→0、代价 20→26.66 改走绕行）+ 场景 `hazard_route_course_terrain`（一格外宽 + 嵌熔岩 + 唯一绕行；**场景由夹具自装**=先热区块再 fill，不依赖前序模块 ✓）；反向对照（去掉 `MovementContext` 加价）红 ✓ |
| 2026-09-17 | — | — | **R-2 Phase 1b 首片（D-293/294/295）**：`CheckHarness`（编排器脱离会话任务，服务器 tick 驱动，每步按普通任务起）+ `module:<id>` 单跑入口 + `tools/module-selftest.sh`（逐模块单独跑、按**声明期望判决**断言）+ `harness_self` 自检模块（两次外部命令打断⇒编排器仍跑完并出判决，旧电池此时是 `no_verdict` ✗）。CORE 48/48 无回归 ✓|
| 2026-09-17 | **48** | **58** | **R-2 v1 + 首个带场景模块（D-296）**：编排器备场相位（停输入→**provision→scenes**（顺序修正：先热区块再 fill ✓）→ 前提自证/关残留菜单 → 每 tick 复检落地，超时 200 tick 如实失败 ✓）；`pathing` 模块（fall/pillar/contrast 三步，内联定义已从电池删除，**行为等价**：CORE 48/48 ✓）；`module:pathing` 单跑 3/3 PASS；`module-selftest` 3/3 ✓；⚠️ 门禁 `check-fixture-hygiene` 抓出我新写的 `NoopCheckTask` **无失败路径**（R1/R2）⇒ 已改为要求布尔前提 ✓ |
| 2026-09-17 | **48** | **58** | **R-2 第二个模块 `decision`（D-297）**：搬走 5 步（container_access_profile / driver_label / risk_profile_frozen / damage_event_visible / mine_failure_visible），内联定义已删、相对顺序不变 ⇒ **行为等价**（CORE 48/48 ✓）；`module:decision` 单跑 5/5 PASS（41 秒）；`module-selftest` **4/4** ✓。⭐ 迁移口径确认：**验收单位是模块**（模块内允许步间依赖 ✓）|
| 2026-09-17 | **48** | **58** | **R-2 第三个模块 `craft`（D-298，12 步最大的一个）**：craft_check/craft_action/craft_table/craft_station/craft_probe_*/craft_station_provision/craft_station_craft/craft_furnace/craft_cooking/craft_goal 内联定义已删、顺序不变 ⇒ **行为等价**（CORE：run1 47/48、run2（同工件）**48/48** ✓）；`module:craft` 单跑 **12/12**（修前 11/12）；`module-selftest` **5/5** ✓。⭐ 首次单跑抓到**编排器步边界缺陷**：`CraftStation` 选择跨步泄漏 ⇒ `craft_goal` 被改道成升级页签路线（`code=upgrade_item_absent … station=upgradetab`）⇒ 补 `CheckHarness.endStepHygiene()`（与电池 `endStep` 同口径）+ 新门禁 **R2-P1**（两侧都要有，删任一侧即红）；顺带修死规则 **NP-P1**（结果被 `prog` 静默覆盖，注入后仍绿）|
| 2026-09-17 | **48** | **58** | **R-2 第四个模块 `machine`（D-299，4 步）**：machine_route/machine_station/machine_cycle/craft_machine 内联定义已删、顺序不变；`module:machine` 单跑 **4/4**、CORE **48/48** ✓、`module-selftest` **6/6** ✓。⭐ 本片必须先修掉入口里那条已登记的坑（`single:machine_station` 必红）：根因是**三个夹具都在 `teleportTo` 那一 tick 读 `onGround`**（读到的是上一处的陈旧值）⇒ **一个错误读法两种相反假判决**：`machine_station` 假绿（⇒ `menu_not_settled`）、`machine_cycle`/`craft_machine` 假红（只在模块化后暴露，旧电池靠「上一步恰好留成站姿」蒙对）。修法 `FixturePremise.settledOnGround` + 门禁 **R4**（反向对照可红）|
| 2026-09-17 | **48** | **58** | **R-2 框架补齐（D-300）**：编排器补 `doneWhen`（常驻任务按达成判过并停任务）与 `skipWhen`（终态判、不看 DONE/FAILED —— 照抄电池 T0-a 堵假绿的教训）+ **三态判决** `PASS`/`DEGRADED`/`FAIL`（有 SKIP ⇒ DEGRADED ⇒ 无头退出码 **2**，与电池同约定）。动因：`CheckStep` 的这两个字段电池有语义、编排器**一个都没读** ⇒ 迁 `lumber`/`mining` 时常驻任务会被误报成超时、缺模组的步会从 SKIP 变 FAIL。反向对照：恒真 skipWhen/doneWhen 注入 ⇒ `SKIP` + `PASS ticks=1` + `skipped=1 → DEGRADED` + 退出码 2 ✓|
| 2026-09-17 | **48** | **58** | **R-2 第五个模块 `mining`（D-301，7 步）**：mine_regression/no_progress/mine_menu/mine_job/mine_no_tool/mine_stale/mine_budget 内联定义已删、顺序不变；`module:mining` 单跑 **7/7**、CORE **48/48** ✓、`module-selftest` **7/7** ✓。⭐ 本片又抓出编排器两个语义缺口（都是「电池有、编排器没有」，都只在**单跑**里暴露）：① **步作用域必须早于 `provision` 开**（电池序 = openScope→场景→provision→起任务；否则 `mine_budget` 把预算压到孤儿作用域 ⇒ 静默失效 ⇒ 超时红）⇒ 新增 `beginSelfCheckTaskInOpenScope`；② **`doneWhen` 必须在终态也判一次**（电池判在「看终态之前」，编排器只在 isBusy 分支判 ⇒ 7~13 tick 就终态的归因步被误判成 FAIL，实测 4/7）⇒ 修后 7/7 ✓|
| 2026-09-17 | **48** | **58** | **R-2 第六个模块 `lumber`（D-303，3 步）**：lumber_failure(EXTRA)/lumber_job(BASELINE)/region_maintain(EXTRA) 内联定义已删、顺序不变；`module:lumber` 单跑 **3/3**（首次即绿）、CORE **48/48** ✓、`module-selftest` **8/8** ✓。⭐ 正面验证：`region_maintain` 是常驻区域作业 ⇒ 靠 `doneWhen` 判过（D-301 补的终态判据在第二个模块上再次被验证：`PASS ticks=163（doneWhen 判据成立 ⇒ 按达成判过；task=RegionLumberJob）`）|
| 2026-09-18 | **48** | **58** | **R-2 第七个模块 `transfer`（D-308，1 步）**：`transfer`（BASELINE）内联定义已删、顺序不变（仍在 `partial_search` 之前）；`module:transfer` 单跑 **1/1**（**首次即绿**，5 个子判决全 PASS：`fixture`/`end_to_end`/`selection`/`selector_events`/`command_parse`）、CORE **48/48** ✓、`module-selftest --changed` = **2/2**（选中 transfer+survival；模块注册表现共 **10** 个）✓。⭐ 本片唯一的新增 = **模块自带"先传送"的前提**：夹具**自己会调场景函数**，但顺序是"**先跑函数、再传送**"⇒ 冷区块上那一发 `/fill` 会静默不落地（D-296 同一个坑）；编排器顺序是 **provision → scenes** ⇒ 把传送放进 `provision` 就**顺带把区块热了**，且**不重复跑场景函数**（刻意不声明 `scenes`，跑两遍即行为不等价 ✗）|
| 2026-09-18 | **48** | **58** | **R-2 第八个模块 `survival`（D-308，1 步）**：`survival_exit`（BASELINE）内联定义已删、顺序不变（仍是 CORE 最后一步 48/48）；`module:survival` 单跑 **1/1**（`checks=124 failures=0` · 461/900 tick · `skipped=0`）、CORE **48/48** ✓、`module-selftest --changed` = **2/2** ✓。⚠️ 场景分工照抄原注释并**写进模块 javadoc**：**有出口**的平台 = 数据包场景 `alice_test:survival_course`（模块在 `scenes` 里声明，由编排器执行）；**封闭场景（无出口）** = 夹具自己在 bot 到位后建造（**不能**进 `scenes`，否则会在传送之前建）|
| 2026-09-18 | **48** | **58** | **R-2 第九个模块 `death`（D-309，2 步）**：`death_persistence`(MAIN) + `death_kill_bot`(EXTRA) 内联定义已删；`module:death` 单跑 **2/2**（`DeathPersistence checks=9` + `DeathE2E checks=6`，均 `failures=0`）、CORE **48/48** ✓、`module-selftest` **3/3**（本批 death+transfer+survival）✓。⭐⭐ **本片的新形状：模块落点必须选"保住 CORE 步序"的那一侧** —— 原电池里 `death_persistence`（MAIN，CORE 跑）在第 **8** 位、`death_kill_bot`（EXTRA，CORE 不跑）在第 **4** 位，**中间隔着 `hazard_aversion_plan` + 整个 `decision` 模块 + `pathing` 模块** ⇒ 一个模块只能落一个位置，"两边都原位"做不到 ⇒ 取代价最小侧：**模块插在 `death_persistence` 原位**（CORE 步序**逐字不变** ✓），只把 `death_kill_bot` 挪过去（EXTRA ⇒ CORE 不跑，且其注释写明"只适合 `single:` 单独跑"）。⚠️ 反过来（"先杀后验"插在第 4 位）会让 `death_persistence` 在 CORE 里**提前 3 个模块** = 未证明的顺序变更（D-302：顺序也是行为，且 CORE 会**蒙对**）|
| 2026-09-18 | **48** | **58** | **R-2 第十个模块 `tools`（D-310，1 步）**：`tool_supply`(BASELINE) 内联定义已删、顺序不变；`module:tools` 单跑 **1/1**（三例真判据：`tool_swap terminal=promoted_from_main` / `worn_no_spare` / `no_tool`，10 tick）；CORE **48/48** ✓。⚠️ 本片**无场景、无 provision**（`ToolSupplyCheckTask` **只动背包**，用 `FixtureToolKit` 摆 A/B/C 三种背包状态 ⇒ 不依赖地形、不写世界、不做传送）⇒ 也**不受"区块冷热"影响**，是"模块自足"的最简形态 ✓|
| 2026-09-18 | **48** | **58** | **R-2 第十一批～第十六片（D-311，六个模块 / 13 步）**：剩余内联步按「**保住 CORE 次序的连续段**」分成六片 —— `gates`(partial_search + capability_gate) · `write`(recoverability + write_policy) · `llm`(llm_contract + permission_gate) · `contracts`(speech_channel + decision_contract + decision_trace) · `pickup`(pickup_gate + collect_job) · `telemetry`(recipes_dump + event_thresholds)。**六片全部原序落位（0 位移）** ✓ ⇒ CORE 步序应逐字不变（与上一轮 CORE 做 diff 作证）。`module:<id>` 逐片单跑全 PASS；CORE **48/48**；模块注册表 **18** 个。⭐ **本批的真实收益：6 个 EXTRA 步第一次有了"单独跑"通道**（`decision_trace`/`permission_gate`/`pickup_gate`/`collect_job`/`recipes_dump`/`event_thresholds` —— 它们此前**只有 FULL 才跑**，进模块后 `module:<id>` 会跑它们）。⚠️ 仍留在电池内联的只剩 2 步：`hazard_aversion_plan`（语义属 `decision`，但并入会让它在 CORE 挪位）· `pathing`（聚合 5000 tick，并入 `PathingModule` 会把它从 CORE 47 挪到 7）|
| 2026-09-18 | **50** | **60** | **设计线 · 假人归属（D-319）**：新能力 —— **创建者登记**（`/alice spawn` 记执行者；`/alice bots` 列表可见；`/alice adopt` 给未登记的补，且**单向**：有主就拒且不改写）。新模块 `OwnershipModule`（注册表 **20** 个）+ 新步 `bot_ownership`（**MAIN**，规则 1）。`module:ownership` 单跑 **1/1**（`checks=20 failures=0`，约 5 tick）；CORE **50/50** ✓（**追加在步表末尾** ⇒ 与上一轮 49 步做步序 diff = **0 处位移**，实测 `49a50`）。三个注入各自单独跑 ⇒ 各自如期红（拆单调性守卫 / 拆 `saveToWorld` 接线 / 让 `read` 静默补主）。⚠️ 本片抓到的坑：**CURATION 与 `prepareSteps()` 的实跑列表是两个出处** —— 只登记 CURATION 会被自校验抓成 `phantom=[bot_ownership]`（实测 CORE FAIL）⇒ 新步必须**两处同时登记** |
| 2026-09-18 | **51** | **62** | **D-323 破坏被拒（真机发现：我们谎报成功）**：`BlockBreakSession` 在 `gameMode.destroyBlock(...)` 之后**不验证世界事实**就宣布 DONE（返回值也丢了）⇒ FTB 认领/别的保护模组拦下的破坏被记成 `block_break_done` + `MineTask COMPLETED`（用户实测：退队后 4 格仍是 `minecraft:dirt`，日志却全绿）。修 = 破坏后比**方块对象身份**，没变 ⇒ `fail("REFUSED")`（上游成 `BREAK_REFUSED`）+ 既有 `[WRITE-REFUSED] … reason=world_unchanged` 口径；批量破坏路径同一条判据。新步 `break_refused`（**MAIN**，进 CORE：所有挖掘/清障/伐木的底座，且只有"有保护的地方"才显形）：**对照真成功**（生存挖掉自己放的泥土 ⇒ 变空气）· **冒险模式被拒**（不依赖模组）· **FTB 认领被拒**（`/ftbchunks claim` 由另一队假人认领；FTB 不在场 ⇒ `ftb=skip`）· 自清理。**先红后绿**：修前 `verdict=FAIL`（两个用例都 `status=DONE` 而方块还在）→ 修后 `PASS（checks=14 failures=0，冒险=REFUSED FTB=REFUSED）` |
| 2026-09-18 | **50** | **61** | **D-320 双假人转发爆栈（真实客户端崩溃的回归门）**：`FakeConnection` 把「发给这只假人的旋转包」再广播给**追踪者**，而追踪者里**含别的假人** ⇒ 两只挨着的假人 A⇄B 互相转发 ⇒ `StackOverflowError: Sending packet`（客户端实测 84,892 帧；`5d63cdf` 把 `broadcastToRealPlayers` 换成 `broadcastToTracking` 引入）。修 = 加**转发中闸**（`ThreadLocal RELAYING`，投递集合不变）。新步 `bot_pair_no_recurse`（**EXTRA**：会写世界存档、失败形态是服务端崩 ⇒ 不进 CORE）：同格放第二只假人 + 连转 40 tick（每 tick 注入旋转包），判**转发有界 / 闸生效 / 不放大**。**旧码实测 `verdict=<无> exit=3`（服务端死）· 修后 `PASS`**（`checks=9`，`relay=235 suppressed=235`）⇒ **先红后绿**。⚠️ 标定教训：我第一版把上界按「每 tick 1 次」写成 48 ⇒ 修后**假红**（实际 ≈6 次/tick，两只假人各自的 `ServerEntity` 也推包）|
| 2026-09-18 | **49** | **59** | **设计线 · 保护区（D-313）**：新能力（不是 R-2 搬迁片）—— `SafeZoneData` 形状从「水平圆形半径」改为 ⭐ **区块级 2D 认领**（忽略 Y、覆盖全高度；D-305 ①′）+ **旧格式自动迁移**（相交即认领，计数进 `summary()`，不静默丢）。新模块 `ProtectionModule`（注册表 **19** 个）+ 新步 `protection_zones`（**MAIN**，按本档规则 1「新能力默认进 MAIN」）。`module:protection` 单跑 **1/1**（`checks=32 failures=0`，约 10 tick）✓；CORE **49/49** ✓（**追加在末尾** ⇒ 与上一轮 CORE 做步序 diff 应为 0 处位移）。本行同时**校正了本节计数**（46/38 → **59/49**，按 CURATION 逐条数）|
| 2026-09-19 | — | — | **`D-338` 附注一（安全区子集 + 内部区块 + 命令入口）**：`protection_zones` 判据 **67 → 95**（+28）—— 不变量（未认领 ⇒ 拒声明且计数不变）· 四邻腐蚀（3×3 ⇒ 恰好中心 1 个）· **退化**（2×2 / 单区块 ⇒ 空集 + 纯函数不改入参）· Y 无关 · 子集语义 · 幂等 · **取消保护区连带清安全区** · `safe_chunks` 存/读往返（含派生视图一致）· **孤儿丢弃**（计数 + summary 可见）· **命令入口三例**（`/alice protect safe claim\|unclaim`：未认领 ⇒ 返回 0 不落库 / 认领后 ⇒ 返回 1 落库 / `unclaim` 保保护区）。**步数/档位不变**（仍 67 项、CORE 51）。**先红后绿**：拆 `declareSafe` 的子集检查 ⇒ `verdict=FAIL failures=4`（恰好是依赖该不变量的四条，**含命令入口拒绝分支**）→ 复原 `checks=95 failures=0`。⚠️ 夹具两条纪律：专用**孤立区块区**（chunk 2000,2000；纯集合运算 ⇒ 不读方块、不加载区块）+ 维度级断言**先裁到该区**、计数按**增量**断言（⇒ 真实存档里也能跑）。收口 `ALICE_HEADLESS=1 check-all` = **17 PASS / 0 FAIL**（CORE **51/51**，266s）|
| 2026-09-19 | — | — | **`D-338` 附注三（返程判据切优先级链）**：`safe_return`（**EXTRA**）判据 **15 → 30**、用例 **3 → 5**（`NO_ZONE` / ⭐`SAFE_RUN`（5×5 保护区 + 3×3 安全区，200 格外 ⇒ 必须走到**安全区内部区块**）/ ⭐`PROT_RUN`（取消安全区 ⇒ 降到**保护区内部区块**）/ `SINGLE_RUN`（1 区块 ⇒ **退化"进区即到"**）/ `SEALED`）+ **判决矩阵**三条（含"在保护区里但不在安全区里、而世界有安全区 ⇒ 仍然兜底"）。**步数/档位不变**（仍 67 项、CORE 51）；`ProtectionModule` 步预算 2200 → **3600**。**先红后绿**：旧实现 + 新判据 ⇒ `failures=4`（⭐优先级链判决 / 安全区内部 / 保护区内部 / 同源 `isSafe`），判别性事实 `safe DONE@2960（保护区最外圈）inSafeInternal=false`；修后 `safe DONE@2992（chunk 187,250 = 安全区内部）3 段 734 tick` · `prot DONE@2976（保护区内部带西端）2 段 292 tick` · `single 退化` · `sealed return_unreachable 1 tick 不动` ⇒ `checks=30 failures=0`。`module:protection` **2/2**、`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 FAIL**（CORE **51/51**，261s）|
| 2026-09-19 | — | — | **`D-338` 附注五（归位点 = 返程链最前项）**：`safe_return`（**EXTRA**）判据 **30 → 41**、用例 **5 → 6**（新增 ⭐`HOME_SET` + ⭐`HOME_RUN`：命令 `/alice bot-home set` ⇒ 归位点 = **执行者站位**；判决"有归位点 ⇒ true（**即使有安全区**）" / "已在半径内 ⇒ false" / "**别的维度 ⇒ 本维度忽略**"；⭐**归位点优先**：必须走到**半径内**（`dHome ≤ 3`）且**没有**跑回区里（`dZone > 30`）；命令 `clear` 后判决回到区几何）。**步数/档位不变**（仍 67 项、CORE 51）。**反向对照先红后绿**：把 `homeOf` 强制返回 `null` ⇒ `failures=3`（恰好归位点三条），判别性事实 `home DONE@2992,4015 dHome=74.0 dZone=17.0`；复原后 `home DONE@2933,4064 dHome=3.0 dZone=93.0`（2 段 220 tick）⇒ `checks=41 failures=0`。`module:protection` **2/2**、`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 FAIL**（CORE **51/51**，260s）|
| 2026-09-19 | — | — | **`§5.12` 第 4 件的「几何 + 锁定」层（`D-338` 附注六）**：新步 `task_zone`（**EXTRA**，`ProtectionModule` 步数 2 → 3）**82 判据 / 10 组**（几何/锁定 6 组 + ⭐**权限阶梯 4 组**，见下条） —— 几何（工作区域 ⇒ **区块最小覆盖**：与**逐方块枚举**这条独立路径做等式断言 + 最小性 + 跨区块边界 + **Y 无关** + 两角规范化 + 纯函数）· 声明/幂等/换区/⛔`NO_SCOPE` · ⭐**生命周期**（作用域收尾 ⇒ 权威**立即消失**；**先重开作用域再问 authority** ⇒ 不许"因为过期条目被 prune 掉了"而侥幸通过）· ⭐**覆盖保护区父类**（同一格上 保护区=true 与 任务区覆盖=true **同时成立**）· ⛔**冲突**（与安全区有交集 ⇒ `CONFLICT_SUBZONE` + **不裁剪**（不留"小一号"的区）+ **不静默降级**（安全区/保护区计数一字未动）+ 工作区域**一字未动** + 判据**只看向交集**）· ⭐**显式退化**（取消那个声明 ⇒ 同一工作区域就能声明）· ⭐**生产接线**（真跑 `RegionLumberJob`：首 tick 解算任务区（`chunks=1`）+ 终态自动解除 + 压到安全区上时 `task_zone_conflict` 如实失败且报出冲突区块）。⚠️ **本片不改任何权限行为**（判据里有"`protectionReason=protected_area` 照旧拒绝"这一条）。**反向对照两次（都先红后绿）**：① 拆掉冲突检查（`if (false && …)`）⇒ `failures=8`，**恰好**是冲突那一组（夹具 4 + 生产 Job 4），几何/生命周期/覆盖那几组**全绿**；② `zoneOf` 退回"按 owner 找"（不看作用域）⇒ `failures=2`，**恰好**是两条生命周期判据。绿：`checks=50 failures=0`（33 tick）。判别性事实 `geometry blocks=451 chunks=3[2200,2200\|2201,2200\|2202,2200]` · `job_ok FAILED reason=goal_timeout zone=DECLARED chunks=1 ticks=5` · `job_conflict FAILED reason=task_zone_conflict failure=task_zone_conflict[safe_zone 1 chunks: 2201,2200]`。专用孤立区（块 35200..35240 跨 3 区块 / 35360..35363 一区块 / 区外点 2215,2215），**临时认领按增量还原**（保护区/安全区/生效任务区三条计数都回到进入前）|
| 2026-09-19 | — | — | **`§5.12` 第 4 件下半段 · ⭐权限阶梯接进闸门（`D-338` 附注七/八）**：`task_zone` 判据 **50 → 76**（+26，第 10 组 = **区域级授权面**）。**用户拍板**：`L0 只读` / `L1 临时脚手架(≤8 放置)` / `L2 工作面(目标内 KEEP + 目标外逐块提权)` / `L3 全权(仅玩家显式)`；**等级单一出处 = `WritePolicyMatrix`**（`Task→Level`；`L3` 无玩家驱动身份 ⇒ 降级 `L2`）。⭐ **"一个判据多处消费"**（原文写"三处"⇒ **作废**：2026-09-19 客户端实测补第④处 = `PathSession`→`CapabilityGate`，见 `D-338` 附注十） = 新 `protection/ZoneAuthority`，被 ①候选扫描（`MineCandidateSource` / `LumberCandidateSource`）②破坏闸门（`BlockBreakSafety`）③**放置闸门（`BlockInteraction.placeAt` —— 今天完全缺失，本片补上）** 共用。判据组：无任务区 ⇒ **逐字回归 `protected_area`**（含"保护区内放置被拦"这条**变严**）· `L0` 破坏/放置全拒 + **野外在 `L0` 期间照旧可写**（≤8 是**区内**配额，不是作用域级上限）· `L1` 只许临时放置、**8 次**后 `zone_place_quota`、破坏 `zone_break_not_allowed` · `L2` 目标内（`EXPECTED_TARGET`）/目标外（`PATH_ACCESS`）破坏 + 临时放置**都放行且真的写/真的拆**（世界事实断言）· 越界 `protected_area` / 安全区 `protected_safe_zone` / 别的 owner `protected_area` · ⭐**候选扫描**（手搭 3 格原木小树在被认领区块里：无区 ⇒ `:protected_area`；`L2` ⇒ 不再以保护区为由拒；`L0` ⇒ `:zone_read_only`）· 等级解析 6 条（含 `L3` 降级）。**反向对照三次（都先红后绿）**：① 拆掉候选扫描那处消费（回退成裸 `protectionReason`）⇒ `failures=2`（**恰好**两条候选判据）；② 拆掉"破坏等级闸"（恒放行）⇒ `failures=3`（`L0`/`L1`/候选 `L0` 三条**拒绝**判据）；③ 反过来"破坏恒拒"⇒ `failures=3`（**恰好** `L2` 三条**放行**判据）。绿：`checks=76 failures=0`（34 tick）；`module:protection` **3/3**；`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 WARN / 0 FAIL**（CORE **51/51**）|
| 2026-09-19 | — | — | **客户端第二轮修漏（`D-338` 附注九/十）**：`task_zone` 判据 **76 → 82**。① **日志卫生**（客户端实测：`[ZoneAuthority] ALLOW` 50 ms 刷 30 行，规划期谓词同格同理由反复问）⇒ 留痕按 **(格, 理由)** 去重 + 上限 `AUDIT_CAP=512`（到顶只报一次饱和）⇒ 判据"同一格+同一理由问 **100 次只留痕 1 条**"。② ⭐**补上第四处消费**：`PathSession` → `CapabilityGate.Facts` 此前走**裸 `SafeZoneData`** ⇒ 保护区里 `PILLAR`（"垫一格上去"）被拒 **144 次**（`ZONE_PROTECTED_AREA`）、全轮 `places=0` ⇒ 云杉最高一格跳过（离线同场景无认领时 `7/7` + `places=9` + `[Ledger] place 28,64,208 cobblestone` = A/B 锁根因）⇒ 新增 `ZoneAuthority.silentRefusal/movementRefusal`，`CapabilityGate.Facts.protectionReason(pos, **placing**)` 带动作语义；判据 5 条（`L2` 放行放置类/破坏类移动 · `L1` 只放行"垫脚" · **无任务区逐字回归 `protected_area`** · `protected_block` 不参与区域授权）。绿：`checks=82 failures=0`；`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 WARN / 0 FAIL**（CORE **51/51**）。⚠️ **仍缺离线覆盖**：真正的 `PathSession` 会话级复验（要客户端复看 `places ≥ 1`）|

### 夹具前提必须**自带**，不许依赖前一步的残留（2026-09-20 实测）

`scope_pending_grace` 在 `full` 里红了两次、`single:` 单跑却一直是绿的。根因不是产品：
`FixtureToolKit.ensurePickaxe` 只把镐放进**快捷栏空格（slot=0）**、**不选中**它，而该夹具的前提读
`getMainHandItem()`（= **选中槽**）；工艺/机器模块的夹具会合法地把选中槽挪到别的槽
（`MachineCycle:445` / `StationProvision:161` / `CraftFurnaceCheckTask:162`）⇒ 整链里主手 = `air`、
"前提未复现"红。**修法**：夹具在 `ensurePickaxe` 之后显式 `selected = 0` + `syncMainHand`。

⚠️ 排查口径（省下一轮）：**"`full` 红、`single:` 绿" ⇒ 先怀疑状态残留，不要先怀疑产品**；
`grep -rn "\.selected = "` 能直接指出谁会动选中槽。同类风险：会话/作用域/背包/选中槽/维度/游戏模式
这五样只要被某一步改过，**任何**跨模块的前置判据都可能被它污染。

### harness 判决读取会**提前放弃**（2026-09-20 实测两次；结果以**服务端日志**为准）

现象：`tools/headless-battery.sh core` 打印 `verdict=<无> exit=3 用时=9s`，而**同一个服务端**继续跑到
结束并写出 `[Headless] RESULT verdict=PASS exit=0 ticks=4768`（`PROFILE=CORE … passed=51/51 → PASS`）。
⇒ 这是**读数竞态**（harness 的判定窗口先到了），不是产品红。**取证口径**：
① 看 `/home/fb486/alice-server/logs/latest.log` 的 `[Headless] RESULT` 行（这是服务端自己写的，唯一权威）；
② `run/headless-logs/<时间戳>-*.log` 是 harness 侧归档，**可能只截到前 9 秒**（本次 24 KB、无 RESULT 行）——
   不要拿它当"跑完了"的证据。
⚠️ 与"真红"的区别：真红会有 `SUMMARY … =FAIL` 与 `[Headless] RESULT verdict=FAIL`；只有 harness 侧 `<无>`
   而服务端 PASS ⇒ 读数问题。**先看服务端日志，再下结论**。
