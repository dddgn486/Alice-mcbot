package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.config.FishboneConfig;
import com.dddgn.alice.decision.Driver;
import com.dddgn.alice.job.fishbone.FishboneTemplate;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
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

/**
 * ⭐ **鱼骨作业启动器**（`alice:fishbone_job`，切片 3；`D-438` / 计划 §8）：普通右键，**零参数**。
 *
 * <p><b>为什么要这个物品</b>：切片 2 之前，`FishboneJob` 只有离线夹具能动它 ——
 * 而鱼骨的全部价值（"按模板开挖，不搜索"）只有在**真实地形**里才看得出来。
 * 这个物品把"最后一次交接"降到**一次右键**：`起点 = bot 自己的脚位` + `方向 = 你的朝向`。
 *
 * <p>⭐ <b>起点是"bot 站在哪"，不是"你站在哪"</b>（`D-441`，2026-09-25 真机裁定）：
 * 切片 3 原设计是"把 bot 送到**你站的那一格**"，真机实测撞墙 —— 你人就在那一格里，
 * bot 被放到同一格后**挤不动你**（玩家不可推），于是它既站不稳、也回不到起点。
 * 现在的口径：**bot 从它自己脚下往外挖**，你站在旁边瞄准方向、看结果就行。
 *
 * <p><b>零坐标参数是硬要求</b>（`AGENTS.md` 客户端测试规则）：把 bot 开到哪、你朝哪，就从哪往哪挖。
 * 不要求你输入坐标、不要求你算位置、不要求你搭场景。
 *
 * <p>⭐ <b>尺寸在 `config/alice-fishbone.toml`</b>（切片 4，用户 2026-09-25 裁定
 * 「主巷深和子巷深做成可配置的」）：物品本身仍然零参数，尺寸**在点击之前**就定好。
 * 默认 = 主巷 {@value FishboneConfig#DEFAULT_MAIN_LENGTH} /
 * 中心距 {@value FishboneConfig#DEFAULT_SPUR_SPACING} / 支巷长
 * {@value FishboneConfig#DEFAULT_SPUR_LENGTH} / {@code BOTH}（左右对称）/ 净高 2
 * ⇒ **6 个位置 × 2 侧 = 12 条肋 × 32 格** = 404 单元 / 808 格。
 *
 * <p><b>本物品只做四件事</b>（其余全在 `FishboneJob`）：
 * <ol>
 *   <li>选出**离你最近的那只 bot**（一只都没有时才生成 —— 与你手上的
 *       `alice:bot_remote_control` 选同一只，免得"我遥控的是 A、开工的却是 B"）；</li>
 *   <li>把**它自己的脚位**当模板起点（`originFor`；站不住就**拒绝开工**，不传送、不夹取）；</li>
 *   <li>保证快捷栏里有镐（`D-089`：工具必须进快捷栏才选得到）；</li>
 *   <li>把决策层切到 {@code FIXTURE}（否则 LLM 的决策会插进来改目标）并派活。</li>
 * </ol>
 *
 * <p>⚠️ 因此**本物品不再传送 bot**（`D-441`）：想让起点换地方，用遥控器把 bot 开过去、
 * 或 `/alice come` 叫它到你**旁边**，再按一次右键。
 *
 * <p><b>你要看什么</b>（计划 §8）：`[Fishbone] SUMMARY` 一行里的
 * `main=…/… spurs=…/…`（模板 = 事实）· `outside=0`（没乱挖）· `searchLimit=0` +
 * `searchNodes` 量级（鱼骨的卖点）· `return=ok`（回得来）；外加观感（巷道直不直、卡不卡、
 * 矿簇挖干净没、掉落物捡没捡）。
 *
 * <p>⚠️ **这是**真机**入口，会真的改世界**（默认形状 = 808 格）。请站到你**真想挖**的位置再按。
 * ⚠️ 长作业**没有断点续跑**：想停就停（关客户端 / 退出存档），已挖的部分留在世界里。
 */
public class FishboneJobItem extends Item {

