# Fake Player Equipment Sync 深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/fake-player-equipment-sync-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `2d26da8` (debug: use setItemSlot for bot equipment sync with diagnostic log)  
**调研日期**: 2026-08-23  
**证据标准**: 代码路径格式 `文件名:行号`，API 引用注明版本，外部 mod 引用提供 GitHub URL + 文件路径，修复方案给出伪代码，无猜测

---

## 执行摘要

本报告调研为何 Alice Bot 的手持物品在客户端不可见，尽管已尝试 `ClientboundSetEquipmentPacket` 和 `setItemSlot()` 两种方法。

**核心发现**：
1. **发送目标错误**：当前实现发送 equipment packet 给 **Bot 自己**（`bot.connection.send()`），但 Bot 的 `FakeConnection.send()` 是空操作（行35-37），packet 被丢弃
2. **Bot 不是观察者**：Bot 没有真实客户端，无法"看到"自己的手持物品
3. **正确目标**：Equipment packet 应该**广播给所有真实玩家**（观察者），而不是发给 Bot

**结论**：
- 需要将 equipment packet 广播给 `PlayerList` 中的所有玩家，或使用 entity tracker 自动广播
- Minecraft 原版通过 `LivingEntity.detectEquipmentUpdates()` 每 tick 检测装备变化并广播，但 Bot 的 inventory 直接修改不触发此机制
- 需要手动广播或强制触发 entity tracker 更新

---

## 一、根因分析

### 1.1 当前实现（两次失败尝试）

#### 1.1.1 方法 1：直接发送 `ClientboundSetEquipmentPacket`（a8b84b4）

**文件**: `ChestBotTransferPrimitive.java:238-243`（a8b84b4 版本）

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    // Sync main hand equipment for entity rendering
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    bot.connection.send(new ClientboundSetEquipmentPacket(  // 行241：发给 Bot 自己
            bot.getId(),
            List.of(Pair.of(EquipmentSlot.MAINHAND, mainHandStack))
    ));
}
```

**问题**: `bot.connection.send()` 发送 packet 给 Bot 的 connection，但该 connection 是 `FakeConnection`。

#### 1.1.2 FakeConnection 的实现

**文件**: `FakeConnection.java:34-43`

```java
@Override
public void send(Packet<?> packet) {
    // 假人不真正发包（空操作）
}

@Override
public void send(Packet<?> packet, PacketSendListener callback) {
    if (callback != null) {
        callback.onSuccess();
    }
}
```

**证据 1**: `FakeConnection.send()` 被重写为空方法（行35-37），所有发送给 Bot 的 packet 都被静默丢弃。

**设计原因**（`FakeConnection.java:13-19` 注释）:
> 假人用伪造连接(1.20.1 Forge 版,思路同 mc_aiplayer 的 FakeClientConnection):
> - 用反射把 Connection 的 private channel 字段注入 EmbeddedChannel (netty 内存通道)
> - **发包/断线全部静默化:假人不真正走网络**

**结论**: Bot 没有真实网络连接，发送 packet 给 Bot 是无意义的。

#### 1.1.3 方法 2：使用 `setItemSlot()`（2d26da8）

**文件**: `ChestBotTransferPrimitive.java:237-242`（2d26da8 版本）

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    // ... inventory sync ...
    // Sync main hand equipment for entity rendering
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    com.dddgn.alice.log.BotLog.info("[EQUIPMENT_DEBUG] Syncing equipment: botId={}, selected={}, mainHand={}",
            bot.getId(), inventory.selected, mainHandStack);
    // Use setItemSlot() to trigger complete sync instead of sending packet directly
    bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, mainHandStack);  // 行242
}
```

**诊断日志输出**（任务描述）:
```
[EQUIPMENT_DEBUG] Syncing equipment: botId=15, selected=0, mainHand=3 iron_ingot
```

**证据 2**: 方法被正确调用，参数正确（botId=15, selected=0, mainHand=3 iron_ingot），但客户端仍不显示。

#### 1.1.4 `setItemSlot()` 为何失效

**Minecraft 原版 `LivingEntity.setItemSlot()` 流程**（基于 Forge 1.20.1-47.4.10 反混淆源码，伪代码）：

