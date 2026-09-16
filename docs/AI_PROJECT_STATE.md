# Alice 当前项目状态

> 这是新 AI 会话恢复上下文的首要文件。只记录当前，不记录完整历史。

更新时间：2026-09-12

> **编号体系速查（2026-09-12）**：本项目有四套编号，互不相同 ——
> ① **寻路内核里程碑 `R1–R5`**（`R1 契约` → `R2-A/B/C/D Movements` → `R3 PathSession+Battery` →
> `R4 Session 执行` → `R5 世界修改 Movement`；日志前缀 `[R2-B Traverse]`/`[R3 Battery]`/`[R4 Session]`/`R5-2`）；
> ② **L3 目标层切片 `J1–J8`**（`JOB_LAYER_DESIGN.md`）；
> ③ **缺口/契约项 `G1–G9`**（如 `G4 写入预算`）；
> ④ **架构决策 `D-001–`**（`AI_DECISIONS.md`，单调递增）。
> 2026-09-11 盘点时我曾临时用 `R1–R7` 记"风险项"，与①**撞车**，现已改称 **`T1–T7`**（见 D-124 末段）。

> **更早的收口历史（2026-09-09 ~ 09-10：批次 5 模组兼容 / 仓库整理 / L3 立项 J1）**：**不在本文件复述**——
> 原始证据在 `docs/AI_DECISIONS.md`（D-075/D-076/D-077/D-078/D-079/D-080/D-081）与 `git log`。
> **为什么删掉**：本文件第一行自己写着"只记录当前，不记录完整历史"，而这段 41 行是**完整历史** ——
> 它在 2026-09-14 的文档盘点里被点名为"STATE 违反自己的规则"（`docs/reviews/2026-09-14-项目完成度与优先级审查.md` §4.1）。
> 指针口径：**只放路径/commit/决策号，不抄原文**（AGENTS.md「规则准入尺子」第 3 条）。


## 最新（2026-09-15）—— 先读这里
**⚠️ 本节及以下大多是**历史存档**（最新到 2026-09-15 白天）。"现在在哪"请看下面的「当前目标 / 当前进度」（已按 D-226…D-232 + M 线重写）与 `docs/HANDOVER.md` §1。**


**T3 已收口**（步骤 1 / B3a / A / A2 / C / (A) / B4 均 `SERVER_TESTED`）；第八步"接第 3 个模组本身"（`MachineMap` 19 行 = Create 15 + EC 4）+ **B3b** 按 **D-219** 推迟（买不到功能，按需再加）；读数 `docs/reviews/2026-09-14-T3-B3a-读取器vanilla优先与探针确定性.md`、台账 §9。
**2026-09-15（D-220）：验证通道"声明必须为真"** —— 夹具时机参数从未生效（`wallTick/disturbTick=30` 实际是"第一个执行 tick"）、`DIAGONAL` 覆盖靠偶发绕行凑、**无头通道被敌对生物杀 bot**（`exit=3` 无判决，bot 先被推离预期格）三处已修；`core` ×3 = PASS 3/3、怪物命中 0、场景行逐字相同。全文 `docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`。

**阶段 3-A（合成/熔炼接进任务层）已收口**（A1–A5 客户端验证 + `USER_ACCEPTED`）。
**当前主线 = 阶段 3-B 模组机器适配**（实验对象 Mekanism，方法见 `docs/MOD_ADAPTER_PROTOCOL.md`）：
**S0/S1/S2 已完成**（类型事实表 / 机器配方只读 `MACHINE_ROUTE` / 机器站点只读）；
**S3 已收口**（`SERVER_TESTED` + `WINDOWS_CLIENT`，2026-09-14 第二轮电池）：`decision/MachineMap.java` =
"机器类型 ↔ 机器方块/菜单"的**唯一真源** + 生成视图 `docs/MACHINE_MAP.csv` + 双向防漂移
`tools/check-machine-map.sh`；`Route.station` 改为机器方块 id；探针「按表认机器」并断言
"方块实体自述配方类型 == 表里的类型"（实测 `按表找到 2 台` + `m1_binding=true m2_binding=true`，
CORE `(28/28) → PASS`）。**实测纠正两条口径**（D-209）：零配方的 `mekanism:smelting` 解释
"表 27 行 vs 实测 26 类型" ⇒ 探针改为对表行完整划分 + 守恒自检；`menuClass` 实测**不区分机器**
（两台共用 `MekanismTileContainer`）⇒ 身份判据只有 `m{i}_binding`。
**S3 客户端实测复验通过**（2026-09-14 第四轮电池，`latest.log:3068`/`:3081`/`:3096`/`:3811`）：
`with_site_confirmed=22 with_site_unobserved=[mekanism:smelting] no_site=4 row_block_missing=0`（守恒 22+1+4+0=27）、
`m2_menu_class_matches=true`、`m{i}_slot_roles` 首次观察、`(28/28) ticks=2845 → PASS`；
`/alice authz` 的 L2 行**首次在客户端敲过**（`:3836`）⇒ 该待验证项关闭。
**S4 已跑通**（**3-B 的第一次写入**，`WINDOWS_CLIENT`，第五轮 `latest.log:3811`）：零参数物品
`alice:machine_cycle_check` → `MachineCycleCheckTask`（放料 → 等 → 取产物）：
`binding=true feed_verified=true in_machine=1 active_seen=true progress_ticks=199 product_after=1
product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true verdict=PASS`。
**不新造授权**：容器写入维度 `WriteBudget` + `WriteReason.CONTAINER_TRANSFER` + requester `machine-cycle`；
放料 shift-click（菜单自己决定落点）、成不成**只看世界事实**。v1 单机单配方 + 夹具传送（内核寻路 = v2）。
**电源前提已自证（D-213，2026-09-14 第七轮客户端实测）**：场景加一行
`data merge block … {EnergyContainers:[{Container:0,stored:"4000000000"}]}` 后重跑 ⇒
`energy_at_open=20000.0 energy_source=cube（场景电源，未补电） energy_ready=20000.0`（**不再是** `api_precharge` 的 4.0E6）、
`progress_ticks=199 product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true verdict=PASS`
（`latest.log:213`，场景 11 条命令见于 `:187`）⇒ **电来自场景本身、没走补电兜底**，D-213 的判据成立、`WINDOWS_CLIENT`。
真因（**D-213**）= **创造能量方块放下时自带电量就是 0 J、而且永远充不进电**（`BasicEnergyContainer:52` 初值 ZERO +
创造档 `insert` 强制 SIMULATE；`TileComponentEjector:166` 对空容器直接跳过），上游设计里"空变体"就是 power sink。
证据 = 存档里的对照组（同一次保存）：机器 `EnergyContainers=[{"Container":0,"stored":"3990000"}]`、方块 `EnergyContainers=[]`。
⚠️ **判据的准确含义**：`energy_source=cube` 实际证明的是"**场景把电送上了**"（开机时机器已有电），
不是"程序认出了 cube 方块"（读的是机器自己的能量容器）；场景里只有这一条供电路径 ⇒ 两者等价，注释已写清。
**S4 已按 D-197 升级为电池步**（第七轮升，准入前提 = 上面这条自证）：`machine_cycle`（MAIN，会写容器）⇒
电池 **CORE=29 / FULL=39**；`MachineCycleCheckTask` 类注释同步按 D-213 改写（台账 ⑥ 关闭）。
**✅ 电池步已转绿（第九轮，`WINDOWS_CLIENT`）**：新客户端会话（14:34:59 启动 ⇒ 新 jar 已加载，
日志里 `PROFILE=CORE 实跑 29 项（跳过 EXTRA 10 项）`）⇒ `[Regression] SUMMARY … machine_station=PASS
machine_cycle=PASS … (29/29) ticks=3108 → PASS`（`latest.log:3842`）；该步 `machine_cycle=PASS ticks=210
idempotent=true`（`:3130`），步内 `[MachineCycle] SUMMARY … energy_at_open=20000.0 energy_source=cube（场景电源，未补电）
… product_landed=true machine_emptied=true container_writes=2 reset=true verdict=PASS`（`:3129`）、
写预算 `containers=2/32 refusedContainers=0`（`:3131`）⇒ **电池内也走的是场景电源，不是补电兜底**。
**R1 收口（2026-09-14，D-211）已完成并经客户端验证**：`WritePolicyMatrix` **首次经手容器写入**
（挂点 `WriteBudget.consumeContainerWrite`；未登记 ⇒ 留痕不拒，**已登记但未声明 ⇒ 硬拒**，
拒绝权默认武装 + 一行回退开关 `setContainerRefusalArmed`）；`docs/authz/CONTAINER_WRITE_SITES.csv`
（20 个调用点）+ `tools/policy-map.py` 断言⑦（负例实测都红），并顶出/补上
**`CraftJob`（生产熔炼）从没记账** 的真缺口 + CRAFT 行补声明 `CONTAINER_TRANSFER`。
**第五轮证据**：`container_gate_live=PASS container_gate_armed=PASS containerGate=armed
container_checks=13 container_refused=0 verdict=PASS`（`latest.log:3206`）——**13 恰好等于各步
`containers=N/32` 之和**（2+2+3+4+2）⇒ 闸门覆盖面与预算覆盖面**逐点一致**；`container_refused=0`
⇒ 没有生产路径被硬停；D-211 的两条复核触发**都已解除**。
**✅ S5 收口已完成（D-215，2026-09-14）**：`alice:machine_cycle_check` 临时入口删除（物品类 + 注册 + 模型 + 两份 lang），
并清掉三支**已无调用点的 `assign*`**（`assignMachineProbe`/`assignMachineStationProbe` 是上轮回收后的死代码，
`assignMachineCycleCheck` 的唯一调用者是本次被删的物品）⇒ 源码零命中，`check-item-models` **76 项**（-1）；
台账⑦ 一并修掉（项数改 `coreStepCount()` 现算，不再写死"26 项"）；**"通用 vs 专属"对照表 = `docs/MOD_ADAPTER_PROTOCOL.md` §6**
（结论：执行侧/发现侧通用，专属只有 `MachineMap` 一张表 + 夹具 1 行目标 + 1 段能量反射）。
**⇒ 3-B（模组机器适配）S0→S5 全部走完**。新 jar `sha256=d5e49c1b4ec7e8f48b634c97f912f4513c5423ef4952871b569899d44fcfc5ef`（已同步客户端 `mods/`）。
**✅ 收口复核已通过（第十轮客户端，2026-09-14）**：启动文案打 **"29 项，约 2~4 分钟"**（台账⑦ 修复生效）、
`[Regression] SUMMARY … machine_cycle=PASS … (29/29) ticks=3083 → PASS`（`latest.log:3849`）；
**"探针零残留"由 Forge 自己证明**：进世界报 `Unidentified mapping from registry minecraft:item
alice:machine_cycle_check: 3405` + `missing registry entries`（⇒ 不在注册表里了）。
⚠️ 那种 missing-registry/stats 警告是**删注册物品的一次性自愈副作用**（`level.dat` 已归零、stats 键已消失），
**不是回归** —— 见 D-215 附注一，下次回收物品别误判。
**⚠️ 路线规划审查进行中（2026-09-14 用户提出「计划可能偏离主线」）**：开场与**可复算的事实盘点**在
**`docs/reviews/2026-09-14-路线规划审查-开场.md`**（最近 60 个 commit：只动 `pathing/` **1** 个、
只动机器适配 **19** 个、两者都不碰 **40** 个；`pathing/` 源码最后一次被改 = `d4e533a` 09-14 12:26）。
**⚠️ 用户已修正方向（该文 §0，优先）**：**寻路内核不是当前必须的主线**，真正的问题面是
**决策-执行框架（LLM 决策层 → 任务层 → 执行层 → 世界）仍有大量问题**；
审查**不是"内核 vs 适配"二选一**，先摆全问题面再定优先级。
⇒ 结论未定，**在用户拍板前不要自行开新线**，尤其**不要**往下推 Thermal 的 `EXECUTABLE`。

