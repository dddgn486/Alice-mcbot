package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bot 可回收性约束检查（Recoverability Constraints）。
 * 
 * <h3>核心约束</h3>
 * <ul>
 *   <li>❌ 不能挖脚下方块（防止掉落）</li>
 *   <li>❌ 不能跳下2格高（无法返回）</li>
 *   <li>❌ 不能进入液体（Phase 1 完全避开）</li>
 *   <li>❌ 不能进入禁区（保护 Bot）</li>
 * </ul>
 * 
 * <h3>设计原则</h3>
 * <p>
 * 这些约束是为了确保 Bot 的可回收性：Bot 必须能够原路返回，不能"一去不复返"。
 * 所有 Movement 原语都必须遵守这些约束。
 * </p>
 */
public final class MovementConstraints {
    
    private MovementConstraints() {}
    
    /**
     * 检查是否可以挖掘指定方块。
     * 
     * <p>约束：不能挖脚下方块。
     * 
     * @param botFootPos Bot 的脚位
     * @param targetPos 要挖的方块位置
     * @return true = 可以挖，false = 不能挖
     */
    public static boolean canBreak(BlockPos botFootPos, BlockPos targetPos) {
        // ❌ 不能挖脚下
        if (targetPos.equals(botFootPos.below())) {
            return false;
        }
        return true;
    }
    
    /**
     * 检查是否可以下降到目标位置。
     * 
     * <p>约束：不能跳下2格或更多（无法返回）。
     * 
     * @param from 起点
     * @param to 终点
     * @return true = 可以下降，false = 不能下降
     */
    public static boolean canDescend(BlockPos from, BlockPos to) {
        int heightDiff = from.getY() - to.getY();
        // ❌ 不能下降超过1格
        return heightDiff <= 1;
    }
    
    /**
     * 检查位置是否有液体（Phase 1：完全避开液体）。
     * 
     * <p>约束：不能进入液体。
     * 
     * @param level 世界
     * @param pos 位置
     * @return true = 有液体，false = 无液体
     */
    public static boolean hasLiquid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        
        // 检查是否是液体方块
        if (block instanceof LiquidBlock) {
            return true;
        }
        
        // 检查是否有流体状态
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查位置是否是禁区。
     * 
     * <p>约束：不能进入禁区（保护 Bot）。
     * 
     * <p>禁区包括：
     * <ul>
     *   <li>岩浆（已由 hasLiquid 检查）</li>
     *   <li>火（避免燃烧）</li>
     *   <li>虚空（Y < minBuildHeight）</li>
     *   <li>TODO: 玩家标记的禁区（未来实现）</li>
     * </ul>
     * 
     * @param level 世界
     * @param pos 位置
     * @return true = 是禁区，false = 不是禁区
     */
    public static boolean isForbiddenZone(ServerLevel level, BlockPos pos) {
        // 1. 虚空
        if (pos.getY() < level.getMinBuildHeight()) {
            return true;
        }
        
        // 2. 火
        BlockState state = level.getBlockState(pos);
        if (state.is(net.minecraft.world.level.block.Blocks.FIRE)) {
            return true;
        }
        
        // 3. 岩浆块
        if (state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) {
            return true;
        }
        
        // TODO: 玩家标记的禁区（未来从配置或NBT读取）
        
        return false;
    }
    
    /**
     * 检查位置是否安全（综合检查）。
     * 
     * @param level 世界
     * @param pos 位置
     * @return true = 安全，false = 不安全
     */
    public static boolean isSafePosition(ServerLevel level, BlockPos pos) {
        // ❌ 有液体 → 不安全
        if (hasLiquid(level, pos)) {
            return false;
        }
        
        // ❌ 是禁区 → 不安全
        if (isForbiddenZone(level, pos)) {
            return false;
        }
        
        return true;
    }
}
