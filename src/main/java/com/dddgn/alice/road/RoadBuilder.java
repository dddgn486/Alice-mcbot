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
                    removeFallingMaterial(unit);
                    retrying = true;
                    buildUnit(unit);
                    waitTicks = 5;
                } else {
                    unitIndex++;
                    retrying = false;
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
        for (RoadPlan.Cell cell : unit.cells()) {
            if (level.getBlockState(cell.pos()).getBlock() instanceof FallingBlock) return true;
        }
        BlockPos support = unit.support();
        // 扫描单元水平柱体上方 8 格：沙/沙砾可能尚未进入三格通道体素，但会在等待期落入。
        for (int dy = -1; dy <= 8; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos scan = support.offset(dx, dy, dz);
                    if (level.getBlockState(scan).getBlock() instanceof FallingBlock) return true;
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, 8.0D, 1.5D);
        return !level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty();
    }

    private void removeFallingMaterial(RoadPlan.Unit unit) {
        for (RoadPlan.Cell cell : unit.cells()) {
            BlockPos pos = cell.pos();
            if (level.getBlockState(pos).getBlock() instanceof FallingBlock
                    && BlockBreakSafety.clearingRefusal(actor, pos) == null) {
                level.destroyBlock(pos, false);
            }
        }
        var box = new net.minecraft.world.phys.AABB(unit.support()).inflate(1.5D, 8.0D, 1.5D);
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
