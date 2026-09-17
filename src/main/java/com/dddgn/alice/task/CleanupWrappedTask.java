package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * **把诊断任务包一层：跑完必做收尾**（2026-09-17，CORE 实测事故的修法）。
 *
 * <p>**为什么要它**：`pillar_execute`（PILLAR 诊断）会真的**垫方块爬竖井**，而电池的 `endStep()` 只
 * **关账本作用域**、**不拆**已放置的临时方块（`RegressionBatteryTask.endStep` 只 warn "建拆同权未闭合"）。
 * 于是那些方块留在 `WorldModLedger.pendingForOwner(...)` 里 ⇒ 之后 `craft_table` 的
 * `no_world_write`（断言 `pendingForOwner` **为空**）与 `crafted_furnace`（断言背包里 **没有圆石**）**双双变红** ✗。
 * ⇒ 结论：**凡是在电池里驱动"会动世界"的诊断，都必须自己收尾**（夹具纪律：结束复位）。
 *
 * <p>收尾动作：① 拆掉**我方**临时方块（`setblock air` + `WorldModLedger.forget`）；
 * ② 复位 bot 库存（`FixtureToolKit.resetInventory`）；③ 抬一格并清零速度，避免下一步起步踩空。
 *
 * <p>判据：`fall_execute` / `pillar_execute` 之后 `pendingForOwner` 必须为空 —— 由 **CORE 里紧随其后的
 * `craft_table`/`craft_station*` 判据**间接锁死（它们跑红即说明收尾失效）。
 */
public class CleanupWrappedTask implements Task {

    private final Task inner;
    private final BotPlayer bot;
    private boolean innerDone;
    private boolean cleaned;
    private int cleanupTicks;

    public CleanupWrappedTask(Task inner, BotPlayer bot) {
        this.inner = inner;
        this.bot = bot;
    }

    @Override
    public String taskName() {
        return inner.taskName();
    }

    @Override
    public TaskTarget target() {
        return inner.target();
    }

    @Override
    public String failureReason() {
        return inner.failureReason();
    }

    @Override
    public String terminalReason() {
        return inner.terminalReason();
    }

    @Override
    public Status tick() {
        if (!innerDone) {
            Status status = inner.tick();
            if (status == Status.DONE || status == Status.FAILED) {
                innerDone = true;
                cleanup();
            }
            return Status.RUNNING;
        }
        if (cleanupTicks++ < 3) {
            return Status.RUNNING;   // 给拆方块/复位一点时间落定
        }
        return innerDone && cleaned ? Status.DONE : Status.FAILED;
    }

    private void cleanup() {
        var server = bot.serverLevel().getServer();
        var level = bot.serverLevel();
        int removed = 0;
        for (WorldModLedger.Entry entry : WorldModLedger.pendingForOwner(server, bot.getUUID())) {
            BlockPos pos = entry.pos();
            if (pos.equals(bot.blockPosition()) || pos.equals(bot.blockPosition().above())) {
                continue;   // 不拆自己所在格/头位格
            }
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            WorldModLedger.forget(level, pos);
            removed++;
        }
        FixtureToolKit.resetInventory(bot);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.teleportTo(level, bot.getX(), bot.getY() + 1.0D, bot.getZ(),
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        cleaned = true;
        BotLog.info("[Cleanup] 诊断收尾：拆我方临时方块={} 库存已复位（wrapped={}）", removed, inner.taskName());
    }
}
