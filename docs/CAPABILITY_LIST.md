# Alice 能力清单（**生成物 —— 禁手改**）

> `B3` / `Q-22`（`survey/29 §3.8⑥`：把「它知道的自己」做成**从代码生成的表**，而不是记忆 —— 「它以为的自己」和「真实的自己」结构上不可能分叉）。
> 生成器 = `tools/capability-list.py`；门禁 = `tools/check-capability-list.sh`（已挂 `tools/check-all.sh`）。
> **手改本文件没有意义**：`--check` 会重新生成并与磁盘逐字节比对，不一致即**构建红**。
> 本文件**不含时间戳**（确定性输出 ⇒ 没改东西时 `git diff` 是空的）。

**怎么读**：每一节 = 一个**已有单一出处**的人读视图；清单与代码不一致时**以代码为准**。「证据」列给出该条目的出处方法，便于回溯。

## §0 总览

| 面 | 数 | 节 |
|---|---|---|
| 目标级动作（它**能说出**什么） | 6 | §1 |
| 作业种类（它**能接什么活**） | 5 | §2 |
| 移动原语（它**能怎么动**） | 10（其中改世界 5） | §3 |
| 自检模块 | 21 | §4.1 |
| 自检步（能力清单用它证明自己） | 98 = BASELINE 15 + MAIN 26 + EXTRA 57（**CORE 实跑 41**） | §4.2 |
| 机器类型（上游已登记） | 59 行 | §5 |
| 玩家能调的开关 | 3 | §6 |
| 底线（**任何玩家入口都不许出现**） | 3 | §7 |
| 区块授权判定 | Verdict 3 × Act 2 | §7 |

---

## §1 目标级：它**能说出**什么（`GoalAction` 白名单）

出处：`decision/GoalAction.java#parse（case 白名单）`。它**只允许**说这 6 个动作；词表与 prompt 文本的一致性由 `tools/check-goal-vocabulary.sh` 断言（集合相等，多一个少一个都红）。

| 动作 |
|---|
| `start_job` |
| `stop_current` |
| `maintain_tool` |
| `craft` |
| `report_status` |
| `no_op` |

> 纪律（`survey/29 §3.8⑦`）：**最强的限制不是限制它能「说」什么，而是限制它能「说得出」什么** ——所以这张表本身就是一道限制。

## §2 作业级：它**能接什么活**（`JobRequest.Kind` × 契约）

出处：`job/JobRequest.java#Kind` + `job/JobKindContract.java#TABLE`。每个 kind 的**成功判据必须能在世界里判**（`D-349`），且「读世界事实」的方法由门禁核对真的存在。

| kind | 成功判据（与什么对账） | 读世界事实 | 对不上时怎么办 |
|---|---|---|---|
| `LUMBER` | 目标树真的被砍掉，且**原木进背包**的净增量 ≥ 本次配额 | `LumberJob#countLogs` | 如实失败/未达成（配额未满足或掉落物没到手），不静默成功 |
| `MINE` | 目标方块真的被破坏，且**目标物品进背包**的净增量 ≥ 已破坏数 | `MineJob#countTargetItems` | 如实 `FAILED product_not_collected`（挖了但没拿到 = 没成功） |
| `REGION_LUMBER` | 区域内持续可作业（区域不变量）；**不可维持时必须如实登记**（无树无苗无欠） | `RegionLumberJob#maintainUnreachable` | 登记 + 上报「可做什么」（不擅自收工：常驻只由玩家/决策层打断） |
| `COLLECT` | 清单内的落物**进背包**（按 in-flight 账本的净增量对账） | `CollectJob#dropsInRange` | 仍有清单落物未到手 ⇒ 如实未完成/失败，不按「去过就算」结账 |
| `CRAFT` | 产物数量 ≥ 配额（**从产物栏/背包读出**的实数） | `CraftJob#verify` | 如实 `FAILED partial_quota`（少了就是少了） |

## §3 移动级：它**能怎么动**（`MovementType`）

出处：`pathing/core/MovementType.java#changesWorld`。**改世界**的那一族 = 规划期写入信封的唯一口径（`D-241`：`PathRequest.pureTraversal()` 与 `WriteEnvelopes` 都从这里取）。

| Movement | 会改世界？ |
|---|---|
| `TRAVERSE` | 否（纯通行） |
| `DIAGONAL` | 否（纯通行） |
| `ASCEND` | 否（纯通行） |
| `DESCEND` | 否（纯通行） |
| `DOWNWARD` | **是**（需显式授权 + 预算） |
| `PILLAR` | **是**（需显式授权 + 预算） |
| `FALL` | 否（纯通行） |
| `BREAK_AND_TRAVERSE` | **是**（需显式授权 + 预算） |
| `BREAK_AND_ENTER` | **是**（需显式授权 + 预算） |
| `PLACE_STEP_AND_TRAVERSE` | **是**（需显式授权 + 预算） |

