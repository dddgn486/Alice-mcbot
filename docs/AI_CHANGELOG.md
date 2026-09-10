# Alice AI 开发变更记录

## 2026-09-07：清理过期 Movement 测试入口

- 用户反馈：测试物品和探针残留过多，影响客户端测试辨识；此前 R2-B 测试还受到旧工件影响。
- 清理：移除旧 Movement 实验 2-6、M1/M2、R2-B Traverse 诊断测试物品及其任务/Fixture/超时探针；从 `AliceItems`、`BotManager`、语言资源和 item model 中移除对应入口。
- 保留：正式工具、MineTask A/B/C 场景入口、`pathing.core` 纯契约、历史文档和历史证据。
- 当前原则：测试入口重新设计后再添加；不把历史验收物品继续带入新 Pathing Core。

## 2026-09-07：Windows 开发端改为无 Git 源码镜像

- 用户决定：Windows 开发端只作为可阅读、可编译的源码镜像；Git 管理、提交和分支操作全部在 WSL `/home/fb486/projects/alice` 完成。
- 操作：先完整备份 Windows 工作区到 `/mnt/d/JAVA_projects/alice-backup-20260907-175435/alice`，确认 `.git` 备份存在后删除 `/mnt/d/JAVA_projects/alice/.git`。
- 新增：`tools/mirror-windows-workspace.sh`；每次镜像前备份 Windows 工作区，随后强制同步源码/文档/资源，排除 `.git`、`.gradle`、`.dsh-runtime`、`build`、`run`、`videos`。
- 保留：运行实例 `mods` 不纳入默认镜像，只在需要自搭建场景或客户端测试时单独更新。
- 测试事实：R2-B 入口能选定目标，但 Bot 完全不动；下一步先调查输入驱动/任务 tick/工件加载链，不进入 R2-C。

## 2026-09-07：固定 Windows 工件同步流程

- 原因：对 `/mnt` 做递归 glob 会扫描整个 Windows 挂载盘并超时；Linux 与 Windows 工作区提交线也已分叉，不能继续依赖手动源码复制或盲目 `git push origin`。
- 新增：`tools/sync-windows-artifact.sh`，默认只同步构建 JAR 到 `/mnt/d/JAVA_projects/alice/build/libs/`，覆盖前自动备份并核对 SHA-256；运行实例 `mods` 目录必须显式传参。
- 验证：`./gradlew build --no-daemon` 通过；本轮工件已同步，SHA-256=`cb42826ba99ee09b2e12e4c163a0e9657361057a183479e6fa1925657e7cc225`。
- 当前限制：已确认 Windows 开发工作区同步，尚未确认实际客户端运行实例 `mods` 目录是否加载该工件。

## 2026-09-07：Pathing Core 架构重规划

- 决策：暂停真实 MineTask Movement 接入，D-019 标记为已被 D-020 替代。
- 新增：`docs/ALICE_PATHING_CORE_ARCHITECTURE.md` 与 `docs/ALICE_PATHING_CORE_R1_CONTRACT.md`。
- 明确：Domain Action 与 Transit Movement 分离；为通行而挖/放不等同于领域挖/放。
- 明确：生存兜底、可回收性提前避险、禁区和保护区为不同策略层。
- 保留：Legacy MineTask/A/B/C、旧 `PathExecutor`、M0/M1/M2 独立实验与证据。
- 历史化：`MINETASK_MOVEMENT_MVP_DESIGN.md` 与 `MOVEMENT_SYSTEM_ARCHITECTURE.md` 不再作为当前实施授权。
- 验证：本轮仅完成文档整理，未修改生产代码，未新增客户端工件。
- 下一步：R1 契约评审，通过后设计 R2 Movement 类型与能力声明。

## 2026-09-06：调整 Alice 协作唤醒规则

- 用户明确要求：默认不创建或启用 active goal；需要客户端操作、用户确认或外部证据时必须暂停，等待用户主动唤醒。
- 已将规则写入 `docs/AI_DEVELOPMENT_PLAYBOOK.md` 的会话流程和 `docs/AI_PROJECT_STATE.md` 当前状态。
- active goal 仅在用户明确授权持续自主推进时使用；创建前说明范围、轮数上限和停止条件。

## 2026-09-06：实施 M2 BotMiner Movement 后端

- 新增显式 `BotMiner(ServerPlayer, MiningPlan, MovementPlan)` 入口，使用 `MovementPathExecutor` 执行一次移动；失败不回退旧 `PathExecutor`，不自动重规划。
- 新增 `alice:movement_plan_mining_tester` 与 `MovementPlanMiningTask`：复用 `MiningPlanner` 选择挖掘站位，编译同高度四向路径，确认移动完成和实际脚位后才进入既有 BotMiner 挖掘流程。
- 未修改 MineTask 真实 `MOVING_TO_POSITION`、目标访问清障、恢复预算、旧 `PathExecutor` 或多 Bot 调度。
- 验证：`./gradlew build --no-daemon` 通过；Windows 工件 SHA-256 为 `0972321f3fb7da423846ec987c0dc5b14ec191221468d54eaa337631586030d2`；首次受控场景暴露通用 Surface 路径与 M1 相邻契约冲突，随后改为轴对齐逐格路径。
- Windows 用户验收：21:09:40-21:09:42 日志确认 5 段 Movement 从 `-5,64,-5` 到 `-4,64,-1` 完成，实际脚位一致，随后开始并完成挖掘，任务终态 `COMPLETED`；最终工件 SHA-256 为 `deaf45944fcb9270eb62186c357bf88c312ec417addb2bd9a10a0a092faf946d`。

## 2026-09-06：修复 M1 计划构造器校验崩溃

- Windows 日志确认 `MovementPlanWalkTask` 已生成路径，但 `MovementPlan` 构造器返回 `PLAN_CONTRACT_INVALID`，原因是紧凑构造器校验方法读取尚未完成赋值的 `projectedFootPath` 字段。
- 最小修复：校验方法改为显式接收构造器参数，不改变原有不变式。
- 验证：`./gradlew build --no-daemon` 通过；工件已同步 Windows，SHA-256 为 `7e5679dc1ff93f83e607d347aeb8e10f8912f160c5b38b4fe659066fddb102a4`。
- 下一步：重新执行独立 `alice:movement_plan_tester`，确认 `compiled`、`completed` 和实际脚位。

## 2026-09-06：修复实验 3 终态日志崩溃

