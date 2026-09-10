package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanRouteSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;

/**
 * 岩浆路线安全检查器（{@code alice:pathing_lava_guard}，D-057）。
 *
 * <p>`lava_course` 的原始断言是"无路可走 → UNREACHABLE"；PILLAR/PLACE_STEP 补全后，
 * 规划器会给出**合法且安全**的路线（抬高一格后在岩浆上方搭桥，站在自建方块上通过）。
 * 因此本检查改为断言**安全属性**：路线中 bot 身体占据格与支撑格不得含岩浆。
 *
 * <p>允许 `REACHED`（只要不接触岩浆），也允许 `UNREACHABLE`（同样安全）。
 */
public class PathingLavaGuardItem extends Item {

    /** 与场景函数一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(4, 64, 66);

    public PathingLavaGuardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return start(context.getPlayer(), (ServerLevel) level);
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private InteractionResult start(Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, COURSE_START_FOOT);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        bot.teleportTo(level, COURSE_START_FOOT.getX() + 0.5D, COURSE_START_FOOT.getY(),
                COURSE_START_FOOT.getZ() + 0.5D, Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        ensureCobblestone(bot, 8);

        BlockPos startFoot = bot.blockPosition().immutable();
        PathRequest request = PathRequest.withWorldModification(
                bot.getUUID().toString(), startFoot, COURSE_GOAL_FOOT, "item:pathing-lava-guard");
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        List<BlockPos> contacts = PlanRouteSafety.lavaContacts(plan, level);
        boolean pass = contacts.isEmpty();
        String first = plan.movements().isEmpty()
                ? "-" : plan.movements().get(0).movementType().name();
        BotLog.info("[LavaGuard] status={} first={} movements={} lava_contacts={} result={} from={} to={}",
                plan.status(), first, plan.movements().size(), contacts.size(),
                pass ? "PASS" : "FAIL", startFoot.toShortString(), COURSE_GOAL_FOOT.toShortString());
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] 岩浆路线检查 status=" + plan.status()
                    + " first=" + first + " movements=" + plan.movements().size()
                    + " lava_contacts=" + contacts.size()
                    + (pass ? " → PASS（路线不接触岩浆）"
                            : " → FAIL（路线进入岩浆，见日志 [LavaGuard]）")));
        }
        return InteractionResult.SUCCESS;
    }

    private static void ensureCobblestone(BotPlayer bot, int count) {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
                have += inventory.getItem(slot).getCount();
            }
        }
        if (have >= count) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, count - have));
                return;
            }
        }
        inventory.add(new ItemStack(Items.COBBLESTONE, count - have));
    }
}
