---
name: failure-pattern-recognition
description: 识别反复出现的失败模式，从多次失败中提取共性根因，避免治标不治本。
---
# Skill: Failure Pattern Recognition

## When to use this skill

Use this skill when:
- A feature fails multiple times with similar symptoms
- Different implementations of the same concept all fail
- You've tried 3+ approaches and all had issues
- Suspecting a fundamental misunderstanding rather than implementation bugs
- Need to step back and identify the common root cause

## Core Principle

**重复失败 = 系统性问题，不是随机 bug。**

如果同一个功能失败 3 次以上，问题不在"实现细节"，而在"理解"或"方向"：
- 误解了 API 行为
- 架构方向错误
- 缺少关键前置知识

找共性，不是一个个修症状。

---

## Failure Pattern Categories

### **Category 1: API 误解模式**

**识别标志**：
- 不同实现方式都失败
- 日志显示"逻辑正确"但结果错误
- 文档说"应该工作"但实际不工作

**示例（Alice 项目）**：
```
失败 1: 直接调用 travel()，bot 不动
失败 2: 设置 deltaMovement，bot 不动
失败 3: 用 setPos()，bot 瞬移但立即回弹

共性根因：误以为"设置速度 = 实体移动"
实际：需要 isEffectiveAi() = true + aiStep() 调用
```

**处理方式**：
1. 停止尝试不同"写法"
2. 深入研究 API 文档/源码
3. 找参考实现（成熟 mod）
4. 理解"为什么原版这样设计"

---

### **Category 2: 架构方向错误**

**识别标志**：
- 每次修复一个问题，出现新问题
- 代码越来越复杂，但功能仍不稳定
- "感觉不对"，但说不清哪里不对

**示例（Alice 项目）**：
```
失败 1: Bot inventory GUI，服务端正常但客户端不更新
失败 2: 加 setChanged()，部分槽位更新
失败 3: 手动发送同步包，出现"幽灵物品"
失败 4: 加 stateId 验证，shift-click 失效
失败 5-8: 各种补丁...

共性根因：客户端和服务端的槽位索引不一致
实际：应该先验证 addSlot() 顺序，而不是修同步逻辑
```

**处理方式**：
1. 画出当前架构图
2. 列出"理想架构"应该是什么样
3. 对比差异
4. 如果差异巨大，推翻重来

---

### **Category 3: 前置知识缺失**

**识别标志**：
- 文档/教程说"很简单"，但你做不出来
- 别人的代码能跑，你照抄还是不行
- 调试时"看不懂"为什么变量是这个值

**示例（Alice 项目）**：
```
失败 1: FakePlayer 不可见
失败 2: 改用 ServerPlayer，仍然不可见
失败 3: 手动发送 AddEntityPacket，客户端收不到

共性根因：不理解 PlayerList.placeNewPlayer() 的作用
实际：需要学习"实体追踪系统"的完整流程
```

**处理方式**：
1. 承认"我不懂 X"
2. 找 X 的教程/文档
3. 创建测试用例验证理解
4. 再回来实现功能

---

### **Category 4: 客户端-服务端混淆**

**识别标志**：
- 服务端日志显示正常
- 客户端表现不正常
- 或者反过来

**示例（Alice 项目）**：
```
失败模式：
- 服务端：bot.horizontalCollision = true ✓
- 客户端：bot 穿过玩家 ✗

失败模式：
- 服务端：container.setItem() 成功 ✓
- 客户端：GUI 槽位不更新 ✗

共性根因：以为"服务端正常 = 完成"
实际：需要网络同步 + 客户端验证
```

**处理方式**：
1. 明确区分"服务端逻辑"和"客户端表现"
2. 服务端测试 ≠ 客户端测试
3. 使用 `test-coverage-matrix` skill

---

## Recognition Process

### **Step 1: 记录失败历史**

```markdown
## 失败记录

### 失败 1 (commit abc123)
- 尝试：直接调用 travel(Vec3)
- 症状：bot 不移动
- 服务端日志：travel() 被调用，velocity 计算正确
- 客户端表现：bot 静止

### 失败 2 (commit def456)
- 尝试：设置 deltaMovement
- 症状：bot 不移动
- 服务端日志：deltaMovement 被设置
- 客户端表现：bot 静止

### 失败 3 (commit ghi789)
- 尝试：用 setPos() 强制移动
- 症状：bot 瞬移后立即回弹
- 服务端日志：位置更新
- 客户端表现：bot 跳到新位置，1 tick 后回到原位置
```

---

### **Step 2: 提取共性**

| 失败 | 尝试方法 | 服务端状态 | 客户端表现 | 共同点 |
|-----|---------|-----------|-----------|--------|
| 1   | travel() | velocity 正确 | 不动 | ✅ 服务端计算正确 |
| 2   | deltaMovement | 速度设置 | 不动 | ✅ 服务端状态正确 |
| 3   | setPos() | 位置更新 | 回弹 | ✅ 服务端变化有效 |

**共性**：服务端一切正常，但客户端不响应

**差异点**：
- 失败 1-2：完全不动
- 失败 3：短暂移动但回弹

**推论**：
- 不是"移动方法"的问题（3 种方法都试过）
- 不是服务端逻辑问题（日志显示正确）
- **可能是**：网络同步问题 or 客户端物理覆盖服务端

---

### **Step 3: 形成假设**

基于共性，形成可验证的假设：

**假设 A**：网络包没有发送
- 预测：客户端根本收不到位置更新包
- 验证方法：监控 ClientboundMoveEntityPacket

**假设 B**：追踪范围问题
- 预测：玩家距离 bot 太远，不追踪
- 验证方法：玩家站在 bot 旁边测试

