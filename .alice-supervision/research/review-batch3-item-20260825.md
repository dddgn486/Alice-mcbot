# 批 3 审查报告：item 模块（测试工具/选择器入口语义）

- **任务**：`c5272e3a-e5ce-482d-834d-6bc70e84d501`（批 3 / item 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 3/§2/§4/§5）、批 1（bot/pathing/task：MovementHelper.supportTopY、R32、SoftPathProbeTask travel 调用点）、批 2（transfer：TransferEndpointSelectorEvents "deliberately not cancel"）、`docs/HANDOVER.md`（R32 support→foot）。**前置已确认**：`SoftPathMineTask.mineTarget`=被挖方块（BotManager.java:507 传 support）、`SoftPathProbeTask` 目标=foot 脚位格（SoftPathProbeSelector.java:59 传 support.above()）；R32 语义（`docs/PATHING_REFACTOR.md`）。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（item/ 8 文件 + BotManager.assign*/SoftPathMineTask 交叉）、grep 引用、`movement-support-foot-*` 客户端记录。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：item = "Alice 自注册物品（测试工具都走这里，不套原版工具——只套贴图）"（AliceItems.java:8-14）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `AliceItems.java` | 注册 | DeferredRegister 注册 6 个物品（interface_scanner/target_selector/soft_move_selector/soft_path_probe_selector/road_planner/transfer_endpoint_selector） |
| `RoadPlannerItem.java` | 选择器 | 道路蓝图工具：右键两端选起点/终点、Shift+右键清空；服务端逻辑 |
| `SoftMoveSelector.java` | 选择器 | 软移动探针选择器：右键支撑→foot 派发 NATIVE_TRAVEL、Shift+右键→SELF_MOVE 对照 |
| `SoftPathProbeSelector.java` | 选择器 | 软路径探针选择器：右键→只软寻路（foot）、Shift+右键→软寻路+挖掘（support=被挖方块） |
| `TargetSelector.java` | 选择器 | 目标指定器：右键方块→派挖掘任务、Shift+右键→放置圆石、右键实体→未实现提示 |
| `TransferEndpointSelector.java` | 选择器 | 传输端点选择器：useOn 返回 PASS（记录在事件层完成，不消费方块交互） |
| `TransferEndpointSelectorEvents.java` | 事件 | RightClickBlock 前置记录端点（source/destination 由 shift 区分），deliberately not cancel |
| `TransferEndpointSelectorEventsFixture.java` | 夹具 | 事件层单写/角色语义/授权拒绝/保留 vanilla 交互断言 |

**注册方式**：ITEMS.register（AliceItems.java:17-42）挂 MOD 总线（AliceMod.java:28 `AliceItems.ITEMS.register(modEventBus)`）；TransferEndpointSelectorEvents 挂 FORGE 总线（TransferEndpointSelectorEvents.java:18）。

---

## 2.2 边界归属

- **归属线**：item = **测试工具线**（4 个选择器 + road_planner + interface_scanner，均服务端逻辑入口，客户端只播交互）。
- **相邻边界**：
  - 矿链：TargetSelector 右键 → `assignTarget(TaskTarget.block)` → MineTask（HARD_PATH，批 1 已确认）——**不触 SOFT**；Shift+右键 → `assignPlace` → PlaceTask；
  - SOFT 实验线：SoftMoveSelector/SoftPathProbeSelector 仅派发 SOFT task（assignSoftMoveProbe/assignSoftPathProbe/assignSoftPathMine）——**不触矿链**；
  - 传输：TransferEndpointSelector 记录草稿（TransferSelectionData，批 2 已确认服务端权威）；
  - 道路：RoadPlannerItem 仅操作 RoadPlan 单例（road 批 3 后审，item 只核对注册/入口）。
