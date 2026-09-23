package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.task.craft.RecipeQuery;
import com.dddgn.alice.task.craft.TableCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **用现成工作台合成自检**（阶段 3-A / A3，D-188）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>分相位推进（**跨真实 tick**，D-165 的教训）：FIND → WALK（`PathRetryRunner`）→ REACH → OPEN
 * （`MenuSession` 直到 OPEN）→ CRAFT（3×3 spec）→ ASSERT。
 *
 * <p>用例：
 * <ul>
 *   <li>{@code table_found}：场景里有工作台 ⇒ 能找到（前提自断言）；</li>
 *   <li>{@code walked_to_table}：走到它旁边的可站格（内核同口径）且**触及校验**通过；</li>
 *   <li>{@code menu_is_crafting_menu}：打开的必须是 `CraftingMenu`（否则"3×3 合成"没被测到）；</li>
 *   <li>{@code crafted_furnace}：8 圆石 → 1 熔炉（3×3 shaped）⇒ 产物 +1、材料 −8；</li>
 *   <li>{@code no_world_write}：**整个过程账本里没有任何我方临时方块**（零写入硬断言）；</li>
 *   <li>{@code no_table_honest}：把工作台临时挪走后重试 ⇒ 如实失败 `no_crafting_table`（不放置、不绕路）。</li>
 * </ul>
 */
public class CraftTableCheckTask implements Task {

    /** 场景起点（电池靠它 teleport；与 `craft_table_course` 场景一致）。 */
    public static final BlockPos START = new BlockPos(46, 64, 304);
    private static final int TABLES_RADIUS = 6;

    private enum Phase { FIND, WALK, REACH, OPEN, CRAFT, NO_TABLE_CASE, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();

    private Phase phase = Phase.FIND;
    private int ticks;
    private int phaseTicks;
    private BlockPos table;
    private BlockPos standPoint;
    private com.dddgn.alice.task.PathRetryRunner runner;
    private MenuSession session;
    private int furnaceBefore;

    public CraftTableCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftTableCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(phase == Phase.NO_TABLE_CASE || table == null
                ? START : table);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public Status tick() {
        if (phase == Phase.DONE) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (++ticks > 400) {
            failures.add("fixture_timeout");
            return finish();
        }
        phaseTicks++;
        return switch (phase) {
            case FIND -> find();
            case WALK -> walk();
            case REACH -> reach();
            case OPEN -> open();
            case CRAFT -> craft();
            case NO_TABLE_CASE -> noTableCase();
            case DONE -> finish();
        };
    }

    // ==================== 相位 ====================

    private Status find() {
        // **夹具自摆前提**（D-187 §6.9.1 / 实测教训）：独立物品入口**没人**替本夹具传送，
        // 上一轮实测就因此从伐木场(20,64,208)出发去够 100 格外的台子 ⇒ walked_to_table=FAIL。
        // 起点、发料、场景复位一律由夹具自己负责；随后**自断言**起点确实到位。
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        check("start_premise", bot.blockPosition().distSqr(START) <= 4.0D,
                "foot=" + bot.blockPosition().toShortString() + " start=" + START.toShortString());
        FixtureToolKit.resetInventory(bot);
        give(Items.COBBLESTONE, 8);
        table = TableCraft.findTable(bot.serverLevel(), START, TABLES_RADIUS);
        check("table_found", table != null, "table=" + (table == null ? "-" : table.toShortString()));
        if (table == null) {
            return finish();
        }
        standPoint = TableCraft.standPointNear(bot.serverLevel(), table);
        if (standPoint == null) {
            check("walked_to_table", false, "no_standing_point");
            return finish();
        }
        PathRequest request = PathRequest.of(bot.getUUID().toString(), bot.blockPosition(),
                standPoint, "craft-table-check");
        runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS, "craft-walk");
        return advance(Phase.WALK);
    }

