# Bot 手持物品客户端渲染问题调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/bot-held-item-rendering-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `bd9f0c3` (fix: sync bot inventory to client after transfer insert)  
**调研日期**: 2026-08-22  
**证据标准**: 代码路径格式 `文件名:行号`，API 引用注明版本，修复方案给出伪代码，无猜测

---

## 执行摘要

本报告调研 2026-08-22 23:53-23:56 客户端测试中观察到的"Bot 搬运物品途中，模型上手持物品不可见"问题。

**核心发现**：
- `bd9f0c3` 的 `syncBotInventorySlots()` 只同步了 inventory 数据（`ClientboundContainerSetSlotPacket`），**未同步 equipment 渲染**（`ClientboundSetEquipmentPacket`）
- Minecraft 客户端实体渲染器需要 **equipment packet** 才能显示手持物品模型
- **Inventory sync ≠ Equipment sync**：前者更新 GUI 数据，后者更新实体模型渲染

**结论**：
- `bd9f0c3` 解决了"客户端打开 bot inventory GUI 看不到物品"问题
- 但**未解决**"Bot 模型上手持物品不可见"问题（独立缺陷）
- 需要在 inventory 写入后额外发送 `ClientboundSetEquipmentPacket`

---

## 一、根因分析

### 1.1 问题观察（客户端日志证据）

**日志路径**: `/mnt/d/JAVA_projects/alice/.alice-supervision/client-tests/36f4014-bot-inventory-sync/evidence/latest.log`

**关键时间线**（23:54:14 - 23:54:49）：
```
[23:54:14.423] transfer_selection: source pos=(21,-60,20) code=accepted
[23:54:17.519] transfer_selection: destination pos=(14,-60,53) code=accepted
[23:54:43.926] transfer request=17dd23e2 state=PLANNED code=accepted
[23:54:44.268] 路径段 3/3: 21, -59, 20  ← Bot 到达源箱
[23:54:44.531] 路径段 1/33: 20, -60, 21  ← Bot 开始搬运（此时已从源箱拿到物品）
[23:54:49.321] task_execution_terminal terminal=COMPLETED code=done  ← 任务完成
```

用户反馈：在 23:54:44 至 23:54:49 期间（Bot 从源箱移动到目标箱），**Bot 模型上看不到手持物品**，但任务成功完成且物品正确转移。

### 1.2 bd9f0c3 的修复范围

