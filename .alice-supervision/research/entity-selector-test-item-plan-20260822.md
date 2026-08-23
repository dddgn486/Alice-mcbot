# A1.1 转移测试实体选择物品 —— 最小实现线路规划

- 日期：2026-08-22
- 角色：Alice 实现规划员+（只读）
- Git 基线：`49be37efb57375f4e4c82ee6d6f4fb18dfb98d85`（HEAD == origin/master 一致；工作区仅未提交治理文件）
- 前置受理：`.alice-supervision/reviews/20260822-entity-selector-test-tool-request.md`（申请 A 受理为新测试入口工作包，先规划后呈报用户批准）
- 协议依据：`docs/SUPERVISION_PROTOCOL.md:95`（新增第 7 条：复杂定位参数默认提供实体测试物品，仅测试/管理员用途，服务端按同一验证路径处理，不得改变被测语义/拦截原版交互/接入普通任务链，且必须作为专门批准的工作包）

> **本规划不构成实现授权。** 未经监督员采纳、用户批准并建立新的 `APPROVED_FOR_IMPLEMENTATION` 工作包，不得实现。A1.1 当前状态保持 `CLIENT_TEST_PENDING`。

## 1. 结论概览

为 A1.1 新增**独立测试物品** `alice:transfer_endpoint_selector`（建议名），作为「端点选择草稿」入口：

- 右键点击容器方块 = 选择 **destination**；Shift+右键 = 选择 **source**（与用户申请一致）。
- 选择只写入**服务端内存、按玩家 UUID 的短期草稿**（dimension + immutable BlockPos + selectedTick），**非持久、不入 ledger、不入物品 NBT**。
- 点击时立即做只读端点校验（复用 `ChestEndpointRef.validate`），失败返回稳定 code 且不记录。
- 转移真正发起仍必须走**显式命令**：新增短命令 `/alice transfer-selection submit <item> <count>`（或 `/alice transfer-test from-selection <item> <count>`），消费草稿构造 `TransferRequest` 后复用现有 `BotManager.assignTransfer` 同一 admission 路径。
- **item/count 只由命令提供，不从物品 NBT 读写**（见 §4 取舍）。

本包不修改 `TransferRequest`/`ChestEndpointRef`/`CapacityPreflight`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`TransferTask`/`BotManager.assignTransfer` 的任何语义；只消费既有入口。

## 2. 已确认事实（固定路径证据）

