package com.dddgn.alice.bot;

import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * ⭐ **服务器 tick 末的「中立挂点」**（刀 2；`D-512` 的结构性配套）。
 *
 * <h2>为什么需要它</h2>
 * 「自检编排器**不在会话任务里**、由 server tick 驱动」这件事（`R-2 Phase 1b`）原先写成
 * `BotManager.onServerTick` 里的一行 `fixture.check.CheckHarness.tickAll(server)` ⇒
 * **`bot/` 反向认识 `fixture/`**（刀 2 要清掉的那类依赖之一）。
 *
 * <p>两种写法都能达到"编排器挂在 tick 上"，但**只有一种保 tick 顺序**：
 * <table border="1">
 *   <tr><th>写法</th><th>顺序</th></tr>
 *   <tr><td>❌ 让编排器**自订阅** `ServerTickEvent`</td>
 *       <td>同优先级（`NORMAL`）监听器的**相对次序由 Forge 的类扫描顺序决定**（不可证、不可读）
 *           ⇒ `tickAll` 相对 `road/RoadBuilder`、`fixture/mining/*Fixture` 的次序会**不可控地改变**</td></tr>
 *   <tr><td>✅ **本挂点**</td>
 *       <td>挂点调用点**逐字就是原来那一行** ⇒ **tick 顺序零变化**</td></tr>
 * </table>
 *
 * <p>⚠️ 这是**与草案 §2 刀 2 的一处显式偏离**（草案写"`CheckHarness` 自订阅 `ServerTickEvent`"）——
 * 偏离理由是**可证的顺序安全**，登记在 `docs/OPEN_ITEMS_LEDGER.md` 的刀 2 条目里。
 *
 * <p><b>语义边界</b>：本类**只有"服务器 tick 末"这一个含义**，⛔ 不认识任何具体挂载者
 * （挂载者是谁、挂几个，`bot/` 一概不知道）⇒ 不构成新的反向依赖。
 *
 * <p>挂载点在**每 tick、每次 `ServerTickEvent.END`**、且**在会话循环之前**触发（与旧位置逐字相同）。
 */
public final class ServerTickHooks {

    private ServerTickHooks() {
    }

    /** ⚠️ `CopyOnWriteArrayList`：挂载发生在启动期、触发在每 tick ⇒ 迭代不持锁、不 `ConcurrentModificationException`。 */
    private static final List<Consumer<MinecraftServer>> SERVER_END_TICK = new CopyOnWriteArrayList<>();

    /**
     * 注册一个「服务器 tick 末」挂点。⚠️ **幂等责任在调用方**（重复注册 = 每 tick 触发两次）。
     *
     * @param hook 收服务器实例；**不得**抛异常（本类不吞异常：抛出去就是 tick 线程的错，该炸就炸）
     */
    public static void onServerEndTick(Consumer<MinecraftServer> hook) {
        SERVER_END_TICK.add(hook);
    }

    /** 当前挂载数（供门禁/诊断读取；⛔ 不是给生产逻辑用的开关）。 */
    public static int hookCount() {
        return SERVER_END_TICK.size();
    }

    /** 由 {@link BotManager#onServerTick} 在**原调用点**逐字位置驱动。 */
    static void fireServerEndTick(MinecraftServer server) {
        for (Consumer<MinecraftServer> hook : SERVER_END_TICK) {
            hook.accept(server);
        }
    }
}
