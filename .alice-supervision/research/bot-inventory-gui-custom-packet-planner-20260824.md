# Bot Inventory GUI 方向 B（自定义 packet + Screen）最小实现线路 — 只读规划

- 规划角色：Alice 实现规划员+
- 规划日期：2026-08-24
- Git 基线：`1621ad2c19a7852a4a380dfd8d47a2d3d42a704d`（当前 HEAD；WSL = Windows 同步）
- 工作会话门禁：`./tools/work-session-start.sh --allow-no-plan` 已执行，报 `PLANNING` 拒绝启动；active-plan 为 `20260824-bot-inventory-gui-custom-packet-v1 / PLANNING`（用户已批准方向 B，等待规划员线路）。
- 依据报告：`.alice-supervision/research/player-view-other-entity-inventory-20260823.md`（6 轮失败根因 = 混合菜单逻辑，非 FakeConnection）。

> **本规划不构成实现授权。** 当前 `PLANNING` 状态仅允许监督员审核本线路；在计划转 `APPROVED_FOR_IMPLEMENTATION` 前不得改代码、运行实现验证或要求客户端复测。

## 0. 结论概览

方向 B 用**自定义 S2C 快照 + 自定义 C2S action packet + 客户端纯 `Screen`**，彻底绕开 `AbstractContainerMenu`/`AbstractContainerScreen`/`MenuScreens`/`MenuType`，以根治 6 轮失败的混合 index 分区与 `setChanged` 同步链路。

**建议结构（最小可行）**：
- 服务端只输出权威事实：`BotInventorySnapshot`（uuid/name + 36 普通 + 4 armor + 1 offhand）经 S2C `BotInventoryPacket` 推给操作者；任何"拿取/放入/Shift"都以 C2S `BotInventoryActionPacket` 回服务端，由服务端 handler 校验（`taskActive` 只读保护、index 合法性、堆叠/上限、物品 move）后写入 `bot.getInventory()`，再回推新快照。
- 客户端 `BotInventoryScreen`（纯 `Screen`，不用 `AbstractContainerScreen`）读取 `ClientBotInventoryState`（`@OnlyIn` volatile 快照）自绘 36+4+1 槽；点击/拖拽/Shift 只发 C2S intent，不本地改数据。
- 废弃（或不再注册）`BotInventoryMenu`/`ModMenuTypes`/`ClientMenuScreens`；`BotInventoryScreen` 不再是 `AbstractContainerScreen`，不再绑定 `MenuType`。

这满足 active-plan 的"彻底绕开容器菜单协议"，并消除拿取消失/翻倍/Shift 错乱的根源（因为客户端不再维护与 bot 真实 index 混编的 slots 列表，一切以服务端快照为唯一权威，所有写走服务端）。

## 1. 现状摘要

### 1.1 现有容器菜单结构（本次需替换/废弃）

