# Windows 端同步清单

> 基准：Windows 端  
> 本次修改：添加 BotController 诊断探针（聊天栏显示）

---

## 📦 需要同步的文件

### **1. BotPlayer.java（已修改）**

**文件路径**：`src/main/java/com/dddgn/alice/bot/BotPlayer.java`

**修改内容**：
1. 添加了 `Component` 导入
2. 添加了每秒一次的聊天栏诊断输出
3. 已经有 `isEffectiveAi()` 重写（返回 true）✅
4. 已经调用了 `controller.onUpdate()` 和 `super.tick()` ✅

**关键代码**：
```java
// 导入部分（line 1-8）
package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;  // ← 新增
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;  // ← 新增

// tick() 方法（line 80-106）
@Override
public void tick() {
    // ========== 简化诊断：每秒发送一次到聊天栏 ==========
    if (this.tickCount % 20 == 0) {
        Vec3 pos = this.position();
        Vec3 delta = this.getDeltaMovement();
        
        // 发送到所有玩家的聊天栏
        this.server.getPlayerList().getPlayers().forEach(player -> {
            if (!(player instanceof BotPlayer)) {
                player.sendSystemMessage(Component.literal(String.format(
                    "§e[BotDebug] §ftick=%d pos=(%.1f,%.1f,%.1f) delta=(%.3f,%.3f,%.3f) onGround=%s xxa=%.2f zza=%.2f jumping=%s isEffAi=%s",
                    this.tickCount,
                    pos.x, pos.y, pos.z,
                    delta.x, delta.y, delta.z,
                    this.onGround(),
                    this.xxa, this.zza,
                    this.jumping,
                    this.isEffectiveAi()
                )));
            }
        });
    }
    
    // ✅ 关键：在 super.tick() 之前设置输入
    controller.onUpdate();
    
    // super.tick() 会读取输入字段并调用 travel()
    super.tick();
}
```

---

### **2. BotController.java（新文件，你已创建）**

**文件路径**：`src/main/java/com/dddgn/alice/bot/BotController.java`

**需要修改的地方**：

#### **修改 1：添加 notifyPlayers 工具方法（文件末尾）**

在 `stopMovement()` 方法后面添加：

```java
// ==================== 工具方法 ====================

/**
 * 发送消息到所有在线玩家的聊天栏（不包括 bot）。
 */
private void notifyPlayers(String message) {
    bot.getServer().getPlayerList().getPlayers().forEach(player -> {
        if (!(player instanceof BotPlayer)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    });
}
```

#### **修改 2：修改 setForward() 方法**

```java
/**
 * 设置前后移动输入。
 * 
 * @param value [-1.0, 1.0]，正值向前，负值向后
 */
public void setForward(float value) {
    float oldValue = this.forward;
    this.forward = clampInput(value);
    
    // 输入变化时通知到聊天栏
    if (oldValue != this.forward) {
        notifyPlayers(String.format("§a[Input] §fsetForward: %.2f -> %.2f", oldValue, this.forward));
    }
}
```

#### **修改 3：修改 jumpOnce() 方法**

```java
/**
 * 单次跳跃（拟人化，推荐）。
 */
public void jumpOnce() {
    this.jumpTicks = 2;
    notifyPlayers("§a[Input] §fjumpOnce triggered");
}
```

#### **修改 4：修改 stopMovement() 方法**

```java
/**
 * 停止所有移动。
 */
public void stopMovement() {
    this.forward = 0.0F;
    this.strafing = 0.0F;
    this.jumping = false;
    this.jumpTicks = 0;
    this.sneaking = false;
    this.sprinting = false;
    
    bot.setJumping(false);
    bot.setShiftKeyDown(false);
    bot.setSprinting(false);
    
    notifyPlayers("§a[Input] §fstopMovement - all cleared");
}
```

---

### **3. BotCommand.java（已有，无需修改）**

你已经有这些测试命令了：
```java
/alice bot-control forward
/alice bot-control jump
/alice bot-control stop
```

✅ 无需修改

---

## 🔧 Windows 端同步步骤

### **方式 A：手动复制粘贴（推荐）**

1. **打开 Windows 端的 BotPlayer.java**
   - 路径：`D:\JAVA_projects\alice\src\main\java\com\dddgn\alice\bot\BotPlayer.java`
   
2. **检查并修改 import 部分**（文件开头）
   ```java
   import net.minecraft.network.chat.Component;  // 确保有这行
   import net.minecraft.world.phys.Vec3;         // 确保有这行
   ```

3. **替换 tick() 方法**（line 80 左右）
   - 用上面的新版本替换整个 `tick()` 方法

4. **检查 isEffectiveAi() 方法**（line 73 左右）
   ```java
   @Override
   public boolean isEffectiveAi() {
       return true;  // 确保返回 true
   }
   ```

5. **打开 Windows 端的 BotController.java**
   - 你已经创建了这个文件
   
6. **在文件末尾添加 notifyPlayers() 方法**
   - 在 `stopMovement()` 后面、最后的 `}` 之前
   
7. **修改 setForward()、jumpOnce()、stopMovement()**
   - 用上面的新版本替换

---

### **方式 B：从 WSL 复制文件（如果你想直接覆盖）**

```bash
# 在 WSL 里执行
cp /home/fb486/projects/alice/src/main/java/com/dddgn/alice/bot/BotPlayer.java /mnt/d/JAVA_projects/alice/src/main/java/com/dddgn/alice/bot/
cp /home/fb486/projects/alice/src/main/java/com/dddgn/alice/bot/BotController.java /mnt/d/JAVA_projects/alice/src/main/java/com/dddgn/alice/bot/
```

⚠️ **注意**：这会直接覆盖 Windows 端的文件！建议先备份。

---

## ✅ 同步完成后的验证

### **在 Windows 端编译**

```bash
# PowerShell 或 CMD
cd D:\JAVA_projects\alice
.\gradlew compileJava
```

应该编译成功，无报错。

### **启动测试**

```bash
.\gradlew runServer
```

进入游戏后：
```
/alice spawn test_bot
```

应该在聊天栏看到每秒的调试信息：
```
[BotDebug] tick=20 pos=(...) delta=(...) onGround=true isEffAi=true
```

---

## 📊 关键检查点

同步完成后，确认以下几点：

- [ ] BotPlayer.java 有 `import net.minecraft.network.chat.Component;`
- [ ] BotPlayer.java 有 `import net.minecraft.world.phys.Vec3;`
- [ ] BotPlayer.isEffectiveAi() 返回 `true`
- [ ] BotPlayer.tick() 里有聊天栏诊断代码
- [ ] BotController 有 `notifyPlayers()` 方法
- [ ] BotController.setForward() 调用了 `notifyPlayers()`
- [ ] Windows 端编译成功

---

## 🎯 测试命令

同步并编译成功后，在游戏里测试：

```
/alice spawn test_bot       # 生成 bot，看聊天栏调试信息
/alice bot-control forward  # bot 前进，看 delta.z 和 pos
/alice bot-control jump     # bot 跳跃，看 Y 和 delta.y
/alice bot-control stop     # bot 停止，看 delta 变 0
```

---

## 🐋 需要帮助吗？

如果同步过程中有任何问题：
1. 编译报错 → 把错误信息发给我
2. 不确定改哪里 → 把你的文件发给我，我帮你对比
3. 测试失败 → 把聊天栏的调试信息发给我

我会帮你排查！🐋💙