- 运行期证据：实验 3 的第 3/3 段已完成，但 `MovementSequenceWalkTask.tick()` 在终态日志访问未初始化的 `movements` 字段，触发 `NullPointerException` 并导致集成服务器崩溃。
- 根因已确认：恢复历史入口时使用了同名局部变量遮蔽任务字段。
- 最小修复：改为给任务字段赋值；不改变 Movement 契约、M1 编译器或 MineTask 边界。
- Windows 日志：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10\logs\latest.log`，时间 `2026-09-06 17:04:16`。
- Windows 回归结果：实验 3 连续三次 `COMPLETED`，实际脚位均与目标一致，未出现新的异常；旧崩溃报告未新增。
- 下一步：确认并测试独立 M1 入口 `alice:movement_plan_tester`。

## 2026-09-06：修正 M1 客户端验收入口

- 证据冲突：实验 3 原入口生成非相邻段，但客户端日志仍显示成功，不能作为 M1 编译器验收证据。
- 修正：恢复 `MovementSequenceWalkTask` 的历史三段实验行为；新增独立 `MovementPlanWalkTask` 与 `alice:movement_plan_tester`，使用直线四向相邻脚位路径消费 `MovementPlanCompiler`。
- 验证入口预期：无遮挡平面目标应记录 M1 编译接受、相邻 Movement 序列和实际脚位完成；非相邻/高差/障碍由编译器显式拒绝。
- 保持：不放宽 M1 契约，不接入 MineTask，不修改旧 `PathExecutor`，不实施多 Bot 调度。
- 验证：`./gradlew build --no-daemon` 通过；M1 新入口待 Windows 客户端验证。

## 2026-09-06：MineTask Movement MVP M1

- 新增 `pathing/MovementPlanCompiler`，将包含起点和终点的脚位路径编译为 `MovementPlan` + `WalkMovement` 序列。
- M1 仅接受同高度、四向相邻且通过 `MovementHelper.canTraverse` 与 `WalkMovement` 校验的段；空路径、起点不一致、非相邻、非同高度或前置条件失败均显式拒绝。
- 实验 3 的独立连续 WalkMovement 入口改为消费该编译器，仍不接入 MineTask。
- 保持：未修改旧 `PathExecutor`、MineTask 调度、多 Bot 调度或世界修改 Movement。
- 验证：`./gradlew compileJava --no-daemon` 与 `./gradlew build --no-daemon` 均通过；`git diff --check` 通过。
- 下一步：先进行实验 3 客户端回归；用户确认后进入 M2，评估 BotMiner 单次 Movement 后端适配。


## 2026-09-06：MineTask Movement MVP M0

- 目标：建立 Movement 规范执行模型的不可变规划快照，不接入 MineTask 调度。
- 新增：`pathing/MovementPlan`，明确 `startFoot`、`goalFoot`、`List<Movement>`、脚位投影路径、搜索状态、成本和规划器名称。
- 校验：坐标统一按脚位解释；投影路径必须从起点到终点；已到达计划不得携带 Movement；已到达且非同脚位路线必须有 Movement；Movement 首尾和相邻段必须连接。
- 保持：未修改 MineTask、BotMiner、PathExecutor、MovementPathExecutor、客户端行为或多 Bot 调度；未删除旧路径模型。
- 验证：`./gradlew compileJava --no-daemon` 通过；状态更新见 `docs/archive/legacy-2026-08/MINETASK_MOVEMENT_MVP_DESIGN.md`。
- 下一步：用户确认后进入 M1，仅支持已验证同高度 `WalkMovement` 的独立编译/验证入口。


## 2026-09-06：性能回归验证收口

- 用户完成本轮性能验证，未复现“平均 FPS 很高但 low 帧很低/实际体感卡顿”的现象。
- 结论：当前没有可确认的性能问题；本轮仅作为性能回归验证，不继续深入客户端帧时间、TPS/MSPT、同步或源码热点。
- 改动：仅更新状态、测试矩阵和本变更记录；未修改性能相关代码。


## 2026-09-06：Movement 实验 6B/6C 验收

- 6B：新增无世界修改的 `DiagnosticTimeoutMovement` 对照入口；多次客户端运行均无动态方块证据，结构化结果为 `MOVEMENT_TIMEOUT`，阶段 `EXECUTING_MOVEMENT`，`noProgressTicks=101`，并清理 Controller 输入。
- 6C：新增场景 F 和 `movement_precondition_tester`；Bot 固定到 `(0,64,60)`，头部 `(0,65,60)` 放置石块后立即检查，任务约 1 tick 终止，结构化结果为 `INVALID_PRECONDITION`、阶段 `PRECONDITION`，未进入 Movement 执行或超时。
- 用户客户端观察：截图中 Bot 视觉上位于头部障碍处；这是夹具放障位置造成的可见现象，外层同时出现 `SURVIVAL_INTERRUPTED/failed:survival_suffocating`，不改变任务自身失败事实。
- 验证：`COMPILES`、`build`、PCL 工件同步、Windows 客户端与用户验收；6C 证据见 `.alice-supervision/client-tests/movement-physics-experiment-6c-20260906/evidence/`。
- 工件 SHA-256：`759ffcaba21f99a987ad87494d512edce11742abab5b849891071427cf7f1cd2`。
- 结论：6A/6B/6C 三组结果契约闭合；未接入 MineTask，未改变多 Bot 调度。

## 2026-09-06：实施 Movement 实验 6A 动态阻挡分类

- 独立场景：新增 `alice_test:movement_e` / `alice_test:movement_e_reset`，E 使用 `(0,64,40) -> (10,64,40)`，中央动态屏障位于 x=5。
- 独立入口：新增 `MovementBlockReasonFixture`、`MovementBlockReasonTester` 和 `alice:movement_block_reason_tester`，不再复用实验 5 的 D 命名。

- 目标：在明确执行期方块碰撞证据下区分 `BLOCKED_DYNAMIC`，不把普通无进展直接改名。
- 改动：`MovementPathExecutor` 保存初始碰撞基线；超时时检查新出现且与 Bot 当前碰撞箱相交的方块碰撞体。
- 结果：有证据时失败码为 `movement_blocked_dynamic_WalkMovement`，否则保留 `movement_timeout_WalkMovement`。
- 结构化报告：`MovementDynamicBlockTask.failureReport()` 输出分类、执行阶段、障碍坐标和 `noProgressTicks`。
- 多 Bot 预留：未改变调度；继续使用现有按 Bot UUID 的 `BotSession`，未改全局目标广播或引入并行调度。
- 验证：`./gradlew compileJava --no-daemon`、`./gradlew build --no-daemon` 通过；工件已同步，SHA-256 为 `34c4a18593329cbe96583150f39d3bb52cd6a15b06c940080ed68d44fdce79e4`。
- 回归结果：用户完成场景 E 客户端测试并提供截图；服务端报告 `movement_blocked_dynamic_WalkMovement`，结构化 `failureCode=BLOCKED_DYNAMIC`、`failurePhase=EXECUTING_MOVEMENT`、`obstacle=5,65,40`、`noProgressTicks=101`，Bot 实际脚位 `4,64,40`、`onGround=true`，并清理 Controller 输入。
- 状态：6A 达到 `USER_ACCEPTED`；`MOVEMENT_TIMEOUT` 与 `INVALID_PRECONDITION` 契约保留，6B/6C 尚未实现或测试。

## 2026-09-06：冻结 Movement 实验 6 结果契约

- 目标：先固定事实型结果语义，再决定执行器实现，不把无进展超时直接改名。
- 冻结结果：`BLOCKED_DYNAMIC`、`MOVEMENT_TIMEOUT`、`INVALID_PRECONDITION`。
- `BLOCKED_DYNAMIC` 门槛：已进入执行、启动时可通行、执行中服务端确认行走方向出现新的方块碰撞体、目标未到达且连续窗口无有效位移。
- `MOVEMENT_TIMEOUT`：超过无进展/执行预算但没有充分动态方块证据时的保守兜底。
- `INVALID_PRECONDITION`：执行前起点/目标脚位/支撑/空间/通行契约不满足。
- 共同要求：记录终点、阶段、稳定失败码和证据字段；所有失败都清理 Controller；不自动挖障碍、绕行、重规划或传送。
- 排除：第一版不分类实体推挤、流体、复杂特殊碰撞体和未知模组方块。
- 状态：`CONTRACT_FROZEN`；尚未实施实验 6。

## 2026-09-06：MineTask 第一阶段内部契约审查

- 范围：只读梳理 MineTask 状态机、MiningPlan 生命周期、BotMiner/PathExecutor、目标访问清障、DropCollectionTask 和终态链路
- 关键事实：`MOVING_TO_POSITION` 当前是占位阶段，下一 tick 直接创建 BotMiner；正式 MineTask 使用 `SurfacePathfinder.Result + List<BlockPos> + PathExecutor`
- 寻路盘点：项目同时存在 `PathPlanner.PathResult + MovementPathExecutor` 平行模型；AStarPathfinder 的正式搜索已使用 Movement Provider 枚举邻居，但正式执行仍消费 BlockPos 序列
- 风险：路径状态名称、输出对象、配置预算和旧 ServerLevel 入口存在语义分叉；不能仅凭注释中的“统一/推荐”接入 MineTask
- 验证：代码审查完成；未修改寻路或任务行为；A/B/C 边界保持不变
- 报告：`docs/archive/legacy-2026-08/MINETASK_INTERNAL_CONTRACT_AUDIT.md`
- 下一步：与用户严肃讨论正式寻路契约和两套模型的取舍，再决定最小可验证收敛方案

## 2026-09-06：移动物理调用链与模式语义纠正

- 用户澄清：Forge `ServerPlayer.tick()` 不按 Fabric 假设自动完成 Bot 所需的 `travel`/AI 物理推进；当前 `BotPlayer` 显式执行 `aiStep()`，任务侧 `BasicMovement` 仍显式调用 `travel()`
- 修正审查：不能把“实体 tick 不自动 travel”和“任务侧 travel 可能重复推进”混为一谈；两者分别审计
- 新事实：当前移动入口至少包括 `PathExecutor + BasicMovement.travel`、`MovementPathExecutor + Movement`、`BotController` 输入、`BotPlayer.aiStep`、直接 `setPos/setDeltaMovement` 旁路和世界修改型 Movement
- 模式结论：`HARD_PATH` 与 `SOFT_SURFACE` 均不能按名称理解实现；当前 `PathExecutor` 不是纯 setPos 瞬移，`SOFT_SURFACE` 也没有完整真实行走闭环证据
- 文档：扩展 `docs/archive/legacy-2026-08/MINETASK_INTERNAL_CONTRACT_AUDIT.md`，新增 D-015/D-016；未修改运行代码
- 下一步：先做 Movement 纯行走和 Forge Bot 单 tick 物理调用链的最小审计，再讨论正式命名、执行后端和 MineTask 解耦

## 2026-09-06：准备 Movement 物理实验 1

- 准备内容：设计 BotController 单段输入驱动物理实验，区分输入驱动、停止和任务侧显式 travel 对照组
- 实验边界：不接 MineTask、BotMiner、PathExecutor、MovementPathExecutor、PathPlanner 或世界修改型 Movement
- 重点数据：每 tick 输入、位置、速度、支撑、onGround，并尽可能记录 travel/aiStep 调用计数
- 目标：确认 Forge Bot 的物理推进调用链和真实行走基础，不提前宣布 SOFT_SURFACE 完成
- 文档：`docs/archive/legacy-2026-08/MOVEMENT_PHYSICS_EXPERIMENT_1.md`
- 状态：平地前进/停止已获用户验收；台阶、落差、动态阻挡和 Movement 序列待后续实验

## 2026-09-06：实施 Movement 实验 2

- 新增：`MovementWalkTask`、`MovementWalkTester`、`alice:movement_walk_tester`
- 链路：单个 `WalkMovement -> MovementPathExecutor -> BotController -> BotPlayer.aiStep`
- 约束：不调用旧 `PathExecutor`，不接 MineTask，不接世界修改型 Movement
- 行为：WalkMovement 只提交朝向和前进输入；执行器终态清除输入；成功要求实际站在目标脚位
- 验证：`./gradlew compileJava --no-daemon` 和 `./gradlew build --no-daemon` 通过
- 工件：已同步 PCL，SHA-256 为 `8ba593072aab94929e58ebcd9261c876402ba65cf56e1bc674de9ab2a118d182`
- 状态：已获用户验收；连续三次单个 WalkMovement 正常完成，复杂地形和序列待后续实验

## 2026-09-06：设计并实现 Movement 实验 5 场景 D

- 新场景：`alice_test:movement_d` / `alice_test:movement_d_reset`
- 坐标：Bot 起点 `(0,64,20)`，目标脚位 `(10,64,20)`，动态障碍 `(5,64,20)`
- 新入口：`alice:movement_dynamic_block_tester`
- 新实现：`MovementDynamicBlockFixture`、`MovementDynamicBlockTask`
- 边界：独立于 MineTask 场景 C；不调用 `MiningReplanFixture`、旧 `PathExecutor`、自动清障或自动重规划
- 验证：`./gradlew compileJava --no-daemon` 和 `./gradlew build --no-daemon` 通过
- 工件：已同步 PCL，SHA-256 为 `59d180504e7a4237f8920454cf43e1451a401154bbbd1daf84516d0429eb1404`
- 状态：首次 Windows 客户端验证通过动态放障和最终到达；因走廊可绕行，`BLOCKED_DYNAMIC`/超时失败语义待窄走廊或封闭屏障对照

## 2026-09-06：实验 5 封闭屏障暴露超时清理缺口

- 事实：封闭横向石墙生效，Bot 停在 `actualFoot=4,64,20`，`onGround=true`。
- 失败语义：执行器报告 `FAILED/movement_timeout_WalkMovement`，尚未定义 `BLOCKED_DYNAMIC`。
- 根因：`MovementPathExecutor` 的 no-progress 超时分支直接返回 `FAILED`，未调用 `controller.stopMovement()`。
- 修复：超时分支补充统一 Controller 输入清理，并记录 `cleanup=controller_stop_movement`；保留原始超时失败码。
- 验证：compileJava/build 通过，工件已同步；SHA-256 为 `42dde3c4f6a80041f2fe60910cf17b01294c3020e168f80305e717372200d516`。
- 回归结果：用户完成修复版客户端测试并提供最终截图；服务端日志出现 `controller_stop_movement` 和 `cleanup=controller_stop_movement`，Bot 停在 `actualFoot=4,64,20`、`onGround=true`，终态为 `FAILED/movement_timeout_WalkMovement`。
- 结论：动态阻挡、事实型超时失败、输入清理达到 `USER_ACCEPTED`；不将超时冒充 `BLOCKED_DYNAMIC`，独立分类码留作后续执行器设计。

## 2026-09-06：Movement 实验 4 验收

- 结果：用户确认一级上台阶和一级下降均无异常。
- 服务端：上升 `heightDiff=1`，Y `-60 -> -59`；下降 `heightDiff=-1`，Y `-59 -> -60`；两次 `actualFoot == goalFoot`、`onGround=true`、`COMPLETED/done`。
- 边界：不自动跳跃、不改世界、不接 MineTask 或旧 PathExecutor。
- 诊断瑕疵：下降入口方向字符串误打印为 `ASCEND`，但高度差和实际 Y 已准确记录。
- 证据：`.alice-supervision/client-tests/movement-physics-experiment-4-20260906/evidence/`
- 结论：一级上升/下降达到 `USER_ACCEPTED`。

## 2026-09-06：实施 Movement 实验 4

- 新增：`MovementVerticalWalkTask`、`MovementVerticalWalkTester`、`alice:movement_vertical_walk_tester`
- 操作：普通右键测试上一级，Shift+右键测试下降一级
- 观测：记录目标/实际脚位、Y、支撑方块和 `onGround`
- 边界：不自动跳跃、不改世界、不接 MineTask 或旧 PathExecutor
- 验证：`./gradlew compileJava --no-daemon` 和 `./gradlew build --no-daemon` 通过
- 工件：已同步 PCL，SHA-256 为 `b1c332d474ee1a99e40e7e295dc6cf2902c2577a66e016c6cfe1e78ee095cf55`
- 状态：待 Windows 客户端验证

## 2026-09-06：Movement 实验 3 验收

- 结果：Windows 用户确认平地连续行走无异常；服务端至少五次三段序列均按 `1/3 -> 2/3 -> 3/3` 完成。
- 事实：每次 `actualFoot == goalFoot`，终态 `COMPLETED/done`，`recoveryStage=NONE`，`recoveryEvents=[]`，输入已清理。
- 边界：跳跃/高差目标按 `movement_sequence_invalid` 拒绝，符合当前实验仅覆盖同高度纯行走的设计。
- 证据：`.alice-supervision/client-tests/movement-physics-experiment-3-20260906/evidence/`
- 结论：多个纯 WalkMovement 的段间切换、最终到达和终态清理达到 `USER_ACCEPTED`。

## 2026-09-06：事实型统一任务结果契约

- 目标：建立跨任务可读取的终态事实快照，不提前设计决策层失败策略
- 改动：新增 `TaskOutcome`、`TaskFailureReport`；`Task.failureReport()` 提供默认入口；MineTask 输出阶段、失败码、计划失效和恢复事件；TaskExecutionRecord 保存统一 outcome
- 保持：未加入 `retryable`、`recommendedAction`、自动换目标或自动重试；未改变 MineTask/BotMiner 行为
- 验证：`./gradlew compileJava --no-daemon` 和 `./gradlew build --no-daemon` 均通过
- 工件：已同步 PCL，SHA-256 为 `b2882abea7f5cd1e11cd593070c14beb8d56bfe412f8fd612f815abb7a8171bb`
- 编译修正：首次编译发现 `BotManager.recordTerminal` 兼容重载漏传失败报告，已补齐后重新编译通过
- 未完成：尚无专门失败场景验证 `TaskOutcome.failure` 的实际终态内容
- 下一步：先积累其他任务的事实型失败样本，再讨论跨任务 failureClass 和决策层策略

## 2026-09-06：初始不可执行计划安全失败

- 现象：Bot 不可达目标的测试中，初始计划已经是 `UNREACHABLE/executable=false`，MineTask 仍把计划交给 BotMiner，并无意义地执行一次任务级重规划
- 修正：MineTask 在初始计划阶段拦截 `UNREACHABLE` 和 `SEARCH_LIMIT`；分别报告 `no_safe_execution_path`、`stand_search_limit`；不再交给 BotMiner 或进入任务级重规划
- 保持：`REACHED` 但运行时视线阻挡仍走既有有限目标访问清障；不改变 `HARD_PATH`、隧道边界和恢复预算
- 可观察性：终态日志直接输出 `failureCode`、`failurePhase`、`failureDetails`
- 验证：`./gradlew compileJava --no-daemon` 通过；完整构建和客户端定向回归待执行
- 下一步：构建同步后重测一个不可达目标，并回归场景 B，确认分别得到安全失败和目标访问清障

## 2026-09-06：不可达目标安全失败客户端验收

- 用户场景：布置 Bot 无法寻路到达的目标并完成测试
- 结果：初始 `UNREACHABLE/executable=false` 在 `EVALUATING` 阶段直接 `FAILED/no_safe_execution_path`
- 恢复：`recoveryAttempts=0`、`recoveryEvents=[ESCALATED_FAILURE]`；没有 BotMiner 执行和任务级重规划
- 结构化失败事实：`failureCode=no_safe_execution_path`、`failurePhase=EVALUATING`
- 回归：场景 B 同轮仍完成目标访问清障，事件序列为 `[BOTMINER_PATH_RETRY, TARGET_ACCESS_CLEAR]`
- 证据：`.alice-supervision/client-tests/minetask-unreachable-20260906/evidence/`
- 结论：初始不可达安全失败和场景 B 回归达到 `USER_ACCEPTED`

## 2026-09-05：MineTask 恢复事件序列修正

- 目标：解决场景 B 同时发生 BotMiner 内部重试和 MineTask 目标访问清障时，单一 `recoveryStage` 丢失事件的问题
- 改动：新增不可变 `recoveryEvents` 列表；BotMiner 和 MineTask 按发生顺序写入事件；终态日志同时输出最高层级和事件序列
- 保持：不改变任何恢复预算、路径规则、清障行为和 A/B/C 场景
- 验证：`./gradlew compileJava --no-daemon` 通过；完整构建待同步前执行
- 下一步：同步新工件后至少重跑场景 B，确认重复 BotMiner 实例事件被去重为 `[BOTMINER_PATH_RETRY, TARGET_ACCESS_CLEAR]`

## 2026-09-06：恢复事件去重修正

- 现象：场景 B 行为通过，但 `recoveryEvents` 因清障前后创建多个 BotMiner 实例而出现重复 `BOTMINER_PATH_RETRY`
- 修正：MineTask 事件合并和自身事件写入均按首次出现顺序去重
- 保持：BotMiner/MineTask 实际恢复次数、路径规则和任务行为不变
- 验证：`./gradlew build --no-daemon` 通过；新工件已同步 PCL
- 新工件 SHA-256：`cbc5b8b120f91aea0ab1960acafb6b6e7179adaab46e379fecddc2fa05fa10b8`
- 下一步：用户重跑场景 B，确认终态事件序列；A/C 可随后用同一工件抽查

## 2026-09-06：恢复事件序列客户端复核通过

- 场景 A：`recoveryStage=NONE`，`recoveryEvents=[]`，终态 `COMPLETED`
- 场景 B：`recoveryStage=BOTMINER_PATH_RETRY`，`recoveryEvents=[BOTMINER_PATH_RETRY, TARGET_ACCESS_CLEAR]`，终态 `COMPLETED`
- 场景 C：`recoveryStage=BOTMINER_PATH_RETRY`，`recoveryEvents=[BOTMINER_PATH_RETRY]`，终态 `COMPLETED`
- B 场景同时保留了多次内部重试原始日志，事件序列按首次发生顺序去重
- 证据：`.alice-supervision/client-tests/minetask-scene-b-20260905/evidence/scene-b-dedup-key-lines.log`
- 结论：恢复事件观测契约达到 `USER_ACCEPTED`；`MINETASK_REPLAN` 仍未通过客户端验证

## 2026-09-05：MineTask 恢复层级契约

- 目标：区分目标访问清障、BotMiner 内部路径重试、MineTask 任务级重规划和最终升级失败
- 改动：新增 `RecoveryStage`；MineTask 和 BotMiner 写入恢复层级；TaskExecutionRecord 与终态日志输出 `recoveryStage`
- 保持：不改变 `HARD_PATH`、`SEARCH_LIMIT`、BotMiner 两次内部重试、MineTask 一次任务级重规划及 A/B/C 场景
- 验证：`./gradlew compileJava --no-daemon`、`./gradlew build --no-daemon` 均通过
- 客户端：本轮仅增加观测字段，未重新同步或声称 A/B/C 回归验收
- 文档：`docs/archive/legacy-2026-08/MINETASK_RECOVERY_CONTRACT.md`
- 下一步：如需验证 `MINETASK_REPLAN`，先提交场景 D 预览图和几何方案，用户确认后再实现

> 只记录关键开发轮次、失败和根因，不替代 Git 历史。

## 记录模板

```text
日期：
提交/工件：
目标：
实际改动：
验证：CODE_REVIEW / COMPILES / SERVER_LOG / WINDOWS_CLIENT / USER_ACCEPTED
用户观察：
当前根因：已确认 / 假设 / 未知
排除的解释：
未解决风险：
下一步：
```

## 使用规则

- 失败也要记录，避免上下文压缩后重复走旧路线；
- 未获得用户反馈前，不把客户端测试标为通过；
- 发现可能修复方式后，先记录假设并与用户讨论；
- 同一问题连续失败时联用 `debugging-root-cause-analysis` 和 `failure-pattern-recognition`；
- 小改动可以只追加几行，不要求写长报告。

## 2026-08-26：MineTask / Movement 职责边界复盘

- 提交/工件：未提交；本轮仅更新项目文档
- 目标：恢复并冻结 MineTask、领域规划、通用路径规划、Movement 与执行器之间的职责边界
- 实际改动：更新 `AI_DECISIONS.md`、`AI_PROJECT_STATE.md`、`MOVEMENT_SYSTEM_ARCHITECTURE.md`；追加本记录
- 验证：CODE_REVIEW
- 用户观察：用户指出 Task 更接近 mc_aiplay 的 Action/行为包，而不是决策层；要求重新核对 Movement 建设时期对 Dijkstra 的设计原因
- 当前根因：已确认（架构表述错误已确认；代码修复未进行）
- 本轮确认：Task/Action 负责行为编排和生命周期；决策层暂不实现；目标访问清障与路径清障分离；Movement 覆盖通用移动原语；当前 `AStarPathfinder` 实际采用零启发式 Dijkstra
- 排除的解释：不能把“类名为 AStar”当成内部已经使用 A*；不能把所有清障都归给 MineTask 或所有清障都归给 Movement
- 未解决风险：`MineTask` 当前清障实现、`BreakAndWalkMovement` 与 `PathExecutor` 的执行契约，以及隧道/搭路/搭柱子能力仍需独立验证；尚未授权重构
- 下一步：先按冻结后的边界讨论最小闭环，再决定是否针对现有执行契约实施定向修复

## 2026-09-05：MineTask 首选站位接入（过渡修复）

- 提交/工件：未提交；已写入源码并同步 Windows 工作区
- 目标：消除 `MineTask` 预选站位与 `BotMiner` 再次选站之间的冲突
- 实际改动：`MineTask` 为原始目标创建 `BotMiner` 时传入 `optimalStandingPoint`；`BotMiner` 优先验证并使用该站位，首选路径不可达时记录原因并回退旧候选逻辑；临时清障目标不复用原目标首选站位
- 验证：`COMPILES`；Linux `compileJava` 成功；Windows 源文件已逐文件同步并用 `cmp` 核对
- 用户观察：此前第二格墙壁目标的 `MineTask` 预选站位更近且预判清晰，但 `BotMiner` 实际改选了另一站位并触发不必要清障
- 当前根因：已确认（两套站位结果未共享）；运行时 LOS 与理想候选点差异仍是独立风险
- 排除的解释：本次未改 LOS 判定、清障策略、PathExecutor、Dijkstra/A* 或 SOFT_PATH
- 未解决风险：首选站位即使路径可达，实际实体到站后仍可能因狭窄通道偏移导致 LOS 失败；长期仍需收敛为统一 `MiningPlanner`/`MiningPlan`
- 下一步：用户在相同第一格/第二格墙壁场景中测试，提供 `[首选站位探针]`、`[站位选择探针]`、`[LOS探针/运行时]` 和最终挖掘结果

## 2026-09-05：增加目标多点 LOS 诊断（不改变行为）

- 目标：验证“目标中心射线失败，但目标方块其他可瞄准区域可见”的假设
- 实际改动：`BotMiner.lineOfSightClear()` 在中心射线失败时，从同一真实眼睛位置向目标中心及六个面内缩点执行诊断 raycast
- 采样：面内缩 `epsilon=0.08`，不使用几何角点
- 行为边界：多点结果仅写入 `[LOS多点探针]`，当前 `lineOfSightClear()` 仍返回原中心射线结果
- 防重复：每个 `BotMiner` 实例最多记录一次多点诊断
- 验证：`COMPILES`；Linux `compileJava` 成功；Windows `BotMiner.java` 已同步并 `cmp` 一致
- 下一步：用户复现狭窄通道第一格/第二格墙壁目标，检查中心结果与各面采样结果是否分歧

## 2026-09-05：正式接入多点 LOS 判定（第二阶段）

- 目标：将已由客户端场景证实的“目标中心不可见但目标面内点可见”纳入正式挖掘判定
- 实际改动：`LineOfSightChecker.checkFromEye()` 统一对目标中心及六个面内缩点 raycast；任一点首个命中目标即 `clear=true`
- 接入：`MineTask` 预检查、`BotMiner` 候选站位检查、`BotMiner` 到站运行时检查均使用同一 `LineOfSightChecker` 语义
- 安全边界：仍使用 `COLLIDER`；不使用几何角点；`epsilon=0.08`；保留目标命中约束、原有距离检查、安全策略和服务端破坏确认
- 未改动：候选排序、路径、清障、PathExecutor、Movement、Dijkstra/A*、SOFT_PATH
- 兼容：保留 BotMiner 旧的多点诊断辅助代码，但正式结果不再依赖它；运行时日志增加成功采样点/首个阻挡方块
- 验证：`COMPILES`；Linux `compileJava` 成功；Windows 两个源码文件已同步并 `cmp` 一致
- 下一步：用户复测第一格墙壁目标应可直接开始挖掘；完全被遮挡目标不得因距离足够而放行

## 2026-09-05：MiningPlan 基础设计最小接入

- 目标：将正常单目标挖掘的目标、规划起点、站位、路径和 LOS 结果收束为可传递的规划快照
- 实际改动：新增 `task/mining/MiningPlan`；`MineTask` 组装并持有原始目标计划；`BotMiner` 增加计划构造器并消费计划路径；原有无计划构造器保留给清障和其他兼容调用
- 计划字段：`target`、`startFoot`、`standingFoot`、`SurfacePathfinder.Result`、`LineOfSightChecker.LineOfSightResult`
- 最小不变量：字段非空且不可变；路径 goal 必须等于 standingFoot；`isExecutable()` 仅表示规划快照满足 `REACHED + LOS clear`，不替代运行时验证
- 运行时边界：计划路径直接复用；当前脚位与 `startFoot` 不一致时只记录 `planPathMayBeStale`，暂不擅自重规划；计划失效仍由 BotMiner 报告、MineTask 后续决定
- 清障边界：临时清障目标不创建/复用原始 MiningPlan，仍走兼容 BotMiner 构造器
- 未改动：SurfacePathfinder、PathPlanner 状态体系、PathExecutor、Movement、目标访问清障策略、掉落物拾取、Dijkstra/A*、SOFT_PATH
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；三个源码文件已同步 Windows 并 `cmp` 一致
- 代码审查备注：`git diff --check` 报告当前差异中既有尾随空格，本轮未做无关格式清理
- 下一步：Windows 复测第一格/第二格墙壁目标，确认 `[MiningPlan探针] consumed`、计划路径执行、实际挖掘无回归；若出现结构性复杂度，停止扩张并重新评估

## 2026-09-05：MiningPlan 基础接入 Windows 验收

- 用户操作：复测已验收的狭窄通道第一格/第二格墙壁目标
- 第一格证据：`MiningPlan created/consumed` 一致；`pathStatus=REACHED`、`pathSize=5`、`visibility=true`、`executable=true`；首选站位被选为 `PREFERRED`；PathExecutor 无阻挡到达；运行时多点 LOS 通过；目标开始并完成挖掘；任务终态 `COMPLETED`
- 第二格证据：`MiningPlan created/consumed` 一致；首选计划路径返回 `SEARCH_LIMIT`，`executable=false`，随后按兼容回退选择可达站位；运行时 LOS 通过；目标开始并完成挖掘；任务终态 `COMPLETED`
- 用户观察：使用已验证的多点 LOS 后，两个场景均能顺利完成；掉落物拾取不计入本轮主链验收
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`
- 仍需保留的问题：`SEARCH_LIMIT` 的计划重置/重规划策略、BotMiner 兼容回退的最终移除时机、MiningPlanner 独立化；SurfacePathfinder 系统替换延期
- 下一步：进入下一轮架构讨论或设计，不继续扩大本轮 MiningPlan 实现

