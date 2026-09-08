# Movement 系统技术架构

**版本**: Phase 2A  
**更新时间**: 2025-01-XX

---

## 🏗️ **系统层次结构**

```
┌─────────────────────────────────────────────────────────────┐
│                      任务层 (Task Layer)                      │
│  - BlockBreakTask                                            │
│  - DropCollectionTask                                        │
│  - FollowTask                                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    寻路层 (Pathfinding Layer)                │
│  - AStarPathfinder     (Phase 2A: 待修改)                    │
│  - PathExecutor        (Phase 2A: 待修改)                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Movement 原语层 (Movement Layer)              │
│  - MovementHelper      (枚举可能的 Movement)                 │
│  - WalkMovement        (平地行走)                            │
│  - PillarMovement      (搭柱子上升)                          │
│  - BreakAndWalkMovement(挖前方障碍)                          │
│  - DescendMovement     (搭台阶下降)                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  基础执行层 (Execution Layer)                 │
│  - BasicMovement       (applyToward, settle, jump)           │
│  - MovementConstraints (可回收性约束检查)                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   物理层 (Physics Layer)                      │
│  - BotPlayer.tick()    (原版物理)                            │
│  - ServerPlayer.aiStep()                                     │
│  - Entity.travel()                                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔄 **数据流（Phase 1 - 当前）**

```
用户操作
  │
  ▼
Task.start()
  │
  ▼
AStarPathfinder.computePath(start, goal)
  │
  ├─ 枚举 26 个相邻格子
  ├─ 计算成本（固定 1.0）
  ├─ A* 搜索
  │
  ▼
返回 List<BlockPos>
  │
  ▼
PathExecutor.setPath(path)
  │
  ▼
PathExecutor.tick() (每 tick)
  │
  ├─ BasicMovement.applyToward(下一个 BlockPos)
  │     │
  │     ├─ 计算朝向
  │     ├─ 设置 bot.xxa, bot.zza
  │     │
  │     ▼
  │   BotPlayer.tick()
  │     │
  │     ├─ super.tick() (ServerPlayer)
  │     ├─ aiStep() (处理移动)
  │     ├─ travel() (应用物理)
  │     │
  │     ▼
  │   Bot 移动
  │
  ▼
到达目标
```

---

## 🚀 **数据流（Phase 2A - 目标）**

```
用户操作
  │
  ▼
Task.start()
  │
  ▼
AStarPathfinder.computePath(start, goal)
  │
  ├─ MovementHelper.getPossibleMovements(bot, current, level)
  │     │
  │     ├─ WalkMovement.create() ×8 方向
  │     ├─ (Phase 2B: PillarMovement.create())
  │     ├─ (Phase 2B: BreakAndWalkMovement.create())
  │     └─ (Phase 2B: DescendMovement.create())
  │
  ├─ 过滤：movement.isValid()
  ├─ 计算成本：movement.cost()
  ├─ A* 搜索
  │
  ▼
返回 List<Movement>
  │
  ▼
PathExecutor.setMovements(movements)
  │
  ▼
PathExecutor.tick() (每 tick)
  │
  ├─ currentMovement.tick(bot)
  │     │
  │     ├─ WalkMovement: BasicMovement.applyToward()
  │     ├─ PillarMovement: 放方块 + jump
  │     ├─ BreakAndWalkMovement: 挖方块 + 移动
  │     └─ DescendMovement: 放台阶 + 移动
  │
  │     ▼
  │   返回 Status (RUNNING / SUCCESS / FAILED)
  │
  ├─ 如果 SUCCESS: 切换到下一个 Movement
  ├─ 如果 RUNNING: 继续执行
  ├─ 如果 FAILED: 重新规划路径
  │
  ▼
所有 Movement 完成
```

---

## 🧩 **Movement 接口设计**

### **Movement（接口）**

```java
public interface Movement {
    // 起点
    BlockPos from();
    
    // 终点
    BlockPos to();
    
    // 成本（tick）
    double cost();
    
    // 是否有效
    boolean isValid();
    
    // 执行一个 tick
    Status tick(BotPlayer bot);
    
    enum Status {
        RUNNING,   // 继续执行
        SUCCESS,   // 完成
        FAILED     // 失败
    }
}
```

### **具体实现**

```java
// 工厂模式
public class WalkMovement implements Movement {
    private WalkMovement(bot, from, to, level) { ... }
    
    public static WalkMovement create(bot, from, to, level) {
        WalkMovement m = new WalkMovement(...);
        return m.isValid() ? m : null;
    }
}
```

---

## 🔒 **可回收性约束（Recoverability Constraints）**

所有 Movement 必须遵守：

```java
class MovementConstraints {
    // 1. 不能挖脚下方块
    static boolean canBreak(BlockPos botPos, BlockPos target) {
        return !target.equals(botPos.below());
    }
    