**📌 项目完成度与优先级审查 + T0/T1 已落地（2026-09-14）**：全文在
**`docs/reviews/2026-09-14-项目完成度与优先级审查.md`**（**先读 §3.1 红线表 + §4.1 优先级表**）。
口径（该文 §0，**优先**）：除寻路内核外多为**原创脚手架**⇒**不要过度自信**，"编译过/电池绿"只证明没崩、不证明对。
- **✅ T0-a 已收口**（`75c1d23`）：电池终局改**三态**（`PASS` 要求 `pass==expected`；有步因环境不具备被跳过 ⇒
  **`DEGRADED`**，不再冒充 PASS）+ SKIP 判据去掉 `status != DONE`；`machine-map` Tier B 缺 jar ⇒
  **INCOMPLETE(exit 2)**，不再打印 PASS；`authz-map` 改**递归 glob**（原先漏扫 21 文件）+ 去家族前缀逃生
  （改族集合+计数断言，实测一次抓出 16 个未登记码与过期计数）；`policy-map` 去 `"inv"` 子串豁免（改整词）。
- **✅ T0-b 已收口**（`6bb26b2`）：`tools/check-all.sh` 串 **9 道门禁**（三态：PASS/WARN/FAIL，WARN=断言**没执行**）
  \+ 接进 `.github/workflows/build.yml`（此前 **CI 一道门禁都不跑**）。
- **✅ T1 已收口并客户端验证（`8b66572` + 第十七轮 19:09–19:12，jar `abed83d2…`）**：R-1 连锁破坏计入
  `WriteBudget`（**前后对照 `mine_regression breaks` 5→13，差值 8 = 连锁自报 `mined=8`**）；R-2 生产入口
  `fixtureProvision` 显式化 + 新门禁；R-3 `isSelfCheck()` 唯一真源 + **`setSelfCheckHold` 随任务存续**
  （实测 `trigger_skipped … until=1287 hold=true` ⇒ 现场证明 1200 tick 窗口**已过期**、是 hold 挡住的，且
  **全会话 `decision_request` = 0**）；R-4 补种补触及前提；R-5 `FurnaceStation` 编译期强制 `WriteGrant`。
  `(passed=30/30 skipped=0) → PASS`。**R-2 运行期路径本轮未走到**（无 Job 被起）⇒ 待手动补一次。
- **未动**：T2（无头回归；已实测本机 `runServer` **`Done (3.675s)!`** 可起）、T3（模组 #3 数据模型）。
  ⚠️ **第 3 个模组已在客户端 `mods/` 里**（`create` / `ExtendedCrafting`）**且静默读不出** —— 推它之前必须先做 T3。
- **流程尺子已写进 `AGENTS.md`**（"规则准入三问 + 只看一个指标"，净增≈0：同期把 STATE 的 41 行历史压成指针）。

**当前弧（用户 2026-09-14 裁定：(c) 起步 + (a) 并行只读）—— 3-B 之后是"让 S4 真能生产用"**：
- **✅ (c) 第 1 步已完成并客户端验证（D-216 / S4 v2，第十一轮）**：闭环自检**自己走到机器旁** —— 起点挪到平台远角
  `CYCLE_START=(72,64,312)`（到机器 ≈8.49 格，远超交互距离）、新 `WALK` 相位
  （`TableCraft.standPointNear` + `PathRequest.of` **纯通行** + `PathRetryRunner`，到位用 `inReach` 断言）、
  删掉 v1"够不着直接判红"、扫描半径 6→12、新留痕 `machine_distance_at_locate`/`stand_point`/`walk_state`/
  `walk_ticks`/`foot_after_walk`/`machine_reach`（= 开菜单前的眼距）。**同一条电池步 `machine_cycle` 就是测试入口（零新入口）**。
  **第十一轮实测（`latest.log:3209`；jar `sha256=5dfd3c57…`）**：`machine_distance_at_locate=8.5`（定位时**确实够不着**，
  v1 那条隐式前提已被拆掉）→ 内核自己走完 **8 段、`[R4 Session] completed session=machine-walk-0 segments=8 ticks=40
  finalFoot=66, 64, 305`**（与断言格**逐字一致**）→ 开菜单那一刻 `machine_reach=1.5`
  ⇒ `walk_state=DONE walk_ticks=41`（**无 `walk_skipped`**，起点真的生效了）、`machine_cycle=PASS ticks=251`、
  `(29/29) ticks=3116 → PASS`（`:3924`）；`WriteBudget … scope=…#1564:Regression:machine_cycle breaks=0/64
  places=0/32 refusedBreaks=0 refusedPlaces=0` ⇒ 这段路**零世界写入**（"纯通行"红线在实测里成立，不是靠代码推断）。
  路径本身是 1×TRAVERSE + 6×DIAGONAL + 1×TRAVERSE 的干净斜线，**零重规划**（25 条会话日志 = 8 segment_start +
  8 segment_done + 7 continuous_advance + completed + Recover，无第二次 `plan`）。
- **✅ (c) 增量 2 已完成并客户端验证（D-217，第十二轮）**：机器路线**接进生产路径** —— 闭环本体抽成 `task/craft/MachineCycle`
  （夹具与生产**同一份实现**），夹具 `MachineCycleCheckTask` 变薄壳；`CraftJob.MACHINE_ROUTE` 现在真的驱动机器，
  准入**数据驱动**（`MachineMap` 新增 `executable(...)`，只把 `mekanism:enriching` 升为 `Capability.EXECUTABLE`，
  其余照旧 `not_executable`）。**红线①机械可查**：执行器里没有造能量的代码，唯一通道 `EnergyTopUp` 只有夹具实现、
  生产位置传 `null`；新门禁 `tools/check-precharge-containment.sh` 三条断言（含**反向测试**：注入一次 `precharge(` ⇒ 立刻红）。
  新增电池步 `craft_machine` ⇒ **CORE 29→30 / FULL 39→40**；六道离线门禁全 PASS（`check-policy-matrix` 还抓到写入点登记漂移，
  已同步 `docs/authz/CONTAINER_WRITE_SITES.csv`）。
  **第十二轮实测（`latest.log`）：`(30/30) ticks=3370 → PASS`（`:3899`）**，两个机器步同轮全绿：
  ① `craft_machine=PASS ticks=255` —— `target=minecraft:soul_soil`（**后备逻辑生效**：首选 `clay_ball` 的路线落在
  `mekanism:chemical_injection_chamber`，那台**没有执行准入** ⇒ 如实拒绝 ⇒ 换下一个），`route_station=mekanism:enrichment_chamber`、
  `job_terminal=DONE`、**`m_walk_state=DONE walk_ticks=41 machine_reach=1.5`**（与夹具**逐字同值**）、
  `m_energy_source=present（…）`（**不是 `api_precharge`**）、`product_after=1`、`WriteBudget … containers=2/32 refusedContainers=0`；
  ② `machine_cycle=PASS ticks=251`（与第十一轮**逐字相同** ⇒ 薄壳重构无回归）。
  本轮 `api_precharge` **全日志零命中** ⇒ 红线①在客户端也成立（没电就该如实红）。**用户目视确认** bot 从平台远角自己走过去。
- **(c) 未做**：机器路线的**多输入 / 化学品输入**（如实拒绝）；`CraftJob` 只驱动 `EXECUTABLE` 那一行。
  **✅ 查询层排序已收（D-218，台账⑬ 关闭，2026-09-14）**：`RecipeQuery` 现在**按执行准入优先**挑机器路线
  （**只排不删** + `recipeId` 收尾 ⇒ 结论确定，不随配方管理器迭代序漂；`note` 里写清挑了哪台/共几条有准入）。
  **第十三轮实测（`latest.log`）已复核通过**：`craft_machine=PASS ticks=255` + **`fallback_used=false` + `target=minecraft:clay_ball`**
  （`route_recipe=mekanism:enriching/clay_ball`、`input=minecraft:clay x1`、`job_reason=crafted:minecraft:clay_ball x4`、`product_after=4`），
  同轮 `(30/30) ticks=3414 → PASS`（`:3955`）。**"前/后"判决器干净**：同一入口、同一断言，仅排序改变
  ⇒ `true`+`soul_soil`（第十二轮）→ `false`+`clay_ball`（第十三轮）。
