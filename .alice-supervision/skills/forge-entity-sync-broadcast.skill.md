---
name: forge-entity-sync-broadcast
description: 理解 Minecraft 实体同步机制、网络包广播、追踪范围与客户端更新时机。
---
# Skill: Forge Entity Sync & Broadcast

## When to use this skill

Use this skill when:
- Server-side entity movement works but client doesn't see it
- Entity position/rotation updates on server but client shows old position
- Need to understand when entities sync to clients
- Debugging "entity teleports" or "rubber-banding" issues
- Creating custom entities that need client visibility

## Core Principle

**服务端修改实体状态 ≠ 客户端立即看到**

Minecraft 的实体同步是：
1. **定期自动同步**（每 tick，但有限制条件）
2. **追踪范围限制**（距离太远不同步）
3. **网络包广播**（只发给追踪该实体的玩家）

如果客户端看不到变化，要检查：**追踪、同步频率、包类型**。

---

## Entity Tracking System

### **什么是 Entity Tracking？**

```java
// 每个实体在服务端有一个 "追踪器"
ServerEntity tracker = new ServerEntity(
    serverLevel,
    entity,
    updateInterval,      // 多少 tick 同步一次
    trackingRange,       // 追踪范围（方块）
    sendVelocity         // 是否发送速度
);

// 追踪器负责：
// 1. 检测哪些玩家在追踪范围内
// 2. 定期发送同步包
// 3. 检测状态变化（位置、旋转、元数据）
```

### **追踪范围**

不同实体类型有不同的追踪范围：

| 实体类型 | 追踪范围（方块） | 同步间隔（tick） |
|---------|----------------|-----------------|
| Player  | 32（可配置）    | 2               |
| Mob     | 8               | 3               |
| Item    | 6               | 20              |
| Arrow   | 4               | 20              |

**关键**：如果玩家距离实体 > 追踪范围，不会收到同步包！

---

## Sync Packets

### **主要的同步包类型**

#### **1. ClientboundAddEntityPacket**
```java
// 告诉客户端"有一个新实体"
// 包含：实体 ID、UUID、类型、初始位置、旋转
server.getPlayerList().broadcastAll(
    new ClientboundAddEntityPacket(entity)
);
```

**什么时候发送**：
- 实体首次进入玩家的追踪范围
- 玩家加入服务器时（发送所有可见实体）

---

#### **2. ClientboundMoveEntityPacket**
```java
// 告诉客户端"实体移动了"
// 包含：实体 ID、位置增量（相对上次）
new ClientboundMoveEntityPacket.Pos(
    entityId,
    (short) deltaX,
    (short) deltaY,
    (short) deltaZ,
    onGround
);
```

**什么时候发送**：
- 每 N tick（取决于 updateInterval）
- 仅当位置变化 > 阈值（避免抖动）

**位置编码**：
- 使用 8 倍缩放的 short（精度 1/8 方块）
- 如果移动 > 8 方块，使用 ClientboundTeleportEntityPacket

---

#### **3. ClientboundTeleportEntityPacket**
```java
// 告诉客户端"实体传送了"
// 包含：实体 ID、绝对位置、旋转
new ClientboundTeleportEntityPacket(entity);
```

**什么时候发送**：
- 位置变化 > 8 方块
- setPos() 或 teleportTo() 被调用
- 实体重生

---

#### **4. ClientboundRotateHeadPacket**
```java
// 告诉客户端"实体头部旋转"
// LivingEntity 特有（玩家、怪物）
new ClientboundRotateHeadPacket(entity, (byte) yaw);
```

---

#### **5. ClientboundSetEntityDataPacket**
```java
// 告诉客户端"实体元数据变化"
// 元数据：装备、状态效果、姿态等
new ClientboundSetEntityDataPacket(
    entityId,
    entity.getEntityData().packDirty()
);
```

**什么时候发送**：
- EntityData 被标记为 dirty（通过 set()）
- 追踪器检测到变化

---

