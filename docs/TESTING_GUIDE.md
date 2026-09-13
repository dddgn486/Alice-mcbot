# Alice 测试流程指南（一键优先）

> 目的：真人测试尽量**一次动作拿到全部信息**。禁止要求输入坐标或长参数。
> 更新时间：2026-09-08

---

## 0. 一次性准备

1. 启动固定客户端：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`（确认 mod 为最新工件）
2. 生成 bot（若当前维度没有）：`/alice spawn`
3. 领取测试物品：
   ```
   /give @s alice:pathing_battery
   /give @s alice:pathing_planner
   ```

**场景由数据包一键生成**（无需自己搭地形）：
```
/function alice_test:pathing_course
```
它会自动生成：上升台阶（东侧 1 格高）、下降坑（北侧 1 格深）、2 段下降链，
把玩家放到起点并发放寻路自检电池。
数据包源文件在仓库 `tools/test-scenes/alice_test/`（同时已部署到测试世界）。

**你不需要自己整理日志**：说「测完了」，AI 会主动读 `latest.log` / `debug.log` 并按前缀分析。

---

## 1. 通用原则

- **一键优先**：能用物品右键就不打命令；能用单词命令就不打坐标。
- **一次只测一项**，等聊天出现终态消息再进下一项。
- **需要"看"的结论必须由真人确认**：是否跳跃、是否回冲、是否卡边缘、是否抖动、有无紫黑贴图。
  日志只能证明"位置对了"，证明不了观感——这类问题 AI 会主动问你。
- **失败也要测**：把 bot 指向不可达处，确认它诚实失败。

---

## 1.5 测试夹具设计原则（新功能照此办理）

后续每加一个功能，测试夹具都按这套模式做，避免"测试太繁琐"重演：

| 原则 | 做法 | 本仓库参考 |
|---|---|---|
| 一键场景 | 需要特定地形就用数据包函数生成，不让用户手搭 | `/function alice_test:pathing_course`、`tools/test-scenes/` |
| 场景孤立长方体 | 定义长方体区域，边界外至少一圈（含上下）为**空气**，不得与其他场景/地形相连；**不要求封闭，开阔场景即可**；必要时才加围墙/底部隔离地板 | `pathing_course_reset` 先整体清空再建造 |
| 一键测试 | 多个检查项合并为一个自检任务，一次右键跑完 | `alice:pathing_battery` → `PathingBatteryTask` |
| 机器可判读 | 末尾输出 `SUMMARY key=VALUE ...`，失败码明确 | `[R3 Battery] SUMMARY ...` |
| 各项独立 | 每个子项开始前 bot 复位到统一起点（`reset_to_hub`） | `PathingBatteryTask.resetToHub()` |
| 缺地形不算失败 | 不支持项记 `SKIP` | 同上 |
| 候选同源 | 夹具用规划器的 `MovementProvider` 检测可测项 | `SurfaceMovementProvider` |
| 场景入库 | 数据包与夹具源码进仓库，不依赖客户端临时文件 | `tools/test-scenes/alice_test/` |
| 视觉结论真人确认 | 是否跳跃/回冲/卡边缘必须问用户 | §1 通用原则 |
| **入口必须写清"场景 + 复位"** | 不自带复位的入口，说明里**必须**写出先跑哪个场景函数；只写物品名视为不完整说明 | 见下表 |

### 1.6 测试入口与复位方式（照此写说明，别再漏）

**自带复位**（右键即跑，每次都会重放场景——可连续点）：

| 测试项 | 物品（零参数右键） | 复位的场景函数（代码内部调用） |
|---|---|---|
| 寻路串联回归 | `alice:pathing_regression` | 每个子场景的 `<scene>_terrain` |
| 挖掘串联回归 | `alice:mine_regression` | 每例的 `<case>_terrain` |
| 脚手架生命周期 | `alice:scaffold_check` | `scaffold_course_terrain` |
| 容器绕行自检 | `alice:clear_guard_check` | `clear_guard_terrain` |
| 写入预算自检 | `alice:write_budget_check` | `break_course_terrain` |
| 伐木失败语义 | `alice:lumber_failure_check` | `lumber_course_terrain` + `lumber_course_trees` |
| 清障换候选（R2） | `alice:clear_retry_check` | 运行时复用 `break_course_terrain` + 脚本搭石头壳 |
| **串联回归电池（★推荐）** | `alice:regression_battery` | 内部逐项复位（各步自带的 + lumber/ore 场景函数） |

**不自带复位**（物品只负责"传送 + 发料"，**必须先跑场景函数**，否则会消耗上一轮剩下的矿/树）：

| 测试项 | 先跑（一键，含复位 + 传送 + 发物品） | 再右键 |
|---|---|---|
| 挖掘 Job | `/function alice_test:ore_course` | `alice:mine_job` |
| 伐木 Job | `/function alice_test:lumber_course` | `alice:lumber_job` |
| 策略对比 | `/function alice_test:lumber_course` | `alice:lumber_policy_check` |

> 规律：**场景函数（`*_course`）负责复位**；物品负责"传送 + 发料 + 启动"。
> 所以凡是"物品不带场景函数"的入口，说明里必须写清先跑哪条函数。

### 1.7 串联回归清单（改到生产任务后的必跑集）

**★ 首选：一条右键跑完全部 26 项**

```
/give @s alice:regression_battery          # 一次性
右键 arbitrary 方块                          # 等约 3~5 分钟
```
判据：`[Regression] SUMMARY clear_retry=… mine_regression=… lumber_job=… decision_contract=… permission_gate=…
pickup_gate=… collect_job=… recipes_dump=… event_thresholds=… pathing=… K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=N) (26/26) → PASS`，
逐项失败不中断（一趟看全）。期间别启动其它任务、人站远一点别捡掉落物。

> `K4=…` 是**谓词一致性自断言**（K-4 / D-167）：本次电池里"规划期宣布 REACHED 但目标格不可站"
> 的矛盾计数必须为 0；`VIOLATION(...)` 会直接把电池判 FAIL，并伴随 `[K4] …` 告警行。
> 累计值也能随时用 `alice:bot_report` 看（"目标准入（K-4 累计）"行；`无异常计数` = 从未发生）。

> **掉落物判据只数"本用例新增"**（D-168）：挖掘回归的 `dropsLeft` 已改为"取基线做增量"，
> 场景函数也补了 `kill @e[type=item,…]` ⇒ **世界里有历史残留掉落物不会再导致假失败**；
> 残留单独显示为 `dropsLeft=0(另有残留1件不计入)`。所以**不必**再手动清掉落物。

> 决策层 6 步中，`pickup_gate` / `collect_job` / `event_thresholds` 另有**单跑物品**
> （`alice:pickup_gate_check` / `alice:collect_job` / `alice:event_threshold_check`）；
> `decision_contract` / `permission_gate` / `recipes_dump` **只有电池入口** —— 它们验的是契约与阈值，不需要手搭场景；
> 排查单项时改看 `[DecisionContract]` / `[PermissionContract]` / `[PickupGate]` / `[CollectJob]` /
> `[RecipeDump]` / `[EventThreshold]` 前缀的行（每项自己的 `SUMMARY key=VALUE` 都在电池里）。

下面是**逐项手工版**（排查单项时用；每项都自带复位，两个 Job 需要先跑场景函数）：

凡改动生产任务（`MineTask` / `RestoreScopeTask` / Job 层）后，按此顺序**一次跑完**再读日志对判据：

| # | 入口 | 复位 | 期望摘要 | 日志判据（D-119 工具语义） |
|---|---|---|---|---|
| 1 | 右键 `alice:mine_regression` | 自带 | `[MineRegression] SUMMARY … no_tool_refuses=PASS ticks=…`（**11/11**） | **恰好 1 条** `[MineTask] no_suitable_tool target=23, 64, 140 …`（第 11 例负例的**期望输出**） |
| 2 | `/function alice_test:lumber_course` → 右键 `alice:lumber_job` | 手动 | `DONE quota_met trees 4/4 logs 19/19 … scaffoldLeft=0` | 零 `no_suitable_tool`、零 `tool_in_main_inventory`、零 `no_tool`；`[Restore] … remaining=0 → DONE` |
| 3 | 右键 `alice:scaffold_check` | 自带 | `[Scaffold] SUMMARY … PASS` | 同 2 |
| 4 | 右键 `alice:restore_check` | 需账本有**待恢复项** | `[Restore] SUMMARY … remaining=0 → DONE` | 同 2 |
| 5 | 右键 `alice:clear_guard_check` | 自带 | `predicate_refuses=true chest_intact=true target_removed=true → PASS` | 同 2 |
| 6 | 右键 `alice:write_budget_check` | 自带 | `refusedPlaces=0 …` | 同 2 |
| 7 | 右键 `alice:lumber_failure_check` | 自带 | `[LumberFailCheck] SUMMARY … 5/5 PASS` | 同 2 |
| 8 | `/function alice_test:ore_course` → 右键 `alice:mine_job` | 手动 | `DONE …` | 同 2 |
| 9 | 右键 `alice:clear_retry_check` | 自带 | `[ClearRetry] SUMMARY retry=PASS attempts=… clear_then_mine=PASS cleared=… sub_profile=PASS → PASS` | **期望**若干条 `[MineTask] clear_skip …`（换候选）与 `no_suitable_tool`（相位①故意无镐） |
| 10 | 右键 `alice:pathing_regression` | 自带 | `16/16` | —（不涉挖矿，仅防连带回归） |

**决策层 6 步（D-149/D-150，电池内自动跑；下面是各自的手工对照入口）**

| # | 入口 | 复位 | 期望摘要 | 日志判据 |
|---|---|---|---|---|
| D1 | 电池 `decision_contract` | 自带 | `snapshot=… menu=…`（契约自检） | 无 `refused` 之外的异常；`GoalAction` 越界被钳制 |
| D2 | 电池 `permission_gate` / 右键 `alice:permission_demo` | 自带 | 四档行为 + 超时=默认拒绝 | `[Permission] … pending/answered` |
| D3 | 电池 `pickup_gate` / 右键 `alice:pickup_gate_check` | 自带 | `A 捡到 / B 被拦（东西留地上）` | A 无 blocked；B **恰好一条** `[Pickup] blocked`（600 tick 冷却，不是 584 条） |
| D4 | 电池 `collect_job` / 右键 `alice:collect_job` | 自带 | `[CollectJob] … collected=N` | 我方掉落物登记后放行 |
| D5 | 电池 `recipes_dump` / 命令 `/alice recipes` | 自带 | 导出文件写出（行数 > 0） | `[RecipeDump] written=… recipes=…` |
| D13 | 电池 `transfer` / 右键 `alice:transfer_check`（R2+R3+L1+L2，约 2 秒；`end_to_end` 默认走**菜单路线**） | 场景函数 `alice_test:transfer_check_terrain`（夹具内部自动调用） | `fixture=PASS end_to_end=PASS selection=PASS selector_events=PASS command_parse=PASS verdict=PASS` | `[Transfer] SUMMARY …`；**`end_to_end`** 跑真实 `TransferTask`（走位→触及校验→容器预算→两段写入），日志里应能看到 `containers=N/M` |
| D22 | **阶段 3-A / A4b 熔炼页签（菜单型炉子）**（D-198）：`/function alice_test:craft_tab_course` → 右键 `alice:craft_cooking_check`；电池步 `craft_cooking` | 场景 `alice_test:craft_tab_course`（精妙容器） | `[CraftFurnaceCheck] SUMMARY provision_verified=true smelted=true product_produced… input_consumed=true no_half_products=true upgrade_returned=true verdict=PASS` | 与 A4 同一个夹具（`upgradeTab=true`）：**装熔炼升级**→认炉子三格（走"**上游自述**"证据路径，精妙没有 `ContainerData`）→放沙子+煤→等烧→取玻璃→**拆回升级**；模组不在 ⇒ `mod_present=false`（电池 SKIP） |
| D21 | **回归电池分档**（D-197）：`/alice battery core`（默认，必要基础+当前主线）/ `full`（全量）/ `list`（看归属表）；物品 `alice:regression_battery` = CORE | —— | SUMMARY 带 `PROFILE=core baseline=13 main=10 extra_skipped=10 (23/23) ticks=… → PASS` | 归属表在 `RegressionBatteryTask.CURATION`，说明书 `docs/BATTERY_CURATION.md`；**漏登记 / 文档与代码不一致 ⇒ 判红**；每加场景或换主线都要同步更新（AI 负责） |
| D20 | **阶段 3-A / L2 工作站装配（装升级→验证→取回）**（D-194）：`/function alice_test:craft_tab_course` → 右键 `alice:craft_station_provision_check`；电池步 `craft_station_provision` | 场景函数 `alice_test:craft_tab_course`（**不再给玩家发升级**） | `[ProvisionCheck] SUMMARY start_premise=true mod_present=true station_found=true fixture_gave_upgrade=true premise_not_provisioned=true item_moved_into_container=true **provision_verified=true** deprovision_verified=true item_returned=true no_block_writes=true verdict=PASS` | `provision_verified` = **关掉再开菜单后**通用发现器认出 ≥3×3（用**能力**验证装配，不猜槽位语义）；`item_moved_into_container` / `item_returned` = 物品前后数量（真搬了）；装配失败会**回滚**并记 `rollback_clean`；模组未装 ⇒ `mod_present=false`（电池记 SKIP） |
| D19 | **阶段 3-A / S1 合成工作站可切换 + 网格探针**（D-192，**只读**；**已入电池**：`craft_probe_inventory` / `craft_probe_table` / `craft_probe_upgradetab`）：`/function alice_test:craft_tab_course` → `/alice craft station`（看候选）/ `/alice craft station <auto\|inventory\|table\|upgradetab>`（切换）→ `/give @s alice:craft_grid_probe` 右键 | 场景函数 `alice_test:craft_tab_course`（平台 + 精妙箱子 + 一颗合成升级给你手动装） | `[CraftGridProbe] SUMMARY selected=… station=… menu=… menu_slots=… discover=… grid_found=… grid_addressable_without_tab=… inactive_slots=… read_only=true verdict=PASS` | 判据全在日志/聊天：`discover=OK grid=3x3 slots=[…] result=…` = 通用发现器认出了网格（**零模组专属代码**）；`grid_addressable_without_tab=true` = 没发任何页签包就能寻址 ⇒ 按裁定"没区别就不用管"；`slots(…): i:Slot/容器@x,y` 是全槽位事实表（精妙升级页签的 9 格会是 **-100,-100**）；`read_only=true` = 账本无我方临时方块。`/function alice_test:craft_tab_snapshot` 可打印容器 NBT 供前后差分 |
| D18 | **阶段 3-A / A3b 自放工作站（放→用→拆）**（首轮实测：放成了圆石 ⇒ 已修，见 D-190 附注一）：先 `/function alice_test:craft_station_course`（平台、**故意没有台子**）→ 右键 `alice:craft_station_check`（约 3~8 秒）；电池步 `craft_station`（自带场景） | 场景函数 `alice_test:craft_station_course` | `start_premise=PASS no_station_premise=PASS holding_station_item=PASS station_placed=PASS write_accounted=PASS placed_table_craft=PASS teardown_clean=PASS verdict=PASS` | `[CraftStationCheck] SUMMARY …`；`write_accounted` = 放下的那格记进账本 **TEMP**；`teardown_clean` = 拆完**方块回到空气 + 账本 pending=0**（建拆同权）；失败也会走清理（多一条 `cleanup_on_failure`，正常不出现）；**游戏里应看到**：bot 身旁多出一台工作台 → 合成 → 台子被拆掉（日志 `[Restore]`） |
| D17 | **阶段 3-A / A3 现成工作台 3×3**（**自带传送**：夹具自己把 bot 放到场景起点，无需先跑场景函数也能单独右键）：先 `/function alice_test:craft_table_course` → 右键 `alice:craft_table_check`（约 3 秒）；电池步 `craft_table`（自带场景） | 场景函数 `alice_test:craft_table_course` | `start_premise=PASS table_found=PASS walked_to_table=PASS table_in_reach=PASS menu_is_crafting_menu=PASS crafted_furnace=PASS no_world_write=PASS no_table_honest=PASS verdict=PASS` | `[CraftTableCheck] SUMMARY …`；`no_world_write` = 账本无我方临时方块（A3 **只"用现成"、不放置**）；`no_table_honest` = 把台挪走后**如实失败**、不绕路不放置 |
| D16 | **阶段 3-A / A2 随身 2×2 合成**：右键 `alice:craft_action_check`（约 1 秒）；电池步 `craft_action` | 自带（夹具自己重置背包并发料） | `menu_is_inventory=PASS craft_sticks=PASS craft_table=PASS craft_multi=PASS query_refuses_when_short=PASS action_cleanup_on_partial=PASS grid_clean=PASS verdict=PASS` | `[CraftActionCheck] SUMMARY …`；判据看**净变化**（产物 +N / 材料 −M）；`missing_ingredient_honest` 同时断言**背包逐槽未变**（不许凭空给、不许吞材料）；`grid_clean` = 网格不留半成品 |
| D15 | **阶段 3-A / A1 只读配方查询**：右键 `alice:craft_check`（约 1 秒）；电池步 `craft_check` | 自带（夹具自己重置背包） | `craftable_sticks=PASS missing_ingredients=PASS needs_table=PASS machine_only_vanilla=PASS no_recipe=PASS machine_only=PASS/SKIP read_only=PASS verdict=PASS` | `[CraftCheck] SUMMARY …`；`read_only` 是**硬断言**：查询前后背包逐槽一致（只读原语的证据）；`machine_only` 未装 Mekanism 时如实记 `SKIP` |
| D14 | **K-3 安全点停止**（D-166/D-169）：右键 `alice:k3_stop_check`（DEFER）/ **Shift+右键**（FORCED）；**不在电池里**（会停掉顶层任务=电池自己） | 自带（把 bot 升空） | DEFER：`停止请求延后到安全点：task=K3StopCheckTask` → 请求后仍被 tick → `已到安全点，执行延后的停止`；FORCED：`任务在不安全时刻被强制停止` | `bot_report` → `安全点取消：deferred=1`（DEFER）/ `forcedUnsafe=1`（FORCED）；前提失败报 `fixture_not_airborne`，被嵌套时报 `fixture_not_top_level` |
| D12 | 电池 `partial_search` / 右键 `alice:partial_search_check`（K-1，约 1 秒，纯规划） | 自带 | `partial_with_prefix=PASS same_goal_reachable_with_budget=PASS no_prefix_for_real_failures=PASS verdict=PASS` | `[PartialSearch] SUMMARY …` |
| D11 | 电池 `capability_gate` / 右键 `alice:capability_gate_check`（基-8 + K-5，约 1 秒，纯逻辑） | 自带 | `pure_traversal_allowed=PASS capability_unauthorized=PASS zone_protected=PASS no_tool=PASS no_throwaway=PASS no_place_budget=PASS declarations=PASS foreign_break_attribution=PASS safe_cancel_wiring=PASS container_write_record=PASS session_status_no_dead_value=PASS verdict=PASS` | `[CapabilityGate] SUMMARY …`；`session_status_no_dead_value` 是 **K-5** 断言：分类表 + 覆盖率（除 RUNNING/COMPLETED 外每个状态都必须有生产者） |
| D10 | 电池 `tool_supply` / 右键 `alice:tool_supply_check`（基-9，约 2 秒） | 自带（**会临时改写 bot 背包并在收尾复原**） | `tool_swap=PASS worn_no_spare=PASS no_tool_no_conjure=PASS verdict=PASS` | `[ToolSupply] SUMMARY …`；`no_tool_no_conjure` 是**负例**：不许凭空变出工具 |
| D9 | 电池 `llm_contract` / 右键 `alice:llm_contract_check`（基-5，约 1 秒） | 自带 | `job_failure_reports=PASS product_filter_target=PASS product_filter_default=PASS refusal_readback=PASS verdict=PASS` | `[LlmContract] SUMMARY …`；`refusal_readback` 同时验"进 prompt"与"接受后清掉" |
| D8 | 电池 `decision_trace` / 右键 `alice:decision_trace_check`（基-4，约 1 秒） | 自带 | `trace_written=PASS trace_memory_tail=PASS state_nbt_roundtrip=PASS restart_semantics=PASS verdict=PASS` | `[DecisionTrace] SUMMARY …`；同时可看 `<config>/alice-decisions.jsonl` 是否新增一行 |
| D7 | 电池 `recoverability` / 右键 `alice:recoverability_check`（基-1，纯计算，约 1 秒） | 自带 | `table_nondeterministic=PASS per_type=PASS guard_is_live=PASS fall_with_fact_accepted=PASS fall_without_fact_refused=PASS policy_table=PASS verdict=PASS` | `[Recover] SUMMARY …` + `[Recover] 规则表 …` + `[Recover] 策略表 …`；两个负例：`required>evaluated` 必须抛、**不带返回守卫事实的 FALL 边必须被拒** |
| D6 | 电池 `event_thresholds` / 右键 `alice:event_threshold_check`（约 25 秒，可盯着看竖井段） | 自带 | `tool_low_once=PASS tool_rearm=PASS stuck_once=PASS stuck_no_spam=PASS` | 每段**恰好一条** `[Events] TOOL_LOW` / `[Events] STUCK`；前提失败会直接报 `fixture_*` |

> 判据含义：`no_suitable_tool` = 生产任务**拒绝**在没有正确工具时挖"必须正确工具才掉落"的方块（D-119）；
> `tool_in_main_inventory` / `no_tool` = 夹具**漏发或发错位置**（工具落 9..35 就永远选不到 —— D-089 斧子、
> D-099 一次性方块，同一病灶第三次同形）。
> **不要**再按"破坏速度 ≤ 1"判断夹具漏料：树叶这类东西本来就没有更快工具（2026-09-12 实测，8/8 全是清障树叶噪声）。

### 1.11 决策层（② 第 2 步，D-135）——`alice:goal_director` + 出网路径

```
# 现在**不需要中继**（直连实测可用）。仅当直连/代理都不通时才起兜底中继：
# python3 tools/llm-relay.py --port 8791       # 只监听 127.0.0.1，key 从 config 读且不打印
# 并在 config/alice-llm.json 里设 "relayUrl": "http://127.0.0.1:8791/chat/completions"
# 验证：curl.exe -s -m 10 -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:8791/chat/completions \
#        -H "Content-Type: application/json" -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"ping"}]}'
#       （期望 200；实测 Windows→WSL 中继 11 ms）

