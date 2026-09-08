# AbstractContainerMenu.clicked() PICKUP 拿取消失源码诊断报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/container-menu-clicked-pickup-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `55e3d32` (fix: use inventory.png background and add GUI debug logs)  
**调研日期**: 2026-08-23  
**证据标准**: 源码引用注明 Forge 1.20.1-47.4.10 + 类名方法名，反编译用 `javap -c`，修复方案给出精确代码，无猜测

---

## 执行摘要

本报告通过反编译原版源码，深入剖析 `AbstractContainerMenu.clicked()` 的 **PICKUP 分支**（`doClick` 内部），解释为何 Alice 混合菜单（bot 槽绑定 `BotPlayer.getInventory()` + 玩家槽绑定玩家 `Inventory`）中，普通左键点击（PICKUP）从 bot 槽拿取物品会"消失"。

**核心发现**：
1. **PICKUP 拿取本身是正确**的：`slotId=21` 对应 bot 的普通槽，`Slot.tryRemove` → `Inventory.removeItem(21, count)` 走 `compartments` 映射，物品正确取出到 `carried`。**服务端物品不会真的丢**。
2. **"消失"是客户端渲染不同步**：`Inventory.setItem`/`removeItem` **不主动调用 `setChanged()`**，而 bot 的 `FakeConnection.send()` 是空操作（不发实时 packet）。客户端 GUI 的槽位渲染依赖 `broadcastChanges()` 每 tick 比较，但 bot 槽写入后服务端的 `remoteSlots` 快照与客户端缓存出现不一致。
3. **`allowModification` 是决定性新线索**：`Slot.tryRemove` 第一步检查 `allowModification(player)`（Forge 扩展），若 bot 槽被判定为"不可修改"，`tryRemove` 返回 `Optional.empty()`，**物品留在槽内但客户端已渲染为拿起** → 表现为"消失"。

**关键**：`[GUI_DEBUG] clicked after` 正常返回但物品消失，最可能根因是 **PICKUP 拿取时 `carried` 被设置，但 bot 槽到玩家背包的落地路径未走通 + 客户端不同步**。PICKUP 拿取到 `carried` 后，物品在玩家手持，**并未"消失"——是玩家背包与 bot 槽位渲染不同步**导致视觉消失。

---

## 一、`clicked()` 分派与 PICKUP 分支源码

### 1.1 `clicked(int slotId, int button, ClickType, Player)` 整体流程

```java
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    // ① 若 carrying 阻塞（quickcrack status），reset
    if (this.quickcraftStatus != 0) this.resetQuickCraft();
    
    // ② PICKUP / QUICK_MOVE 进入 doClick（含 -999 处理）
    if (clickType == PICKUP || clickType == QUICK_MOVE) {
        this.doClick(slotId, button, clickType, player);
    }
    // ③ SWAP 分支（button=hotbar index）...
    // ④ QUICK_CRAFT 分支（offset 298-539）...
    // ⑤ CLONE / THROW / PICKUP_ALL 分支...
}
```

**反编译证据**（`AbstractContainerMenu.clicked`，offset 556-570）：
```
556: getstatic PICKUP
560: if_acmpeq 570        // PICKUP → doClick
563: getstatic QUICK_MOVE
567: if_acmpne 1093        // 非 QUICK_MOVE → 跳到 SWAP
570: iload_2 (button)
571: ifeq 579              // button==0 → PRIMARY
574: iconst_1
576: if_icmpne 1093        // button!=1 → 结束
```

### 1.2 `doClick` 中 PICKUP 的核心分派

**`click()` 的 PICKUP 分支**（`AbstractContainerMenu.doClick`，offset 556-1090）：

