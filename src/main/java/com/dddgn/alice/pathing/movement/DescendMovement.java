package com.dddgn.alice.pathing.movement;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 搭台阶下降（Descend Movement）。
 * 
 * <h3>适用场景</h3>
 * <ul>
 *   <li>目标在前方且低1格</li>
 *   <li>不能直接跳下（违反约束）</li>
 *   <li>需要搭台阶缓慢下降</li>
 * </ul>
 * 
 * <h3>执行方式</h3>
 * <ol>
 *   <li>在目标位置上方放置方块（形成台阶）</li>
 *   <li>走到台阶上</li>
 *   <li>下降到目标位置</li>
 * </ol>
 * 
 * <h3>可回收性</h3>
 * <ul>
 *   <li>✅ 不跳下2格（搭台阶只下降1格）</li>
 *   <li>✅ 可以原路返回（台阶可以爬回来）</li>
 * </ul>
 * 
 * <h3>成本</h3>
 * <ul>
 *   <li>放置台阶 + 行走 = 7 tick</li>
 * </ul>
 * 
 * <h3>注意</h3>
 * <p>
 * 这个原语只处理下降1格的情况。如果需要下降多格，需要连续使用多个 DescendMovement。
 * </p>
 */
public final class DescendMovement implements Movement {
    
    private static final double PLACE_AND_WALK_COST = 7.0D;  // 放方块 + 行走
    
    private final ServerPlayer bot;
    private final BlockPos from;
    private final BlockPos to;
    private final ServerLevel level;
    private final BlockPos stepPos;  // 台阶位置（to 上方）
    
    private boolean stepPlaced = false;
    
    private DescendMovement(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        this.bot = bot;
        this.from = from;
        this.to = to;
        this.level = level;
        this.stepPos = to.above();  // 台阶在目标上方
    }
    
    /**
     * 工厂方法：创建 DescendMovement。
     * 
     * @param bot Bot 玩家
     * @param from 起点
     * @param to 终点
     * @param level 世界
     * @return DescendMovement 实例，如果无效则返回 null
     */
    public static DescendMovement create(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        DescendMovement movement = new DescendMovement(bot, from, to, level);
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
        return PLACE_AND_WALK_COST;
    }
    
    @Override
    public boolean isValid() {
        // 1. 必须是下降1格
        int heightDiff = from.getY() - to.getY();
        if (heightDiff != 1) {
            return false;
        }
        
        // 2. 检查目标位置安全性
        if (!MovementConstraints.isSafePosition(level, to)) {
            return false;
        }
        
        if (!MovementConstraints.isSafePosition(level, to.above())) {
            return false;
        }
        
        // 3. 检查目标位置是否可站立
        BlockState below = level.getBlockState(to.below());
        if (!below.isSolidRender(level, to.below())) {
            return false;
        }
        
        // 4. 检查目标位置是否可通行
        if (!canWalkThrough(level, to)) {
            return false;
        }
        
        // 5. 检查台阶位置
        if (!level.getBlockState(stepPos).isAir()) {
            // 如果台阶位置已经有方块，检查是否可通行
            if (!canWalkThrough(level, stepPos)) {
                return false;
            }
        }
        
        // 6. 检查台阶上方是否可通行（头部空间）
        if (!canWalkThrough(level, stepPos.above())) {
            return false;
        }
        
        return true;
    }
    
    @Override
    public Status tick(ServerPlayer bot) {
        // Step 1: 放置台阶（如果还没放）
        if (!stepPlaced) {
            if (level.getBlockState(stepPos).isAir()) {
                // TODO: 使用正确的放置方法（带物品消耗）
                // 目前先用 setBlock 模拟（Phase 1）
                level.setBlock(stepPos, Blocks.DIRT.defaultBlockState(), 3);
            }
            stepPlaced = true;
        }
        
        // Step 2: 移动到目标
        double goalX = to.getX() + 0.5D;
        double goalZ = to.getZ() + 0.5D;
        
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double remainingDistance = Math.sqrt(dx * dx + dz * dz);
        
        // 到达判定
        if (remainingDistance <= 0.3D && Math.abs(bot.getY() - to.getY()) < 0.5D) {
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
        return state.isAir() || !state.blocksMotion();
    }
    
    @Override
    public String toString() {
        return String.format("DescendMovement[%s -> %s, down=1]", 
            from.toShortString(), to.toShortString());
    }
}
