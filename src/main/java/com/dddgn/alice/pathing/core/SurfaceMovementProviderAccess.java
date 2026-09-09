package com.dddgn.alice.pathing.core;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 供工厂/执行器复用的移动谓词（避免工厂反向依赖 search 包的 provider）。 */
public final class SurfaceMovementProviderAccess {
    private static final int[][] CARDINAL = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private SurfaceMovementProviderAccess() {
    }

    /**
     * 落点是否存在逃生路线：相邻四格中有一格的上一层（脚位）可通行且可站，
     * 即从落点能用一个 ASCEND 爬回原层。
     */
    public static boolean hasEscapeFrom(ServerLevel level, BlockPos landing) {
        for (int[] d : CARDINAL) {
            BlockPos step = landing.offset(d[0], 0, d[1]);
            BlockPos up = step.above();
            if (MovementHelper.canWalkThrough(level, up)
                    && MovementHelper.canWalkThrough(level, up.above())
                    && MovementHelper.canWalkOn(level, up)) {
                return true;
            }
        }
        return false;
    }
}