## §4 自证级：它**能证明自己什么**（自检模块 × 电池步）

出处：`task/check/CheckModules.java#ALL + modules/*.java 的 CheckStep 声明` + `task/RegressionBatteryTask.java#CURATION（+ buildSteps 顺序）`。

### §4.1 自检模块（21 个）

| 模块 id | 标题 | 期望判决 | 步数 | 电池内？ |
|---|---|---|---|---|
| `ledger` | 账本 / 写入预算 / 场景清理 | `PASS` | 6 | ✅ |
| `harness_self` | 编排器自检（故意被外部命令打断两次） | `FAIL` | 3 | ❌（豁免：整模块 expectedVerdict=FAIL（故意被打断两次）⇒ 只由 module:harness_self 单独跑，不进电池） |
| `pathing` | 移动执行（落差 / 竖井 / 停表） | `PASS` | 11 | ✅ |
| `decision` | 决策 / 观测 / 归因 | `PASS` | 5 | ✅ |
| `craft` | 合成 / 工作站（只读查询 → 随身 2×2 → 工作台 → 自放站 → 网格发现 → 熔炉） | `PASS` | 13 | ✅ |
| `machine` | 机器路线（自述只读 → 站点只读 → 单机闭环 → 生产入口） | `PASS` | 4 | ✅ |
| `mining` | 挖掘（执行器回归 / 周期复评 / 候选契约 / 生产入口 / 归因三连） | `PASS` | 16 | ✅ |
| `lumber` | 伐木（失败归因五连 / 生产闭环 / 区域常驻 + 补种） | `PASS` | 6 | ✅ |
| `transfer` | 传输（4 夹具：主流程/端点选择/选择器事件/命令解析 + 端到端） | `PASS` | 1 | ✅ |
| `survival` | 维生（决策表 / 出口可达 / 脚位口径 / 掉血可见 / 逃生准备金上限） | `PASS` | 5 | ✅ |
| `death` | 死亡（数据保留判据 / 端到端真死一次） | `PASS` | 2 | ✅ |
| `tools` | 工具（供给三例 + 不许凭空变工具） | `PASS` | 1 | ✅ |
| `gates` | 闸门（SEARCH_LIMIT≠UNREACHABLE / 能力闸门真的能拦人） | `PASS` | 2 | ✅ |
| `llm` | LLM 与权限契约（上抛失败字段 / 权限门四档） | `PASS` | 2 | ✅ |
| `pickup` | 掉落物（归属闸门 / 收集 Job 最小闭环） | `PASS` | 7 | ✅ |
| `telemetry` | 观测与转储（配方转储 / 事件阈值报到且只报一次） | `PASS` | 2 | ✅ |
| `write` | 写入与可回收性（可回收性真的被评估 / 写入策略表 + 越权负例） | `PASS` | 2 | ✅ |
| `contracts` | 决策契约（说话通道只出不进 / 决策层契约 / trace 跨重启） | `PASS` | 3 | ✅ |
| `protection` | 保护区（区块级认领 + 全高度 + 迁移 + 黑名单回归） | `PASS` | 3 | ✅ |
| `ownership` | 假人归属（创建者登记 / 认领单向 / 存档往返 / 老存档不猜） | `PASS` | 1 | ✅ |
| `break_refused` | 破坏被拒（世界事实判定 / 冒险模式 / FTB 认领 / 不许谎报成功） | `PASS` | 1 | ✅ |

### §4.2 电池步（98 步；`#` = CORE 运行序，`—` = 只在 FULL 跑）

