package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;

/**
 * 每 tick 运动轨迹记录（D-049）：用于与 Baritone 对照测试"连续行动 / 顿挫"。
 *
 * <p>输出格式与测试数据包 `alice_test:trace_tick` **完全一致**，便于直接对比两条曲线：
 * <pre>[TRACE] &lt;bot&gt; t=&lt;tick&gt; x=&lt;毫格&gt; y=&lt;毫格&gt; z=&lt;毫格&gt; vx=&lt;毫格/tick&gt; vy=.. vz=.. og=&lt;0|1&gt; yaw=&lt;百分度&gt;</pre>
 *
 * <p>开关：`/alice trace`（再次执行关闭）。只在开启时输出，不产生常驻日志。
 */
public final class BotTrace {
    private static boolean enabled;
    private static int ticks;

    private BotTrace() {
    }

    public static boolean toggle() {
        enabled = !enabled;
        ticks = 0;
        BotLog.info("[TRACE] recording={}", enabled);
        return enabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 采样上限：到点自动关闭，避免忘记关开关时刷满日志。 */
    private static final int MAX_TICKS = 600;

    /** 由 BotSession 每 tick 调用（服务端主线程）。 */
    public static void tick(BotPlayer bot) {
        if (!enabled) {
            return;
        }
        ticks++;
        if (ticks > MAX_TICKS) {
            enabled = false;
            BotLog.info("[TRACE] recording=false reason=budget_exhausted ticks={}", ticks);
            return;
        }
        BotLog.info("[TRACE] {} t={} x={} y={} z={} vx={} vy={} vz={} og={} yaw={}",
                bot.getName().getString(), ticks,
                Math.round(bot.getX() * 1000.0D), Math.round(bot.getY() * 1000.0D),
                Math.round(bot.getZ() * 1000.0D),
                Math.round(bot.getDeltaMovement().x * 1000.0D),
                Math.round(bot.getDeltaMovement().y * 1000.0D),
                Math.round(bot.getDeltaMovement().z * 1000.0D),
                bot.onGround() ? 1 : 0,
                Math.round(bot.getYRot() * 100.0F));
    }
}
