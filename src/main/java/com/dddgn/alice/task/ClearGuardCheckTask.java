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

    private enum Phase { SETUP, MINE, ASSERT, DONE }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private MineTask miner;
    private int ticks;
    private String failure = "";
    private boolean terminated;

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
        terminated = true;
        bot.controller().stopMovement();
        phase = Phase.DONE;
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }
}
