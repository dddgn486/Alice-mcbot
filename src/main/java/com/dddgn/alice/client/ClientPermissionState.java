package com.dddgn.alice.client;

import com.dddgn.alice.network.PermissionNoticePacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端**请示卡片**状态（S3b / D-141）：**只做一格界面**（够测试），不做对话/管理主界面。
 *
 * <p>收到 {@link PermissionNoticePacket} 就存下来并倒计时；按钮点击由
 * {@code PermissionHudRenderer} 处理（发 {@code PermissionAnswerPacket}）。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT)
public final class ClientPermissionState {

    /** 只保留**最新一条**（一格界面；真正的排队/多卡片留给以后）。 */
    private static PermissionNoticePacket current;
    private static int remainingTicks;

    private ClientPermissionState() {
    }

    public static void accept(PermissionNoticePacket packet) {
        if (!packet.active()) {
            if (current != null && current.id().equals(packet.id())) {
                current = null;
                remainingTicks = 0;
            }
            return;
        }
        current = packet;
        remainingTicks = packet.deadlineInTicks();
    }

    public static PermissionNoticePacket current() {
        return current;
    }

    public static int remainingTicks() {
        return remainingTicks;
    }

    public static void clear(String id) {
        if (current != null && current.id().equals(id)) {
            current = null;
            remainingTicks = 0;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (current == null) {
            return;
        }
        if (--remainingTicks <= 0) {
            // 超时由服务端落档（这里只把卡片撤掉，避免"显示着但已过期"）
            current = null;
        }
    }

    /** 仅供渲染统计（测试用）。 */
    public static String describe() {
        return current == null ? "-" : current.id() + "/" + current.capability()
                + " 剩余=" + remainingTicks + "tick";
    }
}
