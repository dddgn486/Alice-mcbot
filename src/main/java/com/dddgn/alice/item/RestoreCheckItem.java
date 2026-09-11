package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.task.RestoreScopeTask;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.core.BlockPos;
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

import java.util.List;

/**
 * 恢复自检启动器（{@code alice:restore_check}，J6-b1b 验收入口）：普通右键，零参数。
 *
 * <p>为什么需要它：恢复任务要"走到目标正上方再向下拆"，而**测试场景按规范是孤立长方体区域**
 * （边界外一圈为空气、场景之间没有路）。回归任务结束时 bot 停在最后一个场景里，
 * 账本里的待恢复方块却在别的场景 → 搜索如实报 `UNREACHABLE`（实测 `descend_precondition=3769`）。
 * 那不是恢复机制的问题，是**夹具没把 bot 送过去**——与伐木/挖矿物品"传送到场景起点"同一职责。
 *
 * <p>本物品：找到账本里**第一条待恢复的 TEMP**，把 bot 传送到它**旁边一个可站的格子**
 * （不是它正上方——那里可能被夹具墙占着），然后启动一次全量恢复任务。
 */
public class RestoreCheckItem extends Item {

    public RestoreCheckItem(Properties properties) {
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
            say(player, "[alice] 没有可用 bot");
            return;
        }
        if (BotManager.isBusy(bot)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        List<WorldModLedger.Entry> pending = WorldModLedger.pendingTemporary(level.getServer(), null);
        if (pending.isEmpty()) {
            say(player, "[alice] 账本无待恢复项（无需自检）");
            return;
        }
        BlockPos first = pending.get(0).pos();
        BlockPos stand = findStandNear(level, first);
        if (stand == null) {
            say(player, "[alice] 待恢复方块 " + first.toShortString() + " 附近没有可站位置，无法自检");
            return;
        }
        bot.teleportTo(level, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[RestoreCheck] 待恢复 {} 条；bot 传送到 {} 旁的可站格 {}",
                pending.size(), first.toShortString(), stand.toShortString());

        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignRestore(bot, observer, true)) {
            say(player, "[alice] 恢复启动失败");
            return;
        }
        say(player, "[alice] 恢复自检已启动（" + pending.size() + " 条待恢复；详见 [Restore] 日志）");
    }

    /** 在目标附近找一个"可站"格：空气 + 上方空气 + 下方有支撑。 */
    private static BlockPos findStandNear(ServerLevel level, BlockPos target) {
        BlockPos[] candidates = {
                target.above(), target.above(2),
                target.offset(1, 1, 0), target.offset(-1, 1, 0),
                target.offset(0, 1, 1), target.offset(0, 1, -1),
                target.offset(1, 0, 0), target.offset(-1, 0, 0),
                target.offset(0, 0, 1), target.offset(0, 0, -1),
        };
        for (BlockPos pos : candidates) {
            if (MovementHelper.canWalkOn(level, pos)
                    && MovementHelper.canWalkThrough(level, pos)
                    && MovementHelper.canWalkThrough(level, pos.above())) {
                return pos;
            }
        }
        return null;
    }

    private static void say(net.minecraft.world.entity.player.Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
