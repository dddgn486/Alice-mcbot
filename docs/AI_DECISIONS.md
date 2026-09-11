# Alice 当前架构决策

> 简短、稳定、可被显式替换。修 bug 时不能悄悄推翻这些决策。

## D-001：LLM 只做目标级决策

- 状态：稳定
- LLM 选择目标、策略和已注册工具；不逐 tick 控制物理，不直接写库存或世界。

## D-002：服务端权威

- 状态：稳定
- 世界、任务、bot、库存、权限和完成条件以服务端事实为准；客户端只负责显示、输入和观察。

## D-003：普通挖矿和拾取使用 HARD_PATH

- 状态：稳定（**表述已由 D-076 更新为现行术语，语义不变**）
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

- 状态：待重新定义（**表述已由 D-076 更新为现行术语，语义不变**）
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

- 状态：**已验收**（2026-09-09 客户端：`[WalkTo] SUMMARY walk_flat=PASS walk_over_wall=PASS walk_unreachable=PASS walk_unsafe=PASS`，两次运行一致）
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

## D-062：Follow 迁移到新内核 + 目标锚定缺陷修复

- 状态：**已验收**（2026-09-09 客户端：跟随正常；跳跃期间仅 `[Follow] target_airborne hold_goal=...`、无 `follow_*` 失败；站位目标出现墙顶 `23,65,8x` 说明跟随会爬墙；玩家退出后自然终止于 `follow_target_unavailable`）
- 依据：D-045 迁移顺序第二项 `FollowTask`。
- **用户报告的缺陷（本次修复的核心）**：玩家**原地跳一下**，bot 就可能认为玩家在空中/不可达。
  机制：legacy（以及迁移初版）用 `target.blockPosition()` 直接当规划目标；玩家起跳后脚位立刻变成
  **空中格（脚下无支撑）**，`canWalkOn` 为假 → `follow_target_not_on_safe_surface`，
  或规划目标不可站 → `follow_no_path`。
- 修复（对照 Baritone `FollowProcess.java:73` → `new GoalNear(pos, followRadius)`，即"到目标 N 格内"）：
  1. **目标锚定**：只有目标**落地且脚位可站**时才更新锚点 `lastSafeTargetFoot`；目标在空中时保持旧锚点
     （并打一条 `[Follow] target_airborne hold_goal=...`），起跳不再影响目标解析；
  2. **站位目标**：规划目标取锚点**相邻的可站格**（同层优先，最多向下 3 层，取离 bot 最近的一个），
     即"站到目标旁边"，语义等价 Baritone 的 `GoalNear`，但保留 Alice 精确脚位目标的可采纳启发式（D-040）；
  3. 已经站在站位格上 → 待命（避免每 tick 重复规划）。
- 迁移改动：
  - 规划/执行：legacy `SurfacePathfinder` + `BasicMovement` 直驱 → `CorePathPlanner` + `PathRetryRunner` + `PathSession`；
    请求 `PathRequest.of`（纯通行）；
  - 重规划触发：无路径 / 目标脚位漂移 ≥2 格 / 会话失败；**取消 legacy 的"每 10 tick 无条件重规划"**
    （世界变化已由会话健康检查 D-047 覆盖，无条件重规划会打断 D-052 连续推进）；
  - 失败码保留 legacy 语义：`follow_target_unavailable` / `follow_distance_limit` /
    `follow_target_not_on_safe_surface` / `follow_no_path` / `follow_search_limit` /
    `follow_blocked` / `follow_timeout` / `follow_stale` / `follow_invalid_precondition` /
    `follow_execution_failed`；取消 `follow_unsettled`（新内核完成契约已覆盖）；
  - `BotManager.assignFollow` 解除 legacy 门禁并返回 boolean；`stopFollow` 先 `cancel()` 再收尾并输出
    `[Follow] SUMMARY stopped ticks=.. replans=.. minDist=..`（顺带修复"任务被丢弃后输入未清理"的隐患）。
- 验收（零参数）：`/function alice_test:follow_course` → 右键 `alice:follow_runner` 启动 →
  原地跳几下 → 走过墙东侧 → 再点一次结束。
  期望：无 `follow_*` 失败；日志有 `[Follow] replan reason=initial|goal_moved`、
  跳跃期间 `[Follow] target_airborne hold_goal=...`、结束时 `[Follow] SUMMARY stopped ...`。

## D-063：Place 迁移到新内核（放置走统一路径）

- 状态：**已验收**（2026-09-09 客户端：4 次 `PlaceTask` 全 `COMPLETED`，`walk_to_stand` → `completed`，目标格实际放置，11–32 tick）
- 依据：D-045 迁移顺序 `PlaceTask`。
- 改动：
  1. 站位寻路：legacy `AStarPathfinder` + `PathExecutor` → `CorePathPlanner` + `PathRetryRunner`
     + `PathSession`（`PathRequest.of` 纯通行）；候选站位仍是"目标 4×4×4 邻域内可站、眼距 ≤4.2 的脚位，
     按到目标距离排序取前 16，逐个用新内核试规划"；
  2. **放置动作改为统一路径** `BlockInteraction.placeAt`：legacy 直接 `level.setBlock(...)`（无视库存、
     支撑面与服务端校验）→ 现在需要快捷栏一次性方块 + 支撑面 + `InteractionResult` 校验 + 服务端世界复核
     （与 `PLACE_STEP_AND_TRAVERSE` / `PILLAR` 一致，D-037/D-043 语义）；
  3. 取消 legacy 的手工视线检查（`place_line_of_sight`）：放置可达性与支撑面由 `placeAt` 统一判定；
  4. 失败码：保留 `place_no_stand` / `place_no_path` / `place_path_failed`，新增
     `place_resource_unavailable` / `place_no_valid_face`，新内核终态映射
     `place_blocked` / `place_timeout` / `place_stale` / `place_invalid_precondition` / `place_search_limit`；
  5. `BotManager.assignPlace` 解除 legacy 门禁并返回 boolean；
     `TargetSelector`（Shift+右键方块）作为零参数测试入口，并加夹具保证 bot 快捷栏有圆石。
- 验收：`/function alice_test:place_target_course` → Shift+右键方块侧面 →
  期望 `[PlaceTask] walk_to_stand target=... stand=...` → `[PlaceTask] completed target=... stand=... feet=...`，
  且目标格实际出现方块。

## D-064：Mine 迁移（批次 1：BotMiner 去寻路）

- 状态：已实施，待客户端验证（用户 2026-09-09：直接做 Mine、先出设计；设计见 `docs/MINE_MIGRATION_DESIGN.md`）
- 用户裁定：① 收集任务抽取为**公用子任务**并**按需授予世界修改权限**（终点=掉落物位置，怎么过去交给寻路系统）；
  ② 收集 best-effort DONE + 摘要、不挖台阶、删 `pickupDelay` 反射；③ Baritone `blacklist`/`coalesce` 留后；
  ④ 验收入口沿用 `alice:target_selector` 右键 + `alice_test:scene_a`。
- 批次 1 改动（寻路换内核，动作层不变）：
  1. `MiningPlan.path`：legacy `SurfacePathfinder.Result` → 新内核 `PathPlan`；
     `MiningPlanner` 改用 `CorePathPlanner` + `PathRequest.of`（HARD_PATH 纯通行）；
  2. `BotMiner`：删除 `SurfacePathfinder` / `PathExecutor` / `MovementPlan` / `MovementPathExecutor` 依赖与
     M2 专用构造器与 `tickMovementPlan`；站位移动统一走 `PathRetryRunner`（任务层重试）；
     `chooseReachableStand` 用新内核试规划；`StandChoice` 只保留路径段数（不再持有路径列表）；
     `abortMining` 同时取消 runner；失败码保留 legacy 语义（`no_path` / `stand_search_limit` /
     `path_failed` / 新增 `path_blocked` / `path_timeout` / `path_stale` / `path_invalid_precondition`）；
  3. `MineTask` 适配新 `PathPlan`（`PlanningStatus.SEARCH_LIMIT` / `UNREACHABLE`、`movements().size()`）；
  4. `BotManager.assignMine` 解除 legacy 门禁并返回 boolean。
- **D-045 门禁漏洞（本次发现并记录）**：`BotSession.assign(TaskTarget.BLOCK)` **直接创建 `MineTask`**，
  绕过 `assignMine` 的 legacy 门禁——所以 `scene_a` / `target_selector` 右键一直是可用的（走 legacy 内核）；
  门禁只覆盖了 `assignMine/assignFollow/assignWalkTo/assignPlace/assignTransfer` 这些直接 API。
  结论：迁移期间的"legacy 已禁用"对 BLOCK 目标不成立，后续迁移任务时需以**实际调用链**为准。
- 未做（批次 2/3）：`CollectDropsTask` 重写并接入；清障/重规划/失败码对齐 + `mine_course` + `mine_regression`。
- 验收（零参数）：`/function alice_test:scene_a` → 右键 `alice:mining_scene_tester`（或 `alice:target_selector` 右键方块）。
  期望：`[MiningPlanner探针] planned ... pathStatus=REACHED` → `[BotMiner探针] 站位移动启动` →
  `挖掘完成: target=4, 64, 4`；不再出现 legacy `PathExecutor`/`SurfacePathfinder` 日志。

## D-065：站位选择 off-by-one 修复 + 排除目标正上方（Mine 兼容批）

- 状态：已实施，待客户端验证（用户 2026-09-09："保留站位视线检查选优挖掘，先做兼容，直接用 Movement 走到站位"）
- 事实（`scene_a` 客户端日志）：
  `[BotMiner探针] 挖掘前置: target=4,64,4 botPos=4,65,4 standGoal=4,65,4 los=true`、
  `开始挖掘: target=4,64,4 face=down` → bot **站在目标顶上朝下挖**，挖完掉进坑。
- 根因：`StandingPointSelector.isValidStandingPoint` 写的是 `MovementHelper.canWalkOn(level, pos.below())`，
  而 `canWalkOn(level, footPos)` 的语义是"能否站在 footPos"（内部已检查 `footPos.below()`）——
  等于要求"候选脚位**下方两格**有支撑"，**所有正常地面站位被拒**，只剩"站在一个自身有支撑的方块顶上"，
  于是只能站在目标方块顶上。
- 修复（兼容批，不改变设计方向）：
  1. `canWalkOn(level, pos.below())` → `canWalkOn(level, pos)`；
  2. 排除 `pos.equals(target) || pos.equals(target.above())`——站在目标顶上挖 = 挖掉自己的支撑，
     与 legacy `BotMiner.pickStandCandidates` 的排除规则一致。
- 保留（用户裁定）：Alice 的"站位候选 + 视线评分 + 选优"流程不变；走到站位由新内核
  `PathRetryRunner`（D-064 批次 1）负责。**不采用** Baritone 的"目标列即终点 + 边走边破坏"模型。
- 待讨论（用户指定，先不动代码）：① 站位选优机制（评分维度、与 legacy 候选规则合并）；
  ② 需要挖掘通道的目标（有限清障 vs Baritone 式 B1/B2，与 HARD_PATH 红线的关系）。
  见 `docs/MINE_MIGRATION_DESIGN.md` §2.3。
- 验收：`/function alice_test:scene_a` → 右键 `alice:mining_scene_tester` →
  期望 `standGoal` 不再是 `target.above()`（应为侧面同层站位），`face` 不再是 `down`。

## D-066：可挖掘面语义修正——从"评分参照"改为"站位前提条件"

- 状态：已实施，待客户端验证（用户 2026-09-09："可挖掘面真正参与选优不是作为参照评分，而是这个站位能挖掘的前提条件"）
- 改动：
  1. `StandingPointSelector.isValidStandingPoint` 新增**可挖掘面前提**：
     从该站位的假设眼位（站位中心 + `BOT_EYE_HEIGHT-0.5`）执行 `LineOfSightChecker.checkFromEye`
     （目标边界内缩多面体采样：中心 + 6 面内缩点，`SAMPLE_EPSILON=0.08`），
     要求**至少一条射线先命中目标**，且**该可见采样点在触及距离内**（"看得到但打不到"不算能挖）；
  2. `StandingPointEvaluator` 移除视线评分项（`LINE_OF_SIGHT_WEIGHT` 及 `calculateLineOfSightScore` 删除），
     评分只剩 `1×距离 + 0.5×高度`；LOS 结果仍保留在评分记录/`MiningPlan.visibility` 里供日志与计划快照。
- 语义：**视线 = 过滤（能不能挖）；距离/高度 = 排序（在能挖的站位里选哪个）**。
- **已识别的后果（待讨论，见审查清单）**：被遮挡但可清障的站位不再进入候选 →
  现有"运行时发现视线受阻 → `findDirectBlocker` 挖掉遮挡块重试"这条路径会失效
  （目标被完全遮挡时直接 `no_valid_standing_point`）。

## D-067：挖掘站位选优新框架定稿（两模式 + BREAK_AND_ENTER + 成本估算）

- 状态：**设计定稿**（用户 2026-09-09 逐条确认"全部同意"）；实施按 `docs/MINING_STAND_SELECTION_DESIGN.md` §8 的 5 个批次推进
- 背景：站位系统审查发现两套候选实现、独立清障、候选范围过窄、off-by-one（D-065）等问题 → 用户裁定**重新设计**（不是修复）
- 定稿要点：
  1. **两模式**：A（直接可挖）保留多角度候选 + 视线前提 + 评分；A 无解**立刻**转 B（哪怕只挡一层）；
     B（埋藏/需通道）候选 = 4 面 × {同层, 低一层} + **正下方 (y−2)**，到达沿用 `withWorldModification` 寻路（破坏+放置）。
  2. **候选范围**：A 扩到"眼位可触及目标任一面"的格子；垂直规则 = y 与 y−1 全水平展开、**y−2…y−4 只允许正下方**、上方维持原上界；
     排除目标自身与目标正上方；站位允许"自己搭平台"。
  3. **评分只负责挖掘效率**：主项 = **到达站位的路径成本（含破坏/放置）**；拾取距离/可见面数/安全性**不进评分**。
  4. **成本估算两方案可切换**（默认 S2）：S1 直线下界；S2 一次纯通行 Dijkstra 成本场（现成可站候选零损失）；top-K（K=4/6）精算 + K 递增。
  5. **目标下方无支撑**：放支撑方块 vs 只从正下方挖，按成本选。
  6. **`BREAK_AND_ENTER`**（新增类型，不动 `BREAK_AND_TRAVERSE`）：dx=1/dy=0；破坏 `{to, to.above()}`，
     **仅当进入存在高低差时**再补 `to.above(2)`；只破坏一格语义。
  7. **兜底**：模式 B 的 9 格全不可用时允许"走进目标格挖"，但受 `MiningBudget` 限制
     （默认普通方块成本；其他分组用标签/ID 白名单设上限，**不硬编码**）。
  8. **线程模型：暂不异步**。现状同步主线程；Baritone 靠 `BlockStateInterface` 快照 + 线程池。
     前置条件 = 快照层（同时解决规划期投影世界问题）；届时寻路与站位评估共用快照。
  9. **删除**：独立清障（`findDirectBlocker`/`MAX_CLEAR_DEPTH`）、`BotMiner.pickStandCandidates`、`LineOfSightChecker.blockerCount`；
     `BotMiner` 退役（伐木一起迁）；`MiningSceneFixture`/`MiningReplanFixture` 重写。

## D-068：新增 `BREAK_AND_ENTER`（破坏目的地格并进入）—— D-067 批次 1

- 状态：**已验收**（2026-09-09 客户端：`[BreakEnter] SUMMARY plan_a=PASS plan_b=PASS execute_a=PASS`；实跑 `block_break_done pos=23,64,100 ticks=6` → `cleared 0/2` → `pos=23,65,100 ticks=6` → `cleared 1/2` → `execute_a=PASS COMPLETED foot=23,64,100`）。**实测破坏速率：石头 + 石镐 = 6 tick/格**（MiningBudget 参考数据）；回归 **14/14 PASS** + `coverage=PASS executed=...,BREAK_AND_ENTER,...`（D-061 覆盖断言生效）
- 语义（对照 Baritone `MovementTraverse.positionsToBreak = {to.above(), to}` + `Movement.prepared`）：
  目的地格被可破坏方块占用时，**先破坏目的地躯干 + 头位，再走进该格**；破坏走
  `BlockInteraction`/`BlockBreakSession`（工具/进度/广播/ABORT），不使用瞬间销毁。
- 几何：同层卡基数 1 格（`|dx|+|dz| == 1`、`dy == 0`）。
- **扩张一格**（D-067 ⑯）：仅当"进入时存在高低差"。在 Alice 的 dy=0 方块对齐模型下，源/目的地支撑同层
  无法产生高低差，**唯一实际情形是空中衔接**（上一段以空中状态结束）→ 执行期若起破坏时 `!bot.onGround()`，
  额外破坏 `to.above(2)` 并记录 `[BreakEnter] extra_head_break reason=airborne_entry`。
- 实现清单：
  - `MovementType.BREAK_AND_ENTER` + `MovementSpec` 几何 + `CostModel`（TRAVERSE_COST，破坏成本由 provider 累加）；
  - `PathRequest.withWorldModification` 加入（不进 `PathRequest.of`）；
  - `PlannedMovementSpecs`：能力 `pathAccess(LOCAL_STEP)` + 工厂 key；
  - `SurfaceMovementProvider.appendBreakAndEnter`：目的地被阻挡 + 落点支撑可站 + 破坏方块可破坏且成本有限；
  - `BreakAndEnterExecution` + `BreakAndEnterExecutionFactory`（破坏目的地列 → 走入 → `isAtFootColumn` 完成判定）；
  - `PathSession`：`isPrecisionType` 加入；`currentTargetStillValid` 用"落点支撑 + 目的地列仍可破坏"分支
    （**必须在通用通行检查之前**，同 DOWNWARD 教训）；`futureTargetBlocked` 对其实"只查支撑"分支；
    疾跑前瞻加入该类型；
  - 回归：`REQUIRED_COVERAGE` 加入 `BREAK_AND_ENTER`；新增 `break_enter_course` 场景（第 14 项）。
- 遥测：`BlockBreakSession` 已有 `block_break_done ... ticks=`（供 `MiningBudget` 标定）。
- 验收（零参数）：`/function alice_test:break_enter_course` + 右键 `alice:pathing_break_enter` →
  `[BreakEnter] SUMMARY plan_a=PASS plan_b=PASS execute_a=PASS`；回归 `coverage=PASS`（含新类型）。

## D-069：挖掘站位候选重写 + 成本估算（D-067 批次 2）

