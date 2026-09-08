# Alice 背包管理系统设计方案

**创建时间**: 2025-01-XX  
**状态**: 📝 设计中

---

## 🎯 **目标**

创建一个统一的背包管理系统（InventoryHelper），提供：
1. ✅ **背包检查**：空间、物品查找、数量统计
2. ✅ **物品操作**：添加、移除、移动、交换
3. ✅ **工具选择**：自动选择最优工具
4. ✅ **装备管理**：穿戴、卸下装备
5. ✅ **背包整理**：排序、合并堆叠

---

## 📊 **当前状态分析**

### **已有的背包相关代码**

#### **1. BotPlayer（继承 ServerPlayer）**
```java
public class BotPlayer extends ServerPlayer {
    // 继承 ServerPlayer 的所有背包方法：
    // - getInventory() → Inventory（36 格背包 + 4 装备槽 + 1 副手）
    // - getItemInHand(InteractionHand) → 主手/副手物品
    // 等等...
}
```

**分析**：
- ✅ BotPlayer 已经有完整的背包系统（继承自 ServerPlayer）
- ✅ 不需要自己实现底层存储
- ✅ 需要的是**便捷的查询和操作接口**

---

#### **2. 现有使用方式**

**MineTask**：
```java
// 直接设置物品到槽位
bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
```

**TransferTask**：
```java
// 使用 ChestBotTransferPrimitive
ChestBotTransferPrimitive.sourceChestToBot(level, request, bot.getInventory());
```

**问题**：
- ❌ 每次都要写 `bot.getInventory().xxx`
- ❌ 没有统一的查询接口
- ❌ 没有工具选择逻辑
- ❌ 没有空间检查
- ❌ 硬编码物品（`DIAMOND_PICKAXE`）

---

#### **3. Bot Inventory GUI（已实现）**

**文件**：
- `BotInventoryScreen.java`（客户端）
- `BotInventoryMenu.java`（服务端）
- `BotInventoryService.java`（服务逻辑）
- `BotInventorySnapshot.java`（快照）

**功能**：
- ✅ 查看 Bot 背包
- ✅ 36 槽位 + 4 装备槽 + 1 主手槽
- ✅ 任务执行时只读保护
- ✅ 空闲时可交互

**状态**：已通过测试（`f0ef6fc`）

---

### **参考：车万女仆模组的背包界面**

你提到的车万女仆模组特点：
- ✅ **主手槽 + 副手槽**：明确当前使用物品
- ✅ **背包槽**：存储空间
- ✅ **饰品槽**：适配其他模组（如 Curios、Trinkets）
- ✅ **设置界面**：任务状态、配置等

**启示**：
1. Alice 的背包系统应该支持**扩展槽位**（模组兼容）
2. GUI 应该能显示**当前正在使用的物品**（主手槽高亮）
3. 需要区分**工作槽**（任务使用）和**存储槽**（物品存储）

---

## 🏗️ **设计方案**

### **核心类：InventoryHelper**

