# 内核对齐待决问题（专项讨论）

- 日期：2026-09-09
- 依据：`docs/R4_BARITONE_ALIGNMENT_AUDIT.md`（64 项对照）、用户 2026-09-09 指定「先对齐 Baritone」
- 状态：**讨论稿**，尚未实施（除 Q2/Q3 用户已授权尝试）

---

## §0 背景：Baritone 的决策机制（面向非算法读者）

Baritone 把「怎么走」拆成四层，每层只回答一个问题：

| 层 | 回答的问题 | 关键类 | Alice 对应 |
|---|---|---|---|
| 目标层 | **要达成什么** | `Goal` + 各种 process（MineProcess/Goto…）+ `PathingControlManager` 按优先级选当前 process | 任务层 / LLM 目标层（Alice 的差异） |
| 搜索层 | **走哪条路** | `AStarPathFinder` + `Moves` + 各 Movement 的 `cost()`（单位=tick） | `CorePathPlanner` + `CostModel` + `SurfaceMovementProvider` |
| 执行层 | **这一步怎么动** | `PathExecutor` + 每个 Movement 的状态机 `update()` | `PathSession` + `*Execution` |
| 输入层 | **变成按键** | `InputOverrideHandler`（每 tick 重设按键，完成时 `clearAllKeys`） | `bot.controller()` |

关键认知（对非算法读者最重要的一点）：

- **A* 不是"聪明"的算法，它只是在最小化一个成本函数。** 所谓"路线选择"完全由成本函数定义：
  你告诉它"下降很便宜"，它就会尽量往下走。所以调成本 = 调行为。
- **启发式（heuristic）只是加速用的估计**，但有个硬约束：估计值不能大于真实剩余成本（可采纳），
  否则 A* 会返回次优路径。
- **执行层是确定性的**：不搜索、不决策，只按状态机把当前这一步做完，并报告成功/失败。
- **自愈分两种**（很多人会混淆）：
  1. bot 偏离当前步、但**计划仍有效** → 重同步（Baritone `snipsnapifpossible` / 段内前后搜索）；
  2. **计划本身失效**（世界变了/被挡/超时）→ 执行器 `cancel()` 上报，由**上层**重新规划
     （Baritone 是 `PathingBehavior`；Alice 里对应任务层/LLM 层）。

Alice 的定位（D-036）：**目标层是 Alice 的差异（LLM/服务端权威），搜索层与执行层对齐 Baritone。**

---

## §8 各方案对项目目标的影响

项目目标：**G1 服务端权威 · G2 可回收/有终态 · G3 LLM 只做目标级决策 · G4 Baritone 兼容内核 · G5 可测试**

| 方案 | 对 G1 | 对 G2 | 对 G3 | 对 G4 | 对 G5 | 实现量 |
|---|---|---|---|---|---|---|
| Q1① 把 1 格红线写成可回收性不变式 | — | **++**（把隐性保证显性化） | — | +（登记差异） | — | 文档 |
| Q1② 只读统计被拒原因 | — | +（用数据决定） | — | — | + | 小 |
| Q1③ FALL/PARKOUR 登记"暂不采纳" | — | + | — | + | — | 文档 |
| Q1-D 移植 MovementFall + 可回收守卫 | — | 保持 | — | + | 中 | **中+** |
| Q3 相位改 Phase.START | +（输入同 tick 生效） | +（过冲变小） | — | **++**（Baritone 常量可直接用） | 需重测 | 一行 + 重测 |
| Q4 重规划下沉 | — | +（失败有终态且可观测） | **++**（决策回到决策层） | +（对齐分层） | + | 小 |
| Q7 成本模型按实测标定 | — | — | — | + | 需重测 | 小（4 个常量 + 启发式） |

## Q1：D-024 一格红线对「可回收性」的作用，与替补方案

### 1.1 先把「可回收性」钉死

- **局部可回收**：执行一条 Movement 后，**不依赖全局搜索**就能回到执行前的状态。
  形式化：若边 `u→v` 被执行，则存在逆边 `v→u` 属于允许的 Movement 集合，且其前置条件在 `v` 处已成立。
