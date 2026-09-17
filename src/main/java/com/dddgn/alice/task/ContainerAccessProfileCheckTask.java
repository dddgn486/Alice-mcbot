package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuCodes;
import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.risk.RiskProfile;
import com.dddgn.alice.pathing.risk.RiskSwitches;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * **`containerAccess` 画像字段的判据（D-291，2026-09-17 用户裁定加入首批画像字段）**。
 *
 * <p>要证的事：容器访问是**按 bot 冻结的画像**说了算 ✓，且关掉后**不碰世界**、以**可读理由**拒绝 ✓。
 * <ol>
 *   <li>默认（`container_access=true`）⇒ `MenuSession.open` **不会**以 `profile_denies_container` 拒绝 ✓；</li>
 *   <li>关掉开关 + 重新冻结画像 ⇒ **必须**以 `profile_denies_container` 拒绝 ✓（**这是判据**）；</li>
 *   <li>收尾：开关与画像都复位（否则污染后续步 ✗ —— 与 D-283 的纪律一致）。</li>
 * </ol>
 * **反向对照**：把 `MenuSession.open` 里的画像硬门删掉 ⇒ 第 2 条必红（拒绝理由永远是别的）。
 */
public class ContainerAccessProfileCheckTask implements Task {

    private final BotPlayer bot;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private int phase;
    private boolean done;

    public ContainerAccessProfileCheckTask(BotPlayer bot) {
        this.bot = bot;
    }

    @Override
    public String taskName() {
        return "ContainerAccessProfileCheck";
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
        // 目标格：bot 脚下旁边一格（真实容器不参与这次判据：画像门在**碰世界之前** ✓）
        BlockPos target = bot.blockPosition().relative(bot.getDirection());
        switch (phase) {
            case 0 -> {
                RiskSwitches.set(RiskSwitches.CONTAINER_ACCESS, true);
                RiskProfile.freeze(bot);
                String allowedReason = refusalReason(target);
                check("① 默认允许开箱 ⇒ 拒绝理由**不是** profile_denies_container（实测 " + allowedReason + "）",
                        !MenuCodes.PROFILE_DENIES_CONTAINER.equals(allowedReason));
                RiskSwitches.set(RiskSwitches.CONTAINER_ACCESS, false);
                RiskProfile.freeze(bot);   // 关键：冻结画像后开关才生效（这正是 S-6 的语义 ✓）
                phase = 1;
            }
            case 1 -> {
                String deniedReason = refusalReason(target);
                check("② **关掉画像开关 ⇒ 必须 profile_denies_container**（实测 " + deniedReason + "）",
                        MenuCodes.PROFILE_DENIES_CONTAINER.equals(deniedReason));
                // 收尾复位（不污染后续步）
                RiskSwitches.set(RiskSwitches.CONTAINER_ACCESS, true);
                RiskProfile.freeze(bot);
                String restored = refusalReason(target);
                check("③ 复位后不再以画像理由拒绝（实测 " + restored + "）",
                        !MenuCodes.PROFILE_DENIES_CONTAINER.equals(restored));
                BotLog.info("[ContainerAccess] 观测：allow={} deny={} restored={}",
                        phase0Reason, deniedReason, restored);
                done = true;
                boolean pass = failures.isEmpty();
                BotLog.info("[ContainerAccess] SUMMARY checks={} failures={} {} → {}",
                        checks, failures.size(), failures, pass ? "PASS" : "FAIL");
                return Status.RUNNING;
            }
            default -> {
                return Status.RUNNING;
            }
        }
        return Status.RUNNING;
    }

    private String phase0Reason = "-";

    /** 以真实入口发一次开箱请求，只取"拒绝理由"（不关心是否真开成 ✓）。 */
    private String refusalReason(BlockPos target) {
        MenuSession session = MenuSession.open(bot, target, 27);
        String reason = session == null ? "null"
                : (session.failure() == null || session.failure().isBlank()
                        ? "opened/" + session.state()
                        : session.failure());
        if (phase == 0) {
            phase0Reason = reason;
        }
        session.close("profile_check_done");   // 失败路径内部已收尾；这里保证不遗留会话 ✓
        return reason;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
