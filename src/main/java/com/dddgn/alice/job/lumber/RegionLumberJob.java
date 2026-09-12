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
        // §13.2：先**对账我种的苗**（长成树就销账、被拔掉也销账），否则"欠树"判断会被幽灵条目污染
        reconcileMySaplings(state);
        int viableInRegion = 0;
        for (Candidate candidate : raw.viable()) {
            if (region.contains(candidate.anchor())) {
                viableInRegion++;
            }
        }
        if (state.baselineTrees(bot.getUUID()) <= 0) {
            // 区域目标棵数 = 首次巡查时"本来有多少棵可作业的树"（维持原状即可持续）
            state.setBaselineTrees(bot.getUUID(), viableInRegion);
            BotLog.info("[Job] maintain 区域目标棵数 baseline={}（首次巡查确定，之后按它算欠树）",
                    viableInRegion);
        }
        int standing = viableInRegion + state.mySaplingCount(bot.getUUID());
        int deficit = Math.max(0, state.baselineTrees(bot.getUUID()) - standing);
        BotLog.info("[Job] maintain region={} viable={} inRegion={} tried={} mySaplings={}"
                        + " standing={} baseline={} deficit={} chopped={} failed={} planted={}"
                        + " pendingReplant={} lastPatrol={}",
                region.describe(), raw.viable().size(), inRegion.size(), tried.size(),
                state.mySaplingCount(bot.getUUID()), standing, state.baselineTrees(bot.getUUID()),
                deficit, treesChopped, treesFailed,
                LumberRegionState.get(server).saplingsPlanted(bot.getUUID()),
                state.pendingReplantCount(bot.getUUID()), server.getTickCount());

        // ① 欠树 ⇒ 先补种（§13.1"有空格且欠树 → 补种"）；② 有树 ⇒ 砍；两者都在同一轮里按需做
        if (deficit > 0) {
            var planted = tryPlant(state, deficit);
            if (planted != null) {
                return planted;               // 失败（缺配置/缺苗）⇒ 如实上抛
            }
            idlePatrols = 0;                  // 刚干了活（补种），不算待机
        }

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

    /**
     * **对账"我种的苗"**（§13.2）：位置已不是树苗（长成树 / 被拔掉）⇒ 从"我种的"里销账。
     * 长成树的那些会在下一轮作为候选出现，所以销账不会丢信息。
     */
    private void reconcileMySaplings(LumberRegionState state) {
        var level = bot.serverLevel();
        for (var pos : state.mySaplings(bot.getUUID())) {
            var block = level.getBlockState(pos).getBlock();
            boolean stillSapling = level.getBlockState(pos).is(net.minecraft.tags.BlockTags.SAPLINGS);
            if (!stillSapling) {
                state.forgetSapling(bot.getUUID(), pos);
                BotLog.info("[Job] maintain 苗 {} 已不是树苗（{}）⇒ 销账", pos.toShortString(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            }
        }
    }

    /**
     * **补种一格**（§13.2：`KEEP` 策略的计划内永久修改）。
     *
     * @return null = 本轮补种成功（或本轮不需要补）；非 null = **如实失败**的终态
     */
    private com.dddgn.alice.task.Task.Status tryPlant(LumberRegionState state, int deficit) {
        String itemId = state.saplingItem(bot.getUUID());
        if (itemId == null) {
            terminalReason = "sapling_unavailable";
            failure = terminalReason + "（区域欠树 deficit=" + deficit
                    + "，但未选择树苗：/alice region sapling <item>）";
            BotLog.warn("[Job] maintain {}", failure);
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(new net.minecraft.resources.ResourceLocation(itemId));
        if (item == null || !(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
            terminalReason = "sapling_unavailable";
            failure = terminalReason + "（选定的树苗物品无效：" + itemId + "）";
            BotLog.warn("[Job] maintain {}", failure);
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        // 找一格可补种的位置：优先"自己砍过的树桩"，其下方必须是土/草（树苗的放置前提）
        var level = bot.serverLevel();
        var spots = new java.util.ArrayList<>(state.pendingReplant(bot.getUUID()));
        net.minecraft.core.BlockPos spot = null;
        for (var candidate : spots) {
            var below = level.getBlockState(candidate.below());
            if (level.getBlockState(candidate).isAir() && below.is(net.minecraft.tags.BlockTags.DIRT)) {
                spot = candidate;
                break;
            }
        }
        if (spot == null) {
            BotLog.info("[Job] maintain 欠树 deficit={} 但当前没有可补种的位置（等地形/等树桩空出来）", deficit);
            return null;
        }
        // 手上要有这个树苗（没有 ⇒ §13.3 的 tool_missing，语义是"缺执行这件事的东西"）
        var inventory = bot.getInventory();
        int slot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            terminalReason = "tool_missing";
            failure = terminalReason + "（区域欠树 deficit=" + deficit + "，但背包里没有 " + itemId + "）";
            BotLog.warn("[Job] maintain {}", failure);
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        var grant = com.dddgn.alice.action.WriteGrant.of(jobName(),
                com.dddgn.alice.action.WriteReason.REGION_REPLANT);
        var verdict = com.dddgn.alice.action.WriteBudget.consumePlace(bot, level, spot, grant);
        if (verdict == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
            BotLog.warn("[Job] maintain 补种被写入预算拒绝 {}（D-106：超限即硬停，不越界改世界）",
                    spot.toShortString());
            return null;
        }
        var previous = level.getBlockState(spot);
        var placed = blockItem.getBlock().defaultBlockState();
        level.setBlock(spot, placed, 3);
        // 账本记 KEEP（`REGION_REPLANT.temporary()==false`）⇒ 不受"建拆同权"约束
        com.dddgn.alice.ledger.WorldModLedger.recordPlacement(level, bot.getUUID(), grant, spot,
                previous, placed);
        inventory.getItem(slot).shrink(1);
        state.addMySapling(bot.getUUID(), spot);
        state.removePendingReplant(bot.getUUID(), spot);
        BotLog.info("[Job] maintain plant sapling@{}（deficit={} → 补种后 standing 上升；KEEP 策略）",
                spot.toShortString(), deficit);
        return null;
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
            // §13.2：树桩记为待补种位置（区域不变量：欠树则补种）
            LumberRegionState.get(bot.serverLevel().getServer())
                    .addPendingReplant(bot.getUUID(), base);
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
                        + " planted={} baseline={} saplingItem={} reason={} → {}",
                region.describe(), treesChopped, treesFailed,
                LumberRegionState.get(bot.getServer()).patrols(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).mySaplingCount(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).saplingsPlanted(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).baselineTrees(bot.getUUID()),
                String.valueOf(LumberRegionState.get(bot.getServer()).saplingItem(bot.getUUID())),
                terminalReason, status);
        return status;
    }
}
