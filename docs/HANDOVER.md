# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> **本文件只是"当前断点"，不写历史**（历史在 `git log` 与 `docs/AI_DECISIONS.md`）。
> 会话恢复时**权威入口仍是 `docs/AI_PROJECT_STATE.md`**（AGENTS.md 的会话协议里列的是它）。
> 本文件 2026-09-14 从 300 行压到 ~70 行：旧内容（S0–S4 逐轮细节、R1 收口）全部可在
> `AI_DECISIONS.md` / `OPEN_ITEMS_LEDGER.md` / `git log` 里查到，按 AGENTS.md「规则准入尺子」第 3 条
> （只放指针，不抄原文）删除。

---

## 1. 一句话现状（2026-09-15 上午）

> **2026-09-15 晚（压缩断点后第一轮）：S-5「维生最小件」已落地并全绿**（D-226）。
> ① 溺水/着火纳入否决（宽限 10 tick + **必须有出口**，无出口 ⇒ `HOLD_NO_EXIT` **不否决**）；
> ② 掉血可见（`HazardState.previousHealth` 的第一个读者 ⇒ `DANGER` + `delta=`，冷却 40 tick 合并）；
> ③ "否决了却没出口"变可判读（`exit=none decision=stop`）；④ **维生进电池**：新步 `survival_exit`
> （**BASELINE**）⇒ **44 项 / CORE 35**。
> 顺带由新电池步**实测抓到并修掉**一条真缺陷：`startSurvivalExit` 拿 `blockPosition()` 当"排除自己"，
> 站在**半砖**上时会把 bot **自己那格**当出口（逃生 0 步 COMPLETED、bot 原地不动）⇒ 改用
> `SurvivalSystem.footCell`（D-105 唯一口径），并有半砖对照断言。
> **门槛**：`compileJava` ✅、`check-all.sh` 9 PASS + 1 预期 WARN ✅、
> 无头 `single:survival_exit` `checks=38 failures=0` ✅、无头 **CORE `(35/35) ticks=3686 → PASS`** ✅。
> ⚠️ **全是 `SERVER_TESTED`**；"真被否决"那半**只能真人验**（见 §3）。

> **2026-09-15 晚（同一轮追加）：客户端实测暴露 §5.9 真缺陷并已修**（D-227）。
> 用户点 `alice:survival_exit_check` 后"bot 没反应"——**不是窒息机制缺失**（日志里 `hazard=SUFFOCATING`
> 持续 181 tick 且真的掉血），而是**存档账本**里该 bot 有 **11 条 `SUSPENDED` 传输**（`NOT_MOVED`、
> `server_restart`）⇒ `blocksBot` 把它的 **`assign*` 通路永久堵死**，加上 `assignWalkTo` 是 void
> **静默什么都不做**、夹具又**不验前提**（照样打印"就位"）⇒ 看起来像"没反应"。
> **修法（用户裁定 C3+A+B，不放宽 C2）**：`suspendUnfinished`/`expireSuspensions` 对
> `location != BOT_INVENTORY` 的条目**直接落 `ABORTED`**（旧存档**启动即自愈**）+ 派活拒绝**可见**
> + `blocksBot` 拦截打 warn。判据挂在既有 BASELINE 步 `transfer` 里（含**反向对照**）。
> **门槛**：`single:transfer` 反向 `FAIL` → 正向 `PASS`；CORE `(35/35) ticks=3576 → PASS`；
> 自愈实测 `启动结清：11 条…` ✅（同一份污染账本，临时关掉无头"清账本"跑的）。

**`T0-a → T0-b → T1 → T2` 已全部落地（T2 已客户端验证）。
T3 八步已走完七步：步骤 1 / B3a / A / A2 / C / (A) / **B4** 全部落地并实测；
**只剩"接第 3 个模组本身"（`MachineMap` 加 19 行）与 B3b —— 两者**均已被 D-219 推迟**（等真实存档需要）。
⇒ **T3 视为已收口**（用户 2026-09-14 认可）。

**2026-09-15 上午的工作：把"验证通道本身就是可信的"这件事做实**（D-220）。
原计划"`pathing` 起点必填契约"**被实测否掉**（改前 6/6 绿、场景行逐字相同 ⇒ 抖动不复现），
转而查到并修掉三处**可证**缺陷：夹具时机参数**从未生效**、`DIAGONAL` 覆盖**靠偶发绕行凑**、
**无头通道会被敌对生物杀 bot**（`exit=3` 无判决）。修后 `core` ×3 = PASS 3/3、`coverage=PASS`、
怪物/死亡命中 0、三轮场景行逐字相同。
全文：`docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`。

