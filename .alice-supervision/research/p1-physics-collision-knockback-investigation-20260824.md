# P1 物理问题深度调查报告：FakeConnection 阻断位置同步

**调查任务**：`.alice-supervision/research/p1-physics-collision-knockback-investigation-task.txt`  
**触发**：用户 T4 测试发现 bot 穿过玩家 + 击退无位移  
**当前基线**：`65f4863`（测试工具已实施），P1 baseline `2298dd3`  
**调查日期**：2026-08-24  
**方式**：只读；反编译 Forge 1.20.1-47.4.10 源码，读取 Alice BotPlayer/FakeConnection/NATIVE_TRAVEL 实现，未修改代码或运行测试。

---

## 1. 根因诊断

### 问题现象（用户 T4 实测）
1. bot 穿过玩家（一格宽通道堵不住 bot，无碰撞反应）
2. 击退无位移（攻击 bot 后无击退效果）

### 根因：FakeConnection 丢弃所有 S2C 包

**决定性证据**：`src/main/java/com/dddgn/alice/bot/FakeConnection.java:35-37`

```java
@Override
public void send(Packet<?> packet) {
    // 假人不真正发包
}
```

**调用链**：

1. **服务端物理正常执行**：
   - `ServerPlayer.doTick()` → `Player.tick()` → `LivingEntity.tick()` → `LivingEntity.aiStep()`
   - `aiStep` 调用 `travel(Vec3)` 和 `pushEntities()`（反编译确认：`LivingEntity.aiStep` 字节码 1064 行无条件调用 `pushEntities()`）
   - 玩家推挤 bot 时，`Entity.push(Entity)` 修改服务端 bot 的 `deltaMovement` 和位置
   - 玩家攻击 bot 时，`ServerPlayer.hurt()` → `LivingEntity.knockback()` → `setDeltaMovement()`，服务端速度变化

2. **服务端同步包被丢弃**：
   - Minecraft 的实体追踪系统（`ServerEntity`）每 tick 通过 `connection.send(ClientboundMoveEntityPacket)` / `connection.send(ClientboundSetEntityMotionPacket)` 将服务端位置/速度同步给所有观察者的客户端
   - Alice BotPlayer 的 `connection` 字段指向 `FakeConnection`（`BotManager.spawn:72` 调用 `placeNewPlayer(new FakeConnection(...), bot)`）
   - **所有同步包在 `FakeConnection.send()` 空实现中被丢弃**

3. **客户端永不更新 bot 物理状态**：
   - 客户端最后一次收到 bot 位置是 `placeNewPlayer` 的初始位置或玩家主动 `setPos` 的传送
   - 之后 bot 在服务端被推挤/击退，位置和速度变化，但客户端从未收到更新包
   - 客户端渲染的 bot 位置停留在旧位置，**看起来"穿过玩家"和"无击退"**

### 为什么 HARD_PATH 没有此问题

HARD_PATH 使用 `PathExecutor.setPos()` 手动传送，每次到段后**显式调用**：

```java
// PathExecutor.java:85-86
bot.setPos(...);
bot.connection.send(new ClientboundTeleportEntityPacket(bot));
```

显式发送的传送包（`ClientboundTeleportEntityPacket`）虽然也被 `FakeConnection.send()` 丢弃，但 `PlayerList` 在某些情况下会广播实体更新（例如 chunk 加载、维度切换、显式 `teleportTo`）。实际上 HARD_PATH 的客户端同步也不完美，只是传送距离足够大时触发了其他同步路径。

**NATIVE_TRAVEL 的增量位置更新完全依赖 `ServerEntity` 的正常 S2C 包**，`FakeConnection` 阻断了这条路径。

---

## 2. 可修复性判断

### 结论：**可修复**

修复方案有两条路线：

#### 方案 A：广播 bot 位置给真实玩家（推荐）

**原理**：FakeConnection 只需丢弃发给"bot 自己"的包（bot 没有真实客户端），但必须让 `ServerEntity` 广播 bot 的位置/速度给**其他真实玩家**。

