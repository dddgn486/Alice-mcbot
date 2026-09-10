# Alice 寻路系统重构计划

## 📊 当前架构分析

### **核心组件**

1. **AStarPathfinder**
   - A* 搜索算法实现
   - 移植自 Baritone
   - 限制：MAX_NODES=12000, MAX_MOVES=256, SEARCH_MARGIN=12
   - 使用零 heuristic（Dijkstra 顺序）

2. **SurfacePathfinder**
   - 曲面寻路门面
   - 只寻找"真实可通行曲面"的路径
   - 不破坏或放置方块

3. **MovementHelper**
   - 移动判定逻辑
   - `canWalkOn()`: 检查脚位是否可站立
   - `canWalkThrough()`: 检查身体格是否可穿过
   - `avoidWalkingInto()`: 危险方块（熔岩/火）

4. **PathExecutor**
   - 路径跟随器
   - **手动位置步进**（STEP_SPEED=0.5 格/tick）
   - 到达段后对齐 Y 并手动设置 onGround

5. **SoftMovementPrimitive**
   - 底层移动原语（似乎是旧的实现）

---

## 🐛 已知问题

### **问题 1：PathExecutor 使用手动 setPos()**

**当前实现**：
```java
// PathExecutor.tick()
bot.setPos(goalX, segmentGoal.getY(), goalZ);  // 手动传送
bot.setOnGround(true);                          // 手动设置着地
```

**问题**：
- ❌ 不使用原版物理引擎（travel()）
- ❌ 直接 setPos() 传送，不自然
- ❌ 手动设置 onGround，绕过了碰撞检测
- ❌ Bot 移动看起来像"瞬移"

**正确方案**：
- ✅ 使用 BotController 设置输入（xxa/zza）
- ✅ 让原版 travel() 处理移动
- ✅ 自然碰撞检测、摩擦力、重力

---

### **问题 2：注释说"服务端不跑 travel"**

**PathExecutor.java 第 12-14 行**：
```java
/**
 * <b>关键机制</b>:服务端 {@code ServerPlayer} 的移动/重力是「客户端权威」——
 * 真实玩家靠客户端发包驱动,假人没有客户端,服务端不跑 travel(重力不生效)。
 */
```

**这个注释已经过时了！**
- ✅ 我们已经修复了 `BotPlayer.isEffectiveAi() = true`
- ✅ 服务端 Bot 现在**可以跑 travel()**
- ✅ BotController 设置输入，原版物理正常工作

---

### **问题 3：STEP_SPEED 硬编码**

```java
private static final double STEP_SPEED = 0.5D;  // 格/tick
```

**问题**：
- ❌ 不使用 MOVEMENT_SPEED 属性
- ❌ 固定速度，不支持疾跑/潜行

**正确方案**：
- ✅ 通过 BotController 设置输入
- ✅ 让原版物理计算实际速度

---

## 🎯 重构目标

### **核心目标**：
**让 PathExecutor 使用 BotController + 原版物理，而不是手动 setPos()**

### **具体改进**：

1. ✅ **使用 BotController 设置输入**
   - 计算方向 → 设置 forward/strafing
   - 不再手动 setPos()

2. ✅ **使用原版物理引擎**
   - travel() 处理移动
   - 自然碰撞检测
   - 自动重力、摩擦力

3. ✅ **支持跳跃**
   - 检测需要跳跃的情况（上台阶）
   - 使用 controller.jumpOnce()

4. ✅ **移除过时的注释和代码**
   - PathExecutor 的"手动位置步进"注释
   - SoftMovementPrimitive 可能是旧实现

---

## 📝 重构步骤

### **Phase 1: 重构 PathExecutor（核心）**

**目标**：让 PathExecutor 使用 BotController，而不是手动 setPos()

