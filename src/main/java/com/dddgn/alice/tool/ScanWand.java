package com.dddgn.alice.tool;

import com.dddgn.alice.capability.InterfaceScanner;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 测试工具「扫描铲」(开发期便利,等效 {@code /alice scan}):
 * 手持<b>钻石铲</b>对机器方块右键 → 触发一次服务端接口扫描,结果写日志。
 * <p>
 * 会 cancel 掉右键默认行为(防止打开机器 GUI / 把泥土铲成土径)。
 * 限定服务端处理,客户端仅保留动作动画。</p>
 */
public final class ScanWand {

    private ScanWand() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide() != LogicalSide.SERVER) {
            return;
        }
        ItemStack stack = event.getEntity().getMainHandItem();
        if (!stack.is(Items.DIAMOND_SHOVEL)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        event.setCanceled(true); // 阻止默认行为:开 GUI / 铲土径

        if (level.getBlockEntity(pos) == null) {
            BotLog.info("[alice] 钻石铲扫描: 目标方块无方块实体 @ {}", pos.toShortString());
            event.getEntity().displayClientMessage(Component.literal("[alice] 无接口(无方块实体)"), false);
            return;
        }
        String result = InterfaceScanner.scan(level, pos);
        BotLog.info("[alice] 接口扫描(钻石铲):\n{}", result);
        String blockKey = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()).toString();
        event.getEntity().displayClientMessage(
                Component.literal("[alice] 已扫描 " + blockKey + " → 见日志"), false);
    }
}
