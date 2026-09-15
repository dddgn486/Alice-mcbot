# 客户端轮次证据报告 —— 2026-09-15（D-220 + T3）

## 工件

| 项 | 值 |
|---|---|
| jar | `462b10b5af178cb8ed223b8cce949fc8b76be56e55d6535ea6fe7362219c7780` |
| 三处一致 | 仓库 `build/libs` = Windows 镜像 `build/libs` = 客户端 `mods/` |
| 含内容 | T3 全部七步（步骤 1 / B3a / A / A2 / C / (A) / B4）+ **D-220**（夹具时机 / 自愈场景 / 无头 peaceful / `FixtureScript`） |
| 上一次真人验收的 jar | `f478d9f7…`（第十八轮，T3 之前）⇒ 本轮是**新工件**的第一次真人验收 |

**日志**：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/logs/latest.log`（4138 行，10:59 收尾）
**关键行**：同目录 `key-lines.log`

## 入口（用户实际用的）

- **游戏内电池物品一次右键**（`RegressionBatteryTask`，CORE 30 项）—— 日志 `[Regression] PROFILE=CORE 实跑 30 项（跳过 EXTRA 10 项）`；
- **机器探针物品**（T3）—— 日志 `[MachineProbe]` 349 行；
- ⚠️ **没有**跑 R4 夹具物品（`pathing_disturber` / `pathing_waller` / `pathing_placer`）：
  全会话 `[R4 Fixture]` 命中 **0** ⇒ 物品侧的 D-220 改动**本轮未覆盖**（见"待确认"）。
- 聊天里没有 `/alice` 命令（`[CHAT] /alice` 命中 0）⇒ 全程物品驱动。

## 结果表

| 判据 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 电池总判 | `(passed=30/30 skipped=0) → PASS` | **`(passed=30/30 skipped=0) ticks=3337`**，`K4=OK(0/0)`，`baseline=14 main=16 extra_skipped=10` | ✅ |
| 客户端 vs 无头（pathing 场景行） | 逐字一致 | **54 行 `diff` 全等**（先 `tr -d '\r'`：Windows 日志是 CRLF，直接 diff 会整段假差异） | ✅ 跨通道等价 |
| D-220 ①：封路夹具真的在中途动手 | `wall_placed … sceneTick=30 pathIndex=3/9` | **完全一致**（`at=5,64,66 sceneTick=30 pathIndex=3/9`） | ✅ 与 2026-09-09 客户端验证过的位置相同 |
| D-220 ①：平移夹具 | `disturbed … sceneTick=32` | **`from=4,64,66 → 4,64,67 sceneTick=32`** | ✅ |
| D-220 ②：`DIAGONAL` 确定性覆盖 | `coverage=PASS` + 9 种 Movement | **`coverage=PASS executed=TRAVERSE,DIAGONAL,ASCEND,DESCEND,DOWNWARD,PILLAR,FALL,BREAK_AND_TRAVERSE,BREAK_AND_ENTER,PLACE_STEP_AND_TRAVERSE`**；`dip_course+run=PASS (TRAVERSE,DIAGONAL)` | ✅ |
| D-220 ③：无噪声（客户端侧观察） | 无怪物击杀/击退 | `creeper|假人死亡|blown up` 命中 **0** | ✅（一次观察，非结论 ⇒ 按用户裁定"先观察"继续） |
| "夹具未生效"应响亮 | `FIXTURE_NOT_FIRED|disturb_not_applicable|disturb_skipped` = 0 | **0** | ✅ |
| T3 探针读数 | 与无头一致 | 42 字段中 **41 个逐字相同**，唯一差异 = `recipe_order_hash`（**已登记为非确定值**：`RecipeManager.getRecipes()` 迭代序） | ✅ T3 升 `WINDOWS_CLIENT` |

**T3 客户端读数（关键）**：`types=56 type_recipes=1823 samples=108 upstream_readable=47 input_readable=65
chance_declared=27 vanilla_input=0 divergent=0 read_notes=0 query_probed=3 query_reachable=3
machine_map_rows=59 with_site_confirmed=52 shared_site=6`（与无头 `shared-core-r1` 逐字相同）。

## 待确认

1. **R4 物品侧** —— ✅ **同日补测完成**（见文末"补测"一节）。
2. **怪物是否干扰客户端**：本轮 0 命中，样本 1 ⇒ 维持用户 2026-09-15 裁定"先观察，有证据再改"。
3. 日志里的 `[MiningPlanner] standable_only … no_reachable_standing_point`（6 次，目标 `6,64,64`）
   **不是**用户挖矿：那是电池 `clear_retry` 夹具**按设计**用 `standableOnly=true` 制造的场景
   （同轮 `clear_retry=PASS`）。**不要**把它读成"挖矿够不到"的证据。

---

## 补测：R4 夹具物品（同日第二段会话，jar 未变）

**入口**：`/function alice_test:place_course` → 分别右键 `alice:pathing_disturber` / `alice:pathing_waller`
（零参数；物品自己把 bot 传回起点 `(0,64,66)`，不依赖玩家站位）。
**关键行**：`r4-items-key-lines.log`

| 物品 | 夹具行 | 终态 | 用户观察 | 结论 |
|---|---|---|---|---|
| `pathing_disturber` | `disturbed from=4,64,66 to=4,64,67 tick=33` | `COMPLETED segments=8/8 ticks=69 finalFoot=8,62,66 **replans=0**` | "看不出来什么问题" | ✅ 夹具**真的动手了**（旧行为下这里可能是静默跳过）。**"看不出来"是预期**：1 格横向位移被**移动控制器自己走回目标格**吸收（无 resync、无 replan）⇒ 与无头实测同形 |
| `pathing_waller` | `wall_placed at=5,64,66 tick=30 pathIndex=3/9` | `COMPLETED segments=3/3 ticks=40 finalFoot=8,62,66 **replans=1**` | "**挖掉石头的**" | ✅ **日志逐字对上**：`replan attempt=1 replans=1 reason=BLOCKED code=SEGMENT_FUTURE_BLOCKED feet=4,64,66` → 新计划首段 `type=BREAK_AND_TRAVERSE from=4,64,66 to=6,64,66`（**就是挖掉那块石头**）→ `FALL` → `TRAVERSE` 到目标 |

**新判据（D-220 物品侧）**：`FIXTURE_NOT_FIRED` / `not_fired` / `disturb_not_applicable` 命中 **0**，
全会话**零 WARN / 零 FAILED** ⇒ 物品侧走的是"夹具已生效"的**正常分支** ⇒ 该分支升 **`WINDOWS_CLIENT`**。
⚠️ **负例分支仍未覆盖**：`FIXTURE_NOT_FIRED ⇒ Status.FAILED` 只在"夹具放不下 / 找不到落点"时触发，
而两件物品的起点/终点是**硬编码的同一场景**（必然放得下）⇒ 要跑负例得再做一个"注定放不下"的场景
（属新测试入口，按需再做，已记台账）。

**顺带确认（与 D-035 的偏离登记呼应）**：disturber 的 1 格横向位移**既不触发漂移检测、也不触发 resync**
（`driftedOutOfSegment` 要求离两端 >3 格）⇒ 结果正确，但机制是"控制器把 bot 拉回目标格"。
已登记为独立议题（评审 §6.1：建议按 Baritone `getValidPositions().contains(feet)` 对齐）。
