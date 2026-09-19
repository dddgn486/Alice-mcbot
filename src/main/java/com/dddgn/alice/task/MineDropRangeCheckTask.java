package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ⭐ **挖矿产物的「收集距离窗口」取证夹具**（电池步 `mine_far_drop`；`survey/22 §1.5①` 的落地判据）。
 *
 * <h2>它取证的是什么（一句话）</h2>
 * <b>我方登记在册、路上无遮拦、完全可达的产物，只要离 bot 超过 32 格，就会被收集器<b>永久</b>丢弃。</b>
 *
 * <h2>代码链（三条一起才构成"真缺陷"，别只看其中一条）</h2>
 * <ol>
 *   <li><b>距离硬墙 + 永久退休</b>：{@code CollectDropsTask.refreshCandidates()}（`:504-505`）在**每 tick**
 *       刷新候选时，凡"离 bot 距离 &gt; {@code MAX_CHASE_DISTANCE = 32.0}`（`:90`）"的落物立刻
 *       {@code retire(id, "too_far")}；而 {@code retired} 集合**从不清空**（`:492`/`:521`/`:649`）
 *       ⇒ 本次收集内**永久**不再考虑；</li>
 *   <li><b>收集只在配额之后开始</b>：{@code MineJob`（`:309-311`）"挖满 quota 才起收集"⇒ 收集的**第一 tick**，
 *       bot 站在**最后挖的那一格** ⇒ 更早挖出的产物已经落在 32 格外 ⇒ 第一 tick 就被退休（**连走都不走**）；</li>
 *   <li><b>判据把"丢弃"变成"失败"</b>：{@code MineJob.collectPhase()}`（`:331-337`）算
 *       {@code gained = countTargetItems() - itemsBefore}，要求 {@code gained >= minedCount}
 *       ⇒ 被退休的产物让整个 Job 如实报 {@code FAILED product_not_collected}。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么这不是"只有通道挖掘才咬到"</b>：{@code MAX_CHASE_DISTANCE(32) &lt;
 * 2 × MineCandidateSource.SCAN_RADIUS(24) = 48} —— <b>单次</b> {@code MineJob} 的扫描窗口
 * （半径 24 的球）里就可以放下两颗相隔 &gt;32 格的产物 ⇒ 今天的挖矿配额作业就能咬到，
 * 不需要等通道能力（`D-329` 路线图 2.5）落地。这两个常量的关系就是本缺陷的不变量：
 * <b>"配额后集中收集"要成立，收集半径必须 ≥ 扫描直径</b>（今天 32 &lt; 48 ⇒ 不成立）。
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * <b>动作层</b>——真世界里一个真的 {@code CollectDropsTask}，被本夹具**每 tick 驱动一 tick**
 * （与 `region_sweep_e2e` 驱动 `RegionLumberJob` 同一个手法）。它<b>不</b>经由 {@code MineJob}：
 * 本夹具要的是"**可复现、零物理抖动**"的证据（挖矿的走位/掉落时机有随机性 ⇒ 那属于
 * "链路级证据"，见下面「修好之后」。）
 *
 * <h2>几何前提（§6.9.1 ①：盒子以谁为中心、多大，全部写下来并自断言）</h2>
 * <ul>
 *   <li>走廊：原点 {@link #ORIGIN}（x/z 在 3000 一带、其它夹具不用 z=2000），x 方向长
 *       {@link #LANE_LENGTH}=48、z 方向 ±{@link #LANE_HALF_WIDTH}、上方留 {@link #HEADROOM} 格空气
 *       ⇒ 平台顶面 y = 100、脚位 y = 101；周围是空 ⇒ 与其它场景**不相连**（技能 §2 孤立长方体）；</li>
 *   <li>bot 起点 = 走廊起点（x 偏移 0）；<b>近件</b> = 偏移 {@link #NEAR_OFFSET}=8（<b>故意放在 8 格外</b>：
 *       收集器必须**真的走过去**才捡得到 ⇒ 它是"装置可用"的对照，而不是"脚下白捡"）；
 *       <b>远件</b> = 偏移 {@link #FAR_OFFSET}=40（&gt; 32 格窗口）；</li>
 *   <li>作用域 = {@code scope.begin(center, 24)}，center = 偏移 {@link #SCOPE_CENTER_OFFSET}=24
 *       ⇒ 两件落物都在窗口内（±16）—— <b>与 {@code MineJob} 的 `spec.center()/spec.radius()`
 *       （半径 = `SCAN_RADIUS` = 24）同形</b>，不是为夹具量身定做的怪形状。</li>
 * </ul>
 *
 * <h2>怎么保证"只有距离这一个变量"（去掉歧义路径）</h2>
 * <ol>
 *   <li>两件落物都用 {@code scope.registerAsOurs(..., OURS_DIRECT, ...)} 登记成我方产物（与生产路径
 *       "破坏事件配对 ⇒ OURS_DIRECT"同一个状态；**不是** `adoptExistingDrops` —— 那个对"已被捕捉、
 *       只是没配对来源"的落物会跳过，实测收养数恒为 0）⇒ {@code DropPolicy} 一律放行
 *       ⇒ <b>不可能</b>以"策略拒绝"（`policy_blocked`）为由不捡；自断言 `adopted == 2`；</li>
 *   <li>近/远两件**都在同一个作用域、同一层平台、同一条直线**上，唯一差别 = 与 bot 的距离；</li>
 *   <li>近件必须先被捡到（`collected ≥ 1`，前提判据）—— 否则"远件没被捡"无法解读
 *       （可能压根是装置坏了）。</li>
 * </ol>
 *
 * <h2>两处"夹具环境学"（实测踩到，写下来给后来人）</h2>
 * <ol>
 *   <li><b>forceload 之后不能同 tick summon</b>：生成事件已发、物品与坐标都对，但实体在 tick 末
 *       仍查不到（`ScopeBuffer.flushPending` 的 `inWorld()` 判否）⇒ 先热 {@link #CHUNK_WARM_TICKS} tick
 *       再 summon，并**等它真的可见**（本夹具的前提判据当场抓出了这颗 bug ✓ —— 这就是"前提自证"的价值）；</li>
 *   <li><b>候选来源走 `D-344` 的既有钩子</b>（`liveDropsSource`），不走 `scope.liveDrops()`：
 *       这片**远处 forceload 区块**里生成的落物，在生成当 tick 被 `flushPending` 当成"幻影"丢弃
 *       （`作用域忽略未进入世界的掉落物 … x3208`），于是不进登记表 ⇒ `liveDrops()` 为空。
 *       ⚠️ <b>触发条件是夹具人为的</b>（生产里落物都生成在 bot 身边、区块本来就在 tick）
 *       ⇒ **不当作产品缺陷**，只作为"夹具为什么要自己递候选"的理由。换来源**不放松授权**：
 *       能不能捡仍由 `DropPolicy.mayCollect` 把关（归属已设成 `OURS_DIRECT`）。</li>
 * </ol>
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 不调 {@code assign*}/{@code beginTask}（那会 `clearTask()` 掉正在跑的电池步）⇒ 本夹具**自己持有并 tick**
 * 一个 {@code CollectDropsTask}；自己造的平台**收尾清回空气 + 撤销 forceload**，
 * 造出来的落物**全部 discard**（含失败路径），bot 回传送前的位置。
 *
 * <h2>判据（红 = 缺陷在场）</h2>
 * <table border="1">
 *   <tr><th>判据</th><th>期望</th><th>今天</th></tr>
 *   <tr><td>前提：平台建成 / 盒内起始无落物 / 两件被登记为 OURS / 近件 ≤32 / 远件 &gt;32</td>
 *       <td>全真</td><td>✅ 真（前提，红了说明夹具坏）</td></tr>
 *   <tr><td>前提：近件真的进了背包（装置可用）</td><td>真</td><td>✅ 真</td></tr>
 *   <tr><td>⭐ 期望：**两件**都进背包（`collected == 2`）</td><td>真</td><td>❌ <b>红</b>（只有 1）</td></tr>
 *   <tr><td>⭐ 期望：收集结束后**地上不许剩我方产物**（世界事实）</td><td>真</td><td>❌ <b>红</b>（还剩 1）</td></tr>
 * </table>
 *
 * <h2>修好之后（本夹具的翻面条件）</h2>
 * 修法有两族：① <b>抬高/可重扫</b>（32 格硬墙改成相对判据，或退休不是永久的）；
 * ② <b>不让缺口出现</b>（挖一段捡一段 ⇒ 上面的 `MineJob:309-311` 那条前提不再成立）。
 * 走 ② 时本夹具要**改成链路级**（真跑 `MineJob`，断言"地上不留产物"），而不是简单删掉它 ——
 * 它今天钉住的是"**静默丢弃**"这件事：无论哪一族修法，产物都不许无声无息地留在原地还报成功。
 */
public final class MineDropRangeCheckTask implements Task {

    /** 走廊原点（**远离所有场景**：y=100 的空中，其它夹具不在这片 z 上）。 */
    private static final BlockPos ORIGIN = new BlockPos(3200, 100, 2000);

    /** 走廊长度（x 方向）：要放得下 40 格外的远件 + 余量。 */
    private static final int LANE_LENGTH = 48;

    /** 走廊半宽（z 方向 ±1 ⇒ 3 格宽，够绕行）。 */
    private static final int LANE_HALF_WIDTH = 1;

    /** 平台上方留的空气层数（够通行，避免"贴天花板"影响站姿）。 */
    private static final int HEADROOM = 4;

    /** 近件偏移（**刻意 &gt; 1 格**：收集器必须真的走过去 ⇒ 它是"装置可用"的对照）。 */
    private static final int NEAR_OFFSET = 8;

    /** 远件偏移（必须 &gt; {@link #FAR_WINDOW}）。 */
    private static final int FAR_OFFSET = 40;

    /** 作用域中心偏移（= 近/远两件的中点，两件各离中心 16）。 */
    private static final int SCOPE_CENTER_OFFSET = 24;

    /** 作用域半径（与 `MineCandidateSource.SCAN_RADIUS` 同值 —— 与生产同形）。 */
    private static final int SCOPE_RADIUS = 24;

    /**
     * 收集器的距离窗口：**必须与 {@code CollectDropsTask.MAX_CHASE_DISTANCE}（`:90` = 32.0）一致**。
     * 夹具自己留一份常量（那个字段是私有的），并在前提里**实测**两件的距离 ⇒ 两边漂了就当场红。
     */
    private static final double FAR_WINDOW = 32.0D;

    /** 产物物品（挖矿产物的代表；只要能被 `adoptExistingDrops` 登记即可，与具体物品无关）。 */
    private static final String PRODUCT = "minecraft:raw_iron";

    /** 收集器自带的总预算（`CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS`，那个常量是私有的 ⇒ 照抄值）。 */
    private static final int COLLECTOR_BUDGET_TICKS = 600;

    /** 本步预算（收集器自带 600 tick 预算 ⇒ 夹具护栏要高一点）。 */
    private static final int BUDGET_TICKS = 900;

    /** 传送后等落地的上限（`FixturePremise.SETTLE_TICKS` 之上再留余量）。 */
    private static final int SETTLE_CAP = 40;

    /**
     * **区块热身**：forceload / 建地形之后要等**若干 tick** 再往这片区块里 summon。
     *
     * <p>⚠️ 实测（2026-09-20 首跑）：同 tick forceload + setBlock + summon ⇒ **掉落物根本没进世界**
     * —— 证据是 `[CollectDrops]` 那句"作用域忽略未进入世界的掉落物(生成被取消/缓冲): raw_iron x3208"
     * （`ScopeBuffer.flushPending` 的 `inWorld()` 判据 = `level.getEntity(id) != null`）。
     * 生成事件（`EntityJoinLevelEvent`）已经发出、物品与坐标都对，但实体在 tick 末仍查不到
     * ⇒ 该区块此时**还没到"实体 tick"阶段**（`setChunkForced` 只保证方块层加载）。
     * ⇒ 先热够 tick 再 summon，并**等它真的可见**才继续（本夹具的前提判据当场把这颗 bug 抓了出来 ✓）。
     */
    private static final int CHUNK_WARM_TICKS = 10;

    /** 等"召唤出来的落物真的可见"的上限（超时 ⇒ 记硬前提失败，**不当缺陷证据**）。 */
    private static final int SUMMON_CAP = 60;

    private enum Phase { SETUP, SETTLE, SUMMON, RUN, ASSERT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int settleTicks;
    private int checks;
    private final List<String> failures = new ArrayList<>();
    /** 硬前提失败（地形/召唤）⇒ 直接进断言，跑下去没有意义。 */
    private String setupFailure = "";

    // ---- 前提（SETUP 记录、ASSERT 自证）----
    private BlockPos entryFoot;
    private BlockPos center;
    private int builtBlocks;
    private int boxItemsBefore = -1;
    private int inventoryBefore = -1;
    private int adopted = -1;
    private UUID nearId;
    private UUID farId;
    private double nearDistance = -1;
    private double farDistance = -1;
    private boolean bothRegistered;
    private boolean settled;
    private boolean summoned;
    private int summonTicks;
    /** 交给收集器的候选（**夹具持有对象引用** ⇒ 不受 `ScopeBuffer` 登记表是否收录影响）。 */
    private List<ItemEntity> candidates = List.of();

    // ---- 观察（RUN 累积）----
    private CollectDropsTask collector;
    private Task.Status collectorStatus = Task.Status.RUNNING;
    private int runTicks;
    private int collected;
    private int groundLeft;
    private int inventoryAfter;
    private String collectorReason = "";

    public MineDropRangeCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "MineDropRangeCheck";
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
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际跑到 "
                    + ticks + "，收集器 status=" + collectorStatus + "）", false);
            phase = Phase.ASSERT;
        }
        return switch (phase) {
            case SETUP -> setup();
            case SETTLE -> settle();
            case SUMMON -> summon();
            case RUN -> run();
            case ASSERT -> assertResult();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        // ① forceload 先做：**未加载区块里写方块是静默无效的**（本项目踩过两次：D-244 / FarBench 注释）
        ServerLevel level = bot.serverLevel();
        forceload(level, true);

        // ② 造走廊：地板 + 上方空气（该处本就是空中 ⇒ 收尾清回空气即"还原原状"）
        int placed = 0;
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                level.setBlock(ORIGIN.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                placed++;
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        builtBlocks = placed;

        // ③ 几何前提自断言（写进判据：地形没落地就不许继续把"收集失败"当缺陷证据）
        boolean floorOk = level.getBlockState(ORIGIN.offset(NEAR_OFFSET, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN.offset(FAR_OFFSET, 0, 0)).is(Blocks.STONE)
                && level.getBlockState(ORIGIN).is(Blocks.STONE);
        if (!floorOk) {
            setupFailure = "TERRAIN_NOT_BUILT(空中平台没落地，写方块=" + placed + ")";
        }

        // ④ 传送（玩家 ticket 会同步加载区块）+ 记下入口脚位（收尾复位用）
        entryFoot = bot.blockPosition();
        teleport(level, ORIGIN.above());

        // ⑤ 清场：**先清背包**（`resetInventory` 会把背包里的东西掉在脚下 ⇒ 顺序不能反），
        //    再清盒子里的落物 ⇒ "盒内落物数"才是夹具可控的量（§6.9.1 ①）
        FixtureToolKit.resetInventory(bot);
        clearBoxItems(level);
        boxItemsBefore = countBoxItems(level);
        inventoryBefore = countProductInInventory();

        // ⑥ 作用域先开（`begin` 会清空登记表 ⇒ 必须在 summon/adopt 之前）
        center = ORIGIN.offset(SCOPE_CENTER_OFFSET, 0, 0);
        scope.begin(center, SCOPE_RADIUS, bot.getUUID());

        BotLog.info("[MineDropRange] CHECK setup origin={} 走廊={}x{} 地板方块={} 盒内起始落物={}"
                        + " 近件@+{} 远件@+{} scope(center={} r={}) setupFailure={}"
                        + "（先热区块 {} tick 再 summon）",
                ORIGIN.toShortString(), LANE_LENGTH, LANE_HALF_WIDTH * 2 + 1, placed, boxItemsBefore,
                NEAR_OFFSET, FAR_OFFSET, center.toShortString(), SCOPE_RADIUS,
                setupFailure.isEmpty() ? "-" : setupFailure, CHUNK_WARM_TICKS);
        phase = Phase.SETTLE;
        return Task.Status.RUNNING;
    }

    /**
     * 等落地（`teleportTo` 那一 tick 读到的 `onGround` 是**上一处**的状态 ⇒ 必须等物理结算）
     * **并且**等区块热到能收实体（{@link #CHUNK_WARM_TICKS}；否则 summon 的落物根本进不了世界）。
     */
    private Task.Status settle() {
        settleTicks++;
        settled = FixturePremise.settledOnGround(bot, settleTicks);
        if ((settled && settleTicks >= CHUNK_WARM_TICKS) || settleTicks > SETTLE_CAP) {
            BotLog.info("[MineDropRange] CHECK 区块热身结束 ticks={} onGround={} foot={}",
                    settleTicks, bot.onGround(), bot.blockPosition().toShortString());
            phase = Phase.SUMMON;
        }
        return Task.Status.RUNNING;
    }

    // ==================== SUMMON ====================

    /**
     * 造两件落物（近/远各一），**并等它们真的可见**才继续。
     *
     * <p>为什么要等：见 {@link #CHUNK_WARM_TICKS} —— "生成事件已发但实体查不到"是实测过的真事；
     * 等不到就记**硬前提失败**（本步红是"夹具坏"，不是缺陷证据 ✗）。
     */
    private Task.Status summon() {
        ServerLevel level = bot.serverLevel();
        summonTicks++;
        if (!summoned) {
            boolean nearSpawned = summonProduct(level, ORIGIN.offset(NEAR_OFFSET, 0, 0));
            boolean farSpawned = summonProduct(level, ORIGIN.offset(FAR_OFFSET, 0, 0));
            if (!nearSpawned || !farSpawned) {
                setupFailure = "SUMMON_FAILED(near=" + nearSpawned + " far=" + farSpawned + ")";
            }
            summoned = true;
            return Task.Status.RUNNING;
        }
        List<ItemEntity> visible = visibleProducts(level);
        if (visible.size() < 2 && summonTicks <= SUMMON_CAP) {
            return Task.Status.RUNNING;      // 还没进世界/还不可见 ⇒ 再等（上限 SUMMON_CAP）
        }
        if (visible.size() < 2) {
            setupFailure = "SUMMON_NOT_VISIBLE(可见=" + visible.size() + "，等了 " + summonTicks + " tick)";
        }

        // ⑦ 登记成"我方产物"（`OURS_DIRECT`）+ 记 UUID（按离 bot 的远近分）+ 实测距离
        //
        // ⚠️ **不能用 `adoptExistingDrops`**（2026-09-20 第二跑实测）：落物进世界时已被
        // `ScopeBuffer` 捕捉进登记表（日志 `作用域捕捉掉落物: … provenance=FOREIGN(未登记) source=unpaired`
        // —— 没有配对到破坏事件 ⇒ `itemOrigins` 为空 ⇒ **不进 `liveDrops()`**），而
        // `adoptExistingDrops` 对"已在登记表里"的条目**直接跳过**（它只收养表外的）⇒ 收养数恒为 0 ✗。
        // 正解 = `registerAsOurs`（它的 javadoc 明写"夹具用"）：补上"来源格 + 归属" ⇒ 立刻进 `liveDrops()`，
        // 与生产路径"破坏事件配对 ⇒ OURS_DIRECT"给的是**同一个状态**。
        adopted = 0;
        for (ItemEntity item : visible) {
            if (scope.registerAsOurs(item, com.dddgn.alice.decision.DropPolicy.Provenance.OURS_DIRECT,
                    item.blockPosition())) {
                adopted++;
            }
        }
        visible.sort(Comparator.comparingDouble(item -> item.distanceToSqr(bot)));
        if (visible.size() >= 2) {
            nearId = visible.get(0).getUUID();
            farId = visible.get(visible.size() - 1).getUUID();
        }
        candidates = List.copyOf(visible);      // ⚠️ 必须在 `provenanceIsOurs` **之前**（它按 `candidates` 找）
        bothRegistered = provenanceIsOurs(nearId) && provenanceIsOurs(farId);
        nearDistance = distanceTo(ORIGIN.getX() + NEAR_OFFSET + 0.5D, ORIGIN.getY() + 1.2D,
                ORIGIN.getZ() + 0.5D);
        farDistance = distanceTo(ORIGIN.getX() + FAR_OFFSET + 0.5D, ORIGIN.getY() + 1.2D,
                ORIGIN.getZ() + 0.5D);

        BotLog.info("[MineDropRange] CHECK 落物就位 可见={} 等={} tick adopted={} 登记={}"
                        + " 近件距离={} 远件距离={}（窗口={}）setupFailure={}",
                visible.size(), summonTicks - 1, adopted, bothRegistered, fmt(nearDistance),
                fmt(farDistance), FAR_WINDOW, setupFailure.isEmpty() ? "-" : setupFailure);

        if (!setupFailure.isEmpty()) {
            phase = Phase.ASSERT;      // 硬前提失败 ⇒ 跑下去没有意义（ASSERT 里如实判红）
            return Task.Status.RUNNING;
        }
        // ⚠️ **候选来源用 `D-344` 的既有钩子**（默认 `null` = `scope.liveDrops()`）：本夹具在这片
        // forceload 区块里实测到"落物进了世界、`getEntitiesOfClass` 查得到，但 `ScopeBuffer` 的
        // `inWorld()`（= `level.getEntity(id) != null`）判否 ⇒ 它**没进登记表** ⇒ `liveDrops()` 为空"
        // （日志：`作用域忽略未进入世界的掉落物 … raw_iron x3208`）。换来源**不放松授权**：
        // 能不能捡仍由 `DropPolicy.mayCollect` 把关，而归属已由上面的 `registerAsOurs` 设成
        // `OURS_DIRECT`（与生产"破坏事件配对"同一状态）⇒ 与生产路径的**唯一**差别只是候选从哪来。
        collector = new CollectDropsTask(bot, center, scope, List.of(), false, COLLECTOR_BUDGET_TICKS,
                com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY, () -> candidates);
        BotLog.info("[MineDropRange] CHECK 起收集 bot={} 候选={}（收集器第一 tick 就会按上面的距离决定退不退休）",
                bot.blockPosition().toShortString(), candidates.size());
        phase = Phase.RUN;
        return Task.Status.RUNNING;
    }

    // ==================== RUN ====================

    private Task.Status run() {
        runTicks++;
        collectorStatus = collector.tick();
        if (collectorStatus != Task.Status.RUNNING) {
            collected = collector.collected();
            collectorReason = collector.terminalReason();
            BotLog.info("[MineDropRange] CHECK 收集结束 ticks={} status={} collected={} terminalReason={}",
                    runTicks, collectorStatus, collected, collectorReason.isEmpty() ? "-" : collectorReason);
            phase = Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    // ==================== ASSERT ====================

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        // 观察（**dispose 之前**读世界事实）
        groundLeft = countProductOnGround(level);
        inventoryAfter = countProductInInventory();
        boolean farStillOnGround = farId != null && entityAlive(level, farId);
        boolean nearStillOnGround = nearId != null && entityAlive(level, nearId);

        // ---- 前提（红了说明夹具坏，不是缺陷证据）----
        if (setupFailure.isEmpty()) {
            check("前提：几何 —— 空中平台真的落地了（地板方块=" + builtBlocks + "）", true);
        } else {
            check("硬前提失败：" + setupFailure + "（地板方块=" + builtBlocks
                    + "）⇒ 本步的其余判据都不成立", false);
        }
        check("前提：盒内起始**没有**落物（实际 " + boxItemsBefore + "）—— 否则「盒里几件」不可控",
                boxItemsBefore == 0);
        check("前提：背包里起始**没有**产物（实际 " + inventoryBefore
                + "）—— 否则收集器的背包增量口径（`collected`）被污染", inventoryBefore == 0);
        check("前提：两件落物都被登记成我方（`registerAsOurs` 成功=" + adopted + "，归属可解析="
                + bothRegistered + "）—— 否则可能是被策略拦住而不是被距离退休", adopted == 2 && bothRegistered);
        check("前提：近件在窗口内（实测 " + fmt(nearDistance) + " ≤ " + FAR_WINDOW + "）",
                nearDistance > 0 && nearDistance <= FAR_WINDOW);
        check("前提：远件在窗口外（实测 " + fmt(farDistance) + " > " + FAR_WINDOW + "）—— 这是本夹具的唯一自变量",
                farDistance > FAR_WINDOW);
        check("前提：近件**真的被捡起来了**（`collected=" + collected + " ≥ 1`）—— 否则"
                + "「远件没被捡」无法解读（可能压根是装置坏了）", collected >= 1);

        // ---- ⭐ 缺陷判据（今天必然红）----
        check("⭐ 期望：**两件产物都进背包**（`collected == 2`，实际 " + collected
                + "；被丢在地上的 = " + groundLeft + "）", collected == 2);
        check("⭐ 期望：收集结束后**地上不许剩我方产物**（世界事实：地上 " + groundLeft
                + " 件；远件还在=" + farStillOnGround + "、近件还在=" + nearStillOnGround + "）"
                + "—— 收集器是 best-effort，但「够得着却不捡」必须是失败，不许静默丢弃",
                groundLeft == 0);

        // 收尾（**失败路径也走**，§6.9.2）
        clearBoxItems(level);
        clearBox(level);
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        check("收尾：夹具造出来的落物全部收回（盒内剩余=" + countBoxItems(level) + "）", countBoxItems(level) == 0);

        boolean pass = failures.isEmpty();
        BotLog.info("[MineDropRange] SUMMARY checks={} failures={} 近件距离={} 远件距离={} 窗口={}"
                        + " adopted={} collected={} 背包产物={} 地上剩余={} 远件留在地上={} 收集器终态={}"
                        + " collectorReason={} 链路算术=minedCount(2) gained({}) ⇒ MineJob 判 {}"
                        + " → {}｜失败项：{}",
                checks, failures.size(), fmt(nearDistance), fmt(farDistance), FAR_WINDOW, adopted,
                collected, inventoryAfter, groundLeft, farStillOnGround, collectorStatus,
                collectorReason.isEmpty() ? "-" : collectorReason, collected,
                collected >= 2 ? "quota_met" : "product_not_collected（MineJob.java:331-337）",
                pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 挖矿落物距离窗口取证 " + (pass ? "PASS" : "FAIL")
                    + "（收集=" + collected + "/2，地上剩=" + groundLeft + "，详见日志 [MineDropRange]）"));
        }
        phase = Phase.DONE;
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    /**
     * 走廊沿途区块 forceload（**建/拆对称**：收尾必须撤销 —— forceload 是全局世界状态，泄漏会污染后续步）。
     */
    private void forceload(ServerLevel level, boolean on) {
        int minZ = ORIGIN.getZ() - LANE_HALF_WIDTH;
        int maxZ = ORIGIN.getZ() + LANE_HALF_WIDTH;
        for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + LANE_LENGTH) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    /** 盒子 = 走廊本体（**与判据用的盒子同一个**：§6.9.1 ① 基线盒 = 结束盒）。 */
    private AABB box() {
        return new AABB(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ() - LANE_HALF_WIDTH,
                ORIGIN.getX() + LANE_LENGTH + 1, ORIGIN.getY() + HEADROOM + 1,
                ORIGIN.getZ() + LANE_HALF_WIDTH + 1);
    }

    /** 盒内落物件数（**件数之和**：原版会把同格多件并成一叠）。 */
    private int countBoxItems(ServerLevel level) {
        int total = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            total += item.getItem().getCount();
        }
        return total;
    }

    /** 盒内**本夹具的产物**件数（判"地上还剩几件产物"用）。 */
    private int countProductOnGround(ServerLevel level) {
        int total = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            if (item.getItem().is(Items.RAW_IRON)) {
                total += item.getItem().getCount();
            }
        }
        return total;
    }

    private void clearBoxItems(ServerLevel level) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            item.discard();
        }
    }

    /** 地板 + 头顶清回空气（该处原状就是空中 ⇒ 这就是"还原原状"）。 */
    private void clearBox(ServerLevel level) {
        for (int x = 0; x <= LANE_LENGTH; x++) {
            for (int z = -LANE_HALF_WIDTH; z <= LANE_HALF_WIDTH; z++) {
                for (int dy = 0; dy <= HEADROOM; dy++) {
                    level.setBlock(ORIGIN.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private boolean summonProduct(ServerLevel level, BlockPos pos) {
        int commands = level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(),
                "summon minecraft:item " + (pos.getX() + 0.5D) + " " + (pos.getY() + 1.2D) + " "
                        + (pos.getZ() + 0.5D) + " {Item:{id:\"" + PRODUCT + "\",Count:1b},PickupDelay:0s}");
        // 陷阱 #5：`/summon` 在抑制输出的命令源下静默失败 ⇒ **必须看返回值**
        return commands > 0;
    }

    /** **盒内可见的产物落物**（"可见" = 真的进了世界的实体表 —— 见 {@link #CHUNK_WARM_TICKS}）。 */
    private List<ItemEntity> visibleProducts(ServerLevel level) {
        List<ItemEntity> out = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box())) {
            if (item.getItem().is(Items.RAW_IRON)) {
                out.add(item);
            }
        }
        return out;
    }

    /** bot 到某点的距离（**实测**前提：退休判据用的就是这个距离）。 */
    private double distanceTo(double x, double y, double z) {
        return Math.sqrt(bot.distanceToSqr(x, y, z));
    }

    /** **授权前提**：该落物的归属解析出来是"我方"（`DropPolicy.mayCollect` 看的就是它）。 */
    private boolean provenanceIsOurs(UUID id) {
        if (id == null) {
            return false;
        }
        for (ItemEntity item : candidates) {
            if (item.getUUID().equals(id)) {
                return scope.provenanceOf(item) != null;
            }
        }
        return false;
    }

    private boolean entityAlive(ServerLevel level, UUID id) {
        var entity = level.getEntity(id);
        return entity != null && !entity.isRemoved();
    }

    private int countProductInInventory() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.RAW_IRON)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
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
