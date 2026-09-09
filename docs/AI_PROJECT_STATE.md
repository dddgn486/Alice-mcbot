# Alice 当前项目状态

> 这是新 AI 会话恢复上下文的首要文件。只记录当前，不记录完整历史。

更新时间：2026-09-09

## 当前目标

**（2026-09-08 用户重申）完全参照 Baritone 搭建寻路内核**，差异仅限三条：① Bot 可回收性安全策略；② 多层任务失败向上传递（任务层 → LLM 决策层处理接口）；③ 未来 Bot 并行运行接口。最终交付**完整寻路系统**，供其他任务系统接入。详见 `docs/ALICE_PATHING_CORE_ARCHITECTURE.md` §1.1 与 §13。

当前进度：
- R2-C 四个 Movement 已通过客户端验证（Descend 4/4 精确命中；Ascend/Diagonal 各 4/4）；
- **R3 搜索内核已通过客户端验证**（2026-09-08，一键自检电池 8/8 PASS）：
  `plan_flat=REACHED(2)`、`plan_up=REACHED(1)`、`plan_budget=SEARCH_LIMIT`（预算≠不可达）、
  `traverse/diagonal/ascend/descend/chain2` 全 PASS；证据
  `.alice-supervision/client-tests/pathing-r3-battery-20260908/`；
- **R4 PathSession 已通过客户端验证**（2026-09-08）：`alice:pathing_session` 跑 `plan→session`，
  `status=COMPLETED segments=2/2 ticks=21`，分段容差 COLUMN→EXACT 生效；证据
  `.alice-supervision/client-tests/pathing-r4-session-20260908/`；
- **头部朝向修复（D-029）**：夹具传送改用带头部同步的 `ServerPlayer.teleportTo(..., Set<RelativeMovement>, yRot, xRot)` 重载；用户确认"现在同步了"。
- 一键测试基础设施：`/function alice_test:pathing_course`（孤立长方体开阔场景，固定起点）+ `alice:pathing_battery`（8 项自检）+ `alice:pathing_session`（规划→逐段执行）；
- Baritone 对照审计已完成：`docs/R2C_BARITONE_AUDIT.md`；
- 已决策：D-024（落差红线 + 楼梯细化）、D-025（台阶 0.6 对齐真人）、D-026（合法位置集 + 统一完成契约）、D-027（分段完成容差）、D-028（R3→R4 顺序）、D-035（段计时修复 + 段内漂移检测）。

