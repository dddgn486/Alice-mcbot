package com.dddgn.alice.item;

import com.dddgn.alice.protection.ProtectionClaimService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * **保护区地图**（`alice:protection_selector`，D-314）：保护区勾选界面的**零参数游戏内入口**。
 *
 * <p>用法：右键（对着空气或方块都行）⇒ 打开"以玩家为中心的 N×N 区块网格"：
 * 左键 = 认领该区块 / 右键 = 取消认领 / 按住拖动 = 连选 / 关闭界面 = 一次性提交。
 *
 * <p>两侧分工（`D-307`）：
 * <ul>
 *   <li><b>客户端</b>：只负责开界面；认领清单**永远来自服务端下发**，界面从不自己造数据；</li>
 *   <li><b>服务端</b>：顺手回推一次快照（快路径）；即使这一发没到，界面也会用空请求包自愈
 *       （见 {@code ProtectionSyncRequestPacket}）。</li>
 * </ul>
 *
 * <p>⚠️ 这里对两条路径都返回 SUCCESS（消费掉这次点击），避免"右键同时开了箱子"这类歧义；
 * 本物品没有任何其它功能，所以不占用原版语义。客户端类只在 {@link DistExecutor} 的**内层 lambda**
 * 里出现 ⇒ 专用服务端不会去加载 Screen 类。
 */
public class ProtectionSelectorItem extends Item {

    public ProtectionSelectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        trigger(level, player);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        trigger(context.getLevel(), player);
        return InteractionResult.SUCCESS;
    }

    private static void trigger(Level level, Player player) {
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.dddgn.alice.client.ClientProtectionState.openScreen());
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            ProtectionClaimService.pushSnapshot(serverPlayer);
        }
    }
}
