# 交接文档（HANDOVER）—— 2026-09-13 会话末尾

> 用途：本会话上下文即将用尽；**下一轮从本文开始读**，即可零损耗接着干。
> 权威状态以仓库文档为准：`docs/AI_PROJECT_STATE.md`（总览）、`docs/AI_DECISIONS.md`（决策与实测事实）、
> `docs/OPEN_ITEMS_LEDGER.md`（开放项与逐轮实测）、`docs/BATTERY_CURATION.md`（电池分档与维护规则）。

## 1. 主线与目标

- **长期目标**：完全参照 Baritone 搭建寻路内核（差异仅三条：可回收性安全策略 / 多层任务失败向上传递 / 未来并行 Bot 接口）。
- **当前主线（阶段 3-B）＝模组机器适配**：按 `docs/MOD_ADAPTER_PROTOCOL.md`（六步流水线，只读先于执行）；
  **第一个实验 = Mekanism**，第一最小闭环 = **S0+S1（全只读）**：类型事实表 + 查询层机器路线 + 探针 `alice:machine_probe`。
  阶段 3-A（A1–A5）已全部客户端验证收口。
- 用户裁定（本阶段）：**不硬编码合成方式**；工作站**由玩家切换、不自动选优**；**装升级=配置行为（独立一层）**；
  **页签层**：实测"开与不开无差别"⇒ **什么都不做**；电池**只测必要基础 + 当前主线，AI 负责维护**。

## 2. 已验证（`WINDOWS_CLIENT`，有日志/截图证据）

| 能力 | 证据要点 |
|---|---|
| A1 只读配方查询 | `alice:craft_check` 7/7 |
| A2 随身 2×2 | `alice:craft_action_check` 7/7 |
| A3 工作台 3×3（零写入） | `alice:craft_table_check` 7/7（含 `no_world_write`） |
| A3b 自放工作站（建拆同权） | `alice:craft_station_check` 7/7 |
| S1-1/2/3 通用网格发现 + 工作站可切换 + 只读探针 | `alice:craft_grid_probe`：三种站点全认对（`grid=2x2/3x3`、`matrixBy=resultSlotFieldByType`、`gridBy=ownerDeclaration(getRecipeSlots)`），`grid_addressable_without_tab=true` |
| B 执行接入发现器 | 电池回归：`craft_action/craft_table/craft_station` 全绿（发现结果与旧常量逐项一致） |
| A 装配层（L2） | `alice:craft_station_provision_check`：`provision_verified=true`、`deprovision_verified=true`、`item_returned=true`、`no_block_writes=true` |
| C 模组站点真合成 | `alice:craft_station_craft_check`：消耗 8 圆石 / 产物 1、**产物进容器**、不自动补料、装→用→拆全绿 |
| A4 熔炉（方块型） | `alice:craft_furnace_check`：`discover=OK input=#0 fuel=#1 output=#2 container=FurnaceBlockEntity`、`smelted=true`、`no_half_products=true`、**自复位**（`burnLeft=1398 → 重建方块=true`） |
| **A4b 菜单型炉子（精妙"熔炼升级页签"）**（**USER_ACCEPTED**：用户确认"只开关 2~3 次、无连续开关"） | `alice:craft_cooking_check`：`discover=OK input=#64 fuel=#65 output=#66 container=SimpleContainer data=CookingLogicContainer(selfReported)`、`smelt_ticks=207`、`smelted=true`、`input_consumed=true`、`no_half_products=true`、`leftovers_returned` 三项全 true、`deprovision_moved=true`、`upgrade_returned=true`、`verdict=PASS` |
| 电池分档 | CORE **`(24/24) ticks=2809 → PASS`**（含 `craft_cooking=PASS`、`craft_furnace=PASS`；FULL 34 项） |

## 3. A4b（菜单型炉子 = 精妙"熔炼升级页签"）—— **已收口 ✅**（能力 + 夹具双双客户端 PASS）

**目标**：同一个精妙容器 + `sophisticatedstorage:smelting_upgrade` ⇒ 3 格烹饪槽，能放料/等烧/取产物，用完拆回。

**真因（19:02 客户端 diag + 字节码核对，已修）**：3 格烹饪槽**可达**（`#64` 输入 / `#65` 燃料 / `#66` 输出，
`mayPlace` 形态完美，但**未登记在 `menu.slots`**），宿主 `CookingUpgradeContainer` 也**看得见**；
卡的是**自述者 `CookingLogicContainer` 是宿主的私有字段** ⇒ 比 2 层遍历上限**深一层** ⇒
"看见宿主、看不见自述者"（症状 = 连对象都没找到，`findCookingLogic` 返回 null）。
**修**：候选集只对**槽位宿主**（`GridDiscovery.Scan.owners()`）**再展开一层字段** + 自校验
（`getCookingSlots()` 必须给出恰好 3 个 `Slot`）；**不做全局加深**（`ServerLevel` 那类世界对象会把遍历炸开）。
输入/燃料/输出仍由 `mayPlace` 行为判定。

