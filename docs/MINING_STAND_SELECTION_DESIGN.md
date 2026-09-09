# 挖掘站位选优 + Baritone 融合：新框架设计（草案 v1）

> 状态：**设计草案，待讨论**（2026-09-09）。用户裁定：这不是修复而是重新设计；
> 旧站位系统（两套候选、独立清障、遮挡站位被过滤等）已被审查确认有问题。
> 本稿只定"策略骨架 + 待讨论问题"，**不动代码**。

---

## 1. 用户给定的设计原则（不可偏离）

1. **站位集合 = 目标 4 个面的相邻格，垂直放宽一格**：
   - 允许"目标同层"（脚位 y = target.y，目标在躯干/眼位）；
   - 允许"目标在头位"（脚位 y = target.y − 1）；
   - **不允许**站在目标上方（y = target.y + 1）——挖掉自己的支撑；
   - 理由：既安全（不会挖自己脚下、不会悬空），又**离掉落物最近**（挖完掉落物就在脚边/相邻格）。
2. **不设独立的清障机制**：不再有 `findDirectBlocker` + `MAX_CLEAR_DEPTH`；
   挡路的方块由 **WALKANDBREAK**（破坏并进入）在寻路过程中处理。
3. **不照搬 Baritone 的"强制站到目标上"**：只在**没有可用站位**（埋藏目标）时，才允许"以目标格为终点、边走边破坏"作为兜底。
4. 保留 Alice 的"服务端权威 + 确定性执行 + 掉落物收集"链路。

---

## 2. 新站位模型

### 2.1 候选集合（`StandingPointSet`）

对目标 `T = (x, y, z)`，候选脚位：

```
(x±1, y,   z)  (x, y,   z±1)      // 同层（目标在躯干/眼位）
(x±1, y−1, z)  (x, y−1, z±1)      // 低一层（目标在头位）
```

即 **4 个面 × 2 个高度 = 8 个候选**（去重后最多 8 个）。对角、目标正上/正下、目标自身全部排除。

### 2.2 候选的有效性（分两类）

| 类别 | 条件 | 到达方式 |
|---|---|---|
| **可直接站** | `canWalkOn(pos)` + `canWalkThrough(pos)` + `canWalkThrough(pos.above())` | 纯通行（`TRAVERSE/DIAGONAL/ASCEND/DESCEND`） |
| **需破坏进入** | 占用块可破坏（`breakableExplicit`），破坏后该格可站（下方有支撑 + 头部净空） | **WALKANDBREAK**（破坏并进入） |

两类都要求：
- 从该站位（假设眼位）**能打到目标**：`eye → 目标相邻面中心` 距离 ≤ `bot.getBlockReach()`；
- **可见性校验**（廉价兜底）：`LineOfSightChecker` 的目标内缩多面体采样至少一条命中目标
  （4 面相邻格几乎必然通过；保留它用于玻璃/水/特殊方块的异常情况）。

### 2.3 排序（"选优"到底按什么）

用户要求"既安全、又能更快捡到掉落物"。建议排序键（自上而下）：

1. **路径成本**（含破坏成本；不破坏的路线优先）——决定"到达快"；
2. **掉落物拾取距离**（挖完后掉落物落点与站位的水平距离；同层站位通常优于低一层，因为掉落物会落在相邻格而不是掉进坑里）；
3. **安全性 tie-breaker**：优先"不需要破坏方块进入"的站位 → 优先"有退路（相邻可站格）"的站位；
4. （可选）可见面数量（见 §5 Q6）。

> 注意：这与旧系统的"视线权重 10"完全不同——视线是**前提**，不再是排序依据（D-066 已改）。

---

## 3. 与 Baritone 的融合点

| 机制 | Baritone | 本框架 |
|---|---|---|
| 到达站位 | 无"站位"概念，目标即终点 | **保留站位目标**（`GoalFoot` 到站位格），到达用新内核 `PathRetryRunner` |
| 挡路方块 | `MovementTraverse.positionsToBreak = {dest, dest.above()}`，PREPPING 阶段左键破坏后进入 | **WALKANDBREAK**：需要把 `BREAK_AND_TRAVERSE` 的几何从 dx=2 放宽到 **dx∈{1,2}**（dx=1 = 破坏目的地格并进入），或新增 `BREAK_AND_ENTER` |
| 破坏动作 | `BlockBreakHelper`（左键 + 朝向 + 最佳工具） | 复用 `BlockBreakSession`（已与 `BotMiner` 等价） |
| 埋藏目标 | `GoalThreeBlocks/GoalTwoBlocks`（脚位落在目标列） | **兜底**：无可用站位时，以目标格为终点、WALKANDBREAK 走进去 |
| 挖掘后的等待 | `anticipatedDrops`（等掉落物刷新） | 交给公用子任务 `CollectDropsTask`（D-064 §4） |

---

## 4. 组件改造清单（方向性，不含实现细节）

