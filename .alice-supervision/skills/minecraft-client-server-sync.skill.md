---
name: minecraft-client-server-sync
description: 理解 Minecraft 的客户端-服务端分离架构、同步机制与常见混淆点。
---
# Skill: Minecraft Client-Server Sync

## When to use this skill

Use this skill when:
- Server-side code works but client doesn't see the result
- Confused about which code runs on client vs server
- Need to understand when to use network packets
- Debugging "works in singleplayer, breaks in multiplayer"
- Understanding `level.isClientSide()` checks

## Core Principle

**Minecraft 是两个程序，不是一个：**
1. **Server**（服务端）：逻辑、物理、世界状态
2. **Client**（客户端）：渲染、输入、预测

**服务端修改 ≠ 客户端立即看到**。需要网络包同步。

---

## Client vs Server

### **服务端（Server）**

**职责**：
- ✅ 世界状态（方块、实体、物品）
- ✅ 游戏逻辑（伤害、死亡、碰撞）
- ✅ 物理计算（移动、重力、推挤）
- ✅ 权限验证（是否允许操作）

**特点**：
- 单人游戏也有服务端（集成服务器）
- 客户端看到的一切来自服务端同步
- 服务端是"真相的来源"

---

### **客户端（Client）**

**职责**：
- ✅ 渲染（3D 模型、GUI、粒子）
- ✅ 输入（键盘、鼠标）
- ✅ 预测（本地玩家移动预测）
- ✅ 插值（平滑实体移动）

**特点**：
- 客户端状态是"近似值"
- 服务端包到达后，客户端更新
- 客户端不能直接修改世界（会被服务端覆盖）

---

## Code Distribution

### **哪些代码在哪里运行？**

```java
// 在服务端和客户端都运行
public void someMethod(Level level) {
    if (level.isClientSide()) {
        // 只在客户端执行
        playSound();
        spawnParticles();
    } else {
        // 只在服务端执行
        modifyWorldState();
        calculateDamage();
    }
}
```

**关键检查**：
```java
level.isClientSide()   // true = 客户端，false = 服务端
level instanceof ServerLevel   // 服务端
level instanceof ClientLevel   // 客户端
```

---

### **单人游戏（Singleplayer）的特殊性**

```
单人游戏 = 客户端 + 集成服务器（同一进程）

Client Thread               Server Thread
     |                            |
     |  点击方块                   |
     |------------------------->  处理破坏
     |                            |
     |  <-------------------------  发送方块更新包
     |  渲染破坏效果               |
```

**易混淆点**：
- 单人游戏中，服务端和客户端在同一进程
- 但它们仍然是分离的！
- 仍然需要网络包同步（虽然不走真实网络）

---

## Sync Mechanisms

### **机制 1：自动同步（Entities）**

```java
// 服务端修改实体状态
entity.setHealth(10.0f);
entity.setPos(x, y, z);

// 追踪器自动同步到客户端（每 N tick）
// 客户端无需手动处理
```

**什么时候用**：
- ✅ 实体位置、旋转、血量
- ✅ 实体元数据（装备、姿态）
- ❌ 自定义实体字段（需要 DataWatcher）

---

### **机制 2：数据观察器（DataWatcher / EntityData）**

```java
// 定义同步字段
public class CustomEntity extends LivingEntity {
    private static final EntityDataAccessor<Integer> CUSTOM_VALUE = 
        SynchedEntityData.defineId(CustomEntity.class, EntityDataSerializers.INT);
    
    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(CUSTOM_VALUE, 0);
    }
    
    // 服务端设置
    public void setCustomValue(int value) {
        this.entityData.set(CUSTOM_VALUE, value);  // 自动同步到客户端
    }
    
    // 客户端读取
    public int getCustomValue() {
        return this.entityData.get(CUSTOM_VALUE);
    }
}
```

**什么时候用**：
- ✅ 实体的自定义字段（客户端需要看到）
- ✅ 状态标志（是否在燃烧、是否隐身）
- ❌ 大量数据（用 NBT + 手动同步）

---

### **机制 3：手动网络包**

```java
// 定义包
public record BotCommandPacket(UUID botId, String command) {
    public static void encode(BotCommandPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.botId);
        buf.writeUtf(packet.command);
    }
    
    public static BotCommandPacket decode(FriendlyByteBuf buf) {
        return new BotCommandPacket(buf.readUUID(), buf.readUtf());
    }
    
    public static void handle(BotCommandPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 服务端处理
            ServerPlayer sender = ctx.get().getSender();
            BotManager.handleCommand(sender, packet.botId, packet.command);
        });
        ctx.get().setPacketHandled(true);
    }
}

// 客户端发送
PacketDistributor.sendToServer(new BotCommandPacket(botId, "forward"));

// 服务端广播
PacketDistributor.sendToAllPlayers(new BotStatePacket(...));
```

**什么时候用**：
- ✅ 自定义交互（GUI 按钮、按键绑定）
- ✅ 大量数据同步
- ✅ 需要精确控制同步时机

---

## Common Pitfalls

### **Pitfall 1: 客户端修改被覆盖**

**症状**：
```java
// 客户端代码
entity.setHealth(20.0f);  // 设置后立即被服务端覆盖
```

**根因**：
- 客户端状态是"镜像"
- 服务端定期同步，覆盖客户端修改

**修复**：
```java
// 服务端修改
if (!level.isClientSide()) {
    entity.setHealth(20.0f);  // ✅ 服务端修改，自动同步到客户端
}
```

