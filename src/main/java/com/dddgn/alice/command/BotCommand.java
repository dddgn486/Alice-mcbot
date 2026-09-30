package com.dddgn.alice.command;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotOwnership;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ftbteams.FtbPartyBinder;
import com.dddgn.alice.compat.ftbteams.FtbTeamsBridge;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.dddgn.alice.protection.ReturnPointData;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * **产品面 `/alice` 命令**（`D-560` 之后：这里只放"发行包里删掉它，玩家会少一个能力"的命令）。
 *
 * <p>⚠️ 本包（`command/`）是**产品面** ⇒ 按 `R3` **不许**依赖 `fixture/`（开发期包）；
 * 开发期/调试子命令住在 {@code debug/DebugCommands}（它们可能发料、可能依赖夹具 ⇒ 归开发期桶）。
 *
 * <p>两个类**各自** {@code dispatcher.register(Commands.literal("alice") ...)} —— Brigadier 的
 * {@code CommandNode.addChild} 对同名子节点**合并**（保留既有节点及其 {@code requires}，只并入孙节点）
 * ⇒ 命令名/参数/权限与劈分前逐字相同。⚠️ 因此**两侧都必须带 `.requires(...)`**（合并**不复制**
 * `requires` ⇒ 谁先注册谁的生效）—— 见 `tools/check-alice-root-requires`（同刀门禁）。
 */
