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

    /**
     * **几何判据用的是"当前任务的目标"**（D-268⑤）：生产代码里 `goalDistance` 取
     * `BotManager.currentTaskTargetPos(bot)` —— 在电池里那是**包装任务**（`RegressionBattery`）的目标，
     * 不是夹具自己声明的。夹具若用自己的坐标系去"走近"，就会和生产判据**各说各话**
     * （2026-09-17 实测踩过：指纹恒为 `d0` ⇒ 假红）。所以这里一律读**运行时**目标。
     */

    private enum Phase { SETUP, STALL_FIRST, CHECK_FIRST, REARM, STALL_SECOND, CHECK_SECOND,
                         WANDER, CHECK_WANDER, DONE }

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
                // **D-268⑤：先自定位** —— 本夹具的判据是几何量（距目标多远），
                // 所以绝不能依赖"上一步把 bot 留在哪"（CORE 序列里每步的落点都不同）。
                if (phaseTicks == 1) {
                    // 清空背包 ⇒ 下面 REARM 的发料**必然**改变背包指纹（否则"快捷栏已有同种物品"会让
                    // `ensureHotbarStack` 空操作 ⇒ CORE 序列里假红，2026-09-17 实测踩过）
                    com.dddgn.alice.item.FixtureToolKit.resetInventory(bot);
                    return Status.RUNNING;
                }
                if (phaseTicks == 2) {
                    // **前提**：几何进展判据要求"当前任务有方块目标"（没有就无从谈"更近"）
                    var goal = com.dddgn.alice.bot.BotManager.currentTaskTargetPos(bot);
                    check("前提：当前任务必须有方块目标（否则几何进展判据无意义）", goal != null);
                    if (goal != null) {
                        // ⚠️ **必须离开目标**：若让 bot 正好站在目标上，"远离目标"方向无从谈起
                        // （`dx==dz==0` ⇒ 挪不动），WANDER 用例会**空转**变成假绿 —— 2026-09-17 实测踩过。
                        teleportFoot(goal.west(2));
                    }
                    BotLog.info("[NoProgress] 夹具前提：任务目标={} 起点={} 距目标={} 格",
                            String.valueOf(goal), bot.blockPosition().toShortString(),
                            goal == null ? -1 : (int) Math.ceil(Math.sqrt(bot.blockPosition().distSqr(goal))));
                    return Status.RUNNING;
                }
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
                // 制造**真进度**（D-268⑤ 起「进度」= 目标被推进，不是脚位）：
                // **走近一步** ⇒ 单调"最近距离"变小 ⇒ 指纹变化 ⇒ 重新武装。
                // ⚠️ 上一版用"改背包"制造进度，在 CORE 序列里**会被前序步骤污染**
                // （快捷栏里可能已有同种物品 ⇒ 夹具空操作 ⇒ 夹具红）⇒ 改成几何量，状态无关。
                if (phaseTicks == 1) {
                    // **真进度**（D-268⑤：进度 = 目标推进 / 产出，不是脚位）：走夹具唯一写入点发一件
                    com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                            () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                            stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), 1, "no-progress-rearm");
                    return Status.RUNNING;
                }
                // ⚠️ 余量给足：`EventThresholds` 的复评**不是每 tick 都重算指纹**
                // （10 tick 的余量会让"已重新武装"的检查跑在指纹重算之前 ⇒ 假红，2026-09-17 实测）。
                // 30 < WINDOW(40)：新 episode 还没到再次上闩的时间，所以这里判的是"闩被解开了"。
                if (phaseTicks < 30) {
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
                advance(Phase.WANDER);
                return Status.RUNNING;
            }
            case WANDER -> {
                // **D-268⑤ 判别用例（2026-09-17）**：脚位一直在变、但**什么都没推进**
                // （本夹具的目标就是 bot 自己 ⇒ 距离恒为 d0；任务/背包也不变）
                // ⇒ 旧指纹（含脚位）会把它当「有进度」而**漏报**；新指纹必须报。
                if (phaseTicks == 1) {
                    EventThresholds.resetNoProgressTracking(bot);
                }
                if (phaseTicks % 5 == 0 && phaseTicks <= WINDOW) {
                    stepAwayFromGoal();   // 物理上在动，但**离目标更远** ⇒ 不算推进
                }
                if (phaseTicks < WINDOW + MARGIN) {
                    return Status.RUNNING;
                }
                advance(Phase.CHECK_WANDER);
                return Status.RUNNING;
            }
            case CHECK_WANDER -> {
                check("原地绕圈（脚位在变、目标没推进）也必须报 NO_PROGRESS",
                        EventThresholds.noProgressEmits(bot) == 3);
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

    /**
     * **白忙一场**：朝**远离**当前任务目标的方向挪一格 —— 脚位变了，
     * 但"本 episode 内的最近距离"**不可能**变小（方向按运行时目标算，不写死）。
     */
    private void stepAwayFromGoal() {
        var foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        var goal = com.dddgn.alice.bot.BotManager.currentTaskTargetPos(bot);
        if (goal == null) {
            teleportFoot(foot.offset(0, 0, 1));   // 没有目标 ⇒ 任选一个方向（前提检查已在 SETUP 报过）
            return;
        }
        int dx = Integer.compare(foot.getX(), goal.getX());   // 远离：与"朝目标"反号
        int dz = dx == 0 ? Integer.compare(foot.getZ(), goal.getZ()) : 0;
        if (dx == 0 && dz == 0) {
            // ⚠️ **站在目标上时也必须动**（否则整个用例空转成假绿 —— 2026-09-17 实测踩过两次）：
            // 从目标出发往 +X 走，距离只会变大 ⇒ 在"有没有更近"这条判据下必定不算推进。
            dx = 1;
        }
        teleportFoot(foot.offset(dx, 0, dz));
    }

    private void teleportFoot(net.minecraft.core.BlockPos to) {
        bot.teleportTo(bot.serverLevel(), to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