## 2026-09-05：MiningPlan 一致性与执行模式收敛

- 目标：在不引入独立 MiningPlanner 的前提下，明确当前计划执行与旧兼容回退的边界
- 实际改动：`BotMiner` 增加 `executionMode`；计划构造器记录 `PLAN`；旧构造器记录 `LEGACY`/`PREFERRED_STAND`；首选计划路径回退时日志明确 `LEGACY_FALLBACK`
- 一致性探针：`MineTask` 创建原目标 BotMiner 时记录 planTarget、startFoot、standingFoot、optimalStandingPoint、currentMineTarget；计划起点与消费时脚位不一致时记录 `planPathMayBeStale=true`
- 行为边界：不自动重规划、不修改候选选择、不改变清障/拾取；本轮只增加模式与状态证据
- 验证：`COMPILES`（`./gradlew compileJava` 成功）；源码已同步 Windows 并核对一致
- 下一步：用户专项测试计划模式、原目标计划回退和清障兼容模式；如无结构性问题，再讨论独立 MiningPlanner

## 2026-09-05：MiningPlan 执行模式专项复测通过

- 场景 1：目标 `29, -62, -27`；计划创建/一致性/消费均为 `PLAN`；`pathStatus=REACHED`、`visibility=true`、`executable=true`；首选站位 `PREFERRED`；目标开始并完成挖掘；任务终态 `COMPLETED`
- 场景 2：目标 `29, -61, -34`；计划创建/消费为 `PLAN`，但路径为 `SEARCH_LIMIT`、`executable=false`；明确记录 `PLAN -> LEGACY_FALLBACK`；兼容候选选择 `DIRECT`；目标开始并完成挖掘；任务终态 `COMPLETED`
- 验证结论：计划模式、起点/站位/目标一致性探针和兼容回退模式均按设计工作；`SEARCH_LIMIT` 未被误报为 `UNREACHABLE`
- 验证等级：`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`
- 下一步：进入独立 `MiningPlanner` 设计讨论；保持 SurfacePathfinder、掉落物拾取、清障计划化和 PathPlanner 状态统一延期

