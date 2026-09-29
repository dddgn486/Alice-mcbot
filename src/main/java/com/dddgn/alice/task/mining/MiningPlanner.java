package com.dddgn.alice.task.mining;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
// ⭐ 2026-09-29「搬空第二批」：R7「诚实读数」搬进内核侧（`plans §4.2`③）—— 本类改为引用它，
// `PlanningStatus` 的 import 随之不再需要（本类只剩注释里提到它）。
import com.dddgn.alice.pathing.core.search.SearchConclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import com.dddgn.alice.reach.StandingPointSelector;
import com.dddgn.alice.reach.LineOfSightChecker;
import com.dddgn.alice.reach.MiningPlan;
import com.dddgn.alice.reach.MiningTuning;
// ⭐ 2026-09-29 搬包（改革 ① 主体 · `DS-5` 解体第一批）：这两个从本包搬进 `reach/`（`D-460` 的层定位）。
// ⚠️ 它们是本类**仅有的两个同包依赖**；它们一走，本包剩下的 `MiningPlanner`/`MiningProfile`/`MiningBudget`
// 才是解体真正要处置的对象（`plans §4.2`①）。
import com.dddgn.alice.reach.StandingCostEstimator;
import com.dddgn.alice.reach.StandingPointEvaluator;

/**
 * 挖掘领域规划器（D-067 批次 2/3）：目标方块 → 两模式站位选择 → 成本估算 → top-K 精算 → MiningPlan。
 *
 * <p>流程（`docs/MINING_STAND_SELECTION_DESIGN.md` v7）：
 * <ol>
 *   <li><b>A 腿</b>（{@link MiningPlan.Arrival#IN_PLACE}/{@link MiningPlan.Arrival#DIRECT_PURE_PASSAGE}）：
 *       当前站位能挖 → 直接用；否则现成可站的多角度候选 + 可挖掘面前提 + 路径成本排序；</li>
 *   <li><b>目标下方无支撑</b>：按成本比较"在目标下方放支撑块 + 侧面站位"与"只从正下方挖"（仅当需要收集掉落物）；</li>
 *   <li><b>目标级一次搜索</b>（`K2` 接线，`D-520`，改革 ①）：A 无解 → 把目标交给**内核**
 *       （{@link com.dddgn.alice.pathing.core.search.GoalAdjacent} = "站到目标格的某一面"），
 *       落脚点由 A* 自己找；到达允许破坏/放置
 *       （{@link PathRequest#adjacentApproach}；`D-366b` 起**放开** PILLAR/FALL/DOWNWARD，见 `D-366`），
 *       受 {@link MiningBudget#maxExtraBreakTicks()} 限制，超预算即 `found_but_unminable`。</li>
 * </ol>
 *
 * <p>⭐ <b>`D-520`（改革 ① 主体第一刀）删掉了原来的两条腿</b>：
 * 旧「模式 B（{@code Mode.TUNNEL}：固定 13 格站位枚举 → 逐个 top-K 全预算 A*）」与
 * 旧「兜底（{@code Mode.ENTER_TARGET}：以目标格为终点破坏进入）」**合成上面这一条**。
 * 动机是 `DS-4`（**替换** B，不是并存）与 `DS-9`（简化"完成挖掘被掩埋的目标"），
 * 副产品是 `A2` 那个"13 个候选各跑一次全预算搜索 ≈ 2.4 s/tick"的问题**结构性消失**
 * （一次规划调用只发起**一次**目标级搜索）。
 *
 * <p>⚠️ 三条**逐字保留**的契约（改革不许碰）：① `standableOnly` 的早返回；
 * ② `P1-b`/`P1-d` 的 `search_incomplete` 合取闸门（`SEARCH_LIMIT ≠ UNREACHABLE`）；
 * ③ 站位类失败码的**原样上抛**（`1.4w`，腿给了理由就不许改写成总括码）。
 */
public final class MiningPlanner {

