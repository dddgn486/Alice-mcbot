package com.dddgn.alice.task.lumber;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 树木数据结构，表示一棵完整的树。
 * <p>
 * 包含树的类型、基座位置、所有原木方块和树叶方块。
 * 提供树的验证、排序等功能。
 */
public class Tree {
    
    private final TreeType type;
    private final BlockPos basePos;
    private final List<BlockPos> logs;
    private final List<BlockPos> leaves;
    private final int height;
    
    public Tree(TreeType type, BlockPos basePos, List<BlockPos> logs, List<BlockPos> leaves) {
        this.type = type;
        this.basePos = basePos;
        this.logs = new ArrayList<>(logs);
        this.leaves = new ArrayList<>(leaves);
        this.height = calculateHeight();
    }
    
    private int calculateHeight() {
        if (logs.isEmpty()) {
            return 0;
        }
        int minY = logs.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxY = logs.stream().mapToInt(BlockPos::getY).max().orElse(0);
        return maxY - minY + 1;
    }
    
    /**
     * 验证这是否是一棵有效的树。
     * 
     * 有效条件：
     * - 至少 4 个原木方块
     * - 有树叶
     * - 基座在自然地面上
     */
    public boolean isValid() {
        return logs.size() >= 4 && 
               !leaves.isEmpty() && 
               baseOnNaturalGround();
    }
    
    /**
     * 检查树的基座是否在自然地面上（泥土、草方块等）。
     */
    private boolean baseOnNaturalGround() {
        // 暂时简化：只要有原木就算
        // 后续可以检查下方方块
        return !logs.isEmpty();
    }
    
    /**
     * 获取按砍伐顺序排列的原木列表（从下往上）。
     * 这样可以避免浮空原木。
     */
    public List<BlockPos> getLogsInCutOrder() {
        List<BlockPos> sorted = new ArrayList<>(logs);
        // 从下往上排序（Y 坐标从小到大）
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        return sorted;
    }
    
    // Getters
    
    public TreeType getType() {
        return type;
    }
    
    public BlockPos getBasePos() {
        return basePos;
    }
    
    public List<BlockPos> getLogs() {
        return new ArrayList<>(logs);
    }
    
    public List<BlockPos> getLeaves() {
        return new ArrayList<>(leaves);
    }
    
    public int getHeight() {
        return height;
    }
    
    public int getLogCount() {
        return logs.size();
    }
    
    @Override
    public String toString() {
        return String.format("Tree[%s, base=%s, logs=%d, height=%d]",
                type.getDisplayName(), basePos.toShortString(), logs.size(), height);
    }
}
