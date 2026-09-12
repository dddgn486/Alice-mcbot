package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import com.dddgn.alice.log.BotLog;

/**
 * **脚位格规则的无头断言**（原 `pathing/PathingRegression.assertFootCellRule`，2026-09-13 迁出）。
 *
 * <p>为什么单独成类：legacy `PathingRegression`（含它自带的那套 legacy 回归）已随 `/alice selftest`
 * 一起退役，但其中**这一个方法**是新内核回归仍在用的**有用断言**（D-105 不变式），
 * 所以把它救出来独立存放，其余 legacy 代码一并删除。
 *
 * <p>不变式：`MovementHelper.footCell(...)` 给出的格必须是规划层 `MovementHelper.canWalkOn(...)`
 * 认可的格 —— 支撑不满一格时（箱子/灵魂沙 0.875、底半砖 0.5）若二者不一致，完成契约会死锁。
 */
public final class FootCellRuleCheck {

    private FootCellRuleCheck() {
    }

    /**
     * 运行期脚位格规则（D-105，无头断言，不依赖 bot 物理）。
     *
     * <p>不变式：**`footCell` 给出的格必须是规划层 {@link MovementHelper#canWalkOn} 认可的格**
     * ——支撑是整格时二者天然一致；支撑不满一格时（箱子/灵魂沙 0.875、底半砖 0.5）原版
     * `blockPosition()` 会把脚留在支撑自己那一格，与规划层差整整一格，完成契约因此死锁。
     *
     * <p>公开给 `PathingRegressionTask` 作为夹具级断言（自建区域，与场景不重叠）。
     */
    public static boolean assertFootCellRule(ServerLevel level, BlockPos origin) {
        clearAndFloor(level, origin, 12, 12);
        BlockPos base = origin.offset(12, 0, 12);
        BlockPos chest = base;
        BlockPos slab = base.offset(1, 0, 0);
        BlockPos soulSand = base.offset(2, 0, 0);
        BlockPos carpet = base.offset(3, 0, 0);
        BlockPos full = base.offset(4, 0, 0);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(slab, Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), 3);
        level.setBlock(soulSand, Blocks.SOUL_SAND.defaultBlockState(), 3);
        level.setBlock(carpet, Blocks.WHITE_CARPET.defaultBlockState(), 3);
        level.setBlock(full, Blocks.DIRT.defaultBlockState(), 3);

        boolean chestOk = MovementHelper.footCell(level, chest.getX() + 0.5D, chest.getY() + 0.875D,
                chest.getZ() + 0.5D).equals(chest.above())
                && MovementHelper.canWalkOn(level, chest.above());
        boolean slabOk = MovementHelper.footCell(level, slab.getX() + 0.5D, slab.getY() + 0.5D,
                slab.getZ() + 0.5D).equals(slab.above())
                && MovementHelper.canWalkOn(level, slab.above());
        boolean soulSandOk = MovementHelper.footCell(level, soulSand.getX() + 0.5D,
                soulSand.getY() + 0.875D, soulSand.getZ() + 0.5D).equals(soulSand.above())
                && MovementHelper.canWalkOn(level, soulSand.above());
        // 薄面（地毯 0.0625）与整格：脚位格就是脚所在格，不上移
        boolean thinOk = MovementHelper.footCell(level, carpet.getX() + 0.5D, carpet.getY() + 0.0625D,
                carpet.getZ() + 0.5D).equals(carpet);
        boolean fullOk = MovementHelper.footCell(level, full.getX() + 0.5D, full.getY() + 1.0D,
                full.getZ() + 0.5D).equals(full.above());
        boolean ok = chestOk && slabOk && soulSandOk && thinOk && fullOk;
        BotLog.info("PATHING_REGRESSION {} foot_cell_rule chest={} bottomSlab={} soulSand={}"
                        + " thinFace={} fullBlock={}",
                ok ? "PASS" : "FAIL", chestOk, slabOk, soulSandOk, thinOk, fullOk);
        return ok;
    }

    private static void clearAndFloor(ServerLevel level, BlockPos origin, int offsetX, int offsetZ) {
        BlockPos base = origin.offset(offsetX, 0, offsetZ);
        for (int dx = -3; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(base.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), 3);
            }
        }
    }
}
