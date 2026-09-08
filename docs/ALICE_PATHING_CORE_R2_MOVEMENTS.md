# Alice Pathing Core R2：Movement 类型与能力声明

> 状态：R2 设计已确认；R2-A 纯数据契约与 R2-B Traverse 执行对象已实施并编译。R2-B 尚未完成独立运行期 fixture；PathSession 和正式任务接入仍未授权。
>
> 前置：R1 契约已确认，见 `docs/ALICE_PATHING_CORE_R1_CONTRACT.md`。
>
> 目标：借鉴 Baritone 的 Movement 分解、候选生成、局部前置/后置和执行模型，定义 Alice 的 Movement 类型边界；不重复实现已经成熟的通用思想，也不直接复制 Baritone 源码。

## 1. R2 五行任务卡

```text
目标：定义 MovementSpec、MovementExecution 和第一批 Movement 类型的职责边界。
不改变：MineTask、A/B/C、Legacy PathExecutor、M0/M1/M2 实验链和多 Bot 调度。
最小闭环：MovementContext -> Provider 候选 -> MovementSpec -> 主线程 ExecutionFactory -> ExecutionResult。
成功条件：每种 Movement 都能明确回答“移动什么、是否改世界、需要什么、如何完成、如何失败、如何回收”。
验证方式：设计评审；通过后再选择一个纯移动类型做独立实现。
```

## 2. 借鉴原则：复用结构，不复制实现

Baritone 已经提供了几个值得直接借鉴的系统级思想：

- Movement 是局部动作单元，而不是单纯坐标边；
- 不同 Movement 类型各自拥有成本、前置条件、执行和后置条件；
- 路径计算输出 Movement 序列；
- PathExecutor 负责按段执行、校验和切换；
- MineProcess 是领域过程，负责“挖什么”和“什么时候挖”，不是把所有挖方块都变成普通行走。

参考：

