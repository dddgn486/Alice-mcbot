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

## 2. 当前归属表（38 项 → CORE 28 项）

> 2026-09-14 校正：本节此前写「34 项 → CORE 24 项」**已过期**（实际 = BASELINE 13 + MAIN 14 + EXTRA 10 = 37，CORE = 13+14 = 27，与客户端实测 `(27/27)` 一致）。
> 加入 `write_policy`（BASELINE）后为 **38 / CORE 28**。


### BASELINE（14）
`pathing`（移动内核聚合 + 覆盖断言）、`write_budget`（写入预算/授权闸门）、`mine_regression`（挖掘闭环 11 用例）、
`mine_job`、`lumber_job`（两个生产 Job 的最小闭环）、`transfer`（L2 容器传输——破坏性最强）、
`clear_guard` / `clear_retry` / `scaffold`（三条破坏性路径的守卫与建拆同权）、`partial_search`（`SEARCH_LIMIT ≠ UNREACHABLE`）、
`capability_gate`（闸门）、`tool_supply`（不凭空变工具）、`recoverability`（可回收性等级）、
`write_policy`（D-207 ①：写入集中策略表——表完整性 + **越权必须被拒**的负例 + 未登记 requester 留痕=0）

### MAIN（14）—— 阶段 3-A（回退瘦身，保持完整）+ 阶段 3-B / S1+S2（机器只读）
`craft_check`、`craft_action`、`craft_table`、`craft_station`、`craft_probe_inventory`、`craft_probe_table`、
`craft_probe_upgradetab`、`craft_station_provision`、`craft_station_craft`、`craft_furnace`、`craft_cooking`、`craft_goal`、
`machine_route`（S1：机器配方**只读**——问上游自述读输入/输出 + 查询层给 `MACHINE_ROUTE`；模组不在 ⇒ SKIP）、
`machine_station`（S2：机器**站点**只读——找机器 → 开菜单 → 读槽位表/`ContainerData`/上游进度方法名；机器不在 ⇒ SKIP）

> **为什么退回来了**：2026-09-13 实测——把其中 8 项移出 CORE 后，`craft_furnace`/`craft_cooking`/`transfer`
> **可复现地变红**（重启客户端后仍红），而它们在 FULL（35 项）里**全绿** ⇒ 撤走的是它们的**隐含前置/清场**。
> **瘦身的前提**是"每个夹具自己显式自证前提、顺序无关"；该前提未落地前不允许再减 CORE 项。
> 恢复路径：先做"显式自证前提"（菜单身份/位置/方块实体状态/账本与归因时间窗）⇒ 再**逐条**撤，**每条复跑一次**。

### EXTRA（10）
`lumber_failure`、`region_maintain`（区域常驻 Job，耗时）、`decision_contract`、`decision_trace`、`llm_contract`、
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
