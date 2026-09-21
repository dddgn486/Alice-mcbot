package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.BreakEnterHeadBlockedCheckTask;
import com.dddgn.alice.task.CleanupWrappedTask;
import com.dddgn.alice.task.EdgeCompletenessCheckTask;
import com.dddgn.alice.task.ContrastTimerCheckTask;
import com.dddgn.alice.task.FallDiagnosticTask;
import com.dddgn.alice.task.PlaceStepDiagonalCheckTask;
import com.dddgn.alice.task.PillarDiagnosticTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **移动/寻路模块（R-2：第一个"带场景"的模块）**：`fall_execute` + `pillar_execute` + `contrast_timer`。
 *
 * <p>为什么用这三步当**带场景模块**的样板：它们各自需要一块专用地形（落差 / 竖井 / 停表场景 ✓），
 * 正好检验编排器 v1 的"**先热区块、再跑场景**"顺序 ✓ —— 旧电池是"场景→provision"✗，
 * 区块冷时 `/fill` 不落地 ⇒ 判据在虚空里假绿 ✗（`single:craft_table` 单跑必红就是这个坑 ✓）。
 */
public final class PathingModule implements CheckModule {

    @Override
    public String id() {
        return "pathing";
    }

    @Override
    public String title() {
        return "移动执行（落差 / 竖井 / 停表）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        return List.of(
                // D-374（2026-09-21）：⭐ **脚位空、头位实**的目的地必须有入边（规划级；四用例互为对照）。
                // MAIN：便宜（无场景文件、无执行、4 次 1 格远的规划）且守的是**内核图完整性**不变式 ⇒ 进 CORE。
                CheckStep.of("break_enter_head_blocked", CheckProfile.MAIN, List.of(), null,
                        () -> new BreakEnterHeadBlockedCheckTask(bot, ctx.observer()), 120),
                // ⭐⭐ `G2`（2026-09-21，D-374 事故的结构性补救）：**边集完备性差集** ——
                // 局部谓词（canTraverse/canAscend/canDescend/可破入）给出的「应有边」 vs
                // `SurfaceMovementProvider.appendCandidates` 实际产出的边。零搜索、确定性、覆盖每格×每方向。
                // EXTRA：会自建并还原一整个 13×3×13 场景（约 5 000 次 setBlock），不进 CORE。
                CheckStep.of("edge_completeness", CheckProfile.EXTRA, List.of(), null,
                        () -> new EdgeCompletenessCheckTask(bot, ctx.observer()), 200),
                // D-336（2026-09-19）：**斜向上升那一格**（规划级）—— 能力 + 信封两用例，互为反证。
                CheckStep.of("place_step_diagonal", CheckProfile.EXTRA, List.of(), null,
                        () -> new PlaceStepDiagonalCheckTask(bot, ctx.observer()), 400),
                CheckStep.of("fall_execute", CheckProfile.MAIN,
                        List.of("alice_test:fall_course_terrain"), null,
                        () -> new CleanupWrappedTask(new FallDiagnosticTask(bot, ctx.observer()), bot), 600),
                CheckStep.of("pillar_execute", CheckProfile.MAIN,
                        List.of("alice_test:pillar_course_terrain"), null,
                        () -> new CleanupWrappedTask(new PillarDiagnosticTask(bot, ctx.observer()), bot), 900),
                CheckStep.of("contrast_timer", CheckProfile.EXTRA,
                        List.of("alice_test:ore_course_terrain"),
                        () -> teleportTo(bot, new BlockPos(56, 63, 132)),
                        () -> new ContrastTimerCheckTask(bot, ctx.observer()), 200));
    }

    /** 与电池 `teleportBot` **逐字段一致** ✓（模块不能调它的私有方法 ✗ ⇒ 这里复制同一套 ✓）。 */
    private static void teleportTo(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
