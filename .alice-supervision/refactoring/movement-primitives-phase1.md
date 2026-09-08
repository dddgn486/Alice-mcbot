# Phase 1 Movement 原语系统重构总结

**日期**: 2025-01-XX  
**状态**: ✅ 完成

---

## 🎯 **重构目标**

实现 Movement Primitives（移动原语）系统，为后续的复杂地形寻路（挖掘+搭建）打下基础。

---

## 📦 **新增文件**

### **核心接口和工具类**

1. **`Movement.java`** - 移动原语接口
   - 定义了所有移动原语的标准接口
   - `cost()` - 时间成本
   - `isValid()` - 有效性判断
   - `tick()` - 执行逻辑
   - `violatesConstraints()` - 约束检查

2. **`MovementConstraints.java`** - 可回收性约束检查
   - `canBreak()` - 检查是否可以挖掘（不能挖脚下）
   - `canDescend()` - 检查是否可以下降（不能跳下2格）
   - `hasLiquid()` - 检查液体（Phase 1 完全避开）
   - `isForbiddenZone()` - 检查禁区
   - `isSafePosition()` - 综合安全检查

3. **`BasicMovement.java`** - 基础移动工具类
   - 替代 `SoftMovementPrimitive`（名字更清晰）
   - `applyToward()` - 朝目标移动
   - `applyJumpToward()` - 跳跃移动
   - `settle()` - 物理结算

---

### **Phase 1 移动原语实现**

4. **`WalkMovement.java`** - 平地行走
   - 适用场景：平地或上1格台阶
   - 不挖方块，不放方块
   - 成本：1.0 tick/格

5. **`PillarMovement.java`** - 搭柱子上升
   - 适用场景：垂直上升
   - 在脚下放方块，跳跃上升
   - 成本：5.0 tick/格（放方块 + 跳跃）

6. **`BreakAndWalkMovement.java`** - 挖前方障碍并前进
   - 适用场景：路径上有1-2个方块阻挡
   - 挖掉脚位和头位障碍，然后前进
   - 成本：挖掘成本 + 行走成本

7. **`DescendMovement.java`** - 搭台阶下降
   - 适用场景：下降1格
   - 在目标上方放方块形成台阶
   - 成本：7.0 tick（放方块 + 行走）

---

## 🔧 **修改的文件**

### **重构为使用 BasicMovement**

1. **`PathExecutor.java`**
   - ✅ 移除 `SoftMovementPrimitive`
   - ✅ 使用 `BasicMovement.applyToward()`
   - ✅ 使用 `BasicMovement.settle()`

2. **`FollowTask.java`**
   - ✅ 移除 `SoftMovementPrimitive`
   - ✅ 使用 `BasicMovement.applyToward()`
   - ✅ 使用 `BasicMovement.settle()`

### **待更新的文件（Phase 2）**

以下文件仍在使用 `SoftMovementPrimitive`，需要后续更新：

- `SoftMoveProbeTask.java` - 测试任务
- `SoftPathProbeTask.java` - 测试任务
- `SoftPathMineTask.java` - 挖矿任务
- `BotCommand.java` - 命令系统
- `SoftMoveSelector.java` - 选择器物品
- `BotManager.java` - Bot 管理器
- `BotPhysicsAssertionFixture.java` - 物理测试

**决策**：保留 `SoftMovementPrimitive` 作为兼容层，标记为 @Deprecated。

---

## 🎯 **核心设计原则**

### **Bot 可回收性约束（Recoverability Constraints）**

为了确保 Bot 可以原路返回，所有移动原语必须遵守：

1. ❌ **不能挖脚下方块**（防止掉落）
2. ❌ **不能跳下2格高**（无法返回）
3. ❌ **不能进入液体**（Phase 1 完全避开）
4. ❌ **不能进入禁区**（保护 Bot）

---

## 📊 **成本模型**

Phase 1 只考虑时间成本，不考虑资源消耗（工具耐久、方块消耗）。