| 文件 | 内容 | 方向 B 处置 |
|---|---|---|
| `src/main/java/com/dddgn/alice/gui/BotInventoryMenu.java` | `AbstractContainerMenu`；bot 41 槽（36 普通 + 4 armor + 1 offhand，index 0..40）+ 玩家 36 槽（index 41..76）**混编进同一 slots 列表**；`quickMoveStack`/`clicked` 手写 index 分区；`taskActive` 只读保护；`BotArmorSlot`/`BotOffhandSlot`（能穿装备/绑定诅咒）。具体：armor 在 `getContainerSlot()` 39-36，offhand 40，主手是 hotbar 0..8 中 `selected`（不重复）。 | **废弃**（删除或停用）。`taskActive` 读保护逻辑迁移到服务端 C2S handler。 |
| `src/main/java/com/dddgn/alice/gui/ModMenuTypes.java` | `DeferredRegister<MenuType<?>>`，注册 `bot_inventory` 菜单类型，`IForgeMenuType.create` 用 `BotInventoryMenu.fromServer`。 | **废弃**；`AliceMod` 里 `ModMenuTypes.MENUS.register(modEventBus)`（`AliceMod.java:31`）一并移除。 |
| `src/main/java/com/dddgn/alice/client/gui/BotInventoryScreen.java` | `AbstractContainerScreen<BotInventoryMenu>`，`renderBg` 用 `minecraft:inventory.png` 背景 + 下方面板画槽格。 | **重写**为纯 `Screen`，改用 `ClientBotInventoryState` 快照自绘。 |
| `src/main/java/com/dddgn/alice/client/gui/ClientMenuScreens.java` | `@OnlyIn` `FMLClientSetupEvent` 里 `MenuScreens.register(ModMenuTypes.BOT_INVENTORY_MENU.get(), ...)`。 | **废弃**；不再绑定 `MenuType`/`MenuScreens`。 |
| `src/main/java/com/dddgn/alice/gui/BotInventoryFixture.java` | 服务端 fixture：断言菜单槽数/坐标不重叠/taskActive 标志/quickMoveOut 语义。 | **改写**为对"snapshot 读取 + C2S action handler（含任务保护、堆叠、move 语义）"断言；不再断言 `AbstractContainerMenu` 具体槽对象。 |
| `src/main/java/com/dddgn/alice/command/BotCommand.java:188` | 用 `player.openMenu((containerId, playerInv, p) -> new BotInventoryMenu(containerId, playerInv, bot), title)` 打开菜单。 | **替换**为：服务端读快照 → 发 `BotInventoryPacket(S2C)` → 客户端据此 open `BotInventoryScreen`（或由命令本身经 S2C 指示打开）。 |

### 1.2 命令组织与任务状态

- `/alice` 根 `requires(hasPermission(2))`（`BotCommand.java:53-54`），现有 `bot-inventory` 相关入口在此。方向 B 沿用 permission-2 门禁。
- `BotManager.isBusy(BotPlayer)` = `BOTS.get(uuid).task != null`（`BotManager.java:307-310`）。`bot.getInventory()` 为 `ServerPlayer` 原版 `Inventory`：`getItem(0..35)` 普通、`getArmor(0..3)` 装备、`getSelected()` 主手（ordinary 的 `selected`）、`getItem(40)` offhand。
- `BotManager.firstInLevel(level)` / `firstOrSpawn(level,pos)` / `BOTS.get(uuid)` 已存在，可供 C2S handler / 命令取 bot。

### 1.3 packet 通道

- `AliceNetwork.SimpleChannel CHANNEL`（`AliceNetwork.java:12-34`），PROTOCOL_VERSION `"1"`，`register()` 已注册 `TargetPacket`/`RoadPlanPacket`（均为 `record + encode/decode/handle`，`PLAY_TO_CLIENT`）。方向 B 在该通道追加两个包：S2C 快照 + C2S action（`NetworkDirection.PLAY_TO_SERVER`）。

### 1.4 客户端/服务端边界

- 单 jar `side=BOTH`（`mods.toml:10-25`），无独立客户端 source set；客户端类用 `@OnlyIn(Dist.CLIENT)`（`ClientTargetState`、`ClientRoadState`、现有 `BotInventoryScreen`/`ClientMenuScreens`）。方向 B 的纯 `Screen` 与 `ClientBotInventoryState` 继续 `@OnlyIn`，服务端只发 packet 不引用 Screen 类。
- 关键事实（研究报告 §1、§5 已确认）：**`FakeConnection.send()` no-op 不是障碍**。玩家看/操作 bot inventory 走的是"打开菜单的玩家"自己的 connection。方向 B 自定义 packet 同理：`PacketDistributor.PLAYER.with(viewer)` 发给真实玩家 connection，绝不 `bot.connection.send`。

## 2. 实现线路（按依赖排序）

### 2.1 服务端事实层（无 packet）

1. **`com.dddgn.alice.gui.BotInventorySnapshot`**（`record`）：
   - 字段：`UUID botId`、`String name`、`List<BotSlot> slots`；`BotSlot(int index, ItemStack item, boolean isArmor, boolean isOffhand)` 或直接携带 `ItemStack` + 槽位角色。
   - 槽位组织明确为 36 普通（`getItem(0..35)`）+ 4 armor（`getArmor(0..3)`）+ 1 offhand（`getItem(40)`）；主手 = 36 中 `selected`，**不重复**（沿用现有语义 `BotInventoryMenu` 注释）。
   - 只读：不持有 bot 引用、不写 inventory。
