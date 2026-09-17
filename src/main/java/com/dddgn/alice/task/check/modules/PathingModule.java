package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.CleanupWrappedTask;
import com.dddgn.alice.task.ContrastTimerCheckTask;
import com.dddgn.alice.task.FallDiagnosticTask;
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
