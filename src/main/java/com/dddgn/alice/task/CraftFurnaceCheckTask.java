package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.FurnaceStation;
import com.dddgn.alice.task.craft.GridDiscovery;
import com.dddgn.alice.task.craft.RecipeQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **熔炉自检**（阶段 3-A / A4，D-196）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>验的是**另一种执行形状** —— "点一下就出"（合成）之外，还有"**按时间工作**"的机器：
 * <ol>
 *   <li>**认炉子**（零写死下标）：菜单里恰好 3 格的容器 + 菜单含 `ContainerData`（= 会按时间工作）；</li>
 *   <li>放输入 + 放燃料（走菜单协议，各 1 个；燃料是否可用由 `ForgeHooks.getBurnTime` 给事实）；</li>
 *   <li>**等它烧**（真 tick，读 `ContainerData` 的进度作为过程证据）；</li>
 *   <li>取产物（shift-click 回背包）；</li>
 *   <li>**失败不留半成品**：超时就把输入取回、并如实报"没烧成"。</li>
 * </ol>
 *
 * <p>判据一律**世界事实**：产物 +1（背包+容器）、输入 −1、炉内不留东西。
 *
 * <p>零参数入口：`alice:craft_furnace_check`；场景 `alice_test:furnace_course`（孤立平台 + 一个原版熔炉）。
 */
public class CraftFurnaceCheckTask implements Task {

    public static final BlockPos START = new BlockPos(46, 64, 304);
    private static final int SCAN_RADIUS = 6;
    private static final int MAX_TICKS = 1200;
    private static final int OPEN_TICKS = 60;
    /** 烧炼等待预算（原版 200 tick/个；给足余量）。 */
    private static final int SMELT_BUDGET_TICKS = 420;
    private static final ResourceLocation SMELT_RECIPE = ResourceLocation.withDefaultNamespace("stone");

    private enum Phase { PREPARE, OPEN, DISCOVER, PLACE, WAIT, TAKE, ASSERT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private BlockPos furnace;
    private MenuSession session;
    private FurnaceStation.Found found;
    private int stoneBefore;
    private int cobbleBefore;
    private int openRetries;