1. 当前 active plan 第 42 行仍把 `Binding tool, Shift/right-click handling, packets/client UI` 列为 Explicitly Out of Scope（`.alice-supervision/active-plan.md:42`）。因此本申请必须作为**新工作包**放开，不能并入现有 A1.1 计划；监督员受理记录亦确认（`.alice-supervision/reviews/20260822-entity-selector-test-tool-request.md:15,25`）。
2. A1.1 状态：`CLIENT_TEST_PENDING`（HANDOVER `docs/HANDOVER.md:62`；监督二审 PASS 见 `.alice-supervision/reviews/20260822-49be37e-a1-1-review.md`）。未获用户 Windows 验收前，任何代码不得改变 A1.1 语义，本包独立验收。
3. 物品注册模式：`AliceItems.ITEMS`（DeferredRegister）已注册 5 个独立物品；各物品独立类 + `assets/alice/models/item/*.json` + `lang/en_us.json`、`zh_cn.json` 条目（`src/main/java/com/dddgn/alice/item/AliceItems.java:15-42`；`assets/alice/models/item/soft_path_probe_selector.json`；`assets/alice/lang/en_us.json:1-6`）。
4. 现有 `useOn` 先例：`RoadPlannerItem`（右键选点/Shift 重置）、`SoftMoveSelector`（右键 NATIVE_TRAVEL/Shift SELF_MOVE）、`TargetSelector`（右键挖/Shift 放）、`SoftPathProbeSelector`（点击支撑方块）；均在 `useOn` 内 `level.isClientSide` 提前返回，服务端分支执行（`src/main/java/com/dddgn/alice/item/RoadPlannerItem.java:19-49` 等）。
5. C1 扫描器 `ScanWand` 走 Forge 事件并 `event.setCanceled(true)` 拦截默认交互（`src/main/java/com/dddgn/alice/tool/ScanWand.java:28-49`）。**禁止复用该取消模式**：本物品必须返回 `InteractionResult.PASS` 让原版箱子 GUI 正常打开。
6. 转移命令路径：`/alice transfer-test <source> <destination> <item> <count>` → `BotCommand.transfer` 构造 `TransferRequest` → `BotManager.assignTransfer` 统一 admission（`src/main/java/com/dddgn/alice/command/BotCommand.java:99-108,142-159`；`src/main/java/com/dddgn/alice/bot/BotManager.java:247-261`）。命令整体 `requires(hasPermission(2))`（`BotCommand.java:54`）。
7. `TransferRequest` 构造器即校验：`invalid_count`、`cross_dimension_rejected`、`same_endpoint_rejected`（`src/main/java/com/dddgn/alice/transfer/TransferRequest.java:12-28`）。`ChestEndpointRef.validate` 校验同维度/`hasChunkAt`/单箱/`ChestBlockEntity`/handler，返回稳定 code（`src/main/java/com/dddgn/alice/transfer/ChestEndpointRef.java:23-41`）。
8. 既有 `useOn` 物品均无服务端权限检查（只读核实 `TargetSelector`/`SoftMoveSelector`/`RoadPlannerItem`/`SoftPathProbeSelector`）。新物品必须补 permission level 2 gate，因它服务于 C2 写操作测试入口。
9. ledger 现状（49be37e）：abort 对在途物品保留 `SUSPENDED/BOT_INVENTORY/manual_takeover` 保护（`TransferLedgerData.abort` `src/main/java/com/dddgn/alice/transfer/TransferLedgerData.java:103-118`）；`expireSuspensions` 实现 12,000 tick 挂起上限（`TransferLedgerData.java:91-101`）；`blocksBot` 阻止 `IN_TRANSIT_BOT/SUSPENDED` 时普通任务替换（`TransferLedgerData.java:120-124`；`BotManager.java:434-448`）；`assignFollow` 等入口均已在 49be37e 检查 `replaceTaskIfRunning()` 返回值（`BotManager.java:465-478`）。本规划不得回退这些修复。
10. fixture 已覆盖 200/2,400 tick、in-transit 超时、`SEARCH_LIMIT/UNREACHABLE/FAILED` 映射、挂起过期、abort 保护（`src/main/java/com/dddgn/alice/transfer/TransferFixture.java:165-263`）；仍不覆盖 actor disconnect、Brigadier 解析/权限、完整 live tick 生命周期（HANDOVER `docs/HANDOVER.md:61`）——本包的选择→提交路径同样保持 headless 不替代 Windows。

## 3. 最小实现线路

### 3.1 新增文件与职责边界

| 文件（建议） | 最小职责 | 不得承担 |
|---|---|---|
| `item/TransferEndpointSelector.java` | `useOn`：服务端权限 gate；解析 `getClickedPos()` 为 `ChestEndpointRef(dimension, pos)`；调用 `validate` 只读校验；按 Shift 写 source/右键写 destination 到玩家草稿；返回 `PASS`（不拦截 GUI）；反馈 chat 消息 | 创建任务、spawn bot、写库存/箱子、写 ledger、写物品 NBT、调用 C1 扫描、拦截原版交互 |
| `item/AliceItems.java`（修改） | 注册 `transfer_endpoint_selector`（新 `Item` 子类） | 复用/修改既有物品注册 |
| `transfer/TransferSelectionData.java`（新） | 服务端内存草稿：`Map<UUID player, SelectionDraft{source,destination,selectedTick}>`；超时清理；`clear`；只读 `status`；不持久化 | SavedData/ledger 扩展、跨重启恢复、ACL |
| `command/BotCommand.java`（修改） | `transfer-selection status`（只读）、`transfer-selection clear`、`transfer-selection submit <item> <count>`（复用 `BotManager.assignTransfer`） | 修改既有 `transfer-test` 参数语义、绕过权限 |
| 资源：`models/item/transfer_endpoint_selector.json`、`lang/*.json` | 贴图/名称 | — |
| 测试：`TransferSelectionFixture`（或并入现有 fixture 的新函数） | 服务端断言：选择校验拒绝、draft 读写、提交后 request 走既有 admission、无库存写、无 ledger 写、source=destination/跨维度拒绝 | 改变既有 fixture 语义 |

