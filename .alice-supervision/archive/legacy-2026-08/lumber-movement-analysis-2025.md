# 伐木系统移动问题分析与解决方案

## 📊 **问题诊断**

### 现状分析

#### **1. ContinuousLumberTask（自动伐木器）**
- ✅ 使用 BotMiner 进行移动
- ✅ 有完整的寻路系统（A* + PathExecutor）
- ✅ 有碰撞检测和卡住检测
- **问题**：基本没有移动问题

#### **2. RegionLumberTask（区域伐木规划器）**
- ❌ 使用简单的 `setDeltaMovement()` 直接设置速度
- ❌ 没有寻路（A*）
- ❌ 没有避障逻辑
- ❌ 没有卡住检测
- **问题**：这是主要问题所在！

---

## 🐛 **具体问题**

### 问题 1: RegionLumberTask 的 moveTowards() 方法过于简单

**当前实现**：
```java
private void moveTowards(Vec3 target) {
    Vec3 botPos = bot.position();
    Vec3 direction = target.subtract(botPos).normalize();
    
    // 直接设置速度向量
    Vec3 movement = direction.scale(0.2);
    bot.setDeltaMovement(movement.x, bot.getDeltaMovement().y, movement.z);
}
```

**问题**：
1. **没有碰撞检测**：遇到障碍物（树干、地形）时，Bot 会被卡住
2. **没有寻路**：只是朝目标方向移动，无法绕过障碍
3. **没有卡住处理**：被卡住后无法自动解决

**症状**：
- Bot 移动到树干前被卡住
- Bot 被地形（凸起、坑洞）卡住
- 把障碍方块挖掉后，Bot 又能继续移动

---

### 问题 2: 停顿现象

#### **正常停顿**（1-2 tick）
**原因**：PathExecutor 的物理结算阶段
```java
// PathExecutor.java L114-115
// 物理结算：让 travel() 处理重力、摩擦和落地
BasicMovement.settle(bot);
```

**过程**：
1. Bot 到达目标位置附近（水平距离 < 0.1）
2. 进入 settle 阶段，调用 `bot.travel(Vec3.ZERO)`
3. 等待物理引擎结算（重力、摩擦、落地）
4. 检查 `bot.onGround()` 确认站稳
5. 继续下一段路径

这是**正常的**，因为需要确保 Bot 站稳后再继续。

#### **异常停顿**（3+ tick 或更久）
**可能原因**：
1. **卡在方块内部**：寻路计算的位置有碰撞问题
2. **settle 超时**：`settleTicks > MAX_SETTLE_TICKS (10)` 
3. **寻路失败**：A* 找不到路径，Bot 原地等待

---

## 🔧 **解决方案**

### 方案 A：让 RegionLumberTask 使用 PathExecutor（推荐）

**思路**：RegionLumberTask 不应该自己实现移动，应该复用已有的寻路系统。

**实现**：
```java
public class RegionLumberTask {
    private PathExecutor pathExecutor;  // 新增
    
    private Status tick() {
        // ... 扫描树木 ...
        
        // 如果需要移动到下一棵树
        if (needsToMoveToNextTree) {
            if (pathExecutor == null) {
                // 规划路径
                List<BlockPos> path = SurfacePathfinder.plan(
                    bot.serverLevel(),
                    bot.blockPosition(),
                    nextTree.getBasePos(),
                    FluidRiskPolicy.REJECT_FLUID
                );
                
                if (path == null) {
                    BotLog.warn("无法到达下一棵树: {}", nextTree.getBasePos());
                    // 跳过这棵树
                    return Status.RUNNING;
                }
                
                pathExecutor = new PathExecutor(bot, path);
            }
            
            // 执行移动
            PathExecutor.Status status = pathExecutor.tick();
            if (status == PathExecutor.Status.ARRIVED) {
                pathExecutor = null;
                // 到达，开始砍树
                return Status.RUNNING;
            } else if (status == PathExecutor.Status.FAILED) {
                pathExecutor = null;
                // 移动失败，跳过这棵树
                return Status.RUNNING;
            }
            
            return Status.RUNNING;
        }
        
        // ... 砍树逻辑 ...
    }
}
```

**优点**：
- ✅ 复用成熟的寻路系统
- ✅ 有碰撞检测和避障
- ✅ 有卡住检测和恢复
- ✅ 与 ContinuousLumberTask 行为一致

---

### 方案 B：改进 moveTowards() 方法（不推荐）

**思路**：在 moveTowards() 中添加简单的碰撞检测。