下一步（2026-09-09）：**内核对齐路线（D-036）进行中**。已完成并客户端验收：D-035（段计时/漂移）、D-037（流体/放置/危险方块）、D-040（成本模型 + 启发式，路线翻转 + 电池 8/8 + 三场景 COMPLETED 且总 tick 不劣化）；D-038 已撤回。待办 A+B 已验收（D-041：站立判定混合化 + Ascend 防御前置检查；`fence_course` UNREACHABLE PASS，回归全过）。待办 C 已实施（D-042：容差×5 / 超时单层 / 取消清理 / 校验失败计时）+ 串联回归入口 `alice:pathing_regression`，待测。待办 D 已验收（D-043：重规划下沉 + 封路夹具 `replans=1 → COMPLETED`）；串联回归 7/7 PASS（含岩浆池修复）。待办 E 已完成（D-044）。**legacy 收口已按方案 B 启动**（D-045）：legacy 任务入口统一禁用，新内核逐个接入后再启用。风险模式（H/G/S）已决策预留（D-046）。执行层对齐（D-047）已验收。新内核补齐 `DOWNWARD`（D-048/D-050）**已验收**；顿挫已用 Baritone 对照实测量化（D-051：Alice 每格停顿 1 tick vs Baritone 全程连续 0.28 格/tick），**条件 settle + 段间连续推进（D-052）已验收**（直线跑 96→51 tick、零停顿）；疾跑门控（D-053）与 11 项串联回归（D-054）**已验收**（2026-09-09：`SUMMARY` 11/11 PASS，直线跑 96→51→44 tick、零停顿）。**PILLAR 垂直上升（D-055）已验收**（客户端 `pillar_plan/resource_guard/pillar_execute` 全 PASS，两次真实放置）；**COLUMN 空中假成功（D-056）已验收**（12 项回归除 lava 外全 PASS）；**lava_course 断言改为"路线不接触岩浆"（D-057）已验收**（12/12 PASS）；**FALL（落差 2~3 格，D-058）已实施**：2/3 格规划 + 实跑 PASS（15 tick），两项守卫检查按 D-059 改为路线属性断言后**已验收**（五项全 PASS）；**过冲红线默认关闭 + 风险开关接口（D-059）已验收**（`/alice risk`，回归 13/13），风险评估体系按用户要求只讨论不实现（Baritone 原样 + PILLAR 返回守卫，只在允许世界修改的任务启用；新增 `alice:pathing_fall` + `alice_test:fall_course` + 回归第 13 项）：Baritone `MovementPillar` 对齐（居中→起跳→脚高于目标格顶面 0.1 后才在脚下放置→落回），新增 `alice:pathing_pillar` + `alice_test:pillar_course`（3 格深 1×1 基岩竖井）、回归第 12 项、Baritone 对照 `contrast_pillar`/`contrast_trace_pillar`。**Baritone 对照实例已就绪**（`Bariton_contrast`，Forge 47.4.23 + Baritone 1.10.5，流程见 `docs/BARITONE_CONTRAST_TESTING.md`）。Q3 相位对齐**已撤回**（D-038：Forge 源码证明 START/END 等价，实测无差异）；`segment_done` 保留 `ticks=` 遥测；Q7 验收场景已就绪（`alice_test:dip_course` + `alice:pathing_dip_route`，先采基线）。待决问题专项讨论见
`docs/ALIGNMENT_OPEN_QUESTIONS.md`（Q1 一格红线可回收性 / Q3 控制相位 1 tick / Q4 replan 归属 / Q7 成本模型偏好）。四层审计完成
（`docs/R4_BARITONE_ALIGNMENT_AUDIT.md`，64 项对照：39 未登记偏离 / 13 缺失 / 7 已登记 / 4 对齐）；
第一刀 P0 对齐已实施（D-037：流体不可挖 / 放置校验 InteractionResult / 危险方块扩表 /
删除任意 BlockItem 兜底 / D-031 文档修正），待客户端验证（`alice_test:lava_course` + `alice:pathing_lava_guard`
期望 `UNREACHABLE`，并回归 `alice:pathing_placer`、`alice:pathing_breaker`）。

历史：R4 自愈闭环待客户端验证。**2026-09-09 客户端扰动测试暴露 P0 缺陷（D-035）**：
`PathSession.segmentTicks` 从未自增 → 段超时永不触发、健康检查退化为每 tick；夹具把 bot 平移进缺口列后，
会话空跳 17 次约 10 秒不终止。已修复：段计时自增、落地态漂移检测（`SEGMENT_STALE_START`）、空中不重规划、
任务级 600 tick 兜底；夹具扰动只在目标位置可站立时生效。
待测：`/function alice_test:place_course` + `alice:pathing_disturber`（期望 `replans=1 status=COMPLETED`），
以及 `alice:pathing_placer` 干净通过（目标 `8,62,66`）。R5 三阶段已全部验收。

项目：Minecraft Forge 1.20.1 / Forge 47.4.10 / Java 17  
开发目录：`/home/fb486/projects/alice`  
Windows 测试目录：`D:\JAVA_projects\alice\`  
固定客户端：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`

## 当前工作方式

讨论需求 → 读取相关 skills → 选择最小闭环 → 实施 → compile → 同步 Windows → 用户用游戏内测试物品/命令实测 → 讨论证据和根因 → 再决定是否修复。

不再把旧的 dsh-agent-bus、active-plan、严格监督员、HANDOVER 提交流程作为日常开发硬门槛。

## 稳定架构边界

