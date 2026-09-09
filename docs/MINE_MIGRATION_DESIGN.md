# Mine 迁移设计（评审稿）

> 状态：**设计已评审并修订**（2026-09-09）。用户裁定：
> ① **保留** Alice 现有的"站位 + 视线 + 选优"挖掘流程，不改成 Baritone 的"目标即终点"；
> ② 先做**兼容**：站位仍由 `StandingPointSelector` 选，走过去交给新内核 Movement（批次 1 已完成）；
> ③ 收集任务抽取为**公用子任务**并**按需授予世界修改权限**（终点=掉落物位置，怎么过去交给寻路系统）；
> ④ 收集 best-effort DONE + 摘要、不挖台阶、删 `pickupDelay` 反射；
> ⑤ Baritone `blacklist`/`coalesce` 留后；
> ⑥ 验收入口沿用 `alice:target_selector` 右键 + `alice_test:scene_a`；
> ⑦ 伐木任务（`ContinuousLumberTask`/`RegionLumberTask`）与 Mine 一起迁移，`BotMiner` 一次删干净。

---

## 1. 现状（代码事实）

| 组件 | 行数 | 职责 | legacy 依赖（迁移前） |
|---|---|---|---|
| `task/MineTask` | 392 | 编排：评估站位 → 挖掘 → 收集；失败分类/清障/重规划 | `PathExecutor`、`SurfacePathfinder`、`MiningPlanner` |
| `action/BotMiner` | 693 | 动作层：站位候选、视线校验、破坏进度、工具、**移动** | `SurfacePathfinder`、`PathExecutor`、`MovementPlan*` |
| `task/mining/StandingPointSelector` + `Evaluator` | 409 | 站位候选与评分（视线/距离/高度） | `MovementHelper` |
| `task/mining/MiningPlanner` + `MiningPlan` | 85 | 生成"目标 + 站位 + 路径 + 视线"计划快照 | `AStarPathfinder`/`SurfacePathfinder` |
| `task/DropCollectionTask` | 241 | 掉落物收集（屎山，重写） | `SurfacePathfinder`、`PathExecutor`、`BotMiner` |

`DropCollectionTask` 的"屎山"特征：反射清 `pickupDelay`、sticky/abandoned/retries/captureWait 多套计数、
收集阶段内嵌挖台阶（`MAX_STAIR_CLEARS=8`）、`pos.above()` 兜底、400 tick 全局超时。

---

## 2. 目标与边界（混合方案）

### 2.1 兼容修复（D-065，已实施）

| 问题 | 事实 | 修复 |
|---|---|---|
| 正常站位全被拒 → bot 只能站目标顶上朝下挖 | `StandingPointSelector.isValidStandingPoint` 写的是 `canWalkOn(level, pos.below())`；而 `canWalkOn(level, footPos)` 内部**已看 `footPos.below()`** → 相当于要求"下方两格有支撑" | 改为 `canWalkOn(level, pos)` |
| 允许站在目标正上方 | 选择器显式把 `target.above()` 加入候选（注释写着"正上方和正下方（特殊情况）"），与 legacy `BotMiner.pickStandCandidates` 的排除规则不一致 | 排除 `pos.equals(target) || pos.equals(target.above())`（站目标顶上挖 = 挖掉自己的支撑） |

实测证据（修复前，scene_a）：`standGoal=4,65,4 face=down`，目标 `(4,64,4)`——bot 站在目标顶上朝下挖，挖完掉进坑。

### 2.2 保留的 Alice 设计（用户裁定）

- **站位候选**：`StandingPointSelector.generateCandidates`（目标周围侧面/对角/上下 1~2 格）；
- **评分**：`StandingPointEvaluator` = `10×视线 + 1×距离 + 0.5×高度`；
- **移动**：新内核 `PathRetryRunner` + `PathRequest.of`（批次 1 已接入 `BotMiner`）；
- **挖掘**：`BotMiner` 的破坏流程（工具/进度/广播/ABORT），与 `BlockBreakSession` 等价。

### 2.3 待讨论（用户指定，先不动代码）

