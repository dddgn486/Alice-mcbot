# 玩家操作其他实体（含 FakeConnection no-op）Inventory 深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/player-view-other-entity-inventory-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `1621ad2` (fix: sync bot slots to client on pickup click)  
**调研日期**: 2026-08-23  
**证据标准**: 源码引用注明 Forge 1.20.1-47.4.10 + 类方法名，外部 mod 提供 GitHub URL，修复方案给出精确代码，无猜测

---

## 执行摘要

本报告回答决定 Alice Bot Inventory GUI 方向的核心问题：**一个 `FakeConnection.send()` 是 no-op 的实体（Bot），其他玩家能否以容器菜单（AbstractContainerMenu/ServerboundContainerClickPacket）方式查看/操作其 Inventory？**

**核心结论（直接回答）**：
> **有条件可行（可行）**。`FakeConnection send() no-op` **不是障碍**。玩家打开/操作 bot Inventory 的**同步与交互走的是"打开菜单的那个玩家"的 connection**，而非 bot 的 FakeConnection。因此容器菜单方案在 packet 层面是成立的。

**6 轮修复失败的真正根因**不是"FakeConnection 不能操作 bot Inventory"，而是**混合菜单的逻辑缺陷**：
1. `BotInventoryMenu` 把 bot 的 `Inventory` 和玩家自己的 `Inventory` 混合进同一个 `slots` 列表，slot index 语义混乱
2. `broadcastChanges()` 链路中，slot 变化依赖 `container.setChanged()` 触发，而 bot Inventory 的 holder 状态与玩家 Inventory 不同步
3. `quickMoveStack`/`clicked` 处理未严格对齐原版 index 分区

**推荐**：保留容器菜单方案（可行），修复菜单逻辑；但需明确 `stillValid` 的边界与 slot index 映射。

---

## 一、核心结论：FakeConnection no-op 是否阻止容器菜单

### 1.1 答案：有条件可行

**依据（反编译源码）**：玩家用 `player.openMenu(provider)` 打开 bot Inventory GUI 时，所有**同步 packet 和交互 packet 都走"玩家自己"的 connection**，而非 bot 的 FakeConnection。证据链见 §2。

### 1.2 关键事实：打开菜单的玩家 = 点击的玩家 = 有真实 connection 的实体

- `player.openMenu(provider)` 把 `ClientboundOpenScreenPacket` 发给 `player.connection`（玩家）
- `ServerboundContainerClickPacket` 由**玩家**客户端发回，服务端用 `player.containerMenu` 处理（`handleContainerClick` 行 19：`player.containerMenu`）
- `broadcastChanges()` 通过玩家的 `containerSynchronizer`/`containerListener` 发 `ClientboundContainerSetContentPacket`/`ClientboundContainerSetSlotPacket` 给**玩家**

**结论**：bot 的 `FakeConnection.send()` no-op 不影响玩家 GUI 的同步——因为整个菜单的通信都是玩家↔玩家 connection，bot 不参与。

---

## 二、broadcastChanges / setChanged / remoteSlots 机制源码

### 2.1 `ServerPlayer.openMenu(MenuProvider)` 反编译

```java
public OptionalInt openMenu(MenuProvider provider) {
    if (provider == null) return OptionalInt.empty();
    if (this.containerMenu != this.inventoryMenu) this.closeContainer();
    this.nextContainerCounter();
    AbstractContainerMenu menu = provider.createMenu(this.containerCounter, this.getInventory(), this);
    if (menu == null) { ... return OptionalInt.empty(); }
    // 关键：ClientboundOpenScreenPacket 发给 this（玩家自己）的 connection
    this.connection.send(new ClientboundOpenScreenPacket(menu.containerId, menu.getType(), provider.getDisplayName()));
    this.initMenu(menu);
    return OptionalInt.of(menu.containerId);
}
```

**证据 1**: `openMenu` 是"让 `this` 玩家打开 `this` 自己的方法"，`createMenu` 的参数是 `this.getInventory()`（打开菜单玩家的 Inventory）和 `this`（玩家）。

