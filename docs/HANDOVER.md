# Alice 项目交接说明（给新会话的快速上手）

> 用途：另一个对话/会话接手本项目时，先读本文件 + README + 设计文档，即可快速进入工作。
> 更新：2026-08-19（寻路系统重构基线：曲面 A* 优先 / 通道独立设计 / 掉落物与目标簇策略）
> 重要：本会话所有改动已同步到 Windows 端仓库（`origin` = `/mnt/d/JAVA_projects/alice`），
> 最新提交 `daf07d4`。**每次代码修改完成后必须 `git push origin master`**，用户靠 Windows 端实测。

## 〇、当前 Git 状态（重要）

```
daf07d4 fix: bound mine task blocker clearing             ← HEAD（MineTask 最多两格直接清障）
d5424de refactor: restore surface-first mining paths      （撤回 RoadMineTask，单目标恢复纯曲面 A*）
7e00c32 fix: separate geometric route cost from tunnel execution
2b32c6c feat: route mining through weighted road tasks    （已撤回的混合任务实验，不要复活）
7bf3c52 feat: add weighted hybrid road pathfinding        （已撤回的混合任务实验，不要接入采集）
9b00130 docs: rewrite handover with road/bot/collection session state
3f4893d fix: collect only explicit target drops
```

WSL 工作区路径：`~/projects/alice`；Windows：`D:\JAVA_projects\alice`（`origin`）。
GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push 前先 `git fetch github` 查冲突）。

## 快速接手（1 分钟）

先读本文件、`docs/PATHING_REFACTOR.md`、`docs/EXECUTION_FRAMEWORK.md`，再运行 `git status`。当前稳定基线是：钻石斧、`/alice mine`、`/alice auto-mine` 全部创建 `MineTask`，仅使用现有 `AStarPathfinder + PathExecutor` 在真实可通行曲面上移动；最多从当前站位直接清理两格、4.5 格内的视线 blocker，其他深埋目标必须失败为 `target_requires_tunnel`，不得乱走或隐式搭/挖通道。`RoadPlan`/`RoadBuilder`/`RoadBuildTask` 仅服务手动道路蓝图，不能重新接入普通挖矿。下一个实现目标是先封装 `SurfacePathfinder`，再定义独立不可变 `TunnelPlan`/`TunnelPlanner`，且只有所有合法挖掘站位的曲面路径都失败才允许通道；掉落物永不启动长通道，目标簇未来以一次簇级通道到簇外围、簇内局部开采实现。修改后必须 `./gradlew compileJava` 并 `git push origin master`。

---

## 一、项目是什么

**Alice** —— Minecraft Forge 1.20.1 模组（mod_id: `alice`，包 `com.dddgn.alice`）。
目标是做一个「游戏内 AI 助手」：服务端假人玩家能像真实玩家一样感知世界、决策、执行任务。
**设计核心**：LLM 只做目标级决策，确定性执行器负责可靠完成；服务端权威数据，
GUI 背后操作序列化为语义接口（而非视觉点 GUI）。

设计文档：`docs/AI_PLAYER_DESIGN.md`（架构总纲）· `docs/EXECUTION_FRAMEWORK.md`（执行层框架）·
`docs/MEK_GUI_SEMANTICS.md`（Mek GUI→接口语义表）· `docs/ROAD_MATHEMATICAL_MODEL.md`（道路数学模型）·
`docs/BARITONE_PORTING_CHECKLIST.md`（Baritone 移植清单）

---

## 二、当前进度（2026-08-19）

