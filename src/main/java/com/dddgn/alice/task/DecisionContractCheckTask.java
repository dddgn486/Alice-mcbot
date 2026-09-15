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

    /** 是否已把 bot 挪到场景起点（**夹具自带传送**，不依赖电池的 provision；见 PLAYBOOK §5.0d）。 */
    private boolean moved;

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (!moved) {
            // 本夹具依赖**伐木场景**（附近要有树）⇒ 先自己站到场景起点，**下一 tick 再干活**
            // （传送后立刻扫描会撞上"区块/实体还没就绪"⇒ 假失败）
            bot.teleportTo(bot.serverLevel(),
                    com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                    com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getY(),
                    com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            bot.controller().stopMovement();
            moved = true;
            BotLog.info("[DecisionContract] 已传送 bot 到场景起点 {}（{}）",
                    com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.toShortString(),
                    bot.blockPosition().toShortString());
            return Status.RUNNING;
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

        // M4b（D-234）：**任务树"子阶段失败"的口径**（纯逻辑、零世界改动）—— 四个 Job 共用
        // `TaskNode.finished(...)`，把它钉在这里等于一次钉住全部四个（lumber 侧端到端覆盖缺口已登记）。
        check("finished：成功子任务不留失败行",
                TaskNode.finished("T", "x", "P", 1, "", new StubTask("", "SCAN"), Status.DONE)
                        .lastFailure().isEmpty());
        check("finished：失败子任务带 code@phase",
                "boom@SCAN".equals(TaskNode.finished("T", "x", "P", 1, "",
                        new StubTask("boom", "SCAN"), Status.FAILED).lastFailure()));
        check("finished：一行事实有界（≤ ONE_LINE_MAX）",
                TaskNode.finished("T", "x", "P", 1, "", new StubTask("x".repeat(400), "P"), Status.FAILED)
                        .lastFailure().length() <= com.dddgn.alice.bot.TaskFailureReport.ONE_LINE_MAX);

        boolean pass = failures.isEmpty();
        BotLog.info("[DecisionContract] SUMMARY checks=10 failures={} {} → {}",
                failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 决策层契约自检 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        // **结束复位**（PLAYBOOK §5.0d）：停输入 + 回到场景起点，失败路径同样走
        bot.controller().stopMovement();
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.teleportTo(bot.serverLevel(),
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getY(),
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        BotLog.info("[DecisionContract] 结束复位：bot 回到 {}（onGround={}）",
                bot.blockPosition().toShortString(), bot.onGround());
        return pass ? Status.DONE : Status.FAILED;
    }

    /** M4b 口径判据用的最小子任务替身：只回答"失败理由与阶段是什么"。 */
    private record StubTask(String reason, String phase) implements Task {
        @Override
        public TaskTarget target() {
            return TaskTarget.block(net.minecraft.core.BlockPos.ZERO);
        }

        @Override
        public String failureReason() {
            return reason;
        }

        @Override
        public com.dddgn.alice.bot.TaskFailureReport failureReport() {
            return new com.dddgn.alice.bot.TaskFailureReport(reason, phase, "", null, null);
        }

        @Override
        public Status tick() {
            return Status.DONE;
        }
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