**文件**: `ChestBotTransferPrimitive.java:228-234` (bd9f0c3)

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    for (int i = 0; i < inventory.getContainerSize(); i++) {  // 行230：同步所有槽位
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
}
```

**调用位置**: `ChestBotTransferPrimitive.java:39-42`
```java
int inserted = insertBot(context.botInventory, context.item, context.request.count());
if (inserted != context.request.count()) return post(context, fresh, TransferCodes.SOURCE_DELTA_MISMATCH);
syncBotInventorySlots(context.botInventory);  // 行41：在 insertBot 后立即同步
return prove(context, fresh, -context.request.count(), context.request.count(), 0, ...);
```

**证据 1**: `bd9f0c3` 只发送了 `ClientboundContainerSetSlotPacket`，这是 **inventory 数据同步** packet，不触发实体模型渲染。

### 1.3 Minecraft/Forge 1.20.1-47.4.10 渲染同步机制

#### 1.3.1 两种不同的同步 packet

**Packet 1: `ClientboundContainerSetSlotPacket`**
- **用途**: 同步 container/inventory 槽位数据
- **客户端效果**: 更新 GUI 中的物品显示（打开 inventory 时可见）
- **不触发**: 实体模型上的手持/穿戴物品渲染

**Packet 2: `ClientboundSetEquipmentPacket`**
- **用途**: 同步实体装备槽位（`EquipmentSlot.MAINHAND`, `OFFHAND`, `HEAD`, `CHEST`, `LEGS`, `FEET`）
- **客户端效果**: 更新实体渲染器，显示手持/穿戴物品模型
- **触发**: 其他玩家/观察者可见该实体手持物品

**证据 2**: Minecraft 1.20.1 客户端渲染器依赖 `ClientboundSetEquipmentPacket` 显示实体手持物品模型，`ClientboundContainerSetSlotPacket` 只更新 GUI 数据。

#### 1.3.2 原版 ServerPlayer 自动触发机制

**Minecraft 原版触发链**（基于 Forge 1.20.1-47.4.10 反混淆源码）：

1. `ServerPlayer.tick()` 调用父类 `LivingEntity.tick()`
2. `LivingEntity.tick()` 调用 `detectEquipmentUpdates()`
3. `detectEquipmentUpdates()` 比较当前装备与缓存的 `lastHandItem`/`lastArmorItem`
4. 检测到变化后发送 `ClientboundSetEquipmentPacket`

**伪代码**（Minecraft 原版逻辑）：
```java
// LivingEntity.detectEquipmentUpdates()
for (EquipmentSlot slot : EquipmentSlot.values()) {
    ItemStack current = this.getItemBySlot(slot);
    ItemStack last = this.lastEquipment.get(slot);
    if (!ItemStack.matches(current, last)) {
        this.level.getServer().getPlayerList().broadcast(
            null, this.getX(), this.getY(), this.getZ(), 
            64.0, this.level.dimension(),
            new ClientboundSetEquipmentPacket(this.getId(), List.of(Pair.of(slot, current)))
        );
        this.lastEquipment.put(slot, current.copy());
    }
}
```

**证据 3**: 原版 `ServerPlayer` 每 tick 自动检测装备变化并同步，但**前提是 `getItemBySlot(EquipmentSlot.MAINHAND)` 返回值发生变化**。

#### 1.3.3 BotPlayer 的问题

**关键矛盾**：
- `ChestBotTransferPrimitive.insertBot()` 直接修改 `inventory.setItem(slot, stack)` 或 `stack.grow(count)`
- **主手槽位索引** `inventory.selected` 没有改变（假设为 0）
- `getItemBySlot(EquipmentSlot.MAINHAND)` 返回 `inventory.getItem(inventory.selected)`
- 如果物品插入到**非主手槽位**（如槽位 1-35），主手槽位内容不变，`detectEquipmentUpdates()` 不触发

**场景分析**：
```java
// 假设 Bot 主手槽位 (selected = 0) 为空
// insertBot() 找到第一个空槽位（可能是槽位 0，也可能是槽位 1+）
for (int slot = 0; slot < 36 && remaining > 0; slot++) {
    ItemStack stack = inventory.getItem(slot);
    if (stack.isEmpty()) { 
        inventory.setItem(slot, new ItemStack(item, accepted));  // 如果 slot != selected，主手不变
        remaining -= accepted; 
    }
}
```

**证据 4**: 
- 如果 `insertBot()` 写入的槽位恰好是 `inventory.selected`（主手），下一 tick `detectEquipmentUpdates()` 会触发同步
- 如果写入的是**其他槽位**，主手槽位内容不变，不触发同步
- `bd9f0c3` 的 `ClientboundContainerSetSlotPacket` 只更新 GUI 数据，不触发 equipment 检测

**代码证据**:
- `ChestBotTransferPrimitive.java:197-209`：`insertBot()` 遍历所有槽位，不保证写入主手槽位
- `ChestBotTransferPrimitive.java:230`：`syncBotInventorySlots()` 循环 `i = 0` 到 `inventory.getContainerSize()`，包含主手槽位 (0)，但只发送 `ClientboundContainerSetSlotPacket`

---

### 1.4 根因结论

**根因**: `bd9f0c3` 的 `syncBotInventorySlots()` 只发送 `ClientboundContainerSetSlotPacket`，这是 **inventory 数据同步**，不触发客户端实体渲染器的 **equipment 更新**。

**技术细节**:
1. `insertBot()` 修改 inventory 后，主手槽位（`inventory.selected`）的物品可能没有变化
2. Minecraft 原版 `detectEquipmentUpdates()` 只在主手槽位内容变化时触发
3. `ClientboundContainerSetSlotPacket` 只更新 GUI 数据，不更新实体模型渲染
4. 需要显式发送 `ClientboundSetEquipmentPacket` 才能触发客户端渲染器显示手持物品

**代码路径**:
- 缺陷位置：`ChestBotTransferPrimitive.java:41` (`syncBotInventorySlots()` 调用后缺少 equipment 同步)
- 缺失调用：未发送 `ClientboundSetEquipmentPacket(bot.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, stack)))`

---

## 二、修复方案

### 2.1 方案 A：显式同步主手 equipment（推荐）

**改动位置**: `ChestBotTransferPrimitive.java:228-239`

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 同步所有 inventory 槽位（GUI 数据）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    
    // 新增：同步主手 equipment（实体渲染）
    syncBotMainHandEquipment(bot);
}

// 新增辅助方法
private static void syncBotMainHandEquipment(ServerPlayer bot) {
    ItemStack mainHand = bot.getInventory().getItem(bot.getInventory().selected);
    bot.connection.send(new ClientboundSetEquipmentPacket(
        bot.getId(),
        List.of(com.mojang.datafixers.util.Pair.of(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            mainHand
        ))
    ));
}
```

