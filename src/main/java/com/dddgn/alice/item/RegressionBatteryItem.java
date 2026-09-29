package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.RegressionBatteryTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 串联回归电池启动器（{@code alice:regression_battery}，D-122）：**零参数**。
 *
 * <ul>
 *   <li><b>右键</b> = CORE 档（必要基础 + 当前主线），清单见 {@code RegressionBatteryTask.CURATION}
 *       与 {@code docs/TESTING_GUIDE.md §1.7}）：每项自己复位、失败不中断，最后一行
 *       {@code [Regression] SUMMARY … (N/N) → PASS|FAIL}；</li>
 *   <li>⭐ <b>Shift+右键</b>（{@link RegressionBatteryTask#lastOnlySteps()}，`D-521`）
 *       = **重跑上一个单步** —— 调同一个用例时最省事：
 *       <pre>在聊天里点一次步名（`/alice battery list`）⇒ 此后 Shift+右键 就是「再跑一次那一步」</pre>
 *       ⚠️ 没跑过单步时它**不猜**，直接告诉你先去点一个（⛔ 不许静默退化成跑整轮 CORE ——
 *       那会让"我以为只跑了一步"和"其实跑了 30 步"长得一模一样）。</li>
 * </ul>
 *
 * <p>⭐ **观察者会被一起带走**（`D-521`）—— 两条路都是：电池在**每一步开始**把
 * {@code observer} 传到该步现场（同层、离 bot 半径 4~8 的第一个可站格），
 * **整轮结束**时送回原处。为什么需要它：CORE 30 步的场景是硬编码世界坐标，实测散布在
 * {@code z=46…245}（另有一个探针步在 {@code 432/428}）⇒ 不传的话人只能看到最后一步。
 *
 * <p>⚠️ **项数一律现算**（{@link RegressionBatteryTask#coreStepCount()}），**不在文案里写死** ——
 * 写死过一次（"26 项"），加到 29 项后就成了错话（台账⑦）。
 */
public class RegressionBatteryItem extends Item {

    public RegressionBatteryItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        boolean rerunOnly = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
        start(context.getPlayer(), (ServerLevel) level, rerunOnly);
        return InteractionResult.SUCCESS;
    }

    /**
     * @param rerunOnly true = **重跑上一个单步**（Shift+右键）；false = CORE 档（右键）
     */
    private void start(net.minecraft.world.entity.player.Player player, ServerLevel level,
                       boolean rerunOnly) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, com.dddgn.alice.task.ClearRetryCheckTask.START_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        // ⚠️ `onlySteps` 是**静态**的（无头通道的机制）⇒ 两条路都必须显式设定，否则会**串味**：
        // 上一轮单步留下的名单会让这一次右键（本该跑 CORE）只跑那一步 —— 而日志看起来一切正常。
        if (rerunOnly) {
            var last = RegressionBatteryTask.lastOnlySteps();
            if (last.isEmpty()) {
                say(player, "[alice] 还没跑过任何单步 ⇒ 先用 /alice battery list，在聊天里点一个步名");
                return;
            }
            RegressionBatteryTask.setOnlySteps(last);
        } else {
            RegressionBatteryTask.setOnlySteps(null);
        }
        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
        if (!BotManager.assignRegressionBattery(bot, observer)) {
            RegressionBatteryTask.setOnlySteps(null);
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, rerunOnly
                ? "[alice] 重跑单步：" + String.join(",", RegressionBatteryTask.lastOnlySteps())
                        + " ⇒ 已把你传到现场，跑完送回原处（别捡掉落物；结果看 [Regression] SUMMARY）"
                : "[alice] 串联回归电池已启动（" + RegressionBatteryTask.coreStepCount()
                        + " 项，约 2~4 分钟）。"
                        + "⭐ 每一步都会把你传到现场、结束时送回原处；"
                        + "结果看日志 [Regression] SUMMARY。");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
