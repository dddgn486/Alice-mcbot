# Alice 当前架构决策

> 简短、稳定、可被显式替换。修 bug 时不能悄悄推翻这些决策。

## D-001：LLM 只做目标级决策

- 状态：稳定
- LLM 选择目标、策略和已注册工具；不逐 tick 控制物理，不直接写库存或世界。

## D-002：服务端权威

- 状态：稳定
- 世界、任务、bot、库存、权限和完成条件以服务端事实为准；客户端只负责显示、输入和观察。

## D-003：普通挖矿和拾取使用 HARD_PATH

- 状态：稳定
- `SOFT_SURFACE` 只能通过独立实验入口推进，未经真实客户端验证不得接入正式任务。

## D-004：SEARCH_LIMIT 不等于 UNREACHABLE

- 状态：稳定
- 搜索预算耗尽只能报告未知/预算不足，不能自动转为不可达或授权挖隧道。

## D-005：先最小闭环，再正式接入

- 状态：稳定
- 新移动、交互、GUI 或适配器先通过独立测试物品/诊断入口验证，再接入正式任务链。

## D-006：用户是真人测试者和共同开发者

- 状态：稳定
- Windows 客户端测试由用户通过游戏内物品、右键/Shift+右键和键盘鼠标完成；AI 提供简单步骤并主动询问关键现象。

## D-007：修复前先讨论根因

- 状态：稳定
- 发现可能修复方式不等于立即实施。先区分事实、假设、证据缺口和替代解释，与用户确认最小修复方向。

## D-008：旧监督流程不属于当前日常流程

- 状态：稳定
- dsh-agent-bus、active-plan、旧 HANDOVER 和 `session-complete` 已归档，仅用于历史追溯，不是新会话的默认开工门槛。

## D-009：Task 是行为编排层，不是决策层

- 状态：稳定
- `Task`/`Action` 表示可执行行为包，负责阶段推进、生命周期、失败恢复和领域后置条件；当前不承担高层目标决策，也不直接控制每 tick 的物理动作。
- 影响：`MineTask` 是挖掘行为编排者；未来决策层可以选择目标和策略，但暂不强行引入。

## D-010：领域规划与通用移动规划分层

- 状态：稳定
- 挖掘领域负责“目标方块 → 合法挖掘站位”的规划；通用路径规划负责“脚位 → 脚位”的路线；Movement 负责具体移动原语；Executor 负责执行序列和报告执行结果。
- 影响：不得把 `MineTask`、`BotMiner`、`PathPlanner`、`Movement` 和 `PathExecutor` 的职责通过单个补丁混为一谈。

## D-011：目标访问清障与路径清障分离

- 状态：稳定
- 目标访问清障是为了让 bot 在合法范围内看见并挖到目标，属于挖掘领域；路径清障是为了从一个脚位到另一个脚位，属于 `PathPlanner + Movement`。
- 影响：`MineTask` 当前清障逻辑不能未经验证直接删除；`BreakAndWalkMovement`、隧道和搭路能力也不能未经独立验证直接接入正式任务。

## D-012：Movement 覆盖通用移动原语

- 状态：稳定
- 行走、跳跃、下降、游泳、爬梯子，以及带明确安全/资源契约的挖后走、挖隧道、搭柱子、搭桥，都应作为 Movement 原语由规划器选择、由执行器执行。
- 影响：带破坏或放置世界修改的 Movement 必须有权限、资源、安全、前置条件、后置条件和稳定失败码。

## D-013：当前路径搜索保留零启发式 Dijkstra

- 状态：稳定
- `AStarPathfinder` 当前实现使用零 heuristic，实际搜索语义是 Dijkstra；原因是当前直走与对角移动成本都为 1，未经证明的曼哈顿 heuristic 会高估剩余成本并破坏最优性。时间预算用于控制搜索规模，不代表它已经是 A*。
- 影响：暂不因类名直接改算法或改名；只有在成本模型和可接受 heuristic 明确后，才重新评估真正的 A* 或显式改名。

## D-015：HARD_PATH 与 SOFT_SURFACE 暂不作为准确实现语义

- 状态：待重新定义
- 事实：当前正式 `PathExecutor` 使用 `BasicMovement.travel()`，并非旧文档所述的纯 `setPos` 瞬移；`SOFT_SURFACE` 只有模式名和部分真实物理基础设施，尚无完整客户端闭环证据。
- 当前决定：HARD_PATH 暂按“当前已验收的受控路径执行后端”理解；SOFT_SURFACE 暂按“目标中的真实物理移动模式”理解，均不据名称推断实现完成度。
- 影响：在物理调用链、到达/碰撞/失败后置条件完成审计前，不把 SOFT_SURFACE 接入 MineTask，也不把 HARD_PATH 直接改造成另一种实现。
- 可改条件：完成 Movement 纯行走闭环审计，并重新讨论正式命名和执行后端。

## D-016：Forge Bot 物理推进必须以实际 BotPlayer 调用链为准

- 状态：稳定事实，待闭环验证
- 事实：当前 `BotPlayer.tick()` 显式执行 `controller.onUpdate()`、`super.tick()`、`aiStep()`；不能套用 Fabric 或普通 Mob 关于自动 `travel()` 的假设。
- 当前决定：保留显式 `aiStep()` 作为当前 Bot 物理推进路线；单独审计任务侧显式 `travel()` 与 `aiStep()` 的交互，不在未验证前删除任一入口。

## D-017：Movement 实验 6 失败结果契约

- 状态：冻结，实验设计基线
- 适用范围：独立 Movement 实验及其 `MovementPathExecutor` 事实报告；不授权接入 MineTask、旧 `PathExecutor` 或自动重规划。
- 结果 `BLOCKED_DYNAMIC`：仅当 Movement 已进入执行阶段、任务启动时对应路径可通行、执行期间服务端确认行走方向上的方块碰撞体新出现或发生变化、Bot 未到达目标且在连续观察窗口内无有效位移时允许使用。必须保留障碍坐标、发现 tick、Bot 脚位/Y、支撑、onGround 和观察窗口等证据。
- 结果 `MOVEMENT_TIMEOUT`：Movement 未完成且超过无进展/执行预算，但没有足够服务端证据证明是动态方块阻挡时使用；这是保守兜底，不得仅因“Bot 不动”改名为 `BLOCKED_DYNAMIC`。
- 结果 `INVALID_PRECONDITION`：Movement 在执行前置检查阶段即不满足起点、目标脚位、支撑、空间或通行契约时使用；不得把启动前不可执行伪装成动态阻挡或普通超时。
- 共同终态：三类结果都必须记录实际终点、失败阶段、稳定失败码，并清理 Controller 输入；不得自动挖障碍、绕行、重规划、传送或切换到另一种 Movement。
- 排除项：第一版不对实体推挤、流体、复杂特殊碰撞体或未知模组方块做动态阻挡分类；证据不足时回退 `MOVEMENT_TIMEOUT`。
- 可改条件：完成独立对照实验并取得服务端日志与 Windows 客户端证据后，才可讨论新增分类或决策层策略。

## D-018：正式路径模型收敛为 Movement 规范、BlockPos 兼容

- 状态：冻结，MineTask 接入前的架构决策
- 决定：正式移动执行单位采用 `List<Movement> + MovementPathExecutor`；`List<BlockPos> + PathExecutor` 作为迁移兼容后端保留，暂不删除或在同一任务实例内交替重试。
- 原因：搜索阶段已使用 Movement Provider；Movement 能保留脚位、专用前置/后置条件、成本和世界修改语义，而裸坐标投影会丢失这些信息。实验 1-6 已证明纯行走 Movement 的独立物理链和 6A/6B/6C 失败分类，但尚未证明新执行器覆盖 MineTask 全部后置条件。
- 影响：新接入必须先建立不可变 `MovementPlan`，明确脚位坐标和结果状态；旧 MineTask/A/B/C 回归继续使用旧后端，直到新后端完成等价客户端证据。
- 可改条件：新后端完成正常挖掘、目标访问清障、动态阻挡、取消/超时和恢复边界的服务端与 Windows 客户端回归后，才讨论旧后端默认退役。

## D-019：MineTask Movement 接入采用分阶段最小重建（已被 D-020 替代）

- 状态：已废弃；仅作为 M0/M1/M2 历史实施顺序记录
- 原决定：先建立 `MovementPlan` 数据契约，再做仅支持已验证同高度 `WalkMovement` 的独立编译/测试入口，随后适配 BotMiner，最后让 MineTask 实现真实 `MOVING_TO_POSITION`。
- 废弃原因：`MovementPlanCompiler` 是旧 `BlockPos` 路径的事后编译器，不是 Movement-aware 搜索内核；通用 `MiningPlanner` 输出与 M1 编译契约不一致，直接推进 MineTask 会重复造轮子并破坏成熟 Legacy 行为。
- 保留范围：M0 `MovementPlan`、M1 独立编译/测试入口和 M2 `BotMiner` 独立后端保留为实验/迁移证据；真实 `MineTask` Movement 接入已回撤。
- 替代决策：见 D-020；设计草案见 `docs/ALICE_PATHING_CORE_ARCHITECTURE.md` 与 `docs/ALICE_PATHING_CORE_R1_CONTRACT.md`。

## D-020：重建 Alice Pathing Core，而非继续修补 MineTask 接入

- 状态：当前生效，架构重规划基线
- 决定：先设计并实现 Baritone-like Movement-aware Pathing Core，再重新评估 MineTask/PlaceTask 等正式任务的适配；新内核以 `List<Movement>` 为规范规划输出，`List<BlockPos>` 仅作 Legacy 兼容投影。
- 核心边界：Domain Task 拥有领域目标和完成条件；Pathing 拥有通行路线；Movement 拥有局部动作；为通行而挖/放与领域挖/放必须按意图分离。
- Alice 特有策略：生存系统作为未来运行期兜底接口；Pathing 通过 `RecoverabilityPolicy` 提前禁止不可回收路线；`ForbiddenZonePolicy` 限制进入危险区；`ProtectedAreaPolicy` 限制世界修改。
- 影响：暂停真实 MineTask Movement 接入、`MovementPlanCompiler` 主线扩展和 `WalkMovement` 语义堆叠；保留正式 Legacy MineTask/A/B/C、旧 `PathExecutor`、M0/M1/M2 独立实验。
- 下一阶段：R1 契约评审通过后设计 R2 Movement 类型与能力声明；不得在 R1/R2 未确认前修改生产寻路链。
- 设计文件：`docs/ALICE_PATHING_CORE_ARCHITECTURE.md`、`docs/ALICE_PATHING_CORE_R1_CONTRACT.md`。

## D-021：R2 Movement 设计确认与 R2-A 契约实施

- 状态：当前实施切片已完成，行为实现仍冻结
- 决定：确认 `Traverse` 严格为同高度四向相邻，`Diagonal` 独立负责同高度对角，`Ascend/Descend` 允许水平位移并分别处理高差；规划输出使用纯数据 `MovementSpec`，执行期通过 `MovementExecutionFactory` 创建主线程 `MovementExecution`。
- 保留边界：不修改 MineTask、A/B/C、Legacy `PathExecutor`、旧 Movement、`MovementPlanCompiler` 或多 Bot 调度；世界修改 Movement 只保留契约，不进入 R2-A 行为实现。
- 已实施：新增 `pathing.core` 契约对象，包括 Movement 类型、能力声明、可回收等级、规划依赖、WorldView、LiveExecutionContext、MovementSpec、ExecutionFactory 和执行阶段接口。
- 下一阶段：R2-B 仅设计/实现独立 `TraverseSpec + TraverseExecution` 闭环，不接正式 MineTask。
- 设计文件：`docs/ALICE_PATHING_CORE_R2_MOVEMENTS.md`。

## D-022：R2-B Traverse 实现保持独立运行期验证

- 状态：`USER_ACCEPTED`，证据已归档
- 已完成：新增 `TraverseExecutionFactory` 与 `TraverseExecution`，复用现有 `BotController` 输入驱动物理边界和 `MovementHelper` 脚位/支撑判定；未修改旧 `WalkMovement`、`MovementPathExecutor`、MineTask 或 Legacy `PathExecutor`。
- 历史实施：曾新增 `TraverseCoreDiagnosticTester`、`TraverseCoreDiagnosticTask` 和 `alice:traverse_core_diagnostic_tester`，但因工件链混乱且客户端测试入口残留过多，现已退役并移除。
- 当前保留：`pathing.core` 中的 R2-B 契约与 `TraverseExecution` 原型；新增 `/alice pathing traverse <north|south|east|west>` 作为唯一运行期入口，不恢复旧测试物品。
- 当前等级：`USER_ACCEPTED`；固定客户端工件 SHA-256 `fc0ad051208422882b5d8c1060bb4afe822552a1ef753ac5e0964b8e09e0fbbe`。
- 证据：`.alice-supervision/client-tests/pathing-core-r2b-traverse-20260907/evidence/`。

## D-023：R2-C 基础 Movement 验收与物理结论

