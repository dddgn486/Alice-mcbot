package com.dddgn.alice.task;

import java.util.List;

/**
 * **任务树节点**（S1 事实层 / D-136）：给汇报与决策层看的**只读摘要**。
 *
 * <p>为什么需要：在此之前只有 `BotSession` 的顶层任务可见（"正在跑 RegionLumberJob"），
 * 而**内层在干什么**（在内嵌的 `LumberJob` 砍哪棵树、卡在哪个阶段、试了几次）完全不可见 ——
 * 于是"汇报当前完整情况"根本无从谈起。这里把 Job → 内嵌子任务 → 阶段 摊平成树。
 *
 * <p>纪律：**只读、只放事实**（kind/target/phase/ticks）。不放执行器内部状态、不放坐标细节的推导过程，
 * 更不放"下一步该干什么"（那是决策层的事）。
 */
public record TaskNode(
        String kind,
        String target,
        String phase,
        int ticks,
        String progress,
        String lastFailure,
        List<TaskNode> children
) {

    public TaskNode {
        kind = kind == null ? "unknown" : kind;
        target = target == null ? "-" : target;
        phase = phase == null ? "-" : phase;
        progress = progress == null ? "" : progress;
        lastFailure = lastFailure == null ? "" : lastFailure;
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** 一行摘要（`kind@target[phase] ticks=..`），子节点缩进跟随。 */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append(kind);
        if (!"-".equals(target)) {
            builder.append('@').append(target);
        }
        builder.append('[').append(phase).append(']');
        if (ticks > 0) {
            builder.append(" ticks=").append(ticks);
        }
        if (!progress.isBlank()) {
            builder.append(' ').append(progress);
        }
        if (!lastFailure.isBlank()) {
            builder.append(" lastFailure=").append(lastFailure);
        }
        for (TaskNode child : children) {
            builder.append(" > ").append(child.describe());
        }
        return builder.toString();
    }

    /** 树的 JSON 化（Gson 序列化用；字段名与 §3 契约一致）。 */
    public static TaskNode leaf(String kind, String target, String phase, int ticks, String progress) {
        return new TaskNode(kind, target, phase, ticks, progress, "", List.of());
    }

    /**
     * 同上，但带**该子阶段最近一次失败**（M4b）。
     *
     * <p>口径（唯一）：`lastFailure` 只放**该子任务自己**的一行事实
     * （{@link com.dddgn.alice.bot.TaskFailureReport#oneLine()}，形如 `no_suitable_tool@SCAN`），
     * 不放 Job 的推导、不放建议；子任务成功或被重建时**清空**（不留 stale）。
     */
    public static TaskNode leaf(String kind, String target, String phase, int ticks, String progress,
                                String lastFailure) {
        return new TaskNode(kind, target, phase, ticks, progress, lastFailure, List.of());
    }
}
