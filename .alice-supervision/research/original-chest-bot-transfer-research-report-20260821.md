# original chest <-> bot inventory 深度研究报告

- 主题：`original-chest-bot-transfer`
- 日期：2026-08-21
- Alice Git 基线/当前 HEAD：`3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- Forge/Minecraft：Forge `1.20.1-47.4.10`，Parchment `2023.09.03`，Java 17
- 固定 Forge sources SHA-256：`918a11bdfceace2752d4c29bddbdf327981e1f6a1e1f0675f23e5fbf01e226c0`
- 本地 Forge 源码：`~/.gradle/caches/forge_gradle/maven_downloader/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-sources.jar`
- 范围：只读源码证据与设计建议；未调用 simulate/insert/extract、菜单 click 或任何库存写入。

## 证据等级

- **A**：Alice 基线源码或本地固定 Forge 1.20.1-47.4.10 source/patch，可直接复核。
- **B**：固定版本映射/二进制 API 结构或由 A 证据作出的窄架构推论；实现前仍需 fixture。
- **C**：建议、未确认事项或必须由客户端实测确认的行为。

## 一、源码证据表

| 事实 | 证据（固定路径/类/方法/行） | 等级 | 结论边界 |
|---|---|---:|---|
| Alice bot 是玩家模型 | `src/main/java/com/dddgn/alice/bot/BotPlayer.java:22-26`，`BotPlayer extends ServerPlayer` | A | 有原版玩家库存字段与玩家生命周期；不授予 C2 写权限 |
| bot 目前仅保存概要和主手 | `src/main/java/com/dddgn/alice/bot/BotWorldData.java:8-13,36-60`；`BotManager.java:107-128,130-160` | A | 不保存完整背包、事务、在途位置；重启不能恢复完整库存事务 |
| 直接改 bot 槽位不是事务接口 | `BotManager.java:99-105,154-158`；`MineTask.java:40-42` | A | `Inventory.setItem` 改服务器数据；Alice 已明确需显式 packet 同步主手 |
| 当前任务终端记录不含库存账本 | `src/main/java/com/dddgn/alice/bot/TaskExecutionRecord.java:6-35`；`BotManager.java:496-517` | A | 只有任务状态、位置、恢复字符串，无法证明 item location/delta |
| C1 handler 仅只读无 side | `src/main/java/com/dddgn/alice/capability/InterfaceScanner.java:18-30,44-72,125-158`；`InterfaceSnapshot.java:7-45` | A | C1 slot index 是原始观察，不是 source/destination 角色或写授权 |
| 原版箱子有 Forge item capability | 固定 Forge source `patches/net/minecraft/world/level/block/entity/ChestBlockEntity.java.patch`，约 `-160` 后新增 `getCapability`/`createHandler` | A | capability 为 `IItemHandlerModifiable`，经 `InvWrapper` 包装当前 chest `Container`；必须在运行时确认目标箱子、加载状态和 handler 生命周期 |
| 容器基类也有无 side handler | 固定 Forge source `patches/net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java.patch`，约末段 `createUnSidedHandler/getCapability/invalidateCaps/reviveCaps` | A | 不能把“有无 side handler”推广为机器输入输出语义；首端点只允许原版 chest |
| handler 返回栈不可直接修改 | 固定 Forge source `net/minecraftforge/items/IItemHandler.java:25-45` | A | 每次观察必须 copy；不要以返回对象变更作为写入 |
| insert/extract 有 simulate，但不是锁 | 固定 Forge source `IItemHandler.java:47-78` | A | `simulate=true` 只是不写入的试算；源码未提供 reservation/lease/锁，actual 前状态可变 |
| slot limit 与 item max 都约束插入 | 固定 Forge source `IItemHandler.java:80-86`；`patches/net/minecraft/world/inventory/AbstractContainerMenu.java.patch` 约 `600` 行 merge 逻辑 | A | 容量计算必须按每个目标 slot 的 slot limit 与 `ItemStack#getMaxStackSize` 的较小值 |
| `isItemValid` 不是容量证明 | 固定 Forge source `IItemHandler.java:88-107` | A | true 不考虑当前内容、满度或其他状态，只是“某些情况下可能有效” |
| Forge 菜单打开/关闭有事件 | 固定 Forge source `patches/net/minecraft/server/level/ServerPlayer.java.patch`，约 `959-1002` 的 `openMenu`/close 路径 | A | 菜单路径会产生 open/close 事件并发 open screen；不应作为首版服务端语义 API 的唯一写路径 |
| 菜单 slot 合并受 slot limit/max stack 约束 | 固定 Forge source `patches/net/minecraft/world/inventory/AbstractContainerMenu.java.patch`，约 `600` 行 | A | 菜单 click 不是无需验证的原子批量；客户端 GUI/菜单同步另需验收 |
| Forge mapping 配置固定 | Alice `build.gradle:20-34,142-149`；`gradle.properties` 中版本值 | A | 本报告的版本边界为该 checkout 的 Forge/Parchment 配置，不代表其他版本 |
| 当前生存系统是硬中断 | `src/main/java/com/dddgn/alice/survival/SurvivalSystem.java:12-16,46-61`；`BotManager.java:470-489` | A | LAVA/SUFFOCATING 中断任务；无自动恢复、持有物品托管或人工接管机制 |

