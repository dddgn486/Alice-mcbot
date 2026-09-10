package com.dddgn.alice.client.gui;

import com.dddgn.alice.client.ClientBotInventoryState;
import com.dddgn.alice.gui.BotInventoryService;
import com.dddgn.alice.gui.BotInventorySnapshot;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.BotInventoryActionPacket;
import com.dddgn.alice.network.BotInventoryPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Bot Inventory GUI - 参考车万女仆的布局风格。
 * 
 * <p>布局：
 * - 左侧：Bot 装备槽（4 护甲 + 1 副手）
 * - 右侧上：Bot 主背包（3×9）
 * - 右侧下：Bot 快捷栏（1×9）
 * - 底部：玩家背包（3×9 + 1×9 快捷栏）
 * 
 * <p>交互：
 * - 左键点击：PICKUP（从 bot 拿）或 PLACE（放到 bot）
 * - Shift+左键：QUICK_MOVE（智能移动）
 * - 服务端权威，客户端只读
 */
@OnlyIn(Dist.CLIENT)
public class BotInventoryScreen extends Screen {

    private static final ResourceLocation SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/sprites/container/slot.png");

    // GUI 尺寸
    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 222;
    
    // 槽位尺寸
    private static final int SLOT_SIZE = 18;
    
    // 颜色
    private static final int BG_COLOR = 0xC0101010;  // 半透明深灰
    private static final int PANEL_COLOR = 0xFF8B8B8B;  // 面板灰
    private static final int SLOT_BG = 0xFF373737;  // 槽位背景
    
    private int guiLeft;
    private int guiTop;
    
    // 当前手持的物品（用于 PLACE 操作）
    private ItemStack carriedItem = ItemStack.EMPTY;
    
    // 本地预测：被拿起的 bot 槽位（临时隐藏显示）
    private int pickedBotSlot = -1;
    
    // 上一次的快照（用于检测服务端更新）
    private BotInventoryPacket lastPacket = null;

    public BotInventoryScreen() {
        super(Component.literal("Bot Inventory"));
    }

    @Override
    protected void init() {
        this.guiLeft = (width - GUI_WIDTH) / 2;
        this.guiTop = (height - GUI_HEIGHT) / 2;
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
        if (packet == null) {
            graphics.drawCenteredString(font, Component.literal("No bot data"), 
                width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        
        // 检测服务端快照更新，清除本地预测
        if (lastPacket != packet) {
            pickedBotSlot = -1;
            lastPacket = packet;
        }
        
        int x = guiLeft;
        int y = guiTop;
        
        // 绘制主面板背景
        graphics.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, BG_COLOR);
        
        // 标题
        String title = "Bot Inventory: " + packet.name();
        graphics.drawString(font, title, x + 8, y + 6, 0xFFFFFFFF);
        
        // 分区标签
        graphics.drawString(font, "Equipment", x + 8, y + 20, 0xFFAAAAAA);
        graphics.drawString(font, "Inventory", x + 62, y + 20, 0xFFAAAAAA);
        
        // 绘制 Bot 装备槽（左侧）
        renderBotArmor(graphics, packet, x, y);
        
        // 绘制 Bot 背包（右侧）
        renderBotInventory(graphics, packet, x, y);
        
        // 绘制 Bot 快捷栏
        renderBotHotbar(graphics, packet, x, y);
        
        // 绘制玩家背包
        renderPlayerInventory(graphics, x, y);
        
        // 绘制手持物品（如果有）
        if (!carriedItem.isEmpty()) {
            graphics.renderItem(carriedItem, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(font, carriedItem, mouseX - 8, mouseY - 8);
        }
        
        super.render(graphics, mouseX, mouseY, delta);
    }
    
    /** 绘制 Bot 装备槽（左侧竖排）*/
    private void renderBotArmor(GuiGraphics graphics, BotInventoryPacket packet, int x, int y) {
        // 护甲槽：36-39（头、胸、腿、脚）
        for (int i = 0; i < 4; i++) {
            int slotIndex = 39 - i;  // 倒序：39头、38胸、37腿、36脚
            int sx = x + 8;
            int sy = y + 32 + i * SLOT_SIZE;
            
            renderSlot(graphics, sx, sy);
            
            // 如果这个槽被拿起了，不渲染物品
            if (slotIndex != pickedBotSlot) {
                ItemStack stack = packet.slots().get(slotIndex);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, sx + 1, sy + 1);
                    graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
                }
            }
        }
        
        // 副手槽：40
        int sx = x + 8;
        int sy = y + 32 + 4 * SLOT_SIZE + 4;  // 空一点间隔
        renderSlot(graphics, sx, sy);
        
        // 如果副手被拿起了，不渲染物品
        if (40 != pickedBotSlot) {
            ItemStack stack = packet.slots().get(40);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, sx + 1, sy + 1);
                graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
    }
    
