package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CostModel;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ⭐⭐ **水中/离地挖掘的规划成本必须等于执行侧的真值**（电池步 `mining_water_break_cost`；`D-385` 的判据）。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>`estimateBreakTicks` 的输出必须等于执行侧真正会付的 tick 数 —— 在**四种状态**下都是</b>：
 * vanilla 的 `Player.getDigSpeed` 里有两项状态惩罚
 * （`isEyeInFluid(WATER) && !hasAquaAffinity ⇒ /= 5`、`!onGround() ⇒ /= 5`），
 * 执行侧（{@code BlockBreakSession} 每 tick 累加 `BlockState.getDestroyProgress`）照付，
 * 而规划侧原来只等于「站在地上 + 眼不在水里」那一档 ⇒ 眼在水里乐观 5×、眼在水里且离地乐观 25×。
 *
 * <h2>判据分三层（红在哪一层，结论就归哪一层）</h2>
 * <ol>
 *   <li><b>场景前提（红了 = 夹具姿势不对，不是缺陷证据）</b>：四个状态组合真的被造出来了 ——
 *       `(眼在水里, 在地面)` = `(否,是)` / `(是,是)` / `(是,否)` / `(否,否)`；
 *       而且 <b>vanilla 自己</b>在这四档上给出的倍数确实是 1 / 5 / 25 / 5（`1.0F/getDestroyProgress` 实测）
 *       ⇒ 证明「被测量的那个惩罚」在这个运行环境里真实存在（防"量了个幽灵"）。</li>
 *   <li><b>⭐ 被测判据</b>：每个状态下 `BlockInteraction.estimateBreakTicks` ==
 *       `1.0F / state.getDestroyProgress(bot, level, pos)`（相对 1e-4 —— 只差 float/double 精度）。</li>
 *   <li><b>⚠️ 故意偏离 vanilla 的那一档</b>（`DRY_STALE_FLAG`）：bot **站在石头上**但 `onGround` 标志位是
 *       陈旧的 false（传送到场景起点那一 tick 就是这样）⇒ Alice 按**几何事实**（脚下有没有耐久支撑）
 *       不罚（×1），vanilla 按标志位罚 ×5。**这不是宽容，而是证据驱动的修正**：CORE `pathing` 步的
 *       `break_course` 在只用标志位的版本下从「破墙过去 52 tick」翻成「搭柱翻墙 65 tick」（实测 FAIL）。
 *       本用例把那一幕钉住（撤掉"耐久支撑"守卫 ⇒ 它红）。</li>
 *   <li><b>⭐ 生产后果</b>：同一个几何（`from → mid(石) → to`）用生产边生成器 `SurfaceMovementProvider`
 *       取出的 `BREAK_AND_TRAVERSE` 边，在水面浮着时的**破坏项**必须比陆地时贵
 *       `(est_湿 − est_干)/6` 走路格（非破坏部分两边相同 ⇒ 差额只可能来自惩罚）；
 *       并且代价结论成立：<b>陆地挖石头仍比放一块便宜，水里挖石头已比放一块贵</b>
 *       ⇒ 规划器**自然**偏向放置，而不是靠"水里优先放置"的特判。</li>
 * </ol>
 *
 * <h2>场景形状（全部自断言）</h2>
 * <pre>
 *   空中孤立平台（ORIGIN 一圈本来就是空气，见 SETUP 的硬前提）：
 *     环形石箱   x∈[-2,2] z∈[-2,2] y∈[-4,0]  → 石头（5×5×5，内部挖空成水池）
 *     水池内部   x∈[-1,1] z∈[-1,1] y∈[-3,0]  → 按用例填 水 / (下石上空气)
 *     干燥踏板   x∈[3,8]  z∈[-2,2] y=-1      → 石头（脚位层 y=0，与水池顶层同高）
 *   规划器被测的几何：from=(1,0,0) ── mid=(2,0,0)=环形石壁（**被测的破坏目标**）── to=(3,0,0)
 * </pre>
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * <b>成本层 + 边生成层</b>：不 tick 子任务、不执行、不写世界（每用例都断言"测量前后 5 格未变"），
 * 毫秒级、确定性。
 *
 * <h2>副作用边界（§6.9.2）</h2>
 * 自己建地形、自己清地形（收尾把整个场景区域清回空气 + 撤销 forceload + 清背包 + 回传送前位置）；
 * 不调 {@code assign*}/{@code beginTask}；不动写入授权（只用 `PathRequest.withWorldModification` 做**只读**的边枚举）。
 */
