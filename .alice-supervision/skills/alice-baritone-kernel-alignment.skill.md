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

## 验证要求

- 引用 Baritone 时必须给 `文件:行`；只说"参考 Baritone"不算证据。
- 体验类偏离单独记录（不与 bug 修复混做），修复前先登记方向。