- **全局可回收**：从当前位置重规划仍能到达目标（依赖搜索、预算和地形）。

D-024 真正保护的是**局部可回收**。

### 1.2 一格红线 ⇒ 局部可回收（可逐类型验证的命题）

| Movement | 逆边 | 逆的前置条件在落点是否成立 |
|---|---|---|
| TRAVERSE `u↔v` | 同类型反向 | ✓ 对称 |
| DIAGONAL `u↔v` | 同类型反向 | ✓ 对称 |
| ASCEND `u→v(+1)` | DESCEND `v→u` | ✓ `u` 是刚站过的脚位（可站）、空间刚被占用（可通） |
| DESCEND `u→v(−1)` | ASCEND `v→u` | ✓ 同上 |
| PLACE_STEP `u→v(0/−1)` | ASCEND `v→u` | ✓ `u` 可站（放置的是 `v` 下方支撑） |
| BREAK_AND_TRAVERSE `u→w(2格)` | TRAVERSE `w→mid→u`（两步） | ✓ 中列已破坏，两步都是普通通行 |

**结论：单次落差 ≤1 格时，每条被执行的边都有局部逆边**——bot 永远能撤回上一步，不需要搜索。

### 1.3 ≥2 格落差为什么打破这个性质

- `u→v` 落差 2 时，逆边需要 ASCEND `v→(v+1)`，而 `v+1` 正是 `u−1`：**要求悬崖腰部存在可站的中间台阶**。悬崖边通常没有 → 逆边不存在 → 只能依赖全局重规划。
- **实证**（2026-09-09，`r4-session-cb23787c`）：bot 被扰动掉进 x=2..3 的 4 格缺口后，重规划 `UNREACHABLE`，空跳 17 次不终止——这就是「局部不可回收 + 全局不可达」的现场。
- 物理层面：落差越大，落地水平滑移越大，落点不确定性上升；D-024 的过冲列规则（同层或低 1 格）本来就是按 1 格落差设计的。

### 1.4 Baritone 的 3 格容差不能只抄数字

`maxFallHeightNoWater = 3`（`Settings.java:557`）与 `MovementFall` 是**成套**的：

- `MovementFall` 自己建模下落轨迹，并把落点列纳入 `getValidPositions()`；
- `PathExecutor.possiblyOffPath:317-323` 对 MovementFall **特例改用水平距离**判定（因为下落过程中垂直方向必然"远离路径"）。

只放宽阈值而不移植 `MovementFall`，会导致"下落中"被判为偏离路径 → 误报漂移/失败。**要么保持 1 格，要么整套移植。**

### 1.5 替补方案对比

| 方案 | 局部可回收 | 实现量 | 风险 | 与 Baritone |
|---|---|---|---|---|
| **A 保持 1 格**（现状） | ✓ 成立 | 0 | 部分地形判 `UNREACHABLE` | 目标差异（需登记） |
| B 放宽到 2 格 | ✗ 不成立 | 小（新增 dy=−2 分支） | 与 3 格同级风险，收益小（2 格落差地形少） | 偏离 |
| C 移植 `MovementFall`（≤3） | ✗ 依赖全局重规划 | 中（新 Movement + provider + PathExecutor 特例） | 需重测全部场景；落点危险/伤害校验 | 对齐 |
| **D 移植 `MovementFall` + Alice 可回收性守卫** | ✓ 构造保证 | 中+ | 更复杂；可能出现"能下去但不许下去"的保守拒绝 | 对齐 + 登记 Alice 差异 |

D 的守卫具体化：只有**从落点 `v` 用 ≤1 格 Movement 集合能回到源列**（或回到计划后续段）时才允许该 FALL 边——
即「下落是捷径，不是单程票」。实现上对每个 FALL 候选跑一次受限小搜索（小预算 + 缓存）。

### 1.6 建议

1. **短期保持 A**，但把 D-024 的表述从"安全策略"升级为**可回收性不变式**（把 1.2 的命题写进决策文档）。
2. **是否放宽由数据决定**：统计真实任务里 `DESCEND_REJECTED_*` 与 `UNREACHABLE` 的成因占比；若"因 1 格红线被拒"比例很低，就不值得做 C/D。
3. 将来要做就做 **D**（整套 + 守卫），**不要做 B**（半套：既没有 Baritone 的配套机制，又失去局部可回收）。

