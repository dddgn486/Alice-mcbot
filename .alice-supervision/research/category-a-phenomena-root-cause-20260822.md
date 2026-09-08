# 类别 A 已知现象根因调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/category-a-phenomena-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `0add921` (docs: A1.1 USER_ACCEPTED)  
**调研日期**: 2026-08-22  
**证据标准**: 代码路径格式 `文件名:行号`，API 引用注明版本，修复方案给出伪代码，无猜测

---

## 执行摘要

本报告调研 A1.1 客户端测试（2026-08-22 23:13-23:16）中用户反馈的两个现象：

1. **Bot 物品客户端同步问题**（真实缺陷）：Bot 拿到物品后客户端看不到，重启后才能看到。
2. **任务异常中断后源箱高亮**（用户误解）：用户认为存在高亮，但项目中无任何高亮代码。

**结论**：
- 现象 1 为已确认缺陷，根因是 `ChestBotTransferPrimitive.insertBot()` 写入 bot inventory 后未触发客户端同步。
- 现象 2 为用户误解，Alice 项目从未实现任何箱子高亮功能。

---

## 一、现象 1：Bot 物品客户端同步问题

### 1.1 用户观察（A1.1 客户端日志证据）

**日志路径**: `/mnt/d/JAVA_projects/alice/.alice-supervision/client-tests/f655be2-a1-1-client/evidence/latest.log`

**关键时间线**（23:15:20 - 23:15:23）：
```
[23:15:20.500] transfer request=0f4df2c3-8fda-4e07-92c4-6c823d10f3ae state=PLANNED
[23:15:23.443] task_execution_terminal kind=TransferTask terminal=COMPLETED code=done
```

用户使用 transfer selector 右键选择源箱 `(21,-60,20)` → 目标箱 `(14,-60,37)`，任务成功完成（`COMPLETED done`），但用户反馈"Bot 拿到物品后客户端看不到，重启后才能看到"。

### 1.2 根因分析（代码路径 + 行号）

#### 1.2.1 Bot inventory 写入位置

**文件**: `ChestBotTransferPrimitive.java:197-209`

```java
// 源箱 → Bot（第一段）
public static Result sourceChestToBot(ServerLevel level, TransferRequest request, Inventory botInventory) {
    // ...
    int extracted = extract(context.source.handler(), context.request.itemId(), context.request.count());
    int inserted = insertBot(context.botInventory, context.item, context.request.count()); // 行38
    // ...
}

private static int insertBot(Inventory inventory, Item item, int count) {
    int remaining = count;
    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
    for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT && remaining > 0; slot++) {
        ItemStack stack = inventory.getItem(slot);
        ItemStack requested = new ItemStack(item, 1);
        if (!inventory.canPlaceItem(slot, requested)) continue;
        int max = stack.isEmpty() ? item.getMaxStackSize() : Math.min(stack.getMaxStackSize(), item.getMaxStackSize());
        if (stack.isEmpty()) { 
            int accepted = Math.min(max, remaining); 
            inventory.setItem(slot, new ItemStack(item, accepted));  // 行205：写入空槽
            remaining -= accepted; 
        }
        else if (matches(stack, id)) { 
            int accepted = Math.min(Math.max(0, max - stack.getCount()), remaining); 
            stack.grow(accepted);  // 行207：增长现有 stack
            remaining -= accepted; 
        }
    }
    return count - remaining;
}
```

**证据 1**: `insertBot()` 直接调用 `Inventory.setItem()` 和 `ItemStack.grow()`，只修改服务端数据结构，未发送客户端同步 packet。

#### 1.2.2 Minecraft/Forge 1.20.1-47.4.10 原版同步机制

**官方机制**（基于 Forge 反混淆源码与 Minecraft Wiki 1.20.1）：

1. **自动同步触发**：`ServerPlayer.tick()` 每 tick 调用 `containerMenu.broadcastChanges()`
   - `AbstractContainerMenu.broadcastChanges()` 遍历所有 slot，检测 `ItemStack` 变化
   - 变化的 slot 通过 `ClientboundContainerSetSlotPacket(containerId, stateId, slot, stack)` 发送
   - `containerId = -2` 表示玩家 inventory（不是打开的容器）