- **(a) 已完成第 1 份 S0 事实表**：`docs/THERMAL_FACTS.md`（Thermal：652 条 / 30 类型，占本次跳过量 27.5%；
  前 5 = press 227 / pulverizer 81 / smelter 70 / insolator 63 / centrifuge 59 = 500 条 76.7%）。
  **第十三轮已就地重导配方表**（`/alice recipes` ⇒ `recipes=3689 skipped=2379 tags=693`；`config/alice-recipes.json` 1.53 MB）
  ⇒ 台账⑩ 的"表已过时"缺口**关闭**；重导后 `skippedTypes` 逐条与 S0 数字对得上，**Thermal 仍居首**（`thermal:press=227`）。
  重导后新增的可验证事实：Thermal 的 30 个类型里**只有约一半是机器配方类型**（press / pulverizer / smelter / insolator /
  centrifuge / bottler / crucible / sawmill / crystallizer / chiller / refinery / pyrolyzer / rock_gen / tree_extractor / furnace …），
  另一半是**燃料 / 催化 / 增幅类修饰类型**（`*_fuel` / `*_catalyst` / `*_boost` / `*_recycle`）
  ⇒ **S1 枚举不得按"类型数"建行**（须按 jar 里的 TileEntity/Block 逐条核实后才落 `MachineMap`）。
  剩余前置缺口：**无上游 sources jar**（台账⑩）。
  **第十四轮已交付 S1 设备事实表** `docs/THERMAL_S1_FACTS.md`（**只读**，全部证据来自 jar 字节码 + 资源）：
  expansion 的 `blockstates` ∩ `loot_tables` **逐项一致 = 22 个方块**（15 `machine_*` + 7 `dynamo_*`）；
  30 个配方类型全分类 = **13 机器 + 5 机器修饰 + 7 发电机 + 5 无站点**（合计 652 条逐项对齐）；
  **设备清单（三个后端）**：`thermal_expansion` 22 个（15 `machine_*` + 7 `dynamo_*`）
  + **`thermal_foundation` 内嵌的 `META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar`（JiJ）里的 11 个 `device_*`** = **33 个机器类方块**。
  **⚠️ 方法学的坑（留档）**：内嵌 jar 在 `mods/*.jar` 扫描里只显示为**一行** ⇒ **本表第一版据此把 5 个 device 类型误判成
  "1.20.1 没有这些设备"，已纠正**（`device_rock_gen` 等那串字符串两种解释都自洽，只看外层 jar **判不出来**）。
  全客户端只有 `create`（4 个内嵌 jar，全是库、recipes=0）与 `thermal_foundation`（是内容）带 JiJ；**Mekanism 没有** ⇒ 既有 Mekanism 结论不受影响。
  **红线①**：7 台 dynamo 是**发电机**（7 个 `Dynamo*` 类 ↔ 7 个 `*_fuel` 类型 1:1）⇒ 永不进 `EXECUTABLE`。
  **台账⑩③ 的 `+34` 已完全闭合**：静态 **670** = 运行时 **652** + **18**
  （`smelter_recycle` 22 条**全部**带 `cofh_core:tag_exists` 条件；运行时只有 4 个条件标签存在 ⇒ 4 条存活，
  4 = `forge:armor/{gold,iron}` + `forge:tools/{gold,iron}`，用 Alice 自己的 `itemTags`（693 个）交叉验证吻合）。
  §5 给出 33 个 `Menu` 类名（**是候选，不是准入证据**：准入要实测 `menuClass`）。
  台账⑨（`recipe-readability.py --target`）同轮关闭（含自证断言）。
  **✅ S2 落地（离线，2026-09-14）：Thermal 已进 `MachineMap`（32 行，全 `READ_ONLY`）** ⇒ 表从"单一模组表"变成
  **多模组表**（每行带自己的取证件；新增 5 参 `row(...)` / 3 参 `noSite(...)` 重载，`src` 放最后以免动
  `tools/machine-map.py` 的位置解析）。**闸门同步升级**：Tier B 改为 `UPSTREAMS` **按命名空间分别双向断言** +
  **内嵌 jar 解包**进 `javap` classpath（没这一步，Thermal 的 11 个 device 与 12 个 device/fuel 类型会被判成"上游没有"），
  且"表里出现工具没登记取证方式的命名空间"**直接报红**。实测 **`行=59 未映射=10` PASS**，
  Mekanism 27（23+4）/ Thermal 32（26+6）**双向一致**；六道门禁全 PASS。
  **7 台发电机在表里但 `READ_ONLY`** ⇒ 红线① 靠能力列保证（不是"不登记"）。
  **✅ 客户端复核已通过（第十四轮，`latest.log:2908`/`:2989`）**：`row_block_missing=[]` + `unmapped=[]`
  ⇒ **32 个 Thermal 方块 id 全部在客户端注册表里存在**；同轮 `(30/30) ticks=3279 → PASS`，
  `machine_station` 仍精确找到 2 台、`craft_machine` 逐字未变（`fallback_used=false` + `mekanism:enriching/clay_ball`）
  ⇒ 加 32 行**零回归**。
  **同时纠正我先前写错的预期**：`with_site_confirmed` 实测是 **22**（我原写 48）—— 因为
  `MachineProbeTask.NAMESPACE` 只采样 `mekanism`，26 个 Thermal 站点行必然全落进 `with_site_unobserved`
  （`22+27+10+0=59` 守恒）⇒ **本步对 Thermal 只覆盖到"方块存在性"，没有覆盖"类型↔配方"**。
  该缺口**同轮登记台账⑭、第十五轮关闭**：探针的采样命名空间**改为按 `MachineMap` 表推导**
  （加模组不用再改探针 ⇒ `MachineProbeTask` 现在 **0 处 `mekanism` 代码字面量**）
  + 新增**逐命名空间一行**覆盖计数（`[MachineProbe] namespace=<ns> types=… type_recipes=… samples=…`），
  SUMMARY 的 `namespace=` 改为 `namespaces=[…]` 且保留全局合计。
  期望新基线：`namespaces=[mekanism, thermal] types=56 type_recipes=1823`、
  **`with_site_confirmed=46`**、`with_site_unobserved=[mekanism:smelting, thermal:brewer, thermal:hive_extractor]`
  （`46+3+10+0=59` ⇒ "未观测"回到"真·零配方"本义）**——✅ 第十五轮实测逐项命中**（`latest.log:3007`/`:3177`/`:3178`：
  `类型=56 条数=1823`、`with_site_confirmed=46`、`unobserved` 恰好 3 项、`row_block_missing=[]`、`(30/30) ticks=3353 → PASS`）
  ⇒ "Thermal 32 个类型里哪 30 个真有配方"**第一次有了自动化证据**。
  **同轮新发现（台账⑮）→ 第十六轮已修并复核**：根因是 Thermal 的访问器名字不同
  （`getInputItems/getOutputItems/getOutputItemChances` vs Mekanism 的 `getInput/getOutputDefinition`）
  ⇒ `MachineRecipeFacts` 扩名族并成为**唯一读取器**（探针的两份私有反射读取器删除）。
  **第十六轮实测**：`namespace=thermal upstream_readable` **0→26**、`input_readable` **0→34**
  （`refinery`/`crucible` 流体输出**仍读不出**=如实），`namespace=mekanism` 四个计数 **31/20/31/30 逐字未变**、
  `row_block_missing=[]`、`(30/30) ticks=3351 → PASS`。**判据一行未动**（`Verdict`/`Route`/能力列/准入）。
  **⚠️ 同轮自纠**：第一版把概率判据写成 `chance < 1.0`，而 `probabilistic_output=23/57` 与静态 JSON
  **算不出来**（期望 ~5）⇒ 那是在**猜语义**，已撤，改为只声明"上游给了概率信息"（字段 `chance_declared=`），
  **数值语义另立台账⑯**（静态实测 146/670 条声明 `chance`，取值 0.05~12.5 ⇒ **不在 [0,1]，不是概率**；
  **不阻塞**任何当前工作：Thermal 32 行仍全 `READ_ONLY`）。
  另附一条入册的教训：**"没被采样"与"不存在"必须能从日志上区分开**（一个字段混两种含义必被读错）。
  另：`query_machine_route` 1→2 **不是 Thermal 造成的**（被探测物品是随机采样、该计数只报告不断言）。
  附带闭合的一处对账：`[MachineProbe] readable_total=3714 skipped_total=2354` 与导出 `recipes=3689 skipped=2379` 差 25 ——
  导出侧 `skippedTypes` 里正有 `minecraft:crafting(空产出)=25`，两侧**算术精确闭合**（探测把"空产出合成"记为可读、导出记为跳过）
  ⇒ 不是异常，是两处口径不同。