| 模块 | 状态 |
|---|---|
| 假人玩家化 | ✅ `BotPlayer` 继承 ServerPlayer + 伪造连接经 `PlayerList.placeNewPlayer` 注册 |
| 移动 | ✅ 位置步进（无重力，服务端不跑玩家物理）；`PathExecutor` 手动 setPos + setOnGround |
| 挖掘 | ✅ `BotMiner`：站位候选逐个尝试、视线无遮挡硬检查、挖掘时朝向目标（身体+头部+俯仰） |
| 放置 | ✅ `PlaceTask`：候选站位用 Baritone 风格判定（可站/可过/距离/A*），最终对目标空气中心 raycast 做视线检查；放置前 `faceTarget` 朝向目标（与 BotMiner 一致） |
| 道路规划 | ✅ 新框架：`ContinuousRoadCurve`（连续曲线候选生成）+ 体素硬验证；选择链见 §三 |
| 道路构建（玩家版） | ✅ `/alice road build`：`RoadBuilder` 逐单元施工 + 5 tick 稳定检测，先清净空后铺支撑 |
| 道路构建（bot 版） | ✅ `/alice road buildbybot`：`RoadBuildTask` 强制施工动画，逐单元构建并平滑前进（见 §四） |
| 拾取/收集 | ✅ 已收紧：**任何采集任务只收集显式目标方块 origin 的掉落物**（见 §五） |
| 单目标挖掘 | ✅ 斧头目标、`/alice mine` 与 `auto-mine` 已恢复为 `MineTask`：只走真实曲面 A*，绝不隐式挖/搭通道 |
| 感知层 | ✅ `PerceptionSnapshot` + `ScopeBuffer`（任务作用域：掉落物 origin 追踪/方块事件） |
| 决策层 | ✅ `AutoMineDecision` 最小规则（标签/方块 ID → 最近 → 执行；LLM 接入点预留） |
| 接口扫描 | ✅ `InterfaceScanner`：Forge/Mek capability + Mek GUI 页签统一扫描 |
| 客户端高亮 | ✅ 目标透视高亮（自定义 RenderType 关深度测试）；道路蓝图蓝色外轮廓 |
| 世界存档 | ✅ bot 位置/主手物品存 SavedData，重启恢复；死亡反馈+清除 |
| 安全区 | ✅ `SafeZoneData` 持久化：区域/方块 ID/方块标签三类保护；破坏/清障硬拦截 |
| 自动验收 | ⚠️ 13 场景 selftest（10/20/30 秒预算），最近实跑 FAIL 基线保留（TEST5/6/7/9），**道路场景未纳入 selftest** |

---

## 三、道路规划：连续曲线候选框架（本次会话核心改动）

### 3.1 选择链（`RoadPlan.buildUnits`）

```
1. selectContinuousRoute：ContinuousRoadCurve 生成直线 + 两侧二次 Bezier 候选（法向偏移 0~24 格，步长 2）
   → 逐条做体素硬验证（净空/禁区/对角侧格/每步 |dy|<=1/禁止重复水平投影）
   → 首条通过者作为普通路线
2. 否则：verticalDistance > 0 → spiralCompensationRoute（2×2 螺旋原语，保留旧逻辑）
3. 否则：shortestVoxelRoute（A*）作为低高差障碍后备
```

- 日志关键字：`连续道路候选生成` / `连续道路候选 i/n: offset= arc= units= valid=` / `道路路线选择: mode=normal|spiral` / `螺旋路线生成`
- 诊断定位法：先看 `valid=` 是否全 false（曲线形状问题），再看是否进了螺旋（高差接管），再看最终失败原因。

### 3.2 连续曲线体素化（`ContinuousRoadCurve`）

- 曲线按 `ARC_SAMPLES=2048` 采样 → **水平采样点之间用 `appendGridSegment` 逐格补齐 4/8 邻格**（supercover 风格）→ 再按格线序号分配高度。
- 每步检查：`dx/dz <= 1`、`|dy| <= 1`、禁止纯竖直、禁止重复 `(x,z)` 投影、起终点必须等于 start/goal。
- 返回 `Candidate(route, lateralOffset, horizontalArcLength)`，弧长供后续成本/曲率标定。
- ⚠️ 已知边界：Bezier 单弧无法绕复杂障碍；未做曲率/累计转角约束（用户提出的“半圆绕行上限”尚未实现）；候选全失败时靠螺旋/A* 兜底。

### 3.3 本会话已移除的临时逻辑（勿复活）

- ~~`isAcceptableNormalRoute`（拒绝 A* 路线重复水平投影）~~ —— 曾导致高差场景 `no_walkable_route`，已删除。
- ~~`hasUnambiguousCellOwnership`（支撑格与其他单元净空重叠即蓝图失败）~~ —— 曾导致 `routeUnits=29` 误拒绝，已删除；末端冲突由 Builder 先清后铺 + 路线连通性处理。

### 3.4 RoadBuilder（玩家版）注意事项

- `buildUnit` 现在是**先清净空、后铺支撑**（同一单元内顺序固定），防止清出的空腔被后续支撑重新堵上。
- 构建按当前世界状态判断，不依赖规划快照；清障仍走 `BlockBreakSafety.clearingRefusal`。
- `hasFallingMaterial`/`removeFallingMaterial` 扫描范围一致（support ±1 × headroom+6 柱体），沙砾 3 次不稳定即停止。

---

## 四、RoadBuildTask：bot 道路施工（动画式强制构建）

### 4.1 用户明确要求的行为语义（务必遵守，不要退回“真实交互”模式）