## Automatic Sync Flow

### **正常的自动同步流程**

```
每 tick（服务端）：
  ↓
ServerLevel.tick()
  ↓
EntityTrackerStore.tick()
  ↓
对每个追踪器：ServerEntity.sendChanges()
  ↓
检测状态变化：
  - 位置变化？ → ClientboundMoveEntityPacket
  - 旋转变化？ → ClientboundMoveEntityPacket.Rot
  - 元数据变化？ → ClientboundSetEntityDataPacket
  ↓
发送给所有追踪该实体的玩家
```

### **什么时候自动同步失效？**

#### **情况 1：FakeConnection 不转发包**

```java
public class FakeConnection extends Connection {
    @Override
    public void send(Packet<?> packet) {
        // 什么都不做 → 包被丢弃！
    }
}
```

**症状**：
- 服务端日志显示实体移动 ✓
- 客户端看到实体静止 ✗

**根因**：
- FakeConnection 用于测试，不转发网络包
- 真实玩家的连接也收不到包（因为广播时跳过了 fake 连接）

---

#### **情况 2：追踪范围外**

```java
// 玩家在 (0, 64, 0)
// Bot 在 (100, 64, 0)
// 距离 = 100 方块 > 32（Player 追踪范围）
// → 玩家不追踪 bot → 不收到同步包
```

**解决方案**：
```java
// 增加追踪范围（在实体注册时配置）
event.register(
    BOT_ENTITY_TYPE,
    (entity, updateInterval, trackingRange, sendVelocity) -> 
        new ServerEntity(entity, updateInterval, 128, sendVelocity)  // 128 方块
);
```

---

#### **情况 3：同步间隔太长**

```java
// 如果 updateInterval = 20（每秒 1 次）
// 快速移动的实体看起来会"跳跃"
```

**解决方案**：
- 减少 updateInterval（例如 Player 是 2 tick）
- 或手动发送同步包

---

## Manual Sync

### **什么时候需要手动同步？**

- 自定义传送逻辑
- 重要状态变化需要立即同步（不等下一次 tick）
- 特殊效果（闪烁、击退）

### **手动同步的正确方式**

```java
// 方式 1：广播给所有玩家
server.getPlayerList().broadcastAll(
    new ClientboundTeleportEntityPacket(entity)
);

// 方式 2：广播给追踪该实体的玩家
ServerLevel serverLevel = (ServerLevel) entity.level();
serverLevel.getChunkSource().broadcast(
    entity,
    new ClientboundMoveEntityPacket.Pos(...)
);

// 方式 3：只发给特定玩家
serverPlayer.connection.send(
    new ClientboundTeleportEntityPacket(entity)
);
```

---

## Common Pitfalls

### **Pitfall 1: 修改位置后不标记 dirty**

**错误代码**：
```java
// 直接修改字段
entity.setPos(x, y, z);
// ❌ 追踪器不知道位置变了，不会发包
```

**正确代码**：
```java
// 使用原版方法（会自动标记 dirty）
entity.setPos(x, y, z);           // ✅ 会触发追踪器
entity.teleportTo(x, y, z);        // ✅ 会立即发送 TeleportPacket
```

---

### **Pitfall 2: 服务端测试没有真实玩家**

**问题**：
```java
// headless 测试
FakePlayer observer = ...;
bot.setPos(100, 64, 100);

// 期望：observer 看到 bot 移动
// 实际：FakeConnection 不转发包
```

**解决方案**：
- 服务端测试只验证逻辑（位置字段更新）
- 客户端实测验证同步（真实玩家观察）

---

### **Pitfall 3: 混淆服务端坐标和客户端坐标**

**问题**：
```java
// 服务端
bot.setPos(100.5, 64.0, 100.5);

// 客户端
System.out.println(bot.getX());  // 可能输出 100.4 或 100.6
```

**原因**：
- 网络包使用有限精度（short，1/8 方块）
- 客户端插值（smooth movement）
- 客户端预测（prediction）

