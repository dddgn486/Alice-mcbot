package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.ProtectionZoneCheckTask;
import com.dddgn.alice.task.SafeReturnCheckTask;
import com.dddgn.alice.task.TaskZoneCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **保护区模块**（设计线保护区的第一个离线门禁，D-313，2026-09-18）。
 *
 * <p>不是 R-2 的搬迁片 —— 这是**新能力**的判据：`SafeZoneData` 从"水平圆形半径"改成
 * **区块级 2D 认领**（忽略 Y、覆盖全高度；D-305 ①′）之后，"认领的区块不许动、没认领就放行、
 * 旧数据不静默丢"这三件事必须**离线可复现**。
 *
 * <p>档位 = **MAIN**（进 CORE）：按 `BATTERY_CURATION.md` 规则 1「新能力默认进 MAIN」，且这条判据是
 * **安全相关**（认领区块里的一切破坏都会被拒）⇒ 值得每轮 CORE 都覆盖。代价≈0：纯查询、不写方块、
 * 不传送、不生成区块，约 10 tick。⚠️ 它是**追加在步表末尾**的 ⇒ 既有 48 步的次序一个格子都不动
 * （D-309 的口径：新增不许让既有步位移；已用两份 CORE 日志的步序 diff 作证）。
 *
 * <p>没有场景、没有 provision：夹具只做**查询与数据**（认领/取消/存读/迁移/黑名单），
 * 既不需要地形也不需要区块热 —— 也因此它单跑与在电池里跑**行为一致**。
 */
public final class ProtectionModule implements CheckModule {

    @Override
    public String id() {
        return "protection";
    }

    @Override
    public String title() {
        return "保护区（区块级认领 + 全高度 + 迁移 + 黑名单回归）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        BotPlayer bot = ctx.bot();
        var observer = ctx.observer();
        return List.of(
                CheckStep.of("protection_zones", CheckProfile.MAIN, List.of(), null,
                        () -> new ProtectionZoneCheckTask(bot, observer), 200),
                // D-327 机制 B（2026-09-19）：**任务失败后回安全区的兜底** —— EXTRA（走 3 段路 + 封盒，
                // 耗时较长且会临时认领/声明/还原区域 ⇒ 不进 CORE；改世界的部分夹具自己还原 ✓）。
                // `D-338` ③（2026-09-19）加了两段路（安全区内部 / 保护区内部）+ 单区块退化 ⇒ 预算 2200 → 3600。
                CheckStep.of("safe_return", CheckProfile.EXTRA, List.of(), null,
                        () -> new SafeReturnCheckTask(bot, observer), 3600),
                // `§5.12` 第 4 件的"几何 + 锁定"层（2026-09-19，`D-338` 附注二/附注四）：
                // 工作区域（方块级）⇒ 任务区（区块级最小覆盖）+ 覆盖规则（可覆盖保护区父类、
                // 不得覆盖安全区 ⇒ 报错且不裁剪）+ 随 scopeId 生灭 + **真跑一次 RegionLumberJob**。
                // EXTRA：会临时认领/声明几个孤立区块（收尾按增量还原）并跑一个真实 Job。
                CheckStep.of("task_zone", CheckProfile.EXTRA, List.of(), null,
                        () -> new TaskZoneCheckTask(bot, observer, ctx.scope()), 600));
    }
}
