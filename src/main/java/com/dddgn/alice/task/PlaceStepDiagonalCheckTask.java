package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ **斜向上升那一格**（`D-336` / `survey/22 §4.3`）—— 电池步 `place_step_diagonal`（EXTRA，**规划级**）。
 *
 * <p><b>缺口是什么</b>：`PLACE_STEP_AND_TRAVERSE` 原先只有 `dy ∈ {0, -1}`（`SurfaceMovementProvider:72-76`）
 * ⇒ 平走、下 1 格都有，**只有"斜上方"没有**；而 `PILLAR` 只解决纯垂直 ⇒ "斜向上升"此前**无路可走**。
 *
 * <p><b>两用例（互为反证）</b>：
 * <ol>
 *   <li>{@code ASCENT}（能力）：把目标放在**斜上方 1 格**且**该格下方是空气**（放台阶前站不住）——
 *       修好后必须 `REACHED` 且**恰好 1 条** `PLACE_STEP_AND_TRAVERSE`，且 `Δy = +1`；</li>
 *   <li>{@code ENVELOPE}（信封）：**同一对起终点**改用纯通行请求（`PathRequest.of`）⇒
 *       必须**不可达**且**不含**该类型边 —— 证明新边**没有渗透进"纯通行"信封**
 *       （`D-334`：`changesWorld()` 是分档唯一静态口径，动 `ASCEND` 会打穿它，本边不动它）。</li>
 * </ol>
 *
 * <p><b>几何为什么用超平坦世界就够</b>：地表支撑在 `y-1`，其上方全为空气 ⇒
 * `to`（斜上一格）= 空气、`target = to.below()`（要放台阶的那一格）= 空气且**下面是地面**（有放置面、可放）；
 * 而 `to` 的支撑恰好是空的 ⇒ `ASCEND`（要求目标可站）必然不可用 ⇒ 唯一走法就是"放一格台阶再斜向上踩"。
 *
 * <p><b>只做规划断言、不做执行</b>：`PLACE_STEP_AND_TRAVERSE` 的执行路径已由 `pathing` 步的场景覆盖；
 * 本步只补"**这一档几何能不能被规划出来、且在不在信封内**"两件确定性事实。
 */
public final class PlaceStepDiagonalCheckTask implements Task {

    /** 专用孤立点（远离其它场景与基准）。 */
    private static final BlockPos START = new BlockPos(3000, -60, 3800);

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 300;

    private enum Case { ASCENT, MINING, ENVELOPE }

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
    private BlockPos goal;

