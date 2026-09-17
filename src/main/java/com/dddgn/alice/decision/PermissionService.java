package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **F3 地基（D-267，2026-09-17 用户裁定「先做地基」）**：请示的**答复入口收敛为一个服务层**，
 * 并给每次答复打上 **transport（谁在答复）**。
 *
 * <p>为什么：`PermissionGate.answer(...)` 原先是个 `public static`，今天只有命令 / 客户端弹窗 / 夹具三类
 * 调用者各调各的 ⇒ 一旦要接"非游戏内主体"（桌面 AI、外部驱动者）就**没有地方落**：
 * 要么再写一个直调 `PermissionGate` 的分支（状态机被绕过），要么改签名（牵动所有调用点）。
 * `survey/16 §11` 的结论是：**没有统一答复入口 ⇒ 桌面 AI 看不到请示就永远停在 `ASK=拒绝`**（那是硬闸）。
 *
 * <p>**结构约束（本类的存在理由）**：
 * <ul>
 *   <li>{@link PermissionGate#answer} 已改为**包内可见** ⇒ 只有本服务（同包）能调；跨包直调**编译不过**；</li>
 *   <li>每个 transport 只提供"标识 + 转交"，**不改状态机** ⇒ 新增一种传输方式不动状态机；</li>
 *   <li>transport 会被记进日志与可读状态 ⇒ 答复来源**可归因**（与 F1 的 `Driver` 同一条纪律）。</li>
 * </ul>
 *
 * <p>⚠️ **未做（F3-残）**：**"看"这一侧**没做 —— 非游戏内主体今天**读不到**待答复请示
 * （`PermissionGate.pending(bot)` 在服务端进程内可取，但没有对外通道）。本条只把**答复**入口收口。
 */
public final class PermissionService {

    /** 传输方式（谁在答复）。新增一种**不改状态机**，只是多一个字符串。 */
    public static final String TRANSPORT_COMMAND = "command";
    public static final String TRANSPORT_CLIENT_PACKET = "client_packet";
    public static final String TRANSPORT_FIXTURE = "fixture";
    /** 预留：非游戏内主体（桌面 AI / 外部驱动者）。 */
    public static final String TRANSPORT_EXTERNAL = "external";

    private static final Map<UUID, String> LAST_TRANSPORT = new ConcurrentHashMap<>();

    private PermissionService() {
    }

    /**
     * 答复一条请示（**唯一入口**）。
     *
     * @param transport 谁在答复（见本类常量）
     * @param by        人类可读的答复者（玩家名 / 夹具名 …）
     * @return 是否命中了一条待答复请示
     */
    public static boolean answer(String transport, MinecraftServer server, String id, String option,
                                PermissionGate.Scope scope, String by) {
        boolean ok = PermissionGate.answer(server, id, option, scope, by);
        if (ok && server != null) {
            String key = transport == null || transport.isBlank() ? "unknown" : transport;
            // 记"最后一次答复的传输方式"给该 server 上的所有在跑 bot（供夹具/汇报归因）
            for (Object player : server.getPlayerList().getPlayers()) {
                if (player instanceof BotPlayer bot) {
                    LAST_TRANSPORT.put(bot.getUUID(), key);
                }
            }
        }
        return ok;
    }

    /** **夹具/汇报用**：该 bot 最近一次请示答复的 transport（没答过 ⇒ `-`）。 */
    public static String lastTransport(BotPlayer bot) {
        return LAST_TRANSPORT.getOrDefault(bot.getUUID(), "-");
    }

    /** **夹具/复位用**。 */
    public static void reset(BotPlayer bot) {
        LAST_TRANSPORT.remove(bot.getUUID());
    }
}
