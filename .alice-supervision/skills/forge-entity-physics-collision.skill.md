---
name: forge-entity-physics-collision
description: 理解 Minecraft 实体物理系统、碰撞检测、移动计算与 travel() 方法。
---
# Skill: Forge Entity Physics & Collision

## When to use this skill

Use this skill when:
- Entity doesn't move even though velocity is set
- Entity passes through walls or other entities
- Understanding why `travel()` isn't being called
- Need to implement custom movement (flying, swimming, climbing)
- Debugging why entity "rubber-bands" or "teleports"

## Core Principle

**设置速度 ≠ 实体移动**

Minecraft 的物理系统：
1. **输入 → 速度计算**（aiStep）
2. **速度 → 位置更新**（travel）
3. **碰撞检测与响应**（move）

如果实体不动，要检查：**aiStep 调用、travel 入口、碰撞计算**。

---

## Physics Pipeline

### **完整的物理流程**

```
Entity.tick()
  ↓
LivingEntity.tick()
  ↓
【检查】isEffectiveAi() ?
  ↓ true
LivingEntity.aiStep()
  ↓
【计算速度】根据输入（xxa, zza, jumping）
  ↓
LivingEntity.travel(Vec3)
  ↓
【应用重力】velocity.y -= 0.08
  ↓
【应用摩擦】velocity *= 0.91
  ↓
Entity.move(MoverType, Vec3)
  ↓
【碰撞检测】AABB vs World
  ↓
【更新位置】setPos(newX, newY, newZ)
```

---

## Key Methods

### **1. aiStep() - AI 与移动逻辑**

```java
// LivingEntity.aiStep()
@Override
protected void aiStep() {
    super.aiStep();
    
    // 1. 处理跳跃
    if (this.jumping && this.onGround()) {
        this.setDeltaMovement(this.getDeltaMovement().add(0, 0.42, 0));  // 跳跃初速度
    }
    
    // 2. 处理疾跑
    if (this.isSprinting()) {
        // 增加移动速度
    }
    
    // 3. 调用 travel()
    Vec3 movement = new Vec3(this.xxa, this.yya, this.zza);
    this.travel(movement);
    
    // 4. 其他逻辑（药水效果、碰撞推挤等）
}
```

**关键**：
- `xxa`：左右移动输入（-1.0 ~ 1.0）
- `zza`：前后移动输入（-1.0 ~ 1.0）
- `yya`：上下移动输入（飞行模式用）

---

### **2. travel(Vec3) - 移动计算**

```java
// LivingEntity.travel()
@Override
public void travel(Vec3 travelVector) {
    // 入口检查
    if (!this.isEffectiveAi() && !this.isControlledByLocalInstance()) {
        // 不处理物理（客户端控制的实体）
        this.setDeltaMovement(Vec3.ZERO);
        return;
    }
    
    double gravity = 0.08;
    
    // 计算移动向量
    Vec3 movement = this.getDeltaMovement();
    
    // 应用输入
    if (this.onGround()) {
        // 地面移动
        float friction = 0.6f;  // 方块摩擦力
        float speed = this.getSpeed();  // 移动速度
        
        Vec3 moveVec = this.getMoveVector(travelVector, speed, friction);
        this.setDeltaMovement(moveVec.x, movement.y, moveVec.z);
    }
    
    // 应用重力
    this.setDeltaMovement(this.getDeltaMovement().add(0, -gravity, 0));
    
    // 应用空气阻力
    this.setDeltaMovement(this.getDeltaMovement().multiply(0.98, 0.98, 0.98));
    
    // 实际移动
    this.move(MoverType.SELF, this.getDeltaMovement());
}
```

**关键检查**：
- `isEffectiveAi()` 必须返回 true（否则跳过物理）
- `onGround()` 影响移动方式（地面 vs 空中）
- `getSpeed()` 决定移动速度

---

### **3. move(MoverType, Vec3) - 碰撞与位置更新**

```java
// Entity.move()
@Override
public void move(MoverType moverType, Vec3 movement) {
    if (movement.equals(Vec3.ZERO)) return;
    
    // 1. 保存原始位置
    Vec3 originalMovement = movement;
    
    // 2. 碰撞检测
    movement = this.collide(movement);
    
    // 3. 更新位置
    this.setPos(
        this.getX() + movement.x,
        this.getY() + movement.y,
        this.getZ() + movement.z
    );
    
    // 4. 检测地面
    this.onGround = (movement.y != originalMovement.y && originalMovement.y < 0);
    
    // 5. 碰撞反馈
    if (movement.x != originalMovement.x) {
        this.setDeltaMovement(0, this.getDeltaMovement().y, this.getDeltaMovement().z);
        this.horizontalCollision = true;
    }
    if (movement.z != originalMovement.z) {
        this.setDeltaMovement(this.getDeltaMovement().x, this.getDeltaMovement().y, 0);
        this.horizontalCollision = true;
    }
}
```