**今天 2 个提交**：`ce3fac7`（D-220：夹具时机 + 夹具必动手断言 + `dip_course+run` + 无头 `peaceful`）、
`1aa948c`（D-220 附注：`FixtureScript` 共用原语 + 物品侧静默降级）；另有 `4a3bada`/`fe37f8e` 两条文档。

⚠️ **今天所有改动都只做了无头验证（`SERVER_TESTED`）**；没有一条碰渲染/物理/GUI，
所以**按纪律不冒充 `WINDOWS_CLIENT`**，也**没有**开客户端轮次（用户 2026-09-14 裁定：
无头能覆盖的就不占真人轮次）。**客户端 jar 已同步成 `462b10b5…`**（含 T3 七步 + D-220），
**且已由用户 2026-09-15 第十九轮实测验收**（见 §2d）：电池 `(30/30) ticks=3337 → PASS`、
pathing 场景行与无头**逐字相同**、T3 探针 42 字段中 41 个与无头逐字相同 ⇒ **T3 与 D-220 电池侧升 `WINDOWS_CLIENT`**。
**唯一没覆盖的**：R4 夹具物品（`pathing_disturber`/`pathing_waller`）本轮没跑 ⇒ 物品侧"未生效 ⇒ FAILED"仍是 `COMPILES`。

**待你拍板的问题**（§4）：① **客户端电池要不要也在夹具层清怪物**（无头侧已 `peaceful`）；
② 夹具分离要不要做（**只做包移动**，不要"移出发布 jar" —— 见 §2c 的范围修正）；
③ 要不要转向决策层（`survey/04`–`06` 需先过同样的核实）。

## 2. 更早的（T0–T2；今天的工作见 §2b）

| 项 | commit | 状态 |
|---|---|---|
| **完成度与优先级审查**（三路只读审计 + 无头通道实测） | `512f224`…`f5eb66e` | 全文 `docs/reviews/2026-09-14-项目完成度与优先级审查.md` |
| **T0-a 堵证据层假绿** | `75c1d23` | `COMPILES` + 门禁 + 4 条反向测试 |
| **T0-b 门禁进 CI** | `6bb26b2` | 本地 9/9 PASS；CI 已接 |
| **T1 修五条红线漏接** | `8b66572` | **`WINDOWS_CLIENT`**（第十七轮，见下） |
| **流程尺子 + 文档预算机器化** | `1ac024e` | 第 9 道门禁 `check-doc-budget` 在管 |
| **T2 无头通道 + 修「玩家区块票冻结」根因** | `4091694` | **`SERVER_TESTED`**：30/30 全绿、`entity_tick_missing` 47→0；**待客户端验证** |

**T2 报告全文：`docs/reviews/2026-09-14-无头回归通道T2-首轮实测.md`**（两个必须记住的坑 + 根因证据链）。

**第十七轮客户端（19:09–19:12，jar `abed83d2…`）**：6 条预测**逐条命中**；`(passed=30/30 skipped=0) → PASS`；
全会话 `decision_request` = **0**。两条硬证据：
- **R-1 前后对照**：同作用域 `Regression:mine_regression breaks` **5/64 → 13/64**，差值 **8 = 连锁自报 `mined=8`**；
- **R-3 深层漏洞被现场证实**：`trigger_skipped … trigger=terminal:RegressionBatteryTask until=1287 hold=true`
  —— `until` 早已过期，**挡住它的是 `hold`**（所以只改 `isSelfCheck()` 是不够的）。

证据全文：`docs/reviews/2026-09-14-项目完成度与优先级审查.md` §7/§8。

## 2b. 今天（T3 七步；全部已推送，HEAD = `e23869d`）

