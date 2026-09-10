# Alice 移动系统分析报告

## 📊 当前移动系统架构

### 1. BotMiner 的移动方式

**使用的组件**:
- `SurfacePathfinder` - A* 曲面寻路算法
- `PathExecutor` - 路径执行器

**工作流程**:
```
1. 选择站位（pickStandCandidates）
   └─ 生成候选站位（目标周围 2 格内）
   
2. 评估站位可达性（chooseReachableStand）
   └─ 对每个候选使用 A* 寻路
   └─ 选择"路径可达 + 视线清晰"的站位
   
3. 执行移动（PathExecutor）
   └─ 逐段执行路径
   └─ 遇阻时重新规划（最多 2 次）
```

**移动方式**: ✅ **使用物理移动，不是瞬移**
- 通过 `PathExecutor` 执行 Movement 原语
- Movement 原语使用 `setDeltaMovement()` + `travel()`
- 符合 Minecraft 物理规则

---

### 2. 两种伐木任务的移动

#### **RegionLumberTask (区域伐木)**
```java
// 不主动移动
// Bot 站在原地，挨个砍附近的树
// 依赖 BotMiner 的站位选择
```

**特点**:
- ✅ Bot 在固定区域内工作
- ✅ 使用 BotMiner 的 A* 寻路选择站位
- ❌ 没有主动移动逻辑

#### **ContinuousLumberTask (连续伐木)**
```java
// 也不主动移动
// 扫描附近的树 → 依赖 BotMiner 移动到站位
```

**特点**:
- ✅ 自动寻找下一棵树
- ✅ 使用 BotMiner 的 A* 寻路
- ❌ 没有长距离移动能力（只能砍附近的树）

---

## 🐛 你观察到的问题

### 问题 1: "有时会停顿一下"

**可能原因**:

1. **站位评估阶段**
   ```java
   // BotMiner 需要评估多个候选站位
   for (BlockPos candidate : standCandidates) {
       SurfacePathfinder.Result result = SurfacePathfinder.find(bot, current, candidate);
       // A* 寻路需要时间
   }
   ```
   - ⏱️ A* 计算需要 1-3 tick
   - ⏱️ 多个候选站位需要遍历
   - **表现**: Bot 短暂停顿，然后开始移动

2. **路径重新规划**
   ```java
   if (executor.wasObstructed() && pathRetries < 2) {
       pathRetries++;
       // 重新规划路径
   }
   ```
   - ⏱️ 环境变化（砍掉的树叶掉落）导致路径受阻
   - ⏱️ 重新计算路径
   - **表现**: Bot 移动途中停顿，然后继续

3. **视线检查失败**
   ```java
   if (!lineOfSightClear()) {
       failureReason = "line_of_sight_blocked";
       // 等待 Task 层清障
   }
   ```
   - 🌿 树叶遮挡视线
   - ⏱️ MineTask 需要清障
   - **表现**: Bot 到达站位后停顿，清障后继续

---

### 问题 2: "像被方块堵住一样走不动"

**可能原因**:

1. **PathExecutor 卡住**
   ```java
   PathExecutor.Status pathStatus = executor.tick();
   if (pathStatus == PathExecutor.Status.FAILED) {
       // 路径失败
   }
   ```
   - 🧱 Movement 原语执行失败
   - 🧱 Bot 被方块卡住（碰撞箱问题）
   - **表现**: Bot 原地不动或反复尝试

2. **站位选择失败回退**
   ```java
   if (choice == null) {
       failureReason = "no_path";
       return Status.FAILED;
   }
   ```
   - ❌ 所有候选站位都不可达
   - ❌ A* 找不到路径
   - **表现**: Bot 不移动，任务失败

3. **物理碰撞问题**
   - 🌿 掉落的树叶形成临时障碍
   - 🧱 Bot 碰撞箱与方块重叠
   - **表现**: Bot 被"卡住"，无法移动

---

## 🔍 具体案例分析

### 案例 1: 砍树时停顿

**观察到的现象**:
- Bot 走到树旁边
- 停顿 1-2 秒
- 开始挖掘

