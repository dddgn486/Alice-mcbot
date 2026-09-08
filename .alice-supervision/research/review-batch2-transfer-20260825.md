# 批 2 审查报告：transfer 模块（传输线：ledger/request/selector 与 inventory 耦合）

- **任务**：`64394e57-014c-4991-9ffd-3fb024ede016`（批 2 / transfer 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 2/§2/§4/§5）、`review-batch1-task-20260825.md`（TransferTask D 维持、ledger 状态机、HARD_PATH_SEARCH_LIMIT 独立码、TransferFixture 8 场景）、`.alice-supervision/pending/2ef35e5.md`、`pending/f0ef6fc.md`、`.alice-supervision/client-tests/49be37e-a1-1.md`（A1.1 验收）、`docs/HANDOVER.md`。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（transfer/ 13 文件 + item/TransferEndpointSelectorEvents + command/BotCommand 引用）、pending 审核包、A1.1 客户端验收记录、grep 交叉引用。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：transfer = "Server-thread-only legs for fixed original single chests and the bot's 36 ordinary inventory slots. Simulation is evidence only, never a reservation or a cross-endpoint transaction"（ChestBotTransferPrimitive.java:19-22）+ "Independent SavedData ledger. It stores evidence, never item stacks or automatic recovery state"（TransferLedgerData.java:19）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `ChestBotTransferPrimitive.java` | 原语 | 单箱↔bot 库存的服务端转移原语：observe→simulate→fresh 校验→实际写入→prove 守恒 |
| `TransferLedgerData.java` | 状态机 | SavedData ledger：admit/transition/suspend/expire/abort + blocksBot 任务排他 + 16 状态机 |
| `TransferRequest.java` | 请求 | 不可变单批转移输入（requestId/actor/bot/端点/item/count/tick；构造期拒绝跨维/同端点/无效 count） |
| `TransferCodes.java` | 数据 | 35 个稳定机器可读结果码（capacity_rejected/source_insufficient/hard_path_* 等） |
| `CapacityPreflight.java` | 容量 | bot 36 普通槽纯容量计算（acceptsFullRequest/remainder/SlotEvidence） |
| `InventoryObservation.java` | 观测 | 不可变拷贝槽事实（identity/tick/slots/digest；永不保留 ItemStack） |
| `ChestEndpointRef.java` | 端点 | 固定原版单箱端点身份（维度+坐标；validate 校验 single chest + capability handler） |
| `TransferSelectionData.java` | 选择器 | 临时服务端草稿（DRAFTS map，EXPIRY_TICKS=2400，不持久化不入 ledger） |
| `TransferSelectionLifecycle.java` | 生命周期 | 服务端停止时清空选择草稿（@SubscribeEvent） |
| `TransferSelectionSubmission.java` | 提交 | 只读可选参数解析（默认 item/count + 显式覆盖）+ submit 构造（admission 委托） |
| `TransferSelectionFixture.java` | 夹具 | 选择草稿/默认解析/提交路径 fixture（13 断言） |
| `TransferFixture.java` | 夹具 | 8 场景服务端断言（normalLegs/capacity/sourceInsufficient/components/endpoint/simulationConflict/postMismatches/ledgerPolicies/hardPath/taskBudget/interrupt） |
| `TransferTestHooks.java` | 钩子 | 包私有 fixture 钩子（beforeFresh/afterActual，默认惰性） |

**注册方式**：TransferSelectionLifecycle 经 `@Mod.EventBusSubscriber(modid="alice", bus=FORGE)` 注册（TransferSelectionLifecycle.java:8）；ledger 经 `SavedData.computeIfAbsent`（TransferLedgerData.java:26-29）；其余为静态工具/被 BotManager/BotCommand/TransferTask 调用。

---

## 2.2 边界归属

