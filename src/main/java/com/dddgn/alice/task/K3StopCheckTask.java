package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **K-3 安全点停止自检**（{@code alice:k3_stop_check} / 电池步）：**确定性**制造"不安全时刻"，
 * 不靠人手抢时机（2026-09-13 用户实测：手动抓跳跃瞬间"过程很乱"⇒ 夹具设计失败）。
 *
 * <p>两个用例（同一实现的两种模式）：
 * <ul>
 *   <li>{@link Mode#DEFER **DEFER**}：升空（`!onGround()` ⇒ 不安全）→ 请求停止 ⇒ 期望**延后**；
 *       随后**放开让它落地** ⇒ 落到安全点后执行停止（日志 `已到安全点，执行延后的停止（等待 N tick）`），
 *       且 `bot_report` 的 `deferred` 计数 +1；</li>
 *   <li>{@link Mode#FORCED **FORCED**}：升空 → 请求停止 → **一直吊在空中**（每 tick 维持高度）⇒
 *       超过 `SAFE_STOP_DEFER_TICKS` 后**强制停并计数**（日志 `任务在**不安全时刻被强制停止**`），
 *       且 `forcedUnsafe` 计数 +1 —— 这条验"不无限等"。</li>
 * </ul>
 *
 * <p>为什么"升空"就是合法的"不安全"：K-3 的判据是 `task.safeToCancel() && bot.onGround()`，
 * 而 `!onGround()` 是**与任务类型无关的硬事实**。夹具只要维持空中状态，就一定能命中延后/强停两条路径。
 */
public class K3StopCheckTask implements Task {

    public enum Mode { DEFER, FORCED }

    /** 升空高度：足够高，落地要几 tick（留给"延后"窗口）。 */
    private static final int HOVER_HEIGHT = 6;
    /** 吊在空中多久后请求停止（先飞稳再请求）。 */
    private static final int HOVER_TICKS_BEFORE_REQUEST = 10;
    /** 请求后最多再观察多少 tick（防夹具自己卡死；强停阈值是 20 tick）。 */
    private static final int OBSERVE_AFTER_REQUEST = 60;

    private enum Phase { SETUP, HOVER, REQUEST, OBSERVE, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Mode mode;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int phaseTicks;
    private boolean requestAccepted;
    private boolean releasedHold;
    private boolean observedStillTicking;

    public K3StopCheckTask(BotPlayer bot, ServerPlayer observer, Mode mode) {
        this.bot = bot;
        this.observer = observer;
        this.mode = mode;
    }

    /** 夹具前提断言用：空中状态必须成立。 */
    @Override
    public boolean safeToCancel() {
        return true;   // 本任务自身不阻挠取消（不安全性由"空中"提供，不是任务层承诺）
    }

    @Override
    public String taskName() {
        return "K3StopCheck";
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
        return phase == Phase.DONE ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 300) {
            return finish("timeout");
        }
        phaseTicks++;
        return switch (phase) {
            case SETUP -> setup();
            case HOVER -> hover();
            case REQUEST -> request();
            case OBSERVE -> observe();
            case DONE -> failures.isEmpty() ? Status.DONE : Status.FAILED;
        };
    }

    // ==================== 各阶段 ====================

    private Status setup() {
        // **夹具前提断言（D-169，2026-09-13 实测事故）**：本用例观察的是"**顶层任务**被延后停止"，
        // 所以它自己必须是 bot 的**顶层任务**。被嵌进别人的步骤里（例如回归电池的 `k3_stop_defer`）时，
        // `BotManager.stopTask` 停的是**父任务**（电池）—— 实测正是如此：
        // `停止请求延后到安全点：task=RegressionBatteryTask` → `已显式停止任务 RegressionBatteryTask`
        // ⇒ 电池在第 12/25 步**自杀**、永远打不出 SUMMARY（两轮实测都是这个死法）。
        // 因此这里先自断言前提，不满足就**立即失败且绝不调用 stopTask**（绝不再伤父任务）。
        BotManager.BotSession session = BotManager.sessionOf(bot);
        if (session == null || session.currentTask() != this) {
            failures.add("fixture_not_top_level");
            BotLog.warn("[K3] fixture_not_top_level：本自检必须是 bot 的顶层任务"
                    + "（嵌套运行会停掉父任务）。current={}",
                    session == null || session.currentTask() == null
                            ? "-" : session.currentTask().taskName());
            return finish("fixture_not_top_level");
        }
        BlockPos foot = bot.blockPosition();
        BlockPos hover = foot.above(HOVER_HEIGHT);
        bot.teleportTo(bot.serverLevel(), hover.getX() + 0.5D, hover.getY(), hover.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[K3] mode={} 升空到 {}（夹具前提：空中 ⇒ 停止必然不安全）",
                mode, bot.blockPosition().toShortString());
        return advance(Phase.HOVER);
    }

    private Status hover() {
        holdAltitude();
        if (phaseTicks < HOVER_TICKS_BEFORE_REQUEST) {
            return Status.RUNNING;
        }
        // **夹具前提断言**：确实在空中（否则本用例测的是空气）
        if (bot.onGround()) {
            failures.add("fixture_not_airborne");
            return finish("fixture_not_airborne");
        }
        return advance(Phase.REQUEST);
    }

    private Status request() {
        String kind = BotManager.stopTask(bot, "k3_" + mode.name().toLowerCase(java.util.Locale.ROOT));
        requestAccepted = kind != null;
        BotLog.info("[K3] mode={} 已请求停止 task={} accepted={} airborne={}",
                mode, kind, requestAccepted, !bot.onGround());
        if (!requestAccepted) {
            failures.add("stop_request_rejected");
            return finish("stop_request_rejected");
        }
        return advance(Phase.OBSERVE);
    }

    private Status observe() {
        if (mode == Mode.DEFER) {
            // 放开吊空 ⇒ 让它自然落地 ⇒ 期望"到安全点后执行延后的停止"
            if (!releasedHold) {
                releasedHold = true;
                BotLog.info("[K3] DEFER：放开吊空，等它落地（落点即安全点）");
            }
        } else {
            // FORCED：一直吊在空中 ⇒ 期望 20 tick 后被强制停
            holdAltitude();
        }
        // **核心观察点**：请求停止之后，本任务是否**仍在被 tick**？
        // 在（= 延后生效）⇒ 说明 `stopTask` 没有在不安全时刻硬停。
        if (phaseTicks == 3 && ticks > 0) {
            observedStillTicking = true;
            BotLog.info("[K3] mode={} 请求后仍被 tick（tick={}）⇒ {}",
                    mode, ticks, mode == Mode.DEFER ? "延后生效（等待安全点）" : "等待强停阈值");
        }
        if (phaseTicks > OBSERVE_AFTER_REQUEST) {
            // 这么久还没被停：DEFER 说明落地后没停（缺陷）；FORCED 说明强停没触发（缺陷）
            failures.add(mode == Mode.DEFER ? "defer_never_stopped" : "forced_never_stopped");
            return finish("not_stopped_within_window");
        }
        return Status.RUNNING;   // 若停止生效，本方法不会再被调用（任务已被清掉）
    }

    /** 维持高度（吊在空中）：清速度 + 每 tick 拉回原位。 */
    private void holdAltitude() {
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        if (!bot.onGround() && phaseTicks % 5 == 0) {
            bot.teleportTo(bot.serverLevel(), bot.getX(), bot.getY(), bot.getZ(),
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
        }
    }

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status finish(String reason) {
        phase = Phase.DONE;
        notes.add("stillTickingAfterRequest=" + observedStillTicking);
        String summary = "mode=" + mode + " requestAccepted=" + (requestAccepted ? "PASS" : "FAIL")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[K3] SUMMARY {} {} reason={}", summary, String.join(" ", notes), reason);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[K3] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }
}
