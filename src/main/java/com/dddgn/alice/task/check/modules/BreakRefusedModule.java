package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.task.BreakRefusedCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **破坏被拒模块**（`D-323`，2026-09-18）—— "不许把没发生的破坏记成成功"的离线门禁。
 *
 * <p>为什么值得进 CORE：这条缺陷**已经在真机上骗过我们一次**（用户实测：退队后 FTB 拦下的 4 次破坏，
 * 我们全记成了 `block_break_done` + `COMPLETED`，而存档里那 4 格还是泥土）——破坏是所有挖掘/清障/
 * 伐木路径的底座，它一旦"谎报成功"，上层的完成条件、写预算、决策层失败码会同时失真，
 * 而且**只在有保护的地方才显形**（家里能挖、别人领地不能挖）。
 *
 * <p>判据四条（详见 {@link BreakRefusedCheckTask}）：对照真成功 · 冒险模式被拒 · FTB 认领被拒 ·
 * 自清理。成本 ≈200 tick（三个用例各手挖一块泥土）。
 *
 * <p>档位 = **MAIN**（进 CORE）：按 `docs/BATTERY_CURATION.md` **规则 1**「新能力默认进 MAIN」。
 */
public final class BreakRefusedModule implements CheckModule {

    @Override
    public String id() {
        return "break_refused";
    }

    @Override
    public String title() {
        return "破坏被拒（世界事实判定 / 冒险模式 / FTB 认领 / 不许谎报成功）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        return List.of(
                CheckStep.of("break_refused", CheckProfile.MAIN, List.of(), null,
                        () -> new BreakRefusedCheckTask(ctx.bot(), ctx.observer()), 700));
    }
}
