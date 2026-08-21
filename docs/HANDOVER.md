# Alice 项目交接说明（给新会话的快速上手）

> 用途：另一个对话/会话接手本项目时，先读本文件 + README + 设计文档，即可快速进入工作。
> 更新：2026-08-21（最新实现提交 `10f4cd7`：C1 maintenance F1-F4 收口；`interface-readonly-snapshot-v1` 的 S1-S4 已获用户验收）
> 重要：本会话功能提交已同步到 Windows 端仓库（`origin` = `/mnt/d/JAVA_projects/alice`）。**每次代码修改完成后必须 `git push origin master`**，用户在 Windows 客户端实测。
> 当前工作区的未提交改动均为监督流程：`.gitignore`、`AGENTS.md`、`docs/SUPERVISION_PROTOCOL.md`、`docs/supervision/`、`tools/`；它们不属于 Mod 功能实现，应作为独立治理提交核对，勿覆盖或混入功能提交。

## 〇、当前 Git 状态（重要）

```
10f4cd7 feat: close C1 scanner maintenance F1-F4             ← 最新实现（C1 F1-F4 维护收口）
ba63d15 docs: correct supervisor handoff revision          （治理：监督交接回填，位于 8bb2d7b 之后）
276339f docs: define dsh bus supervision handoff           （治理：四角色 bus 交接）
f9d89da docs: record blocked client checkpoint             （治理：客户端阻塞 checkpoint）
8bb2d7b feat: add readonly interface snapshots             （业务：C1 快照与独立扫描器；S1-S4 已 USER_ACCEPTED）
d1def08 docs: define product architecture roadmap          （三产品主线、C0-C5 与 R37-R40）
```

