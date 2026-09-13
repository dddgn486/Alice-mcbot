package com.dddgn.alice.task;

import com.dddgn.alice.bot.TaskFailureReport;

/**
 * 任务(执行层单元,设计文档 §4 的落地骨架)。
 * <p>
 * 一个任务 = 一个确定性行为链,由 BotSession 在主线程逐 tick 驱动。
 * 职责分工:
 * <ul>
 *   <li><b>任务层</b>(本包):编排「先做什么、后做什么」——如挖矿 → 拾取;</li>
 *   <li><b>动作层</b>({@code action/} 包):单动作状态机——如 {@code MineBlockRunner} 挖掘;</li>
 *   <li><b>感知层</b>({@code perception/} 包):任务运行时的世界状态查询
 *       (目标校验、掉落物定位)——Task 构造时注入,即「感知驱动执行」的接线点。</li>
 * </ul>
 * 决策层(外部 LLM / 规则)只负责「创建什么任务」,不干预任务内部执行;
 * 本阶段测试工具直接创建任务,LLM 决策接入点见 docs/EXECUTION_FRAMEWORK.md。</p>
 */
public interface Task {

    /** 本任务的目标(客户端高亮与服务端校验共用)。 */
    TaskTarget target();

    /** 主线程推进一 tick;返回当前状态。 */
    Status tick();

    /** 失败原因(仅 FAILED 时有意义)。 */
    String failureReason();

    /**
     * **终止理由**（DONE 与 FAILED 都有；非 Job 任务默认为空串）。
     *
     * <p>为什么提到 `Task` 层：D-134 起终态记录会把"任务自己报的终止理由"落档（`terminalReason`），
     * 原先只对 `Job` 取值 ⇒ 普通任务（如请示演示、诊断类）永远是空串。
     * 现在任何任务都能报 —— `Job` 仍然是抽象要求（它必须给理由）。
     */
    default String terminalReason() {
        return "";
    }

    /**
     * 任务身份（D-082）：世界写入授权与失败归因里的"谁"。
     * 默认取类名；需要更短/更稳定的名字时覆写（如 Job 用 {@code lumber}）。
     */
    default String taskName() {
        return getClass().getSimpleName();
    }

    /**
     * **此刻终止本任务是否安全**（K-3，2026-09-13，对齐 Baritone `Movement.safeToCancel` 的用法）。
     *
     * <p>为什么任务层也要报这个：Baritone 的 `PathExecutor:287` 在"不安全时**不许取消**"，
     * 而取消的发起者是**任务/命令层**（`/alice stop`、任务被替换）。驱动寻路的任务因此要把
     * "当前段是否安全"透传上来（默认 true = 不阻挠取消）。
     *
     * <p>空中的任务**一律**视为不安全：那是与任务类型无关的硬事实
     * （`BotSession` 会用 `task.safeToCancel() && bot.onGround()` 一起判定）。
     */
    default boolean safeToCancel() {
        return true;
    }

    /** 事实型失败报告；未实现领域详情的任务默认返回空报告。 */
    default TaskFailureReport failureReport() {
        return new TaskFailureReport(failureReason(), "unknown", "", null, null);
    }

    enum Status {
        RUNNING, DONE, FAILED
    }
}
