package com.dddgn.alice.task;

/**
 * 任务(执行层单元,设计文档 §4 的落地骨架)。
 * <p>
 * 一个任务 = 一个确定性行为链,由 BotSession 在主线程逐 tick 驱动。
 * 职责分工:
 * <ul>
 *   <li><b>任务层</b>(本包):编排「先做什么、后做什么」——如挖矿 → 拾取;</li>
 *   <li><b>动作层</b>({@code action/} 包):单动作状态机——如 {@code BotMiner} 挖掘;</li>
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

    enum Status {
        RUNNING, DONE, FAILED
    }
}
