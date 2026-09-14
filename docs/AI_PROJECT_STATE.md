# Alice 当前项目状态

> 这是新 AI 会话恢复上下文的首要文件。只记录当前，不记录完整历史。

更新时间：2026-09-12

> **编号体系速查（2026-09-12）**：本项目有四套编号，互不相同 ——
> ① **寻路内核里程碑 `R1–R5`**（`R1 契约` → `R2-A/B/C/D Movements` → `R3 PathSession+Battery` →
> `R4 Session 执行` → `R5 世界修改 Movement`；日志前缀 `[R2-B Traverse]`/`[R3 Battery]`/`[R4 Session]`/`R5-2`）；
> ② **L3 目标层切片 `J1–J8`**（`JOB_LAYER_DESIGN.md`）；
> ③ **缺口/契约项 `G1–G9`**（如 `G4 写入预算`）；
> ④ **架构决策 `D-001–`**（`AI_DECISIONS.md`，单调递增）。
> 2026-09-11 盘点时我曾临时用 `R1–R7` 记"风险项"，与①**撞车**，现已改称 **`T1–T7`**（见 D-124 末段）。

> **2026-09-09 晚（批次 5：模组兼容）**：首测发现模组连锁会取消原版逐格掉落物生成（缓冲后聚合生成），
> 我们的作用域把"从未进入世界"的幻影实体登记成掉落物 → 收集阶段空转 ~11 s。
> 已修（`ScopeBuffer` 延迟登记 + `inWorld()` 判定）并**按用户裁定把 `CollectDropsTask` 改为簇级收集**
> （背包增量计数 + 守恒交叉校验 `MISMATCH`）。**客户端验收通过（2026-09-09 21:06）**：
> `drops=1`、`collected=9/9`、`mismatch=0`、收集 `ticks=8`（修复前 220）、任务 `COMPLETED` 32 tick；
> 自家挖矿路径同日复测通过（`scene_a`：`collected=1/1 mismatch=0 ticks=14`，`MineTask COMPLETED` 37 tick）。
> **连锁生产开关已实施（D-077）**：默认 `OFF`、`/alice chain off|auto|force` 游戏内切换、AUTO 仅矿石/原木、
> 失败如实回落单格挖掘。首测暴露收集判据缺陷（"看着到位却不捡"：用了到方块中心距离而非原版包围盒相交，
> 且提前取消寻路），已修（D-076 修正）；随后发现重写时误删"创建寻路"段导致直接放弃，
> 已补回并加退休坐标探针；**客户端验收通过（2026-09-09 22:34）**：
> 单格两次 `collected=1/1 mismatch=0 ticks=12`（一次盒内直接吸走、一次走下台阶吸走），与用户观察一致。
> **D-077 三档全部验收**（off 无 `prod_*`；force/auto 各 `collected=9/9 mismatch=0`）。
> **批次 5 `mine_regression` 已实施（D-078）**：一键覆盖规划 5 项 + 执行 2 项 + 模组连锁 1 项，
> **客户端验收通过（2026-09-09 22:48）**：8/8 PASS、`ticks=99`、任务 `COMPLETED`
> （`exec_chain collected=9/9`、`exec_blocked collected=1/1+ delta=2` 通道副产品）。
> 同日补入 **floating 支撑放置**（`floating_course` + `floating_plan`/`exec_floating`，共 10 用例）；
> 首测暴露规划器缺口：`CURRENT` 短路早于悬空判定 → 不放支撑块（掉落物掉走），
> 已按用户裁定修（`CURRENT+悬空+不在正下方+有一次性方块` → 附带支撑放置）；
> 复测 `exec_floating=PASS supportPlaced=true`，仅 `floating_plan` 因期望模式写窄（应含 CURRENT）判 FAIL，
> 已修；**客户端验收通过（2026-09-09 23:13）**：10/10 PASS、`ticks=117`、`COMPLETED`。
> bot 专属连锁配置映射归入"框架完成后的模组兼容适配"阶段（只登记）。
>
> **2026-09-10 仓库整理 + 远程归档**：清理旧监督工作流（删除 182 个纯流程文件、归档 47 份历史文档）、
> 重写 `docs/README.md` 与首页 `README.md`、删除 JEI/JECh 构建依赖（D-079）并**修复长期失败的 CI**
> （首次 success）；本地与 `github/master` 同步（110+ 提交已推送）。
>
> **2026-09-09 晚（批次 5：模组兼容，诊断路径已实施）**：`alice:chain_test_runner` + `alice_test:chain_course`
> ——反射调用 Ore Excavation 1.13.174 的服务端入口触发连锁，验证"连锁掉落物捕获 + 收集"；
> 同步加固 `ScopeBuffer`（跳过被取消的生成事件 + 破坏点位置回退配对，应对模组缓冲掉落物）。
> jar `38fc2bd5…` 已同步固定客户端，**待客户端实测**（`WINDOWS_CLIENT` 未取得）。
> 策略见 **D-075**：连锁全局默认 `OFF`（原版），玩家游戏内手动启用，默认只连锁**矿石与原木**；
> bot 专属连锁配置映射、范围/批量建筑等能力留待后续（联动其他模组）。

> **2026-09-10 L3 立项（D-080）**：目标级任务层 `Job`（决策缝 + 配额 + 终止）设计定稿，
> 伐木为第一消费者（`docs/JOB_LAYER_DESIGN.md`）；用户裁定 `Job implements Task`、
> LOS 限次清障 ≤8 格/棵、高树 v1 拒绝但攀爬登记为未来能力。
> **二次裁定（D-081）**：接受"建拆同权"（放置与拆除同一授权）、持久化 `WorldModLedger`、
> 可持续伐木区做成 `MAINTAIN` 持续型 Job；**切片顺序 J1–J5 → J6 账本+恢复 → J7 攀爬 → J8 区域型**。
> **J1 已实施（2026-09-10）**：`job/` 契约七件 + `job/lumber/`（TreeScanner/LumberCandidateSource/LumberJob）
> + `job/policy/`（Nearest / NearestExposed）+ 入口 `alice:lumber_job` + 场景 `lumber_course` + `BotSession` 子目标跟随。
> jar `已同步`，**待客户端验证**。

## 最新（2026-09-14）—— 先读这里

