package com.dddgn.alice.item;

import com.dddgn.alice.task.mining.MiningSceneFixture;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** 场景 A 一键启动器：右键启动；Shift+右键只清除会话和高亮。 */
public final class MiningSceneTester extends Item {
    public MiningSceneTester(Properties properties) {
        super(properties);
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
            MiningSceneFixture.clear(serverPlayer);
        } else {
            MiningSceneFixture.startSceneA(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
