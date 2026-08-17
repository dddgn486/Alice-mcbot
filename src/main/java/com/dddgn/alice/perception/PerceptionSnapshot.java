package com.dddgn.alice.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 世界直读快照 + 分类聚合摘要(设计文档 §3 感知层核心)。
 * <p>
 * 原始读取:服务端 World API 直读,零协议往返。
 * 摘要输出:按 {@link PerceptionCategory} 归类——目标/危险/收获列坐标,
 * 普通方块仅聚合计数,把上千个方块压缩成几十行结构化文本,再喂给 AI。</p>
 * <p>
 * ⚠️ 审查点 R10:「任务 → 目标标签」映射目前只实现挖矿视角(原版 8 个矿石标签);
 * 后续里程碑按任务类型扩展(建造→建筑材料、探索→结构方块等),这是「自动调整感知权重」的落点。</p>
 */
public final class PerceptionSnapshot {

    private PerceptionSnapshot() {
    }

    /** 挖矿视角的目标标签:原版 8 类矿石(深板岩变体包含在内)。 */
    public static final List<TagKey<Block>> ORE_TAGS = List.of(
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES,
            BlockTags.GOLD_ORES, BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES,
            BlockTags.EMERALD_ORES, BlockTags.DIAMOND_ORES);

    private static final Set<Block> DANGER_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.WATER, Blocks.OBSIDIAN, Blocks.FIRE,
            Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB);

    private static final Set<Block> TREASURE_BLOCKS = Set.of(
            Blocks.SPAWNER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
            Blocks.DIAMOND_BLOCK, Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.ANCIENT_DEBRIS, Blocks.BEACON, Blocks.ENCHANTING_TABLE);

    /**
     * 生成分类聚合摘要。
     *
     * @param targetTags 任务目标标签(挖矿传 {@link #ORE_TAGS})
     * @return 结构化文本:【目标】【危险】【收获】【普通】四段
     */
    public static String summarize(ServerLevel level, BlockPos center, int radius, List<TagKey<Block>> targetTags) {
        Map<String, List<BlockPos>> target = new LinkedHashMap<>();
        Map<String, List<BlockPos>> danger = new LinkedHashMap<>();
        Map<String, List<BlockPos>> treasure = new LinkedHashMap<>();
        Map<String, Integer> common = new TreeMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String name = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
                    PerceptionCategory category = classify(state, targetTags);
                    switch (category) {
                        case TARGET -> target.computeIfAbsent(name, k -> new ArrayList<>()).add(pos.immutable());
                        case DANGER -> danger.computeIfAbsent(name, k -> new ArrayList<>()).add(pos.immutable());
                        case TREASURE -> treasure.computeIfAbsent(name, k -> new ArrayList<>()).add(pos.immutable());
                        case COMMON -> common.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【目标】");
        appendGroup(sb, target, true);
        sb.append("\n【危险】");
        appendGroup(sb, danger, true);
        sb.append("\n【收获】");
        appendGroup(sb, treasure, true);
        sb.append("\n【普通】");
        if (common.isEmpty()) {
            sb.append("无");
        } else {
            common.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(30)
                    .forEach(e -> sb.append(e.getKey()).append('×').append(e.getValue()).append(", "));
            // 去掉末尾多余分隔符
            if (sb.charAt(sb.length() - 1) == ' ') {
                sb.setLength(sb.length() - 1);
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
        }
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, Map<String, List<BlockPos>> group, boolean listCoords) {
        if (group.isEmpty()) {
            sb.append("无");
            return;
        }
        List<Map.Entry<String, List<BlockPos>>> entries = new ArrayList<>(group.entrySet());
        entries.sort(Comparator.comparingInt(e -> -e.getValue().size()));
        boolean first = true;
        for (Map.Entry<String, List<BlockPos>> e : entries) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            sb.append(e.getKey()).append('×').append(e.getValue().size());
            if (listCoords && e.getValue().size() <= 8) {
                sb.append(": ");
                for (BlockPos p : e.getValue()) {
                    sb.append('(').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(')');
                }
            }
        }
    }

    /** 分类规则:目标标签 → 危险 → 收获 → 普通。 */
    private static PerceptionCategory classify(BlockState state, List<TagKey<Block>> targetTags) {
        Block block = state.getBlock();
        if (targetTags != null && targetTags.stream().anyMatch(state::is)) {
            return PerceptionCategory.TARGET;
        }
        if (DANGER_BLOCKS.contains(block)) {
            return PerceptionCategory.DANGER;
        }
        if (TREASURE_BLOCKS.contains(block)) {
            return PerceptionCategory.TREASURE;
        }
        return PerceptionCategory.COMMON;
    }
}
