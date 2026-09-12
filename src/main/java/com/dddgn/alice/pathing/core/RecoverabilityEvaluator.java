package com.dddgn.alice.pathing.core;

import java.util.ArrayList;
import java.util.List;

/**
 * **可回收性评估器**（基-1 / P0-B）：把"这条 Movement 执行后，规划期**已验证**了什么回程保证"
 * 变成可计算、可审计的结论。
 *
 * <p><b>语义</b>（先定义清楚，避免又造一个含义模糊的枚举）：
 * <ul>
 *   <li>{@code LOCAL_STEP}：只保证"这一步本身没把 bot 永久困死"，**没有回程保证**；</li>
 *   <li>{@code PATH_REVERSIBLE}：规划期**已验证**存在回程手段（几何未变 ⇒ 反步可用，或落点已过
 *       PILLAR 返回守卫）；</li>
 *   <li>{@code SAFE_EXIT_REQUIRED} / {@code EMERGENCY_EXIT_REQUIRED}：本步不提供回程，需要"出口"级别的
 *       额外保证才允许 —— **本阶段暂无产出者**（接线阶段再定谁产出，不许先写死）。</li>
 * </ul>
 *
 * <p><b>纪律（P0-B 的处置顺序）</b>：本类只负责"算出真实等级"，**不改变准入结果** ——
 * `MovementCapabilities.requiredRecoverabilityLevel` 仍维持现状；把 required 提上去是**下一步**
 * （顺序反了会 `LOCAL_STEP < required` 直接抛异常崩规划器）。
 *
 * <p><b>依据为什么这样取</b>（每条都对应一个真实的规划期验证，不猜）：
 * <table>
 *   <tr><th>Movement</th><th>等级</th><th>依据</th></tr>
 *   <tr><td>TRAVERSE / DIAGONAL</td><td>PATH_REVERSIBLE</td><td>几何未变（不写世界）⇒ 反步同型可用</td></tr>
 *   <tr><td>ASCEND</td><td>PATH_REVERSIBLE</td><td>反程 = DESCEND 1 格，起点格刚刚站过 ⇒ 可用且 1 格落差安全</td></tr>
 *   <tr><td>DESCEND</td><td>PATH_REVERSIBLE</td><td>反程 = ASCEND 1 格，起点头位刚刚占用过 ⇒ 净空成立</td></tr>
 *   <tr><td>PLACE_STEP_AND_TRAVERSE</td><td>PATH_REVERSIBLE</td><td>放置只**增加**支撑 ⇒ 反步（走回/上 1 格）仍成立</td></tr>
 *   <tr><td>FALL</td><td>PATH_REVERSIBLE</td><td>provider 只在 `fallRecoverable`（PILLAR 返回守卫）通过时产出 FALL 边</td></tr>
 *   <tr><td>PILLAR</td><td>LOCAL_STEP</td><td>上升本身合法，但**回程需要"拆掉自己放的方块"的授权与预算**，规划期没验证 ⇒ 不给回程保证</td></tr>
 *   <tr><td>BREAK_AND_TRAVERSE / BREAK_AND_ENTER / DOWNWARD</td><td>LOCAL_STEP</td><td>破坏了地形且**不恢复**（账本只保证我方放置，不保证地形复原）</td></tr>
 * </table>
 */
public final class RecoverabilityEvaluator {

    /** 依据标签：几何未变（同型反步可用）。 */
    public static final String BASIS_GEOMETRY_UNCHANGED       = "geometry_unchanged_reverse_step";
    /** 依据标签：几何未变（反程为下落 1 格）。 */
    public static final String BASIS_REVERSE_DESCEND          = "geometry_unchanged_reverse_descend";
    /** 依据标签：几何未变（反程为上升 1 格）。 */
    public static final String BASIS_REVERSE_ASCEND           = "geometry_unchanged_reverse_ascend";
    /** 依据标签：放置只增加支撑。 */
    public static final String BASIS_PLACEMENT_ONLY           = "placement_only_supports_reverse";
    /** 依据标签：落点过了 PILLAR 返回守卫（D-024 补记 / D-058）。 */
    public static final String BASIS_FALL_GUARD               = "pillar_return_guard_passed";
    /** 依据标签：**该边没有携带"返回守卫已验证"的事实** ⇒ 保守不给回程保证。 */
    public static final String BASIS_FALL_UNVERIFIED          = "fall_return_unverified";
    /** 依据标签：上升的回程需要拆自己放置的方块（授权/预算未在规划期验证）。 */
    public static final String BASIS_ASCENT_REVERSE_UNVERIFIED = "ascent_reverse_break_unverified";
    /** 依据标签：改动了地形且不恢复。 */
    public static final String BASIS_WORLD_MODIFIED           = "world_modified_not_restored";

