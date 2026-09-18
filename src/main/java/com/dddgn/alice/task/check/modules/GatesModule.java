package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.CapabilityGateCheckTask;
import com.dddgn.alice.task.PartialSearchCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **闸门模块（R-2 第十一片，2 步）**：`partial_search` + `capability_gate`（都是 **BASELINE**）。
 *
 * <p>两步都是"**契约式闸门**"：纯逻辑、无场景、不写世界、不调 LLM ⇒ 便宜且确定（任何改动都跑得到）。
 * <ul>
 *   <li>{@code partial_search}：**`SEARCH_LIMIT` ≠ `UNREACHABLE`** —— 预算耗尽要**交出前缀**，
 *       真失败**不给**前缀（这是 D-076 红线在"预算"这一维上的可执行版本）；</li>
 *   <li>{@code capability_gate}：`MovementCapabilities` **真的能拦人**（保护区 / 资源 / 工具 / 预算 /
 *       声明一致性）；⚠️ 它的类注释记着一次真缺陷：`requiresZoneAuthorization` 曾写死 `false`
 *       ⇒ 保护区分支在生产里**永不触发**、"保护区"字段退化成装饰（G8 同族）⇒ 本步是那条回归的门禁。</li>
 * </ul>
 *
 * <p>**为什么不用自带场景/provision**：两步都**不碰地形也不碰世界**（纯逻辑判定）⇒ 原电池就是
 * `scenes=[] provision=null`，搬迁保持原样 ✓（与 `tools` 片同型：前提要不要补，取决于夹具**自己动什么**）。
 */
public final class GatesModule implements CheckModule {

    @Override
    public String id() {
        return "gates";
    }

    @Override
    public String title() {
        return "闸门（SEARCH_LIMIT≠UNREACHABLE / 能力闸门真的能拦人）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("partial_search", CheckProfile.BASELINE, List.of(), null,
                        () -> new PartialSearchCheckTask(bot, observer), 200),
                CheckStep.of("capability_gate", CheckProfile.BASELINE, List.of(), null,
                        () -> new CapabilityGateCheckTask(bot, observer), 200));
    }
}
