package com.dddgn.alice.client.render;

import com.dddgn.alice.client.ClientPermissionState;
import com.dddgn.alice.network.PermissionNoticePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * **请示卡片**（屏幕右侧一格界面，S3b / D-141）：只做这一格，方便测试。
 *
 * <p>画什么：标题 + 请示 id/能力 + 理由 + 两个按钮（选项）+ 倒计时 + 默认档提示。
 * 点什么：按钮矩形内的左键 ⇒ 发 {@link PermissionAnswerPacket}（与服务端 `/alice ask` **等价**）。
 *
 * <p>点击钩子用 {@link InputEvent.MouseButton.Pre}（已用字节码确认：`MouseHandler` 在**无界面**时
 * 也触发 `ForgeHooksClient.onMouseButtonPre`），因此在游戏里直接点卡片即可，无需打开任何界面。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT)
public final class PermissionHudRenderer {

    private static final int PANEL_BG = 0xC0101010;
    private static final int PANEL_BORDER = 0xFFE0C060;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int BUTTON_BG = 0x80305070;
    private static final int BUTTON_HOVER = 0xC04070A0;
    private static final int ALLOW_COLOR = 0xFF80E080;
    private static final int DENY_COLOR = 0xFFE08080;

    private static final int WIDTH = 190;
    private static final int HEIGHT = 74;
    private static final int BUTTON_HEIGHT = 16;
    private static final int MARGIN = 8;

    private PermissionHudRenderer() {
    }

    /** 面板左上角（右侧居中）。 */
    private static int panelX(Minecraft mc) {
        return mc.getWindow().getGuiScaledWidth() - WIDTH - MARGIN;
    }

    private static int panelY(Minecraft mc) {
        return mc.getWindow().getGuiScaledHeight() / 2 - HEIGHT / 2 - 40;
    }

    @SubscribeEvent
    public static void onRender(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        PermissionNoticePacket notice = ClientPermissionState.current();
        if (notice == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int x = panelX(mc);
        int y = panelY(mc);

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, PANEL_BG);
        graphics.fill(x, y, x + WIDTH, y + 1, PANEL_BORDER);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, PANEL_BORDER);
        graphics.fill(x, y, x + 1, y + HEIGHT, PANEL_BORDER);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, PANEL_BORDER);

        graphics.drawString(font, "§e§lAlice 请示 " + notice.id(), x + 6, y + 5, TEXT, false);
        graphics.drawString(font, "§7" + notice.capability(), x + 6, y + 17, TEXT_DIM, false);
        graphics.drawString(font, trim(font, notice.reason(), WIDTH - 12), x + 6, y + 28, TEXT, false);
        int seconds = Math.max(0, ClientPermissionState.remainingTicks() / 20);
        graphics.drawString(font, "§7" + seconds + "s 后按默认「" + notice.defaultOption() + "」处理",
                x + 6, y + 39, TEXT_DIM, false);

        // 提示行（**不做可点击按钮**：游戏内鼠标锁定，点不到）
        int hintY = y + HEIGHT - 18;
        graphics.drawString(font, "§a[Y] 允许§r  §c[N] 拒绝§r  §7（或用聊天里的按钮）",
                x + 6, hintY, TEXT, false);
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && font.width(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /**
     * **注意：这里故意不处理鼠标点击**（2026-09-12 用户实测反馈）。
     *
     * <p>游戏内鼠标是**锁定**的（准星模式），HUD 上的按钮**无法悬停/点击** —— 我最初按"可点击卡片"设计是错的。
     * 答复改走两条**在游戏里真的能用**的路（都由服务端推送/注册，见 `PermissionGate` 与 `PermissionKeys`）：
     * <ol>
     *   <li>**聊天栏可点击按钮**：请示发出时服务端推一条带 `ClickEvent.runCommand` 的聊天消息；</li>
     *   <li>**快捷键**：允许/拒绝各一个键位（默认 `Y` / `N`，可在控制里改）。</li>
     * </ol>
     * 本渲染器只负责**显示**（标题/能力/理由/倒计时/默认档），不参与交互。
     */
}