**关键 API**:
- `ClientboundSetEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> slots)`
  - 版本：Minecraft 1.20.1 / Forge 1.20.1-47.4.10
  - `entityId`: `bot.getId()` 返回实体 ID（客户端用于定位渲染实体）
  - `slots`: 装备槽位和物品对的列表，可一次同步多个槽位
  - `EquipmentSlot.MAINHAND`: 主手槽位枚举

**优点**:
- 在 `syncBotInventorySlots()` 中统一处理 inventory 和 equipment 同步
- 对调用方（`sourceChestToBot()`）透明，无需修改其他代码
- 与现有 `BotManager.syncMainHand()` 模式一致（inventory + equipment 一起同步）

**缺点**:
- 每次 transfer 额外发送 1 个 equipment packet（性能影响可忽略）

---

### 2.2 方案 B：在 TransferTask 层调用 equipment 同步

**改动位置**: `TransferTask.java:95-101`

```java
private Status sourceWrite() {
    transition(TransferLedgerData.State.SOURCE_LEG_PRE, TransferLedgerData.Location.NOT_MOVED, "", false);
    ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, bot.getInventory());
    if (!result.proven()) return result.unknownDiscrepancy() ? unknown(result) : failNotMoved(result);
    
    // 新增：同步 bot 主手 equipment
    syncBotEquipment(bot);
    
    transition(TransferLedgerData.State.IN_TRANSIT_BOT, TransferLedgerData.Location.BOT_INVENTORY, result.code() + evidence(result), false);
    phase = Phase.TO_DESTINATION; phaseStarted = level.getGameTime(); return Status.RUNNING;
}

// 新增辅助方法
private void syncBotEquipment(BotPlayer bot) {
    ItemStack mainHand = bot.getInventory().getItem(bot.getInventory().selected);
    bot.connection.send(new ClientboundSetEquipmentPacket(
        bot.getId(),
        List.of(com.mojang.datafixers.util.Pair.of(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            mainHand
        ))
    ));
}
```

**优点**:
- Primitive 保持纯数据操作，不依赖网络层
- Task 层统一管理 equipment 同步

**缺点**:
- 调用方需显式调用同步，容易遗漏
- 如果其他任务（如未来的 `PlaceTask` 转移物品）也修改 inventory，需重复实现同步逻辑

---

### 2.3 方案 C：扩展 BotManager.syncMainHand()

**改动位置**: `BotManager.java:107-113` + `ChestBotTransferPrimitive.java:41`

**BotManager.java 修改**:
```java
/** 同步主手物品到客户端（inventory GUI + equipment 渲染）。 */
public static void syncMainHand(net.minecraft.server.level.ServerPlayer bot) {
    int slot = bot.getInventory().selected;
    // 同步 inventory GUI 数据
    bot.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(slot));
    bot.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            -2, 0, slot, bot.getInventory().getItem(slot)));
    
    // 新增：同步 equipment 渲染
    bot.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
        bot.getId(),
        List.of(com.mojang.datafixers.util.Pair.of(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            bot.getInventory().getItem(slot)
        ))
    ));
}
```

**ChestBotTransferPrimitive.java 调用**:
```java
syncBotInventorySlots(context.botInventory);
// 新增：同步主手 equipment
if (context.botInventory.player instanceof ServerPlayer bot) {
    com.dddgn.alice.bot.BotManager.syncMainHand(bot);
}
```

**优点**:
- 统一 `syncMainHand()` 的语义（inventory + equipment 一起同步）
- 其他调用 `syncMainHand()` 的地方（`MineTask.java:42`、`BotManager.java:166`）也受益

