package com.dddgn.alice.reach;

/**
 * 站位选优的**结果载体**：要么拿到一个可用计划（{@link #plan()} 非空），要么只有一条失败理由。
 *
 * <p>⭐ <b>2026-09-29 搬包（改革 ① 主体 · `DS-5` 解体 · `①-2a`）</b>：本 record 原来叫
 * {@code MiningPlanner.Result}（`task/mining/` 的**嵌套** record），现在搬进 `reach/` 成为独立类型。
 * 依据 = `plans §4.2`①「**「选」→ `reach/`**」（它的三个组件的家本来就在这里：
 * {@link MiningPlan} · {@link StandingPointEvaluator.StandingPointScore} · 一个归因串）。
 *
 * <p>⚠️ **逐字搬移**：组件顺序、组件名、访问器语义（`plan()` / `score()` / `failureReason()`）
 * 与 {@link #success()} 的判据**一字未改**；⛔ 它**不是**重命名收窄，而是同一个载体换个住址。
 * ⛔ 别在原处（`MiningPlanner`）留一个同名嵌套 record 或转发壳：那会让"结果载体"长出第二处
 * （{@code J-6}：同一份判据只有一个出处）。
 *
 * <p>⚠️ **为什么它必须住 `reach/`**：生产它的那段逻辑（候选枚举 → 排序 → top-K 精算 → 择优）
 * 就是 `plans §4.2`① 要搬进 `reach/` 的那一件；载体住 `task/` 而逻辑住 `reach/` = 逻辑反过来
 * 依赖上层 ⇒ `check-layer-direction` 断言① 直接红。
 *
 * @param plan          选出来的计划；{@code null} = 本阶段没产生可用计划（见 {@code failureReason}）
 * @param score         选中站位的评分；{@code plan} 为 {@code null} 时同样为 {@code null}
 * @param failureReason 失败归因串；**成功时为 `""`**（⚠️ 不是 {@code null} —— 调用方按
 *                      {@code isEmpty()} 判"腿到底有没有给出理由"，见 `P1-d`/`1.4w`
 *                      的"原样上抛"纪律）
 */
public record StandingPlanResult(MiningPlan plan, StandingPointEvaluator.StandingPointScore score,
                                 String failureReason) {

    /** 规划结果：plan 为空时仅表示当前规划阶段未产生可用计划。 */
    public boolean success() {
        return plan != null;
    }
}
