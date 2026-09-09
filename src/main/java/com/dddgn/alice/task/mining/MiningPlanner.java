package com.dddgn.alice.task.mining;

import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 挖掘领域规划器的第一版薄实现：目标方块 -> 站位 -> HARD_PATH -> MiningPlan。
 * 不负责任务生命周期、运行时重规划、目标访问清障或掉落物拾取。
 *
 * <p>D-064：路径规划由 legacy `SurfacePathfinder` 换成新内核 {@link CorePathPlanner}
 * （`PathRequest.of` 纯通行，保持 HARD_PATH 语义）。
 */
public final class MiningPlanner {
    private static final double BOT_EYE_HEIGHT = 1.62D;

    /** 规划结果：plan 为空时仅表示当前规划阶段未产生可用计划。 */
    public record Result(MiningPlan plan, StandingPointEvaluator.StandingPointScore score,
                         String failureReason) {
        public boolean success() {
            return plan != null;
        }
    }

    public Result plan(ServerPlayer bot, BlockPos target) {
        BlockPos immutableTarget = target.immutable();
        BlockPos startFoot = bot.blockPosition().immutable();

        if (StandingPointSelector.isCurrentPositionGoodEnough(
                bot.serverLevel(), immutableTarget, startFoot)) {
            PathPlan path = planPath(bot, startFoot, startFoot);
            LineOfSightChecker.LineOfSightResult visibility =
                    LineOfSightChecker.check(bot.serverLevel(), startFoot, immutableTarget, BOT_EYE_HEIGHT);
            return new Result(new MiningPlan(immutableTarget, startFoot, startFoot, path, visibility),
                    null, "");
        }

        List<StandingPointEvaluator.StandingPointScore> scores =
                StandingPointSelector.selectWithDetails(bot.serverLevel(), immutableTarget, startFoot);
        if (scores.isEmpty()) {
            return new Result(null, null, "no_valid_standing_point");
        }

        StandingPointEvaluator.StandingPointScore best = scores.get(0);
        BlockPos standingFoot = best.getPosition().immutable();
        PathPlan path = planPath(bot, startFoot, standingFoot);
        MiningPlan plan = new MiningPlan(immutableTarget, startFoot, standingFoot, path,
                best.getLineOfSightResult());
        return new Result(plan, best, "");
    }

    private static PathPlan planPath(ServerPlayer bot, BlockPos startFoot, BlockPos standingFoot) {
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, standingFoot);
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }
}