**实现**：
```java
private void moveTowards(Vec3 target) {
    Vec3 botPos = bot.position();
    Vec3 direction = target.subtract(botPos).normalize();
    
    // 检查前方是否有障碍
    BlockPos nextPos = BlockPos.containing(botPos.add(direction.scale(0.5)));
    if (!MovementHelper.canWalkThrough(bot.serverLevel(), nextPos)) {
        // 前方有障碍，尝试跳跃或停止
        if (bot.onGround()) {
            bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42D, bot.getDeltaMovement().z);
        }
        return;
    }
    
    // 设置移动速度
    Vec3 movement = direction.scale(0.2);
    bot.setDeltaMovement(movement.x, bot.getDeltaMovement().y, movement.z);
}
```

**问题**：
- ❌ 只能处理简单障碍
- ❌ 无法绕过复杂地形
- ❌ 没有全局寻路

---

### 方案 C：让 RegionLumberTask 切换到站桩模式（最简单）

**思路**：RegionLumberTask 不主动移动，只砍当前区域内能够到达的树。

**实现**：
```java
// 只扫描 BotMiner 能够到达的树（A* 可达）
private void scanTrees() {
    for (BlockPos pos : scanArea) {
        if (isLog(pos)) {
            // 检查是否可达
            List<BlockPos> path = SurfacePathfinder.plan(...);
            if (path != null) {
                pendingTrees.add(detectTree(pos));
            }
        }
    }
}

// 让 BotMiner 负责移动
private Status tick() {
    if (currentTree == null) {
        selectNextTree();
    }
    
    // 使用 BotMiner 挖掘（它会自己寻路移动）
    BotMiner.Status status = miner.tick();
    // ...
}
```

**优点**：
- ✅ 不需要实现移动逻辑
- ✅ 完全依赖 BotMiner 的寻路
- ✅ 行为与 ContinuousLumberTask 一致

**缺点**：
- ❌ 失去了"主动移动到远处树木"的能力
- ❌ 只能砍附近的树

---

## 📊 **推荐方案对比**

| 方案 | 复杂度 | 效果 | 兼容性 | 推荐度 |
|------|--------|------|--------|--------|
| **A: 使用 PathExecutor** | 中 | 完美 | ✅ | ⭐⭐⭐⭐⭐ |
| **B: 改进 moveTowards** | 低 | 一般 | ✅ | ⭐⭐ |
| **C: 站桩模式** | 最低 | 良好 | ✅ | ⭐⭐⭐⭐ |

---

## 🎯 **建议行动**

### 短期（立即）
实施**方案 C**（站桩模式）：
- 删除 RegionLumberTask 的 moveTowards() 方法
- 让 BotMiner 负责所有移动
- 只扫描可达的树木

### 中期（后续优化）
实施**方案 A**（PathExecutor 集成）：
- 为 RegionLumberTask 添加 PathExecutor
- 实现树木之间的智能移动
- 保持与 ContinuousLumberTask 行为一致

---

## 🧪 **验证方法**

### 测试场景 1：RegionLumberTask 遇到障碍
1. 在有树木的区域放置一些障碍方块
2. 使用 `/alice lumber` 规划区域伐木
3. 观察 Bot 是否被卡住

**当前行为**：Bot 会被卡住，无法绕过障碍  
**期望行为**：Bot 能够绕过障碍或跳跃

### 测试场景 2：ContinuousLumberTask 连续砍树
1. 在开阔区域种植多棵树
2. 使用自动伐木器
3. 观察 Bot 在树木之间的移动

**当前行为**：Bot 使用 BotMiner 寻路，移动流畅  
**停顿现象**：到达树基座时会有 1-2 tick 的正常停顿（物理结算）

---

## 📝 **日志分析**

### 正常移动日志
```
[INFO] 路径规划成功: from=(x1,y1,z1) to=(x2,y2,z2) segments=5
[INFO] 路径执行: segment 1/5
[INFO] 路径执行: segment 2/5
...
[INFO] 路径执行完成: 到达目标
```

### 卡住日志
```
[WARN] 路径执行失败: 卡住 30 tick, 位置 (x, y, z)
```

### Settle 超时日志
```
[WARN] 路径段 X 物理结算超时: settleTicks > 10
```

---

## 🔮 **未来改进**

1. **动态避障**：移动过程中实时检测障碍并重新规划路径
2. **跳跃优化**：更智能的跳跃时机选择
3. **地形适应**：根据地形类型选择最优移动策略
4. **协作移动**：多 Bot 之间避让，不互相阻挡

---

## ✨ **总结**

**问题根源**：  
RegionLumberTask 使用简单的 `setDeltaMovement()` 进行移动，没有寻路和避障，容易被方块卡住。

**解决方案**：  
让 RegionLumberTask 使用 PathExecutor（方案 A）或切换到站桩模式（方案 C），复用已有的成熟寻路系统。

**停顿现象**：  
1-2 tick 的停顿是正常的物理结算，不是 bug。异常停顿（3+ tick）才需要调查。
