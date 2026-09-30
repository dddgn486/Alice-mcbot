# FTB Chunks 的**权限模型**与**行为拦截**——逐行实测（2026-10-01）

> 触发：用户 2026-10-01「现在要干的事**几乎是权限系统重构**……现在我要引进**新的权限管理方案**，
> 请详细调查，**FTBChunks 是怎么管理权限的，又是怎么实现没有权限的行为拦截**」。
> ⛔ 本轮**零 `src/` 改动**，只读只量。

## §0 取源与版本（可复算）

| 项 | 值 |
|---|---|
| 字节码 | `[fixed-client]/mods/[FTB 区块] ftb-chunks-forge-2001.3.8.jar`（1,159,043 B） |
| 源码 | `https://api.github.com/repos/FTBTeam/FTB-Chunks/tarball/1.20.1/main` ⇒ commit **`9103b7a`** |
| ⭐ **同版核对** | 源码 `gradle.properties:8` = **`mod_version=2001.3.8`** ⇒ **与装好的 jar 逐字同版**（`minecraft_version=1.20.1` · `forge_version=47.1.47`） |
| 依赖 | `ftb-teams-forge-2001.3.2.jar` · `ftb-library-forge-2001.2.13.jar` · `architectury-9.2.14-forge.jar` |
| ⚠️ 网络事实 | 本环境 `api.github.com` **200** · `cursemaven.com` **200** · `repo1.maven.org` **200**；而 `github.com` / `raw.githubusercontent.com` **不可达** ⇒ ⭐ **取源码唯一可用通道 = GitHub tarball API**（`api.github.com/.../tarball/<branch>`） |
| ⚠️ 临时性 | tarball 解在 `/tmp/ftbc-src/FTBTeam-FTB-Chunks-9103b7a/`（**非持久**）⇒ 复算用 §0 的 URL + commit |

⚠️ 本文所有 `文件:行` 都是**上面那棵树**的行号（`common/src/main/java/...` / `forge/src/main/java/...`）。

---

# 第一部分：权限模型

## §1 ⭐ 四层结构（自下而上）

```
① 认领      ClaimedChunk        —— 「这一格**是谁的**」（区块级，维度+区块）
② 队伍      FTB Teams Team      —— 「谁和谁算一伙」（personal team / party，TeamRank）
③ 队伍属性  PrivacyProperty ×N  —— 「这一格的**这个动作**对哪一档人开放」（真正的开关）
④ 服务器配置 FTBChunksWorldConfig —— 全局开关/豁免/上限（管理员）
   ＋ 外部权限  FTB Ranks        —— 4 个 string permission（可选）
```

### §1.1 第①层：认领

- `ClaimedChunkManager`：全局表 `getChunk(ChunkDimPos)`；`ClaimedChunkImpl` 就是 `(ChunkDimPos, ChunkTeamDataImpl)`。
- ⭐ **粒度只到区块，没有子区域、没有多边形、没有高度**（`ClaimedChunk` 接口全文只有 `pos`/`teamData`/签发时间/强加载）。
- ⭐ 因此 FTB 的"区域"**天然是保护区的一个特例**：认领集 ＋ 每格同权（没有 Alice 那套 `L0–L3` 阶梯、也没有工作区域）。

### §1.2 第②层：队伍（FTB Teams）

- 每个认领**必须**属于一个 team；team 分 personal（单人）与 party。
- 判断身份的 API 只有两个：`isAlly(uuid)` 与 `team.getRankForPlayer(uuid)`（`TeamRank` 有序：`OWNER/OFFICER/MEMBER > ALLY > ENEMY/NONE`）。
- 队伍还有 `getExtraData()` 这个**自由 NBT 袋** —— ⭐ FTB Chunks 用它存**管理员绕过开关**（见 §3.2）。

### §1.3 ⭐⭐ 第③层：队伍属性 —— 权限的**真正开关**（13 个）

`api/FTBChunksProperties.java` 全文（13 个）：

| 类型 | 属性 | 语义 |
|---|---|---|
| `PrivacyProperty`（7） | `BLOCK_EDIT_MODE` | 破坏 + 放置（**Forge 有独立的 `BLOCK_EDIT_MODE`**） |
| | `BLOCK_INTERACT_MODE` | 右键交互（Forge 独立；**Fabric 只有合成的 `BLOCK_EDIT_AND_INTERACT_MODE`**） |
| | `BLOCK_EDIT_AND_INTERACT_MODE` | Fabric 的合并档 |
| | `ENTITY_INTERACT_MODE` | 交互实体 |
| | `NONLIVING_ENTITY_ATTACK_MODE` | 左键非生物（画/展示框/盔甲架） |
| | `CLAIM_VISIBILITY` | 认领在地图上是否对非队友可见 |
| | `LOCATION_MODE` | 长距玩家位置可见档 |
| `BooleanProperty`（5） | `ALLOW_EXPLOSIONS` | 允许爆炸破坏地形 |
| | `ALLOW_MOB_GRIEFING` | 允许生物破坏（今天只有末影人） |
| | `ALLOW_PVP` | 允许 PvP |
| | ⭐ `ALLOW_ALL_FAKE_PLAYERS` | 允许**全部**假人 |
| | ⭐ `ALLOW_FAKE_PLAYERS_BY_ID` | 允许"UUID 等于某个真被许可玩家"的假人（**默认 `true`**） |
| `StringListProperty`（1） | ⭐ `ALLOW_NAMED_FAKE_PLAYERS` | 假人**名字或 UUID 字符串**白名单 |

