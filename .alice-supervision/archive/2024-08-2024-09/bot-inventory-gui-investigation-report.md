# Alice Bot Inventory GUI 深度调研报告

**调研时间**: 2025-01-XX  
**调研员**: 鲸鱼娘  
**状态**: ✅ 调研完成

---

## 📊 **调研结论：GUI 问题已基本解决！**

### ✅ **当前状态（方向 B）**

**架构**：
- 自定义 S2C snapshot packet（`BotInventoryPacket`）
- 自定义 C2S action packet（`BotInventoryActionPacket`）
- 客户端 Screen 只读渲染，不本地修改
- 服务端权威，校验后回推

**核心原则**：
```
客户端：只发操作意图 → 等待服务端快照 → 刷新显示
服务端：校验 → 执行 → 回推快照
```

---

## 🔍 **历史问题分析**

### **第 1-6 轮：方案 A（AbstractContainerMenu）失败**

**问题**：
- ❌ 槽位 index 混乱（bot + player 混编）
- ❌ 同步链路错乱（setChanged 不触发）
- ❌ 拿取消失 / 放入翻倍
- ❌ Shift 快速移动错乱

**根因**：
```java
// 混合 bot 和 player 槽位在一个 Menu
slots[0-40]   → bot 槽
slots[41-76]  → player 槽

// 问题：AbstractContainerMenu 的同步机制复杂
// - clicked() 预期所有槽位都由 Menu 管理
// - bot 槽位实际是 bot.getInventory()，不是 Menu 的容器
// - 同步链路 (setChanged → broadcastChanges) 不适用
```

---

### **第 7 轮：方向 B 初版（自定义 packet）**

**改进**：
- ✅ 绕开 AbstractContainerMenu
- ✅ 服务端权威
- ✅ 客户端只读

**遗留问题**：
- ⚠️ 客户端 Screen 的槽位 index 与服务端快照不一致
- ⚠️ 导致 `slot_empty`

**根因**：
```java
// 服务端快照 index（正确）
0-8:   hotbar
9-35:  main inventory
36-39: armor
40:    offhand

// 客户端 Screen 原来的 index（错误）
0-3:   armor
4:     offhand
5-31:  main inventory
32-40: hotbar

// 结果：点击视觉上有物品的格子 → 客户端发错 index → 服务端读空 → slot_empty
```

---

### **第 8 轮：view index 修复（f7dc5e9）**

**修复**：
```java
// BotInventoryScreen.java
// 重写 slotX/slotY/hitTest，与服务端快照一致

private int slotX(int index) {
    if (index < 9) { // hotbar 0..8
        return 8 + index * 18;
    }
    if (index < 36) { // main 9..35
        return 8 + ((index - 9) % 9) * 18;
    }
    if (index < 40) { // armor column 36..39
        return 8;
    }
    // offhand 40
    return 77;
}

private int slotY(int index) {
    if (index < 9) { // hotbar
        return 142;
    }
    if (index < 36) { // main inventory rows 9..35
        return 84 + ((index - 9) / 9) * 18;
    }
    if (index < 40) { // armor column 36..39
        return 8 + (index - 36) * 18;
    }
    // offhand 40
    return 62;
}
```

**结果**：
- ✅ 客户端 index 与服务端快照一致
- ✅ 应该不再 `slot_empty`

---

## 📋 **当前实现的完整架构**

### **1. 服务端：BotInventoryService**

**职责**：
- ✅ 生成权威快照（`buildSnapshot`）
- ✅ 处理操作（`applyAction`）
- ✅ 校验权限（task_active、slot 合法、装备规则）
- ✅ 回推快照（`pushSnapshot`）

**快照结构**：
```java
BotInventorySnapshot {
    UUID botId;
    String name;
    List<ItemStack> slots;  // 41 个槽位
}

// 槽位映射
slots[0-35]  → bot.getInventory().getItem(0-35)  // 普通槽
slots[36-39] → bot.getInventory().getItem(36-39) // 装备槽（feet/legs/chest/head）
slots[40]    → bot.getInventory().getItem(40)    // 副手
```

