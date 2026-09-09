package com.dddgn.alice.task.mining;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.MiningReplanFixturePacket;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 开发期可视动态障碍夹具；只放置真实石头，不控制 MineTask 的恢复逻辑。 */
public final class MiningReplanFixture {
    public static final BlockPos SCENE_C_BOT_START = new BlockPos(0, 64, 0);
    public static final BlockPos SCENE_C_TARGET = new BlockPos(10, 64, 0);
    public static final BlockPos SCENE_C_OBSTACLE = new BlockPos(5, 64, 0);
    private static final int MAX_WAIT_TICKS = 200;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private MiningReplanFixture() {
    }

    private record Session(ServerPlayer player, BotPlayer bot, BlockPos target, BlockPos obstacle,
                           BlockPos botStart, int startTick, boolean placed) {
        Session {
            target = target.immutable();
            obstacle = obstacle.immutable();
            botStart = botStart.immutable();
        }

        Session markObstaclePlaced() {
            return new Session(player, bot, target, obstacle, botStart, startTick, true);
        }

        boolean isObstaclePlaced() {
            return placed;
        }
    }

    public static void selectObstacle(ServerPlayer player, BlockPos obstacle) {
        BlockPos pos = obstacle.immutable();
        if (!player.serverLevel().getBlockState(pos).isAir()) {
            player.sendSystemMessage(Component.literal("[重规划测试] 障碍位置必须是空气：" + pos.toShortString())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        player.getPersistentData().putLong("aliceMiningReplanObstacle", pos.asLong());
        sync(player, true, null, pos, null, "SELECTED");
        player.sendSystemMessage(Component.literal("[重规划测试] 已选择橙色障碍位置：" + pos.toShortString())
                .withStyle(ChatFormatting.GOLD));
    }

    public static void startSceneC(ServerPlayer player) {
        if (!player.serverLevel().getBlockState(SCENE_C_OBSTACLE).isAir()) {
            player.sendSystemMessage(Component.literal(
                    "[场景C] 动态障碍位置不是空气；请先执行 /function alice_test:scene_c")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        selectObstacle(player, SCENE_C_OBSTACLE);
        startWithAutoBot(player, SCENE_C_TARGET);
    }

    public static void startWithAutoBot(ServerPlayer player, BlockPos target) {
        if (!player.getPersistentData().contains("aliceMiningReplanObstacle")) {
            player.sendSystemMessage(Component.literal("[场景C] 请先 Shift+右键选择动态障碍位置")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ServerLevel level = player.serverLevel();
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            try {
                bot = BotManager.spawn(level, SCENE_C_BOT_START, "AliceSceneBot");
            } catch (RuntimeException exception) {
                player.sendSystemMessage(Component.literal("[场景C] Bot 启动失败：" + exception.getMessage())
                        .withStyle(ChatFormatting.RED));
                BotLog.warn("[MiningReplanFixture] scene=C phase=START_FAILED reason={}",
                        exception.toString());
                return;
            }
        } else if (BotManager.isBusy(bot)) {
            player.sendSystemMessage(Component.literal("[场景C] 当前 Bot 正在执行任务，无法启动")
                    .withStyle(ChatFormatting.RED));
            return;
        } else {
            bot.teleportTo(SCENE_C_BOT_START.getX() + 0.5D, SCENE_C_BOT_START.getY(),
                    SCENE_C_BOT_START.getZ() + 0.5D);
            BotManager.saveToWorld(bot);
        }
        start(player, target);
    }

    public static void start(ServerPlayer player, BlockPos target) {
        if (!player.getPersistentData().contains("aliceMiningReplanObstacle")) {
            player.sendSystemMessage(Component.literal("[重规划测试] 请先 Shift+右键选择障碍位置")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        BlockPos immutableTarget = target.immutable();
        BlockPos obstacle = BlockPos.of(player.getPersistentData().getLong("aliceMiningReplanObstacle"));
        ServerLevel level = player.serverLevel();
        if (level.getBlockState(immutableTarget).isAir() || !level.getBlockState(obstacle).isAir()
                || immutableTarget.equals(obstacle)) {
            player.sendSystemMessage(Component.literal("[重规划测试] 目标/障碍位置无效，测试未启动")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            player.sendSystemMessage(Component.literal("[重规划测试] 当前世界没有 Bot")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        BotManager.assignTarget(bot, TaskTarget.block(immutableTarget));
        SESSIONS.put(player.getUUID(), new Session(player, bot, immutableTarget, obstacle,
                bot.blockPosition(), level.getServer().getTickCount(), false));
        sync(player, true, immutableTarget, obstacle, bot.blockPosition(), "ARMED");
        player.sendSystemMessage(Component.literal("[重规划测试] 已启动；绿色=目标，橙色=动态障碍")
                .withStyle(ChatFormatting.GREEN));
        BotLog.info("[MiningReplanFixture] phase=ARMED player={} target={} obstacle={} botStart={}",
                player.getGameProfile().getName(), immutableTarget.toShortString(), obstacle.toShortString(),
                bot.blockPosition().toShortString());
    }

    public static void clear(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null && session.isObstaclePlaced()
                && player.serverLevel().getBlockState(session.obstacle()).is(Blocks.STONE)) {
            player.serverLevel().setBlockAndUpdate(session.obstacle(), Blocks.AIR.defaultBlockState());
        }
        player.getPersistentData().remove("aliceMiningReplanObstacle");
        sync(player, false, null, null, null, "CLEARED");
        player.sendSystemMessage(Component.literal("[重规划测试] 已清理高亮和夹具石头")
                .withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) return;
        int now = event.getServer().getTickCount();
        for (Map.Entry<UUID, Session> entry : new java.util.ArrayList<>(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            if (session.isObstaclePlaced()) continue;
            if (!BotManager.isBusy(session.bot())) {
                fail(entry.getKey(), session, "TASK_NOT_RUNNING");
                continue;
            }
            MiningPlan plan = BotManager.currentMiningPlan(session.bot());
            if (plan == null) continue;
            if (!plan.path().reached()) {
                fail(entry.getKey(), session, "INITIAL_PATH_" + plan.path().status());
                continue;
            }
            if (now - session.startTick() > MAX_WAIT_TICKS) {
                fail(entry.getKey(), session, "WAIT_TIMEOUT");
                continue;
            }
            BlockPos botFoot = session.bot().blockPosition();
            double distance = Math.sqrt(botFoot.distSqr(session.obstacle()));
            boolean moved = !botFoot.equals(session.botStart());
            if (moved && distance >= 2.0D && distance <= 4.0D
                    && session.player().serverLevel().getBlockState(session.obstacle()).isAir()) {
                session.player().serverLevel().setBlockAndUpdate(session.obstacle(), Blocks.STONE.defaultBlockState());
                entry.setValue(session.markObstaclePlaced());
                sync(session.player(), true, session.target(), session.obstacle(),
                        session.botStart(), "OBSTACLE_PLACED");
                session.player().sendSystemMessage(Component.literal("[重规划测试] 服务端已放置动态障碍："
                        + session.obstacle().toShortString()).withStyle(ChatFormatting.GOLD));
                BotLog.info("[MiningReplanFixture] phase=OBSTACLE_PLACED target={} obstacle={} botFoot={} distance={} triggerTick={} initialPathStatus={}",
                        session.target().toShortString(), session.obstacle().toShortString(), botFoot.toShortString(),
                        String.format(java.util.Locale.ROOT, "%.2f", distance), now, plan.path().status());
            }
        }
    }

    private static void fail(UUID id, Session session, String reason) {
        SESSIONS.remove(id);
        sync(session.player(), true, session.target(), session.obstacle(),
                session.botStart(), "FAILED");
        session.player().sendSystemMessage(Component.literal("[重规划测试] 测试未布障：" + reason)
                .withStyle(ChatFormatting.RED));
        BotLog.warn("[MiningReplanFixture] phase=FAILED target={} obstacle={} reason={}",
                session.target().toShortString(), session.obstacle().toShortString(), reason);
    }

    private static void sync(ServerPlayer player, boolean active, BlockPos target,
                             BlockPos obstacle, BlockPos botStart, String phase) {
        AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MiningReplanFixturePacket(active, target, obstacle, botStart, phase));
    }
}
