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
 * Client screen for a bot's inventory menu. Bound to {@link ModMenuTypes#BOT_INVENTORY_MENU}
 * via {@code MenuScreens.register} in the client setup. The screen content is driven by the
 * server menu slots (container width/height match the 3x9 bot grid + player grid). When the
 * bot is busy the server menu is read-only; the title indicates this.
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {

    // A plain 176x222 inventory background from the vanilla chest GUI.
    private static final ResourceLocation CONTAINER_BACKGROUND =
            new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
    }

    @Override
    protected void init() {
        super.init();
        // The default 54-slot background is wider logically than our grid, but the slots are
        // positioned by the server menu's x/y, so the standard panel works.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        graphics.blit(CONTAINER_BACKGROUND, x, y, 0, 0, imageWidth, imageHeight);
    }
}