## 2026-09-05：MiningPlanner 薄层最小迁移

- 目标：把“目标 → 站位 → SurfacePathfinder 路径 → LOS → MiningPlan”从 `MineTask` 中抽出独立入口，不改任务状态机
- 实际改动：新增 `task/mining/MiningPlanner`；复用现有 `StandingPointSelector`、`StandingPointEvaluator`、`LineOfSightChecker` 和 `SurfacePathfinder`；`MineTask.evaluateStandingPoint()` 改为调用 `MiningPlanner.plan()` 并保存返回计划
- 结果类型：薄 `MiningPlanner.Result`，成功携带 `MiningPlan` 与可选评分，失败只携带简单 `failureReason`；不新增路径状态、不处理重试/清障/拾取
- 保留边界：`BotMiner` 继续消费 `MiningPlan`；临时清障仍使用兼容构造器；SurfacePathfinder、PathPlanner 状态统一、PathExecutor、Movement、掉落物拾取均未改动
- 探针：新增 `[MiningPlanner探针] planning failed/planned`，保留原有 `[MiningPlan探针]` 作为消费证据
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；`MiningPlanner.java` 与 `MineTask.java` 已同步 Windows 并 `cmp` 一致
- 下一步：Windows 复测第一格/第二格墙壁目标，确认新规划入口输出与原先 MiningPlan 行为一致；若出现结构性复杂度则停止迁移

