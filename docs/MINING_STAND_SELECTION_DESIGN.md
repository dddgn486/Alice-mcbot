# 挖掘站位选优 + Baritone 融合：新框架设计（草案 v2）

> 状态：**设计草案，待讨论**（2026-09-09）。
> v1 把"4 面站位"当成了通用模型，**用户修正**：4 面站位只适用于**完全掩埋 / 需要挖通道**的目标；
> 能直接挖到的目标要**保留原站位系统的优势**——更多角度、视线检查更真实、提前筛出"可直接挖掘"的站位，
> 这对"是否需要清理方块"的判断有很大价值。本稿按此拆成**两个模式**。

---

## 1. 设计原则（用户给定）

1. **模式 A（直接可挖）= 保留原站位系统**：候选更多（侧面/对角/上下）、视线前提筛选、评分模板排序；
   到达用纯通行寻路，到位后原地挖掘。
2. **模式 B（埋藏 / 需通道）= 4 面 × 2 层 + WALKANDBREAK**：
   候选只有目标 4 个面的相邻格 × {同层, 低一层}；挡路方块由 **WALKANDBREAK** 在寻路中处理，
   **不设独立清障机制**。
3. **不照搬 Baritone 的"强制站到目标上"**：只在模式 B 的极端情况（8 格全不可用）才考虑兜底。
4. 保留服务端权威、确定性执行、掉落物收集链路。

---

## 2. 两个模式

### 2.1 模式 A：直接可挖（保留原站位系统）

| 项 | 内容 |
|---|---|
| 候选 | 原 `StandingPointSelector.generateCandidates`：4 侧面 + 4 对角 × {同层, 上 1, 下 1} + `target.above(2)` + 当前位置（是否扩到 legacy 的 ±4 邻域见 Q2） |
| 前提（硬过滤） | ① `canWalkOn(pos)` + `canWalkThrough(pos)` + `canWalkThrough(pos.above())`；② 非 `target` / `target.above()`；③ **可见面 + 可达**：内缩多面体采样至少一面可见，且该可见采样点在 `bot.getBlockReach()` 内（D-066） |
| 排序 | `StandingPointEvaluator` 模板：`1×距离 + 0.5×高度`（视线已移出评分；是否加新项见 Q6） |
| 到达 | 纯通行 `PathRequest.of`（TRAVERSE/DIAGONAL/ASCEND/DESCEND），`PathRetryRunner` |
| 挖掘 | 站位原地挖（`BlockBreakSession`：工具/进度/广播/ABORT） |
| 适用 | 能从多个角度直接挖到的目标（多数场景） |

**保留的理由（用户）**：多角度 → 更灵活；视线检查 → 更真实（知道"这一面能不能打到"）；
**提前筛出"可直接挖掘"的站位** → 由此判断目标是否需要清理方块。

### 2.2 模式 B：埋藏 / 需通道（4 面 × 2 层 + WALKANDBREAK）

| 项 | 内容 |
|---|---|
| 触发 | 模式 A 找不到任何满足"可见面 + 可达"的站位（= 目标被遮挡/掩埋） |
| 候选 | 目标 4 个面 × {同层 (y), 低一层 (y−1)}，共最多 8 格；排除目标自身、目标正上方 |
| 有效 | **可直接站**（纯通行到达）或 **需破坏进入**（占用块可破坏 + 破后能站 → WALKANDBREAK） |
| 到达 | **WALKANDBREAK**（破坏并进入）；需要把 `BREAK_AND_TRAVERSE` 几何从 dx=2 放宽到 dx∈{1,2}，或新增 `BREAK_AND_ENTER` |
| 挖掘 | 到达后原地挖；若 8 格全不可用，兜底方案见 Q4 |
| 适用 | 完全掩埋、需要挖通道的目标 |

**为什么不设独立清障（用户）**：清障就是"破坏并走进那一格"，WALKANDBREAK 天然覆盖，
不再需要 `findDirectBlocker` + `MAX_CLEAR_DEPTH` 这套运行期特判。

### 2.3 模式切换

- **规划期**：先跑模式 A（`MiningPlanner` 现有流程）；结果为空 → 转模式 B。
- **执行期**：模式 A 的站位到达后若运行期视线复核失败（`RUNTIME_VISIBILITY_FAILED`），
  是否降级到模式 B（而不是旧版"清障后重试"）？见 Q5。

---

## 3. 与 Baritone 的融合点