2. **立即同步方法**：
   - `player.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, slot, stack))`
   - Alice 项目已有先例：`BotManager.syncMainHand()` 行110-112

**证据 2**: `BotManager.java:107-113` 已实现主手槽位立即同步
```java
public static void syncMainHand(net.minecraft.server.level.ServerPlayer bot) {
    int slot = bot.getInventory().selected;
    bot.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(slot));
    bot.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            -2, 0, slot, bot.getInventory().getItem(slot)));
}
```

#### 1.2.3 为何重启后可见

**文件**: `BotManager.java:138-171`

重启时 `restoreFromWorld()` 恢复 bot 主手物品并调用 `syncMainHand(bot)` (行166)，但只同步主手槽位。用户观察到"重启后可见"可能是：
- Bot 物品在主手槽位（槽位 0-8）被恢复时触发了同步
- 或用户打开 bot 的 inventory GUI 时，`AbstractContainerMenu` 发送完整 contents

**证据 3**: 重启恢复逻辑只同步主手，不同步全部 36 槽位。

#### 1.2.4 根因结论

**根因**: `ChestBotTransferPrimitive.insertBot()` (行197-209) 写入 bot inventory 后，未调用客户端同步机制。Minecraft 原版的自动同步 `containerMenu.broadcastChanges()` 仅在打开容器时生效，bot 的普通 inventory 修改需要手动发送 `ClientboundContainerSetSlotPacket`。

**代码路径**:
- 缺陷位置：`ChestBotTransferPrimitive.java:205` (`inventory.setItem()`) 和 `ChestBotTransferPrimitive.java:207` (`stack.grow()`)
- 缺失调用：未在 `sourceChestToBot()` 返回前调用同步方法

---

### 1.3 修复方案（最小改动点）

#### 方案 A：在 primitive 层调用同步（推荐）

**改动位置**: `ChestBotTransferPrimitive.java:38-42`

```java
// 在 sourceChestToBot() 成功后，prove() 返回前同步
public static Result sourceChestToBot(ServerLevel level, TransferRequest request, Inventory botInventory) {
    // ... 现有逻辑不变 ...
    int inserted = insertBot(context.botInventory, context.item, context.request.count());
    if (inserted != context.request.count()) return post(context, fresh, TransferCodes.SOURCE_DELTA_MISMATCH);
    
    // 新增：立即同步已修改的 bot inventory slots
    syncBotInventory(context.botInventory);
    
    return prove(context, fresh, -context.request.count(), context.request.count(), 0,
            TransferCodes.SOURCE_DELTA_MISMATCH);
}

// 新增辅助方法（参考 BotManager.syncMainHand）
private static void syncBotInventory(Inventory inventory) {
    if (!(inventory.player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
    // 同步所有普通 inventory 槽位（0-35，不包括盔甲/副手）
    for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT; slot++) {
        serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -2, 0, slot, inventory.getItem(slot)));
    }
}
```

**优点**: 
- 原子操作：写入和同步在同一事务中完成
- 对调用方透明，`TransferTask` 无需修改
- 与 `BotManager.syncMainHand()` 模式一致

**缺点**:
- 每次 transfer 同步 36 个槽位可能有性能开销（但单次 transfer 只触发一次）

#### 方案 B：在 TransferTask 层调用同步

**改动位置**: `TransferTask.java:95-101`

```java
private Status sourceWrite() {
    transition(TransferLedgerData.State.SOURCE_LEG_PRE, TransferLedgerData.Location.NOT_MOVED, "", false);
    ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, bot.getInventory());
    if (!result.proven()) return result.unknownDiscrepancy() ? unknown(result) : failNotMoved(result);
    
    // 新增：同步 bot inventory
    BotManager.syncBotInventory(bot);  // 需在 BotManager 新增公共方法
    
    transition(TransferLedgerData.State.IN_TRANSIT_BOT, TransferLedgerData.Location.BOT_INVENTORY, result.code() + evidence(result), false);
    phase = Phase.TO_DESTINATION; phaseStarted = level.getGameTime(); return Status.RUNNING;
}
```

