package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 移动判定(移植自 Baritone MovementHelper 的核心判定逻辑,服务端直读版)。
 */
public final class MovementHelper {

    private MovementHelper() {
    }

    /**
     * 该脚位能否站立（混合判定，D-041）。
     *
     * <p>对照 Baritone {@code canWalkOnBlockState:387-426}（整格白名单）与
     * {@code isBlockNormalCube:755-771}（{@code Block.isShapeFullBlock}）：
     * <ol>
     *   <li>整格碰撞形状 → 可站（含模组整格方块），但**蜂蜜块除外**（Baritone:389 显式排除）；</li>
     *   <li>否则按"已知有碰撞但不能站"的薄/半高面排除（栅栏/栅栏门/墙/铁栏杆与玻璃板/门/滴水石/
     *       紫水晶簇/末地棒/避雷针/可可/花盆）；</li>
     *   <li>其余（台阶/半砖/箱子/模组半格方块…）**保持可站**——这是 Alice 对模组方块的兼容选择，
     *       与 Baritone 的严格白名单不同，已在 D-041 登记。</li>
     * </ol>
     * <p>脚下是无碰撞层（草/花/薄雪等）时，看再下一层是否有支撑。
     */
    public static boolean canWalkOn(ServerLevel level, BlockPos footPos) {
        BlockPos belowPos = footPos.below();
        BlockState below = level.getBlockState(belowPos);
        if (below.isAir() || below.getFluidState().isSource() || avoidWalkingInto(below)) {
            return false;
        }
        if (!below.getCollisionShape(level, belowPos).isEmpty()) {
            return isStandableSupport(level, belowPos, below);
        }
        // 脚下是无碰撞层(草/花/薄雪等可穿过方块):看再下一层是否有支撑
        // (真实玩家站在草丛里,脚位高度还是那层空气格)
        BlockPos below2Pos = belowPos.below();
        BlockState below2 = level.getBlockState(below2Pos);
        if (below2.isAir() || below2.getFluidState().isSource() || avoidWalkingInto(below2)) {
            return false;
        }
        return !below2.getCollisionShape(level, below2Pos).isEmpty()
                && isStandableSupport(level, below2Pos, below2);
    }