右键 alice:goal_director
期望：[Goal] path_try name=relay … → ok status=200 …ms
      [Goal] llm_dns host=127.0.0.1 → 127.0.0.1
      [Goal] llm_reply id=1 latency=…ms chars=…
      [Goal] decision_action trigger=manual raw={"action":…} → StartJob(…) / Refused(…)
      [Goal] execute action=… ok=…
```
> **路径矩阵**：`api+direct`（默认，实测 2078ms）→ `api+proxy`（3074ms）→ `relay`（仅当配置了 `relayUrl`），取第一条成功的并记住；
> 每条都打 `path_try`。实测某环境里 Minecraft 的 `java.exe` 外网被火绒静默丢弃（curl 却通），
> 此时只有 `relay` 那条能过 —— 用户把 `java.exe` 加进安全软件允许列表后可清空 `relayUrl` 回到直连。

### 1.10 统一 Job 入口 + 终态契约（② 第 1 步，D-134）——一个零参数右键

```
右键 alice:job_launcher                  # 自带 lumber_course 复位 + 传送 + 发料
期望：[Job] launch bot=… kind=LUMBER center=… radius=16 quota=2 maxTicks=3600
      … 正常伐木（pick tree / tree@… 完成）…
      task_execution_terminal kind=LumberJob … terminal=COMPLETED code=done
      task_terminal_reason kind=LumberJob botId=<uuid> terminalReason=quota_met   ← 契约判据
