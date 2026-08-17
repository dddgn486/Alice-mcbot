package com.dddgn.aibot.command;

import com.dddgn.aibot.bot.BotManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * M0 测试命令入口(后续 M4 将被 AI 工具调用取代)。
 * <pre>
 *   /aibot spawn [name]     在命令执行者位置生成假人
 *   /aibot mine &lt;x y z&gt;     让假人去挖指定坐标的方块
 * </pre>
 */
@Mod.EventBusSubscriber(modid = "aibot", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotCommand {

    private BotCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("aibot")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> spawn(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("mine")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> mine(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))));
    }

    private static int spawn(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        BlockPos pos = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : new BlockPos(level.getSharedSpawnPos());
        FakePlayer bot = BotManager.spawn(level, pos, name);
        source.sendSuccess(() -> Component.literal(
                "[aibot] 假人 " + name + " 已生成于 " + pos.toShortString()), false);
        return 1;
    }

    private static int mine(CommandSourceStack source, BlockPos target) {
        ServerLevel level = source.getLevel();
        if (level.getBlockState(target).isAir()) {
            source.sendFailure(Component.literal("[aibot] 目标位置是空气"));
            return 0;
        }
        FakePlayer bot = BotManager.firstOrSpawn(level, target);
        BotManager.assignMine(bot, target);
        source.sendSuccess(() -> Component.literal(
                "[aibot] " + bot.getName().getString() + " 开始挖掘 " + target.toShortString()), false);
        return 1;
    }
}
