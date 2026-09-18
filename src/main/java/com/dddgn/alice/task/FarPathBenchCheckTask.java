package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **远距离寻路代价曲线**（基准测量，`D-328` 前置）—— 电池步 `far_path_bench`（EXTRA）。
 *
 * <p><b>为什么先做它（不许凭感觉优化）</b>：用户 2026-09-18 提出"远距离寻路太慢"，量级是
 * **300~1000 格**。要优化，先得有"**距离 ⇒ 节点数 / 毫秒 / 状态**"的实际曲线 ——
 * 否则任何优化都是猜（而这正是本项目反复吃过的亏）。仪表**已经在手上**：
 * {@link PathPlan} 自带 `nodesExpanded` / `movementsConsidered` / `elapsedMillis`（`PathPlan:20-22`），
 * 所以这一步**不改内核、只测量**。
 *
 * <p><b>为什么要自己造走廊（`survey/08 §3.5` 的那个结）</b>：内核**从不加载区块**
 * （`AStarMovementSearch:52-53`：目标格所在区块没加载 ⇒ 直接 `GOAL_NOT_LOADED`）。
 * 300~1000 格外的目标格当然没加载 ⇒ **不 forceload 就量不到"搜索有多贵"，只能量到"进不去"**。
 * 所以本夹具：① 在**远离一切场景**的专用走廊（`ORIGIN`）建一条平直平台；② 对走廊沿途区块
 * `setChunkForced`；③ 再逐距离测量。⚠️ 必须把"已 forceload"这件事写进日志 ——
 * 否则读日志的人会以为"远距离本来就能规划"，而真相是**这一步替它加载了**。
 *
 * <p><b>判据（只读仪表，不改行为）</b>：① 6 个距离**都产出了可判读状态**（曲线完整）；
 * ② `d=20` 必须 `REACHED`（**基准对照**：证明测量装置本身可用，防"全都量成失败还当成结论"）；
 * ③ 大距离**不设通过/失败** —— `SEARCH_LIMIT` / `GOAL_NOT_LOADED` / `PARTIAL` 都是**要记录的事实**，
 * 不是判红条件（判红会诱使后来人把曲线"调好看"）。
 *
 * <p>收尾：走廊方块清回空气 + 撤销 forceload（夹具不复位 = 污染后续步）。
 */
public final class FarPathBenchCheckTask implements Task {

    /** 测量点（格）：覆盖用户给的量级 300~1000，并把低端当基准。 */
    private static final int[] DISTANCES = {20, 40, 80, 160, 320, 640};

    /** 专用走廊原点（**远离所有场景**：x/z = 3000 一带，正常电池步不会走到）。 */
    private static final BlockPos ORIGIN = new BlockPos(3000, 100, 3000);

    /** 走廊半宽（z 方向 ±2 ⇒ 5 格宽，够容纳绕行与对角线）。 */
    private static final int LANE_HALF_WIDTH = 2;

    /** 真实地形那一遍的原点（与走廊**不同位置**：走廊那片已经被改过）。 */
    private static final BlockPos TERRAIN_ORIGIN = new BlockPos(3000, 100, 3200);

    /** 平台上方的空气层数（2 格够走，给 4 格避免"贴天花板"影响 canStandCentered）。 */
    private static final int HEADROOM = 4;

    private static final int BUDGET_TICKS = 400;

    private enum Phase { GUARD, BUILD, MEASURE, MEASURE_TERRAIN, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    /** 每个距离一行的测量结果（SUMMARY 里原样给出，供人读曲线）。 */
    private final List<String> curve = new ArrayList<>();

    private Phase phase = Phase.GUARD;
    private int ticks;
    private int checks;
    private boolean done;
    private int index;
    private boolean built;
    /** 走廊**覆盖掉的原方块**（收尾必须还原 —— 第一版直接清成空气，等于把这片自然地形挖了个洞）。 */
    private final java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> overwritten =
            new java.util.LinkedHashMap<>();

