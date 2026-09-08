# TLM「车万女仆」Inventory GUI 专项深度调研报告

**调研员**：Alice 深度调查员  
**任务**：`tlm-maid-inventory-gui-research-task.txt`  
**Alice 基线**：`/home/fb486/projects/alice`，commit `9534a5d`（方向 B 自定义 S2C 快照 + C2S action + Screen；客户端第 7 轮未通过）  
**调研日期**：2026-08-24  
**TLM 项目**：`TartaricAcid/TouhouLittleMaid`，官方仓库 [GitHub](https://github.com/TartaricAcid/TouhouLittleMaid)  
**固定源码边界**：官方 `1.20` 分支 commit `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6`  
**TLM 版本边界**：该 commit 的 `gradle.properties` 明确为 `mod_version=1.5.3-forge+mc1.20.1`、`mc_version=1.20.1`、`forge_version=1.20.1-47.2.0`；`build.gradle` 使用 ForgeGradle、Parchment `2023.08.20-1.20.1`。因此本报告引用的是 **TLM 1.5.3-forge+mc1.20.1 / Forge 47.2.0**，不是 Alice 使用的 Forge 47.4.10。

## 1. 核心结论

### 1.1 TLM 的实际架构

TLM 的女仆库存 GUI **不是纯自定义 Screen + action packet**。固定提交中的主库存路径是：

```text
玩家拥有的女仆
  -> 右键实体 / OpenMaidGuiMessage C2S
  -> EntityMaid.openMaidGui(player, tab)
  -> NetworkHooks.openScreen(serverPlayer, MenuProvider, entityId)
  -> MaidMainContainer extends AbstractMaidContainer extends AbstractContainerMenu
  -> SlotItemHandler 绑定女仆 Forge capability IItemHandler
  -> TLM 自定义 AbstractContainerScreen 负责纹理、按钮和附加显示
```

源码证据：

- `entity/passive/EntityMaid.java:657-674`：`mobInteract` 仅允许拥有者在主手交互；事件/物品交互未消费时调用 `openMaidGui`。
- `entity/passive/EntityMaid.java:1506-1527`：服务端 `openMaidGui` 停止导航，按 tab 创建 `MenuProvider`，用 `NetworkHooks.openScreen` 打开并把实体 ID 写入 buffer。
- `inventory/container/AbstractMaidContainer.java:12-61`：菜单从 `entityId` 解析女仆，加入查看者玩家背包；`stillValid` 检查女仆存在、所有权、存活、非睡眠、距离。
- `inventory/container/MaidMainContainer.java:29-150`：菜单槽位绑定女仆的 capability handler，而不是把女仆当作拥有真实网络连接的玩家。
- `network/message/OpenMaidGuiMessage.java:16-58`：C2S 消息只传 `entityId` 和 `tabId`；服务端根据发送者世界中的实体 ID 查找 `EntityMaid`，再执行 `stillValid` 后打开菜单。

### 1.2 对 Alice 当前问题的直接结论

TLM 给出的最有价值结论不是“改用自定义 packet”，而是：**实体库存可以稳定地接入原版 `AbstractContainerMenu`，前提是把目标实体解析、库存 view、槽位分区、装备规则和权限验证做成菜单自己的明确契约。**

TLM 不使用 FakeConnection 目标作为通信端点；打开菜单和同步仍属于查看者玩家的真实连接。它把实体库存通过 capability `IItemHandler`/`SlotItemHandler` 接入菜单。因而：

- 这支持 Alice 方案 A 作为兼容性路线的可行性，但不等于应立即回档。
- 这不能直接证明 Alice 方向 B 的 `slot_empty` 是网络连接问题；TLM 的方案根本绕过了自定义快照的 view/action 一致性问题。
- TLM 的成熟点是**服务端生成统一菜单、固定物理槽位、从服务器真实 handler 读写**，不是屏幕上直接用实体内部索引猜测操作。

## 2. TLM GUI 入口与功能

### 2.1 打开入口

普通入口是拥有者对女仆实体主手右键：`EntityMaid.mobInteract`：

```java
if (hand == InteractionHand.MAIN_HAND && isOwnedBy(playerIn)) {
    ...
    if (MinecraftForge.EVENT_BUS.post(event)
        || stack.interactLivingEntity(playerIn, this, hand).consumesAction()
        || openMaidGui(playerIn)) {
        return InteractionResult.SUCCESS;
    }
}
```

`OpenMaidGuiMessage` 是另一个入口：客户端/其他 TLM 逻辑发送实体 ID + tab，服务端重新查实体并检查 `maid.isOwnedBy(player) && !maid.isSleeping() && maid.isAlive() && maid.distanceTo(player) < 5.0F`，通过后调用 `maid.openMaidGui`。

TLM 的库存不是由目标实体的连接发送。`NetworkHooks.openScreen` 创建的菜单和屏幕同步属于查看者 `ServerPlayer`。

### 2.2 普通点击、放入、Shift

主库存菜单继承原版 `AbstractContainerMenu.clicked`，所以普通 PICKUP、放置、堆叠合并、交换等由原版菜单处理。TLM 未在固定主菜单源码中自定义 `clicked` 或自定义客户端预测。

`MaidMainContainer.quickMoveStack` 是服务端的 Shift 处理：

- `index < PLAYER_INVENTORY_SIZE` 时，把女仆/菜单前段物品移到玩家背包槽区。
- 否则把玩家背包物品移到女仆槽区。
- `moveItemStackTo` 返回 false 时拒绝本次转移。
- 处理后根据剩余数量调用 `slot.setByPlayer(EMPTY)` 或 `slot.setChanged()`，数量未变化则返回空。
- 最后调用 `slot.onTake`，并对实现 `ITriggerSlotChange` 的槽触发 Shift 取出事件。

源码证据：`inventory/container/MaidMainContainer.java:113-166`。

### 2.3 装备与副手

`MaidMainContainer` 通过 capability 绑定：

- `addMaidArmorInv`：从女仆 `Direction.EAST` 获取 armor `IItemHandler`，建立 4 个 `SlotItemHandler`。
- `addMaidHandInv`：从女仆 `Direction.DOWN` 获取 hands handler，建立主手/副手两个槽。
- `addMainDefaultInv`：默认女仆背包 6 格；具体背包实现再扩展更多格。

装备槽规则：

```java
getMaxStackSize() == 1;
mayPlace(stack) == stack.canEquip(equipmentSlot, maid)
                      && stack.getItem().canFitInsideContainerItems();
mayPickup(player) == 非创造模式时不允许拿出带绑定诅咒的装备
```

副手通过 `MaidHandsInvWrapper` 覆盖 `isItemValid`，调用 `EntityMaid.canInsertItem(stack)`；这不是客户端判断，而是服务器端 handler/slot 的规则。

### 2.4 女仆工作期间的保护

TLM 不以“禁止打开菜单”保护工作，而是打开 GUI 时暂停女仆 AI：

- `AbstractMaidContainer` 构造成功解析到女仆后设置 `maid.guiOpening = true`。
- `AbstractMaidContainer.removed` 设置 `maid.guiOpening = false`。
- `EntityMaid.customServerAiStep` 只有在 `!guiOpening` 时才调用 `maidBrain.tick(...)`。
- `EntityMaid.openMaidGui` 打开前调用 `navigation.stop()`。

这意味着 TLM 采用的是**GUI 生命周期锁定 AI 并发修改**，不是只读 UI。固定源码没有显示一个通用 `isBusy` 拒绝所有库存操作的规则；睡眠、死亡、距离、所有权由菜单 `stillValid` 处理。

## 3. 槽位视图与同步架构

### 3.1 菜单槽位是统一 view index

TLM 的菜单把不同来源库存按固定菜单顺序放入 `slots`：

1. `AbstractMaidContainer.addPlayerInv`：玩家背包 27 格 + 快捷栏 9 格，放在菜单尾部。
2. `MaidMainContainer` 前段依次为女仆护甲、主手/副手、默认/扩展背包。
3. 每个槽的菜单 index 是 `slots` 列表的物理 index；底层 `IItemHandler` index 只存在于对应 `SlotItemHandler` 内部。
4. `quickMoveStack` 只按菜单物理 index 和明确的 `PLAYER_INVENTORY_SIZE` 分区，未把实体内部 inventory index 当作菜单 index。

这正是 Alice 当前需要借鉴的 view-model 原则：**自定义 Screen 也应拥有一个固定 view index -> 服务端槽位语义表，而不是默认把屏幕 index 当作实体 inventory index。**

### 3.2 数据权威与客户端预测

TLM 主库存使用原版菜单同步：服务端 `SlotItemHandler`/handler 是权威；客户端 `AbstractMaidContainerGui` 负责绘制背景、女仆预览、按钮和 tooltip，物品槽本身由 `AbstractContainerScreen` 处理。

固定源码中未发现客户端直接修改女仆槽或乐观预测后等待确认的代码。TLM 也未在主库存路径中使用 revision/nonce/transaction id。原版菜单的 container/state/synchronizer 机制承担同步。

因此可确认：

- **确认事实**：服务端菜单操作、服务端 handler 校验、原版菜单回推。
- **未确认事项**：本次固定提交没有独立的 TLM 主库存 snapshot/revision/失败 packet；不能声称 TLM 存在自定义库存失败回推协议。

## 4. 服务端权威与并发

### 4.1 打开前校验

`OpenMaidGuiMessage.handle`：

```java
Entity entity = player.level.getEntity(message.entityId);
if (entity instanceof EntityMaid maid && stillValid(player, maid)) {
    maid.openMaidGui(player, message.tabId);
}
```

`stillValid`：

```java
return maid.isOwnedBy(playerIn)
    && !maid.isSleeping()
    && maid.isAlive()
    && maid.distanceTo(playerIn) < 5.0F;
```

菜单再次以 `AbstractMaidContainer.stillValid` 防止后续点击：

```java
return maid != null
    && maid.isOwnedBy(playerIn)
    && maid.isAlive()
    && !maid.isSleeping()
    && playerIn.canReach(maid, 3);
```

这形成了实体存在、所有权、存活、状态和距离的双层校验。

### 4.2 槽位/堆叠/装备校验

- 普通移动由原版 `Slot`、`moveItemStackTo`、`ItemStack` 堆叠规则处理。
- 装备槽 `getMaxStackSize=1`、`canEquip` 和 `canFitInsideContainerItems` 限制放入。
- 绑定诅咒限制非创造模式取出。
- 副手 `MaidHandsInvWrapper.isItemValid` 通过 `EntityMaid.canInsertItem` 限制物品。
- TLM backpack/bauble 通过 Forge `IItemHandler` 和 capability wrapper 作为数据权威。

### 4.3 并发、失败和 revision

- GUI 打开期间 `guiOpening=true`，`EntityMaid.customServerAiStep` 跳过 AI tick；这是 TLM 对女仆工作与 GUI 操作并发的主要处理。
- 女仆死亡/睡眠/离开距离/所有权失效时，菜单 `stillValid` 使后续点击无效。
- 源码未发现主库存 action nonce、revision、transaction id 或自定义失败回推消息。
- 原版菜单协议在无效点击时由 vanilla menu/server menu 生命周期处理；固定源码没有足够证据说明 TLM 对每种失败发专用错误文本或重新请求完整快照。

**证据边界**：不能把 TLM 的原版 container stateId 机制误写成 TLM 自己设计的 revision 协议；也不能把 `MaidBackpackChangeEvent` 当作客户端失败回推。该事件只是在背包物品装上/取下时给服务端事件总线的业务钩子。

## 5. Alice 方向 B vs 方案 A vs TLM 对照

| 维度 | Alice 方向 B | Alice 方案 A | TLM 实现 | 可迁移性 |
|---|---|---|---|---|
| GUI 打开入口 | 服务端发 `BotInventoryPacket`，客户端 `setScreen` | `player.openMenu`/`AbstractContainerMenu` | 右键或 `OpenMaidGuiMessage` C2S；服务端 `NetworkHooks.openScreen(MenuProvider)` | 入口校验可迁移；TLM 的 Forge MenuProvider 模式可供方案 A 参考 |
| 数据权威 | Bot 服务端 inventory；屏幕只读快照 | Bot/玩家两个 `Container` 绑定菜单 | 女仆 capability `IItemHandler` + 服务端 `SlotItemHandler` | 服务端权威、handler/adapter 可迁移；TLM capability 不能直接复制 |
| 槽位 view index | 当前屏幕 `0..40`，action 直接带 index；已出现 `slot_empty` | 菜单 `slots` 物理 index；bot/player 分区 | 菜单物理 index；实体 handler index 由 SlotItemHandler 内部映射 | TLM 的固定 view index 表和显式分区可直接迁移到 B；A 应保持物理 menu index |
| 普通点击 | 自定义 `mouseClicked` 发 `PICKUP` action；无客户端预测，等待快照 | vanilla `AbstractContainerMenu.clicked` | vanilla `clicked` | “不本地修改、服务端回推”可迁移；B 需补全操作语义 |
| Shift | Screen 检测 Shift 后发 `QUICK_MOVE` action | 自定义 `quickMoveStack` | `MaidMainContainer.quickMoveStack` 明确分区并调用 `moveItemStackTo` | TLM 的分区算法原则可迁移；不能直接复制其固定数量 |
| 装备/副手 | 当前 B 使用统一 41 index，规则需服务端实现 | `BotArmorSlot`/`BotOffhandSlot` 规则可复用 | `SlotItemHandler` + `canEquip`/诅咒/handler valid | 装备资格与最大堆叠规则可适配；TLM capability 结构是专属 |
| 任务中保护 | 设计为服务端 `BotManager.isBusy` 拒绝 action | `taskActive` 槽位 guard | `guiOpening` 暂停 AI，不是通用 busy 拒绝 | “打开期间冻结竞争写入”可迁移为设计原则；具体 Alice 任务锁需适配 |
| C2S/S2C 同步 | 自定义 S2C 快照 + C2S action；服务端回推 | vanilla container packets | NetworkHooks + vanilla container synchronizer/state | 方向 B 可借鉴 TLM 的 request/authority/refresh；不能宣称 TLM 是 custom packet 实现 |
| 并发/修订号 | 当前日志显示 action 到达但 `slot_empty`；需防陈旧 index/并发 | vanilla menu stateId/slot 快照 | `guiOpening` 暂停 AI；未发现 TLM 自定义 nonce/revision | B 应增加 snapshot revision/失败回推；TLM 没有可直接迁移的 nonce |
| 失败回推 | 当前需明确 `slot_empty`、busy、invalid 等结果并刷新 | vanilla menu 重新同步 | 未发现主库存专用失败 packet；stillValid/菜单生命周期拒绝 | “失败原因 + authoritative snapshot”是 Alice 需要新增的强化点，不是 TLM 已证实模式 |

## 6. 迁移边界

### 6.1 直接可迁移的模式

1. **固定 view index**：建立 `ViewSlot` 表，显式记录 `viewIndex`、逻辑区域、底层 slot、装备槽和可移动规则；屏幕只使用 view index，服务端再映射。
2. **双层权限/有效性校验**：打开 action 和每次操作都重新校验 bot 存在、玩家权限/所有权、距离、存活和当前任务状态。
3. **服务端权威、客户端不预测**：点击只发送意图，服务端成功后发 authoritative snapshot；失败也发明确结果和新 snapshot。
4. **统一的 Shift 分区**：先定义 bot 区、玩家区、可转移方向，再调用已有堆叠合并逻辑；不使用“当前数组前 N 格”的隐式假设。
5. **装备槽独立规则**：最大堆叠、`canEquip`、绑定诅咒、主手/副手资格必须在服务端单独校验。
6. **GUI 生命周期竞争控制**：若 Alice 任务系统会同时写 bot inventory，打开 GUI 时使用明确的读写锁/任务保护；不能只在客户端显示锁定。

### 6.2 需要适配 Forge 1.20.1 / Alice BotPlayer 的模式

- TLM 使用 `EntityMaid` capability；Alice 的 BotPlayer 当前是 `ServerPlayer` + FakeConnection，不能把 TLM 的 `EntityMaid.getCapability(ITEM_HANDLER, direction)` 直接复制。
- TLM 的 `NetworkHooks.openScreen` 与 `MenuProvider` 可用于方案 A 兼容路线；若保留 B，则应实现自己的 server-side bot registry 和 viewer authorization。
- TLM 的距离/所有权 API 是 `EntityMaid.isOwnedBy`、`canReach`；Alice 需要按 BotManager 的 bot ownership/任务规则适配。
- TLM 的 6 格/背包类型是动态结构；Alice 需固定 41 槽 view 或显式传送 view schema，不能照搬其菜单索引常量。

### 6.3 TLM 专属耦合，不应直接迁移

- `EntityMaid.guiOpening` 与 MaidBrain/navigation 的耦合；Alice 不能未经计划把 BotPlayer 的 AI/任务循环全部暂停。
- `MaidBackpackHandler`、`MaidHandsInvWrapper`、`CombinedInvWrapper`、Curios/bauble capability；这些是 TLM 的实体模型和扩展生态。
- `MaidBackpackChangeEvent`、`MaidEquipEvent` 等 TLM 事件；Alice 应使用自身任务/装备事件边界。
- TLM 对女仆所有权、睡眠和 tame 状态的判定；不能把它们当作 Alice Bot 的权限模型。

### 6.4 不能解决 Alice 当前问题的表面相似做法

- **只把 B 的屏幕索引改成 TLM 的内部 inventory index**：TLM 的稳定性来自显式菜单槽位与 handler 映射，单纯换数字不能解决 `slot_empty`。
- **只增加一次 S2C 快照**：没有 action 前的 revision/expected stack 校验时，陈旧客户端仍可请求错误槽。
- **只复用 `guiOpening` 布尔值**：若任务线程/AI 仍能绕过该标志写库存，仍会产生竞争。
- **只复制 TLM 的 `quickMoveStack` 数字范围**：TLM 的范围依赖其菜单布局（女仆槽在前、玩家槽在后）；Alice B 没有 vanilla menu 的 `moveItemStackTo` 语义。
- **把 TLM 的原版菜单误判成自定义 packet 先例**：TLM 证据明确是 `AbstractContainerMenu` + `SlotItemHandler`；它不能直接证明 B 的自定义 action 协议设计正确。

## 7. Alice 下一步最小研究建议（不构成实现授权）

1. 保留方案 A 兼容路线，不回档当前方向 B。
2. 对 B 先定义并记录一个**唯一快照 schema**：`botId`、`snapshotRevision`、41 个 view slots、每个 view slot 的逻辑区域/底层 index、任务锁状态。
3. 每个 C2S action 带 `botId + viewIndex + expectedRevision + expectedStackFingerprint + actionType + button/modifier`；服务端在同一 tick 主线程校验后执行。
4. 成功与失败都回发 `ActionResult`（例如 `OK`、`SLOT_EMPTY`、`STALE_REVISION`、`BUSY`、`INVALID_SLOT`、`NOT_ALLOWED`）和新的 authoritative snapshot。
5. 以 TLM 的装备规则为参考，先把普通槽 PICKUP、普通槽 PUT、Shift 转移、护甲/副手四类分别定义，不把它们混在一个模糊 `PICKUP` action 中。
6. 对方案 A 仅保留 TLM 的可迁移架构研究：目标实体提供菜单、菜单持有明确 view、底层使用服务端 Container/handler；不回档、不复制 TLM 代码。

## 8. 证据缺口与客户端边界

- 已固定并读取官方 TLM 源码 commit；版本边界为 Forge 1.20.1-47.2.0，不是 Alice 的 Forge 47.4.10。
- 固定提交中未找到主库存自定义 snapshot/revision/nonce 或专用失败回推，因此这部分不能归因于 TLM。
- 本报告未运行 TLM 或 Alice 客户端，不能代替 Windows 客户端验收；TLM 的视觉拖拽、鼠标 hover、客户端屏幕动画仍需客户端实测才能确认。
- TLM 源码访问使用官方 GitHub API 固定 commit；DeepWiki 仅作为发现线索，未将其摘要当作源码事实。可参考 [TLM Container GUI System](https://deepwiki.com/TartaricAcid/TouhouLittleMaid/5.1-container-gui-system) 和 [TLM Network Protocol](https://deepwiki.com/TartaricAcid/TouhouLittleMaid/12-developer-guide)，但最终结论以固定 commit 源码为准。

## 9. 结论

TLM 的可迁移价值是一个清晰的**服务端实体菜单契约**：目标实体提供 `MenuProvider`，菜单通过 capability/adapter 建立稳定槽位视图，玩家槽区显式分隔，装备规则在 `Slot`/handler 服务端执行，打开和每次点击都做有效性校验，GUI 生命周期处理 AI 并发。

TLM **不是** Alice 方向 B 的自定义 packet 先例，也没有证据表明它使用 nonce/revision 或自定义失败回推。Alice 当前 B 的 `slot_empty` 应优先按“屏幕 view index、快照 revision、服务端映射和返回原因”调查；不能用单纯复制 TLM 的 `AbstractContainerMenu` 数字布局代替协议修复。

**本报告不构成实现授权**
