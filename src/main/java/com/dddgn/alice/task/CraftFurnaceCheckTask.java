package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.FurnaceStation;
import com.dddgn.alice.task.craft.GridDiscovery;
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

    private enum Phase { PREPARE, OPEN, DISCOVER, PLACE, WAIT, TAKE, ASSERT, CLEANUP, DEPROVISION, DONE }

    /** true = 测**菜单型炉子**（"熔炼升级页签"，A4b）；false = 原版方块熔炉（A4）。 */
    private final boolean upgradeTab;
    private static final ResourceLocation SMELTING_UPGRADE =
            ResourceLocation.fromNamespaceAndPath("sophisticatedstorage", "smelting_upgrade");

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
    private boolean provisionAttempted;
    private boolean provisionReload;
    private net.minecraft.world.item.Item upgradeItem;

    public CraftFurnaceCheckTask(BotPlayer bot, ServerPlayer observer) {
        this(bot, observer, false);
    }

    /**
     * @param upgradeTab true = 菜单型炉子（A4b）：站点是**精妙容器**，先装配"熔炼升级"（L2），
     *                   用完拆回；false = 原版方块熔炉（A4）
     */
    public CraftFurnaceCheckTask(BotPlayer bot, ServerPlayer observer, boolean upgradeTab) {
        this.bot = bot;
        this.observer = observer;
        this.upgradeTab = upgradeTab;
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
            case CLEANUP -> cleanupFurnace();
            case DEPROVISION -> deprovision();
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
        furnace = upgradeTab ? findStationBlock() : findFurnace();
        check(upgradeTab ? "station_found" : "furnace_found", furnace != null,
                "furnace=" + (furnace == null ? "-" : furnace.toShortString()) + " radius=" + SCAN_RADIUS);
        if (furnace == null) {
            return finish();
        }
        FixtureToolKit.resetInventory(bot);
        give(upgradeTab ? Items.SAND : Items.COBBLESTONE, 3);
        give(Items.COAL, 2);
        if (upgradeTab) {
            Item upgrade = BuiltInRegistries.ITEM.get(SMELTING_UPGRADE);
            check("mod_present", upgrade != null && upgrade != Items.AIR, "upgradeId=" + SMELTING_UPGRADE);
            if (upgrade == null || upgrade == Items.AIR) {
                return finish();
            }
            give(upgrade, 1);
            this.upgradeItem = upgrade;
        }
        // **夹具纪律**：手里别握着会被右键消费的东西（A4 只需空手右键开炉子）
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.selected = slot;
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);
                break;
            }
        }
        stoneBefore = totalCount(upgradeTab ? Items.GLASS : Items.STONE);
        cobbleBefore = totalCount(upgradeTab ? Items.SAND : Items.COBBLESTONE);
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
        if (state == MenuSession.State.OPEN && upgradeTab && !provisionAttempted) {
            provisionAttempted = true;
            provision();
            return Status.RUNNING;
        }
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

    /** A4b：**先把"熔炼升级"装进去**（L2 装配层；已有旧装配先取回），再让 DISCOVER 用能力验证它。 */
    private void provision() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (StationProvision.containerHas(menu, bot, upgradeItem)) {
            StationProvision.moveOutOfContainer(bot, menu, upgradeItem);   // 前提：先清干净
        }
        if (!StationProvision.allowContainerWrite(bot, furnace)) {
            record("provision", StationProvision.Codes.BUDGET_REFUSED);
            return;
        }
        boolean moved = StationProvision.moveIntoContainer(bot, menu, upgradeItem, furnace);
        record("install_move_accepted", String.valueOf(moved));
        closeSession("provisioned");
        // 关掉再开（上游在菜单构造时才按升级建容器）⇒ 用一个新相位重开
        phase = Phase.OPEN;
        phaseTicks = 0;
        provisionReload = true;
        session = null;
    }

    private Status discover() {
        // **前提自证**（D-201 附注一 / D-203 附注二）：本步**自己刚打开了站点菜单**，所以要断言的是
        // "**站点菜单确实开着**"（而不是 ownMenu！—— 2026-09-13 实测：写成 ownMenu 会假红）。
        // 意义：若站点没开成，后面的点击会落到玩家背包上，症状是"料进了却不烧"。
        FixturePremise.Fact station = FixturePremise.stationMenuOpen(bot);
        check(station.name(), station.ok(), station.detail());
        FurnaceStation.Result result = FurnaceStation.discover(bot.containerMenu);
        record("discover", result.describe());
        check(upgradeTab ? "provision_verified" : "furnace_slots_discovered", result.ok(), result.describe());
        if (!result.ok()) {
            closeSession("no_furnace");
            return finish();
        }
        found = result.found();
        return advance(Phase.PLACE);
    }

    /**
     * **容器写入授权**（R1 收口，2026-09-14）：往炉子里放料 / 取回，与 G5/A11 同源，走 `WriteBudget`
     * 的**容器写入维度**（+ 策略表 `P-06/P-10` 的声明判定）。
     *
     * <p>为什么按**相位**计而不是每次点击一次：预算单位是"这一次写入"，而本夹具的一个相位
     * （放料 = 输入+燃料两次点击；回收 = 燃料/输入/产物三次点击）在语义上是**一次**场景动作，
     * 粒度过细只会把预算噪音化。归因 requester = 本夹具（{@code CraftFurnaceCheck} ⇒ 派生规则
     * 命中 "check" ⇒ {@code DIAGNOSTIC} 行，该行声明全集）⇒ **不会**因"未声明理由"被策略拒；
     * 能拒它的只有预算耗尽，那必须如实上报（不许绕过）。
     */
    /** 容器写入的授权对象（与 {@link #allowContainerWrite} **同一份口径**）—— 供写入原语做编译期强制（T1/R-5）。 */
    private com.dddgn.alice.action.WriteGrant containerGrant() {
        return com.dddgn.alice.action.WriteGrant.of(
                taskName(), com.dddgn.alice.action.WriteReason.CONTAINER_TRANSFER);
    }

    private boolean allowContainerWrite(String what) {
        com.dddgn.alice.action.WriteGrant grant = containerGrant();
        com.dddgn.alice.action.WriteBudget.Verdict verdict =
                com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot, furnace, grant);
        if (verdict == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
            record("container_write_refused", what);
            check("container_write_allowed", false,
                    what + " 被策略/预算拒绝（grant=" + grant.describe() + "）");
            return false;
        }
        return true;
    }

    private Status place() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (!allowContainerWrite("place")) {
            return advance(Phase.ASSERT);
        }
        boolean input = FurnaceStation.placeOne(bot, menu, found, found.input(),
                upgradeTab ? Items.SAND : Items.COBBLESTONE, containerGrant());
        boolean fuel = FurnaceStation.placeOne(bot, menu, found, found.fuel(), Items.COAL, containerGrant());
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
        if (output != null && !output.isEmpty()) {
            FurnaceStation.Result done = FurnaceStation.discover(bot.containerMenu);
            record("smelt_ticks", String.valueOf(phaseTicks));
            record("progress_at_done", done.ok() ? String.valueOf(done.found().percent()) : "-");
            return advance(Phase.TAKE);
        }
        // 发现器**每 40 tick 问一次就够**：它是反射遍历、而且成功时会打一行"认出炉子"；
        // **每 tick 问一次**会刷出几百行日志（2026-09-13 A4b 实测 383 行），把真正要看的东西淹掉。
        if (phaseTicks % 40 == 0) {
            FurnaceStation.Result now = FurnaceStation.discover(bot.containerMenu);
            if (now.ok()) {
                BotLog.info("[CraftFurnaceCheck] 进度 {}%（{}tick）", now.found().percent(), phaseTicks);
            }
        }
        if (phaseTicks > SMELT_BUDGET_TICKS) {
            // **失败不留半成品**：把输入取回背包（燃料烧掉就烧掉，如实记）
            boolean back = allowContainerWrite("timeout_return_input")
                    && FurnaceStation.takeAll(bot, bot.containerMenu, found.input(), containerGrant());
            record("timeout_input_returned", String.valueOf(back));
            check("smelted", false, "超时 " + phaseTicks + " tick，输入已取回=" + back);
            return advance(Phase.ASSERT);
        }
        return Status.RUNNING;
    }

    private Status take() {
        boolean taken = allowContainerWrite("take_output")
                && FurnaceStation.takeAll(bot, bot.containerMenu, found.output(), containerGrant());
        record("output_taken", String.valueOf(taken));
        return advance(Phase.ASSERT);
    }

    private Status assertResult() {
        AbstractContainerMenu menu = bot.containerMenu;
        int stoneAfter = totalCount(upgradeTab ? Items.GLASS : Items.STONE);
        int cobbleAfter = totalCount(upgradeTab ? Items.SAND : Items.COBBLESTONE);
        ItemStack input = FurnaceStation.stackAt(menu, found.input());
        ItemStack fuel = FurnaceStation.stackAt(menu, found.fuel());
        ItemStack output = FurnaceStation.stackAt(menu, found.output());
        record("product_delta", String.valueOf(stoneAfter - stoneBefore));
        record("cobblestone_delta", String.valueOf(cobbleAfter - cobbleBefore));
        record("input_left", input == null || input.isEmpty() ? "0" : String.valueOf(input.getCount()));
        record("fuel_left", fuel == null || fuel.isEmpty() ? "0" : String.valueOf(fuel.getCount()));
        record("output_left", output == null || output.isEmpty() ? "0" : String.valueOf(output.getCount()));
        if (!failures.contains("smelted")) {
            check("smelted", stoneAfter - stoneBefore == 1, "product+" + (stoneAfter - stoneBefore));
        }
        check("input_consumed", cobbleAfter - cobbleBefore == -1,
                "input" + (cobbleAfter - cobbleBefore));
        check("no_half_products", (input == null || input.isEmpty()) && (output == null || output.isEmpty()),
                "inputLeft=" + (input == null ? "-" : input.getCount())
                        + " outputLeft=" + (output == null ? "-" : output.getCount()));
        return advance(Phase.CLEANUP);
    }

    /**
     * **复位熔炉**（用户 2026-09-13 提醒："熔炉记得重置，不然会一直处于燃烧状态"）。
     *
     * <p>两件事：
     * <ol>
     *   <li>**把炉内东西取回**（剩下的燃料/输入/产物一起 shift-click 回背包）—— 建拆同权的精神：
     *       夹具发出去的料，能拿回来的都拿回来；</li>
     *   <li>**复位方块**：煤能烧 1600 tick，而一次烧炼只用 200 ⇒ 烧完还剩 ~1400 tick 的"余焰"，
     *       光把燃料取走并不会灭（`litTime` 是方块实体自己的状态）⇒ 夹具用**场景同款做法**
     *       （`setblock air` → `setblock furnace`）重建方块，**立刻熄灭并清空**，不给世界留"一直亮着的炉子"。
     *       这是**夹具自己的场景管理**（与 A3b 挪动场景工作台同规格），**不是**生产写入 ⇒
     *       `no_block_writes` 的含义仍是"我方账本里没有临时方块"。</li>
     * </ol>
     *
     * <p><b>必须"进相位只做一次"</b>（2026-09-13 A4b 实测教训）：本方法是**每 tick 都被调用的相位处理函数**。
     * A4b 分支要交给需要多 tick 的 {@link #deprovision()}（开菜单 → 等 OPEN → 取回升级）：原先它
     * `closeSession(...)` 后**相位仍停在 CLEANUP** ⇒ 下一 tick 又跑进来、把刚开的菜单关掉 ⇒
     * **一 tick 一开一关永远收敛不了**（实测 `closed reason=furnace_cleanup` ↔ `use_item_on` 每 50ms 一对）。
     * ⇒ 现在一律 `advance(Phase.DEPROVISION)` 换相位，CLEANUP 只走一次。
     */
    private Status cleanupFurnace() {
        AbstractContainerMenu menu = bot.containerMenu;
        boolean stationOpen = menu != null && !(menu instanceof net.minecraft.world.inventory.InventoryMenu);
        // 菜单已经关了（或只剩玩家自带菜单）⇒ 别对"玩家背包菜单"跑发现器、更别拿旧地址去点（会抛 ReportedException）
        FurnaceStation.Result now = stationOpen ? FurnaceStation.discover(menu) : null;
        int burnLeft = now != null && now.ok() ? now.found().litTime() : -1;
        record("burn_left_ticks_before_reset", String.valueOf(burnLeft));
        record("cleanup_station_open", String.valueOf(stationOpen));
        boolean canWrite = stationOpen && found != null && allowContainerWrite("cleanup_return_leftovers");
        boolean fuelBack = canWrite && FurnaceStation.takeAll(bot, menu, found.fuel(), containerGrant());
        boolean inputBack = canWrite && FurnaceStation.takeAll(bot, menu, found.input(), containerGrant());
        boolean outputBack = canWrite && FurnaceStation.takeAll(bot, menu, found.output(), containerGrant());
        record("leftovers_returned", "fuel=" + fuelBack + " input=" + inputBack + " output=" + outputBack);
        if (upgradeTab && upgradeItem != null) {
            // A4b：**拆回升级**（建拆同权）——烧炼状态跟着升级物品走，拆掉即等于熄灭。
            // 换相位去做（开菜单 → 取回 → 断言），**不要**留在这个相位里每 tick 重入。
            closeSession("furnace_cleanup");
            return advance(Phase.DEPROVISION);
        }
        closeSession("furnace_cleanup");
        // 场景同款复位：先空气再放炉子（重建方块实体 ⇒ 熄灭 + 清空）
        var server = bot.serverLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        String pos = furnace.getX() + " " + furnace.getY() + " " + furnace.getZ();
        server.getCommands().performPrefixedCommand(source, "setblock " + pos + " minecraft:air");
        server.getCommands().performPrefixedCommand(source, "setblock " + pos + " minecraft:furnace");
        boolean reset = bot.serverLevel().getBlockState(furnace)
                .is(net.minecraft.world.level.block.Blocks.FURNACE);
        record("furnace_block_reset", String.valueOf(reset));
        BotLog.info("[CraftFurnaceCheck] 复位熔炉 pos={} burnLeft={} → 重建方块={}", pos, burnLeft, reset);
        check("furnace_reset", reset, "burnLeftWas=" + burnLeft);
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

    /** A4b：站点 = 精妙容器（按方块 id 形态识别，与 `CraftStation` 同一判据）。 */
    private BlockPos findStationBlock() {
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

    /** A4b 收尾（**独立相位**，因为要跨多 tick：开菜单 → 取回熔炼升级 → 断言能力消失 + 物品回包）。 */
    private Status deprovision() {
        if (session == null) {
            if (!bot.onGround()) {
                return Status.RUNNING;
            }
            session = MenuSession.open(bot, furnace, 0);
            return Status.RUNNING;
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            check("deprovision_verified", false, "menu_failed");
            return finish();
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("deprovision_open_timeout") : Status.RUNNING;
        }
        boolean moved = StationProvision.moveOutOfContainer(bot, bot.containerMenu, upgradeItem);
        record("deprovision_moved", String.valueOf(moved));
        closeSession("deprovisioned");
        int inInventory = RecipeQuery.countInInventory(bot, upgradeItem);
        check("upgrade_returned", inInventory >= 1, "inInventory=" + inInventory);
        return finish();
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
        // ⭐ `Z4`（2026-09-23）：这是"**无残留**"判据（夹具自己会摆/收工作站），不是"零写入"。
        // `Z1` 之后区外不记账 ⇒ 光看账本证明不了"外面也收干净了" ⇒ 把**覆盖度**印出来：
        // `writes` = 本步闸门计数的真实写入次数；`writes > ledgerInZone` 的差额落在区外，
        // 本判据**覆盖不到**（`D-398` R1/R2：那里没有义务、也没有账）。
        int writes = com.dddgn.alice.action.WriteBudget.writeCount(bot);
        // 用 `check(...)`（它同时 record + 打日志 + 记账失败）⇒ 覆盖度进日志，判决不变
        check("no_block_writes", pending == 0, "writes=" + writes + " ledgerEntries=" + pending
                + " " + com.dddgn.alice.action.WriteBudget.population(bot));
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
