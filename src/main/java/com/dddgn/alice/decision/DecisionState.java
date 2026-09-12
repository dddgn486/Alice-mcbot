package com.dddgn.alice.decision;

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
 * **跨重启的决策层状态**（基-4）：把"重启会丢什么"变成**明文登记的事实**，而不是靠人猜。
 *
 * <p>盘点（2026-09-12 复核）：
 * <ul>
 *   <li>**已持久化**：能力分级（`PermissionsData`）、ALWAYS 授权、收集授权（`CollectGrants`）、
 *       区域（`LumberRegionState`）、世界改动账本（`WorldModLedger`）—— 这些按设计存活；</li>
 *   <li>**重启即丢（本类登记）**：① {@code PermissionGate} 的**未决请示**（内存队列）；
 *       ② **当前任务**（bot 是假人，重启后从 idle 开始）；③ 决策层的内存状态（上一次触发节流、菜单）。</li>
 * </ul>
 *
 * <p><b>语义裁定（写下来，否则每次都要重新讨论）</b>：
 * <ol>
 *   <li><b>未决请示在重启后一律作废</b> —— 它是对"某个正在阻塞等待的任务"的承诺，而那个任务
 *       重启后已经不存在了；把它"恢复"成一条悬空请示只会误导玩家与 LLM。**但必须报出来**
 *       （日志 + trace + 事件 `RESTART`），绝不静默丢弃。</li>
 *   <li><b>任务不自动续做</b> —— 任务树没有持久化（任务对象持有世界/会话引用，序列化不划算）。
 *       重启后如实在汇报里写"重启前正在跑 X，未续做"，由玩家/决策层重新决定。</li>
 *   <li>本类只登记"丢了什么"，**不做**任何自动恢复动作（避免造出"看起来恢复了、其实状态不一致"的假象）。</li>
 * </ol>
 */
public final class DecisionState extends SavedData {

    private static final String DATA_KEY = "alice_decision_state";

    /** 每个 bot 的未决请示（重启后应作废）。 */
    private final Map<String, List<String>> pending = new LinkedHashMap<>();
    /** 每个 bot 最后一次已知的"正在跑的任务"摘要（重启后用于如实汇报）。 */
    private final Map<String, String> lastTask = new LinkedHashMap<>();

    public static DecisionState get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(DecisionState::load, DecisionState::new, DATA_KEY);
    }

    // ==================== 登记（运行期） ====================

    public void recordPending(UUID botId, String capability, String requester, String detail) {
        pending.computeIfAbsent(botId.toString(), ignored -> new ArrayList<>())
                .add(capability + " by " + requester + (detail == null || detail.isBlank() ? "" : " " + detail));
        setDirty();
    }

    public void clearPending(UUID botId, String capability) {
        List<String> list = pending.get(botId.toString());
        if (list == null) {
            return;
        }
        if (list.removeIf(entry -> entry.startsWith(capability + " by "))) {
            setDirty();
        }
    }

    public void recordTask(UUID botId, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        lastTask.put(botId.toString(), summary);
        setDirty();
    }

    public void clearTask(UUID botId) {
        if (lastTask.remove(botId.toString()) != null) {
            setDirty();
        }
    }

    // ==================== 重启后读取 ====================

    /** 重启后**一次性**消费"丢了什么"的报告（读后即清，第二次调用返回空 ⇒ 天然幂等）。 */
    public String consumeRestartReport(UUID botId) {
        String key = botId.toString();
        List<String> pendingList = pending.remove(key);
        String task = lastTask.remove(key);
        if ((pendingList == null || pendingList.isEmpty()) && task == null) {
            return "";
        }
        setDirty();
        StringBuilder builder = new StringBuilder();
        if (task != null) {
            builder.append("重启前正在跑「").append(task).append("」⇒ 未续做（任务树不持久化）");
        }
        if (pendingList != null && !pendingList.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("；");
            }
            builder.append("重启作废了 ").append(pendingList.size()).append(" 条未决请示（发起任务已不存在）：")
                    .append(String.join(" | ", pendingList));
        }
        return builder.toString();
    }

    /** 只读快照（自检/汇报）。 */
    public Map<String, List<String>> pendingSnapshot() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        pending.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    public Map<String, String> taskSnapshot() {
        return Map.copyOf(lastTask);
    }

    // ==================== 存取 ====================

    private static DecisionState load(CompoundTag root) {
        DecisionState data = new DecisionState();
        ListTag pendingList = root.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            CompoundTag tag = pendingList.getCompound(i);
            String bot = tag.getString("bot");
            ListTag entries = tag.getList("entries", Tag.TAG_STRING);
            List<String> values = new ArrayList<>();
            for (int k = 0; k < entries.size(); k++) {
                values.add(entries.getString(k));
            }
            if (!values.isEmpty()) {
                data.pending.put(bot, values);
            }
        }
        ListTag taskList = root.getList("tasks", Tag.TAG_COMPOUND);
        for (int i = 0; i < taskList.size(); i++) {
            CompoundTag tag = taskList.getCompound(i);
            data.lastTask.put(tag.getString("bot"), tag.getString("summary"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag pendingList = new ListTag();
        pending.forEach((bot, values) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("bot", bot);
            ListTag entries = new ListTag();
            values.forEach(entry -> entries.add(net.minecraft.nbt.StringTag.valueOf(entry)));
            tag.put("entries", entries);
            pendingList.add(tag);
        });
        root.put("pending", pendingList);
        ListTag taskList = new ListTag();
        lastTask.forEach((bot, summary) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("bot", bot);
            tag.putString("summary", summary);
            taskList.add(tag);
        });
        root.put("tasks", taskList);
        return root;
    }

    /** 自检辅助：NBT 往返（save → load）后内容是否一致。 */
    public static DecisionState roundTrip(DecisionState source) {
        return load(source.save(new CompoundTag()));
    }

    static void logSelfCheck(String text) {
        BotLog.info("[DecisionState] {}", text);
    }
}
