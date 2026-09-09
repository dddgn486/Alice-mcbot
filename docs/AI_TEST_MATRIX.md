# Alice 当前测试矩阵

> 轻量记录关键场景，不要求每个功能制作复杂报告。客户端测试在 Windows，由真人完成。
>
> 恢复层级字段见 `docs/MINETASK_RECOVERY_CONTRACT.md`；A/B/C 已用事件序列契约复核，不改变其行为边界。

## 证据等级

```text
CODE_REVIEW -> COMPILES -> SERVER_LOG -> WINDOWS_CLIENT -> USER_ACCEPTED
```

## 测试入口原则

优先使用可 `/give` 获得的游戏内测试物品，通过手持右键/Shift+右键操作。复杂坐标命令只用于精确诊断，不作为普通测试主入口。

## 当前架构阶段说明

当前测试矩阵分为两类：

- Legacy 正式任务：MineTask/A/B/C、不可达安全失败，继续作为稳定基线；
- Movement 独立实验：M1/M2 证据保留，但不代表新 Pathing Core 已建立，也不授权 MineTask 接入。

新的 Pathing Core R1/R2 测试入口尚未定义；在契约评审通过前不新增生产寻路入口。

## 当前矩阵

> 说明：下方 Movement 实验 1-6、M1、M2 行是历史验收记录，不再代表当前注册的测试物品或可用入口；新 Pathing Core 重新设计后会建立新的最小入口。

