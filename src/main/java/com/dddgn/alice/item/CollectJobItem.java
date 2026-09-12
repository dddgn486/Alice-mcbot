package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.task.LumberCourseAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * **掉落物搜索 + 捡拾自检**（{@code alice:collect_job}，D-137）：普通右键，零参数。
 *
 * <p>夹具：在 bot 旁边 4 格处**生成 3 堆圆石掉落物**，把它们**登记为我方掉落物**
 * （`ScopeBuffer.adoptExistingDrops`）⇒ 然后起 {@link JobRequest.Kind#COLLECT}
 * （`anyDrops=false`，即**安全默认的那条路**：只捡我方登记过的）。
 *
 * <p>期望：`[Job] collect pick cluster@…` → `[Job] collect cluster@… done collected=…`
 * → 全部捡完 ⇒ `[Job] collect SUMMARY reason=collected …`；**玩家自己的东西一律不动**。
 */
public class CollectJobItem extends Item {

    /** 掉落物生成位置：bot 起点旁边 4 格（同一平台上）。 */
    public static final BlockPos DROP_CENTER = LumberCourseAnchor.START_FOOT.offset(4, 0, 0);
    /** 生成几堆。 */
    public static final int STACKS = 3;

    public CollectJobItem(Properties properties) {
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

        // 夹具造掉落物（不经过破坏事件 ⇒ 需要显式登记为我方掉落物，走安全默认那条路）
        for (int i = 0; i < STACKS; i++) {
            ItemEntity drop = new ItemEntity(level,
                    DROP_CENTER.getX() + 0.5D + i * 0.4D, DROP_CENTER.getY() + 0.5D,
                    DROP_CENTER.getZ() + 0.5D,
                    new ItemStack(Items.COBBLESTONE, 8));
            drop.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(drop);
        }
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            say(player, "[alice] bot 会话未就绪");
            return;
        }
        session.scope().begin(DROP_CENTER, 12, bot.getUUID());
        int adopted = session.scope().adoptExistingDrops(level, DROP_CENTER, 12);
        if (!BotManager.assignJob(bot, player instanceof ServerPlayer sp ? sp : null,
                JobRequest.collect(DROP_CENTER, 16, 8 * STACKS, 1200))) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 捡拾自检：已生成 " + STACKS + " 堆掉落物并登记为我方掉落物（adopted="
                + adopted + "）；看 [Job] collect pick/done/SUMMARY。**玩家自己的东西不会动**");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
