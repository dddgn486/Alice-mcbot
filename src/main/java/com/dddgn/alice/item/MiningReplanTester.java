package com.dddgn.alice.item;

import com.dddgn.alice.task.mining.MiningReplanFixture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** 开发期挖掘重规划夹具：Shift+右键选障碍，右键目标启动，Shift+右键已选障碍清理。 */
public final class MiningReplanTester extends Item {
    public MiningReplanTester(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player.isShiftKeyDown()
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            MiningReplanFixture.clear(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        Player player = context.getPlayer();
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (serverPlayer.isShiftKeyDown()) {
            MiningReplanFixture.selectObstacle(serverPlayer,
                    context.getClickedPos().relative(context.getClickedFace()));
        } else {
            MiningReplanFixture.start(serverPlayer, context.getClickedPos());
        }
        return InteractionResult.SUCCESS;
    }
}