| 项 | 状态 | 读数 |
|---|---|---|
| **T3 步骤 1** `SiteKind` 五态 + 共享站点（`bc2b1aa`） | `SERVER_TESTED` | `with_site_confirmed 46→52`、`no_site 10→4`、`shared_site=6`、`row_block_missing=[]` |
| **T3 步骤 B3a** 读取器「先原版、再名族」+ 逐字段出处 | `SERVER_TESTED` | **值不变且被证明**（`vanilla_input=0` + `divergent=0` + `read_notes=0` ⇒ 每字段逐位相同）；判据一行未动 |
| **顺带修的**：`MachineProbe` 抽样不再是确定性的 | `SERVER_TESTED` | 修前**同 jar 两轮读数就不同**（`input_readable` 65/64、`query_machine_route` 0/2、`recipe_order_hash` 5 轮 5 值）⇒ **旧读数全部作废**；修后**同 jar 三轮 SUMMARY 逐字相同** |
| **T3 步骤 A** 探针可见性：枚举来源 = **配方注册表** | `SERVER_TESTED` | `unmapped_total=19`（`create` **15 类型/506 配方**、`ExtendedCrafting` **4 类型/25 配方**，**表里各 0 行**）；已登记部分读数逐字未变；两轮逐字相同 |
| **T3 步骤 B4** 能力登记（散文判据的可执行部分） | `PASS`（含**负测试**） | `capabilities=31 组=5 已用=31`；上游已取证 4/5；改一个名字 ⇒ 3 条独立报错。`MOD_ADAPTER_PROTOCOL` §3 的散文判据**保留**，但旧取证方式（`grep` 字面量计数）**作废** |
| **(甲) `ASCEND_NO_HEADROOM` 可判读读数** | `IMPLEMENTED`+`COMPILES`（**尚未被触发**） | 失败码带几何 `ASCEND_NO_HEADROOM@from.up2=(x,y,z):block_id`；加完 4 轮未再现，2026-09-15 又跑 6 轮仍未再现 ⇒ **不是** `SERVER_TESTED` |
| **(A) 落地同步（电池）**：步骤起步未落地时有界等待 | `SERVER_TESTED` | 修前同一 jar 两轮 1 FAIL / 1 PASS（`partial_search`）；**临时探针确定性复现触发条件**后 ⇒ 等待生效、`partial_search` PASS、`(30/30)`；删探针后 `(30/30) ticks=3414 → PASS`。step 1 空降是常态（8/8 轮）⇒ 该步记 info |
| **T3 步骤 A2+C** 未登记类型的形状 + M-4 查询层判决 | `SERVER_TESTED`（**两轮同 jar：轮 1 因无关的 `partial_search` 非确定性变红，轮 2 `(30/30) PASS`**） | ⭐ `unregistered_vanilla_only_out=37/37` ⇒ **模组名族对 Create/EC 读不出产出**，是 B3a 的原版路径在读 ⇒ **B3a 对接第 3 个模组是承重的**（改前这批会判 `MACHINE_RECIPE_UNSUPPORTED`）；M-4：`query_no_recipe=0`、`reachable=6/6` ⇒ 查询层对新模组物品诚实 |

**B3a 全文：`docs/reviews/2026-09-14-T3-B3a-读取器vanilla优先与探针确定性.md`**
（含两个新事实：**机器配方不实现原版 `getIngredients()` 的物品语义**；**旧 `catch(Throwable ignored)`
在本模组集下是潜在风险而非已发生的 bug** —— 对第 3 个模组才是真闸门）。
**步骤 A 全文：`docs/reviews/2026-09-14-T3-步骤A-探针可见性.md`**（核心事实：旧探针的枚举来源就是
`MachineMap` 本身 ⇒ **未登记命名空间连枚举都进不去**，所以 `unmapped=[]` 是"**看不见**"而不是"没有"；
现在接 Create 的工作量 = **实测 15 行**、EC = **4 行**）。

## 2c. 今天后半段（`survey/07` 审查的处理）

| 项 | 结果 |
|---|---|
| **D-219 模组适配改需求驱动** | `8ac6fbe`。`AGENTS.md` 加边界（**净增 0 行**，预算 1475/1476）+ `AI_DECISIONS.md` D-219 |
| **`survey/07` 行动清单核实** | 5 条里 **2.5 条已过时**：`LumberJob` 清障作用域缺陷 **2026-09-10 就修了**（`a8dd9ac`）、`requester` 无填充**已过时**、Create/EC"静默读不出"**今天被 B3a 修掉**；`task/` 那四个数**全对**；§2.2 架构结论**不冲突**（实建 = `action/WritePolicyMatrix` 按域实现）⇒ **不照单执行**。全文 `docs/reviews/2026-09-14-survey07-可行动条目核实.md` |
| **范围修正** | 审查说夹具"移出发布 jar"，但**交付工件只有一个**（同时是游戏内测试物品 + 无头电池的载体）⇒ 移出会把两者一起移走、**验证变贵**（负收益）。**建议只做包移动** |

**D-219 的直接后果**：`MachineMap` **不再**"接第 3 个模组就得补齐 19 行"（Create 15 + EC 4）——
那 19 行**当前买不到任何功能**（实测：`RecipeQuery` 已经报得出那些路线，加表行只把 `station`
从类型 id 换成方块 id，执行准入仍全 `READ_ONLY`）。**T3 视为已收口**，19 行按需再加。

## 2d. 2026-09-15 上午（验证通道"声明必须为真"）

