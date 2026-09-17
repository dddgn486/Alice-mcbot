package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.Driver;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **F1 判据（D-285，2026-09-17 用户裁定「F1 现在补」）**：终态归因 `Driver` 必须如实区分"谁指派的"。
 *
 * <p><b>为什么只断言到这一步</b>（诚实边界，2026-09-17 实测踩到）：**在电池步内部无法行为化验证"玩家命令"路径** ——
 * 任何指派任务的命令（如 `/alice follow on`）都会**顶掉当前会话任务**，也就是把**电池自己**踢掉
 * （实测日志：`已显式停止任务 RegressionBattery（command）` ⇒ 电池无判决行 ⇒ 整轮变 `no_verdict` ✗）。
 * 因此：
 * <ul>
 *   <li>**玩家/物品入口**的归因由门禁 **F1-P1** 静态锁死（`command/` 与 `item/` 的每个指派点之前必须有 `Driver.set`，注入可红）；</li>
 *   <li>**本夹具**断言电池自己这条链是通的：电池步期间 `Driver.of(bot) == "fixture"`（与玩家/LLM 区分开）。
 *       反向对照：去掉电池起点的 `Driver.set(FIXTURE)` ⇒ 本夹具变红。</li>
 * </ul>
 */
public class DriverLabelCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private boolean done;

    public DriverLabelCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "DriverLabelCheck";
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
        String driver = Driver.of(bot);
        check("电池驱动的步 ⇒ 归因 fixture（实测 " + driver + "）", Driver.FIXTURE.equals(driver));
        check("归因**不是**默认的 system（没设过就是 system，说明链路断了）", !Driver.SYSTEM.equals(driver));
        BotLog.info("[DriverLabel] 观测：driver={}（电池步内应为 fixture；玩家/物品入口由门禁 F1-P1 锁）", driver);
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[DriverLabel] SUMMARY checks={} failures={} {} → {}",
                checks, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] F1 归因自检 " + (pass ? "PASS" : "FAIL " + failures)));
        }
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
