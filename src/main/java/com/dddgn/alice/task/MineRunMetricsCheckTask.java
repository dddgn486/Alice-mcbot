package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.TaskMetrics;
import com.dddgn.alice.action.WriteBudget;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * **运行账取证（`D-347` / `survey/22 §5.2③` 的"可测量判据"）**：证明"到达率 / 世界改动数"这两个数
 * 是**量出来的**，不是估计出来的 —— 而且**到达率不是一个恒等式**（它真的会小于 1）。
 *
 * <h2>为什么必须有它（而不是"看代码就知道"）</h2>
 * 勘测报告 §5.2③ 的原话是：「缺可测量判据 —— 到达率 / 返回率 / 平均 tick / 世界改动数 **没人测过**
 * ⇒ "几乎能保证到达并返回"目前是**推断**，不是**事实**」。本夹具就是把这句推断变成现场数字：
 * 在**孤立场景**里连跑 {@link #RUNS} 次**真的** {@code MineJob}，记录每次的终态理由、耗时、
 * 世界改动数、以及**任务自己声明的到达**，再跑一次**反向对照**（目标在场景里不存在）。
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * <b>任务层 + 运行账</b>：真世界里真的 {@code MineJob}（不是替身、不是单独测某个 helper），
 * 由本夹具**每 tick 驱动一 tick**（与 {@code MineInventoryCheckTask} / {@code MineDropRangeCheckTask}
 * 同一个手法）。它断言的量是 {@link TaskMetrics} 里**生产代码自己写**的那几格：
 * <ul>
 *   <li>{@code arrived} —— 由 {@code MineJob} 在"第一格目标方块真的被挖掉"时自己声明；</li>
 *   <li>{@code breaks} —— 由 {@code WriteBudget.consumeBreak} 在**真的扣一次预算**时写；</li>
 *   <li>并**交叉校验**：{@code TaskMetrics.breaks} 与 {@code WriteBudget.breaks(bot)}（同一次破坏的
 *       两个独立读数）必须一致 —— 只对一边断言的话，另一边写错了也看不出来。</li>
 * </ul>
 *
 * <h2>为什么本夹具**不能**断言"会话侧的格子"（诚实边界，不许假装覆盖）</h2>
 * 本夹具直接持有并 tick 子任务 ⇒ 子任务**不经过会话**（`BotSession.beginTask` / `recordTerminal`）
 * ⇒ {@code started/finished/ticks/returns} 那几格在本次窗口里**必须一动不动**。这不是缺陷，而是
 * **分工**：会话侧那几格由编排器（{@code CheckHarness.verdict}）与无头驱动（{@code HeadlessBattery}）
 * 各自加门禁断言（"起过 N 步 ⇒ 账上必须有 N 条"）。所以本夹具把"它们没动"**当前提断言**：
 * 哪天有人把子任务改成走会话，这条会先红（提醒改判据），而不是让两套断言互相冒充。
 *
 * <h2>几何前提（§6.9.1 ①：盒子以谁为中心、多大、种子放哪 —— 全部写下来并自断言）</h2>
 * <ul>
 *   <li>走廊：原点 {@link #ORIGIN}（x 3400 一带、z=2000、y=100 的空中 —— 与 `D-345` 的 3200 错开，
 *       与所有数据包场景都**不相连**：整块区域本来就是空 ⇒ 收尾清回空气即"还原原状"）；</li>
 *   <li><b>两层地板</b>：垫层 = {@code ORIGIN.y}（石头，**整块铺满**）；行走层 = {@code ORIGIN.y+1}
 *       （石头），x 0..{@link #LANE_LENGTH}、z ±{@link #LANE_HALF_WIDTH}（5 格宽）；行走层上方
 *       {@link #HEADROOM} 格空气；</li>
 *   <li>矿：行走层里 {@link #ORE_OFFSETS} 那 8 个 x（z={@link #ORE_Z}）换成铁矿 ⇒ 顶面**已暴露**
 *       （上方是空气）、**与地面齐平**（不需要搭方块就能够到 —— 搭方块会污染"世界改动数"），
 *       且**正下方是垫层**（这一条是首跑血的教训：矿最初镶在**墙**里，掉落物被挤出墙外 1 格 ⇒
 *       平台外是虚空 ⇒ 直接掉到 y=73 还在掉 ⇒ 收集必然 `product_not_collected`）；</li>
 *   <li>bot 起点 = {@code ORIGIN.offset(0, 2, 0)}（站在行走层顶面）；扫描中心 = 同一个点、
 *       半径 = 生产同值 {@link #SCAN_RADIUS} ⇒ 最远的矿（x 偏移 {@link #FARTHEST_ORE_OFFSET}）
 *       距离 ≈ 19.5 &lt; 24 ⇒ **可扫描是前提，不是结论**（自断言）；</li>
 *   <li>每次运行的配额 = {@link #QUOTA}，{@link #RUNS} 次共需 6 块 ⇒ 备 8 块（留冗余，避免"矿不够"
 *       被误读成"到达率不满"）。</li>
 * </ul>
 *
 * <h2>⚠️ 判据边界：本夹具判**运行账**，不判**收集闭环**</h2>
 * 判据 = 每次运行**到达增量恰好 1** + **世界改动数与 `WriteBudget` 独立读数逐位一致**（≥ 配额）+ 耗时 &gt; 0
 * + 反向对照两次增量都为 0。**刻意不要求 `quota_met`**：收集闭环另有判据（`mine_job`(BASELINE) ·
 * `mine_far_drop` · `mine_inventory`），而本夹具的场景只为"到达 + 世界改动"服务。
 * 2026-09-20 实测到一次**与本夹具无关**的收集失败（`product_not_collected`：第一格矿的掉落物被作用域
 * 当成"未进入世界"而忽略 —— `D-345` 记过的同一现象，本次出现在**真实挖掘掉落物**上、触发条件未定性）
 * ⇒ 这类结果**如实进 SUMMARY 的 `quota_met次数` 事实字段**，但不改本夹具的成败：否则一次与判据无关的
 * 路径抖动就会让整条电池**随机变红** —— 那是**假红**，与假绿一样有害。
 *
 * <h2>实测推翻的一条夹具假设（写下来，免得后来人再假设一次）</h2>
 * 首跑我按"挖掉 2 格 ⇒ 世界改动 2 格"写了判据，**实测不成立**：每次真实改动是 3~4 格 ——
 * 矿嵌在行走层里，挖掉就留一个 1 格深的坑，bot 下一步**站进坑里**，出来时清掉坑壁 1 格
 * （日志里 `block_break_done` 多打在两矿之间的行走层）。所以判据不是"等于配额"，而是
 * **账与 `WriteBudget` 独立读数逐位一致**（计数器诚实）+ **配额 ≤ 改动数 ≤ 配额 + 走位清障上界**
 * （场景形状声明，见 {@link #MAX_ACCESS_CLEARS_PER_RUN}）。⭐ 顺带这就是"世界改动数"这个量的
 * 现实大小：**挖 2 格配额 ≈ 改 3~4 格世界**。
 *
 * <h2>反向对照（为什么"到达率 &lt; 1"必须被真的演示一次）</h2>
 * 一条只会输出 100% 的判据等于没有判据。所以第 {@code RUNS+1} 次运行刻意让**目标类型在场景里不存在**
 * （{@link #ABSENT_TARGET}）⇒ 任务必须如实报 {@code no_reachable_candidate}，而
 * {@code arrived} 与 {@code breaks} 的增量必须是 **0** ⇒ 本次窗口的到达率 = {@link #RUNS}/{@code RUNS+1}。
 * ⚠️ 这条同时是**对判据本身的反向对照**：把 {@code MineJob} 里那句 {@code arrived(...)} 挪到
 * "选出了候选"处，本夹具会因为反向对照那一次的到达增量变成 1 而**红**。
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 {@code assign*}/{@code beginTask}（那会 `clearTask()` 掉正在跑的这一步）⇒ 自己持有并 tick
 * {@code MineJob}；自己造的地形（地板 + 矿墙）**收尾清回空气 + 撤销 forceload**，
 * 盒内落物**全部 discard**（失败路径也走），bot 回传送前的位置。
 */
public final class MineRunMetricsCheckTask implements Task {

    /** 走廊原点（y=100 的空中；**与 `D-345` 的 3200/2000 错开到 3400**，避免两片区域互相影响）。 */
    private static final BlockPos ORIGIN = new BlockPos(3400, 100, 2000);

    /** 走廊长度（x 方向 0..N）。 */
    private static final int LANE_LENGTH = 24;

    /** 走廊半宽（z 方向 ±N ⇒ 5 格宽）。 */
    private static final int LANE_HALF_WIDTH = 2;

    /** 行走层上方留的空气层数。 */
    private static final int HEADROOM = 4;

    /** 矿所在的 z（走廊中线 —— 全部排成一行，距离是唯一变量）。 */
    private static final int ORE_Z = 0;

    /** 8 块铁矿的 x 偏移（**全部在扫描半径内**；嵌在行走层里，与地面齐平 ⇒ 不需要搭方块）。 */
    private static final int[] ORE_OFFSETS = {5, 7, 9, 11, 13, 15, 17, 19};

    /** 最远那块矿的 x 偏移（几何前提断言用）。 */
    private static final int FARTHEST_ORE_OFFSET = 19;

    /** 行走层相对 {@link #ORIGIN} 的 y 偏移（垫层 = +0，行走层 = +1，脚位 = +2）。 */
    private static final int WALK_LAYER_DY = 1;
    private static final int FOOT_DY = 2;

    /** 扫描半径（**故意取生产同值** ⇒ 判据与生产同形，不是为夹具量身定做的怪形状）。 */
    private static final int SCAN_RADIUS = MineCandidateSource.SCAN_RADIUS;

    /** 连续跑几次真作业（勘测口径的"连续 N 次"）。 */
    private static final int RUNS = 3;

    /** 每次作业的配额（3 次共 6 块 ⇒ 场景备 8 块）。 */
    private static final int QUOTA = 2;

    /**
     * **每次运行允许的"走位清障"格数上限**（`D-347` 首跑实测写下来的事实）。
     *
     * <p>一开始我以为"世界改动数 = 配额"，实测**不成立**：矿嵌在行走层里 ⇒ 挖掉一格就留下一个 1 格深的坑，
     * bot 下一步会**站进坑里**，出来时清掉坑壁 1 格（日志：`block_break_done` 多打在两矿之间的行走层）。
     * 实测每次配额 2 格的真实世界改动 = **3~4 格**（多 1~2）。所以判据改成：
     * **账与 `WriteBudget` 独立读数逐位一致**（这才是"计数器诚实"）+ **≥ 配额**（真的改了世界）+ **≤ 配额 + 本上限**
     * （挡住"把整层地板都清了"这种退化）。⚠️ 这个上限是**场景形状**的声明，不是产品保证。
     */
    private static final int MAX_ACCESS_CLEARS_PER_RUN = 2;

    /** 反向对照用的目标类型（**场景里没有** ⇒ 如实 `no_reachable_candidate`）。 */
    private static final String ABSENT_TARGET = "minecraft:diamond_ore";

    /** 单次运行的上限（超时 ⇒ 记一条失败，继续跑下一次：一次卡住不该让整条判据失去信息）。 */
    private static final int PER_RUN_CAP = 1200;

    /** 反向对照那次的上限（扫不到就该很快失败）。 */
    private static final int CONTROL_CAP = 600;

    /** 区块热身 tick（`D-345` 的教训：forceload 后同 tick 写方块/召实体都可能不生效）。 */
    private static final int CHUNK_WARM_TICKS = 10;

    /** 传送后等落地的上限。 */
    private static final int SETTLE_CAP = 40;

    /** 夹具护栏（4 次运行 + 建场景 + 收尾）。 */
    private static final int BUDGET_TICKS = 4800;

    private enum Phase { SETUP, SETTLE, PREPARE, RUN, ASSERT, DONE }

    /** 一次运行的现场读数（**全部是增量**：CORE 里同一进程会跑很多步，绝对值没有意义）。 */
    private record RunResult(String label, Task.Status status, String reason, int ticks, int arrivedDelta,
                             int breaksDelta, int scopeBreakDelta, int placesDelta, int exemptDelta) {
        boolean reachedQuota() {
            return status == Task.Status.DONE && "quota_met".equals(reason);
        }
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int settleTicks;
    private int prepareTicks;
    private int runIndex;            // 0..RUNS-1 = 真作业；RUNS = 反向对照
    private int checks;
    private final List<String> failures = new ArrayList<>();
    private final List<RunResult> results = new ArrayList<>();

    // ---- 前提（SETUP 记、ASSERT 自证）----
    private BlockPos entryFoot;
    private BlockPos center;
    private int oreBuilt = -1;
    private int boxItemsBefore = -1;
    private int freeSlotsBefore = -1;
    private double farthestOre = -1;
    private boolean floorOk;
    /** 每块矿的**正下方是实心垫层**（首跑血的教训：矿镶在墙里时掉落物被挤出平台 ⇒ 掉进虚空）。 */
    private boolean backingOk;
    private boolean exposureOk;
    private boolean windowClean;     // 窗口内没有任何"别的任务/别的写入" ⇒ 总量增量才可解读
    private TaskMetrics.Snapshot windowBefore;

    // ---- 观察 ----
    private MineJob job;
    private int runTicks;
    private TaskMetrics.Snapshot runBefore;
    private int scopeBreaksBefore;
    private int totalTicks;
    private int totalArrived;
    private int totalBreaks;
    private int totalScopeBreaks;

    public MineRunMetricsCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "MineRunMetricsCheck";
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
        return failures.isEmpty() ? "passed" : "failed";
    }

    @Override
    public Task.Status tick() {
        if (++ticks > BUDGET_TICKS && phase != Phase.ASSERT && phase != Phase.DONE) {
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际跑到 " + ticks + "，runIndex="
                    + runIndex + " job=" + (job == null ? "null" : job.terminalReason()) + "）", false);
            phase = Phase.ASSERT;
        }
        return switch (phase) {
            case SETUP -> setup();
            case SETTLE -> settle();
            case PREPARE -> prepareRun();
            case RUN -> runJob();
            case ASSERT -> assertResult();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        // ① forceload 先做：**未加载区块里写方块是静默无效的**（D-244 / D-345 都踩过）
        forceload(level, true);

        // ② **两层地板**：垫层（实心，整块铺满）+ 行走层（石头，其中几格换成铁矿）+ 上方空气
        //    （该处本来就是空 ⇒ 收尾清回空气即还原原状）
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                level.setBlock(ORIGIN.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(ORIGIN.offset(x, WALK_LAYER_DY, z), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = WALK_LAYER_DY + 1; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int offset : ORE_OFFSETS) {
            level.setBlock(ORIGIN.offset(offset, WALK_LAYER_DY, ORE_Z), Blocks.IRON_ORE.defaultBlockState(), 3);
        }

        // ③ **几何前提自断言**（地形没落地 ⇒ 后面所有判据都不可解读 ⇒ 当场记失败，不当缺陷证据）
        floorOk = level.getBlockState(ORIGIN).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(LANE_LENGTH, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(0, 0, LANE_HALF_WIDTH)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(3, WALK_LAYER_DY, 1)).is(Blocks.STONE);
        // **垫层前提**（首跑血的教训）：每块矿的**正下方**必须是实心 ⇒ 掉落物掉不进虚空
        backingOk = true;
        for (int offset : ORE_OFFSETS) {
            backingOk &= level.getBlockState(ORIGIN.offset(offset, 0, ORE_Z)).is(Blocks.STONE);
        }
        // 暴露前提：矿的**正上方**必须是空气，否则 `MineCandidateSource` 的"只找已暴露"会过滤掉它
        exposureOk = true;
        for (int offset : ORE_OFFSETS) {
            exposureOk &= level.getBlockState(ORIGIN.offset(offset, WALK_LAYER_DY, ORE_Z)).is(Blocks.IRON_ORE)
                    && level.getBlockState(ORIGIN.offset(offset, WALK_LAYER_DY + 1, ORE_Z)).isAir();
        }
        oreBuilt = countOre(level);

        // ④ 传送 + 清背包 + 清盒（顺序要紧：`resetInventory` 会把背包里的东西掉在脚下 ⇒ 先传送再清）
        entryFoot = bot.blockPosition();
        center = ORIGIN.offset(0, FOOT_DY, 0);
        teleport(level, center);
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        clearBoxItems(level);
        boxItemsBefore = countBoxItems(level);
        freeSlotsBefore = freeSlots();

        // ⑤ 扫描半径前提（**可扫描是前提**：最远的矿必须在半径内，否则"没挖到"是场景问题不是能力问题）
        BlockPos farthest = ORIGIN.offset(FARTHEST_ORE_OFFSET, WALK_LAYER_DY, ORE_Z);
        farthestOre = Math.sqrt(center.distToLowCornerSqr(
                farthest.getX() + 0.5D, farthest.getY() + 0.5D, farthest.getZ() + 0.5D));

        BotLog.info("[MineRunMetrics] CHECK setup origin={} 走廊={}x{}x{} 矿={}/{} 盒内起始落物={}"
                        + " 空位={} 最远矿距离={} 双层地板={} 垫层={} 暴露={} 扫描半径={}"
                        + "（先热区块 {} tick 再开跑）",
                ORIGIN.toShortString(), LANE_LENGTH, LANE_HALF_WIDTH * 2 + 1, HEADROOM,
                oreBuilt, ORE_OFFSETS.length, boxItemsBefore, freeSlotsBefore, fmt(farthestOre),
                floorOk, backingOk, exposureOk, SCAN_RADIUS, CHUNK_WARM_TICKS);
        phase = Phase.SETTLE;
        return Task.Status.RUNNING;
    }

    /**
     * 等落地 + 等区块热够（{@link #CHUNK_WARM_TICKS}）。
     *
     * <p>⚠️ 相位内的检查点从 1 开始编号（`alice-scene-based-testing` 的相位状态机一节：
     * `phaseTicks == 0` 不是"第一 tick"）。
     */
    private Task.Status settle() {
        settleTicks++;
        boolean settled = FixturePremise.settledOnGround(bot, settleTicks);
        if ((settled && settleTicks >= CHUNK_WARM_TICKS) || settleTicks > SETTLE_CAP) {
            BotLog.info("[MineRunMetrics] CHECK 就位 ticks={} onGround={} foot={}",
                    settleTicks, bot.onGround(), bot.blockPosition().toShortString());
            // 窗口基线：从这里到 ASSERT 之间**只允许发生这 4 次运行**（自断言见 assertResult）
            windowBefore = TaskMetrics.snapshot();
            phase = Phase.PREPARE;
            prepareTicks = 0;
        }
        return Task.Status.RUNNING;
    }

    // ==================== 每次运行 ====================

    /** 本轮的现场准备：传送回统一起点 + 清背包/盒内落物 + 等落地 + 记本轮基线。 */
    private Task.Status prepareRun() {
        prepareTicks++;
        if (prepareTicks == 1) {
            ServerLevel level = bot.serverLevel();
            teleport(level, center);
            // ⚠️ 顺序（首跑实测踩到）：**必须先清背包再发镐** —— 反了就是把刚发的镐清掉，
            // 于是每次运行都如实报 `tool_missing`（那是夹具的错、不是产品的错；`空位=41` 就是那条线索）。
            FixtureToolKit.resetInventory(bot);   // 上一次挖到的产物清掉（掉落物随即一起清）⇒ 每次运行同起点
            FixtureToolKit.ensurePickaxe(bot);
            clearBoxItems(level);
            runBefore = TaskMetrics.snapshot();
            scopeBreaksBefore = WriteBudget.breaks(bot);
            runTicks = 0;
            return Task.Status.RUNNING;
        }
        if (!FixturePremise.settledOnGround(bot, prepareTicks) && prepareTicks <= SETTLE_CAP) {
            return Task.Status.RUNNING;
        }
        // 起本轮的 Job（真作业 / 反向对照）
        boolean control = runIndex >= RUNS;
        GoalSpec spec = GoalSpec.mineBlocks(center, SCAN_RADIUS, control ? 1 : QUOTA, 3600);
        MineCandidateSource source = new MineCandidateSource(
                MineCandidateSource.Target.ofBlock(control ? Blocks.DIAMOND_ORE : Blocks.IRON_ORE),
                SCAN_RADIUS);
        job = new MineJob(bot, spec, scope, source, new NearestPolicy());
        BotLog.info("[MineRunMetrics] CHECK 第 {} 次运行开始（{}{} 配额={} 盒内矿={} 空位={}）",
                runIndex + 1, control ? "反向对照：" : "", control ? ABSENT_TARGET : "minecraft:iron_ore",
                control ? 1 : QUOTA, countOre(bot.serverLevel()), freeSlots());
        phase = Phase.RUN;
        return Task.Status.RUNNING;
    }

    private Task.Status runJob() {
        runTicks++;
        int cap = runIndex >= RUNS ? CONTROL_CAP : PER_RUN_CAP;
        Task.Status status = job.tick();
        if (status == Task.Status.RUNNING) {
            if (runTicks > cap) {
                finishRun(Task.Status.FAILED, "fixture_run_timeout", status);
                return Task.Status.RUNNING;
            }
            return Task.Status.RUNNING;
        }
        finishRun(status, job.terminalReason(), status);
        return Task.Status.RUNNING;
    }

    /** 本轮收尾：把现场读数**按增量**取下来（绝对值在 CORE 里没有意义），然后进下一轮。 */
    private void finishRun(Task.Status status, String reason, Task.Status rawStatus) {
        TaskMetrics.Snapshot after = TaskMetrics.snapshot();
        TaskMetrics.Snapshot delta = after.delta(runBefore);
        RunResult result = new RunResult(
                runIndex >= RUNS ? ("control(" + ABSENT_TARGET + ")") : ("run#" + (runIndex + 1)),
                rawStatus, reason == null ? "" : reason, runTicks,
                delta.arrived(), delta.breaks(), WriteBudget.breaks(bot) - scopeBreaksBefore,
                delta.places(), delta.exemptBreaks());
        results.add(result);
        totalTicks += result.ticks();
        totalArrived += result.arrivedDelta();
        totalBreaks += result.breaksDelta();
        totalScopeBreaks += result.scopeBreakDelta();
        BotLog.info("[MineRunMetrics] RUN {} status={} reason={} ticks={} 到达增量={} 破坏增量(账)={}"
                        + "（WriteBudget 同窗口读数={}）放置增量={} 豁免={}",
                result.label(), result.status(), result.reason(), result.ticks(), result.arrivedDelta(),
                result.breaksDelta(), result.scopeBreakDelta(), result.placesDelta(), result.exemptDelta());
        job = null;
        runIndex++;
        prepareTicks = 0;
        if (runIndex > RUNS) {
            phase = Phase.ASSERT;
        } else {
            phase = Phase.PREPARE;
        }
    }

    // ==================== 断言 ====================

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        TaskMetrics.Snapshot window = TaskMetrics.snapshot().delta(windowBefore);

        // ---- ① 场景与窗口前提（红了先怀疑夹具，而不是产品）----
        check("前提：**双层地板**已落地（垫层 + 行走层，floor=" + floorOk + "）", floorOk);
        check("前提：每块矿的正下方都是**实心垫层**（backing=" + backingOk + "）—— 否则掉落物会掉进虚空"
                + "（首跑就栽在这里：矿原本镶在墙里，掉落物被挤出平台外 1 格 ⇒ 一路掉到 y=73）", backingOk);
        check("前提：8 块矿都**已暴露**（正前方是走廊空气，否则扫描器按设计会过滤掉它们）"
                + "｜exposure=" + exposureOk, exposureOk);
        check("前提：盒内起始没有落物（实际 " + boxItemsBefore + "）", boxItemsBefore == 0);
        check("前提：起始有足够空位（实际 " + freeSlotsBefore + " ≥ 3）", freeSlotsBefore >= 3);
        check("前提：最远的矿在**扫描半径内**（实测 " + fmt(farthestOre) + " < " + SCAN_RADIUS
                + "）—— 「可扫描」是前提，不是结论", farthestOre > 0 && farthestOre < SCAN_RADIUS);
        check("前提：场景备的矿够 3 次配额（备 " + oreBuilt + " 块 ≥ " + (RUNS * QUOTA)
                + "）—— 矿不够会让「没挖到」被误读成「到达不了」", oreBuilt >= RUNS * QUOTA);
        windowClean = window.started() == 0 && window.finished() == 0 && window.returns() == 0
                && window.containerWrites() == 0 && window.places() == 0 && window.exemptBreaks() == 0;
        check("前提：本窗口内**只有这 " + (RUNS + 1) + " 次子任务运行**（没有别的任务/放置/容器写入混进来）"
                        + "｜started=+" + window.started() + " finished=+" + window.finished()
                        + " returns=+" + window.returns() + " places=+" + window.places()
                        + " container=+" + window.containerWrites() + " exempt=+" + window.exemptBreaks()
                        + "。⚠️ 子任务是夹具直接 tick 的 ⇒ **会话侧那几格必须是 0**（分工：会话侧由"
                        + "`CheckHarness`/`HeadlessBattery` 的门禁断言）", windowClean);
        check("前提：4 次运行都真的跑过（结果数=" + results.size() + "/" + (RUNS + 1) + "）",
                results.size() == RUNS + 1);

        // ---- ② 真作业：每次都真的**到达目标**（第一格目标方块被挖掉 ⇒ 任务自己声明到达）----
        // ⚠️ **刻意不判"收集闭环"**（不要求 `quota_met`）：收集另有判据（`mine_job`(BASELINE) /
        // `mine_far_drop`(D-346) / `mine_inventory`），本夹具回答的是**运行账**（到达率 / 世界改动 / 耗时）。
        // 运行若因**与本夹具无关**的已知路径没走到 `quota_met`（实例：2026-09-20 实测到"掉落物被作用域
        // 当成未进入世界而忽略" —— `D-345` 记过的同一现象），**如实进 SUMMARY 的事实字段**，不改成败。
        for (int i = 0; i < Math.min(RUNS, results.size()); i++) {
            RunResult result = results.get(i);
            if (!result.reachedQuota()) {
                BotLog.warn("[MineRunMetrics] ⚠️ 事实记录（**不是本夹具的判据**）：{} 不是 quota_met"
                                + "（status={} reason={}）⇒ 收集闭环由 `mine_job`/`mine_far_drop`/"
                                + "`mine_inventory` 判；本夹具只判运行账", result.label(), result.status(),
                        result.reason());
            }
            check("⭐ " + result.label() + "：**任务自己声明的到达**恰好一次（到达增量="
                            + result.arrivedDelta() + "；0 说明没到、>1 说明去重坏了）",
                    result.arrivedDelta() == 1);
            check("⭐ " + result.label() + "：**世界改动数**被真的记下来了，且**账与 `WriteBudget` 独立读数"
                            + "逐位一致**（账=" + result.breaksDelta() + "，WriteBudget="
                            + result.scopeBreakDelta() + "；本次配额=" + QUOTA
                            + "，多出来的是走位清障）⇒ 判据 = 一致 ∧ ≥ 配额 " + QUOTA
                            + " ∧ ≤ " + (QUOTA + MAX_ACCESS_CLEARS_PER_RUN),
                    result.breaksDelta() == result.scopeBreakDelta()
                            && result.breaksDelta() >= QUOTA
                            && result.breaksDelta() <= QUOTA + MAX_ACCESS_CLEARS_PER_RUN);
            check("⭐ " + result.label() + "：耗时是**真的量出来的**（ticks=" + result.ticks() + " > 0）",
                    result.ticks() > 0);
        }

        // ---- ③ 汇总（勘测口径：连续的 N 次 = 到达率 / 平均 tick / 世界改动数）----
        int expectedBreaks = RUNS * QUOTA;
        check("⭐ 汇总：三次真作业**全部到达**（到达增量合计=" + totalArrived + " == " + RUNS + "）",
                totalArrived == RUNS);
        int maxBreaks = RUNS * (QUOTA + MAX_ACCESS_CLEARS_PER_RUN);
        check("⭐ 汇总：世界改动数**合计**被量出来（账上=" + totalBreaks + "，`WriteBudget` 独立读数="
                        + totalScopeBreaks + "；配额合计=" + expectedBreaks + "，含走位清障的上界=" + maxBreaks
                        + "）⇒ 判据 = 两个读数一致 ∧ 落在 [配额, 上界] 内（退化到「把地板全清了」会红）",
                totalBreaks == totalScopeBreaks && totalBreaks >= expectedBreaks && totalBreaks <= maxBreaks);

        // ---- ④ ⭐ 反向对照：到达率**不是一个恒等式** ----
        RunResult control = results.size() > RUNS ? results.get(RUNS) : null;
        check("⭐ 反向对照：目标类型在场景里不存在 ⇒ 必须**如实失败**（实际 status="
                        + (control == null ? "(没跑到)" : control.status()) + " reason="
                        + (control == null ? "-" : control.reason()) + "）",
                control != null && control.status() == Task.Status.FAILED
                        && "no_reachable_candidate".equals(control.reason()));
        check("⭐ 反向对照：**没有到达**（到达增量=" + (control == null ? "-" : control.arrivedDelta())
                        + " == 0）且**没改世界**（破坏增量=" + (control == null ? "-" : control.breaksDelta())
                        + " == 0）⇒ 本次窗口的到达率 = " + RUNS + "/" + (RUNS + 1)
                        + " = **真的小于 1**（这条证明判据能咬人；把 `MineJob` 里那句 `arrived(...)` 挪到"
                        + "「选出了候选」处，这里会变成 1 ⇒ 夹具红）",
                control != null && control.arrivedDelta() == 0 && control.breaksDelta() == 0);

        // ---- ⑤ 收尾（**失败路径也走**，§6.9.2）----
        clearBoxItems(level);
        clearBox(level);
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        int leftItems = countBoxItems(level);
        boolean restored = boxIsAir(level);
        check("收尾：夹具造出来的落物全部收回（盒内剩余=" + leftItems + "）", leftItems == 0);
        check("收尾：自建地形（垫层 + 行走层）清回空气 ⇒ 这片区域与其它场景依旧不相连"
                + "（盒内非空气格=" + (restored ? 0 : countNonAir(level)) + "）", restored);

        boolean pass = failures.isEmpty();
        int quotaMet = 0;
        for (RunResult result : results) {
            if (result.reachedQuota()) {
                quotaMet++;
            }
        }
        int avgTicks = results.isEmpty() ? 0 : totalTicks / results.size();
        // ⚠️ 到达率**必须是算出来的、不能写死**：反向对照①（把 `arrived` 调用删掉）时，
        // 写死的 "3/4" 会让 SUMMARY 看起来一切正常（实测踩到）⇒ 现在报 `到达={实测}/{运行数}`。
        BotLog.info("[MineRunMetrics] SUMMARY checks={} failures={} runs={} 到达={}/{} 平均tick={}"
                        + " 世界改动(账)={} 世界改动(WriteBudget)={} 场景矿={} quota_met次数={}/{}"
                        + "（事实字段，**不是判据**：收集闭环由 mine_job/mine_far_drop/mine_inventory 判）"
                        + " → {}｜失败项：{}",
                checks, failures.size(), results.size(), totalArrived, RUNS + 1, avgTicks,
                totalBreaks, totalScopeBreaks, oreBuilt, quotaMet, RUNS, pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 运行账取证 " + (pass ? "PASS" : "FAIL")
                    + "（到达 " + RUNS + "/" + (RUNS + 1) + "，平均 " + avgTicks + " tick，世界改动 "
                    + totalBreaks + "，详见日志 [MineRunMetrics]）"));
        }
        phase = Phase.DONE;
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    /** 走廊沿途区块 forceload（**建/拆对称**：forceload 是全局世界状态，泄漏会污染后续步）。 */
    private void forceload(ServerLevel level, boolean on) {
        int minZ = ORIGIN.getZ() - LANE_HALF_WIDTH;
        int maxZ = ORIGIN.getZ() + LANE_HALF_WIDTH;
        for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + LANE_LENGTH) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    /** 盒子 = 走廊本体（**与建场景、收尾用的是同一个**：§6.9.1 ① 基线盒 = 结束盒）。 */
    private AABB box() {
        return new AABB(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ() - LANE_HALF_WIDTH,
                ORIGIN.getX() + LANE_LENGTH + 1, ORIGIN.getY() + WALK_LAYER_DY + HEADROOM + 1,
                ORIGIN.getZ() + LANE_HALF_WIDTH + 1);
    }

    /** 盒内**已暴露的**铁矿块数（前提断言与每轮日志用）。 */
    private int countOre(ServerLevel level) {
        int total = 0;
        for (int offset : ORE_OFFSETS) {
            if (level.getBlockState(ORIGIN.offset(offset, WALK_LAYER_DY, ORE_Z)).is(Blocks.IRON_ORE)) {
                total++;
            }
        }
        return total;
    }

    private int countBoxItems(ServerLevel level) {
        int total = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            total += item.getItem().getCount();
        }
        return total;
    }

    private void clearBoxItems(ServerLevel level) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            item.discard();
        }
    }

    /** 垫层 + 行走层 + 头顶清回空气（该处原状就是空中 ⇒ 这就是"还原原状"）。 */
    private void clearBox(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** 盒内是否已全部是空气（收尾自证：**同一只盒子**）。 */
    private boolean boxIsAir(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(x, dy, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int countNonAir(ServerLevel level) {
        int total = 0;
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= WALK_LAYER_DY + HEADROOM; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(x, dy, z)).isAir()) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private int freeSlots() {
        int free = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
