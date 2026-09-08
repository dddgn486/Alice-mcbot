# Minecraft 原版玩家运行机制解析

> 目标：理解 ServerPlayer 的 tick 流程，搞清楚移动物理是怎么工作的  
> 基于：Minecraft 1.20.1 + Forge

---

## 🎮 真实玩家 vs 假人（ServerPlayer）

### **真实玩家的运行流程**

```
客户端（玩家电脑）                服务端（服务器）
┌─────────────────┐              ┌─────────────────┐
│ 1. 玩家按 W 键   │              │                 │
│    ↓             │              │                 │
│ 2. 客户端计算移动 │              │                 │
│    - 读取输入     │              │                 │
│    - 调用 travel()│              │                 │
│    - 更新位置     │              │                 │
│    ↓             │              │                 │
│ 3. 发送位置包     │─────────────→│ 4. 接收位置包    │
│ ServerboundMovePlayerPacket      │    ↓            │
│                  │              │ 5. 验证位置      │
│                  │              │    - 防作弊检查  │
│                  │              │    - 更新位置    │
│                  │              │    ↓            │
│                  │←─────────────│ 6. 广播给其他玩家│
│                  │              │                 │
└─────────────────┘              └─────────────────┘

关键：真实玩家的移动由【客户端驱动】，服务端只做验证！
```

### **假人（ServerPlayer）的问题**

```
假人（BotPlayer）
┌─────────────────┐
│ 没有客户端！     │
│    ↓             │
│ ServerPlayer.tick() 被调用
│    ↓             │
│ super.tick()     │
│    ↓             │
│ 问题：原版的 super.tick() 不会自动调用 travel()！
│       因为原版假设有客户端在发位置包
│    ↓             │
│ 结果：假人不会移动、不会掉落、重力不生效
└─────────────────┘

解决方案：
1. mc_aiplayer 的做法：在 tick() 里手动设置输入，然后调用 travel()
2. Alice 的旧做法：在任务里手动调用 travel()（但调用了两次）
3. Alice 的新做法：用 BotController 统一管理输入
```

---

## 📊 ServerPlayer.tick() 完整流程

### **调用链（服务端每 tick）**

```java
// 1. 服务器主循环
MinecraftServer.tickServer()
  ↓
// 2. 世界 tick
ServerLevel.tick()
  ↓
// 3. 遍历所有实体（包括玩家）
level.tickNonPassenger(entity)
  ↓
// 4. 玩家 tick
ServerPlayer.tick()
  ↓
// 5. 调用父类 Player.tick()
super.tick()  // Player.tick()
  ↓
// 6. 调用父类 LivingEntity.tick()
super.tick()  // LivingEntity.tick()
  ↓
// 7. 物理更新（在 LivingEntity.tick() 里）
this.aiStep()
  ↓
// 8. 移动处理（在 LivingEntity.aiStep() 里）
this.travel(this.getDeltaMovement())  // ← 这里调用 travel()！
```

### **关键点**

```java
// LivingEntity.tick() 源码简化版
@Override
public void tick() {
    super.tick();  // Entity.tick()
    
    this.tickEffects();  // 药水效果
    this.updateSwingTime();  // 挥手动画
    
    if (!this.level().isClientSide) {
        // 服务端才执行
        this.aiStep();  // ← 重要：这里处理 AI 和移动
    }
    
    // ... 其他逻辑
}

// LivingEntity.aiStep() 源码简化版
protected void aiStep() {
    // 处理药水效果、状态效果等
    this.updateInWaterStateAndDoFluidPushing();
    
    // 重力和移动
    if (this.jumping) {
        // 跳跃逻辑
        if (this.onGround() && this.noJumpDelay == 0) {
            this.jumpFromGround();  // ← 这里处理跳跃
            this.noJumpDelay = 10;  // 跳跃冷却
        }
    } else {
        this.noJumpDelay = 0;
    }
    
    // 处理移动输入
    this.zza *= 0.98F;  // 摩擦力
    this.xxa *= 0.98F;
    
    // 调用 travel 处理物理移动
    this.travel(new Vec3(this.xxa, this.yya, this.zza));  // ← 核心！
    
    // ... 其他逻辑
}
```

---

## 🚀 travel() 方法详解

### **travel() 的作用**

