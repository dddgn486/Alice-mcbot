package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
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
 * Q7 验收：路线偏好检查器（{@code alice:pathing_dip_route}）：场景专属，右键即启动。
 *
 * <p>场景：起点到目标之间有一个 1 格深坑。两条候选路线：
 * <ul>
 *   <li>路线 A：下降进坑再上升出来（2 步，实测约 26 tick）；</li>
 *   <li>路线 B：同层绕 4 格（4 步，实测约 24 tick）。</li>
 * </ul>
 * 成本模型按真实耗时标定后，规划器应选**路线 B**（`first=TRAVERSE movements=4`）。
 * 标定前选路线 A（`first=DESCEND movements=2`）——这就是本次验收的判别信号。
 */
public class PathingDipRouteItem extends Item {

    /** 与 {@code dip_course.mcfunction} 一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(0, 64, 64);

    public PathingDipRouteItem(Properties properties) {
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
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, COURSE_GOAL_FOOT);
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        boolean pass = plan.reached() && MovementType.TRAVERSE.name().equals(first);
        BotLog.info("[DipRoute] status={} first={} movements={} cost={} from={} to={} result={}",
                plan.status(), first, plan.movements().size(),
                String.format(java.util.Locale.ROOT, "%.2f", plan.totalCost()),
                startFoot.toShortString(), COURSE_GOAL_FOOT.toShortString(), pass ? "PASS" : "BASELINE/FAIL");
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] 路线偏好 first=" + first
                    + " movements=" + plan.movements().size()
                    + " cost=" + String.format(java.util.Locale.ROOT, "%.2f", plan.totalCost())
                    + (pass ? " → PASS（选了更快的绕路）"
                            : " → 基线（标定前预期 DESCEND；对齐后应为 TRAVERSE movements=4）")));
        }
        return InteractionResult.SUCCESS;
    }
}