- 状态：`USER_ACCEPTED`（2026-09-07），证据已归档
- 已验收：Diagonal（同高度对角 + 两侧通行性防穿墙检查）、Ascend（一级上升）、Descend（一级下降，回收等级 LOCAL_STEP）；入口为 `/alice pathing diagonal|ascend|descend <direction>`，独立验证链，不接 MineTask。
- 验收工件：SHA-256 `804c5eb897a5337a07ff11286332805b1f2a4227d41bf1cdd4e5622982ed98cf`；运行时清洁版（仅移除诊断探针）`9c0388aeafa77f9d7a32129dab5d0270d764a48f271e56cbee4ed048a2b8e8d9`。
- 物理结论（客户端实测，需长期遵守）：
  1. 一级上升不需要跳跃——原版自动踩台阶机制即可完成；显式 `setJumping` 反而干扰节奏导致 settling 超时。
  2. 下降/高差检测必须比较 `blockPosition().getY()` 与脚位 Y；实体 `getY()` 等于支撑面高度（如 65.0），与 `fromY+0.5` 比较恒真，会在第 1 tick 误判"已下降"。
  3. Descend 落点过冲（下落到底被自动踩台阶带上相邻方块边缘、actualFoot 偏一格）为已知行为，用户裁定暂不处理；后续可参考 Baritone MovementDescend 的 sliding-block 预处理。
- 工作流新增（本项目长期规则）：
  1. 设计/修复寻路系统必须先调查 Baritone 对应实现（`web_search` 源码），适配 Alice 契约，不凭空猜物理。
  2. 客户端测试失败时在关键检查点加临时诊断日志探针；根因确认后必须随手回收全部探针，生产代码只保留 started/completed/failed/rejected 终态日志。
- 证据：`.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/`（含 4 轮日志、根因探针记录、探针回收说明）。
- 过冲行为分析结论（2026-09-08，实测 4/4 样本 + 机制审查）：
  1. 当前诊断阶段**维持暂不处理**：失败语义诚实（FAILED + Controller 清理，无静默成功）、人在回路可平凡修正、Legacy 生产链零影响。
  2. 但存在两个不可自愈的隐患：(a) 过冲落点列从未被前置校验——等于一次无安全审查的计划外位置迁移，真实地形下可能是悬崖/熔岩/禁区；(b) west 例"爬回上一级"使 LOCAL_STEP 回收记账对实际脚位失效（`support=false, onGround=true` 站立机制未查明，证据缺口）。
  3. **R3 硬前置**：过冲率 3/4，多段链接下每段工厂校验 `bot.blockPosition()==fromFoot`，过冲必 stale-start 断链。R3 开工必须先完成"第 0 号闭环"：
     - 先 `web_search` 调查 Baritone `MovementDescend` 的输入时机与落点约束（新工作流规则）；
     - 实现**边缘切断输入**（水平越过支撑边缘后停止 setForward，垂直落入目标列，同时消除落地踩台阶）；
     - **落点列前置校验**（把过冲可能落到的相邻列纳入 precondition，不通过即拒绝）；
     - 一次 Windows 客户端测试验证"精确落点 + 多段不断链"。
  4. 不放宽后置条件到 1.0D——落点漂移合法化会污染路径链脚位契约。
- 下一步：R3 PathSession（以第 0 号闭环起步）或用户指定的其他任务。

## D-034：R4 自愈闭环（snipsnap + 重规划）

- 状态：当前生效（用户 2026-09-09 指定 "R4 自愈闭环"）
- 背景（D-033 记录的已知限制）：COLUMN 容差下下降段可能带动量滑入邻列，导致下一段
  `*_STALE_START`；世界变化导致 `SEGMENT_TARGET_CHANGED`（BLOCKED）同理——此前只能诚实失败。
- Baritone 参考：`PathExecutor.snipsnapifpossible:324-343` —— 空中下落时不吸附；否则在
  路径位置表里查找当前脚位并**直接跳到该索引**（清输入）。
- 决策：`PathSession` 在段失败时按顺序自愈，再决定是否终止：
  1. **snipsnap**：仅当 `onGround`；当前脚位命中计划的投影脚位路径 → 从该索引继续；
     若命中终点 → 直接 `COMPLETED`；
  2. **重规划**：从当前脚位用**同一请求（目标/允许 Movement/预算）**重新规划，
     上限 `MAX_REPLANS=2`，成功则替换计划并从头执行；
  3. 都失败 → 按原语义终止，上报原始失败码（不掩盖）。
- 可观测：日志 `snipsnap` / `snipsnap_goal` / `replanned` / `replan_failed`；
  `PathExecutionResult.diagnostics` 带 `replans=N`。
- **自愈首次实测暴露的第三个问题（2026-09-09）**：把课程目标设为 `(7,62,66)` 后，
  `PLACE_STEP_AND_TRAVERSE` 会把台阶**放在目标脚位上**（计划自相矛盾：放置后目标不可站），
  导致自愈重规划 `UNREACHABLE`。修复：规划器守卫——**放置位不得是目标脚位**
  （`SurfaceProvider.appendPlaceStepAndTraverse`）。
- 验收入口：
  - 正常路径：`/function alice_test:place_course` + `alice:pathing_placer`（目标 `(8,62,66)`）；
  - 自愈路径：`alice:pathing_disturber` —— 同一路线，但在第 30 tick 把 bot 平移 1 格
    （模拟被推开/世界变化），确定性触发 `*_STALE_START` 以验证 snipsnap / 重规划。
- 未验证：客户端实测。

## D-033：R5-3 PlaceStepAndTraverse（TEMPORARY_SUPPORT 放置台阶）

- 状态：当前生效（用户 2026-09-08 指定 R5 后按 R5-1 → R5-2 → R5-3 顺序实施）
- Baritone 参考：`MovementTraverse:125-171` 的桥接分支——目标可走 + 目标下方**可替换**时在下方放置，
  成本含 `placeCost`；放置面扫描语义见 `MovementHelper.attemptToPlaceABlock:791-843`。
- 语义：`PLACE_STEP_AND_TRAVERSE` = **目标列缺支撑时，在目标下方放置一个方块，再走上去**。
  几何：同层直线 1 格且 `dy ∈ {0, -1}`（0 = 跨同层缺口，-1 = 把 2 格落差拆成 1 格）。
  这正是"两格高差往下踮一格方块"的显式原语，不得隐藏在普通下降里（架构文档 §4.2）。
- 能力声明：`MovementCapabilities.temporarySupport(...)` =
  `changesWorld=true` + `mutationIntents={TEMPORARY_SUPPORT}` + `consumesResources=true` + `canPlaceBlocks=true`。
- 规划候选（`SurfaceMovementProvider`）：目标可通行且**缺支撑**、目标下方可替换、
  bot 快捷栏有可放置方块、存在支撑面 → 生成候选；成本 = 水平/下降成本 + 4.0
  （Baritone `PLACE_ONE_BLOCK_COST` 量级）。
- 授权：`PathRequest.withWorldModification(...)`（破坏 + 放置），世界修改必须显式授权。
- 失败码：`PLACE_RESOURCE_UNAVAILABLE`、`PLACE_NO_VALID_FACE`、
  `PLACE_STEP_AND_TRAVERSE_PLACE_OCCUPIED`、`..._SUPPORT_EXISTS`、`..._TARGET_BLOCKED`、
  `..._STALE_START`、`..._INVALID_GEOMETRY`、`..._TIMEOUT`、`..._INVALID_PRECONDITION`。
- 恢复：`TEMPORARY_SUPPORT` 已在能力中声明；实际回收（`RECOVERY_CLEANUP` 移除临时方块）为后续闭环。
- 验收入口：`/function alice_test:place_course` + `alice:pathing_placer`（夹具给 8 圆石）。
- 已验证（2026-09-09，用户确认"没什么问题"）：`planned movements=8 cost=20.00`；
  3 次放置 `placed pos=2,63,66 / 3,63,66 / 7,62,66`；末段 `DESCEND (EXACT)` → `(8,62,66)`；
  `result status=COMPLETED segments=8/8 ticks=87 finalFoot=8,62,66`。证据
  `.alice-supervision/client-tests/pathing-r5-place-20260909/`。
- 已知限制（本轮暴露）：COLUMN 容差下下降段仍可能带动量滑入邻列，导致下一段
  `*_STALE_START`；当前只能诚实失败，需要"重规划 / snipsnap"（R4 后续闭环）自愈。
  另外：**没有垂直下降 Movement**（DESCEND 强制水平位移 1 格），目标正在脚下时规划器
  只能绕行（本轮场景已把目标改为下降落点规避）。

## D-032：夹具传送允许（重置 bot 位置）；"不穿墙"针对正常移动

- 状态：当前生效（用户 2026-09-09 澄清："重置 bot 位置允许传送"）
- 澄清：此前把"不能穿墙"误解为夹具传送限制，加了直线碰撞检查并会拒绝传送，**这是错的**。
  测试夹具把 bot 重置到固定起点是正常摆位语义（等同 `/tp`），**允许传送**。
- 真正的红线是**正常移动不得穿墙**，且已由两层保证：
  1. 原版碰撞（`travel()`/`move()`）——2 格高墙测试已证明 bot 停住不爬不穿；
  2. 执行器前置校验（`canWalkThrough`/`canSweepPlayer`）拒绝不可通行目标。
- 实现：`BlockInteraction.teleportSafely` 及其辅助已删除；所有夹具（`pathing_battery`/
  `pathing_session`/`pathing_breaker`/`pathing_placer`/`/alice come`）恢复为
  **带头部同步的普通传送**（`teleportTo(level, x, y, z, Set.of(), yRot, xRot)`，D-029 修复项）。

## D-031：R5 世界修改 Movement —— 原语统一 + BreakAndTraverse

- 状态：当前生效（用户 2026-09-08 指定 R5，并要求"挖掘和放置语义围绕 Baritone 升级、兼容"）
- 背景：`BreakAndWalkMovement`、`RoadBuildTask`、`RoadBuilder`、`BotCommand` 使用
  `level.destroyBlock(...)` **瞬间销毁**，没有工具选择/破坏进度/权限语义；放置也没有
  Baritone 式的面选择与视线校验。
- 决策（R5-1 原语）：
  - 新增 `com.dddgn.alice.action.BlockInteraction` + `BlockBreakSession` 作为**挖掘/放置唯一入口**：
    工具选择、触及距离（Forge `getBlockReach`）、朝向（头/身/俯仰同步）、破坏进度
    （`handleBlockBreakAction` + `getDestroyProgress` + `destroyBlockProgress` + 广播）、
    放置（Baritone `attemptToPlaceABlock` 语义：水平+下的支撑面扫描 + 面中心 +
    快捷栏白名单选方块 + 必要时潜行；**不做客户端射线可见性校验**——Alice 是服务端直接构造
    `BlockHitResult`，站在方块顶面时射线必然先命中顶面；**必须校验 `InteractionResult`
    并以服务器世界状态复核**，见 D-037）、破坏拒绝原因（复用 `BlockBreakSafety.clearingRefusal`）、
    破坏 tick 估算（对照 Baritone `getMiningDurationTicks`）。
  - `BreakAndWalkMovement` 改用 `BlockBreakSession`（失败码 `BREAK_OUT_OF_REACH` /
    `BREAK_PROGRESS_TIMEOUT`）。
  - 批量地形编辑（`RoadBuildTask`、`RoadBuilder`、`BotCommand` 道路应用）统一走
    `BlockInteraction.breakForBulkEdit(...)`：语义仍是批量编辑，但集中到唯一入口便于审计。
- 决策（R5-2 Movement）：
  - 新增 `MovementType.BREAK_AND_TRAVERSE` 执行器 + 工厂（PATH_ACCESS）：
    **语义为"破坏中间列 + 走到其后一格"（同层直线 2 格）**——破坏脚位/头位阻挡，
    再穿过被清出的通道走到目标；完成判定沿用 D-026/D-027。
    （首版曾把 `toFoot` 设为墙块本身，导致规划器从"墙块所在节点"继续扩张时，
    扫掠检查把自己脚下那格当障碍 → 永远 `UNREACHABLE`；已按 2 格位移修正。）
  - `SurfaceMovementProvider` 在水平候选被阻挡且可破坏时生成该候选，成本 = 水平成本 +
    破坏 tick/20；启发式仍可采纳。
  - 失败码：`BREAK_BLOCK_UNBREAKABLE`、`BREAK_BLOCK_PROTECTED`、
    `BREAK_AND_TRAVERSE_NO_SUPPORT`、`BREAK_AND_TRAVERSE_NOTHING_TO_BREAK`、
    `BREAK_AND_TRAVERSE_TIMEOUT`。
  - `MovementContext` 增加 `bot`（规划期需要工具/资源事实）；`PlannedMovementSpecs` 统一
    "规划→执行"转换，Battery/Chain 去重。
