package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.task.ClearGuardCheckTask;
import com.dddgn.alice.task.ClearRetryCheckTask;
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
                        () -> new ClearGuardCheckTask(ctx.bot(), ctx.scope()), 900));
    }
}