**可能的原因**:
```
Bot 到达站位 → 视线检查
                └─ 树叶遮挡
                └─ BotMiner 返回 FAILED
                └─ MineTask 清障 (局部清障)
                └─ 清掉 1-2 片树叶
                └─ 重新开始挖掘
```

**日志分析**:
```
[INFO] 曲面站位已选: stand=(x,y,z)
[WARN] mine 失败(视线受阻): target=(x,y,z) (保持站位，等待清障)
[INFO] 局部清障(1/2): 当前站位直接挖 (x,y,z)  ← 清障树叶
[INFO] 清障完成: 已挖掉遮挡 → 继续挖原目标
[INFO] 开始挖掘: target=(x,y,z)
```

---

### 案例 2: 被方块堵住

**观察到的现象**:
- Bot 尝试移动
- 被树干或树叶卡住
- 反复尝试同一个动作

**可能的原因**:
```
PathExecutor 执行 WalkMovement
  └─ setDeltaMovement(移动向量)
  └─ travel() 应用物理
  └─ 碰撞检测: Bot 与方块重叠
      └─ 移动被取消
      └─ Bot 停在原地
      └─ PathExecutor 认为"移动失败"
          └─ wasObstructed() = true
          └─ 重新规划路径
```

**根本原因**:
- Movement 原语没有考虑动态障碍（掉落的树叶）
- 碰撞箱计算可能有问题
- A* 规划的路径经过了不稳定的方块

---

## 🎯 解决方案建议

### 短期优化（立即可做）

#### 1. 增加诊断日志
在 BotMiner 和 PathExecutor 中添加详细日志：

```java
// BotMiner.java
if (executor != null) {
    PathExecutor.Status pathStatus = executor.tick();
    if (pathStatus == PathExecutor.Status.MOVING) {
        // 🔍 探针：打印移动进度
        BotLog.info("🚶 Moving: segment {}/{}, pos={}", 
                executor.getCurrentSegment(), 
                executor.getTotalSegments(),
                bot.blockPosition().toShortString());
    }
}

// PathExecutor.java
if (wasObstructed()) {
    BotLog.warn("⚠️ 路径受阻: segment={}, obstacle={}, bot_pos={}", 
            currentSegment, 
            getObstacleType(),
            bot.blockPosition().toShortString());
}
```

#### 2. 优化站位选择

```java
// 在选择站位时，避开不稳定的位置
private List<BlockPos> pickStandCandidates(ServerLevel level) {
    List<BlockPos> candidates = new ArrayList<>();
    
    // ... 生成候选 ...
    
    // 🔧 过滤：避开树叶上方的站位
    candidates = candidates.stream()
        .filter(pos -> {
            BlockState below = level.getBlockState(pos.below());
            // 避开树叶、避开空气
            return !below.is(BlockTags.LEAVES) && !below.isAir();
        })
        .collect(Collectors.toList());
    
    return candidates;
}
```

#### 3. 增强路径恢复

```java
// 路径受阻时，不只是重新规划，而是尝试"微调"
if (executor.wasObstructed() && pathRetries < 3) {
    pathRetries++;
    
    // 🔧 尝试 1: 微调当前位置（向后退 1 格）
    if (pathRetries == 1) {
        BotLog.info("路径受阻，尝试微调位置");
        bot.setDeltaMovement(bot.getLookAngle().scale(-0.3));
        return Status.MOVING;
    }
    
    // 🔧 尝试 2: 重新规划路径
    BotLog.warn("路径受阻,重新规划({}/3)", pathRetries);
    // ... 重新规划 ...
}
```

---

### 中期改进（需要一些工作）

#### 1. 动态障碍物检测

```java
// 新增：ObstacleDetector.java
public class ObstacleDetector {
    /**
     * 检测路径上的动态障碍物（如掉落的树叶）
     */
    public static List<BlockPos> detectDynamicObstacles(Level level, List<BlockPos> path) {
        return path.stream()
            .filter(pos -> {
                BlockState state = level.getBlockState(pos);
                // 检测不稳定方块
                return state.is(BlockTags.LEAVES) 
                    || state.getBlock() instanceof FallingBlock;
            })
            .collect(Collectors.toList());
    }
}
```

