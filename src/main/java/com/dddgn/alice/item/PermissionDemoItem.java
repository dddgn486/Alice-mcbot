package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.PermissionDemoTask;
import com.dddgn.alice.task.LumberCourseAnchor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * **请示通道演示**（{@code alice:permission_demo}，S3 / D-140）：普通右键，零参数。
 *
 * <p>起一个 {@link PermissionDemoTask}：它会发起一条**需要玩家拍板**的请示
 * （能力 `demo_ask`，选项 allow/deny，默认 **deny**，30 s 超时），然后：
 * <ul>
 *   <li>答复 `allow` ⇒ 演示能力执行 + 聊天确认；</li>
 *   <li>答复 `deny` / **不答（超时）** ⇒ 不执行 + **自动返回**（如实记终态理由）；</li>
 * </ul>
 * 答复方式（两者等价）：聊天里的 `/alice ask &lt;id&gt; allow|deny [once|session|always]`，
 * 或（S3b 起）屏幕一侧的弹窗卡片。
 */
public class PermissionDemoItem extends Item {

    public PermissionDemoItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        start(context.getPlayer(), (ServerLevel) level);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private void start(net.minecraft.world.entity.player.Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, LumberCourseAnchor.START_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
        if (!BotManager.assignPermissionDemo(bot,
                player instanceof ServerPlayer sp ? sp : null, 1200)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 请示演示：已发起 `demo_ask`（默认 deny、30 s 超时）。"
                + "用 /alice ask 看 id，再 /alice ask <id> allow|deny [once|session|always]；"
                + "**不答也会按默认档自动返回**");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
