package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.tags.FluidTags;

/**
 * **上浮自救**（D-237，2026-09-15，用户批准的"乙"）：溺水且**浮得上去**时，按住跳跃把头露出水面。
 *
 * <p>为什么是"纯输入"而不是新 Movement：原版水里按住跳跃即上浮，Alice 的假人只是**从来没按过**这个键
 * （水面下沉 ⇒ 空气耗尽 ⇒ 淹死）。所以这条不需要内核新增任何水位 Movement（内核今天连含水路线都规划不出，
 * 见 D-236 / 台账 §5.11）—— 它只是把"玩家会做的本能动作"补上。
 *
 * <p>语义边界（诚实记）：
 * <ul>
 *   <li>**只求"能呼吸、能被救"**，不求"自己上岸"：上岸要寻路（`SurvivalExitTask` 那条路，需要干燥落点）；
 *       开阔水面里没有落点 ⇒ 本任务让 bot **踩水维持**，超出预算仍未成功则如实 FAILED 并封住本轮重试
 *       （{@link SurvivalSystem#markFloatFailed}）⇒ 下一次判决就会走"放弃任务"。</li>
 *   <li>**不在地面按跳跃键**：`BotController.setJumping(true)` 长按会变成兔子跳（该类的既有注释明确说过），
 *       所以只有"头在水里"时才按；头一出水就松开（并置空输入）。</li>
 *   <li>它实现 {@link SurvivalExit} 标记 ⇒ 维生**不会**在自救过程中二次否决它（否则会每 tick 自杀循环）。</li>
 * </ul>
 */
public final class SurvivalFloatTask implements Task, SurvivalExit {

    /** 呼吸到什么程度算"缓过来了"（低于此值继续踩水）。 */
    public static final int AIR_SAFE = 100;

    /** 预算：12 秒还浮不上来/缓不过来 ⇒ 如实失败（原版空气 300 tick 满，100 约需 25 tick 呼吸）。 */
    public static final int MAX_TICKS = 240;

    private final BotPlayer bot;
    private int ticks;
    private boolean done;
    private boolean success;
    private String failure = "";

    public SurvivalFloatTask(BotPlayer bot) {
        this.bot = bot;
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
        return done ? (success ? "surfaced" : failure) : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return success ? Status.DONE : Status.FAILED;
        }
        ticks++;
        boolean headUnderWater = bot.isEyeInFluid(FluidTags.WATER);
        // 头出水且空气缓过来 ⇒ 自救成功（**立刻松开按键**：在水里长按跳跃只是踩水，在地面长按会兔子跳）
        if (!headUnderWater && bot.getAirSupply() >= AIR_SAFE) {
            return finish(true, "");
        }
        if (ticks > MAX_TICKS) {
            SurvivalSystem.markFloatFailed(bot);
            return finish(false, "survival_float_failed");
        }
        // 只有头还在水里才按住跳跃（原版：水里按跳跃 = 上浮）；头出水后松手，避免在地面/水面兔子跳。
        bot.controller().setJumping(headUnderWater);
        if (ticks % 20 == 1) {
            BotLog.info("[Survival] 上浮自救 tick={} headUnderWater={} air={} y={}",
                    ticks, headUnderWater, bot.getAirSupply(), String.format("%.2f", bot.getY()));
        }
        return Status.RUNNING;
    }

    private Status finish(boolean ok, String reason) {
        done = true;
        success = ok;
        failure = reason;
        bot.controller().setJumping(false);
        bot.controller().stopMovement();
        BotLog.info("[Survival] 上浮自救{}（tick={} air={} y={}）",
                ok ? "成功：头已出水且空气复原" : "失败：" + reason, ticks, bot.getAirSupply(),
                String.format("%.2f", bot.getY()));
        return ok ? Status.DONE : Status.FAILED;
    }
}
