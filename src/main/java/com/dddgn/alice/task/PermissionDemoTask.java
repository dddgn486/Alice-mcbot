package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.BotEventLog;
import com.dddgn.alice.decision.PermissionGate;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * **请示通道演示任务**（S3 / D-140）：验"需要人决断的事必须问、超时=拒绝、绝不死等"。
 *
 * <p>流程：每 tick 调 {@link PermissionGate#request}：
 * <ul>
 *   <li>返回 {@code null} ⇒ 还在等玩家答复（**继续 RUNNING，不阻塞服务器**）；</li>
 *   <li>返回结论 ⇒ 允许则"执行演示能力"（记一条 MILESTONE 事件 + 聊天），
 *       拒绝/超时则走**自动返回**（如实记终态理由），两种情况都结束任务。</li>
 * </ul>
 * 超时由 {@link PermissionGate#tick}（调度循环里每 tick 调）负责落档为"按默认档"，本任务只轮询。
 */
public class PermissionDemoTask implements Task {

    /** 请示有效期：600 tick = 30 s（用户裁定：超时=不批准）。 */
    public static final int TIMEOUT_TICKS = 600;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final int maxTicks;

    private int ticks;
    private String terminalReason = "";

    public PermissionDemoTask(BotPlayer bot, ServerPlayer observer, int maxTicks) {
        this.bot = bot;
        this.observer = observer;
        this.maxTicks = Math.max(40, maxTicks);
    }

    @Override
    public String taskName() {
        return "PermissionDemo";
    }

    @Override
    public com.dddgn.alice.task.TaskTarget target() {
        return com.dddgn.alice.task.TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return "";
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public Status tick() {
        if (++ticks > maxTicks) {
            terminalReason = "goal_timeout";
            return finish("演示任务自身超时（请示仍未闭环）");
        }
        PermissionGate.Decision decision = PermissionGate.request(bot, PermissionGate.CAP_DEMO,
                "演示：是否允许 bot 执行「演示能力」", List.of("allow", "deny"), "deny", TIMEOUT_TICKS);
        if (decision == null) {
            return Status.RUNNING;   // 等玩家答复/超时
        }
        // 终止理由保持**机器可读**：denied:timeout / denied:player（不把玩家名塞进理由里）
        terminalReason = decision.allowed() ? "allowed"
                : ("denied:" + (decision.decidedBy().startsWith("player") ? "player" : decision.decidedBy()));
        BotLog.info("[Perm] demo decision {} → terminalReason={}", decision.describe(), terminalReason);
        return finish(decision.allowed()
                ? "演示能力**已执行**（批准来源=" + decision.decidedBy() + "，范围=" + decision.scope() + "）"
                : "演示能力**未执行**（" + decision.decidedBy() + "）—— 自动返回，不留残留");
    }

    private Status finish(String text) {
        BotEventLog.record(bot, "PERMISSION", terminalReason.startsWith("allowed") ? "info" : "warn",
                "演示请示闭环 " + terminalReason, "");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] " + text));
        }
        BotLog.info("[Perm] demo SUMMARY reason={} ticks={} → DONE", terminalReason, ticks);
        return Status.DONE;
    }
}
