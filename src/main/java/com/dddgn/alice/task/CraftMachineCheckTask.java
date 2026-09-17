package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.job.craft.CraftJob;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.RecipeQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **机器路线的生产路径自检**（阶段 3-B / (c) 增量 2 / D-217）：`CraftJob` 真的把一台机器跑起来。
 *
 * <p><b>它验的是"生产"，不是"夹具"</b>：本任务**不碰**闭环的任何一步，只做三件测试专属的事 ——
 * ① 传送 bot 到平台远角并复位（夹具纪律，PLAYBOOK §5.0d）；② **挑一个"只能靠机器做出来"的目标物**
 * （用生产自己的查询层 {@link RecipeQuery} 判，判不出就如实失败，不猜）；③ 按测试前提**把料放进背包**
 * （生产路径缺料就该如实失败，不许凭空变物品 —— 所以备料必须由测试侧做，且如实留痕）。
 * 剩下的"走 → 开 → 电 → 放料 → 等 → 取"全部由 {@link CraftJob} 走它自己的机器路线完成。
 *
 * <p><b>为什么目标物要"只能靠机器"</b>：`RecipeQuery` 只有在"**没有任何非机器路线**能产出它"时才给
 * `MACHINE_ROUTE`。若目标物有合成/熔炼路线（例如 `minecraft:charcoal` 能烧木头得到），查询层会走那条 ⇒
 * 生产路径根本不会碰机器，这个自检就成了假绿。候选清单是**离线对撞**出来的（jar 里 `mekanism:enriching`
 * 的 98 道配方 × 客户端配方表里"非 Mekanism 配方都不产出"的 20 个），逐个**用生产查询层现场复核**。
 *
 * <p><b>判据（只看世界事实 + 生产自己留的痕）</b>：① `CraftJob` 终态 `COMPLETED`；
 * ② 目标物在**背包里真的多了**；③ `CraftJob` 的机器路线事实非空且 `walk_state=DONE`（**它自己走到机器旁**）、
 * `product_landed=true`、`machine_emptied=true`（这三条来自生产侧的执行器出口，不是我另算的）。
 *
 * <p>机器不在 / 模组未装 ⇒ `machine_absent:radius_N` ⇒ 电池按 SKIP 处理（同 `machine_*` 其它步）。
 */
public class CraftMachineCheckTask implements Task {

    /** 起点用 S4 v2 的**平台远角**（到机器 ≈8.49 格，远超交互距离）—— 生产路径必须自己走过去。 */
    private static final BlockPos START = MachineCycleCheckTask.CYCLE_START;
    private static final int SCAN_RADIUS = 12;
    private static final int MAX_TICKS = 1600;
    /** `CraftJob` 自己的预算（要够：走 400 + 等 600 + 开关/投取 ≈100）。 */
    private static final int CRAFT_TICKS = 1400;

    /**
     * 候选目标物（**产出只能靠机器**，且输入是**单个普通物品**）。
     *
     * <p>出处：`mekanism:enriching` 的静态配方 × 客户端配方表对撞（2026-09-14，见 D-217）；
     * 每一项在运行期仍要用 {@link RecipeQuery} 现场复核，复核不过就试下一个。
     */
    private static final List<String> CANDIDATE_TARGETS = List.of(
            "minecraft:clay_ball",
            "minecraft:soul_soil",
            "minecraft:glowstone_dust",
            "minecraft:exposed_copper");

    private enum Phase { PREPARE, PICK, RUN, VERIFY, RESET, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;
    private boolean finished;

    private RecipeQuery.Result query;
    private Item targetItem;
    private String targetId;
    private CraftJob job;
    private int productBefore;

