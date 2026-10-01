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
import com.dddgn.alice.protection.AreaData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
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
        AreaData data = AreaData.get(source.getServer());
        int added = data.claimCircle(source.getLevel(), center, radius);
        source.sendSuccess(() -> Component.literal("[alice] 已认领 " + added + " 个区块（" + center.toShortString()
                + " 半径 " + radius + " 所及区块；忽略 Y ⇒ 全高度）；当前共 " + data.claimedChunkCount() + " 个区块"), false);
        return 1;
    }


    /** 取消**包含该坐标的那个区块**的认领（区块级认领下"移除"必定是整块移除）。 */
    private static int removeArea(CommandSourceStack source, BlockPos center) {
        AreaData data = AreaData.get(source.getServer());
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
        AreaData data = AreaData.get(source.getServer());
        boolean changed = data.claim(source.getLevel(), chunkX, chunkZ);
        source.sendSuccess(() -> Component.literal("[alice] " + (changed ? "已认领" : "本就已认领")
                + " 区块 " + chunkX + ", " + chunkZ + "（忽略 Y ⇒ 全高度；当前共 "
                + data.claimedChunkCount() + " 个区块）"), false);
        return 1;
    }


    /** 精确取消一个区块的认领。 */
    private static int unclaimChunk(CommandSourceStack source, int chunkX, int chunkZ) {
        AreaData data = AreaData.get(source.getServer());
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
        AreaData data = AreaData.get(source.getServer());
        ServerLevel level = actor.serverLevel();
        int chunkX = actor.chunkPosition().x;
        int chunkZ = actor.chunkPosition().z;
        if (declare) {
            AreaData.SafeDeclare result = data.declareSafe(level, chunkX, chunkZ);
            if (result == AreaData.SafeDeclare.NOT_PROTECTED) {
                source.sendFailure(Component.literal("[alice] 该区块（" + chunkX + ", " + chunkZ
                        + "）**还不是保护区** ⇒ 安全区必须是保护区的子集：先认领保护区，再声明安全区"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("[alice] 安全区：" + chunkX + ", " + chunkZ + " ⇒ "
                    + (result == AreaData.SafeDeclare.DECLARED ? "已声明" : "本就是安全区")
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
        AreaData data = AreaData.get(source.getServer());
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
        AreaData data = AreaData.get(source.getServer());
        // `D-338` ③：**当前位置**的两态（保护区/安全区）——这是玩家验证"我声明的到底生效没有"的入口，
        // 也让 `isClaimed`/`isSafe` 这对判据在服务端有真实读者（不是只有夹具在读）。
        if (source.getEntity() instanceof ServerPlayer actor) {
            ServerLevel level = actor.serverLevel();
            BlockPos at = actor.blockPosition();
            // `D-338` 附注四②：**任务区**（工作区域派生的区块级授权封套）——只读展示，
            // 玩家由此能看出"这个区块现在被某个任务覆盖着"（任务存续期内他手改不了它）。
            com.dddgn.alice.region.JobRegionRegistry.JobRegion zone =
                    com.dddgn.alice.region.JobRegionRegistry.jobRegionAt(level, at);
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
                + com.dddgn.alice.region.JobRegionRegistry.summary(source.getServer())), false);
        return 1;
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
