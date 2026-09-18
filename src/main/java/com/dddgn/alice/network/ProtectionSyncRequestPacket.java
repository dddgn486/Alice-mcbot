package com.dddgn.alice.network;

import com.dddgn.alice.protection.ProtectionClaimService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：**请求一次认领快照**（D-314，空包）。
 *
 * <p>为什么需要它（`D-307` 只写了"一个批量动作包"，这里是**有意的补充**，已在 `D-314` 登记）：
 * 界面是靠"右键物品"打开的，而"物品的 use 到达服务端"这件事在**方块交互抢先**（箱子/门/工作台…）
 * 时不会发生 ⇒ 会出现"界面开着、快照永远不来"。那种状态下如果还允许右键，用户就会在**空网格**上
 * 盲点右键 = 盲取消认领（**破坏性**，把真认领的区块取消了）。
 *
 * <p>⇒ 两道保险：① 界面在**没有本维度快照**时**拒绝编辑**（只显示"等待服务端数据"）；
 * ② 界面每 20 tick 用本包自愈请求一次；服务端限流（{@link ProtectionClaimService#SYNC_MIN_INTERVAL_TICKS}）。
 */
public record ProtectionSyncRequestPacket() {

    public static void encode(ProtectionSyncRequestPacket packet, FriendlyByteBuf buf) {
        // 空包：没有字段
    }

    public static ProtectionSyncRequestPacket decode(FriendlyByteBuf buf) {
        return new ProtectionSyncRequestPacket();
    }

    public static void handle(ProtectionSyncRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer actor = context.getSender();
                if (actor != null) {
                    ProtectionClaimService.handleSyncRequest(actor);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
