package com.dddgn.alice.task.mining;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 挖掘领域规划器（D-067 批次 2/3）：目标方块 → 两模式站位选择 → 成本估算 → top-K 精算 → MiningPlan。
 *
 * <p>流程（`docs/MINING_STAND_SELECTION_DESIGN.md` v7）：
 * <ol>
 *   <li><b>模式 A</b>（{@link MiningPlan.Mode#CURRENT}/{@link MiningPlan.Mode#DIRECT}）：
 *       当前站位能挖 → 直接用；否则现成可站的多角度候选 + 可挖掘面前提 + 路径成本排序；</li>
 *   <li><b>目标下方无支撑</b>：按成本比较"在目标下方放支撑块 + 侧面站位"与"只从正下方挖"（仅当需要收集掉落物）；</li>
 *   <li><b>模式 B</b>（{@link MiningPlan.Mode#TUNNEL}）：A 无解 → 固定几何集（4 面 × {y,y−1} + 正下方），
 *       到达允许破坏/放置（`PathRequest.miningApproach`；`D-366b` 起**放开** PILLAR/FALL/DOWNWARD，见 `D-366`）；</li>
 *   <li><b>兜底</b>（{@link MiningPlan.Mode#ENTER_TARGET}）：以目标格为终点破坏进入，
 *       受 {@link MiningBudget#maxExtraBreakTicks()} 限制，超预算即 `found_but_unminable`。</li>
 * </ol>
 */
public final class MiningPlanner {

    /**
     * ⭐ **A2（2026-09-21）模式 B 的"有界穷举"上限** —— 一次规划调用最多对几个站位候选做**全预算精算**。
     *
     * <p>为什么必须有（真机第四轮取证，`docs/reviews/2026-09-21-客户端第四轮-深矿搜索卡顿.md` + 日志复算）：
     * `selectBestApproach` 原来对 `tunnelCandidates` **全部**候选各跑一次
     * `PathRequest.miningApproach` 全预算 A\*（`WALK_BUDGET` = 20 000 节点 / 200 ms）。
     * 真机实测 `candidates=13 planned=13` ⇒ **一次规划调用 ≈ 13 × 185 ms ≈ 2.4 s**，
     * 而它发生在**服务端 tick 线程**上 ⇒ `[Job] step` 间隔被实测为 **2.4 s**（≈0.4 TPS，持续 57.6 s）。
     *
     * <p>为什么"可以"截断（这是**有界**而不是"换成贪心"）：候选按
     * {@code GoalFoot.heuristic(startFoot)} **由近到远排序**，而 13 个候选来自同一目标的同一个小几何集
     * （4 面 × {y, y−1} + 正下方）⇒ **它们的可达性高度相关**：真机实测 13/13 全部 `!reached()`
     * （`reason=no_reachable`），即"近的那几个过不去，远的也过不去"。
     *
     * <p>⚠️ **代价与回收条件**（不许当成"已经没问题了"）：截断会丢掉"第 4~13 个候选里恰好有一个可行"的情形。
     * 因此本常量是**临时止血**，回收条件 = 出现一次「前 {@value} 个候选全失败、但更多候选能成功」的实测反例，
     * 届时正确做法是**把穷举摊到多个 tick**（记住进度、下 tick 继续），而不是把上限调大。
     */
    public static final int MAX_APPROACH_PLANS = 3;

    /**
     * ⭐⭐ **`P1-d`（2026-09-25）：这次搜索**有没有得出可达性结论**。
     *
     * <p>两种"没得出"的形态，**都必须**与「不可达」分开（红线 `SEARCH_LIMIT ≠ UNREACHABLE`）：
     * <ul>
     *   <li>{@link PlanningStatus#SEARCH_LIMIT}：<b>A1 每 tick 总账拒绝</b> ⇒ 这次搜索**根本没跑**
     *       （`CorePathPlanner.planInternal` 的 `tryAcquire()` 返回 false）⇒ 什么都不知道；</li>
     *   <li>{@link PlanningStatus#PARTIAL}：搜索**跑了、烧光了自己的预算**（`D-388` 的 50 ms），
     *       交出了 best-so-far **前缀**但 `reached() == false` ⇒ **没算完**。</li>
     * </ul>
     *
     * <p><b>为什么必须收口成一处</b>（`P1-b` 的洞，`D-434 §三`）：`P1-b`（2026-09-22）修好了 `SEARCH_LIMIT`
     * 被覆盖成永久理由的病灶，但**只认了 `SEARCH_LIMIT`**；而**真机实测 09-24 客户端日志**里，
     * 撞 50 ms 上限的 502 次搜索中 **480 次返回的是 `PARTIAL`**（只有 22 次 `SEARCH_LIMIT`，
     * 480+22=502 精确闭合）⇒ 那 96% 照样被判成 `no_reachable` / `found_but_unminable`（**永久理由**）
     * ⇒ `MineJob` 的 40-tick 冷却（只认 `search_incomplete`）几乎不生效
     * （实测 `found_but_unminable` **307** : `search_incomplete` **87**）⇒ mine 循环每 tick 重烧
     * 4 × 50 ms ≈ 200 ms。取证 = `docs/reviews/2026-09-25-mine循环198ms拆解.md`。
     *
     * <p><b>⚠️ 两种形态在 A2 计数上不同</b>（`MAX_APPROACH_PLANS` 只数**真的评价过**的候选）：
     * `SEARCH_LIMIT` 不计入；`PARTIAL` **计入**（预算真花了）。所以调用点不是一个 `if` 能合并的
     * —— 本谓词只回答"结论是否可信"，**不回答**"算不算评价过"。
     */
    public static boolean inconclusive(PlanningStatus status) {
        return status == PlanningStatus.SEARCH_LIMIT || status == PlanningStatus.PARTIAL;
    }

    /**
     * 每 tick 总账拒绝 ⇒ 这次搜索**没跑** ⇒ 什么都不知道（**不是**"不可达"）。
     *
     * <p>单独一个方法而不是直接比较状态：调用点需要区分"没跑"（不计入 A2）与"跑了但没算完"（计入 A2）。
     */
    private static boolean neverRan(PlanningStatus status) {
        return status == PlanningStatus.SEARCH_LIMIT;
    }

    /**
     * ⭐ 瞬时理由码（**唯一出处**）："这次没得出可达性结论" ⇒ 调用方**不许**把这个目标永久了结
     * （`D-387` 的 `P1-b` + `D-434` 的 `P1-d`）。本类内部**不许**再写这个字符串字面量。
     */
    public static final String SEARCH_INCOMPLETE = "search_incomplete";

    /**
     * ⭐⭐ `P1-d`：一条腿（一个 {@link PathPlan}）"**这次到底给出了什么结论**" —— **唯一出处**。
     *
     * <p>为什么要抽成函数（而不是在每个调用点写 `status == SEARCH_LIMIT || status == PARTIAL`）：
     * 判据必须能**确定地**红。而 `PARTIAL` 能不能被造出来**取决于外部地形是否已加载** ——
     * `CoarseGoalPrefixCheckTask`（2026-09-22）记过同一条夹具洁净度坑：单跑时目标方向有地形 ⇒
     * 搜索撞未加载边界 ⇒ `PARTIAL` + 前缀；而在 CORE 里同一区域已加载且为空 ⇒ open set 耗尽 ⇒
     * `UNREACHABLE`。⇒ **`PARTIAL` 形态**的行为级复现是环境相关的，不能当判据的主语。
     * 抽成纯函数之后，夹具可以拿**真的 `PathPlan` 对象**（`PathPlan.partial(...)` /
     * `PathPlan.failure(...)` 都是 public 工厂）做**与环境无关**的真值表断言。
     *
     * @return {@link #SEARCH_INCOMPLETE}（没结论 ⇒ 调用方**不许**当成不可达）；空串（有结论，交回调用方判）
     */
    public static String inconclusiveReason(PathPlan plan) {
        return inconclusive(plan.status()) ? SEARCH_INCOMPLETE : "";
    }

    /** 规划结果：plan 为空时仅表示当前规划阶段未产生可用计划。 */
    public record Result(MiningPlan plan, StandingPointEvaluator.StandingPointScore score,
                         String failureReason) {
        public boolean success() {
            return plan != null;
        }
    }

    /** 兼容入口（批次 2 调用点）：默认收集掉落物预算。 */
    public Result plan(ServerPlayer bot, BlockPos target) {
        return plan(bot, target, MiningBudget.collecting(bot, bot.serverLevel(), target));
    }

    public Result plan(ServerPlayer bot, BlockPos target, MiningBudget budget) {
        return plan(bot, target, budget, false);
    }

    /**
     * @param standableOnly true = **只允许模式 A**（现成可站站位），禁止模式 B 挖隧道与"破坏进入"。
     *                      伐木必须用它：树的目标周围常无可站面，模式 B 会选"正下方/邻格"的**几何**候选
     *                      （例如平台内部 y−1 的格子）并挖地进去站——对矿石是特性，对砍树是荒谬行为
     *                      （2026-09-10 客户端实测：bot 往地里挖一格站进去，随后爬不出来、2/4 根原木失败）。
     *                      需要清障时由**上层 Job** 显式做（限次 + 预算），不由规划器偷偷挖。
     */
    public Result plan(ServerPlayer bot, BlockPos target, MiningBudget budget, boolean standableOnly) {
        ServerLevel level = bot.serverLevel();
        BlockPos immutableTarget = target.immutable();
        BlockPos startFoot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();
        double reach = bot.getBlockReach();

        // S-4（P0-C，2026-09-12 接线）：**挖掘前目标确认** —— 目标格本身不是岩浆，但它的 6 个邻格里
        // 有岩浆 ⇒ 挖穿后岩浆会**流进来**。这条与 D-037（`MovementHelper` 的通行性：身体别**进**岩浆、
        // 流体不可挖）**不重叠**：那条管"别走进去"，这条管"挖穿后会不会涌进来"。
        // 探针 `FluidRiskPolicy.miningRefusal` 早已写好但**零调用**（登记为第 3 个死抽象）——
        // 这里接上，返回 `fluid_risk_lava` 这个**硬拒绝码**（`MineTask.isHardTargetRefusal` 已认它）。
        // 成本 6 次方块读取，可忽略；目标确认在任何站位/隧道规划之前，避免为"注定不能挖的目标"做规划。
        String fluidRefusal = com.dddgn.alice.survival.FluidRiskPolicy.miningRefusal(bot, immutableTarget);
        if (fluidRefusal != null) {
            BotLog.warn("[MiningPlanner] fluid_refusal target={} reason={}（邻格岩浆会涌入，S-4/P0-C）",
                    immutableTarget.toShortString(), fluidRefusal);
            return new Result(null, null, fluidRefusal);
        }

        Result direct = planDirect(bot, level, immutableTarget, startFoot, reach, budget);
        if (direct.success()) {
            return direct;
        }
        if (standableOnly) {
            BotLog.info("[MiningPlanner] standable_only target={} reason={}",
                    immutableTarget.toShortString(), direct.failureReason());
            // ⭐ `P1-d`：**"没算完"不许被写成"站不住"** —— 这条腿原来**无条件**改写 direct 的理由
            // ⇒ 直接吃掉 `search_incomplete`（`MiningProfile.STANDABLE_ONLY` 走的正是这条分支，
            // 鱼骨逐格 `MineTask` 用的就是它）。
            if (SEARCH_INCOMPLETE.equals(direct.failureReason())) {
                return direct;
            }
            return new Result(null, null, "no_reachable_standing_point");
        }
        Result tunnel = planTunnel(bot, level, immutableTarget, startFoot, reach, budget);
        if (tunnel.success()) {
            return tunnel;
        }
        Result enter = planEnterTarget(bot, level, immutableTarget, startFoot, budget);
        if (enter.success()) {
            return enter;
        }
        // P1：三条腿里**任一条**是「本轮没评价完」⇒ 整体**不许**报成不可挖（`SEARCH_LIMIT ≠ UNREACHABLE`）
        if (SEARCH_INCOMPLETE.equals(direct.failureReason())
                || SEARCH_INCOMPLETE.equals(tunnel.failureReason())
                || SEARCH_INCOMPLETE.equals(enter.failureReason())) {
            BotLog.warn("[MiningPlanner] search_incomplete target={} direct={} tunnel={} enter={}"
                            + "（本轮搜索被限流 ⇒ 目标**不许**被永久了结）",
                    immutableTarget.toShortString(), direct.failureReason(), tunnel.failureReason(),
                    enter.failureReason());
            return new Result(null, null, SEARCH_INCOMPLETE);
        }
        BotLog.warn("[MiningPlanner] found_but_unminable target={} direct={} tunnel={} enter={} budget={}",
                immutableTarget.toShortString(), direct.failureReason(), tunnel.failureReason(),
                enter.failureReason(), budget.describe());
        return new Result(null, null, "found_but_unminable");
    }

    // ==================== 模式 A ====================

    private Result planDirect(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              double reach, MiningBudget budget) {
        LineOfSightChecker.LineOfSightResult currentLos =
                StandingPointSelector.isValidStandingPoint(level, target, startFoot, reach);
        if (currentLos != null) {
            PathPlan path = planPath(bot, startFoot, startFoot, PathRequest.of(
                    bot.getUUID().toString(), startFoot, startFoot, "mining-planner"));
            StandingPointEvaluator.StandingPointScore score =
                    StandingPointEvaluator.of(startFoot, 0.0D, 0.0D, currentLos);
            // 掉落物会丢的目标（D-078 修正，v7 §2.3；判据 `D-364` 收紧为"真会丢"）：
            // 即使当前站位就能挖，也要先在目标下方放支撑块。当前站位**就在目标正下方**时属于"从下方挖"策略，无需支撑。
            // 手上没有一次性方块时不强行要求支撑（避免把"没资源"变成任务失败），维持原行为。
            BlockPos supportPos = null;
            if (dropWouldBeLost(level, target) && budget.collectDrops()
                    && !isSameColumn(startFoot, target)
                    && com.dddgn.alice.action.BlockInteraction.findPlaceableSlot(bot) >= 0) {
                supportPos = target.below();
            }
            BotLog.info("[MiningPlanner] mode=CURRENT target={} stand={} cost=0 support={}",
                    target.toShortString(), startFoot.toShortString(),
                    supportPos == null ? "-" : supportPos.toShortString());
            return new Result(new MiningPlan(target, startFoot, startFoot, path, currentLos,
                    MiningPlan.Mode.CURRENT, supportPos), score, "");
        }

        List<StandingPointSelector.Candidate> candidates =
                StandingPointSelector.generateCandidates(level, target, startFoot, reach);
        if (candidates.isEmpty()) {
            // ⭐ P5 诊断探针（2026-09-24，临时：定位完成后删）：模式 A 零候选时，把**几何事实**记下来 ——
            // 否则「被同族矿石包住」只能靠读码推断（`D-391` 三-2 的教训：先补读数，不猜）。
            BotLog.warn("[MiningPlanner探针] no_valid_standing_point target={} faceStandable={}/6 "
                            + "footPassable={} headPassable={} belowSolid={}",
                    target.toShortString(), countStandableFaces(level, target),
                    MovementHelper.canWalkThrough(level, target),
                    MovementHelper.canWalkThrough(level, target.above()),
                    MovementHelper.canWalkOn(level, target.below()));
            return new Result(null, null, "no_valid_standing_point");
        }

        boolean dropLost = dropWouldBeLost(level, target);
        boolean needSupportBlock = dropLost && budget.collectDrops();
        if (needSupportBlock) {
            List<StandingPointSelector.Candidate> side = new ArrayList<>();
            List<StandingPointSelector.Candidate> below = new ArrayList<>();
            for (StandingPointSelector.Candidate candidate : candidates) {
                if (isSameColumn(candidate.foot(), target)) {
                    below.add(candidate);
                } else {
                    side.add(candidate);
                }
            }
            Result withSupport = selectBest(bot, level, target, startFoot, side, MiningPlan.Mode.DIRECT,
                    target.below(), com.dddgn.alice.pathing.core.search.CostModel.PLACE_ONE_BLOCK_COST);
            Result fromBelow = selectBest(bot, level, target, startFoot, below, MiningPlan.Mode.DIRECT,
                    null, 0.0D);
            Result chosen = cheaper(withSupport, fromBelow);
            if (chosen != null) {
                BotLog.info("[MiningPlanner] support_needed target={} supportOption={} belowOption={} chosen={}",
                        target.toShortString(), withSupport.failureReason().isEmpty() ? "ok" : "-",
                        fromBelow.failureReason().isEmpty() ? "ok" : "-",
                        chosen.plan() == null ? "-" : chosen.plan().mode());
                return chosen;
            }
        }
        Result best = selectBest(bot, level, target, startFoot, candidates, MiningPlan.Mode.DIRECT,
                null, 0.0D);
        // ⭐ `P1-d`：`exactTopK` 已经如实判过"本轮没评价完"（`SEARCH_LIMIT` 或 `PARTIAL`）
        // ⇒ **不许**在这一层被改写成"站不住"。原来这里无条件改写 ⇒ `P1-b`/`P1-d` 的信号在模式 A 上全丢。
        if (best.plan() != null || SEARCH_INCOMPLETE.equals(best.failureReason())) {
            return best;
        }
        return new Result(null, null, "no_reachable_standing_point");
    }

    // ==================== 模式 B / 兜底 ====================

    private Result planTunnel(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              double reach, MiningBudget budget) {
        List<BlockPos> candidates = StandingPointSelector.tunnelCandidates(
                bot, level, target, reach, budget.maxExtraBreakTicks());
        if (candidates.isEmpty()) {
            return new Result(null, null, "no_tunnel_standing_point");
        }
        Result result = selectBestApproach(bot, level, target, startFoot, candidates,
                MiningPlan.Mode.TUNNEL);
        if (result.plan() != null) {
            return result;
        }
        // ⭐ `P1-b`（2026-09-22 真机根因）：**逐字保留** `search_incomplete`。
        // 病灶：这里原来无条件改写成 `no_reachable_tunnel_standing_point` ⇒ `plan()` 的 P1 合取闸门
        // （三条腿任一为 `search_incomplete` 就整体降级）**永不触发** ⇒ 报 `found_but_unminable`
        // （永久性理由）⇒ `MineJob` 把候选写进 `attempted` **永久了结**。
        // 真机实测（2026-09-22 客户端 `latest.log`）：`found_but_unminable` 377 次、`search_incomplete` 206 次、
        // A1 拒绝 ~235 次（`[Search] 超 tick 预算` 234 次）⇒ 目标 `336,62,190` 从未被挖却已 `already_attempted`。
        if (SEARCH_INCOMPLETE.equals(result.failureReason())) {
            return result;
        }
        return new Result(null, null, "no_reachable_tunnel_standing_point");
    }

    private Result planEnterTarget(ServerPlayer bot, ServerLevel level, BlockPos target,
                                   BlockPos startFoot, MiningBudget budget) {
        // 兜底：以目标格为终点（破坏进入），破坏成本受预算限制
        PathPlan path = planPath(bot, startFoot, target,
                PathRequest.miningApproach(bot.getUUID().toString(), startFoot, target, "mining-planner"));
        // ⭐ `P1-b`/`P1-d`：**先看"有没有结论"再看到达** —— `!reached()` 里既有 `SEARCH_LIMIT`
        // （本 tick 搜索额度已用尽 ⇒ 根本没跑），也有 `PARTIAL`（跑了、烧完预算、只有前缀）。
        // 两者都不是"到不了"。原来直接返回 `enter_target_unreachable` ⇒ 整体被记成 `found_but_unminable`。
        String inconclusive = inconclusiveReason(path);
        if (!inconclusive.isEmpty()) {
            BotLog.warn("[MiningPlanner] mode=ENTER_TARGET target={} startFoot={} status={} "
                            + "reason={} searchLimited=true"
                            + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`；"
                            + "`PARTIAL` = 预算烧完只拿到前缀，`P1-d`）",
                    target.toShortString(), startFoot.toShortString(), path.status(), inconclusive);
            return new Result(null, null, inconclusive);
        }
        if (!path.reached()) {
            return new Result(null, null, "enter_target_unreachable");
        }
        double breakCost = path.totalCost();
        double budgetCost = budget.maxExtraBreakTicks()
                / com.dddgn.alice.pathing.core.search.CostModel.WALK_ONE_BLOCK_TICKS;
        if (breakCost > budgetCost) {
            BotLog.warn("[MiningPlanner] enter_target_over_budget target={} cost={} budget={}",
                    target.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f", breakCost),
                    String.format(java.util.Locale.ROOT, "%.2f", budgetCost));
            return new Result(null, null, "enter_target_over_budget");
        }
        LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(
                level, StandingPointSelector.eyeAt(startFoot), target);
        StandingPointEvaluator.StandingPointScore score =
                StandingPointEvaluator.of(startFoot, path.totalCost(), path.totalCost(), los);
        BotLog.info("[MiningPlanner] mode=ENTER_TARGET target={} startFoot={} cost={}",
                target.toShortString(), startFoot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", path.totalCost()));
        return new Result(new MiningPlan(target, startFoot, target, path, los,
                MiningPlan.Mode.ENTER_TARGET, null), score, "");
    }

    /** ⭐ P5 诊断探针（2026-09-24，临时：定位完成后删）：目标 6 面邻格里「现成可站」的个数（0 = 真被包住）。 */
    private static int countStandableFaces(ServerLevel level, BlockPos target) {
        int standable = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (StandingPointSelector.isStandable(level, target.relative(direction))) {
                standable++;
            }
        }
        return standable;
    }

    // ==================== 选择与精算 ====================

    /** 模式 A：候选 → 估算 → top-K 精确规划（纯通行请求）。 */
    private Result selectBest(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              List<StandingPointSelector.Candidate> candidates, MiningPlan.Mode mode,
                              BlockPos supportPos, double extraCost) {
        if (candidates.isEmpty()) {
            return new Result(null, null, "no_candidate");
        }
        Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot = new HashMap<>();
        List<BlockPos> feet = new ArrayList<>(candidates.size());
        for (StandingPointSelector.Candidate candidate : candidates) {
            feet.add(candidate.foot());
            losByFoot.put(candidate.foot(), candidate.los());
        }
        return exactTopK(bot, level, target, startFoot, feet, losByFoot, mode, supportPos, extraCost,
                (from, to) -> PathRequest.of(bot.getUUID().toString(), from, to, "mining-planner"));
    }

    /**
     * 模式 B：候选通常 ≤ 11 个，且**都需要破坏/放置才能到达**——
     * 纯通行估算（S1/S2）对它们无意义（不在纯通行成本场里），因此**全部候选精确规划**，
     * 只用一个廉价下界做展开顺序（D-070 修正）。
     */
    private Result selectBestApproach(ServerPlayer bot, ServerLevel level, BlockPos target,
                                      BlockPos startFoot, List<BlockPos> feet, MiningPlan.Mode mode) {
        List<BlockPos> ordered = new ArrayList<>(feet);
        ordered.sort(Comparator.comparingDouble(
                foot -> new com.dddgn.alice.pathing.core.search.GoalFoot(foot).heuristic(startFoot)));
        StandingPointEvaluator.StandingPointScore best = null;
        PathPlan bestPath = null;
        int planned = 0;
        boolean searchLimited = false;
        for (BlockPos foot : ordered) {
            // ⭐ **A2 有界穷举**：见 {@link #MAX_APPROACH_PLANS}。截断事实**必须进日志**，
            // 否则"只试了 3 个"与"试了 13 个全失败"在事后看来一模一样（`capped=` 字段就是为此）。
            if (planned >= MAX_APPROACH_PLANS) {
                break;
            }
            PathPlan path = planPath(bot, startFoot, foot,
                    PathRequest.miningApproach(bot.getUUID().toString(), startFoot, foot, "mining-planner"));
            // P1（D-374，2026-09-21）：SEARCH_LIMIT = 「这一格本轮还没被评价」，**不是**「没有路」。
            // 它不计入 planned（A2 的 cap 只该数真的评价过的候选），并把事实带上去 —— 否则
            // 「本 tick 搜索预算被占满」会被写进 no_reachable_candidate，再被 MineJob 永久了结
            // （真机实测：目标 436,82,229 **从未被挖**却已 already_attempted）。
            if (neverRan(path.status())) {
                searchLimited = true;
                continue;
            }
            // ⭐ `P1-d`：`PARTIAL` = 搜索**跑了、烧光了自己的预算、只拿到前缀** ⇒ 两件事同时成立：
            // ① 结论不可信 ⇒ 置 `searchLimited`（结尾**不许**报 `no_reachable`）；
            // ② 预算**真花了** ⇒ 仍然计入 `planned`（A2 的上限数的是"几次全预算精算"，不是"几次有结论"）。
            if (inconclusive(path.status())) {
                searchLimited = true;
            }
            planned++;
            if (!path.reached()) {
                // ⭐ P5 诊断探针（2026-09-24，临时：定位完成后删）：模式 B 每个候选**逐个**记状态，
                // 配合目标格的脚位/头位读数，才能分清「候选本身站不住」与「路被断在某类边上」。
                BotLog.warn("[MiningPlanner探针] mode={} target={} candidate={} status={} reached=false "
                                + "candFootPassable={} candHeadPassable={}",
                        mode, target.toShortString(), foot.toShortString(), path.status(),
                        MovementHelper.canWalkThrough(level, foot),
                        MovementHelper.canWalkThrough(level, foot.above()));
                continue;
            }
            double cost = path.totalCost();
            if (best == null || cost < best.getScore()) {
                best = StandingPointEvaluator.of(foot, cost, cost, LineOfSightChecker.checkFromEye(
                        level, StandingPointSelector.eyeAt(foot), target));
                bestPath = path;
            }
        }
        if (best == null && searchLimited) {
            BotLog.warn("[MiningPlanner] mode={} target={} startFoot={} candidates={} planned={} capped={}"
                            + " reason=search_incomplete searchLimited=true"
                            + "（**本轮没评价完**，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`）",
                    mode, target.toShortString(), startFoot.toShortString(), ordered.size(), planned,
                    planned < ordered.size());
            return new Result(null, null, SEARCH_INCOMPLETE);
        }
        if (best == null) {
            BotLog.warn("[MiningPlanner] mode={} target={} startFoot={} candidates={} planned={} capped={}"
                            + " reason=no_reachable",
                    mode, target.toShortString(), startFoot.toShortString(), ordered.size(), planned,
                    planned < ordered.size());
            return new Result(null, null, "no_reachable_candidate");
        }
        BotLog.info("[MiningPlanner] mode={} target={} startFoot={} candidates={} estimate=EXHAUSTIVE planned={}"
                        + " capped={} chosen={} cost={} pathSize={} los={}",
                mode, target.toShortString(), startFoot.toShortString(), ordered.size(), planned,
                planned < ordered.size(),
                best.getPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", best.getScore()),
                bestPath.movements().size(), best.getLineOfSightResult().isClear());
        MiningPlan plan = new MiningPlan(target, startFoot, best.getPosition(), bestPath,
                best.getLineOfSightResult(), mode, null);
        return new Result(plan, best, "");
    }

    private Result exactTopK(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                             List<BlockPos> feet,
                             Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot,
                             MiningPlan.Mode mode, BlockPos supportPos, double extraCost,
                             BiFunction<BlockPos, BlockPos, PathRequest> requestFactory) {
        StandingCostEstimator.Result estimate = StandingCostEstimator.estimate(bot, level, feet);
        List<BlockPos> ranked = new ArrayList<>(estimate.costs().keySet());
        ranked.sort(Comparator.comparingDouble(estimate.costs()::get));

        StandingPointEvaluator.StandingPointScore best = null;
        PathPlan bestPath = null;
        int planned = 0;
        // ⭐ `P1-b`：是否出现过"本轮没评价完"（`SEARCH_LIMIT`）。有它 ⇒ 结尾**不许**报 `no_reachable_candidate`。
        boolean searchLimited = false;
        int k = Math.min(MiningTuning.exactTopK(), ranked.size());
        while (true) {
            for (int i = planned; i < k; i++) {
                BlockPos foot = ranked.get(i);
                PathPlan path = planPath(bot, startFoot, foot, requestFactory.apply(startFoot, foot));
                if (!path.reached()) {
                    // ⭐ `P1-d`：`SEARCH_LIMIT`（没跑）与 `PARTIAL`（跑了没算完）**都不许**变成"没有路"。
                    if (inconclusive(path.status())) {
                        searchLimited = true;
                    }
                    continue;
                }
                double cost = path.totalCost() + extraCost;
                if (best == null || cost < best.getScore()) {
                    best = StandingPointEvaluator.of(foot, cost, estimate.costs().get(foot),
                            losByFoot.get(foot));
                    bestPath = path;
                }
            }
            planned = k;
            boolean canExpand = k < ranked.size() && k < MiningTuning.exactTopKMax();
            if (best != null) {
                double nextEstimate = k < ranked.size()
                        ? estimate.costs().get(ranked.get(k)) + extraCost
                        : Double.POSITIVE_INFINITY;
                if (best.getScore() <= nextEstimate || !canExpand) {
                    break;
                }
            } else if (!canExpand) {
                break;
            }
            k = Math.min(k + 2, ranked.size());
        }

        if (best == null) {
            if (searchLimited) {
                BotLog.warn("[MiningPlanner] mode={} target={} startFoot={} candidates={} planned={}"
                                + " reason=search_incomplete searchLimited=true"
                                + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`）",
                        mode, target.toShortString(), startFoot.toShortString(), feet.size(), planned);
                return new Result(null, null, SEARCH_INCOMPLETE);
            }
            return new Result(null, null, "no_reachable_candidate");
        }
        BotLog.info("[MiningPlanner] mode={} target={} startFoot={} candidates={} estimate={} nodes={} ms={}"
                        + " planned={} chosen={} cost={} pathSize={} support={} los={}",
                mode, target.toShortString(), startFoot.toShortString(), feet.size(), estimate.mode(),
                estimate.nodesExpanded(), estimate.elapsedMillis(), planned,
                best.getPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", best.getScore()),
                bestPath.movements().size(),
                supportPos == null ? "-" : supportPos.toShortString(),
                best.getLineOfSightResult().isClear());
        MiningPlan plan = new MiningPlan(target, startFoot, best.getPosition(), bestPath,
                best.getLineOfSightResult(), mode, supportPos);
        return new Result(plan, best, "");
    }

    private static Result cheaper(Result first, Result second) {
        boolean firstOk = first.plan() != null;
        boolean secondOk = second.plan() != null;
        if (firstOk && secondOk) {
            return first.score().getScore() <= second.score().getScore() ? first : second;
        }
        if (firstOk) {
            return first;
        }
        return secondOk ? second : null;
    }

    private static boolean isSameColumn(BlockPos pos, BlockPos target) {
        return pos.getX() == target.getX() && pos.getZ() == target.getZ();
    }

    /**
     * **掉落物真的会丢**才需要垫（`D-364`）—— 真机实测（2026-09-20）暴露了原判据与注释的错位：
     * 注释写的是"否则掉落物会掉进**虚空/岩浆/深坑**"，而实现是 `!hasSupportBelow`（下方那格不是实心就垫）
     * ⇒ **挖矿自己挖出来的坑也满足条件**：先挖 y=72、再挖 y=73 时，下方正是刚挖空的空气
     * ⇒ 每个上层矿石都要求垫方块 ⇒ **垫不上就把那个目标判死**（实测 9 次 `SUPPORT_PLACE_FAILED`，
     * 于是整层 y=73 的煤被留下、bot 跑去远处挖），而且垫下去的方块**会挡住相邻矿石的视线**
     * （实测 `LINE_OF_SIGHT_BLOCKED`）。现在按注释的原意判：**N 格内没有可落面**（深坑/虚空）
     * 或**先撞上岩浆**才算"会丢"。
     */
    /**
     * 「掉落承接面」的搜索深度 ⭐ 用户 2026-09-22 裁定（**先 5 后改为 8**，以 8 为准）：
     * **至少要下方悬空 8 格**才算"掉落物会丢"。
     *
     * <p>原来 = 4 ⇒ 真机上"目标下面只空 3~4 格、再往下就是实心"的**普通矿洞**被当成深坑 ⇒
     * 触发了"在目标下方垫方块"这条会写世界的动作（用户看到的是"莫名其妙跑到目标下面垫石头"）。
     * 8 格口径：**只有掉落物真会掉 ≥8 格（或下方是岩浆/虚空）才垫** ⇒ 普通矿洞一律不写世界。
     */
    private static final int DROP_FALL_SEARCH = 8;

    private static boolean dropWouldBeLost(ServerLevel level, BlockPos target) {
        BlockPos cursor = target.below();
        for (int depth = 0; depth < DROP_FALL_SEARCH; depth++) {
            if (!level.hasChunkAt(cursor)) {
                return false;       // 未加载 ⇒ 不判"会丢"（保守：不写世界；D-331）
            }
            var state = level.getBlockState(cursor);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;        // 掉落物落到岩浆 = 销毁
            }
            if (!state.getCollisionShape(level, cursor).isEmpty()) {
                return false;       // 找到可落面 ⇒ 捡得回来
            }
            cursor = cursor.below();
        }
        return true;                // N 格内都没有可落面 ⇒ 按"深坑/虚空"处理
    }

    private static PathPlan planPath(ServerPlayer bot, BlockPos startFoot, BlockPos standingFoot,
                                     PathRequest request) {
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }
}
