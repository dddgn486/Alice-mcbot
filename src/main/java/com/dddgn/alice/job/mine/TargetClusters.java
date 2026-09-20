package com.dddgn.alice.job.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **目标簇 = 几何相连的目标集合**（`D-329` §3 邻居；用户 2026-09-20 定调：**几何 + 同区块**）。
 *
 * <p>用户原话（本次裁定）："我本来只是想要他**相连就是一簇**，也就是连锁挖掘的那个判定，但就算被
 * **区块分割**，最多搜索成本加三次，也足够了" ⇒ 两条口径：
 * <ol>
 *   <li>**簇的判定纯粹是几何连通性**（连锁挖掘那个判定），**不**看授权面、**不**看可挖性、**不**看记忆；</li>
 *   <li>区块边界**不是**切簇的依据，而是**成本**：一个簇跨了 k 个区块 ⇒ 覆盖它要 k−1 次额外搜索，
 *       **预算 = 3**（用户给的宽容度）。超过预算就**如实拆簇**（宁可多几个选择单元，也不假装一次能覆盖）。</li>
 * </ol>
 *
 * <p>⭐⭐ **为什么必须"纯粹是几何"**（用户点出的陷阱的结构面）：一个"符合要求"的簇里**完全可能有一部分
 * 目标实际不可挖**（保护区、不可破、视线不可达）。⇒ 本类只回答"**谁和谁相连**"；
 * "这一簇里哪些真能挖"永远由**调用方**用**当前**的授权面/可破性去算（`D-348`：代理判据 ≠ 世界事实）。
 * 门禁 {@code rule_cluster_is_pure_geometry} 钉死这条：本类里不许出现授权/破坏/记忆的任何符号。
 */
public final class TargetClusters {

    /** 覆盖一个簇允许的**额外搜索次数**（用户 2026-09-20 给的宽容度：区块分割最多 +3 次）。 */
    public static final int DEFAULT_EXTRA_SEARCH_BUDGET = 3;

    /**
     * 连通口径。
     *
     * <p>默认 {@link #DIAGONAL_26}：连锁挖掘的直觉是"斜着挨着也算一条脉"，而矿脉在 MC 里**经常斜向相连**
     * （`FACE` 会把一条斜脉切成一堆"单格簇"，反而更贵）。
     * ⚠️ 这是**可换**的口径，不是硬编码假设：两种模式都有判据（夹具逐条断言）。
     */
    public enum Connectivity {
        /** 六邻接（只认面相邻）。 */
        FACE,
        /** 二十六邻接（对角也算相邻）。 */
        DIAGONAL_26
    }

    /**
     * 一个簇。
     *
     * @param members           成员位置（**确定性排序**：按 y → x → z）
     * @param chunkCount        成员分布在几个区块里
     * @param crossesChunkBoundary 是否跨了区块边界（跨了 ⇒ 覆盖它要多花搜索）
     * @param representative    代表点（= 排序后第一个成员；日志/选点用它，避免"簇没有名字"）
     */
    public record Cluster(List<BlockPos> members, int chunkCount, boolean crossesChunkBoundary,
                          BlockPos representative) {

        public Cluster {
            members = List.copyOf(members);
        }

        public int size() {
            return members.size();
        }

        /** 用一次接近覆盖整簇，需要**额外**扫几个区块（= 跨的区块数 − 1）。 */
        public int extraSearches() {
            return Math.max(0, chunkCount - 1);
        }

        public String describe() {
            return "cluster@" + representative.getX() + "," + representative.getY() + ","
                    + representative.getZ() + " size=" + size() + " chunks=" + chunkCount
                    + " extraSearches=" + extraSearches();
        }
    }

    private TargetClusters() {
    }

    /** 默认口径：二十六邻接 + 预算 3。 */
    public static List<Cluster> partition(Collection<BlockPos> anchors) {
        return partition(anchors, Connectivity.DIAGONAL_26, DEFAULT_EXTRA_SEARCH_BUDGET);
    }

