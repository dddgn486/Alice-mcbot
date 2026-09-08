# Alice Pathing Core R1 Contract

> 状态：设计草案；不授权修改生产代码。
>
> 关联：`docs/ALICE_PATHING_CORE_ARCHITECTURE.md`
>
> 目标：定义 Alice Pathing Core 的请求、策略、世界视图、规划结果和执行交接边界。

## 1. R1 五行任务卡

```text
目标：固定 Pathing Core 与 Task/Decision/World 的数据边界。
不改变：MineTask、A/B/C、PathExecutor、MovementPlan/M1/M2 实验链和多 Bot 调度。
最小闭环：PathRequest -> policy validation -> planning result contract -> task handoff facts。
成功条件：每个坐标、权限、危险和失败状态都有唯一语义，不需要依赖模糊 BlockPos target。
验证方式：设计审查；Java 接口草案暂不实现；后续用独立 fixture 验证。
```

## 2. 核心对象关系

```text
PathRequest
  -> RequestValidator
  -> PathCalculationContext
  -> MovementProvider candidates
  -> PathPlan / PlanningResult
  -> PathSession
  -> PathExecutionResult
  -> Task / Decision handoff
```

R1 不规定具体类名必须一比一落地；先冻结语义，再决定 Java 包结构。

## 3. 坐标语义

所有跨层位置必须带有明确语义。禁止使用没有上下文的 `BlockPos target`。

```text
FootPos          Bot 脚位占据的方块坐标
SupportPos       支撑 Bot 的方块坐标，通常为 foot.below()，但可能是非整格碰撞顶面
TargetBlockPos   领域目标方块，例如待挖方块
EntityPosition   实体实际 Vec3 位置
GoalSpec         路径目标定义，不一定是单一脚位
```

`fromFoot`/`toFoot` 只能接受 `FootPos`；领域目标必须通过 `GoalSpec` 或任务层计划转换成合法站位。

## 4. PathRequest

### 4.1 语义

`PathRequest` 表达“从当前脚位到某个通行目标的请求”，不表达挖矿、放置或收集的领域完成条件。

```text
PathRequest
├─ requestId
├─ botId
├─ requesterId / taskId
├─ startFoot: FootPos
├─ goal: GoalSpec
├─ movementPolicy
├─ safetyPolicy
├─ mutationPolicy
├─ costPolicy
├─ searchPolicy
├─ worldViewRequirement
└─ cancellationHandle
```

### 4.2 请求前置条件

提交前由 Task 或 RequestValidator 确认：

- `botId` 与任务实例一致；
- `startFoot` 是服务端实际脚位的快照，不是客户端推测；
- `goal` 具有明确目标类型；
- 策略之间不存在显式矛盾；
- 不允许的 Movement 不会被 Provider 隐式启用；
- 请求有预算、取消和生命周期归属。
- `startFoot` 是请求创建时由主线程采集的起点快照，不会被后台计算静默替换。
- 计算上下文记录实际开始时间和 `WorldView.revision`；执行前必须再次验证 Bot 当前脚位仍匹配计划起点。
- 起点不匹配时返回 `INVALID_PRECONDITION` 或 `STALE_START`，不得自动改写请求起点。

### 4.3 GoalSpec

第一版至少区分：

```text
FootGoal(目标脚位)
AreaGoal(允许落在一组脚位/区域)
StandNearBlockGoal(站在领域目标方块附近的合法脚位)
ExitGoal(撤离区域或安全点)
```

`GoalSpec` 只负责目标语义和 `isSatisfied(FootPos, WorldView)` 判断；不携带搜索 heuristic。`StandNearBlockGoal` 只负责把领域目标转换成路径目标候选；它不拥有挖目标方块的动作。

## 5. R1 已确认架构决策

以下五项决策经评审确认，作为 R1 后续实现的冻结约束：

### D-R1-01：可回收性采用等级枚举

```text
RecoverabilityLevel
├─ LOCAL_STEP
├─ PATH_REVERSIBLE
├─ SAFE_EXIT_REQUIRED
└─ EMERGENCY_EXIT_REQUIRED
```

它取代用多个布尔值隐含表达回收要求的方式。不同 Movement 和任务请求必须明确声明所需等级；默认禁止把“能够到达”当作“能够安全返回”。

