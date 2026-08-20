package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.TargetPacket;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.MineTask;
import com.dddgn.alice.task.PlaceTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
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
 * 假人管理:tick 驱动、创建、当前动作分配。
 * <p>
 * ⚠️ 审查点 1:tick 采用「全局 ServerTickEvent 驱动」而非实体自身 tick 内嵌动作逻辑,
 * 与 mc_aiplayer 的 ActionPack.onUpdate 一致——好处是动作逻辑与实体解耦、易测。</p>
 * <p>
 * 假人生成走「玩家化」流程(mc_aiplayer 同款):{@link BotPlayer} 继承 {@code ServerPlayer},
 * 伪造 {@link Connection} 后经 {@code PlayerList.placeNewPlayer} 注册——
 * 客户端可见、tick 无 NPE、物理/交互全走原版玩家逻辑(审查点 R9)。</p>
 * <p>
 * 持久化:spawn 即写入 {@link BotWorldData}(主世界 SavedData),服务器重启后
 * {@link #restoreFromWorld} 自动恢复;remove/死亡清除时同步清档。</p>
 */
public final class BotManager {

    /** 所有在线假人。 */
    private static final Map<UUID, BotSession> BOTS = new HashMap<>();

    private BotManager() {
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
        server.getPlayerList().placeNewPlayer(new FakeConnection(PacketFlow.SERVERBOUND), bot);

        // 传送到位(placeNewPlayer 默认放在出生点,需再定位)
        bot.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        // 强制生存:创造模式破方块不掉落、冒险模式禁止破坏(让采集/建造失效)
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        // 注意:不再固定发工具——主手由「行为执行时替换」管理(MineTask 临时设定),
        // 退出重进不再莫名多一把镐(存档按需还原主手,见 saveToWorld)。

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
        int slot = bot.getInventory().selected;
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(slot));
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -2, 0, slot, bot.getInventory().getItem(slot)));
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

    /** 给假人分配独立软地面移动实验，不接入普通挖矿。 */
    public static void assignSoftMoveProbe(BotPlayer bot, BlockPos target) {
        assignSoftMoveProbe(bot, target,
                com.dddgn.alice.pathing.SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
    }

    /** 分配指定后端的软移动实验；SELF_MOVE 仅用于独立客户端回归对比。 */
    public static void assignSoftMoveProbe(BotPlayer bot, BlockPos target,
                                           com.dddgn.alice.pathing.SoftMovementPrimitive.Backend backend) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return;
        session.assignSoftMoveProbe(target, backend);
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
        session.clearTask();
        session.lastTaskResult = "follow_stopped";
        return true;
    }

    /** 给假人分配独立软路径实验，复用曲面 A*，不接入普通挖矿。 */
    public static void assignSoftPathProbe(BotPlayer bot, BlockPos target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return;
        session.assignSoftPathProbe(target);
    }

    /** 给假人分配独立「放置指定方块」任务。 */
    public static void assignPlace(BotPlayer bot, BlockPos target) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) return;
        session.assignPlace(target);
    }

    /** 给假人分配 Bot 专用道路施工任务。 */
    public static void assignRoadBuild(BotPlayer bot, com.dddgn.alice.road.RoadPlan plan) {
        BotSession session = BOTS.get(bot.getUUID());
        if (session == null) {
            BotLog.warn("assignRoadBuild 失败: bot 不存在 uuid={}", bot.getUUID());
            return;
        }
        session.assignRoadBuild(plan);
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
        for (BotSession session : BOTS.values()) {
            HazardState hazard = SurvivalSystem.tick(session.bot());
            session.tick(hazard);
        }
    }

    /** 服务器启动完成:恢复存档假人(若有)。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        restoreFromWorld(event.getServer());
    }

    /** 关服前:冗余写一次档(平时 spawn/remove 已维护,这里兜底防崩溃丢档)。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (BotSession session : BOTS.values()) {
            saveToWorld(session.bot());
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

        /** 分配任务:按目标类型实例化 Task,开启感知作用域,广播高亮。 */
        public void assignSoftMoveProbe(BlockPos targetPos,
                                        com.dddgn.alice.pathing.SoftMovementPrimitive.Backend backend) {
            clearTask();
            this.target = TaskTarget.block(targetPos);
            this.task = new com.dddgn.alice.task.SoftMoveProbeTask(bot, targetPos, backend);
            broadcastTarget(this.target);
        }

        public void assignFollow(ServerPlayer targetPlayer) {
            clearTask();
            this.target = TaskTarget.entity(targetPlayer.getId());
            this.task = new com.dddgn.alice.task.FollowTask(bot, targetPlayer);
            broadcastTarget(this.target);
        }

        public void assignSoftPathProbe(BlockPos targetPos) {
            clearTask();
            this.target = TaskTarget.block(targetPos);
            this.task = new com.dddgn.alice.task.SoftPathProbeTask(bot, targetPos);
            broadcastTarget(this.target);
        }

        public void assignPlace(BlockPos targetPos) {
            clearTask();
            this.target = TaskTarget.block(targetPos);
            this.task = new PlaceTask(bot, targetPos);
            broadcastTarget(this.target);
        }

        public void assignRoadBuild(com.dddgn.alice.road.RoadPlan plan) {
            clearTask();
            if (!plan.isComplete() || plan.level() != bot.level()) {
                lastTaskResult = "failed:road_plan_invalid";
                return;
            }
            this.target = TaskTarget.block(plan.second());
            scope.begin(plan.second(), 8);
            this.task = new com.dddgn.alice.task.RoadBuildTask(bot, plan, scope);
            broadcastTarget(this.target);
        }

        public void assign(TaskTarget newTarget) {
            clearTask(); // 先收尾上一个任务
            this.target = newTarget;
            switch (newTarget.type()) {
                case BLOCK -> {
                    // 任务启动即开启作用域:监听掉落物与方块变化(设计文档 §3.2)
                    scope.begin(newTarget.blockPos(), 8);
                    // 单目标默认只走真实可通行曲面的 A*；通道规划后续仅在曲面不可达时显式接入。
                    this.task = new MineTask(bot, newTarget.blockPos(), scope);
                }
                case ENTITY -> {
                    BotLog.warn("实体目标任务尚未实现: target={}", newTarget.describe());
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
                lastTaskResult = "failed:" + SurvivalSystem.interruptionReason(hazard);
                BotLog.warn("任务因维生危险中断: bot={} reason={}", bot.getName().getString(), lastTaskResult);
                reportItems();
                clearTask();
                return;
            }
            Task.Status status = task.tick();
            switch (status) {
                case DONE -> {
                    lastTaskResult = "done";
                    if (task instanceof MineTask mineTask) {
                        lastMineStartPos = mineTask.mineStartPos();
                    }
                    reportItems();
                    clearTask();
                }
                case FAILED -> {
                    lastTaskResult = "failed:" + task.failureReason();
                    if (task instanceof MineTask mineTask) {
                        lastMineStartPos = mineTask.mineStartPos();
                    }
                    reportItems();
                    clearTask();
                }
                default -> {
                    // 进行中,保持
                }
            }
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
