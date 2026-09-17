package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.IntrinsicReversibility;
import com.dddgn.alice.pathing.core.MovementCapabilities;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.RecoverabilityAssessment;
import com.dddgn.alice.pathing.core.RecoverabilityEvaluator;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import com.dddgn.alice.pathing.core.RecoverabilityReport;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **可回收性自检**（基-1 / P0-B）：验"可回收性真的被算出来、且那个校验不是恒假"。
 *
 * <p>背景（`RISK_SYSTEM_ISSUE_LIST.md` §2 P0-B）：`RecoverabilityLevel` 曾是**死抽象** ——
 * 生产路径两边都写死 `LOCAL_STEP` ⇒ `MovementSpec` 里的校验 `evaluated < required` 恒假、从未生效。
 *
 * <p>四条例（全部纯计算，不动世界、不动 bot）：
 * <ol>
 *   <li>A 规则表**不是常量**：全体 Movement 类型评估出的等级 ≥ 2 种；</li>
 *   <li>B 逐类型抽查：FALL=PATH_REVERSIBLE（依据=落点过了 PILLAR 返回守卫）、PILLAR/BREAK/DOWNWARD=LOCAL_STEP；</li>
 *   <li>C **校验是活的**（负例）：构造 `required=PATH_REVERSIBLE` 而 `evaluated=LOCAL_STEP` 的 spec。
 *       **必须抛 `IllegalArgumentException`** —— 否则说明校验仍然是恒假（P0-B 复发）；</li>
 *   <li>D **逐边事实真的改变结论**：同一条 FALL 边 —— **不带** `fall_return_verified` 事实必须被**拒绝**
 *       （抛异常，这是"可回收性改变准入"的直接证据）；**带上**事实必须通过且评估为 PATH_REVERSIBLE；</li>
 *   <li>E 策略表：FALL 要求 PATH_REVERSIBLE、其余维持 LOCAL_STEP（抬 required 的前提是评估器能产出真实等级）。</li>
 * </ol>
 */