| 能力 | 推荐入口 | 关键操作 | 关键观察 | 当前状态 |
|---|---|---|---|---|
| BotController | Bot 控制器测试物品/现有控制入口 | 手持后右键、Shift+右键，或少量命令辅助 | bot 是否移动、跳跃、停止、是否回弹/不可见 | 以最近 Windows 证据为准 |
| 目标选择器 | `alice:target_selector` | 右键目标方块 | 目标高亮、bot 接收任务、日志阶段 | 已有实现，等级需核对 |
| C1 扫描 | `alice:interface_scanner` | 右键方块 | GUI/聊天中的只读接口事实，无副作用 | Windows 已验收窄范围 |
| 道路规划 | `alice:road_planner` | 两点右键，Shift+右键重置 | 蓝图预览、障碍/流体处理 | 独立能力，按实际记录 |
| 转移选择 | `alice:transfer_endpoint_selector` | Shift+右键 source，右键 destination | 选择反馈、原版箱子 GUI 不被破坏 | 有历史证据，需按当前工件核对 |
| GUI/同步 | 对应测试物品 | 打开、点击、关闭重开 | 服务端改动是否实时显示，客户端是否崩溃 | 每项单独记录 |
| 实体同步 | 对应 bot/控制物品 | 生成并观察 bot | 生成、可见、位置、装备、回弹 | 每次物理改动必须重测 |
| MineTask 场景 A | `alice:mining_scene_tester` + `alice_test:scene_a` | 普通右键启动；重置后重复测试 | 目标/起点高亮、规划站位、挖掘、Bot 复用后位置同步、终态 | `USER_ACCEPTED`；三轮连续完成，证据见 `.alice-supervision/client-tests/minetask-scene-a-20260905/evidence/` |
| MineTask 场景 B | `alice:mining_scene_b_tester` + `alice_test:scene_b` | 生成场景 B 后使用 B 启动器普通右键 | 固定遮挡、有限目标访问清障、无隧道/软移动回退 | `USER_ACCEPTED`；证据见 `.alice-supervision/client-tests/minetask-scene-b-20260905/evidence/` |
| MineTask 场景 C | `alice:mining_scene_c_tester` + `alice_test:scene_c` | 普通右键自动标记中央障碍并启动；Shift+右键清除 | 长直路径动态阻挡、BotMiner 内部恢复、最终终态 | `USER_ACCEPTED`；截图和日志证据见 `.alice-supervision/client-tests/minetask-scene-c-20260905/evidence/` |
| MineTask 不可达安全失败 | 用户布置的不可达目标 + `alice:target_selector` | 选择无法到达的目标 | 初始 `UNREACHABLE` 直接安全失败，不执行 BotMiner、不重规划、不挖隧道；失败事实可见 | `USER_ACCEPTED`；证据见 `.alice-supervision/client-tests/minetask-unreachable-20260906/evidence/` |
| Pathing Core R2-B 单段 Traverse | `/alice pathing traverse <north|south|east|west>` | 选中/生成 Bot 后执行一次方向命令 | 目标仅为当前脚位同高度四向相邻一格；服务端 `[R2-B Traverse]` 记录 started 与 completed/failed，Bot 实际脚位、支撑、onGround 与输入清理可观察 | `USER_ACCEPTED`；固定工件 SHA-256 `fc0ad051208422882b5d8c1060bb4afe822552a1ef753ac5e0964b8e09e0fbbe`；证据见 `.alice-supervision/client-tests/pathing-core-r2b-traverse-20260907/evidence/` |
| Pathing Core R2-C Diagonal | `/alice pathing diagonal <northeast|northwest|southeast|southwest>` | 选中/生成 Bot 后执行一次对角方向命令 | 目标仅为同高度对角一格；两侧方块通行性检查防对角穿墙；`[R2-C Diagonal]` 记录 started/completed 与 actualFoot/support/onGround/controllerActive | `USER_ACCEPTED`；4/4 方向 8 ticks 完成，actualFoot 全部一致；验收工件 SHA-256 `804c5eb897a5337a07ff11286332805b1f2a4227d41bf1cdd4e5622982ed98cf`；证据见 `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/` |
| Pathing Core R2-C Ascend | `/alice pathing ascend <north|south|east|west>` | 选中/生成 Bot 后在一级台阶旁执行命令 | dy=+1 相邻一格；利用原版自动踩台阶（无跳跃）；头部空间不足时 `ASCEND_INVALID_PRECONDITION` 拒绝；SETTLING 验证落地 | `USER_ACCEPTED`；移除跳跃后完成良好，偶发上层水平位置偏差致 settling 超时（已知，暂不处理）；同一验收工件；证据同上 |
| Pathing Core R2-C Descend | `/alice pathing descend <north|south|east|west>` | 选中/生成 Bot 后在一格落差边缘执行命令 | dy=-1 相邻一格；`blockPosition().getY()` 检测真实下降（修复 getY() 恒真 bug）；SETTLING 验证落地；回收等级 LOCAL_STEP | `USER_ACCEPTED`；下降动作 4/4 成立，1/4 精确落点 completed，3/4 因自动踩台阶过冲报 settling 超时（用户裁定暂不处理）；同一验收工件；根因探针日志已归档并回收 |
| Movement 实验 1 平地物理 | `alice:bot_remote_control` | Bot 平地前进后停止 | `aiStep` 后位移、停止稳定、无明显双倍推进 | `USER_ACCEPTED`；证据见 `.alice-supervision/client-tests/movement-physics-experiment-1-20260906/evidence/` |
| Movement 实验 2 单个 WalkMovement | `alice:movement_walk_tester` | 右键无遮挡平地方块 | 单个 Movement 完成、实际脚位一致、输入清理 | `USER_ACCEPTED`；证据见 `.alice-supervision/client-tests/movement-physics-experiment-2-20260906/evidence/` |
| Movement 实验 3 连续 WalkMovement | `alice:movement_sequence_walk_tester` | 右键距离 Bot 至少 6 格的无遮挡平地方块 | 三段按序完成、段间无异常、实际脚位一致、最终停止 | `USER_ACCEPTED`；修复字段遮蔽后 Windows 日志确认连续三次 `COMPLETED`，实际脚位一致且无新异常；历史证据见 `.alice-supervision/client-tests/movement-physics-experiment-3-20260906/evidence/`；高差/跳跃按边界拒绝 |
| Movement 实验 4 台阶/下降 | `alice:movement_vertical_walk_tester` | 普通右键测试上一级；Shift+右键测试下降一级 | 记录目标/实际脚位、Y、支撑方块、onGround；区分自然通过、阻挡和安全拒绝 | `USER_ACCEPTED`；一级上升/下降均通过，证据见 `.alice-supervision/client-tests/movement-physics-experiment-4-20260906/evidence/`；下降方向标签有诊断瑕疵 |
| **串联回归（常规入口）** | `alice:pathing_regression` + `/function alice_test:pathing_course` | 普通右键一次 | **12 项**依次自动建地形并断言（执行 6 / 拒绝 2 / 岩浆路线安全 1 / 路线偏好 1 / 重规划 1 / 重同步 1）；聊天/日志 `[Regression] SUMMARY ...=PASS/FAIL` | `WINDOWS_CLIENT`（2026-09-09：12 项中 11 项 PASS，`lava_course` 按 D-057 改断言后 PASS，12/12） |
| Pathing Core R3 自检电池 | `alice:pathing_battery` + `/function alice_test:pathing_course` | 生成场景后普通右键一次跑完 8 项 | `SUMMARY` 全 PASS（含 `plan_budget=SEARCH_LIMIT`） | `USER_ACCEPTED`；8/8 PASS；证据见 `.alice-supervision/client-tests/pathing-r3-battery-20260908/` |
| Pathing Core R4 会话 | `alice:pathing_session` | 普通右键 | `plan→session` 多段执行、COLUMN→EXACT 容差、结构化终态 | `USER_ACCEPTED`；`status=COMPLETED segments=2/2`；证据见 `.alice-supervision/client-tests/pathing-r4-session-20260908/` |
| R4 自愈（扰动） | `alice:pathing_disturber` + `/function alice_test:place_course` | 普通右键（第 30 tick 起把 bot 平移 1 格） | bot 被位移后自行恢复、任务有终态、不空跳 | `WINDOWS_CLIENT`（用户 2026-09-09 通过）；修复前曾空跳 17 次不终止（D-035） |
| R5 破坏通行 | `alice:pathing_breaker` + `/function alice_test:break_course` | 普通右键 | 破坏进度/工具选择/直连 2 格语义、终态 | `USER_ACCEPTED`；证据见 `.alice-supervision/client-tests/pathing-r5-break-20260909/` |
| R5 放置台阶 | `alice:pathing_placer` + `/function alice_test:place_course` | 普通右键 | 缺支撑时放置台阶后通行、终态 | `USER_ACCEPTED`；证据见 `.alice-supervision/client-tests/pathing-r5-place-20260909/` |
| Baritone 对照（连续行动轨迹） | `/function alice_test:contrast_trace_pathing` + `#goto 0 62 44` | 一键准备并开始记录；立刻输入 goto | `latest.log` 每 tick 一行 `[TRACE]`（位置/速度/落地/偏航）；看水平速度是否在方块边界归零 | 后续准备，暂不测试 |
| 任务层重规划（D-043） | `alice:pathing_waller` + `/function alice_test:place_course` | 普通右键（第 30 tick 在计划前方封路） | 日志 `[R4 Fixture] wall_placed` → `[PathRetry] replan` → 会话 `COMPLETED replans=1`；任务终态 COMPLETED | 待测 |
| 垂直上升 PILLAR（D-055） | `alice:pathing_pillar` + `/function alice_test:pillar_course` | 普通右键 | `[Pillar] SUMMARY pillar_plan=PASS resource_guard=PASS pillar_execute=PASS detail=REACHED/first=PILLAR/pillars=3`（3 格深 1×1 基岩竖井；规划器选 2×PILLAR+1×ASCEND cost=11.68；清空快捷栏方块后应不可规划） | `WINDOWS_CLIENT`（2026-09-09：三项全 PASS，两次真实放置） |
| COLUMN 完成判定（D-056） | 任意执行场景（PILLAR/ASCEND/DESCEND/DOWNWARD/PLACE/BREAK 全量） | 一次串联回归 | 所有 `segment_done` 都必须发生在 bot **落地后**（日志 `ticks=` 不再出现 4 tick 假成功；PILLAR 必先出现 `[Pillar] placed`） | `WINDOWS_CLIENT`（2026-09-09：12 项回归除 lava 外全 PASS） |
| 岩浆路线安全（D-057） | `alice:pathing_lava_guard` + `/function alice_test:lava_course` | 普通右键 | `[LavaGuard] status=... lava_contacts=0 result=PASS`（允许 REACHED：抬高一格 + 岩浆上方搭桥 + 落回平台 B，全程不进入岩浆格） | `WINDOWS_CLIENT`（2026-09-09：`lava_contacts=0 result=PASS`，回归 12/12） |
| Baritone 对照（PILLAR） | `/function alice_test:contrast_pillar`（或 `contrast_trace_pillar`）+ `#goto 25 67 44` | 一键准备后立刻输入 goto | 同一场景/同一目标下 Baritone 也应「跳跃中在脚下放方块」上升 3 格；`[对照计时]`/`[TRACE]` 与 Alice 侧对比 | 待测（D-055） |
| 垂直下落（D-048/D-050） | `alice:pathing_downward` + `/function alice_test:vertical_course` | 普通右键 | `[Vertical] SUMMARY downward_execute=PASS shaft_plan=REACHED`（Baritone 原样语义：破坏脚下掉 1 格；竖井可规划） | 待测 |
| 顿挫对比轨迹（D-049） | `/alice trace`（开关）+ `alice:pathing_session` | 开→跑场景→关 | `latest.log` 每 tick 一行 `[TRACE]`；已实测（D-051：Alice 每格停顿 vs Baritone 全程 0.28 连续） | `WINDOWS_CLIENT` |
| 站立判定（A+B 对齐 D-041） | `alice:pathing_fence_guard` + `/function alice_test:fence_course` | 普通右键 | 聊天/日志 `status=UNREACHABLE`（栅栏不可站、不可穿）；`REACHED` 即缺陷 | `WINDOWS_CLIENT`：`status=UNREACHABLE` → PASS（2026-09-09） |
| 路线偏好（Q7 验收 D-040） | `alice:pathing_dip_route` + `/function alice_test:dip_course` | 普通右键 | 基线（标定前，客户端实证）`first=DESCEND movements=3 cost=4.41`（≈34 tick）；标定后应为 `first=TRAVERSE movements=4 cost=4.66`（≈28 tick） | `WINDOWS_CLIENT`：标定后 `first=TRAVERSE movements=4 cost=4.66` → PASS（2026-09-09） |
| 流体屏障（内核对齐 D-037） | `alice:pathing_fluid_guard` + `/function alice_test:fluid_course`（充水墙）或 `alice_test:lava_course`（封闭岩浆池） | 普通右键 | 聊天/日志 `status=UNREACHABLE`（拒绝把含流体方块当可挖阻挡物）；`REACHED` 即缺陷；岩浆场景另看 bot 是否朝岩浆移动 | 待测（第一刀对齐验证项） |
| Movement 实验 5 场景 D 动态阻挡 | `/function alice_test:movement_d` + `alice:movement_dynamic_block_tester` | 普通右键启动；行走中途自动在 `(5,64,20)` 放置封闭横向石墙；Shift+右键清理 | 验证动态障碍、实际停留位置、失败语义和输入清理；不自动重规划 | `USER_ACCEPTED`：Bot 停在墙前，`FAILED/movement_timeout_WalkMovement`，超时分支记录 `controller_stop_movement`；独立 `BLOCKED_DYNAMIC` 分类码尚未实现；证据见 `.alice-supervision/client-tests/movement-physics-experiment-5-20260906/evidence/` |
| Movement 实验 6A 动态阻挡分类 | `/function alice_test:movement_e` + `alice:movement_block_reason_tester` | 执行中在 E 场景 x=5 放置封闭横向方块屏障 | 仅有新方块碰撞体与 Bot 碰撞箱相交且无进展时报告 `BLOCKED_DYNAMIC`；记录障碍/阶段/noProgress 并清理输入 | `USER_ACCEPTED`：`FAILED/movement_blocked_dynamic_WalkMovement`，`failureCode=BLOCKED_DYNAMIC`，`failurePhase=EXECUTING_MOVEMENT`，实际脚位 `4,64,40`，`onGround=true`，证据见 `.alice-supervision/client-tests/movement-physics-experiment-6a-20260906/evidence/` |
| Movement 实验 6B 无动态障碍超时对照 | `alice:movement_timeout_diagnostic_tester` | 右键启动不修改世界的诊断 Movement，持续 RUNNING 至超时 | 无动态障碍证据时保持 `MOVEMENT_TIMEOUT`，结构化报告记录 `dynamicObstacleEvidence=NONE`，并清理输入 | `USER_ACCEPTED`：至少两次运行一致，`failureCode=MOVEMENT_TIMEOUT`，`noProgressTicks=101`，证据见 `.alice-supervision/client-tests/movement-physics-experiment-6b-20260906/evidence/` |
| Movement 实验 6C 启动前置条件 | `/function alice_test:movement_f` + `alice:movement_precondition_tester` | 普通右键在 Bot 头部放置测试方块后立即检查；Shift+右键清理 | 起点头部空间被占用时直接 `INVALID_PRECONDITION`，不进入 Movement 执行或超时；清理输入 | `USER_ACCEPTED`：多次约 1 tick 直接 `INVALID_PRECONDITION/PRECONDITION`，无 Movement 执行或超时；截图中 Bot 位于头部障碍处属于夹具可见现象，外层另有窒息中断记录；证据见 `.alice-supervision/client-tests/movement-physics-experiment-6c-20260906/evidence/`；工件 `759ffcaba21f99a987ad87494d512edce11742abab5b849891071427cf7f1cd2` |
| Movement 实验 6 结果契约对照 | 场景 E / 6B 诊断入口 / 场景 F | 对照动态方块阻挡、无明确障碍的普通超时、启动前非法前置条件 | 服务端按证据区分 `BLOCKED_DYNAMIC` / `MOVEMENT_TIMEOUT` / `INVALID_PRECONDITION`；三者均清理输入，不自动重规划 | `USER_ACCEPTED`；6A、6B、6C 均已验收 |