    public CraftMachineCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftMachineCheck";     // `*CheckTask` 约定：自检不招 LLM
    }

    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(START);
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
            failures.add("craft_machine_timeout");
            return finish();
        }
        phaseTicks++;
        return switch (phase) {
            case PREPARE -> prepare();
            case PICK -> pick();
            case RUN -> run();
            case VERIFY -> verify();
            case RESET -> reset();
            case DONE -> finish();
        };
    }

    /** 传送 + 起点前提（夹具纪律：起点确定，且离机器足够远 ⇒ 生产必须自己走过去）。 */
    private Status prepare() {
        if (phaseTicks == 1) {
            // **传送与落地判据分到两个 tick** —— 同 `MachineCycleCheckTask` 的注释（陈旧 `onGround` 假红）；
            // 细则见 `FixturePremise.SETTLE_TICKS` ✓
            bot.teleportTo(bot.serverLevel(), START.getX() + 0.5D, START.getY(), START.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(Vec3.ZERO);
            bot.controller().stopMovement();
            record("on_ground_immediately_after_teleport", FixturePremise.onGround(bot).detail());
            BotLog.info("[CraftMachine] 已传送到远角起点 {}（{}）—— 等物理结算后再判落地 ✓",
                    START.toShortString(), bot.blockPosition().toShortString());
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
        // 机器在不在：不在就 `machine_absent`（电池按 SKIP 处理，与其它 machine_* 步一致）
        BlockPos machine = findMachine(MachineMap.blockFor("mekanism:enriching"));
        if (machine == null) {
            return failAndFinish("machine_absent:radius_" + SCAN_RADIUS);
        }
        record("machine", machine.toShortString());
        return advance(Phase.PICK);
    }

    /**
     * **用生产自己的查询层**挑一个"只有机器路线"的目标物（逐个现场复核，不猜）。
     * 顺带断言：该路线指向的机器行**有执行准入**（`EXECUTABLE`）—— 没有准入的机器，生产本来就该如实拒绝。
     */
    private Status pick() {
        List<String> tried = new ArrayList<>();
        for (String candidate : CANDIDATE_TARGETS) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(candidate));
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            RecipeQuery.Result result = RecipeQuery.query(bot.getServer(), bot, item, 1);
            tried.add(candidate + "=" + result.verdict());
            if (result.verdict() != RecipeQuery.Verdict.MACHINE_ROUTE || result.route() == null) {
                continue;
            }
            MachineMap.Row row = MachineMap.forBlock(result.route().station());
            if (row == null || row.capability() != MachineMap.Capability.EXECUTABLE) {
                tried.add(candidate + "=no_executable_adapter:" + result.route().station());
                continue;
            }
            targetItem = item;
            targetId = candidate;
            query = result;
            record("candidates_tried", String.join(" ", tried));
            // **后备是否被用到**（D-218 / 台账⑬ 的观测点）：查询层现在按**执行准入优先**排机器路线 ⇒
            // 首选候选应当**直接可用**；若这里又变 `true`，说明排序没生效、或同一产出又多了台未准入的机器。
            record("fallback_used", String.valueOf(tried.size() > 1));
            record("target", candidate);
            record("route_station", result.route().station());
            record("route_type", result.route().type());
            record("route_recipe", result.route().recipeId());
            return advance(Phase.RUN);
        }
        record("candidates_tried", String.join(" ", tried));
        return failAndFinish("no_machine_only_target");
    }

    /** 备料（**测试前提**：生产路径缺料就如实失败，所以料必须由测试侧给）+ 起**真**生产 Job。 */
    private Status run() {
        if (phaseTicks == 1) {
            RecipeQuery.Material material = query.route().materials().isEmpty()
                    ? null : query.route().materials().get(0);
            if (material == null || material.candidates().size() != 1) {
                return failAndFinish("route_input_not_single_item");
            }
            Item input = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(material.candidates().get(0)));
            if (input == net.minecraft.world.item.Items.AIR) {
                return failAndFinish("route_input_unknown:" + material.candidates().get(0));
            }
            int need = Math.max(1, material.perCraft()) * Math.max(1, query.route().crafts());
            record("input", material.candidates().get(0) + " x" + need);
            record("provision", provision(input, need));
            productBefore = RecipeQuery.countInInventory(bot, targetItem);
            record("product_before", String.valueOf(productBefore));
            // **生产路径本体**：与玩家/LLM 触发时是同一个 Job（同一份机器闭环实现）
            job = new CraftJob(bot, targetId, 1, CRAFT_TICKS);
            BotLog.info("[CraftMachine] 起 CraftJob target={} x1（生产路径；机器闭环由它自己走）", targetId);
        }
        CraftJob.Status state = job.tick();
        if (state == CraftJob.Status.RUNNING) {
            return Status.RUNNING;
        }
        record("job_terminal", state.name());
        record("job_reason", job.terminalReason().isEmpty() ? "-" : job.terminalReason());
        record("job_failure", job.failureReason().isEmpty() ? "-" : job.failureReason());
        Map<String, String> machine = job.machineFacts();
        record("machine_facts", String.valueOf(machine.size()));
        for (String key : List.of("walk_state", "walk_ticks", "machine_reach", "energy_source",
                "energy_at_open", "feed_verified", "wait_ticks", "product_landed", "machine_emptied",
                "input_consumed", "container_writes")) {
            if (machine.containsKey(key)) {
                record("m_" + key, machine.get(key));
            }
        }
        if (state != CraftJob.Status.DONE) {
            return failAndFinish("craft_job_failed:" + job.failureReason());
        }
        // **生产路径必须真的走了机器路线**（否则这个自检就是假绿：可能走了合成/熔炼路线）
        if (machine.isEmpty()) {
            return failAndFinish("job_did_not_use_machine_route");
        }
        if (!"true".equals(machine.get("product_landed")) || !"true".equals(machine.get("machine_emptied"))) {
            return failAndFinish("machine_facts_not_clean:" + machine.get("product_landed")
                    + "/" + machine.get("machine_emptied"));
        }
        // 起点是平台远角（≈8.49 格）⇒ 生产必须**自己走过去**；出现 `walk_skipped` 说明座位没生效，要查
        if (machine.containsKey("walk_skipped")) {
            return failAndFinish("unexpected_walk_skipped:" + machine.get("walk_skipped"));
        }
        if (!"DONE".equals(machine.get("walk_state"))) {
            return failAndFinish("machine_walk_not_done:" + machine.get("walk_state"));
        }
        return advance(Phase.VERIFY);
    }

    /** 世界事实：目标物在背包里真的多了。 */
    private Status verify() {
        int after = RecipeQuery.countInInventory(bot, targetItem);
        record("product_after", String.valueOf(after));
        check("product_landed_inventory", after - productBefore >= 1,
                "before=" + productBefore + " after=" + after + " 期望+1");
        return advance(Phase.RESET);
    }

    /** 结束复位：关菜单/停输入/回起点（成功与失败路径都走）。 */
    private Status reset() {
        if (bot.containerMenu != null
                && !(bot.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu)) {
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

    // ==================== 小工具 ====================

    private BlockPos findMachine(String blockId) {
        if (blockId == null) {
            return null;
        }
        BlockPos center = bot.blockPosition();
        BlockPos found = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            String id = BuiltInRegistries.BLOCK.getKey(bot.serverLevel().getBlockState(pos).getBlock()).toString();
            if (!id.equals(blockId)) {
                continue;
            }
            double distance = Math.sqrt(pos.distSqr(center));
            if (distance < best) {
                best = distance;
                found = pos.immutable();
            }
        }
        return found;
    }

    /** 测试备料：不够就补进空槽（**报出真正给了多少**）。 */
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

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status failAndFinish(String code) {
        failures.add(code);
        BotLog.warn("[CraftMachine] 失败 {}", code);
        return finish();
    }

    private Status finish() {
        phase = Phase.DONE;
        finished = true;
        StringBuilder summary = new StringBuilder();
        for (var entry : facts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftMachine] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftMachine] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }

    private void record(String key, String value) {
        facts.put(key, value);
    }

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[CraftMachine] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }
}
