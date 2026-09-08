# 批 2 审查报告：network 模块（协议/方向/packet 白名单）——批 2 收尾

- **任务**：`f0c2057a-f9af-44f7-929d-000679d5f3d2`（批 2 / network 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 2/§2/§4/§5）、`review-batch2-transfer-20260825.md`（S2C 仅 2 处、无 C2S 依赖）、`review-batch2-client-20260825.md`（Client*State↔packet 映射表、handle 双隔离）、`review-batch2-gui-20260825.md`（方向 B packet、decode 上限发现）。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（network/ 5 文件 + 跨模块发送/接收方 grep）、gui/client/transfer 三模块报告交叉核对。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：network = "Alice 网络通道(S2C 为主：任务目标同步)。1.20.1 SimpleChannel 老式注册；协议版本不一致时拒连，避免包格式错位"（AliceNetwork.java:8-10）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `AliceNetwork.java` | 通道 | SimpleChannel 注册（alice:main，PROTOCOL_VERSION="1"）+ 4 个 packet 注册（id 0-3） |
| `TargetPacket.java` | 包 S2C | 任务目标同步（active/type/blockPos/entityId → ClientTargetState） |
| `RoadPlanPacket.java` | 包 S2C | 道路数学体素预览（active/cells → ClientRoadState） |
| `BotInventoryPacket.java` | 包 S2C | bot 库存权威快照（botId/name/41 slots → ClientBotInventoryState） |
| `BotInventoryActionPacket.java` | 包 C2S | 客户端库存意图（botId/action/slotIndex/playerSlot → 服务端 applyAction） |

**注册方式**：`AliceNetwork.register()` 在 mod 构造时调用（AliceMod.java:30）；4 个 packet 用 `CHANNEL.registerMessage(nextId++, ...)` 注册（AliceNetwork.java:27-40）。

---

## 2.2 边界归属

- **归属线**：network = **协议层**（服务端↔客户端媒质）；是 transfer/gui/client 三模块的 packet 传输通道。
- **相邻边界**：
  - transfer：无 C2S 依赖（transfer 审查确认），S2C 仅 2 处同步包（ChestBotTransferPrimitive 直接 Connection.send，**不经 SimpleChannel**——见 2.3 N6）；
  - gui（FROZEN）：BotInventoryPacket（S2C）/BotInventoryActionPacket（C2S）属方向 B 冻结线——本审查只核对不触碰；
  - client：三个 packet handle → Client*State.update（client 审查映射表确认）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| N1 | channel 注册 alice:main + 版本 "1" | `newSimpleChannel(RL("alice","main"), ()->"1", "1"::equals, "1"::equals)`（AliceNetwork.java:15-19） | 静态核对 | **一致**（版本不一致拒连——newSimpleChannel 的 clientAcceptedVersions/serverAcceptedVersions 均严格等于 "1"） |
| N2 | 4 个 packet 注册（id 0-3） | TargetPacket id0、RoadPlanPacket id1、BotInventoryPacket id2（均 PLAY_TO_CLIENT）、BotInventoryActionPacket id3（PLAY_TO_SERVER）（:28-39） | 静态核对 | **一致**（方向声明明确，见特别核查②） |
| N3 | 各 packet encode/decode/handle 三件套 | 4 个 packet 均有 encode/decode/handle（TargetPacket.java:21-45 / RoadPlanPacket.java:14-39 / BotInventoryPacket / BotInventoryActionPacket） | 静态核对 | **一致** |
| N4 | decode 边界（防恶意包） | RoadPlanPacket `Math.min(readVarInt(), 8192)`（:24）+ kind 越界 clamp（:28）；BotInventoryPacket `Math.min(readVarInt(), 256)`（:33）；TargetPacket 无集合（标量）；ActionPacket 无上限（见特别核查③） | 静态核对 | **部分有界**（见特别核查③） |
| N5 | handle 仅客户端消费（双隔离） | 3 个 S2C handle 检查 `receptionSide().isClient()`（TargetPacket:41/RoadPlanPacket:35/BotInventoryPacket:39-44 内 DistExecutor CLIENT）；ActionPacket handle 检查 `isServer()`（BotInventoryActionPacket.java:36） | 静态核对 | **一致**（client 审查②已确认） |
| N6 | transfer 的同步包不经 SimpleChannel | ChestBotTransferPrimitive 用 `bot.connection.send(ClientboundContainerSetSlotPacket)` + `chunkSource.broadcast(ClientboundSetEquipmentPacket)`（transfer 审查 X10）——**vanilla 包直发，非 Alice packet** | 静态核对 | **一致**（transfer S2C 走 vanilla 路径，Alice packet 白名单不含这些） |

