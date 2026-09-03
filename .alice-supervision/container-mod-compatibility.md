# 容器 GUI 与整理模组的兼容性

> 理解槽位分类和整理模组兼容性问题

---

## 🎯 问题描述

### **现象**

在使用整理模组（如 Inventory Tweaks, Mouse Tweaks）或鼠标手势时：
- 物品被放到了不该放的位置
- 例如：Bot 背包的副手槽、精致存储的合成网格等
- Shift+点击时行为不符合预期

### **真实案例**

#### **案例 1：Alice Bot 背包**
```
玩家 Shift+点击钻石
    ↓
钻石被放到 Bot 副手槽（索引 4）
    ↓
而不是主背包（索引 5-40）
```

#### **案例 2：精致存储 + 合成升级**
```
玩家使用整理模组整理箱子
    ↓
物品被放到合成网格槽位（不该放）
    ↓
甚至放到合成输出槽（绝对不该放）
```

---

## 🔍 根本原因

### **1. 槽位扫描顺序**

```java
// 整理模组的典型逻辑
for (int i = 0; i < menu.slots.size(); i++) {
    Slot slot = menu.slots.get(i);
    if (slot.mayPlace(itemStack)) {
        // 找到第一个可以放入的槽位 → 放入
        break;
    }
}
```

**问题**：
- 副手槽（索引 4）在主背包（索引 5-40）前面
- 副手槽没有 `mayPlace` 限制
- 所以整理模组优先放到副手槽

### **2. 槽位类型缺乏标准**

Minecraft 没有统一的槽位分类 API：

```
没有办法区分：
- 存储槽位（应该放物品）
- 工作槽位（合成网格、熔炉燃料等，不应该被整理）
- 只读槽位（合成输出、熔炉输出，绝对不能放）
- 装备槽位（只能放装备）
```

### **3. moveItemStackTo 的行为**

```java
// Alice 原来的代码
else {
    // 玩家物品 → Bot 背包
    moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);
    //                            ↑  ↑
    //                            从索引 0 开始扫描
    //                            包括装备槽（0-3）和副手槽（4）
}
```

**扫描顺序**：
```
0-3:   装备槽（有 mayPlace 限制，普通物品放不进）
4:     副手槽（没有限制，任何物品都能放）← 第一个可用槽位！
5-40:  主背包和 hotbar
```

---

## ✅ 解决方案

### **方案 1：定义槽位区域常量（推荐）**

```java
public class BotInventoryMenu extends AbstractContainerMenu {
    
    // 定义清晰的槽位区域
    private static final int BOT_EQUIPMENT_START = 0;
    private static final int BOT_EQUIPMENT_END = 4;      // 不包含
    private static final int BOT_OFFHAND_SLOT = 4;
    private static final int BOT_MAIN_INVENTORY_START = 5;  // 主背包起始
    private static final int BOT_HOTBAR_START = 32;         // Hotbar 起始
    private static final int BOT_HOTBAR_END = 41;           // Bot 槽位结束（不包含）
    
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // ...
        
        if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR) {
            // 装备 → 装备槽
            int armorSlot = armorSlotIndex(equipmentSlot);
            moved = moveItemStackTo(stack, armorSlot, armorSlot + 1, false);
        } else if (equipmentSlot == EquipmentSlot.OFFHAND) {
            // 副手物品 → 副手槽
            moved = moveItemStackTo(stack, BOT_OFFHAND_SLOT, BOT_OFFHAND_SLOT + 1, false);
        } else {
            // ✅ 普通物品 → 主背包和 hotbar（跳过装备和副手）
            moved = moveItemStackTo(stack, BOT_MAIN_INVENTORY_START, BOT_HOTBAR_END, false);
        }
        
        // ...
    }
}
```

**效果**：
- ✅ 普通物品从索引 5 开始放（跳过装备和副手）
- ✅ 装备和副手物品有专门的处理逻辑
- ✅ 整理模组扫描时，主背包槽位更优先

---

### **方案 2：限制副手槽的 mayPlace**

```java
public class BotOffhandSlot extends Slot {
    @Override
    public boolean mayPlace(ItemStack stack) {
        // ❌ 方案 A：完全禁止（太严格）
        return false;
        
        // ⚠️ 方案 B：只允许特定物品（需要维护列表）
        return stack.getItem() instanceof ShieldItem || 
               stack.getItem() instanceof TorchItem;
        
        // ✅ 方案 C：允许放入，但通过 quickMoveStack 控制行为
        return true;  // 保持现状
    }
}
```