- **归属线**：transfer = **传输业务主线**（服务端权威 ledger；A1.1 已 USER_ACCEPTED 不可翻案）。
- **相邻边界**：
  - 矿链（HARD_PATH）：TransferTask 仅消费 PathExecutor/SurfacePathfinder（task 审查 T10/T11 已确认）；
  - GUI（FROZEN，19af345）：**transfer 目录零 GUI 类引用**（grep 确认无 gui./BotInventoryMenu/ModMenuTypes/ClientMenuScreens/BotInventoryScreen）——**不触碰 FROZEN 线**；A9 验收"GUI 保持原版，无 Alice 拦截或额外写入"（49be37e-a1-1.md:44）；
  - network：transfer 的 S2C 包仅两处同步（ClientboundContainerSetSlotPacket 36 槽 + ClientboundSetEquipmentPacket 主手广播，ChestBotTransferPrimitive.java:233-248），**无 C2S 依赖**（服务端权威）——network 批后审。
- **R# 对照**：R26（HARD_PATH_SEARCH_LIMIT 独立码——task 报告 T11 + 本模块 TransferFixture hardPathMappings 交叉确认）；A1.1 验收边界（2ef35e5.md:34：conservative stop、no automatic retry/resume、ledger conservation、GUI isolation）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| X1 | 转移是"证据优先"仿真，非预留/跨端事务 | observe（pre×3）→ preflight → fresh 重校验 → 实际写入 → prove 守恒（ChestBotTransferPrimitive.java:27-49,51-75）；`TransferTestHooks` 在 fresh 前后注入变异（:35,42） | 静态核对 + 服务端可证 | **一致**；TransferFixture simulationConflict/postMismatches 断言（TransferFixture.java:129-163） |
| X2 | capacity_rejected：源满/目标满 → 不写入 | `sourcePreflight`/`destinationPreflight` 的 `simulatesFullInsert`/`simulatesFullExtract` + `CAPACITY_REJECTED`（:77-96）；`botCanAcceptFull`（:192-204） | 服务端可证 | **一致**；TransferFixture capacityRejected 断言 delta=0 不写入（:69-80） |
| X3 | hard_path_failed/search_limit/unreachable：路径中断保守停止 | TransferTask.move → `suspend(code, location)`（TransferTask.java:84-93）；FixtureMovementOutcome 三态映射（:77-82）；TransferFixture hardPathMappings 断言 SUSPENDED+manualTakeoverRequired（:204-226） | 服务端可证 | **一致**（task 审查 T11 + 本模块交叉确认） |
| X4 | 无自动 retry/resume（A1.1 A2/A5/A6/A7 语义） | ledger 无 retry 逻辑；SUSPENDED → 仅 manualTakeoverRequired（TransferLedgerData.java:91-118）；TransferTask 无重试（TransferTask.java 全文无 retry） | 静态核对 + 客户端验收 | **一致**（A1.1 A2 记录"无自动重试"） |
| X5 | ledger 守恒（A1.1 核心） | prove() 用三端 delta 对比 fresh 基线，不匹配 → unknown_discrepancy（ChestBotTransferPrimitive.java:98-110）；TransferFixture normalLegs 断言 source=-8/bot=+8/dest=0（:51-67） | 服务端可证 | **一致** |
| X6 | 状态机防非法迁移 | `transition` 拒绝 terminal→其他（TransferLedgerData.java:167-169）；IN_TRANSIT_BOT→MOVE_TO_DESTINATION 必须 BOT_INVENTORY（:170-173） | 静态核对 + 服务端可证 | **一致**；TransferFixture ledgerPolicies 断言（:165-202） |
| X7 | blocksBot 防无关任务替换 | `blocksBot` 检查 IN_TRANSIT_BOT/SUSPENDED（TransferLedgerData.java:121-124）；BotManager.replaceTaskIfRunning 调用（BotManager.java:452，task 审查已确认） | 服务端可证 | **一致** |
| X8 | 选择草稿临时服务端（不持久化） | DRAFTS IdentityHashMap + EXPIRY_TICKS=2400 + clearServer（TransferSelectionData.java:12-40）；TransferSelectionFixture 断言 draft 无写入/noDraftWrites（:48-49,29） | 服务端可证 | **一致** |
| X9 | 选择器不依赖客户端回传（服务端权威） | select/submit 全在服务端事件/命令内调用（TransferEndpointSelectorEvents.java:61、BotCommand.java:230）；**无 Serverbound 依赖**（grep 确认） | 静态核对 | **一致**（见特别核查③） |
| X10 | syncBotInventorySlots 同步 bot 库存到客户端 | 36 槽 `ClientboundContainerSetSlotPacket(-2,0,i,...)` + 主手 `ClientboundSetEquipmentPacket` broadcast（ChestBotTransferPrimitive.java:233-248） | 静态核对 + 客户端验收 | **一致**（f0ef6fc.md:48-49 记录 USER_ACCEPTED：V1-V3 客户端 PASS，含 equipment broadcast ef43bd6 修复） |
| X11 | 1.20.1 无 data-component 语义 | predicateCode 用 `hasUnsupportedComponents`（hasTag 检查，:154-159）；InventoryObservation.defaultStack（:62） | 静态核对 | **一致**（注释明确"1.20.1 represents non-default stack data as NBT"） |