- 明确不做：`PLACE_STEP_AND_TRAVERSE`（R5-3）；不把这两个 Movement 接入 `MineTask`。
- 验收入口：`/function alice_test:break_course` + `alice:pathing_breaker`。
- 已验证（2026-09-09，用户确认"测试通过了"）：计划 6 段含 1 段 `BREAK_AND_TRAVERSE`；
  `block_break_done pos=3,64,66 ticks=6` + `pos=3,65,66 ticks=6`（石镐工具选择生效）；
  `result status=COMPLETED segments=6/6 ticks=58 finalFoot=7,64,66`。证据
  `.alice-supervision/client-tests/pathing-r5-break-20260909/`。

## D-030：legacy 路径兼容 0.6 台阶（补条件跳跃，参照 Baritone）

- 状态：当前生效（用户 2026-09-08 指定"legacy 兼容，一定要参考 Baritone"）
- 背景：D-025 把假人 `maxUpStep` 降到 0.6 后，legacy 路径执行器（`BasicMovement.applyToward`
  的消费者：`FollowTask`、`PathExecutor`、`DescendMovement`、`BreakAndWalkMovement`，
  以及 `WalkMovement`）遇到 1 格台阶会卡住——它们只会前进，不会跳跃。
- Baritone 参考：`MovementAscend.updateState:209-230` 的跳跃门控——
  必须先对准（横向速度 ≤0.1），再在"头顶干净"或"够近（沿轴 ≤1.2、横向 ≤0.2）"时按 JUMP。
- 决策：在 legacy 直驱原语 `BasicMovement` 中新增 `jumpIfStepAhead(...)`，语义对齐 Baritone：
  1. 必须在地面；
  2. 沿运动轴横向速度 ≤0.1（未对准不跳）；
  3. 前方 0.55/0.85 格处存在高度 > `maxUpStep` 的障碍；
  4. 该障碍上方可穿过（否则撞头不跳）；
  5. 半砖/台阶（顶面 ≤ maxUpStep）不触发，仍交给原版自动跨台阶。
  跳跃通过 `controller().jumpOnce()` 请求，由下一次 `aiStep()` 消费（legacy 直驱模型同样生效）。
- 接入点：`BasicMovement.applyToward`（覆盖 FollowTask / PathExecutor / DescendMovement /
  BreakAndWalkMovement）与 `WalkMovement.tick`（controller 输入路径）。
- 验收入口：`/function alice_test:legacy_ascend`（孤立长方体场景：低位平台 → 1 格台阶 → 2 格高墙），
  用 `/alice follow on` 验证"能爬 1 格、不爬 2 格"。
- 已验证：客户端实测通过（用户确认"测试和预期相同"）：1 格台阶可爬（日志 pos y=65），2 格高墙不爬；证据 `.alice-supervision/client-tests/legacy-ascend-20260908/`。

## D-029：旋转模型对齐 Baritone（只写身体 yaw，不直接写头）

- 状态：当前生效（用户 2026-09-08 反馈"头部朝向很古怪，参照 Baritone 修复"）
- 事实（字节码实证，1.20.1 `LivingEntity.tickHeadTurn`）：
  ```java
  yBodyRot += wrapDegrees(bodyRotArg - yBodyRot) * 0.3F;   // 身体每 tick 只追 30%
  if (|yRot - yBodyRot| > 50°) yBodyRot += ... ±50° 截断;
  yHeadRot = (|yRot - yBodyRot| < 90°) ? yRot : yBodyRot ± 90°;
  ```
  原版由 `yRot` 推导 `yBodyRot`（平滑）与 `yHeadRot`。
- 问题：Alice 的执行器每 tick 直接 `setYRot(yaw)` + `setYHeadRot(yaw)`，**从不设 `yBodyRot`**
  → 头瞬间到位、身体按 0.3 慢追 → 客户端渲染出头身扭转。
- Baritone 做法：`LookBehavior:99-100` **只写 `setYRot`/`setXRot`**，头身交给原版。
- 决策：执行器只写 `bot.setYRot(yaw)` + `bot.setYBodyRot(yaw)`（身体立即对齐），
  **不再直接写 `setYHeadRot`**，由原版 `tickHeadTurn` 派生头部朝向。
- **补充事实（2026-09-08 二次核查，修正上面的推断）**：
  - `LivingEntity.aiStep()` 在 `isEffectiveAi()` 为真时会调用 `serverAiStep()`（字节码 7235-7243）；
  - `Player.serverAiStep()` 的唯一实质动作是 `this.yHeadRot = this.getYRot()`；
  - `BotPlayer.isEffectiveAi()=true` 且 `BotPlayer.tick()` 显式调用 `aiStep()` → **`yHeadRot` 每 tick 确实会被同步为 `yRot`**（只是晚一个 tick，因为执行器在 `ServerTickEvent.END` 改 `yRot`）。
  - 因此"头不对"的主因**不是**头部未同步，而是**传送**：`ServerPlayer.teleportTo(double,double,double)` 走的是不同步头部的重载；只有 `teleportTo(ServerLevel, x, y, z, Set<RelativeMovement>, yRot, xRot)` 会额外 `setYHeadRot(yRot)`（字节码实证）。
- 追加决策：所有夹具传送统一改用带 `Set<RelativeMovement>` 的 5/6 参重载，保证传送后头身一致。
- 待验证：客户端观察头身是否一致（探针 `[RotProbe]` 已临时加入 `PathingBatteryTask` 与 `PathSessionDiagnosticTask`，验证后删除）。

## D-027：分段完成容差（消除链式下降回冲）

- 状态：已决策（用户 2026-09-08 选 A），**待 R4 PathSession 实施**
- 现象：连续下楼梯时 bot 每级落地后先朝目标中心回冲一下再进下一级（探针实测：落点 ab 0.06→0.28→0.35，超过 0.3 容差触发"回头重新瞄准"分支）。
- 根因：D-026 统一完成契约要求水平 ≤0.3；而 Baritone 的成功条件只有"脚位方块相等 + Y 稳定"（`MovementDescend:235`），不含水平距离，因此落在格内即完成、不回冲。
- 决策：完成容差改为**分段策略，由 PathSession/PathRequest 指定，执行器不得自行决定**：
  - 中间段（后面还有段）：脚位方块正确 + 落地即完成（对齐 Baritone）；
  - 最终段 / 安全关键站位（挖矿站位、悬空边缘、需要稳定站位处）：保持 ≤0.3 居中，保住 D-024/避险要求。
- 影响：`MovementHelper.isSettledAtFootPos` 的容差参数必须来自上下文而非硬编码；`DescendExecution` 等执行器的"回头重新瞄准"分支只在需要居中时启用。

## D-028：R3 搜索内核 → R4 PathSession 的实施顺序

- 状态：已决策（用户 2026-09-08）
- 顺序：先做 **R3 Movement-aware A* + PathPlan**（搜索内核，纯数据、服务端可验证），再做 **R4 PathSession**（多段执行、分段完成容差 D-027、周期健康检查、失败向上传递）。
- 理由：与 Baritone 架构顺序一致（PathFinder → PathExecutor）；搜索内核产出规范 `PathPlan` 后，PathSession 才有稳定的输入契约。
- 约束：R3 不接入 MineTask；`List<BlockPos>` 只作兼容投影；`SEARCH_LIMIT` 不得映射为 `UNREACHABLE`。

## D-025：假人台阶高度对齐真实玩家（maxUpStep = 0.6）

- 状态：当前生效，用户 2026-09-08 决策
- 事实（字节码实证，Forge 1.20.1-47.4.10 parchment jar）：`LivingEntity.<init>` 设 `0.6f`；`ServerPlayer.<init>` 设 `1.0F`；`LocalPlayer`（真实玩家 / Baritone 所在实体）无覆写 → 继承 0.6。
- 后果（改前）：Alice 假人能直接踩上整格方块，真实玩家必须跳跃。因此 ① Baritone `MovementAscend` 强制 `Input.JUMP` 是物理必然；② "实心墙会挡住过冲"的安全假设不成立（历史 `west: to=-1,64,3 → actualFoot=-2,65,3` 即过冲后踩上高一级）；③ Baritone 的 1.25/0.25 等常数与跳跃门控不可直接移植。
- 决策：`BotPlayer` 构造显式 `setMaxUpStep(0.6F)`，使假人物理与真实玩家一致；一格方块重新成为真正的障碍，上升必须走跳跃路径。
- 连带修改：`AscendExecution` 按 Baritone `MovementAscend:209-230` 的门控实现条件跳跃（落地 + 侧向速度 ≤0.1 + 沿轴距离 ≤1.2 + 横向偏移 ≤0.2）；legacy `WalkMovement` 上升同样补条件跳跃（否则挖矿路径上升会卡住）。
- 未验证：legacy 挖矿路径的上升用例需客户端复测。

## D-026：Movement 合法位置集与统一完成契约

- 状态：当前生效，用户 2026-09-08 决策（P0 多段链接前置）
- 合法位置集：工厂与执行器的起点校验接受 `{fromFoot, toFoot}` 两个合法脚位（含"已在目标"的幂等情形），不再要求严格 `blockPosition()==fromFoot`。目的是让上一段落点的微小偏差不再导致下一段 `*_STALE_START` 断链（对照 Baritone `Movement:72-74,112-114` 的 validPositions 与 `PathExecutor:324-343` 的 snipsnap）。
- **Descend 追加（2026-09-08 实测）**：合法位置集扩为 `{fromFoot, toFoot, toFoot.above()}`，对齐 Baritone `MovementDescend.calculateValidPositions()`。原因：D-027 的 COLUMN 容差会让上一段带水平动量结束，bot 继续滑入"目标列正上方、下落中"的位置（实测 `(0,63,45)→(0,63,44)`），若不接受该位置，链式下降第二段必然 `DESCEND_INVALID_PRECONDITION` 断链。
- 统一完成契约：四个执行器共用 `MovementHelper.isSettledAtFootPos(level, entity, footPos, maxHorizontal)` = 脚位列正确 + 支撑顶面稳定 + `onGround()` + 水平距目标中心 ≤ 0.3。禁止执行器各自定义"到位"（Ascend 曾以"Y 到位即停"导致水平偏移）。
- 多段验收入口：`/alice pathing chain <direction> <count>` → `ChainDiagnosticTask`，逐段创建 Movement，任一段工厂校验失败即整链失败（断链检测点）。

## D-024：Movement 落差红线——Bot 不允许超过一格的落差

- 状态：当前生效，用户 2026-09-08 明确设定的安全策略
- 规则：任何 Movement 原语规划与执行中，Bot 允许穿越的落差上限为一格。Descend 的计划目标本身就是唯一允许的一格落差；其过冲落点列只允许与目标同层落脚（支撑同高），过冲最坏情况是同层踉跄，不叠加任何额外落差。过冲列存在更深坠落通道（无论有无支持面）一律 `DESCEND_REJECTED_OVERSHOOT_CLIFF` 拒绝，熔岩/火/岩浆块 `DESCEND_REJECTED_LANDING_HAZARD` 拒绝。
- **细化（2026-09-08 用户决策 A）**：标准楼梯（每级下降 1 格、宽 1 格）逐级下降时，目标列再往前一格必然是"下一级台阶"（比目标低 1 格）。若仍要求过冲列同层，**楼梯链的每一级都会被拒绝**，与多段链接目标冲突。因此过冲列允许两种落脚面：① 与目标同层；② 比目标低 1 格的下一级台阶（总落差 2 格内），两者都必须本身可行走且无即死危害；更深（≥2 格）或不可行走仍拒绝。执行层若真落得比目标更低，立即以 `DESCEND_OVERSHOT_BELOW_TARGET` 诚实失败，不再等超时。
- 影响：未来 PathSession/搜索器生成 Movement 候选时必须继承此红线——相邻落脚面比当前路径低超过一格的边不可通行；不得以"制动可靠""3 格内无摔落伤害"等理由放宽。 多格落差（楼梯井、深坑下降）如需支持，必须作为显式的新 Movement 类型（带专用前置/后置/回收契约）另行立项评审。
- 实施位置：`DescendExecutionFactory`（过冲列同层校验）；执行层制动/居中控制（`DescendExecution`）只负责精度，不承担落差安全。
- 前提修订（D-025 后）：过冲列"实心墙会挡住过冲"这一前提**只在 maxUpStep=0.6 时成立**。D-025 之前假人台阶为 1.0，一格墙会被踩上去；`DescendExecutionFactory` 的"实心墙放行"分支依赖 D-025 生效。

## D-014：执行器不能用单一通行检查覆盖所有 Movement

- 状态：稳定
- `PathExecutor` 必须执行当前 Movement 的专用前置条件和后置条件，并报告动态阻挡；不能用统一的“段目标当前必须可通行”检查误伤需要先破坏障碍的 Movement，也不能因此把 `BLOCKED` 偷换为 `UNREACHABLE`。
- 影响：这是未来修复 `PathExecutor`/`BreakAndWalkMovement` 契约的方向，不是本次文档更新的代码授权。

## D-035：PathSession 段计时缺失（无超时）与段内漂移检测