**待你决定**：① 是否把「1 格红线 = 可回收性不变式」写进 D-024；② 是否先加**只读统计**量化"1 格红线导致多少 UNREACHABLE"；③ 是否把 FALL/PARKOUR 正式登记为「暂不采纳」。

---

## Q2/Q3：控制相位 1 tick 对齐（**已实施 D-038，待客户端验证**）

### 2.1 事实链（已核实）

- `BotManager.onServerTick:548-559`：`if (event.phase != TickEvent.Phase.END) return;` → **任务/执行器全部在 `Phase.END` 驱动**。
- `BotPlayer.tick()`：`controller.onUpdate()`（读输入）→ `super.tick()` → `aiStep()`（物理）。
- 因此：`END(N)` 做出的决策由 **tick N+1** 的物理消费 = **1 tick 延迟**。
- Baritone：`InputOverrideHandler` 在客户端 tick（实体 tick 之前）设置输入 → **同 tick** 物理消费 = 0 延迟。

### 2.2 方案

| 方案 | 机制 | 延迟 | 风险 |
|---|---|---|---|
| **(a) 驱动改 `Phase.START`**（推荐） | `START(N)` 决策 → tick N 物理消费 | 0 | 低（信息新鲜度不变，仅应用提前 1 tick） |
| (b) 在 `BotPlayer.tick()` 内、`super.tick()` 前驱动 | 每 bot 独立、同 tick 消费 | 0 | 高（在实体迭代中跑任务逻辑，重入/世界修改风险） |
| (c) 保持现状 | — | 1 | 0（但要为 1 tick 延迟标定所有常量） |

**(a) 的关键点**：`Phase.START` 看到的仍是"上一次物理完成后的状态"，与现在完全一致；区别只是**输入在同一个 tick 内被消费**。

### 2.3 预期收益（可量化）

- 每个 Movement 少 1 tick：6 tick 的 TRAVERSE ≈ −17%；15 tick 的 DESCEND/PLACE ≈ −7%。
- 制动早 1 tick → 过冲/滑移窗口变小（D-024 过冲规则更稳）。
- Baritone 的跳跃门控阈值（`flatDistToNext ≤1.2`、`sideDist ≤0.2`）与 settling 判据更可移植。

### 2.4 实施状态（2026-09-09）

- 已改 `BotManager.onServerTick` 为 `Phase.START`（D-038）；`segment_done` 增加 `ticks=` 遥测。
- 待测：`pathing_battery` / `pathing_session` / `pathing_breaker` / `pathing_placer` / `pathing_disturber` 全量回归，
  并与基线（TRAVERSE 6 / DIAGONAL 8 / ASCEND 10 / DESCEND 16 tick）对比。

### 2.5 风险与验证

- D-026/D-027 的容差与 settling 判据是在"1 tick 延迟"下调出来的 → 必须重跑：`pathing_battery` 8/8、`pathing_session`、`pathing_breaker`、`pathing_placer`、`pathing_disturber`。
- `MiningSceneFixture`/`MiningReplanFixture` 也监听 `Phase.END`，但它们是**观察者**，不驱动物理 → 保持 END 即可。
- 改动量：`BotManager.onServerTick` 一行 + `SurvivalSystem.tick` 语义确认。
- 回退：一行切回 END。

---

## Q4：replan 归属（重述，说清"为什么"）

### 4.1 现在发生了什么

`PathSession.handleFailure(code)` 同时做三件事：

1. `trySnipsnap`：bot 偏离当前段、但**计划仍然有效** → 执行层重同步；
2. `replan()`：**计划本身失效**（世界变了/被挡/段超时）→ 从当前脚位重新搜索，最多 2 次；
3. 都不行 → `mapFailure` 上报。

### 4.2 为什么"重规划"不属于会话（三条具体理由）

