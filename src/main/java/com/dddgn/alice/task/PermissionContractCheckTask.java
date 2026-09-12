package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.PermissionGate;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **请示通道契约自检**（基-2 / D-149）：两条路都要可复跑（绕过客户端 UI，直接调闸门）。
 *
 * <pre>
 * A 批准路径：request ⇒ **夹具替玩家答复 allow** ⇒ 结论 allowed（by=fixture:auto）
 * B 超时路径：request（短时限 40 tick）⇒ 不答复 ⇒ 结论 denied:timeout（**自动返回**）
 * C 策略直达：把能力设成 AUTO ⇒ request 立即 allowed（by=policy:auto），且**不产生待答复**
 * D 不重复问：同一能力在待答复期间连续 request ⇒ 仍然只有 1 条 pending
 * </pre>
 */
public class PermissionContractCheckTask implements Task {

    /** 超时路径用短时限（40 tick = 2 s），电池不用等 30 s。 */
    private static final int SHORT_DEADLINE = 40;
    private static final String CAP_A = "check.contract_allow";
    private static final String CAP_B = "check.contract_timeout";
    private static final String CAP_C = "check.contract_auto";

    private enum Phase { ASK_ALLOW, POLL_ALLOW, ASK_TIMEOUT, POLL_TIMEOUT, CHECK_AUTO, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.ASK_ALLOW;
    private int ticks;
    private boolean allowedSeen;
    private boolean autoImmediate;
    private boolean noDuplicatePending = true;
    private int pendingProbeCount = -1;

    public PermissionContractCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PermissionContractCheck";
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
        if (++ticks > 600) {
            failures.add("自身超时 phase=" + phase);
            return finish();
        }
        PermissionGate.Decision decision;
        switch (phase) {
            case ASK_ALLOW -> {
                decision = PermissionGate.request(bot, CAP_A, "契约自检 A：批准路径",
                        List.of("allow", "deny"), "deny", 400);
                if (decision == null) {
                    // 待答复中：再问一次，验证**不重复登记**
                    PermissionGate.request(bot, CAP_A, "契约自检 A：批准路径",
                            List.of("allow", "deny"), "deny", 400);
                    int pendingA = (int) PermissionGate.pending(bot).stream()
                            .filter(r -> r.capability().equals(CAP_A)).count();
                    pendingProbeCount = pendingA;
                    noDuplicatePending = pendingA == 1;
                    // 夹具替玩家答复
                    String id = PermissionGate.pending(bot).stream()
                            .filter(r -> r.capability().equals(CAP_A)).findFirst()
                            .map(PermissionGate.Request::id).orElse(null);
                    if (id != null) {
                        PermissionGate.answer(bot.getServer(), id, "allow",
                                PermissionGate.Scope.ONCE, "fixture:auto");
                    }
                    phase = Phase.POLL_ALLOW;
                } else {
                    failures.add("A 首次 request 不该直接给结论（默认 ASK）：" + decision.describe());
                    phase = Phase.POLL_ALLOW;
                }
            }
            case POLL_ALLOW -> {
                decision = PermissionGate.request(bot, CAP_A, "契约自检 A", List.of("allow", "deny"),
                        "deny", 400);
                if (decision != null) {
                    allowedSeen = decision.allowed() && "fixture:auto".equals(decision.decidedBy());
                    if (!allowedSeen) {
                        failures.add("A 答复 allow 后应得到 allowed(by=fixture:auto)，实际 "
                                + decision.describe());
                    }
                    if (!noDuplicatePending) {
                        failures.add("D 待答复期间重复 request 应只有 1 条 pending，实际 " + pendingProbeCount);
                    }
                    phase = Phase.ASK_TIMEOUT;
                }
            }
            case ASK_TIMEOUT -> {
                decision = PermissionGate.request(bot, CAP_B, "契约自检 B：超时路径",
                        List.of("allow", "deny"), "deny", SHORT_DEADLINE);
                if (decision != null) {
                    failures.add("B 首次 request 不该直接给结论：" + decision.describe());
                }
                phase = Phase.POLL_TIMEOUT;
            }
            case POLL_TIMEOUT -> {
                decision = PermissionGate.request(bot, CAP_B, "契约自检 B", List.of("allow", "deny"),
                        "deny", SHORT_DEADLINE);
                if (decision != null) {
                    if (decision.allowed() || !"timeout".equals(decision.decidedBy())) {
                        failures.add("B 不答复应超时按默认档拒绝（by=timeout），实际 " + decision.describe());
                    } else {
                        BotLog.info("[PermCheck] B 超时路径 OK：{}", decision.describe());
                    }
                    phase = Phase.CHECK_AUTO;
                }
            }
            case CHECK_AUTO -> {
                PermissionGate.setPolicy(bot.getServer(), CAP_C, PermissionGate.Policy.AUTO);
                decision = PermissionGate.request(bot, CAP_C, "契约自检 C：策略直达",
                        List.of("allow", "deny"), "deny", 400);
                autoImmediate = decision != null && decision.allowed();
                if (!autoImmediate) {
                    failures.add("C 策略=AUTO 时应立即 allowed，实际 " + (decision == null ? "null"
                            : decision.describe()));
                }
                boolean pendingC = PermissionGate.pending(bot).stream()
                        .anyMatch(r -> r.capability().equals(CAP_C));
                if (pendingC) {
                    failures.add("C 策略=AUTO 不应产生待答复");
                }
                phase = Phase.DONE;
                return finish();
            }
            default -> {
                return finish();
            }
        }
        return Status.RUNNING;
    }

    private Status finish() {
        phase = Phase.DONE;
        boolean pass = failures.isEmpty();
        String summary = "allow_path=" + allowedSeen + " timeout_path=" + (!failures.isEmpty() ? "?" : "ok")
                + " auto_path=" + autoImmediate + " no_duplicate=" + noDuplicatePending
                + " failures=" + failures + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[PermCheck] SUMMARY {}", summary);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 请示契约自检 " + summary));
        }
        return pass ? Status.DONE : Status.FAILED;
    }
}