### 3.2 交互与状态流

```text
手持 alice:transfer_endpoint_selector
  右键箱子        -> validate 通过则 draft.destination = ChestEndpointRef(dim,pos)（覆盖）；失败提示稳定 code
  Shift+右键箱子  -> validate 通过则 draft.source = ChestEndpointRef(dim,pos)（覆盖）；失败提示稳定 code
  点击非容器/未加载/双箱 -> 不记录，返回 endpoint_not_loaded / endpoint_not_single_chest / endpoint_handler_unavailable
  返回 InteractionResult.PASS（原版箱子 GUI 照常打开）

/alice transfer-selection status          只读显示当前玩家 draft（source/destination/dimension/tick）
/alice transfer-selection clear           清除当前玩家 draft
/alice transfer-selection submit <item> <count>
   -> 校验 draft 齐全（缺一端 -> 明确错误）
   -> 重新构造 TransferRequest（构造器再次拒绝跨维度/同端点/非法 count；维度以草稿各自维度为准）
   -> BotManager.assignTransfer（与 /alice transfer-test 完全同一 admission）
   -> 成功后清除该玩家 draft；失败保留 draft 供修正
```

选择到提交之间的世界漂移由既有机制兜底：`TransferRequest` 构造校验 + `ChestBotTransferPrimitive.validate` 每次 write 前重验 + fresh pre 检查（`ChestBotTransferPrimitive.java:117-144`）。选择草稿仅消除坐标输入负担，**不构成任何预留或权威事实**。

### 3.3 item/count 处理（最小方案与取舍）

- **采用**：item/count 继续由命令参数显式提供（`transfer-selection submit <item> <count>`），物品只选端点。
- **取舍**：
  - 优点：与 A1.1「explicit command only, with all endpoint/item/count parameters」保持一致；不引入 NBT 读写与解析；不把数量/物品规则绑定到测试物品（避免物品 NBT 成为隐式转移载体）；非授权者即使持有该物品也不能凭点击发起任何写操作。
  - 拒绝备选：物品 NBT 携带 item/count（右键即提交）。理由：点击即提交会把写入动作与定位动作耦合，扩大 NBT 校验/拒绝路径，且与「选择只是草稿」的最小原则冲突；留待未来若用户明确要求「一键提交」再单列工作包。
- 提交仍是显式命令，因此**点击本身永不产生 request、永不写账本、永不派任务**。

## 4. 权限 gate

1. `useOn` 服务端分支：`player instanceof ServerPlayer sp && sp.hasPermissions(2)`，不满足返回 `FAIL` 并提示 `unauthorized_actor`（复用 `TransferCodes.UNAUTHORIZED_ACTOR`，`src/main/java/com/dddgn/alice/transfer/TransferCodes.java:7`），不记录选择。
2. 物品获得：仅管理员/测试用途，通过原版 `/give @p alice:transfer_endpoint_selector` 发放（`/give` 本身受原版 permission level 2 约束）。不新增无权限发放入口；如需 `alice:test_tools` 物品标签或创造标签页，列为后续可选，不进入本最小包。
3. 提交命令 `/alice transfer-selection` 挂在 `Commands.literal("alice").requires(hasPermission(2))` 之下（与既有 transfer 命令一致，`BotCommand.java:53-54`），服务端提交逻辑再校验 `source.getEntity() instanceof ServerPlayer`。
4. 明确边界：本包不做完整 owner/admin/ACL（A1.4 政策包范围）；permission 2 + 受控单测世界即本包 bootstrap gate。

## 5. 隔离边界（禁止项）

