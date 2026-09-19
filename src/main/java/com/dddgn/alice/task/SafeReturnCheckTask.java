package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.ReturnPointData;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ **机制 B 门禁**（`D-327` ① + `D-338` ③）：电池步 `safe_return`（EXTRA）——
 * "任务失败后回安全区"的兜底，含**返程目标优先级链**。
 *
 * <p><b>五组用例（`D-338` ③ 把口径从"进认领区块即到"改成优先级链之后）</b>：
 * <ol>
 *   <li>{@code NO_ZONE}：世界**没有任何认领区** ⇒ 必须**立刻**如实失败 `return_no_safe_zone`
 *       （"没有安全区"不是"到不了"，也**不许**退化成站定不动之外的行为）；判决 `shouldStart=false`；</li>
 *   <li>{@code SAFE_RUN}：认领 **5×5 保护区** + 在其中声明 **3×3 安全区**，把 bot 放到 **200 格外的区外**
 *       ⇒ 必须走到**安全区的内部区块**（3×3 的内部区块恰好 = 中心 1 个）—— 不是"停在保护区边界"，
 *       也不是"停在安全区边界"；判决 `shouldStart=false`（已在区内）；</li>
 *   <li>{@code PROT_RUN}：**取消安全区声明**（保留 5×5 保护区）⇒ 目标降级为**保护区的内部区块**
 *       （5×5 的内部区块 = 中央 3×3）⇒ 必须走到其中离起点最近的区块（≈ 向西那一个），
 *       且脚位**不在**安全区里（证明优先级链真的换了区）；</li>
 *   <li>{@code SINGLE_RUN}：只认领**1 个区块**（内部区块为空 = **退化**）⇒ 到达判据回到"进区即到"，
 *       脚位落在该区块内即 DONE（"内部区块为空"是自适应口径的**正常退化**，不是缺陷）；</li>
 *   <li>{@code SEALED}：把 bot 关在**封死的 1×1 格**里、区在 240 格外 ⇒ 必须如实失败
 *       `return_unreachable:*` 且 bot **原地不动**（用户口径的**临时**兜底 = 站定不动；
 *       "就地固守"作为行为**不做**）。</li>
 * </ol>
 *
 * <p><b>关键的判别性断言</b>（`D-338` 附注一"内部区块"落地前**必红**）：②③ 两例要求脚位落在
 * {@code internalSafeClaims} / {@code internalClaims} 里 —— 旧实现（"最近认领格 + 进区块即到"）
 * 会停在保护区的**最外圈**（chunk 185），离内部区块还差一个区块 ⇒ 立刻判红。
 *
 * <p><b>判决矩阵</b>（`shouldStart` 是接线看的那一条）：区外 + 有区 ⇒ true；已在**到达集**里 ⇒ false；
 * **在保护区里但不在安全区里、而世界有安全区** ⇒ true（`D-338` ③"有安全区就回安全区"）。
 *
 * <p><b>自复位</b>：夹具自己传送 bot、自己认领/声明/取消、自己还原封盒方块（`overwritten`），
 * 结束时把 bot 送回区中心并断言**认领与安全区计数都回到进入前** —— 否则后续步会在一个封死的格子里开跑。
 */
public final class SafeReturnCheckTask implements Task {

    /** 区中心（专用孤立点，远离其它场景与基准）：块 (3000,-60,4000) = 区块 (187,250)。 */
    private static final BlockPos ZONE_CENTER = new BlockPos(3000, -60, 4000);

    /** 保护区 = 中心 ± 2 ⇒ **5×5 区块**（内部区块 = 中央 3×3）。 */
    private static final int PROT_HALF = 2;

    /** 安全区 = 中心 ± 1 ⇒ **3×3 区块**（内部区块恰好 = 中心 1 个）。 */
    private static final int SAFE_HALF = 1;

    /** 区外起点：往 −X 走 200 格（跨到别的区块，离区足够远）。 */
    private static final BlockPos OUTSIDE = ZONE_CENTER.offset(-200, 0, 0);

