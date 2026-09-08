# Minecraft 容器"鼠标上的物品"机制深度调研

**调研员**: 鲸鱼娘  
**调研日期**: 2025-01-XX  
**目标**: 理解原版和 Forge 的"鼠标物品"机制

---

## 🎯 **核心问题**

1. **原版/Forge 如何定义"左键点击物品到鼠标上"？**
2. **鼠标上"拿着"的物品到底是什么状态？**
3. **为什么 JEI 可以拖拽"幽灵物品"到过滤器，而不产生实际物品？**

---

## 📋 **一、原版的"鼠标物品"机制**

### **1.1 AbstractContainerMenu 的 carried 字段**

```java
public abstract class AbstractContainerMenu {
    // 鼠标上拿着的物品（cursor item）
    private ItemStack carried = ItemStack.EMPTY;
    
    // 获取鼠标物品
    public ItemStack getCarried() {
        return this.carried;
    }
    
    // 设置鼠标物品
    public void setCarried(ItemStack stack) {
        this.carried = stack;
    }
}
```

**关键点**：
- ✅ `carried` 是 **Menu 实例的字段**，不是全局状态
- ✅ 每个打开的容器（Menu）有自己的 `carried`
- ✅ 关闭 Menu 时，`carried` 会掉落或返回背包

---

### **1.2 PICKUP 点击的完整流程**

**客户端点击**：
```java
// 客户端 Screen
public boolean mouseClicked(double mouseX, double mouseY, int button) {
    Slot slot = this.getSlotUnderMouse();
    if (slot != null && button == 0) {  // 左键
        // 发送 C2S packet
        this.minecraft.gameMode.handleInventoryMouseClick(
            this.menu.containerId,
            slot.index,
            button,
            ClickType.PICKUP,
            this.minecraft.player
        );
    }
}
```

**服务端处理**：
```java
// AbstractContainerMenu.clicked()
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    if (clickType == ClickType.PICKUP) {
        Slot slot = this.slots.get(slotId);
        ItemStack slotStack = slot.getItem();
        ItemStack carried = this.getCarried();
        
        if (carried.isEmpty()) {
            // Case 1: 鼠标空，槽有物品 → 拿起
            if (!slotStack.isEmpty()) {
                ItemStack picked = slot.tryRemove(slotStack.getCount(), Integer.MAX_VALUE, player);
                this.setCarried(picked.orElse(ItemStack.EMPTY));
            }
        } else {
            // Case 2: 鼠标有物品，槽空/有物品 → 放下/合并
            if (slotStack.isEmpty()) {
                // 放到空槽
                ItemStack toPlace = carried.copy();
                slot.safeInsert(toPlace);
                this.setCarried(ItemStack.EMPTY);
            } else {
                // 合并到非空槽
                // ...
            }
        }
    }
}
```

**同步到客户端**：
```java
// AbstractContainerMenu.broadcastChanges()
public void broadcastChanges() {
    // 同步所有变化的槽位
    for (int i = 0; i < this.slots.size(); i++) {
        ItemStack current = this.slots.get(i).getItem();
        ItemStack remote = this.remoteSlots.get(i);
        if (!ItemStack.matches(current, remote)) {
            // 发送 S2C packet 更新槽位
            this.connection.send(new ClientboundContainerSetSlotPacket(...));
        }
    }
    
    // 同步 carried（鼠标物品）
    if (!ItemStack.matches(this.carried, this.remoteCarried)) {
        this.connection.send(new ClientboundContainerSetSlotPacket(
            containerId, 
            -1,  // slotId = -1 表示 cursor slot
            this.carried
        ));
        this.remoteCarried = this.carried.copy();
    }
}
```

**关键发现**：
- ✅ **slotId = -1 表示"鼠标槽"（cursor slot）**
- ✅ `carried` 通过专门的 packet 同步（slotId = -1）
- ✅ 客户端的 Menu 也有 `carried` 字段，通过 packet 保持同步