2. **`com.dddgn.alice.gui.BotInventoryService`**（服务端读取/写入入口）：
   - `static Optional<BotInventorySnapshot> snapshot(ServerLevel level, UUID botId)`：从 `BotManager`/`BOTS` 取 bot，读 `bot.getInventory()` 构造快照；bot 不存在返回 empty。
   - `static ValidationResult applyAction(ServerLevel, UUID botId, ServerPlayer actor, ActionIntent intent)`：**唯一**写入口，服务端权威校验后写 `bot.getInventory()`。见 §2.2。

### 2.2 S2C 快照包

3. **`com.dddgn.alice.network.BotInventoryPacket`**（S2C，`record`）：
   - 内容：`UUID botId` + `List<SlotData>`（index/item 角色/ItemStack 序列化），`encode/decode/handle`。
   - `handle`：`context.enqueueWork(() -> ClientBotInventoryState.update(...))`；`PLAY_TO_CLIENT`。
   - 在 `AliceNetwork.register()` 注册（`nextId++`）。

### 2.3 C2S action 包（服务端权威写）

4. **`com.dddgn.alice.network.BotInventoryActionPacket`**（C2S，`record`）：
   - 内容：`UUID botId`、`ActionType`（`PICKUP`/`PLACE`/`QUICK_MOVE`/`MOVE`）、`int slotIndex`、`ItemStack payload`（可选，用于放入）、`int cursorSlot`（可选，用于 PICKUP 指定目标格）。
   - `encode/decode/handle`；`handle` 在 `PLAY_TO_SERVER` 包中 `context.enqueueWork` 调服务端 handler。
5. **服务端 handler**（`BotInventoryService.applyAction` 内部）：
   - 先校验 `actor` 权限（send 方即操作者，服务端仍须校验 `hasPermission(2)`）；`botId` 有效；`taskActive == BotManager.isBusy(bot)`。
   - **只读保护**：`if (BotManager.isBusy(bot)) return REJECTED(task_active)` —— 与现有 `BotInventoryMenu.taskActive` 语义一致（`BotInventoryMenu.java:135`、`:211`）。
   - index 合法性：0..35 普通 / armor 映射 36..39 / offhand 40。
   - 依 `ActionType` 执行：PICKUP（把目标槽物品搬到 cursor / 指定槽）、PLACE（cursor 或 payload 放入可用槽，处理堆叠与上限）、QUICK_MOVE/MOVE（把一槽移到另一槽，处理 `getMaxStackSize`、可穿装备判定 `canEquip`、绑定诅咒 `hasBindingCurse`）。
   - **复用现有语义**：armor/offhand "只能放对应装备、maxStack=1、绑定诅咒不可取走" 等规则（把 `BotArmorSlot.mayPlace/mayPickup/getMaxStackSize` 与 `BotOffhandSlot` 的判定搬到服务端 handler）。
   - 写后回推新快照。

### 2.4 命令入口

6. **`BotCommand`（替换 `BotInventoryMenu` 打开逻辑）**：
   - 新增 `/alice bot-inventory [<botName|UUID>]`（`requires(hasPermission(2))`），执行体：取 bot → `BotInventoryService.snapshot` → 发 `BotInventoryPacket` 给操作者，并 `sendSuccess` 一条 chat 反馈（含 `requestId`/botId/槽数）。
   - 客户端收到 S2C 快照包时，若处于匹配状态则打开 `BotInventoryScreen`；也可以由命令直接以 `BotInventoryPacket` 的"open"标志指示客户端 open。**建议**：S2C 包只携带数据，客户端 `BotCommand` 的花式打开由客户端在收到快照时自行 `setScreen`（需保证只对当前操作者触发，用 `PacketDistributor.PLAYER.with(actor)` 已限定）。

### 2.5 客户端 layer

7. **`com.dddgn.alice.client.ClientBotInventoryState`**（`@OnlyIn`）：
   - `volatile BotInventorySnapshot latest`；`update(packet)` 更新；`Snapshot get()`。只读。
