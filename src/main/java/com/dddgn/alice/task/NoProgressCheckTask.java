package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.EventThresholds;
import com.dddgn.alice.decision.GoalDirector;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **长作业周期复评的契约自检**（M2 / G2）。
 *
 * <p>背景（`survey/08` §7）：长作业的触发源只有"开始 / 结束 / 维生中断"⇒ 一个 `maxTicks` 很长的 Job
 * **开始响一次、结束响一次**，中段对决策层是黑箱。**M2 是"自主"这个词的物理载体** ——
 * 没有它，"自主长作业"在物理上不存在，只有"一次长动作"。
 *
 * <pre>
 * 1 窗口关着时**不报**（默认关，生产不受影响）
 * 2 开窗 + 有任务在跑 + 无可观测进度 ⇒ 报**恰好一次** NO_PROGRESS（同 episode 不重复）
 * 3 出现进度（本夹具用"脚位变化"制造）⇒ **重新武装**，再次停滞 ⇒ 报第二次（滞回是活的）
 * 4 自检窗口内**只记录不通知**决策层（`GoalDirector.isSuspended`）—— 夹具不该在生产侧留决策痕迹
 * 5 收尾把窗口**复位回 0**（否则后面的电池步骤会在"开了监控"的状态下跑）
 * </pre>
 *
 * <p>用**本任务自己的停滞**当被观察对象：夹具任务就是一个"在跑、却不推进任何东西"的任务。
 * 这比另造一个假 Job 更诚实 —— 被断言的是真实的 `EventThresholds` 判据链。
 */
public class NoProgressCheckTask implements Task {

    /** 观察窗口（tick）：比真实默认值小得多，夹具只要证明"判据是活的"。 */
    private static final int WINDOW = 40;
    /** 窗口之外的余量（保证越阈值后至少被 tick 到一次）。 */
    private static final int MARGIN = 20;

    private enum Phase { SETUP, STALL_FIRST, CHECK_FIRST, REARM, STALL_SECOND, CHECK_SECOND, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private boolean done;

    public NoProgressCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "NoProgressCheck";
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
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        phaseTicks++;
        switch (phase) {
            case SETUP -> {
                // 先确认"关着不报"这一半（判据 1）：窗口=0 时等一段时间，绝不能有事件
                if (phaseTicks < MARGIN) {
                    return Status.RUNNING;
                }
                check("窗口关闭时不应报 NO_PROGRESS",
                        EventThresholds.noProgressEmits(bot) == 0);
                EventThresholds.setNoProgressWindow(WINDOW);
                EventThresholds.resetNoProgressTracking(bot);
                BotLog.info("[NoProgress] 已开窗 window={} tick，开始观察停滞", WINDOW);
                advance(Phase.STALL_FIRST);
                return Status.RUNNING;
            }
            case STALL_FIRST -> {
                if (phaseTicks < WINDOW + MARGIN) {
                    return Status.RUNNING;   // 什么都不做 —— 这正是"无可观测进度"
                }
                advance(Phase.CHECK_FIRST);
                return Status.RUNNING;
            }
            case CHECK_FIRST -> {
                check("停滞 ⇒ 报恰好一次 NO_PROGRESS",
                        EventThresholds.noProgressEmits(bot) == 1);
                check("报过之后标记为已报（同 episode 不重复）",
                        EventThresholds.noProgressReported(bot));
                check("自检窗口内决策层被按住（只记录不通知）", GoalDirector.isSuspended(bot));
                advance(Phase.REARM);
                return Status.RUNNING;
            }
            case REARM -> {
                // 制造**可观测进度**：脚位是进度指纹的一部分 ⇒ 挪一格即"有进度"
                if (phaseTicks == 1) {
                    moveOne();
                    return Status.RUNNING;
                }
                if (phaseTicks < 10) {
                    return Status.RUNNING;
                }
                check("出现进度后重新武装",
                        !EventThresholds.noProgressReported(bot));
                EventThresholds.resetNoProgressTracking(bot);
                advance(Phase.STALL_SECOND);
                return Status.RUNNING;
            }
            case STALL_SECOND -> {
                if (phaseTicks < WINDOW + MARGIN) {
                    return Status.RUNNING;
                }
                advance(Phase.CHECK_SECOND);
                return Status.RUNNING;
            }
            case CHECK_SECOND -> {
                check("再次停滞 ⇒ 报第二次（滞回是活的）",
                        EventThresholds.noProgressEmits(bot) == 2);
                advance(Phase.DONE);
                return Status.RUNNING;
            }
            case DONE -> {
                finish();
                return failures.isEmpty() ? Status.DONE : Status.FAILED;
            }
            default -> {
                return Status.RUNNING;
            }
        }
    }

    private void finish() {
        // **必须复位**：窗口是 static volatile（全局），不复位后面的电池步骤会在"开了监控"下跑
        EventThresholds.setNoProgressWindow(0);
        EventThresholds.resetNoProgressTracking(bot);
        bot.controller().stopMovement();
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.teleportTo(bot.serverLevel(),
                OreCourseAnchor.START_FOOT.getX() + 0.5D,
                OreCourseAnchor.START_FOOT.getY(),
                OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[NoProgress] SUMMARY emits={} failures={} {} → {}",
                EventThresholds.noProgressEmits(bot), failures.size(), failures,
                pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 长作业复评自检 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        BotLog.info("[NoProgress] 结束复位：窗口已关={} bot 回到 {}（onGround={}）",
                EventThresholds.NO_PROGRESS_WINDOW_TICKS == 0,
                bot.blockPosition().toShortString(), bot.onGround());
    }

    private void advance(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            failures.add(what);
        }
    }

    /** 把 bot 挪一格（制造"脚位变化"这一项进度；不动世界）。 */
    private void moveOne() {
        var foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        bot.teleportTo(bot.serverLevel(), foot.getX() + 1.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
