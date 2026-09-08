package com.dddgn.alice.task.mining;

import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.SurfacePathfinder;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * 单个原始挖掘目标的规划快照：从规划起点前往挖掘站位的方案。
 * 不包含挖掘进度、任务生命周期、目标访问清障或掉落物拾取状态。
 */
public record MiningPlan(
        BlockPos target,
        BlockPos startFoot,
        BlockPos standingFoot,
        SurfacePathfinder.Result path,
        LineOfSightChecker.LineOfSightResult visibility
) {
    public MiningPlan {
        target = Objects.requireNonNull(target, "target").immutable();
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        standingFoot = Objects.requireNonNull(standingFoot, "standingFoot").immutable();
        path = Objects.requireNonNull(path, "path");
        visibility = Objects.requireNonNull(visibility, "visibility");
        if (!path.goal().equals(standingFoot)) {
            throw new IllegalArgumentException("MiningPlan path goal must match standingFoot");
        }
    }

    /** 仅表示规划快照满足正常直接挖掘的基础前置条件，不替代运行时验证。 */
    public boolean isExecutable() {
        return path.status() == AStarPathfinder.SearchStatus.REACHED && visibility.isClear();
    }
}