    /** 保护区用例起点：保护区西边界（块 2960）再往西 60 格 ⇒ 只走 ~76 格就到内部区块。 */
    private static final BlockPos PROT_START = ZONE_CENTER.offset(-100, 0, 0);

    /** 单区块用例起点：该区块西边界（块 2992）再往西 60 格。 */
    private static final BlockPos SINGLE_START = ZONE_CENTER.offset(-68, 0, 0);

    /** 保护区里但**不在**安全区里的位置（区块 185,248 ⇒ 5×5 有、3×3 无）——判决矩阵用。 */
    private static final BlockPos PROT_ONLY = new BlockPos(2960, -60, 3968);

    /** 封死盒子的位置：往 +Z 走 240 格（区外）。 */
    private static final BlockPos SEAL = ZONE_CENTER.offset(0, 0, 240);

    /**
     * ⭐ **归位点**（`D-338` 附注四①）：故意放在**两个区之外**（区块 183,254）——
     * 于是"归位点优先"与"区几何"会走到**完全不同的地方**（区几何会去 2992,4000 / 2976,4000）⇒
     * 判别性判据（忽略归位点的实现必然红）。
     */
    private static final BlockPos HOME = ZONE_CENTER.offset(-64, 0, 64);

    /** 归位点用例起点：再往西 60 格（快）。 */
    private static final BlockPos HOME_START = HOME.offset(-60, 0, 0);

    private static final int SETTLE_TICKS = 30;
    private static final int SAFE_TICK_CAP = 1600;
    private static final int PROT_TICK_CAP = 900;
    private static final int SINGLE_TICK_CAP = 600;
    private static final int HOME_TICK_CAP = 900;
    private static final int BUDGET_TICKS = 3400;

    private enum Phase { PREPARE, NO_ZONE, DECLARE, HOME_SET, HOME_RUN, SAFE_RUN, DEGRADE, PROT_RUN,
        SINGLE, SINGLE_RUN, SEAL_BUILD, SEALED, CLEANUP, DONE }

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
    private boolean timedOut;
    private int safeDeclaredBefore;
    private int chunksBefore;
    private int homesBefore;
    private BlockPos homeCell;

