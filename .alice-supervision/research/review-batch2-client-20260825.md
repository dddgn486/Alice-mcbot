# 批 2 审查报告：client 模块（仅客户端状态与渲染，双端证据缺口）

- **任务**：`6fd54e40-d9eb-4a9a-a23f-5c4950c9c8ba`（批 2 / client 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 2/§2/§4/§5）、`review-batch2-gui-20260825.md`（方向 B 完全接管、ClientBotInventoryState 已交叉核对）、`review-batch2-transfer-20260825.md`、`docs/HANDOVER.md`。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（client/ 6 文件 + network/ 3 包 + BotManager/BotInventoryService/RoadPlannerItem 发送方）、grep 全工程引用。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：client = "仅客户端层：状态存储/渲染/屏幕——服务端权威的客户端镜像；网络线程 → 主线程 via enqueueWork，渲染线程只读"（ClientTargetState.java:8-10、ClientRoadState.java 注释）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `ClientBotInventoryState.java` | 状态 | 客户端只读 bot 库存快照存储（volatile + openScreen；gui 方向 B 的客户端存储） |
| `ClientRoadState.java` | 状态 | 客户端只读道路计划状态（active/cells/positions，从 RoadPlanPacket 更新） |
| `ClientTargetState.java` | 状态 | 客户端只读任务目标状态（active/type/blockPos/entityId，从 TargetPacket 更新） |
| `gui/BotInventoryScreen.java` | 屏幕 | 方向 B 纯客户端 Screen（渲染 41 槽、点击发 C2S、不改本地） |
| `gui/ClientMenuScreens.java` | 屏幕 | 方向 A 遗留 no-op（不再 MenuScreens.register） |
| `render/TargetOutlineRenderer.java` | 渲染 | 目标/道路透视高亮渲染器（RenderLevelStageEvent AFTER_PARTICLES，自定义无深度线框 RenderType） |

**注册方式**：TargetOutlineRenderer/ClientMenuScreens 经 `@Mod.EventBusSubscriber(value = Dist.CLIENT)`（TargetOutlineRenderer.java:43、ClientMenuScreens.java:16）；Client*State 无注册（静态类）；Screen 经 ClientBotInventoryState.openScreen 打开（网络线程 enqueueWork → 渲染线程）。

---

## 2.2 边界归属

- **归属线**：client = **仅客户端层**（服务端权威的客户端镜像）；与 gui FROZEN 线接缝（BotInventoryScreen/ClientMenuScreens/ClientBotInventoryState 属冻结线，gui 审查已交叉核对）。
- **相邻边界**：
  - network（批 2 后审）：client 侧为 S2C 包接收方（TargetPacket/RoadPlanPacket/BotInventoryPacket 的 handle → Client*State.update）；
  - render：TargetOutlineRenderer 读 ClientTargetState/ClientRoadState 渲染；
  - 服务端权威：Client*State 只被 S2C 包更新（见特别核查①），客户端不产生决策。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| C1 | Client*State 只由 S2C 包更新 | 三个 State 的 update 入口均接收对应 packet（ClientTargetState.java:23 / ClientRoadState.java:20 / ClientBotInventoryState.java:31）；handle 侧 `enqueueWork`（TargetPacket.java:42 / RoadPlanPacket.java:36 / BotInventoryPacket.java:39-44） | 静态核对 | **一致**（见特别核查①映射表） |
