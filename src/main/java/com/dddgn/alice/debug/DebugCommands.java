package com.dddgn.alice.debug;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.fixture.FixtureDispatch;
import com.dddgn.alice.gui.BotInventoryService;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.PerceptionProfile;
import com.dddgn.alice.perception.PerceptionSnapshot;
import com.dddgn.alice.transfer.ChestEndpointRef;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferLedgerData;
import com.dddgn.alice.transfer.TransferRequest;
import com.dddgn.alice.transfer.TransferSelectionData;
import com.dddgn.alice.transfer.TransferSelectionSubmission;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.dddgn.alice.protection.AreaData;
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
import com.dddgn.alice.fixture.RegressionBatteryTask;
import com.dddgn.alice.fixture.ShoreEscapeFixture;

/**
 * **开发期/调试 `/alice` 子命令**（`D-560`：`debug/` 里没有任何一条够格给发行包当"严格调试工具"的指令
 * ⇒ 这些命令今天**全部**是开发期入口）。
 *
 * <p>⚠️ 本包是**开发期桶**（`R1′`）⇒ 允许依赖 `fixture/`、允许发料（`DEV_CREATE`）。
 * ⛔ 判据是「**结果不该被发料行为污染**」（`D-560` 第 4 条）⇒ 发料必须**进结果**（`provision` 维）。
 *
 * <p>⚠️ 本类的 {@code register} **同样必须带 `.requires(source -> source.hasPermission(2))`** ——
 * Brigadier 合并同名子节点时**不复制** `requires` ⇒ 两侧都带才与注册顺序无关（同刀门禁钉住）。
 */
