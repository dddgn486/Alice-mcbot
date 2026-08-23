# Original Chest -> Bot -> Chest A1.1 实现路线规划

- 日期：2026-08-21
- 角色：Alice 实现规划员（只读）
- Git 基线：`3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- 已完成前置计划：`20260821-inventory-transfer-transaction-design-v1 / COMPLETED`
- 本次候选：A1.1 原版单箱与真实 bot 背包的单批运输 primitive
- 固定版本证据：Forge `1.20.1-47.4.10`、Parchment `2023.09.03`、Java 17；Forge source SHA-256 `918a11bdfceace2752d4c29bddbdf327981e1f6a1e1f0675f23e5fbf01e226c0`

> **本规划不构成实现授权。** 未经监督员采纳、用户确认本报告列出的未决项并建立新的 `APPROVED_FOR_IMPLEMENTATION` active plan，任何人不得将本文件转化为代码、命令、绑定工具、ledger、任务、packet 或库存写入。

## 1. 结论与边界

A1.1 应被规划为一个单一、受限的 C2 实验：一个显式 request 将一个精确 `namespace:item` 的正整数 `count` 从已加载的普通原版单箱 source，经真实 bot 主库存，送到已加载的普通原版单箱 destination。它由两个可独立审计的写入 primitive 组成：

```text
source chest -> bot inventory
bot inventory -> destination chest
```

端到端 request 只是顺序编排，不是跨 source/bot/destination 的原子事务，也不提供 rollback。单腿实际调用后的 measured delta 才是事实；destination 失败后的物品继续属于 bot inventory，事务暂停而非重抽或凭空回滚。该结论来自固定 Forge `IItemHandler` 逐槽 API 与 `simulate=true` 不是 reservation 的研究证据（`original-chest-bot-transfer-research-report-20260821.md:63-80`）。

A1.1 仅接受：

- 显式 `namespace:item` registry id；正整数 `count`；单批，count 必须全量成功或在 source mutation 前拒绝。
- 显式 source 与 destination endpoint：`dimension + immutable BlockPos`，在启动与每次 write 前均为已加载、普通原版单箱。
- 真实 bot inventory transport；bot 仅使用 36 个普通 item slots，armor、offhand、cursor/临时 menu slots 均排除。
- 固定 bootstrap authorization：命令发起者等于受控/单人实验中的 bot owner；这只是 A1.1 gate，不是完整 owner/admin/ACL 实现。
- 真实移动仅使用既有 `HARD_PATH`，且作为到 source/destination 旁的可观测运输阶段。不能修改 `PathExecutor`、MineTask、DropCollectionTask 或任何软移动路线。
- source 抽取前的全量容量预检；不足即 `capacity_rejected`，source delta 必须为零。
- 生存硬中断、重启或断线后的 `SUSPENDED` + 持久记录 + 人工接管；不自动恢复，不自动抽取第二次，不切换到 mining/follow/其他工作。

A1.1 明确不支持：缺省 item/count、搬空、tag、aliases、组件/NBT predicate、partial success、自动补料、端点扫描、存储标记区、全量/多批、模组容器、双箱/side、液体、机器写/Adapter、GUI 自动化、LLM、AttackTask、道路、隧道、流体或逃生。

C1 的 `InterfaceSnapshot` 与 raw slot index 不能用于 endpoint role、side 或 C2 写授权（`InterfaceScanner.java`/`InterfaceSnapshot.java` 的 C1 边界，及 `PRODUCT_ARCHITECTURE_ROADMAP.md:155-164`）。A1.1 直接验证“固定普通 chest”的类型和可用 handler，绝不由扫描结果推断。

## 2. 已确认事实与架构推论

### 已确认事实

1. `BotPlayer` 继承 `ServerPlayer`，有原版 inventory；但 `BotWorldData` 只持久化 UUID/name/position/rotation/game mode/main hand，明确“不存背包/经验”（`src/main/java/com/dddgn/alice/bot/BotWorldData.java:8-13, 37-60`；`BotManager.java:107-160`）。当前状态不足以跨重启恢复运输或证明在途物品。
2. 当前 `BotManager.syncMainHand` 仅为 selected main hand 显式发送 inventory packet，说明完整 inventory 写入不可假定自动客户端同步（`BotManager.java:99-105`）。
3. `BotSession.tick` 遇 `LAVA_CONTACT`/`SUFFOCATING` 会把任务标为 `SURVIVAL_INTERRUPTED` 后 `clearTask`；`TaskExecutionRecord` 只记录 task/result/terminal bot position/recovery string，没有 item delta、request id 或 in-transit location（`BotManager.java:470-527`、`TaskExecutionRecord.java:6-35`、`SurvivalSystem.java:46-56`）。
4. 当前 task replacement 会先记录 `CANCELLED_REPLACED` 再清 task（`BotManager.java:395-402`）；已抽取物品若没有独立 ledger，会失去可证明归属。
5. 固定 Forge source 证明：普通 chest 可经 unsided `ForgeCapabilities.ITEM_HANDLER` 暴露 handler；`IItemHandler` 的 insert/extract 是逐槽 API；`simulate=true` 不写入且不锁；`isItemValid` 不证明目前容量；slot limit 与 item max size 都约束容量（研究报告 `:25-33, 39-50, 52-61`）。
6. 项目协议要求客户端可见行为有独立、可观察、可停止入口；编译/headless 不能替代用户 Windows 验收（`docs/SUPERVISION_PROTOCOL.md:11-18, 85-96`）。
7. `PathExecutor.MODE` 是 `HARD_PATH`（`PathExecutor.java:18-25`）；普通矿链/拾取链必须保持原有 HARD_PATH 和不扩隧道规则（`docs/PATHING_REFACTOR.md:16-20, 39-44`）。

### 架构推论（须在开发前 source check 验证）

- A1.1 可以用独立 `TransferTask` 编排 source/destination 的 existing `SurfacePathfinder + PathExecutor(HARD_PATH)`，但它应只消费 path result；任何 `SEARCH_LIMIT`、`UNREACHABLE`、阻塞、hazard 或 endpoint identity drift 都是暂停/失败，绝不触发 tunnel、road、soft movement 或世界修改。
- A1.1 需要一个独立 SavedData ledger；复用/扩展 `BotWorldData` 会把“bot 是否存在”的概要存档和多个 request 的不可变审计状态耦合，且当前 remove/death 会清 bot record。ledger 应独立保留 request 和最后已证明 item location。
- 由于当前 `BotSession` 只容纳一个 `Task` 且普通 replace 会清任务，A1.1 需要在分配前和 replacement path 上对 `in_transit`/`SUSPENDED` 状态设置阻断；如何最小改造现有 session 接口必须在批准计划内明确，而非静默改变所有任务语义。
- `TaskExecutionRecord` 可继续作为人类可见 terminal 摘要，但不是权威 inventory ledger。A1.1 应只将 ledger request id/state/location 摘要写入 record/result/log，不能仅依靠 result 字符串复原物品位置。

## 3. 最小模块与文件边界

以下是候选计划的文件级边界。实际文件名是建议；监督员采纳时可缩小，但不得扩张到未列领域。

| 模块/候选文件 | 最小职责 | 不得承担 |
|---|---|---|
| `transfer/TransferRequest.java` | 不可变 request input：request UUID、actor UUID、bot UUID、source/destination `EndpointRef`、exact `ResourceLocation`、positive count、创建 tick | tags、display name、component/NBT predicate、默认 count、批次列表 |
| `transfer/ChestEndpointRef.java` | `dimension id + immutable BlockPos + expected minecraft:chest identity`；验证 loaded、block type、单箱、BE/handler presence | capability 泛化、side、机器角色、区域扫描或 C1 角色推断 |
| `transfer/InventoryObservation.java` | 逐槽 copy 的 read model、count/digest、observation tick 与 endpoint identity；统一 pre/post delta 比较 | 将 C1 snapshot 当作 write handler 或语义 descriptor |
| `transfer/CapacityPreflight.java` | 对 bot 36 inventory slots 以 exact predicate 计算/模拟完整容纳能力；记录 per-slot evidence/remainder | partial extraction、armor/offhand 使用、容量预留宣称 |
| `transfer/ChestBotTransferPrimitive.java` | 两个独立 leg 的 `pre -> simulate -> actual -> post`；返回 measured delta 与已证明 location | 跨端点 atomicity、rollback、自动 retry、direct endpoint transfer |
| `transfer/TransferLedgerData.java` | 独立 `SavedData`：immutable request、state、leg sequence、last evidence digest/tick、proven location、suspend reason、manual-takeover flag | 托管物品、库存恢复、自动恢复策略、把日志当 ledger |
| `task/TransferTask.java` | 单 request 状态机：验证、HARD_PATH 到 source、source leg、HARD_PATH 到 destination、destination leg、终态/暂停 | MineTask/DropCollectionTask 行为、soft movement、道路/隧道、补料编排 |
| `bot/BotManager.java`/`TaskExecutionRecord.java` | 仅增加受计划限定的 TransferTask assignment、in-transit replacement guard、可读终态摘要 | 重写通用任务生命周期、改变其它任务 replacement 语义 |
| `command/BotCommand.java` **或专用测试入口** | 管理员/owner-only显式提交单 request；稳定输出 request id、参数、阶段、error/location | 正常箱子右键劫持、隐式启动、无参数搬空 |
| `bot/BotSelftest.java` 或新窄 transfer fixture | 只构造隔离 chest/bot server fixtures并断言守恒/失败码 | 触及既有 Mine/soft/path smoke 的行为或把外部 timeout 当 PASS |
| `docs/HANDOVER.md` | 实现后记录实际 commit、验证、限制、客户端待验项 | 在规划阶段修改；不能把 headless 写成 user acceptance |

### 独立入口选择

首选一个 `permission(2)` 且额外满足 bootstrap actor/bot owner gate 的短命令，而不是绑定物品：

```text
/alice transfer-test <source-loaded-pos> <destination-loaded-pos> <namespace:item> <positive-count>
```

理由：用户已选择 A1.1 的端到端 request，但 source/destination 均须显式、固定、已加载；命令可使用 `BlockPosArgument.getLoadedBlockPos` 并自然绑定命令维度，减少 packet/右键事件/GUI cancellation 的额外风险。实现必须仍将最终 endpoint 存为 `dimension + immutable BlockPos`，不能只依赖当前命令世界或动态定位。

替代方案是独立绑定工具（Shift-right-click source、right-click destination）加显式 submit 命令。该方案会增加 item registration、client event、packet、profile persistence、普通 GUI 隔离等风险；不建议与 transfer primitive 放在 A1.1，除非用户将“工具 UX”作为另一个独立批准工作包。普通箱子右键、`ScanWand`、原版钻石铲、`TargetSelector` 均不得被复用或劫持。

## 4. A1.1 状态机与两腿语义

### 4.1 Request 状态

```text
PLANNED
  -> PREFLIGHT_SOURCE
  -> MOVE_TO_SOURCE (HARD_PATH)
  -> SOURCE_LEG_PRE
  -> SOURCE_LEG_SIMULATED
  -> SOURCE_EXTRACTED
  -> IN_TRANSIT_BOT
  -> MOVE_TO_DESTINATION (HARD_PATH)
  -> DESTINATION_LEG_PRE
  -> DESTINATION_LEG_SIMULATED
  -> DESTINATION_INSERTED
  -> VERIFIED

