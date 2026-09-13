package com.dddgn.alice.task.mining;

/**
 * 挖掘站位选优的可调参数（D-067 批次 2）。
 *
 * <p>设计依据：`docs/MINING_STAND_SELECTION_DESIGN.md` v7。
 * 这里只放"需要 A/B 切换或现场标定"的开关与阈值，不放业务逻辑。
 */
public final class MiningTuning {

    /**
     * **加高（gain）的水平前提（D-179，2026-09-13 实测事故后补的守卫）**。
     *
     * <p>加高的语义是"**已经站在目标旁边但够不到** ⇒ 往上搭一格/两格更接近它"（D-115/D-116）。
     * 它**只能**改善"够不够得着"，**不能**把 bot 送到远处的目标那里去。
     *
     * <p>缺了这条守卫会怎样（实测）：`RegionLumberJob` 被起在 bot **已被传送离开作业区**之后
     * （转移夹具把 bot 放到 z≈406，而作业区是 z203..231）⇒ 规划器给 `no_reachable_standing_point`
     * ⇒ 任务选了**加高**兜底 ⇒ `[MineTask] gain_start target=33,64,208 from=45,64,406 to=45,65,406`
     * ⇒ 在转移场景里一路搭了 **12 格圆石**（随后又拆回）。写入位置与目标**相距 198 格、毫无因果关系**，
     * 却因为"自己拆自己"骗过了建拆同权检查。
     *
     * <p>判据取**水平曼哈顿距离 ≤ ceil(触及)**：实测合法加高全部 ≤2 格（`28,70,208←28,64,208`、
     * `21,66,207←23,64,207`、`20,67,208←22,68,207`），而漂移事故是 198 格 ⇒ 两类分离干净。
     * 竖直方向不设限（目标在下方也可能需要加高后往下够），交给既有规则决定。
     */
    public static boolean gainHorizontallyReachable(net.minecraft.server.level.ServerPlayer bot,
                                                    net.minecraft.core.BlockPos goal) {
        double horizontal = Math.abs(goal.getX() + 0.5D - bot.getX())
                + Math.abs(goal.getZ() + 0.5D - bot.getZ());
        return horizontal <= Math.ceil(bot.getBlockReach());
    }

    /** 候选成本估算方案（可切换，默认 S2）。 */
    public enum EstimateMode {
        /** S1：A* 的 octile 下界（最快，精度在有绕行时下降）。 */
        LOWER_BOUND,
        /** S2：一次纯通行 Dijkstra 成本场（现成可站候选零损失，默认）。 */
        DIJKSTRA
    }

    private static volatile EstimateMode estimateMode = EstimateMode.DIJKSTRA;

    /**
     * 连锁挖掘策略（D-077，默认 {@link #OFF} = 原版单格）。
     *
     * <ul>
     *   <li>{@code OFF}：永不用模组连锁，生产路径与接入前完全一致；</li>
     *   <li>{@code AUTO}：模组在场且目标属**矿石/原木**时才连锁（玩家手动启用后的默认档）；</li>
     *   <li>{@code FORCE}：模组在场即连锁（仅测试/诊断用，绕过白名单）。</li>
     * </ul>
     */
    public enum ChainMode {
        OFF, AUTO, FORCE
    }

    private static volatile ChainMode chainMode = ChainMode.OFF;

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

    public static ChainMode chainMode() {
        return chainMode;
    }

    /** 按名切换连锁策略；返回是否命中。 */
    public static boolean setChainMode(String name) {
        if (name == null) {
            return false;
        }
        for (ChainMode mode : ChainMode.values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                chainMode = mode;
                return true;
            }
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
                + " chain=" + chainMode
                + " costFieldMaxCost=" + costFieldMaxCost
                + " topK=" + exactTopK + "/" + exactTopKMax
                + " losEpsilon=" + losSampleEpsilon
                + " reachMargin=" + reachMargin;
    }
}
