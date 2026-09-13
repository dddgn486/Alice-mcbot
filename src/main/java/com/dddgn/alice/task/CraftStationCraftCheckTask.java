package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.CraftStation;
import com.dddgn.alice.task.craft.GridDiscovery;
import com.dddgn.alice.task.craft.InventoryCraft;
import com.dddgn.alice.task.craft.RecipeQuery;
import com.dddgn.alice.task.craft.StationProvision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **模组站点真合成自检**（阶段 3-A / C = S1-5b，D-195）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>这一项回答的是最后那个问题：**"装一次、一直用"到底能不能真的合成出来** ——
 * 装上升级（L2）→ 用**发现出来的规格**摆料/取产物（S1-5a）→ 用完拆回（建拆同权）。
 *
 * <p>**判据一律是世界事实**（产物总量、材料消耗），**不是**某个原语的自述：
 * {@link InventoryCraft} 过去假定"产物进玩家背包"，而模组站点有 "Shift 右击将成品放入容器 或 玩家物品栏"
 * 这个开关（默认进容器）—— 所以本夹具**同时数两处**（玩家背包 + 容器），并把两维语义**如实记下来**：
 * <ul>
 *   <li>{@code product_in_player} / {@code product_in_container}：产物到底落在哪（**实测**）；</li>
 *   <li>{@code shift_click_into_storage}：那个开关的当前值（读升级物品 NBT，**实测**）；</li>
 *   <li>{@code primitive_verdict} / {@code primitive_assumption_mismatch}：原语怎么报的、它的假设有没有被现实打脸；</li>
 *   <li>{@code grid_after_craft}：合成后页签那 9 格剩什么（看它会不会自动补料）。</li>
 * </ul>
 *
 * <p>零参数入口：`alice:craft_station_craft_check`；场景 `alice_test:craft_tab_course`（自带箱子，无需手动装升级）。
 */
public class CraftStationCraftCheckTask implements Task {

    public static final BlockPos START = CraftGridProbeTask.START;
    private static final int SCAN_RADIUS = 6;
    private static final int MAX_TICKS = 1200;
    private static final int OPEN_TICKS = 60;
    /** 重试前的冷却 tick（等服务端把上一次交互收尾）。 */
    private static final int RETRY_TICKS = 10;
    /** 与 A3 同一个配方（8 圆石 → 1 熔炉），便于横向比较。 */
    private static final ResourceLocation RECIPE = ResourceLocation.withDefaultNamespace("furnace");
    private static final int COBBLESTONE_COUNT = 8;

    private enum Phase {
        PREPARE,
        INSTALL_OPEN, INSTALL_MOVE, INSTALL_VERIFY_OPEN, INSTALL_VERIFY,
        CRAFT_OPEN, CRAFT,
        CLEANUP_OPEN, CLEANUP_MOVE, CLEANUP_VERIFY_OPEN, CLEANUP_VERIFY,
        DONE
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private BlockPos station;
    private Item upgrade;
    private MenuSession session;
    /** 开菜单重试计数与冷却（见失败分支：电池里出现过服务端没开菜单的偶发情形）。 */
    private int openRetries;
    private int openRetryDelay;
    private InventoryCraft.GridSpec spec;
    private int upgradeBefore;
    private int cobbleBefore;
    private int furnaceBefore;
    private String primitiveVerdict = "-";