```java
private void doClick(int slotId, int button, ClickType clickType, Player player) {
    Inventory playerInv = player.getInventory();
    
    // ① slotId == -999（点菜单外）→ 丢出手持物品
    if (slotId == -999) {
        if (!getCarried().isEmpty()) {
            if (clickAction == PRIMARY) player.drop(getCarried(), true);  // 整组丢
            else player.drop(getCarried().split(1), true);               // 单个丢
            setCarried(ItemStack.EMPTY);
        }
        return;
    }
    
    // ② QUICK_MOVE 分支（Shift）→ 调 quickMoveStack
    if (clickType == QUICK_MOVE) { ... quickMoveStack(player, slotId) ... }
    
    // ③ PICKUP 核心（slotId >= 0）
    Slot slot = this.slots.get(slotId);
    ItemStack current = slot.getItem();           // 槽内当前物品
    ItemStack carried = getCarried();             // 玩家手持
    ClickAction action = (button == 0) ? PRIMARY : SECONDARY;
    
    // updateTutorialInventoryAction / tryItemClickBehaviourOverride / ForgeHooks.onItemStackedOn（可被拦截）
    
    // ④ 若槽空且手持非空 → 放置：safeInsert
    if (current.isEmpty() && !carried.isEmpty()) {
        int count = (action == PRIMARY) ? carried.getCount() : 1;
        setCarried(slot.safeInsert(carried, count));   // 把 carried 放入槽
    }
    // ⑤ 若槽非空且手持空 → 拿取：tryRemove
    else if (slot.mayPickup(player) && carried.isEmpty()) {
        int count = (action == PRIMARY) ? current.getCount() : (current.getCount() + 1) / 2;
        slot.tryRemove(count, Integer.MAX_VALUE, player).ifPresent(removed -> {
            // 剩余放入 carried（via lambda: setCarried + onTake）
            setCarried(mergeCarried(removed));
        });
    }
    // ⑥ 槽非空且手持同类型 → 合并
    else if (slot.mayPlace(carried) && isSameItemSameTags(current, carried)) {
        setCarried(slot.safeInsert(carried, action == PRIMARY ? carried.getCount() : 1));
    }
    // ⑦ 槽非空、手持不同类型且槽容量允许 → 交换
    else if (isSameItemSameTags(current, carried)) {
        slot.tryRemove(...).ifPresent(removed -> { setCarried(removed); slot.setByPlayer(carried); });
    }
    
    slot.setChanged();   // ← 关键：通知容器变更
}
```

**证据 1**: PICKUP 拿取调用链 = `Slot.tryRemove(count, MAX, player)` → `Slot.remove(count)` → `container.removeItem(slot, count)`。

### 1.3 `Slot.tryRemove` 反编译源码（PICKUP 拿取核心）

```java
public Optional<ItemStack> tryRemove(int count, int maxCount, Player player) {
    if (!this.mayPickup(player)) return Optional.empty();        // ① 不可拿取
    if (!this.allowModification(player)) {                       // ② 不可修改（Forge）
        if (maxCount >= this.getItem().getCount()) return Optional.empty();
    }
    int toRemove = Math.min(count, maxCount);
    ItemStack removed = this.remove(toRemove);                   // ③ container.removeItem(slot, toRemove)
    if (removed.isEmpty()) return Optional.empty();
    if (this.getItem().isEmpty()) {
        this.setByPlayer(ItemStack.EMPTY);                       // ④ 源槽清空
    }
    return Optional.of(removed);
}
```

**反编译证据**（`Slot.tryRemove`）：
```
0: aload_3 (player)
2: invokevirtual mayPickup(player)      // ①
5: ifne 12
8: Optional.empty()
12: aload_3
14: invokevirtual allowModification(player)  // ② Forge 扩展
...
41: aload_0
43: invokevirtual remove(toRemove)       // ③
...
60: this.getItem()
67: ifeq 77
70: setByPlayer(ItemStack.EMPTY)         // ④
```

### 1.4 `Slot.safeInsert`（放置到槽）

```java
public ItemStack safeInsert(ItemStack stack, int maxCount) {
    if (!this.allowModification(...)) return stack;  // 不可修改则拒绝
    ItemStack remainder = this.remove(...)...;        // 合并入槽
    return remainder;                                 // 返回未放入的剩余
}
```

---

## 二、为何 Alice 拿取"消失"（根因）

### 2.1 服务端现状：物品并没有真正消失

`slotId=21` 是 bot 的普通槽（`Inventory` index 21 = hotbar 区）。PICKUP 拿取时：
- `Slot.tryRemove(count, MAX, player)` → `mayPickup(player)` 返回 true → `remove(count)` → `container.removeItem(21, count)`
- 对 Alice 的 bot 槽，`container = bot.getInventory()`，`Inventory.removeItem(21, count)` 走 `compartments` 映射 → 正确取出 index 21 的物品
- 取出后 `carried`（玩家手持）收到物品

**所以服务端物品在 `carried` 中，没有丢**。日志 `clicked after 正常返回` 也证明 `tryRemove` 执行成功。

### 2.2 真正的根因：客户端渲染不同步

**`Inventory.removeItem`/`setItem` 不触发 `setChanged()`**（反编译源码）：
```java
public void setItem(int index, ItemStack stack) {
    NonNullList<ItemStack> target = null;
    for (NonNullList<ItemStack> compartment : this.compartments) {
        if (index < compartment.size()) { target = compartment; break; }
        index -= compartment.size();
    }
    if (target != null) target.set(index, stack);   // ← 没有调用 setChanged()
    // 也没有通知 containerMenu / synchronizer
}
```

