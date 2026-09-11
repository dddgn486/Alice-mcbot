package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 执行期写入预算自检（D-106）：**预算用满后绝不再改世界，并如实失败**。
 *
 * <p>场景复用 `break_course`（x=3 两格高石墙挡住 (0,64,66) → (7,64,66)），
 * 但把本次任务的破坏上限压到 **1 格**（夹具专用 {@link WriteBudget#setCaps}，不接玩家命令）。
 *
 * <p>断言（不变式，与"计划先破坏还是先放置"无关）：
 * <ol>
 *   <li>破坏次数 ≤ 上限、放置次数 = 0（上限 0）——**没有任何一类写入越界**；</li>
 *   <li>世界事实与计数一致：墙区（x=3, y=64..65, z=62..70）变空气的格子数 ≤ 上限；</li>
 *   <li>预算确实被触发过（`exhausted`）；</li>
 *   <li>bot 没有穿过墙、且这次通行没有被判成功 —— "没路了就如实失败"，不是"想办法继续拆"。</li>
 * </ol>
 *
 * <p>输出：`[WriteBudget] CHECK breaks=?/1 exhausted=? wall_broken=? passed_wall=? status=? → PASS|FAIL`。
 */
public final class WriteBudgetCheckTask implements Task {

    /** 与 `break_course_terrain` 对齐：起点 / 目标 / 墙位置。 */
    public static final BlockPos START_FOOT = new BlockPos(0, 64, 66);
    private static final BlockPos GOAL_FOOT = new BlockPos(7, 64, 66);
    private static final int WALL_X = 3;
    private static final int WALL_Y_MIN = 64;
    private static final int WALL_Y_MAX = 65;
    private static final int WALL_Z_MIN = 62;
    private static final int WALL_Z_MAX = 70;
    /** 夹具把预算压到 1 格（正常任务默认 64）。 */
    private static final int CAP_BREAKS = 1;
    /** 单次自检的 tick 上限。 */
    private static final int RUN_BUDGET_TICKS = 600;

    private enum Phase { SETUP, RUN, ASSERT, DONE }

    private final BotPlayer bot;
    private final com.dddgn.alice.perception.ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private com.dddgn.alice.task.PathRetryRunner runner;
    private int ticks;
    private String failure = "";
    private String resultStatus = "-";

    public WriteBudgetCheckTask(BotPlayer bot, com.dddgn.alice.perception.ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "WriteBudgetCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(GOAL_FOOT);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        return switch (phase) {
            case SETUP -> setup();
            case RUN -> run();
            case ASSERT -> assertResult();
            case DONE -> Task.Status.DONE;
        };
    }

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "function alice_test:break_course_terrain");
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        scope.begin(START_FOOT, 24, bot.getUUID());
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "pickaxe");
        // 夹具职责：把本次任务的破坏上限压到 1 格（不暴露为玩家命令）
        WriteBudget.setCaps(WriteBudget.scopeOf(bot), new WriteBudget.Caps(CAP_BREAKS, 0));
        BotLog.info("[WriteBudget] CHECK setup start={} goal={} caps={} {}",
                START_FOOT.toShortString(), GOAL_FOOT.toShortString(), CAP_BREAKS,
                WriteBudget.describe(bot));
        runner = new com.dddgn.alice.task.PathRetryRunner(bot,
                PathRequest.withWorldModification(bot.getUUID().toString(), START_FOOT, GOAL_FOOT,
                        "write-budget-check"),
                com.dddgn.alice.task.PathRetryRunner.DEFAULT_MAX_REPLANS, "writebudget");
        phase = Phase.RUN;
        return Task.Status.RUNNING;
    }

    private Task.Status run() {
        if (++ticks > RUN_BUDGET_TICKS) {
            resultStatus = "TASK_TIMEOUT";
            BotLog.warn("[WriteBudget] CHECK 运行超时（{} tick）→ 断言", ticks);
            runner = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        var state = runner.tick();
        if (state == com.dddgn.alice.task.PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        var result = runner.result();
        resultStatus = result == null ? state.name() : result.status().name();
        BotLog.info("[WriteBudget] CHECK run_end state={} status={} replans={} feet={}",
                state, resultStatus, runner.replans(),
                com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot).toShortString());
        runner = null;
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        int breaks = WriteBudget.breaks(bot);
        int places = WriteBudget.places(bot);
        boolean triggered = WriteBudget.breakExhausted(bot) || WriteBudget.placeExhausted(bot);
        // 世界事实复核：墙区里变成空气的格子数（只信世界，不只信计数器）
        int wallBroken = 0;
        for (int y = WALL_Y_MIN; y <= WALL_Y_MAX; y++) {
            for (int z = WALL_Z_MIN; z <= WALL_Z_MAX; z++) {
                if (level.getBlockState(new BlockPos(WALL_X, y, z)).isAir()) {
                    wallBroken++;
                }
            }
        }
        boolean passedWall = com.dddgn.alice.pathing.MovementHelper
                .footCell(level, bot).getX() >= WALL_X + 1;
        // 判据（不变式，不依赖"计划先破坏还是先放置"）：
        // ① 没有任何一类写入越过上限；② 世界事实与计数一致（墙区空气格 ≤ 上限）；
        // ③ 预算确实被触发过；④ 没穿过墙 + 没被当成成功
        boolean noOverrun = breaks <= CAP_BREAKS && places <= 0 && wallBroken <= CAP_BREAKS;
        boolean stopped = !passedWall && !"COMPLETED".equals(resultStatus);
        boolean pass = noOverrun && stopped && triggered;
        BotLog.info("[WriteBudget] CHECK breaks={}/{} places={}/0 exhausted={} wall_broken={}"
                        + " passed_wall={} status={} → {}",
                breaks, CAP_BREAKS, places, triggered, wallBroken, passedWall, resultStatus,
                pass ? "PASS" : "FAIL");
        if (!pass) {
            failure = "WRITE_BUDGET_CHECK_FAILED breaks=" + breaks + " places=" + places
                    + " triggered=" + triggered + " wallBroken=" + wallBroken
                    + " passedWall=" + passedWall + " status=" + resultStatus;
            phase = Phase.DONE;
            return Task.Status.FAILED;
        }
        phase = Phase.DONE;
        return Task.Status.DONE;
    }
}