- **R32 边界对照**：support→foot 映射——本模块实现（见特别核查②），与批 1 MovementHelper.supportTopY、PATHING_REFACTOR.md 一致。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| I1 | TargetSelector 右键方块→派挖掘任务 | useOn → `assignTarget(TaskTarget.block(clicked))`（TargetSelector.java:52）→ BotManager.assignTarget→MineTask（HARD_PATH） | 静态核对 + 服务端可证 | **一致**（批 1 矿链 HARD_PATH 已确认） |
| I2 | TargetSelector 右键实体→提示未实现 | interactLivingEntity → 消息"攻击行为未实现"（:66-67） | 静态核对 | **一致**（next step 预留） |
| I3 | TargetSelector Shift+右键→放置圆石 | `clicked.relative(face)` + `assignPlace`（:41-44）→ PlaceTask（独立，不调 RoadBuilder/MineTask） | 静态核对 | **一致**（批 1 task T7 已确认） |
| I4 | SoftMoveSelector 右键→NATIVE_TRAVEL 软移动 | `support.above()`（foot）+ backend=NATIVE_TRAVEL（:38-49）→ assignSoftMoveProbe | 静态核对 + 客户端 | **一致**（R33：金斧普通右键默认 NATIVE_TRAVEL） |
| I5 | SoftMoveSelector Shift+右键→SELF_MOVE 对照 | backend=SELF_MOVE（:47-48） | 静态核对 | **一致**（R33：SELF_MOVE 仅 Shift+右键回归对照） |
| I6 | SoftPathProbeSelector 右键→只软寻路 | `support.above()`（foot）→ assignSoftPathProbe（:59） | 静态核对 + 客户端 | **一致**（T1 客户端 PASS） |
| I7 | SoftPathProbeSelector Shift+右键→软寻路+挖掘 | `support`（被挖方块）→ assignSoftPathMine（:51） | 静态核对 + 客户端 | **一致**（T2 客户端 PASS；SoftPathMineTask.mineTarget=支撑方块语义复核） |
| I8 | TransferEndpointSelector useOn→PASS 保留 vanilla | `return InteractionResult.PASS`（TransferEndpointSelector.java:14） | 静态核对 | **一致**（A9 GUI 隔离 + 批 2 transfer 审查） |
| I9 | 事件层记录端点（不 cancel） | onRightClickBlock → record → TransferSelectionData.select；"Deliberately do not cancel"（TransferEndpointSelectorEvents.java:29） | 静态核对 + 服务端可证 | **一致**（批 2 transfer 审查②） |
| I10 | 服务端权威（选择器逻辑全服务端） | useOn 全部 `if (level.isClientSide) return SUCCESS`（客户端只播动画）；事件 `event.getSide() != SERVER return`（:24） | 静态核对 | **一致** |

---

## 2.4 R#/决策对照

| R#/决策 | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| R32 | support→foot 映射：右键的是支撑方块，必须转换为 clicked.above() 再校验/指派；/alice 命令直接传脚位 | ✅ **遵守**：SoftMoveSelector.java:38-39（support→support.above()）；SoftPathProbeSelector.java:38-39；SoftMoveProbeTask.validate 注释"目标坐标是 bot 的脚位格"（SoftMoveProbeTask.java:37） | 特别核查② |
| R33 | 金斧普通右键默认 NATIVE_TRAVEL；SELF_MOVE 仅 Shift+右键回归对照 | ✅ **遵守**：SoftMoveSelector.java:46-49 | 与批 1 R33 核对一致 |
| R35 | 落地需真实支撑（批 1 已核查） | ✅ 入口侧一致（选择器只派发，不伪造 onGround） | SoftMoveProbeTask.validate 后由任务 settle |
| A1.1 | TransferEndpointSelector 验收（A9 GUI 隔离） | ✅ **遵守**：PASS 保留 vanilla 交互（I8/I9） | 批 2 transfer 审查② + A1.1 记录 |

**边界漂移待监督员判定**：
- **D1**：`SoftPathProbeSelector` Shift+右键传 `support`（被挖方块）给 `assignSoftPathMine`，而右键传 `support.above()`（foot）——**两者语义正确区分**（mineTarget=方块 vs 探针目标=foot），但**代码未注释**该差异（类注释只说"点击支撑方块，只启动 SoftPathProbeTask"——未说明 Shift 差别的目标语义）；建议补注释（文档级，非功能问题）。
- **D2**：`TargetSelector` Shift+右键放置语义（`clicked.relative(face)`）与右键挖掘语义（`clicked`）——方向明确但与 R32 的"support→foot"概念**不混用**（放置目标=相邻格，非支撑上方）；待监督员确认语义文档化。

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- 事件层单写/角色/授权/保留 vanilla（TransferEndpointSelectorEventsFixture 4 断言，服务端 PASS）；
- 选择器→任务派发链路（TargetSelector→MineTask HARD_PATH、SoftPath→SOFT task——批 1/2 已交叉）；
- support→foot 校验（SoftMoveProbeTask.validate 全结构化拒绝码，批 1 已确认）。