**动作路径已补证**（为什么 `64/65/66` 点得动）：上游 `doClick` 只丢弃 `slotId >= getTotalSlotsNumber()`
（= `menu.slots.size()` + **`upgradeSlots.size()`**），其余经**重写的 `getSlot(int)`** 解析到 `upgradeSlots`；
与 C 能点 `64..73` 真合成是同一机制。

**能力已在客户端跑通**（19:39:55–19:40:05）：`provision_verified=true`（`by=ownerDeclaration(getCookingSlots)`
`assignBy=mayPlaceProbe input=#64 fuel=#65 output=#66`）、`input_and_fuel_placed=true`、`smelted=true product+1`、
`input_consumed=true input-1`、`no_half_products=true` ⇒ 沙子→玻璃真的烧成了，炉内不留东西。

**第二轮发现的新缺陷（夹具，与能力无关）**：`cleanupFurnace()` 不换相位就交接给跨 tick 的 `deprovisionAndFinish()`
⇒ 每 tick 把刚开的菜单关掉 ⇒ **无限开关箱子**、SUMMARY 从不打印。修：独立 `DEPROVISION` 相位 + cleanup 只走一次 +
菜单守卫 + `waitSmelt` 的发现器降到每 40 tick（原先刷 383 行）。

**本轮要做的复测（jar `待同步` 已进客户端 mods）**：
1. `alice:craft_cooking_check`（零参数物品/命令）⇒ 期望 `SUMMARY … discover=OK input=#64 fuel=#65 output=#66
   by=ownerDeclaration(getCookingSlots) assignBy=mayPlaceProbe … provision_verified=true smelted=true
   no_half_products=true no_block_writes=true verdict=PASS`；
2. `alice:regression_battery`（CORE）⇒ 期望 `(24/24) → PASS`。
3. 若第 1 步仍红：**先看 `[FurnaceDiag]` 里的 `slotOwners=` / `owner=… nested=[…] cookingSlots=3 progress=true`**
   （新加的诊断行），再看是"没认出来"还是"认出来但点击/放料没生效"——**这两种要分开判**。

## 3b. A5（决策层接线：`GoalAction.Craft`）—— 已实施，待客户端复测（jar `cd3a6816…`）

**做了什么**：词汇表加 `craft`（**只能在候选菜单的可做清单里选**；站点做不了/越界都在解析层拒绝并回读）；
候选菜单加 `craftable` 清单（**只读**配方扫描 + 站点事实，有界且如实标 `truncated`）；
执行走唯一入口 `JobRequest.CRAFT` → `job/craft/CraftJob`（开**玩家选中的**站点 → 按需装配 → 网格合成/3 格烧炼 →
**只看世界事实判成功** → 失败清场；**不自动拆回**升级、**不发料**）。

**实测（20:07–20:13）= 确定性路径全绿 ✅**：
1. `alice:craft_goal_check` **PASS**（`menu_craftable_total=27`、越界 `Refused(not_in_menu:…)`、
   站点做不了 `Refused(station_cannot:…)`、`job_terminal=DONE`、`planks_delta=-4 product_count=1`、`durationTicks=8`）；
2. CORE 电池 **`(25/25) ticks=2737 → PASS`**（含 `craft_goal=PASS`）。

**LLM 路径已验证 ✅**（20:39，jar `1c441fc9…`）：`/alice instruct 用你词汇表里的 craft 动作做一个工作台` ⇒
`mode=directed` → `directed_result raw={"action":"craft","item":"minecraft:crafting_table","count":1}` →
`execute action=craft ok=true` → `[CraftJob] … 世界事实 product 0→1 ⇒ 达成`。
⇒ **阶段 3-A（A1–A5）全部收口**；下一阶段候选见 §4 待办。

## 3c. 阶段 3-B（模组机器适配，实验对象 Mekanism）—— **S1 查询层接线已完成，待客户端验证**

**协议**：`docs/MOD_ADAPTER_PROTOCOL.md`（六步流水线；只读先于执行；"读不懂多少"始终可见；
进通用骨架须满足"上游自述/两上游共享/纯形态可自校验"）。**S0 事实表**：`docs/MEKANISM_FACTS.md`。