- 状态：当前生效（缺陷修复，2026-09-09 客户端扰动测试暴露）
- 事实（`latest.log` 实证，session `r4-session-cb23787c`）：第 30 tick 夹具把 bot 从 `3,64,66` 平移到 `3,64,67`（缺口列 x=2..3，整列无支撑）→ bot 立即下落；自愈连续重规划 2 次（其中一次从**下落中的** `3,64,67`、一次从 `3,63,67` 起算），最后一段 `ASCEND 3,63,67 → 4,64,67` 开始后 bot 掉到坑底，**连续 `controller_jump_once` 空跳 17 次（约 10 秒）直到玩家退出，会话从未结束**。
- 根因（代码实证）：`PathSession.segmentTicks` **从未自增**（全文件只有读取与归零），导致
  1. 单段超时判断 `segmentTicks > segmentTimeoutTicks()` **永远为假 → 段超时形同不存在**；
  2. 周期健康检查 `segmentTicks % HEALTH_CHECK_INTERVAL == 0` **每 tick 都成立**（0%5==0），周期语义失效。
- 修复：
  1. 在段执行路径（`execution != null`）内 `segmentTicks++`，恢复超时与 5 tick 周期健康检查；
  2. 新增 `driftedOutOfSegment()`（对照 Baritone `PathExecutor#playerInValidPosition`）：落地状态下，脚位低于本段两端最低脚位 1 格以上，或同时离开两端 3 格以上 → `SEGMENT_STALE_START` 进入自愈闭环，不再对着不可达目标空跳；
  3. `handleFailure` 在**空中不吸附/不重规划**（等落地再处理），避免从下落位置产生失真计划（本次日志中的两次失真重规划即此）。
- 保留兜底：`PathSessionDiagnosticTask.MAX_TASK_TICKS = 600` 任务级上限（正常场景 87 tick 完成）。
- 红线：段超时是"确定性执行器必须能终止"的底线，任何会话实现都不得让单段无限运行。

## D-036：内核路线——Alice 是 Baritone 兼容内核，只做目标差异自制

- 状态：当前生效，用户 2026-09-09 重申并定为路线约束
- 原则：
  1. Alice 的差异**只在目标层**：LLM 目标级决策、服务端权威、bot 假人、任务/权限/库存真相、挖矿 `HARD_PATH`；
  2. 其余（搜索、Movement 原语、执行器状态机、自愈 snipsnap/重规划、段超时、成本模型、跳跃门控、完成判定）**默认对齐 Baritone**，不自制替代内核；
  3. 抄语义不抄常量（物理常量按 Alice 实体重标定，见 D-025）；
  4. 必须偏离时在 `docs/AI_DECISIONS.md` 登记：Baritone `文件:行` + Alice 特有约束 + 可验证后果，未登记即违规；
  5. 反补丁红线：同一处第二次失败禁止继续加 `if`/epsilon/延迟/重试/特判，先逐项对照 Baritone。
- 参考源码（2026-09-09 更新，对应用户的 Baritone 对照实例）：
  - **主树（1.20.1，v1.10.5，commit `8c55ad0`）**：`/home/fb486/projects/reference/baritone-1.20.1/`
    —— 与对照实例 `Bariton_contrast` 的 `baritone-standalone-forge-1.10.5.jar` 同版本，行号/语义引用以它为准；
  - 辅树（1.21.4，commit `64333af`）：`/home/fb486/projects/reference/baritone/`，仅用于交叉核对。
  - 对照测试流程见 `docs/BARITONE_CONTRAST_TESTING.md`。
- 落地位置：skill `alice-baritone-kernel-alignment`、`AGENTS.md` 架构边界、`docs/AI_DEVELOPMENT_PLAYBOOK.md` 寻路规则。
- 首个待办（体验，非 bug）：段间无条件 settle 造成视觉顿挫 → 对照 `PathExecutor:232-236`（SUCCESS 后同 tick 推进、不松输入）改为条件 settle。
- 关联：D-035 的漂移检测当前是距离启发式，待改为 Baritone `getValidPositions().contains(feet)` 判定（`PathExecutor:101-124`）。

## D-037：第一刀内核对齐（P0 安全与正确性）

- 状态：已实施，待客户端验证（用户 2026-09-09 选定范围 A）
- 依据：`docs/R4_BARITONE_ALIGNMENT_AUDIT.md`（64 项对照：39 未登记偏离 / 13 缺失 / 7 已登记 / 4 对齐）
- 5 项改动（全部为"照抄 Baritone 语义"）：
  1. **流体不可挖**：`BlockBreakSafety.explicitTargetRefusal` 增加 `fluid_block`；
     `BlockInteraction.estimateBreakTicks` 对流体返回 `+∞`。对照 `MovementHelper.getMiningDurationTicks:588-590`。
     修复前 `BREAK_AND_TRAVERSE` 会把岩浆当清障方块（代价有限、约 10000 tick），规划可能选择"挖岩浆"。
  2. **放置校验结果**：`BlockInteraction.placeAt` 检查 `InteractionResult.consumesAction()`，
     并以服务器世界状态（`placeAt` 是否仍可替换）复核；失败则尝试下一个候选面。
     对照 `BlockPlaceHelper:48-52`。修复前无条件返回 `PLACED`（假成功 → 执行器重试到超时）。
  3. **危险方块扩表**：`MovementHelper.avoidWalkingInto` 增加任意 `BaseFireBlock`（火/灵魂火/营火）、
     仙人掌、甜浆果丛、末地传送门、蛛网、气泡柱。对照 `MovementHelper.avoidWalkingInto:350-360`。
  4. **删除"任意 BlockItem"兜底**：`BlockInteraction.findPlaceableSlot` 只接受一次性方块白名单，
     无则返回 -1。对照 `Settings.acceptableThrowawayItems:230-235`。修复前会用火把/花去"搭台阶"。
  5. **D-031 文档修正**：放置不再声称"视线校验"（见上）。
- 附带修复：`en_us.json` / `zh_cn.json` 尾逗号导致客户端跳过语言文件（`latest.log` 实证 `MalformedJsonException`），
  并补齐 en_us 缺失的 pathing 条目。
- 测试夹具（零参数，两个场景共用一个检查器 `alice:pathing_fluid_guard`，目标 `(4,64,66)`）：
  - `alice_test:fluid_course`：x=2 是 **2 格高充水墙**（判别性用例——脚位高度的岩浆必然流动并毁场景，
    故用"含流体状态的方块"，同一代码路径 `state.getFluidState()`）。期望 `UNREACHABLE`；`REACHED` 即缺陷。
  - `alice_test:lava_course`：x=2..3 是**四周封闭的静止岩浆池**（位于支撑层，可重复使用）。
    期望 `UNREACHABLE`，并观察 bot 不朝岩浆移动。
- 未改：成本模型 / 启发式 / 执行会话契约（属后续刀次，见审计 §4）。
- 验证等级：`IMPLEMENTED` + `COMPILES`；客户端待测。

## D-038：任务/执行器驱动相位对齐 Baritone（END → START）——**已撤回（前提不成立）**

- 状态：**已撤回**（2026-09-09；用户授权尝试后实测无差异，且 Forge 源码证明前提错误）
- 撤回依据（Forge 1.20.1-47.4.10 `patches/net/minecraft/server/MinecraftServer.java.patch:93-107`）：
  `onPreServerTick`（START）在 `++tickCount` 与 `tickChildren` 之前触发，`onPostServerTick`（END）在全部实体 tick 之后触发。
  两者都位于 **physics(N-1) 之后、physics(N) 之前**：观察到的状态与输入生效的 tick 完全相同 → **相位改动对延迟是 no-op**。
- 实测佐证（2026-09-09 客户端，改动生效时）：`pathing_battery` 8/8 PASS；`pathing_placer` 93→88 tick、
  `pathing_disturber` 93→93 tick、`pathing_breaker` 70 tick，无系统性变化。
- 结论：**1 tick 控制延迟是"先观察后动作"的离散回路固有属性，Baritone 同样存在**（其 `InputOverrideHandler`
  读到的也是上一次 `aiStep` 后的位置）。因此无需对齐，也不需要为此重构 tick 顺序。
- 保留：`PathSession.segment_done` 的 `ticks=` 遥测（标定/回归对比用）。
- 历史描述（撤回前）：
- 事实：`BotManager.onServerTick`（原 `:548-559`）在 `TickEvent.Phase.END` 驱动
  任务 → `PathSession` → 各执行器 → `bot.controller()` 输入；而 `BotPlayer.tick()` 的物理
  （`aiStep()`）在实体 tick 内执行 → `END(N-1)` 设定的输入要等 **tick N** 才被消费 = **1 tick 延迟**。
  Baritone 的 `InputOverrideHandler` 在实体 tick **之前**设置输入 = 0 延迟。
- 决策：驱动相位改为 `TickEvent.Phase.START`。信息新鲜度不变（看到的仍是"上一次物理完成后的状态"），
  只是把输入应用提前 1 tick。
- 预期收益：每个 Movement 少 1 tick 延迟（TRAVERSE 6→5 等）；制动早 1 tick → 过冲窗口变小；
  Baritone 的距离/速度门控阈值（`flatDistToNext ≤1.2`、`sideDist ≤0.2`）更可移植。
- 风险：D-026/D-027 的容差与 settling 判据是在"有 1 tick 延迟"的前提下调出来的
  → 必须重跑 `pathing_battery` / `pathing_session` / `pathing_breaker` / `pathing_placer` / `pathing_disturber`。
- 观测手段：`PathSession` 的 `segment_done` 日志新增 `ticks=` 字段（永久遥测，用于 Q3/Q7 标定与回归对比）。
- 回退：把 `Phase.START` 改回 `Phase.END`。

## D-039：验收场景必须先用模拟器验证"能否判别"（Q7 dip_course 教训）

- 状态：当前生效（流程约束，2026-09-09）
- 教训：Q7 首个验收场景用 **1×1 深坑**，期望"旧模型走下降、新模型走绕路"。实测
  `[DipRoute] first=DIAGONAL movements=2 cost=2.83`——规划器用**两个对角从坑角绕过去**了，
  比下降（3.0）和绕路（4.0）都便宜，因此该场景**根本不触发"下降 vs 绕路"的取舍**，无法判别。
- 流程约束：设计验收场景时，必须先用 `tools/simulate-scene-plan.py`（复刻 Alice 的 Movement 集合 +
  D-024 过冲检查 + 成本模型）穷举验证：① 旧模型与新模型选出的路线不同；② 新模型选出的路线**实测更快**。
  工具自校验：对旧 1×1 坑场景推演得到 `cost=2.83 first=DIAGONAL`，与客户端日志完全一致。
- 现行场景（`dip_course`）：坑 x=-1..2、z=65（1 格深），起点 `(0,64,66)`，目标 `(-1,64,63)`。
  旧模型 `DESCEND+ASCEND+DIAGONAL`（cost 4.41，≈34 tick）；标定后 `TRAVERSE+DIAGONAL+DIAGONAL+TRAVERSE`
  （cost 4.66，≈28 tick）→ 翻转且更快 6 tick。

## D-040：成本模型按 Alice 实测标定 + 启发式重写（Q7）

- 状态：已实施，待客户端验证（用户 2026-09-09 确认方案）
- 标定来源（Alice 自己的实测段耗时，`.alice-supervision/client-tests/pathing-r3-battery-20260908/evidence/r3-battery-key-lines.log`）：
  TRAVERSE 6 / DIAGONAL 8 / ASCEND 10 / DESCEND 16 tick → 单位 = "走路 1 格"（{@code WALK_ONE_BLOCK_TICKS = 6}）。
- 成本常量（旧 → 新）：
  | 动作 | 旧 | 新 | 实测 tick |
  |---|---|---|---|
  | TRAVERSE | 1.00 | 1.00 | 6 |
  | DIAGONAL | 1.41 | 1.33 | 8 |
  | ASCEND | 2.00 | 1.67 | 10 |
  | **DESCEND** | **1.00** | **2.67** | 16 |
  旧模型把**最贵的动作当成最便宜**（下降与走路同价），这是本次修正的核心。
- 破坏成本：`breakTicks / 20` → `(breakTicks + BREAK_PENALTY_TICKS) / WALK_ONE_BLOCK_TICKS`
  （对照 Baritone `getMiningDurationTicks:599-604` 的 `1/strVsBlock + blockBreakAdditionalPenalty`，
  旧写法把 tick 当秒折算，导致"挖穿比走路便宜"）。
- 放置成本：`4.0` → `20.0 / 6 = 3.33`（对照 Baritone `blockPlacementPenalty = 20 tick`）。
- 启发式（`GoalFoot.heuristic`）：欧氏 + 对称 |dy| → **octile 水平 + 非对称竖向**：
  `h = straight×1.00 + diagonal×1.33 + (dy>0 ? dy×0.67 : -dy×1.67)`，
  竖向增量 = 垂直移动成本 − 它同时覆盖的 1 格水平成本（1.67−1.00 / 2.67−1.00）。
  结构与 Baritone `GoalBlock.calculate = GoalYLevel + GoalXZ` 一致；**可采纳且一致**（逐边可验证），
  旧公式在斜向下降时高估 1.0 → 不可采纳。
