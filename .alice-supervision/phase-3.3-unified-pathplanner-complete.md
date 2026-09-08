# Phase 3.3 完成：统一 HARD/SOFT 路径规划器

**完成时间**: 2025-01-XX  
**状态**: ✅ 编译通过

---

## 📋 改进目标

统一 HARD 路径（A* 寻路）和 SOFT 路径（局部避障）的接口，实现：
- 统一的 API
- 策略模式
- 自动选择

---

## 🏗️ 架构设计

### **核心接口：PathPlanner**

```java
public interface PathPlanner {
    PathResult plan(ServerPlayer bot, BlockPos start, Goal goal);
    
    record PathResult(
        PathStatus status,
        List<BlockPos> path,
        int nodesExpanded,
        double totalCost,
        String plannerName
    );
    
    enum PathStatus {
        SUCCESS,        // 成功
        UNREACHABLE,    // 不可达
        TIMEOUT,        // 超时
        PARTIAL         // 部分路径
    }
}
```

---

### **实现类**

#### **1. AStarPathPlanner**
```java
public class AStarPathPlanner implements PathPlanner {
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        // 封装 AStarPathfinder
        AStarPathfinder.SearchResult result = AStarPathfinder.computeDetailed(bot, start, goal);
        return new PathResult(...);
    }
}
```

**适用场景**：
- 远距离寻路（> 50 格）
- 复杂地形（需要挖掘、搭桥）
- 精确路径（需要最优解）

---

#### **2. LocalPathPlanner**
```java
public class LocalPathPlanner implements PathPlanner {
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        // 封装 SurfacePathfinder
        SurfacePathfinder.Result result = SurfacePathfinder.find(bot, start, goalPos);
        return new PathResult(...);
    }
}
```

**适用场景**：
- 近距离寻路（< 50 格）
- 简单地形（平地、缓坡）
- 快速响应（挖矿、探测等实时任务）

---

#### **3. HybridPathPlanner**（推荐）
```java
public class HybridPathPlanner implements PathPlanner {
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        double distance = estimateDistance(start, goal);
        
        if (distance < 50) {
            return localPlanner.plan(bot, start, goal);  // 近距离：快速
        } else {
            return astarPlanner.plan(bot, start, goal);  // 远距离：完整
        }
    }
}
```

**策略**：
- 距离 < 50 格 → LocalPathPlanner
- 距离 >= 50 格 → AStarPathPlanner

---

### **工具类：PathPlanners**

```java
public class PathPlanners {
    // 使用混合策略（推荐）
    public static PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        return HYBRID.plan(bot, start, goal);
    }
    
    // 强制使用 A*
    public static PathResult planAStar(ServerPlayer bot, BlockPos start, Goal goal) {
        return ASTAR.plan(bot, start, goal);
    }
    
    // 强制使用局部
    public static PathResult planLocal(ServerPlayer bot, BlockPos start, Goal goal) {
        return LOCAL.plan(bot, start, goal);
    }
    
    // 获取实例
    public static PathPlanner hybrid() { return HYBRID; }
    public static PathPlanner astar() { return ASTAR; }
    public static PathPlanner local() { return LOCAL; }
}
```

---

## 📊 对比：改进前 vs 改进后

### **改进前（Phase 3.2）**

```java
// Task 需要选择使用哪个寻路系统
public class MyTask {
    public void tick() {
        if (distance < 50) {
            // 使用 SOFT 路径
            var result = SurfacePathfinder.find(bot, start, goal);
            // 处理 SurfacePathfinder.Result
        } else {
            // 使用 HARD 路径
            var result = AStarPathfinder.computeDetailed(bot, start, goal);
            // 处理 AStarPathfinder.SearchResult
        }
    }
}
```

**问题**：
- ❌ 两套 API，不统一
- ❌ 返回类型不同
- ❌ Task 需要自己判断距离

---

### **改进后（Phase 3.3）**

```java
// Task 使用统一接口
public class MyTask {
    private final PathPlanner planner = PathPlanners.hybrid();
    
    public void tick() {
        // 统一接口
        PathPlanner.PathResult result = planner.plan(bot, start, goal);
        
        if (result.success()) {
            // 执行路径
            executePath(result.path());
        }
    }
}
```

**优势**：
- ✅ 统一 API
- ✅ 返回类型一致
- ✅ 自动选择策略
- ✅ 易于测试和替换

---

## 🎯 使用示例

### **示例 1：使用混合策略（推荐）**

