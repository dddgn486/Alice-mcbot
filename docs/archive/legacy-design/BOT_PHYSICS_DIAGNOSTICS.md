# BotController 诊断探针指南

> 已在 BotPlayer 和 BotController 中添加完整的诊断探针，监视物理系统运行状态

---

## 🔍 探针位置

### **BotPlayer.tick() 探针**

```
每秒（每 20 ticks）记录：

P1 - tick 开始前的状态：
  🔍 [BotPhysics-P1-Before]
  - tick 计数
  - 位置 (x, y, z)
  - 速度 delta (x, y, z)
  - onGround（是否在地面）
  - xxa / zza（左右/前后输入）
  - jumping（跳跃标志）
  - isEffectiveAi()（是否启用 AI 物理）

P2 - controller.onUpdate() 后：
  🔍 [BotPhysics-P2-AfterController]
  - xxa / zza（输入是否被设置）
  - jumping
  - controller 完整状态字符串

P3 - super.tick() 后：
  🔍 [BotPhysics-P3-After]
  - 位置（是否改变）
  - 速度（是否更新）
  - onGround
  - moved（是否真的移动了）
```

### **BotController 探针**

```
C1 - onUpdate 开始前：
  🎮 [BotController-C1-Before]
  - forward / strafing（控制器输入）
  - jumping / jumpTicks
  - sneaking / sprinting

C2 - onUpdate 设置后：
  🎮 [BotController-C2-After]
  - bot.xxa / bot.zza（是否正确写入 bot 字段）
  - bot.jumping
  - velocity（速度系数）

I1 - 输入变化时立即记录：
  ⌨️ [BotController-I1-Input] setForward
  - 旧值 -> 新值

I2 - 跳跃时立即记录：
  ⌨️ [BotController-I2-Input] jumpOnce

I3 - 停止时立即记录：
  ⌨️ [BotController-I3-Input] stopMovement
```

---

## 🧪 测试步骤

### **步骤 1：编译**

```bash
cd /home/fb486/projects/alice
./gradlew compileJava
```

### **步骤 2：启动服务器**

```bash
./gradlew runServer
```

### **步骤 3：生成 bot**

```
/alice spawn
```

**预期日志（每秒）**：
```
🔍 [BotPhysics-P1-Before] tick=20 pos=(100.50,64.00,200.50) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=0.00 jumping=false isEffectiveAi=true
🎮 [BotController-C1-Before] forward=0.00 strafing=0.00 jumping=false jumpTicks=0 sneaking=false sprinting=false
🎮 [BotController-C2-After] bot.xxa=0.00 bot.zza=0.00 bot.jumping=false velocity=1.00
🔍 [BotPhysics-P2-AfterController] xxa=0.00 zza=0.00 jumping=false controller=BotController[forward=0.00 strafing=0.00 sneaking=false sprinting=false jumping=false jumpTicks=0]
🔍 [BotPhysics-P3-After] tick=20 pos=(100.50,64.00,200.50) delta=(0.000,0.000,0.000) onGround=true moved=false
```

**关键检查**：
- ✅ `isEffectiveAi=true` → 必须是 true！
- ✅ bot 应该站在地面上（`onGround=true`）
- ✅ 如果 bot 生成在空中，应该会掉落（`delta.y` 变成负数）

---

### **步骤 4：测试前进**

添加测试命令（在 `AliceCommand.java` 里）：

```java
.then(Commands.literal("bot-test")
    .then(Commands.literal("forward")
        .executes(ctx -> {
            BotPlayer bot = BotManager.first();
            if (bot != null) {
                bot.controller().setForward(1.0F);
                ctx.getSource().sendSuccess(
                    () -> Component.literal("[alice] Bot 前进测试"), false);
            }
            return 1;
        })
    )
)
```

执行：
```
/alice bot-test forward
```

**预期日志**：
```
⌨️ [BotController-I1-Input] setForward: 0.00 -> 1.00 (clamped from 1.00)

（下一秒）
🔍 [BotPhysics-P1-Before] tick=40 pos=(100.50,64.00,200.50) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=1.00 jumping=false isEffectiveAi=true
🎮 [BotController-C1-Before] forward=1.00 strafing=0.00 jumping=false jumpTicks=0 sneaking=false sprinting=false
🎮 [BotController-C2-After] bot.xxa=0.00 bot.zza=1.00 bot.jumping=false velocity=1.00
🔍 [BotPhysics-P2-AfterController] xxa=0.00 zza=1.00 jumping=false controller=BotController[forward=1.00 ...]
🔍 [BotPhysics-P3-After] tick=40 pos=(100.50,64.00,200.60) delta=(0.000,0.000,0.100) onGround=true moved=true
                                                   ↑↑↑↑↑↑            ↑↑↑↑↑        ↑↑↑↑
                                                  Z 坐标改变      速度出现      移动了！
```