> “通道构建只是个动画过程，bot 强行构建、强行移动。只要工具能挖那个方块，就强制挖掘，不管实际有没有被遮挡、距离够不够；搭方块只要有方块就一定能搭，不判断实际能不能搭。只有特殊条件（没有工具、工具挖不动、没有搭路方块）才停止。要让过程看起来真实、流畅，同时产出的通道让真实玩家也能通过。”

### 4.2 状态机（`task/RoadBuildTask.java`）

```
BUILD_UNIT → WAIT_STABLE → MOVE_TO_NEXT_UNIT →（循环）→ MINE_TARGET → DONE
```

- **BUILD_UNIT**：每 tick 只处理一个净空方块（`collectClearance` 取单元 cells 中非 support 的坐标）：
  - 目标方块 `plan.second()` 保留不拆；
  - 非空气格用 `forceBreak`：`getDestroyProgress(bot, level, pos) <= 0` 才失败（工具挖不动），否则 `faceTarget` + swing + `level.destroyBlock(pos, true, bot)`；
  - 净空清完后支撑格为空气则 `setBlock(COBBLESTONE)`（**无限圆石**，不查背包）；
  - 起点方块不单独挖——它是第一单元净空的一部分，首个单元构建时就清掉了。
- **WAIT_STABLE**：5 tick 稳定检测（FallingBlock 实体/方块扫描），不稳定最多重试 3 次。
- **MOVE_TO_NEXT_UNIT**：平滑位置步进（同 PathExecutor：0.25 格/tick，到点 setPos+onGround），**不做 A*/碰撞/视线判定**。
  - 第一单元建好后：`moveDestinationIndex = 0`，先站进第一格；
  - 之后每建完一单元：移动到下一单元；
  - **最后一个单元建完不移动**（最终支撑格上方就是未挖的目标方块，不能站进去），直接从当前缓冲站位创建 `MineTask(bot, plan.second(), scope)` 挖目标。
- **MINE_TARGET**：复用普通 MineTask（含只收集目标产物逻辑）。
- 移动中不因“路径受阻”失败（不走 PathExecutor 的 canWalkThrough 检查）。

### 4.3 命令

```
/alice road buildbybot   要求当前维度已有完整道路蓝图；无 bot 自动生成在 plan.first()；
                         bot 忙则拒绝；分配 RoadBuildTask
```

### 4.4 尚未做的事

- bot 主手工具在 RoadBuildTask 里**没有自动换**（MineTask 会换钻石镐，但道路施工的净空清理用的是 bot 当前主手，`getDestroyProgress` 挖不动就会失败——用户说这算“特殊条件”，可接受；后续可加“施工时自动换工具”）。
- 圆石“无限提供”目前是直接 setBlock，未走背包/物品实体逻辑；后续背包管理上线时需改。

---

## 五、掉落物收集收紧（最新改动 3f4893d）

### 5.1 规则（用户明确要求）

> “任何采集任务，都只收集主动采集目标的掉落物。”

### 5.2 实现（`DropCollectionTask`，由 `MineTask` 委托）

- 进入 COLLECTING 后，由 `DropCollectionTask` 从 `scope.liveItemsFromOrigin(target)`（作用域捕捉时来源格 == 目标方块）收集 UUID 到 `primaryItemIds`；父任务仍拥有作用域生命周期。
- **不再有 `allItems` 回退**：清障副产物、遗留物、其他玩家掉落物一律不收集。
- 空捕获保护：若 30 tick 内 `primaryItemIds` 仍为空，视为“目标无产物”结束（防延迟生成漏捡）。
- `items` 恒为 `liveItemsFromOrigin(target)` ∩ `primaryItemIds`；为空即 DONE。
- 后续的粘性目标/64 格放弃/格心拾取/forcePickup 逻辑不变。

---

## 六、寻路重构（已定稿，混合任务已撤回）

### 6.1 数学模型（用户原话整理 + ROAD_MATHEMATICAL_MODEL.md §五）

- 目标：**时间成本最低 = 加权最短路**。
- 空间可分层：
  - **曲面（可走区域）**：bot 可以用 Baritone/A* 直接“走过去”的区域，视为三维空间中的曲面，**时间成本低**；
  - **通道（挖过去/搭过去）**：跨越不同曲面之间的连接，需要挖/搭（两者视为**相同成本**），**时间成本高**；
  - 空间里还有其他曲面与避障区域。
- 通道的时间成本**只看数学模型里线段/曲线的长度**，忽略实际构建通道的误差，简化计算。
- 整体：bot 在起点曲面用 Baritone 到达通道入口（动点），通过通道（线段/曲线）到达目标所在曲面的动点，再 Baritone 到目标。
- 数学上可看作**多层加权图**：曲面内边 = 低权，通道边 = 高权；需要快速求较小时间成本路线（不必全局最优，但要好）。

