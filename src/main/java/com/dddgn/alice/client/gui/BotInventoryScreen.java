package com.dddgn.alice.client.gui;

import com.dddgn.alice.gui.BotInventoryMenu;
import com.dddgn.alice.gui.ModMenuTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client screen for a bot's inventory menu. The slot grid is a custom mix of the bot's
 * backpack (armor + offhand + 36 slots, vanilla geometry) and the player's own inventory,
 * so no single vanilla texture's fixed slot borders line up. We draw our own panel with an
 * explicit alpha and place slot cells using the server menu's vanilla slot coordinates,
 * which keeps title/label text readable. Text is drawn by the base class in the standard
 * contrast (title 0xFFFFFF, label 0x404040) so it is never masked by the panel.
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {

    // Panel background colors with explicit alpha (0xFFRRGGBB).
    private static final int PANEL_TOP = 0xFF1F1F1F;
    private static final int BORDER = 0xFF404040;
    private static final int SLOT_BG = 0xFF7A7A7A;
    private static final int SLOT_DARK = 0xFF6A6A6A;

    public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 274; // bot section (vanilla 166) + player section (~108)
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_TOP);
        graphics.fill(x, y, x + imageWidth, y + 1, BORDER);
        graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, BORDER);
        graphics.fill(x, y, x + 1, y + imageHeight, BORDER);
        graphics.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, BORDER);

        for (int i = 0; i < getMenu().slots.size(); i++) {
            var slot = getMenu().slots.get(i);
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
            graphics.fill(sx, sy, sx + 18, sy + 1, SLOT_DARK);
        }
    }
}
