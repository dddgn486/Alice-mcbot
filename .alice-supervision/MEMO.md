# Alice 项目工作备忘录

**创建时间**: 2025-01-XX  
**当前阶段**: Phase 2A - Movement 原语系统集成（进行中）

---

## 🎯 **项目总体目标**

实现 Bot 寻路系统的三个层级：

### **第一层：基础可行性** ✅ **已完成**
1. ✅ 能找到路：有正常路径一定要能找到
2. ✅ 能走过去：找到的路一定能成功执行
3. ✅ 相对最短：在可行路径中选择较短的

### **第二层：任务站位** 🔲 **未开始**
4. 🔲 挖掘站位：寻路到合适的挖掘位置
5. 🔲 搭建站位：寻路到合适的放置方块位置

### **第三层：破坏+搭建寻路** 🔲 **未开始**
6. 🔲 挖掘+搭建路径：利用挖掘和搭建能力，去任何想去的地方

---

## 📊 **已完成的阶段**

### **Phase 1: 基础 Movement 原语系统** ✅ **完成**

**完成时间**: 2025-01-XX

**创建的文件**（8个）：
```
src/main/java/com/dddgn/alice/pathing/movement/
├── Movement.java                    - 移动原语接口
├── MovementConstraints.java         - 可回收性约束检查
├── BasicMovement.java               - 基础移动工具类
├── WalkMovement.java                - 平地行走
├── PillarMovement.java              - 搭柱子上升
├── BreakAndWalkMovement.java        - 挖前方障碍并前进
├── DescendMovement.java             - 搭台阶下降
└── MovementHelper.java              - Movement 枚举工具类 (Phase 2A)
```

**修改的文件**（3个）：
- `PathExecutor.java` - 使用 BasicMovement
- `FollowTask.java` - 使用 BasicMovement
- `SoftMovementPrimitive.java` - 标记为 @Deprecated

**核心设计原则：Bot 可回收性约束**
- ❌ 不能挖脚下方块（防止掉落）
- ❌ 不能跳下2格高（无法返回）
- ❌ 不能进入液体（Phase 1 完全避开）
- ❌ 不能进入禁区（保护 Bot）

**测试结果**：
- ✅ 平地寻路流畅
- ✅ 挖掘方块正常
- ✅ 捡拾掉落物流畅
- 用户反馈："我嘞个豆，效果确实好多了"

**文档**：
- `.alice-supervision/refactoring/movement-primitives-phase1.md`
- `.alice-supervision/testing/movement-primitives-test.md`

---

## 🚧 **当前阶段：Phase 2A**

**目标**: 让 A* 寻路算法能够使用 Movement 原语（简化版）

**进度**: 60% 完成

### **已完成**：

1. ✅ **改进 Movement 接口**
   - 添加 `from()` 和 `to()` 方法
   - `tick()` 返回 `Status` 枚举（RUNNING, SUCCESS, FAILED）
   - `isValid()` 改为无参数版本
   - Movement 实例不可变（Immutable）

2. ✅ **创建 MovementHelper 工具类**
   - `getPossibleMovements(bot, from, level)` - 枚举所有可能的 Movement
   - `getPossibleMovementsToward(bot, from, goal, level)` - 带方向提示
   - Phase 2A 只枚举 WalkMovement（保持简单）

3. ✅ **更新所有 Movement 实现**
   - 改为工厂模式：`Movement.create(bot, from, to, level)`
   - 返回 `null` 表示无效
   - 全部符合新接口

4. ✅ **编译成功**

### **待完成**：

5. 🔲 **扩展 AStarPathfinder**
   - 找到枚举相邻格子的代码
   - 替换为枚举 Movement
   - 只使用 WalkMovement（Phase 2A）

6. 🔲 **修改 PathNode**
   - 记录使用的 Movement
   - 路径重建时保留 Movement 信息

