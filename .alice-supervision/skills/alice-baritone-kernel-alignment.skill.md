---
name: alice-baritone-kernel-alignment
description: Alice 是 Baritone 兼容内核——非 Alice 目标差异部分一律先对齐 Baritone，禁止自制替代内核与补丁堆叠。
---
# Alice 作为 Baritone 兼容内核

## 何时使用
设计、修复或重构寻路 / 移动 / 路径执行 / 世界交互 / 任务执行之前；以及发现自己正在写"自创机制"、或同一处第二次失败时。

## 核心原则

1. **Alice 的差异只在目标层**：LLM 目标级决策、服务端权威、bot 假人、任务/权限/库存真相、寻路红线（D-076：默认纯通行，世界修改须显式授权 + 预算闸门）。
2. **其余默认对齐 Baritone**：搜索、Movement 原语、执行器状态机、自愈（snipsnap/重规划）、段超时、成本模型、跳跃门控、完成判定——不自己发明。
3. **抄语义，不抄常量**：抄门控条件、超时判据、状态机推进、失败语义；物理常量必须按 Alice 实体实测重标定（D-025：maxUpStep 0.6 vs Baritone 1.0）。
4. **偏离必须登记**：在 `docs/AI_DECISIONS.md` 写 D-0xx，包含 ① 对应的 Baritone 实现（`文件:行`）② Alice 特有约束 ③ 偏离的可验证后果。**没有登记即为违规**。
5. **反补丁红线**：同一处第二次失败时，禁止继续加 `if` / epsilon / 延迟 / 重试 / 特判；改用下方对照表逐项比对，必要时用 `debugging-root-cause-analysis`。禁止为绕开 Baritone 已有机制而自造机制。

## 参考位置（本地克隆，可直接读）

**主树（1.20.1 / v1.10.5 / `8c55ad0`）**：`/home/fb486/projects/reference/baritone-1.20.1/`；辅树（1.21.4 / `64333af`）：`/home/fb486/projects/reference/baritone/`

| 关注点 | Baritone 位置 | Alice 对应 |
|---|---|---|
| 逐段执行状态机 | `src/main/java/baritone/pathing/path/PathExecutor.java:93-250` | `pathing/core/session/PathSession` |
| 段超时判据 | 同上 `:243`（`ticksOnCurrent > 原始成本估计 + movementTimeoutTicks`） | `PathSession.segmentTimeoutTicks()` |
| snipsnap（脚位不在当前段合法位置集 → 前后搜索吸附） | 同上 `:101-124` | `PathSession.trySnipsnap()` |
| 段间推进（SUCCESS 后同 tick `pathPosition++` + `onTick()`，**不松输入、不 settle**） | 同上 `:232-236` | 当前有 settle，见下方差异登记 |
| 合法位置集 | `Movement#getValidPositions` | D-026 合法位置集 |
| Movement 前置/驱动/完成 | `baritone/pathing/movement/Movement*.java` | `pathing/core/*Execution.java` + `*ExecutionFactory` |
| 跳跃门控 | `MovementAscend` | `AscendExecution` |
| 下降触发/settling | `MovementDescend` | `DescendExecution` |
| 成本模型 | `baritone/api/utils/`、`ActionCosts` | `CostModel` |
| 世界修改原语（放置/破坏） | `MovementPlaceAgainst`、`MovementBreak*`、`MovementHelper` | `action/BlockInteraction`、`BlockBreakSession` |

## 现有偏离登记（对照结果）

