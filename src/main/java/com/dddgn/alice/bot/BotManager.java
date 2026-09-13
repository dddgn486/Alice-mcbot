package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.TargetPacket;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.MineTask;
import com.dddgn.alice.task.PlaceTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.TransferTask;
import com.dddgn.alice.task.TraverseDiagnosticTask;
import com.dddgn.alice.task.DiagonalDiagnosticTask;
import com.dddgn.alice.task.AscendDiagnosticTask;
import com.dddgn.alice.task.ChainDiagnosticTask;
import com.dddgn.alice.task.DescendDiagnosticTask;
import com.dddgn.alice.task.PathingBatteryTask;
import com.dddgn.alice.task.PathSessionDiagnosticTask;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferLedgerData;
import com.dddgn.alice.transfer.TransferRequest;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.dddgn.alice.pathing.MovementHelper;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 假人管理器：负责 Bot 的生命周期和任务调度。
 * 
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>生命周期管理</b>：spawn()、remove()、持久化（saveToWorld/restoreFromWorld）</li>
 *   <li><b>任务调度</b>：BotSession.tick() 驱动任务执行，监听 ServerTickEvent.Phase.END</li>
 *   <li><b>生存系统</b>：SurvivalSystem.tick() 检查危险状态，必要时中断任务</li>
 * </ul>
 * 
 * <h3>不负责的事项</h3>
 * <ul>
 *   <li>❌ 物理计算：由 BotPlayer.tick() → aiStep() → travel() 处理</li>
 *   <li>❌ 输入控制：由 BotController.onUpdate() 设置输入字段</li>
 *   <li>❌ 击退/受伤：由 BotPlayer.hurt()/knockback() 处理</li>
 * </ul>
 * 
 * <h3>设计原则</h3>
 * <p>
 * BotManager 只管理"高层逻辑"（任务、生存），不干预"底层物理"（移动、碰撞）。
 * 这样职责清晰，避免相互干扰。
 * </p>
 * 
 * <h3>历史遗留问题（已修复）</h3>
 * <ul>
 *   <li>❌ C-1 兜底消费段（已移除）：曾在 tick 末尾清空 deltaMovement，导致击退失效</li>
 *   <li>✅ 原版物理已足够处理残留速度（摩擦力自然衰减）</li>
 * </ul>
 * 
 * @see BotPlayer Bot 实体（负责物理和输入）
 * @see BotController 输入控制器（负责设置移动输入）
 * @see BotSession 任务会话（负责单个 Bot 的任务执行）
 */
public final class BotManager {
    private static final long TRANSFER_MAX_SUSPENSION_TICKS = 12_000L;

    /** 所有在线假人。 */
    private static final Map<UUID, BotSession> BOTS = new HashMap<>();

    private BotManager() {
    }

    // ==================== Bot 查询方法（遥控器需要） ====================
    
    /**
     * 根据 UUID 获取 Bot。
     * 
     * @param uuid Bot 的 UUID
     * @return BotPlayer 实例，如果不存在返回 null
     */
    public static BotPlayer getBot(UUID uuid) {
        BotSession session = BOTS.get(uuid);
        return session != null ? session.bot : null;
    }
    
    /**
     * 获取所有在线的 Bot。
     * 
     * @return Bot 列表（不可修改）
     */
    public static java.util.Collection<BotPlayer> getAllBots() {
        return BOTS.values().stream()
                .map(session -> session.bot)
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * 获取第一个 Bot（用于单 Bot 测试）。
     * 
     * @return 第一个 Bot，如果没有返回 null
     */
    public static BotPlayer first() {
        return BOTS.values().stream()
                .map(session -> session.bot)
                .findFirst()
                .orElse(null);
    }

    /** 在指定位置生成假人:玩家化注册(PlayerList) + 传送 + 强制生存。 */
    public static BotPlayer spawn(ServerLevel level, BlockPos pos, String name) {
        return spawn(level, pos, name, UUID.randomUUID());
    }

    /** 生成假人(可指定 UUID,用于从存档恢复)。生成即写世界存档。 */
    public static BotPlayer spawn(ServerLevel level, BlockPos pos, String name, UUID uuid) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(uuid, name);
        BotPlayer bot = new BotPlayer(server, level, profile);

        // 伪造客户端连接 → PlayerList.placeNewPlayer 注册(填充 connection + 广播给玩家)
        // P1 客户端同步修复：传入 bot 引用以便广播位置/速度包
        server.getPlayerList().placeNewPlayer(new FakeConnection(PacketFlow.SERVERBOUND, bot), bot);

        // 传送到位(placeNewPlayer 默认放在出生点,需再定位)
        bot.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        // 强制生存:创造模式破方块不掉落、冒险模式禁止破坏(让采集/建造失效)
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        // 注意:不再固定发工具——主手由「行为执行时替换」管理(MineTask 临时设定),
        // 退出重进不再莫名多一把镐(存档按需还原主手,见 saveToWorld)。

        // F1 修复：清除 ServerPlayer 出生保护，使 bot 立即可伤害（方案 A）。
        // 开发映射名为 spawnInvulnerableTime，标准发行运行时为 f_8921_；反射不会自动重映射。
        try {
            java.lang.reflect.Field field = findServerPlayerField(
                    net.minecraft.server.level.ServerPlayer.class,
                    "spawnInvulnerableTime", "f_8921_");
            field.setAccessible(true);
            field.setInt(bot, 0);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to clear spawn invulnerability for bot", e);
        }

        BOTS.put(bot.getUUID(), new BotSession(bot));
        saveToWorld(bot);
        BotLog.info("假人已生成(玩家化): name={} pos={}", name, pos.toShortString());
        // 基-4：**重启后第一次出场**时，如实汇报"重启丢了什么"（未决请示作废 / 重启前的任务未续做）。
        // 只入事件环 + 日志 + trace，**不通知决策层**（否则每次开服都白花一次 LLM 调用）。
        reportRestartState(bot);
        // J7 Step 3（D-127）：bot 变得可用时检查"上次会话没拆完的脚手架"
        tryRecoverUnfinishedTeardown(bot);
        return bot;
    }

    /**
     * **重启状态报告**（基-4，一次性）：读取 {@code DecisionState} 里登记下来的"未决请示 / 正在跑的任务"，
     * 如实说出它们**没有**被恢复，然后清掉登记（第二次调用为空 ⇒ 天然幂等，不会每次生成 bot 都刷）。
     *
     * <p>语义见 {@code DecisionState} 的类文档：未决请示一律作废（它承诺的那个任务已不存在）、
     * 任务不自动续做（任务树不持久化）—— 这里只负责**把它说出来**。
     */
    private static void reportRestartState(BotPlayer bot) {
        String report = com.dddgn.alice.decision.DecisionState.get(bot.getServer())
                .consumeRestartReport(bot.getUUID());
        if (report.isBlank()) {
            return;
        }
        BotLog.warn("[DecisionState] bot={} {}", bot.getName().getString(), report);
        com.dddgn.alice.decision.DecisionTrace.lifecycle(bot, "restart", "重启状态报告", report);
        com.dddgn.alice.decision.DecisionEvents.record(bot, "RESTART", "warn",
                "重启状态：未恢复上次的请示/任务", report);
    }

    /** 移除假人(实体 + PlayerList + 世界存档记录)。 */
    public static void remove(BotPlayer bot) {
        SurvivalSystem.forget(bot);
        com.dddgn.alice.decision.PermissionGate.forget(bot.getUUID());
        com.dddgn.alice.decision.EventThresholds.forget(bot.getUUID());
        MenuLifecycle.closeOpen(bot, "bot_removed");
        MenuLifecycle.forget(bot.getUUID());
        BotSession session = BOTS.remove(bot.getUUID());
        if (session != null) {
            if (session.task instanceof TransferTask transfer) {
                transfer.botRemoved();
            }
            session.clearTask(); // 任务收尾 + 广播清除高亮
        }
        if (!bot.isRemoved()) {
            bot.getServer().getPlayerList().remove(bot);
            bot.discard();
        }
        BotWorldData.get(bot.getServer()).clearBot();
    }

