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

