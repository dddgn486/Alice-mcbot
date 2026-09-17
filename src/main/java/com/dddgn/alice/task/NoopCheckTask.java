package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;

/**
 * **平凡自检任务**：几 tick 后如实 `DONE` ✓（没有断言，**它通过本身就说明编排器把控制权交回了它** ✓）。
 *
 * <p>用途：`harness_self` 模块的第三步 —— 前两步故意被外部命令打断（如实 FAIL ✓），
 * 本步能跑完并 PASS ⇒ **证明编排器活过了打断** ✓✓（旧电池在这种情形下整轮 `no_verdict` ✗）。
 */
public class NoopCheckTask implements Task {

    private final BotPlayer bot;
    private final String label;
    private int ticks;

    public NoopCheckTask(BotPlayer bot, String label) {
        this.bot = bot;
        this.label = label;
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
        return "";
    }

    @Override
    public String terminalReason() {
        return "done";
    }

    @Override
    public Status tick() {
        return ++ticks >= 3 ? Status.DONE : Status.RUNNING;
    }
}
