package com.dddgn.alice.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 世界直读快照 + 分类聚合摘要(设计文档 §3 感知层核心)。
 * <p>
 * 原始读取:服务端 World API 直读,零协议往返。
 * 摘要输出:消费 {@link PerceptionProfile} + {@link PerceptionClassifier}——
 * 目标/危险/收获列坐标,普通方块仅聚合计数,把上千个方块压缩成几十行结构化文本喂给 AI。</p>
 */
public final class PerceptionSnapshot {

    private PerceptionSnapshot() {
    }

    /**
     * 生成分类聚合摘要。
     *
     * @param profile 任务感知视角(目标标签 + 范围)
     * @return 结构化文本:【目标】【危险】【收获】【普通】四段
     */
    public static String summarize(ServerLevel level, BlockPos center, PerceptionProfile profile) {
        PerceptionClassifier classifier = new PerceptionClassifier(profile.targetTags());
        int radius = profile.radius();

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
                    switch (classifier.classify(state)) {
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
}
