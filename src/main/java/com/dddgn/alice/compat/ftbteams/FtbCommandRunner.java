package com.dddgn.alice.compat.ftbteams;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **以某个玩家（真人或我们的假人）的身份执行一条原版命令**（D-321）。
 *
 * <p>FTB 的队伍写入一律走这里，而不是去反射它的内部对象：`/ftbteams party create|invite|join|leave`
 * 是 FTB 自己的公开入口 —— 它的权限判定、事件、认领区转移、落盘全都由它自己走完，
 * Alice 只当"代打的玩家"。这样既不需要猜 FTB 的写入语义，也不会绕过它的规则。
 *
 * <p>回车得到的消息**同时**转发给该玩家（他会像自己敲了这条命令一样看到 FTB 的回话）
 * 并收进 {@link Outcome#lines}（Alice 的聊天报告与离线判据都读它）。
 */
public final class FtbCommandRunner {

    /** 一条命令的结果：`ok` 是 brigadier 返回码 &gt; 0；`lines` 是这条命令自己吐出来的文字。 */
    public record Outcome(boolean ok, int result, List<String> lines) {

        public String text() {
            return String.join(" / ", lines);
        }
    }

    private FtbCommandRunner() {
    }

    /** 命令树里有没有这个根字面量（= 这个模组在不在场，用原版 API 问，不靠反射）。 */
    public static boolean hasCommandRoot(ServerPlayer player, String rootLiteral) {
        if (player == null || player.getServer() == null) {
            return false;
        }
        return player.getServer().getCommands().getDispatcher().getRoot().getChild(rootLiteral) != null;
    }

    /** 以 `player` 的身份跑一条命令（`command` 不带前导 `/`）。 */
    public static Outcome runAs(ServerPlayer player, String command) {
        if (player == null || player.getServer() == null) {
            return new Outcome(false, 0, List.of("no_server_or_player"));
        }
        CommandSourceStack base = player.createCommandSourceStack();
        List<String> collected = new ArrayList<>();
        // `withSource` 只换"消息出口"，位置/维度/权限位/实体全都照抄 ⇒ 代打不会变成换个身份
        CommandSourceStack source = base.withSource(new EchoSource(player, collected));
        int result;
        try {
            result = player.getServer().getCommands().performPrefixedCommand(source, command);
        } catch (Throwable t) {
            collected.add("throw:" + t);
            result = 0;
        }
        return new Outcome(result > 0, result, List.copyOf(collected));
    }

    /**
     * 把消息**转发给原玩家**（他照常像自己敲了这条命令一样看到 FTB 的回话）**并抄一份**给我们自己的报告。
     * 其余方法委托给该玩家（`Entity implements CommandSource` ⇒ 权限位与反馈语义保持原样）。
     */
    private record EchoSource(CommandSource delegate, List<String> sink) implements CommandSource {

        @Override
        public void sendSystemMessage(Component message) {
            sink.add(message.getString());
            delegate.sendSystemMessage(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return delegate.acceptsSuccess();
        }

        @Override
        public boolean acceptsFailure() {
            return delegate.acceptsFailure();
        }

        @Override
        public boolean shouldInformAdmins() {
            return delegate.shouldInformAdmins();
        }

        @Override
        public boolean alwaysAccepts() {
            return delegate.alwaysAccepts();
        }
    }
}
