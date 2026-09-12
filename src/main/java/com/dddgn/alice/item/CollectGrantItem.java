package com.dddgn.alice.item;

import com.dddgn.alice.decision.CollectGrants;
import com.dddgn.alice.decision.PermissionGate;
import net.minecraft.ChatFormatting;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **收集授权选区器**（{@code alice:collect_grant}，S3.5 第二步 / D-138 裁定第 3 条）：零参数。
 *
 * <p>用法（**只划水平范围，竖直自适应** —— 沿用 D-130 的区域语义）：
 * <ul>
 *   <li>**右键**：记下第一个角（pos1）；</li>
 *   <li>**潜行 + 右键**：记第二个角（pos2）⇒ 立刻生成一条授权，范围 = 两角的水平矩形；</li>
 * </ul>
 * 默认范围是 **`SESSION`**（本次服务器会话有效）；要**永久**授权用
 * {@code /alice grant always}（会持久化，并且**报告里会显式标记 always** —— 用户裁定第 4 条）。
 *
 * <p>授权后，落在该范围内的掉落物归属变为 {@code GRANTED_AREA}（默认策略 `AUTO`）⇒
 * **主动收集与被动拾取两条路都放行**，于是"派 bot 去把那片东西捡了"这条真实需求有了正规出口。
 */
public class CollectGrantItem extends Item {

    /** 每个玩家各自的 pos1（内存即可：选区是瞬时操作）。 */
    private static final Map<UUID, BlockPos> FIRST_CORNER = new HashMap<>();

    public CollectGrantItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            select(player, context.getClickedPos(), player.isShiftKeyDown(), (ServerLevel) level);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        if (player instanceof ServerPlayer serverPlayer) {
            // 空中右键：用玩家**脚下**当角（"就在这里"更直观）
            select(serverPlayer, serverPlayer.blockPosition(), serverPlayer.isShiftKeyDown(),
                    (ServerLevel) level);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private void select(ServerPlayer player, BlockPos pos, boolean secondCorner, ServerLevel level) {
        BlockPos immutable = pos.immutable();
        if (!secondCorner) {
            FIRST_CORNER.put(player.getUUID(), immutable);
            say(player, ChatFormatting.YELLOW + "[alice] 收集授权：pos1 = " + immutable.toShortString()
                    + "（再**潜行右键**记 pos2 并生效；只划水平范围，竖直自适应）");
            return;
        }
        BlockPos first = FIRST_CORNER.remove(player.getUUID());
        if (first == null) {
            say(player, ChatFormatting.RED + "[alice] 还没记 pos1（先普通右键记第一个角）");
            return;
        }
        var grant = CollectGrants.add(level.getServer(), first.getX(), first.getZ(), immutable.getX(),
                immutable.getZ(), PermissionGate.Scope.SESSION, "player:" + player.getName().getString(),
                20 * 600);
        say(player, ChatFormatting.GREEN + "[alice] 收集授权已生效（" + grant.describe()
                + "）；范围内掉落物会按 GRANTED_AREA 处理（主动/被动都放行）");
        say(player, ChatFormatting.GRAY + "  要永久：/alice grant always（会持久化，报告里会标记）；"
                + "查看/清空：/alice grant / /alice grant clear");
    }

    private static void say(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
