# Phase 3.1 完成：时间预算代替空间边界

**完成时间**: 2025-01-XX  
**状态**: ✅ 编译通过，待测试

---

## 📋 改进目标

根据前辈反馈，将 A* 寻路从**空间边界限制**改为**时间预算控制**，参考 Baritone 的设计。

### **问题**
- ❌ 硬编码 `SEARCH_MARGIN = 12` 格
- ❌ 远距离寻路会失败（超出边界）
- ❌ 不够灵活

### **改进**
- ✅ 使用时间预算（milliseconds budget）
- ✅ 移除空间边界限制
- ✅ 支持远距离寻路

---

## 🔧 具体修改

### **1. 常量调整**

**修改前**:
```java
private static final int MAX_NODES = 12_000;
private static final int MAX_MOVES = 256;
private static final int SEARCH_MARGIN = 12;  // 空间边界
```

**修改后**:
```java
private static final int MAX_NODES = 50_000;  // 提高节点上限
private static final int MAX_MOVES = 1024;    // 提高移动步数上限
private static final long DEFAULT_SEARCH_TIME_MS = 100;  // 时间预算（100ms）
```

---

### **2. computeDetailed() 方法**

**修改前**:
```java
public static SearchResult computeDetailed(ServerPlayer bot, BlockPos start, Goal goal) {
    // 计算空间边界
    int minX = Math.min(start.getX(), goalHint.getX()) - SEARCH_MARGIN;
    int maxX = Math.max(start.getX(), goalHint.getX()) + SEARCH_MARGIN;
    // ... minY, maxY, minZ, maxZ
    
    while (!openSet.isEmpty()) {
        PathNode current = openSet.removeBest();
        // ...
        expand(bot, level, current, goal, closed, openIndex, openSet,
                minX, maxX, minY, maxY, minZ, maxZ);  // 传递边界
    }
}
```

**修改后**:
```java
public static SearchResult computeDetailed(ServerPlayer bot, BlockPos start, Goal goal) {
    return computeDetailed(bot, start, goal, DEFAULT_SEARCH_TIME_MS);
}

public static SearchResult computeDetailed(ServerPlayer bot, BlockPos start, Goal goal, long timeoutMs) {
    long startTime = System.currentTimeMillis();
    
    while (!openSet.isEmpty()) {
        // ⏰ 检查时间预算
        if (System.currentTimeMillis() - startTime > timeoutMs) {
            BotLog.warn("寻路超时: {}ms, 已扩展节点={}", timeoutMs, closed.size());
            return new SearchResult(SearchStatus.SEARCH_LIMIT, ...);
        }
        
        PathNode current = openSet.removeBest();
        // ...
        expand(bot, level, current, goal, closed, openIndex, openSet);  // 不传边界
    }
}
```

**新增**：带时间预算的重载方法，支持自定义超时时间。

---

### **3. expand() 方法**

**修改前**:
```java
private static void expand(ServerPlayer bot, ServerLevel level, PathNode current, Goal goal,
                           Map<BlockPos, PathNode> closed,
                           Map<BlockPos, PathNode> openIndex, OpenSet openSet,
                           int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    // 检查当前节点是否在边界内
    if (x <= minX || x >= maxX || y <= minY || y >= maxY || z <= minZ || z >= maxZ) {
        return;
    }
    
    for (var move : movements) {
        BlockPos to = move.to();
        
        // 检查目标节点是否在边界内
        if (to.getX() < minX || to.getX() > maxX || to.getY() < minY || to.getY() > maxY
                || to.getZ() < minZ || to.getZ() > maxZ) {
            continue;
        }
        // ...
    }
}
```

**修改后**:
```java
private static void expand(ServerPlayer bot, ServerLevel level, PathNode current, Goal goal,
                           Map<BlockPos, PathNode> closed,
                           Map<BlockPos, PathNode> openIndex, OpenSet openSet) {
    // Phase 3.1: 移除空间边界检查，改用时间预算控制搜索范围
    
    var movements = MovementHelper.getPossibleMovements(bot, current.pos, level);
    
    for (var move : movements) {
        BlockPos to = move.to();
        
        // 只保留世界高度限制
        if (to.getY() < level.getMinBuildHeight() || to.getY() >= level.getMaxBuildHeight()) {
            continue;
        }
        // ...
    }
}
```

**关键变化**：
- ✅ 移除所有边界参数
- ✅ 移除边界检查逻辑
- ✅ 只保留世界高度限制（防止越界崩溃）

---

### **4. computeMovementDetailed() 方法**

同样的改动：
- ✅ 添加时间预算检查
- ✅ 移除空间边界计算
- ✅ 添加带超时参数的重载方法

---

### **5. Legacy 方法保持不变**

`computeDetailedLegacy()` 保留旧的边界逻辑：
```java
@Deprecated
public static SearchResult computeDetailedLegacy(ServerLevel level, BlockPos start, Goal goal) {
    final int LEGACY_SEARCH_MARGIN = 12;  // 局部常量
    // ... 保留原有边界逻辑
}
```