**阶段 3-A（合成/熔炼接进任务层）已收口**（A1–A5 客户端验证 + `USER_ACCEPTED`）。
**当前主线 = 阶段 3-B 模组机器适配**（实验对象 Mekanism，方法见 `docs/MOD_ADAPTER_PROTOCOL.md`）：
**S0/S1/S2 已完成**（类型事实表 / 机器配方只读 `MACHINE_ROUTE` / 机器站点只读）；
**S3 已收口**（`SERVER_TESTED` + `WINDOWS_CLIENT`，2026-09-14 第二轮电池）：`decision/MachineMap.java` =
"机器类型 ↔ 机器方块/菜单"的**唯一真源** + 生成视图 `docs/MACHINE_MAP.csv` + 双向防漂移
`tools/check-machine-map.sh`；`Route.station` 改为机器方块 id；探针「按表认机器」并断言
"方块实体自述配方类型 == 表里的类型"（实测 `按表找到 2 台` + `m1_binding=true m2_binding=true`，
CORE `(28/28) → PASS`）。**实测纠正两条口径**（D-209）：零配方的 `mekanism:smelting` 解释
"表 27 行 vs 实测 26 类型" ⇒ 探针改为对表行完整划分 + 守恒自检；`menuClass` 实测**不区分机器**
（两台共用 `MekanismTileContainer`）⇒ 身份判据只有 `m{i}_binding`。
**S3 客户端实测复验通过**（2026-09-14 第四轮电池，`latest.log:3068`/`:3081`/`:3096`/`:3811`）：
`with_site_confirmed=22 with_site_unobserved=[mekanism:smelting] no_site=4 row_block_missing=0`（守恒 22+1+4+0=27）、
`m2_menu_class_matches=true`、`m{i}_slot_roles` 首次观察、`(28/28) ticks=2845 → PASS`；
`/alice authz` 的 L2 行**首次在客户端敲过**（`:3836`）⇒ 该待验证项关闭。
**S4 已跑通**（**3-B 的第一次写入**，`WINDOWS_CLIENT`，第五轮 `latest.log:3811`）：零参数物品
`alice:machine_cycle_check` → `MachineCycleCheckTask`（放料 → 等 → 取产物）：
`binding=true feed_verified=true in_machine=1 active_seen=true progress_ticks=199 product_after=1
product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true verdict=PASS`。
**不新造授权**：容器写入维度 `WriteBudget` + `WriteReason.CONTAINER_TRANSFER` + requester `machine-cycle`；
放料 shift-click（菜单自己决定落点）、成不成**只看世界事实**。v1 单机单配方 + 夹具传送（内核寻路 = v2）。
**电源前提已自证（D-213，2026-09-14 第七轮客户端实测）**：场景加一行
`data merge block … {EnergyContainers:[{Container:0,stored:"4000000000"}]}` 后重跑 ⇒
`energy_at_open=20000.0 energy_source=cube（场景电源，未补电） energy_ready=20000.0`（**不再是** `api_precharge` 的 4.0E6）、
`progress_ticks=199 product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true verdict=PASS`
（`latest.log:213`，场景 11 条命令见于 `:187`）⇒ **电来自场景本身、没走补电兜底**，D-213 的判据成立、`WINDOWS_CLIENT`。
真因（**D-213**）= **创造能量方块放下时自带电量就是 0 J、而且永远充不进电**（`BasicEnergyContainer:52` 初值 ZERO +
创造档 `insert` 强制 SIMULATE；`TileComponentEjector:166` 对空容器直接跳过），上游设计里"空变体"就是 power sink。
证据 = 存档里的对照组（同一次保存）：机器 `EnergyContainers=[{"Container":0,"stored":"3990000"}]`、方块 `EnergyContainers=[]`。
⚠️ **判据的准确含义**：`energy_source=cube` 实际证明的是"**场景把电送上了**"（开机时机器已有电），
不是"程序认出了 cube 方块"（读的是机器自己的能量容器）；场景里只有这一条供电路径 ⇒ 两者等价，注释已写清。
**S4 已按 D-197 升级为电池步**（第七轮升，准入前提 = 上面这条自证）：`machine_cycle`（MAIN，会写容器）⇒
电池 **CORE=29 / FULL=39**；`MachineCycleCheckTask` 类注释同步按 D-213 改写（台账 ⑥ 关闭）。
**✅ 电池步已转绿（第九轮，`WINDOWS_CLIENT`）**：新客户端会话（14:34:59 启动 ⇒ 新 jar 已加载，
日志里 `PROFILE=CORE 实跑 29 项（跳过 EXTRA 10 项）`）⇒ `[Regression] SUMMARY … machine_station=PASS
machine_cycle=PASS … (29/29) ticks=3108 → PASS`（`latest.log:3842`）；该步 `machine_cycle=PASS ticks=210
idempotent=true`（`:3130`），步内 `[MachineCycle] SUMMARY … energy_at_open=20000.0 energy_source=cube（场景电源，未补电）
… product_landed=true machine_emptied=true container_writes=2 reset=true verdict=PASS`（`:3129`）、
写预算 `containers=2/32 refusedContainers=0`（`:3131`）⇒ **电池内也走的是场景电源，不是补电兜底**。
**R1 收口（2026-09-14，D-211）已完成并经客户端验证**：`WritePolicyMatrix` **首次经手容器写入**
（挂点 `WriteBudget.consumeContainerWrite`；未登记 ⇒ 留痕不拒，**已登记但未声明 ⇒ 硬拒**，
拒绝权默认武装 + 一行回退开关 `setContainerRefusalArmed`）；`docs/authz/CONTAINER_WRITE_SITES.csv`
（20 个调用点）+ `tools/policy-map.py` 断言⑦（负例实测都红），并顶出/补上
**`CraftJob`（生产熔炼）从没记账** 的真缺口 + CRAFT 行补声明 `CONTAINER_TRANSFER`。
**第五轮证据**：`container_gate_live=PASS container_gate_armed=PASS containerGate=armed
container_checks=13 container_refused=0 verdict=PASS`（`latest.log:3206`）——**13 恰好等于各步
`containers=N/32` 之和**（2+2+3+4+2）⇒ 闸门覆盖面与预算覆盖面**逐点一致**；`container_refused=0`
⇒ 没有生产路径被硬停；D-211 的两条复核触发**都已解除**。
**✅ S5 收口已完成（D-215，2026-09-14）**：`alice:machine_cycle_check` 临时入口删除（物品类 + 注册 + 模型 + 两份 lang），
并清掉三支**已无调用点的 `assign*`**（`assignMachineProbe`/`assignMachineStationProbe` 是上轮回收后的死代码，
`assignMachineCycleCheck` 的唯一调用者是本次被删的物品）⇒ 源码零命中，`check-item-models` **76 项**（-1）；
台账⑦ 一并修掉（项数改 `coreStepCount()` 现算，不再写死"26 项"）；**"通用 vs 专属"对照表 = `docs/MOD_ADAPTER_PROTOCOL.md` §6**
（结论：执行侧/发现侧通用，专属只有 `MachineMap` 一张表 + 夹具 1 行目标 + 1 段能量反射）。
**⇒ 3-B（模组机器适配）S0→S5 全部走完**。新 jar `sha256=d5e49c1b4ec7e8f48b634c97f912f4513c5423ef4952871b569899d44fcfc5ef`（已同步客户端 `mods/`）。
**下一步 = 客户端一小轮（收口复核，1 步）**：**重启客户端** → ① `/alice battery core` 仍 `(29/29) … → PASS`；
② `/give alice:machine_cycle_check` **不存在**（探针零残留的用户侧证据）⇒ 之后进入下一个模组实验或 S4 v2（内核寻路走到机器旁）。
**上下文窗口已由用户从 256K 改为 512K**（D-214，本会话生效；阈值 409,600 / 保留 81,920）——改的是"何时压缩"，
不改变事实来源；复核触发 = 手动 `/compact` 频率没降、或我出现"忘记已确认事实/重复问已答过的问题" ⇒ 退回 256K。
电池 **CORE=29 / FULL=39**。**详细交接见 `docs/HANDOVER.md`**；**方向来源与审查留档见 `docs/reviews/2026-09-14-外部质疑与工作流审查留档.md`**；今天的新纪律见 PLAYBOOK §5.0b/§5.0c/§5.0d。

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
- Baritone 对照审计已完成：`docs/archive/legacy-2026-08/R2C_BARITONE_AUDIT.md`；
- 已决策：D-024（落差红线 + 楼梯细化）、D-025（台阶 0.6 对齐真人）、D-026（合法位置集 + 统一完成契约）、D-027（分段完成容差）、D-028（R3→R4 顺序）、D-035（段计时修复 + 段内漂移检测）。

