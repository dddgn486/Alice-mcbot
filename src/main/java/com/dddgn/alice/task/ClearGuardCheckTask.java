package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 容器绕行自检（J6-b2，D-095）：**运行一次真实挖掘，断言箱子完好**。
 *
 * <p>场景 `clear_guard_course`：目标在墙后，墙的唯一缺口被**箱子**堵住。
 * 断言三件事：
 * <ol>
 *   <li>谓词层：`BlockInteraction.breakable(chest, PATH_ACCESS)` 必须为 **false**（含方块实体不得作为通行破坏对象）；</li>
 *   <li>执行层：跑完一次真实 {@link MineTask} 之后，**箱子仍在**（bot 没有为取目标而拆它）；</li>
 *   <li>诚实性：无论"绕路成功"还是"如实失败"，都算 PASS——本自检**不要求拿到目标**，
 *       只要求**不伤害非目标容器**。</li>
 * </ol>
 */
public final class ClearGuardCheckTask implements Task {

    /** 场景常量（与 `clear_guard_terrain.mcfunction` 对齐）。 */
    public static final BlockPos START_FOOT = new BlockPos(44, 64, 158);
    /** 堵住唯一通道的两个容器（缺口开在身子/头的高度，踩不到）。 */
    private static final java.util.List<BlockPos> CONTAINERS = java.util.List.of(
            new BlockPos(50, 65, 158), new BlockPos(50, 66, 158));
    private static final BlockPos TARGET = new BlockPos(56, 64, 158);

    /** 单次挖掘的 tick 上限。 */
    private static final int MINE_BUDGET_TICKS = 1600;

    /**
     * `D-362` 组：把同一道墙的通道塞换成**矿**（= 本任务的目标种类）。
     *
     * <p>为什么用同一场景：`clear_guard_course` 的几何**正好**是"唯一通道被 2 格高塞子堵住、上下都是石头"
     * —— 塞子若是矿，它就是"清障 vs 任务目标"冲突的**最小复现**（真机第一轮那个 `479,68,104` 就是它）。
     */
    private static final BlockPos ORE_PLUG_LOW = new BlockPos(50, 65, 158);
    private static final BlockPos ORE_PLUG_HIGH = new BlockPos(50, 66, 158);
    private static final net.minecraft.world.level.block.Block PLUG_ORE =
            net.minecraft.world.level.block.Blocks.IRON_ORE;

    private enum Phase { SETUP, MINE, ASSERT, GUARD_SETUP, GUARD_MINE, GUARD_ASSERT, DONE }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private MineTask miner;
    private int ticks;
    private String failure = "";
    private boolean terminated;

    // ---- `D-362` 组的读数（进 SUMMARY） ----
    private boolean guardBaselineAllowed;
    private boolean guardPathRefused;
    private boolean guardExpectedAllowed;
    private String guardReason = "-";

