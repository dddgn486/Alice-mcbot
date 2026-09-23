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
    /** **被跳过的步**（环境不具备）—— 与电池同口径：**不算失败，但也不算绿**（判决 `DEGRADED` ✓）。 */
    private final List<String> skipped = new ArrayList<>();
    /** 当前步的任务实例（`doneWhen`/`skipWhen` 都要拿它做判据 ⇒ 必须留引用 ✓）。 */
    private Task current;
    /** 本步的账本作用域 id（编排器开、由会话的 `clearTask` 收；早期退出路径由本类兜底收 ✓）。 */
    private String stepScope;
    private int index;
    private int phase;          // 0=等空闲 1=起任务 2=等终态
    private int stepStartTick;
    /**
     * ⭐ `Z2`：**上一步闭合时**的人口基线 = 本步窗口的起点（`WorldModLedger.Population`）。
     * 泄漏判据只看本步作用域**保护区内**的条目，而"本步到底写没写世界"要靠这对计数器才读得出来
     * （区外不入账 ⇒ 只看账本分不清"没写"和"写了但全在区外"）。取在步边界 = 本步的
     * provision/场景都落在窗口内 ✓（与电池 `endStep` 同口径，见 `rule_step_boundary_parity`）。
     */
    private WorldModLedger.Population stepPopulationBaseline = WorldModLedger.Population.ZERO;
    private int premiseStartTick;
    private int ticks;
    private String currentResultDetail = "";
    /**
     * ⭐ `D-347`（运行账）**基线 + 起过几步** —— 用来断言"会话侧的运行账真的在动"。
     *
     * <p>为什么必须由编排器来断言：本编排器**不在会话任务里**（见类注释），步任务是它**直接 tick** 的，
     * 而运行账那几格只能由会话写（`beginTask` / `recordTerminal`）⇒ 如果接线断了，
     * **每一步都会静默少记一条**，而所有步的判据仍然全绿（没人读那个账）。
     * 这里把它变成结构判据：**每起过一步，就必须有一条对应的启动与终态记录**。
     */
    private final com.dddgn.alice.bot.TaskMetrics.Snapshot metricsBaseline;
    private int sessionTasksStarted;

    private CheckHarness(MinecraftServer server, BotPlayer bot, ServerPlayer observer,
                         String moduleId, List<CheckStep> steps) {
        this.server = server;
        this.bot = bot;
        this.observer = observer;
        this.moduleId = moduleId;
        this.steps = steps;
        this.metricsBaseline = com.dddgn.alice.bot.TaskMetrics.snapshot();
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
                        endStepHygiene();
                        index = steps.size();
                    }
                    return;
                }
                BotLog.info("[Harness] step={} ({}/{}) scenes={} budget={}", step.name(), index + 1,
                        steps.size(), step.scenes(), step.budgetTicks());
                // **账本作用域先开**（与电池 `setup` 同序：**openScope → 场景 → provision → 起任务** ✓）。
                // 为什么顺序要紧：`provision` 里可能有"把**本步作用域**的预算/权限压到 0"这类测试前提
                //（`mine_budget`：`WriteBudget.setCaps(WriteBudget.scopeOf(bot), Caps(0,0))`）——
                // 作用域还没开时它会挂到**孤儿/implicit** 作用域上 ⇒ 前提静默失效 ✗（步骤会以超时红）。
                stepScope = WorldModLedger.openScope(server, bot.getUUID(),
                        "Harness:" + moduleId + ":" + step.name());
                bot.controller().stopMovement();
                // **顺序（v1 的关键修正）**：先**发料/传送**（把区块热起来 ✓）**再**跑场景函数。
                // 旧电池是"场景→provision"✗ ⇒ 区块冷时 `/fill` 不落地 ⇒ 判据在虚空里假绿 ✗
                // （`single:craft_table` 单跑必红就是这个坑 ✓）。这里顺序反过来 ⇒ 模块**自足** ✓。
                if (step.provision() != null) {
                    step.provision().run();
                }
                if (!step.scenes().isEmpty()) {
                    var source = server.createCommandSourceStack().withSuppressedOutput();
                    for (String fn : step.scenes()) {
                        int sceneRc = server.getCommands().performPrefixedCommand(source, "function " + fn);
                        // ⭐ `D-251` 教训：`withSuppressedOutput()` 会把场景函数的失败**全部吞掉** ⇒
                        // 夹具会拿着"没有地形"的世界做判断。返回值如实记下，rc<=0 响亮告警。
                        BotLog.info("[Harness] scene={} rc={}", fn, sceneRc);
                        if (sceneRc <= 0) {
                            BotLog.warn("[Harness] ⚠️ 场景函数 {} 一条命令都没成功（rc={}）⇒ 本步前提可能没落地",
                                    fn, sceneRc);
                        }
                    }
                }
                premiseStartTick = ticks;
                phase = 1;
            }
            case 1 -> {
                // **前提自证**：残留容器菜单先关掉；等落地（每 tick 复检 ✓ 不复检会白等满上限 ✗）
                var own = com.dddgn.alice.task.FixturePremise.ownMenu(bot);
                if (!own.ok()) {
                    BotLog.warn("[Harness] step={} 有残留容器菜单 ⇒ 先关掉再跑（{}）", step.name(), own.detail());
                    bot.closeContainer();
                }
                if (!com.dddgn.alice.task.FixturePremise.onGround(bot).ok()) {
                    if (ticks - premiseStartTick > PREMISE_TIMEOUT_TICKS) {
                        failures.add("step=" + step.name() + "：等落地超时 " + PREMISE_TIMEOUT_TICKS + " tick ✗");
                        endStep();
                    }
                    return;
                }
                if (ticks - premiseStartTick > 0) {
                    BotLog.info("[Harness] premise step={} 已落地（等了 {} tick）⇒ 开始本步",
                            step.name(), ticks - premiseStartTick);
                }
                // ⭐ `Z2`：**不再**在这里读 `pendingForOwner`（跨 scope 的 owner 口径）——
                // 泄漏判据改收在"**本步作用域 + 保护区内**"（见终态分支的 `closure`）。
                Driver.set(bot, Driver.FIXTURE);
                Task task = step.factory().get();
                current = task;
                if (task == null) {
                    failures.add("step=" + step.name() + "：任务工厂返回 null ✗");
                    endStep();
                    return;
                }
                if (!BotManager.beginSelfCheckTaskInOpenScope(bot, task)) {
                    failures.add("step=" + step.name() + "：起任务失败（会话忙或不存在 ✗）");
                    endStep();
                    return;
                }
                sessionTasksStarted++;   // `D-347`：这一步真的起了会话任务 ⇒ 运行账必须多一条
                stepStartTick = ticks;
                phase = 2;
            }
            case 2 -> {
                if (BotManager.isBusy(bot)) {
                    // **`doneWhen`（常驻任务）**：与电池逐字同口径 —— 判据成立即**按达成判过**并停任务
                    // （电池那边是"不再 tick 它"；这边是普通会话任务 ⇒ 必须显式停 ✓）。
                    // 为什么必须有：区域型 Job（如 `lumber_job`/`region_maintain`）**本来就会一直巡查**
                    // ⇒ 只按"跑完看终态"会把"本来就该常驻"误报成超时 ✗（电池注释里写明的那条）。
                    if (step.doneWhen() != null && current != null && step.doneWhen().test(current)) {
                        String detail = "ticks=" + (ticks - stepStartTick) + "（doneWhen 判据成立 ⇒ 按达成判过；task="
                                + current.getClass().getSimpleName() + " terminalReason="
                                + current.terminalReason() + "）";
                        BotManager.stopTask(bot, "harness_done_when");
                        currentResultDetail = detail;
                        BotLog.info("[Harness] step={} PASS ticks={} detail={}", step.name(),
                                ticks - stepStartTick, detail);
                        endStep();
                        return;
                    }
                    if (ticks - stepStartTick > step.budgetTicks()) {
                        failures.add("step=" + step.name() + "：超预算 " + step.budgetTicks() + " tick ✗");
                        BotManager.stopTask(bot, "harness_budget");
                        endStep();
                    }
                    return;
                }
                // 终态：读会话记录的结果（与玩家任务同一套记录 ✓）
                // 会话的终态文本只有两态：**"done"** / **"failed:原因"**（见 BotManager:973 的注释 ✓）
                // ⇒ **fail-closed**：只有恰好 "done" 才算过 ✓（别的值一律当失败并原样展示 ✗ 不许静默降级 ✓）
                String result = BotManager.lastTaskResult(bot);
                // **`doneWhen` 也要在终态判一次**（2026-09-17 `module:mining` 单跑实测的缺口）：
                // 电池把 `doneWhen` 判在"看终态**之前**"，而且它拿着任务实例 ⇒ 任务跑得再快都判得到 ✓；
                // 编排器原先只在 `isBusy` 分支里判 ⇒ **"预期失败但归因正确"的步**（`mine_no_tool` 的
                // `tool_missing`、`mine_stale` 的 `stale_target`、`mine_budget` 的 `write_budget_exhausted`）
                // 常常在 10 来个 tick 内就终态了 ⇒ 编排器从没来得及判 `doneWhen` ⇒ **把"达成"误判成 FAIL** ✗
                // （实测：三步的终态理由**恰好就是**期望的那个，却被记了红 ✓）。
                boolean doneMatched = step.doneWhen() != null && current != null && step.doneWhen().test(current);
                boolean pass = doneMatched || "done".equals(result);
                currentResultDetail = doneMatched
                        ? "doneWhen 判据成立 ⇒ 按**达成**判过（task=" + current.getClass().getSimpleName()
                                + " terminalReason=" + current.terminalReason()
                                + "；会话终态=" + (result == null ? "(no_result)" : result) + "）"
                        : (result == null ? "(no_result)" : result);
                // **B 方案（D-283）**：留下我方临时方块且未声明 KEEP ⇒ 本步判红（错误当场出现 ✓）
                // ⭐ `Z2`（2026-09-23）：口径收窄为「**本步作用域 + 保护区内**」——
                //   ① 只认区内（`D-398` R1/R2：区外不负责任 ⇒ 不许拿区外残留判红本步）；
                //   ② 作用域是本步自己的（原先读 `pendingForOwner` = 跨 scope 的 owner 口径，
                //      别的步的遗留会误伤本步 —— 那正是 D-298 那一类假红）。
                var closure = WorldModLedger.closure(bot.serverLevel(), stepScope, stepPopulationBaseline);
                boolean leakFailed = closure.inZone() > 0 && !step.keepWorldState();
                if (leakFailed) {
                    pass = false;
                    currentResultDetail = "leaked_temporary_blocks=" + closure.inZone()
                            + "（未声明 KEEP ✗）ledger[" + closure.describe() + "]";
                } else if (!closure.empty() || closure.anythingHappened()) {
                    // ⭐ `Z2` **可见性**：账本侧发生过事情就印人口读数 —— 否则"账本空"会被读成"没写世界"
                    // （实测：CORE 里 8 条记账全来自自认领的 `scaffold` 步，13 次放置全 `skip`）。
                    BotLog.info("[Ledger] 闭合 module={} step={} {}", moduleId, step.name(), closure.describe());
                }
                // **`skipWhen`（环境不具备）**：与电池同口径 —— **不看终态是 DONE 还是 FAILED**
                // （T0-a 堵假绿的教训：`MachineProbeTask` 缺模组时**如实**返回 DONE + `machine_namespaces_absent`，
                //  旧判据"终态不是 DONE 才 SKIP"会把它记成 **PASS** ⇒ "什么都没断言"被记成绿 ✗）。
                // 但**泄漏我方方块仍然是失败**（电池的 endStep 卫生对 SKIP 步同样生效 ✓）⇒ 先判泄漏。
                boolean skippedStep = !leakFailed && step.skipWhen() != null && current != null
                        && step.skipWhen().test(current);
                if (skippedStep) {
                    skipped.add(step.name());
                    BotLog.info("[Harness] step={} SKIP ticks={} detail={}（环境不具备 ⇒ 不算失败，"
                                    + "但**也不算绿**：整轮判决降为 DEGRADED ✗ 不许冒充 PASS）",
                            step.name(), ticks - stepStartTick, currentResultDetail);
                    endStep();
                    return;
                }
                if (!pass) {
                    failures.add("step=" + step.name() + "：" + currentResultDetail);
                }
                BotLog.info("[Harness] step={} {} ticks={} detail={}", step.name(), pass ? "PASS" : "FAIL",
                        ticks - stepStartTick, currentResultDetail);
                endStep();
            }
            default -> {
                phase = 0;
            }
        }
    }

    /**
     * **步边界（所有结束路径的唯一出口）**：`endStepHygiene()` + 前进一格。
     *
     * <p>为什么要收敛到一个出口：实测（2026-09-17，`module:craft` 第一次单跑）发现
     * **`craft_goal` 单跑红**，根因不是合成而是**站点选择跨步泄漏** ——
     * `craft_cooking` 的 provision 把选择设成 `upgradetab`，而编排器没有还原它 ⇒
     * `CraftJob` 走升级页签路线 ⇒ `[CraftJob] 失败 code=upgrade_item_absent … station=upgradetab`。
     * 旧电池本来就在 `endStep` 里写了这一句（「**站点选择不跨步泄漏**：谁设的谁收 ✓」）⇒
     * 编排器缺它 = **"行为等价"是假的**（CORE 曾经是绿的，只是因为电池那边有那句话 ✓）。
     */
    private void endStep() {
        endStepHygiene();
        index++;
        phase = 0;
        // ⭐ `Z2`：**步边界**取人口基线（本步 provision/场景落在窗口内 ✓，与电池同口径）
        stepPopulationBaseline = WorldModLedger.populationBaseline(server);
    }

    /**
     * **步边界卫生（与电池 `endStep` 逐条对齐 ✓）**。
     *
     * <p>编排器靠会话任务生命周期拿到的部分（账本作用域 / 写入预算 / 终态记录）**不在这里** ——
     * 那些由 `BotManager.beginSelfCheckTask` 与 `clearTask` 负责 ✓。这里只补**会话生命周期不管**的
     * **跨步全局态**：`CraftStation` 的按 bot 选择（内存态、每步读一次）⇒ 不还原就会让"下一步"
     * 的行为取决于"上一步设了什么"✗（正是模块独立性最怕的隐含前提 ✗）。
     */
    private void endStepHygiene() {
        com.dddgn.alice.task.craft.CraftStation.select(bot, "auto");
        // **兜底收作用域**：正常路径由会话的 `clearTask` 收（那时 scope 已经不在），这里只处理
        // **任务根本没起来**的早期退出路径（前提超时 / 工厂返回 null / 起任务失败 / 看门狗）
        // —— 作用域由编排器开，不收就变成**残留作用域**（`BotManager` 有残留检查 ⇒ 后续判据会红 ✗）。
        // ⚠️ 任务仍在跑时**不能收**（`stopTask` 有 K-3 安全点，可能延后）⇒ 只在空闲时收 ✓。
        if (stepScope != null && !BotManager.isBusy(bot)) {
            com.dddgn.alice.action.WriteBudget.closeScope(
                    WorldModLedger.closeScope(server, bot.getUUID()));
            stepScope = null;
        }
    }

    private void verdict() {
        // ⭐ `D-347`（运行账）：**"账活着"的结构判据** —— 起过几步，账上就必须有几条启动/终态记录。
        // 反向可控：拆掉 `beginTask` 里的 `noteStart`（或 `recordTerminal` 里的 `noteTerminal`）
        // ⇒ 本判据立刻红 ⇒ 整个 `module:` 轮次不再绿（否则那种断线**没有任何判据会发现**）。
        com.dddgn.alice.bot.TaskMetrics.Snapshot delta =
                com.dddgn.alice.bot.TaskMetrics.snapshot().delta(metricsBaseline);
        int inFlight = BotManager.isBusy(bot) ? 1 : 0;
        BotLog.info("[Harness] 运行账（D-347）：started=+{} finished=+{} ticks=+{} arrived=+{} 世界改动 breaks=+{}"
                        + " places=+{} ｜起过 {} 步 inFlight={}",
                delta.started(), delta.finished(), delta.ticks(), delta.arrived(), delta.breaks(),
                delta.places(), sessionTasksStarted, inFlight);
        if (delta.started() < sessionTasksStarted || delta.finished() + inFlight < delta.started()) {
            failures.add("运行账（D-347）没跟上：本模块起过 " + sessionTasksStarted + " 步，账上却是 started="
                    + delta.started() + " finished=" + delta.finished() + " ticks=" + delta.ticks()
                    + " ⇒ 会话侧（`beginTask`/`recordTerminal`）的接线断了，本轮其它判据不能当证据");
        }
        boolean pass = failures.isEmpty();
        // **三态判决与电池一致**（`PASS` / `DEGRADED` / `FAIL`）：
        // `DEGRADED` = 没有真失败，但有步因**环境不具备**被跳过 ⇒ **不是绿**，不可作为验收证据 ✓
        //（无头入口把 DEGRADED 翻成退出码 **2**，与电池同约定 ✓）。
        lastVerdict = !pass ? "FAIL" : skipped.isEmpty() ? "PASS" : "DEGRADED";
        finished = true;
        RUNNING = null;
        BotLog.info("[Harness] SUMMARY module={} steps={} failures={} {} skipped={} {} → {}",
                moduleId, steps.size(), failures.size(), failures, skipped.size(), skipped, lastVerdict);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[alice] 模块 " + moduleId + " 单跑 " + lastVerdict
                            + (pass ? "（" + steps.size() + " 步"
                                    + (skipped.isEmpty() ? "" : "，跳过 " + skipped) + "）"
                                    : " " + failures)));
        }
    }

    private static final int WATCHDOG_TICKS = 20 * 600;
    /** 等落地的上限（与电池同口径 ✓：超时如实失败 ✗ 不静默跳过 ✗）。 */
    private static final int PREMISE_TIMEOUT_TICKS = 200;

    /** 模块构建步时能看到的**只读**上下文（模块不持有编排状态 ✓）。 */
    private record SimpleContext(BotPlayer bot, ServerPlayer observer) implements CheckContext {
        @Override
        public com.dddgn.alice.perception.ScopeBuffer scope() {
            // 与玩家任务**共用同一个**会话作用域缓冲 ✓（v0 曾返回 null ⇒ 账本模块第一步就 NPE 崩服 ✗，已修）
            return BotManager.scopeOf(bot);
        }
    }
}
