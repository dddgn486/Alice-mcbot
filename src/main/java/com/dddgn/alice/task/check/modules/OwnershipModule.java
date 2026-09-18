package com.dddgn.alice.task.check.modules;

import com.dddgn.alice.task.BotOwnershipCheckTask;
import com.dddgn.alice.task.check.CheckContext;
import com.dddgn.alice.task.check.CheckModule;
import com.dddgn.alice.task.check.CheckProfile;
import com.dddgn.alice.task.check.CheckStep;

import java.util.List;

/**
 * **假人归属模块**（D-319，2026-09-18）—— 新能力"创建者登记"的离线门禁。
 *
 * <p>为什么值得进 CORE：归属是**后面所有"继承创建者身份/权限"的地基**（FTB 队伍身份、将来的
 * 指挥权限都读它）⇒ 它错了会以"权限看起来生效了其实没有"的形式出现在很远的地方。
 * 而判据本身**极便宜**：纯数据 + 一次存档往返，不生成区块、不写方块、不传送（约 5 tick）。
 *
 * <p>档位 = **MAIN**（进 CORE）：按 `docs/BATTERY_CURATION.md` **规则 1**「新能力默认进 MAIN」。
 * ⚠️ 本步是**追加在步表末尾**的 ⇒ 既有步的次序一个格子都不动。
 */
public final class OwnershipModule implements CheckModule {

    @Override
    public String id() {
        return "ownership";
    }

    @Override
    public String title() {
        return "假人归属（创建者登记 / 认领单向 / 存档往返 / 老存档不猜）";
    }

    @Override
    public List<CheckStep> steps(CheckContext ctx) {
        return List.of(
                CheckStep.of("bot_ownership", CheckProfile.MAIN, List.of(), null,
                        () -> new BotOwnershipCheckTask(ctx.bot(), ctx.observer()), 120));
    }
}
