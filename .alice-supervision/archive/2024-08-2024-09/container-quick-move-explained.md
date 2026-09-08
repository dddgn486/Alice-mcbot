# Shift+点击快速移动的完整逻辑详解

> 给人类开发者的图解教程：理解 Minecraft 容器系统中 Shift+点击的工作原理

---

## 📋 流程总览

```
玩家按 Shift+点击槽位
         ↓
客户端发送 ServerboundContainerClickPacket
    clickType = ClickType.QUICK_MOVE
         ↓
服务端 AbstractContainerMenu.doClick()
         ↓
调用 quickMoveStack(player, slotIndex)  ← 你需要重写这个！
         ↓
    [你的自定义逻辑：判断移动方向]
         ↓
调用 moveItemStackTo(stack, startIndex, endIndex, reverseDirection)  ← 原版提供
         ↓
    [原版移动逻辑：智能合并和放置]
         ↓
返回结果，客户端显示动画
```

---

## 🎯 第一步：quickMoveStack（自定义逻辑）

**你的职责**：决定物品往哪个方向移动

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    Slot slot = this.slots.get(index);
    if (!slot.hasItem()) {
        return ItemStack.EMPTY;
    }
    
    ItemStack stack = slot.getItem();      // ⚠️ 引用！不是拷贝！
    ItemStack original = stack.copy();     // 保存副本用于返回值
    
    // 判断移动方向
    boolean moved;
    if (index < BOT_SLOT_CAP) {
        // 情况 1：从 Bot 槽位移动到玩家背包
        moved = moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), false);
    } else {
        // 情况 2：从玩家背包移动到 Bot 槽位
        moved = moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);
    }
    
    if (!moved) {
        return ItemStack.EMPTY;  // 移动失败
    }
    
    // 清理源槽位
    if (stack.isEmpty()) {
        slot.setByPlayer(ItemStack.EMPTY);
    } else {
        slot.setChanged();
    }
    
    return original;  // 返回原始物品堆（用于客户端显示动画）
}
```

### 槽位索引布局（Alice 项目）

```
Bot 槽位：0 - 40  (BOT_SLOT_CAP = 41)
├── 0-3:   装备槽（头、胸、腿、脚）
├── 4:     副手
├── 5-31:  主背包（27 格）
└── 32-40: Hotbar（9 格）

玩家槽位：41 - 76  (this.slots.size() = 77)
├── 41-67: 主背包（27 格）
└── 68-76: Hotbar（9 格）
```

---

## 🔧 第二步：moveItemStackTo（原版逻辑）

**原版职责**：智能地将物品移动到目标区域

### 方法签名

```java
protected boolean moveItemStackTo(
    ItemStack stack,         // ⚠️ 引用传递！会被修改！
    int startIndex,          // 目标区域起始索引（包含）
    int endIndex,            // 目标区域结束索引（不包含）
    boolean reverseDirection // 是否反向遍历
)
```

### 完整逻辑（伪代码）

```java
protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
    boolean moved = false;
    
    // ========================================
    // 阶段 1：尝试合并到现有堆叠
    // ========================================
    if (stack.isStackable()) {  // 只有可堆叠物品才尝试合并
        for (遍历 startIndex 到 endIndex) {
            Slot targetSlot = this.slots.get(i);
            ItemStack targetItem = targetSlot.getItem();
            
            // 检查是否可以合并
            if (targetItem 不为空 && targetItem 和 stack 是同种物品) {
                int combined = targetItem.getCount() + stack.getCount();
                int maxSize = targetItem.getMaxStackSize();
                
                if (combined <= maxSize) {
                    // 可以完全合并
                    targetItem.setCount(combined);
                    stack.setCount(0);  // ⚠️ 清空源物品
                    moved = true;
                    break;  // 完成，退出
                } else {
                    // 只能部分合并（填满目标槽位）
                    int canMove = maxSize - targetItem.getCount();
                    targetItem.setCount(maxSize);
                    stack.shrink(canMove);  // ⚠️ 源物品减少
                    moved = true;
                    // 继续扫描下一个槽位...
                }
            }
        }
    }
    
    // ========================================
    // 阶段 2：如果还有剩余，放入空槽位
    // ========================================
    if (!stack.isEmpty()) {
        for (遍历 startIndex 到 endIndex) {
            Slot targetSlot = this.slots.get(i);
            
            // 找到空槽位
            if (targetSlot.getItem().isEmpty() && targetSlot.mayPlace(stack)) {
                int maxSize = targetSlot.getMaxStackSize(stack);
                
                if (stack.getCount() <= maxSize) {
                    // 全部放入
                    targetSlot.setByPlayer(stack.copy());
                    stack.setCount(0);  // ⚠️ 清空源物品
                } else {
                    // 只能放一部分
                    targetSlot.setByPlayer(stack.split(maxSize));
                    // stack 剩余部分保留
                }
                
                moved = true;
                break;  // 只放一个空槽位就结束
            }
        }
    }
    
    return moved;  // 返回是否成功移动了至少一部分
}
```

---

## ⚠️ 关键要点

### 1. **引用传递！stack 会被修改！**

这是最容易混淆的地方！

```java
ItemStack stack = slot.getItem();       // ⚠️ 引用！不是拷贝！
ItemStack original = stack.copy();      // ✅ 所以要先拷贝保存原始值

