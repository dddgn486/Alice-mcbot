package com.dddgn.alice.task.mining;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
// ⭐ 批次 1 `1-1b₂`（2026-09-29）：目标腿的**第一条搜索**用同列形状 ⇒ 本类成为
// `GoalColumnBlocks` 的**唯一生产消费者**（`1-0a` 落地时它是"零消费者"，那句已在类头更正）。
import com.dddgn.alice.pathing.core.search.GoalColumnBlocks;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
// ⭐ 2026-09-29「搬空第二批」：R7「诚实读数」搬进内核侧（`plans §4.2`③）—— 本类改为引用它，
// `PlanningStatus` 的 import 随之不再需要（本类只剩注释里提到它）。
import com.dddgn.alice.pathing.core.search.SearchConclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.dddgn.alice.reach.StandingPointSelector;
import com.dddgn.alice.reach.LineOfSightChecker;
import com.dddgn.alice.reach.MiningPlan;
import com.dddgn.alice.reach.StandingPointEvaluator;
// ⭐ 2026-09-29（改革 ① 主体 · `①-0`「拆信封」，`D-527`）：接近能力枚举提到 `reach/` 成为独立类型
// ⇒ 本类与 `MiningProfile` 都改成引用它（`task/` → `reach/` 是合法方向）。
import com.dddgn.alice.reach.ApproachCapability;
// ⭐ 2026-09-29「①-1」（`plans §4.2`④）：`R8`「归因码」独立成类搬进 `reach/`
// ⇒ 本类改为**引用**它（`StandingPointRefusal.…`），常量名与字面量逐字未改。
import com.dddgn.alice.reach.StandingPointRefusal;
// ⭐ 2026-09-29「①-2a」：结果载体 `Result` 搬进 `reach/` 成为独立类型 `StandingPlanResult`
// （理由见本类里那条墓碑；`task/` → `reach/` 是合法方向）。
import com.dddgn.alice.reach.StandingPlanResult;
// ⭐ 2026-09-29「①-2b」（`plans §4.2`①）：**A 腿（`R2`＋`R5`）搬进 `reach/StandingPlanSelector`**
// ⇒ 本类改为**调用**它，并把信封拆开传原始值（见 `selectDirect` 调用点那段注释）。
// ⚠️ 本刀顺带删掉了因搬家而变成**零消费者**的 import（`CorePathPlanner` / `StandingCostEstimator` /
// `MiningTuning` / `DropCatchment` 以及整套 `java.util` 集合）—— 这不改行为，只是别让悬空 import 撒谎。
import com.dddgn.alice.reach.StandingPlanSelector;

