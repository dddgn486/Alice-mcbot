package com.dddgn.alice.task.craft;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.task.PathRetryRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **一台机器的单机最小闭环执行器**（阶段 3-B / S4 v2 → (c) 增量 2）：走 → 开 → 电 → 放料 → 等 → 取 → 复核。
 *
 * <p><b>为什么要有这个类</b>：同一条闭环原来只活在夹具 {@code MachineCycleCheckTask} 里。要把它接进**生产路径**
 * （{@code CraftJob} 的 `MACHINE_ROUTE`）时，若在生产侧另写一份，"放料/取产物/等进度"这套机械细节就会有两处实现，
 * 迟早分叉。本类把它抽成**一处**，夹具与生产各自调用（用户 2026-09-14 裁定 A 方案）。
 *
 * <p><b>它不做的事（边界写死，别在这里加）</b>：
 * <ul>
 *   <li><b>不补电</b>：本类**没有任何"造能量"的代码**。没电 = 如实失败 {@code machine_no_energy}。
 *       唯一的例外通道是调用方注入的 {@link EnergyTopUp}（**只有夹具实现它**，见红线①的门禁
 *       {@code tools/check-precharge-containment.sh}）；生产调用方必须传 {@code null}；</li>
 *   <li><b>不发物品</b>：调用方负责"料在背包里"（夹具按测试前提补进空槽并如实留痕；生产路径缺料就如实失败）；</li>
 *   <li><b>不选配方</b>：输入/输出物品与数量由调用方给定（夹具按类型挑一道；生产用路由给的配方）；</li>
 *   <li><b>不传送、不复位</b>：起点与结束复位是**夹具纪律**（PLAYBOOK §5.0d），生产路径自己走。</li>
 * </ul>
 *
 * <p><b>写入口径（一处不新造）</b>：容器写入维度 {@link WriteBudget#consumeContainerWrite} + 理由
 * {@link WriteReason#CONTAINER_TRANSFER} + **requester 由调用方给**（夹具 `machine-cycle`、生产 `craft`，
 * 都必须在策略矩阵里登记过）；动作走 {@link StationProvision} 的菜单协议（**shift-click，让菜单决定落点**），
 * 成败**一律按结果验证**（机器里真的有了料 / 背包里真的多了产物），**不猜槽位语义**。
 *
 * <p><b>到站判据</b>（S4 v2）：{@link TableCraft#standPointNear} 找"现在就能站"的格 →
 * {@link PathRequest#of}（**纯通行**，D-076：不挖不搭）→ {@link PathRetryRunner} → 停在**开菜单那一刻**
 * 用 {@link TableCraft#inReach} 断言够得着。已经站在旁边时如实记 {@code walk_skipped}，不假装走过。
 */
public final class MachineCycle {

    /**
     * 事实/断言出口。夹具把它接到自己的 `record`/`check`（于是 SUMMARY 字段一个不少），
     * 生产侧接到自己的日志与失败码。
     */
    public interface Sink {
        void record(String key, String value);

        /** `ok=false` 表示**闭环判据不成立**，调用方应据此判红（日志由调用方打）。 */
        void check(String name, boolean ok, String detail);
    }

    /**
     * **按前提补电**（唯一的"造能量"通道）。**只允许夹具实现**：生产语义是"没电 ⇒ 如实失败"，
     * 不许凭空造能量（D-216 红线①）。返回是否真的补上了（**按结果判定**，不看调用是否抛异常）。
     */
    public interface EnergyTopUp {
        boolean apply(Object blockEntity);
    }

    /**
     * 本轮要跑的那一台 + 那道配方。
     *
     * @param machineBlockId     目标机器方块 id（生产来自路由的 `station`；夹具来自它写死的那一行）
     * @param machineTypeId      机器配方类型 id（用作"点对了哪台"的自证：方块实体自述 == 这一行）
     * @param scanRadius         找机器的立方盒半径
     * @param walkRequestLabel   `PathRequest` 的标签（日志里认得出是哪一段路）
     * @param walkSession        `PathRetryRunner` 的会话前缀
     * @param outputBefore       产物在背包里的**起始**数量（复核"真的多了"的基准）
     * @param requester          容器写入的 requester（策略矩阵里的那一行）
     * @param energyPresentLabel 开机时机器已有电时留痕的措辞（夹具 = 场景自证；生产 = 中性措辞）
     * @param cleanBeforeStart   开始前是否把机器容器里的东西搬回背包（**夹具纪律**：不留脏前提；
     *                           生产为 `false` —— 生产**不许**动别人的东西）
     */
    public record Spec(String machineBlockId, String machineTypeId, int scanRadius,
                       String walkRequestLabel, String walkSession, int walkTicks, int waitTicks,
                       Item input, int inputCount, Item output, int outputCount, int outputBefore,
                       String requester, String energyPresentLabel, boolean cleanBeforeStart) {
    }

    public enum State { RUNNING, DONE, FAILED }

    private enum Phase { LOCATE, WALK, OPEN, ENERGY, FEED, WAIT, TAKE, VERIFY, DONE }

    private static final int OPEN_TICKS = 80;
    private static final int EFFECT_TICKS = 40;
    private static final int ENERGY_GRACE_TICKS = 60;

    private final BotPlayer bot;
    private final Sink sink;
    private final String tag;
    private final Spec spec;
    private final EnergyTopUp topUp;

    private Phase phase = Phase.LOCATE;
    private int phaseTicks;
    private boolean finished;
    private String failure;
    private MenuSession session;

    private BlockPos machinePos;
    private Object blockEntity;
    private BlockPos standPoint;
    private PathRetryRunner runner;
    private int containerWrites;
    private int maxOperatingTicks = -1;
    private boolean activeSeen;

    public MachineCycle(BotPlayer bot, Sink sink, String tag, Spec spec, EnergyTopUp topUp) {
        this.bot = bot;
        this.sink = sink;
        this.tag = tag;
        this.spec = spec;
        this.topUp = topUp;
    }

    // ==================== 推进 ====================

    /** 推进一 tick。`FAILED` 时用 {@link #failure()} 取如实失败码（调用方决定怎么报）。 */
    public State tick() {
        if (finished) {
            return failure == null ? State.DONE : State.FAILED;
        }
        phaseTicks++;
        switch (phase) {
            case LOCATE -> locate();
            case WALK -> walk();
            case OPEN -> open();
            case ENERGY -> ensureEnergy();
            case FEED -> feed();
            case WAIT -> awaitProduct();
            case TAKE -> take();
            case VERIFY -> verify();
            case DONE -> finished = true;
        }
        return finished ? (failure == null ? State.DONE : State.FAILED) : State.RUNNING;
    }

    /** 如实失败码；没失败返回 `null`。 */
    public String failure() {
        return failure;
    }

    public BlockPos machinePos() {
        return machinePos;
    }

    /** 关菜单（成功与失败路径都该走；复位/传送由调用方负责）。 */
    public void closeMenu(String reason) {
        if (session != null) {
            session.close(reason);
            session = null;
        }
        if (bot.containerMenu != null && !(bot.containerMenu instanceof InventoryMenu)) {
            bot.closeContainer();
        }
    }

    // ==================== 相位 ====================

    /** 按表认机器（{@link MachineMap} 是唯一出处；半径内取最近一台）。 */
    private void locate() {
        BlockPos center = bot.blockPosition();
        BlockPos found = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-spec.scanRadius(), -spec.scanRadius(), -spec.scanRadius()),
                center.offset(spec.scanRadius(), spec.scanRadius(), spec.scanRadius()))) {
            String id = BuiltInRegistries.BLOCK.getKey(bot.serverLevel().getBlockState(pos).getBlock()).toString();
            if (!id.equals(spec.machineBlockId())) {
                continue;
            }
            double distance = Math.sqrt(pos.distSqr(center));
            if (distance < best) {
                best = distance;
                found = pos.immutable();
            }
        }
        if (found == null) {
            fail("machine_absent:radius_" + spec.scanRadius());
            return;
        }
        machinePos = found;
        sink.record("machine", spec.machineBlockId() + "@" + found.toShortString());
        sink.record("machine_distance_at_locate", fmt(best));
        sink.record("machine_type", spec.machineTypeId());
        blockEntity = bot.serverLevel().getBlockEntity(found);
        sink.record("machine_be", blockEntity == null ? "-" : blockEntity.getClass().getName());
        String beType = readRecipeTypeName(blockEntity);
        if (beType == null) {
            sink.record("binding", "unverified（方块实体没有可读的 getRecipeType/getRegistryName）");
        } else {
            sink.check("binding", spec.machineTypeId().equals(beType),
                    "表=" + spec.machineTypeId() + " 方块实体自述=" + beType);
        }
        advance(Phase.WALK);
    }

    /** 走到机器旁（内核寻路，**纯通行** —— `PathRequest.of` 默认不改世界，D-076）。 */
    private void walk() {
        if (standPoint == null) {
            standPoint = TableCraft.standPointNear(bot.serverLevel(), machinePos);
            if (standPoint == null) {
                fail("machine_no_standing_point@" + machinePos.toShortString());
                return;
            }
            sink.record("stand_point", standPoint.toShortString());
            if (TableCraft.inReach(bot, machinePos) && bot.blockPosition().equals(standPoint)) {
                sink.record("walk_skipped", "already_in_reach");
                advance(Phase.OPEN);
                return;
            }
            PathRequest request = PathRequest.of(bot.getUUID().toString(), bot.blockPosition(),
                    standPoint, spec.walkRequestLabel());
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS, spec.walkSession());
            return;
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            if (phaseTicks > spec.walkTicks()) {
                fail("walk_timeout:" + phaseTicks + "ticks");
            }
            return;
        }
        runner = null;
        sink.record("walk_state", state.name());
        sink.record("walk_ticks", String.valueOf(phaseTicks));
        sink.record("foot_after_walk", bot.blockPosition().toShortString());
        if (state != PathRetryRunner.State.DONE) {
            fail("walk_failed:" + state);
            return;
        }
        advance(Phase.OPEN);
    }

    /** 开菜单（`MenuSession`）；开之前先断言够得着，再把手腾空（拿着东西右键不会开 GUI —— 已实测教训）。 */
    private void open() {
        if (session == null) {
            double eyeDistance = bot.getEyePosition().distanceTo(machinePos.getCenter());
            sink.record("machine_reach", fmt(eyeDistance));
            if (!TableCraft.inReach(bot, machinePos)) {
                fail("machine_out_of_reach:" + fmt(eyeDistance) + "@" + bot.blockPosition().toShortString());
                return;
            }
            sink.record("hand_cleared", String.valueOf(clearHand()));
            session = MenuSession.open(bot, machinePos, 0);
            return;
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            fail("menu_open_failed:" + session.failure());
            return;
        }
        if (state != MenuSession.State.OPEN) {
            if (phaseTicks > OPEN_TICKS) {
                fail("menu_open_timeout");
            }
            return;
        }
        AbstractContainerMenu menu = bot.containerMenu;
        sink.record("menu_class", menu == null ? "-" : menu.getClass().getName());
        sink.record("menu_slots", menu == null ? "-" : String.valueOf(menu.slots.size()));
        if (spec.cleanBeforeStart()) {
            sink.record("machine_cleaned_before", drainMachine());
        } else {
            // 生产路径**不许**把机器里的东西搬走（那是别人的物品）⇒ 只观察、不清理。
            sink.record("machine_cleaned_before", "skipped（生产路径不动机器里的存量）");
        }
        sink.record("machine_empty_before", String.valueOf(firstMachineHolding(menu) == null));
        advance(Phase.ENERGY);
    }

    /**
     * 电前提：**只如实判断**，不造能量。机器已有电 ⇒ 记账放行；读不出 ⇒ 不据此判红（别的模组没有这套访问器，
     * 闭环真正的判据是产物）；宽限期后仍然 0 ⇒ `machine_no_energy`。
     *
     * <p>唯一的例外是调用方注入的 {@link EnergyTopUp}（**夹具的测试前提**，必然留痕 `energy_source=api_precharge`）。
     */
    private void ensureEnergy() {
        double energy = readEnergy(blockEntity);
        if (phaseTicks == 1) {
            sink.record("energy_at_open", energy < 0 ? "unreadable" : fmt(energy));
        }
        if (energy < 0) {
            sink.record("energy_source", "unreadable（上游没有可读的能量容器）");
            advance(Phase.FEED);
            return;
        }
        if (energy > 0) {
            sink.record("energy_source", spec.energyPresentLabel());
            sink.record("energy_ready", fmt(energy));
            advance(Phase.FEED);
            return;
        }
        if (phaseTicks < ENERGY_GRACE_TICKS) {
            return;
        }
        if (topUp == null) {
            sink.record("energy_source", "none（机器没有电；本路径**不补电**，如实失败）");
            fail("machine_no_energy");
            return;
        }
        boolean ok = topUp.apply(blockEntity);
        double after = readEnergy(blockEntity);
        sink.record("energy_source", ok
                ? "api_precharge（场景电源没喂上 ⇒ 按前提补电；补后 " + fmt(after) + " J）"
                : "none（既没电源也补不上电）");
        sink.record("energy_ready", fmt(after));
        if (after <= 0) {
            fail("machine_no_energy");
            return;
        }
        advance(Phase.FEED);
    }

    /** 放料：授权（容器写入维度）→ shift-click（**菜单自己决定落点**）→ **用结果验证**。 */
    private void feed() {
        if (phaseTicks == 1) {
            if (!allowContainerWrite("feed")) {
                return;
            }
            boolean clicked = StationProvision.moveIntoContainer(bot, bot.containerMenu, spec.input(), machinePos);
            sink.record("feed_click", String.valueOf(clicked));
            if (!clicked) {
                fail("feed_click_rejected");
                return;
            }
        }
        int inMachine = StationProvision.countInContainer(bot.containerMenu, bot, spec.input());
        if (inMachine > 0) {
            sink.record("feed_verified", "true in_machine=" + inMachine);
            advance(Phase.WAIT);
            return;
        }
        if (phaseTicks > EFFECT_TICKS) {
            fail("feed_no_effect");
        }
    }

    /** 等：真实进度（上游自述）+ 真实产物（容器里的物品）；超时**如实报**能量与进度，不猜原因。 */
    private void awaitProduct() {
        int out = StationProvision.countInContainer(bot.containerMenu, bot, spec.output());
        int inMachine = StationProvision.countInContainer(bot.containerMenu, bot, spec.input());
        int operating = readInt(blockEntity, "getOperatingTicks");
        if (operating > maxOperatingTicks) {
            maxOperatingTicks = operating;
        }
        if (readBoolean(blockEntity, "getActive")) {
            activeSeen = true;
        }
        if (out >= spec.outputCount()) {
            sink.record("wait_ticks", String.valueOf(phaseTicks));
            sink.record("progress_ticks", String.valueOf(maxOperatingTicks));
            sink.record("active_seen", String.valueOf(activeSeen));
            sink.record("energy_after_wait", fmt(readEnergy(blockEntity)));
            sink.record("input_left_in_machine", String.valueOf(inMachine));
            advance(Phase.TAKE);
            return;
        }
        if (phaseTicks >= spec.waitTicks()) {
            double energy = readEnergy(blockEntity);
            sink.record("progress_ticks", String.valueOf(maxOperatingTicks));
            sink.record("active_seen", String.valueOf(activeSeen));
            sink.record("energy_at_timeout", fmt(energy));
            sink.record("input_left_in_machine", String.valueOf(inMachine));
            fail("no_product_in_" + spec.waitTicks() + "ticks"
                    + (energy <= 0 ? ":no_energy" : ":progress_stalled"));
        }
    }

    /** 取产物：再一次授权 → shift-click 回背包 → **用结果验证**（背包里的产物变多）。 */
    private void take() {
        if (phaseTicks == 1) {
            if (!allowContainerWrite("take")) {
                return;
            }
            boolean clicked = StationProvision.moveOutOfContainer(bot, bot.containerMenu, spec.output());
            sink.record("take_click", String.valueOf(clicked));
            if (!clicked) {
                fail("take_click_rejected");
                return;
            }
        }
        if (RecipeQuery.countInInventory(bot, spec.output()) > spec.outputBefore()) {
            advance(Phase.VERIFY);
            return;
        }
        if (phaseTicks > EFFECT_TICKS) {
            fail("take_no_effect");
        }
    }

    /** 世界事实断言：产物**到了背包**、机器里**不再有产物**、料**被消耗**。 */
    private void verify() {
        int inInventory = RecipeQuery.countInInventory(bot, spec.output());
        int inMachine = StationProvision.countInContainer(bot.containerMenu, bot, spec.output());
        int inputLeft = StationProvision.countInContainer(bot.containerMenu, bot, spec.input());
        sink.record("product_after", String.valueOf(inInventory));
        sink.check("product_landed", inInventory >= spec.outputBefore() + spec.outputCount(),
                "before=" + spec.outputBefore() + " after=" + inInventory + " 期望+" + spec.outputCount());
        sink.check("machine_emptied", inMachine == 0, "机器里还剩产物=" + inMachine);
        sink.record("input_consumed", String.valueOf(inputLeft == 0));
        sink.record("budget_remaining_after", String.valueOf(WriteBudget.remainingContainerWrites(bot)));
        sink.record("container_writes", String.valueOf(containerWrites));
        advance(Phase.DONE);
    }

    // ==================== 授权与搬运 ====================

    /** 容器写入授权；`false` ⇒ 已如实记 `container_write_refused` 并终态失败。 */
    private boolean allowContainerWrite(String what) {
        WriteGrant grant = WriteGrant.of(spec.requester(), WriteReason.CONTAINER_TRANSFER);
        WriteBudget.Verdict verdict = WriteBudget.consumeContainerWrite(bot, machinePos, grant);
        sink.record(what + "_grant", verdict.name());
        if (verdict == WriteBudget.Verdict.REFUSED) {
            fail("container_write_refused");
            return false;
        }
        containerWrites++;
        return true;
    }

    /** 开菜单前把手腾空（持有物品会让右键变成"用物品"而不开 GUI —— 已实测教训）。 */
    private boolean clearHand() {
        var inventory = bot.getInventory();
        if (inventory.getItem(inventory.selected).isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.selected = slot;
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);
                return true;
            }
        }
        return false;
    }

    /** 开局清场：机器容器里**不属于玩家背包**的任何物品都搬回背包（**只给夹具用**）。 */
    private String drainMachine() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == null) {
            return "-";
        }
        int moved = 0;
        for (int guard = 0; guard < 32; guard++) {
            Item item = firstMachineHolding(menu);
            if (item == null) {
                break;
            }
            if (!allowContainerWrite("clean")) {
                break;
            }
            if (!StationProvision.moveOutOfContainer(bot, menu, item)) {
                break;
            }
            moved++;
        }
        return moved == 0 ? "already_empty" : "moved_stacks=" + moved;
    }

    private Item firstMachineHolding(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container == bot.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                return stack.getItem();
            }
        }
        return null;
    }

    // ==================== 上游自述（只读反射，两个调用方共用） ====================

    private static Object callPublicNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只读调用：`(Class[])` 指定参数类型，便于读上游的重载方法。 */
    public static Object callPublic(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readRecipeTypeName(Object blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        Object provider = callPublicNoArg(blockEntity, "getRecipeType");
        if (provider == null) {
            return null;
        }
        Object name = callPublicNoArg(provider, "getRegistryName");
        if (name instanceof ResourceLocation location) {
            return location.toString();
        }
        if (provider instanceof net.minecraft.world.item.crafting.RecipeType<?> type) {
            ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            return key == null ? null : key.toString();
        }
        return null;
    }

    /** 机器能量（J）；读不出返回 **-1**（"读不出"与"没电"必须分开）。 */
    public static double readEnergy(Object blockEntity) {
        List<?> containers = energyContainers(blockEntity);
        if (containers == null || containers.isEmpty()) {
            return -1;
        }
        Object energy = callPublicNoArg(containers.get(0), "getEnergy");
        Object value = energy == null ? null : callPublicNoArg(energy, "doubleValue");
        return value instanceof Double d ? d : -1;
    }

    /** 上游的能量容器列表（`getEnergyContainers(null)`）；**只读**出口，夹具的补电也用它取句柄。 */
    public static List<?> energyContainers(Object blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        Object value = callPublic(blockEntity, "getEnergyContainers",
                new Class<?>[]{Direction.class}, new Object[]{null});
        return value instanceof List<?> list ? list : null;
    }

    private static int readInt(Object target, String name) {
        Object value = callPublicNoArg(target, name);
        return value instanceof Integer i ? i : -1;
    }

    private static boolean readBoolean(Object target, String name) {
        Object value = callPublicNoArg(target, name);
        return value instanceof Boolean b && b;
    }

    // ==================== 小工具 ====================

    static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 如实失败：记下**第一个**失败码（后续相位不再推进）。 */
    private void fail(String code) {
        if (failure == null) {
            failure = code;
            BotLog.warn("[{}] 失败 {}", tag, code);
        }
        finished = true;
    }

    private void advance(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    /** 空实现（生产侧只想看失败码时用）。 */
    public static Sink noopSink() {
        return new Sink() {
            @Override
            public void record(String key, String value) {
            }

            @Override
            public void check(String name, boolean ok, String detail) {
            }
        };
    }

    /** 收集成 `Map` 的 sink（便于调用方二次加工）。 */
    public static Sink mapSink(Map<String, String> facts, List<String> failedChecks) {
        Map<String, String> target = facts == null ? new LinkedHashMap<>() : facts;
        return new Sink() {
            @Override
            public void record(String key, String value) {
                target.put(key, value);
            }

            @Override
            public void check(String name, boolean ok, String detail) {
                target.put(name, ok ? "true" : "false");
                if (!ok && failedChecks != null) {
                    failedChecks.add(name);
                }
            }
        };
    }
}
