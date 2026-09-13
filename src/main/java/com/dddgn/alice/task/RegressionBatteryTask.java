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
 * {@code SUMMARY key=VALUE}"；把 25 个入口拆成让用户点 25 次是**反模式**。本电池把
 * {@code docs/TESTING_GUIDE.md §1.7} 的清单固化下来，一步一项、各自复位、互不干扰。
 *
 * <p>每项独立：进入该项前先跑它需要的场景函数（有的项目已自带复位，就留空）、发齐夹具工具
 * （D-119 起生产任务不发工具）、把 bot 放到该场景起点，然后 tick 到终态；**任一项失败不中断**
 * （一趟看全），最后一行汇总。
 *
 * <p>输出：`[Regression] SUMMARY clear_retry=PASS … pathing=PASS K4=OK(…) (23/23) ticks=… → PASS`。
 */
public final class RegressionBatteryTask implements Task {

    /** 总兜底预算（各项预算之和 + 余量）；典型实跑约 5000~7500 tick（4~6 分钟，含决策层事件窗口 300 tick）。 */
    private static final int TOTAL_BUDGET_TICKS = 20000;

    /** 与 `MineJobItem` 对齐的挖掘 Job 配额。 */
    private static final int MINE_QUOTA = 4;

    /**
     * @param doneWhen 非 null = **常驻任务**（如区域型 Job 会一直巡查等生长）：满足该谓词就判本步通过，
     *                 不必等它自己结束（否则只能靠预算超时，把"本来就该常驻"误报成失败）。
     *                 为 null = 按常规"跑完看终态"。
     */
    private record Step(String name, List<String> scenes, Runnable provision,
                        Supplier<Task> factory, int budgetTicks,
                        java.util.function.Predicate<Task> doneWhen) {
    }