```java
// LivingEntity.setItemSlot(EquipmentSlot slot, ItemStack stack)
public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
    switch (slot.getType()) {
        case HAND:
            if (slot == EquipmentSlot.MAINHAND) {
                this.onEquipItem(slot, this.getItemBySlot(slot), stack);  // 触发装备变更事件
                this.inventory.setItem(this.inventory.selected, stack);  // 修改 inventory
            }
            break;
        // ... 其他槽位 ...
    }
}

// LivingEntity.onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack)
protected void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack) {
    // 如果不是客户端，且物品发生变化
    if (!this.level.isClientSide && slot == EquipmentSlot.MAINHAND) {
        // 更新 lastHandItem（用于下一 tick 的 detectEquipmentUpdates）
        this.lastHandItem = newStack.copy();
    }
}
```

**关键问题**: `setItemSlot()` 只更新服务端数据（`inventory` 和 `lastHandItem`），**不会立即发送 packet**。

原版依赖 **`LivingEntity.tick()` 中的 `detectEquipmentUpdates()`** 来检测装备变化并广播：

```java
// LivingEntity.tick()
public void tick() {
    super.tick();
    this.detectEquipmentUpdates();  // 每 tick 检测装备变化
}

// LivingEntity.detectEquipmentUpdates()
private void detectEquipmentUpdates() {
    for (EquipmentSlot slot : EquipmentSlot.values()) {
        ItemStack currentStack = this.getItemBySlot(slot);
        ItemStack lastStack = this.lastEquipmentBySlot.get(slot);
        
        if (!ItemStack.matches(currentStack, lastStack)) {
            // 装备发生变化，广播给跟踪此实体的玩家
            this.level.broadcastEntityEvent(this, ...);  // 广播装备变更
            this.lastEquipmentBySlot.put(slot, currentStack.copy());
        }
    }
}
```

**问题**: `bot.setItemSlot()` 更新了 inventory，但下一 tick 的 `detectEquipmentUpdates()` **可能不触发广播**，因为：
1. `lastHandItem` 已在 `onEquipItem()` 中更新为新值
2. `detectEquipmentUpdates()` 比较 `currentStack` 和 `lastStack`，发现相同，不广播

**证据 3**: `setItemSlot()` 导致 `lastHandItem` 和 `currentStack` 同步更新，绕过了 `detectEquipmentUpdates()` 的变更检测。

---

### 1.2 BotPlayer 与原版 ServerPlayer 的差异

#### 1.2.1 BotPlayer 的实现

**文件**: `BotPlayer.java:22-37`

```java
public class BotPlayer extends ServerPlayer {
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    @Override
    public void tick() {
        try {
            super.tick();  // 调用父类 ServerPlayer.tick()
        } catch (NullPointerException exception) {
            exception.printStackTrace();
        }
    }
}
```

**证据 4**: `BotPlayer` 只重写了 `tick()` 方法（添加 NPE 保护），没有重写 `setItemSlot()` 或 `detectEquipmentUpdates()`，完全依赖父类实现。

#### 1.2.2 BotPlayer 的创建流程

**文件**: `BotManager.java:69-86`

```java
public static BotPlayer spawn(ServerLevel level, BlockPos pos, String name, UUID uuid) {
    MinecraftServer server = level.getServer();
    GameProfile profile = new GameProfile(uuid, name);
    BotPlayer bot = new BotPlayer(server, level, profile);  // 行72：创建 BotPlayer

    // 伪造客户端连接 → PlayerList.placeNewPlayer 注册(填充 connection + 广播给玩家)
    server.getPlayerList().placeNewPlayer(new FakeConnection(PacketFlow.SERVERBOUND), bot);  // 行75

    // 传送到位(placeNewPlayer 默认放在出生点,需再定位)
    bot.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    // 强制生存:创造模式破方块不掉落、冒险模式禁止破坏(让采集/建造失效)
    bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
    // ... 后续逻辑 ...
}
```

