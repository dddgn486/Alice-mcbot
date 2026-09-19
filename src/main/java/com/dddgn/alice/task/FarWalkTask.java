package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.FarTravelHop;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * **远距离旅行任务**（`D-337 附注二` 的执行侧）：把 bot 送到远处目标的 **XZ 邻域**，
 * 做法是**反复"夹到已加载边界内的一跳"**（{@link FarTravelHop}），而不是"朝几百格外的目标一次搜到底"。
 *
 * <p><b>为什么必须分段（实测依据）</b>：一次性粗目标在加载半径外**永远不可达** ⇒ A\* 把已加载区整片展开完
 * 才收手（400 格目标 = `nodes=20000` = 预算上限 / 147 ms）；夹成 168 格的一跳后 = **161 节点 / 1 ms**
 * 且 `REACHED`（`far_path_bench` 同一轮内 A/B 实测）。⇒ 远距离的正确形状是"**一跳一跳逼近**"，
 * 新区块由 bot **自己的移动**自然加载（合法的世界推进），内核始终不加载区块（红线 `D-132`）。
 *
 * <p><b>到达口径（刻意粗）</b>：本任务只保证"进入目标的 XZ {@code arriveRadius} 内"，因为
 * **目标格常常根本不可站**（村庄中心是屋顶、坐标落在水里/墙里）。要精确站到某一格，请在本任务
 * `DONE` 之后接一次 {@link WalkToTask}（那时目标区块早被自己的移动加载过了）——
 * 这就是 Baritone 的 `GoalNear` → `GoalBlock` 两段式。`arriveRadius` 会被抬到
 * ≥ {@link FarTravelHop#DEFAULT_RADIUS}（否则"进了跳的目标区却不算到达"会空转）。
 *
 * <p><b>确定性护栏</b>：① 每跳有 tick 上限；② 总 tick 上限；③ 跳数上限；④ **单调性判据**
 * （一跳走完必须真的更近，否则报 `far_walk_no_progress` 而不是继续空转）。
 */
public final class FarWalkTask implements Task {

    /** 到达半径（格，XZ 欧氏）：默认 = 一跳目标区半径（进区即算到达）。 */
    public static final int DEFAULT_ARRIVE_RADIUS = FarTravelHop.DEFAULT_RADIUS;

    /** 跳数上限（默认 8 跳：8 × ~170 格 ≈ 1300 格，覆盖用户给的 300~1000 格量级）。 */
    public static final int DEFAULT_MAX_HOPS = 8;

    /** 单跳 tick 上限（一跳 ~170 格 ≈ 650 tick；6 倍余量）。 */
    public static final int DEFAULT_HOP_TICK_CAP = 4_000;

    /** 总 tick 上限（1300 格 ≈ 5000 tick；4 倍余量）。 */
    public static final int DEFAULT_TOTAL_TICK_CAP = 20_000;

    private final BotPlayer bot;
    private final BlockPos target;
    private final int arriveRadius;
    private final int maxHops;
    private final int hopTickCap;
    private final int totalTickCap;
    private final List<String> hopCurve = new ArrayList<>();

    private PathRetryRunner runner;
    private String failure = "";
    private String note = "";
    private int hops;
    private int hopTicks;
    /**
     * 本跳**规划时**到目标的距离（单调性判据的比较基准）。
     *
     * <p>⚠️ 不能用"本 tick 顶部的距离"—— bot 在整跳里一直在移动，等本跳走完时那个值早就变成
     * "已经快到目标"了（2026-09-19 实测：168 格那一跳走完后 `after(140) >= distance(140)` ⇒
     * 误报 `far_walk_no_progress`）。
     */
    private int hopStartDistance = -1;
    private int totalTicks;
    /** D-178：终态后必须幂等（不再碰任何子任务/世界）。 */
    private Status terminal;

    public FarWalkTask(BotPlayer bot, BlockPos target) {
        this(bot, target, DEFAULT_ARRIVE_RADIUS, DEFAULT_MAX_HOPS,
                DEFAULT_HOP_TICK_CAP, DEFAULT_TOTAL_TICK_CAP);
    }

    public FarWalkTask(BotPlayer bot, BlockPos target, int arriveRadius, int maxHops,
                       int hopTickCap, int totalTickCap) {
        this.bot = bot;
        this.target = target.immutable();
        // 到达半径不许小于"一跳目标区半径"：否则进入本跳目标区后既不算到达、又原地再规划 ⇒ 空转
        this.arriveRadius = Math.max(arriveRadius, FarTravelHop.DEFAULT_RADIUS);
        this.maxHops = Math.max(1, maxHops);
        this.hopTickCap = Math.max(1, hopTickCap);
        this.totalTickCap = Math.max(1, totalTickCap);
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    @Override
    public Status tick() {
        if (terminal != null) {
            return terminal;   // D-178 幂等
        }
        totalTicks++;
        if (totalTicks > totalTickCap) {
            return fail("far_walk_tick_limit", "total=" + totalTicks + " distance=" + distanceToTarget());
        }
        ServerLevel level = bot.serverLevel();
        BlockPos feet = MovementHelper.footCell(level, bot);
        int distance = distanceXZ(feet, target);
        if (distance <= arriveRadius) {
            return done("arrived:" + distance);
        }
        if (runner == null) {
            if (hops >= maxHops) {
                return fail("far_walk_hop_limit", "hops=" + hops + " distance=" + distance);
            }
            FarTravelHop.Hop hop = FarTravelHop.compute(level, feet, target,
                    FarTravelHop.DEFAULT_RADIUS, FarTravelHop.MARGIN);
            if (!hop.feasible()) {
                // 边界就在脚下（视野极小 / 加载未推进）：如实失败，**绝不**去读未加载区块硬凑
                return fail("far_walk_no_reach", hop.describe() + " distance=" + distance);
            }
            PathRequest request = FarTravelHop.request(bot.getUUID().toString(), feet, hop, "far-walk");
            note = "hop=" + hops + " " + hop.describe();
            hopCurve.add("hop=" + hops + " distance=" + distance + " " + hop.describe());
            BotLog.info("[FarWalk] hop={} distance={} from={} target={} {}", hops, distance,
                    feet.toShortString(), target.toShortString(), hop.describe());
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "farwalk-hop" + hops);
            hopTicks = 0;
            hopStartDistance = distance;
            hops++;   // ← **已开始的跳数**（含在飞的这一跳）
        }
        hopTicks++;
        if (hopTicks > hopTickCap) {
            if (runner != null) {
                runner.cancel();
                runner = null;
            }
            return fail("far_walk_hop_timeout", "hop=" + hops + " ticks=" + hopTicks
                    + " distance=" + distance);
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        BlockPos now = MovementHelper.footCell(level, bot);
        int after = distanceXZ(now, target);
        if (state == PathRetryRunner.State.FAILED) {
            PathExecutionResult result = runner.result();
            runner = null;
            return fail("far_walk_hop_failed", "hop=" + hops + " code="
                    + (result == null ? "-" : result.failureCode()) + " status="
                    + (result == null ? "-" : result.status()));
        }
        runner = null;
        if (after >= hopStartDistance) {
            return fail("far_walk_no_progress", "hop=" + hops + " from=" + hopStartDistance
                    + " to=" + after + " foot=" + now.toShortString());
        }
        if (after <= arriveRadius) {
            return done("arrived:" + after);
        }
        return Status.RUNNING;   // 下一 tick 算下一跳
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public String terminalReason() {
        return terminal == Status.DONE ? note : failure;
    }

    @Override
    public boolean safeToCancel() {
        return runner == null || runner.safeToCancel();
    }

    /**
     * **已开始的跳数**（含"在飞"的那一跳；夹具/日志读）。
     *
     * <p>为什么不是"已完成的跳数"：到达可能在**上一跳还在飞**的时候就被本 tick 顶部的到达判据捕获
     * （那时正在走的这一跳确实已经走过了）—— 按"完成"计数会漏掉它（实测：300 格实走 2 跳只报 1）。
     */
    public int hops() {
        return hops;
    }

    /** 每跳一行（距离 + hop 描述），供夹具落进曲线。 */
    public List<String> hopCurve() {
        return List.copyOf(hopCurve);
    }

    public int totalTicks() {
        return totalTicks;
    }

    private int distanceToTarget() {
        return distanceXZ(MovementHelper.footCell(bot.serverLevel(), bot), target);
    }

    /** 目标的 XZ 欧氏距离（四舍五入）：与 {@link FarTravelHop} 的度量一致（日志/判据共用）。 */
    public static int distanceXZ(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private Status done(String why) {
        terminal = Status.DONE;
        note = why;
        BotLog.info("[FarWalk] completed bot={} target={} hops={} ticks={} {}", bot.getName().getString(),
                target.toShortString(), hops, totalTicks, why);
        return terminal;
    }

    private Status fail(String code, String detail) {
        terminal = Status.FAILED;
        failure = code + ":" + detail;
        BotLog.warn("[FarWalk] failed bot={} target={} hops={} ticks={} code={} {}",
                bot.getName().getString(), target.toShortString(), hops, totalTicks, code, detail);
        return terminal;
    }
}