- 不拦截原版箱子 GUI：`useOn` 返回 `PASS`，绝不 `setCanceled`；不得复用/修改 `ScanWand`（C1 语义隔离，`ScanWand.java:28-49`）。
- 不接入普通任务链：不调用 `BotManager.assign*`（除提交时 `assignTransfer`）、不创建 `Task`、不 spawn bot、不触发 `FollowTask`/MineTask/道路/隧道。
- 不改变转移语义/ledger/primitive：`TransferRequest`、`ChestEndpointRef`、`CapacityPreflight`、`ChestBotTransferPrimitive`、`TransferLedgerData`、`TransferTask`、`BotManager.assignTransfer` 一律只读消费，不修改。
- 不修改 C1 scanner/金斧/钻石斧/钻石锄语义：新物品独立注册、独立类、独立贴图引用原版纹理。
- 不写物品 NBT、不新增 packet/UI、不新增 SavedData/持久化（草稿重启即失效）。
- 草稿不入 `TransferLedgerData`（ledger 只记录已 admission 的 request；草稿是测试输入便利层）。

## 6. 风险与停止条件

### 6.1 风险与对策

| 风险 | 对策 |
|---|---|
| 选择状态过期/悬挂 | 草稿带 `selectedTick`，超时（建议与活跃 deadline 同值 2,400 ticks）自动失效；`transfer-selection clear` 手动清除；提交成功后自动清除；非持久，重启即失效 |
| 源=目标 | 第二次选择若与已选另一端点同坐标 → 拒绝并提示 `same_endpoint_rejected`（不覆盖）；提交时 `TransferRequest` 构造器再次拒绝 |
| 跨维度 | 第二次选择维度与第一次不同 → 拒绝 `cross_dimension_rejected`（避免白选）；提交时构造器兜底 |
| 非容器点击 | `validate` 返回 `endpoint_not_loaded`/`endpoint_not_single_chest`/`endpoint_handler_unavailable`，不记录、chat 提示 |
| 与既有命令互斥/一致性 | 提交与坐标命令共用 `BotManager.assignTransfer` 同一 admission；各自独立 request UUID；ledger `blocksBot` 保护仍生效（在途时提交被拒并转 `FAILED_NOT_MOVED`/`bot_unavailable`，现状保持） |
| 点击后 GUI 打开与选择并发 | `useOn` 先记录再返回 `PASS`；服务端同一 tick 顺序内先于 GUI 打开；在客户端矩阵验证「选择已生效且 GUI 正常打开」 |
| 客户端分支 | 遵循现有物品模式：`level.isClientSide` 提前返回，逻辑只在服务端执行 |
| 非授权者 | `useOn` + 提交命令双层 permission 2 gate；物品仅 `/give` 发放 |

### 6.2 停止条件（出现即停止并交还监督员）

- 任何实现需要修改 transfer 语义/ledger/primitive/Task 或 `BotManager.assignTransfer` 才能完成；
- 需要写物品 NBT、拦截原版 GUI、接入普通任务链、新增 packet/UI、完整 ACL 或跨重启持久化草稿；
- 点击行为被设计为直接提交（隐式写入）；
- 需要复用或改动 C1 scanner/金斧/钻石斧/钻石锄语义；
- 与 49be37e 已修复的 abort/替换/挂起保护产生冲突。

## 7. Windows 客户端验收矩阵（本包独立 `CLIENT_TEST_PENDING`）

单轮总表单 + 场景证据包规范（`docs/SUPERVISION_PROTOCOL.md:156`）：`latest.log`、`debug.log`、`evidence-report.md`；场景以 是/否 记录，附截图。预期日志关键字（建议稳定前缀）：`transfer_selection: player= role=source|destination pos=<dim,x,y,z> code=<stable>`、`transfer_selection: cleared`、提交后既有 `[alice] transfer request=<uuid> state=PLANNED code=accepted location=not_moved`。