**问题**：
- 方案 A 太严格，玩家无法手动放物品到副手
- 方案 B 需要维护允许列表，不灵活
- 方案 C 最好，通过 quickMoveStack 控制 Shift+点击行为

---

### **方案 3：使用特殊 Slot 类型标记**

```java
// 标记接口
public interface WorkingSlot {
    // 标记这是工作槽位（合成网格、熔炉等）
    // 整理模组应该跳过
}

// 合成网格槽位
public class CraftingGridSlot extends Slot implements WorkingSlot {
    @Override
    public boolean mayPlace(ItemStack stack) {
        return true;  // 允许放入
    }
}

// 整理模组检查
public class InventorySorter {
    public static void sort(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot instanceof WorkingSlot) {
                continue;  // 跳过工作槽位
            }
            // 整理...
        }
    }
}
```

**限制**：
- 需要整理模组支持这个标记接口
- 大多数整理模组不支持

---

## 📊 Alice 项目的修复

### **修改前**

```java
else {
    // 玩家物品 → Bot 背包
    moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);
    //                            ↑
    //                            从 0 开始，包括装备和副手
}
```

**问题**：
- 扫描从索引 0 开始
- 装备槽（0-3）有限制，普通物品放不进
- 副手槽（4）没有限制，成为第一个可用槽位
- 物品都被放到副手槽

### **修改后**

```java
else {
    // 玩家物品 → Bot 主背包和 hotbar（跳过装备和副手）
    moved = moveItemStackTo(stack, BOT_MAIN_INVENTORY_START, BOT_HOTBAR_END, false);
    //                            ↑                          ↑
    //                            从 5 开始                   到 41 结束
    //                            跳过装备（0-3）和副手（4）
}
```

**效果**：
- ✅ 扫描从索引 5 开始（主背包第一格）
- ✅ 副手槽不再被优先填充
- ✅ 物品按预期放到主背包

---

## 🎓 通用原则

### **1. 明确槽位区域**

```java
// ✅ 好的设计
private static final int STORAGE_START = 0;
private static final int STORAGE_END = 27;
private static final int CRAFTING_GRID_START = 27;
private static final int CRAFTING_GRID_END = 36;
private static final int CRAFTING_OUTPUT = 36;
private static final int PLAYER_INVENTORY_START = 37;

// ❌ 坏的设计
// 没有常量，到处写 magic number
moveItemStackTo(stack, 0, 54, false);  // 0 和 54 是什么意思？
```

### **2. 区分存储槽位和工作槽位**

```
存储槽位：
    - 箱子、背包、潜影盒等
    - 应该被整理模组整理
    - Shift+点击的主要目标

工作槽位：
    - 合成网格、熔炉燃料槽、附魔台等
    - 不应该被整理模组整理
    - Shift+点击应该跳过（除非是特定物品）

只读槽位：
    - 合成输出、熔炉输出等
    - mayPlace() 返回 false
    - 绝对不能被填充
```

### **3. quickMoveStack 的职责**

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    // 职责 1：判断物品类型
    EquipmentSlot equipmentSlot = LivingEntity.getEquipmentSlotForItem(stack);
    
    // 职责 2：根据类型选择目标区域
    if (是装备) {
        移动到装备槽
    } else if (是副手物品) {
        移动到副手槽
    } else {
        移动到主存储区域（跳过特殊槽位）
    }
    
    // 职责 3：清理源槽位
    // 职责 4：返回原始物品堆
}
```

---

## 🛡️ 兼容性最佳实践

### **1. 为你的 GUI 设计合理的槽位顺序**

```
✅ 推荐顺序：
    1. 存储槽位（主要区域）
    2. 装备/副手槽位
    3. 玩家背包
    
❌ 不好的顺序：
    1. 装备/副手槽位 ← 整理模组会优先填充
    2. 存储槽位
    3. 玩家背包