    /** 把假人主手物品同步给客户端(Inventory.setItem 不会自动发包,玩家侧看不到)。 */
    public static void syncMainHand(net.minecraft.server.level.ServerPlayer bot) {
        // ✅ 修复：直接广播装备包给追踪 bot 的玩家（绕过 FakeConnection）
        // 不能通过 bot.connection.send()，因为 FakeConnection 会丢弃装备包（防止玩家快捷栏混乱）
        
        if (bot.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket equipmentPacket = 
                    new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                            bot.getId(), 
                            java.util.List.of(
                                    com.mojang.datafixers.util.Pair.of(
                                            net.minecraft.world.entity.EquipmentSlot.MAINHAND, 
                                            bot.getInventory().getItem(bot.getInventory().selected))));
            
            // 直接广播给追踪 bot 的玩家（绕过 FakeConnection）
            serverLevel.getChunkSource().broadcast(bot, equipmentPacket);
        }
    }

    private static java.lang.reflect.Field findServerPlayerField(Class<?> type, String... names)
            throws NoSuchFieldException {
        for (String name : names) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 尝试下一种映射命名。
            }
        }
        throw new NoSuchFieldException(java.util.Arrays.toString(names));
    }

    /** 把假人概要状态写入世界存档(重启恢复用)。 */
    public static void saveToWorld(BotPlayer bot) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", bot.getUUID());
        tag.putString("Name", bot.getName().getString());
        ListTag pos = new ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(bot.getX()));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(bot.getY()));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(bot.getZ()));
        ListTag rot = new ListTag();
        rot.add(net.minecraft.nbt.FloatTag.valueOf(bot.getYRot()));
        rot.add(net.minecraft.nbt.FloatTag.valueOf(bot.getXRot()));
        tag.put("Pos", pos);
        tag.put("Rotation", rot);
        tag.putString("GameMode", bot.gameMode.getGameModeForPlayer().getName());
        tag.putFloat("Health", bot.getHealth());
        // 主手物品(行为替换的工具也存进去,退出重进手里保持原样)
        ItemStack mainHand = bot.getInventory().getItem(bot.getInventory().selected);
        if (!mainHand.isEmpty()) {
            tag.put("MainHand", mainHand.save(new CompoundTag()));
        }
        BotWorldData.get(bot.getServer()).setBot(tag);
    }

    /** 服务器启动后:有存档假人则恢复(位置/朝向/游戏模式)。 */
    public static void restoreFromWorld(MinecraftServer server) {
        CompoundTag tag = BotWorldData.get(server).botTag();
        if (tag == null) {
            BotLog.info("无存档假人,跳过恢复");
            return;
        }
        float savedHealth = tag.contains("Health") ? tag.getFloat("Health") : 20.0f;
        if (savedHealth <= 0.0f) {
            BotLog.info("存档假人已死亡 (health={}),跳过恢复并清除存档", savedHealth);
            BotWorldData.get(server).clearBot();
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
        bot.setYRot(rot.getFloat(0));
        bot.setXRot(rot.getFloat(1));
        switch (tag.getString("GameMode")) {
            case "creative" -> bot.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            case "adventure" -> bot.gameMode.changeGameModeForPlayer(GameType.ADVENTURE);
            case "spectator" -> bot.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
            default -> bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        }
        // 还原主手物品(没有存档则空手)
        if (tag.contains("MainHand")) {
            bot.getInventory().setItem(bot.getInventory().selected,
                    ItemStack.of(tag.getCompound("MainHand")));
            syncMainHand(bot);
        }
        saveToWorld(bot); // 刷新存档为精确位置
        BotLog.info("已从世界存档恢复假人: name={} pos=({}, {}, {})",
                name, (int) pos.getDouble(0), (int) pos.getDouble(1), (int) pos.getDouble(2));
    }

    /** 只读查询指定维度中的一个假人；无假人时返回 null，不生成实体。 */
    public static BotPlayer firstInLevel(ServerLevel level) {
        for (BotSession session : BOTS.values()) {
            if (session.bot().level() == level) {
                return session.bot();
            }
        }
        return null;
    }

    /** 取一个假人(无则生成,用于测试命令)。 */
    public static BotPlayer firstOrSpawn(ServerLevel level, BlockPos pos) {
        for (BotSession session : BOTS.values()) {
            if (session.bot().level() == level) {
                return session.bot();
            }
    }
    // 出生在目标上方(而非目标方块内部,避免卡进方块窒息)
    return spawn(level, pos.above(), "Alice");
}

/** 给假人分配保护区内软移动跟随，不接入普通挖矿。 */
/**
     * 迁移期开关（D-045 / 方案 B）：**legacy 寻路内核驱动的任务暂时禁用**。
     *
     * <p>legacy 内核（`pathing/AStarPathfinder` 零启发 Dijkstra + `pathing/movement/*` + `PathExecutor`）
     * 与 R3/R4 新内核并存；用户决定先禁用这些任务，等新内核逐个接入后再启用。
     * 新内核测试面（`alice:pathing_*` 系列）不受影响。
     */
    public static final boolean LEGACY_PATHING_TASKS_ENABLED = false;

    /** legacy 任务入口的统一门禁：返回 true 表示已拒绝。 */
    private static boolean legacyTaskDisabled(String task) {
        if (LEGACY_PATHING_TASKS_ENABLED) {
            return false;
        }
        BotLog.warn("legacy_pathing_disabled task={} reason=migration_to_core_kernel", task);
        return true;
    }

    /** Follow 已迁移到新内核（D-062），不再走 legacy 门禁。 */
    public static boolean assignFollow(BotPlayer bot, ServerPlayer target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return false;
        return session.assignFollow(target);
    }

    /** 取消当前跟随；其他任务不受此入口影响。 */
    public static boolean stopFollow(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || !(session.task instanceof com.dddgn.alice.task.FollowTask follow)) {
            return false;
        }
        follow.cancel();
        BotLog.info("[Follow] SUMMARY stopped {}", follow.summary());
        session.complete("follow_stopped", TaskExecutionRecord.TerminalStatus.CANCELLED_FOLLOW);
        return true;
    }

    /** 给假人分配独立「放置指定方块」任务（已迁移到新内核，D-063）。 */
    public static boolean assignPlace(BotPlayer bot, BlockPos target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return false;
        return session.assignPlace(target);
    }

    /** 给假人分配纯 HARD_PATH 脚位移动任务。 */
    /** WalkTo 已迁移到新内核（D-060），不再走 legacy 门禁。 */
    public static boolean assignWalkTo(BotPlayer bot, BlockPos goalFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return false;
        session.assignWalkTo(goalFoot);
        return true;
    }

    public static boolean assignWalkToDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.WalkToDiagnosticTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.WalkToDiagnosticTask.OVER_WALL_GOAL));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the focused R2-B one-step Traverse diagnostic. */
    public static boolean assignTraverseDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new TraverseDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the focused R2-C one-step Diagonal diagnostic. */
    public static boolean assignDiagonalDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new DiagonalDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the focused R2-C one-step Ascend diagnostic. */
    public static boolean assignAscendDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new AscendDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the focused R2-C one-step Descend diagnostic. */
    public static boolean assignDescendDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new DescendDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the focused R2-C multi-segment chain diagnostic (P0 链接验收). */
    public static boolean assignChainDiagnostic(BotPlayer bot, java.util.List<BlockPos> plannedFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        BlockPos goal = plannedFoot.get(plannedFoot.size() - 1);
        session.beginTask(new ChainDiagnosticTask(bot, plannedFoot), TaskTarget.block(goal));
        broadcastTarget(session.target);
        return true;
    }

    /**
     * Assigns the R3 one-action self-test battery (plan + all movements + chain).
     *
     * @param hubFoot 测试起点脚位；任务开始与每项开始前会把 bot 锚定到此处
     */
    public static boolean assignPathingBattery(BotPlayer bot, BlockPos hubFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new PathingBatteryTask(bot, hubFoot), TaskTarget.block(hubFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the chained multi-scene regression (one action, all scenes). */
    public static boolean assignPathingRegression(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.PathingRegressionTask(bot, observer),
                TaskTarget.block(new BlockPos(0, 64, 46)));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the R4 plan→session execution diagnostic. */
    public static boolean assignPathSessionDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        return assignPathSessionDiagnostic(bot, goalFoot, false);
    }

    /** Assigns the R4 plan→session diagnostic with a deterministic disturbance (自愈验证夹具). */
    public static boolean assignPathSessionDiagnostic(BotPlayer bot, BlockPos goalFoot,
                                                      boolean allowWorldModification,
                                                      int disturbTick, int disturbDx, int disturbDz) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new PathSessionDiagnosticTask(bot, goalFoot, allowWorldModification,
                disturbTick, disturbDx, disturbDz), TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the R4/R5 plan→session diagnostic；allowWorldModification 授权 PATH_ACCESS 破坏。 */
    public static boolean assignPathSessionDiagnostic(BotPlayer bot, BlockPos goalFoot,
                                                      boolean allowWorldModification) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new PathSessionDiagnosticTask(bot, goalFoot, allowWorldModification),
                TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the D-043 replan fixture: block the path 2 segments ahead at a fixed tick. */
    public static boolean assignPathingWaller(BotPlayer bot, BlockPos goalFoot, int wallTick) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new PathSessionDiagnosticTask(bot, goalFoot, true,
                        0, 0, 0, wallTick, com.dddgn.alice.task.PathRetryRunner.DEFAULT_MAX_REPLANS),
                TaskTarget.block(goalFoot));
        broadcastTarget(session.target);
        return true;
    }

    /** Assigns the DOWNWARD diagnostic (execute + guard). */
    public static boolean assignMineCourseDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.MineCourseDiagnosticTask(bot, observer),
                TaskTarget.block(new net.minecraft.core.BlockPos(23, 64, 140)));
        broadcastTarget(session.target);
        return true;
    }

    /** 伐木 Job（L3/D-080，切片 J1）：在起点附近选一棵树砍完并收集。 */
    public static boolean assignLumberJob(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        // J3：配额 = 4 棵。夹具里 4 棵同型橡树（各需清障 2~3 格）⇒ 累计清障 **≥9 格**，
        // 跨过 MAX_CLEAR_PER_TREE(8) ⇒ 这才是「按棵预算重置」（D-085③）的**真回归**：
        // 若计数器是 job 级，第 3~4 棵必在 clear_budget 失败。
        // （教训：3 棵时实测累计只有 8 格，正好压在阈值上、未触发——见 D-092 附注。）
        // D-119/D-122：夹具职责——入口发料（生产 MineTask 不再兜底发工具）。物品入口已发过，
        // 这里再保证一次，让**任何**调用者（含串联回归电池）都不会徒手砍树。
        com.dddgn.alice.item.FixtureToolKit.ensureAxe(bot);
        com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), 12, "cobblestone");
        // **T5（D-125）：配额随场景推导**，不再写死 4 —— 原来「夹具可行树数(4) 与 Job 默认配额(4)」
        // 互相标定，场景里任何一棵树变得不可行都会表现为 `partial_quota`，看起来像代码 bug
        // （2026-09-11 为此花了两轮客户端）。现在用**同一套候选源**数一遍可行树（too_large 已进 rejected），
        // 配额 = 可行树数；场景变了配额自动跟上，日志里如实打出推导结果。
        com.dddgn.alice.job.lumber.LumberCandidateSource source =
                new com.dddgn.alice.job.lumber.LumberCandidateSource();
        com.dddgn.alice.job.GoalSpec probe = com.dddgn.alice.job.GoalSpec.harvestUnits(
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT, 16, 1, 300);
        int feasible = source.candidates(bot, probe).viable().size();
        int quota = Math.max(1, feasible);
        BotLog.info("[Job] lumber 场景可行树={} ⇒ 配额={}（T5：配额随场景推导）", feasible, quota);
        com.dddgn.alice.job.GoalSpec spec = com.dddgn.alice.job.GoalSpec.harvestUnits(
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT, 16, quota, 3600);
        com.dddgn.alice.job.lumber.LumberJob job = new com.dddgn.alice.job.lumber.LumberJob(
                bot, spec, session.scope(), source,
                new com.dddgn.alice.job.policy.NearestPolicy());
        session.beginTask(job, TaskTarget.block(com.dddgn.alice.task.LumberCourseAnchor.START_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /**
     * 挖掘 Job（切片 J5）：把原来"扫一遍最近方块 → 直接建 MineTask"的孤岛，
     * 换成与伐木同一套 L3 骨架（候选集 → 策略 → 决策 trace → 终止语义）。
     *
     * @param target 目标（标签或方块 ID），由命令解析后传入
     */
    /**
     * **统一 Job 入口**（D-134 / ② 决策层契约）：决策层唯一需要的"起 Job"动作。
     *
     * <p>三件事一次做对：① 发料（{@link com.dddgn.alice.job.JobLauncher#provision}）；
     * ② 构造 Job（`JobLauncher.create`）；③ 登记会话 + 广播目标。各领域的 `assign*` 入口
     * 现在都走这里，避免"决策层起的 Job 与夹具起的 Job 行为不同"。
     */
    public static boolean assignJob(BotPlayer bot, ServerPlayer observer,
                                    com.dddgn.alice.job.JobRequest request) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        if (!com.dddgn.alice.job.JobLauncher.provision(bot, request)) {
            BotLog.warn("[Job] launch 发料失败 ⇒ 不起 Job（{}）", request.describe());
            return false;
        }
        com.dddgn.alice.job.JobLauncher.logLaunch(bot, request);
        com.dddgn.alice.job.Job job = com.dddgn.alice.job.JobLauncher.create(bot, session.scope(), request);
        session.beginTask(job, TaskTarget.block(request.center()));
        broadcastTarget(session.target);
        return true;
    }

    public static boolean assignMineJob(BotPlayer bot, ServerPlayer observer,
                                        com.dddgn.alice.job.mine.MineCandidateSource.Target target,
                                        int quota, int radius) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        // D-134：统一走 JobLauncher（发料 + 构造）；tag 由 Target 的 describe 反推不方便，
        // 这里直接把 Target 交给一条等价的 JobRequest（productTag 仅用于日志/决策层可读）
        com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
        com.dddgn.alice.job.JobRequest request = com.dddgn.alice.job.JobRequest.mine(
                bot.blockPosition(), radius, quota, 3600, target.describe());
        // 用调用方给的具体 Target（而不是重新解析字符串）：语义完全等价，避免二次解析差异
        com.dddgn.alice.job.Job job = new com.dddgn.alice.job.mine.MineJob(
                bot, com.dddgn.alice.job.GoalSpec.mineBlocks(bot.blockPosition(), radius, quota, 3600),
                session.scope(), new com.dddgn.alice.job.mine.MineCandidateSource(target, radius),
                new com.dddgn.alice.job.policy.NearestPolicy());
        com.dddgn.alice.job.JobLauncher.logLaunch(bot, request);
        session.beginTask(job, TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /**
     * 触发一次作用域恢复（J6-b 的**命令兜底**）：拆掉我方放置的临时方块并销账。
     *
     * <p>正常路径是"任务收尾自动追加恢复任务"；本命令用于处理**历史遗留**（例如崩溃/重启留下的
     * 脚手架，或升级前账本里已有的条目）。
     *
     * @param all true = 恢复账本里全部待恢复的 TEMP；false = 仅当前打开的作用域
     */
    public static boolean assignRestore(BotPlayer bot, ServerPlayer observer, boolean all) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        // D-119：拆我方圆石需要镐；工具由**入口**准备（生产 MineTask 不再兜底发工具）
        com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
        com.dddgn.alice.ledger.WorldModLedger.dropStale(bot.serverLevel());
        var pending = com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(bot.getServer(), null);
        if (pending.isEmpty()) {
            BotLog.info("[Restore] 账本无待恢复项（无需启动）");
            return false;
        }
        String scope = all ? null : pending.get(0).scopeId();
        session.beginTask(new com.dddgn.alice.task.RestoreScopeTask(bot, session.scope(), scope),
                TaskTarget.block(pending.get(0).pos()));
        broadcastTarget(session.target);
        return true;
    }

    /**
     * **可持续伐木区（MAINTAIN）入口**（J8 / §13）：设定区域 → 起 {@code RegionLumberJob}。
     *
     * <p>与一次性伐木共用同一套候选源/策略/内嵌 `LumberJob`（§13 的"一个 Job 两套参数"）；
     * 这里额外做两件事：把**区域边界**写进持久化的 {@link com.dddgn.alice.job.lumber.LumberRegionState}
     * （跨会话记得"这片区域该长什么样"），以及入口发料（D-119 起生产任务不发工具）。
     */
    public static boolean assignRegionLumber(BotPlayer bot, ServerPlayer observer,
                                             com.dddgn.alice.job.lumber.LumberRegionState.Region region) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        com.dddgn.alice.item.FixtureToolKit.ensureAxe(bot);
        com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), 12, "cobblestone");
        com.dddgn.alice.job.lumber.LumberRegionState state =
                com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer());
        state.setRegion(bot.getUUID(), region);
        // 树苗选择接口的**默认值**（用户裁定：补种树苗不必与被砍的树一一对应）：
        // 未配置时用橡树苗；想换就用 `/alice region sapling <item>`（写进持久化的区域状态）
        if (state.saplingItem(bot.getUUID()) == null) {
            state.setSaplingItem(bot.getUUID(), "minecraft:oak_sapling");
            BotLog.info("[Job] region_lumber 未选择树苗 ⇒ 默认 {}（/alice region sapling 可更换）",
                    "minecraft:oak_sapling");
        }
        // 夹具/开发入口发料：把**选定的那种**树苗放进快捷栏（区域欠树时补种要用）
        var saplingId = net.minecraft.resources.ResourceLocation.tryParse(state.saplingItem(bot.getUUID()));
        var saplingItem = saplingId == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(saplingId);
        if (saplingItem != null && saplingItem != net.minecraft.world.item.Items.AIR) {
            com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                    () -> new net.minecraft.world.item.ItemStack(saplingItem),
                    stack -> stack.is(saplingItem), 8, "sapling(" + state.saplingItem(bot.getUUID()) + ")");
        }
        BotLog.info("[Job] region_lumber 区域={} saplingItem={}（补种树苗由用户选择，见 /alice region）",
                region.describe(), state.saplingItem(bot.getUUID()) == null
                        ? "-" : state.saplingItem(bot.getUUID()));
        session.beginTask(new com.dddgn.alice.job.lumber.RegionLumberJob(bot, region,
                        session.scope(), new com.dddgn.alice.job.lumber.LumberCandidateSource(),
                        new com.dddgn.alice.job.policy.NearestPolicy(), 40, 24000, observer),
                TaskTarget.block(region.center()));
        broadcastTarget(session.target);
        return true;
    }

    /** J6-b2：容器绕行自检（断言 bot 不为取目标而拆箱子，D-095）。 */
    public static boolean assignClearGuardCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.ClearGuardCheckTask(bot, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.ClearGuardCheckTask.START_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /** 脚手架生命周期自检（J7 Step 1）：搭柱子爬上去 → 高处干活 → 仍在顶上拆掉 → 落地。 */
    public static boolean assignScaffoldCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.ScaffoldLifecycleTask(bot, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.ScaffoldLifecycleTask.CLIMB_GOAL_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /** 串联回归电池（D-122）：一次跑完 9 项常用回归，每项独立复位、失败不中断。 */
    public static boolean assignRegressionBattery(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.RegressionBatteryTask(bot, observer, session.scope()),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** R2 限次清障"换候选"自检：一个候选失败要换下一个，而不是放弃整棵树。 */
    public static boolean assignClearRetryCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.ClearRetryCheckTask(bot, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.ClearRetryCheckTask.START_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /** 写入预算自检（D-106）：任务级破坏上限压到 1 格，断言"用满即停、如实失败"。 */
    public static boolean assignWriteBudgetCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.WriteBudgetCheckTask(bot, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.WriteBudgetCheckTask.START_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /** 伐木失败语义自检（切片 J4）：五条终止路径各一个用例。 */
    public static boolean assignLumberFailureCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.LumberFailureCheckTask(bot, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.LumberCourseAnchor.START_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /** 挖掘专项串联回归（批次 5）。 */
    public static boolean assignMineRegression(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.MineRegressionTask(bot, observer, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.MineCourseDiagnosticTask.START_FOOT));
        broadcastTarget(session.target);
        return true;
    }

    /** 模组兼容自检：Ore Excavation 连锁挖掘的掉落物捕获与收集。 */
    public static boolean assignChainMineDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.ChainMineDiagnosticTask(bot, observer, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.ChainMineDiagnosticTask.SEED));
        broadcastTarget(session.target);
        return true;
    }

    public static boolean assignBreakEnterDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.BreakEnterDiagnosticTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.BreakEnterDiagnosticTask.GOAL_A));
        broadcastTarget(session.target);
        return true;
    }

    public static boolean assignFallDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.FallDiagnosticTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.FallDiagnosticTask.DROP3_GOAL));
        broadcastTarget(session.target);
        return true;
    }

    public static boolean assignPillarDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.PillarDiagnosticTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.PillarDiagnosticTask.RIM_GOAL));
        broadcastTarget(session.target);
        return true;
    }

    public static boolean assignVerticalDiagnostic(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.VerticalDiagnosticTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.VerticalDiagnosticTask.OPEN_GOAL));
        broadcastTarget(session.target);
        return true;
    }

    /* legacy movement experiment entry removed; retain core task APIs only */
    /** 给假人分配 Bot 专用道路施工任务。 */
    public static void assignRoadBuild(BotPlayer bot, com.dddgn.alice.road.RoadPlan plan) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) {
            BotLog.warn("assignRoadBuild 失败: bot 不存在 uuid={}", bot.getUUID());
            return;
        }
        session.assignRoadBuild(plan);
    }

    /** Creates the only approved transfer task entry point. */
    public static String assignTransfer(BotPlayer bot, TransferRequest request) {
        if (legacyTaskDisabled("TransferTask")) return "legacy_pathing_disabled";
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return TransferCodes.BOT_UNAVAILABLE;
        TransferLedgerData ledger = TransferLedgerData.get(bot.getServer());
        if (ledger.find(request.requestId()).isPresent()) return TransferCodes.DUPLICATE_REQUEST;
        if (!ledger.admit(request)) return TransferCodes.DUPLICATE_REQUEST;
        if (!session.assignTransfer(request, ledger)) {
            ledger.transition(request.requestId(), TransferLedgerData.State.FAILED_NOT_MOVED,
                    TransferLedgerData.Location.NOT_MOVED, TransferCodes.BOT_UNAVAILABLE, bot.serverLevel().getGameTime(),
                    "admission:bot_unavailable", false);
            return TransferCodes.BOT_UNAVAILABLE;
        }
        return "accepted";
    }

    public static TransferLedgerData.Entry transferStatus(MinecraftServer server, java.util.UUID requestId) {
        return TransferLedgerData.get(server).find(requestId).orElse(null);
    }

    /** Ledger-only administrator abort used by the command and focused server fixture. */
    public static TransferLedgerData.State abortTransfer(MinecraftServer server, java.util.UUID requestId) {
        return TransferLedgerData.get(server).abort(requestId, server.getTickCount());
    }

    /** 给假人分配「挖掘指定方块」任务(命令/selftest 兼容入口)。 */
    /** Mine 已迁移到新内核（D-064 批次 1），不再走 legacy 门禁。 */
    public static boolean assignMine(BotPlayer bot, BlockPos target) {
        assignTarget(bot, TaskTarget.block(target));
        BotLog.info("分配挖掘任务: bot={} target={}",
                bot.getName().getString(), target.toShortString());
        return true;
    }

    /** 任务入口(测试工具/决策层共用):按目标类型创建对应 Task 并开始执行。 */
    public static void assignTarget(BotPlayer bot, TaskTarget target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) {
            BotLog.warn("assignTarget 失败: bot 不存在 uuid={}", bot.getUUID());
            return;
        }
        session.assign(target);
    }

    /** 直接分配任务对象给 bot（用于自定义任务如伐木）。 */
    public static void assignTask(BotPlayer bot, Task task) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) {
            BotLog.warn("assignTask 失败: bot 不存在 uuid={}", bot.getUUID());
            return;
        }
        session.beginTask(task, task.target());
        BotLog.info("assignTask: bot={} task={}", bot.getName().getString(), task.getClass().getSimpleName());
    }

    /** 查找最近的 bot（用于工具自动选择 bot）。 */
    public static BotPlayer findNearestBot(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        BotPlayer nearest = null;
        double minDist = Double.MAX_VALUE;
        
        for (BotPlayer bot : getAllBots()) {
            if (bot.level() != level) {
                continue;
            }
            double dist = bot.blockPosition().distSqr(pos);
            if (dist < minDist) {
                minDist = dist;
                nearest = bot;
            }
        }
        
        return nearest;
    }

    /** 广播当前任务目标给所有客户端(透视高亮用;null = 清除高亮)。 */
    public static void broadcastTarget(TaskTarget target) {
        TargetPacket packet = target == null
                ? new TargetPacket(false, 0, null, -1)
                : new TargetPacket(true,
                        target.type() == TaskTarget.Type.BLOCK ? 0 : 1,
                        target.blockPos(), target.entityId());
        AliceNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    /** 假人当前是否在执行任务(供自动化验收轮询)。 */
    public static boolean isBusy(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session != null && session.task != null;
    }

    /** 取最近一次任务的结果文本("done" 或 "failed:原因"),供自动化验收断言。 */
    public static String lastTaskResult(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? "" : session.lastTaskResult;
    }

    /** 只读获取最近终端任务记录；无记录时返回 null。 */
    public static TaskExecutionRecord lastExecutionRecord(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? null : session.lastExecutionRecord();
    }

    /** 当前任务的只读摘要；空闲时返回 null。 */
    public static String currentTaskSummary(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? null : session.currentTaskSummary();
    }

    /** 只读获取当前 MineTask 计划，供开发期测试夹具观察，不修改任务。 */
    public static com.dddgn.alice.task.mining.MiningPlan currentMiningPlan(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? null : session.currentMiningPlan();
    }

    /** 取最近一次挖掘任务「开始挖掘时的 bot 位置」(隔空挖断言用;从未开始挖则为 null)。 */
    public static BlockPos lastMineStartPos(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        return session == null ? null : session.lastMineStartPos;
    }

    /**
     * 任务/执行器驱动相位：保持 {@link TickEvent.Phase#END}。
     *
     * <p>D-038 曾改为 {@code START} 以求"输入与物理同 tick"，但 Forge 源码证明两者等价：
     * {@code onPreServerTick} 在 {@code tickCount++/tickChildren} 之前，{@code onPostServerTick}
     * 在全部实体 tick 之后——**都位于 physics(N-1) 之后、physics(N) 之前**，观察到的状态与
     * 输入生效的 tick 完全相同。1 tick 延迟是"先观察后动作"的离散控制回路固有属性（Baritone 亦然）。
     * 详见 D-038（已撤回）。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        TransferLedgerData.get(event.getServer()).expireSuspensions(event.getServer().getTickCount(),
                TRANSFER_MAX_SUSPENSION_TICKS);
        for (BotSession session : BOTS.values()) {
            // D-176：**实体 tick 看门狗** —— 会话在跑、但 bot 实体整 tick 没被 tick 过 ⇒ 物理冻结。
            // 2026-09-13 实测：`segmentTicks=121 entityTicksInSegment=0 travelCallsInSegment=0`
            // （输入 forward=1.00、onGround=true、delta=0、脚下空气/头顶空气/支撑石头）⇒
            // 表现为"任务在跑、bot 一格不动"，且**没有任何报错**（连带 exec_floating 超时、chain 收不到掉落）。
            checkEntityTickProgress(session);
            HazardState hazard = SurvivalSystem.tick(session.bot());
            session.tick(hazard);
            // S3：请示超时（按时限把"没答复"落档为默认档 —— 用户裁定：超时=拒绝）
            com.dddgn.alice.decision.PermissionGate.tick(session.bot());
            // L2：容器菜单生命周期看门狗（菜单开着却没有任务在跑 ⇒ 告警 + 收尾）
            MenuLifecycle.tick(session.bot());
            // S4：事件阈值（工具耐久见底 / 卡住）—— 只报可行动病症，跨越阈值只报一次
            com.dddgn.alice.decision.EventThresholds.tick(session.bot());
            // D-135：决策层循环（事件驱动 + 节流；这里只做"收结果 + 空闲触发"）
            com.dddgn.alice.decision.GoalDirector.tick(session.bot());
        }
    }

    private static String fmt3(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** 崩溃兜底：bot 距残留多近才自动续做（格）。远了**不自动走回去** —— §12.3 要求"仍在架上时拆"。 */
    public static final double TEARDOWN_RECOVERY_RANGE = 16.0D;

    /**
     * **崩溃兜底判定**（J7 Step 3 / §12.4，可单测）：账本里还有没有我这个 bot 没拆完的临时放置，近不近。
     *
     * <p>为什么不无条件续做：D-103 的教训 —— "从地面走回去拆高层柱子"会引出跨场景寻路/站位/支撑等
     * 一整串复杂度，而且**从下面拆高层柱子必然留悬空残块**（§12.3）。所以只做两件事：
     * 近（≤ {@link #TEARDOWN_RECOVERY_RANGE} 格，通常是崩溃时 bot 就站在架上）⇒ 续做；
     * 远 ⇒ **如实报告并留给 `/alice restore`**，不自己走过去。
     *
     * @return {@code none}（干净）/ {@code ready}（可就地续做）/ {@code too_far}（残留太远，只报告）
     */
    public static String teardownRecoveryDecision(BotPlayer bot) {
        var level = bot.serverLevel();
        com.dddgn.alice.ledger.WorldModLedger.dropStale(level);   // 现场已非我方方块的条目先销掉
        var pending = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(level.getServer(), bot.getUUID());
        if (pending.isEmpty()) {
            return "none";
        }
        double nearest = Double.MAX_VALUE;
        for (var entry : pending) {
            nearest = Math.min(nearest, Math.sqrt(entry.pos().distSqr(bot.blockPosition())));
        }
        BotLog.warn("[Recovery] 账本发现 {} 条我方未拆除的临时方块（上次会话未闭合）最近 {} 格",
                pending.size(), fmt3(nearest));
        if (nearest > TEARDOWN_RECOVERY_RANGE) {
            BotLog.warn("[Recovery] 距离超过 {} 格 ⇒ **不自动走回去**（§12.3：拆除须在仍在架上时做）；"
                    + "如需清理：/alice restore", fmt3(TEARDOWN_RECOVERY_RANGE));
            return "too_far";
        }
        BotLog.info("[Recovery] 就近（{} 格）⇒ 可就地续做拆除", fmt3(nearest));
        return "ready";
    }

    /**
     * bot 可用时调用：残留**就近**且 bot 空闲 ⇒ 起一个恢复任务把这次未闭合的会话收尾。
     *
     * @return 见 {@link #teardownRecoveryDecision}（外加 {@code busy} / {@code resumed}）
     */
    public static String tryRecoverUnfinishedTeardown(BotPlayer bot) {
        String decision = teardownRecoveryDecision(bot);
        if (!"ready".equals(decision)) {
            return decision;
        }
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            BotLog.info("[Recovery] bot 正忙/未注册 ⇒ 本次不自动续做（留给 /alice restore）");
            return "busy";
        }
        var pending = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(bot.serverLevel().getServer(), bot.getUUID());
        session.beginTask(new com.dddgn.alice.task.RestoreScopeTask(bot, session.scope(), null),
                TaskTarget.block(pending.get(0).pos()));
        broadcastTarget(session.target);
        BotLog.info("[Recovery] 续做拆除：scope=all blocks={}", pending.size());
        return "resumed";
    }

    /**
     * **显式停止当前任务**（J8 / §13.1）：常驻任务（如可持续伐木区）**只由玩家/决策层打断**，
     * 不自己收工。这里按 {@code CANCELLED_BY_USER} 记账（与"被新指令替换"区分开），并跑一遍收尾。
     *
     * <p>打断是**正常**结束方式，但可能停在"脚手架上/半棵树"的中间态：收尾若发现有我方临时方块
     * 未拆除，会另起一行如实说明（`world_mod_ledger_close …`，不自动追任务，见 D-103）。
     *
     * @return 被停掉的任务名；没有任务时返回 null
     */
    /**
     * **请求停止任务**（K-3，2026-09-13）：能安全停就立刻停；**不安全则延后到安全点**
     * （对齐 Baritone `PathExecutor:287`："不安全时不许取消"）。
     *
     * <p>判据（两条并集）：`task.safeToCancel()`（各 Movement 的承诺点：已跨出边缘/已放置/已起跳…）
     * **或** `!bot.onGround()`（空中 = 与任务类型无关的硬事实 —— 这条让 **Job 也自动受保护**）。
     * 延后上限 {@link BotSession#SAFE_STOP_DEFER_TICKS}，超时则**强制停并计数**（诚实上报，不无限等）。
     */
    public static String stopTask(BotPlayer bot, String reason) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task == null) {
            return null;
        }
        if (session.safeToStopNow()) {
            return session.immediateStop(reason, false);
        }
        session.pendingStopReason = reason == null ? "user" : reason;
        session.pendingStopTicks = 0;
        BotLog.info("[alice] 停止请求**延后到安全点**：task={} reason={}（空中或已提交位移）",
                session.taskKind, session.pendingStopReason);
        return session.taskKind;
    }


    /**
     * **实体 tick 看门狗（D-176）**：只在"该 tick 没 tick 过"时告警，用于抓"物理冻结"的第一现场。
     *
     * <p>输出的是**区分病因**所需的量：bot 是否已从世界/玩家表移除、所在区块是否还加载、
     * 连接是否还在、连续缺 tick 多少。**只告警不改行为**（先拿到现场，再定修法）。
     */
    private static void checkEntityTickProgress(BotSession session) {
        BotPlayer bot = session.bot();
        long now = bot.entityTickCount();
        if (session.lastEntityTicks == now) {
            session.entityTickMissingStreak++;
            // 第 5 次（约 0.25 秒）报第一次，之后每 100 tick（5 秒）报一次，避免刷屏
            if (session.entityTickMissingStreak == 5 || session.entityTickMissingStreak % 100 == 0) {
                BlockPos pos = bot.blockPosition();
                BotLog.warn("[Bot] entity_tick_missing streak={} serverTick={} bot={} pos={}"
                                + " removed={} levelLoaded={} inLevelPlayers={} inPlayerList={}"
                                + " connection={} task={}",
                        session.entityTickMissingStreak, bot.getServer().getTickCount(),
                        bot.getName().getString(), pos.toShortString(),
                        bot.isRemoved(), bot.serverLevel().isLoaded(pos),
                        bot.serverLevel().players().contains(bot),
                        bot.getServer().getPlayerList().getPlayers().contains(bot),
                        bot.connection != null, session.taskKind);
            }
        } else {
            session.entityTickMissingStreak = 0;
        }
        session.lastEntityTicks = now;
    }

    /** 取 bot 的会话（决策层快照只读用；null = 未注册）。 */
    public static BotSession sessionOf(BotPlayer bot) {
        return bot == null ? null : BOTS.get(bot.getUUID());
    }

    /** bot 名下**未闭合**的我方临时方块条数（`/alice region stop` 回执用，只读）。 */
    public static int pendingTemporaryCount(BotPlayer bot) {
        return com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
    }

    /** **被动拾取闸门自检**（S3.5 / D-143）：我方掉落物应捡、外来掉落物应被拦下。 */
    /** K-3 安全点停止自检（确定性夹具：升空后请求停止）。 */
    public static boolean assignK3StopCheck(BotPlayer bot, ServerPlayer observer,
                                            com.dddgn.alice.task.K3StopCheckTask.Mode mode) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.K3StopCheckTask(bot, observer, mode),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** L2 菜单协议最小验证探针（开真菜单 → 菜单点击搬物品 → 关闭）。 */
    public static boolean assignMenuProbe(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.MenuProbeTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** R2 传输模块自检：跑 4 个夹具（主流程/端点选择/选择器事件/命令解析）。 */
    public static boolean assignTransferCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.TransferCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-7 前缀搜索自检（K-1，纯规划）：PARTIAL 前缀 / 同目标可达 / 真失败不给前缀。 */
    public static boolean assignPartialSearchCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.PartialSearchCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-8 能力闸门自检（D-157，纯逻辑）：保护区/资源/工具/预算/声明一致性。 */
    public static boolean assignCapabilityGateCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.CapabilityGateCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-9 工具供给自检：换更好的 / 没得换如实报 / 不能凭空变出工具。 */
    public static boolean assignToolSupplyCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.ToolSupplyCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-9 工具维护（决策层动作 `maintain_tool`）：把已拥有的同类工具弄到手上（只动背包）。 */
    public static boolean assignToolMaintenance(BotPlayer bot, ServerPlayer observer,
                                                com.dddgn.alice.bot.ToolSupply.Kind kind) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.ToolMaintenanceTask(bot, observer, kind),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-5 LLM 上抛契约自检（D-155）：Job 失败报告 / 产物判定口径 / 结构化拒绝回读。 */
    public static boolean assignLlmContractCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.LlmContractCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-4 决策 trace / 跨重启语义自检（D-154）：落盘 / 内存尾 / NBT 往返 / 重启报告只报一次。 */
    public static boolean assignDecisionTraceCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.DecisionTraceCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** 基-1 可回收性自检（D-151，纯计算）：规则表/逐类型/负例/转换点四例。 */
    public static boolean assignRecoverabilityCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.RecoverabilityCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** S4 事件阈值自检（D-150）：工具见底 / 卡住 两类病症的"上报 + 只报一次"。 */
    public static boolean assignEventThresholdCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.EventThresholdCheckTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.PillarDiagnosticTask.SHAFT_START));
        broadcastTarget(session.target);
        return true;
    }

    /**
     * **统一的"bot 正忙"文案**（测试入口一律用它，别各写各的）。
     *
     * <p>为什么：2026-09-12 实测 —— 常驻区域巡查任务占着会话时，玩家右键任何测试物品只看到
     * "bot 正忙，稍后再试"，既不知道**在跑什么**、也不知道**怎么停**（用户原话："我发现 bot
     * 怎么一直在执行区域挖掘任务"）。常驻任务只能由玩家/决策层打断（D-131），所以这里必须把
     * 打断方式一并说清。
     */
    public static String busyMessage(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        String kind = session == null || session.task == null ? "未知任务" : session.taskKind;
        if ("RegionLumberJob".equals(kind)) {
            return "bot 正忙：正在跑常驻区域伐木巡查（viable/苗情见汇报）。"
                    + "要它收工用 /alice region stop";
        }
        return "bot 正忙：正在跑 " + kind + "。要打断用 /alice stop（或等它自己结束）";
    }

    public static boolean assignPickupGateCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.PickupGateCheckTask(bot, observer),
                TaskTarget.block(com.dddgn.alice.task.PickupGateCheckTask.DROP_B));
        broadcastTarget(session.target);
        return true;
    }

    /** **请示通道演示**（S3 / D-140）：起 `PermissionDemoTask`（发起 demo_ask 请示并轮询结论）。 */
    public static boolean assignPermissionDemo(BotPlayer bot, ServerPlayer observer, int maxTicks) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.PermissionDemoTask(bot, observer, maxTicks),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** **未加载区块/世界边界门控自检**（S-2 / P1-A）：无头规划三个用例，不改世界（边界临时改后立刻还原）。 */
    public static boolean assignChunkGuardCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.ChunkGuardCheckTask(bot, observer),
                TaskTarget.block(bot.blockPosition()));
        broadcastTarget(session.target);
        return true;
    }

    /** **挖掘前流体风险自检**（S-4 / P0-C）：目标下方是岩浆必须硬拒，普通目标必须挖完。 */
    public static boolean assignFluidMineCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.beginTask(new com.dddgn.alice.task.FluidMineCheckTask(bot, observer, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.FluidMineCheckTask.TARGET_OVER_LAVA));
        broadcastTarget(session.target);
        return true;
    }

    /**
     * **维生出口自检**（S-1 / P1-C）：起一个"注定会被维生否决"的 dummy 任务。
     *
     * <p>为什么要 dummy：维生否决发生在**任务运行中**（`BotSession.tick` 只在 `task != null` 时检查
     * `shouldInterrupt`）—— 所以夹具必须让 bot 手上有个活，否则否决路径根本不会触发。
     * dummy 用纯通行的 `WalkToTask`（不挖不放置，D-076 不受影响），它在下一个 tick 就会被打断，
     * 随后由 {@code startSurvivalExit()} 接上逃生出口。
     */
    public static boolean assignSurvivalExitCheck(BotPlayer bot, ServerPlayer observer,
                                                  net.minecraft.core.BlockPos dummyGoal) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) {
            return false;
        }
        session.assignWalkTo(dummyGoal);
        broadcastTarget(session.target);
        return true;
    }

    /** 服务器启动完成:恢复存档假人(若有)。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TransferLedgerData.get(event.getServer()).suspendUnfinished(TransferCodes.SERVER_RESTART,
                event.getServer().getTickCount());
        restoreFromWorld(event.getServer());
        // J7 Step 3（D-127）：启动就报出"上次没拆完的脚手架"（0 条时不出声，避免噪声）
        var openScopes = com.dddgn.alice.ledger.WorldModLedger.openScopes(event.getServer());
        int residual = 0;
        for (var entry : com.dddgn.alice.ledger.WorldModLedger.pending(event.getServer())) {
            if (entry.policy() == com.dddgn.alice.ledger.WorldModLedger.Policy.TEMP) {
                residual++;
            }
        }
        if (residual > 0 || !openScopes.isEmpty()) {
            BotLog.warn("[Recovery] 启动检查：账本有我方临时放置 {} 条、未闭合作用域 {} 个"
                            + "（上次不是正常收尾；bot 上线后就近会续做，或用 /alice restore）",
                    residual, openScopes.size());
        }
    }

    /** 关服前:冗余写一次档(平时 spawn/remove 已维护,这里兜底防崩溃丢档)。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TransferLedgerData.get(event.getServer()).suspendUnfinished(TransferCodes.SERVER_RESTART,
                event.getServer().getTickCount());
        for (BotSession session : BOTS.values()) {
            if (session.bot().getHealth() > 0.0f) {
                saveToWorld(session.bot());
            }
        }
    }

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

    /** 单个假人的会话:持有当前任务与感知作用域。 */
    public static final class BotSession {
        private final BotPlayer bot;
        private Task task;
        private TaskTarget target;
        private final ScopeBuffer scope = new ScopeBuffer();
        private String lastTaskResult = "";
        private BlockPos lastMineStartPos;
        private String taskKind = "";
        private String taskTargetDescription = "";
        private long taskStartTick;
        private TaskExecutionRecord lastExecutionRecord;

        private BotSession(BotPlayer bot) {
            this.bot = bot;
        }

        /** D-176 看门狗状态：上一次看到的实体 tick 计数与连续缺 tick 次数。 */
        long lastEntityTicks = -1L;
        int entityTickMissingStreak;

        public BotPlayer bot() {
            return bot;
        }

        public ScopeBuffer scope() {
            return scope;
        }

        /** 最近一次任务结果("done" / "failed:原因")。 */
        public String lastTaskResult() {
            return lastTaskResult;
        }

        /** 当前任务目标(客户端高亮依据;无任务为 null)。 */
        public TaskTarget currentTarget() {
            return target;
        }

        /** 当前任务本体（S1 事实层：汇报需要遍历 Job 的子任务树；无任务为 null）。 */
        public Task currentTask() {
            return task;
        }

        /** 当前任务启动 tick（汇报里算"已跑多久"）。 */
        public long taskStartTick() {
            return taskStartTick;
        }

        public TaskExecutionRecord lastExecutionRecord() {
            return lastExecutionRecord;
        }

        public String currentTaskSummary() {
            return task == null ? null : taskKind + " target=" + taskTargetDescription
                    + " startedTick=" + taskStartTick;
        }

        public com.dddgn.alice.task.mining.MiningPlan currentMiningPlan() {
            return task instanceof MineTask mineTask ? mineTask.currentPlan() : null;
        }

        private long serverTick() {
            return bot.getServer().getTickCount();
        }

        private void beginTask(Task assignedTask, TaskTarget assignedTarget) {
            // 一次任务 = 一个世界修改授权作用域（J6-a）：账本按 scope 聚合，恢复以 scope 为单位
            com.dddgn.alice.ledger.WorldModLedger.openScope(bot.getServer(), bot.getUUID(),
                    assignedTask.getClass().getSimpleName());
            task = assignedTask;
            target = assignedTarget;
            taskKind = assignedTask.getClass().getSimpleName();
            taskTargetDescription = assignedTarget.describe();
            taskStartTick = serverTick();
            // 基-4：登记"当前任务"⇒ 重启后能如实报"重启前正在跑 X（未续做）"，而不是装作无事发生
            com.dddgn.alice.decision.DecisionState.get(bot.getServer())
                    .recordTask(bot.getUUID(), taskKind + " target=" + taskTargetDescription);
        }

        private boolean replaceTaskIfRunning() {
            if (TransferLedgerData.get(bot.getServer()).blocksBot(bot.getUUID())) {
                return false;
            }
            if (task instanceof TransferTask transfer && transferInTransitOrSuspended(transfer)) {
                return false;
            }
            if (task != null) {
                recordTerminal(taskKind, taskTargetDescription, taskStartTick,
                        TaskExecutionRecord.TerminalStatus.CANCELLED_REPLACED,
                        "cancelled:replaced", "idle_after_cleanup");
            }
            clearTask();
            return true;
        }

        private boolean transferInTransitOrSuspended(TransferTask transfer) {
            TransferLedgerData.Entry entry = TransferLedgerData.get(bot.getServer()).find(transfer.request().requestId()).orElse(null);
            return entry != null && (entry.state() == TransferLedgerData.State.IN_TRANSIT_BOT
                    || entry.state() == TransferLedgerData.State.SUSPENDED);
        }

        public boolean assignTransfer(TransferRequest request, TransferLedgerData ledger) {
            if (!replaceTaskIfRunning()) return false;
            TaskTarget assignedTarget = TaskTarget.block(request.source().position());
            beginTask(new TransferTask(bot, request, ledger), assignedTarget);
            broadcastTarget(this.target);
            return true;
        }

        /** 分配任务:按目标类型实例化 Task,开启感知作用域,广播高亮。 */
        public boolean assignFollow(ServerPlayer targetPlayer) {
            if (!replaceTaskIfRunning()) return false;
            TaskTarget assignedTarget = TaskTarget.entity(targetPlayer.getId());
            beginTask(new com.dddgn.alice.task.FollowTask(bot, targetPlayer), assignedTarget);
            broadcastTarget(this.target);
            return true;
        }

        public boolean assignPlace(BlockPos targetPos) {
            if (!replaceTaskIfRunning()) return false;
            TaskTarget assignedTarget = TaskTarget.block(targetPos);
            beginTask(new PlaceTask(bot, targetPos), assignedTarget);
            broadcastTarget(this.target);
            return true;
        }

        public void assignWalkTo(BlockPos goalFoot) {
            if (!replaceTaskIfRunning()) return;
            TaskTarget assignedTarget = TaskTarget.block(goalFoot);
            beginTask(new com.dddgn.alice.task.WalkToTask(bot, goalFoot), assignedTarget);
            broadcastTarget(this.target);
        }

        public void assignRoadBuild(com.dddgn.alice.road.RoadPlan plan) {
            if (!replaceTaskIfRunning()) return;
            if (!plan.isComplete() || plan.level() != bot.level()) {
                lastTaskResult = "failed:road_plan_invalid";
                recordTerminal("RoadBuildTask", "road_plan", serverTick(),
                        TaskExecutionRecord.TerminalStatus.REJECTED_BEFORE_START, lastTaskResult, "not_started");
                return;
            }
            TaskTarget assignedTarget = TaskTarget.block(plan.second());
            scope.begin(plan.second(), 8);
            // D-119：`/alice road` 同样是开发/测试入口 —— 修路会破方块，工具在这里给
            com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
            beginTask(new com.dddgn.alice.task.RoadBuildTask(bot, plan, scope), assignedTarget);
            broadcastTarget(this.target);
        }

        public void assign(TaskTarget newTarget) {
            if (!replaceTaskIfRunning()) return; // in-transit transfer refuses unrelated replacement
            switch (newTarget.type()) {
                case BLOCK -> {
                    // 任务启动即开启作用域:监听掉落物与方块变化(设计文档 §3.2)
                    // D-074：半径 16 覆盖连锁挖掘；只把 bot 自己造成的破坏登记为掉落来源
                    scope.begin(newTarget.blockPos(), 16, bot.getUUID());
                    // D-119：`/alice mine` 是**开发/测试入口** —— 工具在这里给（生产 MineTask 不再发），
                    // 否则挖石头/矿石会如实失败 `no_suitable_tool`（原版徒手不掉落）。
                    com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
                    // 单目标默认只走真实可通行曲面的 A*；通道规划后续仅在曲面不可达时显式接入。
                    // D-112：这是顶层"会话所有者"（一条 /alice mine 指令 = 一次使用会话）→ 用完即拆
                    beginTask(new MineTask(bot, newTarget.blockPos(), scope,
                            com.dddgn.alice.task.mining.MiningBudget
                                    .forTarget(bot, bot.serverLevel(), newTarget.blockPos(), true),
                            com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore(),
                            com.dddgn.alice.action.WriteGrant.of("command",
                                    com.dddgn.alice.action.WriteReason.EXPECTED_TARGET)), newTarget);
                }
                case ENTITY -> {
                    BotLog.warn("实体目标任务尚未实现: target={}", newTarget.describe());
                    lastTaskResult = "failed:entity_task_unimplemented";
                    recordTerminal("unimplemented", newTarget.describe(), serverTick(),
                            TaskExecutionRecord.TerminalStatus.REJECTED_BEFORE_START,
                            lastTaskResult, "not_started");
                    this.target = null;
                    return;
                }
            }
            broadcastTarget(this.target);
        }

        /** K-3：延迟停止的上限（≈1 秒）；超时强制停并计数。 */
        static final int SAFE_STOP_DEFER_TICKS = 20;
        private String pendingStopReason;
        private int pendingStopTicks;
        private int safeStopDeferredCount;
        private int forcedUnsafeStopCount;
        private int survivalUnsafeInterruptCount;

        /** K-3：此刻停这个任务安全吗（任务层承诺点 + 空中硬事实）。 */
        boolean safeToStopNow() {
            return task != null && task.safeToCancel() && bot.onGround();
        }

        /** 立即停止（原 `stopTask` 主体）；`forced` = 在不安全时刻被强制停。 */
        String immediateStop(String reason, boolean forced) {
            String kind = taskKind;
            recordTerminal(taskKind, taskTargetDescription, taskStartTick,
                    TaskExecutionRecord.TerminalStatus.CANCELLED_BY_USER,
                    "cancelled:" + (reason == null ? "user" : reason), "idle_after_cleanup");
            clearTask();
            int residue = com.dddgn.alice.ledger.WorldModLedger
                    .pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
            BotLog.info("[alice] 已显式停止任务 {}（{}）残余临时方块={}", kind,
                    reason == null ? "user" : reason, residue);
            if (forced) {
                forcedUnsafeStopCount++;
                BotLog.warn("[alice] 任务在**不安全时刻被强制停止**（累计 {}）：空中/已提交位移时取消有风险",
                        forcedUnsafeStopCount);
            }
            return kind;
        }

        /** K-3 计数（汇报用）。 */
        public String describeSafeStops() {
            return "deferred=" + safeStopDeferredCount + " forcedUnsafe=" + forcedUnsafeStopCount
                    + " survivalUnsafe=" + survivalUnsafeInterruptCount
                    + (pendingStopReason == null ? "" : " pending=" + pendingStopReason);
        }

        private void tick(HazardState hazard) {
            if (task == null) {
                return;
            }
            // K-3：待处理的安全点停止（每 tick 检查一次）
            if (pendingStopReason != null) {
                boolean safe = safeToStopNow();
                if (safe || ++pendingStopTicks > SAFE_STOP_DEFER_TICKS) {
                    String reason = pendingStopReason;
                    pendingStopReason = null;
                    if (safe) {
                        safeStopDeferredCount++;
                        BotLog.info("[alice] 已到安全点，执行延后的停止（等待 {} tick）", pendingStopTicks);
                    }
                    pendingStopTicks = 0;
                    immediateStop(reason + (safe ? ":safe_point" : ":forced_unsafe"), !safe);
                    return;
                }
            }
            // S-1（P1-C，2026-09-12）：**逃生任务本身豁免否决** —— 否则"中断 ⇒ 起逃生 ⇒ 下一 tick
            // 又被中断"会变成每 tick 自杀循环，逃生一步都走不出去。只豁免逃生动作；
            // 挖矿/伐木/放置这类会把 bot 送进危险的任务照旧被否决。
            if (!(task instanceof com.dddgn.alice.task.SurvivalExit)
                    && SurvivalSystem.shouldInterrupt(hazard)) {
                if (task instanceof TransferTask transfer) {
                    transfer.survivalInterrupted(SurvivalSystem.interruptionReason(hazard));
                }
                // K-3：生存打断**必须立即**（安全优先），但要**记录**它是否发生在不安全时刻 ——
                // 这是"我们有多常在半空中取消"的真实数据，用来决定是否值得做更细的延迟策略。
                if (!safeToStopNow()) {
                    survivalUnsafeInterruptCount++;
                    BotLog.warn("[alice] 生存打断发生在**不安全时刻**（累计 {}，task={}）",
                            survivalUnsafeInterruptCount, taskKind);
                }
                lastTaskResult = "failed:" + SurvivalSystem.interruptionReason(hazard);
                BotLog.warn("任务因维生危险中断: bot={} reason={}", bot.getName().getString(), lastTaskResult);
                complete(lastTaskResult, TaskExecutionRecord.TerminalStatus.SURVIVAL_INTERRUPTED);
                // **否决必须带出口**（用户规矩）：问维生要一个安全落点，用已验收的 WalkTo 走过去。
                startSurvivalExit();
                // S1 事实层：危险进事件环（DANGER）——汇报与阈值（S4）都读它
                com.dddgn.alice.decision.BotEventLog.record(bot, "DANGER", "warn",
                        "维生中断 " + SurvivalSystem.interruptionReason(hazard),
                        "pos=" + bot.blockPosition().toShortString());
                // D-135：维生中断也通知决策层（逃生已经起好，决策层决定"逃生之后干什么"）
                com.dddgn.alice.decision.GoalDirector.onSurvivalInterrupt(bot,
                        SurvivalSystem.interruptionReason(hazard));
                return;
            }
            Task.Status status = task.tick();
            // L3（D-080）：子目标高亮跟随——Job 的目标随内部阶段变化（树 → 当前原木 → 收集点），
            // 其他任务的目标恒定，比较后只在变化时广播。
            TaskTarget liveTarget = task.target();
            if (liveTarget != null && !liveTarget.equals(target)) {
                target = liveTarget;
                broadcastTarget(target);
            }
            BotTrace.tick(bot);
            switch (status) {
                case DONE -> {
                    lastTaskResult = "done";
                    complete(lastTaskResult, TaskExecutionRecord.TerminalStatus.COMPLETED);
                }
                case FAILED -> {
                    lastTaskResult = "failed:" + task.failureReason();
                    complete(lastTaskResult, TaskExecutionRecord.TerminalStatus.FAILED);
                }
                default -> {
                    // 进行中,保持
                }
            }
        }

        /**
         * **维生出口**（S-1 / P1-C，2026-09-12）：否决之后必须说清"那该去哪"。
         *
         * <p>分工严格照用户的规矩：`SurvivalSystem.nearestSafeRefuge` 是**纯查询**（给落点、不找路），
         * 真正的移动交给 `SurvivalExitTask`（= 已验收的硬路径 `WalkToTask`，纯通行、不挖不放置）。
         * 找不到落点就**如实登记"无出口"**（不假装成功、不造一个必失败的任务）。
         */
        private void startSurvivalExit() {
            BlockPos refuge = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS,
                    bot.blockPosition());
            if (refuge == null) {
                BotLog.warn("[Survival] 维生中断 ⇒ 半径 {} 格内**找不到安全落点**：无出口"
                                + "（如实登记，等玩家/决策层干预；bot 停在 {}）",
                        SurvivalSystem.REFUGE_RADIUS, bot.blockPosition().toShortString());
                return;
            }
            BotLog.warn("[Survival] 维生中断 ⇒ 逃生出口 refuge={}（距 {} 格）——启动 SurvivalExitTask",
                    refuge.toShortString(), fmt3(Math.sqrt(refuge.distSqr(bot.blockPosition()))));
            TaskTarget exitTarget = TaskTarget.block(refuge);
            beginTask(new com.dddgn.alice.task.SurvivalExitTask(bot, refuge), exitTarget);
            broadcastTarget(this.target);
        }

        private void complete(String resultCode, TaskExecutionRecord.TerminalStatus terminalStatus) {
            MineTask mineTask = task instanceof MineTask value ? value : null;
            if (mineTask != null) {
                lastMineStartPos = mineTask.mineStartPos();
            }
            recordTerminal(taskKind, taskTargetDescription, taskStartTick, terminalStatus,
                    resultCode, "idle_after_cleanup",
                    mineTask == null ? RecoveryStage.NONE : mineTask.recoveryStage(),
                    mineTask == null ? List.of() : mineTask.recoveryEvents(),
                    failureReportFor(terminalStatus, resultCode));
            if (mineTask != null) {
                com.dddgn.alice.action.MineBlockRunner.FailureReport report = mineTask.lastFailureReport();
                BotLog.info("[MineTask终态计划证据] target={} attempts={} recoveryAttempts={} recoveryStage={} recoveryEvents={} currentPlanRetained={} reason={} phase={}",
                        taskTargetDescription, mineTask.executionAttempts(), mineTask.recoveryAttempts(), mineTask.recoveryStage(),
                        mineTask.recoveryEvents(), mineTask.currentPlanRetained(), report == null ? "-" : report.reason(),
                        report == null ? "NONE" : report.phase());
            }
            reportItems();
            clearTask();
            // S1 事实层：任务终态进事件环（达成=MILESTONE，失败=FAILURE）
            com.dddgn.alice.decision.BotEventLog.record(bot,
                    terminalStatus == TaskExecutionRecord.TerminalStatus.COMPLETED ? "MILESTONE" : "FAILURE",
                    terminalStatus == TaskExecutionRecord.TerminalStatus.COMPLETED ? "info" : "warn",
                    taskKind + " " + terminalStatus + " " + resultCode,
                    lastExecutionRecord == null ? "" : "reason=" + lastExecutionRecord.terminalReason());
            // D-135：任务终态是最自然的"下一步做什么"时机 —— 交给决策层（有节流）
            com.dddgn.alice.decision.GoalDirector.onTaskTerminal(bot, taskKind,
                    lastExecutionRecord == null ? "" : lastExecutionRecord.terminalReason());
        }

        /**
         * 终态失败报告（S-1 附注，2026-09-12）：**维生中断**不是领域失败 —— 任务自己往往来不及写
         * `failureReport`（`WalkToTask` 被中断时 `failureReason()` 还是空串），记录里于是出现
         * `failureCode=unknown_failure`。这里按**会话事实**补一条 `phase=survival` 的报告，
         * 让 `failureCode` 与 `code=failed:survival_*` 对得上（报告 = 会话事实）。
         */
        private TaskFailureReport failureReportFor(TaskExecutionRecord.TerminalStatus terminalStatus,
                                                   String resultCode) {
            if (terminalStatus == TaskExecutionRecord.TerminalStatus.COMPLETED || task == null) {
                return null;
            }
            if (terminalStatus == TaskExecutionRecord.TerminalStatus.SURVIVAL_INTERRUPTED) {
                return new TaskFailureReport(resultCode, "survival", "", null, null);
            }
            return task.failureReport();
        }

        private void recordTerminal(String kind, String targetDescription, long startTick,
                                    TaskExecutionRecord.TerminalStatus terminalStatus,
                                    String resultCode, String recoveryState) {
            recordTerminal(kind, targetDescription, startTick, terminalStatus, resultCode, recoveryState,
                    RecoveryStage.NONE, List.of(), null);
        }

        private void recordTerminal(String kind, String targetDescription, long startTick,
                                    TaskExecutionRecord.TerminalStatus terminalStatus,
                                    String resultCode, String recoveryState, RecoveryStage recoveryStage,
                                    List<RecoveryStage> recoveryEvents, TaskFailureReport failureReport) {
            BlockPos terminalPos = MovementHelper.footCell(bot.serverLevel(), bot);
            // D-134（决策层契约）：把 **Job 自己报的终止理由** 与 **botId** 一起落进终态记录，
            // 否则 `resultCode` 只会有 `done`/`failed:…`，决策层分不清"配额达成"与"背包满提前收工"。
            String terminalReason = task == null ? "" : String.valueOf(task.terminalReason());
            String botId = bot.getUUID().toString();
            TaskOutcome outcome = new TaskOutcome(kind, targetDescription, terminalStatus, resultCode,
                    terminalPos, failureReport, botId, terminalReason);
            lastExecutionRecord = new TaskExecutionRecord(kind, targetDescription, startTick, serverTick(),
                    terminalStatus, resultCode, terminalPos, recoveryState, recoveryStage, recoveryEvents, outcome,
                    botId, terminalReason);
            TaskFailureReport failure = outcome.failure();
            BotLog.info("task_execution_terminal kind={} target={} startTick={} endTick={} durationTicks={}"
                            + " terminal={} code={} pos={} recovery={} recoveryStage={} recoveryEvents={}"
                            + " failureCode={} failurePhase={} failureDetails={}",
                    lastExecutionRecord.taskKind(), lastExecutionRecord.targetDescription(),
                    lastExecutionRecord.startServerTick(), lastExecutionRecord.endServerTick(),
                    lastExecutionRecord.durationTicks(), lastExecutionRecord.terminalStatus(),
                    lastExecutionRecord.resultCode(), lastExecutionRecord.terminalBotPos().toShortString(),
                    lastExecutionRecord.recoveryState(), lastExecutionRecord.recoveryStage(),
                    lastExecutionRecord.recoveryEvents(), failure == null ? "-" : failure.code(),
                    failure == null ? "-" : failure.phase(), failure == null ? "-" : failure.details());
            BotLog.info("task_terminal_reason kind={} botId={} terminalReason={}",
                    lastExecutionRecord.taskKind(), lastExecutionRecord.botId(),
                    lastExecutionRecord.terminalReason());
        }

        /** 任务收尾:清任务、清作用域、广播清除高亮、**输入归零**。 */
        void clearTask() {
            if (task != null) {
                // L2 生命周期保证（2026-09-13 用户实测）：任务终止时若还开着容器菜单 ⇒ 强制关闭，
                // 否则箱子盖子会一直开着（`ContainerOpenersCounter` 没减回去）。
                MenuLifecycle.closeOpen(bot, "clearTask:" + taskKind);
                // 基-4：任务结束 ⇒ 清掉"正在跑的任务"登记（否则重启报告会瞎报）
                com.dddgn.alice.decision.DecisionState.get(bot.getServer()).clearTask(bot.getUUID());
                String closedScope = com.dddgn.alice.ledger.WorldModLedger.closeScope(
                        bot.getServer(), bot.getUUID());
                // 执行期写入预算收尾（D-106）：一行可观测摘要（breaks/places 对上限、豁免、拒绝次数）
                com.dddgn.alice.action.WriteBudget.closeScope(closedScope);
                // 账本保持"活的"：现场已不是我方方块的条目就地销掉（场景重放/别人拆掉/我方已拆）
                com.dddgn.alice.ledger.WorldModLedger.dropStale(bot.serverLevel());
                var pendingTemp = com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(
                        bot.getServer(), closedScope);
                scope.end();
                task = null;
                target = null;
                broadcastTarget(null);
                // **输入归零**（2026-09-12 J8 收尾）：`BotController.onUpdate()` 每 tick 都会把**上次留下的
                // 输入**继续压到 bot 上，而清任务之后没有任务再去 drive 它（`PathSession` 只在自己被 tick
                // 到终态时才 `stopMovement()`）。**显式打断**（`/alice region stop`）恰好停在半路，
                // 不归零就会出现"说停了却还在走/还在跳"。只在确实有残留输入时清，免得刷日志。
                if (bot.controller().hasActiveMovement()) {
                    BotLog.info("[alice] 任务收尾：清除残留移动输入（{}）",
                            bot.controller().getInputStateString());
                    bot.controller().stopMovement();
                }
                if (!pendingTemp.isEmpty()) {
                    // **只报信号，不自动追任务**（2026-09-11 简化，D-103）：
                    // 原先在这里自动追加一个"远程恢复任务"，但那时 bot 已经离开脚手架，
                    // 于是被迫引入"走回去 / 跨场景寻路 / 站位选择 / 侧拆兜底 / 放支撑块"——
                    // 复杂度与失败几乎都来自这个**错误的位置**（实测：走不到 + 往返跑）。
                    // 设计文档 §12.3 的原意是"**仍在脚手架上时**自上而下拆除，才允许离开"——
                    // 那属于**放方块的那个任务**（会话内），将在 J7 攀爬落地时接上。
                    BotLog.warn("world_mod_ledger_close scope={} 仍有 {} 条我方临时放置未拆除"
                                    + "（建拆同权未闭合；如需手动清理用 /alice restore）",
                            closedScope, pendingTemp.size());
                }
            }
        }

        /** M1 验收:任务结束时汇报作用域内仍存活的掉落物(实时坐标可得)。 */
        private void reportItems() {
            List<ItemEntity> items = scope.liveItems();
            if (items.isEmpty()) {
                BotLog.info("任务结束: 作用域内无存活掉落物");
                return;
            }
            for (ItemEntity item : items) {
                BotLog.info("任务结束掉落物: {} x{} y{} z{}",
                        item.getItem().getItem(), item.getBlockX(), item.getBlockY(), item.getBlockZ());
            }
        }
    }
}