moveItemStackTo(stack, ...);            // ⚠️ 这会直接修改 stack 对象！

// 移动后 stack 的状态：
// - 如果完全移动：stack.isEmpty() == true
// - 如果部分移动：stack.getCount() 减少
// - 如果没移动：stack 保持不变

return original;  // ✅ 返回原始值，不是修改后的 stack！
```

**为什么返回 original？**
- 客户端需要知道原始物品是什么（用于显示动画）
- 如果返回 `ItemStack.EMPTY`，表示操作失败

---

### 2. **两阶段扫描**

```
阶段 1：合并到现有堆叠（智能合并）
    ↓
    扫描目标区域，找到相同物品的槽位
    ↓
    尝试合并（不超过 maxStackSize）
    ↓
    如果源物品还有剩余，继续扫描下一个槽位
    ↓
    直到源物品用完或扫描完所有槽位

阶段 2：放入空槽位（如果阶段 1 没用完）
    ↓
    重新扫描目标区域，找到第一个空槽位
    ↓
    把剩余物品放进去
    ↓
    立即结束（只放一个空槽位）
```

---

### 3. **reverseDirection 的作用**

控制扫描顺序：

```java
// forward (false)：从前往后
moveItemStackTo(stack, 0, 36, false);
// 遍历顺序：0 → 1 → 2 → ... → 35

// reverse (true)：从后往前
moveItemStackTo(stack, 0, 36, true);
// 遍历顺序：35 → 34 → 33 → ... → 0
```

**实际应用**：

```java
// 玩家背包 → Bot 背包：正向扫描
// 优先填充 Bot 背包前面的格子（装备槽、副手、主背包前排）
moveItemStackTo(stack, 0, BOT_SLOT_CAP, false);

// Bot 背包 → 玩家背包：可以用反向扫描
// 优先填充玩家的 hotbar（在后面，索引 68-76）
moveItemStackTo(stack, BOT_SLOT_CAP, this.slots.size(), true);
```

---

## 📊 完整示例

### 示例 1：Shift+点击 Bot 的钻石（简单情况）

```
初始状态：
┌─────────────────────────────────────┐
│ Bot 槽位 5:   钻石 x64              │
│ Bot 槽位 6:   空                    │
│ 玩家槽位 41:  空                    │
│ 玩家槽位 42:  空                    │
└─────────────────────────────────────┘

玩家 Shift+点击槽位 5
    ↓
quickMoveStack(player, 5)
    ↓
判断: index=5 < BOT_SLOT_CAP=41
    → 移动方向: Bot → 玩家
    ↓
调用: moveItemStackTo(stack, 41, 77, false)
    stack = 钻石 x64 的引用
    ↓
阶段 1: 扫描 41-76，查找现有的钻石堆叠
    → 没找到钻石
    ↓
阶段 2: 扫描 41-76，查找空槽位
    → 找到槽位 41 是空的
    → slot[41].setByPlayer(stack.split(64))
    → stack 现在: 钻石 x0 (isEmpty = true)
    ↓