    // 2. 不能跳下2格高
    static boolean canDescend(BlockPos from, BlockPos to) {
        return (from.getY() - to.getY()) <= 1;
    }
    
    // 3. 不能进入液体 (Phase 1)
    static boolean hasLiquid(Level level, BlockPos pos) {
        // 检查是否有液体
    }
    
    // 4. 不能进入禁区
    static boolean isForbiddenZone(Level level, BlockPos pos) {
        // 检查是否是禁区
    }
}
```

**为什么需要这些约束？**
- Bot 必须能原路返回
- 避免掉入虚空或岩浆
- 保证任务失败时 Bot 可回收

---

## 📊 **成本模型**

### **Phase 1 简化模型**

只考虑时间成本，不考虑资源消耗：

```java
// 基础移动
WALK = 1.0 tick/格
FALL = 0.5 tick/格

// 放置
PLACE = 5.0 tick
PILLAR = 5.0 tick/格 (放方块 + 跳跃)

// 挖掘
BREAK_DIRT = 2.0 tick
BREAK_STONE = 20.0 tick
BREAK_OBSIDIAN = 200.0 tick

// 复合
DESCEND = 7.0 tick (放台阶 + 行走)
BREAK_AND_WALK = BREAK_COST + WALK_COST
```

### **Phase 2B 扩展（未来）**

可能考虑：
- 工具耐久（可选）
- 方块消耗（可选）
- 饥饿度（可选）

**但用户明确表示**："没必要考虑耐久或放方块成本"

---

## 🗺️ **Phase 路线图**

### **Phase 1** ✅ **完成**
- 创建 Movement 接口和实现
- 验证基础移动正常
- 重构 PathExecutor 和 FollowTask

### **Phase 2A** 🔄 **进行中（60%）**
- 改进 Movement 接口
- 创建 MovementHelper
- 集成到 AStarPathfinder（待做）

### **Phase 2B** 🔲 **未开始**
- A* 枚举所有 Movement（不只 WalkMovement）
- 添加剪枝和优化
- 动态成本计算
- 测试复杂场景

### **Phase 3** 🔲 **未开始（可选）**
- 目标簇识别
- 任务准备系统
- 材料不足时的二次决策

---

## 🐛 **已知问题和解决方案**

### **问题 1：搜索空间爆炸**
- **描述**：枚举所有 Movement 导致搜索节点过多
- **解决方案**：
  - Phase 2A: 只用 WalkMovement
  - Phase 2B: 添加剪枝（只考虑朝向目标的 Movement）
  - 限制搜索范围

### **问题 2：Movement 状态管理**
- **描述**：Movement 可能需要多个 tick 完成
- **解决方案**：
  - tick() 返回 Status 枚举
  - PathExecutor 循环调用直到 SUCCESS

### **问题 3：目标簇内挖脚下**
- **描述**：挖矿时可能需要挖脚下方块
- **解决方案**：
  - 从最底层开始挖（从下往上）
  - 或者暂时不做深度挖掘任务

---

## 📁 **文件组织**

```
alice/
├── src/main/java/com/dddgn/alice/
│   ├── pathing/
│   │   ├── movement/              ← Movement 系统
│   │   │   ├── Movement.java
│   │   │   ├── MovementHelper.java
│   │   │   ├── MovementConstraints.java
│   │   │   ├── BasicMovement.java
│   │   │   ├── WalkMovement.java
│   │   │   ├── PillarMovement.java
│   │   │   ├── BreakAndWalkMovement.java
│   │   │   └── DescendMovement.java
│   │   ├── AStarPathfinder.java   ← 待修改
│   │   ├── PathExecutor.java      ← 待修改
│   │   └── SoftMovementPrimitive.java  ← @Deprecated
│   ├── bot/
│   │   ├── BotPlayer.java
│   │   ├── BotController.java
│   │   └── BotManager.java
│   └── task/
│       ├── BlockBreakTask.java
│       ├── DropCollectionTask.java
│       └── FollowTask.java
└── .alice-supervision/
    ├── MEMO.md                    ← 完整备忘录
    ├── QUICKSTART.md              ← 快速恢复
    ├── ARCHITECTURE.md            ← 本文件
    ├── refactoring/
    │   ├── movement-primitives-phase1.md
    │   └── bot-manager-cleanup-2025.md
    └── testing/
        └── movement-primitives-test.md
```

---

## 🔗 **相关文档**

- **完整备忘录**: `.alice-supervision/MEMO.md`
- **快速恢复**: `.alice-supervision/QUICKSTART.md`
- **Phase 1 总结**: `.alice-supervision/refactoring/movement-primitives-phase1.md`
- **测试指南**: `.alice-supervision/testing/movement-primitives-test.md`

---

**架构版本**: v1.0  
**适用阶段**: Phase 2A  
**最后更新**: 2025-01-XX

🐋💙
