package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 搭柱子上升（Pillar Movement）。
 * 
 * <h3>适用场景</h3>
 * <ul>
 *   <li>目标在正上方</li>
 *   <li>需要垂直上升</li>
 *   <li>Bot 有方块可以放置</li>
 * </ul>
 * 
 * <h3>执行方式</h3>
 * <ol>
 *   <li>在脚下放置方块</li>
 *   <li>跳跃</li>
 *   <li>重复直到到达目标高度</li>
 * </ol>
 * 
 * <h3>可回收性</h3>
 * <ul>
 *   <li>✅ 不挖脚下（只放方块）</li>
 *   <li>✅ 可以原路返回（向下走）</li>
 * </ul>
 * 
 * <h3>成本</h3>
 * <ul>
 *   <li>高度差 × 5.0 tick/格（放方块 + 跳跃）</li>
 * </ul>
 */
public final class PillarMovement implements Movement {
    
    private static final double PLACE_AND_JUMP_COST = 5.0D;  // 放方块 + 跳跃
    
    private final ServerPlayer bot;
    private final BlockPos from;
    private final BlockPos to;
    private final ServerLevel level;
    private final int heightDiff;
    
    private PillarMovement(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        this.bot = bot;
        this.from = from;
        this.to = to;
        this.level = level;
        this.heightDiff = to.getY() - from.getY();
    }
    
    /**
     * 工厂方法：创建 PillarMovement。
     * 
     * @param bot Bot 玩家
     * @param from 起点
     * @param to 终点
     * @param level 世界
     * @return PillarMovement 实例，如果无效则返回 null
     */
    public static PillarMovement create(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        PillarMovement movement = new PillarMovement(bot, from, to, level);
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
        // 每格高度：放方块 + 跳跃 = 5 tick
        return heightDiff * PLACE_AND_JUMP_COST;
    }
    
    @Override
    public boolean isValid() {
        // 1. 必须是正上方（X 和 Z 相同）
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            return false;
        }
        
        // 2. 必须是向上（高度差 > 0）
        if (heightDiff <= 0) {
            return false;
        }
        
        // 3. 检查目标位置安全性
        if (!MovementConstraints.isSafePosition(level, to)) {
            return false;
        }
        
        if (!MovementConstraints.isSafePosition(level, to.above())) {
            return false;
        }
        
        // 4. 检查目标位置是否可站立
        BlockState below = level.getBlockState(to.below());
        if (!below.isSolidRender(level, to.below())) {
            return false;
        }
        
        // 5. 检查目标位置是否可通行
        if (!canWalkThrough(level, to)) {
            return false;
        }
        
        if (!canWalkThrough(level, to.above())) {
            return false;
        }
        
        // 6. 检查 Bot 是否有方块可以放置
        // TODO: Phase 2B 实现物品检查
        // if (!hasPlaceableBlocks(bot)) {
        //     return false;
        // }
        
        return true;
    }
    
    @Override
    public Status tick(ServerPlayer bot) {
        // 检查是否已到达目标高度
        if (bot.blockPosition().getY() >= to.getY()) {
            return Status.SUCCESS;
        }
        
        // 在脚下放置方块
        BlockPos belowPos = bot.blockPosition().below();
        
        // 检查脚下是否已经有方块
        if (level.getBlockState(belowPos).isAir()) {
            // TODO: 实际放置方块（Phase 2B）
            // 目前先用 setBlock 模拟
            level.setBlock(belowPos, Blocks.DIRT.defaultBlockState(), 3);
        }
        
        // 跳跃上升
        BasicMovement.applyJumpToward(bot, to.getX() + 0.5D, to.getZ() + 0.5D);
        
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
        return String.format("PillarMovement[%s -> %s, height=%d]", 
            from.toShortString(), to.toShortString(), heightDiff);
    }
    
    /**
     * 检查 Bot 是否有可放置的方块。
     * 
     * @param bot Bot 玩家
     * @return true = 有方块，false = 没有方块
     */
    @SuppressWarnings("unused")
    private static boolean hasPlaceableBlocks(ServerPlayer bot) {
        // 检查背包中是否有泥土、圆石等常见方块
        for (ItemStack stack : bot.getInventory().items) {
            if (stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE) || stack.is(Items.STONE)) {
                return true;
            }
        }
        return false;
    }
}