moved = true
    ↓
清理源槽位:
    slot[5].setByPlayer(ItemStack.EMPTY)
    ↓
return original (钻石 x64)

最终状态：
┌─────────────────────────────────────┐
│ Bot 槽位 5:   空                    │
│ Bot 槽位 6:   空                    │
│ 玩家槽位 41:  钻石 x64 ← 移动到这里  │
│ 玩家槽位 42:  空                    │
└─────────────────────────────────────┘
```

---

### 示例 2：Shift+点击玩家的钻石（Bot 已有部分钻石）

```
初始状态：
┌─────────────────────────────────────┐
│ Bot 槽位 5:   钻石 x10              │
│ Bot 槽位 6:   空                    │
│ Bot 槽位 7:   钻石 x30              │
│ 玩家槽位 41:  钻石 x64              │
└─────────────────────────────────────┘

玩家 Shift+点击槽位 41
    ↓
quickMoveStack(player, 41)
    ↓
判断: index=41 >= BOT_SLOT_CAP=41
    → 移动方向: 玩家 → Bot
    ↓
调用: moveItemStackTo(stack, 0, 41, false)
    stack = 钻石 x64 的引用
    ↓
阶段 1: 扫描 0-40，查找现有的钻石堆叠
    
    找到槽位 5: 钻石 x10
        combined = 10 + 64 = 74
        maxSize = 64
        74 > 64，只能部分合并
        → slot[5].setCount(64)          // 填满槽位 5
        → stack.shrink(64 - 10 = 54)   // stack 减少 54
        → stack 现在: 钻石 x10
        继续扫描...
    
    找到槽位 7: 钻石 x30
        combined = 30 + 10 = 40
        maxSize = 64
        40 <= 64，可以完全合并
        → slot[7].setCount(40)          // 槽位 7 变成 40
        → stack.setCount(0)             // stack 清空
        → moved = true
        完成！
    ↓
moved = true
    ↓
清理源槽位:
    slot[41].setByPlayer(ItemStack.EMPTY)
    ↓
return original (钻石 x64)

最终状态：
┌─────────────────────────────────────┐
│ Bot 槽位 5:   钻石 x64 ← 从 10 填满  │
│ Bot 槽位 6:   空                    │
│ Bot 槽位 7:   钻石 x40 ← 从 30 增加  │
│ 玩家槽位 41:  空        ← 全部移走   │
└─────────────────────────────────────┘
```

---

### 示例 3：Shift+点击玩家的钻石（Bot 背包满了）

```
初始状态：
┌─────────────────────────────────────┐
│ Bot 槽位 5-40: 全部装满其他物品      │
│ 玩家槽位 41:   钻石 x64              │
└─────────────────────────────────────┘

玩家 Shift+点击槽位 41
    ↓
quickMoveStack(player, 41)
    ↓
调用: moveItemStackTo(stack, 0, 41, false)
    stack = 钻石 x64 的引用
    ↓
阶段 1: 扫描 0-40，查找现有的钻石堆叠
    → 没找到钻石
    ↓
阶段 2: 扫描 0-40，查找空槽位
    → 所有槽位都有物品
    → 没找到空槽位
    ↓
moved = false
    ↓
return ItemStack.EMPTY  // 表示移动失败

最终状态：
┌─────────────────────────────────────┐
│ Bot 槽位 5-40: 保持不变              │
│ 玩家槽位 41:   钻石 x64 ← 没有移动   │
└─────────────────────────────────────┘
```

---

## 🎓 学习要点总结

### 1. **你的职责 vs 原版的职责**

```
你的职责（quickMoveStack）:
    ✅ 判断物品往哪个方向移动
    ✅ 调用 moveItemStackTo
    ✅ 清理源槽位
    ✅ 返回原始物品堆

原版的职责（moveItemStackTo）:
    ✅ 智能合并到现有堆叠
    ✅ 放入空槽位
    ✅ 处理堆叠限制
    ✅ 标记槽位变化
```

### 2. **为什么要拷贝？**

```java
ItemStack stack = slot.getItem();       // 引用
ItemStack original = stack.copy();      // 拷贝

