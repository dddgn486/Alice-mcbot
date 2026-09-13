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

## 2. 当前归属表（33 项 → CORE 23 项）

### BASELINE（13）
`pathing`（移动内核聚合 + 覆盖断言）、`write_budget`（写入预算/授权闸门）、`mine_regression`（挖掘闭环 11 用例）、
`mine_job`、`lumber_job`（两个生产 Job 的最小闭环）、`transfer`（L2 容器传输——破坏性最强）、
`clear_guard` / `clear_retry` / `scaffold`（三条破坏性路径的守卫与建拆同权）、`partial_search`（`SEARCH_LIMIT ≠ UNREACHABLE`）、
`capability_gate`（闸门）、`tool_supply`（不凭空变工具）、`recoverability`（可回收性等级）

### MAIN（10）—— 阶段 3-A 工作站 + 熔炉
`craft_check`（A1 只读查询）、`craft_action`（A2 随身 2×2）、`craft_table`（A3 工作台 3×3，零写入）、
`craft_station`（A3b 自放工作站）、`craft_probe_inventory` / `craft_probe_table`（发现器回归：硬断言 2×2 / 3×3）、
`craft_probe_upgradetab`（模组站点探测）、`craft_station_provision`（L2 装配装/拆）、
`craft_station_craft`（C 模组站点真合成）、`craft_furnace`（A4 熔炉）

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
