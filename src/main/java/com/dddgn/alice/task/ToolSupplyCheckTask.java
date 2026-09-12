package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.ToolSupply;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **工具供给自检**（基-9）：三个用例 + 一个负例，都在真实背包上做，约 2 秒。
 *
 * <pre>
 * A 手上是把快坏的镐、主背包有把新的 ⇒ 维护后**新的到手上**（`promoted_from_main`）
 * B 只有那把快坏的、没有替代     ⇒ 如实报 `worn_no_spare`（**不假装修好了**）
 * C 身上根本没有镐               ⇒ 如实报 `no_tool`，且**不能凭空变出工具**（负例）
 * </pre>
 *
 * <p>为什么必须有 C：工具管理最容易长出"偷偷给工具"的行为（夹具发料就是这么干的）。
 * 生产路径**不许**擅自变出资源 —— 那属于合成（S6）与请示通道。
 */
public class ToolSupplyCheckTask implements Task {

    private enum Phase { SETUP_A, RUN_A, SETUP_B, RUN_B, SETUP_C, RUN_C, RESTORE, DONE }

    private static final int SUBTASK_LIMIT = 40;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Phase phase = Phase.SETUP_A;
    private int ticks;
    private int phaseTicks;
    private Task sub;

    public ToolSupplyCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "ToolSupplyCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return phase == Phase.DONE ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 400) {
            return finish("timeout");
        }
        phaseTicks++;
        return switch (phase) {
            case SETUP_A -> setupA();
            case RUN_A -> runSub("tool_swap");
            case SETUP_B -> setupB();
            case RUN_B -> runSub("worn_no_spare");
            case SETUP_C -> setupC();
            case RUN_C -> runSub("no_tool");
            case RESTORE -> restore();
            case DONE -> failures.isEmpty() ? Status.DONE : Status.FAILED;
        };
    }

    // ==================== A：主背包有更好的 ⇒ 换上 ====================

    private Status setupA() {
        FixtureToolKit.resetInventory(bot);
        var inventory = bot.getInventory();
        ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE);
        worn.setDamageValue((int) (worn.getMaxDamage() * 0.9F));
        inventory.setItem(0, worn);
        inventory.setItem(9, new ItemStack(Items.IRON_PICKAXE));
        BotManager.syncMainHand(bot);
        ToolSupply.Snapshot snapshot = ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE);
        ToolSupply.Slot main = ToolSupply.bestInMain(bot, ToolSupply.Kind.PICKAXE);
        check("swap_premise", snapshot.present() && snapshot.inHotbar() && snapshot.spareInMain() >= 1
                        && main.remaining() > snapshot.remaining(),
                "before=" + snapshot.describe() + " mainRemaining=" + main.remaining());
        return advance(Phase.RUN_A);
    }

    // ==================== B：没有替代 ⇒ 如实报 ====================

    private Status setupB() {
        FixtureToolKit.resetInventory(bot);
        var inventory = bot.getInventory();
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue((int) (worn.getMaxDamage() * 0.9F));
        inventory.setItem(0, worn);
        BotManager.syncMainHand(bot);
        ToolSupply.Snapshot snapshot = ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE);
        check("worn_premise", snapshot.present() && snapshot.spareInMain() == 0,
                "before=" + snapshot.describe());
        return advance(Phase.RUN_B);
    }

    // ==================== C：根本没有 ⇒ 不许变出来 ====================

    private Status setupC() {
        FixtureToolKit.resetInventory(bot);
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        BotManager.syncMainHand(bot);
        check("no_tool_premise", !ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE).present(),
                "inspect=" + ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE).describe());
        return advance(Phase.RUN_C);
    }

    // ==================== 断言 ====================

    private Status runSub(String caseName) {
        if (sub == null) {
            sub = new ToolMaintenanceTask(bot, null,
                    caseName.equals("no_tool") ? ToolSupply.Kind.PICKAXE : ToolSupply.Kind.PICKAXE);
        }
        if (sub.tick() == Status.RUNNING) {
            if (phaseTicks > SUBTASK_LIMIT) {
                check(caseName, false, "子任务超时未结束");
                sub = null;
                return advanceNext();
            }
            return Status.RUNNING;
        }
        // **先取终态再置 null**（2026-09-12 实测的坑：先置 null 会让 lambda 读到占位符 `-`）
        String reason = sub.terminalReason() == null ? "-" : sub.terminalReason();
        sub = null;
        switch (caseName) {
            case "tool_swap" -> {
                ToolSupply.Snapshot after = ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE);
                check(caseName, "promoted_from_main".equals(reason) && after.inHotbar() && after.ratio() > 0.9D,
                        "terminal=" + reason + " after=" + after.describe());
            }
            case "worn_no_spare" -> {
                ToolSupply.Snapshot after = ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE);
                check(caseName, "worn_no_spare".equals(reason) && after.present()
                                && after.ratio() <= 0.20D,
                        "terminal=" + reason + " after=" + after.describe());
            }
            case "no_tool" -> {
                boolean stillNone = !ToolSupply.inspect(bot, ToolSupply.Kind.PICKAXE).present();
                check(caseName, "no_tool".equals(reason) && stillNone,
                        "terminal=" + reason + " 仍无镐=" + stillNone + "（负例：不得凭空变出工具）");
            }
            default -> check(caseName, false, "unknown case");
        }
        return advanceNext();
    }

    private Status restore() {
        // 收尾复原（夹具卫生）：留着可用的镐/斧，清掉测试残留
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        FixtureToolKit.ensureAxe(bot);
        notes.add("after_restore=" + ToolSupply.describe(bot));
        phase = Phase.DONE;
        String summary = "tool_swap=" + verdict("tool_swap")
                + " worn_no_spare=" + verdict("worn_no_spare")
                + " no_tool_no_conjure=" + verdict("no_tool")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[ToolSupply] SUMMARY {} {}", summary, String.join(" ", notes));
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[ToolSupply] " + summary));
        }
        return Status.RUNNING;
    }

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status advanceNext() {
        return switch (phase) {
            case RUN_A -> advance(Phase.SETUP_B);
            case RUN_B -> advance(Phase.SETUP_C);
            case RUN_C -> advance(Phase.RESTORE);
            default -> advance(Phase.DONE);
        };
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[ToolSupply] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        phase = Phase.DONE;
        BotLog.warn("[ToolSupply] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