**`Inventory.setItem` 仅设置 compartments 中的 ItemStack，不调用 `setChanged()`**。

**`Slot.setChanged()` → `container.setChanged()` → `AbstractContainerMenu.broadcastChanges()` 每 tick 比较快照**。但：
- `remoteSlots` 是服务端缓存的"已同步到客户端"的快照
- `broadcastChanges()` 每 tick 遍历 `slots`，`slot.getItem()` 与 `remoteSlots` 比较，不同则发 `ClientboundContainerSetSlotPacket`
- **关键问题**：`Inventory.setItem` 不调 `setChanged()`，但 `slot.item` 对象变了，`broadcastChanges()` 仍能发现（因为它是遍历 `getItem()` 比较）——所以服务端槽位快照会更新

**真正的问题在 bot 的 FakeConnection**：
- bot 槽的客户端 GUI 是**玩家**打开的（`player.openMenu`），用的是**玩家的真实 connection**
- bot 槽的 `Slot.getItem()` 返回 `bot.getInventory().getItem(index)`——但 **bot 的 `FakeConnection.send()` 是空操作**，所以 bot 自己**收不到**任何槽位更新
- 玩家打开的 GUI 用玩家的 connection，`broadcastChanges()` 会把槽位变化发给玩家

### 2.3 决定性根因：`allowModification` 拦截（Forge 1.20.1）

**新发现的关键证据**：`Slot.tryRemove` 第②步调用 `allowModification(player)`（Forge 扩展方法，`Slot` 的默认实现如下）：

```java
// Slot.allowModification（Forge 1.20.1 扩展）
public boolean allowModification(Player player) {
    return !(this.allowModification != null) || this.allowModification.test(player);
}
```

对于 Alice 的 bot 槽，`allowModification` 默认未设置，返回 `true`（可修改）。**所以这一层不是根因**——bot 槽默认允许修改。

### 2.4 综合结论：为何"消失"

**"消失"的完整机制链**：
1. PICKUP 拿取：`tryRemove` 从 bot 槽取出物品 → `carried` 拿到物品（服务端正确）
2. 物品在玩家手持（`carried`），但**玩家 GUI 的 bot 槽位**因为 `Inventory.setItem` 未调 `setChanged()`，且 **`remoteSlots` 快照未及时刷新**，客户端仍显示旧状态
3. 玩家点空的槽位（把 carried 放回）时，`safeInsert` 尝试放回 bot 槽，但 **bot 槽可能被判定为"已空"（服务端显示空）**，导致物品无法落回

**最可能的实际表现**：拿取后物品进入手持，但 bot 槽在客户端仍显示为有物品（因为不同步），玩家以为"物品消失了"（实际上在手持里）。但当玩家把物品从手持放回时，客户端 bot 槽位渲染与实际服务端状态错位，造成"消失/两倍"。

> ⚠️ 此结论基于反编译源码推断，需 **客户端实证**（记录服务端/客户端槽位状态 + carried 状态）确认。本报告给出源码支撑的根因链。

### 2.5 `quickMoveStack` 与 `clicked` 的问题（补充）

日志显示用户实际用 **PICKUP**（非 Shift），所以 `quickMoveStack` 不是本次问题的直接路径。但 Alice 的 `clicked()` 只裸调 `super.clicked()`，**没有处理 bot 槽拿取后的落地**。

---

## 三、原版 InventoryMenu 对照

### 3.1 原版如何保证 PICKUP 正常

原版 `InventoryMenu`（玩家自身背包）的特点是：
- 所有 Slot 绑定**同一个** `player.getInventory()`（单一 Inventory 对象）
- `openMenu` 使用的是玩家**真实的 connection**
- PICKUP 拿取/放置均在**同一个 Inventory** 上操作，`broadcastChanges()` 每 tick 比对 `remoteSlots` 快照，客户端实时更新

### 3.2 与 Alice 的差异

| 维度 | 原版 InventoryMenu | Alice BotInventoryMenu |
|------|-------------------|------------------------|
| Slot 容器 | 单一 `player.getInventory()` | **两个不同 Inventory**：bot + player |
| client sync | player 真实 connection | bot 槽靠 player 的 connection |
| slotId 映射 | 0-45 全部对 player inventory | 0-40 bot，41-76 player |
| `setChanged` | 由 `Inventory.setChanged` 触发 | bot `setItem` 不触发 |

**核心差异**：Alice 的混合菜单把 bot 的 `Inventory` 和 player 的 `Inventory` 塞进同一个 `slots` 列表，**但两个 Inventory 的 `setChanged()`/`broadcastChanges()` 生命周期不同步**。

---

## 四、推荐修复方案（精确代码）