⭐ **`PrivacyMode` 只有三档**（FTB Teams 提供）：`PUBLIC` / `ALLIES` / `PRIVATE`。
⇒ ⭐ **FTB 的权限模型 = 「(动作 × 三档可见性) 的矩阵」，动作是硬编码枚举、档位是队伍自选** ——
⛔ 没有"等级"、没有"每次写入的理由"、没有"额度"。**这是一个"身份制"权限，不是"能力制"权限。**

### §1.4 第④层：服务器配置（`FTBChunksWorldConfig`，34 项，摘保护相关）

| 配置 | 默认 | 作用 |
|---|---|---|
| `DISABLE_PROTECTION` | `false` | ⭐ **一把全局总闸**：true ⇒ `shouldPreventInteraction` 第一行就 `return false` |
| ⭐ `FAKE_PLAYERS`（`EnumValue<ProtectionPolicy>`） | `CHECK` | **假人全局裁定**：`ALLOW` ⇒ 假人全放行；`DENY` ⇒ 假人全拦；`CHECK` ⇒ 落到每队判定 |
| `NO_WILDERNESS` / `NO_WILDERNESS_DIMENSIONS` | `false` / `[]` | 荒野也要认领才能动方块（"无主之地禁止施工"） |
| `PISTON_PROTECTION` | `true` | 活塞跨认领保护 |
| `PROTECT_UNKNOWN_EXPLOSIONS` | `true` | 爆炸源不可判定时（如恶魂火球 `null` 源）仍按保护处理 |
| `PVP_MODE` / `ALLY_MODE` | `ALWAYS` / — | PvP 与盟友关系的**强制档**（可锁死玩家不能自选） |
| `PARTY_LIMIT_MODE` / `HARD_TEAM_*_LIMIT` | `LARGEST` / `0` | 队伍上限怎么算 |
| `MAX_IDLE_DAYS_BEFORE_UNCLAIM` | `0` | 长期不登录自动失去认领 |
| `MAX_PREVENTED_LOG_AGE` | `7` 天 | ⭐ **假人被拦日志**的保留期 |
| `TEAM_PROP_DEFAULTS.*` | 见 §1.3 | 上面 13 个属性的**默认值**（新队伍继承） |

⭐ **外部权限（可选，FTB Ranks）**：`integration/PermissionsHelper.java` 只暴露 **4 个** string permission ——
`ftbchunks.max_claimed` · `ftbchunks.max_force_loaded` · `ftbchunks.chunk_load_offline` · `ftbchunks.no_wilderness`
⇒ ⛔ **全部是"额度/策略"，没有一个管"能不能改这一格"**（改这一格只走 §1.3 的属性）。

### §1.5 ⭐ 三态裁决：`ProtectionPolicy`

```java
public enum ProtectionPolicy {
    CHECK,   // 还要再做一次额外检查（= 由"身份"决定）
    DENY,    // 无条件拒绝
    ALLOW;   // 无条件放行
    public boolean isOverride() { return this != CHECK; }
    public boolean shouldPreventInteraction() { return this == DENY; }
}
```
⭐ **这是整个模型的接缝**：`Protection` 谓词只能回答 `ALLOW`/`DENY`/`CHECK`；
**`CHECK` 的时候谁说了算？—— 队伍身份**（见 §2）。⇒ ⭐ **"规则"与"身份"被一个三态枚举分开了。**

### §1.6 `Protection`：**7 个谓词**，签名统一

`api/Protection.java` 是 `@FunctionalInterface`，签名：
```java
ProtectionPolicy getProtectionPolicy(ServerPlayer player, BlockPos pos, InteractionHand hand,
                                     @Nullable ClaimedChunk chunk, @Nullable Entity entity);
```
7 个公开静态实例：`EDIT_BLOCK` · `INTERACT_BLOCK` · `RIGHT_CLICK_ITEM` · `EDIT_FLUID` ·
`INTERACT_ENTITY` · `ATTACK_NONLIVING_ENTITY` · `EDIT_AND_INTERACT_BLOCK`。

⭐ **三个可复用的设计点**：

1. ⭐ **`chunk == null` 是合法入参**（荒野）⇒ 谓词**不假设"一定在认领里"**，
   所以"数据包白名单标签"在荒野也照样生效（`EDIT_BLOCK` 第 2 行就查 `EDIT_WHITELIST_TAG` 并 `return ALLOW`）。
2. ⭐ **白名单标签优先于身份**：`state.is(EDIT_WHITELIST_TAG)` ⇒ `ALLOW`（任何人都能挖这种方块）；
   黑名单标签（`RIGHT_CLICK_BLACKLIST_TAG`）⇒ `DENY`。**这就是"硬编码规则"那一侧的挂点**。
3. ⭐ **`EDIT_BLOCK` 的三行就是全部**：白名单标签 → 身份够 → `CHECK`。
   ⛔ **它一次都没有问"这一格是不是你的目标"**。

### §1.7 ⭐⭐ 拦截的**唯一裁决函数**

