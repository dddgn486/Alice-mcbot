package com.dddgn.alice.decision;

import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * **OpenAI 兼容的 LLM 客户端**（D-135）：`POST {url}` + `{model, messages[]}` → `choices[0].message.content`。
 *
 * <p>纪律：
 * <ul>
 *   <li>**绝不在主线程上等**：请求跑在专用单线程池里，返回 `CompletableFuture`；服务器 tick 只轮询结果；</li>
 *   <li>**失败如实登记**：HTTP 非 2xx / 超时 / 解析失败 ⇒ 返回 {@code ok=false} + 一行可 grep 的日志，不假装成功；</li>
 *   <li>**不打印 key 与完整 prompt**（prompt 只截断记首行）。</li>
 * </ul>
 */
public final class LlmClient {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "alice-llm");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(EXECUTOR)
            .build();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private LlmClient() {
    }

    /** 一次问答的结果。 */
    public record Reply(boolean ok, String text, String error, long latencyMs) {
    }

    public static CompletableFuture<Reply> askAsync(String systemPrompt, String userPrompt) {
        LlmConfig config = LlmConfig.get();
        int id = SEQ.incrementAndGet();
        if (!config.usable()) {
            return CompletableFuture.completedFuture(
                    new Reply(false, "", "llm_not_configured", 0L));
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("temperature", 0);
        // 推理模型（deepseek-flash 会先出 reasoning_content）**必须留足预算**：
        // 实测 max_tokens=16 时正文只剩一个字符（预算全烧在推理上）。
        body.addProperty("max_tokens", config.maxTokens());
        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            messages.add(system);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);
        body.add("messages", messages);

        long started = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.url()))
                        .timeout(Duration.ofMillis(config.timeoutMs()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                BotLog.info("[Goal] llm_request id={} model={} promptChars={}", id, config.model(),
                        userPrompt.length());
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                long latency = System.currentTimeMillis() - started;
                if (response.statusCode() / 100 != 2) {
                    String detail = response.body() == null ? "" : truncate(response.body(), 200);
                    BotLog.warn("[Goal] llm_http_error id={} status={} body={}", id, response.statusCode(), detail);
                    return new Reply(false, "", "http_" + response.statusCode() + ": " + detail, latency);
                }
                String text = extractContent(response.body());
                if (text == null) {
                    // 只有 reasoning_content、没有 content（finish_reason=length 之类）⇒ 如实失败，
                    // 不要把推理过程当动作（那会让"严格解析"形同虚设）
                    BotLog.warn("[Goal] llm_parse_error id={} body={}", id, truncate(response.body(), 200));
                    return new Reply(false, "", "reply_shape_unexpected", latency);
                }
                BotLog.info("[Goal] llm_reply id={} latency={}ms chars={}", id, latency, text.length());
                return new Reply(true, text, "", latency);
            } catch (Exception ex) {
                long latency = System.currentTimeMillis() - started;
                BotLog.warn("[Goal] llm_transport_error id={} after {}ms: {}", id, latency, ex.toString());
                return new Reply(false, "", "transport:" + ex.getClass().getSimpleName(), latency);
            }
        }, EXECUTOR);
    }

    /** OpenAI 兼容：`choices[0].message.content`（也容忍 `choices[0].text`）。 */
    private static String extractContent(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()
                    || root.getAsJsonArray("choices").isEmpty()) {
                return null;
            }
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("message") && choice.get("message").isJsonObject()) {
                JsonObject message = choice.getAsJsonObject("message");
                if (message.has("content") && message.get("content").isJsonPrimitive()) {
                    return message.get("content").getAsString();
                }
            }
            if (choice.has("text") && choice.get("text").isJsonPrimitive()) {
                return choice.get("text").getAsString();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
