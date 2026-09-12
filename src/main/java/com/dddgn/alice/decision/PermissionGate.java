package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **请示闸门**（S3 请示层 / D-140）：需要**人**决断的事，必须问，而且**超时=拒绝**。
 *
 * <p>为什么必须有它：`DECISION_LAYER_DESIGN.md` §2 的第三条通道。设计原则（用户 2026-09-12 定案）：
 * <ul>
 *   <li>**能力分级** `AUTO / NOTIFY / ASK / IGNORE`（默认值在代码里，玩家可改、可 `always` 记住）；</li>
 *   <li>**超时按默认档处理**（`ASK` 的默认 = **拒绝**），并且**必须有"自动返回"的收尾路径**
 *       —— 绝不让"没批准"变成"卡死"；</li>
 *   <li>**LLM 只能"请求"，不能"批准"**；批准权属于玩家或配置默认值；</li>
 *   <li>每条请示与答复**留审计**（谁批的、什么时候、人工还是超时）。</li>
 * </ul>
 *
 * <p>本类只做**闸门与管理**：发起请示 → 等答复/超时 → 给出结论（`Decision`）。
 * 真正的能力实现（例如"自行取工具材料"）放在各自的执行层，用 {@link #decide} 拿结论。
 */
public final class PermissionGate {

    /** 能力分级。 */
    public enum Policy {
        /** 直接做（低风险、可逆）。 */
        AUTO,
        /** 直接做 + 事后一行提醒（静默提醒模式）。 */
        NOTIFY,
        /** 必须批准；**超时按拒绝**。 */
        ASK,
        /** 永不做（连问都不问）。 */
        IGNORE
    }

    /** 答复范围。 */
    public enum Scope {
        ONCE, SESSION, ALWAYS
    }

    /** 一次请示。 */
    public record Request(String id, UUID botId, String capability, String reason,
                          List<String> options, String defaultOption, long deadlineTick,
                          Map<String, String> context) {
        public String describe() {
            return "id=" + id + " capability=" + capability + " reason=" + reason
                    + " options=" + options + " default=" + defaultOption
                    + " deadline=" + deadlineTick;
        }
    }

    /** 一次结论。 */
    public record Decision(boolean allowed, String option, String decidedBy, Scope scope, String note) {
        public String describe() {
            return (allowed ? "ALLOW" : "DENY") + " option=" + option + " by=" + decidedBy
                    + " scope=" + scope + (note == null || note.isBlank() ? "" : " note=" + note);
        }
    }

    // 能力名（字符串而非枚举：未来模组/任务可以注册自己的，不需要改这个文件）
    public static final String CAP_FETCH_TOOLS = "fetch_tool_materials";
    public static final String CAP_DEMO = "demo_ask";

    private static final Map<String, Policy> DEFAULTS = new LinkedHashMap<>();
    static {
        // 用户裁定：改世界/占用玩家资源必须 ASK；取材料这类"自己动手"先 NOTIFY（S3 起为 ASK 以便验证）
        DEFAULTS.put(CAP_FETCH_TOOLS, Policy.ASK);
        DEFAULTS.put(CAP_DEMO, Policy.ASK);
        // D-138 裁定：掉落物归属策略 —— 我方（直接/间接）与授权区 AUTO；FOREIGN 默认 **ASK**
        // （被动路径上 ASK 会被直接拦下，不弹请示；要捡只能显式派活/先授权）
        DEFAULTS.put(DropPolicy.CAP_OURS_DIRECT, Policy.AUTO);
        DEFAULTS.put(DropPolicy.CAP_OURS_INDIRECT, Policy.AUTO);
        DEFAULTS.put(DropPolicy.CAP_GRANTED_AREA, Policy.AUTO);
        DEFAULTS.put(DropPolicy.CAP_FOREIGN, Policy.ASK);
    }

    /** 会话级（`session` 范围）与内存中的待答复队列。 */
    private static final Map<UUID, List<Request>> PENDING = new LinkedHashMap<>();
    /**
     * **已答复/已超时的结论**（按 bot + 能力暂存，调用方 `request()` 时**取走一次**）。
     *
     * <p>为什么需要：调用方（任务/能力）是**每 tick 轮询**的 —— `request()` 返回 `null` 表示"还在等"，
     * 玩家一答复/一超时，下一次调用必须能拿到**结论**，而不是又发一条新请示（否则会无限问）。
     */
    private static final Map<UUID, Map<String, Decision>> ANSWERS = new LinkedHashMap<>();
    private static final Map<String, Scope> SESSION_GRANTS = new LinkedHashMap<>();
    private static int sequence;

    private PermissionGate() {
    }

    // ==================== 策略 ====================

    public static Policy policy(MinecraftServer server, String capability) {
        Policy remembered = PermissionsData.get(server).policy(capability);
        return remembered != null ? remembered : DEFAULTS.getOrDefault(capability, Policy.ASK);
    }

    public static void setPolicy(MinecraftServer server, String capability, Policy policy) {
        PermissionsData.get(server).setPolicy(capability, policy);
        BotLog.info("[Perm] policy_set capability={} policy={}（always 级，已持久化）", capability, policy);
    }

    public static Map<String, Policy> policyTable(MinecraftServer server) {
        Map<String, Policy> table = new LinkedHashMap<>(DEFAULTS);
        table.putAll(PermissionsData.get(server).all());
        return table;
    }

    // ==================== 请示与答复 ====================

    /**
     * **要一个结论**：命中 `AUTO`/`NOTIFY` ⇒ 立即放行（`NOTIFY` 会记一条提醒）；
     * `IGNORE` ⇒ 立即拒绝；已有 `session`/`always` 授权 ⇒ 直接放行；
     * 否则**登记一条待答复请示**并返回 {@code null}（由调用方稍后重试，或走"等答复"的相位）。
     *
     * @return 结论；null = 已登记请示，等玩家答复/超时（**调用方必须继续 tick，不得阻塞**）
     */
    public static Decision request(BotPlayer bot, String capability, String reason,
                                  List<String> options, String defaultOption, int timeoutTicks) {
        Policy policy = policy(bot.getServer(), capability);
        if (policy == Policy.AUTO) {
            return new Decision(true, "auto", "policy:auto", Scope.ALWAYS, "");
        }
        if (policy == Policy.NOTIFY) {
            BotLog.info("[Perm] notify capability={} reason={}（策略=NOTIFY：直接做 + 事后提醒）",
                    capability, reason);
            return new Decision(true, "notify", "policy:notify", Scope.ALWAYS, "");
        }
        if (policy == Policy.IGNORE) {
            BotLog.info("[Perm] ignore capability={} reason={}（策略=IGNORE：不做也不问）",
                    capability, reason);
            return new Decision(false, "ignore", "policy:ignore", Scope.ALWAYS, "");
        }
        Scope remembered = SESSION_GRANTS.get(capability);
        if (remembered != null) {
            return new Decision(true, "remembered", "session_grant", remembered, "");
        }
        // ① 已经有结论（玩家答过 / 超时默认）⇒ **取走并返回**（调用方每 tick 轮询的语义）
        Map<String, Decision> answers = ANSWERS.get(bot.getUUID());
        if (answers != null && answers.containsKey(capability)) {
            return answers.remove(capability);
        }
        // ② 已有同一能力同一 bot 的待答复 ⇒ 复用（不重复问）
        for (Request existing : pending(bot)) {
            if (existing.capability().equals(capability)) {
                return null;
            }
        }
        String id = "p" + (++sequence);
        Request request = new Request(id, bot.getUUID(), capability, reason,
                List.copyOf(options), defaultOption,
                bot.getServer().getTickCount() + Math.max(20, timeoutTicks),
                Map.of("bot", bot.getName().getString(), "pos", bot.blockPosition().toShortString()));
        PENDING.computeIfAbsent(bot.getUUID(), ignored -> new ArrayList<>()).add(request);
        BotLog.warn("[Perm] request id={} capability={} reason={} options={} default={} deadlineIn={}tick"
                        + "（超时按默认档；LLM 无权批准）",
                id, capability, reason, options, defaultOption, timeoutTicks);
        // S3b：推给客户端画卡片（没有该 mod 的客户端会自动忽略；聊天/命令入口照样可用）
        notifyClients(true, id, capability, reason, options, defaultOption, timeoutTicks);
        // S3c：**聊天栏可点击按钮** —— 游戏内鼠标被锁定，HUD 按钮点不到；
        // 聊天里的 ClickEvent.runCommand 是原版就支持的路，**不依赖任何客户端渲染**
        broadcastChatRequest(id, capability, reason, defaultOption, timeoutTicks);
        return null;
    }

    /** 玩家答复（命令/弹窗都走这里）。 */
    public static boolean answer(MinecraftServer server, String id, String option, Scope scope,
                                 String by) {
        for (Map.Entry<UUID, List<Request>> entry : PENDING.entrySet()) {
            for (Request request : entry.getValue()) {
                if (!request.id().equals(id)) {
                    continue;
                }
                entry.getValue().remove(request);
                String chosen = option == null || option.isBlank() ? request.defaultOption() : option;
                BotLog.warn("[Perm] answer id={} capability={} option={} scope={} by={}",
                        id, request.capability(), chosen, scope, by);
                boolean allowed = !chosen.equalsIgnoreCase("deny") && !chosen.equalsIgnoreCase("no");
                if (allowed) {
                    if (scope == Scope.SESSION) {
                        SESSION_GRANTS.put(request.capability(), Scope.SESSION);
                    } else if (scope == Scope.ALWAYS) {
                        setPolicy(server, request.capability(), Policy.AUTO);
                    }
                }
                putAnswer(request.botId(), request.capability(),
                        new Decision(allowed, chosen, by, scope, ""));
                notifyClients(false, request.id(), request.capability(), "", request.options(),
                        request.defaultOption(), 0);
                return true;
            }
        }
        return false;
    }

    /** 每 tick：处理超时（**超时按默认档 = 拒绝**，并留审计）。 */
    public static void tick(BotPlayer bot) {
        List<Request> pending = PENDING.get(bot.getUUID());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        long now = bot.getServer().getTickCount();
        List<Request> expired = new ArrayList<>();
        for (Request request : pending) {
            if (now >= request.deadlineTick()) {
                expired.add(request);
            }
        }
        for (Request request : expired) {
            pending.remove(request);
            BotLog.warn("[Perm] timeout id={} capability={} ⇒ 按默认档 {}（超时=拒绝，自动返回）",
                    request.id(), request.capability(), request.defaultOption());
            // 结论按 `default` 落档（用户裁定：超时=不批准），并交给调用方去走"自动返回"
            boolean allowedByDefault = !request.defaultOption().equalsIgnoreCase("deny")
                    && !request.defaultOption().equalsIgnoreCase("no");
            putAnswer(request.botId(), request.capability(),
                    new Decision(allowedByDefault, request.defaultOption(), "timeout", Scope.ONCE, ""));
            notifyClients(false, request.id(), request.capability(), "", request.options(),
                    request.defaultOption(), 0);
            BotEventLog.record(bot, "PERMISSION", "warn",
                    "请示超时⇒默认 " + request.defaultOption(),
                    "capability=" + request.capability() + " id=" + request.id());
        }
    }

    /** 推送请示通知（S2C）。**只发通知，不改任何服务端状态**；失败（无客户端/未安装）静默。 */
    private static void notifyClients(boolean active, String id, String capability, String reason,
                                      List<String> options, String defaultOption, int deadlineTicks) {
        try {
            String option1 = options != null && options.size() > 0 ? options.get(0) : "";
            String option2 = options != null && options.size() > 1 ? options.get(1) : "";
            com.dddgn.alice.network.AliceNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.ALL.noArg(),
                    new com.dddgn.alice.network.PermissionNoticePacket(active, id, capability, reason,
                            option1, option2, defaultOption, deadlineTicks));
        } catch (Exception ex) {
            BotLog.warn("[Perm] 请示通知推送失败（不影响服务端流程）：{}", ex.toString());
        }
    }

    /**
     * 聊天栏请示（S3c）：一行文本 + 两个**可点击**按钮（`ClickEvent.runCommand`）。
     *
     * <p>为什么这是主路径：游戏内鼠标被锁定 ⇒ HUD 卡片只能"看"，答复要靠**聊天里的可点按钮**
     * 或**快捷键**（`Y` 允许 / `N` 拒绝）。聊天这条**完全是原版机制**，不依赖客户端 mod 渲染。
     */
    private static void broadcastChatRequest(String id, String capability, String reason,
                                             String defaultOption, int timeoutTicks) {
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            int seconds = Math.max(1, timeoutTicks / 20);
            net.minecraft.network.chat.Component head = net.minecraft.network.chat.Component
                    .literal("[Alice 请示 " + id + "] " + capability + "：").withStyle(style ->
                            style.withColor(net.minecraft.ChatFormatting.YELLOW));
            net.minecraft.network.chat.MutableComponent allow = net.minecraft.network.chat.Component
                    .literal("[ 允许 ]").withStyle(style -> style
                            .withColor(net.minecraft.ChatFormatting.GREEN)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/alice ask " + id + " allow once"))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    net.minecraft.network.chat.Component.literal("点击允许（本次）"))));
            net.minecraft.network.chat.MutableComponent deny = net.minecraft.network.chat.Component
                    .literal(" [ 拒绝 ]").withStyle(style -> style
                            .withColor(net.minecraft.ChatFormatting.RED)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                    "/alice ask " + id + " deny once"))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    net.minecraft.network.chat.Component.literal("点击拒绝（本次）"))));
            net.minecraft.network.chat.Component tail = net.minecraft.network.chat.Component
                    .literal("（" + seconds + "s 后按默认「" + defaultOption + "」；也可 " + reason + "）")
                    .withStyle(net.minecraft.ChatFormatting.GRAY);
            net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.empty()
                    .append(head).append(allow).append(deny).append(tail);
            for (var player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(line);
            }
        } catch (Exception ex) {
            BotLog.warn("[Perm] 聊天请示推送失败（不影响服务端流程）：{}", ex.toString());
        }
    }

    private static void putAnswer(UUID botId, String capability, Decision decision) {
        ANSWERS.computeIfAbsent(botId, ignored -> new LinkedHashMap<>()).put(capability, decision);
    }

    public static List<Request> pending(BotPlayer bot) {
        List<Request> pending = PENDING.get(bot.getUUID());
        return pending == null ? List.of() : List.copyOf(pending);
    }

    public static void forget(UUID botId) {
        PENDING.remove(botId);
        ANSWERS.remove(botId);
    }

    // ==================== 持久化（`always` 级）====================

    /** 玩家用 `always` 记住的能力策略（SavedData，跨会话）。 */
    public static final class PermissionsData extends SavedData {
        private static final String DATA_KEY = "alice_permissions";
        private final Map<String, Policy> policies = new LinkedHashMap<>();

        public static PermissionsData get(MinecraftServer server) {
            return server.overworld().getDataStorage()
                    .computeIfAbsent(PermissionsData::load, PermissionsData::new, DATA_KEY);
        }

        public Policy policy(String capability) {
            return policies.get(capability);
        }

        public void setPolicy(String capability, Policy policy) {
            policies.put(capability, policy);
            setDirty();
        }

        public Map<String, Policy> all() {
            return Map.copyOf(policies);
        }

        private static PermissionsData load(CompoundTag root) {
            PermissionsData data = new PermissionsData();
            ListTag list = root.getList("policies", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                try {
                    data.policies.put(tag.getString("capability"),
                            Policy.valueOf(tag.getString("policy")));
                } catch (IllegalArgumentException ignored) {
                    // 旧档/手改坏了就忽略这一条，不影响启动
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag list = new ListTag();
            for (Map.Entry<String, Policy> entry : policies.entrySet()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("capability", entry.getKey());
                tag.putString("policy", entry.getValue().name());
                list.add(tag);
            }
            root.put("policies", list);
            return root;
        }
    }
}
