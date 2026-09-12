package com.dddgn.alice.client.render;

import com.dddgn.alice.client.ClientPermissionState;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.PermissionAnswerPacket;
import com.dddgn.alice.network.PermissionNoticePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
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

        // 两个按钮（选项 1 / 选项 2），左键点击即答复（scope=ONCE）
        int buttonY = y + HEIGHT - BUTTON_HEIGHT - 4;
        int half = (WIDTH - 18) / 2;
        drawButton(graphics, font, x + 6, buttonY, half,
                label(notice.option1(), notice.defaultOption()), isAllow(notice.option1()));
        drawButton(graphics, font, x + 12 + half, buttonY, half,
                label(notice.option2(), notice.defaultOption()), isAllow(notice.option2()));
    }

    private static String label(String option, String defaultOption) {
        if (option == null || option.isBlank()) {
            return "-";
        }
        return option + ("allow".equalsIgnoreCase(option) ? "（批准）" : "");
    }

    private static boolean isAllow(String option) {
        return option != null && (option.equalsIgnoreCase("allow") || option.equalsIgnoreCase("yes"));
    }

    private static void drawButton(GuiGraphics graphics, Font font, int x, int y, int width,
                                   String text, boolean allow) {
        boolean hovered = isHovered(x, y, width, BUTTON_HEIGHT);
        graphics.fill(x, y, x + width, y + BUTTON_HEIGHT, hovered ? BUTTON_HOVER : BUTTON_BG);
        int color = allow ? ALLOW_COLOR : DENY_COLOR;
        graphics.drawString(font, text, x + 4, y + 4, color, false);
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

    private static boolean isHovered(int x, int y, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth()
                / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight()
                / mc.getWindow().getScreenHeight();
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /** 点击卡片按钮 ⇒ 发答复包（与 `/alice ask <id> <option> once` 等价）。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getButton() != 0 || event.getAction() != 1) {
            return;   // 只看左键按下
        }
        PermissionNoticePacket notice = ClientPermissionState.current();
        if (notice == null) {
            return;
        }
        int x = panelX(mc);
        int y = panelY(mc);
        int buttonY = y + HEIGHT - BUTTON_HEIGHT - 4;
        int half = (WIDTH - 18) / 2;
        String clicked = null;
        if (isHovered(x + 6, buttonY, half, BUTTON_HEIGHT)) {
            clicked = notice.option1();
        } else if (isHovered(x + 12 + half, buttonY, half, BUTTON_HEIGHT)) {
            clicked = notice.option2();
        }
        if (clicked == null || clicked.isBlank()) {
            return;
        }
        AliceNetwork.CHANNEL.sendToServer(new PermissionAnswerPacket(notice.id(), clicked, "ONCE"));
        ClientPermissionState.clear(notice.id());
        event.setCanceled(true);   // 吃掉这次点击，别让它穿透到游戏里
    }
}