1. **站位选优机制**：
   - 是否加入"可挖面 / 不会挖到自己支撑 / 退路可回收"等维度；
   - 是否把 `StandingPointSelector` 与 legacy `BotMiner.pickStandCandidates` 两套候选规则合并为一套。
2. **需要挖掘通道的目标**（深埋矿石）：
   - 现状：`MiningPlanner` 用 `PathRequest.of`（纯通行）→ 不可达即 `target_requires_tunnel` / `no_safe_execution_path`；
   - 待讨论：有限清障（`MAX_CLEAR_DEPTH=2`）还是 Baritone 式"边走边破坏到目标列"（B1/B2），
     以及与 `HARD_PATH` 红线的关系。

---

## 3. Baritone 的挖掘模型（讨论材料，不照搬）

| Baritone 机制 | 位置 | 说明 |
|---|---|---|
| 目标列 = 寻路目标 | `MineProcess.coalesce:260-275` | 默认 `forceInternalMining=false` → `GoalThreeBlocks(loc)`（脚位 ∈ {ore, ore-1, ore-2}，同 x,z）或 `GoalTwoBlocks(loc)` |
| 破坏是 Movement 的一部分 | `MovementTraverse:56` + `Movement.prepared:153-190,226-228` | `positionsToBreak = {to.above(), to}`，PREPPING 阶段左键破坏完再走 |
| 列内暂停挖掘 | `MineProcess.onTick:116-135` | 矿石在自身列且 `y ≥ 脚位` → `REQUEST_PAUSE` + 转向 + 切最佳工具 + 按住左键 |
| `blacklist` / `anticipatedDrops` / 周期重扫 | `MineProcess:60,120,176-199` | 不可达目标、等掉落物、`mineGoalUpdateInterval` |

**核心差异**：Baritone 没有"站位评分/视线检查"；它的"站位"就是目标列，破坏沿途方块是寻路的一部分。
Alice 是"先选站位、检查视线、走到位、再挖"，深埋目标因 HARD_PATH 纯通行而失败。

---

## 4. 收集任务重写设计（`CollectDropsTask`，**公用子任务**）

### 4.1 定位
- 独立的 `task/CollectDropsTask`（不是 MineTask 的内部阶段），可被任意任务复用；
- 职责单一：**把指定来源的掉落物捡回来**；
- 移动完全由寻路内核负责（含必要时的破坏/放置），本任务不自己挖方块、不自己搭桥。

### 4.2 构造参数（复用接口）
```java
CollectDropsTask(
    BotPlayer bot,
    BlockPos origin,                // 来源（挖掘点/伐木点），用于确定收集范围
    ScopeBuffer scope,              // 感知作用域
    List<UUID> expectedIds,         // 期望收集的掉落物（可为空 → 范围内扫描）
    boolean allowWorldModification  // 按需授予：true → PathRequest.withWorldModification
)
```
- `true`：寻路器可用 `BREAK_AND_TRAVERSE` / `PLACE_STEP_AND_TRAVERSE` / `DOWNWARD` / `FALL` / `PILLAR` 打通路线；
- `false`：只用 `PathRequest.of`；够不到就标记该物品不可达。

### 4.3 算法（每 tick）
1. 刷新候选：`scope.liveItemsFromOrigin(origin)`；优先 `expectedIds`，其余按"新出现且在半径内"纳入；
2. 选目标：最近优先 + 粘滞（除非消失/超距/超时）；
3. **终点 = 掉落物的 `blockPosition()`**（不做"相邻站位"改写）；
4. 移动：`PathRetryRunner` +（按需）`withWorldModification`；FAILED（不可达）→ 标记该物品不可达；
5. 拾取：站在掉落物格（或 1 格内）后**等待自然拾取**（上限 `PICKUP_WAIT_TICKS`）；**不反射、不调 `playerTouch`**；
6. 收尾：`[CollectDrops] SUMMARY collected=n/m unreachable=k ticks=...` → `DONE`。

### 4.4 失败语义（已裁定）
best-effort：收集不到不判 FAILED，只记日志 + 摘要。理由：掉落物可能被岩浆烧掉/被别的实体捡走/掉进深坑。

