package com.dddgn.alice.job;

import com.dddgn.alice.task.Task;

/**
 * 目标级任务（L3）：选目标 → 生成子任务 → 记账 → 终止。
 *
 * <p>**接入方式**：`Job extends Task`——现有 `BotSession`/`BotManager` 调度零改动
 * （D-080；`BotSession.tick` 只额外比较 `target()` 变化以跟随子目标高亮）。
 *
 * <p>分层纪律：
 * <ul>
 *   <li>只做决策/记账/终止；移动与挖掘**复用已验收的 L2**（`MineTask` / `CollectDropsTask`）；</li>
 *   <li>**不调用 `SurvivalSystem`**——维生中断统一由 `BotSession` 处理（D-062，勿重犯 P1-B 的重复调用）；</li>
 *   <li>世界修改必须走显式授权 + 预算（D-076/D-081），不得隐式改世界。</li>
 * </ul>
 */
public interface Job extends Task {

    /** 任务名（进决策日志）。 */
    String jobName();

    /** 人类可读进度（终态与定期健康输出用），如 `logs 4/8 trees 1/3`。 */
    String progressSummary();
}