**验证方法**：
- 使用阈值比较：`Math.abs(actual - expected) < 0.2`
- 或只验证方块坐标：`Mth.floor(x)`

---

## Debugging Checklist

实体同步问题调试清单：

### **Step 1: 验证服务端状态**
```java
// 服务端日志
System.out.println("Server: bot pos = " + bot.position());
System.out.println("Server: bot delta = " + bot.getDeltaMovement());
```

- [ ] 服务端位置正确更新？
- [ ] 服务端速度不是 (0,0,0)？

---

### **Step 2: 检查追踪**
```java
// 检查玩家是否追踪该实体
ServerLevel level = (ServerLevel) bot.level();
ChunkMap chunkMap = level.getChunkSource().chunkMap;
ChunkMap.TrackedEntity tracker = chunkMap.entityMap.get(bot.getId());

if (tracker != null) {
    System.out.println("Tracking players: " + tracker.seenBy.size());
} else {
    System.out.println("Entity not tracked!");
}
```

- [ ] 实体有追踪器？
- [ ] 至少有 1 个玩家追踪？
- [ ] 玩家距离 < 追踪范围？

---

### **Step 3: 监控网络包**
```java
// 在 ServerGamePacketListenerImpl 打日志
@Override
public void send(Packet<?> packet) {
    if (packet instanceof ClientboundMoveEntityPacket) {
        System.out.println("Sending move packet: " + packet);
    }
    super.send(packet);
}
```

- [ ] 有 ClientboundMoveEntityPacket 被发送？
- [ ] 包的 entityId 正确？
- [ ] 包的位置增量不是 (0,0,0)？

---

### **Step 4: 客户端验证**

- [ ] 客户端控制台有错误？
- [ ] F3 模式下看到实体坐标？
- [ ] 实体在视野内（不是被遮挡）？

---

## Reference Patterns

### **Pattern 1: 立即同步位置**

```java
public void teleportBotImmediately(BotPlayer bot, Vec3 target) {
    // 1. 服务端设置位置
    bot.teleportTo(target.x, target.y, target.z);
    
    // 2. 立即广播（不等下次 tick）
    ServerLevel level = (ServerLevel) bot.level();
    level.getChunkSource().broadcast(
        bot,
        new ClientboundTeleportEntityPacket(bot)
    );
}
```

---

### **Pattern 2: 定期强制同步**

```java
// 每 N tick 强制同步一次（避免累积误差）
if (bot.tickCount % 20 == 0) {
    ServerLevel level = (ServerLevel) bot.level();
    level.getChunkSource().broadcast(
        bot,
        new ClientboundTeleportEntityPacket(bot)
    );
}
```

---

### **Pattern 3: 检测玩家进入追踪范围**

```java
@SubscribeEvent
public static void onPlayerTrackEntity(PlayerEvent.StartTracking event) {
    Entity tracked = event.getTarget();
    if (tracked instanceof BotPlayer bot) {
        // 玩家开始追踪 bot
        // 发送初始状态包（如果需要）
    }
}
```

---

## Evidence Requirements

实施后需要以下证据：

**服务端日志**：
```
[DEBUG] Bot position updated: (100.0, 64.0, 100.0) → (101.0, 64.0, 100.0)
[DEBUG] Sending ClientboundMoveEntityPacket to 1 players
[DEBUG] Entity tracked by: [Player1]
```

**客户端验证**：
- [ ] 玩家看到 bot 移动（位置变化）
- [ ] 移动流畅（无"跳跃"或"橡皮筋"）
- [ ] F3 界面显示正确坐标
- [ ] 多个玩家同时观察时都能看到

**追踪范围测试**：
- [ ] 玩家距离 < 32 方块时看到 bot
- [ ] 玩家距离 > 32 方块时看不到 bot
- [ ] 玩家接近时 bot 逐渐"出现"

---

**This skill helps you avoid 6-10 hours of "why doesn't the client see my entity move" debugging.**
