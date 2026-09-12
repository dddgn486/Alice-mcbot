package com.dddgn.alice.transfer;

/**
 * **传输模块唯一的测试接缝**（生产侧持有，默认完全惰性）。
 *
 * <p>2026-09-13 传输模块彻查（`docs/TRANSFER_MODULE_AUDIT.md` F1）：原先生产原语里直接调用测试钩子、
 * 生产状态机里还挂着夹具短路，读者分不清"生产逻辑"与"测试脚手架"。
 * 现在**测试场景代码整体迁出**（`com.dddgn.alice.fixture.transfer`），生产侧只留这一个
 * **显式、公开、默认惰性**的接缝：不安装钩子时它就是几个 null 判断。
 */
public final class TransferTestHooks {
    private static Runnable beforeFresh;
    private static Runnable afterActual;

    private TransferTestHooks() {
    }

    // ==================== 移动结果短路（原 `TransferTask.fixtureMovementOutcome`） ====================
    /** 夹具可注入的"行走结果"，用于确定性地演练三种失败码；默认 null = 不干预。 */
    private static com.dddgn.alice.task.TransferTask.FixtureMovementOutcome movementOutcome;

    public static void movementOutcome(com.dddgn.alice.task.TransferTask.FixtureMovementOutcome outcome) {
        movementOutcome = outcome;
    }

    /** 取走一次并清空（生产侧每段行走开头调用；默认返回 null ⇒ 零行为）。 */
    public static com.dddgn.alice.task.TransferTask.FixtureMovementOutcome takeMovementOutcome() {
        com.dddgn.alice.task.TransferTask.FixtureMovementOutcome outcome = movementOutcome;
        movementOutcome = null;
        return outcome;
    }

    public static void beforeFresh(Runnable hook) {
        beforeFresh = hook;
    }

    public static void afterActual(Runnable hook) {
        afterActual = hook;
    }

    static void fireBeforeFresh() {
        Runnable hook = beforeFresh;
        beforeFresh = null;
        if (hook != null) hook.run();
    }

    static void fireAfterActual() {
        Runnable hook = afterActual;
        afterActual = null;
        if (hook != null) hook.run();
    }
}
