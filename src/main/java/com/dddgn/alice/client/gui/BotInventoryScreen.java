package com.dddgn.alice.client.gui;

import com.dddgn.alice.gui.BotInventoryMenu;
import com.dddgn.alice.gui.ModMenuTypes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client screen for a bot's inventory menu. The bot's backpack reuses the vanilla
 * {@code inventory.png} background (176x166) because its armor/offhand/main/hotbar slots use
 * the vanilla player-backpack geometry and line up with that texture. The player's own
 * inventory grid sits below the vanilla region, so the region below y=166 is filled with a
 * plain panel (explicit alpha) and the player slot cells are drawn on top.
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {

    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");

    // Player-section panel colors (explicit alpha 0xFFRRGGBB).
    private static final int PANEL_TOP = 0xFF1F1F1F;
    private static final int SLOT_BG = 0xFF7A7A7A;
    private static final int SLOT_DARK = 0xFF6A6A6A;

    /** Vanilla player-backpack texture height (the bot section). */
    private static final int VANILLA_HEIGHT = 166;

    public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 274; // vanilla bot section (166) + player section (~108)
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        // Bot section: vanilla player-backpack background, matching the bot slot geometry.
        graphics.blit(INVENTORY_TEXTURE, x, y, 0, 0, imageWidth, VANILLA_HEIGHT);
        // Player section (below the vanilla region): plain panel so slot cells are defined.
        if (imageHeight > VANILLA_HEIGHT) {
            graphics.fill(x, y + VANILLA_HEIGHT, x + imageWidth, y + imageHeight, PANEL_TOP);
        }
        // Draw a light slot cell under every server slot so items sit in a defined box.
        for (int i = 0; i < getMenu().slots.size(); i++) {
            var slot = getMenu().slots.get(i);
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
            graphics.fill(sx, sy, sx + 18, sy + 1, SLOT_DARK);
        }
    }
}
