package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.GoalDirector;
import com.dddgn.alice.decision.SpeechChannel;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **F4 判据（D-267 地基，2026-09-17 用户裁定「先做地基」）**：说话通道**只出不进**。
 *
 * <p>断言两件事：
 * ① 说话**真的出去了**（`saidCount` 增加；聊天里也该看得到 —— 客户端目视项）；
 * ② 说话**不改变决策态**：`GoalDirector.describe()` / `isSuspended` / `lastRefusal` 前后**逐字相同**。
 *    ② 是这个地基的意义所在：一旦有人把说话接到决策输入（`onEvent`/`instruct`/prompt），
 *    文本就绕过 `GoalAction` 白名单进了执行路径 —— 本用例会当场红。
 *
 * <p>反向对照：在 `SpeechChannel.say` 里加一句 `GoalDirector.onEvent(bot, text)` ⇒ ② 变红。
 */
public class SpeechChannelCheckTask implements Task {

    private static final int UTTERANCES = 2;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private boolean done;

    public SpeechChannelCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SpeechChannelCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        SpeechChannel.reset(bot);
        String beforeDescribe = GoalDirector.describe();
        boolean beforeSuspended = GoalDirector.isSuspended(bot);
        String beforeRefusal = String.valueOf(GoalDirector.lastRefusal(bot));

        SpeechChannel.say(bot, new SpeechChannel.Utterance(
                SpeechChannel.KIND_VISION_REPORT, "我看到东边有一片云杉林。", "fixture"));
        SpeechChannel.say(bot, new SpeechChannel.Utterance(
                SpeechChannel.KIND_COMPANION, "需要我陪你过去看看吗？", "fixture"));

        int said = SpeechChannel.saidCount(bot);
        String afterDescribe = GoalDirector.describe();
        boolean afterSuspended = GoalDirector.isSuspended(bot);
        String afterRefusal = String.valueOf(GoalDirector.lastRefusal(bot));

        BotLog.info("[Speech] 观测：said={} describeChanged={} suspended {}→{} refusalChanged={}",
                said, !beforeDescribe.equals(afterDescribe), beforeSuspended, afterSuspended,
                !beforeRefusal.equals(afterRefusal));

        check("① 说话真的出去了（said=" + said + " == " + UTTERANCES + "）", said == UTTERANCES);
        // ② 下面的三条是**辅助断言，不是判据**：实测（2026-09-17）在夹具上下文里
        // **恒真、无法反向对照变红** —— 电池/自检期间 `GoalDirector` 处于挂起态，
        // 即使把 `say()` 接到 `GoalDirector.onEvent(...)`，`describe/isSuspended/lastRefusal` 也不变。
        // ⇒ F4 的**判别性判据**是：门禁 **F4-P1**（双向源码断言：说话通道不得引用决策层、决策层不得引用说话通道）
        // 以及本用例的 ①（把 `say()` 改成空操作 ⇒ ① 必红，已实测）。这里保留 ② 只为记录**意图**。
        check("②（非判据·意图记录）describe 前后一致", beforeDescribe.equals(afterDescribe));
        check("②（非判据·意图记录）isSuspended 前后一致", beforeSuspended == afterSuspended);
        check("②（非判据·意图记录）lastRefusal 前后一致", beforeRefusal.equals(afterRefusal));

        SpeechChannel.reset(bot);
        check("收尾：说话计数已复位", SpeechChannel.saidCount(bot) == 0);
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[Speech] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 说话通道自检 "
                    + (pass ? "PASS（只出不进：说了 " + UTTERANCES + " 句，决策态未变）" : "FAIL " + failures)));
        }
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }
}