**下一步（客户端无待验项）—— (a) Thermal 线现在的位置（2026-09-14 第十六轮更新）**：
S0 事实表 ✅ → S1 设备事实表 ✅ → **S2 进表 ✅（`MachineMap` 32 行全 `READ_ONLY`，第十四轮客户端复核
`row_block_missing=[]`）** → 工具与闸门 ✅（台账⑨ `--target`、⑭ 多命名空间探针、`machine-map.py` 按命名空间双向 + JiJ 取证、
⑮ 读取器扩 Thermal 名族）。
**尚未开始的下一格 = S3/S4 级别"某台 Thermal 机器真的开一次"**，前置三件：
① **台账⑯**（Thermal `chance` 语义未取证 —— 要让概率产出参与任何判定，必须先收它）；
② 一个 **Thermal 场景 + 夹具**（走 `alice-scene-based-testing`：孤立区域 + 一键函数 + 自带传送/复位）；
③ 客户端实测该机器的 `menuClass` / 槽位表。
三件齐了才谈得上把某一行从 `READ_ONLY` 升 `EXECUTABLE`（那一步同时就是 **(a) 线与 (c) 线的交汇点**）。
**仍然开着的账**：**⑯**（新，未修，不阻塞）、**⑫**（`[Recover] session=` 标签与载荷不符，观测缺陷）、
**⑩①**（没有上游 sources jar，不可控）。**⑬ / ⑨ / ⑭ / ⑮ 均已关闭并复核。**
**上下文窗口已由用户从 256K 改为 512K**（D-214，本会话生效；阈值 409,600 / 保留 81,920）——改的是"何时压缩"，
不改变事实来源；复核触发 = 手动 `/compact` 频率没降、或我出现"忘记已确认事实/重复问已答过的问题" ⇒ 退回 256K。
电池 **CORE=30 / FULL=40**（(c) 增量 2 加了 `craft_machine`）。**详细交接见 `docs/HANDOVER.md`**；**方向来源与审查留档见 `docs/reviews/2026-09-14-外部质疑与工作流审查留档.md`**；今天的新纪律见 PLAYBOOK §5.0b/§5.0c/§5.0d。

## 当前目标

**（2026-09-08 用户重申）完全参照 Baritone 搭建寻路内核**，差异仅限三条：① Bot 可回收性安全策略；② 多层任务失败向上传递（任务层 → LLM 决策层处理接口）；③ 未来 Bot 并行运行接口。最终交付**完整寻路系统**，供其他任务系统接入。详见 `docs/ALICE_PATHING_CORE_ARCHITECTURE.md` §1.1 与 §13。

当前进度（**2026-09-15 深夜快照**；更早的逐条进度见 `docs/AI_DECISIONS.md` 与 git 历史 —— 本文件不再抄写）：
- **内核 / 寻路**：R1–R5 全部客户端验收；D-036 内核对齐路线与 D-037…D-068 各批次均已验收，此后主线转向任务层与决策层。
- **阶段 3-A（合成 / 熔炼接进任务层）**：已收口（A1–A5 客户端验证 + `USER_ACCEPTED`）。
- **阶段 3-B（模组机器适配，实验对象 Mekanism）**：S0–S4 完成；S3 / S4 已 `WINDOWS_CLIENT`，`machine_cycle` 已成电池步。
- **M 线（旗舰挖掘的框架补齐）全部完成**：M1 候选菜单（LLM 不许猜位置）/ M2 可观测进度 `NO_PROGRESS` /
  M4 失败事实字段 + `llm_contract` 门禁 / M3 终态归因 + **M4b**（任务树带"哪个子阶段失败"，D-231）+
  **M3b**（`stale_target` / `write_budget_exhausted` 两条归因映射首次被观测，D-232）。
- **S-5 维生线（2026-09-15/16）**：D-226 决策表 / D-228 补 `baseTick` / D-229 冻结危险档 / D-236 溺水不再静默 /
  D-237 上浮自救"乙" / D-238 出口必须**可规划** / **D-241 逃生准备金**（轴=任务信封，放置+破坏+PILLAR，上限 8/8）/
  D-242 水里"规划得到、执行不了"的结论 / **D-243 水里垂直移动**（`PILLAR`/`ASCEND` 水里按住跳跃上浮）/ **D-244 水柱省料分支**（起点/目的地都是水 ⇒ 上浮**不放方块**，完成口径改"脚位到格即成功"；实测整段逃生零放置）/ **D-245 逃生放置不自动回收**（回收时机交玩家 `/alice restore`，负向门禁防逃生循环）/ **D-246「出口列表」判定基本为空 ⇒ 关闭**（纯通行档「最近不可达」⇔「没有出口」，证明在决策里）。 / **D-248 水位切片 B 勘查完成、规划那半回退**（水位例外在 CORE 引出未解释回归：逃生改走更便宜的"破墙+升到水面格"路线、最终段支撑被读成 `Air` ⇒ `SEGMENT_FUTURE_BLOCKED`；证据与三个候选解释在决策里） / **D-247 水位切片 A**（实测：蹚水本来就能走 ← 新场景 `water_course`；补 `WATER_TRAVERSE_MULTIPLIER=7.25`让水里步子按水速计价；判据期望值走独立第二来源，防自指）。
  D-226/228/229/236 已 `WINDOWS_CLIENT`。
  零参数入口：`alice:survival_exit_check`（右键窒息 / Shift+右键着火）、
  `alice:survival_full_check`（一次右键跑完全部 **120** 条判据 ⇒ 聊天里看 `SUMMARY checks=120 failures=0 → PASS`）。
- **决策/快照线（同日）**：D-233（`decision_contract` 提档 MAIN + 本文件过时段落重写）、D-234（四个 Job 统一
  "子阶段失败"口径）、D-235（挂起传输"结清落盘"：持久化成立 + 跨重启幂等）。
- **D-230 的自我修正**：我一度判断"bot 的血只减不增、必须补 `doTick()`"，实测**推翻**了它（`Player.aiStep()`
  本来就有自然回血）⇒ `doTick()` 默认**不启用**，开关 `alice.bot.vanillaTick` 保留为有记录的实验开关。
- **离线门槛**（"改一行到知道对不对"）：`tools/headless-battery.sh core` = **CORE 38 步**
  （当前 `(38/38) ticks=3955 → PASS`）；`bash tools/check-all.sh` = **9 PASS + 1 预期 WARN**（无头断言"未执行"）。
- **断点与待办**：`docs/HANDOVER.md`（§1 下一步 / jar hash）、`docs/OPEN_ITEMS_LEDGER.md`
  （**§5.11**：水里逃生四条挂账 —— 水面理由码 / 出口列表 / **B 维生写授权（待用户拍板）** / **丙 内核水位移动**；
  §5.6 风险等级层、§5.10 客户端可验项、其余 Job 的 `lastFailure`）。

项目：Minecraft Forge 1.20.1 / Forge 47.4.10 / Java 17  
开发目录：`/home/fb486/projects/alice`  
Windows 测试目录：`D:\JAVA_projects\alice\`  
固定客户端：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`

## 当前工作方式

讨论需求 → 读取相关 skills → 选择最小闭环 → 实施 → compile → 同步 Windows → 用户用游戏内测试物品/命令实测 → 讨论证据和根因 → 再决定是否修复。

不再把旧的 dsh-agent-bus、active-plan、严格监督员、HANDOVER 提交流程作为日常开发硬门槛。

## 稳定架构边界

- LLM 只做目标级决策；确定性执行器负责动作、安全和完成条件。
- 服务端是世界、bot、任务、权限和库存的真相。
- **寻路红线（D-076，取代 `HARD_PATH` 旧语句）**：寻路请求默认纯通行（`PathRequest.of`）；
  破坏/放置只能由上层任务**显式授权**并受**预算闸门**约束（挖掘站位 `miningApproach` + `MiningBudget`；
  收集 `allowWorldModification=true`）；禁止寻路器自行挖穿地形、禁止把 `SEARCH_LIMIT` 当授权、
  禁止实验性移动模式隐式接入正式任务。
- `SEARCH_LIMIT` 不等于 `UNREACHABLE`，不自动授权挖隧道。
- **Movement 落差红线（D-024）：Bot 不允许超过一格的落差**；Descend 过冲落点列必须与目标同层落脚，更深一律拒绝。
- **假人台阶高度 = 0.6，对齐真实玩家（D-025）**；一格方块必须跳跃才能上，`BotPlayer` 构造显式 `setMaxUpStep(0.6F)`，禁止改回 1.0。
- **Movement 合法位置集与统一完成契约（D-026）**：起点校验接受 `{fromFoot, toFoot}`；完成判定统一用 `MovementHelper.isSettledAtFootPos`（脚位 + 支撑 + onGround + 水平 ≤0.3）。
- 客户端行为必须由 Windows 真人测试确认；源码分析和服务端日志不能替代。

## 能力现状（2026-09-12 重写；旧版是 09-07 的 R2 阶段快照，已过时 ⇒ 见 git 历史）

### L0–L2 寻路内核（R1–R5 全部客户端验收）
- **9+1 种 Movement 原语**（`MovementType`）：`TRAVERSE / DIAGONAL / ASCEND / DESCEND / DOWNWARD /
  PILLAR / FALL / BREAK_AND_TRAVERSE / BREAK_AND_ENTER / PLACE_STEP_AND_TRAVERSE`
  （COLUMN 空中平台由 `PathSession` 上层处理，见 D-056）。
- **搜索与执行**：`AStarMovementSearch`（成本模型 + 启发式，D-040）→ `PathSession`（分段、完成容差、
  段计时/漂移检测、重规划 D-043、`no_progress` 快速失败 D-105、自愈 D-041）。
- **入口**：`alice:pathing_regression`（**18 场景 + 覆盖断言**，一次右键）、`alice:pathing_battery`、
  以及各 `alice:pathing_*` 单项诊断（traverse/ascend/descend/diagonal/pillar/fall/downward/placer/breaker…）。
- **红线纪律**：D-076（`PathRequest.of` 默认纯通行；破坏/放置必须显式授权 + 预算闸门）、
  D-024（落差 ≤1）、D-025（台阶 0.6）、`SEARCH_LIMIT ≠ UNREACHABLE`。

### L2 任务（已验收）
- `MineTask`（站位选优 + 清障 + 恢复阶段）/ `CollectDropsTask`（簇级收集 + 守恒交叉校验）/
  `PlaceTask` / `RestoreScopeTask`（恢复严格自上而下）/ `ScaffoldLifecycleTask`（搭-用-拆闭环）/
  `WalkToTask` / `FollowTask` / `TransferTask`。
- 一键回归：`alice:regression_battery`（**9 项，一次右键**；`mine_regression` 12 例、`lumber_failure_check` 6 例、
  `clear_retry_check`、`write_budget_check`、`scaffold_check`、`clear_guard_check`、`pathing`、还有 `lumber_job`/`region_maintain`）。

