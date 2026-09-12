package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.TreeScanner;
import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * **候选菜单**（S2 选择层 / D-139）：把"能做什么"变成**服务端算好的有界选项**，LLM 只负责挑一个。
 *
 * <p>为什么必须有它：在此之前动作里的 `center` **只能是 bot 当前位置**，于是 LLM 选 `lumber` 时
 * 大概率选到一片没树的地方 —— 实测 `[Job] launch kind=LUMBER` 后 1 tick 就
 * `no_reachable_candidate`。**不是它笨，是我们没给菜单**（D-135 附注四）。
 *
 * <p>两条铁律（`DECISION_LAYER_DESIGN.md` §2）：**选项由确定性层生成，LLM 只负责选择**。
 * 菜单里的每一项都带**服务端算出的事实**（位置/距离/数量/来源），LLM 只能引用 `id`；
 * 引用不在菜单里的 id ⇒ **拒绝**（不猜、不换算坐标）。
 *
 * <p>有界性：每类候选最多 {@link #MAX_PER_KIND} 项、总项数最多 {@link #MAX_TOTAL} 项
 * （对齐 mc_aiplayer 的"感知有界"做法）。
 */
public final class CandidateMenu {

    public static final int MAX_PER_KIND = 5;
    public static final int MAX_TOTAL = 12;
    /** 树/掉落物的扫描半径。 */
    public static final int SCAN_RADIUS = 24;

    /** 菜单项（{@code id} 是 LLM 唯一被允许引用的东西）。 */
    public record Entry(String id, String kind, String label, BlockPos pos, int amount, String extra) {
    }

    private final List<Entry> entries;

    private CandidateMenu(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry find(String id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject node = new JsonObject();
            node.addProperty("id", entry.id());
            node.addProperty("kind", entry.kind());
            node.addProperty("label", entry.label());
            if (entry.pos() != null) {
                node.addProperty("pos", entry.pos().toShortString());
            }
            if (entry.amount() > 0) {
                node.addProperty("amount", entry.amount());
            }
            if (entry.extra() != null && !entry.extra().isBlank()) {
                node.addProperty("extra", entry.extra());
            }
            array.add(node);
        }
        return array;
    }

    public String describe() {
        StringBuilder builder = new StringBuilder();
        for (Entry entry : entries) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.id()).append('(').append(entry.label()).append(')');
        }
        return builder.toString();
    }

    /** 从**服务端事实**生成菜单（只读；不改世界、不派任务）。 */
    public static CandidateMenu build(BotPlayer bot) {
        final List<Entry> entries = new ArrayList<>();
        final BlockPos botPos = bot.blockPosition();

        // ① 树 —— 伐木候选。**必须复用 Job 自己的候选源**（`LumberCandidateSource`），
        // 而不是裸 `TreeScanner`：否则会把 Job 注定拒绝的树（如 2×2 高大云杉 `trunk_too_tall`）
        // 递给 LLM —— 2026-09-12 实测就发生了（菜单给了 `tree@22,64,218`，Job 报 `no_reachable_candidate`）。
        // 这就是项目既有规矩："夹具/菜单的候选必须复用规划器 provider（可规划即可执行）"。
        var lumberSource = new com.dddgn.alice.job.lumber.LumberCandidateSource();
        var probe = com.dddgn.alice.job.GoalSpec.harvestUnits(botPos, SCAN_RADIUS, 1, 3600);
        var candidates = lumberSource.candidates(bot, probe);
        candidates.viable().stream()
                .sorted(java.util.Comparator.comparingDouble(c -> c.anchor().distSqr(botPos)))
                .limit(MAX_PER_KIND)
                .forEach(c -> entries.add(new Entry(
                        c.id(),   // 与 Job 决策日志同一个 id 口径（`tree@x,y,z`）
                        "lumber", "可行树 距离"
                        + String.format(java.util.Locale.ROOT, "%.1f",
                        Math.sqrt(c.anchor().distSqr(botPos))),
                        c.anchor(), 1,
                        "species=" + c.feature("species") + " height=" + c.feature("height")
                                + " logs=" + c.feature("logs"))));

        if (!candidates.rejected().isEmpty()) {
            BotLog.info("[Goal] candidate_menu rejected(不可做)= {}", candidates.rejected());
        }

        // ② 我方掉落的簇（**只列我方登记过的**；FOREIGN/授权区要等 S3.5）—— 捡拾候选
        var session = BotManager.sessionOf(bot);
        if (session != null) {
            List<ItemEntity> ours = new ArrayList<>();
            for (ItemEntity item : session.scope().liveDrops()) {
                if (item.distanceToSqr(bot) <= (double) SCAN_RADIUS * SCAN_RADIUS) {
                    ours.add(item);
                }
            }
            java.util.Map<BlockPos, Integer> clusters = new java.util.LinkedHashMap<>();
            for (ItemEntity item : ours) {
                BlockPos anchor = item.blockPosition();
                clusters.merge(anchor, item.getItem().getCount(), Integer::sum);
            }
            clusters.entrySet().stream()
                    .sorted(java.util.Comparator.comparingDouble(e -> e.getKey().distSqr(botPos)))
                    .limit(MAX_PER_KIND)
                    .forEach(e -> entries.add(new Entry(
                            "drops@" + e.getKey().getX() + "," + e.getKey().getY() + "," + e.getKey().getZ(),
                            "collect", "我方掉落物 距离"
                            + String.format(java.util.Locale.ROOT, "%.1f",
                            Math.sqrt(e.getKey().distSqr(botPos))),
                            e.getKey(), e.getValue(), "provenance=OURS_DIRECT")));
        }

        // ③ 已保存的可持续伐木区 —— 区域型候选（**只能用已存在的区域**，LLM 不能发明）
        LumberRegionState regionState = LumberRegionState.get(bot.getServer());
        var region = regionState.region(bot.getUUID());
        if (region != null) {
            entries.add(new Entry("region:saved", "region_lumber",
                    "已保存区域 " + region.describe(), region.center(), 0,
                    "baseline=" + regionState.baselineTrees(bot.getUUID())
                            + " mySaplings=" + regionState.mySaplingCount(bot.getUUID())));
        }

        List<Entry> bounded = entries.size() > MAX_TOTAL
                ? new ArrayList<>(entries.subList(0, MAX_TOTAL)) : entries;
        CandidateMenu menu = new CandidateMenu(bounded);
        BotLog.info("[Goal] candidate_menu entries={} {}", menu.entries().size(), menu.describe());
        return menu;
    }
}
