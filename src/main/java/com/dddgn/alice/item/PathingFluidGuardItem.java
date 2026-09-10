package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 内核对齐验证：流体屏障检查器（{@code alice:pathing_fluid_guard}）：场景专属，右键即启动。
 *
 * <p>用于 `alice_test:fluid_course`（2 格高充水墙）与 `alice_test:lava_course`（封闭岩浆池）：
 * 规划允许世界修改（可破坏/可放置），但**含流体状态的方块不可挖**、**不可往流体里放置**
 * （对照 Baritone {@code MovementHelper.getMiningDurationTicks:588-590}）。
 * 期望 {@code UNREACHABLE}；若为 {@code REACHED} 说明规划器把流体当成了可挖阻挡物。
 */
public class PathingFluidGuardItem extends Item {

    /** 与场景函数一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(4, 64, 66);

    public PathingFluidGuardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return start(context.getPlayer(), (ServerLevel) level);
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private InteractionResult start(Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, COURSE_START_FOOT);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        bot.teleportTo(level, COURSE_START_FOOT.getX() + 0.5D, COURSE_START_FOOT.getY(),
                COURSE_START_FOOT.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();

        BlockPos startFoot = bot.blockPosition().immutable();
        PathRequest request = PathRequest.withWorldModification(
                bot.getUUID().toString(), startFoot, COURSE_GOAL_FOOT, "item:pathing-fluid-guard");
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        boolean pass = !plan.reached();
        BotLog.info("[FluidGuard] status={} movements={} from={} to={} result={}",
                plan.status(), plan.movements().size(), startFoot.toShortString(),
                COURSE_GOAL_FOOT.toShortString(), pass ? "PASS" : "FAIL");
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] 流体屏障规划 status=" + plan.status()
                    + " movements=" + plan.movements().size()
                    + (pass ? " → PASS（未把含流体方块当可挖阻挡物）"
                            : " → FAIL（规划穿过了流体，见日志 [FluidGuard]）")));
        }
        return InteractionResult.SUCCESS;
    }
}
