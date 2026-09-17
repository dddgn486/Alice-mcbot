package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.BotWorldData;
import com.dddgn.alice.log.BotLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **死亡机制第 1 步判据（D-276，2026-09-17 用户裁定「不能直接删除数据」）**。
 *
 * <p>要证明的事实（旧行为是**反的**）：存档里血量为 0 / 带倒下标记的 bot，**不许**被当作"清掉"；
 * 必须读成 {@code FALLEN}（数据保留），并且倒下态里要留下**位置 / 死因 / 时刻**。
 * 旧行为原话（`BotManager.restoreFromWorld`）："存档假人已死亡 (health=…)，跳过恢复并清除存档"
 * —— 那正是用户说的"直接删除数据 ⇒ 死了进度归零"。
 *
 * <p>**为什么这是判别性的**：把 {@code restoreDecisionFor} 改回旧逻辑（`health<=0 ⇒ DISCARD`）⇒ ③ 必红。
 *
 * <p>⚠️ **本夹具不杀 bot**：它只验证"**恢复决策** + **倒下态内容**"这两件导致"删数据"的事。
 * 完整的"杀一次 + 重启服务端 + 记录仍在"属**端到端**验证，需要一次真机/重启轮次（已登记）。
 */
public class DeathPersistenceCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private boolean done;

    public DeathPersistenceCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "DeathPersistenceCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        BotWorldData data = BotWorldData.get(bot.getServer());
        CompoundTag previous = data.botTag();          // 收尾要还原，别把电池的世界数据改坏
        try {
            // ① 没有数据 ⇒ 无从恢复
            check("① 无存档数据 ⇒ DISCARD",
                    BotManager.restoreDecisionFor(null) == BotManager.RestoreDecision.DISCARD);

            // ② 活着的档案 ⇒ 正常复活
            BotManager.saveToWorld(bot);
            BotManager.RestoreDecision normal = BotManager.restoreDecisionFor(data.botTag());
            check("② 正常存档（血量>0）⇒ RESPAWN", normal == BotManager.RestoreDecision.RESPAWN);

            // ③ 倒下态 ⇒ **FALLEN（数据保留）**，不是 DISCARD（旧行为）
            CompoundTag fallen = BotManager.saveFallenState(bot, "fixture_death");
            BotManager.RestoreDecision decision = BotManager.restoreDecisionFor(data.botTag());
            check("③ 倒下态 ⇒ FALLEN（不许是 DISCARD = 旧行为『清除存档』）",
                    decision == BotManager.RestoreDecision.FALLEN);

            // ④ 倒下态必须留下**可用**的信息：死因 / 位置 / 时刻
            check("④ 倒下态记下死因（" + fallen.getString(BotManager.NBT_FALLEN_CAUSE) + "）",
                    "fixture_death".equals(fallen.getString(BotManager.NBT_FALLEN_CAUSE)));
            check("④ 倒下态记下位置（= bot 当前格）",
                    fallen.getLong(BotManager.NBT_FALLEN_AT) == bot.blockPosition().asLong());
            check("④ 倒下态记下时刻（tick>0：" + fallen.getLong(BotManager.NBT_FALLEN_TICK) + "）",
                    fallen.getLong(BotManager.NBT_FALLEN_TICK) > 0L);
            check("④ 倒下态血量记 0（身份/主手等其余数据仍在）", fallen.getFloat("Health") == 0.0F);
            check("④ 身份未丢（Name/UUID 仍在存档里）",
                    fallen.hasUUID("UUID") && !fallen.getString("Name").isBlank());

            // ⑤ 存档**没有被清掉**（数据保留 = 本步的全部意义）
            check("⑤ 存档仍在（world data 未被 clearBot）", data.botTag() != null
                    && data.botTag().getBoolean(BotManager.NBT_FALLEN));

            BotLog.info("[DeathPersistence] 观测：decision={} cause={} pos={} tick={} tagStillThere={}",
                    decision, fallen.getString(BotManager.NBT_FALLEN_CAUSE),
                    net.minecraft.core.BlockPos.of(fallen.getLong(BotManager.NBT_FALLEN_AT)).toShortString(),
                    fallen.getLong(BotManager.NBT_FALLEN_TICK), data.botTag() != null);
        } finally {
            // 收尾复位：把世界数据恢复到本夹具动它之前的样子
            if (previous == null) {
                data.clearBot();
            } else {
                data.setBot(previous);
            }
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[DeathPersistence] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 死亡数据保留自检 "
                    + (pass ? "PASS（倒下态 = 数据保留，不是清除）" : "FAIL " + failures)));
        }
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }
}
