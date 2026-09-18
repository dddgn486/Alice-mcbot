package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.EventThresholdCheckTask;
import com.dddgn.alice.task.RecipesDumpCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **观测/转储模块（R-2 第十六片，2 步）**：`recipes_dump`(EXTRA) + `event_thresholds`(EXTRA)。
 *
 * <ul>
 *   <li>{@code recipes_dump}：**配方转储**（把运行期看到的配方/站点事实落成可核对的数据）；</li>
 *   <li>{@code event_thresholds}：**事件层阈值**（S4）—— "工具见底 / 卡住"两类**可行动病症**，
 *       各验"**报到**"与"**只报一次**"（夹具自带前提断言）。⚠️ 这条线是"把病症变成可读事实"的基础：
 *       没有它，`NO_PROGRESS`/`DANGER` 这类上报都无从取证。</li>
 * </ul>
 *
 * <p>⚠️ 两步**都不带场景**（原电池即 `scenes=[] provision=null`）⇒ 搬迁保持原样 ✓；
 * 但 `event_thresholds` 预算 **800 tick**（它是本片里唯一"要等一等"的步 —— 事件阈值要跨 tick 才显形）。
 *
 * <p>⭐ **EXTRA 步进模块的第三个受益者**：两步都是 EXTRA（CORE 不跑）⇒ `module:telemetry` 会跑它们，
 * 于是"事件上报"这条线第一次有了单独跑的通道 ✓。
 */
public final class TelemetryModule implements CheckModule {

    @Override
    public String id() {
        return "telemetry";
    }

    @Override
    public String title() {
        return "观测与转储（配方转储 / 事件阈值报到且只报一次）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("recipes_dump", CheckProfile.EXTRA, List.of(), null,
                        () -> new RecipesDumpCheckTask(bot, observer), 200),
                CheckStep.of("event_thresholds", CheckProfile.EXTRA, List.of(), null,
                        () -> new EventThresholdCheckTask(bot, observer), 800));
    }
}
