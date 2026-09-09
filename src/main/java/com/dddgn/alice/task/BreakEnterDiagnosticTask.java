package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * BREAK_AND_ENTER 诊断任务（D-068）：一次右键验证规划与执行。
 *
 * <ol>
 *   <li>`plan_a`：`(22,64,100) → (23,64,100)`，目标列 2 格高（躯干 + 头位）→ 首步必须是 BREAK_AND_ENTER；</li>
 *   <li>`plan_b`：`(22,64,98) → (23,64,98)`，目标列仅躯干 1 格 → 首步必须是 BREAK_AND_ENTER；</li>
 *   <li>`execute_a`：实跑 a 路线，期望 `COMPLETED` 且脚位落在 `(23,64,100)`。</li>
 * </ol>
 */
public final class BreakEnterDiagnosticTask implements Task {
    public static final BlockPos START_A = new BlockPos(22, 64, 100);
    public static final BlockPos GOAL_A = new BlockPos(23, 64, 100);
    public static final BlockPos START_B = new BlockPos(22, 64, 98);
    public static final BlockPos GOAL_B = new BlockPos(23, 64, 98);

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private int phase;
    private int ticks;
    private PathRetryRunner runner;
    private String failure = "";
    private boolean planA;
    private boolean planB;
    private boolean executeA;

    public BreakEnterDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(GOAL_A);
    }

    @Override
    public Status tick() {
        if (++ticks > 1200) {
            failure = "BREAK_ENTER_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> {
                ensureStonePickaxe();
                planA = checkPlan(START_A, GOAL_A, "plan_a");
                phase = 1;
                return Status.RUNNING;
            }
            case 1 -> {
                planB = checkPlan(START_B, GOAL_B, "plan_b");
                phase = 2;
                return Status.RUNNING;
            }
            case 2 -> {
                teleport(START_A);
                runner = new PathRetryRunner(bot, request(START_A, GOAL_A),
                        PathRetryRunner.DEFAULT_MAX_REPLANS, "break-enter-execute");
                phase = 3;
                return Status.RUNNING;
            }
            case 3 -> {
                PathRetryRunner.State state = runner.tick();
                if (state == PathRetryRunner.State.RUNNING) {
                    return Status.RUNNING;
                }
                executeA = state == PathRetryRunner.State.DONE && bot.blockPosition().equals(GOAL_A);
                BotLog.info("[BreakEnter] execute_a={} detail={} replans={} foot={}",
                        executeA ? "PASS" : "FAIL",
                        runner.result() == null ? "-" : runner.result().status().name(),
                        runner.replans(), bot.blockPosition().toShortString());
                phase = 4;
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

    private boolean checkPlan(BlockPos start, BlockPos goal, String key) {
        teleport(start);
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request(start, goal));
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        boolean pass = plan.reached() && MovementType.BREAK_AND_ENTER.name().equals(first);
        BotLog.info("[BreakEnter] {}={} detail={}/first={}/movements={}",
                key, pass ? "PASS" : "FAIL", plan.status(), first, plan.movements().size());
        return pass;
    }

    private PathRequest request(BlockPos start, BlockPos goal) {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(), start, goal);
        return new PathRequest(base.botId(), start, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                "break-enter-task");
    }

    private void ensureStonePickaxe() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.STONE_PICKAXE)) {
                return;
            }
        }
        inventory.add(new ItemStack(Items.STONE_PICKAXE));
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean allPass = planA && planB && executeA;
        String summary = "plan_a=" + (planA ? "PASS" : "FAIL")
                + " plan_b=" + (planB ? "PASS" : "FAIL")
                + " execute_a=" + (executeA ? "PASS" : "FAIL");
        BotLog.info("[BreakEnter] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] BREAK_AND_ENTER 自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "BREAK_ENTER_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