    public FarPathBenchCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "FarPathBenchCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.above());
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
            check("基准测量必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        switch (phase) {
            case GUARD -> guardPhase();
            case BUILD -> buildPhase();
            case MEASURE -> measurePhase();
            case MEASURE_TERRAIN -> terrainPhase();
            case CLEANUP -> cleanupPhase();
            case DONE -> {
                return finish();
            }
            default -> {
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 阶段 ====================

    private void guardPhase() {
        check("前提：本步需要一只会话假人", bot != null);
        check("前提：本步需要观察者玩家", observer != null);
        if (bot == null || observer == null) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.BUILD;
    }

    /** 建走廊 + forceload（顺序不能反：未加载区块里写方块是静默无效的，本项目踩过两次）。 */
    private void buildPhase() {
        ServerLevel level = bot.serverLevel();
        int length = DISTANCES[DISTANCES.length - 1] + 32;
        int minZ = ORIGIN.getZ() - LANE_HALF_WIDTH;
        int maxZ = ORIGIN.getZ() + LANE_HALF_WIDTH;
        for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + length) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        int placed = 0;
        for (int x = ORIGIN.getX(); x <= ORIGIN.getX() + length; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(level, new BlockPos(x, ORIGIN.getY(), z), Blocks.STONE.defaultBlockState());
                placed++;
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    place(level, new BlockPos(x, ORIGIN.getY() + dy, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        built = true;
        BotLog.info("[FarBench] 走廊就绪 origin={} 长度={} 宽={} 方块写={} ⇒ ⚠️ 走廊沿途区块**已 forceload**"
                        + "（否则 >160 格必然 GOAL_NOT_LOADED，量到的只是'进不去'）",
                ORIGIN.toShortString(), length, LANE_HALF_WIDTH * 2 + 1, placed);
        teleport(bot, ORIGIN.above());
        check("前提：走廊建成后假人站到了平台上（脚位 " + ORIGIN.above().toShortString() + "）",
                bot.blockPosition().equals(ORIGIN.above()));
        phase = Phase.MEASURE;
    }

    /**
     * **第二遍：不铺走廊、直接在地表上走**（同一批距离，沿途区块仍 forceload）。
     *
     * <p>⚠️ **实测更正（2026-09-19 首跑）**：无头测试世界是**超平坦**（`level.dat` 里
     * `dimensions.minecraft:overworld.generator.type = minecraft:flat`，地表 y=-60）⇒ 这一遍量到的
     * **仍然是平地**（`startY=-60`、节点数同样 ≈ 距离+1）。所以它**不能**用来回答"真实地形有多贵"。
     * 要量地形复杂度，必须**自建障碍场景**（墙/迷宫）—— 见 `D-328` 的"下一步测量"。
     */
    private void terrainPhase() {
        if (index >= DISTANCES.length * 2) {
            phase = Phase.CLEANUP;
            return;
        }
        int distance = DISTANCES[index++ - DISTANCES.length];
        ServerLevel level = bot.serverLevel();
        BlockPos probe = TERRAIN_ORIGIN.offset(distance, 0, 0);
        for (int cx = TERRAIN_ORIGIN.getX() >> 4; cx <= probe.getX() >> 4; cx++) {
            for (int cz = (TERRAIN_ORIGIN.getZ() >> 4) - 1; cz <= (TERRAIN_ORIGIN.getZ() >> 4) + 1; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        // ⚠️ 必须**强制生成**再量：第一版直接在未生成的区块上取高度图，得到 startY=-60（未加载区块返回最低值）
        // ⇒ 量出来的是"在虚空里走直线"（又一次线性），**那一遍是无效的**。
        // 注意：这里用的是夹具特供的 `getChunk(..., FULL, true)` —— **内核从不这么做**（`GOAL_NOT_LOADED`），
        // 夹具这么干只为了让"真实地形"这一遍有地形可量。
        for (int cx = TERRAIN_ORIGIN.getX() >> 4; cx <= probe.getX() >> 4; cx++) {
            level.getChunk(cx, TERRAIN_ORIGIN.getZ() >> 4,
                    net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
        }
        BlockPos start = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                TERRAIN_ORIGIN);
        BlockPos goal = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                probe);
        teleport(bot, start);
        PathPlan plan = new CorePathPlanner().planTo(bot, bot.serverLevel(), bot.getUUID().toString(),
                start, goal, "pathing");
        String line = "terrain d=" + distance + " startY=" + start.getY() + " goalY=" + goal.getY()
                + " status=" + plan.status() + " nodes=" + plan.nodesExpanded()
                + " moves=" + plan.movementsConsidered() + " ms=" + plan.elapsedMillis()
                + " reached=" + plan.reached() + " partial=" + plan.partial();
        curve.add(line);
        BotLog.info("[FarBench] {}", line);
    }

    /** 写方块前先记原状（仅走廊那一遍用）。 */
    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        overwritten.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, state, 3);
    }

    /** 每个 tick 量一个距离（规划是同步阻塞的，所以一次一拍，避免一步里堆 18 秒）。 */
    private void measurePhase() {
        if (index >= DISTANCES.length) {
            phase = Phase.MEASURE_TERRAIN;   // 走廊那遍量完 ⇒ 接着量真实地形那遍（同一批距离）
            return;
        }
        int distance = DISTANCES[index++];
        BlockPos start = ORIGIN.above();
        BlockPos goal = start.offset(distance, 0, 0);
        teleport(bot, start);
        // 生产入口（= `CorePathPlanner.planTo`：纯通行 + 默认预算 20k 节点 / 3000 ms）
        PathPlan plan = new CorePathPlanner().planTo(bot, bot.serverLevel(), bot.getUUID().toString(),
                start, goal, "pathing");
        String line = "d=" + distance + " status=" + plan.status()
                + " nodes=" + plan.nodesExpanded() + " moves=" + plan.movementsConsidered()
                + " ms=" + plan.elapsedMillis() + " cost=" + String.format(java.util.Locale.ROOT, "%.1f", plan.totalCost())
                + " reached=" + plan.reached() + " partial=" + plan.partial()
                + " pathLen=" + plan.projectedFootPath().size();
        curve.add(line);
        BotLog.info("[FarBench] {}", line);
        if (distance == DISTANCES[0]) {
            check("基准对照：最近的一档（d=" + DISTANCES[0] + "）必须真的规划出来（否则说明测量装置本身坏了，"
                            + "后面的'远距离很慢'就不能当结论）（实际 status=" + plan.status() + "）",
                    plan.reached());
        }
    }

    /** 撤销 forceload + 走廊清回空气。 */
    private void cleanupPhase() {
        ServerLevel level = bot.serverLevel();
        check("曲线完整性：走廊 6 档 + 真实地形 6 档都要量到（实际 " + curve.size() + "/"
                        + (DISTANCES.length * 2) + "）",
                curve.size() == DISTANCES.length * 2);
        if (built) {
            int length = DISTANCES[DISTANCES.length - 1] + 32;
            int minZ = ORIGIN.getZ() - LANE_HALF_WIDTH;
            int maxZ = ORIGIN.getZ() + LANE_HALF_WIDTH;
            // 还原成**原状**（不是一律清成空气）：夹具的世界修改必须是可逆的
            overwritten.forEach((pos, state) -> level.setBlock(pos, state, 3));
            overwritten.clear();
            for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + length) >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    level.setChunkForced(cx, cz, false);
                }
            }
            built = false;
            BotLog.info("[FarBench] 走廊已清回空气、forceload 已撤销");
        }
        if (bot != null) {
            bot.controller().stopMovement();
        }
        phase = Phase.DONE;
        finish();
    }

    // ==================== 工具 ====================

    private void teleport(ServerPlayer who, BlockPos foot) {
        who.teleportTo(who.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), who.getYRot(), who.getXRot());
        who.setDeltaMovement(Vec3.ZERO);
        if (who instanceof BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();   // `controller()` 在 BotPlayer 上，ServerPlayer 没有
        }
    }

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[FarBench] SUMMARY checks={} failures={} → {}｜曲线：{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", curve);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(
                    "[alice] 远距离寻路基准 " + (pass ? "PASS" : "FAIL " + failures) + "（详见日志 [FarBench] 曲线）"));
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
