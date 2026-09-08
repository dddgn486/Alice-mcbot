package com.dddgn.alice.pathing.movement;

import com.dddgn.alice.action.BlockBreakSession;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖前方障碍并前进（Break and Walk Movement）。
 * 
 * <h3>适用场景</h3>
 * <ul>
 *   <li>目标在前方（同一高度或高1格）</li>
 *   <li>路径上有1-2个方块阻挡</li>
 *   <li>挖掉障碍后可以通行</li>
 * </ul>
 * 
 * <h3>执行方式</h3>
 * <ol>
 *   <li>挖掉脚位的障碍方块</li>
 *   <li>挖掉头位的障碍方块（如果有）</li>
 *   <li>前进到目标位置</li>
 * </ol>
 * 
 * <h3>可回收性</h3>
 * <ul>
 *   <li>✅ 不挖脚下方块（只挖前方）</li>
 *   <li>✅ 可以原路返回（挖掉的通道可以走回来）</li>
 * </ul>
 * 
 * <h3>成本</h3>
 * <ul>
 *   <li>挖掘成本 + 行走成本</li>
 *   <li>泥土：2 tick，石头：20 tick</li>
 * </ul>
 */
public final class BreakAndWalkMovement implements Movement {
    
    private final ServerPlayer bot;
    private final BlockPos from;
    private final BlockPos to;
    private final ServerLevel level;
    
    private final BlockPos obstacleFootPos;   // 脚位障碍
    private final BlockPos obstacleHeadPos;   // 头位障碍（可能为 null）
    
    private final double distance;
    private final double breakCost;
    
    private boolean footCleared = false;
    private boolean headCleared = false;
    /** Baritone 式破坏会话（每 tick 推进进度），替代瞬间销毁。 */
    private BlockBreakSession footSession;
    private BlockBreakSession headSession;
    
    private BreakAndWalkMovement(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        this.bot = bot;
        this.from = from;
        this.to = to;
        this.level = level;
        
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        this.distance = Math.sqrt(dx * dx + dz * dz);
        
        // 检查脚位障碍
        if (!canWalkThrough(level, to)) {
            this.obstacleFootPos = to;
        } else {
            this.obstacleFootPos = null;
        }
        
        // 检查头位障碍
        if (!canWalkThrough(level, to.above())) {
            this.obstacleHeadPos = to.above();
        } else {
            this.obstacleHeadPos = null;
        }
        
        // 计算挖掘成本
        double cost = 0.0D;
        if (obstacleFootPos != null) {
            cost += getBreakCost(level, obstacleFootPos);
        }
        if (obstacleHeadPos != null) {
            cost += getBreakCost(level, obstacleHeadPos);
        }
        this.breakCost = cost;
    }
    
    /**
     * 工厂方法：创建 BreakAndWalkMovement。
     * 
     * @param bot Bot 玩家
     * @param from 起点
     * @param to 终点
     * @param level 世界
     * @return BreakAndWalkMovement 实例，如果无效则返回 null
     */
    public static BreakAndWalkMovement create(ServerPlayer bot, BlockPos from, BlockPos to, ServerLevel level) {
        BreakAndWalkMovement movement = new BreakAndWalkMovement(bot, from, to, level);
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
        // 挖掘成本 + 行走成本
        return breakCost + (distance / BasicMovement.WALK_SPEED);
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
            return false;
        }
        
        if (!MovementConstraints.isSafePosition(level, to)) {
            return false;
        }
        
        // 3. 检查目标位置是否可站立
        BlockState below = level.getBlockState(to.below());
        if (!below.isSolidRender(level, to.below())) {
            return false;
        }
        
        // 4. 检查是否违反"不挖脚下"约束
        if (obstacleFootPos != null) {
            if (!MovementConstraints.canBreak(bot.blockPosition(), obstacleFootPos)) {
                return false;
            }
        }
        
        if (obstacleHeadPos != null) {
            if (!MovementConstraints.canBreak(bot.blockPosition(), obstacleHeadPos)) {
                return false;
            }
        }
        
        // 5. 必须至少有一个障碍需要挖（否则用 WalkMovement）
        if (obstacleFootPos == null && obstacleHeadPos == null) {
            return false;
        }
        
        // 6. 检查挖掘成本是否合理（不挖基岩）
        if (breakCost == Double.POSITIVE_INFINITY) {
            return false;
        }
        
        return true;
    }
    
    @Override
    public Status tick(ServerPlayer bot) {
        // Step 1: 挖掉脚位障碍（Baritone 式进度破坏，替代原先的瞬间 destroyBlock）
        if (obstacleFootPos != null && !footCleared) {
            if (!level.getBlockState(obstacleFootPos).isAir()) {
                if (footSession == null) {
                    footSession = BlockInteraction.beginBreak(bot, level, obstacleFootPos);
                }
                BlockBreakSession.Status status = footSession.tick();
                if (status == BlockBreakSession.Status.FAILED) {
                    BotLog.warn("break_and_walk 破坏脚位障碍失败: pos={} code={}",
                            obstacleFootPos.toShortString(), footSession.failureCode());
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            footCleared = true;
        }
        
        // Step 2: 挖掉头位障碍
        if (obstacleHeadPos != null && !headCleared) {
            if (!level.getBlockState(obstacleHeadPos).isAir()) {
                if (headSession == null) {
                    headSession = BlockInteraction.beginBreak(bot, level, obstacleHeadPos);
                }
                BlockBreakSession.Status status = headSession.tick();
                if (status == BlockBreakSession.Status.FAILED) {
                    BotLog.warn("break_and_walk 破坏头位障碍失败: pos={} code={}",
                            obstacleHeadPos.toShortString(), headSession.failureCode());
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            headCleared = true;
        }
        
        // Step 3: 障碍已清除，前进
        double goalX = to.getX() + 0.5D;
        double goalZ = to.getZ() + 0.5D;
        
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double remainingDistance = Math.sqrt(dx * dx + dz * dz);
        
        // 到达判定
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
        return state.isAir() || !state.blocksMotion();
    }
    
    @Override
    public String toString() {
        return String.format("BreakAndWalkMovement[%s -> %s, obstacles=%d]", 
            from.toShortString(), to.toShortString(), 
            (obstacleFootPos != null ? 1 : 0) + (obstacleHeadPos != null ? 1 : 0));
    }
    
    /**
     * 获取挖掘方块的时间成本。
     * 
     * @param level 世界
     * @param pos 方块位置
     * @return 挖掘时间（tick）
     */
    private static double getBreakCost(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        
        // 简化的硬度模型（Phase 1）
        float hardness = state.getDestroySpeed(level, pos);
        
        if (hardness < 0.0F) {
            // 不可破坏（基岩等）
            return Double.POSITIVE_INFINITY;
        }
        
        if (hardness <= 0.6F) {
            // 软方块（泥土、沙子等）
            return 2.0D;
        } else if (hardness <= 2.0F) {
            // 中等硬度（木头、圆石等）
            return 10.0D;
        } else {
            // 硬方块（石头、矿石等）
            return 20.0D;
        }
    }
}
