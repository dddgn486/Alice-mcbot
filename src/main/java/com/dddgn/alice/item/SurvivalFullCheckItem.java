package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * **维生全套自检启动器**（{@code alice:survival_full_check}，D-229 新增）。普通右键，零参数。
 *
 * <p>与 {@link SurvivalExitCheckItem}（只管"否决 + 出口"那条路）不同，这个物品把
 * {@code SurvivalExitCheckTask} 这整套夹具挂成会话任务，一次跑完并**在聊天里打一行 SUMMARY**：
 * <pre>
 * ① 决策表（硬/软/冻结/涉水/无危险…）          ② 封闭场景（**无出口** ⇒ 不许乱否决）
 * ③ 真实着火（软危险 + 出口 ⇒ 否决 + 走开）      ④ 掉血必须变成可判读的事件
 * ⑤ 入水后**空气真的被消耗**（溺水可达）         ⑥ 细雪：`ticksFrozen` 累积 → FREEZING → **全冻后真的掉血**
 * </pre>
 *
 * <p>为什么要这个入口（2026-09-15 实测教训）：细雪那条一开始挂在"疾跑 + 右键"上，
 * 而**原版站着不动进不了疾跑状态**（疾跑需要向前移动）⇒ 用户按了两次都落到"硬：窒息"分支，
 * 冻结**一次都没被触发**。改成一个物品 + 普通右键，零参数、点得到。
 *
 * <p>夹具会自己收尾：拆水/拆雪、归零 `ticksFrozen`、清效果、把人送回平台（见任务 {@code finish()}）。
 */
public class SurvivalFullCheckItem extends Item {

    public SurvivalFullCheckItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private void start(Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, com.dddgn.alice.task.SurvivalCourseAnchor.PLATFORM_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        // ⚠️ **先传送、后建场景**：`/fill`（数据包函数同理）在**未加载的区块**里会静默什么都不做
        // （2026-09-15 踩过两次：水/雪没放上）。传送会让玩家区块票据把区块加载起来。
        bot.teleportTo(level, com.dddgn.alice.task.SurvivalCourseAnchor.PLATFORM_FOOT.getX() + 0.5D,
                com.dddgn.alice.task.SurvivalCourseAnchor.PLATFORM_FOOT.getY(),
                com.dddgn.alice.task.SurvivalCourseAnchor.PLATFORM_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "function alice_test:survival_course");
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        bot.clearFire();
        bot.setTicksFrozen(0);
        bot.removeAllEffects();
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
        if (!BotManager.assignSurvivalFixtureCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            // §5.9：不许静默成功 —— 说清是"忙"还是"手上有未结清传输"
            String blocked = BotManager.assignmentBlockReason(bot);
            say(player, blocked.isEmpty()
                    ? "[alice] " + BotManager.busyMessage(bot)
                    : "[alice] 自检**没有起效**：bot 手上有未结清传输 ⇒ " + blocked
                            + "。它挡住了这个 bot 的所有 assign* 通路。换一个假人，"
                            + "或重开一次世界让启动时的结清把它落成终态");
            return;
        }
        BotLog.info("[SurvivalFullCheck] 就位 bot={}（平台角 {}）；期望：整套维生夹具跑完并打一行 SUMMARY",
                bot.getName().getString(),
                com.dddgn.alice.task.SurvivalCourseAnchor.PLATFORM_FOOT.toShortString());
        say(player, "[alice] 维生**全套**自检开始（约 15~20 秒，含：决策表 / 无出口不乱否决 / 真实着火 / 掉血 / "
                + "入水空气消耗 / **细雪冻结**）。结束时会在这里打一行 "
                + "「[Survival] SUMMARY checks=… failures=… → PASS/FAIL」；"
                + "细雪那段看 `hazard=FREEZING` 与「全冻后掉血」，日志关键词 survival_freezing / 掉血 … hazard=FREEZING");
    }

    private void say(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }
}
