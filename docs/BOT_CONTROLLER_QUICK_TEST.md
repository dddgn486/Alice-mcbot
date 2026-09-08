# BotController 快速测试指南

> 已添加聊天栏诊断，所有关键信息会直接显示在游戏里

---

## 🎮 测试步骤

### **1. 编译**

```bash
cd /home/fb486/projects/alice
./gradlew compileJava
```

### **2. 启动服务器**

```bash
./gradlew runServer
```

### **3. 进入游戏并生成 bot**

```
/alice spawn test_bot
```

**预期**：
- bot 生成在你的位置
- 聊天栏每秒显示一次调试信息：
  ```
  [BotDebug] tick=20 pos=(100.0,64.0,200.0) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=0.00 jumping=false isEffAi=true
  ```

**关键检查**：
- ✅ `isEffAi=true` → 必须是 true！
- ✅ `onGround=true` → bot 在地面上
- ✅ 如果 bot 生成在空中，应该会掉落（delta.y 变负数）

---

### **4. 测试前进**

```
/alice bot-control forward
```

**预期聊天栏消息**：
```
[alice] Bot 前进
[Input] setForward: 0.00 -> 1.00
```

**然后观察每秒的调试信息**：
```
[BotDebug] tick=40 pos=(100.0,64.0,200.0) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=1.00 jumping=false isEffAi=true
[BotDebug] tick=60 pos=(100.0,64.0,200.1) delta=(0.000,0.000,0.100) onGround=true xxa=0.00 zza=1.00 jumping=false isEffAi=true
                                  ↑↑↑↑            ↑↑↑↑↑
                               Z 坐标变了      速度出现了！
[BotDebug] tick=80 pos=(100.0,64.0,202.1) delta=(0.000,0.000,0.100) onGround=true xxa=0.00 zza=1.00 jumping=false isEffAi=true
                                  ↑↑↑↑
                              继续移动！
```

**成功标志**：
- ✅ `zza=1.00` → 输入被设置
- ✅ `delta.z=0.100` → 速度出现
- ✅ Z 坐标持续增加 → bot 在移动！

---

### **5. 测试跳跃**

```
/alice bot-control jump
```

**预期聊天栏消息**：
```
[alice] Bot 跳跃
[Input] jumpOnce triggered
```

**观察调试信息**：
```
[BotDebug] tick=100 pos=(100.0,64.0,204.0) delta=(0.000,0.000,0.100) onGround=true xxa=0.00 zza=1.00 jumping=true isEffAi=true
                                                                                                              ↑↑↑↑
[BotDebug] tick=101 pos=(100.0,64.4,204.1) delta=(0.000,0.420,0.100) onGround=false xxa=0.00 zza=1.00 jumping=true isEffAi=true
                                  ↑↑↑↑            ↑↑↑↑↑              ↑↑↑↑↑
                                Y 增加          跳跃初速度           离地了
[BotDebug] tick=102 pos=(100.0,64.7,204.2) delta=(0.000,0.340,0.100) onGround=false ...
                                  ↑↑↑↑            ↑↑↑↑↑
                              继续上升        重力开始作用
```

**成功标志**：
- ✅ `jumping=true` → 跳跃标志设置
- ✅ Y 坐标增加 → bot 起跳了
- ✅ `delta.y=0.420` → 跳跃初速度正确
- ✅ 几 tick 后 `onGround=true` → 落地了

---

### **6. 测试停止**

```
/alice bot-control stop
```

**预期聊天栏消息**：
```
[alice] Bot 停止
[Input] stopMovement - all cleared
```

**观察调试信息**：
```
[BotDebug] tick=120 pos=(100.0,64.0,210.5) delta=(0.000,0.000,0.100) onGround=true xxa=0.00 zza=0.00 jumping=false isEffAi=true
                                                                                                     ↑↑↑↑
                                                                                                  输入清零
[BotDebug] tick=140 pos=(100.0,64.0,210.5) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=0.00 jumping=false isEffAi=true
                                  ↑↑↑↑↑            ↑↑↑↑↑
                              位置不变          速度清零
```

**成功标志**：
- ✅ `zza=0.00` → 输入清零
- ✅ `delta=(0,0,0)` → 速度清零
- ✅ 位置不再变化 → bot 停止了

---

## 🐛 问题排查

### **问题 1：bot 生成后悬浮在空中不掉落**

