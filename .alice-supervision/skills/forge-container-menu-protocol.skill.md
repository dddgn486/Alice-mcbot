---
name: forge-container-menu-protocol
description: 理解 Container/Menu 系统的客户端-服务端协议、槽位同步、网络包与常见 GUI 问题。
---
# Skill: Forge Container Menu Protocol

## When to use this skill

Use this skill when:
- Creating a custom GUI/container (inventory, chest, custom interface)
- Server-side inventory changes but client GUI doesn't update
- Clicking slots causes items to vanish or duplicate
- Shift-click (quickMoveStack) doesn't work
- Need to understand Container vs Menu vs Screen

## Core Principle

**Minecraft GUI 是三层结构：**
1. **Container**（服务端）：逻辑、槽位、物品存储
2. **Menu**（客户端+服务端）：网络同步、点击处理
3. **Screen**（客户端）：渲染、输入

**客户端点击 → 服务端验证 → 服务端同步 → 客户端更新**

如果任何一步出错，就会出现"幽灵物品"、"点击无效"等问题。

---

## Three-Layer Architecture

### **Layer 1: Container（服务端）**

```java
// 服务端的容器，持有物品数据
public class BotInventoryMenu extends AbstractContainerMenu {
    private final Container botInventory;  // Bot 的背包
    
    public BotInventoryMenu(int containerId, Inventory playerInv, Container botInv) {
        super(MenuType.GENERIC_9x3, containerId);
        this.botInventory = botInv;
        
        // 添加槽位
        for (int i = 0; i < 27; i++) {
            this.addSlot(new Slot(botInventory, i, x, y));
        }
    }
}
```

**职责**：
- 定义槽位布局（哪个槽位对应哪个物品）
- 处理点击逻辑（quickMoveStack、doClick）
- 验证操作合法性（是否允许放置）

---

### **Layer 2: Menu（客户端+服务端）**

```java
// 客户端和服务端都有一份 Menu 实例
// 客户端 Menu：显示 GUI，发送点击包
// 服务端 Menu：验证点击，同步变化
```

**关键方法**：

```java
// 快速移动（Shift + 左键）
@Override
public ItemStack quickMoveStack(Player player, int slotIndex) {
    // 服务端执行
    Slot slot = this.slots.get(slotIndex);
    if (!slot.hasItem()) return ItemStack.EMPTY;
    
    ItemStack slotItem = slot.getItem();
    ItemStack copy = slotItem.copy();
    
    // 尝试移动到其他槽位
    if (!this.moveItemStackTo(slotItem, targetStart, targetEnd, reverse)) {
        return ItemStack.EMPTY;
    }
    
    // ⚠️ 关键：标记槽位变化
    slot.setChanged();
    
    return copy;
}
```

---

### **Layer 3: Screen（客户端）**

```java
// 客户端的 GUI 渲染
public class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 绘制背景、槽位、物品图标
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 处理鼠标点击，发送网络包到服务端
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
```

---

## Network Sync Flow

### **正常的点击流程**

```
1. 客户端：玩家点击槽位
   ↓
2. 客户端：Screen.mouseClicked()
   ↓
3. 客户端：发送 ServerboundContainerClickPacket
   {
       containerId: 5,
       slotNum: 10,
       button: 0,  // 左键
       clickType: PICKUP
   }
   ↓
4. 服务端：ServerGamePacketListenerImpl.handleContainerClick()
   ↓
5. 服务端：Menu.doClick() 验证并执行
   ↓
6. 服务端：发送 ClientboundContainerSetSlotPacket
   {
       containerId: 5,
       slot: 10,
       item: ItemStack(diamond, 1)
   }
   ↓
7. 客户端：更新 GUI 显示
```

### **同步包类型**

#### **1. ClientboundContainerSetSlotPacket**
```java
// 告诉客户端"某个槽位变化了"
new ClientboundContainerSetSlotPacket(
    containerId,
    stateId,      // 防止过期包
    slotIndex,
    itemStack
);
```

**什么时候发送**：
- 服务端修改单个槽位
- 玩家点击槽位后
- 其他玩家修改共享容器

---

#### **2. ClientboundContainerSetContentPacket**
```java
// 告诉客户端"整个容器变化了"
new ClientboundContainerSetContentPacket(
    containerId,
    stateId,
    items,           // 所有槽位的物品
    carriedItem      // 鼠标上的物品
);
```

**什么时候发送**：
- 容器首次打开
- 大量槽位同时变化

---

