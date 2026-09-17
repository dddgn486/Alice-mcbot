package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.MachineCycle;
import com.dddgn.alice.task.craft.MachineRecipeFacts;
import com.dddgn.alice.task.craft.RecipeQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **单机最小闭环自检**（阶段 3-B / S4）：放料 → 等 → 取产物。
 *
 * <p><b>入口</b>：**电池步 `machine_cycle`**（CORE 默认档内含，`RegressionBatteryTask.CURATION`）——
 * 临时物品 `alice:machine_cycle_check` 已按 **S5 收口**回收（D-215），不要再复活临时入口；
 * 本任务**自带传送与结束复位**，可被电池直接复用（PLAYBOOK §5.0d）。
 *
 * <p><b>本类现在只是"夹具"</b>（(c) 增量 2 / D-217 起）：闭环本体（走 → 开 → 电 → 放料 → 等 → 取 → 复核）
 * 已抽到 {@link MachineCycle}，与本任务**同一份实现**被生产路径 `CraftJob` 的 `MACHINE_ROUTE` 复用。
 * 夹具自己只留三样**测试专属**的东西（生产不该有）：
 * <ol>
 *   <li>**传送 + 起点前提 + 结束复位**（夹具纪律：不依赖电池 provision；起点确定、结束干净）；</li>
 *   <li>**按前提补电** {@link #PRECHARGE_JOULES}（{@link MachineCycle.EnergyTopUp} 的**唯一实现者**）——
 *       那是"场景电源必须自证"的兜底，**不是正常路径**；生产语义是"没电 ⇒ 如实失败"，
 *       所以那条通道**只允许住在本文件里**（门禁 `tools/check-precharge-containment.sh` 机械保证）；</li>
 *   <li>**按机器类型挑一道配方 + 自己备料**（夹具按测试前提把料补进空槽；生产路径缺料就如实失败）。</li>
 * </ol>
 *
 * <p><b>§6.9.1 三条前提</b>：① **几何盒** = 以 bot 脚位为中心、半径 {@value #SCAN_RADIUS} 的立方盒，
 * 目标方块由 {@link MachineMap}（单一出处）给，**找见之后要自己走过去**（起点 {@link #CYCLE_START} 是平台远角 ⇒
 * 内核寻路 `PathRequest.of` **纯通行**送到机器旁的可站格，再用 `inReach` 断言够得着）。
 * ⚠️ **S4 v2（D-216）改的正是这一条**：v1 把起点放在机器东侧 2 格、"传送完就够得着" ⇒
 * **"走到机器旁"这件事从来没被验证过**；v2 把这条隐式前提拆掉，**第十一轮客户端实测通过**
 * （`machine_distance_at_locate=8.5` → 内核自己走完 8 段 → `walk_state=DONE walk_ticks=41 machine_reach=1.5`）。
 * ② **世界/模组假设** = 场景 {@code alice_test:machine_course}
 * 已摆好机器**且给了电** —— ⚠️ **创造方块放下就是 0 J**（上游 `BasicEnergyContainer.stored = FloatingLong.ZERO`，
 * 且 creative 侧对 insert/extract 都强制 SIMULATE ⇒ 放下的空方块**永远灌不满、也放不出电**），
 * 所以场景不是"放个方块就有电"，而是用 `/data merge block … EnergyContainers=[{Container:0,stored:"4000000000"}]`
 * **把电直接写进方块实体**（走 `load()` → `setEnergy`，绕过 creative 的插入守卫；灌进去之后 extract 仍是
 * SIMULATE ⇒ 永不耗尽 = 真正的无限电源，2026-09-14 客户端实测确认，见 D-213）。
 * 电不够时**如实报出来**并按前提补电（宽限期后 `api_precharge` {@link #PRECHARGE_JOULES} J，
 * 那只是**兜底**，不是正常路径）；③ **层归属** = 断言落在"机器容器里到底有没有产物"这一**世界事实**上，
 * 不经查询层、不会被上游短路。
 *
 * <p><b>能量判据的准确含义</b>：`energy_source=cube（场景电源，未补电）` 的判据是**开机那一刻机器自己已有电**
 * （`energy_at_open > 0`）—— 它证明的是"**场景把电送上了**"，**不是**"程序认出了那是个 cube 方块"
 * （读的是机器自己的能量容器，认不出电源在哪）。场景里只有这一条供电路径 ⇒ 两者等价；
 * 一旦出现 `energy_source=api_precharge（…）`，说明场景电源失效，**要去查场景**，
 * 不允许放宽断言或删掉这条记录。
 *
 * <p><b>§6.9.3 三问自答</b>：① 层归属见上；② 依赖的假设都**自断言**（机器不在 ⇒ `machine_absent:radius_N`；
 * 够不着 ⇒ `machine_out_of_reach`；配方读不出 ⇒ `no_recipe_with_item_io`；预算拒绝 ⇒ `container_write_refused`；
 * 供不上料/取不回来 ⇒ `feed_*`/`take_*`；没电且补不上 ⇒ `machine_no_energy`）；
 * ③ 失败时用户侧**看得到动作**（bot 走到机器旁、菜单打开、物品进出），判据是聊天里的
 * `[MachineCycle] SUMMARY … verdict=FAIL`。
 */
public class MachineCycleCheckTask implements Task, MachineCycle.Sink {

    /** v1 只跑**一台**：表里已实测菜单类的那台；换机器 = 换这一行（表里的其它行同构）。 */
    private static final String TARGET_BLOCK = "mekanism:enrichment_chamber";
    /**
     * 找机器的立方盒半径。
     *
     * <p>**S4 v2 起 = 12**（原 6）：起点挪到平台远角后必须"先看见、再走过去"，
     * 半径 6 会让机器恰好落在立方盒角上（起点到机器 dx=dz=6）—— 那是**压线**，不是判据。
     * 平台是 13×13 孤立区（边界外一圈空气）⇒ 放大半径不会误认别的场景的机器。
     */
    private static final int SCAN_RADIUS = 12;
    private static final int MAX_TICKS = 1400;
    /** 走过去的上限（tick；平台对角 ~9.9 格，实测几十 tick 级，留足余量）。 */
    private static final int WALK_TICKS = 400;
    /** 等产物上限（★**不缩短**：这是真实机器进度，缩短只会把真绿变成假红）。 */
    private static final int WAIT_TICKS = 600;
    private static final double PRECHARGE_JOULES = 4.0E6D;

    /**
     * **S4 v2 起点 = 平台远角**（x/z 60..72, 300..312，地板 y=63）。
     *
     * <p>为什么改：v1 把起点放在机器东侧 2 格 ⇒ "传送完就够得着"，**闭环从没验过"走过去"**。
     * v2 起点到机器 `dx=dz=6`（≈8.49 格，远超交互距离）⇒ 夹具**仍然自己传送**（纪律不变：
     * 不依赖电池 provision、起点确定），但传送之后**必须走一段**才能开菜单 ⇒ 到机器旁这件事
     * 由**内核寻路**负责（`PathRequest.of` 纯通行，D-076；不挖不搭）。
     */
    public static final BlockPos CYCLE_START = new BlockPos(72, 64, 312);

    private enum Phase { PREPARE, CYCLE, RESET, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private boolean finished;
    private MachineCycle cycle;

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
        BlockPos anchor = cycle != null && cycle.machinePos() != null ? cycle.machinePos() : CYCLE_START;
        return TaskTarget.block(anchor);
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
            case CYCLE -> driveCycle();
            case RESET -> reset();
            case DONE -> finish();
        };
    }

    /** 自带传送 + 起点前提（夹具纪律：不依赖电池 provision，standalone 右键也成立）。 */
    private Status prepare() {
        if (phaseTicks == 1) {
            // **传送与判据分到两个 tick**（2026-09-17 CORE 实测的假红）：`teleportTo` 那一 tick 读
            // `onGround` 得到的是**上一处**的陈旧值 ⇒ 上一处若在空中/刚被传送，这里就会把
            // "bot 明明站得好好的"判成 `premise_on_ground` 失败 ✗（模块化之后才暴露：
            // 旧电池恰好让上一步把 bot 留成站姿，纯属运气 ✓）。⇒ 细则见 `FixturePremise.SETTLE_TICKS`。
            bot.teleportTo(bot.serverLevel(), CYCLE_START.getX() + 0.5D, CYCLE_START.getY(),
                    CYCLE_START.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(Vec3.ZERO);
            bot.controller().stopMovement();
            record("on_ground_immediately_after_teleport", FixturePremise.onGround(bot).detail());
            BotLog.info("[MachineCycle] 已传送 bot 到**远角起点** {}（{}）—— 等物理结算后再判落地 ✓",
                    CYCLE_START.toShortString(), bot.blockPosition().toShortString());
            return Status.RUNNING;
        }
        if (!FixturePremise.settledOnGround(bot, phaseTicks)) {
            if (phaseTicks > FixturePremise.SETTLE_TICKS + 60) {
                return failAndFinish("not_on_ground");
            }
            return Status.RUNNING;
        }
        var ground = FixturePremise.onGround(bot);
        var ownMenu = FixturePremise.ownMenu(bot);
        check("premise_on_ground", ground.ok(), ground.detail()
                + "（传送后第 " + phaseTicks + " tick 复核 ⇒ 不是传送那一 tick 的陈旧读数 ✓）");
        check("premise_own_menu", ownMenu.ok(), ownMenu.detail());
        record("start_pos", bot.blockPosition().toShortString());
        // 按表认机器（`MachineMap` 是唯一出处）+ 按类型挑一道配方（都在**读表**，不动世界）
        MachineMap.Row row = MachineMap.forBlock(TARGET_BLOCK);
        if (row == null) {
            return failAndFinish("table_has_no_block:" + TARGET_BLOCK);
        }
        // **先判"模组在不在"**（T0-a，2026-09-14 堵假绿）。
        // 为什么必须先判：`MachineMap` 是**编译期常量表**，模组没装时 `forBlock` 照样返回那一行，
        // 于是会一路走到"一条配方都读不到"，而那条出口（`no_recipe_with_item_io`）**不含 `_absent`**
        // ⇒ 电池把它记成 **FAIL（红）** ⇒ **"环境不具备"被误报成"功能坏了"**（三路审计 E-3 实证）。
        // 判据 = **上游自述**：该配方类型有没有被注册（模组不在 ⇒ 类型不在注册表里）——
        // 不认类名、不猜语义。注册表里有类型、却仍挑不到"物品进/物品出"的配方 ⇒ 那才是**真失败**
        // （上游配方集变了），走下面的 `no_recipe_with_item_io` 如实报红。
        net.minecraft.resources.ResourceLocation typeRes =
                net.minecraft.resources.ResourceLocation.tryParse(row.typeId());
        if (typeRes == null
                || !net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.containsKey(typeRes)) {
            return failAndFinish("machine_mod_absent:recipe_type_" + row.typeId());
        }
        MachineRecipeFacts.Facts chosen = pickRecipe(row);
        if (chosen == null) {
            return failAndFinish("no_recipe_with_item_io:" + row.typeId());
        }
        Item input = chosen.inputs().get(0).getItem();
        int inputCount = Math.max(1, chosen.inputs().get(0).getCount());
        Item output = chosen.outputs().get(0).getItem();
        int outputCount = Math.max(1, chosen.outputs().get(0).getCount());
        int outputBefore = RecipeQuery.countInInventory(bot, output);
        record("recipe_id", chosen.recipeId());
        record("input", id(input) + " x" + inputCount);
        record("output", id(output) + " x" + outputCount);
        record("product_before", String.valueOf(outputBefore));
        // 夹具自备料（**测试前提**：生产路径缺料就该如实失败，不许凭空变物品）
        record("provision", provision(input, inputCount));
        cycle = new MachineCycle(bot, this, "MachineCycle",
                new MachineCycle.Spec(TARGET_BLOCK, row.typeId(), SCAN_RADIUS,
                        "machine-cycle-walk", "machine-walk", WALK_TICKS, WAIT_TICKS,
                        input, inputCount, output, outputCount, outputBefore,
                        "machine-cycle", "cube（场景电源，未补电）", true),
                blockEntity -> precharge(blockEntity, PRECHARGE_JOULES));
        return advance(Phase.CYCLE);
    }

    /** 驱动共用的闭环执行器（同一份实现也跑在生产路径里）。 */
    private Status driveCycle() {
        MachineCycle.State state = cycle.tick();
        if (state == MachineCycle.State.FAILED) {
            return failAndFinish(cycle.failure());
        }
        return state == MachineCycle.State.DONE ? advance(Phase.RESET) : Status.RUNNING;
    }

    /** 结束复位：关菜单/停输入/回起点（成功与失败路径都走）。 */
    private Status reset() {
        cycle.closeMenu("machine_cycle_end");
        bot.teleportTo(bot.serverLevel(), CYCLE_START.getX() + 0.5D, CYCLE_START.getY(),
                CYCLE_START.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        record("reset_pos", bot.blockPosition().toShortString());
        record("reset", "true");
        record("residue", residue());
        return advance(Phase.DONE);
    }

    // ==================== 夹具专属：按类型挑配方 / 备料 / 补电 ====================

    /**
     * 选一道**物品进出可读**的配方：按配方 id 排序取第一道（确定性）。
     *
     * <p>输入取**配料自己的第一个代表物品**（`getRepresentations()` 的成员 ⇒ 一定是该配料认可的物品，
     * 机不认可由机器自己拒——**我们不猜语义**）；输出取第一个。化学品/气体输入的机器不属 v1，
     * 如实报 `no_recipe_with_item_io`。
     */
    private MachineRecipeFacts.Facts pickRecipe(MachineMap.Row row) {
        List<Recipe<?>> candidates = new ArrayList<>();
        var access = bot.serverLevel().registryAccess();
        for (Recipe<?> recipe : bot.serverLevel().getRecipeManager().getRecipes()) {
            ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            if (key == null || !key.toString().equals(row.typeId())) {
                continue;
            }
            MachineRecipeFacts.Facts read = MachineRecipeFacts.read(recipe, access);
            if (read.itemReadable() && !read.inputs().get(0).isEmpty() && !read.outputs().get(0).isEmpty()) {
                candidates.add(recipe);
            }
        }
        record("recipes_considered", String.valueOf(candidates.size()));
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparing(candidate -> candidate.getId().toString()));
        return MachineRecipeFacts.read(candidates.get(0), access);
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

    /**
     * 前提补电：`getEnergyContainers(null)` → `IEnergyContainer.setEnergy(FloatingLong)`。
     * 只碰**我们自己在跑的这台机器**，且必然在 SUMMARY 里留痕（`energy_source=api_precharge`）。
     *
     * <p><b>这段代码只许存在于此</b>：它是**测试前提**，不是生产行为（D-216 红线①）。
     * 门禁 `tools/check-precharge-containment.sh` 会断言 `setEnergy` / `PRECHARGE` / `precharge`
     * 在源码里**只命中本文件**。
     */
    private static boolean precharge(Object blockEntity, double joules) {
        Object floatingLong;
        try {
            Class<?> type = Class.forName("mekanism.api.math.FloatingLong");
            floatingLong = type.getMethod("create", double.class).invoke(null, joules);
        } catch (Throwable ignored) {
            return false;
        }
        List<?> containers = MachineCycle.energyContainers(blockEntity);
        if (containers == null || containers.isEmpty()) {
            return false;
        }
        for (Object container : containers) {
            MachineCycle.callPublic(container, "setEnergy", new Class<?>[]{floatingLong.getClass()}, floatingLong);
        }
        return MachineCycle.readEnergy(blockEntity) > 0;   // **按结果判定**：设不进去就不算补上
    }

    /** 结束时背包里还剩什么（**如实报**：产物就是本轮的证据，不假装"什么都没留"）。 */
    private String residue() {
        StringBuilder text = new StringBuilder();
        var inventory = bot.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
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

    // ==================== 记账与收尾 ====================

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
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
        if (cycle != null) {
            cycle.closeMenu("machine_cycle_end");
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

    @Override
    public void record(String key, String value) {
        facts.put(key, value);
    }

    @Override
    public void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[MachineCycle] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }
}