```java
// data/ClaimedChunkManagerImpl.java:200-232
public boolean shouldPreventInteraction(@Nullable Entity actor, InteractionHand hand, BlockPos pos,
                                        Protection protection, @Nullable Entity targetEntity) {
    if (!(actor instanceof ServerPlayer player) || DISABLE_PROTECTION.get() || player.level() == null) return false;   // ①
    boolean isFake = PlayerHooks.isFake(player);
    if (isFake && FAKE_PLAYERS.get().isOverride()) return FAKE_PLAYERS.get().shouldPreventInteraction();               // ②
    ClaimedChunkImpl chunk = getChunk(new ChunkDimPos(player.level(), pos));
    if (chunk != null) {                                                                                              // ③ 在认领里
        ProtectionPolicy policy = protection.getProtectionPolicy(player, pos, hand, chunk, targetEntity);
        boolean prevented = policy.isOverride()
                ? policy.shouldPreventInteraction()
                : !player.isSpectator() && (isFake || !getBypassProtection(player.getUUID()));
        if (prevented && isFake) chunk.getTeamData().logPreventedAccess(player, System.currentTimeMillis());
        return prevented;
    } else if (noWilderness(player)) {                                                                                 // ④ 荒野但禁荒野
        ProtectionPolicy override = protection.getProtectionPolicy(player, pos, hand, null, targetEntity);
        if (override.isOverride()) return override.shouldPreventInteraction();
        else if (!isFake && (getBypassProtection(player.getUUID()) || player.isSpectator())) return false;
        player.displayClientMessage(Component.translatable("ftbchunks.need_to_claim_chunk"), true);
        return true;
    }
    return false;                                                                                                     // ⑤ 荒野 + 无禁荒野 ⇒ 放行
}
```

⭐ **逐条读出来的口径**（每一条都是"可指认的设计决定"）：

| # | 口径 | 含义 |
|---|---|---|
| ① | `!(actor instanceof ServerPlayer)` ⇒ **不拦** | ⛔ 非玩家（活塞、爆炸、生物）**不走这里** ⇒ 它们各自有独立钩子（§3） |
| ① | `DISABLE_PROTECTION` ⇒ 一律不拦 | 一把全局闸 |
| ② | **假人特判在队伍判定之前** | 服务器级 `FAKE_PLAYERS` 是**全局覆盖**，一旦 `ALLOW`/`DENY` 就**不再看队伍属性** |
| ③ | `policy.isOverride()` ⇒ 直接用谓词的答案 | `ALLOW`/`DENY` 都短路 |
| ③ | 否则 `prevented = !isSpectator && (isFake \|\| !bypass)` | ⭐ **旁观者永远不被拦**；⭐ **假人连 `bypass` 都不认** |
| ③ | `if (prevented && isFake) logPreventedAccess(...)` | ⭐ **只有假人有"被拦日志"**，且它**持久化进队伍 NBT**（`ChunkTeamDataImpl:727-753`，`PreventedAccess(name, when)` 记录 + `prunePreventedLog()` 按 `max_prevented_log_age` 清理） |
| ④ | 荒野 + `noWilderness` 且谓词给 `CHECK` ⇒ 拦 + 给玩家一句提示 | ⭐ **谓词的 `ALLOW` 可以让"禁荒野"失效**（白名单方块在荒野仍能挖） |
| ⑤ | 其余一律放行 | ⭐ **默认是"荒野自由"**（与 Alice 的 `NOT_GATED` 同构） |

### §1.8 身份判定：`canPlayerUse`（队伍属性的唯一消费者）

```java
// data/ChunkTeamDataImpl.java:291-305
public boolean canPlayerUse(ServerPlayer player, PrivacyProperty property) {
    PrivacyMode mode = team.getProperty(property);
    if (mode == PrivacyMode.PUBLIC) return true;                       // ① 谁都可以
    if (PlayerHooks.isFake(player)) return canFakePlayerUse(player, mode);   // ② 假人单独一条路
    else if (mode == PrivacyMode.ALLIES) return isAlly(player.getUUID());    // ③ 盟友
    else return team.getRankForPlayer(player.getUUID()).isMemberOrBetter();  // ④ 成员
}
```

## §2 ⭐⭐ 假人（fake player）：**FTB 有一整条独立的路**

```java
// data/ChunkTeamDataImpl.java:307-336
private boolean canFakePlayerUse(Player player, PrivacyMode mode) {
    if (team.getProperty(ALLOW_ALL_FAKE_PLAYERS)) return mode == PrivacyMode.ALLIES;   // ①
    boolean checkById = team.getProperty(ALLOW_FAKE_PLAYERS_BY_ID) && player.getUUID() != null;
    if (mode == PrivacyMode.ALLIES) {
        return checkById && isAlly(player.getUUID()) || fakePlayerMatches(player.getGameProfile());   // ②
    } else if (mode == PrivacyMode.PRIVATE) {
        return checkById && team.getRankForPlayer(player.getUUID()).isMemberOrBetter();                // ③
    }
    return false;
}
private boolean fakePlayerMatches(GameProfile profile) {
    return profile.getName() != null && getCachedFakePlayerNames().contains(profile.getName().toLowerCase(ROOT))
        || profile.getId()   != null && getCachedFakePlayerNames().contains(profile.getId().toString().toLowerCase(ROOT));   // ④
}
```

⭐ **4 条要点**：
1. **`PUBLIC` 档连假人都放行**（在 `canPlayerUse` 第①行就 `return true`，走不到这里）。
2. ⭐ **"允许全部假人"(`ALLOW_ALL_FAKE_PLAYERS`) 只在 `ALLIES` 档生效** ——
   若队伍把 `BLOCK_EDIT_MODE` 设成 `PRIVATE`，**"允许全部假人"也不放行**（保守方向，注释未写但代码如此）。