8. **`com.dddgn.alice.client.gui.BotInventoryScreen`**（纯 `Screen`，重写）：
   - `extends Screen`（不再 `AbstractContainerScreen`），`render`/`renderBackground` 自绘 36+4+1 槽：读取 `ClientBotInventoryState.get()`；每个槽画 `ItemStack` + 数量。
   - 点击/拖拽/Shift：本地不改数据，把意图编码为 `BotInventoryActionPacket`（C2S）发给服务端；等待回推快照刷新。
   - 槽位坐标/背景沿用现有 `inventory.png` + 扩展面板布局（复用 `BotInventoryScreen` 现有绘制思路，但数据源改为快照）。

### 2.6 清理与注册

9. `AliceMod` 移除 `ModMenuTypes.MENUS.register(modEventBus)`（`AliceMod.java:31`）；若保留 `ModMenuTypes` 文件则不再注册（或整文件标记 `@Deprecated`）。
10. `ClientMenuScreens` 移除 `MenuScreens.register(...)`（无 `MenuType` 可绑）。
11. 若 `BotInventoryMenu`/`ModMenuTypes` 文件不再被引用，删除或保留为未引用（监督员定夺；建议删除以清出容器菜单残留，避免未来误用）。

### 2.7 依赖 DAG

```text
P0 监督员采纳本报告 -> 用户确认交互语义（拿取/放入/Shift 允许哪些、是否允许装备穿戴）-> active plan 转 APPROVED_FOR_IMPLEMENTATION
P1 BotInventorySnapshot + BotInventoryService.snapshot（读）
P2 BotInventoryPacket（S2C）+ AliceNetwork.register 注册
P3 BotInventoryActionPacket（C2S）+ BotInventoryService.applyAction（含 taskActive 保护、index/堆叠/上限/armor 规则）
P4 /alice bot-inventory 命令（permission-2 + 发快照）
P5 ClientBotInventoryState + BotInventoryScreen（纯 Screen，自绘 + C2S 发送）
P6 清理 BotInventoryMenu/ModMenuTypes/ClientMenuScreens
P7 改写 BotInventoryFixture（snapshot + applyAction 断言）+ ./gradlew compileJava
P8 HANDOVER + commit/push + session-complete -> 监督 review -> CLIENT_TEST_PENDING -> Windows
```

## 3. 依赖评估

- **可自主决定（本地代码/已有固定版本即可）**：
  - `BotManager` 取 bot（`firstInLevel`/`BOTS.get(uuid)`）、`BotManager.isBusy`（任务状态）。
  - `bot.getInventory()` 的槽位布局：36 普通（`getItem(0..35)`）、4 armor（`getArmor(0..3)`）、offhand（`getItem(40)`）、主手 `selected`（不重复，沿用现有语义）。
  - `AliceNetwork.SimpleChannel` 现有 `record + encode/decode/handle` S2C 模式（`TargetPacket`/`RoadPlanPacket`）。
  - 命令组织（`BotCommand` permission-2 路由）。
  - 客户端 `@OnlyIn(Dist.CLIENT)` 单 jar 模式。
- **需参考（外部/固定版本模式，须在实现前 source check 或浅调研确认）**：
  - `Screen` 自绘渲染 API（`GuiGraphics`/`render`/`renderBackground`/`renderSlot`/`renderItem`）在 1.20.1 的用法；现有 `BotInventoryScreen.renderBg` 已用 `GuiGraphics.blit/fill`，可作为基线。
  - `ItemStack` 序列化到 `FriendlyByteBuf`（`ItemStack.OPTIONAL_STREAM_CODEC` 或手动 `writeItemStack/readItemStack`）——已有 `ChestBotTransferPrimitive` 无此先例，需固定 1.20.1 方法。
  - C2S packet 经 `SimpleChannel` 方向 `PLAY_TO_SERVER` 的注册与 `NetworkEvent.Context` 校验（`context.getDirection().getReceptionSide().isServer()`）。
  - armor 穿戴判定 `ItemStack.canEquip(EquipmentSlot, LivingEntity)`、绑定诅咒 `EnchantmentHelper.hasBindingCurse` 已用于现有 `BotArmorSlot`，可直接复用。