**缺点**:
- `syncMainHand()` 现在只同步主手槽位，但 `syncBotInventorySlots()` 已同步所有槽位
- 导致主手槽位被重复同步（2 次 `ClientboundContainerSetSlotPacket` + 1 次 `ClientboundSetEquipmentPacket`）

---

### 2.4 推荐方案

**方案 A**（在 `syncBotInventorySlots()` 内部同步 equipment）。理由：
1. **最小改动**: 只修改 `ChestBotTransferPrimitive.java` 一个文件
2. **对调用方透明**: `TransferTask` 无需修改
3. **语义一致**: `syncBotInventorySlots()` 的契约是"完整同步 bot inventory 到客户端"，包括 GUI 和渲染
4. **避免遗漏**: 其他任务如果调用 `syncBotInventorySlots()`（未来可能复用），自动获得 equipment 同步

---

## 三、修复伪代码（精确方法调用）

```java
// ChestBotTransferPrimitive.java

private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 同步所有 inventory 槽位（GUI 数据）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            -2, 0, i, inventory.getItem(i)
        ));
    }
    
    // 新增：同步主手 equipment（实体渲染）
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    bot.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
        bot.getId(),  // 实体 ID
        java.util.List.of(
            com.mojang.datafixers.util.Pair.of(
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                mainHandStack
            )
        )
    ));
}
```

**关键 API**:
- `ClientboundSetEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> slots)`
  - 版本：Minecraft 1.20.1 / Forge 1.20.1-47.4.10
  - **Import**: `net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket`
  - **EquipmentSlot**: `net.minecraft.world.entity.EquipmentSlot.MAINHAND`
  - **Pair**: `com.mojang.datafixers.util.Pair` (Minecraft 使用的 Pair 类)

**无需修改的地方**:
- `ChestBotTransferPrimitive.java:41` 的调用位置保持不变（`syncBotInventorySlots()` 内部扩展）
- `TransferTask.java` 无需修改

---

## 四、风险评估

### 风险 1：性能开销

**描述**: 每次 `syncBotInventorySlots()` 额外发送 1 个 `ClientboundSetEquipmentPacket`。

**评估**:
- **低风险**。单个 packet 包含 1 个槽位（MAINHAND），payload 小于 inventory 的 36 个槽位
- 现有 `syncBotInventorySlots()` 已发送 36 个 `ClientboundContainerSetSlotPacket`，新增 1 个 equipment packet 的开销 < 3%
- Minecraft 原版每 tick 自动同步装备变化，手动同步不增加额外负担

**缓解**: 无需缓解，开销可忽略。

### 风险 2：与其他 bot 行为冲突

**描述**: `MineTask`、`PlaceTask` 等任务修改 bot inventory 时是否也需要 equipment 同步？

**评估**:
- **低风险**。`MineTask` 已显式调用 `BotManager.syncMainHand()` (行42)，切换工具时已同步
- `PlaceTask` 不修改 bot inventory，无需同步
- 其他任务如果未来修改 inventory，需显式调用同步方法（设计决策，不是缺陷）

**缓解**:
1. **短期**: 只修复 transfer primitive，不改变其他任务行为（保持现状）
2. **长期**: 在 `BotSession` 统一管理 inventory/equipment 变更同步，任务结束时批量同步

### 风险 3：同步槽位不一致

**描述**: 如果 `insertBot()` 写入的槽位不是主手槽位，equipment 同步显示的是旧主手物品，与 GUI 不一致。

**评估**:
- **中风险**。如果主手槽位 (selected = 0) 为空，物品插入到槽位 1，客户端看到：
  - GUI：槽位 1 有物品，槽位 0 为空
  - 实体渲染：手持空物品（因为 `inventory.getItem(inventory.selected)` 返回槽位 0）
  
**缓解方案**:
- **方案 A1**（推荐）: 修改 `insertBot()` 优先插入主手槽位
  ```java
  private static int insertBot(Inventory inventory, Item item, int count) {
      int remaining = count;
      ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
      
      // 优先尝试主手槽位
      int mainHandSlot = inventory.selected;
      ItemStack mainHandStack = inventory.getItem(mainHandSlot);
      if (mainHandStack.isEmpty() || matches(mainHandStack, id)) {
          // ... 先处理主手槽位 ...
      }
      
      // 再处理其他槽位
      for (int slot = 0; slot < 36 && remaining > 0; slot++) {
          if (slot == mainHandSlot) continue;  // 跳过已处理的主手槽位
          // ... 现有逻辑 ...
      }
  }
  ```

