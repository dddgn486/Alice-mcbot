package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.ContainerAccessProfileCheckTask;
import com.dddgn.alice.task.DamageEventVisibilityCheckTask;
import com.dddgn.alice.task.DriverLabelCheckTask;
import com.dddgn.alice.task.MineFailureVisibilityCheckTask;
import com.dddgn.alice.task.OreCourseAnchor;
import com.dddgn.alice.task.RiskProfileCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **决策 / 观测 / 归因模块（R-2）**：任务归因、风险画像冻结、容器访问策略、掉血可见性、挖矿失败可见性。
 *
 * <p>这五步的共同点：都是"**事实能不能被读到**"的判据（归因是否正确 ✓、画像是否按 bot 冻结 ✓、
 * 危险/失败是否进事件环与快照 ✓）⇒ 归为一类 ✓。五步共用 `ore_course_terrain` 场景与同一起点 ✓。
 */
public final class DecisionModule implements CheckModule {

    @Override
    public String id() {
        return "decision";
    }

    @Override
    public String title() {
        return "决策 / 观测 / 归因";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        Runnable toStart = () -> teleportTo(bot, OreCourseAnchor.START_FOOT);
        List<String> scene = List.of("alice_test:ore_course_terrain");
        return List.of(
                CheckStep.of("container_access_profile", CheckProfile.MAIN, scene, toStart,
                        () -> new ContainerAccessProfileCheckTask(bot), 120),
                CheckStep.of("driver_label", CheckProfile.MAIN, scene, toStart,
                        () -> new DriverLabelCheckTask(bot, ctx.observer()), 120),
                CheckStep.of("risk_profile_frozen", CheckProfile.MAIN, scene, toStart,
                        () -> new RiskProfileCheckTask(bot, ctx.observer()), 60),
                CheckStep.of("damage_event_visible", CheckProfile.MAIN, scene, toStart,
                        () -> new DamageEventVisibilityCheckTask(bot, ctx.observer()), 200),
                CheckStep.of("mine_failure_visible", CheckProfile.MAIN, scene, toStart,
                        () -> new MineFailureVisibilityCheckTask(bot, ctx.observer()), 700));
    }

    /** 与电池 `teleportBot` 逐字段一致 ✓（模块不能调它的私有方法 ✗）。 */
    private static void teleportTo(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
