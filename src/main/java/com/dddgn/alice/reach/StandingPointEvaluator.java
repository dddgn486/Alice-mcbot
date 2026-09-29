package com.dddgn.alice.reach;

import net.minecraft.core.BlockPos;

/**
 * 站位评分（D-067 批次 2 重写）：**只按"到达站位的路径成本"排序**。
 *
 * <p>设计依据（用户裁定）：
 * <ul>
 *   <li>评分职责收窄为**挖掘效率**；视线是**前提条件**（在 {@link StandingPointSelector} 里过滤），
 *       不参与评分（D-066）；</li>
 *   <li>拾取距离 / 可见面数量 / 安全性单独计分**都不进评分**；</li>
 *   <li>`score` = 精确规划得到的路径成本（含破坏/放置）；`estimate` = 估算成本（仅用于排序）。</li>
 * </ul>
 *
 * <p>⭐ <b>2026-09-29 搬包（改革 ① 主体 · `MiningPlanner` 解体 `DS-5` 的 <u>第一批</u>）</b>：
 * 本类原来住在 `task/mining/`，现在搬进 `reach/`（内核侧几何层，`D-460` 的层定位）。
 * ⚠️ **只搬包、不改名、不改行为** ⇒ 判据只能是**静态门禁**（`tools/check-layer-direction.py` 断言①）
 * ＋ 编译 ＋ `tools/check-all.sh`。
 * ⚠️ **实测事实（写在这里是因为它决定本类下一刀的去向）**：本类的消费者**全在 `MiningPlanner` 内部**
 * （`plans §4.1:168` 逐字：「消费者**全在它内部**」⇒ 它本来就是"要合并删掉"的对象）——
 * 所以本刀**只是把它搬到正确的层**，**不是**把它定成长期形态；`DS-5` 解体真正落地时，
 * 它要么并进触及站位选择器、要么消失（`plans §4.2`①）。
 */
public final class StandingPointEvaluator {

    private StandingPointEvaluator() {
    }

    /** 站位评分记录。 */
    public static final class StandingPointScore {
        private final BlockPos position;
        private final double score;
        private final double estimate;
        private final LineOfSightChecker.LineOfSightResult lineOfSightResult;

        public StandingPointScore(BlockPos position, double score, double estimate,
                                  LineOfSightChecker.LineOfSightResult lineOfSightResult) {
            this.position = position;
            this.score = score;
            this.estimate = estimate;
            this.lineOfSightResult = lineOfSightResult;
        }

        public BlockPos getPosition() {
            return position;
        }

        /** 精确路径成本（含破坏/放置）。 */
        public double getScore() {
            return score;
        }

        /** 估算成本（排序用，可能为 NaN 表示"估算阶段不可达"）。 */
        public double getEstimate() {
            return estimate;
        }

        public LineOfSightChecker.LineOfSightResult getLineOfSightResult() {
            return lineOfSightResult;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "StandingPoint[pos=%s, cost=%.3f, estimate=%s, los=%s]",
                    position.toShortString(), score,
                    Double.isNaN(estimate) ? "-" : String.format(java.util.Locale.ROOT, "%.3f", estimate),
                    lineOfSightResult);
        }
    }

    public static StandingPointScore of(BlockPos position, double cost, double estimate,
                                        LineOfSightChecker.LineOfSightResult los) {
        return new StandingPointScore(position, cost, estimate, los);
    }
}