3. ⭐ **`ALLOW_FAKE_PLAYERS_BY_ID` 默认 `true`**（`DEF_ALLOW_FAKE_PLAYER_IDS = true`）——
   语义 = **"假人的 UUID 只要正好是某个真被许可的玩家（盟友/成员），就放行"**。
   ⇒ 这是给"自动化模组用真玩家 UUID 造假人"准备的通道。
4. ⭐ **白名单匹配"名字 或 UUID 字符串"**，都 `toLowerCase()` 后比对 ⇒ 一张列表两种写法都行。

⭐ **假人身份怎么判**：`PlayerHooks.isFake(player)` —— **不在 FTB 里，在 Architectury 里**：
```
dev/architectury/hooks/level/entity/PlayerHooks.isFake(Player)
  ⇒ forge: PlayerHooksImpl.isFake(Player) = `player instanceof net.minecraftforge.common.util.FakePlayer`
```
（`architectury-9.2.14-forge.jar` 反汇编，`javap -c`）

⇒ ⭐⭐ **这一条把 FTB 的全部假人逻辑挂在"是不是 `net.minecraftforge.common.util.FakePlayer` 的子类"上** —— 见 §4.1。

## §3 两种"绕过"（管理员通道）

### §3.1 全局总闸
- `FTBChunksWorldConfig.DISABLE_PROTECTION = true` ⇒ `shouldPreventInteraction` 第一行返回 `false`
  ⇒ **所有 16 个钩子同时失效**（它们都调这一个函数）。
- ⚠️ 例外：`explosionDetonate`（`:396`）与 `PistonHelper`（`:44`）**各自又查了一遍**这个开关
  ⇒ 三处独立读，**不是一处**。

### §3.2 `bypass_protection`（**按队伍存 NBT**）
```java
// data/ClaimedChunkManagerImpl.java:183-197
public boolean getBypassProtection(UUID player) {
    return teamManager.getPlayerTeamForPlayerID(player)
            .map(team -> team.getExtraData().getBoolean(BYPASS_FTB_CHUNKS_PROTECTION)).orElse(false);
}
public void setBypassProtection(UUID player, boolean bypass) { ... team.getExtraData().putBoolean(...); team.markDirty(); }
```
⭐ 两条易漏的口径：
1. **它不是按玩家存的，是按"玩家所在队伍"存的** ⇒ 同一个队伍的所有人一起获得/失去绕过；
2. ⭐ **`bypass` 对假人无效**（`:215` 的 `(isFake || !bypass)`）⇒ **假人只能靠 §2 那三个属性放行**。

---

# 第二部分：行为拦截

## §4 ⭐⭐ 结论先行：**不是一处拦截，是 14 个事件钩子 + 4 个 mixin 类，且形态有 3 种**

> ⭐ **计数口径（两种口径交叉验证）**：`grep -c '\.register(this::' FTBChunks.java` = **33**，
> 全部落在 `:86-128`；其中**做保护的只有 12 个**（`:104-123`），其余 **21 个是生命周期/簿记**
> （`LifecycleEvent`×2 · `TeamManagerEvent`×2 · `TeamEvent`×11 · `TickEvent`×2 · `CommandRegistrationEvent` 等）。
> 另有 **2 个 Forge 直连**做保护（`FTBChunksForge.java:29-30`）
> ⇒ ⭐ **保护相关事件钩子 = 12 + 2 = 14**。
> Mixin 类共 **10 个**（common 8 ＋ fabric 2），其中**保护相关 4 个**
> （活塞 ×1 · 盔甲架 ×1 · 末影人 ×2，覆盖 **3 类目标**）。

> ⚠️ **我第一遍数错了（如实记）**：先报"19 个事件"，成因 = 用 `sed -n '100,125p'` 读注册块，
> **漏掉了 `:86-102` 那 16 行** ⇒ ⭐ **又是"截断视图"这一族**（与 `O118` / `O14` ② 同因）。
> 正确数是 **33 个注册、其中 12 个保护**。

### §4.1 事件钩子（保护相关 **14** 个）

`FTBChunks.java:86-128` 是**全部注册**（共 33 个，`FTBChunks` 构造器里）；下表只列**做保护的 12 个**：

| # | Architectury 事件 | 处理器:行 | 传的 `Protection` | 拦截形态 |
|---|---|---|---|---|
| 1 | `BlockEvent.BREAK` | `blockBreak:320` | `getBlockBreakProtection()` = `EDIT_BLOCK` | `EventResult.interruptFalse()` |
| 2 | `BlockEvent.PLACE` | `blockPlace:328` | `getBlockPlaceProtection()` = `EDIT_BLOCK` | `interruptFalse()` + **`forceHeldItemSync`** |
| 3 | `InteractionEvent.LEFT_CLICK_BLOCK` | `blockLeftClick:273` | `getBlockBreakProtection()` | `interruptFalse()` |
| 4 | `InteractionEvent.RIGHT_CLICK_BLOCK` | `blockRightClick:283` | `INTERACT_BLOCK` **或** `EDIT_BLOCK`（持方块物品时两条都问） | `interruptFalse()` + **`forceHeldItemSync`** |
| 5 | `InteractionEvent.RIGHT_CLICK_ITEM` | `itemRightClick:302` | `RIGHT_CLICK_ITEM` | `CompoundEventResult.interruptFalse(stack)` |
| 6 | `InteractionEvent.INTERACT_ENTITY` | `interactEntity:312` | `INTERACT_ENTITY` | `interruptFalse()` |
| 7 | `InteractionEvent.FARMLAND_TRAMPLE` | `farmlandTrample:350` | `EDIT_BLOCK` | `interrupt(false)` |
| 8 | `PlayerEvent.FILL_BUCKET` | `fillBucket:339` | `EDIT_FLUID` | `CompoundEventResult.interrupt(false, stack)` |
| 9 | `PlayerEvent.ATTACK_ENTITY` | `playerAttackEntity:133` | `ATTACK_NONLIVING_ENTITY`（**只对非生物**） | `interruptFalse()` |
| 10 | `EntityEvent.LIVING_HURT` | `onLivingHurt:145` | （不走 `Protection`，走 `PVP_MODE`） | `interruptFalse()` |
| 11 | `EntityEvent.LIVING_CHECK_SPAWN` | `checkSpawn:372` | （`chunk.canEntitySpawn(entity)`，今天恒 `true`） | `interrupt(false)` |
| 12 | ⭐ `ExplosionEvent.DETONATE` | `explosionDetonate:395` | （`chunk.allowExplosions()`） | ⭐ **不 cancel —— 见 §4.3** |

