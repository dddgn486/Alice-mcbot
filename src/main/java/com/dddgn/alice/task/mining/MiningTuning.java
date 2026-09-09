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

    public static String describe() {
        return "estimate=" + estimateMode
                + " costFieldMaxCost=" + costFieldMaxCost
                + " topK=" + exactTopK + "/" + exactTopKMax
                + " losEpsilon=" + losSampleEpsilon;
    }
}
