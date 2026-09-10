# BotManager 重构总结（2025年）

## 🎯 重构目标

清理 BotManager 的历史遗留问题，分离职责，移除不需要的功能。

---

## 🐛 发现的问题

### **问题 1：Bot 移动速度缓慢**

**症状**：
- Bot 手动控制时移动速度极慢（每 tick 只移动 0.018 方块）
- `xxa=1.0`, `zza=1.0`, `MOVEMENT_SPEED=0.1` 都正常

**根因**：
- C-1 兜底消费段误判 Bot 为"空闲"（因为没有任务）
- 在 tick 末尾清空了手动控制的移动速度
- Bot 的移动被 C-1 劫持，只移动了残留的 0.018 方块

**解决方案**：
- 修改 `isTaskLayerInput()` 检查 `bot.xxa/zza` 实际输入值
- 而不是检查 Controller 状态（可能被网络包清零）

---

### **问题 2：Bot 击退无效**

**症状**：
- 攻击 Bot 后，`knockback()` 设置了速度 `(-0.395, 0.361, 0.065)`
- 但 Bot 在游戏中没有任何击退效果

**根因**：
- 击退发生在 tick 间隙（两个 tick 之间）
- ServerPlayer 在下一个 tick 开始前，从客户端同步位置/速度
- 但 Bot 没有真实客户端，导致 deltaMovement 被重置为 0

**时间线**：
```
21:33:02.560 [TICK] end
21:33:02.568 [KNOCKBACK] 设置速度 (-0.395, 0.361, 0.065)
21:33:02.612 [TICK] start，速度已经是 (0.0, -0.078, 0.0)  ← 被清空了！
```

**解决方案**：
- 在 `BotPlayer.knockback()` 保存击退速度
- 在 `BotPlayer.tick()` 开始时立即恢复
- 绕过 ServerPlayer 的客户端同步逻辑

**代码**：
```java
// BotPlayer.java
private Vec3 savedKnockbackVelocity = Vec3.ZERO;
private boolean needRestoreKnockback = false;

@Override
public void knockback(double strength, double x, double z) {
    super.knockback(strength, x, z);
    savedKnockbackVelocity = this.getDeltaMovement();
    needRestoreKnockback = true;
}

@Override
public void tick() {
    if (needRestoreKnockback) {
        this.setDeltaMovement(savedKnockbackVelocity);
        needRestoreKnockback = false;
    }
    // ... 正常 tick 逻辑
}
```

---

## ✅ 重构内容

### **1. 移除 C-1 兜底消费段**

**原因**：
- ❌ 干扰击退等正常物理效果（清空击退速度）
- ❌ 职责混乱（物理逻辑不应该在任务管理器里）
- ❌ 执行时机错误（tick 末尾，在 knockback 之后）
- ✅ 原版摩擦力已足够处理残留速度（地面 × 0.91，空中 × 0.98）

**移除的代码**：
```java
// BotManager.onServerTick() 中移除
if (!isTaskLayerInput(session.bot())) {
    Vec3 residual = session.bot().getDeltaMovement();
    // ... 清空水平速度
    session.bot().setDeltaMovement(new Vec3(0.0D, residual.y, 0.0D));
}

// 移除 isTaskLayerInput() 方法（不再需要）
```

---

### **2. 清理调试日志**

**BotPlayer.java**：
- 移除 `[TICK_TRACE]` 日志（每个 tick 的 deltaMovement 追踪）
- 移除 `[HURT]` 日志
- 移除 `[KNOCKBACK]` 详细日志
- 保留击退修复的核心逻辑

**BotManager.java**：
- 移除 C-1 相关的注释和代码

---

### **3. 更新类文档**

**BotManager.java**：
```java
/**
 * 假人管理器：负责 Bot 的生命周期和任务调度。
 * 
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>生命周期管理</b>：spawn()、remove()、持久化</li>
 *   <li><b>任务调度</b>：BotSession.tick() 驱动任务执行</li>
 *   <li><b>生存系统</b>：SurvivalSystem.tick() 检查危险状态</li>
 * </ul>
 * 
 * <h3>不负责的事项</h3>
 * <ul>
 *   <li>❌ 物理计算：由 BotPlayer.tick() 处理</li>
 *   <li>❌ 输入控制：由 BotController.onUpdate() 处理</li>
 *   <li>❌ 击退/受伤：由 BotPlayer.hurt()/knockback() 处理</li>
 * </ul>
 */
```

**职责分离**：
- **BotManager**：任务调度、生命周期管理
- **BotPlayer**：物理计算、击退、受伤
- **BotController**：输入控制（设置 xxa/zza）
- **BotSession**：单个 Bot 的任务执行

---

## 📊 重构结果

### **代码行数变化**：
- `BotManager.java`: 716 行 → 约 650 行（-66 行）
- `BotPlayer.java`: 移除大量调试日志，保留核心逻辑

### **功能状态**：
- ✅ Bot 手动控制移动速度正常
- ✅ Bot 击退效果正常
- ✅ 原版物理自然处理残留速度
- ✅ 职责清晰，分层明确

### **技术债**：
- ⚠️ 击退修复是"治标不治本"的方案（保存并恢复速度）
- ⚠️ 理想方案是找到 ServerPlayer 清空速度的根源并禁用它
- ⚠️ 但当前方案已足够稳定，可以暂时使用

---

## 🔍 未来改进方向

### **1. 深入调试 ServerPlayer 的客户端同步**

找到 ServerPlayer 在哪里重置 deltaMovement：
- `ServerPlayer.doTick()`
- `ServerGamePacketListenerImpl.tick()`
- `ServerPlayer.absMoveTo()`

重写对应方法，禁用 Bot 的客户端同步。

---

### **2. 参考其他 Bot 项目**

查看 **carpet-mod** 或 **mc_aiplayer** 如何处理击退：
- 是否也遇到过这个问题？
- 他们的解决方案是什么？

---

### **3. 测试极端情况**

- 爆炸推力是否正常？
- 钓鱼竿拉扯是否正常？
- 活塞推动是否正常？

---

## 🐋 总结

这次重构：
1. ✅ 修复了两个重要的物理 bug（移动速度、击退）
2. ✅ 移除了职责混乱的 C-1 兜底消费段
3. ✅ 清理了调试日志，代码更简洁
4. ✅ 明确了各个类的职责边界
5. ✅ 更新了文档，便于后续维护

BotManager 现在只负责"高层逻辑"（任务、生存），不干预"底层物理"（移动、碰撞），职责清晰！🎉
