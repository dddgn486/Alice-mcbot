package com.dddgn.aibot.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界直读快照(设计文档 §3.1 首次落地,M1 骨架)。
 * <p>
 * 服务端 World API 直读,零协议往返;只输出任务相关的局部窗口,
 * 符号化为「方块id + 坐标」列表(供未来感知压缩/差异推送使用)。</p>
 */
public final class PerceptionSnapshot {

    private PerceptionSnapshot() {
    }

    /**
     * 取 center 周围 radius 格内的非空气方块,符号化列表。
     * 每行格式: {@code minecraft:stone x=1 y=64 z=0}
     */
    public static List<String> nearbyBlocks(ServerLevel level, BlockPos center, int radius) {
        List<String> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    result.add(ForgeRegistries.BLOCKS.getKey(state.getBlock()) + " x="
                            + pos.getX() + " y=" + pos.getY() + " z=" + pos.getZ());
                }
            }
        }
        return result;
    }
}