public final class MiningWaterBreakCostCheckTask implements Task {

    /** 专用孤立点（y=100 的空中；与 `scope_pending_grace`(3600,100,2000) 相距 600 格）。 */
    private static final BlockPos ORIGIN = new BlockPos(3600, 100, 2600);

    /** 环形石箱的半宽（5×5）。 */
    private static final int BOX_HALF = 2;
    /** 石箱高度（y ∈ [-4, 0]）。 */
    private static final int BOX_BOTTOM_DY = -4;
    /** 水池内部（挖空 3×3×4，y ∈ [-3, 0]）。 */
    private static final int PIT_BOTTOM_DY = -3;
    /** 干燥踏板（脚位层 y=0，与水池顶层同高）。 */
    private static final int PAD_MIN_DX = 3;
    private static final int PAD_MAX_DX = 8;
    private static final int PAD_DY = -1;

    /** 规划器被测几何（相对 ORIGIN；y 偏移 0 = 脚位层 / 石箱顶面同层）。 */
    private static final int FROM_DX = 1;
    private static final int MID_DX = 2;
    private static final int TO_DX = 3;
    private static final int GEOMETRY_DZ = 0;

    /** 场景空气检查/清理范围（相对 ORIGIN）。 */
    private static final int REGION_MIN_DX = -BOX_HALF;
    private static final int REGION_MAX_DX = PAD_MAX_DX;
    private static final int REGION_MIN_DZ = -BOX_HALF;
    private static final int REGION_MAX_DZ = BOX_HALF;
    private static final int REGION_MIN_DY = -5;
    private static final int REGION_MAX_DY = 4;

    private static final int BUDGET_TICKS = 300;
    /** 每个用例施加后的**固定**静置 tick（让物理把 `onGround` 之类落定）。 */
    private static final int SETTLE_TICKS = 3;
    /** 等 `onGround` 变真的上限（坑底那一档）。 */
    private static final int GROUND_WAIT_CAP = 40;
    /** 估计值与 vanilla 真值的相对容差（同一公式，只差 float/double 精度）。 */
    private static final double REL_TOL = 1.0E-4D;
    /** 成本差额的绝对容差（走路格）。 */
    private static final double COST_TOL = 0.05D;

    /**
     * 一个测量用例。
     *
     * @param id          名字（日志/断言里用）
     * @param water       水池内部是否灌满水
     * @param dx/dy/dz    bot 的脚位（相对 ORIGIN，浮点 = 格中心）
     * @param expectGround 该用例期望「在地面」为真
     * @param pinAirborne 测量前是否**人工置位** `onGround=false`（空中那一档：物理上确实是离地，
     *                    但置位可让它与"前一用例残留的标志"无关 ⇒ 确定性；自然读数另记 `natural`）
     */
    private record CaseSpec(String id, boolean water, double dx, double dy, double dz,
                            boolean expectGround, boolean pinAirborne, boolean aliceDeviation) {
    }

    private static final List<CaseSpec> CASES = List.of(
            // 干地 + 在地面（基线：惩罚 = 1）
            new CaseSpec("DRY_GROUND", false, 1.5D, 0.0D, 0.5D, true, false, false),
            // 水面顶层：脚位是水、头位是空气 ⇒ 眼**不在**水里（视 pose），且浮着 ⇒ 离地
            new CaseSpec("WATER_SURFACE", true, 1.5D, 0.0D, 0.5D, false, false, false),
            // 坑底站立：眼在水里 + 在地面 ⇒ ×5
            new CaseSpec("SUBMERGED_GROUND", true, 1.5D, (double) PIT_BOTTOM_DY, 0.5D, true, false, false),
            // 水柱中段：眼在水里 + 无支撑 ⇒ ×25（= 水下挖矿/落水挖方块的真实状态）
            new CaseSpec("SUBMERGED_FLOAT", true, 1.5D, -1.0D, 0.5D, false, true, false),
            // 空中：眼不在水里 + 离地（脚下也是空气）⇒ ×5
            new CaseSpec("DRY_AIRBORNE", false, 5.5D, 2.0D, 0.5D, false, true, false),
            // ⭐ **CORE 回归那一幕**：站在石头上，但 `onGround` 标志位是陈旧的 false
            //（传送到场景起点那一 tick 就是这样）⇒ Alice 必须**按几何事实**不罚（×1），
            // 而 vanilla 会按标志位罚 ×5 —— 本用例是**故意偏离 vanilla** 的那一档。
            new CaseSpec("DRY_STALE_FLAG", false, 1.5D, 0.0D, 0.5D, true, true, true));