| C2 | 渲染线程只读 | volatile 字段 + 静态 getter（三个 State）；TargetOutlineRenderer 无写回（grep 无 CHANNEL/send/setPos/setItem/applyAction） | 静态核对 | **一致**（渲染器只读，见特别核查②） |
| C3 | 目标高亮方块/实体两型 | type==0 方块 AABB 外扩 0.003 绿色；type==1 实体实时 AABB 外扩 0.1 红色（TargetOutlineRenderer.java:101-121） | 静态核对 | **一致**（A1.1 目标高亮） |
| C4 | 透视线框（关闭深度测试） | 自定义 OutlineRenderType：NO_DEPTH_TEST + NO_CULL + COLOR_WRITE + 3px 线宽（:59-75）；RenderSystem 兜底 disable/enable（:136-140） | 静态核对 | **一致**（注释记录 AFTER_PARTICLES 是 1.20.1 最晚可用阶段，AFTER_LEVEL 永不触发——Forge patch 事实） |
| C5 | 道路只绘外露面 | drawExposedFaces 跳过相邻体素公共面（ClientRoadState.contains 检查，:174） | 静态核对 | **一致**（性能优化 + 视觉效果） |
| C6 | 网络线程→主线程 | handle 均 enqueueWork（不直接在网络线程改 volatile 状态——虽然 volatile 安全，enqueueWork 保证渲染线程可见性时序） | 静态核对 | **一致** |
| C7 | BotInventoryScreen 纯渲染（方向 B） | 读 ClientBotInventoryState 渲染 41 槽，点击发 BotInventoryActionPacket（C2S），不改本地（gui 审查 G5 已确认） | 静态核对 | **一致**（冻结线内不触碰） |

---

## 2.4 R#/决策对照

| 决策/R# | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| FROZEN（19af345） | GUI 交互冻结（BotInventoryScreen 属冻结线） | ✅ **遵守**：BotInventoryScreen/ClientMenuScreens/ClientBotInventoryState 保留未变（gui 审查确认）；本审查不触碰 | gui 审查 2.4 + 本报告不修改 |
| 方向 B（9534a5d） | 自定义 packet+Screen | ✅ **遵守**：BotInventoryScreen 纯 Screen（无 AbstractContainerMenu 底层） | gui 审查 G1/G8 |
| A1.1 目标高亮 | 客户端透视高亮测试效果 | ✅ **遵守**：TargetOutlineRenderer 纯渲染（无决策/写回） | TargetOutlineRenderer.java 全文 |
| 道路渲染 | RoadPlan 单例 → RoadPlanPacket → ClientRoadState → 渲染 | ✅ **遵守**（服务端来源明确：RoadPlannerItem 发送，:26,35） | RoadPlannerItem.java + ClientRoadState.update |

---

## 2.5 Dual-View：客户端状态的服务端来源核查 + 需客户端实测

**服务端来源核查（特别核查①的完整表，见下）**：三个 Client*State 均有对应 S2C 包与服务端发送方。

**需客户端实测（Windows）**：
- 目标高亮渲染（方块绿框/实体红框/透视效果/呼吸动画）——A1.1 记录为 USER_ACCEPTED（测试效果）；
- 道路高亮渲染（外露面线框）——客户端验证状态待查（road 批 3 后审，client 只记录渲染实现）；
- BotInventoryScreen 渲染/交互——**FROZEN（无 G1-G13 记录）**。

