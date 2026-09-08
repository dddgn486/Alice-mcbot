---
name: forge-event-priority-cancel
description: 理解 Forge 事件系统的优先级、取消机制、总线类型与事件传播顺序。
---
# Skill: Forge Event Priority & Cancel

## When to use this skill

Use this skill when:
- Event handler doesn't trigger or triggers at wrong time
- Need to cancel an event but it still happens
- Multiple mods conflict (event order matters)
- Understanding Mod bus vs Forge bus
- Need to intercept vanilla behavior

## Core Principle

**Forge 事件系统是分层的、有序的：**
1. **两条总线**：Mod 总线（注册）vs FORGE 总线（游戏逻辑）
2. **五个优先级**：HIGHEST → HIGH → NORMAL → LOW → LOWEST
3. **取消机制**：@Cancelable 事件可以被中断

**错误的总线 = 事件不触发；错误的优先级 = 冲突或失效。**

---

## Event Bus Types

### **1. Mod Event Bus（MOD 总线）**

```java
// 在 Mod 构造函数中注册
public AliceMod(IEventBus modEventBus) {
    // 注册物品、方块、实体类型等
    AliceItems.ITEMS.register(modEventBus);
    AliceBlocks.BLOCKS.register(modEventBus);
    
    // 监听 Mod 生命周期事件
    modEventBus.addListener(this::onCommonSetup);
}
```

**用途**：
- ✅ 注册阶段（FMLCommonSetupEvent、FMLClientSetupEvent）
- ✅ 数据生成（GatherDataEvent）
- ✅ 配置加载（ModConfigEvent）
- ❌ **不是**游戏逻辑事件（玩家移动、方块破坏等）

**常见 Mod 总线事件**：
- `FMLCommonSetupEvent` - 通用初始化
- `FMLClientSetupEvent` - 客户端初始化
- `RegisterEvent` - 注册对象
- `GatherDataEvent` - 数据生成

---

### **2. FORGE Event Bus（FORGE 总线）**

```java
// 在 Mod 构造函数中注册
public AliceMod(IEventBus modEventBus) {
    // 注册到 FORGE 总线
    MinecraftForge.EVENT_BUS.register(BotManager.class);
    MinecraftForge.EVENT_BUS.register(new MyEventHandler());
}
```

**用途**：
- ✅ 游戏逻辑事件（玩家交互、实体 tick、方块事件）
- ✅ 世界事件（区块加载、生物生成）
- ✅ 服务器事件（玩家登录、命令注册）

**常见 FORGE 总线事件**：
- `ServerTickEvent` - 服务端 tick
- `LivingEntityUseItemEvent` - 使用物品
- `PlayerInteractEvent` - 玩家交互
- `MovementInputUpdateEvent` - 移动输入
- `EntityJoinLevelEvent` - 实体进入世界

---

## Event Priority

### **优先级顺序**

```java
EventPriority.HIGHEST    // 最先执行
  ↓
EventPriority.HIGH
  ↓
EventPriority.NORMAL     // 默认
  ↓
EventPriority.LOW
  ↓
EventPriority.LOWEST     // 最后执行
```

### **使用场景**

```java
// 场景 1：拦截事件（在其他 mod 之前）
@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
    // 如果是控制器物品，取消默认行为
    if (event.getItemStack().is(AliceItems.BOT_CONTROLLER.get())) {
        event.setCanceled(true);  // 阻止后续处理
    }
}

// 场景 2：观察事件（在其他 mod 之后）
@SubscribeEvent(priority = EventPriority.LOWEST)
public void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
    // 记录所有交互（不干扰其他 mod）
    logger.info("Player interacted with " + event.getPos());
}

// 场景 3：正常处理（使用默认优先级）
@SubscribeEvent
public void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
    // 正常处理逻辑
}
```

---

## Event Cancellation

### **@Cancelable 注解**

```java
// 可取消的事件
@Cancelable
public class PlayerInteractEvent extends PlayerEvent {
    // ...
}

// 不可取消的事件
public class ServerTickEvent extends Event {
    // ...
}
```

**检查事件是否可取消**：
```java
@SubscribeEvent
public void onEvent(SomeEvent event) {
    if (event.isCancelable()) {
        event.setCanceled(true);  // ✅ 可以取消
    } else {
        // ❌ 不能取消，会抛出异常
    }
}
```

---

### **取消的效果**

```java
@SubscribeEvent(priority = EventPriority.HIGHEST)
public void cancelEvent(PlayerInteractEvent.RightClickBlock event) {
    event.setCanceled(true);
}

@SubscribeEvent(priority = EventPriority.LOW)
public void afterCancel(PlayerInteractEvent.RightClickBlock event) {
    if (event.isCanceled()) {
        // 这个 handler 仍然会被调用
        // 但可以检测到事件被取消了
        return;
    }
    // 正常处理
}
```