## 二、问题 1：访问/写入路径与首个实验

### 已确认路径

1. **原版 chest 的 Forge `IItemHandler` 路径（首选候选）**：通过目标已加载的 `ChestBlockEntity#getCapability(ForgeCapabilities.ITEM_HANDLER, null)` 获得 handler；固定 Forge patch 显示普通 chest 创建 `InvWrapper`，底层仍是 chest `Container`。逐槽读取 `getStackInSlot`，未来实际插入/抽取使用逐槽 handler API。该路径是服务端权威数据路径，不需要打开玩家 GUI。
2. **原版 chest 的 vanilla Container/BlockEntity 数据路径**：`ChestBlockEntity` 本身及其 `Container` 方法可读取/设置槽位。这是数据变更路径，但单纯设置不等于原版玩家菜单交互，也不会自动获得完整 client menu 同步/事件语义。不能把它当作 C2 已授权 API。
3. **原版 chest menu 路径**：通过 `MenuProvider`/`ServerPlayer.openMenu` 创建 `ChestMenu`，会进入原版菜单生命周期；固定 Forge `ServerPlayer` patch 证明 open/close 事件和 screen packet 发生。菜单 click 适合验证“玩家可见 GUI 行为”，不适合首个服务端事务 primitive：它依赖 connection、菜单 slot 映射、click/action 状态和客户端同步。
4. **bot `Inventory` 路径**：`BotPlayer` 的 `getInventory()` 是 `ServerPlayer` 原版玩家库存。服务端直接读取/写入对应 slots 是数据路径；现有 Alice 只在工具替换和恢复中对 selected slot `setItem`，并显式用 `ClientboundContainerSetSlotPacket` 同步主手。完整库存写入不能假设自动同步。

### 首个实验建议（设计建议，不是授权）

选**服务端同 tick、无 GUI 的原版 chest handler + bot 原版 `Inventory` 服务端路径**，并把 GUI 仅作为观察/客户端验收面。原因：写入对象和 delta 易审计，不依赖 bot 假连接去执行 GUI click，不把 C1 slot index 当角色；但仍必须为每次 actual write 记录 pre/simulate/actual/post。首个实验只允许一个固定已加载普通 chest、一个显式 bot、一个 exact registry item/count、一个方向，且初版应验证 source -> bot 与 bot -> destination 两条独立 primitive，再考虑端到端串联。

**未确认事项**：固定 Forge patch 证明 capability 构造，不证明特定世界中 chunk/双箱合并/锁箱/其他 mod 替换后的行为；必须由 headless fixture 在新 A1 计划中确认。菜单事件、packet 和服务端 delta 也不能由 headless PASS 代替 Windows 客户端验收。

## 三、问题 2：抽取前容量证明

首版 exact predicate 应使用注册表 item id（`namespace:item`），并明确是否要求 tag/component/NBT 相等；不能用显示名。对每个 bot 可用物品 slot：

