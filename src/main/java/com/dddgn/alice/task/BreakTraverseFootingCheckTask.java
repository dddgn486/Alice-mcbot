package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.BreakAndTraverseExecutionFactory;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.RecoverabilityEvaluator;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PathingStats;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ⭐⭐ **「破坏通行」必须能站得住**（电池步 `break_traverse_footing`；`D-379` 的落地判据，规划级）。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>`BREAK_AND_TRAVERSE` 破掉的是**中间列**，而中间列正是 bot 要**踩过去**的那一格 ——
 * 它必须立得住（脚下有支撑、且不是空洞/水/岩浆）。</b>
 *
 * <h2>真机事故（第八轮，逐字见 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §13.3）</h2>
 * ```
 * 14:21:59.894 走到 632, 64, 95（踩在自己刚放的 632,63,95 上）
 * 14:22:00.044 [WRITE] break 632, 64, 94   ← BREAK_AND_TRAVERSE from=632,64,95 → to=632,64,93 的「中间格」
 * 14:22:00.947 维生监测 hazard=WATER_CONTACT pos=632, 62, 94   ← 掉进水里（掉了约 2 格）
 * 14:22:09→ 沉底 air 298→18 → 14:22:24 LOW_AIR air=-2 ⇒ 掉血 20→10
 * ```
 * 三条事实合起来只有一个解释：**bot 是从「中间列」掉下去的**（落点 `632,62,94` 正是中间列正下方、
 * 水面），也就是说 `632,64,94` 被破掉之后，中间列在**脚位层**留下的是一个**没有地板的洞**
 * （它穿过了 `y=63` 才落到 `y=62` ⇒ `(632,63,94)` 不是实心）。
 *
 * <p><b>缺口在哪</b>：规划侧 `appendBreakAndTraverse` 只查「中间列有阻挡且可破坏」+「`to` 可站」，
 * 执行侧 `BreakAndTraverseExecutionFactory.validate` 同样只查 `to` + 可破坏性 ——
 * **两侧都不查中间列自己有没有地板**。而执行器是**直着走过去**的
 * （`BreakAndTraverseExecution.driveTowardTarget`，位移 2 格；`PlanRouteSafety` 也把
 * `mid`/`mid.above()` 算作「bot 身体会占据的格子」）⇒ 中间列立不住时，
 * 「破坏中间列之后走到 `to`」这个承诺是**假的**。
 *
 * <h2>三个用例（判据 + 反证）</h2>
 * <table border="1">
 *   <tr><th>用例</th><th>中间列脚下的情形</th><th>期望</th></tr>
 *   <tr><td>{@code MID_FLOOR_FLUID}</td><td>中间列方块**悬空**：`mid.below` = 空气、再下一格 = **水**
 *       （= 真机那一步的形状）</td><td>**不许**生成这条边；执行工厂必须以
 *       `BREAK_AND_TRAVERSE_NO_MID_SUPPORT` 拒绝；计数记 `…_fluid`</td></tr>
 *   <tr><td>{@code MID_FLOOR_SHALLOW}</td><td>中间列方块悬空但下面**只有 1 格浅坑**（再下一格是实地）
 *       </td><td>**同样不许**（这是**契约**判据：中间列立不住 ⇒「走到 `to`」不成立，
 *       与"掉下去疼不疼"无关）；计数记 `…_dry`</td></tr>
 *   <tr><td>{@code MID_FLOOR_SOLID}（反证）</td><td>中间列方块**立在地板上**（正常的"破墙过去"）
 *       </td><td>这条边**必须**生成，且执行工厂必须接受 —— 证明修复没有把合法的破坏通行一并禁掉</td></tr>
 * </table>
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * **边生成层**（`SurfaceMovementProvider.appendCandidates` 的产出）+ **执行工厂准入**
 * （`BreakAndTraverseExecutionFactory.validate`）—— 两侧同一个谓词（K-4「可规划即可执行」）。
 * 不 tick 子任务、不执行 ⇒ 零搜索、确定性、毫秒级。
 *
 * <h2>前提自证（红了说明夹具坏，不是缺陷证据）</h2>
 * ① 起点可站、目标格可站（否则这条边本来就因别的原因不生成）；② 中间列**确实有阻挡**（`collectBlockers`
 * 非空）；③ **被测的那个谓词 `canWalkOn(mid)` 在三个用例里刚好是三档**（false / false / true）；
 * ④ 写入预算允许这条边（`writesAllowed`）。
 */
