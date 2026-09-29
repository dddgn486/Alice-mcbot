package com.dddgn.alice.reach;

/**
 * **「到不了站位」这一族的归因码**（`F2` / `D-450` / `D-454`；即 `plans §4.2`④ 的 `R8`）——
 * 三个码的**唯一产地** ＋ "这次失败算不算**站位类**"的**唯一谓词**。
 *
 * <p>为什么它该住在 `reach/`：这三个码说的全是**触及/站位**这件事（`D-460` 给 `reach/` 的定位逐字是
 * 「内核侧**几何层**（触及站位 / 视线 / 计划）」），而它们的消费者在**作业层**
 * （`job/fishbone/FishboneJob.standingFailureCode`）与夹具 ⇒ 让作业层去 import
 * 「`task/mining/` 里的**挖掘**规划器」只为判一句"是不是站位类"，正是 `plans` 反复点名的**错位**。
 *
 * <p>⭐ 2026-09-29（改革 ① 主体 · `①-1`，`D-528`）：本组**逐字**从 `task/mining/MiningPlanner` 搬出
 * （依据 = `plans §4.2`④ 逐字「④「归因码 R8」… **跟着 ① 走**」＋ ① 的新家 = `reach/`）。
 * ⛔ **一个字没改**：常量名、字符串字面量、谓词的语义（含下面那条"`ADJACENT` **刻意不进**谓词"）全部保持原样。
 * 📌 沿用 `D-460` 的落地口径「**只搬包、不改名**」—— 把 `STANDING_` 前缀改掉（如 `NO_VALID`）是**另一刀**的事：
 * 锚在名字上的门禁会**静默失效**（`survey/42 §3.3`），不该混进搬家刀里。
 */
public final class StandingPointRefusal {

    private StandingPointRefusal() {
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
}