## 2026-09-05：MiningPlanner 薄层迁移 Windows 验收

- 场景 1：目标 `29, -62, -27`；`MiningPlanner planned` 产生 `REACHED`、`visibility=true`、`executable=true` 的计划；MineTask 一致性与 BotMiner 消费均为 `PLAN`；首选站位执行；挖掘完成；任务终态 `COMPLETED`
- 场景 2：目标 `29, -61, -34`；规划结果为 `SEARCH_LIMIT`、`visibility=true`、`executable=false`；记录 `PLAN -> LEGACY_FALLBACK`；兼容候选选择 `DIRECT`；挖掘完成；任务终态 `COMPLETED`
- 用户观察：两场景结果与上一轮一致，规划入口独立化没有改变已验收行为
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`
- 下一步：在继续迁移前，先讨论 `MiningPlanner` 与 MineTask 的最终职责、计划失效报告和 SurfacePathfinder 后续替换边界

## 2026-09-05：MiningPlan 失败报告最小接入

- 目标：区分 BotMiner 的一次执行失败与 MiningPlan 前提可能失效；本轮只报告，不自动重规划
- 实际改动：`BotMiner` 新增 `PlanInvalidation` 与 `FailureReport`；`failureReport()` 返回稳定的失败原因和计划失效提示；`MineTask` 记录 `[MineTask计划失败报告]`，明确 `currentPlanRetained=true autoReplan=false`
- 失效提示：起点偏差为 `PLAN_START_MISMATCH`；计划路径不可用按 `PLAN_PATH_STALE`；`SEARCH_LIMIT` 单独按 `PLAN_PATH_INCONCLUSIVE`，不将未知结果解释为路径过期；运行时 LOS/距离失败分别报告 `RUNTIME_VISIBILITY_FAILED` / `RUNTIME_OUT_OF_REACH`
- 行为边界：不替换 `currentPlan`，不新增自动重规划，不改变既有清障分支和路径动态阻挡重试；`MiningPlanner.Result` 保持调试材料
- 依据：任务生命周期 skill 要求失败原因稳定且不隐式改成另一种任务；路径执行契约要求区分 `SEARCH_LIMIT`、`UNREACHABLE`、动态 `BLOCKED`
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；BotMiner/MineTask 已同步 Windows 并 `cmp` 一致
- 下一步：Windows 复测已验收的第一格/第二格墙壁目标，重点确认成功链无回归、`SEARCH_LIMIT` 仍报告 `PLAN_PATH_INCONCLUSIVE`；本轮不要求专门制造运行时失效

## 2026-09-05：MineTask 失败报告与终态证据接入

- 目标：保存最近一次 `BotMiner.FailureReport`、记录执行尝试次数，并在 MineTask 终态前输出计划证据；只报告，不自动重规划
- 实际改动：`MineTask` 新增 `lastFailureReport`、`executionAttempts`、`lastFailureReport()`、`executionAttempts()`、`currentPlanRetained()`；每次创建 BotMiner 计为一次执行尝试，清障 BotMiner 也计入
- 终态证据：`BotManager.complete()` 在清理任务前记录 `[MineTask终态计划证据]`，包含 attempts、currentPlanRetained、reason、planInvalidation
- 行为边界：不改变现有失败分支、清障流程、路径动态阻挡重试、拾取流程或计划替换；`currentPlan` 失败后仍保留，`autoReplan=false`
- 技术修正：编译首次发现 BotManager 缺少 BotMiner 导入，已补齐后重新编译通过
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；MineTask/BotManager 已同步 Windows 并 `cmp` 一致；`git diff --check` 仅报告既有尾随空格
- 下一步：Windows 复测第一格/第二格墙壁目标，重点观察 `[MineTask终态计划证据]`；不要求专门制造运行时失效，也不把拾取失败归因于 MiningPlan

## 2026-09-05：MineTask 终态计划证据复测通过

- 场景 1：目标 `29, -62, -27`；计划 `REACHED + visibility=true + executable=true`；`executionMode=PLAN`；挖掘完成；终态 `COMPLETED code=done`；终态证据 `attempts=1 currentPlanRetained=true reason=- planInvalidation=NONE`
- 场景 2：目标 `29, -61, -34`；计划 `SEARCH_LIMIT + executable=false`；中间回退证据为 `PLAN_PATH_INCONCLUSIVE -> LEGACY_FALLBACK`；兼容路径挖掘完成；终态 `COMPLETED code=done`；终态证据 `attempts=1 currentPlanRetained=true reason=- planInvalidation=NONE`
- 语义说明：终态报告的 `reason=- / planInvalidation=NONE` 表示最终任务没有未处理失败；场景 2 的中间计划不确定性已经由 `[首选站位回退]` 日志保留，不应把成功终态标记为失败
- 验证结论：执行尝试计数、currentPlan 保留状态、最终失败报告字段均无回归；本轮两个场景均正常完成，未触发拾取失败
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`
- 下一步：失败证据接入阶段收束；讨论有限内部处理与决策层交接，不立即实现自动重规划