- LLM 只做目标级决策；确定性执行器负责动作、安全和完成条件。
- 服务端是世界、bot、任务、权限和库存的真相。
- 普通挖矿和拾取保持 `HARD_PATH`。
- `SOFT_SURFACE` 只能通过独立实验入口推进，不能悄悄接入正式任务。
- `SEARCH_LIMIT` 不等于 `UNREACHABLE`，不自动授权挖隧道。
- **Movement 落差红线（D-024）：Bot 不允许超过一格的落差**；Descend 过冲落点列必须与目标同层落脚，更深一律拒绝。
- **假人台阶高度 = 0.6，对齐真实玩家（D-025）**；一格方块必须跳跃才能上，`BotPlayer` 构造显式 `setMaxUpStep(0.6F)`，禁止改回 1.0。
- **Movement 合法位置集与统一完成契约（D-026）**：起点校验接受 `{fromFoot, toFoot}`；完成判定统一用 `MovementHelper.isSettledAtFootPos`（脚位 + 支撑 + onGround + 水平 ≤0.3）。
- 客户端行为必须由 Windows 真人测试确认；源码分析和服务端日志不能替代。

## 已验证的核心能力

### MineTask 基线（已验收）
- **场景 A/B/C**：`alice:mining_scene_tester` / `alice:mining_scene_b_tester` / `alice:mining_scene_c_tester`
  - A：基础挖掘，无障碍
  - B：有限清障（`TARGET_ACCESS_CLEAR`）
  - C：动态障碍中途恢复（`BOTMINER_PATH_RETRY`）
  - 证据：`.alice-supervision/client-tests/minetask-scene-{a,b,c}-20260905/evidence/`
- **不可达安全失败**：初始 `UNREACHABLE` 直接 `FAILED/no_safe_execution_path`，不执行 BotMiner 或重规划
  - 证据：`.alice-supervision/client-tests/minetask-unreachable-20260906/evidence/`

### 其他已验收能力
- `alice:interface_scanner`：C1 只读扫描
- `alice:pathfinding_tester`：轻量寻路移动器（摆位工具）
- `alice:mining_replan_tester`：可视动态障碍夹具
- WorldEdit 7.2.15：外部场景编辑工具，不加入构建依赖

### Movement 物理实验（历史证据保留）
- **实验 1-6**：BotController 输入 → aiStep 物理链、WalkMovement 单段/序列、一级上升/下降、动态阻挡分类（`BLOCKED_DYNAMIC` / `MOVEMENT_TIMEOUT` / `INVALID_PRECONDITION`）
  - 证据：`.alice-supervision/client-tests/movement-physics-experiment-{1,2,3,4,5,6a,6b,6c}-20260906/evidence/`
- **M0/M1/M2**：MovementPlan 契约、MovementPlanCompiler、BotMiner Movement 后端
  - 已完成独立验证，**未接入正式 MineTask**

## 当前架构阶段：Alice Pathing Core R2

**目标**：构建 Baritone-like Movement-aware Pathing Core，加入可回收性、禁区/保护区、生存兜底和任务失败交接。

**当前进度**：
- ✅ **R1 契约草案**：`docs/ALICE_PATHING_CORE_R1_CONTRACT.md`
- ✅ **R2-A 纯数据契约**：`pathing.core` 包（MovementSpec、PlanningDependency、LiveExecutionContext、MovementExecution 等）
- ✅ **R2-B Traverse 执行器**：`TraverseExecution`、`TraverseExecutionFactory`、`TraverseDiagnosticTask`
  - 入口：`/alice pathing traverse <north|south|east|west>`
  - 验收状态：`USER_ACCEPTED`
  - 工件：SHA-256 `fc0ad051208422882b5d8c1060bb4afe822552a1ef753ac5e0964b8e09e0fbbe`
  - 证据：已归档 `.alice-supervision/client-tests/pathing-core-r2b-traverse-20260907/evidence/`