**假设 C**：客户端物理覆盖服务端
- 预测：客户端认为 bot 是"由客户端控制"，拒绝服务端更新
- 验证方法：检查 isControlledByLocalInstance()

**假设 D**：物理计算根本没执行
- 预测：travel() 入口检查失败，velocity 被清零
- 验证方法：检查 isEffectiveAi()

---

### **Step 4: 设计决定性实验**

```java
// 实验 1：验证假设 D（最简单）
public class BotPlayer extends ServerPlayer {
    @Override
    public void travel(Vec3 vec3) {
        System.out.println("[EXPERIMENT] travel() called!");
        System.out.println("[EXPERIMENT] isEffectiveAi() = " + isEffectiveAi());
        
        if (!isEffectiveAi()) {
            System.out.println("[EXPERIMENT] ❌ FAILED: isEffectiveAi() = false!");
        }
        
        super.travel(vec3);
    }
}

// 预期结果：
// 如果假设 D 正确：travel() 被调用，但 isEffectiveAi() = false
// 如果假设 D 错误：isEffectiveAi() = true
```

---

### **Step 5: 根因确认**

```
实验结果：
[EXPERIMENT] travel() called!
[EXPERIMENT] isEffectiveAi() = false
[EXPERIMENT] ❌ FAILED: isEffectiveAi() = false!

结论：假设 D 正确！
根因：ServerPlayer.isEffectiveAi() 默认返回 false
解决方案：重写 isEffectiveAi() 返回 true

这解释了所有 3 次失败：
- 失败 1-2：travel() 入口检查失败，velocity 被清零
- 失败 3：setPos() 绕过了 travel()，但客户端物理立即恢复原位置
```

---

## Pattern Library (Alice Project)

### **Pattern: 服务端正常，客户端异常**

**识别**：
- 服务端日志显示操作成功
- 客户端看到旧状态或错误状态

**常见根因**：
1. 网络同步缺失（没有发送同步包）
2. 追踪范围问题（玩家不追踪该实体）
3. FakeConnection 不转发包
4. 客户端-服务端槽位索引不一致

**验证方法**：
- 监控网络包
- 检查追踪范围
- 客户端实测（不是服务端 fixture）

---

### **Pattern: 多次修改同一处代码**

**识别**：
- 同一个方法/类被修改 5+ 次
- 每次修改"好像修复了"，但又出新问题

**常见根因**：
1. 架构设计错误（治标不治本）
2. 误解 API（瞎改碰运气）
3. 缺少测试验证（不知道改对没有）

**处理方式**：
- 停止修改
- 读完整文档
- 找参考实现
- 写测试用例

---

### **Pattern: "应该工作"但不工作**

**识别**：
- 文档说"这样做就行"
- 照着做了，不行
- 别人的代码能跑，你的不行

**常见根因**：
1. 环境差异（Forge 版本、配置）
2. 隐含前提条件（文档没说）
3. 时序问题（初始化顺序）

**处理方式**：
- 对比差异（版本、配置、依赖）
- 找最小可复现示例
- 逐步添加代码，找出"临界点"

---

## Anti-Patterns

### **Anti-Pattern 1: 无限补丁循环**

```
失败 1 → 补丁 A → 失败 2 → 补丁 B → 失败 3 → 补丁 C → ...
```

**问题**：每个补丁修一个症状，根因未解决

**正确做法**：
- 第 3 次失败时，停下来
- 用本 skill 分析共性
- 找根因，而不是加补丁

---

### **Anti-Pattern 2: 盲目抄代码**

```
"某个 mod 也是这样做的，我抄一遍"
→ 抄完不工作
→ 再抄另一个 mod
→ 还是不工作
```

**问题**：不理解"为什么那样做"

**正确做法**：
- 理解参考代码的原理
- 验证前置条件（版本、环境）
- 写测试用例确认理解

---

### **Anti-Pattern 3: 过早放弃**

```
"试了 2 次不行，换个方向"
→ 新方向也失败
→ 再换
→ 永远在换
```

**问题**：没有深入分析失败原因

**正确做法**：
- 至少分析 3 次失败的共性
- 确认"方向错误"还是"实现细节错误"
- 如果是方向错误，换；如果是细节，深挖

---

## Checklist

使用本 skill 前检查：

- [ ] 记录了至少 3 次失败？
- [ ] 每次失败都有日志/截图证据？
- [ ] 提取了共性（表格或列表）？
- [ ] 形成了可验证的假设？
- [ ] 设计了决定性实验？
- [ ] 确认根因（不是猜测）？

全部勾选 = **PASS**，任一未勾选 = 继续分析。

---

## Evidence Requirements

使用本 skill 后需要提供：

**失败记录表**：
```markdown
| 失败 | 方法 | 服务端状态 | 客户端表现 | Commit |
|-----|-----|-----------|-----------|--------|
| 1   | ... | ...       | ...       | abc123 |
| 2   | ... | ...       | ...       | def456 |
| 3   | ... | ...       | ...       | ghi789 |
```

**共性分析**：
- 所有失败的共同点
- 关键差异点
- 推导的假设

**验证实验**：
- 实验设计（代码/步骤）
- 实验结果（日志/截图）
- 结论（哪个假设正确）

**根因报告**：
```markdown
## 根因
问题出在 X，因为 Y。

## 证据
1. 实验 A 显示 ...
2. 日志 B 证明 ...
3. 参考代码 C 确认 ...

## 解决方案
修改 X 为 Y，原因是 Z。
```

---

**This skill helps you avoid 18-28 hours of repeated trial-and-error.**
