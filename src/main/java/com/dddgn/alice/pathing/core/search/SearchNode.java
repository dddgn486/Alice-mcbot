package com.dddgn.alice.pathing.core.search;

/**
 * A* 搜索节点（对照 Baritone `PathNode`）。
 *
 * <p>字段可变以支持 decrease-key；`heapIndex` 由 {@link BinaryHeapOpenSet} 维护。
 */
public final class SearchNode {
    public final int x;
    public final int y;
    public final int z;

    /** 从起点到本节点的已知最小成本。 */
    public double cost = Double.POSITIVE_INFINITY;
    /** 启发式估计（到目标的下界）。 */
    public double estimatedCostToGoal;
    /** cost + estimatedCostToGoal。 */
    public double combinedCost = Double.POSITIVE_INFINITY;
    /** 最优前驱。 */
    public SearchNode previous;
    /** 到达本节点所用 Movement 类型。 */
    public net.minecraft.core.BlockPos previousFoot;
    public com.dddgn.alice.pathing.core.MovementType previousType;
    public double previousCost;

    int heapIndex = -1;
    boolean open;

    SearchNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isOpen() {
        return open;
    }

    public long longHash() {
        return hash(x, y, z);
    }

    public static long hash(int x, int y, int z) {
        long hash = 3241L;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }
}
