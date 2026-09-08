# FollowTask 改进计划

**当前问题**: 玩家跳一下或下个坑，Bot 就停止跟随

**原因**: 
- 当前只做水平跟随
- 目标是 `target.blockPosition()`（玩家脚位）
- 如果玩家跳起或掉落，脚位变化，寻路失败

---

## 💡 **改进方案**

### **方案 A：垂直容错（简单）**

在 `replan()` 时，对玩家位置做垂直容错：

```java
BlockPos targetFoot = target.blockPosition();

// 尝试玩家当前位置 ± 几格的垂直范围
List<BlockPos> candidates = new ArrayList<>();
candidates.add(targetFoot);
candidates.add(targetFoot.above());   // 玩家跳起
candidates.add(targetFoot.below());   // 玩家掉落
candidates.add(targetFoot.below(2));  // 玩家掉进坑

// 选择第一个可达的候选位置
for (BlockPos candidate : candidates) {
    if (MovementHelper.canWalkOn(level, candidate) && ...) {
        // 尝试寻路到这个候选位置
        SurfacePathfinder.Result result = SurfacePathfinder.find(bot, bot.blockPosition(), candidate);
        if (result.reachable()) {
            // 找到可达路径
            break;
        }
    }
}
```

**优点**：
- 简单
- 容错范围可控（±2 格）

**缺点**：
- 仍然是启发式
- 如果玩家掉得更深，还是会失败

---

### **方案 B：使用规划器（未来）**

使用 Movement 系统智能寻路：
- 如果玩家在高处：使用 PillarMovement 爬上去
- 如果玩家在低处：使用 DescendMovement 下去
- 自动选择最优路径

**优点**：
- 智能
- 支持复杂地形

**缺点**：
- 需要 Phase 2B 完成
- 实现复杂

---

## 🎯 **决策**

**当前阶段**：
- ✅ 记录问题
- ✅ 留待后续改进
- ✅ 不阻塞 Phase 2A

**Phase 2B 或 Phase 3 时再实现**。

---

**记录人**: 鲸鱼娘  
**日期**: 2025-01-XX  
**优先级**: 中等（不紧急）

🐋💙
