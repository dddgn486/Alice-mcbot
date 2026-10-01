package com.dddgn.alice.write;

import java.util.Objects;

/**
 * 世界修改的**归因**（`D-082`；2026-10-01 `D-566` 刀 4 由 `WriteGrant` 改名）：**谁**、**为什么**要改世界。
 *
 * <p>⭐ **为什么叫归因**（方案草案 `§22.3` 的实测）：它回答的是「**这次修改是谁要的、为什么**」——
 * `ledger/WorldModLedger.java:27` 逐字：「**授权归属（谁、为什么）仍由 `action/WriteAudit` 负责**，
 * 两者用 `scopeId` 关联」。⛔ 它**不是裁决**（"允不允许"是 `region/authz/` 的事），
 * ⛔ **也不搬家**：它是**全维度共用**的（每个写入调用点都要带）⇒ 用户 2026-10-01 裁定
 * 「**留在 `write/`，改形**」。
 *
 * <p>这是 Alice 唯一的世界写入授权凭证。任何破坏或放置都必须携带一个 {@code Attribution}：
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
 * <p>⚠️ **组件名 `requester` 暂留**（⛔ 这是**登记**，不是漏改）：`§22.3` 给的形状是
 * `(String source, …)`，但 `requester()` 这个名字**今天同时活在三个类型上**
 * （本记录 · `PathRequest.requester()` · 语音 `Utterance.requester()`）＋ 一个 NBT 键
 * （`transfer/TransferLedgerData` 的 `"requester"`）＋ 一个夹具判据 id（`requester_registry`）
 * ＋ 一个日志码（`unregistered_requester`）⇒ ⭐ 只改其中一个 = **同一个词在同一仓里多出一个义**
 * （正是本项目反复吃亏的那种病）⇒ 留给「这三处 `requester` 一起改名」的独立一刀
 * （复核触发：真去改 `PathRequest` 时）。
 *
 * @param requester 谁授权：任务/Job/子系统的稳定身份（如 {@code lumber}、{@code mine}、{@code pathing}）
 * @param reason    为什么：结构化理由，决定安全策略与归因
 */
public record Attribution(String requester, WriteReason reason) {

    /** 未声明身份时的占位（**不应出现在正式路径**：它在日志里就是一条待修的缺口）。 */
    public static final String UNKNOWN = "unknown";

    public Attribution {
        requester = (requester == null || requester.isBlank()) ? UNKNOWN : requester;
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static Attribution of(String requester, WriteReason reason) {
        return new Attribution(requester, reason);
    }

    /**
     * 同一声明者、换一个理由（如挖掘任务给自己放支撑块）。
     * 保留 requester 保证归因不丢。
     */
    public Attribution with(WriteReason other) {
        return new Attribution(requester, other);
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
