package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteAudit;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.config.FishboneConfig;
import com.dddgn.alice.item.FishboneJobItem;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.fishbone.FishboneJob;
import com.dddgn.alice.job.fishbone.FishboneTemplate;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathingStats;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **鱼骨切片 2 夹具（`D-437`）：支巷 + 露头矿顺手挖 + 收集 —— 判据 `C1`（含支巷）/`C2`/`C5`**。
 *
 * <p>设计原文 = {@code docs/plans/2026-09-21-鱼骨挖矿计划.md} §6（判据）/§7-2（切片 2）/§10.1（暴露矿）/
 * §10.2（支巷放弃 vs 主巷失败）。本夹具**自建孤立场景**（`alice-scene-based-testing`：不依赖真机存档、
 * 不依赖前序步留下的地形），并且**刻意不复用切片 1 的夹具** —— `D-436 §一.3` 裁定"不动 `mine_vein_propagation`
 * 那类历史夹具"，同一条纪律在这里适用：切片 1 的三臂已绿且被钉住，**不许为了让新行为通过而改它们**。
 *
 * <h2>场景（三条臂共用一块场地，建/断言共用同一个盒）</h2>
 * <p>与切片 1 的"石体 + 两侧墙 + 盒外空气"不同，本夹具把**整盒填实心石**，期望表只说一件事：
 * <b>除了模板格（与刻意挖掉的露头矿）以外，一格都不许变</b>。⇒ "模板外改动 = 0"（`C1`/`C5`）
 * 从"没超过预算"变成**逐格枚举**。
 *
 * <h2>三条臂</h2>
 * <table border="1">
 *   <tr><th>臂</th><th>场景</th><th>期望终态</th><th>判据</th></tr>
 *   <tr><td>① {@code SPUR_TUNNEL}</td><td>主巷 6 + 间距 3 ⇒ 2 条支巷 ×3 格（全在 <b>LEFT</b>）</td>
 *       <td>{@code template_complete}</td>
 *       <td>`C1` 含支巷 24 格全空 / `C3` 零 `SEARCH_LIMIT` / `C4` 可返回 / 支巷退路 2 次</td></tr>
 *   <tr><td>② {@code SPUR_ABANDONED}</td><td>同上，但**第 1 条支巷的第 2 格是基岩**</td>
 *       <td>⭐ {@code template_complete_spurs_abandoned=1}（**DONE**）</td>
 *       <td>§10.2「子巷放弃 / 主巷失败」两档：基岩与**它之后的支巷格零改动**、主巷与第 2 条支巷照常挖完</td></tr>
 *   <tr><td>③ {@code ORE_IN_PLACE}</td><td>纯主巷 6，顶棚嵌 3 个铁矿 + 1 个**不该被挖的诱饵矿**</td>
 *       <td>{@code template_complete}</td>
 *       <td>`C2` 产物进包 ≥ 3 / `C5` 破坏 ⊆（模板 ∪ 露头矿）/ 顺手挖**不带位移** / 诱饵**原封不动**</td></tr>
 * </table>
 *
 * <p>⭐ 臂②的**判别性对照**在切片 1 的臂②：同一份基岩放在**主巷** ⇒ `main_blocked:` + FAILED + 先回家；
 * 放在**支巷** ⇒ `spur_abandoned:` + 主巷继续 + DONE。两条臂都断言"那一格零改动"，但**终态与状态不同**
 * ⇒ 把"两档合并"这个错法钉成红（`§10.2` 用户裁定的原话就是"子巷放弃 / 主巷如实失败"）。
 *
 * <h2>§6.9.1 三条前提（写下来并自断言）</h2>
 * <ol>
 *   <li><b>几何盒</b>：{@link #ORIGIN} 起 12×7×11 的实心石盒；建场景、期望表、断言**共用同一个盒**；</li>
 *   <li><b>世界假设</b>：y=80 高空，且离切片 1（3600,80,2400）有 160 格 ⇒ 两片场地不连、
 *       不与自然地形相连；</li>
 *   <li><b>层归属</b>：断言落在**世界事实**（逐格方块表 + `WriteAudit` + 背包实物）上，
 *       不落在"任务自报完成"上（`JobKindContract`/`D-349`）。</li>
 * </ol>
 */
public final class FishboneSlice2CheckTask implements Task {

    // ==================== 场景常量（几何，唯一真值） ====================

    /** 起点脚位（= 模板 `startFoot`；也是 `RETURN` 目标与收集作用域中心）。 */
    private static final BlockPos ORIGIN = new BlockPos(3760, 80, 2400);

    private static final Direction DIR = Direction.EAST;

    private static final int MAIN_LENGTH = 6;

    /** 支巷间距（3 ⇒ 支巷挂在第 3、6 个主巷单元之后）。 */
    private static final int SPUR_SPACING = 3;

    /** 支巷长度（3 格 ⇒ 臂②的基岩之后**还有一格**，才能验"跳过剩余"）。 */
    private static final int SPUR_LENGTH = 3;

    private static final int HEIGHT = FishboneTemplate.DEFAULT_HEIGHT;

    private static final FishboneTemplate.SpurSide SIDE = FishboneTemplate.SpurSide.LEFT;

    /** 第 1 条支巷挂靠的主巷单元（1-based；= `SPUR_SPACING`）。 */
    private static final int FIRST_SPUR_MAIN_UNIT = SPUR_SPACING;

    /** 臂②：第 1 条支巷的**第 2 格**放基岩（之后还有第 3 格 ⇒ 验"跳过剩余单元"）。 */
    private static final int SPUR_BEDROCK_STEP = 2;

    /** 臂③：哪几个主巷单元的顶棚嵌铁矿（1-based）。 */
    private static final int[] ORE_UNITS = {1, 3, 5};

    /** 臂④：矿脉所在的**主巷单元**（1-based）。 */
    private static final int VEIN_UNIT = 3;
    /** 臂④：矿脉从巷道壁往里伸的**深度**（格）—— ≥2 格就是"只挖贴壁那一层"时代挖不到的部分。 */
    private static final int VEIN_DEPTH = 3;
    /**
     * 臂④的矿脉方向 = **南**侧。
     *
     * <p>⚠️ 必须避开模板格：`SIDE=LEFT` 且行进方向是东 ⇒ 支巷在北侧（`getCounterClockWise()`），
     * 所以矿脉放南侧，才可能是"**只有追簇才会挖到**"的格。
     */
    private static final Direction VEIN_SIDE = Direction.SOUTH;
    /** 臂④的**地板矿脉**占哪几格支巷（`LEFT`=北侧那条；支巷长 3 ⇒ 取第 2、3 格）。 */
    private static final int[] FLOOR_VEIN_STEPS = {1, 2};
    /**
     * 地板矿脉的**深度**（格）。
     *
     * <p>⚠️ **本臂只钉 1 格**：2 格深会踩到一条**物理边界**（已单独登记，见 `D-440 §边界`）——
     * 挖掘器的模式 A **拒绝把"正在挖的那一格本身"当站位**（那是 `DOWNWARD` 的语义），
     * 所以矿脉最底那一格**本来就挖不掉**（`ore_deferred:no_reachable_standing_point`），
     * 且坑里那颗掉落物可能收不回来（终态 `product_not_collected`）。
     * 那条边界要用户拍板"要不要允许挖脚下 / 要不要把'坑里够不着'降级为如实上报"。
     */
    private static final int FLOOR_VEIN_DEPTH = 1;

    /** 整盒边界（建场景 / 期望表 / 断言**共用**）。 */
    private static final int BOX_MIN_DX = -2;
    private static final int BOX_MAX_DX = MAIN_LENGTH + 3;
    private static final int BOX_MIN_DY = -2;
    private static final int BOX_MAX_DY = HEIGHT + 2;
    /** 支巷长 3 格（`dz = -1..-3`）+ 一圈石壳（`-4`）+ 余量 ⇒ 5。 */
    private static final int BOX_HALF_DZ = 5;

    private static final int BUDGET_TICKS = 5000;
    private static final int JOB_MAX_TICKS = 2000;
    private static final int SETTLE_CAP = 40;

    /** `C3` 的两条规模上界（与切片 1 同口径；多出的支巷退路/收集是**常数**级开销）。 */
    private static final int PLANS_PER_ADVANCE = 6;
    private static final int NODES_PER_ADVANCE = 40;
    private static final int TICK_COST_REPORT_ONLY = 250;

    private enum Arm { SPUR_TUNNEL, SPUR_ABANDONED, ORE_IN_PLACE, ORE_VEIN_CHASE }

    private enum Phase { SETUP_ARM, SETTLE, RUN, ASSERT, CLEANUP, DONE }

    private static final Arm[] ARMS = Arm.values();

    // ==================== 运行态 ====================

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    private Phase phase = Phase.SETUP_ARM;
    private int armIndex;
    private int ticks;
    private int settleTicks;
    private int checks;
    private final List<String> failures = new ArrayList<>();

    private FishboneTemplate template;
    private FishboneJob job;
    private BlockPos entryFoot;

    private String armVerdict = "";
    private PathingStats.Scale scaleBefore;
    private PathingStats.Scale scaleDelta;
    private int auditBefore;
    private int auditDeltaBreaks;
    private int auditOutsideExpected;
    private int auditDeltaPlaces;
    private int auditOutsidePlaces;
    private int worldDiff;
    private int ticksPerAdvance;
    private int productsBefore;
    /** 本臂开始时 `failures` 的长度（用于把 ARM 行报成**本臂**的成败，而不是累计的）。 */
    private int failuresAtArmStart;

    public FishboneSlice2CheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "FishboneSlice2Check";
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
        if (++ticks > BUDGET_TICKS && phase != Phase.ASSERT && phase != Phase.CLEANUP && phase != Phase.DONE) {
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks + "，arm="
                    + ARMS[Math.min(armIndex, ARMS.length - 1)] + " job="
                    + (job == null ? "null" : job.terminalReason()) + "）", false);
            phase = Phase.CLEANUP;
        }
        return switch (phase) {
            case SETUP_ARM -> setupArm();
            case SETTLE -> settle();
            case RUN -> runJob();
            case ASSERT -> assertArm();
            case CLEANUP -> cleanup();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== 模板（每臂一份；几何真源只有 `FishboneTemplate`） ====================

    private FishboneTemplate templateFor(Arm arm) {
        return arm == Arm.ORE_IN_PLACE
                ? FishboneTemplate.main(ORIGIN, DIR, MAIN_LENGTH)
                : FishboneTemplate.spurs(ORIGIN, DIR, MAIN_LENGTH, SPUR_SPACING, SPUR_LENGTH, SIDE);
    }

    /** 臂②的基岩格 = 第 1 条支巷的第 {@link #SPUR_BEDROCK_STEP} 格。 */
    private BlockPos bedrockCell() {
        return ORIGIN.relative(DIR, FIRST_SPUR_MAIN_UNIT)
                .relative(Direction.NORTH, SPUR_BEDROCK_STEP);     // LEFT of EAST = NORTH
    }

    /** 臂③第 `unit` 个单元的顶棚矿格（= 净高之上那一格）。 */
    private BlockPos oreCell(int unit) {
        return ORIGIN.relative(DIR, unit).above(HEIGHT);
    }

    /**
     * 臂③的**诱饵矿**：在第一个顶棚矿的**正上方第二格**。
     *
     * <p>⚠️ 切片 4 把它从"正上方一格"**上移了一格**，原因是一条真事实：
     * 追簇会挖掉"我们已挖掉的矿格"的相邻矿 ⇒ 原来那一格（正上方一格）**现在会被挖掉**，
     * 它已经不是诱饵了。上移之后它与任何已挖格都**不相邻**（中间隔着石头）⇒ ② 必然不达标。
     * 这条诱饵因此反而更强了：它现在考的是「**追簇不会凭空扩散**」（不只是"模板邻域之外"）。
     */
    private BlockPos decoyCell() {
        return oreCell(ORE_UNITS[0]).above(2);
    }

    /**
     * 臂④的**矿脉**：从主巷单元 {@link #VEIN_UNIT} 的南侧壁往里伸 {@link #VEIN_DEPTH} 格。
     *
     * <p>形状刻意做成不规则的团（真实矿脉就是这样）：贴着巷道的前两列**两格高**，
     * 最里面那列只有脚位那一格。
     *
     * <p>为什么第一列要两格高：矿挖掉之后 bot 得能**走进去捡**（1 格高的洞站不进去 ⇒
     * 掉落物永远捡不回）。这条不是"为夹具方便"，而是**真机上也成立**的约束。
     */
    private Set<BlockPos> veinCells() {
        Set<BlockPos> out = new LinkedHashSet<>(sideVeinCells());
        out.addAll(floorVeinCells());
        return out;
    }

    /** 臂④的**侧壁矿脉**（南侧，主巷单元 3 的壁上）。 */
    private Set<BlockPos> sideVeinCells() {
        BlockPos junction = ORIGIN.relative(DIR, VEIN_UNIT);
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int d = 1; d <= VEIN_DEPTH; d++) {
            out.add(junction.relative(VEIN_SIDE, d));
            if (d <= 2) {
                out.add(junction.relative(VEIN_SIDE, d).above(1));
            }
        }
        return out;
    }

    /**
     * ⭐ 臂④的**地板矿脉**（2026-09-25 真机那一例的忠实复现）：矿簇**就在支巷的地板里**
     * （第 {@link #FLOOR_VEIN_STEPS} 格的**正下方**那一格）。
     *
     * <p>真机证据：`cell unit=49/404 cell=2/2 target=-68, 47, 202 spur=west14` ⇒
     * `[MiningPlanner探针] no_valid_standing_point faceStandable=0/6 belowSolid=false` ⇒
     * `spur_abandoned:no_reachable_standing_point`（用户原话：「只是因为挖矿簇往下挖了两格，就把支巷放弃了」）。
     * 修法不是"别挖地板"，而是**让规划器补一块再走**（`PLACE_STEP_AND_TRAVERSE`，`D-440`）。
     */
    private Set<BlockPos> floorVeinCells() {
        BlockPos junction = ORIGIN.relative(DIR, VEIN_UNIT);
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int step : FLOOR_VEIN_STEPS) {
            // ⚠️ **两格深**（用户原话：「只是因为挖矿簇往下挖了两格，就把支巷放弃了」）：
            // 只挖掉 1 格时 bot 站在洞里还能斜向上够到下一格（夹具实测：纯通行也照样跑完 ⇒ 判据不够硬）；
            // 挖掉 2 格之后它在头位格下方 3 格 ⇒ **没有任何可站格** ⇒ 复现真机的 `no_valid_standing_point`。
            for (int down = 1; down <= FLOOR_VEIN_DEPTH; down++) {
                out.add(junction.relative(Direction.NORTH, step).below(down));
            }
        }
        return out;
    }

    /** 臂②里**不会变成空气**的支巷格（基岩那格 + 它之后的格）—— 期望表要用。 */
    private Set<BlockPos> abandonedCells() {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (FishboneTemplate.Unit u : templateFor(Arm.SPUR_ABANDONED).units()) {
            if (u.isSpur() && u.mainUnit() == FIRST_SPUR_MAIN_UNIT && u.spurStep() >= SPUR_BEDROCK_STEP) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    out.add(u.foot().above(dy));
                }
            }
        }
        return out;
    }

    /** 本臂**期望变成空气**的格 = 模板格 ∪ 起点口袋（− 臂②放弃的支巷格）+（臂③）挖掉的露头矿。 */
    private Set<BlockPos> expectedAir(Arm arm) {
        Set<BlockPos> out = new LinkedHashSet<>(templateFor(arm).cellSet());
        out.add(ORIGIN);
        out.add(ORIGIN.above());
        if (arm == Arm.SPUR_ABANDONED) {
            out.removeAll(abandonedCells());
        }
        if (arm == Arm.ORE_IN_PLACE) {
            for (int unit : ORE_UNITS) {
                out.add(oreCell(unit));
            }
        }
        if (arm == Arm.ORE_VEIN_CHASE) {
            out.addAll(sideVeinCells());
        }
        return out;
    }

    /**
     * 臂④的**地板矿脉格**：期望 = **空气或圆石**（`PLACE_STEP_AND_TRAVERSE` 补回来的就是它）。
     *
     * <p>为什么两头都接受：补不补取决于"bot 之后还要不要走这一格"—— 要考的是
     * **"矿必须没了"**（不是"必须补"）＋ 另一条独立判据 `placements >= 1` 专门考"补路真的发生了"。
     * 把两件事压成一条，会让"没补路"和"没挖矿"看起来一模一样。
     */
    private Set<BlockPos> floorVeinCells(Arm arm) {
        return arm == Arm.ORE_VEIN_CHASE ? floorVeinCells() : Set.of();
    }

    /**
     * `C5` 的**放置**白名单 = 破坏白名单 ∪ 「它们的正下方」。
     *
     * <p>口径（为什么这样定）：`PLACE_STEP_AND_TRAVERSE` 的放置位置**就是目标格的正下方**
     * （`to.below()`），所以"只允许在**我们本来就有权碰的格**的下方补" 是一句可检验的强话 ——
     * 它挡住了"沿路随便乱垫"（那会让 bot 有能力把石头垫到任何地方）。
     */
    private Set<BlockPos> placementWhitelist(Arm arm) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos p : writeWhitelist(arm)) {
            out.add(p);
            out.add(p.below());
            out.add(p.below(2));
        }
        return out;
    }

    /** `C5` 的白名单 = 模板格 ∪ 暴露矿格（臂③露头矿 / 臂④整条矿脉）；其余臂 = 模板格。 */
    private Set<BlockPos> writeWhitelist(Arm arm) {
        Set<BlockPos> out = new LinkedHashSet<>(templateFor(arm).cellSet());
        if (arm == Arm.ORE_IN_PLACE) {
            for (int unit : ORE_UNITS) {
                out.add(oreCell(unit));
            }
        }
        if (arm == Arm.ORE_VEIN_CHASE) {
            out.addAll(veinCells());
        }
        return out;
    }

    // ==================== 建场景 ====================

    private Task.Status setupArm() {
        ServerLevel level = bot.serverLevel();
        Arm arm = ARMS[armIndex];

        forceload(level, true);
        if (entryFoot == null) {
            entryFoot = bot.blockPosition();
        }
        clearBox(level);
        buildSolidBox(level, arm);

        teleport(level, ORIGIN);
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        bot.controller().stopMovement();

        template = templateFor(arm);
        productsBefore = countProducts();
        // ⭐ 切片 3（`D-438`）：真机入口 `alice:fishbone_job` 的默认模板 —— 环境无关、只跑一次
        if (armIndex == 0) {
            liveEntryChecks();
        }

        // ⭐ 前提自断言（`alice-scene-based-testing` §6.9.1）：场景没落地 ⇒ 后面所有判据都不可解读
        boolean sceneOk = level.getBlockState(ORIGIN).isAir()
                && level.getBlockState(ORIGIN.below()).is(Blocks.STONE);
        if (arm == Arm.ORE_IN_PLACE) {
            // 本臂的"前方那一格"是石头，但**它上面那格是矿**（这正是"顶棚露头矿"的形态）
            sceneOk = sceneOk
                    && level.getBlockState(ORIGIN.relative(DIR, MAIN_LENGTH)).is(Blocks.STONE)
                    && level.getBlockState(oreCell(ORE_UNITS[0])).is(Blocks.IRON_ORE)
                    && level.getBlockState(decoyCell()).is(Blocks.IRON_ORE);
        } else {
            sceneOk = sceneOk
                    && level.getBlockState(ORIGIN.relative(DIR, 1)).is(Blocks.STONE)
                    && level.getBlockState(ORIGIN.relative(DIR, 1).above(HEIGHT)).is(Blocks.STONE);
            if (arm == Arm.SPUR_ABANDONED) {
                sceneOk = sceneOk && level.getBlockState(bedrockCell()).is(Blocks.BEDROCK);
            }
            if (arm == Arm.ORE_VEIN_CHASE) {
                boolean veinOk = true;
                for (BlockPos p : veinCells()) {
                    veinOk = veinOk && level.getBlockState(p).is(Blocks.IRON_ORE);
                }
                sceneOk = sceneOk && veinOk;
            }
        }
        check("前提（" + arm + "）：场景已落地（起点空气 + 脚下实心 + 本臂特有要素就位）", sceneOk);

        failuresAtArmStart = failures.size();
        scaleBefore = PathingStats.scale();
        auditBefore = WriteAudit.snapshot().size();
        BotLog.info("[Fishbone2] CHECK arm={} template={} tick={} scaleBefore={} 产物基线={}",
                arm, template.describe(), ticks, scaleBefore.describe(), productsBefore);
        settleTicks = 0;
        phase = Phase.SETTLE;
        return Task.Status.RUNNING;
    }

    /**
     * **整盒填实心石**（与切片 1 的"石体 + 盒外空气"不同）—— 这样"模板外改动 = 0"才是**逐格枚举**。
     *
     * <p>只有三处例外：起点口袋（2 格空气）、臂②的基岩、臂③的矿（3 露头 + 1 诱饵）。
     */
    private void buildSolidBox(ServerLevel level, Arm arm) {
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    level.setBlock(ORIGIN.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        // 起点口袋（实心体之外那一格必须空，否则 bot 站不进去）
        level.setBlock(ORIGIN, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(ORIGIN.above(), Blocks.AIR.defaultBlockState(), 3);
        if (arm == Arm.ORE_VEIN_CHASE) {
            for (BlockPos p : veinCells()) {
                level.setBlock(p, Blocks.IRON_ORE.defaultBlockState(), 3);
            }
        }
        if (arm == Arm.SPUR_ABANDONED) {
            level.setBlock(bedrockCell(), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        if (arm == Arm.ORE_IN_PLACE) {
            for (int unit : ORE_UNITS) {
                level.setBlock(oreCell(unit), Blocks.IRON_ORE.defaultBlockState(), 3);
            }
            level.setBlock(decoyCell(), Blocks.IRON_ORE.defaultBlockState(), 3);
        }
    }

    // ==================== 跑作业 ====================

    private Task.Status settle() {
        settleTicks++;
        bot.controller().stopMovement();
        if (FixturePremise.settledOnGround(bot, settleTicks) || settleTicks > SETTLE_CAP) {
            job = new FishboneJob(bot, template, scope, JOB_MAX_TICKS);
            phase = Phase.RUN;
        }
        return Task.Status.RUNNING;
    }

    private Task.Status runJob() {
        Task.Status status = job.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        armVerdict = job.terminalReason();
        scaleDelta = PathingStats.scale().delta(scaleBefore);
        Set<BlockPos> allowed = writeWhitelist(ARMS[armIndex]);
        List<WriteAudit.Entry> audit = WriteAudit.snapshot();
        auditDeltaBreaks = 0;
        auditOutsideExpected = 0;
        auditDeltaPlaces = 0;
        auditOutsidePlaces = 0;
        Set<BlockPos> placeAllowed = placementWhitelist(ARMS[armIndex]);
        for (int i = auditBefore; i < audit.size(); i++) {
            WriteAudit.Entry entry = audit.get(i);
            if ("break".equals(entry.action())) {
                auditDeltaBreaks++;
                if (!allowed.contains(entry.pos())) {
                    auditOutsideExpected++;
                }
            } else if ("place".equals(entry.action())
                    && FishboneJob.isOurs(entry.grant().requester())) {
                auditDeltaPlaces++;
                if (!placeAllowed.contains(entry.pos())) {
                    auditOutsidePlaces++;
                }
            }
        }
        ticksPerAdvance = job.advancedUnits() == 0 ? -1 : job.elapsedTicks() / job.advancedUnits();
        BotLog.info("[Fishbone2] RUN arm={} verdict={} status={} ticks={} advance={}/{} mined={} skipped={}"
                        + " spursAbandoned={} spurUnitsSkipped={} spurReturns={} scanned={}"
                        + " oreMined={} oreFound={} oreDeferred={} oreUncollected={} budgetExhausted={}"
                        + " oreWalkedAway={} collectedProducts={}"
                        + " auditBreaks={} auditOutsideExpected={} places={} placesOutside={}"
                        + " scale={} ticksPerAdvance={}",
                ARMS[armIndex], armVerdict, status, job.elapsedTicks(), job.advancedUnits(), job.unitCount(),
                job.minedCells(), job.skippedCells(), job.spursAbandoned(), job.spurUnitsSkipped(),
                job.spurReturns(), job.scannedUnits(), job.oreMined(), job.oreFound(), job.oreDeferred(),
                job.oreUncollected(), job.oreBudgetExhausted(),
                job.oreWalkedAway(), job.collectedProducts(), auditDeltaBreaks, auditOutsideExpected,
                auditDeltaPlaces, auditOutsidePlaces,
                scaleDelta.describe(), ticksPerAdvance);
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    // ==================== 断言 ====================

    private Task.Status assertArm() {
        ServerLevel level = bot.serverLevel();
        Arm arm = ARMS[armIndex];
        worldDiff = diffExpected(level, arm);

        switch (arm) {
            case SPUR_TUNNEL -> assertSpurTunnel(level);
            case SPUR_ABANDONED -> assertSpurAbandoned(level);
            case ORE_IN_PLACE -> assertOreInPlace(level);
            case ORE_VEIN_CHASE -> assertVeinChase(level);
        }

        // 两条公共判据（**每一臂**都过）
        check("（" + arm + "）⭐`C1` 场景与期望表**逐格一致**（差异格=" + worldDiff
                        + "）—— 期望表 = 整盒石头，**除模板格 / 挖掉的露头矿 / 起点口袋以外一格不变**"
                        + "（这是「模板外改动 = 0」的最强形态：不是「没超预算」，是「逐格枚举」）",
                worldDiff == 0);
        check("（" + arm + "）⭐`C5` 世界写入全部落在白名单内（破坏 " + auditDeltaBreaks + " 条，白名单外 "
                        + auditOutsideExpected + " 条）—— 白名单 = 模板格" + (arm == Arm.ORE_IN_PLACE
                        ? " ∪ 露头矿格（**不许**顺手挖掉非矿的阻挡）" : ""),
                auditOutsideExpected == 0);
        check("（" + arm + "）`C4` 结束时**脚位回到起点**（XZ 偏差 " + fmt(dxz()) + " ≤ 1.5；脚位="
                        + bot.blockPosition().toShortString() + "）", dxz() <= 1.5D);
        check("（" + arm + "）`C3` **零 `SEARCH_LIMIT`**（预算是预算、可达性是可达性）｜searchLimit="
                        + scaleDelta.searchLimits(), scaleDelta.searchLimits() == 0);

        BotLog.info("[Fishbone2] ARM {} {}（本臂新增失败 {} 条）verdict={} scale={} ticksPerAdvance={}",
                arm, armFailures() == 0 ? "PASS" : "FAIL", armFailures(),
                armVerdict, scaleDelta.describe(), ticksPerAdvance);

        armIndex++;
        if (armIndex >= ARMS.length) {
            phase = Phase.CLEANUP;
        } else {
            phase = Phase.SETUP_ARM;
        }
        return Task.Status.RUNNING;
    }

    /** 臂①：`C1`（含支巷）+ 支巷退路记账 + `C3`。 */
    private void assertSpurTunnel(ServerLevel level) {
        int cells = template.cells().size();
        int spurs = template.spurCount();
        check("① 终态 = `template_complete`（实际 " + armVerdict + "）",
                FishboneJob.TEMPLATE_COMPLETE.equals(armVerdict));
        check("① `C1` 模板 " + cells + " 格（主巷 " + MAIN_LENGTH + " + " + spurs + " 条支巷 ×"
                        + SPUR_LENGTH + " 单元，每单元净高 " + HEIGHT + "）**全部为空气**（见期望表判据）",
                worldDiff == 0 && cells == (MAIN_LENGTH + spurs * SPUR_LENGTH) * HEIGHT);
        check("① `C1` 推进记账自洽：`advance=" + job.advancedUnits() + " == advanceCells="
                        + template.advanceCells() + "`，`mined+skipped=" + (job.minedCells() + job.skippedCells())
                        + " == 模板格数 " + cells + "`",
                job.advancedUnits() == template.advanceCells()
                        && job.minedCells() + job.skippedCells() == cells);
        check("① `C1` 真的挖了东西（mined=" + job.minedCells() + " > 0）", job.minedCells() > 0);
        // ⭐ 支巷真的被挖了：不是"主巷挖完就报完成"
        int spurCellsAir = 0;
        int spurCellsTotal = 0;
        for (FishboneTemplate.Unit u : template.units()) {
            if (!u.isSpur()) {
                continue;
            }
            for (int dy = 0; dy < HEIGHT; dy++) {
                spurCellsTotal++;
                if (level.getBlockState(u.foot().above(dy)).isAir()) {
                    spurCellsAir++;
                }
            }
        }
        check("① ⭐**支巷真的被挖穿**（支巷格 " + spurCellsAir + "/" + spurCellsTotal + " 为空气）"
                        + "—— 否则这条臂与切片 1 的臂① 无区别", spurCellsTotal > 0 && spurCellsAir == spurCellsTotal);
        check("① 计划 §3 `SPUR`：**每条支巷各退回主巷一次**（spurReturns=" + job.spurReturns()
                        + " == 支巷条数 " + spurs + "）；若没有显式退路，这条会红",
                job.spurReturns() == spurs);
        check("① `spursAbandoned=0`（这条臂不该放弃任何支巷，实际 " + job.spursAbandoned() + "）",
                job.spursAbandoned() == 0);
        // C3：规模恒定
        check("① `C3` 规划次数与推进格数成正比：plans=" + scaleDelta.plans() + " ≤ "
                        + PLANS_PER_ADVANCE + " × " + template.advanceCells(),
                scaleDelta.plans() <= PLANS_PER_ADVANCE * template.advanceCells());
        check("① `C3` 展开节点与推进格数成正比：nodes=" + scaleDelta.nodes() + " ≤ "
                        + NODES_PER_ADVANCE + " × " + template.advanceCells(),
                scaleDelta.nodes() <= NODES_PER_ADVANCE * template.advanceCells());
        check("① 读数（`survey/32`）：每轮规划 `" + fmt(scaleDelta.millisPerPlan()) + " ms/次`、"
                        + "每格 tick 成本 `" + ticksPerAdvance + " tick/格`（参考上限 " + TICK_COST_REPORT_ONLY + "）",
                ticksPerAdvance > 0 && ticksPerAdvance <= TICK_COST_REPORT_ONLY);
    }

    /** 臂②：§10.2「子巷放弃 / 主巷失败」两档 —— 支巷遇基岩 ⇒ 放弃 + 主巷继续。 */
    private void assertSpurAbandoned(ServerLevel level) {
        int abandonedUnits = SPUR_LENGTH - SPUR_BEDROCK_STEP + 1;   // 第 2、3 格两个单元
        check("② 终态 = `template_complete_spurs_abandoned=1`（实际 " + armVerdict + "）"
                        + "—— ⭐与 `main_blocked:` **分开**：只说 `template_complete` 会让决策层以为"
                        + "「全挖完了」，只说 `main_blocked:` 会把「支巷可以放弃」的行为报成失败",
                "template_complete_spurs_abandoned=1".equals(armVerdict));
        check("② 状态 = **DONE**（`§10.2`：子巷放弃 ⇒ 主巷继续 ⇒ 作业成功；不是 FAILED）｜"
                        + "failureReason=\"" + job.failureReason() + "\"",
                job.failureReason().isEmpty());
        check("② 放弃记账：`spursAbandoned=1`（实际 " + job.spursAbandoned() + "）、"
                        + "`spurUnitsSkipped=" + job.spurUnitsSkipped() + "`（期望 " + abandonedUnits
                        + " = 基岩那格 + 它之后那格）、原始理由=\"" + job.firstSpurAbandonReason() + "\"",
                job.spursAbandoned() == 1 && job.spurUnitsSkipped() == abandonedUnits
                        && !job.firstSpurAbandonReason().isEmpty());
        check("② 记账自洽：`advance + spurUnitsSkipped == advanceCells`（" + job.advancedUnits() + " + "
                        + job.spurUnitsSkipped() + " == " + template.advanceCells() + "）",
                job.advancedUnits() + job.spurUnitsSkipped() == template.advanceCells());
        check("② ⭐基岩那一格**零改动**（" + bedrockCell().toShortString() + " 仍是基岩）",
                level.getBlockState(bedrockCell()).is(Blocks.BEDROCK));
        // ⭐ 判别性：主巷与**另一条**支巷必须照常挖完（否则"放弃"退化成"整个作业停了"）
        int mainNotAir = 0;
        for (int unit = 1; unit <= MAIN_LENGTH; unit++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                if (!level.getBlockState(template.footOf(unit).above(dy)).isAir()) {
                    mainNotAir++;
                }
            }
        }
        int secondSpurNotAir = 0;
        for (FishboneTemplate.Unit u : template.units()) {
            if (u.isSpur() && u.mainUnit() != FIRST_SPUR_MAIN_UNIT) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    if (!level.getBlockState(u.foot().above(dy)).isAir()) {
                        secondSpurNotAir++;
                    }
                }
            }
        }
        check("② ⭐**主巷照常挖完**（非空气 " + mainNotAir + " 格）且**第 2 条支巷也照常挖完**（非空气 "
                        + secondSpurNotAir + " 格）—— 「放弃一条子巷」不许退化成「整个作业停了」",
                mainNotAir == 0 && secondSpurNotAir == 0);
        check("② 计划 §3 `SPUR`：两条支巷都退回过主巷（spurReturns=" + job.spurReturns() + " == 2）"
                        + "—— 被放弃的那条同样要沿支巷退回",
                job.spurReturns() == 2);
        check("② 放弃后**没有**在主巷上报失败码（verdict 不含 `main_blocked:`/`main_unreachable:`）",
                !armVerdict.startsWith("main_blocked:") && !armVerdict.startsWith("main_unreachable:"));
    }

    /** 臂③：`C2`（露头矿进包）+ `C5`（不越界）+ "顺手挖不带位移" + 诱饵不被挖。 */
    private void assertOreInPlace(ServerLevel level) {
        int expectedOres = ORE_UNITS.length;
        check("③ 终态 = `template_complete`（实际 " + armVerdict + "）",
                FishboneJob.TEMPLATE_COMPLETE.equals(armVerdict));
        check("③ 露头矿真的被识别并挖掉：`oreFound=" + job.oreFound() + "`、`oreMined=" + job.oreMined()
                        + "`（期望都 = " + expectedOres + "）、`oreDeferred=" + job.oreDeferred() + "`",
                job.oreFound() == expectedOres && job.oreMined() == expectedOres && job.oreDeferred() == 0);
        check("③ ⭐计划 §10.1「顺手 = 不必移动」：`oreWalkedAway=" + job.oreWalkedAway()
                        + "`（必须为 0 —— 就地挖**不该**把 bot 带走；走去挖是「跟随/探洞」的事，不是鱼骨）",
                job.oreWalkedAway() == 0);
        // ⭐ C2：**独立测量**（夹具自己数背包里的原铁，不信任务自报）
        int gained = countProducts();
        check("③ ⭐`C2` **露头矿进包**：夹具独立清点原铁 " + gained + " ≥ " + expectedOres
                        + "（`inventoryDelta ≥ N`；任务自报 `collectedProducts=" + job.collectedProducts()
                        + "`）—— 这条要求**收集半径从模板推导**（`D-346`：半径 < 作业直径 ⇒ 判据永不成立）",
                gained >= expectedOres && job.collectedProducts() >= expectedOres);
        check("③ 3 个顶棚矿格已是空气（见期望表判据；差异格=" + worldDiff + "）", worldDiff == 0);
        check("③ ⭐**诱饵矿原封不动**（" + decoyCell().toShortString() + " 仍是铁矿）—— 计划 §10.1"
                        + "「只在每个单元挖完那一 tick 扫**该单元自己的** 6 邻域、不全局扫、不递归」："
                        + "诱饵在第一个顶棚矿的**正上方**，永远进不了任何单元的邻域",
                level.getBlockState(decoyCell()).is(Blocks.IRON_ORE));
        check("③ `C5` 破坏数**精确等于** 模板格 + 露头矿格（" + auditDeltaBreaks + " == "
                        + (template.cells().size() + expectedOres) + "）",
                auditDeltaBreaks == template.cells().size() + expectedOres);
        check("③ `C3` 规划次数与推进格数成正比：plans=" + scaleDelta.plans() + " ≤ "
                        + PLANS_PER_ADVANCE + " × " + template.advanceCells(),
                scaleDelta.plans() <= PLANS_PER_ADVANCE * template.advanceCells());
        isolateOrePredicate(level);
    }

    /**
     * ⭐ **臂④：矿脉追挖（切片 4 / `D-439`）—— 用户 2026-09-25 真机报告的缺陷的正面判据**。
     *
     * <p>真机原话：「沿着矿簇挖了一两个，但是**洞壁和洞顶没有完全挖完**」（附截图：巷道左上壁的
     * 钻石矿、中段壁上的铜矿都还在）。结构原因：条件②只认**模板格**当暴露面 ⇒ 矿挖掉第一格之后，
     * 第二格旁边挨着的是刚挖出来的**矿洞**、不是模板格 ⇒ ② 必然不成立 ⇒ **矿簇永远只挖一层**。
     */
    private void assertVeinChase(ServerLevel level) {
        Set<BlockPos> vein = veinCells();
        Set<BlockPos> floorVeinCellsSet = floorVeinCells();
        // ⚠️ 地板那几格的期望是**空气或圆石**：补路用的是 `PILLAR`（在脚下放一块）⇒ 它变圆石。
        // 这条判据问的是"**矿还在不在**"，"补没补"由下面独立的 `places >= 1` 判据承担。
        long resolved = vein.stream()
                .filter(pos -> level.getBlockState(pos).isAir()
                        || (floorVeinCellsSet.contains(pos)
                                && level.getBlockState(pos).is(Blocks.COBBLESTONE)))
                .count();
        check("④ ⭐**矿脉整簇挖干净**（" + resolved + "/" + vein.size() + " 格已不再是矿：空气或补回的圆石）"
                        + " —— 才是对「洞壁和洞顶没有完全挖完」的正面回答",
                resolved == vein.size());
        check("④ ⭐破坏账 = 世界改动：`oreMined=" + job.oreMined() + "` == 矿脉 " + vein.size()
                        + " 格（破坏那一刻记账，不是「收集段成功了几次」）",
                job.oreMined() == vein.size());
        check("④ `oreFound=" + job.oreFound() + "` ≥ " + vein.size() + "（每一格都进过候选队列）",
                job.oreFound() >= vein.size());
        check("④ ⭐`oreDeferred=" + job.oreDeferred() + "` == 0（没有一格因为「够不着」被丢掉 —— "
                        + "触及范围才是追簇的自然上界）",
                job.oreDeferred() == 0);
        check("④ ⭐`oreUncollected=" + job.oreUncollected() + "` == 0（**挖得掉也要捡得回**；"
                        + "矿脉第一列两格高就是为了让 bot 走进去捡）",
                job.oreUncollected() == 0);
        check("④ `oreBudgetExhausted=" + job.oreBudgetExhausted() + "` == 0（" + vein.size()
                        + " 格矿脉不该碰到每单元上限 " + FishboneConfig.oreBudgetPerUnit() + "）",
                job.oreBudgetExhausted() == 0);
        check("④ `oreWalkedAway=" + job.oreWalkedAway() + "` == 0（≤ " + FishboneJob.ORE_STAND_ADJUST_MAX
                        + " 格的站位微调不算「走过去」，见 `D-439` 的真机证据）",
                job.oreWalkedAway() == 0);
        int gained = countProducts() - productsBefore;
        check("④ ⭐`C2` **矿簇进包**：夹具独立清点原铁 +" + gained + " ≥ " + vein.size()
                        + "（不读任务自报值）", gained >= vein.size());
        check("④ 终态 = `template_complete`（实际 " + armVerdict + "）",
                FishboneJob.TEMPLATE_COMPLETE.equals(armVerdict));
        // ⭐⭐ 切片 5（`D-440`）的核心：**地板是矿簇**也不能把支巷搞死
        long floorLeft = floorVeinCells().stream().filter(pos -> level.getBlockState(pos).is(Blocks.IRON_ORE))
                .count();
        check("④ ⭐⭐**地板里的矿簇也被挖掉**（" + (floorVeinCells().size() - floorLeft) + "/"
                        + floorVeinCells().size() + " 格不再是铁矿）—— 真机那一例就是它把支巷搞死的",
                floorLeft == 0);
        long spurAir = templateFor(Arm.ORE_VEIN_CHASE).units().stream()
                .filter(u -> u.isSpur() && u.mainUnit() == VEIN_UNIT && u.spurDir() == Direction.NORTH)
                .flatMap(u -> java.util.stream.IntStream.range(0, HEIGHT).mapToObj(dy -> u.foot().above(dy)))
                .filter(pos -> level.getBlockState(pos).isAir()).count();
        check("④ ⭐⭐**支巷照常挖到底**（" + spurAir + "/"
                        + (templateFor(Arm.ORE_VEIN_CHASE).spurLength() * HEIGHT)
                        + " 格为空气）—— 而真机里它在第 14 格被放弃了",
                spurAir == templateFor(Arm.ORE_VEIN_CHASE).spurLength() * HEIGHT);
        check("④ ⭐⭐`spursAbandoned=" + job.spursAbandoned() + "` == 0（**一条支巷都不该被放弃** —— "
                        + "这正是本轮要修的东西）", job.spursAbandoned() == 0);
        check("④ ⭐**补路真的发生了**：`places=" + auditDeltaPlaces + "` ≥ 1"
                        + "（`PILLAR` 跳起在脚下补一块 / `PLACE_STEP_AND_TRAVERSE` 在目标格下方补一块）"
                        + " —— 真机实测走的是 `[Pillar] placed pos=3763,79,2397`（即被挖掉的那格地板）",
                auditDeltaPlaces >= 1);
        check("④ ⭐**补路只补在允许的位置**（白名单外 " + auditOutsidePlaces + " 处；白名单 = 模板格/矿格"
                        + " ∪ 它们的正下方）", auditOutsidePlaces == 0);
        isolateOrePredicate(level);
    }

    /**
     * ⭐ **用真实输入把 `opportunisticTarget` 的三个条件逐个隔离**（不是真值表 —— 真值表会把
     * "谓词被绕过"这种错法放过；这里每条都是世界事实 + 真实 bot 位置）。
     *
     * <p>放在臂③的断言段（此时模板已挖通、bot 已回到起点）⇒ 临时放一格测试矿、判完再抹掉；
     * 收尾的 `clearBox` 无论如何都会清干净，**失败路径也走**。
     */
    private void isolateOrePredicate(ServerLevel level) {
        BlockPos test = oreCell(MAIN_LENGTH);
        BlockPos second = test.above();     // "矿脉第二层"：只挨着已挖掉的矿格，不挨任何模板格
        level.setBlock(test, Blocks.IRON_ORE.defaultBlockState(), 3);
        level.setBlock(second, Blocks.IRON_ORE.defaultBlockState(), 3);
        Set<BlockPos> exposed = template.cellSet();
        Set<BlockPos> afterFirstOre = new LinkedHashSet<>(exposed);
        afterFirstOre.add(test);
        try {
            // ③ 隔离：是同一种矿、相邻模板格也已通行，但 bot 在 5 格外 ⇒ 够不着 ⇒ 必须 false
            teleport(level, ORIGIN);
            check("③ ⭐条件③隔离：矿与暴露面都成立，但 bot 在 " + MAIN_LENGTH
                            + " 格外 ⇒ **不算顺手**（不许凭空挖远处的矿）",
                    !FishboneJob.opportunisticTarget(level, bot, test, exposed));

            // 站到第 5 格（已挖通）⇒ 视线与触及都成立
            teleport(level, ORIGIN.relative(DIR, MAIN_LENGTH - 1));

            // ② 隔离：换一份**不含**相邻模板格的暴露面（挪到 60 格外）⇒ 必须 false
            FishboneTemplate elsewhere = FishboneTemplate.main(ORIGIN.offset(60, 0, 0), DIR, MAIN_LENGTH);
            check("③ ⭐条件②隔离：矿与 bot 都就位，但相邻的格**不属于本作业挖出来的面** ⇒ 不达标"
                            + "（计划 §10.1：范围锁死在「我们自己的作业面」，不做「看到矿就去挖」）",
                    !FishboneJob.opportunisticTarget(level, bot, test, elsewhere.cellSet()));

            // ④ 三条件齐备 ⇒ true
            check("③ ⭐三条件齐备 ⇒ 判为暴露矿候选（① 是矿 ② 本次作业的暴露面 ③ 视线通 + 触及）",
                    FishboneJob.opportunisticTarget(level, bot, test, exposed));

            // ① 隔离：同一位置的**非矿**（侧墙石头，与已挖通的模板格相邻、视线触及都成立）⇒ 必须 false
            BlockPos wall = ORIGIN.relative(DIR, MAIN_LENGTH - 1).relative(Direction.NORTH);
            check("③ ⭐条件①隔离：把候选换成石头（相邻格已通行、视线与触及都成立）⇒ 不达标"
                            + "（**不许顺手挖掉非矿的阻挡** —— 与 `C5` 是同一件事）",
                    !FishboneJob.opportunisticTarget(level, bot, wall, exposed));

            // ⭐⭐ 切片 4 的核心（这一段就是 `D-439` 的判据本体）：
            // 先把第一层矿"挖掉"（置空气）—— 这样下面两条的差别**只可能**来自条件②
            // （视线已通、触及也够；否则判据就是"因为别的原因恰好为假"，那是 `D-425`⑤ 的假达标）。
            level.setBlock(test, Blocks.AIR.defaultBlockState(), 3);
            // ⚠️ 这里断的是**条件②本身**（`exposedByOurExcavation`），不是整个三合一谓词 ——
            // 第一版用三合一谓词，结果它是因为**视线**（而非②）为假 ⇒ 判据"因为别的原因恰好为假"
            // （`D-425` ⑤ 的假达标）。端到端那一层由 arm ④ 的行为判据（矿脉 5/5 格变空气）承担。
            check("④ ⭐⭐**矿脉第二层**：暴露面只传**模板格** ⇒ ②**不成立**"
                            + " —— 这正是真机「矿簇只挖贴壁那一层」的结构原因（第二格旁边挨着的是矿洞，不是模板格）",
                    !FishboneJob.exposedByOurExcavation(level, second, exposed));
            check("④ ⭐⭐**矿脉第二层**：把**已被我们挖掉的第一层矿格**并进暴露面 ⇒ ②**成立**"
                            + "（暴露面 = **本次作业挖出来的面**，不是只有模板格 ⇒ 追簇能一层层往里走）",
                    FishboneJob.exposedByOurExcavation(level, second, afterFirstOre));
        } finally {
            level.setBlock(test, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(second, Blocks.AIR.defaultBlockState(), 3);
            teleport(level, ORIGIN);
        }
    }

    // ==================== 期望方块表（唯一真值：建场景与断言共用） ====================

    /** 逐格比对，返回**不一致的格数**（> 0 即"动了不该动的地方"或"该挖的没挖"）。 */
    private int diffExpected(ServerLevel level, Arm arm) {
        Set<BlockPos> air = expectedAir(arm);
        Set<BlockPos> floorVein = floorVeinCells(arm);
        int bad = 0;
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    BlockPos pos = ORIGIN.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    boolean ok;
                    if (arm == Arm.SPUR_ABANDONED && pos.equals(bedrockCell())) {
                        ok = state.is(Blocks.BEDROCK);
                    } else if (arm == Arm.ORE_IN_PLACE && pos.equals(decoyCell())) {
                        ok = state.is(Blocks.IRON_ORE);
                    } else if (floorVein.contains(pos)) {
                        ok = state.isAir() || state.is(Blocks.COBBLESTONE);
                    } else if (air.contains(pos)) {
                        ok = state.isAir();
                    } else {
                        ok = state.is(Blocks.STONE);
                    }
                    if (!ok) {
                        bad++;
                        if (bad <= 5) {
                            BotLog.warn("[Fishbone2] 期望表不符 pos={} 期望={} 实际={}", pos.toShortString(),
                                    air.contains(pos) ? "air" : "stone", state.getBlock());
                        }
                    }
                }
            }
        }
        return bad;
    }

    // ==================== 收尾 ====================

    private Task.Status cleanup() {
        ServerLevel level = bot.serverLevel();
        clearBox(level);
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        check("收尾：场地清回空气（残留非空气格=" + countNonAir(level) + "）", countNonAir(level) == 0);
        boolean pass = failures.isEmpty();
        BotLog.info("[Fishbone2] SUMMARY checks={} failures={} arms={} pass={} → {}｜失败项：{}",
                checks, failures.size(), ARMS.length, pass, pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 鱼骨切片 2 " + (pass ? "PASS" : "FAIL")
                    + "（" + ARMS.length + " 臂：" + checks + " checks / " + failures.size()
                    + " failures，详见日志 [Fishbone2]）"));
        }
        phase = Phase.DONE;
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    /** 本臂新增的失败条数（`ARM` 行用它判本臂成败 —— 累计口径会把前一臂的红算到后一臂头上）。 */
    private int armFailures() {
        return failures.size() - failuresAtArmStart;
    }

    /**
     * ⭐ **真机入口 `alice:fishbone_job` 的模板必须是合法且符合裁定的形状**（`D-438` / `D-439`）。
     *
     * <p>为什么夹具要管这件事：它是本片唯一的**真机**入口，形状写错（间距抄成 1 ⇒ 构造抛异常、
     * 侧向抄成 `LEFT` ⇒ 左右不对称、支巷抄短 ⇒ 挖不到 32 格）**在离线就该咬住** ——
     * 等到你右键那一刻才发现，等于白烧一个客户端轮。这里调的是**生产代码的工厂**
     * （`FishboneJobItem.templateFor`），不是照常量另抄一份。
     *
     * <p>⚠️ 切片 4 起尺寸来自 `config/alice-fishbone.toml` ⇒ 判据分两层：
     * <ol>
     *   <li><b>默认值层</b>（用户 2026-09-25 的三条裁定，钉在常量上）：`BOTH` / 中心距 3 / 支巷 ≥ 32
     *       —— 这是**用户裁定的钉子**，将来改裁定要**同时**改这里（否则静默回退会没人发现）；</li>
     *   <li><b>结构层</b>（跟配置走）：尺寸自洽（条数/单元/格数三个数必须互相对得上）+ **左右对称**
     *       + 沿主巷每 `spurSpacing` 格一个位置 + 每条支巷真的有 `spurLength` 格。
     *       ⇒ 你把配置改大改小都不会把夹具判红，但**形状坏了**一定红。</li>
     * </ol>
     */
    private void liveEntryChecks() {
        // ① 用户裁定的默认值（钉子）
        check("真机默认配置 = 用户 2026-09-25 裁定的形状：" + FishboneConfig.DEFAULT_SIDE + "（左右对称）· 中心距 "
                        + FishboneConfig.DEFAULT_SPUR_SPACING + " 格（两根之间空 2 格）· 支巷 ≥ 32 格（实际 "
                        + FishboneConfig.DEFAULT_SPUR_LENGTH + "）",
                FishboneConfig.DEFAULT_SIDE == FishboneTemplate.SpurSide.BOTH
                        && FishboneConfig.DEFAULT_SPUR_SPACING == 3
                        && FishboneConfig.DEFAULT_SPUR_LENGTH >= 32);

        // ② 四个方向的模板都合法、且三个数互相对得上
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            FishboneTemplate live = FishboneJobItem.templateFor(ORIGIN, d);
            check("真机入口（dir=" + d.getName() + "）：主巷 " + live.mainLength() + " · 中心距 "
                            + live.spurSpacing() + " · 支巷 " + live.spurBranches() + " 条 × "
                            + live.spurLength() + " 格（" + live.side() + "）· 净高 " + live.height()
                            + " ⇒ 单元 " + live.advanceCells() + " · 模板格 " + live.cells().size(),
                    live.mainLength() == FishboneConfig.mainLength()
                            && live.spurSpacing() == FishboneConfig.spurSpacing()
                            && live.spurLength() == FishboneConfig.spurLength()
                            && live.side() == FishboneConfig.side()
                            && live.height() == FishboneConfig.height()
                            && live.advanceCells() == live.mainLength() + live.spurBranches() * live.spurLength()
                            && live.cells().size() == live.advanceCells() * live.height()
                            && live.scopeRadius() == live.mainLength()
                                    + (live.hasSpurs() ? live.spurLength() : 0) + 2);
        }

        // ③ 三条结构不变式（只看一个方向就够：它们与方向无关）
        FishboneTemplate live = FishboneJobItem.templateFor(ORIGIN, DIR);
        // ⚠️ 数的是**条**（每条支巷的起点格 `spurStep == 1`），不是格 —— 第一版数成了格
        //（192 vs 192 看着"相等"，其实是 6 条 × 32 格），于是判据自己把自己判红。
        long left = live.units().stream().filter(u -> u.isSpur() && u.spurStep() == 1
                && u.spurDir() == DIR.getCounterClockWise()).count();
        long right = live.units().stream().filter(u -> u.isSpur() && u.spurStep() == 1
                && u.spurDir() == DIR.getClockWise()).count();
        boolean bothSides = live.side() == FishboneTemplate.SpurSide.BOTH;
        check("真机形状 ⭐**左右对称**：行进方向左手侧 " + left + " 条 / 右手侧 " + right + " 条"
                        + "（共 " + live.spurBranches() + " 条）。用户裁定「主巷左右两边对称」——"
                        + (bothSides ? "当前 `BOTH` ⇒ 两侧必须**逐格相等**"
                        : "当前 side=" + live.side() + " **不是** `BOTH` ⇒ 本条只查总数自洽；"
                                + "对称性由上面的默认值判据钉住"),
                left + right == live.spurBranches() && (!bothSides || (left > 0 && left == right)));
        check("真机形状 ⭐**沿主巷每 " + live.spurSpacing() + " 格一个位置**：位置数 " + live.spurCount()
                        + " == 主巷 " + live.mainLength() + " / " + live.spurSpacing()
                        + "（用户裁定「间隔两格」= 中心距 3）",
                !live.hasSpurs() || live.spurCount() == live.mainLength() / live.spurSpacing());
        int deepest = 0;
        for (FishboneTemplate.Unit u : live.units()) {
            if (u.isSpur()) {
                deepest = Math.max(deepest, u.spurStep());
            }
        }
        check("真机形状 ⭐**每条支巷真的挖到 " + deepest + " 格深**（分母 " + live.spurLength()
                        + "；用户裁定「起码 32 格」⇒ 默认配置已由第 1 条判据钉住）",
                !live.hasSpurs() || deepest == live.spurLength());
    }

    private void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(name);
            BotLog.warn("[Fishbone2] ✗ {}", name);
        } else {
            BotLog.info("[Fishbone2] ✓ {}", name);
        }
    }

    /** 背包里**原铁**的件数（`C2` 的**独立**测量口；不读任务的任何自报值）。 */
    private int countProducts() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.RAW_IRON)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private double dxz() {
        return Math.abs(bot.getX() - (ORIGIN.getX() + 0.5D)) + Math.abs(bot.getZ() - (ORIGIN.getZ() + 0.5D));
    }

    private void clearBox(ServerLevel level) {
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    level.setBlock(ORIGIN.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private int countNonAir(ServerLevel level) {
        int total = 0;
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    /** forceload（**建/拆对称**：它是全局世界状态，泄漏会污染后续步）。 */
    private void forceload(ServerLevel level, boolean on) {
        int minX = ORIGIN.getX() + BOX_MIN_DX;
        int maxX = ORIGIN.getX() + BOX_MAX_DX;
        int minZ = ORIGIN.getZ() - BOX_HALF_DZ;
        int maxZ = ORIGIN.getZ() + BOX_HALF_DZ;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
