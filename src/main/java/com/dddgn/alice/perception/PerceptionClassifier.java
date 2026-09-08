package com.dddgn.alice.perception;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 感知分类器:把方块归入 {@link PerceptionCategory}。
 * <p>
 * 规则优先级:任务目标标签 → 危险/障碍 → 意外收获 → 普通。
 * 危险/收获集合来自 {@link PerceptionProfile}(当前硬编码,后续可扩展为可注册规则)。</p>
 */
public final class PerceptionClassifier {

    private final List<TagKey<Block>> targetTags;

    public PerceptionClassifier(List<TagKey<Block>> targetTags) {
        this.targetTags = targetTags;
    }

    public PerceptionCategory classify(BlockState state) {
        Block block = state.getBlock();
        if (!targetTags.isEmpty() && targetTags.stream().anyMatch(state::is)) {
            return PerceptionCategory.TARGET;
        }
        if (PerceptionProfile.DANGER_BLOCKS.contains(block)) {
            return PerceptionCategory.DANGER;
        }
        if (PerceptionProfile.TREASURE_BLOCKS.contains(block)) {
            return PerceptionCategory.TREASURE;
        }
        return PerceptionCategory.COMMON;
    }
}