**已完成（离线）**：S0 类型事实表（26 类型 / 1171 条）；S1 读法两侧定稿
（`getInput().getRepresentations()` / `getOutputDefinition()`，自校验）；`MachineRecipeFacts`（只读适配器）；
`RecipeQuery` 新增 **`MACHINE_ROUTE`**（有出处的机器路线：机器类型 + 输入 + 输出）；
`CraftJob` 对 `MACHINE_ROUTE` **如实拒绝**（`not_executable:<机器>`）。

**本轮要测（一次右键，零参数）**：`/give @s alice:machine_probe` → 右键。
期望日志/聊天栏：`[MachineProbe] SUMMARY … input_readable=N query_probed=3 query_machine_route=3 … verdict=PASS`
以及 `[MachineProbe] query item=… verdict=MACHINE_ROUTE route=…`（**关键判据**：
查询层对机器产出给出 `MACHINE_ROUTE` 而不是 `MACHINE_RECIPE_UNSUPPORTED`）。

**之后**：探针回收（S5）或按 D-197 转电池步 → 进 **S2（机器站点只读发现）**。

## 4. 待办队列

1. **复跑一次 CORE**（回退瘦身后期望 `(25/25) → PASS`）；之后再按 D-201 附注一"先自证前提、再逐条撤"。
2. **阶段 3-B / S0+S1**：Mekanism 类型事实表 + 查询层机器路线 + 只读探针（`docs/MOD_ADAPTER_PROTOCOL.md` §5）。
2. 已登记未做：菜单型站点/背包型站点（精妙背包）复用同一套；Refined Storage 兼容（用户暂缓）。

## 5. 关键入口与环境（复现用）

- 客户端：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`、截图 `screenshots/`）。
- 同步：`./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`；
  镜像：`./tools/mirror-windows-workspace.sh`；道具资源自检：`bash tools/check-item-models.sh`。
- 场景函数（客户端数据包 `alice_test`）：`craft_table_course`、`craft_station_course`、`craft_tab_course`（精妙容器）、
  `furnace_course`（原版熔炉）、`craft_tab_snapshot`（打印容器 NBT 供差分）。
- 零参数入口：`alice:craft_check|craft_action_check|craft_table_check|craft_station_check|craft_grid_probe|
  craft_station_provision_check|craft_station_craft_check|craft_furnace_check|craft_cooking_check|craft_goal_check|regression_battery|bot_report`。
- 命令：`/alice craft station <auto|inventory|table|upgradetab|cookingtab>`、`/alice battery core|full|list`。
- 电池：**CORE = BASELINE 13 + MAIN 12 = 25 项**（FULL 35；**瘦身已回退**，见 D-201 附注一）；配置唯一入口 `RegressionBatteryTask.CURATION`。
- 模组（客户端 mods/）：alice、JEI、OreExcavation、JEI-pinyin、WorldEdit、create、extendedcrafting、cucumber、
  mekanism、thermal_*、**精妙存储 1.4.86.2131 + 精妙核心 1.5.1.2335 + 精妙背包 3.26.3.2157**、RefinedStorage 1.12.4。
- 本轮最后同步的 jar：`8497ec11c573ccbde9679546856f4cec79b8d2260b5a0a9e1bf530f456f663b0`（源码镜像 + 运行工件均已同步）。

## 6. 纪律提醒（别再踩）

- `menu.slots` **不是**菜单里所有槽位（上游自管槽位只设 `slot.index`）⇒ 守卫用"**发现出来的槽位集合**"，不用 `menu.slots.size()`。
  但上游 `doClick` 只丢弃 `slotId >= menu.slots.size() + upgradeSlots.size()`，其余经重写的 `getSlot(int)` 落到 `upgradeSlots` ⇒ 页签地址**点得动**。
- **"找不到对象"先怀疑遍历深度，别急着加判据特例**：上游常把自述者放进宿主的**私有字段**（比 2 层上限深一层）⇒
  扩容要**只对槽位宿主**展开、并保持自校验；**不做全局加深**。
- **对 vanilla 成员**绝不用字符串反射（生产环境是 SRG 名）⇒ 按**类型**找字段/方法，或编译期调用。
- 夹具**自摆前提**：`setblock` 同种方块会**短路**，方块实体状态（含升级、余焰）会跨场景存活。
- 拿升级右键容器 = **物品自己装进去、GUI 不开**（夹具开菜单前先把主手换空）。
- 判成功**一律看世界事实**（产物/材料/清空），不信原语或上游的自述；测出来的东西要**记进 SUMMARY**。
- 失败**也要清场**（回滚 + 复位）；熔炉用完要**熄灭**（重建方块）。
- **A5 起"合成只能从清单里选"**：`craft` 的 `item` 必须在候选菜单 `craftable` 里；`JobLauncher` 对 `CRAFT` **不发料**；
  `CraftJob` **不自动拆回**升级（装配独立成层）。
