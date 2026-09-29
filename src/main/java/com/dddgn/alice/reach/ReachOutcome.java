package com.dddgn.alice.reach;

import java.util.Objects;

/**
 * 一条**到达腿**的结论：“怎么走到能挖到目标的那一格” —— 要么拿到一个可用计划
 * （{@link #plan()} 非空），要么只有一条失败理由。
 *
 * <p>⭐ <b>2026-09-29 `1-3`（批次 1 改革 ① 主体 · 甲「成员级退役」）</b>：
 * 它替掉 {@code reach/ReachOutcome}（`①-2a` 从 `MiningPlanner` 搬出来的那个三组件 record）。
 * 三处改动，逐条给理由：
 * <ol>
 *   <li><b>退役 `score` 组件</b>（施工设计单 `§4e` 甲，2026-09-29 用户裁定）：那个 record 的四个字段
 *       里三个是冗余的 —— `position` == {@link MiningPlan#standingFoot()}、
 *       `lineOfSightResult` == 计划里那份规划期视线（本刀一并退役，见 ②）、
 *       `estimate` **全仓无读者**、`score` == 计划**自己**的成本
 *       ⇒ “评分”不再需要**载体**：它就是计划的一个**导出量**
 *       （{@link MiningPlan#totalCost()}，唯一出处）。</li>
 *   <li><b>退役规划期视线（LOS）</b>（同一裁定）：它的**唯一**消费者是 `task/MineTask` 的一行
 *       **遥测**日志，而执行期 `action/MineBlockRunner` **自己在运行期**复核视线
 *       （`LINE_OF_SIGHT_BLOCKED` / `OUT_OF_REACH`，可重试）⇒ 规划期那一份的
 *       **行为承重 = 零** ⇒ 从 {@link MiningPlan} 的组件表里去掉
 *       （⚠️ 代价如实记：真机取证时少一个 `visibility=` 读数）。</li>
 *   <li><b>名字里不再有 `Standing`</b>：它描述的是**任何一条到达腿**的结论 ——
 *       A 腿（现成站位直接到达）与目标腿（同列 / 侧面两次搜索）都走它，所以旧名把
 *       “目标级到达”那条腿也说成了“站位”。⚠️ 与 {@link MiningPlan} 的命名**暂时不一致**
 *       （后者改名 `ReachPlan` 是 `1-5`）—— `§3c` 建议同刀做，⛔ **未获裁**，本刀不做。</li>
 * </ol>
 *
 * <p>⚠️ <b>三条逐字保留的契约</b>（改革不许碰）：
 * <ol>
 *   <li>{@link #failureReason()} **成功时是 `""`**（⛔ **不是** `null`）：调用方按 {@code isEmpty()}
 *       判“这条腿到底有没有给出理由”（`P1-d` / `1.4w` 的**原样上抛**纪律 —— 腿给了理由就不许被
 *       上游改写成总括码）；</li>
 *   <li>{@link #success()} 的判据仍然是 {@code plan != null}（⛔ 别改成“判理由空不空”：
 *       成功时它是空串，而失败路径里也能是空串 ⇒ 那个判据会把失败读成成功）；</li>
 *   <li>组件顺序仍然是 **计划在前、理由在后**（`plan()` 是本类型的核心语义）。</li>
 * </ol>
 *
 * @param plan          选出来的计划；{@code null} = 这条腿没产生可用计划（见 {@code failureReason}）
 * @param failureReason 失败归因串；**成功时为 `""`**（见上面契约 1）
 */
public record ReachOutcome(MiningPlan plan, String failureReason) {

    public ReachOutcome {
        Objects.requireNonNull(failureReason, "failureReason");
    }

    /** 这条腿给出可用计划了吗（⛔ 判据是 `plan != null`，见类注释契约 2）。 */
    public boolean success() {
        return plan != null;
    }
}