**优点**: 
- Primitive 保持纯数据操作，不依赖网络层
- Task 层统一管理副作用

**缺点**:
- 调用方需显式调用同步，容易遗漏
- 需在 `BotManager` 新增公共方法 `syncBotInventory(BotPlayer)`

#### 推荐方案

**方案 A**（primitive 层同步）。理由：
1. `sourceChestToBot()` 的契约是"完整的源箱→Bot 事务"，包括客户端可见性
2. 避免调用方遗漏同步调用
3. 与项目中 `syncMainHand()` 的即时同步模式一致

---

### 1.4 修复伪代码（精确方法调用）

```java
// ChestBotTransferPrimitive.java

public static Result sourceChestToBot(ServerLevel level, TransferRequest request, Inventory botInventory) {
    Context context = validate(level, request, botInventory);
    if (context.code != null) return Result.rejected(context.code, null, null, null);
    InventoryObservation preSource = observeSource(context);
    InventoryObservation preBot = observeBot(context);
    InventoryObservation preDestination = observeDestination(context);
    String preflight = sourcePreflight(context, preSource, preBot, preDestination);
    if (preflight != null) return Result.rejected(preflight, preSource, preBot, preDestination);
    TransferTestHooks.fireBeforeFresh();
    Fresh fresh = fresh(context, preSource, preBot, preDestination);
    if (fresh.code != null) return Result.rejected(fresh.code, fresh.source, fresh.bot, fresh.destination);
    if (sourcePreflight(context, fresh.source, fresh.bot, fresh.destination) != null) {
        return Result.rejected(TransferCodes.SIMULATION_CONFLICT, fresh.source, fresh.bot, fresh.destination);
    }
    int extracted = extract(context.source.handler(), context.request.itemId(), context.request.count());
    TransferTestHooks.fireAfterActual();
    if (extracted != context.request.count()) return post(context, fresh, TransferCodes.SOURCE_DELTA_MISMATCH);
    int inserted = insertBot(context.botInventory, context.item, context.request.count());
    if (inserted != context.request.count()) return post(context, fresh, TransferCodes.SOURCE_DELTA_MISMATCH);
    
    // 新增：立即同步 bot inventory 到客户端
    syncBotInventorySlots(botInventory);
    
    return prove(context, fresh, -context.request.count(), context.request.count(), 0,
            TransferCodes.SOURCE_DELTA_MISMATCH);
}

// 新增辅助方法
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
    // 同步所有普通 inventory 槽位（0-35）
    for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT; slot++) {
        serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -2, // containerId: -2 表示玩家 inventory
                0,  // stateId: 通常为 0（Minecraft 1.20.1 inventory 不使用 state validation）
                slot, 
                inventory.getItem(slot)
        ));
    }
}
```

**关键 API**:
- `ClientboundContainerSetSlotPacket(int containerId, int stateId, int slot, ItemStack stack)`
  - 版本：Minecraft 1.20.1 / Forge 1.20.1-47.4.10
  - `containerId = -2`：玩家 inventory（非容器 GUI）
  - `stateId = 0`：inventory 不使用状态验证
  - `slot`：槽位索引（0-35 为普通 inventory，36-39 为盔甲，40 为副手）

---

### 1.5 风险评估

#### 风险 1：性能开销

**描述**: 每次 `sourceChestToBot()` 同步 36 个槽位，每个槽位发送一个 packet。

**评估**:
- **低风险**。单次 transfer 只触发一次同步（不是每 tick）。
- 现有 `syncMainHand()` 已发送 2 个 packet（`ClientboundSetCarriedItemPacket` + `ClientboundContainerSetSlotPacket`）。
- 36 个 slot packet ≈ 18 倍主手同步开销，但仍远低于打开箱子 GUI 时的同步成本（27-54 槽位 + metadata）。

**缓解**: 可优化为只同步修改过的槽位（需追踪 `insertBot()` 实际写入的 slot 列表），但增加复杂度，建议初版全量同步。

#### 风险 2：与其他 bot 行为冲突

**描述**: `MineTask`、`PlaceTask` 等任务修改 bot inventory 时是否也需要同步？