public class RecoverabilityCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int ticks;
    private boolean done;

    public RecoverabilityCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "RecoverabilityCheck";
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
            // 留 1 tick 让聊天/日志刷出；**且终态传播 self-check 结论**（2026-09-14 修正：
            // 原写死 DONE ⇒ 内部 FAIL 对电池不可见。全仓唯二两处，另一处是 WritePolicyCheckTask。）
            return done ? (failures.isEmpty() ? Status.DONE : Status.FAILED) : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
        RecoverabilityReport.reset();

        // A 规则表不是常量
        int distinct = (int) java.util.Arrays.stream(MovementType.values())
                .map(RecoverabilityEvaluator::levelOf).distinct().count();
        check("nondeterministic_table", distinct >= 2,
                "distinctLevels=" + distinct + "（必须 ≥2，否则仍等于常量）");

        // B 逐类型抽查（依据也要对：等级对但依据错说明规则表被改坏了）
        // FALL 是**条件性**的：带事实 = PATH_REVERSIBLE，不带事实 = 保守 LOCAL_STEP
        expect(MovementType.FALL, com.dddgn.alice.pathing.core.RecoverabilityFacts.FALL_RETURN_VERIFIED,
                RecoverabilityLevel.PATH_REVERSIBLE, RecoverabilityEvaluator.BASIS_FALL_GUARD);
        expect(MovementType.FALL, com.dddgn.alice.pathing.core.RecoverabilityFacts.NONE,
                RecoverabilityLevel.LOCAL_STEP, RecoverabilityEvaluator.BASIS_FALL_UNVERIFIED);
        expect(MovementType.TRAVERSE, RecoverabilityLevel.PATH_REVERSIBLE,
                RecoverabilityEvaluator.BASIS_GEOMETRY_UNCHANGED);
        expect(MovementType.PILLAR, RecoverabilityLevel.LOCAL_STEP,
                RecoverabilityEvaluator.BASIS_ASCENT_REVERSE_UNVERIFIED);
        expect(MovementType.BREAK_AND_ENTER, RecoverabilityLevel.LOCAL_STEP,
                RecoverabilityEvaluator.BASIS_WORLD_MODIFIED);
        expect(MovementType.DOWNWARD, RecoverabilityLevel.LOCAL_STEP,
                RecoverabilityEvaluator.BASIS_WORLD_MODIFIED);

        // C 校验是活的：required > evaluated 必须抛
        BlockPos from = bot.blockPosition();
        BlockPos to = from.east();
        boolean threw = false;
        String threwMessage = "-";
        try {
            violatingSpec(from, to);
        } catch (IllegalArgumentException exception) {
            threw = true;
            threwMessage = exception.getMessage();
        }
        check("guard_is_live", threw,
                "required=PATH_REVERSIBLE + evaluated=LOCAL_STEP ⇒ 期望抛异常；实际 "
                        + (threw ? "抛出（" + threwMessage + "）" : "**没抛**（校验仍是恒假！）"));

        // D **逐边事实改变准入结论**（第二步的核心）
        // D1 带事实的 FALL ⇒ 必须通过，且评估为 PATH_REVERSIBLE
        PlannedMovement guardedFall = new PlannedMovement(MovementType.FALL, from,
                from.offset(1, -2, 0), 4.0D, RecoverabilityLevel.PATH_REVERSIBLE,
                com.dddgn.alice.pathing.core.RecoverabilityFacts.FALL_RETURN_VERIFIED);
        MovementSpec guardedSpec = PlannedMovementSpecs.toSpec(guardedFall, List.of("recoverability_check"));
        check("fall_with_fact_accepted",
                guardedSpec.evaluatedRecoverability() == RecoverabilityLevel.PATH_REVERSIBLE,
                "带 fall_return_verified ⇒ evaluated=" + guardedSpec.evaluatedRecoverability()
                        + "（期望 PATH_REVERSIBLE）");
        // D2 **不带事实的 FALL ⇒ 必须被拒**（策略表要求 PATH_REVERSIBLE，评估只剩 LOCAL_STEP）
        boolean refused = false;
        String refusedMessage = "-";
        try {
            PlannedMovement unguarded = new PlannedMovement(MovementType.FALL, from,
                    from.offset(1, -2, 0), 4.0D, RecoverabilityLevel.PATH_REVERSIBLE);
            PlannedMovementSpecs.toSpec(unguarded, List.of("recoverability_check"));
        } catch (IllegalArgumentException exception) {
            refused = true;
            refusedMessage = exception.getMessage();
        }
        check("fall_without_fact_refused", refused,
                "不带事实的 FALL ⇒ 期望被拒；实际 " + (refused ? "拒绝（" + refusedMessage + "）"
                        : "**通过**（可回收性仍未改变准入！）"));

        // E 策略表本身
        check("policy_table",
                com.dddgn.alice.pathing.core.RecoverabilityPolicy
                        .requiredFor(MovementType.FALL) == RecoverabilityLevel.PATH_REVERSIBLE
                        && com.dddgn.alice.pathing.core.RecoverabilityPolicy
                        .requiredFor(MovementType.TRAVERSE) == RecoverabilityLevel.LOCAL_STEP,
                com.dddgn.alice.pathing.core.RecoverabilityPolicy.describe());

        // 统计：把"执行期真实统计"一并打出来（无执行则显示 movements=0，不算失败）
        notes.add("report[" + RecoverabilityReport.describe() + "]");
        notes.add("residues[" + RecoverabilityReport.describeResidues() + "]");

        String summary = "table_nondeterministic=" + verdict("nondeterministic_table")
                + " per_type=" + verdict("per_type")
                + " guard_is_live=" + verdict("guard_is_live")
                + " fall_with_fact_accepted=" + verdict("fall_with_fact_accepted")
                + " fall_without_fact_refused=" + verdict("fall_without_fact_refused")
                + " policy_table=" + verdict("policy_table")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[Recover] SUMMARY {} {}", summary, String.join(" ", notes));
        BotLog.info("[Recover] 规则表 {}", RecoverabilityEvaluator.table());
        BotLog.info("[Recover] 策略表 {}", com.dddgn.alice.pathing.core.RecoverabilityPolicy.describe());
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[Recover] " + summary));
        }
        RecoverabilityReport.reset();
    }

    /** 构造一条**故意违规**的 spec：required=PATH_REVERSIBLE 而 evaluated=LOCAL_STEP。 */
    private static MovementSpec violatingSpec(BlockPos from, BlockPos to) {
        return new MovementSpec(MovementType.TRAVERSE, from, to, 1.0D,
                MovementCapabilities.pureTraversal(RecoverabilityLevel.PATH_REVERSIBLE,
                        IntrinsicReversibility.REVERSIBLE),
                List.of(), List.of(), List.of(),
                RecoverabilityLevel.LOCAL_STEP, PlannedMovementSpecs.factoryKey(MovementType.TRAVERSE));
    }

    private void expect(MovementType type, com.dddgn.alice.pathing.core.RecoverabilityFacts facts,
                        RecoverabilityLevel level, String basis) {
        RecoverabilityAssessment assessment = RecoverabilityEvaluator.evaluate(type, facts);
        check("per_type", assessment.level() == level && assessment.basis().equals(basis),
                type + "+" + (facts.fallReturnVerified() ? "fact" : "nofact") + "=" + assessment.describe()
                        + "（期望 " + level + "/" + basis + "）");
    }

    private void expect(MovementType type, RecoverabilityLevel level, String basis) {
        RecoverabilityAssessment assessment = RecoverabilityEvaluator.evaluate(type);
        check("per_type", assessment.level() == level && assessment.basis().equals(basis),
                type + "=" + assessment.describe() + "（期望 " + level + "/" + basis + "）");
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[Recover] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[Recover] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
