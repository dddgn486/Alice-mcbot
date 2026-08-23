package com.dddgn.alice.client.gui;

import com.dddgn.alice.client.ClientBotInventoryState;
import com.dddgn.alice.gui.BotInventoryService;
import com.dddgn.alice.gui.BotInventorySnapshot;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.BotInventoryActionPacket;
import com.dddgn.alice.network.BotInventoryPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-only bot inventory screen (direction B). It reads the authoritative snapshot from
 * {@link ClientBotInventoryState} (pushed by the server) and renders 36 ordinary + 4 armor +
 * 1 offhand slots. Every click is encoded as a {@code BotInventoryActionPacket} (C2S) and sent
 * to the server; the client never mutates local inventory, and the screen refreshes only when
 * the server pushes a fresh snapshot. There is no {@code AbstractContainerMenu} underneath.
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends Screen {

    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 274;
    private static final int SLOT_SIZE = 18;

    private static final int SLOT_BG = 0xFF7A7A7A;
    private static final int SLOT_DARK = 0xFF6A6A6A;

    private int guiLeft;
    private int guiTop;

    public BotInventoryScreen() {
        super(Component.literal("Bot Inventory"));
    }

    @Override
    protected void init() {
        this.guiLeft = (width - IMAGE_WIDTH) / 2;
        this.guiTop = (height - IMAGE_HEIGHT) / 2;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        BotInventoryPacket packet = ClientBotInventoryState.get();
        String name = packet != null ? packet.name() : "?";
        graphics.drawCenteredString(font, Component.literal("Bot Inventory: " + name + " (server-authoritative)"),
                width / 2, guiTop + 4, 0xFFFFFFFF);

        int x = guiLeft;
        int y = guiTop;
        graphics.blit(INVENTORY_TEXTURE, x, y, 0, 0, IMAGE_WIDTH, 166);
        graphics.fill(x, y + 166, x + IMAGE_WIDTH, y + IMAGE_HEIGHT, 0xFF1F1F1F);

        if (packet != null) {
            for (int i = 0; i < packet.slots().size(); i++) {
                int sx = x + slotX(i) - 1;
                int sy = y + slotY(i) - 1;
                graphics.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, SLOT_BG);
                graphics.fill(sx, sy, sx + SLOT_SIZE, sy + 1, SLOT_DARK);
                ItemStack stack = packet.slots().get(i);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, sx + 1, sy + 1);
                    graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
                }
            }
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        BotInventoryPacket packet = ClientBotInventoryState.get();
        if (packet == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int index = hitTest(mouseX, mouseY);
        if (index >= 0) {
            if (button == 0) {
                // Left-click: pickup/place. Shift toggles quick-move.
                boolean shifted = net.minecraft.client.gui.screens.Screen.hasShiftDown();
                BotInventoryService.ActionType action = shifted
                        ? BotInventoryService.ActionType.QUICK_MOVE
                        : BotInventoryService.ActionType.PICKUP;
                AliceNetwork.CHANNEL.sendToServer(new BotInventoryActionPacket(
                        packet.botId(), action, index, -1));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int hitTest(double mouseX, double mouseY) {
        int relX = (int) (mouseX - guiLeft);
        int relY = (int) (mouseY - guiTop);
        for (int i = 0; i < BotInventorySnapshot.SLOT_COUNT; i++) {
            int sx = slotX(i) - 1;
            int sy = slotY(i) - 1;
            if (relX >= sx && relX < sx + SLOT_SIZE && relY >= sy && relY < sy + SLOT_SIZE) {
                return i;
            }
        }
        return -1;
    }

    /** Server snapshot index geometry (0-35 ordinary, 36-39 armor, 40 offhand). */
    private int slotX(int index) {
        if (index < 9) { // hotbar 0..8
            return 8 + index * 18;
        }
        if (index < 36) { // main 9..35
            return 8 + ((index - 9) % 9) * 18;
        }
        if (index < 40) { // armor column 36..39
            return 8;
        }
        // offhand
        return 77;
    }

    private int slotY(int index) {
        if (index < 9) { // hotbar
            return 142;
        }
        if (index < 36) { // main inventory rows 9..35
            return 84 + ((index - 9) / 9) * 18;
        }
        if (index < 40) { // armor column 36..39
            return 8 + (index - 36) * 18;
        }
        // offhand
        return 62;
    }

}
