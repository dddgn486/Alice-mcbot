package com.dddgn.alice.decision;

import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * **决策层 LLM 配置**（`config/alice-llm.json`，D-135）。
 *
 * <p>为什么是文件而不是硬编码：provider 与 key 属于**部署环境**，不是代码。文件不存在时
 * **不报错、不猜** —— 写一份模板（key 留空）并如实登记"LLM 未配置 ⇒ 决策层保持确定性策略"，
 * 这样"没配 LLM"的客户端也能照常跑测试（与项目"未知能力默认只读、不猜语义"一致）。
 *
 * <p>**永不打印 key**：`describe()` 只说有没有。
 */
public final class LlmConfig {

    private static final String FILE_NAME = "alice-llm.json";
    private static LlmConfig cached;

    private final boolean enabled;
    private final String url;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;
    private final int maxRequestsPerMinute;
    private final int minIntervalTicks;
    private final int idleTriggerTicks;
    private final int maxReplyChars;
    private final String systemPrompt;
    private final int maxTokens;
    private final boolean idleDecisionEnabled;
    private final String loadNote;

    private LlmConfig(boolean enabled, String url, String model, String apiKey, int timeoutMs,
                      int maxRequestsPerMinute, int minIntervalTicks, int idleTriggerTicks,
                      int maxReplyChars, String systemPrompt, int maxTokens,
                      boolean idleDecisionEnabled, String loadNote) {
        this.enabled = enabled;
        this.url = url;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.minIntervalTicks = minIntervalTicks;
        this.idleTriggerTicks = idleTriggerTicks;
        this.maxReplyChars = maxReplyChars;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.idleDecisionEnabled = idleDecisionEnabled;
        this.loadNote = loadNote;
    }

    public static synchronized LlmConfig get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    public static synchronized void reload() {
        cached = load();
    }

    private static LlmConfig load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            writeTemplate(path);
            return defaults("配置文件不存在（已写模板）");
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return new LlmConfig(
                    bool(root, "enabled", true),
                    str(root, "url", ""),
                    str(root, "model", ""),
                    str(root, "apiKey", ""),
                    integer(root, "timeoutMs", 25000),
                    integer(root, "maxRequestsPerMinute", 6),
                    integer(root, "minIntervalTicks", 100),
                    integer(root, "idleTriggerTicks", 400),
                    integer(root, "maxReplyChars", 2000),
                    str(root, "systemPrompt", ""),
                    integer(root, "maxTokens", 2000),
                    bool(root, "idleDecisionEnabled", false),
                    "已加载");
        } catch (Exception ex) {
            BotLog.warn("[Goal] LLM 配置解析失败（{}）⇒ 决策层保持确定性策略", ex.toString());
            return defaults("解析失败：" + ex.getClass().getSimpleName());
        }
    }

    private static LlmConfig defaults(String note) {
        return new LlmConfig(false, "", "", "", 25000, 6, 100, 400, 2000, "", 2000, false, note);
    }

    private static void writeTemplate(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, """
                    {
                      "enabled": false,
                      "url": "https://api.deepseek.com/chat/completions",
                      "model": "deepseek-flash",
                      "apiKey": "",
                      "timeoutMs": 25000,
                      "maxRequestsPerMinute": 6,
                      "minIntervalTicks": 100,
                      "idleTriggerTicks": 400,
                      "maxReplyChars": 2000,
                      "maxTokens": 2000,
                      "idleDecisionEnabled": false,
                      "systemPrompt": ""
                    }
                    """, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            BotLog.warn("[Goal] 写 LLM 配置模板失败：{}", ex.toString());
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static int integer(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }

    public boolean enabled() {
        return enabled;
    }

    public String url() {
        return url;
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public int maxRequestsPerMinute() {
        return Math.max(1, maxRequestsPerMinute);
    }

    public int minIntervalTicks() {
        return Math.max(0, minIntervalTicks);
    }

    public int idleTriggerTicks() {
        return Math.max(20, idleTriggerTicks);
    }

    public int maxReplyChars() {
        return Math.max(200, maxReplyChars);
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /** 回复 token 上限：**必须给推理模型留够**（否则预算全烧在 `reasoning_content` 上，正文被截断）。 */
    public int maxTokens() {
        return Math.max(256, maxTokens);
    }

    /** 空闲时是否也触发决策（默认**关**：空闲每 N 秒一次真调用会持续烧钱，先只做事件驱动）。 */
    public boolean idleDecisionEnabled() {
        return idleDecisionEnabled;
    }

    /** 可用 = 打开 + 有 URL + 有 key。 */
    public boolean usable() {
        return enabled && !url.isBlank() && !apiKey.isBlank();
    }

    /** **不含 key** 的一行摘要。 */
    public String describe() {
        return "enabled=" + enabled + " usable=" + usable() + " model=" + (model.isBlank() ? "-" : model)
                + " url=" + (url.isBlank() ? "-" : url) + " apiKey=" + (apiKey.isBlank() ? "缺失" : "已配置")
                + " timeout=" + timeoutMs + "ms 间隔>=" + minIntervalTicks + "tick"
                + " 空闲触发=" + (idleDecisionEnabled ? idleTriggerTicks + "tick" : "关")
                + " maxTokens=" + maxTokens + " (" + loadNote + ")";
    }
}
