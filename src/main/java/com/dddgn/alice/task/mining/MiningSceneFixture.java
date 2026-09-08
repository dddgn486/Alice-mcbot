package com.dddgn.alice.task.mining;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.TaskExecutionRecord;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.MiningReplanFixturePacket;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 场景 A 的开发期自动启动夹具；不构造世界，不修改 MineTask。 */
public final class MiningSceneFixture {
    public static final BlockPos SCENE_A_BOT_START = new BlockPos(0, 64, 0);
    public static final BlockPos SCENE_A_TARGET = new BlockPos(4, 64, 4);
    public static final BlockPos SCENE_B_BOT_START = new BlockPos(-3, 64, 0);
    public static final BlockPos SCENE_B_TARGET = new BlockPos(0, 64, 3);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private MiningSceneFixture() {
    }

    private record Session(String scene, ServerPlayer observer, BotPlayer bot, BlockPos target,
                           BlockPos botStart, long startedTick) {
    }

    public static void startSceneA(ServerPlayer player) {
        startScene(player, "A", SCENE_A_BOT_START, SCENE_A_TARGET);
    }

    public static void startSceneB(ServerPlayer player) {
        startScene(player, "B", SCENE_B_BOT_START, SCENE_B_TARGET);
    }

    private static void startScene(ServerPlayer player, String scene, BlockPos botStart, BlockPos target) {
        ServerLevel level = player.serverLevel();
        if (level.getBlockState(target).isAir()) {
            player.sendSystemMessage(Component.literal(
                    "[MineTask场景" + scene + "] 目标不存在；请先执行 /function alice_test:scene_" + scene.toLowerCase())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!isValidFoot(level, botStart)) {
            player.sendSystemMessage(Component.literal(
                    "[MineTask场景" + scene + "] Bot 起点无有效支撑或空间；请重新生成场景 " + scene)
                    .withStyle(ChatFormatting.RED));
            return;
        }

        BotPlayer bot = BotManager.firstInLevel(level);
        try {
            if (bot == null) {
                bot = BotManager.spawn(level, botStart, "AliceSceneBot");
            } else if (BotManager.isBusy(bot)) {
                player.sendSystemMessage(Component.literal(
                        "[MineTask场景" + scene + "] 当前 Bot 正在执行任务，未替换或传送")
                        .withStyle(ChatFormatting.RED));
                return;
            } else {
                bot.teleportTo(botStart.getX() + 0.5D, botStart.getY(), botStart.getZ() + 0.5D);
                BotManager.saveToWorld(bot);
            }
        } catch (RuntimeException exception) {
            player.sendSystemMessage(Component.literal(
                    "[MineTask场景" + scene + "] Bot 启动失败：" + exception.getMessage())
                    .withStyle(ChatFormatting.RED));
            BotLog.warn("[MiningSceneFixture] scene={} phase=START_FAILED reason={}", scene,
                    exception.toString());
            return;
        }

        BotManager.assignTarget(bot, TaskTarget.block(target));
        SESSIONS.put(player.getUUID(), new Session(scene, player, bot, target.immutable(),
                botStart.immutable(), level.getServer().getTickCount()));
        sync(player, true, target, botStart, "SCENE_" + scene + "_RUNNING");
        player.sendSystemMessage(Component.literal(
                "[MineTask场景" + scene + "] 已自动准备 Bot 并启动；绿色=目标，青色=Bot 起点")
                .withStyle(ChatFormatting.GREEN));
        BotLog.info("[MiningSceneFixture] scene={} phase=STARTED observer={} bot={} start={} target={}",
                scene, player.getGameProfile().getName(), bot.getName().getString(),
                botStart.toShortString(), target.toShortString());
    }

    public static void clear(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
        sync(player, false, null, null, "CLEARED");
        player.sendSystemMessage(Component.literal(
                "[MineTask场景] 已清除会话和高亮；方块与 Bot 保持不变")
                .withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) return;
        for (Map.Entry<UUID, Session> entry : new java.util.ArrayList<>(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            if (BotManager.isBusy(session.bot())) continue;
            TaskExecutionRecord record = BotManager.lastExecutionRecord(session.bot());
            if (record == null || record.startServerTick() < session.startedTick()) continue;

            String summary = "[MineTask场景" + session.scene() + "] 终态=" + record.terminalStatus()
                    + " result=" + record.resultCode()
                    + " durationTicks=" + record.durationTicks()
                    + " terminalBot=" + record.terminalBotPos().toShortString();
            session.observer().sendSystemMessage(Component.literal(summary)
                    .withStyle(record.terminalStatus() == TaskExecutionRecord.TerminalStatus.COMPLETED
                            ? ChatFormatting.GREEN : ChatFormatting.RED));
            sync(session.observer(), true, session.target(), session.botStart(),
                    "SCENE_" + session.scene() + "_FINISHED");
            BotLog.info("[MiningSceneFixture] scene={} phase=FINISHED bot={} terminalStatus={} result={} durationTicks={} terminalBot={}",
                    session.scene(), session.bot().getName().getString(), record.terminalStatus(), record.resultCode(),
                    record.durationTicks(), record.terminalBotPos().toShortString());
            SESSIONS.remove(entry.getKey());
        }
    }

    private static boolean isValidFoot(ServerLevel level, BlockPos foot) {
        return com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot.above());
    }

    private static void sync(ServerPlayer player, boolean active, BlockPos target,
                             BlockPos botStart, String phase) {
        AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MiningReplanFixturePacket(active, target, null, botStart, phase));
    }
}