- 默认只把玩家主库存 36 个 item slots 纳入“可运输容量”；armor、offhand、游标/菜单临时槽、selected 只是这些 slots 的子集或特殊状态，不得默认拿来塞运输物品。若政策允许 armor/offhand，必须单独用户决策和测试。
- 对已有 stack，只有 item identity 与完整 component/tag 等价时才可合并；1.20.1 的 `ItemStack` 比较必须固定采用代码规定的精确比较，不能只比较 registry id（除非用户明确选择“忽略 tag/component”）。
- 每个 slot 的可用容量是 `min(handler/slot limit, stack item max) - current count`；空 slot 可放 `min(slot limit, item max)`。还需先确认 item 的实际 max stack rule 和 slot-specific validity。
- `isItemValid` 只可作为永久拒绝提示，不能算“现在有空间”；固定 `IItemHandler.java:88-107` 明确说它不考虑当前 contents/fullness。
- 对 exact count 做逐 slot simulate，汇总所有 remainder 必须为 0；同时记录每个 slot 的 pre stack、slot limit、max stack、validity、simulated remainder。若不能容纳完整 count，**抽取前失败且 source delta 必须为 0**，不得先抽后部分塞入。
- simulate 结果不是 reservation。actual 前必须 fresh pre，再重新验证 simulation；pre 与 simulate 之间有任何 delta 或 tick/world revision 变化，进入 `simulation_conflict`，不得盲写或重试抽取。

## 四、问题 3：竞态、证据与原子性

### 最小证据序列

每个原子 leg（source -> bot，或 bot -> destination）都应保存：

1. `pre`: request id、server tick、dimension/immutable position、block/entity identity、逐 slot item identity/count/component digest；source、bot、destination 三端都记录，未涉及端也可作为守恒基线。
2. `simulate`: 每个实际会访问的 slot、输入 exact stack/count、返回 remainder/extracted stack、slot limit、handler identity/observation tick；明确 simulation 不是锁。
3. `actual`: 只在 fresh pre 与 simulate 一致时执行；记录实际返回 stack/remainder，不接受“调用成功”作为事实。
4. `post`: 在实际调用后重新捕获 source/bot/destination snapshots，按 exact identity/component 比较 delta；source loss、bot gain、destination gain 必须与 ledger 一致。
5. `location`: actual source delta 证实后，先把物品位置登记为 `in_transit=bot_inventory`；若目标插入后仍有 remainder，未插入 remainder 仍属于 bot inventory，不能丢弃或新建请求。

建议稳定错误：`capacity_rejected`、`source_unavailable`、`simulation_conflict`、`source_delta_mismatch`、`destination_delta_mismatch`、`external_interference`、`timeout`、`actor_disconnect`、`duplicate_request`、`unknown_discrepancy`、`manual_takeover_required`。

### 是否有真正跨端点原子操作

**固定源码不能证明存在。** IItemHandler 的 insert/extract 是逐 slot API，simulate 不锁，source chest、bot Inventory、destination chest 不是同一容器或同一事务上下文。即使两次 actual 在同一 server tick 执行，也不构成跨端点 rollback/2PC。最保守模型是：每个 leg 有可审计的 measured delta；跨 leg 失败进入 `in_transit` 或 `unknown_discrepancy`，不得宣称 all-or-nothing。若 destination 插入失败，已抽取物必须留在 bot inventory 并暂停；若 post 无法判定，禁止重试抽取。

## 五、问题 4：端到端拆批

端到端首版应拆为有界批次：`planned -> reserved -> source_extract -> in_transit(bot_inventory) -> destination_insert -> verified`。每一批只绑定一个 request id/sequence、exact item predicate、count 和固定 source/destination identity。批次完成并 post-verified 后才开始下一批。

未填 item/count 的“全量”不能作为单笔原子事务：全量大小随 source 状态、bot 既有库存、max stack、外部 mutation 改变；单笔执行会扩大在途时间、重启恢复账本、目标容量和竞态窗口，并且跨端点不存在 rollback。首版应拒绝未界定全量，或先 fresh snapshot 冻结候选并按保守上限拆成有限批次；每批容量不足在抽取前失败。建议 A1.1 不做“全量”选项，只做显式 exact item/count；A1.2 再做有界编排。

## 六、问题 5：中断、恢复与在途位置

