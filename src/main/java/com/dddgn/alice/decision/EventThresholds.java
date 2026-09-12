package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **事件阈值**（S4 事件层 / D-150）：把"值得开口的病症"变成事件，**只报可行动的**，
 * 并且**同一次病症只报一次**（阈值跨越 = 事件；`hysteresis` 复位后才可能再报）。
 *
 * <p>为什么必须有它：在此之前决策层的唯一触发是"任务终态"——"工具快坏了""卡住不动了""无活可干"
 * 这三类**可行动病症**完全没人上报，bot 会安静地把镐子磨没、或者安静地卡住。
 *
 * <p>纪律（对齐项目"噪声=隐患"）：
 * <ul>
 *   <li>只报**三档之内**的可行动病症：{@code TOOL_LOW}（工具耐久见底）、{@code STUCK}（有任务但长时间不动）、
 *       {@code DANGER}（维生中断，沿用 S-1 已有事件）；</li>
 *   <li>树叶清障、单次失败重试这类**噪声**不上报；</li>
 *   <li>每种病症**跨越阈值只报一次**，复位阈值更高（滞回），避免抖动刷屏。</li>
 * </ul>
 */
public final class EventThresholds {

    /** 工具耐久低于该比例 ⇒ 报 TOOL_LOW；回到该比例以上才复位（滞回）。 */
    public static final float TOOL_LOW_RATIO = 0.20F;
    public static final float TOOL_REARM_RATIO = 0.35F;
    /**
     * **有移动意图**却脚位连续不动这么多 tick ⇒ 报 STUCK。
     *
     * <p>为什么判据里必须有"移动意图"（`controller.hasActiveMovement()`）：只有"有任务且不动"会把
     * **等待态**（等 LLM 回复、等请示答复、等冷却）误报成病症 —— 那些时刻决策层什么都做不了，
     * 报了就是噪声。真卡住的定义是"**正在走**却一格没挪"。
     */
    public static final int STUCK_WINDOW_TICKS = 200;

    private static final class State {
        boolean toolReported;
        BlockPos lastFoot;
        long lastMoveTick = -1L;
        /** 上一次上报 STUCK 的（脚位, 任务）—— 同一 episode 只报一次，避免"意图闪烁"反复刷屏。 */
        BlockPos lastReportedFoot;
        String lastReportedTask = "";
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private EventThresholds() {
    }

    /** 每 tick（`BotManager` 调度循环里调）：只读观察 + 越阈值时**发一次**事件。 */
    public static void tick(BotPlayer bot) {
        State state = STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
        long now = bot.getServer().getTickCount();

        checkToolDurability(bot, state);
        checkStuck(bot, state, now);
    }