- 状态：**已验收**（2026-09-09 客户端：`[MiningPlanner] target=4,64,4 startFoot=0,64,0 candidates=60 estimate=DIJKSTRA nodes=334 ms=12 planned=4 chosen=1,64,1 cost=1.330 pathSize=1 los=true`；切换后 `estimate=LOWER_BOUND nodes=0 ms=0` 选择一致；回归 14/14 + coverage PASS）
- 设计依据：`docs/MINING_STAND_SELECTION_DESIGN.md` v7（§2.1 候选、§2.2 评分/估算、§6 Q2′/Q6′）
- 改动：
  1. **`MiningTuning`（新）**：估算方案开关（`LOWER_BOUND`/`DIJKSTRA`，默认 S2）、成本场半径/节点上限、
     top-K（4/10）、视线采样内缩距离（0.08）；命令 `/alice mining`（查看）与
     `/alice mining estimate lower_bound|dijkstra`（切换）。
  2. **`StandingPointSelector` 重写**：候选 = 眼位可触及范围内**现成可站**的格子；
     垂直规则 = y+1/y/y−1 全水平展开 + **y−2…y−4 只允许正下方**；排除目标自身、目标正上方；
     **删除 `target.above(2)`**（脚下支撑必挡视线，无效候选）；可挖掘面为硬前提（D-066）。
  3. **`StandingCostEstimator`（新）**：S1 直线下界（`GoalFoot.heuristic`）/ S2 一次**纯通行 Dijkstra 成本场**
     （复用内核 provider 的 TRAVERSE/DIAGONAL/ASCEND/DESCEND 谓词，半径/节点上限可调）。
  4. **`StandingPointEvaluator` 重写**：评分 = **精确路径成本**（`score`）+ 估算成本（`estimate`，仅排序）；
     视线/距离/高度/拾取距离/可见面数**全部移出评分**。
  5. **`MiningPlanner` 重写**：当前站位可挖 → 直接用；否则 候选 → 估算排序 → **top-K 精算**
     （`CorePathPlanner` + `PathRequest.of`）→ 取精确成本最小者；"最优精确成本 > 第 K+1 名估算"则 K 递增（上限 10）。
     新增观测日志 `[MiningPlanner] candidates=.. estimate=.. nodes=.. ms=.. planned=.. chosen=.. cost=.. pathSize=..`。
  6. **`LineOfSightChecker`**：删除 `blockerCount`（恒 0/1 的死字段，D-067 附录）；采样内缩距离走 `MiningTuning`。
- 未做（批次 3）：模式 B（埋藏/需通道）候选与编排、目标下方支撑双策略、`MiningBudget`、`collectDrops` 参数、
  失败枚举 `found_but_unminable`、A→B 降级。
- 验收（零参数）：`/function alice_test:scene_a` + 右键 `alice:mining_scene_tester`（或 `alice:target_selector` 右键方块），
  检查 `[MiningPlanner] target=.. candidates=N estimate=DIJKSTRA nodes=.. ms=.. planned=.. chosen=.. cost=..`；
  切换 `/alice mining estimate lower_bound` 后再跑一次，观察 `estimate=LOWER_BOUND` 与候选/选择差异。

## D-070：两模式编排 + 下方支撑双策略 + MiningBudget + collectDrops（D-067 批次 3）

- 状态：**已验收**（2026-09-09 客户端：修复后 `[MineCourse] SUMMARY free=PASS wall=PASS blocked=PASS headroom=PASS buried=PASS`；`blocked` 走 `mode=TUNNEL candidates=13 estimate=EXHAUSTIVE chosen=23,64,135 cost=7.601`；`buried` 走 `found_but_unminable`）
- 设计依据：`docs/MINING_STAND_SELECTION_DESIGN.md` v7（㉑–㉙）
- 改动：
  1. **`MiningBudget`（新）**：`collectDrops` 为必要参数；兜底破坏上限 = `N × 普通方块破坏 tick`（N=10），
     按目标珍贵程度分档（普通 1× / 普通矿石 2× / 钻石·残骸·绿宝石 4×）；估算不可用时回退实测基线 **6 tick**。
  2. **`PathRequest.miningApproach`（新）**：允许 `BREAK_AND_ENTER`/`BREAK_AND_TRAVERSE`/`PLACE_STEP`，
     **显式禁用 PILLAR/FALL/DOWNWARD**（㉘）。
  3. **`MiningPlan`**：新增 `mode`（CURRENT/DIRECT/TUNNEL/ENTER_TARGET）与 `supportPlacementPos`（可空）。
  4. **`MiningPlanner` 重写编排**：A（当前/直接）→ 下方无支撑时比较"放支撑块 + 侧面站位" vs "只从正下方挖"
     → B（`tunnelCandidates`：4 面 × {y,y−1} + 正下方，遇实心方块按预算决定是否继续）
     → 兜底 `ENTER_TARGET`（目标格为终点，破坏成本超预算即 `found_but_unminable`）。
  5. **`BotMiner`**：按 mode 选择到达请求（TUNNEL/ENTER_TARGET 用 `miningApproach`）；
     新增"目标下方放支撑块"子步骤（到达站位后、挖掘前）。
  6. **`MineTask`**：接入 `MiningBudget`；**删除独立清障**（`findDirectBlocker`/`MAX_CLEAR_DEPTH`/射线）；
     失败一律走重新规划（A 无解时规划器自动降级 B），重试预算 2 次；`collectDrops=false` 时跳过收集阶段。
- 未做（批次 4/5）：`BotMiner` 退役与伐木迁移、夹具重写、`mine_regression`。
- 验收（零参数）：`/function alice_test:mine_course` + 右键 `alice:mine_course_runner` →
  `[MineCourse] SUMMARY free=PASS wall=PASS blocked=PASS headroom=PASS buried=PASS`
  （`blocked` 期望 `mode=TUNNEL`；`buried` 期望 `found_but_unminable` 或 `TUNNEL`）。

### D-070 修正（2026-09-09 客户端首测）

- 首测结果：`free=PASS wall=PASS blocked=FAIL headroom=PASS buried=FAIL`（两次一致）。
- **① `blocked=FAIL` 是真 bug（集成错）**：模式 B 的候选（需破坏才能进入的格子）**不在纯通行成本场里**，
  被 `StandingCostEstimator` 直接丢掉 → `ranked` 为空 → 模式 B 永远不可用
  （日志 `tunnel=no_reachable_tunnel_standing_point`）。
  **修复**：模式 B 候选通常 ≤ 11 个，**跳过估算器、全部候选精确规划**（只用一个下界做展开顺序），
  日志改为 `estimate=EXHAUSTIVE`。
- **② `buried=FAIL` 是场景问题**：场景里 buried 目标**上方没封**，bot 从斜上方站位看到了目标顶面 →
  模式 A 合法成立（`mode=DIRECT chosen=22,64,132 los=true`）。**修复**：场景补 `setblock 23 65 128 stone`。
- 经验（再次印证 D-039/D-042）：**允许"从任意角度挖"的站位系统下，场景必须把所有面（含顶面）都封死**
  才能构造"埋藏"用例；只封四面会留下斜上方视线。

## D-071：`MineBlockRunner` 替代 `BotMiner` 在挖矿链路的角色（D-067 批次 4 第 1/2 步）

- 状态：已实施，待客户端验证（用户 2026-09-09："好一步一步来"）
- 新增 `action/MineBlockRunner`（消费 `MiningPlan`）：**走到站位 → （必要时）目标下方放支撑块 → 破坏目标**。
  - 走位请求按 mode：`TUNNEL`/`ENTER_TARGET` → `PathRequest.miningApproach`，其余 → `PathRequest.of`；
  - 破坏统一走 `BlockBreakSession`（工具/进度广播/ABORT/超时）；
  - 运行期视线复核失败 → `LINE_OF_SIGHT_BLOCKED`（`retryable=true`，任务层重规划/换站位）。
- `MineTask` 切换到新原语：删除 `BotMiner.FailureReport`/`PlanInvalidation`/`syncMinerRecoveryEvents`/清障相关代码；
  失败处理简化为"硬拒绝 → 上报；否则重试（≤2）→ 上报"。

## D-072：`CollectDropsTask`——公用收集子任务（替换 `DropCollectionTask`）

- 状态：已实施，待客户端验证
- 依据：`docs/MINE_MIGRATION_DESIGN.md` §4（用户裁定：公用子任务 + 按需世界修改权限 + 终点=掉落物位置 + best-effort + 删反射/删挖台阶）
- 实现：
  - 构造参数 `(bot, origin, scope, expectedIds, allowWorldModification, totalBudgetTicks)`；
  - 终点 = 物品 `blockPosition()`；移动走 `PathRetryRunner`（`withWorldModification` 或 `of`）；
  - 自然拾取等待（`PICKUP_WAIT_TICKS=40`），**不反射、不 `playerTouch`**；
  - 每物品预算 200 tick + 任务总预算 600 tick；不可达 → 标记并继续；
  - 终态 `[CollectDrops] SUMMARY reason=.. collected=n/m vanished=v unreachable=k ticks=..` → 始终 `DONE`（best-effort）。
  - `MineTask` 在 `collectDrops=true` 时调用（`allowWorldModification=true`）。
- 删除：`task/DropCollectionTask`（241 行屎山：反射 pickupDelay、sticky/abandoned/retries/captureWait 多套计数、收集阶段挖台阶）。

## D-073：伐木任务禁用 + `BotMiner` 删除（用户裁定）

- 状态：已实施
- 用户裁定（2026-09-09）："BotMiner 要删除，伐木任务直接禁用，不能耽误当前流程"；
  伐木**推迟并立专项**（原代码混乱，专项重写）。
- 实施：
  - 删除 `action/BotMiner`（693 行）与 `task/lumber/*`（`ContinuousLumberTask` 531 行 / `RegionLumberTask` 505 行 /
    `TreeDetector` / `Tree` / `TreeType`，共 1377 行；git 历史保留，专项重写）；
  - `alice:auto_lumberer` / `alice:lumber_planner` 两个物品改为"已禁用"提示（保留物品与贴图，避免资源缺失）；
  - `BotSelftest` 的注释与 import 同步清理；`Task` javadoc 改指 `MineBlockRunner`。
- 夹具说明：`MiningSceneFixture` / `MiningReplanFixture` 已只依赖新规划器（`MiningPlan`/`MiningPlanner`），
  **无需重写**（设计里"夹具重写"的动因是它们引用 legacy API，现已不存在）。

### D-071 修正（2026-09-09 客户端首测：`scene_a` FAILED/OUT_OF_REACH）

- 现象：`[MineRunner] walk_start stand=1,64,1` → 立刻 `failed reason=OUT_OF_REACH feet=1,64,1`，
  重试 2 次后 `ESCALATE`。
- 根因（两个问题叠加，**都不是场景问题**）：
  1. **走位没等落定就开挖**：`MineBlockRunner.atStand()` 只比较 `blockPosition()`，
     bot 刚跨进格子（脚在格子边缘、x≈1.0）就停表 → 实际眼位到目标中心 **5.07 > 4.5** → `OUT_OF_REACH`
     （旧 `BotMiner` 有 0.6 水平容差的 `atStandPos()`）。
  2. **触及判定口径不一致**：规划期查"眼位→**可见面采样点**"（4.11 ✓），运行期查"眼位→**方块中心**"
     （4.39，再叠加边缘偏移就超 4.5）。方块中心比最近面采样点最多远 ~0.87 格。
- 修复：
  1. `MineBlockRunner` 只要有 runner 就继续推进，**直到 `PathRetryRunner` 报 DONE**
     （会话按 EXACT 容差把 bot 落定到站位中心）才进入放置/破坏阶段；
  2. 运行期触及判定改用**可见面采样点距离**（与规划期同口径）；
  3. 规划期加**保守余量** `MiningTuning.reachMargin = 0.4`
     （`眼位→可见面 ≤ reach − 0.4`），吸收"假设眼位 vs 实际眼位"的差异（v7 ㉓ 保守化）；
  4. 失败日志补充 `eye/sample/dist/reach` 数值，便于下次直接定位。

## D-074：掉落物来源改为"破坏事件配对" + 追踪距离上限（用户裁定）

- 状态：已实施，待客户端验证（用户 2026-09-09 讨论后裁定）
- 用户裁定：
  1. **从挖掘事件产生的掉落物开始捕获**（"一定准确"）——不再用"捕获时位置 == 挖掘点"这种位置相等匹配；
  2. **超过 N 格放弃**追踪；
  3. 爆炸无所谓；**连锁模组要兼容**（一般会把掉落物聚集在一起）；
  4. 方块连锁更新产生的掉落物（砂砾下落、植物掉落等）**以后遇到再处理**；
  5. 同类型物品混淆暂时可接受。
- 实现（`ScopeBuffer`）：
  - `begin(center, radius, ownerUuid)`：只把**所有者（bot）造成的** `BlockEvent.BreakEvent` 登记为掉落来源，
    避免把附近玩家挖出的掉落物算成本 bot 的收集目标；
  - 新增 `recentBreaks`（`pos + 游戏 tick`）+ `matchBreakSource`：物品生成时与
    **最近 `DROP_PAIR_WINDOW_TICKS = 10` tick 内、`DROP_PAIR_RADIUS = 3` 格内**的破坏点配对，
    取最近者；未配对 → `source=unpaired`（例如区块加载带入的旧物品），不计入收集；
  - 新增 `liveDrops()`：只返回已配对来源的存活掉落物；
  - 连锁挖掘模组：一次破坏 N 格 → N 个破坏点都登记 → 掉落物无论聚在一处还是各掉一份都能配对 ✓；
  - 作用域半径 `8 → 16`（覆盖连锁范围），配合 owner 过滤保证不误收。
- 实现（`CollectDropsTask`）：
  - 候选改用 `scope.liveDrops()`；
  - 新增 `MAX_CHASE_DISTANCE = 32`：超过该距离标记 `unreachable(reason=too_far)` 并放弃；
  - 归属判定仍是"背包数量差"（同类型混淆可接受）。

## D-075：连锁挖掘（模组兼容）默认关闭 + 玩家手动启用（用户裁定）

- 状态：诊断路径已实施（`alice:chain_test_runner` + `chain_course`）；生产开关**未实施**
- 用户裁定（2026-09-09）：
  1. **全局默认原版**（不连锁），玩家在游戏内**手动启用**连锁模式；
  2. 默认只连锁**矿石与原木**；
  3. 以后要做 **bot 专属连锁配置映射**：玩家可自定义 bot 的连锁配置；
  4. 范围、批量建筑等能力**等联动其他模组**再做。
- 机制分层（红线）：
  - 连锁触发只允许在**任务层**（`MineTask` / 未来 `ChainMineTask`），
    **禁止**进入 `MineBlockRunner` / `BlockBreakSession`——两套机制永不交错；
  - 生产路径（`MiningPlanner` → `MineBlockRunner` → `BlockBreakSession`）保持原版单格语义，默认 `OFF`；
  - 全局同一时刻只允许一次连锁（模组 `EventHandler.captureAgent` 是**静态单例**，连锁期间会吞掉落物生成）。
- 开关设计（待实施）：`MiningTuning.ChainMode{OFF, AUTO, FORCE}` + `/alice mining chain off|auto|force`；
  默认 `OFF`；`AUTO` = 模组在场 + 目标属矿石/原木 + 调用方允许；模组缺失时**回落我们自己的单格挖掘**（回落不是猜测）。
- 已核实的模组事实（Ore Excavation 1.13.174 字节码，作为兼容依据，非推测）：
  - 触发入口 `MiningScheduler.INSTANCE.startMining(ServerPlayer, BlockPos, BlockState, ExcavateShape, Direction, Direction)`
    是公开服务端 API；`shape = null` 合法；返回 `null` = `EventExcavate.Pre` 被取消；
  - 连锁破坏走 `player.gameMode.destroyBlock(pos)`（`ServerPlayerGameMode.m_9280_`）→ **会触发 `BlockEvent.BreakEvent`**，
    与本项目 `BlockBreakSession` 同一调用，作用域配对链成立；
  - 连锁期间 `EventHandler.onEntitySpawn` 取消 ItemEntity/XP 生成并缓冲进 `captureAgent`
    （**仅 `tickMiner` 那一 tick 内**，`tickAgents` 前后 set/clear），agent 结束时 `dropEverything()` 一次性生成，
    全部堆在**同一格**（`autoPickup ? player.blockPosition() : origin`）；
  - `EventHandler.onBlockBreak` 对普通破坏会给玩家发 `PacketExcavation`
    （bot 的 `FakeConnection.send` 直接丢弃 → 对原版挖掘无影响）。
- 兼容性加固（已实施）：
  - `ScopeBuffer.onEntityJoin` 跳过 `event.isCanceled()`（模组取消的生成不会进入世界）；
  - `matchBreakSource` 增加"破坏点位置回退"：缓冲型模组延迟生成时，掉落物落在已登记破坏点上也能配对。
- 结论（回答"强行兼容是否影响原版挖掘"）：**反射调用本身不影响**（无 mixin、不覆盖原版方法、
  只在诊断/显式启用时调用）；模组在场的副作用仅限连锁运行的那一 tick（掉落物缓冲）与每次普通破坏的一个客户端包。

### D-075 修正（2026-09-09 客户端首测：连锁成功但"幻影掉落物"导致收集空转 ~11 s）

- 现象：`mined=8 broken=9 drops=10 inventory_gain=9`（连锁正常），但 bot 之后逐个追掉落物，
  每个都在 `pickup_wait_timeout` 后标 `unreachable`，`collected=1/10 vanished=9 ticks=220`；
  任务结束时作用域里仍有 9 个 `raw_iron` 被列为"存活掉落物"。用户观感："掉落物一开始就全进背包了，却在同一格捡来捡去"。
- 根因（**顺序 + 取消语义**，非规划缺陷）：
  1. 模组连锁时用 `player.gameMode.destroyBlock` 逐格破坏 → 原版**每格**生成一个 ItemEntity
     → 模组的 `EventHandler.onEntitySpawn` 把它们 `setCanceled(true)` 并缓冲，连锁结束才在**同一格**
     （`autoPickup ? 玩家位置 : origin`）一次性生成聚合堆；
  2. 模组的监听器是 `@SubscribeEvent(priority = LOWEST)`（字节码实证），而 `ScopeBuffer.onEntityJoin`
     也是普通/LOWEST 优先级 → 同一优先级靠注册顺序，**我们有时先收到未取消的生成事件**，
     于是把"从未进入世界"的 10 个幻影实体登记成了掉落物；
  3. 幻影实体的 `isAlive()` 恒为 true（只是没被加入世界）→ `liveDrops()` 过滤不掉 →
     `CollectDropsTask` 逐个追、逐个等 40 tick、再标 `unreachable`（日志还谎报"不可达"）。
- 修复（`ScopeBuffer`）：
  1. 生成事件只**排队**（`PendingItem(item, tick)`），到**服务端 tick 末**再确认
     `level.getEntity(item.getId()) != null`——与事件顺序无关；未进入世界的如实记
     `作用域忽略未进入世界的掉落物(生成被取消/缓冲)` 并丢弃；
  2. `liveItems*`/`liveDrops` 的存活判定升级为 `inWorld()`（`isRemoved` + 内容非空 + **真的在 level 里**）；
  3. 配对窗口仍按**生成 tick** 计算，聚合堆（在 `origin`）可与破坏点配对 ✓。
- 客户端复测结果（2026-09-09 20:49，修复前）：连锁 `mined=8`、`inventory_gain=9`（聚合堆被一次拾取）✓
  说明**触发与拾取语义都对**，问题只在幻影登记。
