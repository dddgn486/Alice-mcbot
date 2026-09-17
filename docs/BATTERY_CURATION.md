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

## 2. 当前归属表（46 项 → CORE 38 项）

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

### MAIN（23）—— 阶段 3-A（回退瘦身，保持完整）+ 阶段 3-B / S1+S2（机器只读）+ S4 + (c) 增量 2（机器写入：夹具 + 生产）
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

> **为什么退回来了**：2026-09-13 实测——把其中 8 项移出 CORE 后，`craft_furnace`/`craft_cooking`/`transfer`
> **可复现地变红**（重启客户端后仍红），而它们在 FULL（35 项）里**全绿** ⇒ 撤走的是它们的**隐含前置/清场**。
> **瘦身的前提**是"每个夹具自己显式自证前提、顺序无关"；该前提未落地前不允许再减 CORE 项。
> 恢复路径：先做"显式自证前提"（菜单身份/位置/方块实体状态/账本与归因时间窗）⇒ 再**逐条**撤，**每条复跑一次**。

### EXTRA（9）
`lumber_failure`、`region_maintain`（区域常驻 Job，耗时）、`decision_contract`、`decision_trace`、
`permission_gate`、`pickup_gate`、`collect_job`、`recipes_dump`、`event_thresholds`

## 3. 维护规则（我 = AI 负责执行）

1. **新增场景/新夹具** ⇒ 同时在 `CURATION` 与本文登记；新能力默认进 **MAIN**（还没验收前）。
2. **一条能力验收通过、且不再是主线** ⇒ 从 MAIN **移到 EXTRA**（保留可跑，不占默认时长）。
3. **任何"坏了就不能信任 bot"的新守卫**（新的破坏性路径、新的写入维度） ⇒ 进 **BASELINE**。
4. **换主线**（例如从"工作站"转到"熔炉/决策层接线"） ⇒ 重写 MAIN 一节，并把退场项移入 EXTRA。
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
