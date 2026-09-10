# AStarPathfinder Movement 集成计划

**Phase 2A**: 简化版集成（只用 WalkMovement）

---

## 📊 **当前代码分析**

### **核心方法**：

1. **`computeDetailed(level, start, goal)`** - 主搜索方法
2. **`expand(level, current, ...)`** - 枚举相邻节点
3. **`tryMove(level, current, to, type, ...)`** - 尝试移动到一个位置
4. **`reconstruct(end)`** - 重建路径

### **当前的移动枚举**（第 107-132 行）：

```java
for (int dx = -1; dx <= 1; dx++) {
    for (int dz = -1; dz <= 1; dz++) {
        // 8 个水平方向
        BlockPos flat = new BlockPos(x + dx, y, z + dz);       // 同一高度
        BlockPos ascend = new BlockPos(x + dx, y + 1, z + dz); // 上1格
        BlockPos descend = new BlockPos(x + dx, y - 1, z + dz); // 下1格
        
        tryMove(..., flat, MovementType.TRAVERSE, ...);
        tryMove(..., ascend, MovementType.ASCEND, ...);
        tryMove(..., descend, MovementType.DESCEND, ...);
    }
}
// 向下1格
BlockPos down = new BlockPos(x, y - 1, z);
tryMove(..., down, MovementType.DOWNWARD, ...);
```

### **问题**：
- 使用 `MovementHelper.canTraverse()` 等旧的判定方法
- 固定成本 `MovementHelper.cost(type)`
- 不支持复杂 Movement（挖掘、搭建）

---

## 🎯 **Phase 2A 目标**

### **最小改动原则**：
- ✅ 保持接口不变（仍返回 `List<BlockPos>`）
- ✅ 内部使用 Movement 判定和成本
- ✅ 只支持 WalkMovement（保持简单）
- ✅ 为 Phase 2B 做准备

### **不改动**：
- ❌ 不修改返回类型（Phase 2B 再改）
- ❌ 不添加 bot 参数（Phase 2B 再加）
- ❌ 不支持其他 Movement（Phase 2B）

---

## 🔧 **修改方案**

### **方案：替换内部判定逻辑**

**当前**：
```java
boolean valid = switch (type) {
    case TRAVERSE -> MovementHelper.canTraverse(level, from, to);
    case ASCEND -> MovementHelper.canAscend(level, from, to);
    // ...
};
double cost = MovementHelper.cost(type);
```

**Phase 2A**：
```java
// 尝试创建 WalkMovement
// ⚠️ 问题：WalkMovement.create() 需要 BotPlayer，但这里没有
// 解决：先不用 Movement，Phase 2A 暂时保持原样，只改进判定逻辑
```

---

## ⚠️ **发现的问题**

### **问题 1：Movement 需要 BotPlayer，但 Task 用 ServerPlayer**

**当前情况**：
- MovementHelper.getPossibleMovements(BotPlayer bot, ...)
- 所有 Task 的 bot 都是 ServerPlayer（除了 TransferTask）

**解决方案**：
- ✅ 修改 MovementHelper 接受 ServerPlayer
- ✅ Movement.create() 也改为接受 ServerPlayer
- ✅ BotPlayer 继承自 ServerPlayer，完全兼容

**原因**：
- Task 层不应该假设 bot 一定是 BotPlayer
- 未来可能有其他类型的 ServerPlayer
- ServerPlayer 是更通用的抽象

---

## 💡 **解决方案**

### **方案 A：添加 bot 参数到 A***（推荐）

**修改接口**：
```java
// 旧
computeDetailed(ServerLevel level, BlockPos start, Goal goal)

// 新
computeDetailed(BotPlayer bot, BlockPos start, Goal goal)
```

**影响**：
- 需要修改所有调用处
- 但这是必须的，因为 Movement 需要 bot

**调用处**：
```bash
grep -r "AStarPathfinder.compute" src/
```

---

### **方案 B：暂时不用 Movement（Phase 2A）**

**Phase 2A**：
- 保持当前代码不变
- 只改进 `MovementHelper.canTraverse()` 等判定逻辑
- 使用 `MovementConstraints` 检查约束

**Phase 2B**：
- 添加 bot 参数
- 使用 Movement

---

## 🎯 **Phase 2A 最终决策**

### **采用方案 A：添加 bot 参数**

**理由**：
- 早晚要加，不如现在就加
- Movement 系统已经准备好
- 测试通过，可以大胆修改

**步骤**：
1. 添加 bot 参数到 `computeDetailed()`
2. 修改 `expand()` 使用 `MovementHelper.getPossibleMovements()`
3. 修改 `tryMove()` 使用 Movement 判定
4. 查找并修改所有调用处
5. 测试验证

---

## 📋 **具体修改步骤**

### **Step 1：修改签名**

```java
// 旧
public static SearchResult computeDetailed(ServerLevel level, BlockPos start, Goal goal)

// 新
public static SearchResult computeDetailed(BotPlayer bot, BlockPos start, Goal goal)
```

### **Step 2：修改 expand()**

```java
private static void expand(BotPlayer bot, ServerLevel level, PathNode current, ...) {
    // 使用 MovementHelper 枚举
    List<Movement> movements = MovementHelper.getPossibleMovements(bot, current.pos, level);
    
    for (Movement move : movements) {
        if (move == null) continue;
        BlockPos to = move.to();
        double cost = move.cost();
        tryMove(bot, level, current, goal, closed, openIndex, openSet, to, cost, ...);
    }
}
```

### **Step 3：修改 tryMove()**

```java
private static void tryMove(BotPlayer bot, ServerLevel level, PathNode current, 
                           Goal goal, ..., BlockPos to, double moveCost, ...) {
    // 不需要再判定 valid，因为 Movement.create() 已经判定了
    double newCost = current.cost + moveCost;
    // ...
}
```

### **Step 4：查找调用处**

```bash
grep -rn "AStarPathfinder.compute" src/main/java/
```

---

## 🐋 **下一步行动**

1. **查找所有调用处**
2. **修改 computeDetailed() 签名**
3. **修改所有调用处传入 bot**
4. **修改 expand() 使用 Movement**
5. **测试验证**

**准备好了吗？** 🐋💙
