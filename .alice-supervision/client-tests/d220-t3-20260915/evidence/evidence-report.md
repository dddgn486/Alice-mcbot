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

1. **R4 物品侧未覆盖**：D-220 的"夹具未生效 ⇒ `FIXTURE_NOT_FIRED` + 任务 FAILED"这条**只在右键
   `alice:pathing_disturber` / `alice:pathing_waller` 时才会执行** ⇒ 本轮仍停在 `COMPILES`。
   与用户确认：是**有意没跑**（那就不用管），还是**漏了**（下一轮补右键即可，两件物品各一次）。
2. **怪物是否干扰客户端**：本轮 0 命中，样本 1 ⇒ 维持用户 2026-09-15 裁定"先观察，有证据再改"。
3. 日志里的 `[MiningPlanner] standable_only … no_reachable_standing_point`（6 次，目标 `6,64,64`）
   **不是**用户挖矿：那是电池 `clear_retry` 夹具**按设计**用 `standableOnly=true` 制造的场景
   （同轮 `clear_retry=PASS`）。**不要**把它读成"挖矿够不到"的证据。
