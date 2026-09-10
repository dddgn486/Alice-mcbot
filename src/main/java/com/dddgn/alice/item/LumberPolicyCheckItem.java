package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.policy.NearestExposedPolicy;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.LumberCourseAnchor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 决策缝自检（{@code alice:lumber_policy_check}，切片 J3）：普通右键，零参数。
 *
 * <p>证明 L3 的**策略是可替换的决策缝**，而不是写死的 if
 * （{@code docs/JOB_LAYER_DESIGN.md} §6.3）：同一场景、同一候选集合，
 * {@link NearestPolicy} 与 {@link NearestExposedPolicy} 必须给出**不同且都可解释**的选择。
 *
 * <p>**只做规划，不执行、不派任务**（复用挖掘回归里 PLAN 型用例的做法）——
 * 因此没有任何副作用，也不会干扰其它测试。夹具职责同 {@code LumberJobItem}：
 * 把 bot 传送到场景起点（场景是孤立平台，不传送则所有候选都不可达）。
 */
public class LumberPolicyCheckItem extends Item {

    public LumberPolicyCheckItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        check(context.getPlayer(), (ServerLevel) level);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        check(player, (ServerLevel) level);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private void check(net.minecraft.world.entity.player.Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, LumberCourseAnchor.START_FOOT);
        }
        if (bot == null) {
            say(player, "[alice] bot 生成失败，请检查日志");
            return;
        }
        // 夹具职责：放进场景起点（与 lumber_job 同口径）
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();

        GoalSpec spec = GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 3, 2400);
        CandidateSet set = new LumberCandidateSource().candidates(bot, spec);

        BotLog.info("[PolicyCheck] candidates={} rejected={}", set.viable().size(), set.rejected());
        for (Candidate candidate : set.viable()) {
            BotLog.info("[PolicyCheck] candidate {} logs={} height={} visible={} exposed={} open_sky={} d={}",
                    candidate.id(), candidate.feature("logs"), candidate.feature("height"),
                    candidate.feature("visible"), candidate.feature("exposed"),
                    candidate.feature("open_sky"), candidate.feature("d"));
        }

        SelectionPolicy nearestPolicy = new NearestPolicy();
        SelectionPolicy exposedPolicy = new NearestExposedPolicy();
        Selection nearest = nearestPolicy.select(bot, spec, set);
        Selection exposed = exposedPolicy.select(bot, spec, set);

        BotLog.info("[PolicyCheck] nearest picked={} reason={} rejected={}",
                id(nearest.picked()), nearest.reason(), nearest.rejected());
        BotLog.info("[PolicyCheck] exposed picked={} reason={} rejected={}",
                id(exposed.picked()), exposed.reason(), exposed.rejected());

        boolean bothPicked = nearest.picked() != null && exposed.picked() != null;
        boolean differ = bothPicked && !nearest.picked().anchor().equals(exposed.picked().anchor());
        // 「可解释」= 两条选择各有非空理由，且两侧的拒绝列表都带理由码（§6.2a）
        boolean explainable = bothPicked
                && !nearest.reason().isBlank() && !exposed.reason().isBlank()
                && !nearest.rejected().isEmpty() && !exposed.rejected().isEmpty();
        // 「暴露策略名副其实」：选中项 exposed=true，或如实回退并写明 no_exposed
        boolean exposedHonest = exposed.picked() != null
                && ("true".equals(exposed.picked().feature("exposed"))
                    || exposed.reason().contains("fallback=no_exposed"));
        boolean pass = differ && explainable && exposedHonest;

        BotLog.info("[PolicyCheck] SUMMARY nearest={} exposed={} differ={} explainable={} "
                        + "exposedHonest={} → {}",
                id(nearest.picked()), id(exposed.picked()), differ, explainable, exposedHonest,
                pass ? "PASS" : "FAIL");
        say(player, "[alice] 策略自检 " + (pass ? "PASS" : "FAIL")
                + "：nearest=" + id(nearest.picked()) + " exposed=" + id(exposed.picked())
                + "（详见 [PolicyCheck] 日志）");
    }

    private static String id(Candidate candidate) {
        return candidate == null ? "-" : candidate.id();
    }

    private static void say(net.minecraft.world.entity.player.Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
