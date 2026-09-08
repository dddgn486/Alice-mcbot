# Phase 1 Movement 原语测试指南

## 🎯 **测试目标**

验证新的 Movement Primitives 系统是否正常工作。

---

## 🧪 **测试清单**

### **Test 1: 验证基础编译**

```bash
# 编译项目
./gradlew build
```

**预期结果**：
- ✅ 编译成功
- ⚠️ 可能有 @Deprecated 警告（SoftMovementPrimitive）
- ❌ 不应该有编译错误

---

### **Test 2: 平地寻路（WalkMovement）**

**步骤**：
1. 进入游戏
2. 生成 Bot：`/alice spawn TestBot`
3. 拿出 `target_selector`（钻石斧）
4. 在10格外放一个方块
5. 右键点击方块

**预期结果**：
- ✅ Bot 移动流畅自然
- ✅ Bot 到达并挖掘方块
- ✅ 移动速度正常（不是瞬移）

---

### **Test 3: 捡拾掉落物**

**步骤**：
1. Bot 挖掉方块后
2. 观察 Bot 是否捡起掉落物

**预期结果**：
- ✅ Bot 自动寻路到掉落物
- ✅ Bot 捡起掉落物
- ✅ 聊天栏显示"任务结束"

---

### **Test 4: 跟随玩家（FollowTask）**

⚠️ **问题**：目前没有触发 FollowTask 的物品！

**临时测试方案**：
1. 查看代码确认 FollowTask 已更新为使用 BasicMovement
2. 等待创建跟随魔杖（可选）

---

## ⚠️ **已知问题**

### **问题 1: Movement 原语还未集成到 A***

**状态**：Phase 1 只实现了原语本身，还未集成到寻路算法

**影响**：
- ❌ 当前寻路还不会自动使用 Pillar/BreakAndWalk/Descend
- ✅ 但 PathExecutor 使用 BasicMovement，所以平地寻路正常

**解决方案**：Phase 2 扩展 AStarPathfinder

---

### **问题 2: PillarMovement 使用 setBlock**

**状态**：PillarMovement 当前使用 `level.setBlock()` 模拟放置方块

**影响**：
- ⚠️ 不消耗 Bot 背包中的方块
- ⚠️ 不触发放置事件

**解决方案**：Phase 2 实现正确的放置逻辑

---

### **问题 3: BreakAndWalkMovement 使用 destroyBlock**

**状态**：当前使用 `level.destroyBlock()` 模拟挖掘

**影响**：
- ⚠️ 不考虑工具
- ⚠️ 挖掘速度固定

**解决方案**：Phase 2 实现正确的挖掘逻辑

---

## 🐛 **如果测试失败**

### **编译错误**

**可能原因**：
- 缺少 import
- 包路径错误

**解决方案**：
1. 检查 import 语句
2. 确保所有新文件在正确的包中

---

### **Bot 不移动**

**可能原因**：
- BasicMovement 调用错误
- Bot 物理引擎未启用

**解决方案**：
1. 检查日志中的错误
2. 确认 BotPlayer.isEffectiveAi() = true

---

### **Bot 瞬移**

**这不应该发生！** 如果发生，说明：
- PathExecutor 还在使用旧的 setPos()
- 或者 BasicMovement 实现有问题

**解决方案**：
1. 检查 PathExecutor.java
2. 确认使用了 `BasicMovement.applyToward()`

---

## 📊 **测试报告模板**

```
测试日期: 2025-XX-XX
测试人员: [你的名字]

Test 1 (编译):
- [ ] 通过
- [ ] 失败: _______________

Test 2 (平地寻路):
- [ ] 通过
- [ ] 失败: _______________

Test 3 (捡拾掉落物):
- [ ] 通过
- [ ] 失败: _______________

Test 4 (跟随玩家):
- [ ] 跳过（无触发物品）
- [ ] 测试: _______________

其他观察:
___________________________
```

---

## 🚀 **Phase 2 准备**

如果 Phase 1 测试全部通过，可以开始 Phase 2：

1. **扩展 AStarPathfinder**
   - 枚举 Movement 原语
   - 计算原语成本
   - 选择最优路径

2. **完善原语实现**
   - 正确的放置方块逻辑
   - 正确的挖掘逻辑
   - 工具和物品检查

3. **测试复杂场景**
   - 上山（需要 Pillar）
   - 下山（需要 Descend）
   - 穿过障碍（需要 BreakAndWalk）

---

## 🐋 **总结**

Phase 1 是基础，目标是：
- ✅ 验证 Movement 接口设计
- ✅ 验证约束检查逻辑
- ✅ 验证 BasicMovement 工具类

**不需要**：
- ❌ 完整的 A* 集成（Phase 2）
- ❌ 完美的放置/挖掘逻辑（Phase 2）
- ❌ 复杂场景测试（Phase 2）

**只要基础移动正常，Phase 1 就算成功！** 🐋💙
