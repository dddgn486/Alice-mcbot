package com.dddgn.alice.client;

import com.dddgn.alice.network.MiningReplanFixturePacket;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 客户端只读的挖掘重规划测试高亮状态。 */
@OnlyIn(Dist.CLIENT)
public final class ClientMiningReplanState {
    private static volatile boolean active;
    private static volatile BlockPos target;
    private static volatile BlockPos obstacle;
    private static volatile BlockPos botStart;
    private static volatile String phase = "IDLE";

    private ClientMiningReplanState() {
    }

    public static void update(MiningReplanFixturePacket packet) {
        active = packet.active();
        target = packet.target() == null ? null : packet.target().immutable();
        obstacle = packet.obstacle() == null ? null : packet.obstacle().immutable();
        botStart = packet.botStart() == null ? null : packet.botStart().immutable();
        phase = packet.phase();
    }

    public static boolean isActive() { return active; }
    public static BlockPos target() { return target; }
    public static BlockPos obstacle() { return obstacle; }
    public static BlockPos botStart() { return botStart; }
    public static String phase() { return phase; }
}
