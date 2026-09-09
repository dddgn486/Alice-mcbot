package com.dddgn.alice.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 区域伐木规划器（{@code alice:lumber_planner}）——**已禁用**。
 *
 * <p>原因（D-073）：伐木任务与 `BotMiner` 深度耦合、代码混乱，用户裁定**推迟并立专项**；
 * `BotMiner` 已随挖掘迁移删除，伐木待专项重写后重新启用。
 */
public class LumberPlanner extends Item {

    public LumberPlanner(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return notifyDisabled(context.getPlayer());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        notifyDisabled(player);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private static InteractionResult notifyDisabled(Player player) {
        if (player != null && !player.level().isClientSide) {
            player.sendSystemMessage(Component.literal(
                    "[alice] 区域伐木已禁用（D-073：伐木待专项迁移重写）"));
        }
        return InteractionResult.SUCCESS;
    }
}
