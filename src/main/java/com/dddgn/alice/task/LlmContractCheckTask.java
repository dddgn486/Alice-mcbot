package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.bot.TaskExecutionRecord;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.bot.TaskOutcome;
import com.dddgn.alice.decision.CandidateMenu;
import com.dddgn.alice.decision.DecisionSnapshot;
import com.dddgn.alice.decision.GoalDirector;
import com.dddgn.alice.job.collect.CollectJob;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
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
        checkSnapshotFailureFields();
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

    /**
     * **M4**：终态的失败事实必须是**字段**（相位 + 有界细节），而不是只留一段无结构文字。
     *
     * <p>为什么单独断言：`survey/08` §5.7 审计 G3 —— 真因**已经在**（`MineJob` 把逐候选的
     * `rejected()` 拼进 `lastResult`），但 LLM 只能读**一段文字**、难以可靠分支。
     * 判据 = ① 字段齐（code/phase/details）；② 细节**有界**（超长必须截断并如实标 `…`）；
     * ③ **没有失败就不许留 stale 字段**（否则 LLM 会照着上一轮的旧失败做决定）。
     */
    private void checkSnapshotFailureFields() {
        TaskFailureReport report = new TaskFailureReport("no_suitable_tool", "mine:select",
                "block@5,65,67:no_suitable_tool", RecoveryStage.NONE, List.of());
        JsonObject json = DecisionSnapshot.lastTerminalJson(
                syntheticRecord(TaskExecutionRecord.TerminalStatus.FAILED, report));
        boolean fields = "no_suitable_tool".equals(text(json, "failureCode"))
                && "mine:select".equals(text(json, "failurePhase"))
                && text(json, "failureDetails").contains("no_suitable_tool");

        String longDetails = "x".repeat(DecisionSnapshot.MAX_FAILURE_DETAILS + 50);
        JsonObject capped = DecisionSnapshot.lastTerminalJson(
                syntheticRecord(TaskExecutionRecord.TerminalStatus.FAILED,
                        new TaskFailureReport("boom", "phase", longDetails, RecoveryStage.NONE, List.of())));
        String cappedDetails = text(capped, "failureDetails");
        boolean bounded = cappedDetails.endsWith("…")
                && cappedDetails.length() == DecisionSnapshot.MAX_FAILURE_DETAILS + 1;

        JsonObject clean = DecisionSnapshot.lastTerminalJson(
                syntheticRecord(TaskExecutionRecord.TerminalStatus.COMPLETED, null));
        boolean noStale = !clean.has("failureCode") && !clean.has("failurePhase")
                && !clean.has("failureDetails");

        check("snapshot_failure_fields", fields && bounded && noStale,
                "fields=" + fields + " bounded=" + bounded + "(" + cappedDetails.length() + "/"
                        + DecisionSnapshot.MAX_FAILURE_DETAILS + ") noStale=" + noStale);
    }

    /** 构造一条终态记录（M4 自检用：不必真把任务跑失败一次）。 */
    private TaskExecutionRecord syntheticRecord(TaskExecutionRecord.TerminalStatus status,
                                               TaskFailureReport failure) {
        String botId = bot.getUUID().toString();
        TaskOutcome outcome = new TaskOutcome("MineJob", "block@5,65,67", status, "failed:selfcheck",
                BlockPos.ZERO, failure, botId, "no_reachable_candidate");
        return new TaskExecutionRecord("MineJob", "block@5,65,67", 0L, 10L, status, "failed:selfcheck",
                BlockPos.ZERO, "idle_after_cleanup", RecoveryStage.NONE, List.of(), outcome, botId,
                "no_reachable_candidate");
    }

    private String text(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "";
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
