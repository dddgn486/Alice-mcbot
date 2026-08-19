package com.dddgn.alice.task;

import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.PathExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private List<PlacementCandidate> candidates;
    private PlacementCandidate selected;
    private record PlacementCandidate(BlockPos stand, BlockPos support, Direction face) {}
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
        if (selected == null && candidates == null) {
            candidates = pickCandidates();
            if (candidates.isEmpty()) {
                failure = "place_no_stand";
                return Status.FAILED;
            }
        }
        if (selected == null) {
            while (!candidates.isEmpty()) {
                PlacementCandidate candidate = candidates.remove(0);
                if (candidate.stand().equals(bot.blockPosition())) {
                    selected = candidate;
                    stand = candidate.stand();
                    break;
                }
                List<BlockPos> path = AStarPathfinder.computePath(
                        (net.minecraft.server.level.ServerLevel) bot.level(), bot.blockPosition(),
                        new Goal.GoalBlock(candidate.stand()));
                if (!path.isEmpty()) {
                    selected = candidate;
                    stand = candidate.stand();
                    executor = new PathExecutor(bot, path);
                    break;
                }
            }
            if (selected == null) {
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

    /**
     * 生成“站位 + 实际支撑方块 + 放置面”候选。站位规则沿用挖掘侧的两格实体空间、
     * 支撑方块和 MovementHelper 语义；放置还必须能从站位 raycast 命中支撑方块。
     */
    private List<PlacementCandidate> pickCandidates() {
        List<PlacementCandidate> result = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos standPos = target.offset(dx, dy, dz);
                    if (standPos.equals(target) || standPos.above().equals(target)) continue;
                    if (!MovementHelper.canWalkOn((net.minecraft.server.level.ServerLevel) bot.level(), standPos)
                            || !MovementHelper.canWalkThrough((net.minecraft.server.level.ServerLevel) bot.level(), standPos)
                            || !MovementHelper.canWalkThrough((net.minecraft.server.level.ServerLevel) bot.level(), standPos.above())) {
                        continue;
                    }
                    Vec3 eye = new Vec3(standPos.getX() + 0.5D, standPos.getY() + 1.62D,
                            standPos.getZ() + 0.5D);
                    if (eye.distanceTo(target.getCenter()) > MAX_REACH - 0.3D) continue;
                    for (Direction face : Direction.values()) {
                        BlockPos support = target.relative(face.getOpposite());
                        BlockState supportState = bot.level().getBlockState(support);
                        if (supportState.isAir() || supportState.getCollisionShape(bot.level(), support).isEmpty()) continue;
                        BlockHitResult hit = raycastPlacement(eye, support, face);
                        if (hit != null && hit.getBlockPos().equals(support) && hit.getDirection() == face) {
                            result.add(new PlacementCandidate(standPos.immutable(), support.immutable(), face));
                        }
                    }
                }
            }
        }
        result.sort(java.util.Comparator.comparingDouble(candidate ->
                candidate.stand().distSqr(target) + candidate.support().distSqr(target) * 0.05D));
        return result.size() > 16 ? result.subList(0, 16) : result;
    }

    private boolean canReachAndSee() {
        if (selected == null) return false;
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceTo(target.getCenter()) > MAX_REACH) return false;
        BlockHitResult hit = raycastPlacement(eye, selected.support(), selected.face());
        return hit != null && hit.getBlockPos().equals(selected.support())
                && hit.getDirection() == selected.face();
    }

    private BlockHitResult raycastPlacement(Vec3 eye, BlockPos support, Direction face) {
        Vec3 hitPoint = support.getCenter().add(
                face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        return bot.level().clip(new ClipContext(eye, hitPoint,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
    }
}