#### 2. 路径平滑

```java
// PathExecutor 执行前，先平滑路径
public class PathSmoother {
    /**
     * 移除路径中的"锯齿"
     */
    public static List<BlockPos> smooth(List<BlockPos> path) {
        List<BlockPos> smoothed = new ArrayList<>();
        
        for (int i = 0; i < path.size(); i++) {
            // 尝试跳过中间点，直线到达
            if (i + 2 < path.size()) {
                BlockPos current = path.get(i);
                BlockPos next2 = path.get(i + 2);
                if (canDirectPath(current, next2)) {
                    smoothed.add(current);
                    i++; // 跳过中间点
                    continue;
                }
            }
            smoothed.add(path.get(i));
        }
        
        return smoothed;
    }
}
```

#### 3. 站位预留空间

```java
// 在选择站位时，确保周围有足够空间
private boolean hasEnoughClearance(ServerLevel level, BlockPos pos) {
    // 检查 3x3x3 区域是否有足够空间
    for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
            BlockPos check = pos.offset(dx, 0, dz);
            if (!MovementHelper.canWalkThrough(level, check.above())) {
                return false;
            }
        }
    }
    return true;
}
```

---

### 长期改进（架构级）

#### 1. 分离"长距离寻路"和"局部避障"

```
长距离移动（跨区域）:
  └─ A* PathPlanner（网格级别）
  
局部避障（同一区域内）:
  └─ 动态避障系统
  └─ 实时调整移动向量
```

#### 2. 增加"移动状态机"

```java
public enum MovementState {
    PLANNING,    // 规划路径
    MOVING,      // 执行移动
    STUCK,       // 被卡住
    RECOVERING,  // 恢复中
    ARRIVED      // 已到达
}
```

#### 3. 实现"智能等待"

```java
// 检测到树叶掉落时，等待其稳定
if (detectFallingBlocks(path)) {
    BotLog.info("检测到掉落方块，等待 {} ticks", WAIT_DURATION);
    waitTicks = WAIT_DURATION;
    return Status.WAITING;
}
```

---

## 📝 测试建议

### 1. 添加调试日志
在 BotMiner 添加移动探针：

```java
// PathExecutor tick 时
if (pathStatus == PathExecutor.Status.MOVING) {
    BotLog.info("🚶 [MOVING] segment {}/{}, delta={}, pos={}, vel={}",
            executor.getCurrentSegment(),
            executor.getTotalSegments(),
            bot.getDeltaMovement().length(),
            bot.blockPosition().toShortString(),
            bot.getDeltaMovement().toShortString());
}
```

### 2. 记录卡住事件
```java
// 检测 Bot 是否长时间未移动
private int stuckTicks = 0;
private BlockPos lastPos = null;

if (bot.blockPosition().equals(lastPos)) {
    stuckTicks++;
    if (stuckTicks > 20) {
        BotLog.warn("⚠️ Bot 可能卡住: pos={}, stuckFor={} ticks", 
                bot.blockPosition().toShortString(), stuckTicks);
    }
} else {
    stuckTicks = 0;
    lastPos = bot.blockPosition();
}
```

### 3. 复现测试场景
- 场景 1: 在茂密森林中砍树（树叶密集）
- 场景 2: 在山坡上砍树（高度变化）
- 场景 3: 在狭窄空间砍树（周围有墙）

---

## 🎯 总结

### 当前系统
- ✅ **不使用瞬移**，使用物理移动（A* + PathExecutor）
- ✅ 符合 Minecraft 物理规则
- ⚠️ 在复杂环境（树叶密集）下可能卡住
- ⚠️ 缺少动态障碍物处理

### 停顿原因
1. **正常停顿**: A* 计算、站位评估、清障等待
2. **异常停顿**: 路径受阻、碰撞箱冲突、物理卡住

### 优先改进
1. ✅ 增加详细日志（立即）
2. ✅ 优化站位选择（避开不稳定方块）
3. ✅ 增强路径恢复逻辑

