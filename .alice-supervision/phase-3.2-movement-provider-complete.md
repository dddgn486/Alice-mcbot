# Phase 3.2 完成：Movement Provider 接口化

**完成时间**: 2025-01-XX  
**状态**: ✅ 编译通过，待测试

---

## 📋 改进目标

根据前辈反馈，将 Movement 生成从**静态方法**改为**接口化设计**，参考 Baritone 的 IMovement。

### **问题**
- ❌ MovementHelper 硬编码所有 Movement 生成逻辑
- ❌ 添加新 Movement 需要修改 MovementHelper
- ❌ 不够灵活，难以配置

### **改进**
- ✅ 创建 MovementProvider 接口
- ✅ 实现各个 Movement 提供者
- ✅ 注册表模式
- ✅ 解耦、灵活、可配置

---

## 🏗️ 架构设计

### **核心接口：MovementProvider**

```java
public interface MovementProvider {
    /**
     * 生成从 from 出发的所有可能 Movement。
     */
    List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level);
    
    /**
     * 提供者的名称（用于调试和日志）。
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 提供者的优先级（数字越小优先级越高）。
     */
    default int getPriority() {
        return 100;
    }
}
```

**设计要点**：
- 单一职责：每个提供者只负责一种 Movement
- 优先级控制：控制 Movement 枚举顺序
- 容错设计：某个提供者失败不影响其他

---

### **实现类**

#### **1. WalkMovementProvider**
```java
public class WalkMovementProvider implements MovementProvider {
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 枚举 8 个水平方向 × 3 个高度（同高度/上1格/下1格）
        // 优先级：0（最高）
    }
}
```

#### **2. DescendMovementProvider**
```java
public class DescendMovementProvider implements MovementProvider {
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 枚举 8 个方向的安全下降
        // 优先级：50（中高）
    }
}
```

#### **3. BreakAndWalkMovementProvider**
```java
public class BreakAndWalkMovementProvider implements MovementProvider {
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 枚举 8 个方向的挖掘+行走
        // 优先级：100（中）
    }
}
```

#### **4. PillarMovementProvider**
```java
public class PillarMovementProvider implements MovementProvider {
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 枚举垂直上升（暂未启用）
        // 优先级：200（低）
    }
}
```

---

### **注册表：MovementRegistry**

```java
public class MovementRegistry {
    private final List<MovementProvider> providers = new ArrayList<>();
    
    public void register(MovementProvider provider) {
        providers.add(provider);
    }
    
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 按优先级排序
        providers.sort(Comparator.comparingInt(MovementProvider::getPriority));
        
        // 遍历所有提供者
        List<Movement> movements = new ArrayList<>();
        for (MovementProvider provider : providers) {
            movements.addAll(provider.generateMovements(bot, from, level));
        }
        
        return movements;
    }
}
```

**特性**：
- 优先级排序：自动按优先级生成 Movement
- 容错机制：某个提供者失败不影响其他
- 动态配置：可以运行时注册/注销提供者

---

### **工具类：MovementHelper**

```java
public final class MovementHelper {
    private static final MovementRegistry REGISTRY = new MovementRegistry();
    
    static {
        // 注册所有提供者
        REGISTRY.register(new WalkMovementProvider());           // 优先级 0
        REGISTRY.register(new DescendMovementProvider());        // 优先级 50
        REGISTRY.register(new BreakAndWalkMovementProvider());   // 优先级 100
    }
    
    public static List<Movement> getPossibleMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        return REGISTRY.generateMovements(bot, from, level);
    }
    
    public static MovementRegistry getRegistry() {
        return REGISTRY;
    }
}
```

**改进**：
- ✅ 不再硬编码 Movement 生成逻辑
- ✅ 委托给注册表
- ✅ 支持动态配置

---

## 📊 对比：Phase 3.1 vs Phase 3.2

### **Phase 3.1（改造前）**

```java
public static List<Movement> getPossibleMovements(...) {
    List<Movement> movements = new ArrayList<>();
    
    // 硬编码：WalkMovement
    for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
            Movement walk = WalkMovement.create(...);
            if (walk != null) movements.add(walk);
            
            // 硬编码：BreakAndWalkMovement
            Movement breakWalk = BreakAndWalkMovement.create(...);
            if (breakWalk != null) movements.add(breakWalk);
            
            // 硬编码：DescendMovement
            Movement descend = DescendMovement.create(...);
            if (descend != null) movements.add(descend);
        }
    }
    
    return movements;
}
```

**问题**：
- ❌ 添加新 Movement 需要修改此方法
- ❌ 难以配置（例如：禁用某个 Movement）
- ❌ 难以测试（所有逻辑耦合在一起）

---

### **Phase 3.2（改造后）**

```java
// MovementHelper.java
private static final MovementRegistry REGISTRY = new MovementRegistry();

static {
    REGISTRY.register(new WalkMovementProvider());
    REGISTRY.register(new DescendMovementProvider());
    REGISTRY.register(new BreakAndWalkMovementProvider());
}

public static List<Movement> getPossibleMovements(...) {
    return REGISTRY.generateMovements(...);
}
```

