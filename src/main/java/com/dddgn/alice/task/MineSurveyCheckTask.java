package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.ManualTestLock;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.job.mine.MineSurvey;
import com.dddgn.alice.job.mine.MineSurveyStats;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * **真机实测测试工具的自检**（`D-360`，EXTRA）：就地开采 + **阻断 LLM 接手** + 统计口径，三件事一起验。
 *
 * <p>为什么必须有这一步（而不是"手动试一下就行"）：这三件事**都会静默失败**——
 * ① 锁没生效 ⇒ LLM 插进来起了任务，人还以为数据干净；
 * ② 统计没打点 ⇒ 跑完了一行 `SUMMARY` 都没有（四条终态路径漏一条就够了）；
 * ③ 口径算错 ⇒ 数字看着有，含义是错的。
 * ⇒ 判据必须是"量出来的"：**锁上时生产入口返回 false**、**终态钩子真的跑过**（锁被自动放掉）、
 * **快照字段落在合法区间**。
 *
 * <p>⚠️ 本步**只跑 EXTRA**（不进 CORE）：它要起一个真作业并等它到终态，属于"整链"验证。
 */
public final class MineSurveyCheckTask implements Task {

    private static final int BUDGET_TICKS = 1200;
    private static final int SURVEY_QUOTA = 2;
    private static final int SURVEY_RADIUS = 12;

    private final BotPlayer bot;
    private final com.dddgn.alice.perception.ScopeBuffer scope;
    /** 真作业（**直接建**：夹具自己占着会话 ⇒ 不能走 `BotManager` 给自己起任务，`MineRunMetricsCheckTask` 同）。 */
    private com.dddgn.alice.job.mine.MineJob job;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private int ticks;
    private boolean started;
    private boolean done;
    private boolean lockWasActive;

    public MineSurveyCheckTask(BotPlayer bot, com.dddgn.alice.perception.ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "MineSurveyCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        ticks++;
        if (!started) {
            started = true;
            runPureStatChecks();
            startSurvey();
            return Task.Status.RUNNING;
        }
        job.tick();
        if (!MineSurvey.active()) {
            // 终态钩子跑过（`MineJob.finish` 里打点，它自己 `reset()` + 解锁）⇒ 这就是"到终态且没卡死"的证据
            verifyTerminalSnapshot();
            return finish();
        }
        if (ticks > BUDGET_TICKS) {
            check("必须在 " + BUDGET_TICKS + " tick 内到终态（卡死检测；实测 " + ticks + "）", false);
            cleanup();
            return finish();
        }
        return Task.Status.RUNNING;
    }

    // ==================== ① 纯统计口径（合成数据，不依赖世界） ====================

    private void runPureStatChecks() {
        BlockPos start = new BlockPos(0, 64, 0);
        List<BlockPos> order = List.of(
                new BlockPos(0, 64, 0),      // 同层
                new BlockPos(0, 63, 0),      // 下 1
                new BlockPos(3, 60, 4),      // 下 4，水平 5
                new BlockPos(0, 66, 0));     // 上 2
        var snapshot = MineSurveyStats.of(start, order, 3, 2, 77, "quota_met", List.of("world_refused"));
        check("统计：Δy 三分法（下=2 平=1 上=1；实测 " + snapshot.downFromStart() + "/"
                        + snapshot.sameFromStart() + "/" + snapshot.upFromStart() + "）",
                snapshot.downFromStart() == 2 && snapshot.sameFromStart() == 1 && snapshot.upFromStart() == 1);
        check("统计：最大下降深度=4（实测 " + snapshot.maxDrop() + "）· 水平最大=5.0（实测 "
                        + snapshot.maxHorizontal() + "）· 不同水平列数=2（实测 "
                        + snapshot.distinctColumns() + "）",
                snapshot.maxDrop() == 4 && Math.abs(snapshot.maxHorizontal() - 5.0D) < 1e-6
                        && snapshot.distinctColumns() == 2);
        check("统计：向下占比 2/4=0.5（实测 " + MineSurveyStats.downRatio(snapshot) + "）· 失败码被计数（"
                        + snapshot.failures() + "）",
                Math.abs(MineSurveyStats.downRatio(snapshot) - 0.5D) < 1e-9
                        && snapshot.failures().equals(List.of("world_refused×1")));
        var empty = MineSurveyStats.of(start, List.of(), 0, 2, 5, "no_reachable_candidate", List.of());
        check("统计：一次都没选中 ⇒ 分母为 0 时不许 NaN/崩溃（占比=" + MineSurveyStats.downRatio(empty)
                        + " 均值=" + empty.meanDeltaY() + "）",
                MineSurveyStats.downRatio(empty) == 0.0D && empty.meanDeltaY() == 0.0D);
    }

