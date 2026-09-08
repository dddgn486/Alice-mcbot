# Forge BlockPos 可变性陷阱

## 问题描述

Minecraft Forge 的 `BlockPos` 有两种类型：
- **不可变（immutable）**：坐标一旦创建就不会改变
- **可变（mutable）**：坐标可以被修改

某些 API（如 `BlockPos.betweenClosed()`）为了性能优化，会**复用同一个 `MutableBlockPos` 对象**，每次迭代只修改其坐标值，而不创建新对象。

如果直接存储这些可变对象的引用，会导致**存储的所有位置最终都指向同一个被修改后的坐标**。

---

## 典型症状

### **症状 1：存储的坐标都变成了最后一个值**

```java
List<BlockPos> positions = new ArrayList<>();
for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 2, 0, 0)) {
    positions.add(pos);  // ❌ 存储了可变对象的引用
}

// 预期：[(0,0,0), (1,0,0), (2,0,0)]
// 实际：[(2,0,0), (2,0,0), (2,0,0)]  // 所有元素都是最后一个坐标
```

### **症状 2：日志看起来正常，但运行时数据错误**

```java
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    System.out.println("添加: " + pos);  // 打印：(0,0,0), (1,0,0), (2,0,0) ✅
    list.add(pos);
}

// 但稍后访问 list 时，所有元素都是 (2,0,0) ❌
```

**为什么**：打印时，`pos` 的值确实是那个坐标；但存储的是引用，迭代结束后该对象的值已被修改。

### **症状 3：对象的字段值"神奇地"改变了**

```java
Tree tree = new Tree(basePos, logs, leaves);  // basePos = (100, 65, 200)
pendingTrees.add(tree);

// 稍后访问
System.out.println(tree.getBasePos());  // 打印：(150, 80, 250) ❌
// basePos 是 final 字段，但值变了！
```

**原因**：`basePos` 字段指向的是可变对象，该对象在外部被修改了。

---

## 根本原因

### **`BlockPos.betweenClosed()` 的实现**

```java
// Minecraft 源码的简化版
public static Iterable<BlockPos> betweenClosed(int minX, int minY, int minZ, 
                                                 int maxX, int maxY, int maxZ) {
    return () -> new Iterator<BlockPos>() {
        private final MutableBlockPos cursor = new MutableBlockPos();  // 复用的对象
        
        public BlockPos next() {
            cursor.set(currentX, currentY, currentZ);  // 修改同一个对象
            return cursor;  // 返回同一个引用
        }
    };
}
```

**关键点**：
- 所有迭代都返回同一个 `MutableBlockPos` 对象
- 每次只修改这个对象的坐标值
- 如果存储这个引用，所有存储的位置都指向同一个对象

---

## 解决方法

### **方法 1：使用 `.immutable()`（推荐）**

```java
List<BlockPos> positions = new ArrayList<>();
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    positions.add(pos.immutable());  // ✅ 创建不可变副本
}
```

`BlockPos.immutable()` 会创建一个新的、不可变的 `BlockPos` 对象，坐标值被复制。

### **方法 2：手动创建新对象**

```java
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    positions.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));  // ✅
}
```

### **方法 3：避免存储，立即使用**

```java
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    // 直接使用，不存储
    BlockState state = level.getBlockState(pos);
    if (state.is(Blocks.DIAMOND_ORE)) {
        mine(pos.immutable());  // 如果需要传递给异步任务，还是要 immutable
    }
}
```

---

## 受影响的 API

以下 Forge/Minecraft API 可能返回可变对象：

| API | 是否可变 | 注意事项 |
|-----|---------|---------|
| `BlockPos.betweenClosed()` | ✅ 可变 | 必须 `.immutable()` |
| `Entity.blockPosition()` | ❓ 取决于实现 | 建议 `.immutable()` |
| `AABB.inflate()` 等方法返回的迭代器 | ✅ 可变 | 必须 `.immutable()` |
| `new BlockPos(x, y, z)` | ❌ 不可变 | 安全 |
| `BlockPos.above()`/`below()` | ❌ 不可变 | 安全 |

**经验法则**：
- 从**迭代器**获取的 `BlockPos` → 可能可变，需要 `.immutable()`
- 从**方法返回**的 `BlockPos` → 查文档或保守起见调用 `.immutable()`
- **自己创建**的 `BlockPos` → 不可变，安全

---

## 实战案例

### **案例：TreeDetector 漏掉底部原木**

**问题**：
区域伐木时，Bot 从第二层原木开始砍，地上留着树桩（最底层原木）。