`travel(Vec3 travelVector)` 是 Minecraft 物理引擎的核心方法，负责：
1. 读取移动输入（xxa, zza）
2. 应用重力
3. 处理碰撞
4. 更新实体位置

### **travel() 源码简化版**

```java
// LivingEntity.travel() 源码简化版
public void travel(Vec3 travelVector) {
    // travelVector = (xxa, yya, zza)
    // xxa: 左右输入 (-1.0 到 1.0)
    // yya: 上下输入（一般是 0，飞行时用）
    // zza: 前后输入 (-1.0 到 1.0)
    
    // 步骤 1：检查特殊状态
    if (this.isEffectiveAi() || this.isControlledByLocalInstance()) {
        // 只有 AI 控制或本地客户端控制才处理移动
        // 假人需要返回 true！
        
        // 步骤 2：处理流体中的移动（水、岩浆）
        if (this.isInWater()) {
            this.moveRelative(this.getSpeed() * 0.02F, travelVector);
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().scale(0.8D));  // 水中阻力
            // ... 浮力、氧气等
            return;
        }
        
        if (this.isInLava()) {
            // 岩浆中的移动（类似水）
            // ...
            return;
        }
        
        // 步骤 3：正常地面/空中移动
        float friction = 0.91F;  // 默认摩擦系数
        if (this.onGround()) {
            // 在地面上：读取脚下方块的摩擦系数
            BlockPos groundPos = this.getBlockPosBelowThatAffectsMyMovement();
            friction = this.level().getBlockState(groundPos).getFriction(this.level(), groundPos, this) * 0.91F;
        }
        
        float speedMultiplier = this.onGround() ? 
            this.getSpeed() * (0.21600002F / (friction * friction * friction)) : 
            this.getFlyingSpeed();  // 飞行速度
        
        // 步骤 4：根据输入计算移动向量
        this.moveRelative(speedMultiplier, travelVector);
        // ↑ 这一步把 (xxa, yya, zza) 转换成世界坐标的速度向量
        //   考虑了朝向（yRot）和速度倍率
        
        // 步骤 5：应用重力
        Vec3 deltaMovement = this.getDeltaMovement();
        if (!this.onGround()) {
            // 空中：应用重力
            deltaMovement = deltaMovement.add(0.0D, -0.08D, 0.0D);  // 重力加速度
        }
        this.setDeltaMovement(deltaMovement);
        
        // 步骤 6：移动实体（碰撞检测）
        this.move(MoverType.SELF, this.getDeltaMovement());
        // ↑ 这一步真正移动实体，并处理碰撞
        //   会更新 horizontalCollision, verticalCollision, onGround 等状态
        
        // 步骤 7：应用摩擦力
        deltaMovement = this.getDeltaMovement();
        deltaMovement = deltaMovement.multiply(friction, 0.98D, friction);  // XZ 方向摩擦，Y 方向空气阻力
        this.setDeltaMovement(deltaMovement);
    }
}

// moveRelative() 方法：把输入转换成速度
protected void moveRelative(float speed, Vec3 relative) {
    Vec3 motion = getInputVector(relative, speed, this.getYRot());
    this.setDeltaMovement(this.getDeltaMovement().add(motion));
}

// getInputVector() 方法：根据朝向转换坐标系
private static Vec3 getInputVector(Vec3 relative, float speed, float yaw) {
    double lengthSq = relative.lengthSqr();
    if (lengthSq < 1.0E-7D) {
        return Vec3.ZERO;
    }
    
    Vec3 normalized = (lengthSq > 1.0D ? relative.normalize() : relative).scale(speed);
    
    // 把相对坐标（前后左右）转换成世界坐标（XYZ）
    float sin = Mth.sin(yaw * ((float)Math.PI / 180F));
    float cos = Mth.cos(yaw * ((float)Math.PI / 180F));
    
    return new Vec3(
        normalized.x * cos - normalized.z * sin,  // X 分量
        normalized.y,                              // Y 分量（不旋转）
        normalized.z * cos + normalized.x * sin   // Z 分量
    );
}
```

---

## 📐 输入 → 移动的完整流程

### **示例：玩家按 W 键前进**