### L3 目标层 `Job`（J1–J8 **已收工**，伐木=首个高级任务）
- **契约七件**：`GoalSpec` / `Job` / `Candidate`+`CandidateSet` / `SelectionPolicy` /
  `DecisionTrace` / `GoalProgress` / `Selection`；**两个真实消费者**：伐木（`LumberJob`，J1–J4/J7/J8）、
  挖掘（`MineJob`，J5）。
- **J6 世界修改账本**：`WorldModLedger`（`TEMP`/`KEEP` 策略 + 作用域）+ 建拆同权 +
  `WriteBudget`（D-106 每作用域 64 breaks/32 places）+ 崩溃兜底 `pendingForOwner`（D-127）。
- **J7 攀爬砍树**：`ScaffoldLifecycleTask` + 逐树会话内拆除（D-107/D-109）。
- **J8 区域型 MAINTAIN**：`LumberRegionState`（持久化区域/我种的苗/待补种/baseline/统计）+
  `RegionLumberJob`（巡查→复用一次性 `LumberJob` 砍一棵→继续巡查）+ 常驻（只由显式打断）+
  垂直自适应 + 区域补种（`REGION_REPLANT` ⇒ 账本 `KEEP`）+ 玩家接口
  `/alice region info|start|stop|set|sapling|idle-stop`（D-129/D-130/D-131）。

### 决策缝现状（**距离"LLM 决策层"还差什么**）
- **已有**（**2026-09-15 按代码复核，旧版此处写"没有"是过期的**）：`GoalDirector`（四触发 + 三闸节流 + 单飞 + 看门狗）、`LlmClient`（真 HTTP）、`DecisionSnapshot` ↔ `BotStateReport`（同一份事实）、`CandidateMenu`（有界候选 + id 白名单）、`GoalAction`（严格解析、未知即 `Refused`）、`JobRequest`/`JobLauncher`/`BotManager.assignJob`（唯一执行入口）、`PermissionGate`（超时=拒绝 + `once|session|always`）、终态码贯通（`TaskExecutionRecord`/`TaskOutcome`）。
- **还差**（**2026-09-15 三路只读审计**，全文 `docs/reviews/2026-09-15-挖矿高级任务作为框架验收范例-完成度审计与规划.md`）：
  ① **挖矿没有候选菜单**（`CandidateMenu` 只产 lumber/collect/region/craftable）⇒ LLM 在猜位置；
  ② **长作业中段静音**（触发只有终态/维生/四事件，无周期复评；节流丢弃不补发）；
  ③ **父子额度零传递** ⇒ 失败归因在 Job 边界失真（缺镐/预算耗尽被聚合成"没矿"）；
  ④ **动作集只有起/停，无"转向"**，任务树不持久化 ⇒ 中断/重启后无目标引用。

### 项目立项目标的"三条与 Baritone 的差异"现状
| # | 差异 | 现状 |
|---|---|---|
| ① | Bot **可回收性安全策略** | **已闭合**（`RecoverabilityEvaluator` → `RecoverabilityPolicy.requiredFor` → `MovementSpec` 构造器**抛异常**校验 + 逐边事实穿到执行期；D-151/152/153 `WINDOWS_CLIENT`）。⚠️ 旧版此处"**全仓库 0 处读取**"是**过期**结论（台账 §6.11/§7 已改口）。真死值只剩 `SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED`/`NOT_REVERSIBLE` |
| ② | **多层失败上抛 → LLM 决策层** | 数据面齐**且消费者已存在**（`GoalDirector.onTaskTerminal`），但**输入被截断**：`TaskFailureReport.details`/`failurePhase` 不进快照 ⇒ LLM 看不到"为什么输"；且触发点不含任务内部事实（收益率/停滞/背包将满） |
| ③ | 未来 **bot 并行**接口 | 仅 `docs/MULTI_BOT_INTERFACE_RESERVATION.md` 预留 |

### 历史证据（不再逐条列举）
`.alice-supervision/client-tests/` 下保留 R2–R5、MineTask A/B/C、Movement 物理实验 1–6、各阶段验收证据目录；
改动流水见 `docs/AI_CHANGELOG.md`，稳定裁定见 `docs/AI_DECISIONS.md`（D-001…D-131）。

## 传输模块彻查（2026-09-13）

用户要求"从架构与实现查有没有屎山，并定要不要二次重构"。产出
[`TRANSFER_MODULE_AUDIT.md`](TRANSFER_MODULE_AUDIT.md)：**不是屎山**，三处真问题（生产/测试错位、
死码与只写状态、容器写入无授权维度），核心设计扎实 ⇒ **建议定向重构 R1–R3，不推倒重写**；
`/alice selftest` 建议退役（必崩于无 Mekanism + 与 in-game 电池重复），但 `InterfaceScanner` 的
Mekanism 硬引用是**活雷**，需立即修。

## 当前不要做

- ❌ 不把隧道、搭路、实验性移动**隐式**接入普通任务（D-076：必须显式授权 + 预算）
- ❌ 不把 `SEARCH_LIMIT` 当作 `UNREACHABLE`，也不据此自动授权挖隧道
- ❌ 不实施多 Bot 并行调度（只保留接口边界）
- ❌ 不让 `SOFT_SURFACE` / 实验性 Movement 悄悄进入生产任务
- ❌ 不在没有"预算 + 回收保证 + 残留策略"三件套时发放新的放置授权（§11-① 的 6 项要素）

## 开发工具链

- **源码镜像**：`./tools/mirror-windows-workspace.sh` → Windows `/mnt/d/JAVA_projects/alice/`
  - Windows 是源码镜像，不保留 `.git`
  - WSL `/home/fb486/projects/alice` 是唯一 Git 管理端
- **工件同步**：`./tools/sync-windows-artifact.sh [jar] [windows-repo] [runtime-mods-dir]`
  - 第三个参数显式给出才同步到运行时 `mods`
- **固定客户端**：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`
  - 日志：`logs/latest.log`、`logs/debug.log`
  - 截图/视频：`screenshots/`、`videos/`（如果需要）

## 阶段收尾（2026-09-12 上午：T1–T7 全部收口）

**这一阶段做了什么**：把 9/11 盘点出的 7 项"风险/待办"逐项收口，并且每一步都留下**可复跑的验证入口**。
最大的收获不是那 7 个修复，而是**两条防返工基建**：串联回归电池（一次右键 9 项）+ 离线可规划性自检
（场景改完先 `--all`，不用等客户端）。

| 项 | 内容 | 决策 | 验证等级 |
|---|---|---|---|
| **T1 工具语义** | 生产 `MineTask` 不再凭空发工具；改为只读判定 ⇒ 缺正确工具时 `no_suitable_tool` 如实失败；工具一律由**入口**发 | D-119 / D-120 | `WINDOWS_CLIENT`（`mine_regression` 11/11 含负例 `no_tool_refuses`；`lumber_job` 4/4） |
| **T2 清障换候选** | 一个清障候选失败只跳过该候选（`nextClearStep(excluded)` + `clear_skip`），候选用尽/预算用尽才闩锁；清障子任务信封 = **父信封子集**（`nestedSubTask()`） | D-121 | `WINDOWS_CLIENT`（`clear_retry_check` 连续 3 轮：4 次尝试 = 4 个不同候选） |
| **T3 扫尾预算推导** | ① 扫尾的信封与 tick 预算从**树干高度/待收物数**推导（`sweepGain` / `suggestedSweepTicks`）；先查出身，把 `8`（设计裁定）与 `12`（用户裁定）排除在改动之外 | D-123 | `WINDOWS_CLIENT`（一轮内两个树干高 3/7 的算术均可核对） |
| **T4 作用域归属** | `ScopeBuffer.begin()` **默认继承**「仍活着且在新区间内」的我方掉落物（会话内继承、会话结束 `end()` 清空）；删掉 D-108 手工收养 | D-124 | `WINDOWS_CLIENT`（`mine_regression` 12/12；三个历史病灶点都出现「继承掉落物=1」） |
| **T5 配额解耦** | `assignLumberJob` 用同一套候选源数可行树 ⇒ 配额随场景推导，不再与场景互相标定 | D-125 | `WINDOWS_CLIENT`（`场景可行树=4 ⇒ 配额=4`，后随 T7 变为 5） |
| **T6 离线可规划性自检** | 新增 `tools/check-scene-connectivity.py`：复用 `FixtureWorld`+`stand_candidates`/`can_see` 做**保守下界**可达性泛洪，查「封航线」（有合法站位但全不可达）与「目标格必须可达/必须不可达」 | D-125 | **本机自证**：`--selftest` 双向、`--all` 22 场景无硬伤、历史封死版本 `exit 1` |
| **T7 按棵预算真回归** | 台地东扩 + 第 4 棵同型橡树（`33,64,208`，由「橡树#1」平移得到）⇒ 累计清障 **9 > 8** | D-126 | `WINDOWS_CLIENT`（`trees 5/5 logs 26/26 **cleared=9** scaffoldLeft=0`） |

**本阶段立下的规矩（下次别再踩）**
1. **发料放在「任务」里，不能只放在「物品」里** —— 电池/命令/后续调用者都会绕过物品（D-122 附注）。
2. **「报告」必须等于「世界事实」** —— ② 拆除的 `remaining` 要按现场对账（D-116 附注二）。
3. **夹具加树/加地形必须检查唯一通道**，且**改完先跑 `tools/check-scene-connectivity.py --all`**（D-117 / D-125）。
4. **子任务能力 = 父信封子集**；**一次任务 = 一个作用域**（预算与账本都按此记账）。
5. **噪声 = 隐患**：报警只报可行动的病症；**断言「掉落物是否在账上」必须跨过一次服务器 tick END**
   （当 tick 内查 `liveDrops()` 恒为 0，D-124 附注）。
6. **改动前先查常量出身**：设计裁定/用户裁定（`8`、`12`）与"按场景反推"（旧的 `withGain(8)`、`200`）要分清（D-123）。

**5 分钟复验清单（本阶段全部证据）**
```
alice:regression_battery            # 一次右键：9 项常用回归（clear_retry/write_budget/scaffold/
                                    # clear_guard/lumber_failure/mine_regression/lumber_job/mine_job/pathing）
                                    # 期望：[Regression] SUMMARY … (9/9) → PASS