| 机制 | Baritone | 本框架 |
|---|---|---|
| 目标表达 | `GoalBlock`/`GoalTwoBlocks`/`GoalThreeBlocks`（脚位落在目标列） | 模式 A/B 都保留**站位目标**（`GoalFoot`）；目标列仅在 Q4 兜底讨论 |
| 挡路方块 | `MovementTraverse.positionsToBreak = {dest, dest.above()}`，PREPPING 阶段破坏后进入 | **WALKANDBREAK**（放宽 `BREAK_AND_TRAVERSE` 到 dx=1 或新增 `BREAK_AND_ENTER`） |
| 破坏动作 | `BlockBreakHelper`（左键 + 朝向 + 工具） | 复用 `BlockBreakSession` |
| 挖掘后等待 | `anticipatedDrops` | 公用子任务 `CollectDropsTask`（D-064 §4） |

---

## 4. 组件改造清单（方向性）

| 组件 | 处置 |
|---|---|
| `StandingPointSelector` | **保留**（模式 A 的候选生成 + 前提过滤），可能扩候选集（Q2） |
| `StandingPointEvaluator` | **保留模板**（模式 A 排序）；视线保持"前提"不参与评分 |
| `MiningPlanner` | 增加"模式判定"：A 无解 → B；产出"站位 + 到达方式 + 目标" |
| `BotMiner` | **退役**（破坏动作由 `BlockBreakSession` 承担；伐木一起迁） |
| `MineTask` | 删除独立清障（`findDirectBlocker`/`MAX_CLEAR_DEPTH`）；编排 A/B 两模式 |
| `BREAK_AND_TRAVERSE` | 几何放宽到 dx∈{1,2} 或新增 `BREAK_AND_ENTER`（WALKANDBREAK） |
| `LineOfSightChecker` | 保留（前提校验）；可选增强"可见面计数"（Q6） |

---

## 5. 待讨论的问题

### Q1 模式判定边界
1. "模式 A 无解"的判据：**没有任何站位满足"可站 + 可见面 + 可达"**——这个判据够吗？
2. 还是需要更细的判据（例如"有站位但视线被 1 个可破坏方块挡住"也算模式 B 的输入）？
3. 模式 B 是否也允许"站位视线被挡、但挡块可破坏"的情况（即 B 不要求可见面，只要求到达后可挖）？

### Q2 模式 A 的候选集
1. 保持现有候选（水平最多 1 格）还是扩到 legacy 的 ±4 水平 / −4..+2 垂直？
2. 是否需要把"触及距离"从硬编码 4.5 换成 `bot.getBlockReach()`？
3. 对角线站位保留吗（现状保留）？

### Q3 模式 B 的候选与到达
1. 严格 4 面 × 2 层（8 格），还是也允许"需破坏进入"的相邻格以外的兜底格？
2. WALKANDBREAK 一次只破坏 1 格，还是允许连续破坏（= 挖通道）？
3. 破坏白名单与成本/惩罚如何定？

### Q4 埋藏目标的兜底
1. 8 格全不可用时，是否允许"以目标格为终点 + WALKANDBREAK 走进去"（Baritone `GoalBlock` 式）？
2. 是否设破坏格数 / 额外成本上限（保留 `target_requires_tunnel` 语义）？
3. **深埋矿石算"能挖"还是"拒绝"**——HARD_PATH 红线的核心分歧。

### Q5 模式切换与执行期降级
1. 模式 A 到位后运行期视线失败 → 转模式 B（重新规划）还是直接失败？
2. 模式 B 执行失败（WALKANDBREAK 不可达）→ 是否再降级到 Q4 的兜底？
3. 切换是否计入任务层重试预算（`PathRetryRunner` 的 replans）？

### Q6 模式 A 的排序函数
1. 是否保持 `1×距离 + 0.5×高度`，还是加入"拾取距离 / 安全性 / 可见面数"？
2. 是否修 `LineOfSightChecker.blockerCount` 恒 0/1 并统计可见面数作为 tie-breaker？

### Q7 旧机制处置
1. 确认删除独立清障（`findDirectBlocker`/`MAX_CLEAR_DEPTH`）与 `BotMiner.pickStandCandidates` 整套候选逻辑？
2. `MiningSceneFixture`/`MiningReplanFixture`/`LineOfSightChecker` 是否同步重写？

---

## 6. 验收设想（讨论后细化）

| 场景 | 期望 |
|---|---|
| 露天目标（四面开阔） | 模式 A，多角度候选，选同层/近距离站位，掉落物在脚边 |
| 贴墙目标（1~2 面可达） | 模式 A，选可达面站位 |
| 视线被 1 个方块挡住 | 按 Q1/Q3 裁定：转模式 B（WALKANDBREAK 进入相邻格） |
| 完全掩埋目标 | 模式 B；按 Q4 裁定兜底或失败 |
| 目标上方受限（头位被占） | 模式 A 选低一层站位（目标在头位） |

日志终态设想：`[MineTask] SUMMARY mode=A|B stand=... reach=direct|walkandbreak target=... mined=true collected=1/1`
