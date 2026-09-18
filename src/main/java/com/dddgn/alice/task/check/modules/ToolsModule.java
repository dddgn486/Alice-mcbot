package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.ToolSupplyCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **工具模块（R-2 第十片，1 步）**：`tool_supply` —— 工具供给（基-9）。
 *
 * <p>搬运口径 = **逐字段等价**：步名/档位（BASELINE）/预算（400）/工厂与原电池内联定义完全一致。
 *
 * <p>覆盖（三个用例 + 一个负例，都在**真实背包**上做）：
 * <ul>
 *   <li>A：手上是把快坏的镐、主背包有把新的 ⇒ 维护后**新的到手上**（`promoted_from_main`）；</li>
 *   <li>B：只有那把快坏的、没有替代 ⇒ **如实报** `worn_no_spare`（**不假装修好了**）；</li>
 *   <li>C：身上根本没有镐 ⇒ 如实报 `no_tool`，且 ⭐ **不能凭空变出工具**（负例 —— 工具管理最容易长出
 *       "偷偷给工具"的行为，生产路径不许擅自变出资源：那属于合成与请示通道）。</li>
 * </ul>
 *
 * <p>**为什么本模块没有场景、也没有 provision**：`ToolSupplyCheckTask` **只动背包**
 * （用 `FixtureToolKit` 摆好 A/B/C 三种背包状态），不依赖地形、不做传送、不写世界
 * ⇒ 原电池就是 `scenes=[] provision=null`，搬迁保持原样 ✓（也因此它**单跑不受"区块冷热"影响**）。
 */
public final class ToolsModule implements CheckModule {

    @Override
    public String id() {
        return "tools";
    }

    @Override
    public String title() {
        return "工具（供给三例 + 不许凭空变工具）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("tool_supply", CheckProfile.BASELINE, List.of(), null,
                        () -> new ToolSupplyCheckTask(bot, observer), 400));
    }
}