tools/check-scene-connectivity.py --selftest   # 离线：封死通道必判不可达、留缺口必判可达
tools/check-scene-connectivity.py --all        # 离线：22 场景无「封航线」
```
单独入口：`alice:mine_regression`（**12/12**，含 `no_tool_refuses` 与 `scope_reopen_keeps_drops`）、
`alice:clear_retry_check`、`alice:lumber_job`（`lumber_course` 场景，配额由场景推导，当前 5 棵 /
`cleared=9`）、`alice:mine_job`（`ore_course`）、`alice:lumber_failure_check`、`alice:scaffold_check`、
`alice:clear_guard_check`、`alice:write_budget_check`、`alice:pathing_regression`（18 场景 + 无头断言）。

**J7 收口（Step 1–4 全部 `WINDOWS_CLIENT`）**
- Step 1 生命周期闭环（D-107）｜Step 2 攀爬兜底 + 逐树会话内拆除（D-109）｜
  Step 3 崩溃兜底（D-127：`pendingForOwner` + 就近续做判定/阈值 16 格 + 启动报告，夹具第二轮"故意不拆"验证）｜
  Step 4 失败语义收敛（D-128：`trunk_too_tall` 正名、缺斧前置 `tool_missing`、`climb_incomplete` 分类、
  顶层归因、`scaffold_restore_incomplete` 命名）⇒ `alice:lumber_failure_check` **6/6**。

**J8 收口（区域型 MAINTAIN；D-129 / D-130 / D-131）**
- Slice A 可持续伐木区（`LumberRegionState` + `RegionLumberJob` + `alice:region_lumber` + 电池第 10 步）
  ｜Slice B 区域补种（`REGION_REPLANT` ⇒ 账本 `KEEP`）+ 树苗选择接口 ｜
  D-130 语义修正：**玩家只划水平范围 / 垂直自适应**（`adaptiveTop`）、**常驻**（只由显式打断结束）、
  等生长退避 40→…→600 tick ｜D-131 收尾：三个玩家接口做实 + 三处收尾缺陷。
  验证等级：功能全部 `WINDOWS_CLIENT`（D-130 附注）；三个玩家接口 `/alice region stop`、`/alice region set`、
  `idle-stop=true` **全部 `WINDOWS_CLIENT`**（D-131 附注 / 附注二）⇒ **J8 整条闭合**。
  ⚠ 注意 `idle-stop` 是持久化的：上一轮最后一条命令是 `idle-stop true`，要"常驻"语义先跑一次 `false`。
- J8 收尾后的**体验缺陷**（D-131 附注二已验，`WINDOWS_CLIENT`）：常驻空区域在聊天里**零反馈**
  ⇒ 首次查无活时回一句现场状态 + 怎么收工，终态（`idle_no_work` / `FAILED`）也回聊天（**只提示一次**）；
  `baseline=0` 被当成"没推导" ⇒ 每轮重推刷日志，改为持久化 `baselineDerived` 标记（整会话只打印 1 次）。
- J8 收尾修掉的**真缺陷**（不是"没测"那么简单，见 D-131；其中 ④ 已在客户端**当场抓到** `forward=1.00` 残留）：
  ① `/alice region set` 重划区域不作废旧区域的派生记账（`baseline=5` 污染空区域 ⇒ 永不待机）；
  ② 目标棵数未把"我种的苗"算进去（在"已砍完只剩苗"的地块上启动会退化）；
  ③ 显式打断记成 `CANCELLED_REPLACED`（与文档不符）⇒ 新增 `CANCELLED_BY_USER`；
  ④ `clearTask()` 不清残留移动输入（`/alice region stop` 停在半路 ⇒ "说停了却还在走"）。

**⑤ 路线重规划（2026-09-12，D-147）**：用户要求"先把基层补齐，模组适配只做浅测"。三阶段 ——
**阶段 1 基层收口**（决策层进回归电池 → S4 事件层 → 可回收性不变式接线（项目差异①）→ 决策 trace 落盘与跨重启语义）

**阶段 1 进度（2026-09-12）**：
- ✅ **基-2 决策层进回归电池**（D-149）：电池 9 项 → **16 项**，新增 `decision_contract` /
  `permission_gate` / `pickup_gate` / `collect_job` / `recipes_dump` / `event_thresholds`，插在 `pathing` 之前；
- ✅ **基-3 S4 事件层**（D-150）：`EventThresholds` —— `TOOL_LOW`（斧/镐剩余 ≤20%，回到 ≥35% 才复位）与
  `STUCK`（**有任务 + 有移动意图**且脚位 200 tick 未变；等待态不计时）两类**可行动病症**；写事件环 + 通知 `GoalDirector.onEvent`，
  **同一病症只报一次**（滞回复位才可再报）；自检四例 A 报一次 / B 复位数后再报 / C 卡住报一次 / D 继续不动不刷屏；
- ⏭ **下一个 = 基-1 可回收性不变式**（项目差异①：bot 可回收 = 不留残留、不掉落、不卡地形；**仍是空实现**，
  按 P0-B 顺序先填真实档位再打开检查）。以上两项**已实现已编译**；
  **首次实测（19:08–19:09）电池只跑到 7/16 就被暂停 ⇒ S4 那一步未执行**（D-150 附注一），
  已加单跑入口 `alice:event_threshold_check`（约 25 秒）。**首轮单跑又抓到 3 个夹具/报告缺陷**
  （D-150 附注二：竖井残留方块 ⇒ 维生第 1 tick 打断；`onGround` 同 tick 断言是 stale 值；
  夹具失败时 SUMMARY 把"没跑"报成 PASS —— 已全部修）。**第二轮又抓到 2 个**（D-150 附注三：
  支撑判定公式差一格（floorTop 应为"脚下方块底面+形状高度"）；`onGround` 是**粘滞**标记，
  被 teleport 到刚好贴地 + 每 tick 清零速度 ⇒ 永远读 false，非物理缺陷）。**2026-09-12 19:46 实测：
S4 事件层通过**（A/B/C 三例 PASS、TOOL_LOW×2 + STUCK×1 各恰好一条、用户目视确认 bot 顶壁没动），
并**第一次跑通「事件 → LLM 决策 → 执行」闭环**（LLM 选 `stop_current` 停掉卡死任务）。
新增 `GoalDirector.suspend()` 供自检期间暂停触发（D-150 附注四）。
**2026-09-12 20:13：C/D 用例 PASS，S4 四例全部有实测证据**；A/B 失败系**夹具不可重入**（已加归一化阶段）。
用户裁定 A/B 两项均做：**A** 自检窗口内事件只记录不通知决策层；**B** `BotManager.busyMessage` 统一
"bot 正忙"文案（49 处，常驻任务直接给 `/alice region stop`）—— 见 D-150 附注五。
**2026-09-12 20:40 收尾：S4 四例全 PASS + `terminal=COMPLETED` + 用户目视确认 ⇒ `WINDOWS_CLIENT`**
（D-150 附注六）；"悬空不下落/onGround=false"真因 = 夹具每 tick 清速度（附注三已订正）。
**基-9 工具与耐久管理第一批已完成（D-156 + 附注一/二，`WINDOWS_CLIENT` 全绿）**：`ToolSupply`（"现在能用什么 / 还能换成什么"，
快捷栏优先与生产选工具口径一致）+ `ToolMaintenanceTask`（4 种终态，含 `worn_no_spare` 如实上报）+
决策动作 `maintain_tool`（`TOOL_LOW` 之后 LLM 终于有能自己解决的选项）+ 工具事实进 prompt/汇报。
**未做**：工具来源（S6）、"耐久不足以完成计划工作量时提前拒绝"。

**基-8 授权登记缺口已完成（D-157 三附注，`WINDOWS_CLIENT`）**：G8 能力闸门（10 个死字段中 6 个变活，
含"保护区字段又变装饰"的真问题修复）、A9 登记表断档补齐、G3 外来破坏留痕、G5 容器写入留痕
（自检 10 项全 PASS）。
**基-7 内核残余第一批（K-1）已完成（D-158，待客户端复测）**：`PlanningStatus.PARTIAL` +
best-so-far 前缀 + `PathRetryRunner` 消费（先走前缀再重规划），顺带让 K-5 的死状态在新内核变活。
**K-3 `safeToCancel` 已完成并客户端验证（D-166，`WINDOWS_CLIENT`）**：运行期安全承诺点
（`MovementExecution.safeToCancel()` 默认 true + 8 个执行器覆写）+ 延后停止 + L2 开菜单空中门；
`alice:k3_stop_check` 的 DEFER 用例实测通过（`deferred=1`）。
**K-4 谓词不统一已完成"统一 + 测量 + 收口"三步（D-167 + 附注一，`WINDOWS_CLIENT`）**：
"可站"谓词原先在 6 处各写一遍、**目标准入一遍没查** ⇒ 现统一为 `MovementHelper.canStandCentered`
（8 个调用点，纯重构）；目标准入**不硬拒**（"起点即目标"合法 + 挖掘 `ENTER_TARGET` 的 goal 就是矿块），
改为进程累计遥测（`bot_report` 的"目标准入（K-4 累计）"行）+ 回归电池 SUMMARY 自断言 `K4=OK/VIOLATION`。
**K-4 遥测已在真实运行中被使用（`SERVER_TESTED`）**：`目标准入（K-4 累计）：goal_post_write_not_standable=89
final_segment_target_post_write=51` —— 两类**真异常码 0 次**（`[K4]` 告警 0 行），
写入类例外 89 次 = 设计如此；完整电池跑的 `K4=OK` 自断言仍待一次**跑完**的电池。
**D-168 夹具断言不得依赖世界历史**（用户实测"不清理掉落物就失败"）：`mine_regression` 的 `dropsLeft`
原先数"±6 盒内全部掉落物" ⇒ 世界残留导致假失败（run1 `collected=1/1` 却 `dropsLeft=1`，run2 手动清后 PASS）；
改为**基线 UUID 增量**（残留报 `foreignDrops=`，不计入判据）+ 三个挖掘场景函数补 `kill @e[type=item,…]`。
**D-169 电池自杀事故（第三轮实测的真因，已修）**：K-3 夹具嵌在电池里时，`stopTask` 停的是
**顶层任务 = 电池自己**（`停止请求延后到安全点：task=RegressionBatteryTask` → 第 12/25 步 CANCELLED_BY_USER）
⇒ 三轮电池都没有 SUMMARY。修法：K-3 **退出电池**（25 → 26 项）+ `fixture_not_top_level` 前提断言
（绝不再伤父任务）+ 手工入口扩成**右键 DEFER / Shift+右键 FORCED**（FORCED 此前从未被验证）。
一般规矩：**夹具不得对父任务产生副作用**，需要观察"顶层任务被停"就必须做成顶层入口。
**第四轮实测（WINDOWS_CLIENT）**：26 项电池**全 PASS** `(26/26) ticks=3507 → PASS`，
其中 `K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=88)` ⇒
**K-4 收口完成**（按"0 ⇒ 删临时告警"的义务删掉两处 `[K4]` 告警行，保留计数 + 电池自断言；
结论：缝真实存在但实战不咬 ⇒ 不引 `GOAL_NOT_STANDABLE` 硬拒）；
`mine_regression` 11/11 PASS ⇒ D-168 掉落物修复生效（无需手动清理）。
**D-170 资源缺陷（客户端日志才发现）**：`k3_stop_check`/`menu_probe`/`transfer_check` 三个模型是
**0 字节**（紫黑块/看不见 —— 这大概率就是 K-3 自检一直没被测的原因），`partial_search_check` 还指向
不存在的贴图；已补齐/改正 + 新增 **`tools/check-item-models.sh`**（空文件/坏 JSON/死贴图引用，脚本自测过）
并写进构建前检查清单。**未验证**：这 4 个物品贴图的实际显示（需看一眼背包）。
**余项**：K-2 legacy 活引用（`SurfacePathfinder` 7+ 处）、K-5 遗留枚举；
**K-3 自检（DEFER 回归 + FORCED 新入口）仍待测**（道具这轮才修好，之前看不见）。

**基-5 LLM 上抛契约已完成（D-155 + 附注一，`WINDOWS_CLIENT`）**：四个 Job 覆写 `failureReport()`（决策层能看到相位/进度）、
`MineProductFilter`（挖掘产物**目标驱动 + 标签族**，不再硬编码原版矿物、且尊重 `productTag`）、
**结构化拒绝回读**（`lastRefusal` 进下一轮 prompt + 进汇报 + 动作被接受后清除）。

**基-4 决策 trace + 跨重启语义已完成（D-154 + 附注一，`WINDOWS_CLIENT`，含一次真实跨重启验证）**：`DecisionTrace`（JSONL 落盘 + 内存尾、
超限轮转、写失败不影响决策）+ `DecisionState`（登记"重启会丢的未决请示/当前任务"，启动时**只报一次**）。
裁定：未决请示**作废但必须报出**；任务**不自动续做**但如实汇报；不做任何"看起来恢复了"的自动恢复。

**基-1 可回收性不变式（项目差异①）已完成两条轴并客户端验证（D-151/152/153 + 附注）**：：① 逐步"有回程"（D-151 测量 / D-152 逐边事实＋策略表，
**已客户端验证**）；② 活动"没留残留"（**D-153：残留 = 可回收性失败**，计数 + `RESIDUE` 事件 + 夹具自清场）。
**第一步已完成（D-151）**：`RecoverabilityEvaluator` 按类型给出
"等级 + 依据"、provider 8 个产出点与 `AStarMovementSearch` 改为取值、唯一转换点采用评估值并互相核对、
`RecoverabilityReport` 记账并在会话完成时打印；电池第 17 步 `recoverability` 四例（含**负例**：
`required>evaluated` 必须抛异常 ⇒ 证明 P0-B 的恒假校验已变活）。本机纯逻辑实测 `GUARD=LIVE`、
`TOSPEC_FALL evaluated=PATH_REVERSIBLE`。**`required` 一律未动 ⇒ 行为零变化**。
**实测（D-151 附注一）**：自检四例全 PASS（含"`required>evaluated` 必须抛异常"的负例 ⇒ 校验已变活）；
真实计划 12 会话、10 种 Movement 全覆盖、`distinctLevels=2`；寻路回归 **13/13 PASS 无退化**。
**第二步已实施（D-152）**：可回收性评估改为**逐边事实**（`RecoverabilityFacts` 穿搜索到执行期）+
`RecoverabilityPolicy` 策略表（`FALL>=PATH_REVERSIBLE`）⇒ 本机实测"**不带返回守卫事实的 FALL 边被拒绝**"，
即 D-036 差异① 第一次真的改变准入结果；自检新增 `fall_without_fact_refused` 负例。**待客户端复测**
→ **阶段 2 真实数据浅测**（挑两个模组，只验"运行时导出 + P1 读得懂多少"，不写适配器）
→ **阶段 3 能力扩展**（S6 Craft/Smelt → 机器适配器 → 偏好规则；多 bot 并行另立项）。完整缺口表见 D-147 与总账。

**④ 决策层框架（2026-09-12 规划）**：`docs/DECISION_LAYER_DESIGN.md` —— 三条通道
（**决策 / 汇报 / 请示**）+ 契约 + 分步骨架 **S1 事实层 → S2 选择层 → S3 请示层 → S4 事件层 →
S5 知识层(只读配方图) → S6 执行层(Craft/Smelt/Process + 偏好规则)**；S0（契约与管道）已 `WINDOWS_CLIENT`；
**S4 事件层已实现（D-150，待客户端验）**。
知识/配方面的参考材料见 `docs/KNOWLEDGE_RECIPE_GRAPH_NOTES.md`。

**① 安全底座小批次已完成（全部 `WINDOWS_CLIENT`，D-132 附注三）**：S-1 维生出口 / S-2 未加载区块·边界准入 /
S-3 删重复维生调用 / S-4 流体探针接线 + 起点脱困（D-133）。**② 决策层接入进行中**。

**① 的细节（D-132）**
—— S-1 维生出口（`SurvivalExitTask` + 纯查询 `nearestSafeRefuge` + 逃生豁免）、
S-2 未加载区块/世界边界准入（新状态 `GOAL_NOT_LOADED` + 跨区块节点门控，照 Baritone）、
S-3 删 `MineTask` 重复维生调用、S-4 接上 `FluidRiskPolicy`（硬拒不许再加高/清障）。
入口：`alice:survival_exit_check` / `alice:chunk_guard_check` / `alice:fluid_mine_check`（零参数右键）。
**客户端待测**；测完进入 **② 决策层接入**（先补 Job 契约：`terminalReason`→`TaskOutcome`、`botUuid`、
`GoalSpec`→`Job` 统一入口）。

**下一步（按建议优先级；**完整未收口项与方向候选见 [`OPEN_ITEMS_LEDGER.md`](OPEN_ITEMS_LEDGER.md)**）**
1. **未覆盖的行为分支（与 T2/T3 同批登记）**：几何不可达导致的「首候选失败、次候选成功」场景、
   清障子任务**加高**行为、`trunkHeight+1>12` 截断、① 扫尾超时分支、`climb_incomplete` 场景、
   `too_far` 恢复分支、真实崩溃重启路径。
2. **T6 盲区**：`19/24` 那种「本来就看不见目标、必须清障」的目标只给软提示 —— 其"清障是否可行"
   仍归 `analyze-lumber-scene.py`（且不计可达性）；两项合一才算完整。
3. 历史登记项：G3（模组连锁破坏无凭证）、G5（容器写入维度）、G4 Slice B2（尝试级 tick 预算）、
   `isExpensiveToClear` 成本化 + `#alice:clear_forbidden` 标签、`MiningBudget.tierOf` 的 `#forge:ores/*`。
