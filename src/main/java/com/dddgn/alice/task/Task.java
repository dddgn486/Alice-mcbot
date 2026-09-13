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

    /**
     * **任务生命周期契约（D-178，2026-09-13 服务端崩溃后补写的基层契约）**。
     *
     * <p>1. **终态后必须幂等**：`tick()` 一旦返回过 `DONE`/`FAILED`，之后再被调用**必须**返回同一状态、
     * 不得抛异常、不得再碰任何子任务/世界。本契约在**会话**侧由 `BotSession` 保证（终态即
     * `complete() → clearTask()`，之后不再 tick），在**测试台**侧由回归电池集中断言
     * （每个终态步任务补 tick 两次，记 `idempotent=`）。
     * <p>为什么写进接口：2026-09-13 `MineTask.tickRestore` 在"终态后被多 tick 一次"时 NPE
     * （`restoreTask` 已置 null 而 `phase` 仍是 RESTORE）**打死服务端 tick 循环**；同型 NPE 在
     * 2026-09-06 也崩过一次（`MovementSequenceWalkTask.movements`）。凡是**直接 tick 任务**的
     * 调用方（夹具、诊断任务、Job 内部）都必须自己保证"终态即不再 tick"。
     * <p>2. **子任务字段置 null 时必须同步推进状态机**（否则"状态仍在指向它、字段已空"就是上面那个 NPE）。
     *
     * <p>主线程推进一 tick；返回当前状态。
     *
     * @return 当前状态；实现方不得在终态后返回 `RUNNING`
     */
    Status tick();

    /**
     * **是否自检/夹具任务**（D-189，用户实测反馈："测试完 bot 就自己跑去伐木"）。
     *
     * <p>为什么需要：决策层在**任务终态**会被触发一次，LLM 常选 `start_job`（实测就是 `region_lumber`）
     * ⇒ 每次自检结束（尤其**失败**后）bot 就跑去做生产作业，把测试场地占住、把人看懵。
     * 项目早有同类裁定（`DecisionEvents.record` 注释："自检窗口内只记录不通知 —— 检具不该在生产侧
     * 留下决策痕迹"），但**终态触发**当时没被覆盖，这里补上：自检任务开始即**暂停决策层**。
     *
     * <p>默认按命名约定识别（`*CheckTask`）——所有既有夹具都符合；需要例外时覆写本方法。
     */
    default boolean isSelfCheck() {
        return getClass().getSimpleName().endsWith("CheckTask");
    }

    /** 事实型失败报告；未实现领域详情的任务默认返回空报告。 */
    default TaskFailureReport failureReport() {
        return new TaskFailureReport(failureReason(), "unknown", "", null, null);
    }

    enum Status {
        RUNNING, DONE, FAILED
    }
}