**关键检查**：
- ✅ `zza=1.00` → 输入被正确设置
- ✅ `delta=(0.000,0.000,0.100)` → 速度出现
- ✅ `pos` Z 坐标改变 → bot 移动了
- ✅ `moved=true` → 确认移动

---

## 🐛 问题诊断矩阵

### **问题 1：bot 不移动**

#### **症状 A：isEffectiveAi=false**

```
🔍 [BotPhysics-P1-Before] ... isEffectiveAi=false
                                               ↑↑↑↑↑
```

**原因**：BotPlayer 没有重写 `isEffectiveAi()`  
**修复**：确认 BotPlayer.java line 73 有：
```java
@Override
public boolean isEffectiveAi() {
    return true;
}
```

---

#### **症状 B：zza 没有被设置**

```
⌨️ [BotController-I1-Input] setForward: 0.00 -> 1.00  ← 输入被接收
🎮 [BotController-C2-After] bot.zza=0.00              ← 但 zza 还是 0！
```

**原因**：controller.onUpdate() 没有被调用  
**修复**：确认 BotPlayer.tick() line 80 有：
```java
controller.onUpdate();
```

---

#### **症状 C：zza 被设置但 delta 为 0**

```
🔍 [BotPhysics-P2-AfterController] xxa=0.00 zza=1.00  ← 输入正确
🔍 [BotPhysics-P3-After] delta=(0.000,0.000,0.000)    ← 但速度为 0
```

**原因**：travel() 没有被执行  
**可能性**：
1. `isEffectiveAi()` 仍然返回 false
2. `super.tick()` 没有被调用
3. bot 被卡在某个状态（比如 spectator 模式）

**检查**：
```java
// 在 BotPlayer.tick() 里加调试
BotLog.info("DEBUG: gameMode={} abilities.flying={}", 
    this.gameMode.getGameModeForPlayer(), 
    this.getAbilities().flying);
```

---

#### **症状 D：delta 出现但位置不变**

```
🔍 [BotPhysics-P3-After] delta=(0.000,0.000,0.100) moved=false
                                          ↑↑↑↑     ↑↑↑↑↑
                                        速度有      没移动
```

**原因**：位置被某处重置  
**检查**：是否有其他任务在调用 `bot.setPos()` 或 `bot.teleport()`

---

### **问题 2：bot 会移动但速度不对**

#### **症状 A：速度太慢**

```
🔍 [BotPhysics-P3-After] delta=(0.000,0.000,0.030)  ← 应该是 0.100
```

**原因**：潜行模式被意外开启  
**检查**：
```
🎮 [BotController-C2-After] velocity=0.30  ← 速度系数是 0.3（潜行）
```

---

#### **症状 B：速度太快**

```
🔍 [BotPhysics-P3-After] delta=(0.000,0.000,0.200)  ← 应该是 0.100
```

**原因**：travel() 被调用两次  
**检查**：搜索 `.travel(` 是否有手动调用

---

### **问题 3：bot 悬浮在空中**

```
🔍 [BotPhysics-P1-Before] pos=(100.50,65.00,200.50) onGround=false
🔍 [BotPhysics-P3-After] pos=(100.50,65.00,200.50) delta=(0.000,0.000,0.000)
                                  ↑↑↑↑ Y 没变              ↑↑↑ 没有重力
```

**原因**：`isEffectiveAi()=false` 或 travel() 没执行  
**修复**：同问题 1

---

## 📊 正常日志示例

### **bot 生成后静止**

```
🔍 [BotPhysics-P1-Before] tick=20 pos=(100.50,64.00,200.50) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=0.00 jumping=false isEffectiveAi=true
🎮 [BotController-C1-Before] forward=0.00 strafing=0.00 jumping=false jumpTicks=0 sneaking=false sprinting=false
🎮 [BotController-C2-After] bot.xxa=0.00 bot.zza=0.00 bot.jumping=false velocity=1.00
🔍 [BotPhysics-P2-AfterController] xxa=0.00 zza=0.00 jumping=false controller=BotController[forward=0.00 strafing=0.00 sneaking=false sprinting=false jumping=false jumpTicks=0]
🔍 [BotPhysics-P3-After] tick=20 pos=(100.50,64.00,200.50) delta=(0.000,0.000,0.000) onGround=true moved=false
```