4. 可选（J8 未做的小项）：区域"目标密度"的手动配置接口（现在自动推导 = 首次巡查的 standing）、
   选区魔杖（右键记 pos1 / 潜行右键记 pos2 ⇒ 免坐标命令的零参数入口）。

## 会话收尾（2026-09-13）

**本会话主线**：阶段 1 基层收口（基-1/2/3/4/5/7-K1K2/8/9 大部分）+ **L2 方块交互路线**
（对比 → 探针验证 → `MenuSession` 组件 → 生产化）+ **传输模块彻查与重构**（R1/R2/R3 + L1）。
详见 `AI_DECISIONS.md` 的 D-151…D-165 与 `TRANSFER_MODULE_AUDIT.md` / `INTERACTION_LAYERS_COMPARISON.md`。

**唯一未收口**：`alice:transfer_check` 的 `end_to_end` 用例（根因已定位并修：**背包索引 ≠ 菜单槽位号**，
D-165 附注四），**待客户端复测**。判据与排查入口见 `OPEN_ITEMS_LEDGER.md` §6.11。

**本轮（K-3 之后）四条收口线**，全部有客户端证据：

| 线 | 结果 | 等级 |
|---|---|---|
| **K-4 谓词统一**（D-167 + 附注一） | "可站"谓词 6 处复制 → 唯一定义 `MovementHelper.canStandCentered`（8 调用点，纯重构）；目标准入先测量不硬拒；完整电池 `K4=OK(真异常 0 / 写入类例外 88)` ⇒ **收口**：不引 `GOAL_NOT_STANDABLE`，删临时告警、留计数 + 电池自断言 | `WINDOWS_CLIENT` |
| **D-168 夹具测量** | `mine_regression` 的 `dropsLeft` 原本数"盒内全部掉落物"⇒ 世界残留假失败（run1 两次 FAIL 只因 `dropsLeft=1`）；改**基线 UUID 增量** + 三个挖掘场景函数清实体 ⇒ `mine_regression` **11/11 PASS**，用户不必再手动清掉落物 | `WINDOWS_CLIENT` |
| **D-169 电池自杀事故** | 电池里的 `k3_stop_defer` 停的是**顶层任务=电池自己**（三轮电池都没 SUMMARY 的真因）；修：K-3 退出电池（25 → **23** 项）+ `fixture_not_top_level` 前提断言 + 手工入口扩成右键 DEFER / Shift+右键 FORCED | `WINDOWS_CLIENT`（电池 26/26 PASS） |
| **D-170 资源缺陷** | `k3_stop_check`/`menu_probe`/`transfer_check` 模型是 **0 字节**、`partial_search_check` 引用死贴图 ⇒ 客户端缺失模型/紫黑块（**K-3 一直没被测的真因**）；补齐修正 + 新增 `tools/check-item-models.sh`（空文件/坏 JSON/死贴图，已自测）并写入构建前清单 | 建模修正 `COMPILES`；**客户端 0 条 `Failed to load model alice`**（本轮日志）⇒ `WINDOWS_CLIENT` |