| 偏离 | 性质 | 理由 / 待办 |
|---|---|---|
| 段间无条件 settle（每段 `stopMovement` + 等落地减速） | 体验 | 视觉顿挫。Baritone 无 settle；方向：仅在需要精确落点（类型切换 / Descend / 放置）时 settle，共线同类型段连续执行 |
| 漂移检测用"低于最低脚位 >1 格 / 离两端 >3 格"距离启发式（D-035） | 临时实现 | 待改为 Baritone 的 `getValidPositions().contains(feet)` 判定 |
| `BREAK_AND_TRAVERSE` 2 格直连 | 目标差异 | Alice 挖矿接口需要；合法位置集必须包含中间格 |
| `maxUpStep = 0.6` | 物理对齐 | D-025；改物理对齐，而非给 Baritone 逻辑打补丁 |
| 多段容差 COLUMN / EXACT（D-027） | 目标差异 | Alice 分段验收需要；最终段必须精确 |
| 任务级 600 tick 兜底 | 安全网 | 段超时为主，兜底仅防会话异常 |
| **水位处理缺失**（D-236，2026-09-15 登记） | **能力缺口** | Baritone **有**两条水位分支：`MovementTraverse.java:88-96`（水里走用 `waterWalkSpeed`/`walkOnWaterOnePenalty`，能穿水）、`MovementFall.java:102`（落点是水 ⇒ 水不摔伤，:103-109 还能放水桶 MLG）。**Alice 两处都没有**：`MovementHelper.java:52/62/179/196`（流体源不算支撑 ⇒ 进水格不是脚位）、`FallExecution.java:186`（落点必须无流体）⇒ **规划不出任何含水路线**（水平）。
**部分已补（D-243 + D-244，2026-09-16）**：水里**垂直**移动已可用 —— `PillarExecution`/`AscendExecution` 在水里改为
**按住跳跃上浮**（陆地那套一次性 `jumpOnce` 在水里抬不到 1 格 ⇒ 实测 `wastedJumpLandings` ⇒ `SEGMENT_NO_PROGRESS`）；
**水柱那支（起点/目的地都是水）连方块都不放**（D-244，对齐 `MovementPillar.java:150-161` + `:77-82` 的
`LADDER_UP_ONE_COST`），完成口径随之改成 Baritone 的"脚位到格即成功"（水里没有 `onGround`/支撑）。
判据 `survival_exit` 的 `FLOODED_SHAFT` 相位（实测整段**零放置**：水柱上浮 + `ASCEND` 跳上干地板）。
**⚠️ 现成方案对照已完成（2026-09-16，用户要求"先查现成方案"）**：Baritone **没有 `MovementSwim`** —— `movements/` 只有 8 个类，水位 = ①`MovementHelper.canWalkOnPosition:432-448`「**下面是水且我这一格也是水 ⇒ 算支撑**」（jesus 关时"上面也是水"才算）+ ②`CalculationContext:135-152 waterWalkSpeed ≈ 2.0× 陆地走`（`WALK_ONE_IN_WATER_COST = 20/2.2`）+ ③**复用 `MovementTraverse`**（`updateState` 里按需 `JUMP`/`SPRINT`，浮着时禁止背放 = `:160-162 COST_INF`）⇒ Alice 的"丙"其实就这三处，**不要新造游泳 Movement**；全文（含外部实现与上游 issue）见 `docs/reviews/2026-09-16-水位处理现成方案对照.md`。
**✅ 切片 A 已完成（D-247，2026-09-16）**：① 实测确认**蹚水（1 格深、脚位是水、底下实心）本来就能走**（新夹具场景 `water_course`：水沟横跨全场 ⇒ 绕不过去；纯通行 5 段全 `COMPLETED`、零写入）；② 补上水位成本 `CostModel.WATER_TRAVERSE_MULTIPLIER = 7.25`（结构抄 `MovementTraverse:87-90`，数值按 Alice 实测 42~45 vs 5~7 tick —— Baritone 的 1.96× 是给客户端游泳的，别照抄）；③ 判据侧的期望值必须是**独立第二来源**（引用同一常量会自指 ⇒ 反向对照不会红，这轮实测踩到）。
**仍未补**：水柱的**成本模型**（Baritone 给 `LADDER_UP_ONE_COST`，Alice 仍算 `PILLAR_COST`）、
**浮在水面（无可站支撑）时起不来**（合法位置集问题，属"丙"其余部分）、水平游/蹚水、落水（D-058 定案不做）。另：`MovementCapabilities.canEnterFluid` 全仓无读者。（**更正**：其中"FALL 无水落地"实为 **D-058 的用户决策**，不是漏登记；"不进水"的精确机制是 `SurfaceMovementProvider.java:127-130` 跳过流体目的格；Baritone 的**垂直**水位能力在 `MovementPillar.java:77-82` 的水柱分支，不是 `MovementAscend`）。**复核触发**：真出现水下作业/掉进深水的真实案例时，先做逐行对照 + **合法位置集/成本模型**影响评估 |

## 验证要求

- 引用 Baritone 时必须给 `文件:行`；只说"参考 Baritone"不算证据。
- 体验类偏离单独记录（不与 bug 修复混做），修复前先登记方向。
