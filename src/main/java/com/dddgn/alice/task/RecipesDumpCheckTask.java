package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.RecipeDump;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * **配方导出自检**（基-2 / D-149）：`/alice recipes` 是 S5 的数据入口，坏了整条 P1 就断了 ⇒ 进电池。
 *
 * <p>判据：导出成功、`recipes>0`、`tags>0`（原版就有 1000+ 配方与若干物品标签）；
 * 文件写到 `config/alice-recipes-battery.json`（**不覆盖用户手动导出的那份**）。
 */
public class RecipesDumpCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private boolean done;
    private String failure = "";

    public RecipesDumpCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "RecipesDumpCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public String terminalReason() {
        return done ? (failure.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failure.isEmpty() ? Status.DONE : Status.FAILED;
        }
        done = true;
        RecipeDump.Result result = RecipeDump.dump(bot.getServer(), "alice-recipes-battery.json");
        if (result.recipes() <= 0) {
            failure = "配方数为 0（导出可能坏了）";
        } else if (result.tags() <= 0) {
            failure = "物品标签数为 0（标签导出可能坏了）";
        } else if (result.path().startsWith("失败")) {
            failure = result.path();
        }
        boolean pass = failure.isEmpty();
        BotLog.info("[RecipesCheck] SUMMARY {} → {}", result.describe(), pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 配方导出自检 " + result.describe()
                    + " → " + (pass ? "PASS" : "FAIL")));
        }
        return pass ? Status.DONE : Status.FAILED;
    }
}
