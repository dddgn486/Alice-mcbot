# 交接断点（HANDOVER）—— 2026-09-14 21:20 收口

> **本文件只是"当前断点"，不写历史**（历史在 `git log` 与 `docs/AI_DECISIONS.md`）。
> 会话恢复时**权威入口仍是 `docs/AI_PROJECT_STATE.md`**（AGENTS.md 的会话协议里列的是它）。
> 本文件 2026-09-14 从 300 行压到 ~70 行：旧内容（S0–S4 逐轮细节、R1 收口）全部可在
> `AI_DECISIONS.md` / `OPEN_ITEMS_LEDGER.md` / `git log` 里查到，按 AGENTS.md「规则准入尺子」第 3 条
> （只放指针，不抄原文）删除。

---

## 1. 一句话现状

**`T0-a → T0-b → T1 → T2` 已全部落地（T2 已客户端验证）。T3 进行中：步骤 1（`SiteKind` + 共享站点）
与步骤 B3a（读取器「先原版语义」+ 逐字段出处）**已落地并实测**。当前断点 = T3 剩余三步
（**B3b / B4 / M-4**），它们是**接第 3 个模组之前必须做**的。**

⚠️ **本轮改动落在配方读取路径上，只做了无头验证（`SERVER_TESTED`）**；读取器不碰渲染/物理/GUI，
所以**按纪律不冒充 `WINDOWS_CLIENT`**，也**没有**为它开客户端轮次（用户 2026-09-14 裁定：
无头能覆盖的就不占真人轮次）。客户端 jar 仍是 `f478d9f7…`（第十八轮那个）。

## 2. 刚做完的（全部已推送，HEAD = `bc2b1aa`；本轮 B3a 见 §2b）

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

## 2b. 本轮（T3 步骤 1 + B3a）

| 项 | 状态 | 读数 |
|---|---|---|
| **T3 步骤 1** `SiteKind` 五态 + 共享站点（`bc2b1aa`） | `SERVER_TESTED` | `with_site_confirmed 46→52`、`no_site 10→4`、`shared_site=6`、`row_block_missing=[]` |
| **T3 步骤 B3a** 读取器「先原版、再名族」+ 逐字段出处 | `SERVER_TESTED` | **值不变且被证明**（`vanilla_input=0` + `divergent=0` + `read_notes=0` ⇒ 每字段逐位相同）；判据一行未动 |
| **顺带修的**：`MachineProbe` 抽样不再是确定性的 | `SERVER_TESTED` | 修前**同 jar 两轮读数就不同**（`input_readable` 65/64、`query_machine_route` 0/2、`recipe_order_hash` 5 轮 5 值）⇒ **旧读数全部作废**；修后**同 jar 三轮 SUMMARY 逐字相同** |
| **T3 步骤 A** 探针可见性：枚举来源 = **配方注册表** | `SERVER_TESTED` | `unmapped_total=19`（`create` **15 类型/506 配方**、`ExtendedCrafting` **4 类型/25 配方**，**表里各 0 行**）；已登记部分读数逐字未变；两轮逐字相同 |
| **T3 步骤 B4** 能力登记（散文判据的可执行部分） | `PASS`（含**负测试**） | `capabilities=31 组=5 已用=31`；上游已取证 4/5；改一个名字 ⇒ 3 条独立报错。`MOD_ADAPTER_PROTOCOL` §3 的散文判据**保留**，但旧取证方式（`grep` 字面量计数）**作废** |
| **(甲) `ASCEND_NO_HEADROOM` 可判读读数** | `IMPLEMENTED`+`COMPILES`（**尚未被触发**） | 失败码带几何 `ASCEND_NO_HEADROOM@from.up2=(x,y,z):block_id`；加完 4 轮未再现 ⇒ **不是** `SERVER_TESTED` |
| **(A) 落地同步（电池）**：步骤起步未落地时有界等待 | `SERVER_TESTED` | 修前同一 jar 两轮 1 FAIL / 1 PASS（`partial_search`）；**临时探针确定性复现触发条件**后 ⇒ 等待生效、`partial_search` PASS、`(30/30)`；删探针后 `(30/30) ticks=3414 → PASS`。step 1 空降是常态（8/8 轮）⇒ 该步记 info |
| **T3 步骤 A2+C** 未登记类型的形状 + M-4 查询层判决 | `SERVER_TESTED`（**两轮同 jar：轮 1 因无关的 `partial_search` 非确定性变红，轮 2 `(30/30) PASS`**） | ⭐ `unregistered_vanilla_only_out=37/37` ⇒ **模组名族对 Create/EC 读不出产出**，是 B3a 的原版路径在读 ⇒ **B3a 对接第 3 个模组是承重的**（改前这批会判 `MACHINE_RECIPE_UNSUPPORTED`）；M-4：`query_no_recipe=0`、`reachable=6/6` ⇒ 查询层对新模组物品诚实 |