**K-3 自检（DEFER + FORCED）客户端验证通过**（2026-09-13，`WINDOWS_CLIENT`）：
`[K3] mode=DEFER … task=K3StopCheckTask` → 请求后仍被 tick(15) → 落地后 `已到安全点，执行延后的停止`
（terminal `cancelled:k3_defer:safe_point`，25 tick）；FORCED 同一入口 Shift+右键 →
`任务在不安全时刻被强制停止`（terminal `cancelled:k3_forced:forced_unsafe`，33 tick）；
`bot_report`：`deferred=1 forcedUnsafe=1 survivalUnsafe=0`；`目标准入（K-4 累计）：无异常计数`。

**K-2 第三批 + K-5 已完成（D-171/D-172，`COMPILES`，待电池回归）**：
legacy 双内核整批删除（19 文件，依据"按路径分析的零活引用"）；`POSTCONDITION_FAILED` 不删值、
改为给生产者（`PathSessionStatus.classify` 唯一定义）并加"无死值"自检。

**最新复测（客户端 12:42，全绿）**：电池 `(26/26) ticks=3301 → PASS`、`K4=OK(真异常 0 / 写入类例外 83)`、
**22/22 个终态步 `idempotent=true`**、四个 exec 用例 `foreignOk=true(另有残留1件不计入)`
⇒ D-168"残留不计入"分支**首次全量实测通过**；`entity_tick_missing` / `segment_stall` / 异常均 **0**。

**未闭环最高优先 = P0 假人物理冻结（D-176）**：任务/会话跑在全局 `ServerTickEvent.END`，而 `BotPlayer.tick()`
是**实体 tick**（受区块 entity-ticking 影响）⇒ **不同源**，正好解释"任务在跑、bot 一格不动、无报错"
（`segmentTicks=121 / entityTicksInSegment=0 / travelCallsInSegment=0`）。看门狗已带
`entityTicking=` 判别位，**待复现取现场**后定修法（不许先加"补 tick"特判）。

**（历史）第四次电池（22/23）**：唯一失败 = `transfer` 步 `end_to_end` 间歇不动的**同一病因**（即上面的冻结）。
**D-173 补漏**：`pathing/movement/` 14 文件（外部真引用 0）整包删除 + `.gitignore` 藏住的
`PathExecutor.java.backup` 删除。

**最新实测（2026-09-13）**：CORE 电池 **`(23/23) ticks=2616 → PASS`**（FULL 33 项 3527 ⇒ 短 26%）；
A4 熔炉客户端 PASS 且夹具**自复位**（`burnLeft=1398 → 重建方块=true`）。
**阶段 3-A 剩余**：A4b（菜单型炉子=熔炼升级页签，复用 `FurnaceStation`）、A5（决策层接线 `GoalAction.Craft`）。

**回归电池分档（最新，D-197）**：CORE = **BASELINE 13 + MAIN 10 = 23 项**（默认，物品
`alice:regression_battery` 或 `/alice battery core`）；FULL = 33 项（`/alice battery full`）；
`/alice battery list` 看归属表。配置唯一入口是 `RegressionBatteryTask.CURATION`，说明书
`docs/BATTERY_CURATION.md`（**AI 负责维护**：新增场景进 MAIN、验收退场移 EXTRA、换主线重写 MAIN）。
归属表与实跑项不一致 ⇒ **电池判红**（防"文档说测了其实没测"）。
**A4 熔炉已客户端 PASS**（`smelted=true stone+1` / `cobblestone-1` / `no_half_products=true` / `fuel_burn_ticks=1600`）。

**阶段 3-A「工作站」大块收口（2026-09-13，客户端全绿）**：通用发现（随身 2×2 / 工作台 3×3 /
精妙存储"合成升级页签"3×3，**零模组专属代码**）、工作站可切换（`/alice craft station`，不自动选优）、
只读探针（`alice:craft_grid_probe`）、执行接入发现器（B，D-193）、装配层装/拆（A，D-194）、
模组站点**真合成**（C，D-195）—— **32 项电池 `(32/32) ticks=3592 → PASS`**。
实测语义：产物**进容器**（`product_in_container=1`）、**不自动补料**、握升级右键容器=物品自己装进去（GUI 不开）。

**阶段 3-A / L2 工作站装配（D-194，客户端已验证）**：新增 `task/craft/StationProvision`（装配那一层：
QUICK_MOVE 装入 / 取回；**落点不猜、地址不猜**）+ 夹具 `CraftStationProvisionCheckTask` +
零参数入口 `alice:craft_station_provision_check` + **新写入理由 `STATION_PROVISION` 与 A 表 A13**（容器写入维度）；
电池 30 → **31 项**（`craft_station_provision`，模组不在 ⇒ SKIP）。场景 `craft_tab_course` **不再给玩家发升级**。
此前 B（D-193）已把合成执行接入发现器（`InventoryCraft`/`TableCraft` 不再看写死下标），
电池 (30/30) ticks=3383 复测通过。

**阶段 3-A / S1 合成工作站可切换（D-192，客户端已验证）**：新增 `task/craft/GridDiscovery`（通用网格发现，
只用原版 `CraftingContainer`/`ResultContainer` 判据，零模组知识）、`task/craft/CraftStation`（站点描述符 +
`/alice craft station` 切换 + 候选事实，`auto` 不含升级页签 ⇒ 不自动选优）、`task/CraftGridProbeTask` +
`alice:craft_grid_probe`（零参数只读探针）、场景 `alice_test:craft_tab_course` 与诊断
`alice_test:craft_tab_snapshot`。精妙存储的"合成升级页签"是**第二个站点范例**（take/source 未实测 ⇒ `UNKNOWN` 只读）。
新登记条件副作用：开/关精妙菜单可能按玩家设置清掉 `openTabId` ⇒ 探针只读断言如实限定。

**阶段 3-A 合成阶梯（最新）**：A1（`alice:craft_check`）/ A2（`alice:craft_action_check`）/
A3（`alice:craft_table_check`，含 `no_world_write` 零写入硬断言）**客户端均 7/7 PASS**（2026-09-13）。
**A3b 自放工作站已实施、待客户端**（D-190）：`task/craft/StationPlacement`（授权入口 **A12**、
`WriteReason.CRAFT_STATION_PLACE`、账本记 `TEMP` ⇒ 受"建拆同权"约束）+ 夹具 `alice:craft_station_check`
+ 场景 `alice_test:craft_station_course`（**故意没有工作台**）+ 电池 26 → **27 项**（`craft_station` 步）。
判据：`station_placed`（世界事实）/ `write_accounted`（账本 TEMP）/ `placed_table_craft` /
`teardown_clean`（**方块回空气 + pending=0**）。A4（熔炉）、A5（决策层接线）未做。

**本轮环境事实**：电池现 **27 项**（上一轮 26 项时为 `(26/26) ticks=3507 → PASS`，本轮新增 `craft_station` 步）；`k3_stop_check` 等 4 个道具贴图已正常；
镜像/同步脚本正常；`tools/check-item-models.sh` 已接入构建前清单（`checked=66 … PASS`）。

**环境提醒**：镜像脚本 `tools/mirror-windows-workspace.sh` 现为"默认不备份/不校验"快跑（8.6 秒）；
备份轮转由 `ALICE_BACKUP_KEEP`（默认 2）控制；`ALICE_MIRROR_BACKUP=1` / `ALICE_MIRROR_VERIFY=1` 可按需开启。

## 交接入口（新会话从这里起）

> **`docs/HANDOVER.md` = 当前交接文档**（主线、已验证清单、进行中的卡点与下一步、关键入口与环境、纪律提醒）；
> 新会话先读它，再读 `docs/AI_TEST_MATRIX.md`（电池分档）与**本文件**末尾几节（最新实测事实）。

## 开始任何新任务前

1. 读取本文件和 `AI_DEVELOPMENT_PLAYBOOK.md`
2. 读取 `AI_DECISIONS.md` 和相关 skill
3. 查看当前代码和 Git 状态
4. 先与用户确认目标、成功条件和最小测试方式
5. 客户端行为优先设计为游戏内可获得的测试物品/命令，通过右键/Shift+右键/命令观察

## 重要证据规则

- `IMPLEMENTED` → `COMPILES` → `SERVER_TESTED` → `WINDOWS_CLIENT` → `USER_ACCEPTED` 分开记录
- 不能访问 Windows 文件时，不得声称已经读取 Windows 日志
- bug 反馈后先询问操作、预期/实际、复现频率和日志/截图，再讨论根因和修复方向
- 服务端日志片段用 `[关键词]` 筛选；客户端截图关注 Bot 位置、聊天、GUI、粒子和回弹