**原因**：
- 用于诊断命令和测试
- 不依赖 Movement 系统
- 保持向后兼容

---

## 📊 改进效果

### **空间边界 vs 时间预算**

| 特性 | 空间边界（旧） | 时间预算（新） |
|------|---------------|---------------|
| 远距离寻路 | ❌ 失败（超出边界） | ✅ 成功（只受时间限制） |
| 复杂地形 | ❌ 可能超出边界 | ✅ 自动适应 |
| 控制方式 | 硬编码格子数 | 灵活的毫秒数 |
| 可配置性 | ❌ 需要重新编译 | ✅ 运行时可调 |
| 性能可预测性 | ❌ 取决于空间大小 | ✅ 固定时间上限 |

### **数值对比**

| 参数 | Phase 2B（旧） | Phase 3.1（新） |
|------|---------------|---------------|
| MAX_NODES | 12,000 | 50,000 |
| MAX_MOVES | 256 | 1,024 |
| 空间限制 | ±12 格 | 无限制 |
| 时间限制 | 无 | 100ms（可配置） |

---

## 🎯 Baritone 对比

### **Baritone 的做法**

```java
// Baritone 的时间预算控制
public class AStarPathFinder {
    private static final long MAX_SEARCH_TIME_MS = 100;
    
    public IPath calculate(...) {
        long startTime = System.nanoTime();
        
        while (!openSet.isEmpty()) {
            if ((System.nanoTime() - startTime) / 1_000_000 > MAX_SEARCH_TIME_MS) {
                return partial path or failure;
            }
            // ... A* 搜索
        }
    }
}
```

### **Alice 的实现**

```java
// Alice 的时间预算控制（基本一致）
public static SearchResult computeDetailed(ServerPlayer bot, BlockPos start, Goal goal, long timeoutMs) {
    long startTime = System.currentTimeMillis();
    
    while (!openSet.isEmpty()) {
        if (System.currentTimeMillis() - startTime > timeoutMs) {
            return new SearchResult(SearchStatus.SEARCH_LIMIT, ...);
        }
        // ... A* 搜索
    }
}
```

**差异**：
- Baritone 使用 `System.nanoTime()`（更精确）
- Alice 使用 `System.currentTimeMillis()`（足够用）
- 两者思路完全一致

---

## ✅ 测试场景

### **1. 远距离寻路测试**

**测试步骤**：
1. 在游戏中放置 Bot
2. 设置目标到 100+ 格外
3. 观察 Bot 是否能找到路径

**预期结果**：
- ✅ Phase 2B：可能失败（超出 ±12 格边界）
- ✅ Phase 3.1：成功（时间预算足够）

---

### **2. 复杂地形测试**

**测试步骤**：
1. 创建迷宫/山脉地形
2. 设置目标需要绕很远的路
3. 观察 Bot 寻路

**预期结果**：
- ✅ Phase 2B：可能失败（直线距离近但路径远）
- ✅ Phase 3.1：成功（不受空间限制）

---

### **3. 性能测试**

**测试步骤**：
1. 设置不可达的目标
2. 观察寻路耗时
3. 检查日志中的超时提示

**预期结果**：
- ✅ 寻路在 100ms 内中止
- ✅ 不会无限扩展节点

---

## 📝 注意事项

### **1. 时间预算调整**

不同场景可能需要不同时间预算：

```java
// 快速寻路（实时响应）
AStarPathfinder.computeDetailed(bot, start, goal, 50);  // 50ms

// 普通寻路（默认）
AStarPathfinder.computeDetailed(bot, start, goal);  // 100ms

// 复杂寻路（允许更长时间）
AStarPathfinder.computeDetailed(bot, start, goal, 500);  // 500ms
```

### **2. 性能考虑**

- ⚠️ 时间预算不等于实际耗时
- ⚠️ 检查时间也需要成本（每次循环都检查）
- ✅ 可以考虑每 N 次循环检查一次（优化）

### **3. 部分路径**

当前实现：超时直接返回失败。

**改进方向**：
```java
// 返回部分路径（最接近目标的节点）
if (System.currentTimeMillis() - startTime > timeoutMs) {
    PathNode bestNode = findClosestToGoal(closed.values(), goal);
    return new SearchResult(SearchStatus.PARTIAL, reconstruct(bestNode), ...);
}
```

---

## 🚀 下一步：Phase 3.2

**Movement Provider 接口化**：
1. 创建 `MovementProvider` 接口
2. 实现各个 Movement 提供者
3. 注册表模式
4. 动态配置

---

## 📚 参考

- Baritone AStarPathFinder: 时间预算控制
- 前辈反馈：边界太局限，应使用时间预算
- Phase 2B: Movement 系统基础

---

**Phase 3.1 完成！编译通过，等待测试！** ✅
