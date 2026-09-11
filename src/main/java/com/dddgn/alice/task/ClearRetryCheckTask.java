package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 限次清障"换候选"自检（R2 / D-121）：**一个候选失败不再放弃整棵树**。
 *
 * <p>病灶（2026-09-11 实测证据）：`tickClear()` 一旦失败就把 `clearExhausted` 整体置真，
 * 于是 8 格预算只用了 1 格就放弃整个目标（`clear_start used=1/8` → `clear_end exhausted=true`）。
 * 正确语义是：**这一格失败就换下一个候选**，只有"候选都用过"或"预算用尽"才放弃。
 *
 * <p>场景（运行时搭建，复用 `break_course` 的平坦平台 x −3..8 / z 62..70）：
 * 目标 `6,64,64` 放**泥土**（不需要正确工具 ⇒ 父任务不会因缺工具被拒），四周用**石头**围成
 * 3×3×2 的壳（y=64/65）把视线全挡死 ⇒ 父任务必然走**清障**路径。
 *
 * <p>两段断言：
 * <ol>
 *   <li><b>retry（无工具相位）</b>：先清空背包（没有镐）⇒ 每个清障子任务在构造时如实
 *       `no_suitable_tool` 失败 ⇒ 断言 `clearAttempts() ≥ 2` 且 `failedClearBlockers() ≥ 1`
 *       （确实**换过候选**）、目标泥土仍在、父任务 FAILED；</li>
 *   <li><b>clear_then_mine（有工具相位）</b>：发回镐，重跑同一信封 ⇒ 断言清障成功
 *       （`clearedBlocks() ≥ 1`）、目标被挖掉、任务 DONE（新子信封没破坏正常清障）；</li>
 *   <li><b>sub_profile（纯逻辑）</b>：清障子任务信封必须是父信封的**子集**
 *       （`clear=none`；加高 `min(父,1)`；无建拆同权）。</li>
 * </ol>
 *
 * <p>输出：`[ClearRetry] SUMMARY retry=? attempts=? failed=? clear_then_mine=? cleared=? sub_profile=? → PASS|FAIL`。
 */
public final class ClearRetryCheckTask implements Task {

    /** 与 `break_course_terrain` 对齐：平台 x −3..8 / 支撑 y=63 / z 62..70。 */
    public static final BlockPos START_FOOT = new BlockPos(6, 64, 67);
    /** 目标格（泥土；不需要正确工具 ⇒ 父任务允许"徒手"推进到清障阶段）。 */
    private static final BlockPos TARGET = new BlockPos(6, 64, 64);
    /** 父信封：可站站位 + 允许加高 3 + 限次清障 4 格（子任务必须自行裁剪）。 */
    private static final MiningProfile PARENT_PROFILE =
            MiningProfile.STANDABLE_ONLY.withGain(3).withClear(4);
    private static final int RUN_BUDGET_TICKS = 600;

    private enum Phase { SETUP, RUN_NO_TOOL, ASSERT_NO_TOOL, RUN_WITH_TOOL, ASSERT_WITH_TOOL, DONE }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private int ticks;
    private MineTask task;
    private int attempts;
    private int failed;
    private int cleared;
    private boolean targetIntact;
    private String status = "-";