---

## 2.4 R#/决策对照

| 决策/R# | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| FROZEN（19af345） | 方向 B 的 BotInventoryPacket/ActionPacket 属冻结线 | ✅ **遵守**：packet 定义与使用未变（gui 审查确认）；本审查不触碰 | gui 审查 2.4 |
| 方向 B（9534a5d） | 自定义 packet+Screen | ✅ **遵守**：BotInventoryPacket（S2C）+ BotInventoryActionPacket（C2S）在 9534a5d 引入 | git show 9534a5d |
| 服务端权威（transfer/gui 决策） | C2S 面最小、客户端不写本地 | ✅ **遵守**：仅 1 个 C2S（见特别核查④）；S2C 均服务端推送 | 本报告特别核查①④ |

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- channel 注册/方向声明（静态核对）；
- encode/decode 往返一致性（可 headless 用 FriendlyByteBuf 往返断言——当前无专门网络 fixture，但 BotInventoryFixture/TransferSelectionFixture 未覆盖网络编解码，属证据缺口）；
- decode 边界（clamp 逻辑可单测）。

**需客户端实测（Windows）**：
- 包往返时序（S2C 推送 → 渲染 → C2S 意图 → 快照刷新）——方向 B 的 BotInventoryPacket/ActionPacket 往返 **FROZEN 未测**；
- 高亮/道路渲染（client 审查已列盲区）。

> **必须写明**：服务端 PASS ≠ 客户端正确。network 的 encode/decode 往返即使 headless PASS，也不证明客户端渲染/交互正确（方向 B 8 轮失败正是"服务端包正确 + 客户端 view-index 猜错"）；且当前**无网络编解码 fixture**（encode/decode 未在任何 fixture 中直接断言——BotInventoryFixture 直接调 Service 不经过包，属证据缺口）。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| channel 注册/方向声明 | 静态核对（本报告） | 静态核对 | — |
| encode/decode 往返 | 无专门 fixture | **无证据** | **缺网络编解码 fixture**（encode→decode→断言字段） |
| TargetPacket → 高亮 | A1.1 USER_ACCEPTED（client 审查） | USER_ACCEPTED | — |
| RoadPlanPacket → 道路渲染 | 链路 PASS（client 审查） | 服务端 PASS | 渲染客户端矩阵（road 批 3） |
| BotInventoryPacket/ActionPacket 往返 | **FROZEN** | FROZEN | 全部客户端场景 |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| AliceNetwork / TargetPacket / RoadPlanPacket | **D 维持** | 方向正确、使用方明确、A1.1 高亮 USER_ACCEPTED |
| BotInventoryPacket / BotInventoryActionPacket | **D 维持（冻结内）** | 方向 B 冻结线；不触碰 |
| decode 边界（ActionPacket 无上限） | **A 补测试（可选）** | 服务端校验兜底已确认（见特别核查③），但网络层无上限 + 无编解码 fixture——补 fixture 可固化边界 |
| 网络编解码 fixture 缺失 | **A 补测试** | 4 个 packet encode/decode 无 headless 往返断言 |
| 停止条件 | 未触发 | 未发现崩溃/死锁；FROZEN 未触碰；无替换结论 |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向：网络方案（SimpleChannel vs vanilla 自定义 payload vs 其他 mod 网络栈）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | Forge SimpleChannel 是 1.20.1 标准 mod 网络 API（广泛使用、文档完善） | — |
| M2 Forge 1.20.1 兼容 | SimpleChannel 原生兼容 | — |
| M3 集成成本 | 4 packet 已注册，使用方 7 文件；替换需重写注册+全部 handle | 引用面清单（已 grep：BotManager/Client*State/RoadPlannerItem/BotInventoryService/Screen） |
| M4 客户端风险 | 方向 B 客户端 FROZEN（包往返未验收） | 新方案客户端矩阵 |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**结论**：SimpleChannel 是 Forge 1.20.1 标准做法（非重复造轮子），无明确成熟替代候选；**只列方向不选型**。

---

## 特别核查项结论

