package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 平地行走（Walk Movement）。
 * 
 * <h3>适用场景</h3>
 * <ul>
 *   <li>目标在同一水平面</li>
 *   <li>或目标高1格（自动上台阶）</li>
 *   <li>或目标低1格（下台阶）</li>
 *   <li>路径上无障碍</li>
 * </ul>
 * 
 * <h3>不需要</h3>
 * <ul>
 *   <li>✅ 不挖方块</li>
 *   <li>✅ 不放方块</li>
 *   <li>✅ 纯粹的平地移动</li>
 * </ul>
 * 
 * <h3>成本</h3>
 * <ul>
 *   <li>水平距离 × 1.0 tick/格</li>
 * </ul>
 */
public final class WalkMovement implements Movement {
    
    private final ServerPlayer bot;
    private final BlockPos from;
    private final BlockPos to;
    private final ServerLevel level;
    private final double distance;
    
    private WalkMovement(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        this.bot = bot;
        this.from = from;
        this.to = to;
        this.level = level;
        
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        this.distance = Math.sqrt(dx * dx + dz * dz);
    }
    
    /**
     * 工厂方法：创建 WalkMovement。
     * 
     * @param bot Bot 玩家
     * @param from 起点
     * @param to 终点
     * @param level 世界
     * @return WalkMovement 实例，如果无效则返回 null
     */
    public static WalkMovement create(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        WalkMovement movement = new WalkMovement(bot, from, to, level);
        return movement.isValid() ? movement : null;
    }
    
    @Override
    public BlockPos from() {
        return from;
    }
    
    @Override
    public BlockPos to() {
        return to;
    }
    
    @Override
    public double cost() {
        // 平地行走：距离 / 速度
        return distance / BasicMovement.WALK_SPEED;
    }
    
    @Override
    public boolean isValid() {
        // 1. 检查高度差（只允许 -1, 0, +1）
        int heightDiff = to.getY() - from.getY();
        if (heightDiff < -1 || heightDiff > 1) {
            return false;
        }
        
        // 2. 检查可回收性约束
        if (!MovementConstraints.canDescend(from, to)) {
            return false;  // 不能跳下2格
        }
        
        if (!MovementConstraints.isSafePosition(level, to)) {
            return false;  // 目标位置不安全（液体、禁区）
        }
        
        // 3. 检查目标位置是否可站立
        BlockState below = level.getBlockState(to.below());
        if (!below.isSolidRender(level, to.below())) {
            return false;  // 脚下没有方块
        }
        
        // 4. 检查脚位和头位是否可通行
        if (!canWalkThrough(level, to)) {
            return false;  // 脚位有障碍
        }
        
        if (!canWalkThrough(level, to.above())) {
            return false;  // 头位有障碍
        }
        
        return true;
    }
    
    @Override
    public Status tick(ServerPlayer bot) {
        double goalX = to.getX() + 0.5D;
        double goalZ = to.getZ() + 0.5D;
        
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double remainingDistance = Math.sqrt(dx * dx + dz * dz);
        
        // 到达判定：水平距离 < 0.3
        if (remainingDistance <= 0.3D) {
            return Status.SUCCESS;
        }
        
        // 继续移动
        BasicMovement.applyToward(bot, goalX, goalZ);
        return Status.RUNNING;
    }
    
    /**
     * 检查方块是否可通行（空气或可穿过的方块）。
     */
    private static boolean canWalkThrough(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        
        // 空气、草、花等可穿过
        if (state.isAir() || !state.blocksMotion()) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public String toString() {
        return String.format("WalkMovement[%s -> %s, dist=%.1f]", 
            from.toShortString(), to.toShortString(), distance);
    }
}