- [Baritone MineProcess](https://github.com/cabaletta/baritone/blob/e962c395067db099c46f6e9a0f67cef48b4a02fb/src/main/java/baritone/process/MineProcess.java)
- [Baritone Movement Types](https://deepwiki.com/cabaletta/baritone/4.2-movement-types)
- [Baritone Movement 文档](https://raw.githubusercontent.com/nuxdie/baritone-ts/f9dd747becbab714f80664f09246858be6a81897/docs/movements.md)
- [Baritone PathExecutor](https://github.com/cabaletta/baritone/blob/d3c170afaec71d7101c7c5a5a7b4ac374830b66/src/main/java/baritone/pathing/path/PathExecutor.java)

Alice 不直接依赖 Baritone 的原因：

- Alice 使用 Forge 1.20.1 和自有 BotPlayer/Controller 生命周期；
- Alice 需要任务级失败交接和 LLM/行为包策略；
- Alice 的可回收性、禁区、保护区和 Survival 兜底不是 Baritone 的原样契约；
- Alice 需要对世界修改、资源和任务产物建立可审计事实。

## 3. R2 核心对象

### 3.1 MovementSpec：搜索期纯数据

```text
MovementSpec
├─ movementType
├─ fromFoot / toFoot
├─ cost
├─ capabilities
├─ requiredResources
├─ expectedWorldEffects
├─ planningPreconditionFacts
├─ planningDependency
├─ evaluatedRecoverability
└─ executionFactoryKey
```

`MovementSpec` 必须是纯数据，不能持有 Bot、World、Controller 或可变执行状态。

约束：

- 不持有 `ServerPlayer`、`ServerLevel` 或实时世界引用；
- 不执行输入、不修改世界；
- 可放入搜索节点、路径结果或诊断记录；
- 不能仅用 `from/to` 推断 Movement 类型，类型必须显式保存。

### 3.2 MovementExecution：主线程执行对象

```text
MovementExecution
├─ spec
├─ botId / sessionId
├─ phase
├─ live precondition check
├─ tick(ServerLevel, ServerPlayer)
├─ postcondition check
├─ cleanup
└─ stable failure facts
```

执行阶段固定为：

```text
NOT_STARTED
→ PRECONDITION_CHECK
→ EXECUTING
→ SETTLING (需要物理落地/稳定时)
→ POSTCONDITION_CHECK
→ SUCCEEDED

任意非终态 → FAILED / CANCELLED
```

`SUCCESS` 只能由明确的后置条件产生，不能只由距离、整数 Y 或调用次数推断。

### 3.3 MovementExecutionFactory

```text
MovementExecutionFactory
├─ factoryKey()
├─ supports(MovementSpec spec)
├─ validate(MovementSpec spec, LiveExecutionContext context)
└─ create(MovementSpec spec, LiveExecutionContext context)
```

工厂创建前必须在主线程重验实时 Bot、WorldView revision、策略和 Movement 前置条件。工厂不存在、Spec 类型不支持或 Spec 已过期时，PathSession 返回稳定失败事实，不得静默切换另一种 Movement。

约束：

- 只能由当前 Bot 的 active `PathSession` 创建和驱动；
- 每段开始前重验实时世界；
- 终态清理一次性执行；
- 不能自行重规划、切换 Movement 或启动领域任务。

## 4. MovementCapabilities 与坐标边界

每个 MovementSpec 必须声明能力，而不是让策略层从类名猜测。

第一批纯移动类型的坐标变化必须互斥：

```text
TraverseMovement   dy=0，且 abs(dx)+abs(dz)=1
DiagonalMovement   dy=0，且 abs(dx)=1 且 abs(dz)=1
AscendMovement     dy=+1，允许水平位移，但必须是明确的上升动作
DescendMovement    dy=-1，允许水平位移，但必须是明确的下降动作
```

同一 `fromFoot -> toFoot` 在同一个 `MovementContext` 下不得由多个正式 Provider 以相同语义重复生成。旧兼容 Provider 与新正式 Provider 不得同时注册到同一个正式搜索 Registry。

这里的“纯移动”只表示不主动写世界，不表示不依赖 WorldView、碰撞、支撑、流体和危险事实。


```text
MovementCapabilities
├─ changesWorld
├─ mutationIntents
├─ consumesResources
├─ requiresTool
├─ reversible
├─ requiredRecoverabilityLevel
├─ maxNaturalDrop
├─ canBreakBlocks
├─ canPlaceBlocks
├─ canEnterFluid
├─ requiresZoneAuthorization
├─ intrinsicReversibility
└─ supportsMidExecutionRevalidation
```

`requiresZoneAuthorization` 只表达该 Movement 需要经过策略授权；它不表示 Movement 自己可以绕过禁区策略。

`intrinsicReversibility` 表示 Movement 类型的固有可能性；实际 `evaluatedRecoverability` 必须结合当前世界、路径和请求策略计算，不能由类名或静态布尔值伪造。

基本不变量：

```text
canBreakBlocks=true  不等于获得任意破坏权限
changesWorld=true    必须通过 WorldMutationPolicy
reversible=true      必须有可验证依据，不由类名保证
```

## 5. 第一批纯通行 Movement

R2 第一批只定义四种无世界修改的通行 Movement。它们不是立刻全部实现，而是先固定语义。

### 5.1 TraverseMovement

```text
语义：在同一脚位高度进行水平直线移动。
输入：相邻或连续同向脚位段。
世界修改：否。
资源：无。
默认回收等级：PATH_REVERSIBLE。
自然下降：默认 0；是否允许一级地形下落由独立 Descend 语义决定。
```

借鉴 Baritone 的水平移动 Movement：水平移动的碰撞、净空、脚下支撑和实际到达由本段负责。

Alice 增加：

- 检查禁区、保护区和危险流体；
- 检查路径段后仍满足指定 `RecoverabilityLevel`；
- 记录实际脚位、支撑、`onGround` 和输入清理事实。

当前代码映射：

- `WalkMovement` 可作为物理执行实验素材；
- `MovementPlanCompiler` 不作为正式候选生成器；
- 未来需要从绑定 Bot 的实现拆出 `TraverseSpec` 与 `TraverseExecution`。

### 5.2 DiagonalMovement

```text
语义：沿对角方向移动到相邻对角脚位。
输入：dx=±1 且 dz=±1 的脚位变化。
世界修改：否。
资源：无。
默认回收等级：PATH_REVERSIBLE。
```

借鉴重点：Baritone 对对角移动不是简单把两个直角段拼接；必须检查实体连续扫掠空间和两侧碰撞，避免“两个单格都可走，但对角线实际穿墙”。

Alice 增加：

- 检查 Bot 碰撞箱连续扫掠；
- 检查两侧支撑/脚位/头位；
- 禁止通过对角缝隙进入熔岩、危险流体或保护区修改范围；
- 失败时报告具体侧向阻塞位置。

当前 `MovementHelper`/旧 A* 中的对角判定只能作为几何参考，不能直接视为新 MovementSpec 实现。

### 5.3 AscendMovement

```text
语义：上升到相邻更高脚位，通常是一级台阶或明确跳跃动作。
输入：相邻脚位，高度差 +1。
世界修改：否（第一版）。
资源：无（第一版）。
默认回收等级：PATH_REVERSIBLE。
```

借鉴重点：Baritone 将上升与水平移动分开，因为上升涉及跳跃、头部空间、落脚支撑和不同完成条件。

Alice 增加：

- 不把上升隐式塞进 Traverse；
- 明确 `jump/input/settle` 阶段；
- 完成必须验证目标脚位、支撑顶面和 `onGround`；
- 不能因为目标高度正确就提前成功；
- 不允许借助放置方块，放置属于另一个 Movement。

当前 `WalkMovement` 的一级高差能力只能作为实验事实，不能直接代表新的 AscendMovement 契约。

### 5.4 DescendMovement

```text
语义：安全下降到相邻更低脚位。
输入：相邻脚位，高度差 -1。
世界修改：否（第一版）。
资源：无。
默认回收等级：至少 LOCAL_STEP；任务可提升为 PATH_REVERSIBLE。
自然下降：最多一格。
```

借鉴重点：Baritone 将下降作为独立 Movement，并对落差、支撑和落地过程单独判断。

Alice 增加：

- 超过一格自然下降直接拒绝；
- 下降后的回退能力必须由 `RecoverabilityLevel` 决定；
- 检查连续实体扫掠和落点支撑形状；
- 落地需要显式 settle，不得只比较整数 `blockPosition().Y`；
- 不默认用放置方块补齐落差。

当前 `DescendMovement` 原型涉及直接放置 Dirt，不能作为 R2 第一版的无世界修改 Descend 实现；应拆成纯下降与未来 `PlaceStepAndTraverse`。

迁移规则：旧 `DescendMovement` 仅保留为实验/历史原型；新正式纯下降实现必须使用新的 `DescendSpec + DescendExecution`，不得在旧类上继续叠加世界写入语义。

## 6. 世界修改 Movement：只定义边界，不进入第一实现

### 6.1 BreakAndTraverseMovement

```text
意图：PATH_ACCESS
目的：移除路线障碍并通过
不是：MineTargetAction 或 TargetAccessClearAction
```

必须额外具备：

- `WorldMutationPolicy` 授权；
- 工具和资源验证；
- 保护区检查；
- 不挖当前支撑默认规则；
- 破坏方块数量和深度预算；
- 掉落物 provenance；
- 中途失败和取消后的回收策略。

当前 `BreakAndWalkMovement` 直接 `destroyBlock` 的实现只能视为原型，不能进入正式内核。

### 6.2 PlaceStepAndTraverseMovement

```text
意图：TEMPORARY_SUPPORT
目的：放置临时支撑/台阶以通过
不是：PlaceBlockAction
```

必须额外具备：

- 材料来源和消耗记录；
- 放置位置和碰撞验证；
- 临时方块 provenance；
- 是否清理、何时清理和清理失败策略；
- 保护区与任务权限检查；
- 中途失足/阻挡时的撤离能力。

当前 `DescendMovement`/`PillarMovement` 中直接 `setBlock` 的代码不能作为正式实现。

### 6.3 Domain Action 严格独立

```text
MineTargetAction      由 MineTask 拥有
PlaceBlockAction      由 PlaceTask/领域任务拥有
TargetAccessClear     由挖掘领域策略拥有
```

Transit Movement 可以改变世界，但不拥有领域目标、领域掉落物或领域任务终态。

## 7. Provider 生成规则

Provider 按 `MovementContext` 生成 `MovementSpec`：

```text
Provider
  → 只生成满足几何、策略、世界视图和能力边界的候选
  → 不修改世界
  → 不控制 Bot
  → 不启动重试
```

生成顺序可以借鉴 Baritone 的 Movement 组合和成本排序，但不能把“Provider 优先级”误认为最终路径优先级。最终选择由搜索成本、heuristic、策略和可回收等级共同决定。

每个 Provider 必须能够在诊断模式输出拒绝事实：

```text
ASCEND_REJECTED_NO_HEADROOM
DESCEND_REJECTED_DROP_TOO_DEEP
DIAGONAL_REJECTED_SWEEP_COLLISION
TRAVERSE_REJECTED_FORBIDDEN_ZONE
BREAK_REJECTED_PROTECTED_AREA
PLACE_REJECTED_NO_RESOURCE
```

## 8. Movement 与 Domain Task 的交互

```text
MineTask
  → 选择 TargetBlockPos
  → 选择 StandNearBlockGoal
  → 提交 PathRequest
  → PathSession 执行 MovementSpec[]
  → 确认实际站位
  → MineTargetAction
  → 收集掉落物
```

不允许：

```text
Movement 自己挖 MineTask 目标
Movement 自己启动 MineTask
Movement 失败后自行换路或启动清障
MineTask 把领域清障伪装为 PATH_ACCESS
```

## 9. R2 当前结论

### 可复用

- Baritone 的 Movement 类型分解；
- Movement 序列作为搜索输出；
- Movement 局部前置/后置和成本；
- PathExecutor 按段推进与重验；
- MineProcess 的“领域过程拥有挖掘目标，路径系统负责到达站位”关系；
- Alice 当前 `MovementPathExecutor` 的输入清理和结构化失败证据方向。

### 必须重构或隔离

- 当前绑定 `ServerPlayer` 的 Movement 不能直接作为异步搜索对象；
- 当前 `MovementPlanCompiler` 不能作为正式 Movement-aware 搜索核心；
- `WalkMovement` 不能继续承载所有高差/方向语义；
- `BreakAndWalkMovement`/`PillarMovement`/当前 `DescendMovement` 的直接世界修改不能进入正式内核；
- `Goal.heuristic()` 不能继续控制搜索成本估算。

### R2 后续实现顺序

```text
R2-A 纯数据 MovementSpec/能力声明
R2-B Traverse 的独立 Provider + Execution
R2-C Diagonal 的扫掠碰撞闭环
R2-D Ascend 的跳跃/落地闭环
R2-E Descend 的一格下降/settle/回收闭环
R2-F Movement-aware 搜索接入这些纯数据候选
R2-G PathSession 动态检查
```

R2-G 之前不接 MineTask；世界修改 Movement 在 R2 纯移动闭环完成后另行立项。
