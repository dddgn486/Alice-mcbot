package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * **决策 trace 落盘**（基-4）：把"决策层到底做了什么决定、依据什么、结果如何"变成**结构化历史**，
 * 而不是只在日志里翻。
 *
 * <p>为什么需要它：D-135 起决策层的每次请求/回复/执行都只打日志行，而日志会被轮转、
 * 也没法按字段查（"哪次拒绝的？为什么？菜单里当时有什么？"）。用户实测时反复要问
 * "它为什么选了这个" —— 那就该有可查的记录。
 *
 * <p>形态：**JSONL 追加**（一行一条，`<config>/alice-decisions.jsonl`），与 `RecipeDump` 同一套路
 * （配置目录、离线可读、可被工具/LLM 直接消费）；同时在内存保留最近 {@link #MEMORY_TAIL} 条，
 * 供 `alice:bot_report` 这类游戏内汇报读取。文件超过 {@link #MAX_BYTES} 就轮转一份 `.1`。
 *
 * <p>纪律：**写失败绝不影响决策**（最多告警一次并停用），trace 是观测设施，不是关键路径。
 */
public final class DecisionTrace {

    /** 内存里保留多少条（够汇报用）。 */
    public static final int MEMORY_TAIL = 64;
    /** 文件多大开始轮转。 */
    public static final long MAX_BYTES = 8L * 1024L * 1024L;
    /** 文件名（配置目录下）。 */
    public static final String FILE_NAME = "alice-decisions.jsonl";

    private static final Gson GSON = new GsonBuilder().create();
    private static final Deque<JsonObject> TAIL = new ArrayDeque<>();
    private static boolean disabled;
    private static long written;

    private DecisionTrace() {
    }

    /** 决策**请求**发出（谁触发的、当时菜单有多大）。 */
    public static void request(BotPlayer bot, String trigger, int menuSize) {
        JsonObject record = base(bot, "request");
        record.addProperty("trigger", trigger);
        record.addProperty("menuSize", menuSize);
        add(record);
    }

    /** 决策**回复已解析并执行**（动作 + 结果 + 延迟）。 */
    public static void result(BotPlayer bot, String trigger, String action, String outcome,
                              String detail, long latencyMs) {
        JsonObject record = base(bot, "result");
        record.addProperty("trigger", trigger);
        record.addProperty("action", action);
        record.addProperty("outcome", outcome);
        if (detail != null && !detail.isBlank()) {
            record.addProperty("detail", clip(detail));
        }
        record.addProperty("latencyMs", latencyMs);
        add(record);
    }

    /** 决策层异常/超时等**未产出动作**的结局。 */
    public static void failure(BotPlayer bot, String trigger, String reason, String detail) {
        JsonObject record = base(bot, "failure");
        record.addProperty("trigger", trigger);
        record.addProperty("reason", reason);
        if (detail != null && !detail.isBlank()) {
            record.addProperty("detail", clip(detail));
        }
        add(record);
    }

    /** 其它跨重启/结构性事件（如重启后作废的请示）。 */
    public static void lifecycle(BotPlayer bot, String kind, String summary, String detail) {
        JsonObject record = base(bot, kind);
        record.addProperty("summary", summary);
        if (detail != null && !detail.isBlank()) {
            record.addProperty("detail", clip(detail));
        }
        add(record);
    }

    private static JsonObject base(BotPlayer bot, String kind) {
        JsonObject record = new JsonObject();
        record.addProperty("t", System.currentTimeMillis());
        record.addProperty("kind", kind);
        if (bot != null) {
            record.addProperty("tick", bot.getServer().getTickCount());
            record.addProperty("bot", bot.getName().getString());
            record.addProperty("botId", bot.getUUID().toString());
        }
        return record;
    }

    private static String clip(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > 500 ? flat.substring(0, 500) : flat;
    }

    private static synchronized void add(JsonObject record) {
        TAIL.addLast(record);
        while (TAIL.size() > MEMORY_TAIL) {
            TAIL.removeFirst();
        }
        if (disabled) {
            return;
        }
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(path.getParent());
            if (Files.exists(path) && Files.size(path) > MAX_BYTES) {
                Files.move(path, path.resolveSibling(FILE_NAME + ".1"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(path, GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written++;
        } catch (IOException | RuntimeException exception) {
            disabled = true;
            BotLog.warn("[Trace] 落盘失败 ⇒ 本次会话改为只留内存尾（不影响决策）：{}", exception.toString());
        }
    }

    /** 内存尾部（汇报用，最新在后）。 */
    public static synchronized List<String> recent(int limit) {
        List<String> rows = new ArrayList<>();
        int skip = Math.max(0, TAIL.size() - Math.max(1, limit));
        int index = 0;
        for (JsonObject record : TAIL) {
            if (index++ < skip) {
                continue;
            }
            String kind = record.has("kind") ? record.get("kind").getAsString() : "?";
            String action = record.has("action") ? record.get("action").getAsString()
                    : record.has("reason") ? record.get("reason").getAsString()
                    : record.has("summary") ? record.get("summary").getAsString()
                    : record.has("trigger") ? record.get("trigger").getAsString() : "-";
            rows.add(kind + ":" + action);
        }
        return List.copyOf(rows);
    }

    public static synchronized String describe() {
        return "file=" + FMLPaths.CONFIGDIR.get().resolve(FILE_NAME) + " written=" + written
                + (disabled ? "（落盘已停用，仅内存尾）" : "") + " tail=" + TAIL.size();
    }

    /** 自检用：从磁盘读回最后一行（验证真的写进去了）。 */
    public static String lastLineOnDisk() {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.exists(path)) {
                return "";
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        } catch (IOException exception) {
            return "read_failed:" + exception.getMessage();
        }
    }

    public static synchronized void forget() {
        TAIL.clear();
    }
}