    private RecoverabilityEvaluator() {
    }

    /**
     * 规则表（**逐边**）：Movement 类型 + 该边已验证的事实 → 评估结果。**唯一裁决点**。
     *
     * <p>FALL 是唯一的**条件性**类型：只有 {@link RecoverabilityFacts#fallReturnVerified()} 为真
     * （即 provider 的 `fallRecoverable` 放过它）才给 `PATH_REVERSIBLE`；**缺事实一律保守降级**
     * 为 `LOCAL_STEP`，于是策略表（`RecoverabilityPolicy`）能真的把这种边拒掉。
     */
    public static RecoverabilityAssessment evaluate(MovementType type, RecoverabilityFacts facts) {
        if (type == MovementType.FALL && !facts.fallReturnVerified()) {
            return new RecoverabilityAssessment(RecoverabilityLevel.LOCAL_STEP, BASIS_FALL_UNVERIFIED);
        }
        return switch (type) {
            case TRAVERSE, DIAGONAL ->
                    new RecoverabilityAssessment(RecoverabilityLevel.PATH_REVERSIBLE,
                            BASIS_GEOMETRY_UNCHANGED);
            case ASCEND -> new RecoverabilityAssessment(RecoverabilityLevel.PATH_REVERSIBLE,
                    BASIS_REVERSE_DESCEND);
            case DESCEND -> new RecoverabilityAssessment(RecoverabilityLevel.PATH_REVERSIBLE,
                    BASIS_REVERSE_ASCEND);
            case PLACE_STEP_AND_TRAVERSE -> new RecoverabilityAssessment(
                    RecoverabilityLevel.PATH_REVERSIBLE, BASIS_PLACEMENT_ONLY);
            case FALL -> new RecoverabilityAssessment(RecoverabilityLevel.PATH_REVERSIBLE,
                    BASIS_FALL_GUARD);
            case PILLAR -> new RecoverabilityAssessment(RecoverabilityLevel.LOCAL_STEP,
                    BASIS_ASCENT_REVERSE_UNVERIFIED);
            case BREAK_AND_TRAVERSE, BREAK_AND_ENTER, DOWNWARD ->
                    new RecoverabilityAssessment(RecoverabilityLevel.LOCAL_STEP,
                            BASIS_WORLD_MODIFIED);
        };
    }

    /** 无事实版本：等价于"什么都没验"（FALL 会保守降级）。 */
    public static RecoverabilityAssessment evaluate(MovementType type) {
        return evaluate(type, RecoverabilityFacts.NONE);
    }

    public static RecoverabilityLevel levelOf(MovementType type) {
        return evaluate(type).level();
    }

    public static RecoverabilityLevel levelOf(MovementType type, RecoverabilityFacts facts) {
        return evaluate(type, facts).level();
    }

    /**
     * 规则表快照（自检与报告用：断言"不是常量"）。
     *
     * <p>FALL 打两行（有事实 / 无事实）—— 这正是"逐边事实"与"类型假设"的区别所在。
     */
    public static List<String> table() {
        List<String> rows = new ArrayList<>();
        for (MovementType type : MovementType.values()) {
            rows.add(type + "=" + evaluate(type, RecoverabilityFacts.NONE).describe());
        }
        rows.add("FALL+fall_return_verified="
                + evaluate(MovementType.FALL, RecoverabilityFacts.FALL_RETURN_VERIFIED).describe());
        return List.copyOf(rows);
    }
}