| # | 步名 | 档位 | 来源 |
|---|---|---|---|
| 1 | `clear_retry` | BASELINE | ledger |
| 2 | `write_budget` | BASELINE | ledger |
| 3 | `scaffold` | BASELINE | ledger |
| 4 | `clear_guard` | BASELINE | ledger |
| — | `ledger_zone_scope` | EXTRA | ledger |
| — | `lossy_write_accounted` | EXTRA | ledger |
| — | `lumber_failure` | EXTRA | lumber |
| 5 | `lumber_job` | BASELINE | lumber |
| — | `region_maintain` | EXTRA | lumber |
| — | `region_sweep` | EXTRA | lumber |
| — | `region_sweep_e2e` | EXTRA | lumber |
| — | `region_maintain_unmaintainable` | EXTRA | lumber |
| 6 | `mine_regression` | BASELINE | mining |
| 7 | `no_progress` | MAIN | mining |
| 8 | `mine_menu` | MAIN | mining |
| 9 | `mine_job` | BASELINE | mining |
| — | `mine_survey` | EXTRA | mining |
| — | `mine_inventory` | EXTRA | mining |
| 10 | `mine_no_tool` | MAIN | mining |
| 11 | `mine_stale` | MAIN | mining |
| 12 | `mine_budget` | MAIN | mining |
| — | `mine_far_drop` | EXTRA | mining |
| — | `mine_run_metrics` | EXTRA | mining |
| — | `mine_reach_probe` | EXTRA | mining |
| — | `mining_water_break_cost` | EXTRA | mining |
| — | `mining_search_limit_honesty` | EXTRA | mining |
| — | `mine_vein_propagation` | EXTRA | mining |
| — | `fishbone_slice1` | EXTRA | mining |
| 13 | `hazard_aversion_plan` | MAIN | (内联) |
| 14 | `container_access_profile` | MAIN | decision |
| 15 | `driver_label` | MAIN | decision |
| 16 | `risk_profile_frozen` | MAIN | decision |
| 17 | `damage_event_visible` | MAIN | decision |
| 18 | `mine_failure_visible` | MAIN | decision |
| 19 | `break_enter_head_blocked` | MAIN | pathing |
| 20 | `coarse_goal_prefix` | MAIN | pathing |
| — | `break_traverse_footing` | EXTRA | pathing |
| — | `place_step_descend_clearance` | EXTRA | pathing |
| — | `head_blocked_route_closure` | EXTRA | pathing |
| — | `edge_completeness` | EXTRA | pathing |
| — | `place_step_diagonal` | EXTRA | pathing |
| — | `fall_execute` | EXTRA | pathing |
| — | `pillar_execute` | EXTRA | pathing |
| — | `contrast_timer` | EXTRA | pathing |
| — | `tick_budget_bench` | EXTRA | pathing |
| 21 | `death_persistence` | MAIN | death |
| — | `death_kill_bot` | EXTRA | death |
| 22 | `speech_channel` | MAIN | contracts |
| 23 | `decision_contract` | MAIN | contracts |
| — | `decision_trace` | EXTRA | contracts |
| 24 | `craft_check` | MAIN | craft |
| — | `craft_action` | EXTRA | craft |
| 25 | `craft_table` | MAIN | craft |
| — | `craft_station` | EXTRA | craft |
| — | `restore_underfoot_safety` | EXTRA | craft |
| — | `craft_probe_inventory` | EXTRA | craft |
| — | `craft_probe_table` | EXTRA | craft |
| — | `craft_probe_upgradetab` | EXTRA | craft |
| — | `craft_station_provision` | EXTRA | craft |
| — | `craft_station_craft` | EXTRA | craft |
| 26 | `craft_furnace` | MAIN | craft |
| 27 | `craft_cooking` | MAIN | craft |
| 28 | `craft_goal` | MAIN | craft |
| — | `machine_route` | EXTRA | machine |
| — | `machine_station` | EXTRA | machine |
| — | `machine_cycle` | EXTRA | machine |
| 29 | `craft_machine` | MAIN | machine |
| 30 | `transfer` | BASELINE | transfer |
| 31 | `partial_search` | BASELINE | gates |
| 32 | `capability_gate` | BASELINE | gates |
| 33 | `tool_supply` | BASELINE | tools |
| 34 | `llm_contract` | MAIN | llm |
| — | `permission_gate` | EXTRA | llm |
| — | `scope_pending_grace` | EXTRA | pickup |
| — | `pickup_gate` | EXTRA | pickup |
| — | `collect_job` | EXTRA | pickup |
| — | `collect_slot_approach` | EXTRA | pickup |
| — | `collect_offcenter_retry` | EXTRA | pickup |
| — | `kill_drop_provenance` | EXTRA | pickup |
| — | `job_area_grant` | EXTRA | pickup |
| — | `recipes_dump` | EXTRA | telemetry |
| — | `event_thresholds` | EXTRA | telemetry |
| 35 | `recoverability` | BASELINE | write |
| 36 | `write_policy` | BASELINE | write |
| 37 | `pathing` | BASELINE | (内联) |
| — | `survival_idle_drown` | EXTRA | survival |
| — | `survival_shore_escape` | EXTRA | survival |
| — | `survival_escape_air` | EXTRA | survival |
| — | `survival_stop_in_hazard` | EXTRA | survival |
| 38 | `survival_exit` | BASELINE | survival |
| 39 | `protection_zones` | MAIN | protection |
| — | `safe_return` | EXTRA | protection |
| — | `task_zone` | EXTRA | protection |
| 40 | `bot_ownership` | MAIN | ownership |
| — | `bot_pair_no_recurse` | EXTRA | (内联) |
| 41 | `break_refused` | MAIN | break_refused |
| — | `far_path_bench` | EXTRA | (内联) |
| — | `path_retry_bench` | EXTRA | (内联) |

> 档位语义（`docs/BATTERY_CURATION.md` §1）：`BASELINE` = 坏了就不能信任 bot 的任何动作；`MAIN` = 当前主线；`EXTRA` = 已验收/与主线无关/贵 ⇒ 默认不跑（`/alice battery full` 才跑）。
> **CORE = BASELINE + MAIN = 41 步**。

