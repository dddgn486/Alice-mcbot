package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 挖掘站位选优（两模式）诊断任务（D-070）：一次右键跑完五类场景的规划断言。
 *
 * <ol>
 *   <li>`free`：露天目标 → 模式 A（DIRECT/CURRENT）；</li>
 *   <li>`wall`：贴墙目标（仅西面可达）→ 模式 A；</li>
 *   <li>`blocked`：四面 + 上方全包围 → 模式 B（TUNNEL，破坏进入）；</li>
 *   <li>`headroom`：目标在头位 → 模式 A 且站位 y = 目标 y − 1；</li>
 *   <li>`buried`：孤立被包围簇（面格无支撑）→ `found_but_unminable` 或 TUNNEL。</li>
 * </ol>
 */
public final class MineCourseDiagnosticTask implements Task {
    private static final BlockPos FREE_TARGET = new BlockPos(23, 64, 140);
    private static final BlockPos WALL_TARGET = new BlockPos(23, 64, 137);
    private static final BlockPos BLOCKED_TARGET = new BlockPos(23, 64, 134);
    private static final BlockPos HEADROOM_TARGET = new BlockPos(23, 65, 131);
    private static final BlockPos BURIED_TARGET = new BlockPos(23, 64, 128);

    /** 自检起点（物品入口用）。 */
    public static final BlockPos START_FOOT = new BlockPos(21, 64, 140);
    private static final BlockPos START = START_FOOT;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private int phase;
    private int ticks;
    private String failure = "";
    private boolean freePass;
    private boolean wallPass;
    private boolean blockedPass;
    private boolean headroomPass;
    private boolean buriedPass;

    public MineCourseDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(FREE_TARGET);
    }

    @Override
    public Status tick() {
        if (++ticks > 600) {
            failure = "MINE_COURSE_TIMEOUT";
            return finish();
        }
        ensureStonePickaxe();
        switch (phase) {
            case 0 -> {
                freePass = checkMode("free", FREE_TARGET, MiningPlan.Mode.DIRECT, MiningPlan.Mode.CURRENT);
                phase = 1;
            }
            case 1 -> {
                wallPass = checkMode("wall", WALL_TARGET, MiningPlan.Mode.DIRECT, MiningPlan.Mode.CURRENT);
                phase = 2;
            }
            case 2 -> {
                blockedPass = checkMode("blocked", BLOCKED_TARGET, MiningPlan.Mode.TUNNEL);
                phase = 3;
            }
            case 3 -> {
                headroomPass = checkHeadroom();
                phase = 4;
            }
            case 4 -> {
                buriedPass = checkBuried();
                phase = 5;
            }
            default -> {
                return finish();
            }
        }
        return Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private boolean checkMode(String key, BlockPos target, MiningPlan.Mode... expected) {
        teleport(START);
        MiningPlanner.Result result = plan(target);
        MiningPlan.Mode mode = result.plan() == null ? null : result.plan().mode();
        boolean pass = mode != null;
        if (pass) {
            pass = false;
            for (MiningPlan.Mode candidate : expected) {
                if (candidate == mode) {
                    pass = true;
                    break;
                }
            }
        }
        BotLog.info("[MineCourse] {}={} mode={} stand={} cost={} reason={}",
                key, pass ? "PASS" : "FAIL", mode,
                result.plan() == null ? "-" : result.plan().standingFoot().toShortString(),
                result.score() == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f", result.score().getScore()),
                result.failureReason());
        return pass;
    }

    private boolean checkHeadroom() {
        teleport(START);
        MiningPlanner.Result result = plan(HEADROOM_TARGET);
        boolean pass = result.plan() != null
                && result.plan().standingFoot().getY() == HEADROOM_TARGET.getY() - 1;
        BotLog.info("[MineCourse] headroom={} mode={} stand={} cost={} reason={}",
                pass ? "PASS" : "FAIL", result.plan() == null ? "-" : result.plan().mode(),
                result.plan() == null ? "-" : result.plan().standingFoot().toShortString(),
                result.score() == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f", result.score().getScore()),
                result.failureReason());
        return pass;
    }

    private boolean checkBuried() {
        teleport(START);
        MiningPlanner.Result result = plan(BURIED_TARGET);
        boolean pass = (result.plan() != null && result.plan().mode() == MiningPlan.Mode.TUNNEL)
                || "found_but_unminable".equals(result.failureReason());
        BotLog.info("[MineCourse] buried={} mode={} reason={}",
                pass ? "PASS" : "FAIL",
                result.plan() == null ? "-" : result.plan().mode(), result.failureReason());
        return pass;
    }

    private MiningPlanner.Result plan(BlockPos target) {
        MiningBudget budget = MiningBudget.forTarget(bot, bot.serverLevel(), target, true);
        return new MiningPlanner().plan(bot, target, budget);
    }

    private void ensureStonePickaxe() {
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new ItemStack(Items.STONE_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "stone_pickaxe");
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean allPass = freePass && wallPass && blockedPass && headroomPass && buriedPass;
        String summary = "free=" + (freePass ? "PASS" : "FAIL")
                + " wall=" + (wallPass ? "PASS" : "FAIL")
                + " blocked=" + (blockedPass ? "PASS" : "FAIL")
                + " headroom=" + (headroomPass ? "PASS" : "FAIL")
                + " buried=" + (buriedPass ? "PASS" : "FAIL");
        BotLog.info("[MineCourse] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] 挖掘站位自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "MINE_COURSE_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
