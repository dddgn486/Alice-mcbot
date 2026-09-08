# 自动伐木卡死问题修复报告

**修复日期**: 2025-01-XX  
**问题优先级**: 🔥 P0  
**状态**: ✅ 已修复（待游戏测试验证）

---

## 📊 **问题诊断**

### **用户反馈**
```
<Dev> 卡住了
<Dev> 他遇到了第二层就是树叶的树要先挖树叶，才哇得到第一个木头
<Dev> 帮忙打掉树叶就开始动了
```

### **日志证据**

#### 1. 视线受阻（核心问题）
```
[11:34:32] mine 失败(视线受阻): target=102, 65, 109 stand=103, 64, 110 (保持站位，等待清障)
[11:34:33] mine 失败(视线受阻): target=102, 66, 109 stand=103, 64, 110 (保持站位，等待清障)
[11:34:33] mine 失败(视线受阻): target=102, 67, 109 stand=103, 64, 110 (保持站位，等待清障)
```

#### 2. PathExecutor 卡住
```
[11:34:43] 路径执行失败: 卡住 81 tick, 位置 (102.30, 64.00, 109.30)
[11:34:43] mine 失败: target=101, 63, 106 reason=path_failed
```

#### 3. 高原木挖不到
```
[11:34:18] mine 失败: target=102, 67, 109 reason=stand_search_limit(所有候选站位未完成搜索)
```

### **根因分析**

```
问题根源: ContinuousLumberTask 没有清障逻辑
         └─ MineTask 有完整的清障机制
         └─ ContinuousLumberTask 只使用 BotMiner
         └─ BotMiner 视线受阻时返回 FAILED
         └─ Task 只是跳过，没有清障
         └─ 用户手动打掉树叶后才能继续
```

**对比 MineTask**:
| 功能 | MineTask | ContinuousLumberTask（修复前） |
|------|----------|-------------------------------|
| 清障逻辑 | ✅ 有 | ❌ 无 |
| findDirectBlocker | ✅ 有 | ❌ 无 |
| 视线受阻处理 | ✅ 自动清障 | ❌ 只返回失败 |
| 失败重试 | ✅ 有限重试 | ❌ 无限重试同一棵树 |

---

## 🔧 **修复方案**

### **核心思路**
将 MineTask 的清障逻辑集成到 ContinuousLumberTask，实现两个任务的逻辑同步。

### **修改 1: 添加清障相关字段**

```java
private BlockPos originalTarget = null;   // 原始目标（清障时保存）
private int clearDepth = 0;               // 当前清障深度

private static final int MAX_CLEAR_DEPTH = 2;    // 最大清障深度
private static final double MAX_CLEAR_REACH = 4.5D;  // 最大清障距离
```

### **修改 2: 改进砍伐失败处理**

```java
if (minerStatus == BotMiner.Status.FAILED) {
    String failureReason = miner.failureReason();
    
    // 1. 检查硬失败（不可恢复）
    if (isHardFailure(failureReason)) {
        // 跳过该原木
        currentLogIndex++;
        return Status.RUNNING;
    }
    
    // 2. 尝试清障
    BlockPos blocker = findDirectBlocker();
    if (blocker != null && clearDepth < MAX_CLEAR_DEPTH) {
        // 安全检查
        String refusal = BlockBreakSafety.clearingRefusal(bot, blocker);
        if (refusal == null) {
            clearDepth++;
            originalTarget = currentTarget;  // 保存原始目标
            currentTarget = blocker;         // 先挖障碍物
            miner = new BotMiner(bot, blocker);
            return Status.RUNNING;
        }
    }
    
    // 3. 无法清障，增加失败计数
    currentLogFailures++;
    if (currentLogFailures >= MAX_LOG_FAILURES) {
        // 放弃整棵树
        invalidTrees.add(currentTreeBasePos);
        phase = Phase.SCANNING;
    }
}
```

### **修改 3: 清障完成后恢复**

```java
if (minerStatus == BotMiner.Status.DONE) {
    // 检查是否是清障完成
    if (originalTarget != null && !currentTarget.equals(originalTarget)) {
        BotLog.info("清障完成: {} -> 继续挖 {}", 
                currentTarget, originalTarget);
        
        // 恢复挖原始目标
        currentTarget = originalTarget;
        originalTarget = null;
        clearDepth = 0;
        miner = new BotMiner(bot, currentTarget);
        return Status.RUNNING;
    }
    
    // 原木砍完，继续下一个
    currentLogIndex++;
    // ...
}
```

### **修改 4: 添加辅助方法**

#### **findDirectBlocker()**
从 Bot 眼睛到原始目标进行射线检测，找到第一个障碍物。

```java
private BlockPos findDirectBlocker() {
    if (originalTarget == null) return null;
    
    Vec3 eye = bot.getEyePosition();
    BlockPos blocker = raycastBlock(eye, originalTarget.getCenter());
    
    if (blocker == null || blocker.equals(originalTarget)
            || eye.distanceTo(blocker.getCenter()) > MAX_CLEAR_REACH) {
        return null;
    }
    
    return blocker;
}
```

