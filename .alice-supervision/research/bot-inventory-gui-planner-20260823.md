# Bot Inventory GUI 最小实现线路（只读规划）

- 规划角色：Alice 实现规划员+
- 规划日期：2026-08-23
- Git 基线：`a6b9f931b67bd64b3c4c5318a7d423c5300a9d20`（当前 HEAD；WSL 与 Windows 同步）
- 工作会话门禁：`./tools/work-session-start.sh --allow-no-plan` 已执行，但报 `NO_ACTIVE_PLAN`，以退出码 1 拒绝启动。这与 active-plan 现状一致（`.alice-supervision/active-plan.md` 为 `idle / NO_ACTIVE_PLAN`，下一步方向待用户决定，其中正包含用户申请的本 GUI）。
- 协议名：`docs/SUPERVISION_PROTOCOL.md`（为玩家开放 GUI 需独立批准工作包；客户端可见行为须用户 Windows 实测）。

> **本规划不构成实现授权。** 未经监督员采纳、用户批准并建立新的 `APPROVED_FOR_IMPLEMENTATION` active plan，不得实现。当前 `NO_ACTIVE_PLAN` 状态下无人被授权开工。

## 0. 结论概览

Alice 目前**没有任何 GUI/Menu/Screen 代码**（`src/main/java` 全树搜索 `ContainerMenu|MenuProvider|AbstractContainerMenu|MenuType|Screen|AbstractContainerScreen|openMenu|IInventory|MenuScreens|registerMenuType` 命中为 0）。因此本工作包是从零引入 Forge 1.20.1 容器菜单体系，属中等复杂度、跨客户端/服务端边界，应作为**独立、需用户批准**的工作包推进，不宜并入任何现有 package。

建议最小可行方案为**只读优先**：先实现"服务端权威快照 → 客户端 Screen 只读查看 Bot 36+装备+主手槽位"，再视用户决策决定是否加"交互写入"。理由与取舍见 §3。

服务端→客户端数据流不依赖 `bot.connection.send()`（那是 `FakeConnection` 的 no-op，`FakeConnection.java:34-37`），而是**操作者真实玩家**的 `ServerPlayer.openMenu()` 触发，packet 经玩家真实 connection 发送。
 
## 1. 现状摘要

### 1.1 Bot 玩家模型与生命周期

- `BotPlayer extends ServerPlayer`（`src/main/java/com/dddgn/alice/bot/BotPlayer.java:22-26`），经 `BotManager.spawn` 里 `server.getPlayerList().placeNewPlayer(new FakeConnection(SERVERBOUND), bot)` 注册（`src/main/java/com/dddgn/alice/bot/BotManager.java:69-88`）。bot 持有原版玩家 `Inventory`（36 普通 + 4 装备 + 主手/offhand）。
- `BotManager.remove` 走 `playerList.remove` + `discard` + `clearBot`（`BotManager.java:91-105`）；`restoreFromWorld` 恢复（`BotManager.java:140-178`）。bot 死亡目前是过滤而非完整死亡流程（HANDOVER `docs/HANDOVER.md:65`）。
- `BotManager.BotSession`（内部类）持有当前 `Task`、`ScopeBuffer`、`TaskExecutionRecord`，`assign*` 系列是任务入口（`BotManager.java` 后半段）。一个 bot 同一时刻只有一个 active task；`replaceTaskIfRunning()` 检查 `TransferLedgerData.blocksBot` 阻止 in-transit/suspended 转移被普通任务替换（`BotManager.java:434-448`）。
- `syncMainHand` 用 `bot.connection.send(...)` 发送主手 slot（`BotManager.java:107-113`）。这正是 `FakeConnection` 的 no-op 路径。

### 1.2 命令组织（`BotCommand.java`）

