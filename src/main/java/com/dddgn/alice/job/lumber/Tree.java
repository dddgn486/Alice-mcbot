package com.dddgn.alice.job.lumber;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 一棵树的只读模型（伐木领域）。
 *
 * <p>保留自旧 `task/lumber/Tree`（D-073 删除，git 历史可查）的领域知识：
 * 基座 = 最低原木、原木按 y 升序 = 砍伐顺序。
 *
 * @param base      基座（最低原木，也是候选锚点）
 * @param logs      **按 y 升序**的原木列表
 * @param species   树种（按原木方块注册名推断；未知原木回退为注册名）
 * @param hasCanopy 树冠特征（仅作特征，**不再作为"是不是树"的必要条件**——旧代码要求有叶才算树）
 * @param tooLarge  是否超过 `maxLogs`（疑似玩家建筑，如原木墙）
 */
public record Tree(BlockPos base, List<BlockPos> logs, String species, boolean hasCanopy,
                   boolean tooLarge) {

    public Tree {
        base = base.immutable();
        logs = List.copyOf(logs);
    }

    public int logCount() {
        return logs.size();
    }

    public int trunkHeight() {
        if (logs.isEmpty()) {
            return 0;
        }
        return logs.get(logs.size() - 1).getY() - logs.get(0).getY() + 1;
    }

    /** 最上方原木（锯掉树冠后仍可能悬空，用于暴露度判定）。 */
    public BlockPos top() {
        return logs.get(logs.size() - 1);
    }

    /** 砍伐顺序：自下而上（与旧 `getLogsInCutOrder()` 一致）。 */
    public List<BlockPos> logsBottomUp() {
        return logs;
    }
}