- 簇级收集（**已实施**，用户 2026-09-09 裁定"簇级可以，但准确度要够"）：
  - 候选按**连通距离 2.0 格、|Δy| ≤ 1** 聚簇（对应原版拾取盒 ±1.3 x/z、±0.5 y）；
  - 走位一次到簇内最近成员格 → 等该簇成员全部消失或 40 tick 超时；超时后**最多再换 2 次锚点**扫尾，
    仍够不到才如实记 `pickup_timeout`（不再把"顺手捡到的"误报成 `unreachable`）；
  - **计数口径 = 背包增量**（唯一地面真相），替代"实体是否消失"的推断；
  - **守恒交叉校验**：`背包增量 == 簇起始 stack 总和 − 结束剩余存活 stack 总和`，
    不等即 `[CollectDrops] MISMATCH`（暴露同类型被他人拾取/重复生成/背包满/统计漏洞），**不静默相信**；
  - 终态 `[CollectDrops] SUMMARY reason=.. collected=<物品数>/<期望物品数> entities=<消耗>/<已知>
    clusters=.. unreachable=.. pickup_timeout=.. mismatch=.. ticks=..`（`collected` 语义从"实体个数"改为"物品个数"）；
  - 不变：只收 `liveDrops()`（bot 自己的破坏配对）、默认纯通行寻路（D-076）、best-effort 始终 `DONE`、
    32 格放弃上限；经验球与"非破坏事件产生的掉落物"仍不收集（D-074）。
- **客户端验收（2026-09-09 21:06，用户"测试通过"）**：
  `作用域忽略未进入世界的掉落物` ×9 + `作用域捕捉掉落物` ×1（真掉落物）→
  `drops=1`、`cluster_start items=9`、`cluster_done delta=9 remaining=0 ticks=5`、
  `SUMMARY collected=9/9 entities=1/1 clusters=1 unreachable=0 pickup_timeout=0 mismatch=0 ticks=8`、
  `terminal=COMPLETED durationTicks=32`。修复前同场景为 `drops=10 collected=1/10 unreachable=9 ticks=220`。
  自家挖矿路径同样复测通过（2026-09-09 21:19，`scene_a` + `alice:mining_scene_tester`）：
  `walk_start → break_start(eyeDist=3.87) → done`、`作用域捕捉掉落物: cobbled_deepslate x4 y64 z4 source=4,64,4`、
  `cluster_done delta=1 remaining=0 ticks=11`、`SUMMARY collected=1/1 mismatch=0 ticks=14`、`MineTask COMPLETED 37 tick`。


## D-076：寻路红线现行表述（取代 HARD_PATH 旧语句，语义不变）

- 状态：稳定（2026-09-09 用户裁定"这个红线也要改一下语句了，太老了"）
- 背景：旧表述里引用的 `DropCollectionTask`、`BotMiner`、`target_requires_tunnel`、`HARD_PATH`/`SOFT_SURFACE`
  分别已被删除或不再是实现语义（D-015/D-071/D-073），继续沿用会误导新会话。
- **现行表述（权威版）**：
  > **寻路请求默认纯通行；破坏/放置只能由上层任务显式授权，并受预算闸门约束。**
  1. **默认**：`PathRequest.of`（`TRAVERSE / DIAGONAL / ASCEND / DESCEND`）——走到目标不允许破坏、放置或特殊垂直移动；
  2. **显式授权入口**（已登记，不得新增隐式默认）：
     - 挖掘站位（`MiningPlanner` 模式 B / 浮动目标支撑）：`PathRequest.miningApproach`，
       允许 `BREAK_AND_TRAVERSE / BREAK_AND_ENTER / PLACE_STEP_AND_TRAVERSE`，
       **禁用 `PILLAR / FALL / DOWNWARD`**；超出 `MiningBudget.maxExtraBreakTicks` → `found_but_unminable`（如实失败）；
     - 掉落物收集（`CollectDropsTask`）：调用方显式 `allowWorldModification=true` 才走 `withWorldModification`，默认 `of`；
     - 其他任务（道路 / 放置 / 转移）：各自入口显式声明能力集，不共享隐式默认；
  3. **分层边界**：红线约束**寻路请求**（"怎么走过去"），不约束**动作层**破坏
     （`BlockBreakSession` 挖目标与有限清障）；
  4. **禁止**：寻路器自行挖穿地形；把 `SEARCH_LIMIT` 当作授权（D-004）；把不可达当作"那就挖过去"；
     把实验性移动模式隐式接入正式任务；
  5. **术语对照**：`HARD_PATH` / `SOFT_SURFACE` 是旧内核模式名，现行实现用**路径请求能力集 + 授权/预算**表达同一语义；
     `DropCollectionTask` → `CollectDropsTask`、`BotMiner` → `MineBlockRunner`、
     `target_requires_tunnel` → `found_but_unminable` / 模式 B 预算闸门。
- 影响：`AGENTS.md`、`AI_PROJECT_STATE.md`、`AI_DEVELOPMENT_PLAYBOOK.md`、`START_HERE.md`、
  `MINE_MIGRATION_DESIGN.md` §6 与相关 skill 同步改为现行表述；**行为与边界不变**。

## D-077：连锁挖掘生产开关（默认 OFF + 玩家手动启用 + 矿石/原木白名单）

- 状态：已实施，待客户端验证
- 用户裁定（D-075）：全局默认原版，玩家在游戏内**手动启用**连锁；默认只连锁**矿石与原木**；
  bot 专属连锁配置映射与范围/批量建筑等能力留待后续。
- 实现：
  - `compat/ChainMining`：软依赖反射适配器（`available/isChainable/start/isRunning/minedCount/stop/settingsSummary`），
    `MineTask` 与 `ChainMineDiagnosticTask` 共用，消除重复反射；
  - `MiningTuning.ChainMode{OFF, AUTO, FORCE}`，**默认 OFF**；`AUTO` = 模组在场 + 目标命中白名单
    （`forge:ores` + `minecraft:logs`）；`FORCE` = 仅诊断/测试用（绕过白名单）；
  - 游戏内开关：`/alice chain`（查询，含模组在场状态与配置摘要）、`/alice chain off|auto|force`；
  - `MineTask` 新增 `Phase.CHAIN`：规划期一次性判定 `useChain` → `MineBlockRunner(..., walkOnly=true)`
    只走到站位 → 任务层触发连锁 → 轮询 `isRunning` → **校验目标方块真的没了** → 进入收集；
  - **回落如实**：`MOD_ABSENT / LEASE_BUSY / NOT_STARTED / REFLECTION_FAILED / prod_target_remains / prod_timeout`
    一律记 `[ChainMine] prod_fallback|prod_target_remains` 并回落本项目自己的单格挖掘，不把模组问题变成任务失败；
  - **租约**：模组 `EventHandler.captureAgent` 是全局静态 → `ChainMining` 用全局租约挡住并发连锁，
    连锁结束/停止/超时自动释放。
- 分层边界（红线 D-076 不变）：
  - 连锁触发**只在任务层**（`MineTask.tickChain`），**不进入** `MineBlockRunner`/`BlockBreakSession`；
  - 走位仍走 `MineBlockRunner` 的既有规划（模式 A/B + `PathRequest.of`/`miningApproach`），
    `walkOnly` 只表示"到位后不破坏"，不携带任何模组语义；
  - 收集仍由 `CollectDropsTask` 负责（延迟登记 + 簇级 + 守恒校验），不因连锁改变。
- 本轮未做（按用户裁定推迟）：bot 专属连锁配置映射（范围/速度/白名单自定义）、
  模组 `autoPickup=true` 变体验证、连锁与 `MiningBudget` 的额度换算。
- 测试入口：`/function alice_test:chain_mine_course` + `alice:target_selector`（A/B 对比，见 `AI_TEST_MATRIX.md`）。

### D-076 修正（2026-09-09 客户端：单格挖掘"看着到位却不捡"）

- 现象（`chain_mine_course` 场景，chain=OFF）：bot 挖掉矿石后，收集阶段站在**未挖矿石顶上**（脚 y=65），
  离掉落物（落在 y≈64.125）差 0.25 格；40 tick ×2 次换锚点后掉落物被他人取走（`MISMATCH delta=0 expected=1`），
  单格收集耗时 105~108 tick；用户观感"一直不捡掉落物"。
- 根因（**判据错，不是寻路错**）：簇级收集的"已到位"用的是
  `到锚点方块中心水平 ≤1.2 且 |Δy| ≤1.5`，与**原版拾取判定**不是一回事：
  原版 `Player.tick()` 用 `getBoundingBox().inflate(1.0, 0.5, 1.0)` 与掉落物包围盒相交
  （srg `Player.class` 字节码实证）；掉落物落地后中心在方块底面 +0.125，站在上一层（脚 y+1）
  时外扩盒底 64.5 > 掉落物顶 64.25 → **不接触**。假"到位"还会**提前取消寻路**
  （日志 `[R4 Session] failed ... status=CANCELLED code=SESSION_CANCELLED`），bot 停在半格之外干等。
- 修复（`CollectDropsTask`）：
  1. 走位优先：`runner != null` 时**走完**（原版拾取会在路过时自动发生），不再用"距离近"提前取消；
  2. "已到位"改用 `inPickupRange(item)` = **玩家包围盒 inflate(1.0, 0.5, 1.0) 与掉落物包围盒相交**（与原版同口径）；
  3. 到位却够不到 → 换**离 bot 最近的存活成员**重走（≤2 次）→ 用尽后如实记 `not_in_pickup_range`；
  4. 场景侧：观察点后移 1 格（`(23.5,64,169.5)`），避免玩家站在拾取盒内把掉落物先捡走。
- 二次修正（同日，用户澄清"两次都没进背包"）：重写时**误删了"创建寻路"那一段**，
  导致 bot 完全不走位（日志无 `sweep_start`）→ 换锚点 2 次后直接 `not_in_pickup_range` 退休。
  已补回：`runner == null && 未进入拾取范围` → 建 `PathRetryRunner` 走到锚点。
  同时 `retire` 日志补 `itemPos/itemY/botFeet/botBox/inRange`，便于下次直接定位。
- **客户端验收（2026-09-09 22:34，用户"这次对了"）**：单格挖掘两次
  `SUMMARY collected=1/1 entities=1/1 clusters=1 unreachable=0 pickup_timeout=0 mismatch=0 ticks=12`，
  任务各 `COMPLETED` 25 tick；日志形态与用户观察一致——
  第一次无 `sweep_start`（已在拾取盒内，**直接吸走**），第二次有 `sweep_start`
  （**走下台阶进入被挖空的那一格**后吸走）。
- `MISMATCH` 的真实成因（已查清，非"他人拾取"）：原版 `ItemEntity` 合并的判据是
  `包围盒各向外扩 0.5`，相邻 1 格的两个同类掉落物**会合并**——被吸收的实体从世界移除（`entity_gone`），
  其数量并入吸收者；若吸收者来自**上一个被替换任务**（已不在当前作用域 `spawnedItems` 里），
  这次扫描的背包增量就是 0 → `MISMATCH`。这正是交叉校验该抓的"物品去了别处"。
  簇内自合并不影响守恒式（吸收者仍在簇内）。

## D-078：挖掘专项串联回归（批次 5，`alice:mine_regression`）

- 状态：已实施，待客户端验证
- 目标：一次右键覆盖挖掘链路的全部必要复测项，与寻路回归（只覆盖 Movement）分离。
- 覆盖（每用例前重放地形 + 复位 bot 到统一起点）：
  1. 规划 5 项（`mine_course_terrain`）：`free`/`wall`/`headroom` → 模式 A；`blocked` → 模式 B（TUNNEL）；
     `buried` → 模式 B 或 `found_but_unminable`（预算不足必须如实报，不许静默挖隧道）；
  2. 执行 2 项：`exec_direct`（露天目标）、`exec_blocked`（被包围目标走模式 B）——通过条件四项同时成立：
     子任务 `DONE` + 目标方块已空 + `MineTask.collectedItems()` == 期望件数 + 作用域内无剩余存活掉落物
     （另核对背包增量）；
  3. 兼容 1 项：`exec_chain`（临时切 `chain=AUTO`，跑 3x3 铁矿脉，期望 `collected=9`）；
     模组缺失 → `SKIP`（不计 FAIL），结束后恢复原档位。
- 入口（零参数）：`alice:mine_regression` 普通右键（或 `/function alice_test:mine_regression_course` 给物品并传送到中立观察点）。
- 复用：地形直接用既有 `*_terrain` 函数（与单项诊断同一套场景，避免"回归场景和诊断场景不一致"）；
  规划断言与 `mine_course` 同口径；执行复用 `MineTask` 本体（回归测的是生产路径，不是替身）。
- 终态：`[MineRegression] SUMMARY free=.. wall=.. blocked=.. headroom=.. buried=.. exec_direct=.. exec_blocked=.. exec_chain=..`
  + 每项细节行（`mode/stand/collected/inventoryDelta/dropsLeft/ticks`）。
- **客户端验收（2026-09-09 22:48，用户"PASS啦"）**：8/8 PASS，`ticks=99`（约 5 秒），任务 `COMPLETED`：
  `free/wall/blocked/headroom/buried/exec_direct/exec_blocked/exec_chain` 全 PASS；
  执行项细节 `exec_direct collected=1/1 delta=1 dropsLeft=0 ticks=19`、
  `exec_blocked collected=1/1+ delta=2 dropsLeft=0 ticks=57`、
  `exec_chain collected=9/9 delta=9 dropsLeft=0 ticks=15`。
- **floating 支撑放置（同日补入）**：新增 `floating_course` 场景（目标悬空、正下方挖成 1×1 竖井、
  东侧留实心块作水平支撑面——`placeAt` 的支撑面只扫水平+下，不扫上）+
  两个用例 `floating_plan`（规划必须给出 `supportPlacementPos == target.below()`）与
  `exec_floating`（执行必须放下支撑块 + 目标消失 + 收 1 件）；
  该用例**背包净增量为 0**（放支撑消耗 1 圆石、目标掉落 1 圆石）——正好证明两件事都发生。
  合计 10 个用例。

### D-078 修正（2026-09-09 客户端首测：7/8 PASS，唯一 FAIL 是断言过严）

- 首测结果：`free=PASS wall=PASS blocked=PASS headroom=PASS buried=PASS exec_direct=PASS
  exec_blocked=FAIL exec_chain=PASS ticks=94`，任务 `FAILED`（唯一 FAIL 来自断言）。
- `exec_blocked` 实际行为**全部正常**：`status=DONE/targetGone=true/collected=1/1/dropsLeft=0/ticks=57`，
  只有 `inventoryDelta=2 ≠ 1`。
- 根因（**不是链路缺陷，是断言口径错**）：模式 B（TUNNEL）为进入被包围的目标会沿途破坏 1 格通道，
  该方块同样掉落圆石；这件掉落物在 bot **走位/开挖阶段**就被自然拾取（早于收集阶段），
  因此 `CollectDropsTask.collected` 只记它扫到的 1 件（`collected=1/1` 自洽），背包增量却是 2。
- 修复：断言区分 `exactCollected`（露天单目标 / 连锁矿脉 → 精确相等）与"至少 N 件"
  （模式 B → `collected >= N && inventoryDelta >= N`，额外件数是通道副产品）；
  细节行对非精确用例显示 `collected=1/1+`。
- 副产物认知：`collected` 是**收集阶段**口径，`inventoryDelta` 是**端到端**口径，两者在"走位时自然拾取"
  场景下本就会不同；回归同时校验两者，避免把正常差异当失败。

### D-078 修正 2（2026-09-09 客户端：floating 用例暴露规划器缺口 → 已修）

- 首测（含 floating）：`floating_plan=FAIL mode=CURRENT/stand=21,64,190/cost=0.00/support=-`、
  `exec_floating=FAIL collected=1/1/inventoryDelta=1(期望0)/supportPlaced=false`；
  用户观察"好像没放置方块"。
- 根因（**规划器真实缺口，不是场景问题**）：`MiningPlanner.planDirect()` 的**模式 CURRENT 短路
  发生在悬空目标判定之前**——当前站位可达就返回 `supportPlacementPos = null`，
  v7 §2.3 的"目标下方无支撑 → 放支撑块 vs 正下方挖（按成本选）"只在"当前站位不可达"时才执行。
  结果：`CURRENT + 悬空目标` 掉落物直接掉走（本例掉进 1×1 竖井，收集阶段还得下井去捡）。
- 修复（用户裁定 A：改规划器）：`CURRENT` 分支补一句——悬空目标 + `budget.collectDrops()`
  + 当前站位**不在目标正下方** + 手上有一次性方块 → 附带 `supportPlacementPos = target.below()`
  （先放支撑再从原地挖）。
  - 当前站位就在正下方 → 属于"从下方挖"策略，不要求支撑；
  - 手上没有一次性方块 → 不强行要求（避免把"没资源"变成任务失败），维持原行为；
  - 落地目标的计划不受影响（`hasSupportBelow=true`）。

### D-078 修正 3（2026-09-09 客户端：floating_plan 期望模式写窄了）

- 复测现象（用户"我这里看他放了支撑了"）：`exec_floating=PASS ... supportPlaced=true`，
  `support=23, 64, 190` 已进计划，但 `floating_plan=FAIL mode=CURRENT`。
- 根因：**用例定义错**——`floating_plan` 只声明了期望模式 `{DIRECT}`，
  而 bot 起点就能触及悬空目标 → 规划器合法地给出 `CURRENT`（且附带支撑放置）。
- 修复：期望模式改为 `{CURRENT, DIRECT}`；支撑点断言不变（`supportPlacementPos == target.below()`）。
- 教训：规划类断言应针对**必须成立的性质**（这里是"悬空目标必须给出支撑点"），
  模式只能是"允许集合"，否则会把合法更优解判成失败。
- **客户端验收（2026-09-09 23:13，用户"通过了"）**：10/10 PASS、`ticks=117`、任务 `COMPLETED`：
  `free/wall/blocked/headroom/buried/exec_direct/exec_blocked/floating_plan/exec_floating/exec_chain` 全 PASS；
  `floating_plan=PASS mode=CURRENT/stand=21,64,190/support=23,64,190`、
  `exec_floating=PASS collected=1/1 inventoryDelta=0(期望0) dropsLeft=0 supportPlaced=true ticks=20`
  （用户确认看到 bot 在悬空方块正下方放了方块）。

## D-079：移除 JEI / JECh 构建依赖（用户裁定）+ 修复 CI

- 状态：已实施（2026-09-10）
- 用户裁定："把 JEI 和另一个 JEI 附属依赖先删除，然后我们只在客户端实例使用（我自己安装了）"。
- 实施（`build.gradle`）：
  - 删除 `compileOnly`/`runtimeOnly` 的 `mezz.jei:jei-1.20.1-forge:15.49.0.187`；
  - 删除 `clientOnly` 的 `local:jecharacters:1.20.1-forge-4.6.9`（JEI 中文/拼音搜索附属），
    以及仅供它使用的 `configurations { clientOnly }` 声明与 `runClient` 注入钩子；
  - 删除为解析 `libs/` jar 而设的 `flatDir { dir 'libs' }` 仓库；
  - 项目代码本就不 `import` 其 API，发布 jar 不含它们 → 行为零变化。
- **CI 失败根因（顺带查清）**：JEI 的编译期依赖此前由 `libs/jei-*.jar` 经 `flatDir` 满足，
  而 `libs/` 被 `.gitignore` → 干净 clone 的 `:compileJava` 必然失败（2026-09-03 起所有 CI run 均 failure）。
