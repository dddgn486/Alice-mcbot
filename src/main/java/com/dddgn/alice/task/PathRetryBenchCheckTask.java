package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
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

/**
 * **任务层失败重试节奏**基准（`D-328` 下一步测量 ⭐）—— 电池步 `path_retry_bench`（EXTRA）。
 *
 * <p><b>为什么量它</b>：`D-328` 附注已证明"已加载范围内规划是毫秒级"、越过加载边界是
 * **`GOAL_NOT_LOADED`（0 节点 0 ms）硬拒** ⇒ "A\* 太慢"不成立。那用户看到的"卡"就只剩一个可能：
 * **任务层在失败上反复重试**。而重试的代价**完全取决于失败类型**：
 * <ul>
 *   <li>**贵**：目标已加载但**不可达**（例如被封死的房间）⇒ 每次重试都要跑满搜索
 *       （20k 节点，实测 100~200 ms，最坏 3000 ms）⇒ 重试 3 次就是 0.3~9 秒的**服务端停顿**；</li>
 *   <li>**便宜**：`GOAL_NOT_LOADED` ⇒ 0 ms，重试再多次也只是"廉价地空转"。</li>
 * </ul>
 * 所以必须**分开量**，否则会把"廉价空转"当成"卡"。
 *
 * <p><b>仪表</b>：不需要新加 —— `PathRetryRunner` 自带 `replans`（`DEFAULT_MAX_REPLANS = 2`、
 * `MAX_PARTIAL_HOPS = 4`），每次重试打 `[PathRetry] replan attempt=… replans=… reason=… code=…`，
 * `WalkToTask` 终结时打 `replans=`；夹具只负责**驱动任务 + 量 tick 与墙钟毫秒**。
 *
 * <p><b>判据</b>：① 前提要成立（案例 A 的目标必须**真的被封死**、案例 B 的目标区块**真的没加载** ——
 * 缺了这两条前提，量到的又是假象）；② 两案例都必须在 tick 上限内**终结**（若某案例无休止重试 ⇒ 那就是缺陷，判红）；
 * ③ ticks/墙钟毫秒/replans **只记录不判**（它们是待解释的事实）。
 */
public final class PathRetryBenchCheckTask implements Task {

    /** 平坦区起点（远离走廊与其它场景）。 */
    private static final BlockPos START = new BlockPos(3000, -59, 3600);

    /** 案例 A：目标距起点多远（必须**在已加载半径内**，否则退化成案例 B）。 */
    private static final int SEALED_DISTANCE = 30;

    /** 案例 B：目标距起点多远（必须**超出加载半径** ≈192 格）。 */
    private static final int UNLOADED_DISTANCE = 400;

    /** 单个案例的 tick 上限（超了 = 无休止重试 ⇒ 判红：那正是"卡"的形态）。 */
    private static final int CASE_TICK_CAP = 3000;   // 案例 B 是一次 400 格的**真走**（≈1500 tick）

    private static final int BUDGET_TICKS = 4000;

    /** 传送之后等 bot 真正落地的 tick 数（**同一 tick 读脚位会读到空中** —— 与 D-328 附注同一类错）。 */
    private static final int SETTLE_TICKS = 30;

    private enum Phase { GUARD, PREPARE, SEALED, UNLOADED, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    /** 案例 A 造出来的封壳方块（收尾要拆掉）。 */
    private final Map<BlockPos, Boolean> shell = new LinkedHashMap<>();

    private Phase phase = Phase.GUARD;
    private int ticks;
    private int checks;
    private boolean done;
    private Task walkTask;
    private BlockPos caseGoal;
    private int caseStartTick;
    private long caseWallNanos;
    private int prepareStep;
    private int caseTicks;

    public PathRetryBenchCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PathRetryBenchCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(START);
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
            check("重试基准必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        switch (phase) {
            case GUARD -> guardPhase();
            case PREPARE -> preparePhase();
            case SEALED, UNLOADED -> casePhase();
            case CLEANUP -> cleanupPhase();
            case DONE -> {
                return finish();
            }
            default -> {
            }
        }
        return Task.Status.RUNNING;
    }

    private void guardPhase() {
        check("前提：本步需要一只会话假人 + 观察者", bot != null && observer != null);
        if (bot == null || observer == null) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.PREPARE;
    }

