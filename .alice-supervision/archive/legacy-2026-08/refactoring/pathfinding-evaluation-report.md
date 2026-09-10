# Alice 寻路系统评估报告

## 🔍 **调查结论**

### **关键发现：新旧系统并存，但用错了！**

Alice 项目中有**两套寻路实现**：

1. **旧系统（PathExecutor）**
   - ❌ 使用手动 `setPos()` 传送
   - ❌ 不使用原版物理引擎
   - 🔴 **正在被使用**（MineTask, PlaceTask, DropCollectionTask）
   
2. **新系统（SoftMovementPrimitive + FollowTask）**
   - ✅ 使用 `NATIVE_TRAVEL`（原版物理）
   - ✅ 正确的物理结算
   - 🟡 **被标记为 @Deprecated，已冻结**

**问题：新的反而被废弃了！旧的还在用！这完全反了！**

---

## 📊 **代码质量评估**

### **SoftMovementPrimitive.java** 🟢

**质量：优秀**

```java
// 支持两种后端
enum Backend {
    SELF_MOVE,      // bot.move(MoverType.SELF, delta)
    NATIVE_TRAVEL   // bot.travel() + 设置输入 ✅
}

// NATIVE_TRAVEL 模式（正确的方式）
public static Step applyToward(ServerPlayer bot, double targetX, double targetZ,
                               double maxStep, Backend backend) {
    Step step = toward(bot, targetX, targetZ, maxStep);
    bot.setYRot(step.yaw());
    if (backend == Backend.NATIVE_TRAVEL) {
        bot.xxa = 0.0F;
        bot.zza = 1.0F;
        bot.travel(new Vec3(0.0, 0.0, 1.0));  // ← 使用原版物理！
    }
    return step;
}

// 物理结算（重力、摩擦、落地）
public static void settle(ServerPlayer bot) {
    bot.xxa = 0.0F;
    bot.zza = 0.0F;
    bot.travel(Vec3.ZERO);  // ← 零输入，让原版物理处理
}

// 跳跃支持
public static Step applyJumpToward(...) {
    bot.setJumping(true);
    bot.travel(...);
}
```

**优点**：
- ✅ 使用原版物理引擎（travel()）
- ✅ 支持跳跃（applyJumpToward）
- ✅ 有物理结算（settle）
- ✅ 支持两种后端（灵活）
- ✅ 代码清晰、注释完善

**缺点**：
- ⚠️ 直接设置 bot.xxa/zza，绕过了 BotController
- ⚠️ 但这不是大问题，可以改成调用 BotController

**结论**：
- 🟢 **这是正确的实现！应该保留并推广！**
- 🟢 **可以作为重构 PathExecutor 的参考**

---

### **FollowTask.java** 🟢

**质量：优秀**

```java
// 使用 SoftMovementPrimitive 的正确方式
double horizontal = Math.sqrt(dx * dx + dz * dz);
if (horizontal > ARRIVE) {
    SoftMovementPrimitive.applyToward(bot, goalX, goalZ, horizontal,
            SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
    return Status.RUNNING;
}

// 到达后物理结算
boolean settled = MovementHelper.isStandingAtFootPos(level, bot, segment) && bot.onGround();
if (settled) {
    index++;
    return Status.RUNNING;
}
SoftMovementPrimitive.settle(bot);
```

**优点**：
- ✅ 使用原版物理（NATIVE_TRAVEL）
- ✅ 正确的到达判定（isStandingAtFootPos）
- ✅ 有物理结算（settle）
- ✅ 有速度扰动检测（velocityDisruption）
- ✅ 有碰撞检测和即时重规划

**为什么被废弃？**

注释说：
```java
/**
 * ⚠️ FROZEN: 此任务已冻结，等待 BotController 重构完成后重新实现。
 * 原因：依赖 SoftMovementPrimitive 直接调用 travel()，与新的输入驱动架构冲突。
 */
```

**但这是误解！**
- SoftMovementPrimitive 用的就是正确的方式
- 和 BotController 的方式是一样的（设置输入 → travel()）
- **应该解冻，而不是废弃！**

**结论**：
- 🟢 **这是正确的实现！应该解冻并作为参考！**
- 🟢 **可以直接移除 @Deprecated 标记**

---

### **PathExecutor.java** 🔴

**质量：差**

```java
// 手动传送（错误）
bot.setPos(goalX, segmentGoal.getY(), goalZ);
bot.setOnGround(true);
bot.fallDistance = 0.0F;

// 硬编码速度
private static final double STEP_SPEED = 0.5D;
```

**问题**：
- ❌ 手动 setPos() 传送，不自然
- ❌ 不使用原版物理引擎
- ❌ 绕过碰撞检测
- ❌ 硬编码速度，不支持疾跑/潜行
- ❌ 手动设置 onGround，绕过落地判定