    /**
     * 把候选位置切成簇。
     *
     * <p>步骤：① 连通分量（BFS，邻居按确定性顺序展开）；② 分量若 `区块数 − 1 > 预算` ⇒ 按区块**如实拆簇**
     * （greedy 合并相邻区块，直到再加就超预算）。②的产物是①的子集并能覆盖全部成员 ⇒ **成员守恒**（判据断言）。
     */
    public static List<Cluster> partition(Collection<BlockPos> anchors, Connectivity mode,
                                          int extraSearchBudget) {
        Set<BlockPos> nodes = new LinkedHashSet<>();
        for (BlockPos anchor : anchors) {
            nodes.add(anchor.immutable());
        }
        List<BlockPos> ordered = sorted(nodes);
        Set<BlockPos> seen = new LinkedHashSet<>();
        List<Cluster> clusters = new ArrayList<>();
        for (BlockPos start : ordered) {
            if (!seen.add(start)) {
                continue;
            }
            List<BlockPos> component = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                for (BlockPos neighbour : neighboursIn(ordered, current, mode)) {
                    if (seen.add(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            clusters.addAll(splitByChunkBudget(component, extraSearchBudget));
        }
        return List.copyOf(clusters);
    }

    /** 确定性顺序：y → x → z（与扫描的 Y 优先口径一致，日志可复核）。 */
    private static List<BlockPos> sorted(Collection<BlockPos> positions) {
        List<BlockPos> list = new ArrayList<>(positions);
        // ⚠️ 显式类型见证：`BlockPos` 同时有 `getX/getY/getZ` 与继承来的方法 ⇒ 不写 `<BlockPos>` 推断会歧义
        list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return list;
    }

    /** 在当前集合里找与 `pos` 相连的点（**只做几何**；集合本身已去重）。 */
    private static List<BlockPos> neighboursIn(List<BlockPos> ordered, BlockPos pos, Connectivity mode) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos other : ordered) {
            if (!other.equals(pos) && isNeighbour(pos, other, mode)) {
                found.add(other);
            }
        }
        return found;
    }

    /** 相邻判定（**唯一出处**；换口径只改这里）。 */
    public static boolean isNeighbour(BlockPos a, BlockPos b, Connectivity mode) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dx == 0 && dy == 0 && dz == 0) {
            return false;
        }
        if (mode == Connectivity.FACE) {
            return dx + dy + dz == 1;
        }
        return dx <= 1 && dy <= 1 && dz <= 1;
    }

    /**
     * 超预算则按区块拆（**成员守恒**）。
     *
     * <p>greedy：区块键按字典序取，能并进已有子簇（区块 8-邻接）且不超预算就并，否则新开一个。
     * 不是"最优划分"（那是装箱问题），但**确定性、成员不丢、每簇都不超预算** —— 三条都能被判据咬住。
     */
    private static List<Cluster> splitByChunkBudget(List<BlockPos> component, int budget) {
        int cap = Math.max(1, budget + 1);
        Map<Long, List<BlockPos>> byChunk = new LinkedHashMap<>();
        for (BlockPos pos : sorted(component)) {
            byChunk.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4),
                    ignored -> new ArrayList<>()).add(pos);
        }
        List<Long> chunkKeys = new ArrayList<>(byChunk.keySet());
        chunkKeys.sort(Comparator.naturalOrder());
        List<List<Long>> groups = new ArrayList<>();
        for (Long key : chunkKeys) {
            List<Long> target = null;
            for (List<Long> group : groups) {
                if (group.size() >= cap) {
                    continue;
                }
                boolean adjacent = false;
                for (Long member : group) {
                    if (chunksAdjacent(member, key)) {
                        adjacent = true;
                        break;
                    }
                }
                if (adjacent) {
                    target = group;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(key);
        }
        List<Cluster> out = new ArrayList<>();
        for (List<Long> group : groups) {
            List<BlockPos> members = new ArrayList<>();
            for (Long key : group) {
                members.addAll(byChunk.get(key));
            }
            members = sorted(members);
            out.add(new Cluster(members, group.size(), group.size() > 1, members.get(0)));
        }
        return out;
    }

    /**
     * **选中点所在簇的成员队列**（簇消费的纯逻辑）：选中的那个**排第一**（决策理由不变），
     * 其余成员按确定性顺序跟在后面 ⇒ 同一簇的目标**连续挖完**，接近成本被摊薄。
     *
     * <p>⚠️ 只是**顺序建议**，不是可挖承诺：调用方每个成员仍要过**当前**授权面/身份复检
     * （用户点出的陷阱：簇内可能有成员实际不可挖 ⇒ 那些成员必须留下**自己的**理由码）。
     */
    public static List<BlockPos> queueFor(Collection<BlockPos> anchors, BlockPos picked) {
        return queueFor(anchors, picked, Connectivity.DIAGONAL_26, DEFAULT_EXTRA_SEARCH_BUDGET);
    }

    public static List<BlockPos> queueFor(Collection<BlockPos> anchors, BlockPos picked, Connectivity mode,
                                          int extraSearchBudget) {
        for (Cluster cluster : partition(anchors, mode, extraSearchBudget)) {
            if (!cluster.members().contains(picked)) {
                continue;
            }
            // D-364（2026-09-20 真机实测）：簇内顺序**按图距**（从选中格 BFS），
            // 不再直接用 `cluster.members()` 的**层优先坐标序**（`members` 按 y → x → z 排）。
            // ⚠️ 因果要写准（我第一版写成「扫描发现顺序」是**错的**，注入对照把它抓出来了）：
            // `members` 本身就是 (y,x,z) 排序 ⇒ **y 层优先**：真机实测选中序列
            // `y=71`：`401,71,181 → 402,71,181(with z=182)` → `y=72`：`400,72,180 … 403,72,181`
            // —— 先把整层（含同一层里 3 格外的）挖完，再进下一层，于是**来回横跳**；
            // 单个矿石只挖 **12 tick（0.6s）**，而走到下一个站位点要 **9s** ⇒ 用户看到的
            // 「挖一半突然跑出几格、又跑回来开始挖」。BFS 图距保证「越近的越先挖」。
            // **仍是纯几何**（只用坐标 + 连通口径，不读世界）。
            Map<BlockPos, Integer> distance = graphDistance(picked, cluster.members(), mode);
            List<BlockPos> ordered = new ArrayList<>(cluster.members());
            ordered.sort(Comparator
                    .comparingInt((BlockPos pos) -> distance.getOrDefault(pos, Integer.MAX_VALUE))
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
            return List.copyOf(ordered);
        }
        return List.of(picked);
    }

    /**
     * 从 `from` 出发、**只在成员集合内**按 `mode` 走的 BFS 图距（`from` = 0）。
     *
     * <p>同簇成员按定义必然可达（`partition` 就是这么分的）；万一不可达（不该发生）则不进表，
     * 排序时按「最差」处理 —— 顺序问题不该让作业失败。
     */
    private static Map<BlockPos, Integer> graphDistance(BlockPos from, List<BlockPos> members,
                                                        Connectivity mode) {
        List<BlockPos> ordered = sorted(members);
        Map<BlockPos, Integer> distance = new LinkedHashMap<>();
        if (!ordered.contains(from)) {
            return distance;
        }
        distance.put(from, 0);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int next = distance.get(current) + 1;
            for (BlockPos neighbour : neighboursIn(ordered, current, mode)) {
                if (distance.containsKey(neighbour)) {
                    continue;
                }
                distance.put(neighbour, next);
                queue.add(neighbour);
            }
        }
        return distance;
    }

    private static boolean chunksAdjacent(long a, long b) {
        int dx = Math.abs(ChunkPos.getX(a) - ChunkPos.getX(b));
        int dz = Math.abs(ChunkPos.getZ(a) - ChunkPos.getZ(b));
        return dx <= 1 && dz <= 1;
    }
}