**需客户端实测（Windows）**：
- 右键/Shift+右键交互手感（T1-T3 已 USER_ACCEPTED——65f4863 记录：右键软寻路、Shift+右键软寻路+挖掘、隔离验证）；
- TargetSelector 右键挖掘（latest.log SELFTEST TEST1-3 服务端 PASS；客户端挖掘动画记录在早期验收）；
- 事件层"选择端点后仍能开箱子"（A9 GUI 隔离，USER_ACCEPTED）。

> **必须写明**：服务端 PASS ≠ 客户端正确。选择器语义（派发哪个任务）服务端可证；但"点击哪个方块产生哪个动作"的**交互体验**需客户端实测（T1-T3 已提供软路径线客户端证据；TargetSelector/TransferEndpointSelector 的客户端侧依赖既有验收记录）。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| SoftPathProbeSelector 右键/Shift+右键（T1-T2） | 65f4863 retest-result（右键软寻路+挖掘 PASS） | USER_ACCEPTED | — |
| SoftMoveSelector 金斧右键/Shift（T1-T3） | 65f4863 retest-result（软移动基础 PASS） | USER_ACCEPTED | SELF_MOVE 对照客户端验证充分性 |
| TransferEndpointSelector 事件层（A1.1/A9） | f655be2 客户端记录（选择端点+开箱隔离） | USER_ACCEPTED | — |
| TargetSelector 右键挖掘 | latest.log SELFTEST TEST1-3（服务端）+ 早期客户端记录 | 服务端 PASS + 客户端（部分） | 客户端挖掘动画专项记录充分性待查 |
| RoadPlannerItem | 无专项记录（road 批 3 核查） | 无/待查 | road 批 |
| interface_scanner | C1 只读扫描验收（A1.1 相关 capability 批） | service-batch 已验收（批 3 capability 后审） | — |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| 4 个选择器 + 事件层 | **D 维持** | 语义与 R32/R33/A1.1 一致；T1-T3/A1.1 客户端验收；无 SOFT 入矿链路径 |
| SoftPathProbeSelector Shift 语义注释缺失（D1） | **D 维持（文档级）** | 功能正确、无歧义风险低（mineTarget vs foot 已代码区分） |
| RoadPlannerItem / interface_scanner | **D 维持**（road/capability 批核查） | item 侧仅注册/入口核对，无异常 |
| 停止条件 | 未触发 | 未发现崩溃/死锁；FROZEN 未触碰；无替换结论 |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向：选择器入口（自研事件/useOn vs 成熟方案）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 自研基于 vanilla `Item.useOn` + Forge `PlayerInteractEvent`（原生 API）；无成熟"测试工具选择器"库可对照 | — |
| M2 Forge 1.20.1 兼容 | useOn/RightClickBlock 原生兼容 | — |
| M3 集成成本 | 4 选择器 + 事件 + 夹具，被 BotManager.assign* 消费；替换需重写入口面 | 引用面清单（BotManager 5 个 assign 入口 + SoftMoveProbeTask.validate） |
| M4 客户端风险 | T1-T3/A1.1 客户端验收通过 | 新方案客户端矩阵 |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**结论**：选择器入口基于 vanilla/Forge 原生 API（useOn + PlayerInteractEvent），非重复造轮子；无明确成熟替代；**只列方向不选型**。

---

## 特别核查项结论

### ① 入口语义表（四个选择器 + road/interface 对照，已确认）