- 单个 `Commands.literal("alice").requires(source -> source.hasPermission(2))` 根，`then(...)` 挂全部子命令（`src/main/java/com/dddgn/alice/command/BotCommand.java:53-54`）。新增一个 `/alice bot-inventory` 子命令即可沿用 permission-2 门禁。
- `@Mod.EventBusSubscriber(modid="alice", bus=FORGE)` + `RegisterCommandsEvent`（`BotCommand.java:43-51`）。命令树已含 `spawn/mine/transfer-test/transfer-selection/status/protect/...` 等。

### 1.3 transfer 的 inventory/slot 语义（36 槽权威圈）

- `TransferRequest`：不可变请求，含 `source/destination ChestEndpointRef + itemId + count`，构造器拒绝跨维度/同端点/非法 count（`src/main/java/com/dddgn/alice/transfer/TransferRequest.java:8-28`）。
- `ChestEndpointRef.validate`：同维度/已加载/原版单箱/handler（`ChestEndpointRef.java:23-41`）。
- `InventoryObservation.BOT_ORDINARY_SLOT_COUNT = 36`，`observeBot` 只遍历 0..35 普通槽（`src/main/java/com/dddgn/alice/transfer/InventoryObservation.java:16,32-38`）；`CapacityPreflight`/`ChestBotTransferPrimitive.insertBot/removeBot` 都只操作这 36 槽（`ChestBotTransferPrimitive.java:191-227`）。
- 关键交叉点：`ChestBotTransferPrimitive.syncBotInventorySlots` 仍用 `bot.connection.send(ClientboundContainerSetSlotPacket(...))` 发全部 36 槽（`ChestBotTransferPrimitive.java:232-236`），**由于 `FakeConnection.send()` 是 no-op，这些 slot packet 不会到达任何真实客户端**；紧随其后用 `bot.level().getChunkSource().broadcast(bot, ...)` 广播主手装备（`ChestBotTransferPrimitive.java:237-243`）——这才是之前 equipment 渲染修复 `ef43bd6` 的正确模式。也就是说：**当前 bot 的完整 36 槽数据对真实玩家窗口不可见，只有主手装备经 broadcast 可见。**

### 1.4 是否已有 GUI/menu/screen

无。`src/main/java/com/dddgn/alice/` 全树无任何 `Menu/Screen/ContainerMenu/MenuProvider`；`com.dddgn.alice.client` 只有 `ClientTargetState`/`ClientRoadState`（`@OnlyIn(Dist.CLIENT)` 静态状态类）与 `render/TargetOutlineRenderer`（`src/main/java/com/dddgn/alice/client/ClientTargetState.java`、`client/render/TargetOutlineRenderer.java`）。已有 S2C 通道是 `AliceNetwork.SimpleChannel`（`src/main/java/com/dddgn/alice/network/AliceNetwork.java:12-34`），注册了 `TargetPacket`/`RoadPlanPacket` 两个 S2C 包，packet 用 `record + encode/decode/handle` 模式（`TargetPacket.java:19-46`）。

### 1.5 构建/运行配置

- 单一 main source set；mods.toml `side="BOTH"`（`src/main/resources/META-INF/mods.toml:13-25`），无独立客户端 source set。客户端代码用 `@OnlyIn(Dist.CLIENT)`（如 `ClientTargetState`、`TargetOutlineRenderer`）在同一 jar 区分。**不存在必须单独分发的客户端 mod**，这是好消息。
- `runServer` 是 headless 验收入口（`build.gradle:92-98`）。`./gradlew compileJava` 是硬性编译门槛。

## 2. 固定版本 API 事实（只读 javap 核验）

来源 jar：`/home/fb486/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1/forge-1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1.jar`。