任何未 mutation 的拒绝/失败 -> FAILED_NOT_MOVED
任何 source 实际 delta 已证明 -> IN_TRANSIT_BOT 或 SUSPENDED
无法证明 delta/location -> UNKNOWN_DISCREPANCY + MANUAL_TAKEOVER
硬 hazard/restart/disconnect/task replacement -> SUSPENDED + MANUAL_TAKEOVER
```

`SOURCE_EXTRACTED` 是短暂、持久化前不可越过的 write boundary：source post delta 与 bot post gain 证实后，ledger 必须先落为 `IN_TRANSIT_BOT`，才可进入 destination 移动。A1.1 不可把“同 tick 执行两次调用”称为原子操作。

### 4.2 必须共同遵守的 pre/simulate/actual/post 契约

每一 leg 均按以下步骤，不允许跳过或重排：

1. **validate endpoint**：精确维度/位置、chunk loaded、block 是单一原版 `minecraft:chest`、BE/handler 生命周期可用。source 与 destination 不得相同。任何缺失均未 mutation 失败。
2. **fresh pre observation**：对 source、bot、destination 三端记录 exact item count、逐槽 copy/digest、server tick、request id、endpoint identity。保留未参与 leg 的端点作为守恒基线。
3. **full simulation**：对本 leg 逐槽计算/调用 simulation，记录 input、returned extracted stack/remainder、slot limits、item max、validity。source leg 同时证明 bot 对全 count 的完整容量；destination leg 同时证明 destination 对 bot-held full count 的完整容量。
4. **freshness check**：actual 紧前重新观察相关端点。若 pre/simulate 的 identity、所需 exact stack/count 或 capacity 变化，返回 `simulation_conflict`，不得抽取/插入、不得隐式再模拟后重试。
5. **actual**：仅执行获批准的逐槽 write；实际返回值不是成功本身，必须被记录。
6. **post observation + delta proof**：重新捕获三端；验证 request item 的 source loss、bot gain/loss、destination gain 等于本 leg approved count，且没有未解释 remainder。仅在 delta 相等时推进。

Source leg 的 preflight **必须在 source write 前**证明 bot 可容纳全 count；若失败，返回 `capacity_rejected` 且 source item delta=0。Destination leg 若 fresh pre 后无法全量容纳，应在 bot inventory 仍持有物品时进入 `SUSPENDED`，不执行 source 再抽取，也不把物品丢弃/回滚。

### 4.3 精确 item predicate

A1.1 参数只接受可解析、已注册的 `ResourceLocation`，例如 `minecraft:iron_ingot`，与 `count > 0`。为满足“exact item/count”且避免偷偷扩大 item semantics，建议 first plan 明确采用：

- **选择条件**：registry id 相等，且 source/destination/bot 参与的 stack 必须是默认无 components/NBT 的 vanilla stack；
- **防御性拒绝**：请求 item 或匹配 stack 带任何 components/NBT 时拒绝 `unsupported_item_components`，而不是按仅 registry id 合并；
- **后续**：带 components/NBT 的 exact equality、tag matching、aliases、最大数量、partial success 都是 A1.2+ 明确用户政策。

这一点需要监督员/用户在计划采用前确认：若用户所说的“exact item”意为仅 registry id 且允许忽略 components/NBT，必须单独写入 A1.1 predicate 与 headless fixture，不能暗中选择。

## 5. 稳定结果码、location 与暂停策略

### 5.1 建议稳定结果码

| 代码 | 可出现阶段 | 必须记录/后果 |
|---|---|---|
| `invalid_item_id` | request parse | 未 mutation，拒绝 |
| `invalid_count` | request parse | 未 mutation，拒绝 |
| `unauthorized_actor` | request admission | 未 mutation，拒绝 |
| `bot_unavailable` | admission | 未 mutation，拒绝 |
| `duplicate_request` | ledger admission | 返回已有 request state，不新抽取 |
| `endpoint_not_loaded` | validation/pre | 未 mutation或暂停；不得加载 chunk |
| `endpoint_not_single_chest` | validation/pre | 未 mutation，拒绝 |
| `endpoint_handler_unavailable` | validation/pre | 未 mutation或暂停，禁止泛化 fallback |
| `same_endpoint_rejected` | admission | 未 mutation，拒绝 |
| `unsupported_item_components` | predicate/preflight | 未 mutation，拒绝 |
| `capacity_rejected` | source preflight | source delta=0 |
| `source_insufficient` | source pre/simulate | source delta=0 |
| `simulation_conflict` | fresh pre check | source leg不得 actual；保留 last proven location |
| `source_delta_mismatch` | source post | `UNKNOWN_DISCREPANCY`，人工接管 |
| `destination_delta_mismatch` | destination post | `UNKNOWN_DISCREPANCY`，人工接管 |
| `external_interference` | pre/post discrepancy | suspend or unknown；不得盲重试 |
| `hard_path_unreachable` / `hard_path_search_limit` / `hard_path_failed` | movement | 未 source mutation则失败；已抽取则 suspend |
| `survival_lava_contact` / `survival_suffocating` | any active phase | persisted `SUSPENDED`; 未抽取=`not_moved`，已抽取=`bot_inventory` |
| `timeout` | phase/absolute deadline | 同上，按最后 proven location |
| `actor_disconnect` / `server_restart` / `bot_missing` | lifecycle | `SUSPENDED` 或 `UNKNOWN_DISCREPANCY`，人工接管 |
| `manual_takeover_required` | all ambiguous/resume gate | 停止自动推进 |
| `unknown_discrepancy` | post/recovery | 禁止任何 write retry |
| `transfer_verified` | completion | 三端 delta 与 count 完整一致 |

最终 result/record 至少带 `requestId`、state、lastProvenLocation（`source_chest`/`bot_inventory`/`destination_chest`/`not_moved`/`unknown`）、exact item/count、source/destination refs、failure code、evidence tick/digest。日志不能是唯一存储。

### 5.2 持久化与人工接管

**建议 ledger schema**：独立 `TransferLedgerData extends SavedData`，以 request UUID 作为 key。每条记录不可变保存 request input；可变但追加式保存 state transitions（sequence、tick、reason、pre/simulate/actual/post digest、last proven location、manual takeover flag）。不得只存最后字符串，以免 post-mutation 崩溃时重放抽取。

**重启**：server start 时不自动继续。ledger entry 进入/保留 `SUSPENDED`，读取 bot full inventory 的 fresh observation 与两个 chest 的 fresh observations，生成 reconcile report；在用户/管理员显式人工接管前不 mutation。由于现有 bot restore 不保存 full inventory，开发前必须确认 A1.1 是否需要批准“完整 bot inventory 持久化”的最小范围。若该范围不能安全纳入，保守替代是 A1.1 禁止重启跨越 active transaction，并将 server stopping 视为 `manual_takeover_required`；但这与用户要求“重启 suspend + 人工接管”不完全满足，应停止并要求监督员/用户选择。

**生存中断**：在任何实际 source delta 后，ledger 保留 `in_transit=bot_inventory` 与最新 post evidence，任务清理但 transaction 不完成。不得自动跑回 source/destination，不能把 suspension time 静默排除于无限期 deadline；应有 maximum suspension duration，过期仍为 `manual_takeover_required`。

**死亡/移除**：现有 `BotManager.onLivingDeath` 会 remove bot 且清 BotWorldData（`BotManager.java:321-337`）。A1.1 不能声称在途 item仍在 bot inventory；必须转 `unknown_discrepancy`，禁止重试，要求人工核对世界/库存。若要实现死亡物品托管/回收，是新工作包。

**任务替换**：当 request state 为 `IN_TRANSIT_BOT` 或 `SUSPENDED`，普通任务分配必须被拒绝或显式进入 manual takeover，不得执行 `replaceTaskIfRunning` 后丢失运输状态。此 guard 必须只针对 TransferTask，不得无关地更改 MineTask/FollowTask 行为。

## 6. 实现顺序与依赖 DAG

以下为未来获批 A1.1 的最小顺序；本报告不派发这些工作。

```text
P0 监督员采纳本规划 + 用户确认未决政策
 |