## §5 机器级：它**能运营什么机器**（`MachineMap`）

出处：`docs/MACHINE_MAP.csv（由 tools/machine-map.py 生成）`（**新鲜度由 `tools/check-machine-map.sh` 门禁**：Java 表 ↔ CSV 逐字段一致，并用 `javap` 读上游 jar 的注册名做**双向覆盖**断言）。这里只给汇总与人读指针。

| 命名空间 | 已登记类型数 |
|---|---|
| `mekanism` | 27 |
| `thermal` | 32 |

共 **59** 行；逐行明细 = `docs/MACHINE_MAP.csv`（生成物）。

## §6 开关面：玩家**能调什么**（`RiskSwitches.KNOWN`）

出处：`pathing/risk/RiskSwitches.java`。**判定准则**：默认值就是现状、且打开**只会收紧不会放宽**（⇒ 玩家调不坏安全）。

| 开关 | 为什么它可以是可选的 |
|---|---|
| `descend_overshoot_guard` | D-024 过冲红线：默认关（= Baritone 原样），打开只**加**守卫 ⇒ 不会放宽任何安全 |
| `container_access` | D-291 画像字段：默认 true（= 现状允许开箱），关掉只**收紧** ⇒ 不会放宽 |
| `hazard_aversion` | D-292 危险厌恶：默认 false（= 现状零行为变化），打开只**加价绕行** ⇒ 不会放宽 |

> ⚠️ 名字的**常量名**在代码里是 `CONTAINER_ACCESS` / `DESCEND_OVERSHOOT_GUARD` / `HAZARD_AVERSION`。

## §7 不能做什么（底线 + 授权面）

### §7.1 底线（**任何玩家可写入口都不许出现**）

出处：`pathing/risk/RiskSwitches.java` 的 `BOTTOM_LINES`。门禁 `tools/risk-surface.py`（`B2`）断言这些名字不出现在命令/配置面，且与可选开关不相交。

| 底线 | 谁在强制它 |
|---|---|
| `pathing_pure_traversal` | D-076 寻路红线（默认纯通行；破坏/放置只由上层显式授权）+ tools/check-authz-registry.sh |
| `server_authoritative_state` | AGENTS.md「不可悄悄改变的架构边界」（标注 [未门禁]，复核触发已登记） |
| `unknown_mod_read_only` | 「未知模组能力默认只读」+ tools/check-machine-map.sh（部分覆盖） |

### §7.2 区块授权面（世界写入为什么会被拒）

出处：`protection/ZoneAuthority.java（Verdict / Act）`。判定三态 + 两种动作；授权/预算/账本的完整清单 = `docs/authz/OVERVIEW.md`（生成物，出处 = `docs/authz/AUTHZ_REGISTRY.csv`）。

| Verdict | 含义 |
|---|---|
| `NOT_GATED` | 该区块**未认领** ⇒ 本判据不适用（野外） |
| `ALLOW` | 有任务区覆盖 + 等级够 ⇒ 放行（仍受授权/预算/账本约束） |
| `DENY` | 拒绝（带码） |

| Act |
|---|
| `BREAK` |
| `PLACE` |

## §8 门禁在断言什么（**跨出处**的双向；「生成的文档 == 生成的文档」是同义反复，不算判据）

| # | 断言 |
|---|---|
| A1 | 步声明（模块 `CheckStep` ∪ 电池内联 `step(...)`）**↔** `CURATION` 键（双向，无豁免） |
| A2 | 模块 `CheckProfile` **↔** `CURATION` 档位（同一个档位今天有**两个家**） |
| A3 | `CheckModules.ALL` **↔** 电池成员（缺席者必须在 `MODULE_EXEMPT` 里带理由） |
| A4 | `JobRequest.Kind` **↔** `JobKindContract` 表（现有门禁只查单向） |
| A5 | `MovementType` 常量 **↔** `changesWorld()` 名单 |
| A6 | `RiskSwitches.OPTIONAL` 键 **↔** `KNOWN`（解析自检；强门禁在 `risk-surface.py`） |
| A7 | 人口下限（防空集真）+ 每节的**独立计数**交叉核对（**解析不到就响亮失败**） |
| A8 | 本文件**不陈旧**（重新生成 ⇒ 逐字节相同） |
| A9 | `docs/BATTERY_CURATION.md` 的手写小节**不许再自称清单/计数**（它只留「为什么」） |

> 之前这些一致性里，只有 A1 被断言过 —— 而且是在**运行期**（`RegressionBatteryTask.prepareSteps` 自校验，要起一次服务端 ≈20–26 s + 跑完预算才响）。A2/A3/A4 的反向**此前没有任何断言**。