| API | 签名（1.20.1-47.4.10 mapped） | 用途 |
|---|---|---|
| `AbstractContainerMenu` | `protected AbstractContainerMenu(MenuType<?>, int containerId)`；`addSlot(Slot)`；`quickMoveStack(Player,int)` abstract；`clicked(int,int,ClickType,Player)`；`removed(Player)`；`broadcastChanges()`；`setSynchronizer(ContainerSynchronizer)` | 自定义菜单基类 |
| `MenuType` | `MenuType(MenuSupplier<T>, FeatureFlagSet)`；`create(int, Inventory)` | 注册菜单类型 |
| `MenuType.MenuSupplier<T>` | `T create(int containerId, Inventory playerInv)` | 服务端创建函数 |
| `IForgeMenuType<T>` | `static <T> MenuType<T> create(IContainerFactory<T>)` | Forge 工厂注册（可携带额外 buffer 参数） |
| `Registries.MENU` | `ResourceKey<Registry<MenuType<?>>>` | 菜单注册表 key |
| `ServerPlayer.openMenu(MenuProvider)` | `OptionalInt openMenu(MenuProvider)` | 服务端打开菜单；返回 containerId |
| `MenuProvider` | `extends MenuConstructor`；`Component getDisplayName()` | 服务端打开源 |
| `Inventory` | `getContainerSize()`；`getItem(int)`；`getArmor(int)`；`getSelected()`；`public int selected` | bot 槽位访问 |
| `MenuScreens.register` | `static <M,U> void register(MenuType<? extends M>, ScreenConstructor<M,U>)`（客户端） | 绑定菜单类型与 Screen |

这些均已通过只读 `javap` 确认存在，可作为编译级事实；其中 `MenuType.MenuSupplier(id, playerInv)` 不能携带 bot 引用，需要 `IForgeMenuType.create(IContainerFactory)`（能从 `FriendlyByteBuf` 读 bot UUID）或服务端持有 bot 引用（见 §3 取舍）。

## 3. 实现线路（按依赖排序）

> 声明边界：以下最小方案**以"只读查看"为第一版**。用户申请含"查看/交互"，但"交互写入"会改变 bot inventory，触及 transfer 36 槽、`MineTask` 主手替换、`TransferTask` in-transit 保护等已 USER_ACCEPTED 语义，绝不能与本包打包。因此本包只做"可从玩家窗口打开一个只读 Bot inventory 面板"，交独立于交互写入，另立工作包。

### 3.1 依赖路线选择

- **方案 A（推荐）：`openMenu` + 真实菜单** —— 服务端 `player.openMenu(provider)`，packet 经玩家真实 connection 发 `ClientboundOpenScreenPacket` 给操作者；菜单的 slot 同步（`broadcastChanges`）也走操作者 connection。这是最"原版"的路径，天然解决"bot 的 FakeConnection 不能发包"的问题，因为发送方是操作者（真实玩家），不是 bot。
- **方案 B：自定义 S2C packet + `@OnlyIn` Screen，不用 MenuType** —— 服务端把 36+4+1 槽编成一个 packet 发给操作者，客户端自绘 Screen。无容器菜单、无 slot 同步、无 quickMove。**对"只读查看"达到同样效果且更简单**，但不被后续"交互写入"复用（交互要菜单 click / `ServerboundContainerClickPacket`）。
- 取舍：若第一版只要"能看到 bot 背包"，方案 B 最小、跨面最小、最容易回滚。若要作为"Bot 设置页面"演进（用户提到）且未来要交互，方案 A 可持续复用，但实现重、需处理 slot 同步/权限/离线。
- **建议**：第一版**方案 B（只读快照 + Screen）**，因为①用户申请首要是"查看"；②对"交互"先做浅调研确认语义与权限再立项；③避免把只读查看与写入语义耦合。方案 A 作为"交互/设置页"下一阶段的候选，单独独立工作包。

### 3.2 方案 B 文件清单