| 项 | 结果 |
|---|---|
| **原计划被否** | 原打算做"`pathing` 子用例起点必填契约"；实测改前 `single:pathing` **3/3**、`core` **3/3** 全绿且**场景行逐字相同** ⇒ **假设不成立**，改做下面的可证缺陷 |
| **① 夹具时机参数从未生效** | `+wall`/`+disturb` 的 `30` 比的是**任务级** `ticks`（从不按场景复位）⇒ 实测 `tick=346`/`422` = **第一个执行 tick**；且放弃时 `disturbed = true` **假装做过**。改为 `sceneTicks` + **声明了夹具必须断言它真的动手**（`FIXTURE_NOT_FIRED`）+ 删静默降级 |
| **独立对照** | 修后 `wall_placed at=5,64,66 sceneTick=30`，与 `AI_DECISIONS.md:569` 的 **2026-09-09 客户端验证记录一致**；修前电池打的 `at=2,64,66` **不是同一个场景** |
| **② `DIAGONAL` 覆盖靠运气** | 修① 后 `core` **2/2 FAIL `coverage=FAIL([DIAGONAL])`** —— 对角线只来自 `+disturb` 修前那条偶发绕行；`dip_course`/`lava_course` 都只规划不执行。补 `dip_course+run`（复用同一地形）⇒ 确定性覆盖 |
| **③ 无头通道的噪声源 = 敌对生物** | 实测 `假人死亡: Alice was blown up by Creeper → 直接清除` ⇒ **无判决 `exit=3`**；同轮更早 `PLACE_NO_VALID_FACE` + `feet=1,64,68`（起点 `z=66`）⇒ **bot 被推离预期格**。修法：`headless-battery.sh` 把无头服务端设 `difficulty=peaceful`（**零生产代码改动**） |
| **修后判据** | `core` **8 轮全绿**（`30/30`、`coverage=PASS`、怪物/死亡命中 **0**、**8 轮场景行逐字相同**）；对照改前 **9 轮 3 红**（诚实边界：8/8 绿证不了"根除"，只证三个缺陷被修掉且没再出现） |
| **证据位改动** | `/tmp` 里上一会话的证据**已被清空**（历史 3 红因此无法复算）⇒ 本轮起证据放 `/home/fb486/alice-evidence/<日期>-<主题>/`（含 `README.md`） |
| **追加：消掉"同一份夹具两处实现"这一类** | 新增 `task/FixtureScript`（`wallPlan` 用 `footCell` + 前方第 2 格；`notFired` 共用字段串）—— §5 的教训不只是"时机算错"，而是**同一份语义写了两遍且已分叉**（时机基准 / 脚位算法 / 放弃策略），**没有编译期信号**。物品侧同时补掉两处静默降级：扰动放弃 ⇒ warn；夹具未生效 ⇒ 结果行带 `/FIXTURE_NOT_FIRED=…` 且**任务 FAILED**。**验证**：共用原语 `SERVER_TESTED`（`single:pathing` 场景行**重构前后逐字相同**、`core` ×2 PASS、累计 **core 10/10**）；**物品侧接线 `COMPILES`**（验收点 = 下一次 R4 客户端轮次） |
| **第十九轮客户端验收（用户 2026-09-15）** | ✅ 电池 **`(30/30) ticks=3337 → PASS`**、`K4=OK(0/0)`；**pathing 步 54 行场景日志与无头 `core` 逐字相同**（跨通道等价；⚠️ 比对前必须 `tr -d '\r'`——Windows 日志是 CRLF，直接 `diff` 会把 54 行全报成"不同"，我第一次就踩了）；`wall_placed at=5,64,66 sceneTick=30 pathIndex=3/9`、`disturbed sceneTick=32`、`coverage=PASS`（9 种 Movement 全到）、`FIXTURE_NOT_FIRED` **0**；**T3 探针 42 字段中 41 个与无头逐字相同**（唯一差异 `recipe_order_hash`，已登记为非确定值）⇒ **T3 与 D-220 电池侧升 `WINDOWS_CLIENT`**。怪物/死亡命中 **0**。证据 `.alice-supervision/client-tests/d220-t3-20260915/evidence/` |

## 3. ⚠️ 未验证 / 未做（**不要当成做完了**）

台账 **§8 / §9**（`docs/OPEN_ITEMS_LEDGER.md`）逐条有"触发条件"：

- **§5.9 / D-227 两点未验证**：① 结清结果的**落盘**（无头 halt 不存档；客户端正常退出会存，
  且**不存盘也会每次启动重新结清** ⇒ 症状已修）；② `assignWalkTo` 返回 false 这条**接线**缺一个
  "live 会话被挡"的电池判据（提案见台账 §5.9 末尾）。
