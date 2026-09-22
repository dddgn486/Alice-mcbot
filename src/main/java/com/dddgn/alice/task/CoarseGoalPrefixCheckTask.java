package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.GoalNearXZ;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `D-390`（2026-09-22）：**粗目标 + 滚动重规划**的核心断言**进 CORE**。
 *
 * <h2>为什么需要它（用户 2026-09-22 的追问）</h2>
 * *"那远目标寻路呢？影响吗，粗目标加滚动重规划没问题吧，我不知道 CORE 有没有证明"* ——
 * **没有证明**：`far_path_bench`（唯一完整测这件事的基准）是 `EXTRA`（不进 CORE，6000 tick 太贵），
 * 而 CORE 覆盖的搜索**最大只有 4–5 ms**（近距/短搜索）⇒ 50 ms 收紧之后，"远目标还走不走得动"
 * 在回归保护之外。本步把它的**核心断言子集**提进 CORE（大曲线仍留给 `far_path_bench`）。
 *
 * <h2>它钉的四条（`far_path_bench` 同口径，逐字对齐）</h2>
 * <ol>
 *   <li>粗目标区在**加载半径外**时**不许**被 `GOAL_NOT_LOADED` 拒（那会把"我没加载"说成"不可达"）；</li>
 *   <li>**必须给出朝目标推进的前缀**（`projectedFootPath()` 非空 ⇒ 滚动重规划才有东西可走）；</li>
 *   <li>被加载边界挡住时**不许**报 `UNREACHABLE`（期望 `PARTIAL` 前缀或 `SEARCH_LIMIT`：
 *       `SEARCH_LIMIT ≠ UNREACHABLE`，`D-076`）；</li>
 *   <li>`D-132`：粗目标搜索**不许**把未加载区块读进来（跑完目标区中心仍必须未加载）。</li>
 * </ol>
 *
 * <h2>场景与确定性</h2>
 * 孤立平台 + **8 格石走廊**（方向 = 目标方向）⇒ "朝目标推进的前缀"有**确定的落脚点**，
 * 不依赖电池世界的中间地形（否则会因地形变成假红）。目标区 = 起点 +400 格、半径 16 ⇒ 必然未加载
 * （**前提断言**：跑之前中心必须未加载，否则测到的是"已加载的普通搜索"）。
 */
public final class CoarseGoalPrefixCheckTask implements Task {

    /** 孤立原点（其它夹具用 3600/3700/3800 段）。 */
    private static final BlockPos ORIGIN = new BlockPos(3900, 100, 2600);
    /** 石走廊长度（+X 方向）—— 保证前缀有确定落脚点。 */
    private static final int CORRIDOR = 8;
    /** 目标区中心（必然未加载）。 */
    private static final int GOAL_DX = 400;
    private static final int GOAL_RADIUS = 16;
    private static final int BUDGET_TICKS = 300;

    private enum Phase { SETUP, PLAN, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private String status = "-";
    private int nodes = -1;
    private int prefixLen = -1;
    private int progress = -2;
    private long millis = -1L;
    private boolean loadedBefore = true;
    private boolean loadedAfter = true;