---

## 2.4 已知坑与 R# 对照

| R# / 验收决策 | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| R26 | SEARCH_LIMIT ≠ 通道授权 | ✅ **遵守**：TransferCodes.HARD_PATH_SEARCH_LIMIT 独立码（:23）；TransferTask 区分 inconclusive（TransferTask.java:86）；TransferFixture hardPathMappings 断言（:204-226） | 与 task 报告 T11 交叉确认 |
| A1.1-A2 | 完整单批转移、无自动重试 | ✅ **遵守**：normalLegs delta 断言（TransferFixture.java:51-67）；无 retry 代码 | 49be37e-a1-1.md:37 |
| A1.1-A4 | capacity_rejected、source delta=0 | ✅ **遵守**：TransferFixture capacityRejected（:69-80） | 49be37e-a1-1.md:39 |
| A1.1-A5 | simulation_conflict、不自动重试、余量可解释 | ✅ **遵守**：simulationConflict + postMismatches（:129-163） | 49be37e-a1-1.md:40 |
| A1.1-A6/A7 | SUSPENDED、稳定 code、不自动恢复 | ✅ **遵守**：ledgerPolicies/taskInterruptPolicies（:165-202,265-289） | 49be37e-a1-1.md:41-42 |
| A1.1-A8 | abort 语义（未抽取可 ABORTED；在途 SUSPENDED 不改库存） | ✅ **遵守**：ledger.abort（TransferLedgerData.java:104-118）；TransferFixture abortProtected/abortUnmoved（:189-196） | 49be37e-a1-1.md:43 |
| A1.1-A9 | 原版 GUI 隔离（无 Alice 拦截） | ✅ **遵守**：transfer 零 GUI 引用；选择器事件"deliberately do not cancel"（TransferEndpointSelectorEvents.java:29） | 49be37e-a1-1.md:44 |
| 2ef35e5.md:35 | syncBotInventorySlots 客户端验证（当时 awaiting） | ✅ **已验收**：f0ef6fc.md:48-49 记录 USER_ACCEPTED（V1-V3 PASS） | 见特别核查② |

**边界漂移待监督员判定**：
- **D1**：`syncBotInventorySlots` 用 `-2` 作为 ContainerSetSlotPacket 的 containerId（ChestBotTransferPrimitive.java:236）——`-2` 是 vanilla 的 "player inventory" 容器 id（语义正确），但**注释缺失**（未说明 -2 = 玩家库存容器），建议补注释或常量（非功能问题）。
- **D2**：`CapacityPreflight.forBot` 与 `botCanAcceptFull`（ChestBotTransferPrimitive.java:82-83 + :192-204）是**两套并行的 bot 容量逻辑**——preflight 走纯函数 CapacityPreflight，运行时又走 botCanAcceptFull（含 canPlaceItem 检查）；TransferFixture capacityRejected 只覆盖 CapacityPreflight 路径，`botCanAcceptFull` 分支未独立断言——**待监督员确认是否需要统一或补 fixture**（非安全缺口，两者都只做拒绝不做部分写入）。

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- ledger 状态迁移/拒绝码/守恒（TransferFixture 8 场景 + TransferSelectionFixture 13 断言，服务端 PASS）；
- SEARCH_LIMIT/UNREACHABLE/FAILED → SUSPENDED + manualTakeover（hardPathMappings）；
- 选择草稿过期/清空/无写入（TransferSelectionFixture draftPass/expired/noDraftWrites）。