    public ClearGuardCheckTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "ClearGuardCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(TARGET);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        if (terminated) {
            return Task.Status.DONE;
        }
        return switch (phase) {
            case SETUP -> setup();
            case MINE -> mine();
            case ASSERT -> assertResult();
            case GUARD_SETUP -> guardSetup();
            case GUARD_MINE -> guardMine();
            case GUARD_ASSERT -> guardAssert();
            case DONE -> Task.Status.DONE;
        };
    }

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:clear_guard_terrain");
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        scope.begin(TARGET, 24, bot.getUUID());
        // 夹具职责：保证手上有镐（挖石头要用）
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "pickaxe");
        BotLog.info("[ClearGuard] 场景就位 start={} target={} containers={}",
                START_FOOT.toShortString(), TARGET.toShortString(), CONTAINERS);
        miner = new MineTask(bot, TARGET, scope, MiningBudget.forTarget(bot, level, TARGET, true),
                com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore(),
                WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
        phase = Phase.MINE;
        return Task.Status.RUNNING;
    }

    private Task.Status mine() {
        if (++ticks > MINE_BUDGET_TICKS) {
            BotLog.warn("[ClearGuard] 挖掘超时（{} tick）→ 结束并断言", ticks);
            miner = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        Task.Status status = miner.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        BotLog.info("[ClearGuard] 挖掘结束 status={} reason={}", status,
                miner.failureReason() == null || miner.failureReason().isBlank()
                        ? "-" : miner.failureReason());
        miner = null;
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        boolean predicateRefuses = CONTAINERS.stream().noneMatch(pos -> BlockInteraction.breakable(
                bot, level, pos, WriteGrant.of(taskName(), WriteReason.PATH_ACCESS)));
        boolean chestIntact = CONTAINERS.stream().allMatch(
                pos -> level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CHEST));
        // 目标拿没拿到**不作为判据**：本自检只要求"不伤害非目标容器"
        boolean pass = predicateRefuses && chestIntact;
        BotLog.info("[ClearGuard] SUMMARY predicate_refuses={} chest_intact={} target_removed={} → {}",
                predicateRefuses, chestIntact, level.getBlockState(TARGET).isAir(),
                pass ? "PASS" : "FAIL");
        if (!pass) {
            failure = "CLEAR_GUARD_FAILED predicate_refuses=" + predicateRefuses
                    + " chest_intact=" + chestIntact;
        }
        if (!pass) {
            terminated = true;
            bot.controller().stopMovement();
            phase = Phase.DONE;
            return Task.Status.FAILED;
        }
        // ⭐ `D-362`：接着跑"清障 vs 任务目标"那一组（**同一场景**，把塞子换成矿）
        phase = Phase.GUARD_SETUP;
        return Task.Status.RUNNING;
    }

    /**
     * `D-362` 第二组：通道塞换成**矿**，并把这个任务的目标声明成"铁矿石"。
     *
     * <p>判据五条：① 无作用域时矿**可**被清障破坏（基线，证明这不是"无条件拦"）；
     * ② 装作用域后 `PATH_ACCESS` 被拒且理由是 `task_target_not_clearance`；③ `EXPECTED_TARGET`（真的去挖它）不受影响；
     * ④ 跑一次**真实** `MineTask` 之后矿塞**仍在**（绕行或如实失败都算 PASS）；⑤ `end` 之后恢复可破坏（不泄漏）。
     */
    private Task.Status guardSetup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        // 场景复位（顺带把箱子放回来）⇒ 再换成矿塞
        server.getCommands().performPrefixedCommand(source, "function alice_test:clear_guard_terrain");
        level.setBlockAndUpdate(ORE_PLUG_LOW, PLUG_ORE.defaultBlockState());
        level.setBlockAndUpdate(ORE_PLUG_HIGH, PLUG_ORE.defaultBlockState());
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        ticks = 0;

        // ① 基线：**没有**作用域 ⇒ 矿可以被当清障方块挖掉（证明保护是"有作用域才拦"，不是一律拦）
        guardBaselineAllowed = BlockInteraction.breakable(bot, level, ORE_PLUG_LOW,
                WriteGrant.of(taskName(), WriteReason.PATH_ACCESS));
        // ② 装作用域：本任务的目标 = 铁矿石
        com.dddgn.alice.action.TaskTargetProtection.begin(bot, taskName(),
                pos -> pos != null && level.hasChunkAt(pos) && level.getBlockState(pos).is(PLUG_ORE));
        guardReason = BlockInteraction.breakRefusal(bot, level, ORE_PLUG_LOW,
                WriteGrant.of(taskName(), WriteReason.PATH_ACCESS));
        guardPathRefused = !BlockInteraction.breakable(bot, level, ORE_PLUG_LOW,
                WriteGrant.of(taskName(), WriteReason.PATH_ACCESS));
        // ③ 真的去挖那一格（EXPECTED_TARGET）**不受影响** —— 否则挖矿整体被打断
        guardExpectedAllowed = BlockInteraction.breakable(bot, level, ORE_PLUG_LOW,
                WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
        BotLog.info("[ClearGuard] D-362 组就位 塞子={}..{} 基线可挖={} 清障被拒={}（理由={}）目标理由可挖={}",
                ORE_PLUG_LOW.toShortString(), ORE_PLUG_HIGH.toShortString(), guardBaselineAllowed,
                guardPathRefused, guardReason, guardExpectedAllowed);
        // ④ 真实执行：去挖墙后的目标（矿塞受保护 ⇒ 只能绕行或如实失败）
        miner = new MineTask(bot, TARGET, scope, MiningBudget.forTarget(bot, level, TARGET, true),
                com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore(),
                WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
        phase = Phase.GUARD_MINE;
        return Task.Status.RUNNING;
    }

    private Task.Status guardMine() {
        if (++ticks > MINE_BUDGET_TICKS) {
            BotLog.warn("[ClearGuard] D-362 组挖掘超时（{} tick）→ 结束并断言", ticks);
            miner = null;
            phase = Phase.GUARD_ASSERT;
            return Task.Status.RUNNING;
        }
        Task.Status status = miner.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        BotLog.info("[ClearGuard] D-362 组挖掘结束 status={} reason={}", status,
                miner.failureReason() == null || miner.failureReason().isBlank()
                        ? "-" : miner.failureReason());
        miner = null;
        phase = Phase.GUARD_ASSERT;
        return Task.Status.RUNNING;
    }

    private Task.Status guardAssert() {
        ServerLevel level = bot.serverLevel();
        boolean oreIntact = level.getBlockState(ORE_PLUG_LOW).is(PLUG_ORE)
                && level.getBlockState(ORE_PLUG_HIGH).is(PLUG_ORE);
        com.dddgn.alice.action.TaskTargetProtection.end(bot);
        // ⑤ 撤销之后必须恢复可破坏（否则这个 bot 之后所有开路清障都会被拦 —— 泄漏比 bug 更隐蔽）
        boolean noLeak = BlockInteraction.breakable(bot, level, ORE_PLUG_LOW,
                WriteGrant.of(taskName(), WriteReason.PATH_ACCESS));
        boolean reasonOk = com.dddgn.alice.action.TaskTargetProtection.CODE.equals(guardReason);
        boolean pass = guardBaselineAllowed && guardPathRefused && guardExpectedAllowed
                && oreIntact && noLeak && reasonOk;
        BotLog.info("[ClearGuard] SUMMARY guard_baseline_allowed={} guard_path_refused={} guard_reason={} "
                        + "guard_expected_allowed={} guard_ore_intact={} guard_no_leak={} → {}",
                guardBaselineAllowed, guardPathRefused, guardReason, guardExpectedAllowed, oreIntact,
                noLeak, pass ? "PASS" : "FAIL");
        if (!pass) {
            failure = "TARGET_GUARD_FAILED baseline=" + guardBaselineAllowed
                    + " pathRefused=" + guardPathRefused + " reason=" + guardReason
                    + " expectedAllowed=" + guardExpectedAllowed + " oreIntact=" + oreIntact
                    + " noLeak=" + noLeak;
        }
        // **结束复位**（失败路径同样走）：把场景恢复成带箱子的原样 + bot 回起点
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "function alice_test:clear_guard_terrain");
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        com.dddgn.alice.action.TaskTargetProtection.reset();
        terminated = true;
        phase = Phase.DONE;
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }
}
