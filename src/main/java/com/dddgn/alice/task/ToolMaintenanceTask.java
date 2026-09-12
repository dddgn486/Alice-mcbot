package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.ToolSupply;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerPlayer;

/**
 * **工具维护**（基-9）：确定性动作 —— 让"能用的工具"真的在手上。
 *
 * <p>触发场景（实测复现过两次）：S4 的 `TOOL_LOW` 事件把决策层招来，而词汇表里**没有**可做的事，
 * LLM 只能瞎起一个 Job。现在它可以选 `maintain_tool`，由本任务确定性地：
 * <ol>
 *   <li>{@code INSPECT}：看该类工具最好的那件在哪、剩多少（{@link ToolSupply}）；</li>
 *   <li>{@code PROMOTE}：在主背包就搬进快捷栏（**背包内移动，不写世界、不耗资源**）；</li>
 *   <li>结论只有三种，且都如实写进终态：{@code tool_ok}（手上可用）/ {@code promoted_from_main}
 *       （已搬进来）/ {@code no_spare_tool}（身上没有更好的 ⇒ **不假装能修**，交回决策层/请示）。</li>
 * </ol>
 *
 * <p>**不做事**：不合成、不去挖材料、不动世界 —— 那属于 S6 与请示通道（能力名 `fetch_tool_materials`
 * 已登记）。本任务的意义是把"能自己解决的"和"必须上报的"分开。
 */
public class ToolMaintenanceTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ToolSupply.Kind kind;

    private Phase phase = Phase.INSPECT;
    private int ticks;
    private String terminalReason = "";
    private String summary = "-";
    private ToolSupply.Snapshot before;
    private ToolSupply.Snapshot after;

    private enum Phase { INSPECT, PROMOTE, DONE }

    /** 与 S4 事件层阈值保持一致（见 `EventThresholds.TOOL_LOW_RATIO`）。 */
    private static final float LOW_RATIO = 0.20F;

    public ToolMaintenanceTask(BotPlayer bot, ServerPlayer observer, ToolSupply.Kind kind) {
        this.bot = bot;
        this.observer = observer;
        this.kind = kind;
    }

    @Override
    public String taskName() {
        return "ToolMaintenance";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    /** 维护任务**不产生失败**：它做完了自己能做的（结果在 `terminalReason` + summary 里如实写）。 */
    @Override
    public String failureReason() {
        return "";
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    /** 仅供本任务日志用（`Task` 接口没有 progressSummary，那是 Job 的）。 */
    public String progressSummary() {
        return "kind=" + kind.label() + " before[" + (before == null ? "-" : before.describe())
                + "] after[" + (after == null ? "-" : after.describe()) + "]";
    }

    @Override
    public com.dddgn.alice.bot.TaskFailureReport failureReport() {
        return new com.dddgn.alice.bot.TaskFailureReport(
                terminalReason.isBlank() ? "tool_maintenance_done" : terminalReason, phase.name(),
                progressSummary(), com.dddgn.alice.bot.RecoveryStage.NONE, java.util.List.of());
    }

    @Override
    public Status tick() {
        if (++ticks > 100) {
            terminalReason = "timeout";
            return finish(Status.FAILED);
        }
        return switch (phase) {
            case INSPECT -> inspect();
            case PROMOTE -> promote();
            case DONE -> Status.DONE;
        };
    }

    private Status inspect() {
        before = ToolSupply.inspect(bot, kind);
        if (!before.present()) {
            terminalReason = "no_tool";
            summary = "身上没有该类工具（不合成、不去取材料 —— 那需要请示/授权）";
            BotLog.warn("[ToolMaint] {} no_tool（{}）", kind.label(), ToolSupply.describe(bot));
            return finish(Status.DONE);
        }
        phase = Phase.PROMOTE;
        return Status.RUNNING;
    }

    private Status promote() {
        String code = ToolSupply.promoteFromMain(bot, kind);
        after = ToolSupply.inspect(bot, kind);
        StringBuilder text = new StringBuilder();
        switch (code) {
            case "already_in_hotbar", "moved_to_empty", "swapped_with_worn" -> {
                terminalReason = "already_in_hotbar".equals(code) ? "already_ok" : "promoted_from_main";
                if (after.low(LOW_RATIO)) {
                    // **没得换**：手里这件就是最好的，但它已经很旧 ⇒ 如实说清"需要外部补充"，
                    // 而不是假装维护完成了（合成/取材料属于 S6 + 请示通道，能力名 fetch_tool_materials）
                    terminalReason = "worn_no_spare";
                    text.append("可用工具剩余耐久仅 ")
                            .append(String.format(java.util.Locale.ROOT, "%.0f%%", after.ratio() * 100))
                            .append("，且身上没有更好的 ⇒ 需外部补充（请示/合成，本任务不做）");
                }
            }
            case "no_tool_in_main", "no_tool" -> {
                terminalReason = "no_spare_tool";
                text.append("主背包里没有该类工具可搬");
            }
            case "hotbar_full_no_swap" -> {
                terminalReason = "hotbar_full_no_swap";
                text.append("快捷栏已满且手上不是同类工具 ⇒ 不挤占（如实记录）");
            }
            default -> {
                terminalReason = code;
                text.append("结果码=").append(code);
            }
        }
        summary = text.isEmpty() ? "已就绪" : text.toString();
        BotLog.info("[ToolMaint] {} code={} {}", kind.label(), code, summary);
        phase = Phase.DONE;
        return finish(Status.DONE);
    }

    private Status finish(Status status) {
        phase = Phase.DONE;
        BotLog.info("[ToolMaint] SUMMARY kind={} terminal={} {}", kind.label(), terminalReason,
                progressSummary());
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[ToolMaint] " + kind.label() + " → " + terminalReason + "：" + summary));
        }
        return status;
    }
}