## 2026-09-05：MineTask 失败分类与决策层升级入口

- 目标：为 MineTask 增加统一的最终失败记录入口，区分可继续处理的清障分支与需要上层决策的失败；不实现自动重规划
- 实际改动：规划失败、安全拒绝、清障安全拒绝和清障耗尽统一调用私有 `escalateFailure(reason, report)`；输出 `[MineTask升级决策探针]`
- 升级证据：记录 `target`、`reason`、`planInvalidation`、`attempts`、`clearDepth`、`currentPlanRetained`、`nextAction=ESCALATE`
- 行为边界：可继续清障时仍继续；路径动态阻挡既有有限重试不变；不替换 `currentPlan`；不自动重规划；不接入 LLM/决策层；不修改拾取和路径系统
- 技术原则：`BotMiner.Status.FAILED` 是一次执行失败，`MineTask.Status.FAILED` 是任务内部处理后最终失败；`SEARCH_LIMIT` 继续保持不确定语义
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；`MineTask.java` 已同步 Windows 并 `cmp` 一致
- 下一步：Windows 复测第一格/第二格墙壁目标，确认成功链无回归且不误触发 `[MineTask升级决策探针]`；不要求专门制造失败

## 2026-09-05：MineTask 有限失败处理协议最小接入

- 目标：在现有失败处理边界上增加明确的 `CONTINUE_EXISTING_RECOVERY` / `ESCALATE` 分类，不实现任务级自动重试或自动重规划
- 实际改动：`MineTask` 新增私有 `FailureHandling` 和 `classifyFailure()`；BotMiner 失败后输出 `[MineTask失败处理探针]`，再进入原有清障继续或最终升级分支
- 分类边界：硬目标/安全拒绝直接 `ESCALATE`；普通执行失败先 `CONTINUE_EXISTING_RECOVERY`，由原有路径动态重试或目标访问清障逻辑决定；清障耗尽仍通过 `escalateFailure()`
- 行为边界：不替换 `currentPlan`；不调用 MiningPlanner 重规划；不修改 BotMiner 内部路径重试、清障深度、拾取和终态协议；不接入决策层
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；MineTask 已同步 Windows 并 `cmp` 一致
- 下一步：Windows 复测第一格/第二格墙壁目标，观察 `[MineTask失败处理探针]` 不破坏正常链路；不要求专门制造失败

## 2026-09-05：MineTask 有限失败处理协议 Windows 验收

- 场景 1：目标 `29, -62, -27`；MiningPlanner 正常规划；目标挖掘完成；终态 `COMPLETED code=done`；终态计划证据 `attempts=1 currentPlanRetained=true reason=- planInvalidation=NONE`
- 场景 2：目标 `29, -61, -34`；规划为 `SEARCH_LIMIT`；回退正确记录 `PLAN_PATH_INCONCLUSIVE -> LEGACY_FALLBACK`；目标挖掘完成；随后已知 `DropCollectionTask` 返回 `collect_no_path`，整体终态为 `FAILED`；该失败不属于 MiningPlan、MiningPlanner 或 MineTask 失败处理协议
- 验证结论：本轮失败分类入口没有改变正常挖掘链；没有误触发最终升级；`SEARCH_LIMIT` 和兼容回退语义保持正确；第二场景整体失败仅来自延期的拾取技术债
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`；挖掘主链通过，DropCollectionTask 仍为已知失败
- 下一步：有限失败处理协议阶段收束；后续讨论是否设计一次任务级内部重试及其与决策层的交接，不修改当前已验收挖掘主链

## 2026-09-05：MineTask 一次任务级重规划最小实现

- 目标：在现有路径内部重试和目标访问清障均无法继续后，对允许恢复的计划失效类型最多重新调用一次 MiningPlanner
- 实际改动：新增 `MAX_RECOVERY_ATTEMPTS=1`、独立 `recoveryAttempts`、`tryReplan()` 和 `isReplanEligible()`；成功时由 MineTask 显式替换 `currentPlan`、更新 `optimalStandingPoint`、重置清障深度并重新创建原目标 BotMiner
- 允许重规划：`PLAN_START_MISMATCH`、`PLAN_PATH_STALE`、`RUNTIME_VISIBILITY_FAILED`、`RUNTIME_OUT_OF_REACH`
- 禁止重规划：`PLAN_PATH_INCONCLUSIVE`/`SEARCH_LIMIT`、硬安全拒绝、明确需要隧道；BotMiner 不得自行替换计划
- 恢复顺序：硬拒绝直接升级；已有清障优先；无法继续清障后才检查一次重规划资格；资格不符/机会耗尽/规划失败则 `ESCALATE`
- 证据：新增 `[MineTask重规划探针]`；失败报告记录 `taskReplanPolicy=ONE_ATTEMPT`；终态证据增加 `recoveryAttempts`
- 行为边界：不增加路径搜索预算、不修改 `MAX_CLEAR_DEPTH`、不自动授权隧道、不修改拾取、SurfacePathfinder/PathPlanner/Movement、不接入真实决策层
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；MineTask/BotManager 已同步 Windows 并 `cmp` 一致
- 下一步：先复测第一格/第二格墙壁目标，确认 `recoveryAttempts=0` 且成功链无回归；本轮暂不要求人为制造计划失效，之后再设计一个可控失效场景验证一次重规划本身

## 2026-09-05：一次任务级重规划常规场景回归通过

- 场景 1：目标 `29, -62, -27`；规划 `REACHED`；挖掘完成；整体终态 `COMPLETED`；终态证据 `attempts=1 recoveryAttempts=0 currentPlanRetained=true reason=- planInvalidation=NONE`
- 场景 2：目标 `29, -61, -34`；规划 `SEARCH_LIMIT`；正确记录 `PLAN_PATH_INCONCLUSIVE -> LEGACY_FALLBACK`；挖掘完成；终态证据 `attempts=1 recoveryAttempts=0`；整体任务随后因已知 `DropCollectionTask collect_no_path` 失败
- 验证结论：正常 `REACHED` 和 `SEARCH_LIMIT` 回退均未误触发 `[MineTask重规划探针]`；一次任务级重规划机制没有干扰已验收挖掘链；拾取失败继续独立延期
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`；重规划正常链无回归，重规划触发分支尚未实测
- 下一步：设计一个可控的运行时计划失效测试，验证白名单失效类型触发恰好一次重规划以及 `currentPlanReplaced=true`；不依赖普通场景推断