### D-R1-02：规划结果保存纯数据 MovementSpec

未来 `PathPlan` 不直接保存绑定 `ServerPlayer`/`ServerLevel` 的可执行 Movement 实例，而保存不可变的 `MovementSpec`。执行阶段在主线程根据实时世界和策略创建 `MovementExecution`。

```text
MovementSpec       可计算、可序列化、不可直接执行
MovementExecution  绑定 Bot/实时世界、只能由 PathSession 执行
```

现有 M0/M1/M2 的 `MovementPlan` 和 `Movement` 实例保留为实验/迁移兼容对象，不直接视为 R1 新内核契约。

### D-R1-03：Goal 与 HeuristicModel 解耦

`GoalSpec` 只负责目标满足判断和目标语义；成本相关估算统一由 `HeuristicModel` 基于 `CostModel` 计算。Goal 不再拥有独立 heuristic 语义，避免目标类型偷偷决定搜索算法。

### D-R1-04：MovementPolicy 与 WorldMutationPolicy 分离

```text
MovementPolicy       是否允许使用某种 Movement
WorldMutationPolicy  该 Movement 具体能否破坏/放置哪些方块
```

前者不能替代后者。世界修改还必须经过 `ProtectedAreaPolicy`、`RecoverabilityPolicy` 和资源/工具验证。

### D-R1-05：单 Bot 单 active PathSession

一个 Bot 同时最多拥有一个活动 `PathSession`。新会话必须显式取消、等待终止或拒绝旧会话；旧会话的迟到结果不得重新接管 Bot 输入。只有带有当前 `botId` 和 active `sessionId` 的会话可以写入 Controller。

## 6. 策略契约

### 6.1 MovementPolicy

```text
MovementPolicy
├─ allowedTypes
├─ forbiddenTypes
├─ allowWorldMutation
├─ allowTemporarySupport
├─ allowPathAccessBreaking
└─ maxMovementCost / maxSegments
```

它回答“这次请求允许使用哪些 Movement”。

### 6.2 RecoverabilityPolicy

```text
RecoverabilityPolicy
├─ requiredLevel: RecoverabilityLevel
├─ maxNaturalDropHeight = 1 (默认)
├─ requireReturnableTerrain
├─ requireEmergencyExit
├─ forbidBreakingCurrentSupport = true (默认)
├─ forbidBreakingCurrentFoot = true (默认)
├─ allowConditionalSupportRemoval
├─ maxTemporaryMutationDepth
└─ returnPathCheckMode
```

`requiredLevel` 使用已确认的枚举：

```text
LOCAL_STEP              当前 Movement 完成后至少能回到上一个脚位
PATH_REVERSIBLE         当前路径具备可验证的反向回收路线
SAFE_EXIT_REQUIRED      当前路线完成后仍能到达指定安全出口
EMERGENCY_EXIT_REQUIRED 必须保留满足生存兜底的紧急撤离能力
```

它回答“路线完成后 Bot 是否仍处于可回收状态”，而不是只回答“当前目标脚位能不能站”。

规则：超过一格的自然下落不能由普通 Movement 隐式完成；两格高差必须使用显式、安全且获授权的世界修改 Movement。

### 6.3 ForbiddenZonePolicy

```text
ForbiddenZonePolicy
├─ forbiddenBlocks / fluids
├─ forbiddenRegions
├─ maxHazardExposure
├─ collisionEnvelope
└─ evaluateFoot / support / head / sweptVolume
```

它回答“Bot 是否可以进入或经过此空间”。熔岩、危险流体、火焰和任务指定危险区属于这一层。

### 6.4 ProtectedAreaPolicy

```text
ProtectedAreaPolicy
├─ protectedRegions
├─ protectedBlockTags
├─ canBreak(pos, intent)
├─ canPlace(pos, intent)
└─ canRemoveTemporarySupport(pos, provenance)
```

它回答“这个位置是否属于受保护结构”。允许经过保护区不等于允许破坏保护区。

### 6.5 WorldMutationPolicy

```text
WorldMutationPolicy
├─ allowedIntents
├─ canBreak(pos, intent, context)
├─ canPlace(pos, intent, context)
├─ requiredTool
├─ requiredResources
├─ maxBlocksPerMovement
├─ maxBlocksPerPath
├─ temporaryBlockProvenance
└─ cleanupPolicy
```