| 场景 | 观察点 | 预期关键字/结果 | 失败回收条件 |
|---|---|---|---|
| E1 物品获得与授权 | op 可 `/give` 获得；非 op 手持点击被拒、无任何选择记录 | `unauthorized_actor`；无 `transfer_selection` 日志 | 非 op 能选择/提交、非 op 能 give |
| E2 source 选择（Shift+右键） | 点击源箱子后 chat 显示 source 已选；箱子 GUI 同时正常打开 | `transfer_selection ... role=source ... code=accepted` | GUI 被拦截、选择未生效 |
| E3 destination 选择（右键） | 点击目标箱子后 destination 已选；GUI 正常打开 | `role=destination` | 同上 |
| E4 非容器/未加载拒绝 | 点击泥土/空气/未加载位置 | `endpoint_not_single_chest`/`endpoint_not_loaded`，无草稿写入 | 错误端点被记录 |
| E5 源=目标、跨维度拒绝 | 同一箱子选两次；或跨维度选第二端 | `same_endpoint_rejected`/`cross_dimension_rejected`，草稿不变 | 非法草稿被接受 |
| E6 提交短命令 | `/alice transfer-selection submit <item> <count>` 后 bot 按 HARD_PATH 完成端到端；request id 与 A1-A9 一致；提交后草稿清除 | `transfer request=... accepted` → 既有两腿日志 → `transfer_verified` | 点击即写、绕过命令、物品/NBT 成为载体 |
| E7 选择→提交间世界漂移 | 选择后移动/替换箱子再提交 | 提交后失败码按既有 validate 路径；无隐式再选 | 提交静默采用过期草稿 |
| E8 与坐标命令一致性 | 同一环境分别用 `transfer-test` 坐标版与 `submit` 版 | 两路 admission 行为一致（成功码/失败码） | 两条路径结果不一致 |
| E9 状态/清除 | `transfer-selection status` 显示草稿；`clear` 后为空 | `transfer_selection: cleared` | status 无法反映真实草稿 |
| E10 重启失效 | 选择后重启服务器，status 为空，无自动恢复 | 草稿丢失但无任何写操作 | 重启后草稿被自动恢复/继续 |

A1.1 的 A1-A9 场景矩阵保持不变（HANDOVER `docs/HANDOVER.md:62`）；本包矩阵 E1-E10 只验收选择入口与提交衔接，不替代 A1-A9。

## 8. 实施顺序（DAG，供监督员采纳后派发）

```text
P0 监督员审核本报告 -> 呈报用户确认（物品命名/右键映射/提交命令形态/item-count 由命令提供）-> 用户批准 -> 新 active plan
P1 物品注册 + 模型/lang（AliceItems + 资源文件）
P2 TransferEndpointSelector.useOn（权限 gate、点击解析、只读 validate、写草稿、返回 PASS）
P3 TransferSelectionData 草稿（内存 Map、超时、clear、status）
P4 BotCommand：transfer-selection status|clear|submit（复用 assignTransfer）
P5 服务端 fixture（选择拒绝/草稿/提交 admission/无写断言）+ ./gradlew compileJava
P6 HANDOVER 记录 + commit/push + session-complete -> 监督 review -> CLIENT_TEST_PENDING -> Windows E1-E10
```

## 9. 待用户/监督员决策项

1. 物品命名与贴图（建议 `alice:transfer_endpoint_selector`，贴图引用原版 compass/stick 纹理）；
2. 右键映射确认：右键=destination、Shift+右键=source（与申请一致）；
3. 提交命令形态：`/alice transfer-selection submit <item> <count>`（建议）vs 扩展 `/alice transfer-test` 无坐标变体；
4. item/count 由命令提供（建议，理由见 §3.3），拒绝物品 NBT 携带；
5. 草稿超时值（建议 2,400 ticks）与 `clear`/`status` 命令是否进入本包；
6. 草稿非持久（重启失效）是否符合预期。

未解决任一项，本包保持 `NEEDS_USER_DECISION`，不得派发开发。

## 10. 交付状态

- 报告路径：`.alice-supervision/research/entity-selector-test-item-plan-20260822.md`
- 本任务只读：未修改业务代码、active plan、HANDOVER、审核/客户端记录；未派发任务。
- **本规划不构成实现授权。** A1.1 保持 `CLIENT_TEST_PENDING`；本包在用户批准前保持 `NEEDS_USER_DECISION`。
