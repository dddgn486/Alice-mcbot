package com.dddgn.alice.fixture;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.write.Attribution;
import com.dddgn.alice.write.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;

/**
 * ⭐ `1-2`（批次 1，`D-533`）· `D1` ＋ `D2` 判据夹具 —— **破坏判据的公共入口** ＋
 * **「正上方计价 / 侧邻禁止」的分工**。
 *
 * <p><b>为什么有这一步</b>：`1-2` 往内核里加了两条 Baritone 差异（`D-036` 规则 3：抄结构、代入
 * Alice 实测值）——
 * <ol>
 *   <li><b>`D1`</b>（{@link BlockBreakSafety#hazardRefusal}，对照 Baritone `MovementHelper.avoidBreaking:68-82`
 *       ＋ `avoidAdjacentBreaking:84-108`）：③冰 ④虫蚀 ⑤侧邻危险（液体 / 未支撑落体）；</li>
 *   <li><b>`D2`</b>（{@link BlockInteraction#estimateBreakTicks(ServerPlayer, ServerLevel, BlockPos, boolean)}，
 *       对照 Baritone `getMiningDurationTicks:600-605` 的 `includeFalling`）：**正上方**落体 ⇒ **计价累加**。</li>
 * </ol>
 * ⚠️ 这两条的**分工**才是要害，也是本夹具最想钉住的一句：
 * **正上方是"钱"（计价），侧邻是"命"（禁止）** —— 把正上方也做成禁止，等于凭空砍掉"拆一格、
 * 上面那块自己掉下来"这条正常挖掘流程的下半段。
 *
 * <p><b>判据（全部读方块/读返回值，⛔ 不读日志）</b>：
 * <ol>
 *   <li>③ `ICE` ⇒ `ice_clearing_block`；④ `INFESTED_STONE` ⇒ `infested_clearing_block`；</li>
 *   <li>⑤ 侧邻**液体源**（四面围死的水源）⇒ `liquid_source_neighbour`；正上方水源 ⇒ `liquid_above_neighbour`；</li>
 *   <li>⭐ **对照臂一**：孤零零的石头 ⇒ `null`（不许"凡破坏都拒"）；侧邻**有支撑**沙砾 ⇒ `null`；</li>
 *   <li>⭐⭐ **对照臂二（分工）**：**正上方**沙砾 ⇒ `hazardRefusal == null`（**不禁止**）而
 *       `estimateBreakTicks(…, true) > estimateBreakTicks(…, false)`（**要计价**）；</li>
 *   <li>⭐ **公共入口**：上面每一条都要**再经一遍** `BlockInteraction.breakRefusal(...)` 得到同一个码
 *       —— 搜索（`SurfaceMovementProvider` 三处都先问 `BlockInteraction.breakable`）与执行走的就是这口；
 *       ⛔ 只测 `BlockBreakSafety` 会漏掉"接线没接上"这一类失败；</li>
 *   <li>⭐ **一列只给最高格计价**：`estimateColumnBreakTicks` 必须 == `脚位(false) ＋ 头位(true)`，
 *       且**严格小于**"两格都按 `true`"（后者会把同一段落体链算两遍）。</li>
 * </ol>
 *
 * <p><b>⚠️ 一条如实登记的缺口（不是没做，是<u>做不出稳定场景</u>）</b>：`D1` ⑤ 的
 * `unsupported_falling_neighbour`（侧邻**无支撑**落体）在**稳定世界**里存不住 ——
 * 沙砾/沙的 `below` 一旦是 air/fire/液体/`REPLACEABLE_BY_TREES`（就是 `FallingBlock.isFree` 的定义），
 * 它**自己就会掉**（原版 `FallingBlock.onPlace` 排 2 tick 后下落）⇒ 夹具只能在同一 tick 里
 * 摆完就问，属于"用竞态测竞态"。⛔ 本轮**不做**，登记在台账（`O42`）：留证的形态 = Baritone `:90-96`
 * 的逐字对照 ＋ 本类 {@link #neighbourHazard} 的读码，而不是一个会 flaky 的场景。
 *
 * <p><b>纪律</b>：自建孤立长方体场景（`y=-1` 一层地板，边界外一圈全空气）、**先传送再 `setBlock`**
 * （未加载区块里 `setBlock` 静默 0 改动，`D-244`）、结束**按快照逐格还原并复核**、
 * 失败路径同样收尾（`remaining` 是**复核出来的**不匹配格数，⛔ 不是"记了多少个快照"）。
 */