    public ClearRetryCheckTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "ClearRetryCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(TARGET);
    }

    @Override
    public String failureReason() {
        return status;
    }

    @Override
    public Status tick() {
        if (++ticks > RUN_BUDGET_TICKS) {
            BotLog.warn("[ClearRetry] 超时 phase={} ticks={}", phase, ticks);
            return finish(false, "timeout");
        }
        return switch (phase) {
            case SETUP -> setup();
            case RUN_NO_TOOL -> runNoTool();
            case ASSERT_NO_TOOL -> assertNoTool();
            case RUN_WITH_TOOL -> runWithTool();
            case ASSERT_WITH_TOOL -> assertWithTool();
            case DONE -> Status.DONE;
        };
    }

    // ==================== 相位 ====================

    /** 复用 break_course 平台 + 运行时搭"目标被石头壳包围"的几何。 */
    private Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:break_course_terrain");
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        scope.begin(TARGET, 16, bot.getUUID());
        // 相位①：**没有工具**（R1 之后生产任务不发工具；这里借"缺镐"制造确定的清障失败）
        FixtureToolKit.resetInventory(bot);
        // 目标：泥土（不需要正确工具）；壳：石头（需要镐 ⇒ 无镐时清障必然失败）
        server.getCommands().performPrefixedCommand(source,
                "setblock " + TARGET.getX() + " " + TARGET.getY() + " " + TARGET.getZ()
                        + " minecraft:dirt");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;   // 目标列（y=64 是目标本身，y=65 是它的头位）
                }
                for (int dy = 0; dy <= 1; dy++) {
                    server.getCommands().performPrefixedCommand(source,
                            "setblock " + (TARGET.getX() + dx) + " " + (TARGET.getY() + dy)
                                    + " " + (TARGET.getZ() + dz) + " minecraft:stone");
                }
            }
        }
        boolean shellOk = shellPresent(level);
        boolean targetOk = level.getBlockState(TARGET).is(net.minecraft.world.level.block.Blocks.DIRT);
        BotLog.info("[ClearRetry] 场景就位 start={} target={} shell={} targetPlaced={}",
                START_FOOT.toShortString(), TARGET.toShortString(), shellOk, targetOk);
        if (!shellOk || !targetOk) {
            return finish(false, "scene_not_built");
        }
        // 父任务：泥土目标 ⇒ 不需要正确工具 ⇒ 会推进到"规划失败 → 清障"
        task = newTask();
        phase = Phase.RUN_NO_TOOL;
        return Status.RUNNING;
    }

    private Status runNoTool() {
        Status s = task.tick();
        if (s == Status.RUNNING) {
            return Status.RUNNING;
        }
        status = String.valueOf(s);
        phase = Phase.ASSERT_NO_TOOL;
        return Status.RUNNING;
    }

    private Status assertNoTool() {
        attempts = task.clearAttempts();
        failed = task.failedClearBlockers().size();
        targetIntact = bot.serverLevel().getBlockState(TARGET)
                .is(net.minecraft.world.level.block.Blocks.DIRT);
        boolean retryOk = attempts >= 2 && failed >= 1 && targetIntact && !"DONE".equals(status);
        BotLog.info("[ClearRetry] retry attempts={} failed={} targetIntact={} status={} → {}",
                attempts, failed, targetIntact, status, retryOk ? "PASS" : "FAIL");
        if (!retryOk) {
            return finish(false, "retry");
        }
        // 相位②：发回镐，重跑同一信封 ⇒ 清障应当成功并挖掉目标
        FixtureToolKit.ensurePickaxe(bot);
        task = newTask();
        phase = Phase.RUN_WITH_TOOL;
        return Status.RUNNING;
    }

    private Status runWithTool() {
        Status s = task.tick();
        if (s == Status.RUNNING) {
            return Status.RUNNING;
        }
        status = String.valueOf(s);
        phase = Phase.ASSERT_WITH_TOOL;
        return Status.RUNNING;
    }

    private Status assertWithTool() {
        cleared = task.clearedBlocks();
        boolean gone = bot.serverLevel().getBlockState(TARGET).isAir();
        boolean mineOk = "DONE".equals(status) && cleared >= 1 && gone;
        BotLog.info("[ClearRetry] clear_then_mine status={} cleared={} targetGone={} → {}",
                status, cleared, gone, mineOk ? "PASS" : "FAIL");
        return finish(mineOk, mineOk ? "ok" : "clear_then_mine");
    }

    private Status finish(boolean pass, String why) {
        phase = Phase.DONE;
        // 子信封断言（纯逻辑，不需要世界）：清障归零 + 加高 min(父,1) + 无建拆同权
        String subWithGain = PARENT_PROFILE.nestedSubTask().describe();
        String subNoGain = MiningProfile.STANDABLE_ONLY.withClear(8).nestedSubTask().describe();
        boolean subOk = "standableOnly=true gain<=1 blocks<=12 clear=none".equals(subWithGain)
                && "standableOnly=true gain=none clear=none".equals(subNoGain);
        BotLog.info("[ClearRetry] sub_profile withGain=({}) noGain=({}) → {}",
                subWithGain, subNoGain, subOk ? "PASS" : "FAIL");
        boolean allPass = pass && subOk;
        BotLog.info("[ClearRetry] SUMMARY retry={} attempts={} failed={} clear_then_mine={} cleared={}"
                        + " targetIntact={} sub_profile={} → {}",
                attempts >= 2 && failed >= 1 ? "PASS" : "FAIL", attempts, failed,
                cleared >= 1 ? "PASS" : "FAIL", cleared, targetIntact,
                subOk ? "PASS" : "FAIL", allPass ? "PASS" : "FAIL(" + why + ")");
        return allPass ? Status.DONE : Status.FAILED;
    }

    private MineTask newTask() {
        return new MineTask(bot, TARGET, scope,
                MiningBudget.forTarget(bot, bot.serverLevel(), TARGET, false),
                PARENT_PROFILE,
                WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
    }

    /** 世界事实：壳（y=64/65 的 3×3 除中心列）是否都在。 */
    private boolean shellPresent(ServerLevel level) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = TARGET.offset(dx, dy, dz);
                    if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.STONE)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