### 6.2 当前第一版：SurfacePathfinder 语义

- `AStarPathfinder + SurfacePathfinder + PathExecutor + MineTask` 是唯一默认单目标链；它只走真实可通行曲面，成功后直接挖目标；`SurfacePathfinder` 返回 `REACHED` / `UNREACHABLE` / `SEARCH_LIMIT` 及扩展节点数，预算耗尽绝不能被当作无路；
- 钻石斧目标选择、`/alice mine` 与 `/alice auto-mine` 都进入该链，不再创建 `RoadMineTask` 或调用 `RoadPlan`；
- `MineTask` 只允许当前站位直接可见且在 4.5 格内的最多两格局部清障；其余深埋目标失败为 `target_requires_tunnel`，绝不反复重选站位挖长通道；
- 掉落物继续只收 `liveItemsFromOrigin(target)` 锁定 UUID，路径仅允许曲面 A* 与有限侧向下行阶梯恢复，绝不自动启动长通道；
- 手动 `RoadPlan`/`RoadBuilder`/`RoadBuildTask` 保持为独立道路蓝图功能，当前不参与挖矿任务。

### 6.3 后续：独立 TunnelPlanner 与目标簇

- 仅当所有合法挖掘站位均明确 `UNREACHABLE` 时，才允许独立 `TunnelPlanner` 枚举曲面入口/出口并生成不可变 `TunnelPlan`；任何 `SEARCH_LIMIT` 必须交给决策层重试/换目标/客户端确认；
- 总成本 = 起点曲面移动 + 数学通道几何长度 × 因子 + 出口曲面移动；实际施工格数、支撑和重试不参与规划成本；候选先经过独立 `TunnelObstaclePolicy` 的保守硬验证（拒绝流体、保护/不可破坏/高代价方块、断裂和纯竖直几何）；
- `TargetCluster`/`ClusterMineTask` 对同类 6 邻接目标做簇级一次全局规划，通道只能抵达簇外围，禁止侵入簇膨胀一格 AABB；簇内只做局部曲面寻路/有限清障；
- 完整规范：`docs/PATHING_REFACTOR.md`。

### 6.4 相关待办

- 目标簇 AABB 保护（全局路线不侵入簇，簇内局部采集分离）；
- L1/L2：工具损耗线性近似、材料/耐久预算过滤（NP-hard，放决策层）；
- 道路专用回归测试（平面/高差/螺旋正反/液体/端点/沙砾，每项 ≤30s）——**仍未纳入 selftest**。

---

## 七、命令与测试工具（更新）

```
/alice spawn <name>          生成假人
/alice mine <x y z>          指派挖掘
/alice auto-mine <tag|block> 决策层自动挖最近目标
/alice observe               感知摘要
/alice scan <x y z>          接口扫描
/alice selftest              基础 headless 冒烟验收（TEST1-3；复杂地形以客户端实测为准）
/alice selftest full         历史完整 13 项回归，仅排错时显式运行
/alice diagnose-path <x y z>  只读曲面寻路诊断（状态/路径段数/扩展节点）
/alice status                 只读 bot 回收状态（busy/上次结果/位置）
/alice road build            玩家版：按蓝图逐单元构建
/alice road buildbybot        bot 版：强制施工动画 + 终点挖掘
/alice protect ...           安全区管理
目标指定器(钻石斧)右键方块   挖掘；Shift+右键 → 独立放置任务（PlaceTask）
道路蓝图锄(钻石锄)右键两端   生成蓝色道路预览；Shift+右键重置
钻石铲右键                   快捷接口扫描
```

headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自动关服，看 run/logs/latest.log）。

---

## 八、关键决策与已知坑（R# 编号，避免重踩）

