package com.dddgn.alice.pathing.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** 只读世界快照边界；实现可以是主线程快照，不允许通过此接口写世界。 */
public interface WorldView {
    String worldId();
    long revision();
    long capturedAtTick();
    BlockState blockState(BlockPos pos);
    boolean isFluid(BlockPos pos);
    boolean isHazard(BlockPos pos);
    boolean isProtected(BlockPos pos);
}
