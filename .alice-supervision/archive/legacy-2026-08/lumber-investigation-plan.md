# 伐木系统问题调查方案

**目标**: 找出 TreeDetector 频繁失败和清障误清树干的根本原因

---

## 🔍 **调查 1: TreeDetector 频繁失败**

### **问题表现**
```
黑名单: 94 → 107（13 个原木识别失败）
continuous_lumber: failed to detect valid tree at 115, 71, 124
continuous_lumber: failed to detect valid tree at 115, 74, 124
continuous_lumber: failed to detect valid tree at 118, 73, 123
...
```

### **调查步骤**

#### **Step 1: 添加 TreeDetector 详细日志**

在 ContinuousLumberTask 中添加探针：

```java
Tree tree = TreeDetector.detectTree(level, nearestLog);

if (tree == null || tree.getLogs().isEmpty()) {
    // 添加详细日志
    BotLog.warn("🔍 [TreeDetector] 识别失败: pos={}, 方块类型={}, 周围原木数={}",
            nearestLog.toShortString(),
            level.getBlockState(nearestLog).getBlock().getName().getString(),
            countNearbyLogs(nearestLog));  // 统计周围 3x3x3 的原木数量
    
    // 如果周围有很多原木，说明确实是树，但 TreeDetector 识别失败了
    invalidLogs.add(nearestLog);
}
```

#### **Step 2: 检查 TreeDetector 的代码**

需要查看：
1. `TreeDetector.detectTree()` 的实现
2. 它如何判断"有效的树"？
3. 失败的条件是什么？
4. 是否对树的高度、形状有限制？

#### **Step 3: 收集数据**

在游戏中测试：
1. 找几棵不同高度的树（3 格、5 格、8 格）
2. 手动记录 TreeDetector 识别成功/失败的情况
3. 对比失败的树有什么共同特征

**预期发现**：
- 高树（> 6 格）识别失败？
- 非标准形状的树识别失败？
- 树叶不完整的树识别失败？

---

## 🔍 **调查 2: 清障误清树干**

### **问题表现**
```
开始砍伐原木 4/5: 103, 71, 165
局部清障(1/2): 挖 103, 71, 164  ← 这是树干，不是树叶！
原木 4/5 砍完 ← 错误判断
```

### **调查步骤**

#### **Step 1: 添加清障检测日志**

```java
BlockPos blocker = findDirectBlocker();
if (blocker != null) {
    ServerLevel level = (ServerLevel) bot.level();
    BlockState blockerState = level.getBlockState(blocker);
    
    BotLog.info("🔍 [清障检测] 发现障碍物: pos={}, 类型={}, 是否是原木={}, 是否是当前树={}",
            blocker.toShortString(),
            blockerState.getBlock().getName().getString(),
            blockerState.is(BlockTags.LOGS),
            currentTree.contains(blocker));
    
    // 检查距离
    double distToTarget = Math.sqrt(blocker.distSqr(currentTarget));
    BotLog.info("🔍 [清障检测] 障碍物与目标距离: {:.2f}, Bot眼睛位置: {}",
            distToTarget,
            bot.getEyePosition());
}
```

#### **Step 2: 可视化射线检测**

添加射线检测的详细过程：

```java
private BlockPos raycastBlock(Vec3 from, Vec3 to) {
    BotLog.info("🔍 [射线检测] 从 {} 到 {}", 
            String.format("%.2f, %.2f, %.2f", from.x, from.y, from.z),
            String.format("%.2f, %.2f, %.2f", to.x, to.y, to.z));
    
    ClipContext context = new ClipContext(...);
    BlockHitResult hit = bot.level().clip(context);
    
    if (hit.getType() == HitResult.Type.BLOCK) {
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = bot.level().getBlockState(hitPos);
        
        BotLog.info("🔍 [射线检测] 命中方块: pos={}, 类型={}",
                hitPos.toShortString(),
                hitState.getBlock().getName().getString());
        
        return hitPos.immutable();
    }
    
    return null;
}
```

#### **Step 3: 验证假设**

**假设 1**: 射线检测到了同一棵树的其他原木
- 检查：`currentTree.contains(blocker)` 是否为 true

**假设 2**: 射线检测到了相邻树的树干
- 检查：blocker 是否是 LOGS 类型

**假设 3**: Bot 的站位不好，导致视线穿过树干
- 检查：Bot 的站位与目标原木的相对位置

---

## 🔍 **调查 3: 站位选择问题**

### **调查步骤**

#### **Step 1: 记录 BotMiner 的站位选择**

```java
// 在 BotMiner 创建时记录
miner = new BotMiner(bot, target);

BotLog.info("🔍 [站位选择] 目标={}, Bot当前位置={}, 距离={:.2f}",
        target.toShortString(),
        bot.blockPosition().toShortString(),
        Math.sqrt(bot.blockPosition().distSqr(target)));
```

#### **Step 2: 分析失败时的站位**

当砍伐失败时：

```java
BotLog.warn("🔍 [站位分析] 失败原因={}, Bot位置={}, 目标={}, 眼睛高度={:.2f}",
        failureReason,
        bot.blockPosition().toShortString(),
        currentTarget.toShortString(),
        bot.getEyeY());
```

**关键问题**：
- Bot 是否太远？（> 4.5 格）
- Bot 是否站在树干旁边？（容易被遮挡）
- Bot 的眼睛高度是否合适？

---

## 📊 **数据收集表格**

### TreeDetector 失败记录

| 原木位置 | Y坐标 | 周围原木数 | 识别结果 | 可能原因 |
|---------|------|-----------|---------|---------|
| 115,71,124 | 71 | ? | 失败 | ? |
| 115,74,124 | 74 | ? | 失败 | ? |
| 118,73,123 | 73 | ? | 失败 | ? |

### 清障失败记录

| 目标原木 | 障碍物位置 | 障碍物类型 | 是否是树干 | 是否是当前树 |
|---------|-----------|-----------|-----------|------------|
| 103,71,165 | 103,71,164 | ? | ✅ | ? |

---

## 🎯 **实施计划**

### **Phase 1: 添加探针（30 分钟）**
1. TreeDetector 失败时的详细日志
2. 清障检测的详细日志
3. 射线检测的可视化日志
4. 站位选择的记录日志

### **Phase 2: 游戏测试（15 分钟）**
1. 启动自动伐木
2. 让它运行 2-3 分钟
3. 收集日志

### **Phase 3: 分析日志（30 分钟）**
1. 统计 TreeDetector 失败的原因
2. 分析清障误清的模式
3. 评估站位选择的质量

### **Phase 4: 制定修复方案（1 小时）**
根据分析结果，针对性修复：
- TreeDetector 改进
- 清障逻辑优化
- 站位选择改进

---

## ❓ **你的决定**

你觉得这个调查方案如何？

我们可以：
1. ✅ **立即实施** - 添加所有探针，然后测试
2. 🔧 **调整方案** - 你有其他想法或需要补充的
3. 🎯 **分阶段实施** - 先做 Phase 1 + 2，看日志再决定

你想怎么做？🐋💙