public final class BreakHazardCheckTask implements Task {

    /** 场景原点（段 4200：⛔ 不碰 `K2AdjacentGoalCheckTask` 的 4000/4100 两段）。 */
    private static final BlockPos ORIGIN = new BlockPos(4200, 100, 2600);

    private static final int BUDGET_TICKS = 240;
    private static final int FLOOR_MAX_X = 23;

    /** 用例（全部落在 `y=0`，间距 ≥2 ⇒ 各自的水平邻格互不干扰）。 */
    private static final BlockPos ICE = ORIGIN.offset(2, 0, 0);
    private static final BlockPos INFESTED = ORIGIN.offset(4, 0, 0);
    private static final BlockPos WATER_SRC = ORIGIN.offset(6, 0, 0);      // 目标石头
    private static final BlockPos WATER_SRC_LIQ = WATER_SRC.east();        // 它东侧的水源（四面围死）
    private static final BlockPos WATER_ABOVE = ORIGIN.offset(10, 0, 0);   // 目标石头
    private static final BlockPos GRAVEL_ABOVE = ORIGIN.offset(14, 0, 0);  // 目标石头，正上方沙砾
    private static final BlockPos PLAIN = ORIGIN.offset(16, 0, 0);         // 对照臂：纯石头
    private static final BlockPos SUPPORTED_SIDE = ORIGIN.offset(18, 0, 0);// 对照臂：侧邻有支撑沙砾
    private static final BlockPos COL_FOOT = ORIGIN.offset(21, 0, 0);      // 一列：石 / 砾 / 砾
    private static final BlockPos COL_HEAD = COL_FOOT.above();
    private static final BlockPos COL_TOP = COL_FOOT.above(2);

    private enum Phase { SETUP, BUILD, PROBE, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> findings = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    /** 本夹具写过的每一格 + 它进入前的样子（收尾逐格还原、逐格复核）。 */
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    private int snapshots = -1;
    private int remaining = -1;
    private double plainBase = -1.0D;
    private double plainWithFalling = -1.0D;
    private double gravelAboveBase = -1.0D;
    private double gravelAboveWithFalling = -1.0D;
    private double columnCost = -1.0D;
    private double columnNaive = -1.0D;
    private long msTotal;