#### **3. ClientboundContainerSetDataPacket**
```java
// 告诉客户端"容器数据变化了"（进度、燃烧时间等）
new ClientboundContainerSetDataPacket(
    containerId,
    dataId,
    value
);
```

---

## Common Pitfalls

### **Pitfall 1: 忘记调用 slot.setChanged()**

**症状**：
- 服务端日志显示物品移动 ✓
- 客户端 GUI 不更新 ✗

**根因**：
```java
// 错误代码
@Override
public ItemStack quickMoveStack(Player player, int slotIndex) {
    Slot slot = this.slots.get(slotIndex);
    this.moveItemStackTo(slot.getItem(), 0, 27, false);
    // ❌ 忘记标记变化，客户端不知道
    return ItemStack.EMPTY;
}

// 正确代码
@Override
public ItemStack quickMoveStack(Player player, int slotIndex) {
    Slot slot = this.slots.get(slotIndex);
    this.moveItemStackTo(slot.getItem(), 0, 27, false);
    slot.setChanged();  // ✅ 触发同步包
    return slot.getItem().copy();
}
```

---

### **Pitfall 2: 客户端和服务端槽位索引不一致**

**症状**：
- 点击第 1 个槽位，但第 5 个槽位变化

**根因**：
```java
// 客户端 Menu
public BotInventoryMenu(int id, Inventory playerInv) {
    // 添加 bot 槽位
    for (int i = 0; i < 27; i++) {
        this.addSlot(new Slot(..., i, ...));  // 槽位 0-26
    }
    // 添加玩家槽位
    for (int i = 0; i < 36; i++) {
        this.addSlot(new Slot(..., i, ...));  // 槽位 27-62
    }
}

// 服务端 Menu
public BotInventoryMenu(int id, Inventory playerInv, Container botInv) {
    // 顺序不同！
    for (int i = 0; i < 36; i++) {
        this.addSlot(new Slot(..., i, ...));  // 槽位 0-35
    }
    for (int i = 0; i < 27; i++) {
        this.addSlot(new Slot(..., i, ...));  // 槽位 36-62
    }
}
// ❌ 客户端的槽位 0 != 服务端的槽位 0
```

**修复**：
- 确保客户端和服务端的 `addSlot()` 顺序完全一致

---

### **Pitfall 3: Container 数据源不同步**

**症状**：
- 打开 GUI 看到旧数据
- 外部修改 inventory，GUI 不更新

**根因**：
```java
// Bot 的 inventory
public class BotPlayer extends ServerPlayer {
    private final SimpleContainer customInventory = new SimpleContainer(27);
}

// Menu 引用了错误的 inventory
public BotInventoryMenu(BotPlayer bot) {
    // ❌ 引用了原版 inventory
    super(..., bot.getInventory());
    
    // ✅ 应该引用自定义 inventory
    // super(..., bot.getCustomInventory());
}
```

**修复**：
- Menu 必须引用正确的数据源
- 数据源变化时调用 `container.setChanged()`

---

### **Pitfall 4: 多人同时打开容器**

**症状**：
- 玩家 A 修改物品
- 玩家 B 看到旧数据

**根因**：
- 容器只追踪当前打开的玩家
- 需要手动广播变化

**解决方案**：
```java
public class SharedContainer extends SimpleContainer {
    private final Set<Player> viewers = new HashSet<>();
    
    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        
        // 广播给所有查看者
        for (Player viewer : viewers) {
            if (viewer instanceof ServerPlayer sp) {
                sp.connection.send(
                    new ClientboundContainerSetSlotPacket(
                        sp.containerMenu.containerId,
                        0,  // stateId
                        slot,
                        stack
                    )
                );
            }
        }
    }
}
```

---

## Menu Registration

### **注册 MenuType**

```java
public class AliceMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = 
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, AliceMod.MOD_ID);
    
    public static final RegistryObject<MenuType<BotInventoryMenu>> BOT_INVENTORY = 
        MENUS.register("bot_inventory", () -> 
            IForgeMenuType.create((containerId, playerInv, data) -> {
                // 客户端构造函数（没有 bot 数据源）
                return new BotInventoryMenu(containerId, playerInv);
            })
        );
}

// 在 AliceMod 构造函数中
AliceMenus.MENUS.register(modEventBus);
```

---

### **打开 Menu**

```java
// 服务端打开 GUI
public void openBotInventory(ServerPlayer player, BotPlayer bot) {
    player.openMenu(new SimpleMenuProvider(
        (containerId, playerInv, p) -> 
            new BotInventoryMenu(containerId, playerInv, bot.getCustomInventory()),
        Component.literal("Bot Inventory")
    ));
}
```