> **必须写明**：服务端 PASS ≠ 客户端正确。client 层的渲染效果**只有客户端可见**，服务端 fixture 无法证明高亮/透视/时序正确；A1.1 目标高亮有客户端记录，但道路渲染与 BotInventoryScreen 无客户端验收（后者属 FROZEN）。双端证据缺口：ClientRoadState/ClientTargetState 的**渲染视觉效果**无客户端矩阵记录（服务端只证明 packet 到达链路，不证明渲染正确）。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| ClientTargetState ← TargetPacket 链路 | BotManager.broadcastTarget 发送（:305-311）+ handle enqueueWork | 服务端 PASS（链路）+ USER_ACCEPTED（A1.1 高亮效果） | 高亮细节（透视/呼吸）客户端记录充分性 |
| ClientRoadState ← RoadPlanPacket 链路 | RoadPlannerItem 发送（:26,35）+ handle | 服务端 PASS（链路） | **渲染视觉效果无客户端矩阵**（盲区） |
| ClientBotInventoryState ← BotInventoryPacket | BotInventoryService.open/pushSnapshot 发送（:53,202） | 服务端 PASS（链路） | **Screen 渲染 FROZEN**（无客户端） |
| TargetOutlineRenderer 只读性 | grep 无写回 | 静态核对 | — |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| ClientTargetState / TargetOutlineRenderer（目标高亮） | **D 维持** | 纯客户端只读渲染、A1.1 高亮 USER_ACCEPTED、无写回 |
| ClientRoadState | **D 维持** | 服务端来源明确（RoadPlanPacket）；渲染效果客户端记录缺口 → road 批 3 补 |
| ClientBotInventoryState / BotInventoryScreen / ClientMenuScreens | **D 维持（冻结内）** | FROZEN 边界；不触碰 |
| 停止条件 | 未触发 | 未发现崩溃/死锁；FROZEN 未触碰；无替换结论 |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向：客户端渲染（自研渲染 vs vanilla/成熟方案）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 自研 TargetOutlineRenderer 基于 vanilla RenderType/LevelRenderer.renderLineBox（vanilla API）+ 自定义 CompositeState（1.20.1 可行实现） | 无成熟"目标高亮渲染"库可对照（mod 通用做法即自定义 RenderType） |
| M2 Forge 1.20.1 兼容 | RenderLevelStageEvent.AFTER_PARTICLES + RenderType 自定义已验证（注释记录 AFTER_LEVEL 不可用） | — |
| M3 集成成本 | 自研被 ClientTargetState/ClientRoadState 直接消费；替换需重写渲染入口 | 引用面清单 |
| M4 客户端风险 | A1.1 高亮 USER_ACCEPTED（客户端矩阵通过） | 新渲染方案客户端矩阵 |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**结论**：无明确成熟候选替代（vanilla 无内置"目标高亮"渲染；mod 生态通用做法即自定义 RenderType，自研属该做法）；**只列方向不选型**。

---

## 特别核查项结论

### ① S2C 来源覆盖：客户端状态 ↔ packet 映射表（含盲区标注）

| 客户端状态 | 更新入口（setter） | 对应 S2C 包 | 服务端发送方 | 无服务端来源的状态（盲区） |
|---|---|---|---|---|
| `ClientTargetState.active/type/blockPos/entityId` | `update(TargetPacket)`（:23-28） | `TargetPacket` | `BotManager.broadcastTarget`（BotManager.java:305-311，`PacketDistributor.ALL`） | **无**——全部字段来自服务端 |
| `ClientRoadState.active/cells/positions` | `update(RoadPlanPacket)`（:20-26） | `RoadPlanPacket` | `RoadPlannerItem`（RoadPlannerItem.java:26,35，`PacketDistributor.ALL`） | **无**——全部字段来自服务端 |
| `ClientBotInventoryState.latest` | `update(BotInventoryPacket)`（ClientBotInventoryState.java:31-33） | `BotInventoryPacket` | `BotInventoryService.open/pushSnapshot`（BotInventoryService.java:53,202，`PacketDistributor.PLAYER.with(viewer)`） | **无**——全部字段来自服务端 |

**盲区结论（已确认）**：三个 Client*State 的**全部更新入口均有服务端 S2C 包来源**——客户端不存在自造状态（volatile 字段仅被 handle→enqueueWork→update 写）。**双端证据盲区不在"状态来源"而在"渲染效果客户端验收"**：
- ClientRoadState 渲染视觉效果：**无客户端矩阵记录**（服务端只证链路）——盲区；
- ClientBotInventoryState 的 Screen 渲染：**FROZEN 未测**——盲区（冻结线）；
- ClientTargetState 高亮：A1.1 USER_ACCEPTED——已覆盖。

### ② 渲染器边界（TargetOutlineRenderer 只读？）

**结论：纯只读渲染，不写回、不改决策**（已确认）：
- grep 全文件无 `CHANNEL/send/BotManager/setPos/setItem/applyAction/level.setBlock/destroyBlock`——**零写回/零决策调用**；
- 仅读 `ClientTargetState`/`ClientRoadState` 静态 getter + `level.getEntity(entityId)` 取实体 AABB（:113）+ `Minecraft.getInstance()` 取渲染上下文（:88）；
- RenderSystem 状态变更（disableDepthTest/depthMask :136-140）仅影响**渲染管线状态**，且显式恢复（:139-140 恢复 depthMask/深度测试），不触碰游戏逻辑。

### ③ @OnlyIn 标注（全员 Dist.CLIENT 隔离）

