package com.dddgn.alice.client;

import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.PermissionAnswerPacket;
import com.dddgn.alice.network.PermissionNoticePacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * **请示快捷键**（S3c / D-142）：游戏内鼠标被锁定，HUD 按钮点不到 ⇒ 用键位答复。
 *
 * <p>键位（可在"选项 → 控制"里改）：允许 = `Y`，拒绝 = `N`（{@link KeyConflictContext#IN_GAME}：
 * 只在游戏内生效，打开界面时不会误触）。按下即对**最新一条**请示发
 * {@link PermissionAnswerPacket}（scope = ONCE，与聊天按钮等价）。
 *
 * <p>为什么不用鼠标点聊天：聊天里的按钮**本来就是可点击的**（服务端推 `ClickEvent.runCommand`），
 * 那条路不需要客户端做任何事；本类补的是"不想开聊天也能一键答复"。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT)
public final class PermissionKeys {

    public static final String CATEGORY = "key.categories.alice";

    private static KeyMapping allowKey;
    private static KeyMapping denyKey;

    private PermissionKeys() {
    }

    /** 注册键位（Forge 的 mod 事件总线）。 */
    @Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        private Registrar() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            allowKey = new KeyMapping("key.alice.permission_allow", KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);
            denyKey = new KeyMapping("key.alice.permission_deny", KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);
            event.register(allowKey);
            event.register(denyKey);
        }
    }

    /** 每 tick 消费一次按键（`consumeClick` 是"按下一次答复一次"的语义）。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;   // 开着界面时不管（避免在聊天里打字误触）
        }
        while (allowKey != null && allowKey.consumeClick()) {
            answer("allow");
        }
        while (denyKey != null && denyKey.consumeClick()) {
            answer("deny");
        }
    }

    private static void answer(String option) {
        PermissionNoticePacket notice = ClientPermissionState.current();
        if (notice == null) {
            return;   // 没有未决请示：按键什么都不做
        }
        AliceNetwork.CHANNEL.sendToServer(new PermissionAnswerPacket(notice.id(), option, "ONCE"));
        ClientPermissionState.clear(notice.id());
    }
}
