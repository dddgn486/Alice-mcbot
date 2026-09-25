package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.Driver;
import com.dddgn.alice.job.fishbone.FishboneTemplate;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * ⭐ **鱼骨作业启动器**（`alice:fishbone_job`，切片 3；`D-438` / 计划 §8）：普通右键，**零参数**。
 *
 * <p><b>为什么要这个物品</b>：切片 2 之前，`FishboneJob` 只有离线夹具能动它 ——
 * 而鱼骨的全部价值（"按模板开挖，不搜索"）只有在**真实地形**里才看得出来。
 * 这个物品把"最后一次交接"降到**一次右键**：`模板 = 你当前位置 + 你朝向`，
 * 尺寸用一组保守默认（主巷 {@value #MAIN_LENGTH} 格 / 支巷间距 {@value #SPUR_SPACING} /
 * 支巷 {@value #SPUR_LENGTH} 格 / {@code ALTERNATE} / 净高 2）。
 *
 * <p><b>零坐标参数是硬要求</b>（`AGENTS.md` 客户端测试规则）：你走到哪、朝哪，就挖到哪。
 * 不要求你输入坐标、不要求你算位置、不要求你搭场景。
 *
 * <p><b>本物品只做三件事</b>（其余全在 `FishboneJob`）：
 * <ol>
 *   <li>把 bot 送到**你站的那一格**（模板起点 = 你当前位置）；</li>
 *   <li>保证快捷栏里有镐（`D-089`：工具必须进快捷栏才选得到）；</li>
 *   <li>把决策层切到 {@code FIXTURE}（否则 LLM 的决策会插进来改目标）并派活。</li>
 * </ol>
 *
 * <p><b>你要看什么</b>（计划 §8）：`[Fishbone] SUMMARY` 一行里的
 * `main=20/20 spurs=4/4`（模板 = 事实）· `outside=0`（没乱挖）· `searchLimit=0` +
 * `searchNodes` 量级（鱼骨的卖点）· `return=ok`（回得来）；外加观感（巷道直不直、卡不卡、
 * 掉落物捡干净没）。
 *
 * <p>⚠️ **这是**真机**入口，会真的改世界**（挖 20 格主巷 + 4 条 5 格支巷）。请站到你**真想挖**的位置再按。
 */
public class FishboneJobItem extends Item {

    /** 主巷长度（计划 §8 的保守默认）。 */
    private static final int MAIN_LENGTH = 20;

    /** 支巷间距（每 5 格开一条 ⇒ 4 条）。 */
    private static final int SPUR_SPACING = 5;

    /** 支巷长度。 */
    private static final int SPUR_LENGTH = 5;

    /** 支巷侧向（交替：一条左、一条右 ⇒ 形状最像鱼骨，也最容易一眼看出方向有没有错）。 */
    private static final FishboneTemplate.SpurSide SIDE = FishboneTemplate.SpurSide.ALTERNATE;

    /**
     * 作业自己的预算（`goal_timeout` 的判据）。
     *
     * <p>取值依据：离线夹具实测 **≈46 tick/单元**（`fishbone_slice2` 的 `ticksPerAdvance`），
     * 本模板 = 20 主巷 + 4×5 支巷 = **40 个单元** ⇒ ≈1840 tick；真机地形更硬 ⇒ 给 4 倍余量。
     */
    private static final int MAX_TICKS = 8000;

    public FishboneJobItem(Properties properties) {
        super(properties);
    }

    /**
     * ⭐ **真机入口的模板工厂**（`D-438`）—— **唯一出处**：物品与离线夹具都调它
     * ⇒ 夹具断言的就是运行时真的会用的那个模板（不是照着常量另抄一份）。
     *
     * @param startFoot 起点脚位（运行时 = **玩家当前脚位**）
     * @param dir       主巷方向（运行时 = **玩家朝向**，`Player#getDirection` 只给水平四向）
     */
    public static FishboneTemplate templateFor(BlockPos startFoot, Direction dir) {
        return FishboneTemplate.spurs(startFoot, dir, MAIN_LENGTH, SPUR_SPACING, SPUR_LENGTH, SIDE);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        start(context.getPlayer(), (ServerLevel) level);
        return InteractionResult.SUCCESS;
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
        if (player == null) {
            return;
        }
        // ⭐ 零参数：模板 = **你当前脚位 + 你朝向**（只许水平四向 ⇒ `getDirection()` 天然满足）
        BlockPos start = player.blockPosition();
        Direction dir = player.getDirection();
        FishboneTemplate template;
        try {
            template = templateFor(start, dir);
        } catch (IllegalArgumentException refused) {
            // 非法即拒绝而不是夹取（计划 §2）—— 这里理论上到不了，但**不许静默**
            say(player, "[alice] 鱼骨模板参数非法：" + refused.getMessage());
            return;
        }

        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, start);
        }
        if (bot == null) {
            say(player, "[alice] bot 生成失败，请检查日志");
            return;
        }
        // 夹具职责：把 bot 送到模板起点（= 你站的那一格）——
        // 鱼骨是"从**这里**往外挖"，不传送的话起点就是 bot 原来站的地方，与你的视角无关。
        bot.teleportTo(level, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        // 夹具职责：镐（`D-089`：工具必须进快捷栏才选得到）
        FixtureToolKit.ensurePickaxe(bot);
        // 决策层切 FIXTURE：否则 LLM 的决策会插进来改目标（与 `MineJobItem` 同一处置）
        Driver.set(bot, Driver.FIXTURE);

        if (!BotManager.assignFishboneJob(bot, template, MAX_TICKS)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        BotLog.info("[FishboneJobItem] 启动 fishbone Job template={} maxTicks={} by={}",
                template.describe(), MAX_TICKS, player.getName().getString());
        say(player, "[alice] 鱼骨作业启动：方向 " + dir.getName() + " · 主巷 " + MAIN_LENGTH
                + " 格 · 支巷 " + template.spurBranches() + " 条 × " + SPUR_LENGTH + " 格 · 净高 "
                + template.height() + " · 起点 " + start.toShortString());
        say(player, "[alice] 挖完会自己回起点。看聊天/日志的 [Fishbone] SUMMARY 行（"
                + "main=…/… spurs=…/… outside=0 searchLimit=0 return=ok）");
    }

    private static void say(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
