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

    /**
     * **维护工具**（基-9）：把"已经拥有但选不到/更差"的同类工具弄到手上。
     * 只做**背包内移动**（不写世界、不耗资源）⇒ 不需要额外授权。
     */
    record MaintainTool(com.dddgn.alice.bot.ToolSupply.Kind kind, String note) implements GoalAction {
    }

    /** 只要一条状态回报（写进日志/聊天，不动世界）。 */
    record ReportStatus(String note) implements GoalAction {
    }

    /** 明确什么都不做。 */
    record NoOp(String note) implements GoalAction {
    }

    /**
     * **合成 / 熔炼**（A5 / D-199）：把"点一下就出"与"按时间工作"两类加工接进词汇表。
     *
     * <pre>
     * {"action":"craft","item":"minecraft:crafting_table","count":1}
     * </pre>
     *
     * <p>**严格解析（"选项由确定性层生成、LLM 只选择"）**：
     * <ul>
     *   <li>`item` **必须出现在本轮候选菜单的"可做清单"里**（清单由**只读**配方查询 + 站点发现产出），
     *       否则 {@link Refused} 并**回读**清单规模与截断事实（别让 LLM 把"没列出来"当成"做不到"）；</li>
     *   <li>`count` 夹取到安全区间并记一行 `clamps`；</li>
     *   <li>**不接站点参数**：用哪个工作站由**玩家**切换（用户裁定），LLM 只能选"要什么"。</li>
     * </ul>
     */
    record Craft(String item, int count, String note, java.util.List<String> clamps) implements GoalAction {
    }

    /** 拒绝：未知动作/非法参数（**如实回报**，不猜）。 */
    record Refused(String reason) implements GoalAction {
    }

    int MAX_RADIUS = 64;
    int MAX_QUOTA = 64;
    int MAX_TICKS = 120000;
    /** 一次合成请求的数量上限（按"几个产物"计）。 */
    int MAX_CRAFT_COUNT = 32;

    /** 严格解析一行 LLM 回复（容忍被 ```json 包裹或前后有解释文字）。 */
    static GoalAction parse(String reply, com.dddgn.alice.bot.BotPlayer bot) {
        return parse(reply, bot, null);
    }

    /**
     * 严格解析（S2：带候选菜单校验）。
     *
     * <p>`start_job` 的 `target` **必须命中本轮菜单**：这是"选项由确定性层生成、LLM 只选择"的落地点
     * —— 于是 LLM 既不能编坐标，也不能挑一个"那里什么都没有"的位置。
     */
    static GoalAction parse(String reply, com.dddgn.alice.bot.BotPlayer bot, CandidateMenu menu) {
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
            case "start_job" -> parseStartJob(root, note, bot, menu);
            case "stop_current" -> new StopCurrent(root.has("reason") && root.get("reason").isJsonPrimitive()
                    ? root.get("reason").getAsString() : "llm_requested");
            case "maintain_tool" -> parseMaintainTool(root, note);
            case "craft" -> parseCraft(root, note, menu);
            case "report_status" -> new ReportStatus(note);
            case "no_op" -> new NoOp(note);
            default -> new Refused("unknown_action:" + action);
        };
    }

    /** `maintain_tool`：`{"action":"maintain_tool","kind":"pickaxe"}`（kind 未知 ⇒ 拒绝，不猜）。 */
    private static GoalAction parseMaintainTool(JsonObject root, String note) {
        if (!root.has("kind") || !root.get("kind").isJsonPrimitive()) {
            return new Refused("maintain_tool_missing_kind");
        }
        String raw = root.get("kind").getAsString().trim().toUpperCase(java.util.Locale.ROOT);
        for (com.dddgn.alice.bot.ToolSupply.Kind kind : com.dddgn.alice.bot.ToolSupply.Kind.values()) {
            if (kind.name().equals(raw)) {
                return new MaintainTool(kind, note);
            }
        }
        return new Refused("maintain_tool_unknown_kind:" + raw);
    }

    private static GoalAction parseStartJob(JsonObject root, String note,
                                            com.dddgn.alice.bot.BotPlayer bot, CandidateMenu menu) {
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
        // S2：target 必须是菜单里出现过的 id；解析不出 ⇒ 拒绝（不猜坐标）
        String targetId = root.has("target") && root.get("target").isJsonPrimitive()
                ? root.get("target").getAsString().trim() : "";
        CandidateMenu.Entry target = menu == null ? null : menu.find(targetId);
        var botPos = bot == null ? net.minecraft.core.BlockPos.ZERO : bot.blockPosition().immutable();
        var center = target != null && target.pos() != null ? target.pos() : botPos;
        boolean needsTarget = "lumber".equals(kind) || "collect".equals(kind);
        if (needsTarget && target == null) {
            return new Refused("missing_or_unknown_target:" + (targetId.isBlank() ? "(未给)" : targetId)
                    + "（start_job kind=" + kind + " 必须引用菜单里的候选 id）");
        }
        if (target != null && !target.kind().equals(kind)
                && !("region_lumber".equals(kind) && "region_lumber".equals(target.kind()))) {
            return new Refused("target_kind_mismatch:" + targetId + " is " + target.kind()
                    + " but kind=" + kind);
        }
        return switch (kind) {
            case "lumber" -> new StartJob(JobRequest.lumber(center, radius, quota, maxTicks), note, clamps);
            case "mine" -> new StartJob(JobRequest.mine(center, radius, quota, maxTicks, productTag),
                    note, clamps);
            case "collect" -> new StartJob(JobRequest.collect(center, radius,
                    target != null && target.amount() > 0 ? Math.min(quota, target.amount()) : quota,
                    maxTicks), note, clamps);   // 能不能捡由归属+策略决定（LLM 不能自造授权）
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

    /**
     * `craft`：`{"action":"craft","item":"minecraft:crafting_table","count":1}`。
     *
     * <p>**只接受"可做清单"里的物品**：清单来自 `CandidateMenu` 的 `craftable` 条目（只读配方查询 +
     * 站点发现产出的事实）。不在清单里 ⇒ {@link Refused}，并把**清单规模/是否被截断**回读给 LLM
     * ——否则它会把"菜单没列"误当成"做不到"，然后开始瞎试。
     *
     * <p>站点**不由 LLM 指定**（用户裁定：工作站由玩家切换）；清单里带 `can_use=` 事实，
     * 当前站点做不了就在**解析层**如实拒绝（失败快、回读清楚），而不是等执行到一半才失败。
     */
    private static GoalAction parseCraft(JsonObject root, String note, CandidateMenu menu) {
        String item = root.has("item") && root.get("item").isJsonPrimitive()
                ? root.get("item").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "";
        if (item.isBlank()) {
            return new Refused("craft_missing_item（要写 {\"action\":\"craft\",\"item\":\"<物品id>\"}）");
        }
        java.util.List<String> clamps = new java.util.ArrayList<>();
        int count = clamp(number(root, "count", 1), 1, MAX_CRAFT_COUNT, "count", clamps);
        if (menu == null) {
            return new Refused("craft_without_menu（合成目标必须来自候选菜单的 craftable 清单）");
        }
        Boolean canUse = menu.craftable().get(item);
        if (canUse == null) {
            return new Refused("not_in_menu:" + item + "（可做清单 " + menu.craftable().size() + " 项"
                    + (menu.craftableTruncated() ? "，已按上限截断" : "")
                    + "；清单只列\"材料已持有且配方属于原版可读类型\"的产物）");
        }
        if (!canUse) {
            return new Refused("station_cannot:" + item + "（当前选中的工作站做不了它；"
                    + "换工作站由玩家决定，见 /alice craft station）");
        }
        return new Craft(item, count, note, clamps);
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
