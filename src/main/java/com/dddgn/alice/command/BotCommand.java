package com.dddgn.alice.command;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.gui.BotInventoryService;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.PerceptionProfile;
import com.dddgn.alice.perception.PerceptionSnapshot;
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
                // K-3：**取消当前任务**（`bot-control stop` 只停移动输入，不停任务）
                .then(Commands.literal("stop-task")
                        .executes(ctx -> stopTaskCommand(ctx.getSource())))
                // 授权/审批框架的**运行时快照**（零参数、只读；配 docs/authz/OVERVIEW.md）
                .then(Commands.literal("authz")
                        .executes(ctx -> authzSnapshot(ctx.getSource())))
                .then(Commands.literal("bot-control")
                        .then(Commands.literal("forward")
                                .executes(ctx -> botControlForward(ctx.getSource())))
                        .then(Commands.literal("stop")
                                .executes(ctx -> botControlStop(ctx.getSource())))
                        .then(Commands.literal("jump")
                                .executes(ctx -> botControlJump(ctx.getSource()))))
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
               // **队列第④项 / survey/16 §1.3**：低频进度事件（`PROGRESS`）的开关。
               // 此前**只有夹具能打开**（`EventThresholds.setProgressEventInterval` 的唯一调用者在夹具里）
               // ⇒ 游戏内根本没法观测"推送频率够不够"（2026-09-17 核实发现）。
               .then(Commands.literal("progress")
                        .executes(ctx -> progressInterval(ctx.getSource(), null))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                .executes(ctx -> progressInterval(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ticks")))))
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
                .then(Commands.literal("transfer-route")
                        .executes(ctx -> transferRouteShow(ctx.getSource()))
                        .then(Commands.literal("menu").executes(ctx ->
                                transferRouteSet(ctx.getSource(), "menu")))
                        .then(Commands.literal("capability").executes(ctx ->
                                transferRouteSet(ctx.getSource(), "capability"))))
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
                // §5.9 C1（2026-09-16 用户裁定「甲」）：**显式确认**解除永久阻塞的挂起传输。
                // 必须打全 `confirm` 这个字面量 —— 解除 = 放弃对"可能还在 bot 背包里"的物品的追踪，
                // 不能靠手滑触发（`transfer-abort` 那条路保持保守：继续挂起保护）。
                .then(Commands.literal("transfer-resolve")
                        .then(Commands.argument("request", StringArgumentType.word())
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> transferResolve(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "request"))))))
                .then(Commands.literal("restore")
                        .executes(ctx -> restore(ctx.getSource(), true)))
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
                                                ResourceLocationArgument.getId(ctx, "item").toString())))))
                .then(Commands.literal("ledger")
                        .executes(ctx -> ledger(ctx.getSource(), false))
                        .then(Commands.literal("all").executes(ctx -> ledger(ctx.getSource(), true))))
                // S3 请示通道：查看/答复未决请示 + 查看/修改能力分级
                // 操作者直连指令（连通性测试通道；**不询问**，让 LLM 直接执行这句话）
                .then(Commands.literal("instruct")
                        .then(Commands.argument("instruction",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> instruct(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "instruction")))))
                .then(Commands.literal("ask")
                        .executes(ctx -> askList(ctx.getSource()))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("option", StringArgumentType.word())
                                        .executes(ctx -> askAnswer(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "option"), "once"))
                                        .then(Commands.argument("scope", StringArgumentType.word())
                                                .executes(ctx -> askAnswer(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "option"),
                                                        StringArgumentType.getString(ctx, "scope")))))))
                // S5 只读配方图：导出运行时配方表（含整合包魔改）供离线规划器使用
                // 阶段 3-A / S1-2（D-192）：**合成工作站可切换**（只读展示 + 玩家显式选择，不自动选优）
                .then(Commands.literal("craft")
                        .then(Commands.literal("station")
                                .executes(ctx -> craftStationShow(ctx.getSource()))
                                .then(Commands.argument("station", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (var station : com.dddgn.alice.task.craft.CraftStation.all()) {
                                                builder.suggest(station.id());
                                            }
                                            builder.suggest("auto");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> craftStationSet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "station"))))))
                // D-197：**回归电池分档管理**（用户要求"只测必要基础项 + 当前主线项"，由 AI 维护归属表）
                .then(Commands.literal("battery")
                        .executes(ctx -> batteryCommand(ctx.getSource(), "list"))
                        .then(Commands.literal("core").executes(ctx -> batteryCommand(ctx.getSource(), "core")))
                        .then(Commands.literal("full").executes(ctx -> batteryCommand(ctx.getSource(), "full")))
                        .then(Commands.literal("list").executes(ctx -> batteryCommand(ctx.getSource(), "list"))))
                .then(Commands.literal("recipes")
                        .executes(ctx -> recipesDump(ctx.getSource(), "alice-recipes.json"))
                        .then(Commands.argument("file", StringArgumentType.word())
                                .executes(ctx -> recipesDump(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file")))))
                // S3.5 收集授权：查看 / 提升为永久 / 清空
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
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
                request -> {
                    // F1（D-285）：这条请求由**玩家**发起的选择流触发 ⇒ 归因玩家
                    com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
                    return BotManager.assignTransfer(bot, request);
                });
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

    /** §5.9 C1：人工确认解除（**不动物品**，只放开"这个 bot 不许派活"这道门）。 */
    private static int transferResolve(CommandSourceStack source, String rawId) {
        try {
            UUID id = UUID.fromString(rawId);
            TransferLedgerData ledger = TransferLedgerData.get(source.getServer());
            TransferLedgerData.Entry entry = ledger.find(id).orElse(null);
            if (entry == null) {
                return failure(source, "unknown_request");
            }
            if (entry.state() != TransferLedgerData.State.SUSPENDED
                    && entry.state() != TransferLedgerData.State.IN_TRANSIT_BOT) {
                return failure(source, "not_blocking");   // 只对**阻塞态**有意义；终态无需解除
            }
            String reconciliation = reconciliation(source.getServer(), entry);
            TransferLedgerData.State state = ledger.resolveManual(id,
                    TransferLedgerData.clockNow(source.getServer()),
                    "resolve:" + source.getTextName() + ":" + reconciliation);
            source.sendSuccess(() -> Component.literal("[alice] transfer-resolve request=" + id + " state=" + state
                    + " code=" + TransferCodes.RESOLVED_BY_OPERATOR + " " + reconciliation
                    + " / 「物品**没有被移动**」：只解开了「该 bot 不许派活」，请自行核对物品去向"), false);
            com.dddgn.alice.log.BotLog.warn("[Transfer] 人工解除阻塞：request={} {} ⇒ 该 bot 的 assign* 通路已放开"
                    + "（放弃追踪，不动物品）", id, reconciliation);
            return 1;
        } catch (IllegalArgumentException exception) {
            return failure(source, "invalid_request_id");
        } catch (IllegalStateException exception) {
            return failure(source, "terminal_request");
        }
    }

    /** 只读对账：该请求的物品在 bot 背包（主手栏 + 副手）里还有几件；bot 不在线就如实写 offline。 */
    private static String reconciliation(net.minecraft.server.MinecraftServer server, TransferLedgerData.Entry entry) {
        var request = entry.request();
        ServerPlayer bot = server.getPlayerList().getPlayer(request.botId());
        if (bot == null) {
            return "botHeld=offline expected=" + request.count() + " item=" + request.itemId();
        }
        net.minecraft.world.item.Item item =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(request.itemId());
        int held = 0;
        if (item != null) {
            for (net.minecraft.world.item.ItemStack stack : bot.getInventory().items) {
                if (stack.is(item)) {
                    held += stack.getCount();
                }
            }
            if (bot.getOffhandItem().is(item)) {
                held += bot.getOffhandItem().getCount();
            }
        }
        return "botHeld=" + held + "/" + request.count() + " item=" + request.itemId();
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        if (!BotManager.assignMineJob(bot, source.getPlayer(), targetSpec, quota, radius)) {
            source.sendFailure(Component.literal("[alice] " + BotManager.busyMessage(bot)));
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        if (!BotManager.assignRestore(bot, source.getPlayer(), all)) {
            source.sendFailure(Component.literal("[alice] 无可恢复项，或 bot 正忙"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已启动恢复任务（详见 [Restore] 日志；"
                + "结束后用 /alice ledger 确认 pending=0）"), false);
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

    /**
     * {@code /alice authz}：**授权/审批框架的运行时快照**（零参数、只读：不写世界、不分配任务、不清理账本）。
     *
     * <p>配套文档：{@code docs/authz/OVERVIEW.md}（单页总览）、{@code docs/authz/AUTHZ_REGISTRY.csv}（单一出处）、
     * 检查脚本 {@code tools/check-authz-registry.sh}（断言注册表与代码一致）。
     *
     * <p><b>为什么要有这条命令</b>：文档会过期，而"当前真实生效的规则"只有服务端知道。
     * 这里打印的全部是**现场可查询的事实**——预算余量、账本 pending、保护区判定、最近终态/失败码——
     * 而不是抄文档，所以它**不会过期**：文档与它对不上时，以它为准并去修文档。
     */
    private static int authzSnapshot(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var server = source.getServer();
        BlockPos foot = bot.blockPosition();

        // L1 请求层：策略集合取**代码常量**（不抄文档）
        StringBuilder pure = new StringBuilder();
        for (com.dddgn.alice.pathing.core.MovementType t
                : com.dddgn.alice.pathing.core.CapabilityGate.PURE_TRAVERSAL_TYPES) {
            if (pure.length() > 0) {
                pure.append(", ");
            }
            pure.append(t);
        }
        final String pureText = pure.toString();

        // L3 执行期：写入预算与拒绝计数（D-106）。**无作用域时闸门根本没生效**，
        // 此时不能打印各桶"余量"（那会把未生效的上限当成真实额度）——只报未生效 + 作用域内默认上限。
        final String scopeId = com.dddgn.alice.action.WriteBudget.scopeOf(bot);
        final String budget = scopeId != null
                ? "scope=" + scopeId
                        + "；破坏 余" + com.dddgn.alice.action.WriteBudget.remainingBreaks(bot)
                        + "（已拒 " + com.dddgn.alice.action.WriteBudget.refusedBreaks(bot) + "）"
                        + " / 放置 余" + com.dddgn.alice.action.WriteBudget.remainingPlaces(bot)
                        + "（已拒 " + com.dddgn.alice.action.WriteBudget.refusedPlaces(bot) + "）"
                        + " / 容器写入 余" + com.dddgn.alice.action.WriteBudget.remainingContainerWrites(bot)
                : "无作用域 ⇒ 闸门未生效（不计数、不拦截，仅 [WriteBudget] no_scope 留痕）；"
                        + "默认上限（仅作用域内生效）破坏" + com.dddgn.alice.action.WriteBudget.DEFAULT_MAX_BREAKS
                        + "/放置" + com.dddgn.alice.action.WriteBudget.DEFAULT_MAX_PLACES
                        + "/容器" + com.dddgn.alice.action.WriteBudget.DEFAULT_MAX_CONTAINER_WRITES;

        // L4 收尾期：账本 pending（只记放置）+ 保护区判定
        final int pendingMine = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(server, bot.getUUID()).size();
        final int pendingAll = com.dddgn.alice.ledger.WorldModLedger.size(server);
        final int tempOpen = BotManager.pendingTemporaryCount(bot);
        final String zoneSummary = com.dddgn.alice.protection.SafeZoneData.get(server).summary();
        final String zoneVerdict = com.dddgn.alice.protection.SafeZoneData.get(server)
                .protectionReason(source.getLevel(), foot);

        // 最近一次任务的终态（拒绝码/失败码归因）
        com.dddgn.alice.bot.TaskExecutionRecord rec = BotManager.lastExecutionRecord(bot);
        final String last = rec == null ? "无记录"
                : rec.taskKind() + " " + rec.terminalStatus() + " code=" + rec.resultCode()
                        + " @" + rec.terminalBotPos();

        source.sendSuccess(() -> Component.literal("[alice] === 授权/审批快照（只读；注册表 docs/authz/OVERVIEW.md）==="), false);
        source.sendSuccess(() -> Component.literal("[alice] L0 目标层：当前任务="
                + BotManager.currentTaskSummary(bot)), false);
        source.sendSuccess(() -> Component.literal("[alice] L1 纯通行集合=[" + pureText
                + "]（挖掘站位/回收用 PathRequest 的命名工厂，含各自禁用集）"), false);
        // L2 规划期：写入集中策略表（D-207 ①）。表是**唯一出处**，视图 docs/authz/POLICY_MATRIX.csv；
        // 未登记 requester / 未登记组合是"记为错误"的留痕计数（不据此拒绝，但必须可见）。
        final java.util.Map<String, Integer> policyUnregistered =
                com.dddgn.alice.action.WritePolicyMatrix.unregisteredSeen();
        final java.util.Map<String, Integer> policyUndeclared =
                com.dddgn.alice.action.WritePolicyMatrix.undeclaredSeen();
        final String policy = com.dddgn.alice.action.WritePolicyMatrix.describe()
                + (policyUnregistered.isEmpty() ? "" : "；**未登记 requester**=" + policyUnregistered)
                + (policyUndeclared.isEmpty() ? "" : "；**未登记组合**=" + policyUndeclared);
        source.sendSuccess(() -> Component.literal("[alice] L2 规划期策略表：" + policy), false);
        source.sendSuccess(() -> Component.literal("[alice] L3 执行期预算：" + budget), false);
        source.sendSuccess(() -> Component.literal("[alice] L4 账本：本 bot pending=" + pendingMine
                + "；未闭合临时块=" + tempOpen + "；全局 pending=" + pendingAll
                + "；当前 scope=" + (scopeId == null ? "无" : scopeId)), false);
        source.sendSuccess(() -> Component.literal("[alice] L4 保护区：" + zoneSummary
                + "；bot 脚下=" + (zoneVerdict == null ? "可通过" : zoneVerdict)), false);
        source.sendSuccess(() -> Component.literal("[alice] 最近终态：" + last), false);
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

    /** {@code /alice instruct <指令>}：把操作者原话**直接**交给 LLM 执行（连通性测试通道）。 */
    private static int instruct(CommandSourceStack source, String instruction) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        String result = com.dddgn.alice.decision.GoalDirector.instruct(bot,
                source.getEntity() instanceof ServerPlayer sp ? sp : null, instruction);
        source.sendSuccess(() -> Component.literal("[alice] 直连指令：" + result), false);
        return 1;
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

    /**
     * {@code /alice battery [core|full|list]}（D-197）：跑电池 / 看当前归属表。
     *
     * <p>归属表在 `RegressionBatteryTask.CURATION`，配套文档 `docs/BATTERY_CURATION.md`；
     * 这里把它打印出来，便于"配置漂移"一眼可见（用户要求由 AI 管理，那就得**看得见**）。
     */
    private static int batteryCommand(CommandSourceStack source, String action) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用 bot"));
            return 0;
        }
        if ("list".equals(action)) {
            var curation = com.dddgn.alice.task.RegressionBatteryTask.curationSummary();
            for (String line : curation) {
                source.sendSuccess(() -> Component.literal("[alice] " + line), false);
            }
            return 1;
        }
        boolean full = "full".equals(action);
        if (BotManager.isBusy(bot)) {
            source.sendFailure(Component.literal("[alice] " + BotManager.busyMessage(bot)));
            return 0;
        }
        // 2026-09-13 实测：这里原先是 `null` ⇒ `capability_gate` 的"外来破坏归因"前提缺失
        // （observer==null ⇒ 伪造的两条事件都算 bot 自己 ⇒ afterForeign=0 ⇒ 第一 tick 假红）。
        // 与物品入口保持一致：**能拿到玩家就传玩家**。
        ServerPlayer batteryObserver = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        if (!BotManager.assignRegressionBattery(bot, batteryObserver, full)) {
            source.sendFailure(Component.literal("[alice] " + BotManager.busyMessage(bot)));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 回归电池已开始（"
                + (full ? "FULL：全部项" : "CORE：必要基础 + 当前主线") + "）⇒ 看 [Regression] SUMMARY"), false);
        return 1;
    }

    /** {@code /alice craft station}：**只读**列出候选合成工作站与当前选择（S1-2 / D-192）。 */
    private static int craftStationShow(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用 bot"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 合成工作站 = "
                + com.dddgn.alice.task.craft.CraftStation.describe(bot, 6)), false);
        for (var candidate : com.dddgn.alice.task.craft.CraftStation.candidates(bot, 6)) {
            source.sendSuccess(() -> Component.literal("[alice]   " + candidate.station().id()
                    + "（" + candidate.station().label() + "）"
                    + (candidate.available() ? " 可用：" : " 不可用：") + candidate.reason()), false);
        }
        source.sendSuccess(() -> Component.literal("[alice] 切换：/alice craft station <auto|"
                + String.join("|", com.dddgn.alice.task.craft.CraftStation.all().stream()
                        .map(com.dddgn.alice.task.craft.CraftStation.Descriptor::id).toList())
                + ">（auto = 现状顺序：随身 → 工作台；**不含**升级页签，不自动选优）"), false);
        return 1;
    }

    /** {@code /alice craft station <id|auto>}：设置首选工作站（不认识就如实拒绝，不悄悄改成别的）。 */
    private static int craftStationSet(CommandSourceStack source, String id) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用 bot"));
            return 0;
        }
        if (!com.dddgn.alice.task.craft.CraftStation.select(bot, id)) {
            source.sendFailure(Component.literal("[alice] 未知工作站 " + id + "（可选：auto + "
                    + String.join(", ", com.dddgn.alice.task.craft.CraftStation.all().stream()
                            .map(com.dddgn.alice.task.craft.CraftStation.Descriptor::id).toList()) + "）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 合成工作站已切到 "
                + com.dddgn.alice.task.craft.CraftStation.selected(bot) + "：" 
                + com.dddgn.alice.task.craft.CraftStation.describe(bot, 6)), false);
        return 1;
    }

    /** {@code /alice recipes [file]}：导出运行时配方表 + 物品标签（S5 / D-146）。 */
    private static int recipesDump(CommandSourceStack source, String file) {
        var result = com.dddgn.alice.decision.RecipeDump.dump(source.getServer(), file);
        source.sendSuccess(() -> Component.literal("[alice] 配方导出 " + result.describe()), false);
        source.sendSuccess(() -> Component.literal("[alice] 离线分析：python3 tools/recipe-graph.py "
                + "--recipes <该文件> --target <物品 id>"), false);
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

    /** {@code /alice ask}：列出未决请示（S3 / D-140）。 */
    private static int askList(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendSuccess(() -> Component.literal("[alice] 没有可用 bot"), false);
            return 0;
        }
        var pending = com.dddgn.alice.decision.PermissionGate.pending(bot);
        if (pending.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[alice] 没有未决请示"), false);
            return 1;
        }
        for (var request : pending) {
            long remaining = request.deadlineTick() - source.getServer().getTickCount();
            source.sendSuccess(() -> Component.literal("[alice] " + request.id()
                    + " capability=" + request.capability() + " 默认=" + request.defaultOption()
                    + " 剩余=" + remaining + "tick —— " + request.reason()
                    + "（/alice ask " + request.id() + " allow|deny [once|session|always]）"), false);
        }
        return 1;
    }

    /** {@code /alice ask <id> <option> [scope]}：玩家答复请示（**只有玩家/配置能批准**）。 */
    private static int askAnswer(CommandSourceStack source, String id, String option, String scope) {
        com.dddgn.alice.decision.PermissionGate.Scope parsed;
        try {
            parsed = com.dddgn.alice.decision.PermissionGate.Scope
                    .valueOf(scope.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            source.sendSuccess(() -> Component.literal("[alice] scope 只能是 once/session/always"), false);
            return 0;
        }
        String by = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                ? "player:" + player.getName().getString() : "console";
        boolean ok = com.dddgn.alice.decision.PermissionService.answer(
                com.dddgn.alice.decision.PermissionService.TRANSPORT_COMMAND, source.getServer(), id, option, parsed, by);
        source.sendSuccess(() -> Component.literal(ok
                ? "[alice] 已答复 " + id + " = " + option + "（" + parsed + "，by=" + by + "）"
                : "[alice] 没有这条待答复请示：" + id), false);
        return ok ? 1 : 0;
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

    /**
     * 只读寻路诊断，不分配任务、不改变世界。
     *
     * <p>K-2（2026-09-13）：改用**新内核**（`CorePathPlanner`）—— 原先这里是 legacy `SurfacePathfinder`。
     * 语义更严格：`PlanningStatus` 明确区分 `SEARCH_LIMIT`（预算耗尽，可达性未知）/
     * `GOAL_NOT_LOADED`（未加载）/ `UNREACHABLE`（确实到不了）/ `PARTIAL`（只找到前缀）。
     */
    private static int diagnosePath(CommandSourceStack source, BlockPos goal) {
        ServerLevel level = source.getLevel();
        BlockPos start = source.getEntity() != null ? source.getEntity().blockPosition()
                : level.getSharedSpawnPos();
        com.dddgn.alice.bot.BotPlayer bot = com.dddgn.alice.bot.BotManager.firstInLevel(level);
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 诊断需要一只可用 bot（先用 /alice spawn）"));
            return 0;
        }
        com.dddgn.alice.pathing.core.search.PathPlan plan = new com.dddgn.alice.pathing.core.search.CorePathPlanner()
                .plan(bot, level, com.dddgn.alice.pathing.core.search.PathRequest.of(
                        bot.getUUID().toString(), start, goal, "command:diagnose"));
        String detail = "[alice] 寻路诊断 " + plan.status() + ": "
                + start.toShortString() + " -> " + goal.toShortString()
                + ", movements=" + plan.movements().size() + ", expanded=" + plan.nodesExpanded()
                + ", " + plan.summary();
        if (plan.status() == com.dddgn.alice.pathing.core.search.PlanningStatus.SEARCH_LIMIT
                || plan.status() == com.dddgn.alice.pathing.core.search.PlanningStatus.GOAL_NOT_LOADED
                || plan.partial()) {
            detail += "；**不能据此授权挖通道**（预算耗尽/未加载/只有前缀）";
        }
        String message = detail;
        source.sendSuccess(() -> Component.literal(message), false);
        return plan.reached() ? 1 : 0;
    }

    /** 显示当前容器读写路线（L2 调试/对照开关）。 */
    private static int transferRouteShow(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[alice] 容器读写路线："
                + com.dddgn.alice.transfer.TransferRoutes.describe()), false);
        return 1;
    }

    /** 切换容器读写路线（menu=真实菜单协议 / capability=能力直写）。 */
    private static int transferRouteSet(CommandSourceStack source, String raw) {
        com.dddgn.alice.transfer.TransferRoutes.Route route =
                com.dddgn.alice.transfer.TransferRoutes.parse(raw);
        if (route == null) {
            source.sendFailure(Component.literal("[alice] 未知路线：" + raw + "（可选 menu | capability）"));
            return 0;
        }
        com.dddgn.alice.transfer.TransferRoutes.set(route, source.getTextName());
        source.sendSuccess(() -> Component.literal("[alice] 容器读写路线已切到 "
                + com.dddgn.alice.transfer.TransferRoutes.describe()), false);
        return 1;
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
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

    /** D-059：切换风险开关（默认全关 = Baritone 原样高风险）。 */
    /**
     * `/alice progress [<ticks>]` —— 查看/设置**低频进度事件**间隔（0 = 关，默认关）。
     *
     * <p>为什么要有这个入口（2026-09-17）：`EventThresholds.setProgressEventInterval` 原先**只有夹具在调**
     * ⇒ 游戏内无法打开 `PROGRESS` 事件 ⇒ "推送频率够不够 / 桌面 AI 的 RTT"（`survey/16 §1.3/§12.1`）
     * **根本没有观测手段**。设成 N ⇒ 有任务在跑时每 N tick 报一次「任务 + 进度摘要」（进事件环与日志 `[Events] PROGRESS`）。
     */
    private static int progressInterval(net.minecraft.commands.CommandSourceStack source, Integer ticks) {
        if (ticks == null) {
            source.sendSystemMessage(Component.literal("[alice] 进度事件间隔 = "
                    + com.dddgn.alice.decision.EventThresholds.PROGRESS_EVENT_INTERVAL_TICKS
                    + " tick（0 = 关）。用法：/alice progress <ticks>（建议 200 = 10 秒一次）"));
            return 1;
        }
        com.dddgn.alice.decision.EventThresholds.setProgressEventInterval(ticks);
        source.sendSystemMessage(Component.literal("[alice] 进度事件间隔 = " + ticks
                + " tick" + (ticks == 0 ? "（已关闭）" : "（有任务时每 " + ticks + " tick 报一次 PROGRESS）")));
        return 1;
    }

    private static int riskSwitch(net.minecraft.commands.CommandSourceStack source, String name, boolean value) {
        if (!com.dddgn.alice.pathing.risk.RiskSwitches.set(name, value)) {
            source.sendSystemMessage(Component.literal("[alice] 未知风险开关: " + name));
            return 0;
        }
        // **S-6**：开关是全局默认值，而消费者读的是**按 bot 冻结的画像** ⇒
        // 命令改完必须**重新冻结所有在跑的 bot**，否则"A/B 对比"这个用途会立刻失效（实测要求）。
        com.dddgn.alice.pathing.risk.RiskProfile.freezeAll(
                com.dddgn.alice.bot.BotManager.getAllBots());
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
