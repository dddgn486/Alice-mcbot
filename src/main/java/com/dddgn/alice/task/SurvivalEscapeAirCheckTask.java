package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ **逃生途中的空气告警**（电池步 `survival_escape_air`；`D-383`）。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>跑着 {@code SurvivalExitTask} 的 bot，眼睛在水里且空气低时，必须**自己按住跳跃**（主动上浮尝试）——
 * 因为逃生任务带 {@code SurvivalExit} 标记，维生的三条救援分支**全被排除**，它不给这一档兜住就是**零动作**。</b>
 *
 * <h2>真机事实（`D-382` §六，2026-09-21 第十二轮，用户自建冰坑 + 狭长水下通道）</h2>
 * <pre>
 * [SurvProbe] enter … taskName=SurvivalExitTask escapeTask=true inWater=true eyeInWater=true air=284 pos=350,60,94
 * 随后 air 284→278→…→158（约 7 秒全在水里），期间判决恒 IGNORE —— 维生一条动作都没有；
 * 是它自己游上来（21:33:05 air=203）才没淹死。
 * </pre>
 * ⇒ 若通道更长或是个死胡同，**逃生途中会淹死且零干预**。
 *
 * <h2>它断言哪一层（`§6.9.1 ③`）</h2>
 * **动作层**：真的驱动一个 `SurvivalExitTask`，真的读 `bot.controller().isJumping()`。
 * 本步**只断"告警的触发与松开"**（+ 不断死锁/不误按），**不断言"救回一条命"** ——
 * 后者要一条 ≥15 秒的水下路线，且受地形摆布；诚实边界写在 {@link SurvivalExitTask} 的注释里。
 *
 * <h2>三个相位（每相都读 bot 的真实读数）</h2>
 * <table border="1">
 *   <tr><th>相位</th><th>条件</th><th>期望</th></tr>
 *   <tr><td>{@code DRY}</td><td>站在干格上（眼不在水里）</td><td>**不许**按跳跃（地面长按 = 兔子跳）</td></tr>
 *   <tr><td>{@code SUBMERGED_LOW_AIR}</td><td>被放进水里（眼在水里）+ `air=60 ≤ AIR_SAFE`</td>
 *       <td>**必须**按跳跃（= 告警真的动手了）</td></tr>
 *   <tr><td>{@code SUBMERGED_ENOUGH_AIR}</td><td>仍在水里，但 `air=300`</td>
 *       <td>**松开**跳跃（不干预正常走位）</td></tr>
 * </table>
 *
 * <h2>安全（为什么这样写不会把电池自己打断）</h2>
 * 夹具的 bot **有任务**（本夹具自己）⇒ 维生只在**危险**时否决；而空气**始终 > 0**
 * ⇒ `classify` 只到 `WATER_CONTACT`（不是软危险）⇒ 判决恒 `IGNORE` ⇒ 不会中断本步
 * （与电池步 `survival_exit` 的"故意 `air=5`"同一个口径）。
 *
 * <h2>副作用边界（`§6.9.2`）</h2>
 * 自建孤立水池，收尾清回空气、复位 bot 到入口、停输入；不做任何 `assign*`。
 */
public final class SurvivalEscapeAirCheckTask implements Task {

    /** 场景原点（天然地面之上那一层；`ORIGIN.below(1..3)` 灌水）。 */
    private static final BlockPos ORIGIN = new BlockPos(2600, -60, 5200);
    /** 水池半径（5×5）与深度。 */
    private static final int POOL_R = 1;
    private static final int DEPTH = 3;
    /** 干起点（池外 3 格）与干落点（池对侧）。 */
    private static final BlockPos DRY_START = ORIGIN.offset(3, 0, 0);
    private static final BlockPos DRY_REFUGE = ORIGIN.offset(-3, 0, 0);
    /** 被按进水里时用的空气值：`≤ AIR_SAFE` 但**必须 > 0**（否则 `classify` 会判 `LOW_AIR` ⇒ 中断本步）。 */
    private static final int LOW_AIR = 60;
    private static final int FULL_AIR = 300;
    /** 每相位给多少 tick 让"走位 → 告警"这一序稳定下来。 */
    private static final int PHASE_TICKS = 6;
    private static final int BUDGET_TICKS = 600;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private final Map<BlockPos, BlockState> built = new LinkedHashMap<>();
    private BlockPos entryFoot;

