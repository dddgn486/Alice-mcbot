package com.dddgn.alice.task.craft;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * **放置合成工作站**（阶段 3-A / A3b，D-190）—— 合成阶梯里**第一处世界写入**。
 *
 * <p>规矩（照 D-076 与 A 表）：
 * <ul>
 *   <li>**专用 reason**：{@link WriteReason#CRAFT_STATION_PLACE}（记账本、可审计、受放置预算约束）；</li>
 *   <li>**TEMP + 用完即拆**：账本记为临时方块，任务收尾必须把它**原样恢复**（`remaining` 回到 0）；</li>
 *   <li>**不猜放置面**：只放到"空气格 + 下方有支撑 + 存在合法放置面"的位置（与
 *       `PLACE_STEP_AND_TRAVERSE` 同一套判据，`BlockInteraction.hasPlacementFace`）；</li>
 *   <li>**只在缺工作站时才放**：附近已有工作台就用现成的（A3 路径，零写入）。</li>
 * </ul>
 */
public final class StationPlacement {

    /** 失败码。 */
    public static final class Codes {
        public static final String NO_SPOT = "no_placement_spot";
        public static final String BUDGET_EXHAUSTED = "place_budget_exhausted";
        public static final String PLACE_FAILED = "place_failed";
        public static final String NOT_PLACED = "station_not_present_after_place";

        private Codes() {
        }
    }

    /** 一次放置尝试的结果。 */
    public record Outcome(boolean ok, String code, BlockPos spot) {
        public String describe() {
            return (ok ? "OK" : "FAIL:" + code) + " spot="
                    + (spot == null ? "-" : spot.toShortString());
        }
    }

    private StationPlacement() {
    }

    /**
     * 在 bot 周围 {@code radius} 内找一个可放工作站的格（空气 + 有支撑 + 有合法放置面）。
     * **只读**：不改世界。
     */
    public static BlockPos findSpot(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -1, -radius),
                center.offset(radius, 1, radius))) {
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            if (level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
                continue;   // 无支撑 ⇒ 放上去会掉
            }
            if (!BlockInteraction.hasPlacementFace(level, pos)) {
                continue;   // 没有合法放置面 ⇒ 不猜
            }
            if (occupied(level, pos)) {
                continue;   // 实体占着的格子不许放（见 occupied 的说明）
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 该格是否被**实体**占着（2026-09-13 交付前自查抓到的缺陷）。
     *
     * <p>为什么要这条：搜索区间以 **bot 自己所在格**为中心，而 bot 那一格**永远是空气**（实体不改方块状态）、
     * 下方是实心、还有放置面 —— 于是"最近的空位"距离 = 0，**必然选中 bot 自己脚下那一格**。
     * 把它放成工作台 = 方块落在实体身内：轻则被顶起来、重则窒息，而且随后的"触及/站位"跟着变，
     * 现场现象会变成"夹具莫名其妙站起来了一格"。这不是理论担忧：夹具第一版就是这么写的。
     *
     * <p>用**实体包围盒相交**判定（而不是只排掉 bot 那一格）：头位那一格会被同一条件自动排除
     * （bot 高 1.8 ⇒ 它的包围盒伸进上一格），别的实体（掉落物/动物/玩家）也一视同仁。
     */
    private static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(pos),
                entity -> !entity.isSpectator()).isEmpty();
    }

    /**
     * 把主手的工作站方块放到 {@code spot}（**消耗物品、记账本 TEMP、受预算约束**）。
     *
     * <p>调用方必须在任务收尾**恢复原状**（本类提供 {@link #pending(ServerLevel, BlockPos)} 供断言）。
     */
    public static Outcome place(BotPlayer bot, BlockPos spot) {
        ServerLevel level = bot.serverLevel();
        if (!level.getBlockState(spot).isAir() || !BlockInteraction.hasPlacementFace(level, spot)
                || occupied(level, spot)) {
            return new Outcome(false, Codes.NO_SPOT, spot);
        }
        net.minecraft.world.level.block.Block wanted = bot.getMainHandItem().getItem() instanceof
                net.minecraft.world.item.BlockItem blockItem ? blockItem.getBlock() : null;
        if (wanted == null) {
            return new Outcome(false, Codes.PLACE_FAILED, spot);
        }
        WriteGrant grant = WriteGrant.of("craft-station", WriteReason.CRAFT_STATION_PLACE);
        BlockInteraction.PlaceResult result = BlockInteraction.placeAt(bot, level, spot, false, grant);
        if (result == BlockInteraction.PlaceResult.BUDGET_EXHAUSTED) {
            return new Outcome(false, Codes.BUDGET_EXHAUSTED, spot);
        }
        if (result != BlockInteraction.PlaceResult.PLACED) {
            return new Outcome(false, Codes.PLACE_FAILED, spot);
        }
        // 自断言：放下之后那一格**真的**变成了**手里那种**工作站（不靠"我说放下了"）
        if (!level.getBlockState(spot).is(wanted)) {
            return new Outcome(false, Codes.NOT_PLACED, spot);
        }
        BotLog.info("[StationPlacement] placed station={} block={} grant={}",
                spot.toShortString(), level.getBlockState(spot).getBlock().getName().getString(),
                grant.describe());
        return new Outcome(true, "", spot);
    }

    /**
     * 我方在 {@code spot} 的**未闭合临时方块**条数（0 = 已拆干净）；供夹具断言"建拆同权"。
     *
     * <p>按 **owner** 查而不是按作用域 id 查：夹具（自检任务）不在生产任务的作用域里，
     * `WorldModLedger.currentScope` 此时会是 `null`，按作用域查会**恒为 0** ⇒ 断言假通过。
     * 按 owner + `TEMP` 查是"这块地是我的临时方块吗"的**直接定义**，与作用域无关。
     */
    public static int pending(ServerLevel level, java.util.UUID owner, BlockPos spot) {
        int count = 0;
        for (WorldModLedger.Entry entry : WorldModLedger.pendingForOwner(level.getServer(), owner)) {
            if (entry.pos().equals(spot)) {
                count++;
            }
        }
        return count;
    }

    /** 我方在 {@code spot} 附近（含自身±1）的未闭合临时方块数；用于"用完是否留垃圾"的粗断言。 */
    public static int pendingNear(ServerLevel level, java.util.UUID owner, BlockPos spot, int radius) {
        int count = 0;
        for (WorldModLedger.Entry entry : WorldModLedger.pendingForOwner(level.getServer(), owner)) {
            if (entry.pos().distManhattan(spot) <= radius) {
                count++;
            }
        }
        return count;
    }

    /** 该格是否仍是工作站（用于"拆干净"断言前的对照）。 */
    public static boolean isStation(ServerLevel level, BlockPos spot, net.minecraft.world.level.block.Block block) {
        return level.getBlockState(spot).is(block);
    }

    /** 放工作站需要的物品是否在手（主手）。 */
    public static boolean holding(BotPlayer bot, net.minecraft.world.level.block.Block block) {
        ItemStack held = bot.getMainHandItem();
        return !held.isEmpty() && held.is(block.asItem());
    }

    /** 方便：工作站方块常量。 */
    public static net.minecraft.world.level.block.Block craftingTable() {
        return Blocks.CRAFTING_TABLE;
    }
}