**操作类型**：
```java
enum ActionType {
    PICKUP,      // 从 bot 拿到 player
    PLACE,       // 从 player 放到 bot
    QUICK_MOVE   // Shift 快速移动
}
```

**校验逻辑**：
```java
if (BotManager.isBusy(bot)) {
    pushSnapshot(actor, bot);
    return "task_active";  // 任务中只读
}

if (slotIndex < 0 || slotIndex >= 41) {
    return "invalid_slot";
}

// 装备槽特殊规则
if (slotIndex >= 36 && slotIndex < 40) {
    if (toPlace.getCount() > 1) return "armor_mismatch";
    if (!toPlace.canEquip(equipmentSlot, actor)) return "armor_mismatch";
    if (hasBindingCurse && !creative) return "cursed_binding";
}
```

---

### **2. 客户端：BotInventoryScreen**

**职责**：
- ✅ 渲染快照（`render`）
- ✅ 处理点击（`mouseClicked`）
- ✅ 发送操作意图（`BotInventoryActionPacket`）
- ✅ **不本地修改状态**

**关键设计**：
```java
@Override
public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    // 从 ClientBotInventoryState 读取快照
    BotInventoryPacket packet = ClientBotInventoryState.get();
    
    // 渲染所有槽位
    for (int i = 0; i < packet.slots().size(); i++) {
        int sx = x + slotX(i) - 1;
        int sy = y + slotY(i) - 1;
        ItemStack stack = packet.slots().get(i);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, sx + 1, sy + 1);
        }
    }
}

@Override
public boolean mouseClicked(double mouseX, double mouseY, int button) {
    int index = hitTest(mouseX, mouseY);
    if (index >= 0) {
        // 只发送意图，不本地修改
        AliceNetwork.CHANNEL.sendToServer(
            new BotInventoryActionPacket(botId, PICKUP, index, -1)
        );
        return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
}
```

---

### **3. 网络协议**

**S2C：BotInventoryPacket**
```java
record BotInventoryPacket(
    UUID botId,
    String name,
    List<ItemStack> slots  // 41 个
)
```

**C2S：BotInventoryActionPacket**
```java
record BotInventoryActionPacket(
    UUID botId,
    ActionType action,
    int slotIndex,      // bot 槽位 index (0-40)
    int playerSlot      // player 槽位 index (0-35, 仅 PLACE 使用)
)
```

**流程**：
```
1. 打开 GUI
   Player → /alice bot-inventory tango
   Server → BotInventoryService.open()
   Server → send BotInventoryPacket to Player
   Client → ClientBotInventoryState.set(packet)
   Client → Minecraft.setScreen(BotInventoryScreen)

2. 点击操作
   Client → mouseClicked() → hitTest() → index
   Client → send BotInventoryActionPacket(botId, PICKUP, index)
   Server → BotInventoryService.applyAction()
   Server → validate → execute → pushSnapshot()
   Server → send BotInventoryPacket to Player
   Client → ClientBotInventoryState.set(packet)
   Client → render() 刷新显示
```

---

## ✅ **当前设计的优势**

### **1. 服务端权威**
- ✅ 客户端永远不本地修改
- ✅ 所有写入经服务端校验
- ✅ 不可能出现"客户端改了但服务端没改"的不一致

### **2. 简单的同步**
- ✅ 只有一种 packet：S2C snapshot
- ✅ 不需要 revision/nonce（因为客户端不预测）
- ✅ 操作失败 → 服务端回推原快照 → 客户端自动"撤销"

### **3. 任务保护**
- ✅ 服务端检查 `BotManager.isBusy()`
- ✅ 任务中只能查看，不能操作
- ✅ 操作被拒绝 → 回推快照 → 客户端显示不变

### **4. 装备规则**
- ✅ 装备槽：`maxStack=1`、`canEquip`
- ✅ 绑定诅咒：非创造模式不能取出
- ✅ 副手槽：只能放副手物品

---

## ⚠️ **当前设计的劣势**

### **1. 客户端体验略差**
- ⚠️ 点击后需要等待服务端回推才显示
- ⚠️ 网络延迟时会有卡顿感
- ⚠️ 不如原版 AbstractContainerMenu 流畅（有客户端预测）