- ✅ **R2-C 三种基础 Movement**：Diagonal / Ascend / Descend
  - 入口：`/alice pathing diagonal|ascend|descend <direction>`
  - 验收状态：`USER_ACCEPTED`（2026-09-07）
  - 验收工件：SHA-256 `804c5eb897a5337a07ff11286332805b1f2a4227d41bf1cdd4e5622982ed98cf`
  - 运行时清洁版（仅移除探针）：SHA-256 `9c0388aeafa77f9d7a32129dab5d0270d764a48f271e56cbee4ed048a2b8e8d9`
  - 证据：已归档 `.alice-supervision/client-tests/pathing-core-r2c-movements-20260907/evidence/`
  - 已知限制（用户裁定暂不处理）：Descend 落点过冲（自动踩台阶带上相邻方块边缘）、Ascend 偶发上层水平偏差
  - 关键教训：Descend 下降检测必须用 `blockPosition().getY()` 而非 `getY()`；Ascend 一级台阶不需要跳跃
- ⏸️ **R2-D 世界修改 Movement**：BreakAndTraverse、PlaceStepAndTraverse（未启动）
- ⏸️ **R3 PathSession**：多段链接、搜索集成（未启动）
  - ⚠️ **第 0 号闭环（硬前置，D-023）**：Descend 落点过冲修复——先调查 Baritone MovementDescend，再实现边缘切断输入 + 落点列前置校验，客户端验证"精确落点 + 多段不断链"。原因：过冲率 3/4，链式执行下必 stale-start；且过冲落点列从未被前置校验，是安全模型层面的洞

**保留但未接入生产**：
- Movement 实验 1-6 的物理验证链
- M0/M1/M2 的 MovementPlan 与 BotMiner 后端
- 旧 `PathExecutor` 继续作为兼容后端

**设计文档**：
- `docs/ALICE_PATHING_CORE_ARCHITECTURE.md`：总体架构基线
- `docs/ALICE_PATHING_CORE_R1_CONTRACT.md`：R1 契约草案
- `docs/ALICE_PATHING_CORE_R2_MOVEMENTS.md`：R2 Movement 设计
- `docs/MINETASK_MOVEMENT_MVP_DESIGN.md`：历史方案，仅作参考

## 当前不要做

- ❌ 不删除旧 `PathExecutor` 或直接接入 MineTask
- ❌ 不把 SOFT_SURFACE、隧道、搭路隐式接入普通挖矿
- ❌ 不把 `SEARCH_LIMIT` 当作 `UNREACHABLE` 或自动授权挖隧道
- ❌ 不实施多 Bot 并行调度
- ❌ R1/R2 契约未评审通过前，不扩展 MovementPlanCompiler 或给 WalkMovement 堆叠新语义
- ❌ 不在完成 Descend 过冲修复（第 0 号闭环）前启动 R3 多段链接；也不得用放宽落点后置条件（如 1.0D）代替修复

## 开发工具链

- **源码镜像**：`./tools/mirror-windows-workspace.sh` → Windows `/mnt/d/JAVA_projects/alice/`
  - Windows 是源码镜像，不保留 `.git`
  - WSL `/home/fb486/projects/alice` 是唯一 Git 管理端
- **工件同步**：`./tools/sync-windows-artifact.sh [jar] [windows-repo] [runtime-mods-dir]`
  - 第三个参数显式给出才同步到运行时 `mods`
- **固定客户端**：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`
  - 日志：`logs/latest.log`、`logs/debug.log`
  - 截图/视频：`screenshots/`、`videos/`（如果需要）

## 开始任何新任务前

1. 读取本文件和 `AI_DEVELOPMENT_PLAYBOOK.md`
2. 读取 `AI_DECISIONS.md` 和相关 skill
3. 查看当前代码和 Git 状态
4. 先与用户确认目标、成功条件和最小测试方式
5. 客户端行为优先设计为游戏内可获得的测试物品/命令，通过右键/Shift+右键/命令观察

## 重要证据规则

- `IMPLEMENTED` → `COMPILES` → `SERVER_TESTED` → `WINDOWS_CLIENT` → `USER_ACCEPTED` 分开记录
- 不能访问 Windows 文件时，不得声称已经读取 Windows 日志
- bug 反馈后先询问操作、预期/实际、复现频率和日志/截图，再讨论根因和修复方向
- 服务端日志片段用 `[关键词]` 筛选；客户端截图关注 Bot 位置、聊天、GUI、粒子和回弹
