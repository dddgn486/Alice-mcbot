package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 单目标挖掘编排任务：有限局部清障 -> BotMiner 挖掘 -> DropCollectionTask 收集。
 * 普通挖矿不生成道路或隧道，深埋目标明确失败为 target_requires_tunnel。
 */
public final class MineTask implements Task {
    private enum Phase { MINING, COLLECTING }

    private static final int MAX_CLEAR_DEPTH = 2;
    private static final double MAX_CLEAR_REACH = 4.5D;

    private final ServerPlayer bot;
    private final BlockPos target;
    private final ScopeBuffer scope;
    private BotMiner miner;
    private BlockPos currentMineTarget;
    private int clearDepth;
    private Phase phase = Phase.MINING;
    private DropCollectionTask collector;
    private String failureReason = "";

    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        this.currentMineTarget = this.target;
        this.miner = new BotMiner(bot, this.currentMineTarget);
        bot.getInventory().setItem(bot.getInventory().selected,
                new ItemStack(Items.DIAMOND_PICKAXE));
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        BotLog.info("任务创建: MineTask target={}", this.target.toShortString());
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    public BlockPos mineStartPos() {
        return miner.mineStartPos();
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public Status tick() {
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failureReason = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        if (phase == Phase.COLLECTING) {
            Status status = collector.tick();
            if (status == Status.FAILED) {
                failureReason = collector.failureReason();
            }
            return status;
        }

        BotMiner.Status status = miner.tick();
        if (status == BotMiner.Status.MINING || status == BotMiner.Status.MOVING) {
            return Status.RUNNING;
        }
        if (status == BotMiner.Status.DONE) {
            if (!currentMineTarget.equals(target)) {
                BotLog.info("清障完成: 已挖掉遮挡 {} -> 继续挖原目标 {}",
                        currentMineTarget.toShortString(), target.toShortString());
                startMining(target);
                return Status.RUNNING;
            }
            phase = Phase.COLLECTING;
            collector = new DropCollectionTask(bot, target, scope);
            BotLog.info("挖掘阶段完成,进入拾取阶段: target={}", target.toShortString());
            return Status.RUNNING;
        }

        String minerFailure = miner.failureReason();
        if (isHardTargetRefusal(minerFailure)) {
            failureReason = minerFailure;
            return Status.FAILED;
        }
        BlockPos blocker = findDirectBlocker();
        if (blocker != null && clearDepth < MAX_CLEAR_DEPTH
                && !blocker.equals(currentMineTarget)) {
            String refusalReason = BlockBreakSafety.clearingRefusal(bot, blocker);
            if (refusalReason != null) {
                failureReason = refusalReason;
                BotLog.warn("清障被安全策略拦截: blocker={} target={} reason={}",
                        blocker.toShortString(), target.toShortString(), failureReason);
                return Status.FAILED;
            }
            clearDepth++;
            BotLog.info("局部清障({}/{}): 当前站位直接挖 {}", clearDepth,
                    MAX_CLEAR_DEPTH, blocker.toShortString());
            startMining(blocker);
            return Status.RUNNING;
        }
        failureReason = "target_requires_tunnel";
        BotLog.info("目标需要独立通道规划: target={} minerFailure={} clearDepth={}/{}",
                target.toShortString(), minerFailure, clearDepth, MAX_CLEAR_DEPTH);
        return Status.FAILED;
    }

    private static boolean isHardTargetRefusal(String reason) {
        return "unbreakable_block".equals(reason)
                || "fluid_risk_lava".equals(reason)
                || reason.startsWith("protected_");
    }

    private void startMining(BlockPos pos) {
        currentMineTarget = pos.immutable();
        miner = new BotMiner(bot, currentMineTarget);
    }

    private BlockPos findDirectBlocker() {
        net.minecraft.world.phys.Vec3 eye = bot.getEyePosition();
        BlockPos blocker = raycastBlock(eye, target.getCenter());
        if (blocker == null || blocker.equals(target)
                || eye.distanceTo(blocker.getCenter()) > MAX_CLEAR_REACH) {
            return null;
        }
        return blocker;
    }

    private BlockPos raycastBlock(net.minecraft.world.phys.Vec3 from,
                                  net.minecraft.world.phys.Vec3 to) {
        net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(
                from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                bot);
        net.minecraft.world.phys.BlockHitResult hit = bot.level().clip(context);
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? hit.getBlockPos().immutable() : null;
    }
}