- **S-5 / D-226（2026-09-15）只到 `SERVER_TESTED`**，两条**故意**没做客户端轮次（无渲染/物理/GUI 改动）：
  ① `alice:survival_exit_check` 的**两个模式**（右键=窒息硬危险；**潜行右键=着火软危险**，新增）
  ② "软危险 + 无出口 ⇒ **不否决**"与 no-exit 分支的**真人可见行为**。
  ⚠️ **"真被否决"不能在电池里验**：电池步的会话任务就是电池自己，真否决会把整轮电池 `complete` 掉
  ⇒ 这半只能真人看；动手时用 §6 的一条命令即可（右键/潜行右键各一次）。
- **✅ 客户端已验证（第十八轮，2026-09-14 20:14–20:18，jar `f478d9f7…`）**：
  `(passed=30/30 skipped=0) → PASS`、`entity_tick_missing` 0 条、无新增异常。**且是决定性的 B 轮** ——
  聊天行实证真人被传送到 `(10000, -50, 10000)`（离夹具区一万多格，票零覆盖）后才起电池
  ⇒ **票修复升为 `WINDOWS_CLIENT`**。机器真跑起来的读数：`smelt_ticks=200 smelted=true`、
  `walk_state=DONE walk_ticks=41`、`energy 20000→19950 progress_ticks=199`。
- **R2-残**：**已折进 T2**（用户 2026-09-14 裁定，不再单独开客户端轮次）；无头 30/30 全绿即已覆盖生产入口。
- **R1-残**：`prod_budget_exhausted` 分支无场景能触发（干净 3×3 只挖 8 块 ≪ 64）。
- **R4-残**：`RegionLumberJob` 补种仍直接 `setBlock`（已补触及校验）；改走 `placeAt` 会同时改**物品消耗路径**，需独立验证。
- **R5-残**：`StationProvision.click` / `InventoryCraft.click` 未做编译期强制（~19 处机械重构）。
- **代码结构债 TD-1（2026-09-15 用户复盘发现，AI 已核实）**：`*ExecutionFactory.validate()` 与
  `*Execution.preconditionsHold()` **各写一遍同一组前置谓词（8/10 个动作）**，且已分叉一处 ——
  `Traverse` 是唯一 `validate` 里**没有**几何检查的（其余 9 个都有 `<动作>_INVALID_GEOMETRY`）⇒
  越距 spec 到运行期第一帧才失败（码退化成 `TRAVERSE_INVALID_PRECONDITION`）。**优先级低、无已知故障**，
  且**不是有意移除**（自 `5d63cdf` 起就漏）。全文 + 复算命令：台账 **§5.5 / TD-1**。
- **`craft_check` 门禁缺口**（T2 新发现）：`machine_only_vanilla` 期望 `MACHINE_ROUTE`（需 Mekanism/Create），
  没装模组时它 `FAIL` 而非 `SKIP` —— 同文件 ⑥ 已有"没装该模组就 SKIP"的写法，④ 漏了前提声明。
- **T3 状态 = 已收口**（八步走完七步；第八步"接第 3 个模组本身"由 **D-219 判定为按需再做**）；
  剩余仅 **B3b**（等真有第 3 个模组要接时同期做）。以下原条目保留作历史指针：
  **B4 已完成**（§2b）；
  **接第 3 个模组本身**：`MachineMap` 加 **19 行**（Create 15 + EC 4）+ 每行方块/菜单/能力 + `machine-map.py`
  的 `UPSTREAMS` 一段；**B3b** Port 化 `Facts`（每产出自带 `chance` + 长度断言 —— 今天 `outputs`/`chances`
  是两个独立列表 + 空栈过滤 ⇒ **结构上无法配对**，A5；等第 3 个模组同期做）。
- **`query_reachable` 尚未升级为断言**（有意：measure first）—— 它是"读取器读出 X ⇒ 查询层不得对 X 报
  `NO_RECIPE`"这条真不变式，本轮只计数。
- **✅ 通道缺陷已修：(A) 落地同步**（用户 2026-09-14 批准）。`startStep` 在 premise 后**有界等待落地**
  （`awaitGrounding`，40 tick 上限）；setup 只做一次、落地**每 tick 复检**、等不到就**如实继续**。
  **验证 = 临时探针确定性复现**（`transfer` 后抬到 Y+3）：等待生效 → `partial_search` PASS；
  删探针后 `(30/30) ticks=3414 → PASS`。**我自己的实现 bug 被这次验证抓出**：
  第一版重入分支跳过复检 ⇒ 白等满 40 tick（超时日志里 `onGround=true`）。
  step 1 的空降是**常态**（8/8 轮，出生后第一 tick）⇒ 第一步记 info、第二步起记 warn。
  **grep 触发条件**：`premise step=… 起步时未落地`。