1. **职责边界**：`PathSession` 自己的 javadoc 写着"不替任务决策"。重试/放弃/换目标属决策层（Alice 的任务层/LLM 目标层）。Baritone 同样分层：`PathExecutor` 只 `cancel()`，由 `PathingBehavior:154-193` 重新 `findPathInNewThread`。
2. **不可观测**：任务层现在只看到最终结果，分不清"重规划 1 次后成功"与"一次都没重规划就失败"，也无法据此施加策略（改挖矿、换目标、上报 LLM）。
3. **策略写死**：`MAX_REPLANS=2` 硬编码；赶路应该早失败，挖矿可以多试。

### 4.3 提议

- `PathSession` 只保留 **resync（snipsnap）+ 结构化失败上报**（`status` / `failureCode` / `failureSegment` / 当前脚位 / 已执行段数）。
- 新增任务层 `PathRetryPolicy`：收到 `STALE`/`TIMEOUT`/`BLOCKED` → 用**同一 goal** 重新 plan，次数与预算由任务决定，结果写入 `TaskExecutionRecord`（可观测）。
- 兼容：诊断任务用"最多 2 次"的默认策略，行为不变。
- 工作量：小（移动约 20 行 + 策略类 + 诊断任务适配）。

---

## Q7：成本模型为什么"改完路线会变"

### 7.1 原理

**成本模型就是规划器的目标函数**：A* 选"总成本最小"的路径，权重即偏好。改权重 = 改偏好。

### 7.2 具体差异（**已用 Alice 自己的实测数据标定**）

实测来源：`.alice-supervision/client-tests/pathing-r3-battery-20260908/evidence/r3-battery-key-lines.log`
（R3 电池，2026-09-08；段耗时 = `segment_start` → `segment_done`）。

| 动作 | 实测 tick | 实测相对走路 | Alice 现模型 | Baritone 结构 | 偏差 |
|---|---|---|---|---|---|
| TRAVERSE | 6.0 | 1.00 | 1.00 | 1.00 | ✓ |
| DIAGONAL | 8.0 | 1.33 | 1.41 | 1.41 | 略贵 6% |
| ASCEND | 10.0 | **1.67** | 2.00 | 1.43 | 贵 20% |
| **DESCEND** | **16.0** | **2.67** | **1.00** | 2.01 | **便宜 167%** |

**重要修正**：不能照抄 Baritone 的数值（它的 `WALK=4.633 tick/格` 是按真实玩家 4.317 格/秒标定的；
Alice 假人实测 6 tick/格）。正确做法是 **抄 Baritone 的公式结构，用 Alice 实测值代入**（D-036 规则 3）。

结论：当前模型最大的错误不是"上升太贵"，而是**下降被当成免费**（实测它是最贵的动作，比走路贵 2.67 倍）。

### 7.3 会产生什么可观察变化

场景：目标在脚下偏一侧，中间有一条同层绕路。

- 现状：下降 1.0 < 绕路 2.0 → 选下降；
- 对齐后：下降 2.01 > 绕路 2.0 → 选绕路（多走一格，但**实际更快**）。

所以"路线变了"= 规划器开始按真实耗时选路，不是回归。

### 7.4 三个可选方案

| 方案 | 做法 | 影响 | 工作量 |
|---|---|---|---|
| **Q7-b 按实测重标定（推荐）** | 保持归一化单位（走路=1.0），把权重改成实测值 1.00 / 1.33 / 1.67 / 2.67；破坏成本除数由 `/20` 改 `/6`（=走路 tick 数）；启发式同步换标定 | 路线偏好立刻变正确；不动超时公式结构 | 小（4 常量 + 启发式 + 注释） |
| Q7-a 全 tick 制 | 所有成本改成 tick，超时公式改 Baritone 的 `cost + 100` | 最贴近 Baritone 语义，但改动面大（含放置/破坏/超时） | 中 |
| Q7-c 不动 | — | 规划器继续偏好下降、低估挖掘 | 0 |

### 7.5 验收标准（必须先定义，否则会被当成 bug）

