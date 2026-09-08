package com.dddgn.alice.task.lumber;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * 树木检测器。
 * <p>
 * 负责识别和分析树木结构：
 * - 从一个原木方块开始，追踪所有连接的原木
 * - 检测周围的树叶
 * - 判断树的类型
 * - 验证是否是有效的树（而不是建筑材料）
 */
public class TreeDetector {
    
    /**
     * 从给定的原木方块开始，检测完整的树结构。
     * 
     * @param level 世界
     * @param startLog 起始原木位置
     * @return 树对象，如果不是有效的树则返回 null
     */
    public static Tree detectTree(Level level, BlockPos startLog) {
        // 1. 检查起始位置是否是原木
        if (!isLog(level, startLog)) {
            return null;
        }
        
        // 2. 追踪所有连接的原木
        List<BlockPos> logs = traceConnectedLogs(level, startLog);
        
        if (logs.isEmpty() || logs.size() < 4) {
            return null;  // 原木太少，不是树
        }
        
        // 3. 找到树叶
        List<BlockPos> leaves = findNearbyLeaves(level, logs);
        
        if (leaves.isEmpty()) {
            return null;  // 没有树叶，不是树
        }
        
        // 4. 确定树的类型
        TreeType type = detectTreeType(level, logs);
        
        // 5. 找到基座位置（最底部的原木）
        BlockPos basePos = logs.stream()
                .min(Comparator.comparingInt(BlockPos::getY))
                .orElse(startLog);
        
        // 6. 创建树对象
        Tree tree = new Tree(type, basePos, logs, leaves);
        
        // 7. 验证是否是有效的树
        if (!tree.isValid()) {
            return null;
        }
        
        return tree;
    }
    
    /**
     * 追踪所有连接的原木方块。
     * 使用 BFS（广度优先搜索）向上和斜上方搜索。
     */
    private static List<BlockPos> traceConnectedLogs(Level level, BlockPos start) {
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        
        queue.add(start);
        visited.add(start);
        
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            
            if (isLog(level, current)) {
                logs.add(current.immutable());
                
                // 搜索上方和斜上方的 9 个位置
                for (BlockPos neighbor : getUpNeighbors(current)) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
                
                // 也向下搜索（避免漏掉底部原木）
                // 只搜索正下方（不搜索斜下方，避免连接到地面建筑）
                BlockPos below = current.below();
                if (!visited.contains(below)) {
                    visited.add(below);
                    queue.add(below);
                }
            }
        }
        
        return logs;
    }
    
    /**
     * 获取上方和斜上方的 9 个相邻位置。
     * 只向上搜索，不向下或水平搜索（避免连接到其他树）。
     */
    private static List<BlockPos> getUpNeighbors(BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        
        // 上方 3x3 区域（只向上，不向下）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                neighbors.add(pos.offset(dx, 1, dz));
            }
        }
        
        return neighbors;
    }
    
    /**
     * 查找原木附近的所有树叶。
     */
    private static List<BlockPos> findNearbyLeaves(Level level, List<BlockPos> logs) {
        Set<BlockPos> leaves = new HashSet<>();
        
        // 对每个原木方块，检查周围 5x5x5 区域
        for (BlockPos log : logs) {
            for (BlockPos pos : BlockPos.betweenClosed(
                    log.offset(-2, -2, -2),
                    log.offset(2, 2, 2))) {
                
                if (isLeaves(level, pos)) {
                    leaves.add(pos.immutable());
                }
            }
        }
        
        return new ArrayList<>(leaves);
    }
    
    /**
     * 检测树的类型（根据原木方块的种类）。
     */
    private static TreeType detectTreeType(Level level, List<BlockPos> logs) {
        if (logs.isEmpty()) {
            return TreeType.UNKNOWN;
        }
        
        // 检查第一个原木的类型
        BlockState state = level.getBlockState(logs.get(0));
        
        if (state.is(Blocks.OAK_LOG) || state.is(Blocks.OAK_WOOD)) {
            return TreeType.OAK;
        } else if (state.is(Blocks.BIRCH_LOG) || state.is(Blocks.BIRCH_WOOD)) {
            return TreeType.BIRCH;
        } else if (state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.SPRUCE_WOOD)) {
            return TreeType.SPRUCE;
        } else if (state.is(Blocks.JUNGLE_LOG) || state.is(Blocks.JUNGLE_WOOD)) {
            return TreeType.JUNGLE;
        } else if (state.is(Blocks.ACACIA_LOG) || state.is(Blocks.ACACIA_WOOD)) {
            return TreeType.ACACIA;
        } else if (state.is(Blocks.DARK_OAK_LOG) || state.is(Blocks.DARK_OAK_WOOD)) {
            return TreeType.DARK_OAK;
        } else if (state.is(Blocks.CHERRY_LOG) || state.is(Blocks.CHERRY_WOOD)) {
            return TreeType.CHERRY;
        } else if (state.is(Blocks.MANGROVE_LOG) || state.is(Blocks.MANGROVE_WOOD)) {
            return TreeType.MANGROVE;
        }
        
        return TreeType.OAK;  // 默认橡树
    }
    
    /**
     * 检查方块是否是原木。
     */
    private static boolean isLog(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.LOGS);
    }
    
    /**
     * 检查方块是否是树叶。
     */
    private static boolean isLeaves(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.LEAVES);
    }
}