- **R8**：selftest 手动触发；auto 模式用系统属性开关。
- **R14**：JECh 纯客户端模组，只注入 runClient；`libs/` 若被清理需从 WSL 复制回。
- **R15**：1×1 坑爬不出（A* 落脚格需下方实心）；拾取阶梯已部分缓解。
- **R16**：原版 Player.tick 触碰拾取对玩家化假人不触发 → `forcePickup` 兜底。
- **R17**：路线选择 = 普通弯曲优先，`vertical > horizontal + 4` 时才切螺旋 —— **已被本会话改为 `verticalDistance > 0` 就试螺旋**（曲线候选失败后）；不要再改回旧阈值。
- **R18**：螺旋入口外一格同样扩到三格净空。
- **R19**：螺旋出口→目标必须经过一格水平缓冲，且不侵入螺旋 2×2 投影。
- **R20**：蓝图失败时 select 返回 false 并保留 first；看 `no_walkable_route` 日志再改路线。
- **R21**：`RoadPlan` 静态单例绑定 ServerLevel；切维度/重进世界会重置。
- **R22（新）**：不要复活“拒绝重复水平投影”的临时逻辑 —— 它会把高差路线直接打成 `no_walkable_route`。
- **R23（新）**：不要复活“支撑格与其他单元净空重叠即失败” —— 它把合法重叠误判为蓝图失败（`routeUnits=29`）。
- **R24（新）**：`RoadBuildTask` 终点**不能**把 bot 移动到最终支撑格上方（那是未挖的目标方块），应从缓冲单元直接进 MineTask。
- **R25（新）**：采集只认 `liveItemsFromOrigin(target)` ∩ 锁定 UUID；**不要**加回 `allItems` 回退拾取。
- **R26（新）**：`SEARCH_LIMIT` 仅表示当前 A* 预算不能证明可达性，不能用作通道授权；默认停止并报告，决策层可换目标/扩大预算，客户端可用 `/alice diagnose-path` 验证。
- **R27（新）**：通道规划和执行必须以 bot 可回收性优先：执行层独立否决流体、保护区、不可破坏/高代价方块与非法几何；复杂世界无法证明安全时宁可停止，绝不让 LLM 建议绕过硬检查。
- **坑**：掉落物有 pickupDelay；挖高处方块后掉落物需等 onGround 再拾取，未落地前不要反复跑昂贵 A*。
- **坑**：`RenderType.lines()` 自带深度测试 → 透视高亮需自定义 RenderType（NO_DEPTH_TEST）。
- **坑**：`stone` 是方块 ID 不是标签 → auto-mine 需自动判断标签/方块两种模式。
- **坑**：清障目标必须「bot 直接可见」——递归向 bot 靠近，否则 `line_of_sight_blocked` 中断。
- **坑**：`BotMiner` 的 `protected_*` 失败不能交给 MineTask 通用清障分支（会绕开受保护目标先挖墙），直接终止。
- **测试运行器**：`server.halt(true)` 后 Gradle/Forge 子进程可能不退出并遗留 `run/world/session.lock`；清理自测进程再复跑。
- **坑**：Java 17 + netty setAccessible 警告、`FMLJavaModLoadingContext.get()` 过时警告均无害。

---

## 九、开发工作流（三副本同步）

- WSL：`~/projects/alice`（开发/编译/headless 测试）
- Windows：`D:\JAVA_projects\alice`（runClient 实测；`receive.denyCurrentBranch=updateInstead`）
- GitHub：`dddgn486/Alice-mcbot`（公开存档 + Actions CI；SSH 推送）
- 流程：WSL 改代码 → **编译验证（`./gradlew compileJava`）→ `git push origin master`（Windows）→ 用户实测**
- **用户要求：每次修改同步到 Windows 端**，方便实测。本会话所有提交均已 push origin。
- 推 GitHub 前先 `git fetch github` 检查冲突（遇到过 3 次 non-fast-forward）。

## 十、环境

- Forge 1.20.1-47.4.10 + Parchment 2023.09.03 + JDK 17 + Gradle 8.8
- Mekanism 1.20.1-10.4.16.80（modmaven）；JEI（modmaven）；JECh（本地 libs/，仅 runClient）
- 依赖：`implementation fg.deobf("mekanism:Mekanism:...")` + generators(runtimeOnly)

---

## 十一、下个会话建议顺序

1. Windows 实测单目标：钻石斧、`/alice mine`、`/alice auto-mine` 均只走曲面 A*，不得隐式挖/搭通道；
2. 将现有 A* 封装为 `SurfacePathfinder`，返回结构化可达/无路结果与候选站位路径；
3. 定义独立 `TunnelPlan`/`TunnelPlanner`，仅在所有曲面站位不可达时接入，不复用 `RoadPlan` 单例；
4. 拆出 `DropCollectionTask`，保留“无长通道”硬规则；迁移时由父任务保留 ScopeBuffer/高亮生命周期，子任务不得自行结束作用域；
5. 实现 `TargetCluster`/`ClusterMineTask` 与簇 AABB 通道禁入；
6. 道路/曲面/通道/掉落物/目标簇专用回归测试；
7. 曲率/半圆绕行上限、L1/L2 损耗与材料预算过滤。
