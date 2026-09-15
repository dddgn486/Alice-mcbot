package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * **场景夹具的共用原语**（D-220 附注）。
 *
 * <p>背景：电池侧（{@link PathingRegressionTask}）与物品侧（{@link PathSessionDiagnosticTask}）
 * 实现的是**同两个夹具** —— 「计划前方封路」与「定向平移 bot」。2026-09-15 实测发现
 * **这两份实现已经分叉并造成过假覆盖**，而且分叉**没有任何编译期信号**：
 * <ol>
 *   <li><b>时机基准</b>：电池侧比的是**任务级** {@code ticks}（从不按场景复位）⇒ 声明 `30`
 *       实际是"第一个执行 tick"；物品侧比的是**本趟运行**的 {@code ticks} ⇒ `30` 真实生效。
 *       同一行注释、同一常量，行为完全不同。</li>
 *   <li><b>封路目标格</b>：电池侧用 {@link MovementHelper#footCell}（D-105 规范脚位），
 *       物品侧用 {@code bot.blockPosition()}（实体所在格）—— 在箱子 0.875 / 底半砖 0.5
 *       这类**非满高支撑**上两者会取到不同格。</li>
 *   <li><b>放弃策略</b>：两边都是静默降级（假装夹具做过），于是"没测到"被读成 PASS。</li>
 * </ol>
 *
 * <p>本类只收**能共用的部分**（目标格计算 + 未生效的可判读串）。**不共用**的是各自主持有的
 * 时机计数器与终态策略 —— 但按 D-220，两边都必须**响亮**：不得静默降级。
 */
public final class FixtureScript {

    /** 找不到合法落点时的宽限 tick 数（两边共用；到点仍未生效 ⇒ 如实上报）。 */
    public static final int GIVE_UP_GRACE_TICKS = 40;

    /** 未生效夹具在日志/结果里的字段名（两侧共用同一串 ⇒ grep 一处即可）。 */
    public static final String NOT_FIRED_FIELD = "FIXTURE_NOT_FIRED";

    private FixtureScript() {
    }

    /**
     * 封路方案：`target` = 要放石头的格；`pathIndex`/`pathLength` = 下决定时 bot 在投影路径上的位置
     * （**可判读读数**：没有它就无法区分"夹具生效了"与"路径根本不够长"）。
     */
    public record WallPlan(BlockPos target, int pathIndex, int pathLength) {
    }

    /**
     * **封路目标格**：投影脚位路径上 bot 当前所在格的**前方第 2 格**。
     *
     * <p>脚位一律用 {@link MovementHelper#footCell}（D-105：非满高支撑下脚位格必须与规划层一致）。
     *
     * @param projectedPath 会话的投影脚位路径（index 0 = 计划起点）
     * @return 该格**当前是空气**时返回方案；否则 {@code null}
     *         —— bot 不在路径上 / 路径不足 3 格 / 目标格已被占（调用方下一 tick 重试，
     *         到 {@link #GIVE_UP_GRACE_TICKS} 仍未成功就必须如实上报，不许静默放过）。
     */
    public static WallPlan wallPlan(BotPlayer bot, List<BlockPos> projectedPath) {
        if (projectedPath == null || projectedPath.isEmpty()) {
            return null;
        }
        int position = projectedPath.indexOf(MovementHelper.footCell(bot.serverLevel(), bot));
        int target = position >= 0 ? position + 2 : -1;
        if (target <= 0 || target >= projectedPath.size()) {
            return null;
        }
        BlockPos wall = projectedPath.get(target);
        // 已是实体方块 ⇒ 这次封路**没有意义**（前方本来就不通）⇒ 当"未生效"处理，而不是假装封过
        return bot.serverLevel().getBlockState(wall).isAir()
                ? new WallPlan(wall, position, projectedPath.size()) : null;
    }

    /**
     * 「声明了夹具却没动手」的可判读后缀（空列表 ⇒ 空串，便于直接拼接）。
     *
     * <p>例：{@code /FIXTURE_NOT_FIRED=[wall, disturb]}。
     */
    public static String notFired(List<String> missing) {
        return missing == null || missing.isEmpty() ? "" : "/" + NOT_FIRED_FIELD + "=" + missing;
    }
}
