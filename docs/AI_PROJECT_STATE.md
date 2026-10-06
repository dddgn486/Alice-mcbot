# Alice 当前项目状态

> 这是新 AI 会话恢复上下文的首要文件。只记录当前，不记录完整历史。

更新时间：2026-10-06

> ⭐⭐ **本文件 2026-10-06 起【正式启用】为新文档体系的「现在在哪」唯一出处**（出处 = 咨询回执 `004.1` 的裁定 1：`HANDOVER` **只记录断点**，**「现在在哪」全部转移到本文件**）。
> ⚠️ **按新规矩维护**（草案 `§D′-1` ④）：**头部必有 `更新时间`** · ⛔ **不许是追加式的**（⛔ 不写历史，历史去 `docs/HANDOVER.md`）· ⛔ 本文件之外**不许再有第二份「现在在哪」**。
> ⚠️ **失效条件**：超过**保鲜期 14 天**未更新 ⇒ 门禁 `check-project-state-freshness` 判 **WARN**（⭐ 本文件曾 **24 天**未更新，即此条）。

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
> 指针口径：**只放路径/commit/决策号，不抄原文**（AGENTS.md「规则按标准检查」第 3 条）。


## 最新（2026-09-15）—— 先读这里
**⚠️ 本节及以下大多是**历史存档**（最新到 2026-09-15 白天）。**
**⭐ 「现在在哪」只看下面「位置记录」那一节**（⭐ 2026-10-06 起它是**唯一出处**）——⛔ **不再**指向 `docs/HANDOVER.md`（⛔ 它按裁定 1 **只承担断点／历史留痕**）。**


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
- **流程检查已写进 `AGENTS.md`**（"规则按标准检查那三问 ＋ 只看一个指标"，净增≈0：同期把 STATE 的 41 行历史压成指针）。

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
- **S-5 维生线（2026-09-15/16）**：**水位 epic 已于 D-253 收口（`WINDOWS_CLIENT` + `USER_ACCEPTED`；客户端 `checks=122 failures=0` + pathing 全场景 0 FAIL）**；D-226 决策表 / D-228 补 `baseTick` / D-229 冻结危险档 / D-236 溺水不再静默 /
  D-237 上浮自救"乙" / D-238 出口必须**可规划** / **D-241 逃生准备金**（轴=任务信封，放置+破坏+PILLAR，上限 8/8）/
  D-242 水里"规划得到、执行不了"的结论 / **D-243 水里垂直移动**（`PILLAR`/`ASCEND` 水里按住跳跃上浮）/ **D-244 水柱省料分支**（起点/目的地都是水 ⇒ 上浮**不放方块**，完成口径改"脚位到格即成功"；实测整段逃生零放置）/ **D-245 逃生放置不自动回收**（回收时机交玩家 `/alice restore`，负向门禁防逃生循环）/ **D-246「出口列表」判定基本为空 ⇒ 关闭**（纯通行档「最近不可达」⇔「没有出口」，证明在决策里）。 / **D-248 水位切片 B 勘查完成、规划那半回退**（水位例外在 CORE 引出未解释回归：逃生改走更便宜的"破墙+升到水面格"路线、最终段支撑被读成 `Air` ⇒ `SEGMENT_FUTURE_BLOCKED`；证据与三个候选解释在决策里） / **D-247 水位切片 A**（实测：蹚水本来就能走 ← 新场景 `water_course`；补 `WATER_TRAVERSE_MULTIPLIER=7.25`让水里步子按水速计价；判据期望值走独立第二来源，防自指）。
  D-226/228/229/236 已 `WINDOWS_CLIENT`。
  零参数入口：`alice:survival_exit_check`（右键窒息 / Shift+右键着火）、
  `alice:survival_full_check`（一次右键跑完全部 **122** 条判据 ⇒ 聊天里看 `SUMMARY checks=120 failures=0 → PASS`）。
- **决策/快照线（同日）**：D-233（`decision_contract` 提档 MAIN + 本文件过时段落重写）、D-234（四个 Job 统一
  "子阶段失败"口径）、D-235（挂起传输"结清落盘"：持久化成立 + 跨重启幂等）。
