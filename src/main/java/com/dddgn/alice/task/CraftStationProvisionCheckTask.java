package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.CraftStation;
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
 * **工作站装配自检**（阶段 3-A / L2，D-194）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>验的就是用户裁定的那一层 —— *"装上升级本身算一种配置行为"*、*"bot 自己用菜单协议把合成升级点进升级槽"*：
 * <ol>
 *   <li>前提：站点在、**当前没有**合成能力（没有网格）；</li>
 *   <li>**装配**：bot 用菜单协议（shift-click）把合成升级送进容器 ⇒ 关掉再开菜单 ⇒
 *       **用能力验证**（{@link GridDiscovery} 必须认出 3×3）—— 不猜槽位语义，只看结果；</li>
 *   <li>**建拆同权**：再用同一个协议把升级**取回** ⇒ 重开菜单 ⇒ 能力必须消失、物品必须回到背包；</li>
 *   <li>收尾：账本无我方临时方块（本夹具只搬物品，不写方块）。</li>
 * </ol>
 *
 * <p>**失败也要清场**：装配验证失败会把物品取回来并记 `rollback_clean`。
 *
 * <p>零参数入口：`alice:craft_station_provision_check`；场景 `alice_test:craft_tab_course`（**不再给玩家发升级**）。
 */
public class CraftStationProvisionCheckTask implements Task {

    /** 与探针同一个起点（场景一致）。 */
    public static final BlockPos START = CraftGridProbeTask.START;
    private static final int SCAN_RADIUS = 6;
    private static final int MAX_TICKS = 900;
    private static final int OPEN_TICKS = 60;
    /** 重试前的冷却 tick（等服务端把上一次交互收尾）。 */
    private static final int RETRY_TICKS = 10;

    /** 升级物品的 **id**（按 id 找，不引编译期依赖 ⇒ 模组没装时如实报 `mod_absent`）。 */
    private static final ResourceLocation UPGRADE_ID =
            ResourceLocation.fromNamespaceAndPath("sophisticatedstorage", "crafting_upgrade");

    private enum Phase {
        PREPARE, PREMISE_OPEN, PREMISE_CHECK,
        INSTALL_OPEN, INSTALL_MOVE, INSTALL_VERIFY_OPEN, INSTALL_VERIFY,
        REMOVE_OPEN, REMOVE_MOVE, REMOVE_VERIFY_OPEN, REMOVE_VERIFY, DONE
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
    private int inventoryBefore;
    private int installedVerified;

    public CraftStationProvisionCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftStationProvisionCheck";
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
            case PREMISE_OPEN -> openPhase(Phase.PREMISE_CHECK);
            case PREMISE_CHECK -> premiseCheck();
            case INSTALL_OPEN -> openPhase(Phase.INSTALL_MOVE);
            case INSTALL_MOVE -> installMove();
            case INSTALL_VERIFY_OPEN -> openPhase(Phase.INSTALL_VERIFY);
            case INSTALL_VERIFY -> installVerify();
            case REMOVE_OPEN -> openPhase(Phase.REMOVE_MOVE);
            case REMOVE_MOVE -> removeMove();
            case REMOVE_VERIFY_OPEN -> openPhase(Phase.REMOVE_VERIFY);
            case REMOVE_VERIFY -> removeVerify();
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
                "foot=" + bot.blockPosition().toShortString() + " start=" + START.toShortString());
        WorldModLedger.dropStale(bot.serverLevel());