- 验证：移除 `libs/` 后 `./gradlew clean build --offline` **BUILD SUCCESSFUL**，产物字节数一致；
  推送后 GitHub Actions 首次 `success`（run for `4998fa1`）。
- 影响：开发机不再需要 `libs/`；JEI/JECh 只在个人客户端实例 `mods/` 安装；干净 clone 可直接构建。

## D-080：L3 目标级任务层（`Job`）立项 + 伐木作为第一消费者

- 状态：**设计已定稿（`docs/JOB_LAYER_DESIGN.md`），待实施**；用户 2026-09-10 同意方案与裁定
- 背景（用户判断，与本仓库证据一致）：现有 `MineTask` 是**低级局部任务**（单目标单发），
  决策层近乎空白（只有 78 行 `decision/AutoMineDecision`，且只被一条命令调用）；
  项目缺的不是 LLM，而是**"目标 → 子任务"这一层**。
- 立项内容：
  1. 新增 `Job implements Task`（**不扩展 `Task` 契约**，L2 已验收保持不动）；`BotSession` 零改动，
     仅需比较 `task.target()` 变化后重广播（子目标高亮跟随）；
  2. 决策缝三件套：`CandidateSource` + `SelectionPolicy`（带理由）+ `DecisionTrace`（机器可判读）；
     第一版**两个真实策略**（`Nearest` / `NearestExposed`）+ 两个真实候选源（树 / 挖矿）；
  3. `GoalSpec`（配额 + 终止 + 范围 + 硬超时）；**完成判据 = 产物入包**（原始设计 §4.2 标准 3）；
  4. 子任务复用 L2：每个原木一个 `MineTask(collectDrops=false)`，整轮一次 `CollectDropsTask`（D-070 参数）。
- 裁定（用户 2026-09-10）：
  - `Job implements Task`；
  - v1 支持 `COLLECT_ITEMS` / `HARVEST_UNITS` / `maxTicks`，`UNTIL_FULL` 延后；
  - **超出触及的高树：v1 拒绝（`trunk_too_tall`），但明确"不是最终方案"——攀爬砍树登记为未来能力**
    （`JOB_LAYER_DESIGN.md` §11-①，含它触及 D-076 红线的原因与实现所需的 6 项要素）；
  - **视线限次清障允许**：白名单保守（树叶/雪/藤/草）、**≤8 格/棵**、超预算拒绝该树、每次可 trace；
    实现上用既有的 `LineOfSightResult.getFirstBlocker()`，**不自制找树叶逻辑**；
  - 半途目标被替换/消失 → 跳过该树继续；配额未达成且无候选 → `FAILED no_reachable_candidate`。
- 永久删除（旧伐木的六份重复）：`MAX_CLEAR_DEPTH` / `findLeafBlocker`（树叶不主动清理，原版自然衰减）、
  自制 `moveTowards`、每棵等 40 tick 的收集、两套 `Phase` 状态机与记账。
- L3 验收标准升级（新增三类断言）：**决策可判读**（候选/选中/理由/被拒理由码）、
  **不变量**（原木增量 ≥ 该树原木数、`dropsLeft=0`、未破坏非目标方块）、
  **终止与恢复**（配额达成 / 全候选被拒 / 背包满 / 硬超时 / 半途目标变化，五条路径各自有场景且都不空转）。
- 实施切片 J1–J5（J1 = 决策缝 + 砍一棵的最小闭环，入口 `alice:lumber_job` + 场景 `lumber_course`）。
- 顺序说明：**风险系统与维生系统的实施排在本设计之后**——`RiskProfile` 的第一个真实消费者 =
  Job 的候选筛选（"这棵树值不值得去"）；维生"去向" = Job 终止后的一次 `WalkToTask`。

## D-081：世界修改账本 + "建拆同权" + `MAINTAIN` 型 Job（跨能力原则）

- 状态：**已裁定（2026-09-10 二次讨论），待实施（切片 J6）**
- 起因：用户指出可持续伐木区**不得有树木/柱子残留**，但世界改造（攀爬垫脚）必然留下柱子 →
  需要"在自己修改后恢复自己的修改"，并问该逻辑放在哪一层。
- **原则（用户裁定：接受）——授权即配对（建拆同权）**：
  > 任何被授权**放置**的临时方块，必须在同一授权内被**移除**；禁止"只能建、不能拆"的授权。
  > 推论：**不能保证拆掉柱子的 bot，就无权获得放置柱子的授权。**
  这把"区域内不得残留"从"靠自觉"变成**授权本身内建的不变量**。
- **三层分工 + 一个持久化账本（用户裁定：账本持久化）**：
  | 责任 | 归属 | 理由 |
  |---|---|---|
  | 记录（改了什么 + 原状态） | **动作层自动写账本** | 只有动作层看得见每一次修改，**含内核 `PILLAR` 放的方块**；靠调用方自觉记录必然重演"六份重复" |
  | 意图与策略（`TEMP`/`KEEP`/`RESTORE_BY_SCOPE`） | **L3 Job** | 只有目标层知道"这根柱子是为了够到那棵树"；"区域内不许残留"是目标语义 |
  | 执行恢复 | **L2 子任务**（`RestoreScopeTask`，复用 `MineTask` + `DOWNWARD`） | 复用已验收能力，Job 只调度 |
  | 持久化 | **`WorldModLedger`（`SavedData`）**，参照 `TransferLedgerData` 的挂起-恢复 | 崩溃/被替换/重启会留下脚手架，必须能续做清理 |
- 账本条目：`(pos, placedState, previousState, kind, scopeId, owner, policy, tick)`。
  **记 `previousState`** 是精确恢复原状的前提（往雪/草里放置时原状态非空气），也是"绝不拆非我方方块"的依据
  （拆除前比对 `placedState`，不匹配即放弃并告警）。
- **生命周期约束（消灭够不到的残留）**：
  `建脚手架 → 攀爬 → 使用 → **仍在顶上时自上而下拆除** → 才允许离开`；
  **脚手架生命周期 = 一次使用会话**，不允许"先走开以后再回来拆"（从地面拆高层必留悬空残块）。
  拆除机制 = 挖脚下 → `DOWNWARD` 落 1 格 → 重复（`DOWNWARD` 已于 D-048/D-050 客户端验收），
  与攀爬**共用同一份授权**，不额外发放。
- 安全四条：落点安全检查（不落岩浆/虚空）、严格自上而下、只拆我方 `TEMP` 且 `placedState` 匹配、
  拆除消耗计入同一预算与 trace（日志必须看得见"建 5 拆 5"）。
- **顺序结论（用户认可）**：**攀爬砍树（`JOB_LAYER_DESIGN.md` §11-①）必须排在账本+恢复（J6）之后**——
  恢复机制不存在时不得发放放置授权；切片顺序为 J1–J5 → **J6（账本+建拆同权+恢复）** → J7 攀爬 → J8 区域型。
- **`MAINTAIN` 型 Job（用户裁定：可持续伐木区做成持续型）**：
  - 不追求"跑完即结束"，而是**持续维持区域不变量**（有成熟树→砍；欠树→补种；满足→巡查待机）；
  - 停止只由玩家命令触发；连续 N 次巡查无进展且区域无树无苗 → `IDLE_NO_WORK`（如实待机，不算失败）；
  - **必须有定期健康输出**（`[Job] maintain` 摘要），否则常驻任务会变成黑箱；
  - 区域状态持久化 `LumberRegionState`（`SavedData`）：区域定义、**我种下的树苗位置**、待补位置、
    上次巡查 tick —— 必须记得"哪些苗是我种的"，否则算不出"欠几棵"。
  - 两类伐木（漫游型 / 区域型）在旧代码是 **531 + 505 两套任务**，新设计是**一个 Job + 不同 `GoalSpec`/策略**。
- 否决过的方案：❌ 放 L1 内核自动清理（内核不知意图，会拆掉"作为返程手段"的合法柱子，违反 D-076 分层）；
  ❌ 只放 Job 内存（跨任务/跨重启丢失，每个新能力都要重写记录逻辑）。
- 可断言不变量：会话结束时该 scope 在账本中为空；场景快照无 bot 残留方块。
- **追加裁定（2026-09-10，清障分类）**：用户明确"软方块清障如果之后问题太多，干脆直接删了，
  都当成有碰撞箱的方块" → 已实施：**删除 `SoftBlockPolicy` 的软/硬分类**，改为统一规则
  "任何**可破坏**且**非原木**的阻挡方块都可清障，上限由预算（≤8 格/棵）兜底"，
  运行时仅剩两种无解：预算用尽、阻挡物不可破坏。
  动机（实测证据）：橡树第 1 格原木四周是空气，但**相邻格头位被树叶占**（树叶有碰撞）→
  旧诊断找不到"可站立观察位"就判 hard → 整树被拒；按统一规则它只需**清 4 格树叶**即可砍。
  另核查：**工具等级成本无需改动**——寻路三处破坏成本（`BREAK_AND_ENTER`/`DOWNWARD`/`BREAK_AND_TRAVERSE`）
  已调用 `BlockInteraction.estimateBreakTicks`（按快捷栏最佳工具 + `getDestroySpeed` + 不能收获时 ×5），
  与 `MiningBudget` 同源；实测砍原木 60 tick/根与该估算**完全吻合**（镐 speed 1.0）。

---

## D-082 世界写入授权契约：授权成为数据（2026-09-10）

**背景**：用户追问"为什么不能直接在伐木任务声明授权"。核查结论——伐木**当时已经在任务层声明授权**
（`LumberJob` 自己挑出要清的那一格再建 `MineTask`），真正的病灶是**授权的表达方式分裂**：

1. **策略由"调哪个方法"隐式决定**：调 `BlockInteraction.breakable` 走清障策略，
   调 `breakableExplicit` 走明确目标策略——同一份授权语义藏在两个方法名里，调用点看不出"谁授权了什么"。
2. **归因缺失**：`PathRequest` 有 `requester` 字段，但三个工厂全部硬编码 `"unknown"`（`PathRequest.java:39/51/63`），
   全仓唯一读取点只有 `PathRetryRunner.java:53` 的字符串拼接。
3. **写入点分散**：破坏有 5 个入口（`beginBreak` / `breakForBulkEdit` / `placeAt` / **裸 `level.setBlock`** / 模组反射），
   其中道路施工两套实现（`RoadBuildTask`、`RoadBuilder`）**零凭证**：不查保护区、不查库存、无理由、无 requester。
4. **执行期不复验授权**：MovementType 许可只在搜索期（`SurfaceMovementProvider`）生效；
   `PathSession` 保存 `request` 后不再读取，`startSegment()` 直接按 plan 建执行器。

**裁定：授权 = (谁, 为什么) 的数据，而不是任务级开关。实施三件套（`action/` 包）：**

- `WriteReason`（枚举）：10 个结构化理由，各自携带
  ① 策略 `Policy{EXPLICIT_TARGET, CLEARING}`（决定走 `BlockBreakSafety` 哪套拒绝规则）、
  ② 动作 `Action{BREAK, PLACE, BOTH}`；
- `WriteGrant`（record）：`requester + reason`。**不含预算**——预算是既有的独立概念
  （`MiningBudget` / `SearchBudget`），本轮**不合并**，避免造出第三套预算表达；
- `WriteAudit`：每次写入记一条（`[WRITE] break pos=.. block=.. by=lumber:LINE_OF_SIGHT tick=..`）
  + 计数 + `unknownRequesterWrites()` 缺口度量。**这是 J6 账本的唯一数据来源**，本轮只做内存 + 日志。

**唯一入口**：`BlockInteraction` 的全部破坏/放置/批量编辑签名改为**必须携带 `WriteGrant`**；
`breakableExplicit` **删除**（策略改由 `grant.reason().policy()` 派生）；新增
`placeBulkEdit(...)` 封掉道路施工的裸 `level.setBlock`（自做保护区检查 + 审计，被拒即不写入）。

**为什么不能用任务级布尔开关**（用户方案的另一半，已讨论并否决）：
① 谓词必须能被下层判定——寻路内核只算几何与成本，无法区分"树叶/玩家房子/矿床"，给它通行证等于**免检**；
② 授权范围里会塞进与任务无关的能力（`withWorldModification` 含 `PILLAR`/`FALL`/`DOWNWARD`/`PLACE_STEP`）；
③ 预算无法归因，"这棵树花掉多少破坏预算"算不出来，J6 的建拆同权与恢复就没有依据；
④ 失败归因塌陷：`clear_failed`/`no_stand`/`clear_budget`/`MOVE_MOVEMENT_FAILED` 会全部变成一句 `unreachable`；
⑤ **实测证据**：9-10 那次给过内核通用破坏权（`standableOnly` 之前），结果是
`planned standingFoot=20,63,207 mode=TUNNEL` → **往下挖了一格 + 6 秒地道** + `MOVE_MOVEMENT_FAILED`。
规则因此明确为：**每个授权入口必须携带下层可判定的谓词 + 可归因的预算；任务可以声明，但只能声明"这一格/这一次"。**

**新增任务身份**：`Task.taskName()` 默认取类名（Job 覆写为 `lumber` 等），作为授权的 `requester`。

**本轮不做（已登记为后续，见 `docs/WORLD_WRITE_AUTHORIZATION.md` §4）**：
`PathRequest.requester` 全量填值（21 处）、执行期复验 `allowedMovementTypes`、
模组连锁破坏的凭证化（`ChainMining` 反射内部不可插入判定）、容器写入维度（`TransferTask`）、J6 持久化账本。

---

## D-083 清障目标必须"自己也能被清掉"：可规划即可执行（2026-09-10）

**现象（第二次客户端实测，jar `3cd606f7`）**：`/function alice_test:lumber_course` → 右键 `alice:lumber_job`，
4 根原木全部失败，terminal `FAILED partial_tree 0/4 ticks=11`，`writes breaks=0 unknown=0`（D-082 审计链路正常）。

```
[Job] phase=CLEAR target=19,65,207  为 20,64,208 …   → [MiningPlanner] standable_only reason=no_valid_standing_point
[Job] phase=CLEAR target=19,65,207  为 20,65,208 …   → 同上
[Job] phase=CLEAR target=19,67,208  为 20,66,208 …   → reason=no_reachable_standing_point
[Job] phase=CLEAR target=20,68,208  为 20,67,208 …   → reason=no_reachable_standing_point
```

**根因（用夹具真实方块逐格复现，非推断）**：橡树 4 根原木在 `y=64..67`（x=20,z=208），
树冠是 **y=65..68 的 5×5 叶团**（y=64 那层除原木本身全是空气）。

`nextClearStep` 取"观察位 → 原木"射线上**第一块**阻挡，第一版修正只保证"该块可从当前观察位被看到"，
但**没保证该块自己能被打到**：

- 树冠 y=65 是 5×5 **实心叶团**，射线从观察位（如 `18,64,206`）进入树团时的第一块是 `19,65,207`；
- 而 `19,65,207` 的四个横向邻居 `(18/20,65,207)`、`(19,65,206/208)` **与上方 `(19,66,207)` 全是树叶**
  → 从任何现成可站位置到它的连线必经过相邻树叶 → `generateCandidates` 候选集为空
  → `no_valid_standing_point` → 清障子任务 2 tick 内失败；
- `19,67,208` / `20,68,208` 属另一类：候选**存在**但**路径不可达**（要爬上树冠 = J7 攀爬，未实现）
  → `no_reachable_standing_point`（**正确且诚实的失败**，不是 bug）。

**裁定（修正）**：`nextClearStep` 返回的格子必须通过**清障子任务将要使用的同一判据**
`StandingPointSelector.isValidStandingPoint(level, blocker, stand, reach)`，且从**同一个观察位**出发计算。
即"**可规划即可执行**"从夹具要求上升为**生产代码不变量**。
同时删除上一轮误加的 `canWalkOn(level, stand.below())` 检查——它要求"站位下方那格还另有支撑"，
在 1 格厚平台/浮空岛上会否掉全部合法站位。

**预期结果（离线按真实方块手算）**：橡树只需清 **1~2 片树叶**即可砍完全部 4 根，
`cleared≈1~2`、`inventoryDelta=4`、`writes breaks=1~2 unknown=0`。

---

## D-084 掉落物落定判据：只等"下落"漏掉了原版上抛阶段（2026-09-10）

**现象（第三次客户端实测，jar `6806229a`）**：橡树**砍完了**——4/4 根原木、3 次清障、
`writes breaks=7 unknown=0`（= 3 清障 + 4 原木，授权链路完全正确）——但终态仍是
`FAILED product_not_collected`、`inventoryDelta=3`（少 1 根）。

```
20:08:55.084  block_break_done pos=20,67,208          ← 最后一根原木破块
20:08:55.085  作用域捕捉掉落物: oak_log x20 y67 z208  ← 掉落物生成
20:08:55.237  [CollectDrops] sweep_start anchor=20,67,208 feet=20,64,208 worldMod=false
20:08:55.240  [PathingStats] descend_precondition=1308 status=UNREACHABLE goal=20,67,208
20:08:55.240  [CollectDrops] retire item=... reason=MOVEMENT_FAILED itemY=67.750
```

**根因**：`CollectDropsTask.isFalling(item)` 的判据是 `!onGround() && dy < -0.02`（只认"正在下落"）。
但原版 `Block.popResource` 生成的 `ItemEntity` **带向上初速度**，破块后最初几 tick `dy > 0`
→ 判据为假 → 落定闸门不生效 → 收集器立刻去追那一格**空中位置** → `UNREACHABLE`
（要爬上树冠）→ `MOVEMENT_FAILED` → 物品被**退役**。物品本会落到 bot 脚下（`20,64,208` 正是
被掏空的树干底格，bot 就站在那里）自然被拾取。

**裁定**：判据改为 `!item.onGround()`（改名 `isAirborne`），**"只要还没接地就等它落定"**，
上抛与下落一并覆盖；上限仍是 `MAX_SETTLE_TICKS=40`。已接地但位置很高的物品不受影响——
它仍走正常走位，确实够不到时如实 `unreachable` 退休。

**同时完成的归因闭合（R2a，见 `docs/WORLD_WRITE_AUTHORIZATION.md` G1）**：
`PathRequest` 三个工厂改为**必填 requester**（无 3 参重载）；`LiveExecutionContext` 改为必填
第 7 组件并**删除 5/6 参兼容构造器**；`PathSession.startSegment` 传入 `request.requester()`。
此前内核写入（P1–P5）即使请求带了 requester，也会在 `PathSession` 被替换成 `UNKNOWN`——
即"归因只到搜索期、到不了执行期"。5 个诊断任务的自建请求统一改用 `base.requester()`（一条请求链一个身份）。

---

## D-085 执行期复验授权 + 批量破坏闸门 + 清障预算按棵计（R2b，2026-09-10）

**背景**：J1 伐木切片已在客户端通过（`DONE quota_met logs 4/4 trees 1/1 cleared=3
inventoryDelta=4 writes breaks=7 unknown=0`）。用户裁定**先走 R2 再走 J2**，理由是红线不变式
必须在扩大接触面之前关上。落地三件事：