| 选择器 | 触发方式 | 对应动作 | 事件是否 cancel | 目标语义 | 服务端/客户端侧 |
|---|---|---|---|---|---|
| `TargetSelector` | 右键方块 | `assignTarget(TaskTarget.block(clicked))` → MineTask（HARD_PATH 挖掘） | 不 cancel（useOn 返回 SUCCESS，服务端逻辑后返回） | clicked=**被挖方块**（非 foot） | 服务端逻辑：TargetSelector.java:49-57 |
| `TargetSelector` | Shift+右键 | `clicked.relative(face)` + `assignPlace` → PlaceTask（放置圆石） | 同上 | 相邻格=放置目标 | 服务端：:40-48 |
| `TargetSelector` | 右键实体 | 提示"攻击未实现" | 不 cancel | 实体目标（next step） | 服务端：:60-69 |
| `SoftMoveSelector` | 右键 | support→support.above()（foot）→ assignSoftMoveProbe(NATIVE_TRAVEL) | 不 cancel（useOn SUCCESS） | **foot**（R32） | 服务端：:38-49 |
| `SoftMoveSelector` | Shift+右键 | 同上但 backend=SELF_MOVE | 不 cancel | foot（回归对照 R33） | 服务端：:46-49 |
| `SoftPathProbeSelector` | 右键 | support.above()（foot）→ assignSoftPathProbe（只软寻路/诊断） | 不 cancel | **foot**（R32） | 服务端：:57-64 |
| `SoftPathProbeSelector` | Shift+右键 | support（**被挖方块**）→ assignSoftPathMine（软寻路+挖掘） | 不 cancel | **support=方块**（mineTarget） | 服务端：:48-56 |
| `TransferEndpointSelector` | 右键箱子 | 事件层 record（destination）→ TransferSelectionData.select（**PASS 保留 vanilla 开箱**） | **deliberately not cancel**（TransferEndpointSelectorEvents.java:29） | 端点（服务端草稿） | 事件层：TransferEndpointSelectorEvents.java:22-30 |
| `TransferEndpointSelector` | Shift+右键箱子 | 事件层 record（source） | 同上不 cancel | 端点 | 同上 |
| `RoadPlannerItem` | 右键 | 选起点/终点 → RoadPlan.select → 广播 RoadPlanPacket | 不 cancel（SUCCESS） | 道路端点 | 服务端：RoadPlannerItem.java:30-35 |
| `RoadPlannerItem` | Shift+右键 | `RoadPlan.reset()` + 广播清除 | 不 cancel | 清空 | 服务端：:24-28 |
| `interface_scanner` | 右键 | C1 只读接口扫描（capability 批后审） | 待 capability 批 | — | — |

**cancel 语义总览（已确认）**：**全部选择器/事件均不 cancel vanilla 方块交互**——Target/Soft*/Road 返回 SUCCESS（消费交互但任务服务端逻辑完成）、Transfer 返回 PASS + 事件层 deliberately not cancel（保留开箱，A9）；无任何选择器取消原版方块行为。

### ② support→foot 映射（R32，与批 1 MovementHelper.supportTopY 交叉核对，已确认）

**映射链（已确认）**：
- **入口侧**：SoftMoveSelector.java:38-39、SoftPathProbeSelector.java:38-39：`support = context.getClickedPos(); target = support.above()`——**右键的是支撑方块（support），转换为其上方脚位格（foot）再校验/派发**（R32 原文）；
- **校验侧**：SoftMoveProbeTask.validate（SoftMoveProbeTask.java:37-66）以 **foot 为目标**校验（distance/height/canWalkOn/canWalkThrough——批 1 已确认）；SoftPathProbeSelector 内联校验 `canWalkOn(target)/canWalkThrough(target/target.above())`（:40-45）——foot 及其上方净空；
- **执行侧**：SoftPathProbeTask/SurfacePathfinder 以 foot 序列寻路（批 1 pathing 审查——`MovementHelper.supportTopY` 支撑顶面 = foot 下方碰撞形状顶面，MovementHelper.java:90-97）——**R32 契约在入口/校验/执行三侧一致**；
- **挖掘例外（已确认）**：SoftPathProbeSelector Shift+右键传 `support`（被挖方块）给 `SoftPathMineTask`——该任务内部 `navTarget = mineTarget.above()`（SoftPathMineTask.java:101）即**挖掘目标=支撑方块、导航目标=其上方 foot**——与 R32 不冲突（挖掘语义天然针对方块，导航仍走 foot）。

