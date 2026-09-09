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
 * FALL（落差 2~3 格）诊断任务：一次右键验证规划、两条守卫与执行（D-058）。
 *
 * <ol>
 *   <li>2 格落差 `(22,64,66) → (23,62,66)`：期望 `REACHED` 且首步 = FALL；</li>
 *   <li>3 格落差 `(22,64,68) → (23,61,68)`：期望 `REACHED` 且首步 = FALL；</li>
 *   <li>4 格落差 `(22,64,64) → (23,60,64)`：期望**不** REACHED（Baritone `maxFallHeightNoWater=3`）；</li>
 *   <li>落点上方封顶 `(22,64,70) → (23,62,70)`：期望**不** REACHED（PILLAR 返回列被挡 → 不可回收）；</li>
 *   <li>实跑 3 格落差：期望 `COMPLETED` 且稳定在 `(23,61,68)`。</li>
 * </ol>
 */
public final class FallDiagnosticTask implements Task {
    public static final BlockPos DROP2_START = new BlockPos(22, 64, 66);
    public static final BlockPos DROP2_GOAL = new BlockPos(23, 62, 66);
    public static final BlockPos DROP3_START = new BlockPos(22, 64, 68);
    public static final BlockPos DROP3_GOAL = new BlockPos(23, 61, 68);
    public static final BlockPos DROP4_START = new BlockPos(22, 64, 64);
    public static final BlockPos DROP4_GOAL = new BlockPos(23, 60, 64);
    public static final BlockPos GUARD_START = new BlockPos(22, 64, 70);
    public static final BlockPos GUARD_GOAL = new BlockPos(23, 62, 70);

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private int phase;
    private int ticks;
    private PathRetryRunner runner;
    private String failure = "";
    private boolean plan2Pass;
    private boolean plan3Pass;
    private boolean drop4GuardPass;
    private boolean recoverGuardPass;
    private boolean executePass;

    public FallDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(DROP3_GOAL);
    }

    @Override
    public Status tick() {
        if (++ticks > 1200) {
            failure = "FALL_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> {
                ensureCobblestone(8);
                phase = 1;
                return Status.RUNNING;
            }
            case 1 -> {
                plan2Pass = checkFallPlan(DROP2_START, DROP2_GOAL, "fall_plan_2");
                phase = 2;
                return Status.RUNNING;
            }
            case 2 -> {
                plan3Pass = checkFallPlan(DROP3_START, DROP3_GOAL, "fall_plan_3");
                phase = 3;
                return Status.RUNNING;
            }
            case 3 -> {
                drop4GuardPass = checkRefused(DROP4_START, DROP4_GOAL, "drop4_guard");
                phase = 4;
                return Status.RUNNING;
            }
            case 4 -> {
                recoverGuardPass = checkRefused(GUARD_START, GUARD_GOAL, "recover_guard");
                phase = 5;
                return Status.RUNNING;
            }
            case 5 -> {
                teleport(DROP3_START);
                runner = new PathRetryRunner(bot, request(DROP3_START, DROP3_GOAL),
                        PathRetryRunner.DEFAULT_MAX_REPLANS, "fall-execute");
                phase = 6;
                return Status.RUNNING;
            }
            case 6 -> {
                PathRetryRunner.State state = runner.tick();
                if (state == PathRetryRunner.State.RUNNING) {
                    return Status.RUNNING;
                }
                executePass = state == PathRetryRunner.State.DONE
                        && bot.blockPosition().equals(DROP3_GOAL);
                BotLog.info("[Fall] execute={} detail={} replans={} foot={}",
                        executePass ? "PASS" : "FAIL",
                        runner.result() == null ? "-" : runner.result().status().name(),
                        runner.replans(), bot.blockPosition().toShortString());
                phase = 7;
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

    private boolean checkFallPlan(BlockPos start, BlockPos goal, String key) {
        teleport(start);
        PathPlan plan = plan(start, goal);
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        boolean pass = plan.reached() && MovementType.FALL.name().equals(first);
        BotLog.info("[Fall] {}={} detail={}/first={}/movements={}",
                key, pass ? "PASS" : "FAIL", plan.status(), first, plan.movements().size());
        return pass;
    }

    private boolean checkRefused(BlockPos start, BlockPos goal, String key) {
        teleport(start);
        PathPlan plan = plan(start, goal);
        boolean pass = !plan.reached();
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        BotLog.info("[Fall] {}={} detail={}/first={}/movements={}",
                key, pass ? "PASS" : "FAIL", plan.status(), first, plan.movements().size());
        return pass;
    }

    private PathPlan plan(BlockPos start, BlockPos goal) {
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request(start, goal));
    }

    private PathRequest request(BlockPos start, BlockPos goal) {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(), start, goal);
        return new PathRequest(base.botId(), start, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                "fall-task");
    }

    /** 夹具：确保快捷栏里有圆石（FALL 的 PILLAR 返回守卫需要一次性方块）。 */
    private void ensureCobblestone(int count) {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
                have += inventory.getItem(slot).getCount();
            }
        }
        if (have >= count) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, count - have));
                return;
            }
        }
        inventory.add(new ItemStack(Items.COBBLESTONE, count - have));
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean allPass = plan2Pass && plan3Pass && drop4GuardPass && recoverGuardPass && executePass;
        String summary = "fall_plan_2=" + (plan2Pass ? "PASS" : "FAIL")
                + " fall_plan_3=" + (plan3Pass ? "PASS" : "FAIL")
                + " drop4_guard=" + (drop4GuardPass ? "PASS" : "FAIL")
                + " recover_guard=" + (recoverGuardPass ? "PASS" : "FAIL")
                + " fall_execute=" + (executePass ? "PASS" : "FAIL");
        BotLog.info("[Fall] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] FALL 自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "FALL_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