1. 所有既有场景仍 `COMPLETED`；
2. 同一场景**总 tick 不劣化**（±10% 内，理想下降）；
3. 无新增危险/悬空/卡边（真人观察）；
4. 启发式与成本同标定：`h = octile(dx,dz)*1.0 + |dy|*1.67`（取上升/下降中较小者 → 可采纳）；
   或按 Baritone `GoalYLevel` 用方向相关值（更紧，但需要论证可采纳性）。

### 7.7 启发式对照：Q7 必须同时改启发式

| 维度 | Alice 现状 | Baritone | 影响 |
|---|---|---|---|
| 水平形状 | 欧氏 `sqrt(dx²+dz²)` | **octile** `max + (√2−1)·min`（`GoalXZ.calculate:97-116`） | 欧氏对混合位移低估约 7% → 可采纳但更松 → 多展开节点 |
| 水平单价 | 隐含 1.0/格 | `costHeuristic = 3.563`（`Settings.java:434`）= 冲刺单价 `20/5.612` | Baritone 故意取**最便宜移动**的单价来保证可采纳 |
| 垂直项 | 对称 `|dy| × 1.0` | **非对称**（`GoalYLevel.calculate:49-59`）：下降 `FALL_2/2 = 3.894`/格、上升 `JUMP = 3.163`/格 | 下降比上升贵，反映真实物理 |
| 组合方式 | 欧氏 + \|dy\| | Y 项 + XZ 项相加（`GoalBlock.calculate:108-118`） | — |
| 加权 A* 系数 | `{1.5, 2, 2.5, 4.5}` | `{1.5, 2, 2.5, 3, 4, 5, 10}`（`AbstractNodeCostSearch:69`） | 质量/速度权衡 |

**可采纳性验算（当前）**：`p=(0,64,0)` → `goal=(1,63,0)`。`h(p)=1+1=2.0`，而一步 DESCEND 成本 1.0
→ h 高估 1.0 → **不可采纳** → A* 可能返回次优路径。

**Q7 标定后是否自动修好？** 不完全：

| 动作 | 标定后成本 | h 每步减少 | 是否可采纳 |
|---|---|---|---|
| TRAVERSE | 1.00 | 1.00 | ✓ |
| DESCEND | 2.67 | 2.00 | ✓（修好） |
| DIAGONAL | 1.33 | 1.414 | ✗ 仍高估 0.08/步 |
| ASCEND | 1.67 | 2.00 | ✗ 仍高估 0.33/步 |

→ **所以 Q7 不能只改成本，必须同时改启发式形状。**

**Q7 之后的启发式（推荐，结构同 Baritone、单价用 Alice 实测值）**：

```
h = 1.00 × (max(|dx|,|dz|) − min(|dx|,|dz|))   // 直线段单价 = TRAVERSE
  + 1.33 × min(|dx|,|dz|)                       // 对角段单价 = DIAGONAL
  + (dy > 0 ? 1.67 : 2.67) × |dy|               // 非对称竖向 = ASCEND / DESCEND
```

- 无障碍时这就是该 Movement 集合下的**精确距离** → 可采纳且一致（A* 最优）；
- Baritone 用单一 `costHeuristic` 乘 octile；Alice 把 cardinal/diagonal 单价分开会更紧
  （因为 Alice 的 DIAGONAL 1.33 ≠ √2×1.00 = 1.414）。

### 7.6 与 Q4 的耦合（顺序）

成本变了 → 重规划会选不同路线 → 需要 Q4 的结构化结果观察"重规划几次、每次为什么"。
**先 Q4（可观测），再 Q7（目标函数）。**

---

## 附：已同意的项（待实施）

| 项 | 内容 | 备注 |
|---|---|---|
| ② | `canWalkOn` 混合判定：整格形状 OR 白名单 OR（碰撞非空且非已知坏方块） | mod 友好 + 对齐语义 |
| ⑤ | 删除空接口 `WorldView` | R7 立项时再按 `BlockStateInterface` 落地 |
| ⑥ | legacy 双内核收口延后 | 先对齐新内核 |
| ⑧ | 参考仓库切到 1.20.1 分支 + 更新 commit pin | 当前是 1.21.4 树 |
| ⑨ | `canSweepPlayer` 夹具对比 | 静态无法判定 |