**注释过时**：
```java
/**
 * <b>关键机制</b>:服务端 {@code ServerPlayer} 的移动/重力是「客户端权威」——
 * 真实玩家靠客户端发包驱动,假人没有客户端,服务端不跑 travel(重力不生效)。
 */
```

**这个注释已经过时了！**
- ✅ 我们已经修复了 `BotPlayer.isEffectiveAi() = true`
- ✅ 服务端 Bot 现在可以跑 travel()
- ✅ SoftMovementPrimitive 已经证明了这一点

**结论**：
- 🔴 **必须重写！**
- 🔴 **改用 SoftMovementPrimitive 的方式**

---

## 🎯 **重构策略**

### **Phase 1: 改造 PathExecutor（立即行动）**

**目标**：让 PathExecutor 使用 SoftMovementPrimitive

**实现**：
```java
public class PathExecutor {
    public Status tick() {
        if (index >= path.size()) return Status.DONE;
        
        BlockPos segment = path.get(index);
        double goalX = segment.getX() + 0.5D;
        double goalZ = segment.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        
        // 到达当前段
        if (horizontal <= SEGMENT_ARRIVE) {
            // ✅ 使用物理结算，而不是手动 setPos()
            boolean settled = MovementHelper.isStandingAtFootPos(level, bot, segment) 
                           && bot.onGround();
            if (settled) {
                index++;
                return tick();  // 继续下一段
            }
            SoftMovementPrimitive.settle(bot);  // 物理结算
            return Status.MOVING;
        }
        
        // ✅ 使用 NATIVE_TRAVEL，而不是手动 setPos()
        SoftMovementPrimitive.applyToward(bot, goalX, goalZ, horizontal,
                SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
        return Status.MOVING;
    }
}
```

**优点**：
- ✅ 使用原版物理（自然移动）
- ✅ 正确的碰撞检测
- ✅ 不需要硬编码速度
- ✅ 接口保持不变（兼容现有任务）

---

### **Phase 2: 解冻 FollowTask（清理误解）**

**行动**：
1. 移除 `@Deprecated` 标记
2. 更新注释，说明它是正确的实现
3. 作为其他任务的参考

---

### **Phase 3: 统一到 BotController（可选）**

**目标**：让 SoftMovementPrimitive 调用 BotController

**当前**：
```java
// SoftMovementPrimitive 直接设置
bot.xxa = 0.0F;
bot.zza = 1.0F;
bot.travel(...);
```

**改进**：
```java
// 通过 BotController
bot.controller().setForward(1.0F);
// BotController.onUpdate() 会设置 bot.zza = 1.0F
// 然后 BotPlayer.tick() 会调用 travel()
```

**但这不是必须的！** 当前方式已经可以工作了。

---

## 📋 **第一层目标评估**

回顾你的目标：

### **第一层：基础可行性**

1. ✅ **能找到路**：AStarPathfinder 算法没问题
   - 需要调整：MAX_NODES, SEARCH_MARGIN
   
2. ❓ **能走过去**：PathExecutor 有问题
   - 🔴 当前：手动 setPos()，不可靠
   - 🟢 改进：使用 SoftMovementPrimitive（已验证）
   
3. ✅ **相对最短**：使用 Dijkstra（零 heuristic），已保证

---

## 🚀 **推荐行动**

### **立即行动（Phase 1）**：
1. **改造 PathExecutor**，使用 SoftMovementPrimitive
2. **测试**：MineTask, PlaceTask 是否正常工作
3. **移除过时注释**

### **短期行动（Phase 2）**：
1. **解冻 FollowTask**，移除 @Deprecated
2. **更新文档**，说明正确的实现方式

### **长期行动（Phase 3）**：
1. 添加跳跃支持（检测上台阶）
2. 添加挖掘+搭建支持（第二层目标）
3. 优化 A* 参数（MAX_NODES, SEARCH_MARGIN）

---

## 🐋 **总结**

**好消息**：
- ✅ Alice 已经有正确的实现（SoftMovementPrimitive + FollowTask）
- ✅ 不需要完全重写，只需要推广正确的实现
- ✅ 第一层目标（1 和 3）已经达成，只需要修复目标 2

**坏消息**：
- ❌ 正确的实现被废弃了
- ❌ 错误的实现还在使用
- ❌ 有误解（认为 SoftMovementPrimitive 与 BotController 冲突）

**下一步**：
- 🚀 改造 PathExecutor，使用 SoftMovementPrimitive
- 🚀 测试并验证
- 🚀 解冻 FollowTask

**我的小鲸鱼，我们已经有了正确的实现，只是被埋没了！把它挖出来就好！** 🐋💙
