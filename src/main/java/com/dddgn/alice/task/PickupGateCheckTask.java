package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.DropPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * **被动拾取闸门自检**（S3.5 / D-143）：两个用例，验"路过时该捡的捡、不该捡的不捡"。
 *
 * <pre>
 * A 我方掉落物（收养 ⇒ OURS_DIRECT）⇒ bot 走过去 ⇒ **应进背包**（策略 AUTO）
 * B 外来掉落物（不登记 ⇒ FOREIGN）  ⇒ bot 走过去 ⇒ **[Pickup] blocked**，东西**仍留在地上**（策略 ASK⇒拦截）
 * </pre>
 *
 * <p>为什么这两条必须一起测：只测 A 无法证明闸门存在（原版就会捡），只测 B 无法证明没把正常拾取弄坏。
 */
public class PickupGateCheckTask implements Task {

    /** 掉落物落点（bot 起点东侧 4 格，同平台）。 */
    public static final BlockPos DROP_A = com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.offset(4, 0, 0);
    /** 外来掉落物落点（更远一点，避免两批混在一起）。 */
    public static final BlockPos DROP_B = com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.offset(6, 0, 0);
    /** **授权区**里的外来掉落物落点（S3.5 第二步：授权后应放行）。 */
    public static final BlockPos DROP_C = com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.offset(2, 0, 3);

    private enum Phase { SETUP, WALK_A, ASSERT_A, WALK_B, ASSERT_B, WALK_C, ASSERT_C,
                         ACTIVE_BLOCKED_SETUP, ACTIVE_BLOCKED_RUN, ACTIVE_BLOCKED_ASSERT,
                         ACTIVE_ALLOWED_SETUP, ACTIVE_ALLOWED_RUN, ACTIVE_ALLOWED_ASSERT,
                         ACTIVE_CONTROL_SETUP, ACTIVE_CONTROL_RUN, ACTIVE_CONTROL_ASSERT, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;

    private Phase phase = Phase.SETUP;
    private int ticks;
    private int itemsBefore;
    private int cobbleBefore;
    private int aPicked;
    private int bRemaining;
    private int cPicked;
    private int cRemaining;
    private String note = "-";

    public PickupGateCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PickupGateCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(DROP_B);
    }

    @Override
    public String failureReason() {
        return passed() ? "" : "pickup_gate_check_failed";
    }