@Mod.EventBusSubscriber(modid = "alice", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DebugCommands {

    private DebugCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("alice")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mine")
                        // ⭐ `D-360` 真机地形实测入口：**零参数**就地在 bot 脚下开矿（测试纪律：不许要求坐标）
                        .then(Commands.literal("here").executes(ctx -> mineHere(ctx.getSource())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> mine(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("road")
                        .then(Commands.literal("build")
                                .executes(ctx -> buildRoad(ctx.getSource())))
                                 .then(Commands.literal("buildbybot")
                                         .executes(ctx -> buildRoadByBot(ctx.getSource()))))
                .then(Commands.literal("observe")
                        .executes(ctx -> observe(ctx.getSource())))
                // ⭐ 用户 2026-09-21 第十二轮裁定：**「浮起来之后自己走上岸」的真人入口**（零参数）。
                // 一条命令 = 建孤立场景（平台 + 3×3×3 水池）+ 把 bot 放进池底 + 清任务 + 按住决策层 + 玩家到观察点。
                // 应当看到：① 立刻上浮；② 头出水后**自己走到平台干格**上；③ 全程**不放不挖**（纯通行）。
                .then(Commands.literal("shore-escape-test")
                        .executes(ctx -> shoreEscapeTest(ctx.getSource())))
                // K-3：**取消当前任务**（`bot-control stop` 只停移动输入，不停任务）
                .then(Commands.literal("authz")
                        .executes(ctx -> authzSnapshot(ctx.getSource())))
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
                                    "[alice] 挖掘站位调参：" + com.dddgn.alice.reach.MiningTuning.describe()));
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
                                    "[alice] 连锁挖掘：" + com.dddgn.alice.reach.MiningTuning.chainMode()
                                            + "（默认 OFF=原版单格）"
                                            + " mod=" + (com.dddgn.alice.action.mining.ChainMining.available()
                                                    ? "present" : "absent")
                                            + " settings=" + com.dddgn.alice.action.mining.ChainMining.settingsSummary()
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
                .then(Commands.literal("restore")
                        .executes(ctx -> restore(ctx.getSource(), true)))
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
                        .then(Commands.literal("list").executes(ctx -> batteryCommand(ctx.getSource(), "list")))
                        // ⭐ `D-521` **单步**：只跑一步 + 把观察者传到现场（名字可 Tab 补全，也可从
                        // `/alice battery list` 的清单里点 —— 两者都**不需要记/不需要输入坐标**）
                        .then(Commands.literal("single")
                                .then(Commands.argument("step", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (String stepName : com.dddgn.alice.fixture
                                                    .RegressionBatteryTask.knownStepNames()) {
                                                builder.suggest(stepName);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> batteryCommand(ctx.getSource(),
                                                "single:" + StringArgumentType.getString(ctx, "step"))))))
                .then(Commands.literal("recipes")
                        .executes(ctx -> recipesDump(ctx.getSource(), "alice-recipes.json"))
                        .then(Commands.argument("file", StringArgumentType.word())
                                .executes(ctx -> recipesDump(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file")))))
                // S3.5 收集授权：查看 / 提升为永久 / 清空
                .then(Commands.literal("auto-mine")
                        .then(Commands.argument("tag", StringArgumentType.string())
                                .executes(ctx -> autoMine(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "tag"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> autoMine(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "tag"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
        );
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
        if (!BotManager.assignMineJob(bot, source.getPlayer(), targetSpec, quota, radius,
                com.dddgn.alice.fixture.DevCreateProvision.INSTANCE)) {
            source.sendFailure(Component.literal("[alice] " + BotManager.busyMessage(bot)));
            return 0;
        }
        String resultMsg = "挖掘 Job 已启动: 目标 " + targetName + " 配额 " + quota
                + " 半径 " + radius + "（决策与终态见 [Job] 日志）";
        source.sendSuccess(() -> Component.literal("[alice] " + bot.getName().getString() + " " + resultMsg), false);
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
        // （区块级最小覆盖）"在**启动之前**摊给玩家看。
        // ⛔ 2026-10-01：原来这里还报"会不会与安全区冲突" —— 安全区退化后**该判据已删除**
        // （覆盖规则今天只剩"任何区域都不可覆盖 job 区"）。
        if (region != null) {
            var server = source.getServer();
            var level = source.getLevel();
            var area = new com.dddgn.alice.region.JobAreaRegistry.WorkingArea(
                    level.dimension().location(), region.minX(), region.minZ(),
                    region.maxX(), region.maxZ());
            var chunks = area.chunkCover();
            var active = com.dddgn.alice.region.JobAreaRegistry.zoneOf(server, bot.getUUID());
            source.sendSuccess(() -> Component.literal("[alice] 任务区（派生）：工作区域 " + area.describe()
                    + " blocks=" + area.areaXZ() + " ⇒ 区块最小覆盖 chunks=" + chunks.size()
                    + "（**单向派生**：工作区域 ⇒ 任务区）｜覆盖规则=可覆盖保护区与安全区；"
                    + "⛔ 任何区域都不可覆盖 job 区"
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
                region, com.dddgn.alice.fixture.DevCreateProvision.INSTANCE);
        source.sendSuccess(() -> Component.literal(ok
                ? "[alice] 可持续伐木区已启动 region=" + region.describe()
                : "[alice] " + BotManager.busyMessage(bot)), false);
        return ok ? 1 : 0;
    }


    /** 触发作用域恢复（J6-b 兜底）：拆掉我方放置的临时方块并销账。 */
    private static int restore(CommandSourceStack source, boolean all) {
        var bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用 bot"));
            return 0;
        }
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        if (!BotManager.assignRestore(bot, source.getPlayer(), all, com.dddgn.alice.fixture.DevCreateProvision.INSTANCE)) {
            source.sendFailure(Component.literal("[alice] 无可恢复项，或 bot 正忙"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] 已启动恢复任务（详见 [Restore] 日志；"
                + "结束后用 /alice ledger 确认 pending=0）"), false);
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
        for (com.dddgn.alice.pathing.calc.MovementType t
                : com.dddgn.alice.pathing.calc.CapabilityGate.PURE_TRAVERSAL_TYPES) {
            if (pure.length() > 0) {
                pure.append(", ");
            }
            pure.append(t);
        }
        final String pureText = pure.toString();

        // L3 执行期：写入预算与拒绝计数（D-106）。**无作用域时闸门根本没生效**，
        // 此时不能打印各桶"余量"（那会把未生效的上限当成真实额度）——只报未生效 + 作用域内默认上限。
        final String scopeId = com.dddgn.alice.region.authz.Quota.scopeOf(bot);
        final String budget = scopeId != null
                ? "scope=" + scopeId
                        + "；破坏 余" + com.dddgn.alice.region.authz.Quota.remainingBreaks(bot)
                        + "（已拒 " + com.dddgn.alice.region.authz.Quota.refusedBreaks(bot) + "）"
                        + " / 放置 余" + com.dddgn.alice.region.authz.Quota.remainingPlaces(bot)
                        + "（已拒 " + com.dddgn.alice.region.authz.Quota.refusedPlaces(bot) + "）"
                        + " / 容器写入 余" + com.dddgn.alice.region.authz.Quota.remainingContainerWrites(bot)
                : "无作用域 ⇒ 闸门未生效（不计数、不拦截，仅 [Quota] no_scope 留痕）；"
                        + "默认上限（仅作用域内生效）破坏" + com.dddgn.alice.region.authz.Quota.DEFAULT_MAX_BREAKS
                        + "/放置" + com.dddgn.alice.region.authz.Quota.DEFAULT_MAX_PLACES
                        + "/容器" + com.dddgn.alice.region.authz.Quota.DEFAULT_MAX_CONTAINER_WRITES;

        // L4 收尾期：账本 pending（只记放置）+ 保护区判定
        final int pendingMine = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(server, bot.getUUID()).size();
        final int pendingAll = com.dddgn.alice.ledger.WorldModLedger.size(server);
        final int tempOpen = BotManager.pendingTemporaryCount(bot);
        final String zoneSummary = com.dddgn.alice.protection.AreaData.get(server).summary();
        final String zoneVerdict = com.dddgn.alice.protection.AreaData.get(server)
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
                com.dddgn.alice.write.WritePolicyMatrix.unregisteredSeen();
        final java.util.Map<String, Integer> policyUndeclared =
                com.dddgn.alice.write.WritePolicyMatrix.undeclaredSeen();
        final String policy = com.dddgn.alice.write.WritePolicyMatrix.describe()
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
            var curation = com.dddgn.alice.fixture.RegressionBatteryTask.curationSummary();
            for (String line : curation) {
                source.sendSuccess(() -> Component.literal("[alice] " + line), false);
            }
            // ⭐⭐ `D-521` **可点击的单步清单**（用户 2026-09-29：「每个单元测试隔得距离太远，
            // 用电池不好观察」）⇒ 点一下名字 = 只跑那一步 + **把你传到现场**，跑完送回原处。
            // 为什么用"聊天里可点"而不是做 GUI：零参数、零新界面、零网络协议，成本差一个量级。
            var names = new java.util.ArrayList<>(
                    com.dddgn.alice.fixture.RegressionBatteryTask.knownStepNames());
            java.util.Collections.sort(names);
            source.sendSuccess(() -> Component.literal(
                    "[alice] ↓ 点步名 = 只跑这一步（会把你传到现场，跑完送回原处）；共 "
                            + names.size() + " 步："), false);
            // 每行 4 个，避免一行长得没法点
            for (int i = 0; i < names.size(); i += 4) {
                net.minecraft.network.chat.MutableComponent row = Component.literal("[alice]   ");
                for (int j = i; j < Math.min(i + 4, names.size()); j++) {
                    String stepName = names.get(j);
                    row.append(Component.literal("[" + stepName + "]").withStyle(style -> style
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/alice battery single " + stepName))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("只跑 " + stepName + "（点一下即开始）")))));
                    row.append(Component.literal("  "));
                }
                source.sendSuccess(() -> row, false);
            }
            return 1;
        }
        // ⭐ `D-521` `single:<step>`：只跑名单里的那一步（复用无头通道的 `setOnlySteps` 定向机制）。
        String onlyStep = action.startsWith("single:") ? action.substring("single:".length()) : null;
        if (onlyStep != null
                && !com.dddgn.alice.fixture.RegressionBatteryTask.knownStepNames().contains(onlyStep)) {
            source.sendFailure(Component.literal("[alice] 不认识的步名「" + onlyStep
                    + "」⇒ 先 `/alice battery list` 看清单（Tab 也能补全）"));
            return 0;
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
        // ⚠️ `onlySteps` 是**静态**的（无头通道的机制）⇒ 两条路都必须显式设定，否则会**串味**：
        // 上一轮单步留下的名单会让这一轮 `/alice battery core` 只跑那一步（静默）。
        com.dddgn.alice.fixture.RegressionBatteryTask.setOnlySteps(
                onlyStep == null ? null : java.util.List.of(onlyStep));
        if (!FixtureDispatch.assignRegressionBattery(bot, batteryObserver, full)) {
            com.dddgn.alice.fixture.RegressionBatteryTask.setOnlySteps(null);
            source.sendFailure(Component.literal("[alice] " + BotManager.busyMessage(bot)));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(onlyStep == null
                ? "[alice] 回归电池已开始（" + (full ? "FULL：全部项" : "CORE：必要基础 + 当前主线")
                        + "）⇒ 看 [Regression] SUMMARY"
                : "[alice] 单步已开始：**" + onlyStep + "** ⇒ 已把你传到现场，跑完送回原处；"
                        + "读数看日志 [Regression] SUMMARY"), false);
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
            // ⭐ `Z2`（2026-09-23）：**不许把"账本空"说成"建拆同权已闭合"** —— `Z1` 之后区外
            // **不记账**，所以"空"还可能是"压根没记过"（`D-398` R1/R2）。把人口一起摆出来，
            // 让读的人自己分辨（这是一条玩家可见读数，误读成本最高）。
            long recorded = com.dddgn.alice.ledger.WorldModLedger.recordedCount(server);
            long wild = com.dddgn.alice.ledger.WorldModLedger.outsideSkipCount(server);
            source.sendSuccess(() -> Component.literal("[alice] （账本为空：迄今记账 " + recorded
                    + " 次 / 区外放置被跳过 " + wild + " 次）—— 区外修改既不入账也不恢复（D-398）；"
                    + "「记账 0 次」与「建拆同权已闭合」不是一回事"), false);
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
        com.dddgn.alice.pathing.calc.PathPlan plan = new com.dddgn.alice.pathing.calc.CorePathPlanner()
                .plan(bot, level, com.dddgn.alice.pathing.calc.PathRequest.of(
                        bot.getUUID().toString(), start, goal, "command:diagnose"));
        String detail = "[alice] 寻路诊断 " + plan.status() + ": "
                + start.toShortString() + " -> " + goal.toShortString()
                + ", movements=" + plan.movements().size() + ", expanded=" + plan.nodesExpanded()
                + ", " + plan.summary();
        if (plan.status() == com.dddgn.alice.pathing.calc.PlanningStatus.SEARCH_LIMIT
                || plan.status() == com.dddgn.alice.pathing.calc.PlanningStatus.GOAL_NOT_LOADED
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
        if (!FixtureDispatch.assignTraverseDiagnostic(bot, to)) {
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
        if (!FixtureDispatch.assignDiagonalDiagnostic(bot, to)) {
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
        if (!FixtureDispatch.assignAscendDiagnostic(bot, to)) {
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
        if (!FixtureDispatch.assignPathSessionDiagnostic(bot, goal)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R4 路径会话已提交 bot="
                + botName + " → " + goal.toShortString() + "（规划 + 逐段执行）"), false);
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
        if (!FixtureDispatch.assignPathingBattery(bot, anchor)) {
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
        com.dddgn.alice.pathing.calc.PathPlan plan =
                new com.dddgn.alice.pathing.calc.CorePathPlanner()
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
        if (!FixtureDispatch.assignChainDiagnostic(bot, planned)) {
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
        if (!FixtureDispatch.assignDescendDiagnostic(bot, to)) {
            source.sendFailure(Component.literal("[alice] bot_busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] R2-C Descend submitted bot="
                + bot.getName().getString() + " from=" + from.toShortString()
                + " to=" + to.toShortString()), false);
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
        BotManager.assignRoadBuild(bot, plan, com.dddgn.alice.fixture.DevCreateProvision.INSTANCE);
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


    /**
     * **真机地形实测：就地开矿**（`/alice mine here`，零参数）。
     *
     * <p>为什么需要它（用户 2026-09-20 "先搞 A"）：数值层要的是**真实地形**里的决策行为
     * （水平位移分布 / 向下占比），而既有入口 `/alice mine <x y z>` **要求坐标** ⇒ 违反"测试入口零参数"纪律，
     * 也会把"选在哪测"变成人肉决策。
     *
     * <p>它做三件事：① 以 **bot 当前位**为中心、默认半径/配额起一个**多目标种类**（`#forge:ores`）挖掘任务
     * （顺带压到成本模型与价值表）；② **上手动占用锁** ⇒ LLM 起任务被拒且可见（`ManualTestLock`）；
     * ③ 打开采集 ⇒ 终态打一行 `[MineSurvey] SUMMARY`（口径全记，见 `MineSurveyStats`）。
     *
     * <p>⚠️ 未认领区块是前提（FTB Chunks 会拒绝认领区内的假人破坏 ⇒ 那量到的是"权限"不是"行为"）。
     */
    /**
     * `/alice shore-escape-test`（零参数）：建**孤立**场景 + 把 bot 放进池底 + 清任务 + 按住决策层。
     *
     * <p>它验的是"无任务的水下 bot 会先浮、再走到 8 格内的干站位"（用户 2026-09-21 第十二轮裁定）；
     * 场景与判据见 {@link com.dddgn.alice.fixture.ShoreEscapeFixture} 的注释，离线判据见电池步
     * `survival_shore_escape`。
     */
    private static int shoreEscapeTest(CommandSourceStack source) {
        try {
            String armed = com.dddgn.alice.fixture.ShoreEscapeFixture.arm(source.getPlayerOrException());
            source.sendSuccess(() -> Component.literal("[alice] 「浮起来后自己上岸」已武装：" + armed), false);
            source.sendSuccess(() -> Component.literal("[alice] 应当看到：① bot 立刻浮到水面；"
                    + "② 头出水后**自己走到平台干格**上（日志 `[Survival] … ⇒ 找岸（**纯通行**、零写权）`）；"
                    + "③ 全程**不放方块、不挖方块**。看 15 秒即可。"), false);
            return 1;
        } catch (Exception error) {
            source.sendFailure(Component.literal("[alice] 武装失败：" + error.getMessage()));
            return 0;
        }
    }


    private static int mineHere(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BotPlayer bot = BotManager.firstOrSpawn(level, BlockPos.containing(source.getPosition()));
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 没有可用的 bot"));
            return 0;
        }
        BlockPos start = bot.blockPosition();
        // ① 上锁（LLM 的唯一执行入口 = `BotManager.assignJob` ⇒ 从这里起全部被拒并记进事件环）
        com.dddgn.alice.bot.ManualTestLock.on("mine here @" + start.toShortString());
        // ② 开采集（起点口径 = bot 起任务时的脚位）
        com.dddgn.alice.job.mine.MineSurvey.enable(start, level.getGameTime(), "mine here");
        // ③ 起任务：**手动入口**（绕过占用锁；其它闸门照旧）
        // ③b `D-361` 种类分配：**从配置读**（零参数入口 ⇒ 口径只能写在 `config/alice-mine.json` 里，
        //     不许要求玩家输参数）。空 ⇒ 单一总配额（= 今天的行为，逐字不变）。
        java.util.List<String> kindQuotas = com.dddgn.alice.job.mine.MineCostConfig.load().kindQuotas();
        com.dddgn.alice.job.mine.MineKindPlan.Parsed parsed =
                com.dddgn.alice.job.mine.MineKindPlan.parse(kindQuotas);
        int quota = MINE_SURVEY_QUOTA;
        if (!parsed.specs().isEmpty()) {
            // 总配额 = **各条之和**（用户裁定：配额当硬上限 ⇒ 种类分配只收紧、不放开）
            quota = parsed.specs().stream()
                    .mapToInt(com.dddgn.alice.job.mine.MineKindPlan.Spec::count).sum();
        }
        var request = parsed.specs().isEmpty()
                ? com.dddgn.alice.job.JobRequest.mine(start, MINE_SURVEY_RADIUS, quota,
                        MINE_SURVEY_MAX_TICKS, MINE_SURVEY_TAG)
                : com.dddgn.alice.job.JobRequest.mineKinds(start, MINE_SURVEY_RADIUS, quota,
                        MINE_SURVEY_MAX_TICKS, MINE_SURVEY_TAG, kindQuotas);
        // 归因：玩家入口的指派点必须标（F1 规则要求紧邻；手动窗口自己开关，异常也不会把锁留着）
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        com.dddgn.alice.bot.ManualTestLock.beginManualWindow();
        boolean started;
        try {
            started = BotManager.assignJob(bot, source.getPlayer(), request,
                    com.dddgn.alice.fixture.DevCreateProvision.INSTANCE);
        } finally {
            com.dddgn.alice.bot.ManualTestLock.endManualWindow();
        }
        if (!started) {
            com.dddgn.alice.job.mine.MineSurvey.reset();
            com.dddgn.alice.bot.ManualTestLock.off("起任务失败");
            source.sendFailure(Component.literal("[alice] 挖掘任务没起来（看日志里的拒绝理由）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[alice] " + bot.getName().getString()
                + " 就地开矿：" + request.describe()
                + "（LLM 起任务已阻断；结束时看日志 [MineSurvey] SUMMARY）"), false);
        return 1;
    }

    /** 实测默认参数（零参数入口的口径；要改就改这里，别让玩家输坐标）。 */
    private static final int MINE_SURVEY_RADIUS = 24;
    /**
     * 配额 8 → **64**（用户 2026-09-20 裁定：「让每轮数据更充分」）。
     *
     * <p>为什么 8 不够：第一轮实测**整条带内候选只有 55 个**，8 个配额在**同一条矿脉里**就被截断
     * （带内还剩 4 格煤没挖）⇒ 判不了「簇挖完没有」，也看不出扫描冻在 7% 的后果。
     * 64 &gt; 带内 55 ⇒ 这个值**天然会把「候选耗尽」那一支走到**（选不出候选时才 {@code advance()}）
     * ⇒ 顺带实测「扫描能不能接着往下推进」（用户裁定**先不改扫描方式**）。
     */
    private static final int MINE_SURVEY_QUOTA = 64;
    /**
     * `maxTicks` 3600 → **12000**（10 分钟）：配额 ×4 之后，按实测单目标 80–200 tick（含开路/捡拾）估
     * ⇒ 64 个目标 ≈ 5000–13000 tick，3600 会先撞 `goal_timeout` 而拿不到满额数据。
     * ⚠️ 它只是**上限**：跑完就结束（第一轮 8 目标只用 644 tick），不会固定占满。
     */
    private static final int MINE_SURVEY_MAX_TICKS = 12000;
    /** 多目标种类（`#forge:ores`）⇒ 走成本模型与价值表；单种类任务的价值项是惰性的。 */
    private static final String MINE_SURVEY_TAG = "forge:ores";


    private static int mine(CommandSourceStack source, BlockPos target) {
        ServerLevel level = source.getLevel();
        if (level.getBlockState(target).isAir()) {
            source.sendFailure(Component.literal("[alice] 目标位置是空气"));
            return 0;
        }
        BotPlayer bot = BotManager.firstOrSpawn(level, target);
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER);
        BotManager.assignMine(bot, target, com.dddgn.alice.fixture.DevCreateProvision.INSTANCE);
        source.sendSuccess(() -> Component.literal(
                "[alice] " + bot.getName().getString() + " 开始挖掘 " + target.toShortString()), false);
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
        if (!com.dddgn.alice.reach.MiningTuning.setChainMode(mode)) {
            source.sendSystemMessage(Component.literal("[alice] 未知连锁策略: " + mode
                    + "（可选 off / auto / force）"));
            return 0;
        }
        String summary = "[alice] 连锁挖掘 = "
                + com.dddgn.alice.reach.MiningTuning.chainMode()
                + " mod=" + (com.dddgn.alice.action.mining.ChainMining.available() ? "present" : "absent");
        source.sendSystemMessage(Component.literal(summary));
        com.dddgn.alice.log.BotLog.info("[ChainMining] 策略切换 mode={} all={}",
                mode, com.dddgn.alice.reach.MiningTuning.describe());
        return 1;
    }


    private static int miningEstimate(net.minecraft.commands.CommandSourceStack source, String mode) {
        if (!com.dddgn.alice.reach.MiningTuning.setEstimateMode(mode)) {
            source.sendSystemMessage(Component.literal("[alice] 未知估算方案: " + mode));
            return 0;
        }
        source.sendSystemMessage(Component.literal("[alice] 挖掘估算方案已切换："
                + com.dddgn.alice.reach.MiningTuning.describe()));
        com.dddgn.alice.log.BotLog.info("[MiningTuning] estimate={} all={}", mode,
                com.dddgn.alice.reach.MiningTuning.describe());
        return 1;
    }
}
