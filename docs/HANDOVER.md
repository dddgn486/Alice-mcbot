# 交接文档（HANDOVER）—— 2026-09-13 会话末尾

> 用途：本会话上下文即将用尽；**下一轮从本文开始读**，即可零损耗接着干。
> 权威状态以仓库文档为准：`docs/AI_PROJECT_STATE.md`（总览）、`docs/AI_DECISIONS.md`（决策与实测事实）、
> `docs/OPEN_ITEMS_LEDGER.md`（开放项与逐轮实测）、`docs/BATTERY_CURATION.md`（电池分档与维护规则）。

## 1. 主线与目标

- **长期目标**：完全参照 Baritone 搭建寻路内核（差异仅三条：可回收性安全策略 / 多层任务失败向上传递 / 未来并行 Bot 接口）。
- **当前主线（阶段 3-A）**：把"合成/熔炼"接进任务层 —— 已完成 A1–A4b 的大部分；**A4b 卡在最后一步**，之后是 **A5 决策层接线**。
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
| 电池分档 | CORE **`(23/23) ticks=2616 → PASS`**（FULL 33 项 3527 ⇒ 短 26%） |

## 3. 进行中：A4b（菜单型炉子 = 精妙"熔炼升级页签"）**卡住**

**目标**：同一个精妙容器 + `sophisticatedstorage:smelting_upgrade` ⇒ 3 格烹饪槽，能放料/等烧/取产物，用完拆回。

**已实现**：`FurnaceStation` 三条路径（① 同一容器恰好 3 格；② `ContainerData`；③ 可达对象自述 `getCookingSlots()`）、
`mayPlace` 行为探针分配输入/燃料/输出、`CraftStation.COOKING_TAB`（站点=容器+哪种能力）、
夹具复用 `CraftFurnaceCheckTask(upgradeTab=true)`、入口 `alice:craft_cooking_check`、电池 `craft_cooking`（MAIN）。

**卡点（两次失败）**：
1. 第一次：`provision_verified=false FAIL:no_furnace_slots`（"恰好 3 格"不成立）；
2. 第二次（加了路径③后）：**仍然 `no_furnace_slots`**，而且**新日志 `[Furnace] 认出炉子 by=…` 完全没有出现**
   ⇒ **`findCookingLogic(...)` 返回 null**：那个"自述烹饪进度的对象"**根本没被我的可达对象遍历找到**
   （不是找到但格子数不对）。装配本身是成功的（`[Provision] QUICK_MOVE 送出 smelting_upgrade fromSlot=56`）。

**下一步（已把诊断代码装上，jar `bb56d945…`）**：
1. 复测一次 `alice:craft_cooking_check`（会失败，无妨），取 **`[FurnaceDiag]`** 输出：
   - `reachableObjects=N` 与每个 `obj=类名 methods=[…Cook…/…Burn…/…Logic…]` ⇒ 看对象在**哪一层**、**真实方法名**；
   - `slot#地址 槽类/容器类 containerSlot=… mayPlace[coal=… cobble=…]` ⇒ 看那 3 格的真实形态。
2. 同时读用户截图 `screenshots/2026-09-13_18.57.42.png`（熔炼页签的样子）。
3. 依据这两份事实**一次改对判据**（候选方向：沿 `Supplier` 展开一层；或改用上游访问器
   `getSmeltingLogicContainer()` / 直接问 `CookingUpgradeContainer`；或按槽位形态直接认 3 格）。
4. 改完跑 `alice:craft_cooking_check` + CORE 电池（应 24/24）。

## 4. 待办队列

1. **A4b 收口**（见上）。
2. **A5 决策层接线**：`GoalAction.Craft`（词汇表 + 严格解析 + 把"可做路线/站点/缺料"作为**确定性事实**喂候选菜单，LLM 只选）；
   并按裁定接好"任务层失败向上传递"接口。
3. 已登记未做：菜单型站点/背包型站点（精妙背包）复用同一套；Refined Storage 兼容（用户暂缓）。

## 5. 关键入口与环境（复现用）

- 客户端：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`、截图 `screenshots/`）。
- 同步：`./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`；
  镜像：`./tools/mirror-windows-workspace.sh`；道具资源自检：`bash tools/check-item-models.sh`。
- 场景函数（客户端数据包 `alice_test`）：`craft_table_course`、`craft_station_course`、`craft_tab_course`（精妙容器）、
  `furnace_course`（原版熔炉）、`craft_tab_snapshot`（打印容器 NBT 供差分）。
- 零参数入口：`alice:craft_check|craft_action_check|craft_table_check|craft_station_check|craft_grid_probe|
  craft_station_provision_check|craft_station_craft_check|craft_furnace_check|craft_cooking_check|regression_battery|bot_report`。
- 命令：`/alice craft station <auto|inventory|table|upgradetab|cookingtab>`、`/alice battery core|full|list`。
- 电池：**CORE = BASELINE 13 + MAIN 11 = 24 项**（FULL 34）；配置唯一入口 `RegressionBatteryTask.CURATION`。
- 模组（客户端 mods/）：alice、JEI、OreExcavation、JEI-pinyin、WorldEdit、create、extendedcrafting、cucumber、
  mekanism、thermal_*、**精妙存储 1.4.86.2131 + 精妙核心 1.5.1.2335 + 精妙背包 3.26.3.2157**、RefinedStorage 1.12.4。
- 本轮最后同步的 jar：`bb56d945a261787d91b52e64689f2695aa10109efaf863689c0977cd03ca838d`。

## 6. 纪律提醒（别再踩）

- `menu.slots` **不是**菜单里所有槽位（上游自管槽位只设 `slot.index`）⇒ 守卫用"**发现出来的槽位集合**"，不用 `menu.slots.size()`。
- **对 vanilla 成员**绝不用字符串反射（生产环境是 SRG 名）⇒ 按**类型**找字段/方法，或编译期调用。
- 夹具**自摆前提**：`setblock` 同种方块会**短路**，方块实体状态（含升级、余焰）会跨场景存活。
- 拿升级右键容器 = **物品自己装进去、GUI 不开**（夹具开菜单前先把主手换空）。
- 判成功**一律看世界事实**（产物/材料/清空），不信原语或上游的自述；测出来的东西要**记进 SUMMARY**。
- 失败**也要清场**（回滚 + 复位）；熔炉用完要**熄灭**（重建方块）。
