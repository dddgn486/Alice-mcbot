package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.risk.RiskProfile;
import com.dddgn.alice.pathing.risk.RiskSwitches;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **S-6 判据（2026-09-17，用户裁定「先做按 bot 冻结的容器」）**：风险开关**按 bot 冻结**。
 *
 * <p>要证明的语义（而不是"代码看起来对"）：
 * ① 冻结之后，**全局开关被改**不影响该 bot 的画像（同一任务内口径不变）；
 * ② **重新冻结**（= 任务指派时的冻结点）才拿到新值；
 * ③ 命令改开关会重新冻结所有在跑的 bot ⇒ **A/B 对比这个既有用途不退化**
 *    （本夹具直接调用与命令同一入口 `RiskProfile.freezeAll(...)` 来验证这条）。
 *
 * <p>反向对照：把 `RiskProfile.of(...)` 改成每次都读全局 `RiskSwitches` ⇒ ① 必红。
 */
public class RiskProfileCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private boolean done;

    public RiskProfileCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "RiskProfileCheck";
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
        String name = RiskSwitches.DESCEND_OVERSHOOT_GUARD;
        boolean saved = RiskSwitches.descendOvershootGuard();
        try {
            // 前提：默认关（D-059），否则本用例无法区分"冻结"与"本来就开"
            check("前提：该开关默认关（= Baritone 原样）", !saved);

            RiskProfile.reset();
            RiskSwitches.set(name, false);
            RiskProfile.freeze(bot);
            check("冻结后画像 = 全局值（false）", !RiskProfile.of(bot).descendOvershootGuard());

            // ① 全局改开 ⇒ 已冻结的画像**不许**跟着变
            RiskSwitches.set(name, true);
            boolean afterGlobalChange = RiskProfile.of(bot).descendOvershootGuard();
            check("① 全局开关改成 true 后，**已冻结**画像仍是 false（任务内口径不变）", !afterGlobalChange);
            check("对照：全局值确实是 true（证明上一条不是「开关没改成功」）",
                    RiskSwitches.descendOvershootGuard());

            // ② 重新冻结（= 冻结点）才拿到新值
            RiskProfile.freeze(bot);
            check("② 重新冻结后画像跟上新值（true）", RiskProfile.of(bot).descendOvershootGuard());

            // ③ 命令入口用的 freezeAll：所有在跑的 bot 一起重新冻结（A/B 立刻生效）
            RiskProfile.reset();
            RiskProfile.freeze(bot);
            RiskProfile.freezeAll(java.util.List.of(bot));
            check("③ freezeAll 之后画像 = 当前全局值（命令改开关后 A/B 立刻生效）",
                    RiskProfile.of(bot).descendOvershootGuard());
            check("③ 冻结计数可见（>=1）", RiskProfile.frozenCount() >= 1);

            BotLog.info("[RiskProfile] 观测：frozen={} global={} frozenCount={}",
                    RiskProfile.of(bot).descendOvershootGuard(), RiskSwitches.descendOvershootGuard(),
                    RiskProfile.frozenCount());
        } finally {
            // 夹具纪律：把全局开关与冻结画像都复位（不然会影响后面的电池步骤）
            RiskSwitches.set(name, saved);
            RiskProfile.reset();
        }
        check("收尾：全局开关已复位", RiskSwitches.descendOvershootGuard() == saved);
        check("收尾：冻结画像已清空", RiskProfile.frozenCount() == 0);
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[RiskProfile] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 风险画像冻结自检 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }
}
