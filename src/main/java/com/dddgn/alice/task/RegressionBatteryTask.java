package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * **串联回归电池**（{@code alice:regression_battery}，D-122）：一次右键跑完"改了生产任务必须复跑"的常用回归。
 *
 * <p>为什么要有它（项目测试规矩）："多个检查项合并为一个自检任务，一次右键跑完，输出
 * {@code SUMMARY key=VALUE}"；把 9 个入口拆成让用户点 9 次是**反模式**。本电池把
 * {@code docs/TESTING_GUIDE.md §1.7} 的清单固化下来，一步一项、各自复位、互不干扰。
 *
 * <p>每项独立：进入该项前先跑它需要的场景函数（有的项目已自带复位，就留空）、发齐夹具工具
 * （D-119 起生产任务不发工具）、把 bot 放到该场景起点，然后 tick 到终态；**任一项失败不中断**
 * （一趟看全），最后一行汇总。
 *
 * <p>输出：`[Regression] SUMMARY clear_retry=PASS … pathing=PASS (9/9) ticks=… → PASS`。
 */
public final class RegressionBatteryTask implements Task {

    /** 总兜底预算（各项预算之和 + 余量）；典型实跑约 4000~6000 tick（3~5 分钟）。 */
    private static final int TOTAL_BUDGET_TICKS = 20000;

    /** 与 `MineJobItem` 对齐的挖掘 Job 配额。 */
    private static final int MINE_QUOTA = 4;

    private record Step(String name, List<String> scenes, Runnable provision,
                        Supplier<Task> factory, int budgetTicks) {
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;
    private final List<Step> steps = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();

    private int index;
    private int ticks;
    private int stepTicks;
    private Task current;
    private boolean stepStarted;

    public RegressionBatteryTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
        buildSteps();
    }