**结论：全员已标注，服务端不会误触发**（已确认）：

| 文件 | 标注 |
|---|---|
| ClientBotInventoryState | `@OnlyIn(Dist.CLIENT)`（:14） |
| ClientRoadState | `@OnlyIn(Dist.CLIENT)`（:13） |
| ClientTargetState | `@OnlyIn(Dist.CLIENT)`（:12） |
| BotInventoryScreen | `@OnlyIn(Dist.CLIENT)`（:25） |
| ClientMenuScreens | `@OnlyIn(Dist.CLIENT)`（:15）+ `@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = MOD)`（:16） |
| TargetOutlineRenderer | `@OnlyIn(Dist.CLIENT)`（:42）+ `@Mod.EventBusSubscriber(value = Dist.CLIENT)`（:43）+ 内部类 OutlineRenderType `@OnlyIn`（:51） |

**附加隔离证据**：三个 packet 的 handle 均检查 `getReceptionSide().isClient()` 才调 Client*State.update（TargetPacket.java:41 / RoadPlanPacket.java:35 / BotInventoryPacket.java:39-44 内 DistExecutor unsafeRunWhenOn CLIENT）——**双重隔离**（类级 @OnlyIn + 处理侧 reception-side 检查）。

---

## 与 active plan 冲突声明

- client 模块与 p1-client-sync-fix-v1 **无直接交叉**（client 是 S2C 接收/渲染侧，P1 线是 FakeConnection 广播发送侧；但共享"客户端可见性"教训——本模块的渲染效果盲区（RoadState/BotInventoryScreen）印证"服务端 PASS ≠ 客户端正确"）。若监督员需引用到 P1 线调查，写入 active plan Research Decision。
- GUI FROZEN（19af345）：BotInventoryScreen/ClientMenuScreens 属冻结线，本审查只核对未触碰。

---

## 证据分级汇总

- **已确认事实**：6 文件职责与行号；三个 Client*State 全部更新入口有 S2C 包来源（映射表）；TargetOutlineRenderer 零写回纯渲染；6 文件全部 @OnlyIn(Dist.CLIENT) + packet handle 双隔离；packet 发送方三处确认（BotManager/RoadPlannerItem/BotInventoryService）；AFTER_PARTICLES 阶段事实（注释记录）。
- **架构推论**：无（本模块证据充分；渲染效果客户端验收为独立待办）。
- **待调查**：ClientRoadState 渲染视觉效果客户端矩阵（road 批 3 关联）；BotInventoryScreen 渲染（FROZEN 解冻后）；目标高亮细节（透视/呼吸）客户端记录充分性。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、渲染改动均需监督员审核 + 用户批准。

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（批 2 第三个模块）**

独立核查：
1. S2C 来源映射属实：三个 Client*State 更新入口均有对应 packet——ClientTargetState←TargetPacket（BotManager.broadcastTarget :305/477 核实）、ClientRoadState←RoadPlanPacket（RoadPlannerItem :26/35 核实）、ClientBotInventoryState←BotInventoryPacket（BotInventoryService :53/202 核实）→ **状态来源层零盲区** ✅
2. 盲区定位正确：双端证据盲区不在状态来源，而在**渲染效果客户端验收**层——ClientRoadState 渲染无客户端矩阵（road 批 3 补）、BotInventoryScreen 渲染 FROZEN（19af345）✅
3. 渲染器边界：TargetOutlineRenderer 零写回（grep CHANNEL/send/setPos/setItem/applyAction 无命中）、RenderSystem 状态显式恢复 → 纯只读渲染 ✅
4. @OnlyIn 全员 6/6（grep 核实）+ packet handle 侧 reception-side 双隔离 → 服务端不会误触发 ✅
5. C4 有价值发现：AFTER_PARTICLES 是 1.20.1 最晚可用渲染阶段（AFTER_LEVEL 永不触发——Forge patch 事实注释记录）✅
6. 只读性：src 零改动、HEAD 仍 6c2b461 ✅

采纳为批 2 证据。下一步派发 network 模块审查（批 2 最后一个）。