    /*
     * ⚠️ `D-520`（改革 ① 主体第一刀）：这里原来有 `MAX_APPROACH_PLANS = 3`（`A2`「模式 B 的有界穷举」上限）。
     * 它连同 `selectBestApproach` 一起**删除**了 —— 不是"把上限调大"，而是那个形状**结构性消失**：
     * 旧模式 B 要对固定 13 格站位枚举逐个跑全预算 A\*（真机 ≈2.4 s/tick 的来源），
     * 现在改成**一次**目标级搜索（内核 `GoalAdjacent`，落脚点由 A\* 自己找）⇒ 没有"候选穷举"可限。
     * 牙没有丢：`tools/kernel-predicates.py` 的老规则 `rule_approach_plans_bounded` 已**替换**为
     * `rule_arrival_declared_and_consumed`（见该函数 docstring 的三条断言）。
     */
    /*
     * ⚠️ 2026-09-29「搬空第二批」（改革 ① 主体 · `DS-5` 解体）：这里原来装着 **R7「诚实读数」** 三件 ——
     * `inconclusive(PlanningStatus)` · `inconclusiveReason(PathPlan)` · `SEARCH_INCOMPLETE`。
     * **已搬到内核侧** {@link com.dddgn.alice.pathing.core.search.SearchConclusion}（同包名下的新类）。
     * 依据（`plans §4.2`③ ＋ `§2.2` 的 R7 逐字）：
     *   ⭐「**必须活下来**，落 `reach/` 或内核侧」·「⚠️ **红线判据**，不许跟着 `MiningPlanner` 一起消失」·
     *   「⚠️ 它描述的是**内核搜索配额**，放在挖掘包里是**错位**」。
     * ⇒ 本类**不再持有**这三个成员：本文件里所有引用都改成 `SearchConclusion.*`。
     * ⚠️ **只搬不改语义**：判据、理由码字面量、成员名**逐字保留**。
     * ⚠️ **同刀已重锚** `tools/kernel-predicates.py` 的 `rule_search_limit_not_unreachable`
     * （`plans §2.5`：关于 R7 的那半「**必须活下来**（只改锚）」）—— 红线的**牙一颗没丢**。
     * ⛔ 别在这里重新加一个"转发用的"同名薄方法：那会让"唯一出处"重新变成两处。
     */
    /*
     * ⚠️ `D-520`（改革 ① 主体第一刀）：这里原来有 `private static boolean neverRan(PlanningStatus)` ——
     * 它的**唯一**调用点是被删掉的 `selectBestApproach`，用途是 A2 的 `planned` 计数
     * （「没跑」不计入「真的评价过」，因为那次候选穷举会对每个候选各跑一次全预算搜索）。
     * 候选穷举没了 ⇒ A2 的计数口径也没了 ⇒ 它成了零调用者的私有方法，**随之删除**。
     * A 腿 `exactTopK` 不需要它：那里 `SearchConclusion.inconclusive(path.status())` 已经把
     * `SEARCH_LIMIT`（没跑）与 `PARTIAL`（跑了没算完）**一起**置进 `searchLimited`。
     * （⚠️ 该 tombstone 原挂在 `inconclusive` 之后，2026-09-29 R7 搬走时**原样保留**，只改了那一句主语。）
     */

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
        return plan(bot, target, budget, standableOnly, MiningProfile.Approach.PURE_PASSAGE, "mining-planner");
    }

    /**
     * ⭐ **接近能力由调用方声明**（`D-443` 裁定 1a，2026-09-25）：模式 A 的"走到站位格"这一步
     * 不再由本类写死成纯通行。
     *
     * <p>为什么：真机出现"**同一个 bot、同一 tick，走位 `REACHED`、挖掘站位 `no_reachable_standing_point`**"
     * （`survey/34 §2.1`）—— 两个组件对"能不能到"给出相反答案，因为**能力集不同**。
     * 能力是**作业**的属性（鱼骨的 `A14` 已经授权"补一块再走"），所以由调用方的
     * {@link MiningProfile} 带入，而不是规划器替所有消费者猜。
     *
     * @param approach  {@link MiningProfile.Approach#PURE_PASSAGE}（默认，= 精确现状）或
     *                  {@link MiningProfile.Approach#PLACEMENT_ALLOWED}（只放不拆）
     * @param requester 接近走位的归因串（**必须传作业自己的**，否则 `WriteAudit` 里
     *                  这条放置会记到别的名下 ⇒ 作业侧的累计额度看不见它）
     */
    public Result plan(ServerPlayer bot, BlockPos target, MiningBudget budget, boolean standableOnly,
                       MiningProfile.Approach approach, String requester) {
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

        Result direct = planDirect(bot, level, immutableTarget, startFoot, reach, budget, approach, requester);
        if (direct.success()) {
            return direct;
        }
        if (standableOnly) {
            BotLog.info("[MiningPlanner] standable_only target={} reason={}",
                    immutableTarget.toShortString(), direct.failureReason());
            // ⭐ `P1-d`：**"没算完"不许被写成"站不住"** —— 这条腿原来**无条件**改写 direct 的理由
            // ⇒ 直接吃掉 `search_incomplete`（`MiningProfile.STANDABLE_ONLY` 走的正是这条分支，
            // 鱼骨逐格 `MineTask` 用的就是它）。
            // ⭐⭐ `1.4w`（2026-09-26，`survey/35 §9` 桶3-3「最省力收益最大的一刀」）：同一条纪律**通用化** ——
            // 原本只放行 `search_incomplete`，**其余一律改写成总括码** `no_reachable_standing_point`，
            // 于是 `planDirect` 的真理由 `no_valid_standing_point`（`:255`，恰恰是**信息量最大**的那一个）
            // 永远到不了上游。真机实证（`docs/reviews/archive/2026-09-26-真机第五轮-自检报告-空气与通道成品规格.md`
            // §3.4）：`belowSolid=false`（缺一格地板）被伪装成"站位找不到"⇒ 归因四分类（`1.4i`）无从下手。
            // ⇒ 修法 = **腿给出了理由就原样上抛**，只有"腿什么都没说"时才用总括码兜底。
            String directReason = direct.failureReason();
            if (directReason != null && !directReason.isEmpty()) {
                return direct;
            }
            return new Result(null, null, STANDING_NO_REACHABLE);
        }
        // ⭐ `D-520`（改革 ① 主体第一刀）：原来这里是**两条腿**（`planTunnel` + `planEnterTarget`），
        // 现在合成**一条**：把目标交给内核（`GoalAdjacent` = "站到目标格的某一面"，落脚点由 A* 自己找）。
        // 旧 `no_tunnel_standing_point` / `no_reachable_tunnel_standing_point` / `enter_target_unreachable`
        // 三个理由码随之消失（全仓无生产消费者，实测仅本类自己写）；**预算闸门那一半保留**
        // （`enter_target_over_budget` → `approach_over_budget`，见 `planGoalApproach`，`D-076` 不许静默丢）。
        Result goalApproach = planGoalApproach(bot, level, immutableTarget, startFoot, budget);
        if (goalApproach.success()) {
            return goalApproach;
        }
        // P1：两条腿里**任一条**是「本轮没评价完」⇒ 整体**不许**报成不可挖（`SEARCH_LIMIT ≠ UNREACHABLE`）
        // ⚠️ 逐字保留 `P1-b`/`P1-d` 的合取闸门（改革 ① 不许碰这三条契约，见类注释）。
        if (SearchConclusion.SEARCH_INCOMPLETE.equals(direct.failureReason())
                || SearchConclusion.SEARCH_INCOMPLETE.equals(goalApproach.failureReason())) {
            BotLog.warn("[MiningPlanner] search_incomplete target={} direct={} approach={}"
                            + "（本轮搜索被限流 ⇒ 目标**不许**被永久了结）",
                    immutableTarget.toShortString(), direct.failureReason(),
                    goalApproach.failureReason());
            return new Result(null, null, SearchConclusion.SEARCH_INCOMPLETE);
        }
        BotLog.warn("[MiningPlanner] found_but_unminable target={} direct={} approach={} budget={}",
                immutableTarget.toShortString(), direct.failureReason(),
                goalApproach.failureReason(), budget.describe());
        return new Result(null, null, "found_but_unminable");
    }

    // ==================== 模式 A ====================

    private Result planDirect(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              double reach, MiningBudget budget, MiningProfile.Approach approach,
                              String requester) {
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
            BotLog.info("[MiningPlanner] arrival=IN_PLACE target={} stand={} cost=0 support={}",
                    target.toShortString(), startFoot.toShortString(),
                    supportPos == null ? "-" : supportPos.toShortString());
            return new Result(new MiningPlan(target, startFoot, startFoot, path, currentLos,
                    MiningPlan.Arrival.IN_PLACE, supportPos), score, "");
        }

        List<StandingPointSelector.Candidate> candidates =
                StandingPointSelector.generateCandidates(level, target, startFoot, reach);
        if (candidates.isEmpty()) {
            /*
             * ⚠️ 2026-09-29「搬空第一批」：这里原来有一个 P5 诊断探针（2026-09-24 加，
             * 注释自称"定位完成后删"，`plans §2.2` 实测它"**今天无预期读者**"）：
             *   `[MiningPlanner探针] no_valid_standing_point target=… faceStandable=n/6
             *    footPassable=… headPassable=… belowSolid=…`
             * **已删** —— 依据 = `plans §4.2`⑦（R9「**删**（与解体选哪条路无关，本来就该删）」）
             * ＋ 诊断探针纪律（用完即删）。
             * ⚠️ 它当年产出过一次**真根因读数**（`faceStandable=0/6 belowSolid=false`）；那份读数**已固化在文档里**，
             * 不随探针删除而丢：`HANDOVER.md:1178` · `AI_DECISIONS.md:19929`/`:19930` ·
             * `plans/2026-09-25-通道施工器草案.md:56`。
             * ⛔ **别把"这条日志消失"读成"那个判据没了"**：`STANDING_NO_VALID` 本身照旧返回。
             * ⛔ 也别把它读成"诊断探针以后都不许加" —— 纪律是**用完即删**，不是不许加。
             */
            return new Result(null, null, STANDING_NO_VALID);
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
            Result withSupport = selectBest(bot, level, target, startFoot, side,
                    target.below(), com.dddgn.alice.pathing.core.search.CostModel.PLACE_ONE_BLOCK_COST,
                    approach, requester);
            Result fromBelow = selectBest(bot, level, target, startFoot, below,
                    null, 0.0D, approach, requester);
            Result chosen = cheaper(withSupport, fromBelow);
            if (chosen != null) {
                BotLog.info("[MiningPlanner] support_needed target={} supportOption={} belowOption={} chosen={}",
                        target.toShortString(), withSupport.failureReason().isEmpty() ? "ok" : "-",
                        fromBelow.failureReason().isEmpty() ? "ok" : "-",
                        chosen.plan() == null ? "-" : chosen.plan().arrival());
                return chosen;
            }
        }
        Result best = selectBest(bot, level, target, startFoot, candidates,
                null, 0.0D, approach, requester);
        // ⭐ `P1-d`：`exactTopK` 已经如实判过"本轮没评价完"（`SEARCH_LIMIT` 或 `PARTIAL`）
        // ⇒ **不许**在这一层被改写成"站不住"。原来这里无条件改写 ⇒ `P1-b`/`P1-d` 的信号在模式 A 上全丢。
        if (best.plan() != null || SearchConclusion.SEARCH_INCOMPLETE.equals(best.failureReason())) {
            return best;
        }
        return new Result(null, null, STANDING_NO_REACHABLE);
    }

    /**
     * **"找不到站位"的两种码**（`F2` / `D-450`，2026-09-26）：判据**只有一处** —— 本类就是这两个码的产地。
     *
     * <p>为什么要收敛成常量 + 谓词：作业层（`FishboneJob.standingFailureCode`）与夹具都要判
     * "这次失败是不是**站位类**"，而它们**不许**各自照抄一份字符串（`J-6` 的纪律：同一份判据只有一个出处）。
     */
    public static final String STANDING_NO_VALID = "no_valid_standing_point";

    /** 见 {@link #STANDING_NO_VALID}。 */
    public static final String STANDING_NO_REACHABLE = "no_reachable_standing_point";

    /** 这个失败理由是不是**站位类**（找不到 / 到不了站位点）。 */
    public static boolean isStandingPointRefusal(String reason) {
        return STANDING_NO_VALID.equals(reason) || STANDING_NO_REACHABLE.equals(reason);
    }

    /**
     * 目标级一次搜索（`GoalAdjacent`）**到不了目标旁边**（`D-520`，改革 ① 第一刀）。
     *
     * <p>它是旧两个码 {@code no_reachable_tunnel_standing_point} 与 {@code enter_target_unreachable}
     * 的**合并**（两条腿合成一条 ⇒ 两个「到不了」不再有区别）。实测这两个旧码在全仓**无生产消费者**
     * （只有日志与夹具里的字面量断言）⇒ 合并是安全的，不是静默删除。
     *
     * <p>⚠️ **刻意不进 {@link #isStandingPointRefusal}**：旧码也不在
     * （「站位枚举 + 破坏进站」的失败 ≠ 「找不到 / 到不了现成站位」）。
     * 顺手把它加进去会**改变作业侧的分类行为**，那不是这一刀的范围（`D-011`）。
     */
    public static final String ADJACENT_NO_REACHABLE = "no_reachable_adjacent_standing_point";

    // ==================== 目标级一次搜索（改革 ①，`D-520`） ====================

    /**
     * ⭐⭐ **目标级一次搜索**（`K2` 接线，`D-520`）：把"走到被掩埋的目标旁边"交给**内核**。
     *
     * <p><b>它替谁</b>：替掉旧的两条腿 ——
     * ① 模式 B（旧 `planTunnel`）：{@code StandingPointSelector.tunnelCandidates} 枚举**固定 13 格**
     * （4 面 × {y, y−1} + 正下方），再对每个候选跑一次**全预算** A\*
     * （`MAX_APPROACH_PLANS = 3` 截断之前，真机实测 `candidates=13 planned=13` ⇒ ≈2.4 s/tick，
     * 而它发生在**服务端 tick 线程**上）；
     * ② 兜底（旧 `planEnterTarget`）：以**目标格本身**为终点"破坏进入"。
     * 现在只有一句话：{@link com.dddgn.alice.pathing.core.search.GoalAdjacent}
     * =「与目标格曼哈顿相邻、不站进目标格、不站在它上方」⇒ **落脚点由 A\* 自己找**
     * （模板 = Baritone `BuilderProcess.GoalAdjacent extends GoalGetToBlock`，`D-036`）。
     *
     * <p>⭐ <b>与旧两条腿的关系（逐条实测，⛔ 不夸大）</b>：
     * <ul>
     *   <li><b>能力集逐字相同</b>：{@link PathRequest#MINING_APPROACH_MOVEMENTS}
     *       （含 `BREAK_AND_TRAVERSE`/`BREAK_AND_ENTER`/`PILLAR`/`FALL`/`DOWNWARD`）—— 所以旧 ②「破坏进入」
     *       在新请求下**仍然可能**，只是到达判据从"站进目标格"改成"站到它旁边"。</li>
     *   <li>⚠️ <b>到达集是<u>收窄</u>的，不是超集</b> —— 实测两边集合：
     *       新 = `GoalAdjacent` 的 **5 格**（4 个水平邻格 + 正下方 1 格；曼哈顿 ≤1，且不许站目标格、不许站它上方）；
     *       旧 = `tunnelCandidates` 的 **8 格**（4 面 × {y, y−1}）+ **正下方一列**（y−2 起、按触及深度与破坏预算延伸）。
     *       ⇒ 旧集里**曼哈顿 2 的落点**（y−1 那一圈水平格）与**更深的竖直落点**在新形态下**不再是落点**
     *       （A\* 仍然可以**挖出**一格合法的落点，或落在"正下方"那一格上）。</li>
     *   <li>✅ <b>为什么这是对的</b>：新到达集 = **Baritone 自己的 `GoalAdjacent`**
     *       （`BuilderProcess.GoalAdjacent extends GoalGetToBlock`，曼哈顿 ≤1 ＋ 排除"站到方块上方"，
     *       `reference/baritone-1.20.1/…/BuilderProcess.java:892`）⇒ 这次收窄是**向参照实现对齐**（`D-036`），
     *       而旧那套 8＋N 格几何是 Alice 自造的枚举。
     *       ⚠️ 代价已登记：`D-520` §八（真实行为差异，客户端实测时优先看"实心脉 / 完全被包住"那类目标）。</li>
     * </ul>
     *
     * <p>⚠️ <b>三条逐字保留的契约</b>：
     * <ol>
     *   <li>{@code P1-b}/{@code P1-d}：**先看"有没有结论"再看到达** —— {@link SearchConclusion#inconclusiveReason(PathPlan)}
     *       非空（`SEARCH_LIMIT` = 根本没跑 / `PARTIAL` = 跑了没算完）⇒ 原样上抛
     *       {@link SearchConclusion#SEARCH_INCOMPLETE}，⛔ **不许**改写成"到不了"（否则整体被记成
     *       `found_but_unminable`（**永久理由**）⇒ `MineJob` 把目标写进 `attempted` 永久了结；
     *       真机实测过 377 次，取证 `docs/reviews/2026-09-25-mine循环198ms拆解.md`）；</li>
     *   <li>**预算闸门**（`D-076`）：代价超过 {@link MiningBudget#maxExtraBreakTicks()} ⇒ **如实拒绝**
     *       （`approach_over_budget`，旧名 `enter_target_over_budget`）——
     *       这是旧 ② 腿独有的那半，腿合并时**不许静默丢掉**；</li>
     *   <li>失败码 = {@link #ADJACENT_NO_REACHABLE}（旧两码合并；实测全仓**无生产消费者**，
     *       只有日志与夹具的字面量断言）。</li>
     * </ol>
     */
    private Result planGoalApproach(ServerPlayer bot, ServerLevel level, BlockPos target,
                                    BlockPos startFoot, MiningBudget budget) {
        PathPlan path = planPath(bot, startFoot, target,
                PathRequest.adjacentApproach(bot.getUUID().toString(), startFoot, target, null,
                        "mining-planner"));
        String inconclusive = SearchConclusion.inconclusiveReason(path);
        if (!inconclusive.isEmpty()) {
            BotLog.warn("[MiningPlanner] arrival=MINING_APPROACH target={} startFoot={} status={} "
                            + "reason={} searchLimited=true"
                            + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`；"
                            + "`PARTIAL` = 预算烧完只拿到前缀，`P1-d`）",
                    target.toShortString(), startFoot.toShortString(), path.status(), inconclusive);
            return new Result(null, null, inconclusive);
        }
        if (!path.reached()) {
            return new Result(null, null, ADJACENT_NO_REACHABLE);
        }
        // ⭐ 落点 = 路径**实际到达**的那一格，⛔ **不是** `path.goalFoot()`
        // （对 `GoalAdjacent` 来说它返回的是**目标方块本身**，不是脚位；见 `PathPlan.finalFoot()`）。
        // 这与 `MiningPlan` 紧凑构造器里的不变量是**同一条**（那里会再校验一次，抛 IAE 就说明这里传错了）。
        BlockPos standingFoot = path.finalFoot();
        double approachCost = path.totalCost();
        double budgetCost = budget.maxExtraBreakTicks()
                / com.dddgn.alice.pathing.core.search.CostModel.WALK_ONE_BLOCK_TICKS;
        if (approachCost > budgetCost) {
            BotLog.warn("[MiningPlanner] approach_over_budget target={} cost={} budget={} stand={}",
                    target.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f", approachCost),
                    String.format(java.util.Locale.ROOT, "%.2f", budgetCost),
                    standingFoot.toShortString());
            return new Result(null, null, "approach_over_budget");
        }
        LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(
                level, StandingPointSelector.eyeAt(standingFoot), target);
        StandingPointEvaluator.StandingPointScore score =
                StandingPointEvaluator.of(standingFoot, approachCost, approachCost, los);
        BotLog.info("[MiningPlanner] arrival=MINING_APPROACH target={} startFoot={} stand={} cost={} "
                        + "movements={}",
                target.toShortString(), startFoot.toShortString(), standingFoot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", approachCost), path.movements().size());
        return new Result(new MiningPlan(target, startFoot, standingFoot, path, los,
                MiningPlan.Arrival.MINING_APPROACH, null), score, "");
    }


    /*
     * ⚠️ 2026-09-29「搬空第一批」：这里原来是 P5 探针的辅助函数
     * `private static int countStandableFaces(ServerLevel, BlockPos)`（目标 6 面邻格里"现成可站"的个数）。
     * 它与上面那个探针是**同一件东西的两半** ⇒ 同刀删除（唯一的调用者就是那个探针）。
     * ⚠️ 它**不是**"站位枚举"的一部分：`StandingPointSelector.isStandable` 照旧在用，删掉的只是这个**计数**。
     */

    // ==================== 选择与精算 ====================

    /** 模式 A：候选 → 估算 → top-K 精确规划（纯通行请求）。 */
    private Result selectBest(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              List<StandingPointSelector.Candidate> candidates,
                              BlockPos supportPos, double extraCost, MiningProfile.Approach approach,
                              String requester) {
        if (candidates.isEmpty()) {
            return new Result(null, null, "no_candidate");
        }
        Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot = new HashMap<>();
        List<BlockPos> feet = new ArrayList<>(candidates.size());
        for (StandingPointSelector.Candidate candidate : candidates) {
            feet.add(candidate.foot());
            losByFoot.put(candidate.foot(), candidate.los());
        }
        // ⭐ `D-443` 裁定 1a：接近请求的**能力由调用方声明**；默认 `PURE_PASSAGE` = 原有那一行逐字不变
        //（连归因串 `"mining-planner"` 都保持原样，避免动到既有判据/账本口径）。
        String requesterForApproach = requester == null || requester.isBlank()
                ? "mining-planner" : requester;
        boolean placementAllowed = approach == MiningProfile.Approach.PLACEMENT_ALLOWED;
        // ⭐⭐ `D-520`：**到位形状在这里派生、并在紧邻一行决定请求工厂** —— 两件事同一出处；
        // 执行期 `MineBlockRunner` 只读 `plan.arrival()` 复现同一个工厂（⛔ 不再由任何值反推）。
        // 旧形状的病灶：规划期用 `withPlacement`（`D-443` 裁定 1a，鱼骨「补一块再走」）、
        // 执行期一律用 `of`（纯通行）⇒ 规划说到得了、执行说到不了（同一 tick 两个相反答案）。
        MiningPlan.Arrival arrival = placementAllowed
                ? MiningPlan.Arrival.DIRECT_PLACEMENT_ALLOWED
                : MiningPlan.Arrival.DIRECT_PURE_PASSAGE;
        boolean includeUnestimated = placementAllowed;
        return exactTopK(bot, level, target, startFoot, feet, losByFoot, arrival, supportPos, extraCost,
                placementAllowed
                        ? (from, to) -> PathRequest.withPlacement(bot.getUUID().toString(), from, to,
                                requesterForApproach)
                        : (from, to) -> PathRequest.of(bot.getUUID().toString(), from, to,
                                requesterForApproach), includeUnestimated);
    }


    private Result exactTopK(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                             List<BlockPos> feet,
                             Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot,
                             MiningPlan.Arrival arrival, BlockPos supportPos, double extraCost,
                             BiFunction<BlockPos, BlockPos, PathRequest> requestFactory,
                             boolean includeUnestimated) {
        StandingCostEstimator.Result estimate = StandingCostEstimator.estimate(bot, level, feet);
        List<BlockPos> ranked = new ArrayList<>(estimate.costs().keySet());
        ranked.sort(Comparator.comparingDouble(estimate.costs()::get));
        // ⭐ `D-443` 裁定 1a（2026-09-25）：**排名也不能假设纯通行**。
        // `StandingCostEstimator` 的口径是「**不可达候选不在 map 中**」（它记的是**纯通行**成本场）
        // ⇒ 当接近能力升到 `PLACEMENT_ALLOWED` 时，"只有补一块才到得了"的候选会被**整个丢掉排名**，
        // 于是**永远不会被精算** ⇒ 报 `no_reachable_standing_point`，而同一段的走位工厂明明到得了
        // （夹具实测：走位 `REACHED movements=4` / 挖掘站位 `no_reachable_standing_point`）。
        // ⇒ 把这些"估不出成本"的候选按**几何下界**排在已估出的之后，交给精算阶段裁决（它们正是新能力的目标）。
        if (includeUnestimated) {
            List<BlockPos> unestimated = new ArrayList<>();
            for (BlockPos foot : feet) {
                if (!estimate.costs().containsKey(foot)) {
                    unestimated.add(foot);
                }
            }
            unestimated.sort(Comparator.comparingDouble(f -> new com.dddgn.alice.pathing.core.search.GoalFoot(f).heuristic(startFoot)));
            ranked.addAll(unestimated);
        }

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
                    if (SearchConclusion.inconclusive(path.status())) {
                        searchLimited = true;
                    }
                    continue;
                }
                double cost = path.totalCost() + extraCost;
                if (best == null || cost < best.getScore()) {
                    Double estimated = estimate.costs().get(foot);
                    best = StandingPointEvaluator.of(foot, cost,
                            estimated == null ? new com.dddgn.alice.pathing.core.search.GoalFoot(foot).heuristic(startFoot) : estimated,
                            losByFoot.get(foot));
                    bestPath = path;
                }
            }
            planned = k;
            boolean canExpand = k < ranked.size() && k < MiningTuning.exactTopKMax();
            if (best != null) {
                Double nextRaw = k < ranked.size() ? estimate.costs().get(ranked.get(k)) : null;
                double nextEstimate = nextRaw == null
                        ? Double.POSITIVE_INFINITY
                        : nextRaw + extraCost;
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
                BotLog.warn("[MiningPlanner] arrival={} target={} startFoot={} candidates={} planned={}"
                                + " reason=search_incomplete searchLimited=true"
                                + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`）",
                        arrival, target.toShortString(), startFoot.toShortString(), feet.size(), planned);
                return new Result(null, null, SearchConclusion.SEARCH_INCOMPLETE);
            }
            return new Result(null, null, "no_reachable_candidate");
        }
        BotLog.info("[MiningPlanner] arrival={} target={} startFoot={} candidates={} estimate={} nodes={} ms={}"
                        + " planned={} chosen={} cost={} pathSize={} support={} los={}",
                arrival, target.toShortString(), startFoot.toShortString(), feet.size(), estimate.mode(),
                estimate.nodesExpanded(), estimate.elapsedMillis(), planned,
                best.getPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", best.getScore()),
                bestPath.movements().size(),
                supportPos == null ? "-" : supportPos.toShortString(),
                best.getLineOfSightResult().isClear());
        MiningPlan plan = new MiningPlan(target, startFoot, best.getPosition(), bestPath,
                best.getLineOfSightResult(), arrival, supportPos);
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
