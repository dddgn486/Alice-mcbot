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

    public static final String CONTAINER_ACCESS = "container_access";
    /**
     * **危险地带厌恶（D-292，2026-09-17 用户裁定"先加"）**：对应你提的 `hazardTolerance`（容忍度）的
     * **首个布尔形态** —— 容忍度低 ⇔ 厌恶开 = true ✓。
     * 默认 **false = 与今天完全一致**（贴着火/岩浆走不加价 ⇒ 路径选择零变化 ✓）；
     * 打开后：落点**邻接**危险方块（`MovementHelper.avoidWalkingInto` 那一族）的移动会被**加价**
     * ⇒ 规划器**倾向绕开**（不是禁止 ✗ —— 硬禁止仍由既有安全层管 ✓）。消费者：`MovementContext.cost` ✓。
     */
    public static final String HAZARD_AVERSION = "hazard_aversion";

    private static final java.util.Set<String> KNOWN =
            java.util.Set.of(DESCEND_OVERSHOOT_GUARD, CONTAINER_ACCESS, HAZARD_AVERSION);

    /**
     * ⭐ `B2`（2026-09-23）：**可选 vs 底线**的分界 —— 玩家可写面上**只许**出现这里的名字。
     *
     * <p>用户 2026-09-23 裁定取消风险三档 ⇒ 守卫分界改成两类：**可选**（玩家可以调，因为默认值就是
     * 现状、且**只会收紧不会放宽**）与**底线**（见 {@link #BOTTOM_LINES}，**任何玩家可写入口都不许出现**）。
     *
     * <p>每个条目都要写清"为什么它可选"。这张表 + {@link #KNOWN} 是 `tools/risk-surface.py` 的**唯一真源**：
     * 门禁断言 `KNOWN == OPTIONAL.keySet()`（**双向**一致）⇒ 加开关必须同时登记分类，否则构建红。
     */
    public static final java.util.Map<String, String> OPTIONAL = java.util.Map.of(
            DESCEND_OVERSHOOT_GUARD,
            "D-024 过冲红线：默认关（= Baritone 原样），打开只**加**守卫 ⇒ 不会放宽任何安全",
            CONTAINER_ACCESS,
            "D-291 画像字段：默认 true（= 现状允许开箱），关掉只**收紧** ⇒ 不会放宽",
            HAZARD_AVERSION,
            "D-292 危险厌恶：默认 false（= 现状零行为变化），打开只**加价绕行** ⇒ 不会放宽");
    // ⚠️ 新增开关必须同时在这里加一行（写清"为什么它只会收紧"），否则 `tools/risk-surface.py` 红。

    /**
     * ⭐ `B2`：**底线** —— 名字 + "谁在强制它"的指针（只放决策号与门禁路径，不抄原文）。
     *
     * <p>门禁断言三件事：① 这些名字**不出现在**任何玩家可写入口（命令 / 配置面）；
     * ② 与 {@link #OPTIONAL} / {@link #KNOWN} **不相交**；③ **这张表不许为空**（防空集真 —— `Z4` 的教训）。
     */
    public static final java.util.Map<String, String> BOTTOM_LINES = java.util.Map.of(
            "pathing_pure_traversal",
            "D-076 寻路红线（默认纯通行；破坏/放置只由上层显式授权）+ tools/check-authz-registry.sh",
            "server_authoritative_state",
            "AGENTS.md「不可悄悄改变的架构边界」（标注 [未门禁]，复核触发已登记）",
            "unknown_mod_read_only",
            "「未知模组能力默认只读」+ tools/check-machine-map.sh（部分覆盖）");

    /** `B2`：玩家可写的开关名 → 为什么它可选（门禁的唯一真源）。 */
    public static java.util.Map<String, String> optionalSwitches() {
        return OPTIONAL;
    }

    /** `B2`：底线名 → 强制它的机制/指针。 */
    public static java.util.Map<String, String> bottomLines() {
        return BOTTOM_LINES;
    }

    /** `B2`：该名字是不是底线（底线**永不允许**成为玩家可写开关）。 */
    public static boolean isBottomLine(String name) {
        return BOTTOM_LINES.containsKey(name);
    }

    private static volatile boolean descendOvershootGuard = false;
    /**
     * **容器访问策略（D-291，2026-09-17 用户裁定加入首批画像字段）**：默认 **true = 允许开箱**
     * （= 现状行为，零行为变化 ✓）。关掉它 ⇒ `MenuSession.open` 以
     * `profile_denies_container` 拒绝并留日志 ✓（消费点在 `MenuSession`，门禁 S6-P1 已把该文件纳入监督 ✓）。
     */
    private static volatile boolean containerAccess = true;
    private static volatile boolean hazardAversion = false;

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

    /** 容器访问策略（见字段注释）。 */
    public static boolean containerAccess() {
        return containerAccess;
    }

    /** 危险地带厌恶（见字段注释；消费者必须读冻结画像 ✗ 不许直读这里）。 */
    public static boolean hazardAversion() {
        return hazardAversion;
    }

    /** 按名设置开关；返回是否命中已知开关。 */
    public static boolean set(String name, boolean value) {
        if (!isKnown(name)) {
            return false;
        }
        if (DESCEND_OVERSHOOT_GUARD.equals(name)) {
            descendOvershootGuard = value;
        } else if (CONTAINER_ACCESS.equals(name)) {
            containerAccess = value;
        } else if (HAZARD_AVERSION.equals(name)) {
            hazardAversion = value;
        }
        return true;
    }

    /** 当前开关状态快照（日志/命令用）。 */
    public static String describe() {
        return DESCEND_OVERSHOOT_GUARD + "=" + descendOvershootGuard
                + " " + CONTAINER_ACCESS + "=" + containerAccess
                + " " + HAZARD_AVERSION + "=" + hazardAversion;
    }
}