下一步（2026-09-09）：**内核对齐路线（D-036）进行中**。已完成并客户端验收：D-035（段计时/漂移）、D-037（流体/放置/危险方块）、D-040（成本模型 + 启发式，路线翻转 + 电池 8/8 + 三场景 COMPLETED 且总 tick 不劣化）；D-038 已撤回。待办 A+B 已验收（D-041：站立判定混合化 + Ascend 防御前置检查；`fence_course` UNREACHABLE PASS，回归全过）。待办 C 已实施（D-042：容差×5 / 超时单层 / 取消清理 / 校验失败计时）+ 串联回归入口 `alice:pathing_regression`，待测。待办 D 已验收（D-043：重规划下沉 + 封路夹具 `replans=1 → COMPLETED`）；串联回归 7/7 PASS（含岩浆池修复）。待办 E 已完成（D-044）。**legacy 收口已按方案 B 启动**（D-045）：legacy 任务入口统一禁用，新内核逐个接入后再启用。风险模式（H/G/S）已决策预留（D-046）。执行层对齐（D-047）已验收。新内核补齐 `DOWNWARD`（D-048/D-050）**已验收**；顿挫已用 Baritone 对照实测量化（D-051：Alice 每格停顿 1 tick vs Baritone 全程连续 0.28 格/tick），**条件 settle + 段间连续推进（D-052）已验收**（直线跑 96→51 tick、零停顿）；疾跑门控（D-053）与 11 项串联回归（D-054）**已验收**（2026-09-09：`SUMMARY` 11/11 PASS，直线跑 96→51→44 tick、零停顿）。**PILLAR 垂直上升（D-055）已验收**（客户端 `pillar_plan/resource_guard/pillar_execute` 全 PASS，两次真实放置）；**COLUMN 空中假成功（D-056）已验收**（12 项回归除 lava 外全 PASS）；**lava_course 断言改为"路线不接触岩浆"（D-057）已验收**（12/12 PASS）；**FALL（落差 2~3 格，D-058）已实施**：2/3 格规划 + 实跑 PASS（15 tick），两项守卫检查按 D-059 改为路线属性断言后**已验收**（五项全 PASS）；**过冲红线默认关闭 + 风险开关接口（D-059）已验收**（`/alice risk`，回归 13/13）；**WalkTo 已迁移到新内核（D-060）**：`assignWalkTo` 解除 legacy 门禁，新增 `alice:walk_to_runner` + `alice_test:walk_course` 自检（flat/over_wall/unreachable/unsafe）**已验收**（4/4 PASS）；**回归覆盖断言（D-061）已实施**（每场景声明必须执行的 Movement + 全局 9 类型覆盖，待复测）；**Follow 已迁移到新内核（D-062）**：并修复用户报告的"玩家原地跳导致目标被判空中/不可达"缺陷（目标锚定 + 相邻站位目标，对齐 Baritone `GoalNear` 语义），新增 `alice:follow_runner` + `alice_test:follow_course` **已验收**；**Place 已迁移到新内核（D-063）**：放置改走统一 `BlockInteraction.placeAt`（需库存+支撑面+服务端校验），入口 `alice:target_selector` Shift+右键**已验收**；**Mine 迁移批次 1（D-064）已实施**：`MiningPlan/MiningPlanner/BotMiner/MineTask` 全部换新内核（动作层不变），`assignMine` 解除门禁，待客户端验证；**D-065 兼容修复**：`StandingPointSelector` 的 `canWalkOn(pos.below())` off-by-one（导致只能站目标顶上挖）+ 排除目标正上方，待复测；批次 2 = `CollectDropsTask` 重写（公用子任务 + 按需世界修改权限）；**挖掘站位新框架已定稿（D-067，`docs/MINING_STAND_SELECTION_DESIGN.md` v7）**；**批次 1 `BREAK_AND_ENTER`（D-068）已验收**（`plan_a/plan_b/execute_a` 全 PASS；实测石头+石镐 6 tick/格）；**批次 2 站位候选 + S1/S2 成本估算（D-069）已验收**（候选 60、Dijkstra 334 节点 12 ms、top-4 精算、回归 14/14 + coverage PASS）；**批次 3 两模式编排 + MiningBudget + collectDrops（D-070）已验收**（5/5 PASS）；**批次 4（D-071 MineBlockRunner / D-072 CollectDropsTask / D-073 伐木禁用+BotMiner 删除）已验收**（`scene_a` COMPLETED 35 tick）；**D-074 掉落物来源改为破坏事件配对 + 32 格放弃上限，待客户端验证**，风险评估体系按用户要求只讨论不实现（Baritone 原样 + PILLAR 返回守卫，只在允许世界修改的任务启用；新增 `alice:pathing_fall` + `alice_test:fall_course` + 回归第 13 项）：Baritone `MovementPillar` 对齐（居中→起跳→脚高于目标格顶面 0.1 后才在脚下放置→落回），新增 `alice:pathing_pillar` + `alice_test:pillar_course`（3 格深 1×1 基岩竖井）、回归第 12 项、Baritone 对照 `contrast_pillar`/`contrast_trace_pillar`。**Baritone 对照实例已就绪**（`Bariton_contrast`，Forge 47.4.23 + Baritone 1.10.5，流程见 `docs/BARITONE_CONTRAST_TESTING.md`）。Q3 相位对齐**已撤回**（D-038：Forge 源码证明 START/END 等价，实测无差异）；`segment_done` 保留 `ticks=` 遥测；Q7 验收场景已就绪（`alice_test:dip_course` + `alice:pathing_dip_route`，先采基线）。待决问题专项讨论见
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
- **寻路红线（D-076，取代 `HARD_PATH` 旧语句）**：寻路请求默认纯通行（`PathRequest.of`）；
  破坏/放置只能由上层任务**显式授权**并受**预算闸门**约束（挖掘站位 `miningApproach` + `MiningBudget`；
  收集 `allowWorldModification=true`）；禁止寻路器自行挖穿地形、禁止把 `SEARCH_LIMIT` 当授权、
  禁止实验性移动模式隐式接入正式任务。
- `SEARCH_LIMIT` 不等于 `UNREACHABLE`，不自动授权挖隧道。
- **Movement 落差红线（D-024）：Bot 不允许超过一格的落差**；Descend 过冲落点列必须与目标同层落脚，更深一律拒绝。
- **假人台阶高度 = 0.6，对齐真实玩家（D-025）**；一格方块必须跳跃才能上，`BotPlayer` 构造显式 `setMaxUpStep(0.6F)`，禁止改回 1.0。
- **Movement 合法位置集与统一完成契约（D-026）**：起点校验接受 `{fromFoot, toFoot}`；完成判定统一用 `MovementHelper.isSettledAtFootPos`（脚位 + 支撑 + onGround + 水平 ≤0.3）。
- 客户端行为必须由 Windows 真人测试确认；源码分析和服务端日志不能替代。

## 能力现状（2026-09-12 重写；旧版是 09-07 的 R2 阶段快照，已过时 ⇒ 见 git 历史）

### L0–L2 寻路内核（R1–R5 全部客户端验收）
- **9+1 种 Movement 原语**（`MovementType`）：`TRAVERSE / DIAGONAL / ASCEND / DESCEND / DOWNWARD /
  PILLAR / FALL / BREAK_AND_TRAVERSE / BREAK_AND_ENTER / PLACE_STEP_AND_TRAVERSE`
  （COLUMN 空中平台由 `PathSession` 上层处理，见 D-056）。
- **搜索与执行**：`AStarMovementSearch`（成本模型 + 启发式，D-040）→ `PathSession`（分段、完成容差、
  段计时/漂移检测、重规划 D-043、`no_progress` 快速失败 D-105、自愈 D-041）。
- **入口**：`alice:pathing_regression`（**18 场景 + 覆盖断言**，一次右键）、`alice:pathing_battery`、
  以及各 `alice:pathing_*` 单项诊断（traverse/ascend/descend/diagonal/pillar/fall/downward/placer/breaker…）。
- **红线纪律**：D-076（`PathRequest.of` 默认纯通行；破坏/放置必须显式授权 + 预算闸门）、
  D-024（落差 ≤1）、D-025（台阶 0.6）、`SEARCH_LIMIT ≠ UNREACHABLE`。