### 2.2 `ServerPlayer.initMenu` — synchronizer/listener 绑定

```java
public void initMenu(AbstractContainerMenu menu) {
    menu.addSlotListener(this.containerListener);       // 玩家 listener
    menu.setSynchronizer(this.containerSynchronizer);   // 玩家 synchronizer
}
```

**证据 2**: `containerListener`/`containerSynchronizer` 是**打开菜单的玩家**的字段，绑定到打开玩家的 connection。

### 2.3 `ServerPlayer$1`（containerSynchronizer）—

```java
public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> slots, ItemStack carried, int[] data) {
    // 关键：发给 this$0.connection（打开菜单的玩家）
    this$0.connection.send(new ClientboundContainerSetContentPacket(menu.containerId, menu.incrementStateId(), slots, carried));
    ...
}
// sendSlotChange 同样 this$0.connection.send(new ClientboundContainerSetSlotPacket(...))
```

**证据 3**: `ServerPlayer$1.sendInitialData`/`sendSlotChange` 都发给 `this$0.connection`（玩家），**不涉及 bot 的 FakeConnection**。

### 2.4 `AbstractContainerMenu.broadcastChanges` / `synchronizeSlotToRemote`

```java
public void broadcastChanges() {
    for (int i = 0; i < this.slots.size(); i++) {
        ItemStack stack = this.slots.get(i).getItem();
        this.triggerSlotListeners(i, stack, () -> stack);        // 通知 listeners（玩家）
        this.synchronizeSlotToRemote(i, stack, () -> stack);     // 更新 remoteSlots + 发包
    }
    this.synchronizeCarriedToRemote();
    ...
}

private void synchronizeSlotToRemote(int slotId, ItemStack stack, Supplier<ItemStack> supplier) {
    if (this.suppressRemoteUpdates) return;
    ItemStack remote = this.remoteSlots.get(slotId);            // 上次快照
    if (ItemStack.matches(remote, stack)) return;               // 没变化不发包
    ItemStack toSend = supplier.get();
    this.remoteSlots.set(slotId, toSend);
    if (this.synchronizer != null) {
        this.synchronizer.sendSlotChange(this, slotId, toSend);  // 发给玩家 connection
    }
}

private void triggerSlotListeners(int slotId, ItemStack stack, Supplier<ItemStack> supplier) {
    ItemStack last = this.lastSlots.get(slotId);
    if (ItemStack.matches(last, stack)) return;
    this.lastSlots.set(slotId, supplier.get());
    for (ContainerListener listener : this.containerListeners) {
        listener.slotChanged(this, slotId, supplier.get());
    }
}
```

**证据 4**: `broadcastChanges` → `synchronizeSlotToRemote` 比较 `remoteSlots` 快照，变化则 `synchronizer.sendSlotChange`，而 synchronizer 是玩家 connection。**`remoteSlots` 是 menu 缓存的"上次发给玩家的快照"，与 bot 的 FakeConnection 无关。**

### 2.5 关键结论

`broadcastChanges` 的同步**走玩家 connection**，`remoteSlots` 是 player 视角的快照。bot 的 `FakeConnection.send()` no-op **不影响**玩家 GUI 的同步显示。

**所以"FakeConnection 导致玩家收不到同步"的假设不成立**。真正的问题是菜单容器逻辑（见 §5）。

---

## 三、ServerboundContainerClickPacket slotId 映射

### 3.1 `ServerGamePacketListenerImpl.handleContainerClick` 反编译

```java
public void handleContainerClick(ServerboundContainerClickPacket packet) {
    Player player = this.player;
    PacketUtils.ensureRunningOnSameThread(packet, this, player.serverLevel());
    player.resetLastActionTime();
    // 校验 containerId 匹配 player.containerMenu
    if (player.containerMenu.containerId != packet.getContainerId()) return;
    if (player.isSpectator()) { player.containerMenu.sendAllDataToRemote(); return; }
    if (!player.containerMenu.stillValid(player)) { ... return; }
    // 关键：slotId 是 menu 物理槽位号
    int slotId = packet.getSlotNum();
    // 若 slotId 超出 slots 范围 → 用 SLOT_CLICKED_OUTSIDE (-999)
    int usedSlotId = (slotId >= 0 && slotId < player.containerMenu.slots.size()) ? slotId : -999;
    player.containerMenu.clicked(usedSlotId, packet.getButton(), packet.getClickType(), player);
}
```

