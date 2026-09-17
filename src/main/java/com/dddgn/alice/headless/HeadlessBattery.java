package com.dddgn.alice.headless;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * **无头电池入口（T2）**：把"游戏内右键 `alice:regression_battery`"这条验收路径搬到无需真人的服务端。
 *
 * <p>开关是一个系统属性，**默认关闭**（生产/游戏内路径零开销；未开启时本类只读一次属性就返回）：
 * <pre>
 *   ./gradlew runServer --no-daemon -Dalice.headless.battery=core
 *   ./gradlew runServer --no-daemon -Dalice.headless.battery=full
 *   ./gradlew runServer --no-daemon -Dalice.headless.battery=single:capability_gate
 * </pre>
 *
 * <p>它只做四件事：① 起服后生成一只假人；② 走**与游戏内物品完全相同**的入口
 * （{@link BotManager#assignRegressionBattery}，`observer=null` —— 86 处 `observer` 调用点全部有
 * `observer != null` 守卫，所以"没有真人观察者"是既有设计支持的，不是本入口的补丁）；
 * ③ 等电池跑完读 {@link com.dddgn.alice.task.RegressionBatteryTask#lastVerdict()}；
 * ④ 把判决翻成**进程退出码**并 `System.exit`，让 CI 能判红。
 *
 * <p><b>退出码</b>（脚本 `tools/headless-battery.sh` 直接透传）：`0`=PASS、`1`=FAIL、`2`=DEGRADED
 * （有步因环境不具备被跳过 ⇒ **不是绿**）、`3`=看门狗超时（没等到判决）、`4`=起不来（假人/指派失败）。
 *
 * <p><b>世界与模组不在本类里管</b>：夹具的 `START_FOOT` 是**绝对坐标**（`(6,64,67)`、`(0,64,66)`…），
 * 其中有 5 个 CORE 步既无场景函数也无 `provision`，完全依赖该坐标处已存在的地形 ⇒ 无头世界必须是
 * **客户端测试存档的副本**。见 `tools/headless-battery.sh`。
 */
public final class HeadlessBattery {

    /** 开关属性：空/缺省 = 不启用（游戏内与会话测试完全不受影响）。 */
    private static final String PROP = "alice.headless.battery";

    /** 起服后等多少 tick 再生成假人：让 `ServerStartedEvent` 的其余监听者（存档恢复等）先跑完。 */
    private static final int SPAWN_DELAY_TICKS = 20;

    /**
     * 看门狗：超过这么多 tick 仍没等到判决 ⇒ 退 3。
     *
     * <p>必须存在：CI 上"卡住不动"比"失败"更贵（要人去看）。取值 = 电池自身 `TOTAL_BUDGET_TICKS`
     * 之外再留 2 分钟余量。
     */
    private static final int WATCHDOG_TICKS = 30000;

    private static boolean enabled;
    private static boolean fullProfile;
    /** 模块单跑模式下的模块 id（null = 不是模块模式 ✓）。 */
    private static String moduleId;
    private static boolean spawnRequested;
    private static boolean assigned;
    private static boolean sawRunningTask;
    private static int ticks;
    private static BotPlayer bot;

    private HeadlessBattery() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String mode = System.getProperty(PROP, "").trim();
        if (mode.isEmpty()) {
            return;   // 生产路径：一次属性读取就结束
        }
        if ("list-modules".equals(mode)) {
            // **R-2 验收入口**：模块 id 的**唯一出处**在 `CheckModules` ✓ —— 脚本据此逐个单跑，不靠手抄 ✗
            String ids = String.join(",", com.dddgn.alice.task.check.CheckModules.knownIds());
            System.out.println("[Headless] MODULES ids=" + ids);
            System.out.println("[Headless] MODULES expected="
                    + com.dddgn.alice.task.check.CheckModules.expectedVerdicts());
            System.out.flush();
            BotLog.info("[Headless] MODULES ids={}", ids);
            exit(event.getServer(), 0, "list_modules");
            return;
        }
        if ("core".equals(mode) || "full".equals(mode)) {
            fullProfile = "full".equals(mode);
        } else if (mode.startsWith("single:")) {
            String name = mode.substring("single:".length()).trim();
            if (name.isEmpty()) {
                BotLog.warn("[Headless] 属性 {}={} 的步名为空 ⇒ 不启用", PROP, mode);
                return;
            }
            // **快速失败（2026-09-17）**：写错步名原先要**白跑 200 tick** 才报 `battery_never_ran`（本轮实测踩到）。
            // 步名唯一出处 = `CURATION`（构造期自校验与步骤表一一对应）⇒ 起跑前就能判。
            java.util.Set<String> known = com.dddgn.alice.task.RegressionBatteryTask.knownStepNames();
            if (!known.contains(name)) {
                java.util.List<String> close = known.stream()
                        .filter(k -> k.contains(name) || name.contains(k)
                                || k.startsWith(name.substring(0, Math.min(4, name.length()))))
                        .sorted().limit(6).toList();
                BotLog.warn("[Headless] 未知步名 single:{}（已知 {} 步{}）⇒ 立即失败，不白跑",
                        name, known.size(), close.isEmpty() ? "" : "；相近候选：" + close);
                exit(event.getServer(), 6, "unknown_step");
                return;
            }
            com.dddgn.alice.task.RegressionBatteryTask.setOnlySteps(java.util.List.of(name));
            BotLog.info("[Headless] 定向模式：只跑 1 步 {}", name);
        } else if (mode.startsWith("module:")) {
            // **R-2（Phase 1b）**：模块单跑 —— 这是"一个模块保证可以单独测"的验收入口 ✓
            String id = mode.substring("module:".length()).trim();
            java.util.Set<String> known = com.dddgn.alice.task.check.CheckModules.knownIds();
            if (!known.contains(id)) {
                BotLog.warn("[Headless] 未知模块 module:{}（已知 {}）⇒ 立即失败", id, known);
                exit(event.getServer(), 6, "unknown_module");
                return;
            }
            moduleId = id;
        } else {
            BotLog.warn("[Headless] 无法识别的 {}={}（可用：core | full | single:<step> | module:<id>）⇒ 不启用",
                    PROP, mode);
            return;
        }
        enabled = true;
        BotLog.info("[Headless] 无头电池已启用 mode={} dim={}", mode,
                event.getServer().overworld().dimension().location());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!enabled || event.phase != TickEvent.Phase.START) {
            return;
        }
        MinecraftServer server = event.getServer();
        ticks++;

        if (!spawnRequested) {
            if (ticks < SPAWN_DELAY_TICKS) {
                return;
            }
            spawnRequested = true;
            ServerLevel level = server.overworld();
            bot = BotManager.firstOrSpawn(level, com.dddgn.alice.task.ClearRetryCheckTask.START_FOOT);
            if (bot == null) {
                exit(server, 4, "bot_spawn_failed");
                return;
            }
            BotLog.info("[Headless] 假人已就位 name={} foot={} dim={}", bot.getName().getString(),
                    bot.blockPosition(), level.dimension().location());
            return;
        }

        if (!assigned) {
            ServerPlayer observer = syntheticObserver(server.overworld());
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
            if (moduleId != null) {
                if (com.dddgn.alice.task.check.CheckHarness.start(server, bot, observer, moduleId)) {
                    assigned = true;
                    BotLog.info("[Headless] 模块单跑已启动 module={}（编排器不在会话任务里 ✓）", moduleId);
                } else {
                    exit(server, 4, "harness_start_failed:" + moduleId);
                }
                return;
            }
            if (BotManager.assignRegressionBattery(bot, observer, fullProfile)) {
                assigned = true;
                BotLog.info("[Headless] 电池已指派（入口与游戏内物品完全相同；observer={}）",
                        observer == null ? "null（无合成玩家）" : "合成第二玩家 " + observer.getUUID());
            } else {
                exit(server, 4, "assign_failed:" + BotManager.busyMessage(bot));
            }
            return;
        }

        // 指派成功后先确认它真的在跑（`isBusy` 在指派当 tick 就为 true；这里只是把
        // "从未起跑" 与 "跑完收工" 分开，避免把一次失败的指派误判成 PASS）。
        if (!sawRunningTask) {
            sawRunningTask = BotManager.isBusy(bot);
            if (!sawRunningTask && ticks > SPAWN_DELAY_TICKS + 200) {
                exit(server, 4, "battery_never_ran");
            }
            return;
        }

        if (moduleId != null) {
            if (com.dddgn.alice.task.check.CheckHarness.isFinished()) {
                String harnessVerdict = com.dddgn.alice.task.check.CheckHarness.lastVerdict();
                if (harnessVerdict == null) {
                    exit(server, 3, "harness_no_verdict");
                    return;
                }
                exit(server, "PASS".equals(harnessVerdict) ? 0 : 1, harnessVerdict);
            } else if (ticks > WATCHDOG_TICKS) {
                exit(server, 3, "harness_watchdog");
            }
            return;
        }

        if (!BotManager.isBusy(bot)) {
            String verdict = com.dddgn.alice.task.RegressionBatteryTask.lastVerdict();
            if (verdict == null) {
                exit(server, 3, "no_verdict");
                return;
            }
            exit(server, switch (verdict) {
                case "PASS" -> 0;
                case "DEGRADED" -> 2;
                default -> 1;
            }, verdict);
            return;
        }

        if (ticks > WATCHDOG_TICKS) {
            exit(server, 3, "watchdog:" + BotManager.busyMessage(bot));
        }
    }

    /**
     * **合成的"第二个玩家实体"**，充当电池的 `observer`。
     *
     * <p><b>为什么需要</b>：客户端跑电池时 `observer` = 真人玩家；无头环境没有真人。而全仓库**只有一处**
     * 真的用到它 —— {@code CapabilityGateCheckTask} 的 {@code foreign_break_attribution}：它 post 一条
     * {@code BreakEvent(…, breaker=observer)} 来验证"外来破坏能被认出"，并会在 `observer == null` 时
     * **如实自述** `premise_no_observer` 判红。那个用例要的**不是"真人"**，而是"**另一个玩家实体**"
     * （`ScopeBuffer` 只看 `event.getPlayer().getUUID()` 与 owner 是否相等，不查 `PlayerList`）。
     *
     * <p><b>语义边界（必须诚实标出）</b>：合成玩家只模拟"存在另一个玩家实体"这一件事。
     * 如果将来某条断言需要**只有真人才能做的事**（真实输入、客户端渲染、真实连接握手），
     * 这个替身会**造成假绿** —— 届时应把该断言改成在无头下自述"环境不具备"，而不是继续用替身。
     *
     * <p>取不到就退回 `null`（如实降级：那一步会报 `premise_no_observer`），不静默假装成功。
     */
    private static ServerPlayer syntheticObserver(ServerLevel level) {
        try {
            return net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        } catch (Throwable t) {
            BotLog.warn("[Headless] 合成 observer 失败 ⇒ 退回 null（foreign_break_attribution 会如实报 "
                    + "premise_no_observer）：{}", t.toString());
            return null;
        }
    }

    /** 打一行**机器可读**的终局（stdout + 日志 + `run/headless-result.txt` 三份），然后按判决退出。 */
    private static void exit(MinecraftServer server, int code, String verdict) {
        String line = "[Headless] RESULT verdict=" + verdict + " exit=" + code + " ticks=" + ticks;
        System.out.println(line);
        System.out.flush();
        BotLog.info(line);
        enabled = false;
        // 单独落一个结果文件：脚本据此拿判决，**不必**依赖日志刷盘或 Gradle 的退出码（后者会被吞）。
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("headless-result.txt"),
                    line + System.lineSeparator());
        } catch (java.io.IOException e) {
            BotLog.warn("[Headless] 结果文件写入失败（不影响判决，脚本会退回解析日志）：{}", e.toString());
        }
        // ⚠️ **必须用 halt，不能用 System.exit**——在服务端线程上调用 `System.exit` 会与 Forge/原版的
        // shutdown hook **互相 join 成死锁**（2026-09-14 现场线程转储实证）：
        //   "Server thread":           System.exit → Shutdown.runHooks → ApplicationShutdownHooks.runHooks → Thread.join()
        //   "Server Shutdown Thread":  MinecraftServer.halt(MinecraftServer.java:624) → Thread.join()
        // 服务端线程在 System.exit 里等 hook 结束，hook 在 halt 里等服务端线程结束 ⇒ 永不退出
        // （症状 = 判决行已打出、进程却一直挂着，像"还在跑"）。
        // `halt` 不跑 shutdown hook ⇒ 立刻终止、退出码原样带出。代价是**不存档**，这正是我们要的：
        // 每轮都从"原始世界母本"重新拷贝，夹具残留不该跨轮次累积（见 tools/headless-battery.sh）。
        //
        // **例外（`ALICE_SAVE_ON_HALT=1`）**：给"**持久化**"类实验用（D-235）—— 结清/账本这类写进
        // `SavedData` 的状态，**只有存档才看得见**（`halt` 不存档 ⇒ 磁盘上永远是母本那份）。
        // 打开它会在停机前同步存一次（`saveEverything(flush=true)`），于是可以解压 `world/data/alice_*.dat`
        // 读回真实落盘状态。默认关闭 ⇒ 平时行为一字不变。
        if (Boolean.getBoolean("alice.headless.saveOnHalt")) {
            BotLog.info("[Headless] ALICE_SAVE_ON_HALT ⇒ 停机前同步存档（持久化实验用）");
            try {
                server.saveEverything(true, true, false);
                BotLog.info("[Headless] 已存档 ⇒ 磁盘上的 alice_*.dat 反映本轮终态");
            } catch (Throwable t) {
                BotLog.warn("[Headless] 存档失败（不影响判决）：{}", t.toString());
            }
        }
        Runtime.getRuntime().halt(code);
    }
}