/**
 * 挖掘领域规划器（D-067 批次 2/3）：目标方块 → 两模式站位选择 → 成本估算 → top-K 精算 → MiningPlan。
 *
 * <p>流程（`docs/MINING_STAND_SELECTION_DESIGN.md` v7）：
 * <ol>
 *   <li><b>A 腿</b>（{@link MiningPlan.Arrival#IN_PLACE}/{@link MiningPlan.Arrival#DIRECT_PURE_PASSAGE}）：
 *       当前站位能挖 → 直接用；否则现成可站的多角度候选 + 可挖掘面前提 + 路径成本排序；</li>
 *   <li><b>目标下方无支撑</b>：按成本比较"在目标下方放支撑块 + 侧面站位"与"只从正下方挖"（仅当需要收集掉落物）；</li>
 *   <li><b>目标级到达</b>（`K2` 接线 `D-520` ＋ 批次 1 `1-1b₂`）：A 无解 → 把目标交给**内核**，
 *       落脚点由 A* 自己找 —— ⭐ **两条腿顺序搜索**（`D-532` §二「甲」）：
 *       先问 {@link GoalColumnBlocks}（**同列**，"站进目标那一列"），拿不出方案再问
 *       {@link com.dddgn.alice.pathing.core.search.GoalAdjacent}（**侧面兜底**，"站到目标格的某一面"）；
 *       到达允许破坏/放置（`D-366b` 起**放开** PILLAR/FALL/DOWNWARD，见 `D-366`），
 *       受 {@link MiningBudget#maxExtraBreakTicks()} 限制，超预算即 `found_but_unminable`。</li>
 * </ol>
 *
 * <p>⭐ <b>`D-520`（改革 ① 主体第一刀）删掉了原来的两条腿</b>：
 * 旧「模式 B（{@code Mode.TUNNEL}：固定 13 格站位枚举 → 逐个 top-K 全预算 A*）」与
 * 旧「兜底（{@code Mode.ENTER_TARGET}：以目标格为终点破坏进入）」**合成上面这一条**。
 * 动机是 `DS-4`（**替换** B，不是并存）与 `DS-9`（简化"完成挖掘被掩埋的目标"），
 * 副产品是 `A2` 那个"13 个候选各跑一次全预算搜索 ≈ 2.4 s/tick"的问题**结构性消失**
 * （一次规划调用只发起**≤2 次**目标级搜索 —— `D-520` 时是 1 次，`1-1b₂` 起最多 2 次）。
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

    /*
     * ⚠️ 2026-09-29「①-2a」（改革 ① 主体 · `DS-5` 解体）：这里原来有一个**嵌套** record
     * `public record StandingPlanResult(MiningPlan, StandingPointEvaluator.StandingPointScore, String)`。
     * **已搬到** {@link com.dddgn.alice.reach.StandingPlanResult}（`plans §4.2`①：「选」→ `reach/`）。
     * 依据：它的三个组件的家本来就在 `reach/`（`MiningPlan` / `StandingPointScore` / 归因串），
     * 而生产它的那段逻辑（候选枚举 → 排序 → top-K → 择优）正是 `§4.2`① 要搬进 `reach/` 的那一件
     * ⇒ 载体留在 `task/`、逻辑搬进 `reach/` = 逻辑反过来依赖上层（`check-layer-direction` 断言①）。
     * ⚠️ **只搬不改语义**：组件顺序、组件名、访问器名、`success()` 的判据**一字未改**。
     * ⛔ **别在这里放回同名嵌套 record，也别留一个转发壳**：那会让"结果载体"长出第二处
     * （`J-6`：同一份判据只有一个出处）。
     * 📌 合并时机：`DS-5` 解体的 `①-3`（`R1` 收口）会把本类的编排也搬走 ⇒ 届时本类整体消失。
     */

    /** 兼容入口（批次 2 调用点）：默认收集掉落物预算。 */
    public StandingPlanResult plan(ServerPlayer bot, BlockPos target) {
        return plan(bot, target, MiningBudget.collecting(bot, bot.serverLevel(), target));
    }

    public StandingPlanResult plan(ServerPlayer bot, BlockPos target, MiningBudget budget) {
        return plan(bot, target, budget, false);
    }

    /**
     * @param standableOnly true = **只允许模式 A**（现成可站站位），禁止模式 B 挖隧道与"破坏进入"。
     *                      伐木必须用它：树的目标周围常无可站面，模式 B 会选"正下方/邻格"的**几何**候选
     *                      （例如平台内部 y−1 的格子）并挖地进去站——对矿石是特性，对砍树是荒谬行为
     *                      （2026-09-10 客户端实测：bot 往地里挖一格站进去，随后爬不出来、2/4 根原木失败）。
     *                      需要清障时由**上层 Job** 显式做（限次 + 预算），不由规划器偷偷挖。
     */
    public StandingPlanResult plan(ServerPlayer bot, BlockPos target, MiningBudget budget, boolean standableOnly) {
        return plan(bot, target, budget, standableOnly, ApproachCapability.PURE_PASSAGE, "mining-planner");
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
     * @param approach  {@link ApproachCapability#PURE_PASSAGE}（默认，= 精确现状）或
     *                  {@link ApproachCapability#PLACEMENT_ALLOWED}（只放不拆）
     *                  ⚠️ 类型住在 **`reach/`**（`D-527` 从嵌套枚举提出来），不再是 `MiningProfile.Approach`
     *                  —— 理由 = `plans §4.2`① 要把「选」搬进 `reach/`，而 `reach/` 不许 import `task/`。
     * @param requester 接近走位的归因串（**必须传作业自己的**，否则 `WriteAudit` 里
     *                  这条放置会记到别的名下 ⇒ 作业侧的累计额度看不见它）
     */
    public StandingPlanResult plan(ServerPlayer bot, BlockPos target, MiningBudget budget, boolean standableOnly,
                       ApproachCapability approach, String requester) {
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
            return new StandingPlanResult(null, null, fluidRefusal);
        }

        // ⭐ `①-2b`（2026-09-29，改革 ① 主体 · `DS-5` 解体，`plans §4.2`①）：A 腿（`R2`＋`R5`）
        // 已搬进 `reach/StandingPlanSelector` ⇒ **本类负责把信封拆开**，只传原始值：
        //   · `collectDrops` 从预算里取出（`MiningBudget` 进不了 `reach/`：它自己 import `action/`）；
        //   · `canPlaceSupport` = **作业侧的库存能力声明**（`findPlaceableSlot` 是 `action/` 的查询）
        //     ⇒ `reach/` 不许自己去问库存（那样等于把 `action/` 拖下层，`check-layer-direction` 断言①）。
        // ⚠️ **实测口径差异（本刀唯一的行为增量，已登记 `D-530`）**：它现在**每次 `plan()` 都算一次**
        // （原来只在 CURRENT 快路径成功、且前三个条件都成立时才查）—— 代价 = 最多 9 次快捷栏读取的
        // **纯读**（`BlockInteraction.findPlaceableSlot` 无副作用、无日志、不写账本）。
        StandingPlanResult direct = StandingPlanSelector.selectDirect(bot, level, immutableTarget, startFoot,
                reach, budget.collectDrops(),
                com.dddgn.alice.action.BlockInteraction.findPlaceableSlot(bot) >= 0,
                approach, requester);
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
            return new StandingPlanResult(null, null, StandingPointRefusal.STANDING_NO_REACHABLE);
        }
        // ⭐ `D-520`（改革 ① 主体第一刀）：原来这里是**两条腿**（`planTunnel` + `planEnterTarget`），
        // 现在合成**一条**：把目标交给内核（`GoalAdjacent` = "站到目标格的某一面"，落脚点由 A* 自己找）。
        // 旧 `no_tunnel_standing_point` / `no_reachable_tunnel_standing_point` / `enter_target_unreachable`
        // 三个理由码随之消失（全仓无生产消费者，实测仅本类自己写）；**预算闸门那一半保留**
        // （`enter_target_over_budget` → `approach_over_budget`，见 `planGoalApproach`，`D-076` 不许静默丢）。
        StandingPlanResult goalApproach = planGoalApproach(bot, level, immutableTarget, startFoot, budget);
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
            return new StandingPlanResult(null, null, SearchConclusion.SEARCH_INCOMPLETE);
        }
        BotLog.warn("[MiningPlanner] found_but_unminable target={} direct={} approach={} budget={}",
                immutableTarget.toShortString(), direct.failureReason(),
                goalApproach.failureReason(), budget.describe());
        return new StandingPlanResult(null, null, "found_but_unminable");
    }

    // ============ 模式 A（`R2`＋`R5`）→ 已搬 `reach/StandingPlanSelector`（`①-2b`） ============


    /*
     * ⚠️ 2026-09-29「①-1」（改革 ① 主体 · `DS-5` 解体，`plans §4.2`④ 逐字「跟着 **①** 走」）：
     * **`R8`「归因码」整个搬走了** ⇒ 新家 = `com.dddgn.alice.reach.StandingPointRefusal`：
     *   `STANDING_NO_VALID`（字面量 = `no_valid_standing_point`）·
     *   `STANDING_NO_REACHABLE`（字面量 = `no_reachable_standing_point`）·
     *   `isStandingPointRefusal(reason)` ·
     *   `ADJACENT_NO_REACHABLE`（字面量 = `no_reachable_adjacent_standing_point`，`D-520` 加的第三个码）
     * —— 四件**逐字未改**（名字、字符串字面量、谓词语义全保持原样；`D-460` 的"**只搬包、不改名**"口径）。
     *
     * ⛔ **别把"这里没这几个常量了"读成"站位类归因没了"**：判据照旧生效，本类只是**引用**它
     * （`StandingPointRefusal.…`）。
     * ⛔ 也别在原处放回同名常量 —— 门禁 `rule_search_limit_not_unreachable` 的 `D-528` 牙正是挡它的。
     * 📌 为什么必须搬：它们的消费者在**作业层**（`FishboneJob:750`）与夹具，而"是不是站位类"这件事
     * 被 import 一个**挖掘**规划器来判是**错位**；`reach/` 的定位本来就是「触及站位 / 视线 / 计划」
     * （`D-460`）⇒ 作业层可以只依赖 `reach/`，不必认识 `task/mining/`。
     */

    // ============ 目标级到达：两条腿顺序搜索（改革 ① `D-520` ＋ 批次 1 `1-1b₂` 甲） ============

    /**
     * ⭐⭐ **目标级到达**（`K2` 接线 `D-520` ＋ 批次 1 `1-1b₂`）：把"走到被掩埋的目标旁边"交给**内核**。
     *
     * <p><b>它替谁</b>：替掉旧的两条腿 ——
     * ① 模式 B（旧 `planTunnel`）：{@code StandingPointSelector.tunnelCandidates} 枚举**固定 13 格**
     * （4 面 × {y, y−1} + 正下方），再对每个候选跑一次**全预算** A\*
     * （`MAX_APPROACH_PLANS = 3` 截断之前，真机实测 `candidates=13 planned=13` ⇒ ≈2.4 s/tick，
     * 而它发生在**服务端 tick 线程**上）；
     * ② 兜底（旧 `planEnterTarget`）：以**目标格本身**为终点"破坏进入"。
     * 现在只有两句话：先问 {@link GoalColumnBlocks}（**同列**），拿不出方案再问
     * {@link com.dddgn.alice.pathing.core.search.GoalAdjacent}（**侧面兜底**，Alice 特有形状，
     * `1-0b` 已定性更正）⇒ **落脚点由 A\* 自己找**。
     *
     * <p>⭐⭐ <b>形状 = 「甲 · 顺序两次搜索」（`D-532` §二，用户 2026-09-29 裁定）</b>：
     * <pre>
     *   腿 1（同列）`GoalColumnBlocks.forReach(target, bot.getBlockReach())`
     *     ├ 没结论（`SEARCH_LIMIT`/`PARTIAL`/`GOAL_NOT_LOADED`）⇒ **原样上抛**（⛔ 不许去试腿 2）
     *     ├ 到不了 ⇒ 问腿 2
     *     └ 到了   ⇒ 用这一条
     *   腿 2（侧面）`PathRequest.adjacentApproach`（`GoalAdjacent`）
     *     ├ 没结论 ⇒ 原样上抛；├ 到不了 ⇒ `ADJACENT_NO_REACHABLE`；└ 到了 ⇒ 用这一条
     * </pre>
     * ⇒ **最多 2 次全预算搜索**（旧形状是 13 次）。⛔ 「同列 ∪ 侧面」**不**改成一次复合搜索
     * （Baritone 用的是 `GoalComposite`，`reference/baritone-1.20.1/src/main/java/baritone/process/MineProcess.java:188`）——
     * `D-532` §二 已裁：**对挖掘而言"挖进那一列"本身就是目标**，"先到先得"是**意图**不是缺陷。
     * ⚠️ <b>代价已登记</b>：先到先得**不比较谁更便宜** ⇒ 若真机出现"到得了但绕远／挖了很多不必要的方块"，
     * 那就是这个顺序的病 ⇒ 届时换 `GoalComposite`（**改动可局部化**：两条腿换成一次复合，其余不动）。
     *
     * <p>⭐ <b>两条腿的到达集（逐条实测，⛔ 不夸大）</b>：
     * <ul>
     *   <li><b>腿 1 = 同列</b> {@code x==tx && z==tz && ty-depth <= y <= ty}（`depth = 5`，由**触及几何**推导）
     *       —— 与 Baritone `GoalTwoBlocks`（`depth=1`，
     *       `src/api/java/baritone/api/pathing/goals/GoalTwoBlocks.java:60`）与 `GoalThreeBlocks`
     *       （`depth=2`，`MineProcess.java:310`）**逐字同构**，Alice 只是把深度**参数化**
     *       （偏离已登记在 `GoalColumnBlocks` 类头）；</li>
     *   <li><b>腿 2 = 侧面</b> 曼哈顿 ≤1 且不站目标格、不站它上方 ⇒ **4 个水平邻格 ＋ 正下方 1 格**；</li>
     *   <li>⇒ 两集**只共有 1 格**（{@code target.below()}）—— 兜底时那格已被腿 1 否定，重叠无害；
     *       腿 2 净增的只有那 4 个水平邻格；</li>
     *   <li>⚠️ 腿 1 含**目标格自身**（{@code y == ty}）—— 那是 Baritone 的语义（"破坏并站进去"），
     *       ⛔ 不是笔误；与旧 ②「破坏进入」同源。</li>
     * </ul>
     *
     * <p>⭐ <b>与旧两条腿的关系</b>：<b>能力集逐字相同</b>（{@link PathRequest#MINING_APPROACH_MOVEMENTS}，
     * 含 `BREAK_AND_TRAVERSE`/`BREAK_AND_ENTER`/`PILLAR`/`FALL`/`DOWNWARD`）⇒ 到达判据从
     * "枚举出来的那一格"改成**形状本身的算术**（`D-532` §二：零枚举、零规划期射线）。
     * ⚠️ 但**到达集不再收窄**：`D-520` 时期那句「新到达集 = Baritone 自己的 `GoalAdjacent`、是**收窄**的」
     * 只对**腿 2** 成立；腿 1 把**整列（含 `y−2 … y−K`）**拿回来，正好覆盖旧 `tunnelCandidates`
     * 的"y−1 那一圈竖直落点"所服务的那类目标（`U3`：**必须挖穿才能到 `y−2`**）。
     *
     * <p>⚠️ <b>三条逐字保留的契约</b>：
     * <ol>
     *   <li>{@code P1-b}/{@code P1-d}：**每条腿都先看"有没有结论"再看"到没到"** ——
     *       {@link SearchConclusion#inconclusiveReason(PathPlan)} 非空
     *       （`SEARCH_LIMIT` = 根本没跑 / `PARTIAL` = 跑了没算完 / `GOAL_NOT_LOADED` = 目标区没加载）
     *       ⇒ 原样上抛 {@link SearchConclusion#SEARCH_INCOMPLETE}，⛔ **不许**改写成"到不了"
     *       （否则整体被记成 `found_but_unminable`（**永久理由**）⇒ `MineJob` 把目标写进 `attempted`
     *       永久了结；真机实测过 377 次，取证 `docs/reviews/2026-09-25-mine循环198ms拆解.md`）；
     *       ⚠️ 也**不许**拿"腿 1 没结论"当"同列不行"的理由去试腿 2 ——
     *       那会把「本轮没评价完」偷偷降级成「这个形状到不了」；</li>
     *   <li>**预算闸门**（`D-076`）：代价超过 {@link MiningBudget#maxExtraBreakTicks()} ⇒ **如实拒绝**
     *       （`approach_over_budget`，旧名 `enter_target_over_budget`）——
     *       ⚠️ 它是**单一站点**（在腿结构之外）：只对"已经给出方案的那条腿"判，
     *       ⛔ 不因"另一条腿也许更便宜"而回退（那会让 `approach_over_budget` 的含义随搜索顺序漂）；</li>
     *   <li>失败码 = {@link StandingPointRefusal#ADJACENT_NO_REACHABLE}（**两条腿都到不了**时的那个码；
     *       旧两码合并；实测全仓**无生产消费者**，只有日志与夹具的字面量断言）。
     *       ⚠️ 名字里的 `ADJACENT` 是 `D-520` 时期的遗留 —— 今天它覆盖**两条腿**，改名留在批次 2。</li>
     * </ol>
     */
    private StandingPlanResult planGoalApproach(ServerPlayer bot, ServerLevel level, BlockPos target,
                                    BlockPos startFoot, MiningBudget budget) {
        final String botId = bot.getUUID().toString();
        // ---- 腿 1：同列（`GoalColumnBlocks`）----
        // ⚠️ 深度按**触及几何**推导，实参用**执行期同一个** `bot.getBlockReach()`
        // （`action/MineBlockRunner` 的 `LINE_OF_SIGHT_BLOCKED` / `OUT_OF_REACH` 复核用的就是它）
        // ⇒ "规划说到得了"与"执行够得着"是**同一个口径**。
        // ⛔ 刻意**不**减 `MiningTuning.reachMargin`：那个规划期余量属于**站位挖掘**的调参面
        // （本期退休中，`1-3` 删）⇒ 在这里引它 = 给待删的旋钮**新增一个消费者**，正好反着来。
        String leg = "column";
        // ⚠️ `planPath` 的**第 3 个实参**（`standingFoot`）在它体内**根本没被读**
        // （`StandingPlanSelector.planPath` 只把它转成 `new CorePathPlanner().plan(bot, level, request)`）
        // ⇒ 这里照旧传 `target` 只为与既有调用点同形；⛔ 死参数的清理随 `1-3`（那个文件整份退休）。
        PathPlan path = StandingPlanSelector.planPath(bot, startFoot, target,
                PathRequest.miningApproach(botId, startFoot,
                        GoalColumnBlocks.forReach(target, bot.getBlockReach()), "mining-planner"));
        String inconclusive = SearchConclusion.inconclusiveReason(path);
        if (!inconclusive.isEmpty()) {
            warnInconclusive(leg, target, startFoot, path, inconclusive);
            return new StandingPlanResult(null, null, inconclusive);
        }
        if (!path.reached()) {
            // ---- 腿 2：侧面兜底（`GoalAdjacent`）—— ⛔ 只在"腿 1 确实到不了"时才问 ----
            leg = "side";
            path = StandingPlanSelector.planPath(bot, startFoot, target,
                    PathRequest.adjacentApproach(botId, startFoot, target, null, "mining-planner"));
            inconclusive = SearchConclusion.inconclusiveReason(path);
            if (!inconclusive.isEmpty()) {
                warnInconclusive(leg, target, startFoot, path, inconclusive);
                return new StandingPlanResult(null, null, inconclusive);
            }
            if (!path.reached()) {
                return new StandingPlanResult(null, null, StandingPointRefusal.ADJACENT_NO_REACHABLE);
            }
        }
        // ⭐ 落点 = 路径**实际到达**的那一格，⛔ **不是** `path.goalFoot()`
        // （两条腿的 `goalFoot()` 返回的都是**目标方块本身**，不是脚位；见 `PathPlan.finalFoot()`）。
        // 这与 `MiningPlan` 紧凑构造器里的不变量是**同一条**（那里会再校验一次，抛 IAE 就说明这里传错了）。
        BlockPos standingFoot = path.finalFoot();
        double approachCost = path.totalCost();
        double budgetCost = budget.maxExtraBreakTicks()
                / com.dddgn.alice.pathing.core.search.CostModel.WALK_ONE_BLOCK_TICKS;
        if (approachCost > budgetCost) {
            BotLog.warn("[MiningPlanner] approach_over_budget target={} leg={} cost={} budget={} stand={}",
                    target.toShortString(), leg,
                    String.format(java.util.Locale.ROOT, "%.2f", approachCost),
                    String.format(java.util.Locale.ROOT, "%.2f", budgetCost),
                    standingFoot.toShortString());
            return new StandingPlanResult(null, null, "approach_over_budget");
        }
        LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(
                level, StandingPointSelector.eyeAt(standingFoot), target);
        StandingPointEvaluator.StandingPointScore score =
                StandingPointEvaluator.of(standingFoot, approachCost, approachCost, los);
        BotLog.info("[MiningPlanner] arrival=MINING_APPROACH target={} leg={} startFoot={} stand={} cost={} "
                        + "movements={}",
                target.toShortString(), leg, startFoot.toShortString(), standingFoot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", approachCost), path.movements().size());
        return new StandingPlanResult(new MiningPlan(target, startFoot, standingFoot, path, los,
                MiningPlan.Arrival.MINING_APPROACH, null), score, "");
    }

    /**
     * 「本轮没评价完」的**唯一日志形**（`P1-b`/`P1-d` 的取证读数）：两条腿共用，`leg=` 区分是腿 1 还是腿 2。
     *
     * <p>⚠️ 它**只记日志**：上抛仍由调用点**逐字**写成
     * {@code return new StandingPlanResult(null, null, inconclusive);} ——
     * 门禁 `rule_search_limit_not_unreachable` 咬的就是那一行（⛔ 别把上抛挪进本方法）。
     */
    private static void warnInconclusive(String leg, BlockPos target, BlockPos startFoot,
                                        PathPlan path, String inconclusive) {
        BotLog.warn("[MiningPlanner] arrival=MINING_APPROACH leg={} target={} startFoot={} status={} "
                        + "reason={} searchLimited=true"
                        + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`；"
                        + "`PARTIAL` = 预算烧完只拿到前缀，`P1-d`；`GOAL_NOT_LOADED` = 目标区没加载）",
                leg, target.toShortString(), startFoot.toShortString(), path.status(), inconclusive);
    }


    /*
     * ⚠️ 2026-09-29「搬空第一批」：这里原来是 P5 探针的辅助函数
     * `private static int countStandableFaces(ServerLevel, BlockPos)`（目标 6 面邻格里"现成可站"的个数）。
     * 它与上面那个探针是**同一件东西的两半** ⇒ 同刀删除（唯一的调用者就是那个探针）。
     * ⚠️ 它**不是**"站位枚举"的一部分：`StandingPointSelector.isStandable` 照旧在用，删掉的只是这个**计数**。
     */

    /*
     * ⚠️ 2026-09-29「①-2b」（改革 ① 主体 · `DS-5` 解体）：这里原来装着 **`R2`「A 腿」**（CURRENT 快路径 ＋
     * 候选枚举 ＋ 同一竖列/侧面的分流 ＋ 排序）与 **`R5`「精算 / top-K / 选择」**
     * （`selectBest` / `exactTopK` / `cheaper`）＋ 那个 `planPath` 小工具。
     * **已搬到** {@link com.dddgn.alice.reach.StandingPlanSelector}（`plans §4.2`①：
     * 「CURRENT 快路径 ＋ 候选枚举 ＋ LOS/触及过滤 ＋ 排序 ＋ 最优」→ **`reach/`**）。
     * 依据：它的同族**早就住在 `reach/`**（`StandingPointSelector` / `StandingPointEvaluator` /
     * `LineOfSightChecker` / `MiningTuning` / `MiningPlan`）⇒ 只剩这一段还在 `task/mining/`，
     * 于是 `reach/` 侧的东西反过来被 `task/` 编排着用（`D-460` 的层定位）。
     * ⚠️ **只搬家、不改逻辑**：方法体逐字复制；三处改动**全部**由过层带来（`MiningBudget` 形参 →
     * `boolean collectDrops` ＋ `boolean canPlaceSupport`；`budget.collectDrops()` → `collectDrops`；
     * `findPlaceableSlot(bot) >= 0` → `canPlaceSupport`），见新类的类注释。
     * ⛔ **别在这里放回同名方法，也别留转发壳**：那会让"往哪个站"长出第二处
     * （门禁 `rule_search_limit_not_unreachable` 的 `①-2b` 牙正是挡它的）。
     * 📌 `planPath` 也跟着搬了（它是纯内核转调），仍留在本类的目标腿改调
     * {@link com.dddgn.alice.reach.StandingPlanSelector#planPath} —— 只有一份定义。
     */





    /*
     * ⚠️ 2026-09-29「搬空第三批」：`R6`「掉落承接」**整个搬走了**（改革 ① 主体 · `DS-5` 解体，
     * `plans §4.2`⑤ 逐字「⭐ **独立出来**」）⇒ 新家 = `com.dddgn.alice.reach.DropCatchment`：
     *   `DROP_FALL_SEARCH`（= 8，用户 2026-09-22 裁定）· `dropWouldBeLost(level, target)` ·
     *   `isSameColumn(pos, target)` —— 三件**逐字未改**（只把 `private` 放宽成 `public`；
     *   两段 javadoc 的归属也摆正了：描述谓词的那段原来被夹在常量上面）。
     * ⇒ **本类今天连调用点都没有了** —— `①-2b` 把 A 腿整段搬进 `reach/` 之后，
     * `DropCatchment.dropWouldBeLost(` / `isSameColumn(` 的调用点住在
     * {@link com.dddgn.alice.reach.StandingPlanSelector}（门禁 `rule_support_and_cluster_order`
     * 的断言①c 已跟着改锚到那里）。
     *
     * ⛔ **别把"这里没这几个符号了"读成"垫方块的口径没了"**：判据照旧生效；
     * 门禁 `rule_support_and_cluster_order` 的断言①已**改锚**到新家，并加了"原处不许复活 /
     * 不许留转发壳"的牙。
     * ⛔ 也别把这里当成"可以放回一个转发壳"的位置 —— 那条牙正是挡它的。
     *
     * ⚠️ **`R2` 那一半的待裁问题仍然挂着**（`plans §4.2`⑤ 逐字：**"垫一块"这个动作该由谁做，
     * 是另一个要单独裁的问题**）：`①-2b` 只搬了"选哪条路"的编排（`side`/`below` 两组候选比
     * "放支撑块 ＋ 侧面站位"与"只从正下方挖"），**没有**裁定那个动作的归属。
     */

}
