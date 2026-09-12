package com.dddgn.alice.job.lumber;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **`MAINTAIN` 区域型伐木 Job**（J8 / §13）：不追求"跑完即结束"，而是**持续维持区域不变量**。
 *
 * <p>与一次性伐木的关系（§13 的裁定）：**同一个 Job 家族 + 不同 `GoalSpec`/策略**，
 * 而不是两套任务。落地方式：本 Job 只做"**巡查 → 挑一棵 → 派活 → 回来继续巡查**"的编排，
 * 真正的砍伐**原样复用** {@link LumberJob}（每次给它 {quota=1, center=那棵树}），
 * 因此清障预算、建拆同权、攀爬兜底、失败语义全部沿用已验证的那一套 —— 没有第二份实现。
 *
 * <p>生命周期（§13.1）：
 * <pre>
 * PATROL（每 {@code patrolIntervalTicks} 巡查一次：扫区域 → 过滤掉试过的 → 挑最近的一棵）
 *   ├─ 有树 ⇒ HARVEST（内嵌 LumberJob, quota=1）→ 结算 → 回 PATROL
 *   └─ 无树 ⇒ 连续 {@link #IDLE_PATROLS} 次无活 ⇒ `idle_no_work`（§13.3：如实待机，**不算失败**）
 * </pre>
 *
 * <p>失败语义（§13.3）：区域里有树但全不可达 ⇒ `no_reachable_candidate` + 逐树理由；
 * 缺工具 ⇒ `tool_missing`（沿用 D-128 的前置检查）；脚手架没拆干净 ⇒ `scaffold_restore_incomplete`。
 *
 * <p>**健康输出**（§13.1：常驻任务不能是黑箱）：每次巡查一行
 * {@code [Job] maintain region=… viable=… mySaplings=… chopped=… actions=… lastPatrol=…}。
 *
 * <p>停止：只由玩家命令触发（下一条 `/alice …` 指令会替换任务，`cancelled:replaced`）。
 */
public final class RegionLumberJob implements com.dddgn.alice.job.Job {

    public static final String NAME = "region_lumber";

    /** 连续多少次"巡查无活"才判定待机（§13.1：巡查周期由配置决定，禁止高频扫描）。 */
    public static final int IDLE_PATROLS = 3;

    private final BotPlayer bot;
    private final LumberRegionState.Region region;
    private final ScopeBuffer scope;
    private final LumberCandidateSource source;
    private final SelectionPolicy policy;
    private final int patrolIntervalTicks;
    private final int maxTicks;

    private final Set<net.minecraft.core.BlockPos> tried = new LinkedHashSet<>();
    private final List<String> failureNotes = new ArrayList<>();

    private LumberJob current;
    private int ticks;
    private int patrolCooldown;
    private int idlePatrols;
    private int treesChopped;
    private int treesFailed;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;

    public RegionLumberJob(BotPlayer bot, LumberRegionState.Region region, ScopeBuffer scope,
                           LumberCandidateSource source, SelectionPolicy policy,
                           int patrolIntervalTicks, int maxTicks) {
        this.bot = bot;
        this.region = region;
        this.scope = scope;
        this.source = source;
        this.policy = policy;
        this.patrolIntervalTicks = Math.max(1, patrolIntervalTicks);
        this.maxTicks = maxTicks;
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    public com.dddgn.alice.task.TaskTarget target() {
        if (current != null) {
            return current.target();
        }
        return com.dddgn.alice.task.TaskTarget.block(region.center());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public String progressSummary() {
        return "region=" + region.describe() + " chopped=" + treesChopped + " failed=" + treesFailed
                + " mySaplings=" + LumberRegionState.get(bot.getServer()).mySaplingCount(bot.getUUID());
    }

    @Override
    public com.dddgn.alice.task.Task.Status tick() {
        if (terminated) {
            return com.dddgn.alice.task.Task.Status.DONE;
        }
        if (++ticks > maxTicks) {
            terminalReason = "goal_timeout";
            failure = terminalReason;
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        if (current != null) {
            return harvest();
        }
        if (patrolCooldown > 0) {
            patrolCooldown--;
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        patrolCooldown = patrolIntervalTicks;
        return patrol();
    }

    // ==================== 巡查 ====================

    private com.dddgn.alice.task.Task.Status patrol() {
        var server = bot.serverLevel().getServer();
        LumberRegionState state = LumberRegionState.get(server);
        var spec = GoalSpec.harvestUnits(region.center(), region.coverRadius(), 1, maxTicks);
        CandidateSet raw = source.candidates(bot, spec);

        List<Candidate> inRegion = new ArrayList<>();
        for (Candidate candidate : raw.viable()) {
            if (region.contains(candidate.anchor()) && !tried.contains(candidate.anchor())) {
                inRegion.add(candidate);
            }
        }
        state.markPatrol(bot.getUUID(), server.getTickCount());
        BotLog.info("[Job] maintain region={} viable={} inRegion={} tried={} mySaplings={}"
                        + " chopped={} failed={} actions={} lastPatrol={}",
                region.describe(), raw.viable().size(), inRegion.size(), tried.size(),
                state.mySaplingCount(bot.getUUID()), treesChopped, treesFailed, treesChopped,
                server.getTickCount());

        if (inRegion.isEmpty()) {
            idlePatrols++;
            if (idlePatrols >= IDLE_PATROLS) {
                if (!failureNotes.isEmpty()) {
                    // §13.3：区域里有树但全不可达 ⇒ FAILED no_reachable_candidate + 逐树理由
                    terminalReason = "no_reachable_candidate";
                    failure = terminalReason + " " + String.join(" | ", failureNotes);
                    return finish(com.dddgn.alice.task.Task.Status.FAILED);
                }
                // §13.3：连续 N 次巡查无进展且区域内无树无苗 ⇒ 如实待机（**不算失败**）
                terminalReason = "idle_no_work";
                return finish(com.dddgn.alice.task.Task.Status.DONE);
            }
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }

        idlePatrols = 0;
        int localRadius = localSearchRadius();
        Selection selection = policy.select(bot, spec, new CandidateSet(inRegion, raw.rejected()));
        Candidate picked = selection.picked();
        if (picked == null) {
            // 策略没挑出来（理论上不该发生，因为候选非空）⇒ 如实记一笔，换下一轮
            failureNotes.add("policy_no_pick(" + inRegion.size() + " viable)");
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        BotLog.info("[Job] maintain pick tree@{} reason={} candidates={}",
                picked.anchor().toShortString(), selection.reason(), inRegion.size());
        var treeSpec = GoalSpec.harvestUnits(picked.anchor(), localRadius, 1, maxTicks);
        current = new LumberJob(bot, treeSpec, scope, source, policy);
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    /** 单棵树的局部搜索半径：够覆盖该树及其树冠即可，不必整片区域。 */
    private int localSearchRadius() {
        return 8;
    }

    // ==================== 派活（复用一次性伐木 Job）====================

    private com.dddgn.alice.task.Task.Status harvest() {
        var status = current.tick();
        if (status == com.dddgn.alice.task.Task.Status.RUNNING) {
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        var base = current.target().blockPos();
        String reason = current.terminalReason();
        if (status == com.dddgn.alice.task.Task.Status.DONE && "quota_met".equals(reason)) {
            treesChopped++;
            LumberRegionState.get(bot.serverLevel().getServer()).addChopped(bot.getUUID());
            BotLog.info("[Job] maintain tree@{} 完成 chopped={}", base.toShortString(), treesChopped);
        } else {
            treesFailed++;
            String detail = base.toShortString() + ":" + reason
                    + (current.attemptFailures().isEmpty() ? ""
                            : " " + String.join(" | ", current.attemptFailures()));
            failureNotes.add(detail);
            tried.add(base);
            BotLog.warn("[Job] maintain tree@{} 未完成 reason={}（记入逐树理由，本轮不再挑它）",
                    base.toShortString(), reason);
        }
        current = null;
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    private com.dddgn.alice.task.Task.Status finish(com.dddgn.alice.task.Task.Status status) {
        terminated = true;
        bot.controller().stopMovement();
        BotLog.info("[Job] maintain SUMMARY region={} chopped={} failed={} patrols={} mySaplings={}"
                        + " reason={} → {}",
                region.describe(), treesChopped, treesFailed,
                LumberRegionState.get(bot.getServer()).patrols(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).mySaplingCount(bot.getUUID()),
                terminalReason, status);
        return status;
    }
}
