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
 * <p>验的是那条"**谁否决，都得说清那该去哪**"的规矩：把 bot 放进一个**可走出的危险**里，
 * 并起一个**会被维生否决的 dummy 任务**（`WalkToTask`）。两种模式（**零参数**，靠潜行切换）：
 * <pre>
 * 右键       = 窒息（SUFFOCATING，硬危险）：`alice_test:survival_course`
 *              脚位正常、**头顶那一格是石头** ⇒ `isInWall()`
 * 潜行+右键  = 着火（ON_FIRE，**软危险**，S-5 新增）：同一场景 + 把 bot 点着 8 秒
 *              ⇒ 验"过宽限期 + 有出口 ⇒ 否决"这条新判据（硬危险那条通路另有一套）
 * </pre>
 * 两种模式都应当依次出现：
 * <pre>
 * 维生监测: bot=… hazard=SUFFOCATING|ON_FIRE …
 * 任务因维生危险中断: bot=… reason=survival_suffocating|survival_on_fire
 * [Survival] 维生中断 ⇒ 逃生出口 refuge=…（距 1.0 格）——启动 SurvivalExitTask
 * task_execution_terminal kind=SurvivalExitTask … terminal=COMPLETED
 * </pre>
 * 肉眼应当看到：bot 从压顶那一格**走出来一步**（或从火里走开一步），站到旁边安全的格子上。
 *
 * <p>⚠ 为什么用"压顶窒息"而不是"泡在岩浆里"：本自检要验的是**出口机制**（否决之后有没有去向）。
 * 岩浆里能不能爬出来取决于流体物理（本内核的 Movement 不建模岩浆游动），
 * 那是**另一个问题**，不该混在这一条的验收里（详见 `docs/OPEN_ITEMS_LEDGER.md` §1 S-1）。
 *
 * <p>⚠ 为什么"软危险"要单独一条：S-5 之前溺水/着火**只报不拦**（不否决），所以这条通路在
 * "有出口"和"无出口"下的行为**不同**（无出口时**故意不否决**，见
 * {@code docs/OPEN_ITEMS_LEDGER.md} §5.8）—— 后者在电池里已由 `survival_exit` 步离线覆盖，
 * 前者只能在这里真人看。
 */
public class SurvivalExitCheckItem extends Item {

    /** 着火模式的燃烧秒数（够撑过软危险宽限期 `SurvivalSystem.SOFT_HAZARD_GRACE_TICKS`）。 */
    public static final int ON_FIRE_SECONDS = 8;

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
        // 模式选择**零参数**：潜行 = 软危险（着火）；**疾跑 = 冻结**（细雪，D-229）；否则 = 硬危险（窒息）
        boolean onFire = player != null && player.isShiftKeyDown();
        boolean freezing = !onFire && player != null && player.isSprinting();
        // ⚠️ 软危险**不能**站进压顶那一格：`classify` 的顺序是 岩浆 → 窒息 → 缺氧 → 着火 ⇒
        // 那里永远只会被判成 SUFFOCATING，着火那条通路根本轮不到。所以软危险站在**安全的角格**上。
        // 冻结模式站进**封闭石壳**（无出口）：那样判成 FREEZING 后是"HOLD_NO_EXIT ⇒ 不否决"，
        // 与"软危险无出口不乱否决"的口径一致（逃生/否决那条路由着火模式演示）。
        BlockPos hazardFoot = onFire ? com.dddgn.alice.task.SurvivalCourseAnchor.PLATFORM_FOOT
                : freezing ? com.dddgn.alice.task.SurvivalCourseAnchor.SEALED_FOOT
                : com.dddgn.alice.task.SurvivalCourseAnchor.HAZARD_FOOT;
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, hazardFoot);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        var server = level.getServer();
        var source = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, "function alice_test:survival_course");
        if (freezing) {
            // 封闭石壳 + 1×2 细雪：让 bot 真的在细雪里累积 `ticksFrozen`（全冻 140 tick ≈ 7 秒后每 2 秒掉 1 血）。
            // ⚠️ 顺序：**先传送（加载区块）再 fill**，否则 `/fill` 在未加载区块里静默什么都不做。
            bot.teleportTo(level, hazardFoot.getX() + 0.5D, hazardFoot.getY(), hazardFoot.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            server.getCommands().performPrefixedCommand(source, "function alice_test:survival_sealed_course");
            server.getCommands().performPrefixedCommand(source,
                    "fill " + hazardFoot.getX() + " " + hazardFoot.getY() + " " + hazardFoot.getZ() + " "
                            + hazardFoot.getX() + " " + (hazardFoot.getY() + 1) + " " + hazardFoot.getZ()
                            + " minecraft:powder_snow");
        }
        bot.teleportTo(level, hazardFoot.getX() + 0.5D, hazardFoot.getY(), hazardFoot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        bot.clearFire();
        bot.setTicksFrozen(0);
        bot.removeAllEffects();
        if (onFire) {
            bot.setSecondsOnFire(ON_FIRE_SECONDS);
        }
        if (!BotManager.assignSurvivalExitCheck(bot, player instanceof ServerPlayer sp ? sp : null,
                com.dddgn.alice.task.SurvivalCourseAnchor.DUMMY_GOAL)) {
            // A（§5.9）：**不许静默成功** —— 说清是"忙"还是"被未结清传输挡住"（后者以前会打印"就位"骗人）
            String blocked = BotManager.assignmentBlockReason(bot);
            say(player, blocked.isEmpty()
                    ? "[alice] " + BotManager.busyMessage(bot)
                    : "[alice] 自检**没有起效**：bot 手上有未结清传输 ⇒ " + blocked
                            + "。它挡住了这个 bot 的所有 assign* 通路（walk/维生自检…）。"
                            + "换一个假人，或重开一次世界让启动时的结清把它落成终态");
            return;
        }
        BotLog.info("[SurvivalExitCheck] 就位 bot={} hazard_foot={} mode={}；期望：维生中断 →"
                        + " [Survival] 逃生出口 → SurvivalExitTask",
                bot.getName().getString(), hazardFoot.toShortString(),
                onFire ? "ON_FIRE(软)" : freezing ? "FREEZING(冻结)" : "SUFFOCATING(硬)");
        int grace = com.dddgn.alice.survival.SurvivalSystem.SOFT_HAZARD_GRACE_TICKS;
        say(player, freezing
                ? "[alice] 维生冻结自检（**细雪，无出口**）：bot 在封闭石壳里被细雪埋住，"
                        + "`ticksFrozen` 累积到 " + com.dddgn.alice.survival.SurvivalSystem.FREEZE_WARN_TICKS
                        + " 即判成 FREEZING（无出口 ⇒ **不否决**）；全冻（140 tick ≈ 7 秒）后"
                        + "**每 2 秒掉 1 血**。日志关键词 hazard=FREEZING / 掉血 … hazard=FREEZING。"
                        + "想看**否决+逃生**请用潜行右键（着火）"
                : onFire
                ? "[alice] 维生出口自检（**软危险**：着火 " + ON_FIRE_SECONDS + " 秒 + 有出口）："
                        + "过 " + grace
                        + " tick 宽限后应被中断并走开一步；日志关键词 reason=survival_on_fire / [Survival] 逃生出口"
                : "[alice] 维生出口自检（硬危险：头顶压石头）：bot 应被中断后**走出这一步**；"
                        + "日志关键词 reason=survival_suffocating / [Survival] 逃生出口。"
                        + "潜行右键可改验**着火**（软危险），**疾跑右键**可改验**细雪冻结**（无出口 ⇒ 不否决 + 全冻掉血）");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