    public CraftStationCraftCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftStationCraftCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(station == null ? START : station);
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
            case INSTALL_OPEN -> openPhase(Phase.INSTALL_MOVE, false);
            case INSTALL_MOVE -> installMove();
            case INSTALL_VERIFY_OPEN -> openPhase(Phase.INSTALL_VERIFY, true);
            case INSTALL_VERIFY -> installVerify();
            case CRAFT_OPEN -> openPhase(Phase.CRAFT, true);
            case CRAFT -> craft();
            case CLEANUP_OPEN -> openPhase(Phase.CLEANUP_MOVE, true);
            case CLEANUP_MOVE -> cleanupMove();
            case CLEANUP_VERIFY_OPEN -> openPhase(Phase.CLEANUP_VERIFY, true);
            case CLEANUP_VERIFY -> cleanupVerify();
            case DONE -> finish();
        };
    }

    // ==================== 前提 ====================

    private Status prepare() {
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        check("start_premise", bot.blockPosition().distSqr(START) <= 4.0D,
                "foot=" + bot.blockPosition().toShortString());
        WorldModLedger.dropStale(bot.serverLevel());
        // 站点与"该装什么升级"都来自**站点描述符的数据**（不是写死的判断）
        ResourceLocation upgradeId = CraftStation.UPGRADE_TAB.provisionUpgrade();
        upgrade = upgradeId == null ? null : BuiltInRegistries.ITEM.get(upgradeId);
        check("mod_present", upgrade != null && upgrade != Items.AIR,
                "upgradeId=" + upgradeId + "（模组未装 ⇒ 本项不适用）");
        if (upgrade == null || upgrade == Items.AIR) {
            return finish();
        }
        station = findStation();
        check("station_found", station != null,
                "station=" + (station == null ? "-" : station.toShortString()));
        if (station == null) {
            return finish();
        }
        CraftStation.select(bot, CraftStation.UPGRADE_TAB.id());
        FixtureToolKit.resetInventory(bot);
        give(Items.COBBLESTONE, COBBLESTONE_COUNT);
        give(upgrade, 1);
        upgradeBefore = RecipeQuery.countInInventory(bot, upgrade);
        cobbleBefore = totalCount(Items.COBBLESTONE);
        furnaceBefore = totalCount(Items.FURNACE);
        check("fixture_gave_materials", upgradeBefore == 1 && cobbleBefore == COBBLESTONE_COUNT,
                "upgrade=" + upgradeBefore + " cobblestone=" + cobbleBefore + "（含容器的总量）");
        return advance(Phase.INSTALL_OPEN);
    }

    // ==================== 开菜单（四个相位共用） ====================

    private Status openPhase(Phase next, boolean viaSelectedStation) {
        if (openRetryDelay > 0) {
            openRetryDelay--;   // 重试前的冷却（给服务端把上一次交互收尾）
            return Status.RUNNING;
        }
        if (session == null) {
            if (station == null || !CraftStation.inReach(bot, station)) {
                check("station_in_reach", false, "station=" + (station == null ? "-" : station.toShortString()));
                return finish();
            }
            if (!bot.onGround()) {
                return phaseTicks > OPEN_TICKS ? failAndFinish("not_on_ground") : Status.RUNNING;
            }
            if (viaSelectedStation) {
                CraftStation.Opened opened = CraftStation.open(bot, SCAN_RADIUS);
                if (!opened.ok()) {
                    check("menu_open_failed", false, opened.describe());
                    return finish();
                }
                session = opened.session();
            } else {
                session = MenuSession.open(bot, station, 0);
            }
            return Status.RUNNING;   // 开启与 tick 分相位（K-3 空中硬拒）
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            String failure = session.failure();
            String menu = bot.containerMenu == null ? "-" : bot.containerMenu.getClass().getSimpleName();
            BotLog.warn("[{}] 开菜单失败 code={} 当前菜单={} 已重试={}", "CraftStationCraft",
                    failure, menu, openRetries);
            session = null;
            if (openRetries < 1) {
                openRetries++;
                record("open_retries", String.valueOf(openRetries));
                openRetryDelay = RETRY_TICKS;
                phaseTicks = 0;      // 重试要从零计时，否则会立刻撞上本相位的超时预算
                return Status.RUNNING;
            }
            return failAndFinish("menu_open_failed");
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("menu_open_timeout") : Status.RUNNING;
        }
        return advance(next);
    }

    // ==================== L2 装配 ====================

    private Status installMove() {
        if (!StationProvision.allowContainerWrite(bot, station)) {
            return failAndFinish(StationProvision.Codes.BUDGET_REFUSED);
        }
        boolean moved = StationProvision.moveIntoContainer(bot, bot.containerMenu, upgrade, station);
        record("install_move_accepted", String.valueOf(moved));
        closeSession("install_moved");
        return advance(Phase.INSTALL_VERIFY_OPEN);
    }

    private Status installVerify() {
        GridDiscovery.Result discovery = GridDiscovery.discover(bot.containerMenu, bot);
        boolean grid = discovery.ok() && discovery.spec().width() >= 3 && discovery.spec().height() >= 3;
        check("provision_verified", grid, discovery.describe());
        if (!grid) {
            return finish();
        }
        spec = discovery.spec();
        record("grid_slots", java.util.Arrays.toString(spec.gridSlots()));
        record("result_slot", String.valueOf(spec.resultSlot()));
        closeSession("provision_verified");
        return advance(Phase.CRAFT_OPEN);
    }

    // ==================== 真合成（判据 = 世界事实） ====================

    private Status craft() {
        AbstractContainerMenu menu = bot.containerMenu;
        var recipe = bot.getServer().getRecipeManager().byKey(RECIPE).orElse(null);
        if (recipe == null) {
            return failAndFinish("recipe_missing");
        }
        // **两维语义之一**：那个"Shift 右击将成品放入容器/玩家物品栏"的开关（读升级物品 NBT）
        record("shift_click_into_storage", String.valueOf(shiftClickIntoStorage(menu)));
        int cobbleBeforeCraft = totalCount(Items.COBBLESTONE);
        int furnaceBeforeCraft = totalCount(Items.FURNACE);

        InventoryCraft.Result primitive = InventoryCraft.craft(bot, menu, recipe, 1, spec);
        primitiveVerdict = primitive.describe();
        record("primitive_verdict", primitiveVerdict);

        int cobbleAfter = totalCount(Items.COBBLESTONE);
        int furnaceAfter = totalCount(Items.FURNACE);
        int inPlayer = RecipeQuery.countInInventory(bot, Items.FURNACE);
        int inContainer = StationProvision.countInContainer(menu, bot, Items.FURNACE);

        int consumed = cobbleBeforeCraft - cobbleAfter;
        int produced = furnaceAfter - furnaceBeforeCraft;
        record("cobblestone_consumed", String.valueOf(consumed));
        record("furnace_produced", String.valueOf(produced));
        record("product_in_player", String.valueOf(inPlayer));
        record("product_in_container", String.valueOf(inContainer));
        record("grid_after_craft", gridContents(menu));
        check("materials_consumed", consumed == COBBLESTONE_COUNT, "consumed=" + consumed
                + "（期望 " + COBBLESTONE_COUNT + "，含容器）");
        check("product_produced", produced == 1, "produced=" + produced);
        // 原语的自述与现实不一致时**如实记录**（不是失败）：这正是"产物去向"这一维要测的东西
        boolean mismatch = !primitive.ok() && produced == 1;
        record("primitive_assumption_mismatch", String.valueOf(mismatch));
        if (mismatch) {
            BotLog.warn("[CraftStationCraft] 原语报 {} 但世界事实是产物 +1 ⇒ 它的\"产物进玩家背包\"假设"
                            + "在这个站点上不成立（产物去向：玩家 {} / 容器 {}）",
                    primitive.code(), inPlayer, inContainer);
        }
        closeSession("crafted");
        if (produced != 1 || consumed != COBBLESTONE_COUNT) {
            return advance(Phase.CLEANUP_OPEN);   // 失败也要清场（把升级拆回）
        }
        return advance(Phase.CLEANUP_OPEN);
    }

    // ==================== 建拆同权：拆回 ====================

    private Status cleanupMove() {
        if (!StationProvision.allowContainerWrite(bot, station)) {
            return failAndFinish(StationProvision.Codes.BUDGET_REFUSED);
        }
        boolean moved = StationProvision.moveOutOfContainer(bot, bot.containerMenu, upgrade);
        record("cleanup_move_accepted", String.valueOf(moved));
        closeSession("cleanup_moved");
        return advance(Phase.CLEANUP_VERIFY_OPEN);
    }

    private Status cleanupVerify() {
        GridDiscovery.Result after = GridDiscovery.discover(bot.containerMenu, bot);
        int inInventory = RecipeQuery.countInInventory(bot, upgrade);
        check("deprovision_verified", !after.ok(), after.describe());
        check("upgrade_returned", inInventory == upgradeBefore,
                "inInventory=" + inInventory + "（期望 " + upgradeBefore + "）");
        closeSession("cleanup_verified");
        return finish();
    }

    // ==================== 工具 ====================

    /** 产物/材料的**总量** = 玩家背包 + **当前菜单容器**（两处都数，避免"产物落到容器"被误判成失败）。 */
    private int totalCount(Item item) {
        int total = RecipeQuery.countInInventory(bot, item);
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu != null) {
            total += StationProvision.countInContainer(menu, bot, item);
        }
        return total;
    }

    /** 页签 9 格现在的样子（看会不会自动补料）。 */
    private String gridContents(AbstractContainerMenu menu) {
        if (spec == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int address : spec.gridSlots()) {
            for (var slot : GridDiscovery.scan(menu).slots()) {
                if (slot.index != address) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(stack.isEmpty() ? "-"
                        : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + "x" + stack.getCount());
            }
        }
        return sb.toString();
    }

    /**
     * **实测**那个开关：`shiftClickIntoStorage`（读升级物品 NBT）。
     *
     * <p>读法：菜单 → `storageWrapper` → `getUpgradeHandler()` → 槽 0 的升级物品 → NBT。
     * 全是**模组/Forge**成员（名字不重映射）；对 vanilla 成员绝不用字符串反射（D-192 附注五）。
     */
    private boolean shiftClickIntoStorage(AbstractContainerMenu menu) {
        Object wrapper = readField(menu, "storageWrapper");
        Object handler = call(wrapper, "getUpgradeHandler");
        Object stack = callWith(handler, "getStackInSlot", 0);
        if (stack instanceof ItemStack itemStack && itemStack.getTag() != null) {
            return itemStack.getTag().getBoolean("shiftClickIntoStorage");
        }
        return false;
    }

    private BlockPos findStation() {
        BlockPos center = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(bot.serverLevel().getBlockState(pos).getBlock());
            if (key == null || !"sophisticatedstorage".equals(key.getNamespace())) {
                continue;
            }
            String path = key.getPath();
            if (!(path.contains("chest") || path.contains("barrel") || path.contains("shulker"))) {
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
        BotLog.info("[CraftStationCraft] {}={} {}", name, ok ? "true" : "false", detail);
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
        BotLog.info("[CraftStationCraft] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftStationCraft] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    private void give(Item item, int count) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(item, count));
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);
                return;
            }
        }
        BotLog.warn("[CraftStationCraft] 背包没有空槽，无法发料 {}", item);
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target == null ? null : target.getClass();
        while (type != null && type != Object.class) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                var method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object callWith(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = int.class;
        }
        while (type != null && type != Object.class) {
            try {
                var method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