- **✅ 通道缺陷 2026-09-15 复核（D-220）**：上条"起步位置"假设**实测排除** —— 改前 `single:pathing` 3/3、
  `core` 3/3 全绿且**场景行逐字相同**（历史 3 红的 `/tmp` 日志**已被清空** ⇒ 归因永久停在"候选"）。
  转而在同一区域查到三处**可证**缺陷并修掉：① `+wall`/`+disturb` 的 `wallTick/disturbTick=30`
  **从未生效**（基准是任务级 `ticks` ⇒ 夹具总是"第一个执行 tick"就动手，且放弃时**假装做过**）
  ⇒ 改场景局部基准 + **声明了夹具必须断言它真的动手**（`FIXTURE_NOT_FIRED`）；
  ② `DIAGONAL` 覆盖**靠 ① 的偶发绕行凑** ⇒ 补 `dip_course+run` 变确定性；
  ③ **通道真正的噪声源 = 敌对生物**（实测 `假人死亡: … blown up by Creeper` ⇒ **无判决 exit=3**；
  同轮更早 bot 已被推离预期格 `feet=1,64,68`）⇒ `headless-battery.sh` 把无头服务端设 `difficulty=peaceful`。
  **修后**：`core` ×3 = **PASS 3/3**、`30/30`、`coverage=PASS`、怪物/死亡命中 0、三轮场景行逐字相同。
  全文：`docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`。
  **证据改放** `/home/fb486/alice-evidence/<日期>-<主题>/`（`/tmp` 会丢）。
- **⚠️ 等待你拍的根治方向**：把"起点"变成**必填契约** —— `Step` 与 `pathing` 子用例都必须声明
  `startFoot` 或显式声明"我自带场景/复位"，**不声明就响亮失败**（这样"依赖上一步残留"在构造上不可能）。
  它碰全部 30 步 + `pathing` 子用例，是**电池语义**的改动 ⇒ 值得你过一眼再动手。
  ⚠️ 2026-09-15 复核后**优先级下降**：位置类假设已被实测排除，且真噪声源（怪物）已在无头通道清零；
  客户端侧仍是 `easy` 有怪物 ⇒ **先请你拍"客户端要不要也在夹具层清场"**（D-220 的复核触发 ①）。

## 4. 待用户拍板（恢复后**先问这个**）

1. **T3 剩余**：步骤 1 / B3a / A / A2 / C / B4 均已完成（§2b）。**只剩"接第 3 个模组本身"**
   （`MachineMap` 加 **19 行** + 每行方块/菜单/能力 + `machine-map.py` 的 `UPSTREAMS` 一段；
   **B3a 已被证明是它的承重前提**）与 **B3b**（等第 3 个模组同期做）。
   ⚠️ 这 19 行**已被 D-219 推迟**（买不到功能：`RecipeQuery` 已如实报路线，执行仍 `READ_ONLY`）
   ⇒ 等真实存档第一次需要走某台 Create/EC 机器时再做。

**下一轮"接第 3 个模组"的真实工作量（实测，不再是估计）**：`create` **15 行** + `ExtendedCrafting` **4 行**
= **19 行** `MachineMap`（每行含方块/菜单/能力/取证件）+ `tools/machine-map.py` 的 `UPSTREAMS` 一段。
**并且 B3a 已被证明是它的承重前提**（`unregistered_vanilla_only_out=37/37`：名族读不出 Create/EC，
是原版路径在读）—— 好消息是这一步已经做完并验证过了。

2. **通道**：2026-09-15 已定 ① **客户端不清怪物**（用户裁定：客户端仍 `easy`，**先观察**下一次客户端电池
   是否真被怪物干扰，有证据再改）；其余两条（`place_course+wall` 单次红、起点必填契约）已被本轮证据降级/排除（§3）。
