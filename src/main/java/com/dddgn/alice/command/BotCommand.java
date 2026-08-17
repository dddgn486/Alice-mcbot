package com.dddgn.alice.command;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.PerceptionProfile;
import com.dddgn.alice.perception.PerceptionSnapshot;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(Commands.literal("observe")
                        .executes(ctx -> observe(ctx.getSource())))
                .then(Commands.literal("selftest")
                        .executes(ctx -> selftest(ctx.getSource())))
                .then(Commands.literal("scan")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> scan(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))));
    }

    /** M2 接口扫描:输出指定方块的 capability 接口清单(物品/能量/流体/气体等)。 */
    private static int scan(CommandSourceStack source, BlockPos target) {
        String result = com.dddgn.alice.capability.InterfaceScanner.scan(source.getLevel(), target);
        BotLog.info("接口扫描:\n{}", result);
        source.sendSuccess(() -> Component.literal("[alice] 接口扫描完成,详见日志"), false);
        return 1;
    }

    /** 手动触发自检(默认不自动跑,审查点 R8)。 */
    private static int selftest(CommandSourceStack source) {
        com.dddgn.alice.bot.BotSelftest.start();
        source.sendSuccess(() -> Component.literal("[alice] 自检已启动,结果见日志"), false);
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
