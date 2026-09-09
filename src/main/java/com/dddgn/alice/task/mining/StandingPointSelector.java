package com.dddgn.alice.task.mining;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 站位选择器 - 为挖掘任务选择最优站位。
 * 
 * <h3>选择策略</h3>
 * <ol>
 *   <li>生成候选站位（目标周围 3x3x3 区域）</li>
 *   <li>过滤不可站立的位置</li>
 *   <li>评估视线质量、距离、高度</li>
 *   <li>返回最优站位</li>
 * </ol>
 * 
 * <h3>候选站位规则</h3>
 * <ul>
 *   <li>必须是可站立的安全位置</li>
 *   <li>必须在触及范围内（通常 4.5 格）</li>
 *   <li>优先选择视线清晰的位置</li>
 * </ul>
 */
public final class StandingPointSelector {
    
    private StandingPointSelector() {}
    
    /** Bot 眼睛高度（从脚底到眼睛） */
    private static final double BOT_EYE_HEIGHT = 1.62;
    
    /** 最大触及距离 */
    private static final double MAX_REACH = 4.5;
    
    /**
     * 为目标方块选择最优站位。
     * 
     * @param level 世界
     * @param target 目标方块
     * @param currentPos Bot 当前位置（可选，用于优先考虑不需要移动的站位）
     * @return 最优站位，如果没有合适的站位则返回 null
     */
    public static BlockPos selectStandingPoint(ServerLevel level, BlockPos target, BlockPos currentPos) {
        // 1. 生成候选站位
        List<BlockPos> candidates = generateCandidates(level, target, currentPos);
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // 2. 评估并选择最优站位
        return StandingPointEvaluator.selectBest(level, candidates, target, BOT_EYE_HEIGHT, MAX_REACH);
    }
    
    /**
     * 选择最优站位并返回详细评分结果。
     * 
     * @param level 世界
     * @param target 目标方块
     * @param currentPos Bot 当前位置
     * @return 所有候选站位的评分结果（按分数降序）
     */
    public static List<StandingPointEvaluator.StandingPointScore> selectWithDetails(
            ServerLevel level, BlockPos target, BlockPos currentPos) {
        List<BlockPos> candidates = generateCandidates(level, target, currentPos);
        
        if (candidates.isEmpty()) {
            return List.of();
        }
        
        return StandingPointEvaluator.evaluateAndSort(level, candidates, target, BOT_EYE_HEIGHT, MAX_REACH);
    }
    
    /**
     * 检查 Bot 当前站位是否已经是最优的。
     * 
     * @param level 世界
     * @param target 目标方块
     * @param currentPos Bot 当前位置
     * @return true 如果当前站位已经足够好
     */
    public static boolean isCurrentPositionGoodEnough(ServerLevel level, BlockPos target, BlockPos currentPos) {
        // 检查当前位置是否可站立
        if (!isValidStandingPoint(level, currentPos, target)) {
            return false;
        }
        
        // 检查视线是否清晰
        LineOfSightChecker.LineOfSightResult losResult = 
                LineOfSightChecker.check(level, currentPos, target, BOT_EYE_HEIGHT);
        
        // 检查距离是否在范围内
        double distanceSqr = currentPos.distSqr(target);
        
        return losResult.isClear() && distanceSqr <= MAX_REACH * MAX_REACH;
    }
    
    /**
     * 生成候选站位列表。
     */
    private static List<BlockPos> generateCandidates(ServerLevel level, BlockPos target, BlockPos currentPos) {
        List<BlockPos> candidates = new ArrayList<>();
        
        // 优先检查当前位置
        if (currentPos != null && isValidStandingPoint(level, currentPos, target)) {
            candidates.add(currentPos.immutable());
        }
        
        // 搜索目标周围的站位
        // 水平方向：目标的 4 个侧面和 4 个对角
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = target.relative(dir);
            
            // 直接相邻位置
            addCandidateIfValid(level, adjacent, target, candidates, currentPos);
            
            // 高 1 格
            addCandidateIfValid(level, adjacent.above(), target, candidates, currentPos);
            
            // 低 1 格
            addCandidateIfValid(level, adjacent.below(), target, candidates, currentPos);
        }
        
        // 对角位置
        int[][] diagonals = {
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        
        for (int[] diag : diagonals) {
            BlockPos diagPos = target.offset(diag[0], 0, diag[1]);
            addCandidateIfValid(level, diagPos, target, candidates, currentPos);
            addCandidateIfValid(level, diagPos.above(), target, candidates, currentPos);
            addCandidateIfValid(level, diagPos.below(), target, candidates, currentPos);
        }
        
        // 正上方和正下方（特殊情况）
        addCandidateIfValid(level, target.above(), target, candidates, currentPos);
        addCandidateIfValid(level, target.above(2), target, candidates, currentPos);
        
        return candidates;
    }
    
    /**
     * 如果位置有效则添加到候选列表。
     */
    private static void addCandidateIfValid(ServerLevel level, BlockPos pos, BlockPos target,
                                             List<BlockPos> candidates, BlockPos currentPos) {
        // 避免重复添加当前位置
        if (currentPos != null && pos.equals(currentPos)) {
            return;
        }
        
        if (isValidStandingPoint(level, pos, target)) {
            candidates.add(pos.immutable());
        }
    }
    
    /**
     * 检查位置是否是有效的站位。
     */
    private static boolean isValidStandingPoint(ServerLevel level, BlockPos pos, BlockPos target) {
        // 1. 检查距离
        double distanceSqr = pos.distSqr(target);
        if (distanceSqr > MAX_REACH * MAX_REACH) {
            return false;
        }
        
        // 2. 检查是否可以站立（脚下有支撑，头上有空间）
        // 注意：canWalkOn(level, footPos) 的语义是"能否站在 footPos"（内部已看 footPos.below()）；
        // 早期写成 pos.below() 相当于要求"下方两格有支撑"，会拒掉所有正常地面站位（D-065 修复）。
        if (!MovementHelper.canWalkOn(level, pos)) {
            return false;
        }
        
        if (!MovementHelper.canWalkThrough(level, pos)) {
            return false;
        }
        
        if (!MovementHelper.canWalkThrough(level, pos.above())) {
            return false;
        }
        
        // 3. 不能站在目标方块内部
        // 也不站目标正上方：站在目标顶上挖下去 = 挖掉自己的支撑（与 legacy BotMiner 规则一致）
        if (pos.equals(target) || pos.equals(target.above())) {
            return false;
        }
        
        return true;
    }
}
