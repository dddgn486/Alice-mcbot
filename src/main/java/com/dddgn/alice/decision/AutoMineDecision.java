package com.dddgn.alice.decision;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 决策层最小规则(设计文档 §6 的首次落地——无 LLM,纯规则)。
 * <p>
 * 感知 → 决策 → 执行 的最小闭环:
 * <ul>
 *   <li><b>感知</b>:世界直读,扫描以 bot 为中心 N 格内匹配目标标签的方块;</li>
 *   <li><b>决策</b>:取 3D 距离最近的目标(矿脉中的方块被围时由执行层清障/挖通道);</li>
 *   <li><b>执行</b>:产出 {@code TaskTarget} 交给任务层(MineTask 自动挖通道拾取)。</li>
 * </ul>
 * 后续 LLM 决策接入时,只需替换「决策」这一步(改选目标策略),感知与执行不变。</p>
 */
public final class AutoMineDecision {

    /** 感知扫描半径(一次命令触发,ms 级,可放宽)。 */
    public static final int SCAN_RADIUS = 24;

    private AutoMineDecision() {
    }

    /**
     * 感知扫描 + 最近目标决策:返回中心周围 radius 格内匹配标签的最近方块。
     *
     * @return 目标坐标;范围内无匹配方块返回 null
     */
    public static BlockPos pickNearest(ServerLevel level, BlockPos center,
                                       TagKey<Block> tag, int radius) {
        BlockPos best = null;
        double bestDistSqr = Double.MAX_VALUE;
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.is(tag)) {
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
        BotLog.info("决策层感知: 标签 {} 扫描半径 {} 找到 {} 个, 最近={}",
                tag.location(), radius, found,
                best == null ? "无" : best.toShortString());
        return best;
    }
}