    public PlaceStepDiagonalCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PlaceStepDiagonalCheck";
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
            // 脚位**从 bot 实际位置取**（D-328 附注的教训：坐标假设必须自带判据）
            start = bot.blockPosition();
            ServerLevel level = bot.serverLevel();
            check("前提：起点脚下必须有支撑（脚位=" + start.toShortString() + " 脚下="
                            + level.getBlockState(start.below()).getBlock().getName().getString() + "）",
                    !level.getBlockState(start.below()).isAir());
            FixtureToolKit.ensureHotbarTool(bot, () -> new ItemStack(Items.COBBLESTONE, 64),
                    stack -> stack.is(Items.COBBLESTONE), "cobblestone");
            // ⚠️ 用**正向**（+1,+1,0）而不是对角（+1,+1,+1）：`CARDINAL` 只有 4 个正向 ⇒
            // 对角目标需要"上台阶 + 斜走"两条边（第一版就是这么量的，`movements=2`）。
            goal = start.offset(1, 1, 0);
            // 前提判据：目标下方必须是空气（否则那不是"需要放台阶"的斜向上升）
            check("前提：斜上方目标的**下方是空气**（target=" + goal.below().toShortString() + " state="
                            + level.getBlockState(goal.below()).getBlock().getName().getString() + "）",
                    level.getBlockState(goal.below()).isAir());
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
        // ⚠️ requester **不能**用 "pathing"：`WritePolicyMatrix` 里 `(EXTERNAL, PATHING)` 那行
        // **不含 `WITH_WORLD_MODIFICATION`**（P-02 的口径）⇒ 世界修改请求会被**规划期拒掉**（`status=ERROR`）。
        // 本门禁要验的是"这条边能不能规划出来"，所以用**有权写世界**的那一行（`collect-drops` = P-03/GATHERING，
        // 行内授权 `{OF, PURE_TRAVERSAL, WITH_WORLD_MODIFICATION, CLIMB_APPROACH}`）。
        String requester = "collect-drops";
        PathRequest base = switch (which) {
            // ① 有权写世界的通用信封（P-03）
            case ASCENT -> PathRequest.withWorldModification(bot.getUUID().toString(), start, goal, requester);
            // ② ⭐ **挖矿信封**（`miningApproach`）：`D-366b` 起它**允许** PILLAR/FALL/DOWNWARD
            //    （用户 2026-09-20 临时放开）⇒ 这条几何现在**应当可达**（方式不再限定为 PLACE_STEP）。
            case MINING -> PathRequest.miningApproach(bot.getUUID().toString(), start, goal, "mine-plan");
            // ③ 纯通行信封（反证：新边不许渗透进来）
            case ENVELOPE -> PathRequest.of(bot.getUUID().toString(), start, goal, requester);
        };
        PathRequest request = new PathRequest(base.botId(), start, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                base.requester());
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
        int placeStep = 0;
        int placeStepUp = 0;
        for (PlannedMovement m : plan.movements()) {
            if (m.movementType() == com.dddgn.alice.pathing.core.MovementType.PLACE_STEP_AND_TRAVERSE) {
                placeStep++;
                if (m.toFoot().getY() - m.fromFoot().getY() == 1) {
                    placeStepUp++;
                }
            }
        }
        StringBuilder chain = new StringBuilder();
        for (PlannedMovement m : plan.movements()) {
            chain.append('[').append(m.movementType()).append(' ').append(m.fromFoot().toShortString())
                    .append("→").append(m.toFoot().toShortString()).append("] ");
        }
        findings.add(which + ":chain=" + chain + "status=" + plan.status() + " reached=" + plan.reached()
                + " movements=" + plan.movements().size() + " placeStep=" + placeStep
                + " placeStepUp=" + placeStepUp + " ms=" + plan.elapsedMillis());
        BotLog.info("[PlaceStepDiag] case={} status={} reached={} movements={} placeStep={} placeStepUp={} ms={} diag={}",
                which, plan.status(), plan.reached(), plan.movements().size(), placeStep, placeStepUp,
                plan.elapsedMillis(), plan.diagnostics());
        if (which == Case.ASCENT || which == Case.MINING) {
            check((which == Case.ASCENT ? "① 通用信封" : "② 挖矿信封（D-366b 放开垂直能力）")
                            + "：斜向上升必须可规划：REACHED（status=" + plan.status() + "）", plan.reached());
            // ⭐⭐ D-366 不变量（**这是放开信封的安全网**）：搜索产出的每一步都必须能构造出执行端契约
            // (`MovementSpec`) —— 2026-09-20 真机崩服就是"搜索产出的 dy=+1 边执行端构造不出"。
            String violation = contractViolation(plan);
            check("搜索产出的每一步都能构造出执行端 `MovementSpec`（不抛）"
                            + (violation.isEmpty() ? "" : "：违反=" + violation), violation.isEmpty());
            check("⭐ 不再产出执行端不接受的 `PLACE_STEP_AND_TRAVERSE dy=+1`"
                            + "（placeStepUp=" + placeStepUp + "；D-366 撤销 D-336 的生成侧）", placeStepUp == 0);
        } else {
            check("② 信封：纯通行请求下同一起终点必须**不可达**（status=" + plan.status() + "）", !plan.reached());
            check("② 信封：纯通行请求里**不许**出现世界修改类边（placeStep=" + placeStep + "）", placeStep == 0);
        }
    }

    /**
     * `D-366` 不变量：把计划里的**每一步**都真的构造一次执行端 `MovementSpec`。
     *
     * <p>返回空串 = 全部通过；否则返回第一条违反的 `type from→to` + 异常摘要。
     * **这正是 2026-09-20 崩服那一步**（`PathSession.startSegment` → `PlannedMovementSpecs.toSpec`）。
     */
    private static String contractViolation(com.dddgn.alice.pathing.core.search.PathPlan plan) {
        for (com.dddgn.alice.pathing.core.search.PlannedMovement movement : plan.movements()) {
            try {
                com.dddgn.alice.pathing.core.search.PlannedMovementSpecs.toSpec(movement,
                        java.util.List.of("session_segment", "target_support", "target_body_clear",
                                "target_head_clear"));
            } catch (RuntimeException exception) {
                return movement.movementType() + " " + movement.fromFoot().toShortString() + "→"
                        + movement.toFoot().toShortString() + " :: " + exception.getMessage();
            }
        }
        return "";
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
        boolean pass = failures.isEmpty();
        BotLog.info("[PlaceStepDiag] SUMMARY checks={} failures={} → {}｜{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", findings);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 斜向上升门禁 "
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