### L2 任务（已验收）
- `MineTask`（站位选优 + 清障 + 恢复阶段）/ `CollectDropsTask`（簇级收集 + 守恒交叉校验）/
  `PlaceTask` / `RestoreScopeTask`（恢复严格自上而下）/ `ScaffoldLifecycleTask`（搭-用-拆闭环）/
  `WalkToTask` / `FollowTask` / `TransferTask`。
- 一键回归：`alice:regression_battery`（**9 项，一次右键**；`mine_regression` 12 例、`lumber_failure_check` 6 例、
  `clear_retry_check`、`write_budget_check`、`scaffold_check`、`clear_guard_check`、`pathing`、还有 `lumber_job`/`region_maintain`）。

### L3 目标层 `Job`（J1–J8 **已收工**，伐木=首个高级任务）
- **契约七件**：`GoalSpec` / `Job` / `Candidate`+`CandidateSet` / `SelectionPolicy` /
  `DecisionTrace` / `GoalProgress` / `Selection`；**两个真实消费者**：伐木（`LumberJob`，J1–J4/J7/J8）、
  挖掘（`MineJob`，J5）。
- **J6 世界修改账本**：`WorldModLedger`（`TEMP`/`KEEP` 策略 + 作用域）+ 建拆同权 +
  `WriteBudget`（D-106 每作用域 64 breaks/32 places）+ 崩溃兜底 `pendingForOwner`（D-127）。
- **J7 攀爬砍树**：`ScaffoldLifecycleTask` + 逐树会话内拆除（D-107/D-109）。
- **J8 区域型 MAINTAIN**：`LumberRegionState`（持久化区域/我种的苗/待补种/baseline/统计）+
  `RegionLumberJob`（巡查→复用一次性 `LumberJob` 砍一棵→继续巡查）+ 常驻（只由显式打断）+
  垂直自适应 + 区域补种（`REGION_REPLANT` ⇒ 账本 `KEEP`）+ 玩家接口
  `/alice region info|start|stop|set|sapling|idle-stop`（D-129/D-130/D-131）。

### 决策缝现状（**距离"LLM 决策层"还差什么**）
- **已有**：候选从哪来（`CandidateSource`）、选哪个且**带理由**（`SelectionPolicy` + `[Job] pick … reason=`）、
  可判读 trace、失败/终止码（`failureReason()` / `terminalReason` / `TaskExecutionRecord` /
  `TaskOutcome` 的 `terminal` + `code` + `failureCode`）。
- **没有**：真正的 LLM 调用、**给 LLM 的权威状态快照契约**、**动作词汇表**（"起哪个 Job + 什么 spec"）、
  **触发节奏**（LLM 不能每 tick 调）。设计文档 §10 明确把这四项登记为"本设计不解决"。

### 项目立项目标的"三条与 Baritone 的差异"现状
| # | 差异 | 现状 |
|---|---|---|
| ① | Bot **可回收性安全策略** | 概念已定义（`RecoverabilityLevel`/`IntrinsicReversibility`、PILLAR 返回守卫 D-058），但**全仓库 0 处读取** ⇒ 仍是空实现（风险清单 P0-B，2026-09-12 复核实测确认） |
| ② | **多层失败上抛 → LLM 决策层** | 数据面齐（见上"决策缝"），**没有消费者**；上抛只到 `BotLog` + `TaskExecutionRecord` |
| ③ | 未来 **bot 并行**接口 | 仅 `docs/MULTI_BOT_INTERFACE_RESERVATION.md` 预留 |

### 历史证据（不再逐条列举）
`.alice-supervision/client-tests/` 下保留 R2–R5、MineTask A/B/C、Movement 物理实验 1–6、各阶段验收证据目录；
改动流水见 `docs/AI_CHANGELOG.md`，稳定裁定见 `docs/AI_DECISIONS.md`（D-001…D-131）。

## 传输模块彻查（2026-09-13）

用户要求"从架构与实现查有没有屎山，并定要不要二次重构"。产出
[`TRANSFER_MODULE_AUDIT.md`](TRANSFER_MODULE_AUDIT.md)：**不是屎山**，三处真问题（生产/测试错位、
死码与只写状态、容器写入无授权维度），核心设计扎实 ⇒ **建议定向重构 R1–R3，不推倒重写**；
`/alice selftest` 建议退役（必崩于无 Mekanism + 与 in-game 电池重复），但 `InterfaceScanner` 的
Mekanism 硬引用是**活雷**，需立即修。

## 当前不要做

- ❌ 不把隧道、搭路、实验性移动**隐式**接入普通任务（D-076：必须显式授权 + 预算）
- ❌ 不把 `SEARCH_LIMIT` 当作 `UNREACHABLE`，也不据此自动授权挖隧道
- ❌ 不实施多 Bot 并行调度（只保留接口边界）
- ❌ 不让 `SOFT_SURFACE` / 实验性 Movement 悄悄进入生产任务
- ❌ 不在没有"预算 + 回收保证 + 残留策略"三件套时发放新的放置授权（§11-① 的 6 项要素）

## 开发工具链

- **源码镜像**：`./tools/mirror-windows-workspace.sh` → Windows `/mnt/d/JAVA_projects/alice/`
  - Windows 是源码镜像，不保留 `.git`
  - WSL `/home/fb486/projects/alice` 是唯一 Git 管理端
- **工件同步**：`./tools/sync-windows-artifact.sh [jar] [windows-repo] [runtime-mods-dir]`
  - 第三个参数显式给出才同步到运行时 `mods`
- **固定客户端**：`D:\JAVA_projects\worldedit-test\versions\1.20.1-Forge_47.4.10`
  - 日志：`logs/latest.log`、`logs/debug.log`
  - 截图/视频：`screenshots/`、`videos/`（如果需要）

## 阶段收尾（2026-09-12 上午：T1–T7 全部收口）

**这一阶段做了什么**：把 9/11 盘点出的 7 项"风险/待办"逐项收口，并且每一步都留下**可复跑的验证入口**。
最大的收获不是那 7 个修复，而是**两条防返工基建**：串联回归电池（一次右键 9 项）+ 离线可规划性自检
（场景改完先 `--all`，不用等客户端）。

| 项 | 内容 | 决策 | 验证等级 |
|---|---|---|---|
| **T1 工具语义** | 生产 `MineTask` 不再凭空发工具；改为只读判定 ⇒ 缺正确工具时 `no_suitable_tool` 如实失败；工具一律由**入口**发 | D-119 / D-120 | `WINDOWS_CLIENT`（`mine_regression` 11/11 含负例 `no_tool_refuses`；`lumber_job` 4/4） |
| **T2 清障换候选** | 一个清障候选失败只跳过该候选（`nextClearStep(excluded)` + `clear_skip`），候选用尽/预算用尽才闩锁；清障子任务信封 = **父信封子集**（`nestedSubTask()`） | D-121 | `WINDOWS_CLIENT`（`clear_retry_check` 连续 3 轮：4 次尝试 = 4 个不同候选） |
| **T3 扫尾预算推导** | ① 扫尾的信封与 tick 预算从**树干高度/待收物数**推导（`sweepGain` / `suggestedSweepTicks`）；先查出身，把 `8`（设计裁定）与 `12`（用户裁定）排除在改动之外 | D-123 | `WINDOWS_CLIENT`（一轮内两个树干高 3/7 的算术均可核对） |
| **T4 作用域归属** | `ScopeBuffer.begin()` **默认继承**「仍活着且在新区间内」的我方掉落物（会话内继承、会话结束 `end()` 清空）；删掉 D-108 手工收养 | D-124 | `WINDOWS_CLIENT`（`mine_regression` 12/12；三个历史病灶点都出现「继承掉落物=1」） |
| **T5 配额解耦** | `assignLumberJob` 用同一套候选源数可行树 ⇒ 配额随场景推导，不再与场景互相标定 | D-125 | `WINDOWS_CLIENT`（`场景可行树=4 ⇒ 配额=4`，后随 T7 变为 5） |
| **T6 离线可规划性自检** | 新增 `tools/check-scene-connectivity.py`：复用 `FixtureWorld`+`stand_candidates`/`can_see` 做**保守下界**可达性泛洪，查「封航线」（有合法站位但全不可达）与「目标格必须可达/必须不可达」 | D-125 | **本机自证**：`--selftest` 双向、`--all` 22 场景无硬伤、历史封死版本 `exit 1` |
| **T7 按棵预算真回归** | 台地东扩 + 第 4 棵同型橡树（`33,64,208`，由「橡树#1」平移得到）⇒ 累计清障 **9 > 8** | D-126 | `WINDOWS_CLIENT`（`trees 5/5 logs 26/26 **cleared=9** scaffoldLeft=0`） |

