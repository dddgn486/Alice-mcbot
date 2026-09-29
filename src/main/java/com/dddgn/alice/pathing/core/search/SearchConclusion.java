package com.dddgn.alice.pathing.core.search;

/**
 * ⭐⭐ **搜索结论的诚实读数**（`P1-b` / `P1-d`；红线 **`SEARCH_LIMIT ≠ UNREACHABLE`**）—— **唯一出处**。
 *
 * <p>它只回答**一个问题**：「这一次搜索**有没有得出可达性结论**？」两种"没得出"的形态**都必须**与
 * 「不可达」分开：
 * <ul>
 *   <li>{@link PlanningStatus#SEARCH_LIMIT}：<b>A1 每 tick 总账拒绝</b> ⇒ 这次搜索**根本没跑**
 *       （{@code CorePathPlanner.planInternal} 的 {@code tryAcquire()} 返回 false）⇒ 什么都不知道；</li>
 *   <li>{@link PlanningStatus#PARTIAL}：搜索**跑了、烧光了自己的预算**（`D-388` 的 50 ms），
 *       交出了 best-so-far **前缀**但 {@code reached() == false} ⇒ **没算完**。</li>
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
 * <p><b>⚠️ 本类<u>不</u>回答"算不算真的评价过"</b>：两种形态在这件事上**不同** ——
 * `SEARCH_LIMIT` **不计入**（根本没跑）；`PARTIAL` **计入**（预算真花了）。所以调用点不是一个 `if`
 * 能合并的：本类只回答"结论是否可信"，**不回答**"算不算评价过"（那个计数在调用方，
 * 例如 A 腿的 `planned`）。
 *
 * <p><b>为什么抽成函数</b>（而不是在每个调用点写 {@code status == SEARCH_LIMIT || status == PARTIAL}）：
 * 判据必须能**确定地**红。而 `PARTIAL` 能不能被造出来**取决于外部地形是否已加载** ——
 * `CoarseGoalPrefixCheckTask`（2026-09-22）记过同一条夹具洁净度坑：单跑时目标方向有地形 ⇒
 * 搜索撞未加载边界 ⇒ `PARTIAL` + 前缀；而在 CORE 里同一区域已加载且为空 ⇒ open set 耗尽 ⇒
 * `UNREACHABLE`。⇒ **`PARTIAL` 形态**的行为级复现是环境相关的，不能当判据的主语。
 * 抽成纯函数之后，夹具可以拿**真的 {@link PathPlan} 对象**（{@code PathPlan.partial(...)} /
 * {@code PathPlan.failure(...)} 都是 public 工厂）做**与环境无关**的真值表断言。
 *
 * <h2>⭐ 它为什么住内核侧（2026-09-29 搬包；改革 ① 主体 · `DS-5` 解体「搬空第二批」）</h2>
 *
 * <p>依据 = `plans §4.2`③ 与 `plans §2.2` 的 R7 逐字：
 * <ul>
 *   <li>它描述的是**内核搜索配额**（{@link PlanningStatus}）⇒「放在挖掘包里是**错位**」（R7）；</li>
 *   <li>「⭐ **必须活下来**，落 `reach/` 或内核侧」＋「⚠️ **红线判据**，不许跟着 `MiningPlanner` 一起消失」（`§4.2`③）。</li>
 * </ul>
 * ⇒ 本类原来住在 `task/mining/MiningPlanner` 里（`inconclusive` / `inconclusiveReason` /
 * `SEARCH_INCOMPLETE` 三件），**现在搬到内核侧**：它的两个输入类型（{@link PlanningStatus} /
 * {@link PathPlan}）本来就在本包。
 *
 * <p>⚠️ **只搬、不改语义**：三个成员名**逐字保留**（`inconclusive` / `inconclusiveReason` /
 * `SEARCH_INCOMPLETE`），⛔ 不改判据、⛔ 不改理由码字面量。
 * ⚠️ **同刀必须重锚** `tools/kernel-predicates.py` 的 `rule_search_limit_not_unreachable` ——
 * `plans §2.5` 逐字：那条规则里关于 R7 的那半「**必须活下来**（**只改锚**）」。
 * ⛔ 判据只能是静态门禁 ＋ 编译 ＋ 行为夹具（`task/MiningSearchLimitHonestyCheckTask` 拿**真
 * `PathPlan` 对象**做真值表断言）—— 本刀**不改任何运行行为**。
 *
 * <p>⚠️ **一条已登记的现存不一致**（⛔ 本类**不**负责修）：`job/mine/MineJob` 有**两处**自己写
 * `search_incomplete` 这个**字面量**（`transientFailure` 的 `startsWith` ＋ `shortfallReason` 的返回），
 * 而门禁 `rule_search_limit_not_unreachable` **要求**它那样写（那条断言钉在 `MineJob` 上）。
 * ⇒ 「本类是唯一出处」这句话的**准确范围** = **本类内部**（原话见 `MiningPlanner` 的 tombstone）；
 * `MineJob` 那两处是**同一个字的第二、第三个产地**，要不要收口是**另一件事**（未裁）。
 */
public final class SearchConclusion {

    /**
     * ⭐ 瞬时理由码：**"这次没得出可达性结论"** ⇒ 调用方**不许**把这个目标永久了结
     * （`D-387` 的 `P1-b` ＋ `D-434` 的 `P1-d`）。
     *
     * <p>⚠️ **"唯一出处"的准确范围 = 本包/本类**：本类（及其调用方）**不许**再写这个字符串字面量。
     * `MineJob` 那两处是**已登记的例外**（见类头最后一段），⛔ 不是鼓励。
     */
    public static final String SEARCH_INCOMPLETE = "search_incomplete";

    private SearchConclusion() {
    }

    /**
     * ⭐⭐ **`P1-d`（2026-09-25）：这次搜索**有没有得出可达性结论**。
     *
     * <p>见类头：两种"没得出"（{@link PlanningStatus#SEARCH_LIMIT} = 根本没跑 /
     * {@link PlanningStatus#PARTIAL} = 跑了没算完）**都必须**与「不可达」分开。
     *
     * @return true ⇒ 结论**不可信**，调用方**不许**把它当成"不可达"（红线 `SEARCH_LIMIT ≠ UNREACHABLE`）
     */
    public static boolean inconclusive(PlanningStatus status) {
        return status == PlanningStatus.SEARCH_LIMIT || status == PlanningStatus.PARTIAL;
    }

    /**
     * ⭐⭐ `P1-d`：一条腿（一个 {@link PathPlan}）"**这次到底给出了什么结论**" —— **唯一出处**。
     *
     * @return {@link #SEARCH_INCOMPLETE}（没结论 ⇒ 调用方**不许**当成不可达）；空串（有结论，交回调用方判）
     */
    public static String inconclusiveReason(PathPlan plan) {
        return inconclusive(plan.status()) ? SEARCH_INCOMPLETE : "";
    }
}
