package com.dddgn.alice.task;

import com.dddgn.alice.action.WritePolicyMatrix;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **写入策略表自检**（D-207 ① / R1）：验"表是完整的、闸门是活的、默认行为没变"。
 *
 * <p>为什么必须有负例：本项目的教训（P0-B `RecoverabilityLevel` 恒假、K-4 谓词不统一）说明
 * "声明了但没人读"的抽象会长期伪装成通过。所以这里不只验"表里有几行"，而是**构造一次越权请求**，
 * 要求它**必须被拒**——否则说明闸门恒假。
 *
 * <p><b>§6.9.3 三问自答</b>：① **层的归属** —— A/C/D/E/F 断言在**策略表层**（直接调下层原语
 * {@code WritePolicyMatrix}，不经会先短路的调用点），B **额外**断言在**接线后的规划器层**
 *（{@code CorePathPlanner.plan} 必须把它转成 {@code ERROR} plan + 可归因 diagnostics——
 * 这是"异常不会逃逸打断服务端 tick"的证据）；② **世界/模组/几何假设** —— 无：本自检不读地形、
 * 不依赖任何模组、也不依赖夹具场景（`zoneAt` 今天恒 `EXTERNAL`），唯一的前提是表内容本身（由 A 断言）；
 * ③ **失败时用户看到什么** —— 玩家侧**没有可见动作**，判据是聊天 `SUMMARY <key>=FAIL` 与
 * `[WritePolicy] case=… result=FAIL` 行（G 失败会在日志里点名未登记 requester）。
 *
 * <p><b>终态契约（2026-09-14 实测踩坑）</b>：{@code failures} 非空 ⇒ {@code tick()} 必须返回
 * {@code FAILED}。电池只按 {@code status == DONE && idempotent} 记账，**不看夹具自己打印的 verdict**
 * ——写死 {@code DONE} 会让内部 FAIL 被吞成 `(28/28) PASS` 的静默绿。同类规则见
 * {@code tools/check-fixture-hygiene.sh}；全仓另有 {@code RecoverabilityCheckTask} 曾同病（已修）。
 *
 * <p>另外两条例：G 未登记留痕（D-207 ①：`UNKNOWN` requester **记为错误**）、H **零写入自证**
 *（本步不得向账本新增任何条目——夹具自己不能弄脏世界）。
 *
 * <p>八条例（全部纯计算 + 两次注定被拒的规划尝试，不动世界）：
 * <ol>
 *   <li>A <b>表是全的</b>：{@code Zone × Task} 全枚举、每个 {@code WriteReason} 都有行登记、
 *       显式义务与该行理由不矛盾；</li>
 *   <li>B <b>闸门是活的</b>（负例）：纯通行任务（{@code walk-to}）要求
 *       {@code withWorldModification} 的移动集 ⇒ **必须抛 {@code WRITE_POLICY_MOVEMENT_DENIED}**；
 *       同一集合在 {@code DIAGNOSTIC}（夹具）下必须通过；</li>
 *   <li>C <b>授权集合语义没被改坏</b>（逐条对着 {@code PathRequest} 工厂的 javadoc 断言）：{@code of}/
 *       {@code pureTraversal} 不含任何写世界原语且后者是严格超集；{@code scaffoldRemoval}
 *       <b>只拆不建、也不挖穿</b>（∩放置=∅ ∧ ∩挖穿=∅ ∧ 含 {@code DOWNWARD+FALL}）；
 *       {@code miningApproach} 禁用 {@code PILLAR/FALL/DOWNWARD} 但必须保留破坏进入（D-067 ㉘）；</li>
 *   <li>D <b>登记表能认出各任务</b>：12 个真实 requester 归到预期类别，
 *       含 {@code :attemptN} 派生形态；空/{@code unknown} ⇒ {@code UNREGISTERED}（**记为错误**）；</li>
 *   <li>E <b>两区今天等价</b>（{@code zoneDiff=0}）：这是"R1 不改变默认行为"的**可执行**证据，
 *       一旦有人让两区不同，这里会红并逼出决策记录；</li>
 *   <li>F <b>义务解析与今天的口径一致</b>：{@code REASON_DEFAULT} 行 == {@code WriteReason.temporary()}，
 *       显式行（{@code BUILD}/{@code MANUAL}=KEEP、{@code TRAVERSAL}=TEMP）如声明。</li>
 *   <li>B3/B4 <b>容器写入闸门</b>（R1 收口，2026-09-14）：{@code walk-to}+{@code CONTAINER_TRANSFER}
 *       ⇒ {@code UNDECLARED_REASON}（纯通行任务不许写容器）、{@code transfer}+同理由 ⇒ {@code DECLARED}、
 *       未登记 requester ⇒ {@code UNREGISTERED}（缺口不拒）；B4 再把武装开关**真开了**跑一次
 *       {@code WriteBudget.consumeContainerWrite}，要求**真的被拒**，并断言观察模式放行
 *       （开关不是死值）——探针前后**快照/还原**观察样本，绝不污染 G 段。</li>
 * </ol>
 */