**本阶段立下的规矩（下次别再踩）**
1. **发料放在「任务」里，不能只放在「物品」里** —— 电池/命令/后续调用者都会绕过物品（D-122 附注）。
2. **「报告」必须等于「世界事实」** —— ② 拆除的 `remaining` 要按现场对账（D-116 附注二）。
3. **夹具加树/加地形必须检查唯一通道**，且**改完先跑 `tools/check-scene-connectivity.py --all`**（D-117 / D-125）。
4. **子任务能力 = 父信封子集**；**一次任务 = 一个作用域**（预算与账本都按此记账）。
5. **噪声 = 隐患**：报警只报可行动的病症；**断言「掉落物是否在账上」必须跨过一次服务器 tick END**
   （当 tick 内查 `liveDrops()` 恒为 0，D-124 附注）。
6. **改动前先查常量出身**：设计裁定/用户裁定（`8`、`12`）与"按场景反推"（旧的 `withGain(8)`、`200`）要分清（D-123）。

**5 分钟复验清单（本阶段全部证据）**
```
alice:regression_battery            # 一次右键：9 项常用回归（clear_retry/write_budget/scaffold/
                                    # clear_guard/lumber_failure/mine_regression/lumber_job/mine_job/pathing）
                                    # 期望：[Regression] SUMMARY … (9/9) → PASS
tools/check-scene-connectivity.py --selftest   # 离线：封死通道必判不可达、留缺口必判可达
tools/check-scene-connectivity.py --all        # 离线：22 场景无「封航线」
```
单独入口：`alice:mine_regression`（**12/12**，含 `no_tool_refuses` 与 `scope_reopen_keeps_drops`）、
`alice:clear_retry_check`、`alice:lumber_job`（`lumber_course` 场景，配额由场景推导，当前 5 棵 /
`cleared=9`）、`alice:mine_job`（`ore_course`）、`alice:lumber_failure_check`、`alice:scaffold_check`、
`alice:clear_guard_check`、`alice:write_budget_check`、`alice:pathing_regression`（18 场景 + 无头断言）。

**J7 收口（Step 1–4 全部 `WINDOWS_CLIENT`）**
- Step 1 生命周期闭环（D-107）｜Step 2 攀爬兜底 + 逐树会话内拆除（D-109）｜
  Step 3 崩溃兜底（D-127：`pendingForOwner` + 就近续做判定/阈值 16 格 + 启动报告，夹具第二轮"故意不拆"验证）｜
  Step 4 失败语义收敛（D-128：`trunk_too_tall` 正名、缺斧前置 `tool_missing`、`climb_incomplete` 分类、
  顶层归因、`scaffold_restore_incomplete` 命名）⇒ `alice:lumber_failure_check` **6/6**。

**J8 收口（区域型 MAINTAIN；D-129 / D-130 / D-131）**
- Slice A 可持续伐木区（`LumberRegionState` + `RegionLumberJob` + `alice:region_lumber` + 电池第 10 步）
  ｜Slice B 区域补种（`REGION_REPLANT` ⇒ 账本 `KEEP`）+ 树苗选择接口 ｜
  D-130 语义修正：**玩家只划水平范围 / 垂直自适应**（`adaptiveTop`）、**常驻**（只由显式打断结束）、
  等生长退避 40→…→600 tick ｜D-131 收尾：三个玩家接口做实 + 三处收尾缺陷。
  验证等级：功能全部 `WINDOWS_CLIENT`（D-130 附注）；三个玩家接口 `/alice region stop`、`/alice region set`、
  `idle-stop=true` **全部 `WINDOWS_CLIENT`**（D-131 附注 / 附注二）⇒ **J8 整条闭合**。
  ⚠ 注意 `idle-stop` 是持久化的：上一轮最后一条命令是 `idle-stop true`，要"常驻"语义先跑一次 `false`。
- J8 收尾后的**体验缺陷**（D-131 附注二已验，`WINDOWS_CLIENT`）：常驻空区域在聊天里**零反馈**
  ⇒ 首次查无活时回一句现场状态 + 怎么收工，终态（`idle_no_work` / `FAILED`）也回聊天（**只提示一次**）；
  `baseline=0` 被当成"没推导" ⇒ 每轮重推刷日志，改为持久化 `baselineDerived` 标记（整会话只打印 1 次）。
- J8 收尾修掉的**真缺陷**（不是"没测"那么简单，见 D-131；其中 ④ 已在客户端**当场抓到** `forward=1.00` 残留）：
  ① `/alice region set` 重划区域不作废旧区域的派生记账（`baseline=5` 污染空区域 ⇒ 永不待机）；
  ② 目标棵数未把"我种的苗"算进去（在"已砍完只剩苗"的地块上启动会退化）；
  ③ 显式打断记成 `CANCELLED_REPLACED`（与文档不符）⇒ 新增 `CANCELLED_BY_USER`；
  ④ `clearTask()` 不清残留移动输入（`/alice region stop` 停在半路 ⇒ "说停了却还在走"）。

**⑤ 路线重规划（2026-09-12，D-147）**：用户要求"先把基层补齐，模组适配只做浅测"。三阶段 ——
**阶段 1 基层收口**（决策层进回归电池 → S4 事件层 → 可回收性不变式接线（项目差异①）→ 决策 trace 落盘与跨重启语义）

**阶段 1 进度（2026-09-12）**：
- ✅ **基-2 决策层进回归电池**（D-149）：电池 9 项 → **16 项**，新增 `decision_contract` /
  `permission_gate` / `pickup_gate` / `collect_job` / `recipes_dump` / `event_thresholds`，插在 `pathing` 之前；
- ✅ **基-3 S4 事件层**（D-150）：`EventThresholds` —— `TOOL_LOW`（斧/镐剩余 ≤20%，回到 ≥35% 才复位）与
  `STUCK`（**有任务 + 有移动意图**且脚位 200 tick 未变；等待态不计时）两类**可行动病症**；写事件环 + 通知 `GoalDirector.onEvent`，
  **同一病症只报一次**（滞回复位才可再报）；自检四例 A 报一次 / B 复位数后再报 / C 卡住报一次 / D 继续不动不刷屏；
- ⏭ **下一个 = 基-1 可回收性不变式**（项目差异①：bot 可回收 = 不留残留、不掉落、不卡地形；**仍是空实现**，
  按 P0-B 顺序先填真实档位再打开检查）。以上两项**已实现已编译**；
  **首次实测（19:08–19:09）电池只跑到 7/16 就被暂停 ⇒ S4 那一步未执行**（D-150 附注一），
  已加单跑入口 `alice:event_threshold_check`（约 25 秒）。**首轮单跑又抓到 3 个夹具/报告缺陷**
  （D-150 附注二：竖井残留方块 ⇒ 维生第 1 tick 打断；`onGround` 同 tick 断言是 stale 值；
  夹具失败时 SUMMARY 把"没跑"报成 PASS —— 已全部修）。**第二轮又抓到 2 个**（D-150 附注三：
  支撑判定公式差一格（floorTop 应为"脚下方块底面+形状高度"）；`onGround` 是**粘滞**标记，
  被 teleport 到刚好贴地 + 每 tick 清零速度 ⇒ 永远读 false，非物理缺陷）。**2026-09-12 19:46 实测：