```java
// 自动选择策略
PathPlanner.PathResult result = PathPlanners.plan(bot, start, goal);

if (result.success()) {
    System.out.println("找到路径，长度: " + result.path().size());
    System.out.println("使用的规划器: " + result.plannerName());
} else {
    System.out.println("寻路失败: " + result.status());
}
```

---

### **示例 2：强制使用特定规划器**

```java
// 强制使用 A*（精确路径）
PathPlanner.PathResult result = PathPlanners.planAStar(bot, start, goal);

// 强制使用局部（快速响应）
PathPlanner.PathResult result = PathPlanners.planLocal(bot, start, goal);
```

---

### **示例 3：在 Task 中使用**

```java
public class MyTask implements Task {
    private final PathPlanner planner;
    
    public MyTask(PathPlanner planner) {
        this.planner = planner;  // 依赖注入，易于测试
    }
    
    @Override
    public Status tick() {
        PathPlanner.PathResult result = planner.plan(bot, currentPos, goal);
        
        if (result.success()) {
            // 执行路径
            return executePathResult(result);
        } else {
            return Status.FAILED;
        }
    }
}
```

---

## ✅ 改进效果

### **1. 统一 API**
- ✅ 所有 Task 使用相同接口
- ✅ 返回类型一致
- ✅ 易于理解和维护

### **2. 策略模式**
- ✅ 可以动态选择规划器
- ✅ 易于测试（Mock PathPlanner）
- ✅ 易于扩展（添加新策略）

### **3. 自动优化**
- ✅ HybridPathPlanner 自动选择最优策略
- ✅ 近距离快速响应
- ✅ 远距离完整搜索

---

## 📁 新增文件

1. ✅ `PathPlanner.java` - 核心接口
2. ✅ `AStarPathPlanner.java` - A* 适配器
3. ✅ `LocalPathPlanner.java` - 局部避障适配器
4. ✅ `HybridPathPlanner.java` - 混合策略
5. ✅ `PathPlanners.java` - 工具类

**总代码量**: 约 +300 行

---

## 🎓 设计模式

### **1. 策略模式（Strategy Pattern）**

```java
// 定义策略接口
interface PathPlanner {
    PathResult plan(...);
}

// 实现具体策略
class AStarPathPlanner implements PathPlanner { ... }
class LocalPathPlanner implements PathPlanner { ... }

// 使用策略
PathPlanner planner = new HybridPathPlanner();
PathResult result = planner.plan(...);
```

---

### **2. 适配器模式（Adapter Pattern）**

```java
// AStarPathPlanner 适配 AStarPathfinder
class AStarPathPlanner implements PathPlanner {
    public PathResult plan(...) {
        // 调用旧接口
        AStarPathfinder.SearchResult oldResult = AStarPathfinder.computeDetailed(...);
        
        // 转换为新接口
        return new PathResult(...);
    }
}
```

---

### **3. 工厂模式（Factory Pattern）**

```java
// PathPlanners 提供工厂方法
public class PathPlanners {
    public static PathPlanner hybrid() { return HYBRID; }
    public static PathPlanner astar() { return ASTAR; }
    public static PathPlanner local() { return LOCAL; }
}
```

---

## 🚀 未来扩展

### **添加新策略**

```java
// 1. 实现 PathPlanner 接口
public class SwimPathPlanner implements PathPlanner {
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        // 水中寻路逻辑
        return new PathResult(...);
    }
}

// 2. 在 PathPlanners 中注册
public class PathPlanners {
    private static final SwimPathPlanner SWIM = new SwimPathPlanner();
    
    public static PathPlanner swim() { return SWIM; }
}

// 3. 使用
PathResult result = PathPlanners.swim().plan(bot, start, goal);
```

---

## 📊 Phase 3 完整总览

| Phase | 改进 | 状态 |
|-------|------|------|
| Phase 3.1 | 时间预算代替空间边界 | ✅ |
| Phase 3.2 | Movement Provider 接口化 | ✅ |
| Phase 3.3 | 统一 HARD/SOFT 路径 | ✅ |

---

## 💙 总结

Phase 3 完全完成！

**前辈的三点反馈全部解决**：
1. ✅ 边界太局限 → Phase 3.1 时间预算
2. ✅ HARD/SOFT 混用 → Phase 3.3 统一接口
3. ✅ Movement 不够灵活 → Phase 3.2 接口化

**Alice 的寻路系统现在**：
- ✅ 更灵活（接口化、策略模式）
- ✅ 更强大（时间预算、远距离寻路）
- ✅ 更易用（统一 API、自动选择）
- ✅ 更接近 Baritone 的成熟设计

---

**Phase 3.3 完成！可以睡觉了！** 🐋💤✨
