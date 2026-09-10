# Alice 项目移动系统架构与职责边界

> 历史/过渡架构说明。本文保留现有移动实验与 Legacy 边界的事实记录；新的总体架构以 `docs/ALICE_PATHING_CORE_ARCHITECTURE.md` 和 `docs/ALICE_PATHING_CORE_R1_CONTRACT.md` 为准。本文不授权把 Movement 接入 MineTask。
>
> 当前稳定边界：普通挖矿和拾取使用 `HARD_PATH`；`SOFT_SURFACE` 保持独立实验；`SEARCH_LIMIT` 不等于 `UNREACHABLE`，不能自动授权挖隧道。

## 🧭 当前推荐职责分层

```text
Decision（未来，暂不实现）
    ↓ 选择目标、策略和已注册工具
Action / Task（当前行为包）
    ↓ 行为阶段、生命周期、领域失败恢复和后置条件
Domain Planner（挖掘等领域规划）
    ↓ 目标方块 → 合法站位 / 领域约束
Path Planner（通用路线规划）
    ↓ 脚位 → 脚位的 Movement 序列
Movement Provider / Movement（移动原语）
    ↓ 行走、跳跃、下降、挖后走、搭建等具体动作
Movement Executor（PathExecutor）
    ↓ 执行序列、重验动作契约、报告动态阻挡
Minecraft 服务端世界与物理
```

### 职责边界

| 能力 | 负责层 | 说明 |
|---|---|---|
| 选择“做什么” | Decision（未来）/当前上层入口 | 不逐 tick 控制物理 |
| 行为阶段、取消、超时、失败恢复 | Task / Action | `MineTask` 是行为编排者，不是决策层 |
| 目标方块到挖掘站位 | Mining domain planner | 负责范围、支撑、视线和挖掘后置条件 |
| 脚位到脚位的路线 | Path Planner | 搜索路线，不替领域任务决定挖什么 |
| 某种移动如何发生 | Movement | 每个原语定义自己的前置/执行/后置条件 |
| Movement 序列执行 | PathExecutor | 不用单一通行检查误伤专用 Movement |
| 世界、权限、安全和真实完成条件 | 服务端确定性代码 | 服务端是唯一真相 |

## 🧹 “清障”不是单一职责

### 目标访问清障

Bot 已到达候选站位，但视线到挖掘目标被树叶或其他方块遮挡。它服务于“能否挖到目标”，属于挖掘领域；应在重新选站位与局部清障之间按领域规则决策。当前 `MineTask` 的清障逻辑不能未经验证直接删除。

### 路径清障

Bot 需要从脚位 A 到脚位 B，而路线中存在障碍。它属于 `PathPlanner + Movement`：规划器比较绕路、挖后走、隧道、搭台阶等候选，Movement 执行具体动作。

### 动态阻挡

路径生成后环境发生变化，或当前 Movement 的执行前置条件失效。Movement/Executor 应报告 `BLOCKED` 或稳定失败码；上层行为决定是否重新规划，不能把它伪装成 `UNREACHABLE`，也不能无限重试。

## 🧱 Movement 原语分类

```text
纯移动：Walk / Jump / Descend / Swim / Climb
世界修改移动：BreakAndWalk / Tunnel / Pillar / Bridge
```

世界修改 Movement 必须额外具备权限、工具/材料、安全检查、前置条件、后置条件和失败回收；通过独立测试闭环前，不得接入普通正式任务。

## 🔎 当前搜索器语义

`AStarPathfinder` 的类名来自早期 A* 框架，但当前搜索使用零 heuristic，实际是 Dijkstra。原因是当前直行和对角移动成本均为 1，未经证明的曼哈顿 heuristic 会高估剩余成本。时间预算只限制搜索规模，不会把实现变成 A*。只有成本模型和可接受 heuristic 明确后，才重新评估真正的 A* 或显式改名。

## 📊 现有入口关系（参考）