**实施**：

1. 保持 `FakeConnection.send()` 为空（bot 自己不需要收包）
2. 在 `BotPlayer.tick()` 或 `SoftPathProbeTask` 的 P1 观测中，每 N tick（例如每 1 tick 或每 3 tick）调用：
   ```java
   ServerEntity tracker = ((ServerLevel) bot.level()).getChunkSource().chunkMap.entityMap.get(bot.getId());
   if (tracker != null) {
       tracker.sendChanges(); // 强制广播位置/速度给所有追踪此实体的客户端
   }
   ```
3. 或者：修改 `FakeConnection.send()` 识别位置/速度包，手动广播给附近真实玩家：
   ```java
   @Override
   public void send(Packet<?> packet) {
       if (packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundSetEntityMotionPacket) {
           // 广播给所有真实玩家
           BotPlayer bot = this.getPlayer();
           if (bot != null) {
               ((ServerLevel) bot.level()).getChunkSource().broadcast(bot, packet);
           }
       }
   }
   ```

**时间**：2-4 小时（反射获取 ServerEntity + 测试 + 客户端验证）

**风险**：ServerEntity 的 tick 与 bot tick 可能不同步，需要确认 `sendChanges` 不重复广播或漏广播。

#### 方案 B：放弃 FakeConnection，改用真实本地回环连接

**原理**：创建真实的本地 Netty 连接（loopback），让 bot 拥有真实的网络栈，服务端正常发包、bot "客户端"正常收包（但丢弃）。

**实施复杂度**：高（需要伪造握手、登录、加密、协议版本，维护双向通道）

**不推荐**：过度工程，且可能引入更多网络层 bug。

---

## 3. 对比表

| 维度 | 原版 ServerPlayer | Alice BotPlayer | 差异根因 |
|---|---|---|---|
| 碰撞检测（服务端） | `LivingEntity.aiStep` → `pushEntities` → `Entity.push` | **相同**（BotPlayer 不覆盖物理方法） | ✅ 服务端碰撞正常 |
| 击退（服务端） | `ServerPlayer.hurt` → `LivingEntity.knockback` → `setDeltaMovement` | **相同** | ✅ 服务端速度变化正常 |
| travel（服务端） | `LivingEntity.aiStep` → `travel` → `move(SELF)` → `collide` | **相同**（NATIVE_TRAVEL 正确调用 `bot.travel`） | ✅ 服务端物理执行正常 |
| 位置同步（S2C） | `ServerEntity.sendChanges` → `connection.send(ClientboundMoveEntityPacket)` → **真实网络** | `ServerEntity.sendChanges` → `FakeConnection.send()` → **丢弃** | ❌ 客户端从不更新 bot 位置 |
| 速度同步（S2C） | `ServerEntity.sendChanges` → `connection.send(ClientboundSetEntityMotionPacket)` | `FakeConnection.send()` → **丢弃** | ❌ 客户端从不更新 bot 速度 |
| hurt 触发 | `isInvulnerableTo` = false（Survival 模式） | **相同**（BotManager.spawn 设置 SURVIVAL） | ✅ hurt 正常触发 |

---

## 4. P1 适用范围（如不修复）

如果不修复 `FakeConnection`，P1 的物理观测仍然有效（服务端日志正确），但**客户端无法验收物理效果**。

P1 可用于：
- **服务端日志验证**：`soft_phys_disruption`、`soft_phys_collision`、`soft_phys_replan` 等日志能正确记录服务端碰撞/击退
- **Headless 测试**：服务端单元测试可验证物理正确性
- **无需客户端可见的场景**（不存在）

P1 **不可用于**：
- 客户端验收（T4 场景）
- 任何需要玩家观察 bot 物理反应的场景
- 多人服（其他玩家看不到 bot 真实位置会导致碰撞箱、交互、PvP 全部错位）

**结论**：不修复则 P1 无法通过客户端验收，必须回退 P0。

---

## 5. 替代方案