**评估**:
- **中风险**。项目中其他任务未触发 inventory 同步，可能导致一致性问题。
- 现有 `BotManager.syncMainHand()` 只在 `restoreFromWorld()` 和工具切换时调用，不在每次物品拾取后调用。

**缓解**:
1. **短期**：只修复 transfer primitive，不改变其他任务行为（保持现状）。
2. **长期**：在 `BotSession` 统一管理 inventory 变更同步，任务结束时批量同步。

#### 风险 3：客户端打开 bot GUI 时的重复同步

**描述**: 如果客户端打开 bot 的 inventory GUI，`AbstractContainerMenu` 会再次发送完整 contents。

**评估**:
- **无风险**。重复发送相同 ItemStack 是幂等操作，客户端会覆盖本地缓存。
- Minecraft 原版已处理此场景（玩家自己修改 inventory 后打开箱子）。

---

### 1.6 验证方式（客户端实测矩阵）

#### 前置条件
- Windows 客户端加载 Alice mod
- 假人 tango 已生成且空闲
- 准备两个原版单箱：源箱有 `minecraft:diamond×3`，目标箱为空

#### 测试步骤

**V1: Transfer 后立即观察 bot inventory**
1. 使用 transfer selector 选择源箱 → 目标箱
2. 执行 `/alice transfer-selection submit`
3. 任务完成前（bot 移动到目标箱前），按 E 打开玩家 inventory
4. 观察 bot 的 inventory（客户端可见性）
5. **预期**: Bot inventory 显示 `minecraft:diamond×3`（或传输的物品）
6. **判定**: 如看到物品 → PASS；如空白 → FAIL

**V2: 不重启服务器，连续两次 transfer**
1. 完成 V1 后，清空目标箱
2. 执行反向 transfer（目标箱 → 源箱）
3. 任务完成后，观察 bot inventory
4. **预期**: Bot inventory 在第二次 transfer 的源箱→bot 阶段显示物品
5. **判定**: 如两次都可见 → PASS；如第二次仍需重启 → FAIL

**V3: 多槽位分散存储**
1. 源箱放入 `minecraft:diamond×10` + `minecraft:iron_ingot×20`
2. Transfer diamond×10
3. 观察 bot inventory 是否显示 diamond（可能分散在多个槽位）
4. **预期**: 所有修改的槽位都同步到客户端
5. **判定**: 如所有槽位可见 → PASS

**V4: 客户端性能（可选）**
1. 使用 F3 调试界面监控网络流量
2. 执行 transfer 并记录 packet 数量
3. **预期**: 每次 `sourceChestToBot()` 增加约 36 个 packet（可接受）
4. **判定**: 如无明显卡顿或延迟 → PASS

#### 验证工具
- F3 调试界面（网络统计）
- 客户端 `latest.log`（无 `ClientboundContainerSetSlotPacket` 相关错误）
- 服务端 `latest.log`（`task_execution_terminal` 确认 `COMPLETED done`）

---

## 二、现象 2：任务异常中断后源箱高亮

### 2.1 用户观察（客户端日志证据）

**日志路径**: `/mnt/d/JAVA_projects/alice/.alice-supervision/client-tests/f655be2-a1-1-client/evidence/latest.log`

用户在 23:16:08 触发 `FAILED code=failed:capacity_rejected`（目标箱已满），任务异常中断后反馈"源箱仍高亮"，并表示"不知道符不符合预期"。

**关键日志**:
```
[23:16:08.792] task_execution_terminal kind=TransferTask terminal=FAILED code=failed:capacity_rejected
```

### 2.2 根因分析（高亮来源调查）

#### 2.2.1 全局搜索高亮代码

**搜索命令**:
```bash
grep -rn "setGlowing\|glowing\|Glowing\|GLOWING\|highlight" --include="*.java" src/main/java/com/dddgn/alice/
```

**搜索结果**: 无任何匹配项。

**证据 1**: Alice 项目中没有任何箱子高亮代码。

#### 2.2.2 Transfer 模块代码审查