    /** 落到平坦地表（脚位**从 bot 实际位置取**：D-328 附注那个 bug 的教训），并造出案例 A 的封壳。 */
    private void preparePhase() {
        ServerLevel level = bot.serverLevel();
        if (prepareStep == 0) {
            teleport(bot, START);
            prepareStep = 1;
            BotLog.info("[RetryBench] 传送到 {}（等 {} tick 落地后再取脚位）", START.toShortString(), SETTLE_TICKS);
            return;
        }
        if (prepareStep <= SETTLE_TICKS) {
            prepareStep++;
            return;
        }
        BlockPos foot = bot.blockPosition();
        check("前提：起点脚下必须有支撑（脚位=" + foot.toShortString() + " 脚下="
                        + level.getBlockState(foot.below()).getBlock().getName().getString() + "）",
                !level.getBlockState(foot.below()).isAir());
        if (level.getBlockState(foot.below()).isAir()) {
            phase = Phase.DONE;
            return;
        }
        // 案例 A 的封壳：以目标为心 3×3×3，中心柱（脚位 + 头顶）留空气，其余 25 格填石头 ⇒ 可站但进不去
        caseGoal = foot.offset(SEALED_DISTANCE, 0, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    boolean inner = dx == 0 && dz == 0 && dy <= 1;
                    BlockPos pos = caseGoal.offset(dx, dy, dz);
                    if (inner) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    } else {
                        shell.put(pos.immutable(), level.getBlockState(pos).isAir());
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }
        // ★ 前提判据：目标必须真的"可站但被封死" —— 缺了它，量到的会是"目标不可站"（廉价拒绝）而当成"昂贵失败"
        boolean goalAir = level.getBlockState(caseGoal).isAir() && level.getBlockState(caseGoal.above()).isAir();
        boolean goalSupported = !level.getBlockState(caseGoal.below()).isAir();
        int stoneShell = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (dx == 0 && dz == 0 && dy <= 1) {
                        continue;
                    }
                    if (level.getBlockState(caseGoal.offset(dx, dy, dz)).is(Blocks.STONE)) {
                        stoneShell++;
                    }
                }
            }
        }
        check("前提 A：案例 A 的目标必须「可站但被封死」（脚位与头顶=空气 " + goalAir
                        + "、脚下有支撑 " + goalSupported + "、封壳石头格数 " + stoneShell + "/25）",
                goalAir && goalSupported && stoneShell == 25);
        teleport(bot, foot);
        BotLog.info("[RetryBench] 准备完成 foot={} 案例A goal={}（封壳 25 格）案例B goal={}（+{} 格，应超出加载半径）",
                foot.toShortString(), caseGoal.toShortString(),
                foot.offset(UNLOADED_DISTANCE, 0, 0).toShortString(), UNLOADED_DISTANCE);
        phase = Phase.SEALED;
    }

    /** 驱动真的 {@link WalkToTask}，量 tick 与墙钟毫秒，直到它自己终结（或撞上限）。 */
    private void casePhase() {
        ServerLevel level = bot.serverLevel();
        if (walkTask == null) {
            if (phase == Phase.SEALED) {
                caseGoal = bot.blockPosition().offset(SEALED_DISTANCE, 0, 0);
            } else {
                caseGoal = bot.blockPosition().offset(UNLOADED_DISTANCE, 0, 0);
                check("前提 B：案例 B 的目标区块**必须没加载**（否则退化成案例 A）"
                                + "（goal=" + caseGoal.toShortString() + " loaded=" + level.hasChunkAt(caseGoal) + "）",
                        !level.hasChunkAt(caseGoal));
            }
            BotLog.info("[RetryBench] case={} **规划前** hasChunkAt(goal)={} goal={}",
                    caseName(), level.hasChunkAt(caseGoal), caseGoal.toShortString());
            walkTask = new WalkToTask(bot, caseGoal);
            caseStartTick = ticks;
            caseWallNanos = 0L;
            caseTicks = 0;
            return;
        }
        long t0 = System.nanoTime();
        Task.Status status = walkTask.tick();
        caseWallNanos += System.nanoTime() - t0;
        // ⚠️ 案例 B 的反常现象需要解释：目标 400 格外的区块"本该没加载"，规划却 REACHED。
        // 这里在**第一次 plan 之后**再问一次 —— 若它变 true，说明**规划路径里有隐藏的同步加载**（那是真缺陷）。
        if (++caseTicks == 1) {
            BotLog.info("[RetryBench] case={} **首 tick（含一次 plan）之后** hasChunkAt(goal)={}",
                    caseName(), level.hasChunkAt(caseGoal));
        }
        int used = ticks - caseStartTick;
        if (status == Task.Status.RUNNING) {
            if (used < CASE_TICK_CAP) {
                return;
            }
            check("案例 " + caseName() + " 必须在 " + CASE_TICK_CAP + " tick 内终结（无休止重试 = 「卡」的形态）",
                    false);
        }
        if (phase == Phase.UNLOADED) {
            // D-331 门禁：修好之后，指向未加载目标必须**如实拒绝**，且**不许**把目标区块同步加载进来
            check("D-331：指向未加载目标必须如实拒绝（status=" + status + " reason=" + walkTask.failureReason()
                            + "，期望 FAILED + walk_goal_unloaded）",
                    status == Task.Status.FAILED
                            && walkTask.failureReason().startsWith("walk_goal_unloaded"));
            check("D-331：拒绝路径**不许同步加载目标区块**（goal=" + caseGoal.toShortString()
                            + " loaded=" + level.hasChunkAt(caseGoal) + "）",
                    !level.hasChunkAt(caseGoal));
        }
        String line = "case=" + caseName() + " goal=" + caseGoal.toShortString()
                + " status=" + status + " ticks=" + used
                + " wallMs=" + (caseWallNanos / 1_000_000L)
                + " reason=" + walkTask.failureReason()
                + " replans(见日志 [PathRetry])";
        findings.add(line);
        BotLog.info("[RetryBench] {}", line);
        walkTask = null;
        if (phase == Phase.SEALED) {
            phase = Phase.UNLOADED;
        } else {
            phase = Phase.CLEANUP;
        }
    }

    private String caseName() {
        return phase == Phase.SEALED ? "sealed(已加载但封死)" : "unloaded(超出加载半径)";
    }

    private void cleanupPhase() {
        ServerLevel level = bot.serverLevel();
        check("两案例都要量到（实际 " + findings.size() + "/2）", findings.size() == 2);
        shell.forEach((pos, wasAir) -> level.setBlock(pos,
                wasAir ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 3));
        shell.clear();
        BotLog.info("[RetryBench] 封壳已拆、场景已复位");
        phase = Phase.DONE;
        finish();
    }

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
        boolean pass = failures.isEmpty();
        BotLog.info("[RetryBench] SUMMARY checks={} failures={} → {}｜findings：{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", findings);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(
                    "[alice] 失败重试基准 " + (pass ? "PASS" : "FAIL " + failures) + "（详见日志 [RetryBench]）"));
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
