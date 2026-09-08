# Alice Pathing Core Architecture

> 状态：架构重规划基线；仅定义边界与迁移方向，不授权立即接入 MineTask。
>
> 更新时间：2026-09-07
>
> 参考：Baritone 的 [MineProcess](https://github.com/cabaletta/baritone/blob/e962c395067db099c46f6e9a0f67cef48b4a02fb/src/main/java/baritone/process/MineProcess.java)、[Movement 类型](https://deepwiki.com/cabaletta/baritone/4.2-movement-types)、[PathExecutor](https://github.com/cabaletta/baritone/blob/d3c170afaec71d7101c7c5a5a7b4ac374830b66/src/main/java/baritone/pathing/path/PathExecutor.java)。

## 1. 总目标

Alice 的目标不是继续维护一套“能走到目标”的简化 A*，而是构建一个 **Alice 版 Baritone Pathing Core**：

```text
Baritone 级路径计算与 Movement 执行内核
+ Alice Bot 可回收性策略
+ Alice 生存系统兜底接口
+ Alice 任务层失败交接
+ Alice 决策层/LLM 重试与接管
+ 本地行为包与权限策略适配
```

不以性能或 Movement 数量超过 Baritone 为目标；目标是比直接依赖 Baritone 更好地表达 Alice 的 Bot 生命周期、任务包、行为包、可回收性和失败事实。

## 2. 不可混淆的职责层

```text
Decision / LLM
  选择目标、预算、策略与是否授权特殊能力
        ↓
Domain Task
  MineTask、PlaceTask、RoadBuildTask 等拥有领域目标与任务生命周期
        ↓ PathRequest
Pathing Core
  搜索 Movement 路线、成本、策略约束、路径有效性
        ↓ PathPlan / PathSession
Movement Executor
  执行单个 Movement、重验前置/后置、清理输入、报告事实
        ↓
Server Bot / World
```

核心关系：

- Domain Task **拥有领域意图**；
- Pathing Core **拥有到达路线**；
- Movement **拥有局部通行动作**；
- Domain Action **拥有目标方块/结构的完成条件**；
- Safety/Policy **决定动作是否被允许**。

`MineTask` 未来可以持有一次 `PathSession`，但 Movement 不拥有 MineTask，也不直接决定任务重试或 LLM 接管。

## 3. 领域动作与通行 Movement

必须区分以下两种表面相似的动作：

```text
为了移动而挖/放
  = Transit World Mutation
  = 路线手段

为了完成任务而挖/放
  = Domain Action
  = 领域目标
```

### 3.1 Transit Movement

例如：

- `TraverseMovement`：水平直线移动；
- `DiagonalMovement`：对角移动；
- `AscendMovement`：安全上升；
- `DescendMovement`：安全下降；
- `BreakAndTraverseMovement`：为通过而破坏路线障碍；
- `PlaceStepAndTraverseMovement`：为通过而放置临时台阶。

成功条件是：Bot 到达目标脚位，并满足 Movement 的后置条件；不是完成挖矿或建造目标。

### 3.2 Domain Action

例如：

- `MineTargetAction`：挖目标方块；
- `TargetAccessClearAction`：为获得目标视线/距离而清障；
- `PlaceBlockAction`：在领域目标位置放置方块；
- `CollectDropAction`：收集任务产生的掉落物。

成功条件是领域事实成立，例如目标方块已破坏、目标方块已正确放置、目标掉落物已收集。

### 3.3 世界修改意图

所有世界修改必须携带明确意图：

```text
PATH_ACCESS          为路线通行
TARGET_ACCESS        为领域目标可访问
DOMAIN_ACTION        完成领域目标
TEMPORARY_SUPPORT    临时支撑/台阶
RECOVERY_CLEANUP     回收临时结构
```

因此：

```text
BreakAndTraverse ≠ MineTargetAction
PlaceStepAndTraverse ≠ PlaceBlockAction
```

它们可以复用底层方块操作适配器，但不能共享任务所有权、预算、成功条件或失败语义。

## 4. Alice 三层安全模型

### 4.1 生存系统兜底

未来的 `SurvivalSafetyPolicy` 负责：

- 伤害、生命值、受击状态；
- 熔岩、火焰、窒息、溺水；
- 敌对实体和紧急环境风险；
- 中断当前任务、紧急撤离或交给生存任务。

它是运行期最后防线，不替代寻路期安全判断。本阶段只保留接口，不把完整逃生逻辑塞入 Pathing Core。

### 4.2 寻路提前避险

`RecoverabilityPolicy` 负责在候选生成和 Movement 启动前拒绝不可回收路线：

- 自然下降默认最多一格；
- 超过一格不能隐式自然落下；
- 默认不挖当前支撑方块；
- 默认不进入无法返回的单向地形；
- 需要世界修改时检查资源、权限和恢复路径；
- 必要时要求目标脚位之后仍存在安全撤离路线。

“两格高差往下踮一格方块”必须是显式的 `PlaceStepAndTraverseMovement`，不能隐藏在普通下降或 Walk Movement 中。

### 4.3 禁区与保护区

两者必须分离：

```text
ForbiddenZonePolicy
  限制 Bot 进入或经过：熔岩、危险流体、火焰、危险坠落区、任务禁区

ProtectedAreaPolicy
  限制世界修改：建筑、箱子、红石结构、用户标记区域、其他任务工作区
```

禁区回答“能不能走进去”；保护区回答“能不能破坏或放置”。允许经过保护区不代表允许修改保护区。

## 5. 核心数据契约

### 5.1 PathRequest

新内核接收显式请求，而不是含义不清的 `BlockPos target`：

```text
PathRequest
├─ botId
├─ startFoot
├─ goal / GoalSpec
├─ allowedMovementTypes
├─ forbiddenMovementTypes
├─ safetyPolicy
├─ recoverabilityPolicy
├─ worldModificationPolicy
├─ costModel
├─ heuristicModel
├─ searchBudget
├─ cancellationToken
└─ requester/task metadata
```

所有坐标必须标注语义：`foot`、`support`、`targetBlock`、`entityPosition`，禁止跨层复用含义不明的 `target`。

### 5.2 Movement

一个 Movement 是一种明确的局部动作语义，至少提供：

```text
fromFoot / toFoot
movementType
cost
capabilities
planningPrecondition
startPrecondition
perTickObservation
postcondition
failureClassification
requiredResources
expectedWorldEffects
```

第一版正式类型方向：

```text
Traverse / Diagonal / Ascend / Descend
```

之后才讨论 `BreakAndTraverse`、`PlaceStepAndTraverse`、Pillar、Bridge 等世界修改类型。不得继续让一个 `WalkMovement` 无限承担水平、斜向、上升、下降和世界修改语义。

### 5.3 PathResult 与 PathPlan

规划期结果和执行期结果分离：

```text
PlanningStatus:
  REACHED, UNREACHABLE, SEARCH_LIMIT, CANCELLED, ERROR

ExecutionStatus:
  RUNNING, COMPLETED, BLOCKED, STALE,
  INVALID_PRECONDITION, MOVEMENT_FAILED, TIMEOUT,
  CANCELLED, POSTCONDITION_FAILED
```

`PathPlan` 至少包含：

- 起点/目标脚位；
- Movement 序列；
- 投影脚位路径；
- 总成本；
- 搜索诊断；
- 世界快照/版本标识；
- Movement 世界修改摘要；
- 所需资源；
- 可回收性评估；
- 规划器名称与版本。

`List<BlockPos>` 只能是兼容投影，不是新内核的规范规划输出。

## 6. Provider 与搜索内核

`MovementProvider` 负责从当前脚位枚举候选 Movement；不负责任务重试、LLM 决策或修改任务状态。正式接口需要接收包含策略的 `MovementContext`：

```text
MovementContext
├─ bot/world view
├─ currentFoot
├─ PathRequest policy
├─ resource facts
├─ safety facts
└─ calculation context
```

搜索器应是 Movement-aware A*，而不是先生成裸坐标再事后编译：

```text
MovementProvider
  → candidates
  → gScore + heuristic
  → open set
  → Movement path
  → PathPlan
```

当前 `MovementPlanCompiler` 可保留为迁移兼容工具，但不能继续作为新内核主路线。

## 7. 成本、启发式与预算

零启发式 Dijkstra 只能作为当前成本模型下的保守兼容模式，不能作为长期结论。新内核需要显式：

```text
CostModel
HeuristicModel
SearchBudget
PartialResultPolicy
Cancellation
```

启发式应与 Movement 成本一致，可从欧几里得距离、方向性下界和可配置 weighted A* 开始；不能未经证明直接使用会高估成本的曼哈顿距离。

搜索达到预算时：

- 必须返回 `SEARCH_LIMIT`，不能映射为 `UNREACHABLE`；
- 保留扩展节点、耗时、最佳候选/最接近节点等诊断；
- 部分结果是否可交给上层，必须由 `PartialResultPolicy` 显式决定；
- 不得因为有部分路径就伪装成成功。

## 8. 动态世界与执行会话

动态检查分三层：

```text
Movement 级：当前段前置、实体位置、局部碰撞、后置条件
PathSession 级：周期性路径健康检查、世界变化、下一段有效性
Task 级：重试、重算、换策略、领域清障、放弃或交给决策层
```

参考 Baritone 的周期性路径检查思想，Alice 可以在 PathSession 级采用可配置检查周期（例如 5 tick），但检查结果必须区分 `BLOCKED`、`STALE`、`TIMEOUT` 和生存中断。

异步搜索必须先建立 `WorldView` 快照/版本边界：

```text
主线程采集受控 WorldView
  → 后台线程执行纯数据搜索
  → 主线程校验世界版本与 Movement 前置
  → 创建执行会话
```

后台线程不得直接操作实时 Minecraft 世界、Bot、方块或输入。

## 9. 任务交接协议

Pathing 失败只报告事实，不替任务决定策略：

```text
PATH_BLOCKED
PATH_STALE
SEARCH_LIMIT
UNREACHABLE
INVALID_PRECONDITION
RESOURCE_UNAVAILABLE
PROTECTED_AREA
FORBIDDEN_ZONE
SURVIVAL_INTERRUPTED
POSTCONDITION_FAILED
```

任务层可以选择：

- 原策略重试；
- 重新规划；
- 更换 Movement 策略；
- 领域清障；
- 暂停或取消；
- 把结构化事实交给决策层/LLM。

`MineTask` 的未来关系：

```text
MineTask
  → 选择目标方块
  → 选择合法挖掘站位
  → 提交 PathRequest
  → 等待 PathSession 到达并确认站位
  → 执行 MineTargetAction
  → DropCollectionTask
```

Pathing 可以包含 `PATH_ACCESS` 型破坏/放置 Movement，但不能接管目标方块的领域挖掘或放置所有权。

## 10. 与当前代码的迁移关系

保留并标注：

- `MineTask + PathExecutor`：正式 Legacy 路线，继续支撑现有 A/B/C；
- `MovementPlan`：当前不可变结果契约参考；
- `MovementPlanMiningTask` 与 M1/M2 测试入口：实验性执行证据；
- `MovementProvider` 与 `MovementRegistry`：原型素材，尚未成为正式主干；
- `SurfacePathfinder`：当前领域/曲面门面，未来通过适配层迁移到 PathRequest。

冻结：

- 不继续把 Movement 接入真实 `MineTask`；
- 不继续扩展 `MovementPlanCompiler` 作为主架构；
- 不继续给 `WalkMovement` 堆叠全部 Movement 语义；
- 不删除旧 `PathExecutor`；
- 不在没有 WorldView 和取消边界时引入异步线程；
- 不把 `BreakAndWalk`、Pillar、临时台阶放置接入正式任务。

## 11. 分阶段路线

```text
R0  架构与 Baritone 对照基线
R1  PathRequest / Policy / WorldView / PathResult 契约
R2  Movement 类型与能力声明
R3  Movement-aware A* 搜索闭环
R4  PathSession、动态检查、取消与超时
R5  Traverse/Diagonal/Ascend/Descend 独立客户端闭环
R6  世界修改 Movement 的资源、安全、恢复契约
R7  异步 Calculation Session
R8  任务层适配与 MineTask 重新评估
```

每一阶段先有独立测试入口和服务端/Windows 证据，再进入下一阶段；独立 Movement 通过不等于可以接入 MineTask。

## 12. 当前架构结论

Alice 不应复制 Baritone 的所有实现，也不应从零重复构造其已经成熟的路径思想。正确方向是：

```text
Baritone-like Pathing Core
+ Alice Safety / Recoverability Policy
+ Alice Task / Decision Handoff
```

当前停止具体寻路代码扩展，下一步只设计 R1 的数据契约与接口草案。生产 MineTask、A/B/C 和旧执行器保持不变。
