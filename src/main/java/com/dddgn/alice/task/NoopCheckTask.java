package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;

import java.util.function.BooleanSupplier;

/**
 * **平凡自检任务**：几 tick 后检查一个**布尔前提** —— 成立则 `DONE` ✓，不成立则 `FAILED` 并给出可读理由 ✗。
 *
 * <p>用途：`harness_self` 模块的第三步 —— 前两步故意被外部命令打断（如实 FAIL ✓），
 * 本步断言"**打断留下的任务已经被清理干净**"（`BotManager.currentTaskSummary(bot) == null` ✓）：
 * 它跑得完且前提成立 ⇒ **证明编排器活过了打断、并把现场收拾干净了** ✓✓
 * （旧电池在这种情形下整轮 `no_verdict` ✗）。
 *
 * <p>⚠️ 纪律（门禁 R1/R2 教我的）：**夹具必须有失败路径** ✗ —— 只能返回 `DONE` 的夹具会把内部失败
 * 吞成静默绿 ✗（本类因此要求一个 `BooleanSupplier` 前提 ✓，而不是无条件通过 ✓）。
 */
public class NoopCheckTask implements Task {

    private final BotPlayer bot;
    private final String label;
    private final BooleanSupplier premise;
    private int ticks;
    private String failed = "";

    public NoopCheckTask(BotPlayer bot, String label) {
        this(bot, label, () -> true);
    }

    public NoopCheckTask(BotPlayer bot, String label, BooleanSupplier premise) {
        this.bot = bot;
        this.label = label;
        this.premise = premise;
    }

    @Override
    public String taskName() {
        return label;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failed;
    }

    @Override
    public String terminalReason() {
        return failed.isEmpty() ? "done" : "failed:" + failed;
    }

    @Override
    public Status tick() {
        if (++ticks < 3) {
            return Status.RUNNING;
        }
        if (!premise.getAsBoolean()) {
            failed = label + "_premise_failed";
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