    /** 绘制 Bot 主背包（3×9）*/
    private void renderBotInventory(GuiGraphics graphics, BotInventoryPacket packet, int x, int y) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;  // 9-35
                int sx = x + 62 + col * SLOT_SIZE;
                int sy = y + 32 + row * SLOT_SIZE;
                
                renderSlot(graphics, sx, sy);
                
                // 如果这个槽被拿起了，不渲染物品
                if (slotIndex != pickedBotSlot) {
                    ItemStack stack = packet.slots().get(slotIndex);
                    if (!stack.isEmpty()) {
                        graphics.renderItem(stack, sx + 1, sy + 1);
                        graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
                    }
                }
            }
        }
    }
    
    /** 绘制 Bot 快捷栏（1×9）*/
    private void renderBotHotbar(GuiGraphics graphics, BotInventoryPacket packet, int x, int y) {
        graphics.drawString(font, "Hotbar", x + 62, y + 92, 0xFFAAAAAA);
        // 金框 = 主手（= 当前选中槽，D-091）。vanilla 里 MAINHAND 由 selected 派生，
        // 工具切换（如砍树切到斧子）改的就是它。
        int mainHand = Math.max(0, Math.min(8, packet.selected()));
        graphics.drawString(font, "主手", x + 62 + mainHand * SLOT_SIZE + 2, y + 92, 0xFFFFAA00);
        
        for (int col = 0; col < 9; col++) {
            int slotIndex = col;  // 0-8
            int sx = x + 62 + col * SLOT_SIZE;
            int sy = y + 104;
            
            renderSlot(graphics, sx, sy);
            if (col == mainHand) {
                renderMainHandFrame(graphics, sx, sy);
            }
            
            // 如果这个槽被拿起了，不渲染物品
            if (slotIndex != pickedBotSlot) {
                ItemStack stack = packet.slots().get(slotIndex);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, sx + 1, sy + 1);
                    graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
                }
            }
        }
    }
    
    /** 主手（选中槽）金色边框；与 {@code BotInventoryMenuScreen} 的高亮同色。 */
    private void renderMainHandFrame(GuiGraphics graphics, int x, int y) {
        int color = 0xFFFFAA00;
        int w = SLOT_SIZE;
        graphics.fill(x - 1, y - 1, x + w + 1, y, color);              // 上
        graphics.fill(x - 1, y + w, x + w + 1, y + w + 1, color);      // 下
        graphics.fill(x - 1, y, x, y + w, color);                      // 左
        graphics.fill(x + w, y, x + w + 1, y + w, color);              // 右
    }

    /** 绘制玩家背包（3×9 + 1×9 快捷栏）*/
    private void renderPlayerInventory(GuiGraphics graphics, int x, int y) {
        graphics.drawString(font, "Player", x + 8, y + 130, 0xFFAAAAAA);
        
        Inventory playerInv = minecraft.player.getInventory();
        
        // 主背包 9-35
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;
                int sx = x + 8 + col * SLOT_SIZE;
                int sy = y + 142 + row * SLOT_SIZE;
                
                renderSlot(graphics, sx, sy);
                ItemStack stack = playerInv.getItem(slotIndex);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, sx + 1, sy + 1);
                    graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
                }
            }
        }
        
        // 快捷栏 0-8
        for (int col = 0; col < 9; col++) {
            int slotIndex = col;
            int sx = x + 8 + col * SLOT_SIZE;
            int sy = y + 142 + 3 * SLOT_SIZE + 4;  // 空一点间隔
            
            renderSlot(graphics, sx, sy);
            ItemStack stack = playerInv.getItem(slotIndex);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, sx + 1, sy + 1);
                graphics.renderItemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
    }
    
    /** 绘制单个槽位背景 */
    private void renderSlot(GuiGraphics graphics, int x, int y) {
        // 槽位背景
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);
        // 边框
        graphics.fill(x, y, x + SLOT_SIZE, y + 1, PANEL_COLOR);  // 上
        graphics.fill(x, y, x + 1, y + SLOT_SIZE, PANEL_COLOR);  // 左
        graphics.fill(x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF373737);  // 下
        graphics.fill(x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF373737);  // 右
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        
        BotInventoryPacket packet = ClientBotInventoryState.get();
        if (packet == null) return super.mouseClicked(mouseX, mouseY, button);
        
        // 检测点击的是 Bot 槽还是玩家槽
        int botSlot = hitTestBotSlot(mouseX, mouseY);
        int playerSlot = hitTestPlayerSlot(mouseX, mouseY);
        
        boolean shift = Screen.hasShiftDown();
        
        if (botSlot >= 0) {
            // 点击 Bot 槽
            handleBotSlotClick(packet, botSlot, shift);
            return true;
        } else if (playerSlot >= 0) {
            // 点击玩家槽
            handlePlayerSlotClick(playerSlot, shift);
            return true;
        }
        
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    /** 处理 Bot 槽点击 */
    private void handleBotSlotClick(BotInventoryPacket packet, int slotIndex, boolean shift) {
        if (shift) {
            // Shift: 快速移动到玩家背包
            AliceNetwork.CHANNEL.sendToServer(new BotInventoryActionPacket(
                packet.botId(), BotInventoryService.ActionType.QUICK_MOVE, slotIndex, -1));
        } else {
            if (carriedItem.isEmpty()) {
                // PICKUP: 从 bot 拿
                ItemStack botStack = packet.slots().get(slotIndex);
                if (!botStack.isEmpty()) {
                    carriedItem = botStack.copy();  // 本地预测
                    pickedBotSlot = slotIndex;      // 标记被拿起的槽位
                    AliceNetwork.CHANNEL.sendToServer(new BotInventoryActionPacket(
                        packet.botId(), BotInventoryService.ActionType.PICKUP, slotIndex, -1));
                }
            } else {
                // PLACE: 放到 bot
                int playerSlot = findCarriedInPlayerInventory();
                if (playerSlot >= 0) {
                    carriedItem = ItemStack.EMPTY;  // 本地预测
                    pickedBotSlot = -1;             // 清除标记
                    AliceNetwork.CHANNEL.sendToServer(new BotInventoryActionPacket(
                        packet.botId(), BotInventoryService.ActionType.PLACE, slotIndex, playerSlot));
                }
            }
        }
    }
    
    /** 处理玩家槽点击 */
    private void handlePlayerSlotClick(int slotIndex, boolean shift) {
        Inventory playerInv = minecraft.player.getInventory();
        ItemStack stack = playerInv.getItem(slotIndex);
        
        if (!stack.isEmpty()) {
            if (carriedItem.isEmpty()) {
                // 拿起
                carriedItem = stack.copy();
            } else {
                // 交换/合并（简化：只支持拿起）
                carriedItem = stack.copy();
            }
        } else if (!carriedItem.isEmpty()) {
            // 放下
            carriedItem = ItemStack.EMPTY;
        }
    }
    
    /** 碰撞检测：Bot 槽 */
    private int hitTestBotSlot(double mouseX, double mouseY) {
        int x = guiLeft;
        int y = guiTop;
        int relX = (int) (mouseX - x);
        int relY = (int) (mouseY - y);
        
        // 护甲槽 36-39
        for (int i = 0; i < 4; i++) {
            int slotIndex = 39 - i;
            if (hitTest(relX, relY, 8, 32 + i * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)) {
                return slotIndex;
            }
        }
        
        // 副手槽 40
        if (hitTest(relX, relY, 8, 32 + 4 * SLOT_SIZE + 4, SLOT_SIZE, SLOT_SIZE)) {
            return 40;
        }
        
        // 主背包 9-35
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;
                if (hitTest(relX, relY, 62 + col * SLOT_SIZE, 32 + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)) {
                    return slotIndex;
                }
            }
        }
        
        // 快捷栏 0-8
        for (int col = 0; col < 9; col++) {
            if (hitTest(relX, relY, 62 + col * SLOT_SIZE, 104, SLOT_SIZE, SLOT_SIZE)) {
                return col;
            }
        }
        
        return -1;
    }
    
    /** 碰撞检测：玩家槽 */
    private int hitTestPlayerSlot(double mouseX, double mouseY) {
        int x = guiLeft;
        int y = guiTop;
        int relX = (int) (mouseX - x);
        int relY = (int) (mouseY - y);
        
        // 主背包 9-35
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;
                if (hitTest(relX, relY, 8 + col * SLOT_SIZE, 142 + row * SLOT_SIZE, SLOT_SIZE, SLOT_SIZE)) {
                    return slotIndex;
                }
            }
        }
        
        // 快捷栏 0-8
        for (int col = 0; col < 9; col++) {
            if (hitTest(relX, relY, 8 + col * SLOT_SIZE, 142 + 3 * SLOT_SIZE + 4, SLOT_SIZE, SLOT_SIZE)) {
                return col;
            }
        }
        
        return -1;
    }
    
    private boolean hitTest(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
    
    /** 在玩家背包中查找手持物品的槽位 */
    private int findCarriedInPlayerInventory() {
        if (carriedItem.isEmpty()) return -1;
        
        Inventory playerInv = minecraft.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerInv.getItem(i);
            if (ItemStack.isSameItemSameTags(stack, carriedItem)) {
                return i;
            }
        }
        return -1;
    }
}
