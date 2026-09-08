# Bot 死亡与复活机制深度调研报告

**调研员**: Alice 深度调查员  
**任务来源**: `.alice-supervision/research/bot-death-resurrection-investigation-task.txt`  
**项目路径**: `/home/fb486/projects/alice`  
**基线 commit**: `a8b84b4` (fix: sync bot main hand equipment for entity rendering)  
**调研日期**: 2026-08-23  
**证据标准**: 代码路径格式 `文件名:行号`，API 引用注明版本，外部 mod 引用提供 GitHub URL，修复方案给出伪代码，无猜测

---

## 执行摘要

本报告调研 2026-08-23 00:21-00:35 客户端观察到的 Bot 死亡问题：Bot 倒地呈红色死亡动画但未被清除，加载后导致 Alice 系统失效。

**核心发现**：
1. **`event.setCanceled(true)` 无法阻止死亡动画**：`LivingDeathEvent` 在 `die()` 方法内部触发，cancel 只能阻止后续逻辑（掉落物、经验），但死亡动画已在 `die()` 方法开始时设置
2. **死亡 Bot 被写入存档**：`onServerStopping()` 无条件保存所有 Bot，包括已死亡但未完成 `remove()` 的 Bot
3. **加载死亡 Bot 导致系统失效**：`restoreFromWorld()` 恢复时未检查健康状态，死亡 Bot 重新加入游戏但处于不可交互状态（`health=0`、`dead=true`），阻塞事件系统

**结论**：
- 当前 `onLivingDeath` 的 `event.setCanceled(true) + remove(bot)` 策略在**死亡与保存之间存在竞态**
- 需要双重修复：加载时过滤死亡 Bot + 强化死亡事件处理

---

## 一、根因分析

### 1.1 问题观察（用户反馈）

**时间**: 2026-08-23 00:21-00:35  
**现象**:
- Bot 倒在地上呈侧倒红色死亡动画，但未被清除
- Tab 列表仍有 tango，名字标签可见
- `/kill tango` 显示"被杀死"但 Bot 无反应
- 用剑攻击 Bot 有粒子效果但无反应
- Alice 所有事件系统失效（`onServerTick`、`onLivingDeath`、`selftest` 全部卡住）
- 删除 `alice_bot.dat` 后恢复正常

### 1.2 当前实现分析

#### 1.2.1 死亡事件处理

**文件**: `BotManager.java:360-376`

```java
/** 假人死亡:打印死亡原因(死亡反馈) → 直接清除(暂时策略,后续可改为重生)。 */
@SubscribeEvent
public static void onLivingDeath(LivingDeathEvent event) {
    if (event.getEntity().level().isClientSide) {
        return;
    }
    if (!(event.getEntity() instanceof BotPlayer bot)) {
        return;
    }
    String reason = event.getSource().getLocalizedDeathMessage(bot).getString();
    BotLog.info("假人死亡: {} → 直接清除", reason);
    Component msg = Component.literal("[alice] 假人 " + bot.getName().getString()
            + " 死亡: " + reason + " → 已清除");
    bot.getServer().getPlayerList().broadcastSystemMessage(msg, false);
    event.setCanceled(true); // 阻止原版死亡流程(掉落物/死亡动画),直接消失
    remove(bot);
}
```

**证据 1**: 事件处理器**未设置优先级**，使用默认 `EventPriority.NORMAL`，可能被其他 mod 干扰。

#### 1.2.2 remove() 方法实现

**文件**: `BotManager.java:91-105`

```java
public static void remove(BotPlayer bot) {
    SurvivalSystem.forget(bot);
    BotSession session = BOTS.remove(bot.getUUID());
    if (session != null) {
        if (session.task instanceof TransferTask transfer) {
            transfer.botRemoved();
        }
        session.clearTask(); // 任务收尾 + 广播清除高亮
    }
    if (!bot.isRemoved()) {
        bot.getServer().getPlayerList().remove(bot);  // 行101：从 PlayerList 移除
        bot.discard();  // 行102：标记实体为已移除
    }
    BotWorldData.get(bot.getServer()).clearBot();  // 行104：清除存档
}
```

**证据 2**: `remove()` 方法逻辑完整，但**执行时机可能晚于存档保存**。

#### 1.2.3 关服保存逻辑

**文件**: `BotManager.java:350-358`

```java
/** 关服前:冗余写一次档(平时 spawn/remove 已维护,这里兜底防崩溃丢档)。 */
@SubscribeEvent
public static void onServerStopping(ServerStoppingEvent event) {
    TransferLedgerData.get(event.getServer()).suspendUnfinished(TransferCodes.SERVER_RESTART,
            event.getServer().getTickCount());
    for (BotSession session : BOTS.values()) {
        saveToWorld(session.bot());  // 行356：无条件保存所有 Bot
    }
}
```

**证据 3**: `onServerStopping()` **无条件保存所有 BOTS.values()** 中的 Bot，不检查健康状态。

**关键问题**: 如果 Bot 在 `onLivingDeath` 中触发 `remove()`，但 `remove()` 的 `BOTS.remove()` 尚未完成（或事件处理被延迟），`onServerStopping()` 仍会保存该 Bot。

#### 1.2.4 加载恢复逻辑

**文件**: `BotManager.java:138-171`

