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
 * **D-276 端到端（第 1 半）**：真的**弄死一个 bot**，看它的数据是不是**落成倒下态**而不是被删。
 *
 * <p>为什么要单独一个步：夹具 `death_persistence` **不杀 bot**（只验证恢复决策与倒下态内容）⇒
 * "真死一次"这件事没人验证过。这里**另开一个探针 bot**（不动电池自己的 bot，否则电池没法继续），
 * 对它施加致命伤害，然后断言：
 * ① 实体确实被拆掉（不再在玩家列表里）；② 存档里那条记录**还在**且带 `AliceFallen` 与死因。
 *
 * <p>**落盘**是第二半：本步跑在 `ALICE_SAVE_ON_HALT=1` 的那一轮里 ⇒ 停机前会同步存档，
 * 于是 `tools/death-persistence-e2e.sh` 能从 `world/data/*.dat` 里读到 `AliceFallen`；
 * 再起一次服务端（`--reuse-world`）应当打出「存档假人处于**倒下态**」。
 */
public class DeathKillBotCheckTask implements Task {

    /** 探针名（e2e 脚本会在日志/存档里找它）。 */
    public static final String PROBE_NAME = "AliceE2E";

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private boolean done;

    public DeathKillBotCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "DeathKillBotCheck";
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
        var level = bot.serverLevel();
        BotPlayer probe = BotManager.spawn(level, bot.blockPosition(), PROBE_NAME);
        check("探针 bot 已生成（" + PROBE_NAME + "）", probe != null && !probe.isRemoved());
        if (probe != null) {
            // 非危险伤害源：避免维生系统在"我们还没看完"之前介入（D-271 实测过点火会被中断）
            probe.hurt(probe.damageSources().generic(), 1000.0F);
            boolean detached = probe.isRemoved()
                    || probe.getServer().getPlayerList().getPlayer(probe.getUUID()) == null;
            check("① 探针实体已被拆掉（死亡 ⇒ detach，不再在玩家列表）", detached);

            CompoundTag tag = BotWorldData.get(bot.getServer()).botTag();
            boolean saved = tag != null && tag.getBoolean(BotManager.NBT_FALLEN);
            check("② 存档里那条记录**还在**且带倒下标记（不是被 clearBot）", saved);
            if (saved) {
                String cause = tag.getString(BotManager.NBT_FALLEN_CAUSE);
                check("② 死因已记下（" + cause + "）", cause != null && !cause.isBlank());
                check("② 位置/时刻已记下（tick=" + tag.getLong(BotManager.NBT_FALLEN_TICK) + "）",
                        tag.getLong(BotManager.NBT_FALLEN_TICK) > 0L);
                check("② 恢复决策是 FALLEN（不是 DISCARD）",
                        BotManager.restoreDecisionFor(tag) == BotManager.RestoreDecision.FALLEN);
                BotLog.info("[DeathE2E] 探针死亡已落成倒下态：cause={} tick={} fallen={}",
                        cause, tag.getLong(BotManager.NBT_FALLEN_TICK), tag.getBoolean(BotManager.NBT_FALLEN));
            }
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[DeathE2E] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 死亡落盘自检（第 1 半）"
                    + (pass ? "PASS" : "FAIL " + failures)));
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