    /** 常规步骤（跑完看终态）。 */
    private static Step step(String name, List<String> scenes, Runnable provision,
                             Supplier<Task> factory, int budgetTicks) {
        return new Step(name, scenes, provision, factory, budgetTicks, null);
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
    private final Map<String, Integer> k4Baseline;

    public RegressionBatteryTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
        // K-4 / D-167：记录"谓词不统一"计数的基线 ⇒ 收尾时按**本次电池的增量**断言
        // （进程累计会被电池之前的服务端活动污染，增量才是这次电池所有步骤的真实结果）。
        this.k4Baseline = com.dddgn.alice.pathing.core.search.PathingStats.totalsSnapshot();
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
        steps.add(step("clear_retry", List.of(), null,
                () -> new ClearRetryCheckTask(bot, scope), 900));
        steps.add(step("write_budget", List.of(), null,
                () -> new WriteBudgetCheckTask(bot, scope), 900));
        steps.add(step("scaffold", List.of(), null,
                () -> new ScaffoldLifecycleTask(bot, scope), 900));
        steps.add(step("clear_guard", List.of(), null,
                () -> new ClearGuardCheckTask(bot, scope), 900));
        steps.add(step("lumber_failure", List.of(), null,
                () -> new LumberFailureCheckTask(bot, scope), 1800));
        steps.add(step("mine_regression", List.of(), null,
                () -> new MineRegressionTask(bot, observer, scope), 3200));
        // 伐木 Job：手动场景（terrain + 手写树）⇒ 电池自己跑场景函数 + 复刻 LumberJobItem 的发料
        steps.add(step("lumber_job",
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
        steps.add(step("mine_job",
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
                    // Slice B：区域欠树要补种 ⇒ 夹具发**选定的那种**树苗（未选则默认橡树苗）
                    var state = com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer());
                    if (state.saplingItem(bot.getUUID()) == null) {
                        state.setSaplingItem(bot.getUUID(), "minecraft:oak_sapling");
                    }
                    var saplingId = net.minecraft.resources.ResourceLocation
                            .tryParse(state.saplingItem(bot.getUUID()));
                    var sapling = saplingId == null ? null
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(saplingId);
                    if (sapling != null && sapling != Items.AIR) {
                        FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(sapling),
                                stack -> stack.is(sapling), 8, "sapling");
                    }
                },
                () -> new com.dddgn.alice.job.lumber.RegionLumberJob(bot,
                        LumberCourseAnchor.region(),
                        scope, new LumberCandidateSource(), new NearestPolicy(), 20, 8000),
                2000,
                // 常驻任务：砍到 ≥1 棵且补种 ≥1 棵即算本步通过（之后它会继续巡查等苗长大）
                task -> task instanceof com.dddgn.alice.job.lumber.RegionLumberJob region
                        && region.treesChopped() >= 1 && region.plantedSomething()));
        // ==================== 决策层判据（基-2 / D-149）====================
        // 契约类断言：纯逻辑、不改世界、不调 LLM ⇒ 便宜且确定，任何改动都跑得到
        steps.add(step("decision_contract",
                List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                () -> teleportBot(LumberCourseAnchor.START_FOOT),
                () -> new DecisionContractCheckTask(bot, observer), 200));
        // 基-4：决策 trace 落盘 + 跨重启语义（NBT 往返 / 只报一次）
        steps.add(step("decision_trace", List.of(), null,
                () -> new DecisionTraceCheckTask(bot, observer), 200));
        // 基-5：LLM 上抛契约（Job 失败报告 / 产物判定口径 / 结构化拒绝回读）
        // 基-9：工具供给（换更好的 / 没得换如实报 / 不能凭空变出工具）
        // 基-8：能力闸门（MovementCapabilities 真的能拦人：保护区/资源/工具/预算/声明一致性）
        // 基-7：前缀搜索（K-1：预算耗尽交出前缀；真失败不给前缀）
        // R2：传输模块（4 个夹具：主流程/端点选择/选择器事件/命令解析）
        // K-3 安全点停止（D-169）**故意不进电池**：它的判据是"**顶层任务**被延后停止"，
        // 而 `stopTask` 停的是 bot 的顶层任务 —— 在电池里就是**电池自己**
        // （实测：`停止请求延后到安全点：task=RegressionBatteryTask` → 电池在第 12 步自杀、无 SUMMARY）。
        // ⇒ 走独立入口 `alice:k3_stop_check`（右键 DEFER / Shift+右键 FORCED），
        // 任务侧另有 `fixture_not_top_level` 前提断言兜底。
        steps.add(step("transfer", List.of(), null,
                () -> new TransferCheckTask(bot, observer), 400));
        steps.add(step("partial_search", List.of(), null,
                () -> new PartialSearchCheckTask(bot, observer), 200));
        steps.add(step("capability_gate", List.of(), null,
                () -> new CapabilityGateCheckTask(bot, observer), 200));
        steps.add(step("tool_supply", List.of(), null,
                () -> new ToolSupplyCheckTask(bot, observer), 400));
        steps.add(step("llm_contract", List.of(), null,
                () -> new LlmContractCheckTask(bot, observer), 200));
        steps.add(step("permission_gate", List.of(), null,
                () -> new PermissionContractCheckTask(bot, observer), 400));
        steps.add(step("pickup_gate",
                List.of("alice_test:lumber_course_terrain"),
                () -> teleportBot(LumberCourseAnchor.START_FOOT),
                () -> new PickupGateCheckTask(bot, observer), 600));
        steps.add(step("collect_job",
                List.of("alice_test:lumber_course_terrain"),
                () -> {
                    teleportBot(LumberCourseAnchor.START_FOOT);
                    // 夹具造掉落物并**登记为我方**（走安全默认那条路：我方 AUTO 放行）
                    for (int i = 0; i < 3; i++) {
                        var drop = new net.minecraft.world.entity.item.ItemEntity(bot.serverLevel(),
                                com.dddgn.alice.item.CollectJobItem.DROP_CENTER.getX() + 0.5D + i * 0.4D,
                                com.dddgn.alice.item.CollectJobItem.DROP_CENTER.getY() + 0.5D,
                                com.dddgn.alice.item.CollectJobItem.DROP_CENTER.getZ() + 0.5D,
                                new net.minecraft.world.item.ItemStack(
                                        net.minecraft.world.item.Items.COBBLESTONE, 8));
                        drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        bot.serverLevel().addFreshEntity(drop);
                    }
                    scope.begin(com.dddgn.alice.item.CollectJobItem.DROP_CENTER, 12, bot.getUUID());
                    scope.adoptExistingDrops(bot.serverLevel(), com.dddgn.alice.item.CollectJobItem.DROP_CENTER, 12);
                },
                // **走统一入口**（JobRequest → JobLauncher）：顺带覆盖 D-134 的"起任意 Job"路径
                () -> com.dddgn.alice.job.JobLauncher.create(bot, scope,
                        com.dddgn.alice.job.JobRequest.collect(com.dddgn.alice.item.CollectJobItem.DROP_CENTER, 16, 24, 600)),
                800));
        steps.add(step("recipes_dump", List.of(), null,
                () -> new RecipesDumpCheckTask(bot, observer), 200));
        // S4 事件层：工具见底 / 卡住 两类可行动病症，各验"报到"和"只报一次"（自带夹具前提断言）
        steps.add(step("event_thresholds", List.of(), null,
                () -> new EventThresholdCheckTask(bot, observer), 800));
        // 基-1：可回收性真的被评估（P0-B：不再是"两边写死 LOCAL_STEP、校验恒假"）
        steps.add(step("recoverability", List.of(), null,
                () -> new RecoverabilityCheckTask(bot, observer), 200));
        steps.add(step("pathing", List.of(), null,
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
        if (steps.get(index).doneWhen() != null && steps.get(index).doneWhen().test(current)) {
            record(steps.get(index).name(), "PASS",
                    "ticks=" + stepTicks + "（常驻任务按达成判过：chopped/planted 已达判据）");
            endStep();
            return Status.RUNNING;
        }
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
        // K-4 / D-167 自断言：本次电池里**纯通行类**的目标准入矛盾必须为 0。
        //   · goal_not_standable：规划期用离散格相等宣布 REACHED，但该格连"可站"都不成立，
        //     且最后一条边不是写入类 ⇒ 与 provider 自身的 canStandCentered 保证直接冲突；
        //   · final_segment_target_not_standable：最终段（EXACT）的目标格不可站且非写入类。
        // 写入类的两个信息码（goal_post_write_not_standable / final_segment_target_post_write）
        // 是**设计如此**（破坏类移动的落点只能证明"破坏之后可站"），不计入失败。
        int goalBad = k4Delta("goal_not_standable");
        int finalBad = k4Delta("final_segment_target_not_standable");
        int postWrite = k4Delta("goal_post_write_not_standable")
                + k4Delta("final_segment_target_post_write");
        boolean k4Ok = goalBad == 0 && finalBad == 0;
        if (!k4Ok) {
            BotLog.warn("[K4] VIOLATION 本次电池出现谓词矛盾：goal_not_standable={}"
                            + " final_segment_target_not_standable={}（可站谓词不统一的实测证据）",
                    goalBad, finalBad);
        }
        boolean allPass = pass == expected && results.size() == expected && k4Ok;
        StringBuilder line = new StringBuilder();
        for (Step step : steps) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(step.name()).append('=')
                    .append(results.getOrDefault(step.name(), "SKIPPED"));
        }
        line.append(" K4=").append(k4Ok
                ? "OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=" + postWrite + ")"
                : "VIOLATION(goal_not_standable=" + goalBad
                        + " final_segment_not_standable=" + finalBad + ")");
        BotLog.info("[Regression] SUMMARY {} ({}/{}) ticks={} → {}",
                line, pass, expected, ticks, allPass ? "PASS" : "FAIL");
        return allPass ? Status.DONE : Status.FAILED;
    }

    /** 本次电池期间的累计计数增量。 */
    private int k4Delta(String code) {
        Map<String, Integer> now = com.dddgn.alice.pathing.core.search.PathingStats.totalsSnapshot();
        return now.getOrDefault(code, 0) - k4Baseline.getOrDefault(code, 0);
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