| Movement MVP M1 计划编译 | `alice:movement_plan_tester` | 右键无遮挡平面目标方块，编译 Bot 当前脚位到目标脚位的相邻路径 | `MovementPlanCompiler` 接受同高度四向相邻段，执行完成且实际脚位一致；非相邻/高差/障碍路径显式拒绝 | `USER_ACCEPTED`；修复构造器校验后 Windows 日志确认 3 次 `compiled`/`COMPLETED`，段数 8/20/24，实际脚位均与目标一致；证据为 Windows `latest.log` 2026-09-06 18:19:42-18:20:01 |
| Movement MVP M2 BotMiner 后端 | `alice:movement_plan_mining_tester` | 右键无遮挡目标方块；Bot 从当前脚位编译到合法挖掘站位 | MovementPlan 由 BotMiner 消费，MovementPathExecutor 完成后才开始挖掘；移动失败不提前挖掘、不回退旧 PathExecutor | `USER_ACCEPTED`；Windows 日志 21:09:40-21:09:42 确认 5 段 Movement 从 `-5,64,-5` 到 `-4,64,-1` 完成，实际脚位一致，随后开始并完成挖掘；任务终态 `COMPLETED`、durationTicks=35；工件 SHA-256 `deaf45944fcb9270eb62186c357bf88c312ec417addb2bd9a10a0a092faf946d` |

## 性能验证

| 能力 | 测试入口 | 关键观察 | 当前状态 |
|---|---|---|---|
| 性能回归验证 | 与 Movement/MineTask 测试相同的客户端实例和场景 | 本轮未复现“平均 FPS 很高但 low 帧很低/体感卡顿”；未发现需要继续定位的问题 | `USER_ACCEPTED`；本轮到此收口，不做进一步性能诊断 |

## Windows 反馈询问

测试构建交给用户后主动询问：

1. 使用了哪个物品和哪个按键？
2. 游戏中实际看到什么？
3. 预期和实际差异是什么？
4. 必现、偶发，还是执行一段后发生？
5. 是否出现紫黑材质、回弹、卡死、崩溃或聊天异常？
6. 能否提供 Windows runtime 的 `latest.log`/`debug.log` 片段、截图或短视频？

## 失败记录

发生服务端日志与客户端现象不一致时，标记 `EVIDENCE_CONFLICT`，先核对 Windows 实际 JAR 和日志，不直接宣布修复成功。
