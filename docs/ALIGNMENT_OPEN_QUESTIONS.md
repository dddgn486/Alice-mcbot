# 内核对齐待决问题（专项讨论）

- 日期：2026-09-09
- 依据：`docs/R4_BARITONE_ALIGNMENT_AUDIT.md`（64 项对照）、用户 2026-09-09 指定「先对齐 Baritone」
- 状态：**讨论稿**，尚未实施（除 Q2/Q3 用户已授权尝试）

---

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

## Q2/Q3：控制相位 1 tick 对齐（用户已授权尝试）

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

### 2.4 风险与验证

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

### 7.2 具体差异

| 动作 | Alice（相对走路） | Baritone（相对走路，tick 制） | 实测 tick（2026-09-09 日志） |
|---|---|---|---|
| TRAVERSE | 1.00 | 1.00 | 6 |
| DIAGONAL | 1.41 | 1.41 | — |
| ASCEND | 2.00 | 1.43（`max(JUMP,WALK)+jumpPenalty`） | ~11 |
| **DESCEND** | **1.00** | **2.01**（`WALK_OFF + FALL_1`） | **15** |

Alice 现在认为"下降最便宜"，但它自己实测下降要 2.5 倍走路时间——**模型与物理自相矛盾**。

### 7.3 会产生什么可观察变化

场景：目标在脚下偏一侧，中间有一条同层绕路。

- 现状：下降 1.0 < 绕路 2.0 → 选下降；
- 对齐后：下降 2.01 > 绕路 2.0 → 选绕路（多走一格，但**实际更快**）。

所以"路线变了"= 规划器开始按真实耗时选路，不是回归。

### 7.4 验收标准（必须先定义，否则会被当成 bug）

1. 所有既有场景仍 `COMPLETED`；
2. 同一场景**总 tick 不劣化**（±10% 内，理想下降）；
3. 无新增危险/悬空/卡边（真人观察）；
4. 启发式与成本同标定（当前 `GoalFoot.heuristic` 对下降边高估 1.0 → 不可采纳）。

### 7.5 与 Q4 的耦合（顺序）

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
