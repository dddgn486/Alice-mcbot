package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.TaskExecutionRecord;
import com.dddgn.alice.decision.BotEventLog;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * **中止回调（Job 的「说话权」）自检** —— `D-457` 第 3 件 / `J-★` 第 6 段 **step 1.5**。
 *
 * <p>被测的生产契约：{@link Task#onTerminated} —— 框架发起的中止（显式停止 / 维生打断 / 被顶替）
 * 必须在 `recordTerminal(...)` **之前**给任务一次说话的机会。
 *
 * <h2>两臂</h2>
 * <ul>
 *   <li>**臂 A（正常中止）**：探针任务被停 ⇒ ① 钩子**恰好一次** ② 框架记录里的 `terminalReason`
 *       **非空且等于探针说的那句话**（= 证明「先说话、后记账」的**顺序**；记完再问会是空串，
 *       真机签名就是 `task_terminal_reason … terminalReason=` 空）③ 事件环有 `STOP` ④ 任务收尾真的跑了；</li>
 *   <li>**臂 B（回调抛异常）**：钩子抛异常 ⇒ **终态记账照旧**（记录仍在、任务仍被清）、探针没说出话
 *       —— 验 `notifyTaskTerminated` 的异常兜底（抛异常丢的只能是「任务自己的话」，不许连累记账）。</li>
 * </ul>
 *
 * <h2>为什么用**探针假人**（`D-169` 实测事故）</h2>
 * 夹具请求「停止」停的是**顶层任务**；本夹具自己就是电池的步 ⇒ **它不能停自己**（会把电池停掉，
 * 电池在第 N 步自杀、永远打不出 SUMMARY）。因此照 `SurvivalStopInHazardCheckTask` 的先例：
 * 另起一只**探针假人**，停**它**的任务，读**它**的记录。电池本体一步不动。
 *
 * <h2>前提（§6.9.1 三条，逐条自断言）</h2>
 * <ol>
 *   <li>**几何**：隔离场地 `SITE` 的 3×3 平台必须真的建起来且可站（先 `getChunkAt` 强制加载 ——
 *       否则 `setBlock` 静默 0 改动，判据会红成一片「看着像改动坏了」；陷阱 #4）；</li>
 *   <li>**世界/模组集**：只用原版 `stone`，不依赖任何模组方块；</li>
 *   <li>**层归属**：断言落在**框架层**（`BotManager.lastExecutionRecord` / `BotEventLog`），
 *       不落在被判任务自己的字段上；</li>
 *   <li>**请求必须发生在已落地时** —— 空中的请求会走 K-3 的「延后到安全点」那一档（那是
 *       `K3StopCheckTask` 的用例），本夹具会等它落地再提，并把这个前提断言下来。</li>
 * </ol>
 */
public final class JobAbortHookCheckTask implements Task {

    /** 隔离场地（与 `SurvivalStopInHazardCheckTask` 的 `2600/-60/5400` 相距 20 格、不同区块）。 */
    private static final BlockPos SITE = new BlockPos(2620, -60, 5400);
    private static final String PROBE_NAME = "abort_hook_probe";
    private static final int BUDGET_TICKS = 200;
    /** 等探针落地的最多 tick（落地是「立即停」路径的前提）。 */
    private static final int GROUND_WAIT_TICKS = 40;

    private enum Phase { PREPARE, WAIT_GROUND, VERIFY, THROW_ARM, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private int checks;
    private BotPlayer probe;
    private ProbeTask probeTask;
    private ThrowProbeTask throwTask;
    private String requested;
    private String requestedThrow;
    private boolean groundedAtRequest;

    public JobAbortHookCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "JobAbortHookCheck";
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
        return phase == Phase.DONE ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > BUDGET_TICKS) {
            return finish("timeout");
        }
        phaseTicks++;
        return switch (phase) {
            case PREPARE -> prepare();
            case WAIT_GROUND -> waitGroundAndStop();
            case VERIFY -> verifyArmA();
            case THROW_ARM -> throwArm();
            case DONE -> failures.isEmpty() ? Status.DONE : Status.FAILED;
        };
    }

    // ==================== 各相位 ====================

    private Status prepare() {
        ServerLevel level = bot.serverLevel();
        // ⚠️ **陷阱 #4**：区块没加载时 `setBlock` 静默 0 改动 ⇒ 先强制加载（判据只看结果，不看运气）
        level.getChunkAt(SITE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(SITE.below().offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(SITE, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(SITE.above(), Blocks.AIR.defaultBlockState(), 3);
        check("前提：场地可站（脚位+头位可穿 且 有地板）", MovementHelper.bodyPassable(level, SITE)
                && MovementHelper.canWalkOn(level, SITE));

        probe = BotManager.spawn(level, SITE, PROBE_NAME);
        if (probe == null) {
            check("硬前提失败：探针假人生成失败（" + PROBE_NAME + "）", false);
            return finish("probe_spawn_failed");
        }
        com.dddgn.alice.decision.GoalDirector.suspend(probe, BUDGET_TICKS + 200);
        probeTask = new ProbeTask(probe);
        BotManager.assignTask(probe, probeTask);
        check("前提：探针**有任务**（`hasTask`=true，且就是本夹具装的那个）",
                BotManager.hasTask(probe) && BotManager.sessionOf(probe) != null
                        && BotManager.sessionOf(probe).currentTask() == probeTask);
        BotLog.info("[AbortHook] 场地 {} 探针={} 脚位={} 任务={}",
                SITE.toShortString(), PROBE_NAME, probe.blockPosition().toShortString(),
                BotManager.hasTask(probe) ? "yes" : "no");
        return advance(Phase.WAIT_GROUND);
    }

    private Status waitGroundAndStop() {
        if (!probe.onGround()) {
            if (phaseTicks > GROUND_WAIT_TICKS) {
                check("前提失败：探针 " + GROUND_WAIT_TICKS + " tick 内没有落地（空中的请求会走 K-3 的延后档，"
                        + "本夹具测不到「立即停」这条路径）", false);
                return finish("probe_never_grounded");
            }
            return Status.RUNNING;
        }
        groundedAtRequest = true;
        // ⭐ **被观测的动作**：停**探针**的任务（不是电池自己的）
        requested = BotManager.stopTask(probe, "abort_hook_fixture");
        BotLog.info("[AbortHook] 已请求停止探针任务 requested={} onGround={} hasTask={}",
                requested, probe.onGround(), BotManager.hasTask(probe));
        check("前提：停止请求被受理（返回非空，requested=" + requested + "）", requested != null && !requested.isEmpty());
        return advance(Phase.VERIFY);
    }

    private Status verifyArmA() {
        TaskExecutionRecord rec = BotManager.lastExecutionRecord(probe);
        check("前提：探针的终态记录已生成", rec != null);
        if (rec == null) {
            return finish("no_terminal_record");
        }
        // ⭐ ① 钩子恰好一次
        check("① 中止钩子**恰好调用一次**（实际 " + probeTask.calls() + "）", probeTask.calls() == 1);
        // ⭐ ② 记录里的 terminalReason 非空、且**就是任务说的那句话** ⇒ 证明「先说话、后记账」的顺序
        String said = probeTask.terminalReason();
        check("② 框架记录里的 terminalReason 非空（实际「" + rec.terminalReason() + "」）",
                rec.terminalReason() != null && !rec.terminalReason().isBlank());
        check("② 记录里的理由 = 任务说的那句话（记录「" + rec.terminalReason() + "」 vs 任务「" + said + "」）",
                // ⚠️ **必须带非空前提**：两个空串 `equals` 也成立 ⇒ 首版这条在红臂里**恒真**
                // （反向对照实测：钩子被注释掉时它照样绿，是个空转判据）
                said != null && !said.isBlank() && said.equals(rec.terminalReason()));
        check("② 终态档 = CANCELLED_BY_USER（实际 " + rec.terminalStatus() + "）",
                rec.terminalStatus() == TaskExecutionRecord.TerminalStatus.CANCELLED_BY_USER);
        // ⭐ ③ 事件环有 STOP（且指明是本夹具的理由）
        boolean stopEvent = BotEventLog.recent(probe, 30).stream()
                .anyMatch(e -> "STOP".equals(e.type()) && e.summary() != null
                        && e.summary().contains("abort_hook_fixture"));
        check("③ 事件环里有 `STOP`（且理由含 abort_hook_fixture）", stopEvent);
        // ⭐ ④ 任务自己的收尾真的跑了（钩子里做的事）
        check("④ 钩子里的收尾动作真的执行了（closed=true，实际 " + probeTask.closed() + "）", probeTask.closed());
        check("前提：停止后探针**没有任务**（框架清干净了）", !BotManager.hasTask(probe));

        // ---- 臂 B：回调抛异常 ⇒ 终态记账照旧 ----
        throwTask = new ThrowProbeTask(probe);
        BotManager.assignTask(probe, throwTask);
        check("臂 B 前提：抛异常任务已装上", BotManager.sessionOf(probe) != null
                && BotManager.sessionOf(probe).currentTask() == throwTask);
        return advance(Phase.THROW_ARM);
    }

    private Status throwArm() {
        if (requestedThrow == null) {
            if (!probe.onGround()) {
                return Status.RUNNING;
            }
            requestedThrow = BotManager.stopTask(probe, "abort_hook_throw_fixture");
            check("臂 B：停止请求被受理（requested=" + requestedThrow + "）",
                    requestedThrow != null && !requestedThrow.isEmpty());
            return Status.RUNNING;
        }
        TaskExecutionRecord rec = BotManager.lastExecutionRecord(probe);
        check("臂 B：钩子被调用过（calls=" + throwTask.calls() + "）", throwTask.calls() == 1);
        check("⭐ 臂 B：**钩子抛异常也没连累终态记账**（记录仍在，实际 terminal="
                        + (rec == null ? "null" : rec.terminalStatus()) + "）",
                rec != null && rec.terminalStatus() == TaskExecutionRecord.TerminalStatus.CANCELLED_BY_USER);
        check("臂 B：任务仍被清掉（`hasTask`=false）", !BotManager.hasTask(probe));
        check("臂 B：抛异常的那次**没能说话**（terminalReason 为空，实际「"
                        + (rec == null ? "-" : rec.terminalReason()) + "」）",
                rec != null && (rec.terminalReason() == null || rec.terminalReason().isBlank()));
        // 清理：场地还原为空气（§6.9.2 失败路径也要清）
        ServerLevel level = bot.serverLevel();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(SITE.below().offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        check("清理：平台已还原为空气", !MovementHelper.canWalkOn(level, SITE));
        // ⚠️ 必须走 `finish(...)` 而不是 `advance(DONE)`：否则**SUMMARY 一行都不打**（首版实测：
        // 判决是 PASS，但 `checks=` 与失败明细全看不见 ⇒ 判据不可读，等于自评了一把）
        return finish("done");
    }

    // ==================== 助手 ====================

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    private Status finish(String reason) {
        phase = Phase.DONE;
        String summary = "checks=" + checks + " hookCalls=" + (probeTask == null ? "-" : probeTask.calls())
                + " grounded=" + groundedAtRequest + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[AbortHook] SUMMARY {} reason={} failures=[{}]", summary, reason,
                String.join(" ; ", failures));
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AbortHook] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    /** 探针任务：一直在跑，被中止时「说一句话」并留下收尾痕迹。 */
    private static final class ProbeTask implements Task {
        private final BotPlayer bot;
        private final List<String> said = new ArrayList<>();
        private boolean closed;

        ProbeTask(BotPlayer bot) {
            this.bot = bot;
        }

        @Override
        public void onTerminated(TaskExecutionRecord.TerminalStatus status, String resultCode) {
            said.add(status + ":" + resultCode);
            closed = true;                       // 「收尾动作」的痕迹（判据 ④）
        }

        @Override
        public String terminalReason() {
            return said.isEmpty() ? "" : "aborted_at:" + said.get(0);
        }

        int calls() {
            return said.size();
        }

        boolean closed() {
            return closed;
        }

        @Override
        public TaskTarget target() {
            return TaskTarget.block(bot.blockPosition());
        }

        @Override
        public String failureReason() {
            return "";
        }

        @Override
        public Status tick() {
            return Status.RUNNING;
        }
    }

    /** 臂 B 的探针任务：钩子**故意抛异常**（验框架的异常兜底）。 */
    private static final class ThrowProbeTask implements Task {
        private final BotPlayer bot;
        private int calls;

        ThrowProbeTask(BotPlayer bot) {
            this.bot = bot;
        }

        @Override
        public void onTerminated(TaskExecutionRecord.TerminalStatus status, String resultCode) {
            calls++;
            throw new IllegalStateException("夹具故意抛（臂 B）：onTerminated 不许连累终态记账");
        }

        int calls() {
            return calls;
        }

        @Override
        public TaskTarget target() {
            return TaskTarget.block(bot.blockPosition());
        }

        @Override
        public String failureReason() {
            return "";
        }

        @Override
        public Status tick() {
            return Status.RUNNING;
        }
    }
}