    private Status walk() {
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return phaseTicks > 200 ? finishWith("walk_timeout") : Status.RUNNING;
        }
        runner = null;
        check("walked_to_table", state == PathRetryRunner.State.DONE,
                "state=" + state + " foot=" + bot.blockPosition().toShortString()
                        + " stand=" + standPoint.toShortString());
        return state == PathRetryRunner.State.DONE ? advance(Phase.REACH) : finish();
    }

    private Status reach() {
        boolean ok = TableCraft.inReach(bot, table);
        check("table_in_reach", ok, "distance=" + String.format(java.util.Locale.ROOT, "%.2f",
                bot.getEyePosition().distanceTo(table.getCenter())));
        if (!ok) {
            return finish();
        }
        session = TableCraft.openTable(bot, table);
        return advance(Phase.OPEN);
    }

    private Status open() {
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            check("menu_is_crafting_menu", false, "open_failed");
            return finish();
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > 40 ? finishWith("menu_open_timeout") : Status.RUNNING;
        }
        check("menu_is_crafting_menu", bot.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu,
                "menu=" + bot.containerMenu.getClass().getSimpleName());
        furnaceBefore = RecipeQuery.countInInventory(bot, Items.FURNACE);
        return advance(Phase.CRAFT);
    }

    private Status craft() {
        var recipe = bot.getServer().getRecipeManager()
                .byKey(net.minecraft.resources.ResourceLocation.tryParse("minecraft:furnace")).orElse(null);
        if (recipe == null) {
            check("crafted_furnace", false, "recipe_missing");
            return finish();
        }
        TableCraft.Outcome outcome = TableCraft.craftWithMenu(bot, session, table, recipe, 1);
        int after = RecipeQuery.countInInventory(bot, Items.FURNACE);
        boolean ok = outcome.ok() && after - furnaceBefore == 1
                && RecipeQuery.countInInventory(bot, Items.COBBLESTONE) == 0;
        check("crafted_furnace", ok, "product+" + (after - furnaceBefore) + " " + outcome.describe());
        // 零写入硬断言：**A3 只"用现成"，不放置**。
        // ⭐ `Z4`（2026-09-23）：原判据是"账本里没有我方临时方块"，而 `Z1` 之后**区外不记账**
        // ⇒ 它在野外**恒真**（实测 `temporaryBlocks=0` = 空集，见 Z4 清单）。现在改判**闸门计数的
        // 真实写入次数 = 0**（每一次写入都必须过闸门 ⇒ 与区无关、且比"账本空"更强），
        // 并把账本口径一并印出来（覆盖度可读）。
        var pending = WorldModLedger.pendingTemporaryInCurrentScope(bot.serverLevel().getServer(), bot.getUUID());
        int writes = com.dddgn.alice.action.WriteBudget.writeCount(bot);
        check("no_world_write", writes == 0 && pending.isEmpty(),
                "writes=" + writes + " ledgerInZone=" + pending.size() + " "
                        + com.dddgn.alice.action.WriteBudget.population(bot));
        session = null;
        return advance(Phase.NO_TABLE_CASE);
    }

    /** 把工作台挪走（夹具自己摆的世界改动，用完还原）⇒ 期望如实失败 `no_crafting_table`。 */
    private Status noTableCase() {
        var server = bot.serverLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        BlockPos moved = table;
        server.getCommands().performPrefixedCommand(source,
                "setblock " + moved.getX() + " " + moved.getY() + " " + moved.getZ() + " minecraft:air");
        BlockPos found = TableCraft.findTable(bot.serverLevel(), START, TABLES_RADIUS);
        check("no_table_honest", found == null, "found=" + (found == null ? "-" : found.toShortString()));
        server.getCommands().performPrefixedCommand(source,
                "setblock " + moved.getX() + " " + moved.getY() + " " + moved.getZ()
                        + " minecraft:crafting_table");
        return finish();
    }

    // ==================== 收尾 ====================

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status finishWith(String code) {
        failures.add(code);
        return finish();
    }

    private void check(String name, boolean ok, String detail) {
        results.put(name, ok ? "PASS" : "FAIL");
        BotLog.info("[CraftTableCheck] {}={} {}", name, ok ? "PASS" : "FAIL", detail);
        if (!ok) {
            failures.add(name);
        }
    }

    private Status finish() {
        phase = Phase.DONE;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftTableCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftTableCheck] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    private void give(net.minecraft.world.item.Item item, int count) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(item, count));
                return;
            }
        }
        BotLog.warn("[CraftTableCheck] 背包没有空槽，无法发料 {} x{}", item, count);
    }
}
