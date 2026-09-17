package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.task.NoopCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **编排器自检模块（R-2 Phase 1b）**：证明「**外部玩家命令顶不掉编排器**」✓。
 *
 * <p>三步：① 被 `/alice stop-task` **取消**（如实 FAIL ✓）；② 被 `/alice follow on` **替换**（如实 FAIL ✓）；
 * ③ 一个普通步（必须 PASS ✓ —— 它跑得下去本身就证明**编排器活过了前两次打断** ✓✓）。
 * 因此本模块的**期望判决是 FAIL**（两次预期失败 ✓），由 {@link #expectedVerdict()} 声明 ✓，
 * `tools/module-selftest.sh` 按声明断言 ⇒ 不会把"故意失败"误当回归 ✗。
 */
public final class HarnessSelfModule implements CheckModule {

    @Override
    public String id() {
        return "harness_self";
    }

    @Override
    public String title() {
        return "编排器自检（故意被外部命令打断两次）";
    }

    @Override
    public String expectedVerdict() {
        return "FAIL";   // 两个诱饵步**预期**失败 ✓；第三步必须过 ⇒ 证明编排器活着 ✓
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        return List.of(
                CheckStep.of("harness_bait_cancel", CheckProfile.EXTRA, List.of(), null,
                        () -> new ExternalInterferenceBaitTask(ctx.bot(), ctx.observer(),
                                ExternalInterferenceBaitTask.Mode.CANCEL), 200),
                CheckStep.of("harness_bait_replace", CheckProfile.EXTRA, List.of(), null,
                        () -> new ExternalInterferenceBaitTask(ctx.bot(), ctx.observer(),
                                ExternalInterferenceBaitTask.Mode.REPLACE), 400),
                CheckStep.of("harness_survived", CheckProfile.EXTRA, List.of(), null,
                        () -> new NoopCheckTask(ctx.bot(), "harness_survived"), 60));
    }
}