3. **【潜在方向·待议，未动代码】用「旗舰自主挖掘任务」验收决策-执行框架**（用户 2026-09-15 提出；
   定位是**潜讨论 / 范例**，用户将**先与勘测员讨论再定**，并已提示"实际实现可能非常难于预估"）。
   **审计已完成**（三路只读审计，产物 = `docs/reviews/2026-09-15-挖矿高级任务作为框架验收范例-完成度审计与规划.md`
   + 台账 **§5.7**）：判定 = 框架"**单次闭环能用、自主长作业不能**"，差距是四件事
   （挖矿无候选菜单 / 长作业中段静音 / 父子额度零传递 ⇒ 缺镐被聚合成"没矿" / 无"转向"动作且任务树不持久化）；
   含 16 项能力对照、**7 块承重缺失**（第一 = 作业区抽象 + "进入作业区"）、最小补齐清单 **M1–M6**、
   维生+风险最小件、以及**明确拒绝清单**（战斗 AI / 簇优化 / 常驻矿区 / 饥饿进食 / 暴露预算 / `EdgeAdmission`）。
   **给勘测员的提示**：该文 §0 已声明**全部为静态审计、未实测**；其中一条可在无头通道 5 分钟内证伪/证实
   （"`MineJob` 缺镐 ⇒ 终态报 `no_reachable_candidate`"）⇒ 若要把这份审计当决策依据，**建议先跑它**。
   **用户 2026-09-15 已拍板**：确认 **A1–A3**（见 `AI_DECISIONS.md` **D-221**）、**启动 M 线**、§9 待实测按 AI 推荐先做 **#4**。
   **M 线进度**：✅ **M1**（D-222）、✅ **M2**（D-223）、✅ **M4**（D-224）、✅ **M3**（D-225）**四项全部完成并无头验证**；
   ⏭️ 剩下 **M4b**（`tree[].lastFailure`）与 **M3b**（预算耗尽/stale 的实测夹具）—— 都**不是** M 线的承重件；
   ⚠️ 但 **M5 已撤销**（见下）；
   **CORE 电池项数 30 → 34**（`mine_menu` / `no_progress` / `mine_no_tool` 新增，`llm_contract` 从 EXTRA 提到 MAIN）⇒ 客户端跑电池应看到 **`(34/34)`**。
   **下一轮客户端只需两件事**：① 跑一次电池（预期 **`(34/34) ticks≈3560 → PASS`**）；
   ② 跑 **R4 负例**（`/reload` → `/function alice_test:r4_negative_disturb` → 右键 `alice:pathing_disturber` 一次，
   **预期 `disturb_not_applicable` + `FIXTURE_NOT_FIRED=[disturb]` + FAILED** —— 那是如实回报）。
   ⚠️ 场景函数已复制进客户端存档，但**要先 `/reload`** 才认得新函数。
4. 之后收残留：**R1-残 / R4-残 / R5-残** + `craft_check` 门禁缺口（台账 §8/§9，各有触发条件）。

**上下文/文档纪律（AGENTS.md 已机器化，不用背）**：`AGENTS.md + PLAYBOOK + STATE ≤ 1476 行`
（当前 **1472**，余额 4）；新增散文规则必须同时删旧的；任何新验证手段必须挂在
`./gradlew build` 或 `tools/check-all.sh` 上（T2 就是这么接的：第 10 道门禁 `check-headless-battery`，
默认 WARN「断言未执行」，`ALICE_HEADLESS=1` 才跑）。

## 5. 环境与入口速查

