package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.FarTravelHop;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * **机制 B：任务失败后"回安全区"的兜底**（`D-327` ①，2026-09-19 用户裁定执行）：
 * 任务失败条件达成后把 bot 送回**保护区（`SafeZoneData` 认领区块）里面**。
 *
 * <p><b>与机制 A 的分工（`D-327` 明确拆分，术语不许混）</b>：
 * <ul>
 *   <li>机制 A = **局部紧急逃离**（岩浆/水/着火/被围 ⇒ 瞬间脱离）：`SurvivalSystem` 的 8 格
 *       `REFUGE_RADIUS` + 8/8 逃生准备金 + `D-238` 可规划预检 —— **本类不碰它**；</li>
 *   <li>机制 B = **任务失败后回安全区**（本类）：**返回路线不设预算**（用户："不抵触 8 格的局部逃生，
 *       可能是局部逃生的后续动作"）—— 但**不是**"全局逃生"，那个命名是错的。</li>
 * </ul>
 *
 * <p><b>怎么走</b>：远距离用**一跳一跳逼近**（`FarTravelHop`：只读 `hasChunkAt` 采样已加载前沿 ⇒
 * 夹到边界内侧 + `GoalNearXZ`；实测远粗目标 `20000 节点 / 142~186 ms` vs 一跳 `161 节点 / 1 ms`），
 * 终点临近时改走**精确落脚**（在认领区块内找一个可站格）—— 因为"到达"的判据是
 * **脚位落在认领区块里**（`SafeZoneData.isClaimed`，纯认领集查询，不读方块）。
 *
 * <p><b>兜底语义（用户 2026-09-19 重新声明）</b>：**"就地固守"不是兜底** —— 它是"避免死亡的最保守行为"，
 * 勘测侧意见是**现在不做**（那等于把责任转接给玩家）。因此本任务**不新造固守行为**：
 * 返程本身走不到（`UNREACHABLE` / 预算 / 没有可站格 / 段超时）⇒ **如实报码**，bot 就**站定不动**
 * （= 今天已有的行为，作为**临时**兜底）。
 *
 * <p><b>触发</b>：`BotManager.complete(...)` 在任务以 `FAILED` 收尾、**且当前维度存在认领区、且 bot 在区外**
 * 时启动本任务（没有认领区 ⇒ 一字不变，回归零影响）。
 */
public final class SafeReturnTask implements Task {

    /** 段数上限（一跳段 + 末段）：8 跳 × ~170 格 ≈ 1300 格，够 300~1000 格量级。 */
    public static final int DEFAULT_MAX_ROUNDS = 12;

    /** 总 tick 上限（生产兜底不能无限跑）。 */
    public static final int DEFAULT_TOTAL_TICK_CAP = 20_000;

    /** 单"跳段"tick 上限。 */
    public static final int HOP_TICK_CAP = 6_000;

    /** 末段（精确落脚）tick 上限。 */
    public static final int FINAL_TICK_CAP = 1_200;

    /** 末段在认领区内找可站格的搜索半径（格）。 */
    public static final int STAND_SEARCH = 6;

    private final BotPlayer bot;
    private final int maxRounds;
    private final int totalTickCap;
    private final int hopTickCap;
    private final int finalTickCap;
    private final List<String> legCurve = new ArrayList<>();

    private PathRetryRunner runner;
    private String failure = "";
    private String note = "";
    private int rounds;
    private int totalTicks;
    private int legTicks;
    private int legStartDistance = -1;
    private boolean finalLeg;
    private BlockPos entryCell;
    private Status terminal;

    public SafeReturnTask(BotPlayer bot) {
        this(bot, DEFAULT_MAX_ROUNDS, DEFAULT_TOTAL_TICK_CAP, HOP_TICK_CAP, FINAL_TICK_CAP);
    }

    public SafeReturnTask(BotPlayer bot, int maxRounds, int totalTickCap, int hopTickCap,
                          int finalTickCap) {
        this.bot = bot;
        this.maxRounds = Math.max(1, maxRounds);
        this.totalTickCap = Math.max(1, totalTickCap);
        this.hopTickCap = Math.max(1, hopTickCap);
        this.finalTickCap = Math.max(1, finalTickCap);
    }