它回答“当前请求是否授权某种具体世界修改”。必须同时通过 `MovementPolicy`、`ProtectedAreaPolicy`、`RecoverabilityPolicy` 和资源/工具检查。`PATH_ACCESS` 的破坏不能因此获得 `DOMAIN_ACTION` 的目标所有权。

### 6.6 SurvivalSafetyPolicy

```text
SurvivalSafetyPolicy
├─ assess(snapshot)
├─ canStartMovement(snapshot)
└─ interruption(snapshot)
```

R1 只保留接口。完整伤害、危险检测、紧急撤离由未来 Survival/Emergency 系统实现，不由 Pathing Core 自己伪造。

## 6. WorldView

异步搜索和主线程执行之间不能共享未受控的实时 Minecraft 对象。R1 定义只读世界视图概念：

```text
WorldView
├─ worldId / dimension
├─ revision
├─ capturedAtTick
├─ blockState(pos)
├─ collisionShape(pos)
├─ fluidState(pos)
├─ supportShape(pos)
├─ hazardFacts(pos)
├─ protectionFacts(pos)
└─ localRevision(region)
```

要求：

- 后台计算只读取不可变或线程安全的 `WorldView`；
- `WorldView.revision` 必须随执行前校验；
- Movement 仍需在主线程重新验证实时世界；
- WorldView 不提供写操作；
- 未知状态默认按不可安全证明处理，而不是猜测可通行。

## 7. MovementSpec 与 MovementContext

### 7.1 MovementSpec

`MovementSpec` 是搜索阶段产生的不可变纯数据，不持有 `ServerPlayer`、`ServerLevel` 或实时世界引用：

```text
MovementSpec
├─ movementType
├─ fromFoot / toFoot
├─ cost
├─ capabilities
├─ requiredResources
├─ expectedWorldEffects
├─ planningPreconditionFacts
└─ executionFactoryKey
```

`executionFactoryKey` 只标识执行阶段选择哪种 MovementExecution 工厂，不携带可执行对象本身。

### 7.2 MovementContext 与 Provider

```text
MovementContext
├─ botSnapshot
├─ currentFoot
├─ worldView
├─ movementPolicy
├─ recoverabilityPolicy
├─ forbiddenZonePolicy
├─ protectedAreaPolicy
├─ survivalAssessment
└─ calculationContext
```

`MovementProvider` 只做候选生成：

```text
List<MovementSpec> generate(MovementContext context)
```

候选必须说明：

```text
movementType
fromFoot / toFoot
cost
capabilities
requiredResources
expectedWorldEffects
rejectionFacts (若未生成但诊断模式需要)
```

Provider 不得：

- 修改实时世界；
- 修改 Bot 输入；
- 启动任务重试；
- 直接清障；
- 把领域目标方块当作路径障碍自动破坏。

## 8. PlanningResult

```text
PlanningResult
├─ status: REACHED / UNREACHABLE / SEARCH_LIMIT / CANCELLED / ERROR
├─ plan: PathPlan? 
├─ diagnostics
├─ bestFrontier? 
├─ worldViewRevision
├─ expandedNodes
├─ elapsedNanos
└─ failureFacts
```

### 8.1 语义

- `REACHED`：在当前策略、世界视图和预算内找到完整 Movement 路线；
- `UNREACHABLE`：在搜索规则内证明不可达；
- `SEARCH_LIMIT`：预算不足，未知，不得当作不可达；
- `CANCELLED`：由所有者取消；
- `ERROR`：计算基础设施或输入异常。

`bestFrontier` 只用于诊断或由显式 `PartialResultPolicy` 授权，不自动执行成成功路径。

## 9. PathPlan

```text
PathPlan
├─ planId
├─ botId
├─ startFoot
├─ goalFoot / goal satisfaction
├─ movements: List<MovementSpec>
├─ projectedFootPath
├─ totalCost
├─ requiredResources
├─ worldMutationSummary
├─ recoverabilityAssessment
├─ worldViewRevision
└─ plannerMetadata
```

每个 `MovementSpec` 包含：

```text
MovementSpec
├─ movementType
├─ fromFoot / toFoot
├─ intent
├─ cost
├─ capabilities
├─ requiredResources
├─ expectedPreconditionFacts
├─ expectedPostconditionFacts
├─ expectedWorldEffects
└─ executionFactoryKey
```

