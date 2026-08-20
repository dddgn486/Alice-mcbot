package com.dddgn.alice.task;

import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.SoftMovementPrimitive;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 低风险软移动跟随。只跟随同维度在线玩家，使用短周期曲面路径和 NATIVE_TRAVEL。
 */
public final class FollowTask implements Task {
    private static final double FOLLOW_DISTANCE = 2.0D;
    private static final double ARRIVE = 0.25D;
    private static final int REPLAN_INTERVAL = 10;
    private static final int MAX_TARGET_DISTANCE = 24;
    private static final int MAX_SETTLE_TICKS = 30;

    private final ServerPlayer bot;
    private final UUID targetUuid;
    private List<BlockPos> path = List.of();
    private int index;
    private int replanTicks;
    private int settleTicks;
    private String failure = "";

    public FollowTask(ServerPlayer bot, ServerPlayer target) {
        this.bot = bot;
        this.targetUuid = target.getUUID();
    }

    @Override
    public TaskTarget target() {
        ServerPlayer target = targetPlayer();
        return target == null ? TaskTarget.block(bot.blockPosition()) : TaskTarget.entity(target.getId());
    }

    @Override
    public Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failure = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        ServerPlayer target = targetPlayer();
        if (target == null || target.level() != level || !target.isAlive()) {
            failure = "follow_target_unavailable";
            return Status.FAILED;
        }
        if (bot.distanceToSqr(target) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
            failure = "follow_distance_limit";
            return Status.FAILED;
        }
        if (bot.distanceToSqr(target) <= FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
            path = List.of();
            index = 0;
            return Status.RUNNING;
        }

        replanTicks++;
        if (path.isEmpty() || index >= path.size() || replanTicks >= REPLAN_INTERVAL) {
            Status planStatus = replan(level, target);
            if (planStatus != null) {
                return planStatus;
            }
        }
        if (index >= path.size()) {
            return Status.RUNNING;
        }

        BlockPos segment = path.get(index);
        double goalX = segment.getX() + 0.5D;
        double goalZ = segment.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > ARRIVE) {
            SoftMovementPrimitive.applyToward(bot, goalX, goalZ, horizontal,
                    SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
            return Status.RUNNING;
        }

        boolean settled = MovementHelper.isStandingAtFootPos(level, bot, segment) && bot.onGround();
        if (settled) {
            index++;
            settleTicks = 0;
            return Status.RUNNING;
        }
        SoftMovementPrimitive.settle(bot);
        if (++settleTicks > MAX_SETTLE_TICKS) {
            failure = "follow_unsettled:" + segment.toShortString();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private Status replan(ServerLevel level, ServerPlayer target) {
        BlockPos targetFoot = target.blockPosition();
        if (!MovementHelper.canWalkOn(level, targetFoot)
                || !MovementHelper.canWalkThrough(level, targetFoot)
                || !MovementHelper.canWalkThrough(level, targetFoot.above())) {
            failure = "follow_target_not_on_safe_surface";
            return Status.FAILED;
        }
        SurfacePathfinder.Result result = SurfacePathfinder.find(level, bot.blockPosition(), targetFoot);
        if (!result.reachable()) {
            failure = result.inconclusive() ? "follow_search_limit" : "follow_no_path";
            return Status.FAILED;
        }
        path = result.path();
        index = 0;
        replanTicks = 0;
        return null;
    }

    private ServerPlayer targetPlayer() {
        return bot.getServer().getPlayerList().getPlayer(targetUuid);
    }

    @Override
    public String failureReason() {
        return failure;
    }
}
