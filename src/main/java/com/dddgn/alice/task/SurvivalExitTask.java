package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.core.BlockPos;

/**
 * **维生逃生任务**（S-1 / P1-C）：走到维生系统给出的最近安全落点。
 *
 * <p>为什么直接复用 {@link WalkToTask} 而不是新写一套移动：用户裁定过的分工是
 * 「**维生给落点、寻路给路径**」——`SurvivalSystem.nearestSafeRefuge` 只做纯查询，
 * 真正的移动交给**已经客户端验收**的硬路径任务（`PathRequest.of` 纯通行，不挖不放置，
 * D-076 红线不受影响）。
 *
 * <p>实现为独立类（而不是 `WalkToTask` 加个布尔）是为了让**终态记录里能一眼看出这是逃生回合**：
 * `task_execution_terminal kind=SurvivalExitTask …`，日志里也能和普通 `WalkTo` 区分开。
 */
public final class SurvivalExitTask extends WalkToTask implements SurvivalExit {

    public SurvivalExitTask(BotPlayer bot, BlockPos refugeFoot) {
        super(bot, refugeFoot);
    }
}
