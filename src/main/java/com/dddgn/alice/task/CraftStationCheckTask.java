package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.RecipeQuery;
import com.dddgn.alice.task.craft.StationPlacement;
import com.dddgn.alice.task.craft.TableCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * **自放工作站合成自检**（阶段 3-A / A3b，D-190）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>A3（{@link CraftTableCheckTask}）验的是"**用现成**工作台且**零世界写入**"；A3b 验的是
 * 阶梯上**第一次真正写世界**的那一步：附近没有工作站时，**自己放**一个、用它合成、**用完拆回**。
 *
 * <p>分相位推进（跨真实 tick）：PREPARE → PLACE → PLACE_CRAFT → TEARDOWN → DONE。
 *
 * <p>用例（每条都是**世界事实**或**账本事实**，不靠"我说做了"）：
 * <ul>
 *   <li>{@code start_premise}：夹具自己把 bot 送到场景起点（{@link CraftTableCheckTask#START}）并断言到位；</li>
 *   <li>{@code holding_station_item}：主手确实是工作站物品（没有它就不可能放置）；</li>
 *   <li>{@code no_station_premise}：出手前场景里**真的没有**工作站（否则测的就不是"自己放"这条路）；</li>
 *   <li>{@code station_placed}：放置后那一格**变成工作站**（对齐手持方块，不是"发起过放置"）；</li>
 *   <li>{@code write_accounted}：账本里那次放置是**我方 TEMP**（建拆同权的前提：记了才必须拆）；</li>
 *   <li>{@code placed_table_craft}：用**自己放的**台子走 3×3 合出 1 熔炉（产物 +1）；</li>
 *   <li>{@code teardown_clean}：{@link RestoreScopeTask} 之后方块**回到空气**且账本 `pending=0`（不留垃圾）。</li>
 * </ul>
 *
 * <p>**为什么 A3b 单独一个夹具**（而不是塞进 A3）：A3 的硬断言是"零世界写入"，A3b 必然写入——
 * 两者塞进同一个 SUMMARY 会让"零写入"这条断言变成"某段之前零写入"，读日志的人分不清边界。
 * 另外拆台走 {@link RestoreScopeTask}，**预算按真实 tick 计**（基础 100 + 每块 450），
 * 与 A3 的秒级夹具不是同一个量级，混在一起会把 A3 拖成慢测试。
 */
public class CraftStationCheckTask implements Task {

    /**
     * 总预算给足：拆台走 {@link RestoreScopeTask}，它自己的预算是 `100 + 450×块数 + 600（收尾收集）`，
     * 而且那**全是真实 tick**（走位 + 破坏 + 捡回掉落物）。夹具预算若比它小，就会在"恢复其实成功"时
     * 报 `teardown_timeout` 假失败 —— 那种假失败比慢几秒贵得多。
     */
    private static final int MAX_TICKS = 2400;
    private static final int PLACE_CRAFT_TICKS = 120;
    private static final int TEARDOWN_TICKS = 1500;

    private enum Phase { PREPARE, PLACE, PLACE_CRAFT, TEARDOWN, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private BlockPos placedStation;
    private MenuSession session;
    private RestoreScopeTask restore;
    /** 恢复任务的终态理由（`RestoreScopeTask` 特有，接口没有）；失败时是唯一能区分病因的字段。 */
    private String restoreReason = "-";
    /** 失败路径的清理尝试过没有（防 `finish()` ↔ `CLEANUP` 互相递归）。 */
    private boolean cleanupAttempted;

    public CraftStationCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftStationCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(placedStation == null ? CraftTableCheckTask.START : placedStation);
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
        if (++ticks > MAX_TICKS) {
            failures.add("fixture_timeout");
            return finish();
        }
        phaseTicks++;
        return switch (phase) {
            case PREPARE -> prepare();
            case PLACE -> placeStation();
            case PLACE_CRAFT -> craftAtStation();
            case TEARDOWN -> teardownStation();
            case CLEANUP -> cleanupAfterFailure();
            case DONE -> finish();
        };
    }

    // ==================== 相位 ====================

    /**
     * **夹具自摆前提**（D-187 §6.9.1）：起点、发料、场景复位一律自己负责，随后**自断言**。
     * 独立物品入口没有人替本夹具传送（D-189 的实测教训：A3 曾从伐木场出发去够 100 格外的台子）。
     */
    private Status prepare() {
        BlockPos start = CraftTableCheckTask.START;
        bot.teleportTo(bot.serverLevel(), start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        check("start_premise", bot.blockPosition().distSqr(start) <= 4.0D,
                "foot=" + bot.blockPosition().toShortString() + " start=" + start.toShortString());
        // **上一轮失败留下的残留**先销账：场景函数把这块地清成空气了，账本里的条目已是幽灵；
        // 不销掉的话下面的 `teardown_clean`（pendingAll==0）会被上一轮的残留顶成假失败。
        int stale = WorldModLedger.dropStale(bot.serverLevel());
        if (stale > 0) {
            BotLog.info("[CraftStationCheck] 起手销掉 {} 条上一轮的幽灵账目", stale);
        }
        FixtureToolKit.resetInventory(bot);
        // **顺序要紧**：先占住选中槽放工作站（放置读主手），再发圆石到**别的空槽**。
        // 反过来写会出事：`give` 从 0 号槽找空位放进圆石，`giveHeld` 再往同一个选中槽写工作台 ⇒ 圆石被覆盖。
        giveHeld(Items.CRAFTING_TABLE, 1);
        give(Items.COBBLESTONE, 8);
        // 场景自带的台子会掩盖"周围没有工作站"这条前提 ⇒ 先确认它**不在**（不在就别找了）。
        BlockPos existing = TableCraft.findTable(bot.serverLevel(), start, 4);
        check("no_station_premise", existing == null,
                "existingTable=" + (existing == null ? "-" : existing.toShortString()));
        if (existing != null) {
            return finish();
        }
        boolean holding = StationPlacement.holding(bot, StationPlacement.craftingTable());
        check("holding_station_item", holding, "mainHand=" + bot.getMainHandItem().getItem());
        return advance(Phase.PLACE);
    }

    private Status placeStation() {
        BlockPos spot = StationPlacement.findSpot(bot.serverLevel(), bot.blockPosition(), 2);
        if (spot == null) {
            check("station_placed", false, "no_placement_spot");
            return finish();
        }
        StationPlacement.Outcome outcome = StationPlacement.place(bot, spot);
        boolean placed = outcome.ok() && StationPlacement.isStation(bot.serverLevel(), spot,
                StationPlacement.craftingTable());
        check("station_placed", placed, outcome.describe()
                + " block=" + bot.serverLevel().getBlockState(spot).getBlock().getName().getString());
        int pending = StationPlacement.pending(bot.serverLevel(), bot.getUUID(), spot);
        check("write_accounted", pending >= 1,
                "pendingTemporary=" + pending + "（建拆同权：稍后必须拆回）");
        if (!placed) {
            return finish();
        }
        placedStation = spot;
        return advance(Phase.PLACE_CRAFT);
    }

    private Status craftAtStation() {
        if (!TableCraft.inReach(bot, placedStation)) {
            check("placed_table_craft", false, "out_of_reach distance=" + String.format(Locale.ROOT,
                    "%.2f", bot.getEyePosition().distanceTo(placedStation.getCenter())));
            return advance(Phase.TEARDOWN);
        }
        if (session == null) {
            session = TableCraft.openTable(bot, placedStation);
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            session = null;
            check("placed_table_craft", false, "menu_open_failed");
            return advance(Phase.TEARDOWN);
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > PLACE_CRAFT_TICKS ? afterCraft(false, "menu_timeout") : Status.RUNNING;
        }
        var recipe = bot.getServer().getRecipeManager()
                .byKey(net.minecraft.resources.ResourceLocation.tryParse("minecraft:furnace")).orElse(null);
        if (recipe == null) {
            return afterCraft(false, "recipe_missing");
        }
        int before = RecipeQuery.countInInventory(bot, Items.FURNACE);
        TableCraft.Outcome outcome = TableCraft.craftWithMenu(bot, session, placedStation, recipe, 1);
        int after = RecipeQuery.countInInventory(bot, Items.FURNACE);
        return afterCraft(outcome.ok() && after - before == 1,
                "product+" + (after - before) + " " + outcome.describe());
    }

    private Status afterCraft(boolean ok, String detail) {
        session = null;
        check("placed_table_craft", ok, detail);
        return advance(Phase.TEARDOWN);
    }

    /**
     * **建拆同权**：用生产同一条恢复路径（{@link RestoreScopeTask}）把我方 TEMP 拆掉，
     * 然后按**世界事实 + 账本事实**双断言"拆干净"。
     */
    private Status teardownStation() {
        if (restore == null) {
            BotManager.BotSession botSession = BotManager.sessionOf(bot);
            if (botSession == null || botSession.scope() == null) {
                check("teardown_clean", false, "no_session_scope");
                return finish();
            }
            String scopeId = WorldModLedger.currentScope(bot.serverLevel().getServer(), bot.getUUID());
            restore = new RestoreScopeTask(bot, botSession.scope(), scopeId);
            return Status.RUNNING;
        }
        Task.Status status = restore.tick();
        restoreReason = restore.terminalReason();
        if (status == Task.Status.RUNNING) {
            return phaseTicks > TEARDOWN_TICKS ? finishWith("teardown_timeout") : Status.RUNNING;
        }
        restore = null;
        cleanupAttempted = true;
        BotLog.info("[CraftStationCheck] restore 终态 status={} reason={}",
                status, restoreReason == null ? "-" : restoreReason);
        boolean gone = !StationPlacement.isStation(bot.serverLevel(), placedStation,
                StationPlacement.craftingTable());
        int pending = StationPlacement.pending(bot.serverLevel(), bot.getUUID(), placedStation);
        int pendingAll = WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        check("teardown_clean", gone && pending == 0 && pendingAll == 0,
                "stationGone=" + gone + " pendingHere=" + pending + " pendingAll=" + pendingAll);
        return finish();
    }

    // ==================== 收尾 ====================

    /**
     * **失败路径的清理**：把已经写进世界的东西拆回（不追加任何用例，只保证现场干净），
     * 然后照常出 SUMMARY —— 但要如实记一条 `cleanup_on_failure`，如果连清理也没做成。
     */
    private Status cleanupAfterFailure() {
        if (restore == null) {
            BotManager.BotSession botSession = BotManager.sessionOf(bot);
            if (botSession == null || botSession.scope() == null) {
                check("cleanup_on_failure", false, "no_session_scope");
                cleanupAttempted = true;
                return finish();
            }
            String scopeId = WorldModLedger.currentScope(bot.serverLevel().getServer(), bot.getUUID());
            restore = new RestoreScopeTask(bot, botSession.scope(), scopeId);
            return Status.RUNNING;
        }
        Task.Status status = restore.tick();
        restoreReason = restore.terminalReason();
        if (status == Task.Status.RUNNING) {
            return phaseTicks > TEARDOWN_TICKS ? failCleanup("cleanup_timeout") : Status.RUNNING;
        }
        restore = null;
        int left = WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        if (left > 0 || status != Task.Status.DONE) {
            check("cleanup_on_failure", false,
                    "left=" + left + " status=" + status + " reason=" + restoreReason);
        } else {
            BotLog.info("[CraftStationCheck] 失败路径已把世界改动拆回（restore={}）", restoreReason);
        }
        return finish();
    }

    /** 清理也超时：如实记一条，然后出 SUMMARY（不再递归进 CLEANUP）。 */
    private Status failCleanup(String code) {
        check("cleanup_on_failure", false, code);
        restore = null;
        return finish();
    }

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
        BotLog.info("[CraftStationCheck] {}={} {}", name, ok ? "PASS" : "FAIL", detail);
        if (!ok) {
            failures.add(name);
        }
    }

    private Status finish() {
        // **§6.9.4 副作用边界**：任何失败路径都不许把世界改动留在身后。
        // 2026-09-13 实测就吃了这一条：`station_placed=FAIL` 直接 finish() ⇒ 那块圆石留在世界里，
        // 客户端还打了 `world_mod_ledger_close … 仍有 1 条我方临时放置未拆除`。
        if (!cleanupAttempted
                && !WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).isEmpty()) {
            cleanupAttempted = true;
            return advance(Phase.CLEANUP);
        }
        phase = Phase.DONE;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftStationCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftStationCheck] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    /** 发料并**放进当前选中的快捷栏槽**（放置读主手 = 选中槽，观感也一致）。 */
    private void giveHeld(net.minecraft.world.item.Item item, int count) {
        var inventory = bot.getInventory();
        inventory.setItem(inventory.selected, new ItemStack(item, count));
        BotManager.syncMainHand(bot);
    }

    private void give(net.minecraft.world.item.Item item, int count) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(item, count));
                return;
            }
        }
        BotLog.warn("[CraftStationCheck] 背包没有空槽，无法发料 {} x{}", item, count);
    }
}
