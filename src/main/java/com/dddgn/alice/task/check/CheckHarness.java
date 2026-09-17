package com.dddgn.alice.task.check;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.Driver;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.Task;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **自检编排器（R-2 Phase 1b）** —— 用户原话：「让电池本身**脱离任务管理的束缚**，每个电池步按分类模块化，
 * **一个模块保证可以单独测**」。
 *
 * <p><b>与旧电池的关键差别</b>：旧电池**自己就是 bot 的会话任务**（`session.task = RegressionBatteryTask`）✗
 * ⇒ 后果实测过两次：① 任何指派任务的命令（如 `/alice follow on`）会**把电池顶掉** ⇒ 电池没有判决行、整轮 `no_verdict`；
 * ② `stopTask` 会连电池一起杀 ✗。
 * <br>本编排器**不在会话里**：它由服务器 tick 驱动（`BotManager.onServerTick` ✓），每一步**按普通任务**
 * 起（`BotManager.beginSelfCheckTask` ⇒ `session.beginTask` ✓）⇒ 外部命令与它**互不顶替** ✓，
 * 步任务也走**和玩家任务完全一样**的生命周期（终态记录、账本作用域、决策层暂停 ✓）。
 *
 * <p><b>v0 的诚实边界</b>：只支持**不需要场景/发料**的模块（账本模块即此类 ✓）；场景/发料/前提等待
 * 在 v1 补齐（登记在案 ✓）。判据：`module:ledger` 单独跑通 ⇒ **"一个模块可以单独测"** 这条硬要求第一次成立 ✓。
 */
public final class CheckHarness {

    /** 同时只允许一个编排器（与电池同理：无头/游戏内都不会并行跑两份 ✓）。 */
    private static volatile CheckHarness RUNNING;
    /** 最近一次判决（null = 从未跑过；无头驱动据此出退出码 ✓）。 */
    private static volatile String lastVerdict;
    private static volatile boolean finished;

    private final MinecraftServer server;
    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final String moduleId;
    private final List<CheckStep> steps;
    private final List<String> failures = new ArrayList<>();
    private int index;
    private int phase;          // 0=等空闲 1=起任务 2=等终态
    private int stepStartTick;
    private int pendingBefore;
    private int ticks;
    private String currentResultDetail = "";

    private CheckHarness(MinecraftServer server, BotPlayer bot, ServerPlayer observer,
                         String moduleId, List<CheckStep> steps) {
        this.server = server;
        this.bot = bot;
        this.observer = observer;
        this.moduleId = moduleId;
        this.steps = steps;
    }

    /** 起一个模块单跑；返回 false 表示起不来（未知模块 / 已有别的编排器 / bot 忙 ✗ 均如实拒绝 ✓）。 */
    public static boolean start(MinecraftServer server, BotPlayer bot, ServerPlayer observer, String moduleId) {
        CheckModule module = CheckModules.byId(moduleId);
        if (module == null) {
            BotLog.warn("[Harness] 未知模块 id={}（已知：{}）⇒ 拒绝启动", moduleId, CheckModules.knownIds());
            return false;
        }
        if (RUNNING != null) {
            BotLog.warn("[Harness] 已有编排器在跑（module={}）⇒ 拒绝并发 ✗", RUNNING.moduleId);
            return false;
        }
        List<CheckStep> steps = module.steps(new SimpleContext(bot, observer));
        if (steps.isEmpty()) {
            BotLog.warn("[Harness] 模块 {} 没有步 ⇒ 拒绝启动（空模块不能算通过 ✗）", moduleId);
            return false;
        }
        CheckHarness harness = new CheckHarness(server, bot, observer, moduleId, steps);
        RUNNING = harness;
        lastVerdict = null;
        finished = false;
        BotLog.info("[Harness] 启动模块单跑 module={}「{}」步数={}（不在会话任务里 ✓ 每步按普通任务起 ✓）",
                module.id(), module.title(), steps.size());
        return true;
    }

