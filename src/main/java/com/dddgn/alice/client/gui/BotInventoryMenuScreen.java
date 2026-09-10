package com.dddgn.alice.client.gui;

import com.dddgn.alice.gui.BotInventoryMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Bot Inventory GUI - 方案 A（AbstractContainerScreen + Menu）。
 * 
 * <p>使用原版 AbstractContainerMenu 机制：
 * - 自动处理 menu.carried（鼠标上的物品）
 * - 原版 clicked() 逻辑处理 PICKUP/PLACE/QUICK_MOVE
 * - 通过 slotId=-1 packet 自动同步 cursor item
 * 
 * <p>布局参考车万女仆：
 * - 左侧：Bot 装备槽（竖排）+ 主手槽 + 副手槽
 * - 右侧：Bot 背包（3×9）+ Bot 快捷栏（1×9）
 * - 底部：玩家背包（3×9 + 1×9）
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryMenuScreen extends AbstractContainerScreen<BotInventoryMenu> {

    // 背景纹理（暂时使用普通灰色背景，后续可以自定义）
    private static final ResourceLocation BACKGROUND = 
        ResourceLocation.fromNamespaceAndPath("alice", "textures/gui/bot_inventory.png");
    
    // GUI 尺寸（调整高度以容纳玩家背包）
    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 256;  // 增加到 256，容纳 y=238+18=256 的玩家 hotbar

    public BotInventoryMenuScreen(BotInventoryMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 94;  // 玩家背包标签位置
    }

    @Override
    protected void init() {
        super.init();
        // 标题居中
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        
        int x = this.leftPos;
        int y = this.topPos;
        
        // 绘制背景
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xC0101010);
        
        // 绘制所有槽位背景
        for (int i = 0; i < this.menu.slots.size(); i++) {
            var slot = this.menu.slots.get(i);
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            renderSlotBackground(graphics, sx, sy);
        }
        
        // 高亮 bot 的**当前选中槽 = 主手**（D-091）。
        // 2026-09-10 修正：原实现在此硬编码 menu 索引 32，注释写"主手槽永远是 inventory index 0"——
        // 那是**已被取代**的旧设计（受车万女仆模组的独立主手格影响，见 AI_DECISIONS D-091）。
        // vanilla 里 MAINHAND 由 selected 派生，工具切换改的正是 selected（如砍树切到斧子），
        // 所以固定高亮第一格会**指错格**。选中槽现在随快照/包下发。
        int mainHandSlotIndex = com.dddgn.alice.gui.BotInventoryMenu.BOT_HOTBAR_START
                + com.dddgn.alice.client.ClientBotInventoryState.selectedOrDefault(0);
        if (mainHandSlotIndex < this.menu.slots.size()) {
            var slot = this.menu.slots.get(mainHandSlotIndex);
            renderSelectedSlotHighlight(graphics, x + slot.x - 1, y + slot.y - 1);
        }
        
        // TODO: Bot 模型渲染（暂时移除，因为 EntityRenderer 为 null）
    }
    
    /** 渲染选中槽位的高亮框 */
    private void renderSelectedSlotHighlight(GuiGraphics graphics, int x, int y) {
        int slotSize = 18;
        int highlightColor = 0xFFFFAA00;  // 金色高亮
        int borderWidth = 2;
        
        // 绘制边框（四边）
        graphics.fill(x, y, x + slotSize, y + borderWidth, highlightColor);  // 上
        graphics.fill(x, y + slotSize - borderWidth, x + slotSize, y + slotSize, highlightColor);  // 下
        graphics.fill(x, y, x + borderWidth, y + slotSize, highlightColor);  // 左
        graphics.fill(x + slotSize - borderWidth, y, x + slotSize, y + slotSize, highlightColor);  // 右
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
        
        // 渲染分区标签
        int x = this.leftPos;
        int y = this.topPos;
        
        // 装备区域标签
        graphics.drawString(font, "Equipment", x + 8, y - 2, 0xFFAAAAAA, false);
        
        // 副手标签
        graphics.drawString(font, "Off", x + 77, y - 2, 0xFFAAAAAA, false);
        
        // 背包标签
        graphics.drawString(font, "Inventory", x + 8, y + 73, 0xFFAAAAAA, false);
        
        // 渲染任务状态（如果 bot 正在执行任务）
        if (this.menu.taskActive()) {
            graphics.drawString(font, "§6[Busy]", x + 140, y + 6, 0xFFFFAA00, false);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFFFFFFF, false);
        
        // 玩家背包标签
        graphics.drawString(this.font, this.playerInventoryTitle, 
            this.inventoryLabelX, this.inventoryLabelY, 0xFFAAAAAA, false);
    }

    /** 绘制槽位背景 */
    private void renderSlotBackground(GuiGraphics graphics, int x, int y) {
        int slotSize = 18;
        int bgColor = 0xFF373737;
        int borderColor = 0xFF8B8B8B;
        
        // 背景
        graphics.fill(x, y, x + slotSize, y + slotSize, bgColor);
        
        // 边框
        graphics.fill(x, y, x + slotSize, y + 1, borderColor);  // 上
        graphics.fill(x, y, x + 1, y + slotSize, borderColor);  // 左
        graphics.fill(x, y + slotSize - 1, x + slotSize, y + slotSize, 0xFF373737);  // 下
        graphics.fill(x + slotSize - 1, y, x + slotSize, y + slotSize, 0xFF373737);  // 右
    }
}
