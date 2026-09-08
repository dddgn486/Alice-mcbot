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
- 下一步：R2-D 世界修改 Movement / R3 PathSession / Git 提交，待用户选择。

## D-014：执行器不能用单一通行检查覆盖所有 Movement

- 状态：稳定
- `PathExecutor` 必须执行当前 Movement 的专用前置条件和后置条件，并报告动态阻挡；不能用统一的“段目标当前必须可通行”检查误伤需要先破坏障碍的 Movement，也不能因此把 `BLOCKED` 偷换为 `UNREACHABLE`。
- 影响：这是未来修复 `PathExecutor`/`BreakAndWalkMovement` 契约的方向，不是本次文档更新的代码授权。