public class WritePolicyCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int ticks;
    private boolean done;

    public WritePolicyCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "WritePolicyCheck";
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
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 100) {
            return finish("timeout");
        }
        if (ticks > 1) {
            // **终态必须传播自检结论**（2026-09-14 实测修正）：上一版（照抄 RecoverabilityCheckTask）
            // 写死 `done ? Status.DONE : …` ⇒ 即使 `grants_semantics` 红了，电池只按 `status==DONE`
            // 记账，SUMMARY 里仍是 `write_policy=PASS (28/28) → PASS`——**假红被吞成静默绿**，
            // 比假红本身更危险。全仓其余 20 个 *CheckTask 都是 `failures.isEmpty() ? DONE : FAILED`，
            // 照此对齐；`tools/check-fixture-hygiene.sh` 已把这条变成静态规则。
            return done ? (failures.isEmpty() ? Status.DONE : Status.FAILED) : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    /**
     * `D-372` **默认不设格数上限**（用户 2026-09-21 裁定）。
     *
     * <p>用户原话：「**保护区外，世界修改全部放开，只限制时间防止空转**」；
     * 以及「不是说区内强制不让修改啊，**保护区本来不就是有分级权限管理吗，保持权限管理就行**」。
     *
     * <p>所以语义是三条，缺一不可：
     * <ol>
     *   <li>**默认（无显式上限）⇒ 不限格数**：世界修改放开，闸门换成**时间预算**防空转
     *       （`CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS` / `MiningBudget.maxExtraBreakTicks` /
     *       作业 `maxTicks` / `no_progress` 看门狗）；</li>
     *   <li>**显式装订的上限照旧强制**（`setCaps` / `capForEscape`）—— `D-241` 逃生准备金、
     *       以及各夹具刻意压到 1 格的用例都靠它；</li>
     *   <li>**保护区的约束不在这里**：它是独立的权限层（`CapabilityGate` → `protectionReason`
     *       ⇒ `protected_area` / `protected_block`）——**放开默认上限不许顺手拆掉它**。</li>
     * </ol>
     */
    private void runDefaultCapChecks() {
        String scope = com.dddgn.alice.action.WriteBudget.scopeOf(bot);
        net.minecraft.server.level.ServerLevel level = bot.serverLevel();
        net.minecraft.core.BlockPos pos = bot.blockPosition();
        try {
            // ⚠️ **前提断言（防静默测量失败）**：`consumeBreak` 在 `scope == null` 时**直接返回 ALLOW**
            // ⇒ 若作用域拿不到，下面"200 次全 ALLOW"测到的就是"没有作用域"而不是"没有上限"。
            // ② 号用例（显式上限 ⇒ 只有 1 次 ALLOW）反证了强制路径是活的，这里再把前提显式钉住。
            check("前提：作用域存在", scope != null, "scopeOf(bot)=" + scope);
            // ① 默认：不限（连做 200 次破坏，一次都不许被拒）
            com.dddgn.alice.action.WriteBudget.closeScope(scope);
            int allowed = 0;
            for (int index = 0; index < 200; index++) {
                if (com.dddgn.alice.action.WriteBudget.consumeBreak(bot, level, pos, null)
                        == com.dddgn.alice.action.WriteBudget.Verdict.ALLOW) {
                    allowed++;
                }
            }
            check("默认不限①", allowed == 200,
                    "无显式上限时破坏 200 次不得被拒（实测 ALLOW=" + allowed
                            + "；`D-372`：世界修改放开，闸门改时间预算防空转）");

            // ② 显式上限仍然强制
            com.dddgn.alice.action.WriteBudget.closeScope(scope);
            com.dddgn.alice.action.WriteBudget.setCaps(scope,
                    new com.dddgn.alice.action.WriteBudget.Caps(1, 0));
            int allowedExplicit = 0;
            for (int index = 0; index < 5; index++) {
                if (com.dddgn.alice.action.WriteBudget.consumeBreak(bot, level, pos, null)
                        == com.dddgn.alice.action.WriteBudget.Verdict.ALLOW) {
                    allowedExplicit++;
                }
            }
            check("显式上限②", allowedExplicit == 1,
                    "`setCaps` 装订的上限仍强制（实测 ALLOW=" + allowedExplicit + " == 1）");

            // ③ D-241 逃生准备金（capForEscape）在放开默认上限后仍强制
            com.dddgn.alice.action.WriteBudget.closeScope(scope);
            com.dddgn.alice.action.WriteBudget.capForEscape(scope, 1, 1);
            int allowedEscape = 0;
            for (int index = 0; index < 5; index++) {
                if (com.dddgn.alice.action.WriteBudget.consumeBreak(bot, level, pos, null)
                        == com.dddgn.alice.action.WriteBudget.Verdict.ALLOW) {
                    allowedEscape++;
                }
            }
            check("显式上限③", allowedEscape == 1,
                    "`capForEscape`（`D-241` 逃生准备金）仍强制（实测 ALLOW=" + allowedEscape + " == 1）");
        } finally {
            com.dddgn.alice.action.WriteBudget.closeScope(scope);
        }
    }

    private void runChecks() {
        runDefaultCapChecks();
        // D-241：**纯通行名单不许漂移** —— `PathRequest.pureTraversal()` 是字面集合，
        // `MovementType.changesWorld()` 是规划期唯一口径；两者必须互为补集（写错就红，不靠 review 眼睛）。
        java.util.Set<com.dddgn.alice.pathing.core.MovementType> pureTraversalSet = com.dddgn.alice.pathing.core.search
                .PathRequest.of("probe", net.minecraft.core.BlockPos.ZERO,
                        net.minecraft.core.BlockPos.ZERO, "probe").pureTraversal()
                .allowedMovementTypes();
        java.util.Set<com.dddgn.alice.pathing.core.MovementType> pureExpected = java.util.Arrays
                .stream(com.dddgn.alice.pathing.core.MovementType.values())
                .filter(type -> !type.changesWorld())
                .collect(java.util.stream.Collectors.toSet());
        check("纯通行名单与 changesWorld() 互为补集（不许漂移）", pureTraversalSet.equals(pureExpected),
                "pure=" + pureTraversalSet + " expected=" + pureExpected);

        // A 表是全的
        List<String> structural = new ArrayList<>();
        for (String problem : WritePolicyMatrix.audit()) {
            // 运行期观察（未登记 requester / 未登记组合）单独算 F，不混进"结构"这一条
            if (problem.startsWith("未登记")) {
                continue;
            }
            structural.add(problem);
        }
        check("table_total", structural.isEmpty(), "problems=" + structural);
        check("table_shape",
                WritePolicyMatrix.ROWS.size()
                        == WritePolicyMatrix.Zone.values().length * WritePolicyMatrix.Task.values().length,
                "rows=" + WritePolicyMatrix.ROWS.size() + "（期望 "
                        + WritePolicyMatrix.Zone.values().length * WritePolicyMatrix.Task.values().length + "）");

        // B 闸门是活的（负例 + 正例）
        BlockPos from = bot.blockPosition();
        PathRequest illegal = PathRequest.withWorldModification(
                bot.getUUID().toString(), from, from, "walk-to");
        String deniedCode = "-";
        boolean denied = false;
        try {
            WritePolicyMatrix.requireMovementsGranted(bot.serverLevel(), illegal);
        } catch (WritePolicyMatrix.Violation violation) {
            denied = true;
            deniedCode = violation.code();
        }
        check("guard_is_live", denied && "WRITE_POLICY_MOVEMENT_DENIED".equals(deniedCode),
                "walk-to + withWorldModification ⇒ 期望拒绝 WRITE_POLICY_MOVEMENT_DENIED；实际 "
                        + (denied ? "拒绝（" + deniedCode + "）" : "**放行**（闸门恒真！）"));

        boolean legalPassed = true;
        String legalDetail = "-";
        try {
            WritePolicyMatrix.requireMovementsGranted(bot.serverLevel(),
                    PathRequest.of(bot.getUUID().toString(), from, from, "walk-to"));
            // 夹具（DIAGNOSTIC）**必须**拿得到会写世界的集合，否则它没法构造场景
            WritePolicyMatrix.requireMovementsGranted(bot.serverLevel(),
                    PathRequest.withWorldModification(bot.getUUID().toString(), from, from,
                            "pathing-regression"));
        } catch (WritePolicyMatrix.Violation violation) {
            legalPassed = false;
            legalDetail = violation.getMessage();
        }
        // B2 接线后的**规划器层**：越权请求必须被转成 ERROR plan（而不是抛异常打断 tick）
        int ledgerBefore = com.dddgn.alice.ledger.WorldModLedger.size(bot.getServer());
        com.dddgn.alice.pathing.core.search.PathPlan refused =
                new com.dddgn.alice.pathing.core.search.CorePathPlanner()
                        .plan(bot, bot.serverLevel(), illegal);
        boolean plannerRefused = refused.status()
                == com.dddgn.alice.pathing.core.search.PlanningStatus.ERROR
                && refused.diagnostics().contains("WRITE_POLICY_MOVEMENT_DENIED")
                && refused.movements().isEmpty();
        check("planner_refuses_and_reports", plannerRefused,
                "期望 status=ERROR + diagnostics 含 WRITE_POLICY_MOVEMENT_DENIED + 无 movements；实际 status="
                        + refused.status() + " diagnostics=" + refused.diagnostics()
                        + " movements=" + refused.movements().size());

        check("guard_does_not_overreach", legalPassed,
                "walk-to + of / pathing-regression + withWorldModification ⇒ 期望放行；实际 "
                        + (legalPassed ? "放行" : "**被拒**：" + legalDetail));

        // C 授权集合语义——**逐条对着 `PathRequest` 各工厂的 javadoc 契约断言**，不是对着直觉：
        //   of              = 通行四件套（**不含 FALL**、不含任何写原语）
        //   pureTraversal   = of + FALL（严格超集；同样不含写原语）
        //   miningApproach  = 通行 + BREAK_AND_TRAVERSE/BREAK_AND_ENTER/PLACE_STEP_AND_TRAVERSE，
        //                     **显式禁用 PILLAR/FALL/DOWNWARD**（D-067 ㉘）
        //   scaffoldRemoval = 通行 + FALL + DOWNWARD（**只拆不建**：不含 PILLAR/PLACE_STEP_AND_TRAVERSE，
        //                     也不含 BREAK_*——拆脚手架不许沿途挖穿地形）
        //
        // 2026-09-14 实测修正：上一版断言 `scaffoldRemoval ∩ 写原语 = ∅`，**同一条件列表里**又要求
        // `removal.contains(DOWNWARD)`——而 DOWNWARD 本身就在 `writePrimitives()` 里 ⇒ **断言集自相矛盾、
        // 恒 FAIL、零信号**（它红了整整一轮，却没说明任何事实）。正解：把"写原语"拆成**放置**与**挖穿**
        // 两类，分别断"回收阶段不许再建 / 不许挖穿"，并把详情改成**逐集合打印**（旧写法打印的是
        // `A||B||C` 的聚合布尔，红了也定位不到是哪一个）。
        Set<MovementType> of = WritePolicyMatrix.MovementGrant.OF.types();
        Set<MovementType> pure = WritePolicyMatrix.MovementGrant.PURE_TRAVERSAL.types();
        Set<MovementType> removal = WritePolicyMatrix.MovementGrant.SCAFFOLD_REMOVAL.types();
        Set<MovementType> mining = WritePolicyMatrix.MovementGrant.MINING_APPROACH.types();
        Set<MovementType> writers = writePrimitives();
        Set<MovementType> ofWriters = intersect(of, writers);
        Set<MovementType> pureWriters = intersect(pure, writers);
        Set<MovementType> removalPlacement = intersect(removal, PLACEMENT_PRIMITIVES);
        Set<MovementType> removalTunnel = intersect(removal, TUNNEL_PRIMITIVES);
        // D-366b（2026-09-20 用户临时让步）：挖矿信封**放开** PILLAR/FALL/DOWNWARD ⇒ 断言从
        // "必须为空"翻成"必须包含"，让步范围与回收条件见 `docs/AI_DECISIONS.md` `D-366`。
        boolean miningLifted = mining.containsAll(MINING_FORBIDDEN);
        boolean removalRemoves = removal.contains(MovementType.DOWNWARD)
                && removal.contains(MovementType.FALL);
        boolean miningBreaks = mining.contains(MovementType.BREAK_AND_TRAVERSE)
                && mining.contains(MovementType.BREAK_AND_ENTER)
                && mining.contains(MovementType.PLACE_STEP_AND_TRAVERSE);
        boolean strictSubset = pure.containsAll(of) && !of.containsAll(pure);
        boolean semantic = ofWriters.isEmpty()
                && pureWriters.isEmpty()
                && strictSubset
                && removalPlacement.isEmpty()
                && removalTunnel.isEmpty()
                && removalRemoves
                && miningLifted
                && miningBreaks;
        check("grants_semantics", semantic,
                "of∩写原语=" + ofWriters + " pureTraversal∩写原语=" + pureWriters
                        + " of⊊pureTraversal=" + strictSubset
                        + " scaffoldRemoval∩放置=" + removalPlacement
                        + " scaffoldRemoval∩挖穿=" + removalTunnel
                        + " scaffoldRemoval含DOWNWARD+FALL=" + removalRemoves
                        + " miningApproach含[PILLAR,FALL,DOWNWARD]（D-366b 放开）=" + miningLifted
                        + " miningApproach含BREAK_*/PLACE_STEP=" + miningBreaks);

        // D 登记表
        Map<String, WritePolicyMatrix.Task> expected = new LinkedHashMap<>();
        expected.put("walk-to", WritePolicyMatrix.Task.TRAVERSAL);
        expected.put("walk-to:attempt3", WritePolicyMatrix.Task.TRAVERSAL);
        expected.put("mine-runner", WritePolicyMatrix.Task.MINING);
        expected.put("mining-planner", WritePolicyMatrix.Task.MINING);
        expected.put("collect-drops", WritePolicyMatrix.Task.GATHERING);
        expected.put("lumber-plan", WritePolicyMatrix.Task.LUMBER);
        expected.put("craft-station", WritePolicyMatrix.Task.CRAFT);
        expected.put("transfer", WritePolicyMatrix.Task.CONTAINER);
        expected.put("station-provision", WritePolicyMatrix.Task.CONTAINER);
        expected.put("road-build", WritePolicyMatrix.Task.BUILD);
        expected.put("restore", WritePolicyMatrix.Task.RESTORE);
        expected.put("command:diagnose", WritePolicyMatrix.Task.MANUAL);
        expected.put("pathing-regression", WritePolicyMatrix.Task.DIAGNOSTIC);
        expected.put("ClearRetryCheck", WritePolicyMatrix.Task.DIAGNOSTIC);
        // 动态/形态类 requester（2026-09-14 接线时实测会掉进 UNREGISTERED 的那几个）
        expected.put("mine", WritePolicyMatrix.Task.MINING);
        expected.put("region_lumber", WritePolicyMatrix.Task.LUMBER);
        expected.put("PlaceTask", WritePolicyMatrix.Task.TRAVERSAL);
        expected.put("write-budget-check", WritePolicyMatrix.Task.DIAGNOSTIC);
        expected.put("scaffold-lifecycle", WritePolicyMatrix.Task.DIAGNOSTIC);
        expected.put("partial_ample", WritePolicyMatrix.Task.DIAGNOSTIC);
        expected.put("ToolMaintenance", WritePolicyMatrix.Task.CRAFT);
        expected.put("", WritePolicyMatrix.Task.UNREGISTERED);
        expected.put("unknown", WritePolicyMatrix.Task.UNREGISTERED);
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, WritePolicyMatrix.Task> entry : expected.entrySet()) {
            WritePolicyMatrix.Task actual = WritePolicyMatrix.taskOf(entry.getKey());
            if (actual != entry.getValue()) {
                mismatched.add("'" + entry.getKey() + "'→" + actual + "（期望 " + entry.getValue() + "）");
            }
        }
        check("requester_registry", mismatched.isEmpty(), "mismatched=" + mismatched);

        // E 两区等价
        check("zone_equiv", WritePolicyMatrix.zoneDiffCount() == 0,
                "zoneDiff=" + WritePolicyMatrix.zoneDiffCount()
                        + "（EXTERNAL 与 WORKSPACE 今天必须逐条相同 ⇒ 不改变默认行为）");

        // F 义务解析
        boolean obligationOk =
                WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.TRAVERSAL, WriteReason.STEP_PLACEMENT)
                        == WritePolicyMatrix.Obligation.TEMP
                        && WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.LUMBER, WriteReason.REGION_REPLANT)
                        == WritePolicyMatrix.Obligation.KEEP
                        && WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.BUILD, WriteReason.BULK_EDIT)
                        == WritePolicyMatrix.Obligation.KEEP
                        && WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.CRAFT, WriteReason.CRAFT_STATION_PLACE)
                        == WritePolicyMatrix.Obligation.TEMP;
        check("obligation", obligationOk,
                "traversal+step=" + WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.TRAVERSAL, WriteReason.STEP_PLACEMENT)
                        + " lumber+replant=" + WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.LUMBER, WriteReason.REGION_REPLANT)
                        + " build+bulk=" + WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.BUILD, WriteReason.BULK_EDIT)
                        + " craft+station=" + WritePolicyMatrix.obligation(WritePolicyMatrix.Zone.EXTERNAL,
                        WritePolicyMatrix.Task.CRAFT, WriteReason.CRAFT_STATION_PLACE));

        // B3 容器写入闸门（R1 收口，2026-09-14）：**已登记但未声明 ⇒ 拒**；登记缺口 ⇒ 不拒（只留痕）。
        // 层的归属：判定（B3）走**纯函数**（零副作用）；端到端（B4）走**真函数**（必须真的被拒），
        // 但做**样本快照/还原**——否则本自检会把电池样本弄脏（G 段要求 未登记=0/未声明=0）。
        java.util.UUID owner = bot.getUUID();
        com.dddgn.alice.action.WriteGrant undeclaredGrant =
                com.dddgn.alice.action.WriteGrant.of("walk-to", WriteReason.CONTAINER_TRANSFER);
        com.dddgn.alice.action.WriteGrant declaredGrant =
                com.dddgn.alice.action.WriteGrant.of("transfer", WriteReason.CONTAINER_TRANSFER);
        com.dddgn.alice.action.WriteGrant unknownGrant =
                com.dddgn.alice.action.WriteGrant.of("no-such-requester-xyz", WriteReason.CONTAINER_TRANSFER);
        WritePolicyMatrix.Decision decisionUndeclared =
                WritePolicyMatrix.decideContainerWrite(bot.serverLevel(), owner, from, undeclaredGrant);
        WritePolicyMatrix.Decision decisionDeclared =
                WritePolicyMatrix.decideContainerWrite(bot.serverLevel(), owner, from, declaredGrant);
        WritePolicyMatrix.Decision decisionUnknown =
                WritePolicyMatrix.decideContainerWrite(bot.serverLevel(), owner, from, unknownGrant);
        check("container_gate_live",
                decisionUndeclared == WritePolicyMatrix.Decision.UNDECLARED_REASON
                        && decisionDeclared == WritePolicyMatrix.Decision.DECLARED
                        && decisionUnknown == WritePolicyMatrix.Decision.UNREGISTERED,
                "walk-to+CONTAINER_TRANSFER=" + decisionUndeclared + "（期望 UNDECLARED_REASON：纯通行任务不许写容器）"
                        + " transfer+CONTAINER_TRANSFER=" + decisionDeclared + "（期望 DECLARED）"
                        + " 未登记 requester=" + decisionUnknown + "（期望 UNREGISTERED：登记缺口不拒）");

        boolean armedByDefault = WritePolicyMatrix.containerRefusalArmed();
        WritePolicyMatrix.Observations sample = WritePolicyMatrix.snapshotObservations();
        boolean armedDenies;
        boolean observeAllows;
        try {
            WritePolicyMatrix.setContainerRefusalArmed(false);
            observeAllows = !WritePolicyMatrix.refuses(WritePolicyMatrix.Decision.UNDECLARED_REASON);
            WritePolicyMatrix.setContainerRefusalArmed(true);
            armedDenies = com.dddgn.alice.action.WriteBudget.consumeContainerWrite(bot, from, undeclaredGrant)
                    == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED;
        } finally {
            WritePolicyMatrix.setContainerRefusalArmed(armedByDefault);
            WritePolicyMatrix.restoreObservations(sample);
        }
        check("container_gate_armed",
                armedByDefault && armedDenies && observeAllows
                        && !WritePolicyMatrix.refuses(WritePolicyMatrix.Decision.DECLARED)
                        && !WritePolicyMatrix.refuses(WritePolicyMatrix.Decision.UNREGISTERED),
                "默认武装=" + armedByDefault + " 武装时未声明被**真拒**=" + armedDenies
                        + " 观察模式放行=" + observeAllows + "（开关不是死值）"
                        + " 已声明/未登记在武装下也不拒="
                        + (!WritePolicyMatrix.refuses(WritePolicyMatrix.Decision.DECLARED)
                        && !WritePolicyMatrix.refuses(WritePolicyMatrix.Decision.UNREGISTERED)));

        // G 运行期留痕（本次电池之前的所有写入都在样本里）
        Map<String, Integer> unregistered = WritePolicyMatrix.unregisteredSeen();
        Map<String, Integer> undeclared = WritePolicyMatrix.undeclaredSeen();
        check("no_unregistered_requester", unregistered.isEmpty(),
                "未登记 requester 写入/规划=" + unregistered
                        + "（D-207 ①：UNKNOWN requester **记为错误**；补进 PREFIX_RULES）");
        check("no_undeclared_combination", undeclared.isEmpty(),
                "未登记 (行,理由)=" + undeclared + "（表与调用点不一致 ⇒ 补行或改调用点）");

        // H 零写入自证：本步不得向账本新增条目（夹具自己不能弄脏世界；与 D-207 驳回项⑤同口径）
        int ledgerAfter = com.dddgn.alice.ledger.WorldModLedger.size(bot.getServer());
        check("self_write_free", ledgerAfter == ledgerBefore,
                "账本 pending " + ledgerBefore + " → " + ledgerAfter + "（本自检必须零写入）");

        String summary = "table_total=" + verdict("table_total")
                + " table_shape=" + verdict("table_shape")
                + " guard_is_live=" + verdict("guard_is_live")
                + " guard_does_not_overreach=" + verdict("guard_does_not_overreach")
                + " planner_refuses_and_reports=" + verdict("planner_refuses_and_reports")
                + " self_write_free=" + verdict("self_write_free")
                + " grants_semantics=" + verdict("grants_semantics")
                + " requester_registry=" + verdict("requester_registry")
                + " zone_equiv=" + verdict("zone_equiv")
                + " obligation=" + verdict("obligation")
                + " container_gate_live=" + verdict("container_gate_live")
                + " container_gate_armed=" + verdict("container_gate_armed")
                + " unregistered=" + unregistered.size()
                + " undeclared=" + undeclared.size()
                + " containerGate=" + (WritePolicyMatrix.containerRefusalArmed() ? "armed" : "observe")
                + " container_checks=" + WritePolicyMatrix.containerChecks()
                + " container_refused=" + WritePolicyMatrix.containerRefused()
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        notes.add(WritePolicyMatrix.describe());
        BotLog.info("[WritePolicy] SUMMARY {} {}", summary, String.join(" ", notes));
        BotLog.info("[WritePolicy] 表 {}", WritePolicyMatrix.describe());
        for (WritePolicyMatrix.Row row : WritePolicyMatrix.ROWS) {
            BotLog.info("[WritePolicy] row={} zone={} task={} obligation={} movements={} reasons={}",
                    row.id(), row.zone(), row.task(), row.obligation(),
                    row.movements(), row.reasons() == null ? 0 : row.reasons().size());
        }
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[WritePolicy] " + summary));
        }
    }

    /** 会写世界的 Movement 原语（与 `PathRequest.pureTraversal()` 的注释同口径）。 */
    private static Set<MovementType> writePrimitives() {
        return Set.of(MovementType.PILLAR, MovementType.DOWNWARD, MovementType.BREAK_AND_TRAVERSE,
                MovementType.BREAK_AND_ENTER, MovementType.PLACE_STEP_AND_TRAVERSE);
    }

    /** 写原语里的**放置**类（"不许再建"要断的那一半）。 */
    private static final Set<MovementType> PLACEMENT_PRIMITIVES =
            Set.of(MovementType.PILLAR, MovementType.PLACE_STEP_AND_TRAVERSE);

    /** 写原语里的**挖穿地形**类（"不许沿途挖穿"要断的那一半）。 */
    private static final Set<MovementType> TUNNEL_PRIMITIVES =
            Set.of(MovementType.BREAK_AND_TRAVERSE, MovementType.BREAK_AND_ENTER);

    /** `miningApproach` **曾经**显式禁用的三件（D-067 ㉘）；`D-366b`（2026-09-20）起临时放开。 */
    private static final Set<MovementType> MINING_FORBIDDEN =
            Set.of(MovementType.PILLAR, MovementType.FALL, MovementType.DOWNWARD);

    private static Set<MovementType> intersect(Set<MovementType> left, Set<MovementType> right) {
        Set<MovementType> result = new java.util.TreeSet<>(left);
        result.retainAll(right);
        return result;
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[WritePolicy] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[WritePolicy] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
