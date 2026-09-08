# MineTask 接入 Movement：最小重建设计

更新时间：2026-09-06
状态：历史方案，已被 D-020 替代；M0/M1/M2 独立实验保留，真实 MineTask Movement 接入已回撤

## 历史文档说明

本文记录旧的 MineTask Movement MVP 实施方案，不再作为当前开发授权。当前架构以 `docs/ALICE_PATHING_CORE_ARCHITECTURE.md`、`docs/ALICE_PATHING_CORE_R1_CONTRACT.md` 和 `AI_DECISIONS.md` 的 D-020 为准。M0/M1/M2 的独立实验结果仍可作为历史证据；M3 真实 MineTask 接入已回撤。

## 1. 目标与边界

目标是在不改变已验收 MineTask A/B/C、目标访问清障、恢复预算和普通挖矿 `HARD_PATH` 边界的前提下，让“从当前脚位到挖掘站位”的通用移动拥有唯一的 Movement 执行契约。

本设计不包含：

- 多 Bot 并行调度；
- 自动挖掘路径障碍、隧道、搭路或传送；
- `SOFT_SURFACE` 接入正式挖矿；
- 删除 `PathExecutor` 或一次性重写所有 Task；
- 改变目标方块访问清障和掉落物收集。

相关 skill：`alice-path-planning-execution-contract`、`alice-task-lifecycle-and-recovery`、`minimal-implementation-planning`。

## 2. 收敛决策

### 规范模型：`List<Movement> + MovementPathExecutor`

Movement 是规划输出的规范执行单位。每个 Movement 明确：

- `from/to` 的脚位语义；
- 专用前置条件和后置条件；
- 成本；
- `RUNNING/SUCCESS/FAILED`；
- 执行期动态阻挡和终态清理责任。

这保留了当前搜索阶段已经使用的 Movement Provider 语义，避免在执行前把 Movement 类型、专用条件和资源/世界修改语义丢失成裸坐标。

### 兼容模型：`List<BlockPos> + PathExecutor`

旧模型保留为迁移兼容后端，暂不删除。它继续支撑当前已验收 MineTask、DropCollectionTask、TransferTask、PlaceTask 和既有回归场景，直到 Movement 后端完成同等客户端闭环证据。

兼容桥只允许做显式的坐标路径投影，不得声称投影后仍保留原 Movement 的专用语义。暂不把旧执行器和新执行器在同一任务实例中交替重试。

## 3. 统一分层契约

```text
MiningPlanner
  -> MiningPlan(target, startFoot, standingFoot, route contract)
  -> Surface/Movement planner: foot -> foot
  -> MovementPlan(List<Movement>, projectedFootPath)
  -> MovementPathExecutor
  -> BotMiner reports movement outcome
  -> MineTask enters MINING or records failure
```

领域层仍负责 `target -> standingFoot`；通用移动层只负责 `startFoot -> standingFoot`。目标访问清障仍由 MineTask/BotMiner 的挖掘领域逻辑负责，不能借 Movement 名义扩大破坏范围。

规划结果采用以下统一事实状态：

- `REACHED`：规划在规则和预算内找到完整路线；
- `UNREACHABLE`：在规则内证明不可达；
- `SEARCH_LIMIT`：预算耗尽，未知；
- `BLOCKED`：执行期环境变化阻挡当前段；
- `INVALID_PRECONDITION`：执行启动前 Movement 专用条件不满足；
- `MOVEMENT_TIMEOUT`：执行无进展超时但没有充分动态方块证据。

规划期状态和执行期失败必须分开记录，不能把 `SEARCH_LIMIT` 映射成 `UNREACHABLE`，也不能把普通执行超时伪装成 `BLOCKED_DYNAMIC`。

## 4. 最小实施阶段

### 阶段 M0：建立规范数据对象（只改契约）

新增不可变 `MovementPlan`/结果对象，至少包含 `startFoot`、`goalFoot`、`List<Movement>`、投影脚位序列、规划状态、成本和搜索诊断；明确每个坐标是脚位而不是支撑方块或目标方块。

暂不修改 MineTask 调度，不删除旧类型。先用构造校验保证 Movement 首段起点、相邻段连接和末段终点一致。

### 阶段 M1：纯 WalkMovement 编译器与独立 focused 入口

已新增 `MovementPlanCompiler`：只把输入路径中的同高度、四向相邻且通过 `MovementHelper.canTraverse` 与 `WalkMovement` 校验的脚位段编译为 `WalkMovement`。空路径、起点不一致、非相邻、非同高度或前置条件失败均显式拒绝。台阶、下降、跳跃、世界修改 Movement 不做隐式降级。实验 3 保持历史连续 WalkMovement 独立测试链；M1 另有 `alice:movement_plan_tester` focused 入口消费该编译器。

用独立测试物品复用实验 2/3/4 的后置条件，验证：实际脚位、支撑、onGround、序列完成、取消/超时清理和动态阻挡结果。

### 阶段 M2：BotMiner 单次移动后端适配

BotMiner 增加一个明确的 Movement 执行模式，仅消费 `MovementPlan`；`MovementPathExecutor` 的终态一次性映射为 BotMiner 级事实报告。失败不在 BotMiner 内自动切换旧执行器，也不自动重规划。

该阶段先用一个受控 MineTask 摆位场景验证“移动成功 -> 才开始挖掘”，不改变目标访问清障。

### 阶段 M3：MineTask 实现真实 `MOVING_TO_POSITION`

MineTask 不再把 `MOVING_TO_POSITION` 当作占位跳板：

1. 规划并保存 `MiningPlan`；
2. 创建/持有当前 Movement 执行器；
3. 逐 tick 等待 `DONE/FAILED`；
4. 仅在服务端确认实际站位满足后置条件时进入 `MINING`；
5. 失败记录 Movement 失败码、实际脚位、阶段和任务实例，不提前挖掘。

目标访问清障触发的临时站位仍作为独立领域动作；完成清障后重新规划或重建对应 MovementPlan，不能复用已过期执行器状态。

### 阶段 M4：受控回归与旧后端退役评估

至少取得 MineTask A/B/C、不可达安全失败、动态执行失败和取消/超时的服务端及 Windows 客户端证据。只有当新后端覆盖现有后置条件、恢复边界和可回收状态后，才讨论默认切换和旧 `PathExecutor` 退役。

## 5. 本设计的第一实现切片

M0/M1 实现切片均保持契约优先：M0 建立不可变 `MovementPlan`，M1 新增纯 `WalkMovement` 编译器和独立 focused 入口；实验 3 保持历史回归入口。M2 新增 BotMiner 的显式 Movement 后端和独立 `alice:movement_plan_mining_tester` 入口；这些切片均不改 MineTask 真实 `MOVING_TO_POSITION`、旧 `PathExecutor` 或多 Bot 调度。M1 已完成客户端验收，M2 已通过 `./gradlew build --no-daemon`，等待 M2 Windows 客户端验证。

## 6. 多 Bot 接口边界

MovementPlan、MovementPathExecutor 和终态记录必须属于单个任务实例，并携带 Bot UUID；不得使用静态共享执行器、全局当前目标或跨 Bot 的无进展计数。本阶段不实现并行调度和多 Bot 验收。