- A* 系数：`{1.5, 2, 2.5, 4.5}` → Baritone 的 `{1.5, 2, 2.5, 3, 4, 5, 10}`。
- 验收标准：① 既有场景仍 `COMPLETED`；② 同场景总 tick 不劣化（±10%）；③ 无新增危险/悬空/卡边；
  ④ `dip_course` 路线翻转：基线 `first=DESCEND movements=3 cost=4.41`（≈34 tick，客户端实证）
  → 标定后 `first=TRAVERSE movements=4 cost=4.66`（≈28 tick，模拟器预测）。
- 未做：`segmentTimeoutTicks` 的 `*20` 保持为安全余量（不是单位换算），已在注释中说明。

### D-040 补记：标定后的两处可观察变化（2026-09-09 客户端实证）

1. **`dip_course` 路线翻转成功**：`[DipRoute] status=REACHED first=TRAVERSE movements=4 cost=4.66`
   （基线 `first=DESCEND movements=3 cost=4.41`）——与模拟器预测完全一致。
2. **`pathing_battery` 的 `plan_flat` 从 `REACHED(2)` 变为 `REACHED(4)`**：该检查原本从 hub `(0,64,46)`
   向东 2 格，路径要跨过台阶 `(1,64,46)`。旧模型 ASCEND+DESCEND = 3.0（2 步）；标定后 = 4.34，
   而绕行 4 步 = 4.0 → 规划器改选绕行。实测 4 步 ≈ 24 tick < 上+下 ≈ 26 tick，**新选择确实更快**。
   已把该检查改为向南 2 格（平台内部真平地，稳定 `REACHED(2)`），东侧台阶另由 ascend 项覆盖。
3. **`plan_budget` 从 `SEARCH_LIMIT` 变为 `UNREACHABLE`**：标定后启发式一致（D-040），
   每个节点只展开一次，200 节点预算已足以穷尽约 195 格平台 → 搜索**证明**不可达（正确结果），
   但该检查原本用于验证"预算耗尽 ≠ 不可达"的 SEARCH_LIMIT 路径。已把预算收紧到 32 节点，
   使其重新触发 SEARCH_LIMIT。

## D-041：站立判定混合化（canWalkOn）+ Ascend 防御性前置检查

- 状态：**已通过客户端验证**（用户 2026-09-09；`[FenceGuard] status=UNREACHABLE movements=0 result=PASS`，回归全过）
- **A 站立判定**（`MovementHelper.canWalkOn`）：旧实现"碰撞非空即可站"会把栅栏/墙/铁栏杆/门/蜂蜜块
  当作支撑面。改为混合判定（对照 Baritone `canWalkOnBlockState:387-426` + `isBlockNormalCube:755-771`）：
  1. 整格碰撞形状（`Block.isShapeFullBlock`）→ 可站（含模组整格方块），**蜂蜜块除外**（Baritone:389）；
  2. 否则按"已知有碰撞但不能站"的薄/半高面排除：栅栏 / 栅栏门 / 墙 / 铁栏杆与玻璃板 / 门 / 蜂蜜块 /
     滴水石 / 紫水晶簇 / 末地棒 / 避雷针 / 可可 / 花盆；
  3. 其余（台阶/半砖/箱子/模组半格方块）**保持可站** —— 这是 Alice 对模组方块的兼容选择，
     与 Baritone 的严格白名单不同，**已登记为差异**。
  - 活板门**不排除**（关闭的活板门是真实可站地面），同样登记为差异。
- **B Ascend 前置**（`AscendExecutionFactory`，对照 Baritone `MovementAscend`）：
  1. 源头上方 3 格为 `FallingBlock` 且上方 1 格可穿过 → `ASCEND_FALLING_BLOCK_ABOVE`（:96-108，防窒息）；
  2. 脚下为可攀爬方块（梯子/藤蔓/缠怨藤/垂泪藤，`isClimbable` 对照 Baritone:573-580）→ `ASCEND_FROM_CLIMBABLE`（:115-117）。
  - 未采用：Baritone 的"头顶净空即提前起跳"分支（`:219-221`）——Alice 段间无动量，提前起跳会浪费跳跃；
    改为运行期**守卫**（头顶被挡则不起跳，避免撞天花板空跳）。
  - 未移植：bottom-slab 起跳规则（`MovementAscend:119-131`）——Alice 脚位是方块对齐模型，
    半砖起跳需先解决脚位表示，另行立项。
- 验收夹具（零参数）：`alice_test:fence_course` + `alice:pathing_fence_guard`
  （期望 `UNREACHABLE`；修复前为 `REACHED`，规划器会尝试跨栏）。
  B 的两项前置检查是**防御性**的（浮空沙会下落、梯子上无法稳定站位），无法构造稳定客户端场景，
  以代码 + 静态推理验证，见 D-041 说明。

## D-042：执行器契约对齐（待办 C）+ 串联回归测试

- 状态：已实施，待客户端验证（用户 2026-09-09 指定"先做串联回归，再进入待办 C"）
- **C1 分段容差生效**（D-027 落地）：`TraverseExecution` / `AscendExecution` / `DiagonalExecution` 新增
  `tolerance` 字段，与 `BreakAndTraverseExecution` / `PlaceStepAndTraverseExecution` 统一改为
  `COLUMN ? 脚位列正确 && y-toY<0.5 : isSettledAtFootPos(...,0.3)`；此前 5 个执行器硬编码 EXACT，
  会话给的 COLUMN 被忽略（只有 Descend 生效）。
- **C2 超时收敛单层**：删除 `BreakAndTraverseExecution.MAX_TICKS(400)` 与
  `PlaceStepAndTraverseExecution.MAX_TICKS(200)` 两处自制硬上限，统一由 `PathSession.segmentTimeoutTicks()`
  （按规划成本动态放宽）负责；`DescendExecution.FAKE_DEST_MAX_TICKS` 与 `AscendExecution` 的 settling 守卫
  属**语义相位计时**，保留。
- **C3 取消清理破坏进度**：`BlockBreakSession.abort()`（`destroyBlockProgress(...,-1)`）新增，
  `BreakAndTraverseExecution.cancel()` 调用（对照 Baritone `PathExecutor:603-608` → `BlockBreakHelper:43-50`）。
- **C4 校验失败路径纳入超时**：`PathSession` 新增 `startSlotTicks`，在 `execution == null`
  （段校验失败/等待落地）期间也计时并受段超时约束；此前该路径只受任务级 600 tick 兜底。
- **串联回归**（常规回归入口）：`alice:pathing_regression` 一次右键按顺序跑
  `pathing_course`（规划+执行）/ `place_course`（放置+执行）/ `break_course`（破坏+执行）/
  `fluid_course`·`lava_course`·`fence_course`（拒绝行为）/ `dip_course`（路线偏好），
  每场景自动建地形（`<scene>_terrain`）、传送 bot、补齐圆石与石镐，最后输出
  `[Regression] SUMMARY <scene>=PASS/FAIL ...`（聊天 + 日志）。
  场景函数已拆分为 `<scene>_terrain`（仅地形）与 `<scene>`（地形+传送+物品+提示），供回归复用。

### D-042 修复：岩浆池场景封口高度（2026-09-09 串联回归实证）

- 现象：串联回归 `lava_course=FAIL detail=REACHED`；单独跑同一场景也是
  `[FluidGuard] status=REACHED movements=11`。
- 根因：为阻止岩浆横向流动，场景在 `z=61/z=71` 放了 1 格高（y=63）的封口石块；
  其**顶面正好是脚位 y=64**，于是成为可站面，对角移动可踩着封口从池外绕过去
  （`movements=11`）。用 `tools/simulate-scene-plan.py` 复刻场景后**精确复现了 11 步路径**
  （`DIAGONAL+TRAVERSE+TRAVERSE+TRAVERSE+DIAGONAL+TRAVERSE+DIAGONAL+TRAVERSE×4`）。
- 修复：封口改为 **2 格高（y=63..64）**，顶面被占 → 模拟器与预期一致给出 `UNREACHABLE`。
- 二次修复（用户 2026-09-09 手动验证）：**封口必须 ≥3 格高（y=63..65）**。
  机制（用户观察，权威）：封口第一格与平台支撑同层（y=63），因此 **2 格高时封口顶面 y=65 只比脚位 64 高 1 格**
  → bot 可以直接踩/跳上去沿墙绕行；3 格高时顶面高 2 格、且中间那格是墙块 → 踩不上去。
  用户手动补上第 3 格后 `UNREACHABLE`，按成功验收。
  （我曾推测是"放置造脚位再上升"，该推测**未被证实且与用户观察不符**，已撤回。）
- 场景设计规则（新增，纳入 D-039 校验清单）：
  1. **封堵/围挡方块不得在脚位高度形成可站面**；
  2. **允许世界修改的场景，围挡高度必须 ≥3 格**（顶面距脚位 ≥2），否则"放置造支点 + 上升"可绕过；
  3. `tools/simulate-scene-plan.py` 目前**不建模放置/破坏**，因此"允许世界修改"的场景结论
     必须由客户端串联回归复核，不能只看模拟器。

## D-043：重规划决策下沉到任务层（Q4 / 待办 D）

- 状态：**已通过客户端验证**（2026-09-09：`wall_placed at=5,64,66` → `[PathRetry] replan replans=1 reason=INVALID_PRECONDITION` → `COMPLETED replans=1`）
- 分层依据（对照 Baritone）：`PathExecutor` 遇到计划失效只 `cancel()` 上报，由 `PathingBehavior:154-193`
  重新 `findPathInNewThread`；Alice 里对应的决策层是**任务层**（未来由 LLM 目标层裁决）。
- 改动：
  1. `PathSession` **删除内置 `replan()`/`MAX_REPLANS`/`replans`**，`handleFailure` 只保留
     **重同步（snipsnap）** + 结构化失败上报；新增只读 `projectedFootPath()` 供夹具观察当前计划。
  2. 新增 `task/PathRetryRunner`：任务层重试策略——每次从**当前脚位**重新规划，
     可重试状态为 `STALE / TIMEOUT / MOVEMENT_FAILED / INVALID_PRECONDITION`，
     预算 `DEFAULT_MAX_REPLANS=2`，逐次日志 `[PathRetry] planned/replan ...`。
  3. `PathSessionDiagnosticTask` 改为驱动 `PathRetryRunner`；`PathingRegressionTask` 同步改造。
     结果行改为 `[R4 Session] result ... replans=N`。
- 新夹具（零参数）：`place_course` + `alice:pathing_waller` —— 第 30 tick 在**计划路径前方第 2 段**
  放置一块石头（直接让计划失效，而不是位移 bot）。期望：
  `[R4 Fixture] wall_placed` → `[PathRetry] replan ...` → `COMPLETED replans=1`。
  与 `alice:pathing_disturber`（位移 → 重同步）互补：两者分别验证 D-043 的两条路径。
- 兼容：重试行为与之前等价（仍是"从当前脚位重算、最多 2 次"），但决策点与可观测性转移到任务层。

### D-024 补充（2026-09-09，Q1①/③）：一格红线 = 局部可回收性不变式；FALL 暂不采纳

- **不变式**：在"单次 Movement 落差 ≤1 格"下，**每条被执行的边都存在局部逆边**：
  TRAVERSE/DIAGONAL 对称；ASCEND 的逆是 DESCEND（起点是刚站过的可站脚位、空间刚被占用）；
  DESCEND 的逆是 ASCEND；PLACE_STEP 的逆是 ASCEND；BREAK_AND_TRAVERSE 的逆是反向两步 TRAVERSE（中列已破坏）。
  → bot 永远能撤回上一步，**不需要全局搜索**。
- 落差 ≥2 时，逆边需要"悬崖腰部存在可站中间台阶"，通常不存在 → 只能依赖全局重规划
  （2026-09-09 扰动实证：bot 掉进 4 格缺口后重规划 `UNREACHABLE`、空跳不终止）。
- **裁决（Q1③）**：保持一格红线；Baritone 的 `MovementFall` / `MovementParkour`
  （`maxFallHeightNoWater=3`）**暂不采纳**——它们与 `PathExecutor.possiblyOffPath:317-323` 的下落特例
  （改用水平距离判定）成套，单独移植会破坏漂移判定；若将来放宽，必须**整套移植 + 落点可回收守卫**
  （见 `docs/ALIGNMENT_OPEN_QUESTIONS.md` Q1-D）。

## D-044：待办 E —— 清理与裁决（WorldView / canSweepPlayer / FALL / 统计）

- 状态：已实施（2026-09-09）
- **⑤ 删除空接口** `pathing/core/WorldView`：全仓零实现、零引用（仅 `MovementContext` javadoc 提及）。
  R7 异步搜索若立项，按 Baritone `BlockStateInterface`（chunk 缓存 + `isLoaded`）重新落地，而不是留空抽象。