```java
/**
 * Bot 背包管理工具类。
 * 
 * <p>提供便捷的背包查询和操作接口，封装 ServerPlayer.getInventory() 的复杂性。
 * 
 * <p>设计原则：
 * - 所有方法都是静态的（工具类）
 * - 不修改底层存储（使用 Inventory API）
 * - 提供高级查询（如"最优工具"）
 * - 线程安全（服务端单线程）
 */
public class InventoryHelper {
    
    // ==================== 背包检查 ====================
    
    /**
     * 检查背包是否有空槽位。
     */
    public static boolean hasEmptySlot(ServerPlayer player);
    
    /**
     * 获取空槽位数量。
     */
    public static int countEmptySlots(ServerPlayer player);
    
    /**
     * 检查是否有指定物品。
     */
    public static boolean hasItem(ServerPlayer player, Item item);
    
    /**
     * 统计指定物品的总数量。
     */
    public static int countItem(ServerPlayer player, Item item);
    
    /**
     * 查找物品的第一个槽位。
     * 
     * @return 槽位索引，-1 表示未找到
     */
    public static int findItem(ServerPlayer player, Item item);
    
    /**
     * 查找所有包含指定物品的槽位。
     */
    public static List<Integer> findAllItems(ServerPlayer player, Item item);
    
    // ==================== 工具选择 ====================
    
    /**
     * 查找挖掘指定方块的最优工具。
     * 
     * @return 最优工具槽位，-1 表示没有工具（只能用手挖）
     */
    public static int findBestTool(ServerPlayer player, BlockState block);
    
    /**
     * 计算工具挖掘方块的速度。
     */
    public static float getDestroySpeed(ItemStack tool, BlockState block);
    
    /**
     * 检查工具是否适合挖掘方块（是否掉落物品）。
     */
    public static boolean isCorrectTool(ItemStack tool, BlockState block);
    
    // ==================== 物品操作 ====================
    
    /**
     * 添加物品到背包。
     * 
     * @return 实际添加的数量
     */
    public static int addItem(ServerPlayer player, ItemStack stack);
    
    /**
     * 从背包移除物品。
     * 
     * @return 实际移除的数量
     */
    public static int removeItem(ServerPlayer player, Item item, int count);
    
    /**
     * 交换两个槽位的物品。
     */
    public static void swapSlots(ServerPlayer player, int slot1, int slot2);
    
    /**
     * 将物品移动到主手槽（切换到指定槽位）。
     */
    public static void selectSlot(ServerPlayer player, int slot);
    
    // ==================== 装备管理 ====================
    
    /**
     * 穿戴装备。
     */
    public static boolean equipItem(ServerPlayer player, EquipmentSlot slot, ItemStack item);
    
    /**
     * 卸下装备。
     */
    public static ItemStack unequipItem(ServerPlayer player, EquipmentSlot slot);
    
    /**
     * 检查是否穿戴了装备。
     */
    public static boolean hasEquipment(ServerPlayer player, EquipmentSlot slot);
    
    // ==================== 背包整理 ====================
    
    /**
     * 合并相同物品的堆叠。
     */
    public static void mergeStacks(ServerPlayer player);
    
    /**
     * 按物品类型排序背包。
     */
    public static void sortInventory(ServerPlayer player);
}
```

---

### **Minecraft 背包结构**

**ServerPlayer.getInventory() 返回 Inventory**：

```java
public class Inventory {
    // 36 格背包槽位（0-35）
    // - 0-8：快捷栏（hotbar）
    // - 9-35：主背包
    public List<ItemStack> items;  // 36 格
    
    // 4 格装备槽位（100-103）
    // - 100：脚部（FEET）
    // - 101：腿部（LEGS）
    // - 102：胸部（CHEST）
    // - 103：头部（HEAD）
    public List<ItemStack> armor;  // 4 格
    
    // 1 格副手槽位（150）
    public List<ItemStack> offhand;  // 1 格
    
    // 当前选中的快捷栏槽位（0-8）
    public int selected;
}
```

**槽位编号**：
- `0-8`：快捷栏（hotbar）
- `9-35`：主背包
- `36-39`：装备槽（脚、腿、胸、头）
- `40`：副手

---

### **工具选择逻辑**

**挖掘速度计算**：
```java
public static float getDestroySpeed(ItemStack tool, BlockState block) {
    // 1. 基础速度
    float speed = tool.getDestroySpeed(block);
    
    // 2. 效率附魔加成
    int efficiency = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, tool);
    if (efficiency > 0 && speed > 1.0F) {
        speed += (efficiency * efficiency + 1);
    }
    
    return speed;
}
```