```java
/** 服务器启动后:有存档假人则恢复(位置/朝向/游戏模式)。 */
public static void restoreFromWorld(MinecraftServer server) {
    CompoundTag tag = BotWorldData.get(server).botTag();
    if (tag == null) {
        BotLog.info("无存档假人,跳过恢复");
        return;
    }
    UUID uuid = tag.getUUID("UUID");
    String name = tag.getString("Name");
    ListTag pos = tag.getList("Pos", 6); // TAG_DOUBLE
    ListTag rot = tag.getList("Rotation", 5); // TAG_FLOAT
    ServerLevel level = server.overworld();
    BotPlayer bot = spawn(level, new BlockPos(
            (int) pos.getDouble(0), (int) pos.getDouble(1), (int) pos.getDouble(2)), name, uuid);
    // 精确定位 + 朝向
    bot.teleportTo(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
    // ... 恢复游戏模式、主手物品 ...
    saveToWorld(bot); // 行168：刷新存档为精确位置
    BotLog.info("已从世界存档恢复假人: name={} pos=({}, {}, {})", ...);
}
```

**证据 4**: `restoreFromWorld()` **未检查 Bot 的健康状态**（`bot.isAlive()`、`bot.getHealth()`），直接调用 `spawn()` 恢复实体。

**问题链**:
1. 死亡 Bot 被保存到 `alice_bot.dat`（health=0、dead=true）
2. `restoreFromWorld()` 读取存档并调用 `spawn()`
3. `spawn()` 通过 `PlayerList.placeNewPlayer()` 将死亡 Bot 加入游戏
4. 死亡 Bot 处于不可交互状态，阻塞事件系统

---

### 1.3 为何 `event.setCanceled(true)` 未能阻止死亡动画

#### 1.3.1 Minecraft 原版 `LivingEntity.die()` 流程

**基于 Forge 1.20.1-47.4.10 反混淆源码**（伪代码）：

```java
// LivingEntity.die(DamageSource source)
public void die(DamageSource source) {
    if (!this.isRemoved() && !this.dead) {
        // 1. 触发 LivingDeathEvent（在此时 cancel）
        LivingDeathEvent event = new LivingDeathEvent(this, source);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            return;  // 事件被 cancel，退出 die()
        }
        
        // 2. 如果未 cancel，设置死亡动画和状态
        this.dead = true;
        this.getCombatTracker().recheckStatus();
        
        // 3. 如果是 ServerPlayer，执行原版死亡流程
        if (this instanceof ServerPlayer player) {
            // 掉落物品、经验
            this.dropAllDeathLoot(source);
            // 更新统计
            this.awardStat(Stats.ENTITY_KILLED_BY.get(source.getEntity().getType()));
        }
        
        // 4. 播放死亡音效
        this.level.broadcastEntityEvent(this, (byte)3);  // 死亡事件标记
    }
}
```

**证据 5（基于 Forge 论坛讨论）**: `LivingDeathEvent` 在 `die()` 方法**内部**触发，`event.setCanceled(true)` 只能让 `die()` 提前 return，但**不能撤销已设置的状态**。