S4 事件层通过**（A/B/C 三例 PASS、TOOL_LOW×2 + STUCK×1 各恰好一条、用户目视确认 bot 顶壁没动），
并**第一次跑通「事件 → LLM 决策 → 执行」闭环**（LLM 选 `stop_current` 停掉卡死任务）。
新增 `GoalDirector.suspend()` 供自检期间暂停触发（D-150 附注四）。
**2026-09-12 20:13：C/D 用例 PASS，S4 四例全部有实测证据**；A/B 失败系**夹具不可重入**（已加归一化阶段）。
用户裁定 A/B 两项均做：**A** 自检窗口内事件只记录不通知决策层；**B** `BotManager.busyMessage` 统一
"bot 正忙"文案（49 处，常驻任务直接给 `/alice region stop`）—— 见 D-150 附注五。
**2026-09-12 20:40 收尾：S4 四例全 PASS + `terminal=COMPLETED` + 用户目视确认 ⇒ `WINDOWS_CLIENT`**
（D-150 附注六）；"悬空不下落/onGround=false"真因 = 夹具每 tick 清速度（附注三已订正）。
**基-9 工具与耐久管理第一批已完成（D-156 + 附注一/二，`WINDOWS_CLIENT` 全绿）**：`ToolSupply`（"现在能用什么 / 还能换成什么"，
快捷栏优先与生产选工具口径一致）+ `ToolMaintenanceTask`（4 种终态，含 `worn_no_spare` 如实上报）+
决策动作 `maintain_tool`（`TOOL_LOW` 之后 LLM 终于有能自己解决的选项）+ 工具事实进 prompt/汇报。
**未做**：工具来源（S6）、"耐久不足以完成计划工作量时提前拒绝"。

**基-8 授权登记缺口已完成（D-157 三附注，`WINDOWS_CLIENT`）**：G8 能力闸门（10 个死字段中 6 个变活，
含"保护区字段又变装饰"的真问题修复）、A9 登记表断档补齐、G3 外来破坏留痕、G5 容器写入留痕
（自检 10 项全 PASS）。
**基-7 内核残余第一批（K-1）已完成（D-158，待客户端复测）**：`PlanningStatus.PARTIAL` +
best-so-far 前缀 + `PathRetryRunner` 消费（先走前缀再重规划），顺带让 K-5 的死状态在新内核变活。
**K-3 `safeToCancel` 已完成并客户端验证（D-166，`WINDOWS_CLIENT`）**：运行期安全承诺点
（`MovementExecution.safeToCancel()` 默认 true + 8 个执行器覆写）+ 延后停止 + L2 开菜单空中门；
`alice:k3_stop_check` 的 DEFER 用例实测通过（`deferred=1`）。
**K-4 谓词不统一已完成"统一 + 测量 + 收口"三步（D-167 + 附注一，`WINDOWS_CLIENT`）**：
"可站"谓词原先在 6 处各写一遍、**目标准入一遍没查** ⇒ 现统一为 `MovementHelper.canStandCentered`
（8 个调用点，纯重构）；目标准入**不硬拒**（"起点即目标"合法 + 挖掘 `ENTER_TARGET` 的 goal 就是矿块），
改为进程累计遥测（`bot_report` 的"目标准入（K-4 累计）"行）+ 回归电池 SUMMARY 自断言 `K4=OK/VIOLATION`。
**K-4 遥测已在真实运行中被使用（`SERVER_TESTED`）**：`目标准入（K-4 累计）：goal_post_write_not_standable=89
final_segment_target_post_write=51` —— 两类**真异常码 0 次**（`[K4]` 告警 0 行），
写入类例外 89 次 = 设计如此；完整电池跑的 `K4=OK` 自断言仍待一次**跑完**的电池。
**D-168 夹具断言不得依赖世界历史**（用户实测"不清理掉落物就失败"）：`mine_regression` 的 `dropsLeft`
原先数"±6 盒内全部掉落物" ⇒ 世界残留导致假失败（run1 `collected=1/1` 却 `dropsLeft=1`，run2 手动清后 PASS）；
改为**基线 UUID 增量**（残留报 `foreignDrops=`，不计入判据）+ 三个挖掘场景函数补 `kill @e[type=item,…]`。
**D-169 电池自杀事故（第三轮实测的真因，已修）**：K-3 夹具嵌在电池里时，`stopTask` 停的是
**顶层任务 = 电池自己**（`停止请求延后到安全点：task=RegressionBatteryTask` → 第 12/25 步 CANCELLED_BY_USER）
⇒ 三轮电池都没有 SUMMARY。修法：K-3 **退出电池**（25 → 26 项）+ `fixture_not_top_level` 前提断言
（绝不再伤父任务）+ 手工入口扩成**右键 DEFER / Shift+右键 FORCED**（FORCED 此前从未被验证）。
一般规矩：**夹具不得对父任务产生副作用**，需要观察"顶层任务被停"就必须做成顶层入口。
**第四轮实测（WINDOWS_CLIENT）**：26 项电池**全 PASS** `(26/26) ticks=3507 → PASS`，
其中 `K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=88)` ⇒
**K-4 收口完成**（按"0 ⇒ 删临时告警"的义务删掉两处 `[K4]` 告警行，保留计数 + 电池自断言；
结论：缝真实存在但实战不咬 ⇒ 不引 `GOAL_NOT_STANDABLE` 硬拒）；
`mine_regression` 11/11 PASS ⇒ D-168 掉落物修复生效（无需手动清理）。
**D-170 资源缺陷（客户端日志才发现）**：`k3_stop_check`/`menu_probe`/`transfer_check` 三个模型是
**0 字节**（紫黑块/看不见 —— 这大概率就是 K-3 自检一直没被测的原因），`partial_search_check` 还指向
不存在的贴图；已补齐/改正 + 新增 **`tools/check-item-models.sh`**（空文件/坏 JSON/死贴图引用，脚本自测过）
并写进构建前检查清单。**未验证**：这 4 个物品贴图的实际显示（需看一眼背包）。
**余项**：K-2 legacy 活引用（`SurfacePathfinder` 7+ 处）、K-5 遗留枚举；
**K-3 自检（DEFER 回归 + FORCED 新入口）仍待测**（道具这轮才修好，之前看不见）。

**基-5 LLM 上抛契约已完成（D-155 + 附注一，`WINDOWS_CLIENT`）**：四个 Job 覆写 `failureReport()`（决策层能看到相位/进度）、
`MineProductFilter`（挖掘产物**目标驱动 + 标签族**，不再硬编码原版矿物、且尊重 `productTag`）、
**结构化拒绝回读**（`lastRefusal` 进下一轮 prompt + 进汇报 + 动作被接受后清除）。

**基-4 决策 trace + 跨重启语义已完成（D-154 + 附注一，`WINDOWS_CLIENT`，含一次真实跨重启验证）**：`DecisionTrace`（JSONL 落盘 + 内存尾、
超限轮转、写失败不影响决策）+ `DecisionState`（登记"重启会丢的未决请示/当前任务"，启动时**只报一次**）。
裁定：未决请示**作废但必须报出**；任务**不自动续做**但如实汇报；不做任何"看起来恢复了"的自动恢复。