### ① 执行期复验授权（闭合 G2）
`PathSession.startSegment` 此前**只保存** `PathRequest`，建执行器时直接按 plan 里的
`movementType` 走，从不核对 `request.allowedMovementTypes` —— 授权**只在搜索期**生效。
后果：一个 plan 只要活着，就能执行调用方**从未授权**的 Movement（D-076 禁止的"隐式授权"）。
风险不是理论：`CollectDropsTask` 在 `allowWorldModification=false` 时用纯净的 `PathRequest.of`，
而 `MineBlockRunner` 会按 `plan.mode()` **另建**请求 → plan 比请求活得久就会越权。

修正：每段执行前 `if (!request.allows(movement.movementType()))` → 记
`UNAUTHORIZED_MOVEMENT` 并**拒绝执行**（走 `mapFailure` 而非 `handleFailure`，
因为授权违规不应因"bot 在空中"被延后）。

### ② 批量破坏的闸门收进原语（闭合 G9）
`breakForBulkEdit` 原先只审计、把拒绝判定"外推给调用方"，而勘测证实 `RoadBuildTask.forceBreak`
**根本没有保护区检查**（只判 `getDestroyProgress > 0`）→ 道路施工可在保护区内破坏。
修正：闸门移入方法内（按 `grant.reason()` 派生策略；`BULK_EDIT` → 明确目标策略 =
保护区/不可破坏/流体拒绝），返回值改为 `true=已破坏 / false=被拒未写入`，
`forceBreak` 改为如实失败（`road_block_refused_<pos>`），不再"假装破坏成功"。

### ③ 清障预算按"棵"计（J2 前置缺陷，勘测员 06 §0.2，已只读复核为真）
`LumberJob` 原只有一个 **job 级** `clearedBlocks`，却拿去比 **per-tree** 限额
`MAX_CLEAR_PER_TREE=8` 且**换树不重置** —— 单树测试永远暴露不了；一进多树循环（J2），
累计清 8 格后**后面每棵树都会 `clear_budget` 失败**。
修正：拆成 `clearedThisTree`（**闸门**，在 `select()` 换树时归零，符合 D-080「≤8 格/棵」）
与 `clearedTotal`（**仅报告**）。这是 J2 能跑通的前提。

**验证等级**：COMPILES。三处均为行为变更，需回归验证：
`alice:mine_regression`（原 10/10）+ `alice:pathing_regression` + 伐木场景。

---

## D-086 回归假失败的两个根因：夹具测量时机 + 收集器 0 步计划空转（2026-09-10）

**现象**：`alice:mine_regression` 第 1 次跑 `→ FAIL`（`exec_direct` / `exec_floating`），
第 2、3 次跑 **10/10 PASS**。用户初判"场景没重置"。逐行核对后：**两个不同根因，只有一个与"状态残留"有关，
且残留的对象不是地形而是 bot 背包**。

`prepare()` 每个用例都会重放地形 + 复位起点，**地形确实会重置**。

### 根因 A：夹具在 `MineTask` 构造**之前**采集背包基线（假失败）
```java
inventoryBefore = countInInventory(expectedItem);          // ← 此刻选中槽躺着 19 个圆石
mineTask = new MineTask(...);                              // ← 构造里 "夹具补镐" 覆盖选中槽
ensureCobblestone();                                       // 只填空格，救不回被顶掉的
int delta = countInInventory(expectedItem) - inventoryBefore;   // 1 - 19 = -18
```
`exec_direct` 其余判据全部 PASS（`collected=1/1 targetGone=true dropsLeft=0`），
唯一失败是 `inventoryDelta=-18`。触发条件是**状态依赖**的：只有当选中槽恰好放着一叠
"预期掉落物"（跨轮次残留）时才发生；第 2、3 次跑时选中槽已是镐，所以不复现。
→ **修正**：基线移到 `new MineTask(...)` **之后**采集。

### 根因 B：收集器"0 步计划"导致每 tick 重建同一请求（**生产 bug**）
```
sweep_start anchor=23,65,190 feet=23,65,190
[PathRetry] planned status=REACHED movements=0 cost=0.00 from=23,65,190 to=23,65,190
… 重复 201 tick …
retire item=… reason=cluster_budget itemPos=24,64,189 inRange=false
```
掉落物从浮空平台掉到相邻下方 `(24,64,189)`，而 `anchor` 仍是它的出生格
（= 被挖掉的那格 = bot 自己站的位置）→ 请求退化成"从自己走自己" → `movements=0 / REACHED`。
`CollectDropsTask` 在 `DONE` 分支**提前 return**，走不到下面既有的 `reanchor` 逻辑
（按物品**当前**位置重锚点）→ 空转到 `CLUSTER_BUDGET_TICKS=200` 才 `cluster_budget` 放弃。
→ **修正**：`DONE` 时不再提前 return，落到既有的"到位判定"：
在拾取范围内就等；不在就 `reanchor` 到物品当前位置（重锚次数用尽才如实 `not_in_pickup_range` 退休）。

**为什么必须修 B**：J2 多树砍伐会产生更多掉落物，任何一个落到平台边缘或低一格，
收集器就会空转 200 tick 后放弃 → Job 判 `product_not_collected` ——
与"少收集一根原木"是**同一个终态但完全不同的原因**，会让 J2 的失败信号无法判读。

**验证等级**：COMPILES。待 `alice:mine_regression`（应首次即 10/10）+ 伐木场景复验。

---

## D-087 J2：伐木 Job 循环 + 配额 + 终止语义（2026-09-10）

**背景**：J1（单棵闭环）已在客户端通过。J2 = 「循环 + 配额 + `GoalProgress` + 终止语义」
（`JOB_LAYER_DESIGN.md` §6.2c 五条终止路径 + §6.2b 不变量），完成判据 `DONE quota_met`。

### 实现
- **循环**：`collectPhase` 结算一棵树后不再直接终止，改为回到 `SELECT`；`NEXT` 决策行打印进度。
- **不重复砍同一棵（§6.2b）**：`attempted` 集合记录**已尝试过**的树基座（成功或失败都算）。
  半成品树若被反复重选会死循环——这是循环的**硬不变量**，不是优化。
  过滤掉的候选写进 `rejected`（`<anchor>:already_attempted`），满足 §6.2a「拒绝必须带理由码」。
- **逐树记账**：新增 `logsBeforeThisTree`（逐树完成判据的基线）；`logsBefore` 降级为 job 级总报告。
- **报告**：`progressSummary` = `trees <done>/<quota> logs <choppedTotal>/<plannedTotal> cleared=<total>`（monotone）。
- **终止路径**：① `quota_met`；② 一棵没成 → `no_reachable_candidate` + 理由集；
  ③ 背包放不下 → `inventory_full`（新增 `hasRoomForLogs()`，不空转）；④ `goal_timeout`（已有）；
  ⑤ 有产出但配额未达 → `partial_quota` + 逐树失败清单。
- **配额**：物品默认值 **1 → 2 棵**，上限 1200 → 2400 tick（`BotManager.assignLumberJob`）。

### 夹具：为 J2 增加第二棵「可砍完」的树
原场景只有 2 棵可行树，其中 7 格云杉的**顶格 y=70 触及 4.46 > 4.1**
（`deferred` 覆盖到 y=69，y=70 只能站到树冠顶上才看得见 —— 那是 J7 攀爬的事），
所以配额 2 无法由「橡树 + 云杉」达成。

新增 `lumber_course_trees.mcfunction`：**把已验证可行的那棵真橡树按方块原样平移复制**到
`29,64,215`（61 块，trunk+canopy 全部取自 `capture-scene.py` 抓取的真实存档，
**不是手搓几何**）。约束：足迹完全落在 `lumber_course_terrain` 的清空盒 `[17..31]×[203..231]` 内
（否则重跑场景会残留），与既有树净空 7 格，距起点 10.0 格（在 Job 扫描半径 16 内）。
离线分析器确认新场景 = 4 棵树 / 3 棵可行（两棵橡树 + 云杉）/ 高大云杉仍被拒。

**副作用（正面）**：两棵橡树**各需清障**（0 可见 / 2 掏空 / 4 需清障，同型），
于是这个场景成为 `clearedThisTree` 按棵重置（D-085③）的**真实回归**——
若预算没按棵重置，第二棵橡树会在累计 8 格后 `clear_budget` 失败。

**验证等级**：COMPILES。待客户端实测。

---

## D-088 走位时仰着头：视线是"瞬时"还是"永久"（2026-09-10）

**现象（用户客户端实测）**：bot 在**换树的长距离走位**中**整段仰着头**，走到站位才恢复。
只在这种长距离走位明显；挖完单根原木后的小走位不易察觉。

**根因（代码事实）**：`BlockBreakSession:99` 在开始破坏时调 `BlockInteraction.faceBlock`
把视线对准被挖方块；伐木自下而上、越挖越高 → 俯仰角为**仰视**。
而 `BlockInteraction.faceTowards`（`:81-92`）是**永久写入** `setYRot/setXRot`，
`PathSession` 与 `MineBlockRunner` **都不碰俯仰角** → 破坏结束后那段走位一路沿用仰视角。

**这是对 Baritone 的一处未登记偏离**：Baritone `behavior/LookBehavior.java:96-125` 把"看向某处"
当作**逐 tick 瞬时**行为——`PRE` 写入目标旋转，`POST` 用 `prevRotation` **恢复**玩家原旋转
（`Target.Mode.SERVER` 分支）。所以 Baritone 走路时不会被上一次交互的俯仰角粘住。

**已实施（A，最小改动）**：`PathSession.startSegment` 在每段起点把 `xRot` 归零。
安全性依据：需要特定视线的 Movement 会**自行设置**——`FallExecution`/`PillarExecution`
有 `faceTowards`；破坏/放置在动作层 `faceBlock`/`placeAt` 设置（逐个核对过其余 Movement 为 0 处）。

**已登记待办（B，用户裁定"先 A 后 B"）**：对齐 Baritone 完整形态 =
「交互期临时写入 + 结束恢复 `prevRotation`」，即给 `BlockBreakSession` 加保存/恢复
（约 8 个调用点：`MineBlockRunner` + 5 个执行器 + legacy movement）。
B 完成后本处的归零可以撤掉——归零是"掩盖症状"，恢复才是"消除症状"。

**验证等级**：COMPILES。待客户端目视确认（走位时是否平视）。

## D-089 伐木工具：斧子必须进"快捷栏"，不是"背包里"（2026-09-10）

**现象（用户实测）**：bot 砍树时**还在用镐子**。

**客观验证（日志破坏耗时）**：
```
block_break_done pos=28,65,208 ticks=61   ← 2.0(硬度)×1.5÷1.0×20 = 速度 1.0 = 镐/空手
block_break_done pos=27,65,214 ticks=6    ← 树叶 0.2×1.5÷1.0×20，同为速度 1.0
```
斧子对原木是速度 8 → 应为 ~8 tick。**61 tick 反推有效速度 1.0，等于斧子完全没生效。**

**根因**：工具选择 `BlockInteraction.findBestToolSlot` 只扫**快捷栏 0..8**
（与 Baritone `MovementHelper.switchToBestToolFor` 同范围），而
`LumberJobItem.ensureAxe` 在**快捷栏 9 格全满**时退化到 `inventory.add(...)` →
斧子落进**主背包（9..35）**，永远选不到。bot 跑过多轮回归后快捷栏被圆石等塞满，
所以这条缺陷**只在"跑了一阵之后"出现**，首测时不会暴露。

**修正**：`ensureAxe` 保证斧子**一定进快捷栏**——① 已有则沿用；② 有空格则放入；
③ 全满则把"对木材也无用（速度 ≤1 且非斧）"的一格挪进主背包再放斧；④ 全无可能时告警而非静默。
每步都打 `[LumberJobItem] axe ...` 日志，使"斧子在不在快捷栏"以后一眼可判。

**顺带核实（未改代码）**：`MineTask.ensureTool` 不会顶掉斧子——它的第二个条件要求
钻石镐**严格更优**（`pickaxe.getDestroySpeed(state) > main.getDestroySpeed(state)`），
而斧子对原木（8.0）与树叶都不低于镐，故安全。**不做无谓改动**。

**验证等级**：COMPILES。判据：`block_break_done … ticks≈8`（原木）。

## D-090 手持物品客户端同步：改 `selected` 必须广播 MAINHAND（2026-09-10）

**现象（用户实测）**：bot **看起来拿着镐子，砍树却很快**；重进存档后显示才更新。

**根因**：`BotManager.syncMainHand`（`:186-202`）是既有的修复——**直接广播
`ClientboundSetEquipmentPacket`**，且刻意绕开 FakeConnection（FakeConnection 会丢弃装备包，
以免搞乱真实玩家的快捷栏）。但 `BlockInteraction.switchToBestToolFor`（`:114-118`）与
`placeAt`（`:200`）改完 `inventory.selected` **从不调用它** →
服务端按斧子算破坏速度（`ticks=8`，快），客户端仍渲染旧物品（镐）→ 现象完全吻合。

**修正**：三处改 `selected` 的地方统一改为"变了才改 + 立刻 `syncMainHand`"：
`BlockInteraction.switchToBestToolFor`、`BlockInteraction.placeAt`、`PathingBreakerItem.ensureStonePickaxe`。

---

### 裁定：工具「确认 + 更换」系统**明确推迟**（用户裁定，2026-09-10）

用户原话：「当前的更换工具操作是测试时代码，以后需要在挖掘前做一次工具确认和更换，
砍树换斧子，挖石头换镐子，不过这个系统还需要讨论一下设计……这个小系统以后往后面排，
因为对于现在测试没必要还可能影响测试」。

**现状定性（重要，三者性质不同）**：

| 代码 | 性质 | 内容 |
|---|---|---|
| `BlockInteraction.switchToBestToolFor` | **生产** | 破坏开始时"在快捷栏挑速度最高的"——反应式一行，无工具就空手 |
| `MineTask.ensureTool` | **夹具**（源码注释即写"开发夹具"） | 往**选中槽**补钻石镐 |
| `LumberJobItem.ensureAxe` / `PathingBreakerItem.ensureStonePickaxe` | **夹具** | 测试物品保证快捷栏有对应工具 |

即：**"工具从哪来 / 值不值得换 / 耐久成本"目前只有夹具在管**，生产侧仅一个反应式挑选。

**已登记的设计问题（推迟到 J 主线之后讨论）**：
1. **"最快"还是"够快"**：树叶用剪刀最快（用户举例，若记错则当假想例子），
   但空手速度已足够且**不消耗任何耐久** → 判据应是"够快且代价最低"，需要一条成本/耐久规则；
2. **最佳工具表由谁定义**：`mineable/*` 标签派生，还是硬编码表（后者对模组目标不友好）；
3. **确认时机**：挖掘前一次（用户建议）还是每次破坏会话开始（现状）；
4. **工具不存在时怎么办**：补一把（夹具行为）/ 如实失败 / 去打一把（牵扯背包与合成，属后期）；
5. **快捷栏满**：`ensureAxe` 的"挪走最无用一格"是夹具解法，生产语义待定
   （`PathingBreakerItem` 里 `Math.min(slot, 8)` 在工具落主背包时会指向错误物品，同属这一类缺陷）。

## D-091 主手语义：`selected` 派生，不设"固定第一格"（2026-09-10）

### 纰漏
用户提问："以前设计 bot 背包时把快捷栏第一格定义为主手格，这一格是什么 bot 手上就是什么，
还专门用边框注释标出来了，现在还是这样吗？"

核查结论：**不是了，而且代码里同时存在两种互相矛盾的表述**：

| 位置 | 说的是 |
|---|---|
| `BotInventoryMenuScreen:69-71` | `// 高亮 bot 的主手槽（hotbar 第一格，固定位置）` / `// Bot 的主手槽永远是 inventory index 0` |
| `BotInventorySnapshot` javadoc | `The main hand is the ordinary {@code selected} slot (0..8) and is not duplicated.` |
| 全部运行代码（`syncMainHand`、`MineTask`、`switchToBestToolFor`、`placeAt`、登录恢复） | 按 vanilla **`selected`** |

GUI 之所以硬编码，是因为 `BotInventorySnapshot` 里**没有 `selected` 字段**——客户端无从得知选中哪一格。
语义搬到 `selected` 之后 GUI 没跟着改，于是它**高亮错的格**（例如斧子被选在 slot 1，仍高亮 slot 0）。

### 设计讨论（用户提出"要不要让玩家手动固定主手物品"）
来源是**车万女仆模组**的"独立主手格"（装备什么就用什么，不手动换手持）。
关键判断：**那是"实体（entity）"的表达方式**——对生物而言 `EquipmentSlot.MAINHAND` 就是"手上拿什么"
的唯一机制，独立主手格是**架构后果**，不是设计偏好。而 Alice 的 bot 是**假 `ServerPlayer`**：
vanilla 里 MAINHAND **由 `inventory.selected` 派生**（`syncMainHand` 广播的正是这个派生值）。
所以"给假玩家一个独立主手格"在实现上**等价于**"把 `selected` 钉死在 0 并每次换工具搬动物品栈" ——
而搬运正是本项目两次翻车的形态：D-086（补镐顶掉一叠 19 个圆石 → `inventoryDelta=-18` 假失败）、
D-089（快捷栏满，斧子退化进主背包 → 选不到 → 全程用镐）。

**裁定**：主手语义保留 vanilla `selected`。玩家的控制需求用**偏好/白名单**表达
（"只在斧类里换" / "优先用槽 N 的这把"），归入已推迟的工具系统（D-090）。
若将来出现"必须锁死"的硬需求（模组工具带充能/绑定），正确形态是在 `selected` **之上加一层 lock**，
**而不是换掉槽位语义** —— 这样"装备什么就用什么"的保证拿到了，而渲染/`useItemOn`/破坏速度/
登录恢复仍共用同一个真相。

### 已实施（纯客户端展示 + 一次下发，不动 bot 行为）
1. `BotInventorySnapshot` + `BotInventoryPacket` 携带 `selected`（含编码/解码，构造器校验 0..8）；
2. `BotInventoryService` 填充 `selected`，并在 `open()` 时**也下发一次快照**
   （此前只在动作后下发，菜单界面从打开到第一次动作之间没有选中槽信息）；
3. 两个界面都改为按 `selected` 高亮：自绘 `BotInventoryScreen.renderBotHotbar` 新增金框 + "主手"标注，
   `BotInventoryMenuScreen` 删掉硬编码 `32` 与旧注释，改用 `BOT_HOTBAR_START + selected`；
4. `BotInventoryMenu.BOT_HOTBAR_START` 公开为**菜单索引 ↔ 背包索引映射的唯一来源**；
5. 顺带修一处既有不一致：回推快照时 `openScreen()` **无条件重开界面** → 两个 GUI 互相替换；
   改为 `openScreenIfNone()`（已开着就不重开）。

**已知限制**：界面上的金框新鲜度 = 最近一次快照（打开时 / 每次动作）。打开期间 bot 若自行换工具，
需要重开或触发一次动作才刷新。够用，暂不为实时刷新引入推送。

**验证等级**：COMPILES。待客户端目视确认（打开 bot 背包 → 金框应在**真实主手**那格）。

## D-092 J3：策略可替换性——先修 `exposed` 语义，再接第二个策略（2026-09-10）

### 发现：`exposed` 的原定义对真实树**没有区分力**
原定义（`LumberCandidateSource`）= "树顶原木正上方那一列无遮挡"。把当前场景三棵树逐个算：

