package com.dddgn.alice.job.lumber;

import com.dddgn.alice.action.BlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 限次清障白名单（保守）：只有这些**软遮挡**才允许被 Job 主动清除以打通视线。
 *
 * <p>设计依据 `docs/JOB_LAYER_DESIGN.md` §5.4：清障是 **Job 显式授权 + 硬预算**的独立子任务
 * （与"收集子任务由调用方授予世界修改权限"同构），不是寻路器自己挖（D-076）。
 *
 * <p>为什么需要它：真实阔叶/针叶树的**最上面 1~2 根原木总被树冠包住**，
 * 若不允许清障，候选源的"每根原木都必须可见"判据会把**所有真树**判为 `trunk_too_tall`——
 * 即"一棵都砍不了"。所以清障不是优化项，是真实世界可用性的前提。
 *
 * <p>**排除**：原木本身（那是目标）、任何不可破坏/受保护方块、以及一切硬遮挡（石头/泥土/建筑）。
 */
public final class SoftBlockPolicy {

    private SoftBlockPolicy() {
    }

    /** 该方块是否允许作为"限次清障"的对象。 */
    public static boolean isClearable(ServerPlayer bot, ServerLevel level, BlockPos pos, BlockPos target) {
        if (pos.equals(target)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(BlockTags.LOGS)) {
            return false;
        }
        boolean soft = state.is(BlockTags.LEAVES)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(BlockTags.REPLACEABLE)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.VINE)
                || state.is(Blocks.GLOW_LICHEN)
                || state.is(Blocks.MOSS_CARPET);
        if (!soft) {
            return false;
        }
        // 保护区 / 不可破坏 / 流体等一律拒绝（复用生产口径）
        return BlockInteraction.breakable(bot, level, pos);
    }
}