**关键**：
- ✅ 取消事件 **不会**阻止后续 handler 执行
- ✅ 取消事件 **会**阻止原版逻辑执行
- ✅ 后续 handler 可以检测 `isCanceled()` 并决定是否继续

---

## Event Phases

### **某些事件有多个阶段**

```java
// 例如：TickEvent 有 START 和 END 两个阶段
@SubscribeEvent
public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
        // Tick 开始时
    } else if (event.phase == TickEvent.Phase.END) {
        // Tick 结束时
    }
}
```

**常见有阶段的事件**：
- `TickEvent` - START / END
- `RenderLevelStageEvent` - 多个渲染阶段
- `ClientTickEvent` - START / END

---

## Common Pitfalls

### **Pitfall 1: 注册到错误的总线**

**症状**：
- 事件 handler 不触发
- 无错误日志

**根因**：
```java
// 错误：ServerTickEvent 注册到 Mod 总线
public AliceMod(IEventBus modEventBus) {
    modEventBus.register(MyHandler.class);  // ❌ 错误总线
}

// 正确：注册到 FORGE 总线
public AliceMod(IEventBus modEventBus) {
    MinecraftForge.EVENT_BUS.register(MyHandler.class);  // ✅
}
```

**记忆方法**：
- **Mod 总线** = 注册阶段、配置、数据生成
- **FORGE 总线** = 游戏逻辑、玩家交互、世界事件

---

### **Pitfall 2: 优先级冲突**

**症状**：
- 取消事件但仍然生效
- 其他 mod 的行为覆盖了你的逻辑

**根因**：
```java
// Mod A：取消事件
@SubscribeEvent(priority = EventPriority.NORMAL)
public void cancelInteract(PlayerInteractEvent event) {
    event.setCanceled(true);
}

// Mod B：强制执行（优先级更高）
@SubscribeEvent(priority = EventPriority.HIGHEST)
public void forceInteract(PlayerInteractEvent event) {
    event.setCanceled(false);  // 恢复事件
}
// Mod B 的 handler 先执行，导致 Mod A 的取消失效
```

**解决方案**：
- 使用更高的优先级（HIGHEST）
- 或者在 LOWEST 检查 `isCanceled()`

---

### **Pitfall 3: 取消不可取消的事件**

**症状**：
- 抛出异常：`UnsupportedOperationException`

**根因**：
```java
@SubscribeEvent
public void onTick(TickEvent.ServerTickEvent event) {
    event.setCanceled(true);  // ❌ TickEvent 不可取消
}
```

**修复**：
- 检查事件是否有 `@Cancelable` 注解
- 或者用 `if (event.isCancelable())` 检查

---

### **Pitfall 4: 忘记 @SubscribeEvent 注解**

**症状**：
- Handler 方法不触发
- 无错误

**根因**：
```java
public class MyHandler {
    // ❌ 忘记注解
    public void onTick(TickEvent.ServerTickEvent event) {
        // ...
    }
}
```

**修复**：
```java
public class MyHandler {
    @SubscribeEvent  // ✅ 必须有注解
    public void onTick(TickEvent.ServerTickEvent event) {
        // ...
    }
}
```

---

### **Pitfall 5: 静态 vs 实例注册混淆**

**症状**：
- 注册了但不触发

**根因**：
```java
// 方式 1：静态注册
@Mod.EventBusSubscriber(modid = AliceMod.MOD_ID)
public class MyHandler {
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {  // ← 必须是 static
        // ...
    }
}

// 方式 2：实例注册
public class MyHandler {
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {  // ← 不能是 static
        // ...
    }
}
// 在 Mod 构造函数中
MinecraftForge.EVENT_BUS.register(new MyHandler());
```

**混淆点**：
- `@Mod.EventBusSubscriber` 要求 **static** 方法
- `EVENT_BUS.register(instance)` 要求 **非 static** 方法

---

## Registration Patterns

### **Pattern 1: 静态注册（推荐用于简单 handler）**

```java
@Mod.EventBusSubscriber(modid = AliceMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BotEventHandler {
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        BotManager.tickAllBots();
    }
}
```

**优点**：
- ✅ 自动注册（不需要手动调用 register）
- ✅ 适合无状态的 handler

**缺点**：
- ❌ 不能访问实例字段
- ❌ 所有方法必须是 static

---

### **Pattern 2: 实例注册（推荐用于有状态的 handler）**

```java
public class BotControlHandler {
    private boolean isControlling = false;
    
    @SubscribeEvent
    public void onMovementInput(MovementInputUpdateEvent event) {
        if (!isControlling) return;
        // 使用实例字段
    }
}

// 在 Mod 构造函数中
MinecraftForge.EVENT_BUS.register(new BotControlHandler());
```

**优点**：
- ✅ 可以使用实例字段
- ✅ 可以依赖注入

**缺点**：
- ❌ 需要手动注册
- ❌ 需要管理实例生命周期

---

### **Pattern 3: 类注册（注册整个类）**

