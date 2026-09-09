package com.dddgn.alice.task.mining;

/**
 * 挖掘站位选优的可调参数（D-067 批次 2）。
 *
 * <p>设计依据：`docs/MINING_STAND_SELECTION_DESIGN.md` v7。
 * 这里只放"需要 A/B 切换或现场标定"的开关与阈值，不放业务逻辑。
 */
public final class MiningTuning {

    /** 候选成本估算方案（可切换，默认 S2）。 */
    public enum EstimateMode {
        /** S1：A* 的 octile 下界（最快，精度在有绕行时下降）。 */
        LOWER_BOUND,
        /** S2：一次纯通行 Dijkstra 成本场（现成可站候选零损失，默认）。 */
        DIJKSTRA
    }

    private static volatile EstimateMode estimateMode = EstimateMode.DIJKSTRA;

    /** S2 成本场：最大成本半径（走路格数）与节点上限。 */
    private static volatile double costFieldMaxCost = 24.0D;
    private static volatile int costFieldMaxNodes = 20_000;

    /** top-K 精算：K 初值与上限（精算后若最优成本 > 第 K+1 名估算则 K 递增）。 */
    private static volatile int exactTopK = 4;
    private static volatile int exactTopKMax = 10;

    /** 视线采样：目标面内缩距离（多面体采样点距方块边界）。 */
    private static volatile double losSampleEpsilon = 0.08D;

    /**
     * 触及余量（D-070 修正）：规划期用"假设站位中心眼位"，运行期用真实眼位，
     * 二者可差 ~0.4（EXACT 落点容差 0.3 + 眼高差）。规划期要求
     * `眼位→可见面距离 ≤ reach - margin`，保证运行期复核不会因微小偏移失败（v7 ㉓ 保守化）。
     */
    private static volatile double reachMargin = 0.4D;

    private MiningTuning() {
    }

    public static EstimateMode estimateMode() {
        return estimateMode;
    }

    /** 按名切换估算方案；返回是否命中。 */
    public static boolean setEstimateMode(String name) {
        if ("lower_bound".equalsIgnoreCase(name) || "s1".equalsIgnoreCase(name)) {
            estimateMode = EstimateMode.LOWER_BOUND;
            return true;
        }
        if ("dijkstra".equalsIgnoreCase(name) || "s2".equalsIgnoreCase(name)) {
            estimateMode = EstimateMode.DIJKSTRA;
            return true;
        }
        return false;
    }

    public static double costFieldMaxCost() {
        return costFieldMaxCost;
    }

    public static int costFieldMaxNodes() {
        return costFieldMaxNodes;
    }

    public static int exactTopK() {
        return exactTopK;
    }

    public static int exactTopKMax() {
        return exactTopKMax;
    }

    public static double losSampleEpsilon() {
        return losSampleEpsilon;
    }

    public static double reachMargin() {
        return reachMargin;
    }

    public static String describe() {
        return "estimate=" + estimateMode
                + " costFieldMaxCost=" + costFieldMaxCost
                + " topK=" + exactTopK + "/" + exactTopKMax
                + " losEpsilon=" + losSampleEpsilon
                + " reachMargin=" + reachMargin;
    }
}