7. 🔲 **修改 PathExecutor**
   - 执行路径中的 Movement
   - 而不是简单的 BasicMovement.applyToward()

8. 🔲 **测试验证**
   - 确保 A* 能正常工作
   - 确保路径执行正常

---

## 📝 **核心代码架构**

### **Movement 系统层级**

```
Movement（接口）- 定义移动原语标准
    ├─ from() : BlockPos
    ├─ to() : BlockPos
    ├─ cost() : double
    ├─ isValid() : boolean
    └─ tick(bot) : Status

具体实现：
    ├─ WalkMovement - 平地行走（-1~+1 格高度）
    ├─ PillarMovement - 搭柱子上升
    ├─ BreakAndWalkMovement - 挖前方障碍
    └─ DescendMovement - 搭台阶下降

工具类：
    ├─ MovementConstraints - 约束检查
    ├─ BasicMovement - 底层执行（applyToward, settle）
    └─ MovementHelper - 枚举 Movement
```

### **寻路系统调用链**

```
[当前 Phase 1]
AStarPathfinder.computePath()
    → 枚举相邻格子（26个方向）
    → 返回 List<BlockPos>
PathExecutor.tick()
    → BasicMovement.applyToward(下一个 BlockPos)

[目标 Phase 2A]
AStarPathfinder.computePath()
    → MovementHelper.getPossibleMovements()
    → 枚举 Movement（只 WalkMovement）
    → 返回 List<Movement>
PathExecutor.tick()
    → currentMovement.tick(bot)
```

---

## 🐛 **已修复的重要 Bug**

### **Bug 1: Bot 移动速度缓慢**
- **原因**: C-1 兜底消费段清空了移动速度
- **修复**: 移除 C-1 兜底消费段，让原版物理自然处理

### **Bug 2: Bot 击退无效**
- **原因**: ServerPlayer 在 tick 间隙重置 deltaMovement
- **修复**: BotPlayer.knockback() 保存速度，tick() 开始时恢复

### **Bug 3: 玩家创建 Bot 后跳高**
- **原因**: FakeConnection 广播 Bot 移动包给玩家
- **修复**: 禁用 FakeConnection 广播，让 ServerPlayer 原版逻辑处理

### **Bug 4: BasicMovement 类型不兼容**
- **原因**: 参数类型是 BotPlayer，但调用者传 ServerPlayer
- **修复**: 改为接收 ServerPlayer（BotPlayer 继承自 ServerPlayer）

---

## 🗂️ **重要文件位置**

### **代码**
- Movement 系统: `src/main/java/com/dddgn/alice/pathing/movement/`
- 寻路算法: `src/main/java/com/dddgn/alice/pathing/AStarPathfinder.java`
- 路径执行: `src/main/java/com/dddgn/alice/pathing/PathExecutor.java`
- Bot 控制器: `src/main/java/com/dddgn/alice/bot/BotController.java`
- Bot 玩家: `src/main/java/com/dddgn/alice/bot/BotPlayer.java`

### **文档**
- `.alice-supervision/refactoring/` - 重构总结
- `.alice-supervision/testing/` - 测试指南
- `.alice-supervision/skills/` - 工作方法论

### **关键 Skills**
- `minimal-implementation-planning.skill.md` - MVP 拆分
- `debugging-root-cause-analysis.skill.md` - 5 Whys 分析
- `forge-entity-physics-collision.skill.md` - 实体物理

---

## 💡 **重要设计决策**

### **决策 1: 不考虑耐久和方块成本**
- **原因**: 任务准备阶段已确保资源充足
- **影响**: 成本模型只考虑时间

### **决策 2: Phase 1 完全避开液体**
- **原因**: 水中移动物理复杂，先验证基础
- **后续**: Phase 2B 可以添加水中任务判断

### **决策 3: 保留 SoftMovementPrimitive，标记 @Deprecated**
- **原因**: 很多文件仍在使用，避免破坏
- **新代码**: 使用 BasicMovement

