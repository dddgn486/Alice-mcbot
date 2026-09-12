package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.CandidateMenu;
import com.dddgn.alice.decision.DecisionSnapshot;
import com.dddgn.alice.decision.GoalDirector;
import com.dddgn.alice.job.collect.CollectJob;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * **LLM 上抛契约自检**（基-5）：三件事各自可断言，纯计算，约 1 秒。
 *
 * <ul>
 *   <li><b>J-4</b> 四个 Job **真的覆写了** {@code failureReport()}（没覆写就退化成
 *       `phase=unknown` + 空 details，决策层拿到的 `lastTerminal` 等于没有信息）；</li>
 *   <li><b>J-6</b> 挖掘产物判定是**目标驱动 + 标签族**：指定 `#forge:ores/iron` 时只认铁系
 *       （**且认原矿兄弟标签**），未指定时认标签族/原版兜底，非矿物不认；</li>
 *   <li><b>J-7</b> **结构化拒绝回读**：动作被拒后，① `lastRefusal` 读得到原因、
 *       ② **下一轮 prompt 里真的带上了** `lastRefusal`、③ 动作被接受后清掉（不留 stale 理由）。</li>
 * </ul>
 */
public class LlmContractCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int ticks;
    private boolean done;

    public LlmContractCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "LlmContractCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 60) {
            return finish("timeout");
        }
        if (ticks > 1) {
            return done ? (failures.isEmpty() ? Status.DONE : Status.FAILED) : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
        checkJobFailureReports();
        checkProductFilter();
        checkRefusalReadback();

        String summary = "job_failure_reports=" + verdict("job_failure_reports")
                + " product_filter_target=" + verdict("product_filter_target")
                + " product_filter_default=" + verdict("product_filter_default")
                + " refusal_readback=" + verdict("refusal_readback")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[LlmContract] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[LlmContract] " + summary));
        }
    }

    /** J-4：四个 Job 必须自己给失败报告（否则退化默认实现 = 决策层看不到相位/细节）。 */
    private void checkJobFailureReports() {
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        for (Class<?> type : List.of(LumberJob.class, MineJob.class, RegionLumberJob.class, CollectJob.class)) {
            try {
                Class<?> declaring = type.getMethod("failureReport").getDeclaringClass();
                boolean own = declaring == type;
                ok &= own;
                detail.append(type.getSimpleName()).append('=').append(declaring.getSimpleName()).append(' ');
            } catch (NoSuchMethodException exception) {
                ok = false;
                detail.append(type.getSimpleName()).append("=MISSING ");
            }
        }
        check("job_failure_reports", ok, detail.toString().trim());
    }

    /** J-6：产物判定（目标驱动 + 标签族）。 */
    private void checkProductFilter() {
        MineProductFilter iron = MineProductFilter.forTag(TagKey.create(Registries.ITEM,
                new ResourceLocation("forge", "ores/iron")));
        boolean ironOk = iron.matches(new ItemStack(Items.IRON_ORE))
                && iron.matches(new ItemStack(Items.RAW_IRON))
                && !iron.matches(new ItemStack(Items.COAL))
                && !iron.matches(new ItemStack(Items.COBBLESTONE));
        check("product_filter_target", ironOk, "filter=" + iron.describe()
                + " ironOre=" + iron.matches(new ItemStack(Items.IRON_ORE))
                + " rawIron=" + iron.matches(new ItemStack(Items.RAW_IRON))
                + " coal=" + iron.matches(new ItemStack(Items.COAL)));

        MineProductFilter fallback = MineProductFilter.forTag(null);
        boolean fallbackOk = fallback.matches(new ItemStack(Items.RAW_IRON))
                && fallback.matches(new ItemStack(Items.DIAMOND))
                && fallback.matches(new ItemStack(Items.IRON_ORE))
                && !fallback.matches(new ItemStack(Items.COBBLESTONE))
                && !fallback.matches(new ItemStack(Items.OAK_LOG));
        check("product_filter_default", fallbackOk, "filter=" + fallback.describe()
                + " familyTags=" + MineProductFilter.familyTagCount());
    }

    /** J-7：拒绝理由要能被读回、进 prompt、且被接受后清掉。 */
    private void checkRefusalReadback() {
        String marker = "selfcheck_refusal_marker";
        GoalDirector.noteRefusal(bot, marker);
        String read = GoalDirector.lastRefusal(bot);
        String prompt = DecisionSnapshot.buildPrompt(bot, CandidateMenu.build(bot));
        boolean visible = read.contains(marker) && prompt.contains("lastRefusal") && prompt.contains(marker);
        GoalDirector.clearRefusal(bot);
        String afterClear = GoalDirector.lastRefusal(bot);
        boolean cleared = afterClear.isEmpty()
                && !DecisionSnapshot.buildPrompt(bot, CandidateMenu.build(bot)).contains(marker);
        check("refusal_readback", visible && cleared,
                "read=" + read + " promptHasField=" + prompt.contains("lastRefusal")
                        + " cleared=" + cleared);
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[LlmContract] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[LlmContract] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
