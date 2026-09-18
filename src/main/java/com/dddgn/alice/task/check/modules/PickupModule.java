package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.CollectJobItem;
import com.dddgn.alice.job.JobLauncher;
import com.dddgn.alice.job.JobRequest;
import com.dddgn.alice.task.LumberCourseAnchor;
import com.dddgn.alice.task.PickupGateCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * **掉落物模块（R-2 第十五片，2 步）**：`pickup_gate`(EXTRA) + `collect_job`(EXTRA) —— 都是 **EXTRA**。
 *
 * <ul>
 *   <li>{@code pickup_gate}：**掉落物归属闸门**（`DropPolicy.effectiveProvenance` 的那条线：
 *       我方 / `GRANTED_AREA` / `FOREIGN` 三档）；</li>
 *   <li>{@code collect_job}：**收集 Job 的最小闭环**，且**走统一入口**（`JobRequest` → `JobLauncher`）
 *       ⇒ 顺带覆盖 D-134 的"起任意 Job"路径。</li>
 * </ul>
 *
 * ⭐ **本片是"EXTRA 步进模块"的第二个受益者**：两步都是 EXTRA（CORE 不跑、只有 FULL 跑）⇒ 进模块后
 * `module:pickup` **会跑它们**，于是"掉落物这条线"第一次有了**单独跑**的通道 ✓。
 *
 * <p>**本模块自带前提**（照抄原电池，逐字段等价）：
 * <ol>
 *   <li>场景 = `alice_test:lumber_course_terrain`；</li>
 *   <li>传送 = `LumberCourseAnchor.START_FOOT`（放进 `provision` ⇒ 先热区块再 fill ✓）；</li>
 *   <li>⚠️ `collect_job` 的 provision **还要造掉落物**：3 个圆石实体（落在 `CollectJobItem.DROP_CENTER`
 *       附近）+ `scope.begin(...)` + `scope.adoptExistingDrops(...)`（**登记为我方**：走"安全默认"那条路，
 *       我方 AUTO 放行）⇒ 这两句**必须**留在 provision 里，否则收集 Job 起来时**没有可收的东西**（假绿）。</li>
 * </ol>
 */
public final class PickupModule implements CheckModule {

    @Override
    public String id() {
        return "pickup";
    }

    @Override
    public String title() {
        return "掉落物（归属闸门 / 收集 Job 最小闭环）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        var scope = ctx.scope();
        List<String> course = List.of("alice_test:lumber_course_terrain");
        return List.of(
                CheckStep.of("pickup_gate", CheckProfile.EXTRA, course,
                        () -> to(bot, LumberCourseAnchor.START_FOOT),
                        () -> new PickupGateCheckTask(bot, observer), 600),
                CheckStep.of("collect_job", CheckProfile.EXTRA, course,
                        () -> {
                            to(bot, LumberCourseAnchor.START_FOOT);
                            // 夹具造掉落物并**登记为我方**（走安全默认那条路：我方 AUTO 放行）
                            for (int i = 0; i < 3; i++) {
                                var drop = new ItemEntity(bot.serverLevel(),
                                        CollectJobItem.DROP_CENTER.getX() + 0.5D + i * 0.4D,
                                        CollectJobItem.DROP_CENTER.getY() + 0.5D,
                                        CollectJobItem.DROP_CENTER.getZ() + 0.5D,
                                        new ItemStack(Items.COBBLESTONE, 8));
                                drop.setDeltaMovement(Vec3.ZERO);
                                bot.serverLevel().addFreshEntity(drop);
                            }
                            scope.begin(CollectJobItem.DROP_CENTER, 12, bot.getUUID());
                            scope.adoptExistingDrops(bot.serverLevel(), CollectJobItem.DROP_CENTER, 12);
                        },
                        // **走统一入口**（JobRequest → JobLauncher）：顺带覆盖 D-134 的"起任意 Job"路径
                        () -> JobLauncher.create(bot, scope,
                                JobRequest.collect(CollectJobItem.DROP_CENTER, 16, 24, 600)),
                        800));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