**基-1 可回收性不变式（项目差异①）已完成两条轴并客户端验证（D-151/152/153 + 附注）**：：① 逐步"有回程"（D-151 测量 / D-152 逐边事实＋策略表，
**已客户端验证**）；② 活动"没留残留"（**D-153：残留 = 可回收性失败**，计数 + `RESIDUE` 事件 + 夹具自清场）。
**第一步已完成（D-151）**：`RecoverabilityEvaluator` 按类型给出
"等级 + 依据"、provider 8 个产出点与 `AStarMovementSearch` 改为取值、唯一转换点采用评估值并互相核对、
`RecoverabilityReport` 记账并在会话完成时打印；电池第 17 步 `recoverability` 四例（含**负例**：
`required>evaluated` 必须抛异常 ⇒ 证明 P0-B 的恒假校验已变活）。本机纯逻辑实测 `GUARD=LIVE`、
`TOSPEC_FALL evaluated=PATH_REVERSIBLE`。**`required` 一律未动 ⇒ 行为零变化**。
**实测（D-151 附注一）**：自检四例全 PASS（含"`required>evaluated` 必须抛异常"的负例 ⇒ 校验已变活）；
真实计划 12 会话、10 种 Movement 全覆盖、`distinctLevels=2`；寻路回归 **13/13 PASS 无退化**。
**第二步已实施（D-152）**：可回收性评估改为**逐边事实**（`RecoverabilityFacts` 穿搜索到执行期）+
`RecoverabilityPolicy` 策略表（`FALL>=PATH_REVERSIBLE`）⇒ 本机实测"**不带返回守卫事实的 FALL 边被拒绝**"，
即 D-036 差异① 第一次真的改变准入结果；自检新增 `fall_without_fact_refused` 负例。**待客户端复测**
→ **阶段 2 真实数据浅测**（挑两个模组，只验"运行时导出 + P1 读得懂多少"，不写适配器）
→ **阶段 3 能力扩展**（S6 Craft/Smelt → 机器适配器 → 偏好规则；多 bot 并行另立项）。完整缺口表见 D-147 与总账。

**④ 决策层框架（2026-09-12 规划）**：`docs/DECISION_LAYER_DESIGN.md` —— 三条通道
（**决策 / 汇报 / 请示**）+ 契约 + 分步骨架 **S1 事实层 → S2 选择层 → S3 请示层 → S4 事件层 →
S5 知识层(只读配方图) → S6 执行层(Craft/Smelt/Process + 偏好规则)**；S0（契约与管道）已 `WINDOWS_CLIENT`；
**S4 事件层已实现（D-150，待客户端验）**。
知识/配方面的参考材料见 `docs/KNOWLEDGE_RECIPE_GRAPH_NOTES.md`。

**① 安全底座小批次已完成（全部 `WINDOWS_CLIENT`，D-132 附注三）**：S-1 维生出口 / S-2 未加载区块·边界准入 /
S-3 删重复维生调用 / S-4 流体探针接线 + 起点脱困（D-133）。**② 决策层接入进行中**。

**① 的细节（D-132）**
—— S-1 维生出口（`SurvivalExitTask` + 纯查询 `nearestSafeRefuge` + 逃生豁免）、
S-2 未加载区块/世界边界准入（新状态 `GOAL_NOT_LOADED` + 跨区块节点门控，照 Baritone）、
S-3 删 `MineTask` 重复维生调用、S-4 接上 `FluidRiskPolicy`（硬拒不许再加高/清障）。
入口：`alice:survival_exit_check` / `alice:chunk_guard_check` / `alice:fluid_mine_check`（零参数右键）。
**客户端待测**；测完进入 **② 决策层接入**（先补 Job 契约：`terminalReason`→`TaskOutcome`、`botUuid`、
`GoalSpec`→`Job` 统一入口）。

**下一步（按建议优先级；**完整未收口项与方向候选见 [`OPEN_ITEMS_LEDGER.md`](OPEN_ITEMS_LEDGER.md)**）**
1. **未覆盖的行为分支（与 T2/T3 同批登记）**：几何不可达导致的「首候选失败、次候选成功」场景、
   清障子任务**加高**行为、`trunkHeight+1>12` 截断、① 扫尾超时分支、`climb_incomplete` 场景、
   `too_far` 恢复分支、真实崩溃重启路径。
2. **T6 盲区**：`19/24` 那种「本来就看不见目标、必须清障」的目标只给软提示 —— 其"清障是否可行"
   仍归 `analyze-lumber-scene.py`（且不计可达性）；两项合一才算完整。
3. 历史登记项：G3（模组连锁破坏无凭证）、G5（容器写入维度）、G4 Slice B2（尝试级 tick 预算）、
   `isExpensiveToClear` 成本化 + `#alice:clear_forbidden` 标签、`MiningBudget.tierOf` 的 `#forge:ores/*`。
4. 可选（J8 未做的小项）：区域"目标密度"的手动配置接口（现在自动推导 = 首次巡查的 standing）、
   选区魔杖（右键记 pos1 / 潜行右键记 pos2 ⇒ 免坐标命令的零参数入口）。

## 会话收尾（2026-09-13）

**本会话主线**：阶段 1 基层收口（基-1/2/3/4/5/7-K1K2/8/9 大部分）+ **L2 方块交互路线**
（对比 → 探针验证 → `MenuSession` 组件 → 生产化）+ **传输模块彻查与重构**（R1/R2/R3 + L1）。
详见 `AI_DECISIONS.md` 的 D-151…D-165 与 `TRANSFER_MODULE_AUDIT.md` / `INTERACTION_LAYERS_COMPARISON.md`。

**唯一未收口**：`alice:transfer_check` 的 `end_to_end` 用例（根因已定位并修：**背包索引 ≠ 菜单槽位号**，
D-165 附注四），**待客户端复测**。判据与排查入口见 `OPEN_ITEMS_LEDGER.md` §6.11。

**本轮（K-3 之后）四条收口线**，全部有客户端证据：

| 线 | 结果 | 等级 |
|---|---|---|
| **K-4 谓词统一**（D-167 + 附注一） | "可站"谓词 6 处复制 → 唯一定义 `MovementHelper.canStandCentered`（8 调用点，纯重构）；目标准入先测量不硬拒；完整电池 `K4=OK(真异常 0 / 写入类例外 88)` ⇒ **收口**：不引 `GOAL_NOT_STANDABLE`，删临时告警、留计数 + 电池自断言 | `WINDOWS_CLIENT` |
| **D-168 夹具测量** | `mine_regression` 的 `dropsLeft` 原本数"盒内全部掉落物"⇒ 世界残留假失败（run1 两次 FAIL 只因 `dropsLeft=1`）；改**基线 UUID 增量** + 三个挖掘场景函数清实体 ⇒ `mine_regression` **11/11 PASS**，用户不必再手动清掉落物 | `WINDOWS_CLIENT` |
| **D-169 电池自杀事故** | 电池里的 `k3_stop_defer` 停的是**顶层任务=电池自己**（三轮电池都没 SUMMARY 的真因）；修：K-3 退出电池（25 → **23** 项）+ `fixture_not_top_level` 前提断言 + 手工入口扩成右键 DEFER / Shift+右键 FORCED | `WINDOWS_CLIENT`（电池 26/26 PASS） |
| **D-170 资源缺陷** | `k3_stop_check`/`menu_probe`/`transfer_check` 模型是 **0 字节**、`partial_search_check` 引用死贴图 ⇒ 客户端缺失模型/紫黑块（**K-3 一直没被测的真因**）；补齐修正 + 新增 `tools/check-item-models.sh`（空文件/坏 JSON/死贴图，已自测）并写入构建前清单 | 建模修正 `COMPILES`；**客户端 0 条 `Failed to load model alice`**（本轮日志）⇒ `WINDOWS_CLIENT` |

