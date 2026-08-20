package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;

/**
 * A* 搜索节点(移植自 Baritone 的 PathNode 设计)。
 */
public final class PathNode {

    final BlockPos pos;
    double cost;
    double combinedCost;
    PathNode previous;
    int heapIndex = -1;
    int moves;
    int turns;
    int directionX;
    int directionZ;

    PathNode(BlockPos pos) {
        this.pos = pos;
        this.moves = 0;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof PathNode node && node.pos.equals(this.pos));
    }

    @Override
    public int hashCode() {
        return this.pos.hashCode();
    }
}