### ① packet 白名单全集（AliceNetwork.register 逐一列出）

**结论：白名单 = 4 个 packet，无遗留/无关包**（已确认）：

| id | 类名 | 声明方向 | 发送方 | 接收方/消费 | 对应状态或动作 |
|---|---|---|---|---|---|
| 0 | `TargetPacket` | PLAY_TO_CLIENT（:30） | `BotManager.broadcastTarget`（BotManager.java:305-311，PacketDistributor.ALL） | `ClientTargetState.update`（TargetPacket.java:42） | 任务目标高亮 |
| 1 | `RoadPlanPacket` | PLAY_TO_CLIENT（:33） | `RoadPlannerItem`（RoadPlannerItem.java:26,35，PacketDistributor.ALL） | `ClientRoadState.update`（RoadPlanPacket.java:36） | 道路体素预览 |
| 2 | `BotInventoryPacket` | PLAY_TO_CLIENT（:36） | `BotInventoryService.open/pushSnapshot`（BotInventoryService.java:53,202，PacketDistributor.PLAYER.with(viewer)） | `ClientBotInventoryState.update` + openScreen | bot 库存快照 |
| 3 | `BotInventoryActionPacket` | PLAY_TO_SERVER（:39） | `BotInventoryScreen`（C2S sendToServer） | `BotInventoryService.applyAction`（BotInventoryActionPacket.java:44） | 库存动作意图 |

**交叉核对**：transfer 的 S2C 同步（SetSlot/SetEquipment）走 vanilla 包直发（不经 SimpleChannel），**不在白名单内——正确**（transfer 审查 X10 确认）；无遗留测试包/调试包注册。

### ② 方向一致性（声明方向 vs 实际收发侧）

**结论：4 个 packet 声明与使用完全一致**（已确认）：
- **TargetPacket（声明 PLAY_TO_CLIENT）**：发送方 BotManager（服务端，grep :305-311）；接收方 ClientTargetState.handle 检查 `isClient()`（:41）→ **一致**；
- **RoadPlanPacket（声明 PLAY_TO_CLIENT）**：发送方 RoadPlannerItem（服务端）；接收方 ClientRoadState.handle `isClient()`（:35）→ **一致**；
- **BotInventoryPacket（声明 PLAY_TO_CLIENT）**：发送方 BotInventoryService（服务端）；接收方 handle `DistExecutor CLIENT`（:39-44）→ **一致**；
- **BotInventoryActionPacket（声明 PLAY_TO_SERVER）**：发送方 BotInventoryScreen（客户端）；接收方 handle 检查 `isServer()`（:36-37）→ **一致**。
- **一致性机制**：SimpleChannel.registerMessage 的第 6 参 `Optional<NetworkDirection>` 即强制方向校验（错向收发会被通道层拒绝）——4 个均显式声明，无 `Optional.empty()`（无方向歧义）。

### ③ 版本与解码边界

**版本（已确认）**：`PROTOCOL_VERSION = "1"`（AliceNetwork.java:14）；`newSimpleChannel` 的 `clientAcceptedVersions`/`serverAcceptedVersions` 均为 `PROTOCOL_VERSION::equals`（:18-19）→ **版本不匹配时连接被拒（握手失败），不会静默错位**（类注释明示"协议版本不一致时拒连"）。注意：该行为是 Forge SimpleChannel 握手语义（通道层处理），本模块无额外版本字段。

**解码边界（已确认，gui 审查交叉核实）**：

| packet | decode 上限 | 说明 |
|---|---|---|
| TargetPacket | 无集合（标量：boolean/int/BlockPos/int） | 无容量风险 |
| RoadPlanPacket | `Math.min(readVarInt(), 8192)`（:24）+ kind `Math.max(0, Math.min(readByte(), len-1))` clamp（:28） | **有界**（防超大列表/越界枚举） |
| BotInventoryPacket | `Math.min(readVarInt(), 256)`（:33） | **有界**（但 256 > 实际 41 槽——上限宽于语义，客户端 update 会存 256 项？见 D1） |
| BotInventoryActionPacket | **无 decode 上限**（slotIndex/playerSlot 原样 varInt） | **无上限但服务端校验兜底**（BotInventoryService:78 `slotIndex < 0 || >= SLOT_COUNT` → invalid_slot；:117 playerSlot 范围 → invalid_player_slot）——**网络层无界 + 应用层有界，安全成立**（恶意包被服务端拒绝，不会越界访问） |

