package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐⭐ **脚位空、头位实**的目的地必须有入边（`D-374`）—— 电池步 `break_enter_head_blocked`（MAIN，**规划级**）。
 *
 * <p><b>缺口是什么（2026-09-21 真机实测发现）</b>：`SurfaceMovementProvider.appendBreakAndEnter` 原先只查
 * `canWalkThrough(level, to)`（**单格**谓词，只看躯干）就 `return`；而 TRAVERSE 的准入
 * （`canTraverse → canStandCentered`）要求**头位**也可通行 ⇒ 「脚位空、头位实」的目的地
 * **两条边都不生成**，在整张图里**没有任何入边**（= 一格高夹缝的形状）。
 * 真机代价：掉落物落在 1 格高夹缝 ⇒ 20 000 节点搜爆 ⇒ `SEARCH_LIMIT` ⇒ 拾取退役（用户手挖的那一格
 * = 头位方块 = 缺失的边本该破的东西）。对照 Baritone `MovementTraverse:57`（`positionsToBreak = {to.above(), to}`）
 * 与 `:109-118`（给目的地 `y+1` 单独计价）—— 在 Baritone 里这是**一次正常的 Traverse**。
 *
 * <p><b>四个用例（互为对照，缺一不可）</b>：
 * <ol>
 *   <li>{@code HEAD_BLOCKED}（**能力**）：脚位空 + 头位实 ⇒ 修复后必须 `REACHED`，且**恰好 1 条**
 *       `BREAK_AND_ENTER` 落到该格。因为前提已断言"脚位可通行"，所以唯一的 blocker **只能是头位**
 *       （`collectBlockers` 只收脚位/头位两格，见其 `:69-78`）⇒ 本条即"破的是头位"的证明；</li>
 *   <li>{@code FOOT_BLOCKED}（**行为不得变化**）：脚位也被挡（修复前就会生成）⇒ 仍须 `REACHED` + 有该边；</li>
 *   <li>{@code ENVELOPE}（**D-076 反向对照**）：同一份几何改用**纯通行**请求（`PathRequest.of`）
 *       ⇒ 必须**不可达**且**不含**任何 `BREAK_AND_ENTER` —— 证明新边**没有渗透进纯通行信封**；</li>
 *   <li>{@code UNBREAKABLE}（**诚实失败**）：头位换成**不可破坏**方块 ⇒ 必须**不可达**且不产出该边
 *       —— 不许把"破不动"伪装成"能到"。</li>
 * </ol>
 *
 * <p><b>§6.9.1 三条前提（写下来 + 夹具自断言）</b>：
 * <ol>
 *   <li><b>几何盒</b>：超平坦世界，起点 {@link #START}（孤立点，离其它场景 ≥400 格）；
 *       目的地 = 起点 +X 1 格；只动「目的地躯干」与「目的地上方」两格；用例间先 `restore()` 再摆格子；</li>
 *   <li><b>世界/模组集</b>：只依赖「起点脚下有支撑」（自断言 `canStandCentered(start)`）与
 *       `DIRT` 可破坏 / `BEDROCK` 不可破坏（自断言前提），不依赖任何模组方块；</li>
 *   <li><b>层归属</b>：本步只断言**规划层**（`CorePathPlanner.plan` 的产出）—— 执行层由
 *       `pathing`/`break_enter` 类步覆盖；因此**不去 tick 任何子任务**（无副作用边界问题）。</li>
 * </ol>
 *
 * <p><b>§6.9.3 三问自答</b>：① 断言的是规划层（上游 `WritePolicyMatrix` 用 `collect-drops`
 * = P-03/GATHERING 行，含 `WITH_WORLD_MODIFICATION`，不会被短路 —— 与 `PlaceStepDiagonalCheckTask` 同一口径）；
 * ② 依赖的世界假设（平坦地面 / DIRT 可破 / BEDROCK 不可破）都在 {@code tick()} 里断言；
 * ③ 失败时用户/日志看到 `[BreakEnterHead] SUMMARY … failures=N → FAIL`，判据逐条带用例名。
 *
 * <p><b>K-4 安全网</b>：每个计划的每一步都真的构造一次执行端 `MovementSpec`
 * （`PlannedMovementSpecs.toSpec`）—— 保证新边**可规划即可执行**（2026-09-20 崩服就是这一步抛的）。
 */
public final class BreakEnterHeadBlockedCheckTask implements Task {

    /** 专用孤立点：超平坦世界里的一块空地，离其它场景/孤立点 ≥400 格。 */
    private static final BlockPos START = new BlockPos(3000, -60, 3900);

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 300;

    /**
     * 刻意**小**的搜索预算：几何只有 1 格远，4 000 节点足够找到；而"负例"（信封/不可破坏）
     * 会打满它 ⇒ 夹具本身不会变成一次长搜索（`A1` 每 tick 只放 1 次烧预算搜索，本步每 tick 只跑 1 个用例）。
     */
    private static final SearchBudget BUDGET = SearchBudget.of(4_000, 100L);

    private enum Case { HEAD_BLOCKED, FOOT_BLOCKED, ENVELOPE, UNBREAKABLE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private int ticks;
    private int checks;
    private int prepareStep;
    private int index;
    private boolean done;
    private BlockPos start;
    private BlockPos to;

    public BreakEnterHeadBlockedCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "BreakEnterHeadBlockedCheck";
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
            start = bot.blockPosition();
            to = start.offset(1, 0, 0);
            restore();
            // 前提断言（几何 + 世界假设）：起点可站、目的地脚位空、目的地下方有支撑
            check("前提：起点可站（start=" + start.toShortString() + " canStandCentered="
                            + MovementHelper.canStandCentered(level, start) + "）",
                    MovementHelper.canStandCentered(level, start));
            check("前提：目的地脚位初始为空气（to=" + to.toShortString() + " state="
                            + level.getBlockState(to).getBlock().getName().getString() + "）",
                    MovementHelper.canWalkThrough(level, to));
            check("前提：目的地下方有支撑（canWalkOn=" + MovementHelper.canWalkOn(level, to) + "）",
                    MovementHelper.canWalkOn(level, to));
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
        // 用例间隔离：先清干净，再按用例摆格子（不依赖"上一轮留下的状态"）
        restore();
        switch (which) {
            case HEAD_BLOCKED, ENVELOPE -> level.setBlockAndUpdate(to.above(), Blocks.DIRT.defaultBlockState());
            case FOOT_BLOCKED -> {
                level.setBlockAndUpdate(to, Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(to.above(), Blocks.DIRT.defaultBlockState());
            }
            case UNBREAKABLE -> level.setBlockAndUpdate(to.above(), Blocks.BEDROCK.defaultBlockState());
        }

        // ---- 前提自断言：几何形状必须真的是"本用例要测的那一种" ----
        boolean footPassable = MovementHelper.canWalkThrough(level, to);
        boolean headPassable = MovementHelper.canWalkThrough(level, to.above());
        boolean supported = MovementHelper.canWalkOn(level, to);
        boolean traversable = MovementHelper.canTraverse(level, start, to);
        check(which + " 前提：目的地下方有支撑（canWalkOn=" + supported + "）", supported);
        switch (which) {
            case HEAD_BLOCKED, ENVELOPE -> {
                check(which + " 前提：脚位**可通行**（canWalkThrough(to)=" + footPassable + "）", footPassable);
                check(which + " 前提：头位**被挡**（canWalkThrough(to.above())=" + headPassable + "）", !headPassable);
                // 本条是"这不是普通 TRAVERSE"的证据：TRAVERSE 的准入含头位 ⇒ 必须为 false
                check(which + " 前提：不是一次普通 TRAVERSE（canTraverse=" + traversable + "）", !traversable);
            }
            case FOOT_BLOCKED -> check(which + " 前提：脚位**被挡**（canWalkThrough(to)=" + footPassable + "）",
                    !footPassable);
            case UNBREAKABLE -> check(which + " 前提：脚位可通行而头位是**不可破坏**方块（foot=" + footPassable
                    + " head=" + headPassable + "）", footPassable && !headPassable);
        }

        // `collect-drops` = `WritePolicyMatrix` 的 P-03/GATHERING 行（含 `WITH_WORLD_MODIFICATION`）
        // ⇒ 不会被规划期的授权闸门短路（与 `PlaceStepDiagonalCheckTask` 同一口径）。
        String requester = "collect-drops";
        PathRequest base = which == Case.ENVELOPE
                ? PathRequest.of(bot.getUUID().toString(), start, to, requester)
                : PathRequest.withWorldModification(bot.getUUID().toString(), start, to, requester);
        PathRequest request = new PathRequest(base.botId(), start, base.goal(), base.allowedMovementTypes(),
                BUDGET, base.requester());

        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        int breakEnter = 0;
        StringBuilder chain = new StringBuilder();
        for (PlannedMovement movement : plan.movements()) {
            if (movement.movementType() == MovementType.BREAK_AND_ENTER) {
                breakEnter++;
            }
            chain.append('[').append(movement.movementType()).append(' ').append(movement.fromFoot().toShortString())
                    .append('→').append(movement.toFoot().toShortString()).append("] ");
        }
        String violation = contractViolation(plan);
        findings.add(which + ":chain=" + chain + "status=" + plan.status() + " reached=" + plan.reached()
                + " movements=" + plan.movements().size() + " breakEnter=" + breakEnter
                + " ms=" + plan.elapsedMillis());
        BotLog.info("[BreakEnterHead] case={} status={} reached={} movements={} breakEnter={} ms={} diag={}",
                which, plan.status(), plan.reached(), plan.movements().size(), breakEnter,
                plan.elapsedMillis(), plan.diagnostics());

        switch (which) {
            case HEAD_BLOCKED -> {
                check("① 脚位空 + 头位实：必须可规划（REACHED；status=" + plan.status() + "）", plan.reached());
                check("① 恰好 1 条 BREAK_AND_ENTER（movements=" + plan.movements().size()
                        + " breakEnter=" + breakEnter + "）", breakEnter == 1 && plan.movements().size() == 1);
                check("① 该边落到目的地格 " + to.toShortString() + "（toFoot="
                                + (plan.movements().isEmpty() ? "-" : plan.movements().get(0).toFoot().toShortString())
                                + "）",
                        !plan.movements().isEmpty() && plan.movements().get(0).toFoot().equals(to));
                check("① 每一步都能构造出执行端 MovementSpec（K-4 可规划即可执行）"
                        + (violation.isEmpty() ? "" : "：违反=" + violation), violation.isEmpty());
            }
            case FOOT_BLOCKED -> {
                check("② 脚位也被挡：行为不得变化 —— 仍须 REACHED（status=" + plan.status() + "）", plan.reached());
                check("② 仍须出现 BREAK_AND_ENTER（breakEnter=" + breakEnter + "）", breakEnter == 1);
                check("② 每一步都能构造出执行端 MovementSpec（K-4）"
                        + (violation.isEmpty() ? "" : "：违反=" + violation), violation.isEmpty());
            }
            case ENVELOPE -> {
                check("③ 纯通行信封（PathRequest.of）下同一起终点必须**不可达**（status=" + plan.status() + "）",
                        !plan.reached());
                check("③ 纯通行信封里**不许**出现世界修改类边（breakEnter=" + breakEnter + "）", breakEnter == 0);
            }
            case UNBREAKABLE -> {
                check("④ 头位不可破坏 ⇒ 必须**不可达**（不得把破不动伪装成能到；status=" + plan.status() + "）",
                        !plan.reached());
                check("④ 不可达时**不许**产出 BREAK_AND_ENTER（breakEnter=" + breakEnter + "）", breakEnter == 0);
            }
        }
    }

    /**
     * `K-4` 不变量：把计划里的**每一步**都真的构造一次执行端 `MovementSpec`。
     *
     * <p>返回空串 = 全部通过；否则返回第一条违反的 `type from→to` + 异常摘要。
     * `planningFacts` 四个 token 与 `PathSession.startSegment` 的调用口径一致。
     */
    private static String contractViolation(PathPlan plan) {
        for (PlannedMovement movement : plan.movements()) {
            try {
                PlannedMovementSpecs.toSpec(movement, List.of("session_segment", "target_support",
                        "target_body_clear", "target_head_clear"));
            } catch (RuntimeException exception) {
                return movement.movementType() + " " + movement.fromFoot().toShortString() + "→"
                        + movement.toFoot().toShortString() + " :: " + exception.getMessage();
            }
        }
        return "";
    }

    /** 把本步动过的两格还原成空气（**失败路径也走**，见 `finish()`）。 */
    private void restore() {
        if (to == null) {
            return;
        }
        ServerLevel level = bot.serverLevel();
        if (!level.getBlockState(to).isAir()) {
            level.setBlockAndUpdate(to, Blocks.AIR.defaultBlockState());
        }
        if (!level.getBlockState(to.above()).isAir()) {
            level.setBlockAndUpdate(to.above(), Blocks.AIR.defaultBlockState());
        }
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
        restore();
        // 副作用边界（§6.9.2）：失败路径也要清理，且清理结果**进判据**
        ServerLevel level = bot.serverLevel();
        boolean restored = to != null && level.getBlockState(to).isAir() && level.getBlockState(to.above()).isAir();
        check("清理：本步动过的格子已还原为空气（restored=" + restored + "）", restored);
        boolean pass = failures.isEmpty();
        BotLog.info("[BreakEnterHead] SUMMARY checks={} failures={} restored={} → {}｜{}",
                checks, failures.size(), restored, pass ? "PASS" : "FAIL", findings);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 夹缝格入边门禁 "
                    + (pass ? "PASS" : "FAIL " + failures)));
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
