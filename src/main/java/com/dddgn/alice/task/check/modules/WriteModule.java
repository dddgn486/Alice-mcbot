package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.RecoverabilityCheckTask;
import com.dddgn.alice.task.WritePolicyCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **写入与可回收性模块（R-2 第十二片，2 步）**：`recoverability` + `write_policy`（都是 **BASELINE**）。
 *
 * <ul>
 *   <li>{@code recoverability}：**可回收性真的被评估**（P0-B：不再是"两边写死 `LOCAL_STEP`、校验恒假"
 *       —— 那正是"死抽象"的形态）；转换点是执行期每段一次的 `PlannedMovementSpecs.toSpec`，
 *       于是"可回收性是不是常量"变成**可核对的数据**而不是读代码猜；</li>
 *   <li>{@code write_policy}：**写入集中策略表**（D-207 ①）自检 —— 表完整性 + **越权必须被拒**的负例
 *       + 未登记 `requester` 留痕 = 0。</li>
 * </ul>
 *
 * <p>⚠️ **`write_policy` 在电池里的位置是刻意的**（原注释保留在电池侧）：它要审计"**本次电池此前所有写入**"
 * 的归因样本（未登记 requester / 表外 (行,理由)）⇒ **样本越多越有意义** ⇒ 它必须**靠后**跑。
 * 本模块因此**不能**被挪到电池前段（那会让它审计到一个空样本集 ⇒ 判据在空集上"假绿"）。
 *
 * <p>两步都纯逻辑（无场景、不写世界）⇒ 原电池就是 `scenes=[] provision=null`，搬迁保持原样 ✓。
 */
public final class WriteModule implements CheckModule {

    @Override
    public String id() {
        return "write";
    }

    @Override
    public String title() {
        return "写入与可回收性（可回收性真的被评估 / 写入策略表 + 越权负例）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("recoverability", CheckProfile.BASELINE, List.of(), null,
                        () -> new RecoverabilityCheckTask(bot, observer), 200),
                CheckStep.of("write_policy", CheckProfile.BASELINE, List.of(), null,
                        () -> new WritePolicyCheckTask(bot, observer), 300));
    }
}