```
┌────────────────────────────────────────────────────────────────┐
│                       【测试物品层】                              │
│  玩家右键触发 → 派发任务到 BotManager                              │
├────────────────────────────────────────────────────────────────┤
│  • target_selector (钻石斧)    → MineTask/PlaceTask            │
│  • soft_move_selector (金斧)  → SoftMoveProbeTask              │
│  • soft_path_probe_selector   → SoftPathProbeTask              │
│  • road_planner (钻石锄)       → RoadBuildTask                 │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│                      【任务编排层】                              │
│  Task.tick() → 调用下层移动方式 → 返回 Status                    │
├────────────────────────────────────────────────────────────────┤
│  • MineTask         → 使用 PathExecutor (HARD_PATH)            │
│  • SoftMoveProbeTask → 使用 SoftMovementPrimitive             │
│  • SoftPathProbeTask → 使用 SoftMovementPrimitive + 路径       │
│  • PlaceTask        → 使用 PathExecutor                        │
│  • RoadBuildTask    → 使用 SoftMovementPrimitive              │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│                   【底层移动原语层】                             │
│  直接操作 BotPlayer 的位置/输入 → 原版物理处理                    │
├────────────────────────────────────────────────────────────────┤
│  1. PathExecutor (HARD_PATH)                                   │
│     → bot.setPos() 直接设置坐标 (手动步进)                       │
│                                                                 │
│  2. SoftMovementPrimitive (SOFT_SURFACE)                       │
│     • Backend.SELF_MOVE:                                       │
│       → bot.move(MoverType.SELF, delta)                        │
│     • Backend.NATIVE_TRAVEL:                                   │
│       → bot.xxa/zza + bot.travel() ⚠️                          │
│                                                                 │
│  3. BotPlayer.tick()                                           │
│     → super.tick() → 原版 travel() 自动调用                     │
└────────────────────────────────────────────────────────────────┘
```

---

## 🎯 两种核心移动方式对比

### **方式 1：HARD_PATH (PathExecutor)**

```java
// 特点：手动控制位置，不依赖原版物理
public Status tick() {
    // 直接设置坐标
    bot.setPos(targetX, targetY, targetZ);
    bot.setOnGround(true);
    bot.fallDistance = 0.0F;
}
```

**使用场景**：
- ✅ `MineTask` - 挖矿时移动
- ✅ `PlaceTask` - 放置方块时移动
- ✅ 旧的实验代码

**优点**：
- 完全可控，不受物理引擎干扰
- 适合已知安全路径的快速移动

**缺点**：
- 绕过了原版物理，可能看起来不自然
- 需要手动处理 onGround / fallDistance

---

### **方式 2：SOFT_SURFACE (SoftMovementPrimitive)**

```java
// 特点：设置输入，让原版物理处理
public static Step applyToward(ServerPlayer bot, ..., Backend backend) {
    bot.setYRot(yaw);
    if (backend == Backend.NATIVE_TRAVEL) {
        // 设置输入
        bot.setJumping(false);
        bot.xxa = 0.0F;      // 左右
        bot.zza = 1.0F;      // 前进
        bot.travel(new Vec3(0.0D, 0.0D, 1.0D));  // ⚠️ 手动调用
    } else {
        // SELF_MOVE 模式
        bot.move(MoverType.SELF, step.delta());
    }
}
```

**使用场景**：
- ✅ `SoftMoveProbeTask` - 单段软移动实验
- ✅ `SoftPathProbeTask` - 多段软路径实验
- ✅ `RoadBuildTask` - 道路建造

**优点**：
- 使用原版物理，移动看起来自然
- 自动处理重力、摩擦、碰撞

**缺点**：
- ⚠️ **当前实现有问题：手动调用了 `travel()`**

---

## ⚠️ 当前问题：重复调用 `travel()`

### **问题根源**

```java
// SoftMovementPrimitive.java (line 48-56)
if (backend == Backend.NATIVE_TRAVEL) {
    bot.xxa = 0.0F;
    bot.zza = 1.0F;
    bot.travel(new Vec3(0.0D, 0.0D, 1.0D));  // ← 第一次调用
}

// BotPlayer.java (line 29-36)
@Override
public void tick() {
    super.tick();  // ← 第二次调用（内部会调用 travel()）
}
```

**结果**：每 tick 调用两次 `travel()`！

### **mc_aiplayer 的正确做法**

```java
// ActionPack.java (mc_aiplayer)
public void onUpdate() {
    player.forwardSpeed = forward * velocity;
    player.sidewaysSpeed = strafing * velocity;
    player.setJumping(jumping);
    // 不调用 travel()！让 super.tick() 自动处理
}

// AIPlayerEntity.java (mc_aiplayer)
@Override
public void tick() {
    super.tick();  // ← 唯一一次，自动读取 forwardSpeed/sidewaysSpeed
    this.actionPack.onUpdate();
}
```

---

## 🔧 修复方案

### **需要改动的文件**

#### **1. SoftMovementPrimitive.java**

```java
// 修改前（line 48-56）
if (backend == Backend.NATIVE_TRAVEL) {
    bot.setJumping(false);
    bot.xxa = 0.0F;
    bot.zza = 1.0F;
    bot.travel(new Vec3(0.0D, 0.0D, 1.0D));  // ← 删除这行！
}

// 修改后
if (backend == Backend.NATIVE_TRAVEL) {
    bot.setJumping(false);
    bot.xxa = 0.0F;
    bot.zza = 1.0F;
    // 不调用 travel()，让 super.tick() 自动处理
}

// 同样修改 applyJumpToward (line 71-74)
bot.setJumping(true);
bot.xxa = 0.0F;
bot.zza = 1.0F;
// 删除：bot.travel(new Vec3(0.0D, 0.0D, 1.0D));

// 同样修改 settle (line 83-85)
bot.xxa = 0.0F;
bot.zza = 0.0F;
// 删除：bot.travel(Vec3.ZERO);
```

