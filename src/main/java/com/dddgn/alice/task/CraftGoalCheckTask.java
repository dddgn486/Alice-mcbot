package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.CandidateMenu;
import com.dddgn.alice.decision.GoalAction;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.craft.CraftJob;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.RecipeQuery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **A5 决策层接线自检**（阶段 3-A / A5，D-199）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>验的是**"选项由确定性层生成、LLM 只选择"**这条铁律在合成上的落地：
 * <ol>
 *   <li>**可做清单**：候选菜单里必须真的出现"现在就能做"的产物（本夹具给 4 块橡木木板 ⇒ 工作台），
 *       且带 `can_use=` 事实；</li>
 *   <li>**严格解析**：不在清单里的物品 ⇒ `Refused`（**不猜**）；在清单里且当前站点能做 ⇒ `Craft`；</li>
 *   <li>**真执行**：`CraftJob` 走生产路径（JobRequest → JobLauncher → 原语），判据**只看世界事实**
 *       （木板 −4、工作台 +1）；</li>
 *   <li>**清场**：夹具发的东西收回去（`resetInventory`），不给下一步留污染。</li>
 * </ol>
 *
 * <p>**为什么用随身 2×2**：本项**不需要场景**（零地形依赖）⇒ 电池启动即可跑，也不受"上一场景残留"影响；
 * 工作台 = 4 木板 2×2 的经典配方，事实稳定。
 */
public class CraftGoalCheckTask implements Task {

    private static final String TARGET = "minecraft:crafting_table";
    private static final String OUT_OF_MENU = "minecraft:diamond_block";
    private static final int MAX_TICKS = 600;

    private enum Phase { PREPARE, CHECK_MENU, RUN_JOB, ASSERT, CLEANUP, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int planksBefore;
    private boolean jobDone;
    private String jobTerminal = "-";
    private String jobFailure = "-";
    private CraftJob job;

    public CraftGoalCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftGoalCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public String failureReason() {
        return String.join(",", failures);
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
        return switch (phase) {
            case PREPARE -> prepare();
            case CHECK_MENU -> checkMenu();
            case RUN_JOB -> runJob();
            case ASSERT -> assertResult();
            case CLEANUP -> cleanup();
            case DONE -> finish();
        };
    }

    private Status prepare() {
        FixtureToolKit.resetInventory(bot);
        give(Items.OAK_PLANKS, 4);
        planksBefore = RecipeQuery.countInInventory(bot, Items.OAK_PLANKS);
        record("fixture_gave_materials", "planks=" + planksBefore);
        check("materials_given", planksBefore == 4, "planks=" + planksBefore);
        return advance(Phase.CHECK_MENU);
    }

    /** ①②：清单里有事实 + 越界被拒 + 在清单里被接受（**都在解析层**，不动世界）。 */
    private Status checkMenu() {
        CandidateMenu menu = CandidateMenu.build(bot);
        Boolean canUse = menu.craftable().get(TARGET);
        record("menu_craftable_total", String.valueOf(menu.craftable().size()));
        record("menu_truncated", String.valueOf(menu.craftableTruncated()));
        check("menu_has_target", Boolean.TRUE.equals(canUse), "craftable=" + canUse + " 清单="
                + menu.craftable().size() + (menu.craftableTruncated() ? "(truncated)" : ""));

        GoalAction refused = GoalAction.parse(
                "{\"action\":\"craft\",\"item\":\"" + OUT_OF_MENU + "\",\"count\":1}", bot, menu);
        boolean refusedOk = refused instanceof GoalAction.Refused refusedAction
                && refusedAction.reason().startsWith("not_in_menu");
        record("parse_out_of_menu", describe(refused));
        check("parse_out_of_menu_refused", refusedOk, describe(refused));

        GoalAction accepted = GoalAction.parse(
                "{\"action\":\"craft\",\"item\":\"" + TARGET + "\",\"count\":1}", bot, menu);
        record("parse_in_menu", describe(accepted));
        check("parse_in_menu_accepted", accepted instanceof GoalAction.Craft, describe(accepted));

        // 站点做不了的那一档：清单里有 `can_use=false` 的项就必须被"站点拒绝"（没有就如实记，不判红）
        String cannot = null;
        for (Map.Entry<String, Boolean> entry : menu.craftable().entrySet()) {
            if (Boolean.FALSE.equals(entry.getValue())) {
                cannot = entry.getKey();
                break;
            }
        }
        record("station_cannot_probe", cannot == null ? "none_in_menu" : cannot);
        if (cannot != null) {
            GoalAction stationRefused = GoalAction.parse(
                    "{\"action\":\"craft\",\"item\":\"" + cannot + "\",\"count\":1}", bot, menu);
            check("parse_station_cannot_refused",
                    stationRefused instanceof GoalAction.Refused r2 && r2.reason().startsWith("station_cannot"),
                    describe(stationRefused));
        }
        return advance(Phase.RUN_JOB);
    }

    /** ③：走**生产**路径（同一个 `CraftJob` 类，决策层起 Job 时用的就是它）。 */
    private Status runJob() {
        if (job == null) {
            job = new CraftJob(bot, TARGET, 1, MAX_TICKS);
        }
        Status status = job.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        jobDone = status == Status.DONE;
        jobTerminal = status.name();
        jobFailure = job.failureReason().isEmpty() ? "-" : job.failureReason();
        record("job_terminal", jobTerminal);
        record("job_terminal_reason", job.terminalReason().isEmpty() ? "-" : job.terminalReason());
        record("job_failure", jobFailure);
        return advance(Phase.ASSERT);
    }

    /** 判据**只看世界事实**：木板少了、工作台多了。 */
    private Status assertResult() {
        int planksAfter = RecipeQuery.countInInventory(bot, Items.OAK_PLANKS);
        int tables = RecipeQuery.countInInventory(bot, Items.CRAFTING_TABLE);
        record("planks_delta", String.valueOf(planksAfter - planksBefore));
        record("product_count", String.valueOf(tables));
        check("job_done", jobDone, "terminal=" + jobTerminal + " failure=" + jobFailure);
        check("planks_consumed", planksBefore - planksAfter >= 4,
                "planks " + planksBefore + "→" + planksAfter);
        check("product_produced", tables >= 1, "crafting_table=" + tables);
        return advance(Phase.CLEANUP);
    }

    private Status cleanup() {
        FixtureToolKit.resetInventory(bot);
        record("inventory_cleaned", "true");
        return finish();
    }

    private Status advance(Phase next) {
        phase = next;
        return Status.RUNNING;
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
        BotLog.warn("[CraftGoalCheck] 背包没有空槽，无法发料 {}", item);
    }

    private void record(String key, String value) {
        facts.put(key, value);
    }

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "true" : "false");
        BotLog.info("[CraftGoalCheck] {}={} {}", name, ok ? "true" : "false", detail);
        if (!ok) {
            failures.add(name);
        }
    }

    private static String describe(GoalAction action) {
        if (action instanceof GoalAction.Refused refused) {
            return "Refused(" + refused.reason() + ")";
        }
        if (action instanceof GoalAction.Craft craft) {
            return "Craft(" + craft.item() + " x" + craft.count() + ")";
        }
        return action.getClass().getSimpleName();
    }

    private Status finish() {
        phase = Phase.DONE;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftGoalCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftGoalCheck] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }
}
