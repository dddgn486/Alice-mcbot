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
                // ⭐ `D-377`（2026-09-21 真机）：**无任务的 bot 也必须被维生接管** —— 真机上"任务被延后停止
                // 清掉之后在水里沉底、掉血 20→1.0 而维生零动作"。用**第二个假人**（天然无任务）跑生产路径；
                // 判据 = 观察窗口内出现一次任务 + 头露出水面 + 空气回到 AIR_SAFE。自带水井场景，无需数据包。
                CheckStep.of("survival_idle_drown", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.SurvivalIdleDrownCheckTask(bot, ctx.observer()),
                        600),
                // ⭐ 用户 2026-09-21 第十二轮裁定：**浮起来之后要自己走上岸**（原版生物级；纯通行、零写权）。
                // 两个案例：① 8 格内有天然岸 ⇒ 先浮（`SurvivalFloatTask`）**再**走（`SurvivalExitTask`）
                // 且**世界零改动**；② 21×21 大水池（8 格内无落点）⇒ 照样浮起来，但**永远不起逃生任务、
                // 永远不上岸、零改动**（用户口径③：没有现成站位 ⇒ 如实失败，不自己造站位）。
                CheckStep.of("survival_shore_escape", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.SurvivalShoreEscapeCheckTask(bot, ctx.observer()),
                        1400),
                // ⭐ `D-383`（2026-09-21 真机第十二轮）：**逃生途中的空气告警** —— 真机那次逃生路线
                // 钻了水下 7 秒（`air 284→158`），而 `escapeTask=true` 时维生三条救援分支全被排除
                // ⇒ 逃生途中零动作。本步钉"告警真的动手"：眼在水里 + `air ≤ AIR_SAFE` ⇒ 按跳跃；
                // 空气回来 ⇒ 松开；干地上 ⇒ 不按（防兔子跳）。
                CheckStep.of("survival_escape_air", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.SurvivalEscapeAirCheckTask(bot, ctx.observer()),
                        600),
                // ⭐ B3（2026-09-21 用户裁定）：**有活动危险时，K-3 延后停止不许落地** —— 本链条的起点
                // 就是"延后停止在危险中落地 ⇒ 任务被清掉 ⇒ bot 没人管"。用第二个假人（夹具不能停自己）：
                // 密封盒 + 着火（软危险、无出口 ⇒ `HOLD_NO_EXIT`）+ 一次延后停止 ⇒ 危险中任务必须还在，
                // 灭火后必须落地。
                CheckStep.of("survival_stop_in_hazard", CheckProfile.EXTRA, List.of(), null,
                        () -> new com.dddgn.alice.task.SurvivalStopInHazardCheckTask(bot, ctx.observer()),
                        500),
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