---

## Collision Detection

### **AABB 碰撞检测**

```java
// Entity.collide()
private Vec3 collide(Vec3 movement) {
    AABB boundingBox = this.getBoundingBox();
    
    // 获取潜在碰撞的方块
    List<VoxelShape> shapes = this.level().getCollisions(
        this, 
        boundingBox.expandTowards(movement)
    );
    
    // X 轴碰撞
    double newX = movement.x;
    for (VoxelShape shape : shapes) {
        newX = shape.collideX(boundingBox, newX);
    }
    boundingBox = boundingBox.move(newX, 0, 0);
    
    // Y 轴碰撞
    double newY = movement.y;
    for (VoxelShape shape : shapes) {
        newY = shape.collideY(boundingBox, newY);
    }
    boundingBox = boundingBox.move(0, newY, 0);
    
    // Z 轴碰撞
    double newZ = movement.z;
    for (VoxelShape shape : shapes) {
        newZ = shape.collideZ(boundingBox, newZ);
    }
    
    return new Vec3(newX, newY, newZ);
}
```

**关键**：
- 碰撞检测是逐轴进行的（X → Y → Z）
- 如果某轴碰撞，该轴的移动被限制
- `horizontalCollision` 和 `verticalCollision` 标记碰撞状态

---

## Common Pitfalls

### **Pitfall 1: isEffectiveAi() 返回 false**

**症状**：
- xxa/zza 被设置
- aiStep() 被调用
- 但 travel() 入口检查失败，实体不动

**根因**：
```java
// LivingEntity.travel()
if (!this.isEffectiveAi() && !this.isControlledByLocalInstance()) {
    this.setDeltaMovement(Vec3.ZERO);  // ← 清零速度！
    return;
}
```

**修复**：
```java
public class BotPlayer extends ServerPlayer {
    @Override
    public boolean isEffectiveAi() {
        return true;  // ✅ 让 travel() 处理物理
    }
}
```

---

### **Pitfall 2: aiStep() 没有被调用**

**症状**：
- tick() 执行
- 但实体不移动

**根因**：
- `ServerPlayer.tick()` 不会自动调用 `aiStep()`（真实玩家由客户端驱动）
- Bot 没有客户端，必须手动调用

**修复**：
```java
public class BotPlayer extends ServerPlayer {
    @Override
    public void tick() {
        controller.onUpdate();  // 设置 xxa, zza
        this.aiStep();          // ✅ 手动调用物理
        super.tick();
    }
}
```

---

### **Pitfall 3: 输入值范围错误**

**症状**：
- Bot 移动速度异常（太快或太慢）

**根因**：
```java
// 错误：xxa 超出范围
bot.xxa = 10.0f;  // ❌ 应该是 -1.0 ~ 1.0

// 正确
bot.xxa = 1.0f;   // ✅ 全速前进
bot.xxa = 0.5f;   // ✅ 半速
```

**标准范围**：
- `xxa`：-1.0（左）~ 1.0（右）
- `zza`：-1.0（后）~ 1.0（前）
- `yya`：-1.0（下）~ 1.0（上）（飞行模式）

---

### **Pitfall 4: 速度累积**

**症状**：
- Bot 越跑越快，无法停止

**根因**：
```java
// 错误：累加速度
@Override
public void tick() {
    Vec3 vel = this.getDeltaMovement();
    this.setDeltaMovement(vel.add(0, 0, 0.1));  // ❌ 每 tick 加速
}

// 正确：设置输入，让 aiStep 计算速度
@Override
public void tick() {
    this.xxa = 1.0f;  // ✅ 设置输入
    this.aiStep();    // aiStep 会正确计算速度
}
```

---

### **Pitfall 5: 重力被禁用**

**症状**：
- Bot 悬浮在空中
- 不受重力影响

**根因**：
```java
bot.setNoGravity(true);  // ❌ 禁用重力
```

**修复**：
```java
bot.setNoGravity(false);  // ✅ 启用重力
```

---

## Movement Types

### **Ground Movement（地面移动）**

```java
// 在 travel() 中
if (this.onGround()) {
    float friction = this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement())
        .getFriction(this.level(), this.getBlockPosBelowThatAffectsMyMovement(), this);
    
    float speed = this.getSpeed() * (0.16277136f / (friction * friction * friction));
    
    Vec3 moveVec = this.getMoveVector(
        new Vec3(this.xxa, 0, this.zza), 
        speed, 
        friction
    );
    
    this.setDeltaMovement(moveVec.x, this.getDeltaMovement().y, moveVec.z);
}
```

**关键**：
- 地面摩擦力影响速度（冰面 0.98，普通方块 0.6）
- 速度计算使用魔法常数 `0.16277136f`

---

### **Air Movement（空中移动）**