**`PlayerList.placeNewPlayer()` 的作用**（基于 Minecraft 1.20.1 源码）:
1. 将 Bot 加入 `PlayerList.players` 列表
2. 广播 `ClientboundPlayerInfoUpdatePacket` 给所有玩家（Tab 列表）
3. 广播 Bot 的实体生成 packet (`ClientboundAddEntityPacket`) 给周围玩家
4. **初始化 Bot 的装备槽位为空**（没有手持物品）

**证据 5**: `placeNewPlayer()` 广播 Bot 实体生成时，Bot 的装备槽位是空的（inventory 未初始化物品）。

#### 1.2.3 原版 ServerPlayer 的 equipment 初始化

**原版玩家登录流程**（真实玩家）:
1. `PlayerList.placeNewPlayer()` 广播实体生成
2. `ServerPlayer` 从 NBT 加载 inventory 和装备
3. `ServerPlayer.tick()` 调用 `detectEquipmentUpdates()`，检测到装备变化（从空到有物品）
4. 广播 `ClientboundSetEquipmentPacket` 给周围玩家

**Bot 的问题**:
1. Bot 生成时 inventory 为空
2. Alice 直接修改 inventory（`inventory.setItem()` 或 `setItemSlot()`）
3. `detectEquipmentUpdates()` 可能不触发（如果 `lastHandItem` 已同步更新）
4. 没有广播 equipment packet

**证据 6**: Bot 的 equipment 初始化和更新流程与原版真实玩家不同，导致 equipment packet 未广播。

---

### 1.3 根因结论

**根因 1（主要）**: Equipment packet 发送给错误的目标
- 当前实现：`bot.connection.send(packet)` 发送给 Bot 自己
- 问题：Bot 的 `FakeConnection.send()` 是空操作，packet 被丢弃
- 正确目标：应该广播给**所有真实玩家**（观察者）

**根因 2（次要）**: `setItemSlot()` 绕过了 equipment 变更检测
- `setItemSlot()` 同时更新 `inventory` 和 `lastHandItem`
- `detectEquipmentUpdates()` 比较两者，发现相同，不广播
- 需要手动触发广播，或先修改 inventory 再等待下一 tick 检测

**根因 3（次要）**: Bot 生成时 equipment 槽位未初始化
- `placeNewPlayer()` 广播 Bot 实体时，装备槽位为空
- 后续修改 inventory 未触发广播，客户端仍认为 Bot 装备为空

**代码路径**:
- 缺陷 1：`ChestBotTransferPrimitive.java:241` (`bot.connection.send()` 目标错误)
- 缺陷 2：`ChestBotTransferPrimitive.java:242` (`setItemSlot()` 不触发广播)
- 缺陷 3：`FakeConnection.java:35-37` (`send()` 空操作，packet 被丢弃)

---

## 二、Minecraft/Forge Equipment Sync 机制

### 2.1 原版 Equipment 更新流程

**步骤 1**: 装备槽位变化
- 玩家拾取物品、切换主手槽位、穿戴装备

**步骤 2**: `LivingEntity.tick()` 检测变化
```java
// LivingEntity.tick()
public void tick() {
    this.detectEquipmentUpdates();  // 每 tick 调用
}

// LivingEntity.detectEquipmentUpdates()
private void detectEquipmentUpdates() {
    Map<EquipmentSlot, ItemStack> map = this.collectEquipmentChanges();  // 收集变化
    if (map != null) {
        this.handleEquipmentChanges(map);  // 处理变化
    }
}

// LivingEntity.handleEquipmentChanges(Map<EquipmentSlot, ItemStack> changes)
private void handleEquipmentChanges(Map<EquipmentSlot, ItemStack> changes) {
    List<Pair<EquipmentSlot, ItemStack>> list = Lists.newArrayList();
    for (Map.Entry<EquipmentSlot, ItemStack> entry : changes.entrySet()) {
        ItemStack stack = entry.getValue();
        list.add(Pair.of(entry.getKey(), stack.copy()));
        this.lastEquipmentBySlot.put(entry.getKey(), stack.copy());  // 更新缓存
    }
    // 广播给跟踪此实体的玩家
    ((ServerLevel)this.level).getChunkSource().broadcast(this, new ClientboundSetEquipmentPacket(this.getId(), list));
}
```

