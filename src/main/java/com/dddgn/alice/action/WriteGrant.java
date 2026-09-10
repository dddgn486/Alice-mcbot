package com.dddgn.alice.action;

import java.util.Objects;

/**
 * 世界写入授权（D-082）：**谁**、**为什么**要改世界。
 *
 * <p>这是 Alice 唯一的世界写入授权凭证。任何破坏或放置都必须携带一个 {@code WriteGrant}：
 * <ul>
 *   <li>寻路内核：授权体现在 {@code PathRequest} 的 MovementType 集合（默认纯通行，无破坏/放置原语）；</li>
 *   <li>动作层：授权体现为本对象，由上层任务/Job 在**调用点显式构造**。</li>
 * </ul>
 *
 * <p>**范围必须窄**：授权指向"这一格/这一次"，不是任务级开关。任务级布尔开关会把许可泄漏到
 * 该任务的所有子请求（去下一棵树的走位、收集掉落物的走位），并让预算无法归因。
 *
 * <p>**不含预算**：预算是独立的既有概念（{@code MiningBudget} / {@code SearchBudget}），
 * 本轮不合并——见 {@code docs/AI_DECISIONS.md} D-082 的"不改变"条款。
 *
 * @param requester 谁授权：任务/Job/子系统的稳定身份（如 {@code lumber}、{@code mine}、{@code pathing}）
 * @param reason    为什么：结构化理由，决定安全策略与归因
 */
public record WriteGrant(String requester, WriteReason reason) {

    /** 未声明身份时的占位（**不应出现在正式路径**：它在日志里就是一条待修的缺口）。 */
    public static final String UNKNOWN = "unknown";

    public WriteGrant {
        requester = (requester == null || requester.isBlank()) ? UNKNOWN : requester;
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static WriteGrant of(String requester, WriteReason reason) {
        return new WriteGrant(requester, reason);
    }

    /**
     * 同一声明者、换一个理由（如挖掘任务给自己放支撑块）。
     * 保留 requester 保证归因不丢。
     */
    public WriteGrant with(WriteReason other) {
        return new WriteGrant(requester, other);
    }

    /** 归因串：`lumber:LINE_OF_SIGHT`。 */
    public String describe() {
        return requester + ":" + reason.name();
    }

    @Override
    public String toString() {
        return describe();
    }
}