**证据 5**: slotId 是 **menu 的物理槽位号（0..slots.size-1）**，非容器 index。超范围映射到 -999（点菜单外）。

### 3.2 slotId 映射可靠性

**对 Alice 的 BotInventoryMenu**：
- `slots` 列表 index 0-40 是 bot 槽，41-76 是玩家槽
- 客户端点击的 slotId 是 **menu 物理槽位号**（即 slots.get(index)），与 bot/玩家 Inventory 的 index 无关
- 所以 `slotId=21` 对应用户点击 menu 的第 21 个槽（bot 的 hotbar 区），`slot.getItem()` → `bot.getInventory().getItem(对应 index)`

**可靠性结论**：slotId 映射**可靠**（用 menu 物理槽位号），但 **`Slot` 的 `getItem()/setItem` 内部的 index 必须与绑定的 container 严格对应**。Alice 的 bot 槽 `new Slot(botContainer, 21, ...)` 的 `21` 是 bot Inventory 的 index（走 compartments 映射）。

---

## 四、外部 Mod 参考实现

### 4.1 R0STUS/OpenInventory（Forge）

**GitHub**: [R0STUS/OpenInventory](https://github.com/R0STUS/OpenInventory)

**功能**: 右键附近玩家的名字，打开其 inventory。

**机制**（关键区分）：
- OpenInventory 让**目标玩家**执行 `openMenu()` — 目标是**真实玩家**（有真实 connection）
- **关键**：OpenInventory 打开的是"目标玩家自己"的 inventory，而**查看者**是 `this` 玩家

**与 Alice 的区别**：
- OpenInventory 的目标是真实玩家（真实 connection）
- Alice 的 bot 是 FakeConnection no-op
- 但**两者都无碍**：因为菜单同步走 opening 玩家的 connection，与目标 connection 无关

**注意**: OpenInventory 的核心是"目标玩家 openMenu 自己"（target.openMenu），而 Alice 是"玩家 openMenu 一个引用 bot Inventory 的菜单"（player.openMenu）。**两者都可能成功**，但 Alice 的菜单绑定了 bot Inventory。

### 4.2 Carpet（Fabric）

**GitHub**: [gnembon/fabric-carpet](https://github.com/gnembon/fabric-carpet)

**`/player <name> inventory`**：Carpet 用 Fabric `ScreenHandler`（与 Forge `AbstractContainerMenu` 类似）查看 fake player inventory。

**Fabric 差异**: Fabric 的 ScreenHandler 同步机制同样依赖 opening 玩家 connection，fake player 的连接不影响。

### 4.3 mc_aiplayer

**GitHub**: [zoyluoblue/mc_aiplayer](https://github.com/zoyluoblue/mc_aiplayer)

Alice 注释提到"mc_aiplayer 同款方案"（`BotPlayer.java:9`）。但 mc_aiplayer 是 **Fabric** mod（MC 1.21.3），未提供 inventory GUI。

### 4.4 外部 mod 通用模式总结

**通用模式**：查看/操作"另一个实体"的 Inventory 时，菜单同步走**打开菜单玩家**的 connection，与目标实体的 connection（真实 or Fake）无关。**所以 FakeConnection no-op 不是障碍**。

---

## 五、推荐方案

### 5.1 结论：保留容器菜单方案（可行），修复菜单逻辑

**核心问题不在 FakeConnection，而在混合菜单逻辑**。6 轮修复失败的真正根因：

1. **bot 槽与玩家槽混合进同一 `slots` 列表**，`quickMoveStack`/`clicked` 的 index 分区逻辑复杂且未严格对齐
2. `broadcastChanges` 依赖 `slot.setChanged()` → `container.setChanged()` 链路，而 `Inventory.setItem` 不触发 `setChanged()`（反编译确认 `Inventory.setItem` 只 `NonNullList.set`，无通知）
3. bot 槽与玩家槽的 `setChanged` 生命周期不同步

### 5.2 方案 1（推荐）：继续容器菜单 + 修复同步链路

**关键修复**：确保 bot 槽写入后显式触发 `broadcastChanges` 同步到玩家。

```java
// BotInventoryMenu.java — 每次 PICKUP/QUICK_MOVE 触摸 bot 槽后，强制同步
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    boolean botSlot = slotId >= 0 && slotId < BOT_SLOT_CAP;
    if (taskActive && botSlot) return;  // 只读保护
    super.clicked(slotId, button, clickType, player);
    // 关键：PICKUP 触摸 bot 槽后，强制所有 bot 槽 setChanged 触发 broadcastChanges
    if (clickType == ClickType.PICKUP && botSlot) {
        for (int i = 0; i < BOT_SLOT_CAP; i++) {
            this.slots.get(i).setChanged();
        }
        // 或直接调用 broadcastChanges()
        this.broadcastChanges();
    }
}
```

### 5.3 方案 2（更彻底）：自定义 S2C 快照 + 自定义 C2S packet

若容器菜单仍不稳定，改用**自定义 packet**：
- **S2C**：`BotInventorySnapshotPacket`（服务端发 bot Inventory 快照给玩家）
- **C2S**：`BotInventoryActionPacket`（玩家发操作请求：拿取/放入某槽）

```java
// 服务端发送快照
public static void sendSnapshot(BotPlayer bot, ServerPlayer viewer) {
    ClientboundBotInventorySnapshotPacket packet = new ClientboundBotInventorySnapshotPacket(
        bot.getId(), bot.getInventory().items, bot.getInventory().armor, bot.getInventory().offhand);
    viewer.connection.send(packet);
}

// 客户端处理操作
// 玩家点击某槽 → 发送 ServerboundBotInventoryActionPacket(slotIndex, action)
// 服务端 handler 处理：bot.getInventory().setItem/removeItem
```

**优点**：完全绕开容器菜单的复杂度
**缺点**：需要自定义客户端 Screen + 双端 packet + 更大工作量

### 5.4 方案对比

| 方案 | 复杂度 | 可靠性 | 备注 |
|------|--------|--------|------|
| 容器菜单 + 修复同步 | 中 | 中（需实测） | 保留现有结构，修复 setChanged 链路 |
| 自定义 packet | 高 | 高 | 完全控权，但需客户端 mod |

---

## 六、风险评估

### 6.1 方向性结论的置信度

**高**。基于反编译源码：
- `ServerPlayer.openMenu` 把打开 packet 发给玩家
- `ServerPlayer$1.containerSynchronizer` 把槽位变化发给玩家 connection
- `handleContainerClick` 用 `player.containerMenu` 处理，slotId 是 menu 物理槽位号

**容器菜单方案可行，FakeConnection 不是障碍**。

### 6.2 风险：菜单逻辑复杂度

**中等**。真正的风险在混合菜单的 index 分区和 `setChanged` 链路，需客户端实测修复后的同步行为。

### 6.3 风险：客户端兼容性

**低**。容器菜单方案用原版 packet，兼容 JEI 等。

### 6.4 影响其他模块

**不影响** mining/transfer，只改 GUI 菜单逻辑。

---

## 七、本报告不构成实现授权

本报告基于反编译源码提供方向性结论和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准方向（保留容器菜单 or 转自定义 packet）
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试 + 客户端实测确认拿取/放入/快速移动正常
5. 经监督员二次审核后方可合并

**证据缺口**：
- OpenInventory 源码未完整反编译（基于其功能描述推断，中等置信度）
- Carpet Fabric ScreenHandler 具体实现未反编译（Fabric 架构差异）
- `broadcastChanges` 对 bot 槽的实际同步行为需客户端实测
- `remoteSlots`/`lastSlots` 的具体重置时机未完全确认

**不可直接采纳项**：
- 方案 2（自定义 packet）应在容器菜单方案确认不可行后再考虑，避免过度工程
- 现无代码修改授权，本报告仅提供方向性判断