## 4. 风险

1. **客户端 Screen 自绘复杂度**：纯 `Screen` 需自己处理槽位坐标、物品图标、拖拽光标、Shift-堆叠交互、以及"光标随身携带物品"的渲染。工作量比 `AbstractContainerScreen` 大，且"光标携带"需要本地 cursor 状态 + 服务端确认，避免本地擅自改动导致拿取消失。**建议**：第一版只支持"点击拿取/放入单格 + Shift 快速移动"，暂不实现拖拽 cursor（cursor 本地不持有，由服务端 cursor 快照回推）。若用户要完整拖拽，需单列。
2. **服务端权威与客户端乐观 UI 冲突**：若客户端在服务端确认前就显示新状态，会重现"拿取消失/翻倍"。**必须**：客户端不发包不更新本地；收到服务端回推快照后才刷新（唯服务端权威）。这是方向 B 的核心正确性保证。
3. **任务保护（只读）**：`BotManager.isBusy` 在 bot 执行 `TransferTask`/`MineTask` 等时返回 true；C2S handler 必须对此拒绝，否则用户可在 transfer in-transit 期间改 bot inventory，破坏 `TransferLedgerData` 记录。**必须**在 `applyAction` 首查 `taskActive`，返回 `task_active` 拒绝并回推只读快照。
4. **主手/装备/offhand 映射**：主手 = 36 中 `selected`（不重复）；armor/offhand 有"只能放对应装备、maxStack=1、绑定诅咒不可取"规则。C2S handler 必须逐类校验，否则可把任何物品塞进 armor/offhand 或复制。
5. **`getArmor`/`getItem(40)` 是 `Inventory` 的 compartments**：写 back 时用 `setItem(index, stack)`/`setItem(40, ...)`；armor 用 `setItem(36..39)` 或 `Inventory.setItem` 语义需确认（`Inventory.setItem` 对 armor 槽走 `armor.set`，`getArmor(0)` 对应 36）。**实现前需确认** `Inventory` 槽索引（0..35 主背包，36..39 armor，40 offhand）与 `getArmor`/`getItem` 的一致性，避免错位。
6. **`taskActive` 判定精度**：`isBusy` 只在有 `task != null` 时 true；若 bot 空闲但用户单击时恰好要开始任务，存在轻微竞态。可接受（服务端每次写前重查）。
7. **客户端 `@OnlyIn` 与 `bot-connection`**：Screen 类只由客户端事件/S2C 包引用，服务端不加载。发送用 `PacketDistributor.PLAYER.with(actor)`（真实玩家），绝不 `bot.connection.send`（no-op）。
8. **协议版本**：新增 packet 需 bump 或保持 `PROTOCOL_VERSION="1"` 但注意旧客户端/服务端不兼容；建议新增包独立，不重写已有 `Target/RoadPlan`。

## 5. 停止条件

- active plan 仍为 `PLANNING` 或用户未确认交互语义（允许哪些操作、是否含拖拽 cursor、是否允许装备穿戴）；
- 需要修改 `TransferRequest`/`ChestEndpointRef`/`CapacityPreflight`/`ChestBotTransferPrimitive`/`TransferLedgerData`/`TransferTask`/`BotManager.assignTransfer` 或任务链语义才能完成；
- 需要保留 `AbstractContainerMenu`/`MenuType`/`MenuScreens` 才能满足某交互（若用户坚持要完整拖拽光标或原版 shift-click inventory 语义，回到容器菜单修复方案，另立计划）；
- 客户端要完整拖拽光标/跨格交换且拒绝服务端 cursor 快照方案（此时工程与正确性风险高，评估是否转容器菜单修复或缩小）；
- `bot.getInventory()` 对 armor/offhand 的索引语义无法在固定 1.20.1 确认，需额外 source check；
- 需要独立客户端 source set 或客户端 mod 依赖（当前 `side=BOTH` + `@OnlyIn` 应可避免，若需则停止重新评估架构）；
- 影响既有 `MineTask`/`DropCollectionTask`/道路/隧道/流体/逃生/`FollowTask`/`HARD_PATH`/`TransferTask` 行为。

