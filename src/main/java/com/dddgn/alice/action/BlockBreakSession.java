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
            boolean destroyed = bot.gameMode.destroyBlock(pos);
            level.destroyBlockProgress(bot.getId(), pos, -1);
            BlockState after = level.getBlockState(pos);
            // ⭐ D-323（2026-09-18 真机发现）：**世界没变 ⇒ 这次破坏没有发生**。
            // 真机实测：FTB 认领拦下的 4 次破坏，我们全打了 `block_break_done` + `COMPLETED`，
            // 而存档里那 4 格仍是 `minecraft:dirt` ⇒ 决策层拿不到任何失败码、写预算也记了一笔假账。
            // 任何"取消破坏"的来源（FTB 认领 / 别的保护模组 / 事件层取消 / 冒险模式限制）都在这里现形。
            // 判据用**方块对象身份**（`BlockState` 每个状态只有一个实例）：只要不是原来那个状态就算破坏发生
            // （破坏后留下水/另一半高草这类"换了别的状态"也算成功 ✓）。
            if (after == before) {
                BotLog.warn("[WRITE-REFUSED] break pos={} by=BlockBreakSession reason=world_unchanged"
                                + "（destroyBlock={} 方块仍是 {} —— 被保护层取消 / 未生效）{}",
                        pos.toShortString(), destroyed, before.getBlock().getName().getString(),
                        thirdPartyNote());
                return fail("REFUSED");
            }
            level.sendBlockUpdated(pos, before, after, 3);
            status = Status.DONE;
            BotLog.info("block_break_done bot={} pos={} ticks={}", bot.getName().getString(),
                    pos.toShortString(), ticks);
        }
        return status;
    }

    /**
     * 中止破坏会话：清理客户端裂纹广播（`destroyBlockProgress(-1)`）。
     * 对照 Baritone `PathExecutor:603-608` → `BlockBreakHelper:43-50`（取消时 `resetBlockRemoving`）。
     */
    public void abort() {
        if (status == Status.IN_PROGRESS) {
            fail("BREAK_ABORTED");
        }
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

    /**
     * **归因**（补 `D-323` 留的尾巴）：`reason=world_unchanged` 只说明"世界没变"，**不说明谁拦的** ——
     * 原日志只能写"疑似被保护层取消 / 未生效"，读日志的人分不清是**我们自己的闸门**、**方块没被真的破坏**，
     * 还是**别的模组**（实测：2026-09-23 伐木课程树的破坏在 FTB 认领内被静默拦下）。
     *
     * <p>单方块路径走 `gameMode.destroyBlock` ⇒ **会触发 Forge 破坏事件** ⇒ 第三方认领能在这里拦下；
     * 而批量路径走 `level.destroyBlock`，事件不触发、第三方看不见（`D-326`，那边是**事前预检**）。
     * ⇒ 这里问一次 FTB 自己的裁决函数（`FtbChunksBridge`），把"谁拦的"写成**名字**。
     * FTB 不在场 ⇒ 桥 fail-open 返回 `null` ⇒ 如实说"不是它"，绝不编一个原因。
     */
    private String thirdPartyNote() {
        try {
            String reason = com.dddgn.alice.protection.ThirdPartyProtection.refusalReason(bot, pos);
            return reason == null
                    ? "（第三方保护预检：不拦 ⇒ 不是 FTB 认领，须查我方闸门/方块本身）"
                    : "（第三方保护预检：FTB=" + reason + "）";
        } catch (Throwable failure) {
            return "（第三方保护预检异常：" + failure + "）";
        }
    }
}