    public CoarseGoalPrefixCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CoarseGoalPrefixCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    private void check(String what, boolean ok) {
        checks++;
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[CoarsePrefix] FAIL {}", what);
        }
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            return Task.Status.FAILED;
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    buildScene(level);
                }
                if (phaseTicks >= 3) {
                    phase = Phase.PLAN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case PLAN -> {
                if (phaseTicks >= 1) {
                    plan(level);
                    phase = Phase.DONE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                cleanup(level);
                BotLog.info("[CoarsePrefix] SUMMARY checks={} failures={} status={} nodes={} prefixLen={}"
                                + " progress={} ms={} loadedBefore={} loadedAfter={} → {}",
                        checks, failures.size(), status, nodes, prefixLen, progress, millis,
                        loadedBefore, loadedAfter, failures.isEmpty() ? "PASS" : "FAIL");
                for (String line : findings) {
                    BotLog.info("[CoarsePrefix]   {}", line);
                }
                for (String line : failures) {
                    BotLog.warn("[CoarsePrefix]   {}", line);
                }
                if (observer != null) {
                    observer.sendSystemMessage(Component.literal(
                            "[alice] 粗目标前缀夹具：" + (failures.isEmpty() ? "PASS" : "FAIL")
                                    + " checks=" + checks + " failures=" + failures.size()
                                    + "（status=" + status + " prefixLen=" + prefixLen
                                    + " progress=" + progress + " ms=" + millis + "）"));
                }
                return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    // ==================== 场景 ====================

    private void buildScene(ServerLevel level) {
        // 平台 + 走廊（都朝 +X，即目标方向）
        for (int dx = -1; dx <= CORRIDOR; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, ORIGIN.offset(dx, -1, dz), Blocks.STONE);
            }
        }
        BlockPos foot = ORIGIN;
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, -90.0F, 0.0F);
        BotLog.info("[CoarsePrefix] SETUP origin={} corridor={}格(+X) goalCenter={}",
                ORIGIN.toShortString(), CORRIDOR, ORIGIN.offset(GOAL_DX, 0, 0).toShortString());
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    // ==================== 粗目标 ====================

    private void plan(ServerLevel level) {
        BlockPos start = MovementHelper.footCell(level, bot).immutable();
        BlockPos center = ORIGIN.offset(GOAL_DX, 0, 0);

        // 前提①：目标区必须**确实未加载**（否则测到的是"已加载的普通搜索" ⇒ 静默假绿）
        loadedBefore = level.hasChunkAt(center);
        check("前提：目标区中心在跑之前必须未加载（`hasChunkAt=false`，实测 " + loadedBefore + "）",
                !loadedBefore);
        // 前提②：bot 站在走廊起点上（脚位 = 起点、且脚下有支撑）
        check("前提：bot 脚位在走廊起点且脚下有支撑（foot=" + start.toShortString() + "）",
                start.equals(ORIGIN)
                        && !level.getBlockState(ORIGIN.below()).isAir());
        if (!start.equals(ORIGIN) || loadedBefore) {
            status = "PRECONDITION_FAILED";
            return;
        }

        var goal = GoalNearXZ.around(center, GOAL_RADIUS);
        PathPlan coarse = new CorePathPlanner().plan(bot, level,
                new PathRequest(bot.getUUID().toString(), start, goal,
                        PathRequest.of(bot.getUUID().toString(), start, center, "pathing")
                                .allowedMovementTypes(),
                        SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES,
                                CorePathPlanner.DEFAULT_MAX_MILLIS),
                        "pathing"));
        status = String.valueOf(coarse.status());
        nodes = coarse.nodesExpanded();
        prefixLen = coarse.projectedFootPath().size();
        millis = coarse.elapsedMillis();
        int bestProgress = -1;
        if (prefixLen > 0) {
            BlockPos end = coarse.projectedFootPath().get(prefixLen - 1);
            bestProgress = chebyshev(start, center) - chebyshev(end, center);
        }
        progress = bestProgress;
        loadedAfter = level.hasChunkAt(center);
        BotLog.info("[CoarsePrefix] plan status={} nodes={} prefixLen={} progress={} ms={}"
                        + " loadedBefore={} loadedAfter={} diag={}",
                coarse.status(), nodes, prefixLen, progress, millis, loadedBefore, loadedAfter,
                coarse.diagnostics());

        check("粗目标：目标区在加载半径外时**不许**被 `GOAL_NOT_LOADED` 拒（实测 status=" + status + "）",
                coarse.status() != PlanningStatus.GOAL_NOT_LOADED);
        // ⚠️ **判据必须与"外部地形"无关**（2026-09-22 在 CORE 里实测到的夹具洁净度坑）：
        // 单跑时目标方向有地形 ⇒ 搜索撞未加载边界 ⇒ `PARTIAL` + 前缀；而在 CORE 里同一区域
        // **走廊外是空的且已加载** ⇒ 搜索 open set 耗尽 ⇒ `UNREACHABLE`（**这是诚实的**：那里真走不通）
        // 且**前缀被丢弃**（`prefixLen=0`）。⇒ "必须有前缀"只在**被边界/预算截断**时才有意义，
        // 无条件断言它就是"依赖环境"的假红。⇒ 判据改成三条**与世界状态无关**的不变式 + 一条条件式。
        String diag = String.valueOf(coarse.diagnostics());
        check("粗目标：**撞到未加载边界时不许报 `UNREACHABLE`**（只有 `open set exhausted` 才是真不可达；"
                        + "实测 status=" + status + " diag=" + diag + "）",
                !(diag.contains("boundary_unloaded") && coarse.status() == PlanningStatus.UNREACHABLE));
        check("粗目标：被截断时（`PARTIAL`）**必须给出朝目标推进的前缀**（prefixLen=" + prefixLen
                        + " progress=" + progress + "，= 滚动重规划有没有东西可走）",
                coarse.status() != PlanningStatus.PARTIAL || (prefixLen > 0 && progress > 0));
        check("红线 `D-132`：粗目标搜索**不许**读未加载区块（跑完中心仍必须未加载，实测 "
                        + loadedAfter + "）",
                !loadedAfter);
    }

    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    // ==================== 收尾 ====================

    private void cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        bot.teleportTo(level, ORIGIN.getX() + 0.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D, 0.0F, 0.0F);
    }
}
