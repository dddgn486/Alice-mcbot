package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.PillarDiagnosticTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * **事件阈值自检**（{@code alice:event_threshold_check}，S4 / D-150）：普通右键，零参数，约 25 秒。
 *
 * <p>它同时是电池第 15 步的**单跑版**：电池跑满一轮要 4~6 分钟，而 S4 的现场（bot 在竖井里顶壁）
 * 只有站近才看得到 —— 所以给一个能单独重跑、且能盯着看的入口。
 *
 * <p>四例（判据见 {@link com.dddgn.alice.task.EventThresholdCheckTask}）：
 * A 工具压到 15% ⇒ **一条** `TOOL_LOW`；B 修满再压 ⇒ **再一条**；C 竖井顶壁 ⇒ **一条** `STUCK`；
 * D 继续顶 ⇒ **不再增加**。
 */
public class EventThresholdCheckItem extends Item {

    public EventThresholdCheckItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, PillarDiagnosticTask.SHAFT_START);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        // **不要把 bot 直接放进竖井**：上一轮残留的方块会把竖井堵死，bot 一进去就窒息，
        // 维生系统在第 1 tick 打断任务 ⇒ 夹具没机会重建场景（2026-09-12 实测三次全这样）。
        // 先把场景建好，再把 bot 放到竖井**顶沿**（基岩环上方，保证不窒息），
        // 具体进竖井的动作交给任务自己的 setup（它还会再建一次场景并等落地结算）。
        var source = level.getServer().createCommandSourceStack().withSuppressedOutput();
        level.getServer().getCommands().performPrefixedCommand(source, "function alice_test:pillar_course");
        bot.teleportTo(level, PillarDiagnosticTask.RIM_GOAL.getX() + 0.5D,
                PillarDiagnosticTask.RIM_GOAL.getY(), PillarDiagnosticTask.RIM_GOAL.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
        if (!BotManager.assignEventThresholdCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 事件阈值自检（约 25 秒）：A/B 工具耐久见底报告与复位，"
                + "C/D bot 会在 1×1 竖井里顶壁 —— 请看它是否真的原地不动。看 [EventThreshold] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
