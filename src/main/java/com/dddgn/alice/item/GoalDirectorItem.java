package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.DecisionSnapshot;
import com.dddgn.alice.decision.GoalDirector;
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
 * **决策层自检**（{@code alice:goal_director}，D-135 / ② 决策层）：普通右键，零参数。
 *
 * <p>做三件事（都在聊天里能看到）：
 * <ol>
 *   <li>打印 LLM 配置状态（**不含 key**：只说"已配置/缺失"）；</li>
 *   <li>打印**给 LLM 的权威快照**（同一份 JSON 也进日志 `[Goal] snapshot`）——这是"输入契约"的证据；</li>
 *   <li>**强制触发一次决策**（`GoalDirector.forceOnce`），把 LLM 的动作结果回聊天/日志。</li>
 * </ol>
 *
 * <p>没配 LLM 时也照样能测：会明确回 `LLM 未配置 ⇒ 只打印快照，不发请求`，不会假装成功。
 */
public class GoalDirectorItem extends Item {

    public GoalDirectorItem(Properties properties) {
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
        if (bot == null) {
            say(player, "[alice] bot 生成失败");
            return;
        }
        say(player, "[alice] 决策层配置：" + GoalDirector.describe());
        say(player, "[alice] 快照（给 LLM 的权威事实，完整版见日志 [Goal] snapshot）："
                + summarize(bot));
        String result = GoalDirector.forceOnce(bot,
                player instanceof ServerPlayer sp ? sp : null);
        say(player, "[alice] 决策触发：" + result + "（动作结果稍后回聊天/日志 [Goal] decision_action）");
    }

    /** 聊天里只放最关键的一行，避免刷屏；完整 JSON 在日志里。 */
    private static String summarize(BotPlayer bot) {
        var snapshot = DecisionSnapshot.build(bot);
        String task = snapshot.has("task") && snapshot.getAsJsonObject("task").has("current")
                ? snapshot.getAsJsonObject("task").get("current").getAsString() : "?";
        String last = "-";
        if (snapshot.has("task") && snapshot.getAsJsonObject("task").has("lastTerminal")) {
            var node = snapshot.getAsJsonObject("task").getAsJsonObject("lastTerminal");
            last = node.get("kind").getAsString() + "/" + node.get("terminal").getAsString()
                    + "/" + node.get("terminalReason").getAsString();
        }
        return "pos=" + bot.blockPosition().toShortString() + " task=" + task + " lastTerminal=" + last;
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
