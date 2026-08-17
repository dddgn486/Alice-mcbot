package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 测试工具「目标指定器」(alice:target_selector,贴图=钻石斧)。
 * <ul>
 *   <li><b>右键方块</b> → 把挖掘任务派给 bot(bot 不存在则自动生成在目标旁);</li>
 *   <li><b>右键实体</b> → 提示实体攻击行为未实现(下一步)。</li>
 * </ul>
 * 纯测试工具:只做任务指派 + 反馈,不参与玩法平衡。
 */
public class TargetSelector extends Item {

    public TargetSelector(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS; // 客户端只播交互动画,逻辑在服务端
        }
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).isAir()) {
            return InteractionResult.PASS;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        BotPlayer bot = BotManager.firstOrSpawn(serverLevel, pos);
        BotManager.assignTarget(bot, TaskTarget.block(pos));
        Player player = context.getPlayer();
        if (player != null) {
            player.sendSystemMessage(Component.literal(
                    "[alice] 已指派挖掘目标 " + pos.toShortString() + " → " + bot.getName().getString()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity entity, InteractionHand hand) {
        if (entity.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(Component.literal(
                "[alice] 实体目标(" + entity.getName().getString() + ")的攻击行为未实现,下一步再做"));
        return InteractionResult.SUCCESS;
    }
}
