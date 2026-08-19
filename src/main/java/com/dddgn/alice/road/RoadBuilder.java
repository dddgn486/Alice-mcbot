package com.dddgn.alice.road;

import com.dddgn.alice.protection.BlockBreakSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * 按水平道路单元逐格构建：本单元完成后等待 5 tick，确认没有沙/ gravel 下坠，
 * 才允许进入下一单元。重复执行同一单元只检查当前状态，不重复放置/挖掘。
 */
public final class RoadBuilder {
    private static final RoadBuilder INSTANCE = new RoadBuilder();
    private RoadPlan plan;
    private ServerLevel level;
    private ServerPlayer actor;
    private int unitIndex;
    private int waitTicks;
    private boolean active;
    private boolean retrying;
    private int stabilityRetries;
    private static final int MAX_STABILITY_RETRIES = 3;

    private RoadBuilder() {}
    public static RoadBuilder get() { return INSTANCE; }

    public synchronized boolean start(ServerLevel level, ServerPlayer actor, RoadPlan plan) {
        if (active || !plan.isComplete() || plan.level() != level || plan.units().isEmpty()) return false;
        this.level = level;
        this.actor = actor;
        this.plan = plan;
        this.unitIndex = 0;
        this.waitTicks = 0;
        this.retrying = false;
        this.stabilityRetries = 0;
        this.active = true;
        return true;
    }

    public synchronized boolean isActive() { return active; }
    public synchronized int unitIndex() { return unitIndex; }
    public synchronized int unitCount() { return plan == null ? 0 : plan.units().size(); }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) INSTANCE.tick();
    }

    private synchronized void tick() {
        if (!active || plan == null || level == null) return;
        if (unitIndex >= plan.units().size()) {
            finish();
            return;
        }
        RoadPlan.Unit unit = plan.units().get(unitIndex);
        if (waitTicks > 0) {
            waitTicks--;
            if (waitTicks == 0) {
                if (hasFallingMaterial(unit)) {
                    if (stabilityRetries++ >= MAX_STABILITY_RETRIES) {
                        finish();
                        return;
                    }
                    removeFallingMaterial(unit);
                    retrying = true;
                    buildUnit(unit);
                    waitTicks = 5;
                } else {
                    unitIndex++;
                    retrying = false;
                    stabilityRetries = 0;
                }
            }
            return;
        }
        buildUnit(unit);
        waitTicks = 5;
    }

    private void buildUnit(RoadPlan.Unit unit) {
        for (RoadPlan.Cell cell : unit.cells()) {
            BlockPos pos = cell.pos();
            if (pos.equals(plan.first()) || pos.equals(plan.second())) continue;
            if (cell.kind() == RoadPlan.CellKind.SUPPORT_PLACE) {
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            } else if (cell.kind() == RoadPlan.CellKind.CLEAR
                    && BlockBreakSafety.clearingRefusal(actor, pos) == null) {
                if (!level.getBlockState(pos).isAir()) level.destroyBlock(pos, false);
            }
        }
    }

    private boolean hasFallingMaterial(RoadPlan.Unit unit) {
        BlockPos support = unit.support();
        // 螺旋单元有三格净空，检测柱相应提高；稳定实体方块不应让道路假死。
        int upperScan = unit.headroom() + 6;
        for (int dy = -1; dy <= upperScan; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos scan = support.offset(dx, dy, dz);
                    if (isUnstableFallingBlock(scan)) return true;
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, upperScan, 1.5D);
        return !level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty();
    }

    private boolean isUnstableFallingBlock(BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof FallingBlock)) return false;
        BlockPos below = pos.below();
        var belowState = level.getBlockState(below);
        return belowState.isAir() || belowState.getCollisionShape(level, below).isEmpty()
                || !belowState.getFluidState().isEmpty();
    }

    private void removeFallingMaterial(RoadPlan.Unit unit) {
        BlockPos support = unit.support();
        // 清理范围与检测范围一致，否则会检测到上方方块却永远清不掉并重复等待。
        int upperScan = unit.headroom() + 6;
        for (int dy = -1; dy <= upperScan; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = support.offset(dx, dy, dz);
                    if (isUnstableFallingBlock(pos)
                            && BlockBreakSafety.clearingRefusal(actor, pos) == null) {
                        level.destroyBlock(pos, false);
                    }
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, upperScan, 1.5D);
        for (FallingBlockEntity entity : level.getEntitiesOfClass(FallingBlockEntity.class, box)) {
            entity.discard();
        }
    }

    private void finish() {
        active = false;
        level = null;
        actor = null;
        plan = null;
        waitTicks = 0;
    }
}
