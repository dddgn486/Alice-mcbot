package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.BotStateReport;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * **bot 状态汇报**（{@code alice:bot_report}，S1 事实层 / D-136）：普通右键，零参数。
 *
 * <p>汇报通道的入口：**玩家手动问**才输出，内容是**确定性组装的事实**
 * （任务树 + 生存 + 背包 + 最近事件 + 账本 + 区域）—— 完整 JSON 同时进日志，
 * 便于"事实对不对"逐字段核对；后续 S1 的可选部分才是"让 LLM 把它翻成人话"。
 */
public class BotReportItem extends Item {

    public BotReportItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        report(context.getPlayer(), (ServerLevel) level);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        report(player, (ServerLevel) level);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private void report(net.minecraft.world.entity.player.Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            say(player, "[alice] 还没有 bot（任意夹具入口都会生成）");
            return;
        }
        List<String> lines = BotStateReport.render(bot);
        for (String line : lines) {
            say(player, "[alice] " + line);
        }
        BotLog.info("[Report] json={}", BotStateReport.json(bot));
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
