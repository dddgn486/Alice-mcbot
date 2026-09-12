package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.JobRequest;
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
import net.minecraft.world.phys.Vec3;

/**
 * **统一 Job 入口自检**（{@code alice:job_launcher}，D-134 / ② 决策层契约）：普通右键，零参数。
 *
 * <p>验的是"决策层唯一需要的那个动作"：`JobRequest` → `JobLauncher` → `BotManager.assignJob`。
 * 它**不**走任何领域专用入口（`assignLumberJob`/`assignMineJob`/`assignRegionLumber`），
 * 因此日志里应当出现统一路径的痕迹：
 * <pre>
 * [Job] launch bot=… kind=LUMBER center=… radius=16 quota=2 maxTicks=3600
 * … 正常的伐木过程（pick tree / chopped）…
 * task_execution_terminal kind=LumberJob … terminal=COMPLETED code=done
 * task_terminal_reason kind=LumberJob botId=… terminalReason=quota_met     ← J-1：终止理由进记录
 * </pre>
 */
public class JobLauncherItem extends Item {

    /** 自检配额：2 棵（够看出"配额推进 + quota_met"，又不至于跑很久）。 */
    public static final int CHECK_QUOTA = 2;

    public JobLauncherItem(Properties properties) {
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
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] bot 正忙，稍后再试");
            return;
        }
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:lumber_course");
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        JobRequest request = JobRequest.lumber(LumberCourseAnchor.START_FOOT, 16, CHECK_QUOTA, 3600);
        if (!BotManager.assignJob(bot, player instanceof ServerPlayer sp ? sp : null, request)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 统一 Job 入口自检：已用 JobRequest 起 LUMBER（quota=" + CHECK_QUOTA + "）；"
                + "看日志 [Job] launch / task_terminal_reason … terminalReason=quota_met");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