    /** 服务器 tick 驱动（与任何会话任务无关 ✓）。 */
    public static void tickAll(MinecraftServer server) {
        CheckHarness harness = RUNNING;
        if (harness != null) {
            harness.tick();
        }
    }

    public static boolean isFinished() {
        return finished;
    }

    public static String lastVerdict() {
        return lastVerdict;
    }

    private void tick() {
        ticks++;
        if (index >= steps.size()) {
            verdict();
            return;
        }
        CheckStep step = steps.get(index);
        switch (phase) {
            case 0 -> {
                if (BotManager.isBusy(bot)) {
                    if (ticks > WATCHDOG_TICKS) {
                        failures.add("step=" + step.name() + "：等空闲超时（" + BotManager.busyMessage(bot) + "）");
                        index = steps.size();
                    }
                    return;
                }
                pendingBefore = WorldModLedger.pendingForOwner(server, bot.getUUID()).size();
                Driver.set(bot, Driver.FIXTURE);
                Task task = step.factory().get();
                if (task == null) {
                    failures.add("step=" + step.name() + "：任务工厂返回 null ✗");
                    index++;
                    return;
                }
                BotManager.beginSelfCheckTask(bot, task);
                stepStartTick = ticks;
                phase = 1;
            }
            case 1 -> {
                if (BotManager.isBusy(bot)) {
                    if (ticks - stepStartTick > step.budgetTicks()) {
                        failures.add("step=" + step.name() + "：超预算 " + step.budgetTicks() + " tick ✗");
                        BotManager.stopTask(bot, "harness_budget");
                        index++;
                        phase = 0;
                    }
                    return;
                }
                // 终态：读会话记录的结果（与玩家任务同一套记录 ✓）
                // 会话的终态文本只有两态：**"done"** / **"failed:原因"**（见 BotManager:973 的注释 ✓）
                // ⇒ **fail-closed**：只有恰好 "done" 才算过 ✓（别的值一律当失败并原样展示 ✗ 不许静默降级 ✓）
                String result = BotManager.lastTaskResult(bot);
                boolean pass = "done".equals(result);
                currentResultDetail = result == null ? "(no_result)" : result;
                // **B 方案（D-283）**：留下我方临时方块且未声明 KEEP ⇒ 本步判红（错误当场出现 ✓）
                int pendingAfter = WorldModLedger.pendingForOwner(server, bot.getUUID()).size();
                if (pendingAfter > pendingBefore && !step.keepWorldState()) {
                    pass = false;
                    currentResultDetail = "leaked_temporary_blocks=" + (pendingAfter - pendingBefore) + "（未声明 KEEP ✗）";
                }
                if (!pass) {
                    failures.add("step=" + step.name() + "：" + currentResultDetail);
                }
                BotLog.info("[Harness] step={} {} ticks={} detail={}", step.name(), pass ? "PASS" : "FAIL",
                        ticks - stepStartTick, currentResultDetail);
                index++;
                phase = 0;
            }
            default -> {
                phase = 0;
            }
        }
    }

    private void verdict() {
        boolean pass = failures.isEmpty();
        lastVerdict = pass ? "PASS" : "FAIL";
        finished = true;
        RUNNING = null;
        BotLog.info("[Harness] SUMMARY module={} steps={} failures={} {} → {}",
                moduleId, steps.size(), failures.size(), failures, lastVerdict);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[alice] 模块 " + moduleId + " 单跑 " + lastVerdict
                            + (pass ? "（" + steps.size() + " 步）" : " " + failures)));
        }
    }

    private static final int WATCHDOG_TICKS = 20 * 600;

    /** 模块构建步时能看到的**只读**上下文（模块不持有编排状态 ✓）。 */
    private record SimpleContext(BotPlayer bot, ServerPlayer observer) implements CheckContext {
        @Override
        public com.dddgn.alice.perception.ScopeBuffer scope() {
            // 与玩家任务**共用同一个**会话作用域缓冲 ✓（v0 曾返回 null ⇒ 账本模块第一步就 NPE 崩服 ✗，已修）
            return BotManager.scopeOf(bot);
        }
    }
}
