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
 * <p>六条例（全部纯计算 + 一次失败规划尝试，不动世界）：
 * <ol>
 *   <li>A <b>表是全的</b>：{@code Zone × Task} 全枚举、每个 {@code WriteReason} 都有行登记、
 *       显式义务与该行理由不矛盾；</li>
 *   <li>B <b>闸门是活的</b>（负例）：纯通行任务（{@code walk-to}）要求
 *       {@code withWorldModification} 的移动集 ⇒ **必须抛 {@code WRITE_POLICY_MOVEMENT_DENIED}**；
 *       同一集合在 {@code DIAGNOSTIC}（夹具）下必须通过；</li>
 *   <li>C <b>授权集合语义没被改坏</b>：{@code of} 不含任何写世界原语、{@code scaffoldRemoval}
 *       只拆不建、{@code miningApproach} 不含 {@code PILLAR/FALL/DOWNWARD}（D-067 ㉘）；</li>
 *   <li>D <b>登记表能认出各任务</b>：12 个真实 requester 归到预期类别，
 *       含 {@code :attemptN} 派生形态；空/{@code unknown} ⇒ {@code UNREGISTERED}（**记为错误**）；</li>
 *   <li>E <b>两区今天等价</b>（{@code zoneDiff=0}）：这是"R1 不改变默认行为"的**可执行**证据，
 *       一旦有人让两区不同，这里会红并逼出决策记录；</li>
 *   <li>F <b>义务解析与今天的口径一致</b>：{@code REASON_DEFAULT} 行 == {@code WriteReason.temporary()}，
 *       显式行（{@code BUILD}/{@code MANUAL}=KEEP、{@code TRAVERSAL}=TEMP）如声明。</li>
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
            return done ? Status.DONE : Status.RUNNING;   // 留 1 tick 让聊天/日志刷出
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
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
        check("guard_does_not_overreach", legalPassed,
                "walk-to + of / pathing-regression + withWorldModification ⇒ 期望放行；实际 "
                        + (legalPassed ? "放行" : "**被拒**：" + legalDetail));

        // C 授权集合语义
        Set<MovementType> of = WritePolicyMatrix.MovementGrant.OF.types();
        Set<MovementType> pure = WritePolicyMatrix.MovementGrant.PURE_TRAVERSAL.types();
        Set<MovementType> removal = WritePolicyMatrix.MovementGrant.SCAFFOLD_REMOVAL.types();
        Set<MovementType> mining = WritePolicyMatrix.MovementGrant.MINING_APPROACH.types();
        Set<MovementType> writers = writePrimitives();
        Set<MovementType> ofWriters = new java.util.LinkedHashSet<>(of);
        ofWriters.retainAll(writers);
        Set<MovementType> removalWriters = new java.util.LinkedHashSet<>(removal);
        removalWriters.retainAll(writers);
        boolean semantic = ofWriters.isEmpty()
                && removalWriters.isEmpty()
                && removal.contains(MovementType.DOWNWARD)
                && of.containsAll(pure) == false && pure.containsAll(of)
                && !mining.contains(MovementType.PILLAR)
                && !mining.contains(MovementType.FALL)
                && !mining.contains(MovementType.DOWNWARD);
        check("grants_semantics", semantic,
                "of∩写原语=" + ofWriters + " scaffoldRemoval∩写原语=" + removalWriters
                        + " of⊆pureTraversal=" + pure.containsAll(of)
                        + " miningApproach含PILLAR/FALL/DOWNWARD="
                        + (mining.contains(MovementType.PILLAR) || mining.contains(MovementType.FALL)
                        || mining.contains(MovementType.DOWNWARD)));

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

        // G 运行期留痕（本次电池之前的所有写入都在样本里）
        Map<String, Integer> unregistered = WritePolicyMatrix.unregisteredSeen();
        Map<String, Integer> undeclared = WritePolicyMatrix.undeclaredSeen();
        check("no_unregistered_requester", unregistered.isEmpty(),
                "未登记 requester 写入/规划=" + unregistered
                        + "（D-207 ①：UNKNOWN requester **记为错误**；补进 PREFIX_RULES）");
        check("no_undeclared_combination", undeclared.isEmpty(),
                "未登记 (行,理由)=" + undeclared + "（表与调用点不一致 ⇒ 补行或改调用点）");

        String summary = "table_total=" + verdict("table_total")
                + " table_shape=" + verdict("table_shape")
                + " guard_is_live=" + verdict("guard_is_live")
                + " guard_does_not_overreach=" + verdict("guard_does_not_overreach")
                + " grants_semantics=" + verdict("grants_semantics")
                + " requester_registry=" + verdict("requester_registry")
                + " zone_equiv=" + verdict("zone_equiv")
                + " obligation=" + verdict("obligation")
                + " unregistered=" + unregistered.size()
                + " undeclared=" + undeclared.size()
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
