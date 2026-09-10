package com.dddgn.alice.command;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.gui.BotInventoryService;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.PerceptionProfile;
import com.dddgn.alice.perception.PerceptionSnapshot;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.dddgn.alice.transfer.ChestEndpointRef;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferLedgerData;
import com.dddgn.alice.transfer.TransferRequest;
import com.dddgn.alice.transfer.TransferSelectionData;
import com.dddgn.alice.transfer.TransferSelectionSubmission;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * M0 测试命令入口(后续 M4 将被 AI 工具调用取代)。
 * <pre>
 *   /alice spawn [name]     在命令执行者位置生成假人
 *   /alice mine &lt;x y z&gt;     让假人去挖指定坐标的方块
 * </pre>
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
                .then(Commands.literal("come")
                        .executes(ctx -> come(ctx.getSource())))
                .then(Commands.literal("mine")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> mine(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("follow")
                        .then(Commands.literal("on").executes(ctx -> followOn(ctx.getSource())))
                        .then(Commands.literal("off").executes(ctx -> followOff(ctx.getSource()))))
                .then(Commands.literal("road")
                        .then(Commands.literal("build")
                                .executes(ctx -> buildRoad(ctx.getSource())))
                                 .then(Commands.literal("buildbybot")
                                         .executes(ctx -> buildRoadByBot(ctx.getSource()))))
                .then(Commands.literal("observe")
                        .executes(ctx -> observe(ctx.getSource())))
                .then(Commands.literal("bot-control")
                        .then(Commands.literal("forward")
                                .executes(ctx -> botControlForward(ctx.getSource())))
                        .then(Commands.literal("stop")
                                .executes(ctx -> botControlStop(ctx.getSource())))
                        .then(Commands.literal("jump")
                                .executes(ctx -> botControlJump(ctx.getSource()))))
                .then(Commands.literal("selftest")
                        .executes(ctx -> selftest(ctx.getSource(), false))
                        .then(Commands.literal("full")
                                .executes(ctx -> selftest(ctx.getSource(), true))))
                .then(Commands.literal("scan")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> scan(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("diagnose-path")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> diagnosePath(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("trace")
                       .executes(ctx -> {
                           boolean on = com.dddgn.alice.bot.BotTrace.toggle();
                           ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component
                                   .literal("[alice] 运动轨迹记录 = " + (on ? "开" : "关")));
                           return 1;
                       }))
               .then(Commands.literal("mining")
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "[alice] 挖掘站位调参：" + com.dddgn.alice.task.mining.MiningTuning.describe()));
                            return 1;
                        })
                        .then(Commands.literal("estimate")
                                .then(Commands.literal("lower_bound")
                                        .executes(ctx -> miningEstimate(ctx.getSource(), "lower_bound")))
                                .then(Commands.literal("dijkstra")
                                        .executes(ctx -> miningEstimate(ctx.getSource(), "dijkstra")))))
               .then(Commands.literal("chain")
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "[alice] 连锁挖掘：" + com.dddgn.alice.task.mining.MiningTuning.chainMode()
                                            + "（默认 OFF=原版单格）"
                                            + " mod=" + (com.dddgn.alice.compat.ChainMining.available()
                                                    ? "present" : "absent")
                                            + " settings=" + com.dddgn.alice.compat.ChainMining.settingsSummary()
                                            + " 白名单=AUTO 时仅矿石/原木"));
                            return 1;
                        })
                        .then(Commands.literal("off")
                                .executes(ctx -> chainMode(ctx.getSource(), "off")))
                        .then(Commands.literal("auto")
                                .executes(ctx -> chainMode(ctx.getSource(), "auto")))
                        .then(Commands.literal("force")
                                .executes(ctx -> chainMode(ctx.getSource(), "force"))))
               .then(Commands.literal("risk")
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component
                                    .literal("[alice] 风险开关（默认全关 = Baritone 原样高风险）："
                                            + com.dddgn.alice.pathing.risk.RiskSwitches.describe()));
                            return 1;
                        })
                        .then(Commands.literal("descend_overshoot")
                                .executes(ctx -> riskSwitch(ctx.getSource(), "descend_overshoot", true))
                                .then(Commands.literal("on")
                                        .executes(ctx -> riskSwitch(ctx.getSource(), "descend_overshoot", true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> riskSwitch(ctx.getSource(), "descend_overshoot", false)))))
               .then(Commands.literal("pathing")
                        .then(Commands.literal("traverse")
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider
                                                .suggest(new String[]{"north", "south", "east", "west"}, builder))
                                        .executes(ctx -> traverseDiagnostic(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "direction")))))
                        .then(Commands.literal("diagonal")
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider
                                                .suggest(new String[]{"northeast", "northwest", "southeast", "southwest"}, builder))
                                        .executes(ctx -> diagonalDiagnostic(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "direction")))))
                        .then(Commands.literal("ascend")
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider
                                                .suggest(new String[]{"north", "south", "east", "west"}, builder))
                                        .executes(ctx -> ascendDiagnostic(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "direction")))))
                        .then(Commands.literal("descend")
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider
                                                .suggest(new String[]{"north", "south", "east", "west"}, builder))
                                        .executes(ctx -> descendDiagnostic(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "direction")))))
                        .then(Commands.literal("chain")
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider
                                                .suggest(new String[]{"north", "south", "east", "west"}, builder))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(2, 8))
                                                .executes(ctx -> chainDiagnostic(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "direction"),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("session-here")
                                .executes(ctx -> pathSessionHere(ctx.getSource())))
                        .then(Commands.literal("battery")
                                .executes(ctx -> pathingBattery(ctx.getSource())))
                        .then(Commands.literal("plan-here")
                                .executes(ctx -> planPathHere(ctx.getSource())))
                        .then(Commands.literal("plan")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> planPath(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("bot-inventory")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> botInventory(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("transfer-test")
                        .then(Commands.argument("source", BlockPosArgument.blockPos())
                                .then(Commands.argument("destination", BlockPosArgument.blockPos())
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> transfer(ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "source"),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "destination"),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))))
                .then(Commands.literal("transfer-status")
                        .then(Commands.argument("request", StringArgumentType.word())
                                .executes(ctx -> transferStatus(ctx.getSource(), StringArgumentType.getString(ctx, "request")))))
                .then(Commands.literal("transfer-selection")
                        .then(Commands.literal("status").executes(ctx -> transferSelectionStatus(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> transferSelectionClear(ctx.getSource())))
                        .then(Commands.literal("submit").executes(ctx -> transferSelectionSubmit(ctx.getSource(), null, null))
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .executes(ctx -> transferSelectionSubmit(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item").toString(), null))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> transferSelectionSubmit(ctx.getSource(),
                                                        ResourceLocationArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("transfer-abort")
                        .then(Commands.argument("request", StringArgumentType.word())
                                .executes(ctx -> transferAbort(ctx.getSource(), StringArgumentType.getString(ctx, "request")))))
                .then(Commands.literal("restore")
                        .executes(ctx -> restore(ctx.getSource(), true)))
                .then(Commands.literal("ledger")
                        .executes(ctx -> ledger(ctx.getSource(), false))
                        .then(Commands.literal("all").executes(ctx -> ledger(ctx.getSource(), true))))
                .then(Commands.literal("auto-mine")
                        .then(Commands.argument("tag", StringArgumentType.string())
                                .executes(ctx -> autoMine(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "tag"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> autoMine(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "tag"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
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
                        .then(Commands.literal("add-block")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(ctx -> changeBlockRule(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"), true))))
                        .then(Commands.literal("remove-block")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(ctx -> changeBlockRule(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"), false))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listProtection(ctx.getSource())))));
    }

    private static int transfer(CommandSourceStack source, BlockPos sourcePos, BlockPos destinationPos, String rawItem, int count) {
        if (!(source.getEntity() instanceof ServerPlayer actor)) return failure(source, TransferCodes.UNAUTHORIZED_ACTOR);
        ResourceLocation itemId = ResourceLocation.tryParse(rawItem);
        if (itemId == null || ForgeRegistries.ITEMS.getValue(itemId) == null) return failure(source, TransferCodes.INVALID_ITEM_ID);
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) return failure(source, TransferCodes.BOT_UNAVAILABLE);
        try {
            TransferRequest request = new TransferRequest(UUID.randomUUID(), actor.getUUID(), bot.getUUID(),
                    new ChestEndpointRef(source.getLevel().dimension().location(), sourcePos),
                    new ChestEndpointRef(source.getLevel().dimension().location(), destinationPos), itemId, count,
                    source.getLevel().getGameTime());
            String result = BotManager.assignTransfer(bot, request);
            if (!"accepted".equals(result)) return failure(source, result);
            source.sendSuccess(() -> Component.literal("[alice] transfer request=" + request.requestId()
                    + " state=PLANNED code=accepted location=not_moved"), false);
            return 1;
        } catch (IllegalArgumentException exception) { return failure(source, exception.getMessage()); }
    }

    /** /alice bot-inventory <name> — push an authoritative bot inventory snapshot to the viewer. */
    private static int botInventory(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer actor)) return failure(source, TransferCodes.UNAUTHORIZED_ACTOR);
        BotPlayer bot = findBot(source, name);
        if (bot == null) return failure(source, TransferCodes.BOT_UNAVAILABLE);
        BotInventoryService.open(actor, bot);
        botInventoryLog(source, bot);
        return 1;
    }

    private static BotPlayer findBot(CommandSourceStack source, String name) {
        for (net.minecraft.server.level.ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            if (p instanceof BotPlayer bot && bot.getName().getString().equalsIgnoreCase(name)) {
                return bot;
            }
        }
        return null;
    }

    private static void botInventoryLog(CommandSourceStack source, BotPlayer bot) {
        BotLog.info("bot_inventory: open player={} bot={} busy={}",
                source.getEntity().getName().getString(), bot.getName().getString(), BotManager.isBusy(bot));
    }

    private static int transferSelectionStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return failure(source, TransferCodes.UNAUTHORIZED_ACTOR);
        var draft = TransferSelectionData.status(source.getServer(), player.getUUID(), source.getLevel().getGameTime()).orElse(null);
        if (draft == null) return failure(source, "selection_missing");
        source.sendSuccess(() -> Component.literal("[alice] transfer selection source=" + draft.source()
                + " destination=" + draft.destination() + " selectedTick=" + draft.selectedTick()), false);
        return 1;
    }

    private static int transferSelectionClear(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return failure(source, TransferCodes.UNAUTHORIZED_ACTOR);
        TransferSelectionData.clear(source.getServer(), player.getUUID());
        com.dddgn.alice.log.BotLog.info("transfer_selection: cleared player={}", player.getName().getString());
        source.sendSuccess(() -> Component.literal("[alice] transfer selection cleared"), false);
        return 1;
    }

    private static int transferSelectionSubmit(CommandSourceStack source, String rawItem, Integer explicitCount) {
        if (!(source.getEntity() instanceof ServerPlayer actor)) return failure(source, TransferCodes.UNAUTHORIZED_ACTOR);
        var draft = TransferSelectionData.status(source.getServer(), actor.getUUID(), source.getLevel().getGameTime()).orElse(null);
        if (draft == null || draft.source() == null || draft.destination() == null) return failure(source, "selection_incomplete");
        ResourceLocation explicitItem = rawItem == null ? null : ResourceLocation.tryParse(rawItem);
        if (rawItem != null && explicitItem == null) return failure(source, TransferCodes.INVALID_ITEM_ID);
        TransferSelectionSubmission.Resolution resolved = TransferSelectionSubmission.resolve(source.getLevel(), draft.source(), explicitItem, explicitCount);
        if (!resolved.accepted()) return failure(source, resolved.code());
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) return failure(source, TransferCodes.BOT_UNAVAILABLE);
        TransferSelectionSubmission.Submission submitted = TransferSelectionSubmission.submit(actor.getUUID(), bot.getUUID(),
                draft.source(), draft.destination(), resolved, source.getLevel().getGameTime(),
                request -> BotManager.assignTransfer(bot, request));
        if (!submitted.accepted()) return failure(source, submitted.code());
        TransferSelectionData.clear(source.getServer(), actor.getUUID());
        source.sendSuccess(() -> Component.literal("[alice] transfer request=" + submitted.request().requestId()
                + " state=PLANNED code=accepted location=not_moved"), false);
        return 1;
    }

    private static int transferStatus(CommandSourceStack source, String rawId) {
        try {
            TransferLedgerData.Entry entry = BotManager.transferStatus(source.getServer(), UUID.fromString(rawId));
            if (entry == null) return failure(source, "unknown_request");
            source.sendSuccess(() -> Component.literal("[alice] transfer request=" + entry.request().requestId()
                    + " state=" + entry.state() + " code=" + entry.code() + " location=" + entry.location()
                    + " sourceDelta/botDelta/destinationDelta=evidence:" + entry.evidenceDigest()), false);
            return 1;
        } catch (IllegalArgumentException exception) { return failure(source, "invalid_request_id"); }
    }

    private static int transferAbort(CommandSourceStack source, String rawId) {
        try {
            UUID id = UUID.fromString(rawId);
            TransferLedgerData ledger = TransferLedgerData.get(source.getServer());
            TransferLedgerData.Entry entry = ledger.find(id).orElse(null);
            if (entry == null) return failure(source, "unknown_request");
            if (entry.state() == TransferLedgerData.State.VERIFIED
                    || entry.state() == TransferLedgerData.State.FAILED_NOT_MOVED
                    || entry.state() == TransferLedgerData.State.UNKNOWN_DISCREPANCY
                    || entry.state() == TransferLedgerData.State.ABORTED) {
                return failure(source, "terminal_request");
            }
            TransferLedgerData.State state = BotManager.abortTransfer(source.getServer(), id);
            TransferLedgerData.Entry updated = ledger.find(id).orElseThrow();
            source.sendSuccess(() -> Component.literal("[alice] transfer request=" + id + " state=" + state
                    + " code=" + updated.code() + " location=" + updated.location()
                    + " manualTakeover=" + updated.manualTakeoverRequired()), false);
            return 1;
        } catch (IllegalArgumentException exception) { return failure(source, "invalid_request_id"); }
    }

    private static int failure(CommandSourceStack source, String code) { source.sendFailure(Component.literal("[alice] transfer code=" + code)); return 0; }

    private static int addArea(CommandSourceStack source, BlockPos center, int radius) {
        SafeZoneData.get(source.getServer()).addArea(source.getLevel(), center, radius);
        source.sendSuccess(() -> Component.literal("[alice] 已保护区域 " + center.toShortString()
                + " 半径 " + radius + " (当前维度全高度)"), false);
        return 1;
    }

    private static int removeArea(CommandSourceStack source, BlockPos center) {
        int removed = SafeZoneData.get(source.getServer()).removeAreasAt(source.getLevel(), center);
        if (removed == 0) {
            source.sendFailure(Component.literal("[alice] 该坐标没有保护区域"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已移除 " + removed + " 个保护区域: "
                + center.toShortString()), false);
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
        source.sendSuccess(() -> Component.literal("[alice] 安全区: "
                + SafeZoneData.get(source.getServer()).summary()), false);
        return 1;
    }

    /** 决策层最小规则:感知扫描最近的 <tag 或 方块ID> → 自动挖(感知→决策→执行闭环)。
     * 输入两种写法都支持:存在同名标签(如 minecraft:coal_ores)按标签匹配一组方块;
     * 否则按方块 ID 精确匹配(如 minecraft:stone / stone)。 */
    private static int autoMine(CommandSourceStack source, String tagStr, int quota) {
        ServerLevel level = source.getLevel();
        if (tagStr.startsWith("#")) {
            tagStr = tagStr.substring(1);
        }
        net.minecraft.resources.ResourceLocation id;
        try {
            id = net.minecraft.resources.ResourceLocation.parse(tagStr);
        } catch (Exception e) {
            source.sendFailure(Component.literal("[alice] 格式错误: " + tagStr));
            return 0;
        }
        BlockPos center = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : new BlockPos(level.getSharedSpawnPos());

        // 目标解析与扫描都交给 MineCandidateSource（J5：原来这里是 AutoMineDecision 孤岛）
        com.dddgn.alice.job.mine.MineCandidateSource.Target targetSpec =
                com.dddgn.alice.job.mine.MineCandidateSource.Target.parse(level, id);
        if (targetSpec == null) {
            source.sendFailure(Component.literal("[alice] 既不是标签也不是方块 ID: " + tagStr));
            return 0;
        }
        BotPlayer bot = BotManager.firstOrSpawn(level, center);
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot 生成失败"));
            return 0;
        }
        // 半径取 Job 规格半径：命令语义是"以我为中心扫描"，故用来源默认半径
        int radius = com.dddgn.alice.job.mine.MineCandidateSource.SCAN_RADIUS;
        String targetName = targetSpec.describe();
        if (!BotManager.assignMineJob(bot, source.getPlayer(), targetSpec, quota, radius)) {
            source.sendFailure(Component.literal("[alice] bot 正忙，稍后再试"));
            return 0;
        }
        String resultMsg = "挖掘 Job 已启动: 目标 " + targetName + " 配额 " + quota
                + " 半径 " + radius + "（决策与终态见 [Job] 日志）";
        source.sendSuccess(() -> Component.literal("[alice] " + bot.getName().getString() + " " + resultMsg), false);
        return 1;
    }

    /** 触发作用域恢复（J6-b 兜底）：拆掉我方放置的临时方块并销账。 */
    private static int restore(CommandSourceStack source, boolean all) {
        var bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用 bot"));
            return 0;
        }
        if (!BotManager.assignRestore(bot, source.getPlayer(), all)) {
            source.sendFailure(Component.literal("[alice] 无可恢复项，或 bot 正忙"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已启动恢复任务（详见 [Restore] 日志；"
                + "结束后用 /alice ledger 确认 pending=0）"), false);
        return 1;
    }

    /**
     * 只读查看世界修改账本（J6-a）：未清除的放置、按策略分类、打开中的授权作用域。
     *
     * <p>只读——不分配任务、不改变世界、不清理账本。清理是 J6-b 的 `RestoreScopeTask` 的职责。
     */
    private static int ledger(CommandSourceStack source, boolean all) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        var entries = com.dddgn.alice.ledger.WorldModLedger.recent(server, all ? 16 : 6);
        var open = com.dddgn.alice.ledger.WorldModLedger.openScopes(server);
        int size = com.dddgn.alice.ledger.WorldModLedger.size(server);
        long temp = com.dddgn.alice.ledger.WorldModLedger.pending(server).stream()
                .filter(e -> e.policy() == com.dddgn.alice.ledger.WorldModLedger.Policy.TEMP).count();
        long keep = size - temp;
        source.sendSuccess(() -> Component.literal("[alice] 世界修改账本 pending=" + size
                + " TEMP=" + temp + " KEEP=" + keep + " openScopes=" + open.size()), false);
        if (size == 0) {
            source.sendSuccess(() -> Component.literal("[alice] （空：建拆同权已闭合）"), false);
        }
        for (var entry : entries) {
            String line = "  " + entry.describe();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** 只读曲面寻路诊断，不分配任务、不改变世界。 */
    private static int diagnosePath(CommandSourceStack source, BlockPos goal) {
        ServerLevel level = source.getLevel();
        BlockPos start = source.getEntity() != null ? source.getEntity().blockPosition()
                : level.getSharedSpawnPos();
        SurfacePathfinder.Result result = SurfacePathfinder.find(level, start, goal);
        String detail = "[alice] 曲面诊断 " + result.status() + ": "
                + start.toShortString() + " -> " + goal.toShortString()
                + ", path=" + result.path().size() + ", expanded=" + result.expandedNodes();
        if (result.inconclusive()) {
            detail += "；搜索预算耗尽，不能据此授权挖通道";
        }
        String message = detail;
        source.sendSuccess(() -> Component.literal(message), false);
        return result.reachable() ? 1 : 0;
    }

    /** Starts one deterministic same-level cardinal Traverse for the nearest Bot. */
    private static int traverseDiagnostic(CommandSourceStack source, String rawDirection) {
        Direction direction = switch (rawDirection.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (direction == null) {
            source.sendFailure(Component.literal("[alice] traverse_invalid_direction: use north/south/east/west"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        BlockPos from = bot.blockPosition().immutable();
        BlockPos to = from.relative(direction);
        if (!BotManager.assignTraverseDiagnostic(bot, to)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R2-B Traverse submitted bot="
                + bot.getName().getString() + " from=" + from.toShortString()
                + " to=" + to.toShortString()), false);
        return 1;
    }

    /** Starts one deterministic same-level diagonal movement for the nearest Bot. */
    private static int diagonalDiagnostic(CommandSourceStack source, String rawDirection) {
        int dx = 0, dz = 0;
        switch (rawDirection.toLowerCase(java.util.Locale.ROOT)) {
            case "northeast" -> { dx = 1; dz = -1; }
            case "northwest" -> { dx = -1; dz = -1; }
            case "southeast" -> { dx = 1; dz = 1; }
            case "southwest" -> { dx = -1; dz = 1; }
            default -> {
                source.sendFailure(Component.literal("[alice] diagonal_invalid_direction: use northeast/northwest/southeast/southwest"));
                return 0;
            }
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        BlockPos from = bot.blockPosition().immutable();
        BlockPos to = from.offset(dx, 0, dz);
        if (!BotManager.assignDiagonalDiagnostic(bot, to)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R2-C Diagonal submitted bot="
                + bot.getName().getString() + " from=" + from.toShortString()
                + " to=" + to.toShortString()), false);
        return 1;
    }

    /** Starts one deterministic ascend movement (+1 level) for the nearest Bot. */
    private static int ascendDiagnostic(CommandSourceStack source, String rawDirection) {
        Direction direction = switch (rawDirection.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (direction == null) {
            source.sendFailure(Component.literal("[alice] ascend_invalid_direction: use north/south/east/west"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        BlockPos from = bot.blockPosition().immutable();
        BlockPos to = from.relative(direction).above();
        if (!BotManager.assignAscendDiagnostic(bot, to)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R2-C Ascend submitted bot="
                + bot.getName().getString() + " from=" + from.toShortString()
                + " to=" + to.toShortString()), false);
        return 1;
    }

    /** R4 路径会话：规划到命令执行者脚下并逐段执行（零参数）。 */
    private static int pathSessionHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[alice] session-here 需要玩家执行"));
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
        final BlockPos goal = player.blockPosition().immutable();
        final String botName = bot.getName().getString();
        if (!BotManager.assignPathSessionDiagnostic(bot, goal)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R4 路径会话已提交 bot="
                + botName + " → " + goal.toShortString() + "（规划 + 逐段执行）"), false);
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
        BlockPos foot = player.blockPosition().immutable();
        // 摆位传送（允许）：带头部同步的重载，避免头身不一致
        bot.teleportTo(source.getLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        final String name = bot.getName().getString();
        source.sendSuccess(() -> Component.literal("[alice] " + name + " 已到位 " + foot.toShortString()), false);
        return 1;
    }

    /** 一键自检电池：规划检查 + 全部 Movement + 2 段链，零参数。 */
    private static int pathingBattery(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        ServerPlayer player = source.getPlayer();
        if (bot == null && player != null) {
            bot = BotManager.firstOrSpawn(source.getLevel(), player.blockPosition());
        }
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        final BlockPos anchor = player == null ? bot.blockPosition().immutable()
                : player.blockPosition().immutable();
        final String botName = bot.getName().getString();
        if (!BotManager.assignPathingBattery(bot, anchor)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R3 自检电池已提交 bot="
                + botName + " 起点=" + anchor.toShortString()
                + "（规划 + 全部 Movement + 链），结果见聊天/日志"), false);
        return 1;
    }

    /** 零参数规划测试：从最近 bot 规划到命令执行者脚下的脚位。 */
    private static int planPathHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[alice] plan-here 需要玩家执行"));
            return 0;
        }
        return planPath(source, player.blockPosition().immutable());
    }

    /**
     * R3 规划内核诊断：从最近 bot 的脚位到目标脚位跑一次 Movement-aware A*，
     * 只报告 {@code PathPlan}，不执行、不修改世界。
     */
    private static int planPath(CommandSourceStack source, BlockPos goalFoot) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        BlockPos startFoot = bot.blockPosition().immutable();
        com.dddgn.alice.pathing.core.search.PathPlan plan =
                new com.dddgn.alice.pathing.core.search.CorePathPlanner()
                        .planTo(bot, source.getLevel(), bot.getUUID().toString(), startFoot, goalFoot, "command");
        BotLog.info("[R3 Plan] {} bot={} from={} to={}", plan.summary(),
                bot.getName().getString(), startFoot.toShortString(), goalFoot.toShortString());
        for (int i = 0; i < plan.movements().size(); i++) {
            var movement = plan.movements().get(i);
            BotLog.info("[R3 Plan] step={} type={} from={} to={} cost={}",
                    i, movement.movementType(), movement.fromFoot().toShortString(),
                    movement.toFoot().toShortString(), String.format(java.util.Locale.ROOT, "%.3f", movement.cost()));
        }
        source.sendSuccess(() -> Component.literal("[alice] R3 Plan " + plan.summary()
                + " from=" + startFoot.toShortString() + " to=" + goalFoot.toShortString()
                + (plan.diagnostics().isEmpty() ? "" : " | " + plan.diagnostics())), false);
        return plan.reached() ? 1 : 0;
    }

    /**
     * 多段链接诊断：沿指定方向连续下降 count 级（每级水平 1 格 + 下降 1 格），
     * 用于验证 D-026 合法位置集与统一完成契约下的多段链接不断链。
     */
    private static int chainDiagnostic(CommandSourceStack source, String rawDirection, int count) {
        Direction direction = switch (rawDirection.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (direction == null) {
            source.sendFailure(Component.literal("[alice] chain_invalid_direction: use north/south/east/west"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        java.util.List<BlockPos> planned = new java.util.ArrayList<>();
        BlockPos cursor = bot.blockPosition().immutable();
        planned.add(cursor);
        for (int i = 0; i < count; i++) {
            cursor = cursor.relative(direction).below();
            planned.add(cursor);
        }
        if (!BotManager.assignChainDiagnostic(bot, planned)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R2-C Chain submitted bot="
                + bot.getName().getString() + " segments=" + count
                + " from=" + planned.get(0).toShortString()
                + " to=" + planned.get(planned.size() - 1).toShortString()), false);
        return 1;
    }

    /** Starts one deterministic descend movement (-1 level) for the nearest Bot. */
    private static int descendDiagnostic(CommandSourceStack source, String rawDirection) {
        Direction direction = switch (rawDirection.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (direction == null) {
            source.sendFailure(Component.literal("[alice] descend_invalid_direction: use north/south/east/west"));
            return 0;
        }
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] bot_unavailable"));
            return 0;
        }
        BlockPos from = bot.blockPosition().immutable();
        BlockPos to = from.relative(direction).below();
        if (!BotManager.assignDescendDiagnostic(bot, to)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R2-C Descend submitted bot="
                + bot.getName().getString() + " from=" + from.toShortString()
                + " to=" + to.toShortString()), false);
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

    /** M2 接口扫描:输出指定方块的 capability 接口清单(物品/能量/流体/气体等)。 */
    private static int buildRoadByBot(CommandSourceStack source) {
        com.dddgn.alice.road.RoadPlan plan = com.dddgn.alice.road.RoadPlan.get();
        if (!plan.isComplete() || plan.level() != source.getLevel()) {
            source.sendFailure(Component.literal("[alice] 尚未生成当前维度的道路蓝图"));
            return 0;
        }
        BotPlayer bot = BotManager.firstOrSpawn(source.getLevel(), plan.first());
        if (BotManager.isBusy(bot)) {
            source.sendFailure(Component.literal("[alice] 假人当前正在执行其他任务"));
            return 0;
        }
        BotManager.assignRoadBuild(bot, plan);
        source.sendSuccess(() -> Component.literal("[alice] 已让 " + bot.getName().getString()
                + " 按道路蓝图逐单元施工并前往目标"), false);
        return 1;
    }

    private static int buildRoad(CommandSourceStack source) {
        com.dddgn.alice.road.RoadPlan plan = com.dddgn.alice.road.RoadPlan.get();
        if (!plan.isComplete() || plan.level() != source.getLevel()) {
            source.sendFailure(Component.literal("[alice] 尚未生成当前维度的道路蓝图"));
            return 0;
        }
        net.minecraft.server.level.ServerPlayer actor = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p
                ? p : null;
        if (actor == null || !com.dddgn.alice.road.RoadBuilder.get().start(source.getLevel(), actor, plan)) {
            source.sendFailure(Component.literal("[alice] 道路构建已在进行中，或需要由玩家执行"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 道路构建开始: 按水平单元逐格推进，每格等待稳定性检查"), false);
        return 1;
        /*
        int changed = 0;
        int skipped = 0;
        for (com.dddgn.alice.road.RoadPlan.Cell cell : plan.cells()) {
            BlockPos pos = cell.pos();
            // 两个被选中的目标方块必须保留：道路终点的支撑格在它们下方，不能清理目标本身。
            if (pos.equals(plan.first()) || pos.equals(plan.second())) {
                continue;
            }
            if (cell.kind() == com.dddgn.alice.road.RoadPlan.CellKind.SUPPORT_PLACE) {
                if (source.getLevel().getBlockState(pos).isAir()) {
                    source.getLevel().setBlock(pos, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                    changed++;
                }
            } else if (cell.kind() == com.dddgn.alice.road.RoadPlan.CellKind.CLEAR) {
                if (actor == null) {
                    skipped++;
                } else if (com.dddgn.alice.protection.BlockBreakSafety.clearingRefusal(actor, pos) == null) {
                    com.dddgn.alice.action.BlockInteraction.breakForBulkEdit(actor, source.getLevel(), pos, false);
                    changed++;
                } else {
                    skipped++;
                }
            }
        }
        int finalChanged = changed;
        int finalSkipped = skipped;
        source.sendSuccess(() -> Component.literal("[alice] 道路蓝图已实现: 修改 " + finalChanged
                + " 个方块, 跳过 " + finalSkipped + " 个受限方块"), false);
        return changed;
        */
    }

    private static int scan(CommandSourceStack source, BlockPos target) {
        String result = com.dddgn.alice.capability.InterfaceScanner.scan(source.getLevel(), target);
        BotLog.info("接口扫描:\n{}", result);
        source.sendSuccess(() -> Component.literal("[alice] 接口扫描完成,详见日志"), false);
        return 1;
    }

    /** 手动触发自检(默认不自动跑,审查点 R8)。 */
    private static int selftest(CommandSourceStack source, boolean full) {
        com.dddgn.alice.bot.BotSelftest.start(full);
        source.sendSuccess(() -> Component.literal("[alice] " + (full ? "完整回归" : "基础冒烟")
                + "自检已启动,结果见日志"), false);
        return 1;
    }

    private static int spawn(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        BlockPos pos = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : new BlockPos(level.getSharedSpawnPos());
        BotPlayer bot = BotManager.spawn(level, pos, name);
        source.sendSuccess(() -> Component.literal(
                "[alice] 假人 " + name + " 已生成于 " + pos.toShortString()), false);
        return 1;
    }

    private static int mine(CommandSourceStack source, BlockPos target) {
        ServerLevel level = source.getLevel();
        if (level.getBlockState(target).isAir()) {
            source.sendFailure(Component.literal("[alice] 目标位置是空气"));
            return 0;
        }
        BotPlayer bot = BotManager.firstOrSpawn(level, target);
        BotManager.assignMine(bot, target);
        source.sendSuccess(() -> Component.literal(
                "[alice] " + bot.getName().getString() + " 开始挖掘 " + target.toShortString()), false);
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

    /** M1 感知层演示:输出命令执行者周围 5 格的分类聚合摘要(目标/危险/收获/普通)。 */
    private static int observe(CommandSourceStack source) {
        BlockPos center = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : new BlockPos(source.getLevel().getSharedSpawnPos());
        String summary = PerceptionSnapshot.summarize(source.getLevel(), center, PerceptionProfile.MINING);
        source.sendSuccess(() -> Component.literal("[alice] 感知摘要已生成(挖矿视角),详见日志"), false);
        BotLog.info("感知摘要(挖矿视角):\n{}", summary);
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

    /** D-059：切换风险开关（默认全关 = Baritone 原样高风险）。 */
    private static int riskSwitch(net.minecraft.commands.CommandSourceStack source, String name, boolean value) {
        if (!com.dddgn.alice.pathing.risk.RiskSwitches.set(name, value)) {
            source.sendSystemMessage(Component.literal("[alice] 未知风险开关: " + name));
            return 0;
        }
        source.sendSystemMessage(Component.literal("[alice] 风险开关已更新: "
                + com.dddgn.alice.pathing.risk.RiskSwitches.describe()));
        com.dddgn.alice.log.BotLog.info("[Risk] switch {}={} all={}", name, value,
                com.dddgn.alice.pathing.risk.RiskSwitches.describe());
        return 1;
    }


    /** D-067 批次 2：切换候选成本估算方案。 */
    private static int chainMode(net.minecraft.commands.CommandSourceStack source, String mode) {
        if (!com.dddgn.alice.task.mining.MiningTuning.setChainMode(mode)) {
            source.sendSystemMessage(Component.literal("[alice] 未知连锁策略: " + mode
                    + "（可选 off / auto / force）"));
            return 0;
        }
        String summary = "[alice] 连锁挖掘 = "
                + com.dddgn.alice.task.mining.MiningTuning.chainMode()
                + " mod=" + (com.dddgn.alice.compat.ChainMining.available() ? "present" : "absent");
        source.sendSystemMessage(Component.literal(summary));
        com.dddgn.alice.log.BotLog.info("[ChainMining] 策略切换 mode={} all={}",
                mode, com.dddgn.alice.task.mining.MiningTuning.describe());
        return 1;
    }

    private static int miningEstimate(net.minecraft.commands.CommandSourceStack source, String mode) {
        if (!com.dddgn.alice.task.mining.MiningTuning.setEstimateMode(mode)) {
            source.sendSystemMessage(Component.literal("[alice] 未知估算方案: " + mode));
            return 0;
        }
        source.sendSystemMessage(Component.literal("[alice] 挖掘估算方案已切换："
                + com.dddgn.alice.task.mining.MiningTuning.describe()));
        com.dddgn.alice.log.BotLog.info("[MiningTuning] estimate={} all={}", mode,
                com.dddgn.alice.task.mining.MiningTuning.describe());
        return 1;
    }

}