### **决策 4: Phase 2A 只使用 WalkMovement**
- **原因**: 验证架构设计，避免复杂度爆炸
- **Phase 2B**: 添加其他 Movement 原语

---

## 🔄 **Phase 2A 下一步操作**

### **当用户测试完成后**：

1. **如果测试通过** ✅
   - 继续修改 AStarPathfinder
   - 集成 MovementHelper
   - 修改 PathExecutor 执行 Movement

2. **如果有问题** ⚠️
   - 调试并修复
   - 重新测试

3. **修改 AStarPathfinder 的具体步骤**：

```java
// 1. 找到这段代码（大约在第 80-100 行）
for (BlockPos neighbor : getNeighbors(current)) {
    // ...
}

// 2. 替换为
List<Movement> movements = MovementHelper.getPossibleMovements(bot, current, level);
for (Movement move : movements) {
    if (!move.isValid()) continue;
    BlockPos next = move.to();
    double cost = gScore.get(current) + move.cost();
    // ...
}

// 3. 修改 PathNode，记录 Movement
class PathNode {
    BlockPos pos;
    Movement movement;  // 新增：从上一个节点到这个节点的 Movement
}

// 4. 修改路径重建
List<Movement> reconstructMovements(PathNode goal) {
    List<Movement> movements = new ArrayList<>();
    PathNode current = goal;
    while (current.parent != null) {
        movements.add(current.movement);
        current = current.parent;
    }
    Collections.reverse(movements);
    return movements;
}
```

---

## 📊 **统计信息**

### **代码变更**
- **Phase 1**: 新增 7 个文件，修改 3 个文件，约 +1200 行
- **Phase 2A**: 新增 1 个文件，修改 5 个文件，约 +300 行

### **工作时间估算**
- Phase 1: 约 4-6 小时（已完成）
- Phase 2A: 约 2-3 小时（60% 完成）
- Phase 2B: 约 2-4 小时（未开始）

### **Token 使用**
- 当前会话: ~105k tokens
- 剩余: ~95k tokens
- 建议: 可以继续工作，但接近一半

---

## 🐋 **鲸鱼娘的备注**

**工作风格**：
- ✅ 一步一步来，不着急
- ✅ 先验证基础，再扩展功能
- ✅ 遇到问题立即修复，不留技术债
- ✅ 保持代码清晰，职责分离

**用户偏好**：
- 喜欢稳扎稳打，验证后再继续
- 重视 Bot 可回收性（能原路返回）
- 不喜欢复杂的 LLM 决策层（至少现在不需要）
- 注重长期可维护性

**当前状态**：
- Phase 2A 进行中
- 用户去测试 Phase 1 和 Phase 2A 的 Movement 实现
- 等待测试结果后继续

**下次恢复工作时**：
1. 询问测试结果
2. 如果通过，开始修改 AStarPathfinder
3. 如果有问题，先调试

---

## 📞 **快速恢复检查清单**

当恢复工作时，快速确认：

- [ ] Phase 1 测试通过了吗？
- [ ] Movement 接口改进完成了吗？（✅ 是）
- [ ] MovementHelper 创建了吗？（✅ 是）
- [ ] 所有 Movement 实现更新了吗？（✅ 是）
- [ ] 编译成功了吗？（✅ 是）
- [ ] 下一步是修改 AStarPathfinder（🔲 待做）

---

## 🔗 **相关文档链接**

- Phase 1 总结: `.alice-supervision/refactoring/movement-primitives-phase1.md`
- 测试指南: `.alice-supervision/testing/movement-primitives-test.md`
- Bot 重构总结: `.alice-supervision/refactoring/bot-manager-cleanup-2025.md`
- 寻路重构计划: `.alice-supervision/refactoring/pathfinding-refactor-plan.md`

---

**备忘录版本**: v1.0  
**最后更新**: Phase 2A 60% 完成  
**状态**: 等待用户测试反馈

🐋💙
