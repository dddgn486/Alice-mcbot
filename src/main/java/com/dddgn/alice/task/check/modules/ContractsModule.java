package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.DecisionContractCheckTask;
import com.dddgn.alice.task.DecisionTraceCheckTask;
import com.dddgn.alice.task.LumberCourseAnchor;
import com.dddgn.alice.task.OreCourseAnchor;
import com.dddgn.alice.task.SpeechChannelCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **决策契约模块（R-2 第十四片，3 步）**：`speech_channel`(MAIN) + `decision_contract`(MAIN)
 * + `decision_trace`(EXTRA)。
 *
 * <ul>
 *   <li>{@code speech_channel}：**F4 地基**（D-267）—— 说话通道**只出不进**：说话**不改变决策态**
 *       （否则文本会绕过 `GoalAction` 白名单直接进执行路径）；</li>
 *   <li>{@code decision_contract}：**决策层契约**（基-2 / D-149）—— 纯逻辑、不改世界、不调 LLM ⇒
 *       便宜且确定，任何改动都跑得到；</li>
 *   <li>{@code decision_trace}：**决策 trace 落盘 + 跨重启语义**（基-4：NBT 往返 / 只报一次）。</li>
 * </ul>
 *
 * <p>**本模块自带前提**（两步带场景、一步不带 —— 按步各自给，**不搞"一刀切"**）：
 * <ul>
 *   <li>`speech_channel`：场景 `alice_test:ore_course_terrain` + 传送到 `OreCourseAnchor.START_FOOT`；</li>
 *   <li>`decision_contract`：场景 `lumber_course_terrain` + `lumber_course_trees` + 传送到 `LumberCourseAnchor.START_FOOT`；</li>
 *   <li>`decision_trace`：`scenes=[] provision=null`（纯逻辑 + NBT 往返，不碰世界）。</li>
 * </ul>
 * ⚠️ 带场景的两步遵循同一条纪律：**传送放进 `provision`**（编排器序 = openScope → provision → scenes
 * ⇒ 区块先热再 `/fill` 才真的落地，D-296）。
 */
public final class ContractsModule implements CheckModule {

    @Override
    public String id() {
        return "contracts";
    }

    @Override
    public String title() {
        return "决策契约（说话通道只出不进 / 决策层契约 / trace 跨重启）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("speech_channel", CheckProfile.MAIN,
                        List.of("alice_test:ore_course_terrain"),
                        () -> to(bot, OreCourseAnchor.START_FOOT),
                        () -> new SpeechChannelCheckTask(bot, observer), 60),
                CheckStep.of("decision_contract", CheckProfile.MAIN,
                        List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                        () -> to(bot, LumberCourseAnchor.START_FOOT),
                        () -> new DecisionContractCheckTask(bot, observer), 200),
                CheckStep.of("decision_trace", CheckProfile.EXTRA, List.of(), null,
                        () -> new DecisionTraceCheckTask(bot, observer), 200));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
