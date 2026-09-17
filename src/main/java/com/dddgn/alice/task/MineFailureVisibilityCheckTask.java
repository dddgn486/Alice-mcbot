package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.EventThresholds;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **队列第③项判据（2026-09-17）**：`MineJob` 在**新尝试上场时**必须保留"上一轮尝试的失败事实"。
 *
 * <p>缺口（`survey/16 §2` 收窄后）：`MineJob.subTasks()` 走 `miner != null`（活动尝试）分支时，
 * 原先把 failure 传**空串** ⇒ 一到重试，决策快照里的 `tree[].children[].lastFailure` 就空了，
 * 而那一刻恰恰是最需要知道"上次为什么失败"的时候（M4b 只修好了 `miner == null` 那一支）。
 *
 * <p>**为什么必须"运行中"采样**：任务结束时 `miner == null`，走的是 `finishedMinerNode` 分支 ——
 * 那一支**本来就有** failure ⇒ 在结束态断言**判别不了**这个缺口（会假绿）。
 * 所以本夹具**自己持有并手动 tick 一个 `MineJob`**，在"活动尝试 + 已有失败"同时成立的那一刻采样。
 *
 * <p>**怎么造出这个组合**（确定性，不依赖地形偶然）：用 `MineJob` 已有的
 * `identityCheck` 覆盖器（`mine_stale` 用的同一个夹具缝）——**前 {@link #FAIL_FIRST} 个候选判为
 * `target_replaced`**，之后放行。于是必然先积累失败尝试、随后真的开一个 `MineTask`（`miner != null`）。
 *
 * <p>⚠️ **不能**用 `pos -> false`（`mine_stale` 那样）：那会让 `miner` **永远为 null**（走 `shortfall`），
 * 活动分支根本到不了 —— 判据会空转成假绿。
 */
public class MineFailureVisibilityCheckTask implements Task {

    /** 观察上限（tick）。 */
    private static final int MAX_TICKS = 600;
    /** 前几个候选判为"已被替换"（制造上一轮失败）。 */
    private static final int FAIL_FIRST = 2;
    /** 扫描半径（与 ore_course 场景的矿物分布匹配）。 */
    private static final int SCAN_RADIUS = 12;
    /** 采样之后再跑几 tick 让日志落盘。 */
    private static final int TAIL_TICKS = 5;
    /** **队列第④项**：低频进度事件间隔（tick）。 */
    private static final int PROGRESS_INTERVAL = 20;
    /** 观测进度事件需要的 tick 数（≥ 3 个间隔才说明"低频但持续"）。 */
    private static final int PROGRESS_OBSERVE_TICKS = 70;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private int ticks;
    private int overrideCalls;
    private boolean sampled;
    private boolean done;
    private MineJob job;
    private ScopeBuffer scope;

    public MineFailureVisibilityCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MineFailureVisibilityCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(OreCourseAnchor.START_FOOT);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        ticks++;
        if (job == null) {
            // 夹具纪律：自定位 + 复位库存 + 给镐（一次动作覆盖全部，不依赖电池 provision）
            bot.teleportTo(bot.serverLevel(),
                    OreCourseAnchor.START_FOOT.getX() + 0.5D,
                    OreCourseAnchor.START_FOOT.getY(),
                    OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(Vec3.ZERO);
            bot.controller().stopMovement();
            FixtureToolKit.resetInventory(bot);
            FixtureToolKit.ensurePickaxe(bot);
            scope = new ScopeBuffer();
            job = new MineJob(bot,
                    GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT, SCAN_RADIUS, 1, 400),
                    scope,
                    new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE), SCAN_RADIUS),
                    new NearestPolicy(),
                    pos -> ++overrideCalls > FAIL_FIRST);   // 前 FAIL_FIRST 个候选"已被替换"
            // **队列第④项的前提**：进度事件**生产默认必须是关的**（否则它就是个常开噪声源）
            check("前提：低频进度事件生产默认关（间隔=0）",
                    EventThresholds.PROGRESS_EVENT_INTERVAL_TICKS == 0);
            EventThresholds.setProgressEventInterval(PROGRESS_INTERVAL);
            progressBaseline = EventThresholds.progressEmits(bot);
            BotLog.info("[MineFailure] 夹具：前 {} 个候选判为 target_replaced，之后放行（构造"
                    + "『活动尝试 + 已有失败』的组合）；同时开低频进度事件 interval={}",
                    FAIL_FIRST, PROGRESS_INTERVAL);
            return Status.RUNNING;
        }
        job.tick();
        if (!sampled && job.hasActiveAttempt() && job.attemptFailureCount() > 0) {
            sampled = true;
            List<TaskNode> nodes = job.subTasks();
            TaskNode node = nodes.isEmpty() ? null : nodes.get(0);
            String fail = node == null ? null : node.lastFailure();
            // ⭐ 判据：活动尝试节点的 lastFailure 必须带上上一轮失败
            check("活动尝试节点的 lastFailure 保留上一轮失败事实（队列③）",
                    fail != null && fail.contains("target_replaced"));
            check("前提：上一轮失败确实被记录（attemptFailureCount>0）",
                    job.attemptFailureCount() > 0);
            BotLog.info("[MineFailure] 采样：active={} attemptFailures={} node.kind={} node.failure={}",
                    job.hasActiveAttempt(), job.attemptFailureCount(),
                    node == null ? "-" : node.kind(), fail);
        }
        // **队列第④项判据**：低频进度事件必须**按间隔持续**进来（且默认关，见上面的前提 check）
        if (!progressChecked && ticks >= PROGRESS_OBSERVE_TICKS) {
            progressChecked = true;
            int emitted = EventThresholds.progressEmits(bot) - progressBaseline;
            check("低频进度事件按间隔持续上报（间隔=" + PROGRESS_INTERVAL + " tick，"
                            + PROGRESS_OBSERVE_TICKS + " tick 内至少 2 次，实测 " + emitted + " 次）",
                    emitted >= 2);
        }
        if (sampled && progressChecked && ticks > 0 && !finishScheduled) {
            finishScheduled = true;
            finishAtTick = ticks + TAIL_TICKS;
        }
        if (ticks >= MAX_TICKS || (finishScheduled && ticks >= finishAtTick)) {
            finish();
        }
        return Status.RUNNING;
    }

    private boolean finishScheduled;
    private int finishAtTick;
    private int progressBaseline;
    private boolean progressChecked;

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }

    private void finish() {
        if (!sampled) {
            // 显式把"空转"判为失败（2026-09-17 no_progress 假绿的教训：前提不成立必须红）
            check("前提：必须观测到『活动尝试 + 已有失败』（否则用例空转，不能算过）", false);
        }
        if (scope != null) {
            scope.end();   // 别把作用域留着影响后续电池步骤
        }
        if (EventThresholds.PROGRESS_EVENT_INTERVAL_TICKS != 0) {
            EventThresholds.setProgressEventInterval(0);   // 全局开关必须复位（同 no_progress 的窗口纪律）
        }
        check("收尾必须把进度事件间隔复位为 0",
                EventThresholds.PROGRESS_EVENT_INTERVAL_TICKS == 0);
        bot.controller().stopMovement();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.teleportTo(bot.serverLevel(),
                OreCourseAnchor.START_FOOT.getX() + 0.5D,
                OreCourseAnchor.START_FOOT.getY(),
                OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[MineFailure] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 挖矿失败可见性自检 "
                    + (pass ? "PASS（活动尝试仍带着上一轮失败事实）" : "FAIL " + failures)));
        }
    }
}
