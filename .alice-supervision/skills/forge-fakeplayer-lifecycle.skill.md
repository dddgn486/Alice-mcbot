---
name: forge-fakeplayer-lifecycle
description: 理解 FakePlayer/ServerPlayer 的生命周期、注册方式、网络连接与 tick 流程。
---
# Skill: Forge FakePlayer Lifecycle

## When to use this skill

Use this skill when:
- Creating a bot/fake player that needs to be visible to real players
- FakePlayer shows up in player list but is invisible in-game
- Bot's tick() causes NullPointerException
- Unsure whether to use FakePlayer vs ServerPlayer
- Need to understand PlayerList registration and network connection

## Core Principle

**FakePlayer 和 ServerPlayer 有本质区别：**
- **FakePlayer**（Forge 提供）：无网络连接，客户端不可见，tick 路径有 NPE
- **ServerPlayer**（原版）：有网络连接，客户端可见，完整 tick 流程

如果需要客户端可见的 bot，必须继承 ServerPlayer 并通过 PlayerList.placeNewPlayer() 注册。

---

## Forge FakePlayer vs ServerPlayer

### **FakePlayer 的特点**

```java
// Forge 提供的 FakePlayer
public class FakePlayer extends ServerPlayer {
    // 特点：
    // 1. connection = null（无网络连接）
    // 2. 不在 PlayerList 中
    // 3. 客户端不可见
    // 4. tick() 路径有 NPE 风险
}
```

**适用场景**：
- ✅ 服务端逻辑测试（不需要客户端看到）
- ✅ 模拟玩家交互（打开箱子、使用工具）
- ✅ 权限系统测试

**不适用场景**：
- ❌ 需要客户端可见
- ❌ 需要完整物理移动
- ❌ 需要与真实玩家交互（推挤、碰撞）

---

### **ServerPlayer 的特点**

```java
// Minecraft 原版的 ServerPlayer
public class ServerPlayer extends Player {
    public ServerGamePacketListenerImpl connection;  // ← 关键！
    
    // 特点：
    // 1. connection != null（有网络连接）
    // 2. 在 PlayerList 中注册
    // 3. 客户端可见
    // 4. 完整 tick 流程
}
```

**如何创建可见的 Bot**：
```java
// 1. 继承 ServerPlayer
public class BotPlayer extends ServerPlayer {
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }
}

// 2. 通过 PlayerList.placeNewPlayer() 注册
ServerPlayer bot = new BotPlayer(server, level, profile);
ServerGamePacketListenerImpl fakeConnection = createFakeConnection(server, bot);
server.getPlayerList().placeNewPlayer(fakeConnection, bot);

// 3. 现在 bot.connection != null，客户端可见！
```

---

## Critical Registration Steps

### **Step 1: 创建 FakeConnection**

```java
private static ServerGamePacketListenerImpl createFakeConnection(
    MinecraftServer server, 
    ServerPlayer player
) {
    // 创建假的网络连接（不发送真实网络包，但满足游戏逻辑）
    Connection networkManager = new Connection(PacketFlow.SERVERBOUND);
    
    // 设置为内存连接（不需要真实 socket）
    networkManager.setListener(new ServerHandshakePacketListenerImpl(server, networkManager));
    
    // 创建 packet listener
    ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl(
        server, 
        networkManager, 
        player
    );
    
    return connection;
}
```

### **Step 2: 注册到 PlayerList**

```java
// 关键：必须用 placeNewPlayer，不能手动添加
server.getPlayerList().placeNewPlayer(fakeConnection, bot);

// placeNewPlayer 会：
// 1. 设置 player.connection = fakeConnection
// 2. 添加到 players 列表
// 3. 广播 PlayerInfo 包（让客户端知道有新玩家）
// 4. 广播 AddEntity 包（让客户端渲染实体）
// 5. 初始化玩家状态
```

### **Step 3: 移除时也要正确清理**

```java
// 错误方式：直接 remove()
level.removeEntity(bot);  // ❌ 客户端不知道，显示"幽灵"

// 正确方式：通过 PlayerList
server.getPlayerList().remove(bot);  // ✅ 广播移除包
```

---

## Tick Flow Differences

