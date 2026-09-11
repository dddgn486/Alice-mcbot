package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * WalkTo 任务迁移自检（D-060）：一次右键跑完四条路径，全部走**新内核**（R3/R4）。
 *
 * <ol>
 *   <li>`walk_flat`：同层 4 格绕行 → `DONE` 且脚位命中；</li>
 *   <li>`walk_over_wall`：跨越 1 格高墙（ASCEND + DESCEND）→ `DONE` 且脚位命中；</li>
 *   <li>`walk_unreachable`：2 格高柱顶目标 → `FAILED` 且失败码 `walk_no_path`；</li>
 *   <li>`walk_unsafe`：目标无支撑 → `FAILED` 且失败码 `walk_target_not_safe`。</li>
 * </ol>
 */
public final class WalkToDiagnosticTask implements Task {
    public static final BlockPos START = new BlockPos(21, 64, 83);
    public static final BlockPos FLAT_GOAL = new BlockPos(20, 64, 80);
    public static final BlockPos OVER_WALL_GOAL = new BlockPos(25, 64, 83);
    public static final BlockPos UNREACHABLE_GOAL = new BlockPos(25, 66, 88);
    public static final BlockPos UNSAFE_GOAL = new BlockPos(27, 64, 83);

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private int phase;
    private int ticks;
    private WalkToTask subTask;
    private String failure = "";
    private boolean flatPass;
    private boolean overWallPass;
    private boolean unreachablePass;
    private boolean unsafePass;

    public WalkToDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(OVER_WALL_GOAL);
    }

    @Override
    public Status tick() {
        if (++ticks > 1200) {
            failure = "WALKTO_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> {
                return runCheck(FLAT_GOAL, "walk_flat");
            }
            case 1 -> {
                return runCheck(OVER_WALL_GOAL, "walk_over_wall");
            }
            case 2 -> {
                return runCheck(UNREACHABLE_GOAL, "walk_unreachable");
            }
            case 3 -> {
                return runCheck(UNSAFE_GOAL, "walk_unsafe");
            }
            default -> {
                return finish();
            }
        }
    }

    private Status runCheck(BlockPos goal, String key) {
        if (subTask == null) {
            teleport(START);
            subTask = new WalkToTask(bot, goal);
        }
        Status status = subTask.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        boolean arrived = MovementHelper.footCell(bot.serverLevel(), bot).equals(goal);
        String reason = subTask.failureReason();
        boolean pass;
        String detail;
        switch (key) {
            case "walk_flat", "walk_over_wall" -> {
                pass = status == Status.DONE && arrived;
                detail = status + "/foot=" + bot.blockPosition().toShortString();
            }
            case "walk_unreachable" -> {
                pass = status == Status.FAILED && "walk_no_path".equals(reason);
                detail = status + "/reason=" + reason;
            }
            default -> {
                pass = status == Status.FAILED && reason.startsWith("walk_target_not_safe");
                detail = status + "/reason=" + reason;
            }
        }
        switch (key) {
            case "walk_flat" -> flatPass = pass;
            case "walk_over_wall" -> overWallPass = pass;
            case "walk_unreachable" -> unreachablePass = pass;
            default -> unsafePass = pass;
        }
        BotLog.info("[WalkTo] {}={} detail={}", key, pass ? "PASS" : "FAIL", detail);
        subTask = null;
        phase++;
        return phase >= 4 ? finish() : Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean allPass = flatPass && overWallPass && unreachablePass && unsafePass;
        String summary = "walk_flat=" + (flatPass ? "PASS" : "FAIL")
                + " walk_over_wall=" + (overWallPass ? "PASS" : "FAIL")
                + " walk_unreachable=" + (unreachablePass ? "PASS" : "FAIL")
                + " walk_unsafe=" + (unsafePass ? "PASS" : "FAIL");
        BotLog.info("[WalkTo] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] WalkTo 自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "WALKTO_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