**新设计**：
```java
public class PathExecutor {
    private final BotPlayer bot;
    private final List<BlockPos> path;
    private int index;
    private BlockPos currentGoal;
    
    public Status tick() {
        if (index >= path.size()) return Status.DONE;
        
        BlockPos goal = path.get(index);
        
        // 1. 计算到目标的方向
        double dx = (goal.getX() + 0.5) - bot.getX();
        double dz = (goal.getZ() + 0.5) - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        
        // 2. 到达当前段
        if (distance < 0.3) {
            index++;
            return tick();  // 继续下一段
        }
        
        // 3. 设置输入方向（让原版物理处理移动）
        double yaw = Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0;
        bot.setYRot((float) yaw);
        bot.controller().setForward(1.0F);  // ← 关键！设置输入
        bot.controller().setSprinting(true);
        
        // 4. 检测是否需要跳跃（上台阶）
        if (needJump(goal)) {
            bot.controller().jumpOnce();
        }
        
        return Status.MOVING;
    }
    
    private boolean needJump(BlockPos goal) {
        // 检测前方有障碍物或需要上台阶
        // ...
    }
}
```

**优点**：
- ✅ 使用原版物理（自然、流畅）
- ✅ 支持碰撞检测
- ✅ 支持疾跑、跳跃
- ✅ Bot 移动看起来像真实玩家

---

### **Phase 2: 清理旧代码**

1. **检查 SoftMovementPrimitive**
   - 是否还在使用？
   - 是否是旧的"手动 travel()"实现？
   - 可能需要移除或重构

2. **更新注释**
   - PathExecutor 的"客户端权威"注释已过时
   - MovementMode 的说明

3. **清理 MovementMode**
   - `HARD_PATH` 是什么意思？
   - 是否还需要这个枚举？

---

### **Phase 3: 测试和优化**

1. **测试基本寻路**
   - Bot 能否正常寻路？
   - 移动是否流畅？

2. **测试跳跃**
   - 上台阶是否正常？
   - 跳跃时机是否准确？

3. **测试障碍物**
   - 动态障碍物是否触发重新寻路？

4. **性能测试**
   - A* 搜索性能是否足够？
   - 是否需要优化 MAX_NODES？

---

## 🚧 潜在风险

### **风险 1：原版物理可能不够精确**

**问题**：
- A* 路径假设 Bot 能精确到达每个段
- 但原版物理有摩擦力、碰撞偏移

**缓解**：
- 放宽到达判定（SEGMENT_ARRIVE = 0.5）
- 如果偏离路径太远，重新寻路

---

### **风险 2：跳跃时机难以判断**

**问题**：
- 何时需要跳跃？
- 跳跃持续时间（jumpOnce() = 2 ticks）

**缓解**：
- 检测前方 1 格是否有障碍物
- 检测目标 Y 是否比当前高

---

### **风险 3：与现有任务系统的兼容性**

**问题**：
- MineTask、PlaceTask 等可能依赖旧的 PathExecutor

**缓解**：
- 保持 PathExecutor 的接口不变
- 只改内部实现

---

## 📚 参考项目

### **mc_aiplayer**
- 使用 ActionPack（类似我们的 BotController）
- FakePlayerMotion 可能有类似的路径跟随逻辑

### **Baritone**
- PathExecutor 的实现
- Movement primitives（直走、对角、跳跃）

---

## 🐋 总结

**当前问题**：
- PathExecutor 使用手动 setPos()，不使用原版物理
- 注释说"服务端不跑 travel"（已过时）
- Bot 移动像"瞬移"，不自然

**重构目标**：
- 让 PathExecutor 使用 BotController + 原版物理
- 移除手动 setPos()
- 支持自然移动、跳跃、碰撞检测

**预期效果**：
- ✅ Bot 移动流畅自然
- ✅ 使用原版物理引擎
- ✅ 支持疾跑、跳跃
- ✅ 代码更简洁、可维护

**下一步**：
1. 先重构 PathExecutor（Phase 1）
2. 测试基本寻路是否正常
3. 如果有问题，再调整细节

准备好了吗？🐋💙