### **FakePlayer.tick() 的问题**

```java
// FakePlayer 没有 connection，很多路径会 NPE
@Override
public void tick() {
    super.tick();  // 可能在这里 NPE
    
    // 例如：某些代码假设 connection != null
    if (this.connection.getConnection().isConnected()) {  // ← NPE!
        // ...
    }
}
```

### **ServerPlayer.tick() 的正确流程**

```java
@Override
public void tick() {
    // 1. 检查 connection（如果是 bot，FakeConnection 不会 NPE）
    // 2. 更新玩家状态
    // 3. 处理效果
    // 4. 不会自动调用物理（需要 isEffectiveAi() = true）
    super.tick();
}
```

---

## Physics: Why ServerPlayer doesn't move

### **关键问题**：ServerPlayer.tick() 不会自动调用 aiStep()

**原因**：
- 真实玩家由客户端驱动（客户端计算物理 → 发网络包 → 服务端接收）
- 服务端不主动计算玩家物理

**解决方案**：
```java
public class BotPlayer extends ServerPlayer {
    
    @Override
    public void tick() {
        // Bot 没有客户端，必须在服务端手动调用物理
        controller.onUpdate();  // 设置输入（xxa, zza, jumping）
        this.aiStep();          // 手动调用物理计算
        super.tick();           // 其他逻辑
    }
    
    @Override
    public boolean isEffectiveAi() {
        // 让 travel() 入口检查通过
        return true;
    }
}
```

---

## Common Pitfalls

### **Pitfall 1: 使用 FakePlayer 但期望客户端可见**

**症状**：
- Bot 显示在 Tab 列表
- 但游戏中看不到 bot
- 日志无错误

**根因**：
- FakePlayer 没有网络连接
- 服务端不广播 AddEntity 包

**修复**：
- 改用 ServerPlayer + PlayerList.placeNewPlayer()

---

### **Pitfall 2: 忘记设置 isEffectiveAi()**

**症状**：
- bot.tick() 执行
- xxa/zza 被设置
- 但 bot 不移动

**根因**：
- travel() 入口检查 `isEffectiveAi()` 返回 false
- 移动被跳过

**修复**：
```java
@Override
public boolean isEffectiveAi() {
    return true;
}
```

---

### **Pitfall 3: 直接移除 bot，不通过 PlayerList**

**症状**：
- 服务端 bot 已删除
- 客户端仍显示"幽灵"bot

**根因**：
- 没有广播 RemoveEntities 包

**修复**：
```java
server.getPlayerList().remove(bot);  // 不是 level.removeEntity()
```

---

## Reference Implementations

### **Carpet Mod**
- 使用 ServerPlayer + FakeConnection
- 手动调用 aiStep()
- GitHub: `gnembon/fabric-carpet`

### **Alice Project (mc_aiplayer 风格)**
- 继承 ServerPlayer
- PlayerList 注册
- 手动物理 tick

---

## Checklist

使用本 skill 前检查：

- [ ] 需要客户端可见吗？（是 → ServerPlayer，否 → FakePlayer）
- [ ] 是否通过 PlayerList.placeNewPlayer() 注册？
- [ ] 是否创建了 FakeConnection？
- [ ] 是否重写 isEffectiveAi() 返回 true？
- [ ] 是否在 tick() 中手动调用 aiStep()？
- [ ] 移除时是否用 PlayerList.remove()？

全部勾选 = **PASS**，任一未勾选 = 重新检查实现。

---

## Evidence Requirements

实施后需要以下证据：

**服务端日志**：
```
[INFO] BotPlayer[Alice] joined at (100, 64, 100)
[INFO] PlayerList size: 2 (1 real + 1 bot)
```

**客户端验证**：
- [ ] Tab 列表显示 bot 名字
- [ ] 游戏中看到 bot 实体
- [ ] Bot 可以移动（如果设置了输入）
- [ ] Bot 可以被推挤（碰撞正常）

**清理验证**：
- [ ] /alice bot remove 后，客户端不再显示 bot
- [ ] 无"幽灵"实体残留

---

**This skill helps you avoid 8-12 hours of "why is my bot invisible" debugging.**