## 2026-09-05：远目标测试暴露 SEARCH_LIMIT 失败码语义修正

- 现象：约 20～31 格远目标在当前 100ms 零启发式 Dijkstra 预算内返回 `SEARCH_LIMIT`；BotMiner 兼容站位搜索也返回 `stand_search_limit`，但 MineTask 最终错误报告 `target_requires_tunnel`
- 根因：MineTask 在“无清障目标且无法恢复”出口统一覆盖失败原因为 `target_requires_tunnel`，丢失 `SEARCH_LIMIT` 的不确定语义
- 修正：`stand_search_limit` 或 `PLAN_PATH_INCONCLUSIVE` 直接以 `stand_search_limit` 升级；记录“目标站位搜索预算耗尽”；不调用任务级重规划，不转换为需要隧道
- 未改动：100ms 搜索预算、零启发式 Dijkstra、SurfacePathfinder、候选选择、一次任务级重规划白名单、清障和拾取
- 验证：`IMPLEMENTED`；`COMPILES`（`./gradlew compileJava` 成功）；MineTask 已同步 Windows 并 `cmp` 一致
- 下一步：用远目标快速复测失败码应为 `failed:stand_search_limit`；随后将动态阻挡测试通道缩短到能稳定 `REACHED` 的 8～10 格

## 2026-09-05：可视化 MiningReplan 动态障碍测试夹具

- 目标：避免短路径场景依赖手速，提供客户端手动触发、服务端确定性放置障碍、目标/障碍双透视高亮的独立开发夹具；不复用会自动结束服务端的 BotSelftest
- 新入口：注册 `alice:mining_replan_tester`（指南针模型）；Shift+右键方块表面选择相邻空气格为障碍；右键目标方块启动正常 MineTask；Shift+右键空气清理夹具石头与高亮
- 服务端夹具：`MiningReplanFixture` 只读观察当前 MineTask 的 MiningPlan；只有初始路径 `REACHED`、Bot 已移动且距障碍 2～4 格时才放置真实石头；初始非 REACHED、任务未运行或等待超时则中止，不伪造 FailureReport/重规划
- 同步与渲染：新增 S2C `MiningReplanFixturePacket` 和客户端 `ClientMiningReplanState`；复用现有无深度测试线框 RenderType，绿色显示目标、橙色显示障碍
- 架构边界：夹具不调用 `tryReplan()`、不修改 PathExecutor/MiningPlanner、不中止服务端、不传送 Bot/玩家、不修改搜索预算；服务端是放置和状态真相，客户端只触发和渲染
- 编译修正：首次编译发现 record 布尔访问器与构造式方法同名，改为 `isObstaclePlaced()` / `markObstaclePlaced()` 后通过
- 验证：`IMPLEMENTED`；`COMPILES`/`BUILD`（`./gradlew build` 成功）；11 个相关源码/资源文件已同步 Windows 并逐一 `cmp=0`
- 待验证：物品模型、绿色/橙色双高亮、服务端自动放障、客户端可见过程、正式 MineTask 的路径恢复/一次重规划结果

## 2026-09-05：可视化动态障碍夹具与路径内部恢复 Windows 验收

- 客户端证据：用户通过聊天标记和截图确认绿色目标框、橙色障碍框、服务端动态放置石头均可见；测试一 Bot 跨过脚位石头，测试二 Bot 从侧面绕过头部石头；当前模型无法直接解码 PNG，客户端现象以用户观察为证
- 场景 1：目标 `30, -62, -26`，起点 `30, -62, -16`，障碍 `30, -62, -20`；初始计划 `REACHED pathSize=9 visibility=true executable=true`；Bot 移动到距障碍 3 格时夹具放置石头；PathExecutor 报动态阻挡；BotMiner 内部 `重新规划(1/2)` 后跨过方块；目标挖掘完成；终态 `COMPLETED`、`recoveryAttempts=0`
- 场景 2：同一目标/起点，障碍为 `30, -61, -20`；初始计划同样 `REACHED`；夹具在距障碍约 3.16 格时放置石头；PathExecutor 报动态阻挡；BotMiner 内部 `重新规划(1/2)` 后从 X=31 侧面绕行；目标挖掘完成；终态 `COMPLETED`、`recoveryAttempts=0`
- 验证结论：测试夹具前置检查、服务端确定性放障、双高亮和真实路径动态阻挡均工作；两次变化均由执行层第一次内部路径恢复成功吸收，符合“执行层先恢复、任务层后恢复”的职责顺序
- 未验证：`MineTask` 一次任务级重规划触发分支仍未实际触发；本轮 `recoveryAttempts=0` 是正确结果，不能据此宣称任务级重规划已通过
- 验证等级：夹具 `IMPLEMENTED`、`COMPILES/BUILD`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`；BotMiner 动态路径恢复 `WINDOWS_CLIENT/USER_ACCEPTED`；MineTask 一次重规划触发仍待测
- 下一步：若要验证任务级重规划，需设计一个执行层两次内部路径恢复和现有目标访问清障均无法吸收、但重新生成 MiningPlan 仍有替代站位的受控场景；不得靠增加障碍或搜索预算盲试

## 2026-09-05：轻量寻路移动器

- 需求收敛：不做专业寻路测试器、不保存固定起点、不新增专用协议、不维护重跑状态；只需一次右键让现有 Bot 走到选择的终点，作为其他测试的摆位工具并暴露硬路径问题
- 新入口：`alice:pathfinding_tester`，中文名“寻路移动器”；右键方块将其上方一格作为目标脚位，服务端选择当前维度已有 Bot 并分配 `WalkToTask`
- 执行边界：`WalkToTask` 只调用 `SurfacePathfinder.find` + `PathExecutor`，不调用 `MineTask`、`BotMiner`、挖掘/放置/清障或 `SOFT_SURFACE`；目标脚位必须有支撑且脚位/头位可通行
- 结果：成功、`walk_search_limit`、`walk_no_path`、`walk_blocked`、`walk_execution_failed` 分开记录；`SEARCH_LIMIT` 保持不确定语义
- 资源：补充中英文翻译，复用原版完整指南针模型以避免紫黑贴图
- 验证：`./gradlew build` 成功；7 个相关源码/资源文件已同步 Windows 并逐一 `cmp=0`
- 客户端验收：用户确认测试工具操作正常；`alice:pathfinding_tester` 可通过一次右键让 Bot 移动到选择的脚位，可作为其他测试的摆位入口；标记 `WINDOWS_CLIENT`、`USER_ACCEPTED`

## 2026-09-05：MineTask 失败分类与升级入口 Windows 验收

- 场景 1：目标 `29, -62, -27`；`MiningPlanner` 输出 `REACHED + visibility=true + executable=true`；`executionMode=PLAN`；目标挖掘完成；终态 `COMPLETED code=done`；终态计划证据 `attempts=1 currentPlanRetained=true reason=- planInvalidation=NONE`
- 场景 2：目标 `29, -61, -34`；规划结果 `SEARCH_LIMIT + executable=false`；中间回退正确记录 `PLAN_PATH_INCONCLUSIVE -> LEGACY_FALLBACK`；兼容路径挖掘完成；终态 `COMPLETED code=done`；终态计划证据 `attempts=1 currentPlanRetained=true reason=- planInvalidation=NONE`
- 验证结论：MineTask 失败分类/升级入口没有改变成功链；本次没有误触发 `[MineTask升级决策探针]`；`SEARCH_LIMIT` 语义与计划证据链保持正确
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`
- 下一步：失败分类与升级日志阶段收束；下一阶段先设计有限内部重试与决策层交接协议，不立即实现自动重规划

## 2026-09-05：MiningPlan 失败报告复测结果

- 场景 1：目标 `29, -62, -27`；`MiningPlanner planned` 为 `REACHED`、`visibility=true`、`executable=true`；计划一致性与 `executionMode=PLAN` 正常；目标已 `挖掘完成`。随后已知 `DropCollectionTask` 失败，整体 `MineTask` 终态为 `FAILED code=failed:collect_path_failed`；该失败属于延期的拾取技术债，不属于 MiningPlan/挖掘主链失败
- 场景 2：目标 `29, -61, -34`；计划为 `SEARCH_LIMIT`、`executable=false`；回退日志包含 `invalidation=PLAN_PATH_INCONCLUSIVE -> LEGACY_FALLBACK`；目标已挖掘完成；整体任务终态 `COMPLETED code=done`
- 验证结论：失败报告语义正确，`SEARCH_LIMIT` 被单独报告为 `PLAN_PATH_INCONCLUSIVE`；计划创建、消费和兼容回退无回归；第一场景的任务终态受拾取失败影响，不能将其标为完整 MineTask 成功
- 验证等级：`IMPLEMENTED`、`COMPILES`、`SERVER_LOG`、`WINDOWS_CLIENT`、`USER_ACCEPTED`；其中 MiningPlan/挖掘主链通过，DropCollectionTask 仍为已知失败
- 下一步：MiningPlan 失败报告阶段收束；若继续完善，先讨论有限重试与上层决策交接的接口，不修改拾取系统