```
> `terminalReason` 就是决策层要的东西：`quota_met`（达成）/ `inventory_full`（背包满提前收工）/
> `idle_no_work` / `no_reachable_candidate` 等 —— 以前这些只有日志里有，现在进终态记录。

### 1.9 安全底座小批次（S-1–S-4，D-132）——三个零参数右键

| 入口 | 场景 | 期望（一行判据） |
|---|---|---|
| 右键 `alice:survival_exit_check` | `alice_test:survival_course`（自动生成） | `维生监测 hazard=SUFFOCATING` → `任务因维生危险中断 … survival_suffocating` → `[Survival] 维生中断 ⇒ 逃生出口 refuge=…——启动 SurvivalExitTask` → `task_execution_terminal kind=SurvivalExitTask terminal=COMPLETED`；**肉眼：bot 从压顶那格走出一步** |
| 右键 `alice:chunk_guard_check` | 无（就地无头规划） | `[ChunkGuard] SUMMARY far_goal=GOAL_NOT_LOADED no_sync_load=true PASS near_goal=REACHED PASS border_goal=… skipped_border=true PASS → PASS` |
| 右键 `alice:fluid_mine_check` | `alice_test:fluid_mine_course`（自动生成） | `[FluidMineCheck] SUMMARY plan_refuse=fluid_risk_lava run_refuse=FAILED_fluid_risk_lava no_clear_gain=true control=DONE → PASS`，且日志里**没有** `tryClear`/加高相位 |

> S-1 用的是"**头顶压石头**（窒息）"而不是岩浆：本条目验的是**否决之后有没有去向**；
> 岩浆里能否爬出来取决于流体物理（内核不建模岩浆游动），那条登记为未覆盖。

### 1.8 可持续伐木区（J8 / MAINTAIN）——一次跑完三个玩家接口

区域型 Job 的功能已验收（D-130 附注）；这一节专门验**玩家接口**（`stop` / `set` / `idle-stop`），
一次跑完约 1 分钟。区域定义**只有水平范围**（x/z 取自两个角，`baseY` 取较低的那个 Y，竖直自适应）。

```
① 右键 alice:region_lumber                 # 夹具区域（x17..37 z203..231）+ 常驻巡查，等 chopped 在涨
② /alice region stop                       # 显式打断
   期望聊天：[alice] 已停止 RegionLumberJob（region_stop）；账本已闭合（无我方临时方块残留）
             或 …；**账本仍有 N 条我方临时方块未拆**（/alice restore 可清理）
   期望日志：task_execution_terminal … terminal=CANCELLED_BY_USER code=cancelled:region_stop