```
输入阶段：
  zza = 1.0F         // 前进输入
  xxa = 0.0F         // 无左右输入
  yya = 0.0F         // 无上下输入
  yRot = 0.0F        // 朝北（Z 负方向）

↓ travel(new Vec3(0.0F, 0.0F, 1.0F))

速度计算阶段：
  speedMultiplier = 0.1F  // 在地面上的基础速度
  
  moveRelative(0.1F, (0, 0, 1)):
    inputVector = getInputVector((0, 0, 1), 0.1F, 0.0F)
    
    根据朝向转换：
      yaw = 0.0F (朝北)
      sin(0) = 0
      cos(0) = 1
      
      x = 0 * 1 - 1 * 0 = 0
      y = 0
      z = 1 * 1 + 0 * 0 = 1
      
    结果：inputVector = (0, 0, 1) * 0.1 = (0, 0, 0.1)
  
  deltaMovement += (0, 0, 0.1)

↓

重力阶段：
  if (!onGround) {
    deltaMovement += (0, -0.08, 0)  // 如果在空中，加上重力
  }

↓

移动阶段：
  move(MoverType.SELF, deltaMovement)
    → 更新实体位置
    → 碰撞检测
    → 更新 onGround, horizontalCollision 等状态

↓

摩擦阶段：
  friction = 0.6F (石头等硬方块)
  deltaMovement *= (friction, 0.98, friction)
  → 速度逐渐衰减

↓

结果：
  实体向北移动约 0.1 格
  下一 tick 如果继续按 W，会继续移动
```

### **速度参考值**

```java
// 基础移动速度
getSpeed() = 0.1F  // Player.getSpeed()

// 不同状态的速度倍率
正常行走：  0.1 格/tick  (2 米/秒)
潜行：      0.03 格/tick (0.6 米/秒) → getSpeed() * 0.3
疾跑：      0.13 格/tick (2.6 米/秒) → getSpeed() * 1.3
游泳：      0.02 格/tick (0.4 米/秒)
飞行：      0.05 格/tick (1 米/秒)

// 重力加速度
重力：      -0.08 格/tick²
终端速度：  约 -3.92 格/tick（自由落体极限）

// 跳跃初速度
跳跃：      +0.42 格/tick (向上)
```

---

## 🤖 假人的特殊处理

### **问题 1：isEffectiveAi() 返回 false**

```java
// LivingEntity.isEffectiveAi()
public boolean isEffectiveAi() {
    return !this.level().isClientSide;  // 服务端返回 true
}

// Player 重写了这个方法
@Override
public boolean isEffectiveAi() {
    return false;  // ← 问题：玩家默认返回 false！
}

// 原因：原版假设真实玩家由客户端驱动，不需要服务端 AI
// 结果：ServerPlayer 的 travel() 会直接返回，什么都不做！
```

**解决方案**：BotPlayer 需要重写这个方法

```java
// BotPlayer.java
@Override
public boolean isEffectiveAi() {
    return true;  // ← 让服务端处理假人的移动
}
```

### **问题 2：isControlledByLocalInstance() 返回 false**

```java
// Entity.isControlledByLocalInstance()
public boolean isControlledByLocalInstance() {
    // 真实玩家：如果是本地客户端控制，返回 true
    // 假人：返回 false
    return false;
}
```

**travel() 的检查逻辑**：

```java
if (this.isEffectiveAi() || this.isControlledByLocalInstance()) {
    // 只有满足其中一个条件，才处理移动
}
```

**结论**：只要让 `isEffectiveAi()` 返回 `true`，假人就能正常移动了！

---

## 🔧 BotPlayer 的正确实现

### **完整代码**

```java
package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class BotPlayer extends ServerPlayer {
    private final BotController controller;
    
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        this.controller = new BotController(this);
    }
    
    /**
     * 关键修改 1：让服务端处理假人的移动。
     * 原版 Player.isEffectiveAi() 返回 false，导致 travel() 不工作。
     */
    @Override
    public boolean isEffectiveAi() {
        return true;  // ← 必须返回 true！
    }
    
    /**
     * 关键修改 2：在 super.tick() 之前设置输入。
     */
    @Override
    public void tick() {
        try {
            // 步骤 1：BotController 设置输入（xxa, zza, jumping 等）
            controller.tick();
            
            // 步骤 2：调用原版 tick 流程
            // → Player.tick()
            //   → LivingEntity.tick()
            //     → aiStep()
            //       → travel()  ← 这里读取输入并处理物理
            super.tick();
            
        } catch (NullPointerException exception) {
            BotLog.error("BotPlayer.tick() 异常", exception);
        }
    }
    
    public BotController getController() {
        return controller;
    }
}
```

