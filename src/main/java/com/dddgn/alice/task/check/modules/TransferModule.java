package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.TransferCheckTask;
import com.dddgn.alice.task.TransferCourseAnchor;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **传输模块（R-2 第七片，1 步）**：`transfer` —— L2 容器传输（破坏性最强的那条路）。
 *
 * <p>搬运口径 = **逐字段等价**：步名/档位/预算/工厂与原电池内联定义完全一致
 * （`step("transfer", List.of(), null, () -> new TransferCheckTask(bot, observer), 400)`）；
 * 唯一新增的是**模块自带前提** —— 见下。
 *
 * <p>覆盖（`TransferCheckTask` 自带 4 个同步夹具 + 1 个跨真实 tick 的端到端，逻辑未变）：
 * <ul>
 *   <li>{@code fixture}：正常两段传输 + 容量拒绝 + 源不足 + 组件拒绝 + 端点拒绝 + 模拟冲突 +
 *       增量不一致 + 账本策略 + 任务中断策略 + 硬寻路码/预算码映射；</li>
 *   <li>{@code selection}：端点草稿（选择/取消/过期/跨维度/同端点拒绝）；</li>
 *   <li>{@code selector_events}：选择器物品的事件分发与"不干扰原版交互"；</li>
 *   <li>{@code command_parse}：`transfer-selection` 命令解析；</li>
 *   <li>{@code end_to_end}：真 `TransferTask` 跨真实 tick 跑到终态，断言物品真的到了目标箱。</li>
 * </ul>
 *
 * <p>⚠️ **为什么模块必须自带"先传送"这一条前提（本片唯一的新增）**：夹具**自己会调场景函数**
 * （`TransferCourseAnchor.SCENE_FUNCTION`），但它的顺序是"**先跑函数、再传送**"
 * （见 `TransferCheckTask.runFixtures`）⇒ 在**冷区块**上那一发 `/fill` 会**静默不落地**
 * （D-296 记过同一个坑：旧电池"场景→provision"的顺序让 `/fill` 落在冷区块上 ⇒ 判据在虚空里**假绿**）。
 * 编排器的顺序是 **openScope → provision → scenes**（`CheckHarness` 已按此对齐 ✓），
 * 所以这里把"传送到课程起点"放进 `provision`：既**先热了区块**，又不重复跑场景函数（夹具自己会跑）。
 *
 * <p>**不声明 `scenes`** 是**刻意**的：场景函数由夹具自己执行（与原电池同口径），
 * 若在模块里再声明一次就会**跑两遍** —— 那属于"行为不等价"的搬迁（D-302）。
 */
public final class TransferModule implements CheckModule {

    @Override
    public String id() {
        return "transfer";
    }

    @Override
    public String title() {
        return "传输（4 夹具：主流程/端点选择/选择器事件/命令解析 + 端到端）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("transfer", CheckProfile.BASELINE, List.of(),
                        () -> to(bot, TransferCourseAnchor.BASE),
                        () -> new TransferCheckTask(bot, observer), 400));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