③ /alice region info                       # 区域**仍在**（打断不丢区域）、autoIdleStop=false
④ 走到一片空地站定（附近没有树），然后：      # 相对坐标，不用算数
   /alice region set ~ ~ ~ ~8 ~ ~8          # 回执里写 "x… z… baseY=… maxH=48（垂直自适应）+ 目标棵数重新推导"
   /alice region info                       # baseline=0（旧区域的 5 已被清掉 ⇒ D-131 修复点）
⑤ /alice region idle-stop true              # 打开"无活即收工"的可选模式（默认关=常驻）
⑥ /alice region start                       # 空区域 + 无苗 + 无欠 ⇒ 约 3 轮巡查后
   期望日志：[Job] maintain region=… viable=0 … deficit=0 …
             [Job] maintain SUMMARY … reason=idle_no_work → DONE
⑦ /alice region idle-stop false             # ★复原默认（常驻）—— idle-stop 是持久化的，忘了这步
                                            #   下次任务会在"无活"时自己收工（看起来像"突然停了"）
```

> **实测（2026-09-12 13:50–13:51）**：`stop` / `set` / `idle-stop true` 三个接口全部通过（D-131 附注二）。
> `idle-stop true` 可以在任务**正在跑**时直接开：下一轮巡查就会 `idle_no_work` 收工（实测 1 s 内），
> 不必先 `stop` 再 `start`。
>
> 注意：`/alice region info|sapling|idle-stop|set` 是**读/写配置**，**不会**打断正在跑的任务；
> 会分配任务的指令（`start`、`mine`、`follow`…）才替换任务（`cancelled:replaced`）；
> **只有 `/alice region stop` 是显式打断**。重划区域在"当前有任务在跑"时**下一次 start 才生效**。

---

## 2. ★ 一键自检（推荐，两次操作覆盖全部）

**第 1 步（建场景，一条命令）**：
```
/function alice_test:pathing_course
```
自动生成标准课程（上升台阶 / 下降坑 / 2 段下降链），把玩家放到起点，并发放电池。

**第 2 步（跑测试，一次右键）**：手持 `alice:pathing_battery`，右键任意方块 → 等约 8 秒。

> **场景专属测试器**：只负责启动测试，**起点由场景固定**（`(0,64,46)`，与 `pathing_course` 一致），
> 与你站在哪里无关。已有 bot 会被传送到固定起点；没有 bot 会自动生成在固定起点。
> 每个子项开始前也会锚回固定起点（日志 `anchor_to_start`），保证各项独立可测。

**它自动跑完**（每项开始前把 bot 放回统一起点，保证各项独立可测；地形不支持的项记 `SKIP`）：

| 项目 | 内容 |
|---|---|
| `plan_flat` | 规划平地路径 |
| `plan_up` | 规划上升路径 |
| `plan_budget` | 小预算远距离规划（验证 `SEARCH_LIMIT` 语义） |
| `traverse` | 执行水平移动 |
| `diagonal` | 执行对角移动 |
| `ascend` | 执行上升（**应看到跳跃**） |
| `descend` | 执行下降 |
| `chain2` | 执行 2 段连续下降（验证 D-027 无回冲） |

**聊天/日志**：
```
[R3 Battery] plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
[R3 Battery] item=traverse seg=0 result=PASS actualFoot=...
[R3 Battery] SUMMARY plan_flat=REACHED(2) plan_up=REACHED(1) plan_budget=SEARCH_LIMIT
             traverse=PASS diagonal=PASS ascend=PASS descend=PASS chain2=PASS
