# Mine 迁移设计（草案，待用户确认后实施）

> 状态：**设计已评审**（2026-09-09）。用户裁定：收集=best-effort DONE + 摘要（不挖台阶、删反射）；Baritone `blacklist`/`coalesce` 留后；验收入口沿用 `alice:target_selector` 右键 + 恢复 `scene_a`。
>
> 原草案说明：用户裁定：① 直接做 Mine（先恢复核心玩法）；② 先出设计再实施；
> ③ Mine 逻辑移植清晰、可部分照搬 Baritone；④ `DropCollectionTask` 视为屎山，**按重写设计**。

---

## 1. 现状（代码事实）

| 组件 | 行数 | 职责 | legacy 依赖 |
|---|---|---|---|
| `task/MineTask` | 392 | 编排：评估站位 → 挖掘 → 收集；失败分类/清障/重规划 | `PathExecutor`、`SurfacePathfinder`、`MiningPlanner` |
| `action/BotMiner` | 693 | 动作层：站位候选、视线校验、破坏进度、工具、**移动** | `SurfacePathfinder`、`PathExecutor`、`MovementPlan*` |
| `task/mining/StandingPointSelector` + `Evaluator` | 409 | 站位候选与评分（视线/距离/可达） | `AStarPathfinder`（可达性试算） |
| `task/mining/MiningPlanner` + `MiningPlan` | 85 | 生成"目标 + 站位 + 路径 + 视线"计划快照 | `AStarPathfinder` |
| `task/DropCollectionTask` | 241 | 掉落物收集 | `SurfacePathfinder`、`PathExecutor`、`BotMiner` |

`DropCollectionTask` 的"屎山"特征（重写的依据）：
1. **反射清 `pickupDelay`**（`forcePickup`：`Field.setAccessible` + `playerTouch`）；
2. 物品选择混着 sticky UUID + `abandoned` 集合 + `retries` 计数 + `captureWait`/`waitTicks` 两套计时；
3. 收集阶段内嵌 **挖台阶**（`stairMiner = new BotMiner(...)`，`MAX_STAIR_CLEARS = 8`）——收集与挖掘职责互相污染；
4. 路径兜底 `pos.above()`、`PathExecutor(List.of(pos))` 特例、`collect_stair_failed` 之类的失败码；
5. 超时/失败语义与"挖矿是否成功"耦合（收集失败 → MineTask FAILED）。

---

## 2. 目标与边界（**用户 2026-09-09 改为对齐 Baritone 挖掘语义**）

> **用户裁定（2026-09-09）**：不再使用 Alice 自己的"选站位 + 视线 + 可达"启发式，
> 改为**对齐 Baritone 的挖掘语义**：目标方块本身成为寻路终点，破坏由 Movement 承担
> （`MineProcess` + `GoalBlock`/`GoalTwoBlocks`/`GoalThreeBlocks` 模型）。
> 触发原因：实测 `StandingPointSelector.isValidStandingPoint` 的 `canWalkOn(level, pos.below())`
> 是 off-by-one（新内核 `canWalkOn(level, footPos)` 内部已看 `footPos.below()`），
> 导致所有正常地面站位被拒，bot 只能站在目标顶上朝下挖（`standGoal=4,65,4 face=down`）。

### 2.1 Baritone 的挖掘模型（照搬对象）

| Baritone 机制 | 位置 | 说明 |
|---|---|---|
| 目标集合 → `GoalComposite` | `MineProcess:176-199` | 多个矿石 → 复合目标 |
| `coalesce` → `GoalBlock` / `GoalTwoBlocks` / `GoalThreeBlocks` | `MineProcess:260-334` | 按"目标上/下方是否也可挖"决定脚位落在目标列的上/中/下格 |
| 破坏由 Movement 承担 | `MovementTraverse` 等 | 目标格被当作"要破坏才能进入的格子"，破坏是移动的一部分 |
| `blacklist` 不可达目标 | `MineProcess:60,120` | 反复失败 → 记黑名单 |
| 失败不自动开隧道 | `MineProcess:489+` | 目标不可挖即失败（Alice 的 `target_requires_tunnel` 对应） |

### 2.2 Alice 落地设计

1. **目标即寻路终点**：`MineTask` 不再选站位/查视线，改为把"目标方块列"作为寻路目标：
   - 新增 `GoalColumn(cells)`（Baritone `GoalBlock`/`GoalTwoBlocks`/`GoalThreeBlocks` 等价物）：
     脚位落在 `{loc, loc.below(), loc.below(2)}` 的任一格即达成，按"目标上/下方是否可挖"裁剪候选；
   - 启发式 = 各候选格 `GoalFoot.heuristic` 的**最小值**（可采纳且一致：min 保序）。
2. **破坏即移动**：补齐"破坏目的地格并走进去"的 Movement：
   - 首选**方案 1**：把 `BREAK_AND_TRAVERSE` 的几何从 dx=2 放宽为 **dx ∈ {1,2}**（dx=1 时被破坏的就是目的地格），
     成本 = `TRAVERSE_COST + 破坏成本`；dx=2 的既有语义与回归覆盖不变；
   - 备选方案 2：新增独立 `MovementType.BREAK_AND_ENTER`（语义更清晰，但与 BREAK_AND_TRAVERSE 高度重叠）。
3. **破坏动作复用 `BlockBreakSession`**：与 `BotMiner` 的破坏流程等价（工具选择 / START_DESTROY /
   进度广播 / ABORT / 超时），因此**挖掘动作无需保留两份实现**。
