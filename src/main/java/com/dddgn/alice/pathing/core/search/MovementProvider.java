package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.List;

/** 从当前脚位枚举候选 Movement（架构文档 §6）。不负责任务重试或状态修改。 */
public interface MovementProvider {
    /**
     * 把从 {@code from} 出发的合法候选追加到 {@code out}。
     * 实现必须只读世界、无副作用。
     */
    void appendCandidates(MovementContext context, BlockPos from, List<PlannedMovement> out);
}