---

## 📋 **二、真实物品 vs 幽灵物品**

### **2.1 真实物品（Real Item）**

**来源**：
- 从容器/背包拿起的实际物品
- `slot.tryRemove()` 真的从容器移除了物品

**特点**：
- ✅ 物品真实存在于 `menu.carried`
- ✅ 服务端和客户端同步
- ✅ 关闭 Menu 时会掉落或返回背包

---

### **2.2 幽灵物品（Ghost Item）**

**来源**：
- JEI 拖拽
- AE 终端的"显示物品"
- 过滤器配置

**特点**：
- ✅ **只存在于客户端**，不发送到服务端
- ✅ **不消耗实际物品**
- ✅ 用于"标记/配置"，不是真实转移

**实现方式**：
```java
// JEI 拖拽（客户端）
public void onJeiDragStart(ItemStack ghostStack) {
    // 不修改 menu.carried
    // 只在自己的 Screen 里记录一个"幽灵状态"
    this.ghostItem = ghostStack;
    this.isGhostMode = true;
}

// 放到过滤器槽时（客户端）
public void onSlotClick(Slot slot) {
    if (this.isGhostMode) {
        // 发送 C2S packet，只包含物品 ID，不移动实际物品
        sendSetFilterPacket(slot.index, this.ghostItem);
        this.isGhostMode = false;
        this.ghostItem = ItemStack.EMPTY;
    }
}

// 服务端处理
public void handleSetFilter(int slotIndex, ItemStack filterItem) {
    // 只记录过滤规则，不消耗物品
    this.filter.setFilter(slotIndex, filterItem.getItem());
}
```

**关键区别**：

| 维度 | 真实物品 | 幽灵物品 |
|------|---------|---------|
| 存储位置 | `menu.carried` | Screen 的临时字段 |
| 服务端 | ✅ 同步 | ❌ 不同步 |
| 消耗物品 | ✅ 是 | ❌ 否 |
| 关闭 GUI | 掉落/返回 | 消失 |
| 用途 | 真实转移 | 配置/标记 |

---

## 📋 **三、AE2 的特殊机制**

### **3.1 AE2 终端不是标准容器**

```java
// AE2 的终端不继承 AbstractContainerMenu
public class METerminalMenu extends ... {
    // 没有标准的 carried 字段
    // 所有物品显示都是"虚拟的"
    
    // 点击时
    public void onClick(int slotIndex, ItemStack clickedStack) {
        if (isJeiGhost) {
            // JEI 幽灵模式：只请求合成
            requestCraft(clickedStack);
        } else {
            // 真实模式：从 AE 网络提取
            extractFromNetwork(clickedStack);
        }
    }
}
```

**AE2 的"鼠标物品"**：
- ✅ 不是标准 `menu.carried`
- ✅ 从 AE 网络"临时取出"
- ✅ 关闭 GUI 时自动返回网络

---

## 📋 **四、过滤器的幽灵槽机制**

### **4.1 典型过滤器实现**

```java
// 过滤器 GUI（客户端）
public class FilterScreen extends Screen {
    private ItemStack ghostItem = ItemStack.EMPTY;
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 检测 JEI 拖拽
        if (JeiIntegration.isGhostDrag()) {
            this.ghostItem = JeiIntegration.getCurrentGhost();
        }
        
        int slot = getSlotUnderMouse();
        if (slot >= 0 && !this.ghostItem.isEmpty()) {
            // 发送 C2S：设置过滤规则
            sendSetFilterPacket(slot, this.ghostItem);
            this.ghostItem = ItemStack.EMPTY;
            return true;
        }
    }
}

// 过滤器容器（服务端）
public class FilterContainer {
    // 过滤规则（只存 Item ID，不存实际物品）
    private Item[] filters = new Item[9];
    
    public void setFilter(int slot, Item item) {
        this.filters[slot] = item;
        // 不消耗物品！只记录规则
    }
}
```

