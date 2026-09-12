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

    /**
     * 跑**阻塞式** HTTP 调用的线程池。
     *
     * <p>两条教训（2026-09-12 实测）：① 必须**多线程**（缓存池）——一个卡住的调用不能把整条管道堵死；
     * ② **绝不能**把它同时交给 `HttpClient` 当内部 executor —— 否则 `send()` 占住唯一线程、
     * HttpClient 的内部任务排不进来 ⇒ **死锁**：请求发不出去，超时也永不触发
     * （实测现象：日志停在 `llm_dns` 之后整整 5 分钟没有任何一行，中继也从未收到请求）。
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "alice-llm");
        thread.setDaemon(true);
        return thread;
    });
    static {
        // Windows 上 Java **默认不读系统代理**；这个属性必须在代理选择器初始化前设置才有效
        // （2026-09-12 实测根因：系统代理 127.0.0.1:7897，mod 直连 ⇒ HttpConnectTimeoutException）
        try {
            if (System.getProperty("java.net.useSystemProxies") == null) {
                System.setProperty("java.net.useSystemProxies", "true");
            }
        } catch (Exception ignored) {
            // 只读安全属性失败不影响功能：显式配置的 proxy 仍然生效
        }
    }
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
                BotLog.info("[Goal] llm_request id={} model={} promptChars={}（路径矩阵见 path_try）",
                        id, config.model(), userPrompt.length());
                HttpResponse<String> response = sendWithPathMatrix(id, config, body.toString());
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

    /** 记住上一次成功的路径（名字），避免每次都把矩阵试一遍。 */
    private static volatile String chosenPath;
    /** 下一次请求是否把矩阵全部试一遍（诊断模式：手动触发时打开）。 */
    private static volatile boolean forceProbeAll;

    /**
     * **强制重探**（诊断用）：把手动触发变成"把三条路都试一遍并逐条登记"，
     * 而不是命中记忆里那条就返回。用于回答"游戏内 JVM 到底能不能直连"这类问题
     * （2026-09-12：同一个 `java.exe` 在游戏外直连正常，游戏内却曾超时，需要就地取证）。
     */
    public static void forgetChosenPath() {
        chosenPath = null;
        forceProbeAll = true;
    }

    /**
     * **路径矩阵**（D-135 附注二）：按顺序试 —— 本地中继 → API+代理 → API+直连，取第一条成功的，
     * 并**记住**它；每条路径的成败都如实登记（`path_try`），失败时清空记忆以便下次重新探测。
     *
     * <p>为什么需要矩阵：2026-09-12 实测某个 JVM 的外网连接被安全软件静默丢弃（超时），
     * 而同一台机器上的 `curl.exe` 与 WSL 都通 —— 单一路径无法自愈，也不足以定位。
     */
    private static HttpResponse<String> sendWithPathMatrix(int id, LlmConfig config, String payload)
            throws java.io.IOException, InterruptedException {
        java.util.List<String[]> candidates = new java.util.ArrayList<>();
        if (!config.relayUrl().isBlank()) {
            candidates.add(new String[]{"relay", config.relayUrl()});
        }
        if (!config.proxy().isBlank()) {
            candidates.add(new String[]{"api+proxy", config.url()});
        }
        candidates.add(new String[]{"api+direct", config.url()});
        String remembered = chosenPath;
        if (remembered != null) {
            for (String[] candidate : candidates) {
                if (candidate[0].equals(remembered)) {
                    candidates.remove(candidate);
                    candidates.add(0, candidate);
                    break;
                }
            }
        }
        boolean probeAll = forceProbeAll;
        forceProbeAll = false;
        HttpResponse<String> firstSuccess = null;
        java.io.IOException last = null;
        for (String[] candidate : candidates) {
            String name = candidate[0];
            String target = candidate[1];
            String proxy = "relay".equals(name) ? "" : ("api+direct".equals(name) ? "" : config.proxy());
            URI uri = URI.create(target);
            logDns(uri);
            tcpProbe(uri, proxy);
            BotLog.info("[Goal] path_try_begin name={} target={} proxy={}", name, target,
                    proxy.isBlank() ? "-" : proxy);
            try {
                long started = System.currentTimeMillis();
                HttpResponse<String> response = send(config, uri, payload, proxy);
                long elapsed = System.currentTimeMillis() - started;
                BotLog.info("[Goal] path_try name={} target={} proxy={} → ok status={} {}ms",
                        name, target, proxy.isBlank() ? "-" : proxy, response.statusCode(), elapsed);
                if (!probeAll) {
                    chosenPath = name;
                    return response;
                }
                if (firstSuccess == null) {
                    firstSuccess = response;   // 探针模式：记下第一条成功的，继续把其余路径也试完
                    chosenPath = name;
                }
            } catch (java.io.IOException ex) {
                last = ex;
                BotLog.warn("[Goal] path_try name={} target={} proxy={} → {}（{}）",
                        name, target, proxy.isBlank() ? "-" : proxy,
                        ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        if (firstSuccess != null) {
            return firstSuccess;
        }
        chosenPath = null;
        if (last != null) {
            throw last;
        }
        throw new java.io.IOException("no_candidate_path");
    }

    /** 按（代理/连接超时）建 client 并发送（每次新建：配置可热改，调用频率很低）。 */
    private static HttpResponse<String> send(LlmConfig config, URI uri, String payload, String proxy)
            throws java.io.IOException, InterruptedException {
        // **不设置 executor**：HttpClient 的内部线程由 JDK 自己管理（共用我们的池会死锁，见上）
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()));
        if (proxy.isBlank()) {
            builder.proxy(new java.net.ProxySelector() {
                @Override
                public java.util.List<java.net.Proxy> select(URI target) {
                    return java.util.List.of(java.net.Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI target, java.net.SocketAddress address, java.io.IOException ex) {
                    // 直连失败没有"代理连接失败"可报
                }
            });
        } else {
            String[] parts = proxy.split(":");
            int port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 8080;
            builder.proxy(java.net.ProxySelector.of(
                    new java.net.InetSocketAddress(parts[0].trim(), port)));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return builder.build().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * **裸 TCP 探针**：直接 socket 连一下目标（3 s），把"连得上/连不上/连多久"与 HTTP 层分开。
     *
     * <p>为什么需要：进程级拦截/+ 挂起时，"HTTP 超时"与"TCP 不通"看起来一样；分开打点后
     * 日志能直接指出是哪一层（2026-09-12 的死锁就是这么定位的）。
     */
    private static void tcpProbe(URI uri, String proxy) {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
        if (!proxy.isBlank()) {
            String[] parts = proxy.split(":");
            host = parts[0].trim();
            port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 8080;
        }
        long started = System.currentTimeMillis();
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 3000);
            BotLog.info("[Goal] tcp_probe {}:{} → ok {}ms", host, port, System.currentTimeMillis() - started);
        } catch (Exception ex) {
            BotLog.warn("[Goal] tcp_probe {}:{} → {} {}ms", host, port, ex.getClass().getSimpleName(),
                    System.currentTimeMillis() - started);
        }
    }

    /** 解析并记录目标主机的地址（不上网，只查 DNS）。 */
    private static void logDns(URI uri) {
        try {
            var addresses = java.net.InetAddress.getAllByName(uri.getHost());
            StringBuilder builder = new StringBuilder();
            for (var address : addresses) {
                builder.append(address.getHostAddress()).append(' ');
            }
            BotLog.info("[Goal] llm_dns host={} → {}", uri.getHost(), builder.toString().trim());
        } catch (Exception ex) {
            BotLog.warn("[Goal] llm_dns host={} 解析失败: {}", uri.getHost(), ex.toString());
        }
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
