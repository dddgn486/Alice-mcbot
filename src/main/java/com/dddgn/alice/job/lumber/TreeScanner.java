package com.dddgn.alice.job.lumber;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 树识别（伐木领域只读感知）：对旧 `TreeDetector` 的保留与三处弱项修正
 * （`docs/JOB_LAYER_DESIGN.md` §5.1）。
 *
 * <table>
 *   <tr><th>#</th><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>1</td><td>硬编码 8 种 `Blocks.OAK_LOG…`</td><td>用 {@link BlockTags#LOGS}（模组原木友好）</td></tr>
 *   <tr><td>2</td><td>BFS 只搜"上行 3×3 + 正下方"→ 2×2 深色橡木/红树被拆成多棵</td>
 *       <td>增加**同层水平 4 向**；仍只向下 1 格（避免连到玩家建筑）</td></tr>
 *   <tr><td>3</td><td>`isValid()` 要求 `logs ≥ 4 && 有树叶` → 小树/砍过一半的树判不出</td>
 *       <td>**不要求有树叶**；树叶只作 `hasCanopy` 特征</td></tr>
 *   <tr><td>—</td><td>无上限</td><td>{@link #MAX_LOGS} 上限 → 标 `tooLarge`（由候选源按 `too_large` 拒绝）</td></tr>
 * </table>
 */
public final class TreeScanner {

    /** 少于此数量不算树（小树苗/单根原木柱）。 */
    public static final int MIN_LOGS = 3;
    /** 超过此数量视为可疑（玩家建筑），由候选源按 `too_large` 拒绝。 */
    public static final int MAX_LOGS = 64;

    private TreeScanner() {
    }

    /** 扫描 center 半径内的树（同一次扫描内不重复计数）。 */
    public static List<Tree> scan(ServerLevel level, BlockPos center, int radius) {
        List<Tree> trees = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos cursor = pos.immutable();
            if (visited.contains(cursor) || !isLog(level, cursor)) {
                continue;
            }
            BlockPos base = baseOf(level, cursor);
            if (visited.contains(base)) {
                continue;
            }
            List<BlockPos> logs = flood(level, base);
            visited.addAll(logs);
            if (logs.size() < MIN_LOGS) {
                continue;
            }
            boolean tooLarge = logs.size() > MAX_LOGS;
            // 层级优先：先砍完一层的所有列，再上下一层。
            // 理由（用户指出的机制）：自下而上砍完后站进掏空的树干里仰望上方原木底面即可，
            // 不需要清理树叶；但 2×2 树干必须"同层各列都先空出来"，侧向的视线通路才成立。
            logs.sort((a, b) -> {
                int compare = Integer.compare(a.getY(), b.getY());
                if (compare != 0) {
                    return compare;
                }
                compare = Integer.compare(a.getX(), b.getX());
                return compare != 0 ? compare : Integer.compare(a.getZ(), b.getZ());
            });
            trees.add(new Tree(base, logs, speciesOf(level.getBlockState(base)),
                    hasCanopy(level, logs), tooLarge));
        }
        return trees;
    }

    public static boolean isLog(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.LOGS);
    }

    /** 沿正下方找到本柱最低原木（避免从树冠中间开始数）。 */
    private static BlockPos baseOf(ServerLevel level, BlockPos start) {
        BlockPos cursor = start;
        for (int i = 0; i < 64 && isLog(level, cursor.below()); i++) {
            cursor = cursor.below();
        }
        return cursor;
    }

    /** 连通原木：上行 3×3 + 同层水平 4 向 + 正下方 1 格。 */
    private static List<BlockPos> flood(ServerLevel level, BlockPos base) {
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        while (!queue.isEmpty() && logs.size() <= MAX_LOGS) {
            BlockPos current = queue.poll();
            if (!isLog(level, current)) {
                continue;
            }
            logs.add(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    push(queue, seen, current.offset(dx, 1, dz));
                }
            }
            push(queue, seen, current.north());
            push(queue, seen, current.south());
            push(queue, seen, current.east());
            push(queue, seen, current.west());
            push(queue, seen, current.below());
        }
        return logs;
    }

    private static void push(Deque<BlockPos> queue, Set<BlockPos> seen, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        if (seen.add(immutable)) {
            queue.add(immutable);
        }
    }

    /** 树种：注册名去后缀（`oak_log`→`oak`，`crimson_stem`→`crimson`）；未知保持注册名。 */
    private static String speciesOf(BlockState state) {
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) {
            return "unknown";
        }
        String path = id.getPath();
        for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix)) {
                return id.getNamespace().equals("minecraft")
                        ? path.substring(0, path.length() - suffix.length())
                        : id.getNamespace() + ":" + path.substring(0, path.length() - suffix.length());
            }
        }
        return id.toString();
    }

    /** 树冠特征：最上方原木周围 5×4×5 内是否有树叶（**仅特征，不作为成树条件**）。 */
    private static boolean hasCanopy(ServerLevel level, List<BlockPos> logs) {
        BlockPos top = logs.get(logs.size() - 1);
        for (BlockPos pos : BlockPos.betweenClosed(top.offset(-2, -1, -2), top.offset(2, 2, 2))) {
            if (level.getBlockState(pos).is(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }
}