**关键点**：
- ✅ 过滤器槽不是真实容器槽
- ✅ 只存储"物品 ID"或"NBT 模板"
- ✅ JEI 拖拽的物品不经过 `menu.carried`

---

## 🎯 **五、对 Alice Bot Inventory 的启示**

### **5.1 方案 A（AbstractContainerMenu）的优势**

```java
public class BotInventoryMenu extends AbstractContainerMenu {
    // ✅ 自动处理 carried（鼠标物品）
    // ✅ 原版同步机制（slotId=-1）
    // ✅ 不需要手动管理
    
    @Override
    public void clicked(...) {
        super.clicked(...);  // 原版逻辑自动处理 carried
        
        // 只需要修复同步问题
        if (botSlot) {
            forceSync();
        }
    }
}
```

---

### **5.2 方向 B（自定义 Screen）的劣势**

**当前问题**：
```java
// BotInventoryScreen (方向 B)
private ItemStack carriedItem = ItemStack.EMPTY;  // 手动管理

// 问题 1：只在客户端，服务端不知道
// 问题 2：需要手动同步
// 问题 3：PICKUP 逻辑需要完全重写
```

**如果要继续方向 B，需要**：
1. ✅ 客户端管理 `carriedItem`
2. ✅ PICKUP 时不立即服务端操作，只本地标记
3. ✅ PLACE 时才发送"从 X 移到 Y"
4. ✅ 关闭 GUI 时处理剩余 `carriedItem`

**但这等于重新实现整个 AbstractContainerMenu 的 carried 机制！**

---

## 💡 **六、最终建议**

### **强烈建议：回到方案 A** ⭐⭐⭐

**理由**：
1. ✅ `menu.carried` 是原版标准机制，成熟稳定
2. ✅ 自动同步（slotId=-1 packet）
3. ✅ 原版 PICKUP/PLACE 逻辑完整
4. ✅ 只需修复 bot 槽的 `setChanged()` 问题

**需要的工作**：
```java
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    boolean botSlot = slotId >= 0 && slotId < BOT_SLOT_CAP;
    
    if (taskActive && botSlot) {
        return;  // 任务保护
    }
    
    super.clicked(slotId, button, clickType, player);
    
    // ✅ 关键修复：任何涉及 bot 槽的操作，强制同步
    if (botSlot) {
        forceSyncBotSlots();
    }
}

private void forceSyncBotSlots() {
    for (int i = 0; i < BOT_SLOT_CAP; i++) {
        this.slots.get(i).setChanged();
    }
}
```

---

## 📋 **七、关于"幽灵物品"的额外说明**

如果 Alice 将来需要支持"过滤器"或类似功能：

```java
// 过滤器槽（不是真实容器）
public class FilterSlot {
    private Item filterItem = null;  // 只存 ID
    
    public void setFromGhost(ItemStack ghost) {
        this.filterItem = ghost.getItem();  // 不消耗物品
    }
}

// 客户端渲染
public void renderFilterSlot(...) {
    if (filterItem != null) {
        // 渲染物品图标（半透明，表示是过滤规则）
        graphics.renderItem(new ItemStack(filterItem), x, y);
        graphics.setShaderColor(1.0f, 1.0f, 1.0f, 0.5f);  // 半透明
    }
}
```

---

## 🎓 **总结**

### **鼠标上的物品**
- ✅ 存储在 `AbstractContainerMenu.carried`
- ✅ 通过 slotId=-1 的 packet 同步
- ✅ 是真实物品，会消耗

### **幽灵物品**
- ✅ 只在客户端 Screen 里临时存储
- ✅ 不经过 `menu.carried`
- ✅ 用于配置/标记，不消耗物品

### **Alice 应该**
- ✅ 回到方案 A（AbstractContainerMenu）
- ✅ 利用原版的 `carried` 机制
- ✅ 只修复 bot 槽的同步问题

---

**调研完成！** ✨