- **⑨ `canSweepPlayer` 对照**（Alice 自制 AABB 扫掠 vs Baritone `MovementDiagonal:220-249` optionA/optionB）：

  | 维度 | Alice | Baritone |
  |---|---|---|
  | 判定方式 | 玩家尺寸（0.6×1.8）直线扫掠 | 两个角格 optionA/optionB 的通行/挖掘成本 |
  | 单侧阻挡 | **拒绝**（扫掠与角块相交） | **允许**（一侧可绕，加挖掘/惩罚成本） |
  | 对角升降 | 不支持（dy=0） | 支持（dy=±1，`allowDiagonalAscend`） |

  **结论：不是缺陷。** Alice 的 `DiagonalExecution` 走的是真直线对角，与其规划谓词一致
  （不存在"可规划不可执行"）；Baritone 的对角可"切角"是因为它的执行器按 L 形绕行。
  **登记为已对齐差异，不修改**；若将来要对齐，需同时实现"切角执行"（成本更高，且要处理角块碰撞）。
- **Q1①**：D-024 升级为局部可回收性不变式（见上）。
- **Q1②**：新增只读统计 `PathingStats`：记录规划期下降候选被拒原因
  （`descend_precondition` / `descend_overshoot_unsafe`），每次规划结束输出
  `[PathingStats] ... status=... goal=...`，用于将来量化"一格红线让多少目标变成 UNREACHABLE"。
- **Q1③**：`MovementFall` / `MovementParkour` 登记为**暂不采纳**（见上）。
- **⑥ legacy 收口：本轮不做。** 范围：`pathing/movement/*`（BasicMovement / WalkMovement /
  BreakAndWalkMovement / MovementConstraints / DescendMovement / PillarMovement）+ legacy
  `pathing/AStarPathfinder`（零启发 Dijkstra）+ `BotMiner` 自带破坏原语，由 `PathExecutor` /
  `FollowTask` / `MovementPlanCompiler` 驱动，**触及挖矿 HARD_PATH 链路** → 需单独立项 + 完整回归。

## D-045：legacy 寻路任务先禁用、再迁移（方案 B）

- 状态：已实施（用户 2026-09-09 明确选择方案 B）
- 背景：仓库内并存两套寻路内核——
  - **legacy**：`pathing/AStarPathfinder`（**零启发 Dijkstra**，见 `:118-119` 注释）+ `pathing/movement/*`
    + `PathExecutor`，成本表 `TRAVERSE 1 / DESCEND 1 / ASCEND 2 / DOWNWARD 3`（对角与直走同价、下降与走路同价）；
  - **新内核（R3/R4）**：`core/search/AStarMovementSearch`（真 A*：octile 水平 + 非对称竖向，D-040）。
  生产任务（`MineTask`/`FollowTask`/`WalkToTask`/`PlaceTask`/`TransferTask`/`DropCollectionTask`）
  全部走 legacy；新内核此前只在测试面使用。
- 决策：**先把 legacy 任务入口统一禁用**，新内核逐个接入后再启用对应入口。
  开关：`BotManager.LEGACY_PATHING_TASKS_ENABLED = false`；
  统一门禁 `legacyTaskDisabled(task)` 输出 `legacy_pathing_disabled task=... reason=migration_to_core_kernel`。
- 受影响入口：`assignMine`（含内部 `DropCollectionTask`）、`assignFollow`、`assignWalkTo`、`assignPlace`、
  `assignTransfer`。随之不可用的还有挖矿测试场景（`scene_a/b/c`、`movement_*`、`legacy_ascend`）与 `BotSelftest`。
- **不受影响**：新内核全部测试面（`alice:pathing_battery` / `pathing_regression` / `pathing_session` /
  `pathing_dip_route` / `pathing_fluid_guard` / `pathing_fence_guard` / `pathing_placer` / `pathing_breaker` /
  `pathing_walller` / `pathing_disturber`）。
- 迁移顺序（能力补齐后逐个开启）：`WalkToTask` → `FollowTask` → `PlaceTask`/`TransferTask` → `MineTask`（HARD_PATH，最后）。
  - 进度：**`WalkToTask` 已于 D-060 迁移并解除门禁**；其余仍禁用。
- 能力前置（迁移前必须补齐）：新内核缺 **`DOWNWARD`（纯垂直下 1 格）**；`PILLAR`（垂直上 1 格）与 DOWNWARD
  是互逆对，**单独存在 PILLAR 时不可回收**（上去了下不来）→ 已按用户裁决**暂不采纳**（见 D-046）。

## D-046：风险模式 H/G/S —— 决策确认与预留（暂不实现）

- 状态：**已决策，预留**（用户 2026-09-09 确认三模式；实现推迟到功能细节完成后）
- 决策：
  1. 三模式确认：**H**（Baritone 式：高风险 / **局部可回收性较低**）、**G**（守卫式：高可回收性）、
     **S**（智能：默认）；
  2. **默认 = S**；玩家必须有入口**强制** H 或 G；
  3. **覆盖粒度**：不只全局——**每个主要任务层都可让玩家自定义风险模式**；未定义时用默认的 S；
  4. 选择链：**任务声明前置 → 内核推导 → LLM 覆盖 → 玩家强制**；
  5. **数据采集推迟**：H/G 对比测试成本过高，等 beta/内测版有足够真实数据再定默认值；
  6. 风险开关（10 项）与不可协商安全底线见 `docs/RISK_MODES_DISCUSSION.md`。
- **预留方式（重要）**：只做**文档与决策预留**，暂不引入 `RiskProfile` 空类型/空字段——
  `WorldView` 的教训是"零实现的空抽象会被审计记为缺陷"。实现时再一次性引入
  `RiskProfile`（贯通 `PathRequest → MovementContext → provider → 执行器`），避免留死代码。
- 前置依赖：G 模式需要 `DOWNWARD`（垂直 1 格下落，含"禁止挖脚下"开关与逃生守卫）；
  H 模式需要 `MovementFall` + Alice 版下落特例（`PathExecutor.possiblyOffPath:317-323` 等价物）；
  PILLAR 需与 DOWNWARD 成对启用。

## D-047：执行层对齐 Baritone —— 合法位置集重同步 + 前瞻封死检测

- 状态：已实施，待客户端验证
- **段内重同步改为合法位置集判定**（对照 `PathExecutor:101-124`）：
  `PathSession` 每 tick 检查脚位是否在当前段的 `validPositions` 内；不在则
  **先向前（更早的段）、再向后（跳 1~2 段）搜索**能容纳该脚位的段并从该段继续（`[R4 Session] resync`），
  上限 `MAX_RESYNCS=5`。合法位置集按 Movement 类型给出：TRAVERSE {from,to}；DIAGONAL 加两个角格；
  ASCEND 加 `from.above()`；DESCEND/PLACE_STEP 加 `to.above()`；BREAK 加中间格。
  - 同时**删除会话内 `trySnipsnap`**（它把"重同步"当失败处理，与 Baritone 语义不符）；
    找不到重同步点时仍用 `driftedOutOfSegment()` 距离兜底 → 上报 → 任务层重规划（D-043）。
- **前瞻封死检测**（对照 Baritone `costVerificationLookahead=5`，此处用可达性代理）：
  周期健康检查除当前段外，另检查**后续 3 段**的目标是否仍可通行/可站；被封死 →
  `SEGMENT_FUTURE_BLOCKED` → 会话上报 `BLOCKED`（**复活了此前声明但永不产生的死状态**）→
  任务层 `PathRetryRunner` 重规划（`BLOCKED` 已加入可重试集合）。
- 与 D-035 的关系：D-035 的"距离启发式漂移判定"降级为**兜底**（重同步失败时才用），
  主判定改为 Baritone 的合法位置集——这条正是审计 §3.B 登记的待改项。

## D-048：DOWNWARD（垂直下落 1 格）—— 新内核补齐垂直下降

- 状态：已实施，待客户端验证（用户 2026-09-09 确认三件依赖后实施）
- 语义（对照 Baritone `MovementDownward`，`Moves.DOWNWARD(0,-1,0)`）：**破坏脚下的方块后垂直掉 1 格**。
  站在方块上时同列不可能凭空下落，所以该 Movement **必然涉及"挖脚下"**——这是"禁止挖脚下"开关的第一个落点。
- 实现：
  - `MovementType.DOWNWARD` + `MovementSpec` 几何（dx=dz=0, dy=−1）+ `CostModel.DOWNWARD_COST = 1.67`
    （= DESCEND 成本扣除水平 1 格后的竖向分量；待客户端实测按 D-040 方式修正）；
  - `SurfaceMovementProvider.appendDownward`：落点可站 + 脚下可破坏 + **逃生路线守卫** + 成本含破坏 tick；
  - `DownwardExecution`（破坏期间原地保持 → 破后等待自然掉落 → 统一完成契约 + D-027 容差）、
    `DownwardExecutionFactory`（几何/起点/落点/可破坏/逃生五重校验）；
  - `MovementCapabilities.pathAccess(...)`（PATH_ACCESS：改变世界、需工具、破坏不可逆），
    同时修正了 BREAK_AND_TRAVERSE 此前误用 `pureTraversal` 的能力声明；
  - `PathRequest.withWorldModification` 加入 DOWNWARD（保守：仅在允许世界修改的任务里启用）。
- **G 语义守卫**（用户确认的保守版）：落点四邻中必须存在一格，其上一层（原层脚位）可通行且可站
  （即能从落点用一个 ASCEND 爬回去）；否则拒绝——避免掉进 1×1 竖井出不来。
- 验收夹具（零参数）：`alice_test:vertical_course` + `alice:pathing_downward` →
  `[Vertical] SUMMARY downward_execute=PASS downward_guard=PASS`
  （正例 `(0,64,45)→(0,63,45)` 破坏脚下掉 1 格；反例 `(2,64,45)` 是封死竖井，规划应被拒）。
- 未做：PILLAR（与 DOWNWARD 成对，按用户裁决暂不采纳）；FALL（>1 格落差，属 H 模式，D-046 预留）。

## D-049：运动轨迹记录（顿挫对比准备）

- 状态：已实施（用户 2026-09-09 要求"同时准备解决顿挫感，可做 Baritone 对比测试"）
- `BotTrace`：由 `BotSession.tick()` 每 tick 采样一次，输出格式与测试数据包
  `alice_test:trace_tick` **完全一致**：`[TRACE] <bot> t=.. x=.. y=.. z=.. vx=.. vy=.. vz=.. og=.. yaw=..`
  （位置/速度=毫格，偏航=百分度）。
- 开关：`/alice trace`（再次执行关闭，零参数）。
- 对照用法：
  1. Alice 侧：`/alice trace` → `/function alice_test:pathing_course` → 右键 `alice:pathing_session` → `/alice trace`；
  2. Baritone 侧：`/function alice_test:contrast_trace_pathing` → `#goto 0 62 44`（自动记录并在到达时停表）；
  3. 对比指标：水平速度 `h=√(vx²+vz²)/1000` 是否在每个方块边界降到 ≈0（顿挫来源）。

## D-050：Movement 先做 Baritone 原样语义，安全守卫后置

- 状态：当前生效（用户 2026-09-09 决策）
- 决策：**先完整实现与 Baritone 相同模式的 Movement（原样语义）**；安全模式（守卫版）作为
  **后续补充**——可以是独立的安全 Movement，也可以由任务层的风险决策选择（配合 D-046 的 H/G/S）。
- 理由：DOWNWARD 连续两次验收失败暴露"边做功能边加守卫"会把功能本身拖住；先把 Baritone 的行为做对，
  安全边界在任务层/独立变体里表达，职责更清晰、可测性更好。
- 本次落地（DOWNWARD）：
  1. **去掉逃生路线守卫**（`SurfaceMovementProvider` / `DownwardExecutionFactory` / `DownwardExecution`），
     与 Baritone `MovementDownward`（`allowDownward=true` + 落点可站 + 脚下可破坏）对齐；
  2. **修复两处类型感知检查**（真正导致失败的 bug）：
     - `PathSession.currentTargetStillValid`：DOWNWARD 的目标方块**由本段破坏产生**，不能要求它"现在就可行走"
       （旧检查在破坏进度 71% 时误判 `SEGMENT_TARGET_CHANGED` 并取消，触发 `BREAK_ABORTED`）；
     - `PathSession.futureTargetBlocked`：同理，前瞻不能把未来的 DOWNWARD 段目标当成"被封死"。
  3. 夹具期望更新：`downward_execute=PASS` + `shaft_plan=REACHED`（1×1 竖井在 Baritone 原样语义下可规划；
     逃生守卫属后续安全模式）。
- 后续：按同一原则补齐 PILLAR（垂直上升）与 FALL（>1 格落差）等 Baritone 原样 Movement；
  安全变体与任务层风险决策在功能齐备后统一设计（D-046 预留）。

## D-051：连续行动对照实测（顿挫量化）与 DOWNWARD 成本标定

- 状态：已实测入库（2026-09-09，Alice 侧 + Baritone 1.10.5 同场景同目标）
- 场景：`alice_test:trace_course`（11 格纯平地直线，跨 10 个方块边界），
  Alice 侧 `/alice trace` + `alice:pathing_straight`；Baritone 侧 `contrast_trace_straight` + `#goto 0 64 51`。

