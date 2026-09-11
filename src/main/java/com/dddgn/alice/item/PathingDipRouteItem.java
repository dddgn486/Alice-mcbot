package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
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
 * <p>场景：x=-1..2、z=65 是一条 1 格深的坑。两条候选路线（由本地模拟器穷举验证，
 * 并与真实规划器在旧场景上的输出一致）：
 * <ul>
 *   <li>旧模型：`DESCEND + ASCEND + DIAGONAL`（下降进坑再绕），cost=4.41，实测≈34 tick；</li>
 *   <li>标定后：`TRAVERSE + DIAGONAL + DIAGONAL + TRAVERSE`（西侧绕路），cost=4.66，实测≈28 tick。</li>
 * </ul>
 * 所以标定后规划器应改选**更快的绕路**（`first=TRAVERSE`），这就是本次验收的判别信号。
 */
public class PathingDipRouteItem extends Item {

    /** 与 {@code dip_course.mcfunction} 一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(-1, 64, 63);

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

        BlockPos startFoot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, COURSE_GOAL_FOOT, "item:pathing-dip-route");
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