    public CraftFurnaceCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftFurnaceCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(furnace == null ? START : furnace);
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
            case OPEN -> open();
            case DISCOVER -> discover();
            case PLACE -> place();
            case WAIT -> waitSmelt();
            case TAKE -> take();
            case ASSERT -> assertResult();
            case DONE -> finish();
        };
    }

    // ==================== 相位 ====================

    private Status prepare() {
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        check("start_premise", bot.blockPosition().distSqr(START) <= 4.0D,
                "foot=" + bot.blockPosition().toShortString());
        WorldModLedger.dropStale(bot.serverLevel());
        furnace = findFurnace();
        check("furnace_found", furnace != null,
                "furnace=" + (furnace == null ? "-" : furnace.toShortString()) + " radius=" + SCAN_RADIUS);
        if (furnace == null) {
            return finish();
        }
        FixtureToolKit.resetInventory(bot);
        give(Items.COBBLESTONE, 3);
        give(Items.COAL, 2);
        // **夹具纪律**：手里别握着会被右键消费的东西（A4 只需空手右键开炉子）
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.selected = slot;
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);
                break;
            }
        }
        stoneBefore = totalCount(Items.STONE);
        cobbleBefore = totalCount(Items.COBBLESTONE);
        // 燃料事实：原版 API 给出"这玩意儿能烧多久"（不猜）
        int burn = net.minecraftforge.common.ForgeHooks.getBurnTime(new ItemStack(Items.COAL),
                net.minecraft.world.item.crafting.RecipeType.SMELTING);
        record("fuel_burn_ticks", String.valueOf(burn));
        var recipe = bot.getServer().getRecipeManager().byKey(SMELT_RECIPE).orElse(null);
        record("smelt_recipe", recipe == null ? "-" : SMELT_RECIPE.toString());
        check("fixture_gave_materials", cobbleBefore == 3 && burn > 0,
                "cobblestone=" + cobbleBefore + " burnTicks=" + burn);
        return advance(Phase.OPEN);
    }

    private Status open() {
        if (session == null) {
            if (!bot.onGround()) {
                return phaseTicks > OPEN_TICKS ? failAndFinish("not_on_ground") : Status.RUNNING;
            }
            session = MenuSession.open(bot, furnace, 0);
            return Status.RUNNING;
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            String failure = session.failure();
            BotLog.warn("[CraftFurnaceCheck] 开炉子失败 code={} 已重试={}", failure, openRetries);
            session = null;
            if (openRetries < 1) {
                openRetries++;
                phaseTicks = 0;
                return Status.RUNNING;
            }
            return failAndFinish("menu_open_failed:" + failure);
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("menu_open_timeout") : Status.RUNNING;
        }
        return advance(Phase.DISCOVER);
    }

    private Status discover() {
        FurnaceStation.Result result = FurnaceStation.discover(bot.containerMenu);
        record("discover", result.describe());
        check("furnace_slots_discovered", result.ok(), result.describe());
        if (!result.ok()) {
            closeSession("no_furnace");
            return finish();
        }
        found = result.found();
        return advance(Phase.PLACE);
    }

    private Status place() {
        AbstractContainerMenu menu = bot.containerMenu;
        boolean input = FurnaceStation.placeOne(bot, menu, found, found.input(), Items.COBBLESTONE);
        boolean fuel = FurnaceStation.placeOne(bot, menu, found, found.fuel(), Items.COAL);
        record("input_placed", String.valueOf(input));
        record("fuel_placed", String.valueOf(fuel));
        check("input_and_fuel_placed", input && fuel, "input=" + input + " fuel=" + fuel);
        if (!input || !fuel) {
            return advance(Phase.ASSERT);
        }
        return advance(Phase.WAIT);
    }

    private Status waitSmelt() {
        ItemStack output = FurnaceStation.stackAt(bot.containerMenu, found.output());
        FurnaceStation.Result now = FurnaceStation.discover(bot.containerMenu);
        if (now.ok() && phaseTicks % 40 == 0) {
            BotLog.info("[CraftFurnaceCheck] 进度 {}%（{}tick）", now.found().percent(), phaseTicks);
        }
        if (output != null && !output.isEmpty()) {
            record("smelt_ticks", String.valueOf(phaseTicks));
            record("progress_at_done", now.ok() ? String.valueOf(now.found().percent()) : "-");
            return advance(Phase.TAKE);
        }
        if (phaseTicks > SMELT_BUDGET_TICKS) {
            // **失败不留半成品**：把输入取回背包（燃料烧掉就烧掉，如实记）
            boolean back = FurnaceStation.takeAll(bot, bot.containerMenu, found.input());
            record("timeout_input_returned", String.valueOf(back));
            check("smelted", false, "超时 " + phaseTicks + " tick，输入已取回=" + back);
            return advance(Phase.ASSERT);
        }
        return Status.RUNNING;
    }

    private Status take() {
        boolean taken = FurnaceStation.takeAll(bot, bot.containerMenu, found.output());
        record("output_taken", String.valueOf(taken));
        return advance(Phase.ASSERT);
    }

    private Status assertResult() {
        AbstractContainerMenu menu = bot.containerMenu;
        int stoneAfter = totalCount(Items.STONE);
        int cobbleAfter = totalCount(Items.COBBLESTONE);
        ItemStack input = FurnaceStation.stackAt(menu, found.input());
        ItemStack fuel = FurnaceStation.stackAt(menu, found.fuel());
        ItemStack output = FurnaceStation.stackAt(menu, found.output());
        record("stone_delta", String.valueOf(stoneAfter - stoneBefore));
        record("cobblestone_delta", String.valueOf(cobbleAfter - cobbleBefore));
        record("input_left", input == null || input.isEmpty() ? "0" : String.valueOf(input.getCount()));
        record("fuel_left", fuel == null || fuel.isEmpty() ? "0" : String.valueOf(fuel.getCount()));
        record("output_left", output == null || output.isEmpty() ? "0" : String.valueOf(output.getCount()));
        if (!failures.contains("smelted")) {
            check("smelted", stoneAfter - stoneBefore == 1, "stone+" + (stoneAfter - stoneBefore));
        }
        check("input_consumed", cobbleAfter - cobbleBefore == -1,
                "cobblestone" + (cobbleAfter - cobbleBefore));
        check("no_half_products", (input == null || input.isEmpty()) && (output == null || output.isEmpty()),
                "inputLeft=" + (input == null ? "-" : input.getCount())
                        + " outputLeft=" + (output == null ? "-" : output.getCount()));
        closeSession("furnace_done");
        return finish();
    }

    // ==================== 工具 ====================

    private int totalCount(net.minecraft.world.item.Item item) {
        int total = RecipeQuery.countInInventory(bot, item);
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu != null) {
            for (var slot : GridDiscovery.scan(menu).slots()) {
                if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private BlockPos findFurnace() {
        BlockPos center = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (!bot.serverLevel().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FURNACE)) {
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

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status failAndFinish(String code) {
        check(code, false, "");
        return finish();
    }

    private void closeSession(String reason) {
        if (session != null) {
            session.close(reason);
            session = null;
        }
    }

    private void record(String key, String value) {
        facts.put(key, value);
    }

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[CraftFurnaceCheck] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }

    private Status finish() {
        phase = Phase.DONE;
        closeSession("fixture_done");
        int pending = WorldModLedger.pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        record("no_block_writes", String.valueOf(pending == 0));
        if (pending != 0) {
            failures.add("no_block_writes");
        }
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftFurnaceCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftFurnaceCheck] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    private void give(net.minecraft.world.item.Item item, int count) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(item, count));
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);
                return;
            }
        }
        BotLog.warn("[CraftFurnaceCheck] 背包没有空槽，无法发料 {}", item);
    }
}