public final class BreakTraverseFootingCheckTask implements Task {

    /** 专用孤立点（超平坦世界里的一块空地，离其它场景 ≥100 格）。 */
    private static final BlockPos START = new BlockPos(3000, -60, 4600);

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 300;
    /** 合成 Movement 用的成本（`PlannedMovement` 需要有限非负）。 */
    private static final double SYNTHETIC_COST = 5.0D;
    /** 归因计数键（与 `SurfaceMovementProvider` 里 `PathingStats` 记的**同名**）。 */
    private static final String KEY_FLUID = "break_traverse_no_mid_support_fluid";
    private static final String KEY_DRY = "break_traverse_no_mid_support_dry";
    /** 执行工厂的拒绝码前缀（带几何，便于真机归因）。 */
    private static final String FACTORY_CODE = "BREAK_AND_TRAVERSE_NO_MID_SUPPORT";

    private enum Case { MID_FLOOR_FLUID, MID_FLOOR_SHALLOW, MID_FLOOR_SOLID }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private int ticks;
    private int checks;
    private int prepareStep;
    private int index;
    private boolean done;

    private BlockPos from;
    private BlockPos mid;
    private BlockPos to;
    /** 本夹具动过的三格**原本**是什么（收尾按原样还原，不是一律写空气）。 */
    private BlockState midOriginal;
    private BlockState midBelowOriginal;
    private BlockState midBelow2Original;