**D1（推论）**：BotInventoryPacket.decode 上限 256 宽于 SLOT_COUNT=41——客户端 `ClientBotInventoryState` 会存储 256 槽快照（若恶意/异常服务端发 256 项），而 Screen 渲染 41 槽（BotInventoryScreen 只读前 41）——**实际无越界风险（渲染有界），但快照槽数与 SLOT_COUNT 语义不一致**（待监督员判定是否收窄到 41 或加校验；属冻结线内 packet——修改需解冻）。

### ④ C2S 最小面

**结论：C2S 仅 1 个 packet（BotInventoryActionPacket）**（已确认）：
- grep 全工程：PLAY_TO_SERVER 声明仅 AliceNetwork.java:39（BotInventoryActionPacket）一处；
- 其余 3 个均 PLAY_TO_CLIENT；
- transfer 无 C2S 依赖（transfer 审查确认——转移请求全部服务端内发起：命令/选择器事件）；
- **服务端权威要求满足**：客户端→服务端的唯一意图通道 = 库存动作（且 handle 只转发意图，服务端 applyAction 校验/写入/回推快照——客户端永不写本地）。

---

## 与 active plan 冲突声明

- network 模块与 p1-client-sync-fix-v1 **无直接交叉**（P1 线是 FakeConnection.send 广播 vanilla 位置/速度包，不经 AliceNetwork SimpleChannel；本模块 4 个 packet 与 P1 线无关）。若监督员需引用"版本拒连/方向校验"机制到 P1 线调查，写入 active plan Research Decision。
- GUI FROZEN（19af345）：BotInventoryPacket/BotInventoryActionPacket 属方向 B 冻结线，本审查只核对未触碰（含 D1 仅列推论，不提交修改）。

---

## 证据分级汇总

- **已确认事实**：channel 注册/版本/4 packet 白名单与方向声明；发送/接收方 grep 交叉核对；decode 边界（RoadPlan/BotInventory 有界、Action 服务端兜底）；版本不匹配拒连语义；C2S 仅 1 个；transfer 同步走 vanilla 包不进白名单；handle 双隔离。
- **架构推论**：D1（BotInventoryPacket decode 上限 256 宽于 SLOT_COUNT=41——渲染有界无越界风险，槽数语义不一致待监督员判定）。
- **待调查**：网络编解码 fixture（4 packet encode→decode 往返 headless 断言——当前零覆盖）；方向 B 客户端往返（FROZEN）；RoadPlanPacket 渲染客户端矩阵（road 批 3）。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、packet 修改（含 D1）均需监督员审核 + 用户批准（冻结线内 packet 修改需先解冻）。

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（批 2 最后一个模块，批 2 至此全部完成）**

独立核查：
1. packet 白名单核实：AliceNetwork.java:27-40 注册 4 包（TargetPacket id0/RoadPlanPacket id1/BotInventoryPacket id2 均 PLAY_TO_CLIENT、BotInventoryActionPacket id3 PLAY_TO_SERVER）——无遗留/无关包；transfer 的 S2C 同步（SetSlot/SetEquipment）走 vanilla 直发不在白名单，正确 ✅
2. 方向一致性：4 包声明方向与收发侧全一致（SimpleChannel 第 6 参强制方向校验，无 Optional.empty()）✅
3. 版本拒连核实：PROTOCOL_VERSION="1" + clientAcceptedVersions/serverAcceptedVersions 均严格 equals（AliceNetwork.java:15-19）——版本不匹配拒连不静默错位 ✅
4. C2S 最小面核实：PLAY_TO_SERVER 全工程仅 AliceNetwork.java:39 一处（grep 核实）——服务端权威成立 ✅
5. decode 边界：RoadPlan 有界（Math.min 8192 + clamp）、BotInventory 有界但上限 256 宽于 41 槽（D1 推论：渲染有界无越界风险，槽数语义不一致待监督员判定，冻结线内修改需解冻）、ActionPacket 无上限但服务端校验兜底（invalid_slot/invalid_player_slot）——安全成立 ✅
6. 证据缺口：网络编解码 fixture 缺失（4 packet encode→decode 往返零覆盖）——A 补测试项 ✅
7. 只读性：src 零改动、HEAD 仍 6c2b461 ✅

**批 2（transfer/gui/client/network）至此全部验收完成。**