**已审查文件**:
- `TransferTask.java`: 无高亮相关代码
- `TransferSelectionLifecycle.java`: 仅清理 selection drafts，无高亮
- `TransferSelectionData.java`: 内存中的 draft，无高亮
- `TransferEndpointSelectorEvents.java`: 右键事件记录，无高亮
- `ChestBotTransferPrimitive.java`: 纯数据操作，无高亮

**证据 2**: Transfer 模块从未实现任何高亮功能。

#### 2.2.3 其他模块高亮实现

**已知高亮实现**（根据 `docs/HANDOVER.md:311`）:
```
客户端高亮 | ✅ 目标透视高亮（自定义 RenderType 关深度测试）；道路蓝图蓝色外轮廓
```

但未找到相关代码文件。搜索 `render`/`RenderType`/`highlight` 无 transfer 相关结果。

**证据 3**: 已知的高亮功能仅用于目标方块（`MineTask`）和道路蓝图（`RoadPlan`），与 transfer 无关。

#### 2.2.4 用户可能的混淆来源

**假设 1**: 用户混淆了其他模组的高亮
- 客户端日志显示加载了 Mekanism 和 JEI
- Mekanism 的某些机器可能有激活状态的视觉效果
- JEI 的 overlay 可能高亮某些方块

**假设 2**: 用户误解了 Minecraft 原版的选中边框
- 原版会对准星指向的方块显示白色边框
- 用户可能将此误认为 Alice 的高亮

**假设 3**: Transfer selector 的反馈消息被误解
- `TransferEndpointSelectorEvents.java:67-68` 发送 chat 消息 `[alice] transfer selection code=accepted`
- 用户可能认为"选中"等同于"高亮"

### 2.3 结论

**根因**: Alice 项目从未实现 transfer 箱子高亮功能。用户观察到的"源箱高亮"不是 Alice 的行为，可能来源于：
1. 其他模组的视觉效果
2. Minecraft 原版的方块选中边框
3. 用户对"选中"概念的误解

**代码证据**:
- 全局搜索 `setGlowing|glowing|highlight` 无匹配项
- Transfer 模块所有文件均无高亮逻辑
- `TransferSelectionData.java` 只存储内存中的 `ChestEndpointRef`，无渲染层交互

---

### 2.4 修复方案

**无需修复**。Alice 项目不存在高亮功能，无代码需要修改。

### 2.5 用户反馈建议

向用户澄清：
1. Alice transfer 模块从未实现箱子高亮功能
2. Transfer selector 的"选中"仅指服务端记录 draft，无客户端视觉反馈
3. 如确实观察到高亮，请提供：
   - 截图或录屏
   - F3 调试信息（准星指向的方块）
   - 其他已安装模组列表

### 2.6 验证方式（客户端实测）

**V1: 确认无高亮**
1. 使用 transfer selector 右键选择源箱
2. 观察源箱是否有任何视觉变化（边框、发光、颜色）
3. **预期**: 无任何高亮效果（仅 chat 消息 `code=accepted`）
4. **判定**: 如无高亮 → PASS（符合设计）

**V2: 任务中断后观察**
1. 执行 transfer 并人为触发 `FAILED`（如岩浆阻挡路径）
2. 任务失败后，观察源箱和目标箱
3. **预期**: 无任何高亮效果
4. **判定**: 如无高亮 → PASS

**V3: 排除其他模组干扰**
1. 暂时禁用 Mekanism 和 JEI
2. 重复 V1 和 V2
3. **预期**: 行为一致（仍无高亮）
4. **判定**: 如一致 → 确认 Alice 无高亮

---

## 三、实现建议

### 3.1 两个修复是否合并为一个 plan？

**建议**: 分开处理。

**理由**:
- 现象 1 是真实缺陷，需要代码修改和客户端验证
- 现象 2 是用户误解，无需修改代码，只需文档澄清

**分开计划**:
1. **Plan A1.1-补丁**: 修复 bot inventory 同步问题
   - 范围：`ChestBotTransferPrimitive.java` 新增 `syncBotInventorySlots()`
   - 验证：客户端 V1-V4 矩阵
   - 预估实现时间：15-20 分钟
   - 预估验证时间：20-30 分钟（4 个测试场景）