    public BreakTraverseFootingCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "BreakTraverseFootingCheck";
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
            check("规划级门禁必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        if (prepareStep == 0) {
            teleport(bot, START);
            prepareStep = 1;
            return Task.Status.RUNNING;
        }
        if (prepareStep <= SETTLE_TICKS) {
            prepareStep++;
            return Task.Status.RUNNING;
        }
        if (prepareStep == SETTLE_TICKS + 1) {
            ServerLevel level = bot.serverLevel();
            // 脚位**从 bot 实际位置取**（D-328 附注：坐标假设必须自带判据）
            from = bot.blockPosition();
            mid = from.offset(0, 0, 1);
            to = from.offset(0, 0, 2);
            midOriginal = level.getBlockState(mid);
            midBelowOriginal = level.getBlockState(mid.below());
            midBelow2Original = level.getBlockState(mid.below(2));
            check("前提：起点可站（from=" + from.toShortString() + "）", MovementHelper.canStandCentered(level, from));
            check("前提：目标格可站（to=" + to.toShortString() + "）—— 否则这条边本来就因别的原因不生成",
                    MovementHelper.canStandCentered(level, to));
            check("前提：中间列脚下原本是实心（mid.below=" + mid.below().toShortString() + " state="
                            + midBelowOriginal.getBlock().getName().getString() + "）",
                    !midBelowOriginal.isAir());
            prepareStep++;
            return Task.Status.RUNNING;
        }
        if (index >= Case.values().length) {
            return finish();
        }
        runCase(Case.values()[index++]);
        return Task.Status.RUNNING;
    }

    private void runCase(Case which) {
        ServerLevel level = bot.serverLevel();
        // 用例间隔离：先按原样还原，再按用例摆格子（不依赖"上一轮留下的状态"）
        restore();
        // 中间列放一个可破坏方块（D-379 破的就是它）
        level.setBlockAndUpdate(mid, Blocks.STONE.defaultBlockState());
        switch (which) {
            case MID_FLOOR_FLUID -> {
                // 中间列**悬空**，再下一格是水 —— 真机那一步（`632,64,94` 破掉后落进 `632,62,94` 水面）
                level.setBlockAndUpdate(mid.below(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(mid.below(2), Blocks.WATER.defaultBlockState());
            }
            case MID_FLOOR_SHALLOW -> {
                // 中间列悬空但下面只有 1 格浅坑，再下一格是实地
                level.setBlockAndUpdate(mid.below(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(mid.below(2), Blocks.STONE.defaultBlockState());
            }
            case MID_FLOOR_SOLID -> {
                // 中间的方块**立在地板上**（正常的"破墙过去"）—— 用还原后的原状
            }
        }

        // ---- 前提自证：除「中间列立不立得住」外，边生成器的其余闸门全部成立 ----
        boolean fromStandable = MovementHelper.canStandCentered(level, from);
        boolean toStandable = MovementHelper.canStandCentered(level, to);
        boolean midBlocked = !MovementHelper.canWalkThrough(level, mid);
        boolean midFooting = MovementHelper.canWalkOn(level, mid);
        List<BlockPos> blockers = com.dddgn.alice.pathing.core.BreakAndTraverseExecution
                .collectBlockers(level, from, to);
        boolean writesAllowed = MovementContext.live(bot, level,
                PathRequest.withWorldModification(bot.getUUID().toString(), from, to, "collect-drops"))
                .writesAllowed(MovementType.BREAK_AND_TRAVERSE);
        check(which + " 前提：起点可站（" + fromStandable + "）", fromStandable);
        check(which + " 前提：目标格可站（" + toStandable + "）", toStandable);
        check(which + " 前提：中间列**确实有阻挡**（canWalkThrough(mid)=" + !midBlocked
                + " collectBlockers=" + blockers.size() + "）—— 否则失败会归错因", midBlocked && !blockers.isEmpty());
        check(which + " 前提：写入预算允许这条边（writesAllowed=" + writesAllowed + "）", writesAllowed);
        // ⭐ 这就是被测的那个谓词：三档（false/false/true）
        check(which + " 前提：⭐ 中间列落脚（`canWalkOn(mid)`）=" + midFooting + " —— 这就是被测的那个谓词",
                which == Case.MID_FLOOR_SOLID ? midFooting : !midFooting);

        // ---- 生成层：直接问生产用的边生成器 ----
        Map<String, Integer> before = PathingStats.totalsSnapshot();
        SurfaceMovementProvider provider = new SurfaceMovementProvider();
        PathRequest request = PathRequest.withWorldModification(bot.getUUID().toString(), from, to, "collect-drops");
        MovementContext context = MovementContext.live(bot, level, request);
        List<PlannedMovement> out = new ArrayList<>();
        provider.appendCandidates(context, from, out);
        PlannedMovement edge = null;
        for (PlannedMovement movement : out) {
            if (movement.movementType() == MovementType.BREAK_AND_TRAVERSE
                    && movement.fromFoot().equals(from) && movement.toFoot().equals(to)) {
                edge = movement;
            }
        }
        int fluidDelta = delta(before, KEY_FLUID);
        int dryDelta = delta(before, KEY_DRY);

        // ---- 执行层：同一个几何喂给执行工厂（K-4 双向一致） ----
        MovementSpec spec = PlannedMovementSpecs.toSpec(
                edge != null ? edge : new PlannedMovement(MovementType.BREAK_AND_TRAVERSE, from, to,
                        SYNTHETIC_COST, RecoverabilityEvaluator.levelOf(MovementType.BREAK_AND_TRAVERSE)),
                List.of("session_segment", "target_support", "target_body_clear", "target_head_clear"));
        com.dddgn.alice.pathing.core.MovementExecutionFactory.ValidationResult verdict =
                new BreakAndTraverseExecutionFactory().validate(spec,
                        new LiveExecutionContext(bot, level, "break-traverse-footing", 0L,
                                CompletionTolerance.EXACT, "collect-drops"));

        findings.add(which + ":edge=" + (edge != null) + " midFooting=" + midFooting
                + " factoryValid=" + verdict.valid() + " code=" + verdict.failureCode()
                + " fluid=" + fluidDelta + " dry=" + dryDelta);
        BotLog.info("[BreakTraverseFooting] case={} candidates={} edge={} canWalkOn(mid)={} mid={}"
                        + " mid.below={} to={} factoryValid={} code={} fluidDelta={} dryDelta={}",
                which, out.size(), edge != null, midFooting, mid.toShortString(),
                level.getBlockState(mid.below()).getBlock().getName().getString(), to.toShortString(),
                verdict.valid(), verdict.failureCode(), fluidDelta, dryDelta);

        switch (which) {
            case MID_FLOOR_FLUID -> {
                check("⭐ ① 中间列**悬空且落点是水** ⇒ 不许生成这条 BREAK_AND_TRAVERSE 边"
                        + "（D-379 修复前会生成 ⇒ 真机 bot 从中间列掉进水里、沉底、溺水）", edge == null);
                check("⭐ ① 归因计数记 `" + KEY_FLUID + "`（实际 fluidDelta=" + fluidDelta
                        + " dryDelta=" + dryDelta + "）", fluidDelta == 1 && dryDelta == 0);
                check("⭐ ① 执行工厂也必须**拒绝**同一个几何（实际 valid=" + verdict.valid()
                        + " code=" + verdict.failureCode() + "）", !verdict.valid());
                check("⭐ ① 拒绝码必须是 " + FACTORY_CODE + "（实际 " + verdict.failureCode()
                        + "）—— 若因别的原因拒绝，说明本夹具的几何前提没立住",
                        verdict.failureCode() != null && verdict.failureCode().startsWith(FACTORY_CODE));
            }
            case MID_FLOOR_SHALLOW -> {
                check("⭐ ② 中间列**悬空**（哪怕下面只有 1 格浅坑）⇒ 同样不许生成"
                        + "（契约判据：中间列立不住 ⇒「走到 to」不成立，与疼不疼无关）", edge == null);
                check("⭐ ② 归因计数记 `" + KEY_DRY + "`（实际 dryDelta=" + dryDelta
                        + " fluidDelta=" + fluidDelta + "）", dryDelta == 1 && fluidDelta == 0);
                check("② 执行工厂也必须拒绝（实际 valid=" + verdict.valid() + " code="
                        + verdict.failureCode() + "）",
                        !verdict.valid() && verdict.failureCode() != null
                                && verdict.failureCode().startsWith(FACTORY_CODE));
            }
            case MID_FLOOR_SOLID -> {
                check("⭐ ③ 反证：中间列**立在地板上** ⇒ 这条边**必须**生成"
                        + "（修复不许把合法的「破墙过去」一并禁掉）", edge != null);
                check("③ 反证：执行工厂必须**接受**（实际 valid=" + verdict.valid()
                        + " code=" + verdict.failureCode() + "）", verdict.valid());
                check("③ 反证：不该产生本夹具的归因计数（fluidDelta=" + fluidDelta
                        + " dryDelta=" + dryDelta + "）", fluidDelta == 0 && dryDelta == 0);
                check("③ 这条边能构造出执行端 MovementSpec（K-4 可规划即可执行）", spec != null);
            }
        }
    }

    private static int delta(Map<String, Integer> before, String key) {
        return PathingStats.totalsSnapshot().getOrDefault(key, 0) - before.getOrDefault(key, 0);
    }

    // ==================== 收尾 ====================

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        ServerLevel level = bot.serverLevel();
        restore();
        teleport(bot, START);
        bot.controller().stopMovement();

        // 副作用边界（§6.9.2）：失败路径也要清理，且清理结果**进判据**
        boolean restored = Objects.equals(level.getBlockState(mid), midOriginal)
                && Objects.equals(level.getBlockState(mid.below()), midBelowOriginal)
                && Objects.equals(level.getBlockState(mid.below(2)), midBelow2Original);
        check("清理：本夹具动过的三格已按原样还原（restored=" + restored + "）", restored);

        boolean pass = failures.isEmpty();
        BotLog.info("[BreakTraverseFooting] SUMMARY checks={} failures={} 用例={} from={} mid={} to={} → {}"
                        + "｜发现：{}｜失败项：{}",
                checks, failures.size(), Case.values().length,
                from == null ? "-" : from.toShortString(), mid == null ? "-" : mid.toShortString(),
                to == null ? "-" : to.toShortString(), pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 「破坏通行」中间列落脚判据 "
                    + (pass ? "PASS" : "FAIL") + "（3 用例，详见日志 [BreakTraverseFooting]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    /** 把本夹具动过的三格按**原样**还原（失败路径也走）。 */
    private void restore() {
        if (mid == null) {
            return;
        }
        ServerLevel level = bot.serverLevel();
        level.setBlockAndUpdate(mid, midOriginal);
        level.setBlockAndUpdate(mid.below(), midBelowOriginal);
        level.setBlockAndUpdate(mid.below(2), midBelow2Original);
    }

    private void teleport(ServerPlayer who, BlockPos foot) {
        who.teleportTo(who.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), who.getYRot(), who.getXRot());
        who.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (who instanceof BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();
        }
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