**B3a 全文：`docs/reviews/2026-09-14-T3-B3a-读取器vanilla优先与探针确定性.md`**
（含两个新事实：**机器配方不实现原版 `getIngredients()` 的物品语义**；**旧 `catch(Throwable ignored)`
在本模组集下是潜在风险而非已发生的 bug** —— 对第 3 个模组才是真闸门）。
**步骤 A 全文：`docs/reviews/2026-09-14-T3-步骤A-探针可见性.md`**（核心事实：旧探针的枚举来源就是
`MachineMap` 本身 ⇒ **未登记命名空间连枚举都进不去**，所以 `unmapped=[]` 是"**看不见**"而不是"没有"；
现在接 Create 的工作量 = **实测 15 行**、EC = **4 行**）。

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
- **T3 剩余**（`create` / `ExtendedCrafting` **已在客户端 `mods/` 里**，接它们之前必须做完）：
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
- **⚠️ 通道缺陷**已**定形**（2026-09-14）：**9 轮里 3 轮红，3 轮不同步骤、不同码，
  共同点却是一个 —— 起步时 bot 不在预期位置**：
  `partial_search`（空降）/ `place_course+wall`（`ASCEND_NO_HEADROOM`）/
  `place_course+disturb`（**`STALE`** = `STALE_START`）。后两例在 `pathing` **子用例**内、都 `replans=2`。
  ⇒ **(A) 只修了电池"步骤"层；`pathing` 子用例层有同一缺口。**
  **(甲) 的读数已就位但 4 轮未再现**（`IMPLEMENTED`，不是 `SERVER_TESTED`）。
  **不盲修**（Anti-Pattern 1）。候选根治见下条。
- **⚠️ 等待你拍的根治方向**：把"起点"变成**必填契约** —— `Step` 与 `pathing` 子用例都必须声明
  `startFoot` 或显式声明"我自带场景/复位"，**不声明就响亮失败**（这样"依赖上一步残留"在构造上不可能）。
  它碰全部 30 步 + `pathing` 子用例，是**电池语义**的改动 ⇒ 值得你过一眼再动手。

## 4. 待用户拍板（恢复后**先问这个**）

1. **T3 剩余**：步骤 1 / B3a / A / A2 / C / B4 均已完成（§2b）。**只剩"接第 3 个模组本身"**
   （`MachineMap` 加 **19 行** + 每行方块/菜单/能力 + `machine-map.py` 的 `UPSTREAMS` 一段；
   **B3a 已被证明是它的承重前提**）与 **B3b**（等第 3 个模组同期做）。
   下一步若继续推进，**不需要你拍板** —— 但那 19 行要**逐行取证**（方块 id/菜单类/能力），是实打实的工作量。

**下一轮"接第 3 个模组"的真实工作量（实测，不再是估计）**：`create` **15 行** + `ExtendedCrafting` **4 行**
= **19 行** `MachineMap`（每行含方块/菜单/能力/取证件）+ `tools/machine-map.py` 的 `UPSTREAMS` 一段。
**并且 B3a 已被证明是它的承重前提**（`unregistered_vanilla_only_out=37/37`：名族读不出 Create/EC，
是原版路径在读）—— 好消息是这一步已经做完并验证过了。

2. **通道还剩两件**（§3 末两条）：① `place_course+wall` 单次红、**无输入级证据** ⇒ 建议先加一条
   可判读读数（失败时 dump 目标格及其上方两格方块 id）再跑 N 轮，**不盲修**；
   ② 是否把"每步起点"升级成 `Step` 的**必填契约**（不声明就响亮失败）—— 这是根治方向，但会碰全部 30 步。
2. 之后收残留：**R1-残 / R4-残 / R5-残** + `craft_check` 门禁缺口（台账 §8/§9，各有触发条件）。

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
