package com.dddgn.alice.perception;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;

/**
 * 任务感知视角(设计文档 §3「按任务自动调整感知权重」的落地)。
 * <p>
 * 一个任务一个 Profile:定义「这个任务在乎哪些方块」——目标标签(最高优先级)、
 * 危险/障碍、意外收获。换任务 = 换 Profile,分类与摘要逻辑不变。</p>
 * <p>
 * ⚠️ 审查点 R11:当前只预置挖矿/通用两个视角;建造/探索/战斗视角按里程碑补充,
 * 且「危险/收获」集合是硬编码,后续可下沉为可注册规则(模组适配器)。</p>
 */
public final class PerceptionProfile {

    /** 挖矿视角:目标 = 原版 8 类矿石。 */
    public static final PerceptionProfile MINING = new PerceptionProfile(
            "mining",
            List.of(BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES,
                    BlockTags.GOLD_ORES, BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES,
                    BlockTags.EMERALD_ORES, BlockTags.DIAMOND_ORES),
            5);

    /** 通用视角:无特定目标标签,只关注危险与收获。 */
    public static final PerceptionProfile GENERAL = new PerceptionProfile(
            "general", List.of(), 5);

    private final String taskType;
    private final List<TagKey<Block>> targetTags;
    private final int radius;

    private PerceptionProfile(String taskType, List<TagKey<Block>> targetTags, int radius) {
        this.taskType = taskType;
        this.targetTags = targetTags;
        this.radius = radius;
    }

    public String taskType() {
        return taskType;
    }

    public List<TagKey<Block>> targetTags() {
        return targetTags;
    }

    public int radius() {
        return radius;
    }

    /** 挖矿视角(等价 {@link #MINING},语义化命名)。 */
    public static PerceptionProfile mining() {
        return MINING;
    }

    /** 通用视角。 */
    public static PerceptionProfile general() {
        return GENERAL;
    }


    /** 危险/障碍方块(岩浆、水、黑曜石、火等)。 */
    public static final Set<Block> DANGER_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.WATER, Blocks.OBSIDIAN, Blocks.FIRE,
            Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB);

    /** 意外收获方块(刷怪笼、宝箱、贵重方块等)。 */
    public static final Set<Block> TREASURE_BLOCKS = Set.of(
            Blocks.SPAWNER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
            Blocks.DIAMOND_BLOCK, Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.ANCIENT_DEBRIS, Blocks.BEACON, Blocks.ENCHANTING_TABLE);
}
