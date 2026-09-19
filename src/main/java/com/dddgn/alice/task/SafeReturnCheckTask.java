package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ **机制 B 门禁**（`D-327` ①）：电池步 `safe_return`（EXTRA）—— "任务失败后回安全区"的兜底。
 *
 * <p><b>三个用例</b>：
 * <ol>
 *   <li>{@code NO_ZONE}：世界**没有任何认领区** ⇒ 必须**立刻**如实失败 `return_no_safe_zone`
 *       （"没有安全区"不是"到不了"，也**不许**退化成站定不动之外的行为）；</li>
 *   <li>{@code RETURN}：认领一块区、把 bot 放到 **200 格外的区外** ⇒ 必须**真的走回去**
 *       （`DONE` + 脚位落在认领区块里），且**段数 ≥ 2**（证明是"一跳一跳逼近"而不是一次搜到底）；</li>
 *   <li>{@code SEALED}：把 bot 关在**封死的 1×1 格**里、安全区在 240 格外 ⇒ 必须如实失败
 *       `return_unreachable:*` 且 bot **原地不动**（用户口径的**临时**兜底 = 站定不动；
 *       "就地固守"作为行为**不做**）。</li>
 * </ol>
 *
 * <p><b>自复位</b>：夹具自己传送 bot、自己认领/取消认领、自己还原封盒方块（`overwritten`），
 * 结束时把 bot 送回安全区中心 —— 否则后续步会在一个封死的格子里开跑。
 */
public final class SafeReturnCheckTask implements Task {

    /** 安全区中心（专用孤立点，远离其它场景与基准）。 */
    private static final BlockPos ZONE_CENTER = new BlockPos(3000, -60, 4000);

    /** 区外起点：往 −X 走 200 格（跨到别的区块，离认领区足够远）。 */
    private static final BlockPos OUTSIDE = ZONE_CENTER.offset(-200, 0, 0);

    /** 封死盒子的位置：往 +Z 走 240 格（区外）。 */
    private static final BlockPos SEAL = ZONE_CENTER.offset(0, 0, 240);

    private static final int SETTLE_TICKS = 30;
    private static final int RETURN_TICK_CAP = 1500;
    private static final int BUDGET_TICKS = 2200;

    private enum Phase { PREPARE, NO_ZONE, CLAIM, RETURN, SEAL_BUILD, SEALED, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> overwritten = new HashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int checks;
    private int settle;
    private boolean done;
    private boolean claimed;
    private SafeReturnTask task;
    private BlockPos outsideStart;
    private BlockPos sealFoot;
    private int taskStartTick;