## 6. 客户端验收矩阵（Windows，独立 `CLIENT_TEST_PENDING`）

单轮总表单 + 场景证据包（`docs/SUPERVISION_PROTOCOL.md`）：`latest.log`、`debug.log`、`evidence-report.md`；场景以 是/否 记录 + 截图。预期日志关键字（建议）：
- 服务端：`bot_inv: command player= bot= slots=36+4+1 code=accepted`；`bot_inv: action player= bot= act= slot= code=accepted|task_active|invalid_slot|rejected`；`bot_inv: snapshot_sent player= bot= entries=N`。
- 写入码：`code=task_active`（只读保护命中）、`code=invalid_slot`、`code=armor_mismatch`、`code=cursed_binding`、`code=success`。

| 场景 | 观察点 | 预期日志/结果 | 失败回收条件 |
|---|---|---|---|
| G1 入口与授权 | 非 op 被拒；op 打开弹窗，面板显示 bot 36+4+1 | `bot_inv: command ... code=accepted`；面板 41 槽 | 非 op 能开；槽数非 41 |
| G2 快照显示 36 普通 | 36 格内容与服务器 bot inventory 一致（含堆叠数量） | `entries=41`；数量正确 | 数量/内容错乱 |
| G3 4 装备槽 + 1 offhand | armor 0..3 + offhand 显示正确 | 位置/角色正确 | 装备/offhand 错位 |
| G4 主手槽 | 主手 = 36 中 `selected`，与 bot 窗口一致 | 主手物品正确 | 主手显示错误 |
| G5 拿取单格（PICKUP） | 单击 bot 槽 → 物品移入玩家 或 光标；服务端回推后显示正确，**不消失不翻倍** | `act=PICKUP code=success`；快照更新 | 拿取消失/翻倍 |
| G6 放入（PLACE） | 把玩家物品放入 bot 空/同堆槽 → 堆叠正确 | `act=PLACE code=success`；数量对 | 数量错、吞物品 |
| G7 快速移动（QUICK_MOVE） | Shift+点击 bot 槽 → 移到合适玩家槽（或反之）；**不重复不残留** | `act=QUICK_MOVE code=success`；源槽清空 | Shift 错乱/重复/残留 |
| G8 装备穿戴/取下 | 把可穿装备放入 armor/offhand → 穿上；取下头盔等正常 | `code=success`；armor 槽内容正确 | 非装备能塞 armor/offhand |
| G9 只读保护 | bot 执行任务时（TransferTask/MineTask）点击 bot 槽 → 拒绝且 bot 槽不变 | `code=task_active`；快照未变 | 任务中被改写 |
| G10 bot 不在场 | bot 移除后请求返回 `bot_unavailable`/`invalid_bot` | `code=bot_unavailable`；无面板 | 空 bot 崩溃/显示陈旧 |
| G11 客户端/服务端无崩溃 | 打开/交互全程无 `NoClassDefFound`/`ClassNotFoundException` | 无异常栈 | 任何类加载/崩溃 |
| G12 服务端权威一致 | 打开面板前后 bot inventory 三端守恒（服务端 Read/Write 记录） | 无未解释 delta | 非服务端权威写 |
| G13 既有回归 | A1-A9 转移、equipment 渲染（ef43bd6）、死亡过滤、transfer/selector 场景无回退 | 无回归 | 既有 USER_ACCEPTED 回退 |

A1.1 既有矩阵（`docs/HANDOVER.md`）与本包独立；本包只验收"自定义 packet + Screen 的 Bot inventory 查看/拿取/放入/Shift"原语，不升级为泛化 C2/C3/C4，不接入普通任务链（只读保护除外），不改变 transfer ledger/primitive/Task 语义。

## 7. 交付状态

- 报告路径：`.alice-supervision/research/bot-inventory-gui-custom-packet-planner-20260824.md`
- 本任务只读：未修改业务代码、active plan、HANDOVER、客户端/审核记录；未派发任务；未运行测试。
- 当前 active plan `PLANNING`；本报告保持待监督采纳 + 用户批准后才可能进入实现。
- **本规划不构成实现授权。**
