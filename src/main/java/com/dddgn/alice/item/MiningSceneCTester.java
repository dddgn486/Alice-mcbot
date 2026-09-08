package com.dddgn.alice.item;

import com.dddgn.alice.task.mining.MiningReplanFixture;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** 场景 C 启动器：普通右键自动布置动态障碍并启动；Shift+右键清除。 */
public final class MiningSceneCTester extends Item {
    public MiningSceneCTester(Properties properties) {
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
            MiningReplanFixture.clear(serverPlayer);
        } else {
            MiningReplanFixture.startSceneC(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
