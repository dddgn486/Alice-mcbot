package com.dddgn.alice.fixture;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.CollectDropsTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ **掉落物守恒（`MISMATCH`）的结构化读数与夹具**（电池步 `collect_conservation`；`step 5b` 刀①，
 * `D-496` 丙② + 用户 2026-09-27 裁定「全甲」）。
 *
 * <h2>它钉住什么（一句话）</h2>
 * {@link CollectDropsTask} 的守恒交叉校验
 * （`背包增量 == 簇起始 stack 总和 − 结束时剩余存活 stack 总和`）**真的在跑**，
 * 而且它的读数**从结构化出口拿得到**（不是去啃 `SUMMARY` 展示串 —— `D-329 ⑤.3`）。
 *
 * <h2>四条臂（`B甲`）—— 少一条这份夹具就证明不了事情</h2>
 * <pre>
 *   臂① DISCARD 丢掉**远的那一件**落物      ⇒ delta &lt; expected ⇒ **必须**报失配
 *                （物理对应：同类型被别的玩家/别的 bot 拾走；真机样本 `delta=0 expected=1 startSum=3 remaining=2`）
 *   臂② INJECT  往背包**塞同类** {@value #INJECT_COUNT} 件 ⇒ delta &gt; expected ⇒ **必须**报失配
 *                （物理对应：同类型从别的渠道进了包；真机样本 `delta=5 expected=4 startSum=4 remaining=0`）
 *   臂③ CONTROL **不干扰**                  ⇒ delta == expected ⇒ **必须不报**失配
 *   臂④ SHAPE   **与生产调用点逐字同形状**起收集器（`FishboneJob:1385-1389`，`D-496` 丙②）
 *                ⇒ 清单内收得到、清单外不专门收、且守恒成立
 * </pre>
 * ⭐ **臂③ 不可省**（`silent-measurement-failure` 规则 3）：臂①② 断言的都是「**记了**失配」，
 * 于是一个「每簇无脑 `mismatchCount++`」的实现**照样全绿** —— 只有「正常时必须不记」能把它抓出来。
 * ⚠️ 同理，臂① 和臂② 是**两个相反方向**（少了 / 多了）：只测一个方向的话，
 * 「只认少了、不认多了」的实现也全绿。
 *
 * <h2>确定性从哪来（`§6.9.1` ①：前提必须显式且可自证）</h2>
 * 干扰**不靠 sleep、不靠轮询、不靠运气**，锚在 {@code liveDropsSource} 这个既有钩子上：
 * <pre>
 *   CollectDropsTask.tick() 每 tick 第 1 件事 = refreshCandidates() ⇒ 调一次 source.get()；
 *   而它第 1 次看到**非空候选**的那一 tick 就 beginCluster()（`clusterStartSum` 当场定死，
 *   见 `tick()` 的 `clusterIds == null` 分支）
 *   ⇒ **第 {@value #INTERFERENCE_CALL} 次 get()** 里动手 = 数学上保证「簇已开始、簇还没结束」。
 * </pre>
 * 干扰**先于**本次 `get()` 的世界查询发生（见 {@link #armSource()}）⇒ 被丢掉的那件当 tick 就
 * 不在候选里，不需要任何额外同步。
 *
 * <h2>它断言哪一层（`§6.9.1` ③）</h2>
 * **动作层**：真世界里一个真的 {@link CollectDropsTask}，被本夹具**每 tick 驱动一 tick**
 * （与 `collect_offcenter_retry` / `collect_slot_approach` 同一个手法）。
 * 不经过 `Job` 层、不经过 `MineTask` ⇒ 断言落在真正做守恒计算的那一层。
 *
 * <h2>⚠️ 它**覆盖不了**什么（`D-496` 丙② 逐字要求写进注释）</h2>
 * <ol>
 *   <li><b>触发时机</b>：本夹具**自己**起收集器、自己决定什么时候动手。
 *       生产里「一批活干完才收集」那条结构边界（`requestBatchCollect` / `batchCollectDue` /
 *       `CollectKind.PERIODIC`）**不在**本步覆盖范围内（登记进 `step 5b` 未覆盖清单）。</li>
 *   <li><b>实参被改坏</b>：本夹具传的是**它自己**的实参 —— 生产那边把 `false` 改成 `true`、
 *       或把清单改成 `null`，本夹具**依然全绿**。这一半只能靠静态门禁
 *       （`tools/check-collect-callsite-shape.py`：钉 `FishboneJob` 那个调用点的**实参形状**）。
 *       ⇒ 「行为夹具 + 形状门禁」两半合起来才是一件事，缺一不可。</li>
 *   <li><b>真机里 `MISMATCH` 的成因分布</b>：本夹具只造两种**人为**干扰；
 *       真机 47/1121 份日志里的失配到底是「被他人拾取」多还是「背包满」多，本步答不了。</li>
 *   <li><b>预算路径</b>：`SWEEP_*` 那条推导式、锚点退役路径、`/alice mine` 顶层路径均不在本步。</li>
 * </ol>
 *
 * <h2>场景形状与几何前提（`§6.9.1` ①：盒子以谁为中心、多大，必须写下来）</h2>
 * <pre>
 *   空中孤岛（z=2800 一带，与其它夹具的 z=2600 片不重叠）：地板 y=ORIGIN.y
 *     x ∈ [ORIGIN.x−{@value #FLOOR_HALF_X}, ORIGIN.x+{@value #FLOOR_HALF_X}]，
 *     z ∈ [ORIGIN.z−{@value #FLOOR_HALF_Z}, ORIGIN.z+{@value #FLOOR_HALF_Z}]，上方 {@value #HEADROOM} 层空气
 *   bot 脚位    ORIGIN+(0, {@value #FOOT_DY}, 0)
 *   臂①②③ 落物 ORIGIN+({@value #NEAR_DX}, {@value #FOOT_DY}, 0) 与 +({@value #FAR_DX}, {@value #FOOT_DY}, 0)
 *                ⇒ 两件相距 1.0 格 ≤ 簇连通距离 2.0 ⇒ **同一个簇**（这是臂① 成立的前提）
 *   臂④   落物 ORIGIN+({@value #NEAR_DX}, {@value #FOOT_DY}, 0) 粗铁
 *                · ORIGIN+({@value #NEAR_DX}, {@value #FOOT_DY}, {@value #OFF_DZ}) 圆石
 *                ⇒ 相距 3 格（**沿 z 偏**，不是沿 x 再往东 —— 往东会走出地板，实测踩过）：
 *                  bot 走到粗铁旁停下时，圆石**不在**原版拾取盒（±1.425）内
 *                  ⇒ 「圆石没进包」只可能是**主动清单**造成的，不可能是「够不着」
 *   计数盒 {@link #box()}：以 ORIGIN 为中心、覆盖地板 + 上方（`preExistingNonAir` 用它自证是空中）
 * </pre>
 * ⚠️ **模组集假设自证**（`§6.9.1` ②）：臂④ 依赖「粗铁 ∈ 生产产物清单、圆石 ∉」——
 * 夹具**自己断言**这两条（见 {@link #premiseProductInList} / {@link #premiseNonProductOutOfList}），
 * 不靠「原版视角」的想当然。
 *
 * <h2>副作用边界（`§6.9.2`）</h2>
 * <ul>
 *   <li><b>不动父任务</b>：不调 `assign*` / `beginTask` / `replaceTaskIfRunning`，
 *       自己持有并 tick 一个 {@code CollectDropsTask}；</li>
 *   <li><b>失败路径同样收尾</b>：{@link #teardown()} 无条件跑（清方块回空气、撤销 forceload、
 *       丢掉造出来的落物、清背包、bot 回传送前脚位）—— 判据含「残留非空气 = 0」「场景内落物 = 0」；</li>
 *   <li><b>不 tick 终态任务</b>：收集器一旦不是 `RUNNING` 就置 `null`，不再 tick。</li>
 * </ul>
 *
 * <h2>用户能看到什么 / 看不到什么（`§6.9.3` 的用户侧预期管理）</h2>
 * 本步**不写世界留痕**（自建场景会被收回），在无头电池里**没有任何可见动作**；
 * 唯一证据 = 聊天/日志的 `[CollectConservation] SUMMARY`。
 */
public final class CollectConservationCheckTask implements Task {

    /** 场景原点（y=100 空中；z=2800 这一片**其它夹具不用**，见各夹具 ORIGIN 注释）。 */
    private static final BlockPos ORIGIN = new BlockPos(3200, 100, 2800);

    /** 地板范围（相对 ORIGIN，向两侧各这么多格）。 */
    private static final int FLOOR_HALF_X = 6;
    private static final int FLOOR_HALF_Z = 6;
    /** bot 脚位层与上方留空层数（相对 ORIGIN 的 y 偏移）。 */
    private static final int FOOT_DY = 1;
    private static final int HEADROOM = 5;

    /** 落物格（相对 ORIGIN 的脚位层 x 偏移）。 */
    private static final int NEAR_DX = 4;
    private static final int FAR_DX = 5;
    /**
     * 臂④「清单外」那件落物的格：**沿 z 偏 3 格**（不是沿 x 再往东！）。
     *
     * <p>⚠️ 2026-09-27 实测踩过一次：第一版把它放在 `+7` 格 ⇒ ① 超出地板（`FLOOR_HALF_X=6`）
     * ⇒ 那块**没有地板**、落物直接掉出场景；② 也超出计数盒（`box()` 的 maxX = `ORIGIN.x+7`，
     * 而物品中心在 `+7.5`）⇒ 夹具**看不见它**，报出来的是"落物没落地（实际 1/应 2）"这种
     * **指错方向**的话。这正是 `§6.9.1` ① 那条（"放种子的位置必须**落在盒内**，而不是按
     * '起点+偏移'想当然"，`D-179` 的事故）—— 现在由 {@link #premiseCellsInArena} 自证。
     */
    private static final int OFF_DZ = 3;

    /** 作用域半径（覆盖整个场景 ⇒ 追取上限 = `max(32, 2×12)` = 32，落物远在范围内）。 */
    private static final int SCOPE_RADIUS = 12;
    /** 本步预算（四条臂串行，各自一次走位 ≈ 40~60 tick）。 */
    private static final int BUDGET_TICKS = 1600;
    /** 等落物落地/可见的 tick 上限。 */
    private static final int SUMMON_CAP = 60;
    /** 单臂里收集器该在多少 tick 内结束（护栏，不判失败码 —— 超了如实报红）。 */
    private static final int RUN_CAP = 400;
    /**
     * 夹具自己给收集器的额度（`D-493` 拍点 3 `3甲` 落地后，**每个调用点都必须自己声明**它）。
     *
     * <p>历史（`D-495`）：改造前这里是「照抄 `CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS` 的值」，
     * 而 javadoc 还错说那个常量是私有的。默认口已删 ⇒ 这里**不再是副本**，而是调用方自己的额度
     * —— 静默脱钩那一族随之消失（值仍逐字 600 ⇒ 行为零变化）。
     */
    private static final int ARM_COLLECT_BUDGET_TICKS = 600;
    /** 臂② 往背包塞的同类件数。 */
    private static final int INJECT_COUNT = 3;
    /** 第几次 `source.get()` 里动手（第 1 次当 tick 就 `beginCluster` ⇒ 第 2 次必然「簇已开始、未结束」）。 */
    private static final int INTERFERENCE_CALL = 2;

    /** 臂④ 的「清单内」物品（= 生产产物过滤器认的那一族）。 */
    private static final Item PRODUCT = Items.RAW_IRON;
    /** 臂④ 的「清单外」物品（石头族 —— 真机实测占登记落物的 61% 那一族）。 */
    private static final Item NON_PRODUCT = Items.COBBLESTONE;
    /**
     * **判据出处 = 生产过滤器**（与 `FishboneJob.countProductItems()` 同一个类、同一个入口）
     * —— 夹具**不自己编**「什么算产物」（先例：`PickupGateCheckTask` 主动臂、`JobRegionGrantCheckTask`）。
     */
    private static final MineProductFilter PRODUCT_FILTER = MineProductFilter.forTag(null);

    /** 四条臂，按顺序跑。 */
    private enum Arm {
        /** ① 丢掉远的那件落物 ⇒ `delta < expected` ⇒ 必须报失配。 */
        DISCARD,
        /** ② 往背包塞同类 ⇒ `delta > expected` ⇒ 必须报失配。 */
        INJECT,
        /** ③ 不干扰 ⇒ `delta == expected` ⇒ 必须不报。 */
        CONTROL,
        /** ④ 与生产调用点同形状 ⇒ 清单内收得到、清单外不专门收、守恒成立。 */
        SHAPE
    }

    private static final List<Arm> ARMS = List.of(Arm.values());

    private enum Phase { SETUP, ARM_PREPARE, ARM_SETTLE, ARM_RUN, ARM_ASSERT, TEARDOWN, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int armIndex;
    private int settleTicks;
    private int runTicks;

    // ---- 场景（建/拆对称）----
    private final Map<BlockPos, BlockState> probe = new LinkedHashMap<>();
    private BlockPos entryFoot;
    private int preExistingNonAir = -1;
    private int builtBlocks;

    // ---- 每臂状态（臂开始前全部复位）----
    private int sourceCalls;
    private boolean interfered;
    private boolean injectedOk;
    private int injectedCount;
    private int adoptedThisArm = -1;
    private int expectedGround = -1;
    private int ironBefore = -1;
    private int nonProductBefore = -1;
    private CollectDropsTask collector;

    // ---- 每臂观测（断言用）----
    private CollectDropsTask.ConservationReading reading;
    private int mismatchTotalAfter = -1;
    /**
     * 跨臂**累计**（不随臂复位）—— `SUMMARY` 读的是这几个。
     *
     * <p>⚠️ 2026-09-27 实测踩过一次：`SUMMARY` 原先读的是**每臂复位**的那几个字段，
     * 于是最后一次打印的是**臂④**的数（它本来就没有干扰）⇒ 吐出 `discarded=0 injected=0`，
     * 把「臂① 明明丢了落物」读成「根本没干扰」。**读数看起来正常，但它答的是另一个问题。**
     */
    private int totalDiscarded;
    private int totalInjected;
    private final List<String> mismatchByArm = new ArrayList<>();
    private int ironPicked = -1;
    private int nonProductPicked = -1;
    private int nonProductRemaining = -1;
    private int groundLeft = -1;
    private String terminalReason = "";

    // ---- 臂④ 前提（模组/清单假设自证）----
    private boolean premiseProductInList;
    private boolean premiseNonProductOutOfList;
    /** 几何前提：本臂全部造物点都落在地板矩形内（`§6.9.1` ①，实测踩过一次）。 */
    private boolean premiseCellsInArena;

    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int checks;

    public CollectConservationCheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "CollectConservationCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.above(FOOT_DY));
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
        if (++ticks > BUDGET_TICKS && phase != Phase.ARM_ASSERT && phase != Phase.TEARDOWN
                && phase != Phase.DONE) {
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks
                    + "，阶段=" + phase + "，臂=" + arm() + "）", false);
            phase = Phase.TEARDOWN;
        }
        return switch (phase) {
            case SETUP -> setup();
            case ARM_PREPARE -> armPrepare();
            case ARM_SETTLE -> armSettle();
            case ARM_RUN -> armRun();
            case ARM_ASSERT -> armAssert();
            case TEARDOWN -> teardown();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    private Arm arm() {
        return ARMS.get(Math.min(armIndex, ARMS.size() - 1));
    }

    /**
     * 本臂全部造物点是否都落在地板矩形内（`§6.9.1` ① 的几何前提自证）。
     *
     * <p>判据两头都要：`|dx| ≤ FLOOR_HALF_X` 保证**下面有地板**（不会掉出场景），
     * 而物品中心在 `dx+0.5`、计数盒上界是 `ORIGIN.x+FLOOR_HALF_X+1` ⇒ 同一个不等式也保证
     * **在计数盒内**（实测踩过：`+7` 格两头都不满足，夹具却报成"落物没落地"）。
     */
    private boolean cellsInArena() {
        List<BlockPos> cells = switch (arm()) {
            case DISCARD -> List.of(ORIGIN.offset(NEAR_DX, FOOT_DY, 0), ORIGIN.offset(FAR_DX, FOOT_DY, 0));
            case INJECT, CONTROL -> List.of(ORIGIN.offset(NEAR_DX, FOOT_DY, 0));
            case SHAPE -> List.of(ORIGIN.offset(NEAR_DX, FOOT_DY, 0),
                    ORIGIN.offset(NEAR_DX, FOOT_DY, OFF_DZ));
        };
        for (BlockPos cell : cells) {
            if (Math.abs(cell.getX() - ORIGIN.getX()) > FLOOR_HALF_X
                    || Math.abs(cell.getZ() - ORIGIN.getZ()) > FLOOR_HALF_Z) {
                return false;
            }
        }
        return true;
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        forceload(level, true);

        // 硬前提①：这一片本来是空气（否则"自建场景"的基线不干净 —— `§6.9.1` ①）
        preExistingNonAir = 0;
        for (int dx = -FLOOR_HALF_X; dx <= FLOOR_HALF_X; dx++) {
            for (int dz = -FLOOR_HALF_Z; dz <= FLOOR_HALF_Z; dz++) {
                for (int dy = 0; dy <= HEADROOM; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        preExistingNonAir++;
                    }
                }
            }
        }
        if (preExistingNonAir != 0) {
            check("硬前提失败：场景位置本来有 " + preExistingNonAir + " 个非空气方块"
                    + "（空中孤岛不成立 ⇒ 本步其余判据都不成立）", false);
            phase = Phase.TEARDOWN;
            return Task.Status.RUNNING;
        }

        for (int dx = -FLOOR_HALF_X; dx <= FLOOR_HALF_X; dx++) {
            for (int dz = -FLOOR_HALF_Z; dz <= FLOOR_HALF_Z; dz++) {
                set(level, dx, 0, dz, Blocks.STONE);
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    set(level, dx, dy, dz, Blocks.AIR);
                }
            }
        }
        builtBlocks = probe.size();
        if (!level.getBlockState(ORIGIN).is(Blocks.STONE)) {
            check("硬前提失败：地板没落地（`setBlock` 静默 0 改动？区块未加载？）", false);
        }

        entryFoot = bot.blockPosition();
        teleportFoot(level, ORIGIN.offset(0, FOOT_DY, 0));

        // ⭐ 臂④ 的**模组集/清单假设自证**（`§6.9.1` ②）：不靠"原版视角"的想当然
        premiseProductInList = PRODUCT_FILTER.matches(new ItemStack(PRODUCT));
        premiseNonProductOutOfList = !PRODUCT_FILTER.matches(new ItemStack(NON_PRODUCT));

        BotLog.info("[CollectConservation] CHECK setup origin={} 地板方块={} 原本非空气={} "
                        + "清单前提(粗铁∈清单={} · 圆石∈清单={})",
                ORIGIN.toShortString(), builtBlocks, preExistingNonAir,
                premiseProductInList, !premiseNonProductOutOfList);
        phase = Phase.ARM_PREPARE;
        return Task.Status.RUNNING;
    }

    // ==================== 每臂：准备 → 落定 → 跑 → 断言 ====================

    private Task.Status armPrepare() {
        ServerLevel level = bot.serverLevel();
        // 臂之间的隔离：地上清空 + 背包清空 + bot 回固定起点（`§5.0d`：夹具自己传送，不依赖外部 provision）
        discardGround(level);
        FixtureToolKit.resetInventory(bot);
        teleportFoot(level, ORIGIN.offset(0, FOOT_DY, 0));

        sourceCalls = 0;
        interfered = false;
        injectedOk = false;
        injectedCount = 0;
        adoptedThisArm = -1;
        collector = null;
        runTicks = 0;
        settleTicks = 0;
        reading = null;
        mismatchTotalAfter = -1;
        ironPicked = -1;
        nonProductPicked = -1;
        nonProductRemaining = -1;
        groundLeft = -1;
        terminalReason = "";

        ironBefore = countInInventory(PRODUCT);
        nonProductBefore = countInInventory(NON_PRODUCT);

        boolean spawned = switch (arm()) {
            case DISCARD -> summon(level, ORIGIN.offset(NEAR_DX, FOOT_DY, 0), PRODUCT)
                    & summon(level, ORIGIN.offset(FAR_DX, FOOT_DY, 0), PRODUCT);
            case INJECT, CONTROL -> summon(level, ORIGIN.offset(NEAR_DX, FOOT_DY, 0), PRODUCT);
            case SHAPE -> summon(level, ORIGIN.offset(NEAR_DX, FOOT_DY, 0), PRODUCT)
                    & summon(level, ORIGIN.offset(NEAR_DX, FOOT_DY, OFF_DZ), NON_PRODUCT);
        };
        expectedGround = arm() == Arm.DISCARD ? 2 : arm() == Arm.SHAPE ? 2 : 1;
        premiseCellsInArena = cellsInArena();
        BotLog.info("[CollectConservation] CHECK arm={} 造物={} 应在地上={} 背包基线(产物={} 非产物={})"
                        + " 落点在地板内={}",
                arm(), spawned, expectedGround, ironBefore, nonProductBefore, premiseCellsInArena);
        // ⭐ 几何前提自证（`§6.9.1` ①）：造物点必须**落在地板矩形内**。
        // 不做这一条的话，"落物掉出场景"会伪装成"落物没落地"（实测踩过，`OFF_DZ` 的注释里记着）。
        check("臂 " + arm() + " 前提：全部造物点都落在**地板矩形内**（否则落物会掉出场景 / 掉出计数盒）",
                premiseCellsInArena);
        if (!spawned) {
            check("臂 " + arm() + " 硬前提失败：`/summon` 返回 0（抑制输出的命令源会静默失败 ⇒ 必须看返回值）",
                    false);
            phase = Phase.ARM_ASSERT;
            return Task.Status.RUNNING;
        }
        phase = Phase.ARM_SETTLE;
        return Task.Status.RUNNING;
    }

    private Task.Status armSettle() {
        ServerLevel level = bot.serverLevel();
        settleTicks++;
        List<ItemEntity> ground = groundItems(level);
        boolean allDown = ground.size() >= expectedGround && ground.stream().allMatch(ItemEntity::onGround);
        if (!allDown && settleTicks <= SUMMON_CAP) {
            return Task.Status.RUNNING;
        }
        check("臂 " + arm() + " 前提：落物全部可见且已落地（应为 " + expectedGround
                        + "，实际 " + ground.size() + "，等了 " + settleTicks + " tick）",
                ground.size() >= expectedGround);

        // 登记成**我方**（`OURS_DIRECT` ⇒ 原版拾取放行）—— 两件事一起办：
        // ① 让落物**捡得起来**（没登记 = FOREIGN ⇒ `DropPolicy` 拦截 ⇒ 四条臂会一起假红）；
        // ② 让 `scope.liveDrops()` **看得见**它们（臂④ 用生产那个 `null` 候选源，走的就是这条路）。
        //
        // ⚠️⚠️ **`begin` 必须显式传 `inheritDrops = false`（2026-09-27 实测踩过一次）**：
        // 三参重载 `begin(center, radius, owner)` 默认 **`inheritDrops = true`**（`D-124`：重开区间
        // **继承**已登记的掉落物归属），而继承只搬 `spawnedItems` + `itemOrigins`。
        // 本夹具是**每臂**重开一次区间，于是第 2 臂起出现这一串**静默假红**：
        //   `/summon` 造出来的落物**已被生成事件登记进 `spawnedItems`**（但还没配到 `itemOrigins`）
        //   ⇒ `begin` 把它"继承"进新窗口 ⇒ ① `adoptExistingDrops` 见它已在 `known` 里 ⇒ **跳过**
        //   （`adopted=0`）② `liveDrops()` 要求 `itemOrigins != null` ⇒ **看不见它**（臂④ 零候选、
        //   `clusters=0`）③ 没有 `OURS_DIRECT` ⇒ `DropPolicy` 拦拾取（`policy_blocked=1`、
        //   物品留在地上 `remaining=1`）。第 1 臂因为 `active == false`（没有旧窗口可继承）而侥幸通过。
        // ⇒ 夹具要的是 `D-124` javadoc 里那个**「干净区间」**语义。
        scope.begin(ORIGIN, SCOPE_RADIUS, bot.getUUID(), false);
        adoptedThisArm = scope.adoptExistingDrops(level, ORIGIN, SCOPE_RADIUS);
        check("臂 " + arm() + " 前提：落物全部登记成我方（应为 " + expectedGround
                        + "，实际 adopted=" + adoptedThisArm + "）", adoptedThisArm >= expectedGround);

        phase = Phase.ARM_RUN;
        return Task.Status.RUNNING;
    }

    private Task.Status armRun() {
        if (collector == null) {
            collector = buildCollector();
            BotLog.info("[CollectConservation] CHECK arm={} 起收集器：额度={} 候选源={} 主动清单={} "
                            + "worldMod=false 能力信封={}（第 {} 次取候选时动手）",
                    arm(), ARM_COLLECT_BUDGET_TICKS,
                    arm() == Arm.SHAPE ? "scope.liveDrops()（生产形状）" : "夹具实时重扫",
                    arm() == Arm.SHAPE ? "PRODUCT_FILTER::matches（生产形状）" : "无（全部落物）",
                    MiningProfile.STANDABLE_ONLY, INTERFERENCE_CALL);
        }
        runTicks++;
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            if (runTicks > RUN_CAP) {
                check("臂 " + arm() + " 夹具护栏：收集器跑了 " + runTicks + " tick 还没结束", false);
                collector = null;
                phase = Phase.ARM_ASSERT;
            }
            return Task.Status.RUNNING;
        }
        terminalReason = collector.terminalReason();
        // ⭐ 先抓读数**再**丢掉引用（丢掉后就没得读了）
        reading = collector.lastConservation();
        mismatchTotalAfter = collector.mismatchTotal();
        collector = null;          // `§6.9.2`：终态即不再 tick
        BotLog.info("[CollectConservation] CHECK arm={} 收集器结束 status={} ticks={} reason={}",
                arm(), status, runTicks, terminalReason);
        phase = Phase.ARM_ASSERT;
        return Task.Status.RUNNING;
    }

    /**
     * 起收集器 —— **臂④ 用与生产调用点逐字同形状**的那一组实参（`D-496` 丙②）。
     *
     * <p>生产那一处 = `FishboneJob:1385-1389`：
     * {@code (bot, template.startFoot(), scope, List.of(), false, <显式额度>,
     * MiningProfile.STANDABLE_ONLY, null, PRODUCT_FILTER::matches)}。
     * ⚠️ 形状本身由静态门禁 `tools/check-collect-callsite-shape.py` 钉住；
     * 本方法只证明「这一组实参打下去，行为是对的」—— **证明不了**「生产真的这么传」。
     */
    private CollectDropsTask buildCollector() {
        if (arm() == Arm.SHAPE) {
            return new CollectDropsTask(bot, ORIGIN, scope, List.of(), false,
                    ARM_COLLECT_BUDGET_TICKS, MiningProfile.STANDABLE_ONLY, null,
                    PRODUCT_FILTER::matches);
        }
        // 臂①②③：候选源换成"这块地里的落物"（`D-344` 的既有接缝）
        // ⇒ 干扰只可能来自**夹具自己**，不可能是作用域/归属/清单造成的。
        return new CollectDropsTask(bot, ORIGIN, scope, List.of(), false,
                ARM_COLLECT_BUDGET_TICKS, MiningProfile.STANDABLE_ONLY, this::armSource, null);
    }

    /**
     * 候选源 —— **本夹具唯一的确定性锚**（见类注释）。
     *
     * <p>顺序是承重的：**先动手、再查世界** ⇒ 被丢掉的那件当 tick 就不在候选里。
     */
    private List<ItemEntity> armSource() {
        sourceCalls++;
        if (sourceCalls == INTERFERENCE_CALL) {
            applyInterference();
        }
        return groundItems(bot.serverLevel());
    }

    private void applyInterference() {
        switch (arm()) {
            case DISCARD -> {
                ItemEntity victim = farthestGroundItem(bot.serverLevel());
                if (victim != null) {
                    victim.discard();
                    interfered = true;
                    totalDiscarded++;
                    BotLog.info("[CollectConservation] 臂① 干扰：丢掉**远的**那件落物 item={} pos={}"
                                    + "（地面应收 {} 件、实收 {} 件 ⇒ 守恒应报失配）",
                            victim.getUUID(), victim.blockPosition().toShortString(), expectedGround, 1);
                }
            }
            case INJECT -> {
                injectedOk = bot.getInventory().add(new ItemStack(PRODUCT, INJECT_COUNT));
                injectedCount = injectedOk ? INJECT_COUNT : 0;
                interfered = injectedOk;
                totalInjected += injectedCount;
                BotLog.info("[CollectConservation] 臂② 干扰：往背包塞同类 {} 件 ok={}"
                                + "（实收应比应收多 {} ⇒ 守恒应报失配）",
                        PRODUCT, injectedOk, INJECT_COUNT);
            }
            case CONTROL, SHAPE -> {
                // 负对照 / 生产形状：**不干扰** —— 这两臂的判据恰恰是「必须不报失配」
            }
        }
    }

    // ==================== 断言 ====================

    private Task.Status armAssert() {
        ServerLevel level = bot.serverLevel();
        ironPicked = countInInventory(PRODUCT) - ironBefore - injectedCount;
        nonProductPicked = countInInventory(NON_PRODUCT) - nonProductBefore;
        nonProductRemaining = countGround(level, NON_PRODUCT);
        groundLeft = groundItems(level).size();

        switch (arm()) {
            case DISCARD -> {
                check("臂① 前提：干扰真的发生了（丢掉远的那一件落物）", interfered);
                check("臂① 读数拿得到（本臂确实有簇结束过）", reading != null);
                if (reading != null) {
                    check("臂① 守恒**应当报失配**：delta=" + reading.delta()
                                    + " < expected=" + reading.expected()
                                    + "（startSum=" + reading.startSum() + " remaining=" + reading.remaining() + "）",
                            reading.mismatch() && reading.delta() < reading.expected());
                }
                check("臂① 累计失配计数 = 1（实际 " + mismatchTotalAfter + "）", mismatchTotalAfter == 1);
                notes.add("discard(delta=" + delta() + ",expected=" + expected() + ")");
            }
            case INJECT -> {
                check("臂② 前提：往背包塞同类成功（塞入 " + INJECT_COUNT + "）", injectedOk);
                check("臂② 前提：那一件产物**真的进包了**（" + ironPicked + " ≥ 1"
                        + " ⇒ 场景本身收得到，失配不是「没捡到」造成的）", ironPicked >= 1);
                check("臂② 读数拿得到（本臂确实有簇结束过）", reading != null);
                if (reading != null) {
                    check("臂② 守恒**应当报失配**：delta=" + reading.delta()
                                    + " > expected=" + reading.expected()
                                    + "（差额应恰为塞入的 " + INJECT_COUNT + "）",
                            reading.mismatch() && reading.delta() > reading.expected()
                                    && reading.delta() - reading.expected() == INJECT_COUNT);
                }
                check("臂② 累计失配计数 = 1（实际 " + mismatchTotalAfter + "）", mismatchTotalAfter == 1);
                notes.add("inject(delta=" + delta() + ",expected=" + expected() + ")");
            }
            case CONTROL -> {
                check("臂③ 前提：产物真的进包了（" + ironPicked + " ≥ 1"
                        + " ⇒ 场景本身收得到；否则本臂的红是「没捡到」，不是「计数器坏了」）", ironPicked >= 1);
                check("臂③ 读数拿得到（本臂确实有簇结束过）", reading != null);
                if (reading != null) {
                    check("臂③ 守恒**必须成立**：delta=" + reading.delta()
                                    + " == expected=" + reading.expected(),
                            !reading.mismatch() && reading.delta() == reading.expected());
                }
                check("臂③ 累计失配计数 = 0（实际 " + mismatchTotalAfter + "）"
                                + " —— ⭐ 没有这一条，一个「每簇无脑 +1」的实现也全绿",
                        mismatchTotalAfter == 0);
                notes.add("control(delta=" + delta() + ",expected=" + expected() + ")");
            }
            case SHAPE -> {
                check("臂④ 前提自证：粗铁 ∈ 生产产物清单 / 圆石 ∉ 生产产物清单"
                                + "（粗铁∈清单=" + premiseProductInList
                                + " 圆石∈清单=" + !premiseNonProductOutOfList + "）",
                        premiseProductInList && premiseNonProductOutOfList);
                check("臂④ 清单内：产物进包（" + ironPicked + " ≥ 1）", ironPicked >= 1);
                check("臂④ 清单外：圆石**不专门收**（背包增量=" + nonProductPicked + " 应=0）",
                        nonProductPicked == 0);
                check("臂④ 清单外：圆石**仍留在地上**（地上=" + nonProductRemaining + " 应=1"
                        + " ⇒ 「没进包」不是因为被捡走了）", nonProductRemaining == 1);
                check("臂④ 守恒成立（干净跑一趟不许报失配）",
                        reading != null && !reading.mismatch() && mismatchTotalAfter == 0);
                notes.add("shape(iron=" + ironPicked + ",cobble=" + nonProductPicked
                        + ",cobble_left=" + nonProductRemaining + ")");
            }
        }

        // ⚠️ 标签必须在 `armIndex++` **之前**取：第一版写在之后，于是每个标签都指向**下一臂**
        // （实测打出 `[INJECT=1,CONTROL=1,SHAPE=0,SHAPE=0]` —— 数是对的、标签是错的，
        //  正是 `silent-measurement-failure` 那一族："读数看起来正常，但它答的是另一个问题"）。
        mismatchByArm.add(arm() + "=" + mismatchTotalAfter);
        armIndex++;
        if (armIndex >= ARMS.size()) {
            phase = Phase.TEARDOWN;
        } else {
            phase = Phase.ARM_PREPARE;
        }
        return Task.Status.RUNNING;
    }

    private int delta() {
        return reading == null ? -1 : reading.delta();
    }

    private int expected() {
        return reading == null ? -1 : reading.expected();
    }

    // ==================== TEARDOWN ====================

    private Task.Status teardown() {
        ServerLevel level = bot.serverLevel();
        // ① 现场收干净：造出来的落物全丢、方块回空气、撤销 forceload、背包清空、bot 回原位
        discardGround(level);
        clearScene(level);
        forceload(level, false);
        FixtureToolKit.resetInventory(bot);
        if (entryFoot != null) {
            teleportFoot(level, entryFoot);
        }
        int left = remainingNonAir(level);
        check("收尾：自建方块已全部还原为空气（残留非空气=" + left + " 应=0）", left == 0);
        int items = groundItems(level).size();
        check("收尾：造出来的落物已全部收回（场景内残留=" + items + " 应=0）", items == 0);
        int carried = countInInventory(PRODUCT) + countInInventory(NON_PRODUCT);
        check("收尾：背包已复位（产物+非产物残留=" + carried + " 应=0）", carried == 0);

        boolean pass = failures.isEmpty();
        String summary = "arms=" + ARMS.size()
                + " discarded=" + totalDiscarded
                + " injected=" + totalInjected
                + " mismatch_by_arm=[" + String.join(",", mismatchByArm) + "]"
                + " iron_picked=" + ironPicked
                + " non_product_picked=" + nonProductPicked
                + " non_product_ground=" + nonProductRemaining
                + " ground_left=" + groundLeft
                + " premise_product_in_list=" + premiseProductInList
                + " premise_non_product_out=" + premiseNonProductOutOfList
                + " arms_detail=[" + String.join(" · ", notes) + "]"
                + " checks=" + checks + " reason=" + (pass ? "passed" : "failed")
                + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[CollectConservation] SUMMARY {}", summary);
        if (!pass) {
            BotLog.warn("[CollectConservation] 失败项 {} 条：{}", failures.size(), failures);
        }
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 掉落物守恒自检 " + summary));
        }
        phase = Phase.DONE;
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 场景原语 ====================

    private void set(ServerLevel level, int dx, int dy, int dz, net.minecraft.world.level.block.Block block) {
        BlockPos pos = ORIGIN.offset(dx, dy, dz);
        level.setBlock(pos, block.defaultBlockState(), 3);
        probe.put(pos, level.getBlockState(pos));
    }

    private AABB box() {
        return new AABB(ORIGIN.getX() - FLOOR_HALF_X, ORIGIN.getY(), ORIGIN.getZ() - FLOOR_HALF_Z,
                ORIGIN.getX() + FLOOR_HALF_X + 1, ORIGIN.getY() + HEADROOM + 1,
                ORIGIN.getZ() + FLOOR_HALF_Z + 1);
    }

    private List<ItemEntity> groundItems(ServerLevel level) {
        return level.getEntitiesOfClass(ItemEntity.class, box(), entity -> !entity.isRemoved());
    }

    private ItemEntity farthestGroundItem(ServerLevel level) {
        ItemEntity best = null;
        double bestDistance = -1;
        for (ItemEntity entity : groundItems(level)) {
            double distance = bot.distanceToSqr(entity);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private int countGround(ServerLevel level, Item item) {
        int total = 0;
        for (ItemEntity entity : groundItems(level)) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    private int countInInventory(Item item) {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void discardGround(ServerLevel level) {
        for (ItemEntity entity : groundItems(level)) {
            entity.discard();
        }
    }

    private void clearScene(ServerLevel level) {
        for (BlockPos pos : probe.keySet()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private int remainingNonAir(ServerLevel level) {
        int left = 0;
        for (BlockPos pos : probe.keySet()) {
            if (!level.getBlockState(pos).isAir()) {
                left++;
            }
        }
        return left;
    }

    private void forceload(ServerLevel level, boolean on) {
        for (int cx = (ORIGIN.getX() - FLOOR_HALF_X) >> 4; cx <= (ORIGIN.getX() + FLOOR_HALF_X) >> 4; cx++) {
            for (int cz = (ORIGIN.getZ() - FLOOR_HALF_Z) >> 4; cz <= (ORIGIN.getZ() + FLOOR_HALF_Z) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    /** 落物用 `/summon` 造（`PickupDelay:0s` ⇒ 立刻可捡）—— **必须看返回值**（陷阱 #5）。 */
    private boolean summon(ServerLevel level, BlockPos cell, Item item) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        int commands = level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(),
                "summon minecraft:item " + (cell.getX() + 0.5D) + " " + (cell.getY() + 0.2D) + " "
                        + (cell.getZ() + 0.5D) + " {Item:{id:\"" + id + "\",Count:1b},PickupDelay:0s}");
        return commands > 0;
    }

    private void teleportFoot(ServerLevel level, BlockPos foot) {
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
}