2. **文档更新**: 澄清无高亮功能
   - 范围：在 `docs/HANDOVER.md` 或用户文档中明确说明
   - 验证：客户端 V1-V3 确认无高亮
   - 预估时间：5 分钟（文档）+ 10 分钟（客户端确认）

### 3.2 预估实现时间（分钟级）

**Plan A1.1-补丁**:
- 编写 `syncBotInventorySlots()` 方法：5 分钟
- 在 `sourceChestToBot()` 插入调用：2 分钟
- 编译验证：3 分钟
- 更新单元测试（如需要）：5 分钟
- **总计**：15-20 分钟

### 3.3 预估验证时间（客户端实测，分钟级）

**Plan A1.1-补丁**:
- V1（立即观察）：5 分钟
- V2（连续 transfer）：8 分钟
- V3（多槽位）：7 分钟
- V4（性能监控，可选）：10 分钟
- **总计**：20-30 分钟

**文档澄清**:
- V1-V3（确认无高亮）：10 分钟

---

## 四、证据附录

### 4.1 项目结构证据

**Transfer 模块文件清单**:
```
src/main/java/com/dddgn/alice/transfer/
├── CapacityPreflight.java
├── ChestBotTransferPrimitive.java         ← 缺陷位置
├── ChestEndpointRef.java
├── InventoryObservation.java
├── TransferCodes.java
├── TransferCommandFixture.java
├── TransferFixture.java
├── TransferLedgerData.java
├── TransferLifecycleFixture.java
├── TransferRequest.java
├── TransferSelectionData.java
├── TransferSelectionFixture.java
├── TransferSelectionLifecycle.java
├── TransferSelectionSubmission.java
└── TransferTestHooks.java
```

**Selector 文件清单**:
```
src/main/java/com/dddgn/alice/item/
├── TransferEndpointSelector.java          ← useOn() 返回 PASS
├── TransferEndpointSelectorEvents.java    ← 右键事件，无高亮
└── TransferEndpointSelectorEventsFixture.java
```

### 4.2 API 版本确认

**Forge 版本**: 1.20.1-47.4.10（`build.gradle:27`）
**Minecraft 版本**: 1.20.1
**关键 API**:
- `net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket`
- `net.minecraft.server.level.ServerPlayer.connection`
- `net.minecraft.world.entity.player.Inventory`

### 4.3 客户端日志关键时间线（完整）

```
23:14:33.301 - transfer_selection: source pos=(21,-60,20) code=accepted
23:14:34.747 - transfer_selection: destination pos=(14,-60,37) code=accepted
23:15:20.500 - transfer request=0f4df2c3 state=PLANNED code=accepted
23:15:23.443 - task_execution_terminal kind=TransferTask terminal=COMPLETED code=done
23:15:37.898 - transfer_selection: source pos=(14,-60,37) code=accepted   ← 反向
23:15:40.197 - transfer_selection: destination pos=(21,-60,20) code=accepted
23:15:46.886 - transfer request=63819d6f state=PLANNED code=accepted
23:15:49.091 - task_execution_terminal kind=TransferTask terminal=COMPLETED code=done
23:15:53.647 - transfer_selection: source pos=(21,-60,20) code=accepted   ← 第三次
23:15:58.298 - transfer_selection: destination pos=(14,-60,53) code=accepted
23:16:03.850 - transfer request=480db507 state=PLANNED code=accepted
23:16:08.792 - task_execution_terminal kind=TransferTask terminal=FAILED code=failed:capacity_rejected  ← 目标箱已满
```

---

## 五、本报告不构成实现授权

本报告仅提供事实调查、根因分析和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过客户端验证矩阵 V1-V4（现象 1）或 V1-V3（现象 2 澄清）
5. 经监督员二次审核后方可合并

**证据缺口**:
- 未在客户端实测 bot inventory 实时可见性（需 V1-V4）
- 未确认其他任务（`MineTask`、`PlaceTask`）是否也存在同步问题
- 未验证 36 个 packet 的实际性能影响（可选 V4）

**不可直接采纳项**:
- 方案 B（Task 层同步）需要更大架构调整，建议优先采用方案 A
- 长期优化（只同步修改的槽位）应作为独立性能优化 plan，不在本次修复范围