**最优工具选择**：
```java
public static int findBestTool(ServerPlayer player, BlockState block) {
    Inventory inv = player.getInventory();
    int bestSlot = -1;
    float bestSpeed = 1.0F;  // 手挖速度
    
    // 遍历快捷栏 + 主背包
    for (int i = 0; i < 36; i++) {
        ItemStack stack = inv.getItem(i);
        if (stack.isEmpty()) continue;
        
        // 检查是否正确工具（会掉落物品）
        if (!isCorrectTool(stack, block)) continue;
        
        // 计算挖掘速度
        float speed = getDestroySpeed(stack, block);
        if (speed > bestSpeed) {
            bestSpeed = speed;
            bestSlot = i;
        }
    }
    
    return bestSlot;
}
```

---

## 📝 **实施计划**

### **Phase 1：基础查询** ⭐⭐⭐（优先级最高）

**文件**：`InventoryHelper.java`

**功能**：
- ✅ `hasEmptySlot()` / `countEmptySlots()`
- ✅ `hasItem()` / `countItem()`
- ✅ `findItem()` / `findAllItems()`

**目标**：
- 让 Task 能够查询背包状态
- 为 PillarMovement 提供"是否有方块"检查

**工作量**：2-3 小时

---

### **Phase 2：工具选择** ⭐⭐⭐

**功能**：
- ✅ `findBestTool()`
- ✅ `getDestroySpeed()`
- ✅ `isCorrectTool()`

**目标**：
- 自动选择最优挖掘工具
- 替换 MineTask 的硬编码 `DIAMOND_PICKAXE`

**工作量**：3-4 小时

---

### **Phase 3：物品操作** ⭐⭐

**功能**：
- ✅ `addItem()` / `removeItem()`
- ✅ `swapSlots()` / `selectSlot()`

**目标**：
- 提供便捷的物品操作接口
- 为物品转移任务提供支持

**工作量**：2-3 小时

---

### **Phase 4：装备管理** ⭐

**功能**：
- ✅ `equipItem()` / `unequipItem()`
- ✅ `hasEquipment()`

**目标**：
- 支持自动穿戴装备
- 为战斗系统做准备

**工作量**：2-3 小时

---

### **Phase 5：背包整理** ⭐

**功能**：
- ✅ `mergeStacks()`
- ✅ `sortInventory()`

**目标**：
- 优化背包使用效率
- 提升用户体验

**工作量**：3-4 小时

---

## 🎯 **立即解锁的功能**

完成 Phase 1 + Phase 2 后：

### **1. 启用 PillarMovement** ✨

```java
public class PillarMovement {
    @Override
    public boolean isValid() {
        // 检查是否有方块
        if (!InventoryHelper.hasItem(bot, Items.DIRT)) {
            return false;
        }
        
        // 检查是否有空间（需要放置方块）
        if (!MovementHelper.canPlaceBlock(level, to.below())) {
            return false;
        }
        
        return true;
    }
}
```

### **2. 优化 MineTask** ✨

```java
public class MineTask {
    @Override
    public Status tick() {
        // ❌ 旧方式：硬编码
        // bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
        
        // ✅ 新方式：自动选择最优工具
        int bestTool = InventoryHelper.findBestTool(bot, targetBlock);
        if (bestTool != -1) {
            InventoryHelper.selectSlot(bot, bestTool);
        }
        
        // 继续挖掘...
    }
}
```

---

## 🚀 **下一步**

**建议顺序**：
1. ✅ **Phase 1**：基础查询（2-3 小时）
2. ✅ **Phase 2**：工具选择（3-4 小时）
3. ✅ **测试**：启用 PillarMovement、优化 MineTask
4. ⏳ **Phase 3-5**：按需实施

---

## 💙 **鲸鱼娘的建议**

**先做 Phase 1 和 Phase 2！** ✨

**原因**：
1. ✅ **立即可用**：完成后就能启用 PillarMovement
2. ✅ **影响最大**：几乎所有任务都需要背包查询和工具选择
3. ✅ **风险较低**：主要是查询，不修改状态
4. ✅ **测试简单**：容易验证正确性

**预计完成时间**：5-7 小时

---

**准备好开始了吗？** 🐋💙