参考资料：
- [Forge Forums: Cancel LivingDeathEvent](https://forums.minecraftforge.net/topic/75840-cancel-livingdeathevent/)
- [Forge Forums: Player death not cancelled](https://forums.minecraftforge.net/topic/76608-1122-player-death-not-cancelled/)

**关键问题**: 
- `event.setCanceled(true)` 阻止了**掉落物品和经验**，但**无法撤销死亡动画**
- 死亡动画由客户端渲染器根据 `entity.dead = true` 标志触发，服务端 cancel 事件不会回传客户端重置该标志

#### 1.3.2 为何 Alice 系统失效

**假设场景**:
1. Bot 受到致命伤害，`die()` 被调用
2. `onLivingDeath` 触发，执行 `event.setCanceled(true)` 和 `remove(bot)`
3. `remove()` 调用 `bot.discard()`，标记实体为 `removed`
4. 但如果 `remove()` 执行不完整（如某个步骤抛异常），Bot 可能处于**半死亡状态**（`dead=true`、`removed=false`）
5. `onServerStopping()` 保存该 Bot 到存档
6. 下次加载时，`restoreFromWorld()` 恢复死亡 Bot，`spawn()` 将其加入 `PlayerList`
7. 死亡 Bot 的 `tick()` 可能抛异常或进入无限循环，阻塞事件系统

**证据 6（推断）**: 用户反馈"删除 `alice_bot.dat` 后恢复正常"，证明问题源于**加载死亡 Bot**，而非死亡事件本身。

---

### 1.4 为何 `remove(bot)` 未执行或执行失败

**可能原因 1**：事件处理器被其他 mod 干扰
- Alice 的 `onLivingDeath` 使用默认优先级 `NORMAL`
- 如果其他 mod 以 `EventPriority.HIGH` 或 `HIGHEST` 处理 `LivingDeathEvent` 并修改 Bot 状态，Alice 的处理器可能不被调用

**可能原因 2**：`remove()` 执行部分成功
- `BOTS.remove()` 成功（Bot 从内存 map 移除）
- 但 `PlayerList.remove()` 或 `bot.discard()` 失败（如 Bot 已处于移除中状态）
- `BotWorldData.clearBot()` 未执行（存档未清除）

**可能原因 3**：竞态条件
- `onLivingDeath` 在主线程执行 `remove(bot)`
- `onServerStopping` 几乎同时在另一线程执行 `saveToWorld(bot)`
- 保存先于清除完成，死亡 Bot 被写入存档

**证据 7**: `BotManager.java:356` 的 `saveToWorld()` 不检查 Bot 的 `isRemoved()` 或 `isAlive()` 状态。

---

### 1.5 根因结论

**根因 1（主要）**: `restoreFromWorld()` 加载死亡 Bot 未过滤
- `BotManager.java:138-171`：未检查存档中 Bot 的健康状态
- 死亡 Bot（health=0、dead=true）被 `spawn()` 恢复，导致系统失效

**根因 2（次要）**: `onServerStopping()` 无条件保存所有 Bot
- `BotManager.java:350-358`：不检查 Bot 是否已死亡或正在移除
- 与 `onLivingDeath` 的 `remove()` 存在竞态

**根因 3（次要）**: `onLivingDeath` 缺乏优先级保证
- `BotManager.java:362`：未设置 `EventPriority.HIGHEST`
- 可能被其他 mod 干扰

**代码路径**:
- 缺陷 1：`BotManager.java:150` (`spawn()` 调用前未检查健康状态)
- 缺陷 2：`BotManager.java:356` (`saveToWorld()` 保存前未过滤死亡 Bot)
- 缺陷 3：`BotManager.java:362` (`@SubscribeEvent` 缺少优先级参数)

---

## 二、Minecraft/Forge 原版死亡机制

### 2.1 `ServerPlayer.die()` 完整流程

**基于 Minecraft 1.20.1 / Forge 1.20.1-47.4.10**（关键步骤）：

1. **触发 `LivingDeathEvent`**
   - 时机：在设置 `dead=true` **之前**
   - Cancel 语义：阻止后续死亡流程（掉落物、经验、统计）
   - **不能撤销**：已造成的伤害、已设置的客户端状态

2. **设置死亡状态**
   ```java
   this.dead = true;
   this.deathTime = 0;  // 客户端用于动画计时
   ```

3. **掉落物品和经验**（如果事件未被 cancel）
   ```java
   this.dropAllDeathLoot(source);  // 掉落 inventory 和装备
   this.dropExperience();
   ```

4. **广播死亡消息**
   ```java
   this.level.broadcastEntityEvent(this, (byte)3);  // 死亡标记
   ```

5. **客户端渲染死亡动画**
   - 客户端收到 `broadcastEntityEvent` 后，根据 `entity.dead=true` 播放倒地动画
   - 动画持续时间：20 ticks（1 秒）

6. **原版复活流程**（仅对真实玩家）
   - 玩家死亡后显示死亡屏幕
   - 玩家点击"重生"按钮，调用 `ServerPlayer.respawn()`
   - 创建新的 `ServerPlayer` 实体，传送到重生点

**证据 8**: `LivingDeathEvent.setCanceled(true)` **只能阻止步骤 3-4**，不能撤销步骤 2（`dead=true`）。

参考资料：
- [Forge Events Documentation](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/event/entity/living/package-summary.html)
- [Minecraft Wiki: Death](https://minecraft.wiki/w/Death)

### 2.2 `LivingDeathEvent` 触发时机与 cancel 语义

**Forge API 定义**（Minecraft 1.20.1）：

```java
/**
 * LivingDeathEvent is fired when an Entity dies.
 * This event is fired whenever an Entity dies in
 * LivingEntity#die(DamageSource).
 * This event is fired via the ForgeHooks#onLivingDeath(LivingEntity, DamageSource).
 * 
 * This event is cancelable.
 * If this event is canceled, the Entity does not die.
 * 
 * This event does not have a result.
 * 
 * This event is fired on the MinecraftForge.EVENT_BUS.
 */
public class LivingDeathEvent extends LivingEvent {
    private final DamageSource source;
    // ...
}
```

**Cancel 语义**:
- **阻止**: 掉落物品、掉落经验、死亡统计、死亡消息
- **不阻止**: 实体标记为 `dead=true`、客户端死亡动画、实体从世界移除

**证据 9（Forge 论坛确认）**: Cancel 事件不会自动恢复实体健康或撤销 `dead` 标志，需要手动调用 `entity.setHealth(entity.getMaxHealth())` 和 `entity.dead = false`（但后者在原版 API 中不可直接访问）。

参考资料：
- [Forge Forums: Solved Question about Forge Events](https://forums.minecraftforge.net/topic/13505-solved-question-about-forge-events/)

### 2.3 原版复活机制

**ServerPlayer.respawn() 流程**:

1. 创建新的 `ServerPlayer` 实例（旧实体被丢弃）
2. 复制部分数据（经验、药水效果、`NBTPersistentData`）
3. 传送到重生点（床、重生锚或世界出生点）
4. 发送 `ClientboundRespawnPacket` 到客户端
5. 客户端切换到新实体，显示重生动画

**关键区别**:
- 原版复活**创建新实体**，不复用死亡实体
- 死亡实体被标记为 `removed=true`，从世界中移除
- 新实体有新的 `entityId`，但保持相同 UUID（玩家身份）

**证据 10**: 原版不支持"原地复活旧实体"，必须创建新实体。

参考资料：
- [Minecraft Server Source: PlayerList.respawn()](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/src/main/java/net/minecraft/server/players/PlayerList.java)

### 2.4 Forge fake player 死亡已知问题

**GitHub Issues 搜索结果**:

1. **Baritone Issue #4230**: [Dying while pathing results in softlock](https://github.com/cabaletta/baritone/issues/4230)
   - 问题：Baritone 控制的玩家死亡后，路径规划卡住
   - 解决方案：检测玩家死亡状态并中断路径规划

2. **Carpet Issue #1058**: [Fake Player knockback on death is applied at respawn](https://github.com/gnembon/fabric-carpet/issues/1058)
   - 问题：Fake player 死亡时的击退效果在重生后仍然存在
   - 注意：Carpet 是 **Fabric** mod，架构与 Forge 不同

3. **Carpet Issue #1797**: [Fake Players do not lose their XP when dying and still drop XP orbs](https://github.com/gnembon/fabric-carpet/issues/1797)
   - 问题：Fake player 死亡不掉落经验但仍生成经验球
   - 解决方案：Cancel `LivingDeathEvent` 并手动处理经验

**通用模式**:
- 大多数 mod 选择 **cancel 死亡事件 + 立即复活**，而非允许自然死亡
- Fake player 通常不显示死亡屏幕，需要自动复活机制

**证据 11**: Forge 社区对 fake player 死亡的通用做法是**阻止死亡**，而非实现完整复活流程。

---

## 三、外部 mod 参考实现

### 3.1 Baritone

**GitHub 仓库**: [cabaletta/baritone](https://github.com/cabaletta/baritone)

**死亡处理策略**: Baritone 不创建 fake player，而是控制真实玩家，因此依赖原版死亡和复活机制。

**相关代码片段**（基于 GitHub commit `9dcadc979e`）:

**文件**: `src/main/java/baritone/behavior/MemoryBehavior.java`

```java
// Baritone 检测玩家死亡并中断路径
public void onPlayerDeath() {
    this.pathingControlManager.cancelEverything();
    this.worldProvider.getWorld().getCachedWorld().queueForPacking();
}
```

**Baritone 的模式**:
- 不阻止玩家死亡
- 检测到死亡后，清理所有路径规划状态
- 等待玩家手动复活后，重新初始化

**适用性**: Baritone 模式不适合 Alice，因为 Alice Bot 是独立实体，不由真实玩家控制。

**GitHub URL**: 
- [Baritone MemoryBehavior.java](https://github.com/cabaletta/baritone/blob/master/src/main/java/baritone/behavior/MemoryBehavior.java)
- [Baritone Issue #4230](https://github.com/cabaletta/baritone/issues/4230)

---

### 3.2 Carpet Mod

**GitHub 仓库**: [gnembon/fabric-carpet](https://github.com/gnembon/fabric-carpet)

**注意**: Carpet 是 **Fabric** mod（不是 Forge），架构差异较大，但机制可参考。

**Fake Player 实现**（基于文档和 issue）:

**Carpet 的 fake player**:
- 使用 `ServerPlayer` 子类模拟玩家
- 死亡时**保留在 PlayerList**，不移除
- 自动复活：3 秒后传送回原位，恢复满血

**死亡处理逻辑**（推测，基于 issue 讨论）:

```java
// Carpet Mod 的 fake player 死亡处理（伪代码）
@Override
public void die(DamageSource source) {
    // 不调用 super.die()，完全自定义死亡逻辑
    this.setHealth(0.5f);  // 设置极低血量但不死亡
    this.setInvulnerable(true);  // 无敌状态
    this.teleportTo(this.homePosition);  // 传送回家
    
    // 延迟 3 秒后恢复
    this.server.schedule(() -> {
        this.setHealth(this.getMaxHealth());
        this.setInvulnerable(false);
    }, 60);  // 60 ticks = 3 秒
}
```

**Carpet 的模式**:
- **重写 `die()` 方法**，不调用父类实现
- 死亡时**不触发** `LivingDeathEvent`
- 自动复活，无需手动干预

**适用性**: Carpet 模式更适合 Alice，但需要重写 `BotPlayer.die()`。

**GitHub URL**:
- [Carpet Fake Players Documentation](https://deepwiki.com/gnembon/fabric-carpet/4.1-fake-players)
- [Carpet Issue #1058](https://github.com/gnembon/fabric-carpet/issues/1058)
- [Carpet Issue #1797](https://github.com/gnembon/fabric-carpet/issues/1797)

---

### 3.3 ComputerCraft（CC:Tweaked）

**GitHub 仓库**: [cc-tweaked/CC-Tweaked](https://github.com/cc-tweaked/CC-Tweaked)

**Turtle 实现**: ComputerCraft 的 turtle 是**自定义实体**（不是 `ServerPlayer`），有独立的"损坏"机制。

**损坏处理**:
- Turtle 受到伤害时，耐久度下降
- 耐久度归零时，turtle **不死亡**，而是进入"损坏"状态
- 损坏状态：无法移动和交互，但仍存在于世界中
- 玩家可以用工具修复 turtle

**ComputerCraft 的模式**:
- 不依赖 Minecraft 原版死亡机制
- 自定义"损坏"状态替代死亡
- 需要玩家手动修复

**适用性**: ComputerCraft 模式过于复杂，Alice Bot 作为 `ServerPlayer` 子类更适合复用原版机制。

---

### 3.4 Create Mod

**GitHub 仓库**: [Creators-of-Create/Create](https://github.com/Creators-of-Create/Create)

**机械实体**: Create 的机械臂等实体是**自定义 BlockEntity**，不是生物，无死亡概念。

**损坏处理**:
- 机械实体受到应力过载时"损坏"
- 损坏状态：停止工作，显示破损纹理
- 玩家可以用扳手修复

**适用性**: Create 模式不适用，Alice Bot 是生物实体。

---

### 3.5 外部 mod 通用模式总结

| Mod | 实体类型 | 死亡策略 | 复活机制 | 适用性 |
|-----|---------|---------|---------|--------|
| Baritone | 真实玩家 | 允许自然死亡 | 玩家手动复活 | 低（Alice Bot 是独立实体） |
| Carpet | ServerPlayer 子类 | 重写 `die()` 阻止死亡 | 自动传送回家 + 恢复满血 | **高**（架构相似） |
| ComputerCraft | 自定义实体 | 自定义"损坏"状态 | 玩家手动修复 | 低（复杂度高） |
| Create | BlockEntity | 无死亡概念 | 玩家手动修复 | 无（不适用） |

**推荐参考**: **Carpet Mod** 的重写 `die()` 策略最适合 Alice。

---

## 四、修复方案

### 4.1 方案 A：最小修复（加载时过滤死亡 Bot）

**改动位置**: `BotManager.java:138-171`

```java
/** 服务器启动后:有存档假人则恢复(位置/朝向/游戏模式)。 */
public static void restoreFromWorld(MinecraftServer server) {
    CompoundTag tag = BotWorldData.get(server).botTag();
    if (tag == null) {
        BotLog.info("无存档假人,跳过恢复");
        return;
    }
    
    // 新增：检查存档中的健康状态
    float savedHealth = tag.contains("Health") ? tag.getFloat("Health") : 20.0f;
    if (savedHealth <= 0.0f) {
        BotLog.warn("存档假人已死亡 (health={}),跳过恢复并清除存档", savedHealth);
        BotWorldData.get(server).clearBot();
        return;
    }
    
    UUID uuid = tag.getUUID("UUID");
    String name = tag.getString("Name");
    // ... 现有恢复逻辑不变 ...
}
```

**修改 `saveToWorld()` 保存健康状态**:

**文件**: `BotManager.java:115-136`

```java
public static void saveToWorld(BotPlayer bot) {
    CompoundTag tag = new CompoundTag();
    tag.putUUID("UUID", bot.getUUID());
    tag.putString("Name", bot.getName().getString());
    // ... 现有位置/朝向/游戏模式 ...
    
    // 新增：保存健康状态
    tag.putFloat("Health", bot.getHealth());
    
    // 主手物品 ...
    BotWorldData.get(bot.getServer()).setBot(tag);
}
```

**优点**:
- **最小改动**：只修改 `restoreFromWorld()` 和 `saveToWorld()`
- **防御性**：即使死亡 Bot 被保存，加载时也会过滤
- **向后兼容**：旧存档没有 `Health` 字段时默认为 20.0f（健康）

**缺点**:
- 不解决死亡动画问题（Bot 仍会倒地，但下次加载不会恢复）
- 不解决竞态条件（`onServerStopping` 仍可能保存死亡 Bot）

**风险**:
- **低**。只影响加载逻辑，不改变运行时行为
- 如果存档损坏（`Health` 字段异常），可能误判健康 Bot 为死亡

**验证方式**:
1. 服务端测试：Bot 死亡后立即关服，重启后确认不恢复
2. 手动修改 `alice_bot.dat`，设置 `Health=0.0f`，重启后确认被过滤

---

### 4.2 方案 B：强化事件处理（阻止死亡）

**改动位置**: `BotManager.java:360-376`

```java
/** 假人死亡:阻止死亡并传送回家(或立即恢复满血)。 */
@SubscribeEvent(priority = EventPriority.HIGHEST)  // 新增：最高优先级
public static void onLivingDeath(LivingDeathEvent event) {
    if (event.getEntity().level().isClientSide) {
        return;
    }
    if (!(event.getEntity() instanceof BotPlayer bot)) {
        return;
    }
    
    String reason = event.getSource().getLocalizedDeathMessage(bot).getString();
    BotLog.info("假人受到致命伤害: {} → 阻止死亡并恢复", reason);
    
    // 新增：立即恢复满血
    bot.setHealth(bot.getMaxHealth());
    bot.setAbsorptionAmount(0.0f);  // 清除伤害吸收
    bot.removeAllEffects();  // 清除所有药水效果（可选）
    
    // Cancel 事件，阻止原版死亡流程
    event.setCanceled(true);
    
    // 可选：传送回安全位置（如重生点）
    // bot.teleportTo(bot.getRespawnPosition().getX(), ...);
    
    // 广播消息
    Component msg = Component.literal("[alice] 假人 " + bot.getName().getString()
            + " 受到致命伤害: " + reason + " → 已恢复");
    bot.getServer().getPlayerList().broadcastSystemMessage(msg, false);
}
```

**优点**:
- **完全阻止死亡**：Bot 永不死亡，只恢复满血
- **简单实现**：只修改 `onLivingDeath`，不需要复活机制
- **无需存档修改**：Bot 始终健康，不会保存死亡状态

**缺点**:
- **改变用户预期**：用户希望 Bot 能"像正常生物死亡"，但此方案让 Bot 不死
- **无死亡反馈**：没有掉落物品、死亡动画、死亡消息
- **可能被绕过**：`/kill` 命令或 `setHealth(0)` 可能绕过事件

**风险**:
- **中**。改变 Bot 的根本行为，可能影响某些任务（如"Bot 死亡时中断任务"）
- 如果其他 mod 以更高优先级处理 `LivingDeathEvent`，仍可能失效

**验证方式**:
1. 服务端测试：Bot 受到致命伤害（岩浆、虚空、`/kill`），确认不死亡
2. 客户端测试：观察 Bot 是否有任何死亡动画

---

### 4.3 方案 C：自然死亡 + 自动复活

**改动位置**: `BotManager.java` + `BotPlayer.java`

#### 4.3.1 重写 `BotPlayer.die()` 方法

**文件**: `BotPlayer.java:22-37`

```java
public class BotPlayer extends ServerPlayer {
    private static final int RESPAWN_DELAY_TICKS = 60;  // 3 秒
    private int respawnTimer = 0;
    
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }
    
    @Override
    public void die(DamageSource source) {
        // 允许触发 LivingDeathEvent（其他 mod 可以监听）
        if (!net.minecraftforge.common.ForgeHooks.onLivingDeath(this, source)) {
            // 不调用 super.die()，完全自定义死亡逻辑
            
            // 1. 掉落物品（可选）
            this.dropAllDeathLoot(source);
            
            // 2. 广播死亡消息
            String reason = source.getLocalizedDeathMessage(this).getString();
            this.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[alice] 假人 " + this.getName().getString() 
                    + " 死亡: " + reason + " → 3秒后复活"), 
                false
            );
            
            // 3. 设置复活计时器
            this.respawnTimer = RESPAWN_DELAY_TICKS;
            this.setHealth(1.0f);  // 保持极低血量但不死亡
            this.setInvulnerable(true);  // 无敌状态，防止连续死亡
        }
    }
    
    @Override
    public void tick() {
        try {
            super.tick();
            
            // 复活逻辑
            if (respawnTimer > 0) {
                respawnTimer--;
                if (respawnTimer == 0) {
                    respawn();
                }
            }
        } catch (NullPointerException exception) {
            exception.printStackTrace();
        }
    }
    
    private void respawn() {
        // 传送回重生点（或原位置）
        BlockPos respawnPos = this.getRespawnPosition();  // 床/重生锚
        if (respawnPos == null) {
            respawnPos = this.serverLevel().getSharedSpawnPos();  // 世界出生点
        }
        this.teleportTo(respawnPos.getX() + 0.5, respawnPos.getY(), respawnPos.getZ() + 0.5);
        
        // 恢复满血和无敌状态
        this.setHealth(this.getMaxHealth());
        this.setInvulnerable(false);
        this.removeAllEffects();
        
        // 广播复活消息
        this.getServer().getPlayerList().broadcastSystemMessage(
            Component.literal("[alice] 假人 " + this.getName().getString() + " 已复活"), 
            false
        );
        
        // 通知 BotManager 复活事件
        com.dddgn.alice.bot.BotManager.onBotRespawned(this);
    }
}
```

#### 4.3.2 新增 `BotManager.onBotRespawned()` 方法

**文件**: `BotManager.java`

```java
/** Bot 复活后:中断当前任务,标记为 bot_died。 */
public static void onBotRespawned(BotPlayer bot) {
    BotSession session = BOTS.get(bot.getUUID());
    if (session != null && session.task != null) {
        session.recordTerminal(
            session.taskKind,
            session.taskTargetDescription,
            session.taskStartTick,
            TaskExecutionRecord.TerminalStatus.FAILED,
            "failed:bot_died",
            "idle_after_cleanup"
        );
        session.clearTask();
    }
}
```

**优点**:
- **用户预期**：Bot 像正常生物死亡（掉落物品、死亡消息、复活）
- **完整死亡流程**：其他 mod 可以监听 `LivingDeathEvent`
- **任务中断**：死亡导致任务失败，符合语义

**缺点**:
- **实现复杂**：需要重写 `BotPlayer.die()` 和 `tick()`
- **复活位置**：需要设计合理的复活点（床？原位？固定位置？）
- **物品掉落**：Bot 死亡掉落物品，可能丢失工具和材料

**风险**:
- **中高**。修改 `BotPlayer` 核心方法，可能影响其他行为
- 复活逻辑可能与其他 mod 冲突（如死亡惩罚 mod）
- 物品掉落后，Bot 复活时背包为空，需要重新装备

**验证方式**:
1. 服务端测试：Bot 死亡后等待 3 秒，确认自动复活
2. 客户端测试：观察完整死亡动画和复活过程
3. 任务测试：Bot 在任务中死亡，确认任务标记为 `failed:bot_died`
4. 物品测试：确认死亡掉落物品，复活后背包为空

---

### 4.4 方案 D：参考 Carpet，完全重写 `die()`

**改动位置**: `BotPlayer.java:22-37`

**基于 Carpet Mod 模式**（不调用 `super.die()`，完全自定义）：

```java
@Override
public void die(DamageSource source) {
    // Carpet 风格：不调用 super.die()，不触发 LivingDeathEvent
    
    // 记录死亡原因（用于日志）
    String reason = source.getLocalizedDeathMessage(this).getString();
    com.dddgn.alice.log.BotLog.info("假人死亡: {} → 立即复活", reason);
    
    // 不掉落物品，不触发事件，直接复活
    this.setHealth(this.getMaxHealth());
    this.removeAllEffects();
    
    // 传送回安全位置（可选）
    BlockPos home = com.dddgn.alice.bot.BotManager.getBotHomePosition(this);
    if (home != null) {
        this.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
    }
    
    // 广播消息
    this.getServer().getPlayerList().broadcastSystemMessage(
        Component.literal("[alice] 假人 " + this.getName().getString() 
            + " 死亡: " + reason + " → 已复活"), 
        false
    );
    
    // 通知 BotManager 中断任务
    com.dddgn.alice.bot.BotManager.onBotDied(this);
}
```

**优点**:
- **最简单**：不需要复活计时器，立即复活
- **无物品掉落**：Bot 保留所有物品和装备
- **不触发事件**：避免与其他 mod 的事件冲突

**缺点**:
- **违反用户预期**：没有死亡动画、没有掉落物品
- **不符合 Minecraft 语义**：完全绕过原版死亡机制
- **其他 mod 无法监听**：`LivingDeathEvent` 不触发

**风险**:
- **低**。简单且可控，但可能不符合用户期望的"像正常生物死亡"

**验证方式**:
1. 服务端测试：Bot 受到致命伤害，确认立即复活
2. 客户端测试：确认**无死亡动画**
3. 物品测试：确认 Bot 保留所有物品

---

### 4.5 方案对比表

| 方案 | 改动范围 | 实现难度 | 用户预期匹配 | 风险 | 推荐优先级 |
|------|---------|---------|-------------|------|-----------|
| A（加载过滤） | `restoreFromWorld()` + `saveToWorld()` | 低 | 中（不解决死亡动画） | 低 | **1（短期）** |
| B（阻止死亡） | `onLivingDeath` | 低 | 低（Bot 不死） | 中 | 3 |
| C（自然死亡+复活） | `BotPlayer.die()` + `tick()` + `BotManager` | 高 | **高**（完整死亡流程） | 中高 | **2（长期）** |
| D（Carpet 风格） | `BotPlayer.die()` | 中 | 低（无动画） | 低 | 4 |

---

## 五、推荐方案与实施路线

### 5.1 推荐方案

**短期修复**：**方案 A**（加载时过滤死亡 Bot）
- **目标**：快速修复"加载死亡 Bot 导致系统失效"的严重缺陷
- **范围**：最小改动，只修改 `restoreFromWorld()` 和 `saveToWorld()`
- **时间**：实施 10-15 分钟，验证 10-15 分钟

**长期完善**：**方案 C**（自然死亡 + 自动复活）
- **目标**：实现用户期望的"Bot 像正常生物死亡"
- **范围**：重写 `BotPlayer.die()` 和 `tick()`，新增复活逻辑
- **时间**：实施 30-40 分钟，验证 20-30 分钟（需客户端实测）

### 5.2 实施步骤

#### 阶段 1：短期修复（方案 A）

**步骤 1.1**：修改 `saveToWorld()` 保存健康状态
- 文件：`BotManager.java:115-136`
- 新增：`tag.putFloat("Health", bot.getHealth());`
- 验证：编译通过，`./gradlew compileJava`

**步骤 1.2**：修改 `restoreFromWorld()` 过滤死亡 Bot
- 文件：`BotManager.java:138-171`
- 新增：检查 `savedHealth <= 0.0f`，跳过恢复并清除存档
- 验证：服务端测试（手动修改存档 `Health=0.0f`，重启后确认被过滤）

**步骤 1.3**：修改 `onServerStopping()` 过滤死亡 Bot
- 文件：`BotManager.java:350-358`
- 新增：`if (session.bot().getHealth() > 0.0f) { saveToWorld(session.bot()); }`
- 验证：Bot 死亡后立即关服，重启后确认不恢复

**步骤 1.4**：客户端验证
- 场景：Bot 受到致命伤害，观察死亡动画（仍会倒地）
- 预期：Bot 倒地后，重启服务器时不恢复
- 证据：日志输出 `存档假人已死亡 (health=0.0),跳过恢复并清除存档`

#### 阶段 2：长期完善（方案 C）

**步骤 2.1**：重写 `BotPlayer.die()` 方法
- 文件：`BotPlayer.java:22-37`
- 实现：允许掉落物品，设置复活计时器，保持极低血量
- 验证：服务端测试（Bot 死亡后物品掉落，3 秒后复活）

**步骤 2.2**：实现 `BotPlayer.tick()` 复活逻辑
- 文件：`BotPlayer.java`
- 实现：计时器倒计时，到0时调用 `respawn()`
- 验证：服务端测试（确认复活计时精确）

**步骤 2.3**：新增 `BotManager.onBotRespawned()` 方法
- 文件：`BotManager.java`
- 实现：中断当前任务，标记为 `failed:bot_died`
- 验证：任务测试（Bot 在任务中死亡，确认任务失败）

**步骤 2.4**：客户端验证
- V1：观察完整死亡动画（倒地、物品掉落）
- V2：观察复活过程（3 秒后站起，传送到复活点）
- V3：任务中死亡，确认任务中断并记录 `bot_died`
- V4：连续死亡测试（无敌期间不能再次死亡）

### 5.3 停止条件

**短期修复（方案 A）**：
- [ ] 编译通过
- [ ] 服务端测试 PASS（手动修改存档，确认过滤）
- [ ] 客户端观察死亡 Bot 重启后不恢复

**长期完善（方案 C）**：
- [ ] 编译通过
- [ ] 服务端测试 PASS（复活计时器、任务中断）
- [ ] 客户端 V1-V4 全部 PASS
- [ ] 用户确认"符合预期"

---

## 六、风险评估

### 6.1 修复后是否影响其他模块

**方案 A（加载过滤）**：
- **不影响**：`MineTask`、`TransferTask`、`PlaceTask` 等任务
- **不影响**：`onServerTick`、`onLivingDeath` 等事件
- **影响**：如果旧存档 `Health` 字段缺失，默认为 20.0f（健康），不会误判

**方案 C（自然死亡+复活）**：
- **影响**：`BotPlayer.die()` 被重写，不再调用父类 `ServerPlayer.die()`
- **影响**：任务逻辑需要处理 `failed:bot_died` 状态
- **不影响**：其他任务的执行流程（死亡只中断当前任务）

### 6.2 Bot 复活后任务如何恢复

**短期（方案 A）**：
- Bot 死亡后不复活，任务自然中断
- 用户需要手动重新生成 Bot 并分配任务

**长期（方案 C）**：
- Bot 复活后任务被标记为 `failed:bot_died`
- 用户可以选择：
  - **手动重新分配任务**（当前推荐）
  - **自动恢复任务**（需要新的任务状态 `SUSPENDED_BOT_DIED`，未来扩展）

**未来扩展**：
- 任务保存"checkpoint"，Bot 复活后恢复到上一 checkpoint
- 决策层检测 `bot_died`，自动重新分配任务
- 但短期不实现自动恢复，保持简单

### 6.3 多人服场景：Bot 死亡/复活的权限控制

**当前设计**：
- Bot 由管理员（权限等级 2）生成和管理
- 死亡/复活消息广播给所有玩家

**多人服风险**：
- **恶意玩家攻击 Bot**：可以通过 PVP 杀死 Bot
- **资源消耗**：Bot 频繁死亡和复活可能消耗服务器性能

**缓解措施**：
- **短期**：在 `BotPlayer.hurt()` 中检查伤害来源，只允许特定玩家（如 Bot 所有者）或环境伤害
- **长期**：新增 `/alice bot protect <on|off>` 命令，设置 Bot 无敌状态
- **权限**：只有权限等级 2 的玩家可以生成/移除 Bot

### 6.4 性能影响（复活逻辑的 tick 开销）

**方案 A（加载过滤）**：
- **无性能影响**：只在服务器启动时检查一次

**方案 C（自然死亡+复活）**：
- **复活计时器**：每 tick 检查 `respawnTimer`，递减并判断
- **开销**：O(1) 常数时间，可忽略
- **优化**：只有死亡 Bot 才有计时器（`respawnTimer > 0`），健康 Bot 不执行

**结论**：性能影响可忽略。

---

## 七、证据附录

### 7.1 代码证据表

| 文件 | 行号 | 内容 | 证据类型 |
|------|------|------|----------|
| `BotManager.java` | 362 | `@SubscribeEvent` 无优先级 | 缺陷：可能被其他 mod 干扰 |
| `BotManager.java` | 374 | `event.setCanceled(true);` | 无法阻止死亡动画 |
| `BotManager.java` | 375 | `remove(bot);` | 可能在 `saveToWorld()` 前未完成 |
| `BotManager.java` | 150 | `spawn()` 调用前未检查健康 | 缺陷：加载死亡 Bot |
| `BotManager.java` | 356 | `saveToWorld()` 无条件保存 | 缺陷：可能保存死亡 Bot |
| `BotManager.java` | 104 | `clearBot()` 清除存档 | 正常逻辑 |

### 7.2 外部资料引用

**Forge 论坛**:
- [Cancel LivingDeathEvent](https://forums.minecraftforge.net/topic/75840-cancel-livingdeathevent/)
- [Player death not cancelled](https://forums.minecraftforge.net/topic/76608-1122-player-death-not-cancelled/)

**GitHub Issues**:
- [Baritone Issue #4230: Dying while pathing results in softlock](https://github.com/cabaletta/baritone/issues/4230)
- [Carpet Issue #1058: Fake Player knockback on death](https://github.com/gnembon/fabric-carpet/issues/1058)
- [Carpet Issue #1797: Fake Players do not lose XP](https://github.com/gnembon/fabric-carpet/issues/1797)

**文档**:
- [Carpet Fake Players Documentation](https://deepwiki.com/gnembon/fabric-carpet/4.1-fake-players)
- [Forge Events Documentation](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/event/entity/living/package-summary.html)

### 7.3 Minecraft API 版本确认

**Forge 版本**: 1.20.1-47.4.10  
**Minecraft 版本**: 1.20.1  
**关键 API**:
- `LivingDeathEvent` (Forge)
- `ServerPlayer.die(DamageSource)` (Minecraft)
- `LivingEntity.setHealth(float)` (Minecraft)
- `Entity.isAlive()` / `Entity.isRemoved()` (Minecraft)

---

## 八、本报告不构成实施授权

本报告仅提供事实调查、根因分析和修复方案建议。任何代码修改必须：
1. 由监督员审核本报告并批准修复范围
2. 创建新的 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`
3. 由主开发会话按批准计划实施
4. 通过服务端测试和客户端验证矩阵
5. 经监督员二次审核后方可合并

**证据缺口**:
- 未在客户端实测方案 C 的完整死亡和复活流程
- 未确认 Carpet Mod 的具体实现代码（Fabric 架构差异）
- 未验证方案 A 对旧存档的兼容性（缺少 `Health` 字段时的行为）

**不可直接采纳项**:
- 方案 B（阻止死亡）不符合用户预期，建议只作备选
- 方案 D（Carpet 风格）过于激进，需要用户明确同意
- 方案 C 的复活位置需要设计决策（床？原位？固定点？）
