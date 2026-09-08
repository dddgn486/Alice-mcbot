package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 单方块破坏会话：按 tick 推进破坏进度（对齐 Baritone 的 {@code Movement.prepared} +
 * 原版 {@code ServerPlayerGameMode} 流程）。
 *
 * <p>取代 {@code level.destroyBlock(...)} 的瞬间销毁语义：
 * <ul>
 *   <li>开始时选最佳工具 + 计算命中面 + 发送 {@code START_DESTROY_BLOCK}；</li>
 *   <li>每 tick 累加 {@code getDestroyProgress}、上报进度、摆动、持续朝向；</li>
 *   <li>进度满时通过 {@code gameMode.destroyBlock} 销毁并广播方块更新；</li>
 *   <li>越界（超出触及距离）或超时都会以明确失败码结束。</li>
 * </ul>
 */
public final class BlockBreakSession {
    public enum Status { IN_PROGRESS, DONE, FAILED }

    /** 单方块破坏上限：60 秒（黑曜石 + 钻石镐约 9.4 秒，留足余量）。 */
    public static final int MAX_BREAK_TICKS = 20 * 60;

    private final ServerPlayer bot;
    private final ServerLevel level;
    private final BlockPos pos;
    private final int maxTicks;

    private Status status = Status.IN_PROGRESS;
    private String failureCode = "";
    private float progress;
    private int ticks;
    private boolean started;
    private Direction face = Direction.UP;

    private BlockBreakSession(ServerPlayer bot, ServerLevel level, BlockPos pos, int maxTicks) {
        this.bot = bot;
        this.level = level;
        this.pos = pos.immutable();
        this.maxTicks = maxTicks;
    }

    public static BlockBreakSession begin(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        return new BlockBreakSession(bot, level, pos, MAX_BREAK_TICKS);
    }

    public BlockPos pos() {
        return pos;
    }

    public Status status() {
        return status;
    }

    public String failureCode() {
        return failureCode;
    }

    public float progress() {
        return progress;
    }

    public Status tick() {
        if (status != Status.IN_PROGRESS) {
            return status;
        }
        ticks++;
        if (ticks > maxTicks) {
            return fail("BREAK_PROGRESS_TIMEOUT");
        }

        BlockState current = level.getBlockState(pos);
        if (current.isAir()) {
            // 已被其他原因破坏（爆炸/流体/其他 bot）：视为完成，不做二次销毁
            status = Status.DONE;
            return status;
        }

        if (!BlockInteraction.reachable(bot, pos)) {
            return fail("BREAK_OUT_OF_REACH");
        }

        if (!started) {
            BlockInteraction.switchToBestToolFor(bot, pos);
            face = BlockInteraction.faceToward(bot, pos);
            bot.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    face, level.getMaxBuildHeight(), -1);
            current.attack(level, pos, bot);
            started = true;
        }

        BlockInteraction.faceBlock(bot, pos);
        progress += current.getDestroyProgress(bot, level, pos);
        level.destroyBlockProgress(bot.getId(), pos, (int) (progress * 10.0F));
        bot.swing(InteractionHand.MAIN_HAND);

        if (progress >= 1.0F) {
            BlockState before = level.getBlockState(pos);
            bot.gameMode.destroyBlock(pos);
            level.destroyBlockProgress(bot.getId(), pos, -1);
            BlockState after = level.getBlockState(pos);
            level.sendBlockUpdated(pos, before, after, 3);
            status = Status.DONE;
            BotLog.info("block_break_done bot={} pos={} ticks={}", bot.getName().getString(),
                    pos.toShortString(), ticks);
        }
        return status;
    }

    private Status fail(String code) {
        failureCode = code;
        status = Status.FAILED;
        level.destroyBlockProgress(bot.getId(), pos, -1);
        BotLog.warn("block_break_failed bot={} pos={} code={} ticks={} progress={}",
                bot.getName().getString(), pos.toShortString(), code, ticks,
                String.format(java.util.Locale.ROOT, "%.2f", progress));
        return status;
    }
}