| 情况 | 源码可证明事实 | A1.1 保守处理 |
|---|---|---|
| 生存硬中断 | `SurvivalSystem` 只对 lava/suffocation 发中断，`BotManager.BotSession.tick` 终止任务 | 若已抽取，持久化 `in_transit=bot_inventory` 和 post evidence，暂停；未抽取则 `not_moved`。不自动恢复/不新抽取 |
| bot/player disconnect | 当前 Alice 无事务 ledger/disconnect recovery；FakeConnection 是玩家化连接，不等于持久事务 | 停止，按最后已证实 snapshot 分类；不能证明则 `unknown_discrepancy`，人工接管 |
| server restart | `BotWorldData` 只存 UUID/name/Pos/Rotation/GameMode/MainHand，未存完整 inventory/transaction | A1.1 必须新建持久 transaction ledger，或首版禁止跨重启继续；推荐持久 ledger + 重启扫描 bot inventory 后人工确认 |
| 任务替换 | `replaceTaskIfRunning` 记录取消并清 task/scope，当前记录无 item facts | 已进入 in_transit 时禁止普通新任务替换；只允许事务专用 suspend/manual takeover |
| external chest mutation | simulate 不锁；C1 snapshot 只是 observation | fresh pre mismatch 或 post mismatch => `external_interference/simulation_conflict`，不盲重试 |
| destination full | 可由逐槽 simulation remainder 证明；但 actual 前仍可变 | 抽取前 `capacity_rejected`；抽取后 remainder 留在 bot inventory，暂停，不算成功 |
| duplicate request | 当前任务记录没有 request id/idempotency | A1.1 必须 request id + immutable facts；已完成/在途 request 重放返回原状态，不创建第二次 extraction |
| 死亡 | 当前 `onLivingDeath` 取消事件并 `remove(bot)`，清 BotWorldData | 不能把死亡当作物品已安全恢复；若已有 in_transit 必须先人工核对 bot inventory/掉落/世界状态，默认 `unknown_discrepancy` |

结论：现有 `BotWorldData` 和 `TaskExecutionRecord` 不足以证明 in-transit location。A1.1 最小保守策略是固定普通 chest、exact item/count、HARD_PATH、单批、持久 request ledger、抽取前容量检查、每次 actual 前 fresh pre、source/bot/destination post delta、跨重启/断线挂起并人工接管；不实现 LLM 自动恢复、自动换任务或“默认挖矿”。

## 七、问题 6：绑定工具事件隔离设计建议

仅给设计，不实现：建立独立测试工具和独立 packet/服务端命令语义，避免把普通箱子右键变成 Alice 写入口。

- **Shift 右键绑定 source**：客户端只识别独立绑定工具的使用事件并发送一次目标 `dimension + immutable BlockPos + held tool identity + modifier`；服务端验证持有者、维度、已加载 chunk、目标确为普通原版 chest、工具身份和 bootstrap authorization，然后只写 Alice 自己的 endpoint profile。不得调用 chest menu click，不取消普通箱子原版交互。
- **右键绑定 destination**：同上，但 profile role 是显式 destination；source/destination 必须互斥且需要用户确认。服务端不得从 C1 slot 或 side 自动推断角色。
- **未绑定回落 bot inventory**：独立工具在无方块目标或明确未绑定状态时只选择 bot inventory 作为操作对象；不把普通 diamond shovel/普通箱子右键劫持到 Alice 逻辑。实际 transfer command 必须显式 request id/item/count，不能由回落点击隐式启动。
- 每项绑定测试应有服务端拒绝日志、目标身份、权限主体、事件是否取消、原版 GUI 是否正常开启的证据。事件监听优先限定工具/hand/modifier；不要全局取消 `PlayerInteractEvent.RightClickBlock`。

## 八、问题 7：A1.1/A1.2 最小边界与停止条件

### A1.1（建议，需新用户批准计划）

文件边界建议：

- `transfer/TransactionLedger`：request id、immutable facts、state、sequence、item location、delta digest、suspend/manual takeover；持久化格式须在新计划中批准。
- `transfer/InventoryObservation`：固定 original chest handler + bot inventory 的只读逐 slot snapshot；不得复用 C1 `InterfaceSnapshot` 充当写角色。
- `transfer/CapacityPreflight`：exact item/component/count、可用 item slots、slot limit/max stack、simulation remainder 汇总。
- `transfer/ChestBotTransferPrimitive`：两条独立单腿 primitive；每次 pre/simulate/actual/post；不做端点直转、不做模组容器/流体/side traversal。
- `transfer/TransferTask`：仅调度单批生命周期，HARD_PATH；中断进入 suspend/manual takeover。
- `BotWorldData`/`TaskExecutionRecord` 的扩展只有在新 A1 计划明确批准后才可做，不能沿用当前概要记录假定恢复可行。

A1.1 不应包含：全量自动编排、存储标记区、权限系统、mod container/Mek/AE、液体、直转 buffer、LLM 恢复、AttackTask、路径/软移动改动。

### A1.2（后续）

在 A1.1 通过并有用户决策后，才考虑“bot 现有库存不足则从指定 source chest 补足，再送 destination”的两腿编排；仍只接受明确 endpoint identity，不做自动扫描或泛化 machine semantics。全量和 partial-success 需另行决定。

