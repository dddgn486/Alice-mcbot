# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> **本文件只是"当前断点"，不写历史**（历史在 `git log` 与 `docs/AI_DECISIONS.md`）。
> 会话恢复时**权威入口仍是 `docs/AI_PROJECT_STATE.md`**（AGENTS.md 的会话协议里列的是它）。
> 本文件 2026-09-14 从 300 行压到 ~70 行：旧内容（S0–S4 逐轮细节、R1 收口）全部可在
> `AI_DECISIONS.md` / `OPEN_ITEMS_LEDGER.md` / `git log` 里查到，按 AGENTS.md「规则准入尺子」第 3 条
> （只放指针，不抄原文）删除。

---

## 1. 一句话现状（2026-09-15 上午）

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

**今天 1 个提交**：`D-220`（夹具时机 + 夹具必动手断言 + `dip_course+run` + 无头 `peaceful`）。

⚠️ **今天所有改动都只做了无头验证（`SERVER_TESTED`）**；没有一条碰渲染/物理/GUI，
所以**按纪律不冒充 `WINDOWS_CLIENT`**，也**没有**开客户端轮次（用户 2026-09-14 裁定：
无头能覆盖的就不占真人轮次）。**客户端 jar 仍是 `f478d9f7…`（第十八轮那个，即 T3 之前）**
⇒ 若下次要真人测 T3，**必须先 `./tools/sync-windows-artifact.sh` 换 jar**。

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

## 3. ⚠️ 未验证 / 未做（**不要当成做完了**）

台账 **§8 / §9**（`docs/OPEN_ITEMS_LEDGER.md`）逐条有"触发条件"：

- **✅ 客户端已验证（第十八轮，2026-09-14 20:14–20:18，jar `f478d9f7…`）**：
  `(passed=30/30 skipped=0) → PASS`、`entity_tick_missing` 0 条、无新增异常。**且是决定性的 B 轮** ——
  聊天行实证真人被传送到 `(10000, -50, 10000)`（离夹具区一万多格，票零覆盖）后才起电池
  ⇒ **票修复升为 `WINDOWS_CLIENT`**。机器真跑起来的读数：`smelt_ticks=200 smelted=true`、
  `walk_state=DONE walk_ticks=41`、`energy 20000→19950 progress_ticks=199`。
- **R2-残**：**已折进 T2**（用户 2026-09-14 裁定，不再单独开客户端轮次）；无头 30/30 全绿即已覆盖生产入口。
- **R1-残**：`prod_budget_exhausted` 分支无场景能触发（干净 3×3 只挖 8 块 ≪ 64）。
- **R4-残**：`RegionLumberJob` 补种仍直接 `setBlock`（已补触及校验）；改走 `placeAt` 会同时改**物品消耗路径**，需独立验证。
- **R5-残**：`StationProvision.click` / `InventoryCraft.click` 未做编译期强制（~19 处机械重构）。
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
3. 之后收残留：**R1-残 / R4-残 / R5-残** + `craft_check` 门禁缺口（台账 §8/§9，各有触发条件）。

**上下文/文档纪律（AGENTS.md 已机器化，不用背）**：`AGENTS.md + PLAYBOOK + STATE ≤ 1476 行`
（当前 **1472**，余额 4）；新增散文规则必须同时删旧的；任何新验证手段必须挂在
`./gradlew build` 或 `tools/check-all.sh` 上（T2 就是这么接的：第 10 道门禁 `check-headless-battery`，
默认 WARN「断言未执行」，`ALICE_HEADLESS=1` 才跑）。

## 5. 环境与入口速查

- **客户端**：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`）。
- **镜像 / 同步**：`./tools/mirror-windows-workspace.sh`；
  `./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`。
- **本断点已同步的 jar**：`f478d9f75ab8883701064100a728ba0c4af552f0c7c4e984521e7efb3916d13f`（三处一致）
  —— 注意这是**第十八轮客户端那个 jar**（T3 之前）。T3 的两次改动**没换客户端 jar**（按纪律：无头能覆盖的不占真人轮次）。
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