### 方案 1：修复 FakeConnection 同步（推荐）

见 §2 方案 A，时间 2-4 小时，可修复。

### 方案 2：回退 P0（冻结 SOFT_SURFACE）

放弃 P1，保持 `soft-probe` / `soft-path-probe` / `follow` 为独立诊断工具，不接入任务链。测试工具（T1-T3）仍可批准，但 P1 标记为"已知限制：客户端不可见物理"。

### 方案 3：降级 P1 为"服务端日志验证专用"

P1 观测代码保留，但文档明确"客户端不验证物理效果，仅依赖服务端日志"。风险：无法排除服务端日志与客户端实际不一致的 bug。

### 方案 4：自研物理（不推荐）

放弃 NATIVE_TRAVEL，自己实现重力、碰撞、击退模拟。工作量巨大且无法保证与原版一致。

---

## 6. 推荐修复方案（精确代码）

### 修改 1：`FakeConnection.java` 识别位置/速度包并广播

```java
@Override
public void send(Packet<?> packet) {
    // 位置/速度包需要广播给真实玩家
    if (packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
        || packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
        || packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket) {
        broadcastToRealPlayers(packet);
    }
    // 其他包丢弃（bot 自己不需要）
}

private void broadcastToRealPlayers(Packet<?> packet) {
    // 需要反射或添加字段存储 bot 引用
    // 伪代码：找到所有真实玩家并发送
    // for (ServerPlayer realPlayer : server.getPlayerList().getPlayers()) {
    //     if (realPlayer != bot && realPlayer.distanceTo(bot) < 192) {
    //         realPlayer.connection.send(packet);
    //     }
    // }
}
```

**问题**：`FakeConnection` 没有 bot 引用，需要修改构造或添加字段。

### 修改 2：`BotPlayer.tick()` 强制广播位置（更简单）

```java
// BotPlayer.java
@Override
public void tick() {
    try {
        super.tick();
        // P1: 强制广播位置给真实玩家
        if (this.tickCount % 1 == 0) { // 每 tick 广播一次
            ServerLevel level = (ServerLevel) this.level();
            level.getChunkSource().broadcast(this, new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(
                this.getId(), this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot(), this.onGround()));
            level.getChunkSource().broadcast(this, new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(
                this.getId(), this.getDeltaMovement()));
        }
    } catch (NullPointerException exception) {
        exception.printStackTrace();
    }
}
```

**优点**：不需要修改 FakeConnection，直接在 BotPlayer 补齐同步。

**时间**：2 小时实施 + 1 小时客户端 T4 复测。

---

## 7. 证据边界

- 已反编译 Forge 1.20.1-47.4.10 `LivingEntity.aiStep`、`Entity.move`、`Player.tick`、`ServerPlayer.hurt`、`LivingEntity.isPushable` 等关键方法，确认 BotPlayer 不覆盖物理方法。
- 已读取 Alice `BotPlayer.java`、`FakeConnection.java`、`SoftMovementPrimitive.java`、`SoftPathProbeTask.java`（P1 观测）实现。
- 已确认 `BotManager.spawn:80` 设置 `GameType.SURVIVAL`，不是 SPECTATOR，`noPhysics` 应为 `false`。
- 已确认 `FakeConnection.send()` 是空实现，所有 S2C 包被丢弃。
- **未运行客户端或服务端实测**；物理执行正常但同步失效的结论基于源码分析，需客户端复测确认修复后 T4 通过。

---

## 8. 最终建议

1. **立即修复 FakeConnection 同步**（方案 A / 修改 2），时间 2-4 小时。
2. 客户端 T4 复测：碰撞（一格宽通道堵 bot）、击退（攻击 bot 后位移）、P1 观测日志（collision/disruption/replan）。
3. 修复通过后，P1 可正式批准并继续 P2（movement primitive）。
4. 如修复失败或用户不批准，回退 P0（冻结 SOFT_SURFACE 接入）。

**本报告不构成实施授权**；修复方案需监督员审核并创建新的 active plan。