## 2026-09-05：独立标准 Forge 实例中的 WorldEdit 验收

- 目标：为 Alice 的可重复测试场景提供外部蓝图编辑工具，不让 WorldEdit 成为 Alice 的构建或运行依赖
- UserDev 失败边界：发行 JAR 直接放入开发 `run/mods` 时触发 `NoSuchFieldError: f_128958_`；ForgeGradle 映射依赖方案随后触发 `NoClassDefFoundError: com/sk89q/worldedit/session/SessionOwner`，因此该路线已回退
- 标准实例：PCL 独立安装 Minecraft 1.20.1 + Forge 47.4.10，目录为 `D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10\`
- 模组：安装当前构建的 `alice-1.0.0-1.20.1.jar` 与官方 `worldedit-mod-7.2.15.jar`
- 用户观察：标准 Forge 实例启动和 WorldEdit 实际操作均正常
- 验证等级：Alice 构建 `COMPILES/BUILD`；WorldEdit 与 Alice 共存为 `WINDOWS_CLIENT`、`USER_ACCEPTED`
- 固定边界：WorldEdit 仅用于场景创建、保存、加载、粘贴和撤销；Alice 仍负责路径规划、任务、Bot 执行与日志；不再将 WorldEdit 接入 Alice 的 `build.gradle` 或 UserDev `run/mods`

## 2026-09-05：MineTask 可重复测试场景生成器

- 目标：回归 MineTask，提供进入世界后可直接调用的最小场景生成入口，不扩展 MineTask 或 WorldEdit 集成
- 实际改动：在 PCL 世界 `新的世界` 安装 `alice_test` 数据包；新增 `reset`、`scene_a`、`scene_b`、`scene_c` 和 `load` 函数
- 场景：A 为正常曲面挖掘基线；B 为墙体视线遮挡与有限目标访问清障；C 为现有可视动态障碍夹具入口
- 操作边界：数据包只使用原版 `fill`、`setblock`、`tp` 和物品实体清理；不调用 Alice 内部 API，不伪造失败报告，不触发任务级重规划
- 使用说明：新增 `D:\JAVA_projects\worldedit-test\MINETASK_SCENE_QUICKSTART.md`
- 验证：数据包文件、函数路径和 JSON 静态校验通过；客户端实际执行待用户在 PCL 世界中验收
- 下一步：先验收场景 A/B/C 的生成和 MineTask 行为；场景 D 任务级重规划候选几何暂不宣称通过

## 2026-09-05：MineTask 场景 A 一键启动与透视高亮

- 目标：取消场景 A 中用户手动查坐标、摆 Bot 和选择目标的步骤；用户只启动并观察，场景方块仍由用户手动恢复
- 新入口：新增 `alice:mining_scene_tester`（挖掘场景启动器）；普通右键启动固定场景 A，Shift+右键清除会话和高亮
- 服务端行为：校验场景 A 目标 `(4,64,4)` 和 Bot 起点 `(0,64,0)`；无 Bot 时用现有 `BotManager.spawn` 生成可见 `ServerPlayer`，有空闲 Bot 时显式传送并广播位置，再分配正常 `MineTask`
- 客户端显示：扩展既有 `MiningReplanFixturePacket` 传输 Bot 起点；目标保持绿色透视边框，Bot 起点新增青色透视边框，使用现有无深度测试线框 RenderType
- 结束语义：任务完成/失败后保留 Bot、方块和高亮；聊天输出 `TaskExecutionRecord` 终态摘要；Shift+右键只清除会话/高亮，不自动恢复世界
- 资源与同步：更新中英文物品名、启动场景 A 自动发放启动器；构建产物已同步至 PCL `worldedit-test` 实例
- 验证：`./gradlew build` 成功；JAR 校验值 `309929783b6c281e9a8e45d3b41af13d5686410208c84f0b389df0609994d5fa`；客户端 `WINDOWS_CLIENT` / `USER_ACCEPTED` 待本轮实测

## 2026-09-05：场景 A 启动失败的标准 Forge 兼容修复

- 用户现象：启动器普通右键无提示，Shift+右键清除正常
- Windows 日志证据：普通右键已进入 `MiningSceneFixture.startSceneA`，但 `BotManager.spawn` 创建 `FakeConnection` 时抛出 `NoSuchFieldException: channel`；异常来自标准 Forge 运行时的混淆字段名，不是右键未触发
- 根因：`FakeConnection` 固定通过 `Connection.class.getDeclaredField("channel")` 注入 EmbeddedChannel；标准发行运行时字段为混淆名 `f_129468_`，开发映射和发行映射不一致
- 修正：按 `io.netty.channel.Channel` 字段类型定位 `Connection` 中的唯一 Channel 字段，同时兼容 UserDev/标准 Forge；场景启动器增加异常聊天反馈
- 验证：`./gradlew build` 成功；修复 JAR 已同步 PCL 标准实例；新 JAR SHA-256 `77344ee944e498dc9d33416ccbebe46ee90d19ba04284f945b2e1d9c5f5ac4a7`
- 待验证：重启 PCL 客户端后场景 A 自动生成 Bot、起点/目标透视框、MineTask 启动和终态摘要

## 2026-09-05：标准 Forge 第二个反射映射兼容修复

- 用户复测证据：`FakeConnection` 的 `channel` 问题已越过；Bot 已加入玩家列表，但 `BotManager.spawn` 随后的 `spawnInvulnerableTime` 反射再次失败
- 标准运行时映射：开发字段名为 `spawnInvulnerableTime`，标准 SRG 字段为 `f_8921_`；通过 `srg_to_official_1.20.1.tsrg` 核对，不按整数字段位置猜测
- 修正：`BotManager` 使用 `findServerPlayerField("spawnInvulnerableTime", "f_8921_")`，兼容 UserDev 与标准 Forge；场景启动失败仍显示聊天错误
- 验证：`./gradlew build` 成功；修复 JAR 已同步 PCL；新 SHA-256 `eb538b4808b3dfc23da7b3d91ea9468bb9a9f2e307fe10cbf63a51fbba8fe922`
- 待验证：重启 PCL 后 Bot 完成生成，继续验证起点/目标高亮、MineTask 规划和终态摘要

## 2026-09-05：复用 Bot 的客户端整体偏移修复

- 用户复测现象：第一次场景 A 正常；重置后再次测试，服务端任务路径和站位正确，但客户端看到的 Bot 整体偏移，完成瞬间瞬移到固定终点；连续测试动作轨迹相同
- 对照证据：多轮服务端 `startFoot=0,64,0`、`standingFoot=4,65,4`、终点 `4,64,4` 完全一致；只有复用 Bot 走手工传送分支
- 根因假设已由原版 `ServerEntity.sendChanges` 字节码验证：手工发送 `ClientboundTeleportEntityPacket` 只移动客户端位置，没有同步原版 `VecDeltaCodec` 的位置基准；之后的相对移动包按旧基准解释，造成整体偏移，追踪器后续绝对校正造成结束瞬移
- 修正：`MiningSceneFixture` 复用 Bot 时仅调用服务端 `teleportTo`，移除额外 `broadcastAndSend(ClientboundTeleportEntityPacket)`；由原版实体追踪器统一发送绝对位置并更新基准
- 验证：`./gradlew build` 成功；JAR 已同步 PCL；SHA-256 `86fac73b95f03898419c14a99cf701b1108ede6e507443383382f3c9acff760c`
- 待用户验证：重启客户端后连续执行第二轮、第三轮场景 A，确认客户端轨迹与服务端位置一致

## 2026-09-05：场景 C 长直动态障碍验收

- 用户反馈：重做后的场景 C 中 Bot 跨过中央障碍到达目标，并提供客户端截图
- 场景几何：起点 `(0,64,0)`、目标 `(10,64,0)`、障碍 `(5,64,0)`、边界 `x=-1..11,z=-2..2`
- 截图证据：`2026-09-05_23.33.38.png`；青色起点、橙色障碍和 Bot 终态可见，Bot 状态为空闲
- 日志证据：障碍在 Bot `(1,64,0)` 时放置；`OBSTACLE_PLACED`；PathExecutor 报告 `(5,64,0)` 不可走；BotMiner 内部恢复 `重新规划(1/2)`；终点 `(10,64,0)`，任务 `COMPLETED/done`
- 结论：场景 C 达到 `USER_ACCEPTED`；`recoveryAttempts=0` 符合本场景边界，不代表没有发生 BotMiner 内部恢复
- 证据目录：`.alice-supervision/client-tests/minetask-scene-c-20260905/evidence/`
