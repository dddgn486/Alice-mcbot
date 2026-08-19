package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 只负责主动目标掉落物的保守收集。不会结束作用域，也不会调用 TunnelPlanner。
 */
public final class DropCollectionTask implements Task {
    private static final int TIMEOUT_TICKS = 400;
    private static final double MAX_DISTANCE = 64.0D;
    private static final double CENTER_TOLERANCE_SQR = 0.04D;
    private static final int MAX_STAIR_CLEARS = 8;

    private final ServerPlayer bot;
    private final BlockPos origin;
    private final ScopeBuffer scope;
    private final Set<UUID> primaryIds = new HashSet<>();
    private final Set<UUID> abandoned = new HashSet<>();
    private int elapsed;
    private int captureWait;
    private boolean captured;
    private UUID stickyId;
    private BlockPos stickyPos;
    private PathExecutor executor;
    private int waitTicks;
    private int retries;
    private BotMiner stairMiner;
    private int stairClears;
    private String failure = "";

    public DropCollectionTask(ServerPlayer bot, BlockPos origin, ScopeBuffer scope) {
        this.bot = bot;
        this.origin = origin.immutable();
        this.scope = scope;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(origin);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Status tick() {
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failure = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        elapsed++;
        if (elapsed > TIMEOUT_TICKS) {
            failure = "collect_timeout";
            return Status.FAILED;
        }
        ServerLevel level = (ServerLevel) bot.level();
        if (!captured) {
            captureWait++;
            for (ItemEntity item : scope.liveItemsFromOrigin(origin)) {
                primaryIds.add(item.getUUID());
            }
            if (!primaryIds.isEmpty()) {
                captured = true;
            } else if (captureWait < 30) {
                return Status.RUNNING;
            } else {
                captured = true;
                return Status.DONE;
            }
        }

        List<ItemEntity> items = scope.liveItemsFromOrigin(origin).stream()
                .filter(item -> primaryIds.contains(item.getUUID())).toList();
        if (items.isEmpty()) {
            return Status.DONE;
        }
        ItemEntity nearest = chooseItem(items);
        if (nearest == null) {
            return Status.DONE;
        }
        BlockPos pos = nearest.blockPosition().immutable();
        if (!nearest.getUUID().equals(stickyId) || !pos.equals(stickyPos)) {
            stickyId = nearest.getUUID();
            stickyPos = pos;
            executor = null;
            stairMiner = null;
            stairClears = 0;
            waitTicks = 0;
            retries = 0;
        }

        double dx = bot.getX() - (pos.getX() + 0.5D);
        double dz = bot.getZ() - (pos.getZ() + 0.5D);
        if (bot.blockPosition().equals(pos) && dx * dx + dz * dz <= CENTER_TOLERANCE_SQR) {
            waitTicks++;
            if (waitTicks >= 20) {
                forcePickup(nearest);
                if (nearest.isRemoved() || nearest.getItem().isEmpty()) {
                    waitTicks = 0;
                    return Status.RUNNING;
                }
            }
            return Status.RUNNING;
        }
        if (stairMiner != null) {
            BotMiner.Status status = stairMiner.tick();
            if (status == BotMiner.Status.DONE) {
                stairMiner = null;
                executor = null;
                return Status.RUNNING;
            }
            if (status == BotMiner.Status.FAILED) {
                failure = "collect_stair_failed:" + stairMiner.failureReason();
                return Status.FAILED;
            }
            return Status.RUNNING;
        }
        if (!nearest.onGround()) {
            return Status.RUNNING;
        }
        if (executor == null) {
            if (pos.equals(bot.blockPosition())) {
                executor = new PathExecutor(bot, List.of(pos));
            } else {
                SurfacePathfinder.Result result = SurfacePathfinder.find(level, bot.blockPosition(), pos);
                if (!result.reachable()) {
                    result = SurfacePathfinder.find(level, bot.blockPosition(), pos.above());
                }
                if (!result.reachable()) {
                    BlockPos blocker = findStairBlocker(level, pos);
                    if (blocker != null) {
                        stairClears++;
                        stairMiner = new BotMiner(bot, blocker);
                        return Status.RUNNING;
                    }
                    failure = "collect_no_path";
                    return Status.FAILED;
                }
                executor = new PathExecutor(bot, result.path());
            }
        }
        PathExecutor.Status path = executor.tick();
        if (path == PathExecutor.Status.FAILED) {
            if (executor.wasObstructed() && retries++ < 2) {
                executor = null;
                return Status.RUNNING;
            }
            failure = "collect_path_failed";
            return Status.FAILED;
        }
        if (path == PathExecutor.Status.DONE) {
            executor = null;
        }
        return Status.RUNNING;
    }

    private ItemEntity chooseItem(List<ItemEntity> items) {
        ItemEntity sticky = null;
        if (stickyId != null) {
            for (ItemEntity item : items) {
                if (item.getUUID().equals(stickyId)
                        && bot.distanceToSqr(item) <= MAX_DISTANCE * MAX_DISTANCE) {
                    sticky = item;
                    break;
                }
            }
        }
        if (sticky != null) return sticky;
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : items) {
            double distance = bot.distanceToSqr(item);
            if (distance > MAX_DISTANCE * MAX_DISTANCE) {
                abandoned.add(item.getUUID());
                continue;
            }
            if (distance < best) {
                best = distance;
                nearest = item;
            }
        }
        return nearest;
    }

    private BlockPos findStairBlocker(ServerLevel level, BlockPos itemStand) {
        if (stairClears >= MAX_STAIR_CLEARS || itemStand.getY() >= bot.blockPosition().getY()) return null;
        int dxToItem = Integer.compare(itemStand.getX(), bot.blockPosition().getX());
        int dzToItem = Integer.compare(itemStand.getZ(), bot.blockPosition().getZ());
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) candidates.add(bot.blockPosition().offset(dx, -1, dz));
            }
        }
        candidates.sort(Comparator.comparingInt(pos ->
                Math.abs(pos.getX() - bot.blockPosition().getX() - dxToItem)
                        + Math.abs(pos.getZ() - bot.blockPosition().getZ() - dzToItem)));
        for (BlockPos candidate : candidates) {
            if (level.getBlockState(candidate).isAir()
                    || !level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
                    || level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty()
                    || !level.getBlockState(candidate).getFluidState().isEmpty()
                    || !level.getBlockState(candidate.below()).getFluidState().isEmpty()) continue;
            if (BlockBreakSafety.clearingRefusal(bot, candidate) == null) return candidate;
        }
        return null;
    }

    private void forcePickup(ItemEntity item) {
        try {
            Field field = ItemEntity.class.getDeclaredField("pickupDelay");
            field.setAccessible(true);
            field.setInt(item, 0);
        } catch (Exception e) {
            BotLog.warn("拾取兜底: 反射清 pickupDelay 失败 {}", e.getMessage());
        }
        item.playerTouch(bot);
    }
}
