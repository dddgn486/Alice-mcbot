package com.dddgn.alice.task;

import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.PathExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** 独立方块放置测试任务；不调用 RoadBuilder 或 MineTask。 */
public final class PlaceTask implements Task {
    private static final double MAX_REACH = 4.5D;
    private final ServerPlayer bot;
    private final BlockPos target;
    private PathExecutor executor;
    private List<BlockPos> candidates;
    private BlockPos stand;
    private String failure = "";

    public PlaceTask(ServerPlayer bot, BlockPos target) {
        this.bot = bot;
        this.target = target.immutable();
    }

    @Override public TaskTarget target() { return TaskTarget.block(target); }
    @Override public String failureReason() { return failure; }

    @Override
    public Status tick() {
        if (!bot.level().getBlockState(target).isAir()) return Status.DONE;
        if (stand == null && candidates == null) {
            candidates = pickCandidates();
            if (candidates.isEmpty()) {
                failure = "place_no_stand";
                return Status.FAILED;
            }
        }
        if (stand == null) {
            while (!candidates.isEmpty()) {
                BlockPos candidate = candidates.remove(0);
                if (candidate.equals(bot.blockPosition())) {
                    stand = candidate;
                    break;
                }
                List<BlockPos> path = AStarPathfinder.computePath(
                        (net.minecraft.server.level.ServerLevel) bot.level(), bot.blockPosition(),
                        new Goal.GoalBlock(candidate));
                if (!path.isEmpty()) {
                    stand = candidate;
                    executor = new PathExecutor(bot, path);
                    break;
                }
            }
            if (stand == null) {
                failure = "place_no_path";
                return Status.FAILED;
            }
        }
        if (executor != null) {
            PathExecutor.Status status = executor.tick();
            if (status == PathExecutor.Status.FAILED) {
                failure = "place_path_failed";
                return Status.FAILED;
            }
            if (status == PathExecutor.Status.MOVING) return Status.RUNNING;
            executor = null;
        }
        if (!canReachAndSee()) {
            failure = "place_line_of_sight";
            return Status.FAILED;
        }
        bot.level().setBlock(target, Blocks.COBBLESTONE.defaultBlockState(), 3);
        return Status.DONE;
    }

    private List<BlockPos> pickCandidates() {
        List<BlockPos> result = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = target.offset(dx, dy, dz);
                    BlockState foot = bot.level().getBlockState(p);
                    BlockState head = bot.level().getBlockState(p.above());
                    BlockState below = bot.level().getBlockState(p.below());
                    if (!foot.getCollisionShape(bot.level(), p).isEmpty()
                            || !head.getCollisionShape(bot.level(), p.above()).isEmpty()
                            || below.getCollisionShape(bot.level(), p.below()).isEmpty()
                            || !below.getFluidState().isEmpty()) continue;
                    if (bot.getEyePosition().distanceTo(target.getCenter()) <= MAX_REACH) result.add(p.immutable());
                }
            }
        }
        result.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(target)));
        return result;
    }

    private boolean canReachAndSee() {
        if (bot.getEyePosition().distanceTo(target.getCenter()) > MAX_REACH) return false;
        Vec3 eye = bot.getEyePosition();
        BlockHitResult hit = bot.level().clip(new ClipContext(eye, target.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.MISS;
    }
}