    public SafeReturnCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SafeReturnCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ZONE_CENTER);
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("门禁必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        switch (phase) {
            case PREPARE -> {
                if (settle == 0) {
                    teleport(bot, OUTSIDE);
                    settle++;
                    return Task.Status.RUNNING;
                }
                if (settle++ <= SETTLE_TICKS) {
                    return Task.Status.RUNNING;
                }
                outsideStart = bot.blockPosition();
                check("前提：区外起点脚下必须有支撑（脚位=" + outsideStart.toShortString() + " 脚下="
                                + level.getBlockState(outsideStart.below()).getBlock().getName().getString() + "）",
                        !level.getBlockState(outsideStart.below()).isAir());
                check("前提：此刻世界**没有任何认领区**（否则 NO_ZONE 用例不成立；claims="
                                + SafeZoneData.get(level.getServer()).claims(level.dimension().location()).size() + "）",
                        SafeZoneData.get(level.getServer()).claims(level.dimension().location()).isEmpty());
                phase = Phase.NO_ZONE;
            }
            case NO_ZONE -> {
                if (task == null) {
                    task = new SafeReturnTask(bot);
                    taskStartTick = ticks;
                    return Task.Status.RUNNING;
                }
                Task.Status status = task.tick();
                if (status == Task.Status.RUNNING) {
                    return Task.Status.RUNNING;
                }
                findings.add("no_zone status=" + status + " reason=" + task.failureReason());
                BotLog.info("[SafeReturnDiag] no_zone status={} reason={}", status, task.failureReason());
                check("① 没有安全区 ⇒ 必须立刻如实失败 `return_no_safe_zone`（实际 status=" + status
                                + " reason=" + task.failureReason() + "）",
                        status == Task.Status.FAILED
                                && task.failureReason().startsWith("return_no_safe_zone"));
                check("① 没有安全区时**不许移动**（起点=" + outsideStart.toShortString() + " 现位="
                                + bot.blockPosition().toShortString() + "）",
                        bot.blockPosition().distManhattan(outsideStart) <= 1);
                check("① 判决：**没有认领区 ⇒ 不启动兜底**（`shouldStart=false`，"
                                + "= 今天的行为一字不变）",
                        !SafeReturnTask.shouldStart(level, outsideStart));
                task = null;
                phase = Phase.CLAIM;
            }
            case CLAIM -> {
                SafeZoneData zones = SafeZoneData.get(level.getServer());
                claimed = zones.claim(level, ZONE_CENTER.getX() >> 4, ZONE_CENTER.getZ() >> 4);
                check("前提：认领安全区区块（" + (ZONE_CENTER.getX() >> 4) + "," + (ZONE_CENTER.getZ() >> 4)
                                + "）必须成功", claimed);
                check("前提：认领后该中心格**在区内**（isClaimed="
                                + zones.isClaimed(level, ZONE_CENTER) + "）",
                        zones.isClaimed(level, ZONE_CENTER));
                check("前提：区外起点**不在区内**（isClaimed=" + zones.isClaimed(level, outsideStart) + "）",
                        !zones.isClaimed(level, outsideStart));
                check("② 判决：区外 + 有认领区 ⇒ `shouldStart=true`（这就是接线看的那一条）",
                        SafeReturnTask.shouldStart(level, outsideStart));
                settle = 0;
                phase = Phase.RETURN;
            }
            case RETURN -> {
                if (task == null) {
                    teleport(bot, OUTSIDE);
                    if (settle++ <= SETTLE_TICKS) {
                        return Task.Status.RUNNING;
                    }
                    outsideStart = bot.blockPosition();
                    task = new SafeReturnTask(bot);
                    taskStartTick = ticks;
                    BotLog.info("[SafeReturnDiag] return 起走 from={} 安全区中心={} 距离={}",
                            outsideStart.toShortString(), ZONE_CENTER.toShortString(),
                            FarWalkTask.distanceXZ(outsideStart, ZONE_CENTER));
                    return Task.Status.RUNNING;
                }
                Task.Status status = task.tick();
                if (status == Task.Status.RUNNING) {
                    if (ticks - taskStartTick > RETURN_TICK_CAP) {
                        findings.add("return TIMEOUT ticks=" + (ticks - taskStartTick)
                                + " rounds=" + task.rounds());
                        check("② 返程必须在 " + RETURN_TICK_CAP + " tick 内走完（实际 "
                                + (ticks - taskStartTick) + "，rounds=" + task.rounds() + "）", false);
                        task = null;
                        phase = Phase.SEAL_BUILD;
                    }
                    return Task.Status.RUNNING;
                }
                BlockPos now = bot.blockPosition();
                int remaining = FarWalkTask.distanceXZ(now, ZONE_CENTER);
                boolean inside = SafeZoneData.get(level.getServer()).isClaimed(level, now);
                findings.add("return status=" + status + " rounds=" + task.rounds()
                        + " ticks=" + (ticks - taskStartTick) + " inside=" + inside
                        + " remaining=" + remaining + " from=" + outsideStart.toShortString()
                        + " to=" + now.toShortString() + " legs=[" + String.join("; ", task.legCurve()) + "]");
                BotLog.info("[SafeReturnDiag] return status={} rounds={} ticks={} inside={} remaining={} from={} to={}",
                        status, task.rounds(), ticks - taskStartTick, inside, remaining,
                        outsideStart.toShortString(), now.toShortString());
                check("② 200 格外的失败任务必须把 bot 送回**认领区块里**（status=" + status + " inside="
                                + inside + " remaining=" + remaining + " 起走距离="
                                + FarWalkTask.distanceXZ(outsideStart, ZONE_CENTER) + "）",
                        status == Task.Status.DONE && inside);
                check("② 返程必须**分段**（段数 ≥ 2 ⇒ 是「一跳一跳逼近」而不是一次搜到底；实际 rounds="
                                + task.rounds() + "）", task.rounds() >= 2);
                check("② 判决：已进区 ⇒ `shouldStart=false`（已经安全，不再兜底）",
                        !SafeReturnTask.shouldStart(level, now));
                task = null;
                phase = Phase.SEAL_BUILD;
            }
            case SEAL_BUILD -> {
                if (sealFoot == null) {
                    teleport(bot, SEAL);
                    sealFoot = bot.blockPosition();
                }
                // 2 格高的环形墙 = 关住（跳不出去、走不出去）；这是一次**夹具自建**的世界修改，
                // 结束时逐格还原（`overwritten`）
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        place(level, sealFoot.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                        place(level, sealFoot.offset(dx, 1, dz), Blocks.STONE.defaultBlockState());
                    }
                }
                settle = 0;
                phase = Phase.SEALED;
            }
            case SEALED -> {
                if (task == null) {
                    if (settle++ <= SETTLE_TICKS) {
                        return Task.Status.RUNNING;
                    }
                    sealFoot = bot.blockPosition();
                    task = new SafeReturnTask(bot);
                    taskStartTick = ticks;
                    BotLog.info("[SafeReturnDiag] sealed 起走 from={} 安全区中心={} 距离={}",
                            sealFoot.toShortString(), ZONE_CENTER.toShortString(),
                            FarWalkTask.distanceXZ(sealFoot, ZONE_CENTER));
                    return Task.Status.RUNNING;
                }
                Task.Status status = task.tick();
                if (status == Task.Status.RUNNING) {
                    if (ticks - taskStartTick > 400) {
                        check("③ 封死格里的返程必须**很快**如实失败（400 tick 内；实际 "
                                + (ticks - taskStartTick) + "）", false);
                        task = null;
                        phase = Phase.CLEANUP;
                    }
                    return Task.Status.RUNNING;
                }
                findings.add("sealed status=" + status + " reason=" + task.failureReason()
                        + " ticks=" + (ticks - taskStartTick));
                BotLog.info("[SafeReturnDiag] sealed status={} reason={} ticks={} pos={}",
                        status, task.failureReason(), ticks - taskStartTick,
                        bot.blockPosition().toShortString());
                check("③ 走不到的返程必须**如实失败** `return_unreachable:*`（实际 status=" + status
                                + " reason=" + task.failureReason() + "）",
                        status == Task.Status.FAILED
                                && task.failureReason().startsWith("return_unreachable"));
                check("③ 返程失败时 bot 必须**原地不动**（临时兜底 = 站定不动：起点="
                                + sealFoot.toShortString() + " 现位=" + bot.blockPosition().toShortString() + "）",
                        sealFoot != null && bot.blockPosition().distManhattan(sealFoot) <= 1);
                task = null;
                phase = Phase.CLEANUP;
            }
            case CLEANUP -> {
                overwritten.forEach((pos, state) -> level.setBlock(pos, state, 3));
                overwritten.clear();
                SafeZoneData zones = SafeZoneData.get(level.getServer());
                if (claimed) {
                    zones.unclaim(level, ZONE_CENTER.getX() >> 4, ZONE_CENTER.getZ() >> 4);
                    claimed = false;
                }
                check("自复位：取消认领后该维度**没有认领区**（claims="
                                + zones.claims(level.dimension().location()).size() + "）",
                        zones.claims(level.dimension().location()).isEmpty());
                teleport(bot, ZONE_CENTER);   // 传送自带 stopMovement
                phase = Phase.DONE;
                return finish();
            }
            default -> {
                return finish();
            }
        }
        return Task.Status.RUNNING;
    }

    /** 记原状再写（夹具的世界修改必须可逆）。 */
    private void place(ServerLevel level, BlockPos pos, BlockState state) {
        overwritten.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, state, 3);
    }

    private void teleport(ServerPlayer who, BlockPos foot) {
        who.teleportTo(who.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), who.getYRot(), who.getXRot());
        who.setDeltaMovement(Vec3.ZERO);
        if (who instanceof BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();
        }
    }

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[SafeReturnDiag] SUMMARY checks={} failures={} → {}｜{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", findings);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 回安全区门禁 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
