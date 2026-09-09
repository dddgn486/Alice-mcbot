package com.dddgn.alice.task.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 站位评分器 - 评估不同站位的质量，选择最优站位。
 * 
 * <h3>评分标准</h3>
 * <ol>
 *   <li><b>视线清晰度</b>（最高优先级）：视线清晰 > 有 1 个障碍 > 有多个障碍</li>
 *   <li><b>距离</b>（次要）：距离近优于距离远（在触及范围内）</li>
 *   <li><b>高度</b>（辅助）：同高度优于高处优于低处</li>
 * </ol>
 * 
 * <h3>评分公式</h3>
 * <pre>
 * score = 视线权重 * 视线分数 + 距离权重 * 距离分数 + 高度权重 * 高度分数
 * 
 * 视线分数：
 *   - 清晰：100
 *   - 1 个障碍：50
 *   - 2+ 障碍：0
 * 
 * 距离分数：100 - (距离 / 最大距离 * 100)
 * 高度分数：
 *   - 同高度：100
 *   - 高 1 格：80
 *   - 低 1 格：60
 *   - 其他：0
 * </pre>
 */
public final class StandingPointEvaluator {
    
    private StandingPointEvaluator() {}
    
    // 权重配置（D-066：视线是前提条件，不参与评分）
    private static final double DISTANCE_WEIGHT = 1.0;
    private static final double HEIGHT_WEIGHT = 0.5;
    
    // 高度分数
    private static final double SAME_HEIGHT_SCORE = 100.0;
    private static final double ABOVE_ONE_SCORE = 80.0;
    private static final double BELOW_ONE_SCORE = 60.0;
    
    /**
     * 评估单个站位的质量。
     * 
     * @param level 世界
     * @param standingPoint 站位
     * @param target 目标方块
     * @param eyeHeight Bot 眼睛高度
     * @param maxReach 最大触及距离
     * @return 站位评估结果
     */
    public static StandingPointScore evaluate(Level level, BlockPos standingPoint, 
                                               BlockPos target, double eyeHeight, double maxReach) {
        // 检查视线
        LineOfSightChecker.LineOfSightResult losResult = 
                LineOfSightChecker.check(level, standingPoint, target, eyeHeight);
        
        // 计算距离
        double distance = standingPoint.distSqr(target);
        
        // 计算高度差
        int heightDiff = standingPoint.getY() - target.getY();
        
        // 计算各项分数（D-066：视线只作为前提条件在 StandingPointSelector 里过滤，
        // 这里只对"已经能挖的站位"按距离/高度排序；LOS 结果仍保留在评分记录里供日志/计划快照使用）
        double distanceScore = calculateDistanceScore(distance, maxReach);
        double heightScore = calculateHeightScore(heightDiff);
        double totalScore = DISTANCE_WEIGHT * distanceScore + HEIGHT_WEIGHT * heightScore;
        
        return new StandingPointScore(standingPoint, totalScore, losResult, distance, heightDiff);
    }
    
    /**
     * 评估多个候选站位，返回排序后的结果（最优在前）。
     * 
     * @param level 世界
     * @param candidates 候选站位列表
     * @param target 目标方块
     * @param eyeHeight Bot 眼睛高度
     * @param maxReach 最大触及距离
     * @return 排序后的评分结果（最优在前）
     */
    public static List<StandingPointScore> evaluateAndSort(Level level, List<BlockPos> candidates,
                                                             BlockPos target, double eyeHeight, double maxReach) {
        return candidates.stream()
                .map(pos -> evaluate(level, pos, target, eyeHeight, maxReach))
                .sorted(Comparator.comparingDouble(StandingPointScore::getScore).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * 选择最优站位。
     * 
     * @param level 世界
     * @param candidates 候选站位列表
     * @param target 目标方块
     * @param eyeHeight Bot 眼睛高度
     * @param maxReach 最大触及距离
     * @return 最优站位（如果有）
     */
    public static BlockPos selectBest(Level level, List<BlockPos> candidates,
                                       BlockPos target, double eyeHeight, double maxReach) {
        if (candidates.isEmpty()) {
            return null;
        }
        
        List<StandingPointScore> scores = evaluateAndSort(level, candidates, target, eyeHeight, maxReach);
        return scores.isEmpty() ? null : scores.get(0).getPosition();
    }
    
    private static double calculateDistanceScore(double distanceSqr, double maxReach) {
        double distance = Math.sqrt(distanceSqr);
        double maxReachSqrt = Math.sqrt(maxReach * maxReach);
        
        // 距离越近分数越高
        double ratio = Math.min(distance / maxReachSqrt, 1.0);
        return 100.0 * (1.0 - ratio);
    }
    
    private static double calculateHeightScore(int heightDiff) {
        if (heightDiff == 0) {
            return SAME_HEIGHT_SCORE;
        } else if (heightDiff == 1) {
            return ABOVE_ONE_SCORE;
        } else if (heightDiff == -1) {
            return BELOW_ONE_SCORE;
        }
        return 0.0;
    }
    
    /**
     * 站位评分结果。
     */
    public static class StandingPointScore {
        private final BlockPos position;
        private final double score;
        private final LineOfSightChecker.LineOfSightResult lineOfSightResult;
        private final double distance;
        private final int heightDiff;
        
        public StandingPointScore(BlockPos position, double score,
                                   LineOfSightChecker.LineOfSightResult lineOfSightResult,
                                   double distance, int heightDiff) {
            this.position = position;
            this.score = score;
            this.lineOfSightResult = lineOfSightResult;
            this.distance = distance;
            this.heightDiff = heightDiff;
        }
        
        public BlockPos getPosition() {
            return position;
        }
        
        public double getScore() {
            return score;
        }
        
        public LineOfSightChecker.LineOfSightResult getLineOfSightResult() {
            return lineOfSightResult;
        }
        
        public double getDistance() {
            return distance;
        }
        
        public int getHeightDiff() {
            return heightDiff;
        }
        
        public boolean hasClearLineOfSight() {
            return lineOfSightResult.isClear();
        }
        
        @Override
        public String toString() {
            return String.format("StandingPoint[pos=%s, score=%.1f, los=%s, dist=%.1f, height=%+d]",
                    position.toShortString(),
                    score,
                    lineOfSightResult.isClear() ? "CLEAR" : "BLOCKED",
                    Math.sqrt(distance),
                    heightDiff);
        }
    }
}