新内核的规范输出是 Movement 序列；`List<BlockPos>` 只能从 PathPlan 投影出来供 Legacy 兼容。

## 10. PathSession 与 ExecutionResult

### 10.1 PathSession

`PathSession` 是单个 Bot、单个 PathRequest 的执行生命周期，负责：

- 当前 segment；
- 取消；
- 每段启动前重验；
- Movement tick 调度；
- 定期路径健康检查；
- Controller 输入清理；
- 一次性终态。

生命周期：

```text
CREATED -> PLANNING -> READY -> EXECUTING -> COMPLETED
                                  ├-> FAILED(reason)
                                  ├-> CANCEL_REQUESTED -> CANCELLED(reason)
                                  └-> STALE
```

同一 Bot 同时只能有一个 active session。旧 session 的迟到结果不得写入 Controller；终态清理只执行一次。

PathSession 不决定任务是否重试，也不启动 MineTargetAction。

### 10.2 ExecutionResult

```text
ExecutionResult
├─ status
├─ requestId / planId / botId
├─ phase: PRECONDITION / EXECUTING / POSTCONDITION / CLEANUP
├─ actualFoot
├─ actualEntityPosition
├─ actualSupport
├─ onGround
├─ movementType
├─ failureCode
├─ obstacle / hazard / protectedPos?
├─ worldRevisionAtFailure
└─ cleanupFacts
```

建议状态：

```text
COMPLETED
BLOCKED
STALE
INVALID_PRECONDITION
RESOURCE_UNAVAILABLE
FORBIDDEN_ZONE
PROTECTED_AREA
MOVEMENT_FAILED
TIMEOUT
CANCELLED
POSTCONDITION_FAILED
STALE_START
SURVIVAL_INTERRUPTED
```

## 11. Handoff 到 Task/Decision

Pathing 只给事实，不给高层策略命令：

```text
Pathing -> Task:
  execution result + stable facts

Task -> Decision:
  domain context + path facts + available recovery options

Decision -> Task/Pathing:
  new goal / new policy / retry budget / explicit mutation authorization
```

例如：

```text
PATH_ACCESS + PROTECTED_AREA
  不等同于 MineTask 的 target access failure

TEMPORARY_SUPPORT + RESOURCE_UNAVAILABLE
  不自动变成放置任务

SEARCH_LIMIT
  不自动授权 TunnelPlanner
```

## 12. MineTask / PlaceTask 未来适配

### MineTask

```text
MineTask
  -> 选择 TargetBlockPos
  -> 选择合法 StandNearBlockGoal
  -> PathRequest(startFoot -> standingFoot)
  -> PathSession 完成并确认实际站位
  -> MineTargetAction
  -> DropCollectionTask
```

`BreakAndTraverse` 若被允许，只能处理 `PATH_ACCESS`；目标方块和目标访问清障仍由 MineTask 的领域策略管理。

### PlaceTask

```text
PlaceTask
  -> 选择领域放置目标
  -> PathRequest 到施工站位
  -> PathSession
  -> PlaceBlockAction
```

`PlaceStepAndTraverse` 的临时方块不等同于 `PlaceBlockAction` 的领域目标，必须有 provenance、资源和清理策略。

## 13. R1 不解决的问题

- 具体 Java record/interface 的最终包名；
- Baritone 全部 Movement 的移植；
- 异步线程实现；
- MineTask 正式接入；
- 世界修改 Movement 的具体挖掘/放置 API；
- 生存系统和 EmergencyEscapeTask；
- 多 Bot 调度策略。

## 14. R1 评审门槛

R1 通过前必须确认：

- `FootPos`、`SupportPos`、`TargetBlockPos`、`EntityPosition` 不混用；
- 禁区与保护区分离；
- 生存兜底接口存在但不越权；
- Transit Mutation 与 Domain Action 分离；
- 搜索状态与执行状态分离；
- `SEARCH_LIMIT` 不转成 `UNREACHABLE`；
- Pathing 失败能交给 Task/Decision，而非内部无限重试；
- 新内核不依赖 Legacy `List<BlockPos>` 作为规范输出。

R1 评审完成后，才进入 R2 Movement 类型和能力声明设计。
