package com.dddgn.alice.transfer;

import com.dddgn.alice.log.BotLog;

/**
 * **容器读写的路线开关**（L2 生产化，2026-09-13 用户裁定："切到菜单路线 + 保留开关"）。
 *
 * <p>两条路线（`docs/INTERACTION_LAYERS_COMPARISON.md`）：
 * <ul>
 *   <li>{@link Route#MENU}（**默认**）：走真实菜单协议（`MenuSession`）——模组自己的接口，
 *       不绕过权限/统计/GUI 钩子，旁观者能看懂；</li>
 *   <li>{@link Route#CAPABILITY}：capability 直写（旧路）——**显式优化**，用于对照与调试，
 *       也可能用于"适配表明确声明语义等价"的纯物流箱（后续）。</li>
 * </ul>
 *
 * <p>**这是一个调试/对照开关**：进程内存态，重启回默认（menu）。切换会打一行日志（谁改的、从什么到什么），
 * 因为它会实质改变行为。
 */
public final class TransferRoutes {

    public enum Route {
        MENU,
        CAPABILITY
    }

    private static volatile Route route = Route.MENU;

    private TransferRoutes() {
    }

    public static Route route() {
        return route;
    }

    public static void set(Route next, String who) {
        Route previous = route;
        route = next;
        BotLog.warn("[Transfer] 路线切换 {} → {}（by {}）："
                        + "MENU=真实菜单协议（默认）/ CAPABILITY=capability 直写",
                previous, next, who);
    }

    /** 解析命令参数（未知值返回 null，由调用方如实拒绝）。 */
    public static Route parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "menu" -> Route.MENU;
            case "capability", "cap" -> Route.CAPABILITY;
            default -> null;
        };
    }

    public static String describe() {
        return route + "（MENU=真实菜单协议 / CAPABILITY=capability 直写；内存态，重启回默认）";
    }
}
