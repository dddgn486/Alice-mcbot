package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
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
 * 内核对齐验证：站立判定检查器（{@code alice:pathing_fence_guard}）：场景专属，右键即启动。
 *
 * <p>场景：起点与目标之间有一道横跨平台的栅栏墙。
 * 修复前 `canWalkOn` 只看"碰撞非空"，会把栅栏顶当作可站面 → 规划 ASCEND 跨栏 → `REACHED`；
 * 修复后（D-041 混合判定）栅栏不可站且不可穿 → `UNREACHABLE`。
 */
public class PathingFenceGuardItem extends Item {

    /** 与 {@code fence_course.mcfunction} 一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 48);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(0, 64, 44);

    public PathingFenceGuardItem(Properties properties) {
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

        BlockPos startFoot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, COURSE_GOAL_FOOT, "item:pathing-fence-guard");
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        boolean pass = !plan.reached();
        BotLog.info("[FenceGuard] status={} first={} movements={} from={} to={} result={}",
                plan.status(), first, plan.movements().size(), startFoot.toShortString(),
                COURSE_GOAL_FOOT.toShortString(), pass ? "PASS" : "FAIL");
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] 站立判定 status=" + plan.status()
                    + " movements=" + plan.movements().size()
                    + (pass ? " → PASS（栅栏未被当成支撑面）"
                            : " → FAIL（规划跨过了栅栏，见日志 [FenceGuard]）")));
        }
        return InteractionResult.SUCCESS;
    }
}