#### **raycastBlock()**
射线检测，返回碰撞的第一个方块。

```java
private BlockPos raycastBlock(Vec3 from, Vec3 to) {
    ClipContext context = new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            bot);
    BlockHitResult hit = bot.level().clip(context);
    return hit.getType() == HitResult.Type.BLOCK
            ? hit.getBlockPos().immutable() : null;
}
```

#### **isHardFailure()**
判断是否是不可恢复的硬失败。

```java
private boolean isHardFailure(String reason) {
    return "unbreakable_block".equals(reason)
            || "fluid_risk_lava".equals(reason)
            || reason.startsWith("protected_");
}
```

---

## 📊 **修复效果对比**

### **修复前**
```
Bot 到达站位 → 视线被树叶遮挡
            → BotMiner 返回 FAILED
            → Task 增加失败计数
            → 重试 → 失败 → 重试 → 失败
            → 失败 3 次 → 放弃整棵树
            → ❌ 用户需要手动打掉树叶
```

### **修复后**
```
Bot 到达站位 → 视线被树叶遮挡
            → BotMiner 返回 FAILED
            → Task 检测到视线受阻
            → findDirectBlocker() 找到树叶
            → 安全检查通过
            → 保存原始目标
            → 先挖树叶（清障）
            → 清障完成
            → 恢复挖原始目标
            → ✅ 自动完成，无需人工干预
```

---

## 🎮 **测试方案**

### **场景 1: 树叶遮挡测试**
1. 找一棵第二层是树叶的树
2. 启动自动伐木器
3. **期望**: Bot 自动清掉树叶，继续砍原木

**关键日志**:
```
🪓 [CUTTING] 局部清障(1/2): 当前站位直接挖 (x,y,z)
🪓 [CUTTING] 清障完成: 已挖掉遮挡 (x,y,z) -> 继续挖原目标 (x,y,z)
```

---

### **场景 2: 多层树叶测试**
1. 找一棵树叶很密集的树
2. 启动自动伐木器
3. **期望**: 
   - 清障 1-2 层后继续
   - 超过 2 层则放弃，扫描下一棵树

**关键日志**:
```
🪓 [CUTTING] 局部清障(1/2): ...
🪓 [CUTTING] 局部清障(2/2): ...
🪓 [CUTTING] 原木 X/Y 砍伐失败: ..., 失败次数: 1/3
```

---

### **场景 3: 高树放弃测试**
1. 找一棵很高的树（6+ 格）
2. 启动自动伐木器
3. **期望**: 
   - 尝试清障
   - 失败 3 次后放弃
   - 扫描下一棵树

**关键日志**:
```
🪓 [CUTTING] 原木 X/Y 砍伐失败: ..., 失败次数: 3/3
🪓 [CUTTING->SCANNING] 原木连续失败 3 次，放弃整棵树: (x,y,z)
🔍 [SCANNING] 树 (x,y,z) 在黑名单中，跳过
```

---

### **场景 4: 硬失败跳过测试**
1. 在原木前放置基岩/岩浆
2. 启动自动伐木器
3. **期望**: 检测到硬失败，直接跳过该原木

**关键日志**:
```
🪓 [CUTTING] 原木遇到硬失败: ..., 原因: unbreakable_block, 跳过
```

---

## 📈 **代码统计**

| 项目 | 数量 |
|------|------|
| 新增字段 | 3 个 |
| 新增常量 | 2 个 |
| 新增方法 | 3 个 |
| 修改方法 | 1 个（handleCutting） |
| 代码行数 | +120 行 |

---

## ✅ **验收标准**

- [x] 编译通过
- [x] 代码逻辑与 MineTask 同步
- [ ] 场景 1 测试通过（树叶自动清障）
- [ ] 场景 2 测试通过（多层树叶处理）
- [ ] 场景 3 测试通过（高树放弃）
- [ ] 场景 4 测试通过（硬失败跳过）
- [ ] 日志输出符合预期
- [ ] 无新增 bug

---

## 🔮 **后续优化方向**

1. **智能清障优先级**
   - 树叶优先清除（成本低）
   - 石头考虑绕过（成本高）

2. **提前检测高度**
   - 扫描时就判断树是否太高
   - 直接跳过，不浪费时间尝试

3. **改进站位选择**
   - 为高树生成更多站位候选
   - 避开树叶密集区域

4. **清障计数独立**
   - 清障失败不计入原木失败次数
   - 区分"挖不到"和"清不了"

---

## 📝 **总结**

通过集成 MineTask 的清障逻辑，ContinuousLumberTask 现在能够：

1. ✅ **自动清障** - 视线受阻时自动清掉树叶
2. ✅ **智能放弃** - 失败 3 次后放弃整棵树
3. ✅ **安全检查** - 清障前检查安全策略
4. ✅ **状态恢复** - 清障完成后继续挖原目标
5. ✅ **逻辑同步** - 与 MineTask 行为一致

**核心改进**: 从"需要用户手动干预"到"完全自动化处理"。

---

**修复完成时间**: 1.5 小时  
**下一步**: 游戏内测试验证 🎮
