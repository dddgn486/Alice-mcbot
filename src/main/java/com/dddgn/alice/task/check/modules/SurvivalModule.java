package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.SurvivalCourseAnchor;
import com.dddgn.alice.task.SurvivalExitCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **维生模块（R-2 第八片，1 步）**：`survival_exit` —— 维生的第一个（也是目前唯一一个）电池步。
 *
 * <p>搬运口径 = **逐字段等价**：步名/档位/场景/预算/工厂与原电池内联定义完全一致
 * （`step("survival_exit", List.of("alice_test:survival_course"), () -> teleportBot(PLATFORM_FOOT),
 * () -> new SurvivalExitCheckTask(bot, observer), 900)`）。
 *
 * <p>覆盖（S-5 / D-226，与 `SurvivalExitCheckTask` 的 javadoc 同一口径）：
 * <ul>
 *   <li>维生决策表：**软危险**（溺水/着火）需"宽限 + 有出口"才否决；**无出口不否决**；</li>
 *   <li>封闭场景四层前提自证；**真实着火** ⇒ 不否决 + `exit=none decision=continue`；</li>
 *   <li>脚位格口径回归（半砖：不能用 `blockPosition()` 当"排除自己"，否则逃生 0 步原地不动）；</li>
 *   <li>出口**真能走到**（驱动真 `SurvivalExitTask` 到 `DONE`）+ 掉血 ⇒ `DANGER`（`delta=`）且不刷屏；</li>
 *   <li>水域：逃生准备金（放置 + 破坏 + `PILLAR`，上限 8/8）+ **预算真的会咬**的上限守卫。</li>
 * </ul>
 *
 * <p>⚠️ **两个场景的分工（照抄原电池注释，别搞混）**：
 * <ul>
 *   <li>**有出口**的平台 = 数据包场景 `alice_test:survival_course`（本模块在 `scenes` 里声明 ⇒ 由编排器执行 ✓）；</li>
 *   <li>**封闭场景（无出口）** = 夹具**自己**在 bot 到位后建造（`SurvivalExitCheckTask` 内部）。
 *       它**不能**放进 `scenes`：那会在 bot 传送**之前**执行 ⇒ 建出来的封闭墙会被随后的传送/落点自证
 *       对不上（同一个"顺序"坑，见编排器 `CheckHarness` 关于 `provision → scenes` 的注释）。</li>
 * </ul>
 *
 * <p>⚠️ **为什么本片必须自带"先传送"这一条前提**：编排器的顺序是 **provision → scenes** ——
 * 先把 bot 送到平台（顺带把区块**热起来**），再跑场景函数，`/fill` 才会**真的落地**；
 * 旧电池的顺序是"场景 → provision"，区块冷时 `/fill` 不落地 ⇒ 判据在虚空里**假绿** ✗（D-296 记过）。
 * 本片把这个顺序坑一并收口：模块自足 ⇒ `module:survival` 单跑应当与 CORE 同绿 ✓。
 */
public final class SurvivalModule implements CheckModule {

    @Override
    public String id() {
        return "survival";
    }

    @Override
    public String title() {
        return "维生（决策表 / 出口可达 / 脚位口径 / 掉血可见 / 逃生准备金上限）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("survival_exit", CheckProfile.BASELINE,
                        List.of("alice_test:survival_course"),
                        () -> to(bot, SurvivalCourseAnchor.PLATFORM_FOOT),
                        () -> new SurvivalExitCheckTask(bot, observer), 900));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
