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
        return bot;
    }

    /** 移除假人(实体 + PlayerList + 世界存档记录)。 */
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
        com.dddgn.alice.job.GoalSpec spec = com.dddgn.alice.job.GoalSpec.harvestUnits(
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT, 16, 4, 3600);
        com.dddgn.alice.job.lumber.LumberJob job = new com.dddgn.alice.job.lumber.LumberJob(
                bot, spec, session.scope(),
                new com.dddgn.alice.job.lumber.LumberCandidateSource(),
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
    public static boolean assignMineJob(BotPlayer bot, ServerPlayer observer,
                                        com.dddgn.alice.job.mine.MineCandidateSource.Target target,
                                        int quota, int radius) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        com.dddgn.alice.job.GoalSpec spec = com.dddgn.alice.job.GoalSpec.mineBlocks(
                bot.blockPosition(), radius, quota, 3600);
        com.dddgn.alice.job.mine.MineJob job = new com.dddgn.alice.job.mine.MineJob(
                bot, spec, session.scope(),
                new com.dddgn.alice.job.mine.MineCandidateSource(target, radius),
                new com.dddgn.alice.job.policy.NearestPolicy());
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

    /** J6-b2：容器绕行自检（断言 bot 不为取目标而拆箱子，D-095）。 */
    public static boolean assignClearGuardCheck(BotPlayer bot, ServerPlayer observer) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || session.task != null) return false;
        session.beginTask(new com.dddgn.alice.task.ClearGuardCheckTask(bot, session.scope()),
                TaskTarget.block(com.dddgn.alice.task.ClearGuardCheckTask.START_FOOT));
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
            HazardState hazard = SurvivalSystem.tick(session.bot());
            session.tick(hazard);
        }
    }

    private static String fmt3(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** 服务器启动完成:恢复存档假人(若有)。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TransferLedgerData.get(event.getServer()).suspendUnfinished(TransferCodes.SERVER_RESTART,
                event.getServer().getTickCount());
        restoreFromWorld(event.getServer());
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
                    // 单目标默认只走真实可通行曲面的 A*；通道规划后续仅在曲面不可达时显式接入。
                    beginTask(new MineTask(bot, newTarget.blockPos(), scope,
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

        private void tick(HazardState hazard) {
            if (task == null) {
                return;
            }
            if (SurvivalSystem.shouldInterrupt(hazard)) {
                if (task instanceof TransferTask transfer) {
                    transfer.survivalInterrupted(SurvivalSystem.interruptionReason(hazard));
                }
                lastTaskResult = "failed:" + SurvivalSystem.interruptionReason(hazard);
                BotLog.warn("任务因维生危险中断: bot={} reason={}", bot.getName().getString(), lastTaskResult);
                complete(lastTaskResult, TaskExecutionRecord.TerminalStatus.SURVIVAL_INTERRUPTED);
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

        private void complete(String resultCode, TaskExecutionRecord.TerminalStatus terminalStatus) {
            MineTask mineTask = task instanceof MineTask value ? value : null;
            if (mineTask != null) {
                lastMineStartPos = mineTask.mineStartPos();
            }
            recordTerminal(taskKind, taskTargetDescription, taskStartTick, terminalStatus,
                    resultCode, "idle_after_cleanup",
                    mineTask == null ? RecoveryStage.NONE : mineTask.recoveryStage(),
                    mineTask == null ? List.of() : mineTask.recoveryEvents(),
                    terminalStatus == TaskExecutionRecord.TerminalStatus.COMPLETED || task == null
                            ? null : task.failureReport());
            if (mineTask != null) {
                com.dddgn.alice.action.MineBlockRunner.FailureReport report = mineTask.lastFailureReport();
                BotLog.info("[MineTask终态计划证据] target={} attempts={} recoveryAttempts={} recoveryStage={} recoveryEvents={} currentPlanRetained={} reason={} phase={}",
                        taskTargetDescription, mineTask.executionAttempts(), mineTask.recoveryAttempts(), mineTask.recoveryStage(),
                        mineTask.recoveryEvents(), mineTask.currentPlanRetained(), report == null ? "-" : report.reason(),
                        report == null ? "NONE" : report.phase());
            }
            reportItems();
            clearTask();
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
            BlockPos terminalPos = bot.blockPosition();
            TaskOutcome outcome = new TaskOutcome(kind, targetDescription, terminalStatus, resultCode,
                    terminalPos, failureReport);
            lastExecutionRecord = new TaskExecutionRecord(kind, targetDescription, startTick, serverTick(),
                    terminalStatus, resultCode, terminalPos, recoveryState, recoveryStage, recoveryEvents, outcome);
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
        }

        /** 任务收尾:清任务、清作用域、广播清除高亮。 */
        void clearTask() {
            if (task != null) {
                String closedScope = com.dddgn.alice.ledger.WorldModLedger.closeScope(
                        bot.getServer(), bot.getUUID());
                // 账本保持"活的"：现场已不是我方方块的条目就地销掉（场景重放/别人拆掉/我方已拆）
                com.dddgn.alice.ledger.WorldModLedger.dropStale(bot.serverLevel());
                var pendingTemp = com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(
                        bot.getServer(), closedScope);
                scope.end();
                task = null;
                target = null;
                broadcastTarget(null);
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
