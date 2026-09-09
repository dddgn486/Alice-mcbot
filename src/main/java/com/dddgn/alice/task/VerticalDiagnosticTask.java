package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * DOWNWARD（垂直下落 1 格）诊断任务（D-048）：一次右键验证执行与守卫两条路径。
 *
 * <ol>
 *   <li>正例：`(0,64,45)` → `(0,63,45)`，破坏脚下后掉 1 格，期望 `COMPLETED`；</li>
 *   <li>竖井对照：`(2,64,45)` → `(2,63,45)`（1×1 竖井）——Baritone 原样语义下应可规划（`shaft_plan=REACHED`），
 *       逃生守卫留给后续安全模式。</li>
 * </ol>
 */
public final class VerticalDiagnosticTask implements Task {
    public static final BlockPos OPEN_START = new BlockPos(0, 64, 45);
    public static final BlockPos OPEN_GOAL = new BlockPos(0, 63, 45);
    public static final BlockPos SHAFT_START = new BlockPos(2, 64, 45);
    public static final BlockPos SHAFT_GOAL = new BlockPos(2, 63, 45);

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private int phase;   // 0=未开始 1=执行中 2=守卫检查 3=完成
    private int ticks;
    private PathRetryRunner runner;
    private String failure = "";
    private boolean executePass;
    private boolean guardPass;

    public VerticalDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(OPEN_GOAL);
    }

    @Override
    public Status tick() {
        if (++ticks > 600) {
            failure = "VERTICAL_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> {
                ensureStonePickaxe();
                teleport(OPEN_START);
                runner = new PathRetryRunner(bot, request(OPEN_START, OPEN_GOAL),
                        PathRetryRunner.DEFAULT_MAX_REPLANS, "vertical-open");
                phase = 1;
                return Status.RUNNING;
            }
            case 1 -> {
                PathRetryRunner.State state = runner.tick();
                if (state == PathRetryRunner.State.RUNNING) {
                    return Status.RUNNING;
                }
                executePass = state == PathRetryRunner.State.DONE;
                BotLog.info("[Vertical] downward_execute={} detail={} replans={}",
                        executePass ? "PASS" : "FAIL",
                        runner.result() == null ? "-" : runner.result().status().name(),
                        runner.replans());
                phase = 2;
                return Status.RUNNING;
            }
            case 2 -> {
                // Baritone 原样语义下，1x1 竖井也应可规划（逃生守卫属后续安全模式）
                teleport(SHAFT_START);
                PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(),
                        request(SHAFT_START, SHAFT_GOAL));
                guardPass = plan.reached();
                BotLog.info("[Vertical] shaft_plan={} status={} movements={}",
                        guardPass ? "REACHED" : "REFUSED", plan.status(), plan.movements().size());
                phase = 3;
                return finish();
            }
            default -> {
                return finish();
            }
        }
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private PathRequest request(BlockPos start, BlockPos goal) {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(), start, goal);
        return new PathRequest(base.botId(), start, base.goal(), base.allowedMovementTypes(),
                com.dddgn.alice.pathing.core.search.SearchBudget.of(
                        CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                "vertical-task");
    }

    /** 夹具：确保有石镐（挖脚下用；否则徒手 150 tick）。 */
    private void ensureStonePickaxe() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(net.minecraft.world.item.Items.STONE_PICKAXE)) {
                return;
            }
        }
        inventory.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean allPass = executePass && guardPass;
        String summary = "downward_execute=" + (executePass ? "PASS" : "FAIL")
                + " shaft_plan=" + (guardPass ? "REACHED" : "REFUSED");
        BotLog.info("[Vertical] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] DOWNWARD 自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "VERTICAL_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