---

## Debugging Checklist

GUI 不工作时的调试清单：

### **Step 1: 验证 Menu 注册**
```java
// 检查 MenuType 是否注册
System.out.println("Menu registered: " + AliceMenus.BOT_INVENTORY.isPresent());
```

- [ ] MenuType 已注册？
- [ ] 客户端和服务端都注册？

---

### **Step 2: 验证槽位布局**
```java
// 服务端
System.out.println("Server slots: " + menu.slots.size());
for (int i = 0; i < menu.slots.size(); i++) {
    Slot slot = menu.slots.get(i);
    System.out.println("Slot " + i + ": container=" + 
        slot.container.getClass().getSimpleName());
}

// 客户端（同样的日志）
```

- [ ] 客户端和服务端槽位数量相同？
- [ ] 槽位顺序一致？
- [ ] 槽位引用正确的 container？

---

### **Step 3: 监控网络包**
```java
// 在 ServerGamePacketListenerImpl
@Override
public void handleContainerClick(ServerboundContainerClickPacket packet) {
    System.out.println("Click: slot=" + packet.getSlotNum() + 
        " button=" + packet.getButtonNum());
    super.handleContainerClick(packet);
}

// 在发送同步包时
System.out.println("Sending SetSlotPacket: slot=" + slotIndex + 
    " item=" + itemStack);
```

- [ ] 客户端发送点击包？
- [ ] 服务端收到点击包？
- [ ] 服务端发送同步包？
- [ ] 客户端收到同步包？

---

### **Step 4: 检查 setChanged() 调用**
```java
// 在 Container 实现中
@Override
public void setChanged() {
    System.out.println("Container changed!");
    super.setChanged();
}
```

- [ ] quickMoveStack 后调用了 setChanged()？
- [ ] 外部修改 inventory 后调用了 setChanged()？

---

## Reference Patterns

### **Pattern 1: 标准 quickMoveStack 实现**

```java
@Override
public ItemStack quickMoveStack(Player player, int slotIndex) {
    Slot slot = this.slots.get(slotIndex);
    if (!slot.hasItem()) return ItemStack.EMPTY;
    
    ItemStack slotItem = slot.getItem();
    ItemStack copy = slotItem.copy();
    
    // Bot 槽位 → 玩家背包
    if (slotIndex < 27) {
        if (!this.moveItemStackTo(slotItem, 27, 63, true)) {
            return ItemStack.EMPTY;
        }
    }
    // 玩家背包 → Bot 槽位
    else {
        if (!this.moveItemStackTo(slotItem, 0, 27, false)) {
            return ItemStack.EMPTY;
        }
    }
    
    if (slotItem.isEmpty()) {
        slot.set(ItemStack.EMPTY);
    } else {
        slot.setChanged();
    }
    
    return copy;
}
```

---

### **Pattern 2: 实时同步容器**

```java
public class SyncedContainer extends SimpleContainer {
    private final List<ContainerListener> listeners = new ArrayList<>();
    
    public void addListener(ContainerListener listener) {
        listeners.add(listener);
    }
    
    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        
        // 通知所有监听者
        for (ContainerListener listener : listeners) {
            listener.slotChanged(this, slot, stack);
        }
    }
}

// 在 Menu 中
container.addListener(this);
```

---

### **Pattern 3: 只读槽位**

```java
public class ReadOnlySlot extends Slot {
    public ReadOnlySlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }
    
    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;  // 禁止放置
    }
    
    @Override
    public boolean mayPickup(Player player) {
        return false;  // 禁止拾取
    }
}
```

---

## Evidence Requirements

实施后需要以下证据：

**服务端日志**：
```
[DEBUG] Opening bot inventory for Player1
[DEBUG] Menu slots: 63 (27 bot + 36 player)
[DEBUG] Click: slot=10, button=0, type=PICKUP
[DEBUG] Sending SetSlotPacket: slot=10, item=diamond
```

**客户端验证**：
- [ ] 右键打开 GUI
- [ ] GUI 显示正确的物品
- [ ] 左键点击可以拾取物品
- [ ] Shift+左键快速移动物品
- [ ] 拖拽物品工作正常
- [ ] 关闭 GUI 后物品保存

**多人测试**：
- [ ] 两个玩家同时打开同一个容器
- [ ] 玩家 A 修改物品，玩家 B 看到变化

---

**This skill helps you avoid 10-16 hours of "why does my GUI not work" debugging.**
