package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.CandidateMenu;
import com.dddgn.alice.decision.GoalAction;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **决策层契约自检**（基-2 / D-149）：把 S2 的判据做成**纯逻辑断言**（不改世界、不调 LLM）——
 * 这样它们能进串联回归电池，任何改动都跑得到。
 *
 * <pre>
 * 1 候选菜单：站在伐木场景里必须有 tree@… 候选（并且**只列可做的**）
 * 2 缺 target：{"kind":"lumber"} 无 target ⇒ 必须 Refused（不猜坐标）
 * 3 未知 target：target="tree@0,0,0"（不在菜单里）⇒ 必须 Refused
 * 4 未知动作/未知 kind ⇒ 必须 Refused
 * 5 命中菜单：target=<菜单里的 id> ⇒ StartJob 且 center == 该候选位置
 * 6 参数夹取：quota=9999 / radius=999 ⇒ 夹到安全区间（并记 clamps）
 * 7 区域型：无已保存区域时 {"kind":"region_lumber"} ⇒ Refused（LLM 不能凭空发明区域）
 * </pre>
 */
public class DecisionContractCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private boolean done;

    public DecisionContractCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "DecisionContractCheck";
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
        done = true;
        CandidateMenu menu = CandidateMenu.build(bot);
        check("菜单非空", !menu.entries().isEmpty());
        var tree = menu.entries().stream().filter(e -> "lumber".equals(e.kind())).findFirst().orElse(null);
        check("菜单含 lumber 候选", tree != null);

        checkRefused("缺失 target", "{\"action\":\"start_job\",\"kind\":\"lumber\"}");
        checkRefused("未知 target",
                "{\"action\":\"start_job\",\"kind\":\"lumber\",\"target\":\"tree@0,0,0\"}");
        checkRefused("未知动作", "{\"action\":\"explode\",\"target\":\"tree@0,0,0\"}");
        checkRefused("未知 kind", "{\"action\":\"start_job\",\"kind\":\"fishing\",\"target\":\"tree@0,0,0\"}");

        if (tree != null) {
            GoalAction action = GoalAction.parse(
                    "{\"action\":\"start_job\",\"kind\":\"lumber\",\"target\":\"" + tree.id()
                            + "\",\"quota\":9999,\"radius\":999}", bot, menu);
            if (action instanceof GoalAction.StartJob start) {
                check("命中菜单 ⇒ center 取候选位置",
                        start.request().center().equals(tree.pos()));
                check("quota 被夹取", start.request().quota() < 9999);
                check("radius 被夹取", start.request().radius() < 999);
                check("夹取有记录", !start.clamps().isEmpty());
            } else {
                failures.add("命中菜单却未 StartJob：" + action);
            }
        }

        if (com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer())
                .region(bot.getUUID()) == null) {
            checkRefused("无区域时 region_lumber", "{\"action\":\"start_job\",\"kind\":\"region_lumber\"}");
        }

        boolean pass = failures.isEmpty();
        BotLog.info("[DecisionContract] SUMMARY checks=7 failures={} {} → {}",
                failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 决策层契约自检 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Status.DONE : Status.FAILED;
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            failures.add(what);
        }
    }

    private void checkRefused(String what, String json) {
        GoalAction action = GoalAction.parse(json, bot, CandidateMenu.build(bot));
        if (!(action instanceof GoalAction.Refused)) {
            failures.add(what + " 应 Refused，实际 " + action);
        }
    }
}