```java
public class BotManager {
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        // ...
    }
    
    @SubscribeEvent
    public static void onPlayerJoin(EntityJoinLevelEvent event) {
        // ...
    }
}

// 在 Mod 构造函数中
MinecraftForge.EVENT_BUS.register(BotManager.class);  // 注册类，不是实例
```

---

## Event Flow Example

### **示例：拦截玩家右键方块**

```java
// Mod A：记录所有交互（最低优先级）
@SubscribeEvent(priority = EventPriority.LOWEST)
public static void logInteract(PlayerInteractEvent.RightClickBlock event) {
    logger.info("Player " + event.getEntity().getName().getString() + 
        " interacted at " + event.getPos());
}

// Mod B：正常处理（默认优先级）
@SubscribeEvent
public static void handleInteract(PlayerInteractEvent.RightClickBlock event) {
    if (event.getItemStack().is(Items.DIAMOND)) {
        // 处理钻石
    }
}

// Alice Mod：拦截控制器物品（最高优先级）
@SubscribeEvent(priority = EventPriority.HIGHEST)
public static void interceptController(PlayerInteractEvent.RightClickBlock event) {
    if (event.getItemStack().is(AliceItems.BOT_CONTROLLER.get())) {
        // 进入控制模式
        enterControlMode(event.getEntity());
        event.setCanceled(true);  // 取消默认行为
    }
}
```

**执行顺序**：
```
1. Alice Mod (HIGHEST) → 取消事件
2. Mod B (NORMAL) → 检测到 isCanceled()，跳过
3. Mod A (LOWEST) → 仍然记录日志（即使事件被取消）
4. 原版逻辑 → 被跳过（因为事件被取消）
```

---

## Client vs Server Events

### **客户端事件**

```java
@Mod.EventBusSubscriber(modid = AliceMod.MOD_ID, value = Dist.CLIENT)
public class ClientEventHandler {
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        // 只在客户端执行
    }
}
```

**常见客户端事件**：
- `MovementInputUpdateEvent` - 玩家输入
- `RenderLevelStageEvent` - 渲染
- `InputEvent.Key` - 按键
- `ClientTickEvent` - 客户端 tick

---

### **服务端事件**

```java
@Mod.EventBusSubscriber(modid = AliceMod.MOD_ID)
public class ServerEventHandler {
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 只在服务端执行
    }
}
```

**常见服务端事件**：
- `ServerTickEvent` - 服务端 tick
- `RegisterCommandsEvent` - 命令注册
- `ServerStartingEvent` - 服务器启动

---

### **通用事件**

```java
@SubscribeEvent
public static void onEntityJoin(EntityJoinLevelEvent event) {
    // 客户端和服务端都执行
    // 需要检查 level.isClientSide
    if (!event.getLevel().isClientSide()) {
        // 只在服务端执行
    }
}
```

---

## Debugging Checklist

事件不触发时的调试清单：

### **Step 1: 检查总线**
```java
// 在 Mod 构造函数中添加日志
System.out.println("Registering to FORGE bus: " + BotManager.class);
MinecraftForge.EVENT_BUS.register(BotManager.class);
```

- [ ] 注册到正确的总线（Mod vs FORGE）？
- [ ] 注册代码被执行？

---

### **Step 2: 检查注解**
```java
@SubscribeEvent  // ← 有这个注解吗？
public static void onTick(TickEvent.ServerTickEvent event) {
    System.out.println("Tick event!");
}
```

- [ ] 有 `@SubscribeEvent` 注解？
- [ ] 方法签名正确（参数类型匹配）？
- [ ] 如果用 `@Mod.EventBusSubscriber`，方法是 static？

---

### **Step 3: 检查事件类型**
```java
@SubscribeEvent
public void onTick(TickEvent.ServerTickEvent event) {
    System.out.println("Event type: " + event.getClass().getName());
    System.out.println("Phase: " + event.phase);
}
```

- [ ] 事件类型正确（ServerTickEvent vs ClientTickEvent）？
- [ ] 阶段正确（START vs END）？

---

### **Step 4: 检查优先级**
```java
@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onInteract(PlayerInteractEvent event) {
    System.out.println("Interact event, canceled=" + event.isCanceled());
}
```

- [ ] 优先级合理？
- [ ] 事件被其他 mod 取消？

---

## Evidence Requirements

实施后需要以下证据：

**注册日志**：
```
[INFO] Registering to FORGE bus: BotManager
[INFO] Event handler registered: onServerTick
```

**事件触发日志**：
```
[DEBUG] ServerTickEvent triggered, phase=END
[DEBUG] PlayerInteractEvent triggered, pos=(100,64,100)
[DEBUG] Event canceled by Alice mod
```

**优先级测试**：
- [ ] HIGHEST handler 最先执行
- [ ] LOWEST handler 最后执行
- [ ] 取消事件后原版逻辑被阻止

---

**This skill helps you avoid 4-8 hours of "why doesn't my event handler work" debugging.**