| 指标 | Alice | Baritone |
|---|---|---|
| 峰值水平速度 | 0.207 格/tick（走路） | **0.281（疾跑）** |
| 加速到满速 | 4~5 tick | ~7 tick |
| 方块边界停顿 | **10 次/11 格（每格 1 tick 归零）** | **0 次** |
| 速度曲线 | 锯齿 `0.21→0.11→0.06→0.03→0.00` | 直线 `0.28` 恒定 |
| 全程 | 96 tick | ~40 tick |

- 结论：**顿挫的来源被精确定位为"每段结束的 settle + stopMovement"**（D-036 登记的体验项），
  与 Baritone "SUCCESS 后同 tick `pathPosition++` 并立即驱动下一段"（`PathExecutor:231-236`）的差异一致。
  改进方向：**条件 settle**（仅在需要精确落点的段后停：DESCEND / PLACE_STEP / 类型切换 / 空中），
  共线同类型段保持输入连续推进。另需注意 Baritone 疾跑（Alice 目前不疾跑，速度差 1.35×）。
- DOWNWARD 成本标定：`(0,64,45)→(0,63,45)` 整段 **11 tick**（破坏 5.6 + 下落稳定 5.4）
  → `DOWNWARD_COST` 由 1.67 改为 **0.9**；启发式竖向下降速率取 `min(DESCEND−TRAVERSE, DOWNWARD_COST)`
  以保持对全部下降 Movement 可采纳。
- 工具：`tools/analyze-trace.py`（位置差分口径；玩家实体 NBT 的 `Motion` 恒为 0，不能用）。

## D-052：条件 settle + 段间连续推进（顿挫修复）

- 状态：已实施，待客户端验证（用户 2026-09-09 指定，依据 D-051 的实测对照）
- 依据：D-051 实测——Alice 在 11 格直线中每格停顿 1 tick（速度归零），Baritone 全程 0.28 格/tick 无停顿；
  Baritone 的做法是 `PathExecutor:231-236`：Movement SUCCESS 后**同一 tick** `pathPosition++` 并立即驱动下一段。
- 实现（`PathSession`）：
  1. 段成功后的 `settleTicks` 改为**条件值**：`needsSettle(index)` 为真才进入 settle；
  2. `needsSettle` = ① bot 在空中（等落地）；② 下一段需要精确落点（DESCEND / DOWNWARD /
     PLACE_STEP_AND_TRAVERSE / BREAK_AND_TRAVERSE）；
  3. 否则**同一 tick 连续推进**：`startSegment()` 后立即 `execution.tick()` 一次（补上执行器成功时
     清掉的输入），并记录 `[R4 Session] continuous_advance`；
  4. 段成功时若已到末尾，直接置 COMPLETED（不再多等一轮）。
- 保留：需要精确落点的段仍走原 settle（`stopMovement` + 等落地减速），保证 D-024/D-027 的落点精度。
- 验收：`trace_course` 直线跑的速度曲线**不再每格归零**，且总 tick 明显下降；
  回归（battery 8/8、regression 7/7、vertical、placer/breaker/disturber/waller）不得退化。

## D-053：疾跑门控（对照 Baritone shouldSprintNextTick，忽略饱食度）

- 状态：已实施，待客户端验证（用户 2026-09-09："加入同样的疾跑方案，不需要考虑饱食度成本"）
- 依据：D-051 实测——Alice 直线跑 0.216 格/tick（走路），Baritone 0.281（疾跑），差距 1.3×。
- 实现（`PathSession.shouldSprint()`，每 tick 应用）：
  1. 只在**平地 TRAVERSE / DIAGONAL** 段疾跑；
  2. bot 必须在地面、不在水中、未潜行；
  3. **前方 2 段内出现 DESCEND / DOWNWARD / ASCEND / PLACE_STEP / BREAK 时提前收力**
     （对照 Baritone `MovementDescend.safeMode`：疾跑冲下台阶会过冲）；
  4. **忽略饱食度**（用户裁定影响可忽略；Baritone 的 `foodLevel > 6` 条件不移植）。
- 未移植：Baritone 的 traverse→ascend 跳过、descend→ascend 同向跳过、fall 覆写、frost walker 分支
  （依赖 FALL/PARKOUR，等那些 Movement 补齐后再评估）。

## D-054：串联回归覆盖全部必要复测项

- 状态：已实施（用户 2026-09-09："重新整理必要复测场景，适当串联"）
- `alice:pathing_regression` 现在一次右键跑 **11 项**：
  `pathing_course` / `place_course` / `break_course` / `vertical_course`（DOWNWARD）/ `trace_course`（11 格直线跑）
  + `fluid_course` / `lava_course` / `fence_course`（拒绝行为）+ `dip_course`（路线偏好）
  + `place_course+wall`（世界变化 → 任务层重规划，断言 `replans ≥ 1`）
  + `place_course+disturb`（位置漂移 → 重同步/重规划）。
- 夹具内联：封路与漂移逻辑从 `PathSessionDiagnosticTask` 复制到回归任务（按场景参数化），
  单场景物品（`pathing_waller` / `pathing_disturber` 等）保留用于定向排查。


## D-055：PILLAR（垂直上升 1 格）对齐 Baritone `MovementPillar`

- 状态：**已验收**（2026-09-09 客户端：`[Pillar] SUMMARY pillar_plan=PASS resource_guard=PASS pillar_execute=PASS`，两次 `[Pillar] placed ... feetY=65.166/66.166` = Baritone 的"脚高于目标格顶面才放"门槛生效）
- 对照来源：`reference/baritone-1.20.1` @8c55ad0
  `src/main/java/baritone/pathing/movement/movements/MovementPillar.java`（成本 `:62-124`，执行 `:126-231`）。
- Baritone 语义（逐条落地）：
  1. 几何：`src → dest = src.above()`，合法位置集 `{src, dest}`，待净空格 = `src.above(2)`（头顶）；
  2. 成本 = `JUMP_ONE_BLOCK_COST + placeCost + jumpPenalty + hardness`；Alice 用实测 `ASCEND_COST`（已含起跳+落地）
     + `PLACE_ONE_BLOCK_COST`（= 20 tick / 6）→ `PILLAR_COST = 5.00`；
  3. 执行顺序：**水平居中到 src 列（阈值 0.17）→ 起跳 → 脚部 y > dest.y + 0.1 后才在脚下放置方块 → 自然落回**；
     这个 0.1 门槛不是随便取的：脚必须升到待放置格顶面之上，否则原版 `Level.isUnobstructed`
     会因自身碰撞箱与方块相交而拒绝放置（`MovementPillar:227`）。
- Alice 实现：
  - `MovementType.PILLAR` + `MovementSpec` 几何校验（dx=dz=0、dy=+1）+ `CostModel.PILLAR_COST`；
  - `SurfaceMovementProvider.appendPillar`（目标格/头顶格净空、放置格可替换、快捷栏有一次性方块、有放置面）；
  - `PillarExecution` + `PillarExecutionFactory`（阶段机：居中 → 起跳 → 升空放置 → 落回稳定；
    段内 100 tick 上限、放置失败码 `PILLAR_PLACE_FAILED`、无方块 `PLACE_RESOURCE_UNAVAILABLE`）；
  - `PathSession`：PILLAR 计入精确落点类型（`needsSettle` / `isPrecisionType`）、
    `currentTargetStillValid` 用"放置位可通行 / 支撑已就位 / 有方块"判定、`futureTargetBlocked` 跳过支撑检查；
  - `PathRequest.withWorldModification` 加入 PILLAR（TEMPORARY_SUPPORT 能力，LOCAL_STEP 可回收级别）。
- 偏离登记（3 条，均为"更保守"或等价）：
  1. **不做"破头顶方块"分支**：Baritone 允许 `src.above(2)` 可破坏时先挖掉再上；Alice 最小闭环要求该格净空
     （少一条候选，不会产生不安全行为）。需要时再按 `MovementAscend` 的破头分支单独登记实施；
  2. **放置走服务端 `BlockInteraction.placeAt`**（服务端构造 `BlockHitResult`，等价于客户端上报命中包），
     因此不需要 Baritone 的潜行姿态与视线射线；
  3. **规划期放置面按"当前世界"求值**（`hasPlacementFace`）：连续 PILLAR 时第 2/3 段的"脚下已放置方块"
     在规划期尚不存在，靠侧壁提供放置面；1×1 竖井正好有基岩侧壁。这与 `PLACE_STEP_AND_TRAVERSE` 的既有行为一致，
     若未来需要"纯靠脚下已放置方块"的连续上升，需要引入投影世界（写入 `docs/ALIGNMENT_OPEN_QUESTIONS.md`）。
- 验收（一次右键）：`alice_test:pillar_course` + `alice:pathing_pillar`（3 格深 1×1 基岩竖井）
  期望 `pillar_plan=PASS`（首步 PILLAR、PILLAR 数 = 3）+ `resource_guard=PASS`（清空快捷栏方块后不可规划）
  + `pillar_execute=PASS`；串联回归新增 `pillar_course`（第 12 项）。
- Baritone 对照：`alice_test:contrast_pillar` / `contrast_trace_pillar`（同一场景、同一目标 `#goto 25 67 44`）。

## D-056：COLUMN 完成判定补 `onGround`（空中假成功，根因修复）

- 状态：**已验收**（2026-09-09 客户端：修复后 12 项回归除 `lava_course` 外全 PASS，PILLAR 两次真实放置）
- 证据（Windows `latest.log` 11:39，`alice:pathing_pillar` + `pillar_course`）：
  - `segment_done index=0 type=PILLAR ticks=4 actualFoot=24, 65, 44`，**没有任何 `[Pillar] placed` 日志**；
  - 紧接着 `failed status=STALE code=PILLAR_STALE_START index=1 actualFoot=24, 64, 44`（bot 又掉回原脚位）；
  - 重试 3 次全部同样 → `pillar_execute=FAIL`。
- 根因：`CompletionTolerance.COLUMN` 的判定是 `blockPosition().equals(to) && getY()-to.getY() < 0.5`，
  **缺 `onGround`**。bot 起跳后脚到 y≈65.2 时 `blockPosition()` 已等于目标格 → 执行器在空中提前 SUCCEEDED，
  放置分支从未执行；随后 bot 落回起点，下一段起点校验失败（STALE）。
  这违反 D-026 已登记的契约："onGround 是必须项：否则残余动量会在判定成功之后继续把 bot 带离目标列"。
- 影响面：同一缺陷潜伏在**全部 7 个执行器**的 COLUMN 分支（PILLAR/ASCEND/DESCEND/DOWNWARD/
  BREAK_AND_TRAVERSE/PLACE_STEP_AND_TRAVERSE/DIAGONAL/TRAVERSE）；ASCEND 历史上"偶发上层水平偏差
  致 settling 超时"很可能同源。
- 修复（不打 PILLAR 特判，按根因统一）：`MovementHelper.isAtFootColumn(entity, footPos)`
  = `onGround && blockPosition().equals(footPos) && getY()-footPos.getY() < 0.5`，
  7 个执行器的 COLUMN 分支全部改用该判定；EXACT 分支（`isSettledAtFootPos`）本来就带 onGround，不变。
- 附带修正：`PillarDiagnosticTask` 的规划断言从"PILLAR 数量 = 3"改为"≥ 2"——
  实测规划器选 **2×PILLAR + 1×ASCEND（cost 11.68）**，比 3×PILLAR+TRAVERSE（16）更便宜，断言写死了错误值。

## D-057：lava_course 断言改为"路线不接触岩浆"（新 Movement 打开了合法安全路线）

- 状态：**已验收**（2026-09-09 客户端：`[LavaGuard] status=REACHED first=DIAGONAL movements=12 lava_contacts=0 result=PASS`；串联回归 12/12 PASS）
- 事实（D-056 修复后的 12 项回归）：`pathing/place/break/vertical/pillar/trace/fluid/fence/dip/+wall/+disturb` 全 PASS，
  仅 `lava_course=FAIL detail=REACHED`。
- 根因分析（**不是内核缺陷**）：该场景原断言是"无路可走 → UNREACHABLE"。封口 3 格高（y=63..65，顶面 y=66）
  在 D-042 时能封死，是因为当时只能 `PLACE_STEP + ASCEND` 上一格；PILLAR 补全后规划器给出**合法且安全**的路线：
  平台 A 抬高一格 → 在岩浆上方 y=64 用 PLACE_STEP 搭桥（站在自建方块上）→ DESCEND 落到平台 B；
  全程 bot 身体格与支撑格都不含岩浆。`fluid_course` 仍 `UNREACHABLE`（充水墙顶面不可站），
  说明"流体不可挖/不可站"的拒绝语义没有退化。
