# Bot 手动控制系统设计方案

> 需求：创建控制器物品，右键进入控制模式，用玩家按键（WASD/空格）直接控制 bot

---

## 🎯 你的方案分析

### **你计划的方案（自定义网络包）**

```
客户端：
  玩家按 W 键
    ↓
  监听 KeyEvent
    ↓
  创建 InputPacket(forward=1.0)
    ↓
  发送到服务端

服务端：
  接收 InputPacket
    ↓
  bot.controller().setForward(1.0)
    ↓
  bot 移动
```

### **优点**
- ✅ 完全自定义，灵活
- ✅ 可以精确控制发包时机
- ✅ 可以添加额外信息（如控制模式、目标 bot ID）

### **缺点**
- ❌ 需要自己实现网络包注册、序列化
- ❌ 客户端按键监听比较繁琐（KeyEvent 处理）
- ❌ 需要处理玩家退出控制模式时的清理
- ❌ 需要同步控制状态（谁在控制哪个 bot）
- ❌ 玩家自己的输入仍然生效（需要额外屏蔽）

---

## 💡 我的建议方案

### **方案 A：命令 + 手动输入（最简单，推荐测试用）**

不需要网络包，直接用命令模拟输入：

```java
// AliceCommand.java
.then(Commands.literal("bot-control")
    .then(Commands.literal("forward")
        .executes(ctx -> {
            BotPlayer bot = BotManager.first();
            if (bot != null) {
                bot.controller().setForward(1.0F);
            }
            return 1;
        })
    )
    .then(Commands.literal("stop")
        .executes(ctx -> {
            BotPlayer bot = BotManager.first();
            if (bot != null) {
                bot.controller().stopMovement();
            }
            return 1;
        })
    )
)
```

**优点**：5 分钟实现，无需网络包  
**缺点**：不是实时控制  
**适用场景**：测试 BotController 是否工作

---

### **方案 B：Forge 网络包（推荐实现）**

完整的实时控制系统，包含网络包、客户端监听、状态管理。

详细代码见下文。

---

## 📊 方案对比

| 方案 | 实现复杂度 | 测试效果 | 实时性 | 扩展性 |
|-----|----------|---------|--------|--------|
| **A. 命令** | ⭐ 极简 | ⭐⭐ 基础 | ❌ 无 | ❌ 无 |
| **B. 网络包** | ⭐⭐⭐ 中等 | ⭐⭐⭐⭐⭐ 完整 | ✅ 有 | ✅ 高 |

---

## 💡 我的建议

### **阶段 1：先用方案 A 测试 BotController（5 分钟）**
验证 BotController 是否正常工作

### **阶段 2：如果测试通过，再实现方案 B（2-3 小时）**
完整的实时控制系统

---

需要完整代码请告诉我！🐋💙