**其余 21 个注册全是生命周期/簿记**（⛔ 不参与拦截；列出来防止把注册总数误读成保护面）：
紧邻保护块的 7 个 —— `EntityEvent.ENTER_SECTION:361`（发"进入认领"通知）·
`PlayerEvent.PLAYER_QUIT/PLAYER_CLONE/CHANGE_DIMENSION` · `CommandRegistrationEvent` · `TickEvent.SERVER_POST/PLAYER_POST`；
另有 `:86-102` 的 14 个（`LifecycleEvent`×2 · `TeamManagerEvent`×2 · `TeamEvent`×10）。

**两个 Forge 直连钩子**（绕过 Architectury，`forge/FTBChunksForge.java:29-30`）：
| # | Forge 事件 | 处理器:行 | 为什么直连 |
|---|---|---|---|
| 13 | `PlayerInteractEvent.EntityInteractSpecific` | `:44` | 注释逐字：「Temporary hack until Architectury hopefully adds direct support for this. **Needed to prevent interaction with Armor Stands**… NOTE: currently **broken** in 1.18.2 due to a Forge bug [#8143]」 |
| 14 | `EntityMobGriefingEvent` | `:51` | 只对 `ENTITY_MOB_GRIEFING_BLACKLIST_TAG`（默认只有末影人）生效，「we could do this for all mob griefing but that's arguably OP (could trivialize wither fights)」 |

⭐ **Architectury → Forge 的映射已核实**（`javap -c` 反汇编 `architectury-9.2.14-forge.jar` 的
`dev/architectury/event/forge/EventHandlerImplCommon.class`，它引用的 Forge 事件类）：
`BlockEvent.BREAK/PLACE → net.minecraftforge.event.level.BlockEvent` ·
`InteractionEvent.* → net.minecraftforge.event.entity.player.PlayerInteractEvent` ·
`FILL_BUCKET → FillBucketEvent` · `ATTACK_ENTITY → AttackEntityEvent` ·
`EXPLOSION_DETONATE → net.minecraftforge.event.level.ExplosionEvent` ·
`LIVING_CHECK_SPAWN → MobSpawnEvent` · `LIVING_HURT → LivingAttackEvent`。

### §4.2 Mixin 钩子（保护相关 4 个类 / 3 类目标）

| Mixin | 目标 | 注入点 | 为什么必须 mixin |
|---|---|---|---|
| `core/mixin/PistonBaseBlockMixin` | `PistonBaseBlock.moveBlocks` | `@At(INVOKE PistonStructureResolver.getToPush())` ⇒ `cir.setReturnValue(false)` | ⛔ **活塞移动不产生任何"玩家动作事件"** ⇒ 事件总线接不到 |
| `core/mixin/ArmorStandMixin` | `ArmorStand.interactAt` | `@At("HEAD")` ⇒ `cir.setReturnValue(InteractionResult.FAIL)` | 注释逐字：「**this is a hack, but necessary** since Forge's `PlayerInteractEvent.EntityInteractSpecific` event is currently **broken**… and Architectury doesn't currently handle this event at all」 |
| `fabric/mixin/EndermanLeaveBlockMixin` · `EndermanTakeBlockMixin` | 末影人 | — | Fabric 侧无法用 `EntityMobGriefingEvent` |

⭐ **活塞的判据是另一套**（`util/PistonHelper.java:44-72`）：**不看玩家身份，看"源认领 vs 目标认领是否同队"**
```java
prevent(editProp, srcClaim, dstClaim) {
    if (srcClaim != dstClaim && dstClaim != null) {
        return !srcTeamId.equals(dstTeamId) && dstClaim.team.getProperty(editProp) != PrivacyMode.PUBLIC;
    }
    return false;
}
```
⇒ ⭐ **"公开的认领"(`PUBLIC`) 允许活塞跨队推** ⇒ **`PrivacyMode` 在这里被当成"跨队通行的通行证"**。

### §4.3 ⭐ 三种拦截**形态**（不是一种）

| 形态 | 用到哪 | 说明 |
|---|---|---|
| **A · 取消事件** | 破坏/放置/交互/攻击/踩踏/装桶/PvP/生成 | `event.setCanceled(true)` / `interruptFalse()` ⇒ 动作**根本没发生** |
| ⭐ **B · 事后重写列表** | **爆炸**（`:395-414`） | **不取消爆炸**：先 `explosion.clearToBlow()`，再**逐格**判 `chunk == null \|\| chunk.allowExplosions()`，**只把允许的格子重新加回** `getToBlow()` |
| ⭐ **C · mixin 覆盖返回值** | 活塞 · 盔甲架 | 注入到**原版方法内部**，直接改返回 |

⭐ **形态 B 是这份代码里最有价值的一处**（理由）：爆炸的受影响格是**一批**，
用 A 只能"全拦/全放" —— 而 FTB 需要**按区块**逐格决定 ⇒ ⭐ **"批量动作"必须用"过滤"而不是"取消"**。
⚠️ **前置跳过**（`:387-393`）逐字：`explosion.getToBlow().isEmpty()` ⇒ 跳过；
`explosion.source == null && !PROTECT_UNKNOWN_EXPLOSIONS.get()` ⇒ 跳过。
⇒ ⭐ **口径 = 「来源不可判定」时由配置决定"保护还是放行"**（恶魂火球是典型：vanilla 给的是 `null` 源）。

### §4.4 ⭐ 一个容易漏的收尾：**取消之后要修客户端同步**

`FTBCUtils.java:24-31`：
```java
public static void forceHeldItemSync(ServerPlayer sp, InteractionHand hand) {
    if (sp.connection != null) switch (hand) {
        case MAIN_HAND -> sp.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, sp.getInventory().selected, sp.getItemInHand(hand)));
        case OFF_HAND  -> sp.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, Inventory.SLOT_OFFHAND, sp.getItemInHand(hand)));
    }
}
```
⛔ **调用点只有 3 个，全是"取消"的那三条**：`blockPlace:332` · `blockRightClick:294` · `itemRightClick:304`。
（`windowId = -2` = 玩家背包。）
⭐ **背景（写入代码里的是一条工单）**：`blockRightClick:287-289` 注释逐字 ——
「not ideal since it also prevents right-clicking *any* blocks if holding a block item when block placement is prevented
but necessary — [FTB-Mods-Issues#1752]」。
⇒ ⭐ **"服务端取消了互动，客户端却以为发生了" 是这类拦截的必然副作用，FTB 的解法是补一条槽位同步包。**

### §4.5 ⭐⭐ 拦截的**覆盖边界**（FTB 自己知道自己的洞）

`ThirdPartyProtection`（Alice）的 javadoc 已记过：**批量写路径不会触发 Forge 事件**。这里从 FTB 侧再确认一次：

- 破坏事件挂在 `ServerPlayerGameMode` 那条路上（`BlockEvent.BreakEvent`）；
- ⛔ `Level.destroyBlock` / `level.setBlock` **不发** BreakEvent/EntityPlaceEvent；
- ⇒ **任何"绕过原版交互"的写入（Alice 的 `placeBulkEdit`/`breakForBulkEdit`/`setBlock`）FTB 完全看不见**；
- ⭐ **FTB 对这一点的唯一自救 = §4.3 的形态 B**（爆炸那条自己重写列表），**别的路径没有兜底**。

---

# 第三部分：与 Alice 的对照

## §5 逐层对照表

| 维度 | FTB Chunks | Alice 今天 |
|---|---|---|
| 区域定义 | `ClaimedChunk`（**只有区块级**） | `SafeZoneData` 认领集（区块级、忽略 Y） ＋ ⭐ 任务区封套（区块 hull） |
| 子区域 | ⛔ 无 | ✅ 安全区（`safeClaims`） |
| 归属主体 | **队伍**（personal/party） | **单个 UUID**（owner） |
| 身份档位 | `PrivacyMode` 三档（PUBLIC/ALLIES/PRIVATE） | `WritePolicyMatrix.Level` 四档（L0–L3，**按任务类别**给） |
| 权限开关粒度 | **（动作 × 档位）矩阵**，13 个队伍属性 | **（Zone × Task）矩阵 24 行** ＋ 4 个等级 |
| 判据函数 | `shouldPreventInteraction`（**1 个**） | `ZoneAuthority.authorize`（**1 个**） |
| 判据入参 | `(actor, hand, pos, Protection, targetEntity)` | `(level, owner, pos, reason, act)` |
| 三态 | `ALLOW`/`DENY`/`CHECK` | `NOT_GATED`/`ALLOW`/`DENY` |
| 拒绝码 | ⛔ **无**（只有布尔 + 一句 `need_to_claim_chunk` 提示） | ✅ **10 个稳定码**（`protected_area` 等） |
| 额度 | ⛔ 无（"改这一格"维度没有预算） | `WriteBudget` 三维（breaks/places/containers） |
| 理由 | ⛔ 无（`Protection` 谓词不看"为什么"） | ✅ `WriteReason` 15 值 |
| 拦截点 | ⭐ **14 个事件钩子 + 4 个 mixin 类**，事件驱动 | **6 个调用点**（`BlockBreakSafety`·`BlockInteraction`×2·`PathSession`·2 个 CandidateSource） |
| 批量路径 | ⚠️ **只对爆炸做了兜底**，其余裸奔 | ⚠️ 三条批量写路径自己补了 `ThirdPartyProtection`（**问 FTB**） |
| 假人 | ⭐ **一等公民**（3 个专门属性 + 专属日志 + 全局覆盖 + 独立分支） | ⚠️ bot 就是 bot（`BotPlayer extends ServerPlayer`） |
| 旁观者 | ⭐ **永远不被拦**（`!player.isSpectator()`） | ⚠️ 无此概念 |
| 管理员绕过 | 按**队伍**存 NBT（`BYPASS_FTB_CHUNKS_PROTECTION`） | 无（`/alice` 命令） |
| 日志/审计 | ⭐ 假人被拦**持久化进队伍 NBT**，按 7 天清理 | `ZoneAuthority.logAllow`（**只记放行**、内存去重表、上限 512） ＋ `[WRITE-REFUSED]` |
| 客户端同步 | ⭐ **取消后补发槽位包** | ⚠️ 无对应物 |

## §6 ⭐⭐ 对 Alice 最要紧的三条事实

### §6.1 ⛔ **Alice 的 bot 在 FTB 眼里不是假人，是真人**

| 事实 | 证据 |
|---|---|
| Alice 的 bot 类 | `bot/BotPlayer.java:38` `public class BotPlayer extends ServerPlayer`（javadoc 逐字：「直接继承 `ServerPlayer`（mc_aiplayer 同款方案）。**相比旧实现（Forge `FakePlayer`，无网络连接 → 客户端不可见 + tick 部分路径 NPE）**」） |
| FTB 的假人判据 | `PlayerHooks.isFake` ⇒ Forge `player instanceof net.minecraftforge.common.util.FakePlayer` |
| ⇒ 结论 | ⭐ **`isFake == false`** |

⭐ **四条直接后果**（全部可指认）：

1. **服务器配置 `fake_players` 对 Alice 完全无效**（`ClaimedChunkManagerImpl:206` 的 `if (isFake && …)` 进不去）
   ⇒ ⛔ **改 `fake_players` 不能给 Alice 放行，也不能拒绝它**。
2. **队伍属性 `ALLOW_ALL_FAKE_PLAYERS` / `ALLOW_NAMED_FAKE_PLAYERS` / `ALLOW_FAKE_PLAYERS_BY_ID` 同样无效**
   （`ChunkTeamDataImpl:298` 的 `if (PlayerHooks.isFake(player))` 进不去）
   ⇒ ⛔ **把 bot 的名字加进 `allow_named_fake_players` 也不会生效**。
3. **判据退化为"bot 的 UUID 是不是该队伍的成员或盟友"**（`ChunkTeamDataImpl:300-303`）——
   Alice 的 bot UUID 显然不是 ⇒ `canPlayerUse` = `false` ⇒ `EDIT_BLOCK` = `CHECK`
   ⇒ `prevented = !isSpectator && (false || !bypass)` = **`true`（拦）**，除非 §3.2 的 bypass。
4. **没有"假人被拦日志"**（`:216` 的 `if (prevented && isFake)`）⇒ ⛔ 排查时 FTB 侧**不留痕**。

⇒ ⭐ **今天给 Alice 放行的真实抓手只有三个**（按代价升序）：
`(a)` 服务器 `disable_protection=true`（**全局**，会关掉所有人的保护）；
`(b)` 给 bot 所在队伍打 `bypass`（⛔ 但 bot 没有队伍 ⇒ `getPlayerTeamForPlayerID(botUUID)` 大概返回空
⇒ **`setBypassProtection` 也是空操作** ⇒ ⚠️ 这条路需要先让 bot 有队伍）；
`(c)` ⭐ 把 `BLOCK_EDIT_MODE` 设成 `PUBLIC`（**整片认领对所有人开放**），
或者 **让 bot 加入玩家的队伍**（真实做法，但会改 FTB Teams 的数据）。

⚠️ **`(c)` 的第二半是唯一"正确"的做法，但它要求 Alice 去写 FTB Teams 的数据** ——
⛔ 与 `FtbChunksBridge` 的"写路径一个字都不碰"（`D-326`）**直接冲突** ⇒ **这是一条需要用户裁定的边界。**

### §6.2 ✅ FTB 的"拦截是事件驱动"与我们已实测的一致

`ThirdPartyProtection` 的 javadoc 逐字：「三条**批量写**路径……走的是**世界底层写入**，而 Forge 的破坏/放置事件由
`ServerPlayerGameMode` 触发（已反汇编核实：`Level.destroyBlock` **不**触发 `BlockEvent.BreakEvent`）
⇒ **FTB 认领、以及任何靠 Forge 事件做保护的模组，对这三条路径完全不可见**」。
⇒ ⭐ **本次从 FTB 侧独立复核，结论一致**（§4.5），并补一条新证据：**FTB 自己也知道**，所以它给爆炸单独写了形态 B。

### §6.3 ⭐ FTB 没有的三样东西（正好是 Alice 的强项，也是重构要保住的）

| FTB 没有 | Alice 有 | 为什么不能丢 |
|---|---|---|
| **拒绝码**（只有布尔） | 10 个稳定码 | 归因/日志/夹具断言都靠它；`D-341`（"无权"≠"没有"）正是靠 `permanentDenial(code)` 判的 |
| **写入理由**（`Protection` 谓词是 `(player,pos,hand,chunk,entity)`，**没有"为什么"**） | `WriteReason` 15 值 | `INVESTIGATION`/`A2` 已经量出：**"目标内/非目标"这条轴**今天挂在理由上 |
| **额度**（改方块的次数） | `WriteBudget` 三维 | `D-372`/`D-241` 一整套裁定挂在这里 |

⭐ 反过来说，**FTB 有的是 Alice 没有的**：
| FTB 有 | Alice 没有 | 价值 |
|---|---|---|
| **身份是一等维度**（队伍 × 档位） | 只有"单 UUID owner" | 多 bot / 多玩家 / 服务端场景 |
| ⭐ **假人是一等公民**（3 属性 + 专属日志 + 全局覆盖） | bot 与真人同构 | **自动化玩家**的核心场景 |
| ⭐ **旁观者永远放行** | — | 一行，但省掉一整类麻烦 |
| ⭐ **管理员 bypass 按队伍持久化** | — | 可运维 |
| ⭐ **"取消后补发同步包"** | — | 橡皮筋/客户端不同步的直接解药 |
| ⭐ **批量动作的"过滤"形态（爆炸）** | — | 我们的批量写路径**正是**这个形状 |

## §7 可借鉴 / 不可直接借鉴（判断 + 代价 + 替代 + 推荐）

| # | FTB 的做法 | 我的判断 | 代价 | 推荐 |
|---|---|---|---|---|
| 1 | **三态 `ALLOW/DENY/CHECK` 把"规则"与"身份"分开** | ⭐ **值得借鉴** —— 这正是我们缺的那条接缝：`ZoneAuthority` 今天**既算规则又算身份**（`zone.level()` + `covers` 混在一个函数里） | 低：只改判据的**出口类型**，不动拦截点 | ⭐ **推荐**（但保留我们的码：三态里 `DENY` 要带码） |
| 2 | **`chunk == null` 是合法入参**（谓词不假设"在区内"） | ⭐ **值得借鉴** | 低 | ⭐ **推荐** —— 我们今天是靠 `NOT_GATED` 早退，语义等价但**调用方拿不到"荒野专用规则"** |
| 3 | **假人一等公民**（身份维度里显式列出） | ⭐ **强烈建议** —— ⛔ 但不是照抄：FTB 的假人判据（`instanceof FakePlayer`）**对 Alice 直接失效**（§6.1） | 中：要给 bot 一个**自己的身份档**（而不是蹭"假人"） | ⭐ **推荐："bot 身份"必须是显式的一档**，⛔ 不要复用 Forge 的 `FakePlayer` |
| 4 | **批量动作走"过滤"不走"取消"** | ⭐ **值得借鉴** | 中：要有"逐项重写"的入口 | ⭐ **推荐** —— 我们的 `placeBulkEdit`/`breakForBulkEdit` 天生是这个形状 |
| 5 | **取消后补发槽位同步包** | ⭐ **值得借鉴** | 低 | ⭐ **推荐**（与我们的橡皮筋问题同族） |
| 6 | **假人被拦日志持久化 + 过期清理** | ⭐ 值得借鉴（可运维） | 低 | 视需要 |
| 7 | **活塞的"跨认领"判据（同队/PUBLIC）** | ⚠️ **不适用于 Alice 今天**（我们没有活塞保护需求） | — | ⛔ 暂不做 |
| 8 | **14 个事件钩子** | ⛔ **不要照抄数量** —— FTB 多钩子是因为它**没有统一写入口**；Alice **已经在 `BlockInteraction` 收口**了 | 高（14 个钩子 = 14 个漂移点，`D-338` 附注十的教训） | ⛔ **不借鉴** —— 我们该做的是**把现有 6 个调用点收成 1 个** |
| 9 | **`PrivacyMode` 三档** | ⛔ 与我们四档 `L0–L3` 不同构，**不要合并** | — | ⛔ 不借鉴（FTB 的档位是"可见性"，我们的是"能力等级"） |
| 10 | **`bypass` 按队伍存 NBT** | ⚠️ **可借鉴形状，但语义要改**：我们的是**单 UUID owner**，不存在"队伍" | 低 | 换载体后可用 |
| 11 | **`Protection` 谓词没有"理由"** | ⛔ **这是 FTB 的缺口，不是优点** —— 我们已实测"目标内/非目标"这条轴必须有**理由**承载（`A2` §5.4） | — | ⛔ 不要学 |

## §8 本轮的量测边界

| 项 | 状态 |
|---|---|
| 四层模型 / 13 个队伍属性 / 三态 / 唯一裁决函数 / 14 个事件钩子 / 4 个 mixin 类 / 3 种拦截形态 | ✅ **源码逐行实测**（同版 tarball，`mod_version=2001.3.8`） |
| `PlayerHooks.isFake` 的 Forge 实现 | ✅ **字节码反汇编实测**（`javap -c`） |
| Architectury → Forge 事件映射 | ✅ **反汇编实测**（`EventHandlerImplCommon` 引用的 Forge 类） |
| 「Alice 的 bot `isFake == false`」 | ✅ **两个独立事实合成**：`BotPlayer extends ServerPlayer`（源码）＋ `isFake = instanceof FakePlayer`（字节码） |
| ⛔ **未做** | **真机验证**（没在客户端里让 bot 去挖别人的认领看 FTB 拦不拦）；**`getPlayerTeamForPlayerID(botUUID)` 对 bot 返回什么**（⚠️ §6.1 的 `(b)` 结论**依赖这个未验证的前提**）；FTB Teams 侧的数据模型（只看了 API 面） |
| ⚠️ 提醒 | §6.1 的 4 条后果**全部是"按代码应该如此"**，⛔ 不是实测行为 ⇒ 要动手前**必须先做一次真机对照**（bot 挖自己队认领 / 挖别人认领） |