```
树(20,64,208) 顶 y=67 → y68=oak_leaves        ⇒ exposed=false
树(28,64,208) 顶 y=70 → y71/y72=spruce_leaves ⇒ exposed=false
树(29,64,215) 顶 y=67 → y68=oak_leaves        ⇒ exposed=false
```

**3/3 全 false，而且不是地形偶然**：自然树的树冠本来就会盖在顶格上方（云杉的叶尖甚至高出顶格 2 格）。
后果：`NearestExposedPolicy` 每次都回退到"最近"，与 `NearestPolicy` **永远给出相同选择**，
`JOB_LAYER_DESIGN.md` §6.3「两策略必须给出不同且都可解释的选择」**根本无法成立**。
即：**不是 J3 写不出来，是度量的东西选错了。**

### 裁定：`exposed` 重定义为「至少有一根原木当前可见（无需清障即可下手）」
理由是它**对伐木真正有意义**——决定"先砍哪棵最省事"，而场景里立刻产生分歧：

| 树 | `visible` | 新 `exposed` | 含义 |
|---|---|---|---|
| 橡树（×3，同型） | 0 | **false** | 树冠盖住基部 → 必须清障才能下手 |
| 云杉 | 3 | **true** | 基部裸露 → 可直接砍 |

旧定义**不删**，降级为独立特征 `open_sky`（保留观测价值，不再驱动策略）；
同时新增 `visible` 特征（可见原木数），使 `[Job] select` 与自检日志可直接判读。

### 实现
1. `LumberCandidateSource`：`features(...)` 增参 `visibleLogs`；新增 `visible`、`open_sky`；
   `exposed = visibleLogs > 0`（`visibleLogs = logCount - unreachable`）。
2. 新增 `alice:lumber_policy_check`（零参数、右键）：**只规划不执行**（复用挖掘回归 PLAN 型做法，
   无副作用、不干扰其它测试）——同一候选集跑两个策略，打印候选特征、两侧选择理由与拒绝列表，
   并给出机器可读结论：
   ```
   [PolicyCheck] SUMMARY nearest=… exposed=… differ=… explainable=… exposedHonest=… → PASS|FAIL
   ```
   PASS 三条判据：① 两侧选中不同（§6.3）；② 两侧理由非空**且拒绝列表都带理由码**（§6.2a）；
   ③ 暴露策略名副其实——选中项 `exposed=true`，或**如实回退**并写明 `fallback=no_exposed`。

### 夹具：把「按棵预算重置」变成**真回归**（补上 D-085③ 的诚实性欠账）
原 J2 场景累计清障只有 6 格（< `MAX_CLEAR_PER_TREE=8`），job 级计数器也能通过——
即那条修复当时**没有被真正验证**。现再复制**两棵**同型真橡树
（`lumber_course_trees.mcfunction`：`19,64,213`、`29,64,213`，均由 `capture-scene.py` 抓取的真实树
按方块原样平移），使场景成为 **3 棵同型橡树（各 3 格清障）**：
配额提到 **3 棵** ⇒ 累计清障 **9 格 > 8** ⇒ **若预算不是按棵重置，第 3 棵必在清障时 `clear_budget` 失败**。

**验证等级**：COMPILES（离线分析器：5 棵树 / 4 棵可行 = 3 橡树 + 云杉 / 高大云杉仍被拒）。
待客户端：`lumber_policy_check` → PASS；`lumber_job` → `DONE quota_met trees 3/3` 且 `cleared≈9`。

### D-092 附注（实测纠正）：3 棵时**正好差一格**，未触发按棵预算回归
客户端实测：`DONE quota_met trees 3/3 logs 12/12 cleared=8 inventoryDelta=12 unknown=0 ticks=474`。
3 棵同型橡树实际各需 **3 + 3 + 2 = 8** 格清障 —— **正好等于** `MAX_CLEAR_PER_TREE`，
于是 job 级计数器同样能通过（第 8 格时 `budgetLeft = 8 − 7 = 1 > 0` 仍被允许，
而第 3 棵只需 2 格）。**即按棵预算重置到这一轮仍未真正被验证。**

（教训登记：我把"累计 9 格"的估算当成了已验证事实连说两轮。清障需要几格**取决于观察位/接近方向**，
估算不可当作断言；夹具必须让累计值**明显越过**阈值，而不是压线。）

→ 追加**第 4 棵**同型橡树（`24,64,213`，与既有树净空 5 格），配额提到 **4 棵**：
累计清障预期 **≥9**（实测 4 棵中已有 3 棵分别需要 3/3/2），跨过阈值 ⇒ 失效即 `clear_budget` 失败。

---

## D-093 J4：失败语义——身份复检（安全修复）+ 五条终止路径各有场景（2026-09-10）

### ① 安全修复：队列原木的**身份复检**（§6.2c⑤）
`LumberJob.queue` 是**决策时刻的位置快照**。执行期世界可能已变——**原实现直接挖该格**，
而 `MineTask` 只拦"保护区/不可破坏/流体"，**不拦"这还是不是原木"**。
后果：玩家把那一格换成箱子/矿石/机器，bot 就会去拆——**拿别人的东西**。
修正：`chop()` 取到队列格后先 `isStillLog(...)`（`BlockTags.LOGS`）复检，失败即
记 `log_replaced` + `SKIP` 决策行 + **放弃本树**（`queueIndex` 推到队尾，计划已失效），
交给结算走 partial 路径。`isStillLog` 提为 `public static` 以便自检断言。

### ② `inventory_full` 提为**前置检查**（§6.2c③）
原实现只在一棵树结算后才查背包 → 语义是"砍完才发现装不下"。现在 `tick()` 开头就查
（`hasRoomForLogs(ServerPlayer)` 提为 public static），放不下就 `DONE inventory_full`，
**不动世界**、不空转。

### ③ `Job.terminalReason()`：把终止理由变成接口契约
`Task.failureReason()` 只在 FAILED 时有意义，而 §6.2c 有两条终止是 **DONE**
（`quota_met`、`inventory_full`）——上层调度与自检必须能区分"达成了"与"背包满提前收工"。
故在 `Job` 接口上新增 `terminalReason()`（DONE/FAILED 都有），`LumberJob` 实现之。

### ④ 新增 `alice:lumber_failure_check`：**五条终止路径各有真实场景**
用例编排复刻 `MineRegressionTask` 模式（每用例前重放 `lumber_course_terrain` + `_trees` 保证独立）：

| 用例 | 注入 | 断言 |
|---|---|---|
| `no_candidates` | 扫描半径 2（范围外无树） | `FAILED no_reachable_candidate` |
| `all_rejected` | 只把 2×2 超大树（77 原木）圈进半径 | `FAILED no_reachable_candidate` + 理由码 `too_large` |
| `inventory_full` | 背包 36 格塞满圆石（跑完恢复） | `DONE inventory_full`，**未动世界** |
| `goal_timeout` | `maxTicks=40` | `FAILED goal_timeout` 且 `ticks ≤ 120`（**不空转**） |
| `log_replaced` | Job **选完树后**把队列首格换成圆石 | 该格**仍是圆石**（bot 没挖非原木）+ 改砍下一棵并 `DONE quota_met` |

最后一条的断言是"**那一格仍是圆石**"——比字符串匹配硬：直接证明 bot 没有拆掉玩家的方块。

**验证等级**：COMPILES。待客户端 `alice:lumber_failure_check` → `SUMMARY … → PASS`。

### D-093 附注（首测纠正）：注入时机错了 —— 夹具自己也必须自证
首测结果：**4/5 PASS，`log_replaced` FAIL**，失败原因是**自检自身的 bug**，不是产品缺陷：

```
[FailCheck] log_replaced 注入：队列首格 23, 64, 207 已换成圆石   ← 那是 bot 自己站的格子！
[FailCheck] LOG_REPLACED FAIL status=FAILED reason=no_reachable_candidate
            replacedPos=23, 64, 207 仍为圆石=true
```

根因：注入发生在 Job **第一次 `tick()` 之前**，此时仍在 `SELECT` 阶段，
`LumberJob.target()` 走 fallback 分支返回 `TaskTarget.block(spec.center())`（= `START_FOOT`），
于是把 **bot 脚下那格**换成了圆石（bot 被砌在方块里 → 后续如实报 `no_reachable_candidate`）。

修正两条：
1. **注入时机**：改到第一次 `job.tick()` **之后**（此时 SELECT 已完成，`target()` 才是队列首格）；
2. **注入前置校验**：只在 `LumberJob.isStillLog(planned)` 为真时才注入 ——
   这样**结构上不可能**把 bot 脚下那格当目标（bot 不可能站在原木里）。

教训（与 D-092 附注同类）：**夹具的注入动作和被测断言一样需要前置校验**；
"以为 target() 已经是队列格"是我的假设，不是事实。

### D-093 验收（2026-09-10 23:05）：J4 完成
`alice:lumber_failure_check` **5/5 PASS**，五条终止路径各有真实 Job 场景，全部机器可读：
`no_candidates` / `all_rejected(+too_large)` / `inventory_full(DONE)` / `goal_timeout(ticks=41 ≤120)`
/ `log_replaced(DONE quota_met, 注入格仍为圆石)`。
末条同时验收了本轮修的安全缺口：**bot 不得挖玩家换上的非原木方块**。

至此 **J1–J4 全部收口**（J1/J2 伐木闭环与循环配额、J3 决策缝可替换、J4 失败语义与安全守护），
R2（授权契约 + 执行期复验）亦已闭合。剩余：**J5**（`AutoMineDecision` → `MineCandidateSource` + `MineJob`）
→ **J6**（账本 + 建拆同权 + 恢复，J7/J8 前置）→ J7 → J8。

---

## D-094 J5：L3 不只服务伐木——`MineCandidateSource` + `MineJob`，孤岛拆除（2026-09-10）

### 背景：78 行的孤岛
`decision/AutoMineDecision`（78 行）自己做三件事：扫描、挑最近、打日志。它绕开了 L3 已建的
**整套**设施——`CandidateSet`（含拒绝理由码）、`SelectionPolicy`（决策缝）、`DecisionTrace`
（可判读决策）、`GoalSpec`（配额/超时）、§6.2c 终止语义。勘测员把它记为"不可复用孤岛"，
`CandidateSource` 的接口注释里也写了"J5 要把它迁过来"。

### 实现
1. **`job/mine/MineCandidateSource implements CandidateSource`**（第二个真实实现）
   - `Target`（标签 **或** 方块 ID，与命令原有解析口径一致）+ `parse(level, id)`；
   - 以 `spec.center()` 为中心、半径取 `min(来源半径, spec.radius())` 扫描；
   - **只把"匹配目标却被拒"的方块写进 `rejected`**（`protected` / `unbreakable`；一个都没匹配到则
     记 `scan(...):not_found`）——不匹配的方块是背景，不是"被拒候选"，不刷理由码；
   - 特征：`d` / `block` / `y`；破坏判定按**声明的理由**派生策略（D-082，`EXPECTED_TARGET`）。
2. **`job/mine/MineJob implements Job`**（第二个消费者）
   - 与 `LumberJob` **同一套骨架**：`CandidateSet` → `SelectionPolicy` → `DecisionTrace` →
     `GoalSpec` 配额/超时 → §6.2c 同一组终止理由
     （`quota_met`/`no_reachable_candidate`/`partial_quota`/`inventory_full`/`goal_timeout`/`product_not_collected`）；
   - **同一批硬不变量**：`attempted` 防重复选同一格；开工前 `scope.begin`（掉落物登记依赖它）；
     背包无空位即 `inventory_full` 收工；**执行期身份复检**（该格已不是目标 → 记 `target_replaced` + 跳过）。
   - `GoalSpec.mineBlocks(...)` 复用 `HARVEST_UNITS`（**一个方块 = 一个单位**），因此配额语义与伐木同源。
3. **孤岛删除**：删掉 `decision/AutoMineDecision.java`；`/alice auto-mine <tag|block> [count]`
   （原 `/alice auto-mine <tag>`，新增可选 count，默认 1 保持旧行为）改经
   `BotManager.assignMineJob` → `MineJob`；`BotSelftest` TEST9 改用
   `MineCandidateSource` + `NearestPolicy`（与生产同一条缝）。
4. **夹具共享**：把"工具必须进快捷栏"（D-089 教训）抽成 `FixtureToolKit.ensureHotbarTool`，
   `LumberJobItem.ensureAxe` 改为委托，新增的镐路径复用同一实现——**不再制造第六份重复**。

### 夹具（零参数入口）
- 新场景 `ore_course`：孤立长方体区域（x46..66, y58..76, z122..144），石体顶面 y=62，
  顶面层嵌 **6 处裸露铁矿**（52/56/60 × 128/136），起点 `56,63,132` ⇒ 全部走模式 A；
- `alice:mine_job`（零参数右键）：配额 **4**（共 6 处，留 2 处证明"到配额即停"），
  并保证快捷栏有镐；通用入口仍是命令（任意目标/任意世界）。

**验收判据（"同一套"的证据）**：`[Job] select job=mine policy=nearest picked=block@…` →
`[Job] step phase=MINE … target 1/4` → `[Job] terminal job=mine result=DONE reason=quota_met
mined 4/4 inventoryDelta=4`，且 `progressSummary`/`writes … unknown=0` 与伐木同格式。

### D-094 验收（2026-09-10 23:18）：J5 完成
`/function alice_test:ore_course` → 右键 `alice:mine_job`：
`DONE quota_met mined 4/4 inventoryDelta=4 writes breaks=4 unknown=0 ticks=240`。
`candidates` 6→5→4→3（`attempted` 过滤 + `:not_nearest` 理由码）、每次 `select` 按当前位置重算最近，
**日志与伐木逐字同构** ⇒ "同一套 Job/Trace 复用"成立，`AutoMineDecision` 孤岛已彻底拆除。

**J1–J5 全部收口**。剩余：**J6**（账本 + 建拆同权 + 恢复，J7/J8 前置）→ J7 → J8。

---

## D-095 清障不得拆"含方块实体"的方块（2026-09-10，用户裁定）

**问题（用户提出）**：野外采集任务要不要授权"不需要配对的破坏"？不授权影响多大、授权有什么风险？

**分析结论**（已与用户确认）：
- 世界修改须分**三类**，控制手段不同：**破坏型**（挖穿石头够矿、清树叶）**无法配对**，只能靠"谓词 + 预算 + 归因"；
  **临时放置**（垫脚柱/台阶）必须配对（J6 建拆同权）；**永久放置**（道路）走独立授权。
  故"破坏型授权"应**独立成类**，不按 J6 的配对管——硬配对只会制造"我保证能恢复"的假承诺。
- **不授权的代价不是效率，而是能力上限**：橡树 4 根原木**当前可见 0 根**（不授权破坏则根本砍不动）；
  `mine_course` 的 `blocked`/`buried` 两个用例就是"必须破坏才能拿到"的形态；J5 的矿脉场景是**故意做成裸露的**，
  真实矿脉多埋在地下 ⇒ 不授权等于"只采露头矿"，bot 实际不可用。
- 风险盘点：保护区/玩家建筑**已缓解**（SafeZone + 理由派生策略）；预算**规划期已缓解**、执行期见 G4；
  归因**已缓解**（`WriteAudit`）；**路径上的容器/机械未缓解**（本轮修）；**永久地形改变不可避免**
  ⇒ **J8 的"区域不残留"不变量只能对放置物定义**，或明确接受"树没了 = 预期变化"。

**本轮修正（用户选"从可破坏集合剔除"）**：`BlockBreakSafety.clearingRefusal` 新增
`hasBlockEntity()` → 拒绝码 **`block_entity`**。
关键点是**放在"可破坏集合"里而不是另加拒绝逻辑**：`breakable` 会喂给搜索
（`SurfaceMovementProvider` 用它生成 `BREAK_AND_*` 候选）⇒ **规划器自动绕开**容器/机械，
绕不开则如实 `found_but_unminable`。**"绕路"因此是免费得到的，无需新机制。**
只作用于清障策略；`EXPECTED_TARGET`（玩家明确指定的目标）不受影响。

**用户同时裁定**：破坏预算**保持 `单格耗时 × 10 × 档位` 不变**（野外不可控，宁可不挖也不乱挖）；
**G4（执行期预算）排在 J6 之后**。

**验证等级**：`IMPLEMENTED` + `COMPILES`。**运行时断言待补**：需要一个"路径上有容器"的场景
（正向断言"绕开而非拆掉"），计划并入 J6 自检——本轮不声称已验证。

---

## D-096 J6-a：世界修改账本 `WorldModLedger`（只记放置）+ 动作层自动记录 + 只读查看（2026-09-10）

按 D-081 §12 的四层分工落地第一步（"记录"层）。用户已裁定：账本**只记放置**（与 `WriteAudit` 分工，
后者管"谁授权、为什么"含破坏）、`KEEP` 走独立授权不受配对约束、恢复触发先做"任务结束 + 只读查看"、
验收用现有 `pillar_course`/`place_course` 素材。

### 实现
1. **`ledger/WorldModLedger extends SavedData`**（`alice_world_mod_ledger`，存 overworld 的 DataStorage）
   - 条目：`(pos, placed, previous, reason, policy, scopeId, owner, tick)`；一格一条（重放即覆盖）；
   - `Policy { TEMP, KEEP }`：由 `WriteReason.temporary()` 派生——`STEP_PLACEMENT` / `SUPPORT_PLACEMENT` = **TEMP**，
     `BULK_EDIT` / `MANUAL` = **KEEP**（道路等永久放置走独立授权，不受"建拆同权"约束）；
   - **反序列化时未知 policy 一律按 `TEMP`**（宁可多拆自家的，也不漏拆）；
   - `previous` 字段是"精确恢复原状"与"只拆自己放的"的前提（往雪/草里放置时原状态不是空气）。
2. **记录点 = 动作层**（`BlockInteraction.placeAt` / `placeBulkEdit`）：只有动作层看得见**每一次**修改——
   **含内核 `PILLAR` 放的方块**（Job 未必看得见）。`previous` 在放置**之前**采样。
3. **授权作用域 = 一次任务**：`BotSession.beginTask` → `openScope`，`clearTask` → `closeScope`。
   作用域单位正是 `RESTORE_BY_SCOPE` 的粒度。
4. **建拆同权的可断言信号**：`clearTask` 关闭作用域时若账本仍非空，输出
   `world_mod_ledger_close scope=… 仍有 N 条未清除的放置（建拆同权未闭合）` 警告。
   **J6-a 阶段这条警告是预期出现的**——恢复机制（J6-b）还不存在，警告正是"配对未闭合"的证据。
5. **只读命令 `/alice ledger [all]`**：打印 `pending / TEMP / KEEP / openScopes` + 最近若干条明细。
   **只读**——不分配任务、不改世界、不清账本。

### 本轮不做（J6-b/c）
- **恢复**（`RestoreScopeTask`：自上而下拆自己的 TEMP、比对 `placed` 才算自己的）；
- 崩溃/重启续做；
- 把 `previous` 从"方块注册名"升级为**完整 BlockState**（精确原状）——当前按注册名记录/比对，
  等恢复实现真的需要区分半砖/雪层朝向时再升级，避免现在背上 NBT 兼容负担。