**调试信息**：
```
[BotDebug] tick=20 pos=(100.0,70.0,200.0) delta=(0.000,0.000,0.000) onGround=false ...
                                  ↑↑↑↑↑            ↑↑↑↑↑              ↑↑↑↑↑
                              在空中 Y=70        没有速度          不在地面
```

**原因**：`isEffectiveAi()` 没有返回 true，或者 `super.tick()` 没被调用

**修复**：
1. 检查 `BotPlayer.java` line 73 是否有：
   ```java
   @Override
   public boolean isEffectiveAi() {
       return true;
   }
   ```
2. 检查 `BotPlayer.tick()` 是否调用了 `super.tick()`

---

### **问题 2：输入被设置但 bot 不动**

**调试信息**：
```
[Input] setForward: 0.00 -> 1.00  ← 输入收到
[BotDebug] ... zza=1.00 ... delta=(0.000,0.000,0.000) ...
                 ↑↑↑↑                    ↑↑↑↑↑
               输入正确                 但速度为 0
```

**原因**：`isEffectiveAi()` 返回 false，导致 `travel()` 直接返回

**检查聊天栏**：
```
[BotDebug] ... isEffAi=false  ← 这里应该是 true！
                       ↑↑↑↑↑
```

**修复**：确保 `BotPlayer.isEffectiveAi()` 返回 `true`

---

### **问题 3：没有调试信息显示**

**可能原因**：
1. 编译失败（检查 `./gradlew compileJava` 是否成功）
2. BotPlayer 没有被正确替换（检查是否有编译错误）

**检查服务器日志**：
```bash
tail -f run/logs/latest.log
```

看是否有报错。

---

## ✅ 成功标准

如果以下全部通过，说明 BotController 工作正常：

- [ ] bot 生成后落到地面（`onGround=true`）
- [ ] `/alice bot-control forward` → bot 前进
- [ ] 调试信息显示 `zza=1.00` 和 `delta.z>0`
- [ ] `/alice bot-control jump` → bot 起跳
- [ ] Y 坐标增加，`delta.y=0.420`
- [ ] `/alice bot-control stop` → bot 停止
- [ ] `zza=0.00` 和 `delta=(0,0,0)`

---

## 🎯 测试完成后

如果测试通过，把调试信息发给我，格式如下：

```
✅ 生成测试：
[BotDebug] tick=20 pos=(...) delta=(...) onGround=true isEffAi=true

✅ 前进测试：
[Input] setForward: 0.00 -> 1.00
[BotDebug] tick=60 pos=(...) delta=(0.000,0.000,0.100) zza=1.00

✅ 跳跃测试：
[Input] jumpOnce triggered
[BotDebug] tick=100 pos=(100.0,64.4,...) delta=(0.000,0.420,...) jumping=true

✅ 停止测试：
[Input] stopMovement - all cleared
[BotDebug] tick=140 pos=(...) delta=(0.000,0.000,0.000) zza=0.00
```

如果有问题，把失败的调试信息发给我，我帮你分析！🐋💙

---

## 🔧 调试信息说明

### **调试信息格式**

```
[BotDebug] tick=20 pos=(100.0,64.0,200.0) delta=(0.000,0.000,0.000) onGround=true xxa=0.00 zza=0.00 jumping=false isEffAi=true
           ↑       ↑                       ↑                         ↑             ↑        ↑        ↑             ↑
           tick数  位置(X,Y,Z)              速度向量(X,Y,Z)            是否在地面    左右输入  前后输入  跳跃标志      AI物理开关
```

### **关键字段解释**

- **tick**：服务器 tick 计数，每秒增加 20
- **pos**：bot 的世界坐标 (X, Y, Z)
- **delta**：bot 的速度向量（格/tick）
  - `delta.y < 0` → 下落
  - `delta.y = 0.42` → 跳跃
  - `delta.z > 0` → 向南移动
- **onGround**：bot 是否在地面上
- **xxa**：左右输入（-1.0 到 1.0）
- **zza**：前后输入（-1.0 到 1.0）
- **jumping**：跳跃标志
- **isEffAi**：**最关键**！必须是 `true`

---

## 🚀 下一步

测试通过后可以：
1. 添加更多控制命令（后退、左右移动、疾跑等）
2. 实现网络包系统（实时按键控制）
3. 集成到 Task 系统（AI 控制 bot）

需要我帮你做哪一步？🐋💙
