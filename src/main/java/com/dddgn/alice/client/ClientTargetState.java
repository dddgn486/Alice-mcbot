package com.dddgn.alice.client;

import com.dddgn.alice.network.TargetPacket;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端任务目标状态(网络线程 → 主线程 via enqueueWork,渲染线程只读)。
 * <p>渲染器 {@code TargetOutlineRenderer} 每帧读取,画透视高亮线框。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientTargetState {

    private static volatile boolean active;
    private static volatile int type;      // 0=方块 1=实体
    private static volatile BlockPos blockPos;
    private static volatile int entityId = -1;

    private ClientTargetState() {
    }

    public static void update(TargetPacket packet) {
        active = packet.active();
        type = packet.type();
        blockPos = packet.blockPos();
        entityId = packet.entityId();
    }

    public static boolean isActive() {
        return active;
    }

    /** 0=方块目标,1=实体目标。 */
    public static int type() {
        return type;
    }

    public static BlockPos blockPos() {
        return blockPos;
    }

    public static int entityId() {
        return entityId;
    }
}