新增（服务端）：
1. `com.dddgn.alice.gui.BotInventorySnapshot`（`record`，含 `uuid, name, List<Slot>`，`Slot(itemId,count,isEmpty,slotLimit)`）：不可变快照。只读。
2. `com.dddgn.alice.network.BotInventoryPacket`（S2C）：`record + encode/decode/handle`，复用 `AliceNetwork` 通道；在 `AliceNetwork.register()` 里 `registerMessage(nextId++, ...)`。
3. `com.dddgn.alice.gui.BotInventoryQuery`（服务端入口）：从 `BotManager.firstInLevel` 或按 UUID 取 bot，读 `bot.getInventory()` 的 36 普通槽 + `getArmor(0..3)` + `getSelected()`，构造快照并 `CHANNEL.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer)requester), packet)`。

修改（服务端）：
4. `com.dddgn.alice.network.AliceNetwork`：注册 `BotInventoryPacket`（S2C）。
5. `com.dddgn.alice.command.BotCommand`：新增 `/alice bot-inventory` 子命令（`requires(hasPermission(2))`），执行体调 `BotInventoryQuery`，并给操作者发一条 chat 反馈。

新增（客户端，`@OnlyIn(Dist.CLIENT)`，同 jar）：
6. `com.dddgn.alice.client.ClientBotInventoryState`：持最新快照（volatile），供 Screen 渲染。
7. `com.dddgn.alice.client.gui.BotInventoryScreen`：`extends Screen`，在 `render` 里按 36+4+1 槽位格式绘制 `ItemStack` 图标与数量；通过 `FMLClientSetupEvent`/`ClientListener` 打开（或由命令的 S2C 包驱动）。若用 Screen 直接打开，需在客户端监听命令反馈或服务端在发包后主动发一个"打开"提示。

> 打开方式取舍：方案 B 客户端 Screen 可以由（a）命令本身的 S2C 反馈包携带"打开"标志，或（b）`/alice bot-inventory` 在客户端用本地 Screen 打开。因为 `openMenu` 属于方案 A，方案 B 的"打开"更适合由服务端 S2C 包指示客户端弹窗。这需要客户端在收到 `BotInventoryPacket` 时调用 `minecraft.setScreen`。

> 如需（可选）把菜单打开，minimal 也不排除方案 A：新增 `com.dddgn.alice.gui.BotInventoryMenu`（`AbstractContainerMenu`）+ `BotInventoryMenuProvider`（`MenuProvider`）+ `BotInventoryMenu.Screen`，注册 `MenuType` via `Registries.MENU` + `IForgeMenuType.create`，客户端 `MenuScreens.register`。此面作为"交互/设置页"候选，不进入第一版只读包。

### 3.3 依赖 DAG（建议包内顺序）

```text
P0 监督员采纳本报告 + 用户确认（只读 or 交互；是否并入设置页）
P1 服务端快照读取（BotInventorySnapshot 构造 bot inventory 槽位事实；不动 inventory）
P2 BotInventoryPacket（S2C，AliceNetwork.register 注册）
P3 /alice bot-inventory 命令（permission-2 gate + 反馈）
P4 客户端 ClientBotInventoryState + BotInventoryScreen（@OnlyIn）
P5 服务端 focused fixture（断言快照 reads 36+4+1、命令 gate、无 inventory 写）+ ./gradlew compileJava
P6 HANDOVER + commit/push + session-complete -> 监督 review -> CLIENT_TEST_PENDING -> Windows
```

### 3.4 关键 packet 流（方案 B）

```text
操作者: /alice bot-inventory
  -> BotCommand 校验 permission(2)
  -> BotInventoryQuery.read(server, actor, questBot)
       读取 bot inventory 36+4+1 -> BotInventorySnapshot
  -> AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(actor), BotInventoryPacket(snapshot))
  -> 客户端 (actor) 收到 -> ClientBotInventoryState.update(snapshot)
  -> (若含 open 标志) Minecraft.getInstance().setScreen(new BotInventoryScreen(...))
  -> Screen.render 读 ClientBotInventoryState 绘制 36+4+1
```