- **客户端**：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`）。
- **镜像 / 同步**：`./tools/mirror-windows-workspace.sh`；
  `./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`。
- **本断点已同步的 jar**：`ed30f04a9356d10fdb2acb2673a82569b0d24e33cf4dd395fbae2ce28a4ebd51`（源 = `build/libs`，2026-09-15 19:52 同步到客户端 `mods/`；**含 S-5/D-226 + §5.9/D-227**）
- 上一轮 jar（19:03，仅 S-5）：`b918d7b201c792d6f453c247b92e58783b0a4896baaf5021c0e3be4a3628e1af`
- 上一轮 jar（2026-09-15 16:18，M 线 + R4 负例）：`35fa4580dfd8040e27490a8cd219df4c81f7011a94acf3e65c644f8538439814`
  —— 在 `462b10b5…` 之上**新增 M 线四项：M1（挖矿候选菜单）/ M2（长作业周期复评）/ M4（失败事实字段化）/ M3（专有终态理由）**。
  **已完成真人验收（2026-09-15 第十九轮）**：电池 `(30/30) ticks=3337 → PASS`、pathing 场景行与无头逐字相同、
  T3 探针与无头逐字相同（仅 `recipe_order_hash` 不同，已登记为非确定值）。
  ⚠️ **唯一没覆盖**：R4 夹具物品（`pathing_disturber` / `pathing_waller`）—— 想覆盖就各右键一次
  （预期见 `TESTING_GUIDE.md` §4.7；**没看到 `[R4 Fixture] disturbed|wall_placed` 就是如实 FAILED，不是 bug**）。
- **离线门禁（改完就跑这一条）**：`bash tools/check-all.sh`（**10 道**，三态 PASS/WARN/FAIL；
  `WARN` = 断言**没执行**，不是通过；`ALICE_MODS_DIR` 可指定上游模组目录）。
  ⚠️ 改了 Java/工具后**再跑一次**，别只看编译过。
- **无头回归（T2，新）**：`bash tools/headless-battery.sh core`（一条命令，无真人，退出码即判决）。
  一次性准备 `--install`（下 Forge 47.4.10 并装生产服务端到 `/home/fb486/alice-server`）；
  `--dev` 退回 dev 服务端（**装不了上游模组**，会因 cofh mixin refmap 缺条目崩服）；
  `single:<step>` 秒级调试。接进门禁：`ALICE_HEADLESS=1 bash tools/check-all.sh`。
  报告 `docs/reviews/2026-09-14-无头回归通道T2-首轮实测.md`。
- **电池**：`alice:regression_battery`（CORE=**30**）/ `/alice battery full`（FULL=**40**）；
  唯一配置入口 `RegressionBatteryTask.CURATION`。**判决三态**：`PASS`（30/30 全跑过）/ `DEGRADED`（有步被跳过
  ⇒ **不是绿**）/ `FAIL`。
- **场景**：仓库 `tools/test-scenes/alice_test/` → 客户端存档 `saves/新的世界/datapacks/alice_test/`；
  **改场景后要手动复制 + 游戏内 `/reload`**（`mirror`/`sync` 两个脚本都**不碰 `saves/`**）。
- **无头起服**：`./gradlew runServer --no-daemon`（`run/eula.txt` 已 `true`；`run/mods/` 目前为空 ⇒ 机器类步跑不了）。
- **上下文**：`bash tools/dsh-context-usage.sh "${DSH_SESSION_ID:-}"`；取回被压缩的原文：
  `node tools/dsh-session-log.mjs --shadowed|--seq a-b|--grep`。
- **三路审计的"完整报告"只在会话日志里**（落盘的只有结论：审查文 §3.1/§3.2/§3.3/§3.5）。
  需要原始细节（逐条 `file:line`、门禁的实测绕过记录、断言覆盖表）时：
  `node tools/dsh-session-log.mjs --grep "三路"` / `--grep "回归网能力审计"` 取回，**不要凭记忆重建**。

## 6. 断点后下一步（2026-09-15 晚更新：原推荐路线 = 维生最小件，**已完成**）

> 断点 commit = `c208fab`（工作树干净、已推送 `github/master`）；**S-5 落地后客户端 jar = `b918d7b2…`**。
> **维生最小件（S-5）= D-226 已落地**：`survival_exit` 步（BASELINE）+ 决策表 + 无出口不否决 +
> 掉血可见 + 脚位格口径修正；CORE `(35/35) → PASS`。全文见 D-226 / 台账 §5.8。

**推荐下一步（二选一，都由 AI 先推、用户最后拍板）**：

0. **【立刻可做·零成本】客户端重启一次世界**：§5.9 的 11 条挂起会在启动时被结清
   （日志 `[Transfer] 启动结清：11 条…`），随后点 `alice:survival_exit_check`（右键 = 窒息；
   **潜行右键 = 着火软危险**）就能看到 `任务因维生危险中断 …` → `[Survival] 逃生出口 …` →
   `kind=SurvivalExitTask terminal=COMPLETED`。**这一步同时把 S-5 从 `SERVER_TESTED` 升 `WINDOWS_CLIENT`。**
1. **【离线可推】`SurvivalExitTask` 的"选了落点却没走到"变可判读 + 真实否决链的真人入口收口**
   （台账 §5.8 的剩余两格）：① 现成的 `alice:survival_exit_check` 两模式**各点一次**（零参数，30 秒）
   就能把 S-5 从 `SERVER_TESTED` 升到 `WINDOWS_CLIENT`；② 若懒得分开点，可把它合并进一次客户端电池轮次
   （顺手复跑 CORE，确认客户端 35/35）。**代价**：占你一次客户端操作。**收益**：S-5 收口、维生不再是"半验证"。
2. **【要你参与】作业区抽象 / 粗目标 / "到达"**（`survey/08` §3 的结论：短线→长线真正的差别是
   `GoalFoot` 太精确 ⇒ `GOAL_NOT_LOADED`，而滚动重规划骨架已在跑）。**为什么必须你参与**：
   `survey/08` §10 明确**不回答**"到达算法"，因为它需要**真实存档地形做判据**（客户端）。
   这是"旗舰挖掘"的第一块承重件，做之前要先把"作业区"的语义（粗目标落在哪、到达判据是什么）定下来。

**明确不是承重件（按需再做，别插队）**：**M4b**（`tree[].lastFailure`）、**M3b**（`write_budget_exhausted`/
`stale_target` 两条映射**照词表写但未被观测**）、**`decision_contract` 的档位**（自称"任何改动都跑得到"
却挂 EXTRA，属策展裁定）、台账 §5.6 风险议题（B/C 候选）。

**压缩后取细节**：`node tools/dsh-session-log.mjs --shadowed last` 看被遮蔽的 seq，`--seq a-b` 取回，`--grep` 过滤。