    @Override
    public String taskName() {
        return "RegressionBattery";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return results.entrySet().stream()
                .filter(e -> !"PASS".equals(e.getValue()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    // ==================== 清单 ====================

    private void buildSteps() {
        // 由轻到重：先跑秒级自检，再跑 Job / 长回归，便于"早失败早知道"
        steps.add(new Step("clear_retry", List.of(), null,
                () -> new ClearRetryCheckTask(bot, scope), 900));
        steps.add(new Step("write_budget", List.of(), null,
                () -> new WriteBudgetCheckTask(bot, scope), 900));
        steps.add(new Step("scaffold", List.of(), null,
                () -> new ScaffoldLifecycleTask(bot, scope), 900));
        steps.add(new Step("clear_guard", List.of(), null,
                () -> new ClearGuardCheckTask(bot, scope), 900));
        steps.add(new Step("lumber_failure", List.of(), null,
                () -> new LumberFailureCheckTask(bot, scope), 1800));
        steps.add(new Step("mine_regression", List.of(), null,
                () -> new MineRegressionTask(bot, observer, scope), 3200));
        // 伐木 Job：手动场景（terrain + 手写树）⇒ 电池自己跑场景函数 + 复刻 LumberJobItem 的发料
        steps.add(new Step("lumber_job",
                List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                () -> {
                    teleportBot(LumberCourseAnchor.START_FOOT);
                    FixtureToolKit.resetInventory(bot);
                    FixtureToolKit.ensureAxe(bot);
                    FixtureToolKit.ensurePickaxe(bot);
                    FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                            stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
                },
                () -> new LumberJob(bot,
                        GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 4, 3600),
                        scope, new LumberCandidateSource(), new NearestPolicy()),
                1500));
        // 挖掘 Job：同上（ore_course + 复刻 MineJobItem 的发料）
        steps.add(new Step("mine_job",
                List.of("alice_test:ore_course_terrain"),
                () -> {
                    teleportBot(OreCourseAnchor.START_FOOT);
                    FixtureToolKit.resetInventory(bot);
                    FixtureToolKit.ensurePickaxe(bot);
                },
                () -> new MineJob(bot,
                        GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                                MineCandidateSource.SCAN_RADIUS, MINE_QUOTA, 3600),
                        scope,
                        new MineCandidateSource(MineCandidateSource.Target.ofBlock(
                                net.minecraft.world.level.block.Blocks.IRON_ORE),
                                MineCandidateSource.SCAN_RADIUS),
                        new NearestPolicy()),
                2200));
        // J8 可持续伐木区（MAINTAIN）：同一个伐木场景，但走"巡查 → 砍 → 继续巡查"的区域型 Job
        steps.add(new Step("region_maintain",
                List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                () -> {
                    teleportBot(LumberCourseAnchor.START_FOOT);
                    FixtureToolKit.resetInventory(bot);
                    FixtureToolKit.ensureAxe(bot);
                    FixtureToolKit.ensurePickaxe(bot);
                    FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                            stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
                },
                () -> new com.dddgn.alice.job.lumber.RegionLumberJob(bot,
                        new com.dddgn.alice.job.lumber.LumberRegionState.Region(
                                LumberCourseAnchor.REGION_MIN, LumberCourseAnchor.REGION_MAX),
                        scope, new LumberCandidateSource(), new NearestPolicy(), 20, 8000),
                2000));
        steps.add(new Step("pathing", List.of(), null,
                () -> new PathingRegressionTask(bot, observer), 5000));
    }

    // ==================== 执行 ====================

    @Override
    public Status tick() {
        if (++ticks > TOTAL_BUDGET_TICKS) {
            BotLog.warn("[Regression] 总预算用尽 step={} ticks={}", currentStepName(), ticks);
            return finish();
        }
        if (current == null) {
            if (index >= steps.size()) {
                return finish();
            }
            return startStep(steps.get(index));
        }
        Status status = current.tick();
        if (status == Status.RUNNING) {
            if (++stepTicks > steps.get(index).budgetTicks()) {
                record(steps.get(index).name(), "TIMEOUT",
                        "ticks=" + stepTicks + "（单项预算用尽）");
                endStep();
            }
            return Status.RUNNING;
        }
        record(steps.get(index).name(), status == Status.DONE ? "PASS" : "FAIL",
                "ticks=" + stepTicks
                        + (status == Status.DONE ? "" : " reason=" + safe(current.failureReason())));
        endStep();
        return Status.RUNNING;
    }

    private Status startStep(Step step) {
        // **每步一个独立作用域**（镜像 BotSession.beginTask 的那一半）：WriteBudget 的
        // 破坏/放置上限与账本 TEMP 都是"一次任务一个作用域" ⇒ 九项共用一个作用域会串味
        // （后面的项会撞上前面的 64/32 上限、恢复阶段也会互相看见对方的临时方块）。
        com.dddgn.alice.ledger.WorldModLedger.openScope(
                bot.getServer(), bot.getUUID(), "Regression:" + step.name());
        BotLog.info("[Regression] step={} ({}/{}) scenes={} budget={}",
                step.name(), index + 1, steps.size(), step.scenes(), step.budgetTicks());
        bot.controller().stopMovement();
        if (!step.scenes().isEmpty()) {
            var server = bot.serverLevel().getServer();
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (String fn : step.scenes()) {
                server.getCommands().performPrefixedCommand(source, "function " + fn);
            }
        }
        if (step.provision() != null) {
            step.provision().run();
        }
        stepTicks = 0;
        stepStarted = true;
        current = step.factory().get();
        return Status.RUNNING;
    }

    private void endStep() {
        // 镜像 BotSession.clearTask 的作用域收尾：关账本作用域 → 关写入预算（打一行摘要）
        // → 销掉现场已非我方方块的条目 → 关作用域缓冲（下一步的子任务会自己 begin）
        String closed = com.dddgn.alice.ledger.WorldModLedger.closeScope(
                bot.getServer(), bot.getUUID());
        com.dddgn.alice.action.WriteBudget.closeScope(closed);
        com.dddgn.alice.ledger.WorldModLedger.dropStale(bot.serverLevel());
        var pending = com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(
                bot.getServer(), closed);
        scope.end();
        if (!pending.isEmpty()) {
            BotLog.warn("[Regression] step={} 收尾仍有 {} 条我方临时放置未拆（建拆同权未闭合）",
                    currentStepName(), pending.size());
        }
        current = null;
        stepStarted = false;
        index++;
    }

    private void record(String name, String value, String detail) {
        results.put(name, value);
        details.put(name, detail);
        BotLog.info("[Regression] {}={} {}", name, value, detail);
    }

    private Status finish() {
        long pass = results.values().stream().filter("PASS"::equals).count();
        int expected = steps.size();
        boolean allPass = pass == expected && results.size() == expected;
        StringBuilder line = new StringBuilder();
        for (Step step : steps) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(step.name()).append('=')
                    .append(results.getOrDefault(step.name(), "SKIPPED"));
        }
        BotLog.info("[Regression] SUMMARY {} ({}/{}) ticks={} → {}",
                line, pass, expected, ticks, allPass ? "PASS" : "FAIL");
        return allPass ? Status.DONE : Status.FAILED;
    }

    private String currentStepName() {
        return index < steps.size() ? steps.get(index).name() : "-";
    }

    private static String safe(String reason) {
        return reason == null || reason.isBlank() ? "-" : reason;
    }

    private void teleportBot(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