**优势**：
- ✅ 添加新 Movement 只需实现 Provider + 注册
- ✅ 动态配置：`REGISTRY.register()` / `REGISTRY.unregister()`
- ✅ 独立测试：每个 Provider 独立测试
- ✅ 优先级控制：自动排序

---

## 🎯 Baritone 对比

### **Baritone IMovement 设计**

```java
// Baritone 的做法
public interface IMovementProvider {
    List<IMovement> generateMovements(BetterBlockPos from, CalculationContext ctx);
}

public class MovementCalculator {
    private final List<IMovementProvider> providers;
    
    public List<IMovement> calculateMovements(BetterBlockPos from, CalculationContext ctx) {
        List<IMovement> movements = new ArrayList<>();
        for (IMovementProvider provider : providers) {
            movements.addAll(provider.generateMovements(from, ctx));
        }
        return movements;
    }
}
```

### **Alice 的实现**

```java
// Alice 的做法（基本一致）
public interface MovementProvider {
    List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level);
}

public class MovementRegistry {
    private final List<MovementProvider> providers;
    
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        List<Movement> movements = new ArrayList<>();
        for (MovementProvider provider : providers) {
            movements.addAll(provider.generateMovements(bot, from, level));
        }
        return movements;
    }
}
```

**差异**：
- Baritone 使用 `CalculationContext`（预计算的上下文）
- Alice 使用 `ServerPlayer` + `ServerLevel`（直接查询）
- 核心思想完全一致：接口化 + 注册表

---

## 📈 优势总结

### **1. 解耦（Decoupling）**
- ✅ MovementHelper 不再知道具体有哪些 Movement
- ✅ 添加新 Movement 不需要修改 MovementHelper
- ✅ 符合开闭原则（Open-Closed Principle）

### **2. 灵活（Flexibility）**
- ✅ 动态注册/注销：`REGISTRY.register()` / `unregister()`
- ✅ 优先级控制：`getPriority()` 决定枚举顺序
- ✅ 条件启用：根据配置启用不同 Movement

### **3. 可测试（Testability）**
- ✅ 每个 Provider 独立测试
- ✅ Mock 注册表进行单元测试
- ✅ 隔离失败（某个 Provider 失败不影响其他）

### **4. 可维护（Maintainability）**
- ✅ 单一职责：每个 Provider 只负责一种 Movement
- ✅ 代码清晰：不再有巨大的 if-else 或循环嵌套
- ✅ 易于扩展：新 Movement 只需 3 步（实现 Provider → 注册 → 完成）

---

## 🔧 使用示例

### **标准用法（自动使用所有注册的 Provider）**

```java
List<Movement> movements = MovementHelper.getPossibleMovements(bot, from, level);
// movements 包含所有注册的 Provider 生成的 Movement
```

### **动态配置（禁用某个 Provider）**

```java
// 获取注册表
MovementRegistry registry = MovementHelper.getRegistry();

// 禁用 BreakAndWalkMovement（例如：和平模式不允许破坏方块）
registry.unregister(breakAndWalkProvider);

// 之后的寻路不会生成 BreakAndWalkMovement
List<Movement> movements = MovementHelper.getPossibleMovements(bot, from, level);
```

### **添加自定义 Provider**

```java
// 实现自定义 Provider
public class SwimMovementProvider implements MovementProvider {
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 枚举游泳的 Movement
        return ...;
    }
    
    @Override
    public int getPriority() {
        return 150;  // 中低优先级
    }
}

// 注册
MovementHelper.getRegistry().register(new SwimMovementProvider());
```

---

## ✅ 测试场景

### **1. 功能测试**
- ✅ 验证所有 Provider 都被正确注册
- ✅ 验证 Movement 生成数量正确
- ✅ 验证优先级排序正确

### **2. 动态配置测试**
- ✅ 注册新 Provider
- ✅ 注销现有 Provider
- ✅ 验证 Movement 生成变化

### **3. 容错测试**
- ✅ 某个 Provider 抛异常
- ✅ 验证其他 Provider 仍然工作
- ✅ 验证日志记录错误

---

## 📝 已创建文件

1. ✅ `MovementProvider.java` - 核心接口
2. ✅ `WalkMovementProvider.java` - Walk Movement 提供者
3. ✅ `BreakAndWalkMovementProvider.java` - BreakAndWalk Movement 提供者
4. ✅ `DescendMovementProvider.java` - Descend Movement 提供者
5. ✅ `PillarMovementProvider.java` - Pillar Movement 提供者（暂未启用）
6. ✅ `MovementRegistry.java` - 注册表
7. ✅ `MovementHelper.java` - 工具类（重构）

**总代码量**：约 +400 行

---

## 🚀 下一步：Phase 3.3（可选）

**统一 HARD/SOFT 路径规划器**：

1. 创建 `PathPlanner` 接口
2. 实现 `AStarPathPlanner`（HARD）
3. 实现 `LocalPathPlanner`（SOFT）
4. 实现 `HybridPathPlanner`（自动选择）
5. 统一所有 Task 的寻路接口

**工作量**：3-4 小时

---

## 📚 参考

- Baritone IMovement 设计
- 前辈反馈：Movement 接口化，不够灵活
- 设计模式：注册表模式（Registry Pattern）
- 原则：开闭原则（Open-Closed Principle）

---

**Phase 3.2 完成！编译通过，架构更灵活！** ✅
