package com.dddgn.alice.task.craft;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * **用现成工作台的 3×3 合成**（阶段 3-A / A3，D-188）。**零世界写入**：只找、只走过去、只开菜单。
 *
 * <p>为什么先只做"用现成"：放置工作台属于**世界写入**（D-076：必须显式授权 + 预算 + 进 A 表），
 * 而"附近本来就有工作台"是最常见的真实情形（玩家基地、村庄、废弃矿井）。把零写入的路径先做扎实，
 * 写路径（放置 + 用完即拆）留到 A3b 单独验账本闭环。
 *
 * <p>流程：找最近工作台 → 找它旁边的可站格（{@link MovementHelper#canStandCentered}，与内核同口径）
 * → 走到那格（{@code PathRequest.of} 纯通行 + {@code PathRetryRunner}）→ 触及校验
 * （`eyeDistance <= blockReach`，与传输任务同口径）→ {@link MenuSession#open} 开菜单 →
 * 用 {@link InventoryCraft#craft} 的 **3×3 spec** 摆料/取产物 → 关菜单。
 */
public final class TableCraft {

    /** 失败码。 */
    public static final class Codes {
        public static final String NO_TABLE = "no_crafting_table";
        public static final String NO_STANDING_POINT = "table_no_standing_point";
        public static final String WALK_FAILED = "walk_failed";
        public static final String OUT_OF_REACH = "table_out_of_reach";
        public static final String MENU_FAILED = "table_menu_failed";
        public static final String MENU_SHAPE_UNEXPECTED = "table_menu_shape_unexpected";

        private Codes() {
        }
    }

    /** 工作台菜单（`CraftingMenu`）的格网规格：0=结果，1..9=3×3 网格，10..45=玩家背包+快捷栏。 */
    public static InventoryCraft.GridSpec tableSpec() {
        return new InventoryCraft.GridSpec(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, 3, 3, 0, 10, 45);
    }

    /** 一次"到工作台合成"的结果。 */
    public record Outcome(boolean ok, String code, int produced, String table) {
        public String describe() {
            return (ok ? "OK" : "FAIL:" + code) + " produced=" + produced + " table=" + table;
        }
    }

    private TableCraft() {
    }

    /** 在 {@code center} 周围 {@code radius} 内找最近的工作台（只读遍历；找不到返回 null）。 */
    public static BlockPos findTable(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                continue;
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 工作台旁边**现在就能站**的格子（与内核 `canStandCentered` 同口径）；找不到返回 null。 */
    public static BlockPos standPointNear(ServerLevel level, BlockPos table) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos delta : new BlockPos[]{new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 1, 1),
                new BlockPos(0, 1, -1), new BlockPos(1, 1, 0), new BlockPos(-1, 1, 0)}) {
            candidates.add(table.offset(delta));
        }
        for (BlockPos candidate : candidates) {
            if (MovementHelper.canStandCentered(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 触及校验（与传输任务的 L1 同口径：眼位到方块中心的距离 ≤ 方块触及）。 */
    public static boolean inReach(BotPlayer bot, BlockPos table) {
        return bot.getEyePosition().distanceTo(table.getCenter()) <= bot.getBlockReach() + 1.0D;
    }

    /** 开工作台菜单（**调用方负责在自己的 tick 里推进 session**，直到 {@code OPEN}/{@code FAILED}）。 */
    public static MenuSession openTable(BotPlayer bot, BlockPos table) {
        return MenuSession.open(bot, table, 46);
    }

    /**
     * **已经开到菜单**之后摆料 + 取产物（3×3）。
     *
     * <p>前置：`bot.containerMenu` 必须是 {@link CraftingMenu}（由本方法**自断言**——
     * 不是 3×3 菜单就说明"3×3 合成"这件事根本没被测到，必须如实失败而不是照做）。
     */
    public static Outcome craftWithMenu(BotPlayer bot, MenuSession session, BlockPos table,
                                        Recipe<?> recipe, int count) {
        if (!(bot.containerMenu instanceof CraftingMenu)) {
            BotLog.warn("[TableCraft] 菜单不是 CraftingMenu：{}", bot.containerMenu.getClass().getSimpleName());
            session.close("craft_menu_shape");
            return new Outcome(false, Codes.MENU_SHAPE_UNEXPECTED, 0, table.toShortString());
        }
        InventoryCraft.Result crafted = InventoryCraft.craft(bot, bot.containerMenu, recipe, count, tableSpec());
        session.close("craft_done");
        if (!crafted.ok()) {
            BotLog.warn("[TableCraft] 合成失败 table={} code={} {}", table.toShortString(),
                    crafted.code(), crafted.describe());
            return new Outcome(false, crafted.code(), crafted.produced(), table.toShortString());
        }
        return new Outcome(true, "", crafted.produced(), table.toShortString());
    }
}
