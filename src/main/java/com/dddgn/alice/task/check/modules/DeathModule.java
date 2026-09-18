package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.DeathKillBotCheckTask;
import com.dddgn.alice.task.DeathPersistenceCheckTask;
import com.dddgn.alice.task.OreCourseAnchor;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * **死亡模块（R-2 第九片，2 步）**：`death_persistence`（MAIN）+ `death_kill_bot`（EXTRA）。
 *
 * <p>两步合起来才构成 D-276「死亡**不删数据**」的完整证据链：
 * <ul>
 *   <li>{@code death_persistence}：**恢复决策 + 倒下态内容**（`BotManager.restoreDecisionFor` 必须把
 *       "血量为 0 / 带倒下标记"读成 {@code FALLEN} 而**不是** {@code DISCARD}；倒下态里要留
 *       位置 / 死因 / 时刻）。⚠️ 它**不杀 bot**（夹具 javadoc 原话）；</li>
 *   <li>{@code death_kill_bot}：**端到端第 1 半** —— 真弄死一个**探针 bot**（`DeathKillBotCheckTask.PROBE_NAME`
 *       = `AliceE2E`，不动电池自己的 bot），断言实体被拆掉但存档记录**还在**且带 `AliceFallen` 与死因。
 *       落盘那半由 `ALICE_SAVE_ON_HALT=1` 的那一轮 + `tools/death-persistence-e2e.sh`（重启后读存档）完成。</li>
 * </ul>
 *
 * <p>⚠️ **步序是刻意选的（别照"先杀后验"的直觉改）**：本模块的顺序是
 * **先 `death_persistence` 后 `death_kill_bot`** —— 理由不是语义，而是**保持 CORE 的步序逐字不变**：
 * 原电池里 `death_persistence`（MAIN，CORE 跑）在第 8 位、`death_kill_bot`（EXTRA，CORE 不跑）在第 4 位，
 * **两者之间隔着 `hazard_aversion_plan` + 整个 `decision` 模块 + `pathing` 模块**。
 * 一个模块只能落在**一个**位置上 ⇒ 想两边都"原位"是做不到的，于是取**代价最小的那一侧**：
 * <ul>
 *   <li>把模块插在 `death_persistence` 的原位 ⇒ **CORE 步序逐字不变** ✓（这正是能快速证明"行为等价"的那一档）；</li>
 *   <li>被挪动的只有 `death_kill_bot`（EXTRA ⇒ CORE 根本不跑；且它自己的注释写明
 *       "它会写倒下态存档 ⇒ **只适合 `single:` 单独跑**"）⇒ 影响面 = FULL 一轮。</li>
 * </ul>
 * ⚠️ 反过来做（模块插在第 4 位、步序"先杀后验"）会让 **`death_persistence` 在 CORE 里提前 3 个模块**，
 * 那是一次**未证明的顺序变更** —— D-302 的教训正是"顺序也是行为，且 CORE 会**蒙对**"。
 *
 * <p>**本模块自带前提**：两步都用场景 `alice_test:ore_course_terrain` + 传送到课程起点
 * （编排器序 = openScope → **provision** → **scenes** ⇒ 区块先热再 `/fill` ✓）。
 */
public final class DeathModule implements CheckModule {

    @Override
    public String id() {
        return "death";
    }

    @Override
    public String title() {
        return "死亡（数据保留判据 / 端到端真死一次）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        List<String> scene = List.of("alice_test:ore_course_terrain");
        return List.of(
                // ① 判据步（MAIN）：**原位**保留在电池第 8 位（CORE 序不变的关键）
                CheckStep.of("death_persistence", CheckProfile.MAIN, scene,
                        () -> to(bot, OreCourseAnchor.START_FOOT),
                        () -> new DeathPersistenceCheckTask(bot, observer), 60),
                // ② 端到端第 1 半（EXTRA）：真死一个探针 bot（不动电池自己的 bot）
                CheckStep.of("death_kill_bot", CheckProfile.EXTRA, scene,
                        () -> to(bot, OreCourseAnchor.START_FOOT),
                        () -> new DeathKillBotCheckTask(bot, observer), 80));
    }

    /** 传送到统一起点（与电池 `teleportBot` 逐字段一致 ✓；顺带起"先热区块再 fill"的作用 ✓）。 */
    private static void to(BotPlayer bot, BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
