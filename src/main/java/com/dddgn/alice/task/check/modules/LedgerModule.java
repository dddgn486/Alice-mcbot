package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.task.ClearGuardCheckTask;
import com.dddgn.alice.task.ClearRetryCheckTask;
import com.dddgn.alice.task.LedgerZoneScopeCheckTask;
import com.dddgn.alice.task.LossyWriteAccountedCheckTask;
import com.dddgn.alice.task.ScaffoldLifecycleTask;
import com.dddgn.alice.task.WriteBudgetCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **账本 / 写入预算 / 场景清理模块**（R-2 的第一个模块，也是搬迁样板）。
 *
 * <p>这 4 步原先散在 `RegressionBatteryTask.buildSteps()` 里，逐字段搬到此处（**语义不变** ✓）。
 * 选中它当样板的原因：① 不依赖任何场景函数（{@code scenes} 为空 ✓）⇒ "单模块可单独跑"最容易成立 ✓；
 * ② 覆盖 R-2 想根治的那类问题（我方临时方块/写入预算/清理重试 ✓）。
 */
public final class LedgerModule implements CheckModule {

    @Override
    public String id() {
        return "ledger";
    }

    @Override
    public String title() {
        return "账本 / 写入预算 / 场景清理";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        return List.of(
                CheckStep.of("clear_retry", CheckProfile.BASELINE, List.of(), null,
                        () -> new ClearRetryCheckTask(ctx.bot(), ctx.scope()), 900),
                CheckStep.of("write_budget", CheckProfile.BASELINE, List.of(), null,
                        () -> new WriteBudgetCheckTask(ctx.bot(), ctx.scope()), 900),
                CheckStep.of("scaffold", CheckProfile.BASELINE, List.of(), null,
                        () -> new ScaffoldLifecycleTask(ctx.bot(), ctx.scope()), 900),
                CheckStep.of("clear_guard", CheckProfile.BASELINE, List.of(), null,
                        () -> new ClearGuardCheckTask(ctx.bot(), ctx.scope()), 900),
                // ⭐ `Z1` / `D-398`（2026-09-22）：**账本与恢复的地理范围 = 保护区及其子区域**
                //（区外不记账、不恢复、无限制修改；区内一定记账）。
                // EXTRA 而非 CORE：它自建场景（地板 + 目标格）、**改认领状态**、并且**故意让区外
                // 留一块方块不回收**（`D-398` R2 的直接后果）⇒ 与其它"会改世界"的取证夹具同档，
                // 只适合 `single:ledger_zone_scope` / `module:ledger` 单独跑。
                CheckStep.of("ledger_zone_scope", CheckProfile.EXTRA, List.of(), null,
                        () -> new LedgerZoneScopeCheckTask(ctx.bot(), ctx.observer()), 2400),
                // ⭐ `RC3`（2026-09-24）：**不可逆写入的如实记账**。EXTRA：它自建场景、真的破掉
                // 一个装着钻石的箱子 + 一个告示牌（**故意**让内容物拿不回来），只适合
                // `single:lossy_write_accounted` / `module:ledger` 单独跑。
                CheckStep.of("lossy_write_accounted", CheckProfile.EXTRA, List.of(), null,
                        () -> new LossyWriteAccountedCheckTask(ctx.bot(), ctx.observer()), 900));
    }
}
