package com.dddgn.alice.command;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.PerceptionProfile;
import com.dddgn.alice.perception.PerceptionSnapshot;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.dddgn.alice.protection.SafeZoneData;
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
                .then(Commands.literal("mine")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> mine(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("soft-probe")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> softProbe(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("soft-probe-travel")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> softProbeTravel(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("road")
                        .then(Commands.literal("build")
                                .executes(ctx -> buildRoad(ctx.getSource())))
                                 .then(Commands.literal("buildbybot")
                                         .executes(ctx -> buildRoadByBot(ctx.getSource()))))
                .then(Commands.literal("observe")
                        .executes(ctx -> observe(ctx.getSource())))
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
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("auto-mine")
                        .then(Commands.argument("tag", StringArgumentType.string())
                                .executes(ctx -> autoMine(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "tag")))))
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
    private static int autoMine(CommandSourceStack source, String tagStr) {
        ServerLevel level = source.getLevel();
        if (tagStr.startsWith("#")) {
            tagStr = tagStr.substring(1);
        }
        net.minecraft.resources.ResourceLocation id;
        try {
            id = new net.minecraft.resources.ResourceLocation(tagStr);
        } catch (Exception e) {
            source.sendFailure(Component.literal("[alice] 格式错误: " + tagStr));
            return 0;
        }
        BlockPos center = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : new BlockPos(level.getSharedSpawnPos());

        // 先尝试按标签解析(存在同名标签才用标签模式)
        net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag =
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, id);
        boolean tagExists = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
                .getTag(tag).isPresent();

        BlockPos target;
        String mode;
        if (tagExists) {
            target = com.dddgn.alice.decision.AutoMineDecision.pickNearest(
                    level, center, tag, com.dddgn.alice.decision.AutoMineDecision.SCAN_RADIUS);
            mode = "标签";
        } else {
            // 无同名标签 → 按方块 ID 精确匹配
            net.minecraft.world.level.block.Block block =
                    net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id);
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                source.sendFailure(Component.literal(
                        "[alice] 既不是标签也不是方块 ID: " + tagStr));
                return 0;
            }
            target = com.dddgn.alice.decision.AutoMineDecision.pickNearestBlock(
                    level, center, block, com.dddgn.alice.decision.AutoMineDecision.SCAN_RADIUS);
            mode = "方块";
        }

        if (target == null) {
            source.sendFailure(Component.literal("[alice] 扫描半径 "
                    + com.dddgn.alice.decision.AutoMineDecision.SCAN_RADIUS
                    + " 内没找到 " + tagStr + "(" + mode + ")"));
            return 0;
        }
        // 执行
        BotPlayer bot = BotManager.firstOrSpawn(level, target);
        BotManager.assignTarget(bot, com.dddgn.alice.task.TaskTarget.block(target));
        String resultMsg = "自动挖矿(" + mode + "): 最近 " + tagStr + " @ " + target.toShortString();
        source.sendSuccess(() -> Component.literal("[alice] " + bot.getName().getString() + " " + resultMsg), false);
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

    /** 输出当前 bot 的任务可回收状态，不改变任务。 */
    private static int status(CommandSourceStack source) {
        BotPlayer bot = BotManager.firstInLevel(source.getLevel());
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 当前维度没有已生成的假人"));
            return 0;
        }
        HazardState hazard = SurvivalSystem.current(bot);
        String text = "[alice] " + bot.getName().getString() + " busy=" + BotManager.isBusy(bot)
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
                    source.getLevel().destroyBlock(pos, false);
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

    private static int softProbe(CommandSourceStack source, BlockPos target) {
        ServerLevel level = source.getLevel();
        BotPlayer bot = BotManager.firstInLevel(level);
        BlockPos origin = bot != null ? bot.blockPosition()
                : source.getEntity() != null ? source.getEntity().blockPosition() : level.getSharedSpawnPos();
        com.dddgn.alice.task.SoftMoveProbeTask.ProbeValidation validation =
                com.dddgn.alice.task.SoftMoveProbeTask.validate(level, origin, target);
        if (!validation.valid()) {
            source.sendFailure(Component.literal("[alice] 软移动探针无效: " + validation.reason()));
            return 0;
        }
        if (bot == null) {
            bot = BotManager.spawn(level, origin, "SoftProbe");
        }
        if (BotManager.isBusy(bot)) {
            source.sendFailure(Component.literal("[alice] bot 当前有任务，未启动软移动探针"));
            return 0;
        }
        BotManager.assignSoftMoveProbe(bot, target);
        source.sendSuccess(() -> Component.literal("[alice] 已启动 SOFT_SURFACE 软移动探针: "
                + origin.toShortString() + " -> " + target.toShortString()), false);
        return 1;
    }

    /** 原版 travel 物理对比探针；仅客户端实验，不替代金斧默认 SELF_MOVE。 */
    private static int softProbeTravel(CommandSourceStack source, BlockPos target) {
        ServerLevel level = source.getLevel();
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            source.sendFailure(Component.literal("[alice] 请先生成 bot，再运行 travel 对比探针"));
            return 0;
        }
        com.dddgn.alice.task.SoftMoveProbeTask.ProbeValidation validation =
                com.dddgn.alice.task.SoftMoveProbeTask.validate(level, bot.blockPosition(), target);
        if (!validation.valid()) {
            source.sendFailure(Component.literal("[alice] travel 探针无效: " + validation.reason()));
            return 0;
        }
        if (BotManager.isBusy(bot)) {
            source.sendFailure(Component.literal("[alice] bot 当前有任务，未启动 travel 对比探针"));
            return 0;
        }
        BotManager.assignSoftMoveProbe(bot, target,
                com.dddgn.alice.pathing.SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
        source.sendSuccess(() -> Component.literal("[alice] 已启动 NATIVE_TRAVEL 对比探针: "
                + bot.blockPosition().toShortString() + " -> " + target.toShortString()), false);
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
}