    /**
     * **兜底判决**（纯查询、零副作用）：任务失败后**该不该**启动返程？
     *
     * <p>门槛两条（缺一返回 false，值都不读方块）：① bot **不在**认领区块里；② 本维度**有**认领区块。
     * 之所以把它做成公开静态方法而不是埋在 `BotManager` 的私有分支里：**门禁要能断言它**
     * （无区 ⇒ false 是"今天行为一字不变"的判据；区内 ⇒ false 是"已经安全、不瞎跑"的判据）。
     *
     * @return true = 调用方应启动 {@link SafeReturnTask}
     */
    public static boolean shouldStart(ServerLevel level, BlockPos foot) {
        SafeZoneData zones = SafeZoneData.get(level.getServer());
        return !zones.isClaimed(level, foot) && zones.nearestClaimedCell(level, foot) != null;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(entryCell != null ? entryCell : bot.blockPosition());
    }

    @Override
    public Status tick() {
        if (terminal != null) {
            return terminal;   // D-178 幂等
        }
        totalTicks++;
        if (totalTicks > totalTickCap) {
            return fail("return_tick_limit", "total=" + totalTicks + " rounds=" + rounds);
        }
        ServerLevel level = bot.serverLevel();
        SafeZoneData zones = SafeZoneData.get(level.getServer());
        BlockPos foot = MovementHelper.footCell(level, bot);
        if (zones.isClaimed(level, foot)) {
            return done("returned:inside=" + foot.toShortString());
        }
        BlockPos entry = zones.nearestClaimedCell(level, foot);
        if (entry == null) {
            return fail("return_no_safe_zone", "claims=0 dim=" + level.dimension().location()
                    + " from=" + foot.toShortString());
        }
        entryCell = entry;
        if (runner == null) {
            if (rounds >= maxRounds) {
                return fail("return_round_limit", "rounds=" + rounds + " entry="
                        + entry.toShortString() + " from=" + foot.toShortString());
            }
            rounds++;
            legTicks = 0;
            int distance = FarWalkTask.distanceXZ(foot, entry);
            legStartDistance = distance;
            PathRequest request;
            if (level.hasChunkAt(entry) && distance <= FarTravelHop.DEFAULT_RADIUS + STAND_SEARCH) {
                // 末段：终点区块已加载 ⇒ 直取**认领区内**的一个可站格（精确目标）
                BlockPos stand = standableInsideZone(level, zones, foot);
                if (stand == null) {
                    return fail("return_no_standable_cell", "entry=" + entry.toShortString()
                            + " search=" + STAND_SEARCH + " from=" + foot.toShortString());
                }
                finalLeg = true;
                request = PathRequest.of(bot.getUUID().toString(), foot, stand, "walk-return");
                note = "final stand=" + stand.toShortString() + " from=" + foot.toShortString();
            } else {
                // 跳段：夹到已加载边界内侧（绝不读未加载区块；红线 D-132）
                FarTravelHop.Hop hop = FarTravelHop.compute(level, foot, entry,
                        FarTravelHop.DEFAULT_RADIUS, FarTravelHop.MARGIN);
                if (!hop.feasible()) {
                    return fail("return_stuck", hop.describe() + " entry=" + entry.toShortString()
                            + " distance=" + distance);
                }
                finalLeg = false;
                request = FarTravelHop.request(bot.getUUID().toString(), foot, hop, "walk-return");
                note = "hop " + hop.describe() + " from=" + foot.toShortString();
            }
            legCurve.add("leg=" + rounds + (finalLeg ? " final " : " hop ") + note);
            BotLog.info("[SafeReturn] leg={} kind={} distance={} from={} entry={} {}",
                    rounds, finalLeg ? "final" : "hop", distance, foot.toShortString(),
                    entry.toShortString(), note);
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "safereturn-" + rounds);
        }
        legTicks++;
        if (legTicks > (finalLeg ? finalTickCap : hopTickCap)) {
            runner.cancel();
            runner = null;
            return fail("return_leg_timeout", "leg=" + rounds + " final=" + finalLeg
                    + " ticks=" + legTicks);
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        PathExecutionResult result = runner.result();
        runner = null;
        if (state == PathRetryRunner.State.FAILED) {
            // ⭐ **兜底**（用户口径）：回不去就**如实报码**，bot 站定不动 —— 不新造"就地固守"行为
            return fail("return_unreachable", "leg=" + rounds + " final=" + finalLeg + " code="
                    + (result == null ? "-" : result.failureCode()) + " status="
                    + (result == null ? "-" : result.status()));
        }
        BlockPos now = MovementHelper.footCell(level, bot);
        if (zones.isClaimed(level, now)) {
            return done("returned:inside=" + now.toShortString() + " rounds=" + rounds);
        }
        int after = FarWalkTask.distanceXZ(now, entry);
        if (after >= legStartDistance) {
            return fail("return_no_progress", "leg=" + rounds + " final=" + finalLeg
                    + " from=" + legStartDistance + " to=" + after + " foot=" + now.toShortString());
        }
        return Status.RUNNING;   // 下一 tick 再算下一段（更靠近 ⇒ 下一段更短）
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

    /** 已走过的段数（夹具/日志读）。 */
    public int rounds() {
        return rounds;
    }

    /** 每段一行（跳 / 末段 + hop 描述）。 */
    public List<String> legCurve() {
        return List.copyOf(legCurve);
    }

    public int totalTicks() {
        return totalTicks;
    }

    /**
     * 在**认领区块内**找一个可站的脚位（离 bot 最近；**只读已加载区块**）。
     *
     * <p>为什么要这一步：目的地的落点必须真的站得进去（村庄中心可能是屋顶、坐标可能在水里），
     * 而"进入认领区块"只是**到达判据**。搜索顺序：以入口格为心、Chebyshev ≤ {@link #STAND_SEARCH}，
     * 每列从高到低取第一个可站格（`canStandCentered`），列间取离 bot 最近者。确定性：与遍历顺序无关
     * （比较的是距离，平手时取先遇到的 ⇒ 固定顺序遍历 ⇒ 可复现）。
     */
    private BlockPos standableInsideZone(ServerLevel level, SafeZoneData zones, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -STAND_SEARCH; dx <= STAND_SEARCH; dx++) {
            for (int dz = -STAND_SEARCH; dz <= STAND_SEARCH; dz++) {
                for (int dy = 3; dy >= -4; dy--) {
                    BlockPos cell = entryCell.offset(dx, dy, dz);
                    if (!zones.isClaimed(level, cell)) {
                        continue;   // 落点必须在区内（否则"进区"这个判据不成立）
                    }
                    if (!level.hasChunkAt(cell)) {
                        continue;   // ⚠️ 先问加载状态再读方块（D-331/D-337 同一纪律）
                    }
                    if (!MovementHelper.canStandCentered(level, cell)) {
                        continue;
                    }
                    double d = cell.distSqr(from);
                    if (d < bestDist) {
                        bestDist = d;
                        best = cell;
                    }
                    break;   // 本列取最高的可站格
                }
            }
        }
        return best;
    }

    private Status done(String why) {
        terminal = Status.DONE;
        note = why;
        BotLog.info("[SafeReturn] completed bot={} rounds={} ticks={} {}", bot.getName().getString(),
                rounds, totalTicks, why);
        return terminal;
    }

    private Status fail(String code, String detail) {
        terminal = Status.FAILED;
        failure = code + ":" + detail;
        BotLog.warn("[SafeReturn] failed bot={} rounds={} ticks={} code={} {} ——"
                        + " **如实报码、站定不动**（用户口径：固守不是兜底，返程失败就停在原地等指令）",
                bot.getName().getString(), rounds, totalTicks, code, detail);
        return terminal;
    }
}
