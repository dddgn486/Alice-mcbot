package com.dddgn.alice.client.render;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Bot 任务状态 HUD 渲染器。
 * <p>
 * 在屏幕右上角显示所有 Bot 的当前任务状态。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT)
public final class BotTaskHudRenderer {
    
    private static final int TEXT_COLOR = 0xFFFFFFFF;  // 白色
    private static final int SHADOW_COLOR = 0xFF000000;  // 黑色阴影
    private static final int BACKGROUND_COLOR = 0x80000000;  // 半透明黑色背景
    
    private BotTaskHudRenderer() {
    }
    
    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        
        // 获取所有 Bot
        Collection<BotPlayer> bots = BotManager.getAllBots();
        if (bots.isEmpty()) {
            return;
        }
        
        PoseStack poseStack = event.getGuiGraphics().pose();
        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        // 收集需要显示的文本
        List<String> lines = new ArrayList<>();
        lines.add("§l§eBots 任务状态");  // 标题（加粗、黄色）
        
        for (BotPlayer bot : bots) {
            String summary = BotManager.currentTaskSummary(bot);
            boolean isBusy = BotManager.isBusy(bot);
            
            if (isBusy && summary != null) {
                lines.add("§a● §f" + bot.getName().getString());  // 绿点 + 名字
                lines.add("  §7" + formatTaskSummary(summary));  // 灰色任务摘要
            } else {
                lines.add("§8○ §f" + bot.getName().getString() + " §7(空闲)");
            }
        }
        
        // 计算 HUD 尺寸
        int maxWidth = 0;
        for (String line : lines) {
            int width = font.width(stripColorCodes(line));
            if (width > maxWidth) {
                maxWidth = width;
            }
        }
        
        int padding = 5;
        int lineHeight = 10;
        int hudWidth = maxWidth + padding * 2;
        int hudHeight = lines.size() * lineHeight + padding * 2;
        
        // HUD 位置：屏幕右上角
        int hudX = screenWidth - hudWidth - 10;
        int hudY = 10;
        
        // 绘制半透明背景
        event.getGuiGraphics().fill(hudX, hudY, hudX + hudWidth, hudY + hudHeight, BACKGROUND_COLOR);
        
        // 绘制文本
        poseStack.pushPose();
        for (int i = 0; i < lines.size(); i++) {
            int x = hudX + padding;
            int y = hudY + padding + i * lineHeight;
            event.getGuiGraphics().drawString(font, lines.get(i), x, y, TEXT_COLOR, true);
        }
        poseStack.popPose();
    }
    
    /**
     * 格式化任务摘要为更易读的中文。
     */
    private static String formatTaskSummary(String summary) {
        if (summary == null) {
            return "未知任务";
        }
        
        // 解析任务类型
        if (summary.contains("RegionLumberTask")) {
            return "区域伐木";
        } else if (summary.contains("ContinuousLumberTask")) {
            return "自动伐木";
        } else if (summary.contains("MineTask")) {
            return "挖掘任务";
        } else if (summary.contains("TransferTask")) {
            return "物品转移";
        } else if (summary.contains("FollowTask")) {
            return "跟随玩家";
        } else {
            // 提取任务类名
            int idx = summary.indexOf(' ');
            if (idx > 0) {
                return summary.substring(0, idx);
            }
            return summary;
        }
    }
    
    /**
     * 移除 Minecraft 颜色代码（用于计算实际文本宽度）。
     */
    private static String stripColorCodes(String text) {
        return text.replaceAll("§.", "");
    }
}