```

**Alice 项目的槽位顺序**：
```
0-3:   装备槽      ← 有 mayPlace 限制
4:     副手槽      ← 没有限制，但在主背包前面（问题所在）
5-31:  主背包      ← 应该是主要存储区域
32-40: Hotbar      ← 也是存储区域
41+:   玩家背包
```

**更好的顺序**（如果可以重新设计）：
```
0-35:  主背包和 hotbar  ← 主要存储区域
36-39: 装备槽          ← 特殊槽位
40:    副手槽          ← 特殊槽位
41+:   玩家背包
```

但 Alice 项目已经定型，所以通过 quickMoveStack 控制行为。

---

### **2. 使用专门的 Slot 子类**

```java
// ✅ 装备槽
public class BotArmorSlot extends Slot {
    @Override
    public boolean mayPlace(ItemStack stack) {
        return stack.canEquip(equipmentSlot, owner);
    }
}

// ✅ 只读槽（合成输出）
public class ResultSlot extends Slot {
    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;  // 不允许放入
    }
}

// ✅ 副手槽（可以考虑添加限制）
public class BotOffhandSlot extends Slot {
    @Override
    public boolean mayPlace(ItemStack stack) {
        return true;  // 或者添加特定限制
    }
}
```

---

### **3. 在 quickMoveStack 中明确跳过特殊槽位**

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    // ...
    
    // ✅ 明确跳过装备和副手，只放到主存储区域
    moved = moveItemStackTo(stack, MAIN_STORAGE_START, MAIN_STORAGE_END, false);
    
    // ❌ 不要这样做
    moved = moveItemStackTo(stack, 0, ALL_CONTAINER_SLOTS, false);
}
```

---

## 🔧 与整理模组的兼容性

### **整理模组的典型行为**

#### **Inventory Tweaks Renewed**
```java
// 扫描所有槽位，按优先级排序
for (Slot slot : menu.slots) {
    if (slot.mayPlace(stack) && slot.getItem().isEmpty()) {
        // 放入
    }
}
```

#### **Mouse Tweaks**
```java
// Shift+拖拽：均匀分配物品
for (Slot slot : draggedSlots) {
    if (slot.mayPlace(stack)) {
        // 分配一部分
    }
}
```

#### **QuickStack**
```java
// 快速堆叠：只合并到现有堆叠
for (Slot slot : menu.slots) {
    if (!slot.getItem().isEmpty() && 
        ItemStack.isSameItemSameTags(stack, slot.getItem())) {
        // 合并
    }
}
```

---

### **如何确保兼容？**

#### **方法 1：限制 mayPlace（不推荐）**
```java
@Override
public boolean mayPlace(ItemStack stack) {
    return false;  // 整理模组会跳过
}
```

**问题**：玩家也无法手动放入。

#### **方法 2：使用特殊 Slot 类型**
```java
// 标记为工作槽位
public class CraftingGridSlot extends Slot implements WorkingSlot {
    // ...
}
```

**问题**：需要整理模组支持，大多数不支持。

#### **方法 3：合理的 quickMoveStack（推荐）**
```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    // 明确控制 Shift+点击的行为
    // 跳过特殊槽位，只移动到主存储区域
    moved = moveItemStackTo(stack, STORAGE_START, STORAGE_END, false);
}
```

**效果**：
- ✅ Shift+点击行为正确
- ✅ 整理模组可能还是会访问特殊槽位，但影响较小
- ✅ 玩家可以手动放入特殊槽位

---

## 📝 总结

### **问题根源**
1. 槽位顺序：特殊槽位在存储槽位前面
2. 缺乏标准：Minecraft 没有统一的槽位分类
3. 扫描逻辑：整理模组从第一个槽位开始扫描

### **解决方案**
1. **定义槽位区域常量**（清晰、可维护）
2. **quickMoveStack 跳过特殊槽位**（只移动到主存储区域）
3. **使用专门的 Slot 子类**（为未来的兼容性做准备）

### **Alice 项目的修复**
```java
// 修改前
moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);

// 修改后
moved = moveItemStackTo(stack, BOT_MAIN_INVENTORY_START, BOT_HOTBAR_END, false);
```

**效果**：
- ✅ Shift+点击不再放到副手槽
- ✅ 物品优先放到主背包
- ✅ 与整理模组的兼容性更好

---

*文档创建时间：2025-01-XX*  
*适用版本：Minecraft 1.20.1 Forge*  
*项目：Alice Bot System*