## 4. 依赖：需深调研 vs 可自主决定

- **可自主决定（本地代码/固定版本即可）**：
  - bot inventory 的槽位语义（36 普通 = `getItem(0..35)`；4 装备 = `getArmor(0..3)`；主手 = `getSelected()`/`selected`；offhand 是否纳入需用户确认）。
  - 命令入口组织（沿用 `BotCommand` 现有 permission-2 路由）——无需外部依赖。
  - 现有 `BotManager.firstInLevel / BOTS` 查询方式——本地已知。
  - 现有 `AliceNetwork` S2C packet 模式（`record + encode/decode/handle`）——本地已知。
- **需要深度调研（外部框架模式 + 固定版本证据）**：
  - Forge `MenuType`/`IForgeMenuType` 在 1.20.1-47.4.10 的**注册与 `IContainerFactory` 反序列化**若采用方案 A（需从 `FriendlyByteBuf` 读 bot UUID）；目前只核验了签名存在，未核验注册事件/`FMLClientSetupEvent` 里 `MenuScreens.register` 的完整接线。
  - 客户端 Screen 在**无 `clientOnly` source set / 单 jar `@OnlyIn`** 下如何安全加载：现有 Alice 用 `@OnlyIn(Dist.CLIENT)`（`ClientTargetState`、`TargetOutlineRenderer`）已验证可行，但 `Screen` 子类引用 `net.minecraft.client.gui` 需确认同一 jar 中 `@OnlyIn` 不会引入服务端类加载问题（常见做法确认）。
  - 若走方案 A，`ServerboundContainerClickPacket`/`AbstractContainerMenu.clicked` 的服务端权威、以及菜单与 bot 正在执行 `TransferTask`/`MineTask` 在途时的交互权限，属于写入语义，应深调研。

## 5. 风险

1. **`bot.connection.send()` 是 no-op（`FakeConnection.java:34-37`）** —— 任何把 slot packet 发给 bot 自己的尝试都无效（`ChestBotTransferPrimitive.java:232-236` 与此前 equipment 渲染修复的教训）。GUI 必须把数据发给**操作者**的真实 connection（方案 B 用 `PacketDistributor.PLAYER.with(actor)`，方案 A 用 `actor.openMenu`），绝不能 `bot.connection.send`。
2. **交互写入会破坏既有语义**：bot inventory 写入若走菜单 click，可绕过 `TransferTask` in-transit 保护、`MineTask` 主手替换、`CapacityPreflight` 单腿契约。**第一版只读，交独立工作包；若用户要交互，必须新增专门计划并重新过账本/任务保护。**
3. **权限门禁**：`/alice bot-inventory` 必须沿用 `requires(hasPermission(2))`（`BotCommand.java:54`），避免非管理员窥探/操作 bot 背包。
4. **客户端 class-loading**：单 jar `@OnlyIn(Dist.CLIENT)` 的 Screen 类若被服务端引用会崩溃；Screen 只能由客户端侧类/事件引用，服务端只发数据 packet。
5. **bot 不在场/已移除**：`BotManager.firstInLevel` 可能返回 null，必须先判空并返回 `bot_unavailable` 稳定错误，不猜测。
6. **设备/槽位模型**：是否把 offhand（2 号装备槽）与 `selected` 主手重复展示需要用户确认；避免把主手与 36 普通槽中的 `selected` 槽位重复计数。
7. **snapshot 陈旧**：若 bot 正在执行 transfer/task，inventory 会持续变化；只读快照只反映拍下瞬间，GUI 刷新策略需与任务写完保持一致（可选 `broadcastChanges` 方案 A 才实时；方案 B 需手动刷新或重发）。

## 6. 停止条件