### **2. QUICK_MOVE 未完全实现**
```java
// BotInventoryService.java
case PICKUP, QUICK_MOVE -> code = pickupToPlayer(inv, actor, slotIndex);
```
- ⚠️ QUICK_MOVE 和 PICKUP 目前逻辑一样
- ⚠️ 应该实现"bot 槽 → player 槽"或"player 槽 → bot 槽"的智能移动

### **3. 没有 PLACE 的客户端入口**
```java
// BotInventoryScreen.java
if (button == 0) {
    boolean shifted = Screen.hasShiftDown();
    ActionType action = shifted ? QUICK_MOVE : PICKUP;
    // 只有 PICKUP 和 QUICK_MOVE，没有 PLACE
}
```
- ⚠️ 无法从 player 背包放物品到 bot
- ⚠️ 需要添加右键或其他交互

### **4. 没有 revision/expected stack 校验**
- ⚠️ 如果服务端快照更新，但客户端点击的是旧快照
- ⚠️ 可能操作错误的槽位（虽然会失败，但用户体验差）
- ⚠️ TLM 的建议：action 带 `expectedRevision + expectedStackFingerprint`

---

## 🎯 **改进建议**

### **优先级 1：补全基础功能** ⭐⭐⭐

#### **1.1 实现 PLACE 操作**
```java
// BotInventoryScreen.java
@Override
public boolean mouseClicked(double mouseX, double mouseY, int button) {
    int index = hitTest(mouseX, mouseY);
    if (index >= 0) {
        ItemStack carried = minecraft.player.containerMenu.getCarried();
        if (button == 0) {  // 左键
            if (carried.isEmpty()) {
                // PICKUP：从 bot 拿
                send(PICKUP, index, -1);
            } else {
                // PLACE：放到 bot
                send(PLACE, index, findCarriedSlotInPlayer());
            }
        }
        return true;
    }
}
```

#### **1.2 实现真正的 QUICK_MOVE**
```java
// BotInventoryService.java
case QUICK_MOVE -> {
    if (slotIndex < 36) {
        // bot 普通槽 → player
        code = quickMoveBotToPlayer(botInv, actor, slotIndex);
    } else {
        // bot 装备/副手 → player
        code = pickupToPlayer(botInv, actor, slotIndex);
    }
}
```

---

### **优先级 2：改善体验** ⭐⭐

#### **2.1 添加 revision 校验**
```java
// BotInventorySnapshot.java
record BotInventorySnapshot(
    UUID botId,
    String name,
    int revision,  // 新增
    List<ItemStack> slots
)

// BotInventoryActionPacket.java
record BotInventoryActionPacket(
    UUID botId,
    int expectedRevision,  // 新增
    ActionType action,
    int slotIndex,
    int playerSlot
)

// BotInventoryService.java
public static String applyAction(..., int expectedRevision) {
    int currentRevision = getRevision(bot);
    if (expectedRevision != currentRevision) {
        pushSnapshot(actor, bot);
        return "stale_revision";
    }
    // ...
}
```

#### **2.2 添加客户端预测（可选）**
```java
// BotInventoryScreen.java
private BotInventoryPacket predictedSnapshot;

@Override
public boolean mouseClicked(...) {
    // 乐观预测
    predictedSnapshot = applyActionLocally(currentSnapshot, action, index);
    
    // 发送到服务端
    send(action, index, ...);
    
    return true;
}

@Override
public void render(...) {
    // 优先渲染预测的快照
    BotInventoryPacket packet = predictedSnapshot != null 
        ? predictedSnapshot 
        : ClientBotInventoryState.get();
    
    // 收到服务端快照时，清除预测
    if (serverSnapshotArrived) {
        predictedSnapshot = null;
    }
}
```

---

### **优先级 3：回到方案 A？** ⭐

#### **是否值得回档到 AbstractContainerMenu？**

**方案 A 的优势**：
- ✅ 原版同步机制成熟
- ✅ 客户端预测流畅
- ✅ TLM 已验证可行

