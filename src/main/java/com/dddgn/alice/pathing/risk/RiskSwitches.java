package com.dddgn.alice.pathing.risk;

/**
 * 风险开关（D-046 / D-059）：**默认全部关闭 = Baritone 原样高风险**。
 *
 * <p>用户 2026-09-09 方向：智能模式 S 以 Baritone 原样的高风险模式为默认；风险评估的产物是
 * "把哪些部分换成低风险"，接口用**一组开关**表达（不做 H/G/S 三态等级枚举）。
 *
 * <p>现阶段只提供开关的读写与一个游戏内命令（{@code /alice risk}）用于 A/B 对比；
 * **评估体系（任务层 / 维度 / 群系结构 / 现实条件 / 局部场景）只做讨论与预留，尚未实现**。
 * 将来的评估器通过 {@link #set} 逐项设置这些开关，不引入额外的抽象层。
 */
public final class RiskSwitches {

    /** DESCEND 过冲红线（D-024）：默认关闭（对齐 Baritone）；低风险模式可打开。 */
    public static final String DESCEND_OVERSHOOT_GUARD = "descend_overshoot";

    private static final java.util.Set<String> KNOWN = java.util.Set.of(DESCEND_OVERSHOOT_GUARD);

    private static volatile boolean descendOvershootGuard = false;

    private RiskSwitches() {
    }

    /** 开关名是否已知。 */
    public static boolean isKnown(String name) {
        return KNOWN.contains(name);
    }

    /** 已知开关名（用于命令提示）。 */
    public static java.util.Set<String> knownSwitches() {
        return KNOWN;
    }

    public static boolean descendOvershootGuard() {
        return descendOvershootGuard;
    }

    /** 按名设置开关；返回是否命中已知开关。 */
    public static boolean set(String name, boolean value) {
        if (!isKnown(name)) {
            return false;
        }
        if (DESCEND_OVERSHOOT_GUARD.equals(name)) {
            descendOvershootGuard = value;
        }
        return true;
    }

    /** 当前开关状态快照（日志/命令用）。 */
    public static String describe() {
        return DESCEND_OVERSHOOT_GUARD + "=" + descendOvershootGuard;
    }
}