    @Override
    public String terminalReason() {
        return phase == Phase.DONE ? (passed() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        // ⭐ `1.4z`：上限 1200 → 1600 —— 新增三条主动臂要各自走 4 格（实测每臂 ~40 tick），留余量。
        if (++ticks > 1600) {
            return finish("timeout");
        }
        return switch (phase) {
            case SETUP -> setup();
            case WALK_A -> walkA();
            case ASSERT_A -> assertA();
            case WALK_B -> walkB();
            case ASSERT_B -> assertB();
            case WALK_C -> walkC();
            case ASSERT_C -> assertC();
            case ACTIVE_BLOCKED_SETUP -> activeSetup(true, Items.COBBLESTONE, Phase.ACTIVE_BLOCKED_RUN);
            case ACTIVE_BLOCKED_RUN -> activeRun(Phase.ACTIVE_BLOCKED_ASSERT);
            case ACTIVE_BLOCKED_ASSERT -> activeAssertBlocked();
            case ACTIVE_ALLOWED_SETUP -> activeSetup(true, Items.RAW_IRON, Phase.ACTIVE_ALLOWED_RUN);
            case ACTIVE_ALLOWED_RUN -> activeRun(Phase.ACTIVE_ALLOWED_ASSERT);
            case ACTIVE_ALLOWED_ASSERT -> activeAssertAllowed();
            case ACTIVE_CONTROL_SETUP -> activeSetup(false, Items.COBBLESTONE, Phase.ACTIVE_CONTROL_RUN);
            case ACTIVE_CONTROL_RUN -> activeRun(Phase.ACTIVE_CONTROL_ASSERT);
            case ACTIVE_CONTROL_ASSERT -> activeAssertControl();
            case DONE -> passed() ? Status.DONE : Status.FAILED;
        };
    }

    private Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "function alice_test:lumber_course");
        bot.teleportTo(level, com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getY(),
                com.dddgn.alice.task.LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            return finish("no_session");
        }
        // **先清场**：上一轮测试可能残留掉落物，会把"收养/增量"数字污染
        // （2026-09-12 实测：adopted=4、背包 44，其实混进了旧掉落物）
        server.getCommands().performPrefixedCommand(source,
                "kill @e[type=item,x=" + (DROP_A.getX() - 16) + ",y=" + (DROP_A.getY() - 8)
                        + ",z=" + (DROP_A.getZ() - 16) + ",dx=32,dy=24,dz=32]");
        cobbleBefore = inventoryCobblestone();
        session.scope().begin(DROP_A, 16, bot.getUUID());
        // A：我方掉落物（显式收养 ⇒ OURS_DIRECT）
        spawn(level, DROP_A, 1);
        int adopted = session.scope().adoptExistingDrops(level, DROP_A, 4);
        BotLog.info("[PickupGateCheck] A 造物：我方掉落物 1 堆（清场后 adopted={}，应=1）", adopted);
        // B：外来掉落物（**不登记** ⇒ FOREIGN）
        spawn(level, DROP_B, 1);
        itemsBefore = countNear(level, DROP_B, 2.0D);
        BotLog.info("[PickupGateCheck] B 造物：外来掉落物 1 堆（未登记 ⇒ FOREIGN），命中数={}", itemsBefore);
        // C：**授权区里的外来掉落物** —— 玩家授权一片范围后，那里的东西应放行（GRANTED_AREA ⇒ AUTO）
        var grant = com.dddgn.alice.decision.CollectGrants.add(level.getServer(),
                DROP_C.getX() - 2, DROP_C.getZ() - 2, DROP_C.getX() + 2, DROP_C.getZ() + 2,
                com.dddgn.alice.decision.PermissionGate.Scope.SESSION, "fixture:pickup_gate_check",
                20 * 600);
        spawn(level, DROP_C, 1);
        BotLog.info("[PickupGateCheck] C 造物：授权区 {} 内的外来掉落物 1 堆（授权 {}）",
                DROP_C.toShortString(), grant.describe());
        phase = Phase.WALK_A;
        return Status.RUNNING;
    }

    private void spawn(ServerLevel level, BlockPos pos, int stacks) {
        spawn(level, pos, stacks, Items.COBBLESTONE);
    }

    private void spawn(ServerLevel level, BlockPos pos, int stacks, Item item) {
        for (int i = 0; i < stacks; i++) {
            ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5D + i * 0.3D, pos.getY() + 0.5D,
                    pos.getZ() + 0.5D, new ItemStack(item, item == Items.COBBLESTONE ? 4 : 1));
            drop.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(drop);
        }
    }

    private int countNear(ServerLevel level, BlockPos pos, double radius) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(radius),
                ItemEntity::isAlive).size();
    }

    /** 背包里某物品的总数（主动臂的增量口径）。 */
    private int inventoryCount(Item item) {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int inventoryCobblestone() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.COBBLESTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private Status walkA() {
        if (ticks % 40 == 1) {
            BotLog.info("[PickupGateCheck] A 走向我方掉落物 {}…（当前圆石={}）",
                    DROP_A.toShortString(), inventoryCobblestone());
        }
        return walkTo(DROP_A, Phase.ASSERT_A);
    }

    private Status assertA() {
        aPicked = inventoryCobblestone() - cobbleBefore;   // **增量**（清场后仍以增量为准，抗残留）
        BotLog.info("[PickupGateCheck] A 断言：走到我方掉落物上 ⇒ 圆石增量={}（基准 {}，应 >0，策略 AUTO 放行）",
                aPicked, cobbleBefore);
        phase = Phase.WALK_B;
        return Status.RUNNING;
    }

    private Status walkB() {
        if (ticks % 40 == 1) {
            BotLog.info("[PickupGateCheck] B 走向外来掉落物 {}…", DROP_B.toShortString());
        }
        return walkTo(DROP_B, Phase.ASSERT_B);
    }

    private Status assertB() {
        bRemaining = countNear(bot.serverLevel(), DROP_B, 2.0D);
        BotLog.info("[PickupGateCheck] B 断言：走到外来掉落物上 ⇒ 地上还剩 {} 堆（应 >0，策略 ASK 在被动路径上拦截）",
                bRemaining);
        phase = Phase.WALK_C;
        return Status.RUNNING;
    }

    private Status walkC() {
        return walkTo(DROP_C, Phase.ASSERT_C);
    }

    private Status assertC() {
        cPicked = inventoryCobblestone() - cobbleBefore - aPicked;
        cRemaining = countNear(bot.serverLevel(), DROP_C, 2.0D);
        BotLog.info("[PickupGateCheck] C 断言：走到授权区里的外来掉落物上 ⇒ 增量={} 地上剩={} "
                        + "（授权 ⇒ GRANTED_AREA ⇒ AUTO，应被捡起）",
                cPicked, cRemaining);
        phase = Phase.ACTIVE_BLOCKED_SETUP;
        return Status.RUNNING;
    }

    /**
     * ⭐ `1.4z`（2026-09-26，用户口径）：**主动拾取清单** —— 「掉的石头是可捡拾物，但不是**主动**捡拾物；
     * 这个任务里默认只有矿物是主动拾取物」。
     *
     * <p>前三条臂验的是**被动**闸门（路过时该不该捡）；这三条验**主动**：**值不值得专门跑一趟去收**。
     * 判据出处 = 生产过滤器（{@link #PRODUCT_FILTER}），夹具只负责把它注入收集器。
     *
     * <pre>
     * ① 清单**外**（圆石）+ 注入清单 ⇒ 收集器一件都不收：地上仍 1 堆、背包零增量、**bot 一步没动**
     * ② 清单**内**（粗铁）+ 注入清单 ⇒ 走过去收进背包（证明清单没把正常收集弄坏）
     * ③ **负对照**：圆石 + **不注入**清单（`null` = 全部落物）⇒ 走过去收进背包
     *    —— 没有③，①完全可能只是"场景本身收不到手"，而不是清单在起作用
     * </pre>
     */
    private Status activeSetup(boolean filtered, Item item, Phase nextRun) {
        ServerLevel level = bot.serverLevel();
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            return finish("no_session");
        }
        // 场景夹具硬纪律：每个子项开始前把 bot 复位到统一起点（三条臂各自独立）
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        // 清场（同 `setup` 的理由：残留会污染增量与"地上还剩几堆"）
        var source = level.getServer().createCommandSourceStack().withSuppressedOutput();
        level.getServer().getCommands().performPrefixedCommand(source,
                "kill @e[type=item,x=" + (DROP_A.getX() - 8) + ",y=" + (DROP_A.getY() - 4)
                        + ",z=" + (DROP_A.getZ() - 8) + ",dx=16,dy=10,dz=16]");
        spawn(level, DROP_A, 1, item);
        int adopted = session.scope().adoptExistingDrops(level, DROP_A, 4);
        activeItem = item;
        itemBaseline = inventoryCount(item);
        footBefore = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot);
        activeFiltered = filtered;
        activeWaitTicks = 0;
        collector = null;   // ⭐ 等到 RUN 相位确认落物**已进候选源**再建（见 `activeRun`）
        // ⭐⭐ 本臂要验的**只有**"主动拾取清单"这一件事 ⇒ 把另外两层从臂里排除掉：
        // ① 候选源：`scope.liveDrops()` 只认**我们破坏产生**（有 `itemOrigins`）的落物，而夹具是
        //    `addFreshEntity` 直接造的 ⇒ 换成"这块地里的落物"（`D-344` 的接缝，与 `CollectJob` 同法）；
        // ② 归属/许可：给这块地一个采集授权（同臂 C 的做法）⇒ 落物走 GRANTED_AREA ⇒ AUTO 放行，
        //    于是"没去收"只可能是**清单**造成的，不可能是"策略不许捡"。
        final BlockPos spot = DROP_A;
        final ServerLevel lvl = level;
        activeSource = () -> lvl.getEntitiesOfClass(ItemEntity.class,
                new AABB(spot).inflate(2.0D), ItemEntity::isAlive);
        var activeGrant = com.dddgn.alice.decision.CollectGrants.add(level.getServer(),
                DROP_A.getX() - 3, DROP_A.getZ() - 3, DROP_A.getX() + 3, DROP_A.getZ() + 3,
                com.dddgn.alice.decision.PermissionGate.Scope.SESSION,
                "fixture:pickup_gate_check_active", 20 * 600);
        BotLog.info("[PickupGateCheck] 主动臂 采集授权 {}（让'没去收'只可能是清单造成的）",
                activeGrant.describe());
        // 夹具前提自证：清单判据来自**生产过滤器**（同一个类、同一个入口），不是夹具自己编的
        boolean premProduct = PRODUCT_FILTER.matches(new ItemStack(Items.RAW_IRON));
        boolean premStone = !PRODUCT_FILTER.matches(new ItemStack(Items.COBBLESTONE));
        activePremiseOk = activePremiseOk && premProduct && premStone;
        BotLog.info("[PickupGateCheck] 主动臂 造物：{} ×1 @ {} · adopted={} · 清单={} ·"
                        + " 生产过滤器前提(粗铁∈清单={} · 圆石∈清单={})",
                item, DROP_A.toShortString(), adopted, filtered ? "产物" : "全部(负对照)",
                premProduct, !premStone);
        phase = nextRun;
        return Status.RUNNING;
    }

    /**
     * ⭐ 先等落物**真的进候选源**再建收集器 —— 否则会验出一个假结论。
     *
     * <p>为什么必须（`D-348` 的原话就是这件事，同模块 `scope_pending_grace` 步为它而立）：刚
     * `level.addFreshEntity(...)` 的落物在**当 tick 还没进实体的登记/查找路径**，而
     * {@link CollectDropsTask#tick()} 的第一件事就是 `refreshCandidates()`，**候选为空 ⇒ 立刻
     * `finish("done")`**（`live.isEmpty()`）⇒ 本夹具第一版三条臂全是 `collected=0/0 clusters=0`，
     * 看起来像"清单把东西滤掉了"，其实是**它根本没看见**。
     */
    private Status activeRun(Phase next) {
        var session = BotManager.sessionOf(bot);
        if (session == null) {
            return finish("no_session");
        }
        if (collector == null) {
            boolean visible = activeSource != null && !activeSource.get().isEmpty();
            if (!visible) {
                if (++activeWaitTicks > 80) {
                    BotLog.warn("[PickupGateCheck] 主动臂 落物 {} 80 tick 内没进候选源（liveDrops）⇒ 如实判红",
                            activeItem);
                    phase = Phase.DONE;
                    return finish("drop_not_registered");
                }
                return Status.RUNNING;
            }
            collector = new CollectDropsTask(bot, DROP_A, session.scope(), List.of(), false,
                    ACTIVE_COLLECT_BUDGET_TICKS, MiningProfile.STANDABLE_ONLY, activeSource,
                    activeFiltered ? PRODUCT_FILTER::matches : null);
            BotLog.info("[PickupGateCheck] 主动臂 落物已进候选源（等了 {} tick）⇒ 起收集器（清单={}）",
                    activeWaitTicks, activeFiltered ? "产物" : "全部(负对照)");
        }
        Status status = collector.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        collector = null;
        phase = next;
        return Status.RUNNING;
    }

    private Status activeAssertBlocked() {
        ServerLevel level = bot.serverLevel();
        activeBlockedRemaining = countNear(level, DROP_A, 2.0D);
        activeBlockedMoved = !com.dddgn.alice.pathing.MovementHelper.footCell(level, bot).equals(footBefore);
        BotLog.info("[PickupGateCheck] 主动臂①断言（清单**外** + 注入清单 ⇒ **不专门去收**）：地上剩={}（应=1）"
                        + " 背包增量={}（应=0） bot移动={}（应=false）",
                activeBlockedRemaining, inventoryCount(activeItem) - itemBaseline, activeBlockedMoved);
        phase = Phase.ACTIVE_ALLOWED_SETUP;
        return Status.RUNNING;
    }

    private Status activeAssertAllowed() {
        ServerLevel level = bot.serverLevel();
        activeAllowedPicked = inventoryCount(activeItem) - itemBaseline;
        activeAllowedRemaining = countNear(level, DROP_A, 2.0D);
        BotLog.info("[PickupGateCheck] 主动臂②断言（清单**内** + 注入清单 ⇒ 收得到）：背包增量={}（应>0）"
                        + " 地上剩={}（应=0）",
                activeAllowedPicked, activeAllowedRemaining);
        phase = Phase.ACTIVE_CONTROL_SETUP;
        return Status.RUNNING;
    }

    private Status activeAssertControl() {
        ServerLevel level = bot.serverLevel();
        activeControlPicked = inventoryCount(activeItem) - itemBaseline;
        activeControlRemaining = countNear(level, DROP_A, 2.0D);
        BotLog.info("[PickupGateCheck] 主动臂③断言（**负对照**：圆石 + 不注入清单 ⇒ 收得到）：背包增量={}"
                        + "（应>0） 地上剩={}（应=0）—— 没有这条，臂①可能只是场景本身收不到手",
                activeControlPicked, activeControlRemaining);
        phase = Phase.DONE;
        return finish(passed() ? "passed" : "failed");
    }

    /** 用**已验收的** WalkToTask 走过去（不挖不放置），到点后再断言。 */
    private Status walkTo(BlockPos goal, Phase next) {
        if (runner == null) {
            runner = new WalkToTask(bot, goal);
        }
        Status status = runner.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        runner = null;
        phase = next;
        return Status.RUNNING;
    }

    private WalkToTask runner;
    private boolean finished;

    // ==================== ⭐ `1.4z`（2026-09-26）：主动拾取清单三臂 ====================

    /**
     * **主动拾取清单**的判据出处 = **生产过滤器**（与 `FishboneJob.countProductItems()` 同一个入口，
     * 见 {@link CollectDropsTask} 的 `activePickup` 字段）—— 夹具**不自己编**"什么算产物"。
     */
    /** 主动臂自己的收集预算（夹具显式给值，不依赖生产默认常量 —— 免得与别处的默认值暗中耦合）。 */
    private static final int ACTIVE_COLLECT_BUDGET_TICKS = 300;

    private static final com.dddgn.alice.job.mine.MineProductFilter PRODUCT_FILTER =
            com.dddgn.alice.job.mine.MineProductFilter.forTag(null);

    private CollectDropsTask collector;
    private Item activeItem = Items.COBBLESTONE;
    private int itemBaseline;
    private BlockPos footBefore;
    private boolean activeFiltered;
    private java.util.function.Supplier<List<ItemEntity>> activeSource;
    private int activeWaitTicks;
    private boolean activePremiseOk = true;      // 生产过滤器前提（粗铁∈清单 · 圆石∉清单）自证
    private int activeBlockedRemaining = -1;
    private boolean activeBlockedMoved = true;   // 初值 true ⇒ 臂没跑到就判红（不许"没验过算过"）
    private int activeAllowedPicked = -1;
    private int activeAllowedRemaining = -1;
    private int activeControlPicked = -1;
    private int activeControlRemaining = -1;

    private Status finish(String reason) {
        phase = Phase.DONE;
        if (finished) {
            return passed() ? Status.DONE : Status.FAILED;
        }
        finished = true;
        boolean pass = passed();
        note = reason;
        String summary = "a_picked=" + aPicked + " b_remaining=" + bRemaining
                + " c_picked=" + cPicked + " c_remaining=" + cRemaining
                + " active_blocked_remaining=" + activeBlockedRemaining
                + " active_blocked_moved=" + activeBlockedMoved
                + " active_allowed_picked=" + activeAllowedPicked
                + " active_allowed_remaining=" + activeAllowedRemaining
                + " active_control_picked=" + activeControlPicked
                + " active_control_remaining=" + activeControlRemaining
                + " active_premise_ok=" + activePremiseOk
                + " reason=" + reason + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[PickupGateCheck] SUMMARY {}（{}）", summary, DropPolicy.describeWindow());
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 被动拾取闸门自检 " + summary));
        }
        return pass ? Status.DONE : Status.FAILED;
    }

    private boolean passed() {
        // 三条一起过：我方该捡（A）、外来该拦（B）、**授权区该放行**（C）
        // ⭐ `1.4z` 再加三条：清单外**不专门收**（① + 判据前提自证）、清单内**收得到**（②）、
        // **负对照**证明场景本身收得到（③）—— 三条缺一，这份夹具就证明不了"清单在起作用"。
        boolean activeBlockedOk = activePremiseOk && activeBlockedRemaining == 1 && !activeBlockedMoved;
        boolean activeAllowedOk = activeAllowedPicked > 0 && activeAllowedRemaining == 0;
        boolean activeControlOk = activeControlPicked > 0 && activeControlRemaining == 0;
        return aPicked > 0 && bRemaining > 0 && cPicked > 0 && cRemaining == 0
                && activeBlockedOk && activeAllowedOk && activeControlOk;
    }
}