- active plan 仍为 `NO_ACTIVE_PLAN` 或用户尚未确认"只读 vs 交互"；
- 需要修改 `TransferRequest`/`ChestEndpointRef`/`CapacityPreflight`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`TransferTask`/`BotManager.assignTransfer` 或任务链语义才能完成；
- 需要写 bot inventory（物品移动、curse、shift-click、quickMove、物品放进 bot）→ 转入交互包，另立计划；
- 需要改变 `MineTask`/`DropCollectionTask`/道路/隧道/流体/逃生/`FollowTask`/`HARD_PATH` 行为；
- `@OnlyIn` 单 jar 加载 Screen 在服务端崩溃或无法在客户端打开；
- 客户端 SHM/依赖问题（JEI/JECh/JECh 纯客户端注入）影响 GUI 渲染；
- 需要新增客户端 mod 依赖或独立 source set 才能显示（当前 side=BOTH 应不需要，若需则停止重评估）。

## 7. 客户端验收矩阵（Windows，独立 `CLIENT_TEST_PENDING`）

单轮总表单 + 场景证据包（`docs/SUPERVISION_PROTOCOL.md` 相关条款）：`latest.log`、`debug.log`、`evidence-report.md`；场景以 是/否 记录 + 截图。预期日志关键字（建议）：`bot_inventory: query player= bot= slots=36+4+1 code=accepted`；`bot_inventory_packet: sent player= bot= entries=N`；拒绝时 `code=bot_unavailable`/`code=unauthorized_actor`。

| 场景 | 观察点 | 预期关键字/结果 | 失败回收条件 |
|---|---|---|---|
| G1 入口与授权 | 非 op 执行被拒；op 执行后弹窗 | `unauthorized_actor`（非 op）；op 收到快照 | 非 op 能打开 |
| G2 只读查看 36 普通槽 | 面板显示 36 格；与 transfer 期望一致 | `slots=36` | 槽位数量/内容错乱 |
| G3 4 装备槽 | 显示头盔/胸甲/护腿/靴子 | `slots=... armor=4` | 装备槽缺失或错位 |
| G4 主手槽 | 显示 bot 当前主手（selected）；与 `getSelected()` 一致 | 主手物品与 bot 窗口一致 | 主手显示错误（应与 equipment 渲染 `ef43bd6` 对齐） |
| G5 空槽/多槽/堆叠 | 空槽显示空白；多格堆叠显示数量 | 数量正确 | 数量/堆叠错误 |
| G6 GUI 隔离 | 打开 bot-inventory 面板不影响普通箱子 GUI / 玩家自身背包 | 无报错；原版 GUI 正常 | 面板拦截/干扰其他 GUI |
| G7 bot 不在场 | bot 移除后请求返回 `bot_unavailable` | `code=bot_unavailable`，无面板 | 空 bot 崩溃或显示陈旧 |
| G8 不写 bot 背包 | 面板打开/关闭后 bot inventory 无变化（记录前后三端） | 无 `setItem`/`setChanged` 触发 | 任何写操作 |
| G9 客户端无服务端崩溃 | 服务端/客户端均无崩溃栈 | 无 `NoClassDefFound` / `ClassNotFoundException` | 任何类加载崩溃 |
| G10 权限与回归 | 既有 transfer/equipment/死亡过滤测试场景不受影响 | 无回归 | 既有 USER_ACCEPTED 场景回退 |

A1.1 既有矩阵（`docs/HANDOVER.md`）与本包独立；本包只验收"只读查看 Bot inventory 面板"这一原语，不升级为 bot inventory 写入/设置页交互、不接入普通任务链、不泛化到 C2/C3/C4。

## 8. 交付状态

- 报告路径：`.alice-supervision/research/bot-inventory-gui-planner-20260823.md`
- 本任务只读：未修改业务代码、active plan、HANDOVER、客户端/审核记录；未派发任务；未运行测试。
- 当前 active plan 状态 `NO_ACTIVE_PLAN`，本报告保持待监督员采纳 + 用户批准后才可能进入开发。
- **本规划不构成实现授权。**