```java
else {
    // 空中控制能力降低
    float speed = this.isSprinting() ? 0.026f : 0.02f;
    
    this.moveRelative(speed, new Vec3(this.xxa, 0, this.zza));
    this.setDeltaMovement(this.getDeltaMovement().multiply(0.91, 0.98, 0.91));
}
```

**关键**：
- 空中移动速度更慢
- 水平空气阻力 0.91，垂直 0.98

---

### **Water Movement（水中移动）**

```java
if (this.isInWater()) {
    double d0 = this.getY();
    
    // 水中移动
    this.moveRelative(0.02f, new Vec3(this.xxa, this.yya, this.zza));
    this.move(MoverType.SELF, this.getDeltaMovement());
    
    // 浮力
    if (this.getDeltaMovement().y <= 0.0) {
        this.setDeltaMovement(this.getDeltaMovement().multiply(1.0, 0.5, 1.0));
    }
    
    // 向上游
    if (this.jumping) {
        this.setDeltaMovement(this.getDeltaMovement().add(0, 0.04, 0));
    }
}
```

---

## Debugging Checklist

实体物理问题调试清单：

### **Step 1: 验证 aiStep 调用**
```java
@Override
protected void aiStep() {
    System.out.println("[aiStep] called, xxa=" + xxa + " zza=" + zza);
    super.aiStep();
}
```

- [ ] aiStep() 每 tick 被调用？
- [ ] xxa/zza 值正确（-1.0 ~ 1.0）？

---

### **Step 2: 验证 travel 调用**
```java
@Override
public void travel(Vec3 vec3) {
    System.out.println("[travel] called, vec3=" + vec3 + 
        " effectiveAi=" + isEffectiveAi());
    super.travel(vec3);
}
```

- [ ] travel() 被调用？
- [ ] isEffectiveAi() 返回 true？
- [ ] vec3 不是 (0,0,0)？

---

### **Step 3: 验证速度计算**
```java
@Override
public void travel(Vec3 vec3) {
    Vec3 velBefore = this.getDeltaMovement();
    super.travel(vec3);
    Vec3 velAfter = this.getDeltaMovement();
    
    System.out.println("[travel] velocity: " + velBefore + " → " + velAfter);
}
```

- [ ] 速度从 (0,0,0) 变成非零值？
- [ ] 速度方向正确（前进是正 Z）？

---

### **Step 4: 验证碰撞**
```java
@Override
public void move(MoverType moverType, Vec3 movement) {
    Vec3 before = this.position();
    super.move(moverType, movement);
    Vec3 after = this.position();
    
    System.out.println("[move] pos: " + before + " → " + after + 
        " collision=" + this.horizontalCollision);
}
```

- [ ] move() 被调用？
- [ ] 位置实际更新？
- [ ] horizontalCollision 状态正确？

---

## Reference Patterns

### **Pattern 1: 标准 Bot 物理实现**

```java
public class BotPlayer extends ServerPlayer {
    private BotController controller;
    
    @Override
    public void tick() {
        // 1. 设置输入
        controller.onUpdate();  // 设置 xxa, zza, jumping
        
        // 2. 执行物理
        this.aiStep();
        
        // 3. 其他逻辑
        super.tick();
    }
    
    @Override
    public boolean isEffectiveAi() {
        return true;
    }
}
```

---

### **Pattern 2: 自定义移动速度**

```java
public class FastBot extends BotPlayer {
    @Override
    public float getSpeed() {
        return super.getSpeed() * 2.0f;  // 2 倍速
    }
}
```

---

### **Pattern 3: 飞行 Bot**

```java
public class FlyingBot extends BotPlayer {
    @Override
    public void travel(Vec3 travelVector) {
        if (this.isFlying()) {
            // 自定义飞行物理
            this.moveRelative(0.1f, travelVector);
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.9, 0.9, 0.9));
        } else {
            super.travel(travelVector);
        }
    }
}
```

---

## Evidence Requirements

实施后需要以下证据：

**服务端日志**：
```
[aiStep] called, xxa=1.0 zza=0.0
[travel] called, vec3=(1.0, 0.0, 0.0) effectiveAi=true
[travel] velocity: (0.0, -0.08, 0.0) → (0.1, -0.08, 0.0)
[move] pos: (100.0, 64.0, 100.0) → (100.1, 63.92, 100.0)
```

**客户端验证**：
- [ ] Bot 移动（位置变化）
- [ ] 移动方向正确（W = 向前）
- [ ] 移动速度合理（不会瞬移）
- [ ] 碰墙时停止（不穿墙）
- [ ] 掉落时受重力影响
- [ ] 在地面时不会"飘"

**边界测试**：
- [ ] 撞墙时 horizontalCollision = true
- [ ] 落地时 onGround = true
- [ ] 水中移动变慢
- [ ] 跳跃正常

---

**This skill helps you avoid 8-12 hours of "why doesn't my entity move" debugging.**