---

### **Pitfall 2: 忘记 isClientSide 检查**

**症状**：
- 单人游戏正常
- 多人游戏重复执行（服务端 + 客户端各执行一次）

**根因**：
```java
public void onPlayerInteract(PlayerInteractEvent event) {
    giveItem(event.getEntity());  // ❌ 服务端和客户端都执行
}

// 结果：单人游戏给 2 个物品（服务端 1 个 + 客户端 1 个）
```

**修复**：
```java
public void onPlayerInteract(PlayerInteractEvent event) {
    if (!event.getLevel().isClientSide()) {
        giveItem(event.getEntity());  // ✅ 只在服务端执行
    }
}
```

---

### **Pitfall 3: 客户端猜测服务端状态**

**症状**：
```java
// 客户端代码
if (playerHasEnoughMoney()) {  // ❌ 客户端检查
    buyItem();
}
```

**问题**：
- 客户端可以被修改（作弊）
- 客户端状态可能过时

**修复**：
```java
// 客户端：发送请求
sendBuyItemPacket();

// 服务端：验证 + 执行
public void handleBuyItem(ServerPlayer player) {
    if (playerHasEnoughMoney(player)) {  // ✅ 服务端验证
        buyItem(player);
    }
}
```

---

### **Pitfall 4: 依赖客户端事件**

**症状**：
```java
@SubscribeEvent
public void onMovementInput(MovementInputUpdateEvent event) {
    // 这个事件只在客户端触发！
    // 服务端永远收不到
}
```

**修复**：
```java
// 客户端：监听事件
@SubscribeEvent
public void onMovementInput(MovementInputUpdateEvent event) {
    sendInputPacket(event.getInput());  // 发送到服务端
}

// 服务端：接收包
public void handleInput(InputPacket packet) {
    applyInput(packet.forward, packet.strafe);
}
```

---

## Decision Tree

### **我应该在哪里写这段代码？**

```
Q: 这段代码需要修改世界状态（方块、实体、物品）？
   ├─ 是 → 服务端
   └─ 否 ↓

Q: 这段代码需要渲染（粒子、模型、GUI）？
   ├─ 是 → 客户端
   └─ 否 ↓

Q: 这段代码需要处理输入（键盘、鼠标）？
   ├─ 是 → 客户端（然后发包到服务端）
   └─ 否 ↓

Q: 这段代码是纯计算（不涉及世界、渲染、输入）？
   └─ 两边都可以（通常放服务端）
```

---

## Testing Strategy

### **服务端测试（Headless）**

```java
@Test
public void testBotMovement() {
    // 只测试服务端逻辑
    bot.setPos(100, 64, 100);
    bot.tick();
    assertEquals(101, bot.getX());  // ✅ 验证服务端状态
}
```

**可以测试**：
- ✅ 逻辑正确性（伤害计算、物品掉落）
- ✅ 服务端状态（位置、血量、库存）

**不能测试**：
- ❌ 客户端渲染
- ❌ 网络同步
- ❌ GUI 交互

---

### **客户端测试（Manual）**

```
1. 启动客户端 + 服务器
2. 玩家进入游戏
3. 执行操作
4. 观察客户端表现
5. 截图 / 录像作为证据
```

**必须客户端测试的场景**：
- ✅ GUI 显示
- ✅ 实体可见性
- ✅ 移动流畅度
- ✅ 网络同步（多人游戏）

---

## Reference Patterns

### **Pattern 1: 服务端逻辑 + 自动同步**

```java
// 服务端修改
if (!level.isClientSide()) {
    entity.setHealth(newHealth);  // 自动同步到客户端
}

// 客户端读取
float health = entity.getHealth();  // 读取同步后的值
```

---

### **Pattern 2: 客户端输入 → 服务端验证**

```java
// 客户端：监听输入
@SubscribeEvent
public void onKeyPress(InputEvent.Key event) {
    if (event.getKey() == GLFW.GLFW_KEY_R) {
        PacketDistributor.sendToServer(new UseAbilityPacket());
    }
}

// 服务端：验证 + 执行
public void handleUseAbility(ServerPlayer player) {
    if (player.getCooldowns().isOnCooldown(ability)) return;  // 验证
    useAbility(player);  // 执行
}
```

---

### **Pattern 3: 服务端广播状态**

```java
// 服务端：状态变化后广播
public void onBotStateChange(BotPlayer bot) {
    PacketDistributor.sendToAllTracking(
        bot,
        new BotStatePacket(bot.getId(), bot.getState())
    );
}

// 客户端：接收并更新显示
public void handleBotState(BotStatePacket packet) {
    updateBotDisplay(packet.botId(), packet.state());
}
```

---

## Evidence Requirements

使用本 skill 后需要提供：

**服务端证据**：
```
[SERVER] Player interacted with bot
[SERVER] Bot position: (100, 64, 100) → (101, 64, 100)
[SERVER] Sending sync packet to 2 players
```

**客户端证据**：
- [ ] 截图显示正确渲染
- [ ] 视频显示移动流畅
- [ ] F3 显示正确坐标

**同步验证**：
- [ ] 服务端修改后，客户端在 < 100ms 内更新
- [ ] 多个客户端看到相同状态
- [ ] 断线重连后状态正确

---

**This skill helps you avoid 8-12 hours of "works on server but not on client" debugging.**