| 组件 | 处置 |
|---|---|
| `StandingPointSelector` | **重写**：候选集合改为 §2.1 的 8 格；有效性判定改为 §2.2 两类 |
| `StandingPointEvaluator` | **重写排序**：§2.3 的排序键；视线不再参与评分 |
| `MiningPlanner` | 产出"站位 + 到达方式（直接站 / 需破坏进入）+ 目标"；埋藏时产出兜底目标 |
| `BotMiner` | **退役**（用户已裁定伐木一起迁）；破坏动作由 `BlockBreakSession` 承担 |
| `MineTask` | 删除独立清障（`findDirectBlocker`/`MAX_CLEAR_DEPTH`）；改为"选站位 → 到达 → 挖 → 收集" |
| `BREAK_AND_TRAVERSE` | 几何放宽到 dx∈{1,2}（或新增 `BREAK_AND_ENTER`），供 WALKANDBREAK 使用 |
| `LineOfSightChecker` | 保留（作为前提校验），可增强为"可见面计数"（见 Q6） |

---

## 5. 待讨论的问题（**必须先定，再动手**）

### Q1 站位集合的边界
1. 是否严格只要 **4 面 × 2 层**（共 8 格）？对角线站位彻底不要？
2. "需破坏进入"的站位（WALKANDBREAK）是否算合法站位？
3. 是否允许"脚位 = target.y + 1"（站在目标上方）在**没有其它站位**时作为兜底？用户当前裁定：不允许。

### Q2 WALKANDBREAK 的语义与边界
1. 用 **放宽 `BREAK_AND_TRAVERSE` 到 dx=1**，还是新增 `BREAK_AND_ENTER`？
2. WALKANDBREAK **一次只破坏 1 格**，还是允许连续破坏（等价挖通道）？
   ——这直接决定"是否等于自动挖隧道"，与 HARD_PATH 红线相关。
3. 破坏对象的白名单：只破坏可挖方块（`breakableExplicit` + 成本有限），还是也允许放置（PLACE_STEP）？
4. 破坏成本如何计入路径（沿用 `estimateBreakTicks`），是否需要"破坏惩罚"以避免乱挖。

### Q3 埋藏目标的兜底
1. 当 8 个站位全部不可用（占用块不可破坏 / 破坏后仍不可站）时：
   **以目标格为终点 + WALKANDBREAK 走进去**是否允许？
2. 若允许，是否设"最大破坏格数 / 最大额外成本"上限（保留 `target_requires_tunnel` 语义）？
3. 深埋矿石（需要挖一条通道）到底算"能挖"还是"拒绝"——这仍是 HARD_PATH 红线的核心分歧（D-024/D-036）。

### Q4 挖掘动作与掉落物
1. 站位挖掘是否需要"潜行"（防掉落/防滑）与固定朝向？
2. 挖完先"原地等 20~40 tick 让掉落物落地"（Baritone `anticipatedDrops` 思路）再交给 `CollectDropsTask`，
   还是立刻转收集？

### Q5 与 Baritone 融合的边界
1. **目标表达**：保持 Alice 的"站位目标"，还是也引入 Baritone 的 `GoalTwoBlocks/GoalThreeBlocks`（目标列）作为兜底？
2. **可挖性判定**：Alice 的"站位 + 触及 + 可见面"与 Baritone 的"Movement 前置 + 挖掘成本"如何分工？
   是否把 Alice 的判定收窄为"站位可达 + 面触及"，其余交给 Movement？

### Q6 排序函数
1. 排序键的优先级：路径成本 / 拾取距离 / 安全性，谁先谁后？
2. 是否把"可见面数量"（0..7 采样点命中数）作为 tie-breaker？（需要把 `LineOfSightChecker` 的
   `blockerCount` 恒 0/1 的问题一并修掉）

### Q7 旧机制的处置
1. 删除独立清障（`findDirectBlocker`/`MAX_CLEAR_DEPTH`）与 `BotMiner.pickStandCandidates` 整套候选逻辑，
   只保留一套站位集合——是否确认？
2. `MiningSceneFixture` / `MiningReplanFixture` / `LineOfSightChecker` 等测试夹具是否需要同步重写？

---

## 6. 验收设想（讨论后细化）

- 场景一：**露天目标**（四面开阔）→ 应选同层站位、直接走位、挖掉、掉落物在脚边；
- 场景二：**贴墙目标**（只有 1~2 个面可达）→ 应选可达面站位；
- 场景三：**站位被占**（相邻格是泥土）→ WALKANDBREAK 破坏进入后挖掘；
- 场景四：**埋藏目标**（四面包裹）→ 按 Q3 裁定，要么兜底挖进去，要么明确失败；
- 场景五：**目标上方有方块**（头位空间受限）→ 应选低一层站位（目标在头位）。
- 日志终态：`[MineTask] SUMMARY stand=... reach=direct|walkandbreak target=... mined=true collected=1/1`。
