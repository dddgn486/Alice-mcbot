package com.dddgn.alice.job.mine;

import com.dddgn.alice.log.BotLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **种类分配（per-kind allocation）**：把"挖 quota 个任意目标"升级成"**每种各挖几个**"。
 *
 * <p>用户 2026-09-20 需求原话：「比如要求挖一组煤炭和一组铁，**煤炭多了就不要了**，最好做成种类分配」，
 * 并追加约束：「种类分配**当然也不能限定成 ID，也要支持标签**」。
 *
 * <h3>口径（每一条都要能被夹具单独咬住）</h3>
 * <ol>
 *   <li><b>键 = 标签**或**方块 id</b>，解析规则与 {@link MineCandidateSource.Target#parse} **同一处**
 *       （先按标签；无同名标签再按方块 id）⇒ 不存在"两套解析口径"这种漂移。</li>
 *   <li><b>声明顺序 = 优先级</b>：一格方块若同时匹配多条（例如同时声明了 {@code #forge:ores} 与
 *       {@code #forge:ores/coal}），**按声明顺序取第一条**（确定性，不按"谁更具体"猜）。
 *       每个方块**只计一种**，不重复计数。</li>
 *   <li><b>满足后就不再选它</b>（"煤炭多了就不要了"）：拒绝码 {@code kind_quota_met}；
 *       不属于任何一条分配的方块拒绝码 {@code kind_not_wanted}。</li>
 *   <li><b>总数配额仍是硬上限</b>（用户同日裁定：「还是要保持配额当上限」）——本类只提供
 *       {@link #sumQuota()} 给调用方当总上限，**不改变** {@code MineJob} 里"每挖到一格就判配额"的位置。</li>
 *   <li>**配置坏了不许猜**：解析失败/未知目标/重复键都进 {@link #problems()} 并 **WARN**；
 *       有效的条目照旧生效，一个都不剩 ⇒ 整个分配**惰性**（回到"单一总配额"行为）。</li>
 * </ol>
 *
 * <p>纯逻辑（{@link #parse} / {@link #refusal} / {@link #sumQuota}）**不碰世界** ⇒ 夹具可直接断言；
 * 只有 {@link #resolve} 需要注册表（把键解析成标签/方块）。
 */
public final class MineKindPlan {

    /** 一条分配：`key` 是原文键（标签形如 {@code forge:ores/coal}），`target` 是解析后的目标。 */
    public record Entry(String key, int count, MineCandidateSource.Target target) {

        public boolean matches(BlockState state) {
            return target.matches(state);
        }

        public String label() {
            return target.describe() + "×" + count;
        }
    }

    /** 解析后的**原始**条目（还没有解析成标签/方块 ⇒ 纯函数可断言）。 */
    public record Spec(String raw, String key, int count) {
    }

    /** {@link #parse} 的结果：成功的条目 + 失败的原因（都如实带回，不静默丢）。 */
    public record Parsed(List<Spec> specs, List<String> problems) {

        public boolean clean() {
            return problems.isEmpty();
        }
    }

    /** 空计划（= 今天的行为：单一总配额，没有种类分配）。 */
    public static final MineKindPlan NONE = new MineKindPlan(List.of(), List.of());

    private final List<Entry> entries;
    private final List<String> problems;

    private MineKindPlan(List<Entry> entries, List<String> problems) {
        this.entries = List.copyOf(entries);
        this.problems = List.copyOf(problems);
    }

    /** 是否真的在做种类分配（空 ⇒ 惰性）。 */
    public boolean active() {
        return !entries.isEmpty();
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<String> problems() {
        return problems;
    }

    public static MineKindPlan of(List<Entry> entries) {
        return new MineKindPlan(entries, List.of());
    }

    /** 各条分配之和 = **建议的总上限**（调用方把它当 `quota`，见类注释第 4 条）。 */
    public int sumQuota() {
        int sum = 0;
        for (Entry entry : entries) {
            sum += entry.count();
        }
        return sum;
    }

    /**
     * 纯解析：`"<key>=<count>"`。
     *
     * <p>用 **最后一个** `=` 切分（键里不允许出现 `=`，但这样切更宽容）；`count` 必须 &gt; 0；
     * 重复键只保留**第一条**并记 problem（确定性）。
     */
    public static Parsed parse(List<String> rawSpecs) {
        List<Spec> specs = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (rawSpecs != null) {
            for (String raw : rawSpecs) {
                if (raw == null || raw.isBlank()) {
                    problems.add("空条目");
                    continue;
                }
                String text = raw.trim();
                int cut = text.lastIndexOf('=');
                if (cut <= 0 || cut == text.length() - 1) {
                    problems.add(text + "（要写成「键=数量」，键 = 标签或方块 id）");
                    continue;
                }
                String key = text.substring(0, cut).trim();
                String countText = text.substring(cut + 1).trim();
                int count;
                try {
                    count = Integer.parseInt(countText);
                } catch (NumberFormatException exception) {
                    problems.add(text + "（数量不是整数：" + countText + "）");
                    continue;
                }
                if (count <= 0) {
                    problems.add(text + "（数量必须 > 0）");
                    continue;
                }
                try {
                    new ResourceLocation(key);
                } catch (RuntimeException exception) {
                    problems.add(text + "（键不是合法的资源位置：" + key + "）");
                    continue;
                }
                if (!seen.add(key)) {
                    problems.add(text + "（重复声明 ⇒ 只保留第一条）");
                    continue;
                }
                specs.add(new Spec(text, key, count));
            }
        }
        return new Parsed(specs, problems);
    }

    /** 纯解析 + 生产解析（需要注册表）：把键解析成标签/方块（与命令入口**同一处**规则）。 */
    public static MineKindPlan resolve(ServerLevel level, List<String> rawSpecs) {
        Parsed parsed = parse(rawSpecs);
        List<String> problems = new ArrayList<>(parsed.problems());
        List<Entry> entries = new ArrayList<>();
        for (Spec spec : parsed.specs()) {
            MineCandidateSource.Target target =
                    MineCandidateSource.Target.parse(level, new ResourceLocation(spec.key()));
            if (target == null) {
                problems.add(spec.raw() + "（既不是已注册的标签、也不是已知方块 id）");
                continue;
            }
            entries.add(new Entry(spec.key(), spec.count(), target));
        }
        MineKindPlan plan = new MineKindPlan(entries, problems);
        for (String problem : problems) {
            BotLog.warn("[MineKind] 种类分配条目无效，已忽略：{}", problem);
        }
        return plan;
    }

    /** 这一格属于哪条分配（声明顺序取第一条）；`-1` = 不属于任何一条（不想要）。 */
    public int indexOf(BlockState state) {
        if (state == null) {
            return -1;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).matches(state)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * **这一格现在还想要吗**（纯逻辑，夹具逐条断言的那一处）。
     *
     * @param index {@link #indexOf} 的结果
     * @param mined 与 {@link #entries()} 对齐的"已挖数量"
     * @return `null` = 想要；`kind_not_wanted` = 不在分配里；`kind_quota_met` = 这条已经够了
     */
    public String refusal(int index, int[] mined) {
        if (!active()) {
            return null;
        }
        if (index < 0 || index >= entries.size()) {
            return "kind_not_wanted";
        }
        int have = mined == null || index >= mined.length ? 0 : mined[index];
        return have >= entries.get(index).count() ? "kind_quota_met" : null;
    }

    /** 进度（日志/终态用）：`forge:ores/coal 12/64 · minecraft:iron_ore 3/32`。 */
    public String progress(int[] mined) {
        if (!active()) {
            return "（无种类分配）";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                text.append(" · ");
            }
            int have = mined == null || index >= mined.length ? 0 : mined[index];
            text.append(entries.get(index).key()).append(' ').append(have)
                    .append('/').append(entries.get(index).count());
        }
        return text.toString();
    }

    /** 还没满足的种类（终态归因用；全满足 ⇒ 空串）。 */
    public String shortfall(int[] mined) {
        if (!active()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            int have = mined == null || index >= mined.length ? 0 : mined[index];
            if (have >= entries.get(index).count()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(entries.get(index).key()).append(' ').append(have)
                    .append('/').append(entries.get(index).count());
        }
        return text.toString();
    }

    /** 是否**所有种类**都满足（空计划 ⇒ false：那时完成判据归总配额管）。 */
    public boolean allSatisfied(int[] mined) {
        if (!active()) {
            return false;
        }
        for (int index = 0; index < entries.size(); index++) {
            int have = mined == null || index >= mined.length ? 0 : mined[index];
            if (have < entries.get(index).count()) {
                return false;
            }
        }
        return true;
    }

    public String describe() {
        if (!active()) {
            return "种类分配[无]";
        }
        StringBuilder text = new StringBuilder("种类分配[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(entries.get(index).label());
        }
        return text.append("] 合计=").append(sumQuota()).toString();
    }
}