**与批 1 交叉核对结论**：`MovementHelper.supportTopY`（支撑碰撞形状顶面）是执行侧支撑判定（批 1 已确认含半格）；入口侧映射（support→foot）与执行侧支撑顶面**语义互补不冲突**——入口把"被点击支撑"映射到"其上方可站格"，执行把"可站格"落到"实际支撑顶面"。**R32 全线一致**。

### ③ 物品白名单（AliceItems 注册清单，已确认）

| 注册名 | RegistryObject | 用途 | 遗留/无关? |
|---|---|---|---|
| `interface_scanner` | INTERFACE_SCANNER.java:21-22 | C1 只读接口扫描（capability 批后审） | 正常（capability 线） |
| `target_selector` | TARGET_SELECTOR.java:25-26 | 目标指定器（挖掘/放置） | 正常（测试工具线） |
| `soft_move_selector` | SOFT_MOVE_SELECTOR.java:29-30 | 软移动探针 | 正常（SOFT 实验线） |
| `soft_path_probe_selector` | SOFT_PATH_PROBE_SELECTOR.java:33-34 | 软路径探针/挖掘 | 正常（SOFT 实验线） |
| `road_planner` | ROAD_PLANNER.java:37-38 | 道路数学蓝图 | 正常（road 线） |
| `transfer_endpoint_selector` | TRANSFER_ENDPOINT_SELECTOR.java:41-42 | 传输端点选择 | 正常（transfer 线 A1.1） |

**白名单结论**：**6 物品全部对应现行功能线（capability/target/SOFT×2/road/transfer），无遗留/无关/调试物品**；全部 `new Item.Properties()`（无原版行为继承，只套贴图——AliceItems.java:9 注释）；注册挂 MOD 总线（AliceMod.java:28）。

---

## 与 active plan 冲突声明

- item 模块与 p1-client-sync-fix-v1 **无直接交叉**（选择器是任务派发入口，非位置/速度同步）；若监督员需引用支持→foot 或入口语义到 P1 线调查，写入 active plan Research Decision。
- GUI FROZEN（19af345）：选择器交互不触 GUI（transfer 选择器保留 vanilla 开箱是 FROZEN 边界外行为——A9 已验收）；本审查未触碰。

---

## 证据分级汇总

- **已确认事实**：8 文件职责与行号；入口语义表 12 行（4 选择器 + road + scanner）；support→foot 三侧映射链（入口/校验/执行）；挖掘例外语义（mineTarget=方块、navTarget=foot）；6 物品白名单无遗留；全部不 cancel vanilla 交互；服务端权威；T1-T3/A1.1 客户端验收记录。
- **架构推论**：无（本模块证据充分）。
- **待调查**：D1（Shift 差异注释缺失，文档级）；D2（TargetSelector Shift 放置语义文档化）；RoadPlannerItem/interface_scanner 专项客户端验证（road/capability 批后审）。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、入口改动均需监督员审核 + 用户批准。
---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（批 3 第一个模块）**

独立核查：
1. R32 support→foot 映射属实：SoftMoveSelector.java:38-39（support=clickedPos, target=support.above()）+ SoftPathProbeSelector.java:38-39——入口侧三侧映射链（入口/校验/执行）与批 1 MovementHelper.supportTopY 语义互补不冲突 ✅
2. Shift 差异正确：SoftPathProbeSelector Shift+右键传 support（被挖方块）→ assignSoftPathMine、右键传 foot → assignSoftPathProbe——mineTarget vs 探针目标语义区分正确（D1 仅差注释，文档级）✅
3. 入口语义表 12 行完整：4 选择器 + road_planner + interface_scanner 的触发方式/动作/cancel 语义全部标注——**全部不 cancel vanilla 交互**（transfer 保留开箱 A9、其余 SUCCESS 消费）✅
4. 物品白名单：6 物品全部对应现行功能线，无遗留/无关/调试物品 ✅
5. 服务端权威：useOn 全部 isClientSide 早返回 + 事件层 server 侧检查 ✅
6. 只读性：src 零改动、HEAD 仍 6c2b461 ✅

待调查移交：D1（Shift 语义注释缺失，文档级）/ D2（TargetSelector Shift 放置语义文档化）；RoadPlannerItem/interface_scanner 专项验证归 road/capability 批。

采纳为批 3 证据。下一步派发 road 模块审查。