    private enum Phase { SETUP, RUN, ASSERT, DONE }

    /** 一次测量的全部读数（`null` 语义用布尔 + `NaN` 表达）。 */
    private record Reading(String id, double estimate, double vanillaTicks, boolean eyeInWater,
                           boolean onGround, boolean naturalOnGround, boolean aquaAffinity,
                           double eyeY, String pose, boolean edgeFound, double edgeCost,
                           double edgeBase) {
        double breakTerm() {
            return (estimate + CostModel.BREAK_PENALTY_TICKS) / CostModel.WALK_ONE_BLOCK_TICKS;
        }
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    /** 场景内所有动过的格子（收尾按此清回空气）。 */
    private final Map<BlockPos, BlockState> probe = new LinkedHashMap<>();
    /** 规划器被测几何的三格（相对 ORIGIN；y 偏移 0 = 脚位层 = 石箱顶面同层）。 */
    private final BlockPos FROM = ORIGIN.offset(FROM_DX, 0, GEOMETRY_DZ);
    private final BlockPos MID = ORIGIN.offset(MID_DX, 0, GEOMETRY_DZ);
    private final BlockPos TO = ORIGIN.offset(TO_DX, 0, GEOMETRY_DZ);

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int checks;
    private int caseIndex;
    private int settleLeft = -1;
    private int settleUsed;
    private int preExistingNonAir = -1;
    private int builtBlocks;
    private String setupFailure = "";
    private BlockPos entryFoot;

    private final List<Reading> readings = new ArrayList<>();

    public MiningWaterBreakCostCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MiningWaterBreakCostCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(mid());
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
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks + "，阶段=" + phase
                    + "，用例=" + caseIndex + "）", false);
            phase = Phase.ASSERT;
        }
        return switch (phase) {
            case SETUP -> setup();
            case RUN -> run();
            case ASSERT -> assertAll();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== SETUP ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        forceload(level, true);

        preExistingNonAir = 0;
        for (BlockPos pos : region()) {
            if (!level.getBlockState(pos).isAir()) {
                preExistingNonAir++;
            }
        }
        if (preExistingNonAir != 0) {
            setupFailure = "SCENE_NOT_AIR(场景区域本来有 " + preExistingNonAir + " 个非空气方块"
                    + " ⇒ 前一次的收尾没跑完，或场景与别的场景相连)";
            check("硬前提失败：" + setupFailure + " ⇒ 本步其余判据都不成立", false);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }

        // ① 环形石箱（5×5×5）
        for (int dx = -BOX_HALF; dx <= BOX_HALF; dx++) {
            for (int dz = -BOX_HALF; dz <= BOX_HALF; dz++) {
                for (int dy = BOX_BOTTOM_DY; dy <= 0; dy++) {
                    set(level, dx, dy, dz, Blocks.STONE);
                }
            }
        }
        // ② 干燥踏板（脚位层与水池顶层同高）
        for (int dx = PAD_MIN_DX; dx <= PAD_MAX_DX; dx++) {
            for (int dz = -BOX_HALF; dz <= BOX_HALF; dz++) {
                set(level, dx, PAD_DY, dz, Blocks.STONE);
            }
        }
        // ③ 水池内部：先按 DRY 档建（下面用例会逐个重设）
        applyInterior(level, false);
        builtBlocks = probe.size();

        // ④ 几何自断言（红了 = 场景没建对，不是缺陷证据）
        check("前提：脚位格可站（from=" + FROM.toShortString() + "）", MovementHelper.canWalkOn(level, FROM));
        check("前提：目标格可站（to=" + TO.toShortString() + "）", MovementHelper.canWalkOn(level, TO));
        check("前提：头位可穿（to.above=" + TO.above().toShortString() + "）",
                MovementHelper.canWalkThrough(level, TO.above()));
        check("前提：中间列**确实是石头**（mid=" + MID.toShortString() + "）",
                level.getBlockState(MID).is(Blocks.STONE));
        check("前提：中间列有地板（D-379 闸门：mid.below=" + MID.below().toShortString() + "）",
                !level.getBlockState(MID.below()).isAir());

        // ⑤ 工具：夹具给镐 + 把最佳工具放进**主手**（否则 estimate 用最佳工具、vanilla 用主手 ⇒ 两者不可比）
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        BlockInteraction.switchToBestToolFor(bot, MID);
        boolean mainHandIsPickaxe = bot.getMainHandItem().is(ItemTags.PICKAXES);
        check("前提：主手是镐（估计器用最佳工具、vanilla 用主手 ⇒ 两边必须同一件工具）", mainHandIsPickaxe);

        // ⑥ 传送到起点（收尾复位用）
        entryFoot = bot.blockPosition().immutable();

        BotLog.info("[MiningWaterCost] CHECK setup origin={} 建方块={} 原本非空气={} setupFailure={}"
                        + " from={} mid={} to={}",
                ORIGIN.toShortString(), builtBlocks, preExistingNonAir,
                setupFailure.isEmpty() ? "-" : setupFailure, FROM.toShortString(),
                MID.toShortString(), TO.toShortString());
        if (!setupFailure.isEmpty()) {
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        phase = Phase.RUN;
        return Task.Status.RUNNING;
    }

    // ==================== RUN ====================

    private Task.Status run() {
        ServerLevel level = bot.serverLevel();
        if (caseIndex >= CASES.size()) {
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        CaseSpec spec = CASES.get(caseIndex);
        if (settleLeft < 0) {
            applyCase(level, spec);
            settleLeft = SETTLE_TICKS;
            settleUsed = 0;
            return Task.Status.RUNNING;
        }
        keepAlive();
        if (settleLeft > 0) {
            settleLeft--;
            settleUsed++;
            return Task.Status.RUNNING;
        }
        // 坑底那一档要等物理把 onGround 落成真（上限 GROUND_WAIT_CAP）
        if (spec.expectGround() && !bot.onGround() && settleUsed < GROUND_WAIT_CAP) {
            settleUsed++;
            return Task.Status.RUNNING;
        }
        measure(spec);
        caseIndex++;
        settleLeft = -1;
        return Task.Status.RUNNING;
    }

    /** 每个用例都从"干净的世界 + 统一的落点"开始（不依赖上一用例的残留）。 */
    private void applyCase(ServerLevel level, CaseSpec spec) {
        applyInterior(level, spec.water());
        bot.teleportTo(level, ORIGIN.getX() + spec.dx(), ORIGIN.getY() + spec.dy(),
                ORIGIN.getZ() + spec.dz(), Set.of(), bot.getYRot(), 0.0F);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        keepAlive();
    }

    private void applyInterior(ServerLevel level, boolean water) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = PIT_BOTTOM_DY; dy <= 0; dy++) {
                    if (water) {
                        set(level, dx, dy, dz, Blocks.WATER);
                    } else if (dy <= -1) {
                        set(level, dx, dy, dz, Blocks.STONE);   // 干档：下面实心（脚位层留空气）
                    } else {
                        set(level, dx, dy, dz, Blocks.AIR);
                    }
                }
            }
        }
    }

    private void keepAlive() {
        // 假人在水里会掉空气 ⇒ 保满，避免维生层插进来（本夹具要的是**静止状态**，不是逃生）
        bot.setAirSupply(300);
        bot.controller().stopMovement();
    }

    // ==================== MEASURE ====================

    private void measure(CaseSpec spec) {
        ServerLevel level = bot.serverLevel();
        boolean naturalOnGround = bot.onGround();
        if (spec.pinAirborne()) {
            bot.setOnGround(false);
        }
        boolean eyeInWater = bot.isEyeInFluid(FluidTags.WATER);
        boolean onGround = bot.onGround();
        boolean aqua = EnchantmentHelper.hasAquaAffinity(bot);
        double eyeY = bot.getEyeY();

        // 只读自证：测量前后这几格必须一模一样（成本层不许写世界）
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos pos : readOnlyCells()) {
            before.put(pos.immutable(), level.getBlockState(pos));
        }
        BlockState targetState = before.get(MID);
        double estimate = BlockInteraction.estimateBreakTicks(bot, level, MID);
        float progress = targetState.getDestroyProgress(bot, level, MID);
        double vanillaTicks = progress <= 0.0F ? Double.POSITIVE_INFINITY : 1.0D / (double) progress;

        // 规划器：同一几何的 BREAK_AND_TRAVERSE 边（只读枚举，不执行）
        PathRequest request = PathRequest.withWorldModification(
                bot.getUUID().toString(), FROM, TO, "collect-drops");
        MovementContext context = MovementContext.live(bot, level, request);
        double edgeBase = context.cost(MovementType.TRAVERSE, FROM, MID)
                + context.cost(MovementType.TRAVERSE, MID, TO);
        List<PlannedMovement> out = new ArrayList<>();
        new SurfaceMovementProvider().appendCandidates(context, FROM, out);
        PlannedMovement edge = null;
        for (PlannedMovement movement : out) {
            if (movement.movementType() == MovementType.BREAK_AND_TRAVERSE
                    && movement.fromFoot().equals(FROM) && movement.toFoot().equals(TO)) {
                edge = movement;
            }
        }

        // 只读自证（续）：数改动
        int changed = 0;
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                changed++;
            }
        }
        readings.add(new Reading(spec.id(), estimate, vanillaTicks, eyeInWater, onGround,
                naturalOnGround, aqua, eyeY, bot.getPose().name(),
                edge != null, edge == null ? Double.NaN : edge.cost(), edgeBase));
        check("用例 " + spec.id() + "：测量是**只读**的（改动 " + changed + " 格）", changed == 0);
        BotLog.info("[MiningWaterCost] CASE {} eyeInWater={} onGround={}(natural={}) aqua={} eyeY={}"
                        + " pose={} est={} vanilla={} edge={} cost={} base={}",
                spec.id(), eyeInWater, onGround, naturalOnGround, aqua, fmt(eyeY), bot.getPose().name(),
                fmt(estimate), fmt(vanillaTicks), edge != null, fmt(edge == null ? Double.NaN : edge.cost()),
                fmt(edgeBase));
    }

    /** 测量前要快照的格子（只读自证的样本）。 */
    private List<BlockPos> readOnlyCells() {
        return List.of(MID, FROM, TO, TO.above(), MID.below());
    }

    // ==================== ASSERT ====================

    private Task.Status assertAll() {
        if (!setupFailure.isEmpty()) {
            cleanup();
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }
        if (readings.size() != CASES.size()) {
            check("夹具护栏：" + CASES.size() + " 个用例的读数都要有（实际 " + readings.size() + "）", false);
            cleanup();
            phase = Phase.DONE;
            return Task.Status.RUNNING;
        }
        Reading dry = reading("DRY_GROUND");
        Reading surface = reading("WATER_SURFACE");
        Reading submergedGround = reading("SUBMERGED_GROUND");
        Reading submergedFloat = reading("SUBMERGED_FLOAT");
        Reading airborne = reading("DRY_AIRBORNE");
        Reading staleFlag = reading("DRY_STALE_FLAG");

        // ---- ① 场景前提：四个 (眼在水里, 在地面) 组合真的造出来了 ----
        check("前提：DRY_GROUND 的 (眼在水里, 在地面) = (否,是)（实际 " + flags(dry) + "）",
                !dry.eyeInWater() && dry.onGround());
        check("前提：SUBMERGED_GROUND 的 (眼在水里, 在地面) = (是,是)（实际 " + flags(submergedGround) + "）",
                submergedGround.eyeInWater() && submergedGround.onGround());
        check("前提：SUBMERGED_FLOAT 的 (眼在水里, 在地面) = (是,否)（实际 " + flags(submergedFloat) + "）",
                submergedFloat.eyeInWater() && !submergedFloat.onGround());
        check("前提：DRY_AIRBORNE 的 (眼在水里, 在地面) = (否,否)（实际 " + flags(airborne) + "）",
                !airborne.eyeInWater() && !airborne.onGround());
        check("前提：四个用例里**没有**潮涌能量/水下速掘（Aqua Affinity）——否则惩罚本就不该生效"
                        + "（实际 aqua=" + dry.aquaAffinity() + "/" + surface.aquaAffinity() + "/"
                        + submergedGround.aquaAffinity() + "/" + airborne.aquaAffinity() + "）",
                !dry.aquaAffinity() && !surface.aquaAffinity()
                        && !submergedGround.aquaAffinity() && !airborne.aquaAffinity());
        check("前提：WATER_SURFACE 是「浮着」的（在地面=false；实际 " + flags(surface) + "）",
                !surface.onGround());
        check("前提：人工置位的两档自然读数也是离地（SUBMERGED_FLOAT natural=" + submergedFloat.naturalOnGround()
                        + "、DRY_AIRBORNE natural=" + airborne.naturalOnGround() + "）——"
                        + "红了说明置位掩盖了真实状态，本夹具的人工痕迹需要重新设计",
                !submergedFloat.naturalOnGround() && !airborne.naturalOnGround());

        // ---- ② 场景前提：vanilla 自己在四档上给出的倍数 = 1 / 5 / 25 / 5 ----
        //      （这是**量出来的**，不是假设：`1.0F/getDestroyProgress` 就是执行侧每 tick 的进度倒数）
        double baseVanilla = dry.vanillaTicks();
        check("前提：vanilla 在 DRY_GROUND 上不给惩罚（倍数 1，实测 " + fmt(dry.vanillaTicks()) + " tick）",
                nearRatio(dry.vanillaTicks() / baseVanilla, 1.0D));
        check("前提：vanilla 在 SUBMERGED_GROUND（眼在水里 + 在地面）上就是 ×5（实测倍数 "
                        + fmt(submergedGround.vanillaTicks() / baseVanilla) + "）",
                nearRatio(submergedGround.vanillaTicks() / baseVanilla, 5.0D));
        check("前提：vanilla 在 SUBMERGED_FLOAT（眼在水里 + 离地）上就是 ×25（实测倍数 "
                        + fmt(submergedFloat.vanillaTicks() / baseVanilla) + "）",
                nearRatio(submergedFloat.vanillaTicks() / baseVanilla, 25.0D));
        check("前提：vanilla 在 DRY_AIRBORNE（眼不在水里 + 离地）上就是 ×5（实测倍数 "
                        + fmt(airborne.vanillaTicks() / baseVanilla) + "）",
                nearRatio(airborne.vanillaTicks() / baseVanilla, 5.0D));
        check("前提：vanilla 在 DRY_STALE_FLAG（标志位 false 但**脚下是石头**）上照样罚 ×5（实测倍数 "
                        + fmt(staleFlag.vanillaTicks() / baseVanilla) + "）——"
                        + "这正是 Alice **故意不跟**的那一档（不跟的理由 = CORE `break_course` 的真机回归）",
                nearRatio(staleFlag.vanillaTicks() / baseVanilla, 5.0D));

        // ---- ③ ⭐ 被测判据：估计值 == 执行侧真值（逐用例） ----
        //      ⚠️ 例外：`DRY_STALE_FLAG` 是**故意偏离**的那一档（Alice 按几何事实、vanilla 按标志位）
        for (Reading reading : readings) {
            CaseSpec spec = spec(reading.id());
            double expected = spec.aliceDeviation()
                    ? reading.vanillaTicks() / 5.0D     // 故意不收那一项 ⇒ 期望 = vanilla / 5
                    : reading.vanillaTicks();
            check("⭐ " + reading.id() + "：estimateBreakTicks == "
                            + (spec.aliceDeviation() ? "vanilla/5（**故意偏离**：按脚下几何事实、不按陈旧标志位）"
                            : "1.0F/getDestroyProgress")
                            + "（估计 " + fmt(reading.estimate()) + " tick vs 期望 " + fmt(expected)
                            + " tick，vanilla 真值 " + fmt(reading.vanillaTicks()) + " tick，倍率 "
                            + fmt(reading.estimate() / baseVanilla) + "）——"
                            + "`D-385` 修复前只等于「陆地 + 在地面」那一档",
                    relative(reading.estimate(), expected) <= REL_TOL);
        }
        // ⭐ 偏离档的**机制**（CORE `break_course` 那一幕）：站在石头上 + 陈旧 false ⇒ 必须等于基线
        check("⭐ DRY_STALE_FLAG：站在石头上但 `onGround` 是陈旧 false ⇒ 成本必须**等于** DRY_GROUND 基线"
                        + "（" + fmt(staleFlag.estimate()) + " vs " + fmt(dry.estimate()) + "）——"
                        + "按 `onGround()` 单判会让 CORE `break_course` 从「破墙过去」翻成「搭柱翻墙」",
                relative(staleFlag.estimate(), dry.estimate()) <= REL_TOL);
        check("前提：DRY_STALE_FLAG 的自然标志位是**在地面**（人工置位才造出陈旧 false；natural="
                        + staleFlag.naturalOnGround() + "）",
                staleFlag.naturalOnGround() && !staleFlag.onGround());

        // ---- ④ ⭐ 生产后果（一）：生产边生成器在**同一几何**上把惩罚算进破坏项 ----
        //      期望值锚在 **vanilla 真值**（= 执行侧每 tick 进度）上，不锚在 `estimateBreakTicks` 上 ——
        //      否则两边一起变小、这条判据永远绿（自洽≠正确）。红对照实测：撤掉惩罚 ⇒ 期望 3.750 实测 0.000。
        double expectedDelta = (surface.vanillaTicks() - dry.vanillaTicks())
                / CostModel.WALK_ONE_BLOCK_TICKS;
        double actualDelta = (surface.edgeCost() - dry.edgeCost())
                - (surface.edgeBase() - dry.edgeBase());
        check("前提：两种状态下这条边都生成（dry=" + dry.edgeFound() + " cost=" + fmt(dry.edgeCost())
                        + "；wet=" + surface.edgeFound() + " cost=" + fmt(surface.edgeCost()) + "）",
                dry.edgeFound() && surface.edgeFound());
        check("前提：两种状态下的**非破坏部分**相同（dry base=" + fmt(dry.edgeBase())
                        + " vs wet base=" + fmt(surface.edgeBase()) + "）——"
                        + "不同则说明「水里」这一变化影响了别的成本项，差额就不能只归因破坏",
                Math.abs(dry.edgeBase() - surface.edgeBase()) <= 1.0E-9D);
        check("前提：边的成本 = 非破坏部分 + (est + " + fmt(CostModel.BREAK_PENALTY_TICKS)
                        + ")/" + fmt(CostModel.WALK_ONE_BLOCK_TICKS) + "（干 " + fmt(dry.edgeCost())
                        + " vs " + fmt(dry.edgeBase() + dry.breakTerm()) + "；湿 " + fmt(surface.edgeCost())
                        + " vs " + fmt(surface.edgeBase() + surface.breakTerm()) + "）——"
                        + "这一条绿说明边生成器用的就是 `estimateBreakTicks`（唯一来源）",
                Math.abs(dry.edgeCost() - (dry.edgeBase() + dry.breakTerm())) <= COST_TOL
                        && Math.abs(surface.edgeCost()
                        - (surface.edgeBase() + surface.breakTerm())) <= COST_TOL);
        check("⭐ 规划器：浮在水面时这条边的破坏项比陆地贵 (vanilla_湿 − vanilla_干)/"
                        + fmt(CostModel.WALK_ONE_BLOCK_TICKS) + " = " + fmt(expectedDelta)
                        + " 走路格（实测 " + fmt(actualDelta) + "）——"
                        + "期望值锚在 vanilla 真值上 ⇒ `D-385` 修复前这里是 0.000",
                Math.abs(actualDelta - expectedDelta) <= COST_TOL);

        // ---- ⑤ ⭐ 生产后果（二）：代价结论 = 规划器自然偏向放置 ----
        double dryCost = dry.breakTerm();
        double wetCost = surface.breakTerm();
        double deepCost = submergedFloat.breakTerm();
        check("⭐ 陆地：挖一块石头仍比放一块便宜（" + fmt(dryCost) + " < "
                        + fmt(CostModel.PLACE_ONE_BLOCK_COST) + "）——修复不许把陆地挖掘也搞贵",
                dryCost < CostModel.PLACE_ONE_BLOCK_COST);
        check("⭐ 水里（浮在水面）：挖一块石头已比放一块贵（" + fmt(wetCost) + " > "
                        + fmt(CostModel.PLACE_ONE_BLOCK_COST) + "）⇒ 规划器**自然**偏向放置",
                wetCost > CostModel.PLACE_ONE_BLOCK_COST);
        check("⭐ 水柱中段（眼在水里 + 离地）：挖的代价 ×25 ⇒ " + fmt(deepCost) + " > "
                        + fmt(CostModel.PLACE_ONE_BLOCK_COST),
                deepCost > CostModel.PLACE_ONE_BLOCK_COST);

        findings.add("dry=" + fmt(dry.estimate()) + " surface=" + fmt(surface.estimate())
                + " subGround=" + fmt(submergedGround.estimate())
                + " subFloat=" + fmt(submergedFloat.estimate())
                + " airborne=" + fmt(airborne.estimate()));

        cleanup();
        phase = Phase.DONE;
        return Task.Status.RUNNING;
    }

    // ==================== 收尾 ====================

    private void cleanup() {
        ServerLevel level = bot.serverLevel();
        for (BlockPos pos : probe.keySet()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            bot.teleportTo(level, entryFoot.getX() + 0.5D, entryFoot.getY(), entryFoot.getZ() + 0.5D,
                    Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(Vec3.ZERO);
        }
        FixtureToolKit.resetInventory(bot);
        int left = remainingNonAir(level);
        check("收尾：场景区域清回空气（残留非空气=" + left + "）", left == 0);

        boolean pass = failures.isEmpty();
        BotLog.info("[MiningWaterCost] SUMMARY checks={} failures={} 建方块={} 原本非空气={}"
                        + " 读数=[{}] → {}｜失败项：{}",
                checks, failures.size(), builtBlocks, preExistingNonAir, String.join(" ", findings),
                pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(
                    "[alice] 水中/离地挖掘成本取证 " + (pass ? "PASS" : "FAIL")
                            + "（详见日志 [MiningWaterCost]）"));
        }
    }

    // ==================== 世界读写与工具 ====================

    private BlockPos mid() {
        return MID;
    }

    private void set(ServerLevel level, int dx, int dy, int dz, net.minecraft.world.level.block.Block block) {
        BlockPos pos = ORIGIN.offset(dx, dy, dz);
        level.setBlock(pos, block.defaultBlockState(), 3);
        probe.put(pos, level.getBlockState(pos));
    }

    private List<BlockPos> region() {
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = REGION_MIN_DX; dx <= REGION_MAX_DX; dx++) {
            for (int dz = REGION_MIN_DZ; dz <= REGION_MAX_DZ; dz++) {
                for (int dy = REGION_MIN_DY; dy <= REGION_MAX_DY; dy++) {
                    cells.add(ORIGIN.offset(dx, dy, dz));
                }
            }
        }
        return cells;
    }

    private int remainingNonAir(ServerLevel level) {
        int left = 0;
        for (BlockPos pos : region()) {
            if (!level.getBlockState(pos).isAir()) {
                left++;
            }
        }
        return left;
    }

    private void forceload(ServerLevel level, boolean on) {
        for (int cx = (ORIGIN.getX() + REGION_MIN_DX) >> 4; cx <= (ORIGIN.getX() + REGION_MAX_DX) >> 4; cx++) {
            for (int cz = (ORIGIN.getZ() + REGION_MIN_DZ) >> 4;
                 cz <= (ORIGIN.getZ() + REGION_MAX_DZ) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private static CaseSpec spec(String id) {
        for (CaseSpec spec : CASES) {
            if (spec.id().equals(id)) {
                return spec;
            }
        }
        throw new IllegalStateException("缺少用例定义：" + id);
    }

    private Reading reading(String id) {
        for (Reading reading : readings) {
            if (reading.id().equals(id)) {
                return reading;
            }
        }
        throw new IllegalStateException("缺少用例读数：" + id);
    }

    private static String flags(Reading reading) {
        return (reading.eyeInWater() ? "是" : "否") + "," + (reading.onGround() ? "是" : "否");
    }

    private static double relative(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || b == 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(a - b) / Math.abs(b);
    }

    private static boolean nearRatio(double actual, double expected) {
        return Math.abs(actual - expected) / expected <= 0.02D;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
