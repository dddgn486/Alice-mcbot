package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.DecisionState;
import com.dddgn.alice.decision.DecisionTrace;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * **决策 trace / 跨重启语义自检**（基-4）：四例，纯计算 + 一次真实落盘，约 1 秒。
 *
 * <ol>
 *   <li>A **落盘真的写了**：记一条 trace ⇒ 从磁盘读回最后一行，必须含标记（不是只进内存）；</li>
 *   <li>B **内存尾可读**：`recent()` 能取回刚记的条目（供游戏内汇报）；</li>
 *   <li>C **NBT 往返**：`DecisionState` save → load 后，未决请示与"正在跑的任务"都还在；</li>
 *   <li>D **重启语义**：`consumeRestartReport` 必须①把两类丢失都报出来、②**只报一次**（第二次为空）、
 *       ③报完清空（不会下次开服再瞎报）。</li>
 * </ol>
 *
 * <p>为什么必须测"只报一次"：重启报告挂在 bot 生成路径上，如果不清空，每次生成 bot 都会重复报警 ——
 * 那正是本项目反复抓到的"同一件事刷屏"病灶（`[Pickup] blocked` 584 行、`STUCK` 每 tick 上报）。
 */
public class DecisionTraceCheckTask implements Task {

    private static final String MARKER = "selfcheck_trace_marker";

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int ticks;
    private boolean done;

    public DecisionTraceCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "DecisionTraceCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 60) {
            return finish("timeout");
        }
        if (ticks > 1) {
            return done ? done() : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
        // A 落盘
        DecisionTrace.lifecycle(bot, MARKER, "trace_write_probe", "selfcheck");
        String last = DecisionTrace.lastLineOnDisk();
        check("trace_written", last.contains(MARKER),
                "磁盘末行=" + (last.length() > 120 ? last.substring(0, 120) + "…" : last));

        // B 内存尾
        List<String> recent = DecisionTrace.recent(8);
        check("trace_memory_tail", recent.stream().anyMatch(row -> row.contains(MARKER)),
                "recent=" + recent);

        // C NBT 往返（用**独立实例**，不污染真实世界数据）
        UUID probe = UUID.randomUUID();
        DecisionState probeState = new DecisionState();
        probeState.recordPending(probe, "demo_capability", "p99", "selfcheck");
        probeState.recordTask(probe, "SelfCheckTask target=…");
        DecisionState reloaded = DecisionState.roundTrip(probeState);
        boolean pendingKept = reloaded.pendingSnapshot().containsKey(probe.toString());
        boolean taskKept = reloaded.taskSnapshot().containsKey(probe.toString());
        check("state_nbt_roundtrip", pendingKept && taskKept,
                "pendingKept=" + pendingKept + " taskKept=" + taskKept);

        // D 重启语义：报出来 + 只报一次 + 报完清空
        String report = reloaded.consumeRestartReport(probe);
        String second = reloaded.consumeRestartReport(probe);
        boolean reported = report.contains("未决请示") && report.contains("未续做");
        boolean once = second.isEmpty();
        boolean cleared = reloaded.pendingSnapshot().isEmpty() && reloaded.taskSnapshot().isEmpty();
        check("restart_semantics", reported && once && cleared,
                "reported=" + reported + " onlyOnce=" + once + " cleared=" + cleared
                        + " report=" + report);

        String summary = "trace_written=" + verdict("trace_written")
                + " trace_memory_tail=" + verdict("trace_memory_tail")
                + " state_nbt_roundtrip=" + verdict("state_nbt_roundtrip")
                + " restart_semantics=" + verdict("restart_semantics")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[DecisionTrace] SUMMARY {} {}", summary, DecisionTrace.describe());
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[DecisionTrace] " + summary));
        }
        DecisionTrace.forget();
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[DecisionTrace] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status done() {
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[DecisionTrace] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
