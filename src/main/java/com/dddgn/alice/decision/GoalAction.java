package com.dddgn.alice.decision;

import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * **决策层动作词汇表**（D-135）：LLM 只能从这几个动作里选，其余一律**拒绝并如实回报**。
 *
 * <pre>
 * {"action":"start_job","kind":"lumber","radius":16,"quota":2,"maxTicks":3600}
 * {"action":"start_job","kind":"mine","radius":16,"quota":8,"maxTicks":3600,"productTag":"#forge:ores/iron"}
 * {"action":"start_job","kind":"region_lumber","maxTicks":24000}      // 只能用 bot 已保存的区域
 * {"action":"stop_current","reason":"..."}
 * {"action":"report_status","note":"..."}
 * {"action":"no_op","note":"..."}
 * </pre>
 *
 * <p>设计纪律（与项目"未知能力默认只读、不猜语义"一致）：
 * 未知 `action` / 未知 `kind` / 缺字段 / 坐标越界 / 参数超范围 ⇒ {@link Refused}，
 * **不做"尽力猜测"**；数值参数**夹取到安全区间**并记一行日志（LLM 不该因为多写个 0 就崩掉服务器）。
 */
public sealed interface GoalAction {

    /** 起一个 Job（唯一的"动手"动作，走 `JobRequest` → `JobLauncher` 统一入口）。 */
    record StartJob(JobRequest request, String note, java.util.List<String> clamps) implements GoalAction {
    }

    /** 显式停止当前任务（决策层打断）。 */
    record StopCurrent(String reason) implements GoalAction {
    }

    /** 只要一条状态回报（写进日志/聊天，不动世界）。 */
    record ReportStatus(String note) implements GoalAction {
    }

    /** 明确什么都不做。 */
    record NoOp(String note) implements GoalAction {
    }

    /** 拒绝：未知动作/非法参数（**如实回报**，不猜）。 */
    record Refused(String reason) implements GoalAction {
    }

    int MAX_RADIUS = 64;
    int MAX_QUOTA = 64;
    int MAX_TICKS = 120000;

    /** 严格解析一行 LLM 回复（容忍被 ```json 包裹或前后有解释文字）。 */
    static GoalAction parse(String reply, com.dddgn.alice.bot.BotPlayer bot) {
        if (reply == null || reply.isBlank()) {
            return new Refused("empty_reply");
        }
        String json = extractJsonObject(reply);
        if (json == null) {
            return new Refused("no_json_object:" + flat(reply, 120));
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception ex) {
            return new Refused("bad_json:" + flat(reply, 120));
        }
        if (!root.has("action") || !root.get("action").isJsonPrimitive()) {
            return new Refused("missing_action");
        }
        String action = root.get("action").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        String note = root.has("note") && root.get("note").isJsonPrimitive()
                ? root.get("note").getAsString() : "";
        return switch (action) {
            case "start_job" -> parseStartJob(root, note, bot);
            case "stop_current" -> new StopCurrent(root.has("reason") && root.get("reason").isJsonPrimitive()
                    ? root.get("reason").getAsString() : "llm_requested");
            case "report_status" -> new ReportStatus(note);
            case "no_op" -> new NoOp(note);
            default -> new Refused("unknown_action:" + action);
        };
    }

    private static GoalAction parseStartJob(JsonObject root, String note,
                                            com.dddgn.alice.bot.BotPlayer bot) {
        if (!root.has("kind") || !root.get("kind").isJsonPrimitive()) {
            return new Refused("start_job_missing_kind");
        }
        String kind = root.get("kind").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> clamps = new java.util.ArrayList<>();
        int radius = clamp(number(root, "radius", 16), 1, MAX_RADIUS, "radius", clamps);
        int quota = clamp(number(root, "quota", 1), 1, MAX_QUOTA, "quota", clamps);
        int maxTicks = clamp(number(root, "maxTicks", 3600), 20, MAX_TICKS, "maxTicks", clamps);
        String productTag = root.has("productTag") && root.get("productTag").isJsonPrimitive()
                ? root.get("productTag").getAsString() : null;
        var center = bot == null ? net.minecraft.core.BlockPos.ZERO : bot.blockPosition().immutable();
        return switch (kind) {
            case "lumber" -> new StartJob(JobRequest.lumber(center, radius, quota, maxTicks), note, clamps);
            case "mine" -> new StartJob(JobRequest.mine(center, radius, quota, maxTicks, productTag),
                    note, clamps);
            case "region_lumber", "region" -> {
                var region = com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer())
                        .region(bot.getUUID());
                if (region == null) {
                    // LLM **不能凭空发明区域**（区域是玩家划的，见 D-130）
                    yield new Refused("region_lumber_without_saved_region（区域必须由玩家划定）");
                }
                yield new StartJob(JobRequest.region(region, 16, quota, Math.max(maxTicks, 24000)),
                        note, clamps);
            }
            default -> new Refused("unknown_job_kind:" + kind);
        };
    }

    private static int number(JsonObject root, String key, int fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return root.get(key).getAsInt();
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max, String field, java.util.List<String> clamps) {
        int clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            clamps.add(field + ":" + value + "→" + clamped);
            BotLog.warn("[Goal] 参数 {} 超出安全区间 [{}, {}] ⇒ 夹取为 {}", field, min, max, clamped);
        }
        return clamped;
    }

    /** 从可能带 ```json 围栏/前后解释的回复里取**第一个平衡的 JSON 对象**。 */
    static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String flat(String text, int max) {
        String one = text.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