**K-3 自检（DEFER + FORCED）客户端验证通过**（2026-09-13，`WINDOWS_CLIENT`）：
`[K3] mode=DEFER … task=K3StopCheckTask` → 请求后仍被 tick(15) → 落地后 `已到安全点，执行延后的停止`
（terminal `cancelled:k3_defer:safe_point`，25 tick）；FORCED 同一入口 Shift+右键 →
`任务在不安全时刻被强制停止`（terminal `cancelled:k3_forced:forced_unsafe`，33 tick）；
`bot_report`：`deferred=1 forcedUnsafe=1 survivalUnsafe=0`；`目标准入（K-4 累计）：无异常计数`。

**K-2 第三批 + K-5 已完成（D-171/D-172，`COMPILES`，待电池回归）**：
legacy 双内核整批删除（19 文件，依据"按路径分析的零活引用"）；`POSTCONDITION_FAILED` 不删值、
改为给生产者（`PathSessionStatus.classify` 唯一定义）并加"无死值"自检。

**最新复测（客户端 12:42，全绿）**：电池 `(26/26) ticks=3301 → PASS`、`K4=OK(真异常 0 / 写入类例外 83)`、
**22/22 个终态步 `idempotent=true`**、四个 exec 用例 `foreignOk=true(另有残留1件不计入)`
⇒ D-168"残留不计入"分支**首次全量实测通过**；`entity_tick_missing` / `segment_stall` / 异常均 **0**。

**未闭环最高优先 = P0 假人物理冻结（D-176）**：任务/会话跑在全局 `ServerTickEvent.END`，而 `BotPlayer.tick()`
是**实体 tick**（受区块 entity-ticking 影响）⇒ **不同源**，正好解释"任务在跑、bot 一格不动、无报错"
（`segmentTicks=121 / entityTicksInSegment=0 / travelCallsInSegment=0`）。看门狗已带
`entityTicking=` 判别位，**待复现取现场**后定修法（不许先加"补 tick"特判）。

**（历史）第四次电池（22/23）**：唯一失败 = `transfer` 步 `end_to_end` 间歇不动的**同一病因**（即上面的冻结）。
**D-173 补漏**：`pathing/movement/` 14 文件（外部真引用 0）整包删除 + `.gitignore` 藏住的
`PathExecutor.java.backup` 删除。

**最新实测（2026-09-13）**：CORE 电池 **`(23/23) ticks=2616 → PASS`**（FULL 33 项 3527 ⇒ 短 26%）；
A4 熔炉客户端 PASS 且夹具**自复位**（`burnLeft=1398 → 重建方块=true`）。
**阶段 3-A 剩余**：A4b（菜单型炉子=熔炼升级页签，复用 `FurnaceStation`）、A5（决策层接线 `GoalAction.Craft`）。

**回归电池分档（最新，D-197）**：CORE = **BASELINE 13 + MAIN 10 = 23 项**（默认，物品
`alice:regression_battery` 或 `/alice battery core`）；FULL = 33 项（`/alice battery full`）；
`/alice battery list` 看归属表。配置唯一入口是 `RegressionBatteryTask.CURATION`，说明书
`docs/BATTERY_CURATION.md`（**AI 负责维护**：新增场景进 MAIN、验收退场移 EXTRA、换主线重写 MAIN）。
归属表与实跑项不一致 ⇒ **电池判红**（防"文档说测了其实没测"）。
**A4 熔炉已客户端 PASS**（`smelted=true stone+1` / `cobblestone-1` / `no_half_products=true` / `fuel_burn_ticks=1600`）。

**阶段 3-A「工作站」大块收口（2026-09-13，客户端全绿）**：通用发现（随身 2×2 / 工作台 3×3 /
精妙存储"合成升级页签"3×3，**零模组专属代码**）、工作站可切换（`/alice craft station`，不自动选优）、
只读探针（`alice:craft_grid_probe`）、执行接入发现器（B，D-193）、装配层装/拆（A，D-194）、
模组站点**真合成**（C，D-195）—— **32 项电池 `(32/32) ticks=3592 → PASS`**。
实测语义：产物**进容器**（`product_in_container=1`）、**不自动补料**、握升级右键容器=物品自己装进去（GUI 不开）。

**阶段 3-A / L2 工作站装配（D-194，客户端已验证）**：新增 `task/craft/StationProvision`（装配那一层：
QUICK_MOVE 装入 / 取回；**落点不猜、地址不猜**）+ 夹具 `CraftStationProvisionCheckTask` +
零参数入口 `alice:craft_station_provision_check` + **新写入理由 `STATION_PROVISION` 与 A 表 A13**（容器写入维度）；
电池 30 → **31 项**（`craft_station_provision`，模组不在 ⇒ SKIP）。场景 `craft_tab_course` **不再给玩家发升级**。
此前 B（D-193）已把合成执行接入发现器（`InventoryCraft`/`TableCraft` 不再看写死下标），
电池 (30/30) ticks=3383 复测通过。

**阶段 3-A / S1 合成工作站可切换（D-192，客户端已验证）**：新增 `task/craft/GridDiscovery`（通用网格发现，
只用原版 `CraftingContainer`/`ResultContainer` 判据，零模组知识）、`task/craft/CraftStation`（站点描述符 +
`/alice craft station` 切换 + 候选事实，`auto` 不含升级页签 ⇒ 不自动选优）、`task/CraftGridProbeTask` +
`alice:craft_grid_probe`（零参数只读探针）、场景 `alice_test:craft_tab_course` 与诊断
`alice_test:craft_tab_snapshot`。精妙存储的"合成升级页签"是**第二个站点范例**（take/source 未实测 ⇒ `UNKNOWN` 只读）。
新登记条件副作用：开/关精妙菜单可能按玩家设置清掉 `openTabId` ⇒ 探针只读断言如实限定。

**阶段 3-A 合成阶梯（最新）**：A1（`alice:craft_check`）/ A2（`alice:craft_action_check`）/
A3（`alice:craft_table_check`，含 `no_world_write` 零写入硬断言）**客户端均 7/7 PASS**（2026-09-13）。
**A3b 自放工作站已实施、待客户端**（D-190）：`task/craft/StationPlacement`（授权入口 **A12**、
`WriteReason.CRAFT_STATION_PLACE`、账本记 `TEMP` ⇒ 受"建拆同权"约束）+ 夹具 `alice:craft_station_check`
+ 场景 `alice_test:craft_station_course`（**故意没有工作台**）+ 电池 26 → **27 项**（`craft_station` 步）。
判据：`station_placed`（世界事实）/ `write_accounted`（账本 TEMP）/ `placed_table_craft` /
`teardown_clean`（**方块回空气 + pending=0**）。A4（熔炉）、A5（决策层接线）未做。

**本轮环境事实**：电池现 **27 项**（上一轮 26 项时为 `(26/26) ticks=3507 → PASS`，本轮新增 `craft_station` 步）；`k3_stop_check` 等 4 个道具贴图已正常；
镜像/同步脚本正常；`tools/check-item-models.sh` 已接入构建前清单（`checked=66 … PASS`）。

**环境提醒**：镜像脚本 `tools/mirror-windows-workspace.sh` 现为"默认不备份/不校验"快跑（8.6 秒）；
备份轮转由 `ALICE_BACKUP_KEEP`（默认 2）控制；`ALICE_MIRROR_BACKUP=1` / `ALICE_MIRROR_VERIFY=1` 可按需开启。

## 交接入口（新会话从这里起）

> **** = 当前交接文档（主线、已验证清单、进行中的卡点与下一步、关键入口与环境、纪律提醒）。
> 新会话先读它，再读 （电池分档）与  末尾几节（最新实测事实）。

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