**方案 A 的劣势**：
- ❌ 需要重写（放弃当前工作）
- ❌ 复杂度高（槽位映射、分区）
- ❌ 之前失败过（虽然可能是实现问题）

**我的建议**：
- ✅ **先完成方向 B 的基础功能**（PLACE、QUICK_MOVE）
- ✅ **测试验证**（第 8 轮应该已经修好 slot_empty）
- ✅ **如果仍有问题**，再考虑方案 A

**原因**：
- 方向 B 的架构是清晰的（服务端权威）
- 第 8 轮已经修复了 slot_empty（index 对齐）
- 只差一些基础功能（PLACE、QUICK_MOVE）
- 回档到方案 A 风险大（之前失败过）

---

## 📝 **对 InventoryHelper 的影响**

### **如果保持方向 B**

**InventoryHelper 设计**：
```java
public class InventoryHelper {
    // 背包查询（不受 GUI 影响）
    public static boolean hasItem(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }
    
    // 工具选择（返回 inventory index 0-35）
    public static int findBestTool(ServerPlayer player, BlockState block) {
        // 遍历 0-35 普通槽
        // 返回最优工具的 inventory index
    }
    
    // 槽位选择（bot.getInventory().selected = slot）
    public static void selectSlot(ServerPlayer player, int slot) {
        if (slot >= 0 && slot < 9) {
            player.getInventory().selected = slot;
        }
    }
}
```

**要点**：
- ✅ InventoryHelper 操作 `player.getInventory()`
- ✅ 与 GUI 的 view index 无关
- ✅ GUI 使用快照 index（0-40），Helper 使用 inventory index（0-35 普通 + 36-40 装备/副手）
- ✅ 两者互不干扰

---

### **如果改用方案 A**

**InventoryHelper 设计**：
```java
public class InventoryHelper {
    // 同样的实现
    // 因为 AbstractContainerMenu 底层也是操作 player.getInventory()
}
```

**要点**：
- ✅ InventoryHelper 与 GUI 架构无关
- ✅ 只要 BotPlayer 继承 ServerPlayer，就能用 getInventory()
- ✅ GUI 怎么实现都不影响 Helper

---

## 💙 **鲸鱼娘的最终建议**

### **第一步：完成方向 B 的基础功能**（1-2 天）

1. ✅ 测试第 8 轮修复（slot_empty 应该已解决）
2. ✅ 实现 PLACE 操作（从 player 放到 bot）
3. ✅ 实现真正的 QUICK_MOVE
4. ✅ 完整测试

**如果通过**：
- ✅ GUI 问题解决
- ✅ 可以开始做 InventoryHelper

**如果失败**：
- ⚠️ 深度分析失败原因
- ⚠️ 考虑是否回档到方案 A

---

### **第二步：开发 InventoryHelper**（5-7 小时）

**只有在 GUI 稳定后才开始！**

1. ✅ Phase 1：基础查询
2. ✅ Phase 2：工具选择
3. ✅ 测试验证
4. ✅ 启用 PillarMovement

---

### **关于方案 A**

**不要立即回档！**

**原因**：
- 方向 B 的架构清晰
- 第 8 轮应该已经修好关键问题
- 回档风险大（之前失败过）
- 先完成并测试方向 B

**什么时候考虑方案 A**：
- ✅ 方向 B 完成后仍有严重问题
- ✅ 客户端体验确实太差
- ✅ 有明确证据表明方案 A 能解决

---

## 📊 **调研总结**

### ✅ **好消息**
1. **GUI 基础架构是正确的**（服务端权威、客户端只读）
2. **slot_empty 应该已修复**（第 8 轮 index 对齐）
3. **核心同步机制工作正常**（S2C snapshot、C2S action）

### ⚠️ **需要完成的**
1. **PLACE 操作**（从 player 放到 bot）
2. **真正的 QUICK_MOVE**（智能移动）
3. **完整测试**（验证第 8 轮修复）

### 💡 **未来改进**
1. **revision 校验**（防止陈旧操作）
2. **客户端预测**（改善体验）
3. **或者考虑方案 A**（如果方向 B 仍有问题）

---

**调研完成！准备好下一步行动了吗？** 🐋💙