- **保护区线（2026-09-18，D-313…D-317）**：区块级认领（忽略 Y / 全高度 / 旧格式迁移不静默丢）+ 零参数物品 `alice:protection_selector`
  （网格勾选 + 3×3 子格地形/明暗）+ `D-317` 崩溃修复（**用户实测地图正常打开 ✓ `WINDOWS_CLIENT`**；「不许同名重载」门禁）。
  FTB 只读兼容**已整体回撤**（`D-318`）；`D-319` 假人归属（创建者登记 + `/alice bots`/`adopt`，**客户端已验 ✓**）；`D-320` 两假人挨着爆栈崩已加闸 + 新 EXTRA 门先红后绿 + **同配置客户端复验 ✓**；`D-321` FTB 身份继承（`/alice ftb status|bind|unbind`：写入**只由显式命令**触发、代打 FTB 自己的命令）**客户端已验 ✓**（在队能挖领地内 / 退队不能）；`D-322` 步内 spawn/remove 假人崩（迭代活 map 的 CME，已修）；`D-323` ⭐ **破坏被拒不再谎报成功**（真机发现：FTB 拦下的破坏我们记成 `done`+`COMPLETED`，存档里方块还在 ⇒ 改为**世界事实判定** + 新码 `BREAK_REFUSED`，新门 `break_refused` 先红后绿，**客户端已验 ✓**，并且被拒不再白花 2 次重试预算）；`D-325` ⭐ **CORE 偶发假红 `survival_exit` 定根因并修**（掉血检测的**比较基准**没拨正 ⇒ 夹具自己抬血就吃掉了边缘；`D-312` 的"净掉≥2 即不偶发"作废，前提改写成**确定性**判据）；`D-326` ⭐ **批量写路径的第三方保护收口**（道路/补种不再绕过 FTB 认领，只读预检 `ftb_claim_denied`）；Xaero 联动**到此为止**（假人地图上可见 + FTB 队友标记 ✓；专属头像/右键菜单/认领叠加未做，随时可续，见 `D-324`）。**2026-09-19**：`D-327` ⭐ **红线场所化**（保护区=记账/预算/恢复的唯一消费者；野外=**成本模型+维生+只读审计**；逃生拆两机制：**局部 8 格保留**、**任务失败后回安全区不设预算**）—— **已登记未执行**；`D-328` ⭐ **远距离寻路实测**（走廊/平地/不加载三遍 + 执行器：**640 格规划 = 16 ms**、加载边界 ≈**192 格**、320 格行走 = **1217 tick**；两个夹具 bug 已固化判据）；`D-329` 挖矿路线图 + 两个已定口径（**只扫已加载** / 价值模型用户给）+ Q2·Q3 挂账；`D-330` Baritone 对照（**耐久不进成本模型**、远距离三件武器、**粗目标唯一必须**）；`D-331` ⭐ **`WalkToTask` 规划前同步加载目标区块 ⇒ 绕过 `GOAL_NOT_LOADED`** —— **已修 + 门禁**（修前 `false→true` / 修后 `false→false`，`walk_goal_unloaded`），**这才是"远距离卡"的真根因**。**2026-09-19 续**：`D-332` 自测档位（文档改动只跑文档类门禁 / 单功能 `single:`·`module:` / **CORE·全量只在收口**）；`D-333` 守卫线冻结（不重设计守卫、不做任务预设、只加测量过的调试接口）；`D-334` 垂直移动与信封（`changesWorld()` 是唯一静态分档口径 ⇒ **不许让 `ASCEND` 放方块**）+ 斜向上升缺口；`D-335` ⭐ `inventory_full` **复核=非缺陷**（`MineJob:235` 每 tick 都跑 ⇒ 我按勘测报告补的守卫被红证明证伪、已撤；补的是**矿侧门禁** `mine_inventory`，先红后绿）；`D-336` ⭐ **斜向上升那一格**已补（`PLACE_STEP_AND_TRAVERSE` `dy=+1`：挖矿信封修前**不可达** ⇒ 修后 1 条边；门禁 `place_step_diagonal`）；`D-337` ⭐⭐ **粗目标 `GoalNearXZ` + 内核搜索读未加载区块（红线 `D-132`）已修 + 门禁**（红 `newlyLoaded=6` → 绿 `0`；修法 = **读脚印闸门** `READ_FOOTPRINT_RADIUS=3` + 边界状态语义**绝不 `UNREACHABLE`**；`附注一` = 机制"后置门拦不住读 ⇒ 自增强泄漏"；`附注二` ✅ **远距离 = 一跳一跳逼近**：`FarTravelHop`（只读 `hasChunkAt` 夹到边界内侧 + `GoalNearXZ`）+ 生产任务 `FarWalkTask`（反复跳）—— A/B `粗目标 20000 节点 / 142~186 ms / PARTIAL` vs **一跳 161 节点 / 1 ms / REACHED**；执行侧 300 格 = **hops=2 / 1111 tick / DONE** + 精确落脚 30 tick）；`附注三` = 任务层同类收口（`PlaceTask` 未加载目标 ⇒ `place_target_unloaded`，红 `newlyLoaded=1` → 绿 `0`）⇒ **内核/走/扫描/放置四处全部收口**）；**`D-327` 执行项 ① 已落地**：机制 B = `SafeReturnTask`（一跳一跳逼近 + 终点精确落脚）+ `BotManager.complete` 接线（判决 `shouldStart`：区外 + 有认领区；**无认领区 ⇒ 一字不变**）+ 门禁 `safe_return`（无区诚实码 / 200 格 2 段 732 tick 回区 / 封死格 `return_unreachable` 且原地不动）—— 用户本轮重新声明：**就地固守不是兜底**（现在不做）⇒ 返程失败站定不动；`D-327` 剩 ② 预算/账本收窄 + 6 个 CORE 步。 ⚠️ `FarWalkTask` **尚无生产调用方**（复核触发已设）。`D-338` **附注六**（2026-09-19）= `§5.12` 第 4 件的**几何 + 锁定**层落地：`protection/JobAreaRegistry`（**工作区域方块级 ⇒ 任务区区块级最小覆盖**，单向派生；可覆盖保护区父类、**不得**覆盖安全区 ⇒ 报错且**不裁剪不降级**；**随 `scopeId` 生灭**、`NO_SCOPE` 拒绝、命令层无写入口 = 结构性锁定）+ 生产接线（`RegionLumberJob` 首 tick 解算、冲突 ⇒ `task_zone_conflict` **如实失败**、终态自动解除）+ 只读面（`/alice protect list` 三态、`/alice region info` 预检）+ 门禁 `task_zone`（EXTRA，**50 判据**）；**零权限改动**（闸门照旧）；⭐**反向对照两次**（拆冲突检查 ⇒ `failures=8` 恰好冲突那两组；`zoneOf` 退回按 owner 找 ⇒ `failures=2` 恰好两条生命周期判据）⇒ 复原 `checks=50 failures=0`；`module:protection` **3/3**。✅ **两条待拍板已关闭**（2026-09-19 用户：冲突 ⇒ **任务如实失败、不继续跑**；**权限阶梯我同意**）⇒ **`D-338` 附注八**（同日）= 第 4 件下半段落地：`WritePolicyMatrix.Level`（`L0/L1/L2/L3` 单一出处，`L3` 仅玩家显式、否则降级 `L2`）+ 新 `protection/ZoneAuthority` = **一个判据多处消费**（候选扫描 / 破坏闸门 / 放置闸门 / **能力闸门**—— 后两处分别补"保护区内放置本无闸门"与"客户端实测漏接"，见 `D-338` 附注十）+ `L1` 的"≤8"= **区内**配额；门禁 `task_zone` **50 → 89 判据**（★客户端实测后补：第四处消费/日志卫生/**保护区内非玩家发起封顶 `L1`**），⭐**三次反向对照**（拆候选消费 ⇒ 2 红恰好候选两条；破坏闸恒放行 ⇒ 3 红恰好 `L0`/`L1`/候选 `L0`；破坏恒拒 ⇒ 3 红恰好 `L2` 三条放行）⇒ 绿 `checks=76 failures=0`、`module:protection` 3/3、**CORE 51/51 零回归**、`check-all` 17 PASS/0 WARN/0 FAIL。⏳ 剩：勾选界面等级选择（操作逻辑先给用户审核）· 默认任务区（第 6 件）。
- **D-230 的自我修正**：我一度判断"bot 的血只减不增、必须补 `doTick()`"，实测**推翻**了它（`Player.aiStep()`
  本来就有自然回血）⇒ `doTick()` 默认**不启用**，开关 `alice.bot.vanillaTick` 保留为有记录的实验开关。
- **离线门槛**（"改一行到知道对不对"）：`tools/headless-battery.sh core` = **CORE 51 步**（总 **68 项**；`far_path_bench`/`path_retry_bench`/`mine_inventory`/`place_step_diagonal`/`safe_return`/`task_zone` 等属 EXTRA，其中前两个是**测量基准**且改世界 ⇒ 只 `single:` 跑）
  （当前 `passed=51/51 ticks=4791 → PASS`）；`check-all.sh` = **17 PASS + 0 WARN + 0 FAIL**；`module-selftest.sh` = **21 个模块**。
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
  收集 `allowWorldModification=true`）；禁止寻路器自行挖穿地形、禁止把 `SEARCH_LIMIT` 当授权。
- **未加载区块红线（`D-132`，措辞 2026-09-28 用户调整）**：内核**不得静默**加载区块（读未加载区块 = 同步生成 / 磁盘 I/O 落在 tick 线程上）；
  判据 = `MovementContext.chunkLoaded` 绝不加载 + 读脚印闸门 + `GOAL_NOT_LOADED`（⚠️ 原措辞「内核**从不**加载」不是高确定性方案，`D-331`/`D-337`）。
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

> **阶段收尾（2026-09-12 上午：T1–T7 全部收口）的历史正文已于 2026-10-05 移出本文件**：
> 那 177 行的逐项记录在 `docs/AI_DECISIONS.md`（`D-036` · `D-107`–`D-133` · `D-147`–`D-170`，共 **37 个编号**）与 `git log`。
> **为什么移出**：本文件第 3 行自己写着「**只记录当前，不记录完整历史**」，而它是**完整历史** ——
> ⭐ 同法先例 = 本文件上方「更早的收口历史（2026-09-09 ~ 09-10）」那一段（2026-09-14 的文档盘点判它「STATE 违反自己的规则」）。
> 指针口径：**只放路径/决策号，⛔ 不抄原文**（`AGENTS.md`「规则与流程的按标准检查」第 3 条）。

> **会话收尾（2026-09-13）的历史正文已于 2026-10-05 移出本文件**：
> 那 86 行的记录在 `docs/AI_DECISIONS.md`（`D-151` · `D-165` · `D-167`–`D-173` · `D-176` · `D-190` · `D-192`–`D-195` · `D-197`）与 `git log`。
> **为什么移出**：同上 —— 它是**完整历史**，与本文件第 3 行的「只记录当前」冲突。
> 指针口径：**只放路径/决策号，⛔ 不抄原文**。

## 位置记录（2026-10-05 立；⭐ **件级**，⛔ 不是模块级）

> ⭐ **出处** = 草案 `§D′-1` ④ 类身份栏逐字的「**做到哪了／下一步／卡在哪**」（2026-10-05 开发者裁 `B3`）。
> ⭐ **粒度 = 件级**（⭐ 你裁的）：⛔ **不并进 `§D′-10` 轴①** —— 轴① 问的是**模块**「还要不要往前做」，
> 本表问的是**某一件**「停在哪、等谁」 ⇒ ⭐ **粒度不同，所以能同时成立**
> （活样本：「`W4` **施工中**」＋「`W4-5` **等用户裁**」）。
> ⚠️ **它判不了什么**：⛔ 只记**在办件的位置**，⛔ **不代替** `docs/DOC_REFACTOR_PLAN.md` 的**工序表**
> （那张是**权威**，本表只是同一件事的「**现在在哪**」视图）。
> ⚠️ **每条必须答得出「等谁／哪一份」** —— ⛔ 答不出就说明它不该在这张表里（`§D′-10` 10.3 的粒度判据）。

| 件 | 位置 | ⛔ **卡在哪 / 等谁** |
|---|---|---|
| **文档体系 v2 整改**（`W0`–`W7′`） | **施工中** | ⭐ 主线；工序与判据 = `docs/DOC_REFACTOR_PLAN.md`（⚠️ 权威在那份） |
| **`W4-5` 顶层策略文档** | 待做 | ⭐ `§O-5` 第 3 条**已裁「新建一份（草案的后身）」**；⛔ 卡在草案现 **516 行** > 上限 **400 行** |
| **`W4-3` 设计件归位**（10 份） | 待做 | ⛔ 不许合并（实测 8-gram Jaccard 中位 **0.000**）⇒ 只下沉／登记 |
| **`W6-2` 给裁定条目补状态**（丙方案） | 待做 | ⭐ 口径已裁 = **只补「真的被当依据引」的条目**（⛔ 不是全 723 条） |
| **`W7′-4` 批 2..N 搬迁／合并** | 待做 | 靶子 = `docs/` 根 **56** ＋ `docs/reviews/` **75** ＋ `survey/` **50** |
| ✅ **咨询 `002` 已回执并落地** | ✅ **完成** | ⭐ 2026-10-05 开发者手动触发；`consult/request/002-批2开工前的硬阻塞与本轮结构性摩擦.md`（⛔ 无回执 = `check-consult-pairs` **红** ⇒ ⭐ **按用户令停工，不许推进相关事情**）｜内容 = `#2`「类标」三问 ＋ `#3`–`#7` ＋ 4 条结构性摩擦 |
| ⭐⭐⭐ **咨询 `003` 已提交 · ⛔ 全线停工** | ⏳ **等回执** | ⭐ 2026-10-05 开发者令「做咨询申请」；`consult/request/003-提取在这套流程里没有落脚点.md`（⛔ 无回执 = `check-consult-pairs` **红** ⇒ ⭐ **按用户令停工**）｜内容 = ⭐ **「提取」在批 2..N 的判据里没有落脚点**（`W7′-4` 只有搬迁／合并／过期三个件动作）｜⭐⭐ 开发者原话：「这个项目**所有的开发经验和项目价值大部分都在项目的文档里，是项目的所有文档，除了域外的**」｜⚠️ 停工前状态 = 批 2 审批台 `O166` **待批 47/56**（已关 9 件：4 判 ＋ 5 生成物销案） |
| ⭐ **`survey/55 §六` 准备清单 8 条** | ⭐ **`#1` 已裁 · 剩 7 条** | ✅ `#1` 靶子口径 2026-10-05 裁「**分层**」（内容口径为主线 ＋ 件口径只做机械那一半）⇒ ⭐ 连带销掉 `#8b`；⛔ **其余 7 条已全部进咨询 `002`** |
| ⭐⭐ **`#2`「类标」的格式／载体／门禁** | ⛔ **未立（批 2 的前置）** | ⭐ 实测仍 **0 格式 / 0 载体 / 0 门禁**；⚠️ ⛔ **不立它，批 2 主线（内容口径）的判据「有类标」就判不动** |
| ⭐ **「门禁自述块」落地** | 已批 · 待落地 | ⭐ `C5` 已批；⚠️ 它是 `survey/56` 的 **`C4` 候选丙**的前置（`C4` 已裁「先挂账」） |
| ⭐ **回执 `001` 的「规矩三档」**（硬／软／习惯） | 已批 · 待落地 | ⛔ 未进常驻件；落地口径 = 硬 → ①＋门禁 · 软 → `PLAYBOOK`＋`[软]` 标 · 习惯 → ⛔ 不进 |
| ⭐ **`A2`／`A3`／`A4`／`A6` 的改动** | 已落 · **未复核** | ⛔ **等另一个会话对抗性复核**（作者 ≠ 审阅者） |
| ⭐⭐ **本文件自己** | ✅ **已于 2026-10-06 正式启用** | 出处 = 回执 `004.1` **裁定 1**：本文件 = 「**现在在哪**」的**唯一出处** ⇒ ⛔ 本文件之外不许再有第二份；⭐ 保鲜期 **14 天**，超期 ⇒ `check-project-state-freshness` **WARN** |
| ⭐ **新文档体系核心件（`core20`）** | ✅ **已确立 = 14 件** | 出处 = 回执 `004.1`；清单 = `tools/new-system-core-inventory.tsv`（⛔ 旧的 20 件候选**已作废并归档**） |
| ⭐ **`#4` 提取漏斗** | ⏳ **第 2 遍未开始** | 账本 = `docs/EXTRACTED_KNOWLEDGE.md`（⭐ 进度读数看它 `§〇.1`）；⛔ 数字不写在这里，跑 `python3 tools/check-extraction-status.py` |
| ⭐ **回执 `004.1` 的待办 A–E** | ⏳ **未开始**（A/C/E/B 已批 · D 待讨论） | ⚠️ 它们**撞冻结靶子 188** ⇒ 先落 `M7` 白名单 ＋ 「靶子不随改名／搬迁变动」口径 |

## 交接入口（新会话从这里起）

> **`docs/HANDOVER.md` = 当前交接文档**（主线、已验证清单、进行中的卡点与下一步、关键入口与环境、纪律提醒）；
> 新会话先读它，再读 `docs/AI_TEST_MATRIX.md`（电池分档）与**本文件**末尾几节（最新实测事实）。
> ⭐ **「现在在哪」看上方那节「位置记录」**（件级；2026-10-05 立）—— ⛔ 它不代替工序表，只给你**当下视图**。

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