@Mod.EventBusSubscriber(modid = "alice", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotCommand {

    private BotCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("alice")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> spawn(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                // D-319：假人归属 —— `bots` 看列表（含创建者），`adopt` 给**未登记**的假人补创建者
                .then(Commands.literal("bots")
                        .executes(ctx -> listBots(ctx.getSource())))
                .then(Commands.literal("adopt")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> adoptBot(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                // D-321：FTB 身份 —— `status` 只看不写；`bind` **显式**让你的假人继承你的 FTB 队伍
                //（会以你的名义建队 ⇒ 你已有的认领区按 FTB 规则转入该队）；`unbind` 退回。
                .then(Commands.literal("ftb")
                        .then(Commands.literal("status")
                                .executes(ctx -> ftbStatus(ctx.getSource())))
                        .then(Commands.literal("bind")
                                .executes(ctx -> ftbBind(ctx.getSource())))
                        .then(Commands.literal("unbind")
                                .executes(ctx -> ftbUnbind(ctx.getSource()))))
                .then(Commands.literal("come")
                        .executes(ctx -> come(ctx.getSource())))
                .then(Commands.literal("follow")
                        .then(Commands.literal("on").executes(ctx -> followOn(ctx.getSource())))
                        .then(Commands.literal("off").executes(ctx -> followOff(ctx.getSource()))))
                .then(Commands.literal("stop-task")
                        .executes(ctx -> stopTaskCommand(ctx.getSource())))
                // 授权/审批框架的**运行时快照**（零参数、只读；配 docs/authz/OVERVIEW.md）
                .then(Commands.literal("bot-control")
                        .then(Commands.literal("forward")
                                .executes(ctx -> botControlForward(ctx.getSource())))
                        .then(Commands.literal("stop")
                                .executes(ctx -> botControlStop(ctx.getSource())))
                        .then(Commands.literal("jump")
                                .executes(ctx -> botControlJump(ctx.getSource()))))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("region")
                        .then(Commands.literal("info").executes(ctx -> regionInfo(ctx.getSource())))
                        .then(Commands.literal("start").executes(ctx -> regionStart(ctx.getSource())))
                        .then(Commands.literal("stop").executes(ctx -> regionStop(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> regionClear(ctx.getSource())))
                        .then(Commands.literal("idle-stop")
                                .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> regionIdleStop(ctx.getSource(),
                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .executes(ctx -> regionSet(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos1"),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos2"))))))
                        .then(Commands.literal("sapling")
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .executes(ctx -> regionSapling(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "item").toString()))))
                        // ⭐ `D-344` ⑤：**读主手**增删"可配置拾取清单"（零参数 ⇒ 不要求输注册名）
                        .then(Commands.literal("pickup")
                                .then(Commands.literal("add")
                                        .executes(ctx -> regionPickup(ctx.getSource(), true)))
                                .then(Commands.literal("remove")
                                        .executes(ctx -> regionPickup(ctx.getSource(), false)))
                                .then(Commands.literal("list")
                                        .executes(ctx -> regionPickupList(ctx.getSource())))))
                .then(Commands.literal("grant")
                        .executes(ctx -> grantList(ctx.getSource()))
                        .then(Commands.literal("always").executes(ctx -> grantAlways(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> {
                            int cleared = com.dddgn.alice.decision.CollectGrants.clear(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[alice] 已清空收集授权 " + cleared + " 条"), false);
                            return 1;
                        })))
                .then(Commands.literal("policy")
                        .executes(ctx -> policyList(ctx.getSource()))
                        .then(Commands.argument("capability", StringArgumentType.word())
                                .then(Commands.argument("policy", StringArgumentType.word())
                                        .executes(ctx -> policySet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "capability"),
                                                StringArgumentType.getString(ctx, "policy"))))))
                .then(Commands.literal("bot-home")
                        // `D-338` 附注四①（2026-09-19）：**每 bot 一个归位点**，玩家用命令设定。
                        // 零参数：`set` = 以**执行者站位**为归位点；作用对象 = 该维度第一只假人。
                        .then(Commands.literal("set").executes(ctx -> botHome(ctx.getSource(), "set")))
                        .then(Commands.literal("clear").executes(ctx -> botHome(ctx.getSource(), "clear")))
                        .then(Commands.literal("show").executes(ctx -> botHome(ctx.getSource(), "show"))))
                .then(Commands.literal("protect")
                        .then(Commands.literal("add-area")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 1024))
                                                .executes(ctx -> addArea(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("remove-area")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> removeArea(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                        .then(Commands.literal("claim")
                                .then(Commands.argument("chunkX", IntegerArgumentType.integer(-30000000, 30000000))
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-30000000, 30000000))
                                                .executes(ctx -> claimChunk(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "chunkX"),
                                                        IntegerArgumentType.getInteger(ctx, "chunkZ"))))))
                        .then(Commands.literal("unclaim")
                                .then(Commands.argument("chunkX", IntegerArgumentType.integer(-30000000, 30000000))
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-30000000, 30000000))
                                                .executes(ctx -> unclaimChunk(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "chunkX"),
                                                        IntegerArgumentType.getInteger(ctx, "chunkZ"))))))
                        .then(Commands.literal("safe")
                                // `D-338` ②（2026-09-19）：**安全区 = 保护区的子类声明**。
                                // 刻意**零参数**：作用对象 = 执行者**当前所在区块**（资产在脚下 ⇒ 站在里面声明）。
                                // 勾选界面（可多选、可框选）是后续增量，且其操作逻辑先给用户审核。
                                .then(Commands.literal("claim")
                                        .executes(ctx -> safeZone(ctx.getSource(), true)))
                                .then(Commands.literal("unclaim")
                                        .executes(ctx -> safeZone(ctx.getSource(), false))))
                        .then(Commands.literal("add-block")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(ctx -> changeBlockRule(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"), true))))
                        .then(Commands.literal("remove-block")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(ctx -> changeBlockRule(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"), false))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listProtection(ctx.getSource()))))
        );
    }


    /**
     * 认领"该圆**所及**的全部区块"（2026-09-18 起形状 = 区块级 2D 认领，忽略 Y ⇒ 全高度；D-313）。
     *
     * <p>保留这条命令是为了兼容既有用法与"精确复现"；**面向用户的主入口是地图式勾选界面**（D-307 ①-入口）。
     */
    private static int addArea(CommandSourceStack source, BlockPos center, int radius) {
        SafeZoneData data = SafeZoneData.get(source.getServer());
        int added = data.claimCircle(source.getLevel(), center, radius);
        source.sendSuccess(() -> Component.literal("[alice] 已认领 " + added + " 个区块（" + center.toShortString()
                + " 半径 " + radius + " 所及区块；忽略 Y ⇒ 全高度）；当前共 " + data.claimedChunkCount() + " 个区块"), false);
        return 1;
    }


    /** 取消**包含该坐标的那个区块**的认领（区块级认领下"移除"必定是整块移除）。 */
    private static int removeArea(CommandSourceStack source, BlockPos center) {
        SafeZoneData data = SafeZoneData.get(source.getServer());
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        if (!data.unclaimAt(source.getLevel(), center)) {
            source.sendFailure(Component.literal("[alice] 该坐标所在区块未被认领: chunk " + chunkX + ", " + chunkZ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已取消认领区块 " + chunkX + ", " + chunkZ
                + "（当前共 " + data.claimedChunkCount() + " 个区块）"), false);
        return 1;
    }


    /** 精确认领一个区块（管理员诊断 / 精确复现用；用户主入口是勾选界面）。 */
    private static int claimChunk(CommandSourceStack source, int chunkX, int chunkZ) {
        SafeZoneData data = SafeZoneData.get(source.getServer());
        boolean changed = data.claim(source.getLevel(), chunkX, chunkZ);
        source.sendSuccess(() -> Component.literal("[alice] " + (changed ? "已认领" : "本就已认领")
                + " 区块 " + chunkX + ", " + chunkZ + "（忽略 Y ⇒ 全高度；当前共 "
                + data.claimedChunkCount() + " 个区块）"), false);
        return 1;
    }


    /** 精确取消一个区块的认领。 */
    private static int unclaimChunk(CommandSourceStack source, int chunkX, int chunkZ) {
        SafeZoneData data = SafeZoneData.get(source.getServer());
        if (!data.unclaim(source.getLevel(), chunkX, chunkZ)) {
            source.sendFailure(Component.literal("[alice] 区块未被认领: " + chunkX + ", " + chunkZ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已取消认领区块 " + chunkX + ", " + chunkZ
                + "（当前共 " + data.claimedChunkCount() + " 个区块）"), false);
        return 1;
    }


    /**
     * `/alice protect safe claim|unclaim`（`D-338` ②，2026-09-19）：把**执行者当前所在区块**
     * 声明为 / 取消**安全区**（保护区的子类）。
     *
     * <p>刻意**零参数**（AGENTS.md 的测试入口纪律：不许要求玩家输入坐标/长参数）——
     * "站到你的基地里敲一下"就是全部操作；多选/框选留给勾选界面。
     *
     * <p>为什么未认领就**拒绝**而不是顺手认领保护区：那会让"声明安全区"静默扩大资产保护范围
     * （= 静默提权）。这里选择明确拒绝 + 告诉下一步。
     */
    private static int safeZone(CommandSourceStack source, boolean declare) {
        if (!(source.getEntity() instanceof ServerPlayer actor)) {
            source.sendFailure(Component.literal("[alice] 安全区声明必须由玩家在游戏内执行"
                    + "（作用对象 = 他当前所在区块 ⇒ 站在资产里敲）"));
            return 0;
        }
        SafeZoneData data = SafeZoneData.get(source.getServer());
        ServerLevel level = actor.serverLevel();
        int chunkX = actor.chunkPosition().x;
        int chunkZ = actor.chunkPosition().z;
        if (declare) {
            SafeZoneData.SafeDeclare result = data.declareSafe(level, chunkX, chunkZ);
            if (result == SafeZoneData.SafeDeclare.NOT_PROTECTED) {
                source.sendFailure(Component.literal("[alice] 该区块（" + chunkX + ", " + chunkZ
                        + "）**还不是保护区** ⇒ 安全区必须是保护区的子集：先认领保护区，再声明安全区"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("[alice] 安全区：" + chunkX + ", " + chunkZ + " ⇒ "
                    + (result == SafeZoneData.SafeDeclare.DECLARED ? "已声明" : "本就是安全区")
                    + "（安全区 = 保护区的子类；当前安全区 " + data.safeChunkCount() + " 个区块 / 保护区 "
                    + data.claimedChunkCount() + " 个）"), false);
            return 1;
        }
        if (!data.clearSafe(level, chunkX, chunkZ)) {
            source.sendFailure(Component.literal("[alice] 该区块（" + chunkX + ", " + chunkZ
                    + "）不是安全区 ⇒ 无需取消（保护区认领不受影响）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 安全区：已取消 " + chunkX + ", " + chunkZ
                + "（**保护区认领保留**；当前安全区 " + data.safeChunkCount() + " 个区块）"), false);
        return 1;
    }


    /**
     * `/alice bot-home set|clear|show`（`D-338` 附注四①，2026-09-19）：**每 bot 一个归位点**，
     * 由**玩家用命令**设定 —— 它是返程优先级链的**最前项**（**归位点 > 安全区 > 保护区**），
     * 且"有归位点（同维度）⇒ 跳过区几何"。
     *
     * <p>刻意**零参数**（AGENTS.md 的测试入口纪律）：`set` = 以**执行者当前站位**为归位点（默认半径
     * {@link ReturnPointData#DEFAULT_RADIUS}）；作用对象 = 该维度**第一只假人**（多 bot 的名称参数以后再加）。
     * ⚠️ 这也是用户裁定的"小基地 / 想要精确落点"的**正解**：不要去改内部区块几何。
     */
    private static int botHome(CommandSourceStack source, String action) {
        if (!(source.getEntity() instanceof ServerPlayer actor)) {
            source.sendFailure(Component.literal("[alice] 归位点必须由玩家在游戏内设定"
                    + "（作用对象 = 该维度第一只假人，坐标 = 你的站位）"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 该维度没有假人 ⇒ 归位点无人可挂（先 /alice spawn）"));
            return 0;
        }
        ReturnPointData homes = ReturnPointData.get(source.getServer());
        String who = bot.getName().getString();
        if ("show".equals(action)) {
            ReturnPointData.Point point = homes.get(bot.getUUID());
            source.sendSuccess(() -> Component.literal("[alice] 归位点（bot=" + who + "）："
                    + (point == null ? "未设定 ⇒ 返程走区几何（安全区 > 保护区）"
                    : point.describe() + "（到达判据 = XZ 距离 ≤ 半径）")), false);
            return 1;
        }
        if ("clear".equals(action)) {
            boolean cleared = homes.clear(bot.getUUID());
            source.sendSuccess(() -> Component.literal("[alice] 归位点："
                    + (cleared ? "已清除" : "本来就未设定") + "（bot=" + who
                    + "）⇒ 返程回到区几何（安全区 > 保护区）"), false);
            return cleared ? 1 : 0;
        }
        boolean changed = homes.set(bot.getUUID(), actor.serverLevel().dimension().location(),
                actor.blockPosition(), ReturnPointData.DEFAULT_RADIUS);
        ReturnPointData.Point point = homes.get(bot.getUUID());
        source.sendSuccess(() -> Component.literal("[alice] 归位点：" + (changed ? "已设定" : "未变化")
                + " " + (point == null ? "?" : point.describe())
                + "（= 你的站位；返程优先级 **归位点 > 安全区 > 保护区**）"), false);
        return 1;
    }


    private static int changeBlockRule(CommandSourceStack source, String rawId, boolean add) {
        boolean tagRule = rawId.startsWith("#");
        String idText = tagRule ? rawId.substring(1) : rawId;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(idText);
        if (id == null) {
            source.sendFailure(Component.literal("[alice] 方块/标签 ID 格式错误: " + rawId));
            return 0;
        }
        if (tagRule && !source.getLevel().registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
                .getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, id)).isPresent()) {
            source.sendFailure(Component.literal("[alice] 不存在的方块标签: " + rawId));
            return 0;
        }
        if (!tagRule && net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id) == null) {
            source.sendFailure(Component.literal("[alice] 不存在的方块 ID: " + rawId));
            return 0;
        }
        SafeZoneData data = SafeZoneData.get(source.getServer());
        boolean changed = tagRule ? (add ? data.addTag(id) : data.removeTag(id))
                : (add ? data.addBlock(id) : data.removeBlock(id));
        if (!changed) {
            source.sendFailure(Component.literal("[alice] 规则未变化: " + rawId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已" + (add ? "保护 " : "取消保护 ") + rawId), false);
        return 1;
    }


    private static int listProtection(CommandSourceStack source) {
        SafeZoneData data = SafeZoneData.get(source.getServer());
        // `D-338` ③：**当前位置**的两态（保护区/安全区）——这是玩家验证"我声明的到底生效没有"的入口，
        // 也让 `isClaimed`/`isSafe` 这对判据在服务端有真实读者（不是只有夹具在读）。
        if (source.getEntity() instanceof ServerPlayer actor) {
            ServerLevel level = actor.serverLevel();
            BlockPos at = actor.blockPosition();
            // `D-338` 附注四②：**任务区**（工作区域派生的区块级授权封套）——只读展示，
            // 玩家由此能看出"这个区块现在被某个任务覆盖着"（任务存续期内他手改不了它）。
            com.dddgn.alice.protection.TaskZoneRegistry.Zone zone =
                    com.dddgn.alice.protection.TaskZoneRegistry.zoneAt(level, at);
            source.sendSuccess(() -> Component.literal("[alice] 当前位置 " + at.toShortString()
                    + "（区块 " + (at.getX() >> 4) + ", " + (at.getZ() >> 4) + "）：保护区="
                    + data.isClaimed(level, at) + " 安全区=" + data.isSafe(level, at)
                    + " 任务区=" + (zone == null ? "无"
                            : zone.kind() + "（scope=" + zone.scopeId() + "，任务存续期内玩家不可改）")), false);
        }
        source.sendSuccess(() -> Component.literal("[alice] 保护区（父类）/ 安全区（子类）: "
                + data.summary() + "｜内部区块（向中心靠的安全范围）：保护区="
                + data.internalClaims(source.getLevel().dimension().location()).size()
                + " 安全区=" + data.internalSafeClaims(source.getLevel().dimension().location()).size()
                + "（空 = 区域太小 ⇒ 退化为「进区即到」）"), false);
        source.sendSuccess(() -> Component.literal("[alice] 任务区（由任务的工作区域派生，随任务生灭）: "
                + com.dddgn.alice.protection.TaskZoneRegistry.summary(source.getServer())), false);
        return 1;
    }


    /**
     * {@code /alice region set <pos1> <pos2>}：玩家**只划水平范围**（x/z 取两角），
     * 竖直方向**自适应**（基准层取两角较低的 Y，上界由巡查按实测树高收紧）。
     *
     * <p>**重划区域**（与已保存的不同）会重置按旧区域积累的派生记账（目标棵数/界外苗/界外待补种），
     * 见 {@code LumberRegionState#setRegion}；划**同一个**区域是幂等重入，不动记账。
     */
    private static int regionSet(CommandSourceStack source, net.minecraft.core.BlockPos a,
                                 net.minecraft.core.BlockPos b) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        int baseY = Math.min(a.getY(), b.getY());
        var region = new com.dddgn.alice.job.lumber.LumberRegionState.Region(
                a.getX(), a.getZ(), b.getX(), b.getZ(), baseY,
                com.dddgn.alice.job.lumber.LumberRegionState.DEFAULT_MAX_HEIGHT);
        var state = com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        boolean redefined = state.region(bot.getUUID()) != null
                && !state.region(bot.getUUID()).equals(region);
        state.setRegion(bot.getUUID(), region);
        // 正在跑的 Job 持有的是**启动时那一刻**的区域对象（§13.1 一次启动 = 一个区域）：
        // 重划不回灌进运行中的任务，如实说清楚，免得玩家以为"改了没生效"
        boolean running = BotManager.isBusy(bot);
        source.sendSuccess(() -> Component.literal("[alice] 可持续伐木区已设定 " + region.describe()
                + "（x/z 取自两角、baseY 取较低的那个 Y，竖直自适应"
                + (redefined ? "；区域变了 ⇒ 目标棵数重新推导" : "")
                + "；用 /alice region start 启动、/alice region stop 停止）"
                + (running ? " —— 注意：当前有任务在跑，新区域在**下一次 /alice region start** 生效" : "")), false);
        return 1;
    }


    /** {@code /alice region stop}：**显式打断**常驻任务（§13.1：常驻任务只由玩家/决策层打断）。 */
    private static int regionStop(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        String stopped = BotManager.stopTask(bot, "region_stop");
        if (stopped == null) {
            source.sendSuccess(() -> Component.literal("[alice] 当前没有在跑的任务"), false);
            return 1;
        }
        // 打断是常驻任务的正常结束方式，但可能停在"脚手架上/半棵树"的中间态：如实报出未闭合残留
        int residue = BotManager.pendingTemporaryCount(bot);
        source.sendSuccess(() -> Component.literal("[alice] 已停止 " + stopped + "（region_stop）"
                + (residue == 0
                        ? "；账本已闭合（无我方临时方块残留）"
                        : "；**账本仍有 " + residue + " 条我方临时方块未拆**（/alice restore 可清理）")), false);
        return 1;
    }


    /** {@code /alice region idle-stop <true|false>}：是否启用"连续无活即 IDLE_NO_WORK 收工"（默认关=常驻）。 */
    private static int regionIdleStop(CommandSourceStack source, boolean value) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer())
                .setAutoIdleStop(bot.getUUID(), value);
        source.sendSuccess(() -> Component.literal("[alice] 区域任务 idle-stop=" + value
                + (value ? "（无活会自行 IDLE_NO_WORK 收工）" : "（常驻：只由玩家/决策层打断）")), false);
        return 1;
    }


    /** {@code /alice region info}：读区域状态（区域/我种的苗/选定树苗/统计）。 */
    /** {@code /alice region clear}：清掉**已选定区域**（测试/夹具收尾用；区域是玩家划的，只由玩家显式清）。 */
    private static int regionClear(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        com.dddgn.alice.job.lumber.LumberRegionState state =
                com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        boolean had = state.region(bot.getUUID()) != null;
        state.clearRegion(bot.getUUID());
        com.dddgn.alice.log.BotLog.info("region_clear: owner={} had={}", bot.getName().getString(), had);
        source.sendSuccess(() -> Component.literal("[alice] 已清除选定区域（had=" + had + "）"
                + " —— 决策菜单里不会再出现 region:saved"), false);
        return had ? 1 : 0;
    }


    private static int regionInfo(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var state = com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        var region = state.region(bot.getUUID());
        source.sendSuccess(() -> Component.literal("[alice] 可持续伐木区 region="
                + (region == null ? "-（未设定）" : region.describe())
                + " baseline=" + state.baselineTrees(bot.getUUID())
                + " mySaplings=" + state.mySaplingCount(bot.getUUID())
                + " pendingReplant=" + state.pendingReplantCount(bot.getUUID())
                + " saplingItem=" + (state.saplingItem(bot.getUUID()) == null
                        ? "-（未选择，启动时默认 oak）" : state.saplingItem(bot.getUUID()))
                + " chopped=" + state.treesChopped(bot.getUUID())
                + " planted=" + state.saplingsPlanted(bot.getUUID())
                + " patrols=" + state.patrols(bot.getUUID())
                + " autoIdleStop=" + state.autoIdleStop(bot.getUUID())
                + " lastPatrol=" + state.lastPatrolTick(bot.getUUID())), false);
        // `D-338` 附注四②：**任务区预检**（纯查询，不改任何状态）—— 把"工作区域（方块级）⇒ 任务区
        // （区块级最小覆盖）"和"会不会与安全区冲突"在**启动之前**摊给玩家看：
        // 冲突在这里就该被看见，而不是等任务跑起来才失败。
        if (region != null) {
            var server = source.getServer();
            var level = source.getLevel();
            var area = new com.dddgn.alice.protection.TaskZoneRegistry.WorkArea(
                    level.dimension().location(), region.minX(), region.minZ(),
                    region.maxX(), region.maxZ());
            var chunks = area.chunkCover();
            var conflicts = com.dddgn.alice.protection.TaskZoneRegistry.safeZoneConflicts(
                    SafeZoneData.get(server), level.dimension().location(), chunks);
            var active = com.dddgn.alice.protection.TaskZoneRegistry.zoneOf(server, bot.getUUID());
            source.sendSuccess(() -> Component.literal("[alice] 任务区（派生）：工作区域 " + area.describe()
                    + " blocks=" + area.areaXZ() + " ⇒ 区块最小覆盖 chunks=" + chunks.size()
                    + "（**单向派生**：工作区域 ⇒ 任务区）｜冲突="
                    + (conflicts.isEmpty() ? "无（可覆盖保护区父类）"
                            : "⛔ 安全区×" + conflicts.size() + " "
                                    + com.dddgn.alice.protection.TaskZoneRegistry.describeChunks(conflicts)
                                    + " ⇒ 任务会**如实失败**，先 /alice protect safe unclaim 那些区块（显式退化）")
                    + "｜当前生效=" + (active == null ? "无（任务未在跑）"
                            : active.kind() + " chunks=" + active.chunks().size()
                                    + " scope=" + active.scopeId())), false);
        }
        return 1;
    }


    /**
     * {@code /alice region pickup add|remove}：**读主手物品**增删"可配置拾取清单"（`D-344` ⑤ 裁定）。
     *
     * <p><b>为什么读主手而不是收参数</b>：项目纪律要求操作/测试入口**不许长参数串**（输注册名既难记又易错）
     * —— 手里拿着什么就加什么，是零参数且**所见即所得**的做法（与 `alice:collect_grant` 物品同风格）。
     *
     * <p>⚠️ **移不掉"派生项"**（= 当前选定的树苗）：它是**算出来的**（显式项 ∪ 选定树苗），
     * 换树苗要用 {@code /alice region sapling <item>}（`D-344` ④ 的实现方式）。
     */
    private static int regionPickup(CommandSourceStack source, boolean add) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var runner = source.getPlayer();
        if (runner == null) {
            source.sendSuccess(() -> Component.literal(
                    "[alice] 这条要**玩家**执行（指令台没有\u300c主手\u300d可读）"), false);
            return 0;
        }
        var held = runner.getMainHandItem();
        if (held.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[alice] 你的主手是空的 —— 先拿一件要捡的东西（比如树苗/树枝）再执行"), false);
            return 0;
        }
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        var state = com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        boolean changed = add
                ? state.addPickupItem(bot.getUUID(), itemId)
                : state.removePickupItem(bot.getUUID(), itemId);
        var effective = state.effectivePickupItems(bot.getUUID());
        com.dddgn.alice.log.BotLog.info("[alice] 拾取清单 {} item={} changed={} 生效={}",
                add ? "add" : "remove", itemId, changed, effective);
        String tail;
        if (add) {
            tail = changed ? "已加入" : "本来就在清单里（幂等，没重复加）";
        } else {
            tail = changed ? "已移出"
                    : "没移掉：它要么本来就不在，要么是**默许项**（跟着 /alice region sapling 走）";
        }
        source.sendSuccess(() -> Component.literal("[alice] 拾取清单 " + tail + "：" + itemId
                + "\n  生效清单=" + effective + "（区域作业\u300c扫地面\u300d时按它找地上的落物）"), false);
        return 1;
    }


    /** {@code /alice region pickup list}：显示生效清单 + 每项来源（默认 / 手动加）。 */
    private static int regionPickupList(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var state = com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        var effective = state.effectivePickupItems(bot.getUUID());
        StringBuilder text = new StringBuilder("[alice] 区域拾取清单（生效 " + effective.size() + " 项）");
        for (String itemId : effective) {
            text.append("\n  · ").append(itemId).append(state.pickupItemIsDefault(bot.getUUID(), itemId)
                    ? "（默认：跟着 /alice region sapling 走）" : "（手动加：/alice region pickup remove 可移出）");
        }
        if (effective.isEmpty()) {
            text.append("\n  （空 ⇒ 区域作业**不会**扫地面：既没选树苗、也没手动加）");
        }
        text.append("\n  手动加过的=").append(state.pickupItems(bot.getUUID()));
        String out = text.toString();
        source.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }


    /**
     * {@code /alice region sapling <item>}：**选择补种用哪种树苗**（用户 2026-09-12 裁定：
     * 不必与被砍的树一一对应）。写进持久化的区域状态，跨会话有效。
     */
    private static int regionSapling(CommandSourceStack source, String itemId) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            source.sendSuccess(() -> Component.literal("[alice] 未知物品：" + itemId), false);
            return 0;
        }
        boolean isSapling = new net.minecraft.world.item.ItemStack(item)
                .is(net.minecraft.tags.ItemTags.SAPLINGS);
        if (!isSapling) {
            source.sendSuccess(() -> Component.literal("[alice] 只接受树苗类物品（saplings 标签）；"
                    + "收到 " + itemId), false);
            return 0;
        }
        var state = com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        state.setSaplingItem(bot.getUUID(), itemId);
        com.dddgn.alice.log.BotLog.info("[alice] 区域补种树苗选择 = {}", itemId);
        source.sendSuccess(() -> Component.literal("[alice] 区域补种树苗已设为 " + itemId
                + "（欠树时按这个补种；与原来的树不必同种）"), false);
        return 1;
    }


    /** {@code /alice region start}：用**已保存的区域**起区域型伐木 Job（停止：`/alice region stop`，或下其它 /alice 指令替换）。 */
    private static int regionStart(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var state = com.dddgn.alice.job.lumber.LumberRegionState.get(source.getServer());
        var region = state.region(bot.getUUID());
        if (region == null) {
            source.sendSuccess(() -> Component.literal("[alice] 该 bot 还没有区域（用 alice:region_lumber "
                    + "物品在场景里设定，或用命令另行设定）"), false);
            return 0;
        }
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        boolean ok = com.dddgn.alice.bot.BotManager.assignRegionLumber(bot,
                source.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null,
                region);
        source.sendSuccess(() -> Component.literal(ok
                ? "[alice] 可持续伐木区已启动 region=" + region.describe()
                : "[alice] " + BotManager.busyMessage(bot)), false);
        return ok ? 1 : 0;
    }


    /** {@code /alice grant}：列出有效收集授权（`always` 会显式标记）。 */
    private static int grantList(CommandSourceStack source) {
        var grants = com.dddgn.alice.decision.CollectGrants
                .active(source.getServer(), source.getServer().getTickCount());
        if (grants.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[alice] 没有收集授权（用 alice:collect_grant 右键记 pos1、潜行右键记 pos2）"), false);
            return 1;
        }
        for (var grant : grants) {
            boolean always = grant.scope() == com.dddgn.alice.decision.PermissionGate.Scope.ALWAYS;
            source.sendSuccess(() -> Component.literal("[alice] " + (always ? "★always " : "")
                    + grant.describe() + (always ? "（永久授权：bot 会一直捡这片区域里的东西，含玩家物品）" : "")),
                    false);
        }
        return 1;
    }


    /** {@code /alice grant always}：把**最后一条**会话授权提升为永久（持久化 + 报告标记）。 */
    private static int grantAlways(CommandSourceStack source) {
        var grants = com.dddgn.alice.decision.CollectGrants
                .active(source.getServer(), source.getServer().getTickCount());
        if (grants.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可提升的授权（先选区授权）"), false);
            return 0;
        }
        var last = grants.get(grants.size() - 1);
        String by = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                ? "player:" + player.getName().getString() : "console";
        var promoted = com.dddgn.alice.decision.CollectGrants.add(source.getServer(), last.minX(),
                last.minZ(), last.maxX(), last.maxZ(),
                com.dddgn.alice.decision.PermissionGate.Scope.ALWAYS, by, -1);
        // 回执用**新授权**（旧的是会话级、带到期 tick，容易让人误以为"提升没生效"）
        source.sendSuccess(() -> Component.literal("[alice] 已提升为**永久**收集授权 "
                + promoted.describe() + "（报告里会带 ★always 标记）"), false);
        return 1;
    }


    /** {@code /alice policy}：查看能力分级（AUTO/NOTIFY/ASK/IGNORE）。 */
    private static int policyList(CommandSourceStack source) {
        for (var entry : com.dddgn.alice.decision.PermissionGate
                .policyTable(source.getServer()).entrySet()) {
            source.sendSuccess(() -> Component.literal("[alice] " + entry.getKey() + " = " + entry.getValue()), false);
        }
        return 1;
    }


    /** {@code /alice policy <capability> <AUTO|NOTIFY|ASK|IGNORE>}：修改并持久化（always 级）。 */
    private static int policySet(CommandSourceStack source, String capability, String policy) {
        com.dddgn.alice.decision.PermissionGate.Policy parsed;
        try {
            parsed = com.dddgn.alice.decision.PermissionGate.Policy
                    .valueOf(policy.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            source.sendSuccess(() -> Component.literal("[alice] policy 只能是 AUTO/NOTIFY/ASK/IGNORE"), false);
            return 0;
        }
        com.dddgn.alice.decision.PermissionGate.setPolicy(source.getServer(), capability, parsed);
        source.sendSuccess(() -> Component.literal("[alice] " + capability + " = " + parsed
                + "（已持久化；报告里会显式标记 always 级授权）"), false);
        return 1;
    }


    /** 把最近的 bot 叫到玩家身边（场景测试摆位用；零参数）。 */
    private static int come(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[alice] come 需要玩家执行"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            bot = BotManager.firstOrSpawn(source.getLevel(), player.blockPosition());
        }
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        // ⭐ `D-441`：叫它过来 = 送到**你旁边那一格**，**不是**塞进你那一格 ——
        // 玩家不可被推（`fake player` 挤不动真人）⇒ 同格时 bot 会被挤开、也保不住"站得正"。
        BlockPos foot = BotManager.standableCellNear(source.getLevel(), player.blockPosition());
        if (foot == null) {
            source.sendFailure(Component.literal("[alice] 你附近没有可站的位置给它 ⇒ 让开 / 清一块空地再叫"));
            return 0;
        }
        // 摆位传送（允许）：带头部同步的重载，避免头身不一致
        bot.teleportTo(source.getLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        final String name = bot.getName().getString();
        final boolean onPlayer = foot.equals(player.blockPosition());
        final String where = foot.toShortString();
        source.sendSuccess(() -> Component.literal("[alice] " + name + " 已到位 " + where
                + (onPlayer ? "（只有你这一格能站 ⇒ 你让开一格）" : "（在你旁边）")), false);
        return 1;
    }


    /** 输出当前 bot 的任务可回收状态，不改变任务。 */
    private static int status(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 当前维度没有已生成的假人"));
            return 0;
        }
        HazardState hazard = SurvivalSystem.current(bot);
        com.dddgn.alice.bot.TaskExecutionRecord record = BotManager.lastExecutionRecord(bot);
        String current = BotManager.currentTaskSummary(bot);
        String terminal = record == null ? "none" : "kind=" + record.taskKind()
                + " target=" + record.targetDescription() + " terminal=" + record.terminalStatus()
                + " code=" + record.resultCode() + " duration=" + record.durationTicks() + "t"
                + " endPos=" + record.terminalBotPos().toShortString()
                + " recovery=" + record.recoveryState();
        String text = "[alice] " + bot.getName().getString() + " busy=" + BotManager.isBusy(bot)
                + ", current=" + (current == null ? "none" : current)
                + ", latest=" + terminal
                + ", last=" + BotManager.lastTaskResult(bot) + ", pos=" + bot.blockPosition().toShortString()
                + ", hazard=" + hazard.type() + "(" + hazard.durationTicks() + "t)"
                + ", air=" + hazard.airSupply() + ", health=" + hazard.health();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }



    private static int spawn(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        BlockPos pos = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : new BlockPos(level.getSharedSpawnPos());
        BotPlayer bot = BotManager.spawn(level, pos, name, UUID.randomUUID(), source.getPlayer());
        source.sendSuccess(() -> Component.literal(
                "[alice] 假人 " + name + " 已生成于 " + pos.toShortString()
                        + "（创建者=" + BotOwnership.describe(BotOwnership.creatorOfBot(bot)) + "）"), false);
        return 1;
    }


    /** `/alice bots`（D-319）：列出假人 + **创建者**（未登记就写「未登记」，不猜）。 */
    private static int listBots(CommandSourceStack source) {
        java.util.Collection<BotPlayer> bots = BotManager.getAllBots();
        if (bots.isEmpty()) {
            source.sendFailure(Component.literal("[alice] 当前没有假人（/alice spawn <名字>）"));
            return 0;
        }
        for (BotPlayer bot : bots) {
            String line = "[alice] " + bot.getName().getString()
                    + " uuid=" + BotOwnership.shortId(bot.getUUID())
                    + " 创建者=" + BotOwnership.describe(BotOwnership.creatorOfBot(bot))
                    + " dim=" + bot.level().dimension().location()
                    + " @" + bot.blockPosition().toShortString()
                    + (BotManager.isBusy(bot) ? " 忙" : " 闲");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return bots.size();
    }


    /**
     * `/alice ftb status`（D-321）：**只看不写** —— 你的 FTB 队伍 + 每只假人的队伍与它跟你的关系。
     *
     * <p>为什么先有这个：让"现在到底是什么状态"变成一句可以核对的话，再谈要不要写。
     */
    private static int ftbStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (!FtbTeamsBridge.available()) {
            source.sendFailure(Component.literal("[alice] 未检测到 FTB Teams（"
                    + FtbTeamsBridge.unavailableReason() + "）⇒ 队伍相关功能不可用"));
            return 0;
        }
        if (player != null) {
            source.sendSuccess(() -> Component.literal("[alice] FTB 只读：你="
                    + FtbTeamsBridge.describeTeamOf(player)), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "[alice] FTB 只读：命令方块/控制台没有 FTB 身份，只能列假人的队伍"), false);
        }
        java.util.Collection<BotPlayer> bots = BotManager.getAllBots();
        if (bots.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[alice] FTB 只读：当前没有假人"), false);
            return 1;
        }
        for (BotPlayer bot : bots) {
            String relation = player == null ? "-"
                    : (FtbTeamsBridge.sameTeam(player, bot) ? "**同队**" : "不同队");
            String line = "[alice] FTB 只读：" + bot.getName().getString()
                    + " 创建者=" + BotOwnership.describe(BotOwnership.creatorOfBot(bot))
                    + " 队伍=" + FtbTeamsBridge.describeTeamOf(bot)
                    + " 与你的关系=" + relation;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return bots.size();
    }


    /**
     * `/alice ftb bind`（D-321）：把**由你创建**的假人拉进你的 FTB 队伍，让它继承你的身份与权限。
     *
     * <p>⚠️ 副作用（有意为之、且只在你敲这条命令时发生）：你还没有队伍时，Alice 会**以你的名义**建一个
     * party —— FTB Chunks 会把**你已有的认领区块转入该队**（FTB 自己留了原始认领记录，退队时还回）。
     * 因此它**不在 spawn 时自动做**（用户 2026-09-18 裁定）。
     */
    private static int ftbBind(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[alice] 必须由游戏内玩家执行（命令方块/控制台没有 FTB 身份）"));
            return 0;
        }
        List<BotPlayer> mine = botsCreatedBy(player);
        if (mine.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[alice] 你名下还没有假人（先 /alice spawn <名字>；老假人用 /alice adopt <名字> 补登记）"));
            return 0;
        }
        FtbPartyBinder.Result result = FtbPartyBinder.bind(player, mine);
        for (FtbPartyBinder.Step step : result.steps()) {
            source.sendSuccess(() -> Component.literal("[alice] FTB " + step.line()), false);
        }
        if (!result.ok()) {
            source.sendFailure(Component.literal("[alice] FTB 绑定未完全成功："
                    + result.summary()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] FTB 绑定完成："
                + mine.size() + " 只假人已与你要在同一个队伍（party=「" + result.partyName() + "」）"), true);
        return mine.size();
    }


    /** `/alice ftb unbind`（D-321）：反向操作 —— 让假人先退伙、你再退队（FTB 会删掉空队伍）。 */
    private static int ftbUnbind(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[alice] 必须由游戏内玩家执行（命令方块/控制台没有 FTB 身份）"));
            return 0;
        }
        FtbPartyBinder.Result result = FtbPartyBinder.leave(player, botsCreatedBy(player));
        for (FtbPartyBinder.Step step : result.steps()) {
            source.sendSuccess(() -> Component.literal("[alice] FTB " + step.line()), false);
        }
        if (!result.ok()) {
            source.sendFailure(Component.literal("[alice] FTB 解绑未完全成功：" + result.summary()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] FTB 解绑完成（假人已回到自己的队伍）"), true);
        return 1;
    }


    /** **由 `player` 创建**的假人（未登记的假人不算任何人的 —— 与 D-319 同一条口径）。 */
    private static List<BotPlayer> botsCreatedBy(ServerPlayer player) {
        List<BotPlayer> mine = new java.util.ArrayList<>();
        for (BotPlayer bot : BotManager.getAllBots()) {
            BotOwnership.Creator owner = BotOwnership.creatorOfBot(bot);
            if (owner.registered() && player.getUUID().equals(owner.uuid())) {
                mine.add(bot);
            }
        }
        return mine;
    }


    /**
     * `/alice adopt <名字>`（D-319）：把**执行者**登记为这只假人的创建者。
     * **只在未登记时生效**（已有创建者 ⇒ 失败且一个字都不改 —— 认领是单向的）。
     */
    private static int adoptBot(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[alice] 认领必须由游戏内玩家执行（命令方块没有身份）"));
            return 0;
        }
        BotPlayer found = null;
        for (BotPlayer candidate : BotManager.getAllBots()) {
            if (candidate.getName().getString().equalsIgnoreCase(name)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            source.sendFailure(Component.literal("[alice] 没有叫 " + name + " 的假人（/alice bots 看列表）"));
            return 0;
        }
        final BotPlayer target = found;   // 下面有 lambda ⇒ 必须 effectively final
        if (!BotOwnership.adopt(target, player)) {
            source.sendFailure(Component.literal("[alice] " + name + " 的创建者已经是 "
                    + BotOwnership.describe(BotOwnership.creatorOfBot(target))
                    + " ⇒ 不改写（认领是单向的）"));
            return 0;
        }
        BotManager.saveToWorld(target);
        BotLog.info("[Bot] creator 认领 bot={} creator={} creatorUuid={}",
                name, player.getGameProfile().getName(), player.getUUID());
        source.sendSuccess(() -> Component.literal("[alice] 认领成功：" + name + " 的创建者 = "
                + BotOwnership.describe(BotOwnership.creatorOfBot(target))), false);
        return 1;
    }


    /** 开启软移动跟随：只跟随命令执行者本人。 */
    private static int followOn(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("[alice] follow 只能由要被跟随的玩家执行"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(player.serverLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 当前维度没有已生成的 bot"));
            return 0;
        }
        if (BotManager.isBusy(bot)) {
            source.sendFailure(Component.literal("[alice] bot 当前有任务，未开启跟随"));
            return 0;
        }
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        BotManager.assignFollow(bot, player);
        source.sendSuccess(() -> Component.literal("[alice] " + bot.getName().getString()
                + " 开始跟随你（/alice follow off 关闭）"), false);
        return 1;
    }


    private static int followOff(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 当前维度没有已生成的 bot"));
            return 0;
        }
        if (!BotManager.stopFollow(bot)) {
            source.sendFailure(Component.literal("[alice] bot 当前没有跟随任务"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已关闭跟随"), false);
        return 1;
    }

    
    /** BotController 测试：让 Bot 前进 */
    private static int botControlForward(CommandSourceStack source) {
        BotPlayer bot = BotManager.first();
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有 Bot"));
            return 0;
        }
        bot.controller().setForward(1.0F);
        source.sendSuccess(() -> Component.literal("[alice] Bot 前进"), false);
        return 1;
    }

    
    /** BotController 测试：让 Bot 停止 */
    /** K-3：取消当前任务（安全点语义 + 结果如实回报）。 */
    private static int stopTaskCommand(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用 bot"));
            return 0;
        }
        String kind = BotManager.stopTask(bot, "command");
        if (kind == null) {
            source.sendSuccess(() -> Component.literal("[alice] 当前没有任务"), false);
            return 1;
        }
        var session = BotManager.sessionOf(bot);
        String state = session == null ? "-" : session.describeSafeStops();
        source.sendSuccess(() -> Component.literal("[alice] 已请求停止 " + kind
                + "（安全点立即停；空中/已提交位移则延后到安全点）：" + state), false);
        return 1;
    }


    private static int botControlStop(CommandSourceStack source) {
        BotPlayer bot = BotManager.first();
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有 Bot"));
            return 0;
        }
        bot.controller().stopMovement();
        source.sendSuccess(() -> Component.literal("[alice] Bot 停止"), false);
        return 1;
    }

    
    /** BotController 测试：让 Bot 跳跃 */
    private static int botControlJump(CommandSourceStack source) {
        BotPlayer bot = BotManager.first();
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有 Bot"));
            return 0;
        }
        bot.controller().jumpOnce();
        source.sendSuccess(() -> Component.literal("[alice] Bot 跳跃"), false);
        return 1;
    }
}
