package com.dddgn.alice.transfer;

/** Package-private, inert by default hooks used only by the isolated server fixture. */
final class TransferTestHooks {
    private static Runnable beforeFresh;
    private static Runnable afterActual;

    private TransferTestHooks() {
    }

    static void beforeFresh(Runnable hook) {
        beforeFresh = hook;
    }

    static void afterActual(Runnable hook) {
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