#### **2. 问题：谁调用 `super.tick()`？**

**当前流程**：

```
每个服务器 tick:
  → ServerLevel.tick()
    → 遍历所有实体（包括 BotPlayer）
      → BotPlayer.tick()
        → super.tick()  ← 这里自动调用 travel()
```

**但是**，你的任务是在哪里调用的？让我找找：

```java
// BotManager.Session.tick()
// 任务在这里 tick，但 BotPlayer.tick() 是服务器自动调用的
```

**所以不需要改 BotPlayer.java**！只需要删除 `SoftMovementPrimitive` 里的手动 `travel()` 调用。

---

## 📋 完整修复步骤

### **Step 1: 删除手动 `travel()` 调用（3 处）**

1. `SoftMovementPrimitive.applyToward()` - line 53
2. `SoftMovementPrimitive.applyJumpToward()` - line 74
3. `SoftMovementPrimitive.settle()` - line 85

### **Step 2: 验证修复**

运行你的 `SoftMoveProbeTask` 和 `SoftPathProbeTask`，看看：
1. bot 是否还能正常移动
2. 物理是否更准确（不会因为两次 travel 导致速度加倍）

### **Step 3（可选）：统一输入管理**

创建一个 `BotController` 类（参考 mc_aiplayer 的 `ActionPack`）：

```java
public class BotController {
    private final BotPlayer bot;
    private float forward = 0.0F;
    private float strafe = 0.0F;
    private boolean jumping = false;
    
    public void tick() {
        // 每 tick 更新输入
        bot.xxa = strafe;
        bot.zza = forward;
        bot.setJumping(jumping);
    }
    
    public void setForward(float value) { this.forward = value; }
    public void setStrafe(float value) { this.strafe = value; }
    public void setJumping(boolean value) { this.jumping = value; }
    public void reset() { forward = 0; strafe = 0; jumping = false; }
}

// 在 BotPlayer.tick() 里调用
@Override
public void tick() {
    if (controller != null) {
        controller.tick();  // 设置输入
    }
    super.tick();  // 原版物理处理
}
```

---

## 🎨 调用链示例

### **示例 1：soft_move_selector (金斧) → SoftMoveProbeTask**

```
1. 玩家右键方块 (SoftMoveSelector.useOn)
   ↓
2. BotManager.assignSoftMoveProbe(bot, target, backend)
   ↓
3. Session.assignSoftMoveProbe() → 创建 SoftMoveProbeTask
   ↓
4. 每个服务器 tick:
   Session.tick() → SoftMoveProbeTask.tick()
   ↓
5. SoftMoveProbeTask.tick() 调用:
   SoftMovementPrimitive.applyToward(bot, x, z, dist, backend)
   ↓
6. SoftMovementPrimitive.applyToward():
   bot.xxa = 0.0F;
   bot.zza = 1.0F;
   // 修复前：bot.travel() ← 手动调用（错误）
   // 修复后：什么都不做 ← 让 super.tick() 自动处理
   ↓
7. 同一个 tick 稍后:
   ServerLevel.tick() → BotPlayer.tick() → super.tick() → travel()
   ↓ （自动读取 xxa/zza）
8. 原版物理处理完毕，bot 移动
```

### **示例 2：target_selector (钻石斧) → MineTask**

```
1. 玩家右键方块 (TargetSelector.useOn)
   ↓
2. BotManager.assignTarget(bot, target)
   ↓
3. 创建 MineTask → 内部使用 BotMiner
   ↓
4. BotMiner.tick() → PathExecutor.tick()
   ↓
5. PathExecutor.tick():
   bot.setPos(x, y, z);  // ← 直接设置坐标（HARD_PATH）
   bot.setOnGround(true);
   ↓
6. 不依赖 travel()，完全手动控制
```

---

## 📊 MovementMode 枚举

```java
public enum MovementMode {
    HARD_PATH,      // PathExecutor - 手动 setPos
    SOFT_SURFACE    // SoftMovementPrimitive - 原版 travel
}
```

---

## 🐋 总结

你的 Alice 项目**已经有了很好的架构**！

**问题只有一个**：
- `SoftMovementPrimitive.Backend.NATIVE_TRAVEL` 模式下，手动调用了 `travel()`
- 应该只设置 `xxa/zza`，让 `super.tick()` 自动调用 `travel()`

**修复非常简单**：
- 删除 3 行 `bot.travel()` 调用
- 不需要重构任何架构

**工作量**：
- 改动：3 行代码
- 测试：运行金斧测试物品
- 时间：< 10 分钟

唔姆～现在你明白项目的移动系统了吗？需要我帮你直接修复这 3 处吗？🐋💙
