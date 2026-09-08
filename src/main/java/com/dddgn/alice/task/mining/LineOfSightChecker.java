package com.dddgn.alice.task.mining;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 视线检查器：检查眼睛是否能沿至少一条目标方块内部采样射线命中目标。
 */
public final class LineOfSightChecker {
    private LineOfSightChecker() {}

    private static final double SAMPLE_EPSILON = 0.08D;

    public static LineOfSightResult check(Level level, BlockPos fromPos, BlockPos target, double eyeHeight) {
        Vec3 eyePosition = fromPos.getCenter().add(0, eyeHeight - 0.5, 0);
        LineOfSightResult result = checkFromEye(level, eyePosition, target);
        BotLog.info("[LOS探针/预检查] fromPos={} eye={} target={} clear={} blocker={} sample={} eyeHeight={}",
                fromPos.toShortString(), formatVec(eyePosition), target.toShortString(), result.isClear(),
                result.getFirstBlocker() == null ? "-" : result.getFirstBlocker().toShortString(),
                result.getSuccessfulSample() == null ? "-" : formatVec(result.getSuccessfulSample()), eyeHeight);
        return result;
    }

    public static LineOfSightResult checkFromEye(Level level, Vec3 eyePosition, BlockPos target) {
        List<Vec3> samples = targetSamples(target);
        BlockPos firstBlocker = null;
        for (Vec3 sample : samples) {
            BlockHitResult hit = clip(level, eyePosition, sample);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                return new LineOfSightResult(true, null, 0, sample);
            }
            if (firstBlocker == null && hit.getType() == HitResult.Type.BLOCK) {
                firstBlocker = hit.getBlockPos().immutable();
            }
        }
        return new LineOfSightResult(false, firstBlocker, firstBlocker == null ? 0 : 1, null);
    }

    private static BlockHitResult clip(Level level, Vec3 eye, Vec3 sample) {
        return level.clip(new ClipContext(eye, sample,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
    }

    private static List<Vec3> targetSamples(BlockPos target) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        double e = SAMPLE_EPSILON;
        List<Vec3> samples = new ArrayList<>(7);
        samples.add(target.getCenter());
        samples.add(new Vec3(x + e, y + 0.5D, z + 0.5D));
        samples.add(new Vec3(x + 1.0D - e, y + 0.5D, z + 0.5D));
        samples.add(new Vec3(x + 0.5D, y + e, z + 0.5D));
        samples.add(new Vec3(x + 0.5D, y + 1.0D - e, z + 0.5D));
        samples.add(new Vec3(x + 0.5D, y + 0.5D, z + e));
        samples.add(new Vec3(x + 0.5D, y + 0.5D, z + 1.0D - e));
        return samples;
    }

    private static String formatVec(Vec3 vec) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", vec.x, vec.y, vec.z);
    }

    public static List<LineOfSightResult> checkMultiple(Level level, List<BlockPos> candidates,
                                                          BlockPos target, double eyeHeight) {
        List<LineOfSightResult> results = new ArrayList<>(candidates.size());
        for (BlockPos candidate : candidates) {
            results.add(check(level, candidate, target, eyeHeight));
        }
        return results;
    }

    public static class LineOfSightResult {
        private final boolean clear;
        private final BlockPos firstBlocker;
        private final int blockerCount;
        private final Vec3 successfulSample;

        public LineOfSightResult(boolean clear, BlockPos firstBlocker, int blockerCount) {
            this(clear, firstBlocker, blockerCount, null);
        }

        public LineOfSightResult(boolean clear, BlockPos firstBlocker, int blockerCount, Vec3 successfulSample) {
            this.clear = clear;
            this.firstBlocker = firstBlocker;
            this.blockerCount = blockerCount;
            this.successfulSample = successfulSample;
        }

        public boolean isClear() { return clear; }
        public BlockPos getFirstBlocker() { return firstBlocker; }
        public int getBlockerCount() { return blockerCount; }
        public Vec3 getSuccessfulSample() { return successfulSample; }
        public boolean hasBlocker() { return firstBlocker != null; }

        @Override
        public String toString() {
            if (clear) return "LineOfSight[CLEAR]";
            return String.format("LineOfSight[BLOCKED, firstBlocker=%s, count=%d]",
                    firstBlocker != null ? firstBlocker.toShortString() : "null", blockerCount);
        }
    }
}