- 实现：
  1. `PlanRouteSafety.occupiedCells/lavaContacts`：按每段 Movement 枚举 **bot 身体占据 / 站立其上**的格子
     （起点与终点身体+头、终点支撑/放置位，DIAGONAL 两个角格，BREAK_AND_TRAVERSE 中间格），检查其中是否含岩浆；
     **不看"在岩浆上方放置的方块"**——站在自建方块上、方块下方才是岩浆属于安全行为（Baritone 同样搭桥跨越）。
  2. `PathingRegressionTask` 新增 `Kind.PLAN_SAFE_ROUTE`（允许 REACHED 或 UNREACHABLE，只断言 `lava_contacts=0`），
     `lava_course` 改用它；同时所有规划类检查的 detail 补上 `first=` / `movements=`，拒绝类失败可自解释。
  3. 新物品 `alice:pathing_lava_guard`（`PathingLavaGuardItem`）：一键传送到场景起点 → 规划 → 输出
     `[LavaGuard] status=... first=... movements=... lava_contacts=0 result=PASS`；
     `lava_course.mcfunction` 改为发放该物品（`pathing_fluid_guard` 仍用于 `fluid_course` 的 UNREACHABLE 断言）。
- 保留决定（用户）：其余 11 项回归场景全部保留（每项对应一个 Movement 或一类行为）。
- 验收：`/function alice_test:lava_course` + 右键 `alice:pathing_lava_guard` → `lava_contacts=0 result=PASS`；
  串联回归期望 `lava_course=PASS detail=REACHED/first=.../movements=.../lava_contacts=0`。

## D-058：FALL（落差 2~3 格）—— Baritone 原样 + Alice 落点可回收守卫

- 状态：**已验收**（2026-09-09 客户端：`[Fall] SUMMARY fall_plan_2=PASS fall_plan_3=PASS no_deep_fall=PASS fall_recover_guard=PASS fall_execute=PASS`；回归 13/13 PASS，其中 `place_course` 的末尾落差改由 FALL 完成（14 tick））
- 用户决策（2026-09-09）：
  1. **只做"无水落地 ≤3 格"**：不移植 Baritone 的落水 / 水桶救落分支（最小闭环）；
  2. **只在允许世界修改的任务里启用**（`PathRequest.withWorldModification`）——生产任务的普通通行仍保持一格红线；
  3. **落点必须能用 PILLAR 返回**（D-024 补记 Q1-D 的可回收守卫落地）。
- 对照来源：`reference/baritone-1.20.1` @8c55ad0
  - 生成：`Moves.java:152-215`（`DESCEND_*` 在落点比 src 低超过 1 格时改生成 `MovementFall`）；
  - 落点扫描：`MovementDescend.dynamicFallCost:145-224`（无水落地分支 `unprotectedFallHeight <= maxFallHeightNoWater + 1 = 4`，
    底部半砖拒绝 `:203-205`，流体/危险方块由 `canWalkOn/canWalkThrough` 排除）；
  - 执行：`MovementFall.updateState:79-190`（朝落点中心推进、空中偏离 >0.1 才给前进输入、落地判定 `y-dest.y<0.094`）；
  - 合法位置集：`MovementFall.calculateValidPositions`（src + **整条下落列**）；
  - 离轨特例：`PathExecutor:307-311`（下落途中改用**水平距离**判定是否离轨）。
- 成本（Baritone 结构 + Alice 实测标定，D-040 原则）：
  `FALL = WALK_OFF(0.8 格) + FALL_N_BLOCKS_TICKS/6 + CENTER(0.2 格) + 段固定开销`，
  其中逐格下落 tick 取 Baritone `ActionCosts.FALL_N_BLOCKS_COST` 同款速度曲线 `(0.98^t-1)*-3.92`
  （1 格 4.61 / 2 格 6.79 / 3 格 8.46 tick，下落物理与假人一致）；
  段固定开销由 Alice 实测 `DESCEND 16 tick` 反推 ≈ 0.90 走路格。
  → `FALL_TWO_BLOCK_COST = 3.03`、`FALL_THREE_BLOCK_COST = 3.31`（**待客户端 `segment_done ticks=` 实测校正**）。
- 实现清单：
  - `MovementType.FALL` + `MovementSpec` 几何（卡基数 1 格、dy ∈ {-2,-3}）；
  - `CostModel`：`FALL_ONE/TWO/THREE_BLOCKS_TICKS`、`WALK_OFF_EDGE_COST`、`CENTER_AFTER_FALL_COST`、
    `FALL_SEGMENT_OVERHEAD_COST`、`FALL_TWO/THREE_BLOCK_COST`；
  - `SurfaceMovementProvider.appendFall`：走离边缘可通行 + 下落列净空 + 落点可站/非流体/非底部半砖 +
    **PILLAR 返回守卫**（返回列净空到起点层 + 有放置面 + 一次性方块数量 ≥ 落差）；
  - `FallExecution`（地面阶段走离边缘 → 空中朝落点列推进（0.1）→ 落地后继续收敛（0.3）→
    `isAtFootColumn` 完成判定）+ `FallExecutionFactory`（几何/起点/边缘/下落列/落点/流体/半砖/守卫全量校验）；
  - `PathSession`：FALL 计入精确落点类型；`validPositions` 覆盖**整条下落列**；
    疾跑前瞻加入 FALL（Baritone `shouldSprintNextTick` 的 fall 覆写）；空中不判定漂移（离轨特例的 Alice 等价物）。
- 偏离登记：
  1. **不移植落水/水桶**（用户决策；Baritone `MovementFall:100-140` 的落水与 `maxFallHeightBucket` 分支）；
  2. **新增 Baritone 没有的落点守卫**（PILLAR 返回）——Baritone 的 `MovementFall` 不检查可回收性；
     这是 Alice 的 D-024 局部可回收性不变式要求，已登记为差异；
  3. **离轨特例实现方式不同**：Baritone 在 `PathExecutor` 里对 `MovementFall` 改用水平距离；
     Alice 由"空中一律不判定漂移 + validPositions 含整条下落列"等价覆盖。
- D-024 更新：红线从"任何 Movement 落差 ≤1 格"改为
  **"默认 ≤1 格；FALL 允许 2~3 格，但必须通过 PILLAR 返回守卫，且只在允许世界修改的任务中启用"**。
- 验收（零参数）：`alice_test:fall_course` + `alice:pathing_fall` →
  `[Fall] SUMMARY fall_plan_2=PASS fall_plan_3=PASS drop4_guard=PASS recover_guard=PASS fall_execute=PASS`；
  串联回归新增 `fall_course`（第 13 项）；Baritone 对照 `contrast_fall` / `contrast_trace_fall`。

## D-059：过冲红线默认关闭 + 风险开关接口（用户裁定）

- 状态：**已验收**（2026-09-09 客户端：回归 13/13 PASS，含 `fall_course`；`[Fall]` 五项全 PASS）
- 用户裁定：
  1. **D-024 的 DESCEND 过冲红线改为默认关闭（对齐 Baritone 原样），低风险模式再打开**；
     "甚至后续讨论可能删除这个红线机制"；
  2. 风险画像的最小接口用**一组开关**表达，**不用 H/G/S 三态等级枚举**
     （用户："等级枚举后续设计怎么处理我不理解"）；
  3. FALL 的两个守卫检查改为**断言路线属性**（不是"无路可走"）。
  4. 用户声明：**整套风险评估方案与顺序绝不是最终定案**，先按 Baritone 原样把框架搭好、能用。
- 实现：
  - 新增 `pathing/risk/RiskSwitches`（当前只有 `descend_overshoot` 一个开关，默认 `false`）；
    `DescendExecutionFactory.validate` 与 `SurfaceMovementProvider.overshootColumnSafe`
    在该开关关闭时直接放行（= Baritone 原样：不检查过冲列）；
  - 新增命令 `/alice risk`（查看）与 `/alice risk descend_overshoot on|off`（切换），
    切换时输出 `[Risk] switch ... all=...` 便于日志取证；
  - `FallDiagnosticTask` 的两项检查重写：
    - `no_deep_fall`：4 格落差场景断言"不生成 4 格 FALL 候选 **且** 路线中无单段 ≥4 格下落"，
      **允许**规划器用 PLACE_STEP 搭楼梯到达（2026-09-09 实证 `first=TRAVERSE movements=5`）；
    - `fall_recover_guard`：断言"不可回收落点不生成 FALL 候选 **且** 同场景可回收落点仍生成 FALL 候选"
      （证明守卫是选择性的，不是一律禁用）。
- 现状说明（待用户确认）：FALL 的 PILLAR 返回守卫与 ASCEND 的两条前置**目前仍默认开启**；
  按"S 默认 = Baritone 原样"原则它们将来也应默认关闭，等用户确认后再翻。
- 关联：`docs/RISK_MODES_DISCUSSION.md` 附录 A（用户 2026-09-09 的分层评估框架与设计点）。

## D-060：WalkTo 迁移到新内核（D-045 迁移顺序第一项）

- 状态：已实施，待客户端验证（用户 2026-09-09："任务迁移吧"）
- 依据：D-045 方案 B 的迁移顺序 `WalkTo → Follow → Place/Transfer → Mine`。
- 改动：
  1. `task/WalkToTask` 重写为新内核：`CorePathPlanner`（真 A*，D-040）+ `PathRetryRunner`（D-043 任务层重规划）
     + `PathSession`（D-026/D-027 契约、D-047 重同步、D-052 连续推进）；
     请求用 **`PathRequest.of`**（TRAVERSE/DIAGONAL/ASCEND/DESCEND），保持"不挖掘、不放置"语义；
  2. **失败码保持 legacy 语义**（调用方/日志兼容）：`walk_target_not_safe` / `walk_no_path` /
     `walk_search_limit` / `walk_blocked` / `walk_timeout` / `walk_stale` / `walk_invalid_precondition` /
     `walk_execution_failed`；其中 `PLAN_SEARCH_LIMIT` 必须映射为 `walk_search_limit`，
     **不得与 `walk_no_path` 混淆**（架构边界：SEARCH_LIMIT ≠ UNREACHABLE）；
  3. `BotManager.assignWalkTo` **解除 legacy 门禁**并改为返回 boolean；
     `LEGACY_PATHING_TASKS_ENABLED` 仍为 false（只放开 WalkTo 一项）；
  4. 新场景 `alice_test:walk_course` + 物品 `alice:walk_to_runner` + `WalkToDiagnosticTask`：
     `walk_flat`（同层绕行）/ `walk_over_wall`（ASCEND+DESCEND 跨 1 格墙）/
     `walk_unreachable`（孤立柱顶 → `walk_no_path`）/ `walk_unsafe`（无支撑目标 → `walk_target_not_safe`）。
- 验收（零参数）：`/function alice_test:walk_course` + 右键 `alice:walk_to_runner` →
  `[WalkTo] SUMMARY walk_flat=PASS walk_over_wall=PASS walk_unreachable=PASS walk_unsafe=PASS`。
- 后续：`FollowTask` → `PlaceTask`/`TransferTask` → `MineTask`（HARD_PATH，最后）。

## D-061：回归覆盖断言（Movement 变多后防止"测试失效但全绿"）

- 状态：已实施，待客户端验证（用户 2026-09-09："好，加上加覆盖断言"）
- 背景（用户观察 + 日志实证）：Movement 补齐后回归场景的**实际执行路线漂移**了——
  13 项仍全绿，但"过程"已不同。实测各场景执行序列：
  `pathing_course=DESCEND×2`、`place_course=TRAVERSE,PLACE_STEP×2,TRAVERSE×3,FALL,TRAVERSE`（尾部**由 DESCEND×2 变成 FALL**）、
  `pillar_course=PILLAR×2,ASCEND`、`fall_course=FALL`、`vertical_course=DOWNWARD`、
  `break_course=…BREAK_AND_TRAVERSE…`、`trace_course=TRAVERSE×11`、`place_course+wall/disturb` 含 ASCEND/DIAGONAL/PLACE/FALL。
  → 覆盖目前完整，但**每个类型大多只靠一个场景**，且路线漂移不会报警。
- 实现：
  1. 只读遥测：`PathSession.executedMovementTypes()`（段成功时追加）、
     `PathRetryRunner.executedTypes()`（跨重规划累加）；
  2. `PathingRegressionTask.SceneCheck.required`：每个执行场景声明**必须实际执行到**的 Movement
     （pathing→DESCEND、place→PLACE_STEP、break→BREAK、vertical→DOWNWARD、pillar→PILLAR、
     fall→FALL、trace→TRAVERSE、place+wall/disturb→PLACE_STEP）；缺类型 → 该场景 **FAIL**，
     detail 带 `/route=` 与 `/MISSING=`；
  3. **全局覆盖断言** `REQUIRED_COVERAGE`：9 种可执行 Movement（TRAVERSE/DIAGONAL/ASCEND/DESCEND/
     DOWNWARD/PILLAR/FALL/BREAK_AND_TRAVERSE/PLACE_STEP_AND_TRAVERSE）必须在本次回归中至少执行一次；
     `SUMMARY` 末尾输出 `coverage=PASS|FAIL(...)` 与 `executed=<实际执行过的类型>`。
- 验收：`[Regression] SUMMARY ... coverage=PASS executed=TRAVERSE,DIAGONAL,ASCEND,DESCEND,DOWNWARD,PILLAR,FALL,BREAK_AND_TRAVERSE,PLACE_STEP_AND_TRAVERSE`；
  任何一个类型缺失或某场景不再执行其目标 Movement，都会直接变红。
