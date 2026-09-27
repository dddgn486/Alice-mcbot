package com.dddgn.alice.task.mining;

import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ChainMining;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.reach.MiningPlan;
import com.dddgn.alice.reach.MiningTuning;
import com.dddgn.alice.write.WriteGrant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **单格挖掘原语**（`step 5a`，`D-466`）：**一个目标方块的一次作业** —— 计划段 → 走位/破坏 → 单格结论。
 *
 * <p>三个入口，恰好对应三段职责：
 * <ol>
 *   <li>{@link #plan()}：跑一次站位规划（含"这一格能不能连锁"的判定），把结论**返回**给调用方；</li>
 *   <li>{@link #startExecution(boolean)}：按已算好的计划造一个 {@link MineBlockRunner}
 *       （`walkOnly` 落到它**现成的**那个布尔参数上，`D-466` 第 6 拍）；</li>
 *   <li>{@link #tick()}：推进执行，直到给出**单格结论**（{@link Conclusion}）。</li>
 * </ol>
 *
 * <p>⚠️ 本类**没有**相位枚举、**没有**类内默认额度常量、**不造**任何子任务、**不做**收集/建拆/连锁调度
 * —— 那些是编排器（{@link com.dddgn.alice.task.MineTask}）的事。五条判据（相位机 0 引用 / 成功出口恰好 1 /
 * 无 `new MineTask(` / 额度只来自构造参数 / 额度消费点恰好 1）由 `tools/check-task-orchestration-split.py`
 * 静态断言。
 *
 * <p>⚠️ **两个终态闩锁各自持有**（`D-466` §五）：编排器一个（逐字不动），本类一个 ——
 * 「单一成功判据」要求本类也只有一个终态：**返回过终态之后，后续 `tick()` 必须幂等回放同一结论**，
 * 既不崩（`D-175` 的教训：NPE 打死过服务端 tick 循环）也不改口（`D-178`：失败被下一 tick 记成通过）。
 *
 * <p>⚠️ 本类**不认识** `MineTask`：它不引用编排器的任何类型，也不知道自己被谁用。
 */
public final class MineStep {

    /** 单格结论的三种形态（`D-466` §六）。 */
    public enum Outcome { RUNNING, DONE, FAILED }

    /** 失败发生在哪一段 —— 「单一失败归因」的载体（`D-466` §六）。 */
    public enum Leg { PLANNING, EXECUTION }

    /**
     * **单格结论**：{@link #tick()} 与 {@link #plan()} 的唯一产物。
     *
     * <p>编排器拿到的永远是「一个形态 + 一个失败理由 + 一个腿标记（执行失败时还带
     * {@link MineBlockRunner.FailureReport}）」—— 判据 `D-466` §十 要求编排器**不需要**回头读本类的
     * 内部字段就能做全部分叉，这个记录就是那条边界的物理形态。
     */
    public record Conclusion(Outcome outcome, Leg leg, String reason,
                             MineBlockRunner.FailureReport report) {

        public boolean isRunning() {
            return outcome == Outcome.RUNNING;
        }

        public boolean isDone() {
            return outcome == Outcome.DONE;
        }

        public boolean isFailed() {
            return outcome == Outcome.FAILED;
        }

        /** ⭐ **唯一的成功出口** —— 本类里 `Conclusion.success()` 只许出现一次（判据 B）。 */
        private static Conclusion success() {
            return new Conclusion(Outcome.DONE, null, "", null);
        }

        private static Conclusion running() {
            return new Conclusion(Outcome.RUNNING, null, "", null);
        }

        /** 计划段失败：只有一个理由串，没有执行报告。 */
        private static Conclusion planningFailure(String reason) {
            return new Conclusion(Outcome.FAILED, Leg.PLANNING, reason, null);
        }

        /** 执行段失败：带上 `MineBlockRunner` 的失败报告（理由 / 阶段 / 可否重试）。 */
        private static Conclusion executionFailure(MineBlockRunner.FailureReport report) {
            return new Conclusion(Outcome.FAILED, Leg.EXECUTION, report.reason(), report);
        }
    }

    /**
     * 计划段的结论（`D-466` §六）：规划器的原始结果 + **连锁判定**。
     *
     * <p>为什么把"能不能连锁"放进计划段：`D-077` 的判定读的是**同一 tick 的 `targetState`**
     * ⇒ 它属于"计划这一格"的一部分，不属于"什么时候调度连锁"（后者留编排器）。
     * ⚠️ 判定结果**不落本类字段**：它只经这个记录**导出**给编排器 —— 否则 `tryReplan` 那条路会
     * 意外重判一次（旧实现不重判），变成"顺手改了行为"。
     */
    public record PlanOutcome(MiningPlanner.Result result, boolean chainArmed) {

        public boolean ok() {
            return result.success();
        }

        /** 失败理由（成功时是 `null`）。 */
        public String reason() {
            return result.failureReason();
        }

        public MiningPlan plan() {
            return result.plan();
        }
    }

    private final ServerPlayer bot;
    private final BlockPos target;
    /** ⚠️ 见类注释与 `D-466` §六：本刀**没有**消费者（收集/建拆留编排侧），按裁定保留形参。 */
    private final ScopeBuffer scope;
    private final MiningBudget budget;
    private final MiningProfile profile;
    private final WriteGrant grant;

    private final MiningPlanner miningPlanner = new MiningPlanner();

    /** 本次作业的执行器（`startExecution()` 之后非 null）。 */
    private MineBlockRunner miner;
    /** 最近一次**成功**的计划（计划失败时**保留旧值**，与改造前逐字一致）。 */
    private MiningPlan currentPlan;

    /**
     * `D-177`（审查结论 · 日志规矩）：**只在 `status` 变化时打一行**，绝不按 tick 打。
     *
     * <p>⚠️ 改造前这里是 `(phase, status)` 组合去重，而这条路上 `phase` **恒为 `MINING`**
     * （`tickOnce()` 的 8 个相位全被显式分派，只有 `MINING` 落到执行段）⇒ 「`phase` 那一维」
     * 不可能产生新信息，去掉它**逐字等价**（`D-469` 记录了这条推导）。
     */
    private MineBlockRunner.Status lastProbeStatus;

    /** **终态闩锁**（见类注释）：一旦返回过终态，后续 `tick()` 幂等回放同一结论。 */
    private Conclusion terminal;

    /**
     * @param bot     执行者（必须是 {@link BotPlayer}，见 {@link #startExecution(boolean)}）
     * @param target  目标方块（调用方已 `immutable()`）
     * @param scope   ⚠️ 本刀无消费者（裁定保留；见字段注释）
     * @param budget  挖这一格的额度 —— **构造注入**，本类不造额度（判据 D1）
     * @param profile 这一格的能力信封（能否加高/清障/建拆同权…）
     * @param grant   世界写入授权（`D-082`）
     */
    public MineStep(ServerPlayer bot, BlockPos target, ScopeBuffer scope, MiningBudget budget,
                    MiningProfile profile, WriteGrant grant) {
        this.bot = bot;
        this.target = target;
        this.scope = scope;
        this.budget = budget;
        this.profile = profile;
        this.grant = grant;
    }

    /**
     * **计划段**：算一次站位 + 判一次连锁。每次都**真的重算**。
     *
     * <p>⚠️ 为什么没有"已计划就直接复用"的短路：改造前 `evaluateStandingPoint()` 开头有
     * `if (standingPointEvaluated) { … }`，但**进入求值段的三条路各自先把该标志置回 `false`**
     * （`tickClear` / `tickGainClear` / `tickGain` 的收尾），而写 `true` 的唯一位置紧接着就
     * `enterPhase(MINING)` ⇒ **那段短路在今天不可达**（`D-469` 逐点核过读写集合）。本类**不搬**
     * 不可达的分支。
     *
     * <p>⭐ `D-443` 裁定 1a：接近能力由**本步的 profile** 声明；归因串用**本步的 grant.requester**
     * ⇒ 模式 A 里「补一块再走」的放置会记在作业名下（作业侧的累计额度才看得见它）。
     */
    public PlanOutcome plan() {
        MiningPlanner.Result result = miningPlanner.plan(bot, target, budget, profile.standableOnly(),
                profile.approach(), grant.requester());
        if (!result.success()) {
            return new PlanOutcome(result, false);
        }
        currentPlan = result.plan();
        BlockState targetState = bot.level().getBlockState(target);
        boolean chainArmed = ChainMining.shouldChain(MiningTuning.chainMode(), targetState);
        return new PlanOutcome(result, chainArmed);
    }

    /**
     * **开始一次执行**：按已算好的计划造执行器。
     *
     * <p>⭐ **必须清掉终态闩锁**：本方法的语义是"**又开一次作业**"，而编排器确实会在拿到一个终态结论
     * **之后**再开一次（三条路：`beginChain`/`tickChain` 的连锁回落 · `tryReplan` 的重规划 ·
     * 「运行期清视线 → 回求值段」）。不清的话 {@link #tick()} 会**回放上一次的结论** ⇒ 编排器以为这一格
     * 已经挖完（或又一次拿到同一个失败）⇒ **重试与回落全部变成空转**。
     * ⚠️ 这三条路**在无头电池里零覆盖**（连锁要 `oreexcavation` 在场；`[MineTask重规划探针]` 实测 0 行）
     * ⇒ 这是本刀唯一一处"靠推理而不是靠电池"保住语义的地方（`D-469` §九）。
     *
     * <p>⚠️ `walkOnly` 由**编排器**算好传进来（`useChain && !chainTriggered`，两个量都住在编排侧）——
     * 本类只把它落到 {@link MineBlockRunner} 现成的那个布尔参数上，**不新造一套**（`D-466` 第 6 拍）。
     *
     * @throws IllegalStateException 调用方不是 {@link BotPlayer}（与改造前 `startMining` 同一约束）
     */
    public void startExecution(boolean walkOnly) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalStateException("MineStep requires BotPlayer");
        }
        terminal = null;
        miner = new MineBlockRunner(botPlayer, currentPlan, walkOnly, grant);
        lastProbeStatus = null;
    }

    /**
     * **执行段**：推进一格，直到有结论。终态幂等（见字段注释）。
     */
    public Conclusion tick() {
        if (terminal != null) {
            return terminal;   // 终态幂等（见字段注释；D-175/D-178 两条教训的共同要求）
        }
        Conclusion conclusion = tickOnce();
        if (!conclusion.isRunning()) {
            terminal = conclusion;
        }
        return conclusion;
    }

    private Conclusion tickOnce() {
        if (miner == null) {
            // 第二层防御（同 `MineTask.tickRestore` 的 `restoreTask 缺失`）：进入执行段前必先
            // `startExecution()`，而今天每条进 MINING 的路都紧接着调它 ⇒ 这条分支**不可达**。
            // 留着它是为了"真到了也只是如实失败"，绝不 NPE 打死服务端 tick 循环（`D-175` 的教训）。
            BotLog.warn("[MineStep] runner 缺失（不该发生：进入执行段前必先 startExecution()）target={} ⇒ 如实失败",
                    target.toShortString());
            return Conclusion.executionFailure(
                    new MineBlockRunner.FailureReport("runner_missing", "stepping", false));
        }

        MineBlockRunner.Status status = miner.tick();
        if (status != lastProbeStatus) {
            BotLog.info("[MineStep] 挖掘状态: target={} status={} botPos={} stand={} failure={}",
                    target.toShortString(), status, bot.blockPosition().toShortString(),
                    currentPlan == null ? "-" : currentPlan.standingFoot().toShortString(),
                    miner.failureReason().isEmpty() ? "-" : miner.failureReason());
            lastProbeStatus = status;
        }
        if (status == MineBlockRunner.Status.MINING || status == MineBlockRunner.Status.MOVING) {
            return Conclusion.running();
        }
        if (status == MineBlockRunner.Status.DONE) {
            return Conclusion.success();
        }
        return Conclusion.executionFailure(miner.failureReport());
    }

    /** 最近一次成功算出的计划（诊断口径：`MineTask.currentPlan()` 的委托源）。 */
    public MiningPlan currentPlan() {
        return currentPlan;
    }

    /** 本次执行打算从哪里起步（诊断口径：`MineTask.mineStartPos()` 的委托源）。 */
    public BlockPos mineStartPos() {
        return miner != null ? miner.mineStartPos() : null;
    }
}