    /** 支撑面是否可站：整格形状优先，蜂蜜块与薄/半高面排除（见 {@link #canWalkOn}）。 */
    private static boolean isStandableSupport(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block != Blocks.HONEY_BLOCK
                && Block.isShapeFullBlock(state.getCollisionShape(level, pos))) {
            return true;
        }
        return !isKnownNonStandable(block);
    }

    /**
     * 已知"有碰撞但不能站"的方块（对照 Baritone canWalkOn 白名单的反面：碰撞面太薄或形状不完整）。
     * <p>注：活板门（TrapDoorBlock）**不在**此表——关闭的活板门是真实可站地面，Alice 保留该能力
     * （与 Baritone 的严格白名单不同，已在 D-041 登记）。
     */
    private static boolean isKnownNonStandable(Block block) {
        return block instanceof FenceBlock
                || block instanceof FenceGateBlock
                || block instanceof WallBlock
                || block instanceof IronBarsBlock
                || block instanceof DoorBlock
                || block instanceof HoneyBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock
                || block instanceof EndRodBlock
                || block instanceof LightningRodBlock
                || block instanceof CocoaBlock
                || block instanceof FlowerPotBlock;
    }

    /** 可攀爬方块（对照 Baritone isClimbable:573-580）：梯子/藤蔓/缠怨藤/垂泪藤。 */
    public static boolean isClimbable(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LadderBlock
                || block instanceof VineBlock
                || block instanceof WeepingVinesBlock
                || block instanceof TwistingVinesBlock;
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

    /**
     * 危险方块(走入即受伤/致命或无法安全穿过)。
     * <p>对照 Baritone {@code MovementHelper.avoidWalkingInto:350-360}：熔岩、任意火(含灵魂火/营火)、
     * 岩浆块、仙人掌、甜浆果丛、末地传送门、蛛网、气泡柱。
     * <p>注意：Baritone 在此还会拒绝"任意流体"，但 Alice 的水面通行语义不同（见 D-025/D-036 差异登记），
     * 流体只在破坏路径上拒绝（{@code BlockBreakSafety}）。
     */
    public static boolean avoidWalkingInto(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
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
        double topY = supportTopY(level, supportPos);
        return !Double.isNaN(topY) && Math.abs(footY - topY) <= epsilon;
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
        double topY = supportTopY(level, supportPos);
        return !Double.isNaN(topY) && Math.abs(entity.getY() - topY) <= epsilon;
    }

    /**
     * R2-C 统一完成契约：脚位列正确 + 支撑稳定 + 已落地 + 水平距目标中心不超过 maxHorizontal。
     * <p>四个执行器共用，避免各自为政（D-026）。onGround 是必须项：否则残余动量会在
     * “判定成功”之后继续把 bot 带离目标列。
     */
    public static boolean isSettledAtFootPos(ServerLevel level, net.minecraft.world.entity.Entity entity,
                                             BlockPos footPos, double maxHorizontal) {
        if (!isStandingAtFootPos(level, entity, footPos)) {
            return false;
        }
        if (!entity.onGround()) {
            return false;
        }
        double dx = entity.getX() - (footPos.getX() + 0.5D);
        double dz = entity.getZ() - (footPos.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz) <= maxHorizontal;
    }

    /**
     * COLUMN 容差判定（D-027）：脚位所在格正确 + **已落地**。
     *
     * <p>`onGround` 是 D-026 统一完成契约的必须项：起跳/下落途中 `blockPosition()` 已经等于目标格，
     * 若不带 `onGround`，执行器会在空中提前宣布成功（实测 PILLAR 4 tick 假成功 → bot 落回原点 →
     * 下一段 STALE；ASCEND 同类）。
     */
    public static boolean isAtFootColumn(net.minecraft.world.entity.Entity entity, BlockPos footPos) {
        return entity.onGround()
                && entity.blockPosition().equals(footPos)
                && entity.getY() - footPos.getY() < 0.5D;
    }

    /** 支撑碰撞形状的世界坐标顶面；空形状返回 NaN。 */
    public static double supportTopY(ServerLevel level, BlockPos supportPos) {
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(supportPos)
                .getCollisionShape(level, supportPos);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        return supportPos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    /** 平地移动(从 from 脚位水平走到 to 脚位)。 */
    public static boolean canTraverse(ServerLevel level, BlockPos from, BlockPos to) {
        // 目标脚位可站,目标身体格与头格可穿过
        if (!canWalkOn(level, to)
                || !canWalkThrough(level, to)
                || !canWalkThrough(level, to.above())) {
            return false;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            // 对角线不能擦过任一侧方块；同时检查角落交叉格，避免两方块夹角被当成可走。
            BlockPos sideX = new BlockPos(from.getX() + dx, from.getY(), from.getZ());
            BlockPos sideZ = new BlockPos(from.getX(), from.getY(), from.getZ() + dz);
            if (!canWalkThrough(level, sideX) || !canWalkThrough(level, sideX.above())
                    || !canWalkThrough(level, sideZ) || !canWalkThrough(level, sideZ.above())) {
                return false;
            }
        }
        return canSweepPlayer(level, from, to);
    }

    /**
     * 按玩家尺寸检查脚位中心从 from 到 to 的连续扫掠空间。
     * 支撑方块只在脚底边界接触，不会被当作身体碰撞；侧向实体空间则必须完全无碰撞。
     */
    public static boolean canSweepPlayer(ServerLevel level, BlockPos from, BlockPos to) {
        final double halfWidth = 0.3D;
        final double height = 1.8D;
        double minX = Math.min(from.getX() + 0.5D, to.getX() + 0.5D) - halfWidth;
        double maxX = Math.max(from.getX() + 0.5D, to.getX() + 0.5D) + halfWidth;
        double minY = Math.min(from.getY(), to.getY());
        double maxY = Math.max(from.getY(), to.getY()) + height;
        double minZ = Math.min(from.getZ() + 0.5D, to.getZ() + 0.5D) - halfWidth;
        double maxZ = Math.max(from.getZ() + 0.5D, to.getZ() + 0.5D) + halfWidth;
        AABB sweep = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        int startX = (int) Math.floor(minX);
        int endX = (int) Math.floor(Math.nextDown(maxX));
        int startY = (int) Math.floor(minY);
        int endY = (int) Math.floor(Math.nextDown(maxY));
        int startZ = (int) Math.floor(minZ);
        int endZ = (int) Math.floor(Math.nextDown(maxZ));
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    // 起点/终点脚下支撑属于脚底接触，不是身体扫掠阻塞。
                    if (pos.equals(from.below()) || pos.equals(to.below())) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (avoidWalkingInto(state)) {
                        return false;
                    }
                    net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
                    if (!shape.isEmpty() && shape.bounds().move(x, y, z).intersects(sweep)) {
                        double top = y + shape.max(net.minecraft.core.Direction.Axis.Y);
                        if (top > minY + 1.0E-6D) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
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
        // 从当前格能走到中继格(身体可穿过),目标格本身可穿过 + 可站，并检查下降过程的实体扫掠空间。
        return canWalkThrough(level, mid)
                && canWalkThrough(level, mid.above())
                && canWalkThrough(level, to)
                && canWalkOn(level, to)
                && canSweepPlayer(level, from, to);
    }

    /**
     * 当前受限曲面模型的固定代价：水平移动（含对角）=1，上阶=2，侧向下阶=1，原地向下=3。
     * 对角同价使曼哈顿启发式会高估，因此 AStarPathfinder 使用零启发式 Dijkstra 顺序保证最优。
     */
    public static double cost(MovementType type) {
        return switch (type) {
            case TRAVERSE -> 1.0D;
            case DESCEND -> 1.0D;
            case ASCEND -> 2.0D;
            case DOWNWARD -> 3.0D;
        };
    }
}
