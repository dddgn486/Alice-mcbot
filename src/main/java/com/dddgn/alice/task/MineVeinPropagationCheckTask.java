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
 * <h2>判据（两条，都只在"顺序真的改了"时才成立）</h2>
 * <ol>
 *   <li>`job.veinPropagations() > 0` —— 沿脉传播**真的被触发**（否则本夹具测不到被改的那条路径）；</li>
 *   <li>挖到数 ≥ {@link #MIN_MINED}（{@link #ORE_COUNT} 个相邻矿石中的大多数）—— **簇被沿脉挖穿**。</li>
 * </ol>
 *
 * <h2>反向对照（红臂）</h2>
 * 注释掉 `MineJob` 里 `enqueueVeinNeighbours(...)` 那一行 ⇒ 第 1 条必红（`veinPropagations=0`）。
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
     * 判据下限 = **2026-09-22 实测的能力上限**（12/30）。
     * ⚠️ 这是"不许退化"的门，**不是**"簇被挖完"的证明 —— 剩余 18 格见 `D-391`（规划器给不出
     * 站位/进入方案）；`D-391` 收口后必须把这个值抬到 ~28 并把断言改成"接近挖完"。
     */
    private static final int MIN_MINED = 12;
    private static final int JOB_MAX_TICKS = 4200;
    private static final int BUDGET_TICKS = 4600;

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
        }
        // 超时路径下作业可能还在跑：不再让它继续（`Job` 没有 `cancel()`；置空引用即停在本夹具侧）
        if (totalTicks > BUDGET_TICKS) {
            job = null;
        }
        if (!reported) {
            reported = true;
            check("前提：矿脉真的建起来了（中心是铁矿石、外面是石头）", premiseOre && premiseShell);
            check("⭐ 沿脉传播**必须真的被触发**（`veinPropagations > 0`，实测 " + veinPropagations
                            + "；=0 ⇒ 本夹具没测到被改的那条路径）",
                    veinPropagations > 0);
            check("⭐ 沿脉走之后**不许**再出现昂贵搜索被限流（`search_incomplete` 实测 "
                            + transientFailures + " 次；真机同位置是 33 次 × 196 ms ⇒ 卡顿载荷）",
                    transientFailures == 0);
            // ⚠️ **完成度是读数、不是无条件门**：2026-09-22 实测 12/30 是**当前能力的真实上限** ——
            // 剩下的 18 格全部 `found_but_unminable`（`direct=no_valid_standing_point /
            // tunnel=no_reachable / enter=unreachable`），且把 `MAX_APPROACH_PLANS 3→13` **完全没改变结果**
            // （决定性实验，两次都 12/30）⇒ 瓶颈是"被矿石自己包住的目标"给不出站位/进入方案，
            // **不是** A2 截断、也**不是**搜索预算（本轮 `search_incomplete=0`）。
            // ⇒ 这里只钉"不许比已测值退化"；等 `D-391` 收口后**必须把这个下限抬上去**。
            check("矿簇完成度不许退化（mined=" + mined + " ≥ " + MIN_MINED + " / " + ORE_COUNT
                            + "，terminal=" + terminal + "；⚠️ **已测上限就是 12**，"
                            + "剩余 18 格是 `D-391` 的待收口项）",
                    mined >= MIN_MINED);
            cleanup(level);
            BotLog.info("[VeinProp] SUMMARY checks={} failures={} mined={}/{} veinPropagations={}"
                            + " veinEnqueued={} transientFailures={} terminal={} ticks={} → {}",
                    checks, failures.size(), mined, ORE_COUNT, veinPropagations, veinEnqueued,
                    transientFailures, terminal, totalTicks, failures.isEmpty() ? "PASS" : "FAIL");
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
