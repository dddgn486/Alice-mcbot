# PathExecutor 重构测试清单

## 🎯 测试目标

验证重构后的 PathExecutor 是否正常工作。

---

## ✅ 测试清单

### **Test 1: 基本寻路**
- [ ] 创建 Bot
- [ ] 使用 MineTask 挖掘一个远处的方块
- [ ] 观察 Bot 是否能正常寻路并到达目标
- [ ] 观察移动是否自然（不是瞬移）

**预期结果**：
- Bot 能找到路径
- Bot 能平滑移动（使用原版物理）
- Bot 能到达目标

---

### **Test 2: 跟随玩家**
- [ ] 创建 Bot
- [ ] 使用 FollowTask 跟随玩家
- [ ] 玩家走动，观察 Bot 是否跟随
- [ ] 观察 Bot 是否能跟上

**预期结果**：
- Bot 能跟随玩家
- Bot 移动自然
- Bot 能维持在玩家附近

---

### **Test 3: 障碍物处理**
- [ ] Bot 寻路到目标
- [ ] 在路径中间放置方块（制造障碍）
- [ ] 观察 Bot 是否检测到受阻
- [ ] 观察 Bot 是否重新寻路

**预期结果**：
- Bot 检测到路径受阻
- 上层任务触发重新寻路
- Bot 找到新路径

---

### **Test 4: 物理结算**
- [ ] Bot 寻路到一个位置
- [ ] 观察 Bot 到达后是否正确落地
- [ ] 观察 Bot 是否会"漂浮"或"穿模"

**预期结果**：
- Bot 正确落地（onGround = true）
- Bot 位置稳定
- 没有漂浮或穿模

---

### **Test 5: 性能测试**
- [ ] Bot 寻路到远处（50+ 格）
- [ ] 观察是否有卡顿
- [ ] 观察日志中的 expandedNodes

**预期结果**：
- 寻路速度快（< 100ms）
- 没有明显卡顿
- expandedNodes < MAX_NODES

---

## 🐛 已知潜在问题

### **问题 1: 可能需要调整 SEGMENT_ARRIVE**
- 当前：0.3D
- 如果 Bot 总是在"settle"阶段超时，可能需要放宽到 0.5D

### **问题 2: 可能需要添加跳跃支持**
- 当前：没有检测上台阶
- 如果 Bot 遇到台阶卡住，需要添加跳跃逻辑

### **问题 3: 可能需要调整 MAX_SETTLE_TICKS**
- 当前：30 ticks
- 如果物理结算总是超时，可能需要增加

---

## 📊 测试记录

### **测试环境**：
- Minecraft 版本: 1.20.1
- Forge 版本: ?
- 测试地图: 超平坦/自然地形

### **Test 1 结果**：
- [ ] 通过
- [ ] 失败
- 问题描述：

### **Test 2 结果**：
- [ ] 通过
- [ ] 失败
- 问题描述：

### **Test 3 结果**：
- [ ] 通过
- [ ] 失败
- 问题描述：

### **Test 4 结果**：
- [ ] 通过
- [ ] 失败
- 问题描述：

### **Test 5 结果**：
- [ ] 通过
- [ ] 失败
- 问题描述：

---

## 🔧 调试技巧

### **启用详细日志**：
```java
// 在 PathExecutor.tick() 中
BotLog.info("段目标: {}, 水平距离: {:.2f}, settled: {}, onGround: {}", 
    segmentGoal, horizontal, settled, bot.onGround());
```

### **检查物理状态**：
```java
// 在到达判定处
BotLog.info("物理状态: pos=({:.2f},{:.2f},{:.2f}), deltaMovement={}, onGround={}", 
    bot.getX(), bot.getY(), bot.getZ(), bot.getDeltaMovement(), bot.onGround());
```

### **观察碰撞**：
```java
// 检查碰撞状态
BotLog.info("碰撞: horizontal={}, vertical={}", 
    bot.horizontalCollision, bot.verticalCollision);
```

---

## 🐋 下一步

测试完成后，根据结果：
- ✅ 如果全部通过 → 继续添加跳跃支持
- ⚠️ 如果部分失败 → 调整参数或逻辑
- ❌ 如果全部失败 → 回滚并重新分析

加油！🐋💙