    // ==================== ② 锁：LLM 起任务必须被拒 ====================

    private void startSurvey() {
        check("前提：自检开始时**没有**残留的手动占用锁", !ManualTestLock.active());
        ManualTestLock.on("mine_survey 自检");
        lockWasActive = ManualTestLock.active();
        // ⭐ 锁的判据走**准入谓词本身**，不走 `BotManager.assignJob`：夹具自己就占着会话，
        //    那样返回 false 分不清是"锁"还是"会话忙" ⇒ 那是**假绿**（本项目当天已踩过两次同类坑）。
        String refusal = ManualTestLock.refusalFor(bot, "fixture probe");
        check("⭐ 锁生效：生产准入谓词给出拒绝码（实测 " + refusal + "）",
                refusal != null && refusal.startsWith("manual_test_lock:"));
        boolean visible = com.dddgn.alice.decision.BotEventLog.recent(bot, 32).stream()
                .anyMatch(event -> event.summary() != null && event.summary().contains("manual_test_lock"));
        check("⭐ 拒绝**可见**：事件环里有对应的 REFUSED 记录（决策层/事后复盘靠它）", visible);
        // 手动入口能起：**直接建 Job**（与本模块其它步骤同一条路），并用**生产策略**（标签目标 ⇒ 成本模型）
        MineSurvey.enable(bot.blockPosition(), bot.serverLevel().getGameTime(), "mine_survey 自检");
        var source = new com.dddgn.alice.job.mine.MineCandidateSource(
                com.dddgn.alice.job.mine.MineCandidateSource.Target.ofTag(
                        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                                new net.minecraft.resources.ResourceLocation("forge", "ores"))),
                SURVEY_RADIUS);
        job = new com.dddgn.alice.job.mine.MineJob(bot,
                com.dddgn.alice.job.GoalSpec.mineBlocks(bot.blockPosition(), SURVEY_RADIUS, SURVEY_QUOTA,
                        900),
                scope, source, com.dddgn.alice.job.policy.CostOptimalPolicy.production());
        check("手动入口起任务（直接建真 Job：标签目标 + 成本最优策略）", job != null);
    }

    private void verifyTerminalSnapshot() {
        var snapshot = MineSurvey.lastSnapshot();
        check("⭐ 终态钩子真的跑过：锁被**自动放掉**（起任务时锁=" + lockWasActive + " 现在="
                        + ManualTestLock.active() + "）", lockWasActive && !ManualTestLock.active());
        check("⭐ 产出了快照（不是空跑）", snapshot != null);
        if (snapshot == null) {
            return;
        }
        check("摘要字段可用：被选中=" + snapshot.attempts() + " 成功=" + snapshot.successes()
                        + " 终态=" + snapshot.terminal() + " ticks=" + snapshot.ticks(),
                snapshot.attempts() > 0 && !snapshot.terminal().isBlank() && snapshot.ticks() > 0);
        double ratio = MineSurveyStats.downRatio(snapshot);
        check("向下占比落在 [0,1]（实测 " + String.format(java.util.Locale.ROOT, "%.2f", ratio)
                        + "；分母=被选中的目标数=" + snapshot.attempts() + "）",
                ratio >= 0.0D && ratio <= 1.0D);
        BotLog.info("[MineSurvey] 自检读数：{}", snapshot.describe());
    }

    /**
     * 收尾复位（**夹具纪律**：场景/夹具结束必须把 bot 状态还原，否则会污染后面的步骤）。
     *
     * <p>⭐ 实测踩到（2026-09-20，`full` 里 `scope_pending_grace` 红、单跑却绿）：
     * 本夹具跑真作业时，挖掘/收集会把**主手选中槽**挪到别处且不还原；下游夹具 `ensurePickaxe` 把镐放进
     * **槽 0**，而它的前提判据读的是"**选中槽**里的物品" ⇒ 读到 `air` ⇒ "前提未复现"红。
     * ⇒ 收尾必须显式把选中槽拉回 0 并广播主手（`syncMainHand`：客户端也才不会渲染成旧物品）。
     */
    private void cleanup() {
        MineSurvey.reset();
        ManualTestLock.off("自检收尾");
        bot.controller().stopMovement();
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.getInventory().selected = 0;
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }

    private Task.Status finish() {
        done = true;
        cleanup();
        boolean pass = failures.isEmpty();
        BotLog.info("[MineSurveyCheck] SUMMARY checks={} failures={} {} → {}", checksRun, failures.size(),
                failures, pass ? "PASS" : "FAIL");
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }
}