4. **`BotMiner` 退役**：站位候选、视线检查、`PlanInvalidation`、`mineStartPos` 等随之下线；
   伐木任务（`ContinuousLumberTask` / `RegionLumberTask`）同步迁移（它们也用 `BotMiner`）。
5. **视线/触及不再由任务层检查**：由 Movement 前置（`canWalkThrough` / `breakableExplicit` / 触及距离）
   与 `BlockBreakSession` 的越界失败负责（对照 Baritone：可挖性判定在 Movement/`getMiningDurationTicks` 内）。
6. **保留**：不可挖 / 流体 / 保护区的规划期拒绝；挖掘后的掉落物收集（公用子任务，见 §4）。

### 2.3 必须裁定的红线冲突

Baritone 式挖掘的本质是"**边走边破坏，直到走进目标格**"。当目标深埋时，路径本身会挖穿方块——这与 Alice 的
`HARD_PATH` 红线（"`SEARCH_LIMIT` ≠ `UNREACHABLE`，不自动授权隧道"，失败码 `target_requires_tunnel`）冲突。

| 选项 | 含义 | 后果 |
|---|---|---|
| **B1 全 Baritone** | 寻路可破坏任意方块到达目标 | 等价"自动挖隧道"；必须**正式放宽** HARD_PATH 红线并更新 D-024/D-036 相关登记 |
| **B2 有界清障** | 目标本身 + 有限清障（沿用 `MAX_CLEAR_DEPTH=2` 或成本上限），超出则 `target_requires_tunnel` | 保留红线；深埋目标仍失败，但站位/视线启发式全部去掉 |

**用户裁定：待定（见 §9）。**

## 5. 失败码与重试

- 保留（语义不变）：`unbreakable_block`、`fluid_risk_lava`、`protected_*`、
  `target_requires_tunnel`、`stand_search_limit`、`no_safe_execution_path`。
- 新增（新内核终态映射，站位/移动阶段）：`mine_no_path`、`mine_search_limit`、`mine_blocked`、
  `mine_timeout`、`mine_stale`、`mine_invalid_precondition`。
- 收集阶段失败码收敛为：`collect_unreachable`（单物品，进摘要）、`collect_timeout`（总预算耗尽）。
- 重试：保留 `BotMiner.PlanInvalidation` → `MineTask.tryReplan`（`MAX_RECOVERY_ATTEMPTS = 1`）；
  新内核的会话重同步（D-047）与任务层重试（`PathRetryRunner`）替换 legacy 的重规划路径。
- 清障：保留 `MAX_CLEAR_DEPTH = 2`（视线直接遮挡 → 挖掉遮挡块），但清障块的移动同样走新内核。

---

## 6. HARD_PATH 红线（不可违反）

1. `MineTask` 的路径请求必须用 **`PathRequest.of`**（TRAVERSE/DIAGONAL/ASCEND/DESCEND）；
2. 挖掘动作由 `BotMiner` 独立执行，**不得**让寻路器用 `withWorldModification` 自己挖隧道；
3. 深埋目标仍然 `target_requires_tunnel` 失败，不自动授权隧道；
4. `SOFT_SURFACE` 不接入挖矿链路。

> **与收集子任务的关系（用户 2026-09-09 修正）**：HARD_PATH 约束的是"**走到挖掘站位**"这一段
> （不得为了接近矿石自动挖隧道）；**收集子任务**是调用方显式授予世界修改权限的独立需求
> （`allowWorldModification=true`），允许寻路内核为拿到掉落物破坏/放置——两者目的不同，不冲突。

---

## 7. 验收设计

- **入口（零参数）**：`alice:target_selector` 右键方块（已有物品，解除 `assignMine` 门禁即可）。
- **场景**：
  - 恢复 `alice_test:scene_a`（挖矿基础场景，现在因 legacy 禁用不可用）；
  - 新增 `mine_course`：单目标 + 需要清障（视线被挡）+ 需要站位选择（目标侧面）三合一。
- **回归**：单独 `mine_regression`（挖矿较慢，不混入寻路回归）：
  `mine_basic`（直接挖）/ `mine_clear`（先清障）/ `mine_collect`（掉落物收集）→ 输出 `SUMMARY`。
- **日志终态**：`[MineTask] SUMMARY target=... mined=true collected=1/1 ticks=...`。

---

## 8. 实施批次（建议）

| 批次 | 内容 | 验收 |
|---|---|---|
| 1 | `BotMiner` 去寻路（换 `PathRetryRunner`）；`MineTask` 编译/语义对齐；`assignMine` 解除门禁；恢复 `scene_a` | 客户端：右键方块 → 走到站位 → 挖掉 → `COMPLETED` |
| 2 | `CollectDropsTask` 重写 + 接入 `MineTask`（替换 `DropCollectionTask` 调用） | 客户端：挖掉后掉落物被自然拾取，`[CollectDrops] SUMMARY` |
| 3 | 清障/重规划/失败码对齐 + `mine_course` + `mine_regression` | 客户端：三项 SUMMARY 全 PASS |
| 4 | （可选）Baritone `blacklist` 对齐、目标合并 `coalesce` 评估 | 设计评审 |

---

## 9. 用户裁定（2026-09-09）

1. **收集失败语义**：best-effort `DONE` + 摘要；不挖台阶；删除 `pickupDelay` 反射清理（等待自然拾取）。
2. **Baritone 对齐项**：`blacklist` / `coalesce` **都留后**，本批只做"寻路换内核 + 收集重写"。
3. **验收入口**：沿用 `alice:target_selector` 右键方块 + 恢复 `alice_test:scene_a`；
   挖矿自检物品与 `mine_regression` 放到后续批次。
