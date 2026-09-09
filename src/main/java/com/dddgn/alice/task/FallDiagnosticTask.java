package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
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
 *   <li>4 格落差 `(22,64,64) → (23,60,64)`：断言**路线属性**——不生成 4 格 FALL 候选，且路线里没有单段 ≥4 格的下落
 *       （允许规划器用 PLACE_STEP 搭楼梯绕下去，这本身是安全的）；</li>
 *   <li>落点上方封顶 `(22,64,70)`：断言**守卫是选择性的**——该落点不生成 FALL 候选（PILLAR 返回列被挡），
 *       而同场景可回收落点仍能生成 FALL 候选；</li>
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
                drop4GuardPass = checkNoDeepFall();
                phase = 4;
                return Status.RUNNING;
            }
            case 4 -> {
                recoverGuardPass = checkRecoverGuard();
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

    /**
     * 4 格落差断言（路线属性，D-058 修订）：不生成 4 格 FALL 候选，且路线中不出现单段 ≥4 格的下落。
     * <p>规划器允许用 PLACE_STEP 搭楼梯到达——那是安全行为，不应判失败。
     */
    private boolean checkNoDeepFall() {
        boolean noCandidate = !hasFallCandidate(DROP4_START, DROP4_GOAL);
        teleport(DROP4_START);
        PathPlan plan = plan(DROP4_START, DROP4_GOAL);
        boolean noDeepMove = plan.movements().stream()
                .allMatch(m -> m.toFoot().getY() - m.fromFoot().getY() >= -3);
        boolean pass = noCandidate && noDeepMove;
        BotLog.info("[Fall] no_deep_fall={} fall_candidate={} route={}/first={}/movements={}",
                pass ? "PASS" : "FAIL", !noCandidate, plan.status(),
                plan.movements().isEmpty() ? "-" : plan.movements().get(0).movementType().name(),
                plan.movements().size());
        return pass;
    }

    /**
     * 落点可回收守卫断言（选择性，D-058 修订）：不可回收落点不得生成 FALL 候选，
     * 同场景可回收落点必须仍能生成 FALL 候选（证明守卫不是"一律禁用"）。
     */
    private boolean checkRecoverGuard() {
        boolean blocked = !hasFallCandidate(GUARD_START, GUARD_GOAL);
        boolean allowed = hasFallCandidate(DROP2_START, DROP2_GOAL);
        boolean pass = blocked && allowed;
        BotLog.info("[Fall] fall_recover_guard={} blocked_unrecoverable={} allowed_recoverable={}",
                pass ? "PASS" : "FAIL", blocked, allowed);
        return pass;
    }

    /** 规划器是否为 (from → to) 生成 FALL 候选（直接查询 provider，不经过搜索）。 */
    private boolean hasFallCandidate(BlockPos from, BlockPos to) {
        MovementContext context = MovementContext.live(bot, bot.serverLevel(), request(from, to));
        java.util.List<PlannedMovement> candidates = new java.util.ArrayList<>();
        new SurfaceMovementProvider().appendCandidates(context, from, candidates);
        return candidates.stream().anyMatch(m -> m.movementType() == MovementType.FALL
                && m.fromFoot().equals(from) && m.toFoot().equals(to));
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
                + " no_deep_fall=" + (drop4GuardPass ? "PASS" : "FAIL")
                + " fall_recover_guard=" + (recoverGuardPass ? "PASS" : "FAIL")
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
