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
        BlockPos belowPos = footPos.below();
        BlockState below = level.getBlockState(belowPos);
        if (below.isAir() || below.getFluidState().isSource() || avoidWalkingInto(below)) {
            return false;
        }
        // 脚下是无碰撞层(草/花/薄雪等可穿过方块):看再下一层是否有支撑
        // (真实玩家站在草丛里,脚位高度还是那层空气格)
        if (below.getCollisionShape(level, belowPos).isEmpty()) {
            BlockState below2 = level.getBlockState(belowPos.below());
            return !below2.isAir()
                    && !below2.getCollisionShape(level, belowPos.below()).isEmpty()
                    && !below2.getFluidState().isSource()
                    && !avoidWalkingInto(below2);
        }
        return !below.getCollisionShape(level, belowPos).isEmpty();
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

    /**
     * 实体是否真正站在当前碰撞形状支撑顶面上。支持下半砖、台阶等非整格顶面；
     * 不用 blockPosition 的整数 Y 假装实体脚底高度。
     */
    public static boolean isStandingOnSupport(ServerLevel level, net.minecraft.world.entity.Entity entity) {
        final double epsilon = 0.08D;
        double footY = entity.getY();
        int supportY = net.minecraft.util.Mth.floor(footY - epsilon);
        BlockPos supportPos = BlockPos.containing(entity.getX(), supportY, entity.getZ());
        BlockState support = level.getBlockState(supportPos);
        if (support.getFluidState().isSource() || avoidWalkingInto(support)) {
            return false;
        }
        net.minecraft.world.phys.shapes.VoxelShape shape = support.getCollisionShape(level, supportPos);
        if (shape.isEmpty()) {
            return false;
        }
        double topY = supportPos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        return Math.abs(footY - topY) <= epsilon;
    }

    /** 实体是否稳定落在指定的逻辑脚位段，兼容下半砖和台阶的非整格支撑顶面。 */
    public static boolean isStandingAtFootPos(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                              BlockPos footPos) {
        final double epsilon = 0.08D;
        if (net.minecraft.util.Mth.floor(entity.getX()) != footPos.getX()
                || net.minecraft.util.Mth.floor(entity.getZ()) != footPos.getZ()) {
            return false;
        }
        BlockPos supportPos = footPos.below();
        BlockState support = level.getBlockState(supportPos);
        if (support.getFluidState().isSource() || avoidWalkingInto(support)) {
            return false;
        }
        net.minecraft.world.phys.shapes.VoxelShape shape = support.getCollisionShape(level, supportPos);
        if (shape.isEmpty()) {
            return false;
        }
        double topY = supportPos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        return Math.abs(entity.getY() - topY) <= epsilon;
    }

    /** 平地移动(从 from 脚位水平走到 to 脚位)。 */
    public static boolean canTraverse(ServerLevel level, BlockPos from, BlockPos to) {
        // 目标脚位可站,目标身体格与头格可穿过
        return canWalkOn(level, to)
                && canWalkThrough(level, to)
                && canWalkThrough(level, to.above());
    }

    /** 上一阶台阶(从 from 脚位走到 to=from+1y 脚位)。
     * <p>跳跃语义(真实玩家跳高约 1.25 格):
     * <ul>
     *   <li>轨迹下层 mid 允许「1 格高的实心方块」(台阶/围墙可跳越,不再视为墙);</li>
     *   <li>正上方垂直爬升(mid==from)允许目标格悬空——跳跃弧线过渡,
     *       PathExecutor 为硬移动(无重力),bot 不会在过渡格掉落。</li>
     * </ul></p>
     */
    public static boolean canAscend(ServerLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY() + 1) {
            return false;
        }
        BlockPos mid = new BlockPos(to.getX(), from.getY(), to.getZ());
        if (!canJumpThrough(level, mid)) {
            return false;
        }
        if (!canWalkThrough(level, to) || !canWalkThrough(level, to.above())) {
            return false;
        }
        if (mid.equals(from)) {
            // 正上方垂直爬升:目标格悬空也允许(跳跃过渡;硬移动不掉落)
            return true;
        }
        return canWalkOn(level, to);
    }

    /** 跳跃轨迹下层判定:空气/可穿过,或 1 格高的实心方块(跳跃高度可越过)。 */
    private static boolean canJumpThrough(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
            return true;
        }
        if (avoidWalkingInto(state)) {
            return false;
        }
        return state.getCollisionShape(level, pos).max(net.minecraft.core.Direction.Axis.Y) <= 1.0D;
    }

    /** 下一格台阶(从 from 脚位走到 to=from-1y 脚位)。 */
    public static boolean canDescend(ServerLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY() - 1) {
            return false;
        }
        BlockPos mid = new BlockPos(to.getX(), from.getY(), to.getZ());
        // 从当前格能走到中继格(身体可穿过),目标格本身可穿过 + 可站
        return canWalkThrough(level, mid)
                && canWalkThrough(level, mid.above())
                && canWalkThrough(level, to)
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
