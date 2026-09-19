package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.MineDropRangeCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * ⭐ **挖矿落物「收集距离窗口」的取证模块**（`survey/22 §1.5①`；步 `mine_far_drop`）。
 *
 * <h2>为什么它必须是一个「期望判决 = FAIL」的独立模块</h2>
 * <ol>
 *   <li><b>它断言的是缺陷的**反面**</b>（"两件产物都要进背包、地上不许剩"）⇒ 修好之前**必然红**。
 *       这正是它存在的意义：把"静默丢弃产物"从一句推断变成**一条可复跑的红判据**；</li>
 *   <li><b>不能进 CORE，也不能进任何期望 PASS 的模块</b>：那条不变量（CORE 全绿 / 每个模块可单独跑通）
 *       是收口闸门。放进去 = 用一个已知缺陷把闸门永久染红，后续真回归就淹没了；</li>
 *   <li>⇒ 走**已有机制**：{@link CheckModule#expectedVerdict()} 声明 `FAIL`
 *       （同 {@code HarnessSelfModule} 的"故意失败"），`tools/module-selftest.sh` 按声明断言
 *       ⇒ **不把"故意失败"当回归** ✓；</li>
 *   <li>⇒ 本模块**不被电池组合**（`RegressionBatteryTask` 里没有 `new MineDropRangeModule().steps(`）
 *       ⇒ 它不进 CORE/FULL，只走 `module:mine_drop_range` ✓（`R2-P2` 门禁对这种模块正当豁免 ✓）。</li>
 * </ol>
 *
 * <h2>自带的双向绊线（这是它比"记一笔待办"强的地方）</h2>
 * <ul>
 *   <li><b>今天</b>：判据红 ⇒ 模块 `verdict=FAIL` ⇒ 与声明的 `FAIL` **相符** ⇒ 全套门禁照旧绿；</li>
 *   <li><b>修好那天</b>：判据变绿 ⇒ 模块 `verdict=PASS`，而声明还是 `FAIL` ⇒
 *       `module-selftest` 当场判红 ⇒ **强迫**来这里把 {@link #expectedVerdict()} 去掉、
 *       并按 `MineDropRangeCheckTask` 头部「翻面条件」把它**升级成链路级判据**（真跑 `MineJob`）
 *       或搬进挖掘模块的 CORE 档 ✓。</li>
 * </ul>
 *
 * <p>⚠️ 与 `HarnessSelfModule` 的区别：那边是"编排器能不能扛住外部打断"（工具自检），
 * 这边是"**生产行为的缺陷取证**"（`survey/22` 报告项落地）。两者共用机制，但**来源与期限不同** ——
 * 本模块的声明里写明了期限（修好即翻面），别把它当永久例外。
 */
public final class MineDropRangeModule implements CheckModule {

    @Override
    public String id() {
        return "mine_drop_range";
    }

    @Override
    public String title() {
        return "挖矿落物收集的距离窗口（缺陷取证：产物 &gt;32 格被永久退休 ⇒ product_not_collected）";
    }

    @Override
    public String expectedVerdict() {
        // 唯一一处"故意红"：见类头注释的双向绊线（修好之后本方法必须被删掉）。
        return "FAIL";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        // 场景与发料**都由夹具自带**（自建空中走廊 + 自己 summon 落物）⇒ 本模块自足 ✓（不依赖任何前序步）
        return List.of(
                CheckStep.of("mine_far_drop", CheckProfile.EXTRA, List.of(), null,
                        () -> new MineDropRangeCheckTask(bot, ctx.observer(), ctx.scope()), 900));
    }
}
