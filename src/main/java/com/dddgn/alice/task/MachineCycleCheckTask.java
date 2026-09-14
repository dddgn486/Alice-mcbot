package com.dddgn.alice.task;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.MachineRecipeFacts;
import com.dddgn.alice.task.craft.RecipeQuery;
import com.dddgn.alice.task.craft.StationProvision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * **单机最小闭环自检**（阶段 3-B / S4，{@code alice:machine_cycle_check}）：放料 → 等 → 取产物。
 *
 * <p>要回答的问题：Alice 能不能**真的把一台模组机器跑起来一次**（不是"读得出配方"、也不是"开得出菜单"）。
 * 这是 3-B 的第一次**写入**：S0–S3 全是只读，S4 动的是容器的物品位置（算世界改动 ⇒ 有授权 + 预算）。
 *
 * <p><b>写入路径（一处也不新造）</b>：容器写入维度 {@link WriteBudget#consumeContainerWrite}
 * + 理由 {@link WriteReason#CONTAINER_TRANSFER} + requester `machine-cycle`（矩阵里登记为 `CONTAINER`）；
 * 动作走 {@link StationProvision} 的成熟菜单协议（**shift-click，让菜单自己决定落点**），
 * 成功与否一律**用结果验证**（机器里出现了料 / 背包里出现了产物），**不猜槽位语义**。
 * 槽位角色另有上游自述（`MachineStationProbeTask` 的 `slot_roles`）作为旁证。
 *
 * <p><b>§6.9.1 三条前提</b>：① **几何盒** = 以 bot 脚位为中心、半径 {@value #SCAN_RADIUS} 的立方盒，
 * 目标方块由 {@link MachineMap}（单一出处）给；② **世界/模组假设** = 场景 {@code alice_test:machine_course}
 * 已摆好机器**且给了电** —— ⚠️ **创造方块放下就是 0 J**（上游 `BasicEnergyContainer.stored = FloatingLong.ZERO`，
 * 且 creative 侧对 insert/extract 都强制 SIMULATE ⇒ 放下的空方块**永远灌不满、也放不出电**），
 * 所以场景不是"放个方块就有电"，而是用 `/data merge block … EnergyContainers=[{Container:0,stored:"4000000000"}]`
 * **把电直接写进方块实体**（走 `load()` → `setEnergy`，绕过 creative 的插入守卫；灌进去之后 extract 仍是
 * SIMULATE ⇒ 永不耗尽 = 真正的无限电源，2026-09-14 客户端实测确认，见 D-213）。
 * 电不够时**如实报出来**并按前提补电（{@link #ENERGY_GRACE_TICKS} 后 `api_precharge` {@link #PRECHARGE_JOULES} J，
 * 那只是**兜底**，不是正常路径）；③ **层归属** = 断言落在"机器容器里到底有没有产物"这一**世界事实**上，
 * 不经查询层、不会被上游短路。
 *
 * <p><b>能量判据的准确含义</b>：`energy_source=cube（场景电源，未补电）` 的判据是**开机那一刻机器自己已有电**
 * （`energy_at_open > 0`）—— 它证明的是"**场景把电送上了**"，**不是**"程序认出了那是个 cube 方块"
 * （读的是机器自己的能量容器，认不出电源在哪）。场景里只有这一条供电路径 ⇒ 两者等价；
 * 一旦出现 `energy_source=api_precharge（… 按前提补电 …）`，说明场景电源失效，**要去查场景**，
 * 不允许放宽断言或删掉这条记录。
 *
 * <p><b>§6.9.3 三问自答</b>：① 层归属见上；② 依赖的假设都**自断言**（机器不在 ⇒ `machine_absent:radius_N`；
 * 够不着 ⇒ `machine_out_of_reach`；配方读不出 ⇒ `no_recipe_with_item_io`；预算拒绝 ⇒ `container_write_refused`；
 * 供不上料/取不回来 ⇒ `feed_*`/`take_*`）；③ 失败时用户侧**看得到动作**（bot 走到机器旁、菜单打开、
 * 物品进出），判据是聊天里的 `[MachineCycle] SUMMARY … verdict=FAIL`。
 */
public class MachineCycleCheckTask implements Task {

    /** v1 只跑**一台**：表里已实测菜单类的那台；换机器 = 换这一行（表里的其它行同构）。 */
    private static final String TARGET_BLOCK = "mekanism:enrichment_chamber";
    private static final int SCAN_RADIUS = 6;
    /** 交互可达上限（原版 `blockInteractionRange` = 4.5，留余量给眼高换算）。 */
    private static final double REACH_LIMIT = 4.4;
    private static final int MAX_TICKS = 1400;
    private static final int OPEN_TICKS = 80;
    /** 等产物上限（★**不缩短**：这是真实机器进度，缩短只会把真绿变成假红）。 */
    private static final int WAIT_TICKS = 600;
    /** 一次点击后等"结果出现"的上限。 */
    private static final int EFFECT_TICKS = 40;
    /** 开局给电的宽限期：先等场景里的电源把机器喂起来，够不到才走前提补电。 */
    private static final int ENERGY_GRACE_TICKS = 60;
    private static final double PRECHARGE_JOULES = 4.0E6D;

    /** 场景起点（与只读探针同一个起点；机器在它东侧）。 */
    public static final BlockPos START = MachineStationProbeTask.START;

    private enum Phase { PREPARE, LOCATE, OPEN, ENERGY, RECIPE, FEED, WAIT, TAKE, VERIFY, RESET, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private boolean finished;
    private MenuSession session;

    private MachineMap.Row row;
    private BlockPos machinePos;
    private Object blockEntity;
    private Item inputItem;
    private Item outputItem;
    private int inputCount = 1;
    private int outputCount = 1;
    private int outputBefore;
    private int containerWrites;
    private int maxOperatingTicks = -1;
    private boolean activeSeen;

    public MachineCycleCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MachineCycleCheck";     // `*CheckTask` 约定：自检不招 LLM
    }

    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(machinePos == null ? START : machinePos);
    }

    @Override
    public String failureReason() {
        return String.join(",", failures);
    }

    @Override
    public Status tick() {
        if (finished) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (++ticks > MAX_TICKS) {
            failures.add("cycle_timeout");
            return finish();
        }
        phaseTicks++;
        return switch (phase) {
            case PREPARE -> prepare();
            case LOCATE -> locate();
            case OPEN -> open();
            case ENERGY -> ensureEnergy();
            case RECIPE -> pickRecipe();
            case FEED -> feed();
            case WAIT -> awaitProduct();
            case TAKE -> take();
            case VERIFY -> verify();
            case RESET -> reset();
            case DONE -> finish();
        };
    }

    /** 自带传送 + 起点前提（夹具纪律：不依赖电池 provision，standalone 右键也成立）。 */
    private Status prepare() {
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        var ground = FixturePremise.onGround(bot);
        var ownMenu = FixturePremise.ownMenu(bot);
        check("premise_on_ground", ground.ok(), ground.detail());
        check("premise_own_menu", ownMenu.ok(), ownMenu.detail());
        record("start_pos", bot.blockPosition().toShortString());
        BotLog.info("[MachineCycle] 已传送 bot 到场景起点 {}（{}）", START.toShortString(),
                bot.blockPosition().toShortString());
        return advance(Phase.LOCATE);
    }

    /** 按表认机器（{@link MachineMap} 是唯一出处；半径内取最近一台）。 */
    private Status locate() {
        row = MachineMap.forBlock(TARGET_BLOCK);
        if (row == null) {
            return failAndFinish("table_has_no_block:" + TARGET_BLOCK);
        }
        BlockPos center = bot.blockPosition();
        BlockPos found = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            String id = BuiltInRegistries.BLOCK.getKey(bot.serverLevel().getBlockState(pos).getBlock()).toString();
            if (!id.equals(TARGET_BLOCK)) {
                continue;
            }
            double distance = Math.sqrt(pos.distSqr(center));
            if (distance < best) {
                best = distance;
                found = pos.immutable();
            }
        }
        if (found == null) {
            return failAndFinish("machine_absent:radius_" + SCAN_RADIUS);
        }
        machinePos = found;
        record("machine", TARGET_BLOCK + "@" + found.toShortString());
        record("machine_reach", fmt(best));
        record("machine_type", row.typeId());
        blockEntity = bot.serverLevel().getBlockEntity(found);
        record("machine_be", blockEntity == null ? "-" : blockEntity.getClass().getName());
        String beType = readRecipeTypeName(blockEntity);
        if (beType == null) {
            record("binding", "unverified（方块实体没有可读的 getRecipeType/getRegistryName）");
        } else {
            check("binding", row.typeId().equals(beType), "表=" + row.typeId() + " 方块实体自述=" + beType);
        }
        if (best > REACH_LIMIT) {
            return failAndFinish("machine_out_of_reach:" + fmt(best));
        }
        return advance(Phase.OPEN);
    }

    /** 开菜单（`MenuSession`：与只读探针同一条路）；开之前先把手腾空，避免"拿着东西右键不打开 GUI"。 */
    private Status open() {
        if (session == null) {
            record("hand_cleared", String.valueOf(clearHand()));
            session = MenuSession.open(bot, machinePos, 0);
            return phaseTicks > OPEN_TICKS ? failAndFinish("menu_open_timeout") : Status.RUNNING;
        }
        MenuSession.State state = session.tick();
        if (state == MenuSession.State.FAILED) {
            return failAndFinish("menu_open_failed:" + session.failure());
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish("menu_open_timeout") : Status.RUNNING;
        }
        AbstractContainerMenu menu = bot.containerMenu;
        record("menu_class", menu == null ? "-" : menu.getClass().getName());
        record("menu_slots", menu == null ? "-" : String.valueOf(menu.slots.size()));
        // 前提：机器开始前必须是空的（上一次没清干净 ⇒ 夹具自己清，并如实留痕）
        String cleaned = drainMachine();
        record("machine_cleaned_before", cleaned);
        record("machine_empty_before", String.valueOf(firstMachineHolding(menu) == null));
        return advance(Phase.ENERGY);
    }

    /**
     * 给电前提：先等场景电源（`mekanism:creative_energy_cube`）把机器喂起来；宽限期内还是 0 才按前提补电。
     *
     * <p>**为什么允许补电**：S4 的目标是"放料→等→取产物"这条**闭环**；供电是另一条能力线（Alice 还没有
     * 发电/铺线能力）。补电是**测试前提**（与夹具传送同规格），且**必然留痕**（`energy_source`），
     * 不会把"电从哪来"这件事瞒过去。
     */
    private Status ensureEnergy() {
        double energy = readEnergy(blockEntity);
        if (phaseTicks == 1) {
            record("energy_at_open", energy < 0 ? "unreadable" : fmt(energy));
        }
        if (energy < 0) {
            // 读不出 ≠ 没电：**不据此判红**（别的模组没有这套访问器），闭环真正的判据是产物。
            record("energy_source", "unreadable（上游没有可读的能量容器）");
            return advance(Phase.RECIPE);
        }
        if (energy > 0) {
            record("energy_source", "cube（场景电源，未补电）");
            record("energy_ready", fmt(energy));
            return advance(Phase.RECIPE);
        }
        if (phaseTicks < ENERGY_GRACE_TICKS) {
            return Status.RUNNING;
        }
        boolean ok = precharge(blockEntity, PRECHARGE_JOULES);
        double after = readEnergy(blockEntity);
        record("energy_source", ok
                ? "api_precharge（场景电源没喂上 ⇒ 按前提补电 " + fmt(PRECHARGE_JOULES) + " J）"
                : "none（既没电源也补不上电）");
        record("energy_ready", fmt(after));
        if (after <= 0) {
            return failAndFinish("no_energy");
        }
        return advance(Phase.RECIPE);
    }

    /**
     * 选一道**物品进出可读**的配方：按配方 id 排序取第一道（确定性）。
     *
     * <p>输入取**配料自己的第一个代表物品**（`getRepresentations()` 的成员 ⇒ 一定是该配料认可的物品，
     * 机不认可由机器自己拒——**我们不猜语义**）；输出取第一个。化学品/气体输入的机器不属 v1，
     * 如实报 `no_recipe_with_item_io`。
     */
    private Status pickRecipe() {
        List<Recipe<?>> candidates = new ArrayList<>();
        for (Recipe<?> recipe : bot.serverLevel().getRecipeManager().getRecipes()) {
            ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            if (key == null || !key.toString().equals(row.typeId())) {
                continue;
            }
            MachineRecipeFacts.Facts read = MachineRecipeFacts.read(recipe);
            if (read.itemReadable() && !read.inputs().get(0).isEmpty() && !read.outputs().get(0).isEmpty()) {
                candidates.add(recipe);
            }
        }
        record("recipes_considered", String.valueOf(candidates.size()));
        if (candidates.isEmpty()) {
            return failAndFinish("no_recipe_with_item_io:" + row.typeId());
        }
        candidates.sort(Comparator.comparing(candidate -> candidate.getId().toString()));
        MachineRecipeFacts.Facts chosen = MachineRecipeFacts.read(candidates.get(0));
        ItemStack in = chosen.inputs().get(0);
        ItemStack out = chosen.outputs().get(0);
        inputItem = in.getItem();
        inputCount = Math.max(1, in.getCount());
        outputItem = out.getItem();
        outputCount = Math.max(1, out.getCount());
        outputBefore = RecipeQuery.countInInventory(bot, outputItem);
        record("recipe_id", chosen.recipeId());
        record("input", id(inputItem) + " x" + inputCount);
        record("output", id(outputItem) + " x" + outputCount);
        record("product_before", String.valueOf(outputBefore));
        return advance(Phase.FEED);
    }

    /** 放料：授权（容器写入维度）→ shift-click（**菜单自己决定落点**）→ **用结果验证**。 */
    private Status feed() {
        if (phaseTicks == 1) {
            record("provision", provision(inputItem, inputCount));
            if (!allowContainerWrite("feed")) {
                return failAndFinish("container_write_refused");
            }
            boolean clicked = StationProvision.moveIntoContainer(bot, bot.containerMenu, inputItem, machinePos);
            record("feed_click", String.valueOf(clicked));
            if (!clicked) {
                return failAndFinish("feed_click_rejected");
            }
        }
        int inMachine = StationProvision.countInContainer(bot.containerMenu, bot, inputItem);
        if (inMachine > 0) {
            record("feed_verified", "true in_machine=" + inMachine);
            return advance(Phase.WAIT);
        }
        return phaseTicks > EFFECT_TICKS ? failAndFinish("feed_no_effect") : Status.RUNNING;
    }

    /** 等：真实进度（上游自述）+ 真实产物（容器里的物品）；超时**如实报**能量与进度，不猜原因。 */
    private Status awaitProduct() {
        int out = StationProvision.countInContainer(bot.containerMenu, bot, outputItem);
        int inMachine = StationProvision.countInContainer(bot.containerMenu, bot, inputItem);
        int operating = readInt(blockEntity, "getOperatingTicks");
        if (operating > maxOperatingTicks) {
            maxOperatingTicks = operating;
        }
        if (readBoolean(blockEntity, "getActive")) {
            activeSeen = true;
        }
        if (out > 0) {
            record("wait_ticks", String.valueOf(phaseTicks));
            record("progress_ticks", String.valueOf(maxOperatingTicks));
            record("active_seen", String.valueOf(activeSeen));
            record("energy_after_wait", fmt(readEnergy(blockEntity)));
            record("input_left_in_machine", String.valueOf(inMachine));
            return advance(Phase.TAKE);
        }
        if (phaseTicks >= WAIT_TICKS) {
            double energy = readEnergy(blockEntity);
            record("progress_ticks", String.valueOf(maxOperatingTicks));
            record("active_seen", String.valueOf(activeSeen));
            record("energy_at_timeout", fmt(energy));
            record("input_left_in_machine", String.valueOf(inMachine));
            return failAndFinish("no_product_in_" + WAIT_TICKS + "ticks"
                    + (energy <= 0 ? ":no_energy" : ":progress_stalled"));
        }
        return Status.RUNNING;
    }

    /** 取产物：再一次授权 → shift-click 回背包 → **用结果验证**（背包里的产物变多）。 */
    private Status take() {
        if (phaseTicks == 1) {
            if (!allowContainerWrite("take")) {
                return failAndFinish("container_write_refused");
            }
            boolean clicked = StationProvision.moveOutOfContainer(bot, bot.containerMenu, outputItem);
            record("take_click", String.valueOf(clicked));
            if (!clicked) {
                return failAndFinish("take_click_rejected");
            }
        }
        if (RecipeQuery.countInInventory(bot, outputItem) > outputBefore) {
            return advance(Phase.VERIFY);
        }
        return phaseTicks > EFFECT_TICKS ? failAndFinish("take_no_effect") : Status.RUNNING;
    }

    /** 世界事实断言：产物**到了背包**、机器里**不再有产物**、料**被消耗**。 */
    private Status verify() {
        int inInventory = RecipeQuery.countInInventory(bot, outputItem);
        int inMachine = StationProvision.countInContainer(bot.containerMenu, bot, outputItem);
        int inputLeft = StationProvision.countInContainer(bot.containerMenu, bot, inputItem);
        record("product_after", String.valueOf(inInventory));
        check("product_landed", inInventory >= outputBefore + outputCount,
                "before=" + outputBefore + " after=" + inInventory + " 期望+" + outputCount);
        check("machine_emptied", inMachine == 0, "机器里还剩产物=" + inMachine);
        record("input_consumed", String.valueOf(inputLeft == 0));
        record("budget_remaining_after", String.valueOf(WriteBudget.remainingContainerWrites(bot)));
        record("container_writes", String.valueOf(containerWrites));
        return advance(Phase.RESET);
    }

    /** 结束复位：关菜单/停输入/回起点（成功与失败路径都走）。 */
    private Status reset() {
        if (session != null) {
            session.close("machine_cycle_end");
            session = null;
        }
        if (bot.containerMenu != null && !(bot.containerMenu instanceof InventoryMenu)) {
            bot.closeContainer();
        }
        bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        record("reset_pos", bot.blockPosition().toShortString());
        record("reset", "true");
        record("residue", residue());
        return advance(Phase.DONE);
    }

    // ==================== 授权与搬运 ====================

    /** 容器写入授权（G5/A11 同源维度）；`false` ⇒ 调用方**如实报 `container_write_refused`**。 */
    private boolean allowContainerWrite(String what) {
        WriteGrant grant = WriteGrant.of("machine-cycle", WriteReason.CONTAINER_TRANSFER);
        WriteBudget.Verdict verdict = WriteBudget.consumeContainerWrite(bot, machinePos, grant);
        record(what + "_grant", verdict.name());
        if (verdict == WriteBudget.Verdict.REFUSED) {
            return false;
        }
        containerWrites++;
        return true;
    }

    /** 夹具自备料：背包不够就补进空槽（**报出真正给了多少**）。 */
    private String provision(Item item, int count) {
        var inventory = bot.getInventory();
        int have = RecipeQuery.countInInventory(bot, item);
        int need = count - have;
        int given = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && need > 0; slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int put = Math.min(need, inventory.getMaxStackSize());
            inventory.setItem(slot, new ItemStack(item, put));
            need -= put;
            given += put;
        }
        return "given=" + given + " have=" + RecipeQuery.countInInventory(bot, item) + "/" + count;
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

    /** 开局清场：机器容器里**不属于玩家背包**的任何物品都搬回背包（夹具自己复位，不留脏前提）。 */
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

    /** 结束时背包里还剩什么（**如实报**：产物就是本轮的证据，不假装"什么都没留"）。 */
    private String residue() {
        StringBuilder text = new StringBuilder();
        var inventory = bot.getInventory();
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(), Integer::sum);
            }
        }
        for (var entry : counts.entrySet()) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(entry.getKey()).append("x").append(entry.getValue());
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    // ==================== 上游自述（只读反射） ====================

    private static Object callPublicNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callPublic(Object target, String name, Class<?>[] types, Object... args) {
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
    private static double readEnergy(Object blockEntity) {
        List<?> containers = energyContainers(blockEntity);
        if (containers == null || containers.isEmpty()) {
            return -1;
        }
        Object energy = callPublicNoArg(containers.get(0), "getEnergy");
        Object value = energy == null ? null : callPublicNoArg(energy, "doubleValue");
        return value instanceof Double d ? d : -1;
    }

    /**
     * 前提补电：`getEnergyContainers(null)` → `IEnergyContainer.setEnergy(FloatingLong)`。
     * 只碰**我们自己在跑的这台机器**，且必然在 SUMMARY 里留痕（`energy_source=api_precharge`）。
     */
    private static boolean precharge(Object blockEntity, double joules) {
        Object floatingLong;
        try {
            Class<?> type = Class.forName("mekanism.api.math.FloatingLong");
            floatingLong = type.getMethod("create", double.class).invoke(null, joules);
        } catch (Throwable ignored) {
            return false;
        }
        List<?> containers = energyContainers(blockEntity);
        if (containers == null || containers.isEmpty()) {
            return false;
        }
        for (Object container : containers) {
            callPublic(container, "setEnergy", new Class<?>[]{floatingLong.getClass()}, floatingLong);
        }
        return readEnergy(blockEntity) > 0;     // **按结果判定**：设不进去就不算补上
    }

    private static List<?> energyContainers(Object blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        Object value = callPublic(blockEntity, "getEnergyContainers",
                new Class<?>[]{net.minecraft.core.Direction.class}, new Object[]{null});
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

    // ==================== 记账与收尾 ====================

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status failAndFinish(String code) {
        failures.add(code);
        BotLog.warn("[MachineCycle] 失败 {}", code);
        return finish();
    }

    private Status finish() {
        phase = Phase.DONE;
        finished = true;
        if (session != null) {
            session.close("machine_cycle_end");
            session = null;
        }
        StringBuilder summary = new StringBuilder();
        for (var entry : facts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[MachineCycle] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MachineCycle] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    private void record(String key, String value) {
        facts.put(key, value);
    }

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[MachineCycle] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }
}