    private SafeReturnTask task;
    private BlockPos outsideStart;
    private BlockPos caseStartFoot;
    private int caseStartTick;
    private BlockPos sealFoot;

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
        SafeZoneData zones = SafeZoneData.get(level.getServer());
        switch (phase) {
            case PREPARE -> {
                if (settle == 0) {
                    // 进入前的现场（收尾要**按增量**还原 ⇒ 真实存档里也能跑）
                    safeDeclaredBefore = zones.safeChunkCount();
                    chunksBefore = zones.claimedChunkCount();
                    homesBefore = ReturnPointData.get(level.getServer()).count();
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
                                + zones.claims(level.dimension().location()).size() + "）",
                        zones.claims(level.dimension().location()).isEmpty());
                check("前提：此刻世界**没有任何安全区声明**（否则 NO_ZONE 用例不成立；safe="
                                + zones.safeChunkCount() + "）", zones.safeChunkCount() == 0);
                phase = Phase.NO_ZONE;
            }
            case NO_ZONE -> {
                if (task == null) {
                    task = new SafeReturnTask(bot);
                    caseStartTick = ticks;
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
                        !SafeReturnTask.shouldStart(level, bot.getUUID(), outsideStart));
                task = null;
                phase = Phase.DECLARE;
            }
            case DECLARE -> {
                int centreChunkX = ZONE_CENTER.getX() >> 4;
                int centreChunkZ = ZONE_CENTER.getZ() >> 4;
                int claimed = ring(zones, level, centreChunkX, centreChunkZ, PROT_HALF, true);
                check("前提：认领保护区 5×5 = 25 个区块（实际新增 " + claimed + "）", claimed == 25);
                int declared = ringSafe(zones, level, centreChunkX, centreChunkZ, SAFE_HALF, true);
                check("前提：在保护区内声明安全区 3×3 = 9 个区块（实际新增 " + declared + "）", declared == 9);
                check("前提：**安全区 ⊆ 保护区**（安全区块数 " + zones.safeChunkCount()
                                + " ≤ 认领区块数 " + zones.claimedChunkCount() + "）",
                        zones.safeChunkCount() <= zones.claimedChunkCount());
                check("前提：3×3 安全区的**内部区块恰好 = 中心 1 个**（实际 "
                                + describeChunks(zones.internalSafeClaims(level.dimension().location()))
                                + "）",
                        zones.internalSafeClaims(level.dimension().location())
                                .equals(java.util.Set.of(ChunkPos.asLong(centreChunkX, centreChunkZ))));
                check("前提：5×5 保护区的**内部区块 = 中央 3×3**（实际 "
                                + describeChunks(zones.internalClaims(level.dimension().location())) + "）",
                        zones.internalClaims(level.dimension().location()).size() == 9);
                check("前提：区外起点既不在保护区也不在安全区",
                        !zones.isClaimed(level, outsideStart) && !zones.isSafe(level, outsideStart));
                // 判决矩阵（接线看的就这一条）
                check("② 判决：区外 + 有区 ⇒ `shouldStart=true`", SafeReturnTask.shouldStart(level, bot.getUUID(), outsideStart));
                check("② 判决：已在**安全区内部**（到达到达集里）⇒ `shouldStart=false`"
                                + "（已安全，不再兜底）",
                        !SafeReturnTask.shouldStart(level, bot.getUUID(), ZONE_CENTER));
                check("⭐ 判决（`D-338` ③ 优先级链）：**在保护区里、但不在安全区里**、而世界有安全区 ⇒ "
                                + "仍然 `shouldStart=true`（有安全区就回安全区；实际 isClaimed="
                                + zones.isClaimed(level, PROT_ONLY) + " isSafe=" + zones.isSafe(level, PROT_ONLY)
                                + " shouldStart=" + SafeReturnTask.shouldStart(level, bot.getUUID(), PROT_ONLY) + "）",
                        zones.isClaimed(level, PROT_ONLY) && !zones.isSafe(level, PROT_ONLY)
                                && SafeReturnTask.shouldStart(level, bot.getUUID(), PROT_ONLY));
                check("前提：此刻**没有**归位点（本用例自己设定与清除；count="
                                + ReturnPointData.get(level.getServer()).count() + " = " + homesBefore + "）",
                        ReturnPointData.get(level.getServer()).count() == homesBefore);
                settle = 0;
                phase = Phase.HOME_SET;
            }
            case HOME_SET -> {
                // 命令的作用对象是"执行者站位" ⇒ 让 bot 站在归位点上再敲命令（零参数入口的真实走法）
                teleport(bot, HOME);
                var server = level.getServer();
                var source = server.createCommandSourceStack().withEntity(bot).withPosition(bot.position())
                        .withSuppressedOutput();
                int setCode = server.getCommands().performPrefixedCommand(source, "alice bot-home set");
                ReturnPointData homes = ReturnPointData.get(level.getServer());
                ReturnPointData.Point point = homes.get(bot.getUUID());
                homeCell = point == null ? HOME : point.pos();
                check("⭐ 命令入口：`alice bot-home set` 成功（返回 " + setCode + "）且归位点 = **执行者站位**"
                                + "（实际 " + (point == null ? "无" : point.describe()) + "，站位 "
                                + bot.blockPosition().toShortString() + "）",
                        setCode == 1 && point != null && point.pos().equals(bot.blockPosition()));
                check("⭐ 前提：归位点**在两个区之外**（否则「归位点优先」与「区几何」分不开；isClaimed="
                                + zones.isClaimed(level, homeCell) + " isSafe=" + zones.isSafe(level, homeCell) + "）",
                        !zones.isClaimed(level, homeCell) && !zones.isSafe(level, homeCell));
                check("⭐ 判决：有归位点（同维度）⇒ `shouldStart=true` —— **即使世界里有安全区**"
                                + "（实际 " + SafeReturnTask.shouldStart(level, bot.getUUID(), OUTSIDE) + "）",
                        SafeReturnTask.shouldStart(level, bot.getUUID(), OUTSIDE));
                check("⭐ 判决：已在归位点半径内 ⇒ `shouldStart=false`（已经到位，不再兜底）",
                        !SafeReturnTask.shouldStart(level, bot.getUUID(), homeCell));
                // 维度不符 ⇒ 本维度忽略归位点（数据级断言，不传送）：先指向 the_nether 再问
                homes.set(bot.getUUID(), ResourceLocation.parse("minecraft:the_nether"), homeCell,
                        ReturnPointData.DEFAULT_RADIUS);
                check("⭐ 判决：归位点在**别的维度** ⇒ 本维度**忽略**它、仍按区几何判断"
                                + "（区外 + 有区 ⇒ true）",
                        SafeReturnTask.shouldStart(level, bot.getUUID(), OUTSIDE));
                homes.set(bot.getUUID(), level.dimension().location(), homeCell,
                        ReturnPointData.DEFAULT_RADIUS);
                settle = 0;
                phase = Phase.HOME_RUN;
            }
            case HOME_RUN -> {
                Task.Status status = pumpReturn(level, homeCell.offset(-60, 0, 0), HOME_TICK_CAP, "归位点优先");
                if (status == null) {
                    return advanceAfterTimeout(Phase.SAFE_RUN);
                }
                BlockPos now = bot.blockPosition();
                double toHome = FarWalkTask.distanceXZ(now, homeCell);
                double toZone = FarWalkTask.distanceXZ(now, ZONE_CENTER);
                findings.add("home status=" + status + " rounds=" + task.rounds()
                        + " ticks=" + (ticks - caseStartTick) + " from=" + caseStartFoot.toShortString()
                        + " to=" + now.toShortString() + " dHome=" + toHome + " dZone=" + toZone
                        + " legs=[" + String.join("; ", task.legCurve()) + "]");
                BotLog.info("[SafeReturnDiag] home status={} rounds={} ticks={} dHome={} dZone={} to={}",
                        status, task.rounds(), ticks - caseStartTick, toHome, toZone, now.toShortString());
                check("⭐ **归位点优先**：必须走到**归位点半径内**（status=" + status + " 脚位="
                                + now.toShortString() + " 距归位点=" + toHome + " ≤ r="
                                + ReturnPointData.DEFAULT_RADIUS + "）",
                        status == Task.Status.DONE && toHome <= ReturnPointData.DEFAULT_RADIUS);
                check("⭐ 且**没有**跑回区里（区几何本来可用：到达集 "
                                + zones.returnArrivalChunks(level.dimension().location()).size()
                                + " 个区块；距区中心=" + toZone + " ⇒ 必须仍远）",
                        toZone > 30 && !zones.isInReturnZone(level, now));
                task = null;
                var server = level.getServer();
                var source = server.createCommandSourceStack().withEntity(bot).withPosition(bot.position())
                        .withSuppressedOutput();
                int clearCode = server.getCommands().performPrefixedCommand(source, "alice bot-home clear");
                check("⭐ 命令入口：`alice bot-home clear` 成功（返回 " + clearCode + "）且归位点已清"
                                + "（count=" + ReturnPointData.get(level.getServer()).count() + "）",
                        clearCode == 1 && ReturnPointData.get(level.getServer()).get(bot.getUUID()) == null);
                check("⭐ 取消归位点后 ⇒ 判决回到区几何（区外 + 有区 ⇒ true）",
                        SafeReturnTask.shouldStart(level, bot.getUUID(), OUTSIDE));
                settle = 0;
                phase = Phase.SAFE_RUN;
            }
            case SAFE_RUN -> {
                Task.Status status = pumpReturn(level, OUTSIDE, SAFE_TICK_CAP, "有安全区");
                if (status == null) {
                    return advanceAfterTimeout(Phase.DEGRADE);
                }
                BlockPos now = bot.blockPosition();
                long here = ChunkPos.asLong(now.getX() >> 4, now.getZ() >> 4);
                boolean inSafeInternal = zones.internalSafeClaims(level.dimension().location()).contains(here);
                findings.add("safe status=" + status + " rounds=" + task.rounds()
                        + " ticks=" + (ticks - caseStartTick) + " from=" + caseStartFoot.toShortString()
                        + " to=" + now.toShortString() + " inSafeInternal=" + inSafeInternal
                        + " legs=[" + String.join("; ", task.legCurve()) + "]");
                BotLog.info("[SafeReturnDiag] safe status={} rounds={} ticks={} inSafeInternal={} from={} to={}",
                        status, task.rounds(), ticks - caseStartTick, inSafeInternal,
                        caseStartFoot.toShortString(), now.toShortString());
                check("② 200 格外的失败任务必须把 bot 送回**安全区的内部区块**（status=" + status
                                + " 脚位=" + now.toShortString() + " 其区块=" + chunkOf(now)
                                + " 在安全区内部集里=" + inSafeInternal + "；起走 200 格）",
                        status == Task.Status.DONE && inSafeInternal);
                check("② 到达时 `isSafe=true`（不是「停在保护区边界」；实际 " + zones.isSafe(level, now) + "）",
                        zones.isSafe(level, now));
                check("② 返程必须**分段**（段数 ≥ 2 ⇒ 是「一跳一跳逼近」而不是一次搜到底；实际 rounds="
                                + task.rounds() + "）", task.rounds() >= 2);
                check("② 判决：已进到达集 ⇒ `shouldStart=false`（已经安全，不再兜底）",
                        !SafeReturnTask.shouldStart(level, bot.getUUID(), now));
                task = null;
                phase = Phase.DEGRADE;
            }
            case DEGRADE -> {
                int centreChunkX = ZONE_CENTER.getX() >> 4;
                int centreChunkZ = ZONE_CENTER.getZ() >> 4;
                int removed = ringSafe(zones, level, centreChunkX, centreChunkZ, SAFE_HALF, false);
                check("③ 取消安全区声明（3×3 = 9 个，实际 " + removed + "）并**保留**保护区认领（claims="
                                + zones.claimedChunkCount() + "）",
                        removed == 9 && zones.safeChunkCount() == safeDeclaredBefore
                                && zones.claimedChunkCount() > 0);
                check("③ 前提：此刻**没有**安全区 ⇒ 目标降级为**保护区的内部区块**（内部集 "
                                + zones.internalClaims(level.dimension().location()).size()
                                + " 个；安全区内部集 "
                                + zones.internalSafeClaims(level.dimension().location()).size() + " 个）",
                        zones.safeChunkCount() == safeDeclaredBefore
                                && zones.internalSafeClaims(level.dimension().location()).isEmpty()
                                && zones.internalClaims(level.dimension().location()).size() == 9);
                settle = 0;
                phase = Phase.PROT_RUN;
            }
            case PROT_RUN -> {
                Task.Status status = pumpReturn(level, PROT_START, PROT_TICK_CAP, "只有保护区");
                if (status == null) {
                    return advanceAfterTimeout(Phase.SINGLE);
                }
                BlockPos now = bot.blockPosition();
                long here = ChunkPos.asLong(now.getX() >> 4, now.getZ() >> 4);
                boolean inProtInternal = zones.internalClaims(level.dimension().location()).contains(here);
                findings.add("prot status=" + status + " rounds=" + task.rounds()
                        + " ticks=" + (ticks - caseStartTick) + " from=" + caseStartFoot.toShortString()
                        + " to=" + now.toShortString() + " inProtInternal=" + inProtInternal
                        + " legs=[" + String.join("; ", task.legCurve()) + "]");
                BotLog.info("[SafeReturnDiag] prot status={} rounds={} ticks={} inProtInternal={} from={} to={}",
                        status, task.rounds(), ticks - caseStartTick, inProtInternal,
                        caseStartFoot.toShortString(), now.toShortString());
                check("③ 没有安全区时目标降到**保护区的内部区块**（status=" + status + " 脚位="
                                + now.toShortString() + " 其区块=" + chunkOf(now) + " 在保护区内收集里="
                                + inProtInternal + "）",
                        status == Task.Status.DONE && inProtInternal);
                check("③ 这一例脚位**不在**安全区里（该例没有安全区 ⇒ 证明优先级链真的换了区；实际 isSafe="
                                + zones.isSafe(level, now) + "）", !zones.isSafe(level, now));
                task = null;
                phase = Phase.SINGLE;
            }
            case SINGLE -> {
                int centreChunkX = ZONE_CENTER.getX() >> 4;
                int centreChunkZ = ZONE_CENTER.getZ() >> 4;
                ring(zones, level, centreChunkX, centreChunkZ, PROT_HALF, false);
                boolean single = zones.claim(level, centreChunkX, centreChunkZ);
                check("④ 前提：塌成**只认领中心 1 个区块**（实际认领 " + zones.claimedChunkCount()
                                + " 个，本次=" + single + "）", zones.claimedChunkCount() == 1);
                check("④ 前提：1 区块的区**没有内部区块**（退化口径；实际 "
                                + zones.internalClaims(level.dimension().location()).size() + "）",
                        zones.internalClaims(level.dimension().location()).isEmpty());
                settle = 0;
                phase = Phase.SINGLE_RUN;
            }
            case SINGLE_RUN -> {
                Task.Status status = pumpReturn(level, SINGLE_START, SINGLE_TICK_CAP, "单区块退化");
                if (status == null) {
                    return advanceAfterTimeout(Phase.SEAL_BUILD);
                }
                BlockPos now = bot.blockPosition();
                long here = ChunkPos.asLong(now.getX() >> 4, now.getZ() >> 4);
                findings.add("single status=" + status + " rounds=" + task.rounds()
                        + " ticks=" + (ticks - caseStartTick) + " from=" + caseStartFoot.toShortString()
                        + " to=" + now.toShortString() + " legs=[" + String.join("; ", task.legCurve()) + "]");
                BotLog.info("[SafeReturnDiag] single status={} rounds={} ticks={} from={} to={}",
                        status, task.rounds(), ticks - caseStartTick,
                        caseStartFoot.toShortString(), now.toShortString());
                check("④ **内部区块为空 ⇒ 退化「进区即到」**：脚位落在该区块内即 DONE（status=" + status
                                + " 脚位=" + now.toShortString() + " 其区块=" + chunkOf(now) + "）",
                        status == Task.Status.DONE
                                && here == ChunkPos.asLong(ZONE_CENTER.getX() >> 4, ZONE_CENTER.getZ() >> 4));
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
                Task.Status status = pumpReturn(level, SEAL, 400, "封死格");
                if (status == null) {
                    return advanceAfterTimeout(Phase.CLEANUP);
                }
                findings.add("sealed status=" + status + " reason=" + task.failureReason()
                        + " ticks=" + (ticks - caseStartTick));
                BotLog.info("[SafeReturnDiag] sealed status={} reason={} ticks={} pos={}",
                        status, task.failureReason(), ticks - caseStartTick,
                        bot.blockPosition().toShortString());
                check("⑤ 走不到的返程必须**如实失败** `return_unreachable:*`（实际 status=" + status
                                + " reason=" + task.failureReason() + "）",
                        status == Task.Status.FAILED
                                && task.failureReason().startsWith("return_unreachable"));
                check("⑤ 返程失败时 bot 必须**原地不动**（临时兜底 = 站定不动：起点="
                                + sealFoot.toShortString() + " 现位=" + bot.blockPosition().toShortString() + "）",
                        sealFoot != null && bot.blockPosition().distManhattan(sealFoot) <= 1);
                task = null;
                phase = Phase.CLEANUP;
            }
            case CLEANUP -> {
                overwritten.forEach((pos, state) -> level.setBlock(pos, state, 3));
                overwritten.clear();
                int centreChunkX = ZONE_CENTER.getX() >> 4;
                int centreChunkZ = ZONE_CENTER.getZ() >> 4;
                ringSafe(zones, level, centreChunkX, centreChunkZ, SAFE_HALF, false);
                ring(zones, level, centreChunkX, centreChunkZ, PROT_HALF, false);
                check("自复位：认领数回到进入前（" + zones.claimedChunkCount() + " = " + chunksBefore + "）",
                        zones.claimedChunkCount() == chunksBefore);
                check("自复位：安全区数回到进入前（" + zones.safeChunkCount() + " = " + safeDeclaredBefore + "）",
                        zones.safeChunkCount() == safeDeclaredBefore);
                ReturnPointData homes = ReturnPointData.get(level.getServer());
                homes.clear(bot.getUUID());
                check("自复位：归位点数回到进入前（" + homes.count() + " = " + homesBefore + "）",
                        homes.count() == homesBefore);
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

    // ==================== 用例驱动 ====================

    /**
     * 驱动一次返程用例：`null` = 还没结束（调用方 `return RUNNING`）；非 `null` = 任务的终态。
     * 超时按失败记账并置 {@link #timedOut}（调用方据此跳到下一例）。
     */
    private Task.Status pumpReturn(ServerLevel level, BlockPos start, int tickCap, String tag) {
        if (task == null) {
            teleport(bot, start);
            if (settle++ <= SETTLE_TICKS) {
                return null;
            }
            caseStartFoot = bot.blockPosition();
            task = new SafeReturnTask(bot);
            caseStartTick = ticks;
            BotLog.info("[SafeReturnDiag] {} 起走 from={} 区中心={} 距离={}", tag,
                    caseStartFoot.toShortString(), ZONE_CENTER.toShortString(),
                    FarWalkTask.distanceXZ(caseStartFoot, ZONE_CENTER));
            return null;
        }
        Task.Status status = task.tick();
        if (status == Task.Status.RUNNING) {
            if (ticks - caseStartTick > tickCap) {
                findings.add(tag + " TIMEOUT ticks=" + (ticks - caseStartTick) + " rounds=" + task.rounds());
                check("用例「" + tag + "」必须在 " + tickCap + " tick 内结束（实际超时；rounds="
                        + task.rounds() + "）", false);
                task = null;
                timedOut = true;
            }
            return null;
        }
        return status;
    }

    /** 超时 ⇒ 跳到下一例；正常收尾 ⇒ 什么都不做（调用方自己 `advance`）。 */
    private Task.Status advanceAfterTimeout(Phase next) {
        if (!timedOut) {
            return Task.Status.RUNNING;
        }
        timedOut = false;
        task = null;
        settle = 0;
        phase = next;
        return Task.Status.RUNNING;
    }

    // ==================== 工具 ====================

    /** 认领/取消一个 (2·half+1)² 的区块环；返回发生变化（新增/移除）的区块数。 */
    private static int ring(SafeZoneData zones, ServerLevel level, int centreChunkX, int centreChunkZ,
                            int half, boolean claim) {
        int changed = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                boolean did = claim
                        ? zones.claim(level, centreChunkX + dx, centreChunkZ + dz)
                        : zones.unclaim(level, centreChunkX + dx, centreChunkZ + dz);
                if (did) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** 声明/取消一个 (2·half+1)² 的安全区；返回发生变化的安全区块数。 */
    private static int ringSafe(SafeZoneData zones, ServerLevel level, int centreChunkX, int centreChunkZ,
                                int half, boolean declare) {
        int changed = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (declare) {
                    if (zones.declareSafe(level, centreChunkX + dx, centreChunkZ + dz)
                            == SafeZoneData.SafeDeclare.DECLARED) {
                        changed++;
                    }
                } else if (zones.clearSafe(level, centreChunkX + dx, centreChunkZ + dz)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static String chunkOf(BlockPos pos) {
        return (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
    }

    private static String describeChunks(java.util.Set<Long> chunks) {
        List<String> parts = new ArrayList<>();
        for (long key : chunks) {
            parts.add(ChunkPos.getX(key) + "," + ChunkPos.getZ(key));
        }
        return parts.toString();
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