**需客户端实测（Windows）**：
- bot 库存客户端可见性（36 槽 SetSlot + 主手装备 broadcast）——**已有 USER_ACCEPTED 记录**（f0ef6fc.md:48-49，V1-V3：basic/multi-slot/selector path PASS）；
- 选择器右键交互（TransferEndpointSelector 事件流——客户端记录 5fa33ab/a7e02fd 已验收）；
- A9 GUI 隔离（原版箱子 GUI 无 Alice 拦截——客户端 PASS 记录）。

> **必须写明**：服务端 PASS ≠ 客户端正确。transfer 的 A1.1 是**少数同时具备服务端 fixture PASS + 客户端 USER_ACCEPTED 的线**（49be37e-a1-1.md + f655be2 记录）；但 `botCanAcceptFull`/`CapacityPreflight` 双路径（D2）与 `-2` containerId 语义（D1）属服务端细节，无需客户端验收；**新增选择器交互若改事件语义需重新客户端矩阵**（A1.1 不可翻案边界）。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| A1.1 核心语义（conservative stop/no retry/守恒/GUI 隔离） | 49be37e-a1-1.md A1-A9 + f655be2 客户端记录 | **USER_ACCEPTED**（不可翻案） | — |
| TransferFixture 8 场景 | selftest 内运行（BotSelftest.java:347）+ 2ef35e5.md 记录 PASS | 服务端 PASS | — |
| TransferSelectionFixture 13 断言 | selftest 内运行（BotSelftest.java:348）+ 2ef35e5.md 记录 PASS | 服务端 PASS | — |
| syncBotInventorySlots 客户端可见性 | f0ef6fc.md:48-49（V1-V3 PASS）+ a8b84b4 evidence latest.log | **USER_ACCEPTED** | — |
| 选择器右键交互 | 5fa33ab/a7e02fd 客户端记录 | USER_ACCEPTED | — |
| botCanAcceptFull 分支（D2） | 无独立 fixture | 静态核对 | 双路径容量逻辑未独立断言 |
| forcePickup 反射兜底（transfer 目录） | **无**（grep 确认 transfer 目录无 getDeclaredField/setAccessible） | 静态核对 | 见特别核查④ |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| ChestBotTransferPrimitive / TransferLedgerData / TransferRequest / TransferCodes / CapacityPreflight / InventoryObservation / ChestEndpointRef | **D 维持** | 与 A1.1 验收语义一致、服务端 fixture 全覆盖、USER_ACCEPTED 不可翻案 |
| TransferSelectionData / Lifecycle / Submission | **D 维持** | 临时服务端草稿、无客户端回传依赖、fixture 覆盖 |
| TransferFixture / SelectionFixture / TestHooks | **D 维持** | 断言丰富、含守恒/变异注入 |
| D1（`-2` containerId 注释缺失） | **D 维持（文档级）** | 语义正确（vanilla player inventory containerId），仅注释缺失 |
| D2（容量双路径未统一） | **A 补测试（可选）** | botCanAcceptFull 分支无独立 fixture；不涉安全（只拒绝不部分写） |
| 停止条件 | 未触发 | 未发现崩溃/死锁/数据损坏；A1.1 边界一致；GUI FROZEN 未触碰 |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向：transfer 原语/ledger（自研 vs 现成方案）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 自研基于 Forge `IItemHandler` capability（ChestEndpointRef.java:38-40）+ SavedData（TransferLedgerData.java:26-29）——原版/Forge 原生机制，非重复造轮子 | 无现成"带守恒证据的转移 ledger"成熟库（通用库无审计语义） |
| M2 Forge 1.20.1 兼容 | IItemHandler/SavedData 均为 Forge 1.20.1 原生 API | — |
| M3 集成成本 | ledger 与 BotManager/TransferTask/GUI 服务端权威耦合；替换需重写 8 fixture 场景 | 引用面清单（transfer 被 10+ 文件消费） |
| M4 客户端风险 | A1.1 USER_ACCEPTED（客户端矩阵已通过）；替换会翻案 A1.1 | 新方案客户端矩阵 |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**结论**：无明确成熟候选替代"证据优先 ledger + 守恒仿真"模型（该模型是 A1.1 验收语义的载体，且基于 Forge 原生机制）；`IItemHandler` capability 层是 vanilla Container 机制的 Forge 封装，属"借鉴原版机制"而非自研重复。**只列方向不选型；任何替换都需翻案 A1.1 USER_ACCEPTED，须用户批准。**

