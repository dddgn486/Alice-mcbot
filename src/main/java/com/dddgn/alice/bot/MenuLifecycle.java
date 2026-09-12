package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **容器菜单的生命周期保证**（L2，2026-09-13 用户实测发现）。
 *
 * <p>起因：B 路线（真实菜单协议）验证成功后，用户立刻发现"**任务终止时箱子还开着**"——
 * 探针只在**正常路径**关菜单，任何**提前终止**（用户停任务 / 维生打断 / 任务被替换 / 失败）
 * 都会绕过它 ⇒ `ContainerOpenersCounter` 没减回去，**盖子一直开着**（还会让箱子长期处于"被占用"）。
 *
 * <p>本类的两条保证：
 * <ol>
 *   <li>{@link #closeOpen} —— **任务收尾收敛点**（`BotSession.clearTask`）与 bot 移除处调用，
 *       把"还开着的菜单"强制关掉，并**打警告**（哪条路径漏了，日志里一眼能看到）；</li>
 *   <li>{@link #tick} —— **看门狗**：菜单开着但**没有任何任务**在跑（>60 tick）⇒ 先告警（含菜单类型）
 *       再收尾。它是"发现漏网"的诚实兜底，**不是**用来掩盖漏网的补丁 —— 有告警就说明有人忘了收尾。</li>
 * </ol>
 */
public final class MenuLifecycle {

    /** 无任务却开着菜单多少 tick 就判定为漏网（≈3 秒，够任何合法交互交接）。 */
    public static final int IDLE_MENU_LIMIT_TICKS = 60;

    private static final Map<UUID, Integer> IDLE_TICKS = new HashMap<>();
    private static int closedByClear;
    private static int closedByWatchdog;
    private static String lastLeak = "-";

    private MenuLifecycle() {
    }

    /** 任务收尾/bot 移除时调用：还开着菜单就强制关闭，并告警（漏网可见）。 */
    public static boolean closeOpen(BotPlayer bot, String reason) {
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == null || menu == bot.inventoryMenu) {
            return false;
        }
        String type = menu.getClass().getSimpleName();
        lastLeak = type + " reason=" + reason;
        closedByClear++;
        BotLog.warn("[Menu] 收尾时仍开着容器菜单 {}（reason={}）⇒ 强制关闭（否则箱子盖子会一直开着）",
                type, reason);
        forceClose(bot);
        return true;
    }

    /** 每 tick（`BotManager` 调度循环里调）：菜单开着却没有任务在跑 ⇒ 告警并收尾。 */
    public static void tick(BotPlayer bot) {
        boolean menuOpen = bot.containerMenu != null && bot.containerMenu != bot.inventoryMenu;
        if (!menuOpen) {
            IDLE_TICKS.remove(bot.getUUID());
            return;
        }
        BotManager.BotSession session = BotManager.sessionOf(bot);
        boolean hasTask = session != null && session.currentTaskSummary() != null;
        if (hasTask) {
            IDLE_TICKS.remove(bot.getUUID());   // 任务拥有这个菜单 ⇒ 合法
            return;
        }
        int idle = IDLE_TICKS.merge(bot.getUUID(), 1, Integer::sum);
        if (idle == IDLE_MENU_LIMIT_TICKS) {
            lastLeak = bot.containerMenu.getClass().getSimpleName() + " watchdog";
            closedByWatchdog++;
            BotLog.warn("[Menu] 看门狗：菜单已开着 {} tick 却没有任务在跑（{}）⇒ 关闭并记一次漏网",
                    idle, bot.containerMenu.getClass().getSimpleName());
            forceClose(bot);
            IDLE_TICKS.remove(bot.getUUID());
        }
    }

    private static void forceClose(BotPlayer bot) {
        try {
            bot.closeContainer();
        } catch (RuntimeException | Error thrown) {
            BotLog.warn("[Menu] 关闭容器菜单失败（{}）⇒ 交由下次收尾重试", thrown.toString());
        }
    }

    public static void forget(UUID botId) {
        IDLE_TICKS.remove(botId);
    }

    /** 汇报/自检用：收尾关了几次、看门狗抓到几次漏网。 */
    public static String describe() {
        return "closedByClear=" + closedByClear + " closedByWatchdog=" + closedByWatchdog
                + " lastLeak=" + lastLeak;
    }
}