### **为什么这样实现有效？**

```
每个服务器 tick（50ms）：

1. ServerLevel.tick() 遍历所有实体
   ↓
2. BotPlayer.tick() 被调用
   ↓
3. controller.tick() 设置输入：
   bot.xxa = 0.0F
   bot.zza = 1.0F     ← 设置"前进"输入
   bot.jumping = false
   ↓
4. super.tick() 调用原版流程：
   Player.tick()
   → LivingEntity.tick()
     → aiStep()
       → if (isEffectiveAi()) {  ← 现在返回 true！
            travel(new Vec3(xxa, yya, zza))  ← 读取刚才设置的输入
              → 计算速度
              → 应用重力
              → 移动实体
              → 应用摩擦
          }
   ↓
5. bot 移动了 0.1 格！
```

---

## ⚠️ 常见错误

### **错误 1：手动调用 travel()**

```java
// ❌ 错误做法
controller.tick();  // 设置输入
bot.travel(new Vec3(0, 0, 1));  // ← 手动调用 travel()（第 1 次）
super.tick();  // ← 内部又调用 travel()（第 2 次）

// 结果：每 tick 调用两次 travel()，速度翻倍！
```

```java
// ✅ 正确做法
controller.tick();  // 设置输入
super.tick();       // 让 super.tick() 自动调用 travel()（只调用 1 次）
```

### **错误 2：忘记重写 isEffectiveAi()**

```java
// ❌ 如果 BotPlayer 没有重写 isEffectiveAi()
@Override
public void tick() {
    controller.tick();  // 设置了输入
    super.tick();       // 但 travel() 因为 isEffectiveAi() = false 直接返回了
}

// 结果：bot 不移动！
```

### **错误 3：在错误的时机设置输入**

```java
// ❌ 错误做法：在 super.tick() 之后设置输入
@Override
public void tick() {
    super.tick();       // ← travel() 已经执行了，读取的是旧输入
    controller.tick();  // ← 设置新输入，但要等下一 tick 才生效
}

// 结果：输入延迟 1 tick！
```

```java
// ✅ 正确做法：在 super.tick() 之前设置输入
@Override
public void tick() {
    controller.tick();  // ← 先设置输入
    super.tick();       // ← travel() 读取刚设置的输入
}
```

---

## 📊 对比：三种移动方式

| 方式 | 实现 | travel() 调用 | 物理引擎 | 优缺点 |
|------|------|--------------|---------|--------|
| **HARD_PATH**<br>(setPos) | `bot.setPos(x, y, z)` | 不调用 | 不使用 | ✅ 快速可控<br>❌ 不自然，瞬移感 |
| **旧 NATIVE_TRAVEL**<br>(手动 travel) | `bot.travel(...)`<br>`super.tick()` | 每 tick 调用 2 次 | 使用但有 bug | ❌ 速度翻倍<br>❌ 物理不准确 |
| **新 BotController**<br>(只设置输入) | `controller.tick()`<br>`super.tick()` | 每 tick 调用 1 次 | 正确使用 | ✅ 完全原版物理<br>✅ 自然流畅 |

---

## 🎯 总结

### **原版玩家的运行机制**

1. **真实玩家**：客户端计算移动 → 发送位置包 → 服务端验证
2. **假人**：没有客户端 → 需要服务端自己处理移动

### **关键流程**

```
tick() 
  → controller.tick() (设置输入)
  → super.tick()
    → LivingEntity.tick()
      → aiStep()
        → travel(new Vec3(xxa, yya, zza))  ← 物理引擎核心
```

### **BotPlayer 的两个关键修改**

1. **重写 `isEffectiveAi()`**：让 `travel()` 能够执行
2. **在 `super.tick()` 之前设置输入**：让 `travel()` 读取正确的输入

### **BotController 的职责**

- 只负责设置输入（xxa, zza, jumping 等）
- 不调用 `travel()` 或 `move()`
- 让原版物理引擎自动处理

---

## 💡 下一步

现在你理解原版的运行机制了，可以：
1. ✅ 确认 BotPlayer 重写了 `isEffectiveAi()`
2. ✅ 确认 BotController 在 `super.tick()` 之前调用
3. ✅ 测试移动是否正常工作

需要我帮你检查你的 BotPlayer 实现吗？🐋💙
