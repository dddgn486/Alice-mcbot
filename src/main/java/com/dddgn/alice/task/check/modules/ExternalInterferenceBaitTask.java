package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.Driver;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.server.level.ServerPlayer;

/**
 * **编排器自检的"诱饵步"（R-2 Phase 1b）**：本步的任务**故意**让外部玩家命令把自己打断，
 * 用来证明"**编排器不在会话任务里 ⇒ 顶不掉它**" ✓。
 *
 * <p>背景（2026-09-17 实测事故）：旧电池**自己就是会话任务** ✗ ⇒ `/alice follow on`（替换）或
 * `/alice stop-task`（取消）会把**整轮电池**顶掉 ⇒ 电池没有判决行 ⇒ 整轮 `no_verdict` ✗✗。
 * 现在编排器由服务器 tick 驱动 ✓ ⇒ 本步应当**如实 FAIL**（它的任务确实被打断了 ✓），
 * 但**编排器必须继续跑完后续步并给出判决** ✓ —— 这正是本模块要断言的事 ✓。
 *
 * <p>两种打断方式（对应当时事故的两种形态 ✓）：`CANCEL`（`/alice stop-task`）与
 * `REPLACE`（以观察者身份 `/alice follow on` ⇒ 会话任务被**替换**，本步被挂起 ⇒ 由编排器按预算收尾 ✓）。
 */
public class ExternalInterferenceBaitTask implements Task {

    /** 打断方式。 */
    public enum Mode {
        /** 取消当前任务（对应 `/alice stop-task` ✓）。 */
        CANCEL,
        /** 替换当前任务（对应 `/alice follow on` ✓）。 */
        REPLACE
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Mode mode;
    private int ticks;
    private boolean dispatched;

    public ExternalInterferenceBaitTask(BotPlayer bot, ServerPlayer observer, Mode mode) {
        this.bot = bot;
        this.observer = observer;
        this.mode = mode;
    }

    @Override
    public String taskName() {
        return "harness_self_bait_" + mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return "被外部命令打断（预期行为，见本类注释 ✓）";
    }

    @Override
    public String terminalReason() {
        return "bait";
    }

    @Override
    public Status tick() {
        ticks++;
        if (ticks < 5) {
            return Status.RUNNING;
        }
        if (!dispatched) {
            dispatched = true;
            Driver.set(bot, Driver.IN_GAME_PLAYER);   // 打断来自玩家命令 ⇒ 归因也如实切到玩家 ✓
            switch (mode) {
                case CANCEL -> {
                    BotLog.info("[HarnessSelf] 诱饵：派发 /alice stop-task（应取消本步任务 ✗）");
                    dispatch("alice stop-task");
                }
                case REPLACE -> {
                    String name = observer == null ? null : observer.getGameProfile().getName();
                    if (name == null) {
                        BotLog.warn("[HarnessSelf] 诱饵：没有观察者 ⇒ 无法派发 follow（如实失败 ✓）");
                        return Status.FAILED;
                    }
                    BotLog.info("[HarnessSelf] 诱饵：以 {} 身份派发 /alice follow on（应替换本步任务 ✗）", name);
                    dispatch("execute as " + name + " at @s run alice follow on");
                }
                default -> {
                }
            }
        }
        // 等外部命令生效（被取消/被替换都会让本任务的 tick 不再被调用 ✓；若仍在跑说明打断没生效 ✗）
        return ticks > 60 ? Status.FAILED : Status.RUNNING;
    }

    private void dispatch(String command) {
        var server = bot.serverLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