```

**判定**：
- 出现项应为 `PASS`；`SKIP` = 该地形不支持，不算失败
- `plan_*` 状态语义正确：可达=`REACHED`，小预算=`SEARCH_LIMIT`（**不是** `UNREACHABLE`）
- **亲眼确认**：ascend 是否跳跃、chain2 是否还有明显回冲、有无卡边缘

---

## 3. ★ R4 路径会话（规划 → 逐段执行）

**物品**：`alice:pathing_session`（路径会话测试器，场景专属）

**操作**：场景函数已把它发到背包；右键任意方块即可。

**它做什么**：把 bot 锚定到固定起点 `(0,64,46)` → 规划到固定目标 `(0,62,44)`（2 段下降链底部）→ 由 `PathSession` 逐段执行。

**预期日志**：
```
[R4 Session] planned session=... status=REACHED movements=2 cost=2.00 ... from=0,64,46 to=0,62,44
[R4 Session] segment_start index=0/2 type=DESCEND ... tolerance=COLUMN
[R4 Session] segment_done  index=0 type=DESCEND actualFoot=0,63,45
[R4 Session] segment_start index=1/2 type=DESCEND ... tolerance=EXACT
[R4 Session] segment_done  index=1 type=DESCEND actualFoot=0,62,44
[R4 Session] completed session=... segments=2 ticks=... finalFoot=0,62,44
[R4 Session] result session=... status=COMPLETED segments=2/2 ticks=... finalFoot=0,62,44
```

**判定**：`status=COMPLETED segments=2/2`；两段之间不中断、不报 `STALE`/`BLOCKED`。

**失败语义**（R4 新增，供上层任务/LLM 决策）：
`STALE`（起点漂移/世界变化）、`BLOCKED`（目标被阻塞）、`TIMEOUT`（单段超时）、
`INVALID_PRECONDITION`、`MOVEMENT_FAILED`、`CANCELLED`。

---

## 4. 细查入口（一键自检发现问题时再用）

### 4.1 只规划（bot 不动）
`alice:pathing_planner`：
- **右键方块** → 规划到该方块正上方
- **Shift+右键方块** → 规划到该方块本身

聊天给出状态/步数/成本/节点/用时 + 逐步明细。或零参数命令：
```
/alice pathing plan-here      → 规划到你自己脚下
```

### 4.2 单步 Movement（单词方向，无坐标）
```
/alice pathing traverse east
/alice pathing diagonal northeast
/alice pathing ascend east
/alice pathing descend east
```
地形要求：目标必须满足该 Movement 的前置（同高相邻 / 对角相邻 / 高 1 格且头顶留 2 格 / 低 1 格）。

### 4.3 多段链
```
/alice pathing chain east 3
```
地形：从 bot 站立处向东做 3 级标准楼梯（每级下降 1 格、宽 1 格，上方留 2 格）。

---

## 4.5 ★ R5-2 破坏通行（BreakAndTraverse）

**场景**：`/function alice_test:break_course`
生成：固定起点 `(0,64,66)` → 目标 `(7,64,66)`，中间 `x=3` 有 **2 格高石墙**。

**操作**：手持 `alice:pathing_breaker`（函数已发放）右键任意方块。

**预期日志**：
```
[R4 Session] planned ... status=REACHED movements=... （路径含 BREAK_AND_TRAVERSE）
[R4 Session] segment_start ... type=BREAK_AND_TRAVERSE
[BreakAndTraverse] blocked_cleared pos=3,64,66 index=0
[BreakAndTraverse] blocked_cleared pos=3,65,66 index=1
[R4 Session] result ... status=COMPLETED
```

**判定**：
- 规划确实使用 `BREAK_AND_TRAVERSE`（而不是绕路/失败）
- bot **逐步破坏**两个方块（能看到破坏进度/摆动，不是瞬间消失）
- 破坏后走过墙，最终脚位 `(7,64,66)`
- 不允许出现 `BREAK_BLOCK_PROTECTED` / `BREAK_BLOCK_UNBREAKABLE`

**同时回归**（R5-1 原语改造影响面）：跑一次既有挖掘场景 `alice:mining_scene_tester`，
确认 legacy 清障路径（`BreakAndWalkMovement` → 进度破坏）没有回归。

---

## 4.6 ★ R5-3 放置台阶通行（PlaceStepAndTraverse）

**场景**：`/function alice_test:place_course`
生成：起点 `(0,64,66)` → **2 格宽缺口（x=2..3）** → 平台 → **低 2 格的目标 `(7,62,66)`**。

**操作**：手持 `alice:pathing_placer`（函数已发放）右键任意方块。

**预期日志**：
```
[R4 Session] planned ... status=REACHED movements=...
[R4 Session] segment_start ... type=PLACE_STEP_AND_TRAVERSE
[PlaceStepAndTraverse] placed pos=2,63,66 ...
[PlaceStepAndTraverse] placed pos=3,63,66 ...
[PlaceStepAndTraverse] placed pos=7,62,66 ...
[R4 Session] result ... status=COMPLETED finalFoot=7,62,66
```

**判定**：
- bot 在缺口处**放置圆石**（能看到方块出现，不是踩空/穿过去）
- 落差处**先放置台阶再下**（2 格落差被拆成 1 格）
- 最终脚位 `(7,62,66)`，`status=COMPLETED`
- 不允许出现 `PLACE_RESOURCE_UNAVAILABLE` / `PLACE_NO_VALID_FACE`

---

## 4.7 ★ R4 自愈闭环（位置漂移 / 世界变化后自动恢复）

**场景**：`/function alice_test:place_course`（目标 `(8,62,66)`）

**操作**（两件物品都要测）：
1. 手持 `alice:pathing_placer` 右键任意方块 → 正常放置通行（应干净完成）；
2. 手持 `alice:pathing_disturber` 右键任意方块 → **第 30 tick 会被平移 1 格**（模拟被推开），
   观察是否自动恢复（`snipsnap` / `replanned`）。

**预期日志**（自愈生效）：
```
[R4 Session] segment_done ... index=7 type=DESCEND actualFoot=8,62,66
[R4 Session] failed ... code=TRAVERSE_STALE_START index=8        ← 原失败点
[R4 Session] replanned session=... replans=1 movements=2 from=9,62,66 to=7,62,66
[R4 Session] result ... status=COMPLETED ... replans=1
```

**判定**：
- 出现 `replanned`（或 `snipsnap`）后任务**继续并完成**，而不是直接 `STALE` 失败
- `result` 行带 `replans=N`
- 若既不 snipsnap 也不重规划成功，必须**诚实失败**并保留原始失败码

---

## 5. ★ legacy 路径上升兼容（D-030）

**场景**：一条命令建好
```
/function alice_test:legacy_ascend
```
生成：低位平台（你脚下）→ 东侧 **1 格台阶** → 再东 **2 格高墙**。

**操作**：
1. `/alice follow on`
2. 向东走上 1 格台阶，观察 bot 是否跟随并**跳跃上台阶**
3. 继续走向 2 格高墙，观察 bot **不应爬上去**

**预期**：
- 1 格台阶：bot 跟随成功（`FollowTask` 正常推进，无卡住/超时）
- 2 格高墙：bot 停住、不上墙（诚实失败或原地等待），**不允许穿墙/爬墙**

**日志**：`follow_unsettled` / `follow_no_path` 等 legacy 失败码不应在 1 格台阶场景出现。

---

## 6. 日志前缀

| 前缀 | 含义 |
|---|---|
| `[R3 Battery]` | 一键自检：规划检查、逐项执行、SUMMARY |
| `[R3 Plan]` | 规划器输出（状态、步数、成本、节点、用时、逐步明细） |
| `[R2-C Traverse/Diagonal/Ascend/Descend]` | 单步 Movement 终态 |
| `[R2-C Chain]` | 多段链逐段进度与终态 |
| `[Descend-PROBE]` | Descend 临时逐 tick 探针（验收后删除） |

---

## 7. 失败码速查

| 失败码 | 含义 |
|---|---|
| `*_STALE_START` | 起点与计划不符（合法位置集问题） |
| `*_INVALID_PRECONDITION` | 目标不可站/不可穿/无支撑 |
| `DESCEND_REJECTED_OVERSHOOT_CLIFF` | 过冲列过深（>2 格）或不可行走 |
| `DESCEND_REJECTED_LANDING_HAZARD` | 过冲列有熔岩/火/岩浆块 |
| `DESCEND_OVERSHOT_BELOW_TARGET` | 真的落得比目标更低 |
| `*_SETTLING_TIMEOUT` | 到位后未在容差内稳定 |
| `SEARCH_LIMIT` | 预算耗尽（**不是**不可达） |
| `UNREACHABLE` | 搜索空间穷尽，确实不可达 |

---

## 8. 反馈模板（AI 会替你填）

```
测试项：一键自检（alice:pathing_battery）
操作：站在楼梯顶端平地，右键方块
聊天结果：<SUMMARY 行>
亲眼观察：ascend 是否跳跃 / chain2 是否回冲 / 有无卡边缘
复现：必现 / 偶发
```
