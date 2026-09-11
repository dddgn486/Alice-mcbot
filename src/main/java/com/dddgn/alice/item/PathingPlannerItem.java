package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;

/**
 * R3 规划内核测试工具「寻路规划器」（{@code alice:pathing_planner}）。
 *
 * <p><b>零参数交互</b>：
 * <ul>
 *   <li><b>右键方块</b>：让最近 bot 规划到「该方块正上方」的脚位（即站上去）；</li>
 *   <li><b>Shift+右键方块</b>：规划到「该方块本身」的脚位（用于点击空气/精确脚位）。</li>
 * </ul>
 *
 * <p>只做规划与报告：<b>不执行、不移动 bot、不修改世界</b>。
 */
public class PathingPlannerItem extends Item {

    private static final int CHAT_STEP_LIMIT = 8;

    public PathingPlannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        BlockPos clicked = context.getClickedPos();
        boolean exactFoot = player != null && player.isShiftKeyDown();
        BlockPos goalFoot = exactFoot ? clicked : clicked.above();

        ServerLevel serverLevel = (ServerLevel) level;
        BotPlayer bot = BotManager.firstInLevel(serverLevel);
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] 当前维度没有 bot，先 /alice spawn"));
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos startFoot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();
        PathPlan plan = new CorePathPlanner().planTo(bot, serverLevel, bot.getUUID().toString(),
                startFoot, goalFoot, "item:pathing-planner");

        BotLog.info("[R3 Plan] {} bot={} from={} to={} mode={}", plan.summary(),
                bot.getName().getString(), startFoot.toShortString(), goalFoot.toShortString(),
                exactFoot ? "exact" : "above");
        for (int i = 0; i < plan.movements().size(); i++) {
            PlannedMovement movement = plan.movements().get(i);
            BotLog.info("[R3 Plan] step={} type={} from={} to={} cost={}",
                    i, movement.movementType(), movement.fromFoot().toShortString(),
                    movement.toFoot().toShortString(),
                    String.format(java.util.Locale.ROOT, "%.3f", movement.cost()));
        }

        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] R3 规划 " + plan.status()
                    + " 步数=" + plan.movements().size()
                    + " 成本=" + (Double.isInfinite(plan.totalCost()) ? "INF"
                    : String.format(java.util.Locale.ROOT, "%.2f", plan.totalCost()))
                    + " 节点=" + plan.nodesExpanded()
                    + " 用时=" + plan.elapsedMillis() + "ms"
                    + " | bot=" + startFoot.toShortString()
                    + " → " + goalFoot.toShortString()));
            int shown = Math.min(plan.movements().size(), CHAT_STEP_LIMIT);
            for (int i = 0; i < shown; i++) {
                PlannedMovement movement = plan.movements().get(i);
                player.sendSystemMessage(Component.literal("[alice] " + (i + 1) + ". "
                        + movement.movementType() + " " + movement.fromFoot().toShortString()
                        + " → " + movement.toFoot().toShortString()));
            }
            if (plan.movements().size() > shown) {
                player.sendSystemMessage(Component.literal("[alice] ... 其余 "
                        + (plan.movements().size() - shown) + " 步见日志"));
            }
            if (!plan.reached()) {
                player.sendSystemMessage(Component.literal("[alice] " + plan.diagnostics()));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