P1 重新确认 baseline/Forge source/API 与 full inventory persistence 范围
 |
 +--> I1 Value objects: request / endpoint / item predicate / stable codes
 |
 +--> I2 Read-only observation + endpoint validation + ledger SavedData schema
 |          |
 |          v
 +------> I3 CapacityPreflight + per-leg simulate/freshness/delta comparator
             |
             v
           I4 Single-leg primitives (source->bot, bot->destination) + no-atomicity logs
             |
             +--> I5 TransferTask HARD_PATH orchestration + suspend/replacement guards
             |
             +--> I6 Admin-only explicit command + status/manual-takeover readout
                         |
                         v
           I7 Focused headless fixture suite + compile + source/diff audit
                         |
                         v
           I8 Supervisor review -> CLIENT_TEST_PENDING -> Windows user matrix
```

- I1-I4 是数据与单腿事实层；I5 只能消费已验证 primitive，不得在 Task 内自行操作 chest slots。
- I6 必须在 primitive 和 request validation 后实现；入口不得在无 `requestId/item/count/source/destination` 情况下隐式启动。
- I7 在 client test 前完成，但 headless 结果只证明服务端预期，不证明 client sync、GUI isolation 或真实移动视觉效果。
- I8 的 Windows matrix 可只接受 A1.1 独立 primitive/端到端实验，不自动授权 A1.2 fulfilment、storage labels、multi-batch、mod containers 或 normal task integration。

## 7. Headless 验证矩阵

实现计划须把以下作为最小可重复 fixture，使用新隔离 chest/bot fixture，不能借用/改变既有 mining 或 C1 selftest 语义。所有 assertion 输出 request id、state、stable code、three-party delta 和 location；任何未解释 delta 为 FAIL。

| 场景 | 服务端证据/通过条件 |
|---|---|
| 完整 source->bot leg | source loss=count，bot gain=count，destination=0；state `IN_TRANSIT_BOT` |
| 完整 bot->destination leg | bot loss=count，destination gain=count，state `VERIFIED` |
| 端到端正常 | source loss=count，bot end delta=0，destination gain=count；每腿分别记录，不宣称 atomic |
| bot 容量不足 | `capacity_rejected`，source delta=0，bot/destination delta=0 |
| source 不足 | `source_insufficient`，三端无 mutation |
| destination 容量不足（已在途） | source leg 后 bot持有；destination leg拒绝/suspend，source 不重抽，物品 location=`bot_inventory` |
| loaded/type/identity failure | unloaded、非 chest、双箱、同一 endpoint、handler unavailable 都拒绝且无 write；不得加载 chunk |
| exact predicate防御 | invalid id/count、带 components/NBT 的 stack 或未批准 predicate 都 `unsupported_item_components`/拒绝；不得按 display name/tag 匹配 |
| slot capacity边界 | existing matching stacks、empty slots、slot limit、item max stack 的 sum/remainder 正确；`isItemValid` 不被误当容量证据 |
| simulation conflict | simulate 后外部 source/bot/destination mutation；freshness check 命中，actual 前停止，无第二次 blind extraction |
| source/destination post mismatch | 注入/构造 mismatch 后为 `unknown_discrepancy`，无自动 retry/rollback |
| duplicate request | 同 request id 返回原 state，不发生第二次 source loss |
| HARD_PATH失败与搜索预算 | 未抽取时 no-move fail；已抽取后 suspend；`SEARCH_LIMIT` 绝不进 tunnel/road |
| lava/suffocation interrupt | 未抽取为 `not_moved`；已抽取为 `SUSPENDED/bot_inventory`，无自动恢复 |
| task replacement | in-transit request 时拒绝普通替换；ledger state/evidence 保留 |
| restart/recovery | 若 full-inventory persistence 已批准：reload 后 ledger+inventory observation 得出 suspend/manual report；若未批准，则验证关服前拒绝 active transfer 并产生 manual-takeover report，不能假装恢复成功 |
| death/removal | transition `unknown_discrepancy/manual_takeover_required`，禁止重抽 |

另需 `./gradlew compileJava`。如现有 broad `runServer` 被外部 timeout/143 中断，不能称全套通过；只记录有明确 request-level PASS 的 focused fixture 证据。

## 8. Windows 客户端验收矩阵

A1.1 具有真实库存写入和 bot 移动，必须建立新的 `CLIENT_TEST_PENDING`。建议使用一次性/可备份的平坦测试世界，普通玩家箱子和 Alice 入口隔离；保留完整 Windows `latest.log`、`debug.log`、request id/阶段日志、三端数目记录。当前规划员运行时无图像能力，截图/视频由用户观察和文件记录支撑，规划员不得解释为 `USER_ACCEPTED`。

| 场景 | 用户可观察点 | 停止条件 |
|---|---|---|
| 入口与授权 | 非 op/非 owner 被拒绝；参数不完整/非法 item/count 被拒绝；普通箱子右键仍打开原版 GUI | 入口劫持普通 GUI、无参数启动、权限绕过 |
| 正常单批端到端 | bot 仅按 HARD_PATH 移至 source/destination；source 减 count、destination 加 count、bot 最终请求物品 delta=0；日志/状态显示 request id、两腿与 verified | 世界修改、隐式路径能力、未解释数差 |
| source->bot中途状态 | 仅在批准的诊断观察点查看 bot持有和 ledger location；不把 selected main hand 当完整 inventory 同步证明 | client/server inventory 不一致或无法定位在途物 |
| capacity不足 | source 不减少任何请求物；稳定 failure code；bot不启动部分携带 | source 部分抽取、silent partial success |
| destination满/被外部变更 | 已抽取时物品留在 bot，request suspend/manual takeover；不重新从 source 抽取 | 自动 rollback/re-extract、物品丢失/复制 |
| hazard interrupt | 制造已批准的可控危险前先确认已在途；任务停止且 ledger报告 bot location；不自动逃生/续跑 | bot继续写、自动启动其他任务、location不明 |
| restart/断线（仅在持久策略已实现时） | 重启后无自动 write；出现 suspended/manual-takeover 和可核对三端事实 | 自动重试或声明物品已恢复而无证据 |
| 原版 GUI隔离 | 玩家对任一普通箱子正常 open/move；Alice transfer command/diagnostic 无 menu click/重复事件 | GUI取消、Alice 接管普通右键、重复写 |

客户端矩阵通过仅表示此固定 A1.1 original chest + exact item/count + single-batch scenario；不升级为 generic C2、C3/C4、机器/side、tags/components、多批、fulfilment 或其他任务链验收。

## 9. 停止条件与开发前 source checks

### 必须在开工前复核

1. 当前 Git/active plan、Forge/Parchment/JDK 版本和研究报告的 source SHA 未变；若变更，重新评估 mutation/access 结论。
2. 选定 normal chest handler 的获得、chunk loaded/type/single chest identity、cap invalidation/handler lifecycle和 `Inventory` full-slot persistence API 能在固定 Forge/Alice source中证明，并有 planned fixture。
3. user/监督员明确确认 exact predicate（默认无 components/NBT 的 registry id-only guard）、36 slot policy、bootstrap actor/bot owner gate、absolute timeout/phase timeout、maximum suspension time、destination full policy、重启策略和 manual takeover interface。
4. 现有 `BotManager` session 的 in-transit guard 能只作用于 A1.1，而不改变 MineTask、DropCollectionTask、FollowTask、soft probes 或 road behavior。

### 实施中任一出现即停止并交还监督员

- 需要跨 endpoint atomic/rollback、无证据地重复抽取、把 `simulate=true` 当 reservation，或无法把 post delta 分类为 source/bot/destination/unknown。
- 需要 handler side、generic capability、双箱、mod container、machine semantics、GUI menu click、storage labels、permissions/ACL、packet/UI、LLM、tags/components/partial/multi-batch 才能完成首包。
- endpoint 未加载且唯一办法是强制加载 chunk；handler invalid/identity不能证明；source/destination 发生干扰且无法用 fresh evidence分类。
- source preflight capacity 不可在抽取前完整证明；destination failure无法把 remainder证实在 bot inventory。
- full inventory 跨重启持久化不能在明确批准范围内实现，而用户要求跨重启 suspend+manual takeover；应请监督员将 A1.1 缩小为“禁止 active transfer 跨 restart”或拆出 persistence work package。
- 需要改 HARD_PATH、接入 SOFT_SURFACE、FollowTask、道路、隧道、流体、逃生、AttackTask、MineTask/DropCollectionTask，或把 `SEARCH_LIMIT` 当通道授权。
- headless fixture、日志和客户端观察彼此冲突，或同一根因两次实现尝试未改善；按协议转深调研/用户决策，不堆叠特判。

## 10. 仍须用户确认的路线选择

尽管用户已选择真实背包、普通箱子、端到端、精确 item/count、容量不足前置失败和 suspend/manual takeover，A1.1 计划发布前仍须明确以下条目：

1. **first item predicate**：是否同意本报告的保守 guard，即只接受无 components/NBT 的 `namespace:item`；还是要支持带 components/NBT 的 exact equality（后者扩大 fixture/compare 设计，建议后置）。
2. **端点/移动距离**：source/destination 是否必须同维度；建议 A1.1 固定同维度，拒绝跨维度。若允许跨维度，需要单列维度/portal/恢复风险工作包。
3. **bootstrap authority**：是否采纳“permission level 2 + initiator == bot owner + controlled/single-player experiment”；多玩家 owner/admin/ACL 仍后置。
4. **重启策略**：是否批准在 A1.1 中加入 full bot inventory persistence 的窄范围以支持“restart -> suspend -> manual takeover”；若不批准，是否接受首次实现明确禁止 active request 跨 server restart。
5. **manual takeover UX**：首版只提供 read-only status/report 加管理员终止，还是允许管理员显式 resume/finish-insert；建议先只读 report + abort，resume/finish 是另一写入命令，应后置。
6. **destination capacity policy**：A1.1 是否在 source write 前也模拟 destination capacity。建议必须模拟并要求全量可容纳，以最小化在途；但仍保留 destination actual 前的 fresh check 和在途 suspend，因为 simulation 非 reservation。
7. **timeout/suspension values**：phase no-progress ticks、absolute deadline、maximum suspension duration必须由用户或计划给固定值，不能由实现者临时挑选。

未解决的任何项都使计划保持 `NEEDS_USER_DECISION`，不得释放开发任务。

## 11. 对后续版本的明确分界

- **A1.2 fulfilment**：仅在 A1.1 被用户客户端验收后，才根据 bot inventory deficit 从显式 source 做补足，再向 destination 投递；仍不自动扫描/选择 endpoint。
- **A1.3 storage labels**：WorldProfile/storage marking、source/destination/supply/reserved classification，需独立持久化/用户编辑包；禁止从 C1 raw slots推断。
- **A1.4 policies**：owner/admin/ACL、tag/component predicate、maximum quantity、partial/multi-batch、retry/fallback。
- **later compatibility**：mod containers、side semantics、Mek/AE、fluids/temporary tanks、adapters，各自固定版本研究和客户端矩阵。

## 12. 交付状态

- 报告路径：`.alice-supervision/research/original-chest-bot-transfer-a1-implementation-plan-20260821.md`
- 依据：用户政策 `.alice-supervision/research/inventory-transfer-a1-user-policy-20260821.md`、深调研 `.alice-supervision/research/original-chest-bot-transfer-research-report-20260821.md`、A0 review `.alice-supervision/reviews/20260821-inventory-transfer-transaction-a0-review.md`、当前 Alice 代码和固定设计/协议文档。
- 本任务未修改业务代码、active plan、HANDOVER、审核/客户端记录，未派发任务。
- **本规划未获监督采用和用户批准前，不得变为 active plan 或开发授权。**