---

## 特别核查项结论

### ① A1.1 验收语义保持（逐语义核对）

| A1.1 语义 | 代码证据 | 结论 |
|---|---|---|
| capacity_rejected（源满/目标满） | sourcePreflight/destinationPreflight → `CAPACITY_REJECTED`（ChestBotTransferPrimitive.java:77-96）；fixture 断言 delta=0 不写入（TransferFixture.java:69-80） | ✅ **保持** |
| hard_path_failed（路径中断保守停止） | TransferTask → `HARD_PATH_FAILED/SEARCH_LIMIT/UNREACHABLE` → SUSPENDED + manualTakeover（TransferTask.java:84-93 + TransferFixture.java:204-226） | ✅ **保持** |
| 保守停止 | SUSPENDED 后仅 manualTakeoverRequired，无自动恢复（TransferLedgerData.java:91-118）；A6/A7 客户端记录 | ✅ **保持** |
| 无自动 retry/resume | 全模块无 retry/resume 逻辑（grep）；A2/A5 客户端记录"无自动重试" | ✅ **保持** |
| ledger 守恒 | prove() 三端 delta 对比（:98-110）+ fresh 重校验（:125-136）+ 变异注入钩子（:35,42）；fixture normalLegs/postMismatches（:51-67,142-163） | ✅ **保持** |
| GUI 隔离（A9） | transfer 零 GUI 引用；事件"deliberately not cancel"（TransferEndpointSelectorEvents.java:29） | ✅ **保持** |

**A1.1 不可翻案边界确认**：49be37e-a1-1.md 记录 A1-A9 全部通过 + USER_ACCEPTED（2ef35e5.md:34）；当前代码与验收语义逐一相符——**不可翻案项未被动摇**。

### ② inventory 同步缺口：syncBotInventorySlots 当前实现与客户端验证状态

**实现**：36 槽 `ClientboundContainerSetSlotPacket(-2, 0, i, item)`（bot 自身 connection，走 FakeConnection）→ 主手 `ClientboundSetEquipmentPacket` 经 `chunkSource.broadcast(bot, packet)` 广播给追踪玩家（ChestBotTransferPrimitive.java:233-248）。

**验证状态（已确认）**：
- f0ef6fc.md:48-49：bot inventory client sync fix **USER_ACCEPTED**——"Client acceptance: Bot inventory now visible client-side… the same sync primitive covers both"；客户端 V1-V3（basic/multi-slot/selector path）PASS；
- 装备渲染修复 ef43bd6（a8b84b4 evidence latest.log 01:47-01:49）：V1 basic（3 iron_ingot）、V2 different items、V3 multi-slot 全 PASS；
- 根因教训（fake-player-equipment-sync 报告）：`bot.connection.send()` 走 FakeConnection 空实现丢弃 → 必须 `chunkSource.broadcast` 给真实追踪玩家（ChestBotTransferPrimitive.java:246 已按此修复）。

**结论**：**同步缺口已闭合（USER_ACCEPTED）**——36 槽 SetSlot + 主手 broadcast 均有客户端证据；不是"待验证"状态。D1 仅剩 `-2` containerId 注释缺失（文档级）。

### ③ 选择器是否依赖客户端回传（服务端权威要求）

**结论：不依赖（服务端权威成立）**：
- 选择草稿：`TransferSelectionData.select` 在**服务端事件**（PlayerInteractEvent.RightClickBlock，TransferEndpointSelectorEvents.java:61）与**服务端命令**（BotCommand.java:230）内调用，写入服务端 DRAFTS map；
- 提交：`TransferSelectionSubmission.submit` 全服务端解析（resolve 用服务端 handler 读库存，:27-50），admission 委托 BotManager.assignTransfer（服务端）；
- **无 Serverbound 依赖**：transfer 目录仅 2 处 S2C 同步包（见 X10），无任何 C2S/客户端回传输入；
- TransferSelectionFixture 断言"draft 无写入"（noDraftWrites，:48-49,29）证明选择不触库存。

