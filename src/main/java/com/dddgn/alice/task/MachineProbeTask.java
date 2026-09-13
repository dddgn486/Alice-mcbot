package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.RecipeDump;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **机器配方只读探针**（阶段 3-B / S1，D-204）：零参数、**零写入**，一次跑完输出 `SUMMARY`。
 *
 * <p>为什么需要它：S0（{@link RecipeDump} 的运行时导出）只给了"被跳过的类型 × 条数"，
 * **不含这些类型的输入/输出样例**（它们本来就不在原版白名单里）⇒ 认机器（S2）之前必须先**取证**：
 * 这些类型长什么样、输入输出是什么形态。
 *
 * <p>口径（与协议 §1 的"只读先于执行"一致）：**只读** `RecipeManager`，按**命名空间**过滤、
 * 按**类型**聚合，每类最多打 {@value #SAMPLES_PER_TYPE} 条样例；**不改世界、不发包、不派任务**。
 * S1 的产出 = 用这批事实去写"类型 → 输入/输出 + 机器类型"的读法（只读），并升级查询层的拒绝码。
 */
public class MachineProbeTask implements Task {

    /** 本轮取证对象（S0 的第一大户；换模组只改这里 + 入口名字）。 */
    private static final String NAMESPACE = "mekanism";
    /** 每个类型最多打几条样例（有界：26 类 × 2 条，够定 S2 的范围，又不至于刷屏）。 */
    private static final int SAMPLES_PER_TYPE = 2;
    private static final int MAX_TICKS = 40;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private int ticks;
    private boolean finished;
    private boolean failed;

    public MachineProbeTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MachineProbe";   // `*ProbeTask` 命名约定：探针不招 LLM（D-192 附注二）
    }

    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failed ? "probe_failed" : "";
    }

    @Override
    public Status tick() {
        if (finished) {
            return failed ? Status.FAILED : Status.DONE;
        }
        if (++ticks > MAX_TICKS) {
            failed = true;
            finished = true;
            return Status.FAILED;
        }
        run();
        finished = true;
        return failed ? Status.FAILED : Status.DONE;
    }

    private void run() {
        var server = bot.getServer();
        var access = server.registryAccess();
        Map<String, List<Recipe<?>>> byType = new LinkedHashMap<>();
        int readable = 0;
        int skipped = 0;
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (RecipeDump.stationFor(typeId) != null) {
                readable++;
                continue;
            }
            skipped++;
            if (typeId.startsWith(NAMESPACE)) {
                byType.computeIfAbsent(typeId, key -> new ArrayList<>()).add(recipe);
            }
        }
        int types = byType.size();
        int typeTotal = byType.values().stream().mapToInt(List::size).sum();
        BotLog.info("[MachineProbe] 命名空间={} 类型={} 条数={}（全表：可读={} 跳过={}）",
                NAMESPACE, types, typeTotal, readable, skipped);
        for (Map.Entry<String, List<Recipe<?>>> entry : byType.entrySet()) {
            BotLog.info("[MachineProbe]   type={} count={}", entry.getKey(), entry.getValue().size());
            int shown = 0;
            for (Recipe<?> recipe : entry.getValue()) {
                if (shown++ >= SAMPLES_PER_TYPE) {
                    break;
                }
                ItemStack out = recipe.getResultItem(access);
                List<String> ins = new ArrayList<>();
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    ItemStack[] items = ingredient.getItems();
                    ins.add(items.length == 0 ? "?" : BuiltInRegistries.ITEM.getKey(items[0].getItem()).toString());
                    if (ins.size() >= 3) {
                        break;
                    }
                }
                BotLog.info("[MachineProbe]     sample id={} out={} x{} in={}",
                        recipe.getId(), BuiltInRegistries.ITEM.getKey(out.getItem()), out.getCount(), ins);
            }
        }
        int pending = WorldModLedger.pendingForOwner(server, bot.getUUID()).size();
        if (pending != 0) {
            failed = true;
        }
        StringBuilder summary = new StringBuilder();
        summary.append("namespace=").append(NAMESPACE)
                .append(" types=").append(types)
                .append(" type_recipes=").append(typeTotal)
                .append(" readable_total=").append(readable)
                .append(" skipped_total=").append(skipped)
                .append(" samples_per_type=").append(SAMPLES_PER_TYPE)
                .append(" no_writes=").append(pending == 0)
                .append(" verdict=").append(failed ? "FAIL" : "PASS");
        BotLog.info("[MachineProbe] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MachineProbe] " + summary));
        }
    }
}