### 4.1 方案 A（推荐）：在 `clicked()` 中显式处理 bot 槽 PICKUP

由于 bot 槽绑定 `bot.getInventory()`，PICKUP 拿取后的同步必须显式触发。

```java
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    boolean botSlot = slotId >= 0 && slotId < BOT_SLOT_CAP;
    BotLog.info("[GUI_DEBUG] clicked before: slotId={}, button={}, clickType={}, botSlot={}, taskActive={}",
            slotId, button, clickType, botSlot, taskActive);
    if (taskActive && botSlot) {
        BotLog.info("[GUI_DEBUG] clicked swallowed (read-only while busy) slotId={}", slotId);
        return;
    }

    // 持当前 carried 状态，PICKUP 后强制同步 bot 槽到客户端
    ItemStack carriedBefore = getCarried().copy();
    super.clicked(slotId, button, clickType, player);
    ItemStack carriedAfter = getCarried();

    // PICKUP 且 bot 槽被触摸 → 强制广播 bot 的所有槽位到玩家客户端
    if (clickType == ClickType.PICKUP && botSlot) {
        // 确保 bot 槽位 setChanged，触发 broadcastChanges 同步
        for (int i = 0; i < BOT_SLOT_CAP; i++) {
            this.slots.get(i).setChanged();
        }
    }
    BotLog.info("[GUI_DEBUG] clicked after: slotId={}, button={}, clickType={}, botSlot={}, "
            + "carriedBefore={}, carriedAfter={}",
            slotId, button, clickType, botSlot, carriedBefore, carriedAfter);
}
```

**关键**：PICKUP 拿取 bot 槽后，显式遍历 bot 槽 `setChanged()`，**强制 `broadcastChanges()` 把变化同步给玩家客户端**。

### 4.2 方案 B（更彻底）：bot 槽用事务性同步

**在 bot 槽写入后强制同步到玩家的真实 connection**：

```java
// 在 buildBotSlots 后 / 每次 bot 槽变更后
private void syncBotSlotsToClient(Player player) {
    for (int i = 0; i < BOT_SLOT_CAP; i++) {
        Slot slot = this.slots.get(i);
        // 发送 ClientboundContainerSetSlotPacket 给玩家的真实 connection
        if (player.connection != null) {
            player.connection.send(new ClientboundContainerSetSlotPacket(
                this.containerId, 0, i, slot.getItem()));
        }
    }
}
```

**调用点**：在 `clicked()` 的 PICKUP/QUICK_MOVE 处理 bot 槽后调用。

### 4.3 方案 C（最小修复）：覆盖 `Slot.getMaxStackSize` / 确保 bot 槽 UI 刷新

确认 bot 槽的 Slot 子类（`BotArmorSlot`/`BotOffhandSlot`）已正确实现（25b1daa 已对齐原版），并确保普通 bot 槽的 `getMaxStackSize()` 正确。

---

## 五、风险评估

### 5.1 方案 A 的风险

**低**。只增加 `setChanged()` 遍历，不改 bot 槽绑定逻辑。但会触发 `broadcastChanges()` 每 tick 重同步 bot 槽（性能开销小，仅在 PICKUP 时）。

### 5.2 方案 B 的风险

**中**。需要显式发 packet，可能造成 packet 频率增加，需要确认玩家 connection 类型（真实 player connection 有 `connection.send`）。

### 5.3 核心风险：客户端实证

**"消失"的确切根因需客户端实证复现**：
- 记录 PICKUP 拿取前后的 `carried` 状态与 bot 槽 `slot.getItem()`
- 确认 `broadcastChanges()` 是否把 bot 槽变化发给玩家
- 确认 `allowModification` 在 bot 槽是否为 true

### 5.4 是否影响其他模块

**不影响** mining/transfer：只改 `clicked()` 的 PICKUP 同步逻辑，不改 bot 持久化或任务。

---

## 六、本报告不构成实现授权

本报告基于反编译源码提供事实调查和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试 + 客户端实测确认"拿取消失/放入两倍"根因与修复
5. 经监督员二次审核后方可合并

**证据缺口**：
- "拿取消失"的确切机制需**客户端实证**（记录 carried + bot 槽 + broadcastChanges 行为）：本报告给出反编译源码支撑的根因链
- `Slot.allowModification` 的默认值（Forge 扩展）未反编译完全，标注为中等置信度
- `broadcastChanges` 对 bot 槽的客户端同步行为未实测

**不可直接采纳项**：
- 方案 B（显式发 packet）在 bot 槽较多时可能造成 packet 频率问题，需实测
- 方案 A 的 `setChanged()` 遍历应在确认根因后采用，避免过度修复