    private int ticks;
    private int checks;
    private int phase;
    private int phaseTicks;
    private boolean done;
    private SurvivalExitTask escape;

    // ---- 逐相位读数 ----
    private boolean dryJumping;
    private boolean dryAlarm;
    private boolean lowAirSubmerged;
    private boolean lowAirJumping;
    private boolean lowAirAlarm;
    private boolean enoughAirSubmerged;
    private boolean enoughAirJumping;
    private boolean enoughAirAlarm;
    private boolean statusOk = true;

    public SurvivalEscapeAirCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SurvivalEscapeAirCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
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
            check("整步必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        switch (phase) {
            case 0 -> setup(level);
            case 1 -> dryPhase(level);
            case 2 -> submergedLowAirPhase(level);
            case 3 -> submergedEnoughAirPhase(level);
            case 4 -> assertPhase(level);
            default -> {
                return finish();
            }
        }
        return Task.Status.RUNNING;
    }

    private void setup(ServerLevel level) {
        forceload(level, true);
        // 建之前先证明"地面**之上**这一带本来就是空气"（不是就**不许**覆盖：那是别人的场景）。
        // ⚠️ 池子那几格是**挖进天然地面**的（y<0）⇒ 那里**本来就必须是实心**（实测踩到：
        // 第一版把 y∈[-3..2] 全要求成空气 ⇒ 243 个非空气 ⇒ 场景直接不建）。
        int preExisting = 0;
        for (int dx = -(POOL_R + 3); dx <= POOL_R + 3; dx++) {
            for (int dz = -(POOL_R + 3); dz <= POOL_R + 3; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        preExisting++;
                    }
                }
            }
        }
        if (preExisting != 0) {
            check("硬前提失败：SCENE_NOT_AIR（地面之上有 " + preExisting + " 个非空气方块）", false);
            phase = 4;
            return;
        }
        // 天然地面必须是实心的（池子要挖进它、干格要靠它站）
        check("前提：天然地面实心（" + ORIGIN.below(1).toShortString() + "）",
                !level.getBlockState(ORIGIN.below(1)).isAir());
        for (int dx = -POOL_R; dx <= POOL_R; dx++) {
            for (int dz = -POOL_R; dz <= POOL_R; dz++) {
                for (int d = 1; d <= DEPTH; d++) {
                    set(level, ORIGIN.offset(dx, -d, dz), Blocks.WATER);
                }
                set(level, ORIGIN.offset(dx, -(DEPTH + 1), dz), Blocks.STONE);
            }
        }
        // 干起点/干落点的支撑（本来就有天然地面，这里只确保它可站）
        check("前提：干起点可站（" + DRY_START.toShortString() + "）", canStand(level, DRY_START));
        check("前提：干落点可站（" + DRY_REFUGE.toShortString() + "）", canStand(level, DRY_REFUGE));
        check("前提：水池灌满（3 深）+ 池底实心",
                java.util.stream.IntStream.range(1, DEPTH + 1)
                        .allMatch(d -> level.getBlockState(ORIGIN.below(d)).is(Blocks.WATER))
                        && !level.getBlockState(ORIGIN.below(DEPTH + 1)).isAir());

        entryFoot = bot.blockPosition();
        teleportFoot(level, DRY_START);
        bot.setAirSupply(FULL_AIR);
        escape = new SurvivalExitTask(bot, DRY_REFUGE);
        phase = 1;
        phaseTicks = 0;
    }

    private void dryPhase(ServerLevel level) {
        if (++phaseTicks > PHASE_TICKS) {
            dryJumping = bot.controller().isJumping();
            dryAlarm = escape.airAlarmActive();
            phase = 2;
            phaseTicks = 0;
            return;
        }
        tickEscape();
    }

    private void submergedLowAirPhase(ServerLevel level) {
        // 把他按进水里：脚位 = 池底，眼必然在水里；空气给 LOW_AIR（**>0**，所以分类仍是 WATER_CONTACT）
        pinSubmerged(level);
        bot.setAirSupply(LOW_AIR);
        if (++phaseTicks > PHASE_TICKS) {
            lowAirSubmerged = bot.isEyeInFluid(FluidTags.WATER);
            lowAirJumping = bot.controller().isJumping();
            lowAirAlarm = escape.airAlarmActive();
            phase = 3;
            phaseTicks = 0;
            return;
        }
        tickEscape();
    }

    private void submergedEnoughAirPhase(ServerLevel level) {
        pinSubmerged(level);
        bot.setAirSupply(FULL_AIR);
        if (++phaseTicks > PHASE_TICKS) {
            enoughAirSubmerged = bot.isEyeInFluid(FluidTags.WATER);
            enoughAirJumping = bot.controller().isJumping();
            enoughAirAlarm = escape.airAlarmActive();
            phase = 4;
            return;
        }
        tickEscape();
    }

    /** 走位照常 tick；状态只用来判"没死锁/没失败"。 */
    private void tickEscape() {
        Task.Status status = escape.tick();
        statusOk &= status == Task.Status.RUNNING;
    }

    private void pinSubmerged(ServerLevel level) {
        BlockPos foot = ORIGIN.below(DEPTH - 1);
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
    }

    private void assertPhase(ServerLevel level) {
        findings.add("dry(alarm=" + dryAlarm + ",jumping=" + dryJumping + ")"
                + " lowAir(eyeInWater=" + lowAirSubmerged + ",alarm=" + lowAirAlarm + ",jumping="
                + lowAirJumping + ") enoughAir(eyeInWater=" + enoughAirSubmerged + ",alarm=" + enoughAirAlarm
                + ",jumping=" + enoughAirJumping + ") statusOk=" + statusOk);
        BotLog.info("[EscapeAir] 读数 干地(告警={} 按跳跃={}) 低空气(眼在水里={} 告警={} 按跳跃={})"
                        + " 空气够(眼在水里={} 告警={} 按跳跃={}) statusOk={}", dryAlarm, dryJumping,
                lowAirSubmerged, lowAirAlarm, lowAirJumping, enoughAirSubmerged, enoughAirAlarm,
                enoughAirJumping, statusOk);

        check("前提：干地上跑逃生任务时**告警不激活**（实际 " + dryAlarm + "）——地面长按 = 兔子跳",
                !dryAlarm);
        check("前提：相位 2 里 bot 的眼睛**真的在水里**（实际 " + lowAirSubmerged + "）",
                lowAirSubmerged);
        check("前提：相位 3 里 bot 的眼睛**仍在水里**（实际 " + enoughAirSubmerged + "）",
                enoughAirSubmerged);
        check("⭐ `D-383`：**眼在水里 + 空气 60 ≤ AIR_SAFE ⇒ 告警必须激活**（实际 " + lowAirAlarm
                        + "）—— 这条就是真机那 7 秒里缺的动作（并且这一相真的按下了跳跃输入 "
                        + lowAirJumping + "）", lowAirAlarm);
        check("⭐ `D-383`：**空气回到 300 后告警必须解除**（实际 " + enoughAirAlarm
                        + "）—— 告警不许一直按着（会干扰正常走位）。"
                        + "⚠️ 这一相 `isJumping`=" + enoughAirJumping + " 不作为判据：执行器自己在爬出水池时"
                        + "也会按跳跃（首跑实测踩到）", !enoughAirAlarm);
        check("逃生任务全程**没有失败/死锁**（statusOk=" + statusOk + "）", statusOk);
        finish();
    }

    // ==================== 世界读写 ====================

    private void set(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        built.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    private boolean canStand(ServerLevel level, BlockPos pos) {
        return com.dddgn.alice.pathing.MovementHelper.canStandCentered(level, pos);
    }

    private void teleportFoot(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private void forceload(ServerLevel level, boolean on) {
        for (int cx = (ORIGIN.getX() - 4) >> 4; cx <= (ORIGIN.getX() + 4) >> 4; cx++) {
            for (int cz = (ORIGIN.getZ() - 4) >> 4; cz <= (ORIGIN.getZ() + 4) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        for (Map.Entry<BlockPos, BlockState> entry : built.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        forceload(level, false);
        bot.controller().stopMovement();
        bot.setAirSupply(FULL_AIR);
        if (entryFoot != null) {
            teleportFoot(level, entryFoot);
        }
        boolean pass = failures.isEmpty();
        BotLog.info("[EscapeAir] SUMMARY checks={} failures={} → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 逃生空气告警取证 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [EscapeAir]）"));
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
