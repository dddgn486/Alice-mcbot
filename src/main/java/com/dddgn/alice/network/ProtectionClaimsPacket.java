package com.dddgn.alice.network;

import com.dddgn.alice.protection.ProtectionClaimService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：**认领元数据快照**（D-314，保护区 2/2）—— 某维度当前被认领的区块坐标集合。
 *
 * <p>⚠️ 包里**只有区块坐标**：没有地形、没有颜色、没有"这块地是谁的"。地形由客户端从自己的
 * {@code ClientLevel} 采样（`D-307` 事实 1/2）。{@code truncated=true} 表示"服务端还有更多、
 * 但只发了离你最近的一批"（见 {@link ProtectionClaimService#selectNearest}）——
 * 界面必须**说出来**，不能假装那就是全部。
 *
 * <p>{@code externalChunkKeys}（D-316）= **外部认领源**（例如 FTB Chunks）在玩家窗口内的认领：
 * 与本地认领**分开传**，界面才能给它们不同的标记（"这块地是谁的"要看得出来），
 * 而门禁那一侧两者都算保护区。
 */
public record ProtectionClaimsPacket(ResourceLocation dimension, boolean truncated, long[] chunkKeys,
                                     long[] externalChunkKeys) {

    /** 只要本地认领时用它（外部数组为空）；快照组装用 {@link #withExternal}。 */
    public ProtectionClaimsPacket(ResourceLocation dimension, boolean truncated, long[] chunkKeys) {
        this(dimension, truncated, chunkKeys, new long[0]);
    }

    /** 同一份本地认领 + 一组外部认领（D-316，例如 FTB Chunks 的窗口内认领）。 */
    public ProtectionClaimsPacket withExternal(long[] externalKeys) {
        return new ProtectionClaimsPacket(dimension, truncated, chunkKeys,
                externalKeys == null ? new long[0] : externalKeys);
    }

    public static void encode(ProtectionClaimsPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.dimension);
        buf.writeBoolean(packet.truncated);
        writeKeys(buf, packet.chunkKeys);
        writeKeys(buf, packet.externalChunkKeys);
    }

    private static void writeKeys(FriendlyByteBuf buf, long[] keys) {
        long[] safe = keys == null ? new long[0] : keys;
        buf.writeVarInt(safe.length);
        for (long key : safe) {
            buf.writeLong(key);
        }
    }

    /**
     * S2C 解码对**数量撒谎**是宽容的（夹住，不抛）：服务端是我们自己的，协议漂移时宁可少画几格，
     * 也不该把客户端一脚踢下线（与本项目既有包 `RoadPlanPacket` 同一口径）。
     * 反方向的 C2S（{@link ProtectionActionPacket}）则**必须拒收** —— 客户端不可信。
     */
    public static ProtectionClaimsPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        boolean truncated = buf.readBoolean();
        return new ProtectionClaimsPacket(dimension, truncated, readKeys(buf), readKeys(buf));
    }

    private static long[] readKeys(FriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readVarInt(), ProtectionClaimService.MAX_SNAPSHOT_CHUNKS));
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            keys[i] = buf.readLong();
        }
        return keys;
    }

    public static void handle(ProtectionClaimsPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.dddgn.alice.client.ClientProtectionState.accept(packet)));
        context.setPacketHandled(true);
    }
}