### 停止条件

任何一项发生即停止并回监督：原版 chest handler/菜单 identity 无法在固定版本 fixture 证明；pre/simulate/actual/post 不能解释 delta；无法持久化单一 request 状态；重启/断线后 item location 不可分类；出现 source/destination side 语义需求；需要 GUI click、模组容器、自动恢复或权限扩张；任何 `unknown_discrepancy` 被要求自动重试。

## 九、验证矩阵

### Headless fixture（A1 代码包必须定义，当前未运行）

1. 空/部分占用 bot inventory：exact count 全容纳与容量不足，断言不足时 source delta=0。
2. 相同 registry id 但 tag/component 不同：按批准 predicate 拒绝合并或准确分开。
3. slot limit/max stack 边界：逐 slot remainder 与预期一致。
4. source simulate 后外部 mutation：actual 前检测 `simulation_conflict`，不抽取。
5. actual source delta mismatch / destination remainder：进入在途或 discrepancy，禁止盲重试。
6. duplicate request、任务替换、timeout、LAVA/SUFFOCATING interrupt。
7. server stop/start：ledger、bot 完整 inventory、request id、location 可恢复或明确人工接管。
8. source/bot/destination 守恒：每一批 source loss = bot/destination gain + 明确 remainder；任何 unknown 失败。

这些 fixtures 是建议，不是本报告已执行的 PASS。

### Windows 客户端矩阵（必须用户验收）

| 场景 | 观察 |
|---|---|
| 独立 Shift 右键 source 绑定 | 绑定成功/拒绝、普通 chest GUI 是否仍可打开 |
| 独立右键 destination 绑定 | 角色显示、错误目标拒绝、GUI 未被劫持 |
| 未绑定回落 bot inventory | 只显示/选择 bot inventory，不误触发普通箱子交互 |
| exact chest -> bot -> chest | 客户端可见 bot 持有状态、最终 source/bot/destination 数量 |
| 容量不足 | 无源箱部分抽取；明确失败状态 |
| external mutation/duplicate | 无重复抽取，request id/错误状态可见 |
| 中断、bot disconnect、server restart | 最后已证实 item location、suspend/manual takeover；不能以日志 PASS 替代 |
| 原版玩家箱子 GUI | 正常打开、搬动物品与 Alice 工具事件隔离 |

本会话模型无识图能力，因此不能替用户确认 GUI/可见同步/视觉事件；上述矩阵必须由用户 Windows 客户端实测并记录。

## 十、方案对比、可迁移原则与不可迁移风险

| 方案 | 优点 | 风险/结论 |
|---|---|---|
| 真实 bot inventory transport | 物品位置可见，符合用户选择，bot inventory 可作为 in_transit | 运动、中断、死亡、断线、重启和 client sync 复杂；选定但必须有 ledger |
| endpoint-to-endpoint direct transfer | 无运动，事务短 | 需新建权威 buffer/ledger，隐含自动化写路径，用户已明确不选 A1.1 |

可迁移原则：服务端权威；固定 endpoint identity；exact immutable predicate；pre/simulate/actual/post；simulation 不是 reservation；`isItemValid` 不是容量证明；每次 actual 后 measured delta；request id 幂等；未知差异阻断重试；原子性只在单 leg 证据范围内声称。

不可迁移风险：C1 raw slots 不能迁移为角色/side；普通 chest handler 不能代表 mod container；GUI 菜单事件不能代表无 GUI server write；`ServerPlayer` 继承不能代表完整背包持久化或权限；headless 能证明 server delta 不能证明 Windows 客户端显示/事件隔离；`BotWorldData` 主手恢复不能代表事务恢复；同 tick 不能制造跨端点 rollback。

## 十一、必须用户/监督员决策

在 A1 计划前必须确认：首个实验是否端到端 `chest -> bot -> chest`（本任务输入已选择该方向，但仍需写入新批准计划）；exact item/count 与 component/tag 规则；可用 bot slots 是否仅 36 item slots；bootstrap authorization；中断后是否 suspend/manual takeover；重试/绝对 deadline；是否允许跨重启恢复；destination remainder 的处理；全量是否首版拒绝；绑定工具是否允许客户端回落行为。监督员须先审核本报告，再发布新的 `APPROVED_FOR_IMPLEMENTATION` A1 计划。

**本报告不构成实现授权。**
