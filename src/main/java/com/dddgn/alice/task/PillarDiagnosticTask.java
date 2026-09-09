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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * PILLAR（垂直上升 1 格）诊断任务：一次右键验证三条路径。
 *
 * <ol>
 *   <li><b>规划</b>：3 格深 1×1 竖井（基岩壁）内 `(24,64,44)` → 井口边缘 `(25,67,44)`，
 *       期望 `REACHED` 且首步 = PILLAR、PILLAR 数量 ≥ 2（唯一出路必须含"跳跃中在脚下放方块"）；</li>
 *   <li><b>资源守卫</b>：清空快捷栏方块后再规划，期望**不** REACHED
 *       （对照 Baritone `costOfPlacingAt` 无一次性方块 → COST_INF）；</li>
 *   <li><b>执行</b>：恢复方块后实跑，期望 `COMPLETED` 并稳定在井口边缘。</li>
 * </ol>
 */
public final class PillarDiagnosticTask implements Task {
    /** 竖井内部脚位（3 格深：64/65/66 空气，壁 y=64..66，井口顶面 y=67）。 */
    public static final BlockPos SHAFT_START = new BlockPos(24, 64, 44);
    /** 井口边缘脚位（站在壁顶，比井底高 3 格）。 */
    public static final BlockPos RIM_GOAL = new BlockPos(25, 67, 44);
    /**
     * 竖井逃生的 PILLAR 下限：3 格深 1×1 竖井至少需要 2 次 PILLAR
     * （规划器实测选 2×PILLAR + 1×ASCEND，cost 11.68，比 3×PILLAR+TRAVERSE 的 16 便宜）。
     */
    private static final int MIN_PILLARS = 2;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ItemStack[] savedHotbar = new ItemStack[9];

    private int phase;
    private int ticks;
    private PathRetryRunner runner;
    private String failure = "";
    private boolean planPass;
    private boolean guardPass;
    private boolean executePass;
    private String planDetail = "-";

    public PillarDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(RIM_GOAL);
    }

    @Override
    public Status tick() {
        if (++ticks > 1200) {
            failure = "PILLAR_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> {
                teleport(SHAFT_START);
                ensureCobblestone(8);
                phase = 1;
                return Status.RUNNING;
            }
            case 1 -> {
                PathPlan plan = plan();
                long pillars = plan.movements().stream()
                        .filter(m -> m.movementType() == MovementType.PILLAR).count();
                String first = plan.movements().isEmpty()
                        ? "-" : plan.movements().get(0).movementType().name();
                planPass = plan.reached() && pillars >= MIN_PILLARS
                        && MovementType.PILLAR.name().equals(first);
                planDetail = plan.status() + "/first=" + first + "/pillars=" + pillars;
                BotLog.info("[Pillar] plan_check={} detail={}", planPass ? "PASS" : "FAIL", planDetail);
                phase = 2;
                return Status.RUNNING;
            }
            case 2 -> {
                int cleared = clearHotbarBlocks();
                PathPlan plan = plan();
                guardPass = cleared > 0 && !plan.reached();
                BotLog.info("[Pillar] resource_guard={} cleared={} status={}",
                        guardPass ? "PASS" : "FAIL", cleared, plan.status());
                restoreHotbar();
                phase = 3;
                return Status.RUNNING;
            }
            case 3 -> {
                teleport(SHAFT_START);
                ensureCobblestone(8);
                runner = new PathRetryRunner(bot, request(), PathRetryRunner.DEFAULT_MAX_REPLANS,
                        "pillar-execute");
                phase = 4;
                return Status.RUNNING;
            }
            case 4 -> {
                PathRetryRunner.State state = runner.tick();
                if (state == PathRetryRunner.State.RUNNING) {
                    return Status.RUNNING;
                }
                executePass = state == PathRetryRunner.State.DONE
                        && bot.blockPosition().equals(RIM_GOAL);
                BotLog.info("[Pillar] execute={} detail={} replans={} foot={}",
                        executePass ? "PASS" : "FAIL",
                        runner.result() == null ? "-" : runner.result().status().name(),
                        runner.replans(), bot.blockPosition().toShortString());
                phase = 5;
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

    private PathRequest request() {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(),
                SHAFT_START, RIM_GOAL);
        return new PathRequest(base.botId(), SHAFT_START, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                "pillar-task");
    }

    private PathPlan plan() {
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request());
    }

    /** 夹具：确保快捷栏里有圆石（放置一次性方块用）。 */
    private void ensureCobblestone(int count) {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.COBBLESTONE)) {
                have += stack.getCount();
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

    /** 临时清空快捷栏的方块物品，返回清空的槽位数（用于资源守卫检查）。 */
    private int clearHotbarBlocks() {
        var inventory = bot.getInventory();
        int cleared = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            savedHotbar[slot] = stack.copy();
            inventory.setItem(slot, ItemStack.EMPTY);
            cleared++;
        }
        return cleared;
    }

    private void restoreHotbar() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (savedHotbar[slot] != null) {
                inventory.setItem(slot, savedHotbar[slot]);
                savedHotbar[slot] = null;
            }
        }
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean allPass = planPass && guardPass && executePass;
        String summary = "pillar_plan=" + (planPass ? "PASS" : "FAIL")
                + " resource_guard=" + (guardPass ? "PASS" : "FAIL")
                + " pillar_execute=" + (executePass ? "PASS" : "FAIL")
                + " detail=" + planDetail;
        BotLog.info("[Pillar] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] PILLAR 自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "PILLAR_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
