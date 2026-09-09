package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划路线的"接触岩浆"检查（回归安全断言用）。
 *
 * <p>用途：`lava_course` 这类场景的**安全属性**不是"无路可走"（PILLAR 等新 Movement
 * 会打开合法绕行/搭桥路线），而是"bot 永远不会把身体放进岩浆、也不会站在岩浆上"。
 * 本类按每段 Movement 枚举 **bot 身体会占据 / 站立其上**的格子，检查其中是否含岩浆。
 *
 * <p>注意：判定只看 bot 自身占据的格子与支撑格，不看"在岩浆上方放置的方块"——
 * 站在自建方块上、方块下面才是岩浆，属于安全行为（Baritone 同样会搭桥跨越）。
 */
public final class PlanRouteSafety {

    private PlanRouteSafety() {
    }

    /** 计划中与岩浆重叠的格子（空 = 路线不接触岩浆）。 */
    public static List<BlockPos> lavaContacts(PathPlan plan, ServerLevel level) {
        List<BlockPos> hits = new ArrayList<>();
        for (PlannedMovement movement : plan.movements()) {
            for (BlockPos pos : occupiedCells(movement)) {
                if (level.getBlockState(pos).is(Blocks.LAVA)) {
                    hits.add(pos.immutable());
                }
            }
        }
        return hits;
    }

    /** 单段 Movement 中 bot 身体占据 / 站立其上的格子集合。 */
    public static Set<BlockPos> occupiedCells(PlannedMovement movement) {
        BlockPos from = movement.fromFoot();
        BlockPos to = movement.toFoot();
        Set<BlockPos> set = new LinkedHashSet<>();
        addBody(set, from);              // 起点身体 + 头
        addBody(set, to);                // 终点身体 + 头
        addBody(set, to.below());        // 终点支撑（放置类 Movement 的放置位也在此）
        switch (movement.movementType()) {
            case DIAGONAL -> {
                // 切角经过的两个角格
                addBody(set, new BlockPos(to.getX(), from.getY(), from.getZ()));
                addBody(set, new BlockPos(from.getX(), from.getY(), to.getZ()));
            }
            case BREAK_AND_TRAVERSE -> addBody(set, new BlockPos(
                    (from.getX() + to.getX()) / 2, from.getY(), (from.getZ() + to.getZ()) / 2));
            default -> {
            }
        }
        return set;
    }

    private static void addBody(Set<BlockPos> set, BlockPos foot) {
        set.add(foot);
        set.add(foot.above());
    }
}