        upgrade = BuiltInRegistries.ITEM.get(UPGRADE_ID);
        check("mod_present", upgrade != null && upgrade != Items.AIR,
                "upgradeId=" + UPGRADE_ID + "（模组未装 ⇒ 本项不适用）");
        if (upgrade == null || upgrade == Items.AIR) {
            return finish();
        }
        station = findStation();
        check("station_found", station != null,
                "station=" + (station == null ? "-" : station.toShortString()) + " radius=" + SCAN_RADIUS);
        if (station == null) {
            return finish();
        }
        // **夹具自己声明测哪个站点**（不依赖玩家当前选择；也让独立物品入口不受选择影响）
        CraftStation.select(bot, CraftStation.UPGRADE_TAB.id());
        // 夹具自摆前提：**先清背包再发一颗升级**（数量是后面"真搬了"的证据）
        FixtureToolKit.resetInventory(bot);
        give(upgrade, 1);
        inventoryBefore = RecipeQuery.countInInventory(bot, upgrade);
        check("fixture_gave_upgrade", inventoryBefore == 1, "inInventory=" + inventoryBefore);
        return advance(Phase.PREMISE_OPEN);
    }

    /**
     * 前提：**当前没有**合成能力（否则测的不是"装配"这件事）。
     *
     * <p>**夹具自摆前提**（D-187 §6.9.1）：世界里的容器可能**已经被上一轮/手动装配过**
     * （实测：`setblock` 放同种方块会短路，升级会跨场景存活）⇒ 这时夹具**自己把它拆回干净**
     * 再继续，并如实记 `premise_cleaned=true`；清理本身失败才判红。
     */
    private Status premiseCheck() {
        GridDiscovery.Result before = GridDiscovery.discover(bot.containerMenu, bot);
        if (!before.ok()) {
            check("premise_not_provisioned", true, before.describe());
            closeSession("premise_checked");
            return advance(Phase.INSTALL_OPEN);
        }
        // 已被装配过 ⇒ 先拆回来（同一个协议：QUICK_MOVE 取回）
        if (!StationProvision.allowContainerWrite(bot, station)) {
            return failAndFinish(StationProvision.Codes.BUDGET_REFUSED);
        }
        boolean cleaned = StationProvision.moveOutOfContainer(bot, bot.containerMenu, upgrade);
        record("premise_cleaned", String.valueOf(cleaned));
        inventoryBefore = RecipeQuery.countInInventory(bot, upgrade);   // 数量基线跟着更新
        check("premise_cleanup", cleaned, "from=" + before.describe() + " 取回后背包=" + inventoryBefore);
        closeSession("premise_cleaned");
        if (!cleaned) {
            return finish();
        }
        // 清完后的最终状态由 INSTALL 一相证明（重开菜单 + 能力验证）⇒ 这里不重复开一次
        return advance(Phase.INSTALL_OPEN);
    }

    /** 站点方块 = 精妙容器形态（与 {@link CraftStation} 的 upgradetab 同一判据）。 */
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

    // ==================== 四个"开菜单"相位共用一个实现 ====================

    private Status openPhase(Phase next) {
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
            // **夹具纪律**：手里握着升级右键容器 = 物品自己装进去、**GUI 不开**（实测）⇒ 开菜单前先换走
            StationProvision.clearHeldUpgrade(bot, upgrade);
            // 与探针同一条路：**经过 `CraftStation.open`**（它按选中的站点解析，未来换形态也不用改这里）
            CraftStation.Opened opened = CraftStation.open(bot, SCAN_RADIUS);
            if (!opened.ok()) {
                check("menu_open_failed", false, opened.describe());
                return finish();
            }
            session = opened.session();
            return Status.RUNNING;   // 与探针一致：开启与 tick 分两个相位，避免 K-3 空中硬拒
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            // **如实打码 + 重试一次**：2026-09-13 实测电池里出现过"服务端那次没开菜单"
            // （`use_item_on … result=SUCCESS`，而历次成功都是 `CONSUME`，随后 20 tick 超时）。
            // 偶发状态不该把整步判死，但**必须留下证据**（失败码 / 当前菜单 / 重试次数）。
            String failure = session.failure();
            String menu = bot.containerMenu == null ? "-" : bot.containerMenu.getClass().getSimpleName();
            BotLog.warn("[{}] 开菜单失败 code={} 当前菜单={} 已重试={}", "ProvisionCheck",
                    failure, menu, openRetries);
            session = null;
            if (openRetries < 1) {
                openRetries++;
                record("open_retries", String.valueOf(openRetries));
                openRetryDelay = RETRY_TICKS;
                phaseTicks = 0;      // 重试要从零计时，否则会立刻撞上本相位的超时预算
                return Status.RUNNING;
            }
            check("menu_open_failed", false, "code=" + failure + " menu=" + menu);
            return finish();
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("menu_open_timeout") : Status.RUNNING;
        }
        return advance(next);
    }

    // ==================== 装配 ====================

    private Status installMove() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (!StationProvision.allowContainerWrite(bot, station)) {
            check("provision_verified", false, StationProvision.Codes.BUDGET_REFUSED);
            return failAndFinish(StationProvision.Codes.BUDGET_REFUSED);
        }
        boolean moved = StationProvision.moveIntoContainer(bot, menu, upgrade, station);
        record("install_move_accepted", String.valueOf(moved));
        closeSession("provision_install");
        return advance(Phase.INSTALL_VERIFY_OPEN);
    }

    private Status installVerify() {
        AbstractContainerMenu menu = bot.containerMenu;
        int inInventory = RecipeQuery.countInInventory(bot, upgrade);
        int inContainer = StationProvision.countInContainer(menu, bot, upgrade);
        boolean movedItem = inInventory == inventoryBefore - 1 && inContainer >= 1;
        check("item_moved_into_container", movedItem,
                "inInventory=" + inInventory + " inContainer=" + inContainer);
        GridDiscovery.Result after = GridDiscovery.discover(menu, bot);
        boolean grid = after.ok() && after.spec().width() >= 3 && after.spec().height() >= 3;
        check("provision_verified", grid, after.describe());
        installedVerified = grid ? 1 : 0;
        if (!grid) {
            // **失败也要清场**：把刚装进去的取回来
            boolean back = StationProvision.moveOutOfContainer(bot, menu, upgrade);
            int now = RecipeQuery.countInInventory(bot, upgrade);
            check("rollback_clean", back && now == inventoryBefore,
                    "tookBack=" + back + " inInventory=" + now + "（期望 " + inventoryBefore + "）");
            closeSession("provision_failed");
            return finish();
        }
        closeSession("install_verified");
        return advance(Phase.REMOVE_OPEN);
    }

    // ==================== 拆除（建拆同权的另一半） ====================

    private Status removeMove() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (!StationProvision.allowContainerWrite(bot, station)) {
            return failAndFinish(StationProvision.Codes.BUDGET_REFUSED);
        }
        boolean moved = StationProvision.moveOutOfContainer(bot, menu, upgrade);
        record("remove_move_accepted", String.valueOf(moved));
        closeSession("provision_remove");
        return advance(Phase.REMOVE_VERIFY_OPEN);
    }

    private Status removeVerify() {
        AbstractContainerMenu menu = bot.containerMenu;
        int inInventory = RecipeQuery.countInInventory(bot, upgrade);
        int inContainer = StationProvision.countInContainer(menu, bot, upgrade);
        GridDiscovery.Result after = GridDiscovery.discover(menu, bot);
        check("item_returned", inInventory == inventoryBefore && inContainer == 0,
                "inInventory=" + inInventory + " inContainer=" + inContainer);
        check("deprovision_verified", !after.ok(), after.describe());
        closeSession("remove_verified");
        return finish();
    }

    // ==================== 收尾 ====================

    /** 四个"开菜单"相位共用一个实现（一次握手的写法）。 */
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
        BotLog.info("[ProvisionCheck] {}={} {}", name, ok ? "true" : "false", detail);
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
        record("install_verified", String.valueOf(installedVerified));
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[ProvisionCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[ProvisionCheck] " + summary));
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
        BotLog.warn("[ProvisionCheck] 背包没有空槽，无法发料 {} x{}", item, count);
    }
}