// 因为 moveItemStackTo 会修改 stack！
moveItemStackTo(stack, ...);

// 如果不拷贝，你会丢失原始信息！
return original;  // 需要返回原始物品堆
```

### 3. **两阶段扫描的好处**

```
好处 1：智能合并
    - 不会产生多个半满的堆叠
    - 优先填满现有堆叠

好处 2：节省空间
    - 只在必要时占用新槽位
    - 保持背包整洁
```

### 4. **常见错误**

```java
// ❌ 错误 1：忘记拷贝
@Override
public ItemStack quickMoveStack(Player player, int index) {
    ItemStack stack = slot.getItem();
    // 忘记拷贝！
    moveItemStackTo(stack, ...);
    return stack;  // ❌ 返回了被修改的 stack！
}

// ❌ 错误 2：索引范围错误
moveItemStackTo(stack, 0, 77, false);  // ❌ 包含了 Bot 和玩家的所有槽位！

// ❌ 错误 3：忘记清理源槽位
@Override
public ItemStack quickMoveStack(Player player, int index) {
    ItemStack stack = slot.getItem();
    ItemStack original = stack.copy();
    moveItemStackTo(stack, ...);
    // ❌ 忘记清理！
    return original;
}

// ✅ 正确做法
@Override
public ItemStack quickMoveStack(Player player, int index) {
    Slot slot = this.slots.get(index);
    ItemStack stack = slot.getItem();
    ItemStack original = stack.copy();  // ✅ 拷贝
    
    boolean moved = moveItemStackTo(stack, startIndex, endIndex, false);  // ✅ 正确的范围
    
    if (moved) {
        // ✅ 清理源槽位
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
    }
    
    return moved ? original : ItemStack.EMPTY;  // ✅ 正确的返回值
}
```

---

## 💡 实用技巧

### 技巧 1：调试时添加日志

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    Slot slot = this.slots.get(index);
    ItemStack stack = slot.getItem();
    ItemStack original = stack.copy();
    
    System.out.println("BEFORE: slot=" + index + " stack=" + stack);
    
    boolean moved = moveItemStackTo(stack, startIndex, endIndex, false);
    
    System.out.println("AFTER: moved=" + moved + " stack=" + stack);
    
    // ... 其他逻辑
}
```

### 技巧 2：特殊物品特殊处理

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    // ... 前面的代码
    
    EquipmentSlot equipmentSlot = LivingEntity.getEquipmentSlotForItem(stack);
    
    if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR) {
        // 装备优先放到装备槽
        int armorSlot = getArmorSlotIndex(equipmentSlot);
        if (!this.slots.get(armorSlot).hasItem()) {
            moved = moveItemStackTo(stack, armorSlot, armorSlot + 1, false);
        }
    } else if (equipmentSlot == EquipmentSlot.OFFHAND) {
        // 副手物品优先放到副手槽
        // ...
    } else {
        // 普通物品
        moved = moveItemStackTo(stack, startIndex, endIndex, false);
    }
    
    // ...
}
```

### 技巧 3：测试用例

在游戏中测试以下场景：

1. **空背包移动** - 验证基本功能
2. **部分堆叠** - 验证合并逻辑
3. **满背包** - 验证失败处理
4. **装备移动** - 验证特殊物品
5. **不可堆叠物品** - 验证工具、武器等

---

## 🌊 总结

**Shift+点击快速移动的本质**：

1. **不是"拖拽"物品**
   - 而是通过修改 ItemStack 对象来实现
   - 利用 Java 的引用传递机制

2. **智能合并算法**
   - 两阶段扫描：先合并，再放入
   - 最大化利用现有堆叠

3. **职责分离**
   - 你决定**方向**（哪个区域移到哪个区域）
   - 原版处理**细节**（怎么合并、怎么放置）

4. **引用传递是关键**
   - `moveItemStackTo` 会直接修改传入的 `stack`
   - 所以必须先拷贝保存原始值
   - 返回值用于客户端显示，不是给服务端用的

**现在你应该完全理解快速移动的工作原理了！** 🎉

---

*文档创建时间：2025-01-XX*  
*适用版本：Minecraft 1.20.1 Forge*  
*项目：Alice Bot System*
