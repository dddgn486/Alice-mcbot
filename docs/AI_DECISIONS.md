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

**附注（2026-09-14，`/alice authz` 暴露的语义澄清）**：**没有作用域 ⇒ 闸门未生效**，不是"上限放宽"：
`scopeOf()` 为 `null` 时 `consumeBreak/consumePlace/consumeContainerWrite` 一律 `Verdict.ALLOW`
（仅打 `[WriteBudget] no_scope` 留痕，缺口不静默）。因此三个查询口径必须一致地表达"无限"——
`remainingBreaks`/`remainingPlaces`/`remainingContainerWrites` 在无作用域时**都返回 `Integer.MAX_VALUE`**
（此前 `remainingContainerWrites` 返回默认上限 32，导致只读快照把它当成真实余量打印出来，已修）。
调用方见到 `MAX_VALUE` 的含义是"没有记账单位"，**不得**当成"额度很大"去做规划期判断。

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

**Slice B（2026-09-11 同日完成，用户"先做 G4 Slice B"）**
1. **B1 计划期剪枝**：`MovementContext.writesAllowed(type)` + `WriteBudget.plannedWritesAllowed`
   —— 任何预算桶**已耗尽**时，写边走**根本不生成**（"降级为纯通行"的实现，杜绝"换个写入方式继续试"）；
   否则按**下界**（每条写边至少 1 次）判断额度是否还够。
   `SurfaceMovementProvider` 的 5 个写边（`BREAK_AND_TRAVERSE`/`BREAK_AND_ENTER`/`DOWNWARD`/`PILLAR`/
   `PLACE_STEP_AND_TRAVERSE`）入口各加一道闸门。
2. **B2 计划级检查 + 降级**：`PathRetryRunner` 在计划到达后统计该计划的写入**下界**
   （`[PathRetry] planned … writes>=b/p`），若超过剩余额度 → `plan_write_budget_insufficient` →
   **本次运行整体降级为纯通行**（`PathRequest.pureTraversal()` = `TRAVERSE/DIAGONAL/ASCEND/DESCEND/FALL`，
   保留目标与归因；保留 `FALL` 是因为它同样不写世界，而 `PathRequest.of` 的纯通行集不含它）。
3. **为什么用下界而不是精确值**：`PlannedMovement` 不带写入计数，若按类型取"上界"（两格高墙的
   `BREAK_AND_TRAVERSE` 记 2）会把上限 1 的合法场景整条剪掉（夹具实测教训）。下界**不会误剪**，
   而"绝不超过上限"由执行期闸门（Slice A）保证——两者是分工，不是重复。

**Slice B 验证等级**：WINDOWS_CLIENT + USER_ACCEPTED（2026-09-11 19:15）。
- 夹具（同一场景、同一上限 1 格）修复前后对比：`refusedPlaces` **2 → 0**、`replans` **2 → 1**、
  任务耗时 **51 → 24 tick**，且日志里不再出现 `[WRITE-REFUSED] place`
  —— 即"破坏被拒后改规划放置绕行"这条病消失（B1 在计划期就不生成写边）。
  期望行：`CHECK breaks=1/1 places=0/0 refusedBreaks=1 refusedPlaces=0 exhausted=true
  wall_broken=1 passed_wall=false status=MOVEMENT_FAILED → PASS`。
- 反例守卫（防误剪）：回归 16 场景 + `foot_cell_rule` + `coverage` 全 PASS，
  预算实耗与 Slice A 时**完全一致**（`breaks=5/64 places=7/32 refused=0`），
  `plan_write_budget_insufficient` 出现 **0** 次 ⇒ 合法写边一条没被误剪。
- 新遥测：`[PathRetry] planned … writes>=b/p`（每个计划的写入下界；`place_course` 0/2、
  `pillar_course` 0/2、`break_course` 1/0 均符合预期）。
- 语义提醒（下界的固有性质）：上限 1 格的场景里，**第一次尝试仍会真的拆掉那 1 格**，
  之后才停下——预算是"许可"而不是"预测"，"绝不超过上限"由执行期闸门保证。

**仍待做（Slice B2）**：`MiningBudget.maxExtraBreakTicks`（6 tick/格 ×10 × 珍贵度）目前仍只用于
规划期选站位。升级为"每次尝试允许累计消耗的破坏 tick"需要先定两件事：①非挖掘类请求（带世界修改的
通行）的 tick 预算从哪里来；②它与任务级次数上限（64/32）的优先级。等有实测需求再定，避免先造口径。

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

## D-107 J7 Step 1：脚手架生命周期闭环（2026-09-11）

**用户裁定**：Step 1 范围 = **机制闭环夹具**（不碰树）；方块预算默认 **12**（砍树够用；模组超高树不管）；
拆除**先复用 `RestoreScopeTask`**（少造机制）。"一步一步来"，Step 2 = 以树干为脚手架 + 砍伐顺序耦合，
Step 3 = 接进 `LumberJob`，Step 4 = §12.4 崩溃兜底 → 之后才是 J8。

**本步实现 §11-① 的四要素（①–④）**
1. **显式授权入口**：`PathRequest.climbApproach(...)` —— 允许 `TRAVERSE/DIAGONAL/ASCEND/DESCEND/PILLAR`，
   **不含** `FALL/DOWNWARD/BREAK_*`（爬升途中不破坏、不跳下；下来属于拆除阶段走 `scaffoldRemoval`）。
   只由 Job/任务显式开启，不进任何默认集合；`miningApproach` 仍禁用 `PILLAR/FALL/DOWNWARD`。
   授权文档登记为 **A10**。
2. **方块预算**：`ScaffoldLifecycleTask.CLIMB_BUDGET = 12`，判据是**计划里 `PILLAR` 边数**（规划期先数一次）；
   超预算 → 该目标拒绝并给理由码 `climb_budget_exceeded`（另受 D-106 的 `places` 上限与快捷栏一次性方块约束）。
3. **返回保证**：柱子**就是**下行路线（拆一格 → `DOWNWARD` 落一格），不需要额外留路；
   与 D-058 的 PILLAR 返回守卫是同一思想。
4. **残留策略**：**会话内、仍在柱顶**拆除（§12.3 生命周期），复用 `RestoreScopeTask`
   （自上而下 / 只拆账本内我方 `TEMP` / `placedState` 不匹配即跳过 / 侧拆兜底）；拆不完不许静默 →
   `terminalReason=scaffold_left_behind:<剩余格数>`。

**夹具**：`alice:scaffold_check`（零参数右键）+ 场景 `scaffold_course_terrain`
（孤立长方体 x32..44 / y58..74 / z38..54；地板 y=63；目标 `stone` 浮在 (39,70,46)，
从地面**够不到**——眼位 (38.5,65.6,46.5) 到中心 ≈ 4.98 > reach 4.5，且周围没有"可走到且够得着"的站位）。
流程：搭柱爬到 (38,69,46)（5 次 `PILLAR`）→ 采高处目标 → **仍在柱顶**拆掉 → 落地。

**断言（`[Scaffold] SUMMARY`）**：`pillar=N/12`、`torn==pillar`、`remaining==0`（账本该 scope 为空）、
`residue==0`（柱列 x=38,y=64..68,z=46 全为空气——**世界事实复核**）、`target=gone`、
`on_top_at_teardown=true`、`grounded=true`。
其中 `on_top_at_teardown` 是**前提断言**：若采矿阶段自己先跑下去，拆除前提就不成立（届时应作为发现上报，
而不是悄悄走过）。

**首次客户端实测（2026-09-11 19:31，用户"bot 没有行动"）**：`[Scaffold] plan status=UNREACHABLE
movements=0 pillar=0/12` → 原地不动。**根因不在夹具几何，而在 `PILLAR` 的规划前提**：
`appendPillar`（`SurfaceMovementProvider:219-245`）每一步都要求 `hasPlacementFace(level, from)`
（当前格的水平/下方邻格里有实心块）；空旷地形里**只有第一步有面**（地板），规划期再往上没有任何方块
⇒ 链条在第二步就断。回归 `pillar_course` 之所以成立，正是因为它有**竖井基岩壁**给每一层提供放置面。
**修法（夹具侧）**：场景加一道竖壁 x=39,y=64..68,z=46 —— 既给攀爬柱每层提供放置面，壁顶
(39,69,46) 又天然**可站**，成为攀爬的合法目标脚位；目标改到壁外的 (40,70,46)
（地面 5.27 > reach 4.5，且唯一相邻可站格就是壁顶）。

**同轮发现并修掉的两个资源问题**（非 J7 逻辑）：
1. **4 个夹具物品缺模型**（`clear_guard_check`/`restore_check`/`write_budget_check`/`scaffold_check`
   —— 客户端 4 条 `Unable to load model` 警告，物品会显示紫黑）：补 `models/item/*.json`，
   统一指向 `alice:item/check`（与 `lumber_failure_check` 同款）。
2. **3 个僵尸场景**（`movement_d/e/f.mcfunction`）引用早已删除的物品
   （`movement_*_tester`）→ 客户端启动时 3 条 `Failed to load function` ERROR；全仓库无引用，已删除。

**验证等级**：IMPLEMENTED / COMPILES（客户端待复测）。

### D-107 附注：高处作业的掉落物 —— **两处扫尾**生命周期（2026-09-11，用户两轮复测后定稿）

**用户两轮观察**
1. "bot 最后没有去捡掉落物"（第一轮）；
2. "掉下去的掉落物确实在拆完后去捡了，但是有一个掉落物掉在了石头柱子上、没吸进背包，
   然后 bot 拆完柱子，掉落物又捡不到了" —— 并指出"**要重新考虑设计，避免打多重混乱的补丁**"。

**日志事实**
- 柱子材料回收正常：`作用域捕捉掉落物: cobblestone x38 y64..67 z46`（4 块）、
  `[Restore] restored=4 remaining=0 recovered=4~5`（其中 3 块沿柱下降时被自动拾取）；
- 第一轮缺的是**作业产物**：`[MineTask] collect_skipped … reason=collectDrops=false`，地上留 1 个；
- 第二轮暴露**时刻问题**：`[CollectDrops] cluster_start anchor=39,69,46 members=1` →
  `[WARN] retire reason=MOVEMENT_FAILED itemPos=39,69,46 itemY=69.000` ——
  产物先落在**作业点平台（壁柱顶面）**，物品有 `pickupDelay`（约 10 tick），
  而拆除是每格 ~14 tick 往下走 ⇒ bot 在它能被拾取之前就离开了；等拆完柱子，那块平台
  **只有脚手架才够得到**，于是永久够不到 ✗。

**设计（定稿：收集不是"事后补救"，而是"两处扫尾"）**
```
建 → 爬 → 用（作业期间**不追**掉落物：追 = 离开站位）
        ↓
   ① 就地扫尾（仍在脚手架上，anchor = 作业点）      ← 收获业点附近的产物（含拾取延迟等待）
        ↓
   ② 自上而下拆除（产物落到地面）
        ↓
   ③ 落地扫尾（anchor = 落地范围，先重开作用域并收养）
        ↓
   断言 drops_left（世界事实扫描）+ 卡住坐标必须为 0
```
- `MineTask collectDrops=false` **不是 hack**，它就是"作业期间不追掉落物"这条设计；
- `RestoreScopeTask` 自带的收尾收集 = ②的材料闭环（**保留**：语义是"拆下来的方块回到背包"，
  standalone `/alice restore` 也需要；与 ③ 的"作业产物收拢"职责不同）；
- **① 的越界风险不加新限制**：就地扫尾用纯通行移动，理论上可能自己走下去 —— 由已有的前提断言
  `on_top_at_teardown` 兜住（真走下去了直接 FAIL 并暴露，而不是悄悄改语义）；
- 收集复用同一个 `CollectDropsTask`（两处只是**时刻 + 锚点**不同），未新增机制。

**配套（已落地，非补丁）**
- `ScopeBuffer.adoptExistingDrops`（D-108）：修复"作用域重开清空登记 → 已落地产物丢失归属"；
- `drops_left` **世界事实断言**（缓冲视图 + 场景包围盒内 item 实体数）+ SUMMARY 打印
  `sweep_up= / sweep_ground= / stranded=<卡住的坐标>` —— 本轮两次失败都是它抓出来的；
- 场景复位 `kill @e[type=minecraft:item,…]` 清历史残留；夹具开始 `clear <bot>`
  （用户建议：避免背包爆满 + 让一次性方块账目干净）。

**J7 Step 2/3 必须遵守**：砍高树时产物（原木）会成批落到地面，收集同样按"① 作业点就地 + ③ 落地后"
安排；树越高越要靠这个顺序，而不是在高处追掉落物。

## D-108 作用域重开会丢失掉落物归属 → `adoptExistingDrops`（2026-09-11）

**用户复测反馈**："这次 bot 也没有去捡掉下去的目标掉落物，是你的夹具设计吧" —— 判断正确，
但根因在**更底层**：夹具只是踩到了它。

**日志事实（2026-09-11 19:54）**
```
作用域捕捉掉落物: cobblestone x40 y70 z46 source=40,70,46      ← 目标掉落物**登记过**
[Scaffold] collect_start anchor=40,64,46 live_drops=0          ← 收尾时登记表里已空
[CollectDrops] SUMMARY collected=0/0 entities=0/0 clusters=0
[Scaffold] SUMMARY … collected=0 drops_left=1 … → FAIL        ← 世界事实断言抓到了（这次运行是 FAIL）
```

**根因**：`ScopeBuffer.begin(...)` 会先调 `end()`，而 `end()` **清空 `spawnedItems` / `itemOrigins`**
（原本用意是"避免污染下一个任务的掉落物登记"，合理）。但拆除任务 `RestoreScopeTask`
**在任务中途重开作用域**（为了登记它自己拆出来的掉落物），于是"**已经登记过、此刻仍躺在世界里**"
的目标掉落物**丢了归属** → `liveDrops()`（按破坏事件配对）返回空 → 收尾收集无物可追。
⇒ 这不是夹具独有的问题：**任何"先拆完落地、再收集"的流程都会踩**（J7 Step 2/3 砍高树时会有十几根原木
掉在地上，必然撞上）。

**修法（opt-in，不改既有语义）**：新增
`ScopeBuffer.adoptExistingDrops(ServerLevel level, BlockPos center, int radius)`（D-108）——
把半径内**当前还在世界里**的存活掉落物纳入登记表（来源方块取它当前所在格），返回新收养条目数。
`begin/end` 的清空语义**保持不变**（不污染下一个任务的判断），收养由调用方在需要时显式做。

**调用方**：`ScaffoldLifecycleTask` 的 COLLECT 阶段 —— `scope.begin(落点, 8)` 后 `adopt` 再收集，
顺序仍是"**拆完落地 → 再收**"（D-107 附注的规则因此真正可用）。
**注意**：收养按**范围**生效、无法按"谁挖的"归属（掉落物实体不带该信息），因此只应在
孤立场景 / 明确属于我方的区域里使用。

**同轮采纳用户建议**：夹具开始时执行 `clear <bot>` —— ①避免 bot 背包爆满；
②让"一次性方块库存变化（`recovered`）"这类账目干净（历史余料会把账搅浑）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待复测：预期 `adopted=1`、`collected=1`、`drops_left=0 → PASS`）。

## D-109 J7 Step 2：伐木接入攀爬兜底 + 每棵树的会话内拆除（2026-09-11）

**用户裁定**："可以继续主线了"（Step 1 收口后）。Step 2 = §11-① 要素⑤（以树干为脚手架）+
要素⑥（砍伐顺序耦合）。

**关键设计结论（两个要素都按构造解决，不需要新机制）**
1. **我们爬自己的柱子，不爬树**：bot 站在自己放的方块上作业 ⇒ 要素⑥"边爬边砍时脚下的原木
   可能正被自己砍掉"**按构造消失**（脚下支撑不是目标物）✓；
2. **树干天然是放置面**：`PILLAR` 的规划前提 `hasPlacementFace`（水平/下方邻格有实心块）由
   **树干本身**满足（树干是整格方块）⇒ 要素⑤"以目标树为脚手架"**无需任何内核改动** ✓
   （Step 1 的场景里提供放置面的是一道石壁，这里换成树干，机制完全一样）。
   ⇒ 也与"不自己发明机制"一致：只新增**站位选择**（原木正旁边、与原木同高那一格）与**预算**。

**实现（`LumberJob`）**
- 每棵树的阶段：`SELECT → CHOP →（够不到时）CLIMB → ① SWEEP_UP → ② RESTORE → ③ COLLECT → 下一棵`；
- **攀爬兜底触发点**（两处，都是原本如实失败的地方）：
  ① `hasStandNow == false` 且限次清障也无解；② `MineTask` 报 `*standing_point*` /
  `no_valid` / `no_reachable` 类失败。两处都**保留 `queueIndex`**，爬完重试同一根原木；
- **预算**：`CLIMB_BUDGET = 12`（计划里的 `PILLAR` 边数）；不可行或超预算 → 如实回落 `:no_stand`
  （绝不悄悄放宽）；
- **① 就地扫尾只在"本棵树真的爬过"时执行**：没爬就没有"够不到"的问题，保持原有零打扰路径；
- **② 会话内拆除**（§12.3）仅当作用域内仍有我方 `TEMP` 放置时执行，复用 `RestoreScopeTask`
  （仍在架上 → 自上而下）。这在**会话内**第一次闭合了伐木遗留的"建拆同权"；未闭合则记
  `scaffoldLeft` 并进终态（不静默）；
- 报告：`[Job] lumber SUMMARY climbed=… climbBlocks=… scaffoldLeft=…`。

**夹具**：`alice:lumber_job` 入口新增 `clear <bot>` + 一次性方块 ×12 ——
没有一次性方块时 `PILLAR` 根本生成不出来（会如实回落到 `:no_stand`，不是缺陷但测不出攀爬）。

**验证入口**：现有 `lumber_course`（含那棵高树：矩阵记录 `failed=28,70,208:no_reachable_standing_point`，
`4.46 > reach 4.1`）——正是本次要闭合的缺口。同时必须复跑 `alice:lumber_failure_check`
（J4 五条终止路径不能被新阶段破坏）。

**首次客户端实测（2026-09-11 20:24，用户"还是没有砍高树"）**
```
[Job] step job=lumber phase=CUT   target=28, 70, 208 detail=log 7/7
[Job] step job=lumber phase=CLIMB target=28, 70, 208 detail=四周没有可站格 → 放弃攀爬
[Job] lumber 该树未完成 28, 64, 208:partial_tree gained=6/7 failed=28, 70, 208:no_reachable_standing_point
```
**根因（场景文件可查）**：顶端原木 (28,70,208) 的四邻全是 `spruce_leaves`
（`(27,69,208)`、`(29,69,208)`、`(28,69,207)`、`(28,69,209)`）⇒ 攀爬立柱的每一层都被**树冠**占住，
`climbGoalFor` 四侧全返回 null。**这才是 §11-① 要素⑥ 在 Alice 里的真实形态**：不是"踩到自己砍的
原木"（那一条已按构造消失），而是"**树冠挡住脚手架立柱**"。

**用户第二次裁定（2026-09-11 20:31，一针见血）**：
> "你是怎么判定砍伐高处原木的，为什么每次只判定'侧'？明显只需要**搭一格柱子，从下方向上砍**
> 成本最低啊。现在砍高树的站位选择没有直接接入 `MineTask` 的挖掘站位选择吗，还是说原来的系统就有问题？"

三条都成立，前两条是我的实现错了：
1. **原来的系统没问题、也没接错**：伐木用 `standable_only=true`（D-070：不许往地里挖/搭架子），
   `miningApproach` 又按 D-067 ㉘ 显式禁用 `PILLAR/FALL/DOWNWARD` —— 两条都是**有意为之**；
   缺的不是"站位选择没接上"，而是**"允许最小必要的高度增益"这个授权**（§11-① 要素①②）。
2. **我原来写成"爬到原木旁边那一格站上去"是错的**：那等于贴着树干在树冠里搭一根 5 格高柱子，
   四侧全被树叶挡住（场景文件证据：顶端原木四邻全是 `spruce_leaves`）⇒ 几乎必然失败、成本还高。
3. **正确形状 = 最小高度增益**（用户给的）：**在当前站位往上加高到该原木进入触及范围**。
   这棵树只差约 0.36 格 ⇒ **1 格**即可；加高后**bot 自己脚下的柱子顶面**就成了合法站位
   （在 reach 内、0 步可达）⇒ `MineTask` 原班站位选择立刻成功，且树越高越省。

**实现（替换，而非叠加）**：`tryGainHeight(log)` —— 目标 = `foot.above()`（+1 格）；
只检查**头顶那两格**能否钻过（被树冠挡住时只清那 1 格，共用每棵树的清障预算，
`WriteReason.STANDING_SPACE`）；计划要求恰为 **1 次 `PILLAR`**；成功后 `queueIndex` 不变 → 重试同一根；
每棵树加高上限 3 次，超限如实回落 `:no_stand`。原先"逐侧找平台 + 清整列"的实现**已删除**。

**日志依据**：`[MiningPlanner] standable_only … reason=no_reachable_standing_point`
—— 站位候选**存在**但不可达，正是"差一点点高度"的签名；加高 1 格后 bot 自身即成为候选。

**验证等级**：IMPLEMENTED / COMPILES（第三次客户端待验：期望 `[Job] step phase=GAIN …` →
`climb_end state=DONE` → 该原木 `CUT` 成功；`climbed≥1`、`scaffoldLeft=0`）。

## D-110 夹具改背包必须广播主手（单点收敛，2026-09-11）

**用户反馈**："客户端没有同步渲染 bot 手持物品的问题**又回来了**"。

**根因**：`FixtureToolKit` 直接改背包（填空格 / 强制覆盖 / 从主背包搬入），**从不广播**；
而本轮新增的 `clear <bot>`（用户建议，避免背包爆满）把背包清空后，服务端主手变为空，
客户端却继续渲染上一件物品 —— 正是 D-090 修过的那条病灶被夹具重新踩到。

**修法（收敛到一个地方，不再逐处补）**：
1. `FixtureToolKit.ensureHotbarStack` 出口**统一广播** `BotManager.syncMainHand(bot)`
   （原实现拆成 `ensureHotbarStackInternal`，公开方法包一层）；
2. 新增 `FixtureToolKit.resetInventory(bot)`：`clear <bot>` + 广播 + 日志 ——
   两个夹具入口（伐木 / 脚手架）都改调它，不再各自内联 `clear`。

**教训（与 D-089/D-099 同源）**：夹具对背包的任何修改都必须经过**同一个出口**，
否则"工具进快捷栏 / 一次性方块进快捷栏 / 主手同步"这三件事会一次次各自漏掉。

## D-111 挖掘能力信封 `MiningProfile`（切片 A：加高下沉到 L2，2026-09-11）

**用户观察（架构问题）**："`MineTask` 本来是大部分任务的低级子任务，但对不同授权的任务甚至需要重写
一部分逻辑——这个问题需要更改任务架构吗？"

**事实（可量化）**：授权信封当时散成三种形状——
| 维度 | 形状 | 位置 |
|---|---|---|
| 能不能搭架子 | `boolean standableOnly` | `MineTask` 参数 |
| 允许哪些 Movement | **6 份硬编码集合** | `PathRequest` 的 6 个工厂 |
| 清障/加高预算 | `MAX_CLEAR_PER_TREE=8`、`MAX_GAIN_PER_LOG=3`、`CLIMB_BUDGET=12` | `LumberCandidateSource` / `LumberJob` / `ScaffoldLifecycleTask`（**12 写了两份** ✗） |

⇒ 症状：每出现一种新授权需求，**调用点就要自己重写一段 L2 逻辑**。J7 的"原地加高"已经
在两个调用点各实现了一遍（脚手架夹具 + 伐木 Job）✗。

**用户裁定**：不改分层，**收敛信封**；先做切片 A。

**切片 A（本轮完成）**
1. 新增 `MiningProfile(standableOnly, maxGainSteps, gainBlockBudget)`（`task/mining/`）——
   能力白名单 + 预算，由 L3 显式构造、L2 只读消费；`STANDABLE_ONLY` / `TUNNEL_ALLOWED`
   精确对应旧的两个布尔取值 ⇒ **既有调用点行为不变**；加高产生的放置用
   `gainReason()=STANDING_SPACE` 派生归因（requester 仍取自任务的 `WriteGrant`）。
2. **"最小高度增益"下沉到 L2**（`MineTask.tryGainHeight` + `GAIN`/`GAIN_CLEAR` 阶段）：
   站位规划失败且 profile 允许时 → 原地加高 1 格 → 重新评估；头顶被树冠挡住时只清那一格
   （嵌套一个 `STANDABLE_ONLY` 的 `MineTask`）；每格 1 次 `PILLAR`；上限由 profile 给。
   成功经验经 `gainedSteps()` 上报给 L3（决定是否需要 ① 就地扫尾）。
3. `LumberJob` **删除**自己那份增益实现（含 `tryGainHeight`/`startClimbClear`/`climb()`/`CLIMB` 阶段
   与全部重复常量），改为传 `MiningProfile.STANDABLE_ONLY.withGain(3)` ✓；
   `CLIMB_BUDGET=12` 的两份重复收敛为 `MiningProfile.DEFAULT_GAIN_BLOCK_BUDGET` 一处。
4. **不动**：三层分层、D-076 默认纯通行、`WriteBudget`（执行期闸门独立）、`MiningBudget` 标定值。

**仍存的一处重复（留待切片 B/C）**：`ScaffoldLifecycleTask` 的"爬到指定作业平台"是**另一种能力**
（多步 climb + 目标平台），本轮仍由夹具自己实现（但预算已引用同一默认值）✓。

**验证入口**：`alice:lumber_job`（高树，期望 `gainedTrees≥1` / `scaffoldLeft=0`）、
`alice:lumber_failure_check`（J4 五条路径）、`alice:mine_regression`（隧道模式不受影响）、
`alice:scaffold_check`（多步 climb 不受影响）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待复测）。

## D-112 建拆同权补上挖掘路径：`MiningProfile.restoreOwnPlacements`（2026-09-11）

**触发**（客户端日志，非本次改动引入）：一次 `mine_regression` 之后
```
world_mod_ledger_close scope=…:MineRegressionTask 仍有 1 条我方临时放置未拆除（建拆同权未闭合）
```
即 **mine 路径从未拆掉自己放的临时方块**（悬空目标下方的 `SUPPORT_PLACEMENT`、接近路上的
`STEP_PLACEMENT`）。伐木侧已经闭合（每棵树 ② RESTORE ✓），挖掘侧还漏着。

**归属原则（本决策最关键的一点）**：`restoreOwnPlacements` **只能由"会话所有者"开启**。
嵌套子任务必须保持 `false`，否则子任务会拆掉所有者还要用的脚手架——两个具体反例：
- 伐木的清障/目标挖掘子任务若开启，会把 **Job 的加高柱**拆掉（人还在上面 ✗）；
- `ScaffoldLifecycleTask` 的挖掘阶段若开启，会把**爬升立柱**提前拆掉（`on_top_at_teardown` 前提被破坏 ✗）。

**时机（§12.3）**：目标挖完、掉落物收完 → **仍在架上**即刻自上而下拆 → 才允许离开；
复用 `RestoreScopeTask`（自上而下 / 只拆账本内我方 TEMP / `placedState` 不匹配即跳过 / 侧拆兜底 /
收尾材料回收）。拆不完 → `scaffoldLeft` 如实上报（不静默）。

**开启点**：`MineJob`（每个目标一次）、`MineRegressionTask`、`/alice mine` 命令路径、
`ClearGuardCheckTask`（J6-b2 夹具）。
**刻意不开启**：伐木的清障与目标挖掘（Job 的 ② 负责）、`RestoreScopeTask` 自身的挖掘（它就是拆除）、
`ScaffoldLifecycleTask` 的挖掘（夹具自己的 ② 负责）、`RoadBuildTask`（可能跨步骤依赖 TEMP 台阶，
待单独评估）。

**夹具断言随之更新（这是语义变化）**：`mine_regression` 的悬空目标用例从"**支撑块仍在**"
（改造前的语义）改为"**用完即拆**（支撑格恢复为空气）"；掉落物判据从 `scope.liveDrops()` 改为
**世界事实扫描**（拆除阶段会 `ScopeBuffer.end()` 清空缓冲，缓冲视图会变成空集 → 假通过）。

**验证入口**：`alice:mine_regression`（10 项 + 残留警告消失）、`alice:mine_job`、
回归 `alice:lumber_job` / `alice:lumber_failure_check`（嵌套路径不受影响）、`alice:clear_guard_check`。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验）。

### D-112 附注：`mine_regression` 的悬空目标期望值随之更新（2026-09-11）

客户端首测：`exec_floating=FAIL status=DONE/targetGone=true/collected=1/1/inventoryDelta=2(期望0)`
`dropsLeft=0 supportRestored=true` —— **功能正确**（支撑块用完即拆 ✓、掉落物 0 残留 ✓），
失败的是**过时的期望值**：
- 旧语义（支撑留在世界里）：放支撑 −1 ＋ 目标掉落 +1 ⇒ 净 **0**；
- 新语义（D-112 建拆同权）：放支撑 −1 ＋ 目标掉落 +1 ＋ **拆回支撑 +1** ⇒ 净 **+1**。
已把 `expectedDelta` 由 0 改为 1，并在 `CaseDef` 的 javadoc 里写明算式与"旧期望随 D-112 作废"。

## D-113 `floating_course` 竖井实际 4 格深 → 掉落物落在"站不住的格"上收不回（2026-09-11）

**触发**（同一用例第二次失败，两轮数字不一致 ⇒ 停止调期望值，改查根因）：
```
run A: exec_floating FAIL … inventoryDelta=2(期望0) dropsLeft=0 supportRestored=true
run B: exec_floating FAIL … inventoryDelta=0         dropsLeft=1 supportRestored=true
```
**日志取证（run B）**
```
[WRITE] break 23,64,190 cobblestone by=RestoreScope:SCAFFOLD_RESTORE    ← 拆支撑块
[Restore] 23,64,190 ：向下拆 不通 → 改为侧拆兜底（不挖地形）              ← 站不到正上方
[MineTask] … stand=24,65,190                                            ← 侧拆站位（站在"放置面"台阶上）
[CollectDrops] sweep_start … feet=24,65,190 worldMod=false
[PathRetry] plan_failed status=UNREACHABLE feet=24,65,190 goal=23,64,190 ← 掉落物所在格不可达
[CollectDrops] retire … itemPos=22,64,190 reason=MOVEMENT_FAILED         ← 退役 → 残留 1
[Restore] 仍有 1 个掉落物没收回（可能落在够不到的地方）
```
**根因（场景几何）**：`floating_course_terrain` 只写了 `setblock 23 63 190 air`，而下方 y=60..62
全是空气、底垫在 y=59 ⇒ 那口"1×1 竖井"**实际 4 格深**。于是：
1. 拆支撑块时 `DOWNWARD` 前提不成立（正上方站位够不到）→ 走**侧拆兜底**，bot 留在 (24,65,190)；
2. 支撑块的掉落物**掉进 4 格深井**，落点 (23,63,190) 下面是空气 ⇒ **不是可站格**；
3. 收集任务的寻路目标是"掉落物所在格"⇒ 规划器如实报 `UNREACHABLE` ⇒ 退役 ⇒ 残留。
run A 只是掉落物恰好没滑进井里 —— **不稳定场景**，不是随机 bug。

**修复（最小、根因）**：场景补 `fill 23 60 190 23 62 190 stone` ⇒ 竖井变 **1 格深**：
掉落物落在 (23,63,190)（y=62 是实心 ⇒ 可站）⇒ 可就地回收 ✓；`DOWNWARD` 拆除路径也随之可行 ✓。
案件前提不变：目标 (23,65,190) 正下方仍是空气 ⇒ 仍走"侧面站位 + 放支撑块"分支 ✓。

**登记但不本轮做（通用改进，有证据）**：`CollectDropsTask` 的寻路目标是**掉落物所在格**；
当掉落物落在"站不住的格"（1×1 井内、栅栏上、台阶边缘…）时，即使相邻就有可站格也会被判
`UNREACHABLE` 并退役。正确做法应是"**寻路到够得着它的可站格**"（拾取半径 ≈1 格）。
本轮不顺手改（会动到已验收的收集任务），登记为待办，等有第二个实例再一起做。

**验证等级**：IMPLEMENTED（场景修复已入库；客户端复测一次 `alice:mine_regression` 即可）。

## D-114 收集的寻路目标改为"够得着掉落物的可站格"（2026-09-11，用户裁定方案 A）

**背景**：`mine_regression` 的 `exec_floating` 用例**三次失败、三种数字**（`delta=2/drops=0`、
`delta=0/drops=1`、`delta=0/drops=1`）。前两轮我去调夹具期望值与场景几何 ✗——**都调错了地方**。
第三轮取证才拿到根因（日志逐行）：

```
[Restore] block 23,64,190 placed=cobblestone            ← 要拆的支撑块
[Restore] 23,64,190 ：向下拆 不通 → 改为侧拆兜底          ← 正确（落点站不住，内核拒绝 DOWNWARD）
[WRITE] break 23,64,190 cobblestone ✓                   ← 支撑块确实拆掉了（建拆同权 ✓）
[Restore] 开始回收材料 origin=23,64,190（restored=1）
[CollectDrops] sweep_start anchor=23,64,190 … feet=24,65,190
[PathRetry] plan_failed UNREACHABLE feet=24,65,190 goal=23,64,190   ← 要走到"刚拆掉的支撑块那格"
[CollectDrops] retire item=… itemPos=23,64,189 reason=MOVEMENT_FAILED  ← 物品其实停在**可站**的平台格
[Restore] 仍有 1 个掉落物没收回
```

**根因（真缺陷，不是夹具写法）**：`CollectDropsTask` 的**寻路目标**是
「锚点 = 最近掉落物的 `blockPosition()`」（初始锚点取自构造参数 `origin`，如"被恢复方块的位置"）。
当该格**不可站**时（刚拆掉的支撑块所在格 = 空气 + 下方也是空气、1×1 竖井口、台阶边缘、悬空块上方…）
规划器如实报 `UNREACHABLE`（它不会为"走到一个站不住的格"编路径）⇒ 物品退役 ⇒ **材料留在世界里**。
反例规律：**锚点格可站 ⇔ 被拆/被挖的方块下方有实心地面**——所以"支撑块放在地面上"的用例一直好使，
而"悬空目标 + 支撑块放在井口"的用例必然踩到。

**第二次独立实例**：J7 那次"掉落物停在壁柱顶收不回"（同属"寻路目标选错格"这一类，已由两处扫尾缓和）。

**修法（用户裁定方案 A，最小面）**：新增 `normalizeAnchor(members)`——
1. 掉落物所在格**可站**（`StandingPointSelector.isStandable` 同口径）⇒ 原样使用（**今天的行为完全不变**）；
2. 否则在它周围（水平 4 邻、`y ∈ {0,-1}`，先半径 1 再半径 2）找**最近的可站格**，且与掉落物在拾取半径内
   （粗判 `withinPickupReach`；精确判定仍走原 `inPickupRange`）；命中即把锚点换成它；
3. 一个都没有 ⇒ 保留原格（失败码保持诚实）→ 照旧退役。
调用点：起始 sweep（建 runner 之前）+ `reanchor`。新增日志
`[CollectDrops] goal_shift item=… itemPos=… goal=…` 供判读。

**不改**：收集判据（`inPickupRange` 原版拾取盒）、预算、退役语义、`worldMod` 策略 ✓。

**本修法不覆盖的情形（保持诚实）**：目标格**可站但路径不可达**（J7 壁柱顶那种）——
那属于"够得到的时刻"问题，由 D-107 附注的**两处扫尾**负责，不在这里兜。

**验证入口**：`alice:mine_regression`（`exec_floating` 应转绿、10/10）；回归
`alice:pathing_regression`、`/function alice_test:lumber_course` → `alice:lumber_job`（收集口径不变）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验）。

### D-114 附注：支撑类用例的"精确净增量"不是不变量（2026-09-11 第四轮取证）

**用户复测又失败**：`exec_floating=FAIL … inventoryDelta=2 dropsLeft=0 supportRestored=true collected=1/1`
—— **行为完全正确**（世界干净、支撑块拆掉、两个掉落物都收进背包），失败只在**精确净增量**上 ✗。
且本轮 `goal_shift` 出现 **0 次** ⇒ D-114 的修复**没被触发**：这次掉落物恰好落在拾取范围内，
不需要走位（修复仍需要——B/C 两轮就是"落得远且锚点不可站"）。

**为什么"精确净增量"不是不变量**（本轮查实两个混淆项）：
1. **发料在基线之后**：夹具注释写着"构造 MineTask 后再补一次性方块（避免被镐覆盖选中槽）"，
   而基线在补料**之前**采集 ⇒ bot 手头圆石 <8 时，补的料直接进 `inventoryDelta`（本次日志是
   "已有 20（≥8）"所以没补，换个状态就漂移）；
2. **放置消耗 + 两次回收混在一起**：`−1(放支撑) ＋1(目标掉落) ＋1(拆回支撑)`，实测稳定值是 **+2**
   —— 其中多出来的 +1 我**没有完全对上**（这正是它不该当断言的理由）。

**修法（断言测不变量，不测混杂物）**
- 夹具顺序修正：构造 `MineTask`（补镐）→ **补一次性方块** → **再采基线** ⇒ 之后的净增量只反映真实事件；
- 支撑类用例的**材料闭环**改用**账本事实**：`mineTask.restoredBlocks() ≥ 1 && scaffoldLeft() == 0`
  （直接证明"自己放的方块确实拆回来了、且没留残"，比背包净增量稳得多）；
- 精确净增量**只保留在不涉及支撑块的用例**（`exec_direct`/`exec_blocked`/`exec_chain`，那里净增量=掉落物本身）；
  支撑类用例判"`collected ≥ 期望` && `delta ≥ 期望`"（下界=产物确实回来了）；
- 明细行新增 `/ledgerRestored=N/scaffoldLeft=M` 供判读。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验）。

### D-112 / D-114 客户端验收（2026-09-11 21:36，用户"测试通过了，顺便做了其他几个回归"）

| 夹具 | 结果 |
|---|---|
| `alice:mine_regression` | **10/10 PASS**（`ticks=178`）；`exec_floating=PASS status=DONE/targetGone=true/collected=1/1/inventoryDelta=1/dropsLeft=0/supportRestored=true/**ledgerRestored=1/scaffoldLeft=0**` |
| `alice:lumber_failure_check` | **5/5 PASS**（`no_candidates`/`all_rejected`/`inventory_full`/`goal_timeout(41 tick)`/`log_replaced(仍为圆石=true)`） |
| `alice:mine_job`（`ore_course`） | `MineJob … terminal=COMPLETED code=done`（239 tick）；`WriteBudget SUMMARY … breaks=4/64 places=0/32` |
| `/function alice_test:lumber_course` → `alice:lumber_job` | `[Job] lumber SUMMARY gainedTrees=1 gainedBlocks=1 scaffoldLeft=0`（高树靠**L2 的原地加高**完成 ✓） |
| **残留类告警** | `仍有 N 条我方临时放置未拆除` = **0**、`仍有 X 个掉落物没收回` = **0** ✓（D-112 建拆同权在挖掘路径上闭合 ✓） |
| `alice:pathing_regression` | 本轮**未跑**（不冒认） |

**两条额外证据（写进记录，避免以后又走回头路）**
1. `exec_floating` 的 `inventoryDelta` 在本轮是 **1**，而上一轮是 **2** ⇒ **精确净增量确实不是不变量** ✓
   —— D-114 附注里"改判账本事实 + 下界"的决定因此被实测证实 ✓。
2. **`[CollectDrops] goal_shift item=… itemPos=28,70,207 goal=27,69,207`** 在**伐木**路径上真实触发了一次 ✓
   —— 高树顶端原木的掉落物停在树冠里"站不住的格"，D-114 把寻路目标改到旁边**可站格**后收回 ✓✓
   （这正是它设计要解决的那一类；不是只在夹具里生效 ✓）。

## D-115 切片 B1：限次清障能力下沉 L2（`MiningProfile.clearBudget`）（2026-09-11）

**用户裁定**："做切片 B"。切片 B 的目标是让"新授权不再需要在调用点写逻辑"。
本轮先做 **B1（清障）**，B2（把 `ScaffoldLifecycleTask` 的多步 climb 也收进信封）留下一轮。

**事实（用户最初指出的病根）**：伐木 Job 自己实现了**两套**清障——
规划期 `BlockerClearPlanner.nextClearStep`（腾站位/通视线）+ 运行期 `LINE_OF_SIGHT_BLOCKED` 兜底——
任何新任务想清障都得重写一遍 ✗。

**B1 实现**
1. `MiningProfile` 增加 `clearBudget` + `mayClear()` + `withClear(n)`（0 = 不允许清障）；
2. `BlockerClearPlanner` 从 `job/lumber/` **迁到 `task/mining/`**（它本来就只依赖 mining 包的
   `LineOfSightChecker`/`StandingPointSelector`/`MiningTuning` ⇒ 归位合理，且避免 L2 反向依赖 L3）；
3. `MineTask` 增加 `CLEAR` 阶段与两个触发点：
   - **规划失败**（站位类原因）→ `tryClear()` → 清掉"最该清的一格"→ 重试；
   - **运行期 `LINE_OF_SIGHT_BLOCKED`** → `tryClearLineOfSight()` → 清阻塞物 → 重试；
   预算用 `clearBudget` 递减；**顺序固定为"先清障、后加高"**（与客户端已验证的伐木行为一致）；
   新增汇报 `MineTask.clearedBlocks()` 与日志 `clear_start/clear_end`；
4. `LumberJob` **删除**两套清障实现（含 `clearTask`、`hasStandNow`、运行期 LOS 分支），
   改为：每根原木声明 `TARGET_PROFILE.withClear(8 - clearedThisTree)`（**每棵树 8 格的预算
   仍由 Job 决定**，只是执行交给 L2）+ 从 `miner.clearedBlocks()` 逐树记账（`cleared=` 报告不变）。

**行为面**：清障仍走**同一个** `BlockerClearPlanner`、**同一个** `LINE_OF_SIGHT` 理由、**同一个**
每棵树 8 格预算 ⇒ 期望行为不变；唯一可观察差异：失败码由 Job 自造的
`:clear_budget` / `:clear_failed` 变为 L2 上报的站点类原因（更诚实，文档同步更新）。

**评估后本轮不做（写清理由，避免被当遗漏）**：把 6 个 `PathRequest` 工厂的 Movement 集合
"由 profile 派生"——它们各自是一行、且编码的是**清晰不同的策略**（只通行 / 可破坏 / 可搭 / 只拆向下…），
派生只会把同样的信息换个地方写、并新增 5 个能力开关，收益不足以抵消复杂度 ⇒ **暂不派生**。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验：`lumber_job`（高树 + 清障）、
`lumber_failure_check`（5/5）、`mine_regression`（10/10，非伐木路径不受影响））。

### D-115 附注：B1 引入的回归与修正（2026-09-11，用户"路线完全不一样了 + 没有切换工具很慢"）

**用户观察**：砍树回归路线完全不一样、感觉怪异；而且"没有切换工具导致速度很慢"。日志把两条都定量坐实了：

```
ticks=6   ×18   ← 树叶
ticks=8   ×20   ← 原木（斧子 ✓ 工具切换本身是好的）
ticks=201/206/207 各 ×10  ← **共 30 次徒手挖 2.0 硬度方块（≈200 tick/块）**
clear_end status=FAILED cleared=0 used=1/8 … 同一个 blocker 重试到 used=8/8（8× 三个不同 blocker）
[Job] lumber SUMMARY gainedTrees=3 gainedBlocks=10 scaffoldLeft=0     ← 改造前高云杉只搭 1 格
```

**回归一：清障失败被反复重试**（我引入）✗
B1 把清障搬进 L2 时，我漏了改造前 Job 的一条关键行为：**清障失败就直接放弃这根原木**。
新实现失败后仍会去问 `nextClearStep`，它给出**同一个** blocker ⇒ 重试 8 次烧光整棵树的预算。
**修正**：新增 `clearExhausted` —— 清障一旦失败，本目标不再清障（并打进 `clear_end` 日志）。

**回归二：把"二选一"写成了"串联"**（我引入）✗✗ 这是"路线怪异 + 搭 10 格柱子"的直接原因。
改造前的伐木是**二选一**：`hasStandNow==false` ⇒ 清障（失败即放弃）；`hasStandNow==true` ⇒ 正常挖，
挖不动才**加高**。我在 B1 里写成了"先清障、加高其次"的串联 ⇒ 本该放弃的树也去搭柱子。
**修正**：恢复二选一 —— **触及范围内有站位候选但路径不通 ⇒ 加高**；**没有候选 ⇒ 清障**
（`hasStandingCandidateNow()` 就是改造前 `hasStandNow` 的口径）。

**回归三：徒手拆自己搭的柱子**（夹具缺料，非本次逻辑）✗
伐木夹具只发斧子（D-089），而 J7 起它会搭/拆**圆石**柱 ⇒ 没有镐就是 200 tick/块。
**修正**：夹具补发**石镐**（`ensurePickaxe`）。
**登记为待评估（生产侧，非测试侧）**：真实环境的伐木 Job 若允许搭脚手架，应当能感知
"手上没有合适的拆除工具"（否则拆除阶段会慢 30 倍）。属于 J7 Step 3/4 的失败/工具语义范围，
下轮与"工具选择系统"一起评估，本轮只修夹具。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验：`lumber_job` 路线与耗时回到改造前水平、
高树仍靠加高完成且只搭必要格数；`lumber_failure_check` 5/5；`mine_regression` 10/10）。

## D-116 收集复用"加高"能力：`GainStepRunner` + `CollectDropsTask` 可选信封（2026-09-11）

**用户裁定**："让收集复用'加高'能力"。

**背景（D-114 明确不覆盖的那一类）**：高云杉第 7 根原木的掉落物停在**树冠里 (27,70,207)**，
而 bot 站在自己柱子顶（脚位 65，低 5 格）⇒ ① 就地扫尾纯走位直接 `MOVEMENT_FAILED` ⇒ 退役 ⇒
`28,64,208:product_not_collected gained=6/7`（7 根全砍完却算未完成）。D-114 只解决"目标格**不可站**"，
这一类是"目标格可站、但**路径不可达**"。

**实现（不新增机制，并把重复消灭在一处）**
1. 新增 `task/mining/GainStepRunner`：**共享**的"原地加高 1 格"执行器（规划 `foot → foot.above()`、
   要求恰好 1 次 `PILLAR`、按 `MiningProfile.gainBlockBudget` 限块、逐步推进、统一日志）。
   `MineTask` 原先内联的那份实现改为使用它 —— 否则"加高"就有两份实现，正是用户反复指出的病灶。
2. `CollectDropsTask` 增加**可选** `MiningProfile gainProfile`（默认 `STANDABLE_ONLY` = 不允许加高
   ⇒ **既有调用点行为完全不变**）：走位失败时，若最近的掉落物**明显在头顶**且预算允许 ⇒
   加高 1 格 → 重锚重试走位；用尽或不可行才退役（日志 `gain_start/gain_done/gain_failed`）。
3. `LumberJob` 的 **① 就地扫尾**启用加高（≤8 格，方块预算沿用默认 12）；
   **③ 落地扫尾刻意不给**——它在 ② 拆除**之后**，产生的放置没人收回（会留残）。
   ① 的加高放置落在同一作用域 ⇒ 紧随其后的 **② 建拆同权**一并收回 ✓（归属用 `STEP_PLACEMENT`，
   与内核 `PILLAR` 一致）。
4. 与 D-114 的分工写清：**D-114 横着挪（换可站格）；D-116 往上抬（加高够）**，两个不同手段。

**验证入口**：`/function alice_test:lumber_course` → `alice:lumber_job`（期望高云杉
`chopped=7/7 … gained=7/7`，不再 `product_not_collected`）；`alice:mine_regression`（默认信封不变，
仍 10/10）；`alice:lumber_failure_check`（5/5）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验）。

### D-116 附注（2026-09-11 客户端回归实测：① 就地扫尾被**静默跳过**）

**实测现象**（`/function alice_test:lumber_course` → `alice:lumber_job`，两次独立运行同症状）：
高云杉 `28,64,208` 砍完 7/7、末根触发加高 `steps=1/3`（`gained=1`），但日志
**零** `[Job] lumber sweep_up_start/sweep_up_end`，最后一个原木刚破完 150 ms 就直接
`[Job] lumber restore_start … pending=1（仍在架上拆除）`（=②），随后 `phase=COLLECT … gained=1`，
结算 `28,64,208:product_not_collected gained=6/7`。

**事实核对（先排除产物嫌疑）**：客户端 `mods/alice-*.jar` 与本地 `build/libs/` 的 md5 完全一致；
对该 jar 内 `LumberJob.class` 反汇编确认 `advanceAfterChop()` 的 ① 门槛就是
`gainedThisTree > 0 && !sweptUpThisTree` → `Phase.SWEEP_UP`，`tick()` 的 switch 也确实是
`SWEEP_UP → sweepUp()`。⇒ 不是旧产物，是逻辑真跳过了 ①。

**根因（两处，同一处代码里叠加；README 记为"阶段局部状态写成全局状态"）**
1. `sweepUp()` 用 job **全局**计数器 `ticks`（`tick()` 里 `++ticks > spec.maxTicks()` 的那个）
   当 ① 的阶段预算：跑到第 4 棵树时它已是几千 ⇒ `++ticks > SWEEP_UP_BUDGET_TICKS + 40 (240)`
   **首帧就成立**，走的是**唯一没有日志**的超时分支（`sweptUpThisTree = true; return advanceAfterChop();`）。
2. `collectPhase()` 收完**不置空** `collector`，而 `sweepUp()` 又用 `collector == null` 表示"① 还没开工"
   ⇒ 第二棵树起，`collector` 是上一棵树 ③ 留下的**僵尸任务**，"开工分支"（含 `sweep_up_start`
   日志）永远进不去，直接落到上面那个静默超时分支。

两者叠加 = ① 一次都没跑、一行日志都不留、`sweptUpThisTree` 被置真后正常掉进 ② —— 与日志逐行吻合
（含"末根破完 → 150 ms 后 restore_start"这一帧间距）。

**修正**（最小、无新机制）
- ① 用**自己的**阶段状态：`sweepStarted` + `sweepTicks`（`select()` 里逐树重置），不再碰 `ticks`；
- 超时分支补日志 `[Job] lumber sweep_up_timeout ticks=…`（"best-effort 放弃"不能再无声无息）；
- `collectPhase()` 收完 `collector = null`（阶段用完即弃，杜绝跨树复用）。

**待验的判别点（下一次客户端测试直接看这一行）**：① 开工日志里的 `live_drops=N`。
- `live_drops≥1` 且随后 `sweep_up_end swept=…` 收掉 ⇒ 第 7 根原木的掉落物一直在（停在树冠
  `27,70,207`），① 补收即闭合，`gained=7/7`；
- `live_drops=0` ⇒ 掉落物在 ② 之前就已不在世界/作用域里（③ 的 `entities=0/0` 也印证），
  那是**另一条**根因（加高时把方块放在**掉落物所在格**上、② 再拆掉它的后果），
  下一轮单独定位，不在这轮修。

**验证等级**：IMPLEMENTED / COMPILES / SERVER_TESTED（症状与根因均已由日志+反汇编确认）；
客户端待验如上判别点。

### D-116 附注二（2026-09-11 客户端复测：① 已生效；② 暴露"假 scaffoldLeft"与"夹具补工具顶掉物品"）

**采样**：客户端 23:04–23:11（jar `afdaf9a3…`，与本地 build md5 一致）。

**① 就地扫尾 ✅ 达成 D-116 目标**
```
23:08:12.212 [Job] lumber sweep_up_start foot=28,65,208 live_drops=1（仍在架上）
23:08:12.512 [CollectDrops] goal_shift itemPos=27,70,207 → goal=27,69,206
23:08:12.514 [CollectDrops] gain_start steps=1/8 → PILLAR 65→66（放柱 28,65,208）
23:08:13.412 [CollectDrops] gain_start steps=2/8 → 66→67
23:08:13.612 [CollectDrops] entity_gone → cluster_done delta=1 → SUMMARY collected=1/1
23:08:13.664 [Job] lumber sweep_up_end swept=1 live_drops=0
```
高云杉 `28,64,208` 从 `product_not_collected gained=6/7` 变成 **7/7 且不再报"该树未完成"**；
`inventoryDelta` 14→**15**（多出的正是树冠那根）；`trees 3/4`（历轮最好：21:35/21:54/22:04 均 2/4）。
**D-114+D-116 的分工实测成立**：D-114 `goal_shift` 先换到可站格 → 走不通 → D-116 `gain` 原地加高够到。

**F2：假 `scaffoldLeft=1`（本轮修）**
链条：① 收到物品时把 bot 留在 `PILLAR` 起跳的半空（第 2 步 `gain_start steps=2/8` 后**没有** `segment_done`）
⇒ ② 对 `28,65,208` 的"走上正上方"当帧 `UNREACHABLE`（`feet=28,67,208`）、侧拆兜底 `no_reachable_standing_point`
⇒ `skipped=1`；紧接着拆**下一块** `28,64,208` 时用 `DOWNWARD` 把 `28,65,208` 当支撑顺带拆掉
（`[Downward] support_broken pos=28,65,208`）⇒ 世界已干净。
**病灶**：`RestoreScopeTask.finish()` 直接拿账本数当 `remaining`，而 `WorldModLedger.dropStale()`
（"现场已非我方方块 → 销账"）只在 `buildQueue()` 开头跑一次 ⇒ 幽灵条目让 Job 报
`scaffold_left(1)` 并 FAILED，直到**下一棵树**的 ② 才 `[Ledger] 销掉 1 条已失效条目 …(cobblestone→air)`。
**修正**：收尾先 `dropStale` 再算 `remaining`（从此 `remaining` = 世界事实）；新增 `reconciled`
计数与对账 WARN；终态同口径 —— `remaining==0` 且失败仅来自 `skipped` ⇒ 改判
`restore_done_by_other_action`/DONE（超时类失败不受影响，仍 FAILED）。
顺带修掉同类"报告不等于事实"：队列为空（`nothing_to_restore`）时 `recovered` 不再报成整个背包的
一次性方块数（实测下一棵树曾报 `recovered=2`，其实一块都没收）。

**F3：`MineTask.ensureTool` 顶掉背包物品（本轮修）**
`recovered=-8` 是硬证据：基线 10（12 − 2 次放置）− 被抹 10 + 收回 2 = 2。全程 `writes places=2`
（WriteAudit 真值）⇒ 没有任何"看不见的放置"能消耗它。唯一能整栈覆盖的是
`ensureTool` 的 `inventory.setItem(inventory.selected, DIAMOND_PICKAXE)`：`PILLAR` 放置后内核把
**选中槽**留在圆石栈上 ⇒ 整栈被镐子覆盖抹掉（10 个圆石），且它绕过了 D-110 定的唯一写入点
`FixtureToolKit`（那里"塞回背包、塞不下才告警"）。
**修正**：改走 `FixtureToolKit.ensureHotbarTool`（优先填空格，无空格才覆盖**非选中**格并塞回旧物）；
工具选择本就由内核负责（`BlockInteraction.findBestToolSlot` + `selectSlot`），不必占选中槽。
非 bot 的 `ServerPlayer` 一律不写背包。

**F4：19/24 两棵橡树砍不动 —— 旧问题，只登记不改**
本轮 `failed=…no_reachable_standing_point`×4；归档对照：21:35 `clear_failed`×4、21:54/22:04 同类失败。
链条：`clear_start blocker=19,65,215 used=1/8` → 清障子任务自身 `no_reachable_standing_point`
→ `clear_end status=FAILED cleared=0 exhausted=true`（子任务 `gain=none clear=none`，不做嵌套恢复）。
它决定 `partial_quota`（`trees 3/4`），与 F2/F3 无因果。**待评估**：清障是否允许对 blocker 加高/嵌套。

**验证等级**：F2/F3 = IMPLEMENTED / COMPILES；客户端复测待跑（判据见测试矩阵 D-116 行）。

### D-116 附注三（2026-09-11 23:34 复测：不再假报 scaffoldLeft；两轮差异=树叶腐烂时机）

**本轮事实**（客户端 23:33–23:34，jar `bb9929e4d9…`）
```
23:34:12.334 [Job] lumber sweep_up_start foot=28,65,208 live_drops=1（仍在架上）
23:34:13.084 [CollectDrops] entity_gone → cluster_done delta=1 → SUMMARY collected=1/1
23:34:13.133 [Job] lumber sweep_up_end swept=1 live_drops=0
23:34:13.237 [Restore] start … blocks=1（自上而下：站上去 → 向下拆）
23:34:14.333 [Restore] SUMMARY restored=1 skipped=0 reconciled=0 remaining=0 recovered=1 → DONE
23:34:20.439 [Job] terminal job=lumber result=FAILED reason=partial_quota
             progress=trees 3/4 logs 15/23 inventoryDelta=15 gainedTrees=1 gainedBlocks=1
                      scaffoldLeft=0 writes breaks=22 places=1 unknown=0
```
- `① 就地扫尾` 再次生效；高云杉 7/7（连续第三轮），`inventoryDelta=15`。
- ② **本轮走的是正常路径**（`skipped=0`），**不含** `scaffold_left`，终态只剩 `partial_quota`；
  `recovered=1` 与"放 1 收 1"完全对账（F3 未再出现整栈被抹）。
- 诚实边界：**上一轮触发 `reconciled` 的条件是随机出现的，本轮没复现** ⇒ 该对账分支
  （`reconciled>0 → restore_done_by_other_action`）仍属**未在客户端验证**，已登记待复现。

**两轮差异的根因（**世界事实**，与客户端物理无关）**：掉落物是否停在树冠 `27,70,207`，取决于
**树叶随机刻腐烂**的时机。顶部原木破掉时若树叶还在 → 掉落物停在树冠（22:44/22:46/23:08，需要 ①
加高上去拿）；若树叶已腐烂 → 掉落物顺柱落下（23:34，① 原地即可收）。23:08 轮日志里
`作用域捕捉掉落物: spruce_sapling/stick x27 y71 z208 source=unpaired` 就是腐烂产物
（`unpaired` = 非我方破坏产生）——这是同一机制的另一面证据。
⇒ **测试场景的这个分支天生带随机性**：要覆盖 ① 的加高路径与 ② 的对账路径，需要多轮或
更确定的场景构造（登记为夹具改进项，不属本轮）。

**F4 仍是唯一未过的原因**（`partial_quota`，19/24 橡树），与 F2/F3 无因果。

### D-117 F4 根因 = 夹具几何（树冠封死唯一通道），非代码缺陷（2026-09-11）

**现象**：`/function alice_test:lumber_course` → `alice:lumber_job` 每轮 `trees 3/4`，
`19,64,213` / `24,64,213` 四根原木全部 `no_reachable_standing_point`（`clear_start` 后清障子任务自己也
`no_reachable_standing_point`，`clear_end … cleared=0 used=1/8 exhausted=true`）。

**"能用 vs 不能用"对照（关键证据）**
- 工作基线 2026-09-10 **21:26**（`trees 3/3 logs 12/12 cleared=8`，J3 验收）砍的正是
  `20,64,208 → 19,64,213 → 29,64,213` ⇒ **19 当时能砍**，场景候选只有 4 棵（无 `24,64,213`）。
- 21:25 的日志给出那条成功路线：`[MiningPlanner] target=19,65,215 … chosen=22,64,216 cost=21.33 pathSize=21`
  → `[R4 Session] … type=TRAVERSE`（纯走位）：**北上 z=205 → 东到 x=22 → 南下穿墙到 z=216**。
- 场景变更：`a5901f5`（09-10 **21:28**，"配额提到 4 棵 + 第 4 棵橡树"）在 `24,64,213` 种下整棵橡树，
  其树冠包含 `22,65,212/213/214`、`22,66,211..214`。
- 几何事实（直接数场景函数）：y=65 的 z=212..214 上树冠连成 **x=17..31** 的墙，而可行走台地只有
  **x=18..30**（`terrain: fill 18 59 204 30 62 230`）⇒ **x=22 是全墙唯一 2 格高通道**；
  4 号橡树把 `x=22` 占掉后通道整条封死。09-10 21:28 之后**每一轮** 19/24 都失败（归档 21:35 / 21:54 /
  22:04 / 22:44 / 23:08 / 23:34 全部一致）——**时间线与几何完全吻合，与 B1/D-116 等代码改动无关**
  （21:09 那轮的 `clear_failed` 是脏场景，不作对照）。

**修正（仅夹具）**：4 号橡树的树叶不再占用 `x=22` 列（删 7 个 `setblock`：`22,65,212/213/214` +
`22,66,211..214`），树冠整体留在 `x=23..26`；通道恢复为 09-10 已验证的几何（x=22 整列贯通 z=211..215）。
文件头写入**通道红线**（含地形范围与工作基线路线），并把"三棵同型橡树"更正为四棵。
客户端数据包已同步（`saves/新的世界/datapacks/alice_test/…/lumber_course_trees.mcfunction`，需 `/reload`）。

**教训（写进夹具规范）**：夹具加树/加地形时必须检查**唯一通道**——"与既有树净空 5 格"只算了 *树干*
间距，没算 *树冠* 与**通行高度**；场景虽满足"孤立长方体"，却在内部自封航线。
`a5901f5` 的验收只写了 `COMPILES`（配额 4 的轮次从未跑过），所以这个自封一直没被发现。

**验证等级**：IMPLEMENTED（夹具）/ COMPILES；客户端待验（判据：`trees 4/4`、19/24 的 `clear_start`
能成功清掉 `19,65,215`、终态 `DONE quota_met`）。

### D-117 附注（2026-09-11 23:47 复测：**撤回第 4 棵橡树**；通道红线修正为 x=22..26）

**复测事实**：`/reload` 已生效（23:47:17 datapack 重载），世界确实变了 —— 树 24 的首选清障块从
`22,65,212` 变成 `23,65,213`（planner 只能选真实存在的方块）⇒ 我删掉的 `x=22` 那列已空气。**但 19 仍失败**。

**为什么删 x=22 不够**：把工作基线 21:25 的路线逐段打出来才看清 ——
`(20,208)→(19,208)→(19,207)→(18,207)→(18,206)→(18,205)→…→(22,205)→(23,205)→(23,206)→…`
即它**南下走的是 x=23**，不是 x=22。`a5901f5` 把 **x=22 与 x=23 两列一起**占掉了（它的树冠 x=22..26），
所以只补回 x=22 仍然不够（1 格宽、且不是原路线那一列）。

**根因（夹具设计约束，写明进文件头）**：这排树冠在 y=65 的 z=212..214 上构成 x=17..31 的墙，
可行走台地只有 x=18..30（`fill 18 59 204 30 62 230`）⇒ **x=22..26 是唯一缺口/唯一通道**。
**任何"带树冠的新树"种进这段都会把它堵死**（树冠必然占住 y=65 的通行高度），
所以"再加一棵同型橡树"这条路在本场景里**根本不可行**。

**修正**：**撤回第 4 棵橡树**（`lumber_course_trees.mcfunction` 已与 `a5901f5^` 几何完全一致，
只余注释差异 —— 即本会话验证过"能砍 19"的那套场景），配额仍为 4 棵。
累计清障 >8 的目标改由三棵同型橡树 + 云杉凑够：20(3) + 19(3) + 28 云杉(3) = **9 ⇒ 第 3 棵就跨阈值**
（若清障计数器是 job 级，第 3 棵必 `clear_budget` 失败）—— 夹具用途不受影响。
文件头保留**通道红线**（含台地范围、工作基线路线、以及"要加树必须先外扩台地"）。
场景说明文字同步改回"3 棵同型橡树 + 云杉"。客户端数据包已同步（需 `/reload`）。

**验证等级**：IMPLEMENTED（夹具，几何已 diff 证明等于已验证版本）；客户端待验
（判据：`19,64,213` 的 `clear_start blocker=19,65,215` 能真的清掉 → `phase=CUT` 正常开挖；
终态 `DONE quota_met trees 4/4`、`cleared≥9`、`scaffoldLeft=0`）。

### D-118 伐木全流程转绿 + F2/F3 客户端验证（2026-09-11 23:55–23:56，两轮连续）

**两轮都是**：`[Job] terminal job=lumber result=DONE reason=quota_met progress=trees 4/4 logs 19/19
cleared=8 inventoryDelta=19 gainedTrees=1 gainedBlocks=1 scaffoldLeft=0 writes breaks=28/58 places=1/4
unknown=0 ticks=634/691`（writes 为**会话累计**，故第二轮更大）。`task_execution_terminal … terminal=COMPLETED
code=done recovery=idle_after_cleanup recoveryEvents=[]` ✅

**F4（夹具通道）** —— 修好，判据全部命中
```
23:55:21 clear_start target=19,64,213 blocker=19,65,215 used=1/8
23:55:25 [WRITE] break 19,65,215 minecraft:oak_leaves by=lumber:LINE_OF_SIGHT   ← 这一格就是历轮卡死点
23:55:27 clear_start target=19,65,213 blocker=19,65,214 → 清掉
23:55:29 phase=COLLECT target=19,64,213 detail=chopped=4/4 failed=0 cleared=5
```
逐树：20(`18,65,207`/`19,65,207`/`19,65,208`，3 格) → 19(`19,65,215`/`19,65,214`，2 格) →
29(`27,65,212`/`28,65,212`/`28,65,213`，3 格) → 28 云杉（`gain_start steps=1/3` + ① 扫尾
`sweep_up_start live_drops=1 → swept=1`，7/7）。19/19 原木、`inventoryDelta=19` 全数入袋。

**F2（② 世界事实对账）** —— **第二轮真的走到对账分支了** ✅
```
23:56:28.210 [Ledger] 销掉 1 条已失效条目（现场已非我方方块）: 28,66,208(cobblestone→air)
23:56:28.210 [Restore] SUMMARY … restored=2 skipped=1 reconciled=1 remaining=0 recovered=3
             ticks=57 reason=restore_done_by_other_action → DONE
```
即："协议没亲手拆、但现场已无我方方块"被如实识别为 `reconciled=1`、终态 `DONE`（旧代码在这里会报
`remaining=1 → FAILED` + `scaffoldLeft=1`），而 Job 终态 `scaffoldLeft=0` ✅ —— 该分支从此**有客户端证据**。

**F3（补工具不顶物品）** —— `recovered=1`（第一轮：放 1 收 1）与 `recovered=3`（第二轮，累计放 4 收 3，
差额为已回收进背包/自动拾取），**再无负值**（历史病态 `-8`），19/19 原木与背包对账一致 ✅。
未再出现 `[FixtureTool] … 强制覆盖`。

**残留缺口（如实登记，未修）**：累计清障 **正好 8 格**（20:3 + 19:2 + 29:3 + 云杉:0），
而矩阵该行原期望 `cleared ≥ 9` —— 目的是"越过 `MAX_CLEAR_PER_TREE(8)` 阈值，让**按棵重置**这一回归
真正被检验"（若计数器是 job 级，越过阈值的那棵树必 `clear_budget` 失败）。今天恰好压线 ⇒
**该回归项仍未被真正检验**（与 09-10 那次"8 格压线"同病）。功能面已全绿，此缺口属**夹具覆盖度**问题。

## D-119 R1：工具语义——生产 `MineTask` 不再给 bot 发工具（2026-09-12）

**背景（盘点 R1）**：`MineTask` 构造函数里住着一段**夹具语义**：主手没有对该目标有效的工具时，
**凭空写一把钻石镐**进快捷栏。后果有两层：
1. **游戏真实性**：所有采矿路径都变成"创造模式"——没带工具也能挖石头/矿石；
2. **分层边界**：生产任务依赖夹具类（`FixtureToolKit`），且"夹具补料"与"任务构造"的**顺序**
   被隐式绑定（`MineRegressionTask` 里甚至写着"构造 MineTask（夹具补镐）→ 补一次性方块 → 采基线"）。

**原版事实（本次修正的依据）**：`BlockState.requiresCorrectToolForDrops()` 为真的方块
（石头/圆石/矿石…）**徒手破坏不掉落** ⇒ 没有正确工具时"挖了也白挖"，这是**上层该知道的约束**
（目标级决策：先去弄工具），不是任务该偷偷绕过的。

**实现**
1. `MineTask`：删掉 `ensureTool`（发镐）与对 `FixtureToolKit` 的依赖；构造时只做**只读**判定
   `toolRefusal(bot, target)`：目标要求正确工具而快捷栏里没有（`BlockInteraction.hasCorrectTool`）
   ⇒ 记 `failureReason = no_suitable_tool`，`tick()` 首帧如实 FAILED（不空转、不改世界、不动背包）。
   目标**不要求**正确工具时一律允许（原版本来就能慢慢挖），但若最佳破坏速度 ≤ 1（徒手水平）
   打一条 `[MineTask] no_effective_tool …` WARN —— **夹具漏发工具会喊出来**，不再静默变慢。
2. `BlockInteraction` 新增两个只读助手：`bestDestroySpeed(bot,pos)`、`hasCorrectTool(bot,pos)`
   （与既有 `findBestToolSlot` 同口径）。
3. 夹具侧收敛一处供应商：`FixtureToolKit.ensurePickaxe/ensureAxe`（D-110 唯一写入点）；
   `LumberJobItem` 的私有实现改为复用；**入口发料**补齐到此前依赖"偷偷发镐"的三处：
   `/alice mine` 命令入口、`/alice road` 命令入口、`assignRestore`（拆我方圆石要镐）、
   `MineRegressionTask`（**发镐移到构造 MineTask 之前**，否则第一例就会 `no_suitable_tool`）、
   `LumberFailureCheckItem`。

**验证入口**：`alice:mine_regression`（10 例，含石头/圆石/悬空目标）、`alice:lumber_job` +
`/function alice_test:lumber_course`、`alice:lumber_failure_check`、`alice:scaffold_check`、
`alice:restore_check`、`alice:clear_guard_check`、`alice:write_budget_check`、`/alice mine <pos>`；
日志判据：全部照旧 PASS、**不再出现**任务内部发工具、也无 `no_effective_tool/no_suitable_tool`。

**未自动化（登记）**：`no_suitable_tool` 的**负例**（故意不给工具应如实失败）目前没有夹具入口 ——
需要"清掉工具 → 跑 MineTask → 断言失败码且未变出工具"的自检用例；建议加进 `mine_regression`
（第 11 例）或做独立自检夹具，待用户裁定。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验；负例待做夹具）。

### D-119 附注（2026-09-12）：`no_suitable_tool` 负例自检落地

`mine_regression` 增加**第 11 例 `no_tool_refuses`**（新 `Kind.TOOL_REFUSAL`）：
同一格圆石（`23,64,140`，与 `exec_direct` 同目标），但用例开头走 `FixtureToolKit.resetInventory`
**清空背包**（=没有镐）→ 构造 `MineTask` → 断言四件事同时成立：
`status=FAILED` / `reason=no_suitable_tool` / 目标方块仍在（没被破坏）/ **背包里没有镐**（没变出工具），
随后复原告一段（`ensurePickaxe`）供后续用例使用。

**日志判据随之更新**：跑 `mine_regression` 时**应当**恰好看到一条
`[MineTask] no_suitable_tool target=23, 64, 140 …`（来自本负例）——这是**期望输出**，不是回归；
其余入口（`lumber_job` / `mine_job` / `scaffold_check` / `restore_check` / `clear_guard_check` /
`write_budget_check` / `lumber_failure_check`）仍应**零** `no_suitable_tool`、零 `no_effective_tool`。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验：期望 `no_tool_refuses=PASS`、其余 10 例照旧 PASS）。

### D-120 工具诊断去噪 + 串联回归清单（2026-09-12，R1 收尾）

**实测（00:18 两入口）**：`alice:mine_regression` **11/11**，其中负例
`no_tool_refuses=PASS status=FAILED/reason=no_suitable_tool/targetKept=true/toolNotGiven=true/noToolFabricated=true/ticks=1`
（全程仅 **1** 条 `no_suitable_tool`，即负例本身）；`lumber_job` 再次
`DONE quota_met trees 4/4 logs 19/19 cleared=8 inventoryDelta=19 scaffoldLeft=0`，F2 对账分支再次命中
（`[Restore] 未能恢复: 28,66,208:side_break_failed` → `对账：1 条…现场已无我方方块` → DONE）。**R1 目标达成。**

**修正（噪声 = 隐患）**：原实现按"最佳破坏速度 ≤ 1"打 `[MineTask] no_effective_tool`，
本轮伐木刷出 **8 条**，全部是**清障橡树叶**，而 bot 手上明明有镐（`[FixtureTool] pickaxe 快捷栏已有 1`）
—— 树叶本来就没有更快的工具（原版只有剪刀更快，伐木清障不需要）。这种噪声会把真正的病症埋掉。
改为只报两类**可行动**病症：
- `tool_in_main_inventory`：快捷栏 0..8 无工具、主背包 9..35 有 ⇒ **永远选不到**（D-089 斧子 / D-099
  一次性方块，同一病灶第三次同形）；
- `no_tool`：身上根本没有工具 ⇒ 徒手继续（夹具应在入口发料）。
即"工具在手、只是对这个方块不更快"**不再出声**。同时删除随之失去用途的 `BlockInteraction.bestDestroySpeed`。

**串联回归清单入库**：`docs/TESTING_GUIDE.md` 新增 **§1.7 串联回归清单**（9 个入口 + 复位方式 + 期望摘要 +
日志判据），供"改到生产任务后一次跑完"；`mine_regression` 的 `no_tool_refuses` 那一条
`no_suitable_tool` 是**期望输出**，已在指南与矩阵里写清，避免下次被误判成回归。

**验证等级**：IMPLEMENTED / COMPILES；`mine_regression` 与 `lumber_job` 两入口 `WINDOWS_CLIENT`，
其余 7 个入口按 §1.7 清单下次串联复测。

## D-121 R2：限次清障"换候选" + 子任务信封 = 父信封子集（2026-09-12）

**病灶（现场证据）**：`MineTask.tickClear()` 一旦某个清障候选失败就把 `clearExhausted` **整体**置真，
于是 `clearProfile.clearBudget=8` 只用了 **1** 格就放弃整个目标（2026-09-11 实测
`clear_start … used=1/8` → `clear_end … exhausted=true`，随后整棵树/整根原木失败）。
当初这么写是为了修"同一格重试 8 次烧光预算"（21:54 实测）——**修过头了**：正确语义是
"**这一格**不再重试，换下一个候选"。

**实现**
1. `BlockerClearPlanner.nextClearStep(…, Set<BlockPos> excluded)`：新增排除集重载（旧签名委托空集）。
   失败过的候选不再被挑中；**候选都用过**（或预算用尽）才返回 null。
2. `MineTask`：
   - `failedBlockers` 记录失败候选；`tickClear()` 失败时只登记该格并打
     `[MineTask] clear_skip blocker=… reason=…（换下一个候选，不放弃整棵树）`，**不再闩锁**；
   - `tryClear()` 只在 `nextClearStep` 返回 null 时闩锁，并打 `clear_exhausted … tried=/used=`；
   - 运行期"视线被挡"路径：若首个阻挡物刚失败过，改走规划器路径（同样换候选），不原地重试；
   - `clear_end` 增记 `attempts=/failed=`，便于日志判读；
   - 夹具可读：`clearAttempts()` / `failedClearBlockers()`。
3. **`MiningProfile.nestedSubTask()`**：嵌套子任务信封 = **父信封的子集**——清障归零（防"清障里的清障"
   递归）、加高取 `min(父,1)`（父不允许则子也不允许）、**建拆同权归 false**（只归会话所有者，D-112）。
   清障子任务原先硬编码 `STANDABLE_ONLY`（无加高），现在从父信封派生；`clear_start` 后追加
   `clear_subtask_profile` 日志，让"子任务拿到了什么信封"在日志里可查。

**验证入口（新）**：`alice:clear_retry_check`（零参数右键，自带场景）——运行时复用 `break_course` 平台，
把目标 `6,64,64` 放**泥土**（不需要正确工具 ⇒ 父任务不会被工具判定拦下）、四周用**石头**围 3×3×2 壳
（视线全挡 ⇒ 必然走清障），分两相位：
1. **retry**：先清空背包（借 R1 的"无镐"制造**确定的**清障失败）⇒ 断言 `clearAttempts() ≥ 2`、
   `failedClearBlockers() ≥ 1`（确实换过候选）、目标未动、父任务 FAILED；
2. **clear_then_mine**：发回镐重跑同一信封 ⇒ 断言清障成功（`clearedBlocks() ≥ 1`）、目标被挖掉、DONE；
3. **sub_profile**（纯逻辑）：`withGain(3).withClear(8).nestedSubTask()` = `gain<=1 … clear=none`；
   无加高父信封 ⇒ `gain=none clear=none`。

**未覆盖（登记）**：本夹具验证的是"失败后换候选 + 换完确实能清通"；**几何性**的"第一个候选因站位不可达而
失败、第二个候选可达且成功"仍需专门场景（`isValidStandingPoint` 不判可达性，这类场景要造"可达性缺陷"的
地形）——留待做 J8/专项夹具时补。清障子任务**加高**的实际行为（父允许时子可爬 1 格）同理只有
"信封已派生"的日志证据，行为待场景。

**验证等级**：IMPLEMENTED / COMPILES（客户端待验：`alice:clear_retry_check` → `SUMMARY … → PASS`）。

### D-121 附注（2026-09-12 00:32–00:33 客户端实测：连续 3 轮 PASS）

`alice:clear_retry_check` 三轮全绿，**换候选语义拿到逐条证据**（相位①故意无镐）：
```
clear_start target=6,64,64 blocker=5,65,64 used=1/4 attempt=1 → 子任务 no_suitable_tool
clear_skip  blocker=5,65,64 reason=no_suitable_tool（换下一个候选，不放弃整棵树）
clear_end   … used=1/4 attempts=1 failed=1 exhausted=false      ← 旧代码在这里就整体放弃了
clear_start … blocker=5,64,64  attempt=2 → clear_skip … exhausted=false
clear_start … blocker=6,65,63  attempt=3 → clear_skip … exhausted=false
clear_start … blocker=6,64,63  attempt=4 → clear_skip … exhausted=false
[ClearRetry] retry attempts=4 failed=4 targetIntact=true status=FAILED → PASS
```
⇒ **4 次尝试 = 4 个不同候选**（`5,65,64` / `5,64,64` / `6,65,63` / `6,64,63`），且每一步
`exhausted=false`（只有预算用尽才停），完全符合"这一格失败就换下一个"的设计 ✓（旧实现：
第一次失败即 `exhausted=true`，8 格预算只用 1 格）。

相位②（发回镐，同一信封）：`break 5,65,64 stone by=…:LINE_OF_SIGHT` → `break 6,64,64 dirt`
→ `clear_then_mine=DONE cleared=1 targetGone=true → PASS`（新子信封没破坏正常清障）✓；
`clear_subtask_profile … profile=standableOnly=true gain<=1 blocks<=12 clear=none` ✓；
`sub_profile` 两个纯逻辑断言（父 `gain<=3` ⇒ 子 `gain<=1`；父无加高 ⇒ 子 `gain=none clear=none`）均 PASS ✓。
同轮 `[WriteBudget] SUMMARY breaks=2/64 … refusedBreaks=0 exhausted=false`（预算无干扰）✓。

**附带观察（无害）**：新增物品会打一条
`Registry minecraft:item: Object did not get ID it asked for. Name: alice:clear_retry_check Expected: 1286 Got: 1295`
—— 已存在的存档里物品 ID 表与新注册顺序不一致，Forge 自行重映射；只影响诊断，不影响行为。

**验证等级**：`WINDOWS_CLIENT`（`[ClearRetry] SUMMARY … → PASS` ×3）。

## D-122 串联回归电池（`alice:regression_battery`）：一次右键跑完 9 项常用回归（2026-09-12）

**用户裁定**："你帮我串联下必要的常用回归部分" —— 把 `TESTING_GUIDE §1.7` 的清单从"让用户点 9 次"
改成**一个电池任务**（项目规矩本来就是"多项检查合并为一次交互，输出 `SUMMARY key=VALUE`"，
拆成多次点击是反模式）。

**实现**：`task/RegressionBatteryTask` + `item/RegressionBatteryItem`（`alice:regression_battery`）。
一步一项、各自复位、**任一失败不中断**（一趟看全），最后一行
`[Regression] SUMMARY clear_retry=… pathing=… (9/9) ticks=… → PASS|FAIL`。清单（由轻到重）：

| # | 项 | 场景 | 夹具发料（D-119 起生产任务不发工具） |
|---|---|---|---|
| 1 | `clear_retry` | 任务自建（break_course 平台 + 石头壳） | 任务内部两相位各管一次 |
| 2 | `write_budget` | 自带复位 | 自带 |
| 3 | `scaffold` | 自带复位 | 自带 |
| 4 | `clear_guard` | 自带复位 | 自带 |
| 5 | `lumber_failure` | 自带复位 | 自带 |
| 6 | `mine_regression` | 自带复位（逐例） | 自带 |
| 7 | `lumber_job` | `lumber_course_terrain` + `lumber_course_trees` | 电池复刻 `LumberJobItem`：`resetInventory` + 斧 + 镐 + 圆石 ×12 |
| 8 | `mine_job` | `ore_course_terrain` | 电池复刻 `MineJobItem`：`resetInventory` + 镐 |
| 9 | `pathing` | 自带复位 | 自带 |

**关键坑（本实现必须处理）**：`WriteBudget`（D-106）的 64/32 上限与账本 TEMP 都是
**"一次任务 = 一个作用域"**（`WorldModLedger.openScope`）。九项若共用外层任务的作用域，后面的项会
撞上前面的破坏上限、恢复阶段还会互相看见对方的临时方块 ⇒ 电池**每步自己 `openScope`**，
并在步末镜像 `BotSession.clearTask` 的收尾（关账本作用域 → `WriteBudget.closeScope`（打摘要）
→ `dropStale` → `scope.end()`），若步末仍有我方临时放置则 WARN 报出来。

**用法**：`/give @s alice:regression_battery` → 右键 → 等约 3~5 分钟（9 项，总预算 20000 tick）
→ 看 `[Regression] SUMMARY`。期间不要启动其它任务；人站远一点别捡掉落物。

**验证等级**：IMPLEMENTED / COMPILES（客户端待跑；本电池本身也是"§1.7 清单"的可执行版本）。

### D-122 附注（2026-09-12 00:47–00:49 首次实跑：8/9，电池抓到的第一个真缺陷）

**结果**：`[Regression] SUMMARY clear_retry=PASS write_budget=PASS scaffold=PASS clear_guard=PASS
lumber_failure=FAIL mine_regression=PASS lumber_job=PASS mine_job=PASS pathing=PASS (8/9) ticks=2531 → FAIL`

**缺陷（`lumber_failure`）**：子用例 `log_replaced` 变成 `status=FAILED reason=goal_timeout`
（安全不变式仍成立：`replacedPos=20,64,208 仍为圆石=true`，即 bot 没挖非原木 ✓）。
根因由**破块速度**一眼定位：该步是 **61 tick/根**（原木徒手速度），而后面的 `lumber_job` 步是
**6~8 tick/根**（有斧）⇒ 该步**没有工具**，徒手砍树 8 倍慢，`LOG_REPLACED`（`maxTicks=600`）超时。

**为什么会没工具**：D-119 起"发工具"是**入口/夹具职责**，而 `LumberFailureCheckTask` 的工具发在
**物品入口**（`LumberFailureCheckItem`）里；电池**直接跑任务**、绕过物品 ⇒ 徒手。
**修正**：把发料放进**任务自己**的 `prepare()`（`ensureAxe` + `ensurePickaxe`，对任何调用者都成立）；
并给 `BotManager.assignLumberJob` / `assignMineJob` 加同样的防御性发料（物品入口那层保持不变，
重复发料是幂等 no-op）。

**教训（写进夹具规范）**：**发料要放在"任务"里，不能只放在"物品"里** —— 物品只是入口之一，
电池/命令/后续调用者都会绕过它。这条与 D-119"生产任务不发工具"并不矛盾：生产任务不该发，
**夹具任务**该发，且要发在自己身上。

**另一条期望输出（不是缺陷）**：`[Regression] step=pathing 收尾仍有 4 条我方临时放置未拆` ——
寻路回归的 `place_course` 系列是**故意改世界**的场景（`worldMod=true`，内核 `PILLAR`/
`PLACE_STEP_AND_TRAVERSE` 的放置按账本记 TEMP），它们的复位靠下一次 `*_terrain` 重放，
不归建拆同权管；电池按步如实 WARN 出来是正确的（每步仍各自独立作用域，不影响其它步）。

**验证等级**：IMPLEMENTED / COMPILES（待复跑电池，期望 9/9）。

### D-122 附注二（2026-09-12 00:52–00:55 复跑：**9/9 PASS**，2416 tick ≈ 2 分钟）

```
[Regression] SUMMARY clear_retry=PASS write_budget=PASS scaffold=PASS clear_guard=PASS
            lumber_failure=PASS mine_regression=PASS lumber_job=PASS mine_job=PASS
            pathing=PASS (9/9) ticks=2416 → PASS
task_execution_terminal kind=RegressionBatteryTask … terminal=COMPLETED code=done
                     recovery=idle_after_cleanup recoveryEvents=[]     ← 全场唯一非 COMPLETED 记录：无
```
逐项细节：`clear_retry` 59 / `write_budget` 23 / `scaffold` 171 / `clear_guard` 123 /
`lumber_failure` 473（`[FailCheck] SUMMARY … log_replaced=PASS(status=DONE reason=quota_met
replacedPos 仍为圆石=true) → PASS`，修复生效）/ `mine_regression` 183（**11/11**，含
`no_tool_refuses=PASS`）/ `lumber_job` 666（`DONE quota_met trees 4/4 logs 19/19 cleared=8
scaffoldLeft=0`）/ `mine_job` 240（`DONE quota_met mined 4/4 inventoryDelta=4`）/
`pathing` 459（18 场景 + `foot_cell_rule` + coverage 全 PASS）。

**每步独立作用域已被日志证实**：`[WriteBudget] SUMMARY scope=… #112:Regression:lumber_failure
breaks=22/64 … #113:Regression:mine_regression breaks=4/64 … #114:Regression:lumber_job 29/64 …
#115:Regression:mine_job 4/64 … #116:Regression:pathing 5/64`（各自计数、**无跨步累加** ⇒
"一次任务=一个作用域"的设计正确）。

**噪声逐条归类（全部为期望输出）**：
- `no_suitable_tool` ×5 条实体告警 = `clear_retry` 相位①的 4 个壳方块 + `mine_regression` 负例 1 条；
  另有 4 条 `clear_skip … reason=no_suitable_tool`（换候选取证）✓
- `no_tool` ×1（`clear_retry` 相位①故意无工具）✓
- `tool_in_main_inventory` **0** ✓；`clear_exhausted` **0** ✓；`scaffold_left` **0** ✓
- `该树未完成` ×2 均出自 `lumber_failure` 自身的用例场景（`log_replaced` 与随后一棵残树），
  该夹具用例本就容许 partial（其 SUMMARY 5/5 PASS）✓
- `[Regression] step=pathing 收尾仍有 4 条我方临时放置未拆` —— `place_course` 系列**故意改世界**
  的场景，复位靠下次 `*_terrain` 重放，不归建拆同权管 ✓

**一条如实记录的口径差异**：电池里 `lumber_job` 的 `inventoryDelta=21`（干净手工轮是 19）——
电池各步共用一个世界，前面 `lumber_failure` 用例留在地上的原木会在本步被顺路捡起。
**判据不受影响**（`DONE quota_met`、`logs 19/19`、`scaffoldLeft=0` 都成立）；要拿"干净增量"就单独跑手工入口。

**验证等级**：`WINDOWS_CLIENT`（9/9）。本电池一次性复验了 D-119（工具语义/负例）、D-121（清障换候选）、
D-116（① 扫尾/② 对账）、D-117（伐木通道）等此前各项 ✓。

## D-123 R3：① 扫尾的信封与预算改为**从树木几何推导**（2026-09-12）

**先查出身（避免把"裁定常量"误当"场景常量"改掉）**
- `MAX_CLEAR_PER_TREE = 8`：出身是 `JOB_LAYER_DESIGN.md §9-4「≤8 格/棵」` —— **设计裁定**，不动
  （J2/J3 的夹具反而是**照着它**造成"累计 ≥8 才跨阈值"的）。
- `MiningProfile.DEFAULT_GAIN_BLOCK_BUDGET = 12`：**用户 2026-09-11 裁定**（"砍树够用"，模组超高树不在范围），
  且已把 `CLIMB_BUDGET=12` 的两份重复收敛到一处 —— 不动。
- 真正"按本测试场景反推"的只有两个：① 扫尾的 `withGain(8)`（注释自述"一棵树从脚手架上到树冠的余量"）
  与 `SWEEP_UP_BUDGET_TICKS = 200` ⇒ **本轮只改这两个**。

**实现**
1. `MiningProfile.sweepGain(int trunkHeight)`：加高步数 = `min(树干高 + 1, 12)`，且**方块预算 = 步数**
   （每个加高步恰好消耗 1 个方块 ⇒ 紧界，不再沿用宽松的 12）。
   依据（实测）：高云杉脚位 65 → 掉落物 70、树干 64..70 ⇒ 上界就是**整根树干的高度**（+1 给树冠余量）；
   `Tree.trunkHeight()` 是"顶格 − 底格 + 1"的跨度 ⇒ 天然是不小于实际所需的**安全上界**。
   模组超高树如实停在 12 格（不做无限加高）。
2. `CollectDropsTask.suggestedSweepTicks(dropEstimate, gainProfile)` =
   `120（建立簇/等 pickupDelay/等落地/收尾）+ 待收物数×60 + 加高步数×25`，
   单价来自实测代价（PILLAR 一步 10~16 tick；一次走位-吸取 6~30 tick），不是"试出来的一个数"。
3. 两个调用点改用推导值并把**推导过程打进日志**（便于核对算术）：
   - `LumberJob.sweepUp()`：`sweep_up_start … trunkHeight=7 gain<=8 blockBudget=8 budgetTicks=380`；
     超时日志带 `ticks=…/预算`；新增 `SWEEP_PHASE_SLACK_TICKS = 40`（阶段守护余量，不再是匿名的 `+40`）。
   - `ScaffoldLifecycleTask.sweepUp()`：同样推导（该夹具扫尾不加高 ⇒ `120 + 1×60 = 180`，原为写死 200），
     `sweep_up_start … budgetTicks=180`。

**对当前场景的数值**（可核对）：高云杉 `trunkHeight=7` ⇒ 加高 8 步、块预算 8、tick 预算 **380**
（原 200）；`scaffold` 夹具 ① 扫尾 **180**（原 200）。两者都被串联回归电池行为性覆盖
（`lumber_job` 需 ① 扫尾收树冠掉落物、`scaffold` 断言 `drops_left=0`）。

**未覆盖（登记）**：`trunkHeight + 1 > 12` 的**截断分支**（模组超高树）只有代码/算术证据，没有场景；
① 扫尾 **超时分支**（`sweep_up_timeout`）同样未构造。两者都需要"更高的树"或"故意够不到的掉落物"，
留待专项目标时一起做（与 R2 的两条未覆盖同批）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待跑：串联回归电池应仍 9/9，且日志出现推导后的
`trunkHeight=/gain<=/blockBudget=/budgetTicks=`）。

### D-123 附注（2026-09-12 09:40 客户端实测：**9/9 PASS**，推导算术语料齐）

```
[Regression] SUMMARY … (9/9) ticks=2407 → PASS
[Scaffold] sweep_up_start anchor=40,70,46 foot=39,69,46 live_drops=1 budgetTicks=180（仍在架上，就地收）
[Job] lumber sweep_up_start foot=22,69,207 live_drops=1 trunkHeight=3 gain<=4 blockBudget=4 budgetTicks=280
[Job] lumber sweep_up_start foot=28,65,208 live_drops=1 trunkHeight=7 gain<=8 blockBudget=8 budgetTicks=380
```
**关键点：一轮里出现了两个不同的树干高（3 与 7）** ⇒ 公式确实是**输入驱动**的，而不是换个地方写死：
- `trunkHeight=3` → `min(3+1,12)=4` 步、块预算 4、tick `120+1×60+4×25=280` ✓
- `trunkHeight=7` → `min(7+1,12)=8` 步、块预算 8、tick `120+1×60+8×25=380` ✓
- 不加高的 ① 扫尾（scaffold 夹具）→ `120+1×60+0×25=180` ✓

行为未变：电池 9/9，`lumber_job` 4/4、`mine_job` 4/4、`mine_regression` 11/11、`lumber_failure` 5/5 全绿
（预算变化被真实扫尾路径覆盖）。

**验证等级**：`WINDOWS_CLIENT`（推导算术语料 + 行为回归双证据）。仍未覆盖（沿用 D-123 登记）：
`trunkHeight+1 > 12` 的**截断分支**与 ① 扫尾**超时分支**。

## D-124 T4：作用域重开**继承**掉落物归属（2026-09-12）

**病灶（盘点风险项 T4，原编号 R4 与寻路内核里程碑 R4 撞车，见下）**：`ScopeBuffer.begin()` 无条件
`end()`，而 `end()` 清空 `spawnedItems`/`itemOrigins` ⇒ "已配对到来源、随后区间被重开"的掉落物
**丢掉归属**，即使它还好端端躺在地上；后续收集看不见它（实测：拆除任务重开区间后 `live_drops=0`，
收尾收集无物可追）。D-108 的 `adoptExistingDrops` 只是当时的手工补丁，而且会把**别人**的掉落物
一并收养（文档里已标注该风险）。

**修正（通用接入点，不再指望每个调用点记得打补丁）**
- `ScopeBuffer.begin(center, radius, owner, inheritDrops)`：默认 `inheritDrops=true`。
  重开只换**监听窗口**：仍活着且落在**新区间内**的我方掉落物照旧记账（`spawnedItems`/`itemOrigins`
  过滤后搬运），移出窗口/已消失的自然淘汰；`pending`/`brokenBlocks`/`recentBreaks` 仍照常清空
  （它们只服务"刚刚发生的破坏-掉落配对"，跨重开无意义）。
- **整个会话结束**仍走 `end()`（`BotSession.clearTask` 调用）⇒ **跨任务不会串味**（语义边界清晰：
  会话内重开继承，会话结束清空）。
- 新增日志 `作用域重开: center=… 继承掉落物=N（仍在区间内的我方掉落物）`（可观测）。
- `ScaffoldLifecycleTask.sweepGround()` 删掉 D-108 的手工收养（已被通用继承取代，且顺带去掉了
  "收养别人掉落物"的风险面）。

**针对性回归（`mine_regression` 第 12 例 `scope_reopen_keeps_drops`）**：挖出掉落物（该例
`collectDrops=false` 先不收集）→ **重开同一中心/半径的作用域** → 断言 `liveDrops()` 仍 ≥ 1
（修前必然是 0）。判据行：`liveDropsBeforeReopen=… / liveDropsAfterReopen=…`。

**编号澄清（用户 2026-09-12 提问）**：本文档里我一直用的 `R1–R7` 是 **9/11 盘点时临时起的"风险项"编号**，
与项目既有的**寻路内核里程碑 R1/R2/R3/R4/R5**（`R1 契约` → `R2-A/B/C/D Movements` → `R3 PathSession/Battery`
→ `R4 Session 执行` → `R5 世界修改 Movement`，日志前缀 `[R2-B Traverse]`/`[R3 Battery]`/`[R4 Session]`/`R5-2`）
**撞车**。为免继续混淆，盘点项自此改称 **T1–T7**：
T1 工具语义（D-119/120 ✅）、T2 清障换候选（D-121 ✅）、T3 扫尾预算推导（D-123 ✅）、
**T4 作用域归属（D-124，本条）**、T5 配额与场景解耦、T6 通道/可规划性离线校验、
T7"清障预算按棵重置"回归覆盖。寻路内核里程碑编号**保持原样**。

**验证等级**：IMPLEMENTED / COMPILES（客户端待跑：`mine_regression` 应 **12/12**，其中
`scope_reopen_keeps_drops=PASS … liveDropsAfterReopen≥1`；串联回归电池应仍 9/9）。

### D-124 附注（首测假失败：**"破坏当 tick 查 `liveDrops()` 必为 0"**）

首版 `scope_reopen_keeps_drops` 判 FAIL，明细是 `status=DONE/liveDropsBeforeReopen=0/liveDropsAfterReopen=0`
——注意 **重开之前就是 0**，所以**不是继承没生效**，而是**掉落物还没登记进账**。
根因：`ScopeBuffer` 的掉落物登记分两步 —— `EntityJoinLevelEvent` 只把实体排进 `pending`，
真正确认在**服务器 tick 的 END**（`TickEvent.ServerTickEvent.Phase.END` → `flushPending()`，
期间还要判定实体确实在世界里，防模组取消生成造成的"幻影掉落物"）。而用例在
`MineTask` 返回 DONE 的**同一 tick 内**就查账 ⇒ 必然看到 0。

**修正**：用例加"等待确认"窗口 —— 等 **5 tick**（≥1 跨过一次 tick END，且 **< 掉落物
`pickupDelay`(~10)**，免得被 bot 顺手捡走），并在明细里同时报世界侧计数
`dropsInWorld=N`（区分"没掉出来"与"掉了但没登记"）。

**教训（写进夹具规范）**：**凡断言"我方掉落物是否在账上"，必须跨过一次服务器 tick END**；
在破坏/放置的当 tick 内查 `liveDrops()` 恒为 0。这条同样适用于将来任何"收集前先看账"的用例。

**验证等级**：IMPLEMENTED / COMPILES（待复测：`mine_regression` 期望 **12/12**、
`scope_reopen_keeps_drops=PASS status=DONE/dropsInWorld=1/liveDropsBeforeReopen=1/liveDropsAfterReopen=1`）。

### D-124 附注二（2026-09-12 10:04–10:07 客户端实测：**12/12 + 电池 9/9**，继承在三个历史病灶点全部生效）

```
[MineRegression] scope_reopen_keeps_drops=PASS
   status=DONE/dropsInWorld=1/liveDropsBeforeReopen=1/liveDropsAfterReopen=1/ticks=12
[MineRegression] SUMMARY … scope_reopen_keeps_drops=PASS ticks=213 / 214（连续两轮 12/12）
[Regression] SUMMARY … (9/9) ticks=2468 → PASS（scaffold=PASS）
作用域重开: center=23, 64, 140 radius=16 继承掉落物=1     ← 第 12 例自身
作用域重开: center=38, 65, 46  radius=8  继承掉落物=1     ← scaffold 夹具收尾收集（D-108 手工收养已删）
作用域重开: center=22, 66, 207 radius=8  继承掉落物=1     ← lumber ② 拆除后重开（原先 ③ 因此 live_drops=0）
作用域重开: center=28, 65, 208 radius=8  继承掉落物=1     ← 同上（另一棵树）
```
`liveDropsBeforeReopen=1 → liveDropsAfterReopen=1` 直接证明"重开不再丢归属"（修前必为 `1 → 0`）；
三处历史病灶点（自建用例 / scaffold 收尾 / lumber ②③ 之间）都靠**通用继承**而不是各自打补丁。

**验证等级**：`WINDOWS_CLIENT`。

## D-125 T6 离线可规划性自检 + T5 配额随场景推导（2026-09-12）

### T6：`tools/check-scene-connectivity.py`（夹具"自封航线"机器可查）

**动机**：D-117 的封死通道只验到 `COMPILES` 就进了主线，白花两轮客户端才定位；同类缺陷
（"目标站位的可达性被新加的方块掐断"）本该**离线**就能发现。

**做法（有意不另写一套判定）**
- **方块表**复用 `analyze-lumber-scene.py` 的 `FixtureWorld`（从数据包函数 setblock/fill 重建，"夹具即真相"），
  **站位/视线判据**复用其 `stand_candidates` / `can_see`（与 Java `StandingPointSelector` 同口径）。
- 可达性做**保守下界**泛洪：水平 1 格 / 上 1 格 / 下落 ≤3 格 / 对角要求两侧正交可通行；
  **不含**跳跃越沟、加高（PILLAR）、破坏通行 ⇒ 判"不可达"是**强提示但非定论**（需真规划器复核），
  判"可达"则基本可信（内核能力的子集）。
- 检查两件事：① 每个**原木/矿石目标**（按树干列取最低格为代表）是否**至少有一个可达的合法站位**；
  ② 每个场景声明的**目标格**是否可达 —— 含 `expect_gap=True` 的场景（`fluid_course`/`fence_course`）
  **必须仍然不可达**（哪天被改得"能过去了"，测试前提也就失效了）。
  依赖破坏/放置/加高的场景（`needs_world_mod`）保守模型**跳过目标判定**并如实标注。
- 输出可达/搁浅站位统计、逐目标结论，`--verbose` 给 ASCII 切片（`#` 实心 / `o` 可达站位 / `x` 搁浅站位）。

**自证（都在本机离线完成）**
```
tools/check-scene-connectivity.py --selftest
  封死通道 → 报不可达 = True；留 2 格缺口 → 全部可达 = True      ✓
tools/check-scene-connectivity.py --all
  22 个场景检查完毕；有目标无可达站位的场景：无                    ✓（对已知能跑的场景零假报）
git show a5901f5:<trees> > /tmp/sealed && tools/... --fixture terrain /tmp/sealed --start 23 64 207
  可达站位 67 格；目标 9 个，**无可达站位 8 个**（含每轮都失败的 19,64,213 与封死通道的 24,64,213）✓
```
⇒ 该工具**能在离线复现 D-117 并拦下它**，已写进 `alice-scene-based-testing` skill 的流程
（"改完场景先跑 `--all`"）。

### T5：伐木 Job 的配额随场景推导

**病灶**：`lumber_course` 的可行树数(4) 与 `BotManager.assignLumberJob` 写死的配额(4) 互相标定 ⇒
场景里任何一棵树变不可行都表现为 `partial_quota`，看起来像代码 bug（2026-09-11 为此多花两轮）。
**修正**：`assignLumberJob` 用**同一套 `LumberCandidateSource`** 数一遍可行树（`too_large` 本就在 `rejected` 里），
`配额 = max(1, 可行树数)`，并打 `[Job] lumber 场景可行树=N ⇒ 配额=N（T5：配额随场景推导）`；
场景 tellraw 不再写死"配额 4 棵"。当前场景推导结果仍是 4（行为不变），但**场景变了配额自动跟上**。

**验证等级**：T6 = 本机离线自证（上面三段）；T5 = IMPLEMENTED / COMPILES
（客户端待测：`lumber_job` 应打 `场景可行树=4 ⇒ 配额=4` 且终态仍是 `trees 4/4`）。

### D-125 附注（2026-09-12 10:19–10:20 客户端实测：T5 生效）

```
[Alice 伐木 Job 场景·真树] … 配额=场景可行树数（启动时推导，不再写死）。   ← 场景文案已更新
[Job] lumber 场景可行树=4 ⇒ 配额=4（T5：配额随场景推导）                  ← 推导日志
[Job] terminal job=lumber result=DONE reason=quota_met trees 4/4 logs 19/19
                cleared=8 inventoryDelta=19 gainedTrees=1 gainedBlocks=1 scaffoldLeft=0 ticks=689
```
**T5 验证等级：`WINDOWS_CLIENT`**（推导结果与终态均符合预期；当前场景推导值恰为 4，与旧写死值一致 ⇒ 行为不变）。
T6 无需客户端（本机 `--selftest` + `--all` + `a5901f5` 旧版本复现三段自证，见 D-125 正文）。

## D-126 T7：让"清障预算按棵重置"这一回归**真正跨过阈值**（2026-09-12）

**背景**：`MAX_CLEAR_PER_TREE=8`（`JOB_LAYER_DESIGN §9-4`）是设计裁定，要检验"预算**按棵重置**"
必须让**累计清障 > 8**（否则 job 级计数器也能通过 ⇒ 回归形同虚设）。历史两次尝试都卡在这里：
J3 的 3 棵树恰好 3+3+2 = **8 压线**；`a5901f5` 想加第 4 棵树，却把唯一通道封死（D-117），白花两轮客户端。

**这次的做法（先用 T6 离线验证，再上客户端）**
1. **台地东扩**：在**手写**的 `lumber_course_trees.mcfunction` 里加
   `fill 31 58 203 37 116 231 air` + `fill 31 59 204 36 62 230 stone` + `fill 31 63 204 36 63 230 dirt`
   （**不动** `capture-scene.py` 生成的 `lumber_course_terrain.mcfunction`，尊重其"勿手改"）；
   `lumber_course_reset.mcfunction` 的 x 范围同步扩到 37/36。
2. **第 4 棵同型橡树**：把「橡树#1」的 **61 个方块整体平移 (+14, 0, −5)** ⇒ 基底 `33,64,208`
   （同型、非手搓；落在东扩后的台地上，**不占用 x=22..26 的南北通道**）。
3. **离线验伪（T6）**：`check-scene-connectivity.py --scene lumber_course` ⇒ 可达站位 423 格、
   **封航线 0 个**（对比 D-117 那次是 8 个）；`--all` 22 场景无硬伤。
4. **配额自动跟上（T5 的收益）**：`assignLumberJob` 由候选源推导 ⇒ 配额自动 4 → **5**，
   场景文案与代码都无需改（这正是 T5 解耦的价值）。
5. 预期累计清障 ≥ 9（20:3 + 19:2 + 29:3 + 云杉:0~3 + 新树:2~3）⇒ 第 3~4 棵时即跨过 8，
   若清障计数器是 job 级，后面必然 `clear_budget` 失败。

### 顺带：T6 工具自身的两处修正（都由"自证"抓出来）

1. **`--all` 聚合有 bug**：我把 `warn_only=True` 传给了每个子场景 ⇒ `check()` 恒返回 0 ⇒
   汇总**永远打印"无"**（工具看起来在报平安，实际什么都没判）。修：子场景用真实返回码聚合，
   `--warn-only` 只影响整轮退出码。
2. **判据从"周围可站格是否可达"回退到"有合法站位但全不可达"**：前者把 `fence_course`（内核**策略**
   拒绝跨栅栏，不是几何隔断）与"需要清障"的正常伐木目标一起误报；实测确认原判据就能抓住 D-117
   （历史封死版本里高云杉 `22/23,64,218/219` 合法站位 29~36、**可达 0**）。
   另加 `policy_only` 标记（`fluid_course` 不游泳 / `fence_course` 不跨栅栏）。
3. **如实登记的盲区**：像 `19/24` 那种"本来就看不见目标、必须清障"的目标，本工具只给**软提示**
   （"需清障/协助"）；"清障是否可行"仍由 `analyze-lumber-scene.py` 的 soft/hard 判定，而它**不计可达性**
   —— 两者合起来才完整，这条缺口已写进工具注释与矩阵。

**验证等级**：T7 场景改动 + 工具修正 = IMPLEMENTED / COMPILES / **本机离线自证**
（`--selftest` 双向、当前场景 exit 0、历史封死版本 exit 1）；客户端待测：
`alice:lumber_job` 应 `trees 5/5` 且 `cleared ≥ 9`，电池应仍 9/9。

### D-126 附注（2026-09-12 10:35–10:36 客户端实测：**T7 达成**）

```
[Alice 伐木 Job 场景·真树] 4 棵同型橡树(20,64,208 / 19,64,213 / 29,64,213 / 33,64,208) + …
[Job] lumber 场景可行树=5 ⇒ 配额=5（T5：配额随场景推导）
[Job] terminal job=lumber result=DONE reason=quota_met
      progress=trees 5/5 logs 23/23 cleared=9 inventoryDelta=23 gainedTrees=1 gainedBlocks=1 scaffoldLeft=0 ticks=806
逐树清障：20→3、19→2、29→1、云杉→0、新树 33,64,208→3
```
**`cleared=9 > MAX_CLEAR_PER_TREE(8)`** ⇒ "清障预算按棵重置"这一回归**第一次真正跨过阈值**
（历史两次：J3 恰好 8 压线；`a5901f5` 想加树却封死通道）。若计数器是 job 级，第 9 格清障必
`clear_budget` 失败 —— 该回归从"形同虚设"变成"真断言" ✓。
新树（`33,64,208`，由「橡树#1」整体平移 (+14,−5) 得到）正常 `chopped=4/4`，东扩台地与通道均无副作用。

**验证等级**：`WINDOWS_CLIENT`。

## D-127 J7 Step 3：崩溃兜底（§12.4）——账本残留的判定与"就近续做"（2026-09-12）

**设计要求**（`JOB_LAYER_DESIGN §12.4`）：启动时扫描账本中未完成的拆除会话 → 续做；不变量：
任务/会话结束时该 scope 账本为空。**同时必须尊重 §12.3 与 D-103 的教训**：
拆除要在"**仍在架上**时"做（从地面拆高层柱子必然留悬空残块），且**不能**把"走回去拆"这种复杂度
塞进每个任务的收尾（D-103 就是为此删掉了自动追加恢复）。

**实现（三处，都不改变正常路径的行为）**
1. `WorldModLedger.pendingForOwner(server, owner)`：查该 owner **跨 scope** 的未拆除 `TEMP` 放置
   （`Entry` 自带 `owner` 字段，不必解析 scopeId）。
2. `BotManager.teardownRecoveryDecision(bot)`：先 `dropStale`（现场已非我方方块的条目销掉），
   再按**最近残留距离**判定 —— `none`（干净）/ `ready`（≤ `TEARDOWN_RECOVERY_RANGE = 16` 格，
   通常是崩溃时 bot 就站在架上 ⇒ 可就地续做）/ `too_far`（**不自动走回去**，如实报告并留给
   `/alice restore`）。判定与动作分开，便于夹具单测（不劫持当前会话）。
3. `BotManager.tryRecoverUnfinishedTeardown(bot)`：`ready` 且 bot 空闲 ⇒ 起一个
   `RestoreScopeTask(scopeId=null)`（与 `/alice restore` 同一条路径）续做；
   挂钩① **bot 会话建立时**（`BOTS.put(...)` 那个唯一入口）；挂钩② `onServerStarted` 打**启动报告**
   （有残留或未闭合作用域才出声，避免噪声）。

**可验证闭环（复用脚手架夹具，不新增物品）**：`ScaffoldLifecycleTask` 增加**第二轮**：
第一轮（正常生命周期）通过后 ⇒ 再爬一次柱顶、**故意不拆** ⇒
`recover_decide`（断言判定必须是 `ready`）⇒ 复用既有 `TEARDOWN` 相位做"续做" ⇒
`RECOVERY SUMMARY decision=ready ledger_before=N ledger_after=0 residue=0 → PASS`。
判据 = **账本事实**（`pendingTemporary` 归零）+ **世界事实**（立柱列无残留）+ 第一轮仍 PASS。
（第二轮规划攀爬时不重放场景，避免洗掉第一轮的结论；补发一次性方块保证夹具自足。）

**未覆盖（如实登记）**
- `too_far` 分支：夹具场景台地只有 13×17，**没法安全地把 bot 挪到 >16 格外**（走出去就是虚空）
  ⇒ 该分支只有代码与日志证据；触发条件已写清（崩溃后 bot 被传送走 / 换了场景）。
- 挂钩①的**真实崩溃路径**（服务器被强杀 → 重启 → bot 恢复）无法在夹具里复现；夹具验证的是
  "判定正确 + 续做机制把账本收尾"（同一条 `RestoreScopeTask` 路径）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测：`alice:scaffold_check` 应出现第二轮
`recover_decide … decision=ready` 与 `RECOVERY SUMMARY … → PASS`；串联电池的 `scaffold` 步仍 PASS）。

### D-127 附注（2026-09-12 10:57–11:00 客户端实测：**J7 Step 3 达成**）

```
[Recovery] 启动检查：账本有我方临时放置 4 条、未闭合作用域 0 个   ← 启动报告确实抓到了上次会话的残留
[Scaffold] SUMMARY pillar=4/12 torn=4 remaining=0 residue=0 target=gone … → PASS   ← 第一轮原样
[Scaffold] 第一轮通过 ⇒ 第二轮：再爬上柱顶后**故意不拆**，验证崩溃兜底
[Recovery] 账本发现 6 条我方未拆除的临时方块（上次会话未闭合）最近 2.236 格 ⇒ 就近（2.236 格）⇒ 可就地续做
[Scaffold] recover_decide decision=ready pending=4
[Scaffold] teardown_start foot=39,69,46 on_top=true pending=39,69,46   ← 仍在架上（§12.3）
[Scaffold] RECOVERY SUMMARY decision=ready ledger_before=4 ledger_after=0 residue=0
                            restore=restore_done → PASS
[Restore] SUMMARY scope=…#157:Regression:scaffold restored=4 skipped=0 remaining=0 → DONE
[Regression] scaffold=PASS ticks=321 ； 电池 (9/9) ticks=2732 → PASS ；无一条非 COMPLETED
```
要点：① **判定阈值生效**（最近 2.236 格 ⇒ ready ⇒ 续做）；② 续做走的就是 `/alice restore` 同一条
`RestoreScopeTask(scopeId=null)` 路径（`restored=4 remaining=0`）；③ 断言用**账本事实 + 世界事实**
（`ledger_after=0`、`residue=0`）而不是"跑完就算"；④ 启动报告在真实残留上触发（4 条）✓。
未覆盖（沿用 D-127 登记）：`too_far` 分支、真实崩溃重启路径。

**验证等级**：`WINDOWS_CLIENT`。

## D-128 J7 Step 4：失败语义收敛 + 工具语义上抛（2026-09-12）

**要解决的问题**：同一件"没干成"在日志里散成三种口径 —— 候选期（`too_large`）、规划期
（`no_reachable_standing_point`）、结算期（`partial_tree`/`product_not_collected`），而目标级决策
（LLM）需要的是 §13.3 表格那种**可消费的顶层码**（`tool_missing` / `no_reachable_candidate` /
`scaffold_restore_incomplete` / …）。另外「**树干太高**」与「**爬了但没砍完**」都落在 `partial_tree` 里分不开。

**实现**
1. **候选期码正名**：`LumberCandidateSource` 的 `too_large` ⇒ **`trunk_too_tall`**
   （与 `JOB_LAYER_DESIGN §13.3`、矩阵 J1 行的既有期望一致；旧名分不清"树太高"与"清障不可行"）。
2. **`tool_missing` 前置检查（`LumberJob.tick()` 顶部）**：快捷栏没有斧 ⇒ 直接
   `FAILED tool_missing`，**不拿徒手去撞 tick 预算**。依据：原版允许徒手砍原木，但慢 8 倍
   （实测 61 tick/根 vs 6~8），撞预算只会产出"砍一半超时"这种噪声失败；而"缺工具"是
   **目标级决策**该接的事实（"先去弄工具"）。
3. **`climb_incomplete` 分类**：逐树理由不再只看"有没有砍完"，而是用 **L2 汇报的加高步数**
   （`gainedThisTree`，D-111 起加高在 L2）区分：
   `product_not_collected`（全砍完但产物没收齐）/ **`climb_incomplete`（爬过、仍没砍完）** /
   `partial_tree`（没爬、也没砍完）。
4. **顶层归因 `deriveTopLevelReason`**：只在"树被尝试过、却一棵都没成功"（`partial_quota`）时，
   若**所有**逐树失败都指向同一根因 ⇒ 顶层码换成 `tool_missing` 或 `climb_incomplete`；
   有任一成功则保持 `partial_quota`（不能甩锅）✓。
5. **§13.3 的命名**：建拆同权未闭合的终态后缀 `+scaffold_left(n)` ⇒ **`+scaffold_restore_incomplete(n)`**
   （`restorePhase` 里的 `scaffold_left` **日志关键词保持不变**，判据不受影响）。
6. 夹具可读：`LumberJob.attemptFailures()`。

**验证入口**：`alice:lumber_failure_check` 由 5 例 ⇒ **6 例**：新增 `tool_missing`
（该例**故意清空背包** ⇒ 期望 `FAILED tool_missing`），并同步把 `all_rejected` 的期望改为
`trunk_too_tall`。仍跑 `alice:lumber_job` / 串联电池确认正常路径不受影响（`assignLumberJob` 与
`LumberJobItem` 都会发斧 ✓）。

**未覆盖（如实登记）**：`climb_incomplete` 的**场景**仍未构造 —— 它要求"某次成功加高之后仍有原木没砍完"
（现实成因是"爬上去之后清障预算耗尽/够不到"）；`deriveTopLevelReason` 里对 `climb_incomplete` 的
归因分支目前只有代码证据。**J7 的失败码骨架已齐**，这条留作专项场景（与 T2/T3 的未覆盖分支同批）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测：`lumber_failure_check` 应 6/6，其中
`tool_missing=PASS(status=FAILED reason=tool_missing)`、`all_rejected=PASS(… trunk_too_tall)`；
`lumber_job` 与电池行为不变）。

### D-128 附注（2026-09-12 11:44 客户端实测：5/6，**夹具抓到我自己的顺序 bug**）

**实测**：`tool_missing=PASS(status=FAILED reason=tool_missing)` ✓、`all_rejected=PASS(… trunk_too_tall)` ✓，
`lumber_job` 正常路径不变（`trees 5/5 logs 23/23 cleared=9 scaffoldLeft=0 ticks=750`）✓；
但 **`inventory_full=FAIL(status=FAILED reason=tool_missing)`** ✗。

**根因（顺序即语义）**：我把"缺斧 ⇒ tool_missing"的前置检查放在了"背包放不下"**之前**；
而 `INVENTORY_FULL` 用例是把背包**塞满圆石**（连快捷栏一起覆盖 ⇒ 斧头也没了）⇒ 该用例被误判成
`FAILED tool_missing`，而它的既定语义是"**停止收工**：`DONE inventory_full`，未动世界"。
（这也解释了为什么该用例此前一直 PASS：改造前只有库存这一个前置检查。）

**修正**：把缺工具检查移到库存检查**之后** —— "装不下 ⇒ 收工"优先于"缺工具 ⇒ 失败"。
（顺带登记一条夹具写实性观察：`fillInventory` 会连快捷栏一起覆盖，真实场景里塞满背包**不该**没收工具；
本轮只按既有语义修顺序，夹具写实性留待以后。）

**验证等级**：IMPLEMENTED / COMPILES（待复测：`lumber_failure_check` 应 **6/6**，
尤其 `inventory_full=PASS(status=DONE reason=inventory_full)` 与 `tool_missing=PASS` 同时成立）。

### D-128 附注二（2026-09-12 12:15–12:16 客户端实测：**6/6 PASS**，J7 失败码骨架收口）

```
[FailCheck] SUMMARY no_candidates=PASS(status=FAILED reason=no_reachable_candidate)
   all_rejected=PASS(… tree@22,64,218:trunk_too_tall)
   inventory_full=PASS(status=DONE reason=inventory_full)          ← 库存优先于缺工具（顺序修复生效）
   goal_timeout=PASS(status=FAILED reason=goal_timeout ticks=41)
   log_replaced=PASS(status=DONE reason=quota_met replacedPos 仍为圆石=true)
   tool_missing=PASS(status=FAILED reason=tool_missing) → PASS（**6/6**，两轮均 terminal=COMPLETED）
[Job] lumber 背包放不下任何原木，直接结束（未动世界）
[Job] lumber 缺少砍伐工具（快捷栏无斧）⇒ FAILED tool_missing
```
另：`lumber_job` 正常路径不受影响（`trees 5/5 logs 23/23 cleared=9 scaffoldLeft=0`）。

**验证等级**：`WINDOWS_CLIENT`。**J7（Step 1–4）整条收口**：
Step 1 生命周期闭环（D-107）/ Step 2 攀爬兜底（D-109）/ Step 3 崩溃兜底（D-127）/ Step 4 失败语义收敛（D-128）。

## D-129 J8 Slice A：可持续伐木区（MAINTAIN）——区域状态 + 巡查编排 + 复用一次性伐木（2026-09-12）

**用户裁定（本轮）**：开始 J8；**补种的树苗不需要与原树一一对应**，但**要留一个接口让用户选择用哪种树苗**。

**§13 的口径**：MAINTAIN 与"自动找树砍"是**同一个 Job 家族 + 不同 `GoalSpec`/策略**，不是两套任务；
区域不变量 = 无未砍完的树、无我方残留、欠树则补种；停止只由玩家命令触发；常驻任务**必须有健康输出**。

**Slice A（本轮，先不补种）**
1. **`LumberRegionState`（`SavedData`，与账本同族）**：按 owner 存 区域边界 / **我种下的树苗位置** /
   上次巡查 tick / 累计统计 / **用户选定的树苗物品 id**（`saplingItem`，用户接口的存储位，
   补种逻辑在 Slice B 用）。跨会话持久化：`MAINTAIN` 必须记得"这片区域该长什么样"。
2. **`RegionLumberJob`（L3）**：只做编排 —— `PATROL`（每 N tick 巡查：候选源扫区域 → 按**区域边界**过滤
   → 排除本轮试过的 → 策略挑最近）→ `HARVEST`（**原样内嵌 `LumberJob`，quota=1、center=那棵树**）
   → 结算 → 回 `PATROL`。因此清障预算 / 建拆同权 / 攀爬兜底 / 失败语义**全部沿用已验证的那一套，没有第二份实现**。
   - 健康输出：每次巡查一行 `[Job] maintain region=… viable=… inRegion=… mySaplings=… chopped=… lastPatrol=…`；
   - 终止：连续 `IDLE_PATROLS=3` 次无活 ⇒ `DONE idle_no_work`（§13.3：如实待机，**不算失败**）；
     本轮全部树都失败 ⇒ `FAILED no_reachable_candidate` + 逐树理由；缺斧 ⇒ 内嵌 Job 的 `tool_missing`。
3. **入口**：`alice:region_lumber`（零参数右键，夹具区域 = `LumberCourseAnchor.REGION_MIN/MAX`，
   与场景盒对齐）；`BotManager.assignRegionLumber` 负责**发料 + 写区域 + 起 Job**（与一次性伐木入口对称）。
4. **串联电池新增第 10 步 `region_maintain`**（同一伐木场景，巡查间隔压到 20 tick 便于测试）。

**验证入口**：右键 `alice:region_lumber`（或跑电池）→ 期望：
`[Job] maintain pick tree@…` 逐棵 → 5 棵砍完 → `[Job] maintain SUMMARY … chopped=5 failed=0 reason=idle_no_work → DONE`。

**Slice B（下一步，已设计未实现）**：**补种**（`KEEP` 策略：计划内永久修改，与脚手架 `TEMP` 同账本不同策略）
+ **树苗选择接口**（`/alice region sapling <item_id>` 写 `LumberRegionState.saplingItem`，`/alice region info` 读数；
用户已裁定"不必与被砍的树一一对应"）+ 欠树判断（区域目标棵数）与巡查周期配置。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测：`region_lumber` 或电池第 10 步 `region_maintain=PASS`）。

### D-129 附注（Slice A 客户端验收）+ Slice B：补种与树苗选择接口（2026-09-12）

**Slice A 实测（12:30–12:31，`WINDOWS_CLIENT`）**
```
[Job] region_lumber 区域=17,58,203..37,80,231 saplingItem=-（补种树苗由用户选择，见 /alice region）
[Job] maintain region=… viable=5 inRegion=5 … → pick tree@20,64,208 reason=nearest d=3.2 candidates=5 → 完成 chopped=1
…（19 → 29 → 28 → 33，逐棵；每轮巡查都有一行健康输出）…
[Job] maintain region=… viable=0 inRegion=0 chopped=5 failed=0   ×3（连续无活）
[Job] maintain SUMMARY region=… chopped=5 failed=0 patrols=8 mySaplings=0 reason=idle_no_work → DONE
```
即："巡查 → 挑一棵 → 复用一次性 `LumberJob` 砍掉 → 继续巡查 → 无活待机"整条链路 ✓。

**Slice B（本轮）**
1. **补种理由**：`WriteReason.REGION_REPLANT`（`temporary()==false` ⇒ 账本记 **`KEEP`**）——
   与脚手架的 `STEP_PLACEMENT`（`TEMP`，必须配对拆除）**刻意区分**（§13.2：补种是区域不变量的一部分）。
2. **区域状态扩展**：`pendingReplant`（砍完留下的树桩格，待补种）+ `baselineTrees`（**区域目标棵数**，
   首次巡查按当时可作业树数确定 ⇒ "可持续"= 维持原状），均持久化。
3. **`RegionLumberJob` 的补种流程**（每轮巡查一次）：
   - **先对账我种的苗**（已长成树/被拔掉 ⇒ 从"我种的"销账，避免"欠树"被幽灵条目污染）；
   - `deficit = max(0, baseline − (区域内可作业树 + 我种的活苗))`；
   - `deficit > 0` ⇒ 在**自己砍过的树桩**里找一格（要求该格空气、下方是土/草）补种：
     经 `WriteBudget.consumePlace`（超限即硬停）→ `setBlock` → **账本记 `KEEP`** → 扣掉一个树苗 →
     `mySaplings += spot` → `pendingReplant -= spot`；日志 `[Job] maintain plant sapling@…`；
   - **未选择树苗** ⇒ `FAILED sapling_unavailable`（附"用 `/alice region sapling <item>`"）；
     **选了但背包里没有** ⇒ `FAILED tool_missing`（§13.3 口径）；
   - `idle_no_work` 只在不欠树时判定（欠树说明还有活）。
4. **树苗选择接口（用户要的那个）**：
   - `/alice region sapling <item>`：写持久化的 `saplingItem`（**只收 `#minecraft:saplings` 标签**，
     其余明确拒绝）；**不必与被砍的树一一对应**（用户裁定）⇒ 想全种橡树也行；
   - `/alice region info`：区域 / 目标棵数 / 我种的苗 / 待补种 / 选定树苗 / 统计一行读出来；
   - `/alice region start`：用已保存的区域起 Job（停止：下任意 `/alice` 指令 ⇒ `cancelled:replaced`）。
   - 未配置时的**默认**：`minecraft:oak_sapling`（`assignRegionLumber` 里写默认值并打日志，便于零参数测试）。
5. 夹具：`alice:region_lumber` 与电池第 10 步都会按**选定树苗**发 8 个苗（夹具自足）。

**验证入口**：右键 `alice:region_lumber`（场景自带 4 棵同型橡树 + 云杉 + 新树）→ 期望
`chopped=5` 后 `planted=5`、`mySaplings=5`、`deficit=0` ⇒ `SUMMARY … reason=idle_no_work → DONE`；
再 `/alice region info` 复核读数。

**验证等级**：Slice A `WINDOWS_CLIENT`；Slice B = IMPLEMENTED / COMPILES（客户端待测）。

### D-129 附注二（Slice B 验收 + 用户实测发现的**设计缺口**：常驻巡查收工太早）

**Slice B 实测（12:40–12:41，`WINDOWS_CLIENT`）：补种链路完全正确**
```
[Job] maintain plant sapling@19,64,213（deficit=1 → 补种后 standing 上升；KEEP 策略）  … 逐棵树桩补种 …
[Job] maintain region=… viable=0 mySaplings=5 standing=5 baseline=5 deficit=0 chopped=5 planted=5 pendingReplant=0
[Job] maintain SUMMARY region=… chopped=5 failed=0 patrols=16 mySaplings=5 planted=5 baseline=5
                      saplingItem=minecraft:oak_sapling reason=idle_no_work → DONE
```
5 棵砍完 ⇒ 5 个树桩各补回一个苗 ⇒ `deficit=0` ⇒ `KEEP` 策略入账（不受建拆同权约束）✓。

**用户实测发现缺口（本轮修）**：随后用户**手动催熟**了树苗，但 bot 没去砍 —— 因为任务**已经收工**了
（`idle_no_work → DONE`），没人再看新长出来的树。**这是实现偏离设计**：
§13.1 说 `MAINTAIN` 是**常驻**任务（"都满足 ⇒ **巡查待机**"），而 §13.3 的 `IDLE_NO_WORK` 条件是
"连续 N 次巡查无进展**且区域内无树无苗**"。我实现成了"连续 3 次无活就收工" ✗ ⇒ 苗还在长就说再见。

**修正（`RegionLumberJob`）**
1. `idle_no_work` 收紧为**真的什么都没有**：`viable == 0 && deficit == 0 && mySaplings == 0`
   —— 只要还有我种的苗（等它长）或还欠树，任务就**常驻**。
2. **等生长时巡查退避**（§13.1「树苗生长需要真实时间，禁止高频扫描」）：间隔逐步翻倍到
   `MAX_PATROL_INTERVAL_TICKS = 600`（30 s），一发现新树/需补种立刻恢复配置间隔并打日志；
   健康输出新增 `waiting=saplings(N)/deficit(N)` 与 `interval=`。
3. **电池适配常驻任务**：`Step` 增加 `doneWhen` 谓词 —— 区域这一步改为
   "砍到 ≥1 棵 **且** 补种 ≥1 棵即判过"（不再靠预算超时，否则会把"本来就该常驻"误报成失败）。

**验证入口**：右键 `alice:region_lumber` → 砍完 5 + 补种 5 后应看到
`待机巡查：saplings(5)，间隔退避到 40→80→160→320→600 tick`；此时**手动催熟一个苗** ⇒ 下一轮巡查
发现候选 ⇒ `[Job] maintain 发现活 ⇒ 巡查间隔恢复` + `pick tree@…` 砍掉它 ⇒ 再补种回树桩。

**验证等级**：Slice B 补种 = `WINDOWS_CLIENT`；常驻巡查 + 退避 = IMPLEMENTED / COMPILES（客户端待测）。

### D-130 J8 区域语义修正（用户 2026-09-12 裁定）：**玩家只划水平范围**、**常驻只由显式打断结束**

**用户裁定原文要点**：① 区域应由**玩家**定义（夹具可以帮忙划好）；② 玩家**只划分水平区域**，
**垂直区域自适应**；③ 常驻任务"本来就应该让玩家或者决策层**显式打断**才结束"，
旧的"自动 `IDLE_NO_WORK` 收工"可以作为**之前设计的默认**（= 保留为可选模式）。

**改动**
1. **`LumberRegionState.Region` 换成"水平范围 + 自适应垂直"**：
   `(minX, minZ, maxX, maxZ, baseY, maxHeight)` —— 玩家给的两个角只取**水平** x/z 与较低的那个 Y 作基准层；
   实际生效上界由巡查**按实测树高**收紧：
   `effectiveTop = max(baseY + 8, min(baseY + maxHeight, 区域内最高原木 + 4))`（日志 `adaptiveTop=`）。
   于是"刚长高的树不会被漏掉"，也不会把整片天空算进区域。持久化格式同步更新。
2. **常驻语义**：`autoIdleStop`（区域状态里持久化，**默认 false**）—— 默认**常驻**，只由
   `/alice region stop` 或任何 `/alice` 指令（`cancelled:replaced`）打断；
   只有显式打开 `idle-stop` 时才在"无树无苗无欠"时 `IDLE_NO_WORK` 收工。
   `BotManager.stopTask(bot, reason)` 新增：按 `cancelled:<reason>` 记账并跑收尾（与"被替换"区分）。
3. **命令面（玩家接口）**：
   - `/alice region set <pos1> <pos2>` —— **只划水平范围**（竖直自适应，回执里写明）；
   - `/alice region start` / `stop` —— 启动 / **显式打断**；
   - `/alice region sapling <item>` —— 选补种树苗（只收 `#minecraft:saplings`；不必与原树同种）；
   - `/alice region idle-stop <true|false>` —— 是否启用"无活即收工"（默认 false=常驻）；
   - `/alice region info` —— 区域/基准层/上限/我种的苗/待补种/选定树苗/统计/模式。
4. 夹具 `alice:region_lumber` 仍帮忙划好场景区域（`LumberCourseAnchor.region()`，水平 17..37 × 203..231、
   基准层 58、高度上限 48）。

**验证入口**：`/alice region set …`（或右键 `alice:region_lumber`）→ 砍完/补种后**任务不应自行结束**，
日志出现 `待机巡查：saplings(N)，间隔退避 40 → 80 → … → 600 tick（常驻：只由玩家/决策层打断）`；
手动催熟 ⇒ `发现活 ⇒ 巡查间隔恢复` + `pick tree@…`；`/alice region stop` ⇒ `cancelled:region_stop`。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。

### D-130 附注（2026-09-12 12:54–13:06 客户端实测：**常驻/退避/自适应/生长循环全部达成**）

```
[Job] maintain plant sapling@33,64,208（deficit=1 … KEEP 策略）            ← 初始 5 棵砍完 + 5 个苗补回
[Job] maintain 待机巡查：saplings(5)，间隔退避 40 → 80 tick（常驻：只由玩家/决策层打断；…）
        … 80 → 160 → 320 → 600 tick（此后稳定 600，每轮仍有完整健康输出）
[Job] maintain 发现活 ⇒ 巡查间隔恢复 40 tick → pick tree@20,64,208 → 完成 chopped=6 → plant sapling@20,64,208
[Job] maintain 发现活 ⇒ 巡查间隔恢复 40 tick → pick tree@28,64,208 → 完成 chopped=7 → plant sapling@…
[Job] maintain 发现活 ⇒ 巡查间隔恢复 40 tick → pick tree@20,64,208 → 完成 chopped=8 → plant sapling@…
region=x17..37 z203..231 baseY=58 maxH=48（垂直自适应） adaptiveTop=84 …
```
- **常驻**：任务一直没自行结束（客户端 13:11 退出时仍在巡查）⇒ "只由玩家/决策层显式打断" 达成 ✓；
- **退避**：40→80→160→320→600 tick（§13.1「禁止高频扫描」）✓，一发现活立刻恢复 40 ✓；
- **生长循环**：用户三次手动催熟 ⇒ 都被下一轮巡查发现、砍掉、并在树桩补种回（可持续闭环）✓；
- **垂直自适应**：`adaptiveTop=84` = 实测最高原木 80 + 4，**没有**用满 `baseY+maxH=106` ✓。

**未覆盖（登记）**：`/alice region stop`（本轮直接退客户端）与 `/alice region set`（本轮用夹具区域）
这两个**玩家接口**只有代码/命令定义证据；`idle-stop=true` 的可选模式同样未实跑。

**验证等级**：`WINDOWS_CLIENT`（常驻 / 退避 / 生长循环 / 垂直自适应）；玩家接口=IMPLEMENTED / COMPILES。

### D-131 J8 收尾小项：把三个"只有代码证据"的玩家接口做实（+ 三处收尾缺陷）2026-09-12

**本轮定位**：J8 的功能（常驻 / 退避 / 生长循环 / 垂直自适应）已 `WINDOWS_CLIENT`，
但 D-130 附注登记的**三个玩家接口**（`/alice region stop`、`/alice region set`、`idle-stop=true`）
只有代码证据。收尾时逐条走查代码，发现其中两个**本来就会坏**（不是"没测"那么简单），
外加两个"任务收尾语义"的漏项，一并修掉。

**1（真缺陷）`/alice region set` 会被「旧区域的账」污染**
- 现象（代码推演）：`baselineTrees`（区域目标棵数）与 `pendingReplant` 是**持久化**的，
  但 `/alice region set` 只是换掉 `region` 字段 —— 于是"在一个新划的空区域上启动"时，
  上一片区域留下的 `baseline=5` 会让新区域第一轮就报 `deficit=5`，每轮都去补种（无树桩可补 ⇒ 空转），
  `idlePatrols` 被清零 ⇒ **永远不会待机**。`mySaplings` 同理：界外的旧苗仍被算进 `standing`。
- 修法（`LumberRegionState.setRegion`）：**区域被重新划定（与旧的不同）⇒ 派生记账全部作废** ——
  `baselineTrees=0`（下轮按新区域现场重推）+ 丢掉**水平范围之外**的苗/待补种位置（方块本身不动）；
  划**同一个**区域是幂等重入，不动记账（否则"砍完树后重启一次"就会把目标棵数丢掉）。
  日志：`[Job] maintain 区域重划 ⇒ 派生记账重置（旧 … → 新 …）：baseline=0，丢弃界外苗=N 待补种=N`。
- 顺带：`/alice region set` 的 `maxHeight` 原来取**夹具常量** `LumberCourseAnchor.REGION_MAX_HEIGHT`，
  改为区域语义自带的 `LumberRegionState.DEFAULT_MAX_HEIGHT=48`（玩家接口不该依赖测试夹具的常量）；
  另外在"当前有任务在跑"时如实提示"新区域在下一次 `/alice region start` 生效"
  （运行中的 Job 持有启动那一刻的区域对象，重划不回灌）。

**2（真缺陷，与 1 同源）目标棵数该把「我种的苗」算进去**
- `baseline = 首次巡查时的可行树数`，而 `standing = 可行树 + 我种的苗`。
  于是在一片"**已经砍完、只剩苗**"的地块上启动（正是 `/alice region set` 后的常见状态）时
  `baseline` 退化成 0 ⇒ 之后再也补不回"欠 N 棵"。改为 `baseline = standing`（= 树 + 苗），
  日志写明出处：`baseline=5（首次巡查确定 = 现场可作业树 5 + 我种的苗 0；之后按它算欠树）`。
  空区域仍是 0 ⇒ `idle_no_work` 分支不受影响。

**3（真缺陷）显式打断的终态语义对不上文档**
- `BotManager.stopTask` 的 javadoc 写"按 `CANCELLED_BY_USER` 记账（与'被新指令替换'区分开）"，
  但代码发的是 `CANCELLED_REPLACED` —— 常驻任务的**正常结束方式**（玩家叫停）被记成"被替换"。
  新增枚举值 `TaskExecutionRecord.TerminalStatus.CANCELLED_BY_USER`，`stopTask` 改用它
  （`code=cancelled:<reason>` 不变，如 `cancelled:region_stop`）；枚举无 switch 消费者，纯增量。
- `/alice region stop` 回执补齐**现场事实**：`…（region_stop）；账本已闭合（无我方临时方块残留）`
  或 `…；**账本仍有 N 条我方临时方块未拆**（/alice restore 可清理）` ——
  打断是正常结束，但可能停在"脚手架上/半棵树"的中间态，不能是黑箱。

**4（潜在、可见）`clearTask()` 不清残留移动输入**
- `BotController.onUpdate()` **每 tick** 把上次留下的 `forward/strafing/jumping` 压到 bot 上，
  而清任务之后没有任务再 drive 它（`PathSession` 只在自己被 tick 到终态时才 `stopMovement()`）。
  显式打断恰好停在半路 ⇒ 不归零就可能"说停了却还在走/还在跳"。
  修法：`BotSession.clearTask()` 里 **有残留输入才** 归零（`hasActiveMovement()` 判定 + 一行日志），
  保持"任务收尾 = 清任务/清作用域/清高亮/+输入归零"这条契约完整。

**玩家接口的读法（收尾顺带说清）**：只读/写配置的 `/alice region info|sapling|idle-stop|set`
**不打断**正在跑的任务（否则没法在运行中调档）；会分配任务的指令（`start`、`mine`、`follow`…）
才替换任务（`cancelled:replaced`）；**只有 `/alice region stop` 是显式打断**。

**验证入口（一次客户端跑完，零坐标计算）**
```
① 右键 alice:region_lumber → 常驻巡查（确认 chopped 在涨）
② /alice region stop                     → 回执 + task_execution_terminal
                                            terminal=CANCELLED_BY_USER code=cancelled:region_stop
③ /alice region info                     → 区域**仍在**（打断不丢区域）、autoIdleStop=false
④ 走到一片空地站定（附近没有树）：
   /alice region set ~ ~ ~ ~8 ~ ~8        → 回执"已设定 x… baseY=… maxH=48 …"（重划 ⇒ baseline 重推）
   /alice region info                     → baseline=0（旧区域的 5 已被清掉）
⑤ /alice region idle-stop true           → 回执 idle-stop=true
⑥ /alice region start                    → 约 3 轮巡查后
                                            [Job] maintain SUMMARY … reason=idle_no_work → DONE
                                            （空区域 + 无苗 + 无欠 ⇒ 旧的可选模式）
⑦ /alice region idle-stop false          → 复原默认（常驻）
```
判据：② 的 `terminal=CANCELLED_BY_USER`、⑥ 的 `idle_no_work → DONE`、④ 的 `baseline=0`
—— 三条都拿到即三个玩家接口由"只有代码证据"升级为 `WINDOWS_CLIENT`。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测；结果见下方附注）。

### D-131 附注（2026-09-12 13:35–13:39 客户端实测）：`stop` / `set` 拿到 `WINDOWS_CLIENT`，`idle-stop=true` 漏跑

**拿到的证据（latest.log）**
```
13:35:31 右键 alice:region_lumber → [Job] maintain … viable=2 inRegion=2 mySaplings=3 baseline=5 deficit=0 interval=40
13:35:35~52 逐棵 maintain tree@20/28/33,64,208 完成 chopped=1→2→3（每棵都在树桩 plant sapling@… KEEP）
13:36:00 task_execution_terminal kind=RegionLumberJob startTick=531 endTick=1111 durationTicks=580
                             terminal=CANCELLED_BY_USER code=cancelled:region_stop pos=28,64,214   ← ② 接口① ✓
13:36:00 [alice] 已显式停止任务 RegionLumberJob（region_stop）；账本仍有 4 条我方临时方块未拆   ← 残留回执 ✓
13:36:00 [alice] 任务收尾：清除残留移动输入（forward=1.00 strafing=0.00 …）                 ← 见下"意外收获"
13:36:09 /alice region info → region=x17..37 z203..231 … baseline=5 mySaplings=3 chopped=21 planted=16
                             patrols=65 autoIdleStop=false                                     ← ③ 打断不丢区域 ✓
13:37:34 /alice region set ~ ~ ~ ~8 ~ ~8 → 新 x18..26 z230..238 baseY=64 maxH=48
         [Job] maintain 区域重划 ⇒ 派生记账重置（旧 … → 新 …）：baseline=0，丢弃界外苗=3 待补种=0   ← ④ 接口② ✓
13:37:46 region info → baseline=0 mySaplings=0（旧区域的 baseline=5 已被清掉 ⇒ D-131 修复点生效）
13:37:55 /alice region start → 常驻巡查：viable=0 deficit=0 interval 40→80→160→320→600（4 分钟一直没结束）
13:39:22 /alice region idle-stop false        ← **唯一一条 idle-stop 指令，而且是 false**
13:39:46 玩家退出客户端
```

**结论**
1. **接口① `/alice region stop` = `WINDOWS_CLIENT`**：终态 `CANCELLED_BY_USER` + `code=cancelled:region_stop` ✔；
   区域在打断后**仍在**（`region info` 照旧读出 x17..37 z203..231）✔；残留回执如实报出 **4 条**未闭合临时方块
   （当轮确实停在脚手架半路，pos=28,64,214）✔ —— 这条信息以前只在日志里。
2. **接口② `/alice region set` = `WINDOWS_CLIENT`**：相对坐标 `~ ~ ~ ~8 ~ ~8` 划出 x18..26 z230..238、
   `baseY=64`（取较低角）、`maxH=48`（区域语义默认值）✔；**重划确实重置了派生记账**
   （`baseline=0`、界外苗丢弃 3 条），旧区域的 `baseline=5` 没有污染新区 ✔ —— 这正是 D-131 修的缺陷。
3. **接口③ `idle-stop=true` 仍未验证**：用户这一轮只跑过 `idle-stop false`（13:39:22），
   所以空区域在**常驻**（默认）语义下正确表现为"没有活干就退避巡查"，
   `interval` 一路 40→80→160→320→600、任务不结束。**"看不到反应" = 空区域 + 常驻默认，不是回归**；
   但也暴露一个**体验缺陷**：常驻任务没活干时**聊天里一个字都没有**（只有服务端日志），玩家无从判断。

**意外收获（第 4 条修复其实是真缺陷，不是"潜在"）**：`/alice region stop` 那一刻控制器里
**确实残留着 `forward=1.00`**（打断发生在走位途中）—— 归零前的日志把它抓了现行。
若不修，玩家会看到"命令说停了、bot 还在往前走"。

**本轮新增（针对"看不到反应"，IMPLEMENTED / COMPILES 待测）**
1. **常驻任务不许是黑箱**（§13.1）：`RegionLumberJob` 接一个可空 `observer`（`assignRegionLumber` 传入），
   - **首次**巡查发现"区域内没有可作业的树"⇒ 聊天回一句现场状态 + 怎么收工：
     `区域 x… z… 里没有可作业的树（viable=0 mySaplings=0 deficit=0）——常驻巡查中（间隔退避到 600 tick，等树长大；要它收工用 /alice region stop）`；
     `idle-stop=true` 时报的是"连续 3 次无活就收工"。**只提示一次**，不刷屏。
   - **终态**也回聊天：`idle_no_work` ⇒ `区域没有活干了（无树无苗无欠）⇒ idle_no_work 收工；想让它常驻就用 /alice region idle-stop false 再 /alice region start`；
     `FAILED` ⇒ `区域任务失败：<failure>`。
   - 观察者已退出/被移除时不再写连接（常驻任务可能比玩家在线时间还长）。
2. **`baseline=0` 的每轮重推噪声**：`baselineTrees<=0` 被当成"还没推导"，而空区域推出来就是 0 ⇒
   每轮巡查重推 + 重打一行（实测每 600 tick 一行、永久刷下去）。改为持久化的
   **`baselineDerived` 标记**（重划区域时作废；旧存档 `baseline>0` 视为已推导，不冲掉历史目标）。
   判据：空区域常驻时 `区域目标棵数 baseline=…` 只出现**一次**。

**待测**：`idle-stop=true` ⇒ `SUMMARY … reason=idle_no_work → DONE` + 上面两条聊天。
**路径**：区域已经划好（x18..26 z230..238），`/alice region start` 后跑一句 `/alice region idle-stop true`
即可（`idlePatrols` 已经 ≥3，下一轮巡查就会收工，最多 30 s），不必重划。

### D-131 附注二（2026-09-12 13:50–13:51 客户端实测）：J8 三个玩家接口**全部 `WINDOWS_CLIENT`**

```
13:50:06 /alice region start（空区域）
         [CHAT] 区域 x18..26 z230..238 baseY=64 maxH=48（垂直自适应） 里没有可作业的树
                （viable=0 mySaplings=0 deficit=0）——常驻巡查中（间隔退避到 600 tick，等树长大；
                要它收工用 /alice region stop）                    ← 新增"不是黑箱"反馈，**首轮即出** ✓
         [Job] maintain 区域目标棵数 baseline=0（首次巡查确定 …）    ← 本会话**仅此一次**
13:50:31 /alice region stop → terminal=CANCELLED_BY_USER code=cancelled:region_stop
                               回执：账本仍有 4 条我方临时方块未拆（再来一次，稳定复现）
13:50:52 /alice region start → 再来一轮：**没有**再打印 `区域目标棵数 baseline=0`
                               （`baselineDerived` 已持久化 ⇒ 去噪生效）✓
13:51:05 /alice region idle-stop false
13:51:12 /alice region idle-stop true
13:51:13 [Job] maintain SUMMARY region=… chopped=0 failed=0 patrols=85 … reason=idle_no_work → DONE
         task_execution_terminal kind=RegionLumberJob durationTicks=366 terminal=COMPLETED code=done
         [CHAT] 区域没有活干了（无树无苗无欠）⇒ idle_no_work 收工；想让它常驻就用
                /alice region idle-stop false 再 /alice region start              ← 接口③ ✓
         [Ledger] 销掉 1 条已失效条目：33,64,208(oak_sapling→oak_log)              ← 苗长成树即销账 ✓
```

**结论：J8 收尾整条闭合**
| 项 | 判据 | 等级 |
|---|---|---|
| `/alice region stop`（显式打断） | `terminal=CANCELLED_BY_USER code=cancelled:region_stop` + 残留回执 + 区域不丢 | `WINDOWS_CLIENT` |
| `/alice region set`（只划水平） | 回执 x18..26 z230..238 baseY=64 maxH=48 + 重划重置派生记账 | `WINDOWS_CLIENT` |
| `idle-stop=true`（可选收工模式） | `SUMMARY … reason=idle_no_work → DONE` + `terminal=COMPLETED code=done` | `WINDOWS_CLIENT` |
| 常驻空区域不再黑箱 | 首轮巡查即回聊天（现场状态 + 怎么收工），**只提示一次** | `WINDOWS_CLIENT` |
| `baselineDerived` 去噪 | 整会话 `区域目标棵数` **只出现 1 次**（同一区域第二次 start 不再打印） | `WINDOWS_CLIENT` |
| `clearTask` 输入归零 | 打断当场抓到 `forward=1.00` 残留并清除（真缺陷） | `WINDOWS_CLIENT` |

**遗留提醒**：`idle-stop` 是**持久化**的 —— 本轮最后一条命令是 `idle-stop true`，
所以该 bot 现在的默认是**旧的可选模式**（无活即 `IDLE_NO_WORK` 收工）。
要回到"常驻（只由玩家/决策层打断）"的 D-130 语义，跑一次 `/alice region idle-stop false` 即可。

## D-132 安全底座小批次（S-1–S-4）：否决必须带出口 + 未加载区块/边界准入 + 死探针接线（2026-09-12）

**来源**：`docs/OPEN_ITEMS_LEDGER.md` §1（风险/维生清单 9 条断言**至今全部成立、P0/P1/P2 无一实施**）
+ §2（对齐审计仍影响业务的 2 项）。用户 2026-09-12 裁定方向：**①安全底座小批次 → ②决策层接入**。

### S-1（P1-C）维生否决必须有出口
- **改前**：`SurvivalSystem` 有否决权（`shouldInterrupt`：`LAVA_CONTACT` / `SUFFOCATING`），
  但 `BotSession.tick` 在 `complete(SURVIVAL_INTERRUPTED)` 之后直接 `return` ⇒ **泡在岩浆里的 bot
  任务失败、站着不动、继续被烧**（全项目唯一的"拒绝没有出口"反例）。
- **职责分工照用户规矩**：`SurvivalSystem.nearestSafeRefuge(bot, radius[, exclude])` 是**纯查询**
  （只回答"哪个落点算安全"，**不规划路径** —— 该类 javadoc 的承诺不破）；
  移动交给新 `SurvivalExitTask extends WalkToTask`（**已客户端验收**的硬路径，`PathRequest.of` 纯通行）。
- **判据（保守）**：脚/头位都可穿过（不窒息）、脚下有真支撑（`canWalkOn`）、脚/头位都不是流体、
  **所在区块已加载**（复用 S-2 的门控，不为逃生去同步加载区块）。
- **豁免规则**：`SurvivalExit` 标记接口 —— 逃生任务**本身不被维生二次否决**，否则
  "中断 ⇒ 起逃生 ⇒ 下一 tick 又被中断"会成为每 tick 自杀循环，一步都走不出去。
  只豁免逃生动作；挖矿/伐木/放置这类会把 bot 送进危险的任务照旧被否决。
- **诚实分支**：半径内找不到安全落点 ⇒ 日志 `[Survival] …找不到安全落点：无出口（如实登记）`，
  不假装成功、也不造一个注定失败的任务。
- **验证入口**：`alice:survival_exit_check`（零参数）+ 新场景 `alice_test:survival_course`
  （脚位正常、**头顶那一格是石头** ⇒ `isInWall()`=SUFFOCATING；四周同层可走 ⇒ 逃生只需 1 步）。
  期望：`维生监测 hazard=SUFFOCATING` → `任务因维生危险中断 … reason=survival_suffocating` →
  `[Survival] 维生中断 ⇒ 逃生出口 refuge=…——启动 SurvivalExitTask` →
  `task_execution_terminal kind=SurvivalExitTask … terminal=COMPLETED`；**肉眼看到 bot 走出那一步**。
- **为什么不用岩浆做自检**：岩浆里能否爬出来取决于流体物理（本内核的 Movement 不建模岩浆游动），
  那是另一个问题；这一条只验"否决之后有没有去向"。岩浆场景登记为**未覆盖**（见总账 §5）。

### S-2（P1-A + 审计 §3.A:181）未加载区块 / 世界边界准入
- **事实（反编译证据，`RISK_SYSTEM_REVIEW_20260910.md` §2）**：服务端在未加载区块上 `getBlockState`
  **会同步加载/生成区块并阻塞主线程** —— 不是 void air。所以搜索层既不能把"没加载"错报成"到不了"，
  也不能为了看一眼就把区块拉起来。
- **照 Baritone 抄**（`/home/fb486/projects/reference/baritone/`）：
  - `BlockStateInterface.worldContainsLoadedChunk` / `isLoaded`：`provider.getChunk(..., FULL, **false**)`
    —— **从不加载区块**；`AStarPathFinder:105-112` 只在**跨区块**时 `if (!isLoaded(newX,newZ)) continue;`；
  - `AStarPathFinder` 的世界边界：`if (!worldBorder.entirelyContains(newX,newZ)) continue;`；
  - `PathExecutor:188`：执行期"目的地仍在已加载边缘"就**暂停**（不跨出去）。
- **Alice 落地**：
  1. `MovementContext.chunkLoaded(pos)`（= `level.hasChunkAt`，不加载）/ `withinWorldBorder(pos)`；
  2. **目标准入**：目标区块没加载 ⇒ 新独立状态 `PlanningStatus.GOAL_NOT_LOADED`
     （**不是** `UNREACHABLE`、**不是** `SEARCH_LIMIT` —— D-004 红线的延伸），
     失败码 `PLAN_GOAL_NOT_LOADED`，任务层映射 `walk_/place_/follow_goal_unloaded`；
  3. **节点门控**：只有跨区块时才查一次（`>>4` 比较，零成本），未加载 ⇒ **跳过这条边**；
     边界外 ⇒ 跳过；两者都进诊断串（`skipped_unloaded=/skipped_border=`），**诚实报告**。
- **验证入口**：`alice:chunk_guard_check`（零参数、纯无头规划、就地取材）：
  A 远目标（±512 格，必未加载）⇒ `GOAL_NOT_LOADED` **且规划前后那一格区块都仍未加载**（证明无同步加载副作用）；
  B 正对照（身边已加载可站格）⇒ `REACHED`（证明门控没掐死正常寻路）；
  C 世界边界（临时缩到 8 格、目标放在边界外**已加载**区块）⇒ 不可 `REACHED` 且诊断含 `skipped_border>0`，
  **随后立刻还原边界**。

### S-3（P1-B）删掉 `MineTask` 的重复维生调用
- `MineTask.tick` 自调 `SurvivalSystem.tick` + 判定，而 `BotManager` 调度循环每 tick 已经
  `SurvivalSystem.tick(...)` 并把 `HazardState` 交给 `BotSession.tick(hazard)` ⇒ **两套终态记录**
  （任务自己 `FAILED` vs 会话 `SURVIVAL_INTERRUPTED`），且与 `FollowTask` / 新 Job 层
  （`job/Job.java`：Job 不调用 `SurvivalSystem`）不一致。已删除（含两个 import）。
- 副作用（有意）：`MineTask` 的失败码不再自称 `survival_*` —— 维生一律由**会话**记
  `SURVIVAL_INTERRUPTED` + `code=failed:survival_*`，并要求有出口（S-1）。

### S-4（P0-C）接上早就写好、却零调用的流体探针
- **事实**：`FluidRiskPolicy.miningRefusal`（目标格或 6 邻格有岩浆 ⇒ `fluid_risk_lava`）全仓库
  **只有它自己的定义**；而 `MineTask.isHardTargetRefusal` 早就把 `fluid_risk_lava` 列进去了
  ⇒ 一个**永远为假**的分支（第 3 个死抽象，G7）。
- **接线**：`MiningPlanner.plan(...)` 在**任何站位/隧道规划之前**做目标确认，拒 ⇒ 直接返回 `fluid_risk_lava`；
  `MineTask.evaluateStandingPoint` 的规划失败分支**先查硬拒绝**（`isHardTargetRefusal`）——
  硬拒不许再去**加高或清障**（在岩浆旁搭柱子/清方块 = 主动把自己送进危险，而清障/加高各自还会
  起一个嵌套 `MineTask`，正是"挖穿后邻格岩浆涌入"的场景）。
- 覆盖范围不重叠：D-037 管"身体别**进**岩浆"（Movement 通行性），这条管"挖穿后**会不会涌进来**"。
- **验证入口**：`alice:fluid_mine_check`（零参数）+ 新场景 `alice_test:fluid_mine_course`
  （目标正下方 y=63 是岩浆源，目标本身是盖在坑上的石头 ⇒ 挖穿掉落物直接掉进岩浆）。
  期望 `[FluidMineCheck] SUMMARY plan_refuse=fluid_risk_lava run_refuse=FAILED_fluid_risk_lava
  no_clear_gain=true control=DONE → PASS`。

**验证等级**：IMPLEMENTED / COMPILES（四项；客户端待测见下方附注）。

### D-132 附注（2026-09-12 14:26–14:28 客户端实测）：S-4 通过；S-1/S-2 首测暴露"起点非法"这个真根因

**结果速览**
```
14:27:32 [FluidMineCheck] A 规划层 target=66,64,104 success=false reason=fluid_risk_lava
         [MiningPlanner] fluid_refusal target=66,64,104 reason=fluid_risk_lava（邻格岩浆会涌入）
         [FluidMineCheck] A 终态 status=FAILED reason=fluid_risk_lava cleared=0 gained=0
         [WRITE] break 64,64,106 stone … ; B 正对照 status=DONE
         [FluidMineCheck] SUMMARY plan_refuse=fluid_risk_lava run_refuse=FAILED_fluid_risk_lava
                          no_clear_gain=true control=DONE → PASS        ← **S-4 = PASS ✅**
14:27:55 task_execution_terminal kind=WalkToTask … terminal=SURVIVAL_INTERRUPTED
                          code=failed:survival_suffocating failureCode=unknown_failure
         task_execution_terminal kind=SurvivalExitTask target=方块@66,64,103
                          durationTicks=1 terminal=FAILED code=failed:walk_no_path   ← **S-1 逃生第一步就规划不出来**
14:26:51 [ChunkGuard] 用例 A 前提不成立：540,64,726 竟然已加载（视距过大？）
         [ChunkGuard] B 正对照 目标=25,64,211 status=UNREACHABLE movements=0
         [ChunkGuard] C 目标=52,64,214 status=UNREACHABLE skipped_unloaded=0 skipped_border=0
         [ChunkGuard] SUMMARY … → FAIL
```

**根因（代码级确认，不是猜）**：`MovementHelper.canSweepPlayer(from, to)` 的扫掠 AABB
`minY=min(from.y,to.y) … maxY=max(from.y,to.y)+1.8` **包含起点自身的体积** ⇒ 起点非法时
（头部被方块占据 = 窒息；脚/头泡在流体里）**一条边都生成不出来**，连"迈出去一步"都规划不了。
- S-1 的逃生目标就在 **1 格之外**（`方块@66,64,103`）却报 `walk_no_path` —— 正是这个原因；
- S-2 的 B/C 用例 `UNREACHABLE best=0.0`（best 还是起点）也是**同一个根因**：夹具把 bot 放在
  树冠里（`pos=28,64,214`，`y=65` 是树叶），起点自身非法 ⇒ 搜索零扩展。

**对照 Baritone（先查参考再动手）**：`MovementTraverse.cost` **只看 `dest` / `positionsToBreak`，
从不检查 `src` 的占用**；Baritone 的 `Movement` 里没有"起点扫掠"这种概念
⇒ Baritone 天然能从被堵/水里的格子规划出第一步。Alice 的扫掠是**更严格的自制偏离**（D-044 ⑨）。

**修复（D-133，见下）**：新增 `MovementProvider.appendStartEscapeCandidates` ——
**只对搜索起点**、且**常规候选为空**时启用，谓词**与执行器一致**（`to`/`to.above()` 可穿 + `to` 可站 +
目的地无流体；`dy=+1` 另加执行器要的 `from.above(2)` 可穿）⇒ 不产生"可规划不可执行"
（D-044 ⑨ 的担忧不适用：`TraverseExecutionFactory.validate` 本来也只检查目的地）。

**S-2 夹具的两个自身缺陷（与门控无关）**
1. **远目标距离写死 ±512**：本客户端视距 32 chunk = 512 格 ⇒ `540,64,726` **已加载**，
   用例 A 前提不成立。改为**逐个试** {512,1024,2048,4096,8192} 取第一个未加载的；
2. **正对照选了"窗口内第一个可站格"**：`25,64,211` 与起点之间隔着树冠墙（z=212..214 的通行红线）
   ⇒ 本来就不可达。改为**紧邻一格**（4 正 + 4 斜），并先做"前置：把 bot 放到干净落点"
   （扫描 ±4 找脚/头可穿 + 有支撑 + 无流体），否则起点非法会污染全部用例。

**顺带修的观测缺陷**：维生中断的终态记录里 `failureCode=unknown_failure`（任务被中断时
`failureReason()` 为空）⇒ `BotSession.failureReportFor(...)` 按会话事实补 `phase=survival` 报告，
使 `failureCode` 与 `code=failed:survival_*` 一致。

## D-133 起点脱困：`appendStartEscapeCandidates`（S-1 物理前提，2026-09-12）

**问题**：`canTraverse` 末尾 `canSweepPlayer(from, to)` 的扫掠包含**起点体积** ⇒ 起点非法
（窒息 / 泡在流体里）时搜索**零扩展**，"从危险里迈出一步"在规划层就被否决（D-132 附注实测）。

**裁定**：**只对搜索起点放宽**，且**只放宽到执行器的谓词**（Baritone 对齐）。
- 触发条件：`current == startNode` **且** `appendCandidates` 返回**空**（合法起点永远走不到这一步）；
- 候选：8 方向 × `dy ∈ {0, +1}`，判定 = `to` 可站 + `to`/`to.above()` 可穿 + 目的地非流体
  （`dy=1` 再加 `from.above(2)` 可穿，对齐 ASCEND 执行器）；
- **不做**起点扫掠 —— 与 `MovementTraverse.cost`（Baritone，只看 `dest`）一致；
- 诊断：`start_escape=N` 进 `PathPlan.diagnostics`，日志一行
  `[Search] start_escape 起点非法 ⇒ 按目的地谓词生成 N 条脱困候选`。

**为什么不违背 D-044 ⑨**（"扫掠是已对齐差异，不修改"）：该结论管的是**常规边**
（"可规划即可执行"的对角/贴边一致性）；本条只在"起点自身已经非法"这一种情况下放宽，
而执行器的前置条件**本来就不含起点扫掠** ⇒ 依然"可规划即可执行"。

**影响面**：正常寻路零变化（合法起点不触发）；受益者 = 维生逃生（S-1）、以及任何
"bot 被卡住/落进水里后要自救"的场景。

### D-132 附注二（2026-09-12 14:47 复测）：**S-1 完整通过**；S-2 A/B 通过，C 是夹具的第三次自身缺陷

```
14:47:17/14:47:28 两次复现（一次是被中断的 ChunkGuard、一次是 survival_exit_check 本体）：
  [Survival] 维生中断 ⇒ 逃生出口 refuge=66,64,103（距 1.000 格）——启动 SurvivalExitTask
  [Search] start_escape 起点非法 ⇒ 按目的地谓词生成 8 条脱困候选（S-1/D-133）
  [PathRetry] planned status=REACHED movements=1 cost=1.00 from=66,64,104 to=66,64,103
  [R4 Session] segment_done type=TRAVERSE ticks=5 actualFoot=66,64,103        ← **真的从方块里挪出来了**
  [WalkToTask] completed … replans=0
  task_execution_terminal kind=SurvivalExitTask … terminal=COMPLETED          ← **S-1 = PASS（含物理）**
  （终态 failureCode 也已从 unknown_failure 变成 failed:survival_suffocating）
14:47:37 [ChunkGuard] A 目标=1088,64,1126 status=GOAL_NOT_LOADED 规划后仍未加载=true  ← **A PASS**
         [ChunkGuard] B 正对照 目标=65,64,102 status=REACHED movements=1 nodes=2      ← **B PASS**
         [ChunkGuard] C 目标=88,64,102 status=UNREACHABLE … skipped_border=0 start_escape=0  ← 仍 FAIL
```

**S-1 的物理疑问被证伪（好消息）**：我原先担心"实体卡在方块内时 vanilla 碰撞会把水平位移钳到 0"——
实测**允许走出**（`ticks=5` 完成 1 格 TRAVERSE）⇒ 逃生出口不需要"破块脱身"这条更重的设计线。

**S-2 用例 C 的根因（第三次都是夹具，不是门控）**：那次 bot 站在 **5×5** 的存活场景地板上，
而临时边界是"中心 ±4" ⇒ **卡住搜索的是地板边缘而不是边界**（`best=4.0` 恰好是走到地板尽头 x=68），
所以 `skipped_border=0` 是"门控没被触发"，不是"门控坏了"。

**修法（把 C 变成确定性判据）**：
1. 边界尺寸 `8 → 3`（中心 ±1.5）：这样"距离 1 的格在界内、距离 2 的格在界外"，
   而任何 ≥3 格宽的可走面都必然存在距离 2 的格子 ⇒ 门控一定会被踩到；
2. **谓词直断**（与地形无关）：`MovementContext.withinWorldBorder(距离1)==true && (距离2)==false`
   —— 这是 C 的**主判据**；
3. 搜索层计数 `skipped_border>0` 作为**集成判据**，但先探测"本地是否有距离 2 的可走空间"
   （`local_space=`），没有就不把它算进判据（诚实前置，而不是假失败）；
4. 物品侧先调用 `findCleanStandNear` 把 bot 挪到干净落点**再**起任务 ——
   否则上一轮留下的窒息会把任务在第 1 tick 就中断掉（本次实测：ChunkGuard 被 `SURVIVAL_INTERRUPTED`）。

**教训（写给下次）**：自检夹具的**前提本身**也需要判据 —— 三次失败全部来自
"起点非法 / 距离写死 / 边界没成为约束"，没有一次来自被测的门控代码。
夹具必须先把前提**探测出来并如实报告**（`local_space=` / `NO_UNLOADED_CHUNK_IN_RANGE` 之类），
否则"假失败"会一路吃掉客户端回合。

### D-132 附注三（2026-09-12 14:58 复测）：**S-1–S-4 全部收口**（① 安全底座完成）

```
14:58:46 / 14:58:51 两次复现：
[ChunkGuard] 前置：bot 已放到干净落点 64, 64, 102
[ChunkGuard] A 目标=1088,64,1126 status=GOAL_NOT_LOADED 规划后仍未加载=true
[ChunkGuard] B 正对照 目标=65,64,102 status=REACHED movements=1 nodes=2
[ChunkGuard] C 目标=88,64,102 status=UNREACHABLE 谓词(内/外)=true/false 本地空间=true
             diagnostics=… skipped_border=8
[ChunkGuard] SUMMARY far_goal=GOAL_NOT_LOADED no_sync_load=true PASS near_goal=REACHED PASS
             border_predicate=true not_reached=true local_space=true skipped_border=true PASS → PASS
task_execution_terminal kind=ChunkGuardCheckTask … terminal=COMPLETED         ← **S-2 = PASS**
[Survival] 维生中断 ⇒ 逃生出口 refuge=66,64,103（距 1.000 格）——启动 SurvivalExitTask
[Search] start_escape 起点非法 ⇒ 按目的地谓词生成 8 条脱困候选
task_execution_terminal kind=SurvivalExitTask … durationTicks=6 terminal=COMPLETED ← **S-1 = PASS（第 3 次复现）**
```

**S-1 的端到端证据链（这次可以量化"真的脱离了危险"）**：
`hazard=SUFFOCATING` 在整个会话里**只出现 1 次**（14:58:52.697）——
逃生成功后危险观测就再没出现过（对照：修复前那一轮刷了 400+ 行、bot 原地被烧）。

**S-3 的结构性证据**：`survival_suffocating` 只出现在两处 ——
会话的 `任务因维生危险中断` 一行 + **一条** `kind=WalkToTask … terminal=SURVIVAL_INTERRUPTED`；
**没有**任务侧自己记的第二条 `terminal=FAILED code=failed:survival_*` ⇒ "维生终态只由会话产生"成立。

**顺带修的紫黑块**：三个新物品漏了物品模型（`Unable to load model: 'alice:chunk_guard_check#inventory'`）
⇒ 已补 `assets/alice/models/item/{chunk_guard_check,fluid_mine_check,survival_exit_check}.json`
（复用既有贴图 `guard/mine/check`）。这属于"必须主动问的紫黑贴图"那一类现象，本次由日志自查发现。

**① 安全底座小批次收口表**

| 项 | 判据 | 等级 |
|---|---|---|
| S-1 维生否决必须带出口 | `[Survival] 逃生出口` → `start_escape` → `SurvivalExitTask COMPLETED`；危险只被观测 1 次；3 次复现 | `WINDOWS_CLIENT` |
| S-2 未加载区块/边界准入 | A `GOAL_NOT_LOADED` + `no_sync_load=true`；B `REACHED`；C 谓词 `true/false` + `skipped_border=8`；2 次复现 | `WINDOWS_CLIENT` |
| S-3 删重复维生调用 | 维生终态只由会话记一次（无任务侧重复终态） | `WINDOWS_CLIENT`（结构性） |
| S-4 流体探针接线 | `[FluidMineCheck] SUMMARY plan_refuse=fluid_risk_lava run_refuse=FAILED… no_clear_gain=true control=DONE → PASS` | `WINDOWS_CLIENT` |
| D-133 起点脱困 | 起点非法时 `start_escape=N`，且"可规划即可执行"保持（执行器同款谓词） | `WINDOWS_CLIENT` |

**→ 下一步进入 ② 决策层接入**（用户 2026-09-12 裁定 ①→②）：先补 Job 契约缺口
（`Job.terminalReason()` → `TaskOutcome`/`TaskExecutionRecord`、`botUuid`、`GoalSpec`→`Job` 统一入口），
再与用户确认 LLM 的输入快照 / 动作词汇表 / 触发节奏后实现目标级决策循环。

## D-134 ② 决策层接入 · 第 1 步：Job 契约批次（2026-09-12）

**背景**：① 安全底座已全部 `WINDOWS_CLIENT`（D-132 附注三）。用户裁定 **①→②** 后，
本轮先补"**决策层要用的契约**"——即总账 §3 的 J-1/J-2/J-3。这三条不补，LLM 拿到的终态
分不清"达成了"与"提前收工"，也没有"用一个动作起任意 Job"的入口。

### J-1 终止理由进终态记录
- 改前：`resultCode` 只有 `done` / `failed:<reason>`，而 `Job.terminalReason()`（`quota_met` /
  `inventory_full` / `partial_quota` / `idle_no_work` / `no_reachable_candidate` …）**只进日志**。
- 改后：`TaskExecutionRecord` 与 `TaskOutcome` 各加两个字段 —— `botId` 与 `terminalReason`；
  `BotSession.recordTerminal` 从 `task instanceof Job` 取值（非 Job 为空串），并新增一行可 grep 的证据：
  ```
  task_terminal_reason kind=LumberJob botId=… terminalReason=quota_met
  ```
- **兼容**：全部便捷构造器补默认值（null ⇒ `""`），既有调用点与断言（`code=done`）**不变** ——
  刻意不动 `resultCode` 的形状，避免打破已验收的判据。

### J-3 `botId`
- 同上两个字段一并落地（`MULTI_BOT_INTERFACE_RESERVATION.md:7` 的期望：终态记录能关联 bot）。
  目前是 UUID 字符串；将来多 bot 只需要在记录里按它分组，不用再改数据结构。

### J-2 `GoalSpec` → `Job` 统一入口
- 改前：`BotManager` 逐域硬编码（`assignLumberJob` / `assignMineJob` / `assignRegionLumber`），
  调用方必须自己 new 具体 `Job` 类 ⇒ 决策层无法"用一个动作起任意 Job"。
- 改后：
  - `job/JobRequest`（record）：`kind`（`LUMBER` / `MINE` / `REGION_LUMBER`）+ `center` / `radius` /
    `quota` / `maxTicks` / `productTag` / `region`；**只装目标级参数**（怎么走、怎么挖属于 L1/L2 能力信封）；
  - `job/JobLauncher`：**唯一构造点** + **入口发料**（D-119/D-122：发料必须在入口，否则
    "LLM 起的 Job 徒手砍树"迟早复发；未知挖掘目标 ⇒ **回落到默认并如实登记，不猜语义**）；
  - `BotManager.assignJob(bot, observer, JobRequest)`：发料 → 构造 → 登记会话 → 广播目标；
  - 既有领域入口保留（夹具/命令兼容），但 `assignMineJob` 已改为"经同一套 `JobLauncher` 记录 + 构造"。
- ⇒ 决策层的动作词汇表由此收敛成 **`start_job(kind, spec)` 一条**。

**验证入口**：`alice:job_launcher`（零参数右键，自带 `lumber_course` 复位与传送）。
期望：`[Job] launch bot=… kind=LUMBER … quota=2 …` → 正常伐木 → `kind=LumberJob terminal=COMPLETED`
→ **`task_terminal_reason … terminalReason=quota_met`**。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。

### 下一步（② 第 2 步，待用户确认一处事实）
LLM 循环的**管道**可以先做且可验（快照契约 / 动作词汇表 / 事件驱动 + 节流触发 / 严格拒绝未知动作），
但**真实 provider 不能猜**：需要用户给出可用的端点与模型（或明确"先用脚本化假 LLM 验管道"）。

## D-135 ② 决策层接入 · 第 2 步：目标级决策循环（2026-09-12）

**用户裁定（原文）**："按默认做吧" + "你可以直接移植当前使用的api配置，不要读取key，只操作复制，然后你可以直接下一步"。

### 落地范围（用户默认口径）
- **输入快照**：只放服务端**已经知道的事实**（bot 状态/危险/背包摘要/当前任务/**上一条终态记录**（含 D-134 的
  `terminalReason`/`botId`）/区域状态/账本待清理数）；**不放**方块级世界细节与执行器内部状态
  ——否则 LLM 会开始管执行细节，越过"只做目标级决策"的边界。也不放任何凭据。
- **动作词汇表**（6 种形态 / 4 类）：`start_job(lumber|mine|region_lumber)` / `stop_current` /
  `report_status` / `no_op`。**未知动作、未知 kind、缺字段、参数超范围 ⇒ 拒绝**（`Refused`），
  不做"尽力猜测"；数值参数**夹取到安全区间**并记一行日志（LLM 多写个 0 不该崩服务器）。
- **触发节奏**：事件驱动（任务终态 / 维生中断）+ 空闲触发（**默认关**，`idleDecisionEnabled=false`）
  + 手动（夹具）。三道节流闸：最小间隔 tick、每分钟请求上限、同一时刻最多 1 个在飞。
- **执行**：`start_job` 只能走 `BotManager.assignJob`（= D-134 的统一入口，发料+构造都在那一处）；
  `stop_current` 走 `BotManager.stopTask`；拒绝动作**不动世界、不改任务**。

### 实现
| 文件 | 作用 |
|---|---|
| `decision/LlmConfig` | 读 `config/alice-llm.json`（不存在⇒写模板 + 如实登记"未配置 ⇒ 保持确定性策略"）；**永不打印 key** |
| `decision/LlmClient` | OpenAI 兼容 `POST {url}` → `choices[0].message.content`；单线程池异步（**绝不在主线程等**）；HTTP/超时/解析失败如实回报 |
| `decision/DecisionSnapshot` | 权威状态快照（JSON）+ prompt 包装；快照原文进日志 `[Goal] snapshot` |
| `decision/GoalAction` | 严格解析（容忍 ```json 围栏、容忍前后解释文字，取第一个**平衡** JSON 对象）；`Refused` 分支 |
| `decision/GoalDirector` | 触发 + 节流 + 执行 + 决策 trace（`[Goal] decision_request/decision_action/execute`） |
| `item/GoalDirectorItem` | `alice:goal_director` 零参数右键：打印配置（不含 key）+ 快照摘要 + 强制一次决策 |

### 配置移植（**只复制，不读取**）
从当前部署读出：provider `deepseek-official`、baseURL `https://api.deepseek.com`
（`dsh-llm-deepseek` 默认，`$DEEPSEEK_BASE_URL` 未设置）、model `deepseek-flash`、
key 在 `~/.dsh/.credentials.yaml:refs.DEEPSEEK_API_KEY`（len=35，**脚本直接复制，未打印**）。
写入 `<client>/config/alice-llm.json`（`enabled=true`）。

### 实测发现（WSL 侧已用**同一条请求**打过真实 API）
1. **`deepseek-flash` 是推理模型**：会先输出 `reasoning_content`。`max_tokens=16` 时预算全烧在推理上，
   正文只剩 `"p"` ⇒ **`max_tokens` 必须给足**（改为可配置，默认 2000；实测 2000 时
   `completion_tokens=210`、`reasoning_tokens=189`、正文完整）。
2. **它真的会按词汇表回答**：用真实 prompt 打过去，回复
   `{"action":"start_job","kind":"region_lumber","maxTicks":24000}` —— 而那份快照里**没有区域**，
   所以 mod 侧会走 `Refused(region_lumber_without_saved_region（区域必须由玩家划定）)`
   —— 这正是设计里"**LLM 不能凭空发明区域**"的守卫生效的现成用例。
3. **空闲触发默认关**：空闲每 20 s 一次真调用会持续烧钱；先只做事件驱动 + 手动，
   需要时用 `idleDecisionEnabled=true` 打开。

**验证入口**：`alice:goal_director`（零参数右键）—— 期望：
```
[alice] 决策层配置：enabled=true usable=true model=deepseek-flash url=… apiKey=已配置 …
[Goal] snapshot chars=… json={"bot":…,"task":{…"terminalReason":"quota_met"…},…}
[Goal] decision_request trigger=manual model=deepseek-flash
[Goal] llm_reply id=… latency=…ms chars=…
[Goal] decision_action trigger=manual raw={"action":…} → StartJob(kind=…) / Refused(…)
[Goal] execute action=… 
```
**验证等级**：IMPLEMENTED / COMPILES + 真实 API 冒烟（WSL 侧同请求）已通过；mod 内端到端待客户端。

### D-135 附注（2026-09-12 15:28 客户端实测）：配置/快照都对，卡在**出网代理**；并抓到我自己一个 bug

```
15:28:32 [alice] 决策层配置：enabled=true usable=true model=deepseek-flash
         url=https://api.deepseek.com/chat/completions apiKey=已配置 timeout=25000ms 间隔>=100tick
         空闲触发=关 maxTokens=2000                      ← 配置与"不含 key"的日志都对 ✓
15:28:32 [Goal] snapshot chars=524 json={"bot":{…},"inventory":…}    ← 快照契约正常 ✓
15:28:32 [Goal] decision_request trigger=manual model=deepseek-flash
15:28:32 [Goal] llm_request id=1 model=deepseek-flash promptChars=584
15:28:42 [Goal] llm_transport_error id=1 after 10045ms: java.net.http.HttpConnectTimeoutException: HTTP connect timed out
15:28:42 [Goal] decision_failed trigger=manual error=transport:HttpConnectTimeoutException
15:28:42 [Goal] decision_request trigger=idle(606tick) …           ← **空闲也触发了（我的 bug）**
```

**根因（事实，非推测）**：Windows 上开着**系统代理** ——
`HKCU\...\Internet Settings`：`ProxyEnable=1`、`ProxyServer=127.0.0.1:7897`；
而 **Java 的 `HttpClient` 默认不读 Windows 系统代理**（`java.net.useSystemProxies` 默认 false），
于是 mod 直连 → 连接被挡 → 10 s 连接超时。
WSL 侧对照：`curl -4` 直连 `api.deepseek.com` **22 ms / HTTP 401**（401 = 只差鉴权，说明网络通），
`curl -6` 不通（IPv6 不可用）。

**修复（本轮）**
1. **代理支持**：配置新增 `proxy`（`host:port`，空=直连），`HttpClient` 按它建；并设
   `java.net.useSystemProxies=true`（在代理选择器初始化前）作为"自动读系统代理"的兜底；
   **代理失败 ⇒ 自动改直连重试一次**并如实登记走了哪条（用户关掉代理后不用改配置也能通）；
2. **诊断**：每次请求前打 `[Goal] llm_dns host=… → <IP列表>`（IPv6 不通 / DNS 污染一眼可见），
   请求行带上 `proxy=`；
3. **修我自己的 bug**：`idleDecisionEnabled=false` 配了却没读 —— 空闲触发没有加闸，
   导致空闲时每 ~10 s 反复发请求（全部超时）。已在 `GoalDirector.tick` 里补上这道闸
   （教训与 D-132 附注三同源：**配置项存在 ≠ 被读；闸必须有判据**）。

### D-135 附注二（2026-09-12 15:35 复测 + 系统取证）：**不是代码、不是地址，是那个 JVM 的外网被安全软件静默丢弃**

```
15:35:11 [Goal] llm_dns host=api.deepseek.com → 36.147.63.115 120.226.37.16     ← DNS 正常
15:35:11 [Goal] llm_request id=1 proxy=127.0.0.1:7897
15:35:21 [Goal] llm_proxy_failed proxy=127.0.0.1:7897 HttpConnectTimeoutException ⇒ 改直连重试一次
15:35:31 [Goal] llm_transport_error after 20055ms: HttpConnectTimeoutException   ← 直连也超时
```

**系统取证（逐条事实）**
| 检查 | 结果 |
|---|---|
| Windows 系统代理 | `ProxyEnable=1`、`ProxyServer=127.0.0.1:7897`；`netstat` 显示 **mihomo(PID 26228) 正在听**，且有大量 ESTABLISHED ⇒ 代理本身活着 |
| Windows 防火墙 | `Get-NetFirewallProfile`：Domain/Private/Public **Enabled=False**（全关）⇒ 不是它 |
| 第三方安全软件 | **火绒 HIPS 在跑**（`HipsDaemon.exe`、`HipsTray.exe`） |
| Windows `curl.exe` 直连 API | `http=401 connect=0.066s` ⇒ 机器出网正常（401 = 只差鉴权） |
| Windows `curl.exe` 走代理 | `http=401 connect=0.0008s` ⇒ 代理对普通进程也正常 |
| Minecraft 的 JVM | `D:\JDK-21\bin\java.exe`；代理与直连**都** `HttpConnectTimeoutException` |

⇒ **结论：该 JVM 进程的外网 TCP 被静默丢弃（丢弃=连接超时，不是拒绝），头号嫌疑是火绒的联网控制**
（Windows 防火墙已排除）。这与 JVM 版本/代码无关：同一台机器、同一目标 IP，`curl` 通、`java.exe` 不通。

**两条修法（都落地）**
1. **正解（需要用户操作）**：把 `D:\JDK-21\bin\java.exe` 加进火绒的"允许联网"（或首次弹窗时选允许）。
   之后可把 `config/alice-llm.json` 的 `relayUrl` 清空，让 mod 直接走 API。
2. **开发兜底（本轮已通）**：`tools/llm-relay.py` —— 跑在 **WSL**（那侧直连 API 22 ms 正常），
   把 `POST /chat/completions` 原样转发上游；mod 只需访问 `http://127.0.0.1:8791/chat/completions`
   （Windows→WSL 的 localhost 转发）。**已从 Windows 侧验证**：`curl.exe → http=200`（11 ms）。
   启动：`python3 tools/llm-relay.py --port 8791`（key 只从配置读，不打印；只监听 127.0.0.1）。

**mod 侧硬化（本轮）**：`LlmClient` 改为**路径矩阵** —— 依次试 `relay` → `api+proxy` → `api+direct`，
取第一条成功的并**记住**（`chosenPath`），失败时清空记忆下次重探；每条路径逐条登记
`[Goal] path_try name=… target=… proxy=… → ok/异常`，成功记 `[Goal] path_selected`。
这样"哪条路通"变成**一行可读证据**，而不是靠猜；也让"用户关掉代理/开了火绒规则"之后无需改代码即可自愈。

**教训**：诊断网络问题必须**分层取证**（OS 层 curl → 代理层 → 进程层 → JVM 层），
"同一台机器 curl 通、程序不通"几乎一定指向**进程级策略**（安全软件/防火墙/沙箱），而不是代码。

### D-135 附注三（2026-09-12 15:49 复测）：**决策层静默卡死 = 我自己的线程死锁**（不是网络）

```
15:49:14 [Goal] llm_dns host=127.0.0.1 → 127.0.0.1        ← 已走到 relay 路径
15:49:14 （此后 5 分钟**一行都没有**：无 path_try、无超时、无错误）
15:54:12 玩家退出客户端
```

**取证**：中继日志**没有** 15:49 的转发记录（只有我自己 curl 的几条）⇒ 请求**根本没发出去**；
同一时刻 Windows `curl.exe → 中继` 仍 `http=200/0.67s` ⇒ 通路本身没问题。

**根因（我的实现缺陷）**：把**同一个单线程 executor** 既用来跑阻塞的 `send()`，
又用 `.executor(EXECUTOR)` 交给 `HttpClient` 当内部 executor ⇒ `send()` 占住唯一线程，
HttpClient 的内部任务排不进来 ⇒ **死锁**：请求发不出去，超时也永不触发（所以"静默"）。
上一版能报超时，是因为那个 client 在**类初始化时（服务器线程）**建的；本轮改成"每次请求内部新建"，
就把 `build()` 挪进了那个单线程里 ⇒ 立刻死锁。

**修复**
1. `LlmClient.EXECUTOR` 改为 **cached 线程池**（一个卡住的调用不再堵死整条管道）；
2. `HttpClient` **不再复用我们的 executor**（内部线程交给 JDK）；
3. 新增**裸 TCP 探针** `[Goal] tcp_probe host:port → ok/fail Nms` 与 `[Goal] path_try_begin`：
   把"连不上"与"连上了但 HTTP 层挂住"分开 —— 这次就是靠"begin 有、try 没有"定位到死锁的；
4. `GoalDirector` 新增**看门狗**：请求超过 `timeout + 5s` 仍未回 ⇒ `cancel(true)` + `decision_timeout`
   如实回报（绝不再让"点一次永远没有下文"）。

**教训（第三次同源）**：**"没有日志"本身就是一种证据** —— 本轮不是"网络又被挡了"，
而是"代码把唯一那条线程借给了自己还要等的东西"。异步 + 阻塞混用时，
**执行器绝不能借用给自己**；并且任何"可能永不返回"的外部调用都必须有**独立看门狗**。

### D-135 附注四（2026-09-12 16:43 诊断模式实测）：**三条路在游戏内全部通；此前"都超时"是我自己的实现缺陷**

`alice:goal_director` 手动触发 = 诊断模式（三条路全试、逐条登记）：
```
16:43:38 tcp_probe 127.0.0.1:8791  → ok 1ms     relay      → ok status=200 5000ms
16:43:43 tcp_probe 127.0.0.1:7897  → ok 0ms     api+proxy  → ok status=200 3074ms
16:43:46 tcp_probe api.deepseek.com:443 → ok 18ms  api+direct → ok status=200 **2078ms**
```

**结论（含一次明确的归因更正）**
- **直连在游戏内完全可用**（2078 ms）⇒ 15:28/15:35 那两次"代理与直连都 `HttpConnectTimeoutException`"
  **不是火绒、也不是机器网络**，而是**我自己的实现缺陷**：把同一个**单线程 executor** 既用于阻塞 `send()`、
  又交给 `HttpClient` 当内部 executor（饿死/死锁链）。上一轮把"火绒拦 java.exe"当成结论是**过度自信**；
  决定性反证 = 同一个 `D:\JDK-21\bin\java.exe` 在游戏外跑探针：TCP 67ms、`HttpURLConnection` 401/370ms、
  `java.net.http.HttpClient` 401/221ms。
- 修复后**默认路径改为直连优先**（`relayUrl` 清空、`proxy` 保留为第二候选），中继进程已停止
  （`tools/llm-relay.py` 保留为兜底脚本，需要时 `--port 8791` 起）。

**教训（第三次同源，这次记牢）**：**先怀疑自己的并发模型，再怀疑环境**。
"同一台机器 `curl` 通、程序不通"虽然是进程级策略的经典特征，但**它同样**是
"执行器/线程模型自己有毛病"的经典特征 —— 判据是"换成独立线程池 + 不共用 executor 后是否立刻恢复"，
而不是"哪个安全软件最可疑"。

## D-136 S1 事实层：任务树 + 事件环 + 状态报告（2026-09-12）

**背景**：决策层三条通道（`DECISION_LAYER_DESIGN.md` §2）里"汇报"这条此前**没有事实底座** ——
`BotSession` 只暴露顶层任务，内层在干什么（内嵌 `LumberJob` 砍哪棵树、卡在哪个阶段）不可见，
"汇报当前完整情况"无从谈起。

**落地**
1. `task/TaskNode`（新）：任务树节点的**只读摘要**（kind/target/phase/ticks/progress/lastFailure/children）；
2. `Job.subTasks()`（默认空）：有内嵌结构的 Job 覆写 —— `LumberJob`（内嵌 MineTask/CollectDropsTask/
   RestoreScopeTask）、`RegionLumberJob`（内嵌 LumberJob）、`MineJob`（内嵌 MineTask/CollectDropsTask）；
3. `decision/BotEventLog`（新）：每 bot 定长事件环（32 条，类型 DANGER/FAILURE/RECOVERY/MILESTONE/
   COMBAT/COMMAND）。**按需读，不推送**（LLM 不该每 tick 收东西）；
4. `DecisionSnapshot` 增加 `tree` 与 `recentEvents` —— **玩家报告与 LLM 快照共用同一份事实**
   （所以"报告错了"与"LLM 看到的错了"不可能分叉）；
5. `decision/BotStateReport`（新）：把同一份 JSON 渲染成聊天可读多行（任务树缩进 + 上次终态
   （含 `terminalReason`）+ 背包 + 账本 + 事件倒序）；
6. 零参数入口 `alice:bot_report`；完整 JSON 同时进日志 `[Report] json=…` 便于逐字段核对；
7. 事件钩子：任务终态（COMPLETED⇒MILESTONE / 其他⇒FAILURE，带 `terminalReason`）、
   维生中断（DANGER）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测；判据 = 报告字段与实际一致、事件环顺序正确）。

## D-137 掉落物搜索 + 捡拾进任务候选（用户 2026-09-12 要求）

**用户原话**："像捡拾掉落物的子任务，最好也加入任务候选里，只是包装成掉落物搜索加捡拾，有时候还是会用到。"

**为什么之前没有**：捡拾一直只是**收尾动作**（`CollectDropsTask` 被伐木/挖掘复用），没有"**主动去捡**"的入口。

**落地**
- `job/collect/CollectJob`（新）：`SCAN`（读事实：范围内有哪些掉落物 → 挑最近一簇）
  → `COLLECT`（复用**已验收**的 `CollectDropsTask`，簇内最多 16 件）→ 回来继续 `SCAN`，
  直到没得捡 / 达配额 / 超时；终态理由 `collected` / `quota_met` / `none_found` / `goal_timeout`；
  一簇捡不动**不判死**（记一笔继续下一簇，与伐木"换候选"同一哲学）。
- `JobRequest.Kind.COLLECT` + `JobLauncher` 分支 + 词汇表 `{"action":"start_job","kind":"collect",…}`；
  同时把早已存在却**零消费者**的 `GoalSpec.Kind.COLLECT_ITEMS`/`collectItems(...)` 用起来（总账 §3 J-5）。
- **安全边界（重要）**：默认**只捡我方登记过的掉落物**（`ScopeBuffer`，我方破坏事件产生或夹具显式收养）；
  捡**任意无主掉落物**需 `anyDrops=true`，而它**不进 LLM 动作词汇表** —— 因为那可能包括**玩家自己的东西**，
  要等 S3 请示通道（捡玩家物品必须 ASK）。`JobRequest` 里的 `anyDrops` 字段只给夹具/命令用。
- 验证入口：`alice:collect_job`（零参数）—— 夹具在 bot 旁生成 3 堆圆石并 `adoptExistingDrops` 登记，
  再起 `COLLECT`（走**安全默认**那条路）。判据：`[Job] collect pick cluster@…` →
  `done collected=…` → `SUMMARY reason=collected`；**玩家自己的东西不动**。

## D-138 掉落物归属 / 收集授权 / 被动拾取：用户裁定（2026-09-12）

**用户裁定（原文"我同意"，即接受以下四条建议 + 被动拾取档位）**

| # | 议题 | 定案 |
|---|---|---|
| 1 | `FOREIGN`（玩家丢的/未知来源）默认档位 | **`ASK`** —— 问一次，**超时=不捡** |
| 2 | `OURS_INDIRECT`（我方行为间接后果）归属窗口起点 | **60 tick / 4 格**，**可配置**且**写进报告**便于实测标定 |
| 3 | 授权入口形态 | **选区物品右键**（零参数、可重复）为主 + 命令兜底 |
| 4 | `always` 级授权（写配置永久生效） | **允许**，但**必须在报告与日志里显式标记**（"该区域 FOREIGN 已授权"） |
| 5 | **被动拾取**默认档位 | **`auto`**（只放行 `OURS_*`；`FOREIGN` 转 `ASK`）；另保留 `off`（完全不被动拾取）/ `notify`（路过就捡 + 事后一行）/ `ask` |

**落地范围（并入 `DECISION_LAYER_DESIGN.md` §3.1 + 骨架 S3.5）**
- `DropProvenance = OURS_DIRECT | OURS_INDIRECT | GRANTED_AREA | FOREIGN`；
- `DropPolicy = provenance → AUTO | NOTIFY | ASK | IGNORE`（默认值进配置，玩家可改）；
- `CollectGrant { area, until, provenanceMask, scope=once|session|always, grantedBy }`；**`anyDrops` 退役**；
- **授权只能由玩家或配置签发**，决策层（LLM）只能请求；
- `PickupGate`（`EntityItemPickupEvent`，可取消）与 `CollectJob` **共用同一份 `DropPolicy`** ——
  两条路径（主动 / 被动）不允许分叉；
- 每条归属判定留证据（进事件环与报告）：`provenance=OURS_INDIRECT because=owner_window(…)`。
- 连带必修：`CollectDropsTask` 区分 `policy_blocked` 与 `pickup_timeout`（不空转到超时）；
  `CollectJob` 候选先按策略过滤。

**验证矩阵（四类 + 被动）**：① 我方直接自动捡（已验）② 我方间接（树叶衰减/仙人掌甘蔗）自动捡
③ 玩家丢的**不捡**（ASK，超时=不捡）④ 授权区**可捡**（含玩家物品）⑤ 被动：路过 `FOREIGN` 掉落物**不吸附**且留 `[Pickup] blocked …`。

**开工顺序（已与用户确认）**：S2 选择层 → S3 请示层 → **S3.5 收集归属 + 被动闸门**。

## D-139 S2 选择层：候选菜单 + 动作 target（2026-09-12）

**问题（D-135 附注四实测）**：动作里的 `center` 只能是 bot 当前位置 ⇒ LLM 选 `lumber` 时选中一片没树的地方，
1 tick 就 `no_reachable_candidate`。**不是它笨，是我们没给菜单。**

**落地**
1. `decision/CandidateMenu`（新）：从**服务端事实**生成**有界**选项（每类 ≤5、总数 ≤12）：
   - `tree@x,y,z`（`TreeScanner` 半径 24 内最近的树，带距离/树种/高度）→ 伐木；
   - `drops@x,y,z`（**只列我方登记过的**掉落物簇，带数量与 `provenance=OURS_DIRECT`）→ 捡拾；
   - `region:saved`（已保存区域，带 baseline/我种的苗）→ 区域型；
   - 日志一行 `[Goal] candidate_menu entries=…`（可 grep、可断言）。
2. `DecisionSnapshot.build(bot, menu)`：快照新增 `menu`；prompt 里明确"`target` 只能引用 menu 里的 id"；
3. `GoalAction.parse(reply, bot, menu)`：
   - `target` **必须命中本轮菜单**（`CandidateMenu.find`），未命中/缺失 ⇒ `Refused`（**不猜坐标**）；
   - `lumber`/`collect` **必须带 target**；`mine`/`region_lumber` 可省（区域必须来自已保存区域）；
   - target 的 kind 与动作 kind 不符 ⇒ `Refused(target_kind_mismatch)`；
   - 捡拾的 quota 会被**菜单里的实际数量**夹取（`min(quota, amount)`），避免"要 24 只有 12"的空转。
4. `GoalDirector`：每次决策前生成菜单并记进状态（`lastMenu`），解析时用它校验；`BotStateReport`
   也渲染菜单（玩家能看到"现在能做什么"）——**汇报与 LLM 仍共用同一份事实**。

**验证等级**：`WINDOWS_CLIENT`（2026-09-12 17:34）—— 菜单生成 ✓、LLM 引用 `target=tree@20,64,208` ✓、
执行成功 ✓、第一棵树 `terminalReason=quota_met` ✓、砍掉的树**从下一轮菜单消失** ✓、报告里渲染菜单 ✓。

**首测暴露并已修的一处缺陷（S2 精化）**：菜单原先用裸 `TreeScanner`（**所有**树），
而 Job 用 `LumberCandidateSource`（**可行**树）⇒ 把 Job 注定拒绝的 2×2 高大云杉
（`tree@22,64,218`，`trunk_too_tall`）递给了 LLM，执行后 `no_reachable_candidate`。
**修复**：菜单的伐木候选改为**复用 Job 自己的候选源**（`LumberCandidateSource.candidates` 的 `viable`），
候选 id 直接取 `Candidate.id()`（与 Job 决策日志同一口径）；被拒候选记一行
`[Goal] candidate_menu rejected(不可做)=…`（诚实：菜单只给能做的，不能做的如实登记）。
这正是项目既有规矩"夹具/菜单的候选必须复用规划器 provider（可规划即可执行）"。

## D-140 S3 请示层（服务端契约与闸门，2026-09-12）

**落地**（客户端弹窗 UI 归 **S3b**，本步先做契约 + 闸门 + 聊天等价入口）
1. `decision/PermissionGate`（新）：
   - 能力分级 `AUTO / NOTIFY / ASK / IGNORE`（默认表在代码里，玩家可改、可 `always` 持久化）；
   - `request(bot, capability, reason, options, default, timeoutTicks)` —— 命中 `AUTO`/`NOTIFY` 立即放行
     （`NOTIFY` 记一行提醒）、`IGNORE` 立即拒绝、有 `session`/`always` 授权直接放行；
     否则**登记请示并返回 `null`**（调用方继续 tick，**绝不阻塞**）；
   - **己答复/己超时的结论按 bot+能力暂存，调用方下一次 `request()` 取走一次** ——
     这是"每 tick 轮询"语义的关键（否则会无限重复问）；
   - **超时按默认档落档**（用户裁定：`ASK` 默认=拒绝），并留审计；
   - `PermissionsData`（SavedData）持久化 `always` 级策略。
2. `/alice ask`（列出未决请示）/ `/alice ask <id> <option> [once|session|always]`（**只有玩家/控制台能批**）/
   `/alice policy`（查看）/ `/alice policy <capability> <AUTO|NOTIFY|ASK|IGNORE>`（修改并持久化）。
3. 快照与报告新增 `pendingRequests`：玩家报告显示"⚠ 未决请示（等玩家拍板；超时按默认档）"，
   决策层也能看到"有人在等玩家拍板"，不会误判为"卡住"。
4. `alice:permission_demo`（零参数）+ `PermissionDemoTask`：发起 `demo_ask`（默认 deny、30 s 超时）⇒
   `allow` = 执行演示能力；`deny`/不答 = **自动返回**并如实记终态理由。
5. 顺带把 `terminalReason()` 提到 `Task` 层（默认空串）——原先只有 `Job` 有，普通任务（请示演示、诊断类）
   的终态理由在记录里永远是空串；`BotManager` 改为从 `Task` 取值。

**判据**：`[Perm] request id=p1 capability=demo_ask … default=deny deadlineIn=600tick` →
`/alice ask` 列出 → 答复 `allow` ⇒ `[Perm] answer … option=allow` + 任务 `terminalReason=allowed`；
不答 ⇒ 30 s 后 `[Perm] timeout … ⇒ 按默认档 deny` + `terminalReason=denied:timeout`（**自动返回**）。
**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。

### D-140 附注（2026-09-12 17:46–17:47 客户端实测）：S3 两条路径均通过；S2 精化亦通过

```
① 超时路径（不答，用户裁定"超时=拒绝"）
17:46:11 [Perm] request id=p1 capability=demo_ask options=[allow,deny] default=deny deadlineIn=600tick
17:46:41 [Perm] timeout id=p1 ⇒ 按默认档 deny（超时=拒绝，自动返回）      ← 精确 30s（600 tick）✓
         [Perm] demo decision DENY option=deny by=timeout scope=ONCE → terminalReason=denied:timeout
         task_terminal_reason kind=PermissionDemoTask terminalReason=denied:timeout ✓
② 批准路径（once）
17:46:51 [Perm] request id=p2 …
17:47:09 [Perm] answer id=p2 option=allow scope=ONCE by=player:dddgn      ← 只有玩家能批准 ✓
         [Perm] demo decision ALLOW option=allow by=player:dddgn scope=ONCE → terminalReason=allowed
         task_terminal_reason kind=PermissionDemoTask terminalReason=allowed ✓
聊天：[alice] 已答复 p2 = allow（ONCE，by=player:dddgn）

S2 精化：[Goal] candidate_menu rejected(不可做)= [tree@22,64,218:trunk_too_tall]
        [Goal] candidate_menu entries=6 → 5（可行树；砍掉的树从菜单消失）
```

**验证等级**：`WINDOWS_CLIENT`（S3 服务端契约版 + S2 精化）。
**仍未验证**：`alice:bot_report` 的"⚠ 未决请示"段落（本轮未在 30 s 窗口内点报告）。
**下一步**：S3b 客户端弹窗（屏幕一侧卡片 + 服务端→客户端通知包 + 倒计时 + 点击答复，与 `/alice ask` 等价），
随后 S3.5 收集归属 + 被动闸门。

## D-141 S3b 请示卡片（**只做这一格界面**，2026-09-12）

**用户裁定（原文）**："我觉得可以做这个弹窗，但是只需要做这一格界面，方便测试，没必要现在就做对话和管理主界面。"
⇒ 本轮只做**屏幕一侧的请示卡片**；聊天/命令入口（`/alice ask`）继续作为等价入口，不做任何对话或管理主界面。

**落地**
- `network/PermissionNoticePacket`（S2C）：`{active, id, capability, reason, option1, option2, default, deadlineInTicks}`
  —— `active=false` 表示**撤下卡片**（已答复或已超时）；
- `network/PermissionAnswerPacket`（C2S）：`{id, option, scope}` ⇒ 服务端走**同一个**
  `PermissionGate.answer(...)`（与 `/alice ask` 完全等价，"**只有玩家能批准**"不变）；
- `client/ClientPermissionState`：**只保留最新一条**（一格界面；排队/多卡片留给以后），客户端 tick 倒计时；
- `client/render/PermissionHudRenderer`：右侧面板（标题 + 能力 + 理由 + 倒计时 + 两个按钮 + 默认档提示），
  悬停高亮；点击钩子用 **`InputEvent.MouseButton.Pre`** ——
  已用字节码确认：`MouseHandler` 在**无界面**时也触发 `ForgeHooksClient.onMouseButtonPre`
  （`ScreenEvent.MouseButtonPressed` 只在有 Screen 时发，不适用）；点中按钮会 `setCanceled(true)` 吃掉这次点击；
- `PermissionGate`：发起请示时 `PacketDistributor.ALL` 推送通知，答复/超时时推送"撤下"通知；
  推送失败**静默**（无客户端/未安装时聊天入口照旧）；服务端逻辑不依赖客户端。

**判据**：右键 `alice:permission_demo` ⇒ 屏幕右侧出现卡片（标题 `Alice 请示 pN`、倒计时）⇒
点「allow（批准）」⇒ `[Perm] answer … by=player:…` + 任务 `terminalReason=allowed`（与命令等价）；
不点 ⇒ 30 s 后卡片消失 + `[Perm] timeout …⇒默认 deny`。
**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。

## D-142 S3c 请示答复改走"聊天可点按钮 + 快捷键"（用户 2026-09-12 实测反馈）

**用户反馈（原文）**："弹窗再在界面没办法用鼠标啊，要不改成主界面快捷键回答加玩家聊天栏点击"

**我的设计失误**：游戏内鼠标是**锁定**的（准星模式）——HUD 卡片只能"看"，**按钮点不到**。
`InputEvent.MouseButton.Pre` 能收到点击，但**没有可移动的指针**去悬停到按钮上。
（`ScreenEvent.MouseButtonPressed` 更不适用：只在有 `Screen` 时发。）

**改法（两条路，都走同一个 `PermissionGate.answer`，`/alice ask` 仍是等价入口）**
1. **聊天栏可点击按钮（主路径）**：`PermissionGate` 在发起请示时向所有玩家发一行
   `[Alice 请示 pN] capability：` + **[ 允许 ]** + **[ 拒绝 ]** + `（30s 后按默认「deny」…）`，
   按钮带 `ClickEvent.runCommand("/alice ask <id> allow|deny once")` + 悬停说明 ——
   **完全是原版机制，不依赖任何客户端渲染**，也不受"鼠标锁定"影响。
2. **快捷键（S3c）**：`client/PermissionKeys` 注册两个键位
   （`key.alice.permission_allow` = **Y**、`key.alice.permission_deny` = **N**，
   `KeyConflictContext.IN_GAME`、可在"选项→控制"改），按一下对**最新一条**请示发答复（scope=ONCE）；
   开着界面（聊天/背包）时不响应，避免打字误触。
3. **HUD 卡片降级为只读展示**：保留标题/能力/理由/倒计时，底部改成提示行
   `[Y] 允许 [N] 拒绝（或用聊天里的按钮）`；**移除全部鼠标交互代码**（并留注释说明为什么不能点）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。判据：
请示出现时聊天里有一条带 `[ 允许 ]`/`[ 拒绝 ]` 的消息（可点）；
游戏内按 `Y`/`N` 直接答复 ⇒ `[Perm] answer … by=player:…` + 任务 `terminalReason=allowed/denied:…`；
不答 ⇒ 30 s 超时 + 卡片与提示行消失。

## D-143 S3.5 第一步：掉落物归属（`DropProvenance`）+ **被动拾取闸门**（2026-09-12）

**依据**：D-138 五条裁定（`FOREIGN`=ASK、间接窗口 60tick/4 格、选区物品授权、`always` 允许但需标记、被动默认 `auto`）。

**落地**
1. `decision/DropPolicy`（新）：
   - `Provenance = OURS_DIRECT | OURS_INDIRECT | GRANTED_AREA | FOREIGN`；
   - 能力名复用 `PermissionGate` 的策略表 ⇒ **`/alice policy drop.foreign ASK` 立刻可用**；
     默认表新增：`drop.ours_direct/ours_indirect/granted_area = AUTO`、`drop.foreign = ASK`；
   - `mayCollect`（主动）/`mayPickUpPassively`（被动）；**被动路径上 `ASK` 直接拦下不弹请示**
     （否则每路过一堆就问一次＝骚扰；想捡 `FOREIGN` 只能显式派活或先授权）；
   - 窗口常量 `INDIRECT_WINDOW_TICKS=60` / `INDIRECT_WINDOW_RADIUS=4.0`（D-138 裁定值，`describeWindow()` 进日志便于标定）。
2. `ScopeBuffer`：
   - 新增 `itemProvenance`（掉落物 → 归属）+ `provenanceOf(item)`；
   - 生成确认时：**直接配对** → `OURS_DIRECT`；否则走**松窗**（我方动作点 60 tick/4 格内）→ `OURS_INDIRECT`
     —— 覆盖用户点名的"砍树后树叶衰减掉的树苗/木棍、移除支撑后甘蔗/仙人掌弹出"；
   - 日志升级为 `作用域捕捉掉落物: … provenance=OURS_DIRECT|OURS_INDIRECT|FOREIGN(未登记) source=…`；
   - `adoptExistingDrops` 打 `OURS_DIRECT`；新增 `registerAsOurs(...)`（夹具用）。
3. `decision/PickupGate`（新）：`EntityItemPickupEvent` 处理器 —— **只对 `BotPlayer` 介入**（真人玩家一概不动），
   查归属 ⇒ 策略不允许就 `setCanceled(true)` 并记 `[Pickup] blocked bot=… item=… provenance=… policy=…`；
   **只取消这一次转移**，不改物品实体（不用 `setNeverPickUp()`，那会连玩家一起禁掉）。
4. 验证入口：`alice:pickup_gate_check`（零参数）+ `PickupGateCheckTask`：① 我方掉落物（收养）⇒ 走过去**应捡到**；
   ② 外来掉落物（不登记）⇒ 走过去**应被拦下、东西留在地上**。两条必须一起过（只测 A 证明不了闸门存在，
   只测 B 证明不了没把正常拾取弄坏）。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。判据：
`[Pickup] blocked bot=tango item=minecraft:cobblestone x4 provenance=FOREIGN policy=ASK` +
`[PickupGateCheck] SUMMARY a_picked>0 b_remaining>0 → PASS（indirect_window=60tick/4.0格）`。

**未做（S3.5 第二步，登记）**：`CollectGrant`（选区物品授权 + `once/session/always`）、
`GRANTED_AREA` 归属判定、`CollectJob` 按策略过滤候选、`CollectDropsTask` 区分 `policy_blocked` 与超时、
"我方放置/拆除点"并入松窗（目前松窗以**破坏点**为锚）。

### D-143 附注（2026-09-12 18:25 客户端实测）：被动拾取闸门通过；同时抓出两个我该修的缺陷

```
[PickupGateCheck] A 造物：我方掉落物 1 堆（adopted=4）
         A 断言：走到我方掉落物上 ⇒ 背包圆石=44                     ← 该捡的捡到 ✓
         B 造物：外来掉落物 1 堆（未登记 ⇒ FOREIGN），命中数=2
         [Pickup] blocked bot=tango item=cobblestone x4 provenance=FOREIGN policy=ASK
         B 断言：走到外来掉落物上 ⇒ 地上还剩 1 堆                    ← 没被顺走 ✓
         SUMMARY a_picked=44 b_remaining=1 reason=passed → PASS（indirect_window=60tick/4.0格）
         task_terminal_reason kind=PickupGateCheckTask terminalReason=passed（30 tick）
         作用域捕捉掉落物: oak_log … provenance=OURS_DIRECT source=…   ← 归属标记生效 ✓
用户确认："测试完了，符合预期"
```

**瑕疵 1（噪声=隐患，已修）**：`[Pickup] blocked` 一次测试刷了 **584 行** —— bot 站在物品上时
`EntityItemPickupEvent` **每 tick** 都触发。修：同一件物品 **100 tick 内只报一次**，
并带 `次数=`（这一段累计）与 `累计=`（本次会话总数），`PickupGate.blockedTotal()` 可被汇报/自检读取；
表超过 256 条时清理过期项。**报警只报可行动的病症**这条规矩在事件的"每 tick 语义"下必须显式节流。

**瑕疵 2（夹具不干净，已修）**：`adopted=4` / 背包 44 是**上一轮残留掉落物**被一起收养造成的
（`adoptExistingDrops` 收半径内**所有**东西）。修：夹具 setup 先 `kill @e[type=item,…]` 清场，
断言改用**背包增量**（基准 `cobbleBefore`），并在日志里写明"清场后 adopted 应=1"。

**验证等级**：`WINDOWS_CLIENT`（闸门两用例 + 归属标记；两项瑕疵修复待复测）。

## D-144 S3.5 第二步：收集授权（`CollectGrant` + `GRANTED_AREA`）+ `anyDrops` 退役 + 收集按策略过滤（2026-09-12）

**依据**：D-138 裁定（选区物品授权为主 + 命令兜底；`always` 允许但要显式标记；`FOREIGN` 默认 ASK）。

**落地**
1. `decision/CollectGrants`（新）：`Grant{水平范围, untilTick(-1=永久), scope=ONCE|SESSION|ALWAYS, grantedBy}`；
   - `SESSION`/`ONCE` 内存、`ALWAYS` 走 SavedData（`alice_collect_grants`）持久化；
   - `covering(server, pos)`（授权查询，过期自动销账）、`active(...)`（报告用）、`consumeOnce(...)`（用掉一次即失效）、`clear(...)`；
   - 水平范围 + **竖直自适应**（沿用 D-130 的区域语义），日志 `[Grant] add …`。
2. `DropPolicy.effectiveProvenance(bot, item)`（**唯一判定入口**）：① 我方登记在册（`ScopeBuffer` 直接/间接）
   ② 落在授权区 ⇒ `GRANTED_AREA` ③ 其余 `FOREIGN`。**主动与被动两条路共用它**，不允许各写一套。
3. `PickupGate` 改用 `effectiveProvenance` ⇒ 授权区内的东西**被动路径也放行**（默认 AUTO）。
4. **`anyDrops` 布尔退役**（D-138 明确）：`CollectJob` 改为「**扫世界 → 按策略过滤**」——
   可捡 = `mayCollect(effectiveProvenance)`；被拦下的数量进 `blockedCandidates` 并打
   `[Job] collect scan 可捡=… 被拦下=…`（"地上有东西但没资格捡"变成可观测事实）；
   `JobRequest.collect(...)` 去掉该参数，`JobLauncher`/`GoalAction`/夹具同步。
5. **选区物品** `alice:collect_grant`（零参数）：右键记 pos1、**潜行右键**记 pos2 ⇒ 生成 `SESSION` 授权；
   命令兜底：`/alice grant`（列出，`★always` 显式标记）/ `/alice grant always`（把最后一条提升为永久）/
   `/alice grant clear`。**只有玩家能签发**（决策层只能请求）。
6. 报告新增"收集授权"段：`★always` 与"（永久授权：bot 会一直捡这片区域里的东西，含玩家物品）"字样
   —— 落实用户裁定第 4 条"必须在报告/日志里显式标记"。

**验证入口**：`alice:pickup_gate_check`（零参数）升级为**三用例**：
A 我方掉落物（收养）应被捡；B 外来掉落物应被拦下且留在地上；
**C 授权区内的外来掉落物应被捡**（`GRANTED_AREA ⇒ AUTO`）⇒ `SUMMARY a_picked>0 b_remaining>0 c_picked>0 c_remaining=0 → PASS`。
**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。

**仍未做（登记）**：`CollectDropsTask` 的 `policy_blocked` 终态区分（当前靠"候选在扫阶就被过滤掉"避免空转，
若收集途中归属变化仍可能报超时）；"我方放置/拆除点"并入松窗（当前松窗以**破坏点**为锚）。

### D-144 附注（2026-09-12 18:37–18:38 客户端实测）：三用例全过；节流生效；修三处小瑕疵

```
18:37:05 [PickupGateCheck] C 造物：授权区 25,64,210 内的外来掉落物 1 堆
         （授权 g1 x23..27 z208..212 至 tick 12305 scope=SESSION by=fixture:pickup_gate_check）
18:37:06 A 断言：走到我方掉落物上 ⇒ 圆石增量=4（基准 44）        ← AUTO 放行 ✓
18:37:07 B 断言：走到外来掉落物上 ⇒ 地上还剩 1 堆                ← ASK 被动拦截 ✓
18:37:08 C 断言：走到授权区里的外来掉落物上 ⇒ 增量=4 地上剩=0     ← **GRANTED_AREA 放行** ✓
         SUMMARY a_picked=4 b_remaining=1 c_picked=4 c_remaining=0 reason=passed → PASS（56 tick）
         task_terminal_reason kind=PickupGateCheckTask terminalReason=passed ✓
[Pickup] blocked：**5 行**（上次 584 行），带 次数=18/51/95 累计=19/70/165  ← 节流生效 ✓
/alice grant → g1 … scope=SESSION by=fixture:pickup_gate_check ；★always g2 … 永久 scope=ALWAYS by=player:dddgn ✓
/alice grant always → 已提升为**永久**收集授权 ✓ ；/alice grant clear → 已清空 3 条 ✓
报告里出现"收集授权（范围内的掉落物按 GRANTED_AREA 处理）：" ✓
```

**同轮修掉三处小瑕疵**
1. `ALWAYS` 授权同时进"会话表 + 持久化表" ⇒ `/alice grant` **同一条列两次**（实测 g2 出现 2 行）。
   修：`ALWAYS` **只进持久化表**；
2. `/alice grant always` 回执打印的是**旧**（会话级、带到期 tick）描述 ⇒ 看起来像"提升没生效"。
   修：回执用**新**授权的描述；
3. 被动拾取日志冷却 `100 → 600 tick`（站在被拦物品上时 5 s 一条 ⇒ 30 s 一条，噪声再降一档；
   计数仍完整保留在 `次数=`/`累计=` 与 `PickupGate.blockedTotal()`）。

**验证等级**：`WINDOWS_CLIENT`（三用例 + 节流 + 授权命令 + 报告段落；三处瑕疵修复待复测）。

## D-145 S5 / P1：只读配方图逆推规划器（**离线优先**，2026-09-12）

**依据**：`KNOWLEDGE_RECIPE_GRAPH_NOTES.md` §1/§5（**配方是数据不是知识**；运行时 > wiki；
先做"只读、零风险、可离线断言"的 P1，它是所有支线任务的地图）。

**落地**：`tools/recipe-graph.py`（离线，Python）
- 数据源两种、**格式统一**：① 游戏内导出 `config/alice-recipes.json`（权威：KubeJS/CraftTweaker/GT 的魔改
  最终都体现在运行时配方表）② jar 里的 `data/*/recipes` + `data/*/tags/items`（用于离线开发与自检）；
- 归一化：`crafting_shaped`（按 pattern 计数）/`crafting_shapeless`/`smelting`/`blasting`/`smoking`/
  `campfire_cooking`/`stonecutting`/`smithing_*` ⇒ `{输出, 数量, 工作站, 输入[]}`；**未知类型如实跳过并计数**
  （模组机器/多方块不猜语义）；
- **BOM 聚合展开**（关键正确性）：需求累加 + 每次合成 `ceil(need/产出)` + **余料回填**给后续兄弟需求
  + 同一输入的二次需求**必须重新入队**；
- **标签 → 成员**：`#planks`/`#forge:ingots/*` 这类先看是否**有成员没有配方**（= 原始材料，直接列为
  "任取其一"），否则取成员里缺料最少的配方 —— 避免"原木→木块→木板"这种荒谬绕路；
- **环有界**（铜锭↔铜粉）：深度 + 已访问集合；自检里用合成数据专门验；
- `--selftest` 用**原版数据**自证：木镐 ⇒ 3 木板 + 2 木棍 ⇒ **2 原木**（每根出 4 木板，5 块 ⇒ 2 次合成）+
  库存足够应短路 + 环应有界。

**自证抓到并修掉的三个真错误**（这就是"能离线断言"的价值）
1. **递归树各自 ceil** ⇒ 两个 `#planks` 子节点各展开一次 ⇒ **5 木板被算成 8 原木**；
   改为 BOM 全局账 + 余料回填后 = **2 原木** ✓；
2. **标签成员挑到可合成项**：`#acacia_logs` 挑到 `acacia_wood`（要 4 原木）⇒ 改为**优先原始材料**
   （`acacia_log` 直接作为"需采集"叶子）✓；
3. **二次需求不入队**：木棍还要 2 木板时，因"已在 demand 里"而没重新入队 ⇒ 少算一根原木 ✓ 修。

**用法**
```
python3 tools/recipe-graph.py --selftest                       # 原版数据自证（PASS）
python3 tools/recipe-graph.py --recipes <导出文件|数据目录> --target minecraft:wooden_pickaxe \
        [--count N] [--have minecraft:oak_log=2] [--depth 8] [--branch 4] [--json out.json]
```

**验证等级**：本机自证 `PASS`（`--selftest`，含三项边界）。
**下一步（S5 第二步）**：游戏内 `/alice recipes dump`（把运行时配方 + 已知物品标签导出成同一格式），
之后就能对**真实整合包**跑 P1（含 KubeJS/GT 魔改）。再往后才有 S6（`CraftTask`/`SmeltTask` → `ProcessTask`）。

## D-146 S5 第二步：运行时配方导出 `/alice recipes [file]`（2026-09-12）

**落地**：`decision/RecipeDump` + `/alice recipes [file]`（默认写 `config/alice-recipes.json`）
- 导 **运行时** `RecipeManager` 的配方（含整合包魔改）+ 物品标签 → 归一化 JSON，**直接喂** `tools/recipe-graph.py`；
- 归一化：`{id, type, station, output, count, inputs[{key,count,any?}]}`；工作站映射见 `STATION_BY_TYPE`；
- **能力边界（诚实标注，用户 2026-09-12 明确"不要急于深入模组适配"）**：
  - 只含**原版配方体系**；**机器配方（JEI 类别，如格雷/精英工作台）不在此** ⇒ 那部分留到 S6 机器适配器，
    按项目规矩**先只读、不猜语义**；
  - 无法归一化的类型**如实跳过并计数**（日志 `skipped=`）；
  - 标签：成员上限 256（超出标 `truncated`）；配方的 Ingredient 与某标签成员集合**完全相等**才记为 `#tag`，
    否则记"任取其一"的物品列表（诚实表达"我们只知道这个集合"）。
- 日志/回执：`[Recipes] dump recipes=… skipped=… tags=… → <path>`，并提示离线命令。

**验证等级**：IMPLEMENTED / COMPILES（客户端待测）。

## D-147 路线重规划（用户 2026-09-12 要求：先把**基层**补齐，模组适配只做浅测）

**背景**：② 决策层三条通道（S0–S3.5）与 S5/P1 规划器已落地，但用户指出"还有很多基层东西没实现"。
盘点后按"地基 vs 能力"重新排序（完整清单见 `OPEN_ITEMS_LEDGER.md` §1–§6）：

**基层缺口（不做就会到处补丁）**
| # | 缺口 | 现状 |
|---|---|---|
| 基-1 | **可回收性不变式（项目差异①）仍是空实现** | `RecoverabilityLevel`/`IntrinsicReversibility` **0 处读取**、唯一校验恒假（P0-B）；它是所有"放置/攀爬/桥"类能力的安全前提 |
| 基-2 | **决策层没进回归电池** | S0–S3.5 的判据散在各处，`alice:regression_battery` 里没有它们 ⇒ 改动容易悄悄回归 |
| 基-3 | **事件层缺失（S4）** | 没有阈值/上报 ⇒ 汇报与决策只能靠"任务终态"驱动；"工具耐久见底/卡住/无活"无人上报 |
| 基-4 | **决策 trace 只进日志 + 跨重启语义未定义** | 没有结构化决策历史；`grants/policies` 已持久化，`pendingRequests`/trace/任务树续做未定义 |
| 基-5 | **LLM 上抛契约不完整** | `Job.failureReport()` 未覆写（J-4）、决策拒绝理由无结构化回读（J-7）、`MineJob` 硬编码矿物清单（J-6） |
| 基-6 | **多 bot 并行（差异③）** | 只有预留；调度/广播/账本/区域/请示都是单 bot 假设 |
| 基-7 | 内核残余 | best-so-far 前缀（K-1）、legacy 双内核活引用（K-2）、`safeToCancel`/谓词不统一（K-3/4）、死状态（K-5） |
| 基-8 | 写入授权登记缺口 | G3 连锁凭证 / G5 容器写入 / G8 `MovementCapabilities` 无读取点 / `REGION_REPLANT` 未登记 |
| 基-9 | 工具与耐久管理缺位 | 生产路径只靠夹具发料；"工具从哪来、坏了怎么办"是 S6 的前置 |

**新路线（三阶段）**
1. **阶段 1 · 基层收口**：基-2（决策层进电池）→ 基-3（S4 事件层）→ 基-1（可回收性接线，按 P0-B 的顺序：
   先填真实等级再打开校验）→ 基-4（trace 落盘 + 跨重启语义）；
2. **阶段 2 · 真实数据浅测**：挑 **两个模组**（用户裁定：不深入适配）只验"运行时导出 + P1 读得懂多少"，
   产出"需要适配器"的清单；**不写适配器**；
3. **阶段 3 · 能力扩展**：S6（`CraftTask`/`SmeltTask` → 机器适配器按阶段 2 清单挑 1–2 个 → 偏好规则）；
   多 bot 并行另立项（基-6）。

## D-148 阶段 2 浅测的模组集合与"矿物词典=标签"的结论（2026-09-12 用户裁定）

**用户裁定**：同意 **Create + Extended Crafting** 两个模组；并**额外加上 Mekanism 与 Thermal Expansion**
——理由（用户原话）："mek 的配方太寻常了，不过为了测试对配方树的理解，可以加上 mek 和热力膨胀这两个
经常配方打架的模组"。用户另问："他们原生有矿物词典统一吗"。

**结论（回答该问题，并作为阶段 2 的判据）**
1. **老的 `OreDictionary` 在 1.14+ 已被物品/流体标签取代**：`#forge:ingots/<material>`、`#forge:dusts/…`、
   `#forge:ores/…`、`#forge:gears|plates|nuggets|storage_blocks|raw_materials/…`（部分模组另补 `#c:` 约定）。
2. **模组间统一的边界**：✅ **成品材料**（锭/粉/矿/块…）跨模组互通；❌ **中间物与机器配方**不统一
   （Mekanism 的 clump/dirty dust、Thermal 的 rich slag/cinnabar、Create 的 crushed ore 各自封闭）；
   ⚠️ **"打架"发生在路线层** —— 同一个 `#forge:ingots/steel` 有多条跨模组产出链，代价/机器/副产物不同。
3. ⇒ 对 P1 的意义：**标签层我们已经做对了**（P1 的"标签→成员 + 优先原始材料"正是这一层的正解）；
   真正要补的是**多路线与偏好**（阶段 3 的 `RoutePreference`），其输入是阶段 2 产出的**冲突报告**。

**阶段 2 浅测的产出物（明确到可验收）**
- `alice recipes dump` 的真实数据 → 离线 P1 跑通；统计 `recipes/skipped/tags`，并**逐条列出无法归一的类型**
  （预期：Mekanism/Thermal/Create 的机器配方大量落在 `skipped` —— 这正是"需要适配器"的清单）；
- **冲突报告**：对若干目标物（钢锭/青铜/铜粉/齿轮…）列出 **≥2 条独立产出链**（跨模组）、各自工作站/中间物/缺料，
  以及该物品所属标签 ⇒ 既能量化"运行时导出漏了什么"，也直接给阶段 3 的偏好规则做输入；
- **不做**（用户明确"不要急于深入模组适配"）：不写任何机器适配器、不接 JEI 类别、不做偏好规则的执行。

**技术待核实（阶段 2 一并核对）**：导出目前用"**Ingredient 物品集合 == 某标签成员集合**"反推 `#tag`
（Forge 的 `Ingredient` 不直接暴露 `TagKey`）；多模组挂标签时该启发式更可靠，但要用真实数据核对，
必要时改为从模组 jar 的 `data/**/recipes/*.json` 交叉验证标签。

**下一步**：**阶段 1 基层收口开工** —— 先 **基-2（决策层进回归电池）** 与 **基-3（S4 事件层）**。

## D-149 基-2：决策层五件套进回归电池（2026-09-12）

**做了什么**：`alice:regression_battery` 新增 6 步，插在 `pathing` 之前（决策层失败不该淹没在 5000 tick 的寻路里）：

| 步骤 | 验的事 | 入口任务 |
| --- | --- | --- |
| `decision_contract` | 快照/菜单/`GoalAction` 严格解析 + `target` 必须命中菜单 + 越界钳制 | `DecisionContractCheckTask` |
| `permission_gate` | AUTO/NOTIFY/ASK/IGNORE 四档 + 超时=默认拒绝 + 答复队列 | `PermissionContractCheckTask` |
| `pickup_gate` | 我方掉落物放行 / 外来掉落物拦截（被动拾取闸门） | `PickupGateCheckTask` |
| `collect_job` | `JobRequest.collect(...)` → `JobLauncher.create` → `CollectJob` 扫描+收集闭环 | `JobLauncher.create` |
| `recipes_dump` | 运行时配方导出能跑通并写出文件 | `RecipesDumpCheckTask` |
| `event_thresholds` | 工具见底 / 卡住两类事件（见 D-150） | `EventThresholdCheckTask` |

**为什么**：D-135 附注三起，决策层已有 6 个独立测试物品；但"改了生产任务必须复跑"的清单（`docs/TESTING_GUIDE.md §1.7`）
一直没有把它们收进来 ⇒ 决策层改动**不在**任何回归网里。用户一次右键跑电池 = 顺带回归整层。

**纪律**：每步仍然自开账本作用域、自复位夹具、互不干扰；任一步失败**不中断**（一趟看全）。
电池从 9 项扩到 **16 项**（决策层 6 步 + 原有的 10 步），总兜底仍 20000 tick。

## D-150 S4 事件层：阈值只报"可行动病症"，且同一病症只报一次（2026-09-12）

**问题（这是缺口，不是优化）**：D-135 起决策层**唯一的触发是"任务终态"**。于是三类典型的可行动病症没人上报：
① 工具耐久见底（bot 会安静地把唯一一把镐磨没）；② 有任务却长时间不动（卡住/被判定死角）；
③ 无活可干（已有 `idle_no_work`）。结果就是：**决策层只在事情结束之后才知道事情发生了**。

**实现**：`decision/EventThresholds`（每 tick 由 `BotManager` 调度循环调，读数**只读**）：

| 事件 | 触发 | 复位（滞回） |
| --- | --- | --- |
| `TOOL_LOW` | 背包里**剩余比例最低**的斧/镐 ≤ 20% | 该比例回到 ≥ 35%（换新/修好） |
| `STUCK` | **有任务 + 有移动意图**（`controller.hasActiveMovement()`）且脚位连续 200 tick 未变 | 脚位一变、或换了任务 ⇒ 可再报 |

**为什么 `STUCK` 的判据里必须有"移动意图"**（写下来因为这是个容易做错的判断）：只写"有任务且不动"会把
**等待态**（等 LLM 回复、等请示答复、等冷却）误报成病症 —— 那些时刻决策层什么都做不了，报了纯噪声。
真卡住的定义是"**正在走**却一格没挪"；等待态**不计时**（时钟拨到当前 tick）。同一（脚位, 任务）episode
只报一次，避免"意图闪烁"反复刷屏（阈值跨越 = 事件，不是每 tick 事件）。

事件写入 `BotEventLog`（汇报可见）+ `BotLog.warn` 一行 + 通知 `GoalDirector.onEvent(...)`（有节流）。

**已知局限（诚实登记）**：`hasActiveMovement()` 只是"移动意图"的**代理**。纯跳跃相位（如 `DESCEND` 两次
`jumpOnce()` 之间）输入可能为 0 ⇒ 那些相位**不计时**。方向是**宁可漏报、不可误报**（等待态误报会污染
决策层上下文），若将来发现真实卡死场景漏报，再改成"路径执行器显式报告 `in_progress` 且 `hasActiveMovement()`
或段超时计时器在跑"。

**为什么"只报一次"是硬纪律**：2026-09-12 实测 `[Pickup] blocked` 刷了 584 行 —— 每 tick 都满足的判据
如果每 tick 都上报，日志和 LLM 上下文都会被同一件事灌满。⇒ **阈值跨越 = 事件；滞回复位后才可能再报**。
同理，"无任务"不算 `STUCK`（那是 idle，不是病症）；树叶清障、单次重试这类噪声不上报。

**自检**（`alice:event_thresholds` / 电池 `event_thresholds` 步）四例：
A 全部斧镐压到 15% ⇒ 60 tick 内**恰好一条** `TOOL_LOW`；B 修满后再压 ⇒ **再报一条**（证明不是一次性开关，滞回真的复位）；
C 站进 `pillar_course` 的 **1×1 基岩竖井**顶壁长按前进（**真有移动意图**）⇒ 200 tick 内**恰好一条** `STUCK`；
D 继续顶壁 100 tick ⇒ **不再增加**（不刷屏）。
夹具前提自带断言：找不到斧/镐、bot 不在地面、竖井未封闭、bot 被挤出竖井 ⇒ 直接 FAIL 报**夹具**问题，
不算"阈值没触发"（前者是 fixture，后者才是缺陷；两者混在一起就是 2026-09-12 那三次假失败的老病）。

### D-150 附注一：首次实测没跑到 S4（事实登记 + 单跑入口）

**事实（读客户端 `latest.log`，2026-09-12 19:08:10–19:09:35）**：
1. 电池跑到 `step=lumber_job (7/16)`，随后 19:09:22 与 19:09:35 两次 `Saving and pausing game...`
   ⇒ 单人游戏暂停，**运行在 7/16 处中断**；
2. 全日志 **0 条** `[EventThreshold]`、**0 条** `[Events]` ⇒ S4 那一步（15/16）**从未执行**，
   用户"没看到竖井顶壁"是**没跑到**，不是"跑了但没触发"；
3. 顺带得到一条**部分阴性证据**：前 7 步里 bot 有大量"有任务但静止等待"的帧（夹具复位、等待、挖掘间隙），
   **零** `[Events] STUCK` —— 与 D-150"等待态不计时"的设计一致（老判据在这里会刷屏）。
   但这**不足以**证明 STUCK 能报（阳性证据仍未取得）。

**同时修正的笔误**：电池实际是 **16 项**（`step(...)` 15 次 + `region_maintain` 用带 `doneWhen` 的
`new Step(...)` 1 次），此前文档写 15 ⇒ 已全量订正（`TESTING_GUIDE` / `AI_PROJECT_STATE` / `AliceItems` /
`RegressionBatteryTask` / `RegressionBatteryItem`）。

**新增单跑入口**（`alice:event_threshold_check`，零参数，约 25 秒）：S4 的现场只有**站近盯着看**才有意义
（bot 在 1×1 竖井里顶壁约 7 秒），而电池一轮 4~6 分钟且中途还有别的场景；给一个能单独重跑、能盯着看的
入口，既省时间也能取得"物理上真的没动"的目击证据。判据与电池第 15 步完全相同
（`EventThresholdCheckTask`，自带 `pillar_course` 场景与夹具前提断言）。

### D-150 附注二：单跑实测抓到两个**夹具/报告**缺陷（不是阈值问题）

**实测（2026-09-12 19:18–19:20，客户端 `latest.log` + 世界存档复核）**

1. **前两次：竖井被上一轮 pillar 测试的残留方块堵死** ⇒ bot 一被放进 `24,64,44` 就
   `hazard=SUFFOCATING` / `onGround=false`，**维生系统在第 1 tick 打断任务**
   （`durationTicks=1 terminal=SURVIVAL_INTERRUPTED code=failed:survival_suffocating`）⇒ 夹具连断言的机会都没有。
   用户随后手动清空，症状依旧 ⇒ 引出下面第 2 条。
   *（世界存档复核 `tools/capture-scene.py`：`24,63,44` 石头、`24,64..66,44` 空气、四周 `23/25,43/45` 基岩 —— 清理后竖井结构本身是对的。）*

2. **夹具缺陷：`onGround()` 在 teleport 的同一 tick 断言**。`onGround` 由 `move()` 更新，
   teleport 后当 tick 读到的仍是**上一状态的 stale 值** ⇒ 空气竖井里也报 `fixture_not_on_ground`。
   修法：新增 `Phase.SETTLE`（10 tick 静止等重力结算）后再断言，并把前提拆成五条各自可诊断：
   `fixture_shaft_blocked`（中心列可通行）/ `fixture_no_floor`（脚下实心）/ `fixture_not_on_ground` /
   `fixture_shaft_not_enclosed` / `fixture_no_tool`。

3. **报告缺陷（更危险）**：夹具前提失败时四个用例**一个都没跑**，SUMMARY 却打印
   `tool_low_once=PASS tool_rearm=PASS stuck_once=PASS stuck_no_spam=PASS` ——
   因为 `verdict()` 只查"用例名是否在 `failures` 里"，夹具失败映射不到用例上 ⇒ **把"没跑"报成"通过"**。
   修法：三态 `PASS / FAIL / NOT_RUN`（`completed` 集合只在真的断言时登记），并加 `verdict=` 总结。

4. **入口修法**：`alice:event_threshold_check` 不再把 bot 直接塞进竖井（否则堵死的竖井会让维生系统
   在夹具重建场景之前就打断任务）；改为**先跑场景函数、再把 bot 放到竖井顶沿**（`RIM_GOAL`，基岩环上方
   保证不窒息），进竖井由任务自己的 `setup` 负责。

**教训（与 D-135 附注同族）**：夹具前提的断言时机本身就是前提的一部分；"没跑"与"通过"必须在输出上可区分 ——
否则一次夹具故障会被读成"功能已验证"。

### D-150 附注三：`fixture_not_supported` 的根因 = 我的公式差一格 + `onGround` 粘滞语义

**实测（2026-09-12 19:38，探针 + 前提日志）**

```
[EventThreshold] premise feetY=64.0 floorTop=65.0 onGround=false dy=0.0 supported=false
[PhysicsProbe] stage=after_aiStep pos=(24.500,64.000,44.500) velocity=(0.000,-0.078,0.000) onGround=false travelCalls=1
```

1. **公式差一格（我的错）**：脚位格 `shaft=(24,64,44)`，脚下方块 `below=(24,63,44)` 的**底面**在 `below.getY()=63`，
   顶面 = `63 + 形状高度(1.0) = 64.0`。我写成 `shaft.getY() + 形状高度 = 65.0` ⇒ 判定 `feetY(64.0) < 64.95`
   ⇒ 必然 `fixture_not_supported`。**bot 一直好好站在 64.0 上。**
2. **`onGround=false` + "悬空不下落"的真因（附注三当时的解释是错的，此处订正）**：当时我归因于
   "`onGround` 粘滞语义"，**不准**。2026-09-12 20:40 实测（去掉每 tick 清速度后）：
   `premise feetY=64.0 floorTop=64.0 onGround=true dy=-0.078 box=[24.20,64.00..24.80,65.80] collisions=0`
   ⇒ bot **自然落到了 64.0 并 `onGround=true`**。真因是**夹具每 tick `setDeltaMovement(ZERO)` 与物理互相打架**：
   外部把速度归零 ⇒ 位移永远为 0 ⇒ 位置恒定、`onGround` 不被重算 —— **是夹具造出来的假状态，不是物理缺陷**。
   教训（同族于"夹具前提自带断言"）：**夹具不该每 tick 覆写物理量**；那一轮我还保留了 `box/collisions` 定点诊断，
   它先排除了"卡在方块里"（collisions=0），把怀疑逼到"外部干预物理"这条正解上。
3. **修法**：① 修正 `floorTop = below.getY() + 形状高度`；② teleport 时**抬高 0.25** 再落下，让落地那次位移带截断
   （`onGround` 随之变 true，状态也更自然）；③ `onGround` 降级为**信息**（`supported` 已证明站在地板上），
   若仍为 false 只记警告、不判死 —— 站立判定的其他读者另行单独审计，不把夹具卡死在这。

### D-150 附注四：S4 事件层**实测通过**，且第一次跑通了「事件 → LLM 决策 → 执行」闭环

**实测（2026-09-12 19:46–19:47，客户端 `latest.log`）**

| 用例 | 证据 | 结论 |
| --- | --- | --- |
| A `tool_low_once` | `case=tool_low_once result=PASS delta=1 ticks=1` + `[Events] TOOL_LOW … 钻石斧 剩余 234/1561（15%）` | ✅ 阈值跨越即报，**恰好一条** |
| B `tool_rearm` | `case=tool_rearm result=PASS delta=2 ticks=32`（修满 → 滞回复位 → 再压到 15% 再报一条） | ✅ 不是一次性开关 |
| C `stuck_once` | `case=stuck_once result=PASS delta=1 ticks=202 intent=true` + `[Events] STUCK … 有移动意图但 200 tick 没挪过格` | ✅ 有意图不动 200 tick 报，**恰好一条**；用户目视确认"bot 没动"（物理事实） |
| D `stuck_no_spam` | **未断言** —— 见下 | ⏳ |

**闭环（本项目第一次真实发生）**：`[Events] STUCK` → `GoalDirector.onEvent` → `[Goal] decision_request trigger=event:STUCK:…`
→ LLM 返回 `{"action":"stop_current","reason":"…卡在方块@24,64,44 已 200+ tick 无移动，且钻石斧耐久仅剩 15%…"}`
→ `[Goal] execute action=stop_current` → `task_execution_terminal terminal=CANCELLED_BY_USER code=cancelled:llm:…`。
**S4 的设计目的（把可行动病症送到决策层并让它动手）在真实客户端上成立了**，且 LLM 的理由与事实一致。

**因此新增 `GoalDirector.suspend(bot, ticks)`**：自检/回归期间**暂停自动触发**（空闲/终态/事件都不再发起决策），
以免生产决策层在用例中途把被检任务砍掉（D 段就是被 `stop_current` 在第 522 tick 砍掉的）。手动诊断入口
（`forceOnce`）不受影响。**注意这是"检具"而非"改语义"**：生产行为（事件招来决策并动手）保持不变。

**遗留疑点（已加定点诊断，不猜）**：探针显示 bot 停在 `y=64.250`（离地 0.25）**完全不下落**，
速度恒 `-0.078`、位置恒定、`onGround=false`、`hazard=NONE`、`travel()` 每 tick 跑了一次。
在"脚下是空气"的前提下这不合理 ⇒ 已把「包围盒 + 方块碰撞数 + 脚位/头上/头上二格的方块」打进夹具前提行，
下一轮据数判定（可能是我"粘滞标记"的解释不完整）。**不影响本轮 S4 判定**：C 段的判据是"有移动意图 + 脚位不变"，
两者都有独立证据（日志 intent=true + 用户目视）。

### D-150 附注五：C/D 用例通过；A/B 失败是**夹具不可重入**；A/B 两项裁定已实施

**2026-09-12 20:13 实测**
- ✅ `case=stuck_once result=PASS delta=1 ticks=202 intent=true`、`case=stuck_no_spam result=PASS`（继续顶壁 100 tick 后 STUCK 总数仍为 1）
  ⇒ **S4 的四个用例全部有实测证据**；`GoalDirector.suspend` 生效（`trigger_skipped reason=suspended`），本轮无 LLM 介入。
- ❌ `case=tool_low_once FAIL` / `case=tool_rearm FAIL` —— **夹具不可重入**：夹具把斧/镐压到 15% 后**从不复原**，
  于是**第二轮**开始时斧子仍处于"已上报"状态（`toolBase=1`）⇒ 再压到 15% 不会产生新事件。
  实锤：整份日志恰好 2 条 `[Events] TOOL_LOW`，第一条发生在夹具开始之前。
  **修法**：新增 `Phase.NORMALIZE` —— 每轮先**修满并等 30 tick 让滞回复位**（复位没发生则如实报 `fixture_rearm_failed`），
  再压到 15% 起算；`finish()` 收尾也把工具修满（否则 15% 的斧子会持续污染后续任务/下一轮）。
- `premise … box=[24.20,64.25,44.20..24.80,66.05,44.80] collisions=0 blocks: foot=空气 above=空气 above2=空气`
  ⇒ **包围盒正常、零方块碰撞、三格全空气**，bot 却停在 64.25 不下落 ⇒ 排除"卡在方块里"。
  结合探针（速度恒 -0.078、travel 每 tick 跑一次、位置恒定），**最可能是夹具每 tick `setDeltaMovement(ZERO)`
  与物理互相打架**造成的假象；本轮已**去掉每 tick 清速度**（C/D 只保留前进输入），下一轮看 bot 是否自然落到 64.0。

**用户裁定（2026-09-12）**：A、B 两项都做。
- **A 已实施**：自检暂停窗口内，事件**只记录不通知决策层**（`GoalDirector.isSuspended` + `EventThresholds.emit`
  打印 `[Events] X 已记录（自检暂停：不通知决策层）`）。理由：检具不该在生产侧留下决策痕迹（上一轮真的把 LLM 招来起了常驻 Job）。
- **B 已实施**：`BotManager.busyMessage(bot)` 统一文案，**替换全部 49 处**"bot 正忙，稍后再试"：
  常驻区域伐木巡查 ⇒ `bot 正忙：正在跑常驻区域伐木巡查…要它收工用 /alice region stop`；
  其他 ⇒ `bot 正忙：正在跑 <TaskKind>。要打断用 /alice stop（或等它自己结束）`。

### D-150 附注六：S4 事件层收尾（`WINDOWS_CLIENT`）

**2026-09-12 20:40 实测（客户端 `latest.log`，用户目视确认 bot 顶壁没动）**

```
premise feetY=64.0 floorTop=64.0 onGround=true dy=-0.078 supported=true box=[24.20,64.00,44.20..24.80,65.80,44.80] collisions=0
case=tool_low_once result=PASS delta=1 ticks=1
case=tool_rearm    result=PASS delta=2 ticks=32
case=stuck_once    result=PASS delta=1 ticks=202 intent=true
case=stuck_no_spam result=PASS 继续顶壁 100 tick 后 STUCK 总数=1（期望 1）
task_execution_terminal … terminal=COMPLETED code=done
[Events] TOOL_LOW 已记录（自检暂停：不通知决策层）    ← A 项闸门生效
[Goal] trigger_skipped reason=suspended trigger=terminal:EventThresholdCheckTask(passed)
```

**结论**：`基-3 S4 事件层` = `IMPLEMENTED` + `COMPILES` + `SERVER_TESTED` + `WINDOWS_CLIENT`（四例全 PASS、恰好 1 条/例、
滞回复位有效、不刷屏）+ `USER_ACCEPTED`（用户目视"顶壁没动"）。事件环、阈值、滞回、决策通知与**自检闸门**全部有实测证据；
D-150 附注四记录的「事件 → LLM → `stop_current`」闭环是生产路径的实测。

**收尾两处小修**：① 成功路径也打 `SUMMARY`（此前只在失败/前提不成立时打，成功时只有四条 `case=`）；
② `finish()` 收尾把工具修满（夹具不再污染后续任务）。

**新增可复用能力**（本轮顺带产出，后续基线要用）：
`GoalDirector.suspend(bot, ticks)` / `isSuspended(bot)`（自检期间暂停触发 + 事件只记录不通知）、
`BotManager.busyMessage(bot)`（统一"bot 正忙"文案，常驻任务直接给 `/alice region stop`）。

## D-151 基-1 第一步：可回收性**真的被算出来**（只测量，不改准入）2026-09-12

**问题（P0-B，复核 §3 已精确化）**：`PlannedMovementSpecs.toSpec()` 是规划→执行的**唯一转换点**，
它**同时**写死两边：`evaluatedRecoverability = LOCAL_STEP`，各类型的 `required = LOCAL_STEP`
⇒ `MovementSpec` 的校验 `evaluated < required` 就是 `0 < 0` **恒假**；而且 provider 在 `PlannedMovement`
上算好的 `recoverability` 字段**被整个丢掉**（全仓库 0 处读取）。于是"可回收性"这个**项目签名能力**
（D-036 差异①）在代码里从未被计算过。

**本轮做了什么（严格按"先测量、后接线"）**
1. `RecoverabilityAssessment`（等级 + **依据 basis**）：等级必须能被追问"凭什么"，否则又是一个说不出所以然的枚举。
2. `RecoverabilityEvaluator`（**唯一裁决点**）：按 Movement 类型的规则表给出等级 + 依据，依据都对应
   **规划期真实做过的验证**：
   | Movement | 等级 | 依据 |
   |---|---|---|
   | TRAVERSE / DIAGONAL | `PATH_REVERSIBLE` | 几何未变 ⇒ 反步同型可用 |
   | ASCEND / DESCEND | `PATH_REVERSIBLE` | 反程 = DESCEND/ASCEND 1 格，起点格刚站过/刚占用过 ⇒ 可用且安全 |
   | PLACE_STEP_AND_TRAVERSE | `PATH_REVERSIBLE` | 放置只**增加**支撑 ⇒ 反步仍成立 |
   | FALL | `PATH_REVERSIBLE` | provider **只在** `fallRecoverable`（PILLAR 返回守卫）通过时才产出 FALL 边 |
   | PILLAR | `LOCAL_STEP` | 上升合法，但**回程要拆自己放的方块**（授权/预算未在规划期验证）⇒ 不给回程保证 |
   | BREAK_* / DOWNWARD | `LOCAL_STEP` | 破坏地形且**不恢复** |
3. provider 全部 8 个产出点 + `AStarMovementSearch` 改为向评估器取值；`toSpec` 采用评估值并**核对**
   provider 字段（不一致 ⇒ `[Recover] provider/评估器不一致` 告警，防止将来漏改）。
4. `RecoverabilityReport`：在唯一转换点记账（类型=等级/依据），会话完成时打一行
   `[Recover] session=… movements=… distinctLevels=… {…}` ⇒ "可回收性是不是常量"变成**可核对的数据**。
5. **自检** `alice:regression_battery` 新增第 17 步 `recoverability`（纯计算，约 1 秒，四例）：
   A 规则表非常量；B 逐类型抽查（等级**和依据**都对）；C **负例**——`required=PATH_REVERSIBLE` +
   `evaluated=LOCAL_STEP` **必须抛异常**（证明校验是活的，P0-B 不复发）；D 转换点用的是评估值。

**本机纯逻辑实测（非客户端，2026-09-12）**
```
GUARD=LIVE: evaluated recoverability is below Movement requirement
TOSPEC_FALL evaluated=PATH_REVERSIBLE required=LOCAL_STEP
TOSPEC_PILLAR evaluated=LOCAL_STEP
REPORT=movements=2 distinctLevels=2 {FALL=PATH_REVERSIBLE/pillar_return_guard_passed=1, PILLAR=LOCAL_STEP/ascent_reverse_break_unverified=1}
```

**明确不做（下一步才做，顺序不能反）**：把 `required` 提上去（如 FALL → `PATH_REVERSIBLE`）。
本轮的 `required` **一律维持 `LOCAL_STEP`** ⇒ 准入结果与行为**零变化**（评估值都 ≥ LOCAL_STEP，校验恒真不抛）。
接线时必须先确保"所有仍存在的 FALL 边都过了守卫"，否则 `LOCAL_STEP < PATH_REVERSIBLE` 会**抛异常崩规划器**
（复核 §3 的警告）。

### D-151 附注一：真实计划实测 + 一个必须避开的"假接线"陷阱

**实测（2026-09-12 21:3x，客户端 `latest.log`）**
1. **自检四例全 PASS**（`nondeterministic_table` / `per_type`×5 / `guard_is_live` / `to_spec_uses_assessment`），
   `terminal=COMPLETED`；其中 `guard_is_live` 是**负例**：`required>evaluated` 真的抛了
   `evaluated recoverability is below Movement requirement` ⇒ P0-B 的"恒假校验"已变活。
2. **真实计划分布**（`alice:pathing_regression`，12 个会话，movements=2…58）——一个 58 段的会话：
   ```
   distinctLevels=2
   DESCEND=PATH_REVERSIBLE/geometry_unchanged_reverse_ascend=3
   TRAVERSE=PATH_REVERSIBLE/geometry_unchanged_reverse_step=34
   PLACE_STEP_AND_TRAVERSE=PATH_REVERSIBLE/placement_only_supports_reverse=6
   FALL=PATH_REVERSIBLE/pillar_return_guard_passed=5
   ASCEND=PATH_REVERSIBLE/geometry_unchanged_reverse_descend=3
   DIAGONAL=PATH_REVERSIBLE/geometry_unchanged_reverse_step=1
   PILLAR=LOCAL_STEP/ascent_reverse_break_unverified=3
   BREAK_AND_TRAVERSE=LOCAL_STEP/world_modified_not_restored=1
   BREAK_AND_ENTER=LOCAL_STEP/world_modified_not_restored=1
   DOWNWARD=LOCAL_STEP/world_modified_not_restored=1
   ```
   ⇒ 全部 10 种 Movement 都被真实计划覆盖到，等级**不再是常量**，每条都带得出依据。
3. **寻路回归 13/13 PASS**（`pathing/place/break/vertical/pillar/fall/break_enter/trace/chest_step/slab_step/fluid/lava/fence`），
   写预算 `breaks=5/64 places=7/32 refusedBreaks=0 refusedPlaces=0` ⇒ 改评估值**未造成退化**。

**⚠️ 第二步的陷阱（写在动手之前）**：单纯把 `required` 抬到 `PATH_REVERSIBLE` 是**假接线**。
因为当前评估器是**按类型**裁决的 ⇒ FALL 的 evaluated 永远等于 PATH_REVERSIBLE ⇒
`required=PATH_REVERSIBLE` 也就**永远成立** ⇒ 校验只是从"0<0 恒假"变成"1<1 恒真"，
**依然没有鉴别力**（正是本项目反复抓到的"看起来在工作、其实从未计算"）。

**因此第二步的正确形态 = 让评估依赖"逐边事实"而不是"类型假设"**：
1. `PlannedMovement` 携带该边**已验证的事实**（如 `fall_recover_guard=passed`；平走边带 `geometry_unchanged`）；
2. `RecoverabilityEvaluator.evaluate(type, facts)`：**事实缺失 ⇒ 保守降到 `LOCAL_STEP` + 依据 `unverified_*`**；
3. `RecoverabilityPolicy.requiredFor(type)` 数据表：FALL → `PATH_REVERSIBLE`（唯一产出点在 `fallRecoverable` 之后，
   前置已核实：`requiredRecoverabilityLevel` 全仓库只有 `MovementSpec` 一个读者）；
4. 于是"没过守卫的 FALL 边"会**真的抛异常**；自检加**负例**：造一条"无事实的 FALL 边"必须抛，
   而"带 `fall_recover_guard=passed` 的 FALL 边"必须通过。
只有这样，"可回收性"才真正**改变准入结果**（D-036 差异①落地）。

## D-152 基-1 第二步：可回收性**真的改变准入结果**（逐边事实 + 策略表）2026-09-12

**为什么不能只抬 `required`**（D-151 附注一的陷阱）：评估若只看 Movement **类型**，FALL 的 evaluated
就永远等于 `PATH_REVERSIBLE` ⇒ `required=PATH_REVERSIBLE` 永远成立 ⇒ 校验只是从"恒假"变"恒真"。

**做了什么**
1. `RecoverabilityFacts`（新）：**逐边事实**。今天只有一条 `fallReturnVerified`（该 FALL 边过了
   `SurfaceMovementProvider.fallRecoverable` 的 PILLAR 返回守卫）；默认值 `NONE` = 什么都没验。
2. 事实**穿过搜索**：`PlannedMovement` 增加 `recoverabilityFacts` 分量（保留 5 参构造器 ⇒ 缺省 = NONE）、
   `SearchNode.previousFacts`、`AStarMovementSearch` 在展开时写入、回放计划时读回。
3. `RecoverabilityEvaluator.evaluate(type, facts)`：FALL **缺事实 ⇒ 保守降级**
   `LOCAL_STEP/fall_return_unverified`；带事实 ⇒ `PATH_REVERSIBLE/pillar_return_guard_passed`。
4. `RecoverabilityPolicy`（新，**策略表**）：`requiredFor(FALL)=PATH_REVERSIBLE`，其余 `LOCAL_STEP`。
   `PlannedMovementSpecs.toSpec` 的 `required` 一律取自该表（不再散落写死）。
5. 自检加三例：`fall_with_fact_accepted`（带事实必须通过）、**`fall_without_fact_refused`（不带事实必须被拒）**、
   `policy_table`（FALL 要求 PATH_REVERSIBLE、其余维持 LOCAL_STEP）；规则表快照同时打出"有事实/无事实"两行。

**本机纯逻辑实测**
```
GUARDED_FALL   evaluated=PATH_REVERSIBLE required=PATH_REVERSIBLE      → 通过
UNGUARDED_FALL → REFUSED: evaluated recoverability is below Movement requirement
FALL_nofact=LOCAL_STEP/fall_return_unverified   FALL_fact=PATH_REVERSIBLE/pillar_return_guard_passed
POLICY=… FALL>=PATH_REVERSIBLE（其余 LOCAL_STEP）
```
⇒ **D-036 差异① 第一次真的改变准入结果**（不再是"签名能力空实现"）。

**改动面与安全边界**：`requiredRecoverabilityLevel` 全仓库只有 `MovementSpec` 一个读者；FALL 只有一个产出点
（在守卫之后）且已带事实；其余手工构造 FALL 的地方（`ChainDiagnosticTask` 只产 TRAVERSE/DIAGONAL/ASCEND/DESCEND、
`PathingBatteryTask` 用 provider）不受影响。**回归判据**：`alice:pathing_regression` 的 `fall_course` 场景会走
真实 FALL 边穿过搜索 ⇒ 事实链路若断，该场景立刻失败。

**仍未使用**：`SAFE_EXIT_REQUIRED` / `EMERGENCY_EXIT_REQUIRED` 依然**没有产出者**（策略表里不写死它们）。
它们的语义属于"逃逸场景"（生存出口），要有真实产出者时再接。

### D-152 附注一：第二步客户端实测（自检 6/6）+ 一条真实的"残留未收回"观测

**客户端实测（2026-09-12 21:48，`latest.log`）**
```
[Recover] SUMMARY table_nondeterministic=PASS per_type=PASS guard_is_live=PASS
          fall_with_fact_accepted=PASS fall_without_fact_refused=PASS policy_table=PASS verdict=PASS
[Recover] 策略表 … FALL>=PATH_REVERSIBLE …（其余 LOCAL_STEP）
task_execution_terminal kind=RecoverabilityCheckTask terminal=COMPLETED
```
⇒ **不带返回守卫事实的 FALL 边在真实客户端上被真的拒绝**（`fall_without_fact_refused=PASS`），
"可回收性改变准入结果"这条不再是本机推断。
日志里那条 `[Recover] provider/评估器不一致 type=FALL provider=PATH_REVERSIBLE assessed=LOCAL_STEP`
**是自检自己的负例**（故意造的无事实 FALL 边）触发的护栏告警 ✓ 符合设计。

**本轮未覆盖**：`R4 Session completed = 0` ⇒ 本会话**没有跑真实寻路**，所以
"事实穿过搜索链路（`SearchNode.previousFacts` → 计划回放 → `toSpec`）在真实 FALL 边上成立"
**仍未验证**。判据：`alice:pathing_regression` 的 `fall_course`（它必须走到真实 FALL 边）。

**顺带观测到一条真实残留（与本次改动无关，但属于可回收性范畴）**：
```
[Restore] start scope=<all> blocks=4
[Restore] block 24, 65, 44 placed=cobblestone policy=TEMP scope=1ae26630#277:PathingRegressionTask
[Restore] 走上正上方 不通 → 改为侧拆兜底（不挖地形）→ 侧拆兜底也失败
[PathingStats] descend_precondition=3769 status=UNREACHABLE goal=24, 65, 44
[Restore] SUMMARY … restored=0 skipped=4 remaining=4 → FAILED（restore_partial）
```
- 4 块**上一轮 `pathing_regression` 留下的我方 TEMP 方块**（2 块在基岩竖井内 `24,64,44`/`24,65,44`，
  2 块在 `2,63,66`/`3,63,66`）本轮尝试回收，结果 **UNREACHABLE**（bot 在 `8,62,66`，`no_valid_standing_point`）。
- **不是本次改动造成的**：全日志无 Alice 异常、无 `[Recover]` 拒绝；失败码是可达性
  （`UNREACHABLE`）而不是可回收性拒绝；`DESCEND` 的要求未变。
- **但它正是"可回收性不变式"该管的事**：*一次活动留下的我方方块，事后必须能被收回*。
  登记为基-1 的下一批候选（"残留 = 可回收性失败"），待与用户确认是否并入。

## D-153 可回收性的第二条轴：**残留 = 可回收性失败**（用户 2026-09-12 批准"按建议来"）

**背景（真实观测，不是假想）**：一轮 `pathing_regression` 在**基岩竖井内**留下 4 块我方 cobblestone
（scope `#277:PathingRegressionTask`），事后回收 **UNREACHABLE**（bot 站不到正上方、侧拆兜底也失败），
只报出 `restore_partial skipped=4`。这暴露一件事：**"可回收"以前只被理解成"每一步有没有回程"，
没被理解成"我改过的世界有没有收回来"**。

**做了什么（两条轴 + 夹具自清场）**
1. **可回收性 = 两条轴**（写进 `RecoverabilityReport` 的类文档）：
   ① 逐步的"有回程"（D-151/D-152：`evaluated ≥ required`）；
   ② 活动的"没留残留"（本决策：我方 TEMP 方块必须全部收回）。两条都不满足才算真的可回收。
2. **残留计数 + 结构化日志 + 事件**：`RestoreScopeTask` 收尾若**世界事实**里仍有我方方块
   （`pendingTemporary` 对账后 `remaining > 0`）⇒ `RecoverabilityReport.recordResidue(...)` +
   `[Recover] RESIDUE scope=… remaining=N causes=…` + **事件 `RESIDUE`**
   （`DecisionEvents.emit` ⇒ 事件环 + 通知决策层；**自检窗口内只记录不通知**）。
   `alice:bot_report` 的"最近事件"因此能直接看到它。
3. **事件出口收敛为 `DecisionEvents`**（新）：环 + 日志 + 通知决策层（含自检暂停闸门）。
   `EventThresholds` 改为复用它 —— 避免"某个生产者忘了通知决策层/忘了尊重自检暂停"的分叉。
4. **夹具自己清场**：`PathingRegressionTask.advance()` 在**判分之后、进入下一场景之前**重建该场景地形
   （所有 `*_terrain` 函数首行都是 `fill <区域> air`，幂等重放）⇒ 夹具留下的我方方块被一并抹掉；
   账本侧按世界事实对账（`reconciled`）而不是记成失败。日志：
   `[Regression] scene=… cleanup=terrain 我方临时放置剩余=N`。

**验证判据（客户端）**
- `alice:pathing_regression` 跑完后：`[Regression] scene=… cleanup=terrain 我方临时放置剩余=0`，
  随后再跑一次 `alice:restore_check`（或等自动回收）应无 `[Recover] RESIDUE`
  （上一轮的 4 块残块也应被 `pillar_course_terrain` 的 `fill … air` 顺带抹掉 ⇒ 对账为 `reconciled`）；
- `alice:recoverability_check` 的 SUMMARY 末尾多出 `residues[residues=0…]`（本次会话没有残留才算干净）。

**仍未做**：残留的**自动补救**（例如"回收 UNREACHABLE ⇒ 允许在授权下挖开/搭桥取回"）属于能力扩展，
且会碰 D-076 红线，**不在本轮**；本轮只保证"残留一定被如实、结构化地报出来"。

### D-153 附注一：我的清场实现把 bot 埋了（客户端实测暴露，已修）

**实测（2026-09-12 22:51）**：`pathing_regression` 跑到 `break_enter_course` 判 PASS 后，我的清场
（重跑该场景 `_terrain` 函数）执行 ⇒ **bot 正站在该区域里**，`fill <区域> air` + 回填把它埋进方块 ⇒
`hazard=SUFFOCATING` ⇒ `terminal=SURVIVAL_INTERRUPTED code=failed:survival_suffocating pos=23, 64, 100`；
随后**每一次**启动（回归两次、连 `RecoverabilityCheckTask`）都在 1~3 tick 内被维生系统打断，
`SurvivalExitTask` 也 `walk_stale` 失败 —— 现象就是"一启动就马上停"。

**教训（写给未来的自己）**：**夹具清场只能动自己放的东西，不能重建 bot 所在区域的地形**。
场景函数给夹具**搭**场景是安全的（那时 bot 还没进去，且 `prepare` 的顺序是"先建地形再传送"），
但**拆**场景不能在同一位置就地重放。

**修法**：`cleanupScene` 改为**账本精准回收** ——
取当前作用域下本 bot 的 TEMP 记录，逐条核对"世界里的方块是否仍是我方放的那个"：
是 ⇒ `setblock … air` + `WorldModLedger.forget`；不是 ⇒ 留给 `dropStale` 按世界事实销账；
并跳过 bot 所在格与头位格（保险）。**完全不碰地形，也不会有埋人风险**。
日志：`[Regression] scene=… cleanup=ledger 回收我方临时方块=N 非我方=N 销账=N 剩余=N`。

**救援（给当时被埋的现场）**：`/alice come` —— 把 bot 传送回玩家所在位置（带头部同步），
用于把卡在方块里的 bot 拉出来继续测试。

### D-152 附注二：第二步**客户端全绿** —— 事实链在真实 FALL 边上成立

**实测（2026-09-12 22:59，`latest.log`）**
```
[Recover] session=regression-place_course+disturb-1 … FALL=PATH_REVERSIBLE/pillar_return_guard_passed=5
（多会话均出现：…=1 / =3 / =4 / =5）
SUMMARY pathing_course=PASS place_course=PASS break_course=PASS vertical_course=PASS pillar_course=PASS
        fall_course=PASS break_enter_course=PASS trace_course=PASS chest_step_course=PASS slab_step_course=PASS
        fluid_course=PASS lava_course=PASS fence_course=PASS … executed=TRAVERSE,DIAGONAL,ASCEND,DESCEND,
        DOWNWARD,PILLAR,FALL,BREAK_AND_TRAVERSE,BREAK_AND_ENTER,PLACE_STEP_AND_TRAVERSE
task_execution_terminal kind=PathingRegressionTask … terminal=COMPLETED
```
⇒ **`RecoverabilityFacts` 穿过搜索链路（provider → `SearchNode.previousFacts` → 计划回放 → `toSpec`）在真实 FALL 边上成立**，
且 **13/13 场景 + 10 种 Movement 全覆盖**没有退化。第二条轴也同时验到（见 D-153 附注二）。
日志中唯一一条 `provider/评估器不一致` 出现在自检负例 `fall_without_fact_refused` 之前，是**该负例自己触发的护栏告警** ✓。

### D-153 附注二：账本精准清场**生效**，残留归零

```
[Regression] scene=place_course+wall    cleanup=ledger 回收我方临时方块=1 非我方=0 销账=0 剩余=0
[Regression] scene=place_course+disturb cleanup=ledger 回收我方临时方块=2 非我方=0 销账=0 剩余=0
（其余场景 0/0/0/0）
[Recover] SUMMARY … fall_without_fact_refused=PASS … residues[residues=0（本进程内没有出现「我方方块未收回」）]
[Recover] RESIDUE 出现次数 = 0
```
⇒ ① 夹具真的回收了自己放的方块（含竖井内那类"回收 UNREACHABLE"的残块场景）；
② `residues=0` + 零 `RESIDUE` 事件 ⇒ 世界干净、且"一旦不干净就会被结构化报出"的通道已就位；
③ `PathingRegressionTask … terminal=COMPLETED` ⇒ 上一轮"清场埋 bot ⇒ 一启动就停"的 bug 已消除。

**结论**：`基-1 可回收性不变式（项目差异①）` 两条轴都已**客户端验证**：
① 逐步"有回程"（D-151 测量 → D-152 逐边事实 + 策略表，能真的拒绝不带守卫的 FALL 边）；
② 活动"没留残留"（D-153 计数 + `RESIDUE` 事件 + 夹具自清场）。
**仍未做（明确登记，不假装）**：`SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED` **无产出者**（策略表不写死它们）；
残留的**自动补救**（回收 UNREACHABLE 时挖开/搭桥取回）碰 D-076 红线，属能力扩展。

## D-154 基-4：决策 trace 落盘 + 跨重启语义（2026-09-12）

**先盘点现状（事实，不猜）**
| 类别 | 内容 | 重启后 |
|---|---|---|
| 已持久化（SavedData） | 能力分级 `PermissionsData`、收集授权 `CollectGrants`、区域 `LumberRegionState`、世界改动账本 `WorldModLedger`、传输账本、安全区 | **存活**（按设计） |
| **重启即丢（本次登记）** | ① `PermissionGate` 的**未决请示**（内存 `PENDING`/`ANSWERS`/`SESSION_GRANTS`）；② **当前任务**（bot 是假人，重启后从 idle 开始）；③ 决策层节流/菜单等内存态 | 丢失 |
| 只有日志 | 每次决策的请求/回复/执行（`[Goal] …` 行） | 日志轮转后不可查 |

**语义裁定（写下来，否则每次都要重新讨论）**
1. **未决请示重启后一律作废** —— 它承诺的是"某个正在阻塞等待的任务"，而该任务重启后已不存在；
   恢复成悬空请示只会误导玩家与 LLM。**但必须报出来**（日志 + trace + 事件 `RESTART`），绝不静默丢弃。
2. **任务不自动续做** —— 任务树不持久化（任务对象持有世界/会话引用）；重启后如实汇报
   "重启前正在跑 X，未续做"，由玩家/决策层重新决定。
3. **不做任何自动恢复动作** —— 避免造出"看起来恢复了、其实状态不一致"的假象（本项目的头号病灶类型）。

**实现**
- `DecisionTrace`（新）：**JSONL 追加**到 `<config>/alice-decisions.jsonl`（与 `RecipeDump` 同套路：离线可读、可被工具/LLM 消费），
  内存保留最近 64 条供游戏内汇报；超 8 MB 轮转 `.1`；**写失败只告警一次并停用，绝不影响决策**。
  记录点：`GoalDirector` 的请求（含菜单大小）、超时/异常/LLM 错误、以及**每个动作的结局**（executed/refused/no_task + 理由）。
- `DecisionState`（新 SavedData）：登记"重启会丢什么"——每 bot 的未决请示与"正在跑的任务"；
  `consumeRestartReport(botId)` **读后即清**（天然只报一次，避免每次生成 bot 都刷）。
- 接线：`PermissionGate` 入队/答复 ⇒ 登记/清除；`BotSession.beginTask`/`clearTask` ⇒ 登记/清除；
  `BotManager` 生成 bot 时调 `reportRestartState`（日志 + trace + 事件环，**不通知决策层** —— 否则每次开服白花一次 LLM 调用）。
- `DecisionEvents.record(...)`（新，只入环不通知）与 `emit(...)` 分离。
- `alice:bot_report` 新增"决策 trace（最近 8 条）"段；新入口 `alice:decision_trace_check`（零参数，约 1 秒）+ 电池第 18 步 `decision_trace`。

**自检四例**：A 落盘真的写了（从磁盘读回末行）；B 内存尾可读；C `DecisionState` NBT 往返；
D 重启语义（报出来 / **只报一次** / 报完清空）。**本机纯逻辑实测**：
```
REPORT=重启前正在跑「PathingRegressionTask target=x」⇒ 未续做（任务树不持久化）；
       重启作废了 1 条未决请示（发起任务已不存在）：demo_ask by p1 probe
SECOND=[]      CLEARED pending={} task={}
```
**待客户端复测**（`alice:decision_trace_check` + 电池 `decision_trace` 步）。

### D-154 附注一：基-4 **客户端全绿**，并完成一次真实跨重启验证

**实测（2026-09-12 23:16–23:17，客户端 `latest.log`）**
```
[DecisionTrace] case=trace_written result=PASS 磁盘末行={"t":…,"kind":"selfcheck_trace_marker","tick":765,…}
[DecisionTrace] case=trace_memory_tail result=PASS recent=[restart:重启状态报告,
                request:event:TOOL_LOW:工具耐久见底：钻石镐 剩余 219/1561（14%）, result:start_job,
                selfcheck_trace_marker:trace_write_probe]
[DecisionTrace] case=state_nbt_roundtrip result=PASS pendingKept=true taskKept=true
[DecisionTrace] case=restart_semantics result=PASS reported=true onlyOnce=true cleared=true
[DecisionTrace] SUMMARY … verdict=PASS file=D:\…\config\alice-decisions.jsonl written=4 tail=4
task_execution_terminal kind=DecisionTraceCheckTask … COMPLETED
```
⇒ 落盘（真的写进 `config/alice-decisions.jsonl`）、内存尾、NBT 往返、重启报告"只报一次"四例全部成立；
且内存尾里出现的是**真实决策**（`TOOL_LOW` 请求 → `start_job` 结果），不是只有自检标记。

**真实跨重启验证（用户主动重启客户端）**
```
[DecisionState] bot=tango 重启前正在跑「RegionLumberJob target=方块@27, 58, 217」⇒ 未续做（任务树不持久化）
[Events] RESTART bot=tango 重启状态：未恢复上次的请示/任务（…）
（alice:bot_report 的事件环里能看到 #0 RESTART/warn）
```
⇒ 重启前**确实在跑**一个 `RegionLumberJob`，它被持久化登记、重启后被**如实报出且不假装恢复** ——
D-154 的三条裁定在真实重启路径上落地。`基-4 = IMPLEMENTED + COMPILES + WINDOWS_CLIENT`。

**顺带观测（已登记的旧缺口，不是新问题）**：trace 里 `event:TOOL_LOW → start_job` 再次出现
（镐子 14% 时事件招来 LLM，而词汇表里没有"工具维护"这类动作，它只能起 Job）⇒ 属 **S5/S6 知识/动作缺口**。

## D-155 基-5：LLM 上抛契约补全（J-4 / J-6 / J-7，2026-09-12）

**J-6 挖掘产物判定口径（原：硬编码矿物清单）**
- 病：`MineJob.countTargetItems()` 写死 8 个原版标签 + 8 个原版物品 ⇒ ① **模组矿物统计不到**
  （配额永远不满足 ⇒ 任务只能跑到 `goal_timeout`/`partial_quota`）；② **无视 `productTag`**
  （LLM 要铁，配额却被煤顶满 —— 语义错位）。
- 修：新增 `MineProductFilter`（**目标驱动 + 标签族兜底**，纯函数、可断言）：
  指定 `productTag` ⇒ 只认它，且若是 `forge:ores/<material>` 就**同时认** `forge:raw_materials/<material>`
  （Forge 约定：矿掉原矿，否则"挖铁"永远差一个）；未指定 ⇒ 认 `forge:ores/*` + `forge:raw_materials/*`
  **标签族**（运行时从注册表枚举，模组矿物天然覆盖）+ 原版掉落兜底集合。
  `MineJob` 构造时建一次过滤器并打一行 `[MineJob] productFilter=…`。

**J-4 Job 失败报告（原：默认 `phase=unknown` + 空 details）**
- 病：`Task.failureReport()` 默认实现不给领域信息，而四个 Job（`LumberJob`/`MineJob`/`RegionLumberJob`/`CollectJob`）
  **都没覆写** ⇒ 决策层 prompt 里的 `lastTerminal` 有 `failureCode` 却没有"卡在哪一步、当时什么进度"。
- 修：四个 Job 各自覆写 `failureReport()`，`phase` 用自家阶段枚举，`details` 带进度摘要 + `terminalReason` +
  领域细节（MineJob：`attempted`/`inventoryDelta`/`filter`/最后一次尝试失败；RegionLumber：`waitingFor`/苗情；
  Collect：簇数/被拦次数；Lumber：树进度 + recovery 阶段）。

**J-7 结构化拒绝回读**
- 病：LLM 动作被拒的理由只进日志 + 玩家聊天 ⇒ **下一轮 prompt 里没有它**，模型会反复给同一个非法动作。
- 修：`GoalDirector` 记 `lastRefusal{Reason,Tick,Count}`（执行路径与自检**共用** `noteRefusal` 入口）；
  `DecisionSnapshot` 在 `task` 节点写入 `lastRefusal`（**下一轮 prompt 真的带上**）；
  `BotStateReport` 增一行"⚠ 上次决策被拒"；动作被接受执行时清掉（不留 stale 理由）。

**自检（电池第 19 步 `llm_contract` / `alice:llm_contract_check`，纯计算约 1 秒）**
`job_failure_reports`（反射断言四个 Job **真的覆写**了 `failureReport`）、
`product_filter_target`（`#forge:ores/iron` 认铁矿+原铁、不认煤/圆石）、
`product_filter_default`（标签族/原版兜底认矿物、不认圆石/原木）、
`refusal_readback`（登记后 `lastRefusal` 读得到 + **prompt 里真有该字段** + 清掉后消失）。

### D-155 附注一：基-5 **客户端全绿**

**实测（2026-09-12 23:2x，`latest.log`）**
```
[LlmContract] case=job_failure_reports result=PASS 四个 Job 的 failureReport 声明类都=自己
[LlmContract] case=product_filter_target result=PASS filter=#forge:ores/iron | #forge:raw_materials/iron
              ironOre=true rawIron=true coal=false
[LlmContract] case=product_filter_default result=PASS familyTags=13（运行时枚举出的 Forge 矿物/原矿标签数）
[LlmContract] case=refusal_readback result=PASS promptHasField=true cleared=true
[LlmContract] SUMMARY … verdict=PASS     task_execution_terminal kind=LlmContractCheckTask … COMPLETED
```
⇒ J-4/J-6/J-7 三项都在客户端成立。**`familyTags=13`** 是"标签族"口径的实证（旧硬编码清单只覆盖原版 8 个标签，
装模组后统计不到的病灶由此消除）。`基-5 = IMPLEMENTED + COMPILES + WINDOWS_CLIENT`。

## D-156 基-9：工具与耐久管理（第一批，2026-09-12）

**现状复核（事实）**：生产路径**完全不读耐久**（只有 S4 的 `TOOL_LOW` 事件在看）；`MineTask` 对
"工具在主背包"只打一行 `tool_in_main_inventory` **警告**、不会自己修（D-089/D-099 同一病灶第三次同形）；
而且**没有"维护工具"这个动作** ⇒ `TOOL_LOW` 把 LLM 招来时词汇表里没得选，它只能瞎起 Job
（实测三次：`event:TOOL_LOW → start_job region_lumber`）。

**做了什么（只做"不写世界、不耗资源"的部分）**
1. `ToolSupply`（新）：**确定性事实 + 安全动作**。
   - `Kind{PICKAXE,AXE,SHOVEL,SWORD}`（按原版标签 + 物品类识别，模组工具挂标签即可）；
   - `inspect()` 给"**现在能用什么**"（快捷栏优先 —— 与生产路径的选工具口径一致），
     `bestInMain()/bestInHotbar()` 给"**还能换成什么**"；
   - `promoteFromMain()`：主背包有更好的同类 ⇒ **对调**（新的进快捷栏、旧的回主背包）；快捷栏空 ⇒ 搬入；
     结果码 `swapped_with_worn`/`moved_to_empty`/`already_in_hotbar`/`hotbar_full_no_swap`/`no_tool`；
   - **不合成、不去取材料、不动世界** —— 那是 S6 + 请示通道（能力名 `fetch_tool_materials` 已登记）。
2. `ToolMaintenanceTask`（新）：确定性维护流程，终态四种：`already_ok` / `promoted_from_main` /
   **`worn_no_spare`**（手上这件已 ≤20% 且没有更好的 ⇒ 如实说"需外部补充"，**不假装修好了**）/ `no_tool`。
   任务不产生失败（`failureReason()` 恒空），事实写在 `terminalReason` + summary 里。
3. **决策层动作** `{"action":"maintain_tool","kind":"pickaxe"}`（词汇表 + `GoalAction.MaintainTool` + 解析 +
   执行 + `BotManager.assignToolMaintenance`）⇒ `TOOL_LOW` 之后终于有**能自己解决**的选项。
4. **事实进 prompt/汇报**：`DecisionSnapshot` 新增 `tools` 节点（summary + 每种工具的 present/inHotbar/
   remaining/max/spareInMain）；`bot_report` 增"工具：…"一行。
5. **事件文案可行动**：`TOOL_LOW` 现在带上"在主背包（可用 maintain_tool 搬进快捷栏）/ 主背包有更好的 /
   身上没有更好的（需外部补充）"。

**自检**（电池第 20 步 `tool_supply` / `alice:tool_supply_check`）：
A 手上快坏的镐 + 主背包新镐 ⇒ 维护后 **新的到手上**（`promoted_from_main`，ratio>0.9）；
B 只有快坏的、无替代 ⇒ `worn_no_spare`；**C 负例**：身上没有镐 ⇒ `no_tool` 且**不得凭空变出工具**。
收尾复原背包（一把镐 + 一把斧）。

**未做（明确登记）**：工具**来源**（合成/取材料/容器取用）与"耐久不足以完成计划工作量时提前拒绝"
（需要与 `MiningPlanner` 的工作量估算打通）—— 前者属 S6，后者列入下一批。

### D-156 附注一：首轮"失败"是**我自检的断言 bug**，功能本身在日志里已经做对了

**实测（2026-09-12，`latest.log`）**
```
[ToolMaint] 镐 code=swapped_with_worn     → SUMMARY terminal=promoted_from_main
             before[镐=钻石镐 157/1561(10%) 快捷栏] after[镐=铁镐 250/250(100%) 快捷栏]
[ToolMaint] 镐 code=already_in_hotbar     → SUMMARY terminal=worn_no_spare
             （"可用工具剩余耐久仅 10%，且身上没有更好的 ⇒ 需外部补充"）
[ToolMaint] 镐 no_tool                    → SUMMARY terminal=no_tool
```
⇒ **三种行为（换上更好的 / 没得换如实上报 / 没有工具）在真实客户端上都正确执行了**；
`after_restore=镐=钻石镐 1561/1561 | 斧=钻石斧 1561/1561` ⇒ 收尾复原也生效。

**但自检红了，原因全在我自己的断言代码：**
1. `runSub` 里**先把 `sub` 置 null、再通过 lambda 读 `sub.terminalReason()`** ⇒ 永远读到占位符 `-`
   （日志里 `terminal=-` 就是这个）；修法：**先取终态再置 null**（已去掉那层没必要的 lambda）。
2. `Snapshot.spareInMain` 被我写成"主背包件数 − 1"，语义错（自检前提 `spareInMain>=1` 因此失败）。
   修法：语义定为"**主背包里该类工具的件数**"，并新增 `bestMainRemaining`（"主背包那件还剩多少"）——
   这样"能不能自己换上"是**数据**，不是推断（`TOOL_LOW` 文案与 prompt 都用它）。

**教训（第 N 次同族）**：断言代码自己也会说谎。判据要能区分"功能没做对"与"断言写错了"——
本轮日志里 `[ToolMaint]` 的行为行与 `[ToolSupply] case=` 的断言行是**分开的**，所以一眼能分开。

### D-156 附注二：基-9 第一批 **客户端全绿**

**实测（2026-09-12 23:5x，`latest.log`）**
```
case=swap_premise PASS   before=镐=钻石镐 157/1561(10%) 快捷栏 主背包另有 1 件（其中更好）
case=tool_swap PASS      terminal=promoted_from_main  after=镐=铁镐 250/250(100%)
case=worn_premise PASS   before=镐=铁镐 25/250(10%) 快捷栏
case=worn_no_spare PASS  terminal=worn_no_spare（"身上没有更好的 ⇒ 需外部补充"）
case=no_tool_premise PASS   inspect=镐=无
case=no_tool PASS        terminal=no_tool 仍无镐=true（负例：不得凭空变出工具）
SUMMARY tool_swap=PASS worn_no_spare=PASS no_tool_no_conjure=PASS verdict=PASS
after_restore=镐=钻石镐 1561/1561(100%) | 斧=钻石斧 1561/1561(100%)
task_execution_terminal kind=ToolSupplyCheckTask … terminal=COMPLETED
```
⇒ `基-9（第一批）= IMPLEMENTED + COMPILES + WINDOWS_CLIENT`。
**未完项（登记在册）**：① 工具**来源**（合成/取材料/容器取用）属 S6；② "**耐久不足以完成计划工作量时提前拒绝**"
（需与 `MiningPlanner` 的工作量估算打通）—— 归入下一批。

## D-157 基-8（第一批）：把 `MovementCapabilities` 变成**执行期真的会拦人**的能力闸门

**复核发现（比总账登记的更严重，另有一条登记已过期）**
- **G8 不只是 3 个字段**：`MovementCapabilities` 的 **10 个分量读者为 0**（`changesWorld` / `canBreakBlocks` /
  `canPlaceBlocks` / `requiresZoneAuthorization` / `consumesResources` / `requiresTool` / `maxNaturalDrop` /
  `supportsMidExecutionRevalidation` / `mutationIntents` / `intrinsicReversibility`），
  只有 `requiredRecoverabilityLevel` 在基-1 后有读者。⇒ "寻路不许偷偷写世界"（D-076）此前**只靠
  `PathRequest.allowedMovementTypes` 这一层类型清单**在守，能力声明本身是装饰（P0-B 同族病灶）。
- **`REGION_REPLANT`：代码早已接线、文档登记表缺号** —— 代码侧 `RegionLumberJob` 用它作补种放置的
  `WriteReason`（`temporary()==false` ⇒ 账本记 `KEEP`，且过 `WriteBudget.consumePlace`）；
  但**授权登记表文档** `WORLD_WRITE_AUTHORIZATION.md` 的表格只到 A8、下一节直接是 A10 ⇒ **A9 断档**。
  ⇒ 总账那条"未登记"指**文档**、是对的；我一开始误判为"过期信息"，此处**自我订正**，并已补上 **A9 条目**。

**做了什么**
1. `CapabilityGate`（新，**纯函数**）：执行期复验，五类拒绝码 + 一个放行：
   - `CAPABILITY_UNAUTHORIZED`：**会改世界**的 Movement 却拿**纯通行请求**执行（声明与授权不符）；
   - `ZONE_PROTECTED_AREA` / `ZONE_PROTECTED_BLOCK` / `ZONE_PROTECTED_TAG`：目标落在保护区（读 `SafeZoneData`）；
   - `NO_REQUIRED_TOOL`：需要工具而快捷栏没有（生产路径只从快捷栏选 ⇒ 白挖）；
   - `NO_THROWAWAY_BLOCKS`：需要消耗一次性方块而没有；
   - `BREAK_BUDGET_EXHAUSTED` / `PLACE_BUDGET_EXHAUSTED`：写入预算用尽（读 `WriteBudget`）。
   世界事实由 `CapabilityGate.Facts` 注入 ⇒ 纯逻辑可自检（喂假事实即可断言"能不能拦住"）。
2. `PathSession.startSegment` 在**授权复验之后、执行之前**调用闸门；拒绝 ⇒ `[R4 Session] capability_gate_denied …`
   + `mapFailure(code)`（拒绝码进会话失败码，可归因）。**为什么在执行期**：与 D-076「plan 可能比产生它的
   请求活得更久」同一条理由 —— 保护区、一次性方块、工具、预算都可能在计划之后变化。
3. 自检（电池第 21 步 `capability_gate` / `alice:capability_gate_check`）：五个**负例** + 两个正例 +
   一组**声明一致性**断言（真实 `toSpec` 产出的 caps 必须与 Movement 的写世界性质相符：
   平走类 `changesWorld=false`、放置类 `canPlaceBlocks=true`、破坏类 `canBreakBlocks=true`）。

**仍未接线的字段（明确登记，不假装）**：`maxNaturalDrop`（掉落上限）、`supportsMidExecutionRevalidation`
（本闸门就是"执行期复验"的载体，但该布尔本身仍未读）、`mutationIntents`（目前只用于构造期自洽校验）、
`intrinsicReversibility`（可回收性用类型规则而非它 ⇒ 二者重叠，待收口）。
**G3（模组连锁破坏无凭证）与 G5（容器写入维度）** 未动，列入下一批。

### D-157 附注一：能力闸门首测失败 = **两处我的期望错**，其中一处暴露真问题

**实测（2026-09-13 00:3x，`latest.log`）**
```
case=pure_traversal_allowed PASS   case=capability_unauthorized PASS
case=zone_protected FAIL expected=ZONE_PROTECTED_AREA actual=(放行)
case=no_tool PASS   case=no_throwaway PASS   case=no_place_budget PASS   case=all_facts_ok_allowed PASS
case=declarations FAIL  … PILLAR=changesWorld=true break=false place=true
```
1. **`declarations` FAIL = 我的期望写错**：PILLAR 我写成 `(changesWorld=false, breaks=true)`，实际是
   `(changesWorld=true, breaks=false, place=true)`（PILLAR 是"放方块往上爬"，不破坏）。已改为 `(true,true,false)`。
2. **`zone_protected` FAIL = 真问题**：`MovementCapabilities.pathAccess()` 把
   `requiresZoneAuthorization` 声明成 **false**（`temporarySupport()` 同样）⇒ 即使闸门接了保护区检查，
   **生产里也永远不会触发** —— 字段又退化成装饰（G8 同族，第 N 次）。
   修法：**改工厂**（会改世界的 Movement 一律 `requiresZoneAuthorization=true`），而不是改测试。
   默认没有任何保护区时行为不变；一旦玩家划了保护区，破坏/放置类 Movement 会被
   `ZONE_PROTECTED_*` 拒绝（这正是保护区的意义）。
3. 自检同时保留**合成用例**（`zoneAuth=true` 的 caps）+ **生产声明用例**（`zone_protected_by_declaration`），
   后者专门防"字段又变装饰"回归。

**顺带（用户反馈）**：测试物品贴图此前全落到默认 `alice:item/check` ⇒ 分不清。已按类别补齐：
`check_pathing`（蓝：内核/安全）、`check_decision`（紫：决策层）、`check_fixture`（橙：夹具/工具），
并复用已有的 `mine`/`lumber`/`pathing`/`select`；21 个模型已归类。

### D-157 附注二：G3 第一批 —— 范围内**外来破坏**不再无声无息

**复核（`ScopeBuffer.onBlockBreak`）**：原先只登记**我方**破坏（用于掉落物配对），
范围内由**别人/模组连锁/爆炸**造成的破坏被**静默忽略** ⇒ 世界确实被改了，但账本、预算、上报里都看不见。
这正是"未知模组能力默认只读、不让 AI 猜"的对偶面：不猜可以，**但不能装作没发生**。

**做了什么（只记录，不改变行为）**
- `ScopeBuffer` 新增**有界外来破坏记录**（16 条）+ 计数 + 节流告警（第 1 次与每 10 次）：
  `[Scope] 范围内**外来破坏** #N pos tick by=<uuid|unattributed>（非我方；账本不恢复、预算不计入 —— 仅如实记录）`；
  `unattributed` 专指**没有玩家来源**的破坏（爆炸 / 模组程序化破坏）。
- `bot_report` 新增一行 `作用域：外来破坏=N（最后 pos tick by=…）`。
- **自检（能力闸门任务内，双向断言）**：伪造两条 `BreakEvent`（模组连锁走的正是同一条事件路径）——
  ① breaker=**观察者** ⇒ 计入；② breaker=**本 bot** ⇒ **不得**计入。
  只测 ① 无法排除"把所有破坏都记成外来"的假实现，所以必须双向。

**明确未做**：**阻止**外来破坏（`BreakEvent` 可取消 ⇒ 技术上能拦，但那会改变模组行为与保护区语义，
属独立决策）；外来破坏的**恢复**（我们不恢复非我方改动）。
**G5（容器写入维度）** 仍未动，作为 基-8 的最后一项。

### D-157 附注三：G5 —— 容器写入维度（只记录，不撤销）

**复核**：方块改动有账本（`WorldModLedger`），**容器改动没有** —— `TransferLedgerData` 记的是
**请求与状态迁移**，不记"哪几件东西从哪去了哪" ⇒ "bot 往箱子里放了什么/拿了什么"没有可审计的痕迹。

**做了什么**
- `TransferLedgerData` 新增**物品移动**记录（有界 32 条）+ 计数 + 件数 + 一行式描述，并**持久化进 NBT**：
  `record Movement(requestId, leg, itemId, count, pos, tick)`，`leg ∈ {chest_to_bot, bot_to_chest}`。
- 两段**已证明**的写入之后各记一条（取真实的 `botDelta` / `destinationDelta`）：
  `TransferTask.sourceWrite()`（从源箱取出）与 `destinationWrite()`（写入目标箱）。
- `bot_report` 新增一行 `容器写入：N 次 / M 件（最后 …）`。
- 自检（能力闸门任务内）`container_write_record`：用**独立实例**记两条移动 ⇒ 断言计数/件数/描述，
  再 `save → load` **NBT 往返**断言记录真的进存档（只活在内存里不算审计痕迹）。

**明确未做**：容器写入的**撤销/恢复**（把东西放回去）—— 那需要"谁授权、恢复到哪个状态"的完整语义，
属独立决策；本批只保证**留痕**。

**至此 基-8 四项全部有交代**：G8 能力闸门（含"保护区字段又变装饰"的真问题修复）、A9 登记表断档补齐、
G3 外来破坏留痕（双向自检）、G5 容器写入留痕（含 NBT 往返断言）。

## D-158 基-7 第一批：K-1 best-so-far 前缀（顺带把 K-5 的死状态变成活状态）

**复核（事实）**
- `AStarMovementSearch` 里 `bestSoFar[]` **一直在算**，但预算耗尽/搜索穷尽时只把 `best=cost` 写进诊断字符串，
  **前缀被丢掉** ⇒ 调用方只能拿到"失败"，拿不到"已经走通的那一段"；
- 新版枚举 `PlanningStatus` **没有 PARTIAL**；而 `pathing/PathPlanner.Status.PARTIAL`（**遗留内核**枚举）
  虽然有这个值，却**零产出者**（K-5 死状态）⇒ K-1 与 K-5 是同一件事的两面；
- 唯一的执行期消费者 `PathRetryRunner` 对**任何**非 `REACHED` 一律 `FAILED`（`plan_failed`），前缀无处可用。

**做了什么**
1. `PlanningStatus.PARTIAL`（新）：文档写死语义 —— **不是到达**（`PathPlan.reached()` 仍 false）、
   **不是不可达**（前缀的存在恰恰说明还有得走）、承载"**先走一段再重规划**"。
2. `PathPlan.partial(...)` + `partial()`；搜索抽出 `prefixTo(node)`/`projectedFootPath(...)`（与 `reachedPlan` 共用，
   避免两份前缀构造逻辑）。
3. `AStarMovementSearch`：**只在预算耗尽**且 best-so-far 有前驱时返回 `PARTIAL`（`partialPrefix=N` 写进诊断）；
   **搜索空间真穷尽（UNREACHABLE）不给前缀**（那是"证明到不了"），`SEARCH_LIMIT` 无前缀时保持原样。
4. **让消费者真的用它**（否则又是一个"产出无人读"的值）：`PathRetryRunner`
   - `PARTIAL` ⇒ `[PathRetry] partial_plan hop=k/4 movements=N` ⇒ **执行前缀**，走完**不算到达**，
     从新位置**继续重规划**（连续消费上限 `MAX_PARTIAL_HOPS=4`，防"永远走一段、永远到不了"的空转）；
   - 超出上限 ⇒ 如实失败 `PLAN_PARTIAL_LIMIT` / 无重规划额度 ⇒ `PLAN_PARTIAL_NO_REPLAN`；
   - 其它非 `REACHED`（UNREACHABLE / GOAL_NOT_LOADED / CANCELLED）**行为不变**（仍如实失败）。
5. 自检（电池第 22 步 `partial_search` / `alice:partial_search_check`，纯规划不动 bot）：
   A 远目标 + 极小预算 ⇒ `PARTIAL` 且前缀非空、投影长度 = 边数+1；
   B **同目标** + 正常预算 ⇒ `REACHED`（证明 A 是"没算完"而非"规划不好"）；
   C 目标丢到极远处（未加载）⇒ `GOAL_NOT_LOADED` 且**无前缀**（守住"真失败不给前缀"的契约）。

**仍未做（基-7 余项）**：K-2 `SurfacePathfinder`/`PathExecutor` 等**遗留内核仍有 7+ 处活调用**（须迁移或显式登记）；
K-3 `safeToCancel` 概念全缺（Baritone 有：取消时是否安全）；K-4 谓词不统一；
K-5 遗留枚举 `PathPlanner.Status.PARTIAL` 仍是死值（随 K-2 收口处理）。

## D-159 基-7 第二批：K-2 legacy 双内核 —— **生产路径已收敛到单内核**

**逐点清单（2026-09-13 复核，`SurfacePathfinder.find` 共 7 处调用）**

| 调用点 | 性质 | 处置 |
|---|---|---|
| `TransferTask.move()`（走到源箱/目标箱） | **生产路径**（传输两段行走） | ✅ **迁移**到 `PathRetryRunner`（新内核） |
| `BotCommand.diagnosePath()`（`/alice path` 只读诊断） | **开发命令** | ✅ **迁移**到 `CorePathPlanner` |
| `PathingRegression`（legacy 自带回归，×2） | 仅被 `BotSelftest`（手动开发自检）调用 | 登记为**遗留保留**（非生产路径） |
| `TunnelPlanner`（×2） | **无调用者** | 登记为**死代码**，待 K-2 batch 3 删除 |
| `LocalPathPlanner`（×1） | **无调用者**（连同 `HybridPathPlanner`/`PathPlanners`） | 同上（死代码） |

**迁移细节（TransferTask）**：`PathRetryRunner` 每次**从当前脚位重规划**并可重试；
失败码映射保持原词表 —— `PLAN_*UNREACHABLE` ⇒ `HARD_PATH_UNREACHABLE`，
其余 `PLAN_*`（预算耗尽/未加载/前缀用尽）⇒ `HARD_PATH_SEARCH_LIMIT`（对齐 D-076「`SEARCH_LIMIT ≠ UNREACHABLE`」），
会话级失败 ⇒ `HARD_PATH_FAILED`。顺带白拿 **K-1**：预算耗尽时先走前缀再重规划。

**迁移细节（`/alice path` 诊断）**：改用 `CorePathPlanner`，输出 `PlanningStatus`（`SEARCH_LIMIT`/`GOAL_NOT_LOADED`/
`UNREACHABLE`/`PARTIAL` 四态明确区分），并在"预算耗尽/未加载/只有前缀"时**明确写出"不能据此授权挖通道"**。

**迁移后**：剩余 `SurfacePathfinder.find` 调用点**全部在 legacy 文件内部**（`PathingRegression`/`TunnelPlanner`/
`LocalPathPlanner`），**生产与开发命令路径上已无 legacy 引用**。

**K-2 batch 3（待做）**：删除无调用者的 `TunnelPlanner`/`TunnelObstaclePolicy`/`LocalPathPlanner`/
`HybridPathPlanner`/`PathPlanners`（除非有保留理由）；`BotSelftest` + legacy `PathingRegression`
二选一：**迁移**到新内核自检，或**显式登记为仅手动开发自检**。
**验证入口**：传输行走由 `TransferFixture` 覆盖，而它由 `/alice selftest` 触发（见 `BotSelftest:346`）。

## D-160 活雷拆除：`InterfaceScanner` 的 Mekanism 硬引用 → **absence-safe**

**崩溃现场（2026-09-13 00:44，客户端 `crash-reports/`）**
```
Description: Exception in server tick loop
java.lang.NoClassDefFoundError: mekanism/common/capabilities/Capabilities
  at InterfaceScanner.scanMek(InterfaceScanner.java:167)
  at InterfaceScanner.capture:65 → BotSelftest.runInterfaceSnapshotRegression:373 → BotSelftest.setup:344
```
**根因**：Mekanism 是**可选模组**（`build.gradle:142` 是 `implementation`），但
`InterfaceScanner` 里有 **15 处 `mekanism.*` 符号引用**（capability / ITileRedstone / ITileUpgradable /
ISecurityTile / ISideConfiguration / Upgrade / RelativeSide / DataType …）⇒ **任何没装 Mek 的客户端**
只要走到 `capture()`（例如 `/alice selftest`）就必崩。

**修法（absence-safe，不靠反射）**
- 全部 Mek 代码（`scanMek` 家族 + `appendIfPresent`，119 行）搬进新类 `capability/MekanismScanner.java`；
  `InterfaceScanner` 只剩一处**守卫式调用**：
  ```java
  if (net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) {
      MekanismScanner.scan(legacy, be);
  }
  ```
  **JVM 惰性加载** ⇒ 缺席时 `MekanismScanner` 永不加载 ⇒ 永远不会去解析 `mekanism.*` 符号 ✓
  （比反射干净：保留了编译期类型检查）。
- `InterfaceScanner` 现在**零 Mek 符号引用**（只剩注释与那句 `isLoaded("mekanism")`）。
- 语义不变：Mek 探查仍是**只读**（"有什么接口"），不驱动不写入（见 `docs/MEK_GUI_SEMANTICS.md`）。

**同类风险提示（大清查待办）**：任何"可选模组的硬引用"都是同一种雷。
本轮同时确认：客户端装的 243 个模组里**只有 oreexcavation/JEI** 与本仓库有交互面，
其余（Mek/Thermal/Create）**都没装** ⇒ 阶段 2 若要在客户端测它们，必须先把 jar 放进 `mods/`，
**而不是**让代码假设它们在场。

**待定**：`build.gradle` 是否把 Mekanism 从 `implementation` 改为 **`compileOnly`**（让开发期也没有它，
从而**在开发期就暴露**这类硬引用）—— 这是"让环境与测试客户端一致"的做法，但会改变本地 dev 运行条件，**待用户定**。

## D-161 传输模块重构 R1+R2 + 依赖清理 + selftest 退役（用户 2026-09-13 裁定）

**用户裁定**：① **R1/R2/R3 全做**；② **容器写入算"世界改动"** ⇒ 进 A 表 + 要授权/预算；
③ 交互做 **L1（范围内代码操作）**先，**L2（真实 openMenu 菜单协议）另行专门讨论**（它影响整个项目的
模组方块交互基底）；④ **selftest 退役**；⑤ **删掉与 Alice 本质无关的模组依赖**（"要用时我自己在客户端装"）。

**已完成的四项**

1. **依赖清理（B1）**：`build.gradle` 移除 Mekanism（`implementation` + `runtimeOnly generators`）——
   **项目不再声明任何可选模组依赖**。理由（教训）：开发期有 Mek、测试客户端没有 ⇒
   `InterfaceScanner` 15 处 `mekanism.*` 硬引用在真实客户端**必崩**（2026-09-13 崩溃报告）。
   **开发环境必须与测试客户端一致**，否则缺陷被环境差异掩盖。模组集成今后一律"运行时反射/独立可选模块"。
   随之**删除 `MekanismScanner`**（上一轮刚拆出的 absence-safe 类）——既然不留编译依赖，Mek 探查代码一并撤掉；
   语义知识保留在 `docs/MEK_GUI_SEMANTICS.md`。
2. **selftest 退役（B2）**：删除 `bot/BotSelftest.java`、`/alice selftest` 命令、legacy `pathing/PathingRegression.java`；
   其中**唯一仍被新内核使用**的有用断言 `assertFootCellRule` 救出为 `pathing/FootCellRuleCheck.java`
   （`PathingRegressionTask` 仍用它做 D-105 无头断言）。
3. **R1 生产/测试分离（B3）**：4 个传输夹具迁到新包 `com.dddgn.alice.fixture.transfer`
   （`TransferFixture`/`TransferSelectionFixture`/`TransferEndpointSelectorEventsFixture`/
   `TransferSelectionCommandParseFixture`，共 5 个类）。生产侧只保留**一个显式、公开、默认惰性**的接缝
   `transfer/TransferTestHooks`（含原 `TransferTask.fixtureMovementOutcome` 的接缝化版本
   `movementOutcome()/takeMovementOutcome()`）；`TransferTask` 不再自带夹具状态。
4. **R2 一键入口（B3）**：新增 `alice:transfer_check`（`TransferCheckItem` + `TransferCheckTask`）
   —— 零参数右键一次跑完 4 个夹具、输出 `[Transfer] SUMMARY fixture=… selection=… selector_events=…
   command_parse=… verdict=…`；**单个夹具抛异常也只记 FAIL，不炸整条自检**（`guarded(...)`）；
   已进回归电池第 **23** 步。

**产物核对**（jar 内类表）：`BotSelftest` 0 处、legacy `PathingRegression` 0 处、`MekanismScanner` 0 处、
新夹具包 5 个类、`TransferCheckItem/Task` 在场、总类数 535 ✓（避免上次那种"类错位"崩溃）。

**仍未做**：**R3**（删 3 个死码 + 处理 2 个只写状态 + 容器写入入 A 表并接授权/预算）、
**L1**（传输行走改为"附近任一可站点 + 触及校验"，取代"站箱子上"）。二者为下一批。

## D-162 传输模块 R3 + L1：容器写入纳入授权/预算；行走改为"附近任一可站点 + 触及校验"

**R3（清死码 + 授权维度）**
1. **删 3 个零引用错误码**（`SURVIVAL_SUFFOCATING`/`ACTOR_DISCONNECT`/`BOT_MISSING`）。
2. **两个"只写状态"重新定性，不删**：`SOURCE_LEG_PRE`/`DESTINATION_LEG_PRE` 是**审计哨兵**
   （记录"某段写入之前"这一刻），账本本职是**证据链**（类文档 "stores evidence"）⇒ 删掉等于删证据。
   已在 `TransferLedgerData.State` 上写明：它们**不被决策逻辑读取**，属证据而非死码。
3. **容器写入纳入"世界改动"体系（用户裁定）**：
   - `WriteReason.CONTAINER_TRANSFER`（`Policy.EXPLICIT_TARGET`；**用 `Action.BOTH` 而非新增 `Action.CONTAINER`**
     —— `Action` 字段目前**无读者**，新增值只会再造一个死值，已登记该元数据债）；
   - `WriteBudget` 新增**第三维度**：`Caps.maxContainerWrites`（默认 **32**）+ `consumeContainerWrite(...)`
     （超限即 `REFUSED`）+ `remainingContainerWrites(...)`；SUMMARY 增
     `containers=N/M refusedContainers=N`；`Caps` 加了兼容构造器（旧的 2 参调用点不受影响）；
   - `TransferTask` 两段写入前各消费一次；被拒 ⇒ `container_budget_exhausted`（如实失败，不硬写）；
   - **审计带授权**：G5 的物品移动记录新增 `requester`/`reason`（NBT 也持久化）⇒ 审计能回答
     "谁按什么理由动的这箱东西"；
   - 登记表补 **A11 容器写入（传输）**。

**L1（用户裁定：先做"范围内代码操作"，L2 真实菜单协议另立项）**
- `TransferTask.move(...)` 的目标从"**箱子正上方**"改为 **端点附近最近的合法站点**
  （正上方 + 同层 4 正邻 + 8 斜邻，要求 `canWalkOn` 且两格净空）；
- 到达后按**原版触及语义**校验（`BlockInteraction.reachable`）⇒ 够不到就 `endpoint_out_of_reach` 如实失败；
  两段写入前**再各校验一次**（站好了也可能漂移）；
- 边界情况已处理：**已经站在可站点上**时跳过规划（避免"零长度路径"这种边界）；
- 找不到站点 ⇒ `endpoint_no_standing_point`；两个新码与预算拒绝码一并入 `TransferCodes`。

**验证**：编译通过 + jar 内容核对（旧类零残留）。**待客户端复测**：`alice:transfer_check`
（4 夹具全绿即可确认 L1 的三条路径没破坏既有行为），并留意日志里 `containers=N/M` 是否出现。

## D-163 L2 技术验证探针：`alice:menu_probe`（B 路线可行性第一测）

**背景（用户 2026-09-13 裁定）**：同意 **"B 默认 + A 显式优化"** 的分层；先做
`INTERACTION_LAYERS_COMPARISON.md` §3 第 5 项的**最小验证**（"无真实客户端时服务端菜单状态是否自洽"），
并要求**加延迟**以便在游戏里肉眼观察菜单效果。

**探针做什么**（`task/MenuProbeTask`，约 8 秒，带延迟：`FACE_HOLD=30 / OPEN_HOLD=40 / CLICK_HOLD=20`）：
1. `SETUP`：bot 东侧 2 格放箱子、塞 3 个铁锭、清空 bot 背包、传送到箱子旁（探针求确定性；
   生产 L2 应走内核寻路 + L1 站位/触及）；日志打印 start 信息；
2. `FACE`：**转向箱子**（`BlockInteraction.faceTowards`）并停 1.5 秒 —— 人能看见 bot 转头；
3. `OPEN`：**`bot.gameMode.useItemOn(bot, level, 主手, MAIN_HAND, BlockHitResult)`** + `swing`
   —— 这是**真人客户端开箱最终走的同一个方法**；随后断言 `bot.containerMenu != inventoryMenu`
   并打印菜单类型/槽位数；
4. `CLICK_PICK`：在菜单里找到铁锭所在槽 ⇒ **`menu.clicked(slot, 0, ClickType.PICKUP, bot)`** + `broadcastChanges()`
   ⇒ 该堆"拿在鼠标上"（打印 `carried`）；
5. `CLICK_PLACE`：找**快捷栏空槽**（ChestMenu 27 格：0..26 箱子 / 27..53 背包 / 54..62 快捷栏）再点一次
   ⇒ 落进 bot 手上（客户端能"看到 bot 拿着铁锭"）；
6. `CLOSE`：`bot.closeContainer()` ⇒ 断言回到 `inventoryMenu`（盖子合上）；
7. `ASSERT`：bot 背包 3 铁锭、箱子 0 ⇒ `[MenuProbe] SUMMARY menu_opened=… menu_type=ChestMenu/N
   clicks=… item_moved=… verdict=…`。

**判据与意义**：`menu_opened=PASS` + `item_moved=PASS` ⇒ **B 路线（通用菜单驱动器）可行**，
可以继续按 §4 建议投入（槽位语义表 / 生命周期与超时 / A11 授权衔接）；
若 `menu_not_opened` 或点击无效 ⇒ **整条 B 路线需重新评估**（而不是先写一堆适配器）。

**卫生**：任何失败路径都会 `closeContainer()`，不把 bot 卡在菜单里（探针自身的教训来自
"卡在状态里"这一类历史缺陷）。**探针不进回归电池**（它是可行性验证，不是生产功能）。

### D-163 附注一：L2 **验证通过**（用户实测"符合预期"）+ 立即暴露的生命周期漏洞已修

**验证结论（用户目视 + 判据）**：`useItemOn` 真的开出了 `ChestMenu`、`menu.clicked` 真的搬动了物品、
`closeContainer` 干净收尾 ⇒ **B 路线（真实菜单协议）技术上成立**，
"B 默认 + A 显式优化"的分层可以作为后续主线（`docs/INTERACTION_LAYERS_COMPARISON.md` §4）。

**用户立即发现的真问题**："**任务终止的时候箱子还是开着的**"。
根因：探针只在**正常路径**的 `CLOSE` 阶段关菜单；**任何提前终止**
（用户 `/alice stop`、维生打断、任务被替换、失败）都绕过它 ⇒ `ContainerOpenersCounter` 没减回去，
**盖子一直开着**（箱子还会长期处于"被占用"状态）。

**修法（`bot/MenuLifecycle`，两条保证）**
1. **收尾收敛点强制关闭**：`BotSession.clearTask()`（`stopTask` / 被替换 / 维生打断 / 正常结束**都走它**）
   与 bot 移除处调用 `MenuLifecycle.closeOpen(bot, reason)` ⇒ 关闭 + **打警告**（哪条路径漏了一眼可见）。
2. **看门狗（诚实兜底，不是掩盖）**：每 tick 若"菜单开着但**没有任何任务**在跑"超过
   `IDLE_MENU_LIMIT_TICKS=60` ⇒ **先告警**（含菜单类型）再关闭，并计入 `closedByWatchdog`
   ⇒ **有告警就说明有人忘了收尾**（可审计：`MenuLifecycle.describe()`）。

**复测判据**：跑 `alice:menu_probe` 后在它中途 `/alice stop`（制造提前终止）⇒
应看到 `[Menu] 收尾时仍开着容器菜单 ChestMenu（reason=clearTask:MenuProbe）⇒ 强制关闭`
且**箱子盖子合上**；若出现 `[Menu] 看门狗：…` 则说明还有路径没在收尾点关闭（要补，而不是容忍）。

## D-164 L2 生产化第一步：`MenuSession`（菜单会话）+ 探针改为其首个消费者

**背景**：D-163 证明 B 路线（真实菜单协议）技术成立；用户在实测中立刻发现"任务终止时箱子还开着"，
说明"菜单开着"这个新状态**必须由组件本身保证生命周期**，不能靠调用点自觉。

**做了什么**
1. `action/MenuCodes`（新）：菜单维度的**稳定失败码**（与方块写入的失败码分维度，避免日志里
   "没权限"和"菜单没开"混在一起）：`menu_open_failed` / `menu_open_timeout` / `menu_target_mismatch` /
   `menu_closed_early` / `menu_slot_mismatch` / `menu_container_full`。
2. `action/MenuSession`（新，**唯一的菜单交互组件**）：
   - **打开异步化**：`open(bot, target, containerSlots)` 立刻发真实右键（转向 + 挥手 + `gameMode.useItemOn`），
     随后 `tick()` 推进直到 `containerMenu` 生效；超时 ⇒ `menu_open_timeout`；
     开出来的容器比声明的小 ⇒ `menu_target_mismatch`（点到别的容器了）；
   - **只走菜单协议**：`click(slot, ClickType)` = `menu.clicked(...)` + `broadcastChanges()`（真人左键的同一条路）；
     提供 `findInContainer(predicate)` / `findEmptyPlayerSlot()` / `firstHotbarSlot()` / `carried()` / `stillValid()`
     —— **不猜槽位语义**（语义由上层或未来的语义表决定）；
   - **生命周期自我保证**：`close(reason)` **幂等**；**任何失败路径都自动收尾**（`fail()` 内部就调 `close`）；
     与 `MenuLifecycle`（收尾收敛点 + 看门狗）互补成双保险。
3. **探针改为 `MenuSession` 的首个消费者**（关键：不让新抽象变成"没人读的 API"）：`MenuProbeTask` 的
   打开/点击/关闭全部改走会话，新增 `OPENING` 阶段（等会话生效），断言里补 `session.failure()`/`session_left_open`。
   ⇒ 用户可用**同一个入口**（`alice:menu_probe`）复测"抽象化之后行为是否一致"。

**待定（下次讨论）**：① "**打开菜单算不算读取**"（是否入账本/预算）；② 槽位**语义表**的产出方式；
③ 把 `TransferTask` 的容器读写也切到菜单路线（届时 A11 授权与菜单会话如何衔接）。

### D-164 附注一：`MenuSession` **客户端验证通过**（含扰动与中断两个用例）

**用户实测（2026-09-13，三轮，均"符合预期"；其中一次 FAIL 是用户误用 `tp` 造成的，属预期外扰动）**

| 用例 | 日志证据 | 结论 |
|---|---|---|
| **正常流程** | `[Menu] opened … type=ChestMenu slots=63 ticks=1` → `click_pick slot=0 carried=3x铁锭` → `click_place slot=54 carried_after=空 clicksOk=true` → `[Menu] closed reason=probe_done` → `[MenuProbe] SUMMARY … verdict=PASS botHasIron=3 chestLeftIron=0` | ✅ `MenuSession` 打开/槽位查找/点击/关闭**全部走通**；`ticks=1` 说明服务端菜单**同 tick 即生效**（`FakeConnection` 不影响） |
| **扰动（误用 `tp`）** | `use_item_on result=CONSUME` → `[Menu] failed code=menu_open_timeout ticks=21` → `verdict=FAIL`、`terminal=FAILED` | ✅ **如实失败、不挂死**：会话超时后自动收尾；这正是"宁可承认失败，也不假装成功"的姿态 |
| **提前终止（`/alice stop`）** | `[Menu] opened …` → `[Menu] 收尾时仍开着容器菜单 ChestMenu（reason=clearTask:MenuProbeTask）⇒ 强制关闭`、`terminal=CANCELLED_BY_USER` | ✅ **上一轮的生命周期漏洞修复生效**（盖子不再一直开着） |

**顺带修正**：探针失败文案原先写死 `menu_open_failed`，现**透传会话真实码**
（`menu_open_timeout` = "发出右键但服务端没给菜单"；`menu_open_failed` = "右键本身没被接受"）——
下次扰动一眼可分辨。

**结论**：`L2 = 探针验证 + 组件化（MenuSession）+ 生命周期双保险` 三件都成立，可以进入**生产化**：
把真实业务（当前是容器传输）切到菜单路线，并沉淀"槽位语义表 v0"。

## D-165 L2 生产化：容器语义表 v0 + 路线开关 + 传输切到菜单路线 + 菜单打开入事件环

**用户裁定（2026-09-13）**：① 传输**切到菜单路线并保留开关**；② "**打开容器菜单**"记为
**读取事件**入事件环（**不占写入预算**）；③ 语义表 v0 **只做纯物流容器**。

**实现**
1. `action/ContainerSemantics`（**语义表 v0**）：方块 → `Info(slotCount, label)`。
   收录：箱子 27 / 陷阱箱 27 / 木桶 27 / 潜影盒（任意颜色）27 / 漏斗 5 / 发射器 9 / 投掷器 9。
   **表里没有的一律 `null` ⇒ 调用方必须如实失败**（`unsupported_container`），**不许猜槽位语义**
   —— 熔炉类虽然"能做菜单"，但 v0 没有输入/燃料/输出角色表 ⇒ 同样判为不支持（用户裁定先不做）。
2. `transfer/TransferRoutes`（**路线开关**）：`MENU`（默认）/ `CAPABILITY`；进程内存态，切换打一行
   "谁改的、从什么到什么"；命令 `/alice transfer route [menu|capability]`（不带参数显示当前）。
   ⇒ 用户可在游戏里**并排对比 A/B 两种观感与行为**，出问题一键回退。
3. `TransferTask` **菜单路线**（`sourceWriteViaMenu` / `destinationWriteViaMenu`）：**子状态机**
   （`NONE → OPENING → PICK → PLACE`，因为菜单生效与点击都跨 tick）：
   - 语义表校验 → **消耗 A11 容器写入预算** → `MenuSession.open(...)` → 等 `OPEN` →
     在容器槽位找目标物品 / 找空玩家槽（源腿）或找空容器槽（目标腿）→ `click(PICKUP)` 两次 →
     `close(...)` → **按背包/容器实际数量校验** → G5 记账（带 requester/reason）→ 下一相位；
   - 失败路径：**关菜单 + 如实记账**（`container_menu_failed` 并把会话的具体码写进证据）、
     `unsupported_container` / `bot_inventory_full` / `destination_full` / `source_insufficient`；
   - A 路线（capability 直写）**代码原样保留**，仅由开关选择 —— 便于对照与回退。
4. **P3 菜单打开入事件环**：`MenuSession` 在 `OPEN` 时 `DecisionEvents.record(bot, "MENU_OPEN", "info", …)`
   —— **只入环不通知决策层**（菜单打开是高频常规动作，通知会平白招 LLM 调用）；**不占写入预算**。

**覆盖**：`alice:transfer_check` 的 `end_to_end` 用例跑**真实 `TransferTask`** ⇒ 现在默认走**菜单路线**
（走位 → 触及 → 预算 → 开菜单 → 点击 → 校验），因此它同时是 L2 生产化的端到端验证；
4 个夹具仍直接调原语/接缝 ⇒ **两条路线都被覆盖**。

### D-165 附注一：`end_to_end` 首测 FAIL —— 一个夹具前提问题 + 一个**真可诊断性缺陷**

**实测**：`[Transfer] end_to_end status=FAILED ticks=366 moved=0 sourceEmpty=false terminal=`
且**日志里一条 `[Menu]` 都没有** ⇒ 任务**根本没走到写入阶段**；`verdict=FAIL`。

**两个原因，性质不同**
1. **夹具前提没自证（我的问题）**：`end_to_end` 直接用"bot 当前位置旁边"放箱子，而 bot 这次站在
   z≈130 的**任意地形**（不是伐木场地）⇒ L1 的站位搜索找不到合法站点 ⇒ 失败。
   **修法**：夹具**自己铺一小块平台**（6×6 地板 + 两层空气），箱子放固定相对位置，
   bot 传送回平台起点 ⇒ **与地形无关**（这正是"夹具前提必须自证"的第 N 次印证）。
2. **真缺陷：中止路径静默**！`TransferTask.suspend(...)` 原先**不打任何日志** ⇒
   `end_to_end` 失败时日志一片空白，只能靠猜。**修法**：`suspend(...)` 现在打印
   `[Transfer] suspend code=… location=… phase=… route=… bot=… src=… dest=…`；
   `failNotMovedMenu(...)` 与"端点周围找不到可站点"也各补一行。
   ⇒ 以后任何中止都有**可归因的第一现场**（这是本项目的硬要求：失败必须能归因）。

**已修并同步**（jar `f8b6d5ec…`）：夹具自建平台 + 中止路径留痕。

### D-165 附注二：`end_to_end` 再测仍 FAIL —— 夹具地形改由**数据包场景函数**生成（用户建议）

**新诊断立刻定位**（上一轮刚补的 `suspend` 留痕）：
```
[Transfer] suspend code=hard_path_failed location=NOT_MOVED phase=TO_SOURCE route=MENU
           bot=6,64,130 src=6,64,136 dest=9,64,136
```
⇒ 卡在**第一段走位**（连 2 格都走不到）⇒ 我在 Java 里 `setBlock` 手搓的平台**几何对不上**
（bot 站的位置、箱子位置、地板没有对齐）。

**用户建议（正确，且本来就是项目规矩）**："不能直接把 function 塞夹具里吗？这样生成平台不太好"。
AGENTS.md 明确要求"**需要特定地形时用数据包函数一键生成**"，夹具只该负责"传送 + 填内容 + 断言"。

**修法**
1. 新增场景函数 `alice_test:transfer_check_terrain`（`tools/test-scenes/alice_test/data/alice_test/functions/`）：
   **孤立长方体平台**（区域 `x40..60, y60..70, z392..420`；整体先 `fill … air` 含上下空气层 ⇒ 平台悬空、
   与周围地形不相连；地板 y=63 ⇒ 脚位 y=64）+ 两个箱子 + 玩家观察台；刻意避开其它场景
   （伐木 z204..222 / 垂直 z38..54 / 脚位格 z300 / 石台 z100）。
2. 新增 `task/TransferCourseAnchor`：**Java 与数据包函数对齐的固定坐标**
   （`BASE=(44,64,404)`、`SOURCE=(46,64,404)`、`DESTINATION=(46,64,407)`、`FIXTURE_BASE=(44,64,414)`）。
3. 夹具改为**只调场景函数 + 传送 + 填内容 + 断言**（删掉全部手搓 `setBlock` 循环）；
   并补**夹具前提断言**（场景没建好 ⇒ 直接报"场景函数未放置箱子"）。
4. 顺带修一处语义：站位候选**不再包含"容器正上方"** —— 那是 L0 遗留行为，且踩**不满一格高**的容器
   （箱子 0.875）会给内核 ASCEND 出难题；现在只取**同层 4 正邻 + 8 斜邻**。

**离线校验**（用仓库自己的工具，不靠猜）：`tools/check-scene-connectivity.py --fixture … --start 44 64 404`
⇒ **可达站位 609 格、封航线 0** ✓（场景自封航线这种历史坑，现在有机器可查的手段）。
**场景函数已同步到客户端数据包**（`saves/新的世界/datapacks/alice_test/...`），随重启加载生效。

### D-165 附注三：`end_to_end` 的**真根因** —— 嵌套任务不能在同一 tick 内同步循环推进

**新证据（上一轮补的 `suspend` 留痕 + 既有 `[PathRetry]`/`[R4 Session]` 日志）**
```
[PathRetry] planned attempt=0 status=REACHED movements=1 cost=1.00 from=44,64,404 to=45,64,404   ← 规划完全正确
[R4 Session] failed status=TIMEOUT code=SEGMENT_TIMEOUT index=0 actualFoot=44,64,404             ← bot 一格没动
```

**根因（我的夹具写法错）**：`end_to_end` 原先在**同一个服务器 tick 内**用
`while (status == RUNNING && ticks++ < 600) task.tick();` 同步推进真实 `TransferTask`。
但 **bot 的物理是在服务器 tick 之间发生的**：循环把"段计时"烧光（会话按调用次数计时），
真实时间却为零 ⇒ 段超时、`actualFoot` 一动不动。
`TransferFixture` 一直没暴露它，是因为那些用例只断言**错误码映射**（接缝注入），**不需要真的走路**。

**修法**：`TransferCheckTask` 改为**阶段化**：`FIXTURES`（4 个同步夹具）→
`END_TO_END`（**每真实 tick 推进子任务一次**，预算 300 tick）→ `REPORT`（SUMMARY）→ `DONE`。
断言与日志拆成 `prepareEndToEnd(...)`（建请求/填内容/建任务）与 `assertEndToEnd(...)`（按目标箱实际数量断言）。

**教训（值得写下来）**：**"嵌套驱动一个需要真实时间的任务"必须在真实 tick 上推进**；
"同一 tick 内 while 循环"只适用于**纯计算/接缝注入**型夹具（如错误码映射），
一旦涉及走位/物理，就必须跨 tick。这条与"夹具前提自证""断言自己也会说谎"同族。

### D-165 附注四：`end_to_end` 的**第二个真根因** —— 背包索引 ≠ 菜单槽位号（已修，待客户端复测）

**证据（本轮日志，故障点已精确定位）**
```
[Menu] closed reason=source_leg_done                          ← 源腿（箱子→bot）**成功**
[R4 Session] completed session=transfer-MOVE_TO_DESTINATION-0  ← 目标腿走位**成功**
[Menu] opened target=46,64,407 type=ChestMenu slots=63         ← 目标箱菜单**打开成功**
[Menu] closed reason=destination_leg_done                      ← 两次点击都"成功"
[Transfer] suspend code=unknown_discrepancy … phase=DESTINATION_WRITE   ← 但校验读到 0
[Transfer] end_to_end status=FAILED ticks=24 moved=0 sourceEmpty=true
```

**根因（我的 bug）**：目标腿用 `findBotSlotWith(...)` 拿到的是 **`Inventory` 背包索引（0..35）**，
却直接当作**菜单槽位号**去 `click`。原版物流菜单的布局是
`0..26 箱子 / 27..53 主背包 / 54..62 快捷栏` ⇒ 点在了**箱子里的空格**上，物品压根没动
⇒ 目标箱为 0 ⇒ `unknown_discrepancy`。
**源腿一直是对的**，因为它用的是 `findEmptyPlayerSlot()`（返回的本来就是菜单槽位号）。

**修法**
- `MenuSession.playerMenuSlotFor(int inventoryIndex)`：**背包索引 → 菜单槽位号**的转换
  （快捷栏 0..8 ⇒ `containerSlotCount + 27 + i`；主背包 9..35 ⇒ `containerSlotCount + (i - 9)`；
  越界/布局不符 ⇒ `-1` ⇒ 调用方**如实失败**，不许猜）；
- `TransferTask` 目标腿改用 `findBotInventoryIndexWith(...)` + 上述转换；转换失败时给
  `container_menu_failed`（而不是含糊的 `source_insufficient`）；
- 旧方法名 `findBotSlotWith` 改名并加**显式警告注释**（"这是背包索引，不是菜单槽位号"），
  防止再次混用。

**这条坑的意义**：它**印证了"槽位语义表"的必要性** —— 语义表不只编码"哪个槽是输入/输出"，
还必须编码**索引空间映射**（原版物流容器一致，模组菜单可能不同）。
**待客户端复测**（本轮修复未经验证；判据：`end_to_end=PASS` 且 `moved=3`）。

## D-166 基-7 K-3：`safeToCancel`（第一步 = **声明规则**，消费者下一步再接）

**Baritone 依据（照规矩先查对照实现，不自制）**
- `Movement.safeToCancel()` 默认 **true**（`pathing/movement/Movement.java:196-202`）；
- `MovementTraverse:353`：`state != RUNNING || canWalkOn(dest.below())`（正走向"需要垫脚/回填"的格子时不可取消）；
- `MovementFall:182`：`playerFeet == src || state != RUNNING`（**已跨出边缘 ⇒ 空中不可取消**）；
- `MovementAscend:246`：`state != RUNNING || ticksWithoutPlacement == 0`（**放下方块即已提交**）；
- `MovementDiagonal:65`：读玩家坐标做**几何悬空**判定；
- 消费者：`PathExecutor:287`（不安全时**不许**取消）、`PathExecutor:194`、`PathingBehavior:153/197`，
  以及 **`InventoryPauserProcess:53`：`safeToCancel && 站定 ≥2 tick` ⇒ 才允许停下来开背包**
  —— **这条正是 Alice L2 菜单路线需要的门**：打开容器菜单会让物品移动，绝不能在半空中做。

**Alice 侧第一步（本次，纯声明，行为不变）**
`MovementExecution` 新增 `default boolean safeToCancel()`（默认 true），8 个执行器按各自的**承诺点**覆写
（`pathing/core/*Execution.java`，共 9 个文件含接口）：

| Movement | Alice 规则 | Baritone 对应 |
|---|---|---|
| TRAVERSE / DIAGONAL | `phase != EXECUTING` 或 **目的地下方可站** | `MovementTraverse` / `MovementDiagonal`（几何判定用保守近似） |
| ASCEND / DESCEND | `phase != EXECUTING` 或 **已落地**（`bot.onGround()`） | `MovementAscend` 的"已提交"语义 |
| FALL | `phase != EXECUTING` 或 **尚未离开起点**（脚位仍 = `fromFoot`） | `MovementFall` 同款 |
| PILLAR / PLACE_STEP_AND_TRAVERSE | `phase != EXECUTING` 或 **尚未放置** | `MovementAscend` 的 `ticksWithoutPlacement` |
| DOWNWARD | `phase != EXECUTING` 或 **已落地**（脚下方块已被挖掉是承诺点） | （Alice 特有：Baritone 的 DOWNWARD 走 `MovementDownward`） |
| BREAK_* | 默认 true（破坏不产生位移承诺） | 默认 true |

**为什么这一步先只做声明**：用户正在客户端验证昨天的传输修复（jar `aeb3e3d9…`），
而 K-3 的**消费者**要改 `PathSession.cancel()` / `BotManager.stopTask` / 生存打断 /
`MenuSession.open` —— 那些会直接影响正在验证的行为。**先把规则落地（零行为变化），
等这次验证结果出来再接消费者**，避免把两件事混在一起查。

**下一步（K-3 第二步，待接）**
1. `PathSession.safeToCancel()`（暴露当前段的安全性）+ `PathSession.cancel()` 的**安全点延迟**
   （对齐 `PathExecutor:287`：不安全则不解取消；有界（如 20 tick）后强制 + 诚实记 `unsafe_cancel`）；
2. **生存打断**保持立即（安全优先）但**记录 `unsafe_cancel`**（拿到真实频率数据）；
3. **L2 门**：`MenuSession.open(...)` 要求"安全 + 站定 ≥2 tick"（`InventoryPauserProcess:53` 对齐）；
4. 自检：合成执行的规则断言（纯）+ 真实打断用例（可观测 `unsafe_cancel` 计数）。

### D-165 附注五：**L2 生产路线客户端验证通过**（`end_to_end` PASS）

```
[Transfer] end_to_end status=DONE ticks=24 moved=3 sourceEmpty=true
[Transfer] SUMMARY fixture=PASS end_to_end=PASS selection=PASS selector_events=PASS command_parse=PASS verdict=PASS
[Menu] opened target=46,64,404 type=ChestMenu slots=63 → closed reason=source_leg_done
[Menu] opened target=46,64,407 type=ChestMenu slots=63 → closed reason=destination_leg_done
[WriteBudget] SUMMARY scope=…#348:TransferCheckTask breaks=0/64 places=0/32 containers=2/32 refusedContainers=0
```
⇒ **B 路线（真实菜单协议）在生产路径上端到端成立**：
走位（新内核）→ 触及校验（L1）→ **A11 容器预算恰好吃 2 次**（一腿一次）→
**真 `openMenu`** → **菜单点击搬物品** → 按目标箱实际数量校验 → 干净关闭。
`TransferCheckTask` 的 5 个用例（4 同步夹具 + 端到端）全绿 ⇒ 传输模块 R1/R2/R3 + L1 + L2 一并收口。

### D-166 附注一：K-3 **第二步（接消费者）已实施** —— 安全点停止 + 生存打断计数 + L2 空中门

**1. 任务层透传**：`Task.safeToCancel()`（默认 true）+ `PathSession.safeToCancel()`（无执行 ⇒ 安全）
+ `PathRetryRunner.safeToCancel()`（无会话 ⇒ 安全）；**7 个驱动寻路的任务**透传
（`WalkToTask`/`TransferTask`/`FollowTask`/`PlaceTask`/`CollectDropsTask`/`RestoreScopeTask`/`ScaffoldLifecycleTask`）。
`Job.subTasks()` 只是**汇报结构**（`TaskNode`）⇒ 无法聚合 ⇒ 改用下面的"空中硬事实"覆盖 Job。

**2. 安全点停止**（`BotManager.stopTask` → `BotSession.requestStop` 语义）：
- 能安全停 ⇒ 立即停（原行为）；
- **不安全 ⇒ 延后到安全点**（对齐 Baritone `PathExecutor:287`），上限 `SAFE_STOP_DEFER_TICKS = 20`，
  超时**强制停并计数**（诚实上报，不无限等）；
- 判据 = `task.safeToCancel() && bot.onGround()` —— **`!onGround()` 是与任务类型无关的硬事实**，
  于是**Job 也自动获得"空中不取消"保护**（不必给每个 Job 写聚合）；
- 只有**两处**调用 `stopTask`（LLM 的 `stop_current`、`/alice region stop`）⇒ 都是用户路径，
  **不影响回归电池**（夹具不走这个入口）。
- 新增计数器：`deferred`（延后成功）/`forcedUnsafe`（超时强停）/`survivalUnsafe`（生存打断撞上不安全时刻），
  经 `BotSession.describeSafeStops()` 进 `bot_report`。

**3. 新增用户入口 `/alice stop-task`**：原先**没有**"取消当前任务"的命令
（`bot-control stop` 只停移动输入），K-3 也就没法被用户验证 ⇒ 补上；它回报"立即停/延后到安全点"与计数器。

**4. L2 门（`MenuSession.open`）**：对齐 Baritone `InventoryPauserProcess:53`（`safeToCancel && 站定 ≥2 tick`
才允许停下来开背包）—— **空中一律硬拒**（`MenuCodes.MENU_NOT_SETTLED`）；
"控制器仍有输入"先**告警 + 计数**（段结束时可能 1~2 tick 残留，数据不足不硬判，**不猜**）。

**5. 自检**：`capability_gate` 增 `safe_cancel_wiring`（非寻路任务默认 true + 无会话的 runner 报安全）。

**诚实的未完成项**：Job 的**细粒度**承诺点（如"LumberJob 内部正在 PILLAR 半途"）尚未逐 Job 聚合 ——
当前靠"空中硬事实"覆盖最主要的一类；等 `survivalUnsafe`/`forcedUnsafe` 有真实频率数据后再决定要不要细做。

### D-166 附注二：把 K-3 做成**确定性夹具**（用户实测"不好抓时机，过程有点乱"）

**用户反馈（对的批评）**：手动抢"跳跃瞬间"没法稳定命中延后路径 ⇒ 本轮实测只打出
`已显式停止任务 …（command）`（**都是"安全 ⇒ 立即停"**），延后路径**一次没被触发**。
**"靠人手抢时机的测试就是夹具设计失败"**（项目规矩：一次动作覆盖全部、可复现）。

**顺带确认（回归）**：`[Transfer] SUMMARY fixture=PASS end_to_end=PASS … verdict=PASS` ⇒
新的 **L2 空中门没有拦坏已验证的菜单路线** ✓。

**修法：`K3StopCheckTask`（确定性制造"不安全"）**
- **原理**：K-3 判据是 `task.safeToCancel() && bot.onGround()`，而 `!onGround()` 是**与任务类型无关的硬事实**
  ⇒ 夹具只要**把 bot 升空**，请求停止就**必然**落在"不安全"上（不靠手速）。
- **两用例**：
  - `DEFER`：升空 → 请求停止 → **放开吊空**（自然落地）⇒ 期望 `已到安全点，执行延后的停止（等待 N tick）`
    且 `bot_report` 的 `deferred` **+1**；夹具在请求后**仍被 tick** 即是"延后生效"的直接证据；
  - `FORCED`：升空 → 请求停止 → **一直吊在空中**（每 5 tick 拉回原位）⇒ 超过 20 tick 后
    `任务在**不安全时刻被强制停止**` 且 `forcedUnsafe` **+1**（验"不无限等"）。
- 夹具自带前提断言（请求前必须 `!onGround()`，否则报 `fixture_not_airborne`）与窗口上限
  （`OBSERVE_AFTER_REQUEST=60`：DEFER 落地后仍不停 / FORCED 超时不强停 ⇒ 如实判失败）。
- **入口**：`alice:k3_stop_check`（零参数右键，跑 DEFER）+ 电池两步 `k3_stop_defer` / `k3_stop_forced`
  （电池 23 → **25** 项；用户入口只跑 DEFER，FORCED 用例走电池 —— 刻意避免再加一个道具）。

### D-166 附注三：K-3 **DEFER 用例客户端验证通过**（确定性夹具）

```
[K3] mode=DEFER 升空到 24,70,44（夹具前提：空中 ⇒ 停止必然不安全）
[alice] 停止请求**延后到安全点**：task=K3StopCheckTask reason=k3_defer（空中或已提交位移）
[K3] mode=DEFER 已请求停止 task=K3StopCheckTask accepted=true airborne=true
[K3] DEFER：放开吊空，等它落地（落点即安全点）
[K3] mode=DEFER 请求后仍被 tick（tick=15）⇒ 延后生效（等待安全点）
[alice] 已到安全点，执行延后的停止（等待 12 tick）
[alice] 已显式停止任务 K3StopCheckTask（k3_defer:safe_point）残余临时方块=0
task_execution_terminal … durationTicks=25 terminal=CANCELLED_BY_USER
bot_report：安全点取消：deferred=1 forcedUnsafe=0 survivalUnsafe=0；菜单门 blockedAirborne=0 openWhileMoving=0
```
⇒ **"不安全时不取消"真的生效**：空中 → 延后 → 请求后仍被 tick（延后生效的直接证据）→ 落地（12 tick）→
在安全点执行停止（`:safe_point` 后缀）。**计数也对上**（`deferred=1`）。
同时 **`blockedAirborne=0` / `openWhileMoving=0`** ⇒ L2 门没被触发，说明传输路径确实是
"落地 + 静止"后才开菜单（设计意图成立，不是靠门兜住）。

**未验证**：`FORCED`（吊在空中直到 20 tick 上限被强停）—— 它是电池步 `k3_stop_forced`，
会在下一次跑电池时一并验证（不额外加道具）。

### D-167：K-4 **谓词统一** —— "可站"只有一处定义 + 目标准入**先测量不硬拒**

#### 侦察：同一件事被写了 5 遍，而目标准入一遍都没查（K-4 结论）

执行期与规划期对"bot 能不能站在这一格"有**三套**互不相干的说法：

| 位置 | 谓词 | 性质 |
|---|---|---|
| 运行期完成判定 `MovementHelper.isSettledAtFootPos(…, 0.3)` | 脚位格正确 + **已落地** + 水平距中心 ≤0.3 | **连续**、含运行期事实 |
| 规划期候选/站位 | `canWalkOn(to) && canWalkThrough(to) && canWalkThrough(to.above())` | **离散**、世界事实 |
| 目标准入 `AStarMovementSearch:99` | `goal.isInGoal(currentFoot)` = 格坐标相等 | 只看格，**什么都不查** |

⇒ "规划期宣布 REACHED、最终段 EXACT 却满足不了"在结构上可能（K-4 的原始怀疑）。
K-4 侦察把复制点查清：上述 3 子句在 `SurfaceMovementProvider` 复制 **3** 处、
`MovementHelper.canTraverse` **1** 处、`FallExecution.canFall` **1** 处、
`StandingPointSelector.isStandable` **1** 处（共 6 处），而**目标准入 0 处**。

#### 修法一：唯一定义（纯重构，子句集合逐字相同 ⇒ 行为不变）

新增 `MovementHelper.canStandCentered(level, foot)`，上述 **6 处全部委托**它；
方法注释写清它与 EXACT 的关系：**必要不充分**——
"已落地""带没带到位"是运行期事实，规划期**只能**证明"支撑存在且非源流体 + 脚位/头位无碰撞"，
而"以格中心摆放 0.6×1.8 玩家盒必然放得下"正是后两条的等价物。

**两处故意更宽、不并入**（并已在代码处写明理由，避免以后被"统一"掉）：
- `SurfaceMovementProvider` 的 **BREAK_AND_ENTER**：本移动要破坏目的地躯干+头位，
  规划期查"可通行"必然为假 ⇒ **自相矛盾**（实测：查了就一条边都生成不出来）；它只证明"破坏之后可站"；
- 同处的 **DOWNWARD**：要破坏的正是 `to` 本身，加"可通行"等于禁用该移动。
- 运行期侧 `PathSession` 的合法位置集检查本来就对写入类移动**显式分支**（支撑仍在 + 仍可破坏），
  这是运行期该有的形态，保持独立。

#### 修法二：目标准入**只测量、不改行为**（不硬拒的三条理由）

1. **"起点即目标"是合法的"已经在那儿"**，硬拒会把合法调用变成失败；
2. **破坏类请求的目标格本来就证明不了可站**：挖掘 `ENTER_TARGET` 兜底模式的 goal 就是**矿块自身**，
   只能由 BREAK_AND_ENTER 到达 ⇒ 硬拒会打断一条已实现的挖掘策略（这是"不猜"的直接后果）；
3. `SEARCH_LIMIT ≠ UNREACHABLE` 的精神同理：**"目标自身不满足完成契约"是第三种事实**，
   既不是"到不了"也不是"没算完"，要引新状态码就得先把频率测出来。

**遥测码（进程累计，进 `alice:bot_report` 的"目标准入（K-4 累计）"行）**：

| 码 | 含义 | 性质 |
|---|---|---|
| `goal_not_standable` | 纯通行边走进的目标格不可站 ⇒ REACHED 与 provider 自身保证**直接冲突** | **真异常** |
| `final_segment_target_not_standable` | 最终段（EXACT）目标格不可站且非写入类 | **真异常** |
| `goal_not_standable_start` | 起点即目标且起点不可站（bot 泡在流体/卡在墙里） | 观察项 |
| `goal_post_write_not_standable` / `final_segment_target_post_write` | 写入类移动的落点（设计如此） | 信息 |

#### 修法三：夹具**自断言**（断言不许说谎）

回归电池构造时取 `PathingStats.totalsSnapshot()` 作基线，收尾按**本次增量**断言
两个"真异常"码必须为 0，并把结论写进 SUMMARY：`K4=OK(goal_not_standable=0 …)` /
`K4=VIOLATION(…)`；VIOLATION 会使电池整体判**FAIL**（不静默）。用增量而非绝对值，
是因为进程累计会被电池之前的服务端活动污染。

**状态**：`WINDOWS_CLIENT`（见 D-167 附注一：完整电池 `K4=OK(真异常 0 / 写入类例外 88)`，电池现 **23 项** —— K-3 两步已按 D-169 移出电池）。
**未完成（有数据后再决定）**：若实测计数为 0，则 K-4 视为"缝存在但实战不咬"，
**不**引入 `GOAL_NOT_STANDABLE` 状态码；若不为 0，按现场数据把纯通行类改成硬拒 + 新状态码。
**收口义务（两条路都要做，避免临时探针留在生产代码里）**：0 ⇒ 删掉 `[K4]` 告警行，
只留电池自断言；≠0 ⇒ 换硬拒 + 状态码，告警行由否决路径取代。
夹具侧只**复用**内核谓词（`canStandCentered`）而不另写判据，符合
`alice-scene-based-testing` §7"夹具与内核判定不得分叉"。

### D-168：夹具断言**不得依赖世界历史**（用户实测"不清理掉落物就 FAIL"的根因与修法）

#### 现场事实（客户端日志，2026-09-13）

| 运行 | `exec_direct` | `exec_blocked` | 说明 |
|---|---|---|---|
| run1（世界里有历史残留掉落物） | `FAIL … collected=1/1 inventoryDelta=1 **dropsLeft=1**` | `FAIL … collected=1/1+ inventoryDelta=2 **dropsLeft=1**` | 本用例自己的掉落物**已收齐**，唯一失败项是"盒内还有 1 件掉落物" |
| run2（用户手动清掉残留后） | `PASS … dropsLeft=0` | `PASS … dropsLeft=0` | 同样代码、同样用例 ⇒ 差异只在世界历史 |

**旁证（同一份日志）**：下一个用例 `scope_reopen_keeps_drops` 打出
`dropsInWorld=2 liveDropsBeforeReopen=1 liveDropsAfterReopen=1` —— 同一 ±4 盒内确实**有 2 件**掉落物，
而"我方掉落物"只有 1 件。⇒ 那 1 件是**外来残留**（上一轮测试/玩家自己挖的），不是 bot 留下的。

#### 根因：测量方式本身不合法（不是内核 bug、不是 bot 行为 bug）

`MineRegressionTask` 的 `dropsLeft` = `getEntitiesOfClass(ItemEntity, AABB(target).inflate(6)).size()`，
即"**该范围内所有掉落物实体**"，且场景函数 `mine_course_terrain` 只清方块、**不清实体**
（对比：`scaffold_course_terrain` / `contrast_*` / `reset` 都有 `kill @e[type=item,…]`）。
⇒ 判据 = `世界历史` × `本用例行为`，而它只该由后者决定。**违反"夹具可复现/自断言前提"**。

#### 修法（两层，都是夹具/场景侧，内核零改动）

1. **夹具取基线**（主修）：`prepare()` 跑完场景函数后，把测量盒内掉落物的 **UUID** 记进
   `dropsAtCaseStart`；用例结束时 `dropsLeft` 只数**不在基线里**的（= 本用例新增），
   基线内的报 `foreignDrops=N` 且**明确标注"不计入"**。基线非空时打一行 warn
   （提示世界有残留；正常情况场景函数已清空 ⇒ 基线为空）。基线盒与结束盒**必须是同一个**
   （同一 `dropsBox(target)`），否则基线无效。
2. **场景清实体**（前提）：`mine_course_terrain` / `floating_course_terrain` /
   `chain_mine_course_terrain` 各补一行 `kill @e[type=minecraft:item,x=…,y=…,z=…,dx=…,dy=…,dz=…]`，
   覆盖各自孤立盒（与既有场景同约定）。

> 为什么两层都要：场景清理保证"起点干净"（前提），基线保证"即使有残留也不会假失败"（判据）。
> **只做场景清理是不够的** —— 夹具的测量盒（target ±6）比场景盒更大（如 z140 ±6 → 到 z146，场景只到 z142），
> 盒外的静止残留仍会被数进来。

#### 同类风险登记（未扩大修改）

`ScaffoldLifecycleTask` 也断言 `itemsOnGround == 0`（整场景盒）—— 它的场景
`scaffold_course_terrain` 已有 `kill`，当前不会假失败，但**同样依赖"场景已清"这一前提**。
**规则（新）**：凡夹具断言涉及**世界掉落物**或**背包净增量**，必须 ①场景函数清该场景盒实体，
或 ②取基线做增量；二者至少一条，写进该夹具注释。

#### 顺带确认（K-4 遥测在真实运行中的表现，`SERVER_TESTED`）

`bot_report` 新行渲染正常：`目标准入（K-4 累计）：goal_post_write_not_standable=89 final_segment_target_post_write=51`
—— **两类"真异常"码一次都没出现**（`[K4]` 告警 0 行），只有**设计如此**的写入类例外被计数，
印证 D-167 的分档是对的：破坏类移动的目标格确实经常"破坏之后才可站"（89 次）。
另：`安全点取消：deferred=1` —— 用户用命令中止那一轮电池时 bot 正在空中 ⇒ 走了 **K-3 延后停止**路径
（真实世界用例，非夹具造）。

> **更正（见 D-169）**：本条的"run2 关客户端"是**误读** —— run2/run3 的电池都是被
> **K-3 夹具自己停掉的**（`停止请求延后到安全点：task=RegressionBatteryTask`），与用户操作无关；
> 旧日志已被重启覆盖，故当时只能推断，现在有第三轮的硬证据。

### D-169：**夹具不得停掉它的父任务** —— 电池里的 `k3_stop_defer` 让电池自杀（两轮无 SUMMARY 的真因）

#### 现场事实（客户端日志，2026-09-13 第三轮）

```
10:55:34.554 [Regression] step=k3_stop_defer (12/25) …
10:55:35.155 [alice] 停止请求**延后到安全点**：task=**RegressionBatteryTask** reason=k3_defer
10:55:35.806 [alice] 已到安全点，执行延后的停止（等待 12 tick）
10:55:35.807 [alice] 已显式停止任务 **RegressionBatteryTask**（k3_defer:safe_point）
10:55:35.807 task_execution_terminal kind=RegressionBatteryTask … terminal=CANCELLED_BY_USER code=cancelled:…
```
⇒ 电池在第 12/25 步**把自己停了**，所以**永远打不出 SUMMARY**；之后 bot 空转，
`[Pickup] blocked … oak_sapling` 刷了 2 分钟直到玩家退出（10:57:47）。

**前一版判断是错的**（D-168 里我写"run2 是用户关客户端"）：run2 同样在第 12 步开始后 8 秒内失去所有日志，
而死法完全一致；只是 `latest.log`/`debug.log` 在客户端重启时被**覆盖**，无法再直接取证 ⇒ 记为**高度可能、未取证**。

#### 根因（不是 K-3 实现错，是**用例放错了层级**）

K-3 的语义就是"**顶层任务**在不安全时刻不被硬停，延后到安全点再停"；
而 `BotManager.stopTask` 停的是 `session.task`（**顶层**任务）。夹具作为电池的一个**步**被 tick 时，
`session.task` 是**电池**，不是夹具 ⇒ 夹具请求停止 = 请父任务自杀。
`alice:k3_stop_check` 独立运行时没问题（那时它自己就是顶层任务，D-166 附注三已验证）。

#### 修法（三处，最小改动）

1. **K-3 退出电池**：删掉 `k3_stop_defer` / `k3_stop_forced` 两步（电池 25 → **23** 项，
   改 `RegressionBatteryTask` 注释 + 物品文案 + 文档计数），并在 `buildSteps` 处写明**为什么不能进**；
2. **夹具自断言前提**（`K3StopCheckTask.setup()`）：若 `session.currentTask() != this` ⇒
   立即 `fixture_not_top_level` 失败，**绝不调用 `stopTask`**。这条把"静默杀死父任务"变成"诚实失败"，
   也符合"夹具必须自断言前提"的既有规矩（与 `fixture_not_airborne` 同级）；
3. **两种模式都保留手工入口（零参数）**：`alice:k3_stop_check` **右键 = DEFER**，**Shift+右键 = FORCED**
   （原 `k3_stop_forced` 只在电池里、从没被验证过 —— 现在有了独立入口）。

> 一般化的规矩（写入本项目规则）：**夹具/夹具步骤不得对父任务产生副作用**。
> 需要"父任务被停"这类观察时，必须做成**顶层入口**；需要嵌进串联时，只能断言"决策/谓词"，
> 且必须显式记录"本步没有真的执行副作用"。

**状态**：`IMPLEMENTED` + `COMPILES`。**未验证**：客户端（DEFER 入口回归 + FORCED 新入口 + 23 项电池跑完出 SUMMARY）。

### D-170：**资源缺陷只有客户端日志能看出来** —— 3 个 0 字节模型 + 1 个死贴图引用（新自检脚本）

#### 现场事实（客户端 `latest.log`，2026-09-13）

```
[Worker-Main-7/ERROR] Failed to load model alice:models/item/k3_stop_check.json
    com.google.gson.JsonParseException: JSON data was null or empty
[Worker-Main-7/ERROR] Failed to load model alice:models/item/menu_probe.json    （同上）
[Worker-Main-7/ERROR] Failed to load model alice:models/item/transfer_check.json（同上）
[Worker-Main-7/WARN] Unable to load model: 'alice:k3_stop_check#inventory' …
```
⇒ `k3_stop_check` / `menu_probe` / `transfer_check` 三个模型 JSON 在源里就是 **0 字节**
（`find src/main/resources -type f -size 0` 恰好命中这三个；上一轮脚本写文件时留下的空文件），
物品在客户端是**缺失模型**（紫黑块/看不见）。**这大概率就是这轮 K-3 自检没被测的原因**（看不见道具）。

另查出第 4 个缺陷：`partial_search_check.json` 指向**不存在的**贴图 `alice:item/check_pathing_decision`
（实际只有 `check_pathing` / `check_decision`）⇒ 模型能加载、只有 WARN，是**紫黑贴图**，更容易漏。

**为什么编译/打包/同步全 PASS 也挡不住**：这是**纯资源**缺陷 —— Java 编译不看 JSON，
`./gradlew build` 不校验模型引用，jar 里存的就是 0 字节。**唯一的报警面是客户端模型加载日志。**

#### 修法

1. 三个空模型按既有模板补全（`k3_stop_check`→`check_decision`、`menu_probe`→`interface_scanner`、
   `transfer_check`→`check_fixture`）；`partial_search_check` 的死引用改为 `check_pathing`。
2. **新增 `tools/check-item-models.sh`**：扫全部 `models/**/*.json`，抓三类问题 ——
   ①空文件 ②JSON 语法坏 ③引用的贴图文件不存在；输出 `CHECK_ITEM_MODELS … RESULT PASS|FAIL`（退出码 0/1）。
   **脚本自测过**（临时造一个空模型 + 一个死引用 ⇒ 两个都被抓出、退出码 1；删除探针后恢复 PASS）。
3. 之后**每次改资源后、build 前**跑一遍；`build.gradle` 未接钩子（避免拖慢日常编译），
   但已写入 `AI_DEVELOPMENT_PLAYBOOK.md` 的构建前检查清单。

**状态**：`IMPLEMENTED` + `COMPILES` + 脚本自测通过；**未验证**：客户端重启后这 4 个物品贴图是否正常显示
（需要用户看背包/快捷栏一眼）。

### D-167 附注一：K-4 **收口完成**（数据为 0 ⇒ 删临时告警，留计数 + 自断言）

**实测数据（2026-09-13 第四轮，完整电池）**：
```
[Regression] SUMMARY … pathing=PASS K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=88) (23/23) ticks=3507 → PASS
```
按 D-167 定的收口义务（0 ⇒ 删告警行），已删除 `AStarMovementSearch` 与 `PathSession` 里两处临时
`[K4] …` 告警（`[K4]` 在代码里现为 0 处），**保留**：单次规划摘要计数（`[PathingStats]`）、
累计计数（`bot_report` 的"目标准入（K-4 累计）"行）、电池 SUMMARY 的 `K4=` 自断言（真异常会让电池 FAIL）。

**K-4 最终结论**：规划期"可站"谓词原先 6 处各写一遍、目标准入一处不查，**缝是真的**；
但纯通行类目标准入矛盾在**完整电池 + 两轮部分电池里一次都没发生**（真异常 0，`[K4]` 告警 0），
只有**设计允许**的写入类落点例外被计数（88 次）⇒ **不引入 `GOAL_NOT_STANDABLE` 硬拒**，
改为"谓词唯一定义 + 永久自断言"的形态收尾。

**同轮其他确认**：23 项电池 **全 PASS**（`ticks=3507`，含 `mine_regression` 11/11 ⇒ D-168 掉落物修复生效）；
无 `=FAIL` / `=TIMEOUT`；无 `[K4]` 告警。

### D-171：K-2 第三批 —— **删除 legacy 双内核**（19 个文件，先证零活引用再删）

**背景**：批次 2 已把生产/开发路径切到新内核（`TransferTask` 行走 + `/alice path` 诊断），
但 `pathing/`（顶层，非 `core`）下还留着整套旧实验内核。第三批 = **删干净**。

**删除前的证据（不是"看着像死的"）**：按**路径**做的引用分析（简单名会被同名类污染，已修正），
逐类区分"活引用 / 只出现在注释和 import / 无外部引用"：

| 判定 | 类 | 依据 |
|---|---|---|
| **删**（19 个） | `AStarPathfinder`、`AStarPathPlanner`、`PathPlanner`、`PathPlanners`、`LocalPathPlanner`、`HybridPathPlanner`、`SurfacePathfinder`、`OpenSet`、`PathNode`、`Goal`、`MovementPlan`、`MovementPlanCompiler`、`MovementPathExecutor`、`MovementMode`、`MovementType`(顶层)、`PathExecutor`、`TunnelPlanner`、`TunnelObstaclePolicy`、`TunnelPlan` | 外部**活引用 = 0**（`PathExecutor` 13 处、`SurfacePathfinder` 5 处、`PathNode`/`AStarPathfinder` 各若干，全是注释或"原先用 X"的历史说明）；无通配 import、无反射、无资源引用 |
| **留**（2 个） | `MovementHelper`（303 处活引用）、`FootCellRuleCheck`（`PathingRegressionTask` 用） | 新内核与夹具真的在用 |

**顺带清掉的耦合**：
- `MovementHelper.cost(legacy MovementType)` —— 唯一调用者是 `AStarPathfinder:307`，随之删除；
- `BotCommand` 的 `import …SurfacePathfinder`（早已不用，纯残留 import）；
- 顶层 `MovementType`（4 值旧枚举）随之消失 ⇒ 与新内核 `core.MovementType`（10 值）**不再并存**
  （这正是"两套枚举同名"这类混乱的根源）。

**刻意不动的**：`FollowTask` / `WalkToTask` / `TransferTask` / `MiningPlan` / `BotCommand` 里
"原先这里是 legacy X → 现用新内核 Y"的**历史注释**保留（它们解释了迁移与差异登记的来龙去脉）。

**验证**：`compileJava` + `build` PASS（删了 19 个文件后无编译错 ⇒ 与"零活引用"结论一致）；
资源自检 `CHECK_ITEM_MODELS_RESULT PASS`。

### D-172：K-5 —— `POSTCONDITION_FAILED` **不删值，改"给它生产者"**（死状态/观测盲区）

**问题（审计原话）**：`PathSessionStatus` 里 `BLOCKED`（D-047 已复活）与 `POSTCONDITION_FAILED`
"声明但 `mapFailure` 从不产出" ⇒ **观测盲区**：这个状态永远不会出现，写它的代码等于没有。

**决定：不删**。理由：架构文档 §5.3 的 `ExecutionStatus` **契约里就有它**；
而且执行器里真的存在"**到了但完成契约没成立**"这一类失败，只是被 `mapFailure` 的字符串匹配
**并吞**了：`ASCEND_SETTLING_TIMEOUT`（稳定等待超时，含 "TIMEOUT" ⇒ 被吞成 `TIMEOUT`）、
`DESCEND_OVERSHOT_BELOW_TARGET`（冲过目标 ⇒ 被吞成 `MOVEMENT_FAILED`）。

**修法**：
1. 归属规则收敛成**唯一定义** `PathSessionStatus.classify(code)`（纯函数、有序表；
   `set`/`overshot`/`postcondition` 规则**必须排在 `TIMEOUT` 之前**，否则又被吞）；
   `PathSession.mapFailure` 改为委托它；
2. 语义登记：`POSTCONDITION_FAILED` = "站位/落点不对"（重试同一目标往往有效），
   与 `TIMEOUT`（没算完/没走到）、`MOVEMENT_FAILED`（走不动）区分开 —— 对 Job/决策层的重试策略有意义；
3. **自检断言"不得有死值"**（`CapabilityGateCheckTask` 新增用例 `session_status_no_dead_value`）：
   ①分类表逐条断言（含上面的顺序陷阱）；②覆盖率断言 —— 除 `RUNNING`/`COMPLETED`
   （会话初态/终态，不由失败码产生）外，**每个枚举值都必须至少被一条真实失败码样本命中**；
   以后谁再添一个死值，这条会直接变红。

**诚实边界**：这两类失败码在迄今的客户端日志里**一次都没出现过**（今日全部归档 + latest 全文 grep = 0），
所以本项是"把已存在但被吞掉的语义接出来"，**不是**"实测有需求"。等真出现时，
`bot_report`/终态会显示 `POSTCONDITION_FAILED` 而不是含糊的 `TIMEOUT`。

**状态**：`IMPLEMENTED` + `COMPILES`；**未验证**：客户端（电池里 `capability_gate` 步会跑新用例）。

### D-173：K-2 收尾补漏（`pathing/movement/` 14 文件 + 一个 `.backup` 残留）

D-171 只分析了 `pathing/*.java`（顶层），**漏了子包**。补做的**按路径**分析（同名类会污染简单名统计，
所以判据是"外部文件是否 `import com.dddgn.alice.pathing.movement.X` 或用完全限定名"）结论：

- `pathing/movement/`（14 文件：`Movement`/`MovementProvider`/`MovementRegistry`/`MovementConstraints`/
  `MovementHelper`/`BasicMovement`/`WalkMovement(Provider)`/`DescendMovement(Provider)`/`PillarMovement(Provider)`/
  `BreakAndWalkMovement(Provider)`）—— **外部真引用 = 0**（只有包内互引；`Movement`/`MovementHelper`/
  `MovementProvider` 那些"命中"全是同名类误报，如 `core.Movement`、`pathing.MovementHelper`）⇒ **整包删除**。
- `pathing/risk/RiskSwitches` —— **活引用**（`DescendExecutionFactory` / `SurfaceMovementProvider` / `BotCommand`）⇒ 保留。
- `pathing/PathExecutor.java.backup`（4.6 KB，09-02 的脚本残留）：`.gitignore` 里有 `*.backup` ⇒ 它既不被编译、
  也不出现在 `git status`，**静默躺在源码树里**。已删。**教训**：`.gitignore` 忽略的残留不会自己冒头，
  收尾时要用 `find src -name '*.backup' -o -name '*.orig' -o -name '*.rej'` 扫一遍。

删除后 `build` PASS。**未验证**：客户端电池（与 D-171 同一次回归）。

### D-174：`[R4 Session] segment_stall` —— 补上"段卡死"的日志盲区（transfer `end_to_end` 间歇失败）

**现场（2026-09-13 第四次电池）**：`transfer=FAIL reason=end_to_end`，而且**同样的计划**
（`TRAVERSE 44,64,404 → 45,64,404`，1 格、cost 1.0）在 10:09 与 11:06 两次运行里 **5 tick 走完**，
这次却**一格没动**、`actualFoot=44,64,404` 磨满 120 tick 段预算后 `SEGMENT_TIMEOUT`：

```
[R4 Session] segment_start session=transfer-MOVE_TO_SOURCE-0 index=0/1 type=TRAVERSE from=44, 64, 404 to=45, 64, 404 tolerance=EXACT
（6 秒内**一行日志都没有**）
[R4 Session] failed … status=TIMEOUT code=SEGMENT_TIMEOUT index=0 actualFoot=44, 64, 404
[Goal] snapshot … "pos":"44, 64, 404","health":20.0,"onGround":true,"inLiquid":false,"hazard":"NONE"
```
且同一次运行里**后面的 `pickup_gate`/`event_thresholds`/`pathing` 步都 PASS**（bot 能正常走）⇒ 是**局部、瞬时**的卡死。

**已排除（有日志依据）**：夹具用例 `survival_interrupt_death_removal`（纯账本，不动 bot）；
bot 被移除/重生成（全程只有 11:19:28 一次"假人已生成"）；菜单未开（`[Menu` 0 行）；
`stopMovement` 未被反复调用（只在失败那一刻出现一次）；执行器每 tick 都设了 `setForward(1.0F)`
（`driveTowardTarget` 无条件写）。

**已补的诊断（永久、只在失败终态打）**：`[R4 Session] segment_stall …`，
一次性给出区分"输入没生效 / 被谁按住 / 目的地被堵 / 实体没在 tick"所需的客观量：
控制器输入串、真实坐标与速度、`onGround`、目的地/头位/支撑方块、段与总耗时。
（该项目已有 `BotPlayer` 的 200 tick `[PhysicsProbe]`，但它的入口是**遥控器右键**，
而遥控器本身会把玩家输入持续写给 bot ⇒ **不能**用它测这个场景，故新增失败即打的诊断。）

**仍未定论（下次复现时用上面的新日志判定）**，当前最强的两个候选：
1. **遥控输入干扰**（`BotInputPacket`:67-68 每 tick 把**玩家**输入写给 bot；客户端 `ClientInputHandler`
   在"主手拿着带 `BotUUID` 标签的遥控器"时**每 tick** 发包）⇒ 任务设的 `forward=1` 在实体 tick 前被改回 0，
   表现为"一格没动、无日志"。**这是真实的设计洞**：任务驱动与手动遥控**没有互斥**。
2. bot 实体在该时段没有 tick（但后续步骤能走，需要"瞬时未 tick"的解释）。

#### D-174 附注一：遥控器假设**被用户否证**，改为"链路计数"诊断（决定性）

**用户事实**：**没有用过遥控器** ⇒ `BotInputPacket`（客户端每 tick 把玩家输入写给 bot）这条候选排除
（另外本轮日志里也确实没有 `started controlling`，主手一直是电池物品）。

于是把诊断做成"**能证明链路断在哪一环**"：`BotPlayer` 增两个进程内单调计数 ——
`entityTickCount`（`tick()` 第一行 `++`）与 `travelInvocationCount`（`travel()` 里 `++`），
`PathSession` 在**段起点**取基线，失败时随 `segment_stall` 一起打出**段内增量**。

**判读表（下次复现照这张表读）**：

| entityTicksInSegment | travelCallsInSegment | input | 结论 |
|---|---|---|---|
| 0 | 0 | 任意 | **实体根本没被 tick** ⇒ 查假人 tick 驱动（`FakeConnection`/`PlayerList` 那条链） |
| ≈120 | 0 | forward=1.00 | tick 了但 `travel()` 没进 ⇒ 查 `aiStep`/`travel` 入口检查 |
| ≈120~240 | ≈120~240 | forward=1.00 | 物理跑了、输入也对却没位移 ⇒ **碰撞/被挡住**（查目的地与包围盒） |
| 任意 | 任意 | forward=**0.00** | 有别的写者每 tick 清零 ⇒ 找输入写者（全仓只有执行器/网络包/两个夹具/调试命令写它） |

这些都是**失败终态**才输出的（不是每 tick 探针），符合"只留终态日志"的规矩。

**同时登记的潜在设计洞（本次不是病因，但真实存在）**：任务驱动与**手动遥控**之间没有互斥 ——
`BotInputPacket` 会把玩家输入直接写给 bot 控制器，若有活动任务，任务设的前进会被静默改回 0。
语义（拒绝 / 暂停任务 / 抢占用并如实上报）需要用户拍板，**未改**。

#### D-174 附注二：用户实测信息的两条结论（其一否证、其一开新账）

**① 遥控器候选否证**（用户：没用过遥控器）⇒ 输入干扰不是病因。
**② 独立跑 `alice:transfer_check` 约 15 次全 PASS** ⇒ 该间歇失败**只在电池语境**出现过（10:09/11:06 PASS，11:21 FAIL）。

**电池语境已排除的并发驱动**（有日志依据）：
- 决策层**在电池期间是挂起的**：`[Goal] trigger_skipped reason=suspended trigger=terminal:RegressionBatteryTask`；
  全程只有一条 `[Goal] execute action=maintain_tool kind=PICKAXE ok=false`（在 transfer 步之后），
  **没有任何 job 被启动**；
- 卡死窗口（11:21:35–11:21:45）**没有任何 Job/任务创建/写世界日志**；
  `region_maintain` 的常驻 Job 在 11:21:34.859 已 `result=DONE reason=quota_met` 收尾；
- 也没有 `controller_stop_movement`（该方法**每次调用都会打日志**，见 `BotController:178`）
  ⇒ 期间**没有**任何 `stopMovement()` 把输入清掉。

⇒ 只剩"实体没 tick / `travel()` 没进 / 物理跑了却没位移"三类，正好由 D-174 附注一的计数区分。

**③ 用户观察到的"被布置砍树任务 + 开始搭石头"是另一件事，已定位（新账）**：
12:00 会话里，一次 `alice:transfer_check` 结束 → 决策层按 `trigger=terminal:TransferCheckTask` 触发 →
LLM 选了候选菜单里唯一的 `region:saved`（伐木区 x17..37 z203..231）⇒ 起了一个 `RegionLumberJob`。
随后用户**又跑 transfer_check**，夹具把 bot **传送到转移场景（z≈404）**，而那个常驻 Job **没意识到自己被挪走了**：
它在 12:00:03–09 于 **45,64,406 一路往上搭了 12 格圆石**（`by=lumber:gain:attempt0:STEP_PLACEMENT`，
目标树却在 **198 格外** `tree@33,64,208 d=198.5`），12:00:14 起又 `[Restore]` 自上而下拆回（账本闭环 ✓ 无残留）。

**登记为独立缺陷（J 级，未修）**：**常驻 Job 不感知"bot 被传送离开作业范围"** ——
它应当先核对"我在不在自己的作业范围内"（或重规划回到区域），而不是就地开搭。
触发条件正是本项目最常用的操作：**跑测试夹具时后台还挂着常驻 Job**（用户已实测遇到）。

### D-175：**服务端崩溃修复** —— 任务终态后再被 tick 不得崩（`MineTask` 终态闩锁 + 夹具隔离）

#### 崩溃现场（客户端 `crash-reports/crash-2026-09-13_12.07.23-server.txt`）

```
java.lang.NullPointerException: Cannot invoke "RestoreScopeTask.tick()" because "this.restoreTask" is null
  at MineTask.tickRestore(MineTask.java:402) ← MineTask.tick(318) ← MineRegressionTask.tick(248)
  ← RegressionBatteryTask.tick(267) ← BotSession.tick ← onServerTick ← …（服务端 tick 循环被打死）
```
日志序列（同一轮电池）：
```
12:07:23.101 case=scope_reopen_keeps_drops kind=SCOPE_REOPEN
12:07:23.447 [MineTask] restore_start target=23, 64, 140 pending=1
12:07:23.644 [MineTask] restore_end   target=23, 64, 140 status=FAILED restored=0 remaining=1
12:07:23.78x NPE
```

#### 根因（两层，缺一不可）

1. **`MineTask.tickRestore()` 收尾不自洽**：把 `restoreTask = null` 并返回终态，却**没推进 `phase`**
   ⇒ 任何"终态后又 tick 一次"的调用都会 `phase==RESTORE && restoreTask==null` ⇒ NPE。
2. **夹具主动制造了那次"多 tick"**：`MineRegressionTask` 的 SCOPE_REOPEN 用例为了让 `ScopeBuffer`
   flush 又要等 5 tick，而旧实现在等待期间**每 tick 都先 `mineTask.tick()`** ⇒ 正好踩中 (1)。

**这不是孤例**：同类"字段置 null + 之后仍被 tick"的 NPE 在 09-06 已经崩过一次
（`MovementSequenceWalkTask.tick:54`，`movements` is null）。⇒ 这是**一类**缺陷，按类修。

#### 修法

1. **终态闩锁（任务侧，防崩溃）**：`MineTask.tick()` 拆成幂等外壳 + `tickOnce()`；一旦返回过终态
   （DONE/FAILED），后续 `tick()` **直接返回同一状态、不碰任何子任务**。规矩：
   **任务终态后再被 tick 不得崩**（调用方多 tick 是调用方的事，任务自己必须稳）。
2. **防御性守卫**：`tickRestore()` 里 `restoreTask == null` ⇒ 告警 + 按"拆除结束"收尾，绝不 NPE。
3. **夹具不再 tick 已终态的内层任务**：SCOPE_REOPEN 的等待/断言块重构为"等待期间直接 return"，
   并把**契约本身**钉进断言：`tickTwiceAssertIdempotent()`（连 tick 两次须同状态）——
   exec 用例与 SCOPE_REOPEN 用例都纳入判据（`idempotent=`）。
4. **电池隔离（测试台侧）**：`RegressionBatteryTask` 的 `current.tick()` 包 try/catch ⇒
   单步异常记 `FAIL(exception=…)` + **完整栈**并继续跑完其余步骤。理由：生产任务不该吞异常
   （崩溃本身是要修的 bug），但**测试电池不能因一次夹具缺陷就毁掉整轮测试和全部证据**。
   本次崩溃正是"夹具缺陷 ⇒ 整个服务端崩溃 ⇒ 测试与证据全丢"。

**状态**：`IMPLEMENTED` + `COMPILES`；待客户端复测（电池应能整轮跑完，`mine_regression` 的
`idempotent=true`）。**同类隐患登记（未逐个改）**：脚本扫出 19 处"Task/Runner 字段被置 null 且
`.tick()` 无 null 守卫"（`ScaffoldLifecycleTask` 4 处、`MineTask` 3 处、`TransferTask`/`FluidMineCheckTask`/
`ClearGuardCheckTask`/`PickupGateCheckTask` 等）——目前只因调用方"终态即停"而未爆；
**待办**：把终态闩锁推广到这些任务（按同一契约改，或统一抽基类）。

### D-176：**"bot 物理冻结"实锤** —— 实体整段没被 tick（不是寻路/输入问题）

`segment_stall` 诊断（D-174）在真实运行中抓到了两次，读数**完全一致**：

```
[R4 Session] segment_stall session=collect-23_65_190-0 index=0 kind=segment_timeout to=22, 64, 190
  botFoot=21, 64, 190 pos=21.500,64.000,190.500 onGround=true delta=0.0000,0.0000,0.0000
  input=BotController[forward=1.00 strafing=0.00 sneaking=false sprinting=false jumping=false jumpTicks=0]
  toBlock=空气 headBlock=空气 supportBlock=石头 segmentTicks=121 totalTicks=122
  entityTicksInSegment=0 travelCallsInSegment=0        ← 关键
```
另一次 `collect-23_64_172-0`（to=23,64,171）读数相同。

⇒ **会话在 tick（121 次）、输入是"前进 1.0"、脚下/头顶空气、支撑石头、onGround=true、速度为 0**，
而 **bot 实体这一整段一次都没被 tick**（`entityTickCount` 没涨，`travel()` 没进）。
所以既不是规划、也不是输入、也不是被挡住 —— 是**假人实体的 tick 停止**（随后又恢复，故表现为间歇冻结）。

**这解释了同一轮里的全部异常**：`exec_floating=FAIL case_timeout`、`exec_chain=FAIL … collected=0/9
dropsLeft=1`、`restore_end status=FAILED remaining=1`（拆不动、收不到掉落）——都是一个病因：
"bot 不动"。

**已加看门狗（只诊断、不改行为）**：`BotManager.checkEntityTickProgress` —— 会话在跑但该 server tick
内实体 tick 计数没涨 ⇒ 每 5 秒告警一次并打出区分病因所需的量：
`removed / levelLoaded(pos) / inLevelPlayers / inPlayerList / connection!=null / task`。
下次复现即可直接判定是"实体被移除 / 区块卸载 / 玩家表掉队 / 连接停了 / 单纯没被 tick"。

**未定论**：**谁**本该 tick 这个 `ServerPlayer`（假人连接是 `EmbeddedChannel`，没进
`ServerConnectionListener` 的连接表；vanilla 中 `ServerPlayer` 的实体 tick 与 `doTick()` 由
连接/玩家表两条路径驱动）。拿到看门狗数据后再定修法（可能需要在 `BotManager` 侧补一个
"发现漏 tick 就补一次"的兜底驱动）。

### D-177：审查后续批次 —— 探针降噪（①②）+ 违反断言改成可执行（⑦）+ 终态幂等集中执行（⑤）

按 `REVIEW_2026-09-13_FIX_AUDIT.md` §4 的建议顺序（⑦→①→⑤）落地三件事，另附②：

**① 探针降噪（日志规矩）**：`[MineTask探针] 挖掘状态` 原按 `status` 变化打点，而 `MOVING↔MINING`
会来回跳 ⇒ **实测最高 6 行/秒**、单轮电池 212 行（违反"验证后删探针"）。改为**只在
`(phase,status)` 组合首次出现**时打一行，标签去掉"探针"字样（它是**状态转移**日志，不是临时探针），
典型 3~6 行/用例。终态信息仍由 `[MineRunner] done` / `restore_start/end` 等承担。

**② `pathing/` 包语义**：本轮先不动（迁 `MovementHelper` 会牵 300+ 引用）；改由账本登记，
待与"终态闩锁推广"一起排期。

**⑦ 让 D-168 的"残留不计入"分支**从"只靠推理"变成**每轮都被执行**：
`MineRegressionTask.prepare()` 在 EXECUTE/CHAIN 用例里**主动播下**一件外来掉落物
（`summon minecraft:item … {Item:{id:"minecraft:dirt",Count:1b},PickupDelay:32767s}`，
位置取 `start+Z1`＝测量盒内、bot 侧后方），随后采基线 ⇒ 它必然落进 `foreignDrops`。
判据新增 `foreignOk = (未播种 || foreignDrops >= 1)`，并在明细里打 `foreignOk=`。
意义：判据从"恰好没有残留 ⇒ PASS"升级为"**有残留也必须 PASS**"（真正验证排除逻辑）。
另加播种失败的告警（`summon` 没生效时如实判 FAIL）。

**⑤ 终态幂等 · 集中执行点**：把"任务终态后再 tick 必须幂等"这条契约放到**电池**里执行 ——
每个步的终态任务都补 tick 两次（try/catch 包住），明细打 `idempotent=true|false`，
非幂等即判该步 FAIL。这样**不必逐个类加闩锁**，也能把审查 §2-2 的"19 处同类隐患"
变成可观测断言：谁违反，下一轮电池就会红并点名到步。

**预期副作用（要主动告知用户）**：加 ⑤ 之后，**首次运行可能出现新的 `idempotent=false` FAIL**——
那不是回归，而是契约违规被**发现**；日志会给出步名与"再 tick 返回了什么"，据此逐个修。

### D-179：**常驻 Job 的"漂移"守卫** + 加高（gain）的"就近"前提（用户 A 项，实锤修法）

#### 现场（客户端归档 `2026-09-13-5.log.gz`，12:00 会话）

```
12:00:00.383 task_execution_terminal kind=TransferCheckTask           ← 上一轮传输自检结束
12:00:00.387 [Goal] decision_request trigger=terminal:TransferCheckTask（LLM 请求发出，此时 bot 空闲）
12:00:01.254 玩家又右键传输自检（新任务开始）
12:00:03.135 [Goal] decision_action raw={"action":"start_job","kind":"region_lumber…"}
12:00:03.141 [Goal] execute action=start_job ok=true                 ← 决策层起 Job
12:00:03.237 [Job] select job=lumber picked=tree@33,64,208 d=198.5    ← 目标在作业区内，但 bot 在 198 格外
12:00:03.352 [MiningPlanner] standable_only … reason=no_reachable_standing_point
12:00:03.356 [MineTask] gain_start target=33,64,208 from=45,64,406 to=45,65,406   ← 就地加高兜底
12:00:03.638 [WRITE] place 45, 64, 406 cobblestone by=lumber:gain:attempt0:STEP_PLACEMENT
…（一路搭到 y=75，共 12 格，随后 [Restore] 又拆回）
12:00:26.6  玩家手动 /alice region stop 才停下（`停止请求延后到安全点：task=RegionLumberJob reason=command`）
```

#### 两个根因（都在**基层**，不是局部特判能盖住的）

**R1 · 加高（gain）没有"就近"前提**：`MineTask.tryGainHeight()` 把"目标"取成 **bot 自己头上那格**
（`goal = foot.above()`），**从不校验 bot 与 `target` 的关系** ⇒ 当 bot 被传送/漂移到 198 格外时，
"加高"仍会执行 ⇒ **在世界里写入与目标毫无因果关系的方块**（12 格圆石）。
调用条件本身也是错的：`hasStandingCandidateNow()` 判断的是"**目标几何上**有站位候选"（在目标旁边），
与"**bot 现在**够不够得着"无关。

**判据（用实测数据定的，不是拍的）**：全部 `gain_start` 样本分两簇 —— 合法加高的水平曼哈顿距离
全部 ≤2（`28,70,208←28,64,208` / `21,66,207←23,64,207` / `20,67,208←22,68,207`），
漂移事故是 **198** ⇒ 取 `≤ ceil(bot.getBlockReach())` 分离干净。**竖直不设限**（目标在下方也可能需加高）。

**R2 · 常驻 Job 不感知"我被搬走了"**：`RegionLumberJob` 的候选过滤只按**目标是否在区域内**，
从不检查 **bot 自己是否还在区域附近** ⇒ bot 在 198 格外时仍逐个尝试区域内目标，
既空转又污染 `tried` 集合（最后只能人工停）。

#### 修法（三处，判据单一定义）

1. **`MiningTuning.gainHorizontallyReachable(bot, goal)`**（唯一定义，含 R1 的证据与理由注释）；
2. **`MineTask.tryGainHeight`**：入口即校验；不满足 ⇒ `gain_refused` 告警 + `failureReason =
   gain_target_out_of_range` + **返回 false**（如实失败/上抛，绝不在无关位置写世界）。
   两个分支（就地清头顶 / 就地加高）都在这条守卫之后；
3. **`CollectDropsTask.startGainToward`**：原先只看"掉落物在头顶"（竖直），**同一缺陷类** ⇒ 补水平前提；
4. **`RegionLumberJob`**：`patrol()` 入口加 `nearRegion()` 守卫（区域矩形外扩 `DRIFT_MARGIN=8`，
   竖直 baseY±8）：区外**挂起**（不选目标、不消耗候选、不写世界）、每 100 tick 如实告警一次，
   连续 `MAX_DRIFT_TICKS=400`（20 秒）仍在区外 ⇒ **如实失败 `outside_region`**（不无限等、不静默空转；
   回到区内自动继续）。

#### 断言

`capability_gate` 新增**纯逻辑**用例 `gain_requires_proximity`：近处目标必须可加高、40 格外必须被拒
（双向断言，防"永远返回 true"的假守卫）。

**状态**：`IMPLEMENTED` + `COMPILES`；**未验证**：客户端（判据：电池 `capability_gate` 步出现
`gain_requires_proximity=PASS`；以及真起一次 region_lumber 后把 bot 传走，应看到 `region_drifted` 而不是搭柱子）。

#### D-179 附注一：客户端复测通过（含一次**自查发现的我方缺陷**）

**客户端事实（12:54–12:56）**：
```
[CapabilityGate] SUMMARY … session_status_no_dead_value=PASS gain_requires_proximity=PASS verdict=PASS
                 case=gain_requires_proximity result=PASS near=true farRefused=true reach=4.5
[Regression] SUMMARY … pathing=PASS K4=OK(真异常 0 / 写入类例外 70) (23/23) ticks=3257 → PASS
[Job] region_drifted foot=50, 64, 397 region=x17..37 z203..231 driftTicks=1 ⇒ 挂起作业（不选目标/不写世界…）
（此后到关服：**没有任何 lumber 作业/写入行**）
```
⇒ **漂移守卫在真实场景生效**：bot 在 (50,64,397)（转移场景），Job 作业区在 z203..231 ⇒ Job **挂起**，
**没有**再选 198 格外的目标、**没有**再搭圆石（对比事故轮：同场景搭了 12 格）。

**但是我在自查里发现了自己刚写的缺陷（已修）**：
初版把 `driftTicks++` 放在 `patrol()` 里 ⇒ 计的是"**巡查次数**"而不是 tick；
而 `patrol()` 每 `patrolIntervalTicks`（电池里 20）才跑一次 ⇒ `MAX_DRIFT_TICKS=400` 实际是
**400×20 = 8000 tick ≈ 6.7 分钟**，与注释/文档声称的"20 秒"**不符**（用户这次 23 秒关服，没等到终态，
正是这个原因——行为没错，**语义与文档错了**）。
修：计数移到 `tick()`（真实 tick），`patrol()` 只保留"区外不选新作业"；并新增
`[Job] region_returned`（回到区内恢复作业，原先没有恢复日志）。

**未验证**：`outside_region` 终态本身（需连续漂移 20 秒；修好后重测一次即可）。

### D-180：**传送感知**（用户要求：只加报告，不改行为）

**动机**：bot 被传送（`/tp`、夹具传送、任何 `teleportTo`）时，它自己与决策层**都无从得知**——
D-179 那轮事故的起点正是"bot 被传送到 198 格外而 Job 毫无察觉"。用户明确要求**只加报告**（不加自动反应）。

**实现（纯报告，零行为改动）**：
- `BotPlayer` 覆写**两个** `teleportTo` 入口（`(ServerLevel,x,y,z,Set,float,float)` 与 `(x,y,z)`），
  汇入 `noteTeleport(...)`：累计计数 + 最近一次 `from/to/tick/距离` + 一行 `[Bot] teleported …`
  + 决策事件环一条 `TELEPORT`（`DecisionEvents.record` = 只入环+记日志，**不通知决策层**，非可行动事实）。
- `alice:bot_report` 增行：`传送：N 次；最近 A → B（距离 D，tick=T）`。

**状态**：`IMPLEMENTED` + `COMPILES`；**未验证**：客户端（判据：传送 bot 一次后 `bot_report` 出现该行、
日志出现 `[Bot] teleported from=… to=… distance=… tick=…`）。

### D-176 附注一：B 项结构性调查 —— 假人物理**挂在连接 tick 这条链上**（字节码实证）

用 Gradle 缓存里的 **Mojang 映射 + 混淆 client.jar** 做了调用图核对（不靠记忆）：

```
映射：ServerPlayer → aig，ServerGamePacketListenerImpl → aiy，PlayerList → alk，ServerLevel → aif
反汇编 aig.m()（= ServerPlayer.doTick()）：内部 invokespecial #806 // Method byo.l:()V  ← 调父类 tick()
调用者搜索：aig.m:()V 只出现在 aiy（ServerGamePacketListenerImpl）里；
            alk（PlayerList）与 aif（ServerLevel）**都没有**调用它。
```
⇒ **`ServerPlayer` 的实体 tick 由 `ServerGamePacketListenerImpl.tick()` 驱动**，而假人的那个 listener
背后是 Alice 的 `FakeConnection`（`EmbeddedChannel`，**没有注册进 `ServerConnectionListener` 的连接表**）。
而 Alice 的任务/会话跑在**全局 `ServerTickEvent.END`** ⇒ **两条链不同源**：
连接 tick 一断，就出现"任务在跑、bot 一格不动、无任何报错"（实测 `entityTicksInSegment=0`）。

**据此新增一个**精准探针（只计数，不改行为）：`FakeConnection.tick()` 覆写并计数，
看门狗新增 `connTicks=`。冻结复现时：
- `connTicks` **不动** ⇒ 断在**连接 tick**（这条链是脆点，修复方向 = 让假人 tick 不依赖连接，或保证连接被 tick）；
- `connTicks` 在涨而 `entityTicksInSegment=0` ⇒ 断在**实体侧**（另一条路要查）。

#### D-180 附注一：客户端实测（13:05）—— 传送**日志**已生效；并修掉"原地复位"噪声

**实测（5 条 `[Bot] teleported`）**：
```
13:05:29.030 [Bot] teleported from=30,64,209 to=30,64,209 distance=0.1 tick=0 count=1
13:05:29.052 [Bot] teleported from=30,64,209 to=30,64,209 distance=0.1 tick=0 count=2
13:05:58.526 [Bot] teleported from=30,64,209 to=23,64,207 distance=7.3 tick=562 count=3
13:06:02.370 [Bot] teleported from=18,64,206 to=24,64,192 distance=15.6 tick=639 count=4
13:06:20.151 [Bot] teleported from=24,64,193 to=24,64,208 distance=15.7 tick=904 count=5
```
⇒ 传送**能被 bot 自己捕捉并记录**（含 from/to/距离/tick/累计），符合"只加报告"的要求。

**发现并修正的噪声（我方）**：夹具常用 `teleportTo` 把 bot 摆回**同一格**（实测前两条 `distance=0.1`、
`from == to`）⇒ 若照样记日志/入事件环，报告里会出现"bot 被传送 30,64,209 → 30,64,209"，
反而掩盖真正要解释的漂移。现在：**全部计数**，但**只有位移 ≥1 格**才写日志 + 入事件环并更新"最近位移"；
`bot_report` 行改为 `传送（位移）：共 M 次（含原地复位共 N 次）；最近 A → B（距离 D，tick=T）`。

**仍未验证**：`alice:bot_report` 的"传送（位移）"行本身 —— 本轮**没有跑过 `bot_report`**
（日志里没有任何 `[Report]` 行），下次顺手右键一次即可闭环。

### D-181：**阶段 2（模组浅测）的判据与工具链**：把"读得懂多少"变成可测的量

**背景（D-147/D-148 裁定）**：阶段 2 = 装 **Create + Extended Crafting + Mekanism + Thermal Expansion**，
只验"运行时导出 + P1 读得懂多少 + 有没有配方打架"，**不写适配器**（未知模组能力默认只读）。

**已有的链（无需新造）**：游戏内 `/alice recipes [file]` → `config/alice-recipes.json`
（归一化：`recipes` + `itemTags` + 输入的 `any` 候选）→ 离线 `tools/recipe-graph.py`（S5/P1 逆推）。

**本轮补的两处（都是"让判据可测"，零适配器）**：

1. **`RecipeDump` 增"按类型被跳过"直方图**：原先只报一个 `skipped` 总数，
   而"读不懂的是**哪些模组的哪些配方类型**"才是适配器候选清单的证据。
   现在 JSON 增 `skippedTypes: {类型: 条数}`，摘要行增 `skippedTop=类型=条数,…`（前 3）。
   跳过的语义不变：**未知/模组机器配方如实跳过，不猜**（D-148 原则）。
2. **`tools/recipe-readability.py`（新，只读离线）**：把导出表变成阶段 2 报告 ——
   - 读得懂：配方总数 / 按类型 / 工作站分布 / 产出命名空间 / 输入形态（直接物品 · 标签任意其一 · unknown）；
   - **读不懂 = 适配器候选清单**（按 `skippedTypes` 直方图 + 按模组汇总，**只列清单**）；
   - **配方打架候选**：同一产出物 ≥2 条不同路线、**跨模组同产出**（>1 命名空间都能造）；
   - `--selftest` 用内嵌小样本自证（应判出 1 个多路线 + 2 个被跳过的模组类型）⇒ 已 PASS。
   注意环的问题：逆推必须带 visited/上限（沿用 `recipe-graph.py` 的既有约定），本审计只报"多路线/跨模组"。

**判据（阶段 2 的验收口径）**：拿到装了四个模组后的 `/alice recipes` 导出，
报告需能回答三件事：① 读得懂/读不懂各多少（含按类型与按模组）；② 适配器候选优先级；
③ 配方打架与跨模组同产出清单。**产出物 = 报告与清单，不是适配器代码。**

**现状**：`RecipeDump` 直方图 + 审计脚本已 `COMPILES`；
基线导出（电池那份 partial：313 条、全 minecraft、21 个多路线产出）已被审计脚本正常读取
（并暴露"旧导出没有 `skippedTypes` 字段"⇒ 需新版导出）。

### D-182：阶段 2 模组**安装完成**（7 个 jar，含前置的真相）+ 一处工具缺陷（备份不轮转）

#### 安装清单（Modrinth，1.20.1 forge，最新 **release**，逐个校验 sha1 = OK）

| modId | 版本 | 文件 | 性质 |
|---|---|---|---|
| create | 6.0.8 | `create-1.20.1-6.0.8.jar` | 目标（**jar-in-jar 内嵌 flywheel 1.0.5 + Ponder 1.0.91 + Registrate + MixinExtras，且 `mandatory=true`**） |
| extendedcrafting | 6.0.10 | `ExtendedCrafting-1.20.1-6.0.10.jar` | 目标（需 cucumber ✓） |
| cucumber | 7.0.16 | `Cucumber-1.20.1-7.0.16.jar` | 前置 |
| mekanism | 10.4.16.80 | `Mekanism-1.20.1-10.4.16.80.jar` | 目标（只有 optional 依赖，可忽略） |
| thermal_expansion | 11.0.1.29 | `thermal_expansion-1.20.1-11.0.1.29.jar` | 目标（需 cofh_core + thermal ✓） |
| thermal_foundation | 11.0.6.70 | `thermal_foundation-1.20.1-11.0.6.70.jar` | 前置（**JiJ 内嵌 `thermal_core-1.20.1-11.0.6.24`**） |
| cofh_core | 11.0.2.56 | `cofh_core-1.20.1-11.0.2.56.jar` | 前置 |

**前置的真相（差点被 Modrinth 元数据误导）**：
- Modrinth 的依赖元数据**没列** Create 的 `flywheel`/`ponder`，但 jar 里 `META-INF/jarjar/metadata.json`
  显示二者 `mandatory=true` 且**已内嵌** ⇒ 无需额外下载；
- `thermal`（modId）在 Modrinth 上**没有 1.20.1 forge 版本**（那是 CoFH 的 Thermal Core），
  但它**内嵌在 `thermal_foundation` 里**（`thermal_core-1.20.1-11.0.6.24`）⇒ 也无需额外下载。
⇒ **教训**：依赖判定要以 **jar 内 `mods.toml` + `jarjar/metadata.json`** 为准，
Modrinth/CurseForge 的网页元数据可能不全。

**环境核对**：Forge **47.4.10** 满足全部 `forge versionRange [47.1.0,)`/`[47.1.3,)` ✓；
已装 JEI `15.58.0.209` 满足 Create 的 optional JEI `[15.19.0,)` ✓；
客户端真实 jar 只有 **12 个**（alice + JEI + Ore Excavation + 拼音搜索 + WorldEdit + 新装 7 个），
**没有**重复 modId ⇒ 阶段 2 的"读不懂类型"可**干净归因**到 Create/Mekanism/Thermal。

#### 顺带修掉的工具缺陷：**运行时备份不轮转**（同类问题第二次）

`tools/sync-windows-artifact.sh` 每次同步都在客户端 `mods/` 留一份 `alice-*.jar.bak.<时间戳>`
且从不清理 ⇒ 实测累积 **271 份、241 MB**（目录总共 287 MB，**84% 是备份**）。
这与早前"D 盘被 330 份镜像备份写满"是**同一类**缺陷（备份写进使用目录、无轮转）。
修：同步后按 `ALICE_BACKUP_KEEP`（默认 2）只保留最新若干份，并打印 `runtime_backups_pruned=N`；
已清理现存 269 份（目录 287 MB → **50 MB**）。

**状态**：安装与校验 = 已完成（sha1 全 OK、依赖闭合、无重复）。
**未验证**：客户端**启动**与 `/alice recipes` 导出（下一步由用户执行；若启动报缺依赖，日志会点名，我据此补装）。

### D-183：阶段 2 首轮导出 —— **白名单用了序列化器 id 而非类型 id**（读得懂 15% → 预测 55%）

**首轮实测（772 可读 / ≈5293 总量）**：可读类型只有 5 种（stonecutting/smelting/blasting/smoking/
campfire），而"**读不懂第一名**"是 `minecraft:crafting` **2136 条** —— 这显然不合理，于是查代码：

```
RecipeDump.STATION_BY_TYPE 原键：minecraft:crafting_shaped / crafting_shapeless /
                                smithing_transform / smithing_trim      ← 这些是**序列化器** id
运行时 recipe.getType() 给的是：minecraft:crafting / minecraft:smithing ← **类型** id
⇒ 命中不了白名单 ⇒ 2136 + 31 条原版配方被"如实跳过"
```
**修**：白名单改用**类型 id**（保留序列化器 id 作兼容入口）。**预测**可读 772 → **2939（55.5%）**。
**教训（写进规矩）**：Forge 里"配方类型 id"与"配方序列化器 id"是两套命名空间，
写白名单/匹配表时必须明确用的是哪一套，并**用运行时导出直方图验收**（本次正是直方图抓到的）。

**适配器候选（只列清单，不写代码，D-182/148 口径）**：按"类型数 × 配方量"——
Mekanism（1171，26 类；crushing/enriching/injecting/purifying 覆盖主链）>
Thermal（652，30 类；press/pulverizer/smelter/insolator/centrifuge）>
Create（506，15 类；cutting/deploying/crushing/milling/splashing）> Extended Crafting（25，4 类）。
这些都是**机器加工语义**不在原版配方体系里 ⇒ 适配前需要能力声明 + 授权 + 预算（对齐 `CapabilityGate`）。

**报告产物**：`docs/STAGE2_MODS_READABILITY.md`（安装集 / 读得懂统计 / 该缺陷 / 冲突然当前不可信 /
适配器候选 / 待复测）。**未完成**：修复后需重新导出一次以出最终版（可读率与跨模组同产出清单）。

#### D-183 附注一：**修复后复测完成** —— 阶段 2 结论成立（可读 55.2%，打架清单落地）

客户端重新导出后的最终数据（`config/alice-recipes.json`，1125 KB）：

```
可读 2923 / 总量 ≈5293（55.2%）   被跳过 2370（44.8%）   标签 672
可读类型 7 种：crafting 2120 / stonecutting 514 / smelting 150 / blasting 88 / smithing 31 /
              campfire 10 / smoking 10
原版侧只剩 `minecraft:crafting(空产出) 16`（烟花/地图/旗帜这类无具体产出的特殊配方）⇒ **原版体系已完整覆盖**
输入形态：直接物品 10738 / 标签(any) 262（修复前 5 —— 工作台配方大量用标签）
产出（可读）：minecraft 1253 / create 940 / thermal 333 / mekanism 318 / extendedcrafting 79
```

**配方打架（D-148 核心交付）**：**575 个产出物有多条路线**，其中 **48 个跨模组**。
典型：`copper_ingot` 11 条、`iron_ingot`/`gold_ingot` 10 条（minecraft/create/mekanism/thermal 各自注册）、
`netherite_ingot` 7 条、`thermal:nickel_ingot`/`mekanism:ingot_lead` 6 条（create + 本体模组）。

**更深一层（新发现，有数据）**：**同一材料在不同模组是不同 item id**，由 `#forge:*` 标签归一：
```
#forge:ingots/tin = [mekanism:ingot_tin, thermal:tin_ingot]
#forge:ingots/lead = [mekanism:ingot_lead, thermal:lead_ingot]
#forge:dusts/iron = [mekanism:dust_iron, thermal:iron_dust]
```
标签 672 中 **101 个含跨模组成员**，涉及 **1029 个物品** ⇒ 知识层**必须按标签归一**
（P1 已内建"标签按已知成员展开"），否则"我要锡锭"会被当成两种材料。

**分析工具修正（自身缺陷）**：`recipe-audit` 的"跨模组"归因原按**配方类型命名空间**，
而模组常用**原版类型**注册配方 ⇒ 会把它们全算成 minecraft（实测 `cross-mod = 0` 的假象）。
已改为按**配方 id 的命名空间**（= 谁注册了这条路线）归因 ⇒ 48 个跨模组冲突才显形。

**诚实边界**：机器**专属**类型（Mekanism `crushing` vs Thermal `pulverizer` 同台竞争）仍在跳过集里，
故"机器 vs 机器"的冲突**当前看不到**；48 条跨模组冲突全部来自"模组用原版类型注册"的那部分。

**阶段 2 结论**：① 读得懂多少 = **可量化**（55.2%，且原版已全覆盖）；② 有无打架 = **有清单**；
③ 适配器候选已排序（Mekanism 1171/26 类 > Thermal 652/30 类 > Create 506/15 类 > EC 25/4 类），
**本轮不写任何适配器**。报告：`docs/STAGE2_MODS_READABILITY.md`。

### D-184：**LLM token 用量如实记录**（用户要核对金额；此前只有 chars 无法对账）

**问题**：决策层每次调用都打了 `promptChars` / `reply chars`，但**从没读 API 回包里的 `usage`**
⇒ 用户要"核对金额"时只能靠字符估算，且日志会轮转（旧数据丢掉）。

**修（只报告，不改行为）**：解析回包 `usage.prompt_tokens` / `completion_tokens` ⇒
① 一行日志 `[Goal] llm_usage … | 本进程累计 calls=… prompt=… completion=… total=…`；
② 追加写 `config/alice-llm-usage.jsonl`（**跨日志轮转可对账**，每行含 calls/model/tokens/latency/累计）；
③ `alice:bot_report` 增 `LLM 用量：…`。取不到 usage 就记 `?`，**绝不因此让调用失败**。

**已能提供的"金额核对"材料（三条腿）**：
1. **权威**：DeepSeek 平台用量页（按 key 计费，最准）；
2. **本地估算**（本次从仍在盘上的日志聚合，**不是全量历史**）：`llm_request` **153 次**、
   prompt **250,677** 字符、reply **14,689** 字符 ⇒ 估算 prompt ≈ **63k~100k** tokens、
   reply ≈ **3.7k~5.9k** tokens（字符/4 ~ 字符/2.5 区间）。按日：9-12 共 73 次、9-13 共 80 次。
3. **今后精确**：上面新增的 JSONL 与日志（每行都有真实 tokens）。

**换 key 的位置**：`<client>/config/alice-llm.json` 的 `apiKey` 字段（`model`/`url` 也在同一文件，
当前 `model=deepseek-flash`、`url=https://api.deepseek.com/chat/completions`）。
**注意**：key 只在服务端读取；换完需**重启客户端**（配置在启动时载入）。

### D-185：阶段 3-A / A1 实施 —— **只读配方查询原语**（+ 零参数夹具与"只读"硬断言）

**做了什么**（按 `STAGE3A_CRAFT_PLAN.md` 的五行任务卡）：
1. **`task/craft/RecipeQuery`**：按 `itemId × count` 查运行时 `RecipeManager`（与 `/alice recipes`
   导出、阶段 2 审计**同一份数据**），给出结论五态 ——
   `CRAFTABLE`（料齐且随身 2×2 能做）/ `NEEDS_TABLE`（料齐但要 3×3）/ `MISSING_INGREDIENTS`（列缺什么缺多少）/
   `NO_RECIPE` / `MACHINE_RECIPE_UNSUPPORTED`（只由机器类型产出 ⇒ **不猜语义**，如实列出类型名）。
   材料按 ingredient 的候选物品展开（"任选其一"语义），同 ingredient 多次出现合并为 `perCraft`，
   需求按 `crafts = ceil(count / 单次产出)` 折算。
2. **"哪些类型算原版可读"的唯一定义**：抽出 `RecipeDump.stationFor(type)`，查询与导出**共用**
   （D-183 的教训：两处白名单会漂移，且键用错一套 id 会静默错一整类）。
3. **夹具 `CraftCheckTask` + 零参数入口 `alice:craft_check`**：正例（2 木板→4 木棍，2×2）、
   缺料（清空背包 → 如实报 `missing=[…]`）、3×3 边界（8 圆石→熔炉）、无配方（圆石）、
   机器专属（`mekanism:dust_iron`，未装则如实 `SKIP`），以及**硬断言 `read_only`**：
   整个查询过程**背包逐槽快照完全一致** ⇒ 用夹具证明"只读"不是口头承诺。
4. **电池 23 → 24 项**（新增 `craft_check` 步）；`TESTING_GUIDE` 增 D15 条目。

**边界遵守**：本增量**零世界写入、零背包改动**（有断言）；机器配方命中即如实拒绝；
未接决策层（A5 才进词汇表）。

**已知取舍（诚实记录）**：
- `NEEDS_TABLE` 只看**配方网格尺寸**（shaped ≤2×2 / shapeless ≤4），不看"附近有没有工作台"——
  那是 A3 的事；
- 熔炉/切石/锻造等"非 2×2"类型一律先给 `NEEDS_TABLE`，**不区分**"要工作台"还是"要熔炉"——
  A4 会补 `station` 语义（当前 `station` 字段已如实带出，判据暂只用 grid）。

**状态**：`IMPLEMENTED` + `COMPILES` + 资源自检 PASS。**未验证**：客户端（判据见上）。

#### D-185 附注一：首次客户端实测 —— **只有一条用例失败，且是夹具前提写错**（第三次同类）

**实测结果**（`alice:craft_check`，1 tick 跑完）：
```
craftable_sticks=PASS   CRAFTABLE  station=crafting_table grid=2x2 crafts=1 perCraft=4 mats=[12选1(oak_planks…) x2]
missing_ingredients=PASS MISSING_INGREDIENTS missing=[12选1(oak_planks…) 缺2]
needs_table=PASS        NEEDS_TABLE station=crafting_table grid=3x3 mats=[3选1(cobblestone…) x8]
machine_only=PASS       MACHINE_RECIPE_UNSUPPORTED target=mekanism:dust_iron machineTypes=[enriching, crushing]
read_only=PASS          beforeSlots=0 afterSlots=0
no_recipe=FAIL          MACHINE_RECIPE_UNSUPPORTED target=minecraft:cobblestone
                        machineTypes=[mekanism:crushing, create:milling, mekanism:enriching]
```
⇒ **代码是对的，用例期望错了**：我以为"圆石只能挖"，但装了 Mekanism/Create 之后它们给了
`crushing`/`milling`/`enriching` ⇒ 正确结论就是 `MACHINE_RECIPE_UNSUPPORTED`（如实拒绝、不猜语义）。
**这正是该原语要表达的东西**：判定依据是"**配方类型**是否可读"，与"物品是不是模组物品"无关。

**修法**：把该用例拆成两条，并把教训写进注释与判据：
- `machine_only_vanilla`：`minecraft:cobblestone` ⇒ `MACHINE_RECIPE_UNSUPPORTED`（且列出机器类型）；
- `no_recipe`：`minecraft:bedrock` ⇒ `NO_RECIPE`（**任何**类型都产不出）。

**同类教训（第三次）**：夹具**自己的前提**必须按"当前世界/当前模组集"写，不能按"原版视角"写死。
（前两次：D-168 掉落物测量盒、D-179 播种点。）已计入夹具纪律。

### D-186：阶段 3-A / A2 —— **随身 2×2 合成**（真消耗真产物，零世界写入）

**A1 客户端实测通过**（`alice:craft_check` 7/7 PASS）：`craftable_sticks` / `missing_ingredients` /
`needs_table` / `machine_only_vanilla` / `no_recipe` / `machine_only` / `read_only` 全 PASS，`verdict=PASS`。
（顺带观察：`missing_ingredients` 那次报的是 `minecraft:bamboo 缺8` —— 因为木棍有"木板"和"竹子"两条路线，
迭代顺序决定先报哪条 ⇒ **A5 该把"所有路线的缺料"一起给决策层**，不能只报第一条。）

**A2 实施**：
1. **`task/craft/InventoryCraft`**：只走**菜单协议**（与 L2 传输同源）在**玩家自带 `InventoryMenu`**
   上合成 —— 槽位口径：`0`=结果、`1..4`=2×2 网格（行优先）、`9..35`=主背包、`36..44`=快捷栏。
   手势：从背包槽 PICKUP 拿起 → 网格格 **右键放 1 个** → 余量放回原槽 → 摆满 `QUICK_MOVE`(shift-click) 取结果；
   支持 shaped ≤2×2（按 `getWidth/getHeight` 的**行优先**摆放）与 shapeless ≤4；
   **失败必清理**（把网格里已摆的材料收回背包），失败码：`not_inventory_menu` / `recipe_not_2x2` /
   `missing_ingredient` / `click_rejected` / `result_not_taken`。
   **不直接改背包字段、不凭空生成** —— 真消耗真产物。
2. **夹具 `CraftActionCheckTask` + 零参数入口 `alice:craft_action_check`**：2 木板→4 木棍（+4/−2）、
   4 木板→工作台、4 木板→**8** 木棍（两轮）、缺料 ⇒ `missing_ingredient` 且**背包逐槽不变**、
   网格四格皆空、以及前提断言 `menu_is_inventory`（必须是随身菜单）。
3. 电池 24 → **25 项**（新增 `craft_action` 步）。

**边界**：A2 **零世界写入**（不放置工作台）；3×3/熔炉仍是 A3/A4；未接决策层（A5）。
**状态**：`IMPLEMENTED` + `COMPILES` + 资源自检 PASS。**未验证**：客户端（判据：`[CraftActionCheck] SUMMARY … verdict=PASS`）。

#### D-186 附注一：首次客户端实测 —— **功能全对，两条用例期望写错**（含"看起来什么都没发生"的解释）

**实测（15:44，1 tick 跑完）**：
```
menu_is_inventory=PASS  menu=InventoryMenu
craft_sticks=PASS       product+4 material-2   OK crafts=1 produced=4  consumed=[stick+4]
craft_table=PASS        product+1              OK crafts=1 produced=1  consumed=[crafting_table+1]
craft_multi=PASS        product+8              OK crafts=2 produced=8  consumed=[stick+4, stick+4]   ← 两轮真产出
missing_ingredient_honest=FAIL  code=query_MISSING_INGREDIENTS inventoryUnchanged=true
grid_clean=PASS         slots1..4Empty=true
```
⇒ **菜单协议的 2×2 合成真的成立了**（真消耗真产物、多轮也对），网格也不留残料。

**失败原因（又是我方期望，不是功能）**：缺料在 **A1 查询层**就被挡住（`query_MISSING_INGREDIENTS`），
动作层根本没被调用；而我的断言写的是**动作层**的码 `missing_ingredient` ⇒ 假失败。
**修**：拆成两条 —— `query_refuses_when_short`（断言查询层拒绝 + 背包逐槽不变）与
**`action_cleanup_on_partial`（真验动作层清理）**：只给煤不给木棍，**直接调原语**合火把 ⇒
摆料摆到一半缺料 ⇒ 必须 `missing_ingredient`，且**已摆进网格的煤被收回背包、网格四格皆空**。

**"貌似失败了？没有什么事发生"的正确解释（给用户的）**：这套自检**在服务端瞬时完成、且不改变世界**
（只在 bot 自己的随身网格里点菜单、产物进 bot 背包），所以玩家侧**没有任何可见动作/动画/掉落物**；
唯一可见证据是**聊天里那行 `[CraftActionCheck] SUMMARY …`** 与日志。
想"看见"它：用 bot 背包 GUI（`alice:interface_scanner` 那条路线）或看聊天行；`[CraftActionCheck]` 逐条也会打进日志。

### D-187：**夹具开发纪律**入册（四条规则 + 交付前"三问"）

**起因**：连续四轮出现"**功能是对的、夹具的前提/期望写错了**"（每轮浪费一次客户端测试）：
D-168 掉落物测量盒（数了盒内全部掉落物）、D-179 播种点（按 `start+Z1` 推导，落到 target 盒外）、
D-185 附注一（"圆石没有配方"—— 装模组后它有机器配方）、D-186 附注一（缺料被查询层挡住，
却断言动作层的码）。共同点是：**夹具自己的假设没写下、也没断言**。

**入册位置**：
- `.alice-supervision/skills/alice-scene-based-testing.skill.md` 新增 **§6.9 夹具开发纪律**：
  - §6.9.1 三条前提（**几何盒 / 世界·模组集假设 / 层归属**）—— 每条都标注事故出处；
  - §6.9.2 副作用边界（不得停父任务 D-169、不得 tick 终态任务 D-175/178、失败路径必清理 D-179/186）；
  - §6.9.3 交付前**三问**（测哪一层？上游会短路吗？依赖什么假设、断言了吗？失败时用户看到什么、判据在哪一行？）；
  - 反模式清单与完成前自检各加 3 条；
- `docs/AI_DEVELOPMENT_PLAYBOOK.md` 增"夹具开发纪律（四条）"摘要 + 出处。

**另注（用户侧预期管理，已入册）**：服务端瞬时完成且**不改世界**的自检（如随身 2×2 合成）
在玩家侧**没有任何可见动作** —— 交付时必须主动说明"能看到什么/看不到什么是正常的"。

### D-188：阶段 3-A / A3 —— **用现成工作台的 3×3 合成**（零世界写入）

**为什么先只做"用现成"**：放置工作台属于**世界写入**（D-076：显式授权 + 预算 + 进 A 表），
而"附近本来就有工作台"是最常见的真实情形。把零写入路径做扎实，写路径（放置 + 用完即拆）留到 **A3b** 单独验账本闭环。

**实施**：
1. **格网泛化**：`InventoryCraft.GridSpec{gridSlots,width,height,resultSlot,invFirst,invLast}` ——
   2×2（`{1,2,3,4}` w2 h2 / 背包 9..44）与 3×3（工作台 `{1..9}` w3 h3 / 背包 10..45）共用同一套
   摆料/取产物/失败清理逻辑（**不复制第二份**）。槽位口径来自既有 L2 记录（D-163/D-165）。
2. **`TableCraft`**：`findTable`（半径内最近工作台）→ `standPointNear`（用**内核同口径**
   `MovementHelper.canStandCentered` 找旁边可站格）→ `inReach`（眼位距离 ≤ 触及，与传输任务同口径）
   → `openTable`（`MenuSession.open(..., 46)`）→ **`craftWithMenu` 自断言必须是 `CraftingMenu`**
   （不是 3×3 菜单就说明"3×3 合成"根本没被测到 ⇒ 如实失败，而不是照做）。
3. **夹具 `CraftTableCheckTask` + 场景 `craft_table_course` + 零参数入口 `alice:craft_table_check`**：
   **分相位跨真实 tick**（FIND→WALK→REACH→OPEN→CRAFT→断言，D-165 的教训：同步循环会烧尽段预算）；
   用例：`table_found` / `walked_to_table` / `table_in_reach` / `menu_is_crafting_menu` /
   `crafted_furnace`（8 圆石→1 熔炉，判据看净变化）/ **`no_world_write`**（账本无我方临时方块 = 零写入硬断言）/
   `no_table_honest`（把台挪走后重试 ⇒ 如实失败，**不放置、不绕路**，随后还原场景）。
4. 电池 25 → **26 项**（`craft_table` 步，自带场景 + teleport 起点）；`TESTING_GUIDE` 增 D17。

**边界**：A3 **零世界写入**（有断言）；3×3 = 工作台（熔炉等**加工**仍是 A4）；未接决策层（A5）。
**状态**：`IMPLEMENTED` + `COMPILES` + 资源自检 PASS。**未验证**：客户端。

### D-189：**自检任务一律暂停决策层**（用户反馈："测试完 bot 就自己跑去伐木"）+ A3 夹具自摆前提

**用户反馈（原话）**："啥时候能别让失败自动伐木任务了" —— 每次自检结束（尤其**失败**后），
决策层被"任务终态"触发，LLM 常选 `start_job`（实测 `region_lumber`）⇒ bot 跑去做生产作业、
把测试场地占住。日志实证：
```
15:55:40 task_execution_terminal kind=CraftTableCheckTask … terminal=FAILED
15:55:40 [Goal] decision_request trigger=terminal:CraftTableCheckTask
15:55:42 [Goal] decision_action … raw={"action":"start_job","kind":"region_lumber",…}
15:55:42 [Goal] execute action=start_job ok=true
```

**根因**：项目**早有**同类裁定（`DecisionEvents.record` 注释："自检窗口内**只记录不通知** ——
检具不该在生产侧留下决策痕迹（2026-09-12 实测：夹具造的事件把 LLM 招来，在测试场地起了常驻 Job 把会话占死）"），
但那时只覆盖了**事件**通道；**终态触发**（`terminal:<Task>`）没被覆盖 ⇒ 独立物品入口的自检
（电池自己有 suspend，所以只在电池里看不到这个问题）结束后仍会触发 LLM。

**修法**：
1. `Task.isSelfCheck()`（默认按命名约定 `*CheckTask` 识别 —— 既有夹具全部符合；可覆写）；
2. `BotSession.beginTask(...)`：**自检任务开始即 `GoalDirector.suspend(bot, 1200)`**（60 秒，
   与 `EventThresholdCheckTask` 同口径）= 覆盖夹具本身 + 冷却 ⇒ 终态触发被 `trigger_skipped reason=suspended` 跳过，
   自检**失败也不会**招来生产 job。日志留一行 `自检任务 X ⇒ 暂停决策层 1200 tick`。

**同轮修掉 A3 夹具的前提缺陷（第 5 次"夹具没自摆前提"）**：
实测 `table_found=PASS` 但 `walked_to_table=FAIL foot=20,64,208 stand=46,64,307` —— 独立物品入口
**没人**替夹具传送（电池步会 `teleportBot`，物品不会）⇒ 从伐木场出发去够 100 格外的台子必然失败。
**修**：`CraftTableCheckTask` 在首相位**自己 teleport 到场景起点**（零动量 + 停输入）并加
`start_premise` 自断言（起点到位才继续）。

**待用户确认的设计问题**：是否还要一条**通用**规则 —— "**失败终态不自动起 job**"（不仅是自检任务）？
本轮只做了自检窗口（用户当前痛点），通用规则等用户裁定。

### D-190：阶段 3-A / A3b —— **自放工作站**（合成阶梯上第一次真正写世界）+ "建拆同权"闭环

**为什么 A3b 独立成级**：A3（D-188）验的是"**用现成**工作台且**零世界写入**"；真实场景里
"附近没有台子"是常态 ⇒ 必须有人自己放。但 A3b **必然写世界**，与 A3 的"零写入"硬断言互斥：
塞进同一个 SUMMARY 会让"零写入"退化成"某段之前零写入"，读日志的人分不清边界；
且拆台走 `RestoreScopeTask`（**预算按真实 tick 计**：基础 100 + 每块 450 + 收集 600），
与 A3 的秒级夹具不是一个量级。⇒ **独立夹具 + 独立场景 + 独立电池步**。

**实现**（`task/craft/StationPlacement.java`，注册为授权入口 **A12**）：
1. `findSpot(level, center, radius)`（**只读**）：空气 + 下方有碰撞 + `BlockInteraction.hasPlacementFace`
   ⇒ 取最近的一格。**不猜放置面**：面由 `placeAt` 的支撑面扫描决定，找不到合法面就如实 `no_placement_spot`；
2. `place(bot, spot)`：`WriteGrant.of("craft-station", WriteReason.CRAFT_STATION_PLACE)`
   ⇒ `WriteReason` **新增第 13 个结构化理由**（`Policy.EXPLICIT_TARGET` / `Action.PLACE` /
   `temporary()==true`），于是账本记 **`TEMP`** ⇒ **自动落入"建拆同权"约束**（这是本级的核心不变量）；
3. `place` 的自断言是**世界事实**：放下后那一格必须 `is(手持方块)` ——
   不是"发起过放置"。失败码 `place_failed` / `station_not_present_after_place` / `place_budget_exhausted`（`WriteBudget.placeAllowed`）。

**夹具**（`task/CraftStationCheckTask.java` + `alice:craft_station_check`，零参数，分相位跨 tick）：
PREPARE → PLACE → PLACE_CRAFT → TEARDOWN。判据全部是**世界事实或账本事实**：

| 用例 | 判据（不靠"我说做了"） |
|---|---|
| `start_premise` | 夹具**自己** teleport 到场景起点并断言到位（D-187 §6.9.1；独立物品入口没人替你传送） |
| `no_station_premise` | 出手前范围内**真的没有**工作站（否则测的不是"自己放"这条路）⇒ 如实 FAIL 而不是换个台子用 |
| `holding_station_item` | 主手确实是工作站物品（没有它不可能放置） |
| `station_placed` | 放置后那一格**变成**手持那种方块（`outcome.ok() && isStation`） |
| `write_accounted` | `StationPlacement.pending(...) ≥ 1`（账本里是**我方 TEMP**）—— 记了才必须拆 |
| `placed_table_craft` | 用**自己放的**台子合出熔炉（`furnace +1`），且菜单必须是 `CraftingMenu`（`craftWithMenu` 自断言） |
| `teardown_clean` | `RestoreScopeTask` 之后：方块**回到空气** **且** 该格 `pending=0` **且** `pendingForOwner` 全空 |

**交付前自查抓到的一个**必然**缺陷**（值得单记）：`findSpot` 的搜索区间以 **bot 自己所在格为中心**，
而 bot 那一格**永远是空气**（实体不改方块状态）、下方是实心、放置面也有 ⇒ 距离 = 0 **必然胜出**
⇒ 第一版会**把工作台放进 bot 自己脚下那一格**（方块落在实体身内：顶起来/窒息，随后"触及与站位"全变）。
修：`occupied(level,pos)` = **实体包围盒与该格相交即排除**（bot 高 1.8 ⇒ 头位那格被同一条件自动排除，
别的实体一视同仁），`findSpot` 与 `place` **两处都查**（搜索与执行之间世界可能变）。
**教训**：这类"自己占着目标格"的缺陷在服务端日志里只会表现为"夹具莫名其妙站起来一格"，
在客户端第一次实测时极难归因 —— 交付前把"候选集合是否包含我自己"当必答项。

**两个实现细节（都踩过）**：
- `StationPlacement.pending` **按 owner 查**（`WorldModLedger.pendingForOwner`）而不是按作用域 id 查：
  自检任务不在生产作用域里时 `currentScope` 会是 `null`，按作用域查会**恒为 0** ⇒ 断言假通过；
  按 owner + `TEMP` 查才是"这块地是我的临时方块吗"的直接定义。
- 夹具发料**顺序要紧**：先 `giveHeld(工作站)` 占住选中槽，再 `give(圆石)`；
  反过来 `give` 先把圆石放进 0 号槽（= 选中槽），`giveHeld` 再覆盖它 ⇒ 圆石凭空消失。

**场景**：新增 `alice_test:craft_station_course`（与 A3 场景**同一块地**、但**故意不放工作台**）——
A3 之后紧接着跑 A3b 时它顺手把 A3 的台子清掉，"周围没有工作站"这条前提因此必然成立。
电池 **26 → 27 项**（`craft_station` 步，自带场景 + teleport 起点 + 1600 tick 预算）。

**边界**：只覆盖"**放 + 用 + 拆**"这一条闭环；**尚未**做 ① A4 熔炉加工（燃料/时间/取出）、
② A5 决策层接线（`GoalAction.Craft` + 缺料多路事实）。**未验证**：客户端。

#### D-190 附注一：首次客户端实测 —— **"放工作站"放出了一块圆石**（原语语义用错 + 夹具没做失败清理）

**用户反馈（原话）**："没通过，他怎么放了个圆石？"

**实测事实**（客户端 `latest.log` 16:09，两轮一致）：
```
[CraftStationCheck] start_premise=PASS foot=46, 64, 304 start=46, 64, 304
[CraftStationCheck] no_station_premise=PASS existingTable=-
[CraftStationCheck] holding_station_item=PASS mainHand=crafting_table      ← 主手确实是工作台
[Ledger] place 46, 64, 303 minecraft:cobblestone←minecraft:air [TEMP CRAFT_STATION_PLACE scope=…:CraftStationCheckTask]
[CraftStationCheck] station_placed=FAIL FAIL:station_not_present_after_place spot=46, 64, 303 block=圆石
[CraftStationCheck] SUMMARY … write_accounted=PASS verdict=FAIL           ← 2 tick 就终态，没进拆除相位
world_mod_ledger_close … 仍有 1 条我方临时放置未拆除（建拆同权未闭合…）      ← 圆石真留在世界里
```

**根因（两个独立缺陷）**：

1. **原语语义用错**（主因）：`BlockInteraction.placeAt(bot, …, grant)` 是 **"放一个一次性方块"** 的原语 ——
   槽位来自 `findPlaceableSlot`（只认 `alice:throwaway` 白名单）并**自己改 `inventory.selected`**。
   4 个既有调用者（`PillarExecution` / `PlaceStepAndTraverseExecution` / `MineBlockRunner` 支撑块 / `PlaceTask`）
   都是这个语义，**它们没错**；错的是我拿它当"放**指定**方块"用 ⇒ 它在白名单里挑中背包的**圆石**，
   于是"放工作站"变成"放圆石"。
   对照 Baritone `BuilderProcess:563-570`：builder 放的是**计划里的那个方块**，槽位由
   **按想要的方块状态匹配**得到（`valid(...)`），并在同一处 `player.getInventory().selected = toPlace.hotbarSelection`
   —— **两种语义在 Baritone 里也是分开的**。
2. **夹具没做失败清理**：`station_placed=FAIL` 时直接 `finish()` ⇒ 已写入的那格留在世界（违反 D-187 §6.9.4
   "副作用边界：失败也要清理"）⇒ 用户看到地上真的多了一块圆石。

**修法**：
1. `BlockInteraction` 增加**第二个语义**（共用唯一的实现体 ⇒ 预算/审计/账本一条都不会被绕过）：
   - `placeAt(bot, level, pos, sneak, grant)` = 一次性方块（白名单，**语义与行为完全不变**）；
   - `placeAt(bot, level, pos, sneak, grant, Block wanted)` = **指定方块**，槽位来自新增
     `findSlotForBlock(bot, wanted)`（对照 Baritone 的"按方块匹配"的一半），
     找不到就如实返回**新值 `NO_ITEM`**（不换别的方块凑；两个 `NO_*` 病因不同，不能混）。
   只查**快捷栏**：从背包搬东西到快捷栏是另一个能力（Baritone `InventoryBehavior.attemptToPutOnHotbar`），
   **未实现 ⇒ 如实报 `station_not_in_hotbar`**，不假装能做。
2. `StationPlacement.place` 改用带 `wanted` 的重载（`wanted` = 主手方块），自断言仍是世界事实
   （放下后那格必须 `is(wanted)`）。
3. 夹具补**失败清理相位 `CLEANUP`**：`finish()` 发现账本里还有我方 `TEMP` ⇒ 先跑 `RestoreScopeTask`
   拆回去再出 SUMMARY；清理本身失败则多记一条 `cleanup_on_failure`。
   另外 `prepare()` 起手 `WorldModLedger.dropStale` 销掉**上一轮失败留下的幽灵条目**
   （场景函数已把地清空 ⇒ 条目与现实不符），否则下一次的 `teardown_clean` 会被旧残留顶成假失败。

**教训（与 D-187 同族，第五条）**：读日志的人一眼就能看出"放了圆石"，
但**代码里没有任何一处写着圆石** —— 这类缺陷只能靠"**这个原语的语义到底是什么**"这一问拦住。
⇒ 调用写入原语前先答：它是"**放某个**"还是"**放这个**"？（已写进 `WORLD_WRITE_AUTHORIZATION.md` §1）。

### D-191：模组兼容范例 —— **"工作站"必须可切换**（不硬编码合成方式）+ 两个"精致存储"的辨识

**用户提出**（原话）："精致存储的每个容器（背包，箱子，潜影盒）都能装合成升级，打开容器后，右侧会有标签，
点击标签就能打开" + "合成升级的页签" + "合成产物的 QuickMove 可以切换目标"。
**用户裁定**："现在只是做**兼容范例测试**，先实现**工作站的可切换**，不急着完全适配其他模组。"

**先记一个低级错误**：用户说"我们先简单装一个精致存储吧"，我装成了 **Refined Storage** ——
中文里 **Refined Storage** 与 **Sophisticated Storage** 都译作"精致存储"，而"每个容器都能装合成升级 +
右侧标签页 + 页签里 3×3" 是 **Sophisticated Storage（P3pp3rF1y）+ Sophisticated Core** 的形态。
**教训**：模组需求**不要靠中文译名对齐**；用户给的行为描述（"右侧标签页"/"每个容器"）比名字更可靠，
先按行为描述去 Modrinth 反查，再动手装。

**已装**（固定客户端）：`sophisticatedstorage-1.20.1-1.4.86.2131`（sha1 `f0159288…`）+
`sophisticatedcore-1.20.1-1.5.1.2335`（sha1 `24d7f2ee…`，**必需前置**，升级框架与标签页实现都在 Core，
⇒ **背包（Sophisticated Backpacks）共用同一 Core**，同一适配器将来天然覆盖）。
`mods.toml`：Storage 要 `forge [47.1,)` + `sophisticatedcore [1.3.82.+,)`；Core 无依赖。

**三条用户描述全部源码取证为真**（详见 `docs/MOD_COMPAT_CRAFT_STATION_PLAN.md` §1）：
1. `item.sophisticatedstorage.crafting_upgrade.tooltip = "Crafting table in an upgrade tab"`；
2. 矩阵 9 格内容序列化进**那颗升级物品自身的 NBT**（`CraftingUpgradeWrapper`: `new ItemStackHandler(9)` →
   `upgrade.addTagElement("craftingInventory", serializeNBT())`）⇒ 关 GUI 不归还；
3. 结果槽 shift 目标**有设置项**：`CraftingUpgradeWrapper.shouldShiftClickIntoStorage()`（默认 **true** = 进容器），
   语言文件里就是两个按钮 `Shift Click Result Into Storage` / `Shift Click Result Into Player's Inventory`。

**决定性的工程事实（本轮最有价值的发现）**：标签页是**纯视觉**的 ——
`CraftingUpgradeContainer` 把 9 个矩阵槽 + 结果槽一律建在 **(-100,-100)**，
`StorageContainerMenuBase.addUpgradeSettingsContainers` **只要升级装着就把槽加进菜单**；
`getOpenTabId()/setIsOpen` 只影响"哪个升级是打开的那个"（供配方转移挑选）。
⇒ **服务端 bot 不需要点标签、不需要发包**就能摆料取产物；且矩阵/结果是**原版类型**
（`CraftingItemHandler extends TransientCraftingContainer`、原版 `ResultSlot`），
`slot.container instanceof CraftingContainer/ResultContainer` 即可发现 ⇒ **通用发现器可解，零模组专属代码**。

**结论（回答用户的问题）**：**不该硬编码合成方式**，但改动点集中在四个维度：
① 槽位靠**发现**（不再用常量下标）；② 结果槽**取法协议**（QUICK_MOVE 在各站点语义不同，含"重复多次"）；
③ **材料来源**（容器/网络 ≠ 玩家背包，守恒断言口径要跟着变）；④ **持久矩阵 ⇒ 写入授权**
（矩阵内容存进方块实体/升级物品 NBT，"零世界写入"只在随开随灭的原版网格上成立）。
第一步只做 ①②③④ 的**只读事实 + 可切换**，执行接入留第二步。设计见 `docs/MOD_COMPAT_CRAFT_STATION_PLAN.md`。

### D-192：阶段 3-A / S1 —— **合成工作站可切换**（通用网格发现 + 只读探针），"不硬编码合成方式"落地第一步

**用户裁定（原话汇总）**：*"现在只是做兼容范例测试，先实现工作站的可切换，不急着完全适配其他模组"*、
*"不要求这些方式能够自动选优，玩家切换也足够了"*、*"装上升级本身算一种配置行为，点开标签页也单独算上一层"*、
*"如果打开子标签页和不打开子标签页实际没有区别，就不用管"*。
决策：D1-**B**（S1-1 发现器 + S1-2 工作站切换 + S1-3 探针一起做，执行接入留第二步）、
D2-**A**（L3 以实测差分为准，无差别就不动状态）、D3-**A**（装升级=独立配置层）、
D4-**A**（命令切换）、D5-**A**（夹具里 bot 用菜单协议装升级）、D6-**A**（本轮只做方块型站点）。

**实施（3 个新文件 + 接线，全部只读）**：
1. `task/craft/GridDiscovery`（**S1-1**）：从**活菜单**里认网格 —— 网格槽 = `slot.container instanceof CraftingContainer`
   （**同一实例**）、结果槽 = `ResultContainer`、玩家背包 = `container == player.getInventory()` 且容器槽号 <36；
   尺寸取 `CraftingContainer.getWidth()/getHeight()`。**零模组知识**。如实拒绝：
   `no_grid` / `no_result_slot` / `ambiguous_grid` / `ambiguous_result` / `grid_shape_mismatch` / `no_player_inventory_slots`。
   另有 `describeSlots(menu)` 打印**全槽位事实表**（下标/槽类/容器类/坐标/`isActive`/物品）。
2. `task/craft/CraftStation`（**S1-2**）：站点描述符 `{id,label,kind,take,source,note}`；
   `inventory`（随身 2×2）/ `table`（原版工作台）/ `upgradetab`（精妙存储容器，**按方块 id 形态识别** =
   `namespace=sophisticatedstorage` 且 path 含 chest/barrel/shulker，不写死版本清单）。
   **`auto` 只复刻现状（随身→工作台），不含升级页签**（= 不做自动选优）；显式选中的站点不可用就**如实失败**、
   **不悄悄回退**。升级页签的 `take/source` 如实写 **`UNKNOWN`**（未实测 ⇒ 只读，不执行）。
   `candidates()` 产出"能不能用 + 为什么"（带方块坐标/菜单描述等**可核对事实**）。
3. `task/CraftGridProbeTask` + `alice:craft_grid_probe`（**S1-3**，零参数、只读）：自带传送 → 按当前选择打开站点 →
   `GridDiscovery` → 打印菜单身份/发现结果/全槽位表 → 断言 `read_only`（账本无我方临时方块）。
   **不发任何页签消息**（`tab_action=none`），因此 `grid_addressable_without_tab=true` 就是
   "**点开与不点开没区别**"的直接证据（用户裁定的那条规则）。
4. 接线：`/alice craft station [<id|auto>]`（列候选 / 切换）、`bot_report` 新增"合成工作站："一行、
   `MenuSession.state()` 只读访问器、电池**不动**（探针需要精妙容器，未入电池）。
5. 场景：`alice_test:craft_tab_course`（孤立平台 + 精妙箱子 + 给玩家一颗合成升级）+ 诊断函数
   `alice_test:craft_tab_snapshot`（`data get block`，供"开/关菜单是否改持久状态"的 NBT 差分）。

**顺带查出的条件副作用（必须记）**：`StorageContainerMenuBase` 在**构造**与 `removed(player)` 里都调用
`removeOpenTabIfKeepOff()`，它只在**该玩家**把 `KEEP_TAB_OPEN` 显式设为 `false` 时才
`storageWrapper.removeOpenTabId()` ⇒ **"打开菜单=纯读取"在本模组上有一个条件例外**。
处置：探针的 `read_only` 断言**如实限定**为"账本无我方临时方块 + 不发包 + 不摆料/不改背包"，
并用场景诊断函数做 NBT 前后差分（**不靠推理下结论**）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（`checked=71`）+ 已同步客户端（jar `3cb0efcf…`）。
**未验证**：客户端（探针输出、精妙菜单的真实槽位表、`grid_addressable_without_tab` 的真值）。
**第二步**（S1-5）：按实测给 `upgradetab` 定 `take/source`，再谈执行接入；L2 装配任务（装升级）与新 `WriteReason`。

#### D-192 附注一：首次客户端实测 —— **探针把服务端 tick 循环打崩了**（`menu.getType()` 陷阱）

**用户反馈**："一开始任务开始崩溃了喵"。

**实测事实**（`crash-reports/crash-2026-09-13_17.01.00-server.txt` + `latest.log`）：
```
UnsupportedOperationException: Unable to construct this menu by type
  at net.minecraft.world.inventory.AbstractContainerMenu.m_6772_(AbstractContainerMenu.java:80)
  at com.dddgn.alice.task.craft.GridDiscovery.describeMenu(GridDiscovery.java:223)   ← 我方代码
  at com.dddgn.alice.task.CraftGridProbeTask.report(CraftGridProbeTask.java:156)
…  Description: Exception in server tick loop
[CraftGridProbe] open → OK station=inventory 随身菜单（无需打开） foot=46, 64, 304 onGround=false
```
**根因**：`GridDiscovery.describeMenu()` 无保护地调了 `menu.getType()`。而 `AbstractContainerMenu.getType()`
在**没有 MenuType** 的菜单上会抛 `UnsupportedOperationException` —— **原版自己的 `InventoryMenu`（玩家随身菜单）
就是用 `null` MenuType 构造的**（不是模组的毛病）。用户当时选择是 `auto` ⇒ 探针走随身菜单 ⇒ 必崩。
**两条教训**（比这个 bug 本身值钱）：
1. **菜单身份是可选信息**，拿不到就如实标 `unregistered`，绝不为它冒崩服务的险；
2. **凡是对"来源未知的菜单/槽位对象"取值，一律加保护** —— 这类对象来自模组，可能在**任何** getter 里抛。

**修法（全链路清理同类地雷）**：
- `describeMenu`：`getType()` 包 try/catch → `unregistered(<异常类>)`；
- `describeSlots`：**逐槽** try/catch（异常写进那一格的描述，不让整张表陪葬）；
- `GridDiscovery.discover`：逐槽保护 + `matrix.getWidth()/getHeight()` 保护（新码
  `grid_metrics_failed` / `slot_facts_partial`）—— 它是**报告路径**上的常用入口
  （`bot_report`、候选列出每次都调），必须绝对安全；
- `CraftStation.candidates`：每个候选包 try/catch → `probe_exception:<类>`；
  `MenuSession.open` 也包了（`menu_open_failed`）；
- `CraftGridProbeTask.report`：整段包 try/catch（`probe_exception` 一行 + `verdict=FAIL`，**不崩**）。

**顺带修掉的第二个假失败风险**（同一次日志暴露）：`onGround=false` —— teleport 之后**不止一 tick**
才落地，而 `MenuSession.open` 有 K-3 门"空中硬拒" ⇒ 若用户当时选的是 `upgradetab`，
会把"探针还没落地"记成"菜单打不开"。修：开菜单前**显式等落地**（预算 40 tick，等不到如实失败），
并把 `settle_ticks=N` 记进 SUMMARY。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `ade7f714…`）。**待客户端复测**。

#### D-192 附注二：复测结果 —— **两项通过、一项回归（探针招来 LLM 自动伐木）、一项待证**

**客户端实测（`latest.log` 17:04:30 / 17:04:51，两轮探针，无新崩溃）**：

| 结论 | 证据（原样） |
|---|---|
| ✅ **崩溃已修** | `menu=InventoryMenu(unregistered(UnsupportedOperationException)) slots=46` —— 原来抛异常那一行现在只是一句如实标注 |
| ✅ **通用发现器在随身菜单上回归通过** | `discover=OK grid=2x2 slots=[1,2,3,4] result=0 inv=[9..44] matrix=TransientCraftingContainer(4) resultSlot=ResultSlot(1) note=resultSlotIsVanillaResultSlot=true playerSlots=36` —— 与 D-163 记录的布局**逐项一致** |
| ✅ **工作站切换真的生效** | `auto` → `station=inventory`；`/alice craft station upgradetab` → `station=upgradetab@46,64,306`、`menu=StorageContainerMenu(sophisticatedstorage:storage) slots=63`、`menu_opened=true ticks=3` |
| ✅ **落地等待有效** | `settle_ticks=2`（原来下一 tick 仍 `onGround=false`） |
| ✅ **没装升级时如实报"没有网格"** | `discover=FAIL:no_grid … note=菜单里没有 CraftingContainer（该站点当前没有合成网格）` + 全 63 槽事实表 |
| ❌ **回归：探针招来决策层** | `decision_request trigger=terminal:CraftGridProbeTask` → `execute action=start_job ok=true`（`region_lumber`）**连招两次** —— 用户最早的抱怨原样复发 |
| ⏳ **待证** | 装上升级后的那一轮（本次两轮都没有装升级 ⇒ L3 的关键证据 `grid_addressable_without_tab` 仍未知） |

**新事实（对设计有用）**：精妙菜单里**服务端槽位坐标全是 `@0,0`**（`slots(63): … Slot/Inventory@0,0 …`）——
⇒ **x/y 不能用来判断"这格有没有显示"**（与"页签是客户端渲染"一致）。判据只能是**容器身份 + `isActive`**，
这正好是我们发现器的做法（不需要改）。

**回归根因与修法**：`Task.isSelfCheck()` 只认命名 `*CheckTask`，而探针叫 `CraftGridProbeTask`
⇒ 不被当作自检 ⇒ 终态触发 LLM。修：① 命名约定扩展到 **`*ProbeTask`**（既有 `MenuProbeTask` 一并受益）；
② `CraftGridProbeTask` **显式覆写 `isSelfCheck()=true`**（将来改名也不会再犯）。
**教训**：D-189 那条裁定依赖"命名约定"，而**新工具很容易起一个不匹配的名字** ⇒
约定要么覆盖全部自检形态，要么让工具**显式声明**；本类两者都做了。

#### D-192 附注三：**根因找到** —— 上游把"升级页签"的槽位建在 `menu.slots` **之外**（`slot.index` 才是点击地址）

**用户实验（关键一步）**："我把标签页关着做了次测试，又打开标签页做了一次测试" —— 两次结果**完全相同**，
⇒ 页签开关状态**不影响**服务端菜单里有什么（与我们"没区别就不用管"的裁定一致）。

**只读反射诊断给出的决定性事实**（两轮一致）：
```
diag_menu_wrapper=  upgradeHandler=[slots=1] 0:null inventoryHandler=[slots=27]
diag_be_wrapper  = upgradeHandler=[slots=1] 0:null inventoryHandler=[slots=27]
diag_menu_upgradeContainers=1 [0]          ← 合成升级页签容器**建出来了**
menu=StorageContainerMenu(sophisticatedstorage:storage) slots=63   ← 可 menu.slots 只有 27+36
```
⇒ **容器存在、升级槽也存在，但它们不在 `menu.slots` 里** ⇒ 我方"遍历 `menu.slots` 认容器"的发现器**必然看不见**。

**字节码核对（装的那一版 Core 1.5.1.2335）**：
`StorageContainerMenuBase.addUpgradeSlot(Slot)` = `slot.index = getTotalSlotsNumber()` + 收进它自己的
`upgradeSlots` 列表，**从不调用 `AbstractContainerMenu.addSlot(...)`**；`instantiateUpgradeSlot` 由子类实现。
⇒ 上游把"升级槽 + 各升级容器贡献的槽位（含合成 9 格 + 结果槽）"放在 **`menu.slots` 之外**自管，
**`slot.index` 才是它们的点击地址**。

**修法（通用，不是模组专属）**：
1. `GridDiscovery.collectSlots(menu)`：以 `menu.slots` 为起点，**反射**把菜单字段里可达的 `Slot` 全部收进来
   （`Slot` / 集合 / 映射 / "自带 `getSlots()` 的值对象"展开一层；按**对象身份**去重；逐项容错、深度受限）。
2. **点击地址一律用 `slot.index`**（不再用"在 `menu.slots` 里的位置"）。原版槽位的 `index` 就等于其位置
   ⇒ 随身 2×2 / 工作台 3×3 的**行为完全不变**（回归由既有夹具证明）。
3. `describeSlots` 同样用 `collectSlots`，并在表里标出**点击地址**（`#index`）与 **`*` = 不在 `menu.slots` 里**
   ⇒ "页签里的 9 格"从此在探针表里可见。

**教训**：`menu.slots` **不是**"菜单里所有槽位"的完整集合 —— 上游可以在外面自管槽位并只设 `slot.index`。
发现器必须问"**菜单里所有可达的槽位**"，而不是"`menu.slots` 里有什么"。

#### D-192 附注四：槽位表露出真身（`74` 槽 / 9 格在 `-100,-100` / 结果槽 `#73`）+ 发现器改成**四条证据路径**

**客户端事实（17:22 探针 + 17:19 截图）**：
```
slots(74): 0..62 是登记在 menu.slots 的 63 个；
           63: *#63/SimpleContainer@-15,0   = crafting_upgrade      ← 升级槽
           64..72: *#64..72/SimpleContainer@-100,-100                ← **9 个网格格**（在 menu.slots 之外）
           73: *#73/ResultContainer@-100,-100                        ← 结果槽
discover=FAIL:no_grid matrix=-(0) resultSlot=(1)      ← 结果槽认出来了，但"矩阵"没认出来
```
**为什么矩阵没认出来**：那 9 格的 `container` **不是** `CraftingContainer`（挂在升级自己的物品处理器上）；
只有**结果槽**挂着 `ResultContainer`。而精妙那版的合成矩阵是 `CraftingItemHandler extends TransientCraftingContainer`，
它是**升级容器的私有字段**、由结果槽持有（原版 `ResultSlot.craftSlots`）—— 也就是说：
**"9 格"与"矩阵"之间没有容器身份关系**，只有"内容同物"关系。

**发现器重写（四条证据路径，用了哪条写进 `note`）**：
| 路径 | 判据 | 强度 |
|---|---|---|
| ① 身份 | 槽位的 `container` 就是矩阵容器 | 最强（原版形态） |
| ② 结果槽字段 | 反射读原版 `ResultSlot.craftSlots`（或 `getCraftSlots()`）拿矩阵 | 通用、零模组知识 |
| ③ 上游自述 | 可达宿主自己声明 `getRecipeSlots()`/`getCraftMatrix()`，且**矩阵必须是同一个对象** | 上游语义 + 自校验 |
| ④ 内容镜像 | 某容器与矩阵**逐格同物**、格数 == 宽×高，且这样的容器**唯一** | 最弱；不唯一即拒绝（`grid_slots_unresolved`） |

另外 `describeSlots` 现在标出 **`#index`（点击地址）** 与 **`*`（不在 `menu.slots` 里）**，
`note` 带 `matrixBy=` / `gridBy=` / `registeredSlots=` / `reachableSlots=` ⇒ **"用了哪条路"永远可见**。

**仍未验证**：`gridBy` 实际取哪条、`grid=3x3 slots=[64..72] result=73` 是否成立；
以及**点击**是否真的能按这些 `index` 走到上游的槽位（探针只读，属第二步 S1-5 的执行验证 —— 那时才谈"页签站点能不能真合成"）。

#### D-192 附注五：`matrix=-(0)` 的真因 —— **vanilla 字段/方法在生产环境是 SRG 名，字符串反射读不到**

**客户端事实（17:26 探针）**：`menu_slots=74` ✓（`collectSlots` 生效）、`resultSlot=(anonymous)(1)` ✓（结果槽认出来了），
但 `matrix=-(0) note=找不到矩阵容器`。

**根因**：路径 ② 当时是按**名字**反射原版 `ResultSlot` 的矩阵字段（Mojang 名 `craftSlots`）。
而 **Forge 生产环境里 vanilla 的字段/方法名是 SRG 名**（`f_xxxxx_`/`m_xxxxx_`）——
`"craftSlots"` 只有**开发环境**能命中。同源证据：诊断里的 `upgradeHandler=[slots=1] 0:null`
也是同一毛病（`ItemStack.getItem()` 被当成 `"getItem"` 反射 ⇒ 拿到 null）。

**修法（一律"按类型/按编译期调用"，不再靠 vanilla 名字字符串）**：
1. `matrixFromResultSlot`：遍历结果槽的类层次，**认字段类型 `CraftingContainer`**（其次认"无参且返回 `CraftingContainer` 的方法"）
   —— 名字无关，跨映射/跨版本都成立；
2. `scan` 顺带**按类型**收集所有可达的 `CraftingContainer`：唯一时作为矩阵兜底
   （`matrixBy=reachableCraftingContainer(unique)`），多于一个就 `ambiguous_grid`（不猜）；
3. 诊断里读物品名改成**编译期调用**（`stack instanceof ItemStack is → is.getItem()`），不再字符串反射。
   `note` 增 `matrixCandidates=`，`matrixBy=` 会明说是哪条路（`resultSlotFieldByType` 等）。

**通用教训**：**对 vanilla 成员做字符串反射在 Forge 生产环境必然踩空**；要反射就认**类型/签名**，
能不反射就用编译期调用。

#### D-192 附注六：**S1-3 收口** —— 通用发现器在三种站点上都成立 + 探针入电池（30 项）

**客户端实测（17:28:56，最好的一轮）**：
```
discover=OK grid=3x3 slots=[64,65,66,67,68,69,70,71,72] result=73 inv=[27..62]
  matrix=CraftingItemHandler(9) resultSlot=(anonymous)(1)
  note=matrixBy=resultSlotFieldByType gridBy=ownerDeclaration(getRecipeSlots)
       matrixCandidates=0 registeredSlots=63 reachableSlots=74 playerSlots=36
grid_found=true inactive_slots=0 tab_action=none grid_addressable_without_tab=true read_only=true verdict=PASS
```
**结论（三条）**：
1. **通用发现器成立**：随身 2×2 / 工作台 3×3 / 精妙"升级页签" 3×3 三种站点全部认对，且**零模组专属代码** ——
   只做三件事：认**容器类型**（`CraftingContainer`/`ResultContainer`）、认**字段类型**（矩阵从结果槽按类型取）、
   认**上游自述**（`getRecipeSlots()`，并自校验矩阵是同一对象）。
2. **L3 定格为"什么都不做"**（用户裁定）：`tab_action=none` + `grid_addressable_without_tab=true`
   ⇒ 不开页签也能寻址 ⇒ 不发包、不动 `openTabId`/`KEEP_TAB_OPEN`、没有需要复位的东西。
   证据 = 用户"关页签/开页签各跑一次"的对照实验（两轮结果完全相同）。
3. **探针入电池**（27 → 30 项）：`craft_probe_inventory`（2×2 硬断言）/ `craft_probe_table`（3×3 硬断言，**零模组依赖**）
   是"四路径重写对原版站点行为等价"的回归证明；`craft_probe_upgradetab` 是模组站点步（模组不在 ⇒ SKIP）。

**电池基础设施改动**：`Step` 增 `skipWhen` + `stepSkippable`；SUMMARY 的 **SKIP 计入绿**（并写明 `SKIP=n`）；
`endStep()` 复位站点选择（自检串联不许泄漏选择）。

**仍未做**：① 点击这些 `#index` 能不能真合成（第二步 S1-5 执行接入）；
② **L2 装配层**（bot 用菜单协议把合成升级点进升级槽 + 用完即拆，独立 `WriteReason`/A 表条目）——
做完之后 `craft_probe_upgradetab` 才升级成 3×3 硬断言。

### D-193：阶段 3-A / S1-5a —— **合成执行接入发现器**（执行层不再看写死的槽位常量）

**用户裁定**：下一步按 **B → A → C** 走（B = 执行接入 / A = L2 装配层 / C = 模组站点真合成）。

**改了什么**（"执行层真的走工作站"的第一步）：
1. `InventoryCraft.craft(bot, menu, recipe, count)`（4 参入口）：**格网规格改由 {@link GridDiscovery} 现场给出**。
   过去写死 `inventorySpec()`（= 记住的 2×2 下标：1,2,3,4/结果 0/背包 9..44），只要换菜单就错位；
   现在认容器得出规格，**认不出就如实返回新码 `grid_unrecognized`，绝不回退常量**。
2. `TableCraft.craftWithMenu(...)`：**前置断言从 `instanceof CraftingMenu` 改成"菜单里认得出 ≥3×3 网格"**。
   这条换掉很重要 —— 旧断言等于"站点必须是**原版工作台菜单**"，把"用模组容器里的 3×3 页签"这件事
   **在断言层就排除掉了**；新断言只要求**能力**（尺寸 ≥3×3），所以对将来的模组站点天然成立。
3. 删掉 `inventorySpec()` / `tableSpec()`（发现器取代后**没有调用者**，按 K-2/K-5 的死代码纪律删除），
   两处布局说明留在注释里作为"发现器认出来的东西长什么样"的参照。
4. `Codes.NOT_INVENTORY_MENU` → **`GRID_UNRECOGNIZED`**（语义从"菜单必须是玩家自带菜单"变成"菜单里必须认得网格"）；
   `NOT_2X2` 保留代码串但补注释：语义是"配方**放不进**当前网格"（网格可能 >2×2，串名是历史遗留）。

**为什么这不是行为变更而是等价替换**：客户端实测（17:22/17:28 探针）已证明发现器在 `InventoryMenu` 上给出
`grid=2x2 slots=[1,2,3,4] result=0 inv=[9..44]`、在工作台 `CraftingMenu` 上给出 `3x3 slots=[1..9] result=0`，
**与两个常量逐项一致**。⇒ 行为等价由 **电池里的 `craft_action` / `craft_table` / `craft_station` 三步 + 两条硬断言探针步**证明。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `34cf8b1b…`）。**待客户端**：30 项电池整轮复测。

### D-194：阶段 3-A / **L2 装配层** —— 工作站升级的"装/拆"自成一类（用能力验证 + 建拆同权）

**用户裁定**：*"装上升级本身算一种配置行为"*、*"bot 自己用菜单协议把合成升级点进升级槽这条路可以"*、
*"用完即拆"*；并按 **B → A → C** 推进（B 已完成：D-193）。

**做了什么**：
1. **新写入理由** `WriteReason.STATION_PROVISION`（`EXPLICIT_TARGET` / `BOTH`）+ **A 表 A13**：
   装配走**容器写入**维度（`WriteBudget.consumeContainerWrite`，与 A11 `CONTAINER_TRANSFER` 同源），超限即 REFUSED。
2. **`task/craft/StationProvision`**（那一层本身）：
   - `moveIntoContainer`：先 `QUICK_MOVE`（**落点由菜单自己决定** ⇒ 不猜槽位语义），上游不接受时兜底读
     模组自己的 `upgradeSlots` 字段直接放；`moveOutOfContainer` 是其对称操作；
   - **地址映射也是发现出来的**：玩家背包下标 → 菜单地址一律查
     `slot.container == player.getInventory() && slot.getContainerSlot() == 下标`，
     **不按公式猜**（精妙容器的玩家背包段是 27..53 + 54..62，`36+i` 那种公式一猜就错）；
   - 点击走 `menu.clicked(...)` 而**不是** `MenuSession.click`：后者会拒绝超出 `menu.slots.size()` 的地址，
     而上游自管的槽位恰恰在那里（D-192 附注三/四）。
3. **夹具** `CraftStationProvisionCheckTask` + 零参数入口 `alice:craft_station_provision_check`：
   前提（站点在、**当前无网格**）→ 装 → **关掉再开菜单**（上游在菜单构造时才按升级建容器）→
   **用能力验证**（`GridDiscovery` 必须认出 ≥3×3）→ **取回** → 能力必须消失、物品必须回到背包 →
   `no_block_writes`（本夹具只搬物品）。**装配验证失败会回滚**并记 `rollback_clean`。
4. **电池 30 → 31 项**（`craft_station_provision`，自带场景 + teleport；**模组不在/站点不在 ⇒ SKIP**）。
5. 场景 `alice_test:craft_tab_course` **不再给玩家发升级**（用户指出：那颗升级不该塞给玩家）——
   装配现在由 bot 自己做。

**两个刻意的"不猜"**：① 落点不猜（交给菜单，用**结果**验证）；② 地址不猜（查容器身份 + 容器槽号）。
**一条纪律**：装配**不是合成任务的一部分** —— 合成任务只接受"已经具备能力"的站点；没装就如实报
`station_not_provisioned`（未来接入时的码），绝不偷偷替用户改配置。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `9eca7b4a…`）。
**未验证（客户端）**：① 上游是否接受 `QUICK_MOVE` 把升级送进升级槽（不接受就走兜底路径）；
② 关掉再开菜单后 3×3 是否真的出现（`provision_verified`）；③ 取回后能力是否消失（`deprovision_verified`）。
**下一步 C**：模组站点**真合成**（往 `#64..#72` 摆料、从 `#73` 取产物），并实测两维语义
（"Shift 右击将成品放入容器/玩家物品栏"那个开关、以及"材料来源=容器优先"）。

### D-195：阶段 3-A / C = **S1-5b 模组站点真合成**（装一次 → 用 → 拆回）

**用户裁定**：*"不用加这个入口（单向装配命令），现在的测试已经有足够证据了，继续下一步"* ⇒ 直接进 C。
用户同时确认了最终应用场景：**"让 bot 装一次配置，然后就可以一直用了，直到让 bot 拆下升级"** ——
这与现有分层一致：**装配（装/拆）独立成层，合成只读能力**；夹具之所以"装→验证→拆"跑一遍，是因为它是**自检**
（两个方向都要验 + 跑完把世界还原），**不是生产流程**。升级的持久性已由存档实证（`upgradeInventory` 跨会话）。

**`CraftStation.UPGRADE_TAB` 增加数据字段 `provisionUpgrade`**（= `sophisticatedstorage:crafting_upgrade`）：
"这个站点该装什么升级"由**站点描述符的数据**决定，装配层不需要判断站点类名（命令入口按用户裁定**不加**）。

**新增夹具** `CraftStationCraftCheckTask` + 零参数入口 `alice:craft_station_craft_check`：
装（L2）→ 用**发现出来的规格**合成（S1-5a 的 `InventoryCraft.craft(..., spec)`）→ 拆回（建拆同权），
一次右键跑完。**判据一律是世界事实**（产物总量 +1、圆石总量 −8），而**不是**某个原语的自述。

**这一项刻意把两维语义当"测量对象"记进 SUMMARY**（而不是假设）：
| 记录项 | 含义 |
|---|---|
| `shift_click_into_storage` | 那个"Shift 右击将成品放入容器/玩家物品栏"开关的**当前值**（读升级物品 NBT） |
| `product_in_player` / `product_in_container` | 产物**实际**落在哪（两处都数） |
| `primitive_verdict` / `primitive_assumption_mismatch` | `InventoryCraft` 怎么报的、它"产物进玩家背包"的假设有没有被现实打脸 |
| `grid_after_craft` | 合成后页签 9 格剩什么（看它会不会**自动补料**） |
| `materials_consumed` / `product_produced` | 世界事实（判 PASS/FAIL 的依据） |

**电池 31 → 32 项**（`craft_station_craft`，自带场景；模组不在/站点不在 ⇒ SKIP）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `b5a99c1d…`）。**未验证**：客户端这一步。

#### D-195 附注一：C 首测失败 —— **点击原语静默拒绝"上游自管地址"**，还把病因报成 `missing_ingredient`

**客户端事实（17:54:51）**：
```
fixture_gave_materials=true upgrade=1 cobblestone=8（含容器的总量）
provision_verified=true  OK grid=3x3 slots=[64..72] result=73
materials_consumed=false consumed=0      ← 一颗材料都没动
product_produced=false   produced=0
grid_after_craft=-,-,-,-,-,-,-,-,-
primitive_verdict=FAIL:missing_ingredient   ← 但材料明明在背包里
deprovision_verified=true  upgrade_returned=true
```

**根因（我方代码，两处叠加）**：
1. `InventoryCraft.click(...)` 有一句**静默拒绝**：
   `if (slot < 0 || slot >= menu.slots.size()) return false;` —— 而模组站点的 9 格与结果槽
   **建在 `menu.slots` 之外**（地址 64..72 / 73，`menu.slots.size()==63`，D-192 附注三/四）
   ⇒ **每一次摆料与取产物的点击都被吞掉**，材料一颗没动。
   （同源第三例：`MenuSession.click` 也有同样的守卫；那个我早先绕开了，这里忘了改。）
2. `placeGrid` 把"点击被拒"与"缺料"**混成一个失败码**（`missing_ingredient`）⇒ 报了个**反向**的病名，
   白白把排查方向带偏一个回合。

**修法**：
- `InventoryCraft.click`：**只拒绝负数地址**；地址合法性由调用方用"**发现出来的槽位集合**"保证
  （`GridDiscovery.scan`），并在方法注释里写明这次事故。
- 新增 `GridDiscovery.slotByAddress(menu, address)`：**按点击地址取槽位**（`menu.getSlot(address)` 对 64+ 会 IndexOutOfBounds）。
- `placeGrid` 改为**返回失败码**（`missing_ingredient` / `click_rejected` 分开），并在被拒时打印
  `cell / address / 可达槽位数`；`clearGrid` 与 `findInventorySlot` 一并改用发现出来的槽位表
  （顺带把"逐个 getSlot"的 O(n²) 也去掉了）。

**教训**：`menu.slots` **不是**"菜单里所有槽位"—— 这条已经在本会话出现**三次**
（发现器一次、`MenuSession.click` 一次、`InventoryCraft.click` 一次）。
**凡是拿"地址"去点/取槽位的代码，都要先问一句：这个地址在 `menu.slots` 里吗？**
今后新增写入原语时，**守卫一律用"发现出来的槽位集合"，不用 `menu.slots.size()`**。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `81fc53ec…`）。**待客户端复测**。

#### D-195 附注二：C **PASS** —— 模组站点真合成打通；并**实测**出两维语义（产物进容器、不自动补料）

**客户端事实（修复后复测）**：
```
cobblestone_consumed=8  furnace_produced=1        ← 真消耗、真产物
product_in_player=0     product_in_container=1    ← **产物落在容器**（不是玩家背包）
shift_click_into_storage=false                    ← 读到的升级 NBT 开关值
grid_after_craft=-,-,-,-,-,-,-,-,-                ← 网格清空、**没有自动补料**
primitive_verdict=FAIL:result_not_taken           ← 原语按"数玩家背包"判定 ⇒ **假失败**
primitive_assumption_mismatch=true                ← 我预先埋的测量项正好抓到它
materials_consumed=true product_produced=true deprovision_verified=true upgrade_returned=true
no_block_writes=true  verdict=PASS                ← 以**世界事实**判定的 PASS
```
⇒ **"装一次 → 用 → 拆回"全链路通了**（`alice:craft_station_craft_check`），并且两维语义**是测出来的、不是猜的**。

**修法（把"产物口径"变成可注入的一维）**：
- `InventoryCraft` 新增 `ProductCounter`（函数式接口）+ 6 参重载；默认口径仍是**玩家背包**（原版站点，行为不变）；
- `TableCraft.craftWithMenu` 用**站点感知口径**：`玩家背包 + 当前菜单容器`
  （对原版工作台容器里不会有产物 ⇒ 与历史一致，回归由电池证明）。

**一条待查事实（故意不下结论）**：开关读到 `shiftClickIntoStorage=false`（按源码语义="放进玩家物品栏"），
**但产物实际进了容器** ⇒ 说明那个升级 NBT 开关**不是（唯一）决定因素**。
源码里另有一个**玩家侧**设置（`gui.sophisticatedcore.settings.buttons.shift_click_open_tab.on/off`
= "Shift Click **Open Tab** First" / "Into Inventory First"）⇒ **假设**：产物去向由它（或两者共同）决定。
**这一条是假设，不是结论** —— 将来若要精确控制去向，先读那个玩家侧设置（或直接**按站点实测**记录去向，这已是当前做法）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `b028df7d…`）。
**回归风险**：`TableCraft` 的口径改动只影响"产物计数"，由电池 `craft_table`/`craft_station` 两步复测即可确认。

#### D-195 附注三：32 项电池暴露**两个独立缺陷**（都已修）

**电池事实**：`(30/31 绿)` —— `craft_table=FAIL reason=crafted_furnace`、`craft_station=FAIL reason=placed_table_craft`、
`craft_station_provision=FAIL reason=menu_open_failed`；其余（含新的 `craft_station_craft`）全绿。

**缺陷 ①（我上一步引入的回归）：产物口径把"结果槽预览"算成了产物**
```
[CraftTableCheck] crafted_furnace=FAIL product+1 FAIL:result_not_taken produced=0
```
产物明明 +1（夹具自己的计数说 +1），原语却说"没取到"。根因：`TableCraft` 的新口径 = "玩家背包 + 菜单容器"，
而**结果槽里放的是合成预览**（摆好料它就已经是产物）⇒ `before=1（预览）→ after=1（产物进背包、预览消失）`
⇒ 差值为 0。**修**：新增 `StationProvision.countLandedProduct(...)` —— 数"玩家背包 + 容器里**除结果槽与合成网格以外**的槽位"
（结果槽=预览、网格格=材料，都不算产物）。

**缺陷 ②（偶发，但必须留证据）：服务端那次没开菜单**
```
[Menu] use_item_on target=46,64,306 result=SUCCESS      ← 历次成功都是 result=CONSUME
[Menu] failed code=menu_open_timeout ticks=21
```
`SUCCESS` 与 `CONSUME` 的差别说明**服务端没消费这次交互**（模组没开菜单）。**修**：
① 失败时**如实打码**（`session.failure()` + 当前 `containerMenu` 类名 + 已重试次数）——
   原来只打了 `reason=see log`，白费一次排查；
② **重试一次**（冷却 10 tick、`phaseTicks` 归零重计时）—— 偶发状态不该把整步判死，`open_retries` 会记进 SUMMARY。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `4217b7ec…`）。**待客户端**：32 项电池复测。

#### D-195 附注四：**前提被污染** —— `setblock` 放同种方块会短路，升级跨场景存活

**电池事实（第二轮 32 项）**：`(31/32)` —— 上一步两个缺陷都修好了（`craft_table=PASS`、`craft_station=PASS`），
只剩 `craft_station_provision=FAIL reason=premise_not_provisioned`（夹具要求"本来没有合成能力"，
结果开菜单就看见网格）。

**根因**：场景函数用 `setblock <pos> sophisticatedstorage:chest` 重建箱子 —— 但当那一格**已经是同一种方块**时，
原版 `setblock` 会**短路**（方块状态相同 ⇒ 直接返回），**方块实体连同升级槽里的合成升级一起留着**。
⇒ 上一轮/手动装的升级**跨场景存活**，把"本来没有合成能力"这条前提弄脏。
（这也是夹具第二次因为"世界里的既有状态"而误判 —— D-187 §6.9.1 的原话就是"夹具必须自己摆前提"。）

**修法（两处，互为保险）**：
1. **场景**：`setblock <pos> minecraft:air` → 再 `setblock … chest`（强制重建方块实体 ⇒ 干净的容器）；
2. **夹具自摆前提**：
   - `CraftStationProvisionCheckTask`：开菜单后若发现**已被装配** ⇒ 用同一个协议（`moveOutOfContainer`）
     **自己拆回干净**再继续，并如实记 `premise_cleaned=true`（清理失败才判红）；
   - `CraftStationCraftCheckTask`：`installMove` 开头若容器里已有升级 ⇒ 先取回再装，
     并把数量基线 `upgradeBefore` 重算（否则"装进去的那一颗是不是我们这颗"说不清，数量断言会假失败）。

**教训（值得单记）**：**"重建场景"不等于"状态是干净的"** —— 方块实体的持久数据会跟着方块存活；
`setblock` 同种方块是**短路**而非重建。夹具的世界前提必须**自己检查并自己摆平**，不能假设场景函数清过场。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `2efb2d1e…`，场景函数已更新到客户端）。
**待客户端**：32 项电池复测。

#### D-195 附注五：**32/32 全绿** + 一个新机制发现（拿升级右键 = 物品自己装进去，GUI 不开）

**客户端事实（第三轮 32 项电池）**：**`(32/32) ticks=3592 → PASS`**，`K4=OK(goal_not_standable=0
final_segment_not_standable=0 写入类例外=96)`，无 SKIP、无 FAIL、无崩溃。

**新机制（由 `premise_cleaned=true` 反推出来，值得单记）**：
`craft_station_provision` 那一轮**清理了一颗"残留升级"** —— 而上一轮的 32 项电池里没有任何步骤会留下它。
来源是**夹具自己开菜单那一下**：**拿着合成升级右键容器时，升级物品自己会把它装进容器**
（物品驱动的装配路径，"Right Click To Add"），于是那次右键**不打开 GUI**。
这一条同时解释了上一轮的怪现象：
```
[Menu] use_item_on target=46,64,306 result=SUCCESS   ← 持升级右键 ⇒ 被物品消费 ⇒ 没开菜单
[Menu] failed code=menu_open_timeout ticks=21
```
而历次成功打开都是 `result=CONSUME`（手里没有升级时）。

**修法（消除不确定性）**：`StationProvision.clearHeldUpgrade(bot, upgrade)` ——
**开站点菜单前先把选中的快捷栏槽换成空格**（顺手同步主手，客户端观感一致）；两个夹具的开菜单处都调用。
这样"打开菜单"与"装升级"就是**两条明确的路径**，而不是靠运气：
- **路径 A（我们的协议）**：空手右键 → 菜单打开 → `QUICK_MOVE` 装入 → 关掉再开 → 用能力验证；
- **路径 B（模组的物品驱动）**：手握升级右键 → 物品自己装进去（**不开 GUI**）——
  这也是一条**合法**的装配路径，将来若要利用它，同样要靠"重开菜单 + 能力验证"来确认，不能假定成功。

**阶段状态**：**阶段 3-A 的"工作站"这一大块收口** —— 通用发现（三种站点）、工作站可切换、只读探针、
执行接入发现器（B）、装配层装/拆（A）、模组站点真合成（C）全部客户端验证通过，32 项电池全绿。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `e8082b1b…`）。**待客户端**：32 项电池复测（应仍全绿）。

### D-196：阶段 3-A / **A4 熔炉** —— "按时间工作"的另一种执行形状（认炉子 / 放料 / 等烧 / 不留半成品）

**为什么它是独立一级**：合成是"**点一下就出**"，熔炉是"**放料 → 等 → 取**"：多了**时间**与**燃料**两个维度，
失败还可能**留半成品**（料在炉里、产物没出来）。所以它需要自己的发现器、自己的执行节奏、自己的清理纪律。

**新增 `task/craft/FurnaceStation`（只读发现 + 动作原语）**，判据与合成网格同一套思路（**零写死下标/类名**）：
1. 菜单里**恰好占 3 格**的容器 ⇒ 输入/燃料/输出按**该容器自己的槽号 0/1/2**（原版语义，模组普遍沿用）；
2. 菜单里存在 **`ContainerData` 类型的字段**（**按类型找**，不按名字 —— vanilla 字段名在生产环境是 SRG 名）
   ⇒ 这是"它**会按时间工作**"的证据，把机器菜单与合成菜单区分开；
3. 候选**多于一个**就如实拒绝 `ambiguous_furnace`；没有 3 格容器 `no_furnace_slots`；缺 `ContainerData` `no_progress_data`。

**新增夹具** `CraftFurnaceCheckTask` + 零参数入口 `alice:craft_furnace_check` + 场景 `alice_test:furnace_course`：
认炉子（过程证据：`progress=x/200`）→ 放 1 圆石 + 1 煤 → **真 tick 等它烧**（预算 420 tick，原版 200/个）→
取石头 → 断言**世界事实**：`stone+1`、`cobblestone-1`、`input_left=0 output_left=0`（**不留半成品**）、`no_block_writes`。
**超时路径**：把输入**取回背包**（`timeout_input_returned`）并如实报"没烧成"，不假装成功。
过程事实也如实记录：`fuel_burn_ticks`（`ForgeHooks.getBurnTime` 给的，不猜）、`smelt_recipe`、`smelt_ticks`、
`progress_at_done`。

**电池 32 → 33 项**（`craft_furnace`，自带场景）。

**边界**：本轮只做**原版熔炉（方块型）**；"熔炉升级"（精妙存储/背包里的熔炼页签）是同一形状的**菜单型**站点，
按 C 的经验应当复用同一发现器（它同样有 3 格 + `ContainerData`），留作 A4b。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar 见下）。**待客户端**。

### D-197：**回归电池分档管理**（用户要求："只测必要基础项 + 当前主线项，每次新场景/换主线都更新，由 AI 管理"）

**用户原话**："我觉得需要做电池回归管理了，现在电池过程太长了，电池的回归项只测必要基础项和当前主线项，
每次有新场景，更换主线都要更新，由你来管理。"

**做法（配置一处、说明一处、自校验兜底）**：
1. **分三档**：`BASELINE`（必要基础 = 坏了就不能信任 bot 的任何动作）/ `MAIN`（当前主线 = 阶段 3-A 工作站+熔炉）/
   `EXTRA`（已验收、与主线无关或耗时/需长期观察）。默认只跑 BASELINE+MAIN = **CORE**。
2. **配置唯一入口**：`RegressionBatteryTask.CURATION`（一张**按名字的归属表**，33 项全部登记）。
   刻意不改成"给每个步骤加参数"：一处可见、便于 review。
3. **自校验（防漂移）**：构造后首次 tick 检查"有步骤没归属 / 有归属没步骤" ⇒ 直接计入判红条件，
   SUMMARY 里打印 `PROFILE=core baseline=13 main=10 extra_skipped=10 (23/23) …`；
   不一致时额外 WARN（"文档说测了、其实没测"这种事不许悄悄发生）。
4. **入口**：物品 `alice:regression_battery` = CORE；命令 `/alice battery core|full|list`
   （`list` 打印归属表，用户能一眼看到"电池里有什么、为什么"）。
5. **说明书**：`docs/BATTERY_CURATION.md` —— 三档含义、当前归属表、**六条维护规则**（新增场景进 MAIN；
   验收通过且退场则移 EXTRA；新破坏性路径进 BASELINE；换主线重写 MAIN；每次改动在本文档留记录；
   归属表与代码不一致即判红）、历史表。

**分档结果**（33 → CORE 23）：BASELINE 13（pathing / write_budget / mine_regression / mine_job / lumber_job /
transfer / clear_guard / clear_retry / scaffold / partial_search / capability_gate / tool_supply / recoverability）、
MAIN 10（craft_check / craft_action / craft_table / craft_station / craft_probe_inventory / craft_probe_table /
craft_probe_upgradetab / craft_station_provision / craft_station_craft / craft_furnace）、
EXTRA 10（lumber_failure / region_maintain / decision_contract / decision_trace / llm_contract / permission_gate /
pickup_gate / collect_job / recipes_dump / event_thresholds）。

**顺带修**（A4 报告瑕疵）：`FurnaceStation` 读 `ContainerData` 的下标原本写成 0=进度/1=总时长/2=燃烧，
而原版约定是 **0=剩余燃烧 1=本次燃料总长 2=进度 3=配方总时长** ⇒ 报告恒为 `progress=0/0`；
匿名 `ContainerData` 也输出空类名。已修（并注明：**这些只是过程证据，判成功一律看世界事实**）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `1aef94f3…`）。**待客户端**：CORE 电池跑通（23 项）。

#### D-197 附注一：CORE 首跑（23 项）**全绿但被自校验误判 FAIL** + 熔炉复位（用户提醒）

**客户端事实**：`(23/23) ticks=2583`、`K4=OK(写入类例外=51)`、`baseline=13 main=10 extra_skipped=10` ——
**每一步都 PASS**，可整轮却 `→ FAIL`。原因出在我自己的**防漂移自校验**：
```
[Regression] 电池归属表与实跑项不一致： phantom=[collect_job, region_maintain, permission_gate, recipes_dump,
 event_thresholds, decision_trace, decision_contract, lumber_failure, pickup_gate, llm_contract]
```
那 10 项正是 CORE **主动跳过的 EXTRA** —— 我却拿"裁剪后的步骤集"去比对归属表，于是把"按档跳过"误判成
"文档说测了、其实没测"。**修**：自校验一律跟**裁剪前的全量步骤集**比（`allStepNames` 快照）。

**时长对照**：FULL 33 项 `ticks=3527` → CORE 23 项 **`ticks=2583`（−27%）** ⇒ 用户"电池太长"的诉求达成。

**用户提醒（原话）**："熔炉记得重置，不然会一直处于燃烧状态" —— 完全正确：煤能烧 1600 tick，
而一次烧炼只用 200 ⇒ 烧完还剩 ~1400 tick 的"余焰"；而且**光把燃料取走并不会灭**
（`litTime` 是方块实体自己的状态）。⇒ 夹具新增 **CLEANUP 相位**：
1. 把炉内**剩下的东西全取回**（燃料/输入/产物各自 shift-click 回背包）——建拆同权的精神；
2. **复位方块**：`setblock air` → `setblock furnace`（与场景同款做法，**立刻熄灭并清空**），
   并如实记 `burn_left_ticks_before_reset`（余焰事实）、`leftovers_returned`、`furnace_block_reset`、`furnace_reset=true`。
   这是**夹具自己的场景管理**（与 A3b 挪动场景工作台同规格），不是生产写入 ⇒ `no_block_writes` 的含义不变。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `3312608d…`）。**待客户端**：CORE 复跑（应 `(23/23) → PASS`）。

#### D-197 附注二：CORE 复跑 **`(23/23) ticks=2616 → PASS`** + 熔炉复位实测生效

**客户端事实**：`PROFILE=CORE baseline=13 main=10 extra_skipped=10`、`(23/23) ticks=2616 → PASS`、
`K4=OK(真异常 0 / 写入类例外=56)`；熔炉收尾：
```
[CraftFurnaceCheck] 复位熔炉 pos=46 64 306 burnLeft=1398 → 重建方块=true
[CraftFurnaceCheck] no_half_products=true  furnace_reset=true
```
`burnLeft=1398` 正好印证用户的观察（煤 1600 − 一次烧炼 200 ≈ 1400 tick 余焰）⇒ **复位是必要的**，现在夹具自己熄灭并清空。

**分档成效**：FULL 33 项 `ticks=3527` → CORE 23 项 **`ticks=2616`（−26%）**，且 CORE 覆盖
"必要基础 + 当前主线"。A4 熔炉（原版方块型）客户端 PASS。

**下一步候选**（都属阶段 3-A 剩余）：
- **A4b 菜单型炉子**（精妙容器/背包里的"熔炼升级页签"）：应当**复用** `FurnaceStation`（同样 3 格 + `ContainerData`），
  顺带把"熔炉升级"这条用户最初提到的兼容形态补齐；
- **A5 决策层接线**：`GoalAction.Craft`（词汇表 + 严格解析 + 把"可做的合成/熔炼路线与站点"作为**确定性事实**喂给候选菜单，
  LLM 只选），并按裁定"任务层失败向上传递"接好接口 —— 这是"LLM 只选目标"落地的最后一块。

### D-198：阶段 3-A / **A4b 菜单型炉子**（"熔炼升级页签"）—— 同一发现器的第二条证据路径

**调查事实**（装的 Core 1.5.1）：精妙存储的"熔炉升级"物品 id 是
`sophisticatedstorage:smelting_upgrade`（tooltip *"Smelting in an upgrade tab"*）；它的页签内容是
`CookingUpgradeContainer` → `CookingLogicContainer`，**3 个烹饪槽**（input/fuel/output 沿用槽号 0/1/2），
但**没有 `ContainerData` 字段** —— 进度是**方法自述**：`getCookTimeTotal()` / `getCookTimeFinish()` /
`getBurnTimeTotal()` / `isCooking()` / `getCookingSlots()`。

**给 `FurnaceStation` 加第二条证据路径**（与 `GridDiscovery` 的"上游自述"同一套路，**只认方法名形态 + 自校验**）：
① `ContainerData` 字段（原版形态，下标 0/1/2/3 有约定）；② 可达对象自述了烹饪进度方法族。
两者都拿不到才 `no_progress_data`（**不猜**）。判成功与否仍然**一律看世界事实**，进度只是过程证据。

**站点模型**：同一个容器 + **不同升级** = 不同能力 ⇒ 新增描述符 `CraftStation.COOKING_TAB`
（`provisionUpgrade = sophisticatedstorage:smelting_upgrade`）。站点 = "容器 + 哪一种能力"，数据驱动，不判断类名。

**夹具复用**：`CraftFurnaceCheckTask` 加 `upgradeTab` 模式（**不另写一套**）：
页签模式下站点 = 精妙容器（按方块 id 形态找）、先 `StationProvision` 装升级（会先清掉旧装配）、
`discover` 成功即 `provision_verified`（用能力验证装配）、输入换沙子、收尾**拆回升级**
（烧炼状态跟着升级物品走 ⇒ 拆掉即等于熄灭）。零参数入口 `alice:craft_cooking_check`。

**电池 33 → 34 项**（`craft_cooking` 进 MAIN ⇒ CORE 24），归属表与 `docs/BATTERY_CURATION.md` 同步更新
（这正走了一遍 D-197 立下的维护规则）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `2d7cf69e…`）。**待客户端**。

#### D-198 附注一：A4b 首测失败 —— 模组烹饪页签**不是"一个容器占 3 格"**（已修）

**客户端事实**（18:53:28）：
```
[CraftFurnaceCheck] station_found=true furnace=46, 64, 306   mod_present=true upgradeId=…:smelting_upgrade
[Provision] QUICK_MOVE 送出 upgrade=sophisticatedstorage:smelting_upgrade fromSlot=56
[CraftFurnaceCheck] provision_verified=false FAIL:no_furnace_slots 菜单里没有"恰好 3 格"的候选容器
```
⇒ **装配本身成功了**，是**发现器认不出**：模组的烹饪页签把 3 个槽放在**各自的物品处理器**上（不是一个 3 格容器），
所以"同一容器恰好 3 格"这条判据不成立。

**修法（与"合成网格"完全同一套路：再加一条上游自述路径 + 行为验证）**：
1. **路径 ③**：可达对象自述 `getCookingSlots()` 且返回**恰好 3 个 Slot** ⇒ 采用（这正是 `CookingLogicContainer` 的接口）；
2. **不信任自述顺序**：用 **`mayPlace` 行为探针**判定哪格是哪个 ——
   **结果槽**：煤与圆石**都不收**；**燃料槽**：收煤、不收圆石；**输入槽**：收圆石。
   三者都判得出来才用探针结果，否则如实回退到上游顺序并在 note 里标 `upstreamOrder(fallback)`；
3. 日志新增 `[Furnace] 认出炉子 by=… assignBy=…`（**用了哪条路径、怎么分的格**永远可见）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `c94a2ee9…`）。**待客户端**复测。

#### D-198 附注二：A4b 真因 = **自述者在宿主"下面一层"**（遍历深度，不是判据形态）

**客户端事实**（19:02 `[FurnaceDiag]`）：`menuSlots=63`、`reachableObjects=167`，其中
`obj=CookingUpgradeContainer methods=[getSmeltingLogicContainer]` **看得见**；槽位表里 3 格烹饪槽**确实可达**
（`#64` 收圆石不收煤 = 输入、`#65` 收煤不收圆石 = 燃料、`#66` 两样都不收 = 输出，全部**未登记在 `menu.slots`**）；
但 `discover=FAIL:no_furnace_slots` 且**没有 `[Furnace] 认出炉子 by=…`**。

**真因（字节码核对，不是推测）**：`CookingUpgradeContainer.<init>` =
`new CookingLogicContainer<>(player, supplyFromWrapper(w -> w.getCookingLogic()), slots::add)`。
自述者（`CookingLogicContainer.getCookingSlots()` 恰好 3 格，且自带 `getCookTimeTotal/getBurnTimeTotal/isCooking`）
是宿主的**私有字段** ⇒ 比"菜单 → 字段 → 字段"的 2 层上限**深一层**：**宿主可见、自述者不可见**。
⇒ 症状是"连对象都没找到"，而不是"找到但判据不成立"。

**修法（与 D-192 的"到哪一层去问"同一课）**：候选集只对**槽位宿主**（`GridDiscovery.Scan.owners()` = 产出过槽位的上游容器）
**再展开一层字段**，并**自校验**才采用（`getCookingSlots()` 必须给出恰好 3 个 `Slot`）。
**不做全局加深**：世界对象（`ServerLevel`/`ServerPlayer`）也在这个图里，加深会把遍历炸开；而宿主是上游容器对象，展开是安全的。
输入/燃料/输出**照旧由 `mayPlace` 行为判定**，不采信自述顺序。

**顺带补证一条既有事实的机制**（D-192 附注三"点击地址用 `slot.index`"）：上游 `StorageContainerMenuBase`
重写了 `doClick`：`slotId >= getTotalSlotsNumber()`（= `menu.slots.size()` + **`upgradeSlots.size()`**，页签槽位挂在 `upgradeSlots` 里）
才丢弃，其余用**被重写的 `getSlot(int)`** 解析（`getSlot(i≥slots.size())` → `upgradeSlots.get(i - slots.size())`）
⇒ 页签槽位地址（本次 `64..66`；C 那次 `64..73`）**合法**且落到正确槽位上。这就是 C 能用 `menu.clicked` 真合成的原因。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `502ec5f2…`）。**待客户端**：`alice:craft_cooking_check` + CORE（应 24/24）。

#### D-198 附注三：A4b 能力已验证 ✓；**夹具相位函数"要跨 tick 就必须换相位"**（新纪律）

**能力结论（客户端世界事实，19:39:55–19:40:05）**：`provision_verified=true`（路径③ 自述槽位 + `mayPlace` 分格）、
`input_and_fuel_placed=true`、`smelted=true product+1`、`input_consumed=true input-1`、`no_half_products=true`
⇒ **精妙"熔炼升级页签"型炉子：能装、能放料、能真烧、能取走、炉内不留东西**。A4b 到此收口。

**新缺陷（夹具控制流，与能力无关；用户报"一直开关箱子开关个不停"）**：
`cleanupFurnace()` 是**每 tick 被调用的相位处理函数**，而 A4b 收尾要跨多 tick（开菜单 → 等 OPEN → 取回升级）。
原先它 `closeSession(...)` 后把活交给 `deprovisionAndFinish()` 却**不换相位** ⇒ 下一 tick 又进 `cleanupFurnace()`
把刚开的菜单关掉 ⇒ `closed(furnace_cleanup)` ↔ `use_item_on` 每 ~50ms 一对，永远收敛不了，SUMMARY 从不打印。

**纪律（本轮教训，写进交接）**：**相位处理函数里凡是要"跨 tick 等状态"的分支，必须 `advance(下一个相位)`；
在同一相位里做"关菜单 + 再开菜单"＝每 tick 互相抵消的活锁。** 同理：相位函数要么**幂等可重入**（每 tick 跑一次也收敛），
要么**只跑一次**（换相位）。`provision()` 当初就是这么做的（`provisionAttempted` + `phase = OPEN`），cleanup 漏了。

**顺带修的噪声**：夹具每 tick 调发现器 ⇒ 383 行 `[Furnace] 认出炉子`（改为每 40 tick）；
cleanup 在菜单已关时对玩家背包菜单跑发现器 + 拿旧地址点击抛 `ReportedException`（加菜单守卫，如实记 `cleanup_station_open=false`）。

**等级**：能力 = **WINDOWS_CLIENT 已验证**；夹具修复 = IMPLEMENTED + COMPILES，**待客户端复测**（jar 见交接文档）。

#### D-198 附注四：A4b **收口**（客户端 PASS + CORE 24/24）

`alice:craft_cooking_check` 单跑 `verdict=PASS`（`smelt_ticks=207`、`product_delta=1`、`cobblestone_delta=-1`、
`input_left=0 fuel_left=0 output_left=0`、`leftovers_returned` 三项全 true、`deprovision_moved=true`、
`upgrade_returned=true`、`no_block_writes=true`）；CORE 电池 `(24/24) ticks=2809 → PASS`（含 `craft_cooking=PASS`
与 `craft_furnace=PASS` 回归）。附注三的死循环在同轮复测中不再出现（`furnace_cleanup` 3 次、`ReportedException` 0 次）。

**A4b 的完整教训（三条，已各自落到代码/文档）**：
1. **判据别只看形态**：上游把 3 格分挂在**各自的物品处理器**上 ⇒"同一容器恰好 3 格"必然认不出，改问**上游自述**（`getCookingSlots()`）；
2. **"找不到对象"先怀疑遍历深度**：自述者在宿主的**私有字段**里（比 2 层上限深一层）⇒ 只对**槽位宿主**扩一层，不全局加深；
3. **相位函数要么幂等、要么换相位**：跨 tick 的分支留在原相位 = 每 tick 互相抵消的活锁（关菜单 ↔ 开菜单）。

### D-199（A5）决策层接线：`GoalAction.Craft` —— "选项由确定性层生成，LLM 只选择"

**做了什么**（阶段 3-A 收口）：① 词汇表新增 `craft`（**严格解析**：只能在**候选菜单的可做清单**里选 +
当前站点做不了就拒绝 + `count` 夹取）；② 候选菜单新增 `craftable` 清单（**只读**扫描运行时配方表，
列出"以当前背包持有的材料就能做"的产物 + 工作站 + `can_use=`，有界且**如实标 truncated**）；
③ 执行走**唯一入口**：`JobRequest.CRAFT` → `JobLauncher` → 新 `job/craft/CraftJob`（薄：只读查询 → 开**玩家选中的**站点 →
按需装配 → 网格合成/3 格烧炼 → **只看世界事实判成功** → 失败清场）。

**四条边界（不许悄悄动，都是既有裁定的落地）**：
1. **LLM 只选目标**：它能引用的只有清单里的 id；越界/站点做不了都在**解析层**如实拒绝并回读；
2. **不凭空给物品**：`JobLauncher` 对 `CRAFT` **不发料**，缺料如实报 `missing_ingredients`；
3. **站点由玩家切换**：请求里**不带站点**（不自动选优）；`can_use=false` 的项照样列出（属于"事实完整"）但会被拒；
4. **装配独立成层**：`CraftJob` 只**按需装上**（走 `WriteReason.STATION_PROVISION` + 容器写入预算），
   **不自动拆回** —— 拆是独立的一层（夹具的"装→验→拆"只是自检纪律）。

**判据**：世界事实（产物 +N / 材料 −N）；原语返回值只用于失败码与过程证据。失败也清场
（网格半成品由 `InventoryCraft` 契约收回；烧炼超时取回输入；菜单一律关）。

**电池 24 → 25 项**（`craft_goal` 进 MAIN ⇒ CORE 25 / FULL 35），`docs/BATTERY_CURATION.md` 同步。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `8497ec11…`）；**待客户端**（`alice:craft_goal_check` + CORE 25/25 + `/alice ask`）。

#### D-199 附注一：**词汇表漏登记 `craft`** —— prompt 是能力的一部分（已修）

A5 实现完后自查提示词才发现：动作词汇表（`GoalDirector.VOCABULARY`，system prompt 主体）**没有 `craft`**
⇒ LLM 根本不知道这个动作存在，`/alice ask` 路径上 A5 **等于没接**（只有确定性夹具能跑）。
修：词汇表补 `craft` 条目 + 规则行（item 必须来自 `menu` 的 `craftable` 且 `can_use=true`），
user prompt 的"注意"段也补上"craftable 清单 / `craftable_truncated` 的含义"。

**教训（登记为纪律）**：新增任何动作，**必须同时改三处**：① `GoalAction` 解析；② `VOCABULARY`（system prompt）；
③ user prompt/状态的说明文字。只做①=能力存在但没人会叫它。

### D-200：`trigger` 进状态 + 操作者直连指令通道 + `/alice region clear`（含**临时裁定**登记）

**背景**：用户要求 ① 把 `trigger`（"为什么现在问我"）写进给 LLM 的状态；② 测试连通性时**不想靠改菜单**，
要能**直接管理"问 LLM 的那句话"**、不询问、直接让 LLM 执行；③ `/alice region` 加清除指令给夹具收尾。
并按用户新明确的原则（PLAYBOOK §5.0b）**登记临时性**。

**已实施**：
1. **`trigger` 进快照**（`DecisionSnapshot.build/buildPrompt` 增 trigger 参数；`GoalDirector.fire` 传入）——
   原先它只进日志/聊天，LLM 只能从 `task.lastTerminal` 猜意图 ⇒ 它答 `no_op` 时无法判断是
   "上下文说别做"还是"没告诉它要干什么"。**这是补齐输入契约，不是放宽约束**（判定为**最终决策**）。
2. **`/alice instruct <原话>`**：user prompt = 操作者原话（"直接执行它"），解析走
   `GoalAction.parse(..., directed=true)`：`craft` 的 item **不在可做清单里也放行**（只 warn 记录），
   且 **仍然只走既定执行入口**（`execute()` → `BotManager.assignJob`）；自动触发路径**照旧**按
   "只能从菜单选"校验。结果打一行 `[Goal] directed_result raw=… → …`。
3. **`/alice region clear`**：清掉已选定区域（夹具/测试收尾用；区域是玩家划的，只由玩家显式清）。

**⚠️ AI 的评价（用户已授权评价其决策）**：第 2 条**削弱了"LLM 只能从菜单里选"这条 A5 红线**——
直连通道给了 LLM 一条"绕过菜单校验仍能执行"的路。我的判断：**作为连通性测试通道可以接受，但必须限域与回收**，
因此实现上做了三件事：只经命令入口（不参与自动触发）、单独日志前缀、**不新增执行旁路**。
**登记为临时裁定**：`（临时）` 直连通道放行菜单校验；
**复核触发条件 = A5 的 LLM 路径验证通过（或发现它在生产触发里被用到）⇒ 立即二选一**：
(a) 回收 `/alice instruct`，或 (b) 加配置闸门（如 `LlmConfig.allowDirected`，默认关）并写进红线说明。

#### D-200 附注一：直连指令**被自己的清空语句擦掉**（"让它做工作台，它却发了伐木指令"）—— 已结构性修复

**客户端事实**（20:36，jar `21ab912c…`）：`/alice instruct 用你词汇表里的 craft 动作做一个工作台` 之后
```
[Goal] decision_action trigger=operator latency=4153ms raw={"action":"start_job","kind":"region_lumber","target":"region:saved",…} → StartJob(REGION_LUMBER …)
[Goal] execute action=start_job ok=true trigger=operator
```
**没有** `[Goal] directed_result …` 行 ⇒ `trigger=operator`（入口走到了）但**发出去的是普通决策 prompt**，不是操作者指令。

**根因（我的 bug，非 LLM 问题）**：直连指令靠 `State.directedPrompt` 这个**可变"邮箱"**在 `instruct()` 与 `fire()`
之间传参，而 `fire()` 里写成"**先 `= null` 再使用**"⇒ 指令被自己擦掉；同时 `pollResult` 也靠同一字段判
"是否直连模式"，于是**模式判定一起失效**（所以连 `directed_result` 都不打）。LLM 收到的就是普通决策请求，
在"砍树剧情"上下文里选 `region_lumber` 是**正确行为** —— 错的不是它。

**修（按"同类问题第二次 ⇒ 不做补丁，做结构修"的纪律）**：
1. **prompt 改为显式参数**：`fire(bot, state, trigger, directedPrompt)`；`directed` 由"是否传了 prompt"直接推出，
   **删掉可变邮箱字段**（`state.directedPrompt` 归零）⇒ 这一类"状态在两次调用之间被踩"的 bug 结构上不再可能；
2. `state.pendingDirected` 只作为"跨发请求→收回复"的标记（`fire` 里设置，`pollResult` 读）；
3. **可见性**：`[Goal] decision_request trigger=… mode=directed|normal model=…` —— 以后"指令到底发出去没有"一眼可判；
4. `instruct()` 的状态快照也带上 `trigger="operator"`（与 D-200 的 trigger 字段配套）。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `21ab912c…`）；**待客户端复测**（期望 `mode=directed` + `directed_result` 行）。

#### D-200 附注二：直连通道验证通过 + **临时裁定复核结论**

**验证**（20:39）：`mode=directed` 明确出现在请求行 ⇒ 指令真的送到；LLM 回 `{"action":"craft","item":"minecraft:crafting_table","count":1}`
⇒ 解析成 `Craft(…)` ⇒ `execute action=craft ok=true` ⇒ `[CraftJob] … 世界事实 product 0→1 ⇒ 达成`。
**A5 的 LLM 路径成立**（用户确认符合预期）。

**复核结论（D-200 登记的复核触发条件已满足）**：`/alice instruct` **保留**，并把"放行菜单校验"从
"临时让步"升格为**有名字、有范围的例外**：
> **红线例外（唯一一处）**：`"LLM 只能从菜单里选"` 的唯一例外 = `/alice instruct`（操作者直连测试通道）。
> 约束：① 只由玩家命令显式调用；② 自动触发路径（终态/事件/空闲/维生）**永不**进入 directed 模式；
> ③ 每次都用 `[Goal] decision_request … mode=directed` + `[Goal] directed_result raw=…` 留痕。

**为什么保留而不是回收**：它今天一次性证明了它的价值——正是它把"**指令没送达**（我的状态传递 bug）"
与"**LLM 不听话**"分开；若只有菜单式测试，这两种故障会长得一模一样。
**可选加固（用户定）**：若要更保险，可加 `LlmConfig.allowDirected`（默认 false）——代价是默认情况下这条诊断路要手动开。

### D-201：阶段 3-A 收口复盘 + 电池整理（CORE 25 → **17**）

**触发**：阶段 3-A（A1–A5）全部客户端验证完成；用户同意"收口复盘 + 电池整理"。按 `BATTERY_CURATION.md` 规则 2/4。

**整理口径（唯一判据：这一类机制是不是"只此一步覆盖"）**：
- **留在 MAIN（4）**：
  | 步 | 只此一步覆盖的机制 |
  |---|---|
  | `craft_check` | 只读配方查询层（最便宜的一步） |
  | `craft_goal` | **A5 端到端**（可做清单 + 严格解析 + 生产路径 `CraftJob`）；顺带覆盖 A2/A3/C 的**同一套**发现器与执行器 |
  | `craft_furnace` | **方块型**熔炉：`ContainerData` 进度路径 + 炉子自复位（重建方块熄灭） |
  | `craft_cooking` | **菜单型**炉子：上游自述 3 格 + **未登记槽位地址**（`slot.index ≥ menu.slots.size()`）+ 按需装配 |
- **退 EXTRA（8，FULL 仍全覆盖）**：`craft_action`、`craft_table`、`craft_station`、`craft_probe_inventory`、
  `craft_probe_table`、`craft_probe_upgradetab`、`craft_station_provision`、`craft_station_craft`
  —— 它们覆盖的**机制**分别被 `craft_goal`（网格合成/发现器）、`craft_cooking`（装配）、
  BASELINE 的 `write_budget`/`clear_guard`/`clear_retry`/`scaffold`（写入预算 + 建拆同权）覆盖。
- **BASELINE 不动（13）**：规则 3 说的是"新的破坏性路径/写入维度"要进 BASELINE；
  3-A 的容器写入维度已由既有 `write_budget` 守卫，不重复占默认预算。

**当轮 CORE 项数 = 13 + 4 = 17（FULL 35）**。`CURATION` 与 `docs/BATTERY_CURATION.md` 同步（自校验要求一致）。

**阶段 3-A 收口复盘（教训，均已落到代码或纪律）**：
1. **判据别只看形态**：模组把 3 格分挂在各自的物品处理器上 ⇒ 问**上游自述**（`getCookingSlots()`）；
2. **"找不到对象"先怀疑遍历深度**：自述者在宿主的**私有字段**里（比 2 层上限深一层）⇒ 只对**槽位宿主**扩一层，不全局加深；
3. **相位函数要么幂等、要么换相位**：跨 tick 的分支留在原相位 = 每 tick 互相抵消的活锁（关菜单 ↔ 开菜单）；
4. **新增动作必须同时改三处**：解析层 + **system prompt 词汇表** + user prompt/状态说明（漏了第 2 处 = 能力存在但没人会叫它）；
5. **可变状态别跨调用传参**：`State.directedPrompt` 这种"邮箱"被 `fire()` 自己擦掉 ⇒ 直连指令退化成普通决策请求；
   改成**显式参数**后这类 bug 结构上不可能（D-200 附注一）。

**仍然开放/未做（不假装已完成）**：机器/未支持配方类型（如实 `machine_recipe_unsupported`）；方块型熔炉**未进站点模型**
（`cookingtab` 之外的烧炼站点还不能被 A5 选中）；`craftable` 扫描成本（≤8000 配方/次，未测极端整合包下的耗时）；
直连通道这一处红线例外（D-200 附注二）；背包型站点、Refined Storage 兼容未做。

### D-202：阶段 3-B 启动 —— 模组机器适配=**可重复实验**；实验对象 Mekanism

**用户裁定**（2026-09-13）：按 D-183 的优先级做**机器适配器**，并且**本次适配的产出要当今后适配其它模组/附属模组的实验模板**。

**因此本轮的第一产出物不是"支持 Mekanism"，而是方法**：新增 `docs/MOD_ADAPTER_PROTOCOL.md`（协议 v1），
把适配拆成**六步流水线**（S0 枚举 → S1 只读发现 → S2 站点发现 → S3 能力闸门 → S4 单机闭环 → S5 收口回收），
每步一个独立验收点、都能随时停下；并规定：
- **顺序铁律**：**只读永远在执行之前**（先读出事实，再谈真消耗真产物）；
- **"读不懂多少"必须一直可见**（跳过 ≠ 做不到，分开报码）；
- **通用 vs 专属分离判据**：进通用骨架必须满足"上游自述 / 至少两个上游共享 / 纯形态且可自校验"之一，否则留在模组专属适配器；
- **实验记录模板**（8 栏固定）、**反模式清单**（不按类名认、不为适配放宽红线、探针必回收…）。

**第一个实验 = Mekanism**（D-183 排序第 1；已知事实：被跳过 1171 条 / 26 类型，`crushing` 210 领跑）。
**第一最小闭环 = S0 + S1（全只读）**：`docs/MEKANISM_FACTS.md`（类型事实表，含"读不懂多少"）
+ 查询层"机器路线"（把 `machine_recipe_unsupported` 变成有出处的 `machine_route`）
+ 零参数探针 `alice:machine_probe`（形态 AI 自决；**验证通过即按 S5 回收或转电池步**）。
S2（站点/槽位发现）**等 S1 读数出来再定范围**——先看类型形态，再决定认哪些机器，避免一上来就猜。

**等级**：协议 = IMPLEMENTED（文档）；S0/S1 = 未开始（下一轮）。


#### D-201 附注一：**回退电池瘦身** —— "隐含前置"必须先变成"显式自证"

**实测**（2026-09-13）：瘦身后 CORE `(14/17) → FAIL`，红项固定为 `craft_furnace`（放料后不燃烧）、
`craft_cooking`（同形）、`transfer`（按前进但不移动）；**重启客户端后复跑仍红**（可复现 ×2）。
判别实验 `/alice battery full`（35 项）= `(34/35)`，**这三项全绿** ⇒ **撤走的 8 步在替它们做前置/清场**。
同一轮还暴露同病第二处：`capability_gate=FAIL reason=foreign_break_attribution`（**ticks=1**）——
它在 FULL 顺序下**第一 tick** 就断言失败，因为它检查的"外来破坏归因"前提被前面的 `region_maintain`/`transfer`
污染；而 CORE 17 里它反而是绿的（那时 `transfer` 失败、写得更少）⇒ 那个绿是**假的**。

**决定（不再一次砍一批）**：
1. **8 项重回 MAIN**（CORE 恢复 25，绿基线先拿回来）；
2. 瘦身的**前置条件**：每个夹具**显式自证前提**（当前菜单 = 玩家自带菜单 / bot 在起点且 onGround /
   用到的方块实体状态已复位 / 账本与**归因检查限定在自己的时间窗内**——这条直接修 `capability_gate`）；
3. 前提落地后**逐条**撤步骤，**每条撤完复跑一次 CORE**，绿灯才继续下一条；
4. 反模式已写进 `docs/MOD_ADAPTER_PROTOCOL.md`（"不许依赖上一步顺便清场；前置必须显式自证"）。

**等级**：IMPLEMENTED + COMPILES（jar 见交接）；**待客户端**：CORE 复跑应回到 `(25/25) → PASS`。

#### D-201 附注二：`capability_gate` 在 FULL 下假红的**根因**（已修）——命令入口 observer 缺失 + 用例不自证前提

**决定性证据**（FULL 那轮的 `[CapabilityGate]` 明细行）：
```
[CapabilityGate] case=foreign_break_attribution result=FAIL before=0 afterForeign=0 afterSelf=0 外来破坏=0
```
`afterForeign=0` ⇒ 那条"**别人**破坏"的伪造事件**根本没被算成外来**。查代码：该用例用
`new BreakEvent(level, pos, state, observer == null ? bot : observer)` 伪造"外来破坏"；
而 **`/alice battery full` 命令把 observer 传成了 `null`**（`BotManager.assignRegressionBattery(bot, null, full)`），
物品入口 `alice:regression_battery` 传的是玩家 ⇒ 命令路径下两条伪造事件的破坏者**都是 bot**
⇒ 外来计数恒 0 ⇒ **第一 tick 假红**。（CORE 用物品入口跑，所以一直"绿"。）

**修（两处，且第二处是纪律层面的）**：
1. **命令入口补 observer**：`/alice battery core|full` 现在把 `source` 的玩家作为 observer 传下去（与物品入口一致）；
2. **用例自证前提**：`observer == null` 时**先报有名字的前提码**（`premise_no_observer`）再返回 ——
   原实现会"静默退化成一个必然失败的变体"，让失败原因长得像"归因坏了"。这正是 §6.50/D-201 附注一
   那条纪律的第一个落地：**前置必须显式自证，不许默认契约具备**。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `21ab912c…`）；
**待客户端**：`/alice battery full` 期望 `(35/35) → PASS`（这是"FULL 顺序下也真绿"的第一次验证）。

#### D-203：夹具**前提自证**机制落地（试点 2 处；D-201 附注一的执行）

新增 `task/FixturePremise`（可复用机制，不是散落的 if）：
- `ownMenu(bot)`：**当前没有别人的容器菜单挂着**（只能是 `InventoryMenu` 或 null）。这条是最容易踩的——
  上游页签槽位建在 `menu.slots` 之外且**地址会重叠**（64..66 既是合成页签格子、也是熔炼页签格子），
  在**错误菜单**上点击会"被接受"但东西落到别处，症状=**料进去了却不烧**。
- `onGround(bot)`：不许在空中开始"放料/点菜单"这类动作。
- 设计要点：返回 `Fact(name, ok, detail)`，夹具直接 `check(fact.name(), fact.ok(), fact.detail())` ⇒
  失败时进 SUMMARY 的是**有名字的前提码**（`premise_*`），而不是含糊的 FAIL。

**试点接线**：`CraftFurnaceCheckTask.discover()`（曾经红的两个炉子步在认炉子/放料前自证菜单身份）、
`CraftGoalCheckTask.prepare()`（只用随身菜单 ⇒ 自证"无别的容器菜单 + 站在地上"）。

**等级**：IMPLEMENTED + COMPILES + 已同步；**待客户端**（随下一次 CORE 一起验，不单独占你的轮次）。
**继续项**：把其余步骤夹具逐条接上（transfer 的站位/前方方块、各 `craft_*` 的菜单身份），
然后才按"逐条撤 + 每条复跑"瘦身。

#### D-203 附注一：**"每步之间"清场**（电池级前提自证 + 修复）——把三步红的机制从根上关掉

在 `RegressionBatteryTask` 的**步骤启动处**（场景函数 + provision 之后、`factory().get()` 之前）加：
1. **每步打印前提事实**：`[Regression] premise step=<name> own_menu=true|false(…) | on_ground=…`（永远可见，便于下次一眼判读）；
2. **前提修复**：`own_menu=false`（上一步残留了容器菜单）⇒ **先 `bot.closeContainer()` 再跑**，并打 WARN。

**为什么放在"每步之间"而不是散在各夹具里**：这是三次实测的共同机制——上游页签槽位建在 `menu.slots` 之外且
**地址会重叠**（精妙容器菜单的 64..66 既是合成页签格子、也是熔炼页签格子）⇒ 残留菜单会让下一步的点击
"**被接受却落到别处**"，症状正是 `craft_furnace`/`craft_cooking` 的"**料进去了却不烧**"。
一处插入点覆盖全部步骤（配合各夹具自证，形成"电池级清场 + 夹具级自证"两层）。

**同时保留夹具级自证**（D-203）：`CraftFurnaceCheckTask.discover()`、`CraftGoalCheckTask.prepare()`；
其余夹具的逐条接线与"逐条撤步瘦身"作为继续项（瘦身前提不变：先自证、再逐条撤、每条复跑）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `21ab912c…`）；**待客户端**（一次 CORE/FULL 即可同时验证）。

#### D-203 附注二：**前提必须按调用点写** —— `ownMenu` 用在"打开站点之后"会假红

**实测**（CORE 23/25）：新断言抓到真东西——**`own_menu=false` 出现 6 次**（电池每步之间确有残留容器菜单，
被"每步清场"关掉后继续跑 ✓），但同时 `craft_furnace`/`craft_cooking` 双红
`reason=premise_own_menu`。**根因是我把断言写反了**：这两个夹具的**全部工作就是自己把站点菜单打开**
（熔炉 / 熔炼页签），所以 `discover()` 处正确的断言是"**站点菜单确实开着**"，不是"菜单是玩家自带的"。

**修**：`FixturePremise` 增加**反向**前提 `stationMenuOpen(bot)`（`premise_station_menu_open`），
`CraftFurnaceCheckTask.discover()` 改用它；`CraftGoalCheckTask.prepare()` 仍用 `ownMenu`（它在此处确实还没开菜单）。

**纪律（登记）**：**前提按调用点写**——动手**之前**用 `ownMenu`（无别的容器菜单）；打开站点**之后**用
`stationMenuOpen`。前提断言本身也要"知道自己被放在哪一步"，否则它会制造假红（本轮即是）。
可选后续（不改行为、只增事实）：把 6 次 `own_menu=false` 的**来源步骤**也打出来（谁留下的菜单），
便于把"残留"从"被清掉"升级为"追责到具体步骤"。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `21ab912c…`）；**待客户端**：CORE 期望回到 `(25/25)`，
且 `premise_station_menu_open=true` / `premise_own_menu=true` 均不误报。

### D-204（3-B / S1）：**机器配方只读探针** `alice:machine_probe`（零参数、零写入）

**为什么需要它**：S0（`docs/MEKANISM_FACTS.md`，用阶段 2 的**运行时**导出离线算得：Mekanism 26 类型 / 1171 条，
`crushing` 210 起）只给了**类型×条数**，**不含这些类型的输入/输出样例**（它们本就不在原版白名单里）
⇒ 认机器（S2）之前必须先取证"这些类型长什么样"。

**实现**：`task/MachineProbeTask`（`*ProbeTask` 命名 + `isSelfCheck()=true` ⇒ 不招 LLM）
+ `item/MachineProbeItem`（零参数右键）+ `BotManager.assignMachineProbe`；产物 = 一行 `SUMMARY`：
```
namespace=mekanism types=26 type_recipes=1171 readable_total=2923 skipped_total=2370 samples_per_type=2 no_writes=true verdict=PASS
```
外加日志里每个类型的 `count=` 与最多 2 条 `sample id=… out=… x… in=[…]`。
**只读**：只读 `RecipeManager`，不改世界、不发包、不派任务；`no_writes` = 我方账本 pending=0。

**下一步（S2 的范围由这批样例决定）**：拿到样例后写"类型 → 输入/输出 + 机器类型"的读法（只读），
把查询层的 `machine_recipe_unsupported` 升级为**有出处的机器路线**；探针按纪律**验证通过即回收**
（或按 D-197 转成电池步）。换模组只改 `NAMESPACE` 与入口名字——**这就是"实验模板"的可复用性检验**。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（77 项）+ 已同步（jar `1c441fc9…`）；**待客户端**（一次右键）。

#### D-204 附注一：S1 取证**发现了关键事实**——原版 `Recipe` 接口**取不到**机器配方的输入/输出

探针实跑（23:56，`verdict=PASS`，26 类型条数与 S0 逐类吻合 ✓）里，样例长这样：
```
sample id=mekanism:pigment_extracting/carpet/purple  out=minecraft:air x0  in=[]
```
⇒ **`getResultItem()` / `getIngredients()` 对机器类型返回空**：Mekanism 用**自己的配方类/访问器**
（输出可能是物品也可能是**化学品**，输入是它自己的 `ChemicalStack`/多输入结构），
原版接口在这类配方上**没有表达能力**。这不是探针的缺陷，而是 S1 要回答的核心问题被回答了：
**"读法"必须问上游自己的访问器（按类型/接口找，绝不按类名猜）**，否则只能如实报 `machine_recipe_unsupported`。

**下一步（无需客户端）**：在 WSL 里直接反编译/检查 `Mekanism-1.20.1-10.4.16.80.jar` 的配方类层次
（与当初查精妙 Core 同一手法）⇒ 找出"输入/输出访问器"的**接口形态**；据此给 S1 的读法定接口，
并在探针里补一栏 `unreadable_via_vanilla=N`（把"原版读不出"与"没有输出"分开报，避免歧义）。
**纪律提醒**：这类"问上游"的做法正是协议 §3 的第一条通用判据（上游自述驱动），不是为 Mekanism 开的特例。

#### D-204 附注二：S1 的"上游访问器"线索（离线侦察，无需客户端）

`unzip -l Mekanism-1.20.1-10.4.16.80.jar` 显示它**自有配方 API**（不是原版 `Recipe` 语义）：
`mekanism/api/recipes/*` —— 例如 `ItemStackToItemStackRecipe`、`ChemicalCrystallizerRecipe`、
`ChemicalDissolutionRecipe`、`CombinerRecipe`、`ChemicalInfuserRecipe`…（另有 `datagen/recipe/builder/*`）。
⇒ S1 的读法**应当问这些类型自己的访问器**（按**接口/类型**找，不按类名猜；与当初查精妙 Core 的
`getCookingSlots()` 同一手法）：例如"物品→物品"型有 `getInput()/getOutput()` 这类方法，
"→化学品"型则涉及 `ChemicalStack`（超出原版表达能力 ⇒ 如实标注，不硬塞进原版语义）。
**这正是协议 §3 第一条（上游自述驱动）的复用，不是 Mekanism 特例。**

#### D-204 附注三：Mekanism 配方访问器（离线复核，S1 读法的依据）

`javap mekanism/api/recipes/ItemStackToItemStackRecipe`（`crushing`/`enriching`/`sawing`/`compressing` 等的父类）：
```java
public abstract class ItemStackToItemStackRecipe extends MekanismRecipe implements Predicate<ItemStack> {
  public mekanism.api.recipes.ingredients.ItemStackIngredient getInput();
  public ItemStack getOutput(ItemStack);                 // 按具体输入算输出
  public List<ItemStack> getOutputDefinition();          // ← **与输入无关的输出定义**（读法首选）
  public ItemStack m_8043_(RegistryAccess);              // = 原版 getResultItem（对机器类型常常是 AIR）
  public boolean isSpecial();                            // m_142505_
}
```
⇒ **S1 读法**（只读、自校验、按类型不按类名）：
1. **输出**：先试 `getOutputDefinition()`（自校验：非空且不含 AIR）→ 失败再退回原版 `getResultItem(access)`；
2. **输入**：`getInput()` 返回 `mekanism.api.recipes.ingredients.ItemStackIngredient`（下一步核它的枚举方法，
   预期是"可列出 `ItemStack[]`/`Ingredient`"这类形态）；
3. **化学品输出的类型**（`ChemicalCrystallizerRecipe` / `ChemicalDissolutionRecipe` / `ItemStackToGasRecipe` 等）
   **不在原版物品语义里** ⇒ 如实报 `machine_output_not_item`（不硬塞进物品语义，也不假装读懂了）；
4. 探针补一栏 `unreadable_via_vanilla=N`（把"原版读不出"与"真的没有输出"分开，避免歧义）。

**这一步再次验证协议 §3 的分离判据**：能进通用骨架的是"**问上游自述 + 自校验**"这套**机制**；
Mekanism 的具体方法名只出现在**模组专属适配器**里。

### D-205：**active goal 使用范围扩宽 + 硬边界"到用户测试为止"**（用户裁定 2026-09-13）

**用户裁定**：扩宽 active goal 的使用范围——AI 可以在**不需要用户参与**的阶段用 goal 自主续行；
但 **goal 的范围只能定在"用户测试之前"**。**明确要防的事**：goal 反复唤醒对话去查看用户测试结果，
而用户还没开始测试 ⇒ 空转轮次、白烧预算。

**落成规则**（PLAYBOOK §5.0 第 1/2 条已改写，AGENTS.md 同步）：
1. **可以**为离线自主推进创建/启用 goal（读码、读 jar/字节码、写码、编译、镜像、同步、文档、只读分析），不必逐次请示；
2. **硬边界**：下一步需要**客户端验证**或**用户拍板** ⇒ **立刻 `pause`/`blocked`**，不得自动续行；
   同时写清"要测什么 / 期望看到什么 / 怎么回话"；
3. **禁止轮询用户结果**：不许为了看"用户测了没"而唤醒轮次；**恢复只由用户明确唤醒触发**；
4. 创建 goal 时必须写明：目标 / **自动续行范围** / **停止条件（到需要测试或拍板为止）** / 轮数上限；
5. 同规则适用于 `subagent` / Ralph 接力。

**我的执行承诺**：下一段"阶段 3-B / S1"我会开一个 goal，范围=**实现 Mekanism 只读适配器 + 探针补两栏 + 编译同步**，
停止条件=**"探针准备好、需要你点一次"**；到那点即 `pause` 并等你回话，**不会自动来回查你测没测**。

#### D-204 附注四：探针补"上游自述"读取 + 三栏计数（S1 取证第二轮，已同步）

探针现在对每条样例**额外问一次上游自述**（`getOutputDefinition()`/`getOutputs()`，**按返回形态取 + 自校验**：
非空且不含 AIR 才采信），并输出三栏计数——把上一轮"`out=air x0 in=[]`"的**歧义**拆开：
- `unreadable_via_vanilla=N`：原版 `getResultItem()` 读不出（预期≈全部机器样例）；
- `upstream_readable=N`：**上游自述能给出物品输出**（预期 > 0 ⇒ 证明"物品→物品"这一大类**可读**）；
- `machine_output_not_item=N`：原版读不出、上游也没给物品输出 ⇒ **如实归为"非物品输出"**（化学品等，不硬塞语义）。

样例行也多了 `upstream_item_out=…`（`-` = 上游没给）。**这一轮读数直接决定 S1 读法的覆盖面**：
若 `upstream_readable` 接近样例数 ⇒ 先把"物品→物品"接进查询层；若 `machine_output_not_item` 占多数 ⇒
只做"如实报码"，不碰化学品语义。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `1c441fc9…`）；**待客户端**（一次右键）。

#### D-205 附注一：**`pause` 对 AI 是单向的**（实测）——所以"到测试点暂停"要靠**你**恢复

实跑发现（工具层事实）：模型**不能**自行 `resume` 一个 `paused` 的 goal（报错"the model cannot resume a paused goal;
the user must resume it"）。这**正好符合** D-205 的意图（"恢复只由用户明确唤醒触发"），但要用对：
- **到"需要用户测试/拍板"时：`pause`** ⇒ 自动续行停止，**不会**重复唤醒查看测试结果 ✓；
- **恢复方式**：由**你**发起（一句明确的"恢复 goal / 继续推进"或 GUI 上的恢复操作）——我这边只能等你。
- **顺带结论**：`pause` 之前要把"要测什么/期望看到什么/怎么回话"写在回复里（否则你恢复时没有上下文）。

**同日 S1 读数（探针第二轮，51 条样例）**：`unreadable_via_vanilla=31 upstream_readable=20 machine_output_not_item=31`，
且样例 `in=[]` **普遍为空** ⇒ **输入侧必须走上游**（`ItemStackIngredient implements InputIngredient<ItemStack>`，
枚举方法在 `InputIngredient` 上）。⇒ S1 的覆盖面 = "**物品→物品**"子集（原版 ∪ 上游自述），
其余（化学品/气体，如 `rotary`）**如实报 `machine_output_not_item`**。

#### D-204 附注五：S1 读法**两侧都定稿**（离线完成，已同步）

- **输入**：`recipe.getInput().getRepresentations()`（`mekanism.api.recipes.ingredients.InputIngredient<TYPE>#getRepresentations`
  → `List<TYPE>`；物品型即 `List<ItemStack>`，另有 `getNeededAmount(TYPE)` 给数量）——**上游自述，自校验**（非空才采信）；
- **输出**：`getOutputDefinition()`（`List<ItemStack>`；上一轮实测 `upstream_readable=20/51`），退回原版 `getResultItem`；
- **非物品输出**（化学品/气体，如 `rotary`）：如实 `machine_output_not_item`（上一轮 31/51），**不硬塞物品语义**；
- 探针新增计数 `input_readable`（+ 样例行 `upstream_in=[…]`）⇒ 下一轮读数一并决定"物品→物品"子集的实际覆盖。

**至此 S1 的"读法"不再有未知**：两侧都问上游、都自校验；查询层接线（`machine_recipe_unsupported` → 有出处的机器路线）
与探针回收是接下来的两步。

#### D-205 附注二：两处校正（用户 2026-09-13）——goal 范围"两次测试之前" + **上下文量级作废我原来的估算**

1. **goal 范围**：应设为"**两次测试之前**"——一次 goal 允许**跨过下一次测试边界**（pause → 你恢复 → 同一 goal 继续），
   直到**再下一次测试前**才收口 ⇒ 中间**不需要你手动确认目标**。PLAYBOOK §5.0 第 1 条已按此改写。
2. **上下文（重要，我错了）**：窗口 **1,000,000**，**800,000 自动压缩**；实测用量**约 60%**。
   我之前按小得多的窗口估算水位，并因此多次以"预算到头"收尾 ⇒ **方法错误，作废**。
   §5.0c 已改成：**只有接近 800k 压缩阈值时才可作为收尾理由**；在那之前不得以此为由停下、缩小工作量或推迟离线工作。

#### D-204 附注六：S1 **查询层接线完成**（只读；机器路线从"拒绝"升级为"有出处的事实"）

**问题**（读码确认）：`RecipeQuery` 的循环第一行就用**原版** `getResultItem()` 过滤目标物品，
而机器类型那里返回 AIR ⇒ **机器配方在查询层第一步就被丢掉**，最终只能报 `MACHINE_RECIPE_UNSUPPORTED`。

**实现**（新增 1 类 + 改 2 处，全部只读）：
1. **`task/craft/MachineRecipeFacts`**：问上游自述取事实 —— 输出 `getOutputDefinition()/getOutputs()`、
   输入 `getInput()/getItemInput()` → 结果对象的 `getRepresentations()`；**自校验**（非空才采信）、**逐项容错**、
   **不写死类名**；读不出返回 `EMPTY`，由调用方如实报码。
2. **`RecipeQuery`**：机器/未知类型先问上游 —— 若读出的**输出里命中目标物品** ⇒ 记一条 **`MACHINE_ROUTE`**
   （`station` = 机器类型 id，材料 = 上游输入，折算 crafts/perCraft）；否则仍计入 `machineTypes`
   （化学品/气体等 ⇒ 保留 `MACHINE_RECIPE_UNSUPPORTED`，**不硬塞物品语义**）。
3. **`CraftJob`**：遇到 `MACHINE_ROUTE` **如实拒绝** `machine_recipe_unsupported:not_executable:<机器>` ——
   路线有出处 ≠ 能执行（Alice 还没有该机器的执行适配；等 S4 单机闭环再说）。

**探针顺带自证**：`MachineProbeTask` 从**它自己读到的机器产出**里挑最多 3 个物品去问查询层，
打印 `query item=… verdict=…`（期望 `MACHINE_ROUTE`）并计数 `query_probed` / `query_machine_route` ——
**自证式验证**：不需要你手输物品 id，也不用我硬编码样例。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS + 已同步（jar `1c441fc9…`）；**待客户端**（一次右键 `alice:machine_probe`）。
**之后**：探针回收（S5）或按 D-197 转电池步 —— 等这次验证过再动。

#### D-204 附注七：S1 **收口** —— 歧义修掉 + 探针按 S5 回收（转电池步）

1. **实测发现的歧义已修**（`mekanism:crystallizing` 路线读出 `mats=[]`，会被误读为"不需要材料"）：
   `MachineRecipeFacts.Facts` 新增 `inputIngredientPresent`/`nonItemInput()` —— **"读不出"与"没有"分开报**；
   非物品输入时 `RecipeQuery` 给一条占位材料 `非物品输入（化学品/流体等，未由物品语义表达）`，
   并在 note 里标"输入含非物品形态"。
2. **S5 回收**：`alice:machine_probe` **临时入口已删除**（物品类 + 注册 + 模型 + 两份 lang），
   `MachineProbeTask` **转为电池步** `machine_route`（MAIN；该命名空间没有机器类型 ⇒ 报 `<ns>_absent` ⇒ 电池记 **SKIP**，不判红）。
   ⇒ 探针生命周期闭合：**用它取证 → 判定成立 → 回收入口、保留为回归**（D-197 规则 1）。
3. **电池 25 → 26 项**（MAIN 12 → 13），`docs/BATTERY_CURATION.md` 历史表同步。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（76 项，探针物品已回收）+ 已同步（jar `1c441fc9…`）；
**待客户端**：CORE `(26/26) → PASS`（其中 `machine_route=PASS`；若 Mekanism 不在则 `SKIP`）。

#### D-204 附注八：S1 改动让 **A1 夹具**的旧断言过期 —— 按"能力升级"更新，并换掉一个**前提过期**的负例

**实测**（CORE 26 项首跑）：`machine_route=PASS` ✓（新步成立），但 **`craft_check=FAIL reason=machine_only_vanilla,no_recipe,machine_only`**
—— 三个子用例全部是我这次**语义升级**的必然后果（机器配方从"MACHINE_RECIPE_UNSUPPORTED（拒绝）"
升级为"**MACHINE_ROUTE（有出处的路线）**"）：

| 用例 | 旧期望 | 新期望 | 为什么不是"放宽" |
|---|---|---|---|
| `machine_only_vanilla`（圆石） | `MACHINE_RECIPE_UNSUPPORTED` | `MACHINE_ROUTE` + 路线带机器类型 | 上游自述**确实读得出**输入/输出 ⇒ 拒绝变成了"有出处的事实"；**执行侧仍拒绝**（`CraftJob`：`not_executable`） |
| `machine_only`（`mekanism:dust_iron`） | 同上 | `MACHINE_ROUTE` + 路线带机器类型 | 同上 |
| `no_recipe`（**基岩**） | `NO_RECIPE` | **换物品**：`minecraft:command_block` | 实测本整合包**真有机器配方能产出基岩** ⇒ S1 之后它正确变成 `MACHINE_ROUTE`，旧期望假失败（§6.9.1 ② 的教训：负例必须按**当前模组集**核对，"原版视角"会过期） |

**纪律**：能力升级时，**夹具断言要跟着升级，但必须逐个说明"为什么不是放宽判据"**；
负例则优先换一个**依然不可能**的物品，而不是把负例删掉。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `1c441fc9…`）；**待客户端**：CORE 期望回到 `(26/26) → PASS`。

#### D-204 附注九：负例改为**运行时自证**（写死物品必然随模组集过期）

**实测第二次**：`no_recipe` 换成 `minecraft:command_block` 后**仍然红** —— 本整合包里它也有机器配方
（`thermal:insolator` / `thermal:press` / `create:deploying` / `mekanism:*`…）⇒ 结论：
**"写死一个不可能产出的物品"这条路本身不可靠**（基岩 ✗、命令方块 ✗），随模组集变化必然假失败。

**改法（自证）**：`CraftCheckTask.firstUnproducibleItem(server)` —— 扫全表建"**可产出集合**"
（原版 `getResultItem` ∪ `MachineRecipeFacts` 的上游自述产出），再从候选列表
（`barrier`/`debug_stick`/`structure_void`/`jigsaw`/`light`/`command_block`/`bedrock`）里挑**没出现过**的那个；
一个都挑不到 ⇒ 如实记 `SKIP:负例前提不成立`（不判红、不假装）。挑中的物品记进 `no_recipe_item` 便于复现。

**纪律**：**负例的前提也必须自证**，不能靠"我觉得它没配方"。这与 `§6.9.1 ②`（按当前模组集核对前提）同源。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `1c441fc9…`）；**待客户端**：CORE 期望 `(26/26) → PASS`。

#### D-204 附注十：**夹具的自证负例抓出查询层真 bug** —— `machineTypes` 未按目标过滤（已修）

**实测**（CORE 第三轮）：自证负例挑中 `minecraft:barrier`，却报 `MACHINE_RECIPE_UNSUPPORTED`
且列出 `create:milling / create:haunting / create:cutting / thermal:pyrolyzer / mekanism:sawing …`
—— 这些类型与屏障**毫无关系** ⇒ 一眼可见不是"屏障有机器配方"。

**根因（我的实现缺陷）**：S1 接线时我把"机器/未知类型"分支**放到了目标过滤之前**，
于是那条分支对**全表每一张机器配方**都会执行，`machineTypes` 被塞进"整个整合包的机器类型集合"。
后果：任何**没有配方**的物品都会被判成 `MACHINE_RECIPE_UNSUPPORTED`（而不是 `NO_RECIPE`）——
即"读不懂"被夸大成了"被机器产出"。

**修**：机器分支只在**该配方确实产出目标物品**时才记类型（`else if (vanillaHits) machineTypes.add(type)`）；
上游自述命中目标的仍走 `MACHINE_ROUTE`（不变）。

**这次事故的价值**：抓出它的是**夹具的"自证负例"**（上一轮刚改成运行时挑选）——
说明"负例前提自证"不只是防过期，还能**当断言用**去发现实现缺陷。已与 §6.9.1 一并登记。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `1c441fc9…`）；**待客户端**：CORE 期望 `(26/26) → PASS`。

### D-206（3-B / S2 起）：**机器站点只读发现**——探针 + 场景就绪（首个增量）

**S1 已闭环**：CORE `(26/26) → PASS`（`machine_route=PASS`；自证负例 `no_recipe_item=minecraft:barrier → NO_RECIPE`）。

**S2 要回答的问题**（与 S1 不同：S1 答"配方读得出"，S2 答"**机器本身认不认得出来**"）：
机器方块 → 菜单类/槽位表 → **进度数据**（原版 `ContainerData`？还是上游自述？）。

**离线侦察（已做）**：Mekanism 的机器菜单基类是 `mekanism.common.inventory.container.tile.MekanismTileContainer`
（`getTileEntity()` + `addSlots()`；无独立的"富集仓容器"类）；方块 id 已确认（`mekanism:enrichment_chamber`
/ `crusher` / `energized_smelter` / `*_enriching_factory`…）。

**本轮实现（只读、零写入）**：
- **场景** `tools/test-scenes/.../machine_course.mcfunction`（x60..72 / y60..72 / z300..312 孤立平台 +
  `mekanism:enrichment_chamber` @(66,64,306)；**已同步进客户端存档 datapack**）；
- **探针任务** `task/MachineStationProbeTask`（`*ProbeTask` + `isSelfCheck` ⇒ 不招 LLM）：
  按**命名空间**找机器方块（数据驱动，不写死机器清单）→ `MenuSession` 开菜单 → 读
  **菜单类 / 槽位表（槽类·容器·下标·containerSlot）/ `ContainerData` 字段（按类型找）/ 上游自述的进度类方法名（按形态找，只收集不调用）**
  → 关菜单 → `no_writes` 自证 → `SUMMARY`；
- **临时入口** `alice:machine_station_probe`（**验证通过后按 S5 回收**或转电池步）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（77 项）+ 已同步（jar `1c441fc9…`）；**待客户端**（一次右键）。

#### D-206 附注一：S2 首测事实 + **场景夹具两条硬纪律**（用户要求）+ 夹具审计

**S2 实测（客户端，`alice_test:machine_course`）**：
```
机器方块 mekanism:enrichment_chamber@66,64,306   方块实体 TileEntityEnrichmentChamber
菜单 …MekanismTileContainer   slots=41   槽位 VirtualInventoryContainerSlot/InventoryContainerSlot(cs=0)…
上游自述进度方法 [getScaledProgress→double, getOperatingTicks→int, getActive→boolean, getClientActive→boolean]
```
⇒ **S2 的答案**：机器站点**认得出**（命名空间方块 + `MekanismTileContainer` + `getTileEntity()`）；
**进度数据走上游自述**（不是原版 `ContainerData`）——与 S1 同一套路（问上游 + 自校验），可进通用骨架。

**用户纪律（已入 `PLAYBOOK §5.0d` + `AGENTS.md`）**：
1. **夹具必须自己传送 bot 到场景起点**——不许依赖"电池的 provision 帮我挪过去"，否则**单跑前提不成立**
   （本次实测：probe 单跑时 bot 停在 (62,64,305)，不在场景起点）；
2. **结束必须复位**：关菜单 + 停输入 + 回到场景起点，**失败路径也走同一段**，并记 `reset=true`。
- **落地**：`MachineStationProbeTask` 新增 `PREPARE`（自带传送 + `premise_on_ground/own_menu` 断言）
  与 `RESET`（关菜单/停输入/回起点，成功与失败共用）。
- **审计（用"带场景的步 vs 夹具是否自带 teleport"自动比对）**：唯一缺口 = **`decision_contract`**
  （`DecisionContractCheckTask` 无 `teleportTo`；电池里靠 provision 兜底，**单独跑会缺前提**）⇒ 记为待修（下一轮）。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `1c441fc9…`）；**待客户端**（再点一次 probe 验证"传送+复位"生效）。

#### D-206 附注二：`decision_contract` 补齐"自带传送 + 结束复位"（审计缺口关闭）

`DecisionContractCheckTask`（依赖伐木场景的树）在电池里靠 provision 兜底传送、**单独跑会缺前提**。
按 `PLAYBOOK §5.0d` 补齐：① **自带传送**到 `LumberCourseAnchor.START_FOOT`，并**先落一 tick 再干活**
（传送后立刻扫描会撞"区块/实体未就绪"⇒ 假失败）；② **结束复位**：停输入 + 回到起点，**成功与失败同路径**。
⇒ 至此"带场景的步 vs 夹具自带 teleport"审计**无缺口**。

**等级**：IMPLEMENTED + COMPILES + 已同步（jar `1c441fc9…`）；**待客户端**（CORE 复跑应有 `decision_contract` 绿，
且日志出现"已传送/结束复位"两行；`/alice battery full` 亦覆盖）。

#### D-206 附注三：S2 **收口** —— 探针按 S5 回收、任务转电池步（CORE 26 → 27）

- **S2 结论（已客户端验证）**：机器站点**认得出**（按**命名空间**找方块 → `MekanismTileContainer` 菜单 →
  `getTileEntity()` 给方块实体）；**进度数据 = 上游自述**（`getScaledProgress`/`getOperatingTicks`/`getActive`），
  不是原版 `ContainerData` ⇒ 与 S1 同一套路（**问上游 + 自校验**），可进通用骨架；机器专属的类名/方块 id 只留在适配器与场景里。
- **回收（S5）**：`alice:machine_station_probe` **临时入口已删除**（物品类 + 注册 + 模型 + 两份 lang，物品数回到 76）；
  `MachineStationProbeTask` **转为电池步 `machine_station`**（MAIN；机器不在 ⇒ `<ns>_machine_absent` ⇒ **SKIP** 不判红），
  并保留"自带传送 + 结束复位"（§5.0d）。
- **电池 26 → 27 项**（MAIN 13 → 14），`docs/BATTERY_CURATION.md` 历史表同步。

**下一步（S3/S4 之前的最后一块只读）**：把"机器类型 ↔ 机器方块/菜单"的映射做成**单一出处**
（像 `RecipeDump.stationFor` 那样的表），让查询层的 `MACHINE_ROUTE` 能给出"**去哪台机器**"；
之后才谈 S4（单机闭环，需要写入授权与预算）。

**等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（76 项）+ 已同步（jar `1c441fc9…`）；
**待客户端**：CORE `(27/27) → PASS`（含 `machine_route=PASS`、`machine_station=PASS`）。

### D-207：授权模型定为「硬校验 + 集中策略表」，**不做任务级授权开关**（2026-09-14 用户拍板）

**背景**：外部质疑（服务端位移/防贼式审批/无菌温室）+ 两轮工作流审查。取证的现行事实：
`WriteGrant(requester, reason)`（D-082，唯一写入凭证，**明确反对任务级开关**：许可会泄漏到子请求、预算无法归因）；
`WorldModLedger` **只记放置**（无 `recordBreak`）带 `Policy.TEMP`（必须配对拆除）/`Policy.KEEP`（不该拆）；
`RecoverabilityPolicy`：`FALL` 必须带 `fall_return_verified`，否则**规划期抛异常**；`RiskSwitches` 默认全关、评估器未实现。

| 决定 | 内容 |
|---|---|
| **① 授权模型** | **安全 = 硬校验（落点可站 / PILLAR 支撑 / FALL 返回验证）+ 集中策略表**（区域×任务类别 → `TEMP/KEEP` + 允许 Movement 集合 + 预算上限）；**不新增任务级授权开关**，`WriteGrant` **一行不改**；默认 `PROTECTED`，显式降级到工作区；`UNKNOWN` requester 记为错误 |
| **② Movement 放开** | 野外采集/伐木默认集合**加入 `PILLAR/FALL/DOWNWARD`**；`miningApproach` 的"整类禁用"改为**按条件放行**——**须先给 A/B 客户端证据**（可达性/耗时/坠落等风险事件）再改 |
| **驳回（记录以免翻案）** | 取消 Tasks 层只留 InputController（丢掉可回收性/账本，D-034/D-035）；把账本交给基岩=不记账（丢掉"服务端是真相"的证据链与夹具零写入自证）；任务级授权开关（违反 D-082） |
| **纠正（AI 自身）** | 上轮把"禁止挖穿脚下/搭柱须支撑"说成硬约束**不准确**：前者根本不存在（`DOWNWARD` 只要求"挖完能站住"），后者是**游戏物理前提**而非安全政策；"任务级一次授权"提案**撤回** |

**依据**：`DownwardExecutionFactory` / `PillarExecutionFactory` / `RecoverabilityPolicy` / `WriteGrant` / `WorldModLedger` / `RestoreScopeTask`（两条拆除路径、自上而下、只拆自己放的、材料回收）。

**附注（2026-09-14，R1 接线时的拍板与术语纠正）**

- **解释 A（用户选定）**：矩阵只管**回收义务**，上层显式授权（`BULK_EDIT`/`MANUAL`）**不受默认区约束**；
  明确**不做**"默认区除白名单外一律拒写"——那会让道路施工/玩家命令在默认区被拒，误拆风险比现状更大。
- **区域只有两层归属**：`EXTERNAL`（默认，不是 Alice 的地）/ `WORKSPACE`（Alice 的地，来源 = 已划区域）；
  **保护区不是第三层**，它是优先级更高的**独立闸门**（`SafeZoneData` 命中即禁止破坏），与"归属"正交。
  早前提案的三处术语错误已纠正：`PROTECTED` 与既有"保护区"**同名反义**（默认区允许破坏）；
  `TRANSIT` 把"通行"（`PathRequest` 的属性，D-076）当成了"地块的属性"；`WILD` 把"场合"当成了"能力"。
- **移动集词表 = `PathRequest` 的工厂名**（`of`/`pureTraversal`/`miningApproach`/`scaffoldRemoval`/
  `climbApproach`/`withWorldModification`），不新造形容词 ⇒ 每个名字都能 grep 到实现与登记条目。
  ② 要给采集/伐木放开 `PILLAR/FALL/DOWNWARD`，正确做法是**新增显式工厂**并在 `WORLD_WRITE_AUTHORIZATION.md` 登记。
- **工作区语义取 ⓑ**：工作区只是"**允许** KEEP 类理由"，不改变其余理由的回收义务；今天两区解析**逐条相同**
  （`zoneDiff=0` 由自检断言，谁让它不同就必须补一条决策记录）；工作区来源（已划区域）的接线留到 R1b。
- **接线范围**：`action/WritePolicyMatrix.java`（唯一真源，22 行）+ **规划期**闸门（`CorePathPlanner.plan`：
  越权抛 `WRITE_POLICY_MOVEMENT_DENIED`，在规划器入口转成"如实失败的 plan"，**不让异常逃逸**打断服务端 tick）
  + **执行期**复验（`WorldModLedger.recordPlacement:126`）+ 自检 `write_policy`（含"越权必须被拒"的负例）
  + 视图 `docs/authz/POLICY_MATRIX.csv` + 断言脚本 `tools/check-policy-matrix.sh`；authz 注册表加 `L2-5`。
- **本轮不改变任何默认行为**（A + ⓑ 的必然结果）；真正带上牙齿的是**移动授权**：
  纯通行任务（`walk-to` 等）今天起不能再规划出会写世界的移动——这是 D-076 红线的可执行版本。

### D-208：自检夹具的终态必须传播 verdict —— **禁止静默绿**（2026-09-14 实测确立）

- 状态：稳定
- **事实**（R1 接线后首轮客户端电池，`latest.log:3193-3230` 与 `:3738`）：`WritePolicyCheckTask`
  自报 `verdict=FAIL`，电池却打出 `write_policy=PASS … (28/28) → PASS`。两层独立缺陷：
  ① **断言集自相矛盾**：`scaffoldRemoval ∩ 写原语 = ∅` 与"同一条件列表里要求含 `DOWNWARD`"不可能同时成立
  （`DOWNWARD` 本身就在 `writePrimitives()` 里）⇒ 恒 FAIL、**零信号**；
  ② **终态不传播**：该夹具照抄 `RecoverabilityCheckTask` 的 `done ? Status.DONE : Status.RUNNING`，
  而电池**只按 `status == DONE && idempotent` 记账**（`RegressionBatteryTask:558`），从不读夹具的 `verdict=`
  ⇒ 假红被吞成**静默绿**（比假红更危险：整轮测试因此失去判据）。
- **契约**：自检夹具 `failures` 非空 ⇒ `tick()` 必须返回 `Status.FAILED`；`SUMMARY` 必须带 `verdict=`。
  全仓其余 20 个 `*CheckTask` 早已如此，唯二例外（`WritePolicyCheckTask` / `RecoverabilityCheckTask`）已修；
  两个夹具仅被电池实例化（`RegressionBatteryTask:436,441`），无生产影响面。
- **执行**：静态规则 `bash tools/check-fixture-hygiene.sh`
  （R1 = 返回 `DONE` 的方法必须存在能返回 `FAILED` 的路径；R2 = 禁止"一行 return 里 `DONE`+`RUNNING` 而无 `FAILED`"
  的失败分支缺失形状；R3 信息性）。反向验证：把任一修复回退 ⇒ 立刻报红。
- **附带的判定纪律**：**禁止聚合布尔式 detail**——旧写法打印 `A||B||C=false`，红了也定位不到是哪一个；
  自检失败行必须逐项打印真实值（本次已改为 `scaffoldRemoval∩放置=… ∩挖穿=…` 形式）。

### D-209：机器类型 ↔ 机器方块/菜单 的**单一出处**（3-B / S3，2026-09-14）

- 状态：稳定
- **真源** = `decision/MachineMap.java`（27 行 = 上游**全部**类型）；**视图** = 生成的 `docs/MACHINE_MAP.csv`；
  **防漂移** = `bash tools/check-machine-map.sh`（**Tier A** 表内结构：类型/方块唯一、`EXECUTABLE` 必须有实测
  `menuClass`；**Tier B** 用 `javap` 读上游 jar 字符串常量，断言 **表 == 上游全部类型**，今天 23 有站点 + 4 无站点 = 27）。
  反向验证过：删一行、把 `crusher` 改成 `crusher_typo` 都立刻 FAIL。
- **`station` 语义换血**：机器路线的 `station` 从"配方类型 id"改为**机器方块 id**（`RecipeQuery` 两行；
  类型 id 仍留在 `Route.type`，不丢信息）⇒ `CraftJob.not_executable:<machine>` / 目标层 `needs=` 的文案变成
  **可去的地方**。未登记或无站点 ⇒ **如实回落成类型 id**（不猜方块、不静默失败）。
- **探针改为"按表认机器"**：`MachineStationProbeTask` 只认表里登记过的方块（同类型取最近），每台一组 `m{i}_*`，
  并断言两条：① 菜单类 == 表里**已实测登记**的值（未登记的行**只观察**——不拿"猜出来的期望"当断言制造假红）；
  ② **方块实体自述的配方类型 == 表里的类型**（`getRecipeType()`→`getRegistryName()` 只读反射；方块↔方块实体
  编译期绑定 ⇒ 这是"点对了哪台机器"的硬证据）。事实留痕：`reach_skipped` / `untabled_blocks` / `same_type_extra`。
- **能力口径**：`Capability.EXECUTABLE` 今天 **0 行**（全表 `READ_ONLY`）；出现它必须同时满足执行适配器 +
  已实测 `menuClass` + 客户端验证记录（静态检查强制）。**槽位下标一律不入表**——槽位/进度运行时问上游。
- **加机器的成本**（场景已加第二台 `mekanism:crusher` 作证）：表里已有行 + 场景多一个 `setblock`，**不改 Java**。
- **已知未覆盖**：只登记基础机；`crushing` 的 1:N 工厂变体（`basic_/advanced_/elite_/ultimate_crushing_factory`）
  在 `note` 里点名但**未入表**（要入就按数据补行，不许猜）；`menuClass` 实测值现在有 `enriching` / `crushing` 两行
  （其余 `-`，探针只观察不断言）。
- **客户端实测（2026-09-14，第二轮电池，`SERVER_TESTED` + `WINDOWS_CLIENT`）**：`latest.log:2941` / `:2953` / `:2971` / `:3680`
  - 表 ↔ 运行时**闭合**：`machine_map_rows=27 … unmapped=[] row_block_missing=[]`；两台机器由**表里的方块 id** 认出
    （`按表找到 2 台：mekanism:enriching@66,64,306,mekanism:crushing@66,64,307`），
    `m1_binding=true m2_binding=true`，电池 `(28/28) ticks=2789 → PASS`。
  - **口径纠偏（本轮实测教训）**：实测类型数是 **26**、不是表的 27 —— 差集是 **`mekanism:smelting`**：
    它在 jar 里注册、但**零配方** ⇒ RecipeManager 里根本没有这个键。原 SUMMARY 只有 `mapped=22`，
    和 `machine_map_rows=27` 并列像"少了一行"（我预告 `mapped=23` 也正因此错了）。
    已把探针改成**对表行做完整划分**：`with_site_confirmed`(22) + `with_site_unobserved`(1) + `no_site`(4)
    + `row_block_missing`(0) == `machine_map_rows`(27)，并加**分桶守恒自检**（不守恒 ⇒ 判红 = 探针口径 bug）；
    `unmapped` 仍是"运行时有、表里没有"的反向缺口。`with_site_unobserved` **只报事实不判红**：
    配方可被数据包/配置增删，把"今天为 0"钉成期望，将来加一条配方就假红。
- **实测事实：`menuClass` 不是机器身份**（enriching 与 crushing 实测**同一个类**
  `mekanism.common.inventory.container.tile.MekanismTileContainer`，41 槽位、槽位表逐项相同）⇒
  菜单类只能看"菜单形状有没有漂移"；分辨"点对了哪台"的硬证据**只有方块↔方块实体绑定**（`m{i}_binding`）。
  crusher 行已按本轮观察值回填 `menuClass`（下一轮起 `m2_menu_class_matches` 变成断言）。
- **槽位角色也只能问上游**（同轮离线侦察 + 探针新增 `m{i}_slot_roles`）：机器槽的 `slot.container` 是上游
  **共用的空容器**（实测恒 `SimpleContainer(cs=0)`）⇒ 旧槽位表那一列对机器槽没有信息量。
  角色来自 `InventoryContainerSlot.getSlotType()`（`ContainerSlotType{IGNORED,NORMAL,POWER,INPUT,EXTRA,OUTPUT,VALIDITY}`）
  与 `getInventorySlot()` 的实现类（`InputInventorySlot`/`OutputInventorySlot`/`EnergyInventorySlot`/…），
  均为公有只读 getter ⇒ **S4 闭环"哪个下标是输入/输出"不必猜**（本轮只观察，不起断言）。
  同时确认 S4 的能量前提可用**真实方块** `mekanism:creative_energy_cube` 提供（`MekanismBlocks.CREATIVE_ENERGY_CUBE`），
  无需反射塞能量。

### D-210：机器闭环用**既有**容器写入通道，不新造授权（3-B / S4，2026-09-14）

- 状态：试行（**待客户端验证**——这是 3-B 的第一次写入）
- **写法 = 组合既有骨架**：`MenuSession`（开菜单）+ `StationProvision`（shift-click 搬运、结果验证、回滚）
  + `WriteBudget.consumeContainerWrite`（预算）+ `WriteReason.CONTAINER_TRANSFER`（理由）
  + `StationProvision.moveIntoContainer/moveOutOfContainer`。**没有新增授权机制、没有新增 reason、没有新动作层**。
- **不猜槽位语义**：放料用 `QUICK_MOVE`（**让菜单自己决定落点**），成不成**一律用结果验证**
  （机器里出现了料 → 喂进去；背包里产物变多 → 取回来）。槽位角色另有上游自述（`slot_roles`）作旁证，
  **但闭环不依赖它**——这是"结果验证优先于语义推断"的第二次落地。
- **能量是场景前提，不是 Java 改动**：机器要电才跑；场景 `machine_course` 在富集仓下方放**真实方块**
  `mekanism:creative_energy_cube`（纯数据）。夹具先等场景电源（{@code ENERGY_GRACE_TICKS}），
  真喂不上才走**前提补电**（`getEnergyContainers` → `setEnergy(FloatingLong)`），且**必然留痕**
  `energy_source=cube|api_precharge|unreadable|none` ⇒ 不会把"电从哪来"瞒过去。
  能量**读不出**（别的模组没有这套访问器）记 `unreadable` 并**不判红**。
- **判据只看世界事实**：`product_before/product_after`、`machine_emptied`、`input_consumed`；
  超时不猜原因，如实报 `no_product_in_600ticks:no_energy|progress_stalled` + 当时的能量与进度。
- **已知边界（如实登记，待用户裁定）**：`WritePolicyMatrix` 对容器写入**只做声明与审计**
  （未登记 requester/组合会被记下并显示，R1 设计：**可见但不据此拒绝**），容器写入真正的强制闸门是
  `WriteBudget` 的容器维度。requester `machine-cycle` 已按 `CONTAINER` 登记（前缀规则）。
  "矩阵是否应该对容器写入也有拒绝权"是 R1 的开放设计问题，**本轮不擅自改**。
- **v1 边界**：只跑一台（`mekanism:enrichment_chamber`，表里已实测菜单类的那台）；站位用**夹具传送**
  （与只读探针同规格），**走到机器旁的内核寻路是 S4 v2**——不与闭环失败模式纠缠（一次只动一个变量）。

### D-211：容器写入进策略表 —— **声明权 + 可武装的拒绝权**（R1 收口，2026-09-14）

- 状态：已实施（离线圈闭；客户端证据待下一轮电池）
- **审计发现的真事实**（不是猜测）：`WritePolicyMatrix` 到今天**从没经手过容器写入** ——
  `noteUnregistered` 只在 `requireMovementsGranted`（寻路）与 `ledgerPolicy`（放置）里被调；
  而容器写入**不产生账本条目** ⇒ 放置类的"执行期复验"在容器侧**没有对应物**，
  `P-06/P-17` 登记的 `CONTAINER_TRANSFER`/`STATION_PROVISION` **零读者**（表在，牙齿不在）。
- **决定**：挂点 = `WriteBudget.consumeContainerWrite`（所有已接线的容器写入的必经之处，一处管住全部），
  顺序**先策略、后预算**（策略拒 ⇒ 不进预算计数：两层账各自归因，不互相冒充）。口径与移动授权**对齐**：
  requester 未登记 ⇒ **留痕不拒**（那是登记缺口）；已登记但该行**没声明**这个理由 ⇒ **硬拒**。
  拒绝权**默认武装**，保留一行开关（`setContainerRefusalArmed`）作为回退把手 + 给夹具断言两种模式。
- **有意的**不对称（别照抄）：放置类的未声明理由只留痕（回收义务由 `obligation()` 兜底，那是**后果**决定）；
  容器写入没有后果维度（取出来的东西不会自己回去）⇒ "能不能写"是它唯一的门，门必须是硬的。
- **可执行覆盖**（否则"拒绝权"是半个闸门）：新增 `docs/authz/CONTAINER_WRITE_SITES.csv`
  （20 个容器写入调用点，每行必须有 `category` + `why`；`gated=no` 必须写理由，禁止静默豁免）
  + `tools/policy-map.py` 断言⑦：未登记的写入点 / 谎报 `gated=yes` / 陈旧登记行 / `enforced_by` 指向不存在的文件
  或没真调用 `consumeContainerWrite` ⇒ **一律 FAIL**（负例实测四条都红）。
- **覆盖审查顶出来的两个真缺口**（都补上了）：① `CraftJob`（**生产**熔炼路径）往炉子放料/取产物
  **从没记过账** —— 缺口里唯一的生产路径；② `MenuProbeTask`（把物品从箱子真搬出来）不过闸。
  `FurnaceStation` 的执法在其调用方（`CraftJob` / `CraftFurnaceCheckTask` 已按相位/分支过闸）。
  表随之补声明：**CRAFT 行（P-05/P-16）登记 `CONTAINER_TRANSFER`**（熔炼合法写容器）。
- **已知边界（如实登记，未做）**：`InventoryCraft` 的结果槽 shift-click 在**开着容器菜单**时会把产物放进容器
  （`CraftStationCraftCheck` 实测 `product_in_container=1`）——那一下不过闸；接它需要给该方法一个 grant 参数。
  `TransferFixture` 在隔离层直接驱动搬运原语，**有意**不过闸（它验的就是原语自身）。
- **复核触发**：客户端一轮电池后看 `[WritePolicy] SUMMARY … container_checks=N container_refused=M`
  —— `N=0` 而世界里确有容器写入 ⇒ 挂点没接上；`M>0` ⇒ 有生产路径被硬停（先看归因行，再决定补表还是退回观察模式）。

### D-212：S4 场景电源前提是"假绿" —— 能量方块只有**朝向面**出电（2026-09-14 第五轮实测 + 上游源码）

> ⚠️ **本条已被 D-213 修正（2026-09-14 第六轮，客户端实测）**：`[facing=up]` 已落进世界（存档 `r.0.0.mca`
> 里 `facing:"up"`），机器**仍是** `energy_at_open=0.0` ⇒ **朝向不是"流入 0"的根因**。
> 下面 ①②③ 的源码事实**依然成立**（朝向决定从哪个面出电，`[facing=up]` 保留），但"唯一的约束就是方块朝向"
> 这句**作废**：真正的根因是**方块自身电量为 0 且永远充不进电**，见 D-213。

**实测**：S4 首次客户端跑通（`verdict=PASS`，`latest.log:3811`），但 `energy_at_open=0.0`、
`energy_source=api_precharge`。算术定罪：补 4000000 J 后 200 tick 只掉 10000 J = **纯消耗、流入 0**
（若方块在送电，缓冲会被顶满）。**排除"方块没放上"**：同一轮 `[MachineStation] 事实留痕
untabled_blocks=[mekanism:creative_energy_cube@66, 63, 306]`（`:3002`）——方块扫描看见了它。

**根因（两侧都落在上游源码上）**：
① 方块侧 `TileEntityEnergyCube` 用 `setupIOConfig(ENERGY, energyContainer, RelativeSide.FRONT).setEjecting(true)`，
   而 `TileComponentConfig` 这个重载做的是 `fill(DataType.INPUT)` + `setDataType(DataType.OUTPUT, outputSide)`
   ⇒ **除朝向面外，面面只进不出**（对照：同类的物品配置才额外 `.setCanEject(false)`）；
② 机器侧 `TileEntityElectricMachine:65` 用 `setupInputConfig(ENERGY, energyContainer)`（= `fill(INPUT)`）
   ⇒ **所有面都收电** ⇒ 唯一的约束就是**方块朝向**；
③ 方块状态朝向是 **6 向**（`facing=up/down/north/south/west/east`）、`Direction` 枚举序 ⇒ **默认 `down`**；
   场景 `setblock` 没写朝向 ⇒ 电朝地板送，机器在正上方，一格都收不到。

**决定**：改**场景**（`machine_course.mcfunction` 里写 `[facing=up]`），**不改 Java、不重编 jar**（纯数据）；
"喂不上就按前提补电 + 必然留痕"的兜底（D-210）**不变** —— 它不是问题，它正是这次能发现问题的原因。
**复核触发**（第五轮写，**第六轮已触发并推翻**）：下一轮 S4 ⇒ 应为 `energy_at_open>0` + `energy_source=cube`；
实测仍 `api_precharge`（`latest.log:281`）⇒ 前提**仍未成立**，且原因不是朝向 ⇒ 见 **D-213**。
**教训**：写在注释/决策里的"前提"必须有**活体证据**才算前提 —— 这条前提写进了场景注释也写进了决策，
直到"如实留痕"的 `energy_source` 把真相打出来，才发现它一直是假的。

### D-213：创造能量方块**放下就是 0 J、且永远充不进电** ⇒ 场景必须自己灌电（2026-09-14 第六轮；修正 D-212）

**实测（第六轮，场景已带 `[facing=up]`）**：`energy_at_open=0.0` + `energy_source=api_precharge`（`latest.log:281`）
⇒ **朝向不是"流入 0"的原因**，D-212 的因果判定作废（朝向修正保留：它决定从哪个面出电）。

**决定性证据 = 存档里的天然对照组**（世界在场景跑完后 14:05:51 保存，`latest.log:301-308`；区块 4,19 → `r.0.0.mca`）。
`BasicEnergyContainer.serializeNBT` **只在非空时**写 `stored` ⇒ "空"是可判定的：

- `enrichment_chamber @(66,64,306)` → `EnergyContainers=[{"Container":0,"stored":"3990000"}]`（夹具灌的 4,000,000 − 消耗 10,000）
- `creative_energy_cube @(66,63,306)` → **`EnergyContainers=[]`** ⇒ 方块 0 J

**根因（上游源码）**：① `BasicEnergyContainer:52` 初值 `stored = FloatingLong.ZERO` ⇒ 放下的方块就是空的；
② `EnergyCubeEnergyContainer:37-45` 创造档 `insert`/`extract` 都 `action.combine(!isCreative)` ⇒ **强制 SIMULATE**
⇒ 永远充不进（也抽不干）；③ `TileComponentEjector:166 if (!container.isEmpty())` + `CableUtils:27-31` ⇒ **空方块一滴不发**。
④ 上游设计（独立核实 + `mekanism#7352` 维护者原话）：创造方块有"空/满"两个变体，"空"的是 **power sink**，
"满"的靠**物品 NBT `mekanism:data → EnergyContainers`** 在**放置时**灌进方块实体（`BlockMekanism:310-314`）；
`/setblock` 只能造出"空"那个。

**决定（纯数据、零 Java）**：场景在 `setblock` 后复刻"满变体"载荷 ——
`data merge block 66 63 306 {EnergyContainers:[{Container:0,stored:"4000000000"}]}`
（`/data merge` → `load()` → `readContainers` → `setEnergy`，**直接 setter 不走 insert 守卫**）。
`[facing=up]` 保留。场景命令 10 → 11 条。完整证据链：`docs/reviews/2026-09-14-S4电源根因勘察.md`。

**反直觉点（写下来免得下次误判）**：机器容量只有 **20,000 J**（`StorageConfig:53-54`），兜底补电 4,000,000 会把机器顶到
**容量之上**，此时 `BasicEnergyContainer.insert:105-109` **拒绝一切外来电**（~79,600 tick 才回落）⇒
**"补电之后再观察方块通不通"无意义，判电源只看补电之前的 `energy_at_open`**。

**复核触发**：下一轮 S4 ⇒ `energy_source=cube（场景电源，未补电）` + `energy_at_open>0`（20 kJ 容量会被顶满）；
若仍 `api_precharge` ⇒ 回来查 FRONT 的实际绝对朝向（本轮 NBT 侧键是 RelativeSide，看不出绝对方向），
并向用户要"方块顶面是不是亮的输出口"这一眼。

**✅ 复核已执行（2026-09-14 第七轮，`WINDOWS_CLIENT`）**：`/reload` → `/function alice_test:machine_course`
（11 条命令，`latest.log:187`）→ 右键 `alice:machine_cycle_check` ⇒ `latest.log:213`
`energy_at_open=20000.0 energy_source=cube（场景电源，未补电） energy_ready=20000.0`（不再是 `api_precharge` 的 4.0E6）
+ `progress_ticks=199 product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true verdict=PASS`
⇒ **`/data merge` 灌电有效、电源来自场景本身，D-213 判据成立**；D-212 的因果判定确认作废（朝向修正保留）。
**判据语义补充（别把它读强）**：`energy_source=cube` 的判据是**开机那一刻机器自己已有电**（`energy_at_open > 0`），
证明的是"场景把电送上了"，**不是**"程序认出了 cube 方块"（读的是机器自己的能量容器）；
场景里只有这一条供电路径 ⇒ 两者等价。类注释已按此写明。

**教训**：D-212 在**排除法没做完**时就定了根因（漏掉"方块自身是否非空"这一层）。
正解不是打补丁，而是**换一个能证伪的证据源** —— 存档里"同一次保存、一块非空一块空"的对照组，
比任何单向推理都硬。

### D-214：上下文窗口 256K → **512K**（用户裁定 2026-09-14；含复核触发与回退条件）

**来源**：用户主动改 `~/.dsh/settings.yaml`（`llm-deepseek.models[] → deepseek-flash → contextWindow: 512000`，
文件 mtime `2026-09-14 14:23:10 +0800`），并重启 `dsh web` 生效（`dsh-settings` **不监听文件**，改完必须重启）。
实测确认：`tools/dsh-context-usage.sh` ⇒ `window=512000`、`threshold=409600 (0.8×W)`、`retention=81920 (0.16×W)`。

**这个旋钮管什么**：它**不决定"发多少 token"**（每步仍重发完整历史），而是决定**何时压缩**（0.8W）与
**压缩后保留多少原文**（0.16W），并**间接决定每步 prompt 的长度分布**。实测（本会话 `c83b9b33`）：
1M 时代平均 prompt **385,838**（404 请求）、256K 时代 **122,018**（768 请求）⇒ 用户"窗口越大模型越笨"的直觉
**指向真实机制**；但一个压缩周期能装的工作量 = `0.64W` ⇒ 1M 每周期装 4× ⇒ **窗口越小，压缩（唯一不可逆丢原文的动作）越频繁**。
两种效应方向相反，本弧 4 小时内用户手动 `/compact` **7 次**即为代价侧的实测。**A/B 被污染**（两档活动强度差 ~30×）⇒ 不作速率结论。

**决定**：窗口 **512,000**（折中：每周期工作量 327K ≈ 256K 的 2×，阈值/保留同比例放大）。

**复核触发（两条，任一成立就退回 256K）**：① 用户手动 `/compact` 的频率**没有明显下降**（说明省下的按键没兑现）；
② **我出现"忘记已确认事实 / 重复问已答过的问题"**（说明长上下文确实在伤我，用户原始判断成立）。
外部"长上下文退化"证据只有**标题级**（EMNLP 2025 Findings《Context Length Alone Hurts…》等，未读正文）
⇒ 不据它定论，改由上面两条**本项目自身可观察**的事实来裁。

**边界**：① 与"AI 不估算上下文"纪律**正交**（那条纪律的修法是行为规则，不是数值）；
真值只看 `tools/dsh-context-usage.sh`，我**不主动报占比**。② 改窗口**不改变事实来源**：
长期记忆仍是"可验证事实 / 架构决策 / 断点"，坐标与 NBT 形状这类逐字精确的事实仍**先落盘**（AGENTS.md ⑦）。
③ 阈值/保留一律按 `0.8W`/`0.16W` 现算，**不许背死数字**（AGENTS.md 已改成随窗口标注）。

**附注一（同日，用户审查后收紧"提醒"条件）**：我曾在**占用 8%**（`current=40,966 / 512,000`，离阈值 36 万）时按
旧规则"到断点收口就提醒"提示用户敲 `/compact` —— 用户当场指出这是**机械触发**。查源码确认：
① 手动 `/compact` 走 `compactNow` → `selectCompactableRange(session, measure, **0**)`（保留量 = 0）⇒
**只留"系统头 + 最后一段配平尾巴"，其余整段历史一次性换成摘要**（不是按比例裁剪）；
② 固定地板（系统提示 + 全部工具 schema + `AGENTS.md`）本身 ~3–4 万 token ⇒ **低占用时压一次几乎省不下东西**，
却照付"KV 前缀全失效（本会话 99% prompt 是缓存读）+ 一次摘要调用 + 逐字事实变有损"的代价。
⇒ **规则改为"先看真实数字再决定提不提"**：只在 `当前 ≥ 0.5 × 阈值`（现 204,800）或"用户明说要长停顿/收工且
≥ 0.25 × 阈值"时才提醒，其余不提；`AGENTS.md` ④ 已改写。实测旁证：同一会话干**一轮重活**即 +8 万 token
⇒ **"刚干完重活"不能当压缩理由**。

### D-215：S5 收口回收（Mekanism 实验）—— 探针零残留 + "通用 vs 专属"对照表（2026-09-14）

**回收**：`alice:machine_cycle_check` 临时入口删除（物品类 + 注册 + 模型 + 两份 lang）；
**顺带清掉三支已无调用点的 `assign*`** —— `assignMachineProbe` / `assignMachineStationProbe` 是上一轮回收后
留下的**死代码**（`grep` 无调用者），`assignMachineCycleCheck` 的唯一调用者就是本次被删的物品。
⇒ 源码里 `machine_cycle_check` 零命中，`tools/check-item-models.sh` 报 **checked=76**（比上轮 -1）。
**入口替代**：S4 现在**只有**电池步 `machine_cycle`（CORE 29 / FULL 39；第九轮客户端 `(29/29) ticks=3108 → PASS`，
`latest.log:3842`）—— "要单跑就用电池档位，不再复活临时物品"（已写进 `BotManager` 该处注释）。
**顺带修两处陈账**：① `assignCraftCookingCheck` 的 A4b 注释原本**错位**挂在机器探针上方 ⇒ 归位；
② 台账⑦：项数原本写死"26 项"（实际 29）⇒ 新增 `RegressionBatteryTask.coreStepCount()`，文案/注释一律**现算**。
**对照表（S5 核心产出）**：`docs/MOD_ADAPTER_PROTOCOL.md` §6，判据 = 各类中 `mekanism` 字面量计数（只读可复算）。
结论：**执行侧（`StationProvision` 菜单协议 + `WriteBudget`/`WritePolicyMatrix` 闸门）与发现侧
（`MachineStationProbeTask`/`MachineRecipeFacts` 的"问上游自述"）都是通用的（`mekanism` 命中 0~1 处，且只在注释）；
真正专属的只有两处半** = `MachineMap` 这一张表 + `MachineCycleCheckTask` 里 1 行目标方块 + 1 段能量反射
（外加"创造电源怎么造"这种上游语义）。⇒ 下个模组的边际成本 ≈ 填表 + 换 1 行 + 换电源造法，**不需要新内核**。
**复核触发（下轮客户端，1 步）**：重启后 ① 电池仍 `(29/29) … → PASS`；② `/give alice:machine_cycle_check` **不存在**
（探针零残留的用户侧证据）。

**附注一（第十轮实测，2026-09-14）—— 回收注册物品的"一次性自愈警告"（下次别误判成 bug）**：
删掉 `alice:machine_cycle_check` 后**首次进入那个客户端世界**时，Forge 会打：

```
[ERROR] Unidentified mapping from registry minecraft:item   alice:machine_cycle_check: 3405
[WARN]  Forge Mod Loader detected missing registry entries. There are 1 missing entries in this save.
        If you continue the missing entries will get removed.
[WARN]  Invalid statistic in …/stats/<uuid>.json: Don't know what alice:machine_cycle_check is
```

**两条都是一次性且自愈的**（都已用磁盘状态证实，不靠日志措辞）：
① 退出后 `level.dat` 里 `grep machine_cycle_check` = **0** ⇒ 悬挂的注册表条目已被移除并落盘；
② `stats/<uuid>.json` 保存后同样**不再含**该键 ⇒ 非法统计被丢弃。
⇒ **这是"删注册物品"的必然副作用，不是回归**；它会出现在**下一次进世界**的那一轮日志里，
而**恰好就是复核轮**（所以很容易被当成"回收搞坏了什么"）。判据：`level.dat` 归零 = 已自愈。

### D-216：S4 v2 —— 闭环自检**自己走到机器旁**（拆掉"传送点恰好够得着"这条隐式前提）（2026-09-14）

**动机**：v1 的起点在机器东侧 2 格 ⇒ `locate()` 用 `REACH_LIMIT=4.4` 判通过，**"走到机器旁"从来没被验证过**
（那是一条隐式前提）。而要把这台机器的闭环**接进生产路径**（`CraftJob` 的 `MACHINE_ROUTE`），bot 必须真的走过去。

**改法（全部复用已验证原语，零新造）**：① 起点挪到平台远角 `CYCLE_START=(72,64,312)`
（到机器 `dx=dz=6` ≈ 8.49 格，远超交互距离；夹具**仍然自己传送**，纪律不变 —— 拆的是"够得着"而不是"确定性"）；
② 新相位 `WALK`：`TableCraft.standPointNear`（与内核 `canStandCentered` 同口径的"现在就能站"格）+
`PathRequest.of`（**纯通行**，D-076 不挖不搭）+ `PathRetryRunner`（2 次重规划、400 tick 上限）；
③ 判据落在**开菜单那一刻**：`TableCraft.inReach`（眼位→方块中心）不成立就 `machine_out_of_reach`；
④ 删掉 `locate()` 里 v1 的"够不着直接判红"（够不着 = 该走路，不是失败），扫描半径 6→12（远角是压线，半径 6 会让机器
恰好落在立方盒角上）；⑤ 新留痕 `machine_distance_at_locate` / `stand_point` / `walk_state` / `walk_ticks` /
`foot_after_walk` / `machine_reach`（= 开菜单前的眼距）。

**未做（如实）**：**生产接线还没做** —— 本轮只拆前提。接线那一轮必须处理两件红线相关的语义：
(a) **`api_precharge` 兜底绝不能带进生产**（那是测试前提；生产要"没电 ⇒ 如实失败"）；
(b) 目标机器从**路由的 `station`** 来（不是夹具里写死的那一台），化学品/气体 I/O 继续如实拒绝。

**复核触发（下一轮 CORE 电池，零新入口）**：`machine_cycle=PASS` 且日志出现 `walk_state=DONE` + `walk_ticks>0`；
若出现 `walk_skipped=already_in_reach` ⇒ **起点没生效**（要查，别当通过）。

**✅ 复核已通过（第十一轮，`latest.log:3209`／整轮 `:3924`）**：`machine_distance_at_locate=8.5`（定位时**够不着**）
→ `stand_point=66, 64, 305` → **内核自己走完 8 段**（`[R4 Session] completed session=machine-walk-0 segments=8
ticks=40 finalFoot=66, 64, 305`，与断言格**逐字一致**）→ 开菜单那一刻 `machine_reach=1.5`
⇒ `walk_state=DONE walk_ticks=41`（**无 `walk_skipped`**）、`machine_cycle=PASS ticks=251`、
`(29/29) ticks=3116 → PASS`。
**"纯通行"由预算自证、不靠代码推断**：该步 `WriteBudget … breaks=0/64 places=0/32 refusedBreaks=0 refusedPlaces=0`
⇒ 零世界写入；会话**零重规划**（25 条日志 = 8 `segment_start` + 8 `segment_done` + 7 `continuous_advance`
+ `completed` + `Recover`）。**路径形状**：1×TRAVERSE + 6×DIAGONAL + 1×TRAVERSE
（`(72,64,312)→(72,64,311)→…→(67,64,305)→(66,64,305)`；8 段 39 tick + 1 = `walk_ticks=41`，算术闭合），
末段落点在机器**旁格**、**没有**踩进机器自身那一格。
**⇒ S4 v2 达 `WINDOWS_CLIENT`。下一道复核触发 = 增量 2 接线**：生产路径里 `api_precharge` **零命中**（机械可查），
且没电时终态必须是 `machine_no_energy`（如实失败，不许凭空造能量）。

### D-217：(c) 增量 2 —— 机器路线的**生产接线**：一份闭环两处用 + 数据驱动的执行准入 + 补电不许进生产（2026-09-14）

**动机**：S4 v2 已验证"闭环能自己走到机器旁"，但 `CraftJob` 对 `MACHINE_ROUTE` 仍 `not_executable` 如实拒绝 ⇒
"读得出路线"与"真的能做"之间还差这一层。用户 2026-09-14 裁定：**抽公共执行器**（不是生产侧另写一份）+ **新增生产电池步**。

1. **一份实现两处用**：闭环本体抽到 `task/craft/MachineCycle`（走 → 开 → 电 → 放料 → 等 → 取 → 复核）。
   夹具 `MachineCycleCheckTask` 变**薄壳**（只留：传送/复位、按类型挑配方、按前提备料、**补电兜底**、SUMMARY）；
   生产 `CraftJob.MACHINE_ROUTE` 调**同一份**。事实出口走 `Sink`（夹具接进自己的 SUMMARY，生产收成一段
   `[CraftJob] machine SUMMARY`），日志标签由调用方给 ⇒ 两边的留痕键**同源**，可用同一张判据表对账。
2. **执行准入是数据驱动的**：`MachineMap` 新增 `executable(...)` 行构造，把 `mekanism:enriching` 升为
   `Capability.EXECUTABLE`（三条件：① 有执行适配器 ② `menuClass` 已客户端实测 ③ 有客户端验证记录 —— 后者的
   证据是 D-213/D-216 的 S4 闭环 + 第十一轮 `latest.log:3209`）。`CraftJob` **只驱动 EXECUTABLE 的行**，
   其余照旧 `not_executable:<station>`。`tools/machine-map.py` 现在解析 `executable(...)` 并把 capability
   如实写进 `docs/MACHINE_MAP.csv`（此前 CSV 只写 READ_ONLY ⇒ 人读视图会把"能驱动"说成"只读"）。
3. **红线①变成机械可查**：执行器里**没有任何造能量的代码**；唯一通道是调用方注入的 `EnergyTopUp`，
   **只有夹具实现它**，生产**位置传 `null`**（生产文件里连类型名都不出现）。门禁
   `tools/check-precharge-containment.sh` 三条断言：造能量的**调用**只命中夹具文件 / 注入点命中面 = {定义, 夹具} /
   **反向断言**夹具里那些符号还在（防一次重命名让门禁静默变绿）。**已做反向测试**：往 `CraftJob` 注入一次
   `precharge(…)` ⇒ 门禁立刻报红并点名文件:行。

**范围（如实）**：本轮只支持**单物品输入**的机器（与 S4 实测同形）；多输入、化学品/流体输入如实拒绝
（`multi_input_not_supported` / `non_item_input`）。缺料在**出发前**判掉 —— 不走到机器旁才发现喂不进去。

**新测试入口**：电池步 `craft_machine`（MAIN）⇒ **CORE 29→30 / FULL 39→40**。夹具**不碰闭环任何一步**，
只做测试专属三件事：传送平台远角 / 挑"只能靠机器做出来"的目标物（用**生产查询层**现场复核，
候选是离线对撞 jar×配方表得来的，见夹具注释）/ 按前提备料；判据要求**生产真的走了机器路线**
（`machineFacts` 非空 + `walk_state=DONE` + `product_landed`/`machine_emptied`）—— 否则可能是合成/熔炼路线，那就是假绿。

**复核触发（下一轮 CORE 电池，零新入口）**：`(30/30) → PASS`、`craft_machine` 步内 `job_terminal=DONE` +
`m_walk_state=DONE` + `product_after=product_before+1`；且 `machine_cycle` 仍绿（夹具换薄壳后无回归）。
**失败即回退判据**：`craft_machine` 红而 `machine_cycle` 绿 ⇒ 生产接线的问题；两步同红 ⇒ 抽执行器时把夹具改坏了。

**✅ 复核已通过（第十二轮客户端，2026-09-14 16:58–17:01，`(30/30) ticks=3370 → PASS`，`latest.log:3899`）**：
两条硬判据都拿到，而且**生产那段路与夹具逐字相同** —— `[R4 Session] completed session=craft-machine-walk-0
segments=8 ticks=40 finalFoot=66, 64, 305`（25 条会话日志 = 零重规划）⇒ `m_walk_state=DONE m_walk_ticks=41
m_machine_reach=1.5`；`m_energy_source=present（…）` 且 **`api_precharge` 全日志零命中** ⇒ 红线①在客户端成立；
`m_product_landed=true m_machine_emptied=true m_input_consumed=true m_container_writes=2` + `product_after=1`
（`[CraftJob] machine SUMMARY … machine_cleaned_before=skipped（生产路径不动机器里的存量）… verdict=PASS`）。
**"后备候选"这一手被真实用上**：首选 `clay_ball` 的路线落在 `mekanism:chemical_injection_chamber`（**无执行准入**）
⇒ 生产如实拒绝、夹具换到 `soul_soil`（`mekanism:enriching/conversion/soul_sand_to_soul_soil`）⇒ 说明
(a) "只驱动 `EXECUTABLE` 的行"这道闸**真的在拦人**；(b) 同一产出可能有多台机器，而查询层取 `machineRoutes.get(0)`
**不按准入过滤**（已记台账⑬）。**夹具侧无回归**：`machine_cycle=PASS ticks=251` 与第十一轮**逐字相同**。
用户目视确认 bot 从平台远角**自己走过去**（第十一轮缺的那条目视证据补上了）。

### D-218：机器路线**按执行准入优先排序**（只排不删；台账⑬ 收口）（2026-09-14）

**问题（第十二轮实测暴露）**：同一产出可能有多台机器能做，而 `RecipeQuery` 取 `machineRoutes.get(0)`
= **配方管理器迭代序**，**不看 `MachineMap.Capability`** ⇒ 生产侧"能不能做"部分取决于迭代序：
`minecraft:clay_ball` 先命中 `mekanism:chemical_injection_chamber`（无执行准入）⇒ `CraftJob` 如实拒绝，
其实富集仓也能做它（`mekanism:enriching/clay_ball`）。

**改法（最小）**：`RecipeQuery` 返回 `MACHINE_ROUTE` 前把候选路线**只排不删** —— 主序 = `hasExecutor(station)`
（有准入的在前），次序 = `recipeId`（⇒ 同一次查询的结论**确定**，不随迭代序漂）。`Result.note` 里写清
"挑了哪台 / 有没有准入 / 同类路线共几条、其中几条有准入"。**不删**是为了保住既有语义："读得出路线就报得出"
（S1 的只读承诺），拒绝仍由 `CraftJob` 那侧按 `EXECUTABLE` 如实做。

**这不是能力扩张**：准入档（谁能被执行）**仍然只有 `MachineMap` 一个出口**；本次只改"多条里先给哪一条"。

**观测点**：电池步 `craft_machine` 新增 `fallback_used`。修好后首选候选应**直接可用** ⇒ 期望 `fallback_used=false`
且 `target=minecraft:clay_ball`（第十二轮的 `true` + `target=soul_soil` 就是本问题的证据）。
**若又变 `true`** ⇒ 要么排序没生效，要么同一产出又多了台**未准入**的机器（先查后者，那是产品问题不是回归）。

### D-219：模组适配改成「需求驱动」——给"无限适配"装停止规则（用户 2026-09-14 采纳 `survey/07` §3.5）

**背景（`survey/07` §3，本次方向审核最重要的一条）**：目标整合包 ATM9 有 **435 个模组**，而已适配
Mekanism（S0→S5）/ Thermal（S0→S2）。单模组**边际成本很便宜**（"填表 + 换 1 行目标 + 换 1 个电源造法"），
但它是**「每模组」成本** ⇒ 按当前节奏适配完科技模组是**几个月**，且**没有天然停止点**：
每适配一个模组都像一个可验收、可收口的闭环。

> ⚠️ 这与**台账自我生成**是同一个陷阱换了个维度：从一个"永远有货"的收口清单，
> 换成一个"**永远有下一个**"的适配队列。

**决定（用户采纳，即时生效）**：模组适配从**覆盖率驱动**改成**需求驱动** ——
**只为当前存档真正用到的东西做适配**；不因为"这模组有名 / 已在 `mods/` 目录里 / 表里凑齐全量"就登记它。

**依据（不是偏好，是成本曲线）**：真正的产品承诺是**蓝图 §1.2「玩家登记自己的流水线」** ——
那条路线**模组无关**（输入/输出/缓存端点 + 插入/提取用标准 capability 就够 ⇒ **C1/C2** 兼容级别，
机器自己会干活，Alice 不需要理解它）。而 3-B 的机器理解（`MACHINE_ROUTE`/`EXECUTABLE`/`MachineMap`）
是**另一条**、**逐模组**、需要 **C4** 的路线，成本是 **线性 × 435**。
⇒ 覆盖率驱动会把"砸钱"变成无底；需求驱动把它变成有界。

**落地**：
- `AGENTS.md`「不可悄悄改变的架构边界」加一条（净增 0 行 —— 按文档预算纪律等量删减）；
- **对 T3 的直接后果**：`MachineMap` 不再"接第 3 个模组就得补齐 19 行"（Create 15 + EC 4）。
  那 19 行**当前买不到任何功能** —— 见下条**事实**，故**按本条推迟**，等真实存档需要某台 Create/EC 机器时再加。
- **复核触发**：① 真实存档里第一次需要走某台未登记机器的路线；② 或玩家开始用"登记流水线"（蓝图阶段 D）。

**同轮实测到的事实（支撑上面的"买不到功能"）**：
`RecipeQuery` 对 Create/EC 的物品**已经**如实报出路线（步骤 C/M-4：`create:andesite_casing →
MACHINE_ROUTE station=create:item_application`，note 明写"暂无该机器的执行适配"）——
因为 B3a 让它走**原版语义**读得出产出。**加表行只把 `station` 从"类型 id"换成"方块 id"**，
而**执行准入仍然全部 `READ_ONLY`**（Create/EC 没有执行器）⇒ **判决与能力都不变，只是显示更好看**。

### D-220：验证通道的"声明必须为真"——夹具时机按场景复位 + 声明了夹具就必须断言它真的动手 + 无头通道清怪物（2026-09-15）

**背景（一次实测同时暴露三处"声明/环境与事实不符"）**：本轮原计划做"`pathing` 子用例起点契约"，
实测**否掉了**那个假设（改前 `single:pathing` 3/3、`core` 3/3 全绿且场景行逐字相同 ⇒ 抖动不复现），
转而在同一区域查出三处**可证**缺陷。全文与证据：`docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`。

1. **夹具时机基准失效**（`PathingRegressionTask`）：`wallTick`/`disturbTick` 写 `30`，比较的却是
   **任务级** `ticks`（从不按场景复位，进第 15 个场景时已 ≈300）⇒ `>=30` 恒真 ⇒ 夹具在**第一个执行 tick**
   动手（日志 `wall_placed … tick=346`）。两个自愈场景**从未测过"中途"**；扰动找不到落点时还
   `disturbed = true` **假装做过**。**对照**：手机流（`PathSessionDiagnosticTask`/`PathingDisturberItem`）
   的 `DISTURB_TICK=30` 是"本次运行第 30 tick"，且 `docs/AI_TEST_MATRIX.md` 写的就是"第 30 tick 封路" ⇒ 常量搬来了、基准没搬。
2. **覆盖断言此前靠运气过**：`REQUIRED_COVERAGE` 里的 `DIAGONAL` **没有**任何"真的会执行对角线"的场景
   （`dip_course`/`lava_course` 都只规划不执行）——它只来自 `+disturb` 修前那条偶发绕行。① 修好后
   电池**如实变红**（`coverage=FAIL([DIAGONAL])`）。
3. **无头通道并非无噪声**：实测 `假人死亡: Alice was blown up by Creeper → 直接清除` ⇒ 电池**无判决 exit=3**；
   同轮更早 bot 已被**推离预期格**（`feet=1,64,68`，起点 `z=66`）⇒ `PLACE_NO_VALID_FACE`/`*_STALE_START`
   这类"位置不对"的码都能由它造成。夹具**不生成**敌对生物（场景只 summon marker）⇒ 噪声来自环境。

**决定（即时生效，三条都挂在已有命令上，不新增散文规则）**：
- **① 夹具时机一律用"场景局部"基准**：`PathingRegressionTask` 加 `sceneTicks`（runner 建好那刻置 0），
  `ticks` **只**留给总预算并加注释禁止再做场景内时机判断。
- **② 声明了夹具就必须断言它真的动手**：场景判据加 `wallTick>0 ⇒ walled`、`disturbTick>0 ⇒ disturbed`，
  否则该场景 FAIL（detail 带 `/FIXTURE_NOT_FIRED=…`）；**删除静默降级**分支（改为 warn + FAIL）。
- **③ 无头电池跑 `peaceful`**：`tools/headless-battery.sh` 启动前把**无头服务端自己**的
  `server.properties` 设成 `difficulty=peaceful` 并打印 `夹具洁净度：difficulty=…`。
  脚本本来就拥有该目录（`--install`/拷世界/装数据包/清 `alice_*.dat`）⇒ 环境是它的职责；
  `peaceful` 连**存档里已有的**敌对生物一起清（`spawn-monsters=false` 做不到）。
  ⇒ 与客户端**有意不同构**（客户端仍 `easy` 有怪物），待用户拍板是否也在夹具层清场（评审 §6.4）。
- 附带：`dip_course+run`（复用 `dip_course_terrain`，`+` 后缀取地形）把 `DIAGONAL` 变成**确定性**覆盖。

**验证**（`SERVER_TESTED`，无头生产服务端）：修①②③ 后 `core` ×3 = **PASS 3/3**、`coverage=PASS`、
`30/30`、`grep -ci 'creeper|假人死亡'` = 0、**三轮 pathing 场景行逐字相同**；
夹具位置与 2026-09-09 **客户端验证过的**记录一致（`wall_placed at=5,64,66`，见 `AI_DECISIONS.md:569`），
而修前电池打的是 `at=2,64,66` —— **不是同一个场景**。

**复核触发**：① 再出现"位置不对"类红且日志里有怪物/击退痕迹 ⇒ 客户端侧也要清场；
② `FIXTURE_NOT_FIRED` 出现 ⇒ 说明某个场景的课时长已短于夹具时机，要重定标（而不是把断言删掉）。

**附注（同日追加）：把"同一份夹具两处实现"合并 —— 消掉这一类，而不是再打一个补丁。**
D-220 的三条修完之后发现：真正的病因不是"某个常量算错了"，而是**「计划前方封路 / 定向平移」这两个夹具
在电池侧与物品侧各写了一遍**，且已分叉三处、**没有任何编译期信号**：① 时机基准（任务级 tick vs 本趟运行 tick）；
② 封路目标格（`MovementHelper.footCell` vs `bot.blockPosition()` —— 非满高支撑上取到不同格）；
③ 放弃策略（两边都静默）。
⇒ 新增 **`task/FixtureScript`** 收拢**能共用的部分**：`wallPlan(bot, projectedPath)`（脚位一律 `footCell`）
与 `notFired(missing)`（两侧同一字段串 `/FIXTURE_NOT_FIRED=[…]`，`grep` 一处通吃）；
**不共用**的是各自主持有的时机计数器与终态策略 —— 但按本条，**两边都必须响亮**。
物品侧（`PathSessionDiagnosticTask`）随之补掉两处静默降级：扰动找不到落点不再 `disturbed = true` 假装做过
（改 `disturbGaveUp` + warn），封路/扰动**未生效** ⇒ 结果行带 `/FIXTURE_NOT_FIRED=…` 且**任务 `FAILED`**
（旧行为：扰动静默跳过、封路**连一行日志都没有**，两者都可能"干净地报 DONE"）。
**验证分级**：共用原语 `SERVER_TESTED`（电池侧调用它 ⇒ `single:pathing` 场景行**重构前后逐字相同**、
`core` ×2 `30/30`、累计 **core 10/10**）；**物品侧接线 `COMPILES`**（客户端入口，验收点 = 下一次 R4 客户端轮次，
见 `docs/TESTING_GUIDE.md` §4.7 的新预期）。

**附注二（2026-09-15 第十九轮客户端验收，jar `462b10b5…`）**：D-220 的**电池侧**升 `WINDOWS_CLIENT` ——
客户端右键一次电池：`(30/30) ticks=3337 → PASS`、`K4=OK(0/0)`，`wall_placed at=5,64,66 sceneTick=30 pathIndex=3/9`、
`disturbed … sceneTick=32`、`coverage=PASS`（9 种 Movement 全到）、`FIXTURE_NOT_FIRED` 命中 0；
**pathing 步 54 行场景日志与无头 `core` 逐字相同**（跨通道等价 —— 这条本身是新增证据：客户端与无头在
整段寻路回归上**逐 tick 一致**）。比对方法有坑：Windows 日志是 **CRLF**，直接 `diff` 会把 54 行全报成不同 ⇒ 先 `tr -d '\r'`。
**同轮 T3 探针读数复核**：42 字段中 **41 个与无头逐字相同**，唯一差异 `recipe_order_hash`（已登记为非确定值）
⇒ **T3 探针读数亦升 `WINDOWS_CLIENT`**。⚠️ **R4 夹具物品本轮未跑**（全会话 `[R4 Fixture]` 命中 0）⇒
物品侧"未生效 ⇒ FAILED"仍是 `COMPILES`。证据 `.alice-supervision/client-tests/d220-t3-20260915/evidence/`。

**附注三（2026-09-15 同日补测：R4 物品侧，jar 未变）**：两件夹具物品在客户端各右键一次 ——
`pathing_disturber`：`[R4 Fixture] disturbed from=4,64,66 to=4,64,67 tick=33` + `result … COMPLETED segments=8/8 ticks=69 replans=0`；
`pathing_waller`：`[R4 Fixture] wall_placed at=5,64,66 tick=30 pathIndex=3/9` → `replan reason=BLOCKED code=SEGMENT_FUTURE_BLOCKED`
→ 新计划首段 `BREAK_AND_TRAVERSE`（**用户观察"把石头挖掉"与日志逐字对上**）→ `result … COMPLETED segments=3/3 replans=1`。
`FIXTURE_NOT_FIRED` / `not_fired` / `disturb_not_applicable` 命中 **0**、全会话零 WARN/FAILED ⇒ 物品侧新接线走的是
**"夹具已生效"的正常分支** ⇒ 该分支升 `WINDOWS_CLIENT`。**⚠️ 负例分支（夹具未生效 ⇒ FAILED）仍未跑**
（两件物品的起终点硬编码同一场景，必然放得下 ⇒ 要跑负例得再做一个"注定放不下"的场景）。
另注：disturber 的 1 格横向位移**既不触发漂移检测也不触发 resync**（`driftedOutOfSegment` 要求离两端 >3 格），
正确结果由**控制器把 bot 拉回目标格**达成 ⇒ 用户"看不出来"是预期，已登记为按 Baritone
`getValidPositions().contains(feet)` 对齐的独立议题。
**同轮记录一个议题（用户 2026-09-15 提出，只记录不展开）**：风险管控体系现状 ——
"统一可调的风险层"确实接近空白（`RiskSwitches` 仅 1 个开关、评估体系自述未实现），
但风险在**授权/预算/策略/安全四层**都有可执行闸门 ⇒ 详见台账 **§5.6**（含三个候选方向与复核触发）。

### D-221：挖矿范例的三条对齐裁定（A1–A3，用户 2026-09-15 确认）+ M 线启动与顺序

来源：`survey/08-挖矿范例与风险系统形态-20260915.md`（勘测员）+ `docs/reviews/2026-09-15-survey08-可行动条目核实.md`（主工作流复核）。
用户 2026-09-15 对 §8.1 三条**零成本决策逐条确认**。

**A1（停止条件，用户拍板）**：挖矿范例的完成判据 = **"一次长作业自主闭环，全程不需人插手"** ——
拆为四段：**起任务 → 中途自己发现问题 → 自己决定续 / 改 / 收 → 收工回待机**。
⇒ **用它替代"挖矿任务的最终形态"**（后者没有底，是 `survey/07` §3.5 说的那个形状）。
⚠️ 含义：**A1 让 M2（长作业周期复评）成为停止条件的必要条件** —— 没有周期复评，"自主"在物理上不存在，
只有"一次长动作"（`survey/08` §7 的判断，主工作流认同）。

**A2（对齐，改项目叙事）**：**挖矿 = 蓝图「阶段 B · 通用助手闭环」的一项**，与**木材（主线正在做的伐木）同阶段**。
蓝图 §8 阶段 B 八项里**仅 3 项有实现**（挖矿 `job/mine/MineJob`、木材 `job/lumber/LumberJob`、跟随 `task/FollowTask`）
⇒ **挖矿与主线是并行，不是转向**。后续会话**不得再把"做挖矿"当成转向重议**。
复核入口：`docs/archive/legacy-2026-08/PRODUCT_ARCHITECTURE_ROADMAP.md` §8。

**A3（空类目登记）**：**阶段 C（语义接口与事务搬运）与阶段 D（= 蓝图 §1.2「玩家登记自己的流水线」）是空类目** ——
`PipelineProfile` / `ProcessContract` / `ProductionPlanner` / `WorldProfile`（D）与
`ReadOnlySnapshot` / `InterfaceDescriptor` / `simulateInsert` / `TransferTransaction` / `InventoryReservation`（C）
在源码中**命中数全为 0**；而蓝图 §8 自述"阶段并非严格串行……**共享移动、库存事务、权限与结果契约必须作为主线先稳定**"。
⇒ **"需求化模组适配"（D-219）的真实入口在阶段 C，而它是空的** ⇒
**"下一步是登记流水线"不得被当成默认前提**（它不是"难"，是前面那一格没铺）。

**M 线启动与顺序（用户 2026-09-15 批准）**：采纳 `survey/08` §8.2 顺序
**M1 → M2 → M4 → M3**（含勘测员的 **M4/M3 对调**：先把失败事实**结构化**再搬运归因，
理由是 `LumberJob.deriveTopLevelReason` 用的是 `f.contains("no_suitable_tool")` 这类**自由文本匹配**，
照搬会把脆弱写法复制一份 —— 主工作流已复核确认该写法存在），其后 M6。
**验证纪律**：每件以 `./gradlew compileJava` + `bash tools/check-all.sh` + 无头电池 `core` 的**可判据**验收，
判据沿用 `survey/08` §8.2 表（除 M3/M4 顺序外不改）。

**附注一（2026-09-15 撤销 M5，更正两处错误结论）**：`survey/08` §8.2 第 5 条与
`docs/reviews/2026-09-15-挖矿高级任务作为框架验收范例-完成度审计与规划.md` §1.3 都称
`BotManager.tryRecoverUnfinishedTeardown` **"全仓 0 调用者"** ⇒ **不成立**：
它**已在假人生成时被调用**（`BotManager` 生成路径内，紧接 `reportRestartState(bot)`）。
⇒ **M5 撤销**（"最便宜的一件"这个判断建立在错误前提上）。
**教训**：`grep -n <符号>` 找调用点时必须排除定义行并看**同文件内的调用** ——
本次三路审计都在 `BotManager` 之外的包内搜调用点，漏掉了同类内的那次调用；
**"0 调用者"这类否定性结论要求"含定义文件的完整 grep + 人工确认"**，不能只靠 `-l` 的文件计数。

### D-222：M1 —— 挖矿不许猜位置（候选菜单 + 菜单 id 强制 + 取消 IRON_ORE 静默回落）（2026-09-15）

**动因**：`survey/08` §5.7 审计的 **G1**（最承重缺口）：`CandidateMenu.build` 只产
lumber/collect/region/craftable 四类，**`mine` 没有菜单条目**；而 `GoalAction.parseStartJob` 对 `mine`
**不校验 `target`** ⇒ `center` 只能是 `botPos` ⇒ **LLM 事实上在猜位置**（类注释自己记着"没给菜单 ⇒
实测 1 tick `no_reachable_candidate`"，那条教训只修到 lumber/collect）。

**改法（五处，均为最小面）**：
1. `MiningBudget`：把散在 `tierOf` 里的两处矿石判定收成**唯一两份定义** ——
   `COMMON_ORE_TAGS`（常见矿石标签，2× 档）与 `RARE_ORES`（稀有矿石方块，4× 档）。
   **行为不变**（稀有先判、常见后判，顺序与集合逐条等价）；J-6 的"不许长出第二份矿物清单"由此可执行。
2. `CandidateMenu`：新增 **③ 矿物**一节，**复用 Job 自己的候选源** `MineCandidateSource`
   （与 lumber 复用 `LumberCandidateSource` 同一纪律）⇒ 菜单里出现的矿 Job 一定选得中
   （`block@x,y,z` 就是 `MineJob` 决策日志的 id 口径）。条目 `extra` 带**确定性层算出的** `block=` / `y=`；
   扫描半径单列 `MINE_SCAN_RADIUS=12`（菜单是**有界感知**，不是作业搜索；11 个目标 × 25³ ≈ 17 万次读取，
   与树的 24 格扫描同量级）。新增 `CandidateMenu.extraValue` 供动作层取值。
3. `GoalAction.parseStartJob`：`mine` 与 lumber/collect **同列**进 `needsTarget`；
   挖掘的 `productTag` **只取菜单条目的 `block=`**（LLM 自写的只作"与菜单不一致"的提示 ⇒ 记 clamp，**不采信**）；
   `radius` 自动放大到**覆盖被选目标**（否则 Job 会在半径外找不到它）；菜单里没有 mine 条目且未给 target ⇒
   专有拒绝码 `no_mine_candidate`（比笼统的 `missing_or_unknown_target` 更诚实）。
4. `JobLauncher`：新增 `refusalReason(bot, request)` —— **请求级前置拒绝**。为什么不能用异常：
   `create` 抛出的异常会**穿过 `assignJob` 冒到调用方**（`GoalDirector` 的 `catch` 只包 LLM 回复、不包动作执行）
   ⇒ 有把服务端 tick 打崩的风险；而 `assignJob` 本来就返回 `boolean` ⇒ 用"拒绝 + 如实记日志"表达。
   `mineTargetFor` 的两处"**静默回落到 IRON_ORE**"删除（那是在**猜语义**：LLM 写错方块 id 会去挖铁矿石），
   改为防御性 `IllegalArgumentException`（生产路径已被前置拒绝挡住）。
5. `BotManager.assignJob`：在发料/构造**之前**调用 `refusalReason`，非 null ⇒ `warn` + 返回 false。

**判据（新电池步 `mine_menu`，`Profile.MAIN` ⇒ **CORE 跑**）**：`src/main/java/com/dddgn/alice/task/MineMenuCheckTask.java`
（纯逻辑、不改世界、不调 LLM；矿石场景 `alice_test:ore_course_terrain` + 自带传送 + 结束复位）：
菜单含 mine 候选且带 `block=`；缺 target / 未知 target ⇒ 必须 `Refused`；命中 ⇒ `center` 取候选位置、
`productTag` **逐字等于** `block=`、`radius` 覆盖目标距离、该请求通过前置检查；LLM 自写冲突 ⇒ 以菜单为准 + clamp；
空/未知 `productTag` 前置拒绝非 null、合法请求必须为 null（防止前置检查过宽挡住正常挖掘）。

**验证**：`./gradlew compileJava` ✅；`bash tools/check-all.sh` 9 PASS + 1 预期 WARN ✅；
**无头 `core`：`(passed=31/31 skipped=0) ticks=3404 → PASS`**，`K4=OK(goal_not_standable=0 …)`，
`[Goal] candidate_menu mine=6`、`[MineMenu] SUMMARY checks=11 failures=0 [] → PASS` ⇒
**`SERVER_TESTED`**（客户端未跑过这一版）。

**策展**：`BATTERY_CURATION` 更新为 **41 项 → CORE 31**（新增 `mine_menu` 记 MAIN）。
**刻意不进 EXTRA**：它是"不猜语义"红线的门禁，必须每次改动都跑得到。

**附注一（顺带发现，未处置）**：`decision_contract` 步的归属是 **EXTRA** ⇒ **CORE 不跑它**，
但其类文档写着"这样它们能进串联回归电池，**任何改动都跑得到**" ⇒ **两者矛盾**（D-149 的判据承诺 vs
后来的瘦身档位）。要不要把它提到 MAIN/BASELINE 属**策展裁定**，本轮不动，已记台账 §5.7。

### D-223：M2 —— 长作业周期复评（无进度 ⇒ 一次 `NO_PROGRESS`）（2026-09-15）

**动因**：`survey/08` §5.7 审计的 **G2**（第二承重缺口）：触发源只有"终态 / 空闲（默认关）/ 维生中断 /
四个事件阈值"，**没有任何周期进度复评** ⇒ 一个 `maxTicks` 很长的 Job **开始响一次、结束响一次**，中段对决策层是黑箱。
`survey/08` §7 的判据把 M2 定为**停止条件 A1 的必要条件**："**M2 是'自主'这个词的物理载体。
没有它，'自主长作业'在物理上不存在，只有'一次长动作'**"。

**改法**：`EventThresholds` 新增第三类病症 `NO_PROGRESS`。
1. **可观测进度的定义**（三者任一变化即算有进度）：① 任务的 `Job.progressSummary()`；
   ② bot 脚位；③ 背包指纹（物品 id + 数量 + 耐久）。⇒ **走路中的任务不会误报**（脚位在变）。
   ⚠️ **为什么不能用 `currentTaskSummary()` 当指纹**：它是 `taskKind + target + startedTick`，
   **整个任务期间恒定**（实测代码），拿它当"没进度"会**每个长任务都误报**。
2. **等容器交互时跳过**（`containerMenu != inventoryMenu`）：等待态不是病症 —— 与 `STUCK` 判据
   "必须**有移动意图**"同一条纪律（否则等菜单/等请示会被报成卡住）。
3. **默认窗口 `NO_PROGRESS_WINDOW_TICKS = 0` = 关**（对齐 `LlmConfig.idleDecisionEnabled` 同为默认关的既有取舍）；
   打开它 = 增加决策调用频率 ⇒ 由使用者显式开启。`setNoProgressWindow(int)` 供配置/夹具。
4. **同 episode 只报一次**；进度一恢复即**重新武装**（滞回的自然形式，不需要第二个比例阈值 ——
   对照 `TOOL_LOW` 需要 `TOOL_REARM_RATIO` 是因为耐久会来回抖，而"有没有进度"本身是二元事实）。
5. 夹具接缝沿用既有纪律（`resetStuckTracking` 的先例）：`resetNoProgressTracking` / `noProgressEmits` /
   `noProgressReported`。

**判据（新电池步 `no_progress`，`Profile.MAIN` ⇒ CORE 跑）+ `task/NoProgressCheckTask`**：
用**本夹具自己的停滞**当被观察对象（**不造假 Job** —— 被断言的是真实的 `EventThresholds` 判据链）：
① 窗口关着时**不许**报；② 开窗 40 tick + 有任务在跑 + 无可观测进度 ⇒ 报**恰好一次**且标记已报；
③ 出现进度（脚位变化）⇒ **重新武装**，再停滞 ⇒ 报**第二次**（证明滞回是活的）；
④ 自检窗口内 `GoalDirector.isSuspended=true`（**只记录不通知**决策层）；⑤ **收尾把窗口复位回 0**
（它是 `static volatile` 全局量，不复位会让后面的电池步骤在"开着监控"的状态下跑）。

**验证**：`./gradlew compileJava` ✅；`bash tools/check-all.sh` 9 PASS + 1 预期 WARN ✅；
**无头 `core`：`(passed=32/32 skipped=0) ticks=3511 → PASS`**（`baseline=14 main=18 extra_skipped=10`），
实测两行 `[Events] NO_PROGRESS … progress=…|56, 63, 132|…` 与 `…|57, 63, 132|…`（脚位变化 ⇒ 重新武装生效），
每行后面紧跟 `[Events] NO_PROGRESS 已记录（自检暂停：不通知决策层）`，`SUMMARY emits=2 failures=0 → PASS`，
`结束复位：窗口已关=true` ⇒ **`SERVER_TESTED`**（客户端未跑过这一版）。

**策展**：`BATTERY_CURATION` 更新为 **42 项 → CORE 32**（`no_progress` 记 MAIN）。
**⚠️ 未做（诚实标注）**：窗口目前**只能由代码/夹具设置**（`setNoProgressWindow`），**没有**接进
`config/alice-llm.json`；"生产默认开启 + 窗口值可配"是**后续一次决策**（与 M6 的常驻维度同批更合适）。

### D-224：M4 —— 终态失败事实必须是「字段」（phase + 有界 details）（2026-09-15）

**动因**：`survey/08` §5.7 审计的 **G3**（第三承重缺口）与 `survey/08` §7 的**措辞更正**：
真因**并没有丢** —— `MineJob` 早已把逐候选的 `rejected()` 拼进 failure 串
（`failure = terminalReason + " " + String.join(",", set.rejected())`），它一路进
`BotManager.lastTaskResult` 与 `DecisionSnapshot.task.lastResult`。**问题是形态**：
LLM 拿到的是一段**无结构文字**（"看得到、难以可靠分支"）。
⇒ **M3/M4 的价值是"把文字变成字段"，不是"补回丢失的信息"**（这条更正由勘测员提出，主工作流已复核确认）。

**改法**：
1. `DecisionSnapshot` 抽出 **`lastTerminalJson(TaskExecutionRecord)`**（`public static` = **自检接缝**，
   与 `EventThresholds.resetStuckTracking` 同类），`lastTerminal` 新增两个字段：
   - `failurePhase`（相位；此前只有 `failureCode`）；
   - `failureDetails`（细节，**有界**：截到 `MAX_FAILURE_DETAILS = 240` 字符并**如实标 `…`**）。
     ⚠️ **为什么必须有界**：`MineJob` 的 details 是"逐候选拒绝理由串"，60 个候选就能到几百字符 ⇒
     不设上限会**灌爆 prompt**（`survey/08` §8.2 的判据原文即含"快照字符数仍受有界预算约束"）。
2. 没失败时**不写**这几个字段（避免 LLM 照着上一轮的 stale 失败做决定）。

**判据（进 `llm_contract`，基-5 / **J-4 同一个契约**：J-4 已断言"四个 Job 真的覆写了 `failureReport()`"，
M4 补上后半截 —— "覆写了之后**决策层真的看得到**"）**：新增 `snapshot_failure_fields`：
① 字段齐（`failureCode`/`failurePhase`/`failureDetails` 且 phase 逐字等于报告）；
② 超长 details **必须截断**，长度**恰好** = 上限 + 1（含 `…`）；
③ **无失败时不许留 stale 字段**。判据用**构造出来的**终态记录（`syntheticRecord`），
不必先真把任务跑失败一次 —— 这正是把 JSON 构造抽成自检接缝的收益。

**策展**：`llm_contract` **EXTRA → MAIN**（理由同 M1/M2：**门禁必须默认跑得到**）。
`BATTERY_CURATION` 更新为 **42 项 → CORE 33**（BASELINE 14 + MAIN 19 + EXTRA 9）。

**验证**：`./gradlew compileJava` ✅；`bash tools/check-all.sh` 9 PASS + 1 预期 WARN ✅；
**无头 `core`：`(passed=33/33 skipped=0) ticks=3528 → PASS`**（`baseline=14 main=19 extra_skipped=9`），
`case=snapshot_failure_fields result=PASS fields=true bounded=true(241/240) noStale=true` ⇒
**`SERVER_TESTED`**（客户端未跑过这一版）。

**⚠️ M4b 未做（明确推迟，不是漏）**：`tree[].lastFailure` 仍为空 —— Job 的 `subTasks()` 没填
（`MineJob` 甚至没有覆写），要填得**逐 Job 定义"哪个子阶段失败"**（语义工作，不是接线）。
它与 **M3**（专有终态理由）同域 ⇒ 合并到 M3 那一轮更省。**登记在台账 §5.7**。

### D-225：M3 —— 专有终态理由（缺工具不许被说成"没矿"）（2026-09-15）

**动因**：`survey/08` §5.7 审计的 **G3** 后半截 —— 总括码会把真因盖掉：
`MineJob` 在"配额未达成"时只报 `minedCount > 0 ? "partial_quota" : "no_reachable_candidate"`，
于是**缺镐**（每个候选都 `no_suitable_tool`）被报成"**没矿**" ⇒ 决策层据此选的下一步**必然错**
（它该去弄工具，而不是换个地方挖）。`LumberJob` 早有归因先例（`tool_missing` / `climb_incomplete`），
但它用 `f.contains("no_suitable_tool")` 在**拼接串**上做子串匹配 —— 既会误伤位置串，也禁不起词表演化。

**改法**：
1. **结构化**：`MineJob.attemptFailures` 由 `List<String>`（"位置:码"拼好的串）改为
   `List<AttemptFailure>`，`record AttemptFailure(BlockPos pos, String code)` + `describe()` 供日志。
   ⇒ 归因**逐码精确比较**，这是 M4"把失败事实变成字段"的直接收益。
2. 新增 `deriveTopLevelReason(base)`：**只**对"目标被尝试过、但一个都没成功"这个总括码归因
   （`no_reachable_candidate`；`partial_quota` 与其它终态**不许被逐目标理由盖掉** —— 与 `LumberJob` 同纪律）：
   - 全部码 ∈ `TOOL_CODES` ⇒ **`tool_missing`**；
   - 全部码 ∈ `BUDGET_CODES` ⇒ **`write_budget_exhausted`**；
   - 只剩 `target_replaced` ⇒ **`stale_target`**（世界在决策后被改动，诚实说法）。
3. 两张码表都取自**既有词表**（不是新造）：`TOOL_CODES = {no_suitable_tool, tool_missing}`（`MineTask.toolRefusal`）；
   `BUDGET_CODES = {WRITE_BUDGET_EXHAUSTED, write_budget_exhausted, prod_budget_exhausted}`
   （分别来自 `BreakAndEnterExecution` / `PlaceStepAndTraverseExecution` / `DownwardExecution` / `PlaceTask` / 连锁挖掘）。
4. 顺带修一处**错话**：电池里 `doneWhen` 判过时的详情文案是给 `region_maintain` 写的
   （"常驻任务按达成判过：chopped/planted 已达判据"）⇒ 放到别的 `doneWhen` 步骤上就是**错话**，
   且**看不见判据是什么**。改为通用文案并**带出任务自报的终态理由**。

**判据（新电池步 `mine_no_tool`，`Profile.MAIN` ⇒ CORE 跑）**：与 `mine_job` **同场景、同 Job**，
**唯一差别 = 不发镐**（`FixtureToolKit.resetInventory` 后刻意不 `ensurePickaxe`）⇒ 每个候选都 `no_suitable_tool`。
判据挂在 `doneWhen`：`task instanceof MineJob job && "tool_missing".equals(job.terminalReason())` ——
**Job 自身 FAILED 是预期的**（该步验的是**归因**，不是"挖到了"）；归因不对 ⇒ `doneWhen` 永不成立 ⇒
预算耗尽记 `TIMEOUT` 判红。同时 `mine_job`（发镐）仍报 `quota_met` ⇒ 两条合起来证明归因**不误报**。

**验证**：`./gradlew compileJava` ✅；`bash tools/check-all.sh` 9 PASS + 1 预期 WARN ✅；
**无头 `core`：`(passed=34/34 skipped=0) ticks=3553 → PASS`**（`baseline=14 main=20 extra_skipped=9`），
日志逐字：
`[Regression] mine_no_tool=PASS ticks=12（doneWhen 判据成立 ⇒ 本步按达成判过；task=MineJob terminalReason=tool_missing）`
与 `[Job] mine 未能完成的目标: 56, 62, 128:no_suitable_tool | 56, 62, 136:no_suitable_tool | …` ⇒ **`SERVER_TESTED`**。

**策展**：`BATTERY_CURATION` 更新为 **43 项 → CORE 34**（`mine_no_tool` 记 MAIN）。

**⚠️ 诚实标注（未观测）**：`write_budget_exhausted` 与 `stale_target` 两条映射是**照既有词表写的**，
本轮**没有**被观测到（缺"预算耗尽/身份失效"的夹具）⇒ 它们是**按词表实现**，不是已实测行为。
要观测需另造夹具（例如把 `WriteBudget` 上限调极小后跑一次挖掘）—— 属 **M3b**，未做。

**附注一**：`region_maintain`（EXTRA）的详情文案随本条改动一起变了，但**没重跑它**（本轮只跑 CORE）；
改的是**纯文案**且与 `mine_no_tool` **走同一条代码路径**（`doneWhen` 判过分支）⇒ 风险为零，如实记录在此。

**附注一（2026-09-15 M 线客户端验收）**：用户按计划跑完客户端 ——
**电池 `(passed=34/34 skipped=0) ticks=3534 → PASS`**（`PROFILE=CORE 实跑 34 项（跳过 EXTRA 9 项）`），
四个新/改步 **`mine_menu` / `no_progress` / `mine_no_tool` / `llm_contract` 全 PASS** ⇒
**M1（D-222）/ M2（D-223）/ M3（D-225）/ M4（D-224）的"正常分支"升 `WINDOWS_CLIENT`**。
⚠️ **R4 负例第一次没走到**（详见台账 §5.6 的同条记录）：场景跑起来了、但夹具仍动手
（`disturbed … tick=48` + `COMPLETED`），根因 = 我只填了 z=67 的 **y=64 一层**，
而 bot 中途踩台阶升到 **y=65**（扰动会在 tick 30 失败后**每 tick 重试到 +40**，所以它等到了那一层）
⇒ 已改为**填整列 y=60..67** 并重新复制到客户端存档，**待再点一次**。
**教训（新的一类）**：**"堵住某个判定"这类夹具，必须堵住"判定会取值的整个域"，不能只堵当时观测到的那一个值** ——
第一次我只按"日志里出现过 y=64"填了一层，而该夹具的重试机制会把取值域扫描到 y=65。

**附注二（2026-09-15 R4 负例分支客户端实测通过）**：`survey/08` §9#4 的负例**走到了** ——
`/reload` → `/function alice_test:r4_negative_disturb` → 右键 `alice:pathing_disturber` 一次
（jar `35fa4580…`）：`[R4 Fixture] not_fired session=… missing=[disturb]` +
`task_execution_terminal kind=PathSessionDiagnosticTask … terminal=FAILED code=failed:FIXTURE_NOT_FIRED:[disturb]`，
`[R4 Fixture] disturbed` 计数 **0**（无静默成功）⇒ **D-220 的物品侧"声明了夹具就必须断言它真的动手"升 `WINDOWS_CLIENT`**。
**第一次没走到（18:33）及其根因**：场景跑起来了但夹具**照常动手**（`disturbed … tick=48` + `COMPLETED`）——
`place_course` 地板 y=63 ⇒ 脚位本应 y=64，而我只填了 z=67 的 **y=64 一层**；扰动夹具"等 tick 30、失败则
**每 tick 重试到 +40 宽限**"，bot 中途踩台阶升到 **y=65** ⇒ tick 48 拿到没被填的 `(7,65,67)`。
⇒ 改成填**整列**（y=60..67）。
**教训（新的一类，已入册）**：**"堵住某个判定"的夹具，必须堵住该判定会取值的整个域**，
不能只堵当时观测到的那一个值 —— 该夹具的重试机制会遍历取值域。
**更正确期**：`disturb_not_applicable`（放弃宽限）**只在整趟跑过 tick 70 时才出现**；
本次路径 65 tick 就走完 ⇒ 日志里没有那一行，抓到它的是**终态断言**（`FixtureScript.notFired`）。
⇒ **两个机制互补**：前者 = "给了它时间仍找不到落点"，后者 = "这趟根本没生效，与计时无关"。
证据 `.alice-supervision/client-tests/d220-t3-20260915/evidence/r4-negative-key-lines.log`
+ 截图 `screenshots/2026-09-15_18.37.13.png`。

---

### D-226：S-5 维生最小件 —— 溺水/着火纳入否决、无出口不否决、掉血可见、维生进电池（2026-09-15）

**动因**（压缩断点后用户选定的路线，HANDOVER §6 推荐、用户 2026-09-15 认可"继续"）：
维生是**唯一零电池步的子系统**（`RegressionBatteryTask` 里 `Survival` 命中 0 ⇒ 只能真人验），
而审计到的缺口是**真实的**：① `HazardType` 五类里只有 `LAVA_CONTACT`/`SUFFOCATING` 会中断，
`LOW_AIR`/`ON_FIRE` **只报不拦**（溺水/着火不救）；② `HazardState.previousHealth` 自 S-1 起
**只写不读**（掉血对上层完全不可见）；③ `EventThresholds` 的类注释自称"三档可行动病症含 `DANGER`"，
而**代码里根本没有 `DANGER` 判据**（文档与代码不符，属"错话"）。

**改法（四项，全部离线可验证）**：

1. **软危险纳入否决，但必须有出口 + 过宽限期**（`SurvivalSystem.decide` 成为**唯一决策入口**）：
   `Verdict = IGNORE | INTERRUPT | HOLD_NO_EXIT`。硬危险（岩浆/窒息）**无条件**否决（与 S-1 完全一致）；
   软危险（溺水/着火）需 ① 持续 ≥ `SOFT_HAZARD_GRACE_TICKS = 10` ② `hasRefuge`。
   - **为什么"没出口就不否决"**（本轮新增判据）：否决的动作是"中断 + 走去落点"，没落点时
     `startSurvivalExit` 只能登记"无出口"并把 bot 留在原地 —— 对溺水/着火，**停在原地严格劣于**
     让任务继续（任务至少在往水面/安全处走）。硬危险不适用：岩浆里停不停都在烧，且那是已实测的既有行为。
   - **为什么是 10 tick**：溺水伤害发生在 air 归零**之后 20 tick**（原版压到 −20 才结算）⇒ 10 tick
     仍赶在第一次掉血前；着火伤害每 20 tick 一次，晚 0.5 秒同样安全。同时它避免"单 tick 抖动触发否决"
     和"每 tick 跑 17³ 落点查询"。
   - `HOLD_NO_EXIT` 要求**如实登记一次**（`durationTicks == 宽限期` 是唯一 tick ⇒ 天然每 episode 一次），
     事实进 `DecisionEvents.emit`（统一出口：环 + 日志 + 通知决策层，自检期只记录）。
2. **给 `previousHealth` 找第一个读者**（`EventThresholds.checkHealthLoss`）：掉血 ⇒ `DANGER` 事件
   （`delta=` 累计掉血量 + `health=`/`hazard=`/`pos=`）。判据两条滞回：**只在真的掉了那一 tick** 才可能上报
   （静止在 19/20 血不会每 40 tick 重复报一次），两次上报间隔 ≥ `HEALTH_LOSS_COOLDOWN_TICKS = 40`
   （火里连续烧伤合并成一条）。⇒ 顺手把 `EventThresholds` 那句"三档"的**错话**变成真的。
3. **"否决了却没出口"变成可判读事实**：`startSurvivalExit` 的 no-exit 分支补 `exit=none decision=stop`
   机器可读键 + `DANGER` 事件（此前只有一行无键值 warn，电池与决策层都看不见）。
4. **维生进电池**：新电池步 `survival_exit`（`Profile.BASELINE` ⇒ CORE 跑）：`Baseline 15 / MAIN 20 / EXTRA 9 = 44，CORE 35`。

**顺带修掉一条真缺陷（由新电池步**首轮实测**逼出来，不是设想）**：
"哪个格算 bot 站着的格"仓里有**两个口径** —— 原版 `blockPosition()` = "脚**所在**格"，
`MovementHelper.footCell` = "支撑格的上一格"（D-105 定的唯一口径）。整格平地一致，站在**半砖/箱子/台阶**
这类非整格支撑上**必然不同**（实测：脚在 slab 顶面 ⇒ `blockPosition=206,63,306`、`footCell=206,64,306`）。
`startSurvivalExit` 原来拿 `blockPosition()` 当"排除自己"⇒ 排除的是 **slab 那一格**（永远不是落点）
⇒ **bot 自己站的那格被当成出口** ⇒ 逃生任务走到原地、0 步 `COMPLETED`，而 bot 一格没动、**还在危险里**。
现在 `SurvivalSystem.footCell(bot)` 是全类唯一口径，落点搜索的**原点与排除格**都用它。

**判据（`survival_exit` 38 项断言，全部进 SUMMARY `checks=/failures=`）**：
① 决策表（硬危险无条件否决 + 理由码 `survival_lava_contact/suffocating/low_air/on_fire`、
涉水/无危险不否决、软危险宽限期内不抖动、过宽限**且有出口**才否决、过宽限很久仍否决）；
② **封闭场景**（`survival_sealed_course`：17³ 实心石壳里唯一 1×2 空腔）+ 四层前提自证
（真的站在空腔里 / 脚下有真支撑 / 头位可穿 / 半径内确实没有落点）⇒ 软危险 `HOLD_NO_EXIT`、硬危险仍 `INTERRUPT`；
③ **真实软危险端到端**：在封闭场景**真的点着** bot（`setSecondsOnFire(6)`）⇒ 真的没被否决
（否则会话任务=电池早被 `complete`，代码走不到下一行）+ 事件环里确实有 `exit=none decision=continue`；
④ **脚位格口径回归**：把脚下换成半砖 ⇒ 断言两口径**不同**、脚位格仍是"支撑格的上一格"、
排除脚位格后无落点、**对照**（排除 `blockPosition`）会把自己那格当出口；
⑤ **出口真的能走到**：拿维生给的真实落点起真实 `SurvivalExitTask`，必须 `COMPLETED` 且脚位等于落点；
⑥ **掉血可见**：真的扣 2 点血 ⇒ 一条带 `delta=` 的 `DANGER`，且冷却窗口内**恰好 1 条**（不刷屏）。

**⚠️ 结构性限制（必须如实记住，别以为这条步覆盖了整条否决链）**：电池步的**会话任务就是电池自己**，
而维生否决会 `complete()` 掉会话任务 ⇒ 电池里**不可能**跑"真危险 ⇒ 真被否决 ⇒ 起逃生"这条端到端路径
（那会**打死整轮电池**，连 SUMMARY 都没有）。因此"**否决真的发生了**"那半**只能真人验**：
入口 `alice:survival_exit_check` 右键 = 窒息（硬危险，有出口）、**潜行右键 = 着火（软危险，有出口）**。

**验证**：`./gradlew compileJava` ✅；`bash tools/check-all.sh` 9 PASS + 1 预期 WARN ✅；
无头 `single:survival_exit` = **`checks=38 failures=0 → PASS`** ✅；无头 `core` = 见 HANDOVER（本条落地时同步）。
⚠️ **本节全部证据是 `SERVER_TESTED`**：没有一条碰渲染/物理/GUI（`survival_exit` 的"真点火"也只在服务端逻辑层），
按纪律**不冒充** `WINDOWS_CLIENT`；`alice:survival_exit_check` 的两个模式**待真人轮次**。

---

### D-227：§5.9 挂起传输永久堵死 `assign*`（C3 + A + B，用户裁定 2026-09-15）

**动因（客户端实测发现，非审计推测）**：用户点 `alice:survival_exit_check` 后"bot 没反应"。
读日志 + 解析存档账本定位到**与维生无关**的一条真缺陷（台账 §5.9 有逐条证据）：
`saves/新的世界/data/alice_transfer_ledger.dat` 里该 bot 有 **11 条 `SUSPENDED`**（`code=server_restart`、
`location=NOT_MOVED`、`manualTakeover=1`）⇒ `blocksBot` 为真 ⇒ `replaceTaskIfRunning()` 返回 false
⇒ `assignWalkTo`（**void**）静默什么都不做 ⇒ 夹具的 dummy 任务从未建立 ⇒ 维生否决**永不执行**
（日志里窒息被正常检测、也真的掉血，但一次否决都没有）。**用户看到的"没反应"是这条链，不是窒息机制缺失。**

**用户裁定**：采纳 **C3 + A + B**，**不放宽 C2**（`blocksBot` 的拦截口径保持"任何任务替换"）。

**改法**：

1. **C3（`TransferLedgerData`）**：`suspendUnfinished` / `expireSuspensions` 里
   **`location != BOT_INVENTORY` 的条目直接落 `ABORTED`**（新终态码 `aborted_no_bot_inventory`、
   `manualTakeover=false`），只有 `BOT_INVENTORY`（物品确实在 bot 身上）才挂起。
   理由：这类条目**没有任何"人工接管"的语义**（物品还在源容器 / 压根没动），挂起只是纯阻塞；
   而且这让**已被旧版本堵死的存档自然自愈**（每次启动重跑本方法即结清 —— 不依赖"必须存盘"）。
   结清时**出声**（`启动结清：N 条…`）：这条日志正是"用户没反应"时**唯一**能给出线索的东西。
2. **A（前提自证 + 拒绝可见）**：`BotSession.assignWalkTo` 由 `void` 改**返回 boolean**；
   静态 `BotManager.assignWalkTo` 透传；`assignSurvivalExitCheck` 派活失败**返回 false + 打日志**；
   物品侧显示**真实原因**（"bot 手上有未结清传输 … ⇒ 换假人 / 重开世界自动结清"），不再打印骗人的"就位"。
   新增只读 `BotManager.assignmentBlockReason(bot)` + `TransferLedgerData.blockingSummary(UUID)`。
3. **B（拦截必须可见）**：`replaceTaskIfRunning` 被拦时打一行 warn（含条数与首个
   `requestId/state/code/location/manualTakeover`）。

**判据（零新增电池项 —— 挂在既有 BASELINE 步 `transfer` 的 `ledgerPolicies` 夹具里）**：
`suspendUnfinished` 后，`location != BOT_INVENTORY` 的条目必须 `ABORTED` + `code=aborted_no_bot_inventory`
+ `manualTakeover=false`。**反向对照已做**：把该断言取反 ⇒ `single:transfer` 立刻 `FAIL`（判据真能红）。

**验证**：`compileJava` ✅；`check-all.sh` 9 PASS + 1 预期 WARN ✅；
无头 `single:transfer` `FAIL`(反向) → `PASS`(正向) ✅；无头 **CORE `(35/35) ticks=3576 → PASS`** ✅；
**自愈实测**（无头默认会清 `alice_*.dat`，我**临时**关掉清账本跑一次，用与客户端同形的污染账本）：
`[Transfer] 启动结清：11 条**未进过 bot 背包**的未完成传输直接落 ABORTED（code=aborted_no_bot_inventory…）` ✅
（脚本已还原；清账本是**有意的**确定性设计）。
⚠️ **未验证的两点**（如实登记）：① 结清结果的**落盘**（无头 halt 不存档 ⇒ 观测不到；客户端正常退出会存，
且**即使不存盘，每次启动都会重新结清** ⇒ 症状仍被修掉）；② `assignWalkTo` 返回 false 这条**接线**只有
编译级 + 真人侧可见（缺一个"live 会话被挡"的电池判据，见台账 §5.9 末尾的提案）。

**明确没做（会被现有断言挡住，属有意设计）**：`TransferTask.survivalInterrupted` / `menuFailed` / `suspend`
三处**跑动中**的 `NOT_MOVED` 挂起保持原样 —— `taskInterruptPolicies` **明确断言**了
`SUSPENDED + NOT_MOVED + manualTakeover=true`。后果（如实记）：一次"传输中被维生打断"仍会让该 bot
在**本会话内**被挡住 `assign*`（现在至少有 warn 可查，不再静默），跨会话由 C3 在启动时结清。

---

### D-228：假人只跑了**半个原版 tick** —— 补 `baseTick()`（用户裁定 **B**，2026-09-15）

**动因**：用户实测反馈「bot 有反应了，但**看不到燃烧/窒息/受伤的效果**」。取证（台账 §5.10 有全部证据）：
- 客户端日志：硬×3/软×2 全部跑通否决+逃生；但整段着火 `health=20.0` 不变、`掉血` 事件一次都没有。
- 无头探针：`setSecondsOnFire(6)` 后 `remainingFireTicks=120`，16 tick 后**仍是 120**，`sharedFlag0=false`。
- 字节码：真玩家的 `Player.tick()→LivingEntity.tick()→Entity.tick()→baseTick()` **不在**实体 tick 链上，
  它在 `ServerPlayer.doTick()` 里，由**网络层** `ServerGamePacketListenerImpl.tick()` 驱动；
  假人的 `FakeConnection.tick()` **永不被调用**（Alice 自己的 `BotPlayer` javadoc 就写着这条）
  ⇒ 假人只有 `ServerPlayer.tick()`（记账）+ Alice 手动补的 `aiStep()`（物理），`baseTick` 从未执行。

**改法（用户选 B：最小补丁）**：`BotPlayer.tick()` 在 `super.tick()` 与手动 `aiStep()` **之间**插入
`this.baseTick()`（= `LivingEntity.baseTick()` → `Entity.baseTick()`）。
- **顺序与原版一致**：原版 `LivingEntity.tick()` 里 baseTick 在 offset 9、`aiStep()` 在 offset 179。
- **不会跑两遍**（字节码核对）：`ServerPlayer.tick()` 内没有任何 baseTick/aiStep 调用；
  `LivingEntity.baseTick()` 内**没有** `aiStep()`（但**有** `tickEffects()` ⇒ 药水效果计时一并回来了）。
- **不动物理管线**：`aiStep()` 调用点没动（D-174 那套实测基线保持）。

**恢复的能力**：火焰（渲染共享标志 + 每 20 tick 火焰伤害 + 递减）、空气/溺水、传送门冷却、冻结（细雪）、
`walkDistO`（脚步声）、药水效果计时。**仍未恢复**：`Player.tick()` 那半（食物/饥饿、自然回血、
`updateIsUnderwater`）—— 需要时再评估选项 A（补 `doTick()`，但会改变物理推进次数/顺序，风险更高）。

**判据（4 条，进 CORE，挂既有 BASELINE 步 `survival_exit`）**：
① 着火后 `sharedFlagOnFire()` 必须为真（客户端渲染火焰的唯一输入）；② `remainingFireTicks` 必须递减；
③ 着火必须真的掉血；④ 入水后 `airSupply` 必须真的被消耗（`LOW_AIR`/溺水在产线可达的前提）。
**反向对照已做**：注掉 `this.baseTick()` ⇒ 恰好这 4 条全红
（`120→120` / `20.0→20.0` / 空气 `20→20` / 标志 false）⇒ 判据真的挂在修复上，且在无头里复现了用户现象。

**顺带修掉的假绿隐患**：新加的 `fillBlocks(...)` 会断言 `/fill` 的改动方块数 —— 它立刻抓到
「`desc()`（= `BlockPos.toShortString()`，**带逗号**）被拼进命令 ⇒ 非法坐标 ⇒ `/fill` 静默 0 改动」
（新增 `xyz()` 专门给命令用）。以及"`/fill` 必须**先传送后执行**（区块未加载时静默无操作）"再次被踩到并记录在案。

**验证**：`compileJava` ✅；`single:survival_exit` 正向 `PASS`（checks=44 failures=0）/
反向 `FAIL`（4 条精确变红）✅；无头 CORE ✅；`check-all.sh` ✅。
**待真人验**：客户端**看得见火焰**、着火时**看得见掉血**（`/alice` 事件或日志），
以及"走路/水流是否正常"（`baseTick` 动了物理前置）—— 见 HANDOVER §6。

**B 落地后的补充（同日）**：
- 判据数 38 → **45**（① 火焰共享标志 ② 着火 tick 递减 ③ 期间最低血量 ④ 掉血事件带 `hazard=ON_FIRE`
  + 入水空气消耗 + 2 条 `fill` 生效断言 + 场景自建断言）。
- **CORE 第一跑就红了一次**，但**不是 `baseTick` 的错**：日志显示火焰伤害**确实发生**（`掉血 health=19.0 hazard=ON_FIRE`），
  可 16 tick 后血量被**治回 20**（单步跑不会）。后来把判据改成"**相位期间最低血量** + 掉血事件"，
  并在判据相位前 `normalizeVitals()`（清效果/满血/满空气）⇒ 不再依赖"某个时刻的净血量"。
  ⚠️ **治疗来源未定位**（疑似前序机器/药剂类步骤给 bot 上的残留效果 —— 补上 `baseTick` 后
  **药水效果开始真的 tick** 了）。已登记为待查项，但它不影响判据语义。
- **判据自证有效**：把 `this.baseTick()` 注掉 ⇒ 恰好那 4 条全红（`120→120`、`20.0→20.0`、空气 `20→20`、标志 false）
  ⇒ 在无头里**复现了用户看到的"没反应"**。
- **全量门槛**：`single:survival_exit` 正向 `PASS` / 反向 `FAIL`；CORE `(35/35) ticks=3701 → PASS`（含全部移动/挖掘/机器步骤）。
- **连带行为变化（生产可见，需知）**：药水效果**现在会真的计时并到期**（此前永不失效）；
  空气会消耗 ⇒ `LOW_AIR`（溺水）产线可达；火焰/冻结/传送门冷却生效。
  ⚠️ **新增未覆盖的危险类**：细雪冻结（`freeze`）**不在** `HazardType` 里 ⇒ 现在会真的掉血却无否决档，已记为待办。

**✅ 客户端验证（2026-09-15 晚，用户真人实测 ⇒ `WINDOWS_CLIENT`）**：
- **火焰看得见**（用户确认 bot 身上有火）—— 这是"共享标志真的到了客户端并由原版渲染出来"的实证。
- 着火期间**每 20 tick 稳定掉 1 点血**：`[Threshold] 掉血 DANGER … health=19.0/20.0 hazard=ON_FIRE`
  出现在 `duration=1/41/81/121` ⇒ 正是原版 `remainingFireTicks % 20 == 0` 的节奏，且血量停在 19.0（**客户端没有回血**）。
- 硬（窒息）×2 + 软（着火）×1 全部：否决 → `逃生出口（距 1.000 格）` → `SurvivalExitTask terminal=COMPLETED`；
  决策层也照常被触发（`[Goal] decision_action … note=当前 ON_FIRE…`）。
- **走动/放置/挖掘与以前一致**（用户确认）⇒ 补 `baseTick()` 没有引入观感/物理回归。
- 无新崩溃报告。

---

### D-229：新增 **冻结（FREEZING）** 危险档 —— 细雪不再是"静默致死"（用户裁定按推荐①，2026-09-15）

**动因（D-228 带出的真实风险）**：补上 `baseTick()` 之后，**细雪冻结伤害开始真的发生**
（`LivingEntity.baseTick()`：`tickCount % 40 == 0 && isFullyFrozen() && canFreeze()` ⇒ 全冻后**每 2 秒 1 点**），
而 `HazardType` 里**没有冻结档** ⇒ 否决链覆盖不到：bot 可以安静地冻死。

**原版事实（字节码核对）**：
- `isFullyFrozen()` = `ticksFrozen >= getTicksRequiredToFreeze()`（**140** ≈ 7 秒）；伤害在全冻之后 **1 点 / 40 tick**。
- `ticksFrozen` 的**累积在 `LivingEntity.aiStep()`** 里 —— 假人由 Alice **手动调 `aiStep()`** ⇒ 会累积；
  皮靴等 `FREEZE_IMMUNE_WEARABLES` 免疫也在那条路上 ⇒ **用 `ticksFrozen` 当信号天然尊重免疫**，不用自己判靴子。
- 这也解释了为什么"冻结"是 D-228 **之后**才会致死：伤害在 `baseTick()`，而 `baseTick()` 以前根本没跑。

**改法**：
- `HazardType.FREEZING`（枚举里放在 `ON_FIRE` 之后、`WATER_CONTACT` 之前）；
- `SurvivalSystem.classify`：`bot.getTicksFrozen() >= FREEZE_WARN_TICKS` ⇒ FREEZING；
- **软危险**（`softHazard` 加上它）⇒ 走"宽限 + 有出口才否决"的既有口径；
- `interruptionReason` ⇒ `survival_freezing`；
- 阈值 `FREEZE_WARN_TICKS = 60`（≈ 全冻前 4 秒，留够"走出细雪"的余量；路过一小片细雪通常 < 1 秒 ⇒ 不误否决）。
  **可调**：调它要连带看电池步 `survival_exit` 的细雪相位（它按这个常量等累积）。

**判据（挂既有 BASELINE 步 `survival_exit`，checks 45 → 55）**：
① 决策表：冻结第 1 tick ⇒ 不否决；过宽限 + 有出口 ⇒ 否决（`INTERRUPT`）；理由码 = `survival_freezing`；
② 真实相位（**封闭石壳 = 无出口**，为保证不触发否决）：`ticksFrozen` 必须**真的累积** ≥ 阈值；
③ 累积到阈值必须被判成 `FREEZING`；④ 无出口 + 过宽限 ⇒ `HOLD_NO_EXIT`（不乱否决）；
⑤ **全冻（140）后必须真的掉血**（"期间最低血量"口径，抗治疗掩盖）；
⑥ 相位前 `normalizeVitals()`（清效果/满血/满空气）⇒ 判据确定性。
**反向对照已做**：把 `FREEZE_WARN_TICKS` 改成 2000 ⇒ ②③④ 精确变红（`实际 NONE`）⇒ 分类判据真的挂在阈值上。

**真人入口（零新物品）**：`alice:survival_exit_check` **疾跑 + 右键** = 冻结演示
（封闭石壳 + 1×2 细雪；无出口 ⇒ 不否决，全冻后每 2 秒掉 1 血）。**否决 + 逃生**那条路仍由
潜行右键（着火，软危险 + 有出口）演示。⚠️ 顺序：**先传送再 `fill`**（未加载区块里 `/fill` 静默无操作，踩过两次）。

**验证**：`single:survival_exit` 正向 `PASS`（checks=55 failures=0）/ 反向 `FAIL` ✅；
CORE `(35/35) ticks=3830 → PASS` ✅；`check-all.sh` ✅。
**待真人验**：疾跑右键看到 `hazard=FREEZING` + 全冻后掉血（`WINDOWS_CLIENT` 待升）。

**入口设计教训 + 替代入口（同日，用户实测发现）**：
- 细雪那条一开始挂在 `alice:survival_exit_check` 的"**疾跑 + 右键**"上 ⇒ 用户按了两次**全落到"硬：窒息"分支**，
  客户端日志里 `FREEZING` **0 次**。根因是**原版站着不动进不了疾跑状态**（疾跑需要向前移动）⇒
  **这个触发键在静止时根本点不到**（我的设计错误，与用户操作无关）。该分支**已撤掉**。
- 替代入口（零参数、点得到）：**新物品 `alice:survival_full_check`** —— 普通右键，
  把 `SurvivalExitCheckTask` **整套夹具**挂成会话任务：决策表 / 封闭场景（无出口不乱否决）/ 真实着火 /
  掉血可判读 / 入水空气消耗 / **细雪冻结（累积 → FREEZING → 全冻后掉血）**，
  跑完在聊天里打一行 `[Survival] SUMMARY checks=55 failures=N → PASS/FAIL`。
  夹具**自己收尾**：拆水/拆雪、归零 `ticksFrozen`、清效果、把人送回平台。
- 落地要点：`BotSession.assignFixtureTask(...)`（走 §5.9 的可见拒绝路径，被未结清传输挡住时**如实返回 false**）
  + `BotManager.assignSurvivalFixtureCheck(bot, observer)`；物品先**传送再建场景**（未加载区块里 `/fill` 静默无操作）；
  `SurvivalExitCheckTask` 名字带 `Check` ⇒ `isSelfCheck()` 为真 ⇒ 跑夹具时**自动暂停决策层**（LLM 不会插一脚）。
- ⚠️ **一般化教训（写进技能库）**：给真人做的测试入口，触发键必须是**静止可表达**的输入
  （右键 / 潜行+右键 / 物品 / 命令）。**疾跑、跳跃、移动中**这类状态在静止时表达不出来，不能当模式选择器。

**✅ 客户端验证（2026-09-15 晚，用户真人实测 ⇒ `WINDOWS_CLIENT`）**：
`alice:survival_full_check` 右键一次跑通整套（聊天里 `[Survival] SUMMARY checks=55 failures=0 [] → PASS`）：
- `[SurvivalFullCheck] 就位 bot=tango（平台角 64, 64, 102）` → 夹具各相位依次跑完；
- **冻结**：`把 bot 放进细雪（起始 ticksFrozen=1，全冻阈值 140）` → `维生监测 hazard=FREEZING duration=1…121`
  → `软危险 hazard=FREEZING 已持续 10 tick，半径 8 格内**无安全落点** ⇒ 不否决` + 事件 `exit=none decision=continue`
  → `已全冻（ticksFrozen=140）` → `[Threshold] 掉血 … hazard=FREEZING health=19.0/20.0`（两次，间隔 40 tick = 2 秒，
  **正是原版节奏**）→ `拆掉细雪并清零（拆前 140，清零后 0）`（夹具自己收尾 ✅）。
- **新发现（未定位，已登记）**：同一段里血量出现 `19.0 → 20.0` 的**回血**（`duration=121` 那次采样是 20.0，
  而 40 tick 前刚掉到 19.0）。Alice 侧没有任何治疗代码（`heal(`/`setHealth(` 全仓为零），
  而夹具在相位前刚 `removeAllEffects()` ⇒ 说明**有东西在持续重新施加效果/治疗效果**。
  与 CORE 里那次"伤被治回去（18→20）"是**同一个现象**，现在确认它在**真人客户端**也存在。
  最可能：**Regeneration 类效果**（补了 `baseTick` 之后药水效果开始真的 tick ⇒ 才开始生效）。
  待用户一条命令核实：`/data get entity tango active_effects`。

---

### D-230：**"bot 没有自然回血"是我说错了** —— ②（补 `doTick()`）实测后**不启用**（2026-09-15）

**背景**：D-228 的后续项里我写了「bot 的血只减不增（自然回血在 `Player.tick` 那半，仍缺）」，并据此推荐
② 补 `doTick()`。用户批准按推荐推进后，我先做了 A/B 实测（开关版，默认关，零风险），结果**推翻了我自己的判断**。

**实测（无头 A/B，`ALICE_EXTRA_JVM_ARGS="-Dalice.bot.vanillaTick=true"`）**：
| | 拆雪后血量轨迹 | 饱和度 | 结论 |
|---|---|---|---|
| 开关 **OFF**（现状） | `19.0 → 20.0`（≤8 tick 内回来） | `5.0 → 5.0`（**不消耗**） | **本来就会回血** |
| 开关 **ON** | `19.333 → 20.0` | `2.0 → 1.0`（消耗） | 多了一层回血 |

**根因（字节码）**：`Player.aiStep()` 里就有
`if (gamerule NATURAL_REGENERATION && getHealth() < getMaxHealth() && tickCount % 20 == 0) heal(1.0F)`
—— 而 `aiStep()` **在现状路径里一直被手动调用** ⇒ 和平/自然回血**一直在跑**（1 点/秒，不吃饱和度，
正是真人客户端里那次 `19.0 → 20.0` 的来源）。开关 ON 时额外跑 `Player.tick()→FoodData.tick()`，
那层才是"吃饱和度的回血"（1 点/10 tick）⇒ 所以 ON 的 `sat` 才会掉。
（`FoodData.tick()` 的唯一调用点确实在 `Player.tick()` 里 —— 这条字节码我核对过，没看错；
**错的是"回血只在 FoodData 里"这个推论** —— `aiStep()` 还有一份。）

**因此 ② 的收益比我说的小得多**：补 `doTick()` 只多拿 `FoodData`（饥饿/饱和度回血）、`updateIsUnderwater`
和 `Player.tick()` 的记账；而代价是
① 物理推进换成"真玩家那一整套"（顺序/次数与 D-174 基线不同，需整套 CORE + 真人复测）；
② **引入"饥饿"这一新失效模式**（食物会掉，落到 0 且在非和平难度会饿死；Alice 现在没有"饿了就吃"的行为）。

**决定**：**不启用**（保持 `alice.bot.vanillaTick` 默认 `false`）。开关**保留**为有记录的实验开关
（与 `alice.packet.observer` 同类），理由写在代码 javadoc 与本节；将来若真要食物/饥饿语义，先补"吃"的行为再评估。

**A/B 门槛记录**：`single:survival_exit` 在 ON 下 `checks=55 failures=0 → PASS`；
**CORE 在 ON 下也是 `(35/35) → PASS`**（OFF 基线同为 `(35/35)`）⇒ 开关本身不会打坏回归，
所以"不启用"是**收益/风险判断**，不是"跑不过"。**判据**：这次没有新增电池判据（开关默认关 ⇒ 判据会常绿无意义），
A/B 数据即本节表格。

**修正记录**：D-228 后续项 ①「血只减不增」**作废**；台账 §5.10 里"治疗来源未定位"**结案**
（= `Player.aiStep()` 的自然回血 + 夹具相位前的 `normalizeVitals()`）。

---

### D-231：M4b —— 任务树带上"哪个子阶段失败"（`tree[].lastFailure` 不再恒为空，2026-09-15）

**缺口（台账 §5.7 记的 M4b）**：`DecisionSnapshot` 的任务树里，**只有子节点**会序列化 `lastFailure`
（根节点根本不带这个字段），而四个 Job 的 `subTasks()` 全用 `TaskNode.leaf(...)`（**把 `lastFailure`
硬编码成 `""`**）⇒ 决策层/汇报只能看到"挖矿没挖到"，看不到**哪个子阶段、以什么理由**失败
（`MineJob.mine()` 还会立刻把 `miner` 置空 ⇒ 失败的子节点连**节点本身**都消失了）。

**改法（最小闭环，只做 MineJob）**：
1. `TaskFailureReport.oneLine()`：`code@phase`，**有界**（`ONE_LINE_MAX=120`，超长截断标 `…`）——
   `lastFailure` 的**唯一**口径，不放推导、不放建议。
2. `TaskNode.leaf(..., lastFailure)` 重载（旧 5 参重载保持不动 ⇒ 其它 Job 行为零变化）。
3. `MineJob`：新增 `minerFailure` + `finishedMinerNode`；`mine()` 里**在置空之前**取子任务的
   `failureReport().oneLine()`（成功则清空）；`select()` 新建子任务时清空；
   `subTasks()` 在子任务已置空时**仍把刚结束的那个子阶段摊出来**（否则节点的失败事实看不见）。
4. **判据（零新增电池项）**：挂在既有 `mine_no_tool`（**必然失败**）步的 `doneWhen` 上 ——
   归因对了**且**树里出现非空 `lastFailure` 才判过；树里没有那行 ⇒ `doneWhen` 永不成立 ⇒ 预算耗尽判红。
   **反向对照已做**：把"结束的子节点仍摊出来"那支短路 ⇒ 立刻 `mine_no_tool=FAIL`
   （`M4b 判据：… 没有任何子阶段的 lastFailure ⇒ 判红`）。

**验证**：`single:mine_no_tool` 正向 `PASS`（日志 `M4b 判据通过：任务树带子阶段失败事实
lastFailure=no_suitable_tool@EVALUATING`）/ 反向 `FAIL` ✅；CORE `(35/35) ticks=3832 → PASS` ✅。

**明确没做（挂账）**：`LumberJob` / `CollectJob` / `RegionLumberJob` 的子节点**仍是空 `lastFailure`**
（同一个接法，但各自要判定"哪个子阶段失败"的语义；本轮只做 MineJob 这条最小闭环，避免一次改 4 个 Job）。

---

### D-232：M3b —— 两条"写了却从没被观测过"的归因映射，现在各有一个确定性夹具（2026-09-15）

**缺口（台账 §5.7 M3 的 ⚠️）**：`MineJob.deriveTopLevelReason` 里 `write_budget_exhausted` / `stale_target`
两条映射是**照既有词表写的**，但**从来没有一次真实运行产生过**它们 ⇒ "写了等于没写"（红不了也证明不了）。

**两个夹具（都挂在既有电池入口上，各 400 tick）**：
1. **`mine_stale`**（`stale_target`）：`stale_target` 要求"每个候选的身份复检都失败"，而扫描与复检在
   **同一次 `select()`** 里 ⇒ 外部无法确定性插入改动（电池里会话任务就是 Job，夹具进不来）。
   ⇒ 给 `MineJob` 加**夹具专用构造**：注入 `identityCheck`（生产路径恒 `null`，走 `source.matchesTarget`），
   夹具传 `pos -> false`。产生的理由码与真实竞态**完全一样**（`target_replaced`）⇒ 映射被真的走到。
   实测：`56,62,128:target_replaced | …` ⇒ `terminal reason=stale_target` ✅
2. **`mine_budget`**（`write_budget_exhausted`）：用**既有**夹具缝 `WriteBudget.setCaps(scopeOf(bot), Caps(0,0))`
   （javadoc 自述"夹具专用，不接玩家命令"）把本步作用域的破坏预算压到 0 ⇒ 每次破坏被拒。
   实测：`56,62,128:WRITE_BUDGET_EXHAUSTED` ⇒ `terminal reason=write_budget_exhausted` ✅
   —— 这条**顺带证实了词表是对的**：真实路径产的就是 `BUDGET_CODES` 里的大写码。

**判据**：两步各自 `doneWhen = terminalReason == <期望码>`；理由不对 ⇒ `doneWhen` 永不成立 ⇒ 400 tick
预算耗尽判红。**反向对照已做**：把 `deriveTopLevelReason` 短路成 `return base` ⇒ **两步各判红**（~30 s）。

**验证**：`single:mine_stale` / `single:mine_budget` 均 `PASS`；CORE 由 35 步变 **37 步**
（`(37/37) → PASS`）；`docs/BATTERY_CURATION.md` 归属表与历史行同步（44/35 → 46/37）。

---

### D-233：③ `decision_contract` 提档 + ④ `AI_PROJECT_STATE.md` 过时段落重写（2026-09-15）

**③ `decision_contract`：EXTRA → MAIN**（用户队列第 3 项，按既定先例执行）
- **事实（矛盾）**：该步的类注释写着"契约类断言：纯逻辑、不改世界、不调 LLM ⇒ **任何改动都跑得到**"，
  而 `RegressionBatteryTask.CURATION` 把它放在 **EXTRA** ⇒ **CORE 根本跑不到**（承诺与档位打架）。
- **代价**：`DecisionContractCheckTask(bot, observer)`，预算 **200 tick**、确定性、不调 LLM、不改世界。
- **决定**：提到 **MAIN**（⇒ CORE 跑得到）。依据是**同一个项目里已发生三次的同型先例**：
  M1 `mine_menu`、M2 `no_progress`、M4 `llm_contract` 都从 EXTRA 提档，理由都是"**门禁必须默认跑得到**"
  （D-201 的策展口径：机制不丢、默认时长下降）。若用户要的是"改文档承诺"而不是"提档位"，改回一行即可。
- **验证**：CORE **37 → 38 步**，`decision_contract=PASS`，`(38/38) ticks=3847 → PASS`；归属表与历史行已同步。

**④ `AI_PROJECT_STATE.md`：把停在上古的两节重写成现状（**净增 −8 行**，符合"净增 ≤ 0"硬约束）**
- **问题**：`## 当前目标` 下的「当前进度」与「下一步（2026-09-09）」是 **R2–R4 / D-024…D-068 时代**的逐条流水
  （含一段上千字的单行段落），而项目已到 D-232 ⇒ 新会话照它读会**读到错误现状**。
- **改法**：① 在"最新（2026-09-15）"节顶部加一行指针（声明本节及以下多为历史存档，"现在在哪"看下面两节 + `HANDOVER` §1）；
  ② 把 09-08/09 时代的「当前进度 / 下一步」整段换成**当前快照**（内核 R1–R5 收口 → 3-A → 3-B S0–S4 → M 线全完成
  （含 M4b/M3b）→ S-5 维生线三项 `WINDOWS_CLIENT` → D-230 的自我修正 → 离线门槛 CORE 38 步 / check-all 9+1 →
  断点指向 `HANDOVER` 与台账）。逐条历史本来就在 `AI_DECISIONS.md`/git 里，**不再抄进 STATE**。
- **验证**：`check-doc-budget` = **1467 ≤ 1476**（余额 9）✅；`check-all.sh` 9 PASS + 1 预期 WARN ✅。

---

### D-234：M4b 收尾 —— 四个 Job 统一"子阶段失败"口径（判据挂在共享口径上，lumber 侧覆盖缺口如实登记）（2026-09-15）

**做了什么**：
- 新增**唯一口径** `TaskNode.finished(kind, target, phase, ticks, progress, child, status[, children])`：
  子任务 `DONE` ⇒ `lastFailure` 为空；失败 ⇒ 取该子任务自己的 `failureReport().oneLine()`（`code@phase`，有界 120）。
- `MineJob` 改为调用它（删掉自造的 `minerFailure` 字段）；`LumberJob`（miner/collector×2/restore 四个结束点）、
  `CollectJob`、`RegionLumberJob`（内层 Job，带它自己的 `subTasks()`）全部接上 —— 每个 Job 保留"刚结束的那个子节点"，
  在**没有活着的子任务**时把它摊进 `subTasks()`（否则子阶段置空后失败事实又看不见了）。

**判据（CORE 可跑，零新增电池步）**：挂在既有 `decision_contract`（MAIN）里做**纯逻辑**断言 ——
`finished` 三条：成功不留行 / 失败带 `code@phase` / 一行有界（≤ `ONE_LINE_MAX`）。
四个 Job 共用这个口径 ⇒ 钉住它等于一次钉住四个。
**反向对照已做**：把 `finished` 的失败行短路成 `""` ⇒ `decision_contract` 立刻 `FAIL`
（`checks=10 failures=1 [finished：失败子任务带 code@phase]`）✅

**⚠️ 如实登记的覆盖缺口（本轮最有价值的发现）**：`LumberFailureCheckTask`（`lumber_failure`，EXTRA）六个用例
**没有一个**以"子任务结束且失败"收场 —— 我第一版给它们加的"Job FAILED ⇒ 树里必有失败行"判据**是错的**，
被夹具当场打回：
- `GOAL_TIMEOUT`：Job 因**自己的**超时失败时子任务**还在跑**（`tree=[MineTask… ticks=41 cleared=1 gained=0]` 无失败行）⇒ 合法；
- `TOOL_MISSING`：斧头前置检查在**建子任务之前**就拒绝 ⇒ `tree=[]` ⇒ 没有子阶段可点名 ⇒ 合法；
- `NO_CANDIDATES`/`ALL_REJECTED`：压根没建子任务；`INVENTORY_FULL`/`LOG_REPLACED`：DONE。
⇒ 错的判据比没判据更坏（会把合法行为判成失败），**已撤回**，只保留可读的 `tree=…` 明细。
**lumber / collect / region 三条端到端因此只到 `IMPLEMENTED`**（不是 `SERVER_TESTED`）；
要补它需要一个"内层 `MineTask` 真的失败"的确定性用例（例如够不到/超出 `MAX_GAIN_PER_TREE` 的树干），已登记台账 §5.7。

**验证**：`single:decision_contract` 正向 `PASS checks=10` / 反向 `FAIL` ✅；CORE（38 步）见提交信息。

---

### D-235：§5.9 挂起传输"结清落盘"验证 —— **持久化成立、跨重启幂等**（2026-09-15）

**待办（台账 §5.9 尾部）**：C3 把那 11 条 `NOT_MOVED` 挂起直接落 `ABORTED`，但**无头停机是 `Runtime.halt`
（故意不存档）** ⇒ "结清到底有没有落盘"此前**无法观测**（磁盘上永远只有母本那份）。

**新增两个**持久化实验**开关（都挂在既有命令上，默认关 ⇒ 平时行为一字不变）**：
- `ALICE_KEEP_ALICE_DATA=1 tools/headless-battery.sh …` ⇒ 保留世界里的 `alice_*.dat`（默认被删是为了干净起点）；
- `ALICE_EXTRA_JVM_ARGS="-Dalice.headless.saveOnHalt=true"` ⇒ `HeadlessBattery.exit` 在 `halt` 前
  `saveEverything(true,true,false)`（javadoc 写明：`SavedData` 的状态只有存档才看得见）。

**实验与结果**：
1. 第一轮（世界带母本的账本 + 保留 + 存档）：启动日志 `启动结清：11 条…落 ABORTED（code=aborted_no_bot_inventory）`
   ⇒ `transfer` 步 `PASS`；解压 `world/data/alice_transfer_ledger.dat`（gzip → 5.0 MB）
   **读到** `code=aborted_no_bot_inventory` + `state=ABORTED` + `manualTakeover=false` 的条目
   ⇒ **结清确实落盘**（`suspendUnfinished` 的 `setDirty()` 生效）✅
2. 第二轮（把上轮存档当作母本再启动一次）：启动结清变成 **1 条**（不是 11 条）⇒ 上轮那 11 条已
   **terminal（ABORTED）**、不再被结清 ⇒ **跨重启幂等** ✅；`transfer` 步再次 `PASS`（**账本满载时
   bot 的 `assign*` 通路可用**）✅

**⚠️ 方法教训（差点自己造出一个假发现）**：我第一版统计解压后的 NBT，得到"`state=SUSPENDED` 1938 条"
⇒ 看起来像"结清只做了一小部分"。**错的**：`Entry` 里既有**当前状态**字段、也有 `transitions[]` **历史**，
两者字段名都叫 `state` ⇒ **按字符串计数 ≠ 数活状态**。正确的量法是让系统自己报
（结清行的 `released` 计数、或再启动一次的对比）。这与今天前几次是同一族病：**证据口径不当时，数字会骗人**。
（台账 §5.9 的"持久化"待办据此关闭；DB 里要不要真的区分 `transitions[].state` 与 `entry.state` 的观测便利性
不属本轮，未动代码。）

---

### D-236：溺水不能"静默继续"—— `ABANDON_NO_EXIT` + 封闭水牢判据；水里逃生能力缺口**登记**（2026-09-15）

**用户提问逼出来的事实**（"没有游泳 Movement，水里逃生怎么处理？"）：查完是**三重缺口**，不是少一个 Movement：

**① 内核连"进水"都规划不出来**（Alice 相对 Baritone 的偏离）：
> **⚠️ 更正（2026-09-15 深夜，丙 的可行性讨论中查实）**：
> ① **"FALL 无水落地"不是漏登记** —— 它是 **D-058 的用户决策**（"只做无水落地 ≤3 格；不移植 Baritone 的落水/水桶救落分支"）。
> ② "不进水"的**精确机制**在 `core/search/SurfaceMovementProvider.java:127-130`：生成 TRAVERSE/DIAGONAL/ASCEND 边时
> **明确跳过"目的格或头格是流体"的目的地**（注释：不往危险里"脱困"），再叠上 `MovementHelper.canWalkOn` 拒绝流体源当支撑。
> ⇒ 本文下面那张表里"两处未登记"的说法**只对一半**（水走那半），FALL 那半早有 D-058。老实说：这是我引用代码注释时没回溯决定条目。
> ③ 另外 Baritone 的垂直水位能力在 **`MovementPillar` 的水分支**（`movements/MovementPillar.java:77-82`：
> "允许上升水柱，**但只在我们已经在水中时**" ⇒ 按跳跃上浮），不是 `MovementAscend`（后者对水无分支）——
> 这条决定了"丙"若要做，必须包含**垂直**水位移动，而不只是水平水走。

| | Baritone | Alice |
|---|---|---|
| 走水 | `MovementTraverse.java:88-96`：水里走有专门成本 `context.waterWalkSpeed`（+ `walkOnWaterOnePenalty`）⇒ **能穿水** | `pathing/MovementHelper.java:52/62/179/196`：**流体源格不算支撑** ⇒ 水里那格永不是合法脚位 ⇒ `TRAVERSE` 进不了水 |
| 落水 | `MovementFall.java:102`：`isWater = destState.getFluidState().getType() instanceof WaterFluid` ⇒ **认得出落点是水**（水不摔伤，:103-109 还有放水桶 MLG） | `pathing/core/FallExecution.java:186`：落点及上一格**必须无流体** ⇒ 计划里不会有"跳进水里" |
| 附带 | —— | `pathing/core/MovementCapabilities.java:17` 的 `canEnterFluid` **全仓无读者**（装饰字段，`pureTraversal` 还把它设成 false） |
⇒ 结论：**今天没有任何"从水里出来"的能力**（没有上浮/swim，也规划不出一条含水路线）。

**② 维生侧：溺水此前与"着火/冻结"同档 ⇒ 无出口时不否决 ⇒ 静默淹死**。修法（本轮做的，用户批准"甲+丁"）：
- `SurvivalSystem.Verdict` 新增 **`ABANDON_NO_EXIT`**；`decide()`：软危险无落点时
  **`LOW_AIR` ⇒ `ABANDON_NO_EXIT`**（不动手必死），`ON_FIRE`/`FREEZING` **仍 `HOLD_NO_EXIT`**（可能自愈，D-226/D-229 语义不变）。
- 新增 `abandonReason(...)` = `survival_drowning_no_exit`（与 `interruptionReason` 分开：一个是"起了逃生任务"，一个是"连逃都没地方逃"）。
- `BotManager`：`ABANDON_NO_EXIT` ⇒ **结束当前任务**（`SURVIVAL_INTERRUPTED` + 大声 warn + `DANGER` 事件 + `GoalDirector.onSurvivalInterrupt`），
  **不起逃生任务**（维生刚说过半径内没有落点）。
- **判据（零新增电池项，挂既有 BASELINE 步 `survival_exit`）**：新增相位 `DEEP_WATER` 自建**封闭水牢**
  （17³ 实心石壳 + 内部注水，中心 `206,100,306`）⇒ 半径 8 内**无干燥可站落点**（几何自证）
  ⇒ 断言：① 空气够时 `hazard=WATER_CONTACT`；② **空气点成 0 ⇒ `hazard=LOW_AIR`**（溺水排在涉水之前，
  真水里被认出来 ⇒ 顺带回答"溺水会不会被涉水盖掉"）；③ `decide(LOW_AIR,99) == ABANDON_NO_EXIT` + 判定码；
  ④ 对照：`ON_FIRE` 无落点仍 `HOLD_NO_EXIT`、`WATER_CONTACT` 仍 `IGNORE`。**checks 55 → 65**。
  **反向对照已做**：把溺水那一档改回 `HOLD_NO_EXIT` ⇒ 两条新判据精确变红（`… 实际 HOLD_NO_EXIT`）✅
- ⚠️ **结构性限制（如实记）**：`BotManager` 那条"放弃任务"的**接线**在电池里**不可能端到端触发**
  （放弃 = 结束会话任务 = 打死整轮电池）⇒ 它只有**纯判据 + 编译级**验证，端到端要真人（与 §5.9 ③ 同款限制）。

**③ 登记为已知限制（丁）**：**水里逃生 = 不支持**。真实行为矩阵：
| 情形 | 今天的行为 |
|---|---|
| 浅水/岸边 8 格内有干燥可站落点 | 溺水过宽限 ⇒ `INTERRUPT` ⇒ `SurvivalExitTask`（= 普通 `WalkToTask`）**走过去**（能否走到取决于寻路；`isRefuge` **不检查可达性**） |
| **深水里浮着（脚下无支撑）** | 溺水过宽限 ⇒ **`ABANDON_NO_EXIT` ⇒ 放弃任务**（D-236 前是"静默继续到淹死"） |
| 只是涉水（空气没耗尽） | `WATER_CONTACT` ⇒ `IGNORE`（D-226 已验收语义：不打断长作业） |

**复核触发（将来要不要做"乙/丙"）**：① 真实存档里出现水下作业/水下目标（采矿、沉船、海底神殿）；
② 出现"bot 掉进深水后放弃任务"的真实案例；③ 决定补 `MovementTraverse` 水分支时 —— 那时先做 Baritone
`MovementTraverse`/`MovementFall` 的**逐行对照 + 成本模型影响评估**，再决定是否补（**不**先做上浮补丁）。

**⚠️ 本轮的方法教训（差点给误诊留 workaround）**：新相位第一版跑出 `fill=0`、水牢里 `hazard=NONE`，
**现象完全是"D-229 同款：`/fill` 落在未加载区块"** ⇒ 我顺手加了 `/forceload`。真因其实是
**`phaseTicks++` 发生在 tick 开头**（首 tick = 1）⇒ 我的 `if (phaseTicks == 0)` 建场景分支**从来没跑**，
水牢压根没建。修法是整体后移一位；`/forceload` 返回 0（区块本来就在）⇒ **已删除**，
不留"给误诊兜底"的代码。教训：`fill=0` 有两种成因（区块未加载 / **命令压根没执行**），
**先把"命令有没有执行"读出来再动手**。

---

### D-237：水里"乙"—— 溺水时先**上浮自救**（纯输入，不新增 Movement）（2026-09-15）

**用户批准的范围**：D-236 里列的"乙"——溺水时抬头/上浮到水面，**不做**内核水位 Movement（那是"丙"，单独排期）。

**做法（三层，都是最小件）**：
1. `SurvivalSystem.canFloatUp(bot)`：**纯几何判据** —— 从脚位往上找，先撞到"能穿过的非流体格"⇒ 浮得上去；
   先撞到实体方块 ⇒ 浮到顶也还在水里（封闭水牢就是这种）⇒ 浮不上去；顺带排除**头顶是岩浆**。
2. 新判决 **`Verdict.FLOAT_UP`**：`decide()` 里 溺水 + 无落点 时，`canFloatUp && 不在封禁期` ⇒ `FLOAT_UP`，
   否则 `ABANDON_NO_EXIT`（D-236 语义保留）。**封禁期**：`SurvivalFloatTask` 失败时
   `markFloatFailed`（1200 tick）⇒ 同一场溺水不再反复试，直接放弃任务。
3. `SurvivalFloatTask`（新，实现 `SurvivalExit` 标记 ⇒ 自救期间不会被维生二次否决）：
   头在水里就 `controller.setJumping(true)`（原版水里按跳跃 = 上浮），**头一出水立刻松手**
   （长按在地面/水面会变兔子跳，`BotController` 既有注释点名过）；头出水且空气 ≥100 ⇒ `DONE`；
   240 tick 预算用尽 ⇒ `FAILED` + 封禁。`BotManager` 新增 `FLOAT_UP` 分支：
   结束当前任务（`SURVIVAL_INTERRUPTED`）+ `DANGER` 事件（`decision=float_up`）+ 起自救任务。

**判据（零新增电池项，挂既有 BASELINE `survival_exit`）**：新相位 `OPEN_WATER` —— 21×21 露天水池
（水 4 深、**天花板必须拆掉**），半径 8 内无干燥落点（池壁在 ±11 之外）；断言：
`canFloatUp=true` / `!hasRefuge` / `decide(LOW_AIR,99) == FLOAT_UP`；**并端到端**驱动真 `SurvivalFloatTask`：
到达 `DONE`、**眼睛离开水面**、**空气回到 100+**。`checks 65 → 78`。
**反向对照**：把 `canFloatUp` 恒置 false ⇒ 两条新判据精确变红 ✅
**实测证据**：`上浮自救成功：头已出水且空气复原（tick=44 air=102 y=102.81）` ⇒ bot 从 y=100 浮到 102.81 ✅

**⚠️ 接线只观测到一次（如实记，且这是本项目最有价值的一次"结构限制"实证）**：中途一次运行里，
monitor 真的走到了 `LOW_AIR` ⇒ `BotManager` 走了 `FLOAT_UP` 分支：日志
`[Events] DANGER … 溺水无落点 ⇒ 上浮自救（survival_low_air）（… decision=float_up …）` +
`task_execution_terminal kind=SurvivalFloatTask … COMPLETED code=done` ✅。
但那一轮最终 `verdict=no_verdict exit=3`：**被打断的是电池本体**
（`task_execution_terminal kind=RegressionBatteryTask … SURVIVAL_INTERRUPTED code=failed:survival_low_air`）
—— 因为**电池就是会话任务**，任何"真维生判决"都会 `complete()` 掉它 ⇒ 这条接线**不可能**做成绿色的电池步。
⇒ 所以本轮把它拆成：**可复现的判据**（判决 + 物理：水池里真浮上去）+ **一次性的接线观测**（上面两条日志）。

**⚠️ 三个坑（都是同一族：夹具的状态机/前提假设不成立）**：
1. **"air=5 就不会触发溺水判决"是错的**：monitor 在 **tick 开头**读数，而原版在同一 tick 里再扣 1
   ⇒ 钳 `max(1, air)` 会被扣成 0 ⇒ 下一 tick 判 `LOW_AIR` ⇒ **真的行使否决权、打死电池**。
   修法：钳到 **≥2**（注释写清"为什么不是 1"）。
2. **断言后必须让相位真的前进**：端到端那段忘了 `floatTask = null` ⇒ 每 tick 重复断言、永不进收尾 ⇒
   `survival_exit=TIMEOUT ticks=901`。这是今天**同一族的第三次**（前两次：`phaseTicks == 0` 永不命中、
   探针分支重入）⇒ 已写进 `alice-scene-based-testing` 技能。
3. **`hollow` 的上盖会成为"半径 4 内可站的落点"**：水池第一版没拆天花板 ⇒ 判决变成 `INTERRUPT`
   ⇒ 逃生目标是 `refuge=240,105,306`（**我的池顶**）⇒ `WalkToTask … PLAN_UNREACHABLE … walk_no_path` 失败。
   ⇒ 这是 **"`isRefuge` 只查'干+可站+在半径内'、不查可达性"的第一个真实案例**（D-236 只是推理），
   已记进台账 §5.11 作为候选改进项。

---

### D-238：出口从"几何存在"升级为"**可规划**"—— 别再为一个走不到的落点杀任务（2026-09-15）

**唯一闭环**（用户 2026-09-15 明确要求"别开太多支线"）：只做这一件，不碰水面专用理由码、不碰"维生写授权"提案（B）、不碰丙。

**证据（真实案例，D-237 期间实测）**：水池第一版忘了拆天花板 ⇒ 几何落点成立
（`refuge=240,105,306`，干、可站、距 4 格）⇒ `decide` 判 `INTERRUPT` ⇒ **当前任务被杀** ⇒
`SurvivalExitTask` 1 tick 就 `PLAN_UNREACHABLE … walk_no_path` 失败。两次都发生了，只是"派了一个走不到的活"。

**改法（三处，都是一行级）**：
1. `PathRequest.withBudget(...)`（沿用 `pureTraversal()` 的"复制一处改动"模式）。
2. `SurvivalSystem.plannableRefuge(bot, hazardType)`：先取几何落点，再跑一次**真规划预检**
   （`CorePathPlanner` + 小预算 `600 节点 / 20 ms`）。**只有 `UNREACHABLE`（搜索空间穷尽）才算"没有出口"**；
   `SEARCH_LIMIT`（预算耗尽、可达性未知）沿用既有口径按"未知 ⇒ 允许尝试"处理。
   预检成本按"危险类型 + 脚位"缓存在 monitor 里（一场危险最多一次）。
   **`hasRefuge` 保持几何语义不变**（夹具与其它调用点不受影响）。
3. `decide()` 改用 `plannableRefuge`；`BotManager.startSurvivalExit` 同样用它，并把
   "几何有、规划无"如实登记为 **`exit=unreachable`**（与"压根没有落点"的 `exit=none` 区分开）。

**判据（零新增电池步，挂既有 BASELINE `survival_exit`）**：新相位 `UNREACHABLE_REFUGE` ——
5³ 实心石盒里，隔着石墙留一个 1×2 空气袋（**几何上是落点、规划去不了**）。断言：
① 前提自证"几何上确实有落点"；② `plannableRefuge == null`；③ **行为：软危险 ⇒ `HOLD_NO_EXIT`（不再 INTERRUPT）**；
④ 对照：硬危险仍无条件 `INTERRUPT`；⑤ 正对照：回到平台后同一危险类型**能**规划到落点。
`checks 78 → 87`。**反向对照**：把 `decide` 退回"只看几何" ⇒ 判据③精确变红 ✅
实测日志：`落点 272,100,308 几何上成立，但**规划不可达**（status=UNREACHABLE）⇒ 按「没有出口」处理`。

**没做（明确留给台账，不建支线）**：① "被水挡住"的水面专用理由码 —— 现有 `UNREACHABLE` + 失败理由已足够
决策层行动；② 出口**列表**（现在只验最近那个；最近不可达就如实说不可达，不去找更远的 —— 那是策略层的事）；
③ B（维生写授权）；④ 丙。三条都在台账 §5.11 各记一行，触发条件不变。

---

### D-239：用户批准「提案 B」的五个问题（2026-09-16）—— **记录在案，B4 未实现**

用户 2026-09-16 回复"好，我同意这几条建议"，逐条定案（提案 `docs/authz/PROPOSAL_B_survival_write_authorization.md` v2）：

| # | 定案 |
|---|---|
| Q1 | **轴 = 任务信封**（`MovementCapabilities.changesWorld()`）：只有信封允许改世界的任务才有逃生写权；**不按**危险种类/地形分 |
| Q2 | 动作集 = **放置 + 破坏 + `PILLAR`**（竖井那档必须能挖/能垫） |
| Q3 | 准备金 = **破坏 8 / 放置 8**（从 `WriteBudget` 的 64 起点里预留）；**每次危险事件最多升档 1 次**；用尽 ⇒ 如实失败 |
| Q4 | 回收 = **放置 TEMP（必拆，复用 `scaffoldRemoval`）+ 破坏按 KEEP 登记**（挖掉的地形无法"回收"） |
| Q5 | **先做最便宜且不碰红线的一件，再做 B4** |

**状态**：本次只记录裁定；B4（逃生准备金：新 `WriteReason` + `WriteBudget` 分区 + `MovementGrant`/策略表/`AUTHZ_REGISTRY.csv` 登记 + 判据）**未实现**，
下一步按本表实施 —— 后续会话**不必重新征询**，照此表做即可。

### D-240：用户批准的「B1 原路返回」经**实测 + 推演**判定**无可测量收益** ⇒ **不落地**（2026-09-16）

按 D-239 Q5 先做 `survey09` §8 的 B1（给 `SurvivalExitTask` 加"来路轨迹"、候选来源从"8 格内最近安全格"改成"先看轨迹"）。
**实现已在工作树完成并实测，随后回退**（不把无可测量收益的代码塞进树里，用户 2026-09-16 明确要求"别做成各种最小闭环"）。

**实测**（L 形走廊场景，bot 真的走进去后再问候选）：`候选对比：trail=65,64,102 radius=65,64,102 escape=65,64,102` —— **三者同格**。

**推演（比实测更强，可直接引用）**：
1. `isRefuge(pos)` = 脚/头位可穿过 + 脚下有可站支撑；而 TRAVERSE/DIAGONAL/ASCEND 的**目的地生成条件**是
   `canStandCentered`（同一组谓词）⇒ **"可通行且可规划"的格子按定义就是落点**。
2. ⇒ bot 的**上一格**（它刚走过 ⇒ 回程 1 步必然可规划）**永远是"最近且可达"的落点** ⇒
   D-238 的单候选半径扫描**本来就会挑中它** ⇒ 轨迹与半径给出同格（实测吻合）。
3. 只有当"退后一步"被切断时轨迹才有话说；而那时**同一套纯通行规划器**也过不去
   （⇒ 那正是缺水位能力 / 缺 `PILLAR` / 缺写授权的那几档，属 B4 与 C1，不是候选来源问题）。
4. ⇒ **`survey09` §8 的"轨迹比 8 格长 ⇒ 直接缓解 §4.4 竖井无出口"这句站不住**：轨迹**长**不代表能过去，每格仍要过
   D-238 的规划预检。（勘测结论需亲验的又一例 —— 与 `survey09` §1.1 自己的错误更正同源。）
5. 同理 `survey09` §9.2 的 **B3（逃生路径岩浆检查）**价值也有限：`PlanRouteSafety.lavaContacts` 只查
   "bot 身体占据/站立的格子"（注释明确"站在自建方块上、方块下面才是岩浆 = 安全"），而 **Movement 校验器本就拒绝岩浆格**
   ⇒ 它已是**构造性满足**（价值在回归断言）；残余风险是"贴着岩浆边缘走" = §5.6 风险层（仍挂账）。

---

### D-241：逃生准备金落地（用户批准的提案 B，第一步）—— 2026-09-16

按 D-239 的五条定案实现"**路径规划器可以在什么条件下写世界**"的受控口子（`survey09` §3/§7 的合并拍板）。

**实现的五件（都带 file:line 可 grep）**：
1. **轴 = 任务信封**：新类 `pathing/core/WriteEnvelopes.java` —— "本任务期间出现过写请求吗"。
   **推导事实，不维护任务名单**：`PathRequest` 的唯一构造点记一笔（按 `MovementType.changesWorld()`，新加的唯一静态口径：
   `MovementType.java` 的 `changesWorld()`，`pureTraversal()` 也改成从它派生，消除两份名单）；
   `BotSession.beginTask` 清空 ⇒ 信封不会跨任务泄漏。闸门用在 `BotManager`（`decide(bot, hazard, escapeWrites)`）。
2. **受限请求工厂**：`PathRequest.survivalEscape(...)`（`:53`）= 纯通行 + `PLACE_STEP_AND_TRAVERSE` + `PILLAR`
   + `BREAK_AND_TRAVERSE`/`BREAK_AND_ENTER`；**刻意不含** `DOWNWARD`/`FALL`（向下挖/下落不是"离开危险"）；
   搜索预算**有界**（4000 节点 / 100 ms）。
3. **登记**：`WritePolicyMatrix` 新增 `Task.SURVIVAL` + `MovementGrant.SURVIVAL_ESCAPE` + 前缀 `survival-escape`
   + 两行 **P-23/P-24**（EXTERNAL/WORKSPACE × SURVIVAL，`Obligation.TEMP`，理由 = 执行器真正会发的那两个）；
   电池步 `write_policy`（CORE）审计通过。
4. **阶梯用法（写权不滥用）**：`decide` 只有"纯通行确实 `UNREACHABLE`"才升档；升档时装 `WriteBudget.Caps(8, 8, 0)`；
   `SurvivalExitTask(bot, refuge, withReserve)` 覆写新的 `WalkToTask.buildRequest(...)` 钩子用受限请求。
5. **判据**（挂既有 BASELINE `survival_exit` 新相位 `SHAFT_ESCAPE`，**零新增电池步**）：2 格深 1×1 竖坑
   （`survey09` §4.4 那个"上不去、挖不动、退路被切"的杀手）—— 纯通行 `exit=none`、带准备金可达、
   信封闸门三连（false ⇒ 写请求 ⇒ true ⇒ clear ⇒ false）、**端到端真的垫出来**
   （`segment_start type=PILLAR from=330,99,306 to=330,100,306` ⇒ `[WalkToTask] completed … actualFoot=330,101,305`）。
   **checks 87 → 99**；**反向对照**（关掉准备金档）三条判据精确变红。

**如实登记的偏差/未做（不要当成已完成）**：
- **没有新造"逃生专用理由码"**（提案文本里曾写 `ESCAPE_*`）：执行器实际发 `STEP_PLACEMENT`/`PATH_ACCESS`
  （`PillarExecution:57`、`PlaceStepAndTraverseExecution:41`、`BreakAndTraverseExecution:52`），
  归因由 **`requester="survival-escape"`** 承担 ⇒ 新造词只会再造一个没人发的死值（K-5 同族）。策略表行按实际声明。
- **准备金是"逃生作用域的独立上限 8/8"，不是从任务 64 里做减法**（未实现减法；总量上限因此是 64+8 而非 64）。
- **自动回收未接线**：矩阵行已声明 `Obligation.TEMP`（放置必拆），但"过桥后沿桥回收"仍要复用 `scaffoldRemoval` ⇒ **未做**。
- 判据只覆盖竖坑一档；提案里的水渠搭桥、预算上限守卫、水/深水档尚未建场景（水档本就属 B/C1）。

**⚠️ 期间抓到并修掉的一个真陷阱（值得单独记）**：我一度把 `PathRequest.pureTraversal()` 从**字面集合**改成
"从 `MovementType.changesWorld()` 过滤派生"（想消除两份名单的漂移）。结果 **CORE 里一条与它毫不相干的判据翻红**：
`survival_exit` 的「着火必须真的造成伤害」——因为 `Set.of(...)`/`Collectors.toSet()` 的**迭代顺序未定义**，
派生会悄悄改掉顺序 ⇒ 规划/成本选择随之变化 ⇒ 火焰伤害与自然回血的 tick 对齐错开 ⇒ 血量不再下降。
**修法**：① `pureTraversal()` 恢复字面集合（注释写明"为什么故意不派生"）；
② 漂移改由**门禁**兜住 —— `WritePolicyCheckTask` 新增一条断言"纯通行名单与 `changesWorld()` 互为补集"（写错就红）。
教训：**"消除重复来源"要用门禁，不要用会在运行期改变行为顺序的派生**。

---

### D-242：水里那档 —— 写准备金**规划得到、执行不了**；正解是"水柱"这一小块（2026-09-16）

按 D-241 之后继续做"②水渠/水里场景 + 预算守卫"。新相位 `FLOODED_SHAFT`（1×1×2 坑**灌满水**，房间地板 y+2）
把用户最初那个问题（"被水围住怎么办"）做成可复现判据。结果**逼出两条硬事实**：

**① 写准备金在水里"规划得到、执行不了"**（如实记，这条改变了后续排期）：
- 规划：`纯通行去不了 340,101,305 ⇒ 逃生准备金…status=REACHED` ✅（生成器**没有**流体排除 ⇒ 往水里放方块/垫柱子都生成得出来）；
- 执行：`segment_start type=PILLAR from=340,99,306 to=340,100,306` ⇒
  `no_progress … wastedJumpLandings=3（起跳后落回同一脚位格）` ⇒ `SEGMENT_NO_PROGRESS` ⇒ `TIMEOUT` ❌。
  **根因**：`PILLAR` 执行器是**跳起-落地**式的（陆地方案），水里永远不会"落地"。
- **正解**（对齐 Baritone，D-036）：`MovementPillar.java:77-82` 的水分支 —— *"允许上升水柱，但只在我们已经在水中时"*
  及其执行段 *"stay centered while swimming up a water column"*（`MOVE_FORWARD`/按跳跃）⇒ **纯输入上浮、不放置**。
  ⇒ 这是"**丙的一个明确子集**"：**不需要**改合法位置集/成本模型（实测：坑底"水+实心底"按 `canStandCentered`
  本来就是合法脚位），只需**执行器在水里改用上浮**。比整个丙小得多。
  ⇒ 判据已就位（本相位即它的现成夹具）：断言故意写成"**断言当前的失败**"当 tripwire（做出来就翻红，提醒改文档）。

**② Q3 的"上限守卫"在当前几何下**不可构造**（判据设计错误，已改口径）**：
- 我原想"把额度压到 0/0 ⇒ 同一个落点必须不可达" —— **逻辑上不可能生效**：`escapeWithReserve` 自己会装上
  8/8（这正是生产该有的行为）⇒ 外部压低被覆盖（实测 0/0 依然 `REACHED`）。
- 另一个**结构性耦合**：`REFUGE_RADIUS=8` 与"额度 8"耦合 —— 落点要在 8 格内，就走不出需要 >8 个放置的路线
  ⇒ "额度真的会咬"的拒绝场景在当前几何下**造不出来**。
- 改成的口径（三条**可测**事实）：计划里**确实含写动作**（≥1，否则不是"靠准备金"出去的）、**写动作数 ≤ 8**、
  **移动集含 PILLAR/放置/破坏且不含 DOWNWARD/FALL**。**如实记：Q3 的额度是"声明 + 量出来"的，不是"拒绝过"的。**

**③ 顺带修掉一处红线纪律问题**：`WriteBudget.setCaps` 的 javadoc 明写"**夹具专用**"，而 D-241 的生产路径借用了它
⇒ 新增生产入口 `WriteBudget.capForEscape(...)`（自己名字、自己文档、装上有日志），`SurvivalSystem` 改走它；
夹具收尾把额度恢复成 `Caps.DEFAULT`（电池里整台是一个作用域，不恢复会限制后续步骤）。

**判据**：既有 BASELINE `survival_exit` 新相位 `FLOODED_SHAFT`（**零新增电池步**）：纯通行 `exit=none`、
带准备金计划 `REACHED`、额度三连、**已知缺口 tripwire**（水里执行 FAILED + bot 仍在原地水里）。
**checks 99 → 112**；反向对照（关掉准备金档）仍精确变红。

---

### D-243：水里那一小块内核 —— `PILLAR`/`ASCEND` 在水里改为**按住跳跃上浮**（"丙"的第一个子集，2026-09-16）

按 D-242 的结论动内核（**只动执行器在水里那一支，不动合法位置集/成本模型**）。

**根因（实测，两次翻红都在同一处）**：
- `PILLAR from=340,99,306 to=340,100,306` ⇒ `no_progress … wastedJumpLandings=3（起跳后落回同一脚位格）`；
- 第一段修好后，`ASCEND from=340,100,306 to=340,101,305` ⇒ **同一个症状**。
- 共性：两个执行器都用**一次性 `jumpOnce`**（陆地方案），而**水里抬升被阻尼** ⇒ 到不了目标高度 ⇒ 落回同格。

**改法（对齐 Baritone，D-036）**：`reference/baritone/.../MovementPillar.java:150-161` 的水柱分支是
"居中 + 游上去"（靠"朝上看 + 前进"耦合，**不放置**）；服务端假人**没有那个耦合**，所以显式按住跳跃
（`bot.controller().setJumping(true)` —— 本仓 D-237 的 `SurvivalFloatTask` 已实测这条路有效）：
- `PillarExecution`：脚位格是水 ⇒ 按住跳跃直到到达放置高度，之后仍按陆地逻辑在脚下放方块；
- `AscendExecution`：脚位格是水 ⇒ 按住跳跃（**跳过**陆地那套"先对准再跳"门控 —— 那是防斜跳落回原地的）；
- 两处的水判定统一为"**当前脚位格**是水"。

**判据**（既有 BASELINE `survival_exit` 的 `FLOODED_SHAFT` 相位，**零新增电池步**）：
D-242 里那条 tripwire（当时**故意断言"水里执行失败"**）**按设计翻红，现已翻成正断言**：
`水里逃生：终态=DONE` + `bot 从水里出来了（脚位 y ≥ 101、inWater=false）`。**反向证据即 D-242 的那一轮**
（未改前同一条判据 FAILED + `wastedJumpLandings`）。

**⚠️ 这一档做到哪、没做到哪（不要读成"水里能走了"）**：
- ✅ 水里**垂直**移动（上浮）：`PILLAR`（含在脚下放置，**消耗写额度**）与 `ASCEND`（**纯输入、不写世界**）；
- ❌ **不解决**"整列都是水时不放方块的省料分支"（Baritone 水柱的 `LADDER_UP_ONE_COST` 那一支）⇒ 现在仍会放方块（后续切片）；
- ❌ **不解决**"浮在水面（脚下是水、没有可站支撑）时起不来"：那不是执行器问题，而是**合法位置集**问题
  ⇒ 仍属"丙"的其余部分（`PillarExecution` 的水分支只覆盖"脚位格是水但有支撑/在柱中"的情形）；
- ❌ 不解决水平游/蹚水（`SurfaceMovementProvider:127-130` 仍跳过流体目的格）、不解决落水（D-058 定案不做）。

---

### D-244：水柱**省料分支** —— 起点/目的地**都是水**时 `PILLAR` 改为**上浮不放方块**（2026-09-16）

接着 D-243 往下做（那一条把"规划得到、执行不了"修掉了；这一条修掉"水里白放方块"）。
**只动 `PILLAR` 执行器的水柱那一支**，不动合法位置集、不动成本模型、不动 D-076/策略表。

**对齐依据（Baritone，D-036）**：
- 执行：`movements/MovementPillar.java:150-161` —— `isWater(fromDown) && isWater(ctx, dest)` ⇒ **居中 + 游上去，
  全程不放置**，完成口径是 `playerFeet().equals(dest)`（`:157`）；
- 成本：`:77-82` —— `toBreak`/`fromState` 是水且 `srcUp` 是水 ⇒ 直接返回 **`LADDER_UP_ONE_COST`**
  （注释原文"allow ascending pillars of water, but only if we're already in one"）。

**改法**：
- `MovementHelper.isWater(Level, BlockPos)`（**新唯一定义**，对照 Baritone `isWater:698-712`）：口径是**流体状态**，
  与 Baritone 的"方块是 `LiquidBlock`"只在**含水方块**那一档不同 —— 那种格子本来就被 `canWalkThrough` 判为
  不可穿、所有调用点都先被挡下（已登记）；同时把 `AscendExecution` / `SurvivalSystem`（3 处）的**内联副本**收口到它。
- `PillarExecution`：进入执行时读一次世界判 `swimColumn`（`from` 与 `to` **都是水**）——
  - 是 ⇒ 按住跳跃上浮、**不放置**，完成判定改走"脚位到格即成功"（**水中没有 `onGround`、也没有支撑**，
    D-026 的"已落地/居中"两套口径**永不成立** —— 这正是 D-242「规划得到、执行不了」的另一半根因）；
  - 否 ⇒ D-243 的行为原样（水里按住跳跃到放置高度后仍放方块）；
  - 前置（放置资源/放置面）**故意不放开**：规划期 `appendPillar` 用的是同一组前提（"可规划即可执行"）——
    水柱省的是**方块本身**，不是"要不要带方块"。

**判据（既有 BASELINE `survival_exit` 的 `FLOODED_SHAFT`，零新增电池步，checks 112 → 118）**：
1. 前提自证三条（坑里 2 格都是水 / 水上那格不是水 / 计划第一段就是水柱 `PILLAR`）；
2. **主判据：脚位在水里时一次都没放方块**（逐 tick 取 `WriteBudget.places` 增量，并按"那一刻脚位是否在水里"
   归因 —— 比解析日志可靠）；**反向对照就是 D-242/D-243 那一轮**（当时水里那一段会 `[Pillar] placed`）；
3. **干地反向对照**：2 格深**干燥**竖坑仍然靠放置上来（`放置 ≥ 1`）⇒ 证明省料分支没串到陆地；
4. 端到端照旧：`水里逃生：终态=DONE` + 脚位 y ≥ 101 + `inWater=false`。

**实测结果（比预期更强）**：灌水竖坑这条出口**整段逃生一个方块都没放**（`放置=0`）——
计划就是 `PILLAR` 水柱上浮 1 格（11 tick，`[Pillar] swim`）+ `ASCEND` 从水里跳上干地板（14 tick）。
⇒ **D-242 的结论落地验证**：水里自救**不是授权问题**；逃生准备金的作用只是让"**写类 Movement**（`PILLAR`）
能被规划出来（纯通行档这一格根本不会生成），**额度实际一分没花**。

**如实登记的三处未做/差异**：
- 成本模型**没跟**（Baritone 水柱给 `LADDER_UP_ONE_COST`，Alice 仍算 `PILLAR_COST`）：只影响"选不选水柱路"，
  不影响正确性；
- `PILLAR` 仍属**写类 Movement** ⇒ 纯通行档连"不写世界的水柱上浮"都生成不出来（**过严但安全**；
  要按"实际写不写"细分就得动 D-076 / P-01 那条互补集门禁，**本轮不碰**）；
- **浮在水面（无可站支撑）仍然起不来**（合法位置集问题，仍是"丙"的其余部分）、水平游/蹚水、落水（D-058 不做）。

**顺带修掉的夹具缺陷（本轮真踩到一次）**：`fillBlocks` 在**区块未加载**时 `/fill` 会**静默 0 改动** ⇒
330 那一档三个 fill 全 0、bot 被传进空气、8 格内没有落点 ⇒ **一次红了 8 条判据，看起来像"改动坏了"**。
现在 `fillBlocks` 先 `areaLoaded` 检查、未加载就把 bot 传到区域中心（玩家 ticket 同步加载区块），
原有的 `changed >= expected` 断言不动。（`RegressionBatteryTask:648-650` 的注释早就警告过这一点——
"那时区块还没加载，`/fill` 会不落地 ⇒ 判据在虚空里假绿"，只是当时没做成原语级守卫。）

---

### D-245：逃生放置**不做自动回收** —— 回收时机交给玩家许可（2026-09-16 用户裁定）

**用户原话（决定）**："逃生的自动回收……不要做成自动的，这个适合经过玩家允许再去回收，
否则容易引起逃生循环。"

**这条为什么不只是偏好（我同意的理由）**：自动回收会把 bot **重新关回坑里** —— 逃生垫的方块正是它的出路，
拆掉 ⇒ 危险再触发 ⇒ 再逃生 ⇒ **逃生循环**；而且它在**逃生任务内部**执行，等价于"自己把刚修好的门拆了"。

**既有事实（先说清楚今天是什么样，避免误读成"以前是自动的"）**：
- 逃生放置**本来就没有自动回收**（D-241 只把 `Obligation.TEMP` 登记进策略表，没接 `scaffoldRemoval`）；
- **玩家许可的回收入口已经存在**：`/alice restore`（`BotCommand:230/560` ⇒ `BotManager.assignRestore` ⇒
  `RestoreScopeTask`，走 `PathRequest.scaffoldRemoval`"只拆不建、只向下拆脚"）与零参数物品 `alice:restore_check`；
- 待拆**不静默**：`BotCommand:710` 会在任务收尾提示"账本仍有 N 条我方临时方块未拆（`/alice restore` 可清理）"。
⇒ 所以本决定的实际内容是：**把"不做自动"从"没人写"升级成"有人写就红"**，并把时机规则写进权威来源。

**⚠️ 当场点出的冲突与最小补偿**：`ledger/WorldModLedger.java:35` 写着 D-081 的不变量
"**`Policy.TEMP` 的条目必须在同一 scope 内被移除**"。D-245 是这条不变量在**逃生档**上的**显式例外**：
- **没有变的东西**：条目仍是 `TEMP`（**照样要拆**）、回收仍走同一条生产路径（`RestoreScopeTask`）、
  `TEMP` 的权限语义与"建拆同权"不变 —— 变的只是**时机**（"同 scope 内自动" → "玩家许可后"）；
- **补偿（全部可执行，不靠"记得"）**：
  ① **负向门禁**（`survival_exit` 的 `SHAFT_ESCAPE` 相位，checks **118 → 120**）：逃生结束后
     **方块仍在世界里**（`SHAFT_PIT_BOTTOM` 实测 `Cobblestone`）+ **账本里仍是 `TEMP` 待拆**
     ⇒ 谁把自动回收接进逃生路径，这两条立刻红；**反向对照已做**：把两条断言取反 ⇒ 恰好这两条变红
     （失败信息自带实测值，见下）；
  ② 回收入口保持**唯一且玩家触发**（`/alice restore` / `alice:restore_check` 都要玩家身份），逃生任务自己不调；
  ③ 策略表 **P-23/P-24 的"为什么"**写明时机规则（表是权威来源，不是散文规则）。

**反向对照原文（本轮实测，FAIL 那一轮的 SUMMARY）**：
`SUMMARY checks=120 failures=2 [逃生不自动回收（D-245）：垫脚方块**仍在世界里**（330, 99, 306 = Cobblestone）…,`
`逃生不自动回收（D-245）：账本里**仍记着这笔待拆**（policy=TEMP）…] → FAIL` ⇒ 取反后恰好这两条红。

**明确不做（避免下次又把它捡起来）**：不做"逃生任务结束时自动拆"、不做"危险一解除就拆"，
也不做"回收前先判断 bot 还在不在危险里"这类**推测性守卫** —— 按 D-240 的教训（量不出收益的机制不建），
真出现"逃生留下的方块长期堆着影响游玩/卡路"再谈。

**复核触发**：① 真实出现"逃生放置长期堆积、影响游玩或卡路"；② 出现"想一键清掉但又怕 bot 被关回坑里"
的真实操作需求（那时才考虑给 `/alice restore` 加"bot 不在危险中"之类的前置，而不是给逃生加自动拆）。



---

### D-246：§5.11 ②「出口列表」**判定基本为空 ⇒ 关闭，不建机制**（2026-09-16）

台账 `§5.11` 长期挂着一条"出口列表"（"现在只验**最近那个**落点，最近那个不可达就说不可达，
不找更远的"）。本轮**先把证明做完再决定要不要建**（与 D-240 同一把尺子）：结论是**纯通行档下它是空的**。

**证明（纯通行档）**：设 bot 站在脚位 `F`，且**存在**一条纯通行路线能到某个落点。
- 该路线的**第一步**目的地 `D` 是纯通行生成器产出的合法位置（"可站 + 脚位/头位可穿"）；
- 由 D-240 的共用谓词事实（`isRefuge` 与 `TRAVERSE/ASCEND` 目的地生成共用同一组谓词）⇒ **`D` 自己就是一个落点**；
- `D` 与 `F` 水平相邻 ⇒ 距离 **1**（同一格不可能，`nearestSafeRefuge` 排除 `F` 自己）；
  而距离 **< 1** 的候选只可能是"正上/正下"那两格 —— 正上格要求脚下的 `F` 是实心（`F` 是空气/水，不可能），
  正下格就是支撑块自己（实心，`canWalkThrough` 假，不是落点）⇒ **不存在比 1 更近的落点**；
- 任何距离 1 的落点 `X`（干、可站、头位可穿）必然会被 `appendTraverse` 生成边 `F→X`
  （`canStandCentered(X)` 成立即生成）⇒ **距离 1 的落点全都纯可规划**。

⇒ "存在纯通行出口" ⇔ "最近的那个落点纯可规划"。所以"最近那个不可达 ⇒ 判没出口"**不会漏掉真正的出口**，
"出口列表"在这个档上没有可测量的收益。

**残留缺口（如实登记，不做机制）**：只有**等距并列**时才可能误判 —— 最近距离上不止一个落点，
扫描顺序挑中了一个"即使动用准备金也到不了"的那个。要构造出这种几何，需要让某个等距落点**穷尽手段也进不去**，
即用到**人为的不可破方块（基岩级）**；正常游玩里没有这种地形 ⇒ 按 D-240 的尺子（量不出收益的机制不建）
**不建**。若真出现真实案例，最小改法是"候选按距离逐个预检、最多 N 个"（`SurvivalSystem.plannableRefuge`
的循环），届时再加，并同时把预检预算（现 600 节点 / 20 ms × 候选数）重新标定。

**复核触发**：① 真实出现"明明有路却判没出口"的案例（日志特征：`exit=unreachable` 但落点在 8 格内且干、可站）；
② 或出现玩家/任务需要"挑离目标更近的出口"（今天只按"离 bot 近"排序，不看目标方向）。

---

### D-247：水位切片 A —— **蹚水本来就通**，补上"水里的步子要按水速计价"（2026-09-16）

按 D-246 后修订的计划（用户："继续做完 A"）先做**判别性实测**，再动代码。

**实测（新夹具场景 `water_course`，先把地形建出来再问"今天到底行不行"）**：1 格深水沟（x=2..3、水在脚位格、
沟底是石头）**横跨整个场景宽度** ⇒ 绕不过去；起点 `(0,64,66)` → 目标 `(5,64,66)`，纯通行（`worldMod=false`）。
结果：**`REACHED` + 5 段全 `COMPLETED` + 零写入** ⇒ **蹚水今天就能走**（`canWalkOn` 走的是普通实心支撑分支，
合法性从头就是通的；`SurfaceMovementProvider` 主生成路径本来也没有流体排除）。这一条从今天起**有夹具常年守着**。

**但实测暴露真问题：耗时与成本完全脱节**
| 段 | 格子 | 实测 tick |
|---|---|---|
| 0 | 陆地 | **5** |
| 1 | 进水里 | **45** |
| 2 | 水里 | **42** |
| 3 | 出水 | 9 |
| 4 | 陆地 | 7 |

而计划报 `cost=5.00`（5 段 × 1.0）—— **水里那一格被当成陆地一格计价**。两处后果：① 选路会**偏爱**穿水
（明明绕一小段更快）；② `PathSession` 的**段超时是按规划成本放宽的**（`PathSession:419`）⇒ 水里的段预算
只有实际需要的 ~1/7。

**改法（对齐 Baritone 的公式结构 + Alice 的实测值，D-036 规则 3）**：
`CostModel.WATER_TRAVERSE_MULTIPLIER = 7.25`，作用在 `TRAVERSE`/`DIAGONAL` 上，
判定口径 = `isWater(to) || isWater(to.above())`（= Baritone `MovementTraverse.cost:87-90` 的
`isWater(pb0) || isWater(pb1)`，用 `context.waterWalkSpeed` 而不是 `WALK_ONE_BLOCK_COST`）。
- **结构抄、数值不抄**：Baritone 的 `waterWalkSpeed`（无附魔）= `WALK_ONE_IN_WATER_COST/WALK_ONE_BLOCK_COST`
  ≈ **1.96×**，那是按**真实玩家客户端游泳**标定的；Alice 的假人在水里是"站着蹬"（陆地一格 5~7 tick、
  水里一格 42~45 tick）⇒ **7.25×**。照抄 1.96 等于让水里的段预算只有实际的一半以下。
- **只改成本**：合法性、可规划性一律不动（`canWalkOn`/`canStandCentered` 一个字没改）；
  所以 D-076（书写授权）与本条无关。

**判据（`pathing` 步内，零新增电池步）**：
1. `water_course`（执行）：`COMPLETED` + 路线 `TRAVERSE` + **纯通行 ⇒ 零写入**（顺带断言"不靠放方块搭桥"）；
2. `water_course+cost`（只规划，复用同一份地形）：期望值**从计划自身推导** ——
   数出终点含水的步数与陆地步数，期望 = 陆地步×1.0 + 水步×`MEASURED_WATER_MULTIPLIER`，
   实际 `totalCost` 必须 ≥ 期望×0.9。实测 `water_steps=2 / land_steps=3 / cost=17.50 / expected=17.50`。
3. **反向对照**：把 `WATER_TRAVERSE_MULTIPLIER` 改成 `1.0` ⇒ `cost=5.00 / expected=17.50` ⇒
   **恰好这一条红**，而执行那条仍绿（两条判据互不污染）。

**⚠️ 这一轮踩到并记下的坑（比改动本身更值钱）**：判据第一版把期望值写成
`水步 × CostModel.WATER_TRAVERSE_MULTIPLIER` —— **自指**。把常量改成 1.0 做反向对照时，期望值跟着变成
`5.00` ⇒ **判据照样 PASS**（反向对照没红才暴露出来）。改成**判据侧独立第二来源**
`MEASURED_WATER_MULTIPLIER = 7.25`（注释里写明它是第二次独立标定），反向对照立刻精确变红。
> 与 D-241 的教训同源：**重复来源要用"门禁断言两边一致"来消，不要用"引用同一个常量"来消**。

**边界（如实登记）**：
- ✅ 只影响**选路**与**段预算**；❌ 不解决深水（浮着，切片 B）；❌ 落水仍按 D-058 不做；
- **未建模的小偏差**：从水里 `ASCEND` 出去实测 14 tick、成本仍是 10 tick 档（1.4×）—— 本轮只标定水平蹚水，
  留着下次按实测再定（不猜）。
- 水面/水下**没有**新 Movement：与 Baritone 一致（它也没有 `MovementSwim`），见 D-246 的对照文档。

**复核触发**：① 真实出现"水里那一格段超时"；② 成本与实测再次脱节（那时**两处一起改**：`CostModel` 常量
与夹具的独立来源，改一处会让 `water_course+cost` 变红 —— 这是**有意的**漂移门禁）。

---

### D-248：水位切片 B —— 勘查做完、**规划那半回退**（一次实测到的未解释回归，2026-09-16）

按 D-246 的对照结论做切片 B（Baritone `canWalkOnPosition:432-448` 的水位例外）。
**结论：那半先不落地** —— 它在**电池上下文**里引出一次我还没查清的回归。照例先实测、再决定，这次决定是"退"。

**做过的（已回退，可重做）**：
1. `MovementHelper.canWalkOn` 加水位分支（对齐 Baritone `:432-448`：下面是水时"**我这一格也是水**"才算支撑；
   流动水照 `:437-438`），并**有意收窄到"只认水面格"**（`!isWater(footPos.above())`）：假人靠按住跳跃浮着、
   潜不下去 ⇒ 合法位置集只放执行器真到得了的格子（K-4「可规划即可执行」；Baritone 允许任意水格，因它客户端能下潜）。
2. `Traverse`/`Descend`/`Ascend` 三处加"浮着段"完成口径（脚位到格即成功，同 D-244）。
   ⚠️ 期间实测到一个**精度坑**（这条即使回退也值得记）：**蹚水不能套浮着口径**（目的格是水但**下面实心** ⇒
   bot 站在水底、`onGround` 成立）—— 混在一起时 `water_course` 多出 2 次 `TRAVERSE_STALE_START`
   （在格边界就宣布完成、被水推回上一格）；按"下面是不是水"精确分流后回到 `sceneTicks=112`、0 重规划。

**为什么回退（实测证据链）**：
- **单跑全绿**：`single:pathing` PASS（新场景在内）、`single:survival_exit` PASS；
- **CORE 红**：`survival_exit=FAIL`（`SUMMARY checks=120 failures=3`）—— 灌水竖坑的逃生**换了路线**：
  从 `PILLAR(5.0)+ASCEND(1.67)=cost 6.67`（D-244/D-247 验证过的那条）变成 **3 段 `BREAK_AND_ENTER…→ASCEND→ASCEND`，
  cost 6.55**。原因清楚：**水面格 (340,100,306) 变成合法位置后**，多出一条"破墙进石腔 → 升到水面格 → 升上台面"
  的路（`ASCEND` 到水面格的边此前**根本不会生成**，因为目的格不可站）。
- 会话的 `futureTargetBlocked()`（`PathSession:629-659`）在**第一个 tick（`segmentTicks=0`，`0 % 间隔 == 0`）**
  就判 `SEGMENT_FUTURE_BLOCKED`。临时探针（**已删除**）给出确切位置：
  `index=2/3 type=ASCEND to=340, 101, 305 through=true walkOn=false 上方可穿=true 下方方块=Air`。
- ⚠️ **当时未解释的点（这是回退的真正理由）**：那一格的**下方 (340,100,305) 在几 tick 前的落点自证里还是石质**
  （`nearestSafeRefuge` 通过 ⇒ `canWalkOn` 为真），到健康检查时却读成 **Air**；而 `Air` 与同一行读数自洽
  （`canWalkThrough(to)` 也为真）。三个候选解释：① 读的是**未加载区块**；② 场景被前序步骤动过；
  ③ 谓词读的不是我以为的那一格。
  ➡️ **同轮内已查清（**D-249**）：②证实、①排除** —— 那一格是**同一条计划自己的 `BREAK_AND_ENTER` 清掉的头位格**，
  不是未加载区块；回退仍然成立，但理由从"未解释"改成"**搜索会生成踩在自己写入结果上的边**"。

**决定与依据**：同一问题第二次失败 / 证据冲突 ⇒ **停止加特判**（D-236 之后的既有纪律），**回退那半**，
只保留 D-247（水速成本 + 蹚水，已 `SERVER_TESTED`）与**现状登记判据**：
`refused("deep_pond_course")`（从岸上出发：今天纯通行过不去深水）、
`refused("deep_pond_course+floor")`（从池底出发：判 `UNREACHABLE`）——
**断言的是今天的事实**，谁把水位那半做对（游过去），这两条就会变红并提醒改文档（同 D-242 的 tripwire 用法）。

**下一步（查清再落地，顺序别再跳）**：
1. 先**证实/证伪"未加载区块"**：探针里同时打 `level.hasChunkAt(to)`、`bot.chunkPosition()`、以及**同一 tick 再读一次**
   `(340,100,305)` 的方块；
2. 若属上下文相关 ⇒ 做**工作版/失败版对照**（同一 jar，`single:` 与 `core` 两态），不许猜；
3. 落地水位例外时**必须连"会话健康检查的谓词一致性"一起改**：生成器认的边，`futureTargetBlocked` 必须也认
   （否则就是"可规划不可执行"，就是这次回归的形状）。

**边界**：本条没有新增任何写授权（与 D-076 无关）；`isRefuge` 仍要求干燥；**水平游/深水穿越仍不支持**。

### D-249：D-248 那个「读成 Air」的谜团查清了 —— **是我们自己的执行器破的**（2026-09-16，同轮内追查）

**结论（一句话）**：D-248 里「未解释的 `Air`」**既不是未加载区块、也不是前序步骤动过**，而是
**同一条计划里的 `BREAK_AND_ENTER` 自己把那一格挖掉了**：`BreakAndEnterExecution` 清的格子是
**脚位 + 头位两格**（`positionsToBreak = {to, to.above()}`；对照 Baritone `MovementTraverse.java:56`
`new BetterBlockPos[]{to.above(), to}` —— **同款**），而该计划的**最后一段 `ASCEND` 正好要踩在这两格里被清掉的头位格上**。

**证据链**（失败轮日志：`/home/fb486/alice-server/logs/debug-3.log.gz`，16:06）：
1. `16:06:49.150 [WRITE] break 340, 100, 305 minecraft:stone by=survival-escape:attempt0:PATH_ACCESS`
2. `16:06:50.550 block_break_done pos=340, 100, 305` + `[BreakEnter] cleared pos=340, 100, 305 index=1/2`
3. `16:06:50.649 [Probe-FUTURE] … type=ASCEND to=340, 101, 305 … 下方方块=Air`
   —— `(340,101,305)` 的支撑正是 `(340,100,305)`，**它被第 1 步自己清掉了** ⇒ `walkOn=false` 与 `Air` **完全自洽**。
4. 该轮 3 条失败判据（`SUMMARY checks=120 failures=3`）：`前提自证：计划第一段就是水柱里的 PILLAR`（实际是 `BREAK_AND_ENTER`）、
   `端到端：水里逃生任务到达终态（FAILED）`、`端到端：bot 从水里出来了（脚位 y=99）`。

**D-248 遗留问题②（单跑绿 / CORE 红）的答案 = 计划形状不同（同一 jar、同一夹具）**：

| 轮次 | 日志 | 灌水坑逃生的计划 |
|---|---|---|
| 单跑（16:02，PASS） | `debug-4.log.gz` | `[0] PILLAR(swim) (340,99,306)→(340,100,306)` + `[1] ASCEND →(340,101,305)`（2 段，D-244 那条） |
| 切片 B 的 CORE（16:06，FAIL） | `debug-3.log.gz` | `[0] BREAK_AND_ENTER (340,99,306)→(340,99,305)` … `[2] ASCEND →(340,101,305)`（3 段，cost 6.55 vs 6.67） |
| 回退态的 CORE（16:13，PASS） | `latest.log` | 与单跑同形（`PILLAR(swim)` + `ASCEND`，2 段） |

⇒ 切片 B 让**上面的水格 `(340,100,306)` 成为合法脚位**，「挖穿侧壁 → 升进水面格 → 再升上地板」这条更便宜的路才成立
（`ASCEND` 进水面格那条边在回退态**根本不生成**）；而这条路的**最后一段踩在自己挖空的头位格上** ⇒ 自我毁灭。

**根因（Alice 侧的真问题，与水位无关）**：**搜索不模拟自己的写入**。
`SurfaceMovementProvider.java:163-168` 已把这取舍写在注释里（「本移动会先破坏目的地的躯干+头位方块，规划期查它会自相矛盾……
代价是破坏类请求的目标格在规划期无法被证明可站，只能靠运行期 EXACT 兜底」）。
**Baritone 也没有**：`CalculationContext.java:53-77` 只有 `bsi`（真实世界视图）+ `allowBreak`/`allowBreakAnyway`；
全仓 `blockedBy`/`plannedBreaks`/`simulate` **零命中** ⇒ Baritone 靠「执行时发现代价变 `COST_INF` ⇒ 重算路径」兜底。
Alice 的差异：`PathSession.futureTargetBlocked():629-659` **用真实世界做前瞻健康检查** ⇒ **提前**抓到这条不自洽的计划；
而重规划时 bot 已被水推回坑里（`actualFoot=340,99,306`）⇒ **从同一起点重算出同一形状**（attempt 1 改成向东挖，同样自我毁灭）
⇒ 2 次重规划用尽 ⇒ `FAILED`。**这是「自我毁灭计划 + 从原点重算」的组合，不是水位能力不足。**

**下一步（三条路，待用户拍板；本条不改任何代码）**：
1. **搜索侧有界自我写入感知**：节点携带「最近 k 条边清掉的格子」，拒绝把那些格子当支撑/可穿 ⇒ 直接选出 D-244 那条合法路。
   **偏离 Baritone，必须登记**；风险 = 边变少（可能让别的场景变 `UNREACHABLE`）。
2. **计划级自洽校验 + 收窄重试**：校验不过则以「禁用该 Movement 类」重搜（更贴 D-076 的显式授权气味，但多一层）。
3. **维持现状**（切片 B 不落地），把这条登记为**潜在不自洽**，并补一条**不需要水位**的复现场景（否则它只在切片 B 下可见）。

**取证经验（值得复用）**：无头电池每轮都**覆盖** `/tmp/alice-headless-server.log`，但 `alice-server/logs/` 里的
轮转 `debug-N.log.gz` **按服务端启动顺序保留历轮** ⇒ 失败轮的判据行/探针行可以回溯 grep（本轮就是这样找回来的，
不必重跑 4 分钟一轮的电池）。

**边界**：本条为**只读调查结论**，未改代码、未改授权；水位切片 B 仍处于回退态；水平游/深水穿越仍不支持。

### D-250：自我写入感知 ① 的**实测结果** —— 搜索内按路径过滤会**把可解变不可解**（2026-09-16；建议改法见末段）

**做了什么（实验，已全部回退；补丁留档 `/tmp/d250-selfwrite-experiment.patch`）**：按 D-249 的路线①在
`AStarMovementSearch` 加"路径自我写入感知" —— 扩展节点时沿祖先链收集**本路径破坏过的格子**
（`BREAK_AND_ENTER` 脚位+头位、`BREAK_AND_TRAVERSE` 中间列、`DOWNWARD` 目的格），**拒绝支撑落在这些格子上的边**；
`PILLAR` / `PLACE_STEP_AND_TRAVERSE` 豁免（支撑由放置产生）。为复现 D-248 的现场，另把水位切片 B 的四个文件改动**临时**加回。

**实测（同一 jar = 切片 B + 该修法）**：

| 轮次 | 灌水坑逃生（目标 `340,101,305`） | 读数 |
|---|---|---|
| `single:survival_exit` | `[PILLAR(5.00) → ASCEND(1.67)] = 6.67` **REACHED**（正是 D-244 那条 ✅ 修法按预期挡掉了挖掘路线） | `own_write_support=0` |
| `core` | **UNREACHABLE**（❌ 可解变不可解） | `own_write_support=245`、`descend_precondition=4789` |

⇒ 同一场景、同一 jar：**单跑绿、CORE 里没路**（D-248 的"单跑绿/CORE 红"换了张脸又出现一次）。

**机制（算术自洽）**：A\* 用**位置**做节点键 ⇒ 到达 `(340,100,306)` 的两条路只留更便宜那条：
- 干净前缀（`PILLAR`）= **5.00**；带挖掘前缀（`BREAK_AND_ENTER` 4.88 + `ASCEND`）= **4.88**
  ⇒ **更便宜的挖掘前缀占住该节点**（它的 cleared 集合含 `(340,100,305)`）；
- 于是"最后一段 `ASCEND` 落在 `(340,100,305)`"被判非法 ⇒ 目标不可达；而干净前缀（5.00）因为更贵
  **永远不会重挂**（`tentativeCost >= neighbor.cost` 就 `continue`）。
- 单跑里同样的挖掘边是 **5.08** > 5.00 ⇒ 干净前缀赢 ⇒ 走 D-244 那条 ✅（也解释了 D-248 的"单跑绿 / CORE 红"）。

**关键论证（为什么不能靠"生成期规则"绕开）**：出问题的那条边 `ASCEND (340,100,306)→(340,101,305)` **本身是合法的** ——
在 `PILLAR` 前缀之后它成立（就是 D-244 那条路），在挖掘前缀之后它不成立（支撑被自己挖掉）。
**同一条边、两种前缀** ⇒ **不存在"只看这条边自己"的路径无关规则**能区分它们 ⇒ 只有两条路：
①′ 把约束放进**搜索状态**（位置键 → 位置+约束键；节点数上涨、要动 A\* 内核），或 ②′ **搜完再校验**。

**✅ 已实施的改法（②′，用户 2026-09-16 拍板）**：**计划级自洽校验 + 有界重搜**。
- 新增 `pathing/core/search/SelfWriteConsistency.java`：沿计划回放写入（**与执行器同一口径** ——
  破坏格直接调 `BreakAndEnterExecution.collectBlockers` / `BreakAndTraverseExecution.collectBlockers`，
  放置格调 `PillarExecution.placePos` / `PlaceStepAndTraverseExecution.placePos`，后两者为此各加了一个
  坐标版重载、**唯一定义仍在执行器里**），逐边检查"支撑是否落在本计划早先挖掉的格子上"；
  放置会把该格从"已清空"里删掉（"又放回来了"，避免误报）。
- `CorePathPlanner.plan`：搜完校验，冲突就禁掉那条**"清空者"边**（按**具体边** `EdgeKey(类型,from,to)` 禁，
  不是禁整个 Movement 类）重搜，最多 `MAX_SELF_WRITE_RETRIES = 3`；重搜若搜不到 ⇒ **如实交出去**
  （原计划已证明不可执行，不拿它冒充 REACHED）；K 次仍冲突 ⇒ 交出最后一次并计数。
  只校验 `REACHED` 的整条计划；K-1 的 `PARTIAL` 前缀不动（前缀语义是"先走这段"）。
- `AStarMovementSearch` 只加了一个**路径无关**的"禁用具体边"过滤（`skipped_forbidden=` 计入失败说明）。
- 记账：`PathingStats` 累计 `selfwrite_conflict`（发现即已修，信息码）/ `selfwrite_unresolved`（**真异常**）；
  **电池新增硬自断言**（`RegressionBatteryTask.finish`）：`selfwrite_unresolved == 0`，摘要里带
  `自写入冲突=N` —— 这条断言正是 D-248 那个 bug 的**常绿守卫**。

**实施后的实测（2026-09-16）**：
| 轮次 | 结果 |
|---|---|
| 修法**单独**（当前树，切片 B 仍回退）：`single:pathing` / `single:survival_exit` PASS、CORE PASS | 今天**零冲突**（`自写入冲突=0`、无 `[SelfWrite]` 告警）⇒ **行为零变化**，守卫处于"潜伏但可用" |
| 修法 **+ 切片 B 实验**（`single` / CORE） | ✅ 守卫**在真实现场按预期开火**：`[SelfWrite] 计划不自洽（第 1 次）：BREAK_AND_ENTER 清掉 340,100,305，后面的 ASCEND 却要踩在它上面 ⇒ 禁掉那条边重搜` → `重搜 1 次后计划自洽 goal=340,101,305 movements=3`（**不再是 D-248 那种"交出不安全计划"**） |
| 同上，切片 B 的**执行** | ❌ 仍失败，但**原因已换**：新计划是"破墙 → 升进水面格 → 升上地板"，第 1 段结束后 bot 从浮着的水面格被推/落回坑里（`ASCEND_STALE_START`，`actualFoot=341,99,306`）⇒ **切片 B 现在卡在"跨段持续浮着"这一条**（D-248 已登记的下一个缺口），与自我写入无关 |

**结论**：②′ 是**可落地**的（对今天零影响、对 D-248 那类问题有守卫、有电池硬断言）；**切片 B 仍不落地**
—— 它现在只卡在"水里跨段保持浮着"（需要单独拍板：入水前先跳 / 会话级持续浮着）。
- **不在本次范围**（登记为边界）：放置类镜像风险（"自己放的方块占了后面要穿的格"）。

### D-251：水位切片 B **落地** —— 顺带挖出两个"水里没有落地"的真 bug（2026-09-16）

**落地内容（切片 B 正式版）**：
1. `MovementHelper.canWalkOn` 加**水位支撑例外**（对照 Baritone `canWalkOnPosition:432-448`）：下面是水时，
   **"我这一格也是水"才算支撑**；**有意收窄到只认"水面格"**（`!isWater(foot.above())`）—— 假人靠按住跳跃浮着、
   潜不下去 ⇒ 合法位置集只放执行器真到得了的格子（K-4 可规划即可执行）。
2. **浮着段的完成口径**从 `PILLAR` 水柱（D-244）推广到 `TRAVERSE`/`DESCEND`/`ASCEND`：目的格与下面都是水时
   "脚位到格即成功"（水里 `onGround` 永远为假）。⚠️ **蹚水（下面实心）不能套**（会假完成 + 平白 STALE，D-248 已实测）。
3. 唯一化：`MovementHelper.isFloatingDestination` / `isAtFootCell` / `shouldHoldJumpInWater`（后者对照
   Baritone `MovementTraverse:243-248`：脚位低于目的格 ⇒ `Input.JUMP`）。

**过程中查出的两个真 bug（都不是"水位能力"问题，是"水里没有落地"这件事没被建模）**：
- **B1 段间 settle 把浮着松开**：`PathSession` 段间 settle 每 tick `stopMovement()`（**清跳跃输入**）⇒ 水里等于松手
  ⇒ bot 下沉、并被"挖开侧壁后流过来的水"推走。实测（切片 B 的灌水坑路线）：段末浮着在 `340,100,306`，
  10 tick settle 内先被推到 `341,100,306`、再沉到 `341,99,306` ⇒ 下一段 `STALE_START` ⇒ 逃生 FAILED。
  **这就是 D-248 回退现场的真因**（当时误记为"未解释的 Air"⇒ D-249 查明是自家破坏，D-251 才补上"为什么它会掉下去"）。
  **修**：settle 里若下一段起点是浮着水面格 ⇒ 零水平输入 + 按需按住跳跃 + 到格即提前结束 settle。
- **B2 水里的失败被静默吞掉**：`PathSession.handleFailure` 有"空中不处理、等落地再判定"的守卫，而**水里
  `onGround()` 永远为假** ⇒ 水里的段失败**既不报错也不前进**，会话空转到 bot 沉底踩到地面才报 STALE
  （实测深水池空转 ~57 tick，期间**没有任何人按跳**）。**修**：判据改成 `!onGround && !inWater` 才等落地。
- 附带：等起步时若下一段起点是浮着水面格而 bot 还在水下 ⇒ `floatUpToSegmentStart()` 按住跳跃把它浮上来
  （settle 只有 10 tick，浮 2~3 格需要更多）。

**实测（2026-09-16）**：
| 判据 | 结果 |
|---|---|
| `deep_pond_course`（3 格深、4 格宽、**纯通行**、两侧到顶石墙 ⇒ 绕不过去） | 从"现状登记 `refused`"改成**执行判据** ⇒ **`COMPLETED`**（`replans=1`；水面格 12/11 tick vs 陆地 5 tick ⇒ 水/陆节奏不同但都能走）|
| 灌水竖坑逃生（`single:survival_exit` / CORE） | 终态 **DONE**：从水里出来、**水里零放置**、整段放置 0（切片 B 落地后 CORE 上下文里换走"破东侧壁 → 升进水面格 → 升上地板"这条路，三条都自洽）|
| 计划自洽守卫（D-250） | CORE 里按预期开火：`[SelfWrite] … 禁掉那条边重搜` → 重搜后计划自洽；CORE 摘要 `自写入冲突=3`、`selfwrite_unresolved=0` |
| 门槛 | `single:pathing` PASS / `single:survival_exit` PASS / **CORE `(38/38) ticks=4229 → PASS`**；`check-all.sh` 9 PASS + 1 预期 WARN |

**路线形状不再写死**：同一场景里"水柱 `PILLAR`"（D-244）与"破开侧壁 + 升到水面格"两条路**都自洽**，
选哪条由成本决定（CORE 上下文里挖掘那条更便宜）⇒ 夹具里那条"第一段必须是 PILLAR"的**写死路线**已改成
"**计划自洽 + 从坑底起步**"；需求仍是"从水里出来且不靠放置"（端到端 + 省料判据）。

**边界（仍未做；夹具里断言的是今天的事实）**：① **池底出发**（`deep_pond_course+floor`）仍 `UNREACHABLE`
—— 规划期还没有"从池底浮上去"那种边；② 入水那一步靠"落下去 → 浮着段完成 → 浮回来"这套（一次 `replans=1`），
没有专做的"入水物理"；③ 水平潜游（水面以下）仍不支持；④ **真人客户端复核未做**（见下）。

**⚠️ 收口纪律（用户 2026-09-16 明确）**：本次到"**需要用户检查**"为止 —— 溺水逃生闭环**不算完成**，
等用户在固定客户端上做一次零参数复核（`alice:survival_full_check` 右键 ⇒ `checks=122 failures=0`；
`alice:pathing_regression` 右键 ⇒ 深水池场景 PASS），并按用户观察判定是否 `USER_ACCEPTED`。
**不得凭服务端日志宣称客户端可用**（`SERVER_TESTED` ≠ `WINDOWS_CLIENT`）。

### D-252：客户端 `pathing_regression` 失败 —— **是场景数据包陈旧**，不是 mod 回归（2026-09-16；两个夹具同步焊死）

**用户实测**：客户端 `alice:pathing_regression` 右键 ⇒ FAIL。日志（`<client>/logs/latest.log` 19:45-19:46）：
```
scene=water_course       result=FAIL detail=MOVEMENT_FAILED/route=-/sceneTicks=1/MISSING=[TRAVERSE]
scene=deep_pond_course   result=FAIL detail=MOVEMENT_FAILED/route=-/sceneTicks=1/MISSING=[TRAVERSE]
scene=water_course+cost  result=FAIL detail=UNREACHABLE/…/cost=Infinity/expected=0.00
其余全部 PASS（含 coverage=PASS、executed=… 10 类齐全）
```
失败的三条**恰好是只用"新场景地形函数"的那三条**（`water_course_terrain` / `deep_pond_course_terrain`）。

**根因（测试夹具的同步缺口，与 mod 无关）**：客户端存档里的那份数据包
`saves/新的世界/datapacks/alice_test/` 是 **09-15 的手工拷贝**，**缺 D-247/D-248 新增的两个场景函数**
（仓库里 111 个 `.mcfunction`，客户端那份没有这两个）⇒ 夹具的
`/function alice_test:water_course_terrain` **一条命令都没跑** ⇒ 场景**根本没有水沟/水池**，拿"没有地形"的世界去规划
⇒ `UNREACHABLE` ⇒ FAIL。**无头电池每轮 `cp -r tools/test-scenes/alice_test` 刷新数据包**（`headless-battery.sh:137-138`）
⇒ 服务端全绿；客户端那份**没有任何工具刷新**，而且夹具用了 `withSuppressedOutput()` 的命令源，
`/function` 失败**一字不打** ⇒ 表面上看像"mod 回归"。（⇒ 这也是为什么 D-247/D-248 的 `water_course` 在客户端
**从来没真正跑过**：它一诞生，客户端数据包就已经缺它了。）

**两条修法（都已落地）**：
1. **夹具：地形前提必须响亮失败**（不再静默）：
   - `PathingRegressionTask.prepare`：读 `/function …_terrain` 的**返回值**，`<= 0` ⇒ 该场景记
     `PREMISE_FAILED=TERRAIN_NOT_BUILT(cmd=0)`；并加"**起点可站**"（`canStandCentered`）前提 ⇒ 地形没建出来/建错都会红；
   - `SurvivalExitCheckTask` 的 `alice_test:survival_sealed_course` 同样看返回值（封闭/溺水判据依赖那套几何）。
   ⇒ 判据数 `checks 120 → 122`（另 1 条来自 D-251 把"第一段必须是 PILLAR"改成"计划自洽 + 从坑底起步"两条）。
2. **工具：数据包刷新挂到既有同步命令上**（AGENTS.md：新验证手段必须挂在已有命令上）：
   `tools/sync-windows-artifact.sh` 新增第 4 参数 / `ALICE_CLIENT_WORLD`（不给则按 `mods/` 的兄弟目录 `saves/*/datapacks/alice_test`
   自动探测，唯一才用）⇒ 备份旧的 `alice_test.bak.<时间戳>`（保留 `ALICE_BACKUP_KEEP`，默认 2）后整体刷成仓库版本，
   并**校验 `.mcfunction` 份数一致**（111=111），最后提示"游戏内 `/reload`（或重进存档）后再跑测试物品"。
   本次已执行：客户端数据包 105 → **111** 个函数，`water_course_terrain` / `deep_pond_course_terrain` 已就位。

**边界**：**这不能证明 mod 侧的深水能力在客户端可用** —— 数据包刷新后要**用户重跑一次**才算数
（`SERVER_TESTED` ≠ `WINDOWS_CLIENT`）。另外**数据包是存档级资源**：游戏已加载存档时改文件不会自动生效，
必须 `/reload` 或重进存档（脚本已打印这条提示）。

### D-253：水位 epic **收口** —— 真人复核通过，升级 `WINDOWS_CLIENT` + `USER_ACCEPTED`（2026-09-16）

**用户复核**（原话：「跑完了，符合我的预期」）。**AI 自己核过的日志证据**（`<client>/logs/latest.log`，20:01–20:03）：
```
20:01:54  [Survival] SUMMARY checks=122 failures=0 [] → PASS
20:03:11  [Regression] SUMMARY pathing_course=PASS … deep_pond_course=PASS …            （0 条 FAIL）
20:03:11  task_execution_terminal kind=PathingRegressionTask … terminal=COMPLETED
          scene=deep_pond_course result=PASS detail=COMPLETED/replans=1/route=TRAVERSE,DIAGONAL,ASCEND,DESCEND
          scene=water_course     result=PASS detail=COMPLETED/route=TRAVERSE/sceneTicks=112
          scene=water_course+cost result=PASS detail=REACHED/…/cost=17.50/expected=17.50
PREMISE_FAILED/TERRAIN_NOT_BUILT 命中数 = 0（D-252 新增的两条前提在客户端真的跑到了地形）
```

**升级的验证等级**（覆盖 `docs/AI_TEST_MATRIX.md` 的 D-241…D-252 行）：
- `WINDOWS_CLIENT` + `USER_ACCEPTED`：**D-241**（逃生准备金）、**D-242**（水里那档的诚实结论）、**D-243**（水里垂直移动）、
  **D-244**（水柱省料）、**D-245**（不自动回收）、**D-247**（蹚水 + 水速成本）、**D-250**（计划自洽守卫：夹具那条
  「计划自洽」判据在客户端 0 失败）、**D-251**（切片 B）、**D-252**（地形前提 + 数据包刷新）。
- 仍只有 `SERVER_TESTED`：**D-246**（判定"不建机制"——无运行时判据可言）、**D-249**（只读调查结论）。

**水位 epic 关闭**（`docs/OPEN_ITEMS_LEDGER.md` §5.11 的 IN 四条全部达成）：
① 蹚水 ✅ ② 水柱上浮/出水零放置 ✅ ③ 深水浮着（能规划 + 能执行跨段）✅ ④ 逃生准备金 ✅；真人复核 ✅。
**明确不做（OUT）**：游泳 Movement（Baritone 也没有）· 落水免伤（D-058）· 水柱成本模型（只影响选路）· 水面专用理由码。
**仍未做（如实留档，不再是"待办"而是"已知边界"）**：① **池底出发**（`deep_pond_course+floor`）`UNREACHABLE`
—— 规划期没有"从池底浮上去"那种边；② **入水物理**专做（今天靠"落下去 → 浮着段完成 → 浮回来"，`replans=1`）；
③ **水面以下水平潜游**。
**过程**：按用户 2026-09-16 的裁定，**同时只保留一个活跃 epic** ⇒ 下一步只挑一件（见 HANDOVER §1 的候选）。