**关键点**：
- ✅ `isEffectiveAi=true`
- ✅ `onGround=true`（在地面）
- ✅ 无输入，无移动

---

### **bot 前进中**

```
⌨️ [BotController-I1-Input] setForward: 0.00 -> 1.00 (clamped from 1.00)

🔍 [BotPhysics-P1-Before] tick=40 pos=(100.50,64.00,200.50) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=1.00 jumping=false isEffectiveAi=true
🎮 [BotController-C1-Before] forward=1.00 strafing=0.00 jumping=false jumpTicks=0 sneaking=false sprinting=false
🎮 [BotController-C2-After] bot.xxa=0.00 bot.zza=1.00 bot.jumping=false velocity=1.00
🔍 [BotPhysics-P2-AfterController] xxa=0.00 zza=1.00 jumping=false controller=BotController[forward=1.00 strafing=0.00 sneaking=false sprinting=false jumping=false jumpTicks=0]
🔍 [BotPhysics-P3-After] tick=40 pos=(100.50,64.00,200.60) delta=(0.000,0.000,0.100) onGround=true moved=true

（下一秒）
🔍 [BotPhysics-P1-Before] tick=60 pos=(100.50,64.00,202.60) delta=(0.000,0.000,0.100) onGround=true xxa=0.00 zza=1.00 jumping=false isEffectiveAi=true
🔍 [BotPhysics-P3-After] tick=60 pos=(100.50,64.00,204.60) delta=(0.000,0.000,0.100) onGround=true moved=true
                                              ↑↑↑↑↑↑
                                          每秒移动约 2 格（0.1 * 20 ticks）
```

**关键点**：
- ✅ `zza=1.00`（输入持续）
- ✅ `delta.z=0.100`（速度正常）
- ✅ Z 坐标持续增加（移动中）

---

### **bot 跳跃**

```
⌨️ [BotController-I2-Input] jumpOnce: jumpTicks set to 2, bot=Alice

🔍 [BotPhysics-P1-Before] tick=80 pos=(100.50,64.00,210.50) delta=(0.000,0.000,0.100) onGround=true xxa=0.00 zza=1.00 jumping=true isEffectiveAi=true
                                                                                                                            ↑↑↑↑
🔍 [BotPhysics-P3-After] tick=80 pos=(100.50,64.42,210.60) delta=(0.000,0.420,0.100) onGround=false moved=true
                                           ↑↑↑↑            ↑↑↑↑↑                     ↑↑↑↑↑
                                         Y 增加         跳跃初速度 0.42              离地

（下一 tick）
🔍 [BotPhysics-P3-After] tick=81 pos=(100.50,64.76,210.70) delta=(0.000,0.340,0.100) onGround=false moved=true
                                           ↑↑↑↑            ↑↑↑↑↑
                                       继续上升        重力开始作用（0.42 - 0.08）

（几 tick 后落地）
🔍 [BotPhysics-P3-After] tick=95 pos=(100.50,64.00,212.50) delta=(0.000,0.000,0.100) onGround=true moved=true
                                           ↑↑↑↑                     ↑↑↑  ↑↑↑↑↑
                                        回到 Y=64               速度清零  落地
```

---

## 🎯 快速检查清单

运行测试后，按顺序检查日志：

- [ ] `isEffectiveAi=true`（如果是 false，travel 不会执行）
- [ ] `setForward` 被调用（`I1-Input` 日志出现）
- [ ] `bot.zza` 被设置（`C2-After` 显示 zza=1.00）
- [ ] `delta` 出现速度（`P3-After` 显示 delta.z > 0）
- [ ] 位置改变（`P3-After` 的 pos 和 `P1-Before` 不同）
- [ ] `moved=true`（确认移动）

如果以上任何一项失败，对照问题诊断矩阵查找原因。

---

## 🐋 下一步

1. **编译并启动服务器**
2. **生成 bot**：`/alice spawn`
3. **观察日志**：检查是否有探针输出
4. **测试前进**：添加测试命令或物品
5. **把日志贴给我**：我帮你分析问题

需要我帮你添加测试命令吗？🐋💙