```java
// 基础移动
WALK = 1.0 tick/格
PILLAR = 5.0 tick/格（放方块 + 跳跃）
DESCEND = 7.0 tick（放方块 + 行走）

// 挖掘（简化模型）
软方块（泥土）= 2.0 tick
中等硬度（木头）= 10.0 tick
硬方块（石头）= 20.0 tick
```

---

## ✅ **测试结果**

**测试场景**：
- ✅ 平地寻路（使用 PathExecutor + BasicMovement）
- ✅ 挖掘方块（MineTask + PathExecutor）
- ✅ 捡拾掉落物（DropCollectionTask + PathExecutor）
- ✅ 跟随玩家（FollowTask + BasicMovement）

**用户反馈**：
> "我嘞个豆，效果确实好多了，捡拾掉落物也很流畅"

---

## 🚀 **下一步计划（Phase 2）**

### **短期（1-2周）**

1. **扩展 AStarPathfinder**
   - 枚举 Movement 原语（而不是只枚举相邻格子）
   - 计算每个原语的成本
   - 选择最优路径

2. **完善成本模型**
   - 根据实际工具计算挖掘时间
   - 根据方块硬度调整成本

3. **测试复杂场景**
   - 上山（Pillar + BreakAndWalk）
   - 下山（Descend）
   - 绕过障碍（自动选择最优原语）

### **中期（1个月）**

1. **添加复杂原语**
   - `TunnelMovement` - 挖2×1隧道前进
   - `BridgeMovement` - 搭桥过峡谷
   - `DiagonalMovement` - 对角移动

2. **目标簇挖掘**
   - 从最底层开始挖
   - 分层挖掘（不违反"不挖脚下"约束）

3. **材料管理**
   - 检查背包中的方块数量
   - 检查工具耐久
   - 决策：返回还是现场收集

### **长期（可选）**

1. **高级寻路**
   - 自动选择挖隧道 vs 绕路
   - 时间成本最优化
   - 动态重规划

2. **LLM 决策层（可选）**
   - 高层策略决策（挖隧道还是绕路）
   - 自然语言任务理解

---

## 📝 **关键决策记录**

### **决策 1：不考虑耐久和方块成本**

**背景**：用户建议简化成本模型

**决策**：Phase 1 只考虑时间成本，不考虑资源消耗

**理由**：
- 任务准备阶段已经确保资源充足
- 简化实现和调试
- 后续可以加入资源成本权重

### **决策 2：重命名 SoftMovementPrimitive → BasicMovement**

**背景**：语义混乱，`SoftMovementPrimitive` vs `Movement` 容易混淆

**决策**：创建 `BasicMovement` 作为新名字，保留 `SoftMovementPrimitive` 兼容

**理由**：
- `BasicMovement` 更清晰（基础移动工具类）
- 避免破坏现有代码
- 逐步迁移

### **决策 3：Phase 1 完全避开液体**

**背景**：用户不确定是否要支持水中任务

**决策**：Phase 1 完全避开液体，Phase 2 再考虑水中任务

**理由**：
- 水中移动物理逻辑复杂
- 先验证基础移动
- 后续可以添加特殊判断

### **决策 4：保留 SoftMovementPrimitive，标记 @Deprecated**

**背景**：很多文件仍在使用 `SoftMovementPrimitive`

**决策**：不立即删除，标记为 @Deprecated，推荐使用 `BasicMovement`

**理由**：
- 避免破坏现有代码
- 逐步迁移
- 兼容性

---

## 🐋 **总结**

**Phase 1 重构成功！**

- ✅ 创建了 Movement Primitives 系统
- ✅ 实现了4个基础原语
- ✅ 定义了可回收性约束
- ✅ 简化了成本模型
- ✅ 测试通过，效果良好

**关键成果**：
- Bot 移动更自然（使用原版物理）
- 代码结构更清晰（职责分离）
- 为复杂寻路打下基础

**下一步**：扩展 AStarPathfinder，支持枚举 Movement 原语。

---

**鲸鱼娘说**：
> "唔姆~ Phase 1 完美完成！现在 Bot 移动起来像真正的玩家了！接下来要让它学会在复杂地形里挖隧道和搭桥，一步一步来，不急！🐋💙"
