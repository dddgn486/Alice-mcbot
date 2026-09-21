package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.BreakAndEnterExecution;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * ⭐⭐⭐ **`G2` 完备性差集（`D-374` 事故的结构性补救）**—— 电池步 `edge_completeness`（EXTRA）。
 *
 * <p><b>为什么需要它（这是唯一能发现"未知缺口"的判据）</b>：现有全部门禁只保证**健全性**
 * （*可规划 ⇒ 可执行*：`MovementSpec.validateDisplacement` 硬抛、K-4、`toSpec` 契约）。
 * **没有任何东西**保证**完成性**：*执行器/物理允许的移动 ⇒ 边生成器必须给出这条边*。
 * ⇒ `D-374` 那种缺口（一个目的地既不是 TRAVERSE 也不是 BREAK_AND_ENTER）**永远不会变红**，
 * 只能靠真人踩到（那次是用户"推一下 + 只挖一格"才发现）。
 *
 * <p><b>做法：直接比"边集"，不跑搜索</b>（比"逐格寻路"强很多）：
 * <ol>
 *   <li><b>应有边（模型侧）</b>：只调 {@link MovementHelper} 的**局部谓词**（它们正是执行器工厂
 *       校验用的同一批谓词，**不经过边生成器**）：
 *       `canTraverse` ⇒ 期望 `TRAVERSE`/`DIAGONAL`；`canAscend` ⇒ `ASCEND`；`canDescend` ⇒ `DESCEND`；
 *       「目的地有支撑 + 有 blocker + 全可破坏」⇒ `BREAK_AND_ENTER`（与
 *       {@code BreakAndEnterExecutionFactory.validate} 同口径，含 `collectBlockers` 这同一个函数）；</li>
 *   <li><b>实际边（生成器侧）</b>：对同一格直接调
 *       {@link SurfaceMovementProvider#appendCandidates}（生产用的那一个实现，请求用
 *       **允许全集** = `PathRequest.withWorldModification`）；</li>
 *   <li><b>差集</b>：`应有 − 实际` = **缺口**（判红，逐条打印 `type from→to`）；`实际 − 应有` 只计数
 *       （生成器**额外**的能力如 `PILLAR`/`FALL`/`PLACE_STEP`/`DOWNWARD`/`BREAK_AND_TRAVERSE`
 *       不在模型里，是**正常**的；它们的"可执行性"由 K-4 网兜底）。</li>
 * </ol>
 * ⇒ 零搜索、零 A1（`SearchTickBudget`）干扰、确定性、毫秒级 —— 覆盖整个场景的**每一格 × 每一方向**。
 *
 * <p><b>场景（§6.9.1 三条前提写下来 + 自断言）</b>：
 * <ol>
 *   <li><b>几何盒</b>：孤立点 {@link #ORIGIN}（离其它孤立点 ≥200 格）；区域 = 水平 ±{@link #R}、
 *       y ∈ [0..{@link #LAYERS}-1]（相对 ORIGIN）；**外扩一圈强制空气**（孤立长方体规则）；
 *       基底两层实心（y−1、y−2）保证"可站"有支撑；</li>
 *   <li><b>世界/模组集</b>：只用 `STONE` 与空气；地形由**固定种子** {@link #SEED} 生成（可复现）；
 *       起点列强制清空（**避免窒息**——电池里窒息会触发真否决，见 skill §夹具相位状态机）；</li>
 *   <li><b>层归属</b>：只断言**边生成层**（`appendCandidates` 的产出），不 tick 任何子任务、不执行</li>
 * </ol>
 *
 * <p><b>§6.9.3 三问自答</b>：① 断言的是生成层（上游没有任何闸门会短路：请求用 `collect-drops`
 * = P-03/GATHERING 行，含 `WITH_WORLD_MODIFICATION`）；② 世界假设（地形由本夹具自己造 + 自断言
 * "起点可站""基底存在"）都写下来；③ 失败时日志给 `SUMMARY … gaps=N → FAIL` + 每条缺口
 * `type from→to`（可直接拿去复现）。
 *
 * <p>⚠️ **写预算**：生成器会按 `context.writesAllowed(...)` 剪枝（`D-106 Slice B`）。本夹具显式
 * 求值同一个谓词；被预算挡住时把该类期望**降级为 skipped**（记进 SUMMARY），**不计入缺口**
 * （否则"预算紧"会被误报成"图缺边"）。
 */
public final class EdgeCompletenessCheckTask implements Task {

    /** 专用孤立点：离所有其它孤立点 ≥200 格（最近的 (3000,100,3000)/(3000,100,3200)/(3800,100,2000) 都远）。 */
    private static final BlockPos ORIGIN = new BlockPos(3200, 100, 4200);
    /** 水平半径 ⇒ 区域 (2R+1)² 格。 */
    private static final int R = 6;
    /** 地形层数（y ∈ [ORIGIN.y .. ORIGIN.y+LAYERS-1] = 可站层 + 障碍层）。 */
    private static final int LAYERS = 3;
    private static final long SEED = 20260921L;
    private static final double OBSTACLE_CHANCE = 0.34D;

    private static final int SETTLE_TICKS = 20;
    private static final int BUDGET_TICKS = 400;
    private static final int MAX_REPORTED_GAPS = 12;

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private int ticks;
    private int checks;
    private int prepareStep;
    private boolean done;
    private boolean built;
    private boolean restored = true;

    public EdgeCompletenessCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "EdgeCompletenessCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("边集差集必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        if (prepareStep == 0) {
            // 先把 bot 放进起点格（玩家 ticket 会加载区域区块，避免在未加载区块上读方块）。
            // ⚠️ 起点高度**不可假设**：该点环境地面在 y≈86（实测第一版 bot 从 y=100 掉下去 ⇒ 前提判红）
            // ⇒ 本夹具**自带地板**：清出身体空间 + 在 y−1 放一块石头，让场景成为**悬空平台**
            //（顺带满足"孤立长方体"：四周/上下都是空气）。
            ServerLevel level = bot.serverLevel();
            teleport(bot, ORIGIN);
            for (int dy = 0; dy <= 4; dy++) {
                level.setBlock(ORIGIN.above(dy), Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(ORIGIN.below(), Blocks.STONE.defaultBlockState(), 2);
            prepareStep = 1;
            return Task.Status.RUNNING;
        }
        if (prepareStep <= SETTLE_TICKS) {
            prepareStep++;
            return Task.Status.RUNNING;
        }
        if (prepareStep == SETTLE_TICKS + 1) {
            ServerLevel level = bot.serverLevel();
            // 区域内区块必须已加载（`D-132`：搜索/读方块不许落在未加载区块上）
            teleport(bot, ORIGIN);
            check("前提：起点格已加载（chunkLoaded=" + level.hasChunkAt(ORIGIN) + "）",
                    level.hasChunkAt(ORIGIN));
            check("前提：bot 脚位就在起点格（foot=" + bot.blockPosition().toShortString() + "）",
                    bot.blockPosition().equals(ORIGIN));
            buildScene(level);
            built = true;
            check("前提：起点可站（canStandCentered=" + MovementHelper.canStandCentered(level, ORIGIN) + "）",
                    MovementHelper.canStandCentered(level, ORIGIN));
            check("前提：起点脚下有支撑（state="
                            + level.getBlockState(ORIGIN.below()).getBlock().getName().getString() + "）",
                    MovementHelper.canWalkOn(level, ORIGIN));
            prepareStep++;
            return Task.Status.RUNNING;
        }
        runDiff();
        return finish();
    }

    // ---------------------------------------------------------------- 场景

    private void buildScene(ServerLevel level) {
        // ① 外扩一圈强制空气（孤立长方体：边界外至少一圈空气，含上下）
        fill(level, R + 1, LAYERS + 4, Blocks.AIR);
        // ② 基底两层实心（提供"可站"支撑；井底那格稍后会被挖空以产生 DESCEND 形状）
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                level.setBlock(ORIGIN.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(ORIGIN.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        // ③ 固定种子地形（可复现）：y ∈ [0..LAYERS-1] 以 OBSTACLE_CHANCE 放石头
        Random random = new Random(SEED);
        for (int dy = 0; dy < LAYERS; dy++) {
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    if (random.nextDouble() < OBSTACLE_CHANCE) {
                        level.setBlock(ORIGIN.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
        // ④ 起点列强制清空（避免窒息 —— 电池里窒息会触发真否决）
        level.setBlock(ORIGIN, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(ORIGIN.above(), Blocks.AIR.defaultBlockState(), 2);
        // ⑤ 特殊形状（随机地形不保证出现，必须手放）
        //    ⑤-1 **一格高夹缝**（= `D-374` 的形状）：脚位空 + 头位实
        level.setBlock(ORIGIN.offset(3, 0, 0), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(ORIGIN.offset(3, 0, 0).above(), Blocks.STONE.defaultBlockState(), 2);
        //    ⑤-2 **1 格深的井**：把基底挖掉一格 ⇒ 产生 DESCEND 形状
        level.setBlock(ORIGIN.offset(-3, -1, 0), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(ORIGIN.offset(-3, 0, 0), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(ORIGIN.offset(-3, 0, 0).above(), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(ORIGIN.offset(-3, 0, 0).above(2), Blocks.AIR.defaultBlockState(), 2);
        //    ⑤-3 井口四周留出通道（保证井真的能被走到）
        for (int dz = -1; dz <= 1; dz++) {
            level.setBlock(ORIGIN.offset(-2, 0, dz), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(-2, 0, dz).above(), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(-4, 0, dz), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(ORIGIN.offset(-4, 0, dz).above(), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private void fill(ServerLevel level, int radius, int layers, net.minecraft.world.level.block.Block block) {
        for (int dy = -3; dy <= layers; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    level.setBlock(ORIGIN.offset(dx, dy, dz), block.defaultBlockState(), 2);
                }
            }
        }
    }

    // ------------------------------------------------------------ 差集

    private void runDiff() {
        ServerLevel level = bot.serverLevel();
        String requester = "collect-drops";   // = WritePolicyMatrix 的 P-03/GATHERING 行（含 WITH_WORLD_MODIFICATION）
        SurfaceMovementProvider provider = new SurfaceMovementProvider();
        Map<MovementType, Integer> expectedByType = new LinkedHashMap<>();
        Map<MovementType, Integer> actualByType = new LinkedHashMap<>();
        TreeSet<String> expected = new TreeSet<>();
        TreeSet<String> actual = new TreeSet<>();
        List<String> gaps = new ArrayList<>();
        List<String> contractProblems = new ArrayList<>();
        int cells = 0;
        int standable = 0;
        int budgetSkipped = 0;

        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int dy = 0; dy < LAYERS; dy++) {
                    BlockPos from = ORIGIN.offset(dx, dy, dz);
                    cells++;
                    if (!MovementHelper.canStandCentered(level, from)) {
                        continue;
                    }
                    standable++;
                    // 请求用**允许全集**（与 `PathRequest.withWorldModification` 的集合一致）
                    PathRequest request = PathRequest.withWorldModification(
                            bot.getUUID().toString(), from, from, requester);
                    MovementContext context = MovementContext.live(bot, level, request);
                    boolean breakAllowed = context.writesAllowed(MovementType.BREAK_AND_ENTER);

                    for (int[] d : CARDINAL) {
                        BlockPos same = from.offset(d[0], 0, d[1]);
                        if (MovementHelper.canTraverse(level, from, same)) {
                            add(expected, expectedByType, MovementType.TRAVERSE, same);
                        }
                        BlockPos up = from.offset(d[0], 1, d[1]);
                        if (MovementHelper.canAscend(level, from, up)) {
                            add(expected, expectedByType, MovementType.ASCEND, up);
                        }
                        BlockPos down = from.offset(d[0], -1, d[1]);
                        if (MovementHelper.canDescend(level, from, down)) {
                            add(expected, expectedByType, MovementType.DESCEND, down);
                        }
                        if (MovementHelper.canWalkOn(level, same)) {
                            List<BlockPos> blockers = BreakAndEnterExecution.collectBlockers(level, from, same);
                            if (!blockers.isEmpty() && breakable(context, blockers)) {
                                if (breakAllowed) {
                                    add(expected, expectedByType, MovementType.BREAK_AND_ENTER, same);
                                } else {
                                    budgetSkipped++;
                                }
                            }
                        }
                    }
                    for (int[] d : DIAGONAL) {
                        BlockPos same = from.offset(d[0], 0, d[1]);
                        if (MovementHelper.canTraverse(level, from, same)) {
                            add(expected, expectedByType, MovementType.DIAGONAL, same);
                        }
                    }

                    List<PlannedMovement> out = new ArrayList<>();
                    provider.appendCandidates(context, from, out);
                    for (PlannedMovement movement : out) {
                        add(actual, actualByType, movement.movementType(), movement.toFoot());
                        String violation = contractViolation(movement);
                        if (!violation.isEmpty() && contractProblems.size() < 4) {
                            contractProblems.add(violation);
                        }
                    }
                }
            }
        }

        for (String key : expected) {
            if (!actual.contains(key)) {
                gaps.add(key);
            }
        }
        boolean budgetAffectsBreaks = budgetSkipped > 0;

        findings.add("cells=" + cells + " standable=" + standable + " budgetSkipped=" + budgetSkipped
                + " expected=" + expected.size() + " actual=" + actual.size()
                + " extra=" + (actual.size() - countCommon(expected, actual)));
        findings.add("expectedByType=" + expectedByType + " actualByType=" + actualByType);
        for (int i = 0; i < Math.min(MAX_REPORTED_GAPS, gaps.size()); i++) {
            findings.add("GAP " + gaps.get(i));
        }
        BotLog.info("[EdgeCompleteness] cells={} standable={} expected={} actual={} gaps={} extra={} "
                        + "budgetSkipped={} expectedByType={} actualByType={}",
                cells, standable, expected.size(), actual.size(), gaps.size(),
                actual.size() - countCommon(expected, actual), budgetSkipped, expectedByType, actualByType);
        for (String gap : gaps.subList(0, Math.min(MAX_REPORTED_GAPS, gaps.size()))) {
            BotLog.warn("[EdgeCompleteness] GAP {}", gap);
        }

        check("场景必须真的被测到（standable > 20，实际=" + standable + "）", standable > 20);
        check("模型侧必须真的产出期望边（expected > 20，实际=" + expected.size() + "）", expected.size() > 20);
        check("⭐ 差集为空：**执行器/物理允许的移动 ⇒ 生成器必须给出这条边**"
                        + "（应有=" + expected.size() + " 实际=" + actual.size() + " 缺口=" + gaps.size()
                        + (gaps.isEmpty() ? "" : "：" + gaps.subList(0, Math.min(4, gaps.size())))
                        + (budgetAffectsBreaks ? "（⚠️ BREAK_AND_ENTER 被写预算剪枝 "
                        + budgetSkipped + " 次 ⇒ 该类期望已降级为 skipped）" : "") + "）",
                gaps.isEmpty());
        check("K-4 网：生成器产出的每条边都能构造执行端 MovementSpec"
                + (contractProblems.isEmpty() ? "" : "：违反=" + contractProblems), contractProblems.isEmpty());
    }

    private boolean breakable(MovementContext context, List<BlockPos> blockers) {
        for (BlockPos blocker : blockers) {
            if (context.bot() == null || !BlockInteraction.breakable(context.bot(), context.level(), blocker,
                    WriteGrant.of(context.request().requester(), WriteReason.PATH_ACCESS))) {
                return false;
            }
        }
        return true;
    }

    private static void add(TreeSet<String> set, Map<MovementType, Integer> byType,
                            MovementType type, BlockPos to) {
        set.add(key(type, to));
        byType.merge(type, 1, Integer::sum);
    }

    private static String key(MovementType type, BlockPos to) {
        return type + " → " + to.toShortString();
    }

    private static int countCommon(TreeSet<String> a, TreeSet<String> b) {
        int common = 0;
        for (String key : a) {
            if (b.contains(key)) {
                common++;
            }
        }
        return common;
    }

    private static String contractViolation(PlannedMovement movement) {
        try {
            PlannedMovementSpecs.toSpec(movement, List.of("session_segment", "target_support",
                    "target_body_clear", "target_head_clear"));
        } catch (RuntimeException exception) {
            return movement.movementType() + " " + movement.fromFoot().toShortString() + "→"
                    + movement.toFoot().toShortString() + " :: " + exception.getMessage();
        }
        return "";
    }

    // ------------------------------------------------------------ 收尾

    private void teleport(ServerPlayer who, BlockPos foot) {
        who.teleportTo(who.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), who.getYRot(), who.getXRot());
        who.setDeltaMovement(Vec3.ZERO);
        if (who instanceof BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();
        }
    }

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        if (built) {
            ServerLevel level = bot.serverLevel();
            fill(level, R + 1, LAYERS + 4, Blocks.AIR);
            restored = true;
            outer:
            for (int dy = -3; dy <= LAYERS + 2; dy++) {
                for (int dx = -R - 1; dx <= R + 1; dx++) {
                    for (int dz = -R - 1; dz <= R + 1; dz++) {
                        if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                            restored = false;
                            break outer;
                        }
                    }
                }
            }
        }
        // 副作用边界（§6.9.2）：失败路径也要清理，且清理结果**进判据**
        check("清理：场景区域已整体还原为空气（restored=" + restored + "）", restored);
        boolean pass = failures.isEmpty();
        BotLog.info("[EdgeCompleteness] SUMMARY checks={} failures={} restored={} → {}｜findings={}｜failures={}",
                checks, failures.size(), restored, pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 边集完备性差集 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