    /** 工具耐久：取背包里**剩余耐久最低**的斧/镐（"最可能先坏的那把"）。 */
    private static void checkToolDurability(BotPlayer bot, State state) {
        ItemStack worst = ItemStack.EMPTY;
        float worstRatio = 1.0F;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            boolean tool = stack.getItem() instanceof AxeItem
                    || stack.getItem() instanceof PickaxeItem;
            if (!tool) {
                continue;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                continue;
            }
            float ratio = (float) (max - stack.getDamageValue()) / max;
            if (ratio < worstRatio) {
                worstRatio = ratio;
                worst = stack;
            }
        }
        if (worst.isEmpty()) {
            state.toolReported = false;
            return;
        }
        if (!state.toolReported && worstRatio <= TOOL_LOW_RATIO) {
            state.toolReported = true;
            // 基-9：事件文案要**可行动** —— 说清"这是哪类工具、在不在快捷栏、有没有更好的可换"，
            // 于是决策层可以选 `maintain_tool`（能自己解决）或如实上报（解决不了）。
            com.dddgn.alice.bot.ToolSupply.Kind kind = worst.getItem() instanceof AxeItem
                    ? com.dddgn.alice.bot.ToolSupply.Kind.AXE
                    : com.dddgn.alice.bot.ToolSupply.Kind.PICKAXE;
            com.dddgn.alice.bot.ToolSupply.Snapshot toolState =
                    com.dddgn.alice.bot.ToolSupply.inspect(bot, kind);
            String fix = !toolState.inHotbar() ? "（在主背包，可用 maintain_tool 搬进快捷栏）"
                    : toolState.bestMainRemaining() > toolState.remaining()
                    ? "（主背包有更好的，可用 maintain_tool 换上）"
                    : "（身上没有更好的 ⇒ 需外部补充）";
            String summary = "工具耐久见底：" + worst.getHoverName().getString() + " 剩余 "
                    + (worst.getMaxDamage() - worst.getDamageValue()) + "/" + worst.getMaxDamage()
                    + String.format(java.util.Locale.ROOT, "（%.0f%%）", worstRatio * 100) + fix;
            emit(bot, "TOOL_LOW", "warn", summary, "ratio=" + String.format(java.util.Locale.ROOT,
                    "%.2f", worstRatio));
        } else if (state.toolReported && worstRatio >= TOOL_REARM_RATIO) {
            state.toolReported = false;   // 换了新工具/修好了 ⇒ 复位，下次见底还能报
        }
    }

    /**
     * 卡住：**有任务在跑 + 有移动意图**，脚位却长时间不变。
     *
     * <p>无任务（idle）或没有移动意图（等待态）都**不计时**（时钟拨到当前 tick）—— 那不是病症。
     * 同一（脚位, 任务）episode 只报一次；脚位一变（脱困）或换了任务才允许再报。
     */
    private static void checkStuck(BotPlayer bot, State state, long now) {
        String task = BotManager.currentTaskSummary(bot);
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        boolean movingIntent = bot.controller() != null && bot.controller().hasActiveMovement();
        if (task == null || !movingIntent) {
            state.lastFoot = foot;
            state.lastMoveTick = now;
            return;
        }
        if (!foot.equals(state.lastFoot)) {
            state.lastFoot = foot;
            state.lastMoveTick = now;
            return;
        }
        if (state.lastMoveTick < 0) {
            state.lastMoveTick = now;
            return;
        }
        long still = now - state.lastMoveTick;
        if (still < STUCK_WINDOW_TICKS) {
            return;
        }
        if (foot.equals(state.lastReportedFoot) && task.equals(state.lastReportedTask)) {
            return;
        }
        state.lastReportedFoot = foot;
        state.lastReportedTask = task;
        emit(bot, "STUCK", "warn", "有移动意图但 " + still + " tick 没挪过格：" + task,
                "foot=" + foot.toShortString());
    }

    private static void emit(BotPlayer bot, String type, String severity, String summary, String data) {
        // 统一出口（DecisionEvents）：环 + 日志 + 通知决策层（自检暂停时只记录不通知）
        DecisionEvents.emit(bot, type, severity, summary, data);
    }

    /** 测试/汇报用：当前是否处于"工具见底"档。 */
    public static boolean toolLowReported(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        return state != null && state.toolReported;
    }

    /**
     * **测试/复位用**：把"卡住"时钟拨到当前 tick（不改阈值）。
     *
     * <p>为什么需要它：`STUCK` 的判据是"脚位连续不动 200 tick"，而夹具无法保证进入该用例时
     * bot 恰好刚动过（前面的电池步骤可能已经让它站了几百 tick）⇒ 事件会在用例准备好之前就报掉。
     * 有了它，用例可以先把时钟拨零、再开始计时，判据仍然是真的。
     */
    public static void resetStuckTracking(BotPlayer bot) {
        State state = STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
        state.lastFoot = com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
        state.lastMoveTick = bot.getServer().getTickCount();
        state.lastReportedFoot = null;
        state.lastReportedTask = "";
    }

    public static void forget(UUID botId) {
        STATES.remove(botId);
    }
}