**表面症状**：
- 扫描日志显示检测到了 5 个原木：`Y=65, 66, 67, 68, 69` ✅
- 但存储后，logs 列表变成了：`Y=80, 66, 67, 68, 69` ❌
- `basePos` 从 `(119, 65, 112)` 变成了 `(121, 80, 114)` ❌

**根本原因**：

```java
// RegionLumberTask.java
for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
    if (!isLog(pos)) continue;
    Tree tree = TreeDetector.detectTree(level, pos);  // pos 是可变的
    pendingTrees.add(tree);
}

// TreeDetector.java
private static List<BlockPos> traceConnectedLogs(Level level, BlockPos start) {
    while (!queue.isEmpty()) {
        BlockPos current = queue.poll();
        if (isLog(level, current)) {
            logs.add(current);  // ❌ 直接存储了可变引用
        }
    }
}
```

**执行过程**：
1. 扫描到 `(119, 65, 112)`，检测到 5 个原木，加入 logs
2. **所有 logs 元素都指向同一个可变对象**
3. 扫描继续，可变对象的坐标被修改成 `(121, 80, 114)`
4. 此时 logs 列表里的所有元素都"变成"了 `(121, 80, 114)`

**修复**：

```java
logs.add(current.immutable());  // ✅ 创建不可变副本
```

---

## 调试技巧

### **1. 检查对象身份**

```java
List<BlockPos> positions = new ArrayList<>();
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    System.out.println("Identity: " + System.identityHashCode(pos));
    positions.add(pos);
}
// 如果所有打印的 hash 都相同 → 是同一个对象
```

### **2. 立即检查 vs 延迟检查**

```java
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    System.out.println("立即打印: " + pos);  // 正常
    list.add(pos);
}

System.out.println("稍后打印: " + list);  // 所有元素相同 → 可变对象问题
```

### **3. 检查 final 字段是否"变化"**

如果一个 `final BlockPos` 字段的值"变了"，几乎肯定是可变对象问题：

```java
class Tree {
    private final BlockPos basePos;  // final 字段
    
    public Tree(BlockPos basePos) {
        this.basePos = basePos;
        System.out.println("构造时: " + basePos);  // (100, 65, 200)
    }
    
    public void check() {
        System.out.println("检查时: " + basePos);  // (150, 80, 250) ← 变了！
        // → basePos 指向的是可变对象
    }
}
```

---

## 防御性编程建议

### **1. 在构造函数中立即 immutable**

```java
public Tree(BlockPos basePos, List<BlockPos> logs) {
    this.basePos = basePos.immutable();  // ✅ 防御性复制
    this.logs = logs.stream()
                    .map(BlockPos::immutable)  // ✅ 所有元素都复制
                    .collect(Collectors.toList());
}
```

### **2. 在存储前 immutable**

```java
for (BlockPos pos : BlockPos.betweenClosed(...)) {
    list.add(pos.immutable());  // ✅ 存储前复制
}
```

### **3. 在异步传递前 immutable**

```java
CompletableFuture.supplyAsync(() -> {
    processBlock(pos.immutable());  // ✅ 防止并发修改
});
```

---

## 相关陷阱

### **1. Vec3 也有可变版本**

```java
Vec3 mutable = new Vec3(x, y, z);  // 可变
Vec3 immutable = new Vec3(x, y, z);  // 其实 Vec3 本身是不可变的
// 但某些方法返回的可能是可变的
```

### **2. Entity 位置可能是引用**

```java
BlockPos pos = entity.blockPosition();
list.add(pos);  // ⚠️ 可能有问题，建议 .immutable()
```

### **3. 集合类的迭代器可能复用对象**

任何性能敏感的迭代器都可能复用对象，特别是：
- Minecraft 的空间扫描 API
- 大规模数据处理 API
- 性能优化的工具类

---

## 总结

**核心原则**：
- ✅ **从迭代器获取的对象，如果要存储，立即 `.immutable()`**
- ✅ **如果对象的值"神奇地"变了，首先怀疑可变对象**
- ✅ **调试时检查对象身份（`System.identityHashCode`）**

**记住**：
> 性能优化的代价往往是复用对象。如果你需要保留数据，就必须复制它。

---

## 参考

- Minecraft Forge 源码：`net.minecraft.core.BlockPos`
- Java 不可变对象模式
- 防御性编程：Effective Java Item 50

---

**最后更新**：2026-01-04  
**触发案例**：Alice 项目 - RegionLumberTask 漏掉底部原木问题  
**调试时间**：~2 小时（因为症状非常隐蔽）