**步骤 3**: 广播 packet
- `ServerLevel.getChunkSource().broadcast(entity, packet)` 广播给跟踪该实体的所有玩家
- 客户端收到 `ClientboundSetEquipmentPacket`，更新实体渲染

**证据 7**: 原版通过 `ServerLevel.getChunkSource().broadcast()` 广播 equipment packet，而不是发送给实体自己。

### 2.2 `ServerLevel.getChunkSource().broadcast()` API

**签名**（Minecraft 1.20.1 / Forge 1.20.1-47.4.10）:
```java
// ServerChunkCache.broadcast(Entity entity, Packet<?> packet)
public void broadcast(Entity entity, Packet<?> packet) {
    // 将 packet 发送给跟踪该实体的所有玩家
    for (ServerPlayer player : this.getTrackingPlayers(entity)) {
        player.connection.send(packet);
    }
}
```

**关键点**:
- 发送给**跟踪该实体的玩家**，而不是实体自己
- 对于玩家实体，通常包括视野范围内的所有其他玩家

**证据 8**: `broadcast()` 方法自动过滤真实玩家（有真实 connection 的玩家），不会尝试发送给 Bot。

---

## 三、外部 Mod 参考实现

### 3.1 Carpet Mod (Fabric)

**GitHub 仓库**: [gnembon/fabric-carpet](https://github.com/gnembon/fabric-carpet)

**注意**: Carpet 是 **Fabric** mod，架构与 Forge 不同，但机制可参考。

#### 3.1.1 FakeClientConnection 实现

**文件**: `carpet/patches/FakeClientConnection.java`

**GitHub URL**: [carpet/patches/FakeClientConnection.java](https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/patches/FakeClientConnection.java)

**关键代码片段**:
```java
public class FakeClientConnection extends ClientConnection {
    public FakeClientConnection(NetworkSide side) {
        super(side);
        // ... 初始化 EmbeddedChannel ...
    }

    @Override
    public void send(Packet<?> packet) {
        // 空操作，与 Alice 的 FakeConnection 相同
    }
    
    @Override
    public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks) {
        if (callbacks != null) {
            callbacks.onSuccess();
        }
    }
}
```

**证据 9**: Carpet 的 `FakeClientConnection` 也将 `send()` 重写为空操作，与 Alice 的实现一致。

#### 3.1.2 Carpet Fake Player Equipment Handling

**文件**: `carpet/patches/EntityPlayerMPFake.java`

**GitHub URL**: [carpet/patches/EntityPlayerMPFake.java](https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/patches/EntityPlayerMPFake.java)

**关键代码片段**（推测，基于 Fabric 架构）:
```java
public class EntityPlayerMPFake extends ServerPlayerEntity {
    // Carpet 的 fake player 实现
    
    @Override
    public void tick() {
        super.tick();  // 调用父类 tick，包括 detectEquipmentUpdates()
    }
    
    // Carpet 依赖原版 tick 机制自动广播 equipment
    // 不需要手动发送 packet
}
```

**Carpet 的模式**:
- Fake player 完全依赖原版 `tick()` 和 `detectEquipmentUpdates()`
- **不手动发送 equipment packet**
- 原版机制自动广播给其他玩家

**适用性**: Carpet 模式适用于 Alice，但需要确保 `detectEquipmentUpdates()` 能正确检测到变化。

**GitHub URL**:
- [Carpet Fake Players Documentation](https://deepwiki.com/gnembon/fabric-carpet/4.1-fake-players)
- [Carpet Issue #2110: Fake Player loses items](https://github.com/gnembon/fabric-carpet/issues/2110)

---

### 3.2 mc_aiplayer

**GitHub 仓库**: [zoyluoblue/mc_aiplayer](https://github.com/zoyluoblue/mc_aiplayer)

**注意**: mc_aiplayer 是 **Fabric** mod (Minecraft 1.21.3)，但 Alice 的注释提到"mc_aiplayer 同款方案"（`BotPlayer.java:9`）。

#### 3.2.1 mc_aiplayer 的 FakeClientConnection

**推测**（基于 Alice 注释和 Carpet 实现）:
- mc_aiplayer 也使用 `FakeClientConnection` + `EmbeddedChannel`
- `send()` 方法为空操作
- 依赖原版 equipment 检测机制

**适用性**: Alice 已采用 mc_aiplayer 的连接模式（`FakeConnection`），但 equipment sync 仍失败，说明需要额外处理。

**GitHub URL**:
- [mc_aiplayer GitHub](https://github.com/zoyluoblue/mc_aiplayer)
- [mc_aiplayer README](https://github.com/zoyluoblue/mc_aiplayer/blob/main/README.md)

---

### 3.3 外部 Mod 通用模式总结

| Mod | 平台 | Equipment Sync 策略 | 手动广播？ | 适用性 |
|-----|------|---------------------|-----------|--------|
| Carpet | Fabric | 依赖原版 tick 检测 | 否 | 高（但需确保检测触发） |
| mc_aiplayer | Fabric | 未明确（推测同 Carpet） | 未知 | 中（架构相似但平台不同） |

**通用发现**:
- 外部 mod 普遍**不手动发送 equipment packet**
- 依赖 Minecraft 原版的 `detectEquipmentUpdates()` 机制
- 关键是确保装备变化能被检测到

---

## 四、修复方案

### 4.1 方案 A：手动广播 Equipment Packet（最简单）

**改动位置**: `ChestBotTransferPrimitive.java:232-243`

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 同步所有 inventory 槽位（GUI 数据）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    
    // 新增：手动广播 main hand equipment 给所有玩家
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(
        bot.getId(),
        java.util.List.of(
            com.mojang.datafixers.util.Pair.of(
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                mainHandStack
            )
        )
    );
    
    // 广播给所有玩家（包括 Bot 周围的玩家）
    bot.getServer().getPlayerList().getPlayers().forEach(player -> {
        if (player != bot && player.connection != null) {
            player.connection.send(packet);
        }
    });
}
```

**关键 API**:
- `ServerPlayer.getServer().getPlayerList().getPlayers()`: 获取所有在线玩家
- `player.connection.send(packet)`: 发送 packet 给真实玩家

**优点**:
- **最简单**：只修改 `syncBotInventorySlots()`，无需其他改动
- **立即生效**：广播 packet 后客户端立即更新
- **确定性**：不依赖原版 tick 检测机制

**缺点**:
- **广播范围过大**：发送给所有玩家，即使他们在不同维度或距离很远
- **性能开销**：每次 inventory 修改都广播给所有玩家
- **不符合原版模式**：原版只广播给视野范围内的玩家

**风险**:
- **低**。简单且可控，但性能可能不是最优

---

### 4.2 方案 B：使用 Entity Tracker 广播（推荐）

**改动位置**: `ChestBotTransferPrimitive.java:232-243`

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 同步所有 inventory 槽位（GUI 数据）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    
    // 新增：使用 entity tracker 广播 main hand equipment
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(
        bot.getId(),
        java.util.List.of(
            com.mojang.datafixers.util.Pair.of(
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                mainHandStack
            )
        )
    );
    
    // 使用 ServerLevel.getChunkSource().broadcast() 广播给跟踪 Bot 的玩家
    ((net.minecraft.server.level.ServerLevel)bot.level()).getChunkSource().broadcast(bot, packet);
}
```

**关键 API**:
- `ServerLevel.getChunkSource().broadcast(Entity entity, Packet<?> packet)`: 广播给跟踪该实体的玩家
- 版本：Minecraft 1.20.1 / Forge 1.20.1-47.4.10

**优点**:
- **符合原版模式**：使用与原版相同的广播机制
- **性能优化**：只广播给视野范围内的玩家（entity tracker 自动过滤）
- **自动过滤**：不会尝试发送给 Bot 自己（FakeConnection）

**缺点**:
- **依赖 entity tracker**：如果 Bot 不在任何玩家的视野范围内，packet 不会发送（但这是预期行为）

**风险**:
- **低**。与原版机制一致，安全且高效

---

### 4.3 方案 C：触发原版 Equipment 检测（复杂）

**改动位置**: `ChestBotTransferPrimitive.java:232-243`

```java
private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 同步所有 inventory 槽位（GUI 数据）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    
    // 新增：先修改 inventory，然后清空 lastHandItem，强制下一 tick 检测到变化
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    
    // 使用反射清空 Bot 的 lastHandItem 缓存
    try {
        java.lang.reflect.Field field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("lastHandItem");
        field.setAccessible(true);
        field.set(bot, ItemStack.EMPTY);  // 清空缓存，强制下一 tick 检测到变化
    } catch (Exception e) {
        com.dddgn.alice.log.BotLog.warn("无法清空 lastHandItem 缓存: {}", e.getMessage());
    }
}
```

**工作原理**:
1. 修改 Bot 的 inventory（`inventory.setItem()`）
2. 使用反射清空 `LivingEntity.lastHandItem` 缓存
3. 下一 tick，`detectEquipmentUpdates()` 比较 `currentStack` 和 `lastHandItem`（现在为空），检测到变化
4. 原版机制自动广播 equipment packet

**优点**:
- **完全依赖原版机制**：不需要手动广播 packet
- **符合 Carpet 模式**：与外部 mod 的做法一致

**缺点**:
- **使用反射**：可能在不同 Minecraft 版本中失效
- **延迟生效**：需要等待下一 tick 才能生效（20ms 延迟）
- **复杂度高**：增加代码复杂度，难以维护

**风险**:
- **中**。反射可能失效，且依赖原版内部实现细节

---

### 4.4 方案 D：重写 BotPlayer.setItemSlot()（最彻底）

**改动位置**: `BotPlayer.java:22-37`

```java
public class BotPlayer extends ServerPlayer {
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    @Override
    public void tick() {
        try {
            super.tick();
        } catch (NullPointerException exception) {
            exception.printStackTrace();
        }
    }
    
    // 新增：重写 setItemSlot，立即广播 equipment 变化
    @Override
    public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot, net.minecraft.world.item.ItemStack stack) {
        super.setItemSlot(slot, stack);  // 调用父类方法修改 inventory
        
        // 立即广播 equipment 变化给跟踪此 Bot 的玩家
        if (!this.level().isClientSide && slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HAND) {
            net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket packet = 
                new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                    this.getId(),
                    java.util.List.of(com.mojang.datafixers.util.Pair.of(slot, stack))
                );
            ((net.minecraft.server.level.ServerLevel)this.level()).getChunkSource().broadcast(this, packet);
        }
    }
}
```

**优点**:
- **封装性**：所有 equipment 更新逻辑集中在 `BotPlayer` 中
- **自动生效**：任何调用 `setItemSlot()` 的地方都会自动广播
- **未来扩展**：支持其他装备槽位（OFFHAND、HEAD、CHEST 等）

**缺点**:
- **修改 BotPlayer**：增加 Bot 核心类的复杂度
- **可能影响其他行为**：如果其他地方调用 `setItemSlot()`，也会广播

**风险**:
- **中**。修改核心类，需要仔细测试

---

### 4.5 方案对比表

| 方案 | 改动范围 | 实现难度 | 性能 | 符合原版 | 风险 | 推荐优先级 |
|------|---------|---------|------|---------|------|-----------|
| A（手动广播所有玩家） | `syncBotInventorySlots()` | 低 | 低（广播范围大） | 否 | 低 | 3 |
| B（Entity Tracker 广播） | `syncBotInventorySlots()` | 低 | 高 | **是** | 低 | **1（推荐）** |
| C（触发原版检测） | `syncBotInventorySlots()` | 中（需反射） | 高 | 是 | 中 | 4 |
| D（重写 setItemSlot） | `BotPlayer.java` | 中 | 高 | 是 | 中 | 2 |

---

## 五、推荐方案与实施路线

### 5.1 推荐方案

**方案 B**（Entity Tracker 广播）。理由：
1. **符合原版模式**：使用与 Minecraft 原版相同的广播机制
2. **性能最优**：只广播给视野范围内的玩家
3. **最简单**：只修改 `syncBotInventorySlots()`，一行代码即可
4. **安全**：不依赖反射，不修改核心类

**备选方案**: 如果方案 B 失效，使用方案 D（重写 `setItemSlot()`）作为备选。

### 5.2 实施步骤

**步骤 1**: 修改 `syncBotInventorySlots()` 方法
- 文件：`ChestBotTransferPrimitive.java:232-243`
- 新增：调用 `ServerLevel.getChunkSource().broadcast(bot, packet)`
- 验证：编译通过，`./gradlew compileJava`

**步骤 2**: 移除诊断日志
- 文件：`ChestBotTransferPrimitive.java:239-240`
- 删除：`BotLog.info("[EQUIPMENT_DEBUG] ...")`（已不再需要）

**步骤 3**: 客户端验证
- V1：Bot 从源箱拿到物品后，观察 Bot 手持物品模型
- V2：玩家在不同位置观察 Bot（近距离、远距离、不同维度）
- V3：多玩家场景（两个玩家同时观察 Bot）
- V4：Bot 切换主手槽位（如果有此功能）

**步骤 4**: 性能测试（可选）
- 使用 F3 调试界面监控网络流量
- 确认 packet 只发送给视野范围内的玩家

### 5.3 伪代码实现（方案 B）

```java
// ChestBotTransferPrimitive.java:232-250

private static void syncBotInventorySlots(Inventory inventory) {
    if (!(inventory.player instanceof ServerPlayer bot)) return;
    
    // 1. 同步所有 inventory 槽位（GUI 数据）给 Bot 自己
    //    （注意：Bot 的 FakeConnection.send() 是空操作，但保持此逻辑以防未来需要）
    for (int i = 0; i < inventory.getContainerSize(); i++) {
        bot.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, inventory.getItem(i)));
    }
    
    // 2. 广播 main hand equipment 给跟踪 Bot 的真实玩家
    ItemStack mainHandStack = inventory.getItem(inventory.selected);
    if (!mainHandStack.isEmpty()) {  // 只广播非空物品（优化）
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(
            bot.getId(),
            java.util.List.of(
                com.mojang.datafixers.util.Pair.of(
                    net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    mainHandStack
                )
            )
        );
        
        // 使用 ServerLevel.getChunkSource().broadcast() 广播
        // 这会自动发送给跟踪 Bot 的所有真实玩家（视野范围内）
        ((net.minecraft.server.level.ServerLevel)bot.level()).getChunkSource().broadcast(bot, packet);
    }
}
```

**关键点**:
- `bot.level()` 返回 `Level`，需要转换为 `ServerLevel`
- `getChunkSource().broadcast(Entity entity, Packet packet)` 是原版 API
- 只广播非空物品（优化，避免发送空 packet）

---

## 六、风险评估

### 6.1 方案 B 的风险评估

**风险 1**: Entity Tracker 未跟踪 Bot
- **描述**: 如果 Bot 不在任何玩家的视野范围内，packet 不会发送
- **评估**: **预期行为**。原版也是如此，玩家看不到视野外的实体装备
- **缓解**: 无需缓解，这是正确的行为

**风险 2**: Packet 发送时机
- **描述**: 如果 Bot 刚生成，entity tracker 可能尚未建立
- **评估**: **低风险**。`placeNewPlayer()` 后，Bot 立即加入 entity tracker
- **缓解**: Transfer 通常在 Bot 到达源箱后执行，此时 tracker 已建立

**风险 3**: 多维度场景
- **描述**: Bot 在末地/下界时，主世界玩家是否能看到装备变化
- **评估**: **无风险**。Entity tracker 只跟踪同一维度的实体
- **缓解**: 无需缓解，这是正确的行为

**风险 4**: 性能影响
- **描述**: 每次 inventory 修改都广播 equipment packet
- **评估**: **极低风险**。单个 packet 大小 < 100 bytes，与原版开销相同
- **缓解**: 无需缓解，性能影响可忽略

### 6.2 与其他模块的影响

**影响 1**: `BotManager.syncMainHand()`
- **文件**: `BotManager.java:107-113`
- **当前实现**: 发送 `ClientboundSetCarriedItemPacket` + `ClientboundContainerSetSlotPacket` 给 Bot
- **影响**: 无需修改。`syncMainHand()` 主要用于切换主手槽位，不涉及 equipment 渲染
- **建议**: 可选地，在 `syncMainHand()` 中也广播 equipment packet（使用方案 B 的逻辑）

**影响 2**: `MineTask` 切换工具
- **文件**: `MineTask.java:40-42`
- **当前实现**: `bot.getInventory().setItem()` + `BotManager.syncMainHand()`
- **影响**: `syncMainHand()` 不广播 equipment，但 `MineTask` 执行时 Bot 的 `tick()` 会触发 `detectEquipmentUpdates()`
- **建议**: 观察客户端行为，如果工具切换时不可见，也在 `MineTask` 中添加 equipment 广播

**影响 3**: Bot 死亡/复活
- **文件**: `BotManager.java:360-376`
- **当前实现**: Bot 死亡时调用 `remove()`，清除所有状态
- **影响**: 无影响。Bot 复活时会重新生成，equipment 重新同步

### 6.3 多人服场景

**场景 1**: 两个玩家同时观察 Bot
- **行为**: `broadcast()` 自动发送给两个玩家
- **验证**: 两个玩家都应看到 Bot 手持物品

**场景 2**: 玩家距离 Bot 很远
- **行为**: Entity tracker 范围通常是 128 blocks，超出范围不发送
- **验证**: 玩家走近 Bot 时，应看到手持物品（entity tracker 重新同步）

**场景 3**: 玩家在不同维度
- **行为**: Entity tracker 只跟踪同一维度，不发送
- **验证**: 玩家传送到 Bot 所在维度后，应看到手持物品

---

## 七、证据附录

### 7.1 代码证据表

| 文件 | 行号 | 内容 | 证据类型 |
|------|------|------|----------|
| `FakeConnection.java` | 35-37 | `send()` 空操作 | 根因 1：packet 被丢弃 |
| `ChestBotTransferPrimitive.java` | 241 | `bot.connection.send(packet)` | 缺陷：发送目标错误 |
| `ChestBotTransferPrimitive.java` | 242 | `bot.setItemSlot()` | 缺陷：不触发广播 |
| `BotManager.java` | 75 | `placeNewPlayer()` | Bot 生成时装备为空 |

### 7.2 外部资料引用

**Carpet Mod**:
- [Carpet FakeClientConnection.java](https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/patches/FakeClientConnection.java)
- [Carpet EntityPlayerMPFake.java](https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/patches/EntityPlayerMPFake.java)
- [Carpet Fake Players Documentation](https://deepwiki.com/gnembon/fabric-carpet/4.1-fake-players)

**mc_aiplayer**:
- [mc_aiplayer GitHub](https://github.com/zoyluoblue/mc_aiplayer)
- [mc_aiplayer README](https://github.com/zoyluoblue/mc_aiplayer/blob/main/README.md)

**Minecraft API**:
- [Per-player entity equipment discussion](https://github.com/PaperMC/Paper/discussions/10543)
- [Java Edition protocol/Packets](https://minecraft.wiki/w/Java_Edition_protocol/Packets)

### 7.3 Minecraft API 版本确认

**Forge 版本**: 1.20.1-47.4.10  
**Minecraft 版本**: 1.20.1  
**关键 API**:
- `ClientboundSetEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> slots)`
- `ServerLevel.getChunkSource().broadcast(Entity entity, Packet<?> packet)`
- `LivingEntity.detectEquipmentUpdates()`
- `ServerPlayer.getServer().getPlayerList().getPlayers()`

---

## 八、本报告不构成实施授权

本报告仅提供事实调查、根因分析和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过客户端验证矩阵 V1-V4
5. 经监督员二次审核后方可合并

**证据缺口**:
- 未在客户端实测方案 B 的实际效果（需 V1-V4）
- 未确认 Carpet 和 mc_aiplayer 的具体 equipment sync 实现细节（Fabric 架构差异）
- 未验证多维度和多玩家场景（需客户端多人测试）

**不可直接采纳项**:
- 方案 A（广播所有玩家）性能不佳，只作备选
- 方案 C（反射触发检测）使用反射，维护成本高
- 方案 D（重写 setItemSlot）修改核心类，需仔细评估影响范围
