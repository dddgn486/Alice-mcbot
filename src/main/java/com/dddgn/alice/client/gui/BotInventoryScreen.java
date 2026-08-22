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
 * Client screen for a bot's inventory menu. The background is drawn as a plain panel rather
 * than a fixed vanilla container texture, because the slot grid is a custom mix of the bot's
 * backpack (armor + offhand + 36 slots) and the player's own inventory — no vanilla texture's
 * fixed slot borders match it. Drawing our own panel avoids the slot/item misalignment seen
 * with {@code generic_54}. The panel height accommodates the bot grid and the player grid.
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {

    // Panel background colors (dark inventory theme). Alpha is explicit (0xFFRRGGBB).
    private static final int PANEL_TOP = 0xFF3F3F3F;
    private static final int BORDER = 0xFF545454;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF7A7A7A;

    public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 274; // bot section (~166) + player section (~108)
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        // Panel background and border. renderBackground() is NOT overridden; the base class
        // handles the backdrop and masks, and this method draws the panel above it.
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_TOP);
        graphics.fill(x, y, x + imageWidth, y + 1, BORDER);
        graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, BORDER);
        graphics.fill(x, y, x + 1, y + imageHeight, BORDER);
        graphics.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, BORDER);

        // Draw a light slot box under each server slot so items sit in a defined cell.
        for (int i = 0; i < getMenu().slots.size(); i++) {
            var slot = getMenu().slots.get(i);
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
            graphics.fill(sx, sy, sx + 18, sy + 1, SLOT_DARK);
        }
    }
}
