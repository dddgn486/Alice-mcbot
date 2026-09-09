package com.dddgn.alice.task.mining;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 挖掘站位候选生成（D-067 批次 2 重写）。
 *
 * <p>设计（`docs/MINING_STAND_SELECTION_DESIGN.md` v7）：
 * <ul>
 *   <li>**候选范围**：眼位可触及目标任一面的格子（`bot.getBlockReach()` 决定），不再是"水平最多 1 格"；</li>
 *   <li>**垂直规则**：y+1 / y / y−1 全水平展开；**y−2 … y−4 只允许正下方**（向上挖）；</li>
 *   <li>**只收"现成可站"**：无支撑 / 头脚空间不足的格子归模式 B（批次 3），A 不放置、不破坏；</li>
 *   <li>**排除**：目标自身、目标正上方（挖掉自己支撑）、`target.above(2)`（脚下支撑必然挡视线，无效候选）；</li>
 *   <li>**硬前提（可挖掘面）**：从该站位的假设眼位能看到目标至少一个面（内缩多面体采样），
 *       且该可见采样点在触及距离内——"看得到但打不到"不算能挖（D-066）。</li>
 * </ul>
 */
public final class StandingPointSelector {
    /** Bot 眼睛高度（脚底到眼睛）。 */
    public static final double BOT_EYE_HEIGHT = 1.62D;
    /** 正下方候选层数：y−2 … y−(1+BELOW_LEVELS)。 */
    private static final int BELOW_LEVELS = 3;

    /** 候选站位：脚位 + 该站位的视线结果（供计划快照与日志复用）。 */
    public record Candidate(BlockPos foot, LineOfSightChecker.LineOfSightResult los) {
    }

    private StandingPointSelector() {
    }

    /** 生成模式 A 候选（只含现成可站且能挖到的站位）。 */
    public static List<Candidate> generateCandidates(ServerLevel level, BlockPos target,
                                                     BlockPos botFoot, double reach) {
        List<Candidate> result = new ArrayList<>();
        int radius = (int) Math.ceil(reach) + 1;
        // y+1 / y / y−1：全水平展开
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    addCandidate(level, target, target.offset(dx, dy, dz), reach, result);
                }
            }
        }
        // y−2 … y−4：只允许正下方
        for (int k = 2; k <= 1 + BELOW_LEVELS; k++) {
            addCandidate(level, target, target.below(k), reach, result);
        }
        return result;
    }

    /** Bot 当前站位是否已经"能挖到"（省去选位与寻路）。 */
    public static boolean isCurrentPositionGoodEnough(ServerLevel level, BlockPos target,
                                                      BlockPos currentPos, double reach) {
        return isValidStandingPoint(level, target, currentPos, reach) != null;
    }

    /** 站位有效性：可站 + 排除项 + 可挖掘面（可见面且在触及内）；返回视线结果，无效返回 null。 */
    public static LineOfSightChecker.LineOfSightResult isValidStandingPoint(ServerLevel level,
                                                                            BlockPos target,
                                                                            BlockPos pos, double reach) {
        if (pos.equals(target) || pos.equals(target.above())) {
            return null;
        }
        if (!isStandable(level, pos)) {
            return null;
        }
        Vec3 eye = eyeAt(pos);
        LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(level, eye, target);
        if (!los.isClear()) {
            return null;
        }
        if (eye.distanceTo(los.getSuccessfulSample()) > reach) {
            return null;
        }
        return los;
    }

    /** 现成可站：脚下有支撑 + 脚位/头位可通行。 */
    public static boolean isStandable(ServerLevel level, BlockPos pos) {
        return MovementHelper.canWalkOn(level, pos)
                && MovementHelper.canWalkThrough(level, pos)
                && MovementHelper.canWalkThrough(level, pos.above());
    }

    private static void addCandidate(ServerLevel level, BlockPos target, BlockPos pos, double reach,
                                     List<Candidate> out) {
        LineOfSightChecker.LineOfSightResult los = isValidStandingPoint(level, target, pos, reach);
        if (los != null) {
            out.add(new Candidate(pos.immutable(), los));
        }
    }

    /** 站位假设眼位（与 {@link LineOfSightChecker#check} 的口径一致）。 */
    public static Vec3 eyeAt(BlockPos foot) {
        return foot.getCenter().add(0.0D, BOT_EYE_HEIGHT - 0.5D, 0.0D);
    }
}