    /**
     * 作业预算（`goal_timeout` 的判据）—— ⭐ **从模板推导，不是常数**（切片 4）。
     *
     * <p>取值依据（真机实测，不是拍脑袋）：`latest.log` 2026-09-25 12:16~12:17 那轮
     * `main=20 spurSpacing=5 spurLen=5 ALTERNATE`（40 单元）的相邻单元时间差 ≈ **2.35 秒/单元
     * ≈ 47 tick/单元**，与离线夹具的 46 tick/单元一致 ⇒ 用 **200 tick/单元**（≈ 4.3 倍余量）
     * 加一段固定开销（PREPARE + COLLECT + RETURN）。
     *
     * <p>为什么不做成配置项：它是**由形状推出来的**，写死一个数就会在"你把支巷改成 64 格"之后
     * 悄悄变成 `goal_timeout`；推导出来的预算**不会忘**。
     */
    public static int maxTicksFor(FishboneTemplate template) {
        return 200 * template.advanceCells() + 6000;
    }

    public FishboneJobItem(Properties properties) {
        super(properties);
    }

    /**
     * ⭐ **真机入口的模板工厂**（`D-438` / `D-439`）—— **唯一出处**：物品与离线夹具都调它
     * ⇒ 夹具断言的就是运行时真的会用的那个模板（不是照着常量另抄一份）。
     *
     * <p>尺寸来自 {@link FishboneConfig}（`config/alice-fishbone.toml`）；非法组合由
     * {@link FishboneTemplate} 的构造器**拒绝**（计划 §2），本方法**不夹取、不兜底**。
     *
     * @param startFoot 起点脚位（运行时 = **bot 自己的脚位**，见 {@link #originFor}；`D-441`）
     * @param dir       主巷方向（运行时 = **玩家朝向**，`Player#getDirection` 只给水平四向）
     */
    public static FishboneTemplate templateFor(BlockPos startFoot, Direction dir) {
        return new FishboneTemplate(startFoot, dir,
                FishboneConfig.mainLength(), FishboneConfig.spurSpacing(),
                FishboneConfig.spurLength(), FishboneConfig.side(), FishboneConfig.height());
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
        // ⭐ 零参数：**方向 = 你的朝向**（你朝哪，它往哪挖）；**起点 = bot 自己的脚位**（`D-441`）
        Direction dir = player.getDirection();
        BotPlayer bot = resolveBot(level, player);
        if (bot == null) {
            return; // 原因已在 resolveBot 里说过
        }
        BlockPos origin = originFor(level, bot);
        if (origin == null) {
            // 宁可**当场拒绝**，也不把作业开到站不住的地方（`PREPARE` 用的是同一个谓词 `canStandCentered`，
            // 迟早会以 `start_unreachable` 失败 —— 但那时你已经白等一个客户端轮）
            say(player, "[alice] bot「" + bot.getName().getString() + "」站的 "
                    + MovementHelper.footCell(level, bot).toShortString()
                    + " 不能站（悬空 / 头位被挡 / 在流体里）⇒ 没有开工。让它站稳再按一次");
            return;
        }
        BlockPos playerCell = player.blockPosition();
        if (playerCell.equals(origin) || playerCell.equals(origin.above())) {
            // 你与 bot 抢同一格 = 本轮修掉的那个缺陷的**剩下那一半**：它会把你挤出去 / 被你挤出去，
            // 而"站得正"（水平距中心 ≤0.3）是就地挖的硬前提 ⇒ 这一轮必然白跑
            say(player, "[alice] 你就站在 bot 占的那两格（" + origin.toShortString()
                    + "）里 ⇒ 让开一格再按。它会从**自己脚下**往外挖，不用你站到起点上");
            return;
        }
        FishboneTemplate template;
        try {
            template = templateFor(origin, dir);
        } catch (IllegalArgumentException refused) {
            // 非法即拒绝而不是夹取（计划 §2 / `config/alice-fishbone.toml` 写错时走这里）
            say(player, "[alice] 鱼骨模板参数非法（检查 config/alice-fishbone.toml）：" + refused.getMessage());
            return;
        }
        int maxTicks = maxTicksFor(template);

        // 开工前把遥控输入停掉：否则你按着 W 的那只手会与 Job 自己的走位打架
        //（切片 3 的"把 bot 传送到起点"顺带做了这件事，现在起点不再搬动 bot，单独留着）
        bot.controller().stopMovement();
        // 夹具职责：镐（`D-089`：工具必须进快捷栏才选得到）
        FixtureToolKit.ensurePickaxe(bot);
        // 决策层切 FIXTURE：否则 LLM 的决策会插进来改目标（与 `MineJobItem` 同一处置）
        Driver.set(bot, Driver.FIXTURE);

        if (!BotManager.assignFishboneJob(bot, template, maxTicks)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        BotLog.info("[FishboneJobItem] 启动 fishbone Job template={} maxTicks={} by={} bot={}",
                template.describe(), maxTicks, player.getName().getString(), bot.getName().getString());
        say(player, "[alice] 鱼骨作业启动（bot「" + bot.getName().getString() + "」）：起点 = 它自己脚下 "
                + origin.toShortString() + "（离你 "
                + Math.round(Math.sqrt(origin.distSqr(playerCell))) + " 格）· 方向 "
                + dir.getName() + " · 主巷 " + template.mainLength()
                + " 格 · 支巷 " + template.spurBranches() + " 条 × " + template.spurLength()
                + " 格（" + template.side() + "，中心距 " + template.spurSpacing() + "）· 净高 "
                + template.height() + " · 单元 " + template.advanceCells());
        say(player, "[alice] 挖完会自己回起点（预算 " + maxTicks + " tick，真机约 "
                + Math.round(template.advanceCells() * 2.35D / 60.0D) + " 分钟；随时可以停）。"
                + "看聊天/日志的 [Fishbone] SUMMARY 行");
    }

    /**
     * ⭐ **起点裁定**（`D-441`，2026-09-25 用户真机裁定）：模板起点 = **bot 自己的脚位**。
     *
     * <p><b>为什么不是"你站的那一格"</b>（切片 3 的原设计）：真机实测 —— 你人就在起点那一格里，
     * bot 被送到同一格后**挤不动你**（玩家不可推）⇒ 它既站不稳、也回不到起点。
     * 现在的口径：**bot 从它自己脚下往外挖**，你在旁边瞄准方向就行。
     *
     * <p>脚位一律用 `MovementHelper.footCell`（`D-105`：不许用原版 `blockPosition()` 顶替 ——
     * 半砖 / 台阶上两者不一致）。
     *
     * @return 可用作模板起点的脚位；bot 站的这格**不可站**（悬空 / 头位被挡 / 流体）时返回
     *         {@code null} ⇒ 调用方**拒绝开工**，不猜、不夹取、不传送
     */
    public static BlockPos originFor(ServerLevel level, BotPlayer bot) {
        BlockPos foot = MovementHelper.footCell(level, bot);
        return MovementHelper.canStandCentered(level, foot) ? foot : null;
    }

    /**
     * 取**承担这次作业的那只 bot**：离你最近的那只；一只都没有才生成一只。
     *
     * <p>"最近"与 {@code alice:bot_remote_control} 的口径一致（两个入口选同一只 bot，
     * 否则会出现"我遥控的是 A，开工的却是 B"）。
     */
    private static BotPlayer resolveBot(ServerLevel level, Player player) {
        BotPlayer bot = BotManager.findNearestBot(level, player.blockPosition());
        if (bot != null) {
            return bot;
        }
        // ⭐ 生成格 = "你旁边那一格"（`D-441`：**不许**生成在你那一格里 —— 那就是"挤不动你"的现场）
        BlockPos cell = BotManager.standableCellNear(level, player.blockPosition());
        if (cell == null) {
            say(player, "[alice] 附近没有 bot，也没有能站的地方给它生成 ⇒ 先清出一块空地再按");
            return null;
        }
        bot = BotManager.firstOrSpawn(level, cell.below()); // 生成在 cell 里（`firstOrSpawn` 落在 `pos.above()`）
        if (bot == null) {
            say(player, "[alice] bot 生成失败，请检查日志");
        }
        return bot;
    }

    private static void say(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
