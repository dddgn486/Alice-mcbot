package com.dddgn.alice.decision;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * 决策层最小规则(设计文档 §6 的首次落地——无 LLM,纯规则)。
 * <p>
 * 感知 → 决策 → 执行 的最小闭环:
 * <ul>
 *   <li><b>感知</b>:世界直读,扫描以 bot 为中心 N 格内匹配目标的方块;</li>
 *   <li><b>决策</b>:取 3D 距离最近的目标(矿脉中的方块被围时由执行层清障/挖通道);</li>
 *   <li><b>执行</b>:产出 {@code TaskTarget} 交给任务层(MineTask 自动挖通道拾取)。</li>
 * </ul>
 * 目标支持两种写法:<b>标签</b>(如 {@code minecraft:coal_ores}, 匹配一组方块)
 * 或 <b>方块 ID</b>(如 {@code minecraft:stone}, 精确匹配单个方块——注意 stone 没有
 * 同名标签, 必须走方块模式)。后续 LLM 决策接入时,只需替换「决策」这一步。</p>
 */
public final class AutoMineDecision {

    /** 感知扫描半径(一次命令触发,ms 级,可放宽)。 */
    public static final int SCAN_RADIUS = 24;

    private AutoMineDecision() {
    }

    /** 按标签扫描:返回中心周围 radius 格内匹配标签的最近方块。 */
    public static BlockPos pickNearest(ServerLevel level, BlockPos center,
                                       TagKey<Block> tag, int radius) {
        return scan(level, center, state -> state.is(tag), radius,
                "标签 " + tag.location());
    }

    /** 按方块 ID 扫描:返回中心周围 radius 格内指定方块的最近位置。 */
    public static BlockPos pickNearestBlock(ServerLevel level, BlockPos center,
                                            Block block, int radius) {
        return scan(level, center, state -> state.getBlock() == block, radius,
                "方块 " + net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block));
    }

    private static BlockPos scan(ServerLevel level, BlockPos center,
                                 Predicate<BlockState> matcher, int radius, String describe) {
        BlockPos best = null;
        double bestDistSqr = Double.MAX_VALUE;
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !matcher.test(state)) {
                        continue;
                    }
                    found++;
                    double d = center.distSqr(pos);
                    if (d < bestDistSqr) {
                        bestDistSqr = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        BotLog.info("决策层感知: {} 扫描半径 {} 找到 {} 个, 最近={}",
                describe, radius, found,
                best == null ? "无" : best.toShortString());
        return best;
    }
}
