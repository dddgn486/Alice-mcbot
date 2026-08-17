package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 移动判定(移植自 Baritone MovementHelper 的核心判定逻辑,服务端直读版)。
 */
public final class MovementHelper {

    private MovementHelper() {
    }

    /** 该脚位能否站立:下方方块实心可站且非危险。 */
    public static boolean canWalkOn(ServerLevel level, BlockPos footPos) {
        BlockState below = level.getBlockState(footPos.below());
        if (below.isAir() || below.getFluidState().isSource() || avoidWalkingInto(below)) {
            return false;
        }
        return below.getCollisionShape(level, footPos.below()).isEmpty() == false;
    }

    /** 该格能否穿过(身体格):空气或可穿过方块,且非危险。 */
    public static boolean canWalkThrough(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (avoidWalkingInto(state)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** 危险方块(走入即受伤/致命):熔岩/火/岩浆块等。 */
    public static boolean avoidWalkingInto(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.MAGMA_BLOCK);
    }

    /** 平地移动(从 from 脚位水平走到 to 脚位)。 */
    public static boolean canTraverse(ServerLevel level, BlockPos from, BlockPos to) {
        // 目标脚位可站,目标身体格与头格可穿过
        return canWalkOn(level, to)
                && canWalkThrough(level, to)
                && canWalkThrough(level, to.above());
    }

    /** 上一阶台阶(从 from 脚位走到 to=from+1y 脚位)。 */
    public static boolean canAscend(ServerLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY() + 1) {
            return false;
        }
        BlockPos mid = new BlockPos(to.getX(), from.getY(), to.getZ());
        // 中继格可穿过(脚位与头位),目标可站,目标头位空
        return canWalkThrough(level, mid)
                && canWalkThrough(level, mid.above())
                && canWalkOn(level, to)
                && canWalkThrough(level, to.above());
    }

    /** 下一格台阶(从 from 脚位走到 to=from-1y 脚位)。 */
    public static boolean canDescend(ServerLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY() - 1) {
            return false;
        }
        BlockPos mid = new BlockPos(to.getX(), from.getY(), to.getZ());
        // 从当前格能走到中继格(身体可穿过),目标可站
        return canWalkThrough(level, mid)
                && canWalkThrough(level, mid.above())
                && canWalkOn(level, to);
    }

    /** 移动代价(加权:上台阶贵、下台阶略便宜)。 */
    public static double cost(MovementType type) {
        return switch (type) {
            case TRAVERSE -> 1.0D;
            case DESCEND -> 1.0D;
            case ASCEND -> 2.0D;
            case DOWNWARD -> 3.0D;
        };
    }
}