**验证等级**：COMPILES。验收（客户端）：右键 `alice:pathing_regression`（其中 `place_course`/`pillar_course`
会真的放置方块）→ 日志出现 `[Ledger] place …` 与 `world_mod_ledger_close … 仍有 N 条` →
`/alice ledger` 显示 `pending>0 TEMP>0` 且条目含 `scope=` 与 `STEP_PLACEMENT`。

### D-096 验收（2026-09-10 23:47）：J6-a 完成
`alice:pathing_regression` → `/alice ledger`：7 条 `[Ledger] place …` 全为
`cobblestone←air [TEMP STEP_PLACEMENT scope=1ae26630#1:PathingRegressionTask]`；
`clearTask` 输出 `仍有 5 条未清除的放置（建拆同权未闭合）`（J6-a 阶段预期：恢复机制未实现）；
`/alice ledger → pending=5 TEMP=5 KEEP=0 openScopes=0`。

**最有价值的一点**：抓到的方块是**内核 `PILLAR` / `PLACE_STEP_AND_TRAVERSE` 在寻路途中放的**——
正是设计里"Job 未必看得见、必须由动作层记录"的那一类，证明记录点选对了。
同轮 `pathing_regression` 仍 **14/14 PASS** + `coverage=PASS`（10 种 Movement）⇒ 账本钩子无回归。

**下一步 J6-b**：`RestoreScopeTask`（严格自上而下拆自己的 TEMP、比对 `placed` 才算自己的、
拆完 `forget`）+ 由 Job 在收尾调度 + D-095 的"路径上有容器 → 绕开而非拆掉"断言。
验收：`/alice ledger` 回到空 + 场景无残留。

---

## D-097 测试物品贴图按类共用 + README 去除个人环境内容（2026-09-10）

**背景（用户提出）**：测试物品贴图"不够了"——36 个物品模型全在**借用原版贴图**（6 个共用 `iron_pickaxe`、
4 个共用 `diamond_axe`…），背包里分不清；另有 3 个物品（J5/J3/J4 新增）**连模型都没有**。
用户同时指出 README 里不该出现"同步到 Windows 测试客户端""不再需要 `libs/`：JEI"这类**开发者个人环境/习惯**。