当前工作区不是干净状态：治理对接文件待独立提交。治理基线包含 `AGENTS.md`、`docs/SUPERVISION_PROTOCOL.md`、`docs/supervision/`、`tools/` 和 `docs/SUPERVISOR_HANDOFF.md`；四角色 `dsh-agent-bus` 的接管顺序、权限和当前阻塞以 `docs/SUPERVISOR_HANDOFF.md` 为准。下一次功能工作开始前，监督员应先创建或核对 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`；主工作会话必须运行 `./tools/work-session-start.sh`，没有批准工作包不得扩展功能。只提交已核对归属的文件，勿覆盖用户或监督流程变更。

WSL 工作区路径：`~/projects/alice`；Windows：`D:\JAVA_projects\alice`（`origin`）。
GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push 前先 `git fetch github` 查冲突）。

## 快速接手（1 分钟）

本会话已完成的软移动链路：`NATIVE_TRAVEL` 现在是金斧普通右键和 `/alice soft-probe` 的默认实验后端；`SELF_MOVE` 仅保留为 Shift+右键回归对照。客户端已实测 `NATIVE_TRAVEL` 平地前进、跨一格高障碍并正确下落；`SELF_MOVE` 跨障不稳定，仍不适合扩展。软探针不再按水平距离直接成功或伪造 `setOnGround(true)`，而是在到点后以零输入 `travel(Vec3.ZERO)` 结算原版重力/摩擦，并验证支撑与 `onGround`。下半砖/台阶落地改为按目标脚位下方 `VoxelShape` 实际顶面与实体脚底比较，避免整数 `blockPosition()` 误判。

按批准计划 `20260820-surface-astar-correctness-v1`，曲面 A* 的当前成本模型已冻结为：水平（含对角）=1、上阶=2、侧向下阶=1、原地向下=3。因对角同价，旧曼哈顿 heuristic 会高估；现改为零 heuristic 的 Dijkstra 出队顺序，并在发现更低成本时重开 closed 节点。`PathingRegression` 在 headless 世界中已断言四项成本/段数：对角 `2.0/2`、上阶 `2.0/1`、下阶 `1.0/1`、高差替代路线 `3.0/2`。这证明指定曲面成本模型的算法选择，不证明客户端假人物理。

新增独立点击诊断物品 `alice:soft_path_probe_selector`（指南针外观）：点击支撑方块只映射 `clicked -> clicked.above()` 脚位、检查可站/可过并派发 `SoftPathProbeTask`；日志记录入口、支撑、脚位、路径成本和段序列。它不改变金斧默认行为，不生成 bot、不改世界，也不调用 FollowTask、矿链、道路、隧道、流体或 HARD_PATH 回退。

按批准计划 `20260820-soft-path-height-probe-v1`，`SoftPathProbeTask` 现会为 A* 已规划的单格段复核 `MovementHelper` 几何：平地为 `HORIZONTAL`，`dy=+1` 且 `canAscend` 成立才采用显式 jump + `NATIVE_TRAVEL`，`dy=-1` 且 `canDescend` 成立则只用原版前进/重力。任何非相邻或重验失配段返回 `soft_path_invalid_segment`；日志会输出 `action/from/to/step/onGround` 和段完成时的实际 foot/support 证据。该改动只影响 `/alice soft-path-probe`，没有修改 FollowTask、挖矿、拾取、道路、隧道或流体。

已新增两项独立实验/功能入口：`/alice soft-path-probe <脚位>` 复用 `SurfacePathfinder` 的连续脚位段，并用 `NATIVE_TRAVEL` 逐段验收脚位、碰撞顶面支撑和落地；`/alice follow on|off` 是可关闭的同维度短程跟随状态，只跟随命令执行者本人，2 格跟随距离、24 格上限、每 10 tick 重算。跟随与保护区已在 `26819bd` 解耦：保护区停留/巡逻/返航是未来独立任务，不能再作为跟随的启动或运行时前提。

按批准计划 `20260820-soft-path-execution-consistency-v1`，本轮保持现有 Dijkstra/A* 和 `alice:soft_path_probe_selector` 独立入口不变。`MovementHelper` 现对角移动检查两侧格、头部格和连续玩家扫掠空间；一格下降检查同一玩家尺寸 AABB 扫掠，仅忽略起点/终点脚下支撑接触。等成本路径在主成本不变时稳定优先少转弯，再按坐标消除平局；探针重验几何阻塞返回 `soft_path_blocked_segment`，异常关系仍为 `soft_path_invalid_segment`。

按批准计划 `20260820-soft-path-descent-landing-v1`，本轮未修改 A*、对角逻辑或任何稳定任务链。下降段使用统一 `supportTopY` 读取实际 `VoxelShape` 顶面；到达水平半径后显式进入下降 settle 状态，记录 `actualY/supportTopY/onGround/settleTicks`，仅在目标脚位、顶面和 `onGround` 同时成立时完成，失败保持 `soft_path_unsettled` 并带完整诊断。

按批准计划 `20260821-task-observability-v1`，`BotSession` 新增只读不可变 `TaskExecutionRecord`，区分当前任务摘要与最近终端记录。记录包含任务类型、语义目标、起止 server tick、终端状态、原有稳定结果码、终端 bot 方块位置与恢复状态；完成、失败、维生中断、follow 取消、替换取消、road-plan/未实现目标的启动前拒绝均记录稳定 `task_execution_terminal` 日志。`/alice status` 只读展示 `current` 和 `latest`，同时保留原有 `last/pos/hazard`。没有改变 Task 生命周期、成功/失败条件、移动、world edit 或任何客户端交互。

已执行验证：`./gradlew compileJava` 成功（仅既有弃用警告）；headless `./gradlew runServer -Dalice.selftest.auto=true` 的 `INTERFACE_SNAPSHOT_SELFTEST PASS` 验证原版箱子 `OK`、27 个显式槽位、slot 0 `minecraft:diamond×3`、泥土 `NO_BLOCK_ENTITY`，并按 `20260821-interface-c1-maintenance-f1-f4-v1` 新增三项 F1 断言：`formatterPure`（`InterfaceScanner.format` 不改变来源箱子内容与已捕获快照字段）、`postCaptureStable`（源箱子 slot0 改为 iron_ingot×7 后原快照仍为 diamond×3）、`itemsImmutable`（`List.copyOf` items 拒绝 add 并抛 `UnsupportedOperationException`），三项连同基线断言汇总为单一 `INTERFACE_SNAPSHOT_SELFTEST PASS|FAIL` 且日志输出每项可审核布尔值。完整既有 smoke 仍在外部 180 秒时限内未完成并以 `143` 退出，不能报告整套通过。C1 快照不保留 capability handler、ItemStack、tag、block entity 或 level 引用；能量/流体路径有编译覆盖但没有专用 fixture，未写成运行时 PASS（本轮按计划未新增任何 machine/fluid/energy/mock/tick/chunk fixture）。

`alice:interface_scanner` 独立物品的模型/名称、右键只读扫描、泥土等无 block entity 目标 `NO_BLOCK_ENTITY`，以及普通 `minecraft:diamond_shovel` 不再触发 Alice 扫描，已由用户在 Windows 客户端 S1-S4 矩阵确认并标为 `USER_ACCEPTED`（见 `.alice-supervision/client-tests/8bb2d7b.md`）；接受范围仅限独立扫描器身份、只读箱子扫描、无 block entity 扫描与原版钻石铲隔离，不证明 C2/C3/C4 操作兼容性，也不替代既有 SOFT_SURFACE 客户端物理证据。

按批准计划 `20260821-interface-c1-maintenance-f1-f4-v1` 完成的 C1 维护收口：F2 `InterfaceScanner.capture` 现先调用 `level.hasChunkAt(pos)`，未加载位置以保守常量 `unknown` 作为 `blockId` 且不读取目标 `getBlockState`/`getBlockEntity`；F4 删除无调用者的旧泛化 `scanItems/scanEnergy/scanFluid/slotHint` 死代码与槽位 heuristic 注释，类注释改为 C1 unsided raw readonly facts，Mek 投影保留为显式非通用 legacy；F1 见上段 selftest 断言；F3 本文件与 `docs/SUPERVISOR_HANDOFF.md` 事实已同步（业务 SHA 与治理提交分开记录）。未改动 `ScanWand.java`（其 capture 后提示 block lookup 不在本包范围）。

**历史观察（未归因、未修复，2026-08-21 03:29）**：加载旧存档时客户端曾卡死，日志最后可见阶段为 `假人已生成(玩家化): name=tango pos=17, -59, 18`、`已从世界存档恢复假人: name=tango pos=(17, -59, 18)`、`SELFTEST 待命(手动 /alice selftest 触发)`。这些末行只证明卡死发生在恢复假人并进入 selftest 待命之后，尚无异常栈、线程转储或新世界对照，**不能据此归因到 C1 snapshot、独立物品注册或假人恢复中的任一模块**。用户当日稍后正常进入该旧 superflat 存档完成 S1-S4，仅作为未复现观察，不关闭、不归因、不修复该历史问题；复发时先采集完整 `latest.log`/`debug.log` 与客户端/服务端线程转储，再决定窄修复范围。本轮 F1-F4 与旧存档卡死隔离，未诊断、未重复加载。

不能回退的决策：普通挖矿、拾取仍固定 `HARD_PATH`；`MineTask` 不生成道路或隧道，深层目标失败 `target_requires_tunnel`；A* 不破坏方块；本地清障仍仅限直接可见、4.5 格内、最多 2 个方块。`SOFT_SURFACE` 不得接入 `MineTask`、`DropCollectionTask`、道路或隧道，除非逐项客户端验证和单独决策批准。

下一步状态：**C1 S1-S4 + F1-F4 已完成并经监督二审 PASS**。C1 S1-S4 客户端矩阵为 `USER_ACCEPTED`（窄范围：独立扫描器身份、只读箱子扫描、无 block entity 扫描、原版钻石铲隔离）；F1-F4 维护提交 `10f4cd7` 与治理回填 `3644429` 已推送 `origin/master`，二审见 `.alice-supervision/reviews/20260821-interface-c1-maintenance-f1-f4-review.md`。历史旧存档卡死保持未归因、未修复；若恢复诊断，先建旧/新世界对照并采集完整日志与客户端/服务端线程转储，在证据能区分 registry remap、假人 SavedData 恢复、连接注册、客户端同步或其他模组初始化前，不修改业务代码。下一功能包尚未选择；C2+、AttackTask、库存转移、LLM 和所有 SOFT_SURFACE 扩展继续禁止，直至新的用户批准计划。


## A1.1 Original Chest -> Bot -> Chest Transfer (implementation awaiting supervisor review)

- Active plan: `20260821-original-chest-bot-transfer-a1-1-v1`, baseline `3644429d2d88e1bb6fd1eb24654d828d1451d7f8`. This package is narrow C2 A1.1 only: explicit permission-level-2 command moves one default no-NBT `namespace:item` positive count between two loaded same-dimension original single chests through the bot's ordinary 36 inventory slots.
- Implemented package: `transfer/` immutable request/endpoint/observation/preflight/primitive/independent SavedData ledger and focused fixture; `TransferTask`; restricted `BotManager` admission/replacement/lifecycle guards; `/alice transfer-test`, read-only `/alice transfer-status`, and ledger-only `/alice transfer-abort`. There are no bindings, UI/packets, generic capabilities, double chests, multi-batch, tags/components, mod containers, direct endpoint writes, SOFT_SURFACE or changes to mining/collection/roads/tunnels/fluids/escape.
- Audit corrections retained: both legs take three-party pre/fresh/post observations and prove deltas; source preflight requires full bot and destination capacity before extraction; a proven source leg transitions ledger location to `IN_TRANSIT_BOT` before destination movement; destination no-write conflict suspends with bot location, changed/unexplained destination post state becomes `UNKNOWN_DISCREPANCY`; no automatic retry, resume, finish-insert or rollback claim. Restart lifecycle marks every unfinished ledger entry `SUSPENDED/manual_takeover_required`, and a persistent in-transit/suspended entry blocks unrelated task replacement for that bot.
- Verification: `./tools/work-session-start.sh` passed with the approved plan; `./gradlew compileJava` and `git diff --check` passed. `./gradlew runServer -Dalice.selftest.auto=true` produced explicit `TRANSFER_FIXTURE_SUITE PASS` at 2026-08-22 00:13:18. Its request/state/code/location/source/bot/destination-delta assertions cover normal legs; capacity/source/predicate/endpoint rejection; simulation conflict; source/destination post mismatch; protected and ordinary ledger-only abort; duplicate/restart/replacement; suspension expiry; survival/removal; 201-tick phase and 2401-tick active timeouts including `BOT_INVENTORY`; and controlled `SEARCH_LIMIT`/`UNREACHABLE`/`FAILED` TransferTask mappings. The command subsequently entered the pre-existing broad selftest, which later reported a pre-existing mining selftest failure and was externally terminated at 240 seconds with exit `143`; broad selftest is not reported as PASS.
- Known limits: fixture path outcomes use a transfer-only, inert-by-default `TransferTask` test seam and therefore do not claim real-world path search coverage. It does not cover actor disconnect, invoke Brigadier command parsing/permission, or drive manager-owned TransferTask through a complete live tick lifecycle. The fixture is server-side only and does not prove bot inventory packet visibility, actual Windows movement, GUI isolation, client rendering, or client-side command behavior.
- Status: awaiting supervisor review. Do not mark C2 A1.1 `CLIENT_TEST_PENDING` until supervisor PASS. After PASS, Windows client matrix remains `CLIENT_TEST_PENDING` only: permission/command, normal end-to-end, visible in-transit inventory, source/bot/destination capacity rejection, destination external mutation, hazard interruption, restart/abort/manual takeover, and ordinary chest GUI isolation. User Windows evidence is the only acceptance evidence.
- Next safe step: supervisor review of the generated packet, followed by the Windows matrix if approved. Do not expand this package into A1.2 fulfilment, persistence recovery writes, generic container support, ACL, packets/UI, or ordinary task integrations.

---

## 一、项目是什么

**Alice** —— Minecraft Forge 1.20.1 模组（mod_id: `alice`，包 `com.dddgn.alice`）。
目标是做一个「游戏内 AI 助手」：服务端假人玩家能像真实玩家一样感知世界、决策、执行任务。
**设计核心**：LLM 只做目标级决策，确定性执行器负责可靠完成；服务端权威数据，
GUI 背后操作序列化为语义接口（而非视觉点 GUI）。

设计文档：`docs/PRODUCT_ARCHITECTURE_ROADMAP.md`（三条产品主线、兼容分级与长期路线）·
`docs/AI_PLAYER_DESIGN.md`（架构总纲）· `docs/EXECUTION_FRAMEWORK.md`（执行层框架）·
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
/alice status                 只读 bot 回收/维生状态（busy/上次结果/位置/hazard/air/health）
/alice soft-probe <x y z>    SOFT_SURFACE 默认 NATIVE_TRAVEL 平地短距离实验（bot 8格内，不接入挖矿）
/alice soft-probe-travel <x y z>  NATIVE_TRAVEL 兼容别名（需已有空闲 bot，仅平地）
/alice soft-path-probe <x y z>  NATIVE_TRAVEL 连续脚位段实验（复用曲面 A*，仅客户端测试）
/alice follow on|off          跟随开关（执行者本人；同维度/24格内；保护区巡逻为独立模块）
/alice road build            玩家版：按蓝图逐单元构建
/alice road buildbybot        bot 版：强制施工动画 + 终点挖掘
/alice protect ...           安全区管理
目标指定器(钻石斧)右键方块   挖掘；Shift+右键 → 独立放置任务（PlaceTask）
软移动选择器(金斧)右键平地   NATIVE_TRAVEL；Shift+右键平地 → SELF_MOVE 回归对照；需已有空闲 bot，受 8 格/同高度限制
道路蓝图锄(钻石锄)右键两端   生成蓝色道路预览；Shift+右键重置
接口扫描器(`alice:interface_scanner`)右键  C1 快捷扫描（S1-S4 已用户验收：物品身份/只读箱子/无BE/原版钻石铲隔离；原版钻石铲不得触发）
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
- **R28（新）**：`SurvivalSystem` 每 tick 先于任务监测危险；第一版 `LAVA_CONTACT`/`SUFFOCATING` 直接中断任务，水/低空气/着火只记录；`FluidRiskPolicy` 对目标及相邻六格可见岩浆返回 `fluid_risk_lava`，不自动挖源头或堵流体。
- **R29（新）**：维生系统第一版只做监测和硬中断，不主动逃生、上浮、灭火或切换软移动；后续由独立 `EmergencyEscapeTask`/`MovementMode` 实现，不能在 MineTask 内复制应急逻辑。
- **R30（更新）**：`PathExecutor` 当前固定为 `MovementMode.HARD_PATH`，普通挖矿/拾取不可切换。`SOFT_SURFACE` 默认后端为 `NATIVE_TRAVEL`：`/alice soft-probe` 仍限同高度安全平地、bot 8 格内；`/alice soft-path-probe` 与 `FollowTask` 是独立的连续脚位段实验/低风险跟随入口。`SELF_MOVE` 仅作回归对照。软移动不处理液体、失败后的硬移动回退，也不得接入矿链。
- **R31（新）**：软移动与软移动寻路优先借鉴成熟实现，不从零重写核心运动/寻路算法。当前参考 Baritone 的 movement primitive + input override、Carpet fake player 的 action pack；Forge 适配前先确认 `ServerPlayer` 的 tick/travel/input 注入边界。`setPos` 不能伪装成软移动，成熟输入驱动方案未验证前不得接入 MineTask。
- **R32（新）**：软移动的 `BlockPos` 一律表示脚位格。金斧 `soft_move_selector` 右键的是支撑方块，必须转换为 `clicked.above()` 再校验/指派；`/alice soft-probe` 直接传脚位。入口必须使用 `SoftMoveProbeTask.validate`，失败返回具体 reason，禁止回退为笼统“安全平地”提示。完整阶段路线见 `PATHING_REFACTOR.md`。
- **R33（新）**：`NATIVE_TRAVEL` 已通过平地与一格障碍跨越/落地客户端测试，现为金斧普通右键与默认软探针后端；`SELF_MOVE` 仅由金斧 Shift+右键作回归对照。`ServerPlayer.tick()` 不会自动消费 fake player 的 `xxa`/`zza`，当前任务逐 tick 显式调用 `travel`；软路径仅用 `/alice soft-path-probe` 独立测试，不得接入挖矿。
- **R34（新）**：单目标挖掘站位选择必须先比较所有有限候选中的“视线直通 + 曲面可达”站位，并取最短曲面路径；候选分组（下/同/上）只能辅助生成，不能压过直通性。无直通候选时才回退到视线受阻站位，并严格使用 MineTask 最多两格、4.5 格局部清障；A* 不得为了站位自行挖方块或扩大成通道施工。
- **R35（更新）**：软移动抵达不能按水平距离直接 `setOnGround(true)` 成功。必须验证目标脚位下方碰撞形状的实际支撑顶面、实体脚底和 `onGround`；不能以 `blockPosition()` 的整数 Y 拒绝下半砖/台阶上的正常落地。跨障后未落稳时只用零输入 `travel(Vec3.ZERO)` 结算原版重力/摩擦，30 tick 未稳定返回 `soft_probe_unsettled`。半格台阶的实现已编译，但尚无本会话客户端验证；上/下台阶必须由 `SurfacePathfinder` 提供连续脚位段后逐段验证，未验证前不得视为稳定能力。
- **R36（新）**：`FollowTask` 是独立的可开关 SOFT_SURFACE 状态，`/alice follow on|off` 只由被跟随玩家本人开关；运行约束仅为同维度、bot 空闲、24 格上限、安全曲面、连续脚位段和维生中断，保持约 2 格距离并每 10 tick 重算。它与保护区完全解耦；保护区停留/巡逻/返航必须另建任务。跟随尚未获得客户端持续运动、重算和失败边界验证，禁止据此接入攻击或普通矿链。
- **R37（产品主线）**：Alice 按三条产品线组织里程碑：通用玩家助手、无 AE 阶段的玩家定义流水线、AE 阶段的网络/样板助理。共享移动、库存事务、权限和结果契约先稳定；不得用提前接入 LLM 掩盖执行层缺口。
- **R38（兼容分级）**：模组兼容必须声明 `C0` 识别、`C1` 只读、`C2` 受限通用操作、`C3` 配置驱动语义、`C4` 模组原生适配、`C5` 端到端技能等级。capability 发现或教程知识不等于机器写操作兼容；版本不匹配时降级到已证明的只读等级。
- **R39（计划门）**：LLM 的 ToolCall/PlanDraft 必须先经过 `Policy Gate / Plan Validator`，由代码校验 schema、权限、预算、资源、世界/适配器版本和后置条件。移动、攻击、库存和机器原语默认只供编排器使用，不作为常规 LLM 逐步工具。
- **R40（知识边界）**：官方说明和目标网站教程应精炼为带来源/版本的本地指导手册，联网 RAG 仅作缺失或冲突时的后备。知识负责指导模型选择已注册工具，不能生成执行权限或替代 `ModAdapterPack` 的确定性 handler。
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
- 流程：监督员先对新模块做浅调研；仅在外部依赖/版本语义、高风险跨层、证据冲突、连续受阻或用户要求时派发 `.alice-supervision/research/` 深调研 → 监督员审核报告并在计划 `Research Decision` 写明采纳结论、证据等级与版本边界 → 监督员发布 `.alice-supervision/active-plan.md`（仅 `APPROVED_FOR_IMPLEMENTATION` 且所需报告齐全可开工）→ 主会话 `./tools/work-session-start.sh` 读取计划与调研结论 → WSL 改代码 → **编译验证（`./gradlew compileJava`）→ 更新本 HANDOVER（实际 HEAD、完成项、验证证据、未验证限制、下一安全步）→ 提交 → `git push origin master`（Windows）→ 用户实测 → `./tools/session-complete.sh` 生成含计划快照的监督审核包**。
- **用户要求：每次修改同步到 Windows 端**，方便实测。本会话所有提交均已 push origin。
- **二次审核规则**：每个完成会话都必须将交接记录写入本文件，并生成 `.alice-supervision/pending/<commit>.md` 审核包；审核员先读审核包、本文件、当前 Git HEAD 与相关设计文档，审查架构方向、R# 决策、任务边界和验证充分性。审核员不接管业务实现；发现架构漂移、实验能力提前接入稳定链、或记录与事实不一致时，必须明确阻止后续扩展或要求用户决策。连续两次小修复仍不能改善同一客户端/跨层问题时，停止叠加特判，改为深调研或请用户选择保守降级/重新规划；调研不得阻塞无共享风险的稳定主线。完整协议见 `docs/SUPERVISION_PROTOCOL.md`；每个开发副本首次运行 `./tools/install-supervision-hook.sh` 安装本地 post-commit 自动入队。监督员在 DSH 中通过用户级 preset「Alice项目监督员」（`~/.dsh/.agent-presets/alice-supervisor`）以独立会话运行，打开时自动读取审核队列。
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
