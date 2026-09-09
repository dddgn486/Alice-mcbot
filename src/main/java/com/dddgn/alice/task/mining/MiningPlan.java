package com.dddgn.alice.task.mining;

import com.dddgn.alice.pathing.core.search.PathPlan;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * 单个原始挖掘目标的规划快照：从规划起点前往挖掘站位的方案。
 * 不包含挖掘进度、任务生命周期、目标访问清障或掉落物拾取状态。
 *
 * <p>D-064：路径类型由 legacy `SurfacePathfinder.Result` 换成新内核 {@link PathPlan}（R3/R4）。
 */
public record MiningPlan(
        BlockPos target,
        BlockPos startFoot,
        BlockPos standingFoot,
        PathPlan path,
        LineOfSightChecker.LineOfSightResult visibility,
        Mode mode,
        BlockPos supportPlacementPos
) {
    /** 规划模式（D-067 批次 3）。 */
    public enum Mode {
        /** 当前站位即可挖掘。 */
        CURRENT,
        /** 模式 A：现成可站的多角度站位。 */
        DIRECT,
        /** 模式 B：埋藏/需通道，候选 = 4 面 × {y,y−1} + 正下方，到达允许破坏/放置。 */
        TUNNEL,
        /** 兜底：以目标格为终点、破坏进入（受预算限制）。 */
        ENTER_TARGET
    }

    /** 兼容构造器（批次 2 调用点）：默认 DIRECT、无支撑位。 */
    public MiningPlan(BlockPos target, BlockPos startFoot, BlockPos standingFoot, PathPlan path,
                      LineOfSightChecker.LineOfSightResult visibility) {
        this(target, startFoot, standingFoot, path, visibility, Mode.DIRECT, null);
    }
    public MiningPlan {
        target = Objects.requireNonNull(target, "target").immutable();
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        standingFoot = Objects.requireNonNull(standingFoot, "standingFoot").immutable();
        path = Objects.requireNonNull(path, "path");
        visibility = Objects.requireNonNull(visibility, "visibility");
        mode = mode == null ? Mode.DIRECT : mode;
        supportPlacementPos = supportPlacementPos == null ? null : supportPlacementPos.immutable();
        if (!path.goalFoot().equals(standingFoot)) {
            throw new IllegalArgumentException("MiningPlan path goal must match standingFoot");
        }
    }

    /** 仅表示规划快照满足正常直接挖掘的基础前置条件，不替代运行时验证。 */
    public boolean isExecutable() {
        return path.reached() && visibility.isClear();
    }
}
