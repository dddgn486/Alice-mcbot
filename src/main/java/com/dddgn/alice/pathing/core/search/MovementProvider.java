package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.List;

/** 从当前脚位枚举候选 Movement（架构文档 §6）。不负责任务重试或状态修改。 */
public interface MovementProvider {
    /**
     * 把从 {@code from} 出发的合法候选追加到 {@code out}。
     * 实现必须只读世界、无副作用。
     */
    void appendCandidates(MovementContext context, BlockPos from, List<PlannedMovement> out);

    /**
     * **起点脱困的第一跳**（S-1 / D-133，2026-09-12）：只在搜索的**起点**上、且
     * {@link #appendCandidates} 一条边都没生成时调用。
     *
     * <p>为什么需要：`canTraverse` 末尾的 `canSweepPlayer(from, to)` 扫掠 AABB **包含起点自身的体积**，
     * 所以当起点非法（头部被方块占据=窒息、或脚/头泡在流体里）时，**一条边都生成不出来** ——
     * 连"迈出去一步"都规划不了（2026-09-12 客户端实测：维生逃生任务
     * `kind=SurvivalExitTask … code=failed:walk_no_path`，目标就在 1 格外）。
     *
     * <p>为什么这样放宽是安全的：**执行器的前置条件本来就是只看目的地**
     * （`TraverseExecutionFactory.validate`：`to` / `to.above()` 可穿 + `to` 可站），
     * 因此"只检查目的地"不会造出"可规划不可执行"的路径（D-044 ⑨ 的担忧不适用）；
     * 对照 Baritone `MovementTraverse.cost`（只看 `dest`/`positionsToBreak`，从不看 `src` 的占用）。
     */
    default void appendStartEscapeCandidates(MovementContext context, BlockPos from,
                                             List<PlannedMovement> out) {
        // 默认无脱困候选（夹具/无头 provider 不需要）
    }
}