- **方案 A2**: 同步所有装备槽位（MAINHAND + OFFHAND），但增加 1 个额外 packet
- **方案 A3**: 只同步 equipment 不保证与 GUI 一致，由用户切换主手槽位（按数字键 1-9）触发原版同步

**推荐**: 方案 A1（优先主手槽位），与 Minecraft 原版行为一致（拾取物品优先进入主手或快捷栏）。

### 风险 4：副手（OFFHAND）物品不可见

**描述**: Bot 可能在副手持有物品（槽位 40），但本方案只同步主手。

**评估**:
- **低风险**。Alice 项目当前没有任何代码修改副手槽位
- Transfer 只操作普通 inventory 槽位 (0-35)，不包括副手 (40)

**缓解**: 如果未来需要副手渲染，扩展 `syncBotMainHandEquipment()` 为 `syncBotEquipment()`，同步 MAINHAND + OFFHAND。

---

## 五、验证方式（客户端实测矩阵）

### 前置条件
- Windows 客户端加载 Alice mod（包含本次修复）
- 假人 tango 已生成且空闲
- 准备两个原版单箱：源箱有 `minecraft:iron_ingot×3`，目标箱为空

### 测试步骤

**V1: Transfer 途中观察 Bot 手持物品模型**
1. 使用 transfer selector 选择源箱 → 目标箱
2. 执行 `/alice transfer-selection submit minecraft:iron_ingot 3`
3. Bot 到达源箱并拿到物品后，**立即观察 Bot 模型**（不要等任务完成）
4. 在 Bot 移动到目标箱途中，持续观察其手持物品
5. **预期**: Bot 手上显示铁锭模型（3D 物品或扁平纹理，取决于 Minecraft 渲染设置）
6. **判定**: 如看到手持铁锭 → PASS；如空手 → FAIL

**V2: 不同物品类型渲染**
1. 测试不同物品：`minecraft:diamond`（小物品）、`minecraft:stone`（方块）、`minecraft:diamond_pickaxe`（工具）
2. 对每种物品执行 transfer，观察 Bot 手持模型
3. **预期**: 所有物品类型都正确渲染
4. **判定**: 如所有类型都可见 → PASS

**V3: 多槽位分散存储（风险 3 验证）**
1. 源箱放入 `minecraft:iron_ingot×64`（超过单槽位上限）
2. Transfer iron_ingot×64
3. 观察 Bot 手持物品（可能分散在槽位 0-9）
4. **预期**: Bot 手上显示铁锭（与主手槽位一致）
5. **判定**: 如手持物品与主手槽位一致 → PASS；如不一致 → 需实施方案 A1（优先主手槽位）

**V4: 与 bd9f0c3 对比（回归测试）**
1. 打开 bot inventory GUI（按 E 后点击 Bot 实体）
2. 确认 GUI 中显示的物品与 Bot 手持物品一致
3. **预期**: GUI 和手持模型都正确显示
4. **判定**: 如两者一致 → PASS（bd9f0c3 的修复不受影响）

**V5: 性能监控（可选）**
1. 使用 F3 调试界面监控网络流量
2. 执行 transfer 并记录 packet 数量
3. **预期**: 每次 `sourceChestToBot()` 增加约 37 个 packet（36 个 inventory + 1 个 equipment）
4. **判定**: 如无明显卡顿或延迟 → PASS

### 验证工具
- F3 调试界面（网络统计、实体信息）
- 客户端 `latest.log`（无 `ClientboundSetEquipmentPacket` 相关错误）
- 服务端 `latest.log`（`task_execution_terminal` 确认 `COMPLETED done`）
- 截图/录屏：Bot 手持物品模型（提交给监督员）

---

## 六、与 bd9f0c3 的关系

### 6.1 bd9f0c3 解决了什么