**贴图（用户裁定"不精挑、同类共用、靠名字区分即可"）**：
取自开源贴图集 [`malcolmriley/unused-textures`](https://github.com/malcolmriley/unused-textures)（**CC-BY-4.0**，可用但**必须署名**），
按领域归为 **6 类**共用：`lumber`（锯）/ `mine`（镐模）/ `pathing`（齿轮）/ `guard`（水晶）/ `check`（泥板）/ `select`（透镜）。
36 个物品模型全部重写为引用 `alice:item:<类>`；署名与逐项映射见
`src/main/resources/assets/alice/textures/CREDITS.md`（含作者、来源、许可、映射表）。
抓取走 jsDelivr CDN（`raw.githubusercontent.com` 在本机不可达）。

**README**：删除 `libs/`/JEI 段落与"同步到 Windows 测试客户端"整节（含 `/mnt/d/...` 绝对路径）；
"开发流程"改为不含本地环境的通用描述；"真人在 Windows 客户端的观察"→"真人在游戏客户端的观察"；
新增 **Credits** 节（贴图来源与许可）。**顺带修正一处过期内容**：能力表原写"伐木**已禁用**（D-073）"，
而 J1–J5 均已验收——改为如实描述伐木/挖掘两个 L3 Job、世界写入授权、修改账本与决策缝自检。

**验证等级**：COMPILES（贴图与 CREDITS 确认已打包进构建产物）。

---

## D-098 J6-b1：作用域恢复 `RestoreScopeTask` + 收尾自动追加 + 命令兜底（2026-09-10）

用户裁定方案 **C**：**Job/任务收尾自动恢复** + **命令兜底**处理历史遗留。为了"脚踏实地"，
J6-b 拆两小步：**b1 = 恢复能力本身**（本轮），**b2 = D-095 容器绕行断言场景**（下一步）。

### 三条纪律（缺一条就会伤到别人的世界）
`RestoreScopeTask`（L2，复用已验收的 `MineTask`：走位 → 视线 → 触及 → 破坏）：
1. **严格自上而下**：按 `y` 降序处理。垫脚柱必须"站在顶上拆脚下"，否则会留下够不到的悬空块；
2. **只拆自己放的**：拆前比对账本 `placed` 与该格当前方块——不匹配即**放弃并销账**（记 `not_ours`）。
   绝不能因为"账本里记着"就去拆玩家后来放的东西；
3. **不许挖地形**：子任务一律 `standableOnly=true`（只允许现成可站站位），
   禁止为了够到一块脚手架而挖穿地面。

破坏理由用新增的 `WriteReason.SCAFFOLD_RESTORE`（**明确目标策略**）——
清障策略会拒 `underfoot_block`，而拆垫脚柱**恰恰就是**拆自己脚下那格。
预算按队列实际长度算：`100 + 200 × 块数`；终态 `restore_done` / `restore_partial` / `nothing_to_restore`。

### 建拆同权由**会话**强制，而非靠每个 Job 自觉
`BotSession.clearTask`：关闭作用域后若该 scope 仍有**我方 TEMP 放置**，**自动追加一个恢复任务**
（而不是只打一条警告）。优点：
- 覆盖**所有**任务，含非 Job 的任务（例如寻路回归——它正是本轮 5 条历史遗留的来源）；
- 恢复任务自身不放置任何东西 ⇒ 不会自我递归（已显式排除）。

### 命令兜底
`/alice restore`：对账本里全部待恢复的 TEMP 启动一次恢复任务（处理崩溃/重启/升级前的历史遗留）。
`/alice ledger` 仍为只读查看；两者配合的验收口径是"**`pending` 回到 0**"。

### 本轮不做（J6-b2）
D-095 的"路径上有容器 → 绕开而非拆掉"断言需要"箱子挡在必经之路"的场景；下一轮专做，
避免把"恢复能力"和"新场景夹具"两件事混在一次验证里。

**验证等级**：COMPILES。验收（客户端）：右键 `alice:pathing_regression` →
其收尾应自动追加恢复任务 → 日志出现 `仍有 5 条未清除的放置 → 自动追加恢复任务`、
`[Restore] start … blocks=5`、`[Restore] SUMMARY … restored=5 … remaining=0 → DONE`，
随后 `/alice ledger` 显示 `pending=0`。

### D-098 附注（首测纠正）：拆脚手架**必须用 DOWNWARD**，不能用 MineTask
首测结果：`restored=0 skipped=5 remaining=4 ticks=9 reason=restore_partial → FAILED`。
5 块里 1 块判 `not_ours`（账本记 cobblestone、现场是 air → **正确地放弃并销账**），
其余 4 块**在 9 tick 内全部规划失败**：

```
[Restore] block 24,65,44 → [MiningPlanner] standable_only reason=no_valid_standing_point
[Restore] block 24,64,44 → reason=no_reachable_standing_point
[Restore] block 2,63,66  → reason=no_reachable_standing_point
```

**根因（我的实现错，不是设计错）**：`MiningPlanner` 模式 A **显式排除 `target.above()`
作为站位**——因为"站在目标头上"正是 `DOWNWARD` 的语义，不是"站位"。所以"用 `MineTask`
拆自己脚下那格"**必然规划失败**。而设计文档 D-081 §12.3 早就写明了正确机制：
**"挖脚下 → `DOWNWARD` 落 1 格 → 重复（`DOWNWARD` 已于 D-048/D-050 客户端验收）"**——我绕开了它。

**修正**：`RestoreScopeTask` 改为每块两阶段：**① APPROACH 走到目标正上方站好 → ② DESCEND
向下走一格**（`DOWNWARD` 破坏脚下并让 bot 落进去）→ 销账。于是拆 n 格垫脚柱 = "落 n 次"，
天然自上而下，且下一块正好落到脚下，形成紧凑循环。
配套新增**显式授权入口** `PathRequest.scaffoldRemoval`：只允许
`TRAVERSE/DIAGONAL/ASCEND/DESCEND/DOWNWARD/FALL`，**刻意不含** `PILLAR`/`PLACE_STEP_AND_TRAVERSE`
（回收阶段不许再建）与 `BREAK_AND_*`（不许沿途挖地形）；已在
`docs/WORLD_WRITE_AUTHORIZATION.md` 登记为 P7（回收任务用 A8）。

**教训**：实现前应当先读**设计文档里已经写明的机制**（§12.3 明写 DOWNWARD），
而不是凭"复用 MineTask"的直觉开工——`MineTask` 的排除规则是**为挖掘场景**设的。

---

## D-099 回归大面积失败：一次性方块落进主背包（D-089 的同一病灶，第三次出现）

**现象**：`pathing_regression` 从 14/14 掉到 **9/14**，失败集中在
`place_course` / `pillar_course` / `fall_course` / `place_course+wall` / `+disturb`，
`coverage=FAIL([DIAGONAL, ASCEND, PILLAR, FALL, PLACE_STEP_AND_TRAVERSE])`，
`executed` 里恰好缺这三种"需要放置"的 Movement。

**诊断链**（不是"我的新代码碰了搜索"）：
```
[PathingStats] descend_precondition=180 status=UNREACHABLE goal=8,62,66
[PathRetry]    plan_failed attempt=0 status=UNREACHABLE
```
搜索直接判不可达 ⇒ 这些 Movement **根本没被生成**。共同点：`PILLAR` / `PLACE_STEP_AND_TRAVERSE` /
`FALL` 的生成都要求**能手拿一次性方块**（`SurfaceMovementProvider:225/347` 用
`findPlaceableSlot`；FALL 的"PILLAR 返回"守卫用 `countThrowaway`）。

**根因**：`BlockInteraction.findPlaceableSlot` / `countThrowaway` **只扫快捷栏 0..8**
（与 Baritone 同口径）；而夹具 `PathingRegressionTask.ensureCobblestone(bot, 8)` 只填空格，
**快捷栏满时退化到 `inventory.add(...)` → 方块落进主背包 → 永远选不到** ⇒
`PILLAR`/`PLACE_STEP`/`FALL` 生成不出来 ⇒ 三个场景 `UNREACHABLE`、覆盖率断言缺三种。

**这与 D-089（斧子落主背包 → 全程用镐）是同一个病灶**，且同样**只在"跑了一整天之后"暴露**
（快捷栏被历次运行积累的物品塞满）——第三次出现了。

**修正（治一类，不治一个）**：在 `item/FixtureToolKit` 增加
**`ensureHotbarStack(bot, sample, isMatch, minCount, label)`**：数快捷栏 → 有空格放入 →
否则把"既不是目标物、也不是有用工具"的一格挪进主背包 → 实在腾不出则**明确告警**
（而不是静默放进扫不到的地方）。`PathingRegressionTask.ensureCobblestone` 与
`MineRegressionTask.ensureCobblestone` 均改走该共享实现（与 D-089 的工具版本同源）。

**如实标注未做完的一点**：`RestoreScopeTask` 目前**只拆不收回**——拆下来的方块变成掉落物，
而恢复任务是在 `scope.end()` 之后跑的（作用域未开启、掉落物不被登记），因此**材料没有回到背包**。
夹具的 `ensureHotbarStack` 只是保证"每轮够用"，并没有解决"拆了要收回"。
这正是"建拆同权"的**物质闭环**该有的下一步（重开作用域 + 收尾调一次 `CollectDropsTask`），
登记为 **J6-b1b**，下一轮做。

**验证等级**：COMPILES。验收：`pathing_regression` 回到 **14/14**、`coverage=PASS`。

### D-099 追加（用户裁定）：夹具 = 创造背包逻辑 → 强制替换，并收敛全部发料点
首测修复后仍失败，但**我加的自证日志直接给出了答案**：
```
[FixtureTool] 腾不出快捷栏放 cobblestone×8：物品进了主背包，**工具/放置判定扫不到**
```
9 个快捷栏格全被历次运行攒下的工具占满，"只让无用格"的策略一格都让不出。

**用户裁定**："这个阶段的工具赋予只是临时测试手段，强制替换也无妨——不然就给予后让 bot
从背包里拿到快捷栏，如果还是满了，就覆盖，**其实就是创造背包的逻辑**。"

**据此重写 `FixtureToolKit`（不再讲究"这格有没有用"）**：
① 快捷栏已够 → 完事；② 否则把**主背包**里的同种物品搬进快捷栏（先并进同类栈、再放空格）；
③ 还不足 → 填空格；没有空格就**直接覆盖**第一个非选中格（旧物尽量塞回主背包，塞不下就丢并告警）。

**同时把全部 12 个"自己发料"的夹具收敛到该实现**（此前各自 `数空格 → inventory.add`，
是同一 latent bug 的 12 份拷贝）：`PathingRegressionTask`、`MineRegressionTask`、
`Fall/Pillar/Vertical/BreakEnter/MineCourse` 诊断任务、`TargetSelector`、
`PathingBreaker/ Waller / Placer / Disturber / LavaGuard` 物品。
现全仓**只剩这一处**往快捷栏发料。

**验证等级**：COMPILES。验收：`pathing_regression` 回到 14/14、`coverage=PASS`。

---

## D-100 `THROWAWAY` 标签化（2026-09-11，用户裁定"只整理、不扩张"）

**背景**：用户提问——"可破坏集合是白名单吗？黑曜石严格说是条件可拆而非不可拆，这条规则对模组兼容怎么样？"
核查结论：破坏判定是**默认允许 + 拒绝清单**（黑名单式），黑曜石的拒绝码是
`expensive_clearing_block`（**代价**）而非 `unbreakable_block`（**不可能**），且**只作用于清障策略**——
作为**明确目标**仍可挖 ✓，正是用户描述的"条件可拆"。模组兼容的真正短板是**三处硬编码方块清单**：
`isExpensiveToClear`（3 个原版方块）、`THROWAWAY`（7 个原版方块）、`MiningBudget.tierOf`（原版 8 种矿石）。
按"按 API/状态判定"的规则（流体、负硬度、`block_entity`、保护区、破坏成本）都对模组友好，
凡"硬编码清单"的都对模组排斥。

**本轮只做 `THROWAWAY` 标签化**（用户裁定：模组扩展已知改哪里、以后再加；先整理现有逻辑）：
- 新增数据包标签 `data/alice/tags/blocks/throwaway.json`，内容与原清单**完全一致**（7 个原版方块）；
- `BlockInteraction.THROWAWAY` 由 `List<Block>` 改为 `TagKey<Block>`（`alice:throwaway`），
  两处用法（`findPlaceableSlot` / `countThrowaway`）改判 `defaultBlockState().is(THROWAWAY)`；
- **行为不变**（同一批方块），但从此**不改代码即可扩展**：整合包/模组在自己的
  `data/<ns>/tags/blocks/throwaway.json` 里追加（`replace:false` 合并）✓；
  将来要放宽，只需把通用标签（`#minecraft:base_stone_overworld`、`#forge:cobblestone` 等）加进标签文件。

**未做（已登记，等模组适配阶段）**：`isExpensiveToClear` 改"代价阈值"（用已有的
`estimateBreakTicks`，黑曜石自然被挡住、模组高代价方块自动被挡住）+ 标签扩展点
`#alice:clear_forbidden`；`tierOf` 接 `#forge:ores/*`。

**验证等级**：COMPILES。**验收判断很方便**：若标签没生效，`findPlaceableSlot` 会返回 -1，
`PILLAR`/`PLACE_STEP`/`FALL` 立刻生成不出来——即 `pathing_regression` 的 place/pillar/fall
三个场景会像昨天 D-099 那样失败 ⇒ **跑一次 `pathing_regression` 回到 14/14 即证明标签生效**。

---

## D-101 J6-b1b：恢复的**物质闭环**——拆下来的方块要回到背包（2026-09-11）

**背景（D-099 附注里我自己标注的欠账）**：`RestoreScopeTask` 原来"只拆不收回"——它跑在
`BotSession.clearTask` 的 `scope.end()` **之后**，作用域未开启、掉落物**不被登记**，
于是拆下来的方块变成无人认领的掉落物（最终消失）。后果是"建拆同权"只完成了**世界侧**，
**材料侧**每轮净消耗——夹具只能靠 `ensureHotbarStack` 补料掩盖（正是 D-099 的成因之一）。

**实现（三步）**：
1. **重开作用域**：`buildQueue` 里按队列的几何中心与覆盖半径 `scope.begin(center, radius, owner)`
   （空队列时不开，避免影响调用方的掉落物登记）；半径至少 8 格、按最远目标 +4；
2. **收尾收集**：所有块处理完后进入收集阶段，用 `CollectDropsTask(bot, origin=首个成功恢复点,
   scope, expectedIds=[], allowWorldModification=false)`——**不允许为收材料而改世界**；
   预算 600 tick，与 `CollectDropsTask` 默认一致，并计入总预算；
3. **如实报账**：终态日志新增 `recovered=`（**一次性方块库存的净变化**），
   并若仍有掉落物未收回则记 `drops_left=N`（够不到就如实说，不粉饰）；
   任务自己开的作用域在 `finish` 里自己关。

**验收判据**：`[Restore] SUMMARY … restored=N recovered=?` —— `recovered` 期望为正
（拆下的方块以掉落物形式被收回）；`/alice ledger` 仍为 `pending=0`。

**验证等级**：COMPILES。待客户端（`alice:pathing_regression` → 自动恢复 → 看 SUMMARY 的 `recovered`）。

### D-101 附注（实测纠正）：恢复失败的**真正原因**是"测试场景相互孤立"
J6-b1b 上线后测：回归 14/14 通过 ✓，但恢复仍 `restored=0 skipped=5 remaining=4 recovered=0 → FAILED`，
且失败点从"挖不动"变成了 **APPROACH 阶段**。日志给出根因：
```
[PathingStats] descend_precondition=3769 status=UNREACHABLE goal=24,66,44
[PathRetry]    plan_failed attempt=0 status=UNREACHABLE feet=8,62,66 goal=24,66,44
```
bot 在 `8,62,66`（回归最后一个场景的终点），待恢复方块在 `24,66,44`（**另一个场景**）——
而测试场景按规范是**孤立长方体区域**（边界外一圈为空气）⇒ **场景之间没有路**，搜索如实报 `UNREACHABLE`。

**结论**：不是恢复机制错，而是**夹具没把 bot 送过去**（与伐木/挖矿物品"传送到场景起点"同一职责）。
DOWNWARD 机制（D-098 附注）本身仍未被证伪，只是尚未被走到。

**新增夹具入口 `alice:restore_check`**（零参数右键，`/give @s alice:restore_check`）：
找账本里第一条待恢复 TEMP → 把 bot 传送到它**旁边一个可站格**（不是它正上方——那里可能被夹具墙占着，
`2,64,66` 就是回归自己放的墙）→ 启动全量恢复任务。这样恢复机制能在**单个场景内**被真正走到。

**已知的开放问题（不粉饰）**：`pathing_regression` 这类夹具会把方块放在**孤立场景**里，
任务收尾自动追加的恢复任务**必然走不到**它们 ⇒ 每轮回归都会留下 `pending`（账本只增不减）。
这是"夹具几何"与"建拆同权"的冲突，需要单独处理（可选方向：夹具在自己的场景内自行清理 /
给恢复任务一个"仅限本场景"的范围参数 / 让夹具把 bot 传送到每个块附近）。

---

## D-102 J6-b1c：恢复加**侧拆兜底**——两条拆除路径（2026-09-11）

**上一轮的诊断（切中机制本身）**：`restore_check` 把 bot 送到待恢复方块正上方后，
APPROACH 成功（`movements=0`），但 **DESCEND 失败**：
```
[PathingStats] status=UNREACHABLE goal=2,63,66
[PathRetry]    plan_failed feet=2,64,66 goal=2,63,66
```
读 `SurfaceMovementProvider.appendDownward`（`:243-247`）：`DOWNWARD` 要求
`canWalkOn(level, to)`——即**目标格的下方必须有支撑**。而 `(2,63,66)` 是 `place_course` 里
**跨坑的桥面方块**（下面是坑）⇒ 生成不出 `DOWNWARD` ⇒ `UNREACHABLE`。
**内核是对的**：拆掉桥面后 bot 会掉进坑里、没有落点，拒绝下降是正确的安全行为。

**结论**：设计文档 §12.3 的"挖脚下 → DOWNWARD"机制**只覆盖"下方有支撑"的垫脚柱**；
悬空的桥面/台阶需要**从侧面拆**。于是恢复任务改为**两条路径按块依次尝试**：

| 路径 | 适用 | 授权 |
|---|---|---|
| ① APPROACH + DESCEND（`DOWNWARD`） | 垫脚柱（下方有支撑） | `PathRequest.scaffoldRemoval`（只拆不建、只允许向下拆脚） |
| ② **SIDE_BREAK 兜底**（`MineTask`） | 悬空桥面/台阶、或走不到正上方 | `WriteReason.SCAFFOLD_RESTORE`（明确目标策略——清障策略会拒 `underfoot_block`）+ `standableOnly=true` |

两条路都坚持"**恢复一律不许挖地形**"；单块预算从 300 提到 450 tick（最多三段）。

**验收**：`restore_check` 右键后，**同一个场景内**的桥面方块应被侧拆并销账
（`restored≥1 recovered≥1`，`/alice ledger` 的 `pending` 相应下降）；
跨场景的（孤立区域走不到）仍如实 `approach_failed`，任务终态为 `restore_partial`（如实失败）。

**验证等级**：COMPILES。

---

## D-103 简化：拆除回到"该在的位置"——删掉收尾自动恢复（2026-09-11）

**用户裁定**："我觉得确实该这么简化"。

**问题回顾（三次失败的共同根因）**：`RestoreScopeTask` 被我实现成**任务结束后、bot 已经离开脚手架**，
再回头去拆**任意几何上**的方块。这一个位置选择引出了全部复杂度与失败：
- 走回去（夹具场景互相孤立 ⇒ `UNREACHABLE`）；
- 站位选择 + 侧拆兜底（J6-b1c）；
- **悬空目标要放支撑块 ⇒ 需要材料 ⇒ 鸡生蛋**（实测 `PLACE_RESOURCE_UNAVAILABLE`：
  bot 刚把方块全用掉，正等着被收回，却被要求再拿一块出来）；
- "往返跑"（每块都走过去、卡住、换下一块）。

而设计文档 §12.3 的原意是：**"建脚手架 → 攀爬 → 使用 → 仍在顶上时自上而下拆除 → 才允许离开"**——
拆除是**放方块的那个任务在会话内顺手做完的事**，机制只有三步（拆脚下 → `DOWNWARD` 落 1 格 → 重复），
**不需要寻路、站位、回程或任何材料**。我在 J6-b1 里选错了位置，随后给错位置打了三次补丁——
这违反了项目自己的规矩（同一问题失败 2+ 次应停下做根因分析，而不是继续加分支）。

**本轮改动（是减复杂度）**：
1. **删除 `BotSession.clearTask` 里的"收尾自动追加恢复任务"**：不再自动追任务，
   只保留一条**信号日志**（`world_mod_ledger_close … 建拆同权未闭合`）并提示 `/alice restore`。
   接口留给**放方块的那个任务**（J7 攀爬落地时接上"在顶上拆"）。
2. **真 bug 修正**：恢复用的 `MineTask` 传 **`collectDrops=false`** —— 它同时关掉"悬空目标先放支撑块"，
   于是**拆东西不再需要材料**（解开鸡生蛋）。材料回收仍由 J6-b1b 的收尾收集负责。
3. **账本 GC `WorldModLedger.dropStale`**：现场已不是我方方块的条目**就地销账**并记日志
   （场景重放/别人拆掉/我方已拆都归此类）——在 `clearTask` 与触发恢复前各跑一次，
   账本因此保持"活的"，不再只增不减。这是 D-101 附注里那个开放问题的**最小解**。

**保留**：`/alice restore`（手动尽力而为的清理工具，够不到就如实说）与 `alice:restore_check`（夹具）。
**未做（接口已留）**：J7 的"会话内在脚手架上自上而下拆除"。

**验证等级**：COMPILES。验收：`pathing_regression` 跑完**不再自动追加恢复任务**（日志只有一条
未闭合信号），随后 `/alice ledger` 的 `pending` 因 GC 而**自动下降**（场景重放抹掉的条目被销账）。

---

## D-104 J6-b2：容器绕行自检——"不为取目标而拆箱子"（2026-09-11）

**目的**：把 D-095 那条规则（含方块实体的方块从"可破坏集合"里剔除 ⇒ 规划器自然绕开）
从"代码推断"变成**可实测的断言**。

**场景 `clear_guard_course`**（isolated 长方体区域，x40..62 / y58..78 / z150..170）：
平台 y=63；一道横墙（x=50，y64..67，够高防跳越），**唯一缺口 (50,64,158) 用箱子堵住**；
目标石头在墙另一侧 (56,64,158) 附近；bot 起点 (44,64,158)。
⇒ 想拿到目标，**要么绕路、要么拆箱子** —— 正是要检验的抉择点。

**自检 `alice:clear_guard_check`（零参数右键）跑一次真实 `MineTask`，断言三件事**：
1. **谓词层**：`BlockInteraction.breakable(chest, PATH_ACCESS)` 必须为 `false`
   （含方块实体不得作为"通行破坏"对象）；
2. **执行层**：跑完之后**箱子仍在**（`chest_intact=true`）——bot 没有为取目标而拆它；
3. **诚实性**：`target_removed` 只作参考、**不作判据**——绕路成功或如实失败**都算 PASS**，
   本自检只要求"**不伤害非目标容器**"。
输出：`[ClearGuard] SUMMARY predicate_refuses=? chest_intact=? target_removed=? → PASS|FAIL`。

**验证等级**：COMPILES。验收：右键 `alice:clear_guard_check` → `→ PASS`（箱子完好）。

## D-105 运行期脚位格统一（`footCell`）+ R4 会话零进展快速失败（2026-09-11）

**触发**：J6-b2 客户端实测"bot 在箱子上跳了半天"。日志与代码定位到**不是**箱子这一类方块的问题，
而是**两套脚位格坐标系**：规划层是"支撑格的上一格"，运行期 `entity.blockPosition()` 是"脚所在格"。

**证据链（可复查）**
1. `latest.log`（2026-09-11 17:11）：TRAVERSE 44→49 每段 4–5 tick 正常；`ASCEND from=49,64,158 to=50,65,158`
   （目标格 = 箱子上一格）开始后 `controller_jump_once` ×12（每 ~0.6 s = 一次完整跳跃周期），
   17:11:17.9 `SEGMENT_TIMEOUT`，整段 161 tick；超时瞬间 `actualFoot=50,66,158`
   （= 箱顶 64.875 起跳 1.25 格的顶点格）→ bot 确实爬上了箱子，然后在箱顶原地弹跳。
2. `AscendExecution` 三个出口全部以 `blockPosition().getY() >= toFoot.getY()` 为界；站在 0.875 高的
   箱子上该值恒为 from 的高度 → **完成不了、结算进不去、跳跃永远允许**，只剩段超时。
3. 所有 `*Execution` 的 COLUMN 完成契约与 D-026 合法位置集都在比 `blockPosition()`；而
   `isStandingAtFootPos`（EXACT 路径）早已按 `supportTopY` 形状判定 —— 说明"形状感知"本就是这个项目
   的意图，只是 COLUMN 路径没跟上。

**Baritone 对照（本地 1.20.1 树 `/home/fb486/projects/reference/baritone-1.20.1/`）**
- `src/api/java/baritone/api/utils/IPlayerContext.java:63-81`：`playerFeet()` 对裸坐标 `y + 0.1251`
  再取整，并且"脚位格是 `SlabBlock` 就再上移一格" —— 与 `MovementAscend` 的目标格语义对齐，
  所以 Baritone 不会进入本死锁（它的移动完成判定 `PathExecutor.java:428` 用的就是这个校正后的脚位）。
- `src/main/java/baritone/pathing/movement/MovementHelper.java:394-430`：`canWalkOnBlockState` 对
  `CHEST / TRAPPED_CHEST / ENDER_CHEST` 直接返回 `YES`，底半砖按 `allowWalkOnBottomSlab` 处理
  ⇒ **"箱子可走"是 Baritone 的既定语义**，Alice 的 D-041（非满高方块保持可站）与之一致；
  错的不是"把箱子当可站"，而是运行期脚位格没有做同样的对齐。
- `src/main/java/baritone/pathing/path/PathExecutor.java:242-250`：段级兜底是
  `ticksOnCurrent > 原始成本估计 + movementTimeoutTicks`（Baritone 没有独立的"零进展"检测）。

**决定（两件独立的事）**
1. **R1（根因）**：新增 `MovementHelper.footCell(ServerLevel, Entity|坐标)` 作为**唯一**的运行期脚位格口径：
   脚所在格有碰撞形状、可站、且顶面 ≥ 半格 → 取上一格；否则就是脚所在格。
   与 Baritone `playerFeet()` 语义一致，但用**碰撞形状**判定而非硬编码 `0.1251` + `SlabBlock instanceof`
   （D-041 的模组兼容立场：模组半格方块同样成立）。
   替换点：全部 `*Execution` 前置/完成/跳跃门控、`*ExecutionFactory` 前置、`PathSession`
   （D-026 合法位置集 / 重同步 / 漂移 / 遥测）、以及任务层所有"以 bot 当前脚位格为输入"的地方
   （`PathRequest` 起点、到达判定、站位候选、`task_execution_terminal pos`、回归覆盖索引）。
   `isAtFootColumn` 签名相应改为 `(level, entity, footPos)`。
2. **R2（安全网，Alice 特有）**：`PathSession` 增加"起跳后落回**同一脚位格**"计数，
   连续 3 次 → `TIMEOUT / SEGMENT_NO_PROGRESS`（仍走重规划，只是提前约 120 tick）。
   **这是登记在案的偏离**：Baritone 只有段超时兜底；Alice 加它的理由是"8 秒可见原地弹跳"既是体验问题、
   也让失败码无法区分（`SEGMENT_TIMEOUT` 掩盖了"物理上不可能收敛"这一事实）。
   可验证后果：正常收敛路径不受影响（落点变了即清零，回归 14/16 必须保持）；
   不涉及跳跃的卡死仍由段超时兜底。

**验收夹具**
- 无头断言（`PathingRegression.assertFootCellRule`，自建区域 z=300，不依赖 bot 物理）：
  箱子 0.875 / 底半砖 0.5 / 灵魂沙 0.875 / 地毯 0.0625 / 整格，逐项断言"`footCell` 给出的格 =
  `canWalkOn` 认可的格"。它随 `alice:pathing_regression` 的 SUMMARY 输出 `foot_cell_rule=PASS/FAIL`，
  也随 `/alice selftest` 全量跑（原先只挂在 selftest 上，2026-09-11 客户端首测发现"回归项没跑它"，已补挂）。
- 可执行回归项（`pathing_regression` 一次右键覆盖）：
  `chest_step_course`（起点 (1,64,126) → 目标 (2,65,126)，必须执行 `ASCEND` 到箱顶）、
  `slab_step_course`（封闭 1 格宽走廊，(4,64,145) → (8,64,145)，中途一块底半砖）。

**验证等级**：WINDOWS_CLIENT + USER_ACCEPTED（2026-09-11 18:31，用户"测试完了"）。
- `[Regression] SUMMARY … chest_step_course=PASS slab_step_course=PASS foot_cell_rule=PASS coverage=PASS`
  （16 场景 + 1 无头断言全 PASS，任务 `COMPLETED` 460 tick）；
- `chest_step_course`：`ASCEND from=1,64,126 to=2,65,126` **10 tick 完成**，`actualFoot=2,65,126`
  （旧行为：箱顶原地弹跳 12 次 → `SEGMENT_TIMEOUT` 161 tick）；
- 全程 `SEGMENT_TIMEOUT` / `no_progress` 出现 **0** 次 ⇒ R2 无误报；
  R2 本身仍是构造性验证（R1 修好后无法用原场景主动触发）；
- `clear_guard_check`：`predicate_refuses=true chest_intact=true target_removed=true → PASS`（91 tick）
  —— bot 在容器缺口旁合法挖 2 格石头绕行（`[WRITE] break 50,64,159 / 50,65,159 PATH_ACCESS`），
  全程未触碰容器。

## D-106 执行期写入预算（G4 Slice A）：任务级破坏/放置上限（2026-09-11）

**问题**：授权（`WriteGrant`）只约束"**这一格**能不能改"，不约束"**改了多少格**"。内核清障
（`PATH_ACCESS` 破坏 + `SUPPORT_PLACEMENT` 放置）是"有多少拆多少"：目标不可达时它会一直试下去，
世界侧**没有任何"到此为止"**。D-082 当时明确把预算留作独立概念（"预算不合并"），本轮把它接上。

**Baritone 对照**（本地 1.20.1 树，D-036 规则 4 登记为 Alice 特有差异）：
- 写入权限 = `CalculationContext.allowBreak:104` / `allowBreakAnyway:105` + `blocksToDisallowBreaking`
  （`MovementHelper.java:73`）；
- 写入代价 = `breakCostMultiplierAt:175`、`costOfPlacingAt:156` 进路径成本，由
  `PathExecutor.java:242-250`（超时）与 `:213`（`maxCostIncrease`）兜底；
- **没有任何"计数上限"**（全树 grep `maxBreaks/breakLimit/writeBudget` 为空）。
⇒ Alice 加计数上限的理由：服务端权威 + 可审计 + 可归因（同 D-082 的立场），不是"补 Baritone 的缺"。

**用户裁定（2026-09-11）**：(a) 破坏与放置**分开计**；(b) 起点 **64 / 32**（先观测再收紧）；
(c) **只读**：不加运行期覆盖命令（夹具内部构造"预算不足"场景除外）。

**口径**
| 项 | 决定 |
|---|---|
| 记账单位 | `WorldModLedger` 的 scopeId（**一次任务 = 一个作用域**；Job 就是一个任务） |
| 默认上限 | 破坏 64 / 放置 32，**分开计**（`Caps` 可按作用域覆写，仅夹具使用） |
| 豁免 | `WriteReason.SCAFFOLD_RESTORE`（回收我方临时放置）**豁免上限但仍计数** —— 否则"谁建谁拆"会被自己的预算卡死（J6 教训） |
| 无作用域 | 不设上限，但记 `[WriteBudget] no_scope`（缺口不静默） |
| 超限语义 | **硬停 + 如实报告**：`[WriteBudget] exhausted …` 警告 + 内核失败码 `WRITE_BUDGET_EXHAUSTED`；**不自动补恢复**（恢复仍按 D-103 会话内做） |
| 可观测 | 作用域收尾一行 `[WriteBudget] SUMMARY scope=… breaks=n/64 places=n/32 exempt… refused… exhausted=…` |

**执行点（不可绕过）**：写入只有一个咽喉 `BlockInteraction`
（`placeAt→setBlock`、`beginBreak→gameMode.destroyBlock`、`breakForBulkEdit/placeBulkEdit`），闸门就放在这里；
其中 `beginBreak` 被拒时**不写世界、不登记审计、不开会话**，返回 `null`，6 个调用点全部映射为
`WRITE_BUDGET_EXHAUSTED`。`placeAt` 新增 `PlaceResult.BUDGET_EXHAUSTED`（4 个调用点逐一处理；
**注意**：`PlaceStepAndTraverseExecution` 原先只判 `== NO_OPTION`，新值若不处理会被当成成功）。
`breakable(...)` 谓词用**同一个判据**（`WriteBudget.breakAllowed`），因此**搜索与执行不会各行其是**。

**已知缺口（Slice B 待做，本轮有意不做）**
1. **计划期不剪枝**：搜索不知道预算会被消耗，因此可能承诺一条超出预算的路径 → 执行到一半被拒、
   重规划、如实失败。行为是安全的（**绝不多拆**），但不够经济。
2. **尝试级预算缺席**：`MiningBudget.maxExtraBreakTicks`（6 tick/格 ×10 × 珍贵度）目前仍只用于
   规划期选站位；升级为"每次尝试允许累计消耗的破坏 tick" + 耗尽后本次尝试降级为纯通行，是 Slice B。
3. 放置上限 0 的场景只被夹具间接覆盖。

**验收夹具**：`alice:write_budget_check`（零参数右键）——复用 `break_course`（x=3 两格高石墙），
把本次任务上限压到 **1 格**，断言不变式：破坏 ≤1 / 放置 ≤0 / 墙区空气格 ≤1（**用世界事实复核，
不只信计数器**）/ 预算确实被触发 / 没穿过墙 / 通行没被判成功。

**验证等级**：WINDOWS_CLIENT + USER_ACCEPTED（2026-09-11 19:03）。
- 夹具：`[WriteBudget] CHECK breaks=1/1 places=0/0 exhausted=true wall_broken=1 passed_wall=false
  status=MOVEMENT_FAILED → PASS`（51 tick，任务 `COMPLETED`）；中间证据齐全——
  `[WriteBudget] exhausted … action=break pos=3,65,66 by=write-budget-check:attempt0:PATH_ACCESS breaks=1/1`
  → `[WRITE-REFUSED] break`（不写世界），随后 attempt1/attempt2 改走放置也被 `[WRITE-REFUSED] place` 拒绝。
- 反例守卫：`pathing_regression` 16 场景 + `foot_cell_rule` + `coverage` 全 PASS，预算实耗
  **breaks=5/64、places=7/32、refused=0**（`[WriteBudget] SUMMARY scope=…#23:PathingRegressionTask`）
  ⇒ 上限约为实际需要的 13 倍，正常路径零打扰。**这是 64/32 的实测依据**（后续按数据收紧）。
- 修复（客户端发现）：`placeAt` 的**提前拒绝**不经过 `consumePlace`，导致 SUMMARY 漏报放置拒绝次数
  （日志有 2 条 `[WRITE-REFUSED] place` 而 `refusedPlaces=0`）→ 新增 `notePlaceRefusal` 补计，
  并把 `exhausted` 警告统一到同一处。

**待办（Slice B，已由本轮日志证实必要性）**：夹具日志显示——破坏预算用满后，**重规划仍会规划
"靠放置绕行"的路线**（attempt1/attempt2 的 `STEP_PLACEMENT`），在生产上限（32）下这意味着可能烧掉
若干次放置才如实失败。⇒ Slice B：把预算并入 `PathRequest`/搜索做计划期剪枝，并让"尝试级耗尽"降级为
纯通行重规划（而不是换一种写入方式继续试）。