**结论**：符合服务端权威要求；无需"用户决策/待 network 批核对"标记（network 批仅核对 S2C 方向包，不改变此结论）。

### ④ forcePickup 反射兜底证据等级（transfer 目录）

**结论：transfer 目录无同类反射兜底**（grep `getDeclaredField\|setAccessible` 零命中）。`forcePickup` 属 **task/DropCollectionTask.java:231-240**（task 模块），是拾取兜底（反射清 pickupDelay 后 playerTouch），与 transfer 无关。transfer 模块的兜底机制是 `TransferTestHooks`（包私有 fixture 钩子，非生产路径）与 ledger manualTakeover（人工接管契约）——均为**显式设计**而非反射。

**证据等级**：DropCollectionTask.forcePickup = 静态核对（task 报告 T13 已记录，无独立客户端证据，等级"无证据"）；transfer 模块无反射兜底，故本核查项为"不适用/已排除"。

---

## 与 active plan 冲突声明

- transfer 模块与 p1-client-sync-fix-v1 **无直接交叉**（transfer 的 S2C 同步已 USER_ACCEPTED 且走 `chunkSource.broadcast`——恰是 P1 线"广播 vs send"教训的正确形态：ChestBotTransferPrimitive.java:246 用 broadcast，:236 用 bot.connection.send 的 36 槽走 FakeConnection 空实现，语义为"bot 自己收不到无需发"，非 P1 线修改对象）。若监督员需引用本证据到 P1 线调查，须写入 active plan Research Decision。
- GUI FROZEN（19af345）：transfer 零 GUI 引用（2.2 已确认）——**未触碰冻结边界**。

---

## 证据分级汇总

- **已确认事实**：13 文件职责与行号；A1.1 六项语义逐一保持；syncBotInventorySlots USER_ACCEPTED（f0ef6fc.md:48-49 + a8b84b4 evidence）；选择器服务端权威（无 Serverbound）；transfer 无 GUI 引用；无反射兜底；ledger 状态机/守恒/无 retry 实现；TransferFixture/SelectionFixture 断言覆盖。
- **架构推论**：D1（`-2` containerId 语义正确但注释缺失）；D2（CapacityPreflight/botCanAcceptFull 双路径并行，未统一）。
- **待调查**：D2 是否需要统一容量逻辑或补 botCanAcceptFull 独立 fixture（待监督员判定）；network 批对 S2C 方向的进一步核对（不改变服务端权威结论）。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、修复派发均需监督员审核 + 用户批准（A1.1 不可翻案项的任何变更都需用户重新批准）。

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（批 2 第一个模块）**

独立核查：
1. A1.1 六项语义（capacity_rejected / hard_path_failed 保守停止 / 无自动 retry / ledger 守恒 / GUI 隔离 / abort 语义）逐一与代码+fixture 核对——**全部保持**，不可翻案项未被动摇 ✅
2. syncBotInventorySlots：**已 USER_ACCEPTED**（f0ef6fc.md:48-49 V1-V3 + a8b84b4 equipment evidence），非"待验证"；`-2` containerId 语义正确（vanilla player inventory），仅注释缺失（D1）✅
3. 服务端权威成立：transfer 零 GUI 引用（grep 核实）+ 零 C2S/Serverbound（grep 核实）→ 无客户端回传依赖 ✅
4. forcePickup 属 task/DropCollectionTask（task 报告 T13），transfer 无反射兜底（grep getDeclaredField/setAccessible 零命中）——排除项 ✅
5. **P1 线宝贵对照证据**：transfer 的 S2C 同步用 `chunkSource.broadcast(bot, packet)`（ChestBotTransferPrimitive.java:246）——恰是 P1 线 ab510fd FakeConnection `send()` 循环广播的"正确形态"对照（追踪广播 vs 全玩家广播）✅
6. 风险等级：transfer 核心全部 D 维持；D1 文档级 D；D2（CapacityPreflight/botCanAcceptFull 双容量路径）A 补测试（可选）——待监督员判定 ✅
7. 只读性：src 零改动、HEAD 仍 6c2b461 ✅

采纳为批 2 证据。下一步派发 gui 模块审查（FROZEN 边界核对 + 方向 B 死代码核查）。
