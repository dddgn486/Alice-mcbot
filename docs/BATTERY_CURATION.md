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

## 2. 当前归属表（67 项 → CORE 51 项）

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


### BASELINE（15）
`pathing`（移动内核聚合 + 覆盖断言）、`write_budget`（写入预算/授权闸门）、`mine_regression`（挖掘闭环 11 用例）、
`mine_job`、`lumber_job`（两个生产 Job 的最小闭环）、`transfer`（L2 容器传输——破坏性最强）、
`clear_guard` / `clear_retry` / `scaffold`（三条破坏性路径的守卫与建拆同权）、`partial_search`（`SEARCH_LIMIT ≠ UNREACHABLE`）、
`capability_gate`（闸门）、`tool_supply`（不凭空变工具）、`recoverability`（可回收性等级）、
`write_policy`（D-207 ①：写入集中策略表——表完整性 + **越权必须被拒**的负例 + 未登记 requester 留痕=0）
`survival_exit`（S-5 / D-226：维生决策表（软危险 = 溺水/着火 需"宽限 + 有出口"才否决、无出口**不否决**）、
封闭场景四层前提自证、**真实着火** ⇒ 不否决 + `exit=none decision=continue`、脚位格口径回归（半砖）、
出口真能走到、掉血 ⇒ `DANGER`（`delta=`）且不刷屏）

### MAIN（35）—— 阶段 3-A（回退瘦身，保持完整）+ 阶段 3-B / S1+S2（机器只读）+ S4 + (c) 增量 2（机器写入：夹具 + 生产）
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

### EXTRA（15）
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

## 4. 历史

| 日期 | CORE | FULL | 说明 |
|---|---|---|---|
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
