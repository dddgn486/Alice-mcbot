package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.LlmContractCheckTask;
import com.dddgn.alice.task.PermissionContractCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **LLM 与权限契约模块（R-2 第十三片，2 步）**：`llm_contract`(MAIN) + `permission_gate`(EXTRA)。
 *
 * <ul>
 *   <li>{@code llm_contract}：**LLM 上抛契约**（基-5）—— Job 失败报告 / 产物判定口径 / 结构化拒绝回读；
 *       ⚠️ 它是"**失败必须是字段**"那条口径的门禁（`snapshot_failure_fields`：终态失败事实要有
 *       phase + 有界 details；无失败**不许留 stale**）；</li>
 *   <li>{@code permission_gate}：**权限契约**（`PermissionGate` 四档 + `ONCE/SESSION/ALWAYS` +
 *       "拒绝不卡死"原则）—— 答复入口不依赖客户端渲染（聊天栏可点击按钮 + 快捷键 Y/N）。</li>
 * </ul>
 *
 * <p>⭐ **本片是"EXTRA 步进模块"的第一个受益者**：`permission_gate` 是 **EXTRA**（CORE 不跑、只有 FULL 跑），
 * 进了模块之后 `module:llm` **会跑它**（编排器不按档位裁剪模块内容）⇒ 它第一次有了"**单独跑**"的通道。
 *
 * <p>两步都纯逻辑（无场景、不写世界、不调 LLM）⇒ 原电池就是 `scenes=[] provision=null`，搬迁保持原样 ✓。
 */
public final class LlmModule implements CheckModule {

    @Override
    public String id() {
        return "llm";
    }

    @Override
    public String title() {
        return "LLM 与权限契约（上抛失败字段 / 权限门四档）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("llm_contract", CheckProfile.MAIN, List.of(), null,
                        () -> new LlmContractCheckTask(bot, observer), 200),
                CheckStep.of("permission_gate", CheckProfile.EXTRA, List.of(), null,
                        () -> new PermissionContractCheckTask(bot, observer), 400));
    }
}