    public BreakHazardCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "BreakHazardCheck";
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
            BotLog.warn("[BreakHazard] FAIL {}", what);
        }
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            cleanup(level);
            return Task.Status.FAILED;
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    // 先传送（玩家 ticket 同步加载区块）⇒ 再 `setBlock` 才真的落下去（`D-244`）。
                    bot.teleportTo(level, ORIGIN.getX() + 0.5D, ORIGIN.getY(),
                            ORIGIN.getZ() + 0.5D, -90.0F, 0.0F);
                }
                if (phaseTicks >= 4) {
                    phase = Phase.BUILD;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case BUILD -> {
                if (phaseTicks >= 1) {
                    buildScene(level);
                    phase = Phase.PROBE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case PROBE -> {
                if (phaseTicks >= 1) {
                    long t0 = System.nanoTime();
                    probe(level);
                    msTotal = (System.nanoTime() - t0) / 1_000_000L;
                    phase = Phase.DONE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                remaining = cleanup(level);
                check("副作用边界：收尾后**复核**出的不匹配格数必须 == 0（实测 " + remaining
                        + "；本刀起 `remaining` 是复核出来的，⛔ 不是快照数）", remaining == 0);
                BotLog.info("[BreakHazard] SUMMARY checks={} failures={} plainBase={} plainFalling={}"
                                + " graveBase={} graveFalling={} colCost={} colNaive={}"
                                + " snapshots={} remaining={} ms={} → {}",
                        checks, failures.size(), plainBase, plainWithFalling, gravelAboveBase,
                        gravelAboveWithFalling, columnCost, columnNaive, snapshots, remaining, msTotal,
                        failures.isEmpty() ? "PASS" : "FAIL");
                for (String line : findings) {
                    BotLog.info("[BreakHazard]   {}", line);
                }
                for (String line : failures) {
                    BotLog.warn("[BreakHazard]   {}", line);
                }
                if (observer != null) {
                    observer.sendSystemMessage(Component.literal(
                            "[alice] 破坏危险判据夹具：" + (failures.isEmpty() ? "PASS" : "FAIL")
                                    + " checks=" + checks + " failures=" + failures.size()
                                    + "（D1 危险码 ＋ D2 正上方计价 / 侧邻禁止的分工）"));
                }
                return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    // ==================== 场景（自建、孤立、可还原） ====================

    private void buildScene(ServerLevel level) {
        // 地板一整层（用例的支撑）＋ 用例上方清成空气（边界外一圈也是空气）。
        for (int dx = -1; dx <= FLOOR_MAX_X + 1; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                place(level, ORIGIN.offset(dx, -1, dz), Blocks.STONE);
                for (int dy = 0; dy <= 4; dy++) {
                    place(level, ORIGIN.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        // ③ 冰 / ④ 虫蚀石：孤零零一格（它们的水平邻格是空气 ⇒ 只测自身那一类）。
        place(level, ICE, Blocks.ICE);
        place(level, INFESTED, Blocks.INFESTED_STONE);
        // ⑤a 侧邻液体源：水源**四面围死**（下方是地板、东南北三面填石、西面就是目标石头）
        // ⇒ 它一滴也流不出去 ⇒ 场景稳定、无世界的额外副作用（`D-244` 的反面：不许留流体的账）。
        place(level, WATER_SRC, Blocks.STONE);
        place(level, WATER_SRC_LIQ, Blocks.WATER);
        place(level, WATER_SRC_LIQ.east(), Blocks.STONE);
        place(level, WATER_SRC_LIQ.north(), Blocks.STONE);
        place(level, WATER_SRC_LIQ.south(), Blocks.STONE);
        // ⑤b 正上方液体：目标石头 ＋ 它头顶那格水源（同样围死：顶上加盖、四邻填石）。
        place(level, WATER_ABOVE, Blocks.STONE);
        place(level, WATER_ABOVE.above(), Blocks.WATER);
        place(level, WATER_ABOVE.above(2), Blocks.STONE);
        place(level, WATER_ABOVE.above().east(), Blocks.STONE);
        place(level, WATER_ABOVE.above().west(), Blocks.STONE);
        place(level, WATER_ABOVE.above().north(), Blocks.STONE);
        place(level, WATER_ABOVE.above().south(), Blocks.STONE);
        // ⭐ 分工的正例：目标石头**正上方**是沙砾（会掉下来 ⇒ 计价），但**不禁止**。
        place(level, GRAVEL_ABOVE, Blocks.STONE);
        place(level, GRAVEL_ABOVE.above(), Blocks.GRAVEL);
        // 对照臂：纯石头；侧邻沙砾但**有支撑**（沙砾下面是地板 ⇒ `FallingBlock.isFree` 为假）。
        place(level, PLAIN, Blocks.STONE);
        place(level, SUPPORTED_SIDE, Blocks.STONE);
        place(level, SUPPORTED_SIDE.east(), Blocks.GRAVEL);
        // 一列破坏格（脚=石 / 头=砾 / 顶=砾）：用来钉"只有最高格累加落体"。
        place(level, COL_FOOT, Blocks.STONE);
        place(level, COL_HEAD, Blocks.GRAVEL);
        place(level, COL_TOP, Blocks.GRAVEL);
        snapshots = touched.size();
        BotLog.info("[BreakHazard] SETUP origin={} snapshots={} ice={} infested={} waterSrc={}"
                        + " waterAbove={} gravelAbove={} plain={} supportedSide={} column={}",
                ORIGIN.toShortString(), snapshots, ICE.toShortString(), INFESTED.toShortString(),
                WATER_SRC.toShortString(), WATER_ABOVE.toShortString(), GRAVEL_ABOVE.toShortString(),
                PLAIN.toShortString(), SUPPORTED_SIDE.toShortString(), COL_FOOT.toShortString());
    }

    private void place(ServerLevel level, BlockPos pos, Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    // ==================== 判据 ====================

    private void probe(ServerLevel level) {
        // ---- 前提（自断言，不静默）----
        check("前提：场景已落地（`snapshots` > 0，实测 " + snapshots + "）", snapshots > 0);
        check("前提：冰块还在（⛔ 没被顶光照化 —— 原版 `IceBlock.randomTick` 只看 BLOCK 光）",
                level.getBlockState(ICE).is(Blocks.ICE));
        check("前提：围死的水源仍在（场景稳定）", level.getBlockState(WATER_SRC_LIQ).is(Blocks.WATER));
        check("前提：正上方沙砾仍在（有支撑 ⇒ 不会掉）",
                level.getBlockState(GRAVEL_ABOVE.above()).is(Blocks.GRAVEL));

        // ---- D1：哪些破坏该被拒（读稳定码）----
        check("③ 冰 ⇒ `ice_clearing_block`（实测 " + BlockBreakSafety.hazardRefusal(level, ICE) + "）",
                "ice_clearing_block".equals(BlockBreakSafety.hazardRefusal(level, ICE)));
        check("④ 虫蚀石 ⇒ `infested_clearing_block`（实测 "
                        + BlockBreakSafety.hazardRefusal(level, INFESTED) + "）",
                "infested_clearing_block".equals(BlockBreakSafety.hazardRefusal(level, INFESTED)));
        check("⑤ 侧邻液体源 ⇒ `liquid_source_neighbour`（实测 "
                        + BlockBreakSafety.hazardRefusal(level, WATER_SRC) + "）",
                "liquid_source_neighbour".equals(BlockBreakSafety.hazardRefusal(level, WATER_SRC)));
        check("⑤ 正上方液体 ⇒ `liquid_above_neighbour`（实测 "
                        + BlockBreakSafety.hazardRefusal(level, WATER_ABOVE) + "）",
                "liquid_above_neighbour".equals(BlockBreakSafety.hazardRefusal(level, WATER_ABOVE)));

        // ---- D1 对照臂（防"凡破坏都拒"的假绿）----
        check("对照臂：纯石头 ⇒ **允许**（`hazardRefusal == null`，实测 "
                        + BlockBreakSafety.hazardRefusal(level, PLAIN) + "）",
                BlockBreakSafety.hazardRefusal(level, PLAIN) == null);
        check("对照臂：侧邻沙砾**有支撑** ⇒ **允许**（`unsupported_falling_neighbour` 的前提不成立，实测 "
                        + BlockBreakSafety.hazardRefusal(level, SUPPORTED_SIDE) + "）",
                BlockBreakSafety.hazardRefusal(level, SUPPORTED_SIDE) == null);

        // ---- ⭐⭐ D1/D2 的分工：正上方落体 ⇒ 不禁止、只计价 ----
        check("⭐ 分工：**正上方**沙砾 ⇒ **不禁止**（`hazardRefusal == null`，实测 "
                        + BlockBreakSafety.hazardRefusal(level, GRAVEL_ABOVE) + "）",
                BlockBreakSafety.hazardRefusal(level, GRAVEL_ABOVE) == null);

        // ---- ⭐ 公共入口：搜索与执行走的那一口必须得到同一个码 ----
        Attribution path = Attribution.of(taskName(), WriteReason.PATH_ACCESS);
        Attribution target = Attribution.of(taskName(), WriteReason.EXPECTED_TARGET);
        check("⭐ 公共入口（清障面）：`breakRefusal(PATH_ACCESS)` 冰 ⇒ `ice_clearing_block`（实测 "
                        + BlockInteraction.breakRefusal(bot, level, ICE, path) + "）",
                "ice_clearing_block".equals(BlockInteraction.breakRefusal(bot, level, ICE, path)));
        check("⭐ 公共入口（**任务目标**面）：`breakRefusal(EXPECTED_TARGET)` 冰 ⇒ 同一个码（实测 "
                        + BlockInteraction.breakRefusal(bot, level, ICE, target) + "）"
                        + " —— 对照 Baritone：同一个 `avoidBreaking` 也在 `MineProcess:489` 上跑",
                "ice_clearing_block".equals(BlockInteraction.breakRefusal(bot, level, ICE, target)));
        check("⭐ 公共入口：正上方沙砾在**两面**都**不被拒**（清障="
                        + BlockInteraction.breakRefusal(bot, level, GRAVEL_ABOVE, path)
                        + " / 目标=" + BlockInteraction.breakRefusal(bot, level, GRAVEL_ABOVE, target) + "）",
                BlockInteraction.breakRefusal(bot, level, GRAVEL_ABOVE, path) == null
                        && BlockInteraction.breakRefusal(bot, level, GRAVEL_ABOVE, target) == null);
        check("⭐ `breakable(...)`（搜索侧真正调的那个）对冰必须 == false",
                !BlockInteraction.breakable(bot, level, ICE, path));

        // ---- D2：正上方落体 ⇒ 计价累加 ----
        plainBase = BlockInteraction.estimateBreakTicks(bot, level, PLAIN, false);
        plainWithFalling = BlockInteraction.estimateBreakTicks(bot, level, PLAIN, true);
        gravelAboveBase = BlockInteraction.estimateBreakTicks(bot, level, GRAVEL_ABOVE, false);
        gravelAboveWithFalling = BlockInteraction.estimateBreakTicks(bot, level, GRAVEL_ABOVE, true);
        check("对照臂：正上方是空气 ⇒ `includeFalling` 前后**同值**（" + plainBase + " vs "
                        + plainWithFalling + "）",
                plainBase == plainWithFalling);
        check("⭐ D2：正上方沙砾 ⇒ `includeFalling=true` **更贵**（" + gravelAboveBase + " → "
                        + gravelAboveWithFalling + "）",
                gravelAboveWithFalling > gravelAboveBase);
        double gravelOnly = BlockInteraction.estimateBreakTicks(bot, level, GRAVEL_ABOVE.above(), true);
        check("⭐ D2 的增量**恰好等于**沙砾自身那一格（" + (gravelAboveWithFalling - gravelAboveBase)
                        + " == " + gravelOnly + "）—— 多算少算都算错",
                Math.abs((gravelAboveWithFalling - gravelAboveBase) - gravelOnly) < 1.0E-6D);

        // ---- D2：一列里只有最高那格累加（否则同一段落体链会被算两遍）----
        columnCost = BlockInteraction.estimateColumnBreakTicks(bot, level, List.of(COL_FOOT, COL_HEAD));
        columnNaive = BlockInteraction.estimateBreakTicks(bot, level, COL_FOOT, true)
                + BlockInteraction.estimateBreakTicks(bot, level, COL_HEAD, true);
        double footOnly = BlockInteraction.estimateBreakTicks(bot, level, COL_FOOT, false);
        double headWithFalling = BlockInteraction.estimateBreakTicks(bot, level, COL_HEAD, true);
        check("⭐ 一列：`estimateColumnBreakTicks` == 脚位(false) ＋ 头位(true)（" + columnCost
                        + " vs " + (footOnly + headWithFalling) + "）",
                Math.abs(columnCost - (footOnly + headWithFalling)) < 1.0E-6D);
        check("⭐ 一列：必须**严格小于**「两格都按 true」（" + columnCost + " < " + columnNaive
                        + "）—— 差值 = 头位那段落体链被重复计价的部分",
                columnCost < columnNaive);
    }

    // ==================== 收尾（逐格还原 + 逐格复核） ====================

    /**
     * 还原所有写过的格，并**复核**（读回来跟快照比）—— 返回值 = 不匹配格数。
     *
     * <p>⚠️ 为什么不是"`touched.size()` 清空后 == 0"那种写法：那是**恒真**的（清空之后当然是 0），
     * 属于"看起来在测、其实什么都没测"的假绿。这里必须**读世界**才算判据
     * （同族教训见台账 `O42`：`K2AdjacentGoalCheckTask` 的 `remaining` 就是恒真写法）。
     */
    private int cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        int mismatch = 0;
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                mismatch++;
            }
        }
        touched.clear();
        bot.teleportTo(level, ORIGIN.getX() + 0.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D, 0.0F, 0.0F);
        return mismatch;
    }
}
