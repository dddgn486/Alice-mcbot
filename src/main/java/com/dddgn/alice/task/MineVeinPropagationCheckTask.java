package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
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
 * ⭐ `D-389`（2026-09-22 用户裁定）的**行为级判据**：**沿脉传播**必须真的发生，且矿簇必须被挖穿。
 *
 * <h2>它钉的是哪条链</h2>
 * 用户原话：*"矿簇应该要能尽量挖完……矿簇每个子矿一定是六面或者对角相连……理论上除了水下/岩浆旁
 * 这些本来就不能挖的情况，都是能够完成挖掘的"*。
 * 而真机（2026-09-22 10:49）消费的是**列表序**：下一个成员离 bot **6.6 格**（`target=48,63,140`
 * vs `startFoot=45,68,137`）⇒ 每个成员付一次跨 6 格的**昂贵** approach 搜索 ⇒ 30 s 内 33 次 × 196 ms。
 *
 * <h2>判据（三条）</h2>
 * <ol>
 *   <li>`job.veinPropagations() > 0` —— 沿脉传播**真的被触发**（否则本夹具测不到被改的那条路径）；</li>
 *   <li>挖到数 ≥ {@link #MIN_MINED}（{@link #ORE_COUNT} 个相邻矿石中的大多数）—— **簇被沿脉挖穿**；</li>
 *   <li>⭐ `job.staleProofRetries() > 0` —— `PL-1` 的**过期证明重评**真的被触发
 *       （否则这条能力是死代码，或者仍被 `already_attempted` 永久挡住）。</li>
 * </ol>
 *
 * <h2>反向对照（两条红臂，各对应一组判据）</h2>
 * <ol>
 *   <li>注释掉 `MineJob` 里 `enqueueVeinNeighbours(...)` 那一行 ⇒ 第 1 条必红（`veinPropagations=0`）；</li>
 *   <li>把 `MineJob.proofExpired(...)` 改成恒 `false`（= 关掉重评，回到"永久了结"）⇒ 第 2/3 条必红
 *       （实测落回 `mined 12/30`、`staleRetries=0`，与 `PL-1` 之前完全一致 ⇒ 差距确实由这条能力带来）。</li>
 * </ol>
 *
 * <h2>场景</h2>
 * 孤立**石壳 + 3×2×3 铁矿脉**（18 格，**26 邻接** ⇒ 对角也算一条脉），bot 站在石壳西面的平台上
 * ⇒ 只能挖进去；进脉之后每个下一个目标都在 1 格内 ⇒ 便宜。
 */
public final class MineVeinPropagationCheckTask implements Task {

    /** 孤立原点（避开 3600/3800/3900 段）。 */
    private static final BlockPos ORIGIN = new BlockPos(3700, 100, 2600);
    /** 矿脉：x ∈ [0,2]、y ∈ [0,1]、z ∈ [-1,1] ⇒ 18 格。 */
    private static final int VEIN_DX_MAX = 2;
    private static final int VEIN_DY_MAX = 1;
    /** 石壳（把矿脉整块包住）。 */
    private static final int SHELL_DX_MIN = -1;
    private static final int SHELL_DX_MAX = 3;
    private static final int SHELL_DY_MIN = -1;
    private static final int SHELL_DY_MAX = 2;
    private static final int SHELL_DZ = 2;
    /** 平台（bot 站位）。 */
    private static final int PAD_DX_MIN = -4;
    private static final int PAD_DX_MAX = -2;
    private static final int PAD_DZ = 1;
    /** bot 脚位（相对原点）：紧贴石壳西面。 */
    private static final int BOT_DX = -2;
    private static final int SCAN_RADIUS = 8;
    /** 矿脉总格数（= 作业配额）。 */
    private static final int ORE_COUNT = (VEIN_DX_MAX + 1) * (VEIN_DY_MAX + 1) * (2 * SHELL_DZ + 1);
    /**
     * 判据下限 = **2026-09-24 实测值**（`30/30`，连跑两次：4421 / 4540 tick）。
     *
     * <p>历史：`D-389` 那会儿实测只有 **12/30**（剩余 18 格全 `found_but_unminable`）⇒ 下限只能写 12。
     * `PL-1` 切片 1 的**过期证明重评**交付后（`MineJob.proofExpired`：失败时记 26 邻域证明，
     * 只有邻域变了才**有界**重评），同一夹具两跑都是 `mined=30/30` · `orphans=0` · `staleRetries=18`
     * ⇒ 按实测把下限抬到 **28**（留两格余量：每格的收集阶段时长随掉落物落点波动，见 `D-424`）。
     * 复核触发：连续三次都是 `30/30` ⇒ 抬到 30（判据只许紧、不许松）。
     */
    private static final int MIN_MINED = 28;
    /**
     * 作业自己的上限与夹具上限（`PL-1` 2026-09-24 实测重定）：重评机制让"每格都要付一次收集阶段"
     * （≈130 tick/格）⇒ 挖穿 30 格 ≈ 4400 tick，原 4200/4600 会把作业**卡在最后一格**上收场
     * （实测 `mined 29/30` + `goal_timeout`）。现在给足余量，让终态由**作业自己达成配额**决定，
     * 而不是由时钟落点决定 ⇒ 判决可复现（并留一格余量给 MIN_MINED 的判据）。
     */
    private static final int JOB_MAX_TICKS = 5200;
    private static final int BUDGET_TICKS = 5400;

    private enum Phase { SETUP, RUN, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private ScopeBuffer scope;
    private MineJob job;
    private int mined;
    private String terminal = "-";
    private int veinPropagations;
    private int veinEnqueued;
    private int transientFailures;
    /** ⭐ `PL-1`：作业**真的重评**过期证明的次数（0 ⇒ 新能力是死代码，夹具会红）。 */
    private int staleRetries;

    public MineVeinPropagationCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MineVeinPropagationCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.offset(1, 0, 0));
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
            BotLog.warn("[VeinProp] FAIL {}", what);
        }
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            return finish(level);
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    buildScene(level);
                }
                if (phaseTicks >= 3) {
                    startJob(level);
                    phase = Phase.RUN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case RUN -> {
                if (job != null) {
                    Task.Status status = job.tick();
                    mined = job.subTasks().isEmpty() ? mined : mined;
                    if (status != Task.Status.RUNNING) {
                        mined = readMined();
                        terminal = String.valueOf(status);
                        veinPropagations = job.veinPropagations();
                        veinEnqueued = job.veinEnqueued();
                        transientFailures = job.transientFailureCount();
                        staleRetries = job.staleProofRetries();
                        phase = Phase.DONE;
                        phaseTicks = 0;
                    }
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                return finish(level);
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    /** 作业的成功格数：从 `MineJob` 的进度摘要里取（`mined X/Y`）。 */
    private int readMined() {
        String summary = job == null ? "" : job.progressSummary();
        if (summary == null) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("mined (\\d+)").matcher(summary);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private Task.Status finish(ServerLevel level) {
        if (phase != Phase.DONE || totalTicks > BUDGET_TICKS || mined == 0) {
            mined = job == null ? mined : readMined();
            veinPropagations = job == null ? veinPropagations : job.veinPropagations();
            veinEnqueued = job == null ? veinEnqueued : job.veinEnqueued();
            transientFailures = job == null ? transientFailures : job.transientFailureCount();
            staleRetries = job == null ? staleRetries : job.staleProofRetries();
        }
        // 超时路径下作业可能还在跑：不再让它继续（`Job` 没有 `cancel()`；置空引用即停在本夹具侧）
        if (totalTicks > BUDGET_TICKS) {
            job = null;
        }
        if (!reported) {
            reported = true;
            // ⭐ `PL-1` 取证读数（**只读**）：作业终态后复评每一格剩余矿石。
            // ⚠️ **必须在判据之前**跑 —— 判据行里要印"剩余矿石格"，而它是这个探针数出来的
            // （2026-09-24 实测踩过：放在判据之后 ⇒ 红臂日志里那行印成"剩余矿石格=0"，与 SUMMARY 的 18 矛盾）。
            probeOrphans(level);
            check("前提：矿脉真的建起来了（中心是铁矿石、外面是石头）", premiseOre && premiseShell);
            check("⭐ 沿脉传播**必须真的被触发**（`veinPropagations > 0`，实测 " + veinPropagations
                            + "；=0 ⇒ 本夹具没测到被改的那条路径）",
                    veinPropagations > 0);
            check("⭐ 沿脉走之后**不许**再出现昂贵搜索被限流（`search_incomplete` 实测 "
                            + transientFailures + " 次；真机同位置是 33 次 × 196 ms ⇒ 卡顿载荷）",
                    transientFailures == 0);
            check("⭐ `PL-1` **过期证明重评必须真的被触发**（`staleRetries > 0`，实测 " + staleRetries
                            + "；=0 ⇒ 新能力是死代码，或被 `already_attempted` 永久了结挡住）",
                    staleRetries > 0);
            // ⚠️ **完成度是读数、不是无条件门**：2026-09-22 实测 12/30 是**当前能力的真实上限** ——
            // 剩下的 18 格全部 `found_but_unminable`（`direct=no_valid_standing_point /
            // tunnel=no_reachable / enter=unreachable`），且把 `MAX_APPROACH_PLANS 3→13` **完全没改变结果**
            // （决定性实验，两次都 12/30）⇒ 瓶颈是"被矿石自己包住的目标"给不出站位/进入方案，
            // **不是** A2 截断、也**不是**搜索预算（本轮 `search_incomplete=0`）。
            // ⇒ 这里只钉"不许比已测值退化"；等 `D-391` 收口后**必须把这个下限抬上去**。
            check("矿簇必须被挖穿（mined=" + mined + " ≥ " + MIN_MINED + " / " + ORE_COUNT
                            + "，terminal=" + terminal + "，剩余矿石格=" + orphanCount
                            + "；2026-09-24 实测 30/30）",
                    mined >= MIN_MINED);
            cleanup(level);
            BotLog.info("[VeinProp] SUMMARY checks={} failures={} mined={}/{} veinPropagations={}"
                            + " veinEnqueued={} transientFailures={} terminal={} ticks={}"
                            + " orphans={} orphanPlannableNow={} staleRetries={} → {}",
                    checks, failures.size(), mined, ORE_COUNT, veinPropagations, veinEnqueued,
                    transientFailures, terminal, totalTicks, orphanCount, orphanPlannable,
                    staleRetries, failures.isEmpty() ? "PASS" : "FAIL");
            for (String line : findings) {
                BotLog.info("[VeinProp]   {}", line);
            }
            for (String line : failures) {
                BotLog.warn("[VeinProp]   {}", line);
            }
            if (observer != null) {
                observer.sendSystemMessage(Component.literal(
                        "[alice] 沿脉传播夹具：" + (failures.isEmpty() ? "PASS" : "FAIL")
                                + " checks=" + checks + " failures=" + failures.size()
                                + "（mined=" + mined + "/" + ORE_COUNT
                                + " 传播=" + veinPropagations + " 入队=" + veinEnqueued + "）"));
            }
        }
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    private boolean reported;
    private boolean premiseOre;
    private boolean premiseShell;

    /**
     * ⭐ `PL-1` 读数：作业终态后，对每一格**仍是矿石**的目标再规划一次（**只读**）。
     *
     * <p>为什么需要它：夹具的 18 个失败目标在**尝试时刻**确实没有可行方案（邻格还是矿石 ⇒ 没有可站格 /
     * 没有视线），但作业继续挖下去会**改变邻域**（西列被挖空）⇒ 之前那份"无解"证明**过期**。
     * 光读作业日志分不出这两种情况（`already_attempted` 对两者长得一样）⇒ 必须补这个读数。
     */
    private void probeOrphans(ServerLevel level) {
        com.dddgn.alice.task.mining.MiningPlanner planner = new com.dddgn.alice.task.mining.MiningPlanner();
        java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
        for (int dx = 0; dx <= VEIN_DX_MAX; dx++) {
            for (int dy = 0; dy <= VEIN_DY_MAX; dy++) {
                for (int dz = -SHELL_DZ; dz <= SHELL_DZ; dz++) {
                    BlockPos cell = ORIGIN.offset(dx, dy, dz);
                    if (!level.getBlockState(cell).is(Blocks.IRON_ORE)) {
                        continue;
                    }
                    orphanCount++;
                    com.dddgn.alice.task.mining.MiningPlanner.Result result = planner.plan(bot, cell,
                            com.dddgn.alice.task.mining.MiningBudget.forTarget(bot, level, cell, true), false);
                    if (result.success()) {
                        orphanPlannable++;
                    } else {
                        reasons.merge(result.failureReason(), 1, Integer::sum);
                    }
                    BotLog.info("[VeinProp复评探针] pos={} ok={} mode={} reason={}",
                            cell.toShortString(), result.success(),
                            result.plan() == null ? "-" : result.plan().mode(), result.failureReason());
                }
            }
        }
        BotLog.info("[VeinProp复评探针] SUMMARY 孤儿={} 现在可规划={} 仍然无解={} reasons={}",
                orphanCount, orphanPlannable, orphanCount - orphanPlannable, reasons);
    }

    private int orphanCount;
    private int orphanPlannable;

    // ==================== 场景与作业 ====================

    private void buildScene(ServerLevel level) {
        for (int dx = SHELL_DX_MIN; dx <= SHELL_DX_MAX; dx++) {
            for (int dy = SHELL_DY_MIN; dy <= SHELL_DY_MAX; dy++) {
                for (int dz = -SHELL_DZ; dz <= SHELL_DZ; dz++) {
                    place(level, ORIGIN.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        int oreCount = 0;
        for (int dx = 0; dx <= VEIN_DX_MAX; dx++) {
            for (int dy = 0; dy <= VEIN_DY_MAX; dy++) {
                for (int dz = -SHELL_DZ; dz <= SHELL_DZ; dz++) {
                    place(level, ORIGIN.offset(dx, dy, dz), Blocks.IRON_ORE);
                    oreCount++;
                }
            }
        }
        for (int dx = PAD_DX_MIN; dx <= PAD_DX_MAX; dx++) {
            for (int dz = -PAD_DZ; dz <= PAD_DZ; dz++) {
                place(level, ORIGIN.offset(dx, -1, dz), Blocks.STONE);
            }
        }
        premiseOre = level.getBlockState(ORIGIN).is(Blocks.IRON_ORE);
        premiseShell = level.getBlockState(ORIGIN.offset(-1, 0, 0)).is(Blocks.STONE);
        BotLog.info("[VeinProp] SETUP origin={} 矿脉={}格（3×2×3，26 邻接）石壳={}×{}×{} 平台={}..{}", 
                ORIGIN.toShortString(), oreCount, SHELL_DX_MAX - SHELL_DX_MIN + 1,
                SHELL_DY_MAX - SHELL_DY_MIN + 1, 2 * SHELL_DZ + 1, PAD_DX_MIN, PAD_DX_MAX);
    }

    private void startJob(ServerLevel level) {
        BlockPos foot = ORIGIN.offset(BOT_DX, 0, 0);
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, -90.0F, 0.0F);
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        scope = new ScopeBuffer();
        job = new MineJob(bot,
                GoalSpec.mineBlocks(ORIGIN.offset(1, 0, 0), SCAN_RADIUS, ORE_COUNT, JOB_MAX_TICKS),
                scope,
                new MineCandidateSource(MineCandidateSource.Target.ofBlock(Blocks.IRON_ORE), SCAN_RADIUS),
                new NearestPolicy());
        BotLog.info("[VeinProp] 作业开始 quota={} radius={} foot={}", ORE_COUNT, SCAN_RADIUS,
                foot.toShortString());
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    private void cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        FixtureToolKit.resetInventory(bot);
        BlockPos home = ORIGIN.offset(BOT_DX, 0, 0);
        bot.teleportTo(level, home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 0.0F, 0.0F);
    }
}