**文件**: `ChestBotTransferPrimitive.java:228-234`

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
}
```

**解决的问题**: "Bot 拿到物品后客户端看不到，重启后才能看到"（类别 A 现象 1）
- 同步了所有 inventory 槽位数据到客户端
- 客户端打开 bot inventory GUI 时可以看到物品
- 对应调研报告：`.alice-supervision/research/category-a-phenomena-root-cause-20260822.md`

### 6.2 bd9f0c3 未解决什么

**未解决的问题**: "Bot 搬运物品途中，模型上手持物品不可见"（本报告调研问题）
- `ClientboundContainerSetSlotPacket` 只同步 GUI 数据，不触发实体渲染
- 客户端实体渲染器需要 `ClientboundSetEquipmentPacket` 更新手持物品模型
- Bot 模型上看不到手持物品，即使 GUI 中物品已正确显示

### 6.3 两个问题的关系

**独立问题**:
- 现象 1（bd9f0c3 修复）: Inventory 数据未同步 → GUI 看不到物品
- 本报告问题（待修复）: Equipment 渲染未同步 → 实体模型上看不到手持物品

**依赖关系**:
- 本次修复**依赖** `bd9f0c3` 的 `syncBotInventorySlots()` 框架
- 修复方案只需在 `bd9f0c3` 基础上**扩展**，新增 equipment 同步
- 不需要回滚或重写 `bd9f0c3` 的代码

**验证顺序**:
1. 先确认 bd9f0c3 修复有效（V4：GUI 显示物品）
2. 再验证本次修复（V1-V3：实体模型显示手持物品）

---

## 七、证据附录

### 7.1 客户端日志关键时间线（完整）

```
23:54:03.462 - tango[local] logged in (Bot 生成)
23:54:14.423 - transfer_selection: source pos=(21,-60,20) code=accepted
23:54:17.519 - transfer_selection: destination pos=(14,-60,53) code=accepted
23:54:43.926 - transfer request=17dd23e2 state=PLANNED code=accepted

23:54:44.268 - 路径段 3/3: 21, -59, 20  ← Bot 到达源箱
23:54:44.520 - tango取得了进度[来硬的]  ← Bot 拿到物品（触发进度）
23:54:44.531 - 路径段 1/33: 20, -60, 21  ← Bot 开始搬运

23:54:49.321 - task_execution_terminal kind=TransferTask terminal=COMPLETED code=done  ← 任务完成
```

**关键观察窗口**: 23:54:44.531 至 23:54:49.321（约 4.8 秒），Bot 从源箱移动到目标箱，此时应显示手持物品。

### 7.2 代码证据表

| 文件 | 行号 | 内容 | 证据类型 |
|------|------|------|----------|
| `ChestBotTransferPrimitive.java` | 41 | `syncBotInventorySlots(context.botInventory);` | bd9f0c3 同步调用 |
| `ChestBotTransferPrimitive.java` | 230 | `for (int i = 0; i < inventory.getContainerSize(); i++)` | 同步所有槽位（包括主手） |
| `ChestBotTransferPrimitive.java` | 231 | `ClientboundContainerSetSlotPacket(-2, 0, i, ...)` | 只发送 inventory packet |
| `BotManager.java` | 108-113 | `syncMainHand()` 发送 2 个 packet | 参考实现（但缺少 equipment） |
| `MineTask.java` | 42 | `BotManager.syncMainHand(bot);` | 其他任务的同步调用 |

### 7.3 Minecraft API 版本确认

**Forge 版本**: 1.20.1-47.4.10  
**Minecraft 版本**: 1.20.1  
**关键 API**:
- `net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket` (inventory 数据)
- `net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket` (equipment 渲染)
- `net.minecraft.world.entity.EquipmentSlot.MAINHAND` (主手槽位枚举)
- `com.mojang.datafixers.util.Pair` (Minecraft 使用的 Pair 类)

---

## 八、本报告不构成实现授权

本报告仅提供事实调查、根因分析和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过客户端验证矩阵 V1-V5
5. 经监督员二次审核后方可合并

**证据缺口**:
- 未在客户端实测 Bot 手持物品实时可见性（需 V1-V3）
- 未验证方案 A1（优先主手槽位）是否解决风险 3
- 未确认 `ClientboundSetEquipmentPacket` 的实际 packet 大小和性能影响（可选 V5）

**不可直接采纳项**:
- 方案 B（Task 层同步）需要更大架构调整，建议优先采用方案 A
- 方案 C（扩展 `syncMainHand()`）导致主手槽位重复同步，建议采用方案 A
- 方案 A1（优先主手槽位）需要修改 `insertBot()` 逻辑，应作为独立子任务在客户端验证后实施
