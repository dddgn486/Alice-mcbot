package com.dddgn.alice.bot;

import com.dddgn.alice.action.BotMiner;
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
import com.dddgn.alice.task.DescendDiagnosticTask;
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
public static void assignFollow(BotPlayer bot, ServerPlayer target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return;
        session.assignFollow(target);
    }

    /** 取消当前跟随；其他任务不受此入口影响。 */
    public static boolean stopFollow(BotPlayer bot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null || !(session.task instanceof com.dddgn.alice.task.FollowTask)) return false;
        session.complete("follow_stopped", TaskExecutionRecord.TerminalStatus.CANCELLED_FOLLOW);
        return true;
    }

    /** 给假人分配独立「放置指定方块」任务。 */
    public static void assignPlace(BotPlayer bot, BlockPos target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return;
        session.assignPlace(target);
    }

    /** 给假人分配纯 HARD_PATH 脚位移动任务。 */
    public static void assignWalkTo(BotPlayer bot, BlockPos goalFoot) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return;
        session.assignWalkTo(goalFoot);
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
    public static void assignMine(BotPlayer bot, BlockPos target) {
        assignTarget(bot, TaskTarget.block(target));
        BotLog.info("分配挖掘任务: bot={} target={}",
                bot.getName().getString(), target.toShortString());
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
        public void assignFollow(ServerPlayer targetPlayer) {
            if (!replaceTaskIfRunning()) return;
            TaskTarget assignedTarget = TaskTarget.entity(targetPlayer.getId());
            beginTask(new com.dddgn.alice.task.FollowTask(bot, targetPlayer), assignedTarget);
            broadcastTarget(this.target);
        }

        public void assignPlace(BlockPos targetPos) {
            if (!replaceTaskIfRunning()) return;
            TaskTarget assignedTarget = TaskTarget.block(targetPos);
            beginTask(new PlaceTask(bot, targetPos), assignedTarget);
            broadcastTarget(this.target);
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
                    scope.begin(newTarget.blockPos(), 8);
                    // 单目标默认只走真实可通行曲面的 A*；通道规划后续仅在曲面不可达时显式接入。
                    beginTask(new MineTask(bot, newTarget.blockPos(), scope), newTarget);
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
                BotMiner.FailureReport report = mineTask.lastFailureReport();
                BotLog.info("[MineTask终态计划证据] target={} attempts={} recoveryAttempts={} recoveryStage={} recoveryEvents={} currentPlanRetained={} reason={} planInvalidation={}",
                        taskTargetDescription, mineTask.executionAttempts(), mineTask.recoveryAttempts(), mineTask.recoveryStage(),
                        mineTask.recoveryEvents(), mineTask.currentPlanRetained(), report == null ? "-" : report.reason(),
                        report == null ? "NONE" : report.planInvalidation());
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
                scope.end();
                task = null;
                target = null;
                broadcastTarget(null);
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
