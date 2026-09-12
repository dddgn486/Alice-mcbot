package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
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

/**
 * **维生出口自检启动器**（{@code alice:survival_exit_check}，S-1 / P1-C）：普通右键，零参数。
 *
 * <p>验的是那条"**谁否决，都得说清那该去哪**"的规矩：把 bot 放进一个**可走出的危险**里
 * （{@code alice_test:survival_course}：脚位正常、**头顶那一格是石头** ⇒ `isInWall()` =
 * `SUFFOCATING`），并起一个**会被维生否决的 dummy 任务**（`WalkToTask`）。然后应当依次出现：
 * <pre>
 * 维生监测: bot=… hazard=SUFFOCATING …
 * 任务因维生危险中断: bot=… reason=survival_suffocating
 * [Survival] 维生中断 ⇒ 逃生出口 refuge=…（距 1.0 格）——启动 SurvivalExitTask
 * task_execution_terminal kind=SurvivalExitTask … terminal=COMPLETED
 * </pre>
 * 肉眼应当看到：bot 从压顶那一格**走出来一步**，站到旁边没有压顶的格子上。
 *
 * <p>⚠ 为什么用"压顶窒息"而不是"泡在岩浆里"：本自检要验的是**出口机制**（否决之后有没有去向）。
 * 岩浆里能不能爬出来取决于流体物理（本内核的 Movement 不建模岩浆游动），
 * 那是**另一个问题**，不该混在这一条的验收里（详见 `docs/OPEN_ITEMS_LEDGER.md` §1 S-1）。
 */
public class SurvivalExitCheckItem extends Item {

    /** 场景中央（= 有压顶的那一格脚位）；与 `alice_test:survival_course` 对齐。 */
    public static final BlockPos HAZARD_FOOT = new BlockPos(66, 64, 104);
    /** dummy 任务的目标（同一平台上、相邻的一格；它其实活不到走过去 —— 会被维生中断掉）。 */
    public static final BlockPos DUMMY_GOAL = new BlockPos(68, 64, 106);

    public SurvivalExitCheckItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, HAZARD_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] bot 正忙，稍后再试");
            return;
        }
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:survival_course");
        bot.teleportTo(level, HAZARD_FOOT.getX() + 0.5D, HAZARD_FOOT.getY(), HAZARD_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        if (!BotManager.assignSurvivalExitCheck(bot, player instanceof ServerPlayer sp ? sp : null, DUMMY_GOAL)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        BotLog.info("[SurvivalExitCheck] 就位 bot={} hazard_foot={}（头顶 {} 是石头 ⇒ SUFFOCATING）；"
                        + "期望：维生中断 → [Survival] 逃生出口 → SurvivalExitTask",
                bot.getName().getString(), HAZARD_FOOT.toShortString(),
                HAZARD_FOOT.above().toShortString());
        say(player, "[alice] 维生出口自检：bot 已放进「头顶压石头」的危险格，"
                + "看着它被中断后**走出这一步**；日志关键词 [Survival] 逃生出口 / kind=SurvivalExitTask");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
