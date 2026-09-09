# Mine 迁移设计（草案，待用户确认后实施）

> 状态：**设计草案**（2026-09-09）。用户裁定：① 直接做 Mine（先恢复核心玩法）；② 先出设计再实施；
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

## 2. 目标与边界

**目标**：把**移动/寻路**整体换成新内核（R3/R4），保留**破坏动作**与**任务编排语义**；`HARD_PATH` 红线不变。

| 层 | 处置 | 说明 |
|---|---|---|
| 任务层 `MineTask` | 改 | 编排不变（评估站位 → 走到站位 → 挖掘 → 收集）；失败分类/清障保留；寻路调用换新内核 |
| 站位层 `StandingPointSelector/Evaluator` | 保留（可简化） | 候选站位 + 视线/距离评分；可达性试算由新内核完成 |
| 动作层 `BotMiner` | **只删寻路** | 保留：破坏进度、工具选择、广播、视线校验、`PlanInvalidation`；删除：`SurfacePathfinder`/`PathExecutor`/`MovementPlan*` |
| 寻路层 | **换新内核** | `CorePathPlanner` + `PathRetryRunner` + `PathSession`（`PathRequest.of` 纯通行） |
| 收集层 `DropCollectionTask` | **重写** | 新的 `CollectDropsTask`，见 §4 |

---

## 3. Mine：照搬 Baritone 的部分

Baritone 参考：`reference/baritone-1.20.1` `process/MineProcess.java`。

| Baritone 机制 | 行号 | Alice 现状 | 建议 |
|---|---|---|---|
| 目标集合 → `GoalComposite` + `coalesce`（`GoalTwoBlocks`/`GoalThreeBlocks`） | `:176-334` | 单目标 + 精确站位 `GoalFoot` | **暂不照搬**（Alice 是单目标挖掘，站位评分已覆盖）；登记为后续可选对齐 |
| `blacklist`（不可达目标加入黑名单） | `:60,120` | 无（失败即升级） | **可照搬**：同一目标反复失败 → 记黑名单并上报 |
| `mineGoalUpdateInterval` 周期刷新目标集合 | `:105-120` | 不适用（单目标） | 不做 |
| 挖掘后等待掉落物刷新（`anticipatedDrops`） | `:120` | `captureWait` 30 tick | 重写收集任务时统一 |
| 挖掘失败 → `failed` 状态上报，不自行开隧道 | `:489+` | `target_requires_tunnel`（HARD_PATH） | **保持**（这是 Alice 的硬红线） |

**结论**：Mine 的迁移只做"寻路换内核 + 失败码/重试对齐 + （可选）黑名单"，不做目标合并。

---

## 4. 收集任务重写设计（`CollectDropsTask`）

### 4.1 职责单一化
只做一件事：**把指定来源（origin）产生的掉落物捡起来**。不挖方块、不改世界。

### 4.2 输入与生命周期
- 构造：`CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope, List<UUID> expectedIds)`；
  由 `MineTask` 在挖掘完成后创建，`expectedIds` = 挖掘前/中登记的掉落物 UUID（可为空 → 走"范围内扫描"）。
- 结束条件（**best-effort**）：范围内目标掉落物全部消失（被拾取/合并/移除）或全部标记为不可达/超时。

### 4.3 算法（每 tick）
1. **刷新候选**：`scope.liveItemsFromOrigin(origin)` 中仍在的掉落物；优先 `expectedIds`，其余按"新出现且在半径内"纳入。
2. **选目标**：最近优先 + 粘滞（同一目标连续追，除非它消失/超距/超时）。
3. **定位站位**：目标方块位置 `p`；若 `p` 可站 → 站位 = `p`；否则取 `p` 的相邻可站格（同层优先，向下 1~2 层），仍无 → 标记不可达。
4. **移动**：`PathRetryRunner` + `PathRequest.of`（纯通行，不挖不放）走到站位；失败 → 标记该物品不可达（**不挖台阶**）。
5. **拾取**：站到目标 1 格内后**等待自然拾取**（原版拾取延迟默认 10 tick + 余量，最多 `PICKUP_WAIT_TICKS = 40`）；不做反射、不调 `playerTouch`。
6. **收尾**：所有目标结束 → 输出 `[CollectDrops] SUMMARY collected=n/m unreachable=k ticks=...`，返回 `DONE`。

### 4.4 失败语义（待确认）
- 建议：**best-effort** —— 收集不到不判 FAILED，只记日志 + 摘要（`collected=1/3 unreachable=2`）。
  理由：掉落物可能被岩浆烧掉/被别的实体捡走/掉进深坑，不应让整次挖矿失败。
- 备选：`MineTask` 可配置"必须收全"（严格模式），默认关。

### 4.5 明确删除的 legacy 行为
| 行为 | 处置 | 理由 |
|---|---|---|
| 反射清 `pickupDelay` + `playerTouch` | **删除** | 侵入原版状态；等待即可 |
| 收集阶段挖台阶（`BotMiner`） | **删除** | 收集任务不得改世界；不可达就放弃该物品 |
| `abandoned` / `retries` / `captureWait` / `waitTicks` 多套计数 | **删除** | 统一为"每物品预算 + 任务总预算" |
| `pos.above()` 兜底 / `PathExecutor(List.of(pos))` 特例 | **删除** | 由站位解析统一处理 |
| 400 tick 全局超时 | **保留但参数化** | 改为"总预算 + 每物品预算" |

---

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

## 9. 待用户裁定的问题

1. **收集失败语义**：best-effort `DONE` + 摘要（建议）？还是必须收全否则 FAILED？
2. **收集时是否保留"挖台阶"**：建议 v1 不做（不可达就放弃并记录）。
3. **`pickupDelay` 反射清理**：建议删除，改为等待自然拾取。
4. **Baritone `blacklist` / 目标合并 `coalesce`**：现在做还是留到后续对齐批次？
5. **验收入口**：沿用 `alice:target_selector` 右键，还是新增专门的挖矿自检物品（带 SUMMARY）？