### 4.5 明确删除的 legacy 行为
| 行为 | 处置 |
|---|---|
| 反射清 `pickupDelay` + `playerTouch` | 删除（等待自然拾取） |
| 收集阶段自己挖台阶（`BotMiner`） | 删除（打通路线改由寻路内核的 `withWorldModification` 负责） |
| `abandoned`/`retries`/`captureWait`/`waitTicks` | 删除（统一为每物品预算 + 任务总预算） |
| `pos.above()` 兜底 / `PathExecutor(List.of(pos))` 特例 | 删除 |
| 400 tick 全局超时 | 保留但参数化 |

---

## 5. 失败码与重试

- 保留：`unbreakable_block`、`fluid_risk_lava`、`protected_*`、`target_requires_tunnel`、
  `stand_search_limit`、`no_safe_execution_path`。
- 新增（新内核终态映射）：`mine_no_path`、`mine_search_limit`、`mine_blocked`、`mine_timeout`、
  `mine_stale`、`mine_invalid_precondition`。
- 收集阶段：`collect_unreachable`（单物品，进摘要）、`collect_timeout`（总预算耗尽）。
- 重试：保留 `BotMiner.PlanInvalidation` → `MineTask.tryReplan`（`MAX_RECOVERY_ATTEMPTS = 1`）；
  新内核的会话重同步（D-047）与 `PathRetryRunner` 替换 legacy 重规划。
- 清障：保留 `MAX_CLEAR_DEPTH = 2`（视线直接遮挡 → 挖掉遮挡块），清障块的移动同样走新内核。

---

## 6. HARD_PATH 红线

1. `MineTask` 的**到站位**路径必须用 `PathRequest.of`（TRAVERSE/DIAGONAL/ASCEND/DESCEND）；
2. 挖掘动作由 `BotMiner` 独立执行；不得让寻路器用 `withWorldModification` 自己挖隧道；
3. 深埋目标仍然 `target_requires_tunnel` 失败（除非 §2.3 讨论后另行裁定）；
4. `SOFT_SURFACE` 不接入挖矿链路。

> **与收集子任务的关系**：HARD_PATH 约束的是"走到挖掘站位"这一段；
> 收集子任务是调用方显式授予世界修改权限的独立需求（`allowWorldModification=true`），两者不冲突。

---

## 7. 验收设计

- 入口（零参数）：`alice:target_selector` 右键方块 / `alice_test:scene_a` + `alice:mining_scene_tester`。
- 场景：`scene_a`（基础）、后续新增 `mine_course`（需清障 + 需选站位）。
- 回归：后续单独 `mine_regression`（挖矿慢，不混入寻路回归）。
- 日志终态：`[MineTask] SUMMARY target=... mined=true collected=1/1 ticks=...`。

---

## 8. 实施批次

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | `BotMiner` 去寻路（换 `PathRetryRunner`）；`MiningPlan`/`MiningPlanner` 换新内核；`assignMine` 解除门禁 | **已实施**（D-064） |
| 1.5 | 站位选择 off-by-one 修复 + 排除目标正上方（D-065） | **已实施，待复测** |
| 2 | `CollectDropsTask` 重写 + 接入 MineTask | 待做 |
| 3 | 清障/重规划/失败码对齐 + `mine_course` + `mine_regression` | 待做 |
| 4 | 伐木任务一起迁移，删除 `BotMiner` | 待做 |
| 5 | （讨论后）站位选优机制改进 / 深埋目标通道策略 | 待讨论 |

---

## 9. 用户裁定记录（2026-09-09）

1. 直接做 Mine（跳过 Transfer）；先出设计再实施。
2. Mine 逻辑移植清晰、可部分照搬 Baritone；`DropCollectionTask` 按重写。
3. 收集 = 公用子任务 + 按需世界修改权限 + 终点为掉落物位置；best-effort DONE + 摘要；不挖台阶；删反射。
4. Baritone `blacklist`/`coalesce` 留后；验收入口沿用 `target_selector` + `scene_a`。
5. **保留站位/视线/选优挖掘流程**，先用 Movement 兼容到站位；之后再讨论站位选优机制与深埋目标通道策略。
6. 伐木任务与 Mine 一起迁移，`BotMiner` 一次删干净。
